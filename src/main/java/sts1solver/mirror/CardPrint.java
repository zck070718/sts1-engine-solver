package sts1solver.mirror;

import com.megacrit.cardcrawl.cards.AbstractCard;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * What makes one physical card that card, expressed as a string a sandbox and the live game can both
 * produce.
 *
 * <p>The problem this replaces: a plan names a card by uuid, but a uuid is fork-local. A card the
 * SANDBOX created (Blade Dance's Shiv, a Duplication potion copy, anything "add a random card") gets a
 * fresh uuid in every clone and another one on the real engine, so the uuid can never match. The old
 * fallback was "same card id + same upgrade state" and, when that matched two copies, "the cheapest
 * one" -- which silently plays a DIFFERENT card when two copies differ in state the id does not
 * carry. Base game: Blood for Blood keeps its cost reduction in {@code misc}, Rampage its grown
 * damage. Mod cards routinely keep counters in {@code misc} or in their own fields.
 *
 * <p>So identity here is everything the GAME would read off the card, and nothing that only exists to
 * draw it. Two cards with the same print are interchangeable (the game cannot tell them apart either,
 * apart from the uuid); two cards with different prints are not, and a plan that named one must never
 * be executed with the other.
 *
 * <p>Tier A (compared): cardID, upgrade, cost / costForTurn, freeToPlayOnce, misc, the base numbers
 * (damage, block, magic, heal, draw, discard), damageTypeForTurn, plus every scalar field the card's
 * own class adds (so a mod's counter is covered without naming the mod).
 *
 * <p>Tier B (NOT compared here, but written into the log when it disagrees with the library
 * definition): type / color / rarity / target / exhaust / ethereal / innate / retain ... they are
 * decided by the card id, so a mismatch means something changed the STRUCTURE of a card at runtime,
 * which is worth a line but not worth calling every hand look-up a different card.
 *
 * <p>Tier C (never compared): uuid, position/scale/angle/transparency, colours, textures, portraits,
 * glow/hover/selection flags, description text, price, {@code energyOnUse} (that is the INPUT of this
 * play, carried by the action, not a property of the card).
 */
public final class CardPrint {

    /** Names already rendered by {@link #of}: never repeat them through the reflection pass. */
    private static final Set<String> EXPLICIT = new HashSet<String>(java.util.Arrays.asList(
            "uuid", "cardID", "name", "originalName", "rawDescription", "description", "cantUseMessage",
            "upgraded", "timesUpgraded", "cost", "costForTurn", "misc", "freeToPlayOnce",
            "baseDamage", "baseBlock", "baseMagicNumber", "baseHeal", "baseDraw", "baseDiscard",
            "damageTypeForTurn", "energyOnUse", "ignoreEnergyOnUse", "price", "chargeCost",
            // Derived from the last applyPowers (they depend on WHICH monster was applied last), so
            // they are not a fact of the card: comparing them across two engines produced false
            // mismatches on multi-monster fights. The base numbers above are the real state.
            "damage", "block", "magicNumber", "heal", "draw", "discard",
            "isDamageModified", "isBlockModified", "isMagicNumberModified",
            // Per-turn bookkeeping the game rewrites while it draws: cost highlight flags are
            // reproduced by cost/costForTurn, "seen" flips as cards are shown, fadingOut by the
            // exhaust animation. None of it can be observed by playing the card.
            "isCostModified", "isCostModifiedForTurn", "isSeen", "isLocked", "isSelected",
            "isInAutoplay", "fadingOut", "isFlipped", "darken", "isUsed",
            "showEvokeValue", "showEvokeOrbCount",
            // Decided by the card id (a mismatch here means the card DEFINITION changed, which the
            // library comparison reports separately, not that this copy differs from that one).
            "damageType", "target", "assetUrl"));

    /**
     * Field names that exist to DRAW the card. Matched as substrings: the game's own naming is
     * consistent enough ({@code current_x}, {@code targetTransparency}, {@code hoverTimer},
     * {@code frameColor}, {@code portraitImg}) that a name filter is stable across mods too.
     */
    private static final String[] UI_NAMES = {
            "_x", "_y", "X", "Y", "Color", "Scale", "Timer", "timer", "alpha", "Alpha", "Angle",
            "angle", "transparency", "Transparency", "glow", "Glow", "hover", "Hover", "draw",
            "Draw", "xml", "img", "Img", "portrait", "Portrait", "texture", "Texture", "font",
            "Font", "flash", "Flash", "effect", "Effect", "list", "List", "hitbox", "hb", "raw",
            "render", "Render", "tip", "Tip", "shown", "Shown", "varData"};

    private static final Map<Class<?>, Field[]> EXTRA =
            Collections.synchronizedMap(new WeakHashMap<Class<?>, Field[]>());

    private CardPrint() {
    }

    /**
     * The full identity of one card: same string in the sandbox and on the live engine for the same
     * card state, different string the moment anything the game would act on differs.
     */
    public static String of(AbstractCard c) {
        if (c == null) {
            return "?";
        }
        StringBuilder sb = new StringBuilder(64);
        sb.append(c.cardID);
        if (c.upgraded) {
            sb.append('+');
        }
        sb.append("|c=").append(c.cost).append('/').append(c.costForTurn);
        sb.append("|m=").append(c.misc);
        if (c.freeToPlayOnce) {
            sb.append("|free");
        }
        sb.append("|d=").append(c.baseDamage).append("|b=").append(c.baseBlock)
                .append("|g=").append(c.baseMagicNumber);
        if (c.baseHeal != 0) {
            sb.append("|h=").append(c.baseHeal);
        }
        if (c.baseDraw != 0) {
            sb.append("|r=").append(c.baseDraw);
        }
        if (c.baseDiscard != 0) {
            sb.append("|x=").append(c.baseDiscard);
        }
        sb.append("|t=").append(c.damageTypeForTurn == null ? "" : c.damageTypeForTurn.name());
        appendExtras(sb, c);
        return sb.toString();
    }

    /**
     * B206 (user m02438): the part of a print that says WHICH CARD this is -- the id, the upgrade,
     * the cost it shows and its numbers. Everything else a print carries (type, rarity, colour, the
     * card's own text, fields a mod's card class adds) describes the card FACE, not this copy of it.
     *
     * <p>Two copies with the same face are the same card to the game: nothing in the engine reads
     * "which copy", so any of them answers a plan that named the card. Answering by face is what
     * lets a plan and a screen agree when their prints differ in a field that has no bearing on the
     * pick -- {@code of()} keeps being the strict identity the digests and library checks use.
     *
     * <p>Only the leading segment of a print is read, up to the first field a card's CLASS adds
     * ({@code |type=}), and the turn cost {@code |c=cost/costForTurn} is normalised to
     * {@code cost/*}: the plan was written before the draw re-rolled it, which is a fact about the
     * turn, not about the card.
     */
    public static String face(String print) {
        if (print == null) {
            return "";
        }
        int cut = print.indexOf("|type=");
        String head = cut < 0 ? print : print.substring(0, cut);
        int cost = head.indexOf("|c=");
        if (cost >= 0) {
            int slash = head.indexOf('/', cost);
            int end = head.indexOf('|', cost);
            if (slash > 0 && (end < 0 || slash < end)) {
                head = head.substring(0, slash + 1) + "*" + (end < 0 ? "" : head.substring(end));
            }
        }
        return head;
    }

    /** True when the game itself has no way to tell these two cards apart. */
    public static boolean same(AbstractCard a, AbstractCard b) {
        return of(a).equals(of(b));
    }

    /**
     * The small per-card state a digest should carry, appended after the cost annotation.
     *
     * <p>Only state that is a fact of the CARD, never a number derived from the current target
     * ({@code damage} / {@code block} are recalculated against whichever monster was applied last, so
     * comparing them across two engines produces false drift on multi-monster fights).
     */
    public static String digestExtra(AbstractCard c) {
        if (c == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(8);
        if (c.misc != 0) {
            sb.append('*').append(c.misc);
        }
        if (c.freeToPlayOnce) {
            sb.append('F');
        }
        return sb.toString();
    }

    /** Every scalar field the card's own class (or a mod's subclass) adds, as {@code |name=value}. */
    private static void appendExtras(StringBuilder sb, AbstractCard c) {
        Field[] fields = extraFields(c.getClass());
        for (int i = 0; i < fields.length; i++) {
            Field f = fields[i];
            Object v;
            try {
                v = f.get(c);
            } catch (Throwable t) {
                // A field we cannot read is a gap in the print, not a crash of the fight: name it
                // once and keep going (the digest check still catches the consequence).
                sts1solver.util.Log.warnEvery("cardprint-read-" + f.getName(), 60000L,
                        "cannot read " + f.getDeclaringClass().getName() + "#" + f.getName()
                                + " into a card print");
                continue;
            }
            if (v == null) {
                continue;
            }
            sb.append('|').append(f.getName()).append('=').append(v);
        }
    }

    private static Field[] extraFields(Class<?> type) {
        Field[] cached = EXTRA.get(type);
        if (cached != null) {
            return cached;
        }
        List<Field> out = new ArrayList<Field>();
        for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass()) {
            Field[] declared = k.getDeclaredFields();
            for (int i = 0; i < declared.length; i++) {
                Field f = declared[i];
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()
                        || Modifier.isTransient(f.getModifiers())) {
                    continue;
                }
                if (EXPLICIT.contains(f.getName()) || isUiName(f.getName())
                        || !isScalar(f.getType())) {
                    continue;
                }
                try {
                    if (!f.isAccessible()) {
                        f.setAccessible(true);
                    }
                } catch (Throwable t) {
                    continue;
                }
                out.add(f);
            }
            if (k == AbstractCard.class) {
                break;
            }
        }
        Field[] arr = out.toArray(new Field[0]);
        EXTRA.put(type, arr);
        return arr;
    }

    private static boolean isScalar(Class<?> t) {
        return t == int.class || t == long.class || t == boolean.class || t == float.class
                || t == double.class || t == short.class || t == byte.class
                || t == String.class || Enum.class.isAssignableFrom(t);
    }

    private static boolean isUiName(String name) {
        for (int i = 0; i < UI_NAMES.length; i++) {
            if (name.contains(UI_NAMES[i])) {
                return true;
            }
        }
        return false;
    }
}
