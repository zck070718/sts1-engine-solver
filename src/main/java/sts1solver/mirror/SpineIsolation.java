package sts1solver.mirror;

import com.esotericsoftware.spine.AnimationState;
import com.esotericsoftware.spine.Skeleton;
import com.megacrit.cardcrawl.core.AbstractCreature;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import sts1solver.util.Log;

/**
 * B192 round 5: give every cloned creature its own spine state.
 *
 * <p>{@link ClonePolicy} shares the whole {@code com.esotericsoftware.spine} package by reference:
 * atlas data, skeleton and animation state are large and were assumed to be "looks, not state".
 * That assumption is wrong for a live creature. The shared {@link Skeleton} is the pose the screen
 * draws and the shared {@link AnimationState} is the clock the game advances every frame, so a
 * simulated {@code state.update} / {@code state.apply} or {@code skeleton.setToSetupPose} reaches
 * the player's screen. The static-root ownership in {@link StaticEntities} cannot see this: the
 * reference lives in the cloned creature, not in a static field.
 *
 * <p>So right after a creature is copied, its two spine fields are re-pointed at objects built with
 * the engine's own copy API (no mod class is named anywhere):
 * <ul>
 *   <li>{@code new Skeleton(liveSkeleton)} -- the pose, placement and flip the creature had at
 *       capture time, in an object only the sandbox can move;</li>
 *   <li>{@code new AnimationState(liveState.getData())} plus one re-created track entry per
 *       animation track, at the captured time, loop flag and time scale -- the same animation, on a
 *       clock only the sandbox can advance.</li>
 * </ul>
 * {@code stateData} stays shared: it is configuration (mix durations), not per-creature state.
 *
 * <p>Rollback: {@code mirrorSpine=share} in settings.txt restores the old shared reference without
 * a rebuild; deleting the two {@code SpineIsolation.isolate} calls in {@link Cloner} does the same.
 */
public final class SpineIsolation {

    private SpineIsolation() {
    }

    private static final long SETTINGS_MS = 2000L;

    private static Field skeletonField;
    private static Field stateField;
    private static boolean fieldsTried;

    private static long detached;
    private static long failures;

    private static Boolean enabledPinned;
    private static Boolean enabledCached;
    private static long enabledAt;

    /**
     * Re-points the copy's spine fields at sandbox-owned objects. Called once per cloned creature,
     * after its fields are copied, so the fields still hold the live references.
     */
    public static void isolate(AbstractCreature copy) {
        if (copy == null || !enabled()) {
            return;
        }
        resolveFields();
        if (skeletonField == null && stateField == null) {
            return;
        }
        try {
            detachSkeleton(copy);
        } catch (Throwable t) {
            warnOnce(copy, t);
        }
        try {
            detachState(copy);
        } catch (Throwable t) {
            warnOnce(copy, t);
        }
    }

    private static void detachSkeleton(AbstractCreature c) throws Exception {
        if (skeletonField == null) {
            return;
        }
        Object v = skeletonField.get(c);
        if (!(v instanceof Skeleton)) {
            return;
        }
        skeletonField.set(c, new Skeleton((Skeleton) v));
        ++detached;
    }

    private static void detachState(AbstractCreature c) throws Exception {
        if (stateField == null) {
            return;
        }
        Object v = stateField.get(c);
        if (!(v instanceof AnimationState)) {
            return;
        }
        AnimationState live = (AnimationState) v;
        AnimationState copy = new AnimationState(live.getData());
        copy.setTimeScale(live.getTimeScale());
        com.badlogic.gdx.utils.Array<AnimationState.TrackEntry> tracks = live.getTracks();
        if (tracks != null) {
            for (int i = 0; i < tracks.size; i++) {
                AnimationState.TrackEntry e = tracks.get(i);
                if (e == null || e.getAnimation() == null) {
                    continue;
                }
                AnimationState.TrackEntry ne = copy.setAnimation(i, e.getAnimation(), e.getLoop());
                if (ne != null) {
                    ne.setTime(e.getTime());
                    ne.setTimeScale(e.getTimeScale());
                }
            }
        }
        stateField.set(c, copy);
    }

    private static void warnOnce(AbstractCreature c, Throwable t) {
        ++failures;
        Log.warnEvery("spine-isolation", 30000L, "spine isolation: cannot detach the animation of "
                + c.getClass().getName() + " (" + t + "); it stays shared with the live game");
    }

    private static synchronized void resolveFields() {
        if (fieldsTried) {
            return;
        }
        fieldsTried = true;
        try {
            skeletonField = AbstractCreature.class.getDeclaredField("skeleton");
            skeletonField.setAccessible(true);
        } catch (Throwable t) {
            skeletonField = null;
        }
        try {
            stateField = AbstractCreature.class.getDeclaredField("state");
            stateField.setAccessible(true);
        } catch (Throwable t) {
            stateField = null;
        }
    }

    // ------------------------------------------------------------------ settings / report

    /** settings.txt {@code mirrorSpine}, default copy; {@code share}/{@code off}/{@code 0} disables. */
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
            String v = s.get("mirrorSpine");
            if (v != null) {
                String lv = v.toLowerCase(java.util.Locale.ROOT);
                on = !("share".equals(lv) || "off".equals(lv) || "no".equals(lv)
                        || "false".equals(lv) || "0".equals(lv) || "\u5173".equals(lv));
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
        return "spine isolation: " + detached + " detached spine state(s), " + failures
                + " failure(s)" + (enabled() ? "" : ", disabled by mirrorSpine");
    }

    public static synchronized void resetForTest() {
        detached = 0;
        failures = 0;
    }
}
