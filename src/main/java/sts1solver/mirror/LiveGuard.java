package sts1solver.mirror;

import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.monsters.MonsterGroup;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import sts1solver.util.Log;

/**
 * B192 round 3: does a sandbox window change the LIVE objects?
 *
 * <p>The mirror owns the statics, but putting the static slot back only proves the reference is
 * back -- it says nothing about the object it points at. A window that reaches the live creature
 * through any other path (a shared list element, a power, a field on a live-shared mod static the
 * mirror does not own) can still heal or damage it, and no static put-back can undo that.
 *
 * <p>This holds the live player, the live monsters and every dynamic clone root across one window
 * and compares their state afterwards: a change is a real write to the live game, named with old
 * and new values. It never repairs anything -- it is the measurement earlier rounds lacked.
 *
 * <p>Cost: a handful of reflective reads per capture (player + monsters + dynamic roots, usually
 * under 10 objects) and the same again per uninstall; nothing per node.
 */
public final class LiveGuard {

    private LiveGuard() {
    }

    private static final class Entry {
        final String label;
        final Object obj;
        final String sig;

        Entry(String label, Object obj, String sig) {
            this.label = label;
            this.obj = obj;
            this.sig = sig;
        }
    }

    private static List<Entry> entries = new ArrayList<Entry>();
    private static long clean;
    private static long dirty;
    private static String lastDirty = "";
    private static long lastLogAt;

    /** Snapshot the live objects right before a capture. Called with no window installed. */
    public static synchronized void capture() {
        List<Entry> next = new ArrayList<Entry>();
        Map<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();
        add(next, seen, "AbstractDungeon.player", AbstractDungeon.player);
        try {
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            MonsterGroup mg = room == null ? null : room.monsters;
            if (mg != null && mg.monsters != null) {
                for (int i = 0; i < mg.monsters.size(); i++) {
                    AbstractMonster m = mg.monsters.get(i);
                    add(next, seen, "monster " + (m == null ? String.valueOf(i) : m.id), m);
                }
            }
        } catch (Throwable ignored) {
            // Outside a combat room there is nothing to hold; the roots below still apply.
        }
        try {
            List<Statics.Spec> dyn = Statics.dynamicSpecs();
            for (int i = 0; i < dyn.size(); i++) {
                Statics.Spec s = dyn.get(i);
                if (s.kind != Statics.Kind.CLONE) {
                    continue;
                }
                Object v = sts1solver.reflect.Reflect.get(s.field, null);
                if (StaticEntities.isBattleEntity(v)) {
                    add(next, seen, "static " + s.label(), v);
                }
            }
        } catch (Throwable ignored) {
            // An unreadable dynamic table is reported by sync()/walk, not here.
        }
        // B192 round 4: a creature the walk did NOT own (a mod pet whose field was missed) is
        // exactly the case this guard exists for. Cover every mod static that currently holds a
        // creature, registered or not, so the next dirty window names the field instead of only
        // saying "the live pet moved".
        try {
            List<Field> cands = ModStatics.watchCandidates();
            for (int i = 0; i < cands.size(); i++) {
                Field f = cands.get(i);
                Object v = sts1solver.reflect.Reflect.get(f, null);
                if (v instanceof com.megacrit.cardcrawl.core.AbstractCreature) {
                    add(next, seen, "unowned static " + f.getDeclaringClass().getSimpleName() + '#'
                            + f.getName(), v);
                }
            }
        } catch (Throwable ignored) {
            // The candidate table is best-effort; the roots above still hold.
        }
        entries = next;
    }

    /** Compare with the capture snapshot. Runs first in uninstall, before any put-back. */
    public static synchronized void verify() {
        if (entries.isEmpty()) {
            return;
        }
        int changed = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            String now;
            try {
                now = sig(e.obj);
            } catch (Throwable t) {
                now = "unreadable " + t;
            }
            if (now.equals(e.sig)) {
                continue;
            }
            ++changed;
            if (changed <= 6) {
                sb.append(e.label).append(' ').append(e.sig).append(" -> ").append(now)
                        .append("; ");
            }
        }
        entries = new ArrayList<Entry>();
        long now = System.currentTimeMillis();
        if (changed > 0) {
            ++dirty;
            lastDirty = sb.toString();
            Log.warn("live guard (B192): the window CHANGED " + changed + " live object(s): "
                    + lastDirty + "-- a real write to the live game, not a restored static"
                    + " (windows checked " + (clean + dirty) + ", dirty " + dirty + ")");
            return;
        }
        ++clean;
        if (now - lastLogAt > 60000L) {
            lastLogAt = now;
            Log.info("live guard (B192): " + clean + " clean window(s), " + dirty + " dirty;"
                    + " live player/monsters/dynamic roots unchanged"
                    + (lastDirty.isEmpty() ? "" : "; last dirty: " + lastDirty));
        }
    }

    private static void add(List<Entry> out, Map<Object, Boolean> seen, String label, Object o) {
        if (o == null || seen.put(o, Boolean.TRUE) != null) {
            return;
        }
        try {
            out.add(new Entry(label, o, sig(o)));
        } catch (Throwable ignored) {
            // Unreadable object: not evidence either way, leave it out.
        }
    }

    /** Readable state for the objects a battle write would touch. */
    private static String sig(Object o) {
        if (o instanceof com.megacrit.cardcrawl.core.AbstractCreature) {
            com.megacrit.cardcrawl.core.AbstractCreature c =
                    (com.megacrit.cardcrawl.core.AbstractCreature) o;
            // B192 round 5: HP alone never proved a window clean for the animation. The spine
            // signature is clock-free, so within one install window it only moves when the sandbox
            // actually wrote the live pose/animation.
            return "hp=" + c.currentHealth + "/" + c.maxHealth + " b=" + c.currentBlock
                    + " dead=" + (c.isDead ? 1 : 0) + (c.isDying ? 1 : 0) + " "
                    + SkeletonFingerprint.spineSignature(c);
        }
        return StaticEntities.fingerprint(o);
    }

    public static synchronized String report() {
        return "live guard: " + clean + " clean / " + dirty + " dirty window(s)"
                + (lastDirty.isEmpty() ? "" : "; last dirty: " + lastDirty);
    }

    public static synchronized void resetForTest() {
        entries = new ArrayList<Entry>();
        clean = 0;
        dirty = 0;
        lastDirty = "";
        lastLogAt = 0;
    }
}
