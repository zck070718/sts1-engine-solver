package sts1solver.mirror;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * The process-global fields that hold combat state, expressed as a table so a mirror can swap the
 * whole world in and out.
 *
 * <p>Combat state is NOT all reachable from {@code AbstractDungeon.player}: the game keeps RNG
 * streams, the action manager, the turn counter and the energy gate in <em>static</em> fields, and a
 * few relics cache per-combat flags the same way. A mirror that ignores those either reads the live
 * game's numbers or writes into them.
 *
 * <p>Four kinds:
 * <ul>
 *   <li>{@code CLONE} -- a reference to a combat object graph; deep-copied per mirror.</li>
 *   <li>{@code CONTAINER} -- a process-global list/map whose <em>contents</em> are state
 *       ({@code CardCrawlGame.metricData}, the effect lists, tip counters). Copied by value, and
 *       installed as a throwaway container so the live object never receives a write.</li>
 *   <li>{@code MUTABLE_CONTAINER} -- the same idea for a {@code static final} container we cannot
 *       swap: JDK 21 refuses {@code Field.set} on a static final ({@code
 *       FieldAccessorImpl.throwFinalFieldIllegalAccessException}). The contents are deep-copied per
 *       mirror, installed by rewriting the shared object IN PLACE, and the live contents are
 *       snapshotted at install and put back on uninstall.</li>
 *   <li>{@code VALUE} -- a scalar, enum, String or {@code Color} holder. Colors are written back
 *       IN PLACE, because other objects keep a reference to the same Color instance.</li>
 *   <li>{@code PREF} -- a {@code Prefs} save file. The object is shared (the engine's own call
 *       sites do not hold references we could swap), but its {@code filepath} is redirected to
 *       {@link SandboxPrefs} while installed and its {@code data} map is replaced by a copy, so a
 *       simulated card's {@code flush()} cannot reach the player's save.</li>
 * </ul>
 */
public final class Statics {

    public enum Kind { CLONE, CONTAINER, MUTABLE_CONTAINER, VALUE, COLOR, PREF }

    public static final class Spec {
        public final Class<?> owner;
        public final String name;
        public final Field field;
        public Kind kind;
        /**
         * Clone roots that must not be written back: the object a simulated card OPENED stays in the
         * sandbox and the live static keeps pointing at the live object.
         *
         * <p>Read-back exists to carry post-step state (HP, powers, RNG) into the live world. A UI
         * screen is not state the live game needs from a simulation -- and writing the sandbox's copy
         * back is exactly how "I only pressed F8 and a card choice screen appeared" happens.
         */
        public boolean discardOnReadBack;

        /** Precomputed: label() sits inside World.fork, i.e. thousands of times per second. */
        private final String label;

        Spec(Class<?> owner, String name, Field field, Kind kind) {
            this.owner = owner;
            this.name = name;
            this.field = field;
            this.kind = kind;
            this.label = owner.getSimpleName() + "." + name;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return kind + " " + label();
        }
    }

    /** Combat object graphs: one clone per mirror, and the thing the search actually branches on. */
    private static final Object[][] ROOTS = {
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "player"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "actionManager"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "currMapNode"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "gridSelectScreen"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "handCardSelectScreen"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "overlayMenu"},
            // Screens a simulated card can OPEN are cloned, not merely restored by reference.
            // Discovery and the "Potion of <type>" potions call CardRewardScreen.customCombatOpen on
            // the screen OBJECT: rewardGroup, discoveryCard, hasTakenAll and the private
            // discovery/chooseOne flags all change in place. A VALUE spec only restores the static
            // reference, so with the real object shared those writes survived the mirror -- which is
            // how a simulated win popped the real post-combat card screen. Owning a copy makes the
            // open, the answer and the close entirely local to the sandbox.
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "cardRewardScreen"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "dynamicBanner"},
            // RNG: every stream is captured, not just the ones combat visibly uses. A simulated
            // shuffle that consumes shuffleRng must not advance the live run's stream.
            //
            // That intent was NOT met until 2026-11-02: java.util.Random keeps its position in an
            // AtomicLong, ClonePolicy shares java.util.concurrent.* by reference, so every "clone"
            // here pointed at the LIVE seed. Searching advanced the real run's streams and sibling
            // forks drew from one shared sequence. Cloner now copies a Random by stream position.
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "monsterRng"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "mapRng"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "eventRng"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "merchantRng"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "cardRng"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "treasureRng"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "relicRng"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "potionRng"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "monsterHpRng"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "aiRng"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "shuffleRng"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "cardRandomRng"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "miscRng"},
            // The global libGDX RNG behind MathUtils.random()/randomBoolean(). Not a Megacrit field,
            // so nothing else sweeps it: 533 random() call sites in the base jar (monster hit
            // jitter, CardGroup.random, PummelDamageAction, the vanilla characters) and 14 classes in
            // Narmaya alone. A plain non-final static, so it swaps like any other root.
            {com.badlogic.gdx.math.MathUtils.class, "random"},
            // Not a list: a single accumulating MetricData record. It is cloned rather than
            // swapped as a container, so a simulated combat cannot append to the live run's metrics.
            {com.megacrit.cardcrawl.core.CardCrawlGame.class, "metricData"},
            // B26: the screen shake the player sees while a search runs.
            //
            // CardCrawlGame.screenShake is ONE process-wide ScreenShake object holding private
            // duration/x/intensity fields. AbstractCreature.decrementBlock calls
            // CardCrawlGame.screenShake.shake(MED, SHORT, false) whenever a hit is absorbed by
            // block (before the damage is even applied) and ShakeScreenAction.update() does the
            // same, both through the static reference. The old shell sweep parked this field as a
            // VALUE spec, which only saves and restores the REFERENCE: the sandbox kept writing
            // into the live object, so every blocked hit inside the search re-armed the live
            // shake and CardCrawlGame.update(viewport) really shook the window for the whole
            // search. Owning a copy makes the shake sandbox-local.
            {com.megacrit.cardcrawl.core.CardCrawlGame.class, "screenShake"},
    };

    /** Process-global containers whose CONTENTS are state. */
    private static final Object[][] CONTAINERS = {
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "effectList"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "effectsQueue"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "topLevelEffects"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "topLevelEffectsQueue"},
            {com.megacrit.cardcrawl.helpers.DrawMaster.class, "drawList"},
            {com.megacrit.cardcrawl.helpers.TipTracker.class, "tips"},
    };

/**
     * Mod-owned containers whose CONTENTS are combat state, mirrored by content.
     *
     * <p>Not reachable for {@link ModStatics}, which only mirrors mutable scalars. Most are
     * {@code static final} (JDK 21 refuses {@code Field.set} on those); the two {@code recyclecards}
     * entries are plain statics but are listed here anyway so one mechanism covers both, and because
     * rewriting a list in place is the only way to keep identity with the live objects.
     *
     * <p>Adjudicated one at a time from {@code tools/audit/static_refs.py} output; the commentary on
     * each row says what breaks without it.
     */
    private static final String[][] MOD_CONTAINERS = {
            // CaiNongMod's GetInside keeps the two cards it took ("下次打出「搞里头」时打出上次选择的两张牌").
            // Left shared, a search that played GetInside cleared the live list and left sandbox card
            // objects in it -- and the game's own clear ran against the live list mid-search (m25378).
            {"CaiNongMod.helpers.ModHelper", "cards_Inside"},
            // ninja / ElainaMod "recycle" piles: a card parked here comes back later in the fight.
            // Shared, a simulated recycle either ate the live card or handed the sandbox a card the
            // live game already owned (found by the static-refs audit, 2026-11-02).
            {"demoMod.ninjaMod", "recyclecards"},
            {"ElainaMod.modcore.ElainaMod", "recyclecards"},
            // aristocrat: cards flagged "retain until played" this combat, and the silk curtains seeded
            // into the opening hand (a per-combat Set that decides the first turn's hand size).
            {"aristocrat.util.TemporaryRetainHelper", "retainUntilPlayedCards"},
            {"aristocrat.patches.SilkCurtainStartTurnPatch", "curtainsInOpeningHand"},
            // ArlequinAndante (basicmod): the trap cycle the next turns draw from, and the "until
            // played" discount bookkeeping -- both are per-combat, both were live-shared.
            {"basicmod.util.TrapCycle", "offered"},
            {"basicmod.util.TrapCycle", "remaining"},
            {"basicmod.cards.UntilPlayedDiscount", "originals"},
            // A fixture in this jar holding the same shape as cards_Inside, so the path stays gated
            // on installs that do not enable CaiNongMod (gate S142), the way ModProbeCounter keeps
            // the mod-scalar sweep gated (gate S77). Nothing in production reads it.
            {"demoMod.probe.ModProbeCards", "HELD"},
    };

    /**
     * Save files. {@code UnlockTracker.markCardAsSeen} puts a key into {@code seenPref.data} and
     * flushes it to disk; the same is true for relics, bosses and achievements. The sandbox redirects
     * the filepath and swaps in a copy of the data, then restores both on uninstall.
     */
    private static final Object[][] PREFS = {
            {com.megacrit.cardcrawl.unlock.UnlockTracker.class, "seenPref"},
            {com.megacrit.cardcrawl.unlock.UnlockTracker.class, "relicSeenPref"},
            {com.megacrit.cardcrawl.unlock.UnlockTracker.class, "bossSeenPref"},
            {com.megacrit.cardcrawl.unlock.UnlockTracker.class, "achievementPref"},
            {com.megacrit.cardcrawl.unlock.UnlockTracker.class, "unlockPref"},
            {com.megacrit.cardcrawl.unlock.UnlockTracker.class, "betaCardPref"},
            {com.megacrit.cardcrawl.unlock.UnlockTracker.class, "unlockProgress"},
    };

    /** Unlock lists/maps the game rewrites while a run is simulated (never part of a real run). */
    private static final Object[][] UNLOCK_BOOKKEEPING = {
            {com.megacrit.cardcrawl.unlock.UnlockTracker.class, "unlockReqs"},
            {com.megacrit.cardcrawl.unlock.UnlockTracker.class, "lockedCards"},
            {com.megacrit.cardcrawl.unlock.UnlockTracker.class, "lockedRelics"},
            {com.megacrit.cardcrawl.unlock.UnlockTracker.class, "lockedCharacters"},
            {com.megacrit.cardcrawl.unlock.UnlockTracker.class, "lockedLoadouts"},
    };

    /** Color holders: keyed by field, written back in place so aliases stay aliased. */
    private static final Object[][] COLORS = {
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "fadeColor"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "sourceFadeColor"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "topGradientColor"},
            {com.megacrit.cardcrawl.dungeons.AbstractDungeon.class, "botGradientColor"},
    };

    private static Spec[] specs;
    private static String report = "";
    private static boolean loggedModContainers;

    private Statics() {
    }

    /**
     * B109 (stage 1): fields registered at run time, appended after the built-in table.
     *
     * <p>The built-in table is a compile-time list; a mod field can only be added while the game is
     * already running. Registration is only legal BEFORE a world is captured (a world owns a copy of
     * the spec array, so a later registration would silently be missing from it -- see
     * {@link World#install()}).
     */
    private static final List<Spec> dynamicSpecs = new ArrayList<Spec>();

    /** Bumped by every registration/reset; a world remembers the generation it was captured in. */
    private static int dynamicGeneration;

    /**
     * Adds one field to the mirror at run time (B109 stage 1).
     *
     * <p>Not the classifier: this only makes "the mirror owns this field now" possible. Whether a
     * field SHOULD be isolated is decided by experiment (stage 2), because a field descriptor cannot
     * tell combat state from a registry.
     *
     * @return true when this call added it, false when it was already mirrored or is not static
     */
    public static synchronized boolean registerDynamic(java.lang.reflect.Field f, Kind kind) {
        if (f == null || kind == null) {
            return false;
        }
        if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
            return false;
        }
        String key = f.getDeclaringClass().getName() + '#' + f.getName();
        Spec[] cur = specs();
        for (int i = 0; i < cur.length; i++) {
            if (key.equals(cur[i].owner.getName() + '#' + cur[i].name)) {
                return false;
            }
        }
        for (int i = 0; i < dynamicSpecs.size(); i++) {
            Spec s = dynamicSpecs.get(i);
            if (key.equals(s.owner.getName() + '#' + s.name)) {
                return false;
            }
        }
        try {
            f.setAccessible(true);
        } catch (Throwable t) {
            sts1solver.util.Log.warn("statics: cannot make " + key + " accessible: " + t);
            return false;
        }
        dynamicSpecs.add(new Spec(f.getDeclaringClass(), f.getName(), f, kind));
        specs = null;
        ++dynamicGeneration;
        sts1solver.util.Log.info("statics: registered " + key + " as " + kind
                + " at run time (" + dynamicSpecs.size() + " dynamic, generation "
                + dynamicGeneration + ")");
        return true;
    }

    /** B109 stage 2: removes a run-time registration again (used by the calibration A/B test). */
    public static synchronized boolean unregisterDynamic(java.lang.reflect.Field f) {
        if (f == null) {
            return false;
        }
        String key = f.getDeclaringClass().getName() + '#' + f.getName();
        for (int i = 0; i < dynamicSpecs.size(); i++) {
            Spec s = dynamicSpecs.get(i);
            if (key.equals(s.owner.getName() + '#' + s.name)) {
                dynamicSpecs.remove(i);
                specs = null;
                ++dynamicGeneration;
                return true;
            }
        }
        return false;
    }

    /** B192: the run-time registrations, in registration order (a copy; callers must not edit). */
    public static synchronized java.util.List<Spec> dynamicSpecs() {
        return new java.util.ArrayList<Spec>(dynamicSpecs);
    }

    /** B109: how many run-time registrations are live. */
    public static synchronized int dynamicCount() {
        return dynamicSpecs.size();
    }

    /** B109: the registration generation a world must match to be installable. */
    public static synchronized int generation() {
        return dynamicGeneration;
    }

    /** Test hook (S161): drop every run-time registration so the suite returns to its baseline. */
    public static synchronized void resetDynamicForTest() {
        dynamicSpecs.clear();
        specs = null;
        ++dynamicGeneration;
    }

    /** Built once, on first use, because class literals initialise game classes. */
    public static synchronized Spec[] specs() {
        if (specs != null) {
            return specs;
        }
        List<Spec> out = new ArrayList<Spec>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        int staticColors = 0;

        for (Object[] row : ROOTS) {
            add(out, seen, (Class<?>) row[0], (String) row[1], Kind.CLONE);
        }
        for (Object[] row : CONTAINERS) {
            add(out, seen, (Class<?>) row[0], (String) row[1], Kind.CONTAINER);
        }
        for (Object[] row : COLORS) {
            add(out, seen, (Class<?>) row[0], (String) row[1], Kind.COLOR);
        }
        int prefCount = 0;
        for (Object[] row : PREFS) {
            if (add(out, seen, (Class<?>) row[0], (String) row[1], Kind.PREF)) {
                prefCount++;
            }
        }
        // Unlock bookkeeping: the lists and requirement map are rewritten by unlock() while a
        // simulation runs. They are sandbox-local and must never be carried back into the live run.
        for (Object[] row : UNLOCK_BOOKKEEPING) {
            add(out, seen, (Class<?>) row[0], (String) row[1], Kind.CONTAINER);
        }
        // Every mutable static Color in the game. These are VALUE HOLDERS, mutated in place all over
        // the engine (tints, glows, frame colours); a mirror that leaves them shared means a
        // simulated card can repaint the live UI. This is the "cards turn black" class of bug: the
        // static was never in the table, so nothing swapped it and nothing restored it.
        for (java.lang.reflect.Field f : RenderState.staticColorTargets()) {
            if (add(out, seen, f.getDeclaringClass(), f.getName(), Kind.COLOR)) {
                staticColors++;
            }
        }
        // The generated sweep: every mutable static scalar in the shipped game. Curation was tried
        // and failed (EnergyPanel.totalCount was missing from the hand-written list, and that is the
        // field AbstractCard.hasEnoughEnergy() gates card costs on). Generated is not optional.
        int generated = 0;
        for (Object[] row : GeneratedStaticTable.SCALARS) {
            Class<?> owner = (Class<?>) row[0];
            String name = (String) row[1];
            if (isAudioOrEnginePlumbing(owner, name)) {
                continue;
            }
            if (add(out, seen, owner, name, Kind.VALUE)) {
                generated++;
            }
        }
        // Shell statics: EVERY mutable static field of AbstractDungeon and CardCrawlGame.
        //
        // This is the fix for "F8 and suddenly the death screen appears". A sandbox player that dies
        // runs AbstractPlayer.damage, which assigns AbstractDungeon.deathScreen = new DeathScreen(...)
        // -- a LIVE static that was never in the mirror, so the object survived uninstall and the
        // game rendered a real death screen over a living player. Restoring the whole shell class
        // closes that whole category, not just this one field.
        int shell = 0;
        Class<?>[] shellClasses = {
                com.megacrit.cardcrawl.dungeons.AbstractDungeon.class,
                com.megacrit.cardcrawl.core.CardCrawlGame.class
        };
        for (Class<?> shellClass : shellClasses) {
            java.lang.reflect.Field[] fields = shellClass.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                java.lang.reflect.Field f = fields[i];
                int mods = f.getModifiers();
                if (!java.lang.reflect.Modifier.isStatic(mods)
                        || java.lang.reflect.Modifier.isFinal(mods)) {
                    continue;
                }
                if (isShellPlumbing(f.getType())) {
                    continue;
                }
                if (add(out, seen, shellClass, f.getName(), Kind.VALUE)) {
                    shell++;
                }
            }
        }

        // Mod-owned static-final containers we know hold combat state (see MOD_CONTAINERS). Resolved
        // reflectively and skipped when the mod is not loaded, so the table can name a mod that is
        // not installed.
        int modContainers = 0;
        StringBuilder mirroredContainers = new StringBuilder();
        java.util.Set<String> mirroredContainerKeys = new java.util.LinkedHashSet<String>();
        for (int i = 0; i < MOD_CONTAINERS.length; i++) {
            Class<?> owner = ModStatics.resolve(MOD_CONTAINERS[i][0]);
            if (owner != null
                    && add(out, seen, owner, MOD_CONTAINERS[i][1], Kind.MUTABLE_CONTAINER)) {
                modContainers++;
                mirroredContainerKeys.add(MOD_CONTAINERS[i][0] + '#' + MOD_CONTAINERS[i][1]);
                mirroredContainers.append(mirroredContainers.length() == 0 ? "" : ", ")
                        .append(MOD_CONTAINERS[i][0]).append('#').append(MOD_CONTAINERS[i][1]);
            }
        }
        // Says out loud which mod card memories are mirrored, once per process: without this line a
        // player has no way to tell "the mod keeps its picks in a static final list we handle" from
        // "that list is still shared with the sandbox".
        if (modContainers > 0 && !loggedModContainers) {
            loggedModContainers = true;
            sts1solver.util.Log.info("mirror: mod combat container(s) mirrored by content: "
                    + mirroredContainers);
        }
        // Mod-owned statics (m11229): every mod is accepted, so a mod's own globals have to be
        // sandboxed as well. Scalars only -- see ModStatics for the stated boundary.
        int modStatics = 0;
        for (Object[] row : ModStatics.rows()) {
            if (add(out, seen, (Class<?>) row[0], (String) row[1], Kind.VALUE)) {
                modStatics++;
            }
        }
        // Says out loud what the mod sweep found and what it cost: the number is the honest answer
        // to "does this really cover the mods I have loaded?".
        sts1solver.util.Log.info(ModStatics.report(mirroredContainerKeys));

        for (Spec sp : out) {
            if (sp.kind == Kind.CLONE && ("cardRewardScreen".equals(sp.name)
                    || "dynamicBanner".equals(sp.name) || "screenShake".equals(sp.name))) {
                // B26: a shake started inside the search is presentation, not combat state. Reading
                // it back would shake the player's window the moment the search ends.
                sp.discardOnReadBack = true;
            }
            if (sp.kind == Kind.CLONE && "metricData".equals(sp.name)) {
                // B134 (user m07482): the run's metric record is not combat state. AbstractRoom
                // .endBattle() appends one damage_taken record per fight -- including every fight a
                // search simulates -- so reading it back grew the record inside the search tree and
                // made every fork deep-copy it. The sandbox owns its copy (ROOTS) and starts empty
                // each window (World.install).
                sp.discardOnReadBack = true;
            }
            if (sp.kind == Kind.CONTAINER
                    && "com.megacrit.cardcrawl.unlock.UnlockTracker".equals(sp.owner.getName())) {
                sp.discardOnReadBack = true;
            }
            if (sp.kind == Kind.VALUE
                    && "com.megacrit.cardcrawl.dungeons.AbstractDungeon".equals(sp.owner.getName())
                    && ("screen".equals(sp.name) || "isScreenUp".equals(sp.name))) {
                // B81 (m22687): a simulated death opens DeathScreen inside the sandbox and the engine
                // writes AbstractDungeon.screen = DEATH. Reading that back put "a screen is open" into
                // the next install window -- for the whole rest of the search, on every node -- which
                // made InputMute skip the cursor 50k times per search and log each one.
                // Which screen is up is the live game's business, not combat state: never carry it back.
                sp.discardOnReadBack = true;
            }
        }
        // B109: run-time registrations come last and never displace a built-in spec.
        for (int i = 0; i < dynamicSpecs.size(); i++) {
            Spec sp = dynamicSpecs.get(i);
            String key = sp.owner.getName() + '#' + sp.name;
            if (seen.add(key)) {
                out.add(sp);
            }
        }
        specs = out.toArray(new Spec[out.size()]);
        scannedColors = staticColors;
        StringBuilder sb = new StringBuilder();
        sb.append("statics: ").append(specs.length).append(" specs (")
                .append(ROOTS.length).append(" roots, ")
                .append(CONTAINERS.length).append(" containers, ")
                .append(prefCount).append(" prefs, ")
                .append(COLORS.length).append(" named colors + ")
                .append(staticColorsCount()).append(" scanned colors, ")
                .append(generated).append(" generated scalars, ")
                .append(shell).append(" shell statics, ")
                .append(modStatics).append(" mod statics, ")
                .append(modContainers).append(" mod containers)");
        report = sb.toString();
        return specs;
    }

    /**
     * Volume/mute fields are excluded on purpose: the driver zeroes {@code Settings.SOUND_VOLUME}
     * for the duration of a simulated play, and a mirror install that restored the live value would
     * un-mute the simulation and play thousands of card sounds.
     */
    /** Threads, loggers, class objects and locks cannot be moved into a sandbox meaningfully. */
    private static boolean isShellPlumbing(Class<?> type) {
        String n = type.getName();
        return type == Class.class || type == Thread.class
                || java.util.logging.Logger.class.isAssignableFrom(type)
                || org.apache.logging.log4j.Logger.class.isAssignableFrom(type)
                || java.util.concurrent.locks.Lock.class.isAssignableFrom(type)
                || n.startsWith("java.lang.ref.");
    }

    private static boolean isAudioOrEnginePlumbing(Class<?> owner, String name) {
        String cn = owner.getName();
        if (cn.startsWith("com.badlogic.gdx.") || cn.startsWith("org.lwjgl.")) {
            return true;
        }
        return name.contains("VOLUME") || name.contains("MUTED") || name.contains("AUDIO")
                || name.contains("BGM") || name.contains("SFX");
    }

    /** Every static field this mirror installs, as owner#field keys (used by the sweep gate). */
    public static java.util.List<String> mirrorKeys() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        Spec[] all = specs();
        for (int i = 0; i < all.length; i++) {
            out.add(all[i].owner.getName() + "#" + all[i].name);
        }
        return out;
    }

    private static boolean add(List<Spec> out, java.util.Set<String> seen, Class<?> owner,
                               String name, Kind kind) {
        String key = owner.getName() + '#' + name;
        if (!seen.add(key)) {
            return false;
        }
        Field f = sts1solver.reflect.Reflect.find(owner, name);
        if (f == null) {
            return false;
        }
        if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
            return false;
        }
        out.add(new Spec(owner, name, f, kind));
        return true;
    }

    private static int scannedColors = -1;

    private static int staticColorsCount() {
        return scannedColors < 0 ? 0 : scannedColors;
    }

    public static String report() {
        specs();
        return report;
    }

    /**
     * B192 round 5: true when the mirror owns this field as a dynamically registered CLONE root.
     * StaticWatch must not treat such a field as live-shared: the value it sees during a window is
     * the mirror's own clone, so "the sandbox wrote it" is the install, not a leak.
     */
    public static synchronized boolean isDynamicClone(Field f) {
        for (int i = 0; i < dynamicSpecs.size(); i++) {
            Spec s = dynamicSpecs.get(i);
            if (s.field == f) {
                return s.kind == Kind.CLONE;
            }
        }
        return false;
    }

    /** The roots, for the leak check to name what it is deliberately ignoring. */
    public static boolean isCaptured(Field f) {
        for (Spec s : specs()) {
            if (s.field == f) {
                return true;
            }
        }
        return false;
    }
}