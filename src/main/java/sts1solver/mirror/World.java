package sts1solver.mirror;

import sts1solver.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A complete, isolated copy of one combat: the process-global statics plus the cloned object graphs
 * they point at.
 *
 * <p>This is the "sandbox a real engine" half of the design. A World can be <b>installed</b> (its
 * values are written into the real static fields, so the REAL methods of the game operate on it),
 * the engine can be driven, and then it is <b>uninstalled</b> (the live values are written back).
 * The live game therefore never sees a simulated play, and one search can hold many worlds at once --
 * which a rewind-based design cannot do, because it only ever has one state.
 *
 * <p>Installation is strictly nested and single-threaded. That is not a limitation to work around:
 * the game's combat code reads these statics directly, so two installed worlds would be two
 * definitions of the same variable.
 */
public final class World {

    private final Statics.Spec[] specs;
    /** B109: the registration generation this world was captured in; a stale one cannot install. */
    private final int staticsGeneration;
    private Object[] cells;
    private Object[] live;
    private boolean installed;
    private String digest;
    /** B182: this world's copy of the card glow queues has already been emptied. */
    private boolean glowReset;

    private World(Statics.Spec[] specs) {
        this.specs = specs;
        this.staticsGeneration = Statics.generation();
        this.cells = new Object[specs.length];
    }

    // ------------------------------------------------------------------ capture

    /** Mirrors whatever the process-global statics currently hold (the live combat, normally). */
    public static World captureLive() {
        // B192: register "battle entity that only statics point at" as a CLONE root BEFORE the spec
        // array is taken, so this capture already owns it. No-op on every capture that changes
        // nothing (one reflective read per mod object-static).
        StaticEntities.sync();
        Statics.Spec[] specs = Statics.specs();
        World w = new World(specs);
        Cloner cloner = new Cloner();
        if (cloneCost.size() > 64) {
            cloneCost.clear();
        }
        for (int i = 0; i < specs.length; i++) {
            Statics.Spec s = specs[i];
            Object v = sts1solver.reflect.Reflect.get(s.field, null);
            switch (s.kind) {
                case CLONE:
                    w.cells[i] = cloner.copy(v);
                    break;
                case CONTAINER:
                    w.cells[i] = copyContainer(cloner, v);
                    break;
                case MUTABLE_CONTAINER:
                    // A static final list: the reference cannot change, so the ELEMENTS have to be
                    // this world's own objects -- a shared card would let two branches rewrite each
                    // other's state.
                    w.cells[i] = deepContainer(cloner, v);
                    break;
                case COLOR:
                    w.cells[i] = copyColor(v);
                    break;
                case PREF:
                    // Shared object by design: install() redirects its filepath and swaps its data
                    // map, so no per-fork copy of the ~400-entry seen-card map is needed.
                    w.cells[i] = v;
                    break;
                default:
                    w.cells[i] = v;
                    break;
            }
        }
        return w;
    }

    /** Deep copy of this world, preserving identity across roots. The search's branch primitive. */
    /** Per-kind timings for the last fork, so a fork's cost can be attributed instead of guessed. */
    public static long tClone;
    public static long tContainer;
    public static long tColor;
    /** Per-root clone time and count, so the expensive part of a fork can be named. */
    public static final java.util.Map<String, long[]> cloneCost =
            new java.util.LinkedHashMap<String, long[]>();

    public static String cloneCostReport() {
        java.util.List<java.util.Map.Entry<String, long[]>> list =
                new java.util.ArrayList<java.util.Map.Entry<String, long[]>>(cloneCost.entrySet());
        java.util.Collections.sort(list, new java.util.Comparator<java.util.Map.Entry<String, long[]>>() {
            public int compare(java.util.Map.Entry<String, long[]> a,
                               java.util.Map.Entry<String, long[]> b) {
                return Long.compare(b.getValue()[0], a.getValue()[0]);
            }
        });
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size() && i < 4; i++) {
            sb.append(i == 0 ? "" : " ").append(list.get(i).getKey())
                    .append('=').append(list.get(i).getValue()[0] / 1000000L).append("ms/")
                    .append(list.get(i).getValue()[1]).append("x");
        }
        return sb.toString();
    }

    public World fork() {
        World w = new World(specs);
        Cloner cloner = new Cloner();
        for (int i = 0; i < specs.length; i++) {
            Statics.Spec s = specs[i];
            Object v = cells[i];
            switch (s.kind) {
                case CLONE:
                    long tc = System.nanoTime();
                    w.cells[i] = cloner.copy(v);
                    long dt = System.nanoTime() - tc;
                    tClone += dt;
                    String key = s.label();
                    long[] acc = cloneCost.get(key);
                    if (acc == null) {
                        acc = new long[2];
                        cloneCost.put(key, acc);
                    }
                    acc[0] += dt;
                    acc[1]++;
                    break;
                case CONTAINER:
                    long tk = System.nanoTime();
                    w.cells[i] = copyContainer(cloner, v);
                    tContainer += System.nanoTime() - tk;
                    break;
                case MUTABLE_CONTAINER:
                    w.cells[i] = deepContainer(cloner, v);
                    break;
                case COLOR:
                    long tl = System.nanoTime();
                    w.cells[i] = ((float[]) v) == null ? null : ((float[]) v).clone();
                    tColor += System.nanoTime() - tl;
                    break;
                case PREF:
                    // Redirected per install, never per fork: a 400-key map copy per node would cost
                    // more than the state it protects.
                    w.cells[i] = v;
                    break;
                default:
                    w.cells[i] = v;
                    break;
            }
        }
        w.digest = digest;
        return w;
    }

    // ------------------------------------------------------------------ install

    public boolean isInstalled() {
        return installed;
    }

    private static World installedInstance;

    /** B110 gate hook: make the next {@link #uninstall()} throw before it finishes. */
    private static boolean failNextUninstall;

    public static void failNextUninstallForTest() {
        failNextUninstall = true;
    }

    /** True when some world is currently installed (a leaked one would corrupt the live game). */
    public static boolean anyInstalled() {
        return installedInstance != null;
    }

    /**
     * Restores whatever world is currently installed, if any.
     *
     * <p>A half-finished install is the worst failure this design can have: the live statics point at
     * sandbox clones (a dead player, a squashed action queue) and the game renders that. Every caller
     * that can fail mid-install calls this in its catch/finally.
     */
    public static void forceRestore() {
        World w = installedInstance;
        if (w != null) {
            Log.warn("a world was left installed; restoring the live game");
            try {
                w.uninstall();
            } catch (Throwable t) {
                Log.error("force restore failed", t);
            } finally {
                // B110: even if the teardown itself failed, the process must not keep a pointer to a
                // world that is no longer installed.
                if (installedInstance == w) {
                    installedInstance = null;
                }
            }
        }
    }

    public void install() {
        if (installed) {
            throw new IllegalStateException("world already installed");
        }
        // B109: this world holds a COPY of the spec array. If a run-time registration happened after
        // it was captured, installing it would silently leave the new field unmirrored -- refuse
        // loudly instead, and let the caller capture a fresh world.
        if (this.staticsGeneration != Statics.generation()) {
            throw new IllegalStateException("this world was captured before a run-time static"
                    + " registration (" + this.staticsGeneration + " -> " + Statics.generation()
                    + "); capture a new world from the live game");
        }
        // A window that was opened and never closed would leave the game on the stand-in mixer (dead
        // music) and on a redirected save file. Recover here, before this install takes over.
        ExternalMute.heal();
        SfxMute.heal();

        if (installedInstance != null) {
            // Never stack worlds: the outer one would be lost and the live game left on a clone.
            // B82: if a leak ever persists this fires on every install, i.e. per node.
            Log.warnEvery("world-stacked", 5000L,
                    "another world is installed; restoring it before installing this one");
            forceRestore();
        }
        // Read before the loop swaps statics: once AbstractDungeon.player points at a clone, the live
        // player object is gone from the statics.
        com.megacrit.cardcrawl.characters.AbstractPlayer livePlayer =
                com.megacrit.cardcrawl.dungeons.AbstractDungeon.player;
        // B192 round 3: remember what the LIVE player/monsters/dynamic roots look like before this
        // window swaps anything in; uninstall() compares them (see LiveGuard). A window is allowed
        // to change its clones, not these objects -- a difference here is a real write to the game.
        LiveGuard.capture();
        // B213 (user m00344): the career record is the one live object a simulated death used to
        // write directly. Snapshot its numbers here, compare and put them back in uninstall().
        CharStatGuard.capture();
        live = new Object[specs.length];
        liveColors = new float[specs.length][];
        livePrefData = new Object[specs.length];
        liveContainers = new Object[specs.length];
        // While a clone is installed every engine log line is noise, and each one is paid for by
        // ModTheSpire's Swing console on this thread. Silence it for exactly this window.
        sts1solver.util.ConsoleMute.push();
        installed = true;
        installedInstance = this;
        for (int i = 0; i < specs.length; i++) {
            Statics.Spec s = specs[i];
            Object liveValue = sts1solver.reflect.Reflect.get(s.field, null);
            live[i] = liveValue;
            try {
                switch (s.kind) {
                    case CLONE:
                        s.field.set(null, cells[i]);
                        break;
                    case CONTAINER:
                        // A NEW container on purpose: the live list/map object is left untouched, so
                        // a simulated write cannot land in it even by aliasing.
                        s.field.set(null, freshContainer(cells[i]));
                        break;
                    case MUTABLE_CONTAINER:
                        // Cannot swap a static final field (JDK 21 throws on Field.set), so the
                        // sandbox gets the same object with the contents rewritten. The live
                        // contents are snapshotted here and put back on uninstall, so a simulated
                        // GetInside can neither clear nor refill the real list.
                        liveContainers[i] = copyContainer(liveValue);
                        if (!rewriteContainer(liveValue, cells[i])) {
                            Log.warnEvery("mutable-container-" + s.label(), 30000L,
                                    "cannot rewrite " + s.label() + " in place ("
                                            + (liveValue == null ? "null" : liveValue.getClass())
                                            + "); it stays live-shared");
                        }
                        break;
                    case COLOR:
                        // In place: other objects hold a reference to this same Color instance.
                        liveColors[i] = copyColor(liveValue);
                        writeColor(liveValue, (float[]) cells[i]);
                        break;
                    case PREF:
                        // Same Prefs object (the engine holds references we cannot swap), but the
                        // disk target and the data map become sandbox-local, so a simulated
                        // markCardAsSeen() can neither reach the real file nor the real map.
                        livePrefData[i] = mutePref(liveValue, s);
                        break;
                    default:
                        s.field.set(null, cells[i]);
                        break;
                }
            } catch (Throwable t) {
                // Roll the partial install back before reporting: the caller cannot do it.
                Log.error("install failed at " + s.label() + "; rolling back", t);
                try {
                    uninstall();
                } catch (Throwable ignored) {
                    // reported below
                }
                throw new RuntimeException("install " + s.label(), t);
            }
        }
        // B134 (user m07482): a simulated death really runs the DeathScreen constructor, and the
        // submitDefeatMetrics hooks fire the uploader: Minty Spire spawns a thread, serializes the
        // whole run and Metrics logs one "UPLOADING METRICS TO: ... data=<json>" line -- 10-49 KB per
        // simulated death, 1171 of them in 23 minutes of searching. ExternalMute already drops the
        // HTTP request, but that line is written before the request is made. UPLOAD_DATA is a
        // mirrored VALUE spec, so turning it off here is sandbox-local; uninstall puts the live
        // value back.
        com.megacrit.cardcrawl.core.Settings.UPLOAD_DATA = false;
        // B134: the run's metric record is not world state either. Give the sandbox an empty one so
        // a search cannot accumulate a fake damage_taken log (and pay for it on every fork).
        if (com.megacrit.cardcrawl.core.CardCrawlGame.metricData != null) {
            com.megacrit.cardcrawl.core.CardCrawlGame.metricData.clearData();
        }
        // A simulated kill calls CharStat.incrementEnemySlain() -> Prefs.flush() on the player's own
        // save file. Redirect it for exactly this window (B25).
        PlayerPrefs.muteForInstall(livePlayer);
        PlayerPrefs.muteForInstall(com.megacrit.cardcrawl.dungeons.AbstractDungeon.player);
        // Sandbox monsters share the live spine AnimationState, so a simulated attack would play on the
        // monster the player is looking at (B34). Give the sandbox its own throwaway states.
        AnimationMute.muteAll();
        // The simulated update reads the REAL cursor (AbstractPlayer.update / Hitbox.update), so
        // hovering a card mid-search ran the live hover and targeting code on a clone (B37). Input is
        // live-session state: keep it away from the sandbox for exactly this window.
        InputMute.push();
        // A simulated fight can arm music (a boss calls unsilenceBGM + playPrecachedTempBgm from its
        // pre-battle action). The mixer is a shell static, so that plays on the real game; and every
        // armed track stays in the track list forever (B45). Bracket the window: tracks created here
        // are disposed at the end of the node, i.e. within a millisecond, long before either fade-in.
        MusicMute.begin(true);
        // The sound-effect half of the same problem (B65): a simulated kill calls
        // AbstractScene.fadeOutAmbiance(), whose entry the LIVE mixer then re-applies at the LIVE
        // volume every frame -- the reported "background sound appears again, repeatedly, at short
        // intervals" after a search. Plays also load OpenAL sources inside the node. Swap the mixer
        // tables for inert ones for exactly this window; the real ones come back untouched.
        SfxMute.begin();
        // A simulated death is not just a screen: the real DeathScreen constructor posts metrics over
        // HTTP, writes the character save, deletes the run save and disposes the live music (B47).
        // Swap the four external sinks for inert ones for exactly this window.
        ExternalMute.begin();
        // B76 (m21391): the live game resets an exhausted card one second later, in a renderer
        // effect this window throws away. Apply that end state now so the sandbox and the game agree
        // (and so Exhume hands the card back with the cost the game would give it).
        ExhaustAttrs.begin();
        // B105 (P2-62, cheap half): the mod statics the mirror does NOT own stay live-shared. Watch a
        // few of them per window instead of copying all of them (a full shallow copy would double the
        // fork cost); a write through one is named, put back, and watched on every later install.
        StaticWatch.afterInstall();
        // B187 (user m01082/m01098): both mixer mutes above own the GAME's mixers; a mod that plays
        // a sound from its own hook -- YuAi's heal postfix opens a javax.sound Clip -- bypasses
        // both, and B185's "let it play, then close what it left" wrote an already-closed live clip
        // back into the mod field, which the next window's flush() turned into a native crash
        // (hs_err_pid17280). Instead every clip-typed mod static is parked on an inert stand-in for
        // exactly this window, so the mod's own play() early-outs and no line is ever opened.
        // It must run AFTER StaticWatch.afterInstall(): the watch has to snapshot the LIVE clip, not
        // the stand-in, or it would write the stand-in into the real game at uninstall. uninstall
        // still calls AudioQuiet.end() BEFORE StaticWatch.beforeUninstall(), so the live reference
        // is back before the watch compares.
        AudioQuiet.begin();
        // B182 (user m00001/m00148): the hand's glow ring is live-session presentation, and the
        // sandbox never renders it. AbstractCard.glowList held CardGlowBorder objects shared by
        // reference with the live hand (ClonePolicy shares every AbstractGameEffect), and
        // AbstractCard.stopGlowing() (:1140) divides the duration of everything in the list by 5 --
        // so a search shortened the LIVE card's pulse (1.2 s -> 0.24 s -> gone) and the hand
        // flickered differently while it calculated. Copying the rings would be paid on every fork
        // for an object nothing reads (updateGlow only runs from renderCard/renderInLibrary), so the
        // sandbox's queue is emptied instead, once per world.
        if (!glowReset) {
            glowReset = true;
            GlowReset.drop();
        }
    }

    public void uninstall() {
        if (!installed) {
            return;
        }
        // B110 (user m05182): the whole teardown runs under try/finally. A step in the middle used to
        // be able to throw and skip everything after it, which left this world "installed" forever:
        // every later install then force-restored it, and -- the part that cost the player a run --
        // the live player's save target stayed redirected into preferences\sts1solver-sandbox, so the
        // game's own save landed there and the real file stayed stale.
        try {
        // B192 round 3: before anything is put back. StaticWatch's repair below would otherwise
        // mask exactly the write we are looking for.
        LiveGuard.verify();
        // B213: before the statics go back, so a written stat field cannot outlive the window.
        CharStatGuard.verify();
        if (failNextUninstall) {
            failNextUninstall = false;
            throw new RuntimeException("B110 gate: uninstall interrupted on purpose");
        }
        // B187: put the live clip references back BEFORE the static put-back below (the watch
        // compares against them), and close any clip the window opened itself. A reference the
        // window dropped is restored too; a reference whose line really died is written as null
        // rather than handed back as a dead line (that write-back is what crashed B187).
        AudioQuiet.end();
        // B105: compare the watched mod statics and put the live contents back BEFORE anything is
        // torn down, so a write the sandbox made cannot outlive the window.
        StaticWatch.beforeUninstall();
        // The external sinks go back FIRST, while the live statics are still in place: CardCrawlGame
        // .music is a mirrored static, so restoring it after the field loop would put a clone back.
        ExhaustAttrs.end();
        ExternalMute.end();
        MusicMute.end();
        SfxMute.end();
        for (int i = 0; i < specs.length; i++) {
            Statics.Spec s = specs[i];
            try {
                switch (s.kind) {
                    case MUTABLE_CONTAINER:
                        // Same object, so only the contents need restoring.
                        if (liveContainers != null) {
                            rewriteContainer(live[i], liveContainers[i]);
                        }
                        break;
                    case COLOR:
                        writeColor(live[i], liveColors[i]);
                        break;
                    case PREF:
                        s.field.set(null, restorePref(live[i], livePrefData[i]));
                        break;
                    default:
                        s.field.set(null, live[i]);
                        break;
                }
            } catch (Throwable t) {
                Log.error("uninstall " + s.label(), t);
            }
        }
        } catch (Throwable t) {
            // Never rethrow: the finally below is what keeps the live game usable. The failure is
            // still reported loudly, and the world is marked not-installed either way.
            Log.error("uninstall failed mid-way; completing the teardown anyway", t);
        } finally {
            // B110: these MUST run. Order matters: the save target first, because the game may write
            // at any time, then the flags, then ConsoleMute last (it needs the statics restored).
            try {
                PlayerPrefs.restoreInstall();
            } catch (Throwable t) {
                Log.error("restoring the live save target failed", t);
            }
            try {
                InputMute.pop();
            } catch (Throwable t) {
                Log.warn("input mute pop failed: " + t);
            }
            live = null;
            liveColors = null;
            livePrefData = null;
            liveContainers = null;
            installed = false;
            // B201: the art this window uploaded goes back to the driver here, on every exit of the
            // window (normal, failed, interrupted). Live art is never in the registry.
            try {
                SandboxArt.close();
            } catch (Throwable t) {
                Log.warn("reclaiming the window's textures failed: " + t);
            }
            try {
                sts1solver.util.ConsoleMute.pop();
            } catch (Throwable t) {
                Log.warn("console mute pop failed: " + t);
            }
            if (installedInstance == this) {
                installedInstance = null;
            }
            // B110: the repair is deliberately NOT run here. The gate that owns this area
            // (S66) keeps a synthetic player's save target redirected for the whole session, and a
            // blanket "repair" would point that simulated save at the real file. The guarantee comes
            // from restoreInstall() above, which every window now reaches no matter what threw;
            // PlayerPrefs.verifyNoLiveRedirect() stays available for an explicit health check.
        }
    }

    /** The live color values saved at install time, restored on uninstall. */
    private float[][] liveColors;

    /** The live {@code Prefs.data} maps displaced by an install, restored on uninstall. */
    private Object[] livePrefData;

    /** Contents of each static-final container at install time, put back on uninstall. */
    private Object[] liveContainers;

    /**
     * Redirects one save file into the sandbox and returns the map it displaced.
     *
     * <p>The game reads these maps during combat ({@code hasCardBeenSeen} gates unlocks and
     * card-reward filtering), so the sandbox needs the same contents. It used to copy the whole map
     * (about 400 keys, seven Prefs, on every install). {@link PrefsOverlay} replaces that copy: reads
     * fall through to the live map, writes stay local, and the live map is never touched — the same
     * isolation, without the per-install {@code putAll} that stack sampling priced at ~6% of search
     * time (P1-2d-1).
     */
    private static Object mutePref(Object pref, Statics.Spec s) {
        if (pref == null) {
            return null;
        }
        com.megacrit.cardcrawl.helpers.Prefs p = (com.megacrit.cardcrawl.helpers.Prefs) pref;
        Object original = p.data;
        java.util.Map<String, String> overlay = p.data == null
                ? new java.util.HashMap<String, String>()
                : new PrefsOverlay(p.data);
        String originalPath = p.filepath;
        p.data = overlay;
        // Relative on purpose: the engine's saver prefixes the game's localStorage path.
        p.filepath = SandboxPrefs.relative(s.name);
        livePrefPaths.put(p, originalPath);
        return original;
    }

    /** Puts the real data map and real path back, so the live game keeps saving to its own file. */
    private static Object restorePref(Object pref, Object originalData) {
        if (pref == null) {
            return null;
        }
        com.megacrit.cardcrawl.helpers.Prefs p = (com.megacrit.cardcrawl.helpers.Prefs) pref;
        if (originalData != null) {
            p.data = castData(originalData);
        }
        String path = livePrefPaths.remove(p);
        if (path != null) {
            p.filepath = path;
        }
        return p;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, String> castData(Object o) {
        return (java.util.Map<String, String>) o;
    }

    /** Original filepath per Prefs object, captured by {@link #mutePref} on first sight. */
    private static final java.util.Map<Object, String> livePrefPaths =
            new java.util.IdentityHashMap<Object, String>();

    // ------------------------------------------------------------------ read back

    /** Reads the engine's post-step values back into this world. Call while installed. */
    public void readBack() {
        if (!installed) {
            throw new IllegalStateException("readBack needs the world installed");
        }
        for (int i = 0; i < specs.length; i++) {
            Statics.Spec s = specs[i];
            if (s.discardOnReadBack) {
                // The sandbox may have opened this screen; the live world keeps its own object.
                // B162 (user m16041): two of these are not presentation. Which screen is up decides
                // whether the action manager runs at all (AbstractRoom.update advances it only
                // while !isScreenUp), so leaving them behind made a world that ANSWERED its picker
                // install with that same picker up again -- the queue stayed frozen and every later
                // card play was a silent no-op (measured: 290-step plan, t/e/hp/monster identical).
                // Carry them back as a state, normalised: a screen that is actually waiting for an
                // answer is part of the state; anything else (a simulated death screen, a shake) is
                // not, and the stored value becomes "no screen".
                if (isScreenState(s)) {
                    // Fast path first: with no screen up (every ordinary node) this is two static
                    // reads, not the reflection inside isAwaitingSelection().
                    boolean waiting = com.megacrit.cardcrawl.dungeons.AbstractDungeon.isScreenUp
                            && sts1solver.drive.Driver.isAwaitingSelection();
                    if (waiting) {
                        cells[i] = sts1solver.reflect.Reflect.get(s.field, null);
                    } else if ("screen".equals(s.name)) {
                        cells[i] = com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen.NONE;
                    } else {
                        cells[i] = Boolean.FALSE;
                    }
                }
                continue;
            }
            if (s.kind == Statics.Kind.PREF) {
                // Restored by identity on uninstall; there is nothing to carry back.
                continue;
            }
            Object v = sts1solver.reflect.Reflect.get(s.field, null);
            switch (s.kind) {
                case CLONE:
                    cells[i] = v;
                    break;
                case CONTAINER:
                    cells[i] = copyContainer(v);
                    break;
                case MUTABLE_CONTAINER:
                    cells[i] = copyContainer(v);
                    break;
                case COLOR:
                    cells[i] = copyColor(v);
                    break;
                default:
                    cells[i] = v;
                    break;
            }
        }
    }

    /** True for the two AbstractDungeon fields that say whether a screen owns the frame (B162). */
    private static boolean isScreenState(Statics.Spec s) {
        return s.kind == Statics.Kind.VALUE
                && "com.megacrit.cardcrawl.dungeons.AbstractDungeon".equals(s.owner.getName())
                && ("screen".equals(s.name) || "isScreenUp".equals(s.name));
    }

    // ------------------------------------------------------------------ hygiene

    /**
     * Drops presentation-only state the simulation produced.
     *
     * <p>Effects are real objects the engine constructed (that is required -- the effect logic must
     * run), but their only consumer is the renderer, which never sees a sandbox. Keeping them would
     * make every node's graph larger than the last. Call while installed, between steps.
     */
    public void trimPresentation() {
        // The effects we are about to drop are not all cosmetic: ExhaustCardEffect resets the card
        // one second in (B76), which also happens for cards that fizzle out of limbo (P2-61), so
        // settle every queued effect's end state before the object disappears.
        ExhaustAttrs.sync();
        try {
            EffectSettle.settle(
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.effectList,
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.effectsQueue,
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.topLevelEffects,
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.topLevelEffectsQueue);
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.effectList.clear();
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.effectsQueue.clear();
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.topLevelEffects.clear();
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.topLevelEffectsQueue.clear();
        } catch (Throwable t) {
            // Never fatal: the worst case is a slightly larger graph.
        }
        // B130 (m06143): the death ANIMATION is the other thing that lands after the engine stops.
        // AbstractMonster.updateDeathAnimation() clears a dead monster's powers when its 1.8s timer
        // runs out, and no render loop runs here, so a monster killed in the sandbox kept them --
        // which made power-counting cards (防哈/DefendToKill) over-count dead monsters. Same settle
        // point as the effects above: end state before the object is gone, before the digest.
        try {
            DeathSettle.settle();
        } catch (Throwable ignored) {
            // Never fatal: the worst case is the old over-count.
        }
        try {
            if (com.megacrit.cardcrawl.helpers.DrawMaster.drawList != null) {
                com.megacrit.cardcrawl.helpers.DrawMaster.drawList.clear();
            }
        } catch (Throwable ignored) {
            // Same.
        }
    }

    // ------------------------------------------------------------------ views

    public com.megacrit.cardcrawl.characters.AbstractPlayer player() {
        return com.megacrit.cardcrawl.dungeons.AbstractDungeon.player;
    }

    public com.megacrit.cardcrawl.rooms.AbstractRoom room() {
        return com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom();
    }

    public com.megacrit.cardcrawl.monsters.MonsterGroup monsters() {
        return com.megacrit.cardcrawl.dungeons.AbstractDungeon.getMonsters();
    }

    public boolean isCombat() {
        try {
            return player() != null && room() != null
                    && room().phase == com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMBAT;
        } catch (Throwable t) {
            return false;
        }
    }

    public String digest() {
        return digest;
    }

    public void setDigest(String d) {
        this.digest = d;
    }

    /** Convenience for "install, let the body run, always uninstall". */
    public void run(Runnable body) {
        install();
        try {
            body.run();
        } finally {
            uninstall();
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * B192: the CONTAINER copy used by capture and fork. Same shape as
     * {@link #copyContainer(Object)}, except that an element which is a battle entity (a card, a
     * monster, a power, a relic, a potion, an orb) is copied through the pass's own {@link Cloner}.
     * A shallow copy left those elements live-shared: a static list of taken cards or of pets was
     * one more way for a simulated write to land in the real game. Identity with the cloned hand /
     * pile / monster set is preserved because it is the same Cloner instance.
     *
     * <p>Non-entity elements stay shared on purpose: strings, ids, textures and mod singletons must
     * keep their identity, and {@link ClonePolicy} decides the rest inside {@code copy}.
     */
    @SuppressWarnings("unchecked")
    private static Object copyContainer(Cloner cloner, Object v) {
        if (v instanceof List) {
            List<?> src = (List<?>) v;
            ArrayList<Object> out = new ArrayList<Object>(src.size());
            for (int i = 0; i < src.size(); i++) {
                out.add(element(cloner, src.get(i)));
            }
            return out;
        }
        if (v instanceof java.util.Set) {
            java.util.Set<?> src = (java.util.Set<?>) v;
            java.util.LinkedHashSet<Object> out = new java.util.LinkedHashSet<Object>();
            for (Object o : src) {
                out.add(element(cloner, o));
            }
            return out;
        }
        if (v instanceof Map) {
            Map<?, ?> src = (Map<?, ?>) v;
            HashMap<Object, Object> out = new HashMap<Object, Object>();
            for (Map.Entry<?, ?> e : src.entrySet()) {
                out.put(e.getKey(), element(cloner, e.getValue()));
            }
            return out;
        }
        return v;
    }

    private static Object element(Cloner cloner, Object e) {
        return StaticEntities.isBattleEntity(e) ? cloner.copy(e) : e;
    }

    private static Object copyContainer(Object v) {
        if (v instanceof List) {
            return new ArrayList<Object>((List<?>) v);
        }
        if (v instanceof java.util.Set) {
            // Mods keep per-combat sets too (aristocrat: the cards retained until played, the silk
            // curtains still in the opening hand). A Set fell through every branch here and stayed
            // live-shared, so a simulated card could add/remove live elements.
            return new java.util.LinkedHashSet<Object>((java.util.Set<?>) v);
        }
        if (v instanceof Map) {
            return new HashMap<Object, Object>((Map<?, ?>) v);
        }
        return v;
    }

    /**
     * Deep copy of a static-final container's contents.
     *
     * <p>Shallow would not do: the elements are combat objects (CaiNongMod stores the two cards a
     * GetInside took). Two branches sharing them would rewrite each other's card. The Cloner doing
     * the rest of the pass is used here too, so identity with the cloned hand/piles is preserved.
     */
    private static Object deepContainer(Cloner cloner, Object v) {
        if (v instanceof List) {
            List<?> src = (List<?>) v;
            ArrayList<Object> out = new ArrayList<Object>(src.size());
            for (int i = 0; i < src.size(); i++) {
                out.add(cloner.copy(src.get(i)));
            }
            return out;
        }
        if (v instanceof java.util.Set) {
            java.util.Set<?> src = (java.util.Set<?>) v;
            java.util.LinkedHashSet<Object> out = new java.util.LinkedHashSet<Object>();
            for (Object o : src) {
                out.add(cloner.copy(o));
            }
            return out;
        }
        if (v instanceof Map) {
            Map<?, ?> src = (Map<?, ?>) v;
            HashMap<Object, Object> out = new HashMap<Object, Object>();
            for (Map.Entry<?, ?> e : src.entrySet()) {
                out.put(e.getKey(), cloner.copy(e.getValue()));
            }
            return out;
        }
        return v;
    }

    /**
     * Replaces the contents of an existing container in place, leaving the object (and therefore
     * every reference the engine holds to it) intact. Returns false when it is not a container.
     */
    private static boolean rewriteContainer(Object target, Object contents) {
        if (target instanceof List) {
            List<Object> list = (List<Object>) target;
            list.clear();
            if (contents instanceof java.util.Collection) {
                list.addAll((java.util.Collection<Object>) contents);
            }
            return true;
        }
        if (target instanceof java.util.Set) {
            java.util.Set<Object> set = (java.util.Set<Object>) target;
            set.clear();
            if (contents instanceof java.util.Collection) {
                set.addAll((java.util.Collection<Object>) contents);
            }
            return true;
        }
        if (target instanceof Map) {
            Map<Object, Object> map = (Map<Object, Object>) target;
            map.clear();
            if (contents instanceof Map) {
                map.putAll((Map<Object, Object>) contents);
            }
            return true;
        }
        return false;
    }

    private static Object freshContainer(Object contents) {
        if (contents instanceof List) {
            return new ArrayList<Object>((List<?>) contents);
        }
        if (contents instanceof java.util.Set) {
            return new java.util.LinkedHashSet<Object>((java.util.Set<?>) contents);
        }
        if (contents instanceof Map) {
            return new HashMap<Object, Object>((Map<?, ?>) contents);
        }
        // A captured-null container becomes an empty one: writing null into a static the game
        // dereferences would turn a mirror into a crash.
        return new ArrayList<Object>();
    }

    private static float[] copyColor(Object c) {
        if (!(c instanceof com.badlogic.gdx.graphics.Color)) {
            return null;
        }
        com.badlogic.gdx.graphics.Color col = (com.badlogic.gdx.graphics.Color) c;
        return new float[]{col.r, col.g, col.b, col.a};
    }

    private static void writeColor(Object target, float[] rgba) {
        if (!(target instanceof com.badlogic.gdx.graphics.Color) || rgba == null) {
            return;
        }
        com.badlogic.gdx.graphics.Color col = (com.badlogic.gdx.graphics.Color) target;
        col.r = rgba[0];
        col.g = rgba[1];
        col.b = rgba[2];
        col.a = rgba[3];
    }

    /**
     * Test hook (P1-2d-2): the cloned root cells, so a gate can compare two forks of the same
     * world field by field. Returns a copy of the array, not the live cells.
     */
    public Object[] roots() {
        return cells.clone();
    }

    public int graphObjects() {
        return graphObjects;
    }

    private int graphObjects;

    /** Debug: the cloned object count of the last fork (set by callers that care). */
    public void setGraphObjects(int n) {
        this.graphObjects = n;
    }

    @SuppressWarnings("unused")
    private static IdentityHashMap<Object, Object> newIdentityMap() {
        return new IdentityHashMap<Object, Object>();
    }
}