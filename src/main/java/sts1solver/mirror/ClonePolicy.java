package sts1solver.mirror;

import java.lang.reflect.Field;

/**
 * Which objects a combat mirror shares with the live game, and which it copies.
 *
 * <p>The rule is by ROLE, not by convenience:
 *
 * <ul>
 *   <li><b>Copied</b> -- anything combat logic can read or write: creatures, cards, powers, relics,
 *       potions, orbs, stances, action queues, RNGs, and the mutable <em>values</em> those hold
 *       (including {@code Color}, which is a mutable field holder, not a resource).</li>
 *   <li><b>Shared</b> -- resources that must not be duplicated and are never part of combat
 *       semantics: OpenGL textures, fonts, sprite batches, audio, spine skeletons, log4j loggers,
 *       localization data, and presentation-only objects (VFX, most UI screens) whose fields combat
 *       code does not branch on.</li>
 * </ul>
 *
 * <p>Getting this wrong in the "copied" direction is safe but slow; wrong in the "shared" direction
 * is a silent leak into the live game. So the presentation list is explicit and each entry says why.
 */
public final class ClonePolicy {

    private ClonePolicy() {
    }

    /**
     * True when objects of class {@code c} must be shared by reference rather than copied.
     *
     * <p>Every rule below depends only on the object's CLASS, so the answer is memoised per class
     * with {@link ClassValue}: the cloner asks this question once per object in the graph (tens of
     * thousands of times per fork) and the raw version walked a chain of {@code instanceof} tests
     * plus six {@code String.startsWith} per call.
     */
    /**
     * Diagnostic switch for A/B measurement (P1-2d-2): true restores the pre-optimisation code
     * paths (no per-class/per-field memoisation, reflective array access). Only the cost-fork probe
     * flips it; the mod itself never sets it, and it defaults to false.
     */
    public static volatile boolean legacy = Boolean.getBoolean("sts1solver.clone.legacy");

    public static boolean share(Object o, Class<?> c) {
        if (legacy) {
            return shareClass(c);
        }
        return SHARE.get(c).booleanValue();
    }

    private static final ClassValue<Boolean> SHARE = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            return Boolean.valueOf(shareClass(c));
        }
    };

    /** The uncached rule table. Everything here is a function of {@code c} alone. */
    private static boolean shareClass(Class<?> c) {
        if (String.class.isAssignableFrom(c) || Number.class.isAssignableFrom(c)
                || Boolean.class.isAssignableFrom(c) || Character.class.isAssignableFrom(c)
                || Enum.class.isAssignableFrom(c) || Class.class.isAssignableFrom(c)) {
            return true;
        }
        if (java.util.Locale.class.isAssignableFrom(c) || java.util.UUID.class.isAssignableFrom(c)
                || java.util.regex.Pattern.class.isAssignableFrom(c)
                || java.io.File.class.isAssignableFrom(c)) {
            return true;
        }
        // ANY visual effect: shared, whatever package defines it. The package prefix below covers
        // vanilla; a mod's effect class outside it would otherwise be COPIED -- and a copy is
        // allocated without its constructor, so fields like MoveNameEffect.msg stay null while
        // isDone stays false. Rendering such an object nulls out inside GlyphLayout and CRASHES the
        // game, which is exactly what happened on the first real end-turn.
        if (com.megacrit.cardcrawl.vfx.AbstractGameEffect.class.isAssignableFrom(c)) {
            return true;
        }
        String n = c.getName();
        if (n.startsWith("java.lang.reflect.") || n.startsWith("java.lang.invoke.")) {
            return true;
        }
        if (n.startsWith("java.lang.Thread") || n.startsWith("java.lang.ClassLoader")) {
            return true;
        }
        if (n.startsWith("java.util.concurrent.") || n.startsWith("java.util.logging.")) {
            return true;
        }
        if (n.startsWith("java.io.") || n.startsWith("java.nio.") || n.startsWith("java.awt.")) {
            return true;
        }
        // Resources: duplicating a Texture would create a second GL handle for the same image, and
        // mutating the copy would then desynchronise from the atlas. Combat never branches on them.
        if (n.startsWith("com.badlogic.gdx.graphics.") && !n.equals("com.badlogic.gdx.graphics.Color")) {
            return true;
        }
        if (n.equals("com.badlogic.gdx.graphics.Color")) {
            // NOT shared: cards and powers mutate their tint/transparency fields in place, and a
            // shared Color is exactly the kind of aliasing that leaks a value into the live card.
            return false;
        }
        if (n.startsWith("com.badlogic.gdx.audio.") || n.startsWith("com.badlogic.gdx.files.")
                || n.startsWith("com.badlogic.gdx.net.") || n.startsWith("com.badlogic.gdx.utils.Timer")) {
            return true;
        }
        if (n.startsWith("org.lwjgl.") || n.startsWith("com.esotericsoftware.spine.")) {
            return true;
        }
        // THE CREATURE TINT IS NOT PRESENTATION. It is a mutable value holder (TintEffect) that
        // combat code WRITES: DamageAction / PoisonLoseHpAction / RegenAction / DarkOrbEvokeAction
        // all call target.tint.changeColor(...), and AbstractCreature.update() lerps it every frame.
        // Every creature carries one, so sharing it meant a simulated hit or a mod hook recoloured
        // the LIVE player the player is looking at (reported as "while calculating the character
        // turns green"), and the colour stayed because nothing resets it. Copy it like any other
        // combat value.
        if (n.equals("com.megacrit.cardcrawl.vfx.TintEffect")) {
            return false;
        }
        // The bob offset is presentation too, and unlike the effects above it is NOT an
        // AbstractGameEffect: the "vfx/" rule below therefore shared it, so every sandbox creature
        // drove the LIVE intent badge. The search pumps room.update() on each won node
        // (PostCombatLoot) while the delta proxy hands out Driver.FAST_DELTA (20s), so ONE pump
        // moved the shared timer by 20 * speed(4) = 80 rad (~12.7 whole bobs) -- reported as
        // "the intent number bobs faster while it is calculating" (user m18398, B166). Measured by
        // gate S223 before this rule: 8 pumped frames advanced the LIVE timer by exactly 640 rad.
        if (n.equals("com.megacrit.cardcrawl.vfx.BobEffect")) {
            return false;
        }
        // Presentation-only: the four AbstractDungeon effect lists are captured as containers and
        // emptied every step, so the effects inside them are never read by combat logic.
        if (n.startsWith("com.megacrit.cardcrawl.vfx.")) {
            return true;
        }
        // Localization tables are immutable loaded data; copying them would duplicate strings only.
        if (n.startsWith("com.megacrit.cardcrawl.localization.")) {
            return true;
        }
        // Screens are UI. The two SELECTION screens are the exception: a card that asks the player
        // to pick opens one, and combat logic reads its selection groups, so those are copied.
        // BUTTONS ARE NOT PRESENTATION. EndTurnButton carries isHidden/enabled/isGlowing, which
        // combat code toggles (Disable/EnableEndTurnButtonAction, hide()). Sharing it let a
        // simulated turn hide the LIVE End Turn button, and it stayed hidden after the solve --
        // the reported "the next-turn button disappeared after F8".
        if (n.startsWith("com.megacrit.cardcrawl.ui.buttons.")) {
            return false;
        }
        // PANELS ARE NOT PRESENTATION EITHER. DrawPilePanel / DiscardPilePanel / ExhaustPanel carry
        // isHidden and targetX, which combat code toggles (hideCombatPanels / showCombatPanels).
        // Sharing them let a simulated turn hide the LIVE pile panels -- the reported "after F8 the
        // draw and discard pile counts vanished". Copy them like the buttons.
        if (n.startsWith("com.megacrit.cardcrawl.ui.panels.")) {
            return false;
        }
        // The two end-of-fight screens are opened by the engine itself when a simulated branch wins
        // or dies. Sharing the object meant the sandbox could animate the LIVE death / reward screen;
        // copying it keeps every branch's screen private.
        if (n.equals("com.megacrit.cardcrawl.screens.DeathScreen")
                || n.equals("com.megacrit.cardcrawl.screens.rewards.CombatRewardScreen")) {
            return false;
        }
        // THE IN-COMBAT CARD CHOICE SCREEN IS COMBAT STATE, NOT PRESENTATION.
        // "Discovery" (and every "choose one" card) opens CardRewardScreen with customCombatOpen /
        // chooseOneOpen, which WRITES the object: rewardGroup, discoveryCard, hasTakenAll, draft,
        // discovery, chooseOne, header. Sharing it let a simulated Discovery card open the LIVE
        // pick screen -- the reported "after F8 a card reward screen popped up although I never
        // played the fight out". The sandbox needs its own copy so answering a simulated pick
        // cannot touch the live one. Same for the banner that screen animates.
        if (n.equals("com.megacrit.cardcrawl.screens.CardRewardScreen")
                || n.equals("com.megacrit.cardcrawl.ui.DynamicBanner")) {
            return false;
        }
        // B213 (user m00005): THE CAREER RECORD IS NOT COMBAT STATE. CharStat sits under
        // screens.* below, i.e. it was SHARED, so every fork wrote the LIVE counters: a simulated
        // death ran incrementDeath() / incrementPlayTime() straight on the player's own object
        // (fields -> pref.put -> flush). PlayerPrefs only redirects the Prefs object, never those
        // in-memory fields, so the dirty numbers stayed in the live game and the next real save
        // flushed them into preferences\STSData<character> -- the reported "after a fight the stats
        // screen shows more runs and more play time". The numbers are plain ints/longs, so copying
        // costs one allocation and cannot change a search result (nothing in sts1solver reads it).
        if (n.equals("com.megacrit.cardcrawl.screens.stats.CharStat")) {
            return false;
        }
        if (n.startsWith("com.megacrit.cardcrawl.screens.") || n.startsWith("com.megacrit.cardcrawl.ui.")) {
            return !isSelectionScreen(n);
        }
        // SAVE FILE HOLDERS ARE SHARED, NOT COPIED. A character's Prefs carries progress
        // (ENEMY_KILL, wins, ascension) and CharStat keeps the same reference; a COPY would carry the
        // real filepath, so a simulated kill would flush the player's live save file (B25: the
        // Vagabond counter went 1898 -> 8013 in one afternoon of gate runs). Sharing keeps one object
        // for the live player and every fork, so PlayerPrefs can redirect its filepath once.
        if (n.equals("com.megacrit.cardcrawl.helpers.Prefs")) {
            return true;
        }
        // B134 (user m07482): the run's MetricData was shared on purpose (it is not combat state),
        // but it is WRITTEN by the engine: every simulated death (the DeathScreen constructor) and
        // every simulated fight end (AbstractRoom.endBattle) appends a damage_taken record. Shared,
        // those records landed in the LIVE run's metrics, and the mirror carried the growing object
        // into every node of the search tree. It is a plain data holder (ints, Strings, ArrayLists of
        // HashMaps), so it is safe to copy: each window gets its own record, cleared at install.
        if (n.equals("com.megacrit.cardcrawl.metrics.MetricData")) {
            return false;
        }
        if (n.startsWith("com.megacrit.cardcrawl.audio.")
                || n.startsWith("com.megacrit.cardcrawl.integrations.")
                || n.startsWith("com.megacrit.cardcrawl.metrics.")
                || n.startsWith("com.megacrit.cardcrawl.saveAndContinue.")) {
            return true;
        }
        if (n.startsWith("org.apache.logging.log4j.") || n.startsWith("javax.")) {
            return true;
        }
        if (n.startsWith("basemod.devcommands.") || n.startsWith("basemod.ModPanel")) {
            return true;
        }
        // Fonts, cursors, input: engine plumbing.
        if (n.startsWith("com.megacrit.cardcrawl.core.GameCursor")
                || n.startsWith("com.megacrit.cardcrawl.helpers.Hitbox")) {
            return false;
        }
        return false;
    }

    private static boolean isSelectionScreen(String n) {
        return n.equals("com.megacrit.cardcrawl.screens.select.GridCardSelectScreen")
                || n.equals("com.megacrit.cardcrawl.screens.select.HandCardSelectScreen");
    }

    /**
     * Fields that must be LINKED to the live object rather than copied.
     *
     * <p>{@code MapRoomNode} is the one real case: it is the combat room's container, but it also
     * holds the map graph (parents/edges) and its own animation effects. Copying the graph would
     * copy the whole map for every search node; and the node itself must NOT be shared, or the
     * combat room inside it is shared too -- which is exactly the leak that let a sandbox action
     * damage the live monster.
     */
    public static boolean shareField(Field f) {
        if (legacy) {
            return shareFieldUncached(f);
        }
        Boolean cached = SHARE_FIELD.get(f);
        if (cached == null) {
            cached = Boolean.valueOf(shareFieldUncached(f));
            SHARE_FIELD.put(f, cached);
        }
        return cached.booleanValue();
    }

    /** Memoised per field: the cloner asks this once per field of every object it copies. */
    private static final java.util.Map<Field, Boolean> SHARE_FIELD =
            new java.util.concurrent.ConcurrentHashMap<Field, Boolean>();

    private static boolean shareFieldUncached(Field f) {
        String owner = f.getDeclaringClass().getName();
        if (owner.equals("com.megacrit.cardcrawl.map.MapRoomNode")) {
            String n = f.getName();
            return n.equals("parents") || n.equals("edges") || n.equals("fEffects");
        }
        if (owner.equals("com.megacrit.cardcrawl.screens.stats.CharStat")) {
            // B213: the run history is READ-ONLY display data, and copying CharStat must not drag it
            // along. The constructor fills the list with one RunData per file under
            // runs\<slot>_<class>\ (60 files on this machine, 140 KB); a fork that deep-copied them
            // would pay ~90 us per fork on a 233 us player clone -- ~2 s on an 8.3 s search (+25%).
            // Nothing outside that constructor ever writes the list, so sharing it is safe.
            return "runs".equals(f.getName());
        }
        return false;
    }



    /**
     * Never walked into. These own the whole game (the run, the save file, the render pipeline);
     * copying one would mean copying a dungeon per search node.
     *
     * <p>Deliberately NOT {@code com.megacrit.cardcrawl.map.} (the map node is a combat root) and
     * deliberately NOT all of {@code com.badlogic.gdx} (its {@code utils.Array} is a MUTABLE
     * container the game uses for state; sharing it would alias two worlds).
     */
    public static boolean denyRecurse(Class<?> c) {
        if (legacy) {
            return denyRecurseUncached(c);
        }
        return DENY_RECURSE.get(c).booleanValue();
    }

    /** Memoised per class; also a function of the class alone, like {@link #share}. */
    private static final ClassValue<Boolean> DENY_RECURSE = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            return Boolean.valueOf(denyRecurseUncached(c));
        }
    };

    private static boolean denyRecurseUncached(Class<?> c) {
        // mutable value holders first: Color lives under com.badlogic.gdx.graphics (which is denied
        // below so the cloner never walks into textures), and denying it made every card's Color
        // SHARED between the sandbox and the live fight. A simulated card play then repainted the
        // live cards -- the reported "cards turn black after F8".
        if (c == com.badlogic.gdx.graphics.Color.class) {
            return false;
        }
        String n = c.getName();
        return n.startsWith("com.megacrit.cardcrawl.dungeons.")
                || n.startsWith("com.megacrit.cardcrawl.saveAndContinue.")
                || n.startsWith("com.megacrit.cardcrawl.desktop.")
                || n.startsWith("com.badlogic.gdx.graphics.")
                || n.startsWith("com.badlogic.gdx.audio.")
                || n.startsWith("com.badlogic.gdx.files.")
                || n.startsWith("com.badlogic.gdx.scenes.")
                || n.startsWith("com.badlogic.gdx.backends.")
                || n.startsWith("org.lwjgl.");
    }
}