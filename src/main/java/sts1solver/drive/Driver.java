package sts1solver.drive;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardQueueItem;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.screens.select.HandCardSelectScreen;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;

import java.lang.reflect.InvocationHandler;
import sts1solver.mirror.Digest;
import sts1solver.mirror.ScreenOpens;
import sts1solver.util.Log;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives the REAL engine through one step (a card play, a potion, a turn end) and waits for it to
 * settle.
 *
 * <p>Three obstacles have to be handled, and each is a specific mechanism rather than a workaround:
 *
 * <ol>
 *   <li><b>Actions are frame-timed.</b> {@code AbstractGameAction.tickDuration()} subtracts
 *       {@code Gdx.graphics.getDeltaTime()}. {@code Graphics} is an interface, so a dynamic proxy
 *       reports a large fixed delta and every action finishes in a couple of updates.</li>
 *   <li><b>Simulation must be silent.</b> Hundreds of simulated card plays would fire hundreds of
 *       sounds; {@code SoundMaster} reads {@code Settings.SOUND_VOLUME} at play time.</li>
 *   <li><b>Some cards ask the player a question.</b> The engine opens a selection screen and waits
 *       for input; a drain must STOP there instead of spinning, and the search branches on the
 *       answer (or refuses the branch).</li>
 * </ol>
 *
 * <p>The same driver is used for the sandbox (with a cloned world installed) and for the live game
 * (when executing a plan); nothing here knows which is which.
 */
public final class Driver {

    /** Big enough that an action's first {@code tickDuration()} finishes it. */
    public static final float SIM_DELTA = 2.0f;

    private static Graphics realGraphics;
    private static Graphics simGraphics;
    private static boolean installed;
    private static float savedSoundVolume;
    private static int nesting;
    /** B59: how many times a search handed the frame delta back late (see {@link #verifyReleased}). */
    private static int frameDeltaLeaks;
    /** B59: who borrowed the frame delta last, so a late hand-back can name the guilty path. */
    private static Throwable borrowedAt;
    /** B49: did the waiting action actually take the choice we filled into the live screen? */
    private static boolean lastSelectionConsumed = true;

    private Driver() {
    }

    /** Outcome of draining the action queue. */
    public static final class Drain {
        public int steps;
        public boolean settled;
        public boolean awaitingSelection;
        public boolean timedOut;
        public boolean stalled;
        public boolean turnAdvanced;
        public boolean combatOver;
        public boolean discardMissing;
        /** The game would not let the player drink that potion (PotionBook.usable() said no). */
        public boolean refused;

        public boolean ok() {
            return settled && !timedOut && !stalled && !awaitingSelection && !refused;
        }
    }

    public static void begin() {
        if (nesting++ > 0) {
            return;
        }
        if (borrowedAt == null) {
            borrowedAt = new Throwable("frame delta borrowed here");
        }
        Graphics current = Gdx.graphics;
        if (current != null) {
            realGraphics = current;
            if (simGraphics == null) {
                simGraphics = makeDeltaProxy(current, SIM_DELTA);
            }
            if (simGraphics != null) {
                Gdx.graphics = simGraphics;
                installed = true;
            }
        }
        savedSoundVolume = Settings.SOUND_VOLUME;
        Settings.SOUND_VOLUME = 0.0f;
    }

    public static void end() {
        if (--nesting > 0) {
            return;
        }
        nesting = 0;
        borrowedAt = null;
        if (installed) {
            Gdx.graphics = realGraphics;
            installed = false;
        }
        Settings.SOUND_VOLUME = savedSoundVolume;
    }

    /**
     * B59: is the engine currently running on a borrowed frame delta?
     *
     * <p>{@link #begin()} swaps {@code Gdx.graphics} for a proxy whose {@code getDeltaTime()}
     * returns {@link #simDelta} (0.05 s per sandbox frame since B181). That swap is a
     * <b>process-wide</b> global: if one path ever returns without {@link #end()}, the live game
     * keeps advancing every animation by half a second per frame -- which is exactly what "all the
     * monsters and the player speed up after a solve" looks like. Nothing used to check it.
     */
    public static boolean isInstalled() {
        return installed || nesting > 0;
    }

    /** One-line state for the watchdog and slow-frame logs. */
    public static String gfxState() {
        return "gfx=" + (isInstalled() ? "sim" : "real") + " nesting=" + nesting
                + " leaks=" + frameDeltaLeaks;
    }

    /** How many times a search left the frame delta swapped past the end of a frame. */
    public static int frameDeltaLeaks() {
        return frameDeltaLeaks;
    }

    /** B59: how many borrows are outstanding right now (0 = the real frame delta is in place). */
    public static int borrowDepth() {
        return nesting;
    }

    /** Test hook: forget the leak tally so one gate can measure only its own window. */
    public static void resetLeakCount() {
        frameDeltaLeaks = 0;
    }

    /**
     * Called at the end of every idle frame: the real graphics object must be back in place.
     *
     * @return true when everything was released; false when the frame delta had to be taken back
     */
    public static boolean verifyReleased(String where) {
        if (!isInstalled()) {
            return true;
        }
        int was = nesting;
        nesting = 0;
        if (installed) {
            Gdx.graphics = realGraphics;
            installed = false;
        }
        Settings.SOUND_VOLUME = savedSoundVolume;
        frameDeltaLeaks++;
        Throwable cause = borrowedAt;
        borrowedAt = null;
        if (cause != null) {
            Log.error("frame delta: a sandbox still owned Gdx.graphics at " + where + " (nesting="
                    + was + ") -- every animation would have run at " + simDelta
                    + "s per frame; the stack below is where that borrow started;", cause);
        } else {
            Log.error("frame delta: a sandbox still owned Gdx.graphics at " + where + " (nesting="
                    + was + ") -- every animation would have run at " + simDelta + "s per frame;",
                    new IllegalStateException("frame delta leaked"));
        }
        return false;
    }

    public static void run(Runnable body) {
        begin();
        try {
            body.run();
        } finally {
            end();
        }
    }

    private static Graphics makeDeltaProxy(final Graphics real, final float delta) {
        try {
            return (Graphics) Proxy.newProxyInstance(
                    Driver.class.getClassLoader(),
                    new Class<?>[]{Graphics.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args)
                                throws Throwable {
                            if ("getDeltaTime".equals(method.getName())) {
                                // Per frame, not per window: an action that opens a select screen
                                // ticks its own duration in the SAME update, so those frames have to
                                // look like the game's own frame length or the action is done before
                                // the answer exists (B83/B84).
                                return Float.valueOf(frameDelta());
                            }
                            try {
                                return method.invoke(real, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        }
                    });
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ drain

    /** Measurement counters: engine updates and presentation-only actions seen in a drain. */
    public static long updates;
    public static long wastedUpdates;
    public static long drainCalls;
    public static long settledDrains;
    public static long stalledDrains;
    public static long stallIters;
    public static long stallWithEndTurnLatched;
    /** Only set by the cost probe: hashing every update is too expensive for a real search. */
    public static boolean measureCost;
    public static long presentationActions;
    public static long actionsSeen;
    /** Where a drain update spends its time: the engine's own action pump, and the room object pump. */
    public static long updateNanos;
    public static long pumpNanos;
    /**
     * Time per in-flight action class, as long[]{nanos, updates}. A per-update cost that grows with
     * the fight must belong to some action, so this names it instead of guessing.
     */
    public static final java.util.Map<String, long[]> actionNanos =
            new java.util.HashMap<String, long[]>();

    public static void resetCostCounters() {
        updateNanos = 0;
        pumpNanos = 0;
        actionNanos.clear();
        updates = 0;
        wastedUpdates = 0;
        drainCalls = 0;
        settledDrains = 0;
        stalledDrains = 0;
        stallIters = 0;
        stallWithEndTurnLatched = 0;
        presentationActions = 0;
        actionsSeen = 0;
    }

    /**
     * {@code Class.getSimpleName()} is not free: for anonymous/lambda classes it walks out to the
     * enclosing method ({@code Class.getEnclosingMethod0}, a native call), and this predicate runs once
     * per action popped from the queue. Measured ~5% of samples in the search loop. Cached per class.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, String> SIMPLE_NAMES =
            new java.util.concurrent.ConcurrentHashMap<Class<?>, String>();

    private static String simpleName(Class<?> c) {
        String s = SIMPLE_NAMES.get(c);
        if (s == null) {
            String full = c.getName();
            int dot = full.lastIndexOf('.');
            s = dot >= 0 ? full.substring(dot + 1) : full;
            SIMPLE_NAMES.put(c, s);
        }
        return s;
    }

    private static boolean isPresentation(com.megacrit.cardcrawl.actions.AbstractGameAction a) {
        String n = simpleName(a.getClass());
        return n.startsWith("VFXAction") || n.startsWith("SFXAction")
                || n.equals("WaitAction") || n.startsWith("TalkAction")
                || n.startsWith("SpeechBubble") || n.startsWith("AnimationAction")
                || n.startsWith("ShakeScreenAction") || n.startsWith("FlashAction")
                || n.startsWith("SetMoveAction") ? false : n.startsWith("VFX")
                || n.startsWith("SFX") || n.equals("WaitAction");
    }

    /** B116 (m00748): why the engine turned the last play down, or null when it landed. */
    private static volatile String lastRefusal;

    /** B116: the refusal that stopped the last play, for callers that must report it. */
    public static String lastRefusal() {
        return lastRefusal;
    }

    public static Drain drain() {
        return drain(600, 250L);
    }

    /**
     * Set while the caller already ran the room's endTurn: a latched end turn then must NOT run it a
     * second time. Vault and any "press the End Turn button" card latch it from inside the action
     * queue, where nothing but the game loop would have called {@code room.endTurn()}.
     */
    private static boolean suppressRoomEndTurn;

    /** B114: how many times the end-of-turn card queue ran out of its wall-clock budget. */
    private static int endOfTurnQueueTimeouts;

    /**
     * B102 (user m2901): a wall-clock budget is not reproducible, and a truncated queue is a
     * different state.
     *
     * <p>Every card, potion and end turn drains the action queue with a 250 ms budget
     * ({@link #drain()}). Under load -- the live game still rendering at 60 fps, an 8 GB search heap,
     * a GC pause -- the same action stops at a different point in its own queue from one run to the
     * next. The parked state of a deferred path is therefore not the state its rebuild lands in:
     * the search captured "the draw has happened" (its own screen walk pumps a few extra frames
     * afterwards) while the rebuild stops with the {@code DrawCardAction} still queued, so the plan
     * asks for a card that is not in the rebuilt hand and A3 rejects a line it had just found.
     *
     * <p>The fix is to treat the time budget as "how long we are willing to wait before spending
     * more", not as "stop here and call it a state": a drain that neither settled nor reached a
     * selection is completed with a step-bounded drain, which depends only on the state, so the same
     * starting world always lands in the same place.
     */
    private static Drain completeIfTruncated(Drain d) {
        if (d == null || d.settled || d.awaitingSelection || d.refused) {
            return d;
        }
        Drain rest = drain(COMPLETION_STEPS, COMPLETION_MILLIS);
        d.settled = rest.settled;
        d.awaitingSelection = rest.awaitingSelection;
        d.stalled = rest.stalled;
        d.timedOut = d.timedOut || rest.timedOut;
        d.steps += rest.steps;
        if (rest.settled) {
            ++truncatedDrains;
            Log.warnEvery("drain-truncated", 5000L, "a step hit its drain budget and was completed with a "
                    + "step-bounded drain (" + truncatedDrains + " so far): a truncated queue would let the"
                    + " same action land in two different states");
        }
        return d;
    }

    /**
     * B153 (user m14038/m14215): answering a screen can resume a WHOLE end turn, and the queue behind
     * the answer must be finished before the state is read.
     *
     * <p>A click only unblocks the queue: the waiting action reads the answer on the next frame. If
     * that action sat inside an end turn (a card the end-of-turn block played opens a pick -- 唐 ->
     * 豹指导), what follows is a monster turn plus the next turn's start, which is far more than the
     * 600 steps / 250 ms a card play gets. The old answer path drained with that budget and ignored
     * the result, so the step was stored MID-turn: the plan said turn 3->3 / HP 32 while the live
     * fight was at turn 6 / HP 51 (the panel-vs-game report, m14038). Finish the queue with the same
     * step-bounded completion B102 already uses, and report a state that still does not settle
     * instead of storing it as an outcome.
     */
    private static void drainAfterAnswer() {
        Drain d = drain();
        answerLeftUnfinished = false;
        if (d.settled || d.awaitingSelection || d.refused) {
            return;
        }
        Drain done = completeIfTruncated(d);
        if (!done.settled && !done.awaitingSelection && !done.refused) {
            answerLeftUnfinished = true;
            Log.warnEvery("answer-unfinished", 5000L, "an answer resumed the queue but it did not settle"
                    + " inside the completion drain; the state is mid-chain, not a step outcome");
        }
    }

    /** B102: extra steps a truncated drain may run to reach a state that depends only on the state. */
    private static final int COMPLETION_STEPS = 4000;

    /** B102: the completion drain's own safety net -- long enough that it is not the limit. */
    private static final long COMPLETION_MILLIS = 3600000L;

    /** B102: how many steps were completed after their drain budget ran out. */
    private static volatile int truncatedDrains;

    /** B102: how many steps were completed after their drain budget ran out. */
    public static int truncatedDrainsForTest() {
        return truncatedDrains;
    }

    /** B153 (user m14038): the last answered screen resumed a queue that never settled. */
    private static volatile boolean answerLeftUnfinished;

    /**
     * B153 (user m14038): true when the last screen answer resumed work the completion drain could
     * not finish. A caller must not store that state as the step's outcome (B140 rule).
     */
    public static boolean lastAnswerUnfinished() {
        return answerLeftUnfinished;
    }

    public static Drain drain(int maxSteps, long maxMillis) {
        Drain r = new Drain();
        // B89: a latch that is ALREADY set when a drain starts is somebody else's business (the
        // player's own end turn runs through Driver.endTurn, which passes suppressRoomEndTurn). Only
        // a latch that appears WHILE we drive the engine is an effect asking to end the turn.
        // B122b: the latch this loop cares about is a PRESS of the End Turn button and nothing else,
        // and that press is read fresh off the player (endTurnQueued/isEndingTurn) every tick instead
        // of being remembered. Folding turnHasEnded into it (the old endTurnLatched) made the end of
        // the turn we had just finished look like a NEW press: it fired again while AbstractRoom's own
        // queue was still running, so applyEndOfTurnTriggers ran twice (measurement: S168 lost 2 HP
        // to one Combust hit of 1). A press is never lost, because it is not consumed by this loop's
        // memory, only by clearing the player's flag below.
        GameActionManager gam = AbstractDungeon.actionManager;
        if (gam == null) {
            return r;
        }
        drainCalls++;
        long startedAt = System.currentTimeMillis();

        long lastState = Long.MIN_VALUE;
        int unchanged = 0;
        int idleStreak = 0;
        for (int i = 0; i < maxSteps; i++) {
            // B128 (user m05245): the game's own frame for a player who is leaving the fight. Runs
            // before the idle test, because the escape is a PLAYER animation: the action queues are
            // empty for its whole duration, so an idle check that did not know about it would call
            // the fight settled two frames in and never let endBattle() happen.
            tickEscapeFrame();
            if (isIdle(gam) && !escapePending()) {
                // The engine applies the LAST effect in the update AFTER the queue empties: a
                // DamageAction is removed from the queue one tick before its damage lands. Treating
                // the first idle tick as settled made every prediction miss the final effect (the
                // Defect probe showed orb passive/evoke damage landing one drain later). Two
                // consecutive idle updates means the engine has really finished.
                idleStreak++;
                if (idleStreak >= 2) {
                    r.settled = true;
                    settledDrains++;
                    break;
                }
            } else {
                idleStreak = 0;
            }
            if (isAwaitingSelection()) {
                r.awaitingSelection = true;
                break;
            }
            if (managerFrozen()) {
                // B133 (user m06893): the engine's own frame schedule, not a measurement. In
                // AbstractDungeon.update() the room (and with it GameActionManager.update()) runs only
                // for screen NONE / NO_INTERACT / FTUE / BOSS_REWARD / HAND_SELECT, and for GRID /
                // CARD_REWARD only while PeekButton.isPeeking. Every other screen owns the frame.
                // Running the manager anyway is not a harmless extra tick: DiscoveryAction (the four
                // "<type> Potion" cards) regenerates its three cards on EVERY update, so each pump frame
                // with the reward screen up silently advances cardRandomRng by 3-5 -- and with Snecko
                // Eye every draw after that rolls different costs. Drive the screen instead: that is
                // exactly what a frame does while the screen is up.
                //
                // B151: this is now the ENGINE's own gate (isScreenUp), not a per-screen list. The
                // HAND_SELECT case of AbstractDungeon.update() runs room.update(), but room.update()
                // itself skips GameActionManager.update() while a screen is up, so a pump must not
                // advance the queue there either -- that is how a waiting action ran ahead of its
                // own answer.
                if (System.currentTimeMillis() - startedAt > maxMillis) {
                    r.stalled = true;
                    break;
                }
                ++screenFrames;
                pumpScreenSelf(1);
                continue;
            }
            if (System.currentTimeMillis() - startedAt > maxMillis) {
                r.stalled = true;
                break;
            }
            // The room's own per-frame object pump. This is NOT optional: DrawCardAction checks
            // SoulGroup.isActive() and RETURNS WITHOUT DRAWING while any cached soul is not ready
            // for reuse. Souls become ready in SoulGroup.update(), which the game calls from
            // AbstractRoom.update() once per frame -- and a frame never happens while we are
            // driving the engine from the main menu. Without this the opening draw stalls forever.
            long pumpStart = System.nanoTime();
            pumpRoomObjects();
            pumpNanos += System.nanoTime() - pumpStart;
            if (!scryArmed && (hasScry(gam.actions) || hasScry(gam.preTurnActions)
                    || gam.turnStartCurrentAction instanceof com.megacrit.cardcrawl.actions.utility.ScryAction)) {
                // Remember it even if the action completes before the screen is answered: a
                // power-triggered scry (Foresight) has no card play to mark the context.
                scryArmed = true;
            }
            // A card that presses End Turn (Vault) does it through the End Turn BUTTON:
            // PressEndTurnButtonAction -> callEndTurnEarlySequence() -> EndTurnButton.disable(true),
            // and the only thing that really ends the turn is one frame of the room's own update:
            // it sees AbstractPlayer.endTurnQueued, sets isEndingTurn, and calls room.endTurn().
            // No game loop runs inside the sandbox, so finish exactly that frame here.
            //
            // Skipping it used to leave the turn un-advanced AND the next turn without energy: the
            // per-turn energy grant is not an action at all, it is a side effect of the PlayerTurnEffect
            // CONSTRUCTOR, which DrawCardAction(..., true) instantiates when the turn-start block runs.
            // No turn start, no recharge: a Vault line read "turn 2->2 energy=0" (PLAN P2-1d-1).
            // B89 (user m26061): this used to fire only for cards on a whitelist
            // (Legal.PRESS_END_TURN_CARDS = {Vault}). Conclude presses the very same button through
            // PressEndTurnButtonAction -> callEndTurnEarlySequence -> EndTurnButton.disable(true), so
            // its turn never ended in the sandbox: the search kept planning turn-1 steps, the live
            // turn moved on without them, and the plan was dropped as "already in a new turn" -- the
            // last step never got played. Time Maze (a power) and any mod card that presses End Turn
            // had the same hole. The latch itself is now the evidence; the whitelist is only a hint.
            // B122 (user m02740): a button press that nothing has consumed yet is ALWAYS ours to
            // finish, even while suppressRoomEndTurn is set. 昏睡 (TrancePower.atStartOfTurn) presses
            // the button at the START of the turn that Driver.endTurn is draining, so the old condition
            // (which required !suppressRoomEndTurn) threw that press away and left endTurnQueued=true:
            // the sandbox kept playing the turn the live game had already ended.
            boolean pressNow = endTurnQueued(gam);
            if (!combatOver() && pressNow) {
                // B114: FIRST finish the queue the button just filled. disable(true) queued a
                // NewQueueCardAction; running it pushes the empty CardQueueItem sentinel, and the
                // manager answers THAT by calling callEndOfTurnActions() -- end-of-turn relics,
                // pre-card powers, orbs and every hand card's triggerOnEndOfTurnForPlayingCard()
                // (灼伤/Burn's damage lives there). Ending the room before the sentinel exists let
                // ClearCardQueueAction wipe it and skipped the whole block.
                if (runEndOfTurnQueue(gam, 1000L)) {
                    // B151 (user m13424/m13425): the end-of-turn block stopped on a selection screen
                    // that a queued action (e.g. 豹指导/SealGuide) opened. The real game is frozen on
                    // exactly that screen, so the answer must be given before the rest of the block
                    // runs. Leave the screen up and the press latched; the next drain finishes this
                    // same end turn once the caller has answered.
                    r.awaitingSelection = true;
                    r.combatOver = combatOver();
                    break;
                }
                AbstractPlayer pressed = AbstractDungeon.player;
                AbstractRoom room = AbstractDungeon.getCurrRoom();
                if (pressed != null) {
                    // What AbstractPlayer.updateInput() would have done on the next frame. The room's
                    // endTurn() below resets isEndingTurn, exactly like the game does.
                    pressed.endTurnQueued = false;
                    if (room != null) {
                        pressed.isEndingTurn = true;
                    }
                }
                if (room != null && !combatOver()) {
                    // Only the room's endTurn: pumping room.update() here changed the cost and the
                    // behaviour of every end turn (measured: a 12s boss search stopped winning), so
                    // the whole frame stays out of every other path.
                    room.endTurn();
                }
            }
            updates++;
            long beforeHash = measureCost ? sts1solver.mirror.Digest.progressHash() : 0L;
            if (measureCost) {
                for (int q = 0; q < gam.actions.size(); q++) {
                    actionsSeen++;
                    if (isPresentation(gam.actions.get(q))) {
                        presentationActions++;
                    }
                }
            }
            long updateStart = System.nanoTime();
            try {
                engineFrame(gam);
            } catch (Throwable t) {
                sts1solver.util.Log.errorEvery("engine-update-threw", 1000L, "engine update threw: phase=" + gam.phase
                        + " turnHasEnded=" + gam.turnHasEnded
                        + " queues=" + gam.actions.size() + "/" + gam.cardQueue.size()
                        + "/" + gam.monsterQueue.size()
                        + " player=" + (AbstractDungeon.player == null ? "null" : "ok")
                        + " hand=" + (AbstractDungeon.player == null || AbstractDungeon.player.hand == null
                                ? "null" : String.valueOf(AbstractDungeon.player.hand.size())), t);
                throw new RuntimeException(t);
            }
            if (sts1solver.mirror.World.anyInstalled()) {
                // B76 (m21391): a card the engine just exhausted comes back with the cost the live game
                // gives it (ExhaustCardEffect resets the attributes; we never run that effect).
                sts1solver.mirror.ExhaustAttrs.sync();
            }
            long updateTaken = System.nanoTime() - updateStart;
            updateNanos += updateTaken;
            if (measureCost) {
                String at = gam.currentAction == null ? "-"
                        : simpleName(gam.currentAction.getClass());
                long[] slot = actionNanos.get(at);
                if (slot == null) {
                    slot = new long[2];
                    actionNanos.put(at, slot);
                }
                slot[0] += updateTaken;
                slot[1]++;
            }
            if (measureCost && sts1solver.mirror.Digest.progressHash() == beforeHash) {
                wastedUpdates++;
            }
            r.steps++;
            // Every 4th step: a cheap progress hash. A finished action whose phase never advances
            // spins forever otherwise, which is the one failure mode that can hang a search node.
            if ((i & 3) == 0) {
                long now = sts1solver.mirror.Digest.progressHash();
                // B128: the escape animation is progress the digest cannot see (only its timer
                // moves, and at a small frame delta that takes ~50 frames -- long enough to trip
                // the stall counter and report a node as stuck mid-escape).
                if (now == lastState && !escapePending()) {
                    if (++unchanged >= 12) {
                        r.stalled = true;
                        stalledDrains++;
                        stallIters += i;
                        if (gam.turnHasEnded) {
                            stallWithEndTurnLatched++;
                        }
                        break;
                    }
                } else {
                    unchanged = 0;
                    lastState = now;
                }
            }
        }
        if (!r.settled && !r.awaitingSelection && !r.stalled) {
            r.timedOut = true;
        }
        r.combatOver = combatOver();
        return r;
    }

    /** B128 (user m05245): the player's own escape frame, the one the sandbox never runs. */
    private static int escapesSeen;

    /**
     * B128 (user m05245): run the frame that turns "the player is leaving" into "the fight is over".
     *
     * <p>烟雾弹/SmokeBomb is the only way the PLAYER leaves a fight: SmokeBomb.use() sets
     * {@code room.smoked}, {@code player.isEscaping} and {@code player.escapeTimer = 2.5f}. What
     * actually ends the fight is AbstractPlayer.updateEscapeAnimation(): it counts escapeTimer down by
     * the frame delta and, once it drops below zero, calls AbstractDungeon.getCurrRoom().endBattle().
     * Every other caller of endBattle() is a MONSTER dying (AbstractMonster.die/escape) -- that path
     * lives in the engine and always ran here. A player escape is the one ending that depended on the
     * render loop.
     *
     * <p>No game loop runs inside the sandbox, so the timer never moved. Measured (user's log
     * 2026-11-04 14:12): the plan's step 14 was 烟雾弹, the monster still had 20 HP in the trace, the
     * plan carried on with 农化 + 结束回合 and was reported as a WIN with potions=2 -- the escape
     * branch looked like a potion that costs 9 HP and does nothing, so a line could drink a second
     * potion, keep fighting and claim the fight was won.
     *
     * <p>Line for line the game's own updateEscapeAnimation() (minus the cosmetic drawX slide).
     * Sandbox only: in the live game the real frame already does this. Bounded by the caller's loop.
     */
    private static void tickEscapeFrame() {
        if (!sts1solver.mirror.World.anyInstalled()) {
            return;
        }
        AbstractPlayer p = AbstractDungeon.player;
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (p == null || room == null) {
            return;
        }
        if (p.escapeTimer != 0.0f) {
            p.escapeTimer -= simDelta;
        }
        if (p.escapeTimer < 0.0f) {
            room.endBattle();
            p.flipHorizontal = false;
            p.isEscaping = false;
            p.escapeTimer = 0.0f;
            // The sandbox says what that beat was (m03996 #7): without this line an escape is
            // invisible in the log and looks like a fight that simply stopped.
            escapesSeen++;
            // One search expands hundreds of smoke-bomb nodes, so this line is throttled: Log writes
            // autoflush on the render thread and a line per node would cost frames.
            if (Log.allow("sandbox-escape", 2000L)) {
                Log.info("sandbox: the player left the fight (escape animation finished): room.isBattleOver=true"
                        + " smoked=" + room.smoked + " turn=" + GameActionManager.turn
                        + " hp=" + p.currentHealth + "/" + p.maxHealth + " escapes=" + escapesSeen);
            }
        }
    }

    /** B128: is the player's escape animation still running (so a drain must not call the fight settled)? */
    private static boolean escapePending() {
        if (!sts1solver.mirror.World.anyInstalled()) {
            return false;
        }
        AbstractPlayer p = AbstractDungeon.player;
        return p != null && (p.isEscaping || p.escapeTimer != 0.0f);
    }

    /** Advances the room-owned objects that the game would advance once per render frame. */
    private static void pumpRoomObjects() {
        try {
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            if (room != null && room.souls != null) {
                room.souls.update();
            }
        } catch (Throwable t) {
            // A presentation pump must never be able to break a simulation step.
        }
    }

    /**
     * B114: run the game's own END-OF-TURN CARD QUEUE, the frame the sandbox never ran.
     *
     * <p>Ending the turn -- the button, and every card that presses it (Vault, Conclude, 按兵不动) --
     * goes PressEndTurnButtonAction -> GameActionManager.callEndTurnEarlySequence() ->
     * EndTurnButton.disable(true), which queues a {@code NewQueueCardAction} and sets
     * {@code player.endTurnQueued}. That action then pushes an EMPTY {@code CardQueueItem} sentinel;
     * {@code GameActionManager.getNextAction()} answers the sentinel by calling
     * {@code callEndOfTurnActions()}, which applies end-of-turn relics, pre-card powers, orbs and
     * every hand card's {@code triggerOnEndOfTurnForPlayingCard()} -- that is where 灼伤/Burn deals
     * its damage. Only after all of that is the card queue empty and AbstractPlayer.updateInput()
     * lets the room call endTurn().
     *
     * <p>The sandbox used to call {@code room.endTurn()} the moment it saw the latch, i.e. before the
     * sentinel existed; ClearCardQueueAction (queued by room.endTurn()) then wiped it and the whole
     * end-of-turn block was silently skipped. Measured live vs panel: 3 Burns in hand cost 6 Block,
     * the leftover 15-6=9 let exactly 3 of the Guardian's 4x3 through -> panel showed 41, the game 38.
     *
     * <p>Bounded by iterations and wall clock; a presentation pump must never hang a simulation step.
     */
    private static boolean runEndOfTurnQueue(GameActionManager gam, long capMillis) {
        if (gam == null) {
            return false;
        }
        long startedAt = System.currentTimeMillis();
        long cap = Math.max(50L, Math.min(capMillis, 1000L));
        for (int i = 0; i < 512; i++) {
            // B151 (user m13424/m13425): the real game freezes the action manager while a screen is
            // up -- AbstractRoom.update() calls GameActionManager.update() only when
            // !AbstractDungeon.isScreenUp. A queue pump that ignores the screen runs the NEXT action
            // while the previous one is still waiting for its answer. Measured on 豹指导 (SealGuide,
            // queued by Tang): its PlayCardAction opened the "打出" picker and the very next update
            // found wereCardsRetrieved false with an EMPTY selectedCards, so it took ZERO cards
            // (PlayCardAction.java:100-108) -- the free card was never played and live vs panel
            // diverged from that step on (block 16 vs 2, NongAbacusPower2 missing). Stop here: the
            // caller answers the screen, and the still-set end-turn latch resumes this same end turn.
            if (managerFrozen()) {
                return true;
            }
            if (gam.actions.isEmpty() && gam.cardQueue.isEmpty() && gam.currentAction == null) {
                return false;
            }
            if (System.currentTimeMillis() - startedAt > cap) {
                endOfTurnQueueTimeouts++;
                return managerFrozen();
            }
            pumpRoomObjects();
            try {
                engineFrame(gam);
                updates++;
            } catch (Throwable t) {
                sts1solver.util.Log.errorEvery("endturn-queue-threw", 1000L,
                        "end-of-turn card queue threw: phase=" + gam.phase
                                + " queues=" + gam.actions.size() + "/" + gam.cardQueue.size(), t);
                return managerFrozen();
            }
        }
        return managerFrozen();
    }

    /**
     * B151 (user m13424/m13425): the action manager's own frame gate.
     *
     * <p>{@code AbstractRoom.update()} advances the manager only while
     * {@code !AbstractDungeon.isScreenUp}; the HAND_SELECT case of {@code AbstractDungeon.update()}
     * still runs {@code room.update()} (that is what {@link #roomBlockedByScreen()} reads), but the
     * manager call inside it is skipped all the same. Pumps here drive the manager DIRECTLY, so they
     * must use this gate rather than the room one: otherwise a picker that is still waiting for its
     * answer lets the next queued action run.
     */
    private static boolean managerFrozen() {
        // Two conditions, both taken from the engine itself:
        //  - AbstractDungeon.update() calls room.update() only for screen NONE / NO_INTERACT / FTUE /
        //    BOSS_REWARD / HAND_SELECT, and for GRID / CARD_REWARD only while the Peek button is held
        //    (AbstractDungeon.java:1998-2067). Every other screen owns that frame.
        //  - room.update() itself calls GameActionManager.update() only while !isScreenUp
        //    (AbstractRoom.java:254-259).
        // B151 measured why the second half matters: a leftover previousScreen = NONE made
        // closeCurrentScreen() restore "screen NONE" with isScreenUp left TRUE, and a gate that only
        // looked at the screen list would have run the manager under a screen the game considers open.
        if (AbstractDungeon.isScreenUp) {
            return true;
        }
        AbstractDungeon.CurrentScreen s = AbstractDungeon.screen;
        if (s == null) {
            return false;
        }
        switch (s) {
            case NONE:
            case NO_INTERACT:
            case FTUE:
            case BOSS_REWARD:
            case HAND_SELECT:
                return false;
            case GRID:
            case CARD_REWARD:
                return !peekingNow();
            default:
                return true;
        }
    }

    /**
     * B116 (m00748): {@code usingCard} is a latch the engine only clears in the EXECUTING_ACTIONS
     * tail of {@code GameActionManager.update()}. A card-queue refusal clears the queue item but not
     * the latch, so this exact shape -- latch set, every queue empty, nothing left to run, turn not
     * over -- is the fingerprint of "the engine threw the play away".
     */
    private static boolean leftoverUsingCard(GameActionManager gam) {
        return gam != null && gam.usingCard && gam.currentAction == null
                && gam.actions.isEmpty() && gam.cardQueue.isEmpty() && !gam.turnHasEnded;
    }

    private static boolean isIdle(GameActionManager gam) {
        if (!turnTransitionFinished(gam)) {
            return false;
        }
        // B188 (user m00750/m00778/m00820): a latched End Turn press is not a finished state.
        // See endTurnPressWaiting() for the measurement; this is the only place it is enforced.
        if (endTurnPressWaiting(gam)) {
            return false;
        }
        // GameActionManager.update() moves the next action into currentAction at the end of a frame
        // and runs it on the following one, so an empty queue is one tick early. Draining on that
        // tick left the last action (a heal, a block gain) stranded in currentAction, where nothing
        // ever ran it again -- the same one-frame gap as Gameplay.engineSettled() (B61).
        if (gam.currentAction != null && !gam.currentAction.isDone) {
            return false;
        }
        return gam.actions.isEmpty() && gam.cardQueue.isEmpty() && gam.monsterQueue.isEmpty()
                && gam.preTurnActions.isEmpty() && !gam.usingCard;
    }

    /**
     * B188 (user m00750/m00778/m00820): a press of the End Turn button that nothing has consumed yet
     * means the turn transition has NOT finished, and a drain must not call the board settled.
     *
     * <p>The failure this closes, measured on 计划妥当 (Well Laid Plans, which is the engine's
     * RetainCardPower): Driver.endTurn() runs the end-of-turn card queue first, and the retain screen
     * opens from inside that block -- a card being asked which one to keep. B151 deliberately returns
     * early there with the press latched (player.endTurnQueued = true, turnHasEnded still false), so
     * the answer could come before the rest of the block ran. Answering such a screen closes it with
     * an EMPTY action queue, and the next drain reached isIdle() -- which looked only at the queues --
     * declared the board settled, and returned. The latched press was never consumed: the turn never
     * ended, the state stayed "turn 3, player still owes an end turn", and every later end turn was
     * refused with "the end turn did not finish the turn (turn stayed 3)". In the live game the same
     * shape is the freeze the user reported: the picker is answered, and nothing moves afterwards.
     *
     * <p>So the press is part of the state, exactly like a non-empty queue: while it waits, a drain
     * keeps running (it reaches the press handling in {@link #drain(int, long)} within one frame and
     * finishes the turn there), and {@link #settleBlock()} reports "action-busy" instead of letting
     * the search canonicalise a half-ended turn. A decided fight is exempt: the game has no turn left
     * to end there, and B128's escape animation is handled separately by {@link #escapePending()}.
     */
    private static boolean endTurnPressWaiting(GameActionManager gam) {
        if (gam == null || !endTurnQueued(gam) || combatOver()) {
            return false;
        }
        if (Log.allow("endturn-press-waiting", 4000L)) {
            Log.info("an end turn press is still latched (turnHasEnded=" + gam.turnHasEnded
                    + " turn=" + GameActionManager.turn + " actions=" + gam.actions.size()
                    + "/" + gam.cardQueue.size() + "): the board is not settled until it is consumed");
        }
        return true;
    }


    /**
     * B176 (user m22904): did the engine finish everything it had pending? This is the predicate
     * behind the search's canonical position key: {@code Digest.stable} strips the action manager's
     * phase/queues (ph/q/use/end), which are NOT part of the board, so two samples of one board
     * differ only in those fields -- merging them is only safe when the sample came from a finished
     * board. A false result (including any read failure) means do not canonicalise.
     */
    public static boolean settled() {
        return settleBlock().isEmpty();
    }

    /**
     * B176 diagnostics: "" when the engine had finished, else the name of the check that says it
     * had not. The search keeps its raw fingerprint whenever this is non-empty, so the string is
     * only ever read for the log line.
     */
    public static String settleBlock() {
        try {
            GameActionManager gam = AbstractDungeon.actionManager;
            if (gam == null) {
                return "";
            }
            if (!isIdle(gam)) {
                return gam.turnHasEnded ? "turn-ended" : "action-busy";
            }
            if (isAwaitingSelection()) {
                return "selection";
            }
            if (AbstractDungeon.isScreenUp) {
                return "screen";
            }
            if (AbstractDungeon.player != null && AbstractDungeon.player.limbo != null
                    && !AbstractDungeon.player.limbo.isEmpty()) {
                return "limbo";
            }
            if (AbstractDungeon.player != null && AbstractDungeon.player.isEndingTurn) {
                return "ending-turn";
            }
            return "";
        }
        catch (Throwable throwable) {
            return "error";
        }
    }

    private static boolean turnTransitionFinished(GameActionManager gam) {
        if (!gam.turnHasEnded) {
            return true;
        }
        try {
            if (playerDead()) {
                return true;
            }
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            if (room != null && room.isBattleOver) {
                return true;
            }
            if (AbstractDungeon.getMonsters() == null
                    || AbstractDungeon.getMonsters().areMonstersBasicallyDead()) {
                return true;
            }
        } catch (Throwable ignored) {
            // Unreadable counts as finished so the drain cannot spin forever.
        }
        return false;
    }

    // ------------------------------------------------------------------ actions

    /** Plays a card through the engine's real card pipeline (energy, piles, hooks, everything). */
    /**
     * Frame delta used by the sandbox.
     *
     * <p>Most actions only need "one tick", so a big delta keeps the search fast. But an action that
     * opens a card-select screen marks itself done in the SAME tick it opens it when the delta is
     * larger than the action's duration -- and the selection is then never consumed (observed with
     * "Prepared": the discard silently did nothing). Card plays therefore run at a small delta, so
     * the action survives the tick that opened the screen and can consume the answer on the next one.
     */
    /**
     * Frame delta used for ordinary sandbox steps.
     *
     * <p>Larger than any game action duration, so an action finishes in one update instead of several.
     * It is kept only for A/B measurement ({@code -Dsts1solver.cardDelta=fast}); B181 showed that a
     * single update this long skips every state change an action makes in a LATER time window (see
     * {@link #SELECTION_DELTA} and the sandbox default below).
     */
    private static final float FAST_DELTA = 20.0f;
    /**
     * Smaller than {@code Settings.ACTION_DUR_XFAST}: an action that opens a card-select screen ticks
     * its own duration in the same update, and if that duration reaches zero it is marked done before
     * the answer exists. Any action-level duration would still be consumed by this one; the smallest
     * game-action duration wins.
     */
    private static final float SELECTION_DELTA = 0.05f;
    /**
     * B181 (user m24423/m24488): every sandbox step runs on {@link #SELECTION_DELTA}, not
     * {@link #FAST_DELTA}.
     *
     * <p>An action is free to change combat state in a later update than the first one. Hexaghost's
     * Inferno queues ONE {@code BurnIncreaseAction}: the update that sees {@code duration == 3.0}
     * upgrades the burns already in the piles, and a later update ({@code duration < 1.5}) creates
     * three new ones. With a 20 s frame the very first tick took 3.0 to -17, the action marked itself
     * done, and the sandbox fought the rest of the battle three burns short -- on turn 10 the hand the
     * panel predicted was not the hand the game dealt (solver.log 22:11:37, exactly three Burn+
     * missing). The card-play path had already reached the same conclusion (see
     * {@code smallDeltaForPlay}); turns now use the same frame length, so no time window is skipped.
     */
    private static float simDelta = SELECTION_DELTA;

    public static void setSimDelta(float value) {
        simDelta = value;
    }

    static float simDelta() {
        return simDelta;
    }

    /**
     * What one frame lasts while a select screen is up.
     *
     * <p>The live game stops pumping the action queue the moment {@code AbstractDungeon.isScreenUp} is
     * set: {@code AbstractRoom.update} only calls {@code actionManager.update()} while no screen is up.
     * An action that opens a screen therefore never sees its own duration move while it waits for the
     * answer. The sandbox cannot lean on that: {@link #FAST_DELTA} is larger than every action duration,
     * so the very update that opened the screen also takes the duration below zero and the action marks
     * itself done -- the 0.5 s keep-a-card screen (RetainCardsAction), a scry (ScryAction) or Nilry's
     * Codex then has nobody left to consume the answer (B83, B84). Handing the engine the game's own
     * frame length for exactly the frames a screen is up restores that freeze, and it does so without
     * naming a card, relic or power -- which is what keeps it working for modded triggers.
     */
    private static final float SCREEN_DELTA = 1.0f / 60.0f;

    /**
     * Upper bound on combinations scanned for one multi-select screen (P2-57).
     *
     * <p>Choosing 3 of 10 is 120 combinations and they are all worth branching on; choosing 5 of 30
     * is 142506 and only a handful can ever be searched, so the pool shrinks to the best few cards
     * first (the list is already value-ordered, so the tail is what gets dropped).
     */
    private static final long COMBINATION_SCAN = 4000L;

    /** B139: how many more answered screens this session still gets a trace line for. */
    private static int selectionTraceLeft = 60;

    /** Diagnostics: how many engine updates were handed {@link #SCREEN_DELTA}. */
    private static long screenFrames;
    /** Test hook: null = use the real rule, TRUE/FALSE = force it (A/B gates). */
    private static Boolean screenDeltaForTest;

    public static long screenFrames() {
        return screenFrames;
    }

    public static void resetScreenFrames() {
        screenFrames = 0;
    }

    public static void setScreenDeltaForTest(Boolean value) {
        screenDeltaForTest = value;
    }

    /**
     * The frame length the engine is handed for this update.
     *
     * <p>Only ever reachable inside a sandbox window: the proxy that answers {@code getDeltaTime} is
     * installed by {@link #begin()} and removed by {@link #end()}, and the installed-world check makes
     * that explicit so the live game can never be handed a simulated frame.
     */
    static float frameDelta() {
        if (screenDeltaApplies()) {
            ++screenFrames;
            return SCREEN_DELTA;
        }
        // B184 (user m01136): an audited action gets the frame length that lands on its next time
        // window instead of the uniform 0.05 s. The screen rule above stays first: a frame that opens
        // a picker must hand the picker the game's own frame length, plan or no plan.
        if (FramePlan.planned()) {
            return FramePlan.delta();
        }
        return simDelta;
    }

    /**
     * One engine update, with the frame length the current action needs (B184).
     *
     * <p>Everything else -- the end turn, the screen pumps, the replay settle -- keeps calling
     * {@code gam.update()} directly and therefore keeps the uniform frame. Only the two paths that
     * drive a whole step (the step drain and the end-of-turn card queue) go through here.
     */
    private static void engineFrame(GameActionManager gam) {
        FramePlan.begin(gam);
        try {
            gam.update();
        } finally {
            FramePlan.end();
        }
    }

    private static boolean screenDeltaApplies() {
        if (!sts1solver.mirror.World.anyInstalled()) {
            return false;
        }
        if (screenDeltaForTest != null) {
            return screenDeltaForTest.booleanValue();
        }
        try {
            return AbstractDungeon.isScreenUp;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Whether this play needs the small frame delta.
     *
     * <p>The hardcoded {@code PROMPT_IDS} list only ever covered the cards we had already seen open
     * a screen, so any card it missed -- Headbutt, Hologram, Exhume, Liquid Memories, and every mod
     * card that picks from a pile -- was played at {@link #FAST_DELTA}. Its action then ticked its own
     * duration to zero in the very update that opened the screen, marked itself done, and the
     * selection was never consumed: the card silently did nothing while the screen stayed open.
     *
     * <p>Deciding by card id cannot be right in general (mod content, and even a vanilla card only
     * opens a screen in some states), so the safe default is the small delta for every play. The
     * fast delta is kept behind {@code -Dsts1solver.cardDelta=fast} for A/B measurement only.
     */
    /** Count of screens that opened with no action left to consume the answer (must stay 0). */
    public static int orphanSelections;

    public static int orphanCount() {
        return orphanSelections;
    }

    public static void resetOrphans() {
        orphanSelections = 0;
    }

    private static boolean orphanSelection() {
        try {
            GameActionManager gam = AbstractDungeon.actionManager;
            if (gam == null) {
                return true;
            }
            if (!gam.actions.isEmpty()) {
                return false;
            }
            return gam.currentAction == null || gam.currentAction.isDone;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String describeCurrentAction() {
        try {
            GameActionManager gam = AbstractDungeon.actionManager;
            Object a = gam == null ? null : gam.currentAction;
            return a == null ? "-" : a.getClass().getName();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Test override: null keeps the production rule; true restores the old buggy behaviour. */
    private static Boolean fastDeltaForTest;

    public static void setFastDeltaForTest(Boolean value) {
        fastDeltaForTest = value;
    }

    private static boolean smallDeltaForPlay(AbstractCard card) {
        boolean fastOnly = fastDeltaForTest != null
                ? fastDeltaForTest.booleanValue()
                : "fast".equals(System.getProperty("sts1solver.cardDelta"));
        if (fastOnly) {
            return sts1solver.search.Legal.needsSmallDelta(card.cardID);
        }
        return true;
    }

    public static Drain play(AbstractCard card, AbstractMonster target, int energyOnUse) {
        Drain d = new Drain();
        GameActionManager gam = AbstractDungeon.actionManager;
        if (card == null || gam == null) {
            d.timedOut = true;
            return d;
        }
        boolean selectionBefore = isAwaitingSelection();
        // B116 (m00748): the live UI never queues a card the game would turn down -- AbstractPlayer
        // checks canUse() and shows a ThoughtBubble instead. Queuing one here is not harmless:
        // GameActionManager.getNextAction() takes the refusal branch, removes the queue item WITHOUT
        // calling useCard, and leaves usingCard=true. Only the EXECUTING_ACTIONS tail of update()
        // ever clears that flag, and after a refusal nothing else is queued -- so the flag stays true,
        // isIdle() can never be true again, and every later drain burns its whole budget on a corpse
        // state (the replay of 按兵不动+ after an attack died exactly here).
        lastRefusal = null;
        if (!canUse(card, AbstractDungeon.player, target)) {
            d.refused = true;
            lastRefusal = "canUse() said no: " + refusalReason(card);
            Log.warnEvery("play-refused-" + card.cardID, 30000L, "play refused before queueing: "
                    + card.cardID + " (" + card.name + ") " + lastRefusal);
            return d;
        }
        card.energyOnUse = energyOnUse;
        // Small frames while a card is being played: a card that opens a card-select screen queues an
        // action that must survive the tick that opened it, or it marks itself done and the selection
        // is never consumed (observed: "Prepared" discarded nothing).
        float savedDelta = simDelta;
        simDelta = smallDeltaForPlay(card) ? SELECTION_DELTA : FAST_DELTA;
        try {
            gam.addCardQueueItem(new CardQueueItem(card, target, energyOnUse));
            // B102: a drained queue is the state; a queue that ran out of time is not.
            Drain raw = drain();
            Drain drained = completeIfTruncated(raw);
            if (drained != null && !drained.settled && leftoverUsingCard(gam)) {
                // The engine refused a play we thought legal: same corruption as above, one layer
                // deeper. Name it, then put the flag back so the state stays usable.
                drained.refused = true;
                lastRefusal = "the engine dropped the queued play (usingCard left set): " + refusalReason(card);
                Log.errorEvery("play-refused-engine-" + card.cardID, 30000L, "the engine refused a queued play"
                        + " (usingCard left true): " + card.cardID + " (" + card.name + ") " + refusalReason(card)
                        + " canUse=" + canUse(card, AbstractDungeon.player, target)
                        + " queues=" + gam.actions.size() + "/" + gam.cardQueue.size(), null);
                gam.usingCard = false;
            }
            if (!selectionBefore && isAwaitingSelection()) {
                drained.awaitingSelection = true;
                if (orphanSelection()) {
                    // The screen is up but nothing is waiting on it: the action that opened it
                    // already marked itself done, so the answer can never be consumed and the
                    // card's effect was lost. Only a frame delta larger than the action's own
                    // duration can cause this (see smallDeltaForPlay).
                    orphanSelections++;
                    Log.warn("orphan selection after playing " + card.cardID + " (" + card.name
                            + "): screen=" + AbstractDungeon.screen
                            + " currentAction=" + describeCurrentAction()
                            + " queued=" + gam.actions.size()
                            + " -- the action died before it could take the answer");
                }
            }
            return drained;
        } finally {
            simDelta = savedDelta;
        }
    }

    /**
     * B83: does ending the turn have to run with {@link #SELECTION_DELTA}?
     *
     * <p>End-of-turn powers can queue an action that opens a card-select screen. Well-Laid Plans
     * does exactly that: {@code RetainCardPower.atEndOfTurn} (RetainCardPower.java:35-37) queues
     * {@code RetainCardsAction}, whose first update opens the screen AND ticks its own 0.5 s duration
     * (RetainCardsAction.java:29-43). A {@link #FAST_DELTA} frame therefore takes that duration to
     * -19.5, the action marks itself done, and the screen is left with nothing to consume it: the
     * chosen card is never marked {@code retain}, the end-of-turn discard throws it away, and the next
     * turn the card the plan wants to play is simply not in hand.
     *
     * <p>The check is cheap and conservative: in the base game only RetainCardPower asks for a card at
     * end of turn, so the class name is enough; any power that is not shipped by the base game is
     * treated as "may ask", because a mod power can open any screen.
     *
     * <p>B181: the answer no longer decides the frame -- every end turn runs on the small frame, so this
     * predicate is only what the diagnostic counter (and the A/B hook) reads now.
     */
    private static Boolean endTurnSmallFramesForTest;

    /** Test override: false reproduces the old big-frame turn end, true forces the small frame. */
    public static void setEndTurnSmallFramesForTest(Boolean value) {
        endTurnSmallFramesForTest = value;
    }

    private static boolean needsSelectionDeltaAtEndOfTurn() {
        if (endTurnSmallFramesForTest != null) {
            return endTurnSmallFramesForTest.booleanValue();
        }
        try {
            AbstractPlayer p = AbstractDungeon.player;
            if (p == null || p.hand == null || p.hand.isEmpty()) {
                return false;
            }
            for (int i = 0; i < p.powers.size(); i++) {
                com.megacrit.cardcrawl.powers.AbstractPower power = p.powers.get(i);
                if (power == null) {
                    continue;
                }
                String cls = power.getClass().getName();
                if (cls.indexOf("Retain") >= 0) {
                    return true;
                }
                if (!sts1solver.util.Provenance.isGame(power.getClass())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // unreadable state: no power named
        }
        return false;
    }

    /** B83: how many turn ends ran with the small frame because a power can ask for a card. */
    private static int endTurnSlowFrames;

    public static int endTurnSlowFrameCount() {
        return endTurnSlowFrames;
    }

    public static void resetEndTurnSlowFrames() {
        endTurnSlowFrames = 0;
    }

    /** Names of the first few queued actions, for diagnostics. */
    public static String queueNames(int max) {
        try {
            GameActionManager gam = AbstractDungeon.actionManager;
            if (gam == null || gam.actions == null) {
                return "-";
            }
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < gam.actions.size() && i < max; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                Object act = gam.actions.get(i);
                sb.append(act == null ? "null" : act.getClass().getSimpleName());
            }
            return sb.append(']').toString();
        } catch (Throwable t) {
            return "?";
        }
    }


    /**
     * B142 (user m10519): press the game's own End Turn button, exactly like a click does.
     *
     * <p>The live autopilot used to only set {@code player.endTurnQueued = true}. That flag does end
     * the turn, but it skips {@code EndTurnButton.disable(true)} -- and that method is the ONLY thing
     * that queues the {@code NewQueueCardAction} whose empty {@code CardQueueItem} sentinel makes
     * {@code GameActionManager.getNextAction()} call {@code callEndOfTurnActions()}. Without the
     * sentinel the live fight skipped end-of-turn relics, pre-card powers, orbs and every hand card's
     * {@code triggerOnEndOfTurnForPlayingCard()} (Burn/Decay/Regret), while the sandbox has run them
     * since B114. Measured (2 robbers, turn 1->2): 腐朽 stayed in the live hand, so the hand 唐 picked
     * from differed, the whole turn diverged from the plan and the plan's card answers went unused.
     *
     * <p>The button is a clone inside an installed world (ClonePolicy copies ui.buttons), so pressing
     * it here can never hide or disable the player's real button.
     *
     * @return true when the real button was pressed; false when only the button's effects were
     *         reproduced (no overlay, e.g. a headless world)
     */
    public static boolean pressEndTurnButton() {
        com.megacrit.cardcrawl.ui.buttons.EndTurnButton button =
                AbstractDungeon.overlayMenu == null ? null : AbstractDungeon.overlayMenu.endTurnButton;
        if (button != null) {
            try {
                button.disable(true);
                return true;
            } catch (Throwable t) {
                sts1solver.util.Log.errorEvery("endturn-button", 1000L,
                        "pressing the end turn button threw", t);
            }
        }
        GameActionManager gam = AbstractDungeon.actionManager;
        if (gam != null) {
            try {
                gam.addToBottom(new com.megacrit.cardcrawl.actions.utility.NewQueueCardAction());
            } catch (Throwable t) {
                sts1solver.util.Log.errorEvery("endturn-sentinel", 1000L,
                        "could not queue the end-of-turn sentinel", t);
            }
        }
        if (AbstractDungeon.player != null) {
            AbstractDungeon.player.endTurnQueued = true;
        }
        return false;
    }

    /** Ends the player's turn exactly the way the End Turn button does, then resolves everything. */
    public static Drain endTurn() {
        return endTurn(1600L);
    }

    public static Drain endTurn(long millisCap) {
        Drain d = new Drain();
        GameActionManager gam = AbstractDungeon.actionManager;
        if (gam == null) {
            d.timedOut = true;
            return d;
        }
        // B151: the game ignores the End Turn button while a screen is up
        // (EndTurnButton.update: "if (!this.isDisabled && !AbstractDungeon.isScreenUp)"). A direct end
        // turn asked for now cannot happen, so it is refused with a reason instead of being queued --
        // the alternative (latch it and end the turn after the answer) invents a press the player
        // never made. The end turn that DOES have to wait is the other case: a screen opened by an
        // action of the end-of-turn block itself, which returns below.
        if (isAwaitingSelection()) {
            d.awaitingSelection = true;
            d.refused = true;
            d.timedOut = true;
            lastRefusal = "a selection screen is open; the game does not end the turn there";
            sts1solver.util.Log.warnEvery("endturn-screen-open", 30000L,
                    "end turn refused: " + lastRefusal + " kind=" + selectionKind());
            return d;
        }
        int turnBefore = GameActionManager.turn;
        int nonRetained = countNonRetained(AbstractDungeon.player.hand);
        int discardBefore = AbstractDungeon.player.discardPile.size();
        int exhaustBefore = AbstractDungeon.player.exhaustPile.size();
        // B114: press the button before ending the turn. The button's NewQueueCardAction is what
        // makes the manager run callEndOfTurnActions() (end-of-turn relics, pre-card powers, orbs and
        // hand-card triggers such as 灼伤/Burn's damage); jumping straight to endTurn() skipped them,
        // so a plain "end turn" plan step under-charged the player exactly like a Vault step did.
        try {
            gam.addToBottom(new com.megacrit.cardcrawl.actions.utility.NewQueueCardAction());
        } catch (Throwable t) {
            sts1solver.util.Log.errorEvery("endturn-sentinel", 1000L,
                    "could not queue the end-of-turn sentinel", t);
        }
        if (runEndOfTurnQueue(gam, millisCap)) {
            // B151 (user m13424/m13425): a queued action opened a selection screen before the
            // end-of-turn block ran. Measured (三奴隶贩子, 5th turn): 唐 (TangPower) queued 豹指导
            // (SealGuide), whose PlayCardAction opens the "打出" picker; the old code kept pumping the
            // manager with the screen up, the action's next update saw wereCardsRetrieved == false and
            // an empty selectedCards and played ZERO cards (PlayCardAction.java:100-108). Live the
            // game freezes the queue there (AbstractRoom.update: !isScreenUp) until the card is picked,
            // then plays it. So stop at the same point: keep the press latched like EndTurnButton's
            // disable() does, and let the caller answer the screen; its drain() then finishes this
            // same end turn. Ending the room here instead would skip both the free card and the
            // end-of-turn block.
            try {
                if (AbstractDungeon.player != null) {
                    AbstractDungeon.player.endTurnQueued = true;
                }
            } catch (Throwable ignored) {
                // no player: the fight is over, nothing is waiting
            }
            Drain waiting = new Drain();
            waiting.awaitingSelection = true;
            waiting.combatOver = combatOver();
            return waiting;
        }
        // TWO calls, and both are required. endTurn() only latches turnHasEnded; the room's
        // endTurn() queues ClearCardQueue + DiscardAtEndOfTurn. Doing only the first never advances
        // the turn; doing only the second never discards the hand.
        gam.endTurn();
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room != null) {
            room.endTurn();
        }
        suppressRoomEndTurn = true;
        float savedDelta = simDelta;
        // B181: every end turn runs on the SMALL frame, the same one a card play uses. An action is
        // free to change combat state in a later update than the one that queued it (Hexaghost's
        // BurnIncreaseAction upgrades the existing burns at duration == 3.0 and only creates its
        // three new ones below 1.5), so a frame longer than those windows silently drops half of the
        // action. The flag below is the A/B hook only: FALSE restores the pre-B181 big frame so the
        // gates can still show the bug the small frame fixes.
        boolean smallEndTurn = endTurnSmallFramesForTest != null
                ? endTurnSmallFramesForTest.booleanValue()
                : true;
        if (smallEndTurn && needsSelectionDeltaAtEndOfTurn()) {
            ++endTurnSlowFrames;
        }
        simDelta = smallEndTurn ? SELECTION_DELTA : FAST_DELTA;
        Drain drained;
        try {
            // B102: a drained queue is the state; a queue that ran out of time is not.
            drained = completeIfTruncated(drain(4000, Math.max(50L, millisCap)));
            // B125 (m03996): an end turn that leaves the turn where it was is not an end turn.
            // The exploit this closes is real: in a fight whose monsters are all dying or escaping
            // the game skips the turn increment (getNextAction skips the new-turn block when
            // turnHasEnded and areMonstersBasicallyDead()), so every further end turn was a free
            // no-op that the search chained as if it were a decision -- the panel then showed four
            // end turns in one turn row. Give the queue one more frame budget first: a slow enemy
            // turn is not a stuck one.
            // B189 (user m01083): a pending answer is not a stuck turn -- do not spend a second
            // drain on a screen that is already up (it would return with steps=0 and hide the
            // first drain's count).
            if (!drained.awaitingSelection && !combatOver()
                    && GameActionManager.turn == turnBefore) {
                drained = completeIfTruncated(drain(4000, Math.max(50L, millisCap)));
            }
        } finally {
            simDelta = savedDelta;
            suppressRoomEndTurn = false;
        }
        drained.turnAdvanced = endTurnFinished(turnBefore, GameActionManager.turn, combatOver());
        drained.combatOver = combatOver();
        // B189 (user m01083): an end turn that stopped on a pick screen the end-of-turn block
        // opened (计划妥当's retain picker, 豹指导's play picker, ...) has NOT failed: the screen is
        // waiting for its answer, and the answer's drain finishes this same end turn. B125's
        // "the turn counter did not move" rule is about a turn that cannot finish, so it must not
        // turn a pending answer into a refusal -- that refusal is what poisoned the whole
        // 结束回合（选：X） step and truncated the printed plan at the step before it.
        if (drained.awaitingSelection) {
            sts1solver.util.Log.warnEvery("endturn-waiting", 30000L,
                    "end turn stopped on a pick screen (turn " + turnBefore + " -> "
                            + GameActionManager.turn + "): the answer finishes it");
        } else if (!drained.turnAdvanced) {
            drained.timedOut = true;
            // B125 (m03996 #3/#4): a fight that is over has no end turn, and a turn that did not
            // advance must not be recorded as a decision. `Legal.applyOrRefuse` turns this into a
            // refusal, so the search does not chain no-op end turns and the log says why.
            drained.refused = true;
            lastRefusal = "the end turn did not finish the turn (turn stayed " + turnBefore
                    + ", combatOver=" + drained.combatOver + ")";
            sts1solver.util.Log.warnEvery("endturn-refused", 30000L,
                    "end turn refused: " + lastRefusal + " steps=" + drained.steps
                            + " settled=" + drained.settled + " stalled=" + drained.stalled
                            + " awaitingSelection=" + drained.awaitingSelection);
        }
        int discardAfter = AbstractDungeon.player.discardPile.size();
        int exhaustAfter = AbstractDungeon.player.exhaustPile.size();
        if (nonRetained > 0 && discardAfter == discardBefore && exhaustAfter == exhaustBefore) {
            drained.discardMissing = true;
        }
        return drained;
    }

    /**
     * B125 (m03996): what counts as a finished end turn.
     *
     * <p>A turn that ends normally moves the turn counter forward. A turn that ends the FIGHT does
     * not: the game skips the new-turn block when the monsters are all dying or escaping, so the
     * counter stays where it was -- that one is finished all the same. Everything else (a queue
     * that ran out of time, a screen nobody answered, a decision that resolved to nothing) left the
     * fight in the middle of the turn it was already in, and must be refused rather than recorded:
     * the search used to chain such steps as if each one were a decision, and the panel then drew
     * them all inside one turn row.
     */
    public static boolean endTurnFinished(int turnBefore, int turnAfter, boolean fightOver) {
        return turnAfter != turnBefore || fightOver;
    }

    private static int countNonRetained(com.megacrit.cardcrawl.cards.CardGroup g) {
        if (g == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < g.group.size(); i++) {
            AbstractCard c = g.group.get(i);
            if (c != null && !c.retain && !c.selfRetain) {
                n++;
            }
        }
        return n;
    }

    /**
     * Is the fight actually finished?
     *
     * <p>"All monsters are basically dead" is NOT the same thing. A Slime Boss that is about to
     * SPLIT, a reviving boss, or a summon that is still queued all read as "basically dead" for a
     * moment. Treating that as the end is what made a fight stop halfway ("the plan ends and the
     * panel disappears while the split is happening"). So the transient case is excluded:
     *
     * <ul>
     *   <li>anything still queued means the fight is mid-resolution;</li>
     *   <li>a monster with {@code halfDead} is a boss in a phase/split transition;</li>
     *   <li>only when the engine is idle does "all basically dead" mean victory.</li>
     * </ul>
     */
    /**
     * B64: the player is dead the moment the engine says so, and no later heal undoes it.
     *
     * <p>{@code AbstractPlayer.damage} clamps HP to 0 and sets {@code isDead = true} / creates the
     * {@code DeathScreen} (AbstractPlayer.java:1452-1484) -- but it does NOT set {@code isDying},
     * and {@code AbstractCreature.heal} only refuses while {@code isDying} (AbstractCreature.java:379).
     * So the engine itself will happily heal a corpse: with Bird Faced Urn (a power heals 2) the
     * HP number climbs back above zero while the run is already over. Reading only
     * {@code currentHealth} therefore "revived" a dead player inside the search.
     */
    public static boolean playerDead() {
        try {
            AbstractPlayer p = AbstractDungeon.player;
            if (p == null) {
                return false;
            }
            return deadFrom(p.isDead, p.currentHealth);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * B73: the death rule, and the only place it is written down.
     *
     * <p>It reads the player's own state. It must NOT look at
     * {@code AbstractDungeon.deathScreen}: the game assigns that static once
     * (AbstractPlayer.java:1482-1483) and never sets it back to null -- it only calls
     * {@code hide()}/{@code reopen()}. So after the first death the field stays non-null for the rest
     * of the process, and B64 made every later fight look "already over" (the 计算 button answered
     * 战斗已结束 while the player stood in a fight). A new run builds a new AbstractPlayer, so
     * isDead/currentHealth are the fields that actually reset.
     *
     * <p>isDead (not currentHealth) is what catches B64: a heal on the corpse can push HP back above
     * zero, but the game has already set isDead = true.
     */
    private static boolean combatOverLogged;

    public static boolean deadFrom(boolean isDead, int currentHealth) {
        return isDead || currentHealth <= 0;
    }

    public static boolean combatOver() {
        try {
            if (playerDead()) {
                return true;
            }
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            if (room != null && room.isBattleOver) {
                return true;
            }
            // cannotLose is the game OWN flag for "this fight cannot be decided yet": split actions
            // wrap themselves in CannotLoseAction while the parent dies and the children spawn.
            // Without this, the gap between "parent dying" and "children exist" looked like a
            // victory: the search finished early and the live plan had no next step.
            if (room != null && room.cannotLose) {
                return false;
            }
            com.megacrit.cardcrawl.monsters.MonsterGroup group = AbstractDungeon.getMonsters();
            if (group == null) {
                return true;
            }
            GameActionManager gam = AbstractDungeon.actionManager;
            if (gam != null && (!gam.actions.isEmpty() || !gam.cardQueue.isEmpty()
                    || !gam.monsterQueue.isEmpty() || !gam.preTurnActions.isEmpty()
                    || gam.usingCard)) {
                return false;
            }
            if (group.monsters.isEmpty()) {
                // An empty group is a transient state (boss removed, spawns not added yet), not a
                // victory: Slime Boss and Awakened One both pass through it.
                return room != null && room.isBattleOver;
            }
            for (int i = 0; i < group.monsters.size(); i++) {
                com.megacrit.cardcrawl.monsters.AbstractMonster m = group.monsters.get(i);
                if (m != null && m.halfDead) {
                    return false;
                }
            }
            return group.areMonstersBasicallyDead();
        } catch (Throwable t) {
            // B73: a throw here used to answer "the fight is over" silently, which cancels the search
            // and the autopilot while the player is still standing. Keep the conservative answer, but
            // say it once so the log can tell a real exception from a real ending.
            if (!combatOverLogged) {
                combatOverLogged = true;
                Log.error("combatOver: could not read the fight state; treating it as over", t);
            }
            return true;
        }
    }

    /** Which selection screen is open, if any. */
    public enum SelectionKind { NONE, HAND, GRID, REWARD, OTHER }

    private static boolean scrySelection;
    /** A ScryAction was seen waiting since the last selection was answered. */ 
    private static boolean scryArmed;

    /**
     * B122 (user m02740): a real press of the End Turn button that nothing has consumed yet.
     *
     * <p>{@code gam.turnHasEnded} is deliberately NOT part of this: it stays true for the whole monster
     * turn, and folding it in made the end of the turn we had just finished look like a new press
     * (measured: S168 lost 2 HP to one Combust hit, because applyEndOfTurnTriggers ran twice). A press
     * sets {@code endTurnQueued} (and the frame that follows derives {@code isEndingTurn}); we clear
     * both when we finish that frame, so a true value always means an unconsumed press.
     */
    private static boolean endTurnQueued(GameActionManager gam) {
        try {
            AbstractPlayer p = AbstractDungeon.player;
            return gam != null && p != null && (p.endTurnQueued || p.isEndingTurn);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * No-op, kept for the probes that used to ask for it.
     *
     * <p>B122b: a press inside the action queue is now recognised by its own flag, so the card-play
     * hint (Legal's PRESS_END_TURN_CARDS) is no longer needed to finish that turn.
     */
    public static void allowLatchedEndTurn() {
    }

    public static void markScrySelection() {
        scrySelection = true;
    }

    /**
     * True when the open selection is a SCRY (the chosen cards are the ones to discard).
     *
     * <p>Checked dynamically as well as by the card-play flag: Foresight opens a scry at the START
     * of a turn, where no card was just played. The waiting {@code ScryAction} is still in the action
     * manager, so that is the reliable signal.
     */
    public static boolean isScrySelection() {
        if (scrySelection || scryArmed) {
            return true;
        }
        try {
            GameActionManager gam = AbstractDungeon.actionManager;
            if (gam == null) {
                return false;
            }
            if (hasScry(gam.actions) || hasScry(gam.preTurnActions)
                    || (gam.turnStartCurrentAction instanceof com.megacrit.cardcrawl.actions.utility.ScryAction)) {
                return true;
            }
        } catch (Throwable ignored) {
            // a busy action manager is no reason to crash
        }
        return false;
    }

    public static void clearScrySelection() {
        scrySelection = false;
    }

    private static void pumpRoomFrames(AbstractRoom room, int frames) {
        for (int i = 0; i < frames; i++) {
            try {
                room.update();
            } catch (Throwable t) {
                Log.warn("pumpRoomFrames: " + t);
                return;
            }
        }
    }

    private static boolean hasScry(java.util.List<com.megacrit.cardcrawl.actions.AbstractGameAction> list) {
        if (list == null) {
            return false;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) instanceof com.megacrit.cardcrawl.actions.utility.ScryAction) {
                return true;
            }
        }
        return false;
    }

    public static SelectionKind selectionKind() {
        try {
            if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.HAND_SELECT
                    || AbstractDungeon.screen == AbstractDungeon.CurrentScreen.GRID) {
                // B106: one predicate for both sides. A screen the game is only closing (cards
                // already retrieved) or a screen a pump opened one frame before it filled itself in
                // is not a decision -- expansion said "nothing to answer" for it and replay said
                // "answer it", which is how a plan step ended up with no answer at all.
                if (!isAwaitingSelection()) {
                    return SelectionKind.NONE;
                }
                return AbstractDungeon.screen == AbstractDungeon.CurrentScreen.HAND_SELECT
                        ? SelectionKind.HAND : SelectionKind.GRID;
            }
            if (isCombatCardReward()) {
                return SelectionKind.REWARD;
            }
            // B71: a reward screen with no waiting combat action (post-combat three-card reward,
            // event, shop) is not something the plan may answer.
            return isAwaitingSelection() ? SelectionKind.OTHER : SelectionKind.NONE;
        } catch (Throwable t) {
            return SelectionKind.OTHER;
        }
    }

    /** Cards the open screen is offering, in screen order (empty when none). */
    public static java.util.List<AbstractCard> selectionCandidates() {
        java.util.List<AbstractCard> out = new java.util.ArrayList<AbstractCard>();
        try {
            if (selectionKind() == SelectionKind.REWARD) {
                // The engine's own three offers, in screen order (the player sees them left to
                // right, and the plan's answer is matched back by card id).
                java.util.List<AbstractCard> group = AbstractDungeon.cardRewardScreen.rewardGroup;
                for (int i = 0; i < group.size(); i++) {
                    if (group.get(i) != null) {
                        out.add(group.get(i));
                    }
                }
            } else if (selectionKind() == SelectionKind.HAND) {
                // The hand-select screen offers the player's hand.
                com.megacrit.cardcrawl.cards.CardGroup g = AbstractDungeon.player.hand;
                for (int i = 0; i < g.group.size(); i++) {
                    if (g.group.get(i) != null) {
                        out.add(g.group.get(i));
                    }
                }
            } else if (selectionKind() == SelectionKind.GRID) {
                com.megacrit.cardcrawl.cards.CardGroup g = AbstractDungeon.gridSelectScreen.targetGroup;
                for (int i = 0; i < g.group.size(); i++) {
                    if (g.group.get(i) != null) {
                        out.add(g.group.get(i));
                    }
                }
            }
        } catch (Throwable t) {
            Log.warn("selectionCandidates: " + t);
        }
        return out;
    }

    /**
     * Answers the open selection screen with {@code chosen} and advances the screen to closed.
     *
     * <p>The screen is a UI object whose confirm path is normally a `CardSelectConfirmButton` click.
     * A sandbox cannot click, so this writes the selection the button would have produced and then
     * pumps the screen's own `update()` until it closes -- the same path the player's click takes,
     * just driven directly.
     *
     * @return true when the screen closed
     */
    /**
     * Candidate answers for the open screen, best-first and worst-first.
     *
     * <p>The search branches on these instead of accepting one heuristic answer, which is what makes
     * "discard this or that" a decision rather than a guess.
     */
    public static java.util.List<java.util.List<AbstractCard>> selectionOptions(int max) {
        java.util.List<java.util.List<AbstractCard>> out =
                new java.util.ArrayList<java.util.List<AbstractCard>>();
        if (selectionKind() == SelectionKind.REWARD) {
            // Pick ONE of the offered cards: every offer is its own branch (a Discovery is a real
            // decision, not a heuristic), best value first. Identical offers collapse in addOption.
            java.util.List<AbstractCard> ordered =
                    new java.util.ArrayList<AbstractCard>(selectionCandidates());
            java.util.Collections.sort(ordered, new java.util.Comparator<AbstractCard>() {
                public int compare(AbstractCard a, AbstractCard b) {
                    return cardValue(b) - cardValue(a);
                }
            });
            for (int i = 0; i < ordered.size() && out.size() < Math.max(1, max); i++) {
                java.util.List<AbstractCard> single = new java.util.ArrayList<AbstractCard>();
                single.add(ordered.get(i));
                addOption(out, single);
            }
            if (out.isEmpty()) {
                out.add(new java.util.ArrayList<AbstractCard>());
            }
            return out;
        }
        java.util.List<AbstractCard> candidates = selectionCandidates();
        if (candidates.isEmpty()) {
            out.add(new java.util.ArrayList<AbstractCard>());
            return out;
        }
        int want = 1;
        boolean upTo = false;
        try {
            if (selectionKind() == SelectionKind.HAND) {
                com.megacrit.cardcrawl.screens.select.HandCardSelectScreen h =
                        AbstractDungeon.handCardSelectScreen;
                // B106: "retain up to 2" / "discard any number" carry their cap in upTo/anyNumber
                // and can leave numCardsToSelect at 0. Reading the count alone made those screens
                // look like a one-card decision, so the search never considered keeping zero cards.
                upTo = h != null && (h.upTo || anyNumber(h));
                want = Math.max(1, h == null ? 1 : h.numCardsToSelect);
            } else if (selectionKind() == SelectionKind.GRID) {
                want = Math.max(1, gridSelectCount());
            }
        } catch (Throwable ignored) {
            // one card is a safe default
        }
        java.util.List<AbstractCard> best = new java.util.ArrayList<AbstractCard>(candidates);
        java.util.Collections.sort(best, new java.util.Comparator<AbstractCard>() {
            public int compare(AbstractCard a, AbstractCard b) {
                return cardValue(b) - cardValue(a);
            }
        });
        boolean scry = isScrySelection();
        if (upTo && !scry) {
            // B106: an "up to N" screen is a set-SIZE decision as much as a card decision -- keeping
            // zero cards is a legal answer, and "keep nothing" comes first because that is what a hand
            // of junk wants.
            //
            // B161 (user m15864): it is also a SET decision. Cumulative prefixes (keep the best 1, the
            // best 2, ...) meant "keep the 3rd best and drop the 1st" never entered the search at all,
            // and the engine's any-number pickers are exactly this shape (GamblingChipAction opens
            // open(TEXT[0], 99, true, true)). Enumerate every size 0..maxKeep, best-first by summed card
            // value, and let the search score them -- the cap below (Limits.handBranches) only decides
            // which sets are tried first, exactly like the exact-count case further down.
            int cap = Math.max(1, max);
            int maxKeep = Math.min(want > 1 ? want : best.size(), best.size());
            addOption(out, new java.util.ArrayList<AbstractCard>());
            for (int k = 1; k <= maxKeep && out.size() < cap; k++) {
                java.util.List<java.util.List<AbstractCard>> sets =
                        combinations(best, k, cap - out.size());
                for (int i = 0; i < sets.size() && out.size() < cap; i++) {
                    addOption(out, sets.get(i));
                }
            }
            return out;
        }
        if (scry) {
            // Scry discards the selected cards: the useful answers start at "discard nothing" and
            // then the WORST cards first, never the best. Ordering only decides who is tried first.
            java.util.Collections.reverse(best);
            addOption(out, new java.util.ArrayList<AbstractCard>());
        }
        int cap = Math.max(1, max);
        if (scry) {
            // Scry may discard ANY NUMBER up to want, and only the worst cards are ever worth it,
            // so the meaningful answers are "nothing", "the worst 1", "the worst 2" ... up to want.
            // Cumulative prefixes, not combinations: picking the worst 2 always includes the worst 1.
            for (int i = 1; i <= want && i <= best.size() && out.size() < cap; i++) {
                addOption(out, take(best, i));
            }
            return out;
        }
        if (want <= 1) {
            // P2-57 (user m24721): a single-card pick is a real decision for EVERY card on offer --
            // "the best one" is a guess, and the guess is exactly what the player saw fail when the
            // winning line needed a different card. Options are emitted best-first so the search
            // still has a sensible order, capped only by the settings tier (Limits).
            for (int i = 0; i < best.size() && out.size() < cap; i++) {
                java.util.List<AbstractCard> single = new java.util.ArrayList<AbstractCard>();
                single.add(best.get(i));
                addOption(out, single);
            }
            if (out.isEmpty()) {
                out.add(new java.util.ArrayList<AbstractCard>());
            }
            return out;
        }
        // Multi-select ("discard 3"): every combination is a real outcome, so enumerate them and
        // keep the most promising ones (ordered by summed card value), capped by the settings tier.
        // The cap only decides which combinations are tried first -- nothing that could win is
        // skipped while the count stays under the tier.
        java.util.List<java.util.List<AbstractCard>> combos = combinations(best, want, cap);
        for (int i = 0; i < combos.size() && out.size() < cap; i++) {
            addOption(out, combos.get(i));
        }
        return out;
    }

    /**
     * All size-{@code want} combinations of {@code ordered}, best-first, at most {@code cap}.
     *
     * <p>Used for multi-card screens where "the best k" is not the only sensible answer. The
     * enumeration is bounded ({@link #COMBINATION_SCAN}) so a 20-card hand selecting 10 cannot spend
     * a second building lists nobody will search.
     */
    private static java.util.List<java.util.List<AbstractCard>> combinations(
            java.util.List<AbstractCard> ordered, int want, int cap) {
        java.util.List<java.util.List<AbstractCard>> out =
                new java.util.ArrayList<java.util.List<AbstractCard>>();
        int n = ordered.size();
        if (want <= 0 || want > n) {
            return out;
        }
        // n choose want can explode; keep the scan bounded and shrink the pool to the best few first
        // (cardValue order means the tail is the least interesting anyway).
        int pool = n;
        while (pool > want && choose(pool, want) > COMBINATION_SCAN) {
            --pool;
        }
        int[] idx = new int[want];
        for (int i = 0; i < want; i++) {
            idx[i] = i;
        }
        while (true) {
            java.util.List<AbstractCard> one = new java.util.ArrayList<AbstractCard>(want);
            for (int i = 0; i < want; i++) {
                one.add(ordered.get(idx[i]));
            }
            out.add(one);
            if (out.size() >= Math.max(cap, 1) * 4) {
                break;
            }
            int i = want - 1;
            while (i >= 0 && idx[i] == pool - want + i) {
                --i;
            }
            if (i < 0) {
                break;
            }
            ++idx[i];
            for (int j = i + 1; j < want; j++) {
                idx[j] = idx[j - 1] + 1;
            }
        }
        return out;
    }

    private static long choose(int n, int k) {
        long r = 1L;
        for (int i = 1; i <= k; i++) {
            r = r * (n - k + i) / i;
            if (r > 1000000L) {
                return r;
            }
        }
        return r;
    }

    private static void addOption(java.util.List<java.util.List<AbstractCard>> out,
                                  java.util.List<AbstractCard> option) {
        for (int i = 0; i < out.size(); i++) {
            // Identity is id + upgrade level, not just the id: an upgraded and an un-upgraded copy
            // of the same card are different branches, and collapsing them hid the upgraded one
            // (B50).
            if (identityOf(out.get(i)).equals(identityOf(option))) {
                return;
            }
        }
        out.add(option);
    }

    /**
     * The state fingerprint of every card in the list -- the dedupe key of {@link #addOption}.
     *
     * <p>B200: this used to be id plus upgrade level, which merged copies that the game treats as
     * different cards. With Snecko Eye two Strikes can cost 0 and 2; "discard this Strike" and
     * "discard that Strike" lead to different turns, but they collapsed into one branch and the
     * search kept whichever copy came first. Cards are compared by what they ARE ({@link
     * sts1solver.mirror.CardPrint}), which is also the key the plan and both engines resolve by.
     */
    static String identityOf(java.util.List<AbstractCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            AbstractCard c = cards.get(i);
            sb.append(c == null ? "?" : sts1solver.mirror.CardPrint.of(c));
        }
        return sb.toString();
    }

    private static java.util.List<AbstractCard> take(java.util.List<AbstractCard> cards, int n) {
        java.util.List<AbstractCard> out = new java.util.ArrayList<AbstractCard>();
        for (int i = 0; i < cards.size() && out.size() < n; i++) {
            out.add(cards.get(i));
        }
        return out;
    }

    /**
     * Comma separated card NAMES, for the panel and the log (m16202-4).
     *
     * <p>Same order and same duplicates as {@link #namesOf}: it is only the human reading of the
     * same list ("防御,防御+"), never the thing a replay matches on.
     */
    public static String displayNamesOf(java.util.List<AbstractCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            AbstractCard c = cards.get(i);
            sb.append(c == null ? "?" : c.name);
        }
        return sb.toString();
    }

    /**
     * B79 (user m22205): comma separated card UUIDs, same order as {@link #namesOf}.
     *
     * <p>This is the identity a plan should be answered with. Ids merge the upgraded and the plain
     * copy, names merge two identical copies in hand ("打击,打击"), but a uuid names exactly one
     * physical card and survives cloning, so a replay can pick the very card the search picked
     * instead of re-deriving the choice from a list.
     */
    public static String uuidsOf(java.util.List<AbstractCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            AbstractCard c = cards.get(i);
            sb.append(c == null || c.uuid == null ? "" : c.uuid.toString());
        }
        return sb.toString();
    }

    /**
     * B98 (user m02347): comma separated card STATE fingerprints, same order as {@link #namesOf}.
     *
     * <p>A uuid is fork local: the sandbox stamps one on every card it creates, and the live game
     * never saw that uuid, so a plan cannot be replayed by uuid alone (it falls back to id + upgrade,
     * which cannot tell two copies apart). The fingerprint is what both engines can compute from the
     * card itself, so it is the identity a plan carries across.
     */
    public static String printsOf(java.util.List<AbstractCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            AbstractCard c = cards.get(i);
            sb.append(c == null ? "" : sts1solver.mirror.CardPrint.of(c));
        }
        return sb.toString();
    }

    /**
     * B205 (user m01995): the STATES of a screen's cards in enumeration order, "state,state,...".
     *
     * <p>This is what tells two live screens apart fairly: the same cards listed in another order
     * are the same screen (which slot a copy sat in is unobservable), while a missing or extra state
     * is not. It only feeds comparisons -- nothing is written into a plan.
     */
    public static String stateRunOf(java.util.List<AbstractCard> cards) {
        if (cards == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            AbstractCard c = cards.get(i);
            sb.append(c == null ? "?" : sts1solver.mirror.CardPrint.of(c));
        }
        return sb.toString();
    }

    /** Comma separated card ids, for labels and for comparing options. */
    public static String namesOf(java.util.List<AbstractCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cards.get(i) == null ? "?" : cards.get(i).cardID);
        }
        return sb.toString();
    }

    /**
     * B139: the first {@code max} ids of a list with a {@code ,+N} tail, so a screen holding twenty
     * cards cannot turn one log line into twenty.
     */
    private static String brief(java.util.List<AbstractCard> cards, int max) {
        if (cards == null) {
            return "";
        }
        int n = Math.min(cards.size(), max);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cards.get(i) == null ? "?" : cards.get(i).cardID);
        }
        if (cards.size() > n) {
            sb.append(",+").append(cards.size() - n);
        }
        return sb.toString();
    }

    /**
     * Replays an answer recorded in a plan: {@code action.choice} holds the comma separated card ids
     * that {@link #namesOf} produced when the branch was explored.
     *
     * <p>Matching by card id (never by index) is the point: a replay starts from a fresh copy where
     * every draw has shifted the hand, so an index from the original line would answer with the wrong
     * card. Without this, a plan that ever opened a selection screen could not be replayed at all --
     * the screen stayed open, endTurn became a no-op, and the next step failed with
     * "no card at hand index N" (which made long plans look 12 steps long and forced autopilot to
     * recompute mid-fight).
     *
     * @return true when the screen was answered; false leaves the caller to fall back
     */
    public static boolean resolveSelectionByNames(String cardIds) {
        return resolveSelectionByNames(cardIds, null);
    }

    /**
     * B78: the same answer, but the plan's card NAMES decide first.
     *
     * <p>A card id never carries its upgrade state, so an id-only match can pick the un-upgraded
     * copy of exactly the card the plan named. Replaying "选：破灭+" as "破灭" is what made long
     * plans diverge a step later. The display name (ends in "+" for an upgraded copy) is exact;
     * the id stays as the fallback.
     *
     * @param displayNames comma separated card names from the plan, may be null
     */
    public static boolean resolveSelectionByNames(String cardIds, String displayNames) {
        return resolveSelectionByNames(cardIds, displayNames, null);
    }

    /**
     * B79 (user m22205): the same answer, with the uuids the plan stored.
     *
     * <p>Order of belief: uuid (exactly the physical card the search picked) &gt; id + name (upgrade
     * state exact) &gt; id &gt; name. The uuid pass is what makes a replay a COPY instead of a second
     * decision, and it is the only pass that can tell two identical copies apart.
     *
     * @param choiceUuids comma separated uuids from the plan, may be null
     */
    public static boolean resolveSelectionByNames(String cardIds, String displayNames, String choiceUuids) {
        return resolveSelectionByNames(cardIds, displayNames, choiceUuids, null);
    }

    /** The same, with the STATE fingerprints the plan stored (B98): identity that crosses forks. */
    public static boolean resolveSelectionByNames(String cardIds, String displayNames, String choiceUuids,
                                                  String choicePrints) {
        return resolveSelectionByNames(cardIds, displayNames, choiceUuids, choicePrints, "", "");
    }

    /**
     * B163 (user m16041): the same answer, with the cards the player moved to the END of the hand
     * before confirming (select, then deselect -- see {@code Action.moved}).
     *
     * <p>An EMPTY answer is legal and is no longer a refusal: "keep nothing" is a real choice on a
     * picker that allows it ({@code canPickZero}, an any-number picker), and B161 started emitting it
     * as a branch. Treating it as "the plan named a card the screen does not offer" made those
     * branches un-replayable (a plan that said 不选 could never be applied again).
     */
    public static boolean resolveSelectionByNames(String cardIds, String displayNames, String choiceUuids,
                                                  String choicePrints, String movedIds,
                                                  String movedUuids) {
        selectionFailure = "";
        if (selectionKind() == SelectionKind.NONE) {
            refused("not-settled", "no select screen is open");
            return true;
        }
        java.util.List<AbstractCard> candidates = selectionCandidates();
        boolean askedEmpty = cardIds == null || cardIds.trim().isEmpty();
        java.util.List<AbstractCard> chosen =
                picksFor(candidates, cardIds, displayNames, choiceUuids, choicePrints);
        if (chosen.isEmpty() && !askedEmpty) {
            refusedInBranch("not-offered", "the screen offers [" + namesOf(candidates) + "], the plan chose ["
                    + (displayNames == null || displayNames.isEmpty() ? cardIds : displayNames) + "] ("
                    + selectionState() + ")");
            return false;
        }
        java.util.List<AbstractCard> moved =
                picksFor(candidates, movedIds, null, movedUuids, null);
        boolean askedMove = movedIds != null && !movedIds.trim().isEmpty();
        if (askedMove && moved.isEmpty()) {
            refusedInBranch("not-offered", "the plan asks to move [" + movedIds
                    + "] to the end of the hand, but the screen offers [" + namesOf(candidates) + "]");
            return false;
        }
        return resolveSelection(chosen, moved);
    }
    /**
     * Answers the open screen on the LIVE game: fill the selection, close the screen, and let the
     * game's own frames consume it (no engine pumping -- that is the mode that once crashed a turn).
     *
     * @param cardIds comma separated card ids from the plan; empty means "best-first"
     * @return true when a selection was opened and this call answered it
     */
    public static boolean resolveSelectionLive(String cardIds) {
        return resolveSelectionLive(cardIds, null);
    }

    /**
     * B77 (m21571): the same answer, but with the card NAMES the search recorded ("破灭+").
     *
     * <p>A card's id never carries its upgrade state ({@code Havoc} is both 破灭 and 破灭+), so
     * matching on the id alone could take the un-upgraded copy of the very card the plan named --
     * the headbutt case the player hit: the plan asked for 破灭+ and the pick moved a different
     * card, which then showed up as a drift one step later. The display name is the exact identity
     * (it ends in "+" for an upgraded copy); the id stays as the fallback.
     *
     * @param displayNames comma separated card names from the plan, may be null
     */
    public static boolean resolveSelectionLive(String cardIds, String displayNames) {
        return resolveSelectionLive(cardIds, displayNames, null);
    }

    /** The same, with the uuids the plan stored (B79): the live hand is matched by identity first. */
    public static boolean resolveSelectionLive(String cardIds, String displayNames, String choiceUuids) {
        return resolveSelectionLive(cardIds, displayNames, choiceUuids, null);
    }

    /** The same, with the STATE fingerprints the plan stored (B98). */
    public static boolean resolveSelectionLive(String cardIds, String displayNames, String choiceUuids,
                                               String choicePrints) {
        selectionFailure = "";
        if (selectionKind() == SelectionKind.NONE) {
            refused("not-settled", "no select screen is open");
            return false;
        }
        String[] names = displayNames == null || displayNames.isEmpty()
                ? null : displayNames.split(",");
        String[] uuids = choiceUuids == null || choiceUuids.isEmpty()
                ? null : choiceUuids.split(",");
        String[] prints = choicePrints == null || choicePrints.isEmpty()
                ? null : choicePrints.split(",");
        if (selectionKind() == SelectionKind.REWARD) {
            // The LIVE game keeps its own frames: answer the screen the way a click would and let
            // the frozen action resume. No engine pumping here (that is the sandbox path).
            java.util.List<AbstractCard> offers = selectionCandidates();
            AbstractCard pick = null;
            if (cardIds != null && !cardIds.isEmpty()) {
                String[] ids = cardIds.split(",");
                for (int i = 0; i < ids.length && pick == null; i++) {
                    pick = matchOffered(offers, null, ids[i].trim(), nameAt(names, i), nameAt(uuids, i),
                            nameAt(prints, i));
                }
            }
            if (pick == null) {
                pick = bestOf(offers);
                refused("not-offered", "the reward screen offers [" + namesOf(offers)
                        + "], the plan chose [" + cardIds + "]");
                Log.warn("live reward pick: planned " + cardIds + " not offered; taking "
                        + (pick == null ? "none" : pick.cardID));
            }
            return answerCardReward(pick);
        }
        if (cardIds == null || cardIds.isEmpty()) {
            return resolveSelectionLiveHeuristically();
        }
        java.util.List<AbstractCard> candidates = selectionCandidates();
        java.util.List<AbstractCard> chosen =
                picksFor(candidates, cardIds, displayNames, choiceUuids, choicePrints);
        if (chosen.isEmpty()) {
            // P2-15/B49: do NOT answer with the best-first guess here. The plan named a card the
            // screen does not offer, so the search and the game disagree about the state; replaying
            // the rest on a guessed pick hides that. Refuse, and let the caller stop and ask for a
            // recompute.
            refused("not-offered", "the screen offers [" + namesOf(candidates)
                    + "], the plan chose [" + cardIds + "]");
            lastSelectionConsumed = false;
            Log.warn("live selection: the plan asks for " + cardIds
                    + " but the screen offers " + namesOf(candidates) + "; refusing to guess");
            return false;
        }
        // B139 (user m08865/m08974): say what the screen actually offered and which copies were
        // taken. Until now the only line named what the PLAN asked for, so "the plan said Strike
        // and the fight moved SuoKai" could not be told apart from "the plan said Strike and a
        // Strike was taken" -- the two need completely different fixes.
        Log.info("live selection: kind=" + selectionKind() + " offered=[" + brief(candidates, 10)
                + "] picked=[" + namesOf(chosen) + "] state=[" + printsOf(chosen) + "] plan=["
                + cardIds + "] planState=[" + (choicePrints == null ? "" : choicePrints) + "]");
        return answerLiveSelection(chosen, cardIds);
    }

    /**
     * B163 (user m16041): answer the open screen on the LIVE game, first moving {@code movedIds} to
     * the END of the hand -- what clicking a card and clicking it again does on the real screen.
     *
     * <p>Only the hand order changes; the cards themselves stay in the fight, so this is the same
     * state a player could produce by hand. Nothing here looks at which mod opened the screen.
     */
    public static boolean resolveSelectionLive(String cardIds, String displayNames, String choiceUuids,
                                               String choicePrints, String movedIds,
                                               String movedUuids) {
        if (movedIds != null && !movedIds.trim().isEmpty()) {
            if (selectionKind() != SelectionKind.HAND) {
                refused("not-offered", "only a hand picker can be reordered (" + selectionState() + ")");
                return false;
            }
            java.util.List<AbstractCard> offers = selectionCandidates();
            java.util.List<AbstractCard> moved = picksFor(offers, movedIds, null, movedUuids, null);
            if (moved.isEmpty()) {
                refused("not-offered", "the plan asks to move [" + movedIds
                        + "] to the end of the hand, but the screen offers [" + namesOf(offers) + "]");
                return false;
            }
            if (!moveToEndOfHand(moved)) {
                return false;
            }
        }
        return resolveSelectionLive(cardIds, displayNames, choiceUuids, choicePrints);
    }

    /**
     * B190 (user m01149): the plan answered this picker with NOTHING -- pick nothing and press 确定.
     *
     * <p>This is a real answer, not a missing one: 计划妥当 ("خطط" / Well Laid Plans) opens a
     * "keep one card" picker that the player may leave empty, and a player presses the confirm button
     * with no card selected. It used to be impossible to say: an empty id list fell back to the
     * best-first guess (B158 forbids guessing), and a grid answer could never be empty at all.
     *
     * @param kindHint the screen kind recorded in the plan, or null/"" when it was not recorded;
     *                 a mismatch refuses instead of answering the wrong screen
     */
    public static boolean resolveSelectionLiveEmpty(String kindHint) {
        SelectionKind kind = selectionKind();
        if (kind == null || kind == SelectionKind.NONE) {
            refused("not-settled", "no pick screen is open");
            return false;
        }
        if (kindHint != null && !kindHint.trim().isEmpty() && !kindHint.trim().equals(kind.name())) {
            refused("not-offered", "the plan answered a " + kindHint.trim()
                    + " screen but a " + kind + " screen is open (" + selectionState() + ")");
            return false;
        }
        if (kind == SelectionKind.REWARD) {
            // The engine has no "skip" on a card reward screen: the open screen IS a decision.
            refused("not-offered", "a card reward screen has no 「不选」 answer");
            return false;
        }
        if (!allowsEmptyPick(kind)) {
            refused("not-offered", "this picker does not allow picking nothing ("
                    + selectionState() + ")");
            return false;
        }
        Log.info("live selection: answering with 「不选」 (确定 only, " + kind + ")");
        return answerLiveSelection(new java.util.ArrayList<AbstractCard>(), "不选");
    }

    /** B190: whether the open picker lets the player confirm without selecting anything. */
    private static boolean allowsEmptyPick(SelectionKind kind) {
        try {
            if (kind == SelectionKind.HAND) {
                return AbstractDungeon.handCardSelectScreen != null
                        && AbstractDungeon.handCardSelectScreen.canPickZero;
            }
            if (kind == SelectionKind.GRID) {
                // GridCardSelectScreen has no canPickZero; the two places it lets an empty confirm
                // through are an anyNumber picker and a screen that only asks for confirmation.
                return AbstractDungeon.gridSelectScreen != null
                        && (AbstractDungeon.gridSelectScreen.anyNumber
                            || AbstractDungeon.gridSelectScreen.isJustForConfirming);
            }
        } catch (Throwable t) {
            Log.warn("allowsEmptyPick: " + t);
        }
        return false;
    }

    /**
     * The offered card a plan entry names: the uuid this exact copy (B79), then id + upgrade (the
     * display name), then the bare id, then (for hand written plans) the display name alone.
     */
    private static AbstractCard matchOffered(java.util.List<AbstractCard> offered, String id, String name) {
        return matchOffered(offered, null, id, name, null);
    }

    private static AbstractCard matchOffered(java.util.List<AbstractCard> offered, boolean[] used,
                                             String id, String name, String uuid) {
        return matchOffered(offered, used, id, name, uuid, null);
    }

    /**
     * The offered card a plan entry names: STATE FINGERPRINT (B98) &gt; uuid (fork local) &gt; id +
     * upgrade &gt; bare id &gt; bare name.
     *
     * <p>The print pass sits FIRST on purpose (B200). The sandbox and the live game each resolve the
     * plan against their own card objects, so the only key they can both be trusted with is the state
     * the plan recorded. A uuid is fork local; when it happened to match, it silently overrode the
     * print and the two sides could take DIFFERENT copies of one card (two copies whose prints are
     * equal, e.g. two Strikes that Snecko rolled to the same cost). Taking copy #2 in the sandbox and
     * copy #1 live reorders the hand, the discard pile and therefore the next shuffle, and the run
     * diverges while every per-step counter still reads "rng same". Resolving by print first makes
     * both sides apply the same rule and pick the same occurrence (the first one that matches).
     */
    private static AbstractCard matchOffered(java.util.List<AbstractCard> offered, boolean[] used,
                                             String id, String name, String uuid, String print) {
        for (int pass = 0; pass < 5; pass++) {
            for (int k = 0; k < offered.size(); k++) {
                AbstractCard c = offered.get(k);
                if (c == null || used != null && used[k]) {
                    continue;
                }
                if (pass == 0 && print != null && !print.isEmpty()
                        && print.equals(sts1solver.mirror.CardPrint.of(c))) {
                    return c;
                }
                if (pass == 1 && uuid != null && !uuid.isEmpty() && c.uuid != null
                        && uuid.equals(c.uuid.toString())) {
                    return c;
                }
                if (pass == 2 && name != null && id != null && id.equals(c.cardID)
                        && name.equals(c.name)) {
                    return c;
                }
                if (pass == 3 && id != null && id.equals(c.cardID)) {
                    return c;
                }
                if (pass == 4 && name != null && name.equals(c.name)) {
                    return c;
                }
            }
        }
        return null;
    }

    /**
     * B95 (user: "autoplay must use the same interface as the sandbox"): THE answer matcher, shared
     * by the sandbox replay ({@link #resolveSelectionByNames}) and the live autoplay
     * ({@link #resolveSelectionLive}).
     *
     * <p>Both paths used to be written out twice, and they had quietly drifted: the live one passed
     * {@code used = null}, so a plan that discards TWO copies of one card ("Strike_R,Strike_R") sent
     * the same physical card twice -- the second fill found the card already taken and the screen
     * consumed one card instead of two, while the sandbox replay did it right. One helper, one rule:
     * a card can be chosen once per answer, and the belief order is uuid &gt; id+name &gt; id &gt; name.
     */
    public static java.util.List<AbstractCard> picksFor(java.util.List<AbstractCard> candidates,
                                                String cardIds, String displayNames,
                                                String choiceUuids) {
        return picksFor(candidates, cardIds, displayNames, choiceUuids, null);
    }

    /** The same, with the STATE fingerprints (B98): the one belief order both engines share. */
    public static java.util.List<AbstractCard> picksFor(java.util.List<AbstractCard> candidates,
                                                String cardIds, String displayNames,
                                                String choiceUuids, String choicePrints) {
        java.util.List<AbstractCard> chosen = new java.util.ArrayList<AbstractCard>();
        if (candidates == null || candidates.isEmpty() || cardIds == null || cardIds.isEmpty()) {
            return chosen;
        }
        String[] names = displayNames == null || displayNames.isEmpty()
                ? null : displayNames.split(",");
        String[] uuids = choiceUuids == null || choiceUuids.isEmpty()
                ? null : choiceUuids.split(",");
        String[] prints = choicePrints == null || choicePrints.isEmpty()
                ? null : choicePrints.split(",");
        String[] ids = cardIds.split(",");
        boolean[] used = new boolean[candidates.size()];
        for (int i = 0; i < ids.length; i++) {
            // B200: when the plan recorded a STATE and the screen offers SEVERAL copies of that id,
            // none of which carries it, there is no honest answer left. Falling through to "a card
            // with that id" would silently take a different copy -- the two engines would then keep
            // different hands, and the divergence only shows up turns later as a phantom cost. Refuse
            // the whole answer instead; the callers already report "not-offered" and stop.
            String want = nameAt(prints, i);
            String bare = ids[i].trim();
            int cut = bare.lastIndexOf('#');
            if (cut > 0) {
                bare = bare.substring(0, cut).trim();
            }
            if (want != null && !want.isEmpty() && !carriesPrint(candidates, used, want)
                    && countUnusedId(candidates, used, bare) > 1) {
                Log.warn("selection: " + countUnusedId(candidates, used, bare) + " copies of "
                        + bare + " on offer and none carries the planned state (" + want + ")");
                return new java.util.ArrayList<AbstractCard>();
            }
            // used[] keeps duplicate ids (a hand-select that discards two Strikes) honest: the same
            // physical card can be chosen once, never twice.
            //
            // B204 (user m01614): "id@STATE#k" names the k-th copy of that STATE on the screen. Two
            // Strikes+ are the same card to the game (same id, same upgrade, same numbers), but the
            // k the search wrote still says WHICH of them it clicked, so a plan never degrades into
            // "some card with this id". The k is a position in the screen's own enumeration, so it is
            // only used while the screen still presents the very same distinct cards, in the same
            // order; any change falls through to the state / identity matchers below.
            String raw = ids[i].trim();
            int hash = raw.lastIndexOf('#');
            String position = null;
            if (hash > 0) {
                try {
                    Integer.parseInt(raw.substring(hash + 1).trim());
                    // Only a decided STATE may carry a slot, the same rule positionsOn applies.
                    position = sameState(ownState(raw), want) ? raw : null;
                    raw = raw.substring(0, hash).trim();
                } catch (NumberFormatException notAPosition) {
                    position = null;
                }
            }
            // B205 (user m01995): positions are no longer written into a plan, so below they arrive
            // only from an answer recorded by 1.0.7 (kept replayable). Such an entry's state is
            // checked against the plan's own STATE record here (above), so the slot can never resolve
            // onto a different card than the one the plan named.
            AbstractCard pick = position == null ? null : atPosition(candidates, used, position, want);
            if (pick == null) {
                pick = matchOffered(candidates, used, raw, nameAt(names, i), nameAt(uuids, i), want);
            }
            if (pick != null) {
                used[candidates.indexOf(pick)] = true;
                chosen.add(pick);
            }
        }
        return chosen;
    }

    /**
     * B205 (user m01995): the STATE part of an "id@STATE" / "id@STATE#k" answer entry ("" when the
     * entry carries none). Only old-build plans hold these; a plain id has no '@'.
     */
    private static String ownState(String entry) {
        String one = entry == null ? "" : entry.trim();
        int at = one.indexOf('@');
        return at < 0 ? "" : one.substring(at + 1);
    }

    /** B205: are these the same state? An unrecorded state agrees with anything. */
    private static boolean sameState(String a, String b) {
        return a == null || a.isEmpty() || b == null || b.isEmpty() || a.equals(b);
    }

    /** Does an unused candidate carry exactly this state fingerprint? (B200) */
    /**
     * B204 (user m01614): the k-th copy of a STATE in the screen's enumeration ("Strike_R+|c=1/1#2").
     *
     * <p>Two copies of one card are the same card to the game -- same id, same upgrade, same numbers --
     * so a plan written in ids or states cannot say WHICH copy the search clicked, and the two
     * engines can then hold the same multiset in a different order (the cost a card carries under
     * Snecko Eye is bound to that copy). The position is the only thing that survives, and it is
     * stable as long as the screen offers the very same distinct cards in the same order -- which is
     * exactly what a fork of the live game and the live game itself do. When that does not hold, this
     * returns null and the caller falls back to matching by state, then by id.
     */
    private static AbstractCard atPosition(java.util.List<AbstractCard> candidates, boolean[] used,
                                           String idAndPosition, String wantState) {
        int hash = idAndPosition.lastIndexOf('#');
        if (hash <= 0 || candidates == null) {
            return null;
        }
        int k;
        try {
            k = Integer.parseInt(idAndPosition.substring(hash + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        String id = idAndPosition.substring(0, hash).trim();
        int at = 0;
        for (int j = 0; j < candidates.size(); j++) {
            AbstractCard c = candidates.get(j);
            if (c == null || c.cardID == null
                    || !id.startsWith(c.cardID)
                    || id.length() > c.cardID.length() && id.charAt(c.cardID.length()) != '|') {
                continue;
            }
            String state = sts1solver.mirror.CardPrint.of(c);
            if (!id.equals(state) && !id.equals(c.cardID)) {
                continue;
            }
            // B205: a "id@STATE#k" entry may only be resolved onto that very state.
            if (wantState != null && !wantState.isEmpty() && !wantState.equals(state)) {
                continue;
            }
            at++;
            if (at == k) {
                return used != null && used[j] ? null : c;
            }
        }
        return null;
    }

    private static boolean carriesPrint(java.util.List<AbstractCard> candidates, boolean[] used,
                                        String print) {
        for (int k = 0; k < candidates.size(); k++) {
            AbstractCard c = candidates.get(k);
            if (c != null && (used == null || !used[k])
                    && print.equals(sts1solver.mirror.CardPrint.of(c))) {
                return true;
            }
        }
        return false;
    }

    /** How many still-available candidates carry this id -- 1 means the pick cannot be ambiguous. */
    private static int countUnusedId(java.util.List<AbstractCard> candidates, boolean[] used, String id) {
        int n = 0;
        for (int k = 0; k < candidates.size(); k++) {
            AbstractCard c = candidates.get(k);
            if (c != null && (used == null || !used[k]) && c.cardID.equals(id)) {
                ++n;
            }
        }
        return n;
    }

    private static String nameAt(String[] names, int i) {
        return names == null || i >= names.length ? null : names[i].trim();
    }

    /**
     * Answers an open GRID/HAND selection on the live game: fill, let the waiting action consume it,
     * close, then check that it really was consumed (B49).
     *
     * <p>Filling and closing in one frame matched the real click path (GridCardSelectScreen closes on
     * the same frame, line 211), but it lost Shrug's effect anyway: headbutt+ picked a Defend and the
     * card stayed in the discard pile while the digest showed the move applied. The consumer
     * ({@code DiscardPileToTopOfDeckAction}) only acts when it sees a non-empty selection on ITS next
     * update, so the live path needs the same bounded pump the sandbox path uses, plus a check that
     * the selection actually left the screen. If it did not, the effect is lost -- say so instead of
     * pretending the step worked and letting the drift show up one step later.
     */
    private static boolean answerLiveSelection(java.util.List<AbstractCard> chosen, String label) {
        try {
            // B141 (user m09905): what the picker count was BEFORE this answer. A mod action that
            // asks two questions opens the second picker inside the pump below; a count change says
            // so, and that screen belongs to the next answer -- closing it is what lost the swap.
            int openedBefore = ScreenOpens.count();
            SelectionKind kind = selectionKind();
            boolean filled = kind == SelectionKind.HAND ? fillHandSelection(chosen) : fillGridSelection(chosen);
            if (!filled) {
                refused("not-offered", "the screen would not take the choice (" + label + ")");
                lastSelectionConsumed = false;
                Log.warn("live selection: the screen would not take the choice (" + label + ")");
                return false;
            }
            boolean consumed = pumpLiveUntilConsumed();
            if (!consumed) {
                // B66 (m19362): do NOT give up on the frame the screen was filled. The action that
                // opened the screen may consume the pick a few frames later (the engine advances an
                // animation before it reads the selection), and closing the screen here is exactly
                // what loses the effect. Keep the screen and the selection untouched and let the
                // frame loop call this again until the settle window in AutoPace runs out.
                if (selectionSinceMs == 0L) {
                    selectionSinceMs = System.currentTimeMillis();
                    Log.info("live selection: filled " + label + " and waiting for the action to take it");
                }
                ++lastSelectionAttempts;
                lastSelectionConsumed = false;
                refused("not-consumed", "filled " + label + " but the waiting action has not taken it yet");
                return false;
            }
            if (selectionSinceMs != 0L) {
                Log.info("live selection: the action took " + label + " after "
                        + (System.currentTimeMillis() - selectionSinceMs) + "ms");
                selectionSinceMs = 0L;
            }
            if (ScreenOpens.count() != openedBefore && selectionKind() != SelectionKind.NONE) {
                // The waiting action took this answer and asked its NEXT question in the same pump.
                // Leave that picker open: the plan holds an answer for it (Gameplay.answerNextScreen
                // steps the chain forward), and closing it here made the action read an empty
                // selection -- HeadChase's second pick vanished exactly this way.
                lastSelectionConsumed = true;
                Log.info("live selection: " + label + " was taken and the action opened its next screen ("
                        + selectionQuestion() + "); leaving it for the next answer");
                return true;
            }
            AbstractDungeon.closeCurrentScreen();
            lastSelectionConsumed = true;
            Log.info("live selection answered with " + label + " (consumed by the waiting action)");
            return true;
        } catch (Throwable t) {
            lastSelectionConsumed = false;
            Log.warn("live selection failed: " + t);
            return false;
        }
    }

    /**
     * B79 (user m22205-3): the last reason a selection answer was refused, with a coarse bucket
     * ("not-offered: the screen offers [打击], the plan chose [破灭]"). Empty when nothing failed.
     * The replay reads it to say WHY a line cannot be rebuilt, instead of a generic message.
     */
    private static String selectionFailure = "";
    private static long settlePumps;

    public static String lastSelectionFailure() {
        return selectionFailure;
    }

    /**
     * B204 (user m01614): "id@STATE" per card, in the screen's own enumeration order -- the shape
     * {@link sts1solver.search.Solver#positionsOn} maps a plan's positions onto.
     */
    public static String positionedIds(java.util.List<AbstractCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; cards != null && i < cards.size(); i++) {
            AbstractCard c = cards.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append(c == null ? "?" : c.cardID).append('@')
                    .append(c == null ? "?" : sts1solver.mirror.CardPrint.of(c));
        }
        return sb.toString();
    }

    /** How many engine updates the last {@link #settleForReplay} spent (diagnostics). */
    public static long lastSettlePumps() {
        return settlePumps;
    }

    /** Records a refusal found OUTSIDE this class (the live walk), so the caller reads one failure. */
    public static void refuseNow(String kind, String detail) {
        refused(kind, detail);
    }

    private static void refused(String kind, String detail) {
        selectionFailure = kind + ": " + detail;
        Log.warn("selection refused (" + selectionFailure + ")");
    }

    /**
     * B185 (user m03912): the same refusal, but on the SANDBOX path, where it is the normal end of a
     * branch rather than a defect.
     *
     * <p>The search walks a picker one card at a time: a branch answers "take Catalyst", the next
     * call in that same branch asks for a card the branch already spent, and the answer is a
     * not-offered refusal that the caller turns into "branch left the screen chain unfinished" and
     * discards. That is correct behaviour -- but it was logged as a WARNING per hit, up to hundreds
     * of lines in one fight, and a reader (this user included) reasonably concluded the mod was
     * failing on his own run. The live path keeps WARN, because there the same sentence means the
     * plan and the game really disagree; here it is INFO, throttled to one line per 5 s.
     */
    private static void refusedInBranch(String kind, String detail) {
        selectionFailure = kind + ": " + detail;
        if (Log.allow("branch-selection-refused", 5000L)) {
            Log.info("selection refused while walking a branch (" + selectionFailure + ")");
        }
    }

    /**
     * B79: let the engine catch up before believing a refused selection.
     *
     * <p>User m22205-3 asked whether a replay failure could just be "the game had not loaded yet".
     * It can: a step opens its screen on a later update, and a replay that reads the screen on the
     * same tick sees none. Pumping a bounded number of updates and retrying once separates "we
     * answered wrong" from "we asked too early" -- only the first one is a modelling problem.
     *
     * @return the selection kind that is open afterwards (NONE when no screen appeared)
     */
    public static SelectionKind settleForReplay(int maxUpdates) {
        settlePumps = 0L;
        GameActionManager gam = AbstractDungeon.actionManager;
        for (int i = 0; i < maxUpdates; i++) {
            SelectionKind now = selectionKind();
            if (now != SelectionKind.NONE) {
                return now;
            }
            if (gam == null) {
                break;
            }
            try {
                gam.update();
            } catch (Throwable t) {
                Log.warn("replay settle: action manager update threw: " + t);
                break;
            }
            ++settlePumps;
        }
        return selectionKind();
    }

    /** When the current selection was first filled and not yet taken (0 = none pending). */
    private static long selectionSinceMs;
    private static long lastSelectionAttempts;

    /** True while a filled selection is still waiting for the action that opened the screen. */
    public static boolean selectionRetrying() {
        return selectionSinceMs != 0L;
    }

    /** Clock of the first unanswered fill (B66); 0 when nothing is pending. */
    public static long selectionSince() {
        return selectionSinceMs;
    }

    /** B119 (m02060): the settle window must not run while the game is paused. */
    public static void compensatePause(long ms) {
        if (ms > 0L && selectionSinceMs != 0L) {
            selectionSinceMs += ms;
        }
    }

    /** How many frames the current selection has been waiting (diagnostics). */
    public static long selectionAttempts() {
        return lastSelectionAttempts;
    }

    /** Drops the pending-selection bookkeeping (a new fight, or the player took over). */
    public static void clearSelectionRetry() {
        selectionSinceMs = 0L;
        lastSelectionAttempts = 0L;
    }

    /**
     * Bounded pump on the LIVE game: lets the action that opened the screen run until it takes (and
     * clears) the selection, at most 8 updates.
     *
     * <p>Never drains and never touches the live action queue beyond those updates -- the sandbox
     * variant's full pump once crashed a live turn (see the note in {@link #resolveSelectionLive}).
     */
    private static boolean pumpLiveUntilConsumed() {
        GameActionManager gam = AbstractDungeon.actionManager;
        if (gam == null) {
            return true;
        }
        for (int i = 0; i < 8; i++) {
            if (selectionConsumed()) {
                return true;
            }
            try {
                // B165 (user m17933): F2 detector. Count what this frame's update spends while a
                // screen is ALREADY up. An action that re-rolls its own screen every frame (the
                // Discovery pattern) shows up here; the frame that OPENS the screen is not reported
                // (it legitimately generates once) because the screen is not up before that update.
                boolean wasOpen = AbstractDungeon.isScreenUp;
                int rngBefore = screenDrawCount();
                gam.update();
                noteFrameDraws("live pump", wasOpen, rngBefore);
            } catch (Throwable t) {
                Log.warn("live selection: action manager update threw: " + t);
                return false;
            }
        }
        return selectionConsumed();
    }

    /** B165 (F2): how many draws the two random streams this pattern touches have made so far. */
    private static int screenDrawCount() {
        try {
            int n = 0;
            if (AbstractDungeon.cardRandomRng != null) {
                n += AbstractDungeon.cardRandomRng.counter;
            }
            if (AbstractDungeon.miscRng != null) {
                n += AbstractDungeon.miscRng.counter;
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * B165 (F2, user m17933): report, and only report, a frame of an ALREADY OPEN screen that spent
     * random numbers. This is the shape of the Discovery bug: the action regenerated its three
     * choices on every update while the screen stayed up, so live play burned tens of draws the
     * sandbox never spent. Discovery itself is normalized now (see
     * {@code sts1solver.mirror.DiscoveryRandom}), so this line is how any OTHER frame-dependent
     * action is found instead of guessed at. It never changes behaviour.
     */
    private static void noteFrameDraws(String where, boolean screenWasOpen, int before) {
        if (!screenWasOpen) {
            return;
        }
        try {
            int delta = screenDrawCount() - before;
            if (delta > 0) {
                Log.warnEvery("screen-rng", 10000L, where + ": one frame spent " + delta
                        + " random number(s) while " + AbstractDungeon.screen
                        + " was already open -- a frame-dependent action is wasting draws here");
            }
        } catch (Throwable ignored) {
            // a detector must never break the pump
        }
    }

    /**
     * True when the action that opened the screen has TAKEN the answered selection.
     *
     * <p>B66 (m19362): the hand-select screen never empties {@code selectedCards} when it closes --
     * {@code HandCardSelectScreen.prep()} is the only place that clears it -- so "the group is
     * empty" was the wrong test: the consumer (ExhaustAction, DiscardAction, ...) sets
     * {@code wereCardsRetrieved = true} and leaves the cards in the group until the next screen
     * opens. Reading only the group made every hand-pick look unconsumed, which stopped autoplay
     * right after Burning Pact. The grid screen does clear its own list, so that branch stays.
     */
    private static boolean selectionConsumed() {
        try {
            SelectionKind kind = selectionKind();
            if (kind == SelectionKind.GRID) {
                return AbstractDungeon.gridSelectScreen == null
                        || AbstractDungeon.gridSelectScreen.selectedCards.isEmpty();
            }
            if (kind == SelectionKind.HAND) {
                if (AbstractDungeon.handCardSelectScreen == null) {
                    return true;
                }
                return AbstractDungeon.handCardSelectScreen.wereCardsRetrieved
                        || AbstractDungeon.handCardSelectScreen.selectedCards.group.isEmpty();
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Whether the last live selection answer was actually consumed by the waiting action (B49). */
    public static boolean lastSelectionConsumed() {
        return lastSelectionConsumed;
    }

    /** Answers the open screen with a deterministic best-first choice. */
    public static boolean resolveSelectionHeuristically() {
        if (selectionKind() == SelectionKind.NONE) {
            return true;
        }
        java.util.List<AbstractCard> candidates = selectionCandidates();
        if (candidates.isEmpty()) {
            return resolveSelection(new java.util.ArrayList<AbstractCard>());
        }
        java.util.List<AbstractCard> best = new java.util.ArrayList<AbstractCard>(candidates);
        java.util.Collections.sort(best, new java.util.Comparator<AbstractCard>() {
            public int compare(AbstractCard a, AbstractCard b) {
                return cardValue(b) - cardValue(a);
            }
        });
        int want = 1;
        try {
            if (selectionKind() == SelectionKind.HAND) {
                want = Math.max(1, AbstractDungeon.handCardSelectScreen.numCardsToSelect);
            } else if (selectionKind() == SelectionKind.GRID) {
                want = Math.max(1, gridSelectCount());
            }
        } catch (Throwable ignored) {
            // one card is a safe default
        }
        java.util.List<AbstractCard> chosen = new java.util.ArrayList<AbstractCard>();
        for (int i = 0; i < best.size() && chosen.size() < want; i++) {
            chosen.add(best.get(i));
        }
        return resolveSelection(chosen);
    }

    /**
     * Answers the open screen on the LIVE game with a best-first choice, WITHOUT pumping the engine.
     *
     * <p>Used when the plan carries no usable choice (an older plan, a mod card the search never
     * branched on, or a card whose pick was re-rolled live). The sandbox variant resolves through
     * {@link #resolveSelection} which pumps frames; on the live game that is the path that once
     * crashed a turn, so here we only fill the selection and close the screen.
     */
    public static boolean resolveSelectionLiveHeuristically() {
        if (selectionKind() == SelectionKind.NONE) {
            return true;
        }
        java.util.List<AbstractCard> candidates = selectionCandidates();
        if (candidates.isEmpty()) {
            Log.warn("live selection: no candidates to choose from (" + AbstractDungeon.screen + ")");
            return false;
        }
        java.util.List<AbstractCard> best = new java.util.ArrayList<AbstractCard>(candidates);
        java.util.Collections.sort(best, new java.util.Comparator<AbstractCard>() {
            public int compare(AbstractCard a, AbstractCard b) {
                return cardValue(b) - cardValue(a);
            }
        });
        int want = 1;
        try {
            if (selectionKind() == SelectionKind.HAND) {
                want = Math.max(1, AbstractDungeon.handCardSelectScreen.numCardsToSelect);
            } else if (selectionKind() == SelectionKind.GRID) {
                want = Math.max(1, gridSelectCount());
            }
        } catch (Throwable ignored) {
            want = 1;
        }
        java.util.List<AbstractCard> chosen = new java.util.ArrayList<AbstractCard>();
        for (int i = 0; i < best.size() && chosen.size() < want; i++) {
            chosen.add(best.get(i));
        }
        return answerLiveSelection(chosen, namesOf(chosen));
    }

    /**
     * B203 (user m01393): answer the open screen the way the SEARCH walks it -- the first option.
     *
     * <p>The sandbox takes exactly one click per screen it walks ({@code Solver.expandScreens}:
     * {@code resolveSelection(options.get(0))} over {@code selectionOptions(...)}), so "the plan
     * records the screen but not the pick" has one live equivalent, and it is this one -- not "the
     * best card", which is a different rule the search never made and which picks a card the plan
     * did not assume. Nothing here decides WHICH card is good: the order is the screen's own.
     */
    public static boolean resolveSelectionLiveFirst(String kindLabel) {
        selectionFailure = "";
        SelectionKind kind = selectionKind();
        if (kind == SelectionKind.NONE) {
            refused("not-settled", "no select screen is open");
            return false;
        }
        java.util.List<AbstractCard> candidates = selectionCandidates();
        if (candidates == null || candidates.isEmpty()) {
            refused("not-offered", "the screen offers nothing to take (" + selectionState() + ")");
            return false;
        }
        AbstractCard pick = candidates.get(0);
        refused("plan-recorded-no-pick", "the plan recorded the " + kindLabel + " screen but not its pick;"
                + " taking " + pick.cardID + ", the first option, as the search does");
        Log.info("live selection: first option [" + pick.cardID + "] state="
                + sts1solver.mirror.CardPrint.of(pick) + " of [" + brief(candidates, 10) + "]"
                + " (the plan recorded no pick for this screen)");
        java.util.List<AbstractCard> one = new java.util.ArrayList<AbstractCard>();
        one.add(pick);
        return answerLiveSelection(one, pick.cardID);
    }

    /**
     * B163 (user m16041): the same ordering value, readable from the search package (which decides
     * which card a reorder answer moves). It is only a heuristic for ORDERING candidates; nothing is
     * decided by it.
     */
    public static int orderValue(AbstractCard card) {
        return cardValue(card);
    }

    /** Rough worth of a card, used only to order choices. */
    static int cardValue(AbstractCard card) {
        int v = 0;
        v += card.damage > 0 ? card.damage * 2 : 0;
        v += card.block > 0 ? card.block * 2 : 0;
        v += Math.max(0, card.magicNumber) * 2;
        v += card.cost < 0 ? 2 : Math.max(0, 3 - card.cost);
        if (card.upgraded) {
            v += 2;
        }
        if (card.rarity != null) {
            v += card.rarity.ordinal();
        }
        return v;
    }

    public static boolean resolveSelection(java.util.List<AbstractCard> chosen) {
        return resolveSelection(chosen, java.util.Collections.<AbstractCard>emptyList());
    }

    /**
     * B163 (user m16041): move cards to the END of the hand, exactly as "select, then deselect"
     * leaves them.
     *
     * <p>Engine facts: {@code HandCardSelectScreen.selectHoveredCard()} takes the card out of the
     * hand and puts it into {@code selectedCards}; clicking a selected card again calls
     * {@code player.hand.addToTop(card)} and drops it from {@code selectedCards};
     * {@code CardGroup.addToTop} APPENDS to the group. The net state is "same hand, this card last",
     * which is what this method writes -- the intermediate selection state is not observed by any
     * consumer, because the action reads {@code selectedCards} only after the screen closes.
     */
    private static boolean moveToEndOfHand(java.util.List<AbstractCard> moved) {
        if (moved == null || moved.isEmpty()) {
            return true;
        }
        com.megacrit.cardcrawl.cards.CardGroup hand =
                AbstractDungeon.player == null ? null : AbstractDungeon.player.hand;
        if (hand == null) {
            refused("not-offered", "there is no hand to reorder");
            return false;
        }
        for (int i = 0; i < moved.size(); i++) {
            AbstractCard c = rebind(moved.get(i), hand.group);
            if (c == null) {
                c = moved.get(i);
            }
            if (!hand.group.contains(c)) {
                refused("not-offered", "the plan asks to move [" + c.cardID
                        + "] to the end of the hand, but it is not in the hand");
                return false;
            }
            hand.removeCard(c);
            hand.addToTop(c);
            c.setAngle(0.0f, false);
        }
        hand.refreshHandLayout();
        return true;
    }

    /**
     * B163 (user m16041): the same, after moving {@code moved} to the END of the hand.
     *
     * <p>A player standing in front of a hand picker can click a card (it enters the selection) and
     * click it again (it goes back to the END of the hand). The net effect on the hand is one move
     * of that card to the back, and the hand order is engine state (唐 plays a uniformly random hand
     * card; end-of-turn discards run in hand order), so the search has to be able to say it. This is
     * applied before the picks are filled, exactly in the order the player would click them.
     */
    public static boolean resolveSelection(java.util.List<AbstractCard> chosen,
                                          java.util.List<AbstractCard> moved) {
        SelectionKind kind = selectionKind();
        if (moved != null && !moved.isEmpty() && kind != SelectionKind.HAND) {
            // A grid or reward screen has no hand order to speak of; reordering it would be inventing
            // an effect, so the answer is refused instead of silently ignored.
            refused("not-offered", "only a hand picker can be reordered (" + selectionState() + ")");
            return false;
        }
        // B139 (user m08865/m08974): the sandbox used to answer screens with NO record of what the
        // screen offered or whether the answer had any effect at all, so a step where the fight
        // answered four screens and moved no card looked exactly like a step that worked. Record
        // the first few answers of a session (throttled: screens are rare, nodes are not).
        boolean trace = selectionTraceLeft > 0;
        String traceBefore = trace ? Digest.stable(Digest.ofCurrent()) : null;
        java.util.List<AbstractCard> traceOffered = trace ? selectionCandidates() : null;
        try {
            if (kind == SelectionKind.HAND) {
                // B151 (user m13424/m13425): the real answer is "write selectedCards, close the
                // screen, look away" -- HandCardSelectScreen.update() on a click fills the selection
                // and calls AbstractDungeon.closeCurrentScreen() in the same frame; the waiting
                // action reads it on the NEXT frame, once the screen is down. Reproduce exactly that:
                // no action-manager advance while the screen is up (the game freezes it), close, and
                // let drain() resume the queue. The old order (fill, pump the manager, then close)
                // ran actions queued behind the waiting one before it had read anything.
                // B163: the click script runs FIRST -- reorder the hand, then take the picks.
                if (!moveToEndOfHand(moved)) {
                    return false;
                }
                if (!fillHandSelection(chosen)) {
                    return false;
                }
                boolean closed = closeHandSelection();
                // B153: drainAfterAnswer resumes the queue with the screen down, exactly like the frame
                // after a real click, and finishes it even when the answer unblocked a whole end turn.
                // If that action asks a second question, the new screen is still up when this returns:
                // the caller (Solver.expandScreens) answers it next.
                drainAfterAnswer();
                return closed;
            }
            if (kind == SelectionKind.GRID) {
                // ORDER MATTERS (same reason as the hand path): the action that opened the grid
                // reads selectedCards on its NEXT update and only then moves the card. Pumping
                // before the selection is filled was a no-op, and closing before pumping skipped
                // the consumption entirely. Observed bug: Headbutt opened the discard picker, the
                // screen closed, and no card ever reached the draw pile.
                if (chosen.isEmpty()) {
                    boolean ok = closeGridScreen();
                    drainAfterAnswer();
                    return ok;
                }
                // B151: same order as the hand path -- fill, close, then let the queue resume. A grid
                // click only checks the card and returns; the screen closes on Confirm, and the action
                // reads selectedCards afterwards either way. A second question opened by the same
                // action is still up when this returns (P2-57/B86) and the caller answers it.
                if (!fillGridSelection(chosen)) {
                    return false;
                }
                boolean ok = closeGridScreen();
                drainAfterAnswer();
                return ok;
            }
            if (kind == SelectionKind.REWARD) {
                // No pump first: the action that opened this screen is FROZEN until the screen
                // closes (AbstractRoom.update only calls actionManager.update when !isScreenUp).
                // Answering = exactly what a real click does, then let the action retrieve.
                boolean ok = chosen.isEmpty() || answerCardReward(chosen.get(0));
                drainAfterAnswer();
                return ok;
            }
        } catch (Throwable t) {
            Log.warn("resolveSelection failed: " + t);
        } finally {
            if (trace) {
                traceSandboxAnswer(kind, traceOffered, chosen, traceBefore);
            }
            clearScrySelection();
            scryArmed = false;
        }
        return kind == SelectionKind.NONE;
    }

    /** B139: one line per answered screen in the sandbox, capped per session. */
    private static void traceSandboxAnswer(SelectionKind kind, java.util.List<AbstractCard> offered,
            java.util.List<AbstractCard> chosen, String before) {
        if (--selectionTraceLeft < 0) {
            return;
        }
        try {
            String after = Digest.stable(Digest.ofCurrent());
            Log.info("sandbox selection: kind=" + kind + " offered=[" + brief(offered, 10)
                    + "] picked=[" + brief(chosen, 10) + "] changed=" + !after.equals(before)
                    + " after=" + after);
        } catch (Throwable t) {
            Log.warn("sandbox selection trace: " + t);
        }
    }

    /**
     * Answers an open card-reward / choose-one screen exactly the way a click does.
     *
     * <p>Three flavours, all real engine paths:
     * <ul>
     *   <li>discovery (Discovery, the four "Potion of <type>" potions): write {@code discoveryCard}.
     *       The waiting DiscoveryAction then copies that card into the hand -- writing the field IS
     *       the click, see {@code CardRewardScreen.cardSelectUpdate}.</li>
     *   <li>choose-one (Foreign Influence and friends): {@code onChoseThisOption()} plus the exhaust
     *       effect, again exactly what the click path does.</li>
     *   <li>codex (Nilry's Codex): write {@code codexCard}.</li>
     * </ul>
     * A draft or a plain post-combat reward is NOT answered here: those are not combat decisions.
     */
    public static boolean answerCardReward(AbstractCard pick) {
        if (pick == null) {
            return false;
        }
        com.megacrit.cardcrawl.screens.CardRewardScreen r = AbstractDungeon.cardRewardScreen;
        if (r == null) {
            return false;
        }
        try {
            if (rewardFlag(r, "discovery")) {
                r.discoveryCard = pick;
            } else if (rewardFlag(r, "chooseOne")) {
                pick.onChoseThisOption();
                AbstractDungeon.effectList.add(
                        new com.megacrit.cardcrawl.vfx.cardManip.ExhaustCardEffect(pick));
            } else if (rewardFlag(r, "codex")) {
                r.codexCard = pick;
            } else {
                Log.info("reward screen is not a combat choice; left alone (" + cardRewardSummary() + ")");
                return false;
            }
            AbstractDungeon.closeCurrentScreen();
            noteRewardPick(pick.cardID);
            return true;
        } catch (Throwable t) {
            Log.warn("answerCardReward failed: " + t);
            return false;
        }
    }

    /**
     * B137 (m08397): the search answers a Discovery / choose-one / Codex screen on almost every node
     * that opens one, and one {@code INFO} line per answer made a single fight write 16.8 M lines
     * (the user's log reached 17 MB and log4j rotated it every few seconds, on the render thread).
     * The information is worth keeping, the per-node line is not: the first answer of a burst is
     * logged whole, the rest are folded into one line every few seconds.
     */
    private static volatile int rewardPicks;
    private static volatile String rewardPickLast;
    private static long rewardPickLogAt;

    /** How many reward screens the sandbox answered since it started (probe/gate accessor). */
    public static int rewardPicks() {
        return rewardPicks;
    }

    private static void noteRewardPick(String cardId) {
        ++rewardPicks;
        rewardPickLast = cardId;
        long now = System.currentTimeMillis();
        if (rewardPickLogAt == 0L) {
            rewardPickLogAt = now;
            Log.info("reward pick answered with " + cardId);
            return;
        }
        long waited = now - rewardPickLogAt;
        if (waited < 5000L) {
            return;
        }
        Log.info("reward picks answered: " + (rewardPicks - rewardPicksLogged)
                + " in the last " + (waited / 1000L) + "s (last " + cardId + ", "
                + rewardPicks + " so far)");
        rewardPicksLogged = rewardPicks;
        rewardPickLogAt = now;
    }

    private static volatile int rewardPicksLogged;

    /** Highest-value card of a group (used only to order or default a choice). */
    private static AbstractCard bestOf(java.util.List<AbstractCard> cards) {
        AbstractCard best = null;
        for (int i = 0; i < cards.size(); i++) {
            AbstractCard c = cards.get(i);
            if (c != null && (best == null || cardValue(c) > cardValue(best))) {
                best = c;
            }
        }
        return best;
    }

    /** Harness hook: how many cards the open GRID screen wants. */
    public static int gridSelectCountForTest() {
        return gridSelectCount();
    }

    /**
     * B133 (user m06893): does the engine keep the room (and the action manager) off this frame?
     *
     * <p>Transcribed from {@code AbstractDungeon.update()} bytecode; this is the game's own scheduler,
     * not a rule invented here. NONE and NO_INTERACT run the room, FTUE and BOSS_REWARD run it, and
     * HAND_SELECT runs it too (which is why a hand pick consumes the manager's frames). GRID and
     * CARD_REWARD run it only while the player is peeking at the draw pile through the Peek button.
     * Every other screen replaces the room for that frame.
     */
    public static boolean roomBlockedByScreen() {
        if (!AbstractDungeon.isScreenUp || peekingNow()) {
            return false;
        }
        AbstractDungeon.CurrentScreen s = AbstractDungeon.screen;
        if (s == null) {
            return false;
        }
        switch (s) {
            case NONE:
            case NO_INTERACT:
            case FTUE:
            case BOSS_REWARD:
            case HAND_SELECT:
                return false;
            default:
                return true;
        }
    }

    private static boolean peekingNow() {
        try {
            return com.megacrit.cardcrawl.ui.buttons.PeekButton.isPeeking;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Pumps engine frames until a selection screen is open (or nothing more happens).
     *
     * <p>Needed because the action that opens a screen may open it several frames after the previous
     * one closed -- the screen is not a return value, it appears in the middle of an update.
     *
     * @param maxFrames how many engine updates to spend waiting
     * @return true when a screen is open now
     */
    public static boolean pumpUntilSelection(int maxFrames) {
        if (AbstractDungeon.isScreenUp) {
            // B202 (user m01245): a screen that is UP but not yet "awaiting" (its open animation is
            // still running, selectedCards not filled in) used to be pumped through -- the click
            // landed on the frame it appeared. Leave the frame to the screen's own update.
            return true;
        }
        if (isAwaitingSelection()) {
            return true;
        }
        // B106: a screen that is open but not yet "awaiting" is normally one frame short of filling
        // itself in (prep/open bookkeeping). The action manager never advances a screen, so pumping it
        // alone could never turn that screen into a decision -- which is exactly how a screen that
        // really was waiting for an answer got recorded as "nothing happened".
        pumpScreenSelf(4);
        if (isAwaitingSelection()) {
            return true;
        }
        GameActionManager gam = AbstractDungeon.actionManager;
        if (gam == null) {
            return false;
        }
        for (int i = 0; i < Math.max(1, maxFrames); i++) {
            try {
                if (managerFrozen()) {
                    // B133/B151: while a screen is up the engine runs neither the room nor the action
                    // manager, so pumping the manager here would advance it once per attempted frame
                    // (a Discovery screen would reroll its three cards every time). Drive the screen
                    // instead; the queue resumes after the answer.
                    pumpScreenSelf(1);
                } else {
                    boolean wasOpen = AbstractDungeon.isScreenUp;
                    int rngBefore = screenDrawCount();
                    gam.update();
                    noteFrameDraws("sandbox-side pump", wasOpen, rngBefore);
                }
            } catch (Throwable t) {
                Log.warn("pumpUntilSelection: update threw: " + t);
                return false;
            }
            if (isAwaitingSelection()) {
                return true;
            }
            pumpScreenSelf(2);
            if (isAwaitingSelection()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drives the OPEN screen's own {@code update()} a bounded number of times.
     *
     * <p>Only while a hand/grid screen is up and not yet awaiting an answer, and only a few frames: the
     * point is to let the screen finish its own open/prep bookkeeping ({@code numCardsToSelect},
     * {@code targetGroup}), never to click anything or close it. Input is muted in the sandbox, so an
     * update cannot select a card.
     */
    private static void pumpScreenSelf(int maxFrames) {
        try {
            for (int i = 0; i < Math.max(0, maxFrames) && !isAwaitingSelection(); i++) {
                if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.HAND_SELECT
                        && AbstractDungeon.handCardSelectScreen != null) {
                    AbstractDungeon.handCardSelectScreen.update();
                } else if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.GRID
                        && AbstractDungeon.gridSelectScreen != null) {
                    AbstractDungeon.gridSelectScreen.update();
                } else {
                    return;
                }
            }
        } catch (Throwable t) {
            Log.warnEvery("pump-screen-self", 30000L, "pumping the open screen threw: " + t);
        }
    }

    /**
     * What the open screen is asking, as a string: kind plus the offered cards.
     *
     * <p>Two consecutive screens of the same kind are still two different questions (HeadChase asks
     * the discard pile and then the draw pile), so the cards are part of the identity.
     */
    public static String selectionSignature() {
        SelectionKind kind = selectionKind();
        if (kind == SelectionKind.NONE) {
            return "none";
        }
        return kind + ":" + namesOf(selectionCandidates());
    }

    /**
     * The identity of the question being asked, for telling a NEW question from the same one.
     *
     * <p>The card list cannot be the identity: answering makes the acting action move those cards,
     * which changes the list even though nobody asked anything new (HeadChase swaps the two piles
     * while its own screen is still up). What does change on a real second question is the group the
     * screen asks about -- the engine hands {@code open()} a different pile. So the identity is the
     * screen kind plus that group object.
     *
     * <p>Known limit, on purpose: an action that asks twice about the SAME group looks like one
     * question here. No engine or mod card we support does that (Headbutt/HeadChase, Dredge,
     * Hologram, Secret Weapon all hand over a different pile each time).
     */
    public static String selectionQuestion() {
        SelectionKind kind = selectionKind();
        if (kind == SelectionKind.NONE) {
            return "none";
        }
        String group = "?";
        try {
            if (kind == SelectionKind.GRID) {
                group = "" + System.identityHashCode(AbstractDungeon.gridSelectScreen.targetGroup);
            } else if (kind == SelectionKind.HAND) {
                group = "" + System.identityHashCode(AbstractDungeon.player.hand);
            } else if (kind == SelectionKind.REWARD) {
                group = "" + System.identityHashCode(AbstractDungeon.cardRewardScreen.rewardGroup);
            }
        } catch (Throwable t) {
            group = "err";
        }
        return kind + "#" + group;
    }

    private static String names(com.megacrit.cardcrawl.cards.CardGroup group) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < group.group.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(group.group.get(i) == null ? "null" : group.group.get(i).cardID);
        }
        return sb.append(']').toString();
    }

    /**
     * Puts the chosen cards into the hand screen's selection exactly the way a click does.
     *
     * <p>The click path is {@code HandCardSelectScreen.selectHoveredCard()}: remove the card from
     * the hand, refresh the layout, add it to {@code selectedCards}. Removing is not cosmetic -- some
     * consumers never move the card themselves. CaiNongMod's GetInside only stores the picks for a
     * later play, so with a fill-only emulation the cards sat in BOTH the hand and the selection: the
     * panel showed the picks, nothing left the hand, and the next draw differed from the sandbox
     * (user m25378). Vanilla actions were written against the real click path, so they cope with the
     * card already being out of the hand (ArmamentsAction re-adds it: "p.hand.addToTop(c)").
     *
     * <p>Idempotent on purpose: the live path re-fills the same selection every frame until the
     * waiting action consumes it (B66), and by then the cards are no longer in the hand. A card that
     * this screen already took is simply put back into the selection.
     */
    private static boolean fillHandSelection(java.util.List<AbstractCard> chosen) {
        com.megacrit.cardcrawl.screens.select.HandCardSelectScreen screen =
                AbstractDungeon.handCardSelectScreen;
        java.util.List<AbstractCard> previous =
                new java.util.ArrayList<AbstractCard>(screen.selectedCards.group);
        com.megacrit.cardcrawl.cards.CardGroup hand =
                AbstractDungeon.player == null ? null : AbstractDungeon.player.hand;
        int want = Math.max(1, screen.numCardsToSelect);
        java.util.List<AbstractCard> here = hand == null
                ? new java.util.ArrayList<AbstractCard>()
                : new java.util.ArrayList<AbstractCard>(hand.group);
        java.util.List<AbstractCard> removed = new java.util.ArrayList<AbstractCard>();
        screen.selectedCards.group.clear();
        for (int i = 0; i < chosen.size() && screen.selectedCards.group.size() < want; i++) {
            // B200: ... the pool excludes what this fill already took (see fillGridSelection).
            java.util.List<AbstractCard> pool =
                    withoutIdentical(here, screen.selectedCards.group);
            AbstractCard c = rebind(chosen.get(i), pool);
            if (c == null) {
                continue;
            }
            screen.selectedCards.group.add(c);
            if (hand != null && hand.group.contains(c)) {
                hand.removeCard(c);
                removed.add(c);
            }
        }
        if (!removed.isEmpty() && hand != null) {
            hand.refreshHandLayout();
            for (int i = 0; i < removed.size(); i++) {
                removed.get(i).setAngle(0.0f, false);
            }
        }
        if (screen.selectedCards.group.size() < want && !screen.canPickZero) {
            // Leave the screen exactly as we found it, so a refused answer can be retried.
            if (hand != null) {
                for (int i = 0; i < removed.size(); i++) {
                    hand.addToTop(removed.get(i));
                }
                hand.refreshHandLayout();
            }
            screen.selectedCards.group.clear();
            screen.selectedCards.group.addAll(previous);
            return false;
        }
        return true;
    }

    /**
     * Closes the screen the way the confirm button ultimately does, WITHOUT pumping the screen's own
     * update loop.
     *
     * <p>Pumping it was wrong: the screen's update advances its close timer and clears
     * {@code selectedCards}, so the action that is waiting on the selection saw an empty group and
     * silently discarded nothing (observed: "Prepared" drew a card but discarded none). The consumer
     * action needs the selection to still be there when it runs.
     */
    private static boolean closeHandSelection() {
        try {
            AbstractDungeon.handCardSelectScreen.button.isDisabled = false;
            AbstractDungeon.handCardSelectScreen.button.enable();
        } catch (Throwable ignored) {
            // the button state is cosmetic for the sandbox
        }
        try {
            AbstractDungeon.closeCurrentScreen();
        } catch (Throwable t) {
            Log.warn("closeHandSelection: closeCurrentScreen threw: " + t);
        }
        return selectionKind() == SelectionKind.NONE;
    }

    /**
     * Fills the open grid screen's selection WITHOUT closing it.
     *
     * <p>The waiting action (e.g. DiscardPileToTopOfDeckAction, ExhumeAction) only moves the card
     * when it sees a non-empty {@code selectedCards} on a later update, so closing first loses the
     * effect. See the order note in {@link #resolveSelection}.
     */
    private static boolean fillGridSelection(java.util.List<AbstractCard> chosen) {
        com.megacrit.cardcrawl.screens.select.GridCardSelectScreen screen =
                AbstractDungeon.gridSelectScreen;
        if (screen == null) {
            return false;
        }
        screen.selectedCards.clear();
        if (chosen.isEmpty()) {
            // B190 (user m01149): "pick nothing" on a grid is legal exactly where the engine lets the
            // confirm through with nothing selected; everywhere else it is refused, never guessed.
            return screen.anyNumber || screen.isJustForConfirming;
        }
        int want = Math.max(1, gridSelectCount());
        java.util.List<AbstractCard> here = selectionCandidates();
        for (int i = 0; i < chosen.size() && screen.selectedCards.size() < want; i++) {
            // B200: rebind keys on state, so two print-identical copies would both resolve to the
            // first one and a "take two Strikes" answer would pick one card twice. Cards this fill
            // already took leave the pool, so the second pick gets the next copy.
            java.util.List<AbstractCard> pool =
                    withoutIdentical(here, screen.selectedCards);
            AbstractCard c = rebind(chosen.get(i), pool);
            if (c != null) {
                screen.selectedCards.add(c);
            }
        }
        return !screen.selectedCards.isEmpty();
    }

    /**
     * {@code all} without the cards that appear in {@code taken}, compared BY REFERENCE.
     *
     * <p>Not {@code List.removeAll}: that goes through {@code equals}, and a mod card may override it
     * (two different physical cards would then be treated as one).
     */
    private static java.util.List<AbstractCard> withoutIdentical(java.util.List<AbstractCard> all,
                                                                 java.util.List<AbstractCard> taken) {
        java.util.List<AbstractCard> out = new java.util.ArrayList<AbstractCard>(all.size());
        for (int i = 0; i < all.size(); i++) {
            AbstractCard c = all.get(i);
            boolean skip = false;
            for (int k = 0; k < taken.size(); k++) {
                if (taken.get(k) == c) {
                    skip = true;
                    break;
                }
            }
            if (!skip) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Maps a card picked in one clone onto this clone's copy of it (P2-57).
     *
     * <p>The search explores options on a fork; the objects in that fork are different instances of
     * the same card. Feeding the fork's instance into the live screen's {@code selectedCards} makes
     * the game's own {@code discardPile.removeCard(c)} / {@code hand.moveToDeck(c)} miss (they match
     * by identity), so the card would stay in its pile and appear in two places at once. UUID
     * survives cloning, so it is the first key; id plus upgrade level is the fallback for a card the
     * screen recreated.
     *
     * <p>The state pass (B200) sits between them and uses the same rule as {@link
     * #matchOffered}: a card is identified by what it IS, and two copies with one print are
     * interchangeable, so the first occurrence wins on both sides.
     */
    private static AbstractCard rebind(AbstractCard wanted, java.util.List<AbstractCard> here) {
        if (wanted == null) {
            return null;
        }
        String uuid = wanted.uuid == null ? "" : wanted.uuid.toString();
        for (int i = 0; i < here.size(); i++) {
            AbstractCard c = here.get(i);
            if (c == null || c.uuid == null) {
                continue;
            }
            if (c == wanted) {
                return c;
            }
            if (!uuid.isEmpty() && uuid.equals(c.uuid.toString())) {
                return c;
            }
        }
        String print = sts1solver.mirror.CardPrint.of(wanted);
        for (int i = 0; i < here.size(); i++) {
            AbstractCard c = here.get(i);
            if (c != null && print.equals(sts1solver.mirror.CardPrint.of(c))) {
                return c;
            }
        }
        for (int i = 0; i < here.size(); i++) {
            AbstractCard c = here.get(i);
            if (c != null && c.cardID.equals(wanted.cardID)
                    && c.timesUpgraded == wanted.timesUpgraded) {
                return c;
            }
        }
        return wanted;
    }

    /** Closes the grid screen after its waiting action has consumed the selection. */
    private static boolean closeGridScreen() {
        // B151: the engine's own invariant is "previousScreen is null or a real screen". Our synthetic
        // worlds used to write NONE into it (Synthetic/clearSelection), and closeCurrentScreen() then
        // restored that value: screen = NONE but isScreenUp left TRUE, which froze the action manager
        // for good. Normalise the impossible value here as well, so a world built by an older path
        // still closes cleanly instead of hanging the queue.
        try {
            if (AbstractDungeon.previousScreen == AbstractDungeon.CurrentScreen.NONE) {
                AbstractDungeon.previousScreen = null;
            }
        } catch (Throwable ignored) {
            // best effort; the close below is what matters
        }
        try {
            AbstractDungeon.closeCurrentScreen();
        } catch (Throwable t) {
            Log.warn("closeGridScreen: closeCurrentScreen threw: " + t);
        }
        return selectionKind() == SelectionKind.NONE;
    }

    private static int gridSelectCount() {
        try {
            java.lang.reflect.Field f = sts1solver.reflect.Reflect.find(
                    com.megacrit.cardcrawl.screens.select.GridCardSelectScreen.class, "numCards");
            Object v = f == null ? null : sts1solver.reflect.Reflect.get(f,
                    AbstractDungeon.gridSelectScreen);
            if (v instanceof Integer && ((Integer) v).intValue() > 0) {
                return ((Integer) v).intValue();
            }
        } catch (Throwable ignored) {
            // fall back to one card
        }
        return 1;
    }

    /**
     * True when a CARD-CHOICE screen (reward / choose-one) is open.
     *
     * <p>Potions such as Attack Potion make the player choose one of three cards; that is not a
     * hand-select screen, and the old code neither answered it nor reported it, so the potion looked
     * like it did nothing. It is reported so the caller can hand control back.
     */
    public static boolean isAwaitingCardChoice() {
        try {
            if (isCombatCardReward()) {
                // Answerable: see resolveSelectionLive/answerCardReward. Only screens we cannot
                // answer are reported as "hand control back".
                return false;
            }
            if (!inCombatPhase()) {
                // B71 (m20521-2): outside a fight no screen is a combat decision. The post-combat
                // three-card reward, the shop, an event, the map -- none of them belong to the plan,
                // and the old code threw the rest of a winning line away because the card reward
                // screen object is a live singleton that always "looks open".
                return false;
            }
            AbstractDungeon.CurrentScreen screen = AbstractDungeon.screen;
            return screen == AbstractDungeon.CurrentScreen.CARD_REWARD
                    || screen == AbstractDungeon.CurrentScreen.CHOOSE_ONE;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when a card-REWARD style screen (choose 1 of 3) is open and offering cards.
     *
     * <p>Discovery, Foreign Influence, Nilry's Codex and the four "Potion of <type>" potions all open
     * this screen: the engine builds the three choices itself, and the action that built them WAITS
     * for {@code cardRewardScreen.discoveryCard} (or for {@code onChoseThisOption()} when the pick is
     * a choose-one card). Treating it as a dead end ("this step opens a card screen") meant the
     * search never branched on the choice and the live plan could not answer it -- the reported
     * "Discovery is not simulated, and autoplay does not pick".
     */
    public static boolean isCardRewardOpen() {
        try {
            AbstractDungeon.CurrentScreen screen = AbstractDungeon.screen;
            if (screen != AbstractDungeon.CurrentScreen.CARD_REWARD
                    && screen != AbstractDungeon.CurrentScreen.CHOOSE_ONE) {
                return false;
            }
            if (!inCombatPhase()) {
                // B71 (m20521-2): the post-combat three-card reward is the same screen class. It is
                // not a combat decision, so it must never reach the search or the autopilot.
                return false;
            }
            com.megacrit.cardcrawl.screens.CardRewardScreen reward = AbstractDungeon.cardRewardScreen;
            return reward != null && reward.rewardGroup != null && !reward.rewardGroup.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * B71 (m20521-2): true only for the card screens a COMBAT ACTION opens and then waits on --
     * Discovery, Foreign Influence, Nilry's Codex, Choose One and the "Potion of &lt;type&gt;" potions.
     *
     * <p>The engine sets one of those three flags in {@code customCombatOpen}/{@code chooseOneOpen}/
     * {@code codexOpen} and leaves them set; the post-combat reward screen has none of them. Answering
     * a screen without a waiting action does nothing, which is why the sandbox used to burn its whole
     * budget on thousands of "reward pick answered with ..." lines in a fight that never opened one.
     */
    public static boolean isCombatCardReward() {
        if (!isCardRewardOpen()) {
            return false;
        }
        try {
            com.megacrit.cardcrawl.screens.CardRewardScreen r = AbstractDungeon.cardRewardScreen;
            return rewardFlag(r, "discovery") || rewardFlag(r, "chooseOne") || rewardFlag(r, "codex");
        } catch (Throwable t) {
            return false;
        }
    }

    /** True while the current room is still a fight (the post-combat reward phase is COMPLETE). */
    public static boolean inCombatPhase() {
        try {
            com.megacrit.cardcrawl.rooms.AbstractRoom room = AbstractDungeon.getCurrRoom();
            return room != null && room.phase == com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMBAT;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Diagnostics: which screen the game has up right now, and in which room phase. */
    public static String screenName() {
        try {
            return String.valueOf(AbstractDungeon.screen) + " isScreenUp=" + AbstractDungeon.isScreenUp
                    + " phase=" + (AbstractDungeon.getCurrRoom() == null ? "?" : AbstractDungeon.getCurrRoom().phase);
        } catch (Throwable t) {
            return "screen=?";
        }
    }

    /** Diagnostics: what the reward screen holds and which flavour it is (discovery/codex/choose-one). */
    public static String cardRewardSummary() {
        try {
            com.megacrit.cardcrawl.screens.CardRewardScreen r = AbstractDungeon.cardRewardScreen;
            if (r == null) {
                return "reward=null";
            }
            return "reward[screen=" + AbstractDungeon.screen + " isScreenUp=" + AbstractDungeon.isScreenUp
                    + " group=" + (r.rewardGroup == null ? 0 : r.rewardGroup.size())
                    + " discovery=" + rewardFlag(r, "discovery")
                    + " chooseOne=" + rewardFlag(r, "chooseOne")
                    + " codex=" + rewardFlag(r, "codex")
                    + " discoveryCard=" + (r.discoveryCard == null ? "-" : r.discoveryCard.cardID)
                    + "]";
        } catch (Throwable t) {
            return "reward=?";
        }
    }

    private static boolean rewardFlag(com.megacrit.cardcrawl.screens.CardRewardScreen r, String name) {
        java.lang.reflect.Field f = sts1solver.reflect.Reflect.find(
                com.megacrit.cardcrawl.screens.CardRewardScreen.class, name);
        Object v = f == null ? null : sts1solver.reflect.Reflect.get(f, r);
        return v instanceof Boolean && ((Boolean) v).booleanValue();
    }

    public static boolean isAwaitingSelection() {
        try {
            if (isCombatCardReward()) {
                // B71: only a card screen a combat action is waiting on counts as a selection.
                return true;
            }
            AbstractDungeon.CurrentScreen screen = AbstractDungeon.screen;
            if (screen == AbstractDungeon.CurrentScreen.GRID) {
                return AbstractDungeon.gridSelectScreen != null
                        && AbstractDungeon.gridSelectScreen.targetGroup != null;
            }
            if (screen == AbstractDungeon.CurrentScreen.HAND_SELECT) {
                com.megacrit.cardcrawl.screens.select.HandCardSelectScreen h =
                        AbstractDungeon.handCardSelectScreen;
                if (h == null || h.wereCardsRetrieved) {
                    // The cards were already taken; the screen is only playing its close animation.
                    // Reading it as a decision is what made a replay demand an answer the plan could
                    // never have stored (B106).
                    return false;
                }
                // B106: "retain up to 2" / "discard any number" keep the cap in upTo/anyNumber and may
                // leave numCardsToSelect at 0. The old test (numCardsToSelect > 0) called those "no
                // selection", so expansion never recorded an answer for them, while replay -- which
                // only looked at AbstractDungeon.screen -- insisted on one.
                return h.numCardsToSelect > 0 || h.upTo || anyNumber(h);
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The private "any number" flag of a hand-select screen: {@code open(msg,n,anyNumber,...)}. */
    private static boolean anyNumber(com.megacrit.cardcrawl.screens.select.HandCardSelectScreen h) {
        try {
            java.lang.reflect.Field f = sts1solver.reflect.Reflect.find(
                    com.megacrit.cardcrawl.screens.select.HandCardSelectScreen.class, "anyNumber");
            Object v = f == null ? null : sts1solver.reflect.Reflect.get(f, h);
            return v instanceof Boolean && ((Boolean) v).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * How many cards the open screen will accept at most (0 = "any number", bounded by the hand).
     *
     * <p>For an "up to N" screen this is N; for an any-number one it is the hand size.
     */
    public static int selectionMaxCards() {
        try {
            if (selectionKind() != SelectionKind.HAND) {
                return selectionKind() == SelectionKind.GRID ? Math.max(1, gridSelectCount()) : 0;
            }
            com.megacrit.cardcrawl.screens.select.HandCardSelectScreen h =
                    AbstractDungeon.handCardSelectScreen;
            if (h == null) {
                return 0;
            }
            if (anyNumber(h)) {
                return Math.max(0, AbstractDungeon.player.hand.size());
            }
            if (h.upTo) {
                return Math.max(0, h.numCardsToSelect > 0
                        ? h.numCardsToSelect : AbstractDungeon.player.hand.size());
            }
            return Math.max(1, h.numCardsToSelect);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * B205 (user m01995): the hand picker's own state, shortened -- what a replay error adds when
     * the plan expected another screen in a chain and the engine opened none.
     */
    public static String handScreenSummary() {
        try {
            com.megacrit.cardcrawl.screens.select.HandCardSelectScreen h =
                    AbstractDungeon.handCardSelectScreen;
            if (h == null) {
                return "null";
            }
            return "numCardsToSelect=" + h.numCardsToSelect + " wereCardsRetrieved="
                    + h.wereCardsRetrieved + " numSelected=" + h.numSelected;
        } catch (Throwable t) {
            return "? (" + t + ")";
        }
    }

    /** One line describing the open screen, for a log that has to explain a refusal (B106). */
    public static String selectionState() {
        try {
            com.megacrit.cardcrawl.screens.select.HandCardSelectScreen h =
                    AbstractDungeon.handCardSelectScreen;
            String hand = h == null ? "hand-screen=null"
                    : ("numCardsToSelect=" + h.numCardsToSelect + " upTo=" + h.upTo
                       + " canPickZero=" + h.canPickZero + " anyNumber=" + anyNumber(h)
                       + " wereCardsRetrieved=" + h.wereCardsRetrieved + " numSelected=" + h.numSelected);
            return "screen=" + AbstractDungeon.screen + " " + hand + " kind=" + selectionKind()
                    + " awaiting=" + isAwaitingSelection();
        } catch (Throwable t) {
            return "screen=? (" + t + ")";
        }
    }

    public static void clearSelection() {
        try {
            if (AbstractDungeon.gridSelectScreen != null
                    && AbstractDungeon.gridSelectScreen.targetGroup != null) {
                AbstractDungeon.gridSelectScreen.targetGroup = null;
                AbstractDungeon.gridSelectScreen.selectedCards.clear();
            }
            if (AbstractDungeon.handCardSelectScreen != null) {
                AbstractDungeon.handCardSelectScreen.numCardsToSelect = 0;
                if (AbstractDungeon.handCardSelectScreen.selectedCards != null) {
                    AbstractDungeon.handCardSelectScreen.selectedCards.clear();
                }
                AbstractDungeon.handCardSelectScreen.wereCardsRetrieved = true;
            }
            AbstractDungeon.isScreenUp = false;
            AbstractDungeon.screen = AbstractDungeon.CurrentScreen.NONE;
            // B151: previousScreen is null when there is no screen to go back to. Writing NONE here
            // (an enum value the engine never assigns to this field) made closeCurrentScreen() take its
            // "restore the previous screen" branch: it set screen = NONE but left isScreenUp TRUE, so the
            // action manager stayed frozen after every answered picker.
            AbstractDungeon.previousScreen = null;
        } catch (Throwable ignored) {
            // Best effort; the world swap is the real isolation.
        }
    }

    // ------------------------------------------------------------------ policy

    public static List<AbstractMonster> aliveMonsters() {
        List<AbstractMonster> out = new ArrayList<AbstractMonster>();
        try {
            if (AbstractDungeon.getMonsters() == null
                    || AbstractDungeon.getMonsters().monsters == null) {
                return out;
            }
            List<AbstractMonster> all = AbstractDungeon.getMonsters().monsters;
            for (int i = 0; i < all.size(); i++) {
                AbstractMonster m = all.get(i);
                if (m != null && !m.isDying && !m.isDeadOrEscaped() && m.currentHealth > 0) {
                    out.add(m);
                }
            }
        } catch (Throwable ignored) {
            // No monsters readable means no candidates.
        }
        return out;
    }

    public static boolean needsEnemyTarget(AbstractCard c) {
        return c != null && c.target == AbstractCard.CardTarget.ENEMY;
    }

    public static boolean canUse(AbstractCard c, AbstractPlayer p, AbstractMonster target) {
        try {
            return c.canUse(p, target);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * B127 (user m04994): can the PLAYER click this card right now?
     *
     * <p>The game has two gates and they are not the same one. A mouse click runs
     * {@code hoveredCard.hasEnoughEnergy() && hoveredCard.canUse(...)} (AbstractPlayer.inputUpdate),
     * while the card queue only runs {@code canUse}. The base {@code AbstractCard.canUse} happens to
     * call {@code hasEnoughEnergy()} too, so for vanilla cards both agree -- but a mod card that
     * overrides {@code canUse} without calling super drops the energy check entirely (CaiNong's
     * 按兵不动 answers {@code !isUseAttack}). The search then offered a 3-cost card on 0 energy: the
     * fight could not play it, yet the panel showed a line that could never be executed.
     *
     * <p>Only player-initiated candidates and plays go through this method. The engine's own queue
     * keeps its exemption for autoplay ({@code isInAutoplay} in hasEnoughEnergy), which is how the
     * fight plays cards for free -- that path is not gated here.
     */
    public static boolean playerCanUse(AbstractCard c, AbstractPlayer p, AbstractMonster target) {
        if (c == null) {
            return false;
        }
        try {
            if (!c.hasEnoughEnergy()) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        return canUse(c, p, target);
    }

    /**
     * m21115-1: name the reason the engine would turn this click down.
     *
     * <p>"the game refused" was all the log said, so a refused 恶魔之焰 could be an empty energy
     * bar, a blocking curse in hand, or a turn that had already ended -- three different bugs that
     * looked identical. {@code canUse()} already stores its verdict text in
     * {@code AbstractCard.cantUseMessage}, so quote it next to the numbers it depends on.
     */
    public static String refusalReason(AbstractCard c) {
        if (c == null) {
            return "no card";
        }
        StringBuilder sb = new StringBuilder();
        if (c.cantUseMessage != null && !c.cantUseMessage.isEmpty()) {
            sb.append(c.cantUseMessage);
        }
        sb.append(" cost=").append(c.costForTurn).append(" energy=").append(EnergyPanel.totalCount);
        try {
            if (AbstractDungeon.actionManager != null) {
                sb.append(" turnEnded=").append(AbstractDungeon.actionManager.turnHasEnded);
            }
        } catch (Throwable ignored) {
            // Diagnostic suffix only: the caller still needs the rest of the line.
        }
        return sb.toString();
    }

    public static int energyFor(AbstractCard c, AbstractPlayer p) {
        if (c.cost == -1) {
            return Math.max(0, p.energy == null ? 0 : p.energy.energy);
        }
        return 0;
    }

    /** One candidate action at a decision point. */
    public static final class Play {
        public final AbstractCard card;
        public final AbstractMonster target;
        /** Index of the target inside the monster group; -1 when there is no target. */
        public final int targetIndex;
        public final int energyOnUse;
        /** Potion slot for a potion; -2 for a card, -1 for "end turn". */
        public final int potionSlot;
        /**
         * The potion's name, read while it is still in the belt. The panel used to print
         * {@code POTION#2}: the slot is gone once the potion is drunk, so the label has to be
         * captured here or it can never be spelled out afterwards.
         */
        public final String potionName;

        public Play(AbstractCard card, AbstractMonster target, int energyOnUse, int potionSlot) {
            this(card, target, targetIndex(target), energyOnUse, potionSlot);
        }

        public Play(AbstractCard card, AbstractMonster target, int targetIndex, int energyOnUse,
                    int potionSlot) {
            this.card = card;
            this.target = target;
            this.targetIndex = targetIndex;
            this.energyOnUse = energyOnUse;
            this.potionSlot = potionSlot;
            this.potionName = potionSlot >= 0 ? potionNameAt(potionSlot) : null;
        }

        /** Best effort: a label is not worth throwing over. */
        private static String potionNameAt(int slot) {
            try {
                if (AbstractDungeon.player == null || AbstractDungeon.player.potions == null
                        || slot >= AbstractDungeon.player.potions.size()) {
                    return null;
                }
                com.megacrit.cardcrawl.potions.AbstractPotion p =
                        AbstractDungeon.player.potions.get(slot);
                return p == null ? null : p.name;
            } catch (Throwable ignored) {
                // A missing name falls back to the slot number below.
                return null;
            }
        }

        private static int targetIndex(AbstractMonster m) {
            if (m == null) {
                return -1;
            }
            try {
                return AbstractDungeon.getMonsters().monsters.indexOf(m);
            } catch (Throwable t) {
                return -1;
            }
        }

        public static Play endTurn() {
            return new Play(null, null, -1, 0, -1);
        }

        public boolean isEndTurn() {
            return card == null && potionSlot == -1;
        }

        public String label() {
            if (isEndTurn()) {
                return "END TURN";
            }
            if (card != null) {
                return card.name + (target == null ? "" : " -> " + target.name);
            }
            String name = potionName == null || potionName.isEmpty()
                    ? "POTION#" + potionSlot : potionName;
            return target == null ? name : name + " -> " + target.name;
        }
    }

    /** Applies one action to whatever world is installed. */
    public static Drain apply(Play play) {
        if (play.isEndTurn()) {
            return endTurn();
        }
        if (play.card != null) {
            return play(play.card, play.target, play.energyOnUse);
        }
        return usePotion(play.potionSlot);
    }

    public static Drain usePotion(int slot) {
        return usePotion(slot, -1);
    }

    /** @param targetIndex monster-group index for a targeted potion, or -1 for the first living one */
    public static Drain usePotion(int slot, int targetIndex) {
        Drain d = new Drain();
        try {
            AbstractPlayer p = AbstractDungeon.player;
            com.megacrit.cardcrawl.potions.AbstractPotion potion = p.potions.get(slot);
            // Never drink something the real pop-up would refuse (PotionBook). The search does not
            // offer those branches, so reaching here means the plan is stale or a probe asked for
            // it; refusing keeps a Fairy in a Bottle or a boss-room Smoke Bomb from being burned.
            if (!sts1solver.search.PotionBook.usable(potion)) {
                Log.warn("usePotion: the game refuses this potion ("
                        + (potion == null ? "none" : potion.name + "[" + potion.ID + "]") + ")");
                d.refused = true;
                return d;
            }
            List<AbstractMonster> alive = aliveMonsters();
            AbstractMonster target = null;
            if (!alive.isEmpty()) {
                if (targetIndex >= 0 && targetIndex < AbstractDungeon.getMonsters().monsters.size()
                        && AbstractDungeon.getMonsters().monsters.get(targetIndex) != null) {
                    target = AbstractDungeon.getMonsters().monsters.get(targetIndex);
                } else {
                    target = alive.get(0);
                }
            }
            // Small frame delta for every potion: the "Potion of <type>" family opens the same
            // discovery screen a Discovery card does, and that screen-opening action is frozen
            // while the screen is up -- a fast 20-unit tick would complete it before the answer
            // and the picked card would never be added. Potions are used a handful of times per
            // fight, so the extra frames cost nothing measurable.
            // The small delta has to cover the DRAIN too, exactly like a played card does: the
            // action that opens the pick screen only stays queued while its own duration is above
            // zero, and a 20-unit tick on the very update that opened the screen marks it done. The
            // screen then stays up with nothing left to consume the answer, so Liquid Memories moved
            // no card at all (m16202-5 / B56: measured "opened=true consumed=false discard=6->6").
            float savedDelta = simDelta;
            simDelta = SELECTION_DELTA;
            try {
                // Same three steps the pop-up takes (effect, relic pass, consumption), so the
                // clone's bar matches what the player's bar will look like after the real drink
                // (m17821: the live path used to apply the effect and keep the bottle).
                sts1solver.drive.PotionDrink.drink(slot, potion, target, false);
                // B102: a drained queue is the state; a queue that ran out of time is not.
                Drain drained = completeIfTruncated(drain());
                if (drained.awaitingSelection && orphanSelection()) {
                    orphanSelections++;
                    Log.warn("orphan selection after drinking " + potion.name + " (" + potion.ID
                            + "): screen=" + AbstractDungeon.screen
                            + " currentAction=" + describeCurrentAction()
                            + " queued=" + AbstractDungeon.actionManager.actions.size()
                            + " -- the action died before it could take the answer");
                }
                return drained;
            } finally {
                simDelta = savedDelta;
            }
        } catch (Throwable t) {
            d.timedOut = true;
            return d;
        }
    }

    public static int energy() {
        return EnergyPanel.totalCount;
    }

    public static void setEnergy(int n) {
        EnergyPanel.setEnergy(n);
    }

    @SuppressWarnings("unused")
    private static HandCardSelectScreen handScreen() {
        return AbstractDungeon.handCardSelectScreen;
    }
}