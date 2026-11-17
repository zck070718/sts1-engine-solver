# STS1 Engine Solver

A combat solver for **Slay the Spire 1** (1.0 / 12-18-2022) that does not model the game.

It **mirrors the real combat engine into an isolated copy**, lets the **game's own card, monster,
power and relic code run on that copy**, and searches the **states that really came out** of it. The
search result is a line, and executing a line is audited against the states the search predicted.

The architecture follows the reference project [CombatSolver (STS2)](https://github.com/Torch1230/CombatSolver):
capture a root state, simulate in the background without touching the live fight, print the route,
execute through the game's native entry points, and re-solve when reality diverges. The mechanism had
to be different because STS1's engine is Java plus global static state, so "background simulation"
here means *cloned worlds swapped into the static slots*, not a re-implementation.

---

## Why not simulate the cards by hand

A previous attempt in this workspace (`sts1-combat-solver`) re-implemented cards, monsters, powers,
relics and RNG in a hand-written model. It worked, and then bled:

* ascension damage tiers were missing per move, so a **12-damage intent matched nothing** and the
  panel showed a different move entirely;
* "ghost" moves existed in the model that the engine never has, so live intents could not be matched;
* `Slimed` was classified "cannot be simulated", so the search expanded **one node** and reported
  that non-solution as the best line;
* shuffle timing and draw-pile order drifted from the engine and produced "play a card you don't have".

Every one of those is a *transcription* bug. The fix chosen here is to stop transcribing: copy the
objects and call the real methods.

## Compatibility

The solver calls the game's own code, so it does not need a per-card table: **in theory it works with
any mod that adds content — cards, relics, potions, characters, monsters, events — without changing how
combat itself resolves.** A mod that rewrites combat rules (damage calculation, action queue, turn
structure, draw/shuffle order) is outside that boundary and may simulate differently from the live
fight. The solver also refuses to expand a branch it cannot replay faithfully and names it at the
bottom of the panel instead of guessing, so an unsupported interaction shows up as "未展开" rather than
as a wrong optimal line.

---

## How it works

1. **Mirror.** `World.captureLive()` deep-copies the combat object graph (player, piles, monsters,
   powers, relics, potions, orbs, the action manager, 13 RNG streams) without running a single
   constructor, and records the 453 process-global fields that hold combat state.
2. **Sandbox.** `World.install()` writes the copy into those static slots, so the engine's own code
   now operates on the copy; `uninstall()` writes the live values back. The live fight is never the
   thing being simulated. A combat entity that **only a static field points at** (a mod's pet, a
   static power) is not in the player/pile/monster graph, so `mirror/StaticEntities` walks that graph
   before every capture and registers such a field as a clone root by rule (engine base type +
   unreachable from the fight) — no mod class names are named anywhere. Two settings keys exist only
   to correct a misjudgement: `mirrorEntityRoots=off` disables the rule, `mirrorEntityIgnore=`
   (`owner#field,…`) skips single fields; both are logged when they fire.
3. **Execute real code.** A step is `AbstractCard.use` / a potion / the End Turn button, driven
   through `GameActionManager` with a synthetic frame delta (and the room's per-frame object pump,
   without which drawing stalls). No card effect is written by this project.
4. **Search.** Beam search over `World` copies: each edge is a real play, each node a real state,
   deduplicated by a readable state digest. Objective: survive, win, then lose the least HP, then
   spend the fewest potions.
5. **Execute with audit.** Each planned step stores the digest the search predicted. Executing
   compares the live digest after every action; the first mismatch invalidates the plan and asks for
   a re-solve. The plan is never allowed to disagree with the game.

### 面板按钮（唯一的正式操作方式）

面板固定在屏幕右上角，全部操作只用鼠标完成：

| 按钮 | 作用 |
| --- | --- |
| **计算** | 开始计算当前战斗；计算进行中再点一次即为停止（标签随状态变化） |
| **执行一步** | 提交计划中的下一步；游戏真正执行完后再比对摘要，不符即作废整条路线 |
| **自动出牌** | 开关。开启后按「节奏」逐步执行整条路线，直到战斗结束 |
| **秒数 / 节点** | 深度搜索的时间与节点预算，点击循环档位 |
| **节奏** | 自动出牌每步之间的间隔（默认 750 ms） |
| **药水** | 允许或禁用求解过程中使用药水 |
| **局外收益** | 是否把局外收益（金币、最大生命、永久伤害、升级、药水）计入评分 |
| **收起 / 隐藏** | 收起面板为一条标题，或隐藏整个覆盖层；隐藏后点屏幕边缘的恢复标签即可回来 |
| **导出报告**（标题行右侧小按钮） | 把日志尾部、设置、版本与当前路线打包成一个 zip，写到 `%USERPROFILE%\sts1-engine-solver\reports\`，并在资源管理器中定位；用户目录会被替换成 `%USERPROFILE%`，不含存档 |

> 1.0.0 起，F5–F12 的按键分支已从代码中删除（m03455）：唯一的操作方式是上面的面板按钮，按键不可能启动搜索、改预算或触发自检。方向键只负责滚动路线区。残留的 `autotest.request` 也不会在玩家机器上跑自检——那需要一个 `%USERPROFILE%\sts1-engine-solver\debug.keys` 标记，只有作者的 `tools/run.ps1` 会创建它。

The overlay follows the STS2 solver's route-row model (read from its `SolverRouteRow`, not guessed):
one row per TURN, one line per action, **every action names its card and its target**, the turn row
carries the turn's outcome (HP / energy / block) and each action carries the target's HP after it.
The current step is marked `▶`, and anything the solver refused to expand is NAMED at the bottom
("未展开（会打开选择提示）: …").

```
STS1 Engine Solver  [win line found]
可赢 · 预计掉血 10 · 3 回合 · 778 节点 · 1.2s
第 1 回合            HP 74  能量 0
▶1. 燃烧
  2. 打击 → 邪教徒    敌 51/51
  3. 结束回合          → 第 1 回合结束
第 2 回合            HP 80  能量 3
未展开（会打开选择提示）: Prepared, Survivor 等 2 张
[计算] [执行一步] [自动出牌] [收起] [隐藏]
```

On screen it also POINTS at what to do, the way the STS2 panel does: a box on the card to play, a red
box on the target monster with a dotted line between them, or a box on the End Turn button. A self-buff
or a potion with no target draws no target box -- naming a wrong monster would be worse than naming
none.

The hint appears as soon as a combat starts, so "the mod is loaded" is visible before any solve.
Rendering is in the game's virtual coordinate space; `Hud.demo()` (no key binding since 1.0.0)
draws a fixed banner, which separates "the panel cannot draw" from "there is no plan".

---

## Automated evidence

The mod has a harness mode: if `%USERPROFILE%\sts1-engine-solver\autotest.request` exists at
startup, it builds real combats **inside the running game**, runs the suite at the main menu, writes
the verdict to `solver.log` and halts the process. That makes "build → launch → verdict" one command
and lets every change be re-validated.

```powershell
powershell -NoProfile -File tools\install.ps1     # build + install into the game's mods dir
powershell -NoProfile -File tools\run.ps1         # launch, run the suite, print this run's log
```

Last full run: **54 checks, 0 failures**. Highlights:

| Check | Result |
| --- | --- |
| Synthetic fight built and started from nothing | turn 1, opening hand 5, energy 3, monster intent rolled |
| Mirror installed identifies with the live fight | digest equal, including monster `moveHistory` and RNG counters |
| Same action on live and on the mirror | **byte-identical resulting digests** |
| 12 mirrored steps across a turn boundary (2 monsters, Strength, Vulnerable, block, draw, shuffle) | **24/24 state comparisons exact** |
| Search on the first fight | win, **5 HP lost**, 778 nodes, 992 ms, 12 layers |
| Every predicted state reproduced on the live engine | **12/12 steps, 0 mismatches** |
| Solver line vs a naive "first playable card" policy | 0 HP lost vs 4 (illustrative, one fight) |
| Search leaves the live fight untouched | digest equal before/after |
| Sandbox actions leave the live fight untouched | digest equal, 3 actions executed |
| Static leak check around a sandbox session | **457 inventory fields, 0 changed**, restore exact |
| Gameplay path (F8/F9/F10 logic) on a real fight | solved, executed, **won at 74/80**, 0 drift |

## Content coverage (measured, not claimed)

There is no card table, no monster table and no relic table in this project. Coverage is therefore a
question about the ENGINE on a mirrored world, and the only honest way to answer it is to run every
piece. `tools/run.ps1 -Mode coverage` does exactly that, inside the running game:

| Content | Count | Result |
| --- | ---: | --- |
| Cards | **541** | **472 played for real**; 40 refused by the game's own rules; 30 opened a card-choice prompt (the one real gap); **0 exceptions** |
| Potions | **46** | **44 drank and resolved**; 1 death-only (Fairy Potion); 0 exceptions |
| Relics | **210** | **206** ran the full hook sweep; 4 throw only because they are out-of-combat card-transform relics (Pandora's Box, War Paint, Whetstone, one mod placeholder) whose `onEquip` needs a run-level screen |
| Monsters | **67** | **63** initialised and took two real monster turns; 4 have no constructor the sweep can reach; 0 exceptions |
| Characters | **6** | **6/6 built, mirrored, SOLVED and played** — Ironclad, Silent, Defect, Watcher, plus the **CaiNong** and **Ninja** mod characters |
| Leak | 397 static fields | after the **entire** sweep (370 cards + 42 potions + 189 relics + 62 monsters): **0 fields changed**, live fight digest unchanged |

Six more cards are only "unplayable" because of the test situation (3 energy, mixed hand) and play
fine with 99 energy and a clean hand: Blood for Blood, Meteor Strike, Clash, Sands of Time,
Force Field, Signature Move.

**These numbers are mod-inclusive**, and that took a fix: ModTheSpire's `--mods` flag FILTERS the load
list, so an earlier sweep reported 370 cards and "characters: 4" while claiming to cover the install.
`tools/run.ps1 -Mode coverage -AllMods` reproduces the player's real launch (19 mods, 21 jars).

**Mod characters work, with one rule learned the hard way**: a content object constructed OUTSIDE the
mirror can capture `AbstractDungeon.player` — the LIVE player — and then hit it. The sweep exposed
this (The Guardian's NoBlock/Weakened ended up on the live player). Potions, relics and monsters are
now all constructed *inside* the mirrored world, and the "live fight untouched" gate passes for an
entire 541-card / 46-potion / 210-relic / 67-monster sweep.

The sweep is also a harness test. Its first run found three run-level statics the sandbox was missing
(`AbstractDungeon.topPanel`, `lastCombatMetricKey`, `CardCrawlGame.dungeon`), which produced
NullPointerExceptions in the exhaustion path, `DaggerSpray` and the hand-select screen. The scenario
now installs a real run shell (`Exordium`), so those cards run instead of failing.

What this does **not** prove: coverage of every card, relic and monster *in every board state*. It does not need to, because
the engine executes them -- that is the point of the design. It does mean a real card this project has
never seen is *more* likely to work here than in a hand-written model, not less.

---

## Limits (current build)

* **Card-choice prompts** (discard / search / Scry style) are detected and the branch is refused
  ("a screen is open" blocks a solve), not modelled. A branch that would open one is dropped by the
  search rather than guessed.
* **Potion targeting** picks the first living monster for targeted potions.
* **Search horizon** is actions, not turns, with a wall-clock budget (3 s in game).
* **No map/pre-combat planning**: this solves the fight that is running, like the reference project's
  in-combat overlay (its pre-combat forecast needed a headless worker process, which STS1's asset
  chain does not allow -- documented in the predecessor's notes and not attempted here).
* Multi-threaded search is not implemented; the sandbox swaps global statics, which must be
  single-threaded by definition.

---

## Layout

```
src/main/java/sts1solver/
  SolverMod.java            mod entry, keys, harness auto-run
  Gameplay.java             solve the live fight, execute with per-step audit
  drive/Driver.java         frame-delta proxy, sound mute, drain, play, end turn, soul pump
  mirror/Cloner.java        constructor-free deep copy, identity-preserving
  mirror/ClonePolicy.java   what is copied, what is shared, what is linked
  mirror/Statics.java       the 453 global fields that hold combat state
  mirror/World.java         install / uninstall / fork / digest
  mirror/Digest.java        readable state fingerprint (dedupe + audit)
  mirror/LeakCheck.java     generated static inventory; proves nothing leaked
  search/Legal.java         candidate actions from the engine's own canUse
  search/Solver.java        beam search over real states, plan replay
  scenario/Synthetic.java   builds a real combat from nothing (tests only)
  probe/Probe.java          the automated suite
  ui/Hud.java               plan overlay
tools/                      build, install, run, javap helpers
research/javap/             decompiled engine signatures used while building this
```

## Build requirements

* Slay the Spire 1.0 at `D:\Steam\steamapps\common\SlayTheSpire` (override with `-GameDir`)
* ModTheSpire 3.30+ and BaseMod in the workshop directory
* A JDK for `javac` (the game itself runs on its bundled Java 8; the jar is built as Java 8 bytecode)