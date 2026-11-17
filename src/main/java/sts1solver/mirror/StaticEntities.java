package sts1solver.mirror;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.orbs.AbstractOrb;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.megacrit.cardcrawl.relics.AbstractRelic;

import sts1solver.util.Log;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B192: makes the mirror own the combat entities that only a STATIC FIELD points at.
 *
 * <p>The static sweep classifies a field by its descriptor ({@code Statics} tables +
 * {@link ModStatics}), and a descriptor cannot tell "a manager singleton" from "the pet this
 * combat owns". A mod that keeps its companion creature in a static (the reported case:
 * TheNecrobinder's Osty) therefore stayed live-shared: the sandbox wrote the live creature's HP,
 * and the player saw their pet heal after pressing "计算".
 *
 * <p>This class answers the question without naming any mod: <b>is this value an engine battle
 * entity, and is it unreachable from the live combat's object graph?</b> A value that is one of the
 * engine bases ({@link com.megacrit.cardcrawl.cards.AbstractCard}, {@link AbstractRelic},
 * {@link AbstractPotion}, {@link AbstractOrb}, {@link AbstractPower},
 * {@link com.megacrit.cardcrawl.monsters.AbstractMonster}/AbstractCreature) and cannot be reached
 * from the player, the monsters, the piles, the action queue or anything hanging off those is
 * owned by nothing but statics -- exactly the shape that leaks. It is registered as a
 * {@link Statics.Kind#CLONE} root for every later capture and fork ({@link Statics#registerDynamic}).
 *
 * <p>Everything else is left alone: a mod's card registry, its icon table and its static scalars
 * are either reachable or not battle entities, so they are not copied. That keeps the blast radius
 * of the rule at "objects the live game is not otherwise using".
 *
 * <p>Cost: one reflective read per mod object-static per capture; the world-graph walk only runs
 * when a value's CLASS changes (a new combat, a pet appearing) or every {@link #WALK_EVERY} calls,
 * and is bounded by {@link #MAX_OBJECTS}/{@link #MAX_DEPTH}. Steady state is ~300 reflective reads
 * (tens of microseconds), against a capture that already clones the whole combat.
 */
public final class StaticEntities {

    private StaticEntities() {
    }

    /** How many captures between unconditional re-walks; a class change walks immediately. */
    private static final int WALK_EVERY = 512;

    /** Bound on the world-graph walk: enough for a full combat, small enough to never stall. */
    private static final int MAX_OBJECTS = 20000;

    /** How deep the walk follows fields (player -> power -> card -> ... is well under this). */
    private static final int MAX_DEPTH = 8;

    /** How often settings.txt is re-read (the player edits it by hand). */
    private static final long SETTINGS_MS = 2000L;

    /**
     * State fields a battle entity's fingerprint looks for, by NAME. A name whitelist is what makes
     * the digest and the watch see the same thing regardless of where in the class hierarchy the
     * field is declared (Osty's HP fields live in an abstract mod base).
     */
    public static final String[] STATE_NAMES = {
            "currentHealth", "maxHealth", "currentBlock", "isDying", "isDead", "isEscaping",
            "halfDead", "lastDamageTaken", "block", "energy", "hp", "maxHp", "gold"};

    private static final Map<Field, String> signatures = new IdentityHashMap<Field, String>();
    private static final Map<Class<?>, Field[]> fieldCache = new IdentityHashMap<Class<?>, Field[]>();
    private static int calls;
    private static int walks;
    private static int registered;
    private static int skippedReachable;
    private static int skippedFinal;
    private static int skippedIgnored;
    private static int lastWalked;
    private static long lastWalkNanos;
    private static long lastLogAt;
    private static String lastSummary = "";

    private static Boolean enabledPinned;
    private static Boolean enabledCached;
    private static long enabledAt;
    private static String ignoreRaw;
    private static long ignoreAt;
    private static Set<String> ignoreKeys = Collections.emptySet();

    // ------------------------------------------------------------------ entry point

    /**
     * Called at the very start of {@link World#captureLive()} (before the spec array is taken, so a
     * new registration is part of this capture). Cheap and silent in the common case.
     */
    public static synchronized void sync() {
        if (!enabled()) {
            return;
        }
        if (World.anyInstalled()) {
            // Never change the spec table while a window is open: install() refuses a stale world,
            // so a registration here would abort the search's current node.
            return;
        }
        List<Field> candidates;
        try {
            candidates = ModStatics.watchCandidates();
        } catch (Throwable t) {
            return;
        }
        if (candidates.isEmpty()) {
            return;
        }
        boolean changed = false;
        Map<Field, String> now = new IdentityHashMap<Field, String>();
        for (int i = 0; i < candidates.size(); i++) {
            Field f = candidates.get(i);
            if (f == null || !Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Object v = read(f);
            String sig = v == null ? "null" : v.getClass().getName();
            now.put(f, sig);
            String was = signatures.get(f);
            if (was == null || !was.equals(sig)) {
                changed = true;
            }
        }
        signatures.clear();
        signatures.putAll(now);
        ++calls;
        if (!changed && (calls % WALK_EVERY) != 0) {
            return;
        }
        walk(candidates);
    }

    private static void walk(List<Field> candidates) {
        long t0 = System.nanoTime();
        Set<Object> reachable;
        try {
            reachable = reachableFromLiveCombat();
        } catch (Throwable t) {
            // The walk is a refinement, not a precondition: without it we would clone statics that
            // may be reachable, which splits identity -- worse than doing nothing. Bail out.
            Log.warnEvery("mirror-entities-walk", 30000L,
                    "mirror entities: cannot walk the live combat graph: " + t);
            return;
        }
        int added = 0;
        List<String> fresh = new ArrayList<String>();
        for (int i = 0; i < candidates.size(); i++) {
            Field f = candidates.get(i);
            if (f == null) {
                continue;
            }
            if (Modifier.isFinal(f.getModifiers())) {
                // A static-final holder cannot be swapped (JDK 21 throws on Field.set). Its value is
                // reported as a boundary instead of being half-mirrored.
                Object v = read(f);
                if ((isBattleEntity(v) || (v == null && isBattleType(f.getType())))
                        && (v == null || !reachable.contains(v))) {
                    ++skippedFinal;
                    String held = v == null ? "declared " + f.getType().getSimpleName()
                            + ", currently null" : v.getClass().getName();
                    Log.warnEvery("mirror-entities-final-" + key(f), 60000L,
                            "mirror entities: static-final " + key(f) + " holds a battle entity"
                                    + " (" + held + ") that nothing else reaches;"
                                    + " it cannot be swapped, so it stays live-shared (known"
                                    + " boundary, B192).");
                }
                continue;
            }
            if (Statics.isCaptured(f)) {
                continue;
            }
            if (ignored(f)) {
                ++skippedIgnored;
                continue;
            }
            Object v = read(f);
            // B192 round 2: the DECLARED TYPE counts even when the value is null right now. A pet
            // field is empty until the summon happens; judging by value alone made "no pet yet" and
            // "a pet nobody can reach" look identical, and the first summon then happened inside a
            // window where the field was still live-shared (StaticWatch caught the write and put it
            // back, which is exactly the leak the user still saw). Owning the field up front means
            // the summon creates a sandbox pet and the live slot stays untouched.
            boolean byType = isBattleType(f.getType());
            if (!byType && !isBattleEntity(v)) {
                continue;
            }
            if (v != null && reachable.contains(v)) {
                ++skippedReachable;
                continue;
            }
            if (Statics.registerDynamic(f, Statics.Kind.CLONE)) {
                ++added;
                ++registered;
                if (fresh.size() < 8) {
                    fresh.add(key(f) + (v == null ? " (declared " + f.getType().getSimpleName()
                            + ", currently null)" : " (" + v.getClass().getSimpleName() + ")"));
                }
            }
        }
        long dt = System.nanoTime() - t0;
        lastWalked = reachable.size();
        lastWalkNanos = dt;
        ++walks;
        lastSummary = "mirror entities: " + added + " static root(s) newly owned, "
                + Statics.dynamicCount() + " dynamic root(s) live, walked " + reachable.size()
                + " object(s) in " + (dt / 1000000L) + "ms";
        long now = System.currentTimeMillis();
        if (added > 0 || now - lastLogAt > 60000L) {
            lastLogAt = now;
            Log.info(lastSummary);
            if (added > 0) {
                Log.info("mirror entities: skipped " + skippedReachable + " reachable, "
                        + skippedFinal + " static-final, " + skippedIgnored + " ignored;"
                        + " these roots are cloned for every capture and fork (B192)");
                Log.info("mirror entities: newly owned " + fresh
                        + (added > fresh.size() ? " (+" + (added - fresh.size()) + " more)" : ""));
            }
        }
    }

    // ------------------------------------------------------------------ classification

    /** The engine bases: every battle entity a static can leak into the live game. */
    public static boolean isBattleEntity(Object v) {
        // B192 round 4: ALL of AbstractCreature, not just monsters and the player. A mod pet is a
        // creature that extends AbstractCreature directly (Necrobinder's Osty does); judging by
        // AbstractMonster made it invisible, the static stayed live-shared, and every simulated
        // heal/summon landed on the live pet the user was looking at.
        return v instanceof com.megacrit.cardcrawl.core.AbstractCreature
                || v instanceof com.megacrit.cardcrawl.monsters.AbstractMonster
                || v instanceof AbstractPlayer
                || v instanceof AbstractPower
                || v instanceof AbstractCard
                || v instanceof AbstractRelic
                || v instanceof AbstractPotion
                || v instanceof AbstractOrb
                || (v != null && hasHealthState(v.getClass()));
    }

    /**
     * B192 round 4: a mod "pet" does not have to extend anything of ours. Anything with the two
     * health fields by name is treated as combat state; the test is cached per class, so the walk
     * pays it once per type, not once per field.
     */
    private static final ClassValue<Boolean> HEALTH_STATE = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            return Boolean.valueOf(scanHealthState(c));
        }
    };

    static boolean hasHealthState(Class<?> c) {
        if (c == null || c.isPrimitive() || c.isArray()) {
            return false;
        }
        try {
            return HEALTH_STATE.get(c).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean scanHealthState(Class<?> c) {
        boolean health = false;
        boolean max = false;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            java.lang.reflect.Field[] fs;
            try {
                fs = k.getDeclaredFields();
            } catch (Throwable t) {
                return false;
            }
            for (int i = 0; i < fs.length; i++) {
                java.lang.reflect.Field f = fs[i];
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Class<?> t = f.getType();
                if (t != int.class && t != Integer.class) {
                    continue;
                }
                if ("currentHealth".equals(f.getName())) {
                    health = true;
                } else if ("maxHealth".equals(f.getName())) {
                    max = true;
                }
            }
            if (health && max) {
                return true;
            }
        }
        return false;
    }

    /** Same test by declared type, for a field whose current value is null. */
    public static boolean isBattleType(Class<?> c) {
        if (c == null || c.isPrimitive() || c.isArray()) {
            return false;
        }
        try {
            return com.megacrit.cardcrawl.core.AbstractCreature.class.isAssignableFrom(c)
                    || hasHealthState(c)
                    || com.megacrit.cardcrawl.monsters.AbstractMonster.class.isAssignableFrom(c)
                    || AbstractPlayer.class.isAssignableFrom(c)
                    || AbstractPower.class.isAssignableFrom(c)
                    || AbstractCard.class.isAssignableFrom(c)
                    || AbstractRelic.class.isAssignableFrom(c)
                    || AbstractPotion.class.isAssignableFrom(c)
                    || AbstractOrb.class.isAssignableFrom(c);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ reachability

    private static final class Node {
        final Object value;
        final int depth;

        Node(Object value, int depth) {
            this.value = value;
            this.depth = depth;
        }
    }

    private static Set<Object> reachableFromLiveCombat() {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        ArrayDeque<Node> queue = new ArrayDeque<Node>();
        AbstractPlayer p = AbstractDungeon.player;
        if (p != null) {
            queue.add(new Node(p, 0));
            addAll(queue, p.relics, 1);
            addAll(queue, p.potions, 1);
            addAll(queue, p.orbs, 1);
            addPile(queue, p.hand, 1);
            addPile(queue, p.drawPile, 1);
            addPile(queue, p.discardPile, 1);
            addPile(queue, p.exhaustPile, 1);
            addPile(queue, p.limbo, 1);
        }
        try {
            if (AbstractDungeon.getMonsters() != null) {
                addAll(queue, AbstractDungeon.getMonsters().monsters, 1);
            }
        } catch (Throwable ignored) {
            // no combat: nothing to walk
        }
        try {
            if (AbstractDungeon.actionManager != null) {
                queue.add(new Node(AbstractDungeon.actionManager, 0));
            }
        } catch (Throwable ignored) {
            // ditto
        }
        while (!queue.isEmpty() && seen.size() < MAX_OBJECTS) {
            Node n = queue.poll();
            Object o = n.value;
            if (o == null || !seen.add(o)) {
                continue;
            }
            if (n.depth >= MAX_DEPTH) {
                continue;
            }
            Class<?> c = o.getClass();
            int d = n.depth + 1;
            if (o instanceof Collection) {
                for (Object e : (Collection<?>) o) {
                    queue.add(new Node(e, d));
                }
                continue;
            }
            if (o instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                    queue.add(new Node(e.getKey(), d));
                    queue.add(new Node(e.getValue(), d));
                }
                continue;
            }
            if (c.isArray()) {
                if (c.getComponentType().isPrimitive()) {
                    continue;
                }
                int len = Array.getLength(o);
                for (int i = 0; i < len; i++) {
                    queue.add(new Node(Array.get(o, i), d));
                }
                continue;
            }
            if (skip(c)) {
                continue;
            }
            Field[] fs = fieldsOf(c);
            for (int i = 0; i < fs.length; i++) {
                try {
                    Object v = fs[i].get(o);
                    if (v != null) {
                        queue.add(new Node(v, d));
                    }
                } catch (Throwable ignored) {
                    // a field we cannot read is simply not part of the graph
                }
            }
        }
        return seen;
    }

    private static void addPile(ArrayDeque<Node> queue, Object group, int depth) {
        if (group != null) {
            queue.add(new Node(group, depth - 1));
        }
    }

    private static void addAll(ArrayDeque<Node> queue, Collection<?> c, int depth) {
        if (c == null) {
            return;
        }
        for (Object e : c) {
            queue.add(new Node(e, depth));
        }
    }

    /** Classes whose internals are plumbing (never combat state) or too big to walk. */
    private static boolean skip(Class<?> c) {
        if (c == Class.class || c.isEnum() || c.isPrimitive()) {
            return true;
        }
        String n = c.getName();
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("sun.")
                || n.startsWith("jdk.") || n.startsWith("com.badlogic.gdx.")
                || n.startsWith("org.lwjgl.") || n.startsWith("com.esotericsoftware.")
                || n.startsWith("org.apache.") || n.startsWith("com.codedisaster.")
                || n.startsWith("it.unimi.");
    }

    private static Field[] fieldsOf(Class<?> c) {
        Field[] cached = fieldCache.get(c);
        if (cached != null) {
            return cached;
        }
        List<Field> out = new ArrayList<Field>();
        Class<?> k = c;
        while (k != null && k != Object.class) {
            Field[] declared = k.getDeclaredFields();
            for (int i = 0; i < declared.length; i++) {
                Field f = declared[i];
                int mods = f.getModifiers();
                if (Modifier.isStatic(mods) || f.isSynthetic()) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    out.add(f);
                } catch (Throwable ignored) {
                    // inaccessible: not walked
                }
            }
            k = k.getSuperclass();
        }
        Field[] result = out.toArray(new Field[0]);
        fieldCache.put(c, result);
        return result;
    }

    // ------------------------------------------------------------------ digest

    /**
     * One digest segment per dynamic clone root, e.g. {@code " | sc=Osty#1a2b3c4d"}. Empty when no
     * static root is owned, so the digest of a vanilla fight is byte-identical to before.
     */
    public static String fingerprintLine() {
        List<Statics.Spec> dyn = Statics.dynamicSpecs();
        if (dyn.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<String>();
        for (int i = 0; i < dyn.size(); i++) {
            Statics.Spec s = dyn.get(i);
            if (s.kind != Statics.Kind.CLONE) {
                continue;
            }
            Object v = sts1solver.reflect.Reflect.get(s.field, null);
            if (v == null) {
                continue;
            }
            parts.add(fingerprint(v));
        }
        if (parts.isEmpty()) {
            return "";
        }
        Collections.sort(parts);
        StringBuilder sb = new StringBuilder(" | sc=");
        for (int i = 0; i < parts.size() && i < 4; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** Class simple name + FNV-1a over the state fields; readable, not a security hash. */
    public static String fingerprint(Object v) {
        long h = 1469598103934665603L;
        String name = v.getClass().getSimpleName();
        if (name.isEmpty()) {
            name = v.getClass().getName();
        }
        h = mix(h, name);
        Field[] fs = fieldsOf(v.getClass());
        int taken = 0;
        for (int n = 0; n < STATE_NAMES.length; n++) {
            for (int i = 0; i < fs.length; i++) {
                if (!fs[i].getName().equals(STATE_NAMES[n])) {
                    continue;
                }
                try {
                    h = mix(h, String.valueOf(fs[i].get(v)));
                } catch (Throwable ignored) {
                    h = mix(h, "?");
                }
                ++taken;
            }
        }
        if (taken == 0) {
            // A power/relic with none of the names above: fall back to its numeric amount/id.
            h = mix(h, powerish(v));
        }
        if (v instanceof AbstractPower) {
            h = mix(h, String.valueOf(((AbstractPower) v).amount));
        }
        return name + '#' + Long.toHexString(h);
    }

    private static String powerish(Object v) {
        try {
            Field f = v.getClass().getField("amount");
            return String.valueOf(f.get(v));
        } catch (Throwable t) {
            return "";
        }
    }

    private static long mix(long h, String s) {
        if (s == null) {
            s = "";
        }
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 1099511628211L;
        }
        h ^= '\u0001';
        h *= 1099511628211L;
        return h;
    }

    // ------------------------------------------------------------------ settings / report

    /** settings.txt {@code mirrorEntityRoots}, default on ({@code off}/{@code 0}/关 disables). */
    public static boolean enabled() {
        if (enabledPinned != null) {
            return enabledPinned.booleanValue();
        }
        long now = System.currentTimeMillis();
        if (enabledCached != null && now - enabledAt < SETTINGS_MS) {
            return enabledCached.booleanValue();
        }
        boolean on = true;
        try {
            Map<String, String> s = settings();
            String v = s.get("mirrorEntityRoots");
            if (v != null) {
                String lv = v.toLowerCase(java.util.Locale.ROOT);
                on = !("off".equals(lv) || "no".equals(lv) || "false".equals(lv) || "0".equals(lv)
                        || "\u5173".equals(lv) || "\u5173\u95ed".equals(lv));
            }
        } catch (Throwable t) {
            on = true;
        }
        enabledCached = Boolean.valueOf(on);
        enabledAt = now;
        return on;
    }

    /** Test hook: pin the switch, null = read settings.txt again. */
    public static synchronized void setEnabledForTest(Boolean value) {
        enabledPinned = value;
        enabledCached = value;
        enabledAt = 0L;
    }

    /** settings.txt {@code mirrorEntityIgnore=owner#field,owner2#field2}; never contains a mod name. */
    private static boolean ignored(Field f) {
        String key = key(f);
        Set<String> keys = ignoreKeys();
        return keys.contains(key);
    }

    private static Set<String> ignoreKeys() {
        long now = System.currentTimeMillis();
        if (ignoreRaw != null && now - ignoreAt < SETTINGS_MS) {
            return ignoreKeys;
        }
        String raw = "";
        try {
            raw = settings().get("mirrorEntityIgnore");
            if (raw == null) {
                raw = "";
            }
        } catch (Throwable ignored) {
            raw = "";
        }
        Set<String> out = new LinkedHashSet<String>();
        String[] parts = raw.split("[,;\\s]+");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        ignoreRaw = raw;
        ignoreAt = now;
        ignoreKeys = out;
        return ignoreKeys;
    }

    private static Map<String, String> settings() {
        Map<String, String> out = new java.util.HashMap<String, String>();
        try {
            File f = new File(sts1solver.SolverMod.dir(), "settings.txt");
            if (!f.isFile()) {
                return out;
            }
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line == null) {
                    continue;
                }
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (Throwable ignored) {
            // missing/unreadable settings: defaults apply
        }
        return out;
    }

    /** One line for the search summary / the debug report. */
    public static synchronized String report() {
        return "mirror entities: " + registered + " static root(s) registered over " + walks
                + " walk(s), " + Statics.dynamicCount() + " live; last walk " + lastWalked
                + " object(s) in " + (lastWalkNanos / 1000000L) + "ms; skipped " + skippedReachable
                + " reachable / " + skippedFinal + " static-final / " + skippedIgnored + " ignored";
    }

    /** The last walk's one-line summary (empty before the first walk). */
    public static synchronized String summary() {
        return lastSummary;
    }

    public static synchronized void resetForTest() {
        signatures.clear();
        fieldCache.clear();
        calls = 0;
        walks = 0;
        registered = 0;
        skippedReachable = 0;
        skippedFinal = 0;
        skippedIgnored = 0;
        lastWalked = 0;
        lastWalkNanos = 0L;
        lastSummary = "";
    }

    private static Object read(Field f) {
        try {
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String key(Field f) {
        return f.getDeclaringClass().getName() + '#' + f.getName();
    }
}
