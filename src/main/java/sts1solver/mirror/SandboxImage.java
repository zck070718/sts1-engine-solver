package sts1solver.mirror;

import com.badlogic.gdx.graphics.Texture;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import java.util.HashMap;
import java.util.Map;
import sts1solver.util.Log;

/**
 * B217 (user m00001/m00301): a sandbox window must not build the same icon twice.
 *
 * <p>What went wrong (solver-20261124-002519.log, solver-20261124-100627.log, Ninja vs three
 * Sentries): mod power constructors load their two icons inline, e.g.
 * {@code ninja.jar powers.LexKela.<init>} runs {@code new AtlasRegion(ImageMaster.loadImage(
 * "img/powers_Ninja/LexKela84.png"), 0, 0, 84, 84)} and the same for the 32px icon. The engine's
 * {@code ImageMaster.loadImage} has no cache at all: its bytecode is {@code new Texture(fileHandle)}
 * then {@code setFilter(Linear, Linear)} and return, so every call decodes the PNG and uploads a
 * fresh GL texture. The constructor has to run -- it also writes {@code amount} and the power's
 * bookkeeping, so skipping it would simulate a different fight -- but the icon it drags along does
 * not.
 *
 * <p>The sandbox visits 430-436 worlds per second (status line: {@code 已查阅 174716 条世界线
 * （436 条/s）}), and every world that plays a ninjutsu card builds one LexKela, i.e. two textures of
 * 84x84 and 32x32 (27.6 KB + 4 KB). That is ~13.8 MB/s of VRAM, matching the measured 13.5 MB/s
 * (24.5 GB over the 1905 s of the crash run; Windows recorded a 26.3 GB commit for java.exe at
 * 10:05). The Java heap stayed at 500-1300 MB the whole time, so the collector never saw any of it;
 * once VRAM ran out WDDM paged the textures into system memory, the 32 GB RAM + 52 GB pagefile
 * filled, and a C2 compiler thread died in {@code Chunk::new} (hs_err_pid20716.log). The NPEs at
 * {@code TextureRegion.setRegion} in the tail of the log are the symptom that followed (loadImage
 * started returning null), not a second bug.
 *
 * <p>Why reuse instead of "dispose at window end" (the B201 rule for {@code TextureAtlas}): an icon
 * is pure presentation, identical for every world, and safe to share -- the 72 call sites found by
 * scanning the mod jars all store the returned texture in a region field and never dispose it. A
 * per-window dispose would pay the decode again in every window (the search installs one window per
 * expansion step), which is exactly the cost this fix is meant to remove.
 *
 * <p>Live game: when no world is installed every call continues untouched, so the live game keeps
 * its own textures and never receives a cached one.
 *
 * <p>Cost: one HashMap lookup per loadImage inside a window; distinct textures stay resident for
 * the rest of the session (bounded below).
 *
 * <p>Risk: a mod that disposes a texture it got from {@code loadImage} would later receive a dead
 * texture from this cache. No such call site exists in the installed mods (ninja, CaiNongMod,
 * TheNecrobinderPlus, loadout, mintySpire, NpcFeminization, FeminizationModSFW, NinjaSkin), and
 * {@code ImageMaster} is the only loader they use; if one appears, the entry count and byte budget
 * below keep the damage bounded rather than unbounded.
 */
public final class SandboxImage {

    /** Distinct textures kept for reuse. A 1 MB icon each would be 256 MB at the cap. */
    private static final int MAX_ENTRIES = 1024;

    /** Hard budget on the pixels kept resident, so one giant texture cannot fill the card alone. */
    private static final long MAX_BYTES = 192L * 1024L * 1024L;

    /** Path + filter flag -> the one texture the sandbox uses for it. */
    private static final Map<String, Texture> CACHE = new HashMap<String, Texture>();

    private static long bytes;
    private static long reused;
    private static long created;
    private static long refused;
    private static long failed;
    private static boolean capLogged;

    private SandboxImage() {
    }

    private static String key(String file, boolean flag) {
        // The flag is part of the key: the two overloads may pick different filtering, and one
        // overload's texture must never satisfy the other's call.
        return flag ? "1:" + file : "0:" + file;
    }

    /** The texture a window should reuse for this path, or null when it must load one. */
    static Texture hit(String file, boolean flag) {
        if (file == null) {
            return null;
        }
        try {
            if (!World.anyInstalled()) {
                return null;
            }
            Texture t = CACHE.get(key(file, flag));
            if (t == null) {
                return null;
            }
            reused++;
            if (reused == 1 || reused % 20000 == 0) {
                Log.info("sandbox image: " + describe());
            }
            return t;
        } catch (Throwable t) {
            if (failed++ < 4) {
                Log.warn("sandbox image: cache lookup failed: " + t);
            }
            return null;
        }
    }

    /** Records the texture a window just built, so the next world can reuse it. */
    static void record(String file, boolean flag, Texture t) {
        if (file == null || t == null) {
            return;
        }
        try {
            if (!World.anyInstalled()) {
                return;
            }
            String k = key(file, flag);
            if (CACHE.containsKey(k)) {
                return;
            }
            if (CACHE.size() >= MAX_ENTRIES) {
                refuse("entry cap " + MAX_ENTRIES);
                return;
            }
            long size = (long) t.getWidth() * (long) t.getHeight() * 4L;
            if (bytes + size > MAX_BYTES) {
                refuse("byte budget " + (MAX_BYTES / (1024L * 1024L)) + "MB");
                return;
            }
            CACHE.put(k, t);
            bytes += size;
            created++;
            if (created == 1 || created % 200 == 0) {
                Log.info("sandbox image: " + describe());
            }
        } catch (Throwable e) {
            if (failed++ < 4) {
                Log.warn("sandbox image: recording a loaded texture failed: " + e);
            }
        }
    }

    private static void refuse(String why) {
        refused++;
        if (!capLogged) {
            capLogged = true;
            Log.warn("sandbox image: not caching past " + why
                    + "; further loads run without reuse (this is a bound, not a fix)");
        }
    }

    public static String describe() {
        return "reused=" + reused + " distinct=" + CACHE.size() + " kept="
                + (bytes / (1024L * 1024L)) + "MB refused=" + refused;
    }

    static long reused() {
        return reused;
    }

    static long distinct() {
        return CACHE.size();
    }

    /**
     * {@code ImageMaster.loadImage(String)}: hand back the window's texture when it already has one.
     * A returned {@code SpireReturn} skips the body (no PNG decode, no GL upload); otherwise the body
     * runs and {@link RecordPatch} remembers what it built.
     */
    @SpirePatch(clz = ImageMaster.class, method = "loadImage", paramtypez = {String.class})
    public static final class LoadPatch {
        @SpirePrefixPatch
        public static SpireReturn<Texture> prefix(String file) {
            Texture t = hit(file, false);
            return t == null ? SpireReturn.<Texture>Continue() : SpireReturn.Return(t);
        }

        @SpirePostfixPatch
        public static void postfix(Texture __result, String file) {
            record(file, false, __result);
        }
    }

    /** The two-argument overload the engine and some mods use; kept as its own key. */
    @SpirePatch(clz = ImageMaster.class, method = "loadImage",
            paramtypez = {String.class, boolean.class})
    public static final class LoadFilteredPatch {
        @SpirePrefixPatch
        public static SpireReturn<Texture> prefix(String file, boolean flag) {
            Texture t = hit(file, flag);
            return t == null ? SpireReturn.<Texture>Continue() : SpireReturn.Return(t);
        }

        @SpirePostfixPatch
        public static void postfix(Texture __result, String file, boolean flag) {
            record(file, flag, __result);
        }
    }
}
