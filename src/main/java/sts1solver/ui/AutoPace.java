package sts1solver.ui;

import sts1solver.util.Log;

/**
 * Autoplay pace (P2-14): how long to wait between two actions of the plan.
 *
 * <p>Without a pace the mod executed the whole plan back to back -- measured 100-250 ms per step,
 * 1.3 s for a whole fight (user m14889) -- because {@code Gameplay.maybeAutopilot} only waited for
 * {@code engineSettled()} and never for the player's eye. STS2 does the same thing with
 * {@code DeploymentInterActionDelaySeconds} (0-3 s) plus a {@code SolverDeploymentFastMode} switch;
 * this is that delay, kept small and configurable in game.
 *
 * <p>The value lives in the same {@code settings.txt} as the budget ({@code autoplayDelayMs=}) and is
 * re-read on use, so the panel button works without a restart. 0 means "as fast as the engine allows"
 * and is the only setting that reproduces the old behaviour; it exists so the A/B test is possible.
 */
public final class AutoPace {

    /** Values the 节奏 button steps through, in ms. */
    public static final int[] PROFILE_MS = {0, 250, 450, 750, 1000};
    /** STS2's default is one beat per action; 450 ms reads as "playing", not "sped up". */
    public static final int DEFAULT_MS = 450;
    public static final int MIN_MS = 0;
    public static final int MAX_MS = 3000;

    private AutoPace() {
    }

    private static java.io.File file() {
        return new java.io.File(sts1solver.SolverMod.dir(), "settings.txt");
    }

    /**
     * m19362: how long after an action the autopilot waits before it is allowed to call the step a
     * failure. The engine is settled long before the visuals and the animation-driven side lists
     * are, so judging on the first settled frame produced false drift reports ("选牌已应答，但等待
     * 中的动作没有取走这张牌") on cards that in fact resolved correctly -- Burning Pact was the one
     * the player caught. This is a wait, not a sleep: the frame loop only compares clocks.
     */
    public static final int VERIFY_DEFAULT_MS = 700;
    public static final int VERIFY_MIN_MS = 0;
    public static final int VERIFY_MAX_MS = 5000;

    /**
     * B209 (user m03085): how long a pick screen must have been visible before autoplay clicks it.
     *
     * <p>STS2's autoplay keeps the native page open for at least 250 ms before it selects on it
     * ({@code NativeChoiceRuntime.OtherSurfaceMinimumOpenMilliseconds}), so the click never looks
     * like something that happened before the page was there. This is that constant, in the same
     * settings.txt as the pace ({@code autoplaySelectMinOpenMs=}), re-read on use.
     */
    public static final int SELECT_MIN_OPEN_DEFAULT_MS = 250;
    public static final int SELECT_MIN_OPEN_MAX_MS = 2000;

    private static int cached = -1;
    private static long cachedAt;
    private static int verifyCached = -1;
    private static long verifyCachedAt;
    private static int selectCached = -1;
    private static long selectCachedAt;
    private static final long CACHE_MS = 500L;

    /** Drops the cache so the next read hits the file (after a write). */
    public static synchronized void invalidate() {
        cached = -1;
        cachedAt = 0L;
        verifyCached = -1;
        verifyCachedAt = 0L;
        selectCached = -1;
        selectCachedAt = 0L;
    }

    /**
     * B209 (user m03085): the minimum time a pick screen stays open before autoplay clicks it
     * (settings.txt {@code autoplaySelectMinOpenMs=}), clamped to 0..2000. 0 = click on the first
     * frame, which is the pre-B209 behaviour and the A/B switch.
     */
    public static synchronized int selectMinOpenMs() {
        long now = System.currentTimeMillis();
        if (selectCached >= 0 && now - selectCachedAt < CACHE_MS) {
            return selectCached;
        }
        int v = (int) readLong("autoplaySelectMinOpenMs=", SELECT_MIN_OPEN_DEFAULT_MS);
        selectCached = Math.max(0, Math.min(SELECT_MIN_OPEN_MAX_MS, v));
        selectCachedAt = now;
        return selectCached;
    }

    /** Settle window in ms (settings.txt {@code autoplayVerifyMs=}), clamped to 0..5000. */
    public static synchronized int verifyMs() {
        long now = System.currentTimeMillis();
        if (verifyCached >= 0 && now - verifyCachedAt < CACHE_MS) {
            return verifyCached;
        }
        int v = (int) readLong("autoplayVerifyMs=", VERIFY_DEFAULT_MS);
        verifyCached = Math.max(VERIFY_MIN_MS, Math.min(VERIFY_MAX_MS, v));
        verifyCachedAt = now;
        return verifyCached;
    }

    /**
     * True when the settle window since {@code actionAtMs} has passed, so a mismatch may be judged.
     * Pure, so the gate can pin it with a fake clock.
     */
    public static boolean readyToJudge(long nowMs, long actionAtMs, int verifyMs) {
        if (actionAtMs <= 0L) {
            return true;
        }
        return nowMs - actionAtMs >= Math.max(0, verifyMs);
    }

    /** Current delay in ms, clamped to MIN_MS..MAX_MS. */
    public static synchronized int delayMs() {
        long now = System.currentTimeMillis();
        if (cached >= 0 && now - cachedAt < CACHE_MS) {
            return cached;
        }
        int v = (int) readLong("autoplayDelayMs=", DEFAULT_MS);
        cached = clamp(v);
        cachedAt = now;
        return cached;
    }

    public static int clamp(int ms) {
        return Math.max(MIN_MS, Math.min(MAX_MS, ms));
    }

    /**
     * True when the next action is allowed at {@code nowMs}.
     *
     * <p>Pure so the gate can pin it with a fake clock: the frame loop must never sleep, it only
     * compares the clock and returns.
     */
    public static boolean due(long nowMs, long nextActionAtMs) {
        return nowMs >= nextActionAtMs;
    }

    /** The earliest time the next action may run; 0 delay means "now". */
    public static long nextAt(long nowMs, int delayMs) {
        return delayMs <= 0 ? nowMs : nowMs + delayMs;
    }

    /** Next pace profile (0 -> 250 -> ... -> 1000 -> 0). Returns the new value. */
    public static int cycle() {
        int now = delayMs();
        int next = PROFILE_MS[0];
        boolean found = false;
        for (int i = 0; i < PROFILE_MS.length; i++) {
            if (PROFILE_MS[i] > now) {
                next = PROFILE_MS[i];
                found = true;
                break;
            }
        }
        if (!found) {
            next = PROFILE_MS[0];
        }
        write(next);
        return next;
    }

    /** Writes the key, preserving every other line of the file. */
    public static synchronized void write(int ms) {
        int v = clamp(ms);
        java.util.List<String> out = new java.util.ArrayList<String>();
        boolean saw = false;
        try {
            if (file().isFile()) {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(file()), "UTF-8"));
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("autoplayDelayMs=")) {
                            out.add("autoplayDelayMs=" + v);
                            saw = true;
                        } else {
                            out.add(line);
                        }
                    }
                } finally {
                    r.close();
                }
            }
            if (!saw) {
                out.add("autoplayDelayMs=" + v);
            }
            java.io.PrintWriter w = new java.io.PrintWriter(
                    new java.io.OutputStreamWriter(new java.io.FileOutputStream(file()), "UTF-8"));
            try {
                for (int i = 0; i < out.size(); i++) {
                    w.println(out.get(i));
                }
            } finally {
                w.close();
            }
            invalidate();
            Log.info("pace: autoplayDelayMs=" + v);
        } catch (Throwable t) {
            Log.warn("pace: cannot write settings.txt: " + t);
        }
    }

    /** "节奏 450ms" / "节奏 瞬时". */
    public static String label() {
        int v = delayMs();
        return "节奏 " + (v <= 0 ? "瞬时" : v + "ms");
    }

    /** One panel line. */
    public static String describe() {
        int v = delayMs();
        return "节奏: " + (v <= 0 ? "瞬时（引擎允许就出下一张）" : "每步 " + v + "ms")
                + "（面板「节奏」按钮切换；0/250/450/750/1000）";
    }

    private static long readLong(String key, long fallback) {
        try {
            java.io.File f = file();
            if (!f.isFile()) {
                return fallback;
            }
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith(key)) {
                        return Long.parseLong(line.substring(key.length()).trim());
                    }
                }
            } finally {
                r.close();
            }
        } catch (Throwable ignored) {
            // a broken settings file must never break autoplay
        }
        return fallback;
    }
}
