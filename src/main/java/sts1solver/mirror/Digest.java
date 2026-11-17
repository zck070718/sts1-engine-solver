package sts1solver.mirror;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;

import java.util.List;

/**
 * A readable fingerprint of the current combat state.
 *
 * <p>Two uses, both load-bearing:
 *
 * <ul>
 *   <li><b>Transposition dedupe</b> in the search: two branches that reached the same fight are one
 *       node. The digest is deliberately readable rather than a hash, because when a search result
 *       looks wrong the first question is "are these two states actually the same".</li>
 *   <li><b>Proof that the live game was not touched</b>: the harness takes the live digest before a
 *       search and after it, and compares. A solver that mirrors state but leaks one write shows up
 *       as a changed digest, which is a functional test rather than a code review.</li>
 * </ul>
 */
public final class Digest {

    private Digest() {
    }

    public static String ofCurrent() {
        // B192 round 2: own the live mod statics BEFORE the digest is taken. The quick-search
        // pre-check compares this digest against one taken after the capture; when the capture
        // registers a new dynamic root (a pet field that was null a moment ago, say) the sc= line
        // appeared only on the second read and the search aborted with "LIVE STATE CHANGED" --
        // a false alarm caused by our own bookkeeping. Syncing here makes both reads see the same
        // root set. sync() is a no-op inside a window, so this costs one reflective read pass.
        StaticEntities.sync();
        StringBuilder sb = new StringBuilder(256);
        try {
            GameActionManager gam = AbstractDungeon.actionManager;
            sb.append("t=").append(GameActionManager.turn);
            if (gam != null) {
                sb.append(" ph=").append(gam.phase)
                        .append(" q=").append(gam.actions.size())
                        .append("/").append(gam.cardQueue.size())
                        .append("/").append(gam.monsterQueue.size())
                        .append(" use=").append(gam.usingCard ? 1 : 0)
                        .append(" end=").append(gam.turnHasEnded ? 1 : 0);
            }
            AbstractPlayer p = AbstractDungeon.player;
            if (p != null) {
                sb.append(" | P hp=").append(p.currentHealth).append("/").append(p.maxHealth)
                        .append(" b=").append(p.currentBlock)
                        .append(" e=").append(EnergyPanel.totalCount)
                        .append(" st=").append(p.stance == null ? "-" : p.stance.name)
                        .append(" pw=").append(powers(p.powers))
                        .append(" rl=").append(relics(p.relics))
                        .append(" or=").append(orbs(p.orbs));
                sb.append(" H=").append(pile(p.hand));
                sb.append(" D=").append(pile(p.drawPile));
                sb.append(" X=").append(pile(p.discardPile));
                sb.append(" E=").append(pile(p.exhaustPile, false));
            }
            if (AbstractDungeon.getMonsters() != null
                    && AbstractDungeon.getMonsters().monsters != null) {
                List<AbstractMonster> ms = AbstractDungeon.getMonsters().monsters;
                for (int i = 0; i < ms.size(); i++) {
                    AbstractMonster m = ms.get(i);
                    sb.append(" | M").append(i).append('=');
                    if (m == null) {
                        sb.append("null");
                        continue;
                    }
                    sb.append(m.id).append(':').append(m.currentHealth).append('/')
                            .append(m.maxHealth).append(" b=").append(m.currentBlock)
                            .append(" in=").append(m.intent)
                            .append(" mv=").append(intentHits(m))
                            .append(" dm=").append(safeIntentDmg(m))
                            .append(" pw=").append(powers(m.powers))
                            .append(" d=").append(m.isDying ? 1 : 0)
                            .append(m.isDeadOrEscaped() ? 1 : 0);
                }
            }
            // B192: a battle entity owned through a STATIC (a mod's pet, a static power) is part of
            // the position even though it hangs off no pile or monster list. One short segment per
            // such root: "sc=Osty#1a2b3c4d" with the pet's HP/max/block/death flags folded in. Empty
            // when no static root is owned, so a vanilla fight's digest is unchanged.
            sb.append(StaticEntities.fingerprintLine());
        } catch (Throwable t) {
            sb.append(" | unreadable:").append(t.getClass().getSimpleName());
        }
        return sb.toString();
    }

    /**
     * The prediction-stable part of a digest: what a plan step must reproduce.
     *
     * <p>Two things in the raw digest are transient and must never decide whether a plan was right:
     *
     * <ul>
     *   <li>the action-manager phase/queues (`ph=`, `q=`, `use=`, `end=`) -- the same state reads
     *       EXECUTING_ACTIONS in one frame and WAITING_ON_USER in the next;</li>
     *   <li>everything about a monster that is already dying (`d=11`, or 0 HP): its POWERS, its
     *       intent, its hit count and its damage. The live game clears the powers when the death
     *       animation ends and the monster never acts again, so this is display-only; comparing it
     *       used to trigger a replan on a kill (B19) and, when a mod power also moved the shown
     *       damage, another one mid-turn (B41).</li>
     * </ul>
     */
    public static String stable(String digest) {
        if (digest == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(digest.length());
        for (int i = 0; i < digest.length(); i++) {
            char c = digest.charAt(i);
            if (c != '|') {
                out.append(c);
            } else {
                out.append('|');
            }
        }
        String[] parts = out.toString().split("\\|");
        StringBuilder sb = new StringBuilder(digest.length());
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.startsWith("ph=") || part.startsWith("q=") || part.startsWith("use=")
                    || part.startsWith("end=")) {
                continue;
            }
            if (part.startsWith("P ")) {
                sb.append('|').append(part);
                continue;
            }
            if (part.startsWith("M")) {
                sb.append('|').append(monsterStable(part));
                continue;
            }
            sb.append('|').append(part);
        }
        // B167 (user m18895): the transient fields live INSIDE the first segment
        // ("t=3 ph=EXECUTING_ACTIONS q=3/0/0 use=0 end=0"), so the skip loop above never saw them
        // as parts of their own and only "ph=" was ever stripped (by a plain replace). A frame that
        // had three actions queued therefore produced a different "stable" digest than the same
        // position one frame later -- which made the plan cache miss on a reload and made the
        // "only transient fields moved" check fire on the wrong field. Strip the four tokens
        // wherever they appear in a segment.
        String s = sb.toString();
        s = s.replaceAll(" ph=\\S+", "").replaceAll(" q=\\S+", "")
                .replaceAll(" use=\\S+", "").replaceAll(" end=\\S+", "");
        return s;
    }

    /**
     * The comparable form of one monster segment
     * (`M0=AcidSlime_S:0/9 b=0 in=ATTACK mv=1 dm=3 pw=CaiNongMod:NongBaiPower-1 d=11`).
     *
     * <p>A monster that is already dying (or at 0 HP) never acts again, so everything describing
     * "what it would do" is display noise for a prediction check: the live game clears its powers
     * when the 1.5 s death animation finishes
     * (`AbstractMonster.updateDeathAnimation()` -> `this.powers.clear()`,
     * build/gm2/.../monsters/AbstractMonster.java:853-860), while a sandbox snapshot taken right
     * after the kill still carries them. CaiNong's NongBaiPower then reports its intent damage as 3
     * instead of 4 (atDamageFinalGive x0.75), and that difference used to be read as "the board
     * changed", which cost a full replan in the middle of a turn (B41). Powers and intent of a
     * LIVE monster are always compared.
     */
    public static String monsterStable(String part) {
        if (part == null) {
            return "";
        }
        if (!part.startsWith("M")) {
            return part;
        }
        boolean dying = part.contains(" d=11") || part.contains(":0/");
        if (!dying) {
            return part;
        }
        // B75 (m21295): a power name may contain spaces ("Curl Up10", "Curl Up9"), so splitting the
        // whole segment on ' ' left the tail of "pw=Curl Up10" behind as a bare "Up10" token and the
        // two digests never matched. Drop the whole powers span (up to the next marker) first.
        String seg = part;
        int pwAt = seg.indexOf(" pw=");
        if (pwAt >= 0) {
            int dAt = seg.indexOf(" d=", pwAt);
            seg = dAt >= 0 ? seg.substring(0, pwAt) + seg.substring(dAt) : seg.substring(0, pwAt);
        }
        StringBuilder sb = new StringBuilder(seg.length());
        for (String token : seg.split(" ")) {
            if (token.isEmpty() || token.startsWith("in=") || token.startsWith("mv=")
                    || token.startsWith("dm=") || token.startsWith("pw=")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(token);
        }
        return sb.toString();
    }

    /** A cheap 64-bit progress hash for stall detection; allocation-free on purpose. */
    public static long progressHash() {
        long h = 1469598103934665603L;
        try {
            GameActionManager gam = AbstractDungeon.actionManager;
            if (gam != null) {
                h = mix(h, gam.actions.size());
                h = mix(h, gam.cardQueue.size());
                h = mix(h, gam.monsterQueue.size());
                h = mix(h, gam.usingCard ? 1 : 0);
                h = mix(h, gam.turnHasEnded ? 1 : 0);
                h = mix(h, GameActionManager.turn);
                h = mix(h, gam.phase == null ? 0 : gam.phase.ordinal());
            }
            AbstractPlayer p = AbstractDungeon.player;
            if (p != null) {
                h = mix(h, p.currentHealth);
                h = mix(h, p.currentBlock);
                h = mix(h, p.hand == null ? 0 : p.hand.size());
            }
            h = mix(h, EnergyPanel.totalCount);
            if (AbstractDungeon.getMonsters() != null
                    && AbstractDungeon.getMonsters().monsters != null) {
                List<AbstractMonster> ms = AbstractDungeon.getMonsters().monsters;
                for (int i = 0; i < ms.size(); i++) {
                    AbstractMonster m = ms.get(i);
                    if (m == null) {
                        continue;
                    }
                    h = mix(h, m.currentHealth);
                    h = mix(h, m.currentBlock);
                }
            }
        } catch (Throwable t) {
            h ^= System.nanoTime();
        }
        return h;
    }

    private static long mix(long h, int v) {
        h ^= (v & 0xffffffffL);
        h *= 1099511628211L;
        return h;
    }

    private static int safeIntentDmg(AbstractMonster m) {
        try {
            return m.getIntentDmg();
        } catch (Throwable t) {
            return -999;
        }
    }

    private static int intentHits(AbstractMonster m) {
        try {
            Object v = sts1solver.reflect.Reflect.get(
                    sts1solver.reflect.Reflect.find(AbstractMonster.class, "intentMultiAmt"), m);
            int n = v instanceof Integer ? ((Integer) v).intValue() : 0;
            return n > 0 && n <= 15 ? n : 1;
        } catch (Throwable t) {
            return 1;
        }
    }

    private static String pile(CardGroup g) {
        return pile(g, true);
    }

    /**
     * B76 (m21391): a card in the exhaust pile is reset one second later in the live game
     * ({@code ExhaustCardEffect.update()} -> {@code resetAttributes()}), so whatever per-turn cost it
     * still carries is a matter of how long that frame took, never a fact a plan must reproduce.
     * Pass {@code annotateCost = false} for the exhaust pile, the other piles keep it.
     */
    private static String pile(CardGroup g, boolean annotateCost) {
        if (g == null || g.group == null) {
            return "-";
        }
        StringBuilder sb = new StringBuilder(g.group.size() * 9);
        for (int i = 0; i < g.group.size(); i++) {
            AbstractCard c = g.group.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append(c == null ? "?" : c.cardID);
            if (c != null && c.upgraded) {
                sb.append('+');
            }
            if (annotateCost && c != null && c.costForTurn != c.cost) {
                sb.append('(').append(c.costForTurn).append(')');
            }
            // Per-copy state the card itself carries: two copies with the same cardID and the same
            // upgrade are NOT interchangeable once one of them has counted something (base game
            // Blood for Blood / Rampage, and most mod cards keep their counter in `misc`). The rules
            // live in CardPrint so the digest and the identity matcher can never disagree about what
            // "the same card" means (2026-11-02, user m02302).
            if (c != null) {
                sb.append(CardPrint.digestExtra(c));
            }
        }
        return sb.toString();
    }

    private static String powers(List<AbstractPower> ps) {
        if (ps == null || ps.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ps.size(); i++) {
            AbstractPower p = ps.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append(p == null ? "?" : p.ID + p.amount);
        }
        return sb.toString();
    }

    private static String relics(List<AbstractRelic> rs) {
        if (rs == null || rs.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rs.size(); i++) {
            AbstractRelic r = rs.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append(r == null ? "?" : r.relicId);
        }
        return sb.toString();
    }

    private static String orbs(List<com.megacrit.cardcrawl.orbs.AbstractOrb> os) {
        if (os == null || os.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < os.size(); i++) {
            com.megacrit.cardcrawl.orbs.AbstractOrb o = os.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append(o == null ? "?" : o.ID);
        }
        return sb.toString();
    }
}
