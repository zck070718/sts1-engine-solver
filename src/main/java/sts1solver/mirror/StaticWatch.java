package sts1solver.mirror;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import sts1solver.util.Log;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The cheap half of P2-62: WATCH the mod statics the mirror does not own, instead of copying them.
 *
 * <p>{@link ModStatics} finds every static field a mod keeps state in and mirrors the mutable
 * scalars. What is left (static-final containers, static references to mutable objects) stays
 * live-shared, because a field descriptor erases the element type and a wrong guess would hand the
 * engine copies of its own singletons. That boundary was only visible as a report line: nothing
 * proved whether any of those fields is actually written during a search.
 *
 * <p>This class answers that question at run time and costs nothing on the hot path. On install it
 * takes a shallow snapshot of a few candidate fields; on uninstall it compares, and if the sandbox
 * wrote through one of them it (a) names the field in the log once, (b) puts the live contents back,
 * and (c) marks the field dirty so every later install watches that one field only. A window is
 * sampled (all candidates) only on the first few installs and then one in {@link #SAMPLE_EVERY}; the
 * dirty set is watched every time. Measured cost per unsampled install is a handful of reflective
 * reads on the dirty set, against a fork that already costs tens of microseconds.
 *
 * <p>What it does NOT do: it does not make sibling forks independent of each other. It proves the
 * leak exists, keeps the live game clean, and names the field that would have to move into
 * {@link Statics#MOD_CONTAINERS} to make the simulation faithful (P2-62 proper).
 */
public final class StaticWatch {

    private StaticWatch() {
    }

    /** Sample every Nth install; the dirty set is watched on every install regardless. */
    private static final int SAMPLE_EVERY = 128;

    /** The first installs are always sampled: a search must not need 128 forks to find a leak. */
    private static final int FIRST_SAMPLES = 4;

    /** How many names a log line carries before it says "+N more". */
    private static final int NAME_LIMIT = 8;

    /**
     * B170: an array with more elements than this is copied with {@code System.arraycopy} and compared
     * with a primitive {@code Arrays.equals} instead of element by element. Measured case (gate S229):
     * a mod keeps its whole 8 MB wav in a static byte[]; boxing eight million elements into an
     * {@code Object[]} for the snapshot and walking them again for the compare cost 582 ms + 280 ms per
     * sampled window, which the player saw as 500-700 ms frames only with that mod installed.
     */
    private static final int MAX_ARRAY_ELEMENTS = 4096;

    private static final class Watch {
        final Field field;
        final String key;
        final boolean isFinal;

        /** Learned: the sandbox has written through this field at least once. */
        boolean dirty;

        /**
         * B192: the field's declared type is an engine battle entity (a pet, a power). Such a value
         * is sampled EVERY window: its whole body is state (HP, block, death flags), so the usual
         * "every 128th install" rule would let the leak run unseen for a whole search.
         */
        final boolean entity;

        Watch(Field f) {
            field = f;
            key = f.getDeclaringClass().getName() + '#' + f.getName();
            isFinal = Modifier.isFinal(f.getModifiers());
            entity = StaticEntities.isBattleType(f.getType());
            try {
                f.setAccessible(true);
            } catch (Throwable ignored) {
                // setAccessible failing is reported on first use, not here
            }
        }
    }

    private static final class Snap {
        /** The static's own value, so a reassignment of a non-final static is caught too. */
        final Object staticValue;
        final int shape;
        final Object body;

        Snap(Object staticValue, int shape, Object body) {
            this.staticValue = staticValue;
            this.shape = shape;
            this.body = body;
        }
    }

    /** Shallow field copy of a plain object: fields and their values, in the same order. */
    private static final class Fields {
        final Field[] fs;
        final Object[] vals;

        Fields(Field[] fs, Object[] vals) {
            this.fs = fs;
            this.vals = vals;
        }
    }

    private static List<Watch> all;
    private static final List<Watch> testWatches = new ArrayList<Watch>();
    private static final Map<Watch, Snap> snaps = new IdentityHashMap<Watch, Snap>();
    private static final Set<String> dirtyKeys = new LinkedHashSet<String>();
    private static final List<String> writtenForTest = new ArrayList<String>();
    private static int installs;
    private static int sampledWindows;
    private static int writes;
    /** B169: writes that only released a reference (set it to null) and were therefore left alone. */
    private static int released;
    private static boolean forceSample;

    // ------------------------------------------------------------------ window hooks

    /** Called at the end of {@link World#install()}: snapshot the candidates for this window. */
    public static synchronized void afterInstall() {
        ++installs;
        boolean sample = forceSample || installs <= FIRST_SAMPLES || (installs % SAMPLE_EVERY) == 0;
        if (sample) {
            ++sampledWindows;
        }
        snaps.clear();
        List<Watch> candidates = all();
        for (int i = 0; i < candidates.size(); i++) {
            Watch w = candidates.get(i);
            if (Statics.isDynamicClone(w.field)) {
                continue;
            }
            if (!sample && !w.dirty && !w.entity) {
                continue;
            }
            try {
                snaps.put(w, snapshot(w));
            } catch (Throwable t) {
                Log.warnEvery("static-watch-snap-" + w.key, 30000L,
                        "cannot snapshot " + w.key + ": " + t);
            }
        }
    }

    /** Called at the start of {@link World#uninstall()}: compare, report, and put the live value back. */
    public static synchronized void beforeUninstall() {
        if (snaps.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<String>();
        int changedNow = 0;
        for (Map.Entry<Watch, Snap> e : snaps.entrySet()) {
            Watch w = e.getKey();
            Snap s = e.getValue();
            if (Statics.isDynamicClone(w.field)) {
                continue;
            }
            try {
                Object live = w.field.get(null);
                if (live == s.staticValue && bodyEquals(live, s)) {
                    continue;
                }
                // B169: a bare reference can also be RELEASED (set to null) by a thread that is not
                // this sandbox. Measured case: a mod's javax.sound LineListener clears its
                // "current clip" static on the audio callback thread while a window happens to be
                // open. Putting the snapshot back would hand the mod a reference to a resource that
                // is already closed: its isPlaying() then answered true forever and the music never
                // came back. A release cannot leak a sandbox object into the live game, so it is left
                // as it is; every other shape is still put back.
                if (live == null && s.staticValue != null) {
                    ++released;
                    Log.warnEvery("static-watch-release-" + w.key, 30000L, "static watch: " + w.key
                            + " was released (set to null) while a window was open; left released."
                            + " Another thread may legitimately clear a static while the sandbox runs,"
                            + " and putting the old value back would hide that (B169).");
                    continue;
                }
                ++changedNow;
                ++writes;
                names.add(w.key + " (" + shapeName(s.shape) + ")");
                if (writtenForTest.size() < 64) {
                    writtenForTest.add(w.key);
                }
                boolean firstTime = !w.dirty;
                w.dirty = true;
                if (firstTime && dirtyKeys.add(w.key)) {
                    Log.warn("static watch: the sandbox wrote mod static " + w.key + " ("
                            + shapeName(s.shape) + ") and the contents were put back; it is now"
                            + " watched on every install. P2-62: this field is live-shared, so a"
                            + " sibling fork can see the write.");
                }
                restore(w, s, live);
            } catch (Throwable t) {
                Log.warnEvery("static-watch-" + w.key, 30000L,
                        "cannot compare/restore " + w.key + ": " + t);
            }
        }
        snaps.clear();
        if (changedNow > 0) {
            Log.warnEvery("static-watch-window", 5000L, "static watch: " + changedNow
                    + " write(s) to live-shared mod statics put back: " + summarize(names));
        }
    }

    /** One line for the search summary / the gates. */
    public static synchronized String report() {
        int candidates = all().size();
        return "static watch: " + candidates + " mod static ref(s), " + installs
                + " install window(s), " + sampledWindows + " sampled, " + writes + " write(s) in "
                + dirtyKeys.size() + " field(s)"
                + (released == 0 ? "" : ", " + released + " release(s) left alone")
                + (dirtyKeys.isEmpty() ? "" : " dirty=" + summarize(new ArrayList<String>(dirtyKeys)));
    }

    // ------------------------------------------------------------------ snapshot / compare / restore

    /** Every non-static field of the class and its bases, resolved once per type. */
    private static final ClassValue<Field[]> INSTANCE_FIELDS = new ClassValue<Field[]>() {
        @Override
        protected Field[] computeValue(Class<?> c) {
            List<Field> all = new ArrayList<Field>();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                Field[] declared;
                try {
                    declared = k.getDeclaredFields();
                } catch (Throwable t) {
                    break;
                }
                for (int i = 0; i < declared.length; i++) {
                    Field f = declared[i];
                    if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        all.add(f);
                    } catch (Throwable ignored) {
                        // a field we cannot touch is simply not compared
                    }
                }
            }
            return all.toArray(new Field[0]);
        }
    };

    private static Snap snapshot(Watch w) throws Exception {
        Object v = w.field.get(null);
        if (v == null) {
            return new Snap(null, 0, null);
        }
        if (v instanceof Collection) {
            return new Snap(v, 1, new ArrayList<Object>((Collection<?>) v));
        }
        if (v instanceof Map) {
            return new Snap(v, 2, new LinkedHashMap<Object, Object>((Map<?, ?>) v));
        }
        Class<?> c = v.getClass();
        if (c.isArray()) {
            int n = Array.getLength(v);
            if (n > MAX_ARRAY_ELEMENTS) {
                // B170: one bulk copy of the same component type -- no Object[] and no boxing.
                Object copy = Array.newInstance(c.getComponentType(), n);
                System.arraycopy(v, 0, copy, 0, n);
                return new Snap(v, 5, copy);
            }
            Object[] copy = new Object[n];
            for (int i = 0; i < n; i++) {
                copy[i] = Array.get(v, i);
            }
            return new Snap(v, 3, copy);
        }
        if (isPlainObject(c)) {
            // B192 round 4: collect the WHOLE hierarchy before choosing. The old 96-field cap cut
            // the walk off at the deepest class, so a pet's health fields (declared far down
            // AbstractCreature's list) were never even seen by the name pass below -- the watch
            // "restored" a handful of timer/texture fields and left the HP at whatever the sandbox
            // wrote. Selection still caps at 32, collection no longer does.
            Field[] all = INSTANCE_FIELDS.get(c);
            // B192: state fields first, BY NAME and across the hierarchy. A mod pet's HP fields are
            // declared in an abstract base class; plain declaration order buried them behind ~30
            // texture/timer fields and the 32-field cap dropped them, which is why the watch could
            // never see the pet move. Names first, then whatever fits as ordinary context.
            List<Field> fs = new ArrayList<Field>(32);
            Set<Field> picked = new HashSet<Field>();
            for (int n = 0; n < StaticEntities.STATE_NAMES.length && fs.size() < 32; n++) {
                String name = StaticEntities.STATE_NAMES[n];
                for (int i = 0; i < all.length && fs.size() < 32; i++) {
                    Field f = all[i];
                    if (f.getName().equals(name) && picked.add(f)) {
                        fs.add(f);
                    }
                }
            }
            for (int i = 0; i < all.length && fs.size() < 32; i++) {
                Field f = all[i];
                if (picked.add(f)) {
                    fs.add(f);
                }
            }
            Object[] vals = new Object[fs.size()];
            for (int i = 0; i < fs.size(); i++) {
                try {
                    vals[i] = fs.get(i).get(v);
                } catch (Throwable ignored) {
                    vals[i] = null;
                }
            }
            return new Snap(v, 4, new Fields(fs.toArray(new Field[0]), vals));
        }
        // A type we cannot compare cheaply (an engine manager, a texture): only the reference itself
        // is watched, which still catches a sandbox that swaps it.
        return new Snap(v, 0, null);
    }

    private static boolean bodyEquals(Object live, Snap s) {
        switch (s.shape) {
            case 1: {
                Collection<?> now = (Collection<?>) live;
                List<?> was = (List<?>) s.body;
                if (now.size() != was.size()) {
                    return false;
                }
                java.util.Iterator<?> a = now.iterator();
                java.util.Iterator<?> b = was.iterator();
                while (a.hasNext() && b.hasNext()) {
                    if (!eq(a.next(), b.next())) {
                        return false;
                    }
                }
                return true;
            }
            case 2: {
                Map<?, ?> now = (Map<?, ?>) live;
                Map<?, ?> was = (Map<?, ?>) s.body;
                if (now.size() != was.size()) {
                    return false;
                }
                for (Map.Entry<?, ?> e : was.entrySet()) {
                    if (!now.containsKey(e.getKey()) || !eq(now.get(e.getKey()), e.getValue())) {
                        return false;
                    }
                }
                return true;
            }
            case 3: {
                Object[] was = (Object[]) s.body;
                if (Array.getLength(live) != was.length) {
                    return false;
                }
                for (int i = 0; i < was.length; i++) {
                    if (!eq(Array.get(live, i), was[i])) {
                        return false;
                    }
                }
                return true;
            }
            case 5:
                // B170: a big array, compared in bulk (primitive Arrays.equals, no boxing).
                return sameElements(live, s.body);
            case 4: {
                Fields f = (Fields) s.body;
                for (int i = 0; i < f.fs.length; i++) {
                    Object now;
                    try {
                        now = f.fs[i].get(live);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (!eq(now, f.vals[i])) {
                        return false;
                    }
                }
                return true;
            }
            default:
                return true;
        }
    }

    private static void restore(Watch w, Snap s, Object live) {
        if (live != s.staticValue) {
            if (w.isFinal) {
                Log.warnEvery("static-watch-final-" + w.key, 30000L, w.key
                        + " was reassigned to a sandbox object and is final, so the live reference"
                        + " cannot be put back");
                return;
            }
            try {
                w.field.set(null, s.staticValue);
            } catch (Throwable t) {
                Log.warnEvery("static-watch-put-" + w.key, 30000L,
                        "cannot put the live value of " + w.key + " back: " + t);
            }
            return;
        }
        try {
            switch (s.shape) {
                case 1: {
                    @SuppressWarnings("unchecked")
                    Collection<Object> now = (Collection<Object>) live;
                    List<?> was = (List<?>) s.body;
                    now.clear();
                    now.addAll(was);
                    break;
                }
                case 2: {
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> now = (Map<Object, Object>) live;
                    Map<?, ?> was = (Map<?, ?>) s.body;
                    now.clear();
                    now.putAll(was);
                    break;
                }
                case 3: {
                    Object[] was = (Object[]) s.body;
                    if (Array.getLength(live) == was.length) {
                        for (int i = 0; i < was.length; i++) {
                            Array.set(live, i, was[i]);
                        }
                    } else {
                        Log.warnEvery("static-watch-len-" + w.key, 30000L,
                                "the array behind " + w.key + " changed length; contents left as is");
                    }
                    break;
                }
                case 5: {
                    int n = Array.getLength(s.body);
                    if (Array.getLength(live) == n) {
                        System.arraycopy(s.body, 0, live, 0, n);
                    } else {
                        Log.warnEvery("static-watch-len-" + w.key, 30000L,
                                "the array behind " + w.key + " changed length; contents left as is");
                    }
                    break;
                }
                case 4: {
                    Fields f = (Fields) s.body;
                    for (int i = 0; i < f.fs.length; i++) {
                        if (Modifier.isFinal(f.fs[i].getModifiers())) {
                            continue;
                        }
                        try {
                            f.fs[i].set(live, f.vals[i]);
                        } catch (Throwable ignored) {
                            // one field we cannot put back is reported by the compare next time
                        }
                    }
                    break;
                }
                default:
                    break;
            }
        } catch (Throwable t) {
            Log.warnEvery("static-watch-restore-" + w.key, 30000L,
                    "cannot put the contents of " + w.key + " back: " + t);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static boolean isPlainObject(Class<?> c) {
        String n = c.getName();
        return !(n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("com.badlogic.")
                || n.startsWith("org.") || n.startsWith("com.esotericsoftware."));
    }

    /** B170: do two arrays of the same component type hold the same elements, without boxing any? */
    private static boolean sameElements(Object live, Object was) {
        if (live instanceof byte[]) {
            return java.util.Arrays.equals((byte[]) live, (byte[]) was);
        }
        if (live instanceof short[]) {
            return java.util.Arrays.equals((short[]) live, (short[]) was);
        }
        if (live instanceof int[]) {
            return java.util.Arrays.equals((int[]) live, (int[]) was);
        }
        if (live instanceof long[]) {
            return java.util.Arrays.equals((long[]) live, (long[]) was);
        }
        if (live instanceof char[]) {
            return java.util.Arrays.equals((char[]) live, (char[]) was);
        }
        if (live instanceof float[]) {
            return java.util.Arrays.equals((float[]) live, (float[]) was);
        }
        if (live instanceof double[]) {
            return java.util.Arrays.equals((double[]) live, (double[]) was);
        }
        if (live instanceof boolean[]) {
            return java.util.Arrays.equals((boolean[]) live, (boolean[]) was);
        }
        return java.util.Arrays.equals((Object[]) live, (Object[]) was);
    }

    private static boolean eq(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
    }

    private static String shapeName(int shape) {
        switch (shape) {
            case 1:
                return "collection";
            case 2:
                return "map";
            case 3:
                return "array";
            case 4:
                return "object";
            case 5:
                return "large array";
            default:
                return "reference";
        }
    }

    private static String summarize(List<String> names) {
        if (names.size() <= NAME_LIMIT) {
            return names.toString();
        }
        return names.subList(0, NAME_LIMIT) + " and " + (names.size() - NAME_LIMIT) + " more";
    }

    private static List<Watch> all() {
        if (all != null) {
            return all;
        }
        List<Watch> out = new ArrayList<Watch>();
        Set<String> owned = new HashSet<String>();
        try {
            owned.addAll(Statics.mirrorKeys());
        } catch (Throwable ignored) {
            // no mirror table yet: everything is a candidate
        }
        List<Field> candidates;
        try {
            candidates = ModStatics.watchCandidates();
        } catch (Throwable t) {
            candidates = new ArrayList<Field>();
        }
        for (int i = 0; i < candidates.size(); i++) {
            Field f = candidates.get(i);
            String key = f.getDeclaringClass().getName() + '#' + f.getName();
            if (owned.contains(key)) {
                // The mirror already owns this one (MOD_CONTAINERS). Watching it would report the
                // mirror's own restore as a sandbox write.
                continue;
            }
            if (Statics.isDynamicClone(f)) {
                // B192 round 5: a dynamic CLONE root holds the mirror's own clone inside a window,
                // so neither its value nor its body is live state: watching it would report our
                // install as a sandbox write and put the live reference back mid-window.
                continue;
            }
            out.add(new Watch(f));
        }
        out.addAll(testWatches);
        all = out;
        return out;
    }

    // ------------------------------------------------------------------ gates

    /** Registers a field the gate controls, and turns sampling on (B105 gate S156). */
    public static synchronized void watchForTest(Field f) {
        testWatches.add(new Watch(f));
        all = null;
        forceSample = true;
    }

    /**
     * B109 stage 2: the fields the sandbox has actually written (the calibration candidates).
     *
     * <p>Only dirty fields: one that was never written cannot be changing a combat result, and
     * probing every mod static would cost the player time for nothing.
     */
    public static synchronized java.util.List<java.lang.reflect.Field> dirtyFields() {
        java.util.List<java.lang.reflect.Field> out =
                new java.util.ArrayList<java.lang.reflect.Field>();
        java.util.List<Watch> candidates = all();
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).dirty) {
                out.add(candidates.get(i).field);
            }
        }
        return out;
    }

    /** Test hook (S162): pretend the sandbox wrote this field, so the calibration sees a candidate. */
    public static synchronized void markDirtyForTest(java.lang.reflect.Field f) {
        java.util.List<Watch> candidates = all();
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).field == f) {
                candidates.get(i).dirty = true;
                return;
            }
        }
        Watch w = new Watch(f);
        w.dirty = true;
        testWatches.add(w);
        all = null;
    }

    /** Keys the sandbox was seen writing since the last {@link #resetForTest()}. */
    public static synchronized List<String> writtenForTest() {
        return new ArrayList<String>(writtenForTest);
    }

    /** How many windows were fully sampled (proves the sampling switch is wired, not just idle). */
    public static synchronized int sampledForTest() {
        return sampledWindows;
    }

    /**
     * B170 gate S229: the shape the last install used for a watched field ("not sampled" when the
     * field was not part of this window). Lets a gate prove a big array is snapshotted as one array
     * instead of an {@code Object[]} with one boxed element per slot.
     */
    public static synchronized String shapeForTest(Field f) {
        for (Map.Entry<Watch, Snap> e : snaps.entrySet()) {
            if (e.getKey().field == f) {
                return shapeName(e.getValue().shape);
            }
        }
        return "not sampled";
    }

    /**
     * B187 gate S255: the reference this window snapshotted for a watched field, or
     * {@code "not sampled"} when the field was not part of it. Proves the audio stand-in is never
     * what the watch records -- otherwise uninstall would write the stand-in into the real game.
     */
    public static synchronized Object snapshotValueForTest(Field f) {
        for (Map.Entry<Watch, Snap> e : snaps.entrySet()) {
            if (e.getKey().field == f) {
                return e.getValue().staticValue;
            }
        }
        return "not sampled";
    }

    /** B169 gate S228: how many bare references were released during a window and left released. */
    public static synchronized int releasedForTest() {
        return released;
    }

    public static synchronized void resetForTest() {
        all = null;
        testWatches.clear();
        snaps.clear();
        dirtyKeys.clear();
        writtenForTest.clear();
        installs = 0;
        sampledWindows = 0;
        writes = 0;
        released = 0;
        forceSample = false;
    }
}
