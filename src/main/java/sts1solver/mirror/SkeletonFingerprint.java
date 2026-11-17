package sts1solver.mirror;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.esotericsoftware.spine.AnimationState;
import com.esotericsoftware.spine.Bone;
import com.esotericsoftware.spine.Skeleton;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;

/**
 * B59: the half of a creature's look that {@link RenderState} never covered.
 *
 * <p>{@link RenderState} tracks colours, scales and card tint. It does not track the spine state --
 * and the spine package is deliberately <em>shared</em> between the sandbox and the live game
 * ({@code ClonePolicy} returns "share" for {@code com.esotericsoftware.spine.}). If a simulated
 * update ever advances the live {@link AnimationState} or moves the live {@link Skeleton}, the
 * picture on screen changes and nothing notices: the combat digest records numbers, and the render
 * audit records colours.
 *
 * <p>So: fingerprint every creature before a search and compare afterwards. Same shape as the render
 * audit -- name the difference and log it, then let {@link RenderState} repair what it can.
 */
public final class SkeletonFingerprint {

    private static Field skeletonField;
    private static boolean skeletonFieldTried;

    private SkeletonFingerprint() {
    }

    /** One line per creature: role, class, animation, its time and speed, skeleton placement. */
    public static String capture() {
        StringBuilder b = new StringBuilder();
        try {
            AbstractPlayer p = AbstractDungeon.player;
            if (p != null) {
                b.append(line("player", p)).append('\n');
            }
            if (AbstractDungeon.getMonsters() != null
                    && AbstractDungeon.getMonsters().monsters != null) {
                List<AbstractMonster> ms = AbstractDungeon.getMonsters().monsters;
                for (int i = 0; i < ms.size(); i++) {
                    AbstractMonster m = ms.get(i);
                    if (m == null) {
                        continue;
                    }
                    b.append(line("monster" + i, m)).append('\n');
                }
            }
        } catch (Throwable t) {
            b.append("unreadable: ").append(t).append('\n');
        }
        return b.toString();
    }

    private static String line(String role, AbstractCreature c) {
        // The clock comes first so a search-level diff can be stripped of it (see clockOnly): a
        // live frame advancing the animation is not a sandbox write.
        return role + " " + c.getClass().getSimpleName() + " t=" + trackTime(c) + " "
                + spineSignature(c);
    }

    /** The animation clock of track 0, or "-". */
    private static String trackTime(AbstractCreature c) {
        try {
            AnimationState state = c.state;
            AnimationState.TrackEntry e = state == null ? null : state.getCurrent(0);
            return e == null ? "-" : String.valueOf(e.getTime());
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * B192 round 5: the spine state WITHOUT the animation clock.
     *
     * <p>{@link LiveGuard} compares this across one install window (same tick, no rendered frame):
     * the clock cannot legitimately move there, but it is also the one field a live frame moves, so
     * leaving it out keeps the window measurement free of that false positive. Animation name, time
     * scale, placement, flip, bone pose and tint are all in, because a simulated
     * {@code setToSetupPose} or {@code state.apply} moves exactly those.
     */
    public static String spineSignature(AbstractCreature c) {
        StringBuilder b = new StringBuilder();
        try {
            AnimationState state = c.state;
            if (state != null) {
                AnimationState.TrackEntry e = state.getCurrent(0);
                b.append("anim=").append(e == null || e.getAnimation() == null
                        ? "-" : e.getAnimation().getName());
                b.append(" ts=").append(state.getTimeScale());
            } else {
                b.append("anim=- ts=-");
            }
        } catch (Throwable t) {
            b.append(" anim=?");
        }
        try {
            Skeleton s = skeletonOf(c);
            if (s == null) {
                b.append(" skel=- pose=-");
            } else {
                b.append(" skel=").append(s.getX()).append(',').append(s.getY())
                        .append(" flip=").append(s.getFlipX()).append(',').append(s.getFlipY())
                        .append(" pose=").append(poseHash(s));
            }
        } catch (Throwable t) {
            b.append(" skel=? pose=?");
        }
        try {
            Color tint = c.tint == null ? null : c.tint.color;
            b.append(" tint=").append(tint == null ? "-"
                    : tint.r + "," + tint.g + "," + tint.b + "," + tint.a);
        } catch (Throwable t) {
            b.append(" tint=?");
        }
        return b.toString();
    }

    /**
     * Bone transforms in one hex number: the difference between "idle" and "setup pose" when the
     * animation name and the skeleton's own placement happen to be identical.
     */
    public static String poseHash(Skeleton s) {
        long h = 1469598103934665603L;
        com.badlogic.gdx.utils.Array<Bone> bones = s.getBones();
        if (bones != null) {
            for (int i = 0; i < bones.size; i++) {
                Bone b = bones.get(i);
                if (b == null) {
                    continue;
                }
                h = mix(h, Float.floatToIntBits(b.getX()));
                h = mix(h, Float.floatToIntBits(b.getY()));
                h = mix(h, Float.floatToIntBits(b.getRotation()));
                h = mix(h, Float.floatToIntBits(b.getScaleX()));
                h = mix(h, Float.floatToIntBits(b.getScaleY()));
            }
        }
        return String.format(java.util.Locale.ROOT, "%08x",
                Integer.valueOf((int) (h ^ (h >>> 32))));
    }

    private static long mix(long h, int v) {
        h ^= v;
        h *= 1099511628211L;
        return h;
    }

    private static final java.util.regex.Pattern CLOCK =
            java.util.regex.Pattern.compile(" t=[-+0-9.eE]+");

    /** True when two lines differ only in the animation clock (a live frame, not a sandbox write). */
    public static boolean clockOnly(String diff) {
        int i = diff.indexOf(" -> ");
        if (i < 0) {
            return false;
        }
        String a = CLOCK.matcher(diff.substring(0, i)).replaceAll(" t=#");
        String b = CLOCK.matcher(diff.substring(i + 4)).replaceAll(" t=#");
        return a.equals(b);
    }

    private static Skeleton skeletonOf(AbstractCreature c) {
        if (!skeletonFieldTried) {
            skeletonFieldTried = true;
            try {
                skeletonField = AbstractCreature.class.getDeclaredField("skeleton");
                skeletonField.setAccessible(true);
            } catch (Throwable t) {
                skeletonField = null;
            }
        }
        if (skeletonField == null) {
            return null;
        }
        try {
            Object v = skeletonField.get(c);
            return v instanceof Skeleton ? (Skeleton) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Human-readable differences between two {@link #capture()} strings. */
    public static List<String> diff(String before, String after) {
        List<String> out = new ArrayList<String>();
        String[] a = before.split("\n");
        String[] b = after.split("\n");
        if (a.length != b.length) {
            out.add("creature count " + a.length + " -> " + b.length);
            return out;
        }
        for (int i = 0; i < a.length; i++) {
            if (!a[i].equals(b[i])) {
                out.add(a[i] + " -> " + b[i]);
            }
        }
        return out;
    }
}
