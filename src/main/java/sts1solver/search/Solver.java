/*
 * Decompiled with CFR 0.152.
 */
package sts1solver.search;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.potions.PotionSlot;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import sts1solver.drive.Driver;
import sts1solver.mirror.Digest;
import sts1solver.mirror.World;
import sts1solver.search.Action;
import sts1solver.search.EscapePolicy;
import sts1solver.search.GrowthCredit;
import sts1solver.search.Legal;
import sts1solver.search.PostCombatHealing;
import sts1solver.util.Log;

public final class Solver {
    private static final int CHOICE_OPTIONS = 2;
    /**
     * P2-8: how many extra layers to keep searching after the first winning line. The reference
     * project keeps refining while the baseline is not a proven zero-damage win and budget remains
     * (CombatSolver-main `docs\archive\strategy\beam-width-portfolio.md`), it does not stop at the
     * first win either. Eight layers is enough to matter in one fight without spending the whole
     * budget on a fight that is already won.
     */
    public static final int REFINE_LAYERS = 8;
    /**
     * A3 (docs/SEARCH-OPTIONS.md §7.2): a node pushed out of the beam used to be dropped forever
     * (`continue`), which is exactly the "浅层打分把可能最优的路线永久排除了" the user objected to.
     * Such nodes are now parked in a deferred list (path + score only, never a World) and promoted
     * later with whatever budget is left, so beam position decides ORDER, not EXISTENCE.
     */
    private static final int DEFERRED_LIMIT = 2000;
    /**
     * How many parked nodes one search may re-materialize (each costs depth x apply).
     *
     * <p>P2-41 (user m19889 "6条世界线是不是太少了"): this used to be a flat ceiling of 6, which threw
     * away almost the whole parking lot even when minutes of budget were left. It is now the FLOOR of
     * an adaptive limit computed by {@link #promotionLimit(long)} from the budget actually left and
     * the measured cost of the promotions this session already ran. A fixed count is still the
     * answer when there is no budget, and {@link #setPromotionLimitOverride(int)} pins it for gates.
     */
    private static final int MAX_PROMOTIONS = 6;
    /** P2-41: ceiling of the adaptive limit (each promotion replays a whole line, so not unbounded). */
    private static final int MAX_PROMOTIONS_CAP = 60;
    /** P2-41: cost guess for the first promotion of a session, before anything was measured. */
    private static final long PROMOTE_COST_GUESS_MS = 150L;
    /** P2-41: weight of the newest measurement in the moving average. */
    private static final double PROMOTE_COST_WEIGHT = 0.4;
    /**
     * P2-41: a promotion is only worth doing if the search AFTER it gets a real slice of budget.
     * The replay itself measures 1-2 ms (shallow paths), but a 1 ms search on the rebuilt state is
     * worthless, so every promotion is charged at least this much.
     */
    private static final long MIN_PROMOTION_SLICE_MS = 250L;
    private static volatile double promoteCostMs;
    private static volatile int promoteCostSamples;
    private static volatile int promotionLimitOverride;
    /** Below this much leftover budget a promotion cannot pay for itself. */
    private static final long MIN_PROMOTION_MS = 60L;
    /** Promotion recurses into solveInternal; one level is enough, deeper would re-defer forever. */
    private static final int MAX_PROMOTION_DEPTH = 1;
    private static final ThreadLocal<Integer> PROMOTION_DEPTH = new ThreadLocal<Integer>();
    /** B44: below this much leftover time, continuing the deepest line is not worth starting. */
    private static final long MIN_EXTEND_MS = 800L;
    /** B44: fraction of the budget kept back from promotions for the "finish the fight" step. */
    private static final long EXTEND_RESERVE_DIV = 4L;
    /** B44: how many times one search may continue the deepest line (each shrinks the budget). */
    private static final int MAX_EXTEND_DEPTH = 6;
    private static final ThreadLocal<Integer> EXTEND_DEPTH = new ThreadLocal<Integer>();
    /** B44: whether the line handed back was walked to the end of the battle. */
    private static final ThreadLocal<Boolean> LINE_FINISHED = new ThreadLocal<Boolean>();
    /**
     * B78 (user m21889): where the last replay stopped matching the plan, or -1 when it walked the
     * whole line. The plan is printed only if this is -1 and the step count matches, otherwise the
     * panel would promise a win that came from the exploration tree while showing a shorter line.
     */
    private static final ThreadLocal<Integer> REPLAY_FAILED_AT = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return Integer.valueOf(-1);
        }
    };
    private static final ThreadLocal<String> REPLAY_FAILED_ON = new ThreadLocal<String>() {
        @Override
        protected String initialValue() {
            return "";
        }
    };

    /** Index of the step the last {@link #replay(World, List)} could not reproduce, or -1. */
    public static int lastReplayFailedAt() {
        return REPLAY_FAILED_AT.get().intValue();
    }

    /** Label of the action at {@link #lastReplayFailedAt()}, or "". */
    public static String lastReplayFailedOn() {
        return REPLAY_FAILED_ON.get();
    }

    /**
     * B79 (user m22205-3): WHY the last replay failed, bucketed -- "not-offered: …" / "not-settled:
     * …" / "not-in-hand: …". The log needs the bucket, not just a stack trace: a line that cannot be
     * reproduced for "the screen never came up" is our timing, one for "the screen does not offer
     * that card" is the plan (or the model) being wrong.
     */
    private static final ThreadLocal<String> REPLAY_FAILURE = new ThreadLocal<String>() {
        @Override
        protected String initialValue() {
            return "";
        }
    };

    public static String lastReplayFailure() {
        return REPLAY_FAILURE.get();
    }
    /** A3 switch, for the gate that has to show the difference between parking and dropping. */
    private static boolean deferredEnabled = true;

    public static void setDeferredEnabled(boolean enabled) {
        deferredEnabled = enabled;
    }

    public static boolean deferredEnabled() {
        return deferredEnabled;
    }

    /** P2-41: measured cost of ONE promotion (moving average), 0 before anything was measured. */
    public static double promotionCostMs() {
        return promoteCostMs;
    }

    public static int promotionCostSamples() {
        return promoteCostSamples;
    }

    /** P2-41: record how long replaying one parked candidate actually took. */
    public static void notePromotionCost(long ms) {
        if (ms < 0L) {
            return;
        }
        synchronized (Solver.class) {
            if (promoteCostSamples <= 0) {
                promoteCostMs = ms;
            } else {
                promoteCostMs = promoteCostMs * (1.0 - PROMOTE_COST_WEIGHT) + ms * PROMOTE_COST_WEIGHT;
            }
            ++promoteCostSamples;
        }
    }

    /** P2-41: forget the measurements (a new game has a different machine state). */
    public static void resetPromotionStats() {
        synchronized (Solver.class) {
            promoteCostMs = 0.0;
            promoteCostSamples = 0;
            promotionLimitOverride = 0;
        }
    }

    /** P2-41: pin the limit for a gate (0 restores the adaptive rule). */
    public static void setPromotionLimitOverride(int n) {
        promotionLimitOverride = Math.max(0, n);
    }

    /**
     * P2-41: how many parked candidates this search may re-run, given the budget it has left.
     *
     * <p>Each promotion replays its whole line from the root, so the affordable count is
     * {@code (remaining / 2) / cost}: half the leftover budget may go to the parking lot, the other
     * half stays for finishing the line. Below {@link #MAX_PROMOTIONS} it never goes down, above
     * {@link #MAX_PROMOTIONS_CAP} it never goes up. With no budget left it is the old flat 6.
     */
    /**
     * B103: the reason no parked candidate was replayed, from the counters {@code promote()} kept.
     *
     * <p>Ordered by what the player can do about it: a fidelity gap and the shared parked-pass
     * ceiling are worth reporting separately from the layer cap, which is just the configured
     * limit. Never returns "" -- an unexplained zero would put the panel back where it started.
     */
    public static String parkedRefusal(int depth, int earlier, int dup, int rebuild) {
        return parkedRefusal(depth, earlier, dup, rebuild, 0);
    }

    /**
     * B107: the same reason, with the lines skipped because a step of theirs cannot be replayed.
     *
     * <p>These are not "retried later": a rebuild is deterministic, so a step the plan has no answer
     * for fails for every line that reaches it. They are skipped by name instead.
     */
    public static String parkedRefusal(int depth, int earlier, int dup, int rebuild, int poisoned) {
        if (poisoned > 0) {
            return poisoned + " 条被挤掉的线要走一个无法重放的动作（本搜索已确认那一步缺答案），跳过；"
                    + "另有 " + rebuild + " 条重建不回当时的状态（不再重试）";
        }
        if (rebuild > 0) {
            return rebuild + " 条被挤掉的线重建不回当时的状态（保真缺口，不是预算问题；不再重试）";
        }
        if (depth > 0 && earlier == 0 && dup == 0) {
            return "被挤掉的线全部在层数上限之外（调高层数才能看）";
        }
        if (earlier > 0 && dup == 0) {
            return earlier + " 条属于更早的层，且当前不允许回放更早的层";
        }
        if (dup > 0 && earlier == 0 && depth == 0) {
            return "这些线本次已经回放过了";
        }
        return "没有任何一条命中回放条件（depth=" + depth + " earlier=" + earlier
                + " duplicate=" + dup + " rebuild=" + rebuild + "）";
    }

    public static int promotionLimit(long remainingMs) {
        int override = promotionLimitOverride;
        if (override > 0) {
            return override;
        }
        if (remainingMs <= 0L) {
            return Limits.promotions();
        }
        double cost = promoteCostMs > 0.0 ? promoteCostMs : PROMOTE_COST_GUESS_MS;
        double perPromotion = Math.max(cost, (double) MIN_PROMOTION_SLICE_MS);
        long affordable = (long) (remainingMs * 0.5 / perPromotion);
        int limit = (int) Math.min((long) Math.max(MAX_PROMOTIONS_CAP, Limits.promotionCap()), affordable);
        return Math.max(Limits.promotions(), limit);
    }
    /**
     * A1 (docs/SEARCH-OPTIONS.md §3.5): optional short-rollout re-ranking of the beam frontier.
     *
     * <p>The static score ranks candidates by the state reached after ONE action; it cannot see
     * that a slightly worse-looking state has a much better follow-up. With this switch on, the
     * best `2 x beam` candidates of each layer are re-scored by playing one greedy action on a
     * throwaway fork and reading the result, then re-ordered. It only changes ORDER, never the
     * verdict: every number still comes from the real engine (JUDGMENT.md R10), and the switch
     * defaults OFF until an on/off experiment proves it pays for itself.
     */
    public static final int ROLLOUT_REFINE_FACTOR = 2;
    private static volatile boolean rolloutOn = Boolean.parseBoolean(System.getProperty("sts1solver.search.rollout", "false"));
    private static volatile long rolloutMs = 0L;
    private static volatile int rolloutCalls = 0;

    public static void setRolloutEnabled(boolean on) {
        rolloutOn = on;
    }

    public static boolean rolloutEnabled() {
        return rolloutOn;
    }

    /** Milliseconds spent inside rollout probes since the last reset (experiment only). */
    public static long rolloutMs() {
        return rolloutMs;
    }

    public static int rolloutCalls() {
        return rolloutCalls;
    }

    public static void resetRolloutStats() {
        rolloutMs = 0L;
        rolloutCalls = 0;
    }

    /**
     * One-ply greedy rollout: fork, play the single most useful action, read the state back.
     * The policy is deliberately dumb (hit the weakest living monster, else use anything untargeted,
     * else end the turn) because its ONLY job is to break ties better than the static score.
     */
    static int rolloutScore(World base) {
        long t0 = System.nanoTime();
        World probe = null;
        boolean installed = false;
        int result = Integer.MIN_VALUE;
        try {
            probe = base.fork();
            probe.install();
            installed = true;
            int hp0 = Solver.hp();
            int gold0 = Solver.playerGold();
            List<Action> actions = Legal.actions();
            Action greedy = Solver.greedyRolloutAction(actions);
            if (greedy != null) {
                Legal.apply(greedy);
                if (Driver.selectionKind() != Driver.SelectionKind.NONE) {
                    Driver.resolveSelectionHeuristically();
                }
            }
            int hp1 = Solver.hp();
            int gold1 = Solver.playerGold();
            boolean win = hp1 > 0 && Solver.monstersDead();
            boolean dead = hp1 <= 0;
            result = Solver.score(Math.max(0, hp0 - hp1), 0, win, dead, 1,
                    Solver.monsterHp(), Solver.block(), Solver.aliveCount(), 0, gold1 - gold0);
        }
        catch (Throwable throwable) {
            result = Integer.MIN_VALUE;
        }
        finally {
            if (installed) {
                try {
                    probe.uninstall();
                }
                catch (Throwable throwable) {
                    // Best-effort cleanup inside a finally-style block; the result the caller asked
                    // for is already computed, and a failed uninstall is reported by the world check.
                }
            }
        }
        rolloutMs += (System.nanoTime() - t0) / 1000000L;
        ++rolloutCalls;
        return result;
    }

    private static Action greedyRolloutAction(List<Action> actions) {
        if (actions == null || actions.isEmpty()) {
            return null;
        }
        int[] hps = Solver.monsterHps();
        Action end = null;
        Action untargeted = null;
        Action targeted = null;
        int weakest = Integer.MAX_VALUE;
        for (int i = 0; i < actions.size(); ++i) {
            Action a = actions.get(i);
            if ("end".equals(a.kind)) {
                if (end == null) {
                    end = a;
                }
                continue;
            }
            if (a.targetIndex >= 0 && a.targetIndex < hps.length) {
                if (hps[a.targetIndex] > 0 && hps[a.targetIndex] < weakest) {
                    weakest = hps[a.targetIndex];
                    targeted = a;
                }
            }
            else if (untargeted == null) {
                untargeted = a;
            }
        }
        if (targeted != null) {
            return targeted;
        }
        if (untargeted != null) {
            return untargeted;
        }
        return end;
    }

    /**
     * Re-rank the first `count` candidates of an already statically sorted layer by rollout score.
     * The rest of the list keeps its static order, so this can only permute the head of the layer.
     */
    static void rerankByRollout(List<Node> nodes, int count) {
        int k = Math.min(count, nodes.size());
        if (k < 2) {
            return;
        }
        final java.util.Map<Node, Integer> refined = new java.util.IdentityHashMap<Node, Integer>();
        for (int i = 0; i < k; ++i) {
            Node node = nodes.get(i);
            refined.put(node, Integer.valueOf(Solver.rolloutScore(node.world)));
        }
        List<Node> head = new ArrayList<Node>(nodes.subList(0, k));
        Collections.sort(head, new Comparator<Node>() {
            @Override
            public int compare(Node a, Node b) {
                int ra = refined.get(a).intValue();
                int rb = refined.get(b).intValue();
                if (ra != rb) {
                    return rb - ra;
                }
                if (a.rank != b.rank) {
                    return b.rank - a.rank;
                }
                if (a.score != b.score) {
                    return b.score - a.score;
                }
                return a.digest.compareTo(b.digest);
            }
        });
        for (int i = 0; i < k; ++i) {
            nodes.set(i, head.get(i));
        }
    }

    /**
     * P2-11b: soft heap ceiling for the wide beam, in MB. Measured long runs peak at 1.7-2.0GB
     * because the widest pass (beam=128) keeps 128 fully cloned Worlds; on a machine with a small
     * heap that is an OOM rather than a slow search. When the used heap crosses this line the
     * widest pass narrows to {@link #GUARDED_BEAM} for its next layer, which loses candidates but
     * converges instead of dying.
     *
     * <p>B72 (user m20740): this used to default to a flat 1400MB. This machine has a 6.9GB heap
     * (physical 31.2GB), so any search that touched 1.4GB stayed pinned at beam=48 for the whole
     * fight; on the two-slime fight that threw away the defensive line and returned a 7-HP
     * "race the monster" line the player beat by hand. An unpinned guard now follows the JVM:
     * {@link #HEAP_LIMIT_AUTO_PCT}% of {@code Runtime.maxMemory()}. Set the property
     * `-Dsts1solver.heapLimitMb=N` to pin it, or 0 to switch the guard off.
     */
    public static volatile long heapLimitMb = Long.getLong("sts1solver.heapLimitMb", -1L);
    /** B72: share of {@code Runtime.maxMemory()} the unpinned guard may use. */
    public static final long HEAP_LIMIT_AUTO_PCT = 65L;
    /** Below this configured beam the guard does nothing: 32-wide passes are already cheap. */
    public static final int GUARD_MIN_BEAM = 64;
    /** Beam the widest pass falls back to while the heap is over the limit (48 = 3/8 of 128). */
    public static final int GUARDED_BEAM = 48;
    /** B59: release the narrowed beam only below this percentage of the limit (hysteresis). */
    public static final long GUARD_RELEASE_PCT = 65L;

    /** Used heap in MB (what the panel can show; the JVM has no cheaper honest number). */
    public static long heapUsedMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) >> 20;
    }

    /**
     * The ceiling actually in force: the pinned property value (0 = guard off), or a share of the
     * real heap when nothing was pinned (B72).
     */
    public static long effectiveHeapLimitMb() {
        if (heapLimitMb >= 0L) {
            return heapLimitMb;
        }
        long maxMb = Runtime.getRuntime().maxMemory() >> 20;
        if (maxMb <= 0L) {
            return 0L;
        }
        return Math.max(256L, maxMb * HEAP_LIMIT_AUTO_PCT / 100L);
    }

    /**
     * The beam the truncation code should really use this layer. Pure apart from the heap read, so
     * a gate can pin `heapLimitMb` and assert both branches without allocating gigabytes.
     */
    public static int effectiveBeam(int configured) {
        // P2-58: the beam tier caps whatever a profile asked for (LOW 45, HIGH 96, unlimited 256);
        // the heap guard below can still narrow it, because memory is a fact and a setting is a wish.
        int tier = Limits.beam();
        if (configured > tier) {
            configured = tier;
        }
        long limit = effectiveHeapLimitMb();
        if (limit <= 0L || configured < GUARD_MIN_BEAM) {
            return configured;
        }
        long used = heapUsedMb();
        if (!guardLatched) {
            if (used <= limit) {
                return configured;
            }
            guardLatched = true;
            return Math.min(configured, GUARDED_BEAM);
        }
        // B59: the collector routinely frees a gigabyte between two layers, so a plain "used > limit"
        // test flipped 128 -> 48 -> 128 -> 48 inside one second (the elite fight logged exactly that).
        // Stay narrowed until the heap is comfortably down again.
        if (used * 100L < limit * GUARD_RELEASE_PCT) {
            guardLatched = false;
            return configured;
        }
        return Math.min(configured, GUARDED_BEAM);
    }

    /**
     * B72 (user m20740): replay parked candidates from EARLIER layers as well, not only the layer
     * that just dried up. False restores the old layer-locked rule, which is what the two-slime
     * fight ran into (kept=270, promoted=0). Kept switchable so a gate can A/B the change.
     */
    public static volatile boolean replayEarlier = true;

    public static void setReplayEarlier(boolean on) {
        replayEarlier = on;
    }

    /** Latch state of the heap guard (B59); reset at the start of every search. */
    private static volatile boolean guardLatched;

    public static boolean guardLatched() {
        return guardLatched;
    }

    public static void resetGuards() {
        guardLatched = false;
        deferredPhaseStarted = 0L;
        deferredCapped = false;
        // B107: "this step cannot be replayed" is a fact about THIS search; a new search re-learns it.
        rebuildPoison.clear();
        rebuildPoisonList.clear();
        promotedSkippedPoisoned = 0;
    }

    /*
     * B59: "no live branch left, so try the parked candidates" is where an unwinnable elite fight
     * spent 12 of its 15 seconds (4186 nodes + 2000 deferred, then no win anyway). The parked phase
     * is a bonus pass, not the proof, so it gets its own wall-clock ceiling.
     *
     * <p>B103: that ceiling is now charged per pass and scaled to the pass's own budget
     * ({@link #parkedCapFor(long)}), because one search runs several profiles in a row and a single
     * shared latch silently starved every profile after the first. The panel no longer offers a
     * deeper button to escape it (B43): the refusal reason itself is shown instead
     * ({@link PlanText#parkedNote(boolean, int, int, String)}).
     */
    public static volatile long deferredPhaseMs = Long.getLong("sts1solver.deferredPhaseMs", 6000L);
    private static volatile long deferredPhaseStarted;
    private static volatile boolean deferredCapped;

    /** Start the clock the first time a search falls back to its parked candidates. */
    public static void deferredPhaseBegin() {
        if (deferredPhaseStarted == 0L) {
            deferredPhaseStarted = System.currentTimeMillis();
        }
    }

    /** True once the parked-candidate pass has used its ceiling. */
    public static boolean deferredPhaseExpired() {
        if (deferredPhaseMs <= 0L || deferredPhaseStarted == 0L) {
            return false;
        }
        return System.currentTimeMillis() - deferredPhaseStarted > deferredPhaseMs;
    }

    public static boolean deferredPhaseCapped() {
        return deferredCapped;
    }

    /** Marks (and logs once) that the parked pass was cut short. */
    public static void deferredPhaseCap() {
        if (!deferredCapped) {
            deferredCapped = true;
            Log.warn("deferred phase: capped at " + deferredPhaseMs
                    + "ms -- no live branch was left, the parked candidates are the only work left;"
                    + " the result is a survival line, not proof the fight is lost");
        }
    }
    /**
     * B103 (user m03616): the ceiling belongs to ONE pass, not to the whole search. The panel runs
     * several search profiles back to back inside one resetGuards window; the old static latch let
     * profile 1 spend the only 6 seconds and left profiles 2..4 promoting nothing while the log said
     * "kept=2000 promoted=0 budgetLeftMs=2886" -- thousands of parked lines and 3 seconds left to
     * spend them with. A parked pass may now use min(deferredPhaseMs, a quarter of that pass' own
     * budget): the B59 protection (no single parked pass eats the run) survives, and every profile
     * gets its own shot instead of inheriting an empty clock.
     */
    public static long parkedCapFor(long passBudgetMs) {
        long cap = deferredPhaseMs;
        if (passBudgetMs > 0L) {
            cap = Math.min(cap <= 0L ? Long.MAX_VALUE : cap, Math.max(500L, passBudgetMs / 4L));
        }
        return cap;
    }

    /** Marks (and logs once) that one pass' parked work was cut short, naming that pass' ceiling. */
    public static void deferredPhaseCap(long capMs) {
        if (!deferredCapped) {
            deferredCapped = true;
            Log.warn("deferred phase: capped at " + capMs
                    + "ms for this pass -- no live branch was left, the parked candidates are the only"
                    + " work left; the result is a survival line, not proof the fight is lost");
        }
    }

    private static Profile profile = new Profile();

    // ---- B170 (user m20332): name the microSteps that cost more than a slice ------------------
    // The frame budget is checked BETWEEN microSteps (Session.advance), so one expensive step is
    // exactly one long frame. The 2026-11-09 22:22 elite log showed a 40ms slice plan with ~1/s
    // frames of 500-700ms and no evidence of what those steps were; this measures them instead of
    // guessing (loot = PostCombatLoot.settle, which runs inside the step for every winning child).
    /** B170: a slice the player can feel, even when its individual steps look cheap. */
    private static final long SLOW_SLICE_US = 120000L;

    private static final long SLOW_STEP_US = 60000L;
    private static final int SLOW_STEP_LOG_CAP = 40;
    private static int slowSteps;
    private static int slowLogged;
    private static long slowWorstMs;
    private static String slowWorstWhat = "-";
    /** Time spent inside PostCombatLoot.settle for this search (part of the step's own work). */
    private static long lootNanos;

    private static final Map<String, Integer> EXPAND_FAILURES = new LinkedHashMap<String, Integer>();

    private Solver() {
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    /** Last limits line written to the log, so a search only reports a settings change once. */
    private static String loggedLimits = "";

    /**
     * P2-58: say out loud which caps this search runs with. The player edits settings.txt by hand,
     * so without this line there is no way to tell "the tier did not apply" from "the fight was easy".
     */
    static void logLimits() {
        try {
            String line = sts1solver.search.Limits.describe();
            if (!line.equals(Solver.loggedLimits)) {
                Solver.loggedLimits = line;
                Log.info(line);
            }
        } catch (Throwable t) {
            // a settings read must never break a search
        }
    }

    public static Result solve(World world, int n, int n2, long l) {
        Solver.resetGuards();
        Solver.logLimits();
        try {
            Result result = Solver.solveInternal(world, n, n2, l, 0);
            return result;
        }
        finally {
            World.forceRestore();
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static Result solvePortfolio(World world, long l) {
        // B59: a parked-candidate ceiling belongs to ONE search. Without this reset the static clock
        // survived the previous search and the next one gave up on its parked work in the first layer.
        Solver.resetGuards();
        Solver.logLimits();
        try {
            Result result = Solver.solvePortfolioInternal(world, l);
            return result;
        }
        finally {
            World.forceRestore();
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    /**
     * P2-8: the beam profiles tried in order, narrow-and-fast first, wide-and-deep last. Same list
     * for the blocking and the sliced portfolio, so "compute" and "deep" run one algorithm and
     * only differ in how much budget they are given (merging the two entry points, user m06154).
     */
    /**
     * B44: the layer cap is a safety net, not a stopping rule (user m13104: "打六火我要条件最好的
     * 战斗路线，要打完打赢；实在赢不了才给最长路线"). A cap of 32/64/96 truncated a Hexaghost fight
     * halfway (the boss was still at 74/264 HP and the panel showed no further steps), because a
     * fight can need 20+ turns = 60+ layers. The real limit is the budget, and the digest set already
     * makes revisiting a state impossible, so a large cap here only guards against a runaway loop.
     */
    /**
     * B146 (user m11490): one gold is worth 80 score points, and spending gold costs the same -- the
     * same rate the growth book used to price gold at (25 gold = 1 HP, 1 HP = 2000 points), but now a
     * direct, always-on term instead of an out-of-combat budget. Measured against the fight's opening
     * gold, so a line that gains 50 gold is worth +4000 and a line that pays 30 gold loses 2400.
     */
    public static final int GOLD_POINTS = 80;
    public static final int MAX_SEARCH_LAYERS = 400;

    /**
     * P2-58: the layer cap comes from the settings tiers (settings.txt {@code limits=low|high|
     * unlimited}), so widening the search is a setting, not a rebuild. HIGH is the historical value.
     */
    private static int[][] portfolioProfiles() {
        int layers = Limits.layers();
        // B172 (user m21118): the widest profile used to be the literal 128, so under
        // limits=unlimited the Boss ladder (QuickSession, Limits.beam()=256) searched wider than
        // an ordinary fight did. It is a ceiling now: never narrower than the historical 128, and
        // never narrower than Limits.beam(). Read per call so a settings change is picked up.
        int top = Math.max(128, Limits.beam());
        return new int[][]{
                {10, layers, 0},
                {28, layers, 0},
                {64, layers, 0},
                {top, layers, 0}};
    }

    /** Test hook: the profile table the search really uses (B172 gate S232). */
    public static int[][] portfolioProfilesForTest() {
        return Solver.portfolioProfiles();
    }
    /** B179 shares; P7's tier driver reuses the same split for the rung ladders inside a tier. */
    static final int[] PORTFOLIO_SHARES = new int[]{35, 25, 25, 15};

    private static Result solvePortfolioInternal(World world, long l) {
        // B179 (user m23836): the four beams share ONE session. This used to build a fresh Session
        // per member from the same root, so the narrow member's expansions were redone -- same
        // boards, same children, same order -- by every wider member. Now a wider rung continues
        // from the layer the previous one reached; only the time/node share per rung remains, via
        // {@code deadlines} (the historical 35/25/25/15).
        long started = System.currentTimeMillis();
        int[][] profiles = Solver.portfolioProfiles();
        // P7 (user m00001): the ladder is now the LADDER INSIDE one potion tier; the tiers themselves
        // (0 potions, 1 potion, ... K) are run by PotionTieredSession and merged on net HP.
        int[] ladder = new int[profiles.length];
        for (int i = 0; i < profiles.length; i++) {
            ladder[i] = profiles[i][0];
        }
        PotionTieredSession session = new PotionTieredSession(world, ladder, profiles[0][1], l, 0);
        long deadline = started + l;
        while (!session.advance(Math.max(4L, deadline - System.currentTimeMillis()))) {
            if (System.currentTimeMillis() > deadline + 2000L) {
                session.requestStop();
            }
        }
        return session.result();
    }

    /** B179: the beams of the portfolio after the narrow rung (the ladder of the shared session). */
    private static int[] rungBeams(int[][] profiles) {
        int[] beams = new int[Math.max(0, profiles.length - 1)];
        for (int i = 1; i < profiles.length; ++i) {
            beams[i - 1] = profiles[i][0];
        }
        return beams;
    }

    /**
     * B179: cumulative wall-clock deadline per rung, from the historical shares of the job budget
     * ({@link #PORTFOLIO_SHARES}). Entry 0 is the narrow rung's deadline, the last is the whole budget.
     */
    static long[] rungDeadlines(int rungs, long budgetMs) {
        long[] out = new long[rungs];
        long cumulative = 0L;
        for (int i = 0; i < rungs; ++i) {
            cumulative += budgetMs * (long)PORTFOLIO_SHARES[Math.min(i, PORTFOLIO_SHARES.length - 1)] / 100L;
            out[i] = cumulative;
        }
        return out;
    }

    /** B171: package-visible so a sliced job (QuickSession) can merge passes the same way. */
    static boolean better(Result result, Result result2) {
        if (result2 == null) {
            return result != null;
        }
        if (result == null) {
            return false;
        }
        // B173 (user m21300): both sides of the merge now speak the same number the panel prints
        // (net HP), through the same {@link #compareRoutes}; passing net as the deficit and zeroing
        // postHeal/potionHp keeps that method's order untouched instead of duplicating it here.
        return Solver.compareRoutes(result.win, result.escaped, result.deathSaves,
                Solver.netOf(result), 0, 0, result.predictedTurns, result.layersRun,
                result2.win, result2.escaped, result2.deathSaves,
                Solver.netOf(result2), 0, 0, result2.predictedTurns,
                result2.layersRun) < 0;
    }

    /**
     * Route quality, in the order the reference project uses
     * (CombatSolver-main `src\Search\RouteQualityPolicy.cs:98-115` `ComparePrimary`):
     * won -> survives -> death-save uses -> strategic HP deficit -> growth -> combat end turn.
     *
     * Two things the reference explicitly does NOT have here, both of which we used to get wrong:
     *  - leftover BLOCK is not a quality dimension at all. In the reference block only appears in
     *    beam retention (`CombatBeamSolver.BeamRetentionPolicy.Ranking.cs:34-35`
     *    `UsefulDefensiveBlockReserve`), i.e. as a hint for which live nodes to keep, never as a
     *    reason to prefer one finished route over another. So a 9-turn win with 40 block left
     *    cannot beat a 7-turn clean win -- turns decide, block is not read.
     *  - plan LENGTH is not a dimension either. We used to prefer the longer plan, which is the
     *    opposite of "fewer turns is better"; the reference reads the turn the fight ended on.
     *
     * Deficit is in HP: hpLost plus the HP value of the potions spent (PotionBook tiers: 9/14/18).
     * Depth (`layers`) is only the last tiebreak, for lines that neither won nor fled.
     */
    /** Old signature, kept for the gates: no post-combat heal. */
    public static int compareRoutes(boolean win, boolean escaped, int deathSaves,
                                    int hpLoss, int potionHp, int turns, int layers,
                                    boolean win2, boolean escaped2, int deathSaves2,
                                    int hpLoss2, int potionHp2, int turns2, int layers2) {
        return Solver.compareRoutes(win, escaped, deathSaves, hpLoss, 0, potionHp, turns, layers,
                win2, escaped2, deathSaves2, hpLoss2, 0, potionHp2, turns2, layers2);
    }

    /**
     * m22600: the post-combat heal each route would get is subtracted from the HP it lost, so a line
     * that spends a spare energy on "Self Repair" ranks above the same line without it (and a line
     * that drinks potions to survive still pays for them).
     */
    public static int compareRoutes(boolean win, boolean escaped, int deathSaves,
                                    int hpLoss, int postHeal, int potionHp, int turns, int layers,
                                    boolean win2, boolean escaped2, int deathSaves2,
                                    int hpLoss2, int postHeal2, int potionHp2, int turns2, int layers2) {
        if (win != win2) {
            return win ? -1 : 1;
        }
        if (escaped != escaped2) {
            return escaped ? -1 : 1;
        }
        if (deathSaves != deathSaves2) {
            return Integer.compare(deathSaves, deathSaves2);
        }
        int deficit = Math.max(0, hpLoss - Math.max(0, postHeal)) + potionHp;
        int deficit2 = Math.max(0, hpLoss2 - Math.max(0, postHeal2)) + potionHp2;
        if (deficit != deficit2) {
            return Integer.compare(deficit, deficit2);
        }
        if (turns != turns2) {
            return Integer.compare(turns, turns2);
        }
        return Integer.compare(layers2, layers);
    }

    private static void noteExpandFailure(Throwable throwable) {
        String string = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        Integer n = EXPAND_FAILURES.get(string);
        int n2 = n == null ? 1 : n + 1;
        EXPAND_FAILURES.put(string, n2);
        if (n2 <= 3) {
            Log.warn("expand failed (" + n2 + "x): " + string);
        }
        if (n2 == 1) {
            Log.error("expand failed, stack follows", throwable.getCause() == null ? throwable : throwable.getCause());
        }
    }

    public static void clearExpandFailures() {
        EXPAND_FAILURES.clear();
    }

    public static String expandFailures() {
        StringBuilder stringBuilder = new StringBuilder();
        for (Map.Entry<String, Integer> entry : EXPAND_FAILURES.entrySet()) {
            if (stringBuilder.length() > 0) {
                stringBuilder.append(" | ");
            }
            stringBuilder.append(entry.getValue()).append('x').append(' ').append(entry.getKey());
        }
        return stringBuilder.toString();
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    /**
     * B124 (user m03444): the cards played so far this turn, in play order, inside the sandbox.
     *
     * <p>GameActionManager clears this list when a new turn starts, so an action that ends a turn
     * gives an empty list on the far side of the call. {@link #recordAutoPlays} treats a list that
     * is not an extension of the one seen before the action as "unknown" and records nothing rather
     * than inventing plays.
     */
    private static java.util.List<String> cardsPlayedNames() {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        try {
            if (AbstractDungeon.actionManager == null
                    || AbstractDungeon.actionManager.cardsPlayedThisTurn == null) {
                return out;
            }
            for (Object o : AbstractDungeon.actionManager.cardsPlayedThisTurn) {
                out.add(o instanceof AbstractCard ? ((AbstractCard) o).name : "");
            }
        } catch (Throwable ignored) {
            // a sandbox that cannot read the list simply records nothing
        }
        return out;
    }

    /**
     * B128 (user m05245): the turn an action ENDED in, never behind the one it started in.
     *
     * <p>After the fight ends, {@code room.endBattle()} calls {@code actionManager.clear()}, which
     * resets {@code GameActionManager.turn} (measured: 11 -> 1). That number is meaningless once
     * there is no fight, and taken literally it rewinds the plan: the panel drew a "第 1 回合" row
     * after the smoke bomb that left the fight. Clamping keeps every action's span >= 0.
     */
    private static int turnAfterOf(int turnBefore) {
        int t = GameActionManager.turn;
        return t < turnBefore ? turnBefore : t;
    }

    /**
     * B124 (user m03444): store the cards the fight played by itself during one action.
     *
     * <p>The player's own card is played first in almost every case, but an action can also fail to
     * play at all (refused, not offered), so the player's card is dropped by position -- the first
     * new entry that carries the action's card name -- instead of by name alone, or two 防御 (one
     * played by the player, one by the fight) would collapse into one entry.
     */
    private static void recordAutoPlays(Action action, java.util.List<String> before,
                                        java.util.List<String> after) {
        action.autoPlays.clear();
        Solver.recordAutoPlaysInto(action.autoPlays, action.isCard(), action.cardName, before, after);
    }

    /**
     * B135 (user m07728): the same delta, written into a branch's own list.
     *
     * <p>Expansion walks every answer to a selection screen on its own fork, and each answer decides
     * which card the fight then plays by itself (豹指导 -> PlayCardAction -> NewQueueCardAction).
     * Recording only the probe's all-first run and copying it onto every branch (via withChoice) made
     * the panel print the wrong "（自动：…）" card for any branch that answered differently -- and,
     * worse, made autopilot skip the wrong planned step. The list belongs to the branch, so it is
     * recorded per branch here.
     */
    private static void recordAutoPlaysInto(java.util.List<String> out, boolean ownCard, String ownName,
                                            java.util.List<String> before, java.util.List<String> after) {
        if (after.size() <= before.size()) {
            return;
        }
        for (int i = 0; i < before.size(); ++i) {
            if (!before.get(i).equals(after.get(i))) {
                // the turn restarted under us; the delta would be a lie
                return;
            }
        }
        boolean ownSeen = !ownCard;
        for (int i = before.size(); i < after.size(); ++i) {
            String name = after.get(i);
            if (!ownSeen && name.equals(ownName)) {
                ownSeen = true;
                continue;
            }
            if (name != null && !name.isEmpty()) {
                out.add(name);
            }
        }
    }

    /**
     * B135 (user m07728): the action a branch actually carries -- its answer plus the cards the fight
     * played by itself while that answer resolved. Both loop copies (the blocking portfolio and the
     * sliced session) go through here so a choice can never inherit another branch's auto-plays.
     */
    private static Action actionFor(Choice choice, Action action) {
        // B190 (user m01149): a step that answered a screen with NOTHING still has an answer -- the
        // click on 确定. It used to be folded away entirely (no label, no cached answer), so the
        // panel showed a plain 结束回合 and autopilot had nothing to give the picker.
        Action a = (choice.choice.isEmpty() && choice.screenKinds.isEmpty()) ? action
                : action.withChoice(choice.choice, choice.choiceDisplay, choice.choiceUuids,
                        choice.choicePrints, choice.screenKinds);
        a.autoPlays = new ArrayList<String>(choice.autoPlays);
        // B140 (user m09491): the answer travels with the KIND of screen it was written on.
        a.screenKinds = choice.screenKinds;
        // B203: the same for what those screens offered -- the states every branch's screens held.
        a.screenOffered = choice.screenOffered;
        return a;
    }

    private static List<Choice> expand(World world, Action action, int n) {
        return Solver.expandCore(world, action, n, true);
    }

    /**
     * B135 (user m07728): the shared walk. {@code applyNow} decides whether the walk applies an
     * Action itself or walks screens an action already queued in the manager opened (the probe's
     * test doubles do the latter, because a mod action cannot be named by the core gates).
     */
    private static List<Choice> expandCore(World world, Action action, int n, boolean applyNow) {
        ArrayList<Choice> out = new ArrayList<Choice>();
        java.util.List<java.util.List<String>> ids = new java.util.ArrayList<java.util.List<String>>();
        java.util.List<java.util.List<String>> displays = new java.util.ArrayList<java.util.List<String>>();
        java.util.List<java.util.List<String>> uuids = new java.util.ArrayList<java.util.List<String>>();
        java.util.List<java.util.List<String>> prints = new java.util.ArrayList<java.util.List<String>>();
        java.util.List<String> kinds = new java.util.ArrayList<String>();
        // B203 (user m01393): what each screen OFFERED, one entry per screen, laid out exactly like the
        // plan's answer chain so the live walk can compare entry by entry.
        java.util.List<String> offeredPrints = new java.util.ArrayList<String>();
        // B184: the six profile counters were declared and read but never written, so the slice
        // profile line could only ever print zeros. Charge the real spans here -- one node is exactly
        // fork + install + apply + readBack + digest + uninstall, which is also the split the slow
        // step line prints.
        long forkStart = System.nanoTime();
        World probeWorld = world.fork();
        Solver.profile.fork += System.nanoTime() - forkStart;
        Choice probe = new Choice();
        probe.child = probeWorld;
        boolean installed = false;
        boolean complete = false;
        try {
            long installStart = System.nanoTime();
            probeWorld.install();
            Solver.profile.install += System.nanoTime() - installStart;
            installed = true;
            // B122 (user m02740): the panel draws one row per turn and has to draw the turns the
            // fight skips by itself (TrancePower ends the turn at its start). Both ends of the
            // action are read here, inside the sandbox, where GameActionManager.turn is the
            // sandbox's own turn.
            if (applyNow && action != null) {
                action.turnBefore = GameActionManager.turn;
                action.turnAfter = action.turnBefore;
            }
            // B124 (user m03444): what the fight plays by itself while this action runs. TangPower
            // plays up to its stack count of hand cards on its own; recording the delta of
            // cardsPlayedThisTurn is how the plan learns about them without knowing about Tang.
            // B165 (user m17933): whether this action drew through the normalized open-screen random
            // (a Discovery/power-potion screen generates once instead of once per frame). Sampled
            // around the apply so the panel can mark exactly the steps whose random stream differs
            // from the untouched game's.
            long rngBefore = sts1solver.mirror.DiscoveryRandom.count();
            java.util.List<String> playedBefore = Solver.cardsPlayedNames();
            if (applyNow && action != null) {
                long applyStart = System.nanoTime();
                Legal.apply(action);
                Solver.profile.apply += System.nanoTime() - applyStart;
            }
            complete = expandScreens(ids, displays, uuids, prints, kinds, offeredPrints, n);
            if (applyNow && action != null) {
                action.turnAfter = Solver.turnAfterOf(action.turnBefore);
                if (sts1solver.mirror.DiscoveryRandom.count() != rngBefore) {
                    action.rngNormalized = true;
                }
            }
            Solver.recordAutoPlaysInto(probe.autoPlays,
                    action != null && action.isCard(),
                    action != null ? action.cardName : "",
                    playedBefore, Solver.cardsPlayedNames());
            // B205 (user m01995): the plan carries plain ids. The "which copy" detail lives in
            // Action.screenOffered (the states each screen offered), never in the answer string --
            // the answer string is what the panel prints.
            // B212 (user m03920): one slot per screen the walk actually found. `n` is the
            // per-screen OPTION cap the caller asked for (2 from both call sites), not a screen
            // count: a step that opened three or more pickers used to index past this array and
            // throw ArrayIndexOutOfBoundsException here, which dropped the whole action (and its
            // branches) out of the search -- the panel then printed 引擎异常 and lost the win line.
            int[] firstPick = new int[ids.size()];
            probe.choice = Solver.joinSegments(ids, firstPick);
            probe.choiceDisplay = Solver.joinSegments(displays, firstPick);
            probe.choiceUuids = Solver.joinSegments(uuids, firstPick);
            probe.choicePrints = Solver.joinSegments(prints, firstPick);
            probe.screenKinds = Solver.joinStrings(kinds);
            // B208 (user m02882): ONE entry per screen the sandbox opened -- exactly the screens the
            // engine answered here, the same count the live fight opens. B207 folded the entries of a
            // screen that offered only one card, which is what put the sandbox's plan one answer
            // short of its own screens: a replay walked the remaining screens looking for a "sel"
            // step that was never there and died in Legal with "not-settled: a screen is open".
            probe.screenOffered = Solver.joinScreens(offeredPrints);
            probeWorld.trimPresentation();
            long readBackStart = System.nanoTime();
            probeWorld.readBack();
            Solver.readChoice(probe);
            Solver.profile.readBack += System.nanoTime() - readBackStart;
            // B129 (user m05700): the escape is a fact about THIS action, not about the session's
            // best node: the plan that gets printed may be rebuilt from another stored line.
            if (action != null) {
                action.escaped = probe.escaped;
            }
            out.add(probe);
        } catch (Throwable t) {
            Solver.noteExpandFailure(t);
            World.forceRestore();
            return out;
        } finally {
            if (installed) {
                try {
                    long uninstallStart = System.nanoTime();
                    probeWorld.uninstall();
                    Solver.profile.uninstall += System.nanoTime() - uninstallStart;
                } catch (Throwable ignored) {
                    // the force-restore path already put the live world back
                }
            }
        }
        int screens = ids.size();
        if (screens == 0) {
            return out;
        }
        if (!complete) {
            // B212 (user m03920): say WHICH step could not be walked. Without the label the log
            // only had "a step opened more than 16 selection screen(s)" and no way to name the card
            // or the screen that kept reopening.
            Log.warnEvery("step-incomplete", 5000L, "step "
                    + (action == null ? "(already queued)" : action.label)
                    + " could not be walked to the end of its screen chain; the action has no branch");
            // B140 (user m09491): the walk stopped with a picker still waiting, so the digest this
            // probe read back is a HALF-DONE step, not the step's outcome. Storing it is how the
            // sandbox ended up "not having swapped the cards" in a fight that did swap them. A step
            // whose answers do not finish its chain has no branch at all.
            out.clear();
            return out;
        }
        // P2-57 (user m24721): every combination of answers across the screens this one action opens,
        // ordered so the most promising one is tried first, capped by the settings tier. Options are
        // already value-ordered per screen, so the rank sum is the priority (0 = the heuristics' first
        // pick on every screen). The all-first combination is the probe above.
        java.util.List<int[]> combos = Solver.combinations(ids);
        int limit = Math.max(1, Limits.combos());
        for (int i = 0; i < combos.size() && out.size() < limit; i++) {
            int[] pick = combos.get(i);
            World w = world.fork();
            Choice choice = new Choice();
            choice.child = w;
            boolean ok = false;
            try {
                w.install();
                ok = true;
                // B135 (user m07728): record what THIS branch's answer made the fight play, not the
                // probe's all-first run (which is what withChoice used to copy onto every branch).
                java.util.List<String> branchPlayedBefore = Solver.cardsPlayedNames();
                if (applyNow && action != null) {
                    Legal.apply(action);
                }
                boolean answered = true;
                java.util.List<String> branchKinds = new java.util.ArrayList<String>();
                // B207: the states each screen offered, walked in the same order as the answers. It is
                // usually the probe's own list, but a branch whose chain came out shorter walks fewer
                // screens, so the list is measured rather than inherited.
                java.util.List<String> branchOffered = new java.util.ArrayList<String>();
                for (int s = 0; s < screens && answered; s++) {
                    if (!Driver.pumpUntilSelection(24)) {
                        answered = false;
                        break;
                    }
                    Driver.SelectionKind kind = Driver.selectionKind();
                    branchOffered.add(Solver.stateRun(Driver.selectionCandidates()));
                    answered = Driver.resolveSelectionByNames(
                            ids.get(s).get(pick[s]), displays.get(s).get(pick[s]),
                            uuids.get(s).get(pick[s]), prints.get(s).get(pick[s]));
                    if (answered) {
                        branchKinds.add(kind == null ? "" : kind.name());
                    }
                    if (answered && Driver.lastAnswerUnfinished()) {
                        // B153: same rule as above, per branch.
                        answered = false;
                    }
                }
                // B140 (user m09491): a branch is only a step outcome when its last answer finished
                // the chain. If the fight opened one more picker than the walk recorded, this branch
                // has no answer for it, and the state would be read mid-chain (the swap never
                // performed, the piles as before) -- the exact silent divergence the user reported.
                if (answered && Driver.settleForReplay(12) != Driver.SelectionKind.NONE) {
                    answered = false;
                }
                if (!answered) {
                    Log.warnEvery("screens-branch", 5000L, "a branch left the screen chain unfinished"
                            + " (answer " + Solver.joinSegments(ids, pick) + "); it is not a step"
                            + " outcome, so it is not stored");
                } else {
                    Solver.recordAutoPlaysInto(choice.autoPlays,
                            action != null && action.isCard(),
                            action != null ? action.cardName : "",
                            branchPlayedBefore, Solver.cardsPlayedNames());
                    w.trimPresentation();
                    w.readBack();
                    Solver.readChoice(choice);
                    // B208 (user m02882): one entry per screen THIS branch answered, never folded --
                    // the plan has to answer every screen the fight can open, or the replay after it
                    // meets a screen with no answer and the search loses the line.
                    choice.choice = Solver.joinSegments(ids, pick);
                    choice.choiceDisplay = Solver.joinSegments(displays, pick);
                    choice.choiceUuids = Solver.joinSegments(uuids, pick);
                    choice.choicePrints = Solver.joinSegments(prints, pick);
                    choice.screenKinds = Solver.joinStrings(branchKinds);
                    // B203: the states the screens offered are the same for every branch -- they were
                    // read before any answer -- so each branch carries the probe's list.
                    choice.screenOffered = Solver.joinScreens(offeredPrints);
                    out.add(choice);
                }
            } catch (Throwable t) {
                Solver.noteExpandFailure(t);
                World.forceRestore();
            } finally {
                if (ok) {
                    try {
                        w.uninstall();
                    } catch (Throwable ignored) {
                        // nothing to put back after a failed install
                    }
                }
            }
        }
        return out;
    }

    /**
     * B135 (user m07728) test hook: walk screens an already-queued action opens and report, per
     * branch, "answer => cards the fight played by itself after that answer". The core gates cannot
     * name a mod action, so they queue a test double and read the arrows here -- the same walk the
     * search uses, not a copy of it.
     */
    public static java.util.List<String> expandQueuedAutoForTest(World world, int n) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        java.util.List<Choice> choices = Solver.expandCore(world, null, n, false);
        for (int i = 0; i < choices.size(); i++) {
            Choice c = choices.get(i);
            StringBuilder sb = new StringBuilder();
            sb.append(c.choice);
            sb.append(" => ");
            for (int j = 0; j < c.autoPlays.size(); j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append(c.autoPlays.get(j));
            }
            out.add(sb.toString());
        }
        return out;
    }

    /**
     * B138 (m08865) test hook: apply one action and report every screen the search's own walk sees
     * (with the options on each) plus the digest the walk leaves behind. The user's fight opened a
     * chain of pickers inside one step (an auto-played 豹指导 picks a card, that card opens two
     * grids); this is how a probe can read what the sandbox actually did with that chain.
     */
    public static java.util.List<String> screenWalkForTest(World world, Action action, int n) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        java.util.List<java.util.List<String>> ids = new java.util.ArrayList<java.util.List<String>>();
        java.util.List<java.util.List<String>> displays = new java.util.ArrayList<java.util.List<String>>();
        java.util.List<java.util.List<String>> uuids = new java.util.ArrayList<java.util.List<String>>();
        java.util.List<java.util.List<String>> prints = new java.util.ArrayList<java.util.List<String>>();
        World probeWorld = world.fork();
        boolean installed = false;
        try {
            probeWorld.install();
            installed = true;
            if (action != null) {
                Legal.apply(action);
            }
            Solver.expandScreens(ids, displays, uuids, prints,
                    new java.util.ArrayList<String>(), new java.util.ArrayList<String>(), n);
            out.add("digest: " + Digest.stable(Digest.ofCurrent()));
        } catch (Throwable t) {
            Solver.noteExpandFailure(t);
            World.forceRestore();
            out.add("walk threw: " + t);
            return out;
        } finally {
            if (installed) {
                try {
                    probeWorld.uninstall();
                } catch (Throwable ignored) {
                    // the force-restore path already put the live world back
                }
            }
        }
        for (int i = 0; i < ids.size(); i++) {
            StringBuilder sb = new StringBuilder();
            sb.append("screen ").append(i + 1).append(": ");
            for (int j = 0; j < ids.get(i).size(); j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append(ids.get(i).get(j));
            }
            out.add(sb.toString());
        }
        return out;
    }

    /**
     * Walks the selection screens one action opens and records every option of every screen.
     *
     * <p>Called with the action already applied, on a throwaway world. It answers each screen with
     * its first (best) option just to reach the next one -- those answers are the all-first
     * combination, so the walk doubles as the first branch instead of costing an extra fork.
     *
     * @param ids      filled with, per screen, the card ids of each option
     * @param displays filled with, per screen, the human names of each option
     * @param uuids    filled with, per screen, the uuids of each option
     * @param n        the caller's option count request (a floor for the settings tier)
     */
    private static boolean expandScreens(java.util.List<java.util.List<String>> ids,
                                        java.util.List<java.util.List<String>> displays,
                                        java.util.List<java.util.List<String>> uuids,
                                        java.util.List<java.util.List<String>> prints,
                                        java.util.List<String> kinds,
                                        java.util.List<String> offeredPrints, int n) {
        int maxScreens = Math.max(1, Limits.screens());
        // B212 (user m03920): a picker that reopens with the SAME kind and the SAME cards can only
        // ask the same question again -- answering it a second time cannot move the fight. One log
        // line per walk is enough to say so, and the walk stops instead of recording a 16-deep
        // answer chain that the live game will never ask for.
        String lastScreenKey = null;
        int sameScreen = 0;
        for (int s = 0; s < maxScreens; s++) {
            if (!Driver.pumpUntilSelection(24)) {
                // No picker is waiting: the chain this step opened is finished, and the state that
                // follows is the step's real outcome.
                return true;
            }
            Driver.SelectionKind kind = Driver.selectionKind();
            int cap = Limits.choiceBranches(kind, n);
            List<List<AbstractCard>> options = Driver.selectionOptions(cap);
            if (options == null || options.isEmpty()) {
                // B140: a picker with nothing to record means the answers for this step are
                // incomplete. Half a chain is not a step outcome; say so instead of storing it.
                Log.warnEvery("screens-empty", 5000L, "a " + kind + " screen opened with no option to"
                        + " record; the answers for this step are incomplete");
                return false;
            }
            java.util.List<String> screenIds = new java.util.ArrayList<String>();
            java.util.List<String> screenDisplays = new java.util.ArrayList<String>();
            java.util.List<String> screenUuids = new java.util.ArrayList<String>();
            java.util.List<String> screenPrints = new java.util.ArrayList<String>();
            // B203 (user m01393): the STATE of every card the picker offers, read once with the
            // option list it came from (the order candidates are enumerated in is the order of this
            // string, and that is the index a screenStates entry names).
            java.util.List<com.megacrit.cardcrawl.cards.AbstractCard> offered =
                    Driver.selectionCandidates();
            // B204: the STATE run (not the card-by-card print list) is what a live screen is compared
            // with: same states in any order is the same screen.
            String offeredRun = Solver.stateRun(offered);
            offeredPrints.add(offeredRun);
            String screenKey = kind + "#" + offeredRun + "#" + options.size();
            if (screenKey.equals(lastScreenKey)) {
                if (++sameScreen >= 2) {
                    Log.warnEvery("screens-repeat", 5000L, "the same " + kind + " screen ("
                            + options.size() + " option(s)) came back " + (sameScreen + 1)
                            + " times in a row with the same cards; one of the answers is not being"
                            + " consumed, so the answers for this step are incomplete");
                    return false;
                }
            } else {
                lastScreenKey = screenKey;
                sameScreen = 0;
            }
            for (int i = 0; i < options.size(); i++) {
                screenIds.add(Driver.namesOf(options.get(i)));
                screenDisplays.add(Driver.displayNamesOf(options.get(i)));
                screenUuids.add(Driver.uuidsOf(options.get(i)));
                // B98: the state each option carries, the identity a replay can still resolve after
                // the fork that chose it is gone.
                screenPrints.add(Driver.printsOf(options.get(i)));
            }
            ids.add(screenIds);
            displays.add(screenDisplays);
            uuids.add(screenUuids);
            prints.add(screenPrints);
            kinds.add(kind == null ? "" : kind.name());
            if (!Driver.resolveSelection(options.get(0))) {
                Log.warnEvery("screens-refused", 5000L, "the walk could not answer screen "
                        + (s + 1) + " (" + kind + "); the answers for this step are incomplete");
                return false;
            }
            if (Driver.lastAnswerUnfinished()) {
                // B153 (user m14038): the answer resumed a queue (an end turn behind the pick) that the
                // completion drain still could not settle. The state that follows is mid-chain, so it
                // is not this step's outcome -- same rule as B140 below.
                Log.warnEvery("screens-unfinished", 5000L, "answering screen " + (s + 1) + " (" + kind
                        + ") resumed work that did not settle; the answers for this step are incomplete");
                return false;
            }
        }
        // B139 (user m08865): reaching the cap is not a quiet end. The walk stops while the acting
        // action is still mid-chain, and the caller would then trim/read back/digest that half-done
        // state as if it were the step's outcome -- the recorded answers and the stored digest then
        // describe a fight that never happened. Pump once more so the last answer settles, and say
        // out loud that the answers are incomplete instead of pretending the chain ended.
        if (Driver.pumpUntilSelection(24)) {
            Log.warnEvery("screens", 5000L, "a step opened more than " + maxScreens
                    + " selection screen(s); the walk stopped there, so the answers recorded for this"
                    + " step are incomplete and the state read back is mid-chain"
                    + " (raise limits.screens= in settings.txt)");
            return false;
        }
        return true;
    }

    /**
     * Test hook (S139): the branch strings expand() would hand the search, for an action that is
     * already queued in the given world. No action is applied -- a mod action that asks twice cannot
     * be replayed by name from the core gates, so they enqueue a test double of the same shape.
     */
    public static java.util.List<String> expandQueuedForTest(World world, int n) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        java.util.List<java.util.List<String>> ids = new java.util.ArrayList<java.util.List<String>>();
        java.util.List<java.util.List<String>> displays = new java.util.ArrayList<java.util.List<String>>();
        java.util.List<java.util.List<String>> uuids = new java.util.ArrayList<java.util.List<String>>();
        java.util.List<java.util.List<String>> prints = new java.util.ArrayList<java.util.List<String>>();
        World probeWorld = world.fork();
        boolean installed = false;
        try {
            probeWorld.install();
            installed = true;
            Solver.expandScreens(ids, displays, uuids, prints,
                    new java.util.ArrayList<String>(), new java.util.ArrayList<String>(), n);
        } catch (Throwable t) {
            Solver.noteExpandFailure(t);
            World.forceRestore();
            return out;
        } finally {
            if (installed) {
                try {
                    probeWorld.uninstall();
                } catch (Throwable ignored) {
                    // the force-restore path already put the live world back
                }
            }
        }
        if (ids.isEmpty()) {
            return out;
        }
        out.add(Solver.joinSegments(ids, 0));
        java.util.List<int[]> combos = Solver.combinations(ids);
        int limit = Math.max(1, Limits.combos());
        for (int i = 0; i < combos.size() && out.size() < limit; i++) {
            out.add(Solver.joinSegments(ids, combos.get(i)));
        }
        return out;
    }

    /**
     * Index tuples over all screens, minus the all-first one, ordered by summed option rank.
     *
     * <p>A HeadChase opens two screens of ~7 options each: 49 combinations, all real outcomes, all
     * cheap. When the product explodes (three screens of 20) the per-screen lists are truncated to
     * keep the tuple count bounded; the truncated ones are the worst-rated options.
     */
    private static java.util.List<int[]> combinations(java.util.List<java.util.List<String>> ids) {
        final int screens = ids.size();
        int[] sizes = new int[screens];
        int total = 1;
        for (int i = 0; i < screens; i++) {
            sizes[i] = Math.max(1, ids.get(i).size());
            total *= sizes[i];
            if (total > 200000) {
                total = 200001;
                break;
            }
        }
        if (total <= 1) {
            return new java.util.ArrayList<int[]>();
        }
        final int[] cap = new int[screens];
        int product = 1;
        for (int i = 0; i < screens; i++) {
            cap[i] = sizes[i];
            product *= cap[i];
        }
        while (product > 20000) {
            int widest = 0;
            for (int i = 1; i < screens; i++) {
                if (cap[i] > cap[widest]) {
                    widest = i;
                }
            }
            if (cap[widest] <= 1) {
                break;
            }
            --cap[widest];
            product = 1;
            for (int i = 0; i < screens; i++) {
                product *= cap[i];
            }
        }
        java.util.List<int[]> all = new java.util.ArrayList<int[]>();
        int[] idx = new int[screens];
        while (true) {
            int rank = 0;
            for (int i = 0; i < screens; i++) {
                rank += idx[i];
            }
            if (rank > 0) {
                all.add(idx.clone());
            }
            int i = screens - 1;
            while (i >= 0 && idx[i] == cap[i] - 1) {
                idx[i] = 0;
                --i;
            }
            if (i < 0) {
                break;
            }
            ++idx[i];
        }
        java.util.Collections.sort(all, new java.util.Comparator<int[]>() {
            public int compare(int[] a, int[] b) {
                int ra = 0;
                int rb = 0;
                for (int i = 0; i < screens; i++) {
                    ra += a[i];
                    rb += b[i];
                }
                if (ra != rb) {
                    return ra - rb;
                }
                for (int i = 0; i < screens; i++) {
                    if (a[i] != b[i]) {
                        return a[i] - b[i];
                    }
                }
                return 0;
            }
        });
        return all;
    }

    /**
     * B204 (user m01614): "id@STATE" per card -- the id plus the state that tells two copies of one
     * card apart (打击 vs 打击+). B205 (user m01995): no "#k" any more; the slot number reached the
     * panel, so the plan answer is plain ids and this form is only used to COMPARE two screens (see
     * {@link #positionsOn}), never written into {@code Action.choice}.
     */
    public static String positionedIds(java.util.List<com.megacrit.cardcrawl.cards.AbstractCard> cards) {
        return Driver.positionedIds(cards);
    }

    /**
     * B204: the distinct states of one screen in enumeration order, "state,state,...".
     *
     * <p>The live screen and the screen the search walked are compared by this alone, NOT card by
     * card. Two same-state copies are identical to the game (same card, same numbers) -- the
     * engine's removal is order-preserving and nothing reads "which copy", so they are one choice
     * in two slots. A card for card comparison calls a screen "different" when the engine merely
     * filled its slots in another order (hand order after a rebuild), which is no difference at all.
     */
    public static String stateRun(java.util.List<com.megacrit.cardcrawl.cards.AbstractCard> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            com.megacrit.cardcrawl.cards.AbstractCard c = candidates.get(i);
            sb.append(i > 0 ? "," : "").append(c == null ? "?" : sts1solver.mirror.CardPrint.of(c));
        }
        return sb.toString();
    }

    /**
     * B204 (user m01614): the plan's positioned answer ("id@STATE#k,..."), re-expressed for the
     * screen that is open NOW -- same distinct cards in the same order as the ones the search
     * walked, so the k names the same copy. Returns plain ids ready to match.
     *
     * <p>B206 (user m02438): every entry that cannot be re-expressed (a state the live screen shows
     * at another cost, a copy number that does not resolve, an enumeration that moved) falls back to
     * the id the plan named -- the caller's matcher answers it by card face. Only an entry that does
     * not parse at all returns null. A slot is a hint about WHICH COPY, never a reason to refuse a
     * screen that clearly holds the card.
     *
     * <p>Nothing here knows about a card or a relic: it compares two enumerations of the same screen.
     *
     * <p>B204 rework (user m01642): when the caller also knows the screen's states in enumeration
     * order ({@code walked} = the search's, {@code liveStates} = the live screen's), a screen that
     * merely lists the same states in another order is NOT a different screen -- which copy sits in
     * which slot is unobservable -- so the answer is filled by state and the slot detail is
     * dropped. Only a different multiset of states refuses.
     */
    public static String positionsOn(String planned, String live, String walked) {
        return Solver.positionsOn(planned, live, walked, null, null);
    }

    /** {@link #positionsOn(String, String, String)} with both surfaces compared by state run. */
    public static String positionsOn(String planned, String live, String walked,
                                     String liveStates, String walkedStates) {
        if (planned == null || planned.isEmpty()) {
            return null;
        }
        String[] want = planned.split(",");
        String[] here = live == null || live.isEmpty() ? new String[0] : live.split(",");
        String[] before = walked == null || walked.isEmpty() ? new String[0] : walked.split(",");
        boolean sameSurface = sameStates(here, liveStates, walkedStates);
        if (sameSurface) {
            // The two screens hold the same cards, only in another order: which slot a copy sat in
            // is unobservable, so the answer is filled by state. (`here` is the live screen's own
            // "id@STATE" run, so the card really is offered; matching resolves it by state.)
            StringBuilder byState = new StringBuilder();
            for (int i = 0; i < want.length; i++) {
                byState.append(i > 0 ? "," : "").append(Solver.baseOf(want[i]));
            }
            return byState.toString();
        }
        boolean checkShape = here.length == before.length;
        boolean positional = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < want.length; i++) {
            String entry = want[i].trim();
            int hash = entry.lastIndexOf('#');
            // B205 (user m01995): a plan answer is plain ids again ("Strike_R,Defend_R"), and a plan
            // carried over from 1.0.7 still holds "id@STATE#k". Only an entry that carries a copy
            // number is resolved here -- it is the one that could land on the wrong copy. Anything
            // else is left to picksFor's own matchers (state first, then id), exactly as before.
            if (hash < 0) {
                sb.append(i > 0 ? "," : "").append(entry);
                continue;
            }
            positional = true;
            // B205: a "…#k" answer from an older build still replays, but only where the k-th copy
            // of that state is the same card on both screens.
            int k;
            try {
                k = Integer.parseInt(entry.substring(hash + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            // B206 (user m02438): the slot is a hint, not a demand. A copy number that does not
            // resolve -- because the live screen shows that card at another cost, or holds fewer
            // copies of it -- falls back to the plan's plain id for this entry and lets picksFor
            // answer it (face first, then id). Returning null here refused the whole screen over a
            // number the user never sees and the game never reads.
            String positionedNow = Solver.nthOfState(here, entry.substring(0, hash).trim(), k);
            if (positionedNow == null) {
                sb.append(i > 0 ? "," : "").append(entry.substring(0, hash).trim());
                continue;
            }
            if (checkShape && !entry.substring(0, hash).trim().equals(
                    Solver.nthOfState(before, entry.substring(0, hash).trim(), k))) {
                // The k-th copy of that state is a different card on the live screen than it was on
                // the walked one: the enumeration moved, so the position no longer means anything --
                // answer by the card the plan named instead.
                sb.append(i > 0 ? "," : "").append(entry.substring(0, hash).trim());
                continue;
            }
            sb.append(i > 0 ? "," : "").append(positionedNow);
        }
        return positional ? sb.toString() : planned;
    }

    /** B204: do the two surfaces hold the same state multiset (a different order is no difference)? */
    private static boolean sameStates(String[] here, String liveStates, String walkedStates) {
        if (here.length == 0 || liveStates == null || liveStates.isEmpty()
                || walkedStates == null || walkedStates.isEmpty()
                || !liveStates.equals(Solver.stateRunOf(here))) {
            return false;
        }
        String[] a = liveStates.split(",");
        String[] b = walkedStates.split(",");
        if (a.length != b.length) {
            return false;
        }
        String[] sa = (String[])a.clone();
        String[] sb = (String[])b.clone();
        java.util.Arrays.sort(sa);
        java.util.Arrays.sort(sb);
        return java.util.Arrays.equals(sa, sb);
    }

    /** The states of "id@STATE" entries, in their order ("id@STATE#k" -> "STATE"). */
    private static String stateRunOf(String[] entries) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.length; i++) {
            String entry = entries[i].trim();
            int hash = entry.lastIndexOf('#');
            String base = hash < 0 ? entry : entry.substring(0, hash).trim();
            int at = base.indexOf('@');
            sb.append(i > 0 ? "," : "").append(at < 0 ? base : base.substring(at + 1));
        }
        return sb.toString();
    }

    /** B205: "id@STATE#k" -> "id@STATE". */
    private static String baseOf(String entry) {
        String one = entry == null ? "" : entry.trim();
        int hash = one.lastIndexOf('#');
        return hash < 0 ? one : one.substring(0, hash).trim();
    }

    /** The "id@STATE#k" of the k-th entry of {@code listed} whose prefix is {@code idAndState}. */
    private static String nthOfState(String[] listed, String idAndState, int k) {
        int at = 0;
        for (int j = 0; j < listed.length; j++) {
            String entry = listed[j].trim();
            int hash = entry.lastIndexOf('#');
            String base = hash < 0 ? entry : entry.substring(0, hash);
            if (base.equals(idAndState)) {
                at++;
                if (at == k) {
                    return entry;
                }
            }
        }
        return null;
    }

    /** The old form (ids only), kept for the callers that never answer a screen (B204). */
    private static String joinSegments(java.util.List<java.util.List<String>> byScreen, int[] pick) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < byScreen.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            java.util.List<String> screen = byScreen.get(i);
            if (screen == null || screen.isEmpty()) {
                continue;
            }
            int at = pick == null || i >= pick.length ? 0
                    : Math.min(Math.max(pick[i], 0), screen.size() - 1);
            sb.append(screen.get(at) == null ? "" : screen.get(at));
        }
        return sb.toString();
    }

    /**
     * B203: '|' per screen, nothing between them -- for a list that already has one entry per screen
     * (the states every card a screen offered). No index is applied: a screen is its whole option list.
     */
    private static String joinScreens(java.util.List<String> perScreen) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < perScreen.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            String one = perScreen.get(i);
            sb.append(one == null ? "" : one);
        }
        return sb.toString();
    }

    /** The same at one fixed option index (the probe walk always takes index 0). */
    private static String joinSegments(java.util.List<java.util.List<String>> byScreen, int pick) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < byScreen.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            java.util.List<String> screen = byScreen.get(i);
            if (screen == null || screen.isEmpty()) {
                continue;
            }
            int at = Math.min(Math.max(pick, 0), screen.size() - 1);
            sb.append(screen.get(at) == null ? "" : screen.get(at));
        }
        return sb.toString();
    }

    /** One value per screen, joined with '|' (the recorded screen kinds travel the same way). */
    private static String joinStrings(java.util.List<String> byScreen) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < byScreen.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(byScreen.get(i) == null ? "" : byScreen.get(i));
        }
        return sb.toString();
    }


    private static void readChoice(Choice choice) {
        choice.hp = Solver.hp();
        choice.potions = Solver.potionsLeft();
        choice.potionHp = Solver.potionValueLeft();
        choice.mhp = Solver.monsterHp();
        choice.maxHealth = Solver.playerMaxHealth();
        choice.gold = Solver.playerGold();
        // m22600: read while this node's world is still installed -- see the field comment.
        choice.postHeal = sts1solver.search.PostCombatHealing.bound();
        Solver.readDeckGrowth(choice);
        choice.block = Solver.block();
        choice.energy = Solver.energyNow();
        choice.powers = Solver.powersNow();
        choice.alive = Solver.aliveCount();
        // B64: the engine's own death flag, not the HP number -- a corpse can be healed back above
        // zero (Bird Faced Urn), so hp alone "revived" a dead branch.
        choice.dead = sts1solver.drive.Driver.playerDead() || choice.hp <= 0;
        choice.win = !choice.dead && Solver.monstersDead();
        choice.escaped = Solver.roomEscaped();
        choice.monsters = Solver.monsterCount();
        // G3 (JUDGMENT.md): both death saves are observable after the fact -- Lizard Tail flips its
        // counter to -2, and Fairy in a Bottle can only leave the potion bar through death.
        choice.fairies = DeathSaves.fairiesInBar();
        choice.lizardTail = DeathSaves.lizardTailSpent();
        long digestStart = System.nanoTime();
        choice.digest = Digest.ofCurrent();
        Solver.profile.digest += System.nanoTime() - digestStart;
        // B176 (user m22904): the digest carries the action manager's phase/queues, which are not
        // part of the board. Record whether the board was finished so the visited key may drop them;
        // a mid-flight sample keeps its raw string, because merging it with the settled sample would
        // merge two different positions (the queue still owes state changes).
        choice.settled = sts1solver.drive.Driver.settled();
        if (!choice.settled) {
            // B178: snapshot the reason NOW; by log time the engine has drained and says "".
            choice.settleWhy = sts1solver.drive.Driver.settleBlock();
        }
        choice.positionKey = Solver.positionKey(choice.digest, choice.settled);
        // L2 (user m17363): the reward screen belongs to the fight that was actually won, so it is
        // read here, inside this node's installed world. GrowthBook.counting() is the same switch the
        // panel calls "局外": off means the player wants the fight's HP arithmetic only.
        if (choice.win && !choice.dead && GrowthBook.counting()) {
            // B170: measured, because this runs for every winning child and was the one part of a
            // step the fork/install/apply profile could not see.
            long lootStart = System.nanoTime();
            choice.loot = PostCombatLoot.settle();
            lootNanos += System.nanoTime() - lootStart;
        }
    }

    /** B170: one step over the slice budget, with the profile split that explains it. */
    static void noteSlowStep(long stepUs, int layer, int nodeIndex, int frontier, Action action,
            int branches, long workMs, long lootMs, long forkMs, long installMs, long applyMs,
            long readBackMs, long digestMs, long uninstallMs) {
        long ms = stepUs / 1000L;
        ++slowSteps;
        String what = action == null ? "-" : action.label;
        if (ms > slowWorstMs) {
            slowWorstMs = ms;
            slowWorstWhat = what;
        }
        if (slowLogged >= SLOW_STEP_LOG_CAP) {
            return;
        }
        ++slowLogged;
        Log.info("slow step: " + ms + "ms layer=" + layer + " node=" + nodeIndex + "/" + frontier
                + " action=" + what + " kind=" + (action == null ? "-" : action.kind)
                + " branches=" + branches + " work=" + workMs + "ms loot=" + lootMs + "ms"
                + " fork=" + forkMs + " install=" + installMs + " apply=" + applyMs
                + " readBack=" + readBackMs + " digest=" + digestMs + " uninstall=" + uninstallMs
                + " (B170)");
    }

    /** B170: per-search counters, reset where a search starts. */
    public static void resetSlowSteps() {
        slowSteps = 0;
        slowLogged = 0;
        slowWorstMs = 0L;
        slowWorstWhat = "-";
        lootNanos = 0L;
    }

    /** B170: one line for the search summary, so a log without the step detail still says the cost. */
    public static String slowStepSummary() {
        long threshold = SLOW_STEP_US / 1000L;
        if (slowSteps == 0) {
            return "none over " + threshold + "ms";
        }
        return slowSteps + " step(s) over " + threshold + "ms, worst " + slowWorstMs + "ms ("
                + slowWorstWhat + "), logged " + slowLogged + ", loot="
                + (lootNanos / 1000000L) + "ms total (B170)";
    }

    public static String profileLine() {
        return profile.line();
    }

    public static int lastTranspositions() {
        return Solver.profile.transpositions;
    }

    public static int lastNodeBudget() {
        return Solver.profile.nodeBudget;
    }

    public static int lastNodesExpanded() {
        return Solver.profile.nodes;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static Result solveInternal(World world, int n, int n2, long l, int n3) {
        Object object;
        int n4;
        Object object2;
        int n5;
        Node node;
        int n6;
        int n7;
        int n8;
        String string;
        int n9;
        int n10;
        profile = new Profile();
        World.tClone = 0L;
        World.tContainer = 0L;
        World.tColor = 0L;
        Legal.clearPromptCards();
        long l2 = System.currentTimeMillis();
        int n11 = 0;
        int n12 = 0;
        boolean bl = false;
        Choice choice = null;
        ArrayList<Action> arrayList = null;
        int n13 = 0;
        int n14 = 0;
        int n15 = 0;
        int n16 = 0;
        int n17 = 0;
        int fairies0 = 0;
        // P2-35/P2-36: growth is credited against the fight's opening numbers.
        int startMaxHp = 0;
        int startGold = 0;
        GrowthBook.Facts startGrowth = null;
        String string2 = "layers";
        // A3: candidates the beam had to drop, kept as paths so they can be re-run later.
        ArrayList<Deferred> deferred = new ArrayList<Deferred>();
        int promoted = 0;
        int promoNodes = 0;
        int promoExpansions = 0;
        // P2-8: keep searching after the first winning line. `bestWinLayer` is the depth of the
        // first win, `provenOptimal` means the win cannot be improved (zero HP lost, no potion),
        // `frontierDead` means the live frontier ran out at the current beam width.
        int bestWinLayer = -1;
        int layersAfterFirstWin = 0;
        boolean provenOptimal = false;
        boolean frontierDead = false;
        world.install();
        try {
            n10 = Solver.hp();
            n9 = Solver.potionValueLeft();
            startMaxHp = Solver.playerMaxHealth();
            startGold = Solver.playerGold();
            startGrowth = new GrowthBook.Facts(startMaxHp, startGold,
                    Solver.upgradedCards(), Solver.ritualDamage(), n9);
            fairies0 = DeathSaves.fairiesInBar();
            string = Digest.ofCurrent();
        }
        finally {
            world.uninstall();
        }
        world.setDigest(string);
        world.install();
        try {
            n8 = Solver.monsterHp();
            n7 = Solver.block();
            n6 = Solver.aliveCount();
        }
        finally {
            world.uninstall();
        }
        int n18 = Solver.score(0, 0, false, false, 0, n8, n7, n6, 0);
        Node node2 = node = new Node(world, new ArrayList<Action>(), n18, n18, 0, GameActionManager.turn, false, false, Solver.monsterCount(), 0, string);
        // B173: the root costs nothing by definition; every later node computes its own net.
        node2.netHpLoss = 0;
        ArrayList<Node> arrayList2 = new ArrayList<Node>();
        arrayList2.add(node);
        HashSet<String> hashSet = new HashSet<String>();
        Driver.begin();
        try {
            for (n5 = 0; n5 < n2; ++n5) {
                int n19;
                // A0 evidence: how many extra layers were explored AFTER a win was already known.
                if (bestWinLayer >= 0) {
                    ++layersAfterFirstWin;
                }
                if (System.currentTimeMillis() - l2 > l) {
                    string2 = "time";
                    break;
                }
                object2 = new HashMap();
                for (n4 = 0; n4 < arrayList2.size(); ++n4) {
                    object = (Node)arrayList2.get(n4);
                    if (System.currentTimeMillis() - l2 > l) {
                        string2 = "time";
                        break;
                    }
                    if (!hashSet.add(((Node)object).digest)) continue;
                    List<Action> list = Solver.candidates(((Node)object).world);
                    ++n12;
                    if (list.size() > n17) {
                        n17 = list.size();
                    }
                    for (int i = 0; i < list.size(); ++i) {
                        if (System.currentTimeMillis() - l2 > l) {
                            string2 = "time";
                            break;
                        }
                        Action action = list.get(i);
                        List<Choice> list2 = Solver.expand(((Node)object).world, action, 2);
                        for (int j = 0; j < list2.size(); ++j) {
                            Node node3;
                            Choice choice2 = list2.get(j);
                            ++n11;
                            if (list2.size() > 1) {
                                ++n13;
                            }
                            if (choice2.monsters != ((Node)object).monsters) {
                                ++n14;
                            }
                            choice2.child.setDigest(choice2.digest);
                            ArrayList<Action> arrayList3 = new ArrayList<Action>(((Node)object).path);
                            arrayList3.add(Solver.actionFor(choice2, action));
                            if (!choice2.dead && arrayList3.size() > n15) {
                                n15 = arrayList3.size();
                                choice = choice2;
                                arrayList = arrayList3;
                            }
                            int n20 = Math.max(0, n10 - choice2.hp);
                            int n21 = Math.max(0, n9 - choice2.potionHp);
                            // P2-35: growth already realized by this line is paid for out of the HP
                            // this node lost, exactly like a potion is (STS2 adds it to the score).
                            GrowthBook.Facts now2 = new GrowthBook.Facts(choice2.maxHealth, choice2.gold,
                                    choice2.upgradedCards, choice2.ritualDamage, choice2.potionHp);
                            int growthHp = GrowthBook.credit(arrayList3, now2, startGrowth);
                            int n20s = Math.max(0, n20 - growthHp);
                            int saves2 = DeathSaves.used(fairies0, choice2.fairies, choice2.lizardTail);
                            int n22 = Solver.score(n20s, n21, choice2.win, choice2.dead, n5 + 1, choice2.mhp, choice2.block, choice2.alive, saves2, choice2.gold - startGold) + (choice2.win ? Solver.lootPoints(choice2.loot) : 0) + (choice2.win ? Solver.healPoints(choice2.postHeal) : 0) + (choice2.win ? GrowthCredit.valueOf(arrayList3) : 0);
                            Node node4 = new Node(choice2.child, arrayList3, n22, Solver.rankOf(n22, choice2.block, choice2.energy, choice2.powers), n20, GameActionManager.turn, choice2.win, choice2.dead, choice2.monsters, saves2, choice2.digest);
                            node4.postHeal = choice2.postHeal;
                            node4.loot = choice2.loot;
                            node4.netHpLoss = Solver.netHpLoss(n20, growthHp, choice2.postHeal, n21);
                            // B173 keeps the ranking alone: score already puts 1 HP at 2000 points and
                            // the board terms (monster HP / alive / block) only break near ties, so a
                            // line that killed more never buys back the HP a worse one already lost.
                            // What changed is that the NUMBER the panel prints is the net one -- and
                            // since user m21803 that number has no loot term.
                            if (n22 > node2.score) {
                                node2 = node4;
                                bl = choice2.escaped && !choice2.win && EscapePolicy.mayRecommendEscape();
                            }
                            if (choice2.win && !choice2.dead) {
                                // P2-8 (JUDGMENT.md): a win is not the end of the search. Keep the
                                // cheapest win found so far -- score already prices 1 HP at 2000
                                // points -- and keep exploring the other live lines, because the
                                // first winning line the beam reaches is often not the cheapest.
                                if (!node2.win || n22 > node2.score) {
                                    node2 = node4;
                                }
                                string2 = "win";
                                if (bestWinLayer < 0) {
                                    bestWinLayer = n5 + 1;
                                }
                                if (n20 == 0 && n21 == 0) {
                                    provenOptimal = true;
                                }
                                // A finished battle has no children: never put a win back into the frontier.
                                continue;
                            }
                            java.util.Map<String, Node> nextMap = (java.util.Map<String, Node>) object2;
                            if ((node3 = nextMap.get(choice2.digest)) != null && n22 <= node3.score) continue;
                            nextMap.put(choice2.digest, node4);
                        }
                    }
                }
                n16 = n5 + 1;
                java.util.Map<String, Node> nextMap2 = (java.util.Map<String, Node>) object2;
                if (nextMap2.isEmpty()) {
                    if (!string2.equals("win")) {
                        string2 = "no-live-branches";
                        frontierDead = true;
                    }
                    break;
                }
                // A0 (docs/SEARCH-OPTIONS.md §7): never stop just because a winning line was
                // found. The search keeps using its budget until it can prove the result cannot be
                // improved (zero HP lost, zero potions), the live frontier runs dry, or the
                // budget/layer limit is reached. `bestWinLayer` is kept for the log only.
                ArrayList arrayList4 = new ArrayList(nextMap2.values());
                Collections.sort(arrayList4, new Comparator<Node>(){

                    @Override
                    public int compare(Node node, Node node2) {
                        if (node.rank != node2.rank) {
                            return node2.rank - node.rank;
                        }
                        if (node.score != node2.score) {
                            return node2.score - node.score;
                        }
                        return node.digest.compareTo(node2.digest);
                    }
                });
                arrayList2 = new ArrayList();
                int beam = Solver.effectiveBeam(n);
                // A1: optional rollout re-ranking of the candidates that could still make the beam.
                if (Solver.rolloutEnabled()) {
                    Solver.rerankByRollout(arrayList4, Math.max(beam, beam * Solver.ROLLOUT_REFINE_FACTOR));
                }
                for (int i = n19 = Math.min(n3, arrayList4.size()); i < arrayList4.size() && arrayList2.size() < beam; ++i) {
                    arrayList2.add((Node)arrayList4.get(i));
                }
                // A3: everything the beam could not keep is parked, not dropped. Beam position now
                // decides the order in which lines are tried, not whether they are ever tried.
                if (Solver.deferredEnabled()) {
                    java.util.Set<Node> kept = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Node, Boolean>());
                    kept.addAll(arrayList2);
                    for (int i = 0; i < arrayList4.size(); ++i) {
                        Node overflow = (Node)arrayList4.get(i);
                        if (!kept.contains(overflow)) {
                            Solver.defer(deferred, overflow, n5 + 1);
                        }
                    }
                }
            }
        }
        finally {
            Driver.end();
        }
        // ---- B44: never hand back a line that stops in the middle of a fight ----
        // A beam search can run out of live branches while one long line is still healthy: with the
        // Hexaghost fixture the frontier died at layer 37 and the panel showed the fight "ending"
        // with the boss at 71/264 HP. The rule (user m13104) is: keep going until the battle really
        // ends -- a win, or a line that dies -- and only then report the longest survival line.
        int extendedHpLoss = 0;
        boolean lineFinished = false;
        long left = l - (System.currentTimeMillis() - l2);
        if (Solver.promotionDepth() == 0 && Solver.extendDepth() == 0) {
            Log.info("extend check: choice=" + (choice != null) + " steps=" + (arrayList == null ? -1 : arrayList.size())
                    + " reason=" + string2 + " left=" + left + " proven=" + provenOptimal);
        }
        if (choice != null && arrayList != null && !provenOptimal
                && !"win".equals(string2) && left >= MIN_EXTEND_MS
                && Solver.extendDepth() < MAX_EXTEND_DEPTH) {
            int depth = Solver.extendDepth();
            EXTEND_DEPTH.set(depth + 1);
            Result sub = null;
            World seed = Solver.stateAfter(world, arrayList);
            try {
                sub = seed == null ? null : Solver.solveInternal(seed, n, Solver.MAX_SEARCH_LAYERS, left, 0);
            } catch (Throwable throwable) {
                Log.warn("extend from the deepest node failed: " + throwable);
            } finally {
                EXTEND_DEPTH.set(depth);
            }
            if (Solver.extendDepth() == 0) {
                Log.info("extend probe: sub=" + (sub == null ? "null" : sub.summary())
                        + " plan=" + (sub == null || sub.plan == null ? -1 : sub.plan.size())
                        + " child=" + (choice.child == null ? "null" : "world"));
            }
            if (sub != null) {
                promoNodes += sub.nodes;
                promoExpansions += sub.expansions;
                if (sub.plan != null && !sub.plan.isEmpty()) {
                    ArrayList<Action> combined = new ArrayList<Action>(arrayList);
                    for (int i = 0; i < sub.plan.size(); ++i) {
                        combined.add(sub.plan.get(i).action);
                    }
                    if (combined.size() > arrayList.size()) {
                        int combinedHp = Math.max(0, n10 - choice.hp) + sub.hpLoss;
                        arrayList = combined;
                        n15 = combined.size();
                        if (sub.win) {
                            int saves2 = node2.deathSaves + sub.deathSaves;
                            int sscore = Solver.score(combinedHp, 0, true, false, sub.predictedTurns, 0, 0, 0, saves2)
                                    + Solver.healPoints(sub.postHeal) + Solver.lootPoints(sub.loot);
                            node2 = new Node(choice.child, combined, sscore, sscore, combinedHp,
                                    sub.predictedTurns, true, false, choice.monsters, saves2, choice.digest);
                            node2.postHeal = sub.postHeal;
                            node2.loot = sub.loot;
                            // B173: the continuation's own post-fight heal is credited; its loot is not
                            // (user m21803: rewards never pay for lost HP).
                            node2.netHpLoss = Solver.netHpLoss(combinedHp, 0, sub.postHeal, 0);
                            string2 = "win";
                            Log.info("extend: the deepest line was continued to a win (+"
                                    + sub.plan.size() + " actions)");
                        } else {
                            extendedHpLoss = sub.hpLoss;
                            Log.info("extend: the deepest line was continued by " + sub.plan.size()
                                    + " more actions (hpLoss +" + sub.hpLoss + ")");
                        }
                    }
                }
            }
        }

        // ---- A3: spend the leftover budget on the candidates the beam had to drop ----
        // B103: same accounting as the sliced path's Session.promote(), so "deferred>0,
        // promoted==0" always comes with the refusal that caused it.
        String promoWhy = null;
        int promoDepthSkip = 0;
        int promoRebuildFail = 0;
        int promoPoisoned = 0;
        if (!provenOptimal && !deferred.isEmpty() && Solver.promotionDepth() < MAX_PROMOTION_DEPTH) {
            Solver.deferredPhaseBegin();
            Solver.sortDeferred(deferred);
            for (int i = 0; i < deferred.size(); ++i) {
                long remaining = l - (System.currentTimeMillis() - l2);
                // P2-41: the limit follows the budget left, not a fixed 6. See promotionLimit().
                int promoLimit = Solver.promotionLimit(remaining);
                if (promoted >= promoLimit) {
                    if (promoWhy == null) {
                        promoWhy = "已回放 " + promoted + " 条，到预算允许的上限";
                    }
                    break;
                }
                // B44: keep a slice of the budget for "finish the line" below; promotions are a
                // refinement, a battle that ends is the answer the player asked for.
                long extensionReserve = Math.max(300L, l / EXTEND_RESERVE_DIV);
                if (Solver.deferredPhaseExpired()) {
                    Solver.deferredPhaseCap();
                    if (promoWhy == null) {
                        promoWhy = "停牌阶段已用完 " + Solver.deferredPhaseMs + "ms（一次搜索共用）";
                    }
                    break;
                }
                if (remaining - extensionReserve < MIN_PROMOTION_MS) {
                    if (promoWhy == null) {
                        promoWhy = "剩余预算不足一次回放（还留了收尾用的预留）";
                    }
                    break;
                }
                Deferred entry = deferred.get(i);
                if (entry.layer >= n2) {
                    ++promoDepthSkip;
                    continue;
                }
                // B107: a step this search already proved it cannot replay -- do not spend the time
                // again, and count the line as skipped rather than failed.
                if (Solver.poisonedStepOf(entry.path) != null) {
                    ++promoPoisoned;
                    ++Solver.promotedSkippedPoisoned;
                    continue;
                }
                long materializeAt = System.currentTimeMillis();
                World seed = Solver.materialize(world, entry);
                Solver.notePromotionCost(System.currentTimeMillis() - materializeAt);
                if (seed == null) {
                    ++promoRebuildFail;
                    continue;
                }
                ++promoted;
                int depth = Solver.promotionDepth();
                Result sub;
                PROMOTION_DEPTH.set(depth + 1);
                try {
                    sub = Solver.solveInternal(seed, n, Math.max(1, n2 - entry.layer),
                            Math.max(MIN_PROMOTION_MS, remaining - extensionReserve), 0);
                }
                finally {
                    PROMOTION_DEPTH.set(depth);
                }
                promoNodes += sub.nodes;
                promoExpansions += sub.expansions;
                if (sub.plan == null || sub.plan.isEmpty()) {
                    continue;
                }
                int combinedHp = entry.hpLoss + sub.hpLoss;
                if (sub.win && (!node2.win || combinedHp < node2.hpLoss)) {
                    ArrayList<Action> combined = new ArrayList<Action>(entry.path);
                    for (int s = 0; s < sub.plan.size(); ++s) {
                        combined.add(sub.plan.get(s).action);
                    }
                    if (combined.size() <= entry.path.size()) {
                        continue;
                    }
                    int saves2 = entry.deathSaves + sub.deathSaves;
                    int sscore = Solver.score(combinedHp, 0, true, false, sub.predictedTurns, 0, 0, 0, saves2)
                            + Solver.healPoints(sub.postHeal) + Solver.lootPoints(sub.loot);
                    node2 = new Node(seed, combined, sscore, sscore, combinedHp, sub.predictedTurns, true, false, node2.monsters, saves2, entry.digest);
                    node2.postHeal = sub.postHeal;
                            node2.loot = sub.loot;
                    node2.netHpLoss = Solver.netHpLoss(combinedHp, 0, sub.postHeal, 0);
                    string2 = "win";
                    if (bestWinLayer < 0) {
                        bestWinLayer = entry.layer;
                    }
                    if (combinedHp == 0 && sub.provenOptimal) {
                        provenOptimal = true;
                        break;
                    }
                }
            }
            if (Solver.promotionDepth() == 0) {
                Log.info("A3 deferred: kept=" + deferred.size() + " promoted=" + promoted
                        + " limit=" + Solver.promotionLimit(l - (System.currentTimeMillis() - l2))
                        + " costMs=" + Math.round(Solver.promotionCostMs())
                        + " samples=" + Solver.promotionCostSamples()
                        + " budgetLeftMs=" + Math.max(0L, l - (System.currentTimeMillis() - l2)));
            }
        }
        n5 = 0;
        object2 = node2.path;
        n4 = node2.hpLoss;
        // B63: the "time" / "layers" reason was overwriting "win" as soon as the budget ran out
        // during the refine phase (A0 keeps searching after the first win). The fallback below then
        // saw "no win" and replaced a zero-damage winning line with the deepest LOSING line, while
        // still reporting win=true. Whether a win exists is node2.win, not the reason string.
        if (node2.win && !"win".equals(string2)) {
            Log.info("search: a win was already found when the search stopped (" + string2 + ")");
            string2 = "win";
        }
        if (!"win".equals(string2) && arrayList != null && arrayList.size() > node2.path.size()) {
            List<Step> list;
            int n24 = Math.max(0, n10 - choice.hp) + extendedHpLoss;
            // B43: once the search has found no winning line at all, the useful answer is the
            // longest line that survives, not the cheapest short one. The old rule demanded
            // hpLoss <= best + max(6, hp/5), so a 34-step line that dies at the end lost to a
            // 9-step stub and the panel ended mid-fight with the boss alive.
            arrayList = (ArrayList<Action>) Solver.finishLine(world, (List<Action>)arrayList, 8);
            lineFinished = Boolean.TRUE.equals(LINE_FINISHED.get());
            n15 = arrayList.size();
            List<Step> list3 = list = Solver.replay(world, (List<Action>)arrayList);
            if (list != null && list.size() == arrayList.size()) {
                object2 = arrayList;
                n4 = n24;
                n5 = 1;
                Log.info("route: no win found, reporting the deepest playable line (" + arrayList.size() + " actions, hpLoss=" + n24 + ")");
            } else {
                Log.info("route: the deepest line was not usable (steps=" + arrayList.size()
                        + " hpLoss=" + n24 + " bestSteps=" + node2.path.size()
                        + " replayed=" + (list == null ? -1 : list.size()) + ")");
            }
        }
        object = Solver.replay(world, (List<Action>)object2);
        int failedAt = Solver.lastReplayFailedAt();
        int replayedSteps = object == null ? 0 : ((List<?>)object).size();
        int plannedSteps = ((List<?>)object2).size();
        boolean verified = failedAt < 0 && replayedSteps == plannedSteps;
        String failedOn = Solver.lastReplayFailedOn();
        Solver.profile.nodes = n11 + promoNodes;
        Solver.profile.transpositions = hashSet.size();
        if (Solver.promotionDepth() == 0) {
            Log.info(profile.line());
        }
        // G2 (JUDGMENT.md): `win` stays the engine's verdict; the reference's extra `actionCount > 0`
        // condition is carried separately as Result.completeVictory (see 4e in JUDGMENT.md).
        Result result5 = new Result((List<Step>)object, node2.win, n4, node2.turn, n11 + promoNodes, n12 + promoExpansions, System.currentTimeMillis() - l2, string2, n16, n17, Legal.promptCards(), Solver.planEscaped((List<Step>)object, bl), n2, n13, n14, n15, node2.deathSaves, n5 != 0);
        result5.startHp = n10;
        result5.postHeal = node2.win ? Math.max(0, node2.postHeal) : 0;
        result5.loot = node2.win ? node2.loot : null;
        // B173 (user m21300): the number the panel and the merge read. The deepest-line
        // replacement (n5 != 0) has no heal or loot to credit, so its net is its raw loss.
        result5.netHpLoss = n5 != 0 ? n4 : node2.netHpLoss;
        result5.planVerified = verified;
        result5.planFailedAt = failedAt;
        result5.planFailedOn = failedOn;
        result5.planRequested = plannedSteps;
        if (!verified) {
            Log.warn("the printed line is shorter than the one the search found (replayed "
                    + replayedSteps + " of " + plannedSteps + " steps, first failure at "
                    + failedAt + (failedOn.isEmpty() ? "" : " on " + failedOn) + "); it is not offered as a win");
        }
        result5.provenOptimal = node2.win && provenOptimal;
        result5.lineFinished = lineFinished || node2.win;
        result5.frontierExhausted = frontierDead;
        result5.frontierLeft = frontierDead ? 0 : arrayList2.size();
        // Every way out of the loop except a dry frontier is a limit (time, node cap, layer cap,
        // stop request), so the flag needs no reason parsing.
        result5.cutShort = !frontierDead;
        result5.layersAfterFirstWin = layersAfterFirstWin;
        result5.deferred = deferred.size();
        result5.promoted = promoted;
        if (promoted == 0 && !deferred.isEmpty()) {
            result5.deferredWhy = promoWhy != null ? promoWhy
                    : Solver.parkedRefusal(promoDepthSkip, 0, 0, promoRebuildFail, promoPoisoned);
        }
        return result5;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static List<Step> replay(World world, List<Action> list) {
        ArrayList<Step> arrayList = new ArrayList<Step>();
        REPLAY_FAILED_AT.set(Integer.valueOf(-1));
        REPLAY_FAILED_ON.set("");
        REPLAY_FAILURE.set("");
        World world2 = world.fork();
        Driver.begin();
        try {
            world2.install();
            try {
                for (int i = 0; i < list.size(); ++i) {
                    Action action = list.get(i);
                    // B85 (m23938 / m24017): the turn a step belongs to is the turn it was TAKEN in,
                    // read BEFORE the action runs. Read afterwards, an end-turn step already reports
                    // the turn it advanced into and an extra-turn card (Vault) reports the NEXT turn,
                    // so both used to be guessed back with a "-1" inside Gameplay.turnOfStep(). That
                    // guess is wrong whenever the turn does not advance (poison killing the last
                    // monster inside the end turn -> the step was filed under turn 0 and the plan was
                    // dropped) and wrong for a CARD that advances it (Vault -> filed under the next
                    // turn). AutoWatcher records the same fact instead of reconstructing it: the card
                    // is tagged as ending the turn and its turn pipeline advances the turn
                    // (C:/Users/YOKI/Desktop/AutoWatcher-main src/WatcherVerbs.cs:272-282 and
                    // docs/VERIFICATION.md:72-74).
                    int turnBefore = GameActionManager.turn;
                    // B126: remember both ends on the action too, so the Step this replay builds
                    // reports how many turns the action moved the fight through.
                    action.turnBefore = turnBefore;
                    // B135 (user m07728): the plan that gets printed and executed is THIS replay, so
                    // its "（自动：…）" list is recorded here as well -- a line rebuilt from the
                    // deferred pool may never have passed through the branch that learned it.
                    java.util.List<String> replayPlayedBefore = Solver.cardsPlayedNames();
                    // B147 (user m12327): the sandbox's side of the play trace. Every play inside
                    // this action (the player's card and whatever the fight plays by itself) lands
                    // on the engine-side trace with the random position it happened at, so the plan
                    // carries what the simulation did and the live fight can be compared play by
                    // play instead of by a hand diff that cannot see order, duplicates or RNG.
                    long traceMark = sts1solver.mirror.PlayTrace.mark();
                    Legal.applyOrRefuse(action);
                    // The plan stores the answer it chose for any screen the action opened
                    // (Action.choice). Replaying without answering it left HAND_SELECT/GRID/
                    // card-reward screens open, so endTurn became a no-op and the line died at the
                    // next "no card at hand index N" -- the reason reported plans were far shorter
                    // than the explored line and autopilot had to recompute mid-fight.
                    // B78 (user m21889): a planned answer the screen does not offer means this
                    // line cannot be reproduced. The old fallback guessed with
                    // resolveSelectionHeuristically(), which answered with a different card and
                    // shifted every later hand position -- the printed plan and the verified plan
                    // were then two different games.
                    Solver.answerForReplay(action);
                    Solver.recordAutoPlays(action, replayPlayedBefore, Solver.cardsPlayedNames());
                    action.turnAfter = Solver.turnAfterOf(turnBefore);
                    world2.trimPresentation();
                    world2.readBack();
                    arrayList.add(new Step(action, Digest.ofCurrent(),
                            sts1solver.mirror.RngStamp.of(),
                            sts1solver.mirror.PlayTrace.describe(traceMark),
                            turnBefore, Solver.hp(), Solver.block(), EnergyPanel.totalCount,
                            Solver.monsterHps(), Solver.monsterMaxHps(), Solver.monsterNames()));
                }
            }
            finally {
                world2.uninstall();
            }
        }
        catch (Throwable throwable) {
            World.forceRestore();
            REPLAY_FAILED_AT.set(Integer.valueOf(arrayList.size()));
            REPLAY_FAILED_ON.set(arrayList.size() < list.size() && list.get(arrayList.size()) != null
                    ? list.get(arrayList.size()).label : "");
            if (throwable instanceof ReplayException) {
                String kind = ((ReplayException)throwable).kind;
                String detail = String.valueOf(throwable.getMessage());
                // The message already names the bucket ("… : not-offered: …"); do not say it twice.
                REPLAY_FAILURE.set(detail.indexOf(kind) >= 0 ? detail : kind + ": " + detail);
            } else {
                REPLAY_FAILURE.set(String.valueOf(throwable));
            }
            Log.warn("plan replay failed at step " + arrayList.size() + ": " + throwable);
        }
        finally {
            Driver.end();
        }
        return arrayList;
    }

    /**
     * B174 (user m21993 / m22084): what a planned potion actually buys the line.
     *
     * <p>The search charges the potion its {@link PotionBook} HP (9 for a standard one, 18000 points)
     * before it prefers a line, but nothing in the log ever said WHICH part of the fight that bought,
     * so "it drank a potion that changed nothing" could be neither confirmed nor refuted. This
     * rebuilds the same plan with every potion step removed and reports the first step where the two
     * lines diverge -- the step where the potion's effect actually showed up.
     *
     * <p>Read-only: it replays into a fork, puts the replay failure fields back and returns a line
     * for the log. It never touches the search, the result or the plan.
     */
    static String potionCounterfactual(World root, List<Action> plan, List<Step> with, int startHp) {
        if (root == null || plan == null || plan.isEmpty()) {
            return "";
        }
        ArrayList<Action> stripped = new ArrayList<Action>();
        ArrayList<Integer> at = new ArrayList<Integer>();
        int potions = 0;
        for (int i = 0; i < plan.size(); ++i) {
            Action a = plan.get(i);
            if (a != null && "potion".equals(a.kind)) {
                ++potions;
                continue;
            }
            stripped.add(a);
            at.add(Integer.valueOf(i));
        }
        if (potions == 0) {
            return "";
        }
        int failedAtBefore = Solver.lastReplayFailedAt();
        String failedOnBefore = Solver.lastReplayFailedOn();
        String failureBefore = Solver.lastReplayFailure();
        List<Step> without = Solver.replay(root, stripped);
        int failedAt = Solver.lastReplayFailedAt();
        String failure = Solver.lastReplayFailure();
        REPLAY_FAILED_AT.set(Integer.valueOf(failedAtBefore));
        REPLAY_FAILED_ON.set(failedOnBefore);
        REPLAY_FAILURE.set(failureBefore);
        boolean ok = failedAt < 0 && without != null && without.size() == stripped.size();
        String firstDiff = "none";
        if (with != null && without != null) {
            int n = Math.min(without.size(), at.size());
            for (int k = 0; k < n; ++k) {
                int planAt = at.get(k).intValue();
                if (planAt >= with.size()) {
                    break;
                }
                Step a = with.get(planAt);
                Step b = without.get(k);
                if (Solver.potionStepDiffers(a, b)) {
                    firstDiff = "@" + (planAt + 1) + " with[" + Solver.potionStepDesc(a) + "] without["
                            + Solver.potionStepDesc(b) + "]";
                    break;
                }
            }
        }
        Step lastWith = with == null || with.isEmpty() ? null : with.get(with.size() - 1);
        Step lastWithout = without == null || without.isEmpty() ? null : without.get(without.size() - 1);
        String line = "potion counterfactual (B174): potions=" + potions + " plan=" + plan.size()
                + " stripped=" + stripped.size() + " replayOk=" + ok
                + " with{loss=" + Math.max(0, startHp - (lastWith == null ? startHp : lastWith.hp))
                + " " + Solver.potionStepDesc(lastWith) + "}"
                + " without{loss=" + Math.max(0, startHp - (lastWithout == null ? startHp : lastWithout.hp))
                + " " + Solver.potionStepDesc(lastWithout) + "}"
                + " firstDiff=" + firstDiff;
        if (!ok) {
            line = line + " failure=" + failure;
        }
        return line;
    }

    /**
     * B174 gate hook: the HP value of the potions left in the bar -- the number the search subtracts
     * at a node to price what the route spent (see {@link #potionValueLeft}).
     */
    public static int potionValueForTest() {
        return Solver.potionValueLeft();
    }

    /** B174 gate hook: the counterfactual line for a plan (empty when the plan spends no potion). */
    public static String potionCounterfactualForTest(World root, List<Action> plan, List<Step> with, int startHp) {
        return Solver.potionCounterfactual(root, plan, with, startHp);
    }

    /**
     * B174 gate hook: the numeric twin of {@link #potionCounterfactual}. It replays the same plan with
     * every potion step removed and reports what that costs, so a gate can ask the only question the
     * log line cannot answer by eye: did the potion buy more than its PotionBook price?
     *
     * <p>Returns {@code {hpLossWithout, potions, replayOk?1:0, winWithout?1:0}} or null when the plan
     * spends no potion. "win" means the stripped replay walked the WHOLE plan and ended with every
     * monster down and the player alive -- i.e. the potions were not needed at all.
     */
    public static int[] potionSkipForTest(World root, List<Action> plan, int startHp) {
        if (root == null || plan == null || plan.isEmpty()) {
            return null;
        }
        ArrayList<Action> stripped = new ArrayList<Action>();
        int potions = 0;
        for (int i = 0; i < plan.size(); ++i) {
            Action a = plan.get(i);
            if (a != null && "potion".equals(a.kind)) {
                ++potions;
                continue;
            }
            stripped.add(a);
        }
        if (potions == 0) {
            return null;
        }
        int failedAtBefore = Solver.lastReplayFailedAt();
        String failedOnBefore = Solver.lastReplayFailedOn();
        String failureBefore = Solver.lastReplayFailure();
        List<Step> without = Solver.replay(root, stripped);
        int failedAt = Solver.lastReplayFailedAt();
        REPLAY_FAILED_AT.set(Integer.valueOf(failedAtBefore));
        REPLAY_FAILED_ON.set(failedOnBefore);
        REPLAY_FAILURE.set(failureBefore);
        boolean ok = failedAt < 0 && without != null && without.size() == stripped.size();
        Step last = without == null || without.isEmpty() ? null : without.get(without.size() - 1);
        boolean allDown = last != null && last.monsterHp != null;
        if (allDown) {
            for (int i = 0; i < last.monsterHp.length; ++i) {
                if (last.monsterHp[i] > 0) {
                    allDown = false;
                }
            }
        }
        boolean alive = last != null && last.hp > 0;
        int loss = last == null ? 0 : Math.max(0, startHp - last.hp);
        return new int[]{loss, potions, ok ? 1 : 0, ok && allDown && alive ? 1 : 0};
    }

    private static boolean potionStepDiffers(Step a, Step b) {
        if (a == null || b == null) {
            return a != b;
        }
        if (a.hp != b.hp || a.block != b.block || a.energy != b.energy || a.turn != b.turn) {
            return true;
        }
        if (a.monsterHp == null || b.monsterHp == null || a.monsterHp.length != b.monsterHp.length) {
            return true;
        }
        for (int i = 0; i < a.monsterHp.length; ++i) {
            if (a.monsterHp[i] != b.monsterHp[i]) {
                return true;
            }
        }
        return !(a.digest == null ? "" : a.digest).equals(b.digest == null ? "" : b.digest);
    }

    /**
     * B176 (user m22904): the key two samples of the SAME board share. With a settled board the
     * transient action-manager fields are stripped ({@link Digest#stable}); a mid-flight sample
     * keeps its raw digest, because a queue that has not run yet is a different position, not a
     * presentation difference.
     */
    /** Harness hook (B176): the visited key a digest would get at the given settle state. */
    public static String positionKeyForTest(String digest, boolean settled) {
        return Solver.positionKey(digest, settled);
    }

    /** B176: how many "kept raw" reasons were printed in this process (bounded, log only). */
    private static int settleReasonLogged;

    static String positionKey(String digest, boolean settled) {
        if (digest == null) {
            return "";
        }
        return settled ? Digest.stable(digest) : digest;
    }

    private static String potionStepDesc(Step s) {
        if (s == null) {
            return "hp=- b=- e=- mhp=- pw=-";
        }
        int mhp = 0;
        if (s.monsterHp != null) {
            for (int i = 0; i < s.monsterHp.length; ++i) {
                mhp += s.monsterHp[i];
            }
        }
        return "hp=" + s.hp + " b=" + s.block + " e=" + s.energy + " mhp=" + mhp + " " + Solver.potionPowers(s.digest);
    }

    private static String potionPowers(String digest) {
        if (digest == null) {
            return "pw=-";
        }
        int i = digest.indexOf(" pw=");
        if (i < 0) {
            return "pw=-";
        }
        int j = digest.indexOf(' ', i + 4);
        return j < 0 ? digest.substring(i + 1) : digest.substring(i + 1, j);
    }


    /**
     * Answers the select screen an action opened while a line is being rebuilt (B79, user m22205).
     *
     * <p>Two things matter here. (1) The plan's uuid decides which PHYSICAL card to pick, so the
     * replay copies the search's answer instead of choosing again -- an id matches the upgraded and
     * the plain copy, a name matches two identical copies in hand. (2) A refusal is retried ONCE
     * after letting the engine settle: the screen can open a few updates after the action that opens
     * it, so "the screen does not offer X" on the first read is sometimes only "not yet". Only a
     * refusal that survives the settle is a fact about the line, and it is raised with a bucket
     * (not-offered / not-settled) so the log can be read without guessing which one happened.
     */
    public static void answerForReplay(Action a) {
        if (a.isSelection()) {
            // B162: the answer IS the action. Legal.apply() already filled the picker and closed it,
            // so asking here again would pump a screen that is no longer open -- and refuse a line
            // that just replayed correctly.
            return;
        }
        String[] choices = Solver.segments(a.choice);
        if (choices.length <= 1) {
            // B106: ask the same question expansion asks ("is a screen WAITING for an answer"), not
            // "which screen enum is set". A screen the game is only closing is not a decision, and
            // reading it as one is what made a plan step demand an answer it never had.
            // B190 (user m01149): a screen answered with nothing is still a screen this step walked
            // (screenKinds is what says so), so the replay has to wait for it and confirm it empty
            // instead of treating the step as "asked nothing".
            boolean expected = (a.choice != null && !a.choice.isEmpty())
                    || (a.screenKinds != null && !a.screenKinds.isEmpty());
            if (!Driver.isAwaitingSelection()) {
                if (!expected) {
                    // No answer was recorded and no screen is waiting: this step asked nothing.
                    return;
                }
                // B108: the plan DID record an answer for this step, so a screen was expected here.
                // Returning silently would turn "this line cannot be replayed" into "it played fine"
                // -- exactly the kind of quiet pass the user forbade (m04606). Give the engine a few
                // frames (a screen can open a tick late) and then refuse with the real state.
                if (!Driver.pumpUntilSelection(8) || !Driver.isAwaitingSelection()) {
                    throw new ReplayException("not-settled", "the plan answered a screen ("
                            + a.choice + ") but none opened (" + Driver.selectionState() + ")");
                }
            }
            Solver.checkScreenKind(Solver.at(Solver.segments(a.screenKinds), 0), a, 1);
            // B205: the entry point that already knows the screen's recorded states compares them
            // with the live screen's, so "same cards, other order" is answered by state instead of
            // being refused. Passing no surfaces here made that comparison impossible.
            Solver.answerOneScreen(Solver.positionsOn(a.choice,
                            Driver.positionedIds(Driver.selectionCandidates()),
                            Solver.firstScreen(a.screenOffered),
                            Driver.stateRunOf(Driver.selectionCandidates()),
                            Solver.firstScreen(a.screenOffered)),
                    a.choice, a.choiceDisplay, a.choiceUuids, a.choicePrints, a,
                    Boolean.TRUE.equals(a.replayLive));
            Solver.checkChainFinished(a, 1);
            return;
        }
        // B86 / P2-57: one action can ask several questions in a row (HeadChase picks from the
        // discard pile and then from the draw pile). The plan stores one answer per screen, '|'
        // separated, and the replay answers them in the order the engine opens them.
        String[] displays = Solver.segments(a.choiceDisplay);
        String[] uuids = Solver.segments(a.choiceUuids);
        String[] prints = Solver.segments(a.choicePrints);
        String[] kinds = Solver.segments(a.screenKinds);
        for (int i = 0; i < choices.length; i++) {
            if (!Driver.pumpUntilSelection(24)) {
                // B140 (user m09491): the chain the plan recorded ends before the plan's answers
                // do. Stopping here would hand a mid-chain state to the caller as if it were the
                // step's outcome, so refuse the line instead.
                throw new ReplayException("chain-unfinished", "the line cannot be replayed: the step"
                        + " answered " + i + " of " + choices.length + " recorded screen(s) and the"
                        + " fight opened no more (" + Driver.selectionState() + "; kind="
                        + Driver.selectionKind() + " handScreen=" + Driver.handScreenSummary() + ")");
            }
            Solver.checkScreenKind(Solver.at(kinds, i), a, i + 1);
            // B205: same as the single-screen path -- with the recorded states the "same cards, other
            // order" case is answered by state; without them the chain used to refuse.
            Solver.answerOneScreen(Solver.positionsOn(choices[i],
                            Driver.positionedIds(Driver.selectionCandidates()),
                            Solver.at(Solver.segments(a.screenOffered), i),
                            Driver.stateRunOf(Driver.selectionCandidates()),
                            Solver.at(Solver.segments(a.screenOffered), i)),
                    choices[i], Solver.at(displays, i), Solver.at(uuids, i), Solver.at(prints, i), a,
                    Boolean.TRUE.equals(a.replayLive));
        }
        Solver.checkChainFinished(a, choices.length);
    }

    /**
     * B140 (user m09491): the screen now open must be the KIND the answer was written on.
     *
     * <p>The plan used to carry only the answers, so a replay filled whatever picker happened to be
     * open: when TangPower auto-played a different card, the answer landed on the wrong screen, the
     * mod's action read an empty selection and quietly did nothing, and the sandbox kept a state the
     * real fight never had. A mismatch is not a guess to fix -- it means this line is not replayable.
     */
    private static void checkScreenKind(String recorded, Action a, int screenNo) {
        if (recorded == null || recorded.isEmpty()) {
            return;
        }
        Driver.SelectionKind now = Driver.selectionKind();
        String here = now == null ? "NONE" : now.name();
        if (!recorded.equals(here)) {
            throw new ReplayException("screen-mismatch", "the line cannot be replayed: answer "
                    + screenNo + " of " + Solver.describeChoice(a) + " was recorded on a " + recorded
                    + " screen, but a " + here + " screen is open (" + Driver.selectionState() + ")");
        }
    }

    /**
     * B140 (user m09491): after the plan's last answer the chain has to be over.
     *
     * <p>If the fight opens one more picker, the plan has no answer for it. Reading the state there
     * is reading a half-done step (a swap that never ran, the piles unchanged) -- the silent
     * divergence the user reported. Refusing keeps the search honest: such a line is dropped rather
     * than stored with a state that never happened.
     */
    private static void checkChainFinished(Action a, int answered) {
        Driver.SelectionKind left = Driver.settleForReplay(12);
        if (left != Driver.SelectionKind.NONE) {
            throw new ReplayException("chain-unfinished", "the line cannot be replayed: after "
                    + answered + " answer(s) (" + Solver.describeChoice(a) + ") a " + left
                    + " screen is still waiting and the plan has none for it ("
                    + Driver.selectionState() + ")");
        }
    }

    /** Answers exactly one screen with one recorded answer, retrying once after a settle. */
    private static void answerOneScreen(String resolved, String recorded, String display, String uuids,
                                        String prints, Action a, boolean live) {
        // B204 (user m01614): "resolved" is the recorded answer re-expressed for the screen that is
        // open (positions resolved); null means the two enumerations do not line up.
        String fail = resolved == null
                ? "not-offered: the line's recorded answer [" + recorded
                        + "] names a copy this screen does not enumerate the same way" : null;
        // B204 (user m01642): "state + position + throw the line away" is only safe where a refusal
        // costs nothing -- a live answer can stop and ask for a recompute. In the sandbox a refusal
        // also costs the branch, so it keeps today's behaviour (state only, then id): the fork came
        // from the live game, so its first copy of a state IS the live game's first copy.
        if (resolved == null && !live) {
            resolved = recorded;
        }
        if (resolved == null) {
            Driver.refuseNow("not-offered", fail.substring("not-offered: ".length()));
            throw new ReplayException("not-offered", "the line cannot be replayed: " + fail);
        }
        if (Driver.resolveSelectionByNames(resolved, display, uuids, prints)) {
            return;
        }
        String first = Driver.lastSelectionFailure();
        Driver.SelectionKind after = Driver.settleForReplay(16);
        if (after != Driver.SelectionKind.NONE
                && Driver.resolveSelectionByNames(resolved, display, uuids, prints)) {
            Log.info("replay selection: answered " + Solver.describeChoice(a) + " after settling "
                    + Driver.lastSettlePumps() + " update(s) (first try said " + first + ")");
            return;
        }
        String second = Driver.lastSelectionFailure();
        throw new ReplayException(after == Driver.SelectionKind.NONE ? "not-settled" : "not-offered",
                "the line cannot be replayed: " + second
                        + (first.equals(second) ? "" : " -- the first read said " + first));
    }

    /** Splits a plan's '|' separated chain into one entry per screen ("" -> empty array). */
    private static String[] segments(String joined) {
        if (joined == null || joined.isEmpty()) {
            return new String[0];
        }
        return joined.split("\\|", -1);
    }

    /** The first '|' separated entry of a plan chain ("" -> ""). */
    private static String firstScreen(String joined) {
        String[] parts = Solver.segments(joined);
        return parts.length == 0 ? "" : parts[0];
    }

    /** Element {@code i} of an array that may be shorter than the chain. */
    private static String at(String[] values, int i) {
        return values != null && i < values.length ? values[i] : "";
    }

    /** The plan's answer, written for a human ("" when the step needs no answer). */
    private static String describeChoice(Action a) {
        if (a == null || a.choice == null || a.choice.isEmpty()) {
            return "?";
        }
        String text = a.choiceDisplay == null || a.choiceDisplay.isEmpty() ? a.choice : a.choiceDisplay;
        return text.replace("|", " → ");
    }

    private static int rankOf(int n, int n2, int n3, int n4) {
        return n + n2 * 4 + n3 * 40 + n4 * 150;
    }

    private static int energyNow() {
        try {
            return AbstractDungeon.player == null ? 0 : AbstractDungeon.player.energy.energy;
        }
        catch (Throwable throwable) {
            return 0;
        }
    }

    private static int powersNow() {
        try {
            return AbstractDungeon.player == null ? 0 : AbstractDungeon.player.powers.size();
        }
        catch (Throwable throwable) {
            return 0;
        }
    }

    private static int score(int n, int n2, boolean bl, boolean bl2, int n3, int n4, int n5, int n6, int deathSaves) {
        return score(n, n2, bl, bl2, n3, n4, n5, n6, deathSaves, 0);
    }

    /**
     * B146 (user m11490): the same formula plus the gold term. {@code goldDelta} is the node's gold
     * minus the fight's opening gold, so it is negative when the line spent gold. A dead line keeps
     * its flat -50M penalty: no amount of gold rescues it.
     */
    private static int score(int n, int n2, boolean bl, boolean bl2, int n3, int n4, int n5, int n6, int deathSaves, int goldDelta) {
        if (bl2) {
            return -50000000 - n * 100;
        }
        // One strategic HP deficit, then the board terms (JUDGMENT.md G1). n = HP lost,
        // n2 = HP value of the potions spent (PotionBook: 9 / 14 / 18 per tier), n3 = layer (turn),
        // n4 = monster HP, n5 = block, n6 = monsters alive.
        //
        // G5 (JUDGMENT.md): the exchange rates are explicit and pinned by gate S53, because this is
        // the one place where a single number replaces the reference's route-quality tuple:
        //   1 HP = 2000, 1 monster HP = 0.01 HP, 1 living monster = 0.2 HP,
        //   1 block = 0.001 HP, 1 layer = 0.0005 HP.
        // A potion is no longer a constant: the reference prices one by tier
        // (CombatSolver-main src\Search\PotionValuationRegistry.cs:58-70, 9/14/18 HP), and a route's
        // potion cost is the sum of the tiers it actually drank.
        // Leftover BLOCK is deliberately not a route-quality dimension: the reference reads block
        // only in beam retention (`CombatBeamSolver.BeamRetentionPolicy.Ranking.cs:34-35`
        // `UsefulDefensiveBlockReserve`), never when it ranks finished routes. Here it stays as a
        // small retention hint for LIVE / losing lines (a 9-turn win with 40 block must not beat a
        // 7-turn clean win), and dropped entirely once the route wins. Route ordering itself lives in
        // `compareRoutes`, which does not read block at all.
        int deficit = sts1solver.search.StrategicDeficit.of(n, 0, 0, 0, n2);
        int n7 = bl ? 10000000 : 0;
        // n3 (the layer the fight ended on) is subtracted below, i.e. a quicker win scores higher.
        int board = n4 * 20 + n6 * 400 + (bl ? 0 : n5 * 2) + n3;
        // G3 (JUDGMENT.md): a route that burned Lizard Tail / a Fairy in a Bottle ranks below a route
        // that did not, even at lower HP -- "fewer death saves" outranks "less HP lost" in the
        // reference's route-quality tuple. 1,000,000 points = 500 HP, so it is effectively a
        // lexicographic tier that still stays far below the 10,000,000 victory bonus.
        return n7 - deficit * sts1solver.search.StrategicDeficit.POINTS_PER_HP - board
                - DeathSaves.points(deathSaves) + goldDelta * goldRate();
    }

    /**
     * B146 (user m11490 + m11767): gold is an out-of-combat gain, so it is priced only while the panel
     * says "局外: 计". Reading the switch here -- instead of copying it into a flag -- keeps one owner
     * for that meaning: the same switch that prices max HP, upgrades and ritual damage also prices
     * gold. {@link GrowthBook#counting()} is a cached read; the per-node growth credit already calls
     * into {@link GrowthBook} every node, so this adds no new synchronisation cost.
     */
    public static int goldRate() {
        return GrowthBook.counting() ? GOLD_POINTS : 0;
    }

    /**
     * The exact score formula, exposed for gate S53 (JUDGMENT.md G2/G5) so the exchange rates and
     * the win/block invariant cannot drift silently. Production code always goes through the search.
     */
    public static int scoreOf(int hpLost, int potionsSpent, boolean win, boolean dead,
                              int layers, int monsterHp, int block, int monstersAlive) {
        return scoreOf(hpLost, potionsSpent, win, dead, layers, monsterHp, block, monstersAlive, 0);
    }

    /** Same formula plus the G3 death-save term (JUDGMENT.md). */
    public static int scoreOf(int hpLost, int potionsSpent, boolean win, boolean dead,
                              int layers, int monsterHp, int block, int monstersAlive,
                              int deathSaves) {
        return scoreOf(hpLost, potionsSpent, win, dead, layers, monsterHp, block, monstersAlive,
                deathSaves, 0);
    }

    /**
     * B146 (user m11490): the same formula plus the gold term, written for gates. {@code goldDelta}
     * is the gold the line gained or spent against the fight's opening gold.
     */
    public static int scoreOf(int hpLost, int potionsSpent, boolean win, boolean dead,
                              int layers, int monsterHp, int block, int monstersAlive,
                              int deathSaves, int goldDelta) {
        // This overload takes a COUNT of potions (what gate S53 and the old callers speak), so it
        // converts at the reference's floor rate first. The search itself prices each potion by its
        // own tier through score(hpLost, potionHp, ...).
        int potionHp = potionsSpent * (sts1solver.search.StrategicDeficit.POTION_POINTS
                / sts1solver.search.StrategicDeficit.POINTS_PER_HP);
        return score(hpLost, potionHp, win, dead, layers, monsterHp, block, monstersAlive, deathSaves, goldDelta);
    }

    /**
     * m22600: post-combat healing is worth its HP in score points. The amount comes from the NODE
     * ({@link Choice#postHeal}), never from the live player: the live value is identical for every
     * winning route, so pricing it here used to cancel itself out and the search never played
     * "Self Repair" even with energy to spare.
     */
    private static int healPoints(int healHp) {
        return Math.max(0, healHp) * sts1solver.search.StrategicDeficit.POINTS_PER_HP;
    }

    /**
     * L1 (user m17363): score added for the reward screen a winning line earns. The old expression
     * charged the ESCAPE terminal with {@code EscapePolicy.forfeitPoints()} (the loot given up) --
     * that was never wired into any score until B129 and it is now retired: a kill is priced by what
     * it actually earns, and an escape simply earns nothing (the stolen gold is already gone from
     * player.gold, which {@code Solver.score} prices separately). One term, one place, no double count.
     */
    private static int lootPoints(PostCombatLoot.Loot loot) {
        return loot == null ? 0 : loot.points();
    }

    /**
     * B173 (user m21300): THE ruler. Before this, three places each had their own idea of what a
     * line costs: the in-rung best used the raw score (growth and loot paid for HP), the cross-rung
     * merge and the final answer used {@code hpLoss - postHeal + potionHp} (no growth, no loot), and
     * the panel printed the raw {@code hpLoss} with no deduction at all. So a line could become the
     * "best node" while the number under it went UP, and the merge one rung later could pick a
     * different line again. One number, used by all three.
     *
     * <p>Everything is floored at zero as a group: a line cannot bank one credit twice, and a line
     * that took no damage cannot show negative HP lost. Potions are added on top, 1:1.
     *
     * <p>B173 correction (user m21803): the reward screen is <b>not</b> part of this number. The user
     * spelled out the failure mode -- a fight that costs 6 HP and then hands out the equivalent of 6
     * HP of loot would read as "0 HP lost", i.e. as the perfect line, which is false: the 6 HP are
     * gone and the rewards are a normal, roughly constant part of every win. Rewards are still
     * priced, but only inside {@link #score} for the in-rung ranking (B164), which is a separate
     * question from "how much blood did this line cost me". So the ruler has no loot term at all;
     * it cannot be reintroduced by accident without changing this signature.
     */
    static int netHpLoss(int hpLoss, int growthHp, int postHeal, int potionHp) {
        int net = Math.max(0, hpLoss) - Math.max(0, growthHp) - Math.max(0, postHeal);
        return Math.max(0, net) + Math.max(0, potionHp);
    }

    /**
     * B173: the net cost of an already-finished result. Falls back to the old formula for results
     * built by a gate or by an older path that never set {@link Result#netHpLoss}.
     */
    static int netOf(Result result) {
        if (result.netHpLoss >= 0) {
            return result.netHpLoss;
        }
        return Solver.netHpLoss(result.hpLoss, 0, result.postHeal, result.potionHpSpent);
    }

    /** B173: gate hook -- the ruler itself is package-private. */
    public static int netHpLossForTest(int hpLoss, int growthHp, int postHeal, int potionHp) {
        return Solver.netHpLoss(hpLoss, growthHp, postHeal, potionHp);
    }

    /** B173: gate hook -- {@link #netOf} is package-private. */
    public static int netOfForTest(Result result) {
        return Solver.netOf(result);
    }

    /**
     * B173 correction (user m21803): a finished result we know lost a given number of HP, so a gate
     * can give it a rich reward screen and prove the ruler still reports every HP of the loss.
     */
    public static Result resultWithLossForTest(int hpLoss) {
        return new Result(new ArrayList<Step>(), false, Math.max(0, hpLoss), 0, 0, 0, 0L,
                "test", 0, 0, new ArrayList<String>(), false, 0, 0, 0, 0, 0, false);
    }

    /** B173: gate hook -- {@link #better} is package-private. */
    public static boolean betterForTest(Result result, Result result2) {
        return Solver.better(result, result2);
    }

    private static int monsterCount() {
        try {
            return AbstractDungeon.getMonsters() == null ? 0 : AbstractDungeon.getMonsters().monsters.size();
        }
        catch (Throwable throwable) {
            return 0;
        }
    }

    private static int aliveCount() {
        try {
            int n = 0;
            ArrayList arrayList = AbstractDungeon.getMonsters().monsters;
            for (int i = 0; i < arrayList.size(); ++i) {
                AbstractMonster abstractMonster = (AbstractMonster)arrayList.get(i);
                if (abstractMonster == null || abstractMonster.isDying || abstractMonster.isEscaping || abstractMonster.currentHealth <= 0) continue;
                ++n;
            }
            return n;
        }
        catch (Throwable throwable) {
            return 0;
        }
    }

    private static int monsterHp() {
        try {
            int n = 0;
            ArrayList arrayList = AbstractDungeon.getMonsters().monsters;
            for (int i = 0; i < arrayList.size(); ++i) {
                AbstractMonster abstractMonster = (AbstractMonster)arrayList.get(i);
                if (abstractMonster == null || abstractMonster.isDying || abstractMonster.currentHealth <= 0) continue;
                n += abstractMonster.currentHealth;
            }
            return n;
        }
        catch (Throwable throwable) {
            return 0;
        }
    }

    private static int block() {
        AbstractPlayer abstractPlayer = AbstractDungeon.player;
        return abstractPlayer == null ? 0 : abstractPlayer.currentBlock;
    }

    private static int[] monsterHps() {
        try {
            ArrayList arrayList = AbstractDungeon.getMonsters().monsters;
            int[] nArray = new int[arrayList.size()];
            for (int i = 0; i < arrayList.size(); ++i) {
                nArray[i] = arrayList.get(i) == null ? 0 : ((AbstractMonster)arrayList.get((int)i)).currentHealth;
            }
            return nArray;
        }
        catch (Throwable throwable) {
            return new int[0];
        }
    }

    private static int[] monsterMaxHps() {
        try {
            ArrayList arrayList = AbstractDungeon.getMonsters().monsters;
            int[] nArray = new int[arrayList.size()];
            for (int i = 0; i < arrayList.size(); ++i) {
                nArray[i] = arrayList.get(i) == null ? 0 : ((AbstractMonster)arrayList.get((int)i)).maxHealth;
            }
            return nArray;
        }
        catch (Throwable throwable) {
            return new int[0];
        }
    }

    private static String[] monsterNames() {
        try {
            ArrayList arrayList = AbstractDungeon.getMonsters().monsters;
            String[] stringArray = new String[arrayList.size()];
            // The same labels the plan rows use, ranks included: a step's target is matched against
            // this array (MechanicProbe.targetsAlive), so both sides must be written by one rule.
            java.util.List<AbstractMonster> whole = new java.util.ArrayList<AbstractMonster>();
            for (int i = 0; i < arrayList.size(); ++i) {
                if (arrayList.get(i) != null) {
                    whole.add((AbstractMonster)arrayList.get(i));
                }
            }
            for (int i = 0; i < arrayList.size(); ++i) {
                stringArray[i] = arrayList.get(i) == null
                        ? "?" : MonsterLabels.of((AbstractMonster)arrayList.get(i), whole);
            }
            return stringArray;
        }
        catch (Throwable throwable) {
            return new String[0];
        }
    }

    /**
     * B129 (user m05700): does the line that is actually being printed end by leaving the fight?
     *
     * <p>The session kept that fact in a single flag that only followed its best node; the printed
     * plan can come from a different stored line (the deepest one, a split), and then the panel
     * showed a plan ending in the smoke bomb while the result still said the fight was running.
     * The flag now rides on the action, and the fallback keeps the old answer for lines whose last
     * action was never expanded here.
     */
    private static boolean planEscaped(List<Step> steps, boolean fallback) {
        if (steps != null && !steps.isEmpty()) {
            Step last = steps.get(steps.size() - 1);
            if (last.action != null && last.action.escaped) {
                return EscapePolicy.mayRecommendEscape();
            }
        }
        return fallback;
    }

    private static boolean roomEscaped() {
        // B129 (m05700 #1): "smoked" is set the moment the potion is used, but the fight is only
        // really left once the escape animation has finished and the room called endBattle(). The
        // sandbox runs that frame in Driver.tickEscapeFrame(); a node taken before it must not count
        // as an escape, otherwise a truncated drain could bank the escape value without leaving.
        try {
            return AbstractDungeon.getCurrRoom() != null && AbstractDungeon.getCurrRoom().smoked
                    && Driver.combatOver();
        }
        catch (Throwable throwable) {
            return false;
        }
    }

    private static int hp() {
        AbstractPlayer abstractPlayer = AbstractDungeon.player;
        return abstractPlayer == null ? 0 : abstractPlayer.currentHealth;
    }

    private static int playerMaxHealth() {
        AbstractPlayer p = AbstractDungeon.player;
        return p == null ? 0 : p.maxHealth;
    }

    private static int playerGold() {
        AbstractPlayer p = AbstractDungeon.player;
        return p == null ? 0 : p.gold;
    }

    /** Cards the master deck already has upgraded (Lesson Learned's unit, P2-36). */
    private static int upgradedCards() {
        AbstractPlayer p = AbstractDungeon.player;
        if (p == null || p.masterDeck == null || p.masterDeck.group == null) {
            return 0;
        }
        int n = 0;
        java.util.ArrayList<AbstractCard> g = p.masterDeck.group;
        for (int i = 0; i < g.size(); i++) {
            AbstractCard c = g.get(i);
            if (c != null && c.upgraded) {
                n++;
            }
        }
        return n;
    }

    /**
     * Sum of the permanent damage the deck's Ritual Dagger carries (its {@code misc}), P2-36.
     * RitualDaggerAction bumps {@code misc} on the master deck card when the dagger kills, so the
     * delta across a line is exactly the damage it gained.
     */
    private static int ritualDamage() {
        AbstractPlayer p = AbstractDungeon.player;
        if (p == null || p.masterDeck == null || p.masterDeck.group == null) {
            return 0;
        }
        int n = 0;
        java.util.ArrayList<AbstractCard> g = p.masterDeck.group;
        for (int i = 0; i < g.size(); i++) {
            AbstractCard c = g.get(i);
            if (c != null && "RitualDagger".equals(c.cardID)) {
                n += Math.max(0, c.misc);
            }
        }
        return n;
    }

    /** One pass over the master deck for both P2-36 units, so a node pays for it once. */
    private static void readDeckGrowth(Choice choice) {
        AbstractPlayer p = AbstractDungeon.player;
        if (p == null || p.masterDeck == null || p.masterDeck.group == null) {
            return;
        }
        int up = 0;
        int ritual = 0;
        java.util.ArrayList<AbstractCard> g = p.masterDeck.group;
        for (int i = 0; i < g.size(); i++) {
            AbstractCard c = g.get(i);
            if (c == null) {
                continue;
            }
            if (c.upgraded) {
                up++;
            }
            if ("RitualDagger".equals(c.cardID)) {
                ritual += Math.max(0, c.misc);
            }
        }
        choice.upgradedCards = up;
        choice.ritualDamage = ritual;
    }

    /**
     * P7 (user m00001): how many potions the search may actually drink from the root. Deliberately
     * stricter than {@link #potionsLeft()}: a Smoke Bomb sits in the belt but Legal never offers it
     * as a drink. P7c: this is no longer the tier ceiling by itself -- see {@link #potionSlots(World)}
     * and {@code PotionTieredSession.tierCount}: a tier above today's count still differs whenever
     * the fight can create a potion (Entropic Brew, Alchemize, mod potions), so the ceiling is the
     * belt capacity, and this count only decides whether the job tiers at all.
     */
    static int potionCountUsable(World world) {
        int n = 0;
        try {
            world.install();
            try {
                ArrayList bar = AbstractDungeon.player.potions;
                for (int i = 0; i < bar.size(); ++i) {
                    Object p = bar.get(i);
                    if (p == null || p instanceof PotionSlot) continue;
                    if (PotionBook.usable((com.megacrit.cardcrawl.potions.AbstractPotion)p)) ++n;
                }
            } finally {
                world.uninstall();
            }
        } catch (Throwable throwable) {
            return 0;
        }
        return n;
    }

    /**
     * P7c (user m00172): the belt capacity the GAME reports, not a constant. Vanilla is three slots,
     * but Potion Belt and mods raise {@code AbstractPlayer.potionSlots}, and that capacity -- not
     * today's potion count -- bounds how many potions a line may drink, because a fight can refill
     * the belt (Entropic Brew, Alchemize, mod potions). Falls back to the live belt size when the
     * field reports nothing, and 0 when the game cannot be inspected.
     */
    static int potionSlots(World world) {
        try {
            world.install();
            try {
                if (AbstractDungeon.player == null) return 0;
                int slots = AbstractDungeon.player.potionSlots;
                if (slots <= 0) {
                    ArrayList bar = AbstractDungeon.player.potions;
                    slots = bar == null ? 0 : bar.size();
                }
                return Math.max(0, slots);
            } finally {
                world.uninstall();
            }
        } catch (Throwable throwable) {
            return 0;
        }
    }

    /** P7: potion steps already taken along this line. */
    static int potionUses(List<Action> path) {
        int n = 0;
        for (int i = 0; i < path.size(); ++i) {
            Action a = path.get(i);
            if (a != null && "potion".equals(a.kind)) ++n;
        }
        return n;
    }

    /** P7: the same candidate list with every potion action removed (the tier's ceiling is reached). */
    static List<Action> withoutPotions(List<Action> acts) {
        ArrayList<Action> out = new ArrayList<Action>(acts.size());
        for (int i = 0; i < acts.size(); ++i) {
            Action a = acts.get(i);
            if (a == null || !"potion".equals(a.kind)) out.add(a);
        }
        return out;
    }

    private static int potionsLeft() {
        try {
            int n = 0;
            ArrayList arrayList = AbstractDungeon.player.potions;
            for (int i = 0; i < arrayList.size(); ++i) {
                if (arrayList.get(i) == null || arrayList.get(i) instanceof PotionSlot) continue;
                ++n;
            }
            return n;
        }
        catch (Throwable throwable) {
            return 0;
        }
    }

    /**
     * The HP value of every potion still in the bar (PotionBook tiers). A route's potion cost is
     * this number at the root minus this number at the node, so a cheap potion and a Fairy in a
     * Bottle no longer cost the same.
     */
    private static int potionValueLeft() {
        try {
            int hp = 0;
            ArrayList bar = AbstractDungeon.player.potions;
            for (int i = 0; i < bar.size(); ++i) {
                Object p = bar.get(i);
                if (p == null || p instanceof PotionSlot) continue;
                hp += PotionBook.valueHp((com.megacrit.cardcrawl.potions.AbstractPotion)p);
            }
            return hp;
        }
        catch (Throwable throwable) {
            return 0;
        }
    }

    private static boolean monstersDead() {
        // B128 (user m05245): a fight the player LEFT is over, but it is not a victory.
        // room.endBattle() clears the action queue and (through onVictory) heals -- that made
        // Driver.combatOver() true while getCurrRoom().smoked says the player ran away, and the
        // search booked the smoke bomb line as a WIN with rewards.
        return Driver.combatOver() && !Solver.roomEscaped();
    }

    private static List<Action> candidates(World world) {
        world.install();
        try {
            // B125 (m03996 #2): a decided fight has no decisions; Legal.actions() answers empty
            // for it, and the search must treat that as TERMINAL instead of inventing a step.
            if (Driver.combatOver()) {
                return new ArrayList<Action>();
            }
            // B162 (user m16041): a screen that is already waiting is the decision. Answering it is a
            // step of the plan, so a search started while the player is looking at a picker produces
            // "答选牌（选：…）" first instead of a plan that starts one click too late.
            List<Action> waiting = Legal.selectionActions();
            if (!waiting.isEmpty()) {
                return waiting;
            }
            List<Action> list = Legal.actions();
            return list;
        }
        finally {
            world.uninstall();
        }
    }

    static final class Profile {
        long fork;
        long install;
        long apply;
        long readBack;
        long digest;
        long uninstall;
        int nodes;
        int nodeBudget;
        int transpositions;
        int skipped;

        Profile() {
        }

        String line() {
            long l = this.fork + this.install + this.apply + this.readBack + this.digest + this.uninstall;
            return "profile: nodes=" + this.nodes + "/" + this.nodeBudget + " transposed=" + this.transpositions + " skipped=" + this.skipped + " fork=" + Profile.ms(this.fork) + "(clone=" + Profile.ms(World.tClone) + ",container=" + Profile.ms(World.tContainer) + ",color=" + Profile.ms(World.tColor) + ") cloneTop=" + World.cloneCostReport() + " install=" + Profile.ms(this.install) + " apply=" + Profile.ms(this.apply) + " readBack=" + Profile.ms(this.readBack) + " digest=" + Profile.ms(this.digest) + " uninstall=" + Profile.ms(this.uninstall) + " total=" + Profile.ms(l);
        }

        private static String ms(long l) {
            return l / 1000000L + "ms";
        }
    }

    public static final class Result {
        public final List<Step> plan;
        public final boolean win;
        public final int hpLoss;
        public final int potionsUsed;
        /** HP value of the potions this line drinks (PotionBook tiers), the route-ordering cost. */
        public final int potionHpSpent;
        public final int predictedTurns;
        public final int nodes;
        public final int expansions;
        public final long millis;
        public final String reason;
        public final int layersRun;
        public final int maxBranch;
        public final List<String> promptCards;
        public final boolean escaped;
        public final int maxLayers;
        public final int choiceBranches;
        public final int splitStates;
        public final int deepestActions;
        public final boolean deepestRoute;
        /** G3 (JUDGMENT.md): Lizard Tail / Fairy in a Bottle uses this route needed. */
        public final int deathSaves;
        /**
         * The reference's `IsCompleteVictory` (CombatSolver-main `src\Search\SolverInterimResultOrdering.cs:5-13`):
         * a victory only counts once it has a real action behind it. There the solver can hold a
         * "won" node whose actions are still projected, so publishing needs `actionCount > 0`
         * (plus all enemies dead, player alive, projected HP > 0 -- all implied by our engine's
         * `Driver.combatOver()`). We keep the same predicate as a separate flag instead of folding
         * it into {@link #win}: win is the engine's verdict, this says whether it is replayable.
         */
        public final boolean completeVictory;

        /**
         * P2-8: the win provably cannot be improved -- zero HP lost and no potion spent on the
         * winning line. The reference calls this `IsProvenZeroDamageRoute`
         * (CombatSolver-main `src\Search\CombatSearchCoordinator.cs:730-740`) and stops refining
         * when it holds, because no route can beat "lost nothing".
         */
        public boolean provenOptimal;

        /**
         * HP the player had when the plan was made (before its first action), -1 when unknown.
         *
         * <p>The panel's first turn row prints "HP a -> b". {@link Step#hp} is read AFTER a step, so
         * using the first step's own value as the start of the row showed the HP the turn had
         * already spent (an Offering or a Hemokinesis made the row start at the wrong number, user
         * m00002 item 3: the panel must not print numbers the sandbox did not produce).
         */
        public int startHp = -1;
        /** Live nodes still unexplored when the search stopped (0 = the frontier ran dry). */
        public int frontierLeft;

        /**
         * True when the search stopped on a LIMIT (time / node cap / layer cap / stop request) and
         * may therefore have real work left; false means the beam frontier ran dry, i.e. it
         * converged. Derived as {@code !frontierExhausted}: every loop exit other than a dry
         * frontier is a limit. The panel must not call the parked beam overflow "candidates still
         * to expand" after a converged search (user m19708).
         */
        public boolean cutShort;

        /** The live frontier ran out at this beam width. NOT a proof of exhaustiveness: beam
         *  truncation may have dropped lines earlier, so the UI must not call this "all routes". */
        public boolean frontierExhausted;
        /** B44: true when the returned line was walked to the end of the battle (win or death). */
        public boolean lineFinished;

        /**
         * B78 (user m21889): the printed plan was REPLAYED to the end without losing a step.
         *
         * <p>{@link #win} is the exploration tree's verdict and stays that (JUDGMENT.md R1). This
         * flag answers a different question: can the steps being shown actually be played in this
         * order? A line that fails to replay is not offered as 可赢 and the autopilot refuses it,
         * because printing the names from one tree next to the state of another is exactly how the
         * panel asked for a card that had already been exhausted.
         */
        public boolean planVerified = true;
        /** Index of the step that could not be replayed (-1 when the whole line replayed). */
        public int planFailedAt = -1;
        /** Label of that step, or "". */
        public String planFailedOn = "";
        /** How many steps the search's own line had (the printed plan may be a shorter prefix). */
        public int planRequested;

        /**
         * m22600: HP this winning line heals after the battle (PostCombatHealing, which reads the
         * relics and powers of the line's final board). Route ordering subtracts it from the HP lost.
         */
        public int postHeal;

        /**
         * B173 (user m21300): the HP this line costs on the shared ruler (growth, post-combat heal
         * and loot credited; potions added back). This is the number the panel prints and the one
         * {@link Solver#better} orders routes by. -1 when the line came from a path that never set
         * it (gates, the deepen path): {@link Solver#netOf} falls back to the old formula then.
         */
        public int netHpLoss = -1;

        /**
         * L1 (user m17363): the post-combat reward this winning line earns (gold, cards, relic,
         * potions read from the engine's own settlement). The panel prints it as "战后 +X 金 +Y 卡"
         * so the reason the line killed the Looter is visible instead of being a hidden score term.
         */
        public PostCombatLoot.Loot loot;

        /** Layers explored after a winning line was already in hand (A0: >0 means it kept going). */
        public int layersAfterFirstWin;

        /**
         * A3 (docs/SEARCH-OPTIONS.md §7.2): how many candidates were parked because the beam could
         * not keep them, and how many of those the leftover budget managed to re-run. A non-zero
         * `deferred` with `promoted == 0` means "there was more to look at and no budget for it" --
         * the panel is allowed to say that, and only that.
         */
        public int deferred;
        public int promoted;
        /** B72: how many of those promotions came from an earlier layer than the one that dried up. */
        public int promotedEarlier;
        /**
         * B103: why none of {@link #deferred} was replayed, in words the panel can print.
         *
         * <p>Null when something was replayed (or nothing was parked). "deferred &gt; 0 and
         * promoted == 0" alone is not an explanation: the parked pass can be refused for the
         * budget, for its own shared ceiling, for the layer cap, or because the rebuild did not
         * reproduce the parked state -- and those call for different things from the player.
         */
        public String deferredWhy;

        /**
         * P2-11b: the same two numbers for the WHOLE job, not just the pass that produced this
         * line. The panel counts every pass (a portfolio runs four, the quick path up to two), so
         * the headline must not say "only 256 branches" while the panel says 20602 -- that reads
         * like two different searches. `nodes`/`expansions` stay per pass because they describe
         * how thoroughly THIS answer was explored; these totals describe the effort behind it.
         */
        public int totalNodes;
        public int totalExpansions;
        /**
         * B179 (user m23836): the work of the PASS that produced this answer, next to the job-wide
         * totals above. A widening ladder spends its rungs in one session; the panel counts every
         * rung, so the headline has to name both or it reads like two different searches.
         *
         * <p>{@code nodes}/{@code expansions} already mean "this pass"; these are the same numbers
         * kept under a name that cannot be confused with the totals.
         */
        public int passNodes;
        public int passExpansions;
        /** How many search passes (portfolio members / widening rungs) contributed; >= 1. */
        public int passes = 1;

        Result(List<Step> list, boolean bl, int n, int n3, int n4, int n5, long l, String string, int n6, int n7, List<String> list2, boolean bl2, int n8, int n9, int n10, int n11, int saves, boolean bl3) {
            this.plan = list;
            this.win = bl;
            this.hpLoss = n;
            // B53: both call sites used to pass a literal 0 here, so every result claimed "no potion
            // drunk" -- the panel never showed one, and compareRoutes ranked a potion line exactly
            // like a potion-free one. The plan is the truth: count its potion steps and price them.
            int used = 0;
            int potionHp = 0;
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    Step step = list.get(i);
                    if (step == null || step.action == null || !"potion".equals(step.action.kind)) continue;
                    ++used;
                    // The id travels in the action's id field since m16202-4 (the label is only
                    // the readable name). Old plans that still carry "name[ID]" fall back to the
                    // label parse.
                    int hp = PotionBook.valueForId(step.action.cardId);
                    if ((step.action.cardId == null || step.action.cardId.isEmpty())
                            && step.action.label != null && step.action.label.indexOf('[') >= 0) {
                        hp = PotionBook.valueForLabel(step.action.label);
                    }
                    potionHp += hp;
                }
            }
            this.potionsUsed = used;
            this.potionHpSpent = potionHp;
            this.predictedTurns = n3;
            this.nodes = n4;
            this.expansions = n5;
            this.millis = l;
            this.reason = string;
            this.layersRun = n6;
            this.maxBranch = n7;
            this.promptCards = list2;
            this.escaped = bl2;
            this.maxLayers = n8;
            this.choiceBranches = n9;
            this.splitStates = n10;
            this.deepestActions = n11;
            this.deathSaves = saves;
            this.deepestRoute = bl3;
            this.completeVictory = bl && list != null && !list.isEmpty();
            this.totalNodes = n4;
            this.totalExpansions = n5;
            this.passNodes = n4;
            this.passExpansions = n5;
        }

        public String summary() {
            return (this.win ? "win" : (this.escaped ? "escape" : "no-win")) + " splits=" + this.splitStates + " deepest=" + this.deepestActions + " hpLoss=" + this.hpLoss + " potions=" + this.potionsUsed + " potionHp=" + this.potionHpSpent + " saves=" + this.deathSaves + " complete=" + this.completeVictory + " turns=" + this.predictedTurns + " nodes=" + this.nodes + " totalNodes=" + this.totalNodes
                    + " expansions=" + this.expansions + " totalExpansions=" + this.totalExpansions
                    + " passes=" + this.passes
                    + " " + this.millis + "ms reason=" + this.reason + " layers=" + this.layersRun + " maxBranch=" + this.maxBranch + " deferred=" + this.deferred + " promoted=" + this.promoted;
        }

        public String planText() {
            StringBuilder stringBuilder = new StringBuilder();
            for (int i = 0; i < this.plan.size(); ++i) {
                if (i > 0) {
                    stringBuilder.append(" | ");
                }
                stringBuilder.append(this.plan.get((int)i).action.label);
            }
            return stringBuilder.toString();
        }

        /**
         * B125 (m03996 #7): the sandbox's own account of every plan step -- the turn the step ran in,
         * the HP/energy/block it left behind and the monsters' HP. The panel only prints the labels, so
         * when live play and the prediction disagree this line says WHICH beat drifted instead of
         * leaving the report to guess. `!stuck` marks an end turn that closed a turn no later step ever
         * left (the shape that put four end turns in one turn row).
         */
        public String planTrace() {
            // B126 (user m04653): t=<before>-><after>, so a step the fight ends by itself reads
            // "t=2->4 skip=1" instead of hiding the jump. B125 marked every end turn that followed
            // another end turn as !stuck because it compared the step's START turn with the previous
            // step's; only an end turn whose own after == before did not move the fight is stuck.
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < this.plan.size(); ++i) {
                Step st = this.plan.get(i);
                if (st == null || st.action == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(i + 1).append(":t=").append(st.turnBefore).append("->").append(st.turnAfter)
                        .append(" hp=").append(st.hp)
                        .append(" b=").append(st.block)
                        .append(" e=").append(st.energy)
                        .append(" mhp=").append(totalHp(st.monsterHp));
                int skipped = st.turnAfter - st.turnBefore - (st.action.isEndTurn() ? 1 : 0);
                if (skipped > 0) {
                    sb.append(" skip=").append(skipped);
                }
                if (st.action.isEndTurn() && st.turnAfter == st.turnBefore) {
                    sb.append("!stuck");
                }
                java.util.List<String> auto = st.action.autoPlays;
                if (auto != null && !auto.isEmpty()) {
                    sb.append(" auto=").append(auto);
                }
            }
            return sb.toString();
        }

        private static int totalHp(int[] hp) {
            if (hp == null) {
                return -1;
            }
            int sum = 0;
            for (int i = 0; i < hp.length; ++i) {
                sum += Math.max(0, hp[i]);
            }
            return sum;
        }
    }

    private static final class Choice {
        World child;
        String digest;
        int hp;
        int potions;
        /** Sum of PotionBook.valueHp over the bar: what the potions still held are worth. */
        int potionHp;
        int mhp;
        /** P2-35: the player's own max HP and gold inside this node (growth is measured on them). */
        int maxHealth;
        int gold;
        /** P2-36: master deck cards already upgraded (Lesson Learned) ... */
        int upgradedCards;
        /** P2-36: sum of the deck's permanent damage (Ritual Dagger's misc), in points. */
        int ritualDamage;
        /**
         * m22600: HP this board would heal at victory (PostCombatHealing.bound() read inside this
         * node's own world). It is per node on purpose: the live player's heal is a constant for
         * every winner, so reading it at scoring time gave the search no reason to play the card.
         */
        int postHeal;
        /**
         * L1 (user m17363): what this board's post-combat reward screen is worth, read in this
         * node's own world by {@link PostCombatLoot} (only on a win, and only while 局外 is counted).
         */
        PostCombatLoot.Loot loot;
        int block;
        int energy;
        int powers;
        int alive;
        boolean win;
        boolean dead;
        boolean escaped;
        int monsters;
        int fairies;
        boolean lizardTail;
        String choice = "";
        /** Same answer as {@link #choice}, written for the panel ("防御+"). */
        String choiceDisplay = "";
        /** B79: uuids of the cards in {@link #choice}, so a replay copies the pick, not re-derives it. */
        String choiceUuids = "";
        /**
         * B98 (user m02347): the state each named card carried inside this node. uuids are fork local
         * (the live game never saw them), so the print is the copy of the pick that survives a replay
         * into another engine.
         */
        String choicePrints = "";
        /** B140 (user m09491): the KIND of screen each answer was given on, '|' joined. */
        String screenKinds = "";
        /**
         * B203 (user m01393): what each of those screens OFFERED, the state of every card in the order
         * the screen listed them, '|' between screens -- the sandbox's side of the question "was the
         * card the plan wants even on this screen?".
         */
        String screenOffered = "";
        /**
         * B135 (user m07728): the cards the fight played by itself while THIS branch's answer
         * resolved. Per branch, because the answer decides which card gets auto-played.
         */
        java.util.List<String> autoPlays = new java.util.ArrayList<String>();
        /**
         * B176 (user m22904): true when {@link #digest} was sampled on a finished board, so its
         * action-manager fields (ph/q/use/end) may be dropped when it is used as a visited key.
         */
        boolean settled = true;
        /** B176: the canonical visited key for this board; see {@link Solver#positionKey}. */
        String positionKey = null;
        /**
         * B178 (user m23534): why the board was NOT settled at sampling time, captured here and not
         * later -- reading {@code Driver.settleBlock()} when the log line is written reports the state
         * after the drain, which is always "" and made the diagnostic useless.
         */
        String settleWhy = "";

        private Choice() {
        }
    }

    private static final class Node {
        final World world;
        final List<Action> path;
        final int score;
        final int rank;
        final int hpLoss;
        final int turn;
        final boolean win;
        final boolean dead;
        final int monsters;
        final int deathSaves;
        final String digest;
        /** B176: the canonical visited key (see {@link Solver#positionKey}); null means raw digest. */
        String positionKey;
        /** m22600: post-combat heal this node's board would get; see {@link Choice#postHeal}. */
        int postHeal;
        /** L1: the reward screen this node's win would earn; see {@link Choice#loot}. */
        PostCombatLoot.Loot loot;
        /**
         * B173 (user m21300): what this line costs on the ONE ruler the panel, the in-rung best and
         * the cross-rung merge all use -- growth credit, the post-combat heal and the win's loot are
         * subtracted, the potions it drinks are added back. -1 until {@code expandAndRecord} fills it.
         */
        int netHpLoss = -1;

        Node(World world, List<Action> list, int n, int n2, int n3, int n4, boolean bl, boolean bl2, int n5, int n6, String string) {
            this.world = world;
            this.path = list;
            this.score = n;
            this.rank = n2;
            this.hpLoss = n3;
            this.turn = n4;
            this.win = bl;
            this.dead = bl2;
            this.monsters = n5;
            this.deathSaves = n6;
            this.digest = string;
        }
    }

    /**
     * A3: a candidate the beam could not keep. Only the replayable path and its ranking are stored --
     * keeping the World would cost as much as the node itself and defeat the point of the beam.
     */
    private static final class Deferred {
        final List<Action> path;
        final int score;
        final int rank;
        final int hpLoss;
        final int layer;
        final int deathSaves;
        final String digest;
        /** B176: the canonical visited key recorded with the parked node; null means raw digest. */
        final String positionKey;

        Deferred(List<Action> path, int score, int rank, int hpLoss, int layer, int deathSaves, String digest, String positionKey) {
            this.path = path;
            this.score = score;
            this.rank = rank;
            this.hpLoss = hpLoss;
            this.layer = layer;
            this.deathSaves = deathSaves;
            this.digest = digest;
            this.positionKey = positionKey;
        }
    }

    /** Park a beam overflow candidate, keeping the list bounded without dropping the best ones. */
    private static void defer(List<Deferred> deferred, Node node, int layer) {
        Solver.defer(deferred, node, layer, 0);
    }

    /** P7: same, with a per-tier ceiling so a wider beam can park more than the global 2000. */
    private static void defer(List<Deferred> deferred, Node node, int layer, int cap) {
        if (node == null || node.path == null || node.digest == null) {
            return;
        }
        int limit = cap > 0 ? Math.max(Limits.deferred(), cap) : Limits.deferred();
        if (deferred.size() < limit) {
            deferred.add(new Deferred(node.path, node.score, node.rank, node.hpLoss, layer, node.deathSaves, node.digest, node.positionKey));
            return;
        }
        int worst = 0;
        for (int i = 1; i < deferred.size(); i++) {
            Deferred d = deferred.get(i);
            Deferred w = deferred.get(worst);
            if (d.rank < w.rank || (d.rank == w.rank && d.score < w.score)) {
                worst = i;
            }
        }
        Deferred w = deferred.get(worst);
        if (node.rank < w.rank || (node.rank == w.rank && node.score <= w.score)) {
            return;
        }
        deferred.set(worst, new Deferred(node.path, node.score, node.rank, node.hpLoss, layer, node.deathSaves, node.digest, node.positionKey));
    }

    private static void sortDeferred(List<Deferred> deferred) {
        Collections.sort(deferred, new Comparator<Deferred>() {
            @Override
            public int compare(Deferred a, Deferred b) {
                if (a.rank != b.rank) {
                    return b.rank - a.rank;
                }
                if (a.score != b.score) {
                    return b.score - a.score;
                }
                return a.digest.compareTo(b.digest);
            }
        });
    }

    private static int promotionDepth() {
        Integer d = PROMOTION_DEPTH.get();
        return d == null ? 0 : d;
    }

    private static int extendDepth() {
        Integer d = EXTEND_DEPTH.get();
        return d == null ? 0 : d;
    }

    /**
     * Rebuilds the world at the end of a deferred path by replaying it from the root. This is the
     * cost A3 pays for not having kept the World: depth x (fork + apply).
     */
    /**
     * B107: the steps whose rebuild is impossible for a reason that belongs to the PLAN, not to the
     * parked line -- the recorded action carries no answer for a screen the engine always opens
     * there ({@code not-offered} with an empty {@code choice}).
     *
     * <p>Replaying is deterministic, so such a step fails identically for every line that reaches it.
     * Until B107 each of the ~1300 parked lines rediscovered that at ~3 ms apiece, inside a single
     * promote pass: 4 seconds of one frame, 6660 log lines, and nothing learnt. The fix is not to give
     * the pass a time budget (that hides it); it is to record the impossibility once and skip the
     * lines that would hit it.
     *
     * <p>Deliberately NOT poisoned: a state-level failure (the screen offers different cards, a
     * fingerprint mismatch). Those can succeed for a sibling line whose state differs, so treating
     * them as plan defects would silently throw away good lines.
     */
    private static final java.util.Set<String> rebuildPoison = new java.util.HashSet<String>();
    /** Same keys in insertion order, so {@link #poisonedStepOf} never allocates while scanning. */
    private static final java.util.List<String> rebuildPoisonList = new java.util.ArrayList<String>();
    private static volatile int promotedSkippedPoisoned;

    /** Records that {@code step} (that action) cannot be replayed in this search. Idempotent. */
    private static void poisonStep(int step, Action action) {
        String key = step + "|" + (action == null ? "?" : action.label);
        if (rebuildPoison.add(key)) {
            rebuildPoisonList.add(key);
            Log.warn("A3 promote: step " + step + " '" + (action == null ? "?" : action.label)
                    + "' cannot be replayed in this search (the plan carries no answer for it);"
                    + " every parked line that reaches it is skipped from now on, no retry");
        }
    }

    /** The poisoned step this path would reach (its key), or null. */
    private static String poisonedStepOf(java.util.List<Action> path) {
        if (rebuildPoisonList.isEmpty() || path == null) {
            return null;
        }
        for (int i = 0; i < rebuildPoisonList.size(); i++) {
            String key = rebuildPoisonList.get(i);
            int bar = key.indexOf('|');
            int step;
            try {
                step = Integer.parseInt(key.substring(0, bar));
            } catch (Throwable t) {
                continue;
            }
            if (step < path.size() && key.substring(bar + 1).equals(path.get(step).label)) {
                return key;
            }
        }
        return null;
    }

    /** B107 gate hook: which poisoned step (if any) this line would hit. */
    public static String poisonedStepForTest(java.util.List<Action> path) {
        return poisonedStepOf(path);
    }

    /** B107 gate hook: poison one step, as a failed rebuild would. */
    public static String poisonStepForTest(int step, Action action) {
        poisonStep(step, action);
        return step + "|" + action.label;
    }

    /** B107 gate hook: how many distinct steps this search knows it cannot replay. */
    public static int poisonedCountForTest() {
        return rebuildPoisonList.size();
    }

    /** How many parked lines were skipped because of a poisoned step (report/gate). */
    public static int poisonedSkippedForTest() {
        return promotedSkippedPoisoned;
    }

    private static World materialize(World root, Deferred entry) {
        World cur = root;
        int step = 0;
        String rebuilt = null;
        try {
            for (int i = 0; i < entry.path.size(); i++) {
                step = i;
                Action action = entry.path.get(i);
                World next = cur.fork();
                boolean installed = false;
                try {
                    next.install();
                    installed = true;
                    Legal.applyOrRefuse(action);
                    // B78 (user m21889): a planned answer the screen does not offer means this
                    // line cannot be reproduced. The old fallback guessed with
                    // resolveSelectionHeuristically(), which answered with a different card and
                    // shifted every later hand position -- the printed plan and the verified plan
                    // were then two different games.
                    Solver.answerForReplay(action);
                    next.trimPresentation();
                    next.readBack();
                    // B101 (user m2901): a walk that only throws no exception proves nothing. Read
                    // the state here, while this fork is still installed, and compare it with what
                    // the search parked. Only a silent mismatch explains "the line replays, but the
                    // hand at the marked step is a different hand": every step accepted, and the
                    // world still ended up somewhere else.
                    rebuilt = Digest.stable(Digest.ofCurrent());
                } finally {
                    if (installed) {
                        next.uninstall();
                    }
                }
                cur = next;
            }
            if (rebuilt != null) {
                String parked = Digest.stable(entry.digest);
                if (!rebuilt.equals(parked)) {
                    ++promotionMismatch;
                    Log.warn("A3 promote: the rebuilt state is not the parked one (" + entry.path.size()
                            + " actions, " + firstDifference(parked, rebuilt) + ")");
                    return null;
                }
            }
            cur.setDigest(entry.digest);
            return cur;
        } catch (Throwable t) {
            World.forceRestore();
            String reason = String.valueOf(t);
            Action failing = step < entry.path.size() ? entry.path.get(step) : null;
            // B107: only a plan-level defect poisons the step. "not-offered" with an empty recorded
            // answer means the action itself has no answer to give, so the same step fails for every
            // line. A state-level refusal (different cards on the screen, a fingerprint mismatch) can
            // succeed for a sibling line and must not be written off.
            if (failing != null && reason.indexOf("not-offered") >= 0
                    && (failing.choice == null || failing.choice.isEmpty())) {
                poisonStep(step, failing);
            }
            Log.warn("A3 promote: cannot rebuild a deferred path (" + entry.path.size() + " actions) at step "
                    + step + (failing != null ? " on " + failing : "") + ": " + t);
            return null;
        }
    }

    /** B101: how many promoted paths rebuilt into a state that was not the parked one. */
    private static volatile int promotionMismatch;

    /** B101: the number of A3 paths that rebuilt into a different state than the search parked. */
    public static int promotionMismatchForTest() {
        return promotionMismatch;
    }

    /** B101: where two digests stop agreeing, with a little context on both sides. */
    private static String firstDifference(String parked, String rebuilt) {
        int n = Math.min(parked.length(), rebuilt.length());
        int i = 0;
        while (i < n && parked.charAt(i) == rebuilt.charAt(i)) {
            i++;
        }
        int from = Math.max(0, i - 24);
        String a = i < parked.length() ? parked.substring(from, Math.min(parked.length(), i + 40)) : "<end>";
        String b = i < rebuilt.length() ? rebuilt.substring(from, Math.min(rebuilt.length(), i + 40)) : "<end>";
        return "first difference at char " + i + ": parked=<" + a + "> rebuilt=<" + b + ">";
    }

    /**
     * B44: rebuilds the world after a concrete action path (the same walk A3 uses) so the search can
     * continue from the end of the deepest line. The deepest node's own {@code child} world is not
     * usable for this: it is the state right after one action, before the screen that action opened
     * has been answered, so its candidate list is empty.
     */
    /**
     * B44: a battle line must reach the end of the battle. When the search has no winning line, the
     * deepest line is handed to the player; if it stops while the fight is still going, keep ending
     * turns (a legal, minimal continuation) until the fight is over or the cap is reached. This is
     * what makes the panel able to say "this is the whole fight, and you lose here" instead of
     * showing a line that stops with the boss alive (user m13104).
     */
    private static java.util.List<Action> finishLine(World root, java.util.List<Action> path, int maxExtra) {
        java.util.ArrayList<Action> out = new java.util.ArrayList<Action>(path);
        World w = Solver.stateAfter(root, path);
        if (w == null) {
            return out;
        }
        Driver.begin();
        boolean installed = false;
        try {
            w.install();
            installed = true;
            for (int i = 0; i < maxExtra; i++) {
                if (Driver.combatOver()) {
                    break;
                }
                Action action = Action.endTurn();
                Legal.applyOrRefuse(action);
                if (Driver.selectionKind() != Driver.SelectionKind.NONE) {
                    Driver.resolveSelectionHeuristically();
                }
                out.add(action);
            }
            LINE_FINISHED.set(Driver.combatOver());
        } catch (Throwable t) {
            Log.warn("B44: cannot finish the line after " + out.size() + " actions: " + t);
        } finally {
            if (installed) {
                w.uninstall();
            } else {
                World.forceRestore();
            }
            Driver.end();
        }
        return out;
    }

    private static World stateAfter(World root, java.util.List<Action> path) {
        World w = root.fork();
        Driver.begin();
        try {
            // B59: every begin() needs an end(), or the whole live game keeps the borrowed frame
            // delta (animations at SIM_DELTA per frame). The frame-boundary check used to hide this.
            return Solver.walkTo(root, w, path);
        } finally {
            Driver.end();
        }
    }

    private static World walkTo(World root, World w, java.util.List<Action> path) {
        try {
            w.install();
            try {
                for (int i = 0; i < path.size(); i++) {
                    Action action = path.get(i);
                    Legal.applyOrRefuse(action);
                    // B78 (user m21889): a planned answer the screen does not offer means this
                    // line cannot be reproduced. The old fallback guessed with
                    // resolveSelectionHeuristically(), which answered with a different card and
                    // shifted every later hand position -- the printed plan and the verified plan
                    // were then two different games.
                    Solver.answerForReplay(action);
                    // B64: once the player is dead the rest of the line is fiction -- replaying it used
                    // to walk a corpse through turns that healed it back up.
                    if (Driver.playerDead()) {
                        break;
                    }
                }
                w.trimPresentation();
                w.readBack();
            } finally {
                w.uninstall();
            }
            return w;
        } catch (Throwable t) {
            World.forceRestore();
            Log.warn("B44: cannot rebuild the deepest line (" + path.size() + " actions): " + t);
            return null;
        }
    }

    public static final class Step {
        public final Action action;
        public final String digest;
        /**
         * B147 (user m12327): where the game's random streams stood when this step finished, as the
         * sandbox measured them ({@link sts1solver.mirror.RngStamp#of()}). "" for a step built by
         * hand (a test result). The digest cannot see this: two fights with the same board but a
         * different card random position play different cards the moment anything rolls, which is
         * how 唐 (TangPower) turned a matching plan into a wrong one.
         */
        public final String rng;
        /**
         * B147: the cards the FIGHT played while this step ran, in order, with the random position
         * each was played at ({@link sts1solver.mirror.PlayTrace#describe(long)}). This is the
         * sandbox's side of the evidence; the live fight's side is recorded by the same patch, so a
         * step that came out differently can say WHICH play was the first one that differed.
         */
        public final String plays;
        /**
         * B85: the turn this step was TAKEN in (read before the action ran), not the turn it left the
         * fight on. An end-turn step closes this turn; a card that presses End Turn (Vault) belongs to
         * this turn too, and the steps after it belong to the next one. Grouping, staleness and cache
         * reuse all read this field, so there is exactly one meaning for it.
         */
        public final int turn;
        public final int hp;
        public final int block;
        public final int energy;
        public final int[] monsterHp;
        public final int[] monsterMaxHp;
        public final String[] monsterNames;
        /**
         * B126 (user m04653): the turn this step STARTED in and the turn it LEFT the fight in, as the
         * sandbox measured them around the action. They differ by more than one when the fight skips
         * turns by itself (a power that presses End Turn at the start of a turn, such as TrancePower;
         * one stack per skipped turn). {@code turn} alone could not say that, so the panel had no way
         * to expect a 2 -> 4 jump and read a correct skip as "the plan is stale".
         *
         * <p>-1 means "not measured" (a hand-built Result, or an action applied outside the sandbox).
         */
        public final int turnBefore;
        public final int turnAfter;

        Step(Action action, String string, int n, int n2, int n3, int n4, int[] nArray, int[] nArray2, String[] stringArray) {
            this(action, string, "", "", n, n2, n3, n4, nArray, nArray2, stringArray);
        }

        Step(Action action, String string, String rng, String plays, int n, int n2, int n3, int n4,
             int[] nArray, int[] nArray2, String[] stringArray) {
            this.action = action;
            this.digest = string;
            this.rng = rng == null ? "" : rng;
            this.plays = plays == null ? "" : plays;
            this.turn = n;
            this.turnBefore = action != null && action.turnBefore >= 0 ? action.turnBefore : n;
            this.turnAfter = action != null && action.turnAfter >= 0 ? action.turnAfter : n;
            this.hp = n2;
            this.block = n3;
            this.energy = n4;
            this.monsterHp = nArray;
            this.monsterMaxHp = nArray2;
            this.monsterNames = stringArray;
        }

        public String targetDelta() {
            int n = this.action.targetIndex;
            if (n < 0 || this.monsterHp == null || n >= this.monsterHp.length) {
                return "";
            }
            return "\u654c " + this.monsterHp[n] + "/" + this.monsterMaxHp[n];
        }

        public String toString() {
            return this.action.label;
        }
    }

    /**
     * Test hook (S177, B122): a hand-built Result so the panel's row builder can be driven without a
     * search. {@code turns[i]} is the turn step i was taken in, {@code hp[i]} the HP it left the
     * fight on.
     */
    /**
     * B124 (user m03444): run one action in the sandbox and return it with its automatic plays.
     *
     * <p>Test hook: a gate has to ask "what did the fight play by itself while this action ran"
     * without driving a whole search. The action is applied exactly the way {@code expand} applies
     * it, so the answer is the same one a plan would carry.
     */
    /** B124: test hook -- the cards played so far this turn, as {@link #cardsPlayedNames} sees them. */
    public static java.util.List<String> cardsPlayedNamesForTest() {
        return Solver.cardsPlayedNames();
    }

    public static Action simulateForTest(World root, Action action) {
        World probe = root.fork();
        boolean installed = false;
        try {
            probe.install();
            installed = true;
            java.util.List<String> before = Solver.cardsPlayedNames();
            Driver.Drain d = Legal.apply(action);
            try {
                Driver.drain(4000, 1500L);
            } catch (Throwable ignored) {
                // a sandbox that cannot settle simply has no delta to record
            }
            java.util.List<String> after = Solver.cardsPlayedNames();
            Solver.recordAutoPlays(action, before, after);
        } finally {
            if (installed) {
                try {
                    probe.uninstall();
                } catch (Throwable ignored) {
                    // the caller's world is still the live one; nothing to restore
                }
            }
        }
        return action;
    }

    public static Result resultForTest(List<Action> actions, int[] turns, int[] hp) {
        ArrayList<Step> steps = new ArrayList<Step>();
        for (int i = 0; i < actions.size(); ++i) {
            steps.add(new Step(actions.get(i), "test", turns[i], hp[i], 0, 3,
                    new int[0], new int[0], new String[0]));
        }
        return new Result(steps, false, 0, 0, 0, 0, 0L, "test", 0, 0,
                new ArrayList<String>(), false, 0, 0, 0, 0, 0, false);
    }

    public static final class Session {
        private final World root;
        /** B177 (user m23168): not final -- a widening rung raises the beam inside this session. */
        private int beamWidth;
        private final int maxLayer;
        private final long budgetMs;
        /** B177: not final -- every rung gets its own node cap on top of the nodes already run. */
        private int maxNodes;
        private final int bandOffset;
        /** B119: not final -- a paused span is handed back by shifting it (compensatePause). */
        private long startedAt = System.currentTimeMillis();
        private int startHp;
        /** HP value of the potions in the bar when this session started (PotionBook tiers). */
        private int startPotionHp;
        /** P2-35: max HP and gold when this session started; growth credit is measured on them. */
        private int startMaxHp;
        private int startGold;
        /** P2-36: the fight's opening deck growth, so a node only measures deltas. */
        private GrowthBook.Facts startGrowth;
        private int startMonsterHp;
        private int startBlock;
        private int startAlive;
        private int startFairies;
        private String startDigest;
        private Node best;
        private List<Node> frontier = new ArrayList<Node>();
        private Map<String, Node> next;
        private int nodeIndex;
        private int actionIndex;
        private List<Action> currentActs;
        private int layer;
        private int nodes;
        private int expansions;
        private int maxBranch;
        private int choiceBranches;
        private int splitStates;
        private int deepestActions;
        private int layersRun;
        private Choice deepest;
        private List<Action> deepestPath;
        private String reason = "layers";
        private boolean bestEscaped;
        // P2-8: same refinement rule as solveInternal -- keep expanding after the first win for
        // up to REFINE_LAYERS more layers, stop immediately on a proven zero-damage win.
        private int bestWinLayer = -1;
        private int layersAfterFirstWin;
        private boolean provenOptimal;
        private boolean frontierDead;
        private boolean done;
        private boolean finished;
        private Result result;
        private final Set<String> seen = new HashSet<String>();
        /** B177: not final -- reset per rung (see {@link #widenToNextRung}). */
        private int nodeBudget;
        private int expansionsThisLayer;
        private int layerAllowance = Integer.MAX_VALUE;
        private int transpositions;
        private int skipped;
        /**
         * B176 (user m22904): nodes whose digest was taken on a board that had NOT finished, so its
         * raw string had to be kept as the visited key. A number above zero is the honest signal that
         * the fingerprint could not be canonicalised there (mid-flight sample).
         */
        private int unsettledNodes;
        /** B176: how many times two samples of one board were merged thanks to the canonical key. */
        private int mergedByPhase;
        // A3: same rule as the blocking search -- beam overflow is parked, not dropped.
        private final List<Deferred> deferred = new ArrayList<Deferred>();
        private int promoted;
        /** B72: promotions of a candidate that was parked on an EARLIER layer than the dry one. */
        private int promotedEarlier;
        /** B72: identity of the parked candidates already replayed, so none is re-run twice. */
        private final Set<String> promotedKeys = new HashSet<String>();
        /** P2-41: how many parked candidates THIS search may re-run (0 = not computed yet). */
        private int promotionLimit;
        private volatile boolean stopRequested;
        /** P2-11b: log the narrow-the-beam fallback once per pass, not once per layer. */
        private boolean guardLogged;

        /*
         * B177 (user m23168, option C): the widening ladder runs INSIDE this session.
         *
         * <p>The quick job used to finish the narrow pass and then build a brand-new Session from
         * the same root for every wider rung, so the first layers were expanded again at every
         * rung -- the same boards, the same children, the same order. Here the rung is a state
         * change: the beam grows, the node cap is topped up, and the search continues from the
         * layer it had already reached. The lines the narrow beam squeezed out are not lost: they
         * sit in {@link #deferred} and are replayed into the frontier under the wider beam.
         */
        private int[] rungBeams;
        /** Widening rungs already started (0 while the narrow rung runs); not the ladder index. */
        private int rung;
        /** Node cap for one widening rung, fixed from the narrow rung's own work (B171 rule). */
        private int rungCapNodes;
        /** Nodes run at the moment the current rung started (its own work is nodes - this). */
        private int nodesAtRungStart;
        /** Parked candidates replayed in the current rung; the quota is per rung, not per session. */
        private int promotedThisRung;
        /** Layer the last widening happened on; -1 while the narrow rung is still running. */
        private int widenedAtLayer = -1;
        /** Expansions run when the FIRST widening happened (what the narrow rung had done). */
        private int expansionsAtWiden;
        /**
         * B179 (user m23836): per-rung wall-clock deadline, cumulative milliseconds measured from
         * {@link #startedAt}, one entry per rung (index 0 = the narrow rung). Null keeps the old
         * rule: one deadline for the whole session. The portfolio runs its four beams this way --
         * 35/25/25/15 of the job's budget -- but inside ONE session, so a wider rung continues
         * from the layer the narrow one reached instead of re-expanding it from the root.
         */
        private long[] rungDeadlines;
        /**
         * B179: per-rung node cap, one entry per widening rung (index 0 is unused: the narrow rung
         * gets its cap through the constructor). Null = the B177 rule (twice the previous rung).
         */
        private int[] rungNodeCaps;
        /** B179: expansions at the moment the current rung started (its own work is the delta). */
        private int expansionsAtRungStart;

        /**
         * Why the parked candidates were not replayed, when they were not (B103).
         *
         * <p>The panel used to print "前沿跑完，但没回放被挤掉的线" without saying which of the refusals
         * in {@link #promote()} fired, so telling "the parked pass ran out of time" apart from "the
         * beam overflow was all above the layer cap" needed the source. Worse, the ceiling is a
         * static clock that starts at the FIRST portfolio member's promotion attempt, so members
         * 2..4 inherit an expired phase and can never promote at all -- 2000 parked lines, zero
         * replays, and not one warning.
         */
        private String promoteWhy;
        /** B103: true once the refusal has been explained, so a dry frontier logs it once. */
        private boolean promoteExplained;
        /*
         * B103: this pass' own parked budget. -1 means "not measured yet"; the ceiling is pinned on the
         * first promotion attempt ({@link Solver#parkedCapFor(long)}) and is never shared with the
         * other profiles running under the same resetGuards window. Only the time actually spent
         * rebuilding parked lines is charged: the panel's sliced search expands the promoted node
         * between two promotions, and billing that to the parked pass made profile 1 stop after four
         * replays while 4.5 of its 7 seconds were still unspent.
         */
        private long parkedCapMs = -1L;
        private long parkedSpentMs;
        /*
         * B103: refusal counters live on the session, not in the method, so the one "nothing replayed"
         * line can report them even when the refusal happened before a single entry was looked at.
         */
        private int skipDepth;
        private int skipEarlier;
        private int skipDup;
        private int skipRebuild;
        /** B107: lines skipped because they would reach a step this search cannot replay. */
        private int skipPoisoned;
        /*
         * P7 (user m00001): potion-tier controls. The tiered driver narrows a rung to "at most N
         * potions drunk", keeps the potion price out of the ranking INSIDE that tier (the price is
         * charged once, at the cross-tier merge, through netHpLoss) and gives wider tiers a wider
         * beam plus a bigger parked list. A plain Session leaves every field at its default, so the
         * blocking solve() and the untiered portfolio behave exactly as before.
         */
        private int maxPotionUses = Integer.MAX_VALUE;
        private boolean deferPotionCost;
        private int deferredCap;
        private int promoteFloor;
        private boolean tiered;

        public void requestStop() {
            this.stopRequested = true;
        }

        /** P7: do not offer any more potion actions once a line has drunk this many. */
        public void limitPotionUses(int n) {
            this.maxPotionUses = Math.max(0, n);
        }

        /** P7: keep the potion price out of the in-tier ranking (the merge charges it instead). */
        public void deferPotionCost(boolean b) {
            this.deferPotionCost = b;
        }

        /** P7: parked-line ceiling / promotion quota that grow with the tier beam. */
        public void setCaps(int deferredCap, int promoteFloor) {
            this.deferredCap = deferredCap;
            this.promoteFloor = promoteFloor;
        }

        /** P7: this session is one potion tier; skip the B174 counterfactual (tier 0 is the baseline). */
        public void markTiered() {
            this.tiered = true;
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        public Session(World world, int n, int n2, long l, int n3, int n4) {
            Node node;
            this.root = world;
            this.beamWidth = n;
            this.maxLayer = n2;
            this.budgetMs = l;
            this.maxNodes = n3;
            this.nodeBudget = n3;
            this.bandOffset = n4;
            Legal.clearPromptCards();
            world.install();
            try {
                this.startHp = Solver.hp();
                this.startPotionHp = Solver.potionValueLeft();
                this.startMaxHp = Solver.playerMaxHealth();
                this.startGold = Solver.playerGold();
                this.startGrowth = new GrowthBook.Facts(this.startMaxHp, this.startGold,
                        Solver.upgradedCards(), Solver.ritualDamage(), this.startPotionHp);
                this.startDigest = Digest.ofCurrent();
                this.startMonsterHp = Solver.monsterHp();
                this.startBlock = Solver.block();
                this.startAlive = Solver.aliveCount();
                this.startFairies = DeathSaves.fairiesInBar();
            }
            finally {
                world.uninstall();
            }
            world.setDigest(this.startDigest);
            int n5 = Solver.score(0, 0, false, false, 0, this.startMonsterHp, this.startBlock, this.startAlive, 0);
            this.best = node = new Node(world, new ArrayList<Action>(), n5, n5, 0, GameActionManager.turn, false, false, Solver.monsterCount(), 0, this.startDigest);
            // B173: the root costs nothing by definition; every later node computes its own net.
            node.netHpLoss = 0;
            // B176: the root is captured from a finished board by definition.
            node.positionKey = Solver.positionKey(this.startDigest, true);
            this.frontier.add(node);
        }

        public boolean finished() {
            return this.finished;
        }

        public Result result() {
            return this.result;
        }

        public int nodes() {
            return this.nodes;
        }

        /** P2-11b: expansions this pass, so a portfolio can add them up for the headline. */
        public int expansions() {
            return this.expansions;
        }

        public int layersRun() {
            return this.layersRun;
        }

        public long elapsedMs() {
            return System.currentTimeMillis() - this.startedAt;
        }

        public String reason() {
            return this.reason;
        }

        public int bestActions() {
            return this.best.path.size();
        }

        /**
         * B115 (m00748): the best line the search holds right now, as a copy of its actions.
         *
         * <p>The panel used to show a route only after {@code finish()} had replayed it, so a
         * ten-second budget showed nothing route-shaped for ten seconds. This lets the panel preview
         * the line the search is holding WITHOUT replaying it: the copy is cheap (a few references)
         * and the caller cannot mutate the tree while the search keeps expanding.
         */
        public List<Action> bestPath() {
            if (this.best == null || this.best.path == null || this.best.path.isEmpty()) {
                return null;
            }
            return new ArrayList<Action>(this.best.path);
        }

        /**
         * B118 (m01978): HP the held line is expected to cost, from the node the search is holding.
         *
         * <p>{@code hpLoss} is cumulative from the search root, which is the board as it stood when
         * the search started, so the panel can turn it into "预计 -N HP" without replaying. -1 when
         * no line is held yet.
         */
        public int bestHpLoss() {
            return this.best == null ? -1 : this.best.hpLoss;
        }

        /**
         * B173 (user m21300): the same held line's cost on the shared ruler (growth/loot/heal
         * credited, potions added). This is the number the panel prints as "预计 -N HP".
         */
        public int bestNetHpLoss() {
            return this.best == null ? -1 : this.best.netHpLoss;
        }

        /** Harness hook (B176): nodes whose fingerprint came from a board that had not finished. */
        /** Harness hook (B176): how many boards this run expanded (same number as nodes()). */
        public int nodesForTest() {
            return this.nodes;
        }

        public int unsettledNodesForTest() {
            return this.unsettledNodes;
        }

        /** Harness hook (B176): how often the canonical key merged two phase-different samples. */
        public int mergedByPhaseForTest() {
            return this.mergedByPhase;
        }

        /**
         * B120 (m02336): whether the line {@link #bestPath()} holds already reaches victory.
         *
         * <p>The preview needs it: "no HP lost so far" means optimal for a won line and means
         * nothing at all for a line still mid-fight, so the panel used to read a partial route as
         * if it were the final answer.
         */
        public boolean bestIsWin() {
            return this.best != null && this.best.win;
        }

        /**
         * B119 (m02060): give the session back a span it did not actually run in.
         *
         * <p>The budget is measured as {@code System.currentTimeMillis() - startedAt}, which keeps
         * growing while the pause menu is up. Shifting the start forward means exactly "that span
         * did not count": every deadline derived from it (layer budget, this profile's node share,
         * the parked-candidate pass) moves with it and no derived number is touched.
         */
        public void compensatePause(long ms) {
            if (ms > 0L) {
                this.startedAt += ms;
                if (deferredPhaseStarted != 0L) {
                    deferredPhaseStarted += ms;
                }
            }
        }

        /**
         * B177 (user m23168): give this session the widening ladder to run after the narrow rung.
         *
         * <p>Beams are tried in the given order and a rung whose beam is not strictly wider than
         * the one before it is skipped, so the caller can hand in a sorted list without filtering.
         * Without a ladder the session behaves exactly as before (one rung, one finish).
         */
        public void planWidening(int[] beams) {
            this.planWidening(beams, null, null);
        }

        /**
         * B179 (user m23836): the same ladder with a wall-clock and a node budget per rung.
         *
         * <p>{@code deadlinesMs} holds one cumulative deadline per rung (index 0 = the narrow one),
         * measured from the session start; {@code nodeCaps} holds one cap per WIDENING rung. Both
         * may be null to keep the old behaviour. The portfolio uses them to give its four beams the
         * historical 35/25/25/15 share of the job while sharing one frontier.
         */
        public void planWidening(int[] beams, long[] deadlinesMs, int[] nodeCaps) {
            this.rungBeams = beams == null ? null : beams.clone();
            this.rungDeadlines = deadlinesMs == null ? null : deadlinesMs.clone();
            this.rungNodeCaps = nodeCaps == null ? null : nodeCaps.clone();
            this.rung = 0;
            this.nodesAtRungStart = 0;
            this.promotedThisRung = 0;
            this.rungCapNodes = 0;
        }

        /** B177: which rung is running (0 = the narrow one). */
        public int rung() {
            return this.rung;
        }

        /**
         * B179: the wall-clock budget of the rung that is running. Without per-rung deadlines this
         * is the session's single budget, exactly as before.
         */
        private long budgetNow() {
            if (this.rungDeadlines == null) {
                return this.budgetMs;
            }
            int at = Math.min(this.rung, this.rungDeadlines.length - 1);
            return this.rungDeadlines[at] > 0L ? this.rungDeadlines[at] : this.budgetMs;
        }

        /** B177: how many rungs this session plans to run (1 when no ladder was given). */
        public int rungs() {
            return this.rungBeams == null ? 1 : this.rungBeams.length + 1;
        }

        /** B177: the beam the current rung runs with (the memory guard may still narrow it). */
        public int beamWidth() {
            return this.beamWidth;
        }

        /** B177: nodes expanded since the current rung started -- the panel's "本档" counter. */
        public int passNodes() {
            return this.nodes - this.nodesAtRungStart;
        }

        /** Harness hook (B180): the node cap the running rung obeys (0 = no cap). */
        public int maxNodesForTest() {
            return this.maxNodes;
        }

        /** Harness hook (B177): the layer the last widening happened on (-1 = never widened). */
        public int widenedAtLayerForTest() {
            return this.widenedAtLayer;
        }

        /** Harness hook (B177): parked candidates replayed in total (quota is per rung). */
        public int promotedForTest() {
            return this.promoted;
        }

        /** Harness hook (B177): expansions the narrow rung had run when the first widening fired. */
        public int expansionsAtWidenForTest() {
            return this.expansionsAtWiden;
        }

        /** Harness hook (B177): how many lines are parked for a wider rung to pick up. */
        public int deferredCountForTest() {
            return this.deferred.size();
        }

        public int deepestActions() {
            return this.deepestActions;
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        public boolean advance(long l) {
            if (this.finished) {
                return true;
            }
            long sliceStart = System.nanoTime();
            long l2 = System.currentTimeMillis() + Math.max(4L, l);
            Driver.begin();
            try {
                while (!this.done && System.currentTimeMillis() < l2) {
                    this.microStep();
                }
            }
            finally {
                Driver.end();
            }
            long sliceUs = (System.nanoTime() - sliceStart) / 1000L;
            if (sliceUs >= Solver.SLOW_SLICE_US) {
                // B170: the budget is checked between steps, so one expensive step IS the frame.
                // This line names the frame the player felt even when that step was neither an
                // expand (slow step) nor a candidate scan (slow candidates).
                Log.warn("slow slice: " + (sliceUs / 1000L) + "ms layer=" + this.layer
                        + " node=" + this.nodeIndex + "/" + this.frontier.size()
                        + " budget=" + Math.max(4L, l) + "ms (B170)");
            }
            if (this.done) {
                // B177 (user m23168): a rung ending is not a search ending. Widen in place and keep
                // the frontier/layer this session already reached; only the last rung finishes.
                if (!this.widenToNextRung()) {
                    this.finish();
                }
            }
            return this.finished;
        }

        private void microStep() {
            if (this.stopRequested) {
                this.reason = "stopped";
                this.done = true;
                return;
            }
            // B179: a rung gets its own deadline; the session's total budget is still the outer wall.
            if (System.currentTimeMillis() - this.startedAt > this.budgetNow()) {
                this.reason = "time";
                this.done = true;
                return;
            }
            if (this.maxNodes > 0 && this.nodes >= this.maxNodes) {
                this.reason = "nodes";
                this.done = true;
                return;
            }
            // B120 (m02336): the field comment above has always promised "stop immediately on a
            // proven zero-damage win", but only the unsliced search (solve) and the portfolio
            // acted on it. This sliced loop -- the one the panel runs -- merely SET the flag and
            // kept expanding the rest of the frontier until it dried, which is why the panel could
            // already say 预计无伤 and still show 计算中 for minutes. A route that wins without
            // losing HP and without spending a potion cannot be improved (same rule as the
            // reference's IsProvenZeroDamageRoute), so the answer is final here.
            if (this.provenOptimal && this.best != null && this.best.win) {
                this.reason = "win";
                this.done = true;
                return;
            }
            if (this.layer >= this.maxLayer) {
                // A3: the layer cap is not a reason to abandon parked candidates while budget lasts.
                if (this.promote()) {
                    return;
                }
                this.done = true;
                return;
            }
            if (this.next == null) {
                this.next = new HashMap<String, Node>();
                this.nodeIndex = 0;
                this.actionIndex = 0;
                this.currentActs = null;
                this.expansionsThisLayer = 0;
                int n = this.nodeBudget - this.nodes;
                int n2 = Math.max(1, this.maxLayer - this.layer);
                int n3 = this.layerAllowance = this.nodeBudget > 0 ? Math.max(64, n / n2) : Integer.MAX_VALUE;
            }
            if (this.nodeBudget > 0 && this.expansionsThisLayer >= this.layerAllowance) {
                this.nodeIndex = this.frontier.size();
            }
            if (this.nodeIndex >= this.frontier.size()) {
                int n;
                this.layersRun = this.layer + 1;
                if (this.next.isEmpty()) {
                    // A3: before calling the frontier dead, try a candidate the beam dropped.
                    if (this.promote()) {
                        return;
                    }
                    // The frontier is dry either way. A win used to leave this flag false, which
                    // made the panel say "预算内最好" and report the parked beam overflow as if it
                    // were still computable work -- both wrong once the search has converged.
                    if (!"win".equals(this.reason)) {
                        this.reason = "no-live-branches";
                    }
                    this.frontierDead = true;
                    this.done = true;
                    return;
                }
                // A0: a found win is not a reason to stop; only a proof (zero HP lost, no
                // potion), a dry frontier, or the layer/budget limit ends the search.
                ArrayList<Node> arrayList = new ArrayList<Node>(this.next.values());
                Collections.sort(arrayList, new Comparator<Node>(){

                    @Override
                    public int compare(Node node, Node node2) {
                        if (node.rank != node2.rank) {
                            return node2.rank - node.rank;
                        }
                        if (node.score != node2.score) {
                            return node2.score - node.score;
                        }
                        return node.digest.compareTo(node2.digest);
                    }
                });
                ArrayList<Node> arrayList2 = new ArrayList<Node>();
                int beam = Solver.effectiveBeam(this.beamWidth);
                // A1: same optional rollout re-ranking as the blocking path (Shift+F8).
                if (Solver.rolloutEnabled()) {
                    Solver.rerankByRollout(arrayList, Math.max(beam, beam * Solver.ROLLOUT_REFINE_FACTOR));
                }
                if (beam != this.beamWidth && !this.guardLogged) {
                    this.guardLogged = true;
                    Log.info("memory guard: beam " + this.beamWidth + " -> " + beam + " (heap " + Solver.heapUsedMb() + "MB over limit " + Solver.effectiveHeapLimitMb() + "MB)");
                }
                for (int i = n = Math.min(this.bandOffset, arrayList.size()); i < arrayList.size() && arrayList2.size() < beam; ++i) {
                    arrayList2.add((Node)arrayList.get(i));
                }
                if (Solver.deferredEnabled()) {
                    java.util.Set<Node> kept = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Node, Boolean>());
                    kept.addAll(arrayList2);
                    for (int i = 0; i < arrayList.size(); ++i) {
                        Node overflow = (Node)arrayList.get(i);
                        if (!kept.contains(overflow)) {
                            Solver.defer(this.deferred, overflow, this.layer + 1, this.deferredCap);
                        }
                    }
                }
                this.frontier = arrayList2;
                this.next = null;
                ++this.layer;
                // A0 evidence: this layer only exists because the search kept going after a win.
                if (this.bestWinLayer >= 0) {
                    ++this.layersAfterFirstWin;
                }
                return;
            }
            Node node = this.frontier.get(this.nodeIndex);
            if (this.currentActs == null) {
                String visitKey = node.positionKey == null ? node.digest : node.positionKey;
                if (!this.seen.add(visitKey)) {
                    if (!visitKey.equals(node.digest)) {
                        ++this.mergedByPhase;
                    }
                    ++this.skipped;
                    ++this.transpositions;
                    profile.skipped = this.skipped;
                    profile.transpositions = this.transpositions;
            // B178 (user m23534): this branch runs once per DUPLICATE node, so the summary line that
                    // used to sit here wrote ~12.7k lines (96% of a 13k-line log) on the render thread,
                    // through Log.write() which is synchronized and flushes every line. Counters only;
                    // the one summary is logged by finish().
                    ++this.nodeIndex;
                    this.actionIndex = 0;
                    this.currentActs = null;
                    return;
                }
                long candStart = System.nanoTime();
                this.currentActs = Solver.candidates(node.world);
                long candUs = (System.nanoTime() - candStart) / 1000L;
                if (candUs >= Solver.SLOW_STEP_US) {
                    // B170: enumerating actions can open screens and replay choices; that work is
                    // outside expandAndRecord's timer, so it needs its own line.
                    Log.warn("slow candidates: " + (candUs / 1000L) + "ms layer=" + this.layer
                            + " node=" + this.nodeIndex + "/" + this.frontier.size()
                            + " actions=" + this.currentActs.size() + " (B170)");
                }
                // P7: a tier only allows its own number of potions, so once the line has drunk
                // that many the potion branches are not offered at all (they are pure dead ends).
                if (this.maxPotionUses != Integer.MAX_VALUE
                        && Solver.potionUses(node.path) >= this.maxPotionUses) {
                    this.currentActs = Solver.withoutPotions(this.currentActs);
                }
                ++this.expansions;
                ++this.expansionsThisLayer;
                if (this.currentActs.size() > this.maxBranch) {
                    this.maxBranch = this.currentActs.size();
                }
            }
            if (this.actionIndex >= this.currentActs.size()) {
                ++this.nodeIndex;
                this.actionIndex = 0;
                this.currentActs = null;
                return;
            }
            Action action = this.currentActs.get(this.actionIndex);
            ++this.actionIndex;
            this.expandAndRecord(node, action);
        }

        private void expandAndRecord(Node node, Action action) {
            // B170: one step is one indivisible unit of the sliced loop, so its wall time is the
            // frame the player feels. The profile deltas split it without a second measurement.
            long stepStart = System.nanoTime();
            long fork0 = Solver.profile.fork;
            long install0 = Solver.profile.install;
            long apply0 = Solver.profile.apply;
            long readBack0 = Solver.profile.readBack;
            long digest0 = Solver.profile.digest;
            long uninstall0 = Solver.profile.uninstall;
            long loot0 = Solver.lootNanos;
            List list = Solver.expand(node.world, action, 2);
            long stepUs = (System.nanoTime() - stepStart) / 1000L;
            if (stepUs >= Solver.SLOW_STEP_US) {
                long lootMs = (Solver.lootNanos - loot0) / 1000000L;
                Solver.noteSlowStep(stepUs, this.layer, this.nodeIndex, this.frontier.size(), action,
                        list.size(), stepUs / 1000L - lootMs, lootMs,
                        (Solver.profile.fork - fork0) / 1000000L,
                        (Solver.profile.install - install0) / 1000000L,
                        (Solver.profile.apply - apply0) / 1000000L,
                        (Solver.profile.readBack - readBack0) / 1000000L,
                        (Solver.profile.digest - digest0) / 1000000L,
                        (Solver.profile.uninstall - uninstall0) / 1000000L);
            }
            for (int i = 0; i < list.size(); ++i) {
                Node node2;
                Choice choice = (Choice)list.get(i);
                if (!choice.settled) {
                    ++this.unsettledNodes;
                    if (Solver.settleReasonLogged < 5) {
                        ++Solver.settleReasonLogged;
                        Log.info("position key kept raw (B176): " + choice.settleWhy);
                    }
                }
                ++this.nodes;
                if (list.size() > 1) {
                    ++this.choiceBranches;
                }
                if (choice.monsters != node.monsters) {
                    ++this.splitStates;
                }
                choice.child.setDigest(choice.digest);
                ArrayList<Action> arrayList = new ArrayList<Action>(node.path);
                arrayList.add(Solver.actionFor(choice, action));
                if (!choice.dead && arrayList.size() > this.deepestActions) {
                    this.deepestActions = arrayList.size();
                    this.deepest = choice;
                    this.deepestPath = arrayList;
                }
                int n = Math.max(0, this.startHp - choice.hp);
                int n2 = Math.max(0, this.startPotionHp - choice.potionHp);
                // P2-35: same rule as the blocking portfolio -- realized growth pays for HP lost.
                GrowthBook.Facts now = new GrowthBook.Facts(choice.maxHealth, choice.gold,
                        choice.upgradedCards, choice.ritualDamage, choice.potionHp);
                int growthHp = GrowthBook.credit(arrayList, now, this.startGrowth);
                int n2s = Math.max(0, n - growthHp);
                int saves = DeathSaves.used(this.startFairies, choice.fairies, choice.lizardTail);
                // P7 (user m00001): inside a potion tier the price of a potion is NOT charged on the
                // node -- charging it here is exactly the 18000-point cliff that kept the "drink the
                // dexterity potion first" line out of the beam. The spend is still recorded in n2 and
                // still charged once by netHpLoss/compareRoutes when the tiers are merged, so the
                // final route ranking is unchanged in scale.
                int n2Score = this.deferPotionCost ? 0 : n2;
                int n3 = Solver.score(n2s, n2Score, choice.win, choice.dead, this.layer + 1, choice.mhp, choice.block, choice.alive, saves, choice.gold - this.startGold) + (choice.win ? Solver.lootPoints(choice.loot) : 0) + (choice.win ? Solver.healPoints(choice.postHeal) : 0) + (choice.win ? GrowthCredit.valueOf(arrayList) : 0);
                Node node3 = new Node(choice.child, arrayList, n3, Solver.rankOf(n3, choice.block, choice.energy, choice.powers), n, GameActionManager.turn, choice.win, choice.dead, choice.monsters, saves, choice.digest);
                node3.positionKey = choice.positionKey;
                node3.postHeal = choice.postHeal;
                node3.loot = choice.loot;
                node3.netHpLoss = Solver.netHpLoss(n, growthHp, choice.postHeal, n2);
                // B173 (user m21300): the in-rung best is still picked by the raw score -- the net
                // number is only what the panel prints and what the cross-rung merge compares. Trying
                // to rank by it dropped the board terms and picked zero-damage zero-turn stubs.
                if (n3 > this.best.score) {
                    this.best = node3;
                    this.bestEscaped = choice.escaped && !choice.win && EscapePolicy.mayRecommendEscape();
                }
                if (choice.win && !choice.dead) {
                    // P2-8: keep the best winning line, not the last one the beam happens to reach.
                    // The rank is still the score, so a win that lost 2 more HP but carries 3 HP worth
                    // of extra loot wins -- and the panel's net number legitimately goes up with it.
                    // That is intended: user m21969 (2026-11-10) rejected ranking wins by net HP, and
                    // the net number stays free of the loot term (m21803) so it never reads as "free".
                    if (!this.best.win || n3 > this.best.score) {
                        this.best = node3;
                    }
                    this.reason = "win";
                    if (this.bestWinLayer < 0) {
                        this.bestWinLayer = this.layer + 1;
                    }
                    if (n == 0 && n2 == 0) {
                        this.provenOptimal = true;
                    }
                }
                String layerKey = choice.positionKey == null ? choice.digest : choice.positionKey;
                if ((node2 = this.next.get(layerKey)) != null && n3 <= node2.score) {
                    if (!layerKey.equals(choice.digest)) {
                        ++this.mergedByPhase;
                    }
                    continue;
                }
                this.next.put(layerKey, node3);
            }
        }


        /**
         * A3: refill the frontier from the parked candidates. Costs depth x (fork + apply) to rebuild
         * the state, which is exactly the price of not having kept the World; budget and promotion
         * count bound it, so a dry frontier still terminates.
         */
        private boolean promote() {
            boolean ok = promoteOne();
            // B103: one line per pass, whatever the refusal was. Logging only after the scan finished
            // left every early refusal (no budget, quota used, clock spent) silent -- which is how
            // "kept=2000 promoted=0" reached the panel with no explanation attached at all.
            if (!ok && !this.deferred.isEmpty() && !this.promoteExplained) {
                this.promoteExplained = true;
                if (this.promoteWhy == null || this.promoteWhy.isEmpty()) {
                    this.promoteWhy = "没有可回放的线";
                }
                Log.info("A3 promote: nothing replayed (poisoned=" + this.skipPoisoned + ") ("
                        + this.promoteWhy + ") kept="
                        + this.deferred.size() + " promoted=" + this.promoted
                        + " limit=" + this.promotionLimit + " layer=" + this.layer
                        + " maxLayer=" + this.maxLayer + " depth-skip=" + this.skipDepth
                        + " earlier-skip=" + this.skipEarlier + " duplicate=" + this.skipDup
                        + " rebuild-failed=" + this.skipRebuild + " budgetLeftMs="
                        + Math.max(0L, this.budgetNow() - (System.currentTimeMillis() - this.startedAt)));
            }
            return ok;
        }

        private boolean promoteOne() {
            if (this.deferred.isEmpty()) {
                return false;
            }
            long left = this.budgetNow() - (System.currentTimeMillis() - this.startedAt);
            if (left <= 0L) {
                this.promoteWhy = "搜索预算已用完";
                return false;
            }
            // P2-41: the adaptive limit follows the budget left, not a flat 6. The first call pins
            // it for this search so a slow promotion cannot shrink it halfway through.
            if (this.promotionLimit <= 0) {
                this.promotionLimit = Solver.promotionLimit(left);
                // P7: a wider tier parks and replays proportionally more lines, otherwise the extra
                // beam width only feeds the parked list and never reaches the answer.
                if (this.promoteFloor > 0) {
                    this.promotionLimit = Math.max(this.promotionLimit, this.promoteFloor);
                }
            }
            if (this.promotedThisRung >= this.promotionLimit) {
                this.promoteWhy = "已回放 " + this.promotedThisRung + " 条，到本档的上限";
                return false;
            }
            // B103: this pass' own ceiling, not a latch every profile in the search shares.
            if (this.parkedCapMs < 0L) {
                this.parkedCapMs = Solver.parkedCapFor(this.budgetNow());
            }
            if (this.parkedSpentMs > this.parkedCapMs) {
                Solver.deferredPhaseCap(this.parkedCapMs);
                this.promoteWhy = "停牌阶段已用掉 " + this.parkedCapMs + "ms 的回放预算（本次轮廓的预算 "
                        + this.budgetNow() + "ms）";
                return false;
            }
            Solver.sortDeferred(this.deferred);
            // B103: count every refusal instead of a silent "promoted=0". depth = the parked line
            // sits at or above the layer cap; earlier = replayEarlier is off; dup = already replayed
            // in this pass; rebuild = it did not rebuild into the state it was parked on. The counters
            // are session fields so the refusing line can report them from any exit.
            for (int i = 0; i < this.deferred.size() && this.promotedThisRung < this.promotionLimit; ++i) {
                Deferred entry = this.deferred.get(i);
                // B72 (user m20740): the old rule also demanded `entry.layer >= this.layer`, so when a
                // layer dried up WITHOUT overflow (every child died) the deepest parked candidate was
                // one layer below and every entry was skipped -- the two-slime fight logged
                // "kept=270 promoted=0" and reported a 7-HP race line. A parked line is precisely the
                // line the beam squeezed out earlier (the defensive one), so replay it from whatever
                // layer it was parked on. promotedKeys keeps one candidate from being re-run twice.
                if (entry.layer >= this.maxLayer) {
                    ++this.skipDepth;
                    continue;
                }
                boolean earlier = entry.layer < this.layer;
                if (earlier && !Solver.replayEarlier) {
                    ++this.skipEarlier;
                    continue;
                }
                String key = entry.layer + "|" + entry.digest;
                if (!this.promotedKeys.add(key)) {
                    ++this.skipDup;
                    continue;
                }
                // B107: do not spend 3 ms re-discovering a step this search already proved is not
                // replayable. The skipped lines are named in the report, not silently dropped.
                if (Solver.poisonedStepOf(entry.path) != null) {
                    ++this.skipRebuild;
                    ++this.skipPoisoned;
                    ++Solver.promotedSkippedPoisoned;
                    continue;
                }
                long materializeAt = System.currentTimeMillis();
                World seed = Solver.materialize(this.root, entry);
                long materializeMs = System.currentTimeMillis() - materializeAt;
                this.parkedSpentMs += materializeMs;
                Solver.notePromotionCost(materializeMs);
                if (seed == null) {
                    // B107 (user m04659): NO retry. A rebuild is deterministic -- same root, same
                    // mirror, same actions, same missing answer -- so a second attempt cannot succeed;
                    // re-adding the key only rediscovered the failure. The key stays in promotedKeys,
                    // i.e. this line is "already tried" and is skipped from now on.
                    ++this.skipRebuild;
                    continue;
                }
                ++this.promoted;
                ++this.promotedThisRung;
                if (earlier) {
                    ++this.promotedEarlier;
                }
                int count;
                seed.install();
                try {
                    count = Solver.monsterCount();
                }
                finally {
                    seed.uninstall();
                }
                Node node = new Node(seed, new ArrayList<Action>(entry.path), entry.score, entry.rank,
                        entry.hpLoss, GameActionManager.turn, false, false, count, entry.deathSaves, entry.digest);
                node.positionKey = entry.positionKey;
                this.frontier = new ArrayList<Node>();
                this.frontier.add(node);
                this.nodeIndex = 0;
                this.actionIndex = 0;
                this.currentActs = null;
                this.next = null;
                this.layer = entry.layer;
                if (this.layer + 1 > this.layersRun) {
                    this.layersRun = this.layer + 1;
                }
                return true;
            }
            // B103: the pass ended without filling the quota. Say which refusal emptied it, so a
            // panel that reports "2000 parked, 0 replayed" has an answer attached.
            this.promoteWhy = Solver.parkedRefusal(this.skipDepth, this.skipEarlier, this.skipDup,
                    this.skipRebuild, this.skipPoisoned);
            return false;
        }

        /**
         * B177 (user m23168, option C): continue this same session one rung wider.
         *
         * <p>What a rung change does, and nothing else:
         * <ul>
         *   <li>the beam grows to the next rung (only ever upwards),</li>
         *   <li>the node cap becomes "nodes run so far + twice the work the previous rung did"
         *       (floor 20000), the same bound the old per-rung Session had,</li>
         *   <li>the parked-candidate quota starts over, so the lines the narrow beam dropped are
         *       replayed into the frontier under the wider beam -- the reason a wider rung still
         *       has new work to do without re-expanding the layers already run,</li>
         *   <li>the search state itself (frontier, layer, seen table, best line, counters) is
         *       kept, so the next slice carries on from where the narrow rung stopped.</li>
         * </ul>
         *
         * <p>No widening when the answer is proven, when the job was stopped, or when the time
         * budget is already gone: in each of those a wider beam cannot buy anything.
         */
        private boolean widenToNextRung() {
            if (this.rungBeams == null || this.rung >= this.rungBeams.length) {
                return false;
            }
            if (this.stopRequested) {
                return false;
            }
            if (this.provenOptimal && this.best != null && this.best.win) {
                return false;
            }
            if (System.currentTimeMillis() - this.startedAt >= this.budgetMs) {
                return false;
            }
            int next = this.rungBeams[this.rung];
            if (next <= this.beamWidth) {
                // Nothing wider to run -- skip the rung rather than re-running the same width.
                ++this.rung;
                return this.widenToNextRung();
            }
            // B180 (user m24185, option A): the last rung of a job whose time budget is "unlimited"
            // must not carry a node cap. B171/B179 gave every widening rung 2 * narrow nodes, so a
            // 90k-node job ended with 169 positions still open while the panel said the player had
            // set no limit -- that number was a budget, not the fight's complexity. The session still
            // stops on maxLayer, a proven line, a dry frontier or the stop button.
            boolean lastRung = this.rung == this.rungBeams.length - 1;
            boolean noCap = lastRung && sts1solver.ui.DeepBudget.unlimited(this.budgetMs);
            int cap;
            if (noCap) {
                cap = 0;
            } else if (this.rungNodeCaps != null && this.rung < this.rungNodeCaps.length) {
                // B179: the portfolio gives each beam its historical share of the node budget.
                cap = Math.max(200, this.rungNodeCaps[this.rung]);
            } else {
                if (this.rungCapNodes <= 0) {
                    // B171's bound, measured once from the narrow rung: one widening rung may do
                    // twice the work the narrow pass did (floor 20000). Every wider rung gets the
                    // same cap, so the ladder cannot grow exponentially just because it is wider.
                    int done = Math.max(1, this.nodes - this.nodesAtRungStart);
                    this.rungCapNodes = Math.max(20000, done * 2);
                }
                cap = this.rungCapNodes;
            }
            ++this.rung;
            this.expansionsAtRungStart = this.expansions;
            if (this.widenedAtLayer < 0) {
                this.expansionsAtWiden = this.expansions;
            }
            this.widenedAtLayer = this.layer;
            this.beamWidth = next;
            this.nodesAtRungStart = this.nodes;
            this.nodeBudget = noCap ? 0 : this.nodes + cap;
            this.maxNodes = this.nodeBudget;
            this.done = false;
            this.frontierDead = false;
            this.reason = "layers";
            this.promotedThisRung = 0;
            this.promotionLimit = 0;
            this.parkedCapMs = -1L;
            this.parkedSpentMs = 0L;
            this.promoteExplained = false;
            this.promoteWhy = null;
            if (this.next != null) {
                // Widened in the middle of a layer: give this layer the new rung's allowance
                // instead of the narrow rung's leftover, or it would end on the old cap.
                this.expansionsThisLayer = 0;
                int left = Math.max(1, this.nodeBudget - this.nodes);
                int layersLeft = Math.max(1, this.maxLayer - this.layer);
                // B180: an uncapped rung must not inherit the narrow rung's per-layer allowance.
                this.layerAllowance = noCap ? Integer.MAX_VALUE : Math.max(64, left / layersLeft);
            }
            Log.info("widening in place (B177): rung=" + (this.rung + 1) + "/" + this.rungs()
                    + " beam=" + next + " layer=" + this.layer + " nodes=" + this.nodes
                    + " cap=" + (noCap ? "+unlimited" : "+" + cap)
                    + " parked=" + this.deferred.size());
            return true;
        }

        private void finish() {
            if (this.finished) {
                return;
            }
            boolean bl = false;
            List<Action> list = this.best.path;
            int n = this.best.hpLoss;
            if (!this.best.win && !"win".equals(this.reason) && this.deepestPath != null
                    && this.deepestPath.size() > this.best.path.size()) {
                List<Step> list2;
                int n3 = Math.max(0, this.startHp - this.deepest.hp);
                // B43: same rule as the blocking path -- a losing search reports its longest line.
                List<Step> list3 = list2 = Solver.replay(this.root, this.deepestPath);
                if (list2 != null && list2.size() == this.deepestPath.size()) {
                    list = this.deepestPath;
                    n = n3;
                    bl = true;
                } else {
                    Log.info("route: the deepest line was not usable (steps=" + this.deepestPath.size()
                            + " hpLoss=" + n3 + " bestSteps=" + this.best.path.size()
                            + " replayed=" + (list2 == null ? -1 : list2.size()) + ")");
                }
            }
            // B44: a losing line has to reach the end of the battle here too (m13104). Without this
            // the sliced path (F8 on a boss) handed back a line that stopped mid-fight.
            boolean lineFinished = false;
            if (!this.best.win && list != null && !list.isEmpty()) {
                java.util.List<Action> longer = Solver.finishLine(this.root, list, 8);
                if (longer != null && longer.size() > list.size()) {
                    list = longer;
                    lineFinished = Boolean.TRUE.equals(LINE_FINISHED.get());
                }
            }
            List<Step> list4 = Solver.replay(this.root, list);
            int failedAt = Solver.lastReplayFailedAt();
            boolean verified = failedAt < 0 && list4 != null && list4.size() == list.size();
            String failedOn = Solver.lastReplayFailedOn();
            profile.nodes = this.nodes;
            profile.nodeBudget = this.nodeBudget;
            // B178 (user m23534): ONE summary per pass instead of one line per duplicate node.
            Log.info("position keys (B176): nodes=" + this.nodes + " unsettled=" + this.unsettledNodes
                    + " mergedByPhase=" + this.mergedByPhase + " skipped=" + this.skipped);
            profile.skipped = this.skipped;
            profile.transpositions = this.transpositions;
            // B184: the sliced path never printed the split, so the one place a player actually
            // watches (the panel's F8 search) had no fork/install/apply numbers at all.
            Log.info(profile.line());
            // G2 (JUDGMENT.md): same split as solveInternal -- win is the verdict, the reference's
            // actionCount > 0 travels as Result.completeVictory.
            // B179 (user m23836): the result carries both scales -- the rung that produced the
            // answer (final fields, so they go through the constructor) and the whole job.
            int passNodes = Math.max(0, this.nodes - this.nodesAtRungStart);
            int passExpansions = Math.max(0, this.expansions - this.expansionsAtRungStart);
            this.result = new Result(list4, this.best.win, n, this.best.turn, passNodes, passExpansions, System.currentTimeMillis() - this.startedAt, this.reason, this.layersRun, this.maxBranch, Legal.promptCards(), Solver.planEscaped(list4, this.bestEscaped), this.maxLayer, this.choiceBranches, this.splitStates, this.deepestActions, this.best.deathSaves, bl);
            this.result.passNodes = passNodes;
            this.result.passExpansions = passExpansions;
            this.result.totalNodes = this.nodes;
            this.result.totalExpansions = this.expansions;
            this.result.passes = this.rung + 1;
            this.result.startHp = this.startHp;
            this.result.postHeal = this.best.win ? Math.max(0, this.best.postHeal) : 0;
            this.result.loot = this.best.win ? this.best.loot : null;
            // B173 (user m21300): carry the shared ruler onto the finished answer. The deepest-line
            // replacement (bl) has no heal/loot to credit, so its net is its raw loss.
            this.result.netHpLoss = bl ? n : this.best.netHpLoss;
            this.result.planVerified = verified;
            this.result.planFailedAt = failedAt;
            this.result.planFailedOn = failedOn;
            this.result.planRequested = list.size();
            if (!verified) {
                Log.warn("the printed line is shorter than the one the search found (replayed "
                        + (list4 == null ? 0 : list4.size()) + " of " + list.size() + " steps, first failure at "
                        + failedAt + (failedOn.isEmpty() ? "" : " on " + failedOn) + "); it is not offered as a win");
            }
            // B174 (user m21993 / m22084): say what the planned potion buys. Log only -- the search,
            // the ranking and the printed plan are untouched.
            // P7 (T6): a tiered run reports its own tier-0 baseline instead; the extra counterfactual
            // replay would just re-search the same fight a fifth time.
            if (!this.tiered) {
                String counterfactual = Solver.potionCounterfactual(this.root, list, list4, this.startHp);
                if (!counterfactual.isEmpty()) {
                    Log.info(counterfactual);
                }
            }
            this.result.provenOptimal = this.best.win && this.provenOptimal;
            this.result.frontierExhausted = this.frontierDead;
            this.result.layersAfterFirstWin = this.layersAfterFirstWin;
            this.result.lineFinished = lineFinished || this.best.win;
            // A3 may leave `next` null when it refills the frontier from a parked candidate.
            int left = (this.next == null ? 0 : this.next.size())
                    + Math.max(0, this.frontier.size() - this.nodeIndex);
            this.result.frontierLeft = this.frontierDead ? 0 : left;
            this.result.cutShort = !this.frontierDead;
            this.result.deferred = this.deferred.size();
            this.result.promoted = this.promoted;
            this.result.promotedEarlier = this.promotedEarlier;
            if (this.promoted == 0 && !this.deferred.isEmpty()) {
                this.result.deferredWhy = this.promoteWhy;
            }
            if (!this.deferred.isEmpty()) {
                Log.info("A3 deferred: kept=" + this.deferred.size() + " promoted=" + this.promoted
                        + " earlier=" + this.promotedEarlier
                        + " limit=" + this.promotionLimit
                        + " costMs=" + Math.round(Solver.promotionCostMs())
                        + " samples=" + Solver.promotionCostSamples()
                        + " budgetLeftMs=" + Math.max(0L, this.budgetMs - (System.currentTimeMillis() - this.startedAt)));
            }
            this.finished = true;
        }
    }

    /**
     * B179 shell kept for the callers (Gameplay, the panel, QuickSession, probes): the portfolio is
     * a four-rung ladder, and since P7 that ladder runs once per potion tier. All accounting lives in
     * {@link PotionTieredSession}; this class only owns the job's clock and the stop flag.
     */
    public static final class PortfolioSession {
        /** Test hook: the widest beam this session will run (B172 gate S232). */
        public static int topBeamForTest() {
            int[][] p = Solver.portfolioProfiles();
            return p[p.length - 1][0];
        }
        private final World root;
        private final long budgetMs;
        private final int maxNodes;
        private PotionTieredSession current;
        private volatile boolean stopRequested;
        private boolean finished;

        public PortfolioSession(World world, long l, int n) {
            this.root = world;
            this.budgetMs = Math.max(300L, l);
            this.maxNodes = n;
        }

        public boolean finished() {
            return this.finished;
        }

        public Result result() {
            return this.current == null ? null : this.current.result();
        }

        public void requestStop() {
            this.stopRequested = true;
            PotionTieredSession session = this.current;
            if (session != null) {
                session.requestStop();
            }
        }

        public int nodes() {
            return this.current == null ? 0 : this.current.nodes();
        }

        /** P2-11b: nodes of the pass currently running (B179: the rung, not a rebuilt member). */
        public int passNodes() {
            return this.current == null ? 0 : this.current.passNodes();
        }

        /** B179: expansions run by the whole ladder so far. */
        public int expansions() {
            return this.current == null ? 0 : this.current.expansions();
        }

        /** B179: how many rungs the running tier plans (the number of portfolio profiles). */
        public int rungsForTest() {
            return this.current == null ? 0 : this.current.rungs();
        }

        /** B179: which rung of the running tier (0 = narrow). */
        public int rungForTest() {
            return this.current == null ? -1 : this.current.rung();
        }

        /** B179: the beam the running rung uses. */
        public int beamForTest() {
            return this.current == null ? -1 : this.current.beamWidth();
        }

        /** B179: passes (rungs) run so far by the running tier; a "pass" is a rung of one tier. */
        public int passes() {
            return this.current == null ? 0 : this.current.rung() + 1;
        }

        public int bestActions() {
            return this.current == null ? 0 : this.current.bestActions();
        }

        public List<Action> bestPath() {
            return this.current == null ? null : this.current.bestPath();
        }

        public boolean bestIsWin() {
            return this.current != null && this.current.bestIsWin();
        }

        public int bestHpLoss() {
            return this.current == null ? -1 : this.current.bestHpLoss();
        }

        public int bestNetHpLoss() {
            return this.current == null ? -1 : this.current.bestNetHpLoss();
        }

        /** B115 (m00748): the best line available right now, for the panel's preview row. */
        public List<Action> previewPath() {
            return this.current == null ? null : this.current.previewPath();
        }

        /** B120 (m02336): whether the line {@link #previewPath()} holds already wins. */
        public boolean previewWin() {
            return this.current != null && this.current.previewWin();
        }

        /** B119 (m02060): the running session's deadline (see Session#compensatePause). */
        public void compensatePause(long ms) {
            if (ms > 0L && this.current != null) {
                this.current.compensatePause(ms);
            }
        }

        /** B118 (m01978): expected HP cost of {@link #previewPath()}, or -1 when none is held. */
        public int previewHpLoss() {
            return this.current == null ? -1 : this.current.previewHpLoss();
        }

        /** B173 (user m21300): the same held line's cost on the shared ruler. */
        public int previewNetHpLoss() {
            return this.current == null ? -1 : this.current.previewNetHpLoss();
        }

        public String phase() {
            if (this.finished) {
                return "done";
            }
            return this.current == null ? "准备中" : this.current.phase();
        }

        /** P7b: the potion-worldline hint of the running cell (empty in the tier-order rollback). */
        public String hint() {
            return this.current == null ? "" : this.current.hint();
        }

        /*
         * B179: the four profiles become one ladder. P7: the ladder repeats once per potion tier,
         * each tier on its own share of the budget and node cap, and the answers are merged on net HP.
         */
        public boolean advance(long l) {
            if (this.finished) {
                return true;
            }
            if (this.current == null) {
                int[][] profiles = Solver.portfolioProfiles();
                int[] ladder = new int[profiles.length];
                for (int i = 0; i < profiles.length; i++) {
                    ladder[i] = profiles[i][0];
                }
                this.current = new PotionTieredSession(this.root, ladder, profiles[0][1],
                        this.budgetMs, this.maxNodes);
                Log.info("portfolio ladder (B179): beams=" + java.util.Arrays.toString(ladder)
                        + " potionTiers=" + this.current.tiersForTest()
                        + " potionSlots=" + this.current.slotsForTest()
                        + " usablePotions=" + this.current.usablePotionsForTest()
                        + " cellMode=" + this.current.cellMode()
                        + " cells=" + this.current.cellsForTest()
                        + " nodes=" + this.maxNodes);
            }
            if (this.stopRequested) {
                this.current.requestStop();
            }
            if (!this.current.advance(l)) {
                return false;
            }
            this.finished = true;
            return true;
        }
    }
}
