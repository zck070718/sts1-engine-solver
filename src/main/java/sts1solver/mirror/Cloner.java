package sts1solver.mirror;

import sts1solver.reflect.Instantiator;
import sts1solver.reflect.Reflect;
import sts1solver.util.Log;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deep-copies an object graph without running any constructor.
 *
 * <p>This is the mechanism the whole project rests on: instead of re-implementing Slay the Spire's
 * cards and monsters (which is how a solver drifts from the game), it copies the real objects and
 * lets the real methods run on the copies.
 *
 * <p>Identity is preserved with a single {@link IdentityHashMap} per copy, so a card that appears in
 * both the hand and an action's captured state stays ONE object in the copy, exactly as in the live
 * graph. Cycles therefore terminate, and cross-root aliasing survives.
 *
 * <p>Arrays are copied element-wise; containers ({@code ArrayList}, {@code HashMap}, libGDX
 * {@code Array}) need no special case because their internal fields are copied like any other
 * object's. Sharing decisions live in {@link ClonePolicy}.
 */
public final class Cloner {

    /**
     * Diagnostic switch (P1-2d-2): true keeps the reflective {@code Array.get/set} array copy. The
     * cost-fork probe flips this to price the two array strategies in one JVM; nothing else sets it.
     */
    public static volatile boolean arrayLegacy = Boolean.getBoolean("sts1solver.clone.legacy");

    /**
     * Every AbstractGameEffect this cloner ever allocated.
     *
     * <p>It should always be empty: effects are SHARED by ClonePolicy, because a
     * constructor-less effect has default fields and rendering one crashes the game
     * (MoveNameEffect with a null message throws inside libGDX). This tag is how VfxGuard can
     * remove ONLY such objects -- an earlier guard inferred it from field values
     * (duration == 0) and deleted 30 REAL effects, because StrikeEffect and DeckPoofEffect
     * legitimately have no duration.
     */
    public static final java.util.Set<Object> EFFECT_CLONES =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());

    /** Deeper than any real card/power graph; a guard against a pathological mod, not a design limit. */
    static final int MAX_DEPTH = 24;

    private final IdentityHashMap<Object, Object> map = new IdentityHashMap<Object, Object>();
    private int objects;
    private int shared;
    private int failures;

    /** Field kinds, so the hot copy loop can use typed getters/setters and skip boxing entirely. */
    static final byte OBJ = 0;
    static final byte BOOL = 1;
    static final byte INT = 2;
    static final byte LONG = 3;
    static final byte FLOAT = 4;
    static final byte DOUBLE = 5;
    static final byte SHORT = 6;
    static final byte BYTE = 7;
    static final byte CHAR = 8;

    /**
     * The copy recipe for one class: its instance fields, whether each one is shared by reference,
     * and each one's primitive kind. Built once per class; a fork copies thousands of objects and
     * would otherwise redo the shareField lookup and the boxing getter/setter for every field.
     */
    static final class Plan {
        final Field[] fields;
        final boolean[] share;
        final byte[] kind;

        Plan(Field[] fields, boolean[] share, byte[] kind) {
            this.fields = fields;
            this.share = share;
            this.kind = kind;
        }
    }

    private static final ClassValue<Plan> PLANS = new ClassValue<Plan>() {
        @Override
        protected Plan computeValue(Class<?> c) {
            List<Field> list = Reflect.instanceFields(c);
            int n = list.size();
            Field[] fields = new Field[n];
            boolean[] share = new boolean[n];
            byte[] kind = new byte[n];
            for (int i = 0; i < n; i++) {
                Field f = list.get(i);
                fields[i] = f;
                share[i] = ClonePolicy.shareField(f);
                kind[i] = kindOf(f.getType());
            }
            return new Plan(fields, share, kind);
        }
    };

    private static byte kindOf(Class<?> t) {
        if (t == boolean.class) {
            return BOOL;
        }
        if (t == int.class) {
            return INT;
        }
        if (t == long.class) {
            return LONG;
        }
        if (t == float.class) {
            return FLOAT;
        }
        if (t == double.class) {
            return DOUBLE;
        }
        if (t == short.class) {
            return SHORT;
        }
        if (t == byte.class) {
            return BYTE;
        }
        if (t == char.class) {
            return CHAR;
        }
        return OBJ;
    }

    /**
     * Diagnostic switch (P1-2d-2): true restores the pre-optimisation copy loop, which read every
     * field through the boxed {@code Field.get}/{@code set}. Only the cost-fork probe flips it.
     */
    public static volatile boolean fieldLegacy = Boolean.getBoolean("sts1solver.clone.legacy");
    public int objects() {
        return objects;
    }

    public int shared() {
        return shared;
    }

    public int failures() {
        return failures;
    }

    public IdentityHashMap<Object, Object> identity() {
        return map;
    }

    @SuppressWarnings("unchecked")
    public <T> T copy(T root) {
        return (T) walk(root, 0);
    }

    private Object walk(Object o, int depth) {
        if (o == null) {
            return null;
        }
        Class<?> c = o.getClass();
        if (c.isPrimitive() || ClonePolicy.share(o, c)) {
            shared++;
            return o;
        }
        Object got = map.get(o);
        if (got != null) {
            return got;
        }
        // A plain java.util.Random is copied by POSITION IN ITS STREAM, not field by field, because
        // field-by-field silently shared the stream: the seed lives in an AtomicLong (Java 8:
        // Random.seed), ClonePolicy shares java.util.concurrent.* by reference, and the copy
        // therefore pointed at the LIVE AtomicLong. Every simulated draw then advanced the real
        // game's RNG -- a search on one node changed the numbers the player's next card would roll --
        // and two sibling forks shared one stream, so "the same action" scored differently depending
        // on which node was evaluated first. Serialization is the only position-preserving copy that
        // works on both the bundled Java 8 and a modern JVM (java.util is not open to reflection).
        if (c == java.util.Random.class) {
            Object snapshot = copyRandomState((java.util.Random) o);
            if (snapshot != null) {
                map.put(o, snapshot);
                objects++;
                return snapshot;
            }
        }
        if (depth > MAX_DEPTH) {
            failures++;
            return o;
        }
        if (c.isArray()) {
            return copyArray(o, c, depth);
        }
        if (ClonePolicy.denyRecurse(c)) {
            shared++;
            return o;
        }
        Object copy;
        try {
            copy = Instantiator.allocate(c);
        } catch (Throwable t) {
            failures++;
            if (failures < 20) {
                Log.warn("clone: cannot allocate " + c.getName() + " (" + t.getMessage() + "), sharing");
            }
            return o;
        }
        map.put(o, copy);
        objects++;
        if (copy instanceof com.megacrit.cardcrawl.vfx.AbstractGameEffect) {
            EFFECT_CLONES.add(copy);
            if (EFFECT_CLONES.size() < 10) {
                Log.warn("clone: an EFFECT was copied (" + c.getName()
                        + "); effects must be shared -- VfxGuard will drop it");
            }
        }
        if (fieldLegacy) {
            List<Field> fields = Reflect.instanceFields(c);
            for (int i = 0; i < fields.size(); i++) {
                Field f = fields.get(i);
                Object v;
                try {
                    v = f.get(o);
                } catch (Throwable t) {
                    continue;
                }
                if (ClonePolicy.shareField(f)) {
                    try {
                        f.set(copy, v);
                    } catch (Throwable t) {
                        failures++;
                    }
                    continue;
                }
                Object cv = walk(v, depth + 1);
                try {
                    f.set(copy, cv);
                } catch (Throwable t) {
                    failures++;
                }
            }
            // B192 round 5: the spine fields were just copied as SHARED references (ClonePolicy
            // keeps the whole com.esotericsoftware.spine package), so the clone would advance the
            // live game's animation clock and pose. Give the clone its own before returning.
            if (copy instanceof com.megacrit.cardcrawl.core.AbstractCreature) {
                SpineIsolation.isolate((com.megacrit.cardcrawl.core.AbstractCreature) copy);
            }
            return copy;
        }
        Plan plan = PLANS.get(c);
        Field[] fs = plan.fields;
        for (int i = 0; i < fs.length; i++) {
            Field f = fs[i];
            try {
                // Primitives are copied with typed getters/setters. Reading them through Field.get
                // allocates a box for every int/boolean/float on every fork -- thousands of short-
                // lived objects per node, and the value is written straight back anyway.
                switch (plan.kind[i]) {
                    case OBJ: {
                        Object v = f.get(o);
                        if (plan.share[i]) {
                            f.set(copy, v);
                        } else {
                            // ALWAYS write, even when the copy walk returns the same reference: a
                            // skipped write leaves a null/zero behind, which produced a player with
                            // 0/0 HP and empty piles whose digest looked almost right.
                            f.set(copy, walk(v, depth + 1));
                        }
                        break;
                    }
                    case BOOL:
                        f.setBoolean(copy, f.getBoolean(o));
                        break;
                    case INT:
                        f.setInt(copy, f.getInt(o));
                        break;
                    case LONG:
                        f.setLong(copy, f.getLong(o));
                        break;
                    case FLOAT:
                        f.setFloat(copy, f.getFloat(o));
                        break;
                    case DOUBLE:
                        f.setDouble(copy, f.getDouble(o));
                        break;
                    case SHORT:
                        f.setShort(copy, f.getShort(o));
                        break;
                    case BYTE:
                        f.setByte(copy, f.getByte(o));
                        break;
                    default:
                        f.setChar(copy, f.getChar(o));
                        break;
                }
            } catch (Throwable t) {
                failures++;
                if (failures < 20) {
                    Log.warn("clone: cannot copy " + c.getName() + "." + f.getName() + ": " + t);
                }
            }
        }
        if (copy instanceof com.megacrit.cardcrawl.core.AbstractCreature) {
            SpineIsolation.isolate((com.megacrit.cardcrawl.core.AbstractCreature) copy);
        }
        return copy;
    }

    /** A {@link java.util.Random} sitting at the same position in its stream, or null. */
    private static Object copyRandomState(java.util.Random r) {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(256);
            java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes);
            out.writeObject(r);
            out.close();
            java.io.ObjectInputStream in = new java.io.ObjectInputStream(
                    new java.io.ByteArrayInputStream(bytes.toByteArray()));
            Object copy = in.readObject();
            in.close();
            return copy;
        } catch (Throwable t) {
            // A random that cannot be position-copied is still usable if it is shared; say so once
            // instead of pretending the streams are independent.
            Log.warnEvery("clone-random", 30000L,
                    "cannot copy a java.util.Random by stream position (" + t + "); "
                            + "sandbox draws will advance the live stream");
            return null;
        }
    }

    private Object copyArray(Object o, Class<?> c, int depth) {
        Class<?> comp = c.getComponentType();
        int len = Array.getLength(o);
        Object copy = Array.newInstance(comp, len);
        map.put(o, copy);
        objects++;
        if (comp.isPrimitive()) {
            System.arraycopy(o, 0, copy, 0, len);
            return copy;
        }
        // Reference arrays are read and written as Object[] instead of through Array.get/set. Every
        // Java reference-array type is an Object[] subtype, so the cast is always legal, and plain
        // indexing is a handful of instructions where Array.get/set are reflective calls with type
        // checks -- stack sampling put them at ~60% of the whole fork cost (P1-2d-2).
        if (arrayLegacy) {
            for (int i = 0; i < len; i++) {
                Array.set(copy, i, walk(Array.get(o, i), depth + 1));
            }
            return copy;
        }
        Object[] src = (Object[]) o;
        Object[] dst = (Object[]) copy;
        for (int i = 0; i < len; i++) {
            // Unconditional, for the same reason as the field loop: a shared element (Byte, String,
            // Texture) would otherwise leave a null hole in the copy. A monster's moveHistory is
            // ArrayList<Byte>, and "[null]" is not a subtle difference -- the monster AI NPEs on it.
            dst[i] = walk(src[i], depth + 1);
        }
        return copy;
    }

    /** True when {@code a} and {@code b} are the same object or both null. */
    static boolean same(Object a, Object b) {
        return a == b;
    }

    @SuppressWarnings("unused")
    private static boolean isContainer(Object o) {
        return o instanceof List || o instanceof Map;
    }
}