package sts1solver.search;

/**
 * One decision, expressed as INDICES rather than object references.
 *
 * <p>This is not a style choice. A search branches by cloning a world, and a clone's cards and
 * monsters are different objects from the parent's; an action that carried a reference would apply
 * to the wrong graph (or to the live fight). A hand index plus a monster-group index is exactly what
 * a player's click means, and it survives cloning, replay and execution.
 */
public final class Action {

    /** Position in hand, or -1 when this is not a card play. */
    public final int handIndex;
    /** Position in the monster group, or -1 when there is no target. */
    public final int targetIndex;
    /** Energy offered to X-cost cards; 0 for everything else. */
    public final int energyOnUse;
    /** Potion slot, or -1. */
    public final int potionSlot;
    /** What to draw, in one string: "打击 → 虱虫" / "结束回合" / "药水:火焰药水". */
    public final String label;
    /** Card name only ("" for end turn / potion). */
    public final String cardName;
    /** Target monster name, or "" when the action has no target. */
    public final String targetName;
    /** "card", "potion", "end", or "sel" (an answer to a selection screen, B162). */
    public final String kind;
    /** Cards chosen to answer a card-select screen (comma separated ids), or "". */
    public final String choice;
    /** Card id (language independent) when this is a card play, else "". */
    public final String cardId;
    /**
     * The card's uuid when this is a card play, else "".
     *
     * <p>Indices describe a position, and positions drift: the hand can be restructured between the
     * moment a plan is made and the moment it is drawn or executed, which used to highlight (and
     * play) the card NEXT TO the intended one. The uuid survives cloning (ClonePolicy shares UUID
     * values), so it is the stable identity to re-find the card by.
     */
    public final String cardUuid;
    /**
     * How to WRITE the {@link #choice} in the panel, in the player's language ("防御+"), or "" when
     * it equals {@link #choice}.
     *
     * <p>{@code choice} has to stay machine readable (card ids) so a replay can match the answer
     * back to a card, but the panel and the log should not show "（选：Bash）" to a player who sees
     * "痛击" on the card (m16202-4).
     */
    public final String choiceDisplay;
    /**
     * B79 (user m22205): the uuids of the cards in {@link #choice}, same order, or "".
     *
     * <p>A plan has to be answerable by COPYING it, not by deciding again. Card ids do not carry the
     * upgrade state and names do not carry duplicates ("打击" twice), so the only exact identity of
     * the card the search picked is its uuid -- it survives cloning (ClonePolicy shares UUID values)
     * and it is what the live hand is matched on when a step is played. Storing it makes a replay
     * pick the same physical card the search picked instead of re-deriving the choice.
     */
    public final String choiceUuids;
    /**
     * B98 (user m02347): what makes this card THAT card, not just a card with that id.
     *
     * <p>A uuid is fork-local: a card the sandbox created (a Shiv, a duplicated copy, anything
     * "add a random card") gets a new uuid in every clone and another one on the live engine, so it
     * can never be matched across them. The print carries the state the game actually reads
     * (upgrade, cost, counters in {@code misc}, a mod's own fields), so a plan can say "the copy that
     * has counted 5" instead of "the copy with the same id".
     */
    public final String cardPrint;
    /** The same, one print per entry of {@link #choice}, or "". */
    public final String choicePrints;

    /**
     * B122 (user m02740): the turn the engine was in BEFORE this action ran, or -1 when unknown.
     *
     * <p>The panel has to show a turn the player never played. 昏睡 (TrancePower) ends the turn at
     * the start of that turn, so the plan jumps from turn N to turn N+2 with no action in between.
     * A row per turn needs to know both ends of an action to draw the skipped turn instead of
     * silently merging it into the next row.
     */
    public int turnBefore = -1;

    /** The turn this action left the fight in, or -1 when unknown. */
    public int turnAfter = -1;

    /**
     * B129 (user m05700): whether the fight was left behind when this action finished.
     *
     * <p>Set inside the sandbox by the expansion that applies the action (smoke bomb -&gt; the
     * escape animation ends the room). The printed plan has to carry that fact itself: the panel
     * used to take it from a session-level flag that only tracked the session's best node, so a
     * plan rebuilt from another stored line (the deepest one, a split) printed the smoke bomb as
     * the last step while the result still claimed the fight was on.
     */
    public boolean escaped;

    /**
     * B165 (user m17933/m17993): whether the sandbox draw for this step went through the normalized
     * open-screen random (see {@code sts1solver.mirror.DiscoveryRandom}).
     *
     * <p>The panel marks such a step with '*' and prints one legend line. It does not mean "the plan
     * guessed": it means the game's own per-frame re-roll of an open card screen was folded into a
     * single draw, so a hand-played card sequence that would have spent those draws is on a different
     * random stream from the simulated one. That difference comes from the mod, not from the player,
     * and the panel says so instead of quietly showing a plan that the untouched game could not
     * reproduce.
     */
    public boolean rngNormalized;

    /**
     * B124 (user m03444): the cards the FIGHT played by itself while this action ran, in order.
     *
     * <p>唐 (TangPower) plays up to {@code amount} hand cards on its own every turn, and any card or
     * relic can queue a play the same way. Those plays are not decisions -- the search does not
     * branch on which card the fight picks -- but they change the hand: a plan that names 防御 is
     * wrong when the fight already played it. Recording them here, per simulated action, lets the
     * panel show them where they happen and lets the executor step over a plan step the fight has
     * already carried out. This is read from the sandbox (the delta of cardsPlayedThisTurn while
     * the action ran), never from the live fight, so it is part of the simulation itself.
     */
    public java.util.List<String> autoPlays = new java.util.ArrayList<String>();

    /**
     * B140 (user m09491): the KIND of screen each answer in {@link #choice} was given on, joined with
     * '|' in the same order ("HAND|GRID|GRID"), or "" when the step was expanded before this was
     * recorded.
     *
     * <p>The plan used to store only the answers. A replay then filled whichever picker happened to be
     * open, in order, without ever checking that the picker was the one the answer was written for.
     * When the fight opened a different chain (TangPower plays a different card, which opens a
     * different picker), the answer landed on the wrong screen, the mod's action read an empty
     * selection and quietly did nothing, and the sandbox carried on from a state the real fight never
     * had. Recording the kind is what lets the replay refuse instead of guessing.
     */
    public String screenKinds = "";

    /**
     * B203 (user m01393): what each answered screen OFFERED -- the state of every card on it, in the
     * order the screen listed them, '|' between screens and ',' inside one. Empty on a step the search
     * expanded before this was recorded, or on a screen with no cards.
     *
     * <p>An answer names a card; whether that card EXISTS to be taken is a separate fact, and only the
     * screen that was walked can say it. Without this, a plan that picked "the Strike this branch
     * upgraded" could not be told apart from a plan that picked "a Strike, which happened to be the
     * other copy on the live screen", and the live walk answered with a card the sandbox may not have
     * offered at all.
     */
    public String screenOffered = "";

    /**
     * B204 (user m01642): true while this step is being replayed against the LIVE game.
     *
     * <p>The plan's answers are written as "state#position". Re-expressing them for the screen
     * that is open can fail, and a refusal costs different things on the two sides: on the live
     * game it only stops the autopilot and keeps the plan (the player can recompute), while in a
     * sandbox branch it throws a perfectly good world away. Runtime only -- never recorded, never
     * compared, never hashed into a digest.
     */
    public transient boolean replayLive;

    /**
     * B162 (user m16041): the KIND of screen this step ANSWERS ("HAND", "GRID", "REWARD"), or "".
     *
     * <p>A selection screen used to be answered inside the step that opened it, so the search could
     * never ask "and what do you pick" when the screen was ALREADY open (a Gambling Chip at the start
     * of a turn, a relic that asks before the player's first action). Making the answer its own step
     * is what lets the plan start from a screen the player is looking at. The kind travels with the
     * step so a replay can refuse an answer written for a different screen instead of filling
     * whatever happens to be open.
     */
    public String screenKind = "";

    /**
     * B163 (user m16041): the cards this picker answer moves to the END of the hand BEFORE it
     * confirms, as card ids in the order they were moved ("," separated), or "".
     *
     * <p>The player does not only choose cards on a picker -- clicking a card and clicking it again
     * puts it back at the END of the hand, and the hand's order is engine state: 唐 (TangPower) plays
     * a uniformly random hand card, end-of-turn discards run in hand order, and kept cards return in
     * hand order. An answer that cannot say "move this one to the back" therefore cannot reach part
     * of the game's future. The net effect of select-then-deselect is exactly "remove it from the
     * hand and append it", so that is what this field records, not the click sequence itself.
     */
    public String moved = "";

    /** One uuid per entry of {@link #moved}, or "" (see {@link #choiceUuids}). */
    public String movedUuids = "";

    /** The same as {@link #choiceDisplay}, for {@link #moved}: panel text only. */
    public String movedDisplay = "";

    private Action(int handIndex, int targetIndex, int energyOnUse, int potionSlot, String label,
                   String cardName, String targetName, String kind) {
        this(handIndex, targetIndex, energyOnUse, potionSlot, label, cardName, targetName, kind,
                "", cardName == null ? "" : cardName, "", "", "", "", "");
    }

    private Action(int handIndex, int targetIndex, int energyOnUse, int potionSlot, String label,
                   String cardName, String targetName, String kind, String choice) {
        this(handIndex, targetIndex, energyOnUse, potionSlot, label, cardName, targetName, kind,
                choice, cardName == null ? "" : cardName, "", "", "", "", "");
    }

    private Action(int handIndex, int targetIndex, int energyOnUse, int potionSlot, String label,
                   String cardName, String targetName, String kind, String choice, String cardId) {
        this(handIndex, targetIndex, energyOnUse, potionSlot, label, cardName, targetName, kind,
                choice, cardId, "", "", "", "", "");
    }

    private Action(int handIndex, int targetIndex, int energyOnUse, int potionSlot, String label,
                   String cardName, String targetName, String kind, String choice, String cardId,
                   String cardUuid) {
        this(handIndex, targetIndex, energyOnUse, potionSlot, label, cardName, targetName, kind,
                choice, cardId, cardUuid, "", "", "", "");
    }

    private Action(int handIndex, int targetIndex, int energyOnUse, int potionSlot, String label,
                   String cardName, String targetName, String kind, String choice, String cardId,
                   String cardUuid, String choiceDisplay) {
        this(handIndex, targetIndex, energyOnUse, potionSlot, label, cardName, targetName, kind,
                choice, cardId, cardUuid, choiceDisplay, "", "", "");
    }

    private Action(int handIndex, int targetIndex, int energyOnUse, int potionSlot, String label,
                   String cardName, String targetName, String kind, String choice, String cardId,
                   String cardUuid, String choiceDisplay, String choiceUuids) {
        this(handIndex, targetIndex, energyOnUse, potionSlot, label, cardName, targetName, kind,
                choice, cardId, cardUuid, choiceDisplay, choiceUuids, "", "");
    }

    private Action(int handIndex, int targetIndex, int energyOnUse, int potionSlot, String label,
                   String cardName, String targetName, String kind, String choice, String cardId,
                   String cardUuid, String choiceDisplay, String choiceUuids, String cardPrint,
                   String choicePrints) {
        this.cardPrint = cardPrint == null ? "" : cardPrint;
        this.choicePrints = choicePrints == null ? "" : choicePrints;
        this.choiceUuids = choiceUuids == null ? "" : choiceUuids;
        this.choiceDisplay = choiceDisplay == null ? "" : choiceDisplay;
        this.cardUuid = cardUuid == null ? "" : cardUuid;
        this.choice = choice;
        this.cardId = cardId;
        this.handIndex = handIndex;
        this.targetIndex = targetIndex;
        this.energyOnUse = energyOnUse;
        this.potionSlot = potionSlot;
        this.label = label;
        this.cardName = cardName;
        this.targetName = targetName;
        this.kind = kind;
    }

    /**
     * Same decision, with the card-select answer it needs.
     *
     * <p>A card like "Prepared" is only one decision once you say WHAT to discard; the search branches
     * on that, so the answer has to travel with the action.
     */
    public Action withChoice(String chosen) {
        return withChoice(chosen, "");
    }

    /** The same, but the panel shows {@code display} instead of the raw ids. */
    public Action withChoice(String chosen, String display) {
        return withChoice(chosen, display, "");
    }

    /** The same, keeping the uuid of every card the answer names (B79). */
    public Action withChoice(String chosen, String display, String uuids) {
        return withChoice(chosen, display, uuids, "");
    }

    /** The same, with the fingerprints of the cards the answer names (B98). */
    public Action withChoice(String chosen, String display, String uuids, String prints) {
        return withChoice(chosen, display, uuids, prints, screenKinds);
    }

    /**
     * B190 (user m01149): the same, told which screens the step answered, so the label can name the
     * clicks.
     *
     * <p>{@code kinds} comes from the expansion ({@code Choice.screenKinds}) and has one entry per
     * screen. It has to be passed in rather than read off this action: the search attaches the kinds
     * AFTER the branch is built, so reading them here would always see the empty pre-answer value.
     */
    public Action withChoice(String chosen, String display, String uuids, String prints,
                             String kinds) {
        String raw = chosen == null ? "" : chosen;
        String text = display == null || display.isEmpty() ? raw : display;
        // A chain answer (one entry per screen, '|' separated: HeadChase asks twice) reads as
        // "A → B" in the panel and the log, never as a pipe.
        String shown = text.replace("|", " → ");
        String kindsNow = kinds == null ? "" : kinds;
        // B190 (user m01149): EVERY screen the step answered gets its own click script, including the
        // ones it answered with nothing (计划妥当's 保留 picker: "不选 → 确定"). The suffix used to be
        // added only for a non-empty answer, so a step that left the picker empty looked like a plain
        // 结束回合 in the panel, and autopilot had no answer to give it.
        int screens = Math.max(chainCount(raw), chainCount(kindsNow));
        String suffix;
        if (screens > 0) {
            // B191 (user m01447): the click script names the cards the panel shows, not the raw ids.
            suffix = "（" + clickScript(text, raw, screens) + "）";
        } else {
            suffix = raw.isEmpty() ? "" : "（选：" + shown + "）";
        }
        Action a = new Action(handIndex, targetIndex, energyOnUse, potionSlot, label + suffix,
                cardName, targetName, kind, raw, cardId, cardUuid, text,
                uuids == null ? "" : uuids, cardPrint, prints == null ? "" : prints);
        a.turnBefore = turnBefore;
        a.turnAfter = turnAfter;
        a.autoPlays = new java.util.ArrayList<String>(autoPlays);
        a.screenKinds = kindsNow;
        a.screenKind = screenKind;
        // B163: the click script travels with the answer through every copy of the decision.
        a.moved = moved;
        a.movedUuids = movedUuids;
        a.movedDisplay = movedDisplay;
        a.escaped = escaped;
        a.rngNormalized = rngNormalized;
        return a;
    }

    /** B190: how many '|' separated entries a chain has (an empty chain has none). */
    public static int chainCount(String joined) {
        if (joined == null || joined.isEmpty()) {
            return 0;
        }
        int n = 1;
        for (int i = 0; i < joined.length(); i++) {
            if (joined.charAt(i) == '|') {
                ++n;
            }
        }
        return n;
    }

    /**
     * B190 (user m01149): what the player has to DO, one entry per screen -- "选：打击 → 确定",
     * "不选 → 确定", or "1) …  2) …" when one action answers several screens in a row.
     */
    private static String clickScript(String shownText, String raw, int screens) {
        // B191 (user m01447): prefer what the panel shows (the localized card name) and fall back to
        // the id only when this screen has no name -- a plan that reads "生存者（选：尖啸 → 确定）"
        // must not print "PiercingWail" just because the answer was recorded by id.
        String[] names = shownText == null || shownText.isEmpty()
                ? new String[0] : shownText.split("\\|", -1);
        String[] ids = raw == null || raw.isEmpty() ? new String[0] : raw.split("\\|", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < screens; i++) {
            if (i > 0) {
                sb.append("  ");
            }
            String one = i < names.length ? names[i] : "";
            if (one.trim().isEmpty() && i < ids.length) {
                one = ids[i];
            }
            if (screens > 1) {
                sb.append(i + 1).append(") ");
            }
            sb.append(one.trim().isEmpty() ? "不选 → 确定" : "选：" + one + " → 确定");
        }
        return sb.toString();
    }

    public static Action card(int handIndex, int targetIndex, int energyOnUse, String label) {
        return card(handIndex, targetIndex, energyOnUse, label, "", "");
    }

    public static Action card(int handIndex, int targetIndex, int energyOnUse, String label,
                              String cardName, String targetName) {
        return card(handIndex, targetIndex, energyOnUse, label, cardName, targetName, "");
    }

    public static Action card(int handIndex, int targetIndex, int energyOnUse, String label,
                              String cardName, String targetName, String cardId) {
        return card(handIndex, targetIndex, energyOnUse, label, cardName, targetName, cardId, "");
    }

    public static Action card(int handIndex, int targetIndex, int energyOnUse, String label,
                              String cardName, String targetName, String cardId, String cardUuid) {
        String l = cardName + (targetName == null || targetName.isEmpty() ? "" : " → " + targetName);
        return new Action(handIndex, targetIndex, energyOnUse, -1, l, cardName, targetName, "card",
                "", cardId, cardUuid);
    }

    /** The same decision, tagged with the uuid of the card it means. */
    public Action withCardUuid(String uuid) {
        Action a = new Action(handIndex, targetIndex, energyOnUse, potionSlot, label, cardName,
                targetName, kind, choice, cardId, uuid, choiceDisplay, choiceUuids, cardPrint,
                choicePrints);
        a.turnBefore = turnBefore;
        a.turnAfter = turnAfter;
        a.autoPlays = new java.util.ArrayList<String>(autoPlays);
        a.screenKinds = screenKinds;
        a.screenKind = screenKind;
        // B163: the click script travels with the answer through every copy of the decision.
        a.moved = moved;
        a.movedUuids = movedUuids;
        a.movedDisplay = movedDisplay;
        a.escaped = escaped;
        a.rngNormalized = rngNormalized;
        return a;
    }

    /** The same decision, carrying what makes its card THAT card (B98). */
    public Action withCardPrint(String print) {
        Action a = new Action(handIndex, targetIndex, energyOnUse, potionSlot, label, cardName,
                targetName, kind, choice, cardId, cardUuid, choiceDisplay, choiceUuids, print,
                choicePrints);
        a.turnBefore = turnBefore;
        a.turnAfter = turnAfter;
        a.autoPlays = new java.util.ArrayList<String>(autoPlays);
        a.screenKinds = screenKinds;
        a.screenKind = screenKind;
        // B163: the click script travels with the answer through every copy of the decision.
        a.moved = moved;
        a.movedUuids = movedUuids;
        a.movedDisplay = movedDisplay;
        a.escaped = escaped;
        a.rngNormalized = rngNormalized;
        return a;
    }

    public static Action potion(int potionSlot, int targetIndex, String label) {
        return potion(potionSlot, targetIndex, label, "");
    }

    /**
     * A potion drink, carrying the potion's engine id in {@link #cardId}.
     *
     * <p>The label has to stay readable ("火焰药水 → 酸液史莱姆"), so the id cannot live there any
     * more; it travels in the same field card plays use for their card id, which is also what prices
     * a finished plan ({@code PotionBook.valueForId}).
     */
    public static Action potion(int potionSlot, int targetIndex, String label, String potionId) {
        String[] parts = label.split("::", 2);
        return new Action(-1, targetIndex, 0, potionSlot, parts[0],
                parts[0], parts.length > 1 ? parts[1] : "", "potion", "",
                potionId == null ? "" : potionId, "");
    }

    public static Action endTurn() {
        return new Action(-1, -1, 0, -1, "结束回合", "", "", "end");
    }

    public boolean isPotion() {
        return potionSlot >= 0;
    }

    /**
     * B162 (user m16041): an answer to a selection screen, as a step of its own.
     *
     * @param cardIds card ids of the answer ("" means "confirm what is already selected")
     * @param screenKind "HAND", "GRID" or "REWARD"
     */
    public static Action selection(String cardIds, String display, String uuids, String prints,
                                   String screenKind) {
        String ids = cardIds == null ? "" : cardIds;
        Action a = new Action(-1, -1, 0, -1, ids.isEmpty() ? "答选牌（不选）" : "答选牌",
                "", "", "sel").withChoice(ids, display, uuids, prints);
        a.screenKind = screenKind == null ? "" : screenKind;
        return a;
    }

    /**
     * B163 (user m16041): a picker answer that ALSO reorders the hand first.
     *
     * <p>Standing in front of a hand picker the player can click a card (it enters the selection),
     * click it again (it returns to the END of the hand), and only then confirm. This factory says
     * exactly that: move {@code moved} to the back, then take {@code cardIds}. No mod is named and
     * no screen is special-cased -- it is the picker's own click semantics.
     *
     * @param moved card ids moved to the end of the hand, in click order ("" = no reorder)
     * @param movedDisplay how to write them in the panel, or "" for the ids
     */
    public static Action selectionWithMoves(String cardIds, String display, String uuids,
                                            String prints, String moved, String movedUuids,
                                            String movedDisplay, String screenKind) {
        String ids = cardIds == null ? "" : cardIds;
        String text = display == null || display.isEmpty() ? ids : display;
        String shown = text.replace("|", " \u2192 ");
        String label = ids.isEmpty() ? "答选牌（不选）" : "答选牌（选：" + shown + "）";
        String mv = moved == null ? "" : moved;
        String mvShown = movedDisplay == null || movedDisplay.isEmpty() ? mv : movedDisplay;
        if (!mv.isEmpty()) {
            label += "（换序：" + mvShown.replace("|", " \u2192 ").replace(",", "、") + "）";
        }
        Action a = new Action(-1, -1, 0, -1, label, "", "", "sel", ids, "", "", text,
                uuids == null ? "" : uuids, "", prints == null ? "" : prints);
        a.screenKind = screenKind == null ? "" : screenKind;
        a.moved = mv;
        a.movedUuids = movedUuids == null ? "" : movedUuids;
        a.movedDisplay = mv.isEmpty() ? "" : mvShown;
        return a;
    }

    public boolean isSelection() {
        return "sel".equals(kind);
    }

    public boolean isEndTurn() {
        // B162: the kind decides, not the indices. A selection answer has no hand index either, and
        // reading it as an end turn pressed the button and skipped the picker.
        return "end".equals(kind);
    }

    public boolean isCard() {
        return handIndex >= 0;
    }

    @Override
    public String toString() {
        return label;
    }
}