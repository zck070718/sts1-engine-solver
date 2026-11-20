package sts1solver.mirror;

import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import sts1solver.reflect.Reflect;
import sts1solver.util.Log;

/**
 * B213 (user m00005/m00344): the player's career record must not move while a search runs.
 *
 * <p>{@code com.megacrit.cardcrawl.screens.stats.CharStat} used to be shared, so a simulated death
 * ran {@code incrementDeath()} / {@code incrementPlayTime()} on the LIVE object. Those methods write
 * the fields first and the Prefs second, and {@link PlayerPrefs} only redirects the Prefs -- the
 * fields stayed dirty in the live game until the next real save flushed them into
 * {@code preferences\STSData<character>}. That is the reported "after a fight the stats screen shows
 * more runs and more play time".
 *
 * <p>ClonePolicy now copies the record (and shares only its read-only {@code runs} list), which is
 * the actual repair. This guard is the belt: install snapshots every primitive field, uninstall
 * compares them, puts the captured values back and names the fields that moved. A window should
 * never trip it; if a later change re-shares the record, this fails loudly instead of quietly
 * inflating the player's counters again. Cost: one snapshot and one comparison per window
 * (about 16 reflective reads, no per-fork work).
 */
public final class CharStatGuard {

    private CharStatGuard() {
    }

    /** Per-class recipe: every primitive instance field, i.e. the numbers a career record holds. */
    private static final Map<Class<?>, Field[]> FIELDS = new HashMap<Class<?>, Field[]>();

    private static Object stat;
    private static Object[] values;
    private static long clean;
    private static long dirty;
    private static String lastDirty = "";
    private static long lastLogAt;

    /** Snapshot the live record right before a window is installed. */
    public static synchronized void capture() {
        stat = null;
        values = null;
        try {
            AbstractPlayer p = AbstractDungeon.player;
            Object cs = p == null ? null : p.getCharStat();
            if (cs == null) {
                return;
            }
            Field[] fs = fieldsOf(cs);
            Object[] vs = new Object[fs.length];
            for (int i = 0; i < fs.length; i++) {
                vs[i] = Reflect.get(fs[i], cs);
            }
            stat = cs;
            values = vs;
        } catch (Throwable t) {
            Log.warnEvery("charstat-capture", 30000L,
                    "cannot snapshot the live career record (" + t + "); the window is unguarded");
        }
    }

    /**
     * Compare with the capture snapshot and put the captured numbers back. Runs first in uninstall,
     * next to {@link LiveGuard#verify()} and before any static is put back.
     */
    public static synchronized void verify() {
        Object cs = stat;
        Object[] before = values;
        stat = null;
        values = null;
        if (cs == null || before == null) {
            return;
        }
        int changed = 0;
        StringBuilder sb = new StringBuilder();
        try {
            Field[] fs = fieldsOf(cs);
            if (fs.length != before.length) {
                return;
            }
            for (int i = 0; i < fs.length; i++) {
                Object now;
                try {
                    now = Reflect.get(fs[i], cs);
                } catch (Throwable t) {
                    continue;
                }
                if (now == null || now.equals(before[i])) {
                    continue;
                }
                ++changed;
                if (changed <= 6) {
                    sb.append(fs[i].getName()).append(' ').append(before[i]).append("->").append(now)
                            .append("; ");
                }
                try {
                    Reflect.set(fs[i], cs, before[i]);
                } catch (Throwable t) {
                    // Named above; the copy in ClonePolicy is what keeps this from mattering.
                }
            }
        } catch (Throwable t) {
            Log.warnEvery("charstat-verify", 30000L,
                    "cannot compare the live career record (" + t + "); it was left as found");
            return;
        }
        long now = System.currentTimeMillis();
        if (changed == 0) {
            ++clean;
            if (now - lastLogAt > 300000L) {
                lastLogAt = now;
                Log.info("career record guard (B213): " + clean + " clean window(s), " + dirty
                        + " dirty; the live stats were untouched"
                        + (lastDirty.isEmpty() ? "" : "; last dirty: " + lastDirty));
            }
            return;
        }
        ++dirty;
        lastDirty = sb.toString();
        Log.warn("career record guard (B213): the window CHANGED " + changed
                + " live stat field(s): " + lastDirty + "-- put back, and ClonePolicy should be"
                + " copying CharStat (windows checked " + (clean + dirty) + ", dirty " + dirty + ")");
    }

    private static Field[] fieldsOf(Object o) {
        Class<?> c = o.getClass();
        Field[] cached = FIELDS.get(c);
        if (cached != null) {
            return cached;
        }
        List<Field> out = new ArrayList<Field>();
        List<Field> all = Reflect.instanceFields(c);
        for (int i = 0; i < all.size(); i++) {
            Field f = all.get(i);
            if (f.getType().isPrimitive()) {
                out.add(f);
            }
        }
        Field[] fs = out.toArray(new Field[out.size()]);
        FIELDS.put(c, fs);
        return fs;
    }

    public static synchronized String report() {
        return "career record guard (B213): " + clean + " clean / " + dirty + " dirty window(s)"
                + (lastDirty.isEmpty() ? "" : "; last dirty: " + lastDirty);
    }
}
