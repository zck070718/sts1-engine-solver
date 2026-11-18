package sts1solver.mirror;

import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.core.AbstractCreature;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import sts1solver.reflect.Reflect;
import sts1solver.util.Log;

/**
 * B201 (user m00511): a sandbox window must hand back the textures it uploads.
 *
 * <p>What went wrong (solver.log 2026-11-19 00:06-00:07, SlimeBoss): the boss splits by calling
 * {@code new AcidSlime_L(...)} / {@code new SpikeSlime_L(...)}, whose constructor ends in
 * {@code AbstractCreature.loadAnimation(atlasUrl, jsonUrl, scale)}, which builds a brand new
 * {@code TextureAtlas}. That decodes the PNG and uploads a GL texture -- the sandbox shares the
 * game's GL context, so this is a real 8 MB-class VRAM allocation per atlas, not a heap object
 * that the collector can take back.
 *
 * <p>The sandbox never disposed those atlases. {@link DeathSettle} deliberately skips
 * {@code AbstractCreature.dispose()} for dead monsters because the real fight's monsters can be
 * shared objects; the effect of that rule, applied to monsters the sandbox CREATED, is that every
 * simulated split leaked one atlas per slime and nothing ever gave it back. The player's run was
 * unlimited in time ("已用 1012s（不限）") with 609,170 worlds visited, so the split count ran into
 * the thousands; the 8 GB card filled up, the driver hung (20 watchdog samples in
 * {@code nSwapBuffers}, "no frame for 4141 ms"), and from then on even PNG decoding failed
 * ({@code decoder init failed for stream}, {@code skeleton.atlas (Internal)} not found) because the
 * allocator behind it had nothing left. Nothing in the search had thrown OutOfMemoryError: the
 * crash was the GPU, not the heap and not the search.
 *
 * <p>The fix is a property of the window, not of a monster, card or relic: art loaded while a
 * window is installed was loaded BY that window (the live game's own creatures already exist and
 * never call {@code loadAnimation} during a window), so the window may free it when it uninstalls.
 * Live art is never registered here and is therefore never freed. Nothing is cached or shared: the
 * decode still costs exactly what it cost before, the difference is only that the VRAM goes back
 * at the end of the window instead of at the end of the run.
 *
 * <p>Cost: one {@code Texture.dispose()} per atlas the window created -- at most a couple per
 * window, on the render thread, at the same place the window already restores statics. Memory
 * drops from "one atlas per split per world" to "one atlas per split per live window".
 *
 * <p>Risk: if the engine ever keeps using a creature the sandbox created (it does not: sandbox
 * monsters are thrown away with their world), freeing its atlas would leave a disposed texture
 * behind. The registry is per-window and only ever holds atlases created inside that window, so a
 * stored world that is installed again later simply loads fresh art for anything it constructs
 * then.
 */
public final class SandboxArt {

    private SandboxArt() {
    }

    /** Atlases created while a window was installed, in creation order. */
    private static final IdentityHashMap<TextureAtlas, Boolean> OPEN =
            new IdentityHashMap<TextureAtlas, Boolean>();

    private static long loaded;
    private static long disposed;
    private static Field atlasField;

    private static long windows;
    private static int logged;

    /**
     * {@code AbstractCreature.loadAnimation} ends with the atlas already assigned, so a postfix is
     * enough: no engine logic runs between the upload and this hook.
     */
    @SpirePatch(clz = AbstractCreature.class, method = "loadAnimation",
            paramtypez = {String.class, String.class, float.class})
    public static final class LoadPatch {
        @SpirePostfixPatch
        public static void postfix(AbstractCreature __instance) {
            try {
                if (__instance == null) {
                    return;
                }
                // {@code atlas} is protected on AbstractCreature (a Spine atlas, not public API), so
                // it is read reflectively; the field is resolved once and cached.
                Field field = atlas();
                if (field == null) {
                    return;
                }
                Object value = Reflect.get(field, __instance);
                // Only a window can be told to free anything; the live game's own art must never
                // enter the registry, because this class has no way to give it back untouched.
                if (!World.anyInstalled()) {
                    return;
                }
                if (!(value instanceof TextureAtlas)) {
                    return;
                }
                if (OPEN.put((TextureAtlas) value, Boolean.TRUE) == null) {
                    loaded++;
                    if (loaded == 1 || loaded % 2000 == 0) {
                        Log.info("sandbox art: " + describe() + " (open=" + OPEN.size() + ")");
                    }
                }
            } catch (Throwable t) {
                if (logged++ < 4) {
                    Log.warn("sandbox art: recording a loaded atlas failed: " + t);
                }
            }
        }
    }

    private static Field atlas() {
        if (atlasField == null) {
            atlasField = Reflect.find(AbstractCreature.class, "atlas");
        }
        return atlasField;
    }

    /**
     * Frees every atlas this window created. Called from {@code World.uninstall()}'s finally, i.e.
     * on every exit of a window including the failure paths, so a window that threw half-way cannot
     * leak art either.
     */
    public static void close() {
        if (OPEN.isEmpty()) {
            return;
        }
        ArrayList<TextureAtlas> all = new ArrayList<TextureAtlas>(OPEN.keySet());
        OPEN.clear();
        int freed = 0;
        for (int i = 0; i < all.size(); i++) {
            try {
                all.get(i).dispose();
                disposed++;
                freed++;
            } catch (Throwable t) {
                if (logged++ < 8) {
                    Log.warn("sandbox art: disposing an atlas failed: " + t);
                }
            }
        }
        if (freed > 0) {
            windows++;
            // One line per window that actually loaded art: rare (splits and the like), so it does
            // not flood the log the way a per-node line would.
            Log.info("sandbox art: closed window " + windows + " (" + freed + " atlas(es)); "
                    + describe());
        }
    }

    public static String describe() {
        return "loaded=" + loaded + " disposed=" + disposed;
    }

    /** True when every atlas a window loaded has been handed back. */
    public static boolean balanced() {
        return loaded == disposed;
    }

    public static long loaded() {
        return loaded;
    }

    public static long disposed() {
        return disposed;
    }
}
