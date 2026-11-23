package sts1solver.mirror;

import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.megacrit.cardcrawl.cards.DamageInfo;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.NewExpr;
import sts1solver.util.Log;

/**
 * B218 (user m00005 / m00431 / m00504): a sandbox death must not run the LIVE death screen.
 *
 * <p>What went wrong: {@code AbstractPlayer.damage} ends a lethal hit with
 * {@code AbstractDungeon.deathScreen = new DeathScreen(getMonsters())} (the only {@code new}
 * DeathScreen in the engine; bytecode of {@code AbstractPlayer.damage}: offset 1107 {@code new
 * DeathScreen}, 1114 {@code <init>(MonsterGroup)}, 1117 {@code putstatic
 * AbstractDungeon.deathScreen} -- the object is stored and never dereferenced). That constructor is
 * not presentation: it builds a {@code GameOverScreen}, calls {@code UnlockTracker},
 * {@code StatsScreen} and the achievement APIs, runs {@code SaveHelper.shouldDeleteSave() ->
 * SaveAndContinue.deleteSave()}, {@code Prefs.flush()} (a synchronous disk write) and starts the
 * death BGM and the black-screen banner. A search plays thousands of lethal lines per second, so in
 * the 2026-11-23 Ninja-vs-Lagavulin fight the process ran 95,799 {@code GameOverScreen}s (41,741 of
 * them in the 15 s the game was stuck) and 460 GCs in 13 s; the task manager read ~90% memory and
 * the game looked frozen. It also DELETED the real run's autosave on the way.
 *
 * <p>Why not the simpler fixes:
 * <ul>
 *   <li>Clamping HP or intercepting damage changes what a lethal blow means -- last words, poison,
 *       thorns and explosions after the hit would stop resolving, so the search would answer a
 *       different fight.</li>
 *   <li>A {@code @SpireInsertPatch} inside {@code DeathScreen.<init>} still builds the object first
 *       and, as measured for {@link PresentationQuiet}, other mods' {@code insertAfter} postfixes
 *       run before every {@code RETURN} -- the side effect chain is exactly what we must not
 *       enter.</li>
 *   <li>Restoring {@code AbstractDungeon.deathScreen} on uninstall (which {@code Statics} already
 *       does) hides the screen but pays every cost again on the next death.</li>
 * </ul>
 *
 * <p>What this class does: at load time (before any {@code insertBefore}/{@code insertAfter}) it
 * rewrites the ONE {@code new DeathScreen(...)} call site in {@code AbstractPlayer.damage} into
 * {@code if (quiet()) { noteSuppressed(); $_ = null; } else { $_ = $proceed($$); }}, where
 * {@code quiet()} is {@link World#anyInstalled()}. The engine still runs the rest of the lethal
 * damage (HP to 0, {@code isDead}, block loss), so {@code Driver.deadFrom(isDead, currentHealth)}
 * and the search see exactly the same state; only the live-bookkeeping constructor is skipped. With
 * no window installed the bytecode behaves exactly as before.
 *
 * <p>What a sandbox death therefore no longer does: career/unlock/achievement bookkeeping, save
 * deletion, the {@code preferences\STSPlayer} write per death, music changes, the black screen and
 * other mods' {@code publishPostDeath} hooks.
 */
public final class DeathQuiet {

    /** {@code new DeathScreen} sites in the engine that this class expects to replace. */
    public static final int EXPECTED_SITES = 1;

    private static final String SITES_PROPERTY = "sts1solver.deathQuiet.patchedSites";

    /** How many suppressed-death lines to write to the log for one process. */
    private static final int NOTE_CAP = 3;

    private static int patchedSites;
    private static long suppressed;

    private DeathQuiet() {
    }

    /**
     * True while the sandbox statics are live. A lethal hit in that window is a simulated outcome,
     * not a game over, so the live death-screen constructor must not run.
     */
    public static boolean quiet() {
        return World.anyInstalled();
    }

    /** How many call sites the load-time rewrite actually replaced. */
    public static int patchedSites() {
        int local = patchedSites;
        if (local > 0) {
            return local;
        }
        try {
            String value = System.getProperty(SITES_PROPERTY);
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Called from the rewritten site once per suppressed death; logs only the first few. */
    public static void noteSuppressed() {
        ++suppressed;
        if (suppressed <= NOTE_CAP) {
            Log.info("sandbox death: live DeathScreen skipped (" + suppressed
                    + (suppressed == 1 ? ", later ones are not logged)" : ")"));
        }
    }

    /** Suppressed deaths in this process (diagnostics only). */
    public static long suppressed() {
        return suppressed;
    }

    private static ExprEditor editor() {
        return new ExprEditor() {
            @Override
            public void edit(NewExpr expr) throws CannotCompileException {
                if (!"com.megacrit.cardcrawl.screens.DeathScreen".equals(expr.getClassName())) {
                    return;
                }
                // "$_" is the constructed object, "$$" the original arguments. The engine stores it
                // with putstatic and never reads it inside the window, and Statics puts the real
                // static back on uninstall, so null is a safe stand-in.
                expr.replace("{ if (sts1solver.mirror.DeathQuiet.quiet()) {"
                        + " sts1solver.mirror.DeathQuiet.noteSuppressed();"
                        + " $_ = (com.megacrit.cardcrawl.screens.DeathScreen) null; }"
                        + " else { $_ = $proceed($$); } }");
                ++patchedSites;
                try {
                    System.setProperty(SITES_PROPERTY, String.valueOf(patchedSites));
                } catch (Throwable ignored) {
                    // a SecurityManager would be the only way this fails
                }
            }
        };
    }

    /** AbstractPlayer.damage(DamageInfo) -- the single lethal-hit site. */
    @SpirePatch(clz = AbstractPlayer.class, method = "damage", paramtypez = { DamageInfo.class })
    public static final class Site01 {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return editor();
        }
    }
}
