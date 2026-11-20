/*
 * Decompiled with CFR 0.152.
 */
package sts1solver;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardQueueItem;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.rooms.MonsterRoomBoss;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import sts1solver.drive.Driver;
import sts1solver.mirror.Cloner;
import sts1solver.mirror.Digest;
import sts1solver.mirror.RenderState;
import sts1solver.mirror.VfxGuard;
import sts1solver.mirror.World;
import sts1solver.modsupport.ContentOrigin;
import sts1solver.reflect.Reflect;
import sts1solver.search.Action;
import sts1solver.search.EscapePolicy;
import sts1solver.search.GrowthCredit;
import sts1solver.search.PlanCache;
import sts1solver.search.PostCombatHealing;
import sts1solver.search.Solver;
import sts1solver.ui.AutoPace;
import sts1solver.ui.Hud;
import sts1solver.ui.ViewState;
import sts1solver.util.Log;

public final class Gameplay {
    private static boolean inCombat;
    private static boolean hintShown;
    private static Solver.Result plan;
    private static int step;
    private static String status;
    private static int drift;
    private static int renderRepairs;
    private static String renderRepairDetail;
    private static String viewRestore;
    private static Solver.Step pending;
    private static int pendingTurnBefore;
    private static boolean autopilot;
    /**
     * P2-14: wall clock of the earliest moment the next autoplay action may run.
     *
     * <p>Autoplay used to fire the next step the frame the engine settled, which measured 100-250 ms
     * per action and 1.3 s for a whole fight. The gate never sleeps the render thread; it only
     * compares the clock and returns.
     */
    private static long nextActionAtMs;
    /**
     * m17581: pressing 计算 while the turn is still resolving used to be dropped with a log line --
     * the player pressed again, which reads as "the button did not work". The press is remembered and
     * started on the first frame whose board is settled.
     */
    private static boolean solveQueued;
    private static int overFrames;
    private static int verified;
    private static String escapeNote;
    private static final List<Solver.Step> history;
    private static int replans;
    private static final List<Integer> rowSteps;
    /** The planned route grouped by turn, for the pill strip (STS2 style). */
    private static final List<sts1solver.ui.RouteRow> route;
    private static int stepTarget;
    private static int autopilotFailures;
    private static boolean weakConfirmed;
    private static String vfxSweep;
    private static ContentOrigin.Status origin;
    private static int driftReplans;
    /** Digest seen last frame while an action resolved; used to wait for a stable state. */
    private static String lastPendingDigest;
    private static int pendingStableFrames;
    /**
     * B126 (user m04653): last time the panel said what it is waiting for. A step that never settled
     * used to say nothing until its 20 s deadline, so "the autopilot froze" had no cause in the log.
     */
    private static long settleReportAt;
    /** Replans triggered by an exhausted plan in autopilot; capped so a bad plan cannot loop. */
    private static int exhaustedReplans;
    /** Replans forced because the shown plan belonged to an earlier turn; capped the same way. */
    private static int staleReplans;
    /**
     * B147 (user m12327): where the play trace stood when the pending step was submitted, so the
     * step's own plays can be read back after it settles.
     */
    private static long stepTraceMark;
    // ---- m16202-5: a screen that opens one action AFTER the step which caused it ----------------
    // LiquidMemories queues BetterDiscardPileToHandAction, which only opens the discard-pile GRID
    // on ITS first update -- by then the potion step may already have been checked and its pending
    // cleared, so the screen sat there with nothing left to answer it. The plan's answer is kept
    // for a few seconds after such a step so a late screen is still answered from the plan.
    private static String pendingChoice = "";
    private static String pendingChoiceDisplay = "";
    /** B79: uuids of the planned answer, so the live screen is answered by identity, not by guessing. */
    private static String pendingChoiceUuids = "";
    /** B98: the state of each card the pending answer names (see Action#choicePrints). */
    private static String pendingChoicePrints = "";
    /**
     * B167 (user m18895): the reorder half of the pending answer (see {@link Action#moved}), '|'
     * separated like {@link #pendingChoice} so a chain keeps one segment per screen.
     */
    private static String pendingChoiceMoved = "";
    /** One uuid per entry of {@link #pendingChoiceMoved}, or "" (see Action#movedUuids). */
    private static String pendingChoiceMovedUuids = "";
    /**
     * Which screen of the chain is being answered (P2-57).
     *
     * <p>One action can ask two questions in a row -- HeadChase ("头追") picks a card from the discard
     * pile and then one from the draw pile -- so a planned answer is a '|' separated chain, one entry
     * per screen. The live answer therefore has to remember how far down the chain it is, instead of
     * consuming the whole answer on the first screen.
     */
    private static int pendingChoiceAt;
    /**
     * B190 (user m01149): how many screens the pending step's plan answers, from
     * {@code Action.screenKinds}. {@link #pendingChoice} alone cannot say it -- an answer of
     * "nothing on one screen" and "no screen at all" both split to zero segments.
     */
    private static int pendingScreens;
    /** B190: one screen kind per answered screen, '|' separated like {@link #pendingChoice}. */
    private static String pendingChoiceKinds = "";
    /**
     * B203 (user m01393): what the sandbox's screens held -- every card's state, in screen order,
     * laid out like {@link #pendingChoice} ('|' per screen, ',' per card). Used to tell "the plan's
     * card is on this screen" from "this screen is a different one".
     */
    private static String pendingChoiceOffered = "";
    /** Last "a screen is open and we have no answer" signature, logged once (m16202-5). */
    private static String latentLogged = "";
    /** B202: when autoplay first saw the kind of pick screen that is open now (0 = none). */
    private static long selectionSeenAt;
    /** B202: which kind of pick screen {@link #selectionSeenAt} belongs to. */
    private static Driver.SelectionKind selectionSeenKind;
    /** Last unanswerable card-screen signature (B71), logged once per screen+step. */
    private static String cardScreenLogged = "";
    /**
     * Wall clock of the last action we handed to the game (m19362). The autopilot may only judge a
     * step (drift, or "the waiting action never took the pick") once {@link AutoPace#verifyMs()} has
     * passed since it, because the engine reports itself settled a few frames before the action that
     * reads a screen has actually run.
     */
    private static long lastActionAtMs;
    /** Wall clock of the moment the shown plan was adopted; -1 when there is no plan (B40). */
    private static long planBuiltAt = -1L;
    private static EndTurnRisk lastRisk;
    private static boolean lethalConfirmed;

    // ---- B51 (m15336-2): 自动出牌 = 先「计算」再执行，只有一条求解路径 --------------------------
    // Pressing 自动出牌 used to run a SECOND, blocking solve (solveLive) with its own budget ladder
    // and its own adoption rules. That is why "先按计算再按自动" behaved better than "直接按自动":
    // the two buttons were not the same computation. Now the button arms this flag, starts exactly
    // what 计算 starts, and plays the line when it is adopted.
    private static boolean autoplayArmed;
    private static String autoplayReason;

    // ---- B52 (m15336-3): save & load / 快速SL rebuilt the dungeon under us ------------------------
    private static Object worldPlayer;
    private static Object worldNode;
    private static String worldMode;
    private static int worldSwitches;

    // ---- deep search (P1-4a): a slice-able portfolio, one ~40ms chunk per frame ----
    private static Solver.PortfolioSession deep;
    private static String deepBefore;
    private static RenderState.Snap deepRenderBefore;
    private static ViewState deepView;
    private static long deepStartedAt;
    private static long deepBudgetMs;
    private static final int DEEP_SLICE_MS = 40;
    private static final int DEEP_MAX_NODES = 120000;

    // ---- quick solve (P2-9): F8 is the same search, also advanced in ~40ms slices ----------------
    // The blocking call froze the game for the whole 3-12s budget. Slicing does not change the
    // algorithm, the budget or the stop rules; it only lets the render thread breathe and lets the
    // panel show how many worlds have been reviewed.
    private static sts1solver.search.QuickSession quick;
    private static String quickBefore;
    private static RenderState.Snap quickRenderBefore;
    /** B59: static colours + spine state of the same search, checked so damage cannot last 15s. */
    private static RenderState.StaticColors quickStatics;
    private static String quickSkelBefore;
    private static int staticsRepaired;
    private static int staticsRepairReports;
    private static String deepSkelBefore;
    private static ViewState quickView;
    private static String quickCacheKey;
    private static long quickStartedAt;
    private static final int QUICK_SLICE_MS = 40;
    // B42: an autopilot replan is the SAME search as F8, not a 4 s guess. It runs in slices with the
    // full budget (A0) and is adopted when it finishes; the flags let finishQuick() word it right and
    // let the autopilot know a search is in flight (so it does not read "no plan" as "nothing found").
    private static boolean quickRestartRequested;
    private static boolean quickIsReplan;
    private static String quickReason;
    // ---- B115 (m00748): show the line the search is holding before it is done --------------------
    // The panel used to publish a route only after finishQuick() had replayed and verified the line,
    // so a long budget showed only a progress bar for its whole duration -- the player had no way to
    // judge whether the best line was still changing. After previewAfterMs (3s) the row below shows
    // the line the search holds right now, spelled out as actions, clearly marked as unverified.
    // It costs one small copy per slice and NO replay: buildRoute() needs Step.turn/hp, which only a
    // replay produces, and replaying every 3 seconds on the render thread would stutter.
    private static String previewLine;
    /** Labels of the previewed path, so the string is rebuilt only when the line really moved. */
    private static String previewSig;
    private static int previewAfterMs = 3000;
    /**
     * B117 (m01814): the preview is a real route strip, not a text row.
     *
     * <p>The player asked for the same panel shape the finished plan gets ("可以像计算完毕那样给出
     * 一个正常的面板"). Those rows carry Step.turn/hp, which only a replay produces, so this strip is
     * built from the action labels alone and grouped by the end-turn actions in the path: no HP, no
     * energy, and the turn numbers are counted forward from the turn the fight is in (a card that
     * presses End Turn itself is not visible in the labels, so such a line can merge two turns into
     * one row -- the header says 未回放校验 for exactly this reason).
     */
    private static final List<sts1solver.ui.RouteRow> previewRoute =
            new ArrayList<sts1solver.ui.RouteRow>();
    /** How many turn rows a preview strip shows before it stops (the search line can be long). */
    private static final int PREVIEW_ROWS = 8;
    /**
     * B118 (m01978): expected HP cost of the previewed line, straight from the held search node.
     *
     * <p>{@code hpLoss} is cumulative from the search root (= the board when the search started), so
     * "预计 -N HP" is the search's own number, not a replay: the preview stays replay-free on the
     * render thread. -1 while nothing is held.
     */
    private static int previewHpLoss = -1;
    /**
     * B173 (user m21300): the same line's cost on the shared ruler (growth, post-combat heal and the
     * win's loot credited). The preview prints this one; {@link #previewHpLoss} is only the raw
     * figure next to it, and only when the two differ.
     */
    private static int previewNetHpLoss = -1;

    // ---- B119 (m02060): the search pauses with the game -------------------------------------------
    // The in-game pause menu IS the settings screen (CurrentScreen has no PAUSE; ESC and the gear
    // both call settingsScreen.open(), which sets isScreenUp and screen=SETTINGS). While it is up the
    // game keeps rendering but nothing advances -- so the search must not spend its budget either.
    // A 30s pause inside a 60s budget used to eat half of it and the line published on resume was
    // already cut short. Two things are needed, and the first alone is NOT enough:
    //   1) tick() returns before deep/quick advance, so no slice is spent while the menu is up;
    //   2) on resume every wall-clock start time is shifted forward by the paused span, because the
    //      budgets are measured against System.currentTimeMillis() in QuickSession/Solver/Driver.
    // Gameplay keeps its own clock: now() is the wall clock minus every paused span, so an autopilot
    // delay or a settle window that was running when the menu opened is not instantly "overdue".
    private static boolean paused;
    private static long pausedSinceMs;
    private static long pausedTotalMs;

    /** Wall clock with every paused span removed: all Gameplay deadlines are measured against it. */
    private static long now() {
        return System.currentTimeMillis() - pausedTotalMs;
    }

    /** True while the in-game pause menu (the settings screen) is up. */
    public static boolean pauseMenuUp() {
        try {
            return AbstractDungeon.isScreenUp
                    && AbstractDungeon.screen == AbstractDungeon.CurrentScreen.SETTINGS;
        }
        catch (Throwable t) {
            return false;
        }
    }

    /** True while the search and the autopilot are held because the pause menu is up. */
    public static boolean pausedByMenu() {
        return paused;
    }

    /** B119: the first frame with the menu up -- log once and say so on the panel. */
    private static void pauseForMenu() {
        if (paused) {
            return;
        }
        paused = true;
        pausedSinceMs = System.currentTimeMillis();
        Log.info("game paused: the search and the autopilot are paused with it");
        if (quick != null || deep != null) {
            status = "计算已暂停（游戏暂停中）";
            Hud.set(lines());
        }
    }

    /** B119: the menu closed -- hand the paused span back to every wall-clock budget. */
    private static void resumeFromMenu() {
        long ms = Math.max(0L, System.currentTimeMillis() - pausedSinceMs);
        paused = false;
        pausedSinceMs = 0L;
        pausedTotalMs += ms;
        try {
            if (quick != null) {
                quick.compensatePause(ms);
            }
            if (deep != null) {
                deep.compensatePause(ms);
            }
            Driver.compensatePause(ms);
        }
        catch (Throwable t) {
            Log.warnEvery("pause-compensate", 10000L, "pause compensation failed: " + t);
        }
        Log.info("game resumed: the search continues (paused " + ms + "ms)");
        if (quick != null || deep != null) {
            Hud.set(lines());
        }
    }

    private Gameplay() {
    }

    // ---- deep search (P1-4a) ---------------------------------------------------------------

    public static boolean deepSearching() {
        return deep != null;
    }

    /**
     * Start a LONG search that advances in ~40ms slices.
     *
     * <p>Every node installs a copy of the live game into the global statics, which can only happen
     * on the game thread. A 60-300 second search in one call froze the game and looked like a hang.
     * Between slices the worlds are detached again, so the game keeps rendering and the panel shows
     * progress.
     */
    public static void startDeepSearch() {
        if (deep != null) {
            return;
        }
        if (quick != null) {
            status = "正在计算中：先点「计算」停止它，再开始深度搜索";
            Hud.set(lines());
            return;
        }
        if (!inFight()) {
            status = "不在战斗里";
            Hud.set(lines());
            return;
        }
        World.forceRestore();
        Hud.markStep(-1, -1, false);
        sts1solver.mirror.InputMute.resetSkipLog();
        try {
            Solver.clearExpandFailures();
            // B109 stage 2: same calibration, same reason (before the root world is captured).
            try {
                sts1solver.mirror.StaticCalibrate.beforeSearch();
            } catch (Throwable t) {
                Log.warn("static calibration skipped: " + t);
            }
            World root = World.captureLive();
            deepBefore = Digest.ofCurrent();
            deepRenderBefore = RenderState.capture();
            deepSkelBefore = sts1solver.mirror.SkeletonFingerprint.capture();
            deepView = ViewState.capture();
            sts1solver.search.Solver.resetGuards();
            sts1solver.ui.FrameStats.startSearch();
            deepStartedAt = now();
            deepBudgetMs = deepened(deepBudgetMs());
            sts1solver.search.Solver.resetSlowSteps(); // B170
            deep = new Solver.PortfolioSession(root, deepBudgetMs, deepNodes());
            autopilot = false;
            nextActionAtMs = 0L;
            pending = null;
            stepTarget = -1;
            status = "深度搜索中…";
            Log.info("deep search: started, budget=" + deepBudgetMs + "ms nodes=" + deepNodes()
                    + " boss=" + (sts1solver.ui.DeepBudget.inBossRoom() ? "x" + sts1solver.ui.DeepBudget.BOSS_SECONDS_FACTOR : "no")
                    + " ascension=" + AbstractDungeon.ascensionLevel);
        } catch (Throwable t) {
            World.forceRestore();
            deep = null;
            status = "深度搜索无法启动: " + t;
            Log.error("deep search start failed", t);
        }
        Hud.set(lines());
    }

    /** Stop the running deep search and keep the best line found so far. */
    public static void stopDeepSearch() {
        if (deep == null) {
            return;
        }
        deep.requestStop();
        status = "深度搜索：正在收尾…";
        Hud.set(lines());
    }

    private static void tickDeep() {
        boolean done;
        try {
            done = deep.advance(DEEP_SLICE_MS);
        } catch (Throwable t) {
            World.forceRestore();
            deep = null;
            status = "深度搜索失败: " + t;
            Log.error("deep search failed", t);
            Hud.set(lines());
            return;
        }
        if (!done) {
            long elapsed = now() - deepStartedAt;
            status = "深度搜索 " + (elapsed / 1000) + "s / " + deep.nodes() + " 节点 / 最优 "
                    + deep.bestActions() + " 步（点「计算」停止）";
            Hud.set(lines());
            return;
        }
        finishDeep();
    }

    private static void finishDeep() {
        Solver.Result r = deep.result();
        long elapsed = now() - deepStartedAt;
        String before = deepBefore;
        RenderState.Snap renderBefore = deepRenderBefore;
        ViewState viewBefore = deepView;
        String skelBefore = deepSkelBefore;
        deep = null;
        deepBefore = null;
        deepRenderBefore = null;
        deepSkelBefore = null;
        deepView = null;
        World.forceRestore();
        sts1solver.ui.FrameStats.finishSearch();
        Gameplay.noteSkeletonDiff("deep", skelBefore);
        if (r == null) {
            status = "深度搜索没有结果";
            Hud.set(lines());
            return;
        }
        String after = Digest.ofCurrent();
        if (before != null && !Digest.stable(before).equals(Digest.stable(after))) {
            status = "搜索期间局面变化，结果已丢弃；请重新搜索";
            Log.warn("deep search: LIVE STATE CHANGED DURING SEARCH | "
                    + firstDiff(Digest.stable(before), Digest.stable(after)));
            Hud.set(lines());
            return;
        }
        int repairs = 0;
        if (renderBefore != null) {
            List<String> diffs = RenderState.diff(renderBefore, RenderState.capture());
            if (!diffs.isEmpty()) {
                repairs = RenderState.repair(renderBefore);
                Log.warn("deep search: render state changed, repaired " + repairs);
            }
        }
        String viewFix = viewBefore == null ? "none" : viewBefore.restoreIfChanged();
        VfxGuard.sweep();
        plan = r;
        planBuiltAt = now();
        step = 0;
        history.clear();
        drift = 0;
        lastRisk = null;
        lethalConfirmed = false;
        syncMarkers();
        status = "深度搜索：" + sts1solver.search.PlanText.headline(r);
        Log.info("solve: DEEP " + r.summary() + " steps=" + r.plan.size() + " budget="
                + (elapsed / 1000) + "s renderRepairs=" + repairs + " view=" + viewFix);
        Log.info("slow steps: " + Solver.slowStepSummary());
        Hud.set(lines());
    }

    // ---- quick solve (P2-9): F8 in slices, same algorithm and budget -------------------------

    public static boolean quickSearching() {
        return quick != null;
    }

    /** F8 while a quick search runs: stop it and keep the best line found so far. */
    public static void stopQuickSolve() {
        if (quick == null) {
            return;
        }
        // B51: stopping the search also cancels "play it when it lands" -- the player asked for one
        // thing at a time. (A restart right after this re-arms it explicitly.)
        autoplayArmed = false;
        quick.requestStop();
        status = "计算中：正在收尾…";
        Hud.set(lines());
    }

    /**
     * Start the F8 solve without blocking the frame.
     *
     * <p>This is the same preparation, budget and algorithm as {@link #solveLive()}; the only
     * difference is pacing: the search is a {@link sts1solver.search.QuickSession} advanced by
     * {@link #QUICK_SLICE_MS} per frame. {@code solveLive} stays blocking because the gate suite and
     * the autopilot start path need an answer in the same call; gate S62 keeps the two verdicts equal.
     */
    public static void startQuickSolve() {
        startQuickSolve(false);
    }

    /**
     * One solve, two callers: the 计算 button (F8) and 自动出牌 (B51).
     *
     * @param armAutoplay the press came from 自动出牌: play the line the moment it is adopted
     */
    public static void startQuickSolve(boolean armAutoplay) {
        // B81 (m22687): one search, one summary line about the skipped cursor -- not one per node.
        sts1solver.mirror.InputMute.resetSkipLog();
        autoplayArmed = armAutoplay;
        if (deep != null) {
            autoplayArmed = false;
            status = "深度搜索进行中：点「计算」停止";
            Hud.set(lines());
            return;
        }
        if (quick != null) {
            // B42: a replan search (or an older F8 solve) is in flight. Stop it, and once it has wound
            // down start the solve the player actually asked for: one press must mean one answer.
            quickRestartRequested = true;
            stopQuickSolve();
            // stopQuickSolve() cancels the intent; this call re-declares it for the restart.
            autoplayArmed = armAutoplay;
            return;
        }
        history.clear();
        replans = 0;
        driftReplans = 0;
        stepTarget = -1;
        autopilot = false;
        pending = null;
        lethalConfirmed = false;
        lastRisk = null;
        Hud.demoOff();
        World.forceRestore();
        origin = ContentOrigin.inspect();
        Log.info("content: " + origin.describe());
        String blocked = Gameplay.blockedReason();
        // B162 (user m16041): "a screen is open" and "engine busy" are exactly what an open picker
        // looks like. If the screen IS the picker, the search starts from the answer to it; any other
        // screen still refuses (there is nothing to plan while a reward or map is up).
        if (blocked != null && Gameplay.pickerWaiting()
                && ("a screen is open".equals(blocked) || "engine busy".equals(blocked))) {
            Log.info("solve: starting from the open pick screen (" + blocked + ")");
            blocked = null;
        }
        if (blocked != null) {
            if (blocked.indexOf("busy") >= 0) {
                // A half-settled board is a moment, not a refusal: keep the press and retry.
                solveQueued = true;
                autoplayArmed = armAutoplay;
                status = "等这一拍结算完就开始计算…";
                Hud.set(Gameplay.lines());
                Log.info("solve queued (engine busy): it starts as soon as the board settles");
                return;
            }
            autoplayArmed = false;
            status = Gameplay.refusalText(blocked);
            Hud.set(Gameplay.lines());
            Log.info("solve refused: " + blocked);
            return;
        }
        solveQueued = false;
        // B109 stage 2: decide the fields the sandbox was seen writing BEFORE the search captures its
        // root (a world captured earlier cannot be installed after a registration). No candidate ->
        // this returns immediately; a candidate costs two probe turns.
        try {
            sts1solver.mirror.StaticCalibrate.beforeSearch();
        } catch (Throwable t) {
            Log.warn("static calibration skipped: " + t);
        }
        String digest = Digest.ofCurrent();
        RenderState.Snap render = RenderState.capture();
        ViewState view = ViewState.capture();
        String cacheKey = PlanCache.key(digest, AbstractDungeon.ascensionLevel, EscapePolicy.mode().label, origin == null ? "?" : origin.describe());
        Solver.Result cached = PlanCache.get(cacheKey);
        if (cached != null && !Gameplay.cacheFits(cached)) {
            Log.warn("plan cache: hit rejected, does not fit the live hand | digest=" + digest);
            cached = null;
        }
        if (cached != null) {
            plan = cached;
            planBuiltAt = now();
            step = 0;
            drift = 0;
            lastRisk = null;
            lethalConfirmed = false;
            Gameplay.syncMarkers();
            status = "\u7f13\u5b58\u547d\u4e2d\uff08\u672a\u91cd\u65b0\u641c\u7d22\uff09";
            Hud.set(Gameplay.lines());
            Log.info("solve: cache hit, steps=" + cached.plan.size() + " ascension=" + AbstractDungeon.ascensionLevel + " | digest=" + digest);
            if (autoplayArmed) {
                Gameplay.beginAutoplay(autoplayReason == null ? "自动出牌" : autoplayReason);
            }
            return;
        }
        try {
            World liveRoot = World.captureLive();
            long budgetMs = quickBudget();
            boolean expensive = quickNarrowDeep();
            quickCacheKey = cacheKey;
            quickBefore = digest;
            Gameplay.beginSearchAudit(render);
            quickView = view;
            quickStartedAt = now();
            sts1solver.search.Solver.resetSlowSteps(); // B170
            quick = new sts1solver.search.QuickSession(liveRoot, budgetMs, expensive);
            Gameplay.clearPreview();
            // B48: the mark names the card of the PREVIOUS plan. It must not survive into a search
            // whose result may point somewhere else entirely.
            Hud.markStep(-1, -1, false);
            status = "计算中…";
            Hud.set(lines());
            Log.info("quick search: started, budget=" + budgetMs + "ms narrow=" + expensive
                    + " ascension=" + AbstractDungeon.ascensionLevel);
        }
        catch (Throwable error) {
            World.forceRestore();
            quick = null;
            autoplayArmed = false;
            status = "solve failed: " + error;
            Hud.set(lines());
            Log.error("solve failed", error);
        }
    }

    /**
     * Budget for one solve, from settings.txt (deepSeconds, Boss rooms x2).
     *
     * <p>A0: the budget ladder (3s / 5s / 8s / 12s) is gone. The player asked for "use the whole
     * budget by default, ten minutes is fine as long as it is really computing", so 计算 spends the
     * whole budget and there is no second, deeper button any more (B43). What used to be 加深 now
     * only survives as the Shift+F8 hotkey, see {@link #deepened(long)}.
     */
    private static long quickBudget() {
        long budget = deepBudgetMs();
        // Gates must never spend the player's real (now up to ten minute) budget: they set this cap
        // once, so a harness run stays fast and still exercises the same scheduling code.
        if (budgetCapForTest > 0L) {
            budget = Math.min(budget, budgetCapForTest);
        }
        return budget;
    }

    private static volatile long budgetCapForTest;

    /** Caps the 计算 budget in a harness run (0 = no cap). Used by the gate suite only. */
    public static void setBudgetCapForTest(long ms) {
        budgetCapForTest = ms;
    }

    /** The cap in force, for a gate that wants to assert it is not leaking into a real run. */
    public static long budgetCapForTest() {
        return budgetCapForTest;
    }

    /**
     * The Shift+F8 deep search runs the same search with twice the budget (capped by DeepBudget).
     *
     * <p>This is the leftover of the old 加深 tier: the button is gone (B43) and nothing on the panel
     * says "twice", so the only caller is {@link #startDeepSearch()}. It is kept because the hotkey
     * exists to spend more than one pass; when the budget is already unlimited there is nothing to
     * double and the value is returned unchanged.
     */
    private static long deepened(long budgetMs) {
        if (sts1solver.ui.DeepBudget.unlimited(budgetMs)) {
            // There is nothing to double, and no cap to hit: unlimited stays unlimited (P2-12).
            return budgetMs;
        }
        long doubled = budgetMs * 2L;
        long cap = sts1solver.ui.DeepBudget.MAX_SECONDS * 1000L;
        return Math.max(sts1solver.ui.DeepBudget.MIN_SECONDS * 1000L, Math.min(cap, doubled));
    }

    /** Same narrow-deep rule as the blocking F8 path (P2-2d). */
    private static boolean quickNarrowDeep() {
        boolean bossRoom = AbstractDungeon.getCurrRoom() instanceof com.megacrit.cardcrawl.rooms.MonsterRoomBoss;
        int bossHp = 0;
        try {
            for (int i = 0; i < AbstractDungeon.getMonsters().monsters.size(); i++) {
                AbstractMonster m = AbstractDungeon.getMonsters().monsters.get(i);
                if (m != null && m.maxHealth > bossHp) {
                    bossHp = m.maxHealth;
                }
            }
        }
        catch (Throwable ignored) {
            // a missing monster list means the normal portfolio
        }
        return bossRoom || bossHp >= 200;
    }

    // B170: where the hook's time goes. The search slice is capped at QUICK_SLICE_MS, so anything
    // the player notices beyond that cap is in one of these phases (or in the panel beside them).
    static long lastAdvanceUs;
    static long lastRepairUs;
    static long lastPreviewUs;
    static long lastHudUs;
    static long lastTickUs;

    private static void tickQuick() {
        tickQuick(QUICK_SLICE_MS);
    }

    private static void tickQuick(long sliceMs) {
        if (quick == null) {
            return;
        }
        long tickStart = System.nanoTime();
        if (!Gameplay.inFight()) {
            World.forceRestore();
            quick = null;
            Gameplay.clearPreview();
            status = "战斗已结束，计算取消";
            Hud.set(lines());
            return;
        }
        boolean done;
        long phaseStart = System.nanoTime();
        try {
            done = quick.advance(sliceMs);
        }
        catch (Throwable t) {
            World.forceRestore();
            quick = null;
            Gameplay.clearPreview();
            status = "计算失败: " + t;
            Log.error("quick search failed", t);
            Hud.set(lines());
            return;
        }
        Gameplay.lastAdvanceUs = (System.nanoTime() - phaseStart) / 1000L;
        phaseStart = System.nanoTime();
        // B59: a sandbox write to a static colour used to stay on screen until the search ended
        // (15s in the elite fight). Repairing between slices costs ~136 reflective reads.
        Gameplay.repairStaticsDuringSearch();
        Gameplay.lastRepairUs = (System.nanoTime() - phaseStart) / 1000L;
        if (!done) {
            long elapsed = now() - quickStartedAt;
            long cap = Math.max(1L, quick.budgetMs());
            phaseStart = System.nanoTime();
            Gameplay.refreshPreview(elapsed);
            Gameplay.lastPreviewUs = (System.nanoTime() - phaseStart) / 1000L;
            // P7b (user m00331): "which potion worldline is being searched now", appended to the
            // status line as well as the panel, so the player sees the tier move without opening it.
            String hint = quick.potionHint();
            hint = hint.isEmpty() ? "" : " \u00b7 " + hint;
            if (sts1solver.ui.DeepBudget.unlimited(cap)) {
                // No percentage without a denominator (P2-12): the moving worldline count is the
                // "it is still working" signal the player asked for.
                status = "计算中 · " + worldlineText(quick) + hint
                        + " · 已用 " + (elapsed / 1000) + "s（不限）";
            } else {
                int pct = (int)Math.min(95L, elapsed * 100L / cap);
                status = "计算中 " + pct + "% · " + worldlineText(quick) + hint;
            }
            phaseStart = System.nanoTime();
            Hud.set(lines());
            Gameplay.lastHudUs = (System.nanoTime() - phaseStart) / 1000L;
            Gameplay.lastTickUs = (System.nanoTime() - tickStart) / 1000L;
            return;
        }
        Gameplay.lastTickUs = (System.nanoTime() - tickStart) / 1000L;
        finishQuick();
    }

    /**
     * B115 (m00748): keep the "line the search is holding right now" row up to date.
     *
     * <p>Only runs while a search is in flight and only after {@link #previewAfterMs}; the row is
     * rebuilt only when the line actually changed, so a steady search costs no string work per slice.
     * The copy comes from the search tree, never from a replay: replaying every three seconds on the
     * render thread would stutter, and B114 showed a forced end turn is exactly where a replay is
     * expensive.
     */
    private static void refreshPreview(long elapsed) {
        if (quick == null || elapsed < previewAfterMs) {
            return;
        }
        List<Action> path = quick.previewPath();
        if (path == null || path.isEmpty()) {
            return;
        }
        // B121 (m02524): the preview is only ever a COMPLETE line. The player has said more than once
        // that a route stopping mid-fight is not a route ("我一直都强调要走完不要给我打一半的线"), so a
        // held node that has not won yet is a half line: show progress only and take down any preview
        // that was up (a cheaper branch can replace a winning line with a losing one).
        if (!quick.previewWin()) {
            if (previewLine != null || !previewRoute.isEmpty()) {
                Gameplay.clearPreview();
            }
            return;
        }
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < path.size(); ++i) {
            sig.append(path.get(i).label).append('\u0001');
        }
        String signature = sig.toString();
        if (signature.equals(previewSig)) {
            return;
        }
        previewSig = signature;
        previewHpLoss = quick.previewHpLoss();
        previewNetHpLoss = quick.previewNetHpLoss();
        buildPreviewRoute(path);
        // The strip below carries the actions; this row says what it is, how long the line is, and
        // (B118) what it is expected to cost -- the finished panel's "预计 -N HP" number.
        previewLine = "暂定路线（仍在计算，未回放校验）· " + path.size() + " 步"
                + hpPreviewText(previewNetHpLoss, previewHpLoss)
                + (previewRoute.size() < countPreviewRows(path)
                        ? " · 只显示前 " + previewRoute.size() + " 回合" : "");
    }

    /**
     * B118 (m01978): " · 预计 -N HP → 剩 M HP" for the previewed line, or " · 预计无伤" when it costs
     * nothing. Empty when the search holds no HP number yet. Mirrors the finished panel's wording
     * ("预计 -N HP" on the end-turn pill, 掉血 N in the headline).
     */
    private static String hpPreviewText(int netHp, int rawHp) {
        // B121 (m02524): the preview only publishes winning lines, so the cost shown here is the cost
        // of an actual route to the end of the fight -- never "what we have lost so far".
        // B173 (user m21300) printed the NET cost here. B175 (user m22661) takes the number back to
        // the real loss: the net also credits growth, and growth is priced richly enough (Lesson
        // Learned = 5 HP per upgraded card) that one 神化 turned a 3-HP Looter fight into
        // "预计无伤". The credit now only appears as a note and never changes the number or the word.
        // B173 correction (user m21803): the reward screen is NOT credited here. A fight that costs 6
        // HP and hands out 6-HP-worth of loot must still read as -6 HP, not 0 -- otherwise a line that
        // simply lost blood would look free. Rewards stay priced inside the score only (B164).
        if (netHp < 0 && rawHp < 0) {
            return "";
        }
        // B175 (user m22661): 无伤 must mean the line really loses no HP. The credited number is a
        // RANKING value -- it subtracts growth (a whole deck of upgrades from 神化 is worth dozens of
        // HP by the growth budgets) and the post-combat heal -- so a Looter fight that really took 3
        // HP read as "预计无伤（原始 3，成长/回血抵扣 45）". The player never got those 45 HP back.
        // The word and the number now come from the raw loss; the credit is only a note (m21803: a
        // line that loses blood must never read as free).
        int shown = rawHp >= 0 ? rawHp : Math.max(0, netHp);
        String note = shown > netHp && netHp >= 0
                ? "（成长/回血抵扣 " + (shown - Math.max(0, netHp)) + " 不计入掉血）" : "";
        if (shown == 0) {
            return " · 预计无伤" + note;
        }
        int hpNow = -1;
        try {
            if (com.megacrit.cardcrawl.dungeons.AbstractDungeon.player != null) {
                hpNow = com.megacrit.cardcrawl.dungeons.AbstractDungeon.player.currentHealth;
            }
        } catch (Throwable ignored) {
            // Outside a fight (a gate) there is no player; the number alone is still useful.
        }
        if (hpNow < 0) {
            return " · 预计 -" + shown + " HP" + note;
        }
        return " · 预计 -" + shown + " HP → 剩 " + Math.max(0, hpNow - shown) + " HP" + note;
    }

    /** Test hook for {@link #hpPreviewText}: the exact row the panel would print. */
    public static String hpPreviewTextForTest(int netHp, int rawHp) {
        return hpPreviewText(netHp, rawHp);
    }

    /** How many turn rows the whole path would need; used only for the "only the first N" note. */
    private static int countPreviewRows(List<Action> path) {
        int rows = 1;
        for (int i = 0; i < path.size(); ++i) {
            if (path.get(i).isEndTurn()) {
                ++rows;
            }
        }
        return rows;
    }

    /**
     * B117: turns the search's held line into the same turn-row strip the finished plan uses.
     *
     * <p>Rows are cut at end-turn actions and numbered forward from the turn the fight is in. The
     * pills carry the step index so the panel highlights and scrolls exactly like a real plan; a
     * click on one does nothing, because {@link #requestStep} refuses while {@link #plan} is null.
     */
    private static void buildPreviewRoute(List<Action> path) {
        previewRoute.clear();
        if (path == null || path.isEmpty()) {
            return;
        }
        int turn = 1;
        try {
            turn = Math.max(1, com.megacrit.cardcrawl.actions.GameActionManager.turn);
        } catch (Throwable ignored) {
            // A gate may call this outside a fight; the row numbers are cosmetic.
        }
        sts1solver.ui.RouteRow row = null;
        int current = turn;
        for (int i = 0; i < path.size(); ++i) {
            Action a = path.get(i);
            // B122 (user m02740): an action carries the turn it was taken in. When the fight ends a
            // turn by itself (TrancePower at the start of turn), the line jumps over that turn with
            // no action in it -- draw it as its own row instead of merging it into the next one.
            int before = a.turnBefore >= 0 ? a.turnBefore : current;
            if (before > current) {
                row = null;
                for (int t = current; t < before; ++t) {
                    if (!addSkippedRow(t)) {
                        return;
                    }
                }
                current = before;
            }
            if (row == null) {
                if (previewRoute.size() >= PREVIEW_ROWS) {
                    break;
                }
                row = new sts1solver.ui.RouteRow("第 " + current + " 回合", "");
                previewRoute.add(row);
            }
            row.add(a.label + autoPlaySuffix(a), i);
            int after = a.turnAfter >= 0 ? a.turnAfter : (a.isEndTurn() ? before + 1 : before);
            if (after > before) {
                row = null;
                for (int t = before + 1; t < after; ++t) {
                    if (!addSkippedRow(t)) {
                        return;
                    }
                }
                current = after;
            }
        }
    }

    /**
     * B122 (user m02740): one row for a turn the fight ended before the plan reached an action in
     * it. False when the strip is already full.
     */
    private static boolean addSkippedRow(int turn) {
        if (previewRoute.size() >= PREVIEW_ROWS) {
            return false;
        }
        sts1solver.ui.RouteRow auto = new sts1solver.ui.RouteRow("第 " + turn + " 回合", "自动结束");
        auto.add("结束回合（自动）", -1);
        previewRoute.add(auto);
        return true;
    }

    /**
     * B117: publishes the preview strip into the list the panel draws.
     *
     * <p>The panel reads {@link #route()} live, so the strip follows the search without a Hud call.
     * It is republished on every {@link #lines()} while a preview exists, which is one list copy.
     */
    private static void publishPreviewRoute() {
        route.clear();
        rowSteps.clear();
        route.addAll(previewRoute);
        for (int i = 0; i < previewRoute.size(); ++i) {
            rowSteps.add(previewRoute.get(i).steps.isEmpty() ? -1 : previewRoute.get(i).steps.get(0));
        }
    }

    /** B117: true while the panel is showing an unverified preview strip. */
    public static boolean previewActive() {
        return !previewRoute.isEmpty();
    }

    /** Probe hook (S177, B122): drives the preview strip from a hand-built line. */
    public static void setPreviewPathForTest(List<Action> path) {
        buildPreviewRoute(path);
        publishPreviewRoute();
    }

    /** Probe hook (S177, B122): the preview rows as the panel would draw them. */
    public static List<sts1solver.ui.RouteRow> previewRouteForTest() {
        return previewRoute;
    }


    /**
     * B121 (m02524): gate hook -- true when the held line behind the preview (or the row about to be
     * published) reaches victory, i.e. the preview is a whole route and not half a fight.
     */
    public static boolean previewWinForTest() {
        return quick != null && quick.previewWin();
    }

    /** B115/B117: drop the preview row and strip (a new search, the search ended, board changed). */
    private static void clearPreview() {
        previewLine = null;
        previewSig = null;
        previewHpLoss = -1;
        previewNetHpLoss = -1;
        previewRoute.clear();
        route.clear();
        rowSteps.clear();
    }

    /**
     * P2-11b: one wording for the counters, on the panel and in the status line. The first number
     * is every pass combined (what the player watched grow); the pass figure is only added when it
     * differs, so a simple fight still reads as one number.
     */
    public static String worldlineText(sts1solver.search.QuickSession job) {
        if (job == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        b.append("已查阅 ").append(job.nodes()).append(" 条世界线");
        int pass = job.passNodes();
        if (pass > 0 && pass != job.nodes()) {
            b.append("（本档 ").append(pass).append("）");
        }
        if (job.nodesPerSecond() > 0) {
            b.append("（").append(job.nodesPerSecond()).append(" 条/s）");
        }
        return b.toString();
    }

    /** B115: gate hook -- how long a search runs before the preview row appears (default 3s). */
    public static void setPreviewAfterMsForTest(int ms) {
        previewAfterMs = ms;
    }

    /** B119: gate seam -- elapsed ms of the running search on the pausable clock (-1 when none). */
    public static long quickElapsedForTest() {
        return quick == null ? -1L : now() - quickStartedAt;
    }

    /** B119: gate seam -- nodes expanded by the running search (-1 when none). */
    public static int quickNodesForTest() {
        return quick == null ? -1 : quick.nodes();
    }

    /** Drives the sliced search from a headless gate; returns true when it is finished. */
    public static boolean advanceQuickForTest(long sliceMs) {
        tickQuick(Math.max(4L, sliceMs));
        return quick == null;
    }

    /** Where two digests first part company ('|' separated fields), for the log line. */
    private static String firstDiff(String a, String b) {
        if (a == null || b == null) {
            return "expected=" + a + " actual=" + b;
        }
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        int from = Math.max(0, i - 40);
        int toA = Math.min(a.length(), i + 120);
        int toB = Math.min(b.length(), i + 120);
        return "firstDiff@" + i + " expected[" + a.substring(from, toA) + "] actual["
                + b.substring(from, toB) + "]";
    }

    /** B59: everything a search has to be audited against, captured in one place. */
    private static void beginSearchAudit(RenderState.Snap render) {
        quickRenderBefore = render;
        quickStatics = RenderState.captureStaticColorsOnly();
        quickSkelBefore = sts1solver.mirror.SkeletonFingerprint.capture();
        staticsRepaired = 0;
        staticsRepairReports = 0;
        sts1solver.search.Solver.resetGuards();
        sts1solver.ui.FrameStats.startSearch();
    }

    /** B59: put back any static colour the sandbox wrote, without waiting for the search to end. */
    private static void repairStaticsDuringSearch() {
        RenderState.StaticColors sc = quickStatics;
        if (sc == null) {
            return;
        }
        try {
            List<String> changed = RenderState.diffStatics(sc);
            if (changed.isEmpty()) {
                return;
            }
            int fixed = RenderState.repairStatics(sc);
            staticsRepaired += fixed;
            staticsRepairReports++;
            if (staticsRepairReports <= 3) {
                Log.warn("quick search: the search recoloured the screen (" + changed.size()
                        + " static colour(s)), repaired " + fixed + " mid-slice | " + firstName(changed, 4));
            }
        } catch (Throwable ignored) {
            // an audit must never break a search
        }
    }

    /** B59: did the search move a live skeleton? Names every creature that changed. */
    private static void noteSkeletonDiff(String phase, String before) {
        if (before == null) {
            return;
        }
        try {
            List<String> diffs = sts1solver.mirror.SkeletonFingerprint.diff(
                    before, sts1solver.mirror.SkeletonFingerprint.capture());
            // B192 round 5: a search spans real frames, so the live animation clock advances on its
            // own. Only a clock-only difference is live play, not a sandbox write; anything else
            // (animation name, time scale, placement, pose, tint) is still a warning.
            List<String> real = new java.util.ArrayList<String>();
            int clockOnly = 0;
            for (int i = 0; i < diffs.size(); i++) {
                String d = diffs.get(i);
                if (sts1solver.mirror.SkeletonFingerprint.clockOnly(d)) {
                    ++clockOnly;
                } else {
                    real.add(d);
                }
            }
            if (real.isEmpty()) {
                Log.info(phase + " search: spine state unchanged"
                        + (clockOnly == 0 ? ""
                                : " (" + clockOnly + " animation clock(s) advanced by live frames)"));
                return;
            }
            Log.warn(phase + " search: SPINE STATE CHANGED by the search (" + real.size() + ") | "
                    + firstName(real, 3));
        } catch (Throwable ignored) {
            // an audit must never break a search
        }
    }

    /** First {@code n} entries of a diff list, joined for one log line. */
    private static String firstName(List<String> diffs, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < diffs.size() && i < n; i++) {
            if (i > 0) {
                b.append(" ; ");
            }
            b.append(diffs.get(i));
        }
        if (diffs.size() > n) {
            b.append(" ; +").append(diffs.size() - n).append(" more");
        }
        return b.toString();
    }

    /** B59: how many static colours the searches had to put back (0 is the healthy answer). */
    public static int staticsRepaired() {
        return staticsRepaired;
    }

    private static void finishQuick() {
        sts1solver.search.QuickSession job = quick;
        Solver.Result r = job == null ? null : job.result();
        long elapsed = now() - quickStartedAt;
        String before = quickBefore;
        RenderState.Snap renderBefore = quickRenderBefore;
        String skelBefore = quickSkelBefore;
        ViewState viewBefore = quickView;
        String key = quickCacheKey;
        quick = null;
        quickBefore = null;
        quickRenderBefore = null;
        quickSkelBefore = null;
        quickStatics = null;
        quickView = null;
        quickCacheKey = null;
        Gameplay.clearPreview();
        sts1solver.ui.FrameStats.finishSearch();
        // B51: an armed solve may be restarted from here (one press, one answer), so the intent has
        // to survive the bookkeeping below -- which clears it when the line is unusable.
        boolean armForRestart = autoplayArmed;
        World.forceRestore();
        if (r == null) {
            autoplayArmed = false;
            status = "计算没有结果";
            Hud.set(lines());
            return;
        }
        String after = Digest.ofCurrent();
        String stableBefore = Digest.stable(before);
        String stableAfter = Digest.stable(after);
        if (before != null && !stableBefore.equals(stableAfter)) {
            autoplayArmed = false;
            status = "计算期间局面已变化，结果已丢弃；请重新点「计算」";
            Log.warn("quick search: LIVE STATE CHANGED DURING SEARCH | "
                    + firstDiff(stableBefore, stableAfter));
            Hud.set(lines());
            return;
        }
        if (before != null && !before.equals(after)) {
            // Only the transient fields moved (the action-manager phase and queue counters, or a
            // dying monster's powers). B19/B41 settled that those never decide whether a plan was
            // right, so the answer is still good -- but say it, with the field, so a later log can
            // prove it instead of guessing (m16202-3: "计算完了却说没算出结果").
            Log.info("quick search: only transient fields moved, the result stands | "
                    + firstDiff(before, after));
        }
        renderRepairs = 0;
        renderRepairDetail = "";
        if (renderBefore != null) {
            List<String> diffs = RenderState.diff(renderBefore, RenderState.capture());
            if (!diffs.isEmpty()) {
                renderRepairs = RenderState.repair(renderBefore);
                renderRepairDetail = firstName(diffs, 5);
                Log.warn("quick search: render state changed, repaired " + renderRepairs
                        + " | " + renderRepairDetail);
            }
        }
        Gameplay.noteSkeletonDiff("quick", skelBefore);
        String viewFix = viewBefore == null ? "none" : viewBefore.restoreIfChanged();
        if (!"clean".equals(viewFix)) {
            viewRestore = viewFix;
            Log.warn("quick search: UI state changed, restored: " + viewRestore);
        } else {
            viewRestore = viewFix;
        }
        vfxSweep = VfxGuard.sweep();
        Cloner.EFFECT_CLONES.clear();
        if (!"clean".equals(vfxSweep)) {
            Log.warn("vfx guard after quick search: " + vfxSweep);
        }
        boolean wasReplan = quickIsReplan;
        String replanReason = quickReason;
        quickIsReplan = false;
        quickReason = null;
        if (key != null) {
            PlanCache.put(key, r);
        }
        plan = r;
        planBuiltAt = now();
        step = 0;
        drift = 0;
        lastRisk = null;
        lethalConfirmed = false;
        Gameplay.syncMarkers();
        if (!r.win && !r.escaped && ("time".equals(r.reason) || "nodes".equals(r.reason))) {
            status = "预算用尽，未算到胜利；可点「计算」再给一轮预算";
        } else if (!r.win && !r.escaped && sts1solver.search.Solver.deferredPhaseCapped()) {
            // B59: the parked-candidate pass was cut short on purpose -- say so instead of showing a
            // survival line as if the search had proved the fight lost.
            // The deep button is gone (B43) and asking for it was a dead end: the action that exists is
            // another full pass of 计算, which rebuilds the same parked list from the current position.
            status = sts1solver.search.PlanText.headline(r) + "；停车候选阶段被缩短（原因见上），可点「计算」再给一轮预算";
        } else {
            status = sts1solver.search.PlanText.headline(r);
        }
        if (wasReplan) {
            exhaustedReplans = 0;
            staleReplans = 0;
            boolean limited = "time".equals(r.reason) || "nodes".equals(r.reason);
            if (r.win || r.escaped) {
                status = replanReason + "：" + sts1solver.search.PlanText.headline(r);
            } else if (limited) {
                // A0 + B42: hitting a time/node limit is NOT proof the fight is lost. Never keep playing
                // the survival line as if it were the answer -- stop, say why, and let the player deepen.
                autopilot = false;
                status = replanReason + "：预算用尽仍未算到胜利（不代表打不过）；点「计算」再给一轮预算";
                Log.warn("replan search hit its limit without a win: the line shown is a survival line, autopilot stopped");
            } else {
                autopilot = false;
                status = replanReason + "：" + sts1solver.search.PlanText.headline(r) + "；走完就停";
            }
        }
        // B51: the line is adopted -- if 自动出牌 asked for it, start playing now (a survival line
        // still asks for the usual confirmation press instead of being played silently).
        if (!wasReplan && autoplayArmed) {
            Gameplay.beginAutoplay(autoplayReason == null ? "自动出牌" : autoplayReason);
        }
        Hud.set(Gameplay.lines());
        Log.info("solve: QUICK " + r.summary() + " ascension=" + AbstractDungeon.ascensionLevel);
        Log.info("slow steps: " + Solver.slowStepSummary());
        Log.info("plan : " + r.planText());
        Gameplay.logLoot(r);
        // B125 (m03996 #7): the sandbox keeps its own record of the beat it simulated.
        Log.info("trace: " + r.planTrace());
        Log.info("solve wall clock: " + elapsed + "ms (sliced)");
        if (quickRestartRequested) {
            quickRestartRequested = false;
            startQuickSolve(armForRestart);
        }
    }

    /**
     * m17581: a 计算 press that arrived while the turn was still resolving is kept, then started on the
     * first frame whose board is settled. One press still means one answer -- it just waits a beat.
     */
    private static void startQueuedSolve() {
        if (!solveQueued || quick != null || deep != null || pending != null) {
            return;
        }
        boolean picking = Gameplay.pickerWaiting();
        if (!Gameplay.inRoomCombat() || (!picking && com.megacrit.cardcrawl.dungeons.AbstractDungeon.isScreenUp)) {
            solveQueued = false;
            return;
        }
        // B162 (user m16041): while a pick screen waits, the board is frozen ON PURPOSE. That is the
        // state the search starts from -- it answers the screen as its first step.
        if (!Gameplay.engineSettled() && !picking) {
            return;
        }
        boolean arm = autoplayArmed;
        solveQueued = false;
        Log.info("solve queued: the board settled, starting the search the player asked for");
        Gameplay.startQuickSolve(arm);
    }

    /** B162: a pick screen is waiting for an answer -- the search may start from there. */
    public static boolean pickerWaiting() {
        try {
            return Driver.isAwaitingSelection();
        }
        catch (Throwable throwable) {
            return false;
        }
    }

    /** Harness hook: is a 计算 press still waiting for the board to settle? */
    public static boolean solveQueuedForTest() {
        return solveQueued;
    }

    /** Deep-search node cap: settings.txt (deepNodes); 0 (the default) means no cap at all. */
    private static int deepNodes() {
        int nodes = 0;
        try {
            java.io.File f = new java.io.File(sts1solver.SolverMod.dir(), "settings.txt");
            if (f.isFile()) {
                List<String> cfg = java.nio.file.Files.readAllLines(f.toPath(),
                        java.nio.charset.StandardCharsets.UTF_8);
                for (int i = 0; i < cfg.size(); i++) {
                    String line = cfg.get(i).trim();
                    if (line.startsWith("deepNodes=")) {
                        nodes = Integer.parseInt(line.substring("deepNodes=".length()).trim());
                    }
                }
            }
        } catch (Throwable ignored) {
            // a bad settings file must never block the search
        }
        int clamped = Math.max(0, Math.min(sts1solver.ui.DeepBudget.MAX_NODES, nodes));
        return sts1solver.ui.DeepBudget.nodes(clamped, sts1solver.ui.DeepBudget.inBossRoom());
    }

    /** Deep-search budget: settings.txt (deepSeconds); 0 (the default) means unlimited (P2-12). */
    private static long deepBudgetMs() {
        long seconds = 0L;
        try {
            java.io.File f = new java.io.File(sts1solver.SolverMod.dir(), "settings.txt");
            if (f.isFile()) {
                List<String> cfg = java.nio.file.Files.readAllLines(f.toPath(),
                        java.nio.charset.StandardCharsets.UTF_8);
                for (int i = 0; i < cfg.size(); i++) {
                    String line = cfg.get(i).trim();
                    if (line.startsWith("deepSeconds=")) {
                        seconds = Long.parseLong(line.substring("deepSeconds=".length()).trim());
                    }
                }
            }
        } catch (Throwable ignored) {
            // a bad settings file must never block the search
        }
        return sts1solver.ui.DeepBudget.budgetMs(seconds, sts1solver.ui.DeepBudget.inBossRoom());
    }

    /** "300s" or "不限" for a resolved time budget -- one wording for panel and log (P2-12). */
    public static String budgetText(long budgetMs) {
        return sts1solver.ui.DeepBudget.unlimited(budgetMs) ? "不限" : (budgetMs / 1000) + "s";
    }

    /** Effective deep-search budget for the room the player is in (Boss rooms double it). */
    public static long effectiveDeepBudgetMs() {
        return deepBudgetMs();
    }

    /** Effective node cap for the room the player is in (Boss rooms double it). */
    public static int effectiveDeepNodes() {
        return deepNodes();
    }

    public static void tick() {
        sts1solver.ui.FrameStats.tick();
        // A frame that starts inside someone else's sandbox window (the probe harness installs a
        // world, then drives a frame) cannot be blamed for what that window still holds.
        boolean frameBeganClean = !sts1solver.drive.Driver.isInstalled();
        try {
            // B119 (m02060): the pause menu freezes the game, so it freezes us too. Nothing below
            // runs while it is up -- no slice is spent, nothing is executed, no timer advances.
            if (Gameplay.pauseMenuUp()) {
                Gameplay.pauseForMenu();
                return;
            }
            if (paused) {
                Gameplay.resumeFromMenu();
            }
            // A deep search owns the game thread in SMALL slices; while it runs nothing else may
            // expand, because a slice aliases the live statics.
            if (deep != null) {
                tickDeep();
                return;
            }
            // P2-9: the F8 solve advances here instead of freezing the frame.
            if (quick != null) {
                tickQuick();
                return;
            }
            if (World.anyInstalled()) {
                World.forceRestore();
            }
            // B52: a rebuilt dungeon invalidates every static we keep; checked here so it can never
            // see an installed sandbox copy.
            Gameplay.detectWorldSwitch();
            Gameplay.pollPending();
            // m16202-5: a screen whose step already finished (a potion's pick opens one action
            // later) still has to be answered -- but only from the plan, never by guessing.
            Gameplay.answerLatentSelection();
            Gameplay.startQueuedSolve();
            if (stepTarget > step && pending == null && !autopilot && plan != null) {
                Gameplay.executeStepInternal();
            } else if (stepTarget >= 0 && step >= stepTarget) {
                stepTarget = -1;
            }
            boolean inRoomCombat = Gameplay.inRoomCombat();
            boolean combatOver = inRoomCombat && Driver.combatOver();
            overFrames = combatOver ? ++overFrames : 0;
            // A finished fight keeps the panel "in combat" for a few frames so the last step can settle.
            boolean active = inRoomCombat && (!combatOver || overFrames < 20);
            Gameplay.maybeAutopilot(active);
            if (active != inCombat) {
                inCombat = active;
                if (active) {
                    plan = null;
                    step = 0;
                    status = "idle";
                    hintShown = false;
                    // B147: the divergence replan allowance is per COMBAT -- a new fight starts with a
                    // fresh one, so one bad simulation cannot silently eat the allowance of the next.
                                origin = ContentOrigin.inspect();
                    Log.info("combat detected: the 计算 button solves it | " + origin.describe());
                } else {
                    plan = null;
                    step = 0;
                    status = "idle";
                    hintShown = false;
                    pending = null;
                    autopilot = false;
                    lastRisk = null;
                    lethalConfirmed = false;
                    overFrames = 0;
                    Hud.set(null);
                    Hud.markStep(-1, -1, false);
                }
            }
            if (inCombat && plan == null && !hintShown) {
                hintShown = true;
                Hud.set(Gameplay.lines());
            }
        }
        catch (Throwable ignored) {
            // a failing tick must never break the game loop
        }
        finally {
            // B48 (m14700): the marker layer only draws while the solver is idle. Early returns above
            // (a running slice search, a tracked deep search) must not leave it looking idle.
            sts1solver.ui.Hud.setBusy(deep != null || quick != null || pending != null);
            // m21115-2: the boxes follow 自动出牌, not the plan. A plan made by the button is a plan
            // nobody is playing yet, so it gets no markers on the board.
            sts1solver.ui.Hud.setAutopilotMarkers(autopilot);
            // B59: the borrowed frame delta must be back by now. If it is not, every animation in
            // the live game is running at SIM_DELTA per frame -- take it back and say so.
            if (frameBeganClean) {
                sts1solver.drive.Driver.verifyReleased("the end of a frame");
            }
        }
    }

    /**
     * True when the shown plan describes an earlier turn than the live fight.
     *
     * <p>This is exactly what happens when the player ends the turn by hand after a plan was
     * computed: the remaining steps were searched on a board that no longer exists, so executing
     * them plays a card with the wrong energy or ends the fresh turn immediately.
     */
    public static boolean planIsStale() {
        if (!Gameplay.hasPlan()) {
            return false;
        }
        try {
            int from = Math.max(0, Gameplay.step);
            List<Solver.Step> steps = Gameplay.plan.plan;
            if (from >= steps.size()) {
                return false;
            }
            int live = GameActionManager.turn;
            Solver.Step current = steps.get(from);
            // Without a step of ours in flight, the only way the turn moved is the player pressing
            // End Turn: every remaining step then belongs to a board that no longer exists.
            if (pending == null) {
                return current != null && current.turn < live;
            }
            // B126 (user m04653): while OUR step is still settling, a step from an earlier turn is
            // not proof the plan is dead. The fight skips turns by itself (TrancePower presses End
            // Turn at the start of a turn, one stack per skipped turn) and the pending step belongs
            // to the turn BEFORE the skip. The plan survives as long as some remaining step still
            // STARTS in the live turn or a later one. (Start, not turnAfter: an end-turn step
            // legitimately reaches the next turn, so turnAfter would keep a dead plan alive.)
            for (int i = from; i < steps.size(); ++i) {
                Solver.Step s = steps.get(i);
                if (s != null && s.turn >= live) {
                    return false;
                }
            }
            return current != null && current.turn < live;
        }
        catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * B40: after a plan runs out, is it worth searching again? A WIN line that ran out early (a slime
     * split, the board moved on) is; a survival line ("no win found, deepest playable line") is not,
     * because replaying it to the end and searching again only produced a different, longer line a few
     * seconds later -- which the player reads as the plan changing by itself.
     */
    public static boolean shouldReplanAfterExhaustion(Solver.Result finished) {
        return finished == null || finished.win || finished.escaped;
    }

    /**
     * True when the first step of a freshly built plan belongs to the live turn or a later one (B40).
     * A plan that starts in an earlier turn is dropped by {@link #dropStalePlan()} on its first frame,
     * which used to trigger another search immediately.
     */
    public static boolean startsInLiveTurn(Solver.Result r, int liveTurn) {
        try {
            if (r == null || r.plan == null || r.plan.isEmpty()) {
                return false;
            }
            return r.plan.get(0).turn >= liveTurn;
        }
        catch (Throwable ignored) {
            return true;
        }
    }

    /** Throw away a plan that no longer matches the live turn. True when one was dropped. */
    private static boolean dropStalePlan() {
        if (!Gameplay.planIsStale()) {
            return false;
        }
        int belongsTo = -1;
        try {
            belongsTo = Gameplay.plan.plan.get(Gameplay.step).turn;
        }
        catch (Throwable ignored) {
            // The stale-plan log is diagnostic: a plan that lost its step array must still produce a
            // line (belongsTo stays -1) instead of an exception inside the frame loop.
        }
        long age = Gameplay.planBuiltAt <= 0L ? -1L : now() - Gameplay.planBuiltAt;
        Log.info("plan stale: step " + (Gameplay.step + 1) + " belongs to turn " + belongsTo
                + ", live turn " + GameActionManager.turn + "; dropping it (plan age " + age + "ms)");
        if (age >= 0L && age < 1500L) {
            // Two very different causes land here, so the line names both: the player ended the turn
            // by hand (normal, B35), or the plan was built while a turn was still resolving (the B40
            // bug -- the settle guards are what keep this from happening).
            Log.warn("plan went stale " + age + "ms after it was built: the turn moved under it (a hand end-turn, or a plan built mid-turn)");
        }
        Gameplay.plan = null;
        Gameplay.step = 0;
        Gameplay.stepTarget = 0;
        return true;
    }

    private static void maybeAutopilot(boolean fightActive) {
        if (!autopilot || pending != null) {
            return;
        }
        if (Gameplay.dropStalePlan()) {
            if (++staleReplans > 5) {
                Gameplay.stopAutopilot("计划连续落在旧回合并被丢弃 5 次；请点「计算」重算");
                return;
            }
            // m16202-2: no silent recompute. The plan belongs to a turn that already passed, which
            // means the live board moved without us -- stop and let the player decide.
            Gameplay.stopAutopilot("计划属于上一回合（你先结束了回合），自动出牌已停止；点「计算」重算");
            return;
        }
        if (!Gameplay.hasPlan()) {
            // m16202-2: 计算完就冻结。A plan that ran out is done, win line or not: searching again
            // on our own is exactly what read as "打几个回合它又自己算了一次". The player's 计算 is the
            // only thing that starts a new route now.
            Gameplay.stopAutopilot("路线已走完（不代表打不过）；点「计算」可以再算一条");
            return;
        }
        if (!fightActive) {
            autopilot = false;
            status = "\u6218\u6597\u7ed3\u675f";
            Hud.set(Gameplay.lines());
            return;
        }
        // Idle frames while the engine resolves an action are NOT failures. Counting them as such
        // stopped autopilot with "engine busy 31 times" and refreshed the panel every frame, which
        // is its own stutter.
        if (!Gameplay.engineSettled()) {
            return;
        }
        // P2-14: one beat per action. The frame loop only compares the clock -- it never sleeps --
        // so the game keeps rendering at full speed while the plan waits for its next turn.
        if (!AutoPace.due(now(), nextActionAtMs)) {
            return;
        }
        if (!Gameplay.executeStepInternal()) {
            if (Driver.isAwaitingSelection() || AbstractDungeon.isScreenUp) {
                // B202 (user m01245): waiting for the pick screen's beat is not a failure.
                return;
            }
            if (++autopilotFailures > 30) {
                Gameplay.stopAutopilot("\u81ea\u52a8\u6267\u884c\u8fde\u7eed\u5931\u8d25 " + autopilotFailures + " \u6b21\uff1a" + status);
            }
            return;
        }
        autopilotFailures = 0;
        nextActionAtMs = AutoPace.nextAt(now(), AutoPace.delayMs());
    }

    /**
     * P2-14: how long autoplay still has to wait before its next action, 0 when it may act now.
     * Exposed for the panel/watchdog line only.
     */
    public static long paceWaitMs() {
        if (!autopilot) {
            return 0L;
        }
        long now = now();
        return AutoPace.due(now, nextActionAtMs) ? 0L : nextActionAtMs - now;
    }

    private static boolean inRoomCombat() {
        try {
            if (AbstractDungeon.player == null) {
                return false;
            }
            AbstractRoom abstractRoom = AbstractDungeon.getCurrRoom();
            return abstractRoom != null && abstractRoom.phase == AbstractRoom.RoomPhase.COMBAT && abstractRoom.monsters != null && !abstractRoom.isBattleOver;
        }
        catch (Throwable throwable) {
            return false;
        }
    }

    private static boolean inFight() {
        return Gameplay.inRoomCombat() && !Driver.combatOver();
    }

    public static void reset() {
        plan = null;
        planBuiltAt = -1L;
        step = 0;
        // B119: a new fight starts with a clean clock (the menu state itself is the game's).
        paused = false;
        pausedSinceMs = 0L;
        pausedTotalMs = 0L;
        Gameplay.clearPreview();
        status = "idle";
        drift = 0;
        pending = null;
        Gameplay.clearPendingChoice();
        lastActionAtMs = 0L;
        Driver.clearSelectionRetry();
        latentLogged = "";
        cardScreenLogged = "";
        autopilot = false;
        autoplayArmed = false;
        solveQueued = false;
        staleReplans = 0;
        verified = 0;
        lethalConfirmed = false;
        weakConfirmed = false;
        lastRisk = null;
        // P2-27: the text caches belong to a plan that no longer exists.
        sts1solver.ui.Panel.clearCaches();
        Hud.set(Gameplay.lines());
    }

    /**
     * B52 (m15336-3): did the dungeon get rebuilt under us (save &amp; load / 快速SL)?
     *
     * <p>Quick Restart's SL calls {@code AbstractDungeon.reset()} and reloads the save, which replaces
     * the whole dungeon object tree. The plan, the tracked step, the autopilot flag and the arming
     * flag are our own statics: they survived, so after a load the mod kept executing a plan for the
     * old board, or started a full-budget search the moment a fight appeared again. Both read as
     * "SL is laggy now" -- the load itself was never the slow part.
     *
     * <p>Identity of the player, the current map node and the game mode is the cheapest signal that
     * the tree was rebuilt; it is only sampled on idle frames, never while a sandbox copy is
     * installed (those alias the statics).
     */
    private static void detectWorldSwitch() {
        Object p;
        Object n;
        String m;
        try {
            p = AbstractDungeon.player;
            n = AbstractDungeon.currMapNode;
            m = com.megacrit.cardcrawl.core.CardCrawlGame.mode == null ? null
                    : com.megacrit.cardcrawl.core.CardCrawlGame.mode.name();
        }
        catch (Throwable t) {
            return;
        }
        boolean same = p == worldPlayer && n == worldNode
                && (m == null ? worldMode == null : m.equals(worldMode));
        if (same) {
            return;
        }
        boolean had = plan != null || pending != null || autopilot || autoplayArmed;
        worldPlayer = p;
        worldNode = n;
        worldMode = m;
        if (!had) {
            return;
        }
        ++worldSwitches;
        Log.info("world switched (save/load or new dungeon): solver state cleared | plan="
                + (plan == null ? "-" : plan.plan.size() + " steps")
                + " pending=" + (pending == null ? "-" : pending.action.label)
                + " autopilot=" + autopilot + " armed=" + autoplayArmed
                + " count=" + worldSwitches
                + " | " + sts1solver.ui.FrameStats.heapLine());
        plan = null;
        planBuiltAt = -1L;
        step = 0;
        stepTarget = -1;
        pending = null;
        sts1solver.ui.Panel.clearCaches();
        autopilot = false;
        autoplayArmed = false;
        solveQueued = false;
        autopilotFailures = 0;
        nextActionAtMs = 0L;
        exhaustedReplans = 0;
        staleReplans = 0;
        drift = 0;
        overFrames = 0;
        lethalConfirmed = false;
        lastRisk = null;
        inCombat = false;
        hintShown = false;
        status = "\u65b0\u5bf9\u5c40/\u8bfb\u6863\uff1a\u6c42\u89e3\u5668\u5df2\u6e05\u7a7a\uff08\u70b9\u300c\u8ba1\u7b97\u300d\u91cd\u7b97\uff09";
        Hud.set(null);
        Hud.markStep(-1, -1, false);
    }

    /** How many times a rebuilt dungeon dropped the solver's state (B52); gate-only. */
    public static int worldSwitchCount() {
        return worldSwitches;
    }

    /** Gate-only: forget the last seen dungeon identity so the next tick sees a "switch". */
    public static void forgetWorldForTest() {
        worldPlayer = null;
        worldNode = null;
        worldMode = null;
    }

    public static boolean hasPlan() {
        return plan != null && step < Gameplay.plan.plan.size();
    }

    /** The plan currently shown, for gates that need the verdict; may be null. */
    public static Solver.Result currentPlan() {
        return plan;
    }

    /**
     * A one-line description of what the mod is doing right now, for the hang dump (B47).
     *
     * <p>When the render thread stops beating, the only useful question is which of our synchronous
     * sections it entered. This string is written next to the stacks, so the dump says both "stuck
     * inside install()" and "the plan was in step 4 of a 30-step line".
     */
    public static String watchdogState() {
        try {
            StringBuilder b = new StringBuilder();
            b.append("inCombat=").append(inCombat);
            b.append(" autopilot=").append(autopilot);
            b.append(" pending=").append(pending == null ? "-" : String.valueOf(pending.action));
            b.append(" plan=").append(plan == null ? "-" : (step + "/" + plan.plan.size()));
            b.append(" quick=").append(quick == null ? "-" : (quickIsReplan ? "replan" : "solve"));
            b.append(" deep=").append(deep == null ? "-" : deep.phase());
            b.append(" turn=").append(com.megacrit.cardcrawl.actions.GameActionManager.turn);
            b.append(" screen=").append(com.megacrit.cardcrawl.dungeons.AbstractDungeon.screen);
            b.append(" status=").append(status == null ? "-" : status);
            if (autopilot) {
                b.append(" pace=").append(AutoPace.delayMs()).append("ms wait=").append(Gameplay.paceWaitMs());
            }
            b.append(" hud=").append(sts1solver.ui.Hud.markerGate());
            b.append(" ").append(sts1solver.drive.Driver.gfxState());
            if (sts1solver.ui.FrameStats.active()) {
                b.append(" frame={").append(sts1solver.ui.FrameStats.describe()).append("}");
            }
            b.append(" markerUs=").append(sts1solver.ui.Hud.lastMarkerMicros());
            if (sts1solver.ui.Hud.lastMarkerGeometry() != null
                    && !sts1solver.ui.Hud.lastMarkerGeometry().isEmpty()) {
                b.append(" geom=").append(sts1solver.ui.Hud.lastMarkerGeometry());
            }
            return b.toString();
        } catch (Throwable t) {
            return "state unavailable: " + t;
        }
    }

    public static int rowStepIndex(int n) {
        if (n < 0 || n >= rowSteps.size()) {
            return -1;
        }
        return rowSteps.get(n);
    }

    public static int stepTarget() {
        return stepTarget;
    }

    public static boolean requestStep(int n) {
        if (plan == null || n < step || n >= Gameplay.plan.plan.size()) {
            return false;
        }
        stepTarget = n + 1;
        status = "\u70b9\u51fb\u6267\u884c\u5230\u7b2c " + (n + 1) + " \u6b65";
        Hud.set(Gameplay.lines());
        Log.info("click: execute through step " + (n + 1));
        return true;
    }

    public static String panelText() {
        List<String> rows = Gameplay.lines();
        if (rows == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < rows.size(); ++i) {
            text.append(rows.get(i)).append('\n');
        }
        return text.toString();
    }

    /**
     * B164 (m17808): the reward screen's value is logged with the plan, never drawn in the panel.
     *
     * <p>m17808: "面板多出来的这一行删掉，战后多少金币什么的你知道就行". The number still feeds
     * {@code Solver.score} (that is how killing the Looter beats letting it run), but it is an input
     * to the choice rather than a readout the player acts on, so it belongs in the log with the plan
     * text. Only logged when something was actually earned.
     */
    static void logLoot(sts1solver.search.Solver.Result r) {
        if (r == null || r.loot == null || r.loot.isEmpty()) {
            return;
        }
        Log.info("loot : " + r.loot.describe());
    }

    public static List<String> lines() {
        String expandFailures = Solver.expandFailures();
        // B119 (m02060): while the menu is up, say we are paused. A progress bar whose percentage
        // stopped moving (because the slice is held) would read as a stall, which is the one thing
        // the panel must never look like.
        if (paused && (quick != null || deep != null)) {
            ArrayList<String> held = new ArrayList<String>();
            held.add("STS1 Engine Solver  [计算已暂停]");
            held.add("游戏暂停中：计算和自动出牌一起停住，恢复游戏后接着算（暂停时间不计入预算）");
            return held;
        }
        if (deep != null) {
            ArrayList<String> progress = new ArrayList<String>();
            long elapsed = now() - deepStartedAt;
            progress.add("STS1 Engine Solver  [深度搜索中]");
            progress.add("已用 " + (elapsed / 1000) + "s / " + budgetText(deepBudgetMs) + "   节点 "
                    + deep.nodes() + "   最优 " + deep.bestActions() + " 步");
            progress.add(deep.phase());
            if (!expandFailures.isEmpty()) {
                progress.add("引擎异常: " + expandFailures);
            }
            progress.add("再点「计算」停止，保留当前最优线");
            return progress;
        }
        if (quick != null) {
            ArrayList<String> progress = new ArrayList<String>();
            long elapsed = now() - quickStartedAt;
            long cap = Math.max(1L, quick.budgetMs());
            if (sts1solver.ui.DeepBudget.unlimited(cap)) {
                // Unlimited: no percentage bar, because there is nothing to be a percentage of.
                // The worldline line below is the progress indicator (P2-12).
                progress.add("STS1 Engine Solver  [计算中]");
                progress.add("已用 " + (elapsed / 1000) + "s（不限，一直算到分叉跑完或再点「计算」）");
            } else {
                int pct = (int)Math.min(95L, elapsed * 100L / cap);
                StringBuilder bar = new StringBuilder();
                int filled = Math.max(0, Math.min(20, pct / 5));
                for (int i = 0; i < 20; i++) {
                    bar.append(i < filled ? '\u2588' : '\u2591');
                }
                progress.add("STS1 Engine Solver  [计算中 " + pct + "%]");
                progress.add(bar + " " + pct + "%   已用 " + (elapsed / 1000) + "s / " + (cap / 1000) + "s");
            }
            progress.add(worldlineText(quick)
                    + "   最优 " + quick.bestActions() + " 步（未证明）   " + quick.phase());
            // B115 (m00748): the line the search is holding right now. It is NOT the plan: it has not
            // been replayed, so the row says so and the turn rows stay empty until finishQuick().
            if (previewLine != null) {
                progress.add(previewLine);
                publishPreviewRoute();
            }
            long stalled = quick.stalledMs();
            if (stalled >= 3000L) {
                progress.add("⚠ 已有 " + (stalled / 1000) + "s 没有新世界线：可能卡在某次结算上（再点「计算」可停）");
            }
            if (!expandFailures.isEmpty()) {
                progress.add("引擎异常: " + expandFailures);
            }
            // B149 (user m12829 #3): "再点一次算下一轮" was not true -- a second click does not start
            // another round, and the plan is already the best line this search found. Say only what
            // the click does.
            progress.add("再点「计算」就采用当前最优线");
            return progress;
        }
        ArrayList<String> rows = new ArrayList<String>();
        // B32: once a plan exists its own row already states the result, so repeating the status in
        // the title row printed the same sentence twice (reported as 重复出现的文本).
        // m19237: the player does not want the internal bookkeeping on screen. "渲染已修复 6" and
        // "UI状态已还原" were already written to solver.log (where they belong); showing them here only
        // raised the question of what they meant.
        rows.add("STS1 Engine Solver" + (plan == null && !status.isEmpty() ? "  [" + status + "]" : "")
                + (Hud.failures() > 0 ? "   HUD ERRORS: " + Hud.failures() : ""));
        if (!Solver.expandFailures().isEmpty()) {
            rows.add("引擎异常（搜索分支被丢弃）: " + Solver.expandFailures());
        }
        if (plan == null) {
            // B36: the route strip is a SEPARATE list from the text rows, and only buildRoute()
            // clears it. Before a plan exists this early return skipped that call, so the pills from
            // the previous fight stayed on screen (reported: "a new fight still shows the last
            // fight\s play suggestions"). Clear both here; Panel also refuses to draw a strip
            // without a plan, so a path that never reaches lines() cannot resurrect one either.
            route.clear();
            rowSteps.clear();
            rows.add("点「计算」求解本场（真实引擎镜像 + beam 搜索）");
            return rows;
        }
        if (pending != null) {
            rows.add("正在执行: " + Gameplay.pending.action.label + "（等待游戏推进）");
        }
        // B33: the header rows for 来源 / 难度 / 预算 / 遗物 / 面板按钮 were removed. The player asked
        // for STS2's minimal panel (result, damage, node count), and the rest is in solver.log.
        if (!escapeNote.isEmpty()) {
            rows.add(escapeNote);
        }
        if (lastRisk != null) {
            rows.add(lastRisk.describe());
        }
        // Every mod is accepted (m11229), so there is no "uncertified" wording any more; the panel
        // still says when the fight contains mod content, because that is worth knowing.
        boolean modContent = origin != null && !origin.vanilla;
        if (!history.isEmpty()) {
            int first = Math.max(0, history.size() - 3);
            StringBuilder executed = new StringBuilder("已执行 ");
            executed.append(history.size()).append(" 步:");
            for (int i = first; i < history.size(); ++i) {
                executed.append(i == first ? " " : " | ").append(Gameplay.history.get(i).action.label);
            }
            rows.add(executed.toString());
        }
        // B160 (user m15722): the potion-outlook row is gone from the panel. The forecast still drives
        // the credit in Solver.score; the panel line was never a decision the player makes here.
        // m19237: the growth rows (P2-35/P2-36) are gone from the panel. The player's point was that
        // listing the pricing rules under the plan is not information about this fight; the budgets
        // still drive the score and are still printable from settings/log when someone wants them.
        String healingRow = PostCombatHealing.describe();
        if (!healingRow.isEmpty()) {
            rows.add(healingRow);
        }
        // B164 (user m17363): what the reward screen pays is part of what winning this fight is worth,
        // and the plan is chosen on that difference (killing the Looter instead of letting it run).
        // m17808: it stays OUT of the panel -- the value is an input to the choice, not a readout the
        // player acts on; the plan's log line carries it ("loot : ...") when someone needs the numbers.
        rows.add(sts1solver.search.PlanText.headline(Gameplay.plan)
                + (modContent ? "（含 mod 内容：按真实引擎模拟）" : "")
                // m19237: "预计掉血 N" repeated what the headline and the turn rows already say, and
                // "N 回合" was the plan's total turn count, so on turn 2 it still read "1 回合".
                + " · " + Gameplay.plan.nodes + " 节点 · " + Gameplay.plan.millis + "ms"
                + (Gameplay.plan.potionsUsed > 0 ? " · 药水 " + Gameplay.plan.potionsUsed : "")
                + (verified > 0 ? " · 已校验 " + verified + " 步" : "")
                + (autopilot ? " · 自动执行中 · " + AutoPace.label() : ""));
        // The actions are no longer text rows: they live in the turn rows of the pill strip (see
        // route() and Panel.drawRoute), which can hold the whole plan and scroll instead of folding.
        rowSteps.clear();
        buildRoute();
        // B165 (user m17993): the legend for the '*' marks, only when this plan actually has one.
        if (Gameplay.plan != null && Gameplay.plan.plan != null) {
            for (int i = 0; i < Gameplay.plan.plan.size(); ++i) {
                if (Gameplay.plan.plan.get(i).action.rngNormalized) {
                    rows.add("* 该步开屏随机已归一（一次开屏只抽一次；手打若沿用原版逐帧重抽，随机流会不同）");
                    break;
                }
            }
        }
        // m22405: the old "未展开（会打开选择提示）: ..." row was a development note, not something the
        // player can act on. The list is still collected (Legal.promptCards) and still lands in the log.
        return rows;
    }

    /** The planned route grouped by turn; empty before a plan exists. */
    public static List<sts1solver.ui.RouteRow> route() {
        return route;
    }

    /** Index of the next planned step (0 when the plan should be replanned). */
    public static int currentStep() {
        return Gameplay.step;
    }

    /**
     * Groups the current plan into one row per turn, one pill per action.
     *
     * <p>This replaces the old per-action text rows. Those had to stop at 16 rows and fold everything
     * past the next turn, so the player could only see the few steps around the cursor (reported: the
     * panel cannot be scrolled to see what comes later). A turn row holds all of its actions and the
     * renderer scrolls the whole strip, so the plan is always fully readable.
     *
     * <p>Repeated identical actions in the same turn are still merged into one pill with a xN suffix,
     * exactly like the old rows: four Strikes are one decision, not four.
     */
    private static void buildRoute() {
        route.clear();
        if (plan == null || plan.plan == null || plan.plan.isEmpty()) {
            return;
        }
        // B33 / B85: Step.turn is now the turn the step was TAKEN in, so the end-turn action that
        // closes turn N reports N and sits on the same row as that turn's cards (the player reads
        // 结束回合, and the damage taken after it, as the end of turn N). A card that presses End
        // Turn (Vault) reports N too, and the steps it unlocks report N+1 -- one rule, no guessing.
        int[] shown = new int[plan.plan.size()];
        for (int i = 0; i < plan.plan.size(); ++i) {
            Solver.Step st = plan.plan.get(i);
            shown[i] = st.turn;
        }
        int currentTurn = Integer.MIN_VALUE;
        sts1solver.ui.RouteRow current = null;
        int rowFirst = 0;
        for (int i = 0; i < plan.plan.size(); ++i) {
            Solver.Step st = plan.plan.get(i);
            if (shown[i] != currentTurn) {
                // B122 (user m02740): a turn can end without the player ending it -- 昏睡
                // (TrancePower) presses End Turn at the start of the turn it skips. That turn has no
                // step of its own, so it gets a row of its own instead of the number jumping.
                if (currentTurn != Integer.MIN_VALUE && shown[i] > currentTurn + 1) {
                    for (int t = currentTurn + 1; t < shown[i]; ++t) {
                        sts1solver.ui.RouteRow auto = new sts1solver.ui.RouteRow(
                                "第 " + t + " 回合", "自动结束");
                        auto.add("结束回合（自动）", -1);
                        route.add(auto);
                    }
                }
                currentTurn = shown[i];
                rowFirst = i;
                current = new sts1solver.ui.RouteRow("第 " + currentTurn + " 回合",
                        turnStats(rowFirst, currentTurn, shown));
                route.add(current);
            }
            int repeats = 1;
            if (!st.action.isEndTurn()) {
                while (i + repeats < plan.plan.size()
                        && shown[i + repeats] == shown[i]
                        && plan.plan.get(i + repeats).action.label.equals(st.action.label)) {
                    ++repeats;
                }
            }
            StringBuilder label = new StringBuilder(st.action.label);
            if (repeats > 1) {
                label.append(" ×").append(repeats);
            }
            // B124 (user m03444): the cards the fight played by itself while this step ran. They are
            // part of what the step did, so they belong on the step, not in a separate row: 唐 plays
            // up to its stack count every turn and a plan that hides them looks like it played less.
            label.append(autoPlaySuffix(st.action));
            // B165 (user m17993): this step drew through the normalized open-screen random
            // (Discovery/power potion generates once per activation). A hand-played sequence that
            // kept the untouched game's per-frame re-rolls would be on another random stream, so
            // the step is marked and one legend line below the plan explains the mark.
            if (st.action.rngNormalized) {
                label.append(" *");
            }
            String delta = st.targetDelta();
            if (!delta.isEmpty()) {
                label.append("  ").append(delta);
            }
            if (st.action.isEndTurn() && i > 0) {
                int hpLost = Math.max(0, plan.plan.get(i - 1).hp - st.hp);
                if (hpLost > 0) {
                    label.append("  预计 -").append(hpLost).append(" HP");
                }
            }
            current.add(label.toString(), i);
            i += repeats - 1;
        }
    }

    /**
     * The numbers on one turn row: HP from before its first action to after its last one (which
     * includes the enemy turn when the player closed the turn there), plus the energy and block as
     * they stand when the player ends the turn.
     */
    private static String turnStats(int rowFirst, int turn, int[] shown) {
        Solver.Step start = plan.plan.get(rowFirst);
        // A step's hp is read AFTER the action, so the first row would otherwise start at the HP
        // its own first card left behind. Result.startHp is the HP the plan started from (-1 when a
        // caller built a Result by hand), so the row still shows a real number in that case.
        int hpBefore = rowFirst > 0 ? plan.plan.get(rowFirst - 1).hp
                : (plan.startHp >= 0 ? plan.startHp : start.hp);
        Solver.Step last = null;
        Solver.Step beforeEndTurn = null;
        for (int i = rowFirst; i < plan.plan.size() && shown[i] == turn; ++i) {
            Solver.Step step = plan.plan.get(i);
            last = step;
            if (!step.action.isEndTurn()) {
                beforeEndTurn = step;
            }
        }
        Solver.Step numbers = beforeEndTurn != null ? beforeEndTurn : last;
        if (numbers == null) {
            return "";
        }
        int hpEnd = last == null ? hpBefore : last.hp;
        return "HP " + hpBefore + " → " + hpEnd + "   能量 " + numbers.energy
                + (numbers.block > 0 ? "   格挡 " + numbers.block : "");
    }

    private static String join(List<String> items, int max) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < items.size() && i < max; ++i) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(items.get(i));
        }
        if (items.size() > max) {
            text.append(" \u7b49 ").append(items.size()).append(" \u5f20");
        }
        return text.toString();
    }

    private static void syncMarkers() {
        if (plan == null || step >= Gameplay.plan.plan.size()) {
            Hud.markStep(-1, -1, false);
            return;
        }
        Solver.Step step = Gameplay.plan.plan.get(Gameplay.step);
        // Identity first: the hand is rebuilt as cards are drawn and discarded, so the index in a
        // plan can already point at the neighbour of the intended card by the time we draw.
        // A card the sandbox created (a Shiv) has a sandbox-local uuid, so resolve it to the LIVE
        // card first and mark that one. When nothing matches, mark nothing: drawing over a neighbour
        // is the B21 bug.
        String markUuid = step.action.cardUuid;
        if ("card".equals(step.action.kind)) {
            AbstractCard live = Gameplay.liveCardFor(step.action);
            markUuid = live == null || live.uuid == null ? "" : live.uuid.toString();
        }
        Hud.markStep(markUuid, step.action.handIndex, step.action.targetIndex,
                step.action.isEndTurn());
        Log.info("markers: step=" + Gameplay.step + " kind=" + step.action.kind + " uuid=" + step.action.cardUuid + " card=" + step.action.handIndex + " target=" + step.action.targetIndex + " label=" + step.action.label);
    }

    private static void rememberExecuted() {
        if (plan == null) {
            return;
        }
        int n = Math.min(step, Gameplay.plan.plan.size());
        for (int i = 0; i < n; ++i) {
            history.add(Gameplay.plan.plan.get(i));
        }
    }

    public static boolean replanFromCurrentState(String reason) {
        if (!Gameplay.inRoomCombat()) {
            return false;
        }
        // B40: the same guard as in maybeAutopilot, one level deeper. Searching a board whose turn is
        // still resolving produces a plan that is one turn behind the moment it lands.
        if (!Gameplay.engineSettled()) {
            Log.info("replan deferred (" + reason + "): the engine is still resolving an action");
            return false;
        }
        int liveTurn = GameActionManager.turn;
        if (plan != null) {
            Gameplay.rememberExecuted();
        }
        boolean resumeAutopilot = autopilot;
        pending = null;
        World.forceRestore();
        String cacheKey = PlanCache.key(Digest.ofCurrent(), AbstractDungeon.ascensionLevel, EscapePolicy.mode().label, origin == null ? "?" : origin.describe());
        Solver.Result cached = PlanCache.get(cacheKey);
        if (cached != null && !Gameplay.startsInLiveTurn(cached, liveTurn)) {
            Log.warn("replan rejected the cached plan: it starts in an earlier turn than " + liveTurn + "");
            cached = null;
        }
        if (cached != null) {
            plan = cached;
            planBuiltAt = now();
            step = 0;
            verified = 0;
            exhaustedReplans = 0;
            staleReplans = 0;
            autopilot = resumeAutopilot;
            status = reason + "：缓存命中（未重新搜索）";
            Gameplay.syncMarkers();
            Hud.set(Gameplay.lines());
            return true;
        }
        // B42 (user m12219): this used to be a BLOCKING 4 second search. On a board that needed a little
        // more work the search hit its TIME limit and handed back the longest survival line, which the
        // autopilot then played turn after turn -- in a fight the player knew was winnable. A replan is
        // a real search: the same budget and the same slicing as F8 (A0), and the autopilot waits for it
        // instead of guessing from four seconds.
        autopilot = resumeAutopilot;
        if (!Gameplay.startReplanSearch(reason, cacheKey)) {
            status = reason + "：重算没开始（已有搜索在跑）；可自己出牌，或点「计算」";
            Hud.set(Gameplay.lines());
            return false;
        }
        return true;
    }

    /**
     * B42: start an autopilot replan as a sliced search with the FULL budget (A0), not a 4 s guess.
     *
     * <p>Returns true when the search is running. The autopilot keeps its flag and waits; when the job
     * finishes, {@link #finishQuick()} adopts the line and words a time-limited survival line honestly
     * (a time limit is not proof the fight is lost).
     */
    static boolean startReplanSearch(String reason, String cacheKey) {
        if (deep != null || quick != null) {
            Log.info("replan search skipped (" + reason + "): a search is already running");
            return false;
        }
        try {
            String digest = Digest.ofCurrent();
            RenderState.Snap render = RenderState.capture();
            ViewState view = ViewState.capture();
            World liveRoot = World.captureLive();
            long budgetMs = quickBudget();
            boolean expensive = quickNarrowDeep();
            quickCacheKey = cacheKey;
            quickBefore = digest;
            Gameplay.beginSearchAudit(render);
            quickView = view;
            quickStartedAt = now();
            quickIsReplan = true;
            quickReason = reason;
            sts1solver.search.Solver.resetSlowSteps(); // B170
            quick = new sts1solver.search.QuickSession(liveRoot, budgetMs, expensive);
            Gameplay.clearPreview();
            Hud.markStep(-1, -1, false);
            status = reason + "：正在重算（用满预算）…";
            Hud.set(lines());
            Log.info("replan search: started, budget=" + budgetMs + "ms narrow=" + expensive + " reason=" + reason);
            return true;
        }
        catch (Throwable error) {
            World.forceRestore();
            quick = null;
            quickIsReplan = false;
            quickReason = null;
            Log.warn("replan search failed: " + error);
            return false;
        }
    }

    /** Budget the next solve (or replan) will use; the gate checks a replan uses the same number as F8. */
    public static long solveBudgetForTest() {
        return quickBudget();
    }

    /** True while an autopilot replan (not an F8 solve) is the search in flight (B42). */
    public static boolean replanSearching() {
        return quick != null && quickIsReplan;
    }

    public static void markStepsExecutedForTest(int n) {
        if (plan == null) {
            return;
        }
        step = Math.min(n, Gameplay.plan.plan.size());
    }

    public static int historySize() {
        return history.size();
    }

    public static void solveLive() {
        if (deep != null) {
            status = "深度搜索进行中：点「计算」停止";
            Hud.set(lines());
            return;
        }
        String digestAfterSearch;
        Solver.Result found;
        history.clear();
        replans = 0;
        driftReplans = 0;
        stepTarget = -1;
        autopilot = false;
        pending = null;
        lethalConfirmed = false;
        lastRisk = null;
        Hud.demoOff();
        World.forceRestore();
        origin = ContentOrigin.inspect();
        Log.info("content: " + origin.describe());
        String blocked = Gameplay.blockedReason();
        if (blocked != null) {
            status = Gameplay.refusalText(blocked);
            Hud.set(Gameplay.lines());
            Log.info("solve refused: " + blocked);
            return;
        }
        long startedAt = now();
        String digestBeforeSearch = Digest.ofCurrent();
        RenderState.Snap renderSnap = RenderState.capture();
        ViewState viewSnap = ViewState.capture();
        String cacheKey = PlanCache.key(digestBeforeSearch, AbstractDungeon.ascensionLevel, EscapePolicy.mode().label, origin == null ? "?" : origin.describe());
        Solver.Result cached = PlanCache.get(cacheKey);
        if (cached != null && !Gameplay.cacheFits(cached)) {
            // A cached plan that cannot even start in the live hand is not an answer. This is the
            // guard for a cache key that silently stopped covering a state component: a stale plan
            // would be rendered and, worse, executed.
            Log.warn("plan cache: hit rejected, does not fit the live hand | digest=" + digestBeforeSearch);
            cached = null;
        }
        if (cached != null) {
            plan = cached;
            planBuiltAt = now();
            step = 0;
            drift = 0;
            lastRisk = null;
            lethalConfirmed = false;
            Gameplay.syncMarkers();
            status = "\u7f13\u5b58\u547d\u4e2d\uff08\u672a\u91cd\u65b0\u641c\u7d22\uff09";
            Hud.set(Gameplay.lines());
            Log.info("solve: cache hit, steps=" + cached.plan.size() + " ascension="
                    + AbstractDungeon.ascensionLevel + " | digest=" + digestBeforeSearch);
            return;
        }
        try {
            World liveRoot = World.captureLive();
            boolean bossRoom = AbstractDungeon.getCurrRoom() instanceof MonsterRoomBoss;
            int aliveMonsters = 0;
            try {
                ArrayList monsters = AbstractDungeon.getMonsters().monsters;
                for (int i = 0; i < monsters.size(); ++i) {
                    AbstractMonster monster = (AbstractMonster)monsters.get(i);
                    if (monster == null || monster.currentHealth <= 0 || monster.isDying) continue;
                    ++aliveMonsters;
                }
            }
            catch (Throwable ignored) {
                // a missing monster list just means the single-monster budget
            }
            long budgetMs = bossRoom ? 8000L : (aliveMonsters >= 5 ? 12000L : (aliveMonsters >= 3 ? 8000L : (aliveMonsters >= 2 ? 5000L : 3000L)));
            // P2-2d: a phase boss (or a fight whose monsters have a lot of HP) gets ONE narrow-deep
            // pass with the whole budget. A portfolio splits the budget across members, and measured
            // on Awakened One the deep member never got enough time to pass 15 layers.
            int bossHp = 0;
            try {
                for (int i = 0; i < AbstractDungeon.getMonsters().monsters.size(); i++) {
                    AbstractMonster m = AbstractDungeon.getMonsters().monsters.get(i);
                    if (m != null && m.maxHealth > bossHp) {
                        bossHp = m.maxHealth;
                    }
                }
            } catch (Throwable ignored) {
                // a missing monster list just means the normal portfolio
            }
            boolean expensive = bossRoom || bossHp >= 200;
            found = expensive
                    ? Solver.solve(liveRoot, 10, 96, budgetMs)
                    : Solver.solvePortfolio(liveRoot, budgetMs);
            if (expensive && !found.win && !found.escaped) {
                // one wide pass with whatever is left, so a found short line is not the only answer
                Solver.Result widePass = Solver.solve(liveRoot, 40, 40, Math.max(800L, budgetMs / 3L));
                if (widePass.win && !found.win) {
                    found = widePass;
                }
            }
            PlanCache.put(cacheKey, found);
            // The digest is printed on purpose: when a verdict looks wrong the first question is
            // "was this really the position you were in", and a stored digest is the only way to
            // answer it from a log after the fight is over.
            Log.info("solve: stored plan | " + (found.win ? "win" : "no-win") + " digest="
                    + digestBeforeSearch);
            digestAfterSearch = Digest.ofCurrent();
        }
        catch (Throwable error) {
            World.forceRestore();
            status = "solve failed: " + error;
            Hud.set(Gameplay.lines());
            Log.error("solve failed", error);
            return;
        }
        if (!digestBeforeSearch.equals(digestAfterSearch)) {
            status = "solver corrupted the live fight";
            Log.error("LIVE STATE CHANGED DURING SEARCH", null);
            Hud.set(Gameplay.lines());
            return;
        }
        List<String> renderDiffs = RenderState.diff(renderSnap, RenderState.capture());
        renderRepairs = 0;
        renderRepairDetail = "";
        if (!renderDiffs.isEmpty()) {
            for (int i = 0; i < renderDiffs.size() && i < 20; i++) {
                Log.warn("RENDER STATE CHANGED BY SEARCH: " + renderDiffs.get(i));
            }
            renderRepairs = RenderState.repair(renderSnap);
            renderRepairDetail = renderDiffs.get(0);
            Log.warn("render state: " + renderDiffs.size() + " field(s) changed, " + renderRepairs + " repaired");
        }
        if (!"clean".equals(viewRestore = viewSnap.restoreIfChanged())) {
            Log.warn("UI STATE CHANGED BY SEARCH: " + viewRestore);
        }
        vfxSweep = VfxGuard.sweep();
        Cloner.EFFECT_CLONES.clear();
        if (!"clean".equals(vfxSweep)) {
            Log.warn("vfx guard after solve: " + vfxSweep);
        }
        plan = found;
        planBuiltAt = now();
        step = 0;
        drift = 0;
        lastRisk = null;
        lethalConfirmed = false;
        Gameplay.syncMarkers();
        if (!found.win && !found.escaped && ("time".equals(found.reason)
                || "nodes".equals(found.reason))) {
            status = "预算用尽，未算到胜利；可点「计算」再给一轮预算";
        } else {
            status = sts1solver.search.PlanText.headline(found);
        }
        Hud.set(Gameplay.lines());
        Log.info("solve: " + found.summary() + " ascension=" + AbstractDungeon.ascensionLevel);
        Log.info("plan : " + found.planText());
        Gameplay.logLoot(found);
        Log.info("trace: " + found.planTrace());
        Log.info("solve wall clock: " + (now() - startedAt) + "ms");
    }

    public static EndTurnDecision decideEndTurnRisk(EndTurnRisk risk, int playerMaxHp, boolean manual) {
        if (risk == null || !risk.ok) {
            return manual ? EndTurnDecision.ALLOW : EndTurnDecision.STOP;
        }
        int margin = Math.max(5, playerMaxHp / 10);
        if (risk.wouldDie) {
            return manual ? EndTurnDecision.CONFIRM : EndTurnDecision.STOP;
        }
        if (risk.hpAfter <= margin) {
            return manual ? EndTurnDecision.WARN : EndTurnDecision.STOP;
        }
        return EndTurnDecision.ALLOW;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static EndTurnRisk predictEndTurnRisk() {
        int hpBefore = Gameplay.currentHp();
        Driver.begin();
        World world = null;
        boolean installed = false;
        try {
            world = World.captureLive();
            world.install();
            installed = true;
            int incoming = Gameplay.intentDamage();
            // B159 (user m15391): B151 froze the action queue while a picker is up, so this gate
            // used to stop at the FIRST screen the end turn opened (Tang auto-playing SealGuide)
            // and report the state half way through a turn -- a number the plan never had. It now
            // replays the whole end turn with the very answers this step recorded, so it finishes
            // where the plan's step finishes. A screen the plan has no answer for is not guessed:
            // the risk comes back as unknown and the gate stops the autopilot.
            Action answers = Gameplay.endTurnStepAnswers();
            Driver.endTurn();
            Gameplay.EndTurnRisk unfinished = Gameplay.answerEndTurnScreens(answers, hpBefore, incoming);
            if (unfinished != null) {
                return unfinished;
            }
            int hpAfter = Gameplay.currentHp();
            return new EndTurnRisk(hpBefore, hpAfter, incoming, true, null);
        }
        catch (Throwable failure) {
            return new EndTurnRisk(hpBefore, hpBefore, 0, false, String.valueOf(failure));
        }
        finally {
            if (installed && world != null) {
                try {
                    world.readBack();
                    world.uninstall();
                }
                catch (Throwable ignored) {
                    // Cleanup path: a world that cannot be restored must not mask the failure that
                    // brought us here. World.forceRestore() and the startup check are the backstop.
                }
            }
            Driver.end();
        }
    }

    /** B159: the answers the plan recorded for the end-turn step at the cursor (null when none). */
    private static Action endTurnStepAnswers() {
        try {
            if (!Gameplay.hasPlan() || Gameplay.plan.plan == null
                    || Gameplay.step < 0 || Gameplay.step >= Gameplay.plan.plan.size()) {
                return null;
            }
            Solver.Step planned = Gameplay.plan.plan.get(Gameplay.step);
            return planned == null ? null : planned.action;
        }
        catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * B159: answers the pickers an end turn opens, exactly as a replay would, using the plan's
     * recorded per-screen answers. Returns null when the end turn is complete, or the reason it
     * could not be finished.
     */
    private static EndTurnRisk answerEndTurnScreens(Action answers, int hpBefore, int incoming) {
        String[] choices = Gameplay.splitChain(answers == null ? null : answers.choice);
        String[] displays = Gameplay.splitChain(answers == null ? null : answers.choiceDisplay);
        String[] uuids = Gameplay.splitChain(answers == null ? null : answers.choiceUuids);
        String[] prints = Gameplay.splitChain(answers == null ? null : answers.choicePrints);
        int answered = 0;
        while (Driver.isAwaitingSelection()) {
            if (answered >= choices.length) {
                return new EndTurnRisk(hpBefore, hpBefore, incoming, false,
                        "\u7ed3\u675f\u56de\u5408\u91cc\u5f00\u4e86\u4e00\u5757\u8ba1\u5212\u6ca1\u6709\u7b54\u6848\u7684\u9009\u724c\u5c4f\uff08"
                                + Driver.selectionState() + "\uff09");
            }
            boolean ok = Driver.resolveSelectionByNames(Gameplay.chainAt(choices, answered),
                    Gameplay.chainAt(displays, answered), Gameplay.chainAt(uuids, answered),
                    Gameplay.chainAt(prints, answered));
            if (!ok) {
                Driver.settleForReplay(16);
                if (Driver.isAwaitingSelection()) {
                    ok = Driver.resolveSelectionByNames(Gameplay.chainAt(choices, answered),
                            Gameplay.chainAt(displays, answered), Gameplay.chainAt(uuids, answered),
                            Gameplay.chainAt(prints, answered));
                }
            }
            if (!ok) {
                return new EndTurnRisk(hpBefore, hpBefore, incoming, false,
                        "\u7b2c " + (answered + 1) + " \u5757\u9009\u724c\u5c4f\u7b54\u4e0d\u8fdb\u53bb\uff1a"
                                + Driver.lastSelectionFailure());
            }
            if (++answered > 64) {
                return new EndTurnRisk(hpBefore, hpBefore, incoming, false,
                        "\u9009\u724c\u5c4f\u8d85\u8fc7 64 \u5757\u8fd8\u6ca1\u8d70\u5b8c");
            }
        }
        Driver.SelectionKind left = Driver.settleForReplay(24);
        if (left != Driver.SelectionKind.NONE) {
            return new EndTurnRisk(hpBefore, hpBefore, incoming, false,
                    "\u7b54\u5b8c\u9009\u724c\u5c4f\u540e\u4ecd\u6709\u4e00\u5757\u5c4f\u5728\u7b49\uff08"
                            + Driver.selectionState() + "\uff09");
        }
        return null;
    }

    /** B159: splits a plan's '|' separated per-screen chain ("" -> empty array). */
    private static String[] splitChain(String joined) {
        if (joined == null || joined.isEmpty()) {
            return new String[0];
        }
        return joined.split("\\|", -1);
    }

    /** B159: element {@code i} of an answer chain that may be shorter than the screen count. */
    private static String chainAt(String[] values, int i) {
        return values != null && i < values.length ? values[i] : "";
    }
    private static int intentDamage() {
        int total = 0;
        try {
            ArrayList monsters = AbstractDungeon.getMonsters().monsters;
            for (int i = 0; i < monsters.size(); ++i) {
                int damage;
                AbstractMonster monster = (AbstractMonster)monsters.get(i);
                if (monster == null || monster.isDying || monster.isDeadOrEscaped() || monster.currentHealth <= 0 || (damage = monster.getIntentDmg()) <= 0) continue;
                Field field = Reflect.find(AbstractMonster.class, "intentMultiAmt");
                Object raw = field == null ? null : Reflect.get(field, monster);
                int hits = raw instanceof Integer ? (Integer)raw : 0;
                total += damage * (hits > 0 && hits <= 15 ? hits : 1);
            }
        }
        catch (Throwable ignored) {
            // a missing monster list means no incoming damage
        }
        return total;
    }

    private static int currentHp() {
        try {
            return AbstractDungeon.player == null ? 0 : AbstractDungeon.player.currentHealth;
        }
        catch (Throwable throwable) {
            return 0;
        }
    }

    private static void stopAutopilot(String reason) {
        autopilot = false;
        autoplayArmed = false;
        nextActionAtMs = 0L;
        status = reason;
        Hud.set(Gameplay.lines());
        Log.warn("autopilot stopped: " + reason);
    }

    /**
     * B51: continue 自动出牌 through the 计算 path -- the same sliced, full-budget, cache-aware solve
     * the 计算 button runs. Returns true when the autopilot should keep waiting (a search is in
     * flight, or a cached line was adopted straight away).
     */
    public static boolean solveAgainForAutoplay(String reason) {
        if (deep != null || quick != null) {
            // A search is already running; keep the flag on it so its result keeps playing.
            autoplayArmed = autopilot;
            autoplayReason = reason;
            return true;
        }
        if (!Gameplay.inRoomCombat()) {
            return false;
        }
        if (!Gameplay.engineSettled()) {
            // Still resolving an action: searching now would produce a plan for a half-applied board
            // (B40). Try again on a later frame rather than guessing.
            return true;
        }
        autoplayReason = reason;
        Gameplay.startQuickSolve(true);
        if (quick != null) {
            autopilot = false;
            nextActionAtMs = 0L;
            status = reason + "：正在计算（和「计算」同一条路）…";
            Hud.set(Gameplay.lines());
            Log.info("autopilot: solving again through the 计算 path (" + reason + ")");
            return true;
        }
        if (Gameplay.hasPlan()) {
            return true;
        }
        autoplayArmed = false;
        return false;
    }

    public static EndTurnRisk lastEndTurnRisk() {
        return lastRisk;
    }

    public static boolean executeStep() {
        return Gameplay.executeStepInternal();
    }

    private static boolean executeStepInternal() {
        if (!Gameplay.hasPlan()) {
            status = "\u65e0\u8ba1\u5212\uff1b\u8bf7\u70b9\u300c\u8ba1\u7b97\u300d";
            Hud.set(Gameplay.lines());
            return false;
        }
        if (pending != null) {
            status = "\u4e0a\u4e00\u6b65\u8fd8\u5728\u6267\u884c\uff08\u7b49\u6e38\u620f\u63a8\u8fdb\uff09";
            Hud.set(Gameplay.lines());
            return false;
        }
        if (Gameplay.dropStalePlan()) {
            status = "计划已过期（已经进入新回合）；点「计算」重算";
            Hud.set(Gameplay.lines());
            return false;
        }
        Solver.Step step = Gameplay.step >= 0 && Gameplay.plan.plan != null
                && Gameplay.step < Gameplay.plan.plan.size()
                ? Gameplay.plan.plan.get(Gameplay.step) : null;
        String string = Gameplay.blockedReason(step == null ? null : step.action);
        if (string != null) {
            status = "\u65e0\u6cd5\u6267\u884c: " + string;
            Hud.set(Gameplay.lines());
            return false;
        }
        if (step == null) {
            status = "\u8ba1\u5212\u5df2\u7ecf\u8d70\u5b8c\uff1b\u70b9\u300c\u8ba1\u7b97\u300d\u91cd\u7b97";
            Hud.set(Gameplay.lines());
            return false;
        }
        if (step.action.isEndTurn()) {
            lastRisk = Gameplay.predictEndTurnRisk();
            int playerMaxHp = AbstractDungeon.player == null ? 80 : AbstractDungeon.player.maxHealth;
            EndTurnDecision endTurnDecision = Gameplay.decideEndTurnRisk(lastRisk, playerMaxHp, !autopilot);
            Log.info("end-turn risk: " + lastRisk.describe() + " -> " + (Object)((Object)endTurnDecision));
            if (endTurnDecision == EndTurnDecision.STOP) {
                Gameplay.stopAutopilot("\u5df2\u505c\u6b62\u81ea\u52a8\u6267\u884c\uff1a" + lastRisk.describe());
                return false;
            }
            if (endTurnDecision == EndTurnDecision.CONFIRM) {
                if (!lethalConfirmed) {
                    lethalConfirmed = true;
                    status = lastRisk.describe() + "\uff1b\u518d\u70b9\u300c\u6267\u884c\u4e00\u6b65\u300d\u5f3a\u5236\u6267\u884c";
                    Hud.set(Gameplay.lines());
                    return false;
                }
                lethalConfirmed = false;
            } else if (endTurnDecision == EndTurnDecision.WARN) {
                status = lastRisk.describe() + "\uff08\u6fd2\u6b7b\uff09";
            } else {
                lethalConfirmed = false;
            }
        }
        if (!Gameplay.submit(step)) {
            return false;
        }
        pending = step;
        pendingTurnBefore = GameActionManager.turn;
        settleReportAt = 0L;
        status = "\u6267\u884c\u4e2d: " + step.action.label + "\uff08\u7b49\u6e38\u620f\u63a8\u8fdb\uff09";
        Hud.set(Gameplay.lines());
        return true;
    }

    private static boolean submit(Solver.Step step) {
        // B147 (user m12327): remember where the play trace stood, so the cards this step actually
        // played -- the player's own and whatever the fight plays by itself -- can be read back and
        // compared with the sandbox's side of the same step.
        stepTraceMark = sts1solver.mirror.PlayTrace.mark();
        // m16202-5: remember what this step expects from a screen. Some cards and potions queue an
        // action that opens the screen a frame or two later; the window keeps that answer available
        // even after this step's own pending has been resolved.
        // B190 (user m01149): a step whose plan answered a screen with NOTHING still has to keep
        // that answer alive -- Action.screenKinds says a screen was answered even when choice is "".
        if (step != null && step.action != null
                && ((step.action.choice != null && !step.action.choice.isEmpty())
                    || Action.chainCount(step.action.screenKinds) > 0)) {
            Gameplay.planPendingChoice(step.action);
        }
        try {
            Action action = step.action;
            // B204 (user m01642): this IS the live game. Steps replayed in a sandbox stay false.
            action.replayLive = true;
            if (action.isSelection()) {
                // B162 (user m16041): answering the screen IS the step (the search planned it from a
                // root where the screen was already open). Live clicks only: the sandbox path is
                // Legal.apply -> Driver.resolveSelectionByNames.
                // B163 (user m16041): a reorder answer moves the named cards to the end of the hand
                // first, which is exactly what clicking select-then-deselect does on the real screen.
                // B167 (user m18895): answer it THROUGH the chain, not around it -- see
                // answerSelectionStep.
                boolean answered = Gameplay.answerSelectionStep(action);
                if (!answered) {
                    Gameplay.fail("计划里的选牌答案答不进去（" + Driver.lastSelectionFailure() + "）");
                    return false;
                }
                Log.info("step selection: answered " + Driver.selectionKind() + " with [" + action.choice + "]");
                lastActionAtMs = now();
                return true;
            }
            if (action.isEndTurn()) {
                // B142 (user m10519): END THE TURN WITH THE BUTTON, not with the latch. The latch
                // alone skips EndTurnButton.disable(true), which is the only place the
                // NewQueueCardAction that makes callEndOfTurnActions() run comes from -- so Burn and
                // Decay never got played, end-of-turn relics/orbs never fired, and the sandbox (which
                // has queued that sentinel since B114) predicted a different hand than the game had.
                boolean pressed = Driver.pressEndTurnButton();
                if (!pressed) {
                    Log.warn("end turn: no End Turn button in this world; used the button's effects");
                }
                lastActionAtMs = now();
                return true;
            }
            if (action.isCard()) {
                AbstractCard card = Gameplay.findHandCard(action);
                if (card == null) {
                    // B124 (user m03444): the search's own simulation recorded which cards the fight
                    // played by itself during this step (唐 plays up to its stack count every turn).
                    // When the plan step names one of them, the fight already carried the step out;
                    // stepping over it keeps the line running instead of stopping the autopilot on a
                    // card that is gone because it was played. No card name is special-cased here --
                    // the answer comes from the simulation of THIS plan.
                    if (action.autoPlays != null && action.autoPlays.contains(cardNameOf(action))) {
                        Gameplay.skipAutoPlayed(step);
                        return false;
                    }
                    Gameplay.fail("计划里的「" + cardNameOf(action) + "」不在手上了");
                    return false;
                }
                AbstractMonster target = null;
                if (action.targetIndex >= 0) {
                    ArrayList monsters = AbstractDungeon.getMonsters().monsters;
                    if (action.targetIndex >= monsters.size() || monsters.get(action.targetIndex) == null) {
                        Gameplay.fail("\u76ee\u6807\u5df2\u6d88\u5931\uff08\u7b2c " + (action.targetIndex + 1) + " \u53ea\u602a\uff09");
                        return false;
                    }
                    target = (AbstractMonster)monsters.get(action.targetIndex);
                }
                if (!Driver.playerCanUse(card, AbstractDungeon.player, target)) {
                    Gameplay.refuseCard("\u6e38\u620f\u62d2\u7edd\u6253\u51fa " + card.name
                            + "\uff08" + Driver.refusalReason(card) + "\uff09");
                    return false;
                }
                card.energyOnUse = action.energyOnUse;
                AbstractDungeon.actionManager.addCardQueueItem(new CardQueueItem(card, target, action.energyOnUse));
                lastActionAtMs = now();
                return true;
            }
            boolean drank = Gameplay.submitPotion(action.potionSlot, action.targetIndex);
            if (drank) {
                lastActionAtMs = now();
            }
            return drank;
        }
        catch (Throwable error) {
            Gameplay.fail("\u63d0\u4ea4\u52a8\u4f5c\u5931\u8d25: " + error);
            return false;
        }
    }

    /** Probe hook (S109): the live potion branch, exactly as autopilot would run it. */
    public static boolean submitPotionForTest(int slot, int targetIndex) {
        return Gameplay.submitPotion(slot, targetIndex);
    }

    /**
     * One potion, the way a click on the pop-up does it: effect, relic pass, consumption.
     *
     * <p>Using the potion alone left the bottle in the bar (m17821): the effect happened but the
     * bar still showed the potion, so the plan and the fight disagreed and the player could drink
     * the same potion again. {@link sts1solver.drive.PotionDrink} mirrors the pop-up branches.
     */
    private static boolean submitPotion(int potionSlot, int targetIndex) {
        ArrayList potions = AbstractDungeon.player.potions;
        if (potionSlot < 0 || potionSlot >= potions.size() || potions.get(potionSlot) == null) {
            Gameplay.fail("计划里的药水不在药水栏里了");
            return false;
        }
        AbstractPotion potion = (AbstractPotion)potions.get(potionSlot);
        // The real pop-up checks canUse() before it accepts the click, and using a potion from
        // code skips that pop-up (PotionBook). Refuse instead of drinking something the player
        // could not drink: Fairy in a Bottle would heal 30% in the middle of a fight.
        if (!sts1solver.search.PotionBook.usable(potion)) {
            Gameplay.fail("\u6e38\u620f\u4e0d\u5141\u8bb8\u559d\u8fd9\u74f6\u836f\u6c34\uff08" + potion.name + "\uff09");
            return false;
        }
        try {
            AbstractMonster target = null;
            if (targetIndex >= 0 && AbstractDungeon.getMonsters() != null
                    && targetIndex < AbstractDungeon.getMonsters().monsters.size()) {
                target = (AbstractMonster)AbstractDungeon.getMonsters().monsters.get(targetIndex);
            }
            sts1solver.drive.PotionDrink.drink(potionSlot, potion, target, true);
            return true;
        }
        catch (Throwable error) {
            Gameplay.fail("\u559d\u836f\u6c34\u5931\u8d25: " + error);
            return false;
        }
    }

    /**
     * The card an action means: by uuid first, then by card identity, index only as a last resort.
     *
     * <p>Playing by raw position is how the wrong card could be played (and highlighted) after the
     * hand shifted; a plan that cannot be matched to a live card is refused instead.
     *
     * <p>The identity fallback exists because a card the SANDBOX created can never carry the live
     * uuid: Blade Dance builds its Shivs inside the clone, so the plan names a uuid the live fight
     * has never seen even though the same Shiv is sitting in the real hand. Refusing those plays
     * stopped autopilot mid-turn, replanned the whole fight and looked like a stutter. Matching the
     * card ID + upgrade state is still identity, not position: a different card is never played.
     */
    private static AbstractCard findHandCard(Action action) {
        AbstractCard found = liveCardFor(action);
        if (found == null && action.cardUuid != null && !action.cardUuid.isEmpty()) {
            Log.warn("execute: card uuid " + action.cardUuid + " (" + action.cardName
                    + ") is no longer in hand; refusing instead of playing a neighbour");
        }
        return found;
    }

    /**
     * uuid, else the STATE the plan recorded (B98), else the same card ID + upgrade flags, else the
     * recorded hand index.
     *
     * <p>A uuid only exists inside the fork that made it, and a card id merges every copy: two
     * Strikes in hand are one id, so an id match used to take "a" Strike and hope. The print is what
     * both engines can see of the very card the search played, so it decides before the id does, and
     * when the hand holds several copies of that id and none carries the planned state this refuses
     * (null) instead of playing a card the plan never chose.
     */
    public static AbstractCard liveCardFor(Action action) {
        ArrayList<AbstractCard> hand = AbstractDungeon.player.hand.group;
        if (action.cardUuid != null && !action.cardUuid.isEmpty()) {
            for (int i = 0; i < hand.size(); ++i) {
                AbstractCard c = (AbstractCard)hand.get(i);
                if (c != null && c.uuid != null && action.cardUuid.equals(c.uuid.toString())) {
                    return c;
                }
            }
            if (action.cardPrint != null && !action.cardPrint.isEmpty()) {
                for (int i = 0; i < hand.size(); ++i) {
                    AbstractCard c = (AbstractCard)hand.get(i);
                    if (c != null && action.cardPrint.equals(sts1solver.mirror.CardPrint.of(c))) {
                        Log.info("execute: uuid " + action.cardUuid + " is sandbox-local; matched "
                                + action.cardName + " by the state the plan recorded (cost="
                                + c.costForTurn + ")");
                        return c;
                    }
                }
                int copies = 0;
                for (int i = 0; i < hand.size(); ++i) {
                    AbstractCard c = (AbstractCard)hand.get(i);
                    if (c != null && action.cardId != null && action.cardId.equals(c.cardID)) {
                        ++copies;
                    }
                }
                if (copies > 1) {
                    // Several copies of that id, none with the planned state: whatever we picked here
                    // would be a different play than the one the plan scored (B98).
                    Log.warn("execute: " + copies + " copies of " + action.cardId
                            + " in hand and none carries the planned state (" + action.cardPrint
                            + "); refusing instead of playing a neighbour");
                    return null;
                }
            }
            AbstractCard same = Gameplay.pickSameCard(action);
            if (same != null) {
                Log.info("execute: uuid " + action.cardUuid + " is sandbox-local; matched "
                        + action.cardName + " by card id (cost=" + same.costForTurn + ")");
            }
            return same;
        }
        if (action.handIndex >= 0 && action.handIndex < hand.size()) {
            return (AbstractCard)hand.get(action.handIndex);
        }
        return null;
    }

    /**
     * B167 (user m18895): can a cached plan that STARTS by answering a screen still be followed?
     *
     * <p>The old gate asked {@link #liveCardFor} for the first action, and that only searches the
     * HAND. A picker answer is chosen from the screen's own candidates and carries no hand index, so
     * every plan that opened on a screen (a Gambling Chip at the start of a turn, a relic that asks
     * before the first action) was refused and searched again -- eight times in the 2026-11-08 log,
     * each time producing the same 19 steps.
     *
     * <p>No card name and no mod is special-cased: the test is "the screen this answer was written
     * for is the one that is open, and every card it names is still offered". With the screen gone,
     * or the cards not among the offers, the cache is refused exactly as before.
     */
    private static boolean selectionAnswerFits(Action action) {
        Driver.SelectionKind open = Driver.selectionKind();
        if (open == null || open == Driver.SelectionKind.NONE) {
            return false;
        }
        String planned = Gameplay.firstSegment(action.screenKinds);
        if (planned.isEmpty()) {
            planned = action.screenKind == null ? "" : action.screenKind;
        }
        if (!planned.isEmpty() && !planned.equals(open.name())) {
            return false;
        }
        String ids = Gameplay.firstSegment(action.choice);
        if (ids.isEmpty()) {
            // "答选牌（不选）": legal exactly when the planned screen is the one that is open.
            return true;
        }
        // B204: the plan names a card as "id@STATE#k", where k is the copy's position on the screen
        // the search walked. That position is only meaningful while the live screen presents the
        // same distinct cards in the same order, so the answer is re-expressed as plain ids for the
        // live enumeration -- a plan whose positions no longer line up is refused instead of
        // answered with a shifted card.
        String idsNow = sts1solver.search.Solver.positionsOn(ids,
                sts1solver.search.Solver.positionedIds(Driver.selectionCandidates()),
                Gameplay.firstSegment(action.screenOffered));
        if (idsNow == null) {
            return false;
        }
        java.util.List<com.megacrit.cardcrawl.cards.AbstractCard> offered = Driver.selectionCandidates();
        if (offered.isEmpty()) {
            return false;
        }
        java.util.List<com.megacrit.cardcrawl.cards.AbstractCard> chosen = Driver.picksFor(offered, idsNow,
                Gameplay.firstSegment(action.choiceDisplay),
                Gameplay.firstSegment(action.choiceUuids),
                Gameplay.firstSegment(action.choicePrints));
        // Every named card has to be offered: a stale plan that names three cards while the screen
        // shows one would answer a different question than the one it scored.
        return chosen.size() == idsNow.split(",").length;
    }

    /** The first '|' separated entry of a planned answer chain, or "" (B86/B167). */
    private static String firstSegment(String joined) {
        if (joined == null || joined.isEmpty()) {
            return "";
        }
        int cut = joined.indexOf('|');
        return (cut < 0 ? joined : joined.substring(0, cut)).trim();
    }

    /** Can this cached plan still start here? (turn, and a live card for its first step) */
    private static boolean cacheFits(Solver.Result cached) {
        if (cached == null || cached.plan == null || cached.plan.isEmpty()) {
            return false;
        }
        Solver.Step first = cached.plan.get(0);
        if (first == null || first.action == null) {
            return false;
        }
        if (first.turn != com.megacrit.cardcrawl.actions.GameActionManager.turn) {
            return false;
        }
        if (first.action.isSelection()) {
            // B167 (user m18895): the first step ANSWERS a screen, so there is no hand card to find.
            return Gameplay.selectionAnswerFits(first.action);
        }
        if (first.action.isEndTurn()) {
            return true;
        }
        if ("potion".equals(first.action.kind)) {
            return AbstractDungeon.player != null && AbstractDungeon.player.potions != null
                    && first.action.potionSlot >= 0
                    && first.action.potionSlot < AbstractDungeon.player.potions.size();
        }
        return Gameplay.liveCardFor(first.action) != null;
    }
    /**
     * m21115-1: the same card ID can sit in hand twice with different costs. Dead Branch adds a
     * random card whenever a card is exhausted, and Infernal Blade adds an attack that costs 0 for
     * the turn -- so a fight can hold a free 恶魔之焰 and a 2-cost one at once. Matching by card ID
     * alone took the FIRST copy, which was the expensive one, and the live game refused the play
     * ("游戏拒绝打出 恶魔之焰") while the sandbox, which had picked the free copy, expected it to
     * work. Pick the copy the game would actually accept, then the cheapest one.
     */
    private static AbstractCard pickSameCard(Action action) {
        if (AbstractDungeon.player == null || AbstractDungeon.player.hand == null) {
            return null;
        }
        ArrayList<AbstractCard> hand = AbstractDungeon.player.hand.group;
        AbstractMonster target = Gameplay.monsterAt(action.targetIndex);
        AbstractCard first = null;
        AbstractCard usable = null;
        AbstractCard cheapest = null;
        int copies = 0;
        for (int i = 0; i < hand.size(); ++i) {
            AbstractCard c = (AbstractCard)hand.get(i);
            if (!Gameplay.sameCard(c, action)) {
                continue;
            }
            ++copies;
            if (first == null) {
                first = c;
            }
            if (usable == null && Driver.canUse(c, AbstractDungeon.player, target)) {
                usable = c;
            }
            if (cheapest == null || c.costForTurn < cheapest.costForTurn) {
                cheapest = c;
            }
        }
        AbstractCard pick = usable != null ? usable : (cheapest != null ? cheapest : first);
        if (copies > 1 && pick != null) {
            Log.info("execute: " + copies + " copies of " + pick.name + " in hand; playing the one"
                    + " that costs " + pick.costForTurn + (usable == null ? " (none of them is usable)" : ""));
        }
        return pick;
    }

    /** The monster an action points at, or null. */
    private static AbstractMonster monsterAt(int targetIndex) {
        if (targetIndex < 0 || AbstractDungeon.getMonsters() == null) {
            return null;
        }
        ArrayList monsters = AbstractDungeon.getMonsters().monsters;
        if (targetIndex >= monsters.size()) {
            return null;
        }
        return (AbstractMonster)monsters.get(targetIndex);
    }

    /** Same card, not same instance: the ID must agree and so must the name ("+" = upgraded). */
    private static boolean sameCard(AbstractCard c, Action action) {
        if (c == null || action == null) {
            return false;
        }
        if (action.cardId != null && !action.cardId.isEmpty() && !action.cardId.equals(c.cardID)) {
            return false;
        }
        if (action.cardName == null || action.cardName.isEmpty()) {
            return true;
        }
        return action.cardName.equals(c.name);
    }

    /**
     * m21115-1: the live game would not take this card.
     *
     * <p>A refusal means the plan disagrees with this board about THIS card (a second copy with a
     * different cost, an empty energy bar, a curse that blocks it), not that the fight is lost.
     * Wiping the plan here is what turned "the game said no" into "路线已走完（不代表打不过）" two
     * frames later: keep the plan, stop the autopilot, and say why in the panel.
     */
    private static void refuseCard(String message) {
        Log.warn("execute: " + message);
        String why = message + "；自动出牌已暂停，处理好后点「自动」继续";
        if (autopilot) {
            Gameplay.stopAutopilot(why);
            return;
        }
        status = why;
        Hud.set(Gameplay.lines());
    }

    /**
     * B77 (m21571): "I cannot play this one right now" -- NOT "the plan is wrong".
     *
     * <p>STS2 deploys the planned action and pauses when the card or the potion it names is not
     * there; the plan survives. This method used to wipe the plan, so every missing card turned into
     * "需重算" a few frames later and the player had to press 计算 again. Now it clears only the
     * pending action, pauses the autopilot and keeps the plan, so pressing 「自动」 resumes it.
     */
    private static void fail(String message) {
        ++drift;
        pending = null;
        Hud.markStep(-1, -1, false);
        Log.warn("execute: " + message);
        String why = message + "；计划已保留，处理好后点「自动」继续";
        if (autopilot) {
            Gameplay.stopAutopilot(why);
            return;
        }
        status = why;
        Hud.set(Gameplay.lines());
    }

    /**
     * B124 (user m03444): the step's card was played by the fight itself, so the step is done.
     *
     * <p>This is NOT the "the plan broke" case that {@link #fail(String)} handles. The card left the
     * hand because the fight played it while this step ran, which is exactly what the step wanted;
     * stopping there made the player press 自动 again for nothing. The evidence comes from the
     * search's own simulation of this plan ({@link Action#autoPlays}), so the same rule covers 唐
     * and any other effect that queues a play -- nothing here knows about Tang. The step is advanced
     * and the next frame carries on with the rest of the line, and the panel shows those plays on
     * the step that caused them.
     */
    private static void skipAutoPlayed(Solver.Step step) {
        int skipped = Gameplay.step + 1;
        ++Gameplay.step;
        pending = null;
        String why = "计划里的「" + cardNameOf(step.action) + "」已由自动打出完成（跳过第 "
                + skipped + " 步，接着执行下一步）";
        Log.info("execute: " + why);
        status = why;
        Hud.set(Gameplay.lines());
    }

    /**
     * B77 (m21571): a step that came out differently than predicted is a note, not a verdict.
     *
     * <p>The route stays frozen (m16202-2: no silent recompute) and the remaining steps keep being
     * deployed. If the difference really broke the plan, the next step needs a card that is not in
     * hand and {@link #fail(String)} pauses there -- which is exactly the STS2 rule the player asked
     * for: keep playing the plan, stop only when the card or the potion is gone.
     */
    private static void noteDriftAndContinue(Solver.Step step, String actual, String livePlays,
                                             String liveRng) {
        ++drift;
        boolean rngSame = sts1solver.mirror.RngStamp.same(step.rng);
        // Empty on either side means "no evidence" (a hand-built step, an old plan, a test hook):
        // absence is not a difference, and guessing would stop the autopilot on a plan it cannot
        // actually check.
        boolean playsSame = step.plays == null || step.plays.isEmpty() || livePlays == null
                || livePlays.isEmpty() || step.plays.equals(livePlays);
        Log.warn("step " + (Gameplay.step + 1) + " came out differently than predicted ("
                + step.action.label + "); the plan keeps running");
        Log.warn("  expected " + step.digest);
        Log.warn("  actual   " + actual);
        // B147 (user m12327): the board alone cannot say WHERE the two worlds parted. The play trace
        // and the random position can: the first play that differs, and the counter each side rolled
        // at, is the step (and often the exact card) to look at. A board difference with the SAME
        // random position and the SAME plays is an effect difference, not a random-path difference.
        Log.warn("  plays sandbox " + step.plays);
        Log.warn("  plays live    " + livePlays);
        Log.warn("  rng   sandbox " + step.rng);
        Log.warn("  rng   live    " + liveRng + (rngSame ? "  (same)" : "  (DIFFERS)"));
        Gameplay.rememberExecuted();
        // B148 (user m12610): this reports the first play that differs; it does NOT recompute. The
        // fix has to be that the plan is right from the start, not that a wrong one is noticed.
        if (!playsSame) {
            Log.warn("  the first play that differs is the step to look at; the plan keeps running");
        }
    }

    /**
     * B124 (user m03444): "（自动：防御、打击）" -- the cards the fight played while this step ran.
     *
     * <p>Empty for the overwhelming majority of steps. The panel shows them on the step that caused
     * them, so a player comparing the plan with the fight sees the two 防御 that 唐 played instead of
     * a plan that looks one card short.
     */
    private static String autoPlaySuffix(Action action) {
        if (action == null || action.autoPlays == null || action.autoPlays.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("（自动：");
        for (int i = 0; i < action.autoPlays.size(); ++i) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(action.autoPlays.get(i));
        }
        return sb.append("）").toString();
    }

    /** The card an action names, for a message the player can act on ("铁斩波+"). */
    private static String cardNameOf(Action action) {
        if (action == null) {
            return "?";
        }
        if (action.cardName != null && !action.cardName.isEmpty()) {
            return action.cardName;
        }
        return action.label == null ? "?" : action.label;
    }

    /** The plan's answer to a selection screen, written for the panel ("防御+"), or "". */
    private static String choiceText(Solver.Step step) {
        if (step == null || step.action == null || step.action.choice == null
                || step.action.choice.isEmpty()) {
            return "";
        }
        return step.action.choiceDisplay == null || step.action.choiceDisplay.isEmpty()
                ? step.action.choice : step.action.choiceDisplay;
    }

    /**
     * m16202-5: answer a selection screen that opened AFTER its step was already checked.
     *
     * <p>Purely additive: it only fires while autopilot is on, only inside the few seconds after a
     * step that carried a planned answer, and only with that planned answer -- never with a guess.
     * When it has nothing to answer it logs the open screen once, so the next report ("it would not
     * pick from the discard pile") carries the screen's actual candidates.
     */
    /** One screen's worth of a planned answer chain, split on '|' (B86/P2-57). */
    private static String[] choiceSegments(String joined) {
        if (joined == null || joined.isEmpty()) {
            return new String[0];
        }
        return joined.split("\\|", -1);
    }

    private static String segmentAt(String[] values, int i) {
        return values != null && i >= 0 && i < values.length ? values[i] : "";
    }

    private static int choiceScreenCount() {
        return Math.max(choiceSegments(pendingChoice).length, pendingScreens);
    }

    /**
     * Answers the CURRENT screen with the next recorded answer and steps the chain forward.
     *
     * <p>Returns false only when the engine refused the answer, in which case nothing moved and the
     * caller can retry inside its settle window.
     */
    private static boolean answerNextScreen() {
        int screens = Gameplay.choiceScreenCount();
        if (screens == 0) {
            return false;
        }
        String[] choices = choiceSegments(pendingChoice);
        String[] kinds = choiceSegments(pendingChoiceKinds);
        String[] displays = choiceSegments(pendingChoiceDisplay);
        String[] uuids = choiceSegments(pendingChoiceUuids);
        String[] prints = choiceSegments(pendingChoicePrints);
        String[] moved = choiceSegments(pendingChoiceMoved);
        String[] movedUuids = choiceSegments(pendingChoiceMovedUuids);
        String[] offered = choiceSegments(pendingChoiceOffered);
        int at = Math.min(pendingChoiceAt, screens - 1);
        // B203 (user m01393): was the card this answer names ON the screen the sandbox walked? The
        // live screen is asked the same question below; a "no" on either side is the evidence that
        // the two walks are on different screens, and it is printed before any click.
        String want = segmentAt(prints, at);
        if (!want.isEmpty()) {
            Log.info("screen " + (at + 1) + "/" + screens + " answer=[" + segmentAt(choices, at)
                    + "] planState=[" + want + "]"
                    + (offered.length > at && !offered[at].isEmpty()
                            ? " sandboxOffered=[" + brief(offered[at]) + "]" : " sandboxOffered=-")
                    + " liveOffered=[" + brief(Solver.stateRun(Driver.selectionCandidates())) + "]");
        }
        String ids = segmentAt(choices, at);
        String kindAt = segmentAt(kinds, at);
        boolean ok;
        boolean screenRead = ids.trim().isEmpty() && kindAt.isEmpty();
        if (screenRead) {
            // B203 (user m01393): the plan recorded this screen but not what it picked on it -- the ONE
            // click the search still writes blindly, so there is nothing to replay here. Instead of a
            // guess, take the click expandScreens makes: the first option of the screen, in the same
            // enumeration order the search used. Driver refuses a screen with no option at all.
            ok = Driver.resolveSelectionLiveFirst(kindAt);
        } else if (ids.trim().isEmpty()) {
            // B190 (user m01149): the plan answered this screen with nothing -- pick nothing and
            // press 确定. Driver refuses it unless the picker itself allows zero picks; it never
            // guesses a card instead.
            ok = Driver.resolveSelectionLiveEmpty(kindAt);
        } else {
            // B204 (user m01614): the recorded answer carries "id@STATE#k" -- which copy of a card,
            // not just which card. Re-express it against the LIVE enumeration: same distinct cards in
            // the same order means the k still names the same copy; anything else is refused (a
            // refused answer stops autopilot and keeps the plan), never silently re-pointed.
            // "not-offered: …" (B204) means the screen is up but its enumerations do not line up, so
            // there is nothing to lose by waiting: refuse, and let the caller's settle window retry
            // (a screen the engine is still filling has one copy back, then two -- exactly the case
            // the search never sees, because the sandbox only reads a screen once it is complete).
            // Any other "!" is a real answer to a screen that IS complete: the step must stop.
            // The screen surfaced for comparison are STATE runs (Solver.stateRun), not card for card:
            // on the live side the cards come out of selectionCandidates(), on the planned side the
            // sandbox recorded the same list when it walked the screen.
            String firstRead = "";
            String walked = offered.length > at ? offered[at] : "";
            String liveStates = Solver.stateRun(Driver.selectionCandidates());
            String idsNow = Solver.positionsOn(ids, Solver.positionedIds(Driver.selectionCandidates()),
                    walked, liveStates, walked);
            for (int attempt = 0; idsNow == null && attempt < 3; attempt++) {
                if (Driver.settleForReplay(8) == Driver.SelectionKind.NONE) {
                    break;
                }
                liveStates = Solver.stateRun(Driver.selectionCandidates());
                idsNow = Solver.positionsOn(ids, Solver.positionedIds(Driver.selectionCandidates()),
                        walked, liveStates, walked);
                if (firstRead.isEmpty() && Driver.lastSelectionFailure().startsWith("not-offered: ")) {
                    firstRead = Driver.lastSelectionFailure();
                }
            }
            if (idsNow == null) {
                Driver.refuseNow("not-offered", "the plan names [" + ids
                        + "] but the live screen offers different cards in another order ("
                        + brief(Solver.positionedIds(Driver.selectionCandidates())) + ")"
                        + (firstRead.isEmpty() ? "" : "; first read said " + firstRead));
                ok = false;
            } else {
                ok = Driver.resolveSelectionLive(idsNow, segmentAt(displays, at),
                        segmentAt(uuids, at), segmentAt(prints, at), segmentAt(moved, at),
                        segmentAt(movedUuids, at));
            }
        }
        if (ok) {
            if (pendingChoiceAt + 1 < screens) {
                pendingChoiceAt++;
                // The next screen of the same action opens its own frames later; the answer stays
                // alive (B158: no expiry clock) so it is still there when it does.
            } else {
                Gameplay.clearPendingChoice();
            }
        }
        return ok;
    }

    /**
     * B167 (user m18895): remember a step's planned answer as a chain, reorder half included.
     *
     * <p>One place, so {@code submit} and the S226 gate cannot drift apart: these fields are exactly
     * what {@link #answerNextScreen()} walks.
     */
    private static void planPendingChoice(Action action) {
        pendingChoice = action.choice == null ? "" : action.choice;
        pendingChoiceDisplay = action.choiceDisplay == null ? "" : action.choiceDisplay;
        pendingChoiceUuids = action.choiceUuids == null ? "" : action.choiceUuids;
        pendingChoicePrints = action.choicePrints == null ? "" : action.choicePrints;
        pendingChoiceMoved = action.moved == null ? "" : action.moved;
        pendingChoiceMovedUuids = action.movedUuids == null ? "" : action.movedUuids;
        // B190: the screens this step answered, including the ones it answered with nothing.
        pendingChoiceKinds = action.screenKinds == null ? "" : action.screenKinds;
        // B203: what the sandbox saw on those screens, compared with what the live screen offers.
        pendingChoiceOffered = action.screenOffered == null ? "" : action.screenOffered;
        pendingScreens = Math.max(Action.chainCount(action.choice),
                Action.chainCount(pendingChoiceKinds));
        pendingChoiceAt = 0;
    }

    /** B167: no answer is waiting any more (its screens consumed it, or the fight changed). */
    private static void clearPendingChoice() {
        pendingChoice = "";
        pendingChoiceDisplay = "";
        pendingChoiceUuids = "";
        pendingChoicePrints = "";
        pendingChoiceMoved = "";
        pendingChoiceMovedUuids = "";
        pendingChoiceKinds = "";
        pendingChoiceOffered = "";
        pendingScreens = 0;
        pendingChoiceAt = 0;
    }

    /**
     * B167 (user m18895): answer the screen a selection STEP is for, through the same chain
     * bookkeeping every later screen of the same action uses.
     *
     * <p>{@code submit} used to call {@link Driver#resolveSelectionLive} directly. That answered the
     * screen but never told the chain it had been consumed, so {@code pendingChoiceAt} stayed 0 and
     * the answer stayed pending. Two things followed, both seen in the 2026-11-08 log: every later
     * step warned "the plan recorded N selection answers for this step but the fight opened 0; the
     * rest were never used", and a still-pending answer could be handed to an unrelated screen that
     * opened later (the autopilot path answers whatever is pending).
     */
    private static boolean answerSelectionStep(Action action) {
        boolean nothing = action.choice == null || action.choice.trim().isEmpty();
        if (nothing && Gameplay.choiceScreenCount() == 0) {
            // "答选牌（不选）" (B163/B190): choosing NOTHING is a legal answer to an "up to N" picker.
            // It goes through the explicit 确定 path: the old call treated an empty id list as "no
            // answer given" and fell back to picking the best card, which is a guess (B158).
            return Driver.resolveSelectionLiveEmpty(action.screenKind);
        }
        return Gameplay.answerNextScreen();
    }

    /**
     * B203: a comma separated state list with a ",+N" tail, so a twenty-card screen cannot turn one
     * log line into twenty.
     */
    private static String brief(String states) {
        if (states == null || states.isEmpty()) {
            return "";
        }
        String[] all = states.split(",", -1);
        int max = 10;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.length && i < max; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(all[i]);
        }
        if (all.length > max) {
            sb.append(",+").append(all.length - max);
        }
        return sb.toString();
    }

    /** "防御 → 打击（2/2）" -- what the answered screen(s) look like in the panel. */
    private static String choiceSegmentText() {
        String[] displays = choiceSegments(pendingChoiceDisplay);
        String[] choices = choiceSegments(pendingChoice);
        int total = Math.max(choices.length, pendingScreens);
        String text = "";
        for (int i = 0; i < total; i++) {
            if (i > 0) {
                text = text + " → ";
            }
            String one = i < displays.length && !displays[i].isEmpty() ? displays[i] : segmentAt(choices, i);
            // B190: "no card picked, 确定 pressed" reads as 不选 in the panel, not as an empty string.
            text = text + (one.isEmpty() ? "不选" : one);
        }
        return text;
    }

    /**
     * B202 (user m01245): the pick screen is answered by the autopilot whenever it is open -- not
     * only between two steps.
     *
     * <p>Before this, the answer only ran when {@code pending == null}, i.e. after the step that
     * opened the screen had already been checked. A potion or an end turn runs to its picker INSIDE
     * the engine's own chain, so its screen was up while the step was still pending and nothing
     * touched it: the autopilot stopped with "\u8fd9\u4e00\u5c4f\u8ba1\u5212\u91cc\u6ca1\u6709\u7b54\u6848".
     *
     * <p>B202 correction (user m01364): the WALK of a screen belongs to the side that walks it. A
     * plan that HAS screens recorded answers them itself; the search enumerated the options in the
     * sandbox and took the first one, so the plan and the board stay one mechanism. Only where the
     * plan recorded no screen for this step -- the sandbox never saw a picker there (a potion the
     * search drank without one, a chain that came out longer) -- does the screen's own options
     * decide, because no plan answer exists to be wrong.
     *
     * <p>An EMPTY answer is not a missing answer: "answer the pick with nothing" is a chain entry
     * the search wrote (it picked nothing), and asking for a card there changes what the pick does.
     * Cards are never fitted to a screen: the planned cards must be offered as planned, or the
     * step reports the mismatch (P2-15/B49). The click happens one autoplay beat after the screen
     * appeared ({@link #selectionBeatDue()}), so it reads like a player, not like a frame.
     */
    private static void answerLatentSelection() {
        try {
            Driver.SelectionKind kind = Driver.selectionKind();
            boolean open = Driver.isAwaitingSelection()
                    || kind == Driver.SelectionKind.REWARD;
            if (!open) {
                latentLogged = "";
                return;
            }
            if (pending != null && Driver.settleBlock().isEmpty()) {
                // The step still has the engine's queue to itself; its own answer path runs first.
                return;
            }
            if (!autopilot) {
                return;
            }
            if (!Gameplay.selectionBeatDue()) {
                return;
            }
            // B202 (user m01364): the plan walks the screens wherever it recorded any -- an empty
            // recorded answer included. Answering those here would make the live fight take a
            // branch the plan never chose.
            String planned = pendingChoice == null ? "" : pendingChoice;
            boolean planWalksScreens = (pending != null && Gameplay.plannedScreens() > 0)
                    || Gameplay.choiceScreenCount() > 0;
            if (planWalksScreens) {
                boolean ok = answerNextScreen();
                if (ok) {
                    status = "\u9009\u724c\uff1a" + choiceSegmentText();
                    Hud.set(Gameplay.lines());
                    Log.info("latent selection answered with [" + choiceSegmentText() + "]"
                            + " (the plan's own answer; the screen opened inside a running step)");
                } else {
                    Log.warn("latent selection: could not answer " + planned
                            + " (kind=" + Driver.selectionKind() + "; " + Driver.lastSelectionFailure() + ")");
                }
                return;
            }
            // B202 (user m01364) + B203 (user m01393): the plan recorded no screen for this step at all
            // (a potion the search drank without seeing a picker, a chain that came out longer live).
            // There is no answer to be wrong, so the click is the one the search makes on every screen
            // it walks: the first option. NOT the best card -- that is a rule the search never applied.
            if (Driver.resolveSelectionLiveFirst(Driver.selectionKind() == null
                    ? "" : Driver.selectionKind().name())) {
                status = "\u9009\u724c\uff1a" + choiceSegmentText();
                Hud.set(Gameplay.lines());
                Log.info("latent selection: the plan recorded no screen for this step; took the first option"
                        + " (the same click the search makes on every screen it walks)");
                return;
            }
            Log.warn("latent selection: the open screen could not be answered ("
                    + Driver.lastSelectionFailure() + ")");
        } catch (Throwable t) {
            Log.warn("latent selection: " + t);
        }
    }

    /** B126: the turn the sandbox says this pending step ends in (-1 when it never measured). */
    private static int expectedStepEnd() {
        if (pending == null) {
            return GameActionManager.turn;
        }
        return pending.turnAfter >= 0 ? pending.turnAfter : pending.turn + 1;
    }

    /**
     * B126 (user m04653): say what the panel is waiting for while a step is pending.
     *
     * <p>The wait is rate limited (first line 2 s in, then one every 2 s) and only exists while a
     * step is unresolved, so it costs nothing in the settled case.
     */
    private static void reportSettleWait(long now) {
        if (settleReportAt != 0L && now - settleReportAt < 2000L) {
            return;
        }
        boolean first = settleReportAt == 0L;
        settleReportAt = now;
        try {
            StringBuilder b = new StringBuilder();
            b.append("step ").append(Gameplay.step + 1).append('/')
                    .append(Gameplay.plan == null ? -1 : Gameplay.plan.plan.size())
                    .append(" turn=").append(pendingTurnBefore).append("->").append(GameActionManager.turn)
                    .append(" expectedEnd=").append(Gameplay.expectedStepEnd())
                    .append(" settled=").append(Gameplay.engineSettled())
                    .append(" quiet=").append(Gameplay.actionQueueQuiet());
            if (pending != null) {
                // B154 (user m14492): "it has to be right from the start" can only be proven with
                // both sides of the SAME moment in one line -- the live board and the board the plan
                // stored for this step. The first field that differs is where the sandbox and the
                // fight part ways; nothing about the plan or the decisions changes here.
                String liveDigest = Digest.ofCurrent();
                b.append(" digestMatch=")
                        .append(Digest.stable(liveDigest).equals(Digest.stable(pending.digest)))
                        .append(" live=|").append(liveDigest).append('|')
                        .append(" plan=|").append(pending.digest).append('|');
                // B154: the boards say WHERE they differ, the plays say WHO moved first. Both sides
                // are the same step, so the first play that is not on both lists is the action the
                // sandbox and the fight disagree about.
                try {
                    Solver.Step expected = Gameplay.plan != null && Gameplay.plan.plan != null
                            && Gameplay.step >= 0 && Gameplay.step < Gameplay.plan.plan.size()
                            ? Gameplay.plan.plan.get(Gameplay.step) : null;
                    if (expected != null) {
                        b.append(" live plays: ").append(sts1solver.mirror.PlayTrace.recent(8))
                                .append(" || plan plays: ").append(expected.plays);
                    }
                }
                catch (Throwable ignored) {
                    // a pending step without a plan entry is normal after a plan drop
                }
            }
            b.append(" stableFrames=").append(pendingStableFrames)
                    .append(" selecting=").append(Driver.isAwaitingSelection())
                    .append(" kind=").append(Driver.selectionKind())
                    .append(" screenUp=").append(AbstractDungeon.isScreenUp)
                    .append(" screen=").append(AbstractDungeon.screen)
                    .append(" endingTurn=")
                    .append(AbstractDungeon.player != null && AbstractDungeon.player.isEndingTurn)
                    .append(" turnHasEnded=")
                    .append(AbstractDungeon.actionManager != null
                            && AbstractDungeon.actionManager.turnHasEnded);
            Log.info((first ? "settle wait: " : "settle wait (still): ") + b);
        }
        catch (Throwable t) {
            Log.info("settle wait: " + t);
        }
    }

    /** B126: no action of ours or the monster's is mid-flight; a screen may still be up. */
    private static boolean actionQueueQuiet() {
        try {
            GameActionManager m = AbstractDungeon.actionManager;
            if (m == null) {
                return true;
            }
            if (!m.actions.isEmpty() || !m.cardQueue.isEmpty() || !m.monsterQueue.isEmpty()
                    || !m.preTurnActions.isEmpty() || m.usingCard || m.turnHasEnded) {
                return false;
            }
            if (m.currentAction != null && !m.currentAction.isDone) {
                return false;
            }
            return AbstractDungeon.player == null || !AbstractDungeon.player.isEndingTurn;
        }
        catch (Throwable t) {
            return false;
        }
    }

    /**
     * B190 (user m01149): how many screens the pending step's plan answers, empty answers included.
     *
     * <p>Read off the ACTION (not the pending mirrors) because it is asked while a screen is
     * already up, possibly before anything was cached for it.
     */
    private static int plannedScreens() {
        Action a = pending == null ? null : pending.action;
        if (a == null) {
            return 0;
        }
        return Math.max(Action.chainCount(a.choice), Action.chainCount(a.screenKinds));
    }

    /**
     * B139: after a step is done, say it when the plan's answer chain was longer than the screens
     * the fight actually opened. Informational only: nothing is changed and nothing is guessed.
     */
    private static void reportUnusedAnswers() {
        try {
            int total = choiceScreenCount();
            if (total <= 0 || pendingChoice == null || pendingChoice.isEmpty()) {
                return;
            }
            int used = Math.max(0, Math.min(pendingChoiceAt, total));
            if (used >= total) {
                return;
            }
            Log.warn("the plan recorded " + total + " selection answers for this step but the fight"
                    + " opened " + used + "; the rest were never used (" + pendingChoice + ")");
        } catch (Throwable t) {
            Log.warn("unused answer report: " + t);
        }
    }

    private static void pollPending() {
        if (pending == null) {
            settleReportAt = 0L;
            return;
        }
        long now = now();
        // B126: say what this wait is for, once 2 s in and every 2 s after. Silence here is what made
        // "the autopilot froze for 20 s" unreadable in the log.
        reportSettleWait(now);
        // B157 (user m14819): there is no wall clock on a step any more. The 20 s deadline used to
        // drop a step that was still PROGRESSING (a fight that skips turns takes several), and the
        // dropped pending is what then made the plan look stale and vanish. A step now ends only
        // when the live state says it did: the turn the sandbox measured is reached, or the fight
        // is over. Anything that says the two worlds disagree stops the autopilot instead.
        if (Driver.isAwaitingCardChoice()) {
            // B71 (m20521): this used to drop the plan and the pending step with no log at all. That
            // is how a five-step winning line lost its last two actions two frames after the
            // end-turn that opened a screen ("autopilot stopped: the line is finished"). Keep both,
            // say what is on screen once, and only pause the autopilot.
            String sig = "card:" + Driver.screenName() + " step=" + step;
            if (!sig.equals(cardScreenLogged)) {
                cardScreenLogged = sig;
                Log.warn("card screen is up and the plan cannot answer it (" + Driver.cardRewardSummary()
                        + ", " + Driver.screenName() + ") at step " + step
                        + "; the plan and the pending action are kept, the autopilot pauses");
            }
            String why = "\u9009\u724c\u754c\u9762\u6321\u7740\uff08" + Driver.screenName()
                    + "\uff09\uff1b\u8ba1\u5212\u8fd8\u5728\uff0c\u5904\u7406\u5b8c\u518d\u70b9\u300c\u81ea\u52a8\u300d\u7ee7\u7eed";
            if (autopilot) {
                stopAutopilot(why);
            } else {
                status = why;
            }
            Hud.set(Gameplay.lines());
            return;
        }
        if (Driver.selectionKind() == Driver.SelectionKind.REWARD) {
            // Discovery / choose-one screen: a real decision the plan already made. Answer it the way
            // a click would and let the frozen action retrieve the card -- then the plan continues.
            String pick = pending == null || pending.action.choice == null ? "" : pending.action.choice;
            String pickText = choiceText(pending);
            // B158 (user m14819/m15075): no guessing, same rule as the hand/pile screens below. A
            // reward screen the plan has no answer for is a live/sandbox difference; answering it with
            // "the best card" invents a state the plan never assumed.
            if (pick.isEmpty()) {
                String whyReward = "\u8fd9\u4e00\u5c4f\u5956\u52b1\u8ba1\u5212\u91cc\u6ca1\u6709\u7b54\u6848\uff1b\u81ea\u52a8\u51fa\u724c\u5df2\u505c\u4e0b\uff0c\u8bf7\u4f60\u9009\uff0c\u9009\u5b8c\u70b9\u300c\u81ea\u52a8\u300d\u7ee7\u7eed";
                Log.warnEvery("reward-no-answer", 5000L, "card reward screen with no planned answer at step "
                        + (Gameplay.step + 1) + ": " + Driver.cardRewardSummary());
                if (autopilot) {
                    Gameplay.stopAutopilot(whyReward);
                } else {
                    status = whyReward;
                    Hud.set(Gameplay.lines());
                }
                return;
            }
            boolean answered = pendingChoice == null || pendingChoice.isEmpty()
                    ? Driver.resolveSelectionLive(pick, null,
                            pending == null || pending.action == null ? null : pending.action.choiceUuids,
                            pending == null || pending.action == null ? null : pending.action.choicePrints)
                    : answerNextScreen();
            if (answered) {
                status = "\u9009\u724c\uff1a" + pickText;
                Hud.set(Gameplay.lines());
                Log.info("execute: answered card reward with " + pick);
                return;
            }
            Log.warn("execute: could not answer card reward (" + Driver.cardRewardSummary() + ")");
        }
        if (Driver.isAwaitingSelection()) {
            String planned = pending == null || Gameplay.pending.action.choice == null
                    ? "" : Gameplay.pending.action.choice;
            // B158 (user m14819/m15075): NO guessing. A step whose plan carries no answer for the
            // screen that is up means the live fight opened something the sandbox never walked -- the
            // old best-first pick ("最优一张") answered it with a card the plan had not assumed, and
            // every number after that step was fiction. Stop and leave the screen to the player; the
            // plan and the pending step stay untouched.
            // B190 (user m01149): unless the plan's answer for this screen is "pick nothing" -- that
            // is a real answer (plannedScreens() counts the screens, not the picked cards).
            if (planned.isEmpty() && plannedScreens() == 0) {
                String why = "\u8fd9\u4e00\u5c4f\u8ba1\u5212\u91cc\u6ca1\u6709\u7b54\u6848\uff08\u5b9e\u673a\u5f00\u4e86\u6c99\u76d2\u6ca1\u7b97\u5230\u7684\u754c\u9762\uff09\uff1b\u81ea\u52a8\u51fa\u724c\u5df2\u505c\u4e0b\uff0c\u8bf7\u4f60\u9009\uff0c\u9009\u5b8c\u70b9\u300c\u81ea\u52a8\u300d\u7ee7\u7eed";
                Log.warnEvery("selection-no-answer", 5000L, "selection open with no planned answer at step "
                        + (Gameplay.step + 1) + "/" + (plan == null || plan.plan == null ? 0 : plan.plan.size())
                        + ": kind=" + Driver.selectionKind() + " candidates="
                        + Driver.namesOf(Driver.selectionCandidates()) + " | last plays: "
                        + sts1solver.mirror.PlayTrace.recent(8));
                if (autopilot) {
                    Gameplay.stopAutopilot(why);
                } else {
                    status = why;
                    Hud.set(Gameplay.lines());
                }
                return;
            }
            boolean answered = answerNextScreen();
            if (!answered && Driver.lastSelectionFailure().startsWith("not-offered: ")) {
                // B204 (user m01642): the screen is up and complete, but the plan's answer names a
                // copy it does not carry the same way. Clicking anything here would run a branch the
                // plan never chose, so stop -- the same rule the no-answer case above follows.
                String why = "\u8fd9\u4e00\u5c4f\u9009\u724c\u8ba1\u5212\u91cc\u7684\u7b54\u6848\u5bf9\u4e0d\u4e0a\uff08\u540c\u540d\u724c\u7684\u5b9e\u673a\u987a\u5e8f\u4e0e\u8ba1\u5212\u4e0d\u540c\uff09\uff1b\u81ea\u52a8\u51fa\u724c\u5df2\u505c\u4e0b\uff0c\u8bf7\u4f60\u9009\uff0c\u9009\u5b8c\u70b9\u300c\u81ea\u52a8\u300d\u7ee7\u7eed";
                Log.warn("selection answer does not fit: " + Driver.lastSelectionFailure()
                        + " | plan=[" + planned + "] liveOffered=["
                        + brief(Solver.positionedIds(Driver.selectionCandidates())) + "]");
                if (autopilot) {
                    Gameplay.stopAutopilot(why);
                } else {
                    status = why;
                    Hud.set(Gameplay.lines());
                }
                return;
            }
            if (answered) {
                status = "\u9009\u724c\uff1a" + choiceSegmentText();
                Hud.set(Gameplay.lines());
                Log.info("execute: answered selection with " + planned);
                lastActionAtMs = now();
                Driver.clearSelectionRetry();
                return;
            }
            // B66/B158 (user m15075): this wait is a STATE, not a stopwatch. The pick is filled and the
            // action that opened the screen has not taken it yet, so the engine simply has frames left
            // to run -- wait for them for as long as the screen is up. Waiting used to be bounded by
            // AutoPace.verifyMs() (700ms), which handed control back on a slow frame and, worse, let the
            // autopilot judge a half-run step. Nothing is dropped here: the plan and the pending step
            // stay untouched, and a real deadlock is visible in the log every five seconds.
            if (Driver.selectionRetrying()) {
                Log.warnEvery("selection-retry", 5000L, "a filled answer is still waiting for the action that opened the screen ("
                        + Driver.selectionKind() + ") at step " + (Gameplay.step + 1));
                status = "\u9009\u724c\u4e2d\uff1a\u7b49\u8fd9\u4e00\u6b65\u7ed3\u7b97";
                Hud.set(Gameplay.lines());
                return;
            }
            // B77 (m21571): the plan survives a selection the autopilot could not answer. Only the
            // pending action is dropped, so 「自动」 picks the plan up again once the screen is gone.
            pending = null;
            if (!Driver.lastSelectionConsumed()) {
                // B49: the screen took the choice but the action waiting on it never consumed it, so
                // the card effect (headbutt+ moves the pick to the top of the draw pile) would be
                // silently lost. Pause instead of replaying a plan built on a wrong state.
                String why49 = "选牌已应答，但等待中的动作没有取走这张牌（效果可能丢）；计划已保留，点「自动」继续";
                if (autopilot) {
                    Gameplay.stopAutopilot(why49);
                } else {
                    status = why49;
                    Hud.set(Gameplay.lines());
                }
                return;
            }
            String whySel = "该步会打开选牌/奖励界面，但没能替你选；计划已保留，选完点「自动」继续";
            if (autopilot) {
                Gameplay.stopAutopilot(whySel);
                return;
            }
            status = whySel;
            Hud.set(Gameplay.lines());
            Hud.markStep(-1, -1, false);
            Log.warn("execute: could not answer the open selection; handing control back (" + Driver.selectionKind() + ")");
            return;
        }
        // B85: an end turn that ENDED THE FIGHT is done the moment the fight is over. Waiting for
        // the engine to settle here buys nothing -- the room is gone and the monster turn the line
        // predicted never runs -- and the over-frame cleanup would drop the pending step before it
        // could be counted, so the panel ended up saying "执行中" for a fight that was already won.
        boolean endTurnEndedFight = Gameplay.pending.action.isEndTurn()
                && Gameplay.fightFinished();
        // B202 (user m01245): a pick screen this step opened is answered by answerLatentSelection,
        // which runs before this check in the same frame. One still up here means the step is not
        // done yet (its answer did not take, or the beat has not come), so it must not be judged,
        // reported or dropped as if its board were final.
        if (!endTurnEndedFight && (Driver.isAwaitingSelection() || AbstractDungeon.isScreenUp)) {
            return;
        }
        // B157 (user m14819): a live turn PAST the one the sandbox measured is not a finished step.
        // It means the two worlds already disagree about the turn count, so every number the panel
        // shows from here on would be made up. Stop and wait for the player -- this replaces the B126
        // "the fight passed the step" advance, which counted a step as done while the real turn
        // counter had moved.
        int expectedEnd = Gameplay.expectedStepEnd();
        if (!endTurnEndedFight && GameActionManager.turn > expectedEnd) {
            String why = "实机已到第 " + GameActionManager.turn + " 回合，计划这一步只到第 "
                    + expectedEnd + " 回合；自动出牌已停下，计划保留";
            Log.warnEvery("step-overshoot", 5000L, "step " + (Gameplay.step + 1) + "/"
                    + (Gameplay.plan == null ? 0 : Gameplay.plan.plan.size())
                    + " overshot the plan: live turn " + GameActionManager.turn + " > expected "
                    + expectedEnd + " (turnBefore " + pendingTurnBefore + "); the step is kept");
            if (autopilot) {
                Gameplay.stopAutopilot(why);
            } else {
                status = why;
                Hud.set(Gameplay.lines());
            }
            return;
        }
        if (!endTurnEndedFight && !Gameplay.engineSettled()) {
            return;
        }
        boolean endsTurn = Gameplay.pending.action.isEndTurn();
        // B85: an end turn that ENDS THE FIGHT never advances the turn (poison ticking on the
        // monsters' turn kills the last one, the room ends, GameActionManager.turn stays put), so
        // combat over is the other, equally final, way for that step to be done.
        // B157 (user m14819): otherwise an end turn is done when the live fight reaches the turn the
        // sandbox measured FOR IT -- not as soon as the counter moved by one. A fight that skips
        // turns (TrancePower stacks) takes two or three, and settling after the first compared a
        // half-way board with the planned end-of-step board and "found" a difference that was not
        // there. Wait for the measured turn; an overshoot is handled above.
        if (endsTurn && GameActionManager.turn < expectedEnd && !Gameplay.fightFinished()) {
            return;
        }
        Solver.Step step = pending;
        String digestNow = Digest.ofCurrent();
        String stableNow = Digest.stable(digestNow);
        String stableExpected = Digest.stable(step.digest);
        // B85: an end turn that ENDED THE FIGHT can never match the planned digest -- the room is
        // gone and the monster turn the line predicted never runs -- so the drift path below would
        // sit in its settle window while the panel clears the finished fight out from under the
        // pending step. Combat over is that step's completion, nothing more to verify.
        boolean fightEnded = Gameplay.fightFinished();
        if (!fightEnded && !stableNow.equals(stableExpected)) {
            // B158 (user m15075): judge on ENGINE STATE, not on a stopwatch. The only thing that used to
            // hold this back was AutoPace.verifyMs() (700ms after the last action); what it protected --
            // a card in limbo, an effect still landing -- is already covered by engineSettled() above
            // (empty queues, no current action, no limbo, not ending the turn). A stale digest now means
            // the step really came out differently, and the branch below stops the autopilot.
            // The frame counter only keeps a digest that is still MOVING from being called a mismatch;
            // it is not a timeout and it can never make the panel advance on its own.
            if (!stableNow.equals(lastPendingDigest)) {
                lastPendingDigest = stableNow;
                pendingStableFrames = 0;
                return;
            }
        }
        // B139 (user m08865/m08974): the plan carries one answer per selection screen the SEARCH
        // walked. The live fight can open a different number of them, and the leftovers used to be
        // dropped without a word -- that is how a step could be "answered" three times while the
        // plan had four answers (and the fourth was meant for a screen the fight never opened).
        reportUnusedAnswers();
        pending = null;
        lastPendingDigest = null;
        pendingStableFrames = 0;
        // B147 (user m12327): the two worlds' play traces and random positions, side by side. The
        // trace is engine-side (one entry per card play, whichever side played it), so this is the
        // same kind of fact for the live fight and for the plan the search built.
        String livePlays = sts1solver.mirror.PlayTrace.describe(stepTraceMark);
        String liveRng = sts1solver.mirror.RngStamp.of();
        // B157 (user m14819): "有不对的直接停下来". A step whose settled board differs from the one
        // the plan recorded means the numbers after it are fiction, so the autopilot stops here and
        // the plan stays for the player to read. Nothing is recomputed on its own (m16202-2).
        boolean diverged = !fightEnded && !stableNow.equals(stableExpected);
        if (diverged) {
            // B77 (m21571): STS2 behaviour, as asked -- deploy the plan, and only pause when the card
            // or the potion it names is not there. A board that came out slightly differently is not a
            // reason to throw the plan away: dropping it here is exactly what ended fight after fight
            // with "第 N 步与预测不一致（局面变了），已作废计划". Note it and keep walking; the next
            // step that needs a card we do not have pauses by itself (see fail()).
            Gameplay.noteDriftAndContinue(step, digestNow, livePlays, liveRng);
        } else if (!fightEnded && step.action != null && step.action.isEndTurn()
                && !step.plays.isEmpty()) {
            // B147: an end turn that came out RIGHT still prints both sides once. That line is what
            // makes the next "the plan was wrong after an end turn" report readable without a
            // reproduction: it names every card each side played, in order, with its random position.
            Log.info("end turn plays: live " + livePlays + " || sandbox " + step.plays
                    + " || rng " + (sts1solver.mirror.RngStamp.same(step.rng) ? "same"
                            : "DIFFERS planned " + step.rng + " live " + liveRng));
        } else if (!fightEnded && step.action != null
                && (!livePlays.isEmpty() || !step.plays.isEmpty())) {
            // B149 (user m12829): an ordinary step that auto-played something (唐 plays a card at the
            // start of every turn, 奶龙 replays it, a relic queues one) used to print nothing at all
            // unless it was an end turn, so when a fight opened a selection screen "out of nowhere"
            // the log could not say which card opened it. Same two-sided line as an end turn.
            Log.info("step plays: live " + livePlays + " || sandbox " + step.plays
                    + " || rng " + (sts1solver.mirror.RngStamp.same(step.rng) ? "same"
                            : "DIFFERS planned " + step.rng + " live " + liveRng));
        }
        Gameplay.syncMarkers();
        ++verified;
        if (diverged) {
            status = "第 " + (Gameplay.step + 1) + " 步和计划不一样（局面已偏离）；自动出牌已停下，计划保留";
            ++Gameplay.step;
            if (autopilot) {
                Gameplay.stopAutopilot(status);
            } else {
                Hud.set(Gameplay.lines());
            }
            return;
        }
        status = "\u7b2c " + ++Gameplay.step + "/" + Gameplay.plan.plan.size() + " \u6b65\u5df2\u6821\u9a8c";
        if (Gameplay.step >= Gameplay.plan.plan.size()) {
            status = "\u8ba1\u5212\u6267\u884c\u5b8c\u6bd5\uff08" + (Gameplay.plan.win ? "\u80dc\u7ebf" : (Gameplay.plan.escaped ? "\u9003\u8dd1" : "\u672a\u80dc")) + "\uff09";
        }
        Hud.set(Gameplay.lines());
    }

    /**
     * B85: is this fight finished, in any of the shapes the engine reports it?
     *
     * <p>{@code Driver.combatOver()} asks "are the monsters dead and nothing left to resolve". That
     * is one frame too late for the room: the room leaves COMBAT phase first, and the panel clears
     * its slate when that happens, which dropped an end turn that had just won the fight before it
     * could be counted. An end turn that ended the fight is done as soon as any of these is true.
     */
    private static boolean fightFinished() {
        if (sts1solver.drive.Driver.combatOver()) {
            return true;
        }
        try {
            com.megacrit.cardcrawl.rooms.AbstractRoom room = AbstractDungeon.getCurrRoom();
            if (room == null) {
                return true;
            }
            return room.isBattleOver
                    || room.phase != com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMBAT;
        }
        catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Harness hook (B85): has the mod latched "in combat" yet? The first tick on a freshly built
     * board clears the slate and only the NEXT one flips this on, and that flip also nulls the plan
     * -- so a gate that installs a plan between the two loses it before it can run.
     */
    public static boolean inCombatForTest() {
        return inCombat;
    }

    /** Harness hook for the settle predicate (P2-2f): limbo must count as "not settled". */
    public static boolean engineSettledForTest() {
        return engineSettled();
    }

    private static boolean engineSettled() {
        try {
            GameActionManager gameActionManager = AbstractDungeon.actionManager;
            if (gameActionManager == null) {
                return true;
            }
            if (!gameActionManager.actions.isEmpty() || !gameActionManager.cardQueue.isEmpty() || !gameActionManager.monsterQueue.isEmpty() || !gameActionManager.preTurnActions.isEmpty() || gameActionManager.usingCard || gameActionManager.turnHasEnded) {
                return false;
            }
            // GameActionManager.update() pulls the next action into currentAction at the end of a
            // frame and only runs it on the following one, so "the list is empty" is one frame too
            // early. Without this the board looked settled while the last action (a heal, a block
            // gain) had not executed yet -- that is how a potion's relic heal got lost.
            if (gameActionManager.currentAction != null && !gameActionManager.currentAction.isDone) {
                return false;
            }
            if (Driver.isAwaitingSelection() || AbstractDungeon.isScreenUp) {
                return false;
            }
            // A played card sits in limbo between "queued" and "in the discard pile". The digest
            // changes when it lands, so treating limbo as settled made every card look like drift --
            // which triggered a replan per card (the per-card lag) and eventually dropped the plan.
            if (AbstractDungeon.player != null && AbstractDungeon.player.limbo != null
                    && !AbstractDungeon.player.limbo.isEmpty()) {
                return false;
            }
            return AbstractDungeon.player == null || !AbstractDungeon.player.isEndingTurn;
        }
        catch (Throwable throwable) {
            return false;
        }
    }

    public static void toggleAutopilot() {
        Hud.demoOff();
        if (!autopilot && !Gameplay.hasPlan()) {
            // Turning autopilot on without a plan is a request to solve and go, not an error.
            if (!Gameplay.inFight()) {
                status = "\u672a\u5728\u6218\u6597\u4e2d\uff1a\u5148\u8fdb\u5165\u4e00\u573a\u6218\u6597";
                Hud.set(Gameplay.lines());
                return;
            }
            if (quick != null) {
                // A 计算 is already running: arm it (a second press cancels the arming).
                if (autoplayArmed) {
                    autoplayArmed = false;
                    status = "已取消：算完不会自动出牌";
                    Hud.set(Gameplay.lines());
                    Log.info("autopilot: arming cancelled on the running search");
                    return;
                }
                autoplayArmed = true;
                autoplayReason = "自动出牌";
                status = "计算中：算完自动开始出牌（再按一次取消）";
                Hud.set(Gameplay.lines());
                Log.info("autopilot: armed on the running search");
                return;
            }
            if (deep != null) {
                status = "\u6df1\u5ea6\u641c\u7d22\u8fdb\u884c\u4e2d\uff1a\u5148\u70b9\u300c\u8ba1\u7b97\u300d\u505c\u6b62\u5b83";
                Hud.set(Gameplay.lines());
                return;
            }
            // B51 (m15336-2): 自动出牌 IS 计算 + 自动执行. The old path called the blocking solveLive()
            // with its own budget ladder, which is exactly why "先计算再自动" behaved differently.
            autoplayReason = "自动出牌";
            Gameplay.startQuickSolve(true);
            if (!Gameplay.hasPlan()) {
                status = "正在计算（和「计算」同一条路）：算完自动开始出牌";
                Hud.set(Gameplay.lines());
                return;
            }
            if (autopilot) {
                // A cached win line was adopted and is already playing.
                return;
            }
            // A cached line that is not a win: fall through to the usual confirmation press.
        }
        if (!(autopilot || plan == null || Gameplay.plan.win || Gameplay.plan.escaped || weakConfirmed)) {
            weakConfirmed = true;
            status = "\u672a\u627e\u5230\u80dc\u7ebf\uff08\u53ea\u662f\u5c3d\u529b\u7ebf\uff09\uff1b\u518d\u70b9\u300c\u81ea\u52a8\u51fa\u724c\u300d\u786e\u8ba4\u6267\u884c";
            Hud.set(Gameplay.lines());
            return;
        }
        if (autopilot) {
            Gameplay.markAutopilotOff();
            return;
        }
        Gameplay.startAutoplay();
    }

    /** Turn the autopilot on with one beat of delay before the first card. */
    private static void startAutoplay() {
        // B78 (user m21889): a line whose replay lost a step is not safe to play -- the steps shown
        // are a prefix of a different game. Refuse it here, the single place every autopilot start
        // goes through, so 自动 and the armed 算完开始 path behave the same.
        if (plan != null && !plan.planVerified) {
            status = sts1solver.search.PlanText.headline(plan);
            Hud.set(Gameplay.lines());
            Log.warn("autopilot: not playing a line that did not replay | " + status);
            return;
        }
        autopilot = true;
        autopilotFailures = 0;
        // A fresh autopilot run starts with one beat of delay too, otherwise the first card is
        // already gone by the time the player looks at the panel.
        nextActionAtMs = AutoPace.nextAt(now(), AutoPace.delayMs());
        weakConfirmed = false;
        autoplayArmed = false;
        status = "\u81ea\u52a8\u6267\u884c\u4e2d\uff08\u518d\u70b9\u300c\u81ea\u52a8\u51fa\u724c\u300d\u505c\u6b62\uff09";
        Hud.set(Gameplay.lines());
        Log.info("autopilot on");
    }

    private static void markAutopilotOff() {
        autopilot = false;
        autoplayArmed = false;
        autopilotFailures = 0;
        nextActionAtMs = 0L;
        weakConfirmed = false;
        status = "\u81ea\u52a8\u6267\u884c\u5df2\u505c\u6b62";
        Hud.set(Gameplay.lines());
        Log.info("autopilot off");
    }

    /**
     * B51: a solved line landed while 自动出牌 was armed.
     *
     * <p>A proven win starts playing immediately. A survival line is NOT played silently: that is the
     * same confirmation the two-press flow always required ("再按一次自动"), so nothing plays a
     * "no win found" line just because the search happened to finish late.
     */
    private static void beginAutoplay(String why) {
        autoplayArmed = false;
        if (plan == null || !Gameplay.hasPlan()) {
            status = why + "：算完了，但没有可执行的步骤";
            Hud.set(Gameplay.lines());
            return;
        }
        if (plan.win || plan.escaped) {
            Gameplay.startAutoplay();
            return;
        }
        status = why + "：" + sts1solver.search.PlanText.headline(plan) + "；这是尽力线，再按一次\u300c\u81ea\u52a8\u300d\u6267\u884c";
        Hud.set(Gameplay.lines());
        Log.info("autopilot: the adopted line is a survival line, waiting for confirmation");
    }

    /** True while 自动出牌 has asked for a solve and is waiting for it to land (B51). */
    /**
     * Harness hook (m16202-2): drop the line the way a finished route does, so a gate can check
     * that the autopilot STOPS there instead of quietly searching again.
     */
    public static void dropPlanForTest() {
        plan = null;
        planBuiltAt = -1L;
        step = 0;
        stepTarget = 0;
        pending = null;
        Gameplay.clearPendingChoice();
    }

    /** Harness hook (m16202-2): autopilot on, nothing armed, no search in flight. */
    public static void autopilotOnForTest() {
        autopilot = true;
        autoplayArmed = false;
        nextActionAtMs = 0L;
    }

    public static int lastDriftStepForTest() {
        return drift;
    }

    /**
     * Probe hook (S127): adopt a solved line as if 计算 had just finished, starting at a given step.
     *
     * <p>Autopilot is on, so one {@link #tick()} deploys that step -- which is exactly what the gate
     * needs to prove "a missing card pauses but keeps the plan" (B77).
     */
    public static void installPlanForTest(Solver.Result result, int startStep) {
        plan = result;
        step = Math.max(0, Math.min(startStep, result == null || result.plan == null
                ? 0 : result.plan.size() - 1));
        stepTarget = 0;
        planBuiltAt = now();
        pending = null;
        Gameplay.clearPendingChoice();
        drift = 0;
        autopilot = true;
        autoplayArmed = false;
        nextActionAtMs = 0L;
        lastActionAtMs = 0L;
        status = "";
        Hud.set(Gameplay.lines());
    }

    /** Probe hook (S184, B126): pretend one step of the plan is in flight. */
    public static void startPendingForTest(Solver.Step step, int turnBefore) {
        pending = step;
        pendingTurnBefore = turnBefore;
        settleReportAt = 0L;
    }

    /** Probe hook (S184, B126): drop the pretend in-flight step. */
    public static void clearPendingForTest() {
        pending = null;
        lastPendingDigest = null;
        pendingStableFrames = 0;
        settleReportAt = 0L;
    }

    /** Probe hook (S135/S136, B85): how many steps of the plan are behind us. */
    public static int stepForTest() {
        return step;
    }

    /** Probe hook (S135/S136, B85): is one submitted step still waiting for the engine? */
    public static boolean pendingForTest() {
        return pending != null;
    }

    /** Probe hook (S212, B158): the planned answer still held for a screen that has not opened yet. */
    public static String pendingChoiceForTest() {
        return pendingChoice;
    }

    /** Probe hook (S224, B167): the same gate the solve path applies to a cached plan. */
    public static boolean cacheFitsForTest(Solver.Result cached) {
        return Gameplay.cacheFits(cached);
    }

    /** Probe hook (S226, B167): answer the open screen the way a selection step does. */
    public static boolean answerSelectionForTest(Action action) {
        if (action == null) {
            return false;
        }
        Gameplay.planPendingChoice(action);
        return Gameplay.answerSelectionStep(action);
    }

    /** Probe hook (S212, B158): record an answer the way submit() does, to prove it has no clock. */
    public static void holdAnswerForTest(String choice) {
        pendingChoice = choice == null ? "" : choice;
        pendingChoiceDisplay = "";
        pendingChoiceUuids = "";
        pendingChoicePrints = "";
        pendingChoiceMoved = "";
        pendingChoiceMovedUuids = "";
        pendingChoiceAt = 0;
    }
    /** Probe hook (S127): the state a failed step leaves behind, for the gate's assertions. */
    public static String planStateForTest() {
        return "plan=" + (plan == null ? "-" : Integer.toString(plan.plan.size()))
                + " step=" + step
                + " autopilot=" + autopilot
                + " pending=" + (pending == null ? "-" : "1")
                + " status=" + status;
    }

    /** Probe hook (S127): feed the drift path one step, to prove it no longer drops the plan. */
    public static void driftForTest() {
        if (plan != null && plan.plan != null && step < plan.plan.size()) {
            // B147: "" on both sides means "no live evidence", which is what this hook has.
            Gameplay.noteDriftAndContinue(plan.plan.get(step), Digest.ofCurrent(), "", "");
        }
    }

    public static boolean autoplayArmed() {
        return autoplayArmed;
    }

    public static boolean autopilot() {
        return autopilot;
    }

    public static void onEscapePolicyChanged(EscapePolicy.Mode mode) {
        Log.info("escape policy: " + mode.label);
        if (plan != null) {
            plan = null;
            status = "\u9003\u8dd1\u7b56\u7565\u5df2\u6539\u4e3a\u300c" + mode.label + "\u300d\uff1b\u70b9\u300c\u8ba1\u7b97\u300d\u91cd\u7b97";
        } else {
            status = "\u9003\u8dd1\u7b56\u7565\uff1a \u300c" + mode.label + "\u300d";
        }
        Hud.set(Gameplay.lines());
    }

    public static void executePlan() {
        if (!autopilot) {
            Gameplay.toggleAutopilot();
        }
    }

    public static int verifiedSteps() {
        return verified;
    }

    /**
     * B202 (user m01245): when autoplay may touch the open pick screen -- a fraction of a second
     * AFTER it appeared, at the same beat the plan uses between two actions.
     *
     * <p>Answering on the first frame the screen is up read as "the mod clicks before I can see it".
     * The wait is a clock comparison on the frame loop (never a sleep), keyed on the screen itself:
     * the first frame the same kind of screen is seen starts the beat, so a second screen of a chain
     * starts its own. Delay 0 means "answer on the first frame" (the pre-B202 behaviour), kept so
     * the A/B is possible.
     */
    private static boolean selectionBeatDue() {
        int delay = sts1solver.ui.AutoPace.delayMs();
        if (delay <= 0) {
            return true;
        }
        Driver.SelectionKind kind = Driver.selectionKind();
        if (kind == null || kind == Driver.SelectionKind.NONE) {
            selectionSeenAt = 0L;
            return true;
        }
        long now = now();
        if (selectionSeenAt == 0L || kind != selectionSeenKind) {
            selectionSeenAt = now;
            selectionSeenKind = kind;
            return false;
        }
        return sts1solver.ui.AutoPace.due(now, selectionSeenAt + delay);
    }

    /**
     * B202 (user m01245): the plan answered no screen here -- pick one live, from THIS screen.
     *
     * <p>The sandbox records an answer per screen it walked; a screen the plan does not cover (a
     * potion the search drank without seeing a picker, an end turn whose chain came out one screen
     * longer) used to stop the autopilot. Enumerating the screen's own options and replaying the
     * first is the same walk the search makes in the sandbox (Solver.expandScreens takes
     * options.get(0) of its options), so the live board and the plan stay one mechanism. The answer
     * is logged as a live selection with no planned answer, which is the evidence a later report
     * reads.
     */
    /** Player-facing text for a refusal; the raw reason stays in English in the log. */
    private static String refusalText(String reason) {
        if ("not in combat".equals(reason)) {
            return "不在战斗中：先进入一场战斗";
        }
        if ("engine busy".equals(reason)) {
            return "引擎还在结算：等一拍再点";
        }
        if ("a screen is open".equals(reason)) {
            return "有界面开着（选牌/奖励/地图）：先处理掉再计算";
        }
        if ("no player".equals(reason)) {
            return "没有角色";
        }
        return "现在不能计算：" + reason;
    }

    private static String blockedReason() {
        return Gameplay.blockedReason(null);
    }

    /**
     * B162 (user m16041): an open pick screen is a decision the search can start from. It is the only
     * thing allowed to be "in the way" of a step that answers it; everything else (a queue still
     * running, a screen nobody planned for) is still a refusal.
     */
    private static String blockedReason(Action answering) {
        try {
            if (AbstractDungeon.player == null) {
                return "no player";
            }
            AbstractRoom abstractRoom = AbstractDungeon.getCurrRoom();
            if (abstractRoom == null || abstractRoom.phase != AbstractRoom.RoomPhase.COMBAT) {
                return "not in combat";
            }
            GameActionManager gameActionManager = AbstractDungeon.actionManager;
            if (gameActionManager == null) {
                return "no action manager";
            }
            boolean pick = Driver.isAwaitingSelection() || AbstractDungeon.isScreenUp;
            boolean answerStep = answering != null && answering.isSelection();
            if (!(gameActionManager.actions.isEmpty() && gameActionManager.cardQueue.isEmpty() && gameActionManager.monsterQueue.isEmpty() && gameActionManager.preTurnActions.isEmpty() && !gameActionManager.usingCard)) {
                if (!(pick && answerStep)) {
                    return "engine busy";
                }
            }
            if (pick && !answerStep) {
                return "a screen is open";
            }
            return null;
        }
        catch (Throwable throwable) {
            return "state unreadable: " + throwable;
        }
    }

    public static String viewRestore() {
        return viewRestore;
    }

    public static String vfxSweep() {
        return vfxSweep;
    }

    public static int renderRepairs() {
        return renderRepairs;
    }

    public static String renderRepairDetail() {
        return renderRepairDetail;
    }

    public static int driftCount() {
        return drift;
    }

    static {
        status = "idle";
        renderRepairDetail = "";
        viewRestore = "clean";
        escapeNote = "";
        history = new ArrayList<Solver.Step>();
        rowSteps = new ArrayList<Integer>();
        route = new ArrayList<sts1solver.ui.RouteRow>();
        stepTarget = -1;
        vfxSweep = "clean";
    }

    public static final class EndTurnRisk {
        public final int hpBefore;
        public final int hpAfter;
        public final int damage;
        public final int incoming;
        public final boolean wouldDie;
        public final boolean ok;
        public final String error;

        public EndTurnRisk(int hpBefore, int hpAfter, boolean ok, String error) {
            this(hpBefore, hpAfter, 0, ok, error);
        }

        public EndTurnRisk(int hpBefore, int hpAfter, int incoming, boolean ok, String error) {
            this.hpBefore = hpBefore;
            this.hpAfter = Math.max(0, hpAfter);
            this.incoming = incoming;
            this.damage = Math.max(0, hpBefore - hpAfter);
            this.wouldDie = hpAfter <= 0;
            this.ok = ok;
            this.error = error;
        }

        public String describe() {
            if (!this.ok) {
                return "\u98ce\u9669\u9884\u6d4b\u5931\u8d25: " + this.error;
            }
            return "\u7ed3\u675f\u56de\u5408\u9884\u8ba1 -" + this.damage + " HP \u2192 HP " + this.hpAfter + (this.incoming > 0 ? "\uff08\u6765\u88ad \u2265" + this.incoming + "\uff09" : "") + (this.wouldDie ? "\uff08\u4f1a\u6b7b\uff09" : "");
        }
    }

    public static enum EndTurnDecision {
        ALLOW,
        CONFIRM,
        STOP,
        WARN;

    }
}