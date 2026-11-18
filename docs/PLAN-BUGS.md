# 用户反馈 bug 详细记录（PLAN 详细部分）

> 这是 `docs/PLAN.md` 里「实机 bug 跟踪」的展开：每条 bug 的现象、取证、根因、修法、门禁与复测。
> 总表见 `docs/PLAN.md`；其它板块的实现记录见 `docs/PLAN-WORKLOG.md`。

---

## 实机第二批反馈：B16–B19（✅ 281/0）

### B16 大史莱姆"未找到胜利路线"——诊断结果

新增 `-Mode bossa20`（A20 史莱姆老大 300HP + 14 张基础牌，8s）：

```
BOSSA20 result: no-win splits=38 deepest=33 ... reason=no-live-branches layers=36
BOSSA20 plan: 剑柄打击 → 史莱姆老大 | 痛击 | 结束回合 | ... (16 步)
BOSSA20 replay: maxMonsters=1 over=false hp=63
```

结论：**搜索其实建模了分裂**（`splits=38`，36 层），只是所有线都死光（`no-live-branches`）；
报告的那条 16 步线**在 Boss 掉到 50% 之前就结束了**（replay 里 `maxMonsters=1`，分裂从未发生），
所以实机上看起来"计划没到分裂"。

- A20 + 基础牌组打史莱姆老大，**很可能真的赢不了**；
- 但"最深线停在半路"这个呈现要改：`no-live-branches` 时应明确写"所有路线都会死，给的是最长生存线"，
  并对 Boss 战提高预算。→ 登记 **B16-followup**。

**已修（318/0，门禁 S47）**：新增 `search/PlanText`——按 `Solver.Result.reason` 分文案：可赢 /
只能逃跑（烟雾弹）/ **所有路线都会死（已搜索 N 条分叉）· 给最长生存线 M 步** / 所有路线都会死 ·
没有一条线能走完 / 未找到胜线（预算用尽或到深度上限分开说）。面板行、深度搜索结束状态、快速计划
状态都改用它（旧的面板行还重复拼了一句"未找到胜线：给出探索到的最深整条线"）。
新增 `ui/DeepBudget`：Boss 房（`instanceof MonsterRoomBoss`）秒数与节点各 ×2，仍受 10s 下限 /
600s、1,000,000 节点上限；`settings.txt` 里的数字不动，只是在它之上叠加，深搜日志会打 `boss=x2`。
**门禁看不到的**：套件里不站在 Boss 房，所以 S47 覆盖的是算术（60s→120s）、谓词（只有 MonsterRoomBoss
算）和"当前房间的深搜预算确实经 DeepBudget"；最后一跳是引擎的 `getCurrRoom()`，实机打 Boss 看日志
`boss=x2` 即可确认。

### B17 计算后出现"选牌"界面——不是镜像泄漏

日志：

```
20:43:12 WARN autopilot stopped: 重算仍未给出计划
20:43:16 INFO solve refused: a screen is open
20:43:25 INFO combat detected ...（新战斗）
```

那面界面是**战斗结束后的正常奖励/选牌界面**（自动出牌把这场打完了），我们的 `blockedReason` 正确地拒绝了计算。
顺手把英文提示改成中文：`有界面开着（选牌/奖励/地图）：先处理掉再计算`。

### B18 面板逐回合行太挤

计划文字行高 22px → **26px**，背景宽度 560 → 600px，悬停命中区同步；标题/按钮区上一轮已加 64px header。

### B19 漂移的第二个来源（卡顿/AI 判定）

实机日志 20:41:50 抓到：expected `ph=EXECUTING_ACTIONS` + `M0 pw=Vulnerable2 d=11`，actual `ph=WAITING_ON_USER` + `pw=-`。
两处都是**瞬态**：

- `ph`（动作管理器相位）同一状态相邻帧就不同；
- **濒死怪物（`d=11`）的 power 会被清空**，沙盒快照里还留着。

新增 `Digest.stable()`：**忽略相位/队列计数 + 忽略濒死怪物的 power**（但保留活怪的 power 与血量），
`pollPending` 用 stable 版本比对。门禁 **S42**（3 项）。

### 附带修复

- `Budget` 读缓存（1s）+ 写入失效（S41）；
- 自动出牌在引擎忙时不再每帧刷面板、也不计入失败；
- 漂移判定前先等 digest 连续 8 帧稳定。
## 实机第三批反馈：B23（四地精那场，✅ 398/0，门禁 S58）

用户报了三条（同一场四地精 A20、静默猎手）：①自己打没死，模拟却说无生还路线；②可能会死的对局“提前”出现战后奖励提示；③自动打牌中途卡顿。

日志取证（`%USERPROFILE%\sts1-engine-solver\solver.log`，01:39:45 起）：

```
01:39:51 solve: no-win ... nodes=1802 expansions=279 1423ms reason=no-live-branches layers=31
01:39:51 plan: 打击→卑鄙地精 | 打击→卑鄙地精 | 防御 | 结束回合 | ...（12 步）
01:40:09 WARN execute: card uuid ... (小刀) is no longer in hand
01:40:09 autopilot: plan exhausted, replanning the rest (attempt 1)   （随后 4 次整场重算 379–1387 nodes）
01:40:13 WARN autopilot stopped: 重算仍未给出计划
01:40:20 combat detected（下一场）
```

### ① 判死措辞（同时解释 ②的观感）

`PlanText` 只要 `reason=no-live-branches` 就说“所有路线都会死”，而这场只扩展了 **279** 次——A20 四地精这种宽场景根本不足以证明死。新增 `PlanText.PROVEN_EXPANSIONS = 1500`：

- `expansions >= 1500` → “所有路线都会死（已搜索 N 条分叉） · 给最长生存线 M 步”；
- `expansions < 1500` → “未找到胜线（只搜了 N 条分叉，不能证明会死；可加大预算或再按 F8） · 给最长生存线 M 步”。

autopilot 的两种停止文案也去掉“没计划”的口吻，改成“计划已走完，没有后续（不代表打不过）；可自己出牌，或按 F8 重算 / Shift+F8 加深”。

### ② “提前”出现的战后奖励屏

取证结论：**沙盒打不开这个屏**。

- `src\main\java` 里 `endBattle` / `openCombat` / `rewardPopOutTimer` 除注释外 0 命中；
- `pumpRoomFrames(AbstractRoom,int)`（`Driver.java:644`）无调用者，`room.update()` 只在它内部与 `Synthetic` 建场景时出现；
- javap -c：`combatRewardScreen.openCombat/open` 只在 `AbstractRoom.update()` 里调用（`getstatic AbstractDungeon.combatRewardScreen` + `invokevirtual openCombat`），`endBattle()` 本身不 open 奖励屏。

所以那面屏是真游戏在战斗真的结束（01:40:13–01:40:20）时自己开的；“提前”来自面板此时还挂着必死判词。
（曾把 `combatRewardScreen`/`deathScreen` 加进 `Statics.ROOTS` 做 CLONE 根，取证后已全部撤销：不需要，且每次 fork 多克隆 ~15µs。）

### ③ 自动出牌卡顿

根因：沙盒里由卡牌效果生成的牌（刀刃之舞 → `new Shiv()`）uuid 是新生成的，与实机那张不同；`findHandCard` 只认 uuid → 找不到 → 拒绝执行 → autopilot 判计划失效 → 整场重算（91–447ms，最长 1325ms）。

修法（三处 + 一条日志）：

1. `Gameplay.liveCardFor(Action)`：uuid 优先，未命中按 `cardId + cardName`（含升级名）回退到等价牌，日志 `execute: uuid … is sandbox-local; matched … by card id`；仍无才 warn 并拒绝（不画邻牌）。`syncMarkers` 也用它。
2. `Gameplay.cacheFits(cached)`：缓存命中前校验回合一致、首步出牌在当前手牌里（同一 cardId/cardName）、药水槽位在范围内；不满足则 `plan cache: hit rejected, does not fit the live hand | digest=…` 并丢弃。
3. `Solver.replay` 消费 `action.choice`：`Driver.resolveSelectionByNames(cardIds)` 按卡名逐个认领（同 id 重复也正确），认不到才启发式。修前 replay 不回答选择屏，计划第 5 步后失效，只报 12 步。

### 门禁与顺带修复

**S58**（4 条）：thin 文案含 279/“不能证明会死”且不含“所有路线都会死”；9000 扩展时说“所有路线都会死”；bogus uuid 按 cardId 回退到 `Shiv/小刀`；`resolveSelectionByNames` 错名拒绝（屏仍开）对名成功（屏关闭）。
**S46** 改稳：一次运行里 `Prefs.flush()` 入队的写盘用了 3.03s 还没落盘（文件 15s 后才出现）导致假失败 → 改成 3 轮 × 最多 5s、每轮重新 `boss.flush()`，失败时把目录清单打进 detail。
日志另加 `solve: stored plan | win/no-win digest=…`，以后可直接从日志判断缓存是否命中同一局面。

证据：`evidence\B23-GREMLIN-FEEDBACK-20261030.txt`（sha256 `45FF5AC2EC45B391E42148095B125EFC3242D79CBD4E35B999954E3B600CABF2`，8678 bytes）。
## B25 模拟战斗不再写玩家真实角色存档（✅ 454/0，门禁 S66，2026-10-30）

### 1. 现象

A3 收尾那一轮 443/3 里，S48 偶发失败：`Synthetic.newPlayer → Ironclad.<init> → loadPrefs → SaveHelper.getPrefs → handleCorruption`，报 `FileNotFoundException: …\preferences\STSDataVagabond.backUp (另一个程序正在使用此文件)`。顺着查，发现更严重的事实：**模拟战斗在写玩家真实角色统计**（`preferences\STSDataVagabond` 的 `ENEMY_KILL` 在套件窗口内从 895 涨到 8013，`STSDataDefect/TheSilent/Watcher` 的 mtime 也都落在窗口内）。

### 2. 根因

- `com.megacrit.cardcrawl.screens.stats.CharStat` 构造器拿 `AbstractPlayer.getPrefs()`，每个计数器（`incrementEnemySlain` / `incrementDeath` / `incrementBossSlain` …）末尾都 `Prefs.flush()`；
- `Prefs.flush()` → `AsyncSaver.save(filepath, json)` → `helpers\File.save()` 用 `Gdx.files.getLocalStoragePath() + 相对 filepath` **先删后写**；
- B22 只定向了 `UnlockTracker` 的 7 个 `Prefs`，玩家角色的 `Prefs` 没有任何隔离——模拟里的一次击杀就落到真文件；同一个文件被「异步写」与「新玩家 loadPrefs 读」同时碰到，就是 S48 的偶发失败。

### 3. 修法

| 文件 | 改动 |
| --- | --- |
| `mirror\PlayerPrefs.java`（新） | 两个 `IdentityHashMap` 作用域：`SESSION`（合成玩家，整场永久）与 `INSTALL`（真玩家，仅 install→uninstall 窗口）；`mute` 把 `data` 换成 `PrefsOverlay`、`filepath` 换到 `preferences\sts1solver-sandbox\player-<原名>`；`restoreInstall()` 按引用还原；另有 `mutedCount/originalFilepath/sandboxName/isPlayerSave` |
| `mirror\ClonePolicy.java` | `helpers.Prefs` 一律共享（不复制）——否则每个 fork 的副本都带着真实 filepath，模拟击杀照样写盘；共享后一次重定向覆盖所有分支，也省掉每 fork 复制 data map（顺手降本） |
| `mirror\World.java` | `install()` 先抓实机 player 引用，装完对「实机 + 副本」两个 player 都 `muteForInstall`；`uninstall()` 在重置 live 数组前 `restoreInstall()` |
| `scenario\Synthetic.java` | `newPlayer` / `newIronclad` 出生即 `muteForSession`——合成的角色从第一帧起只写沙盒 |

### 4. 门禁 S66（8 条）

```
PASS a synthetic player never saves into the real character file | preferences\sts1solver-sandbox\player-STSDataVagabond (real preferences\STSDataVagabond)
PASS the live character stats map is wrapped, not written into | sts1solver.mirror.PrefsOverlay
PASS a simulated stat flush lands in the sandbox directory | …\preferences\sts1solver-sandbox\player-STSDataVagabond
PASS the player's real character file is byte-identical afterwards | 91B / 91B
PASS no simulated counter reaches the real file |
PASS the live player's target is redirected only for the installed window | preferences\sts1solver-sandbox\player-STSDataS66
PASS uninstall gives the live player its own save target back | preferences\STSDataS66
PASS an install inside a sandbox fight does not steal the redirect | preferences\sts1solver-sandbox\player-STSDataVagabond
```

同一次套件里 S48 也恢复通过（`pool-r1.txt / pool-r2.txt` 都在），说明「自己写、自己读、抢同一个真文件」这条竞态没了。

### 5. 玩家存档现状（未擅自改写）

`tools\run.ps1` 结束时会用运行前快照恢复 62 个 preferences 文件，所以模拟期间涨上去的数字不留在盘上。取证时磁盘上有三份互相矛盾的 Vagabond：`preferences\STSDataVagabond`（895/4）、`.backUp`（894/4）、`sendToDevs\preferences\STSDataVagabond.corrupt`（1898/64，引擎 `handleCorruption` 自己挪走的副本）。bug 表 B13 早已记录过「测试改写了我的角色统计且数据不可恢复」，所以这次没有擅自把 `.corrupt` 写回去——要不要用 1898/64 覆盖，由用户在回复里定（这些都是角色统计页的数字，不影响战斗与解锁）。

### 6. 验证与残留

- 套件 **454/0**；jar `5ECA7354…A543`（493623 bytes）；覆盖不变。
- 证据：`evidence\B25-PLAYER-SAVE-20261030.txt`（sha256 `565182AE…21137`）。
- 残留：套件被强杀时脚本层面的 preferences 快照恢复不会执行；`CardLibrary.getCard(id).isSeen` 纯内存布尔仍未隔离（不写盘）；未认证 mod 自己保存的自定义数据不在隔离范围。
## 计算时屏幕抖动 + 面板上下太窄（✅ 480/0，门禁 S69 / S61）

用户 m08304：「1.计算时屏幕抖动，建议查看抖动屏幕是哪个函数，看哪mod计算里调用了。2.面板拥挤，看sts2怎么解决的，现在上下距离太窄了」

### B26 抖动的真凶：`CardCrawlGame.screenShake` 是共享单例

抖屏只有一条链：`com.megacrit.cardcrawl.helpers.ScreenShake`（进程里只有一个实例 `CardCrawlGame.screenShake`）。
`shake(...)` 把 `duration` 设为 0.3–3s，`update(FitViewport)` 每帧 `duration -= delta` 并按 `Interpolation.fade`+`cosDeg` 改写
`viewport.update(M_W ± |x|, M_H)` —— 这就是肉眼看到的抖动。

战斗里调它的两个地方（`javap` 已核）：
- `AbstractCreature.decrementBlock`：只要 `currentBlock > 0` 且不是 HP_LOSS，**先 `shake(MED, SHORT, false)` 再扣格挡**，
  不经过 `effectList`，所以「清空 VFX」拦不住；
- `ShakeScreenAction.update()`：同样直接 `shake`。
整个游戏 jar 里 50 个类引用 `screenShake`（含全部角色、几乎全部 Boss、多个 vfx）。

为什么我们这边会真的摇：`mirror/Statics` 的 ROOTS 里没有 `screenShake`，它被「shell 清扫」当成 `Kind.VALUE` 收进来，
而 VALUE 只保存/恢复**静态字段的引用**、不拥有对象。沙盒里 `CardCrawlGame.screenShake` 仍指向实机那个对象 →
模拟里每一次「打在格挡上 / 跑怪物回合」都把实机 `duration` 置正 → 主循环每帧真摇。与玩家装了什么 mod 无关，
只加载 BaseMod 也复现（门禁就是在这种最小环境下跑的）。

修法（`src/main/java/sts1solver/mirror/Statics.java`）：ROOTS 新增 `{CardCrawlGame.class, "screenShake"}`（CLONE），
`specs()` 后处理给它 `discardOnReadBack = true`（摇屏是表现层，回写会让搜索结束那一刻再摇一下）。
沙盒从此改的是自己那份；uninstall 时静态引用按身份恢复。

### B27 面板尺寸对齐 STS2

STS2（`CombatSolver-main/src/UI/SolverUiTokens.cs`）：ActionPillHeight 28、RouteRowHeight 44、Spacing Sm 8/Md 12、
ExpandedMaxWidth 820、ButtonHeight 34；`SolverOverlay` 的路线区是 ScrollContainer（行间距 Sm、pill 间距 Xs=4、
pill 内边距 Sm/Xs），可视区只放 3 行、其余「另有 N 回合未展开」。

我们的改动（`ui/RouteLayout` + `ui/Panel`）：PILL_H 22→28、HEAD_H 22→26、GAP_X 8→10、GAP_Y 6→10、TOP_PAD 4→8、
PILL_PAD_X 20→26、MIN_PILL_W 34→40、MAX_ROUTE_H 430→520、面板宽 600→720、视觉行高 24→26。
差别只在呈现方式：我们整条可滚（滚轮 / 上滚下滚按钮），STS2 固定 3 行 + 折叠；两者都能看到全部步骤。

### 验证

- 安装版 jar：512415 bytes，sha256 `3B4F0F913EBAA23539D6A005E9544B9965EAC0BCFB6A1FCF228741F557FF712E`；
- 套件 **480/0**（15:08:03；改前 472，新增 S69 六条 + S61 两条）；覆盖不变（cards 370/342、potions 43/41、relics 190/189、monsters 66/66、characters 4/4）；
- S69 日志：`S69 shake: live=1459502507 sandbox=504006993 liveDuration=0.0` + 四条 PASS（副本、沙盒 shake 不动实机、引用身份恢复、不回写）；
- S61 日志：`S61 strip: rows=2 pills=9 maxRight=222.0 total=250.0`（旧 208.0/188.0）+ 两条新 PASS；
- 证据 `evidence\B26-B27-SHAKE-PANEL-20261030.txt`（7374 bytes，sha256 `AC9B9E246DF288E380EE050368879935EBCFAF6232419F29EE80BB719EEB87DC`）。

### 同类风险（登记，不修）

`CardCrawlGame.sound` / `CardCrawlGame.music` 也是 shell VALUE，但 Driver 模拟期间把 `Settings.SOUND_VOLUME` 置 0，
听不到也看不见；教训写进 `docs/JUDGMENT.md` R13：静态表里指向「可变单例」的字段只恢复引用等于没隔离。

---
## 存档安全复核（✅ 480/0，B28 验证，无代码改动）

用户 m08627：「你只要确定之后启动mod不会损害存档就可以」。这条要的不是「我说我修好了」，而是从进程外面看得见的证据。

### 方法（不靠门禁自证）

`build\save-guard-watch.ps1`：
1. 记下 `D:\Steam\steamapps\common\SlayTheSpire\preferences\` 下**全部 62 个文件**的 SHA256 / 字节数 / mtime；
2. 后台跑 `tools\run.ps1 -TimeoutSeconds 2400`（整套 480 项，含数百场合成战斗——历史上正是这个路径把 `ENEMY_KILL` 从 895 写到 8013）；
3. 期间每 3 秒重比一次 62 个文件，结束后再比 SHA256。

### 结果

| 观察点 | 结果 |
|---|---|
| 62 个 preference 文件 | **SHA256 全部一致** |
| `STSDataVagabond/TheSilent/Defect/Watcher`、`STSUnlocks`、`STSUnlockProgress`、`STSSeenCards/Bosses/Relics`、`STSAchievements` | **一次都没被写**（mtime 未变） |
| 唯一被触碰的真文件 | `STSPlayer` / `.backUp`：**484B、SHA256 不变**（反复写同样内容；它是全局档案 alias/DMG_DEALT/各角色 `*_WIN`，不是我方代码写的——源码里 `playerPref`/`STSPlayer` 零命中；单场 `-Mode killcheck` 探针全程未触碰它） |
| `saves\` | 无新文件（只有当天的 `steam_autocloud.vdf`） |
| `preferences\sts1solver-sandbox\` | 出现 14 个文件：模拟写盘全落这里（`player-STSDataVagabond` 的 `ENEMY_KILL`=**6494**，真档 895） |

### 谁挡住了写入（三层）

| 写入路径 | 引擎位置 | 现在 | 门禁 |
|---|---|---|---|
| 角色存档 `STSData<角色>` | `AbstractPlayer.getPrefs()`，`CharStat.increment*` 后 `Prefs.flush()` | `mirror/PlayerPrefs`：`data` 换 `PrefsOverlay`、`filepath` 换 `preferences\sts1solver-sandbox\player-*` | S66 |
| 解锁/已见簿记（7 个 Prefs） | `UnlockTracker.*Pref.flush()` | `Statics.PREFS` 一起重定向 + `readBack` 跳过 | S46 |
| 静态单例上的表现层 | `cardRewardScreen`、`screenShake` | `Statics.ROOTS` CLONE + `discardOnReadBack` | S45 / S69 |

### 边界（不在保证内）

1. **不要一边玩一边跑 `tools\run.ps1`**：历史上文件被写坏就是两个进程写同一个存档目录（`.backUp` 被占用）。
2. 别的 mod 自己的存档写入不归我们管。
3. Steam 云会同步坏文件，不会自愈。
4. `CardLibrary.getCard(id).isSeen` 纯内存标记不隔离（不写盘）。
5. 已经写坏的 `STSDataVagabond` 不会自愈；补不补 `ASCENSION_LEVEL=20` 由用户定。

### 产物

- 证据 `evidence\B28-SAVE-GUARD-20261030.txt`（4489 bytes，sha256 `27BE1623E1D9C76E59ABFBDF36963229FB8CD8E3E69E70FC854BB03E09315B11`）；
- 脚本 `build\save-guard-watch.ps1`（可随时复跑，退出游戏后跑最稳）。
## 选角色开局闪退（✅ 483/0，门禁 S70）

用户 m08747：「为什么现在选择角色开始游戏后会闪退，看日志哪里出问题了，不要主观臆断」。

### 崩溃（外部证据，不是我们的日志推断）

`D:\Steam\steamapps\common\SlayTheSpire\sendToDevs\logs\SlayTheSpire.log`：

```
07:43:54.434 INFO core.CardCrawlGame> Exception occurred in CardCrawlGame render method!
java.lang.NullPointerException: null
    at com.badlogic.gdx.graphics.g2d.GlyphLayout.setText(GlyphLayout.java:63)
    at com.megacrit.cardcrawl.helpers.FontHelper.renderFontCentered(FontHelper.java:1414)
    at com.megacrit.cardcrawl.vfx.scene.LevelTransitionTextOverlayEffect.render(LevelTransitionTextOverlayEffect.java:57)
    at com.megacrit.cardcrawl.dungeons.AbstractDungeon.render(AbstractDungeon.java:2802)
    at com.megacrit.cardcrawl.core.CardCrawlGame.render(CardCrawlGame.java:441)
```

随后 `PAUSE()` / `Game shutting down...` —— 这就是「闪退」。

### 根因链

1. `LevelTransitionTextOverlayEffect.render` 的 LineNumberTable（javap）：`line 57: 7` → offset 7..41 用 `this.levelNum`；`GlyphLayout.setText` 的 `line 63: 0` 是 `CharSequence.length` ⇒ 崩的是 **levelNum 为 null**（不是字体、不是 name）。
2. 该字符串在**构造时**就抄进字段：`NeowEvent` 里 `new LevelTransitionTextOverlayEffect(AbstractDungeon.name, AbstractDungeon.levelNum, true)` → 加进 `AbstractDungeon.topLevelEffects`。
3. 主菜单下 `AbstractDungeon.levelNum` **本来就是 null**：全 jar 唯一写者是 `DungeonTransitionScreen.setAreaName(String)`，只在玩家点「开始」那条路（`CharacterSelectScreen.updateButtons()` 的 confirm 分支 → `CardCrawlGame.update()` :677-679 `new DungeonTransitionScreen("Exordium")`）里被调用；BaseMod 的 `DisplayRunInfo.getActName` 只是主菜单渲染时快照/写回。本地化不是原因（25 种语言 `DungeonTransitionScreen` 全部 11 项、无 null）。
4. 合成战斗会在主菜单造一个真地牢：`Synthetic.installRunShell` 在 `CardCrawlGame.dungeon == null` 时 `new Exordium(player, [])` → `AbstractDungeon` 构造器建第一个房间（NeowRoom）→ `NeowEvent` 立刻排队那个 overlay，**对象落在实机的 `topLevelEffects` 里**，levelNum = null。
5. 主菜单阶段它是哑的：`CardCrawlGame.render` 的 `switch(mode)` 里 CHAR_SELECT 画 `mainMenuScreen`，不跑 `AbstractDungeon.render`。玩家一开局（mode=GAMEPLAY、dungeon != null）第一帧就遍历 `topLevelEffects` → 渲染它 → NPE。
6. 为什么会发生在这台机器上：旧版 `SolverMod` 跑完探针不删 `%USERPROFILE%\sts1-engine-solver\autotest.request`，于是**每次启动游戏都会在主菜单自动跑一场合成战斗**。崩溃进程 15:43:12 的日志是 `AUTOTEST requested (mode=audit-summon)` → 15:43:14 `combat detected: F8 solves it`（主菜单仍被判在战斗里）→ 15:43:54 玩家开局 → 崩。

### 复现（决定性证据）

`-Mode namecheck-pollute`（`probe/NameProbe.runPolluted`）：先按真实探针的做法建合成战斗（`Synthetic.build` + World install/uninstall），再按「点开始」的真实顺序开局（`Settings.seed` → `isFadingOut` → `fadeOutMusic()` → `generateSeeds()` → ascension → `chosenCharacter`），最后 45s 每 2ms 采样 `levelNum`。

修复前（16:15:05，jar `B932E94A…`）：

```
16:15:05.985 NAMECHECK t=213ms levelNum null -> null, name null -> '塔底'
16:15:50.773 NAMECHECK overlay: levelNum='null' name='塔底'
08:15:08.419 java.lang.NullPointerException: null
    at com.megacrit.cardcrawl.vfx.scene.LevelTransitionTextOverlayEffect.render(LevelTransitionTextOverlayEffect.java:57)
```

同一行栈、同一个原因，稳定复现。

### 三处修法（都不碰判定）

| 位置 | 改动 |
|---|---|
| `src/main/java/sts1solver/scenario/Synthetic.java` | 新增 `dropActTransitionOverlays()`，`installRunShell()` 末尾把 `topLevelEffectsQueue`/`topLevelEffects` 里的 `LevelTransitionTextOverlayEffect` 全部摘掉并记数（`run shell: dropped N act-transition overlay(s)`） |
| `src/main/java/sts1solver/SolverMod.java` | 请求文件一次性：分派完 `deleteRequest()`；启动时 `cleanStaleRequests()` 删 5 分钟以上的 `autotest*.request`（launcher 写完请求几秒内就启动 JVM） |
| `src/main/java/sts1solver/probe/KillProbe.java` | `Driver.end()`/`readBack()`/`uninstall()` 三段各自 try/catch（原来共用一个 finally，任一句抛异常就跳过 uninstall，把沙盒静态留在实机里） |

### 验证

- 新门禁 **S70**（3 条）：`S70 overlays: queued=0 live=0 levelNum=null`；队列空、实机列表空、其余 overlay 都有 levelNum。
- 全套 **483/0**（16:19:16；改前 480）。jar `AE98614D41BB1AB92B73D23FE0190424AA378364479A86B1BD27EB5B55C668EA`（521429 bytes）。
- 修复后同一路径（16:20:07，`-AllMods` 19 个 mod）：`run shell: dropped 1 act-transition overlay(s)`、`NAMECHECK overlay: levelNum='第一阶段' name='塔底'`、`verdict: survived`、`AUTOTEST PASSED`。

### 边界

1. 旧版已经种进内存的 null overlay 只能靠重启进程清掉；装这版后不会再种。
2. 探针（killcheck/audit-*）仍会在主菜单建一场合成战斗（它们本来就是干这个的），只是不再留幕间文字；跑完请求文件即删，正常启动不会自动跑探针。
3. 没改 Solver/Driver 任何规则：原 480 条门禁一条没变，只多了 S70。
4. 顺带确认：`NameProbe` 早期手工搭局（跳过 `generateSeeds()`）会崩在 `AbstractDungeon.cardRng == null`（javap line 3110 是 `cardRng.counter` 读）——那是探针自己的错误，与用户这条崩溃无关；复现开局必须走真实「点开始」顺序。

### 产物

- 证据 `evidence\B29-CRASH-LEVELNUM-20261030.txt`（8954 bytes，sha256 `AE940D7A21158E64D6A1B8250EE464A897AA8DDDDF7E59419306B828174888C3`）；
- 探针 `src/main/java/sts1solver/probe/NameProbe.java`（`-Mode namecheck` / `-Mode namecheck-pollute`，可随时复跑）。
## B30 面板出牌区上下太挤 + 要看得到之后的出牌（✅ 486/0，门禁 S61）

用户 m09373：「左边的出牌太挤了，上下间距调大，而且要能上下滑动看之后怎么出牌」。

### 现象
- 路线条里的动作药丸（面板左侧「第 N 回合」下的方框）两行贴在一起，回合与回合之间也看不出分界。
- 滚轮/「上滚 下滚」其实一直能用，但屏幕上没有滚动条，也不显示「后面还有多少步」，所以看不出还能往下看。

### 取证
- `ui/RouteLayout`：B27 后是 `PILL_H=28 / HEAD_H=26 / GAP_Y=10`，回合之间只靠行距，没有独立间距。
- `ui/Panel.drawPlanText`：视口 `MAX_ROUTE_H=520`，滚轮步长 3×药丸高；没有任何滚动指示器；顶部提示只说「跟随当前步 / 已滚动离开当前步」。

### 修法（纯几何与绘制，Solver/Driver 一行未动）
| 项 | 旧 | 现在 |
|---|---|---|
| 药丸高 `PILL_H` | 28 | **30** |
| 回合标题 `HEAD_H` | 26 | **28** |
| 换行行距 `GAP_Y` | 10 | **16** |
| 回合间距 `TURN_GAP` | 无 | **10**（只在两个回合之间加，末回合不加，否则每次重画多一条空带、滚动条到不了底） |
| 顶部留白 `TOP_PAD` | 8 | **10** |
| 药丸内边距 `PILL_PAD_X` | 26 | **28** |
| 药丸最小宽 `MIN_PILL_W` | 40 | **44** |

- 滚轮/键盘步长 3×药丸高 → **2×药丸高**（一次滚两行，还看得见上下文）。
- 路线条右边缘新增**滚动条**：底槽 + 滑块，`thumbH = max(24px, 视口² / 总高)`，内容不超出视口时不画。
- 顶部提示追加「· 后面还有 N 步」（N = 当前步之后剩下的动作药丸数）；底部提示改成「滚轮 / 上滚 下滚 = 看之后怎么出牌    点方框 = 执行到这一步」。

### 门禁（S61 从 11 条扩到 12 条）
把写死的 `PILL_H==28 / HEAD_H==26` 换成下限断言，并新增三条精确几何断言：
```
S61 strip: rows=2 pills=9 maxRight=226.0 total=306.0
S61 spacing: pillLine=46.0 turnGap=10.0
PASS the strip uses taller pills and row labels (>= 30 / 28) | pill=30.0 head=28.0
PASS turn rows and pills are separated enough to read | gapY=16.0 gapX=10.0
PASS wrapped pill lines use the pill gap | pillLine=46.0
PASS turn blocks are separated by the turn gap | gap=10.0
PASS no trailing gap after the last turn | total=306.0
```
同一场景旧值是 `total=250.0`（现在 306.0）。

### 产物
- 证据 `evidence\B30-PANEL-SPACING-20261030.txt`（4075 bytes，sha256 `ACB5181E62EAB4C94E1C70D7A3097D91C7863C2D90DF4A0E679B30821CDC2A2F`）。
- 安装版 jar 522226 bytes，sha256 `A387F0D2F6F63B82AF9154AB41440FF1E8CA83F61EA8B8F0755B46D2570AD24F`；套件 486/0。
- 覆盖未复跑：只改 UI 几何与绘制。

### 实机复测
1. 出计划后两个回合之间应明显留白，药丸之间更松。
2. 计划长于视口时右侧出现滚动条；滚轮或「上滚 / 下滚」能往下看，顶部会说「后面还有 N 步」。
3. 滚回当前步后提示从「已滚动离开当前步」变回「跟随当前步」；点药丸仍「执行到这一步」。

## B31 每个回合的标题被出牌挡住 → 改成 STS2 的一回合一张卡片（✅ 487/0，门禁 S61）

用户 m09521/m09523（附两张截图：我们现在的面板 + STS2 参考面板）：「每个回合的步骤和第几回合挡住了。看我给你的图片（现在错误的面板）调整到sts2的面板（第二张图片）」。

### 现象
- 每个回合的标题行「第 1 回合　HP 80 → 80　能量 0　格挡 5」与它自己的动作药丸画在同一段纵向空间，药丸底色把标题盖掉一半；
- 回合换行成两行药丸后更明显：标题夹在两行药丸中间，看不出这两行属于哪个回合；
- STS2 的样子是：一个回合一整块卡片，左边一列写「第 N 回合」，中间药丸，右边右对齐写数字，块与块之间留白。

### 根因
1. `ui/RouteRow` 只有一个 `head` 字符串，把「第 N 回合」和「HP/能量/格挡」粘在一起，渲染层没法分开摆；
2. `ui/RouteLayout` 把它当「回合标题行」画在卡片顶部 `HEAD_H * 0.72` 处，药丸从 `HEAD_H` 之下开始排，但**标题是整行宽度、药丸同行从 x=0 起画**，于是同一水平区间里互相压；
3. 没有「卡片」这一层：一个回合换行后只是散落的方框，视觉上分不出回合边界。

### 修法（结构改成卡片式；Solver/Driver 一行未动）
| 项 | 旧 | 现在 |
|---|---|---|
| 回合文字 | 一个 `head` 字符串 | 拆成 `label`（「第 1 回合」）+ `stats`（「HP 80 → 65　能量 3　格挡 5」），`head` 保留兼容 |
| 标题位置 | 卡片顶部、整行、被药丸压 | 卡片内左侧独立列 `LABEL_W=104`，药丸永不进入这一列 |
| 卡片 | 无 | 每回合一张卡片：底色 + 描边 + 左侧 3px 色条；当前回合金色描边 |
| 数字 | 粘在标题里 | 右对齐到卡片内右边缘，只占第一行药丸的右侧空间（后续换行用整行宽） |
| 卡片间距 | 无（只有行距） | `TURN_GAP=10` |

- `ui/RouteLayout`：`PILL_H=30`、`LABEL_W=104`、`CARD_PAD 12/10`、`LABEL_GAP=8`、`STATS_GAP=18`、`TURN_GAP=10`、`TOP_PAD=8`；`Placed` 新增 `labelW/labelX/textBaseline/statsRight/statsW`。
- `ui/Panel`：新增 `drawCard(...)` 与 `rowHasStep(...)`；标题画 `x+labelX`（当前回合金色、其它淡蓝），数字右对齐到 `x+statsRight-statsW`；药丸坐标、点击映射、滚动、滚动条全部沿用。
- `Gameplay.buildRoute`：构造 `new RouteRow("第 N 回合", "HP a → b　能量 c　格挡 d")`。

### 门禁（S61 从 12 条扩到 15 条）
```
S61 strip: rows=2 pills=9 maxRight=232.0 total=440.0
S61 cards: minPillX=124.0 labelRight=116.0 baseline=30.4 cardH=280.0 statsW=0.0
S61 spacing: pillLine=46.0 cardGap=10.0
S61 real card: labelRight=116.0 firstPillX=124.0 statsLeft=450.0 statsRight=690.0 invasion=0.0 cardH=96.0
```
- 新增：`no pill is drawn over the turn label column`（124 ≥ 116）、`the turn label sits on the first pill line, not above it`（baseline 30.4 落在 10..40 带内）、`a real card keeps the turn label out of the pills`、`no pill on the first line reaches into the numbers`（invasion=0.0）、`the numbers are right-aligned inside the card`、`a wrapped turn card is taller than one pill line`（280）。
- 保留：`pillLine = PILL_H + GAP_Y`（46）、`cardGap = TURN_GAP`（10）、无尾部空带。
- 中间一次 483/1 是门禁自己写错：断言 `baseline <= PILL_H`（30.4 > 30），改成「与第一行药丸同带」后通过。

### 产物
- 证据 `evidence\B31-PANEL-CARDS-20261030.txt`（5031 bytes，sha256 `B2089A31136AC40B1FA75ECB3BFEEA20530D033F13F3A59486F62D316CFD203B`）。
- 安装版 jar 524216 bytes，sha256 `2FE43AF0F08DDD8E8460A0D0C6C94AA20C869ACE9E20C0719B966C6E55DD962D`；套件 **487/0**（17:03:31，改前 486）。
- 覆盖未复跑：只改 UI 结构与绘制。

### 实机复测
1. 出计划后，每个回合是一整块卡片：左边「第 N 回合」，中间药丸，右边右对齐「HP a → b　能量 c　格挡 d」；标题不再被药丸盖住，换行后仍属于同一张卡片。
2. 当前回合那张卡片金边高亮，已执行的更暗。
3. 滚轮 /「上滚 下滚」仍能看后面的回合；点药丸仍「执行到这一步」。

## B32 面板四条：滚出去的卡片不消失 / 文字太窄 / 底部提示 / 重复文本（✅ 495/0，门禁 S71）

用户 m09704（截图）+ m09705：「1.上滑之后本来应该消失的第一回合框没有消失。2.面板文字左右距离太短。3.下面“已滚动离开当前步。。。执行到这一步”这一串都可以删掉，占位置还没用。4.重复出现的文本要去掉」。

### 1. 滚上去之后第一回合的框还在

**根因**：`ui/Panel.drawPlanText` 只挡「整张卡片都在视口外」——
`if (rowTop < viewBottom || rowTop - p.height > routeTop) continue;`——
部分越界的卡片仍按完整尺寸绘制，底框/金边/左色条直接画在 `routeTop` 之上，压住标题文字。

**修法**：新增 `ui/RouteLayout.clipBand(y, h, clipBottom, clipTop)`（返回交集 `{y, height}`，无交集 null）。
卡片按交集画；回合标签与右侧数字的基线不在带内就不画；药丸只有完全在带内才画（跨边界跳过，不会露半颗）。

### 2. 文字左右距离太短

**根因**：盒子宽度写死 `720 * scale`，而「来源: … 9 个 mod 战斗钩子（未认证）: demoMod.ninjaMod#postDrawSubscribers, …」、
「预算: 300s / 400000 节点（面板「秒数」「节点」按钮切换，也可用 F5 / Shift+F5）」这类行比 720 长，文字跑出盒子直到屏幕边缘。

**修法**：新增 `ui/TextWrap`——
- `boxWidth(minWidth, maxLineWidth, padX, available)`：`max(720*scale, 最长行 + 2*pad)`，夹到屏幕可用宽；
- `wrap(lines, maxWidth, measure)`：仍超宽的行按**字符**折行（中文无空格），不丢字；`Panel.fittedLines` 做缓存；
- 盒子起点 `x = 22*scale`、内边距 `pad = 20*scale`；头部行数按折行后的行数算，路线条自动下移。

### 3. 底部两行提示删掉

删除 `viewHint(...)`（「跟随当前步 / 已滚动离开当前步 · 共 N 回合 · 点这里回到当前步 · 后面还有 N 步」）、
「滚轮 / 上滚 下滚 = 看之后怎么出牌    点方框 = 执行到这一步」，以及那条**隐形**的「回到当前步」点击区（step = -2）。
保留滚动条、「上滚 / 下滚」按钮、滚轮、点药丸执行；视口高度改成 `min(总高, min(MAX_ROUTE_H, routeTop - 26*scale))`，把这块空间吃回来。

### 4. 重复文本

两处：①`ui/Panel.render` 在标题带又画了一遍 `status`（`top - 44`），而 `Gameplay.lines()[0]` 本来就把它拼在标题行；
②`Gameplay.lines()` 标题行在有计划时也拼 `[status]`，与计划那一行重复。
修：标题带只画「STS1 求解器」；标题行只在 `plan == null` 时显示 `[status]`。

### 门禁 S71（8 条）
```
S71 fit: grown=1140.0 clamped=1400.0 minimal=720.0 wrapped=4
PASS the box grows to fit the longest line | width=1140.0
PASS the box never grows past the screen | width=1400.0
PASS a short header keeps the normal width | width=720.0
PASS a long line is wrapped without losing characters | lines=4 widest=100.0
PASS the wrapped line really breaks into pieces | lines=4
PASS a card crossing the bottom edge is cut at the edge | 120.0/30.0
PASS a card fully below the strip is not drawn | null
PASS a card taller than the strip is limited to the strip | 0.0/300.0
```

### 产物
- 证据 `evidence\B32-PANEL-CLIP-20261030.txt`（5355 bytes，sha256 `41E6ED0F90E1A96881FD9B9F83CF9A0201E417E4E48406FA8DA794AC100EBEEB`）。
- 安装版 jar 527887 bytes，sha256 `20CAC3066CF97197E61A23A4EE75E2586F98603A2F19CB59190BC5353C2F3D3E`；套件 **495/0**（17:34:17，改前 487）。
- 覆盖未复跑：只改 UI 绘制与文本排版。

### 实机复测
1. 长计划往上滚：第一回合卡片随视口被截断，不再整块压到上面的标题文字；最上面的卡片可能只露下半截。
2. 头部几行在黑底框内换行，右边不再顶到屏幕边缘、不再跑出框。
3. 底部不再有两行提示；滚动条、「上滚 / 下滚」、滚轮、点药丸执行仍可用。
4. 状态句只出现一次。

---

## B33 面板四条：多余行 / 结束回合归属 / 可缩放 / 长标签换行（✅ 505/0，门禁 S72，2026-10-30）

用户 m10040 / m10041（附图）原话：

> 1.现在面板上面来源难度预算遗物，面板按钮这几个都多余，sts2不也是只有预计掉血，节点这些的吗。
> 2.结束回合和结束回合后扣血这些应该在本回合这一行，比如第一回合打击×3后面接结束回合扣多少血而不是放到第二回合。
> 3.能不能做到拖动缩放的面板，可以自行调整面板大小（就是出牌提示那一块的大小），sts2可以。
> 4.一回合的动作一行放不下就换行，不要把后面的字都到屏幕外面了。

### 根因

1. **多余行**：`Gameplay.lines()` 把排查信息当面板行 —— 来源行（`origin.describe()`）、难度行、
   `Budget.describe()`、`RelicRow.describe()`，以及末尾「面板按钮：计算/停止/…（快捷键 F8…）」。
   STS2 的面板只有路线与数字，这些不该占头部。
2. **结束回合跑行**：`Solver.Step.turn` 取的是动作应用**之后**的 `GameActionManager.turn`，
   所以 endTurn 那一步的 `turn` 已经是下一回合 → `buildRoute` 按 `st.turn` 分行就把「结束回合」
   推到第二回合；而 endTurn 那一步的 `hp` 正是敌方回合打完的血，于是「扣血」也跟着进了下一行。
3. **不能缩放**：出牌区宽度写死 `720*scale`，只跟整体 UI 缩放，没有用户可调的尺寸，也没有 grip。
4. **长标签出屏**：`TextWrap` 只折「整行文字」，pill 内部从没折过；且首个 pill 在 `x==0` 时
   无条件画，右侧 HP/能量数字会被压住。

### 修法

| 项 | 改动 |
| --- | --- |
| 精简头部 | `Gameplay.lines()` 删除 来源/难度/预算/遗物/面板按钮 五行；保留标题行、引擎异常、`plan==null` 提示、逃跑提示、风险行、已执行行、药水/成长/回血行、结果行（预计掉血/节点/ms）、promptCards 行。删掉的信息仍写 `solver.log`。 |
| 结束回合归本回合 | `buildRoute()` 先算 `shown[i] = st.action.isEndTurn() ? st.turn - 1 : st.turn`，按 `shown` 分行；endTurn 药丸尾标「预计 -N HP」（前一步 hp 减本步 hp）；新增 `turnStats(rowFirst, turn, shown)` 生成每行数字（HP 起始 → 该行最后一步，含敌方回合；能量/格挡取该行最后一个非 endTurn 步）。旧 `lastStepOfTurn()` 删除。 |
| 拖动缩放 | 新文件 `src/main/java/sts1solver/ui/RouteSize.java`（0.6–2.0，默认 1.0，存 `settings.txt` 的 `panelScale=`，保留其它键；`preview`/`commit` 分离避免每帧写盘）；`Panel` 在出牌区右下角画 grip（18*scale 方框 + 三点），拖动时 `preview`，松手 `commit`；grip 位置由 `innerW` 推出，永远在按钮面板左侧。 |
| 换行 | `RouteLayout.Placed.pills` 元素从 5 元变 6 元 `{x,y,w,h,step,textLines}`；标签超宽时 `TextWrap.wrapLine` 折行，pill 高 = `textLines*PILL_LINE_H + 2*PILL_PAD_Y`；换行条件加 `statsLine`（首行有右侧数字时也允许换行）；出牌区宽度取 `min(可用宽, PanelLayout.x0 - x - 14*scale)` 并随 `RouteSize` 缩放；`Panel.drawPill` 多行逐行画。 |

### 门禁 S72（十条，日志 18:30:08）

真打一场（seed=73，Strike_R×3 + Defend_R×2 打 JawWorm）→ `Gameplay.solveLive()`：

```
S72 turns: rows=4 endTurnRows=3 rowsStartingWithEndTurn=0
S72 grip: 2034.6666,1138.6666 24.0x24.0 panelLeft=2136.0
S72 panel: lines=2 closedTurns=3
```

- 面板行里没有 来源: / 难度: A / 预算: / 遗物: / 面板按钮：；
- 仍有「预计掉血」行（结果没被误删）；
- `route()` 至少 2 回合，且**没有任何回合行以「结束回合」开头**，同时至少有一个结束回合药丸；
- `RouteSize` 系数夹到 MIN/MAX；
- `Panel.render` 后 `gripRect` 非空、右边界 ≤ `PanelLayout.x0`（不在按钮面板下面）、且未被拖动。

S61 同时扩到 15 条（pill 折行、首行 pill 不压右侧数字）：

```
S61 strip: rows=2 pills=9 maxRight=224.0 total=440.0
S61 cards: minPillX=124.0 labelRight=116.0 baseline=30.4 cardH=280.0 statsW=0.0
S61 spacing: pillLine=46.0 cardGap=10.0
S61 real card: labelRight=116.0 firstPillX=124.0 statsLeft=450.0 statsRight=690.0 invasion=0.0 cardH=96.0
```

### 产物

- 安装版 jar：539021 bytes / 237 entries / sha256 `6F397729B44DDE688A546A3557984B72CE47563C4F89FDB0E73F890B220BC522`。
- 套件：`tools\run.ps1 -TimeoutSeconds 2400` → **passed=505 failed=0**（改前 495/0）。
- 证据：`evidence\B33-PANEL-MINIMAL-RESIZE-20261030.txt`（5967 bytes，sha256 `9849B09ADECC2606A8A6E58A3C5A1D4179503BFFF7DCB59628A4785144AA79A9`）。
- 只动 UI 几何与面板文案；`Solver`/`Driver`/判定未改一行，覆盖数字沿用同代码路径的 `-Mode coverage` 结果。

### 实机复测
1. 打任意一场按「计算」：面板第一屏只有标题行 + 结果行（预计掉血/节点），不再有五行诊断信息。
2. 看第一回合那行：最后是「结束回合」方框，有扣血时写「预计 -N HP」；第二回合行从真正的第二回合第一张牌开始。
3. 拖出牌区右下角的小方块：出牌区整体放大/缩小，松手后重开游戏仍保留。
4. 长标签（如「打击 → 酸液史莱姆（中） 敌 27/33」）在方框内折行，不跑出屏幕、不压右侧数字。
## B34 计算时小怪有动作（✅ 513/0，门禁 S73）

### 现象（用户 m10269 第 1 条，逐字）
「刚刚打小怪计算的时候小怪有动作，看哪个函数调用小怪动画，改掉。」

### 根因
- `src\main\java\sts1solver\mirror\ClonePolicy.java:104` 对 `com.esotericsoftware.spine.*` 直接共享：沙盒世界的怪物克隆体与实机怪物用的是**同一个 `AnimationState` 对象**（骨架、SkeletonData、atlas 也一样共享，这是为了 fork 便宜）。
- 动画在渲染里推进，不在 update 里：`AbstractMonster.render()`（`build\gm2\...\monsters\AbstractMonster.java:706-722`）每帧 `state.update(delta)` + `state.apply(skeleton)` + `skeleton.updateWorldTransform()` + `draw`。
- 沙盒模拟怪物出招时调用 `state.setAnimation("attack", …)`，改的是共享的实机轨道 ⇒ 玩家在屏幕上看到真怪物开始演沙盒里的招式。沙盒自己从不 render，所以「动」的是实机。
- 动画时间是否参与判定：全仓 266 处 `state.getCurrent|getTime|addAnimation|setAnimation`，只有 `build\decomp6\...\monsters\exordium\SlaverRed.java:128` 的 `changeState()` 读时间，且只是把当前轨道时间搬到新轨道（纯表现）。

### 修法
- 新增 `src\main\java\sts1solver\mirror\AnimationMute.java`：按 `AnimationStateData` 缓存替身 `AnimationState`，`World.install()` 末尾对沙盒玩家与场上每只怪 `creature.state = stub`（`state` 是 public 字段，无需反射）。
- 骨架仍共享（fork 成本不变），替身只吸收 `setAnimation/addAnimation`；沙盒从不渲染，替身不会被 update/apply。
- 不需要在 uninstall 还原：替身只挂在沙盒克隆体上，实机 `state` 字段从未被写过。同数据造替身 ⇒ 动画名都存在，不会抛 `Animation not found`。

### 门禁 S73（八条）
`Probe.java`：seed=90、80 HP、Strike_R/Defend_R 对 JawWorm，记录实机怪物 `state` 与 track0，install 后断言沙盒有新 state、能在沙盒里 `setAnimation`、实机轨道未变，uninstall 后断言对象与轨道原样。
日志（2026-10-30 18:46:34）：
```
PASS the live monster really owns an animation state | com.esotericsoftware.spine.AnimationState
INFO   S73 animation: live=1738039140 sandbox=1644952850
PASS the sandbox owns its own monster | copy
PASS the sandbox monster gets its own animation state | live=1738039140 sandbox=1644952850
PASS the sandbox state knows the monster's animations | chomp
PASS the simulated animation really started inside the sandbox | chomp
PASS the live animation track was never touched by the search | before=idle after=idle
PASS the live monster keeps its animation state after the search | same object
PASS the live animation track is still the one the game had | idle
```

### 产物
- 套件：**passed=513 failed=0**（18:47:24，改前 505/0，新增 S73 八条）。
- 安装版 jar：543268 bytes / 2026-10-30 18:45:01 / sha256 `4B8C3729511B610ABA6D9241AECCC75300A749CD4D40CA389EE1440C176C48C4`。
- 覆盖（同 jar）：cards 370/342、potions 43/41、relics 190/189、monsters 66/66、characters 4/4（无退化）。
- 证据：`evidence\B34-ANIMATION-MUTE-20261030.txt`（6106 bytes，sha256 `28CBB9E6A8B5CAFEBE77A40890BEA651D5BB24C15136A82CF7ACFE5B7907BE94`）。

### 实机复测
1. 打一场多怪战（三只史莱姆之类），按 F8：计算过程中怪物保持原样，不再突然抬手/前冲。
2. 算完给计划后面板正常；点「自动出牌」时怪物按真实动作演。
3. 仍看到计算中动作：记怪物名 + 按键 + `solver.log` 里 `deep search: started` 附近 50 行。



## B35 计划属于旧回合：先手动结束回合，自动出牌就出错（✅ 521/0，门禁 S74）

用户（m10795）：「现在比如第一回合直接结束回合会导致自动出牌出bug」

### 现象
- 按 F8 或 Shift+F8 算出计划后，玩家自己先点游戏里的「结束回合」，再开「自动出牌」：
  第 1 回合的牌被拿到第 2 回合去打（能量、手牌、敌人意图都变了），打出来的是错牌。
- 若当前步正好是「结束回合」，它会把刚开始的第 2 回合直接结束掉，白挨一轮敌人攻击。

### 根因
`Gameplay.executeStepInternal()` 只检查 `hasPlan` / `pending` / `blockedReason`，从不比较
「这一步属于第几回合」与实机 `GameActionManager.turn`。玩家手动结束回合只让 `turn` 前进，
计划对象本身没有任何字段会失效，于是旧计划被原样执行。

语义细节：`Solver.Step.turn` 记的是「动作应用之后读到的回合」（`Solver.java:874`），
所以 endTurn 那一步的 `turn` 已经是新回合，其余动作的 `turn` 才是它所属的回合。因此判断归属要换算：

```
turnOfStep(step) = step.action.isEndTurn() ? step.turn - 1 : step.turn
stale  <=>  turnOfStep(plan.plan.get(step)) < GameActionManager.turn
```

### 修法（只动 Gameplay.java；Solver/Driver/判定逻辑一行未改）
| 改动 | 内容 |
| --- | --- |
| `staleReplans` 字段 | `reset()` 归零，`replanFromCurrentState` 与深搜结束处也归零 |
| `turnOfStep(Step)` | 上面的换算 |
| `planIsStale()` | 无计划返回 false；异常也返回 false（绝不因此停手） |
| `dropStalePlan()` | 过期则 `Log.info("plan stale: step N belongs to turn X, live turn Y; dropping it")`，置 `plan=null; step=0; stepTarget=0;` |
| `executeStepInternal()` | 在 `blockedReason` 之前调用 `dropStalePlan()`：过期则 status=「计划已过期（已经进入新回合）；按 F8 重算」并返回 false（拒绝执行，绝不猜） |
| `maybeAutopilot()` | 开头调用 `dropStalePlan()`，过期则走原有的 `replanFromCurrentState()`；连续 5 次（`staleReplans > 5`）停手并提示按 F8 |

### 门禁 S74（八条，全 PASS）
场景：Synthetic seed=23 / 80 HP / Strike_R x3 + Defend_R x2 vs JawWorm，走真实 F8 路径
（`Gameplay.startQuickSolve()` + `advanceQuickForTest(5ms)` 切片）。

```
21:55:29.518 PASS the fight starts with a plan for the current turn |
21:55:29.518 PASS a plan computed on the live turn is not stale |
21:55:29.519 PASS ending the turn by hand makes the old plan stale | firstTurn=1
21:55:29.519 INFO plan stale: step 1 belongs to turn 1, live turn 2; dropping it
21:55:29.519 PASS a stale step is refused instead of played on the new turn |
21:55:29.519 PASS the stale plan is dropped |
21:55:29.519 PASS the panel explains why it refused | STS1 Engine Solver  [计划已过期（已经进入新回合）；按 F8 重算] ...
21:55:30.884 PASS replanning produces a plan for the new turn | slices=260
21:55:30.884 INFO   S74 turns: before=1 after=2 slices=260
```

（「the new plan starts on the new turn」同批通过，after=2 即新计划从第 2 回合起。）

### 产物
- 代码：`src\main\java\sts1solver\Gameplay.java`（staleReplans / turnOfStep / planIsStale / dropStalePlan 与两处调用点）
- 门禁：`src\main\java\sts1solver\probe\Probe.java`（S74）
- 套件：**passed=521 failed=0**（21:57:05，改前 513/0，新增 S74 八条）
- 安装版 jar：545961 bytes / 2026-10-30 21:54:54 / sha256 `F77C36F00D14297F8F4665BC4D9273D10F880C427C2AC57621C3F8E8C6E1FCCA`
- 覆盖（同 jar，`-Mode coverage`）：cards 370/342（unplayable 28、prompt 0）、potions 43/41、relics 190/189（deprecated 1）、monsters 66/66、characters 4/4（无退化）
- 证据：`evidence\B35-STALE-PLAN-20261030.txt`（5249 bytes，sha256 `E1F2678436CDC9F0082E38ED5111D636744DC116F88F3DB3F0022BABF0B2FAA6`）

### 实机复测
1. 进战斗按 F8 算出计划（面板出现「第 N 回合 …」卡片）。
2. 不点「执行」，自己点「结束回合」，再开「自动出牌」：面板应提示「计划已过期（已经进入新回合）；按 F8 重算」，不会把第 1 回合的牌打到第 2 回合，也不会顺手结束新回合；随后自动重算并正常打。
3. 连续 5 次都落在旧回合时自动出牌会停下提示「请按 F8 重算」——这是兜底，不是新 bug。

看日志关键词：`plan stale: step`
## B36 / B37 新战斗残留上一场建议 + 计算时鼠标/人物异常（✅ 533/0，门禁 S75 / S76）

用户 m10933：「1.新一场战斗开局不能残余上一场战斗的出牌建议。2.计算时鼠标在卡动，人物会变绿，查看鼠标相关，人物动画相关函数，防止其被我们沙箱错误调用」

### B36：新一场战斗还显示上一场的出牌建议

**现象**：打完一场、进入新的一场时，面板仍列着上一场的「第 1 回合　防御」这类药丸。

**根因**：面板的路线条来自 `Gameplay.route()`，而 `route.clear()` 写在 `Gameplay.buildRoute()` 里；
`Gameplay.lines()` 在 `plan == null` 时**早退**（直接 `rows.add("F8 求解本场…"); return rows;`），根本不调 `buildRoute()`。
`ui/Panel.drawPlanText` 又无条件取 `Gameplay.route()` 画药丸 → 上一场的行一直留在列表里。

**修法（两层）**：
| 位置 | 改动 |
|---|---|
| `Gameplay.lines()` 的 `plan == null` 分支 | 早退前 `route.clear(); rowSteps.clear();` |
## B51 自动出牌 ≠ 「计算」：两条求解路径（✅ 647/0）

**用户 m15336-2（原文）**：「自动出牌改成相当于先按『计算』再按现在的自动出牌。因为我发现先计算再按自动出牌不会有 bug。可能是自动出牌现在的计算有问题。」

**旧行为（代码事实）**
- 面板「计算」/F8 → `Gameplay.startQuickSolve()` → `search/QuickSession`：预算 = settings.txt `deepSeconds`/`deepNodes`（0 = 不限），每帧 40ms 分帧，采纳后写入 `PlanCache`。
- 面板「自动」/F10 **在没有计划时** → `Gameplay.toggleAutopilot()` → `Gameplay.solveLive()`：
  - 同步跑在渲染线程那一帧（整帧卡住）；
  - 预算来自硬编码阶梯 `bossRoom?8000:(>=5?12000:(>=3?8000:(>=2?5000:3000)))` ms，**看不见 settings**；
  - 不分帧、不走 P2-2d 的 Boss 窄深；
  - 计划走尽时再走第三套路径 `replanFromCurrentState()`。
→ 同一个按钮两种行为，正是「先计算再自动就没 bug」的来源。

**修法**
- 新增 `autoplayArmed`：没有计划时按「自动」= `startQuickSolve(true)`；`finishQuick()` 采纳后 `beginAutoplay()`。
- 计划走尽 → `solveAgainForAutoplay()`（同一条分帧路径），日志 `autopilot: plan exhausted, solving again exactly like 计算 (attempt N)`。
- 尽力线（survival）不自动播出，提示「这是尽力线，再按一次『自动』执行」；`exhaustedReplans >= 2` 仍停机，避免必死局面反复满预算重算。
- 计算中按「自动」= 取消 / 重新武装，面板标签「自动: 算完开始」。

**门禁 S93**：`S93 arm: waited=2ms searching=true armed=true autopilot=false` → `S93 adopt: plan=true autopilot=true armed=false slices=30`。

## B52 快速 SL 卡顿取证 + 读档残留状态（🟡 647/0）

**用户 m15336-3（原文）**：「我快速 sl（保存再加载存档）总是很卡，之前没有这种情况现在有了，而且 sl 按道理和我们 mod 没关系不应该因为我们 mod 卡住。我需要你看是不是哪里加了没必要的时间限制或者哪里有问题。」

**取证**
1. 生产代码没有任何 `Thread.sleep`/阻塞等待（只有探针与看门狗线程有）；唯一「时限」是 `pendingDeadline = now + 20000L`，超时只写日志、不阻塞、不丢弃。
2. 两份 hang dump 都不在我方循环：
   - `hang-20261031-145343.txt`：`no frame for 4242 ms`，栈 `CharStat.<init>` ← `Ironclad.<init>` ← `CharacterManager.recreateCharacter`（游戏读角色统计）。
   - `hang-20261031-145539.txt`：`no frame for 4037 ms`，栈 `OpenALAudio.obtainSource` ← `OpenALSound.play` ← `DamageAction.update` ← `Driver.drain`（真引擎分配音效源）。
   → 这台机器本身有秒级停顿。
3. **真问题**：`plan/pending/autopilot/autoplayArmed/nextActionAtMs` 是 mod 的 static；快速 SL 调 `AbstractDungeon.reset()` 重建整棵地牢树，旧状态仍在 → 读档后可能拿存档前的计划继续出牌，或立刻触发一次满预算重算。
4. 次要：`Log.write()` 每行都调 `LogFiles.logsDir(new File(dir()))` 做目录枚举。

**修法**
- `Gameplay.detectWorldSwitch()`：只在空闲帧比对 `AbstractDungeon.player`/`currMapNode`/`CardCrawlGame.mode` 身份，变化才清 solver 状态（计划/自动/武装/失败计数/漂移计数等），日志 `world switched (save/load or new dungeon): solver state cleared`，`worldSwitchCount()` 可观测。
- `SolverMod.receivePostUpdate` 给 `Panel.update + Gameplay.tick` 计时，>25ms 且距上次 >1s 记 `slow frame: our hook took Nms (panel+tick) | …`。
- `Log` 缓存 archives 目录（不再每行枚举）。

**门禁 S94**：`S94 switch: before=0 after=1 hadPlan=true plan=false autopilot=false armed=false`。

**诚实边界**：外部停顿（读盘/音频）不是 mod 能修的；SL 若仍卡，先看日志有无 `slow frame` 行——有则我方仍有份，无则不在 `receivePostUpdate` 里。


## B53 `Solver.Result.potionsUsed` 恒为 0（药水既不进面板、也不参与路线采纳）

- 谁报的：自查，与 P2-19「药水要确定能用」（用户 m15881-2）同一批改动。
- 症状：面板与计划文本里的「用药 N 瓶」永远是 0；两条候选路线一条喝了三瓶、一条没喝，最终
  采纳时按「都没喝」比较；`Result.summary()` 输出 `potions=0` 即使计划里确实有药水步。
- 根因：`search/Solver.java` 里 `Result` 的**两个**构造点（`Solver.java:936` 的 portfolio 引擎、
  `:2016` 的 `Session` 引擎）都把 `potionsUsed` 实参写死成 `0`。搜索内部的 beam 打分是准的
  （`Solver.score` 用 `startPotionHp - choice.potionHp`），但最终结果对象回到 0，于是
  `Solver.better()` / `compareRoutes()` 比较两条线时药水代价消失，面板也永远显示 0 瓶。
- 修法：`Result` 不再接受调用方传入瓶数，改为在构造函数里自己遍历 `plan`：
  `"potion".equals(step.action.kind)` 计数，并用 `PotionBook.valueForLabel(step.action.label)`
  累加 HP 值，新增 `public final int potionHpSpent`；`summary()` 输出 `potions=… potionHp=…`；
  `better()` 传 `potionHpSpent` 给 `compareRoutes`（后者不再 ×10 换算）。
- 门禁：**S97**（纯格挡牌组的战斗只能靠药水取胜）：
  ```
  S97 solve: win splits=0 deepest=32 hpLoss=0 potions=1 potionHp=9 saves=0 complete=true turns=1 nodes=2982 expansions=1075 789ms reason=win layers=32 maxBranch=3 deferred=19 promoted=6
  ```
  三条断言：`potionsUsed>=1`、`potionHpSpent>=PotionBook.STANDARD_HP`、`summary()` 含 `potionHp=`。
- 同类隐患一起堵掉：S95/S96 证明 `Legal` 不再枚举游戏拒绝的药水（Fairy in a Bottle、
  回合结束后的 Blood/Fruit/Entropic、首领战的 SmokeBomb），`Driver` 与实机提交分支也会拒绝。
- 诚实边界：药水仍按**静态档位**（9/14/18 HP）计价，没有跑「同场不喝药」的对照基线，
  也就是不知道这瓶药在这场实际救了多少血——已登记为 P2-21b。


## B55 计算完不冻结（过几回合又算一次）+ 开局误报「没算出结果」

- 谁报的：用户 m16202-2 与 m16202-3（逐字）：「计算完毕之后应该冻结路线不再计算，自动出牌遇到和预计不一样的情况直接停止自动出牌」；「查清为什么打哨卫自动出牌过几个回合会又计算，还有为什么一开始算不出路线说是没有算出结果（后面正常了）」。
- 现象一（又算一次）：计划走完之后自动出牌自己又起一次搜索；回合对不上时也自己重排。面板上表现为「过几个回合忽然开始算」，用户看不出这是 mod 自己干的。
- 根因一：自动出牌留了三条自行重算的路 —— ① `maybeAutopilot` 计划走尽 → `solveAgainForAutoplay()`（`startQuickSolve(true)`，限额 2 次）；② 回合序号不对 → `staleReplans`；③ `pollPending` 的 `PREDICTION DRIFT` → `driftReplans < 3` 时 `replanFromCurrentState()`。三条都属于「替用户做主」，与 m16202-2 的「冻结」要求相反。
- 现象二（开局说没算出结果）：第一次按「计算」说计算期间局面变了、结果不采用；再按一次就正常。
- 根因二：`finishQuick()`（快搜收尾）与深度搜索收尾用的是**全量** `Digest` 比对，而 digest 里带 `ph=`（相位）/`q=`（队列计数）/`use=`/`end=` 这些**一帧就会变**的字段。B19 已经把「漂移判别」换成 `Digest.stable`（忽略相位/队列计数），但这两处收尾没跟着换，于是正常的瞬态变化被当成「计算期间局面已变化，结果已丢弃」，用户看到的就是「没算出结果」。
- 修法：① `maybeAutopilot` 的走尽分支与回合过期分支一律 `stopAutopilot(...)` 并提示「点『计算』重算」；`pollPending` 的漂移分支删掉自动 replan，同样停手（停止理由里带第几步）。`solveAgainForAutoplay` / `replanFromCurrentState` 的自动调用点全部移除，函数本身留给手动路径。② 两处收尾改比 `Digest.stable(before)` vs `Digest.stable(after)`；仍不同才丢弃，并打 `firstDiff@N expected[...] actual[...]`；只差瞬态时打一行「只有瞬态字段变化，结论仍然有效」（`Log.info`）。
- 门禁 S99：给一个计划、开自动出牌、清掉计划 → `tick()` 之后必须 `autopilot=false` 且没有任何搜索在跑。实测 `S99 exhausted: hadPlan=true autopilotAfter=false searching=false armed=false`。
- 诚实边界：冻结是「不自动重算」，不是「不允许重算」；F8/「计算」/「加深」仍然是满预算的手动入口。


## B56 液态记忆喝下后不选牌（药水路径的 drain 用了 20 倍帧长）

- 谁报的：用户 m16202-5（逐字）：「药水液态记忆使用后自动出牌不能正常从弃牌堆选牌。（不要再把自动出牌的逻辑改错了，不要动计算这里，只要加选牌的动作）」。
- 现象：喝「液态记忆」后弃牌堆选牌屏打开，自动出牌没有选，那张牌不回手。
- 根因：`Driver.usePotion(slot, target)` 只在 `potion.use(target)` 外面包了 `simDelta = SELECTION_DELTA (0.05)`，随后的 `drain()` 用的是 `FAST_DELTA = 20`。`BetterDiscardPileToHandAction.update()` 在自己开屏的那一次 update 里就执行 `gridSelectScreen.open(...) + tickDuration()`，20 的帧长直接把 duration 打成负数 → `isDone` → 动作出队，屏还开着但**已经没有任何动作**会来消费这次选择，于是选完也没人取牌。（与 B43 的出牌路径是同一个坑：那次只修了「打牌」，药水路径漏了。）
- 修法：整段 `usePotion`（含 `drain()`）都用 `SELECTION_DELTA`（0.05 帧），与出牌路径一致；同时复用 `orphanSelection()` 检测「屏开了但动作已死」并计入 `orphanSelections`。另外 `Gameplay` 新增 `pendingChoice` 潜伏应答：动作开屏比我们提交晚一帧时，下一次 tick 仍能按计划里的选择回答（`answerLatentSelection()`），每个屏幕签名只记一次日志。
- 门禁 S98：`captureLive` → install → 手牌全进弃牌堆并补到至少 3 张 → 弃牌堆顶放 `Strike_R` → 槽 0 换成 `LiquidMemories` → `Driver.usePotion(0,-1)`。实测 `S98 ok opened=true consumed=true moved=true inHand=true discard=6->5 hand=0->1`（修前 `consumed=false discard=6->6 hand=0->0`）。
- 附带：搜索侧同样受益（沙盒里喝液态记忆现在也能把牌带回手），门禁 S97 的药水胜线仍然通过。

## B57 重复怪物没有标号（P2-26；677/0）

- 现象（用户 m16717-2）：三只哨卫时面板与计划行都只写「打击 -> 哨卫」，看不出打的是哪一只。
- 做法（学 STS2 src/Runtime/SolverDisplayNames.cs 与游戏自己的提示）：同名敌人按从左到右编号，写成「哨卫（左起2）」。新增 search/MonsterLabels：battle() 取整场（含已死者，所以兄弟死不跳号）；of(m,battle) 只在同名数 >1 时加编号；xOf 用 hb.cX，退化用 drawX。编号只是显示，Action 仍靠 targetIndex 定位，绝不把名字解析回目标。
- 接线：Legal 的卡行与药水行都走 MonsterLabels（同一规则），Action.targetName 就是面板/日志里那个名字。
- 连带 bug：第一次跑套件就红 —— MechanicProbe.targetsAlive 拿 action.targetName（带编号）去和 Step.monsterNames（裸 m.name）精确相等。修法：Solver.monsterNames() 也走 MonsterLabels.of，同一帧两边同规则。
- 门禁 S101：三哨卫 labels=[哨卫（左起1）, 哨卫（左起2）, 哨卫（左起3）] distinct=3；计划行 targets=[0,1,2] labels=[打击 -> 哨卫（左起1）, …（左起2）, …（左起3）]；单只怪不加编号（a lone monster keeps its plain name）；混合战只给同名者编号（jaw=大颚虫 left=哨卫（左起1） right=哨卫（左起2））。S21 恢复 targetsAlive=true。

## B58 面板文字缓存只增不减（P2-27 / P2-27 的 ③；687/0）

- 现象：没有可见症状，纯粹是常驻内存只涨不落。用户这轮问的是卡顿，顺手把缓存整理掉。
- 根因：`Panel.measure()` 每遇到一个新文本就 `widthCache.put`，且从不清；`wrapCache` 有 32/64 条上限，`widthCache` 漏了。
- 修法：`measure()` 在 4000 条时 clear；`Panel.clearCaches()`（宽/换行/布局三个缓存）在 `Gameplay.reset()` 与 `detectWorldSwitch()` 的换局分支调用。
- 门禁：S103 里 `clearCaches()` 后必须只多一次 rebuild（builds=3）。
## B59 计算 3 哨卫那一局的三个症状：花屏 / 动画变快 / 15 秒卡顿（✅ 708/0）

用户原话（m17185）：「1.计算3哨卫时屏幕花了。2.计算完所有敌人和角色动画都变快，异常的卡顿。3.与以前的怪相比这次是多怪而且是多个同样的怪，怪还能塞晕眩，你看是这几个原因吗，找原因给方案」

用户猜的三条（多怪 / 同名怪 / 塞晕眩）是放大器不是根因：同名怪让分支从 4 条变 12 条（3 个目标 × 牌），每节点克隆 3 只怪 + buff 更贵；Dazed 是哨卫 Bolt 的行为。

### 根因一：花屏
沙盒渲染会改写静态颜色（`AbstractCard`/`AbstractBlight` 上的 `*_COLOR` 等），旧代码只在整轮搜索结束后统一修，15 秒窗口里一直带着改动渲染 → 屏幕花。
修法：`mirror/RenderState.StaticColors` + `captureStaticColorsOnly()`/`diffStatics()`/`repairStatics()`；`Gameplay.repairStaticsDuringSearch()` 每个切片结束后只比/修这 136 个静态颜色，日志形如 `static AbstractBlight.PASSIVE_OUTLINE_COLOR: (0,0,0,0.33) -> (0.25,0,0,0.33)`。

### 根因二：动画变快
`Driver` 借用 `Gdx.graphics.getDeltaTime`（返回 FAST_DELTA=20 / SELECTION_DELTA=0.05）时靠计数抵消嵌套，但 `Solver.stateAfter` 与 `Solver.finishLine` 借了不还 —— 计数永远回不到 0，整局之后每帧 delta 变成 0.02s，所有动画都被按快进播。
修法：`stateAfter` 主体抽成 `Solver.walkTo(...)` 外层 try/finally `Driver.end()`；`finishLine` 的 finally 末尾补 `Driver.end()`；`Driver.borrowedAt` 记外层 begin 的栈，`verifyReleased(where)` 点名借出点并计 `frameDeltaLeaks`；`Gameplay.tick` 开头若是干净帧则结尾统一 `Driver.verifyReleased("the end of a frame")`。

### 根因三：15 秒卡顿
预算默认不限（P2-12）+ 无活分支 → 4186 节点 + 2000 条延后候选全部跑完；每帧只让出 40ms 给渲染（约 22fps），第一切片 493ms；收尾 2.5–3GB 堆回收（gc=44/4280ms）。
修法：停车（延后队列）阶段加时间上限 `deferredPhaseMs`（默认 6000ms，`-Dsts1solver.deferredPhaseMs` 可调），到时封顶并记日志，面板写「已缩短停车候选阶段」，点「加深」还能继续；堆守卫从抖动改成滞回（`GUARD_RELEASE_PCT=65`，latch 后要降到 65% 才释放）。

### 新增取证（只读）
`ui/FrameStats`（帧直方图 p50/p95/max/slow33 + GC 次数与累计耗时 + 堆 used/max，搜索前后各一行）；`mirror/SkeletonFingerprint`（每个生物一条 spine 指纹，搜索前后对比点名）。

### 诚实边界
花屏与动画变快只能实机肉眼确认，门禁只证明不变量（S104 帧步长归还、S105 静态颜色往返、S106 帧报告有数、S107 指纹能点名、S108 守卫滞回）。6 秒停车上限是工程取舍。

---

## B60 回合没结算完按「计算」，按键被丢掉（✅ 708/0）

现象：出牌/回合还在结算时按面板「计算」，按钮看起来没反应，得再按一次。
根因：`Gameplay.startQuickSolve` 的 blocked 分支只写一行 `solve refused: engine busy` 就 return，按键被丢弃。
修法：`solveQueued` 标记 + `Gameplay.startQueuedSolve()` 在 `tick` 里（`answerLatentSelection` 之后）等引擎结算完成的第一帧自动开始那次搜索；状态行显示「等这一拍结算完就开始计算…」；`reset()`/换局/停止 清标记。门禁随 `-Full` 完整套件；核心套件里 S74 已验证「手动结束回合后按计算能出计划」（不再依赖引擎刚好结算完）。

```
AUTOTEST RESULT passed=133 failed=0 (core gates only, 104 extended gate(s) skipped)   build/run-core-e.log
AUTOTEST RESULT passed=708 failed=0                                                  build/run-b59e.log
```
## B61 药水喝完不消失（+ engineSettled 早一帧）—— P2-33，核心 137/0

**用户（m17821）**：「有个小问题，药水自动打出的时候有效果但是没消耗药水。刚刚的棘刺药水就这样。
改成按照真实的出药水消耗的行为。sts2怎么做的可以看」

**根因**：我们提交药水只调了 `potion.use(target)`。真游戏点一瓶药水是四件事
（`build/decomp6/.../ui/panels/PotionPopUp.java:192-210,225-242`）：
①`metricData.potions_floor_usage.add(floorNum)` ②`potion.use(target)` ③遍历 `player.relics`
调 `r.onUsePotion()` ④`AbstractDungeon.topPanel.destroyPotion(slot)`（内部就是
`player.potions.set(slot, new PotionSlot(slot))`，`TopPanel.java:517-519`）。
少了 ③④ → 效果有了、瓶子还在；搜索里也少算 Toy Ornithopter 的 `onUsePotion()`
（`relics/ToyOrnithopter.java:26-35`：`addToBot(RelicAboveCreatureAction)` + `addToBot(HealAction(player,player,5))`）。

**修法**：
- 新 `drive/PotionDrink.drink(slot, potion, target, live)` 按 ①→④ 执行；①只在 live 时做
  （沙盒不得写玩家统计）；④失败退回 `player.potions.set(clear, new PotionSlot(clear))`。
- `drive/Driver.usePotion`（沙盒）与 `Gameplay.submitPotion`（实机）都改走它。
- `probe/ActionAudit.tryPotion` 增加「瓶位必须变成 PotionSlot」判定，否则记 `not-consumed` 并算失败。

**连带真 bug（engineSettled 早一帧）**：`actions/GameActionManager.java:140-166` 的 `update()`
在 EXECUTING_ACTIONS 里先把当前动作跑完，再把下一个从 `actions` 搬进 `currentAction`，
执行发生在**下一帧**。所以 `actions.isEmpty()` 会早一帧为真。绿门禁时表现为
「遗物回血读不到」，实机上表现为自动出牌可能赶在最后一个动作之前出牌。
修法：`Gameplay.engineSettled()` 追加
`if (gameActionManager.currentAction != null && !gameActionManager.currentAction.isDone) return false;`

**门禁 S109（核心）**：seed=9091、maxHealth=80、deck Strike_R×2/Defend_R×2/Bash、JawWorm、
relics {Burning Blood, Toy Ornithopter}，玩家 HP 置 60；同一瓶 FirePotion 分别在沙盒与实机喝，
实机泵到引擎空闲。断言 consumed / cloneConsumed / refusedAgain / relic / sameDelta。

**实测（build/run-s109o.log，23:56）**
```
S109 potion: ok live=true pot=FirePotion target=JawWorm/44
  actAfterUse=3(DamageAction(20),RelicAboveCreatureAction(0),HealAction(5)) slices=34
  relics=[BurningBlood,ToyOrnithopter] consumed=true cloneConsumed=true refusedAgain=true
  relic=true sameDelta=true hp=60->65 cloneHp=60->65 enemy=44->24 cloneEnemy=44->24
AUTOTEST RESULT passed=137 failed=0 (core gates only, 104 extended gate(s) skipped)
```

**诚实边界**：沙盒故意不写 `metricData.potions_floor_usage`；若有人禁用 TopPanel，④会退回直接 set。

## B62 遗物 id 写错静默变 Circlet —— 核心 137/0

`build/decomp6/.../helpers/RelicLibrary.java:601-617` 的 `getRelic(key)` 永不返回 null，未知 key
直接 `new Circlet()`。门禁 spec 里写 `"ToyOrnithopter"`（真实 ID 是带空格的 `"Toy Ornithopter"`）
拿到的是 Circlet，于是「遗物没生效」有一半是门禁自己的错。
修法：`scenario/Synthetic.java` 取到遗物后 `RelicLibrary.isARelic(id)` 检查并 warn；门禁改正确 id。

## B63 已经搜到胜利，却被「超时」改写成最深的必死线 —— 核心 137/0 / 完整 712/0

**症状**：修完 B61 之后完整套件 S59 两条失败 ——
`portfolio=78 single=0`：同一战斗同一枚 jar，单档搜索「不掉血两步打赢」，
多档合并搜索却给「掉 78 血、56 步全是结束回合」。

**根因（两条叠加）**：
1. `Solver.solveInternal` 的层循环搜到胜利只写 `string2 = "win"` 却继续深化（A0），
   预算到期时 `string2 = "time"` 把 "win" 覆盖，胜利事实只剩 `node2.win`。
2. 收尾兜底 `if (!"win".equals(string2) && arrayList.size() > node2.path.size())` 因此以为没赢，
   把最深的必死线当答案返回，而 `Result.win` 仍取 `node2.win=true` → 「赢了却掉 78 血」。
   `Session.finish()` 的 `!"win".equals(this.reason)` 同一个洞。

**为什么现在才炸**：B61 让 `Driver.isIdle` 把「停靠在 currentAction 里的那个动作」也算作不空闲，
每节点多跑一拍（631 → 2600+ 节点），2100ms 档第一次出现「搜到胜利后未深化完就超时」。
bisect 确认：注释掉 isIdle 的停靠判据即恢复 hpLoss=0 / nodes=631。

**修法**：`solveInternal` 收尾前若 `node2.win` 为真就把 reason 改回 "win" 并记
`search: a win was already found when the search stopped (<reason>)`；`Session.finish` 的最深线兜底加
`&& !this.best.win`。判胜负只看 `node2.win`/`best.win`，reason 只是标签。无新增门禁（S59 就是判据）。

**实测**
```
S59 single:    win=true hpLoss=0 proven=true nodes=106
S59 portfolio: win=true hpLoss=0 proven=true nodes=2605
核心: AUTOTEST RESULT passed=137 failed=0 (core gates only, 104 extended gate(s) skipped)
完整: AUTOTEST RESULT passed=712 failed=0                     build/run-full-i.log
```
安装版 jar 698722 bytes / sha256 `261C2A0AEF6D6DEDCC5BE8F246F906A7E92E37DD98534B933E0F16B80BCE9EA8`。
证据 `evidence/B63-WIN-REASON-20261101.txt`。

**诚实边界**：节点数 631 → 2605 是 B61 的直接代价（每节点必须真跑到空闲）；
确实没有胜利线时仍按 B43/B44 口径给最长生存线。

## B64 0 血之后被「鸟面瓮」治活 —— 死亡只看 HP 是个陷阱（✅ 核心 140/0，门禁 S110）

**用户（m18618）**：「模拟里 0 血之后靠鸟面瓮回血「复活」了，实际应该 0 血就死」

**引擎事实（决定性）**：`characters/AbstractPlayer.java:1452-1484`，受伤后 HP<1 时
若没有 FairyPotion / Lizard Tail，就 `this.isDead = true; AbstractDungeon.deathScreen = new DeathScreen(...);
this.currentHealth = 0;` —— **它从不设 `isDying`**。而 `core/AbstractCreature.java:375-381` 的 `heal()`
只在 `isDying` 时提前返回。于是引擎自己真的会给「尸体」回血：血条数字能从 0 涨回去，
但 `isDead` 与 DeathScreen 已经证明这一局结束了。

我方 bug：搜索里 `choice.dead = hp <= 0` 只在读数的**那一瞬**成立。鸟面瓮（`relics/BirdFacedUrn.java:28-34`，
每打出一张能力牌 `HealAction(player,2)`）在同一个结算窗口里把 HP 拉回 >0，下一次读状态就是「活着」，
死分支被继续展开/重放 —— 面板上就出现 0 血后靠回血继续打的线。

**修法（一处判据，四个调用点）**：
1. `drive/Driver.playerDead()`：`player == null ? false : p.isDead || p.currentHealth <= 0 || AbstractDungeon.deathScreen != null`。
2. `Solver.readChoice`：`choice.dead = Driver.playerDead() || choice.hp <= 0;`，`choice.win = !choice.dead && monstersDead()`。
3. `Driver.combatOver()` 与 `turnTransitionFinished()` 改用 `playerDead()`。
4. `Solver.walkTo` 每应用一个动作后 `if (Driver.playerDead()) break;`（重放不再把尸体带过回合）。

**门禁 S110（核心，一条）**：合成战斗 → 沙盒里把玩家 `currentHealth=0; isDead=true` → `heal(7)`。
断言：血真的涨（`healLanded`，这就是陷阱本身）、`playerDead()` 仍为真（`deadWhilePositive`）、`combatOver()` 为真。
```
S110 death: hpAfterHeal=7 healLanded=true deadWhilePositive=true over=true
AUTOTEST RESULT passed=140 failed=0 (core gates only, 104 extended gate(s) skipped)   build/run-b64.log
AUTOTEST RESULT passed=715 failed=0                                              build/run-b64full.log
```
安装版 jar 700293 bytes / sha256 `359CD20E32694B9D23E0A395DF04DBB6B74FCBCD9C87CA32B19B0C9C56EF1F6A`。

**为什么这条对「创造性AI + 鸟面瓮蓄血」特别重要**：那种线的全部价值就是「掉到很低再治回来」，
而它最容易跨过 0 血；死亡判据一错，搜索就会把「先死一次再复活」当成合法策略。
## B65 模拟期的声音漏出来（用户 m18796-2；核心 152/0，门禁 S111）

**用户原话**：「现在计算模拟的时候偶尔还是有背景声音多次出现（间隔很短应该是模拟的时候把背景或者音效没有摘出来导致的，重点看音效）。」

**为什么音量归零还不够**
- 音量确实只在 `Driver.begin()`/`end()` 里被置 0（`Settings.SOUND_VOLUME` / `MASTER_VOLUME`），而且 `Statics.isAudioOrEnginePlumbing` 不镜像任何音量字段，所以沙盒不会把音量写回实机。
- 但 `SoundMaster` 有一张共享的私有表 `map(key -> Sfx)` 和一个 `fadeOutList`。
- 模拟里打死 Boss / 换场景会走 `AbstractScene.fadeOutAmbiance()`（AbstractScene.java:89-95）→ `CardCrawlGame.sound.fadeOut(key,id)` → **往共享的 fadeOutList 里塞一条淡出记录**。
  窗口一关，实机的 `SoundMaster.update()`（:407-421）每帧用**实机音量**重设那条记录的 Sfx 音量 → 背景音短促地响一下又淡掉，反复多次。这就是用户听到的声音。
- 次要路径：`Sfx.play/loop` 懒加载 `Gdx.audio.newSound`（OpenAL source），沙盒里第一次播某个 key 会在节点内做驱动/磁盘 IO。

**修法**
- 新 `src/main/java/sts1solver/mirror/SfxMute.java`：
  - 窗口内把 `SoundMaster.map` 换成 `Inert`（`containsKey` 恒 true、`get` 恒返回 `Silent`），`fadeOutList` 换成 `FadeSink`（`add` 只计数不保存）。
  - `Silent extends Sfx`：`play/loop` 返回 0 并计数，`setVolume/stop` 空实现 —— 既不出声，也不加载 `Gdx.audio`。
  - 窗口结束把原表写回；`World.install()` 开头 `SfxMute.heal()` 兜底关掉上次没关的窗口。
- `World.install` 里 `SfxMute.begin()`（在 `MusicMute.begin(true)` 之后），`uninstall` 里 `SfxMute.end()`（在 statics 还原之前）。

**门禁 S111（core）**
```
S111 sfx: active=true play=0 loop=0 played=2 dropped=1 fadesBefore=0 fadeAfter=0 mapSize=368 mapLive=true stillMuted=false
```
五条断言：窗口换表 / 沙盒播不出声 / 模拟产生的淡出被丢弃 / 窗口后没有任何 fade 残留 / 真表恢复。

**诚实边界**：已经加载进实机 Sfx 表的循环（战斗 BGM 由 `MusicMute` 管）不受影响；本修法针对「沙盒期间新产生的播放与淡出」。

## P2-35 局外收益按来源计价（用户 m18796-1；核心 152/0，门禁 S112）

**旧实现的问题**：`GrowthCredit` 只有一个「每次固定 credit」，默认 0（关），而且只在 `choice.win` 时加分。
遗传算法刷了几次、献祭涨了多少最大生命、贪婪之手拿了多少金币 —— 全都不进搜索。

**STS2 的口径**（`C:/Users/YOKI/Desktop/CombatSolver-main`）
- `src/Search/GrowthPolicy.cs`：每个来源一个整数 HP 额度（`GrowthValues`，overlay 默认 Feed 3 / GeneticAlgorithm 2），
  `Credit() = Σ 额度 × 已实现次数`，`StateEvaluation.cs:196-197` 里 `score += growthHpCredit * hpWeight`。
- 金币真记账：`CorePowerSupport.cs:144-152` HandOfGreed 致命击杀 → `GainPlayerGold`。

**新实现**
- `src/main/java/sts1solver/search/GrowthBook.java`：六源，各一个 HP 额度
  （feed 默认 3 / geneticAlgorithm 默认 2 / theScythe / ritualDagger / lessonLearned / handOfGreed 默认 0）。
  额度写在 `settings.txt` 的 `growth.feed=3` 这类键，缺省用 DEFAULTS，上限 50 防手误。
- 已实现量按游戏事实取：Feed 取 `maxHealth` 实际增量（不是打出次数），Hand of Greed 取金币实际增量按 25 金 = 1 单位，
  其余按打出次数（`Action.cardId` 匹配）。非成长牌（Strike 等）不计。
- 打分：portfolio 与 Session 两个引擎都改成 `n20s = max(0, hpLoss - growthHp)` 再进 `Solver.score`，
  与药水消耗同一条口径；节点报告的 hpLoss 仍是真实掉血（门禁与复测不受影响）。
- 面板新增一行：`GrowthBook.describe()` → 「成长: 献祭(FEED) 3HP/最大生命 · 遗传算法 2HP/次 …」。

**门禁 S112（core）**
```
S112 growth: credit=25 zero=0 off=0 clamped=50 feed=3 genetic=2 order=feed,geneticAlgorithm,theScythe,ritualDagger,lessonLearned,handOfGreed described=true restored=true
```
credit=25 = 5 点最大生命(×3) + 50 金(2 单位 ×2) + 3 次遗传算法(×2)。

**诚实边界**：这是「排序/定价」层，不是判定层（`docs/JUDGMENT.md` R10 仍成立：胜负与掉血只由真引擎给）。
额度默认值直接抄 STS2 的 overlay 默认；改额度不需要重编，改 `settings.txt` 即可。


## B66 燃烧契约之后必定停：手牌选牌的「已取走」判据错了（P2-38；核心 174/0，完整 749/0）

### 用户报（m19362-2，逐字）
「我都说了自动出牌和执行一步这两个除非玩家操作了（打了牌喝药水什么的）不然不要重算，现在我要求自动打牌过了一定时间间隔再检测是否有错误。比如自动打出燃烧契约之后现在必定要重新计算，但实际上是没有差别的，只是因为现在检测太快导致（"选牌已应答，但等待中的动作没有取走这张牌"）要重算。」

### 根因（两层，第二层才是"必定"）
1. **判据错**：HAND 选牌屏被取走的信号不是「selectedCards 空」。
   `build/decomp6/com/megacrit/cardcrawl/actions/common/ExhaustAction.java:94` 打开 handCardSelectScreen；
   `:99-105` 在 `!wereCardsRetrieved` 时把 selectedCards 里的牌移进消耗堆，然后
   `AbstractDungeon.handCardSelectScreen.wereCardsRetrieved = true;`。
   `HandCardSelectScreen.java:616-618` 只有 `prep()`（下一次开屏）才 `selectedCards.clear()` 并重置标志。
   我方 `Driver.selectionConsumed()` 旧实现读 `selectedCards.group.isEmpty()` → 对 HAND 恒为 false。
2. **判得太早**：即使修好标志，第一帧就判也容易撞上"动作还没轮到"（引擎 self-report settled 早几帧）。

### 修法
- `src/main/java/sts1solver/drive/Driver.java`
  · `selectionConsumed()`：HAND 分支改读 `wereCardsRetrieved || selectedCards.group.isEmpty()`；GRID 分支不变。
  · `answerLiveSelection()`：未取走时记 `selectionSinceMs`、**不关屏**、返回 false，让帧循环再来一次；
    成功才 `closeCurrentScreen()` 并清 retry 状态。新增 `selectionRetrying()/selectionSince()/selectionAttempts()/clearSelectionRetry()`。
- `src/main/java/sts1solver/ui/AutoPace.java`：新增结算窗 `VERIFY_DEFAULT_MS=700`（`settings.txt` 的 `autoplayVerifyMs`，
  0–5000，缓存 500ms）与纯函数 `readyToJudge(now, actionAt, grace)`；帧循环只比时钟，不 sleep。
- `src/main/java/sts1solver/Gameplay.java`：新字段 `lastActionAtMs`（submit 的结束回合/出牌/喝药三条路径 + 答完选牌打点，
  reset 清 0）；`pollPending` 的选牌分支在窗内只写「选牌中：等这一步结算」并 return；
  漂移分支在 digest 稳定 8 帧后再加 `readyToJudge` 门槛，窗内只写「等待结算完成…」。

### 门禁 S115（core）
- 假时钟：`1000/1000/700` 未到、`1700/1000/700` 已到、`999/1000/700` 未到、`actionAt=0` 不等待、`verifyMs` 在范围内。
- 实机：合成战斗（seed 5151，JawWorm，手牌 燃烧契约+打击+防御）→ `Driver.play` → 打开 HAND 屏 →
  `Driver.resolveSelectionLive("Strike_R")`。
- 实测：`S115 hand pick: ok opened=true answered=true consumed=true exhausted=1 exhaustTop=Strike_R stillOpen=false retry=clear`

### 诚实边界
- 结算窗只推迟判断，不改变判定口径（R10：判定仍由真引擎给）；窗内不重算、不推进计划。
- 如果某个 mod 的选牌屏既不清 selectedCards 也不设 wereCardsRetrieved，会等满窗口后停下并提示重算（宁可停，不猜）。


## B67 面板调小后文字不缩小、不减少（P2-39；核心 191/0，完整 766/0）

### 用户报（m19536，逐字）
「面板调整大小学习sts2面板，现在面板调整大小调整小了显示的文字不会少导致很挤。改成调小减少显示的文字调大多显示点」

### 根因
- 路线条的几何全部按 `strip = Settings.scale * RouteSize.factor()` 算（药丸高 30*strip、行高、内边距、折行宽度）；
- 文字一直按字体的 1.0 尺寸绘制，折行却按 `measure(font, Settings.scale)` 测宽 —— 三个数各不相同，
  面板调小后框缩、字不缩，文字溢出药丸/贴边；
- 而且头部行、每回合的 HP/能量/格挡、药丸上的「敌 9/15」无论面板多小都照画，没有任何取舍。

### 修法
- 新 `src/main/java/sts1solver/ui/PanelDetail.java`（纯函数，可无窗口门禁）：
  `level(factor)` 三档（FULL ≥0.95 / MEDIUM ≥0.72 / COMPACT 以下）、`textScale(strip)`（夹在 0.62–1.30）、
  `rows(rows, level)` 生成显示副本（药丸顺序与 step 索引不变）、`headerLines(lines, level)`（2 / 5 / 全部）、
  `dropTargetHp` / `compactPill` / `compactTurn`。
- `Panel.drawPlanText`：detail 取 `RouteSize.factor()`（玩家拖的那个数），字号取 strip；
  布局测量与绘制用同一个 font scale，绘制完成后恢复；新增 `Panel.detailLevel()`。

### 门禁 S116（core）
阈值、三档取舍、字号上下限、压缩规则、行副本的 step 索引、头部行过滤，以及 `preview(0.6)` → COMPACT / `preview(1.0)` → FULL。
实测行：`PASS the compact pill keeps the card and which monster it hits | 打击→虱虫1`、
`PASS the strip's detail follows the size the player dragged it to | small=compact full=full`。

### 诚实边界
- 三档是离散取舍，不是逐像素连续变化；中间大小不会出现"半行"。
- 字号有 0.62 的下限，所以面板极小只减信息，不会继续缩到看不清。

## B68 面板「还有 N 条候选未展开（可再算）」是假的（P2-40；核心 194/0，完整 769/0）

### 用户质疑（m19708，逐字）
「这两条有问题吧，加深已经删除了，现在的计算就有加深，没道理有未展开的啊，又没到上限而且继续算也无所谓这几百条一秒算完了」

### 核查
- 没有入口会再展开它们：『加深』在 P2-10 面板精简时删除，现面板只有计算/停止/执行一步/自动/秒数/节点/节奏/收起/隐藏。
- deferred 是 beam 挤掉的候选，每次搜索只晋升 `MAX_PROMOTIONS=6` 条，每条要重放整条路线，不是零成本。
- 真 bug：`Solver.java` 的 Session 在「前沿跑干且已有胜线」时只 break、不置 `frontierDead`，
  于是 headline 说『预算内最好』（其实已搜完），frontierLeft 仍按剩余前沿算，叠加 parking lot 就成了 886。

### 修法
- `Result.cutShort = !frontierExhausted`（除前沿跑干外的退出方式都是限制）。
- Session 前沿跑干分支统一 `frontierDead = true`。
- `PlanText.openCandidates` 只在 `cutShort` 时返回 `frontierLeft`；新增 `PlanText.candidateNote(cutShort, open)`，
  收敛时返回空串，限制停下时返回『还有 N 条没展开（受层数/预算限制，可以再算）』。
- headline 的 win 分支按 `frontierExhausted` 说『已搜完可用路线』或『预算内最好』，候选尾只在限制停下时追加。

### 门禁
- S117（core）：收敛/零候选无尾，限制停下含数字与『没展开』。
- S63 断言改语义：`openCandidates(full) == (cutShort ? frontierLeft : 0)`。

### 诚实边界
- parking lot 仍在、仍在日志里；每次搜索最多晋升 6 条的上限没动。
- 若要把「继续算也无所谓」做成真的（有空多试几条停车场），那是改搜索行为，需要单独评估。

## P2-41 停车场按剩余预算花（不再写死 6 条；核心 201/0，完整 776/0）

### 用户要求
m19889「6条世界线是不是太少了，那样子8+6总共才算14世界线吗」→ m19892「改」。

### 旧行为
- `MAX_PROMOTIONS = 6` 是硬上限：无论还剩 300 秒还是 3 秒，收尾时最多重演 6 条被 beam 淘汰的候选。
- 预算充裕的对局里，这几百条停车场候选实际上是永远睡着的。

### 新规则
- `promotionLimit(remainingMs) = clamp(6, 60, remainingMs * 0.5 / max(costMs, 250))`。
  - 一半剩余预算给停车场，另一半留给「把整条线打完」。
  - `costMs` 是 `materialize`（从根重放整条路线）的实测滑动平均，权重 0.4；第一次用 150ms 猜。
  - 每条晋升至少按 250ms 的搜索预算计价 —— 实测重放只要 1–2ms，若照这个数放开跑，
    341ms 剩余会晋升 170 条、每条搜索只分到几毫秒，等于白跑。
- 两条晋升路径（阻塞 `solveInternal` 收尾块、分帧 `Session.promote`）都走同一个函数；
  Session 在本次搜索第一次晋升时定住 limit，避免测速抖动。
- 日志 `A3 deferred: kept=… promoted=… limit=… costMs=… samples=… budgetLeftMs=…`。

### 门禁
`S118`（core）：无预算=6、猜测成本下 60s=60、实测 1s 后 60s=30、薄预算 2s=6、钉子覆盖=3、reset 后恢复=60。

### 诚实边界
- 套件里的搜索都被预算/节点卡死，剩余预算多为 0，所以日志里 limit 多是 6 —— 这正是「没有富余就不花」。
- 真实对局面板默认不限预算，收敛时通常还剩大量时间，limit 会是 60。
- 未改 beam 宽度、打分、收敛判据；60 是硬上限。

## B69 面板重叠与字号随缩放（P2-42；相关门禁 S116+S119 21/0）

### 用户要求（m20027 逐字）
3.面板有问题，现在面板”第一回合“和第一个行动比如”防御“挡住，把行动往左边移动。
4.面板随大小调整显示内容理解意思错误，我说的是字体大小不变，减少内容。比如现在面板很小就只正常显示一行，每次下滚看一行这样的，面板很大就多显示几行。
（关于第 3 条的方向，我用选项问过，用户选了「药丸整体往右挪，留出「第 1 回合」完整宽度」）

### 根因
1. `RouteLayout.LABEL_W = 104`，但「第 1 回合」在游戏字体下实测更宽，且第一个药丸的 x = padX + labelW + gap，于是药丸左边缘盖在标签上（设计上就是“标签列永不被药丸遮住”，但列宽写死）。
2. P2-39 把字号绑在 strip 上（`PanelDetail.textScale`），且布局用 `strip`：面板调小 = 字变小，而不是少显示。

### 修法
- `RouteLayout.layout`：`labelW = max(LABEL_W*scale, measure(label)+LABEL_GAP*scale)`，`contentX = padX + labelW`（间距已含在列宽里）。
- `PanelDetail`：删 `textScale/TEXT_MIN/TEXT_MAX`，加 `visibleRows(factor)`（<0.72→1、<0.95→2、<1.30→3、<1.65→4、否则5）。
- `Panel`：`FIXED_TEXT_SCALE=1.0`；布局/测量用 `scale` 而不是 `strip`；可见高度 = visibleRows × `rowUnit(scale)`；滚轮与 `scrollRows` 改滚一个回合行。
- 门禁：S116 的 textScale 断言改为 visibleRows＋固定字号；新增核心 S119（标签列按实测宽度、药丸不压标签、短标签仍用标准列宽）。

### 实测
- `-Gates S116,S119`：`AUTOTEST RESULT passed=21 failed=0 (only [S116, S119] asked for, 141 gate(s) skipped)`（`build/run-p242.log`）。
- 安装 jar 739255 bytes / sha256 `587D811212A3F694018FEC01380012D72E21681A35072C42B8715BB9629C3FFB`。

### 诚实边界
- 只跑了 S116+S119（用户 m20027-1 要求不跑整套）；完整套件下一次交付前再跑。
- 字号固定后，很小的面板里药丸会比面板宽度大，靠换行解决（已有 B33 的折行机制）。
## B70 每次启动音量变 100% / 计算后主音量变 0（P2-43；只跑相关门禁 19/0）

**用户 m20138-2 原话**：「每次你启动sts的时候音量都会变成100%，先找原因我确认再改」
**用户 m20313-2 批准的修法**：「① MutedMusic 只构造一次（static final），不再每次安装都跑构造函数；② 任何 MusicMaster/SoundMaster 构造前后保存并还原三个音量静态量；③ 把玩家文件修回去（Master 恢复 1.0000002，补回 Music/Sound 键）；④ 设置界面开着时禁用 InputMute 的坐标改写，杜绝再把滑块拖走。」

### 两个根因
1. **每次沙盒安装都 new 一个 MusicMaster 子类**：`mirror/ExternalMute.java` 原先 `CardCrawlGame.music = new MutedMusic();`，而
   `build/decomp6/com/megacrit/cardcrawl/audio/MusicMaster.java:19-23` 的构造函数把
   `Settings.MASTER_VOLUME = Settings.soundPref.getFloat("Master Volume", 0.5f)`、
   `Settings.MUSIC_VOLUME = ...("Music Volume", 0.5f)` 重新读一遍并打日志。
   游戏日志 `SlayTheSpire.log` 在 06:16:12–06:16:14 刷了 9000+ 行 `Music Volume: 0.5` —— 构造函数被调用上万次。
   `util/SilentInstance.tick()` 只在每个渲染帧重置一次零，一次搜索在一帧里上千次装/卸，缝隙里就是文件里的音量。
2. **光标被推走后，设置界面的滑块跟着走**：全游戏只有 `screens/options/Slider.modifyVolume()` 写 `Master Volume`，
   `Slider.update()` 用 `InputHelper.mX` 拖手柄、松开时 `Prefs.flush()`。`mirror/InputMute` 在模拟期把 mX/mY 置 -100000，
   设置界面开着时就把主音量拖到最左并落盘 —— `preferences/STSSound` 只剩 `{"Master Volume":"0.0"}`。
   我方生产代码没有任何一处 `soundPref.putX/flush`（只有 Probe 读）。

### 修法
| 条 | 改动 |
| --- | --- |
| ① | `ExternalMute.mutedMusic()`：懒建一次并缓存，第二次安装复用同一对象；`musicBuilds()` 计数 |
| ② | `mutedMusic()` 的 try/finally 与 `begin()`（depth 0→1 快照 `savedMaster/savedMusic/savedSound`）/ `restoreAll()` 还原，窗口内任何构造都改不动音量；新增 `volumes()` |
| ③ | `preferences/STSSound` 先备份为 `.pre-mod-20261101.bak`，再写回 Ambience/Mute/Master 1.0000002 + Music 0.5 + Sound 0.5 |
| ④ | `InputMute.screenUp()`（`isScreenUp` 或 `screen != NONE`，异常按 true）+ push 跳过改写、记一行日志、`skippedForScreen()`；pop 只在真改过时还原 |

### 门禁与实测
`tools/run.ps1 -TimeoutSeconds 300 -Gates S120,S86,S87,S88,S111,S76` → **passed=19 failed=0**（141 个门禁跳过）
```
S120 audio: volumesKept=true built=1 buildsAdded=0 musicBack=true screenSkip=true noScreenMoved=true cursorBack=true volumes=[0.0,0.0,0.0]
S111 sfx: active=true play=0 loop=0 played=2 dropped=1 fadesBefore=0 fadeAfter=0 mapSize=368 mapLive=true stillMuted=false
external mute: built the silent mixer once for this process (its constructor re-reads the volume settings)
input mute: a screen is open, leaving the cursor alone (a slider would be dragged to the edge)
```
装配件：`mod/sts1-engine-solver.jar` 742054 bytes / sha256 `22BAFD98AC073EC4D3B63B359E57800EDA6EECB8C806C2230150FEF4626A4E66`

### 诚实边界
「以前为什么是 100%」还差一环没证死（坏文件缺 Music Volume → 默认 0.5；Master 历史值 1.0000002 在 `.backUp`）。
本轮按用户批准的四条执行，不再扩范围；要定死只需再加一行音量日志。
另：`STSSound` 里的 Music/Sound 两个键游戏可能在下一次保存时丢掉（`Settings.java:308-316` 只 put 它自己的两个布尔键并 flush），
丢掉后读的是默认 0.5，与写入 0.5 等效。


## B70 每次启动音量变 100% / 计算后主音量变 0（P2-43；只跑相关门禁 19/0）

**用户 m20138-2 原话**：「每次你启动sts的时候音量都会变成100%，先找原因我确认再改」
**用户 m20313-2 批准的修法**：「① MutedMusic 只构造一次（static final），不再每次安装都跑构造函数；② 任何 MusicMaster/SoundMaster 构造前后保存并还原三个音量静态量；③ 把玩家文件修回去（Master 恢复 1.0000002，补回 Music/Sound 键）；④ 设置界面开着时禁用 InputMute 的坐标改写，杜绝再把滑块拖走。」

### 两个根因
1. **每次沙盒安装都 new 一个 MusicMaster 子类**：`mirror/ExternalMute.java` 原先 `CardCrawlGame.music = new MutedMusic();`，而
   `build/decomp6/com/megacrit/cardcrawl/audio/MusicMaster.java:19-23` 的构造函数把
   `Settings.MASTER_VOLUME = Settings.soundPref.getFloat("Master Volume", 0.5f)`、
   `Settings.MUSIC_VOLUME = ...("Music Volume", 0.5f)` 重新读一遍并打日志。
   游戏日志 `SlayTheSpire.log` 在 06:16:12–06:16:14 刷了 9000+ 行 `Music Volume: 0.5` —— 构造函数被调用上万次。
   `util/SilentInstance.tick()` 只在每个渲染帧重置一次零，一次搜索在一帧里上千次装/卸，缝隙里就是文件里的音量。
2. **光标被推走后，设置界面的滑块跟着走**：全游戏只有 `screens/options/Slider.modifyVolume()` 写 `Master Volume`，
   `Slider.update()` 用 `InputHelper.mX` 拖手柄、松开时 `Prefs.flush()`。`mirror/InputMute` 在模拟期把 mX/mY 置 -100000，
   设置界面开着时就把主音量拖到最左并落盘 —— `preferences/STSSound` 只剩 `{"Master Volume":"0.0"}`。
   我方生产代码没有任何一处 `soundPref.putX/flush`（只有 Probe 读）。

### 修法
| 条 | 改动 |
| --- | --- |
| ① | `ExternalMute.mutedMusic()`：懒建一次并缓存，第二次安装复用同一对象；`musicBuilds()` 计数 |
| ② | `mutedMusic()` 的 try/finally 与 `begin()`（depth 0→1 快照 `savedMaster/savedMusic/savedSound`）/ `restoreAll()` 还原，窗口内任何构造都改不动音量；新增 `volumes()` |
| ③ | `preferences/STSSound` 先备份为 `.pre-mod-20261101.bak`，再写回 Ambience/Mute/Master 1.0000002 + Music 0.5 + Sound 0.5 |
| ④ | `InputMute.screenUp()`（`isScreenUp` 或 `screen != NONE`，异常按 true）+ push 跳过改写、记一行日志、`skippedForScreen()`；pop 只在真改过时还原 |

### 门禁与实测
`tools/run.ps1 -TimeoutSeconds 300 -Gates S120,S86,S87,S88,S111,S76` → **passed=19 failed=0**（141 个门禁跳过）
```
S120 audio: volumesKept=true built=1 buildsAdded=0 musicBack=true screenSkip=true noScreenMoved=true cursorBack=true volumes=[0.0,0.0,0.0]
S111 sfx: active=true play=0 loop=0 played=2 dropped=1 fadesBefore=0 fadeAfter=0 mapSize=368 mapLive=true stillMuted=false
external mute: built the silent mixer once for this process (its constructor re-reads the volume settings)
input mute: a screen is open, leaving the cursor alone (a slider would be dragged to the edge)
```
装配件：`mod/sts1-engine-solver.jar` 742054 bytes / sha256 `22BAFD98AC073EC4D3B63B359E57800EDA6EECB8C806C2230150FEF4626A4E66`

### 诚实边界
「以前为什么是 100%」还差一环没证死（坏文件缺 Music Volume → 默认 0.5；Master 历史值 1.0000002 在 `.backUp`）。
本轮按用户批准的四条执行，不再扩范围；要定死只需再加一行音量日志。
另：`STSSound` 里的 Music/Sound 两个键游戏可能在下一次保存时丢掉（`Settings.java:308-316` 只 put 它自己的两个布尔键并 flush），
丢掉后读的是默认 0.5，与写入 0.5 等效。



## B71 -- 算完的胜利线只打了三步就停 + 战后三选一被当成战斗选项（✅ 相关门禁 28/0）

用户 m20437 / m20485 / m20521 原话：
- 「大颚虫那局没走完，计算是算到了接下来的步骤但是没写还是没算到呢，如果说算到了怎么改」
- 「1.原计划里面怪物有没有死？2.不是执行（自动出牌）的问题，是计算的问题。3.为什么被扔掉」
- 「1.做ab 。2.战后三选一选牌这个和战斗无关不要计算。」

### 事实（logs/solver-20261101-141644.log）
```
14:16:14.713 solve: QUICK win splits=0 deepest=17 hpLoss=12 ... complete=true turns=3 nodes=1842
                       expansions=571 986ms reason=win layers=18 maxBranch=6 deferred=1085 promoted=0
14:16:14.713 plan : 打击 -> 大颚虫 | 打击 -> 大颚虫 | 结束回合 | 头槌 -> 大颚虫（选：恶魔形态） | 打击 -> 大颚虫
14:16:15.546 / 15.981 前两张打击被执行
14:16:16.080 end-turn risk: 结束回合预计 -12 HP -> HP 55（来袭 >=12） -> ALLOW
14:16:16.113 WARN autopilot stopped: 路线已走完（不代表打不过）；点「计算」可以再算一条
```
- 伤害对得上（JawWorm 45 HP）：痛击+头槌 20 → 25，打击×2 12 → 13，头槌 12 → 1，最后打击收掉。
  `Solver.java:712` 的 win 用引擎自己的 `monstersDead()`，所以这条线是以致命一击结尾的：计划里怪物是死的。
- ALLOW 到停止只有 33ms（约两帧），中间无漂移、无过期、无换局 → 与 beam/停车场无关（promoted=0）。

### 根因一：计划被静默扔掉
`Gameplay.java:2281`（旧）：
```java
if (Driver.isAwaitingCardChoice()) {
    pending = null;
    plan = null;
    status = "该步会打开选牌/奖励界面（本版不展开）；请手动选完再按 F8";
    return;
}
```
无日志，且排在「可应答选牌」分支之前。而旧 `Driver.isAwaitingCardChoice()` 的兜底是
`reward != null && selectionKind() == NONE && screen != NONE` —— `AbstractDungeon.cardRewardScreen`
是非 null 单例，于是「任意非 NONE 界面」都算不可答。

### 根因二：战后三选一被当成战斗选项
旧 `Driver.isCardRewardOpen()` 只看 `screen == CARD_REWARD/CHOOSE_ONE` + `rewardGroup` 非空：
没有房间相位门槛，也没有「是不是战斗动作开的」判据。战后三选一是同一个对象，于是搜索与自动出牌
都把它当决策，日志里刷了约 5500 行 `reward pick answered with Combust / Corruption / Barricade`，
真机上还出现过 `execute: answered selection with Demon Form`（替玩家选了战后奖励牌）。

### 修法
- `Driver.inCombatPhase()`：`getCurrRoom().phase == RoomPhase.COMBAT`。
- `Driver.isCardRewardOpen()` 追加相位门槛 → 战后三选一进不了搜索/自动出牌（m20521-2）。
- `Driver.isCombatCardReward()`：`isCardRewardOpen()` 且引擎自己置的 discovery/chooseOne/codex
  标志之一为真（只有这时才真有动作在等这张牌）。
- `selectionKind()` / `isAwaitingSelection()` 改走 `isCombatCardReward()`。
- `isAwaitingCardChoice()` 删掉「任意非 NONE 界面」尾巴：战斗外一律 false；战斗内只剩无法分类的
  CARD_REWARD/CHOOSE_ONE。
- `pollPending` 该分支：**保留 plan 与 pending**，按 `screen + step` 只记一行日志
  （`card screen is up and the plan cannot answer it (...)`），并且只 `stopAutopilot`（它从不动 plan）。

### 门禁 S121（core）
```
S121 screens: phaseGate=true postKind=true postChoice=false combatOpen=true flagsSet=false
              flagGate=true combatKind=true combatChoice=true restored=true
```
同版本另跑 S43（发现仍能开 3 条分支且牌进手）、S45（沙盒屏是副本、真屏还原）、S98（液态记忆选牌）、
S99（计划走完只停不算）、S109（喝药：瓶子清空 + 遗物回血）→ 28/0。

### 诚实边界
门禁证明的是判据与相位/标志逻辑，不是一次半小时实机。需要玩家确认的两件事：
1. 打完一场 → 战后三选一不再被 mod 自动应答（自己选）；
2. 计划应答不了的选牌界面 → 自动出牌暂停、计划还在（点「自动」继续），不再出现「路线已走完」。

## B72 -- 更早层被 beam 挤掉的候选从来没被回放过（✅ 核心 230/0、相关门禁 32/0）

用户 m20668 / m20710 / m20719 原话：
- 「你看一下日志，我的打法比你计算的好，给我简单的具体的理由」
- 「但是我掉血更少为什么在你这里分数变低了」
- 「那就是你没有搜到。我打法少掉血但多打了几回合。你看看为什么没找到吧」

### 现场（C:/Users/YOKI/sts1-engine-solver/solver.log，15:09 那一战）
```
15:09:25.346 / 26.480 / 28.368 / 30.225  A3 deferred: kept=270 / 680 / 1314 / 1314  promoted=0
15:09:26.501  memory guard: beam 64 -> 48 (heap 2878MB over 1400MB)
15:09:28.388  memory guard: beam 128 -> 48 (heap 3584MB over 1400MB)
15:09:30.245  solve: QUICK win hpLoss=7 nodes=509 expansions=205 606ms reason=win layers=25 deferred=270 promoted=0
15:09:30.246  plan : 12 步 / 4 回合，只有 1 张防御 + 1 张重振精神（抢杀线，掉 7 血）
```
面板写「可赢（已搜完可用路线，最好：掉血 7）」，而玩家手打（耸肩无视 / 幽灵铠甲 / 坚毅 / 两张 Wound）掉得更少。

### 根因一（决定性）：promote 只回放「当前层」的候选
`src/main/java/sts1solver/search/Solver.java` 旧门槛：
```java
if (entry.layer >= this.maxLayer || entry.layer < this.layer) continue;
```
停车时溢出候选被标成「上一层」（`defer(deferred, overflow, this.layer + 1)` 之后 `++this.layer`）。
因此某层「没有溢出、所有子节点都死」地跑干时，停车场里最深的条目正好是 `layer-1`，
`entry.layer < this.layer` 对每条都成立 → 全部 continue → `promoted` 恒 0。
越早被挤掉的线（防御线正是这一类）永远不会被回放；「已搜完可用路线」只代表 48 宽 beam 的前沿走死。

### 根因二：堆守卫阈值写死 1400MB
`Solver.heapLimitMb = Long.getLong("sts1solver.heapLimitMb", 1400L)`；本机 max heap 7106MB、物理 31.2GB，
一旦越过 1.4GB 就 latch 到 beam=48，且要掉到 910MB 才释放 → 整局都在 48 宽下搜。

### 修法
- `Solver.promote()`：门槛只留 `entry.layer >= maxLayer`；新增 `promotedKeys`（`layer|digest`）保证同一条只重跑一次；materialize 失败撤销登记；新增 `promotedEarlier` 计数与日志 `earlier=N`。
- 新增 `Solver.replayEarlier`（默认 true）+ `setReplayEarlier(false)` 复现旧规则，供门禁 A/B。
- `heapLimitMb` 默认 -1 = 跟随 JVM：新增 `effectiveHeapLimitMb()` = `max(256MB, 65% × Runtime.maxMemory())`；0 仍关闭、>0 仍钉死；latch/释放都用它。
- `PlanText.winBest(frontierExhausted, promoted, deferred)` / `parkedNote(...)`：前沿跑干但没回放时不再说「已搜完可用路线」，改说「前沿跑完，但没回放被挤掉的线，最好：…」+「还有 N 条被挤掉的线没回放（可以再算）」。
- `run.ps1 -Gates` 的显式列表优先于 core/extended 分级（此前点名 S65 会被 coreOnly 跳过）。

### 门禁 S122（core）
```
S122 heap:   max=7106MB auto=4618MB want=4618MB
S122 replay: off[finished=true slices=4 deferred=45 promoted=0 earlier=0 steps=61 hpLoss=19]
             on[finished=true slices=2 deferred=45 promoted=7 earlier=1 steps=61 hpLoss=19]
S122 text:   noReplay=[前沿跑完，但没回放被挤掉的线，最好：] replayed=[已搜完可用路线，最好：]
             note=[ 还有 270 条被挤掉的线没回放（可以再算）]
```
夹具 seed=1221 / 20 HP / 5×Defend_R / JawWorm / beam=1 / maxLayer=60 / 4000ms：打不赢，前沿必定无溢出跑干，
正是生产日志的形状。关掉 `replayEarlier` 得 `promoted=0`，与 kept=1314 promoted=0 同形。

### 回归（同一枚安装 jar）
- 核心套件 `AUTOTEST RESULT passed=230 failed=0`（104 extended 跳过）。
- `-Gates S122,S108,S109,S65,S59` → `passed=32 failed=0`；`S59 single` 未变（hpLoss=0 nodes=106）；
  `S59 portfolio` 结果未变（hpLoss=0）但节点 2605 → 3573（更早层候选现在真的被重放）；`S65` 未变。
- 安装 jar 748275 bytes / sha256 `DC38E08BD68E31663905D5A152C76120258026C8312B86F3B9AD790ECE389FC1`。

### 诚实边界
门禁证明「更早层候选会被回放」，不证明「每条回放的线都更好」——好不好仍由真引擎评分决定；
回放要 materialize 整条路线，节点/时间成本会涨（S59 2605→3573），预算用尽时由 promotionLimit 与 deferredPhaseMs 兜底。
两史莱姆那一战的牌组/血量没被日志记录，无法逐字段复现，实机收益需玩家复测。

## B73 一次死亡之后，每场战斗都被判成「已结束」（✅ 相关门禁 17/0）

**用户（m20914）**：「改出问题了。现在面板总是一闪一闪的，而且计算按钮按了之后就显示战斗已结束，明明还在战斗」

**症状**：日志 `logs/solver-20261101-154132.log` 里 15:40:37 刚进战斗，15:40:39 按计算 → 同帧 `status=战斗已结束，计算取消`，之后每次按都在一帧内取消；`inCombat=true` 却在 `inFight()` 处为 false。面板「一闪一闪」= 每次按下先「计算中」、下一帧变「战斗已结束」。

**根因**：B64 的死亡判据把 `AbstractDungeon.deathScreen != null` 也算进去。这个静态只在真死（`AbstractPlayer.java:1465-1488`）和弃局（`ConfirmPopup.java:239-244`）时赋值，**全游戏没有一处置回 null**（只有 `hide()/reopen()`），所以本进程第一次死亡/弃局之后，后面每一场战斗都被当成「已结束」。真正随后备玩家对象复位的是 `isDead` / `currentHealth`。

**修法**：
- `Driver.deadFrom(boolean isDead, int currentHealth)` = `isDead || currentHealth <= 0`，`playerDead()` 只调它；注释写明为什么不能看 `deathScreen`。B64 的「尸体回血」仍由 `isDead` 拦住。
- `combatOver()` 的 `catch (Throwable)` 从静默 `return true` 改成第一次记 `Log.error(...)`，异常不再伪装成「战斗结束」。

**门禁**：新增 core `S123`（`deadFrom` 四组合 + 合成战斗里活着/`isDead`/0 血的来回走 + 未分胜负时 `combatOver()` 为 false）。实测：

    S123 death: aliveStart=true deadFlag=true deadHp=true aliveAgain=true over=false deathScreenPresent=false
    AUTOTEST RESULT passed=17 failed=0 (only [S123, S110, S99, S121] asked for, 143 gate(s) skipped)
    AUTOTEST PASSED

**安装物**：750084 bytes / sha256 `28621D971DF2C7FE3F7CA26F6C5DC52F7C5AF629156777180979DD0B449F66F3`。

**实机复测**：①死一次或弃一局后再进战斗按计算应正常出线；②面板不再闪；③自动出牌能连续出牌；④真死时仍立刻判结束。

## B74 同名两张牌打错了副本 + 方框只在自动出牌时画（P2-47；只跑相关门禁 53/0）

**用户（m21115，逐字）**：1.最新一战打到要打出恶魔之焰不知道为什么自动出牌停止了，应该打到战斗结束啊我没动，可能是又是检测出问题了以为和计划不一样。2.现在的画方框是计算之后就有的，改成只有自动出牌开才有。

### 1 为什么停在恶魔之焰
日志 `C:/Users/YOKI/sts1-engine-solver/solver.log` 两局都停在同一张牌：

```
15:57:50.293 INFO execute: uuid 8386f53f-... is sandbox-local; matched 恶魔之焰 by card id
15:57:50.293 WARN execute: 游戏拒绝打出 恶魔之焰
15:57:50.310 WARN autopilot stopped: 路线已走完（不代表打不过）；点「计算」可以再算一条
15:58:08.632 (同一毫秒) 地狱之刃 → 恶魔之焰 → WARN 游戏拒绝打出 恶魔之焰
```

不是 `PREDICTION DRIFT`：是引擎自己的 `canUse()` 说不。

**真因两条**：
1. 同名两张、费用不同。恶魔之焰是「地狱之刃」当帧生成的；「腐化」让技能被消耗、「死枝」每次消耗再塞一张随机牌，所以手里可以同时有一张 0 费（地狱之刃给的）和一张 2 费。旧 `Gameplay.liveCardFor()` 只按 cardID 取第一张：沙盒算的是 0 费那张，实机挑到 2 费那张 → 拒绝。
2. 拒绝之后清错了东西。`submit()` 走 `Gameplay.fail()`，它把 `plan`/`pending` 一起清空；两帧后 `maybeAutopilot` 看到 `!hasPlan()` 就打「路线已走完」。面板上的原因和真正原因无关，看起来就是「它以为出问题了」。

**修法**：
- `Gameplay.pickSameCard(action)`：同名副本里优先 `Driver.canUse()` 通过的那张，其次费用最低；一次多张时记 `execute: N copies of X in hand; playing the one that costs C`。
- `Driver.refusalReason(card)`：真引擎 `canUse()` 已把判决词写进 `AbstractCard.cantUseMessage`，连 `cost`/`energy`/`turnEnded` 一起打出来。
- `Gameplay.refuseCard()` 取代 `fail()` 的拒绝分支：WARN + 暂停自动出牌 + **保留计划**，面板写「游戏拒绝打出 X（原因…）；自动出牌已暂停，点「计算」重算」。

### 2 方框只在自动出牌时画
`Hud.markersAllowed()` 原来只看 `enabled/busy/markersOff/hasMark()`；按「计算」后 `syncMarkers()` 就把方框画到待出的牌上。现在加 `autopilotMarkers` 标志（`setAutopilotMarkers`），`markersAllowed()` 追加该条件，`Gameplay.tick()` 的 finally 每帧 `Hud.setAutopilotMarkers(autopilot)`（早退分支同样覆盖）。搜索/执行期间仍由 `busy` 关掉（B48）。

### 门禁
新增 core 门禁 S124：同名两张（0 费 + 2 费，energy=0）必须挑 0 费那张；`refusalReason` 必须含 `cost=2` 与 `energy=`；`setAutopilotMarkers(false)` 时 `draw=no`，true 时 `draw=yes`。旧 S90 显式 `setAutopilotMarkers(true)` 后再断言忙碌规则（它测的是 busy）。

实测（`build/run-s124b.log`，`tools/run.ps1 -TimeoutSeconds 400 -Gates S124,S90,S104,S109,S116,S119,S120,S121,S123`）：

```
S90 markers: idle[enabled=true busy=false off=false auto=true mark=true draw=yes] busy[enabled=true busy=true off=false auto=true mark=true draw=no]
S124 copies: copies=2 pickedFree=true paidRefused=true cost=0 reason=没有足够 #r能量 。 cost=2 energy=0 turnEnded=false
S124 markers: off[enabled=true busy=false off=false auto=false mark=true draw=no] on[... auto=true mark=true draw=yes]
AUTOTEST RESULT passed=53 failed=0 (only [S124, S90, S104, S109, S116, S119, S120, S121, S123] asked for, 139 gate(s) skipped)
```

安装物：`mod/sts1-engine-solver.jar` 753536 bytes，sha256 `8E5FC4A697B2D0F6E329344422479635BE7B0C36EB5F09B68D88FA4932A195E4`。

**诚实边界**：门禁只证明「同名多张时不挑付不起的那张」「拒绝会写原因且保留计划」，不证明任何一局都能打到战斗结束；如果拒绝来自别的原因（例如封锁类诅咒），新文案会直接写出来。
## B75 结束回合被判「局面变了」：死怪能力名里有空格（只跑相关门禁 12/0）

**用户（m21295，逐字）**：「最新一战显示第三步与预测不一样（局面变了），第三步是结束回合，之后要重新算，问题可能处在结束回合这里，为什么结束回合局面会不一样？」

### 取证
两次重算后都停在同一步（solver.log 16:31:02 / 16:31:22）：

```
PREDICTION DRIFT at step 3: 结束回合
  expected … M1=FuzzyLouseNormal:0/12 b=0 in=ATTACK mv=1 dm=8 pw=Curl Up10 d=11
  actual   … M1=FuzzyLouseNormal:0/12 b=0 in=ATTACK mv=1 dm=8 pw=-          d=11
```

两行 488 / 480 字符，第一个不同字符在第 474 位。第 2 步「重刃 → 虱虫（左起2）」把它打到 0/12 进入死亡动画（`d=11`）；实机在死亡动画结束时 `powers.clear()`（`AbstractMonster.java:853-860`），沙盒快照还带 `Curl Up10`。

### 根因
`src/main/java/sts1solver/mirror/Digest.java` 的 `monsterStable()` 用 `part.split(" ")` 丢 `in=/mv=/dm=/pw=` 前缀的 token。能力名可以带空格（虱虫的 `Curl Up`），于是 `pw=Curl Up10` 被切成 `pw=Curl`（丢掉）与 `Up10`（留下）→ 死怪段变成 `… b=0 Up10 d=11` vs `… b=0 d=11`，永远不相等。

结束回合本身没有改变局面；它只是让「已经死掉的那只」留在场上进入比对（第 2 步还在结算窗里，根本不去判）。

### 修法
先把 ` pw=` 到下一个 ` d=` 之间整段删掉，再按空格丢 `in=/mv=/dm=`；活怪的能力照旧比较。

### 验证
- 独立 JVM 工具 `build/DigestCheck.java`（`java -cp mod/sts1-engine-solver.jar build/DigestCheck.java`）用日志里那两行做输入：`equal=true leftover=false liveDiffers=true`，stable 结果 `|M1=FuzzyLouseNormal:0/12 b=0 d=11`。
- 新增 core 门禁 S125；`-Gates S125,S42,S80` → `AUTOTEST RESULT passed=12 failed=0`，`S125 spaced power: dying-equal=true leftover=false liveDiffers=true`。

### 诚实边界
只证明「死怪带空格能力不算漂移、活怪同类能力仍算」；若漂移来自别的字段，日志仍会打 expected/actual 两行与 `firstDiff` 位置。

### 复测
1. 打死虱虫后结束回合：不再出现 `PREDICTION DRIFT at step N: 结束回合`。
2. 打死任意怪后结束回合：自动出牌继续走计划。
3. 活怪能力真变了（上虚弱/叠蜷缩）：仍应判漂移并停。
## B76 消耗堆里的牌永远保留「本回合费用」，打一张牌就被判局面变了（m21391-2；相关门禁 20/0）

**用户原话（m21391）**：「这次打乐嘉第三回合打狂野打击之后又要重新算，到底怎么改的老出问题。玩家没动作条件没变就不要让我重新算啊。」

**现象**：16:49:09.164 `PREDICTION DRIFT at step 27: 狂野打击 → 乐加维林`。expected/actual 逐字符只差一处：
`E=...,Seeing Red,Shockwave(0)` vs `E=...,Seeing Red,Shockwave`。两边玩家血量/能量/格挡/遗物/怪物状态完全一致。

**根因**
1. 实机在牌进消耗堆 1 秒后由 `ExhaustCardEffect.update()` 调 `resetAttributes()`（`ExhaustCardEffect.java:41-44` → `AbstractCard.java:2020-2030` 里 `costForTurn = cost`）；那个效果在 `AbstractDungeon.effectList`，而 `World.trimPresentation()` 直接清空它 —— 沙盒里这张牌从此带着腐化给的 0 费。发掘（Exhume）把它拿回手时会给错费用。
2. `Digest.pile()` 把本回合费用写进四个堆的文本，于是动画时序差被当成局面差。

**修法**：`mirror/ExhaustAttrs`（install 复位已有消耗堆、trimPresentation 与沙盒泵清效果前 sync）、`Digest.pile(g, false)` 只对消耗堆不标注本回合费用、门禁 S126。

**实测**：`S126 exhaust: keptBeforeReset=true printed=1 reset=true handShows=true exhaustQuiet=true resets=1`；`AUTOTEST RESULT passed=20 failed=0 (only [S126, S125, S42, S43, S109] asked for)`。

**诚实边界**：门禁不重放那一局；「那一步不再报漂移」需实机复测。复位时机与实机差 1 秒，但当前没有判定读这个中间值。

## B77 自动出牌每次都被打断（头槌选「破灭」后要重新点计算；m21571）

用户原话（m21571）：「我受够了，你这改的一坨屎，每次自动出牌都会打断，这次打出头槌选破灭又要我重新点计算。现在改成自动出牌就和sts2一样按照游戏内的打，如果没有这张牌或者药水就暂停（但是计划不要丢）。」

### 为什么每次都打断
旧版有三处「一有异常就 plan = null」：
1. `Gameplay.pollPending` 的 digest 漂移分支（m16202-2 定的「漂移即停」）——计划置空 + stopAutopilot。
2. `pollPending` 的选牌失败分支与 `lastSelectionConsumed() == false` 分支——计划置空。
3. `Gameplay.fail()`（找不到牌、引擎拒绝出牌、药水槽空）——计划置空。

计划被置空后，下一帧 `maybeAutopilot` 看到 `!hasPlan()`，报「路线已走完（不代表打不过）；点『计算』可以再算一条」——玩家看到的就是「又打断了」。

选牌那一层还有第二个坑：`Driver.resolveSelectionLive` 只用 `cardID` 匹配屏幕上的候选，而 `cardID` 不带升级位——头槌要选「破灭+」时可能选中「破灭」。

### 修法（P2-49）
- 漂移：新增 `Gameplay.noteDriftAndContinue(step, actual)`，只 `++drift` + 记日志 + `rememberExecuted()`，然后照常 `++step` 继续走。**不再停、不再作废计划。**
- 缺件：`fail()` 改为「暂停但保留计划」，文案「计划已保留，处理好后点『自动』继续」；选牌失败的两个分支同样保留计划；`refuseCard()` 同步改文案。
- 选牌匹配：`resolveSelectionLive(cardIds, displayNames)` + `matchOffered(offered, id, name)` 三趟（id+name → 仅 id → 仅 name），Gameplay 三处调用都传 `choiceDisplay`。
- 测试钩子：`installPlanForTest` / `planStateForTest` / `driftForTest`。

### 门禁 S127（core）
- 缺牌：装计划 + 把第一张要打的牌移出弃牌堆 + tick → 计划仍在（plan=15）、autopilot 停、「计划已保留」。
- 漂移：装计划 + 喂一次漂移 → 计划仍在、autopilot 仍 true。
实测：`AUTOTEST RESULT passed=40 failed=0 (only [S127, S99, S93, S92, S109, S43, S98, S121, S125, S126] asked for, 141 gate(s) skipped)`；`S127 missing: … plan=15 step=0 autopilot=false … 计划已保留`、`S127 drift: plan=15 step=0 autopilot=true`。

### 诚实边界
漂移不停 = 计划可能与现实偏离时仍照打，这是用户明确要的 STS2 语义；缺件才暂停。

### 安装物
762001 bytes / sha256 `38DF79646935397E765D549C03714E940EEA19C78EE41C29BBDF9A2AB6BFDF62`。

## B78 名与局面拼在一起：按手牌位置重放、选牌失败就猜、没校验整条线（P2-50；相关门禁 35/0，门禁 S128）

用户 m21889 批准的 A/B/C 三处（起因 m21772：坚毅消耗了发掘，计划后面还排着「发掘+」）。

### 根因

1. `Legal.apply(Action)` 的卡牌分支只认 `handIndex`（第几张），从不校验那张是不是计划里的牌 → 位置一变就打邻牌。
2. `Solver.replay` 里选牌失败会退回 `Driver.resolveSelectionHeuristically()`（按 cardValue 猜一张）→ 一猜，后面手牌顺序全错位。
3. 印计划前没有「整条线重放成功」的校验：`replay` catch 住异常只记 warn 并返回部分 steps，`Result.win` 仍取搜索节点的 win → 面板照样说「可赢」。

### 修法

| 项 | 改法 |
| --- | --- |
| A 打牌按身份 | `Legal.handCard(Action)`：index 上的牌身份对上就用它；否则按 uuid、再按「卡名+ID」、再按 ID 找；都没有抛 `ReplayException`（消息带手牌列表）。新增 `handCardFor` 给门禁。 |
| B 选牌不猜 | `Driver.resolveSelectionByNames(ids, names)` 先按卡名全等（名字带 + 才是升级版）、再按 cardID；找不到返回 false 并记「planned cards not offered」；`Solver` 三处选牌失败一律抛 `ReplayException`，删除 `resolveSelectionHeuristically` 回退。 |
| C 自洽校验 | `Solver` 用 ThreadLocal 记 `lastReplayFailedAt/On`；`Result` 新增 `planVerified / planFailedAt / planFailedOn / planRequested`；`PlanText.headline` 先看 `planVerified`，失败输出「这条线重放不通过（第 N 步：X），只给前 M 步；不算可赢，可以再算一次」；`Gameplay.startAutoplay` 拒绝播放未验证的线。 |

### 门禁 S128（core）

`S128 identity: card=打击 indexNow=痛击 resolved=打击 identity=true moved=true`

`S128 missing: refused=true msg=the line cannot be replayed: 打击 [Strike_R] is not in hand (hand=防御, 痛击, 防御, 防御)`

`S128 text: 这条线重放不通过（第 3 步：发掘+），只给前 2 步；不算可赢，可以再算一次`

### 诚实边界

门禁只证明「按身份能找到牌」「找不到会拒绝」「文案会说真话」，不重放用户那一局（起点状态没有存档）。重放失败时面板会少给后面的步数——这是有意的：错误的完整线比正确的半条线更糟。

## B79 选牌没记 uuid、失败就判线坏、失败原因混在一起（P2-51；相关门禁 27/0）

用户 m22205：面板一旦出现「这条线重放不通过（第 N 步：X）」，说明给出的线打不出来；要确认是真错而不是「打太快、屏还没开」，并且失败原因要能分类。

三条根因：
1. 计划只记 cardID（不带升级位），重放时按 cardID 匹配 → 同名不同升级、同名多张的副本会被认成同一张（B50 遗留）。
2. 重放选牌只答一次：屏还没开的窗口里失败就直接判这条线坏，与「打太快」不可区分。
3. 失败信息只有一句人类可读的话，没有分类，日志里分不清 not-in-hand / not-offered / not-settled。

修法：Action.choiceUuids + Solver.Choice.choiceUuids + Gameplay.pendingChoiceUuids 把选项 uuid 写进计划；Driver.matchOffered 四趟（uuid > cardID+卡名 > cardID > 卡名）；Solver.answerForReplay 首次失败后 Driver.settleForReplay(16) 再答一次，成功记「answered … after settling N update(s)」；ReplayException.kind 与 Driver.refused / Solver.lastReplayFailure() 把桶名写进日志。

门禁 S129（core）：Synthetic seed=9129/maxHealth=80/deck {Headbutt,Strike_R,Defend_R,Defend_R,Bash}/JawWorm，弃牌堆放两张同名 Defend_R，用第二张的 uuid 作 choice：carried=true steps=1；坏选择 Bash：steps=0 且失败串含 not-offered；settleForReplay(4)：idle=NONE pumps=4。

实测行：S129 copy: carried=true steps=1 copied=the planned copy failure= ／ S129 refused: steps=0 failure=the line cannot be replayed: not-offered: the screen offers [Defend_R,Defend_R], the plan chose [Bash] ／ S129 settle: idle=NONE pumps=4 bounded=true。

诚实边界：settleForReplay 有界 16 次 update，更久的动画仍判 not-settled（不会卡搜索）；桶名只解释「这一遍为什么打不出来」，不解释搜索是否有解；uuid 进计划不改变候选集。

安装 jar 774044 bytes / sha256 FE1085291CCC45A471E49775A6541DE12818751012031B530525553FC08DBBCC。证据 evidence/B79-UUID-CHOICE-RETRY-20261101.txt。

## B80 自我修复的战后回血没进评分（P2-52；相关门禁 25/0，S130）

用户原话（m22491）：「1.最新打乐嘉这里局外收益回血没打（“自我修复”这张牌）没算。」

**现象**：搜索把「自我修复」当纯亏 1 费 —— 掉血一样多、还花一张牌，于是永远不选它；实际上它是战后白拿 7（升级 10）点血。

**根因**：沙盒的胜利判定是 Solver.monstersDead()，房间没真的结束，引擎的 power.onVictory() 在模拟里永不执行；而 search/PostCombatHealing 只补遗物（Burning Blood / Black Blood / Meat on the Bone），从没读 power。

**引擎事实**：build/decomp6/com/megacrit/cardcrawl/cards/blue/SelfRepair.java 的 use() = ApplyPowerAction(p,p,new RepairPower(p,magicNumber),magicNumber)；powers/RepairPower.java 的 onVictory() 里 if (p.currentHealth > 0) p.heal(amount)。全量扫描 powers 只有这一个在 onVictory 回血。

**修法**：
- search/PostCombatHealing.java：bound() 增加 repairPower(p)（遍历 player.powers，按类名 RepairPower 累加 amount，异常 0）；describe() 追加「自我修复 +N」。
- 计价入口不变：Solver.postCombatHealBonus() = bound() * 2000，只在 choice.win 时加；打分时安装的是该节点的世界，所以逐节点生效。
- 封顶沿用 min(总量, maxHealth - currentHealth)。

**门禁** S130（core）：无遗物夹具，60/80 打一张 → 7，再打升级版 → 17，改 78/80 → 封顶 2。
实测：S130 repair: ok before=0 seven=7 said=true up=17 capped=2

**验证**：tools\run.ps1 -TimeoutSeconds 420 -Gates S130,S14,S53,S97,S129 → AUTOTEST RESULT passed=25 failed=0（149 门禁跳过）。
安装物 775819 bytes / sha256 23E2D1FA00DC11D408605A71E45306507CEBD435C8F568122E4F25D73F78B784。

**诚实边界**：门禁证明记账与封顶正确，不证明某条具体路线一定被选中；只覆盖原版 power 表，mod 新增同类 power 需要扩表。

**补记（用户 m22600）**：第一次修只补了数量，没补「按节点读」。评分时子节点世界已 uninstall，
postCombatHealBonus() 读到的是实机玩家 → 所有胜利节点加同一常数，等于没加。
改法：Choice.postHeal（readChoice 里读）→ Node.postHeal → 打分用 healPoints(choice.postHeal)；
Result.postHeal + compareRoutes(…, postHeal, …) 让最终选线也扣掉这份回血；组合节点继承 sub.postHeal。
新增断言 S130 search：60/80、能量 3、自我修复 + 打击 x2，实测 plan=自我修复 | 打击 | 打击、heal=7、hpLoss=0（32/0）。

## B81 — 一次搜索写 14 万行日志，世界线从 1700 条/s 掉到 36 条/s（✅ 相关门禁 34/0，S131）

**用户**（m22687）：「看最新日志，明显有大量重复的日志，是哪个循环出问题了吗，打的是三哨位，一开始算还不卡，member=4的时候就非常卡了，一秒几十条世界线，为什么会这样先找出原因不要乱改，告诉我哪里出问题了」；m22748「能修好吗，怎么改」。

**现场**：`solver.log` 16.5MB / 141876 行，140252 行（98.8%）是同一句 `input mute: a screen is open, leaving the cursor alone (a slider would be dragged to the edge)`。世界线 1700 条/s → 36 条/s；`frame p50=83ms p95=511ms`；`heap=5832/7106MB`；`gc=1250/358964ms`；watchdog 抓到渲染线程 9 秒停在 `OutputStreamManager.writeBytes`。

**根因两条**：
1. 打印点在循环里：`InputMute.push()` 被 `World.install()` 每个模拟窗口调一次（每节点、每次重放），旧代码每次 push 写一行 `Log.info`，而 `Log` 每行 autoflush、写在渲染线程上。
2. 窗口里总认为「有界面开着」：`World.install()` 先换静态量、后 push，所以 `screenUp()` 读沙盒值；`World.readBack()` 会把沙盒里每个未标 `discardOnReadBack` 的静态量写回 `cells` —— 包括 `AbstractDungeon.screen` / `isScreenUp`。模拟死亡在窗口里构造 `DeathScreen` 并把 screen 置成 DEATH，这个值被存进世界，之后每个窗口都带着「界面开着」。

**修法**：`mirror/Statics.java` 给 `AbstractDungeon.screen` / `isScreenUp` 标 `discardOnReadBack`（同类：B20 的 cardRewardScreen、B26 的 screenShake）；`mirror/InputMute.noteSkip()` 改摘要（最多每 5 秒一行，带 `screen=` / `isScreenUp=`），新增 `skippedSinceLog` / `skipLines` / `skipLogLines()` / `resetSkipLog()` / `lastScreenName()` / `lastScreenUp()`；`Gameplay.startQuickSolve` 与 `startDeepSearch` 各调 `resetSkipLog()`，保证一次搜索至少一行摘要。

**门禁** S131（core，辅助 `s131ScreenNotCarried()`）：窗口里置 DEATH → readBack → 再开窗口断言 `carried=false`；无界面 push/pop 不跳过不写日志；界面开着连开两个窗口跳过 2 次但只写 1 行摘要。实测 `S131 carry: carried=false skipsUnchanged=true liveRestored=true summaryPrinted=true`；`-Gates S131,S120,S121,S45,S43,S109` → 34/0（149 跳过）。

**安装物**：779154 bytes / sha256 `7B350D6A25AB99FA58C525EC4337B96B9C556BAC29294F9253058FC8962BA682`。

**诚实边界**：门禁证明「界面不进世界、窗口不再误判、日志不再逐节点写」，不证明真机吞吐；「member=4」在日志里 0 命中，无从对应。

## B83 — 计划妥当保留的牌在沙箱里被弃掉（✅ 相关门禁 58/0，S133）

**用户**（m23574）：「看最新战斗，回合结束时计划妥当选择保留牌这个操作沙箱模拟没做好…下一回合让我打中和+但是没摸到…是不是和头槌的问题一样沙箱里面选的太快导致的」——猜对了。

**链路**（build/decomp6）：`AbstractRoom.endTurn`（rooms/AbstractRoom.java:383）→ `applyEndOfTurnTriggers`（core/AbstractCreature.java:536）→ `RetainCardPower.atEndOfTurn`（powers/RetainCardPower.java:35-37）→ `RetainCardsAction.update`（actions/unique/RetainCardsAction.java:29-43）：`duration == 0.5f` 时 `handCardSelectScreen.open(...)` + `addToBot(new WaitAction(0.25f))` + `tickDuration()`，之后只有 `!wereCardsRetrieved` 才把 `selectedCards` 里的牌 `retain = true` 并放回手上。

**根因**：`Driver.endTurn(long)` 的 drain 用 `FAST_DELTA = 20.0f`。开屏那一拍 `duration = 0.5 - 20.0 = -19.5`，`AbstractGameAction.tickDuration` 立刻 `isDone = true`，动作在读到答案之前就出队；之后填的 `selectedCards` 没人消费，牌 `retain` 没设 → `DiscardAtEndOfTurnAction`（actions/common/DiscardAtEndOfTurnAction.java:30）把它扔掉 → 下一回合计划里的「中和+」不在手上。与 B49/B66（头槌）同源。

**修法**：`Driver.needsSelectionDeltaAtEndOfTurn()` —— 手牌非空且任一 power 类名含 `Retain`（基础游戏只有 `RetainCardPower`）或类名不以 `com.megacrit.cardcrawl.` 开头（mod power 可能开任意屏）时，`endTurn` 的 drain 改用 `SELECTION_DELTA = 0.05f`，`finally` 还原。另加 `endTurnSlowFrameCount()`/`resetEndTurnSlowFrames()`/`queueNames(int)`/`setEndTurnSmallFramesForTest(Boolean)`。

**门禁** S133（core，辅助 `s133Retain(boolean smallFrames)`，同款战斗跑两遍）：`S133 old: ok small=false hand=5 awaited=true answered=true inHand=false discarded=true handAfter=5 discard=5 slow=0`；`S133 fixed: ok small=true hand=5 awaited=true answered=true inHand=true discarded=false handAfter=6 discard=4 orphans=0 slow=1`；`-Gates S133` → 8/0。回归 `-Gates S133,S130,S129,S127,S109,S92,S98,S126,S125,S59,S65,S121,S120` → 58/0（S59 single nodes=106 不变）。

**安装物**：783572 bytes / sha256 `2B0083E283E400BBF5FDD2CF48EAC5ECED08BBDE79960827BC1B15FF17A756B8`。

**诚实边界**：门禁只证明引擎层（被保留的牌留在手上、其余四张进弃牌堆）；不重放那一局。`RestoreRetainedCardsAction` 会在恢复时把 `retain` 清掉，所以断言看牌在哪而不是看标志。基础游戏里我们没见过的开屏 power 仍走快帧。

## B84 — 屏开那一拍引擎仍拿 20 秒帧，答案没人消费（P2-55；相关门禁 56/0）

用户 m23817（原文）：「方案一最好，要确认不会和我们已有的 pumpWhileScreenOpen 打架；补丁要用 World.anyInstalled() 限住，别影响实机先确认再做」

### 机理
真游戏里动作队列在界面开着时是冻结的：`AbstractRoom.update` 只有 `!AbstractDungeon.isScreenUp` 才调 `actionManager.update`。所以「开屏动作」在自己的 tick 里永远不会把 duration 走完：RetainCardsAction 停在 0.5s 那一拍等玩家点，ScryAction 停在第一个分支。
沙盒里那一拍拿到的是 FAST_DELTA=20s，于是 `duration -= 20` 直接为负 → 动作 isDone 出队；随后我们（或自动出牌）填好 selectedCards，却已经没有任何动作会来读它。表现就是：计划妥当保留的牌被弃掉、先见之明的预见不弃牌、mod 自己开屏的动作同样失联。
（B83 曾按「哪个 power 会开屏」打名单补丁；B84 把规律改回引擎自己的不变量：屏幕开着的那几拍就是 1/60。）

### 修法
- `Driver.frameDelta()`：屏开着（`AbstractDungeon.isScreenUp`）且 `World.anyInstalled()` → 返回 `SCREEN_DELTA = 1/60`；否则返回 `simDelta`（20 或出牌时的 0.05）。注入点仍是 `Driver.makeDeltaProxy`，只在 begin/end 窗口存在，实机拿不到。
- 新增 `screenFrames()/resetScreenFrames()/setScreenDeltaForTest(Boolean)`：前者是诊断与门禁证据，后两者给 A/B 门禁强制开关。
- 旧的名单规则（`needsSelectionDeltaAtEndOfTurn`）保留但不再是唯一路径。

### 实测（build/run-b84b.log、build/run-b84c.log）
```
S133 old: ok small=false screenDelta=false screenFrames=0 inHand=false discarded=true discard=5
S133 generic: ok small=false screenDelta=true screenFrames=1113 inHand=true discarded=false discard=4
S133 list: ok small=true screenDelta=false screenFrames=0 inHand=true discarded=false slow=1
S134 old: ok screenDelta=false awaited=true kind=GRID offered=1 answered=true closed=true scried=false draw=10 discard=5
S134 fixed: ok screenDelta=true awaited=true kind=GRID offered=1 answered=true closed=true scried=true draw=9 discard=6 screenFrames=1603
AUTOTEST RESULT passed=56 failed=0 (only [S133,S134,S92,S98,S126,S129,S43,S109,S59,S65,S120,S131])
```

### 安装物
`D:/Steam/steamapps/common/SlayTheSpire/mods/sts1-engine-solver.jar` = 786012 bytes / sha256 `562C18931D0A5AD615BD4123DF4DBE8B5BBF2991B27E392FEBCCADAFEA7E8390`

### 诚实边界
- 门禁覆盖两条真实路径（保留牌、Foresight 的 scry），没有穷举所有 mod 开屏动作；但规则本身不再依赖名单，所以新触发点自动适用。
- 屏开着时每拍只推进 1/60，涉及选牌的重放会多跑一些 update；本次 `-Mode cost-normal` 实测 0.72ms/节点，未升。
- 仍只 pump ≤8 拍；若有动作需要更多拍才消费，会以「孤儿屏」暴露，再按证据加，不预先加。

## B85 — 回合归属靠算术反推（用户 m23938 / m24017 / m24065）

**症状**：一局里最后一步「结束回合」被记成**第 0 回合**（实际是第 1 回合之后），于是 `planIsStale()` 判计划过期、直接丢掉剩下的步骤，自动出牌「最后一步没打出」；面板把那一行画在「第 0 回合」。

**根因**：`Solver.replay` 里 `new Step(..., GameActionManager.turn, ...)` 是**动作执行之后**才读回合号，而 `Gameplay.turnOfStep()` 又用 `action.isEndTurn() ? step.turn - 1 : step.turn` 反推「动作开始时的回合」。这个算术只在「结束回合真的让 turn +1」时成立：
- 毒/持续伤害在结束回合里打死最后一只怪 → 房间结束战斗、`turn` 不再推进 → 那一步记成 N，减 1 变 **0**。
- 方向相反的同一处：腾跃（Vault）是**卡牌驱动的回合推进**，`Action.isEndTurn()` 定义为「非牌非药水」，不减 1 → 腾跃被记成 **N+1**，于是面板把它画进下一回合、`cacheFits`（`first.turn != turn`）永不命中、玩家手动结束回合后旧计划反而判不过期。

**修法**（B85/P2-56，六处消费点只需要「动作开始时的回合」，没有任何地方需要 turnAfter）：
- `Solver.java` replay 循环：`Legal.apply(action)` **之前**取 `int turnBefore = GameActionManager.turn;`，`Step` 记它（字段注释改为「the turn this step was TAKEN in」）。
- `Gameplay.java`：删 `turnOfStep()`；`planIsStale()` 用 `plan.plan.get(step).turn < GameActionManager.turn`；`startsInLiveTurn()` 用 `plan.get(0).turn >= liveTurn`；`dropStalePlan()` 的 `belongsTo` 直接取 `step.turn`；`buildRoute()` 的 `shown[i] = st.turn`。
- `probe/HexaProbe.java`：`int turn = s.turn;`。
- 配套防御：结束回合的等待条件加 `&& !fightFinished()`（`Driver.combatOver() || room == null || room.isBattleOver || room.phase != RoomPhase.COMBAT`）——否则记账修好后会退化成「白等 20 秒 pendingDeadline」。

**门禁**：S135（毒在结束回合杀怪：末步 `isEndTurn`、`turn==1`、`planIsStale()==false`、面板无「第 0 回合」）、S136（同一夹具把整条线跑到底：`verifiedSteps()` 增长、`combatOver()==true`、111ms 内完成，证明没走 20 秒兜底）、S137（腾跃：`t0==1` 即本回合、`t1==2`、首步腾跃的计划能命中缓存）。回归 S52（腾跃推进回合）、S74（过期计划）、S133/S134。

**教训**：
- 「瞬时状态」门禁：`Gameplay.tick()` 同一帧里既有接受又有「战斗结束」清理，读 `step`/`status` 永远读到清理后的值；要用不会被复位的 `verifiedSteps()` 这类计数器。
- 探针驱动 live 引擎必须走真实帧 `AbstractRoom.update()`（它才会 `player.updateInput()` → `room.endTurn()`），只打 `actionManager.update()` 永远不会消费 `endTurnQueued`。
- 回合号只能当末位 tie-break（见 JUDGMENT R20），不要用它反推动作归属。


## B86 — 一个动作连开两次选牌，只答了第一屏（2026-11-02，用户 m24541/m24637）

**现象**：打出头追（CaiNongMod `HeadChase`）时，这张牌要在弃牌堆与抽牌堆各选一张（共两次）。自动出牌「不卡住但也没换牌」；手动点两次能正常互换；面板只显示一张要选。

**根因（不是沙箱只能选一次，真引擎两屏都会开）**：
1. `Driver.pumpWhileScreenOpen()` 答完第一屏后继续泵最多 8 帧，把 mod 动作 `ExchangeCardsOfTwoPilesAction` 的 `duration=Settings.ACTION_DUR_FASTER`(0.1) 在同一拍烧完 → 动作 `isDone` 出队，第二屏永远不会被打开；`closeGridScreen()` 又顺手把它关掉。
2. `Solver.expand` 只为第一屏生成分支，计划里只有一个 `choice`。
3. `Gameplay` 的 `pendingChoice` 只有单值，答完就清空 → 第二屏来了也没人答。

**修法**（详见 PLAN P2-57 与 evidence `B86-P2-57-58-20261102.txt`）：
- `Driver`：`screenSerial` + `selectionQuestion()`（屏身份 = 选牌种类 + 组对象 `identityHashCode`）。泵里发现身份变了就 `++screenSerial` 并把新屏留给调用方；`resolveSelection` 发现 `screenSerial` 变过就不再 close/drain。
- `Solver.expandScreens()` 逐屏应答并记录每屏选项表，`combinations()` 做笛卡尔积，`choice` 用 `|` 分段（`"A|B"`）；`answerForReplay()` 按段依次 `pumpUntilSelection(24)` → `answerOneScreen`。
- `Gameplay`：`pendingChoiceAt` 指针答完一屏再进下一屏，面板显示「选牌：A → B（2/2）」。

**为什么不能用「候选卡名单」当屏身份**：答题本身会搬牌（头追换完牌名单就变了），会被误判成第三屏。真正的第二问只有「给 `open()` 换了一个堆」才发生。已知局限：同一动作连问同一个堆看起来像一问，现有引擎/mod 卡没有这种用法（Headbutt/HeadChase、Dredge、Hologram、Secret Weapon 都换堆）。

**门禁**：S138（复刻两屏：弃牌堆 5 张 / 抽牌堆 7 张，两屏都答、两张牌真互换、无残留屏、实机牌堆未被碰）；S139（5×7=35 个组合全部生成、按名次排序、low 档截到 8）。

## B87 — 搞里头（GetInside）选出的两张牌没离开手牌 + 静态卡牌容器被共用（2026-11-02，用户 m25378）

**现象**：打出搞里头（CaiNongMod `GetInside`）后面板提示「（选：打击,打击）」，但自动出牌后两张打击仍在手牌里；手动打出并手选之后，下一回合的抽牌与沙盒推演分叉。

**根因（两层）**：
1. `Driver.fillHandSelection` 只把牌塞进 `HandCardSelectScreen.selectedCards`，**不搬手牌**。真实点击（`HandCardSelectScreen.selectHoveredCard`）是当场 `hand.removeCard` + `refreshHandLayout` + `selectedCards.addToTop`。多数动作（Exhaust/Armaments）自己会搬，GetInsideAction 不搬——它只 `ModHelper.addInsideCards(card)` 记一下，靠屏幕那一下把牌取走。于是牌同时存在于手牌与静态 list，下回合抽牌数差一张，沙盒与实机从此分叉。
2. `CaiNongMod.helpers.ModHelper.cards_Inside` 是 `static final ArrayList<AbstractCard>`，`ModStatics` 只镜像非 final 标量，所以**沙盒与实机共用同一个 list**：沙盒 `clearInsideCards()` 会清掉实机存下的牌，沙盒存的牌对象又留在实机 list 里。

**修法**：
- `Driver.fillHandSelection` 复刻真实点击：先取 `previous = selectedCards`、`here = hand.group` 快照；逐张 `rebind` 到当前副本后 `selectedCards.add`，并从手牌 `removeCard`（记入 `removed`）；`refreshHandLayout` + `setAngle(0)`；若填不满 `want` 且不允许空选则整体回滚返回 false。必须幂等——live 每帧重填（B66），已取走的牌要认出来直接补进 selectedCards 而不是当「不在手牌」。
- 新 `Kind.MUTABLE_CONTAINER`（`mirror/Statics.java` 的 `MOD_CONTAINERS` 表 + `World` 五处 switch）：static final 引用在 JDK21 下用反射换不掉（`IllegalAccessException: Can not set static final`，已实测），所以装沙盒时**原地重写内容**（实机内容先快照进 `liveContainers`），卸下时还原；`readBack` 只带副本。解析靠 `ModStatics.resolve()` 三 loader，mod 未装就跳过。
- 观测：进程内首次扫描到 mod 容器时打一行 `mirror: mod static-final card container(s) mirrored by content: <owner#field>`。

**门禁**：S141（沙盒开手牌屏选 2 张：两张真的离开手牌、未被选的还在、屏幕关闭、实机手牌未动）；S142（static-final `ArrayList` 进镜像表；实机换不掉引用但内容被替换成副本；沙盒改动不影响实机；卸下还原——用 `demoMod.probe.ModProbeCards#HELD` 当夹具，CaiNongMod 未加载时那一行跳过）。

**顺带改的旧行**：S133 的「大帧 + 屏规则关闭 → 牌被丢掉」这一行，旧断言是 `discarded=true`（牌留在手牌、回合结束被弃）；现在牌在应答瞬间就离开手牌，动作早已完成没人收它 → `inHand=false & discarded=false & exhausted=false`（掉在所有堆外面）。断言语义改成后者，文案不变。


## B88 — 效果队列里的状态携带者被直接丢掉（P2-61；✅ 核心 323/0，门禁 S144）

**现象**：不是用户报的，是 P2-61 审计「出牌后到底哪些状态落在我们丢掉的东西里」时查出来的。

**根因**：沙盒用 `World.trimPresentation()` 清空四个效果容器（`effectList/effectsQueue/topLevelEffects/topLevelEffectsQueue`）。
游戏里 `vfx/cardManip/ExhaustCardEffect.update()`（:26-45）除了播动画还负责两件战斗状态：`c.resetAttributes()`（把 block/damage/magicNumber/costForTurn 归位，
`cards/AbstractCard.java:2020-2030`）与「不在手牌则 `c.fadingOut = true`」。效果对象一丢，这两件事永不发生。
B76 的 `ExhaustAttrs` 只按「消耗堆张数变化」触发，于是这**两条路径**完全没覆盖：

- `GameActionManager.java:255-269`、`:275-285`：牌在 `limbo` 里被判废（目标已死 / 这张牌打不出来）时，引擎给牌挂 `ExhaustCardEffect`，牌被移出 limbo 且**不进任何堆**；
- `GameActionManager.java:368-381 callEndTurnEarlySequence`：提前结束回合时清 limbo，每张 limbo 牌挂 `ExhaustCardEffect`。

**为什么现在才暴露**：B87 的搞里头正是「把牌放进 limbo 再打出去」的机制；牌被离手后的 cost/damage 复位依赖这条终态。

**修法**：新增 `mirror/EffectSettle`，在清空容器前按类名分派：`ExhaustCardEffect` 读私有字段 `c` 补做终态；其他效果类记一次日志（最多 40 个类名），
把「未来新增的带状态效果」变成日志里的可见项。`ExhaustAttrs` 保留。

**门禁**：S144 —— cost/damage/block 三项复位、离手牌 `fadingOut=true`、在手牌不淡出、未知类被记录、实机未被改。


## B89 — 打出后强制结束回合的牌，沙盒不结束回合（P2-63；✅ 核心 328/0 / 完整 903/0，门禁 S145）

**用户（m26061，逐字）**：「1.新问题，最新日志，牌里有打出后结束回合之后自动出牌有问题。2.比如说第一回合打出一张牌，这张牌打出后强制结束回合，但是面板自动出牌以为还要再结束第一回合但是第一回合已经结束了，所以显示条件已变化要重新计算。3."…AutoWatcher-main"你可以看观者"结末"这张牌怎么处理的，一样是打出后结束回合」

**现象**：观者打「结末」（Conclude，12 点群体伤害 + 结束本回合）之后，自动出牌把整条线走完却提示「计划已过期（已经进入新回合）；按 F8 重算」，线尾的步骤（包括那一步「结束回合」）永远打不出来。

**引擎事实**：
- `cards/purple/Conclude.java`：`use()` = 伤害 + `new PressEndTurnButtonAction()`。
- `actions/watcher/PressEndTurnButtonAction.update()` = `AbstractDungeon.actionManager.callEndTurnEarlySequence()`。
- `GameActionManager.java:368-381`：清 cardQueue、limbo 牌塞 `ExhaustCardEffect`、`player.releaseCard()`、`endTurnButton.disable(true)`。
- `EndTurnButton.java:142-156 disable(isEnemyTurn)`：`if (isEnemyTurn) AbstractDungeon.player.endTurnQueued = true;`（**:151**）→ 真回合推进走 `AbstractPlayer.updateInput()` → `isEndingTurn` → `AbstractRoom.endTurn()`。
- `powers/TimeMazePower.java:51` 也是同一个按钮，同样强制结束回合。

**根因**：`Driver.drain` 只在 `Legal.apply` 命中 `PRESS_END_TURN_CARDS`（构建时只有 "Vault"）时置 `allowLatchedEndTurn`，其它牌即便按了结束回合按钮，沙盒也不跑 `room.endTurn()`：回合号停在 N、`endTurnQueued` 残留 true。于是搜索照旧规划第 N 回合的步骤（甚至再补一步「结束回合」），而实机已经进 N+1 → `Gameplay.planIsStale()` 为真 → `dropStalePlan()` 丢计划。用户看到的「条件已变化要重新计算」就是那条 status。

**参照 AutoWatcher-main**：`src/Cards/WatcherCardMirrors.A.cs:164-170` 把结末镜像成「伤害 + `V.ForceEndTurn(context)`（只在 `IsLastInSeries` 时）」；`src/WatcherVerbs.cs:255-270` 的 `ForceEndTurn` = `RequestPlayerTurnEnd()`，注释里记着同一类实机事故（结末后补的一张打击打不出来，下回合被渎神杀死），结论是「必须真的建模，只记风险不够」。

**修法**（不按卡名，按引擎事实）：
- `Driver.drain` 每帧读一个统一的闩锁 `endTurnLatched(gam)` = `gam.turnHasEnded || player.endTurnQueued || player.isEndingTurn`；
- `drain` 入口先记 `latchedAtEntry`：**开始时就已经置位的闩锁不归这次 drain 管**（那是玩家自己点的结束回合，走 `Driver.endTurn()` 的 `suppressRoomEndTurn` 路径）；
- 之后新出现（`!latchedAtEntry && latchedNow`）就结束回合，无论牌叫什么名字；`allowLatchedEndTurn` 退化成「这次结束回合是我们允许的」的旧开关，`PRESS_END_TURN_CARDS` 降级为提示名单（仍是 `{Vault}`，注释写明自 B89 起仅供参考）；
- 战斗已结束（`combatOver()`）时不再补推回合。

**门禁 S145**（两条路径）：
- `Conclude` 直接 `Legal.apply`：断言 `advanced=true`（回合 1→2）、`recharged=true`（能量回满 3/3、新回合抽了 5 张）、`damage=true`（伤害先落地）、`queued=false`（闩锁被消费掉）；
- 队列里塞一个匿名动作，`update()` 只 `player.endTurnQueued = true`：断言同样结束回合 —— 覆盖时间迷宫与任何 mod 卡（不依赖任何名单）。

**A/B（这是真 bug，不是门禁自己编的）**：把条件临时改回旧写法（只认 `allowLatchedEndTurn`）→ `S145 conclude: advanced=false recharged=false damage=true queued=true t=1->1 energy=2/3 hand=0`、power 那条 `advanced=false queued=true`，四条断言红；恢复后全绿。这也解释了用户那局的日志：实机进了第 2 回合，而计划里还是第 1 回合的步骤。

**顺带修的既有 flake**：S66 的「模拟角色存档写盘」也用 `Prefs.flush()` + 5 秒轮询等 AsyncSaver 队列，`-Full` 里红了一次（`a simulated stat flush lands in the sandbox directory`）。按 P2-59 的办法改成用引擎自己的 `com.megacrit.cardcrawl.helpers.File(filepath, json).save()` 同步写 + 字节比对。


## B90 — 包名当身份：mod 角色按 `com.megacrit.cardcrawl.` 前缀被当成原版（P6-03；✅ modchar 12/12，mod-characters=8）

**发现**：`-Mode modchar` 清点 `characterManager.getAllCharacters()` 时，第 7 项是
`class=com.megacrit.cardcrawl.characters.MerchantPlayer name=El estafador chosenClass=THE_MERCHANT` ——
ArlequinAndante（商人）把自己的角色注册进**游戏自己的包名**。旧判据 `cls.startsWith("com.megacrit.cardcrawl.")`
于是把它当原版跳过：不构造、不搜索、不差分，报告里却写着「mod 角色 7 个」，看起来是覆盖率齐了。

**同一判据的其余三处**（都是「输出与实际不符」）：
- `mirror/ModStatics.java#skipClass`：包里前缀直接跳过 → `MerchantPlayer.REWARD_CARDS`（`static final List<String>`）
  从未进过镜像报告；
- `modsupport/ModHooks.java#isModOwned`：面板的 mod 战斗钩子清单少列这些类；
- `drive/Driver.java:779`：包里前缀被当成「原版能力」→ 走小帧快进结束回合，mod 能力开屏时会漏动作。

**根因**：判据用**名字**判断「是谁的代码」。名字不可靠，来源才可靠。

**修法**：新增 `util/Provenance`：`isGame(Class)` = 该类名是否由**基础游戏 jar**（`desktop-*.jar`，从
`AbstractDungeon` 的 CodeSource 或 `user.dir` 找）提供；无 CodeSource 的运行期生成类算游戏侧。
四个调用点全部改用它（`ModCharProbe` 两处清点、`ModStatics#skipClass` 的后置判定、`ModHooks#isModOwned`、`Driver:779`）。

**第一版修错了（记录在案）**：只用 CodeSource 比较 jar → Ironclad/TheSilent/Defect 被 ModTheSpire 的补丁
classloader 重新定义，CodeSource 不是游戏 jar，于是三个原版角色被当成 mod（`mod-characters=11`）。
改成「游戏 jar 里有同名 class 就是游戏代码」后：4 个原版 `owner=game`、8 个 mod 角色 `owner=mod`。

**复测**：`MODCHAR summary: characters=12 mod-characters=8`；MerchantPlayer `deck=10 win=true steps=12
replay=12/12 live=match=12/12 raw=12/12`；`mod statics: ... 9 vanilla bundled`。

## B91 — mod 静态量报告在 40 条处静默截断，报出的数字不是真实数字（P6-06；✅）

**现象**：报告写 `NOT mirrored: 39 static-final container(s), 39 static object ref(s)`，看起来「只剩 39 条」。
**根因**：`addGap` 里 `if (into.size() < GAP_LIMIT)`（`GAP_LIMIT=40`）—— 两处都是 40 上限，超过的直接不记，
计数与名单一起被截断，于是数字恒等于 40 以下。真实数量是 **225 个 static-final 容器 / 103 个 static 对象引用**。
**修法**：不再限长（都是字符串，两百条无成本），并在打印时按名字/类型分三类：
`text`（DESCRIPTIONS/OPTIONS/TEXT/String[] 等，战斗中不会被写）、`art`（TEXTURE/IMG/ICON/Sprite…）、`other`。
只把 `other` 逐个点名 —— 只有它是需要人判断的。同一处的 `mod statics:` 行补 `N vanilla bundled`
（mod jar 里打包的原版类，跳过但要说出来）。

## B92 — 面板把药水显示成 `POTION#2`（✅）

**现象**：自动出牌/执行历史里出现 `POTION#2`，玩家不知道喝的是哪瓶。
**根因**：`Driver.Play.label()` 对药水只有 `"POTION#" + potionSlot`。药水喝掉后槽位就失效了，
到渲染时再查名字已经查不到 —— 名字必须在构造动作时抓。
**修法**：`Play` 新增 `public final String potionName`，构造时从 `AbstractDungeon.player.potions.get(slot).name`
读一次（失败退回槽位号）；`label()` 用药水名 + 目标名。


## B93 — 自动出牌门禁不能在一帧里跨回合（P6-02；设计边界，已写进门禁注释）

**现象**：S148 第一版让每个 mod 角色跑 3 步，结果 ninja 只到 2/14、CaiNong 与 aristocrat 卡在 1/12，
状态永远是「执行中 结束回合（等游戏推进）」，drift=0、没有任何异常。

**排查（逐层钉死，不是猜）**：
- `roomErrors=0` → 房间帧没抛异常，不是"没跑到"；
- 采样 20 秒：`turn=2`（结束回合**确实**推进了）、`queued=false`、`isEndingTurn=false`、
  `cardQueue=0`、`monsterQueue=0`、`screenUp=false`、`roomPhase=COMBAT`，但 `hasControl=true`、
  `engineSettled()=false` 一直不变；
- `javap` 反编译 `GameActionManager`：`hasControl=false` 只在
  `currentAction==null && room.phase==COMBAT && !usingCard` 时被置（`getNextAction` 之后那段）。
  也就是说**还有一个 currentAction 没走完**。

**根因**：`AbstractGameAction.duration` 是按**帧的 delta 时间**递减的。门禁体跑在同一个真实帧内，
循环里 `room.update()` 转一亿次 delta 都不变，所以那个动作永远不结束 —— 实测 `frames=8900万`、
耗时 22 秒、`settled` 始终 false。这不是 mod 的 bug，是"想在一帧里快进整场战斗"这个做法不成立。

**处置**：门禁改成只声明一步（`want=1`），并把理由写在代码里：这一步已经证明「mod 卡能按 uuid
在手里被找到、经真实引擎打出、Digest 与沙箱**逐字**相同」。多步/整场的一致性由真实帧驱动的
live 模式（autoplay）承担，不塞进门禁。三个 mod 角色实测全部 `verified=1/… drift=0`。

## B94 — `-Gates S147` 单独跑时 NPE：门禁默认"已经有一局"（✅）

**现象**：`-Gates "S147"` 报 `java.lang.NullPointerException at Probe$112.run(Probe.java:5093)`，
日志同一行写着 `starting at main menu`。
**根因**：S147 直接取 `AbstractDungeon.player.potions`。整套跑时前面的门禁已经造了局，所以没人发现；
被 `-Gates` 单独点名时停在大厅，`player` 是 null。凡"只跑一条"就红、整套跑就绿的门禁都是这一类。
**修法**：取药水前先判 `AbstractDungeon.player == null` → 自己 `Synthetic.build` 一局（Ironclad 起始牌组 +
大颚虫）再继续，日志留一行 `S147 built a board first: player=80`。


## B95 — 自动出牌和沙盒各写了一份"选哪张牌"，然后悄悄长歪了（✅ P6-08）

**现象**：同一个计划，沙盒 replay 能正确回答"弃两张打击"，自动出牌只弃掉一张。
**根因**：`Driver.resolveSelectionByNames`（沙盒）和 `Driver.resolveSelectionLive`（实机自动出牌）
是**两份复制粘贴的匹配逻辑**。沙盒那份带 `boolean[] used`（同一张实体牌只能选一次），
实机那份传的是 `matchOffered(candidates, null, ...)` —— `used == null`，于是
`"Strike_R,Strike_R"` 两次都命中同一张牌，`fillHandSelection` 收到 `[S, S]`，屏幕只消费一张。
**修法**：抽出 `public static java.util.List<AbstractCard> picksFor(candidates, cardIds, displayNames, choiceUuids)`
作为**唯一**匹配器，两条路径都调它（信念顺序 uuid > id+name > id > name，同一张牌一次答案里只能选一次）。
**门禁 S149**（4 条 check）：两张同 id 必须选到**两张不同**实体牌、三个同 id 只选出两张
（不重复取同一张）、`uuid` 优先于 id 决定是哪一张、屏幕没有的 id 一个都不选。
**教训**：「两条路径做同一件事」的那份代码，要么合成一个函数，要么迟早会分叉——而且分叉点会正好
在一类少见的输入（同名牌弃两张）上，跑常规局看不出。

## B96 — coverage 报告三处"说了假的"（✅ P6-10）

1. **同一张卡打印两次**：`card-stalled` 既在"按 bucket 逐个 report"的循环里打印，又在结尾
   手写了一次 `report("card-stalled", 20)`，日志里出现两块一模一样的 `card-stalled (2)`。
2. **两个 mod 角色假失败**：`sweepCharacters` 用无参构造造角色，aristocrat 和 thePirate 只有
   `(String, PlayerClass)` 构造器 → 报 `cannot construct player ... (ctors=1)`、`characters: ok=10 failed=2`。
   可 modchar 探针里这两个角色 `win=true live=match`。修法：和 `ModCharProbe` 用同一套
   —— 从 `CardCrawlGame.characterManager.getAllCharacters()` 找到注册实例，取它的 `chosenClass` 填进
   `Spec.playerClassEnum`（新增 `Coverage.chosenClassOf(Class)`）。修后 `characters: total=12 ok=12 failed=0`。
3. **一行 50KB**：`triggered through their real trigger: 453 [...]` 把 453 个名字拼成一行打印
   （实测 50KB 以上，把整份报告冲掉）。修法：`head(Set, 40)` 只点名 40 个，末尾 `... and 413 more`，
   计数保持精确。修后该行 2387 字符。

## B97 — `thePirate:BottledVoid` 在 `atBattleStartPreDraw` 抛 NPE = 覆盖率夹具现象，不是沙盒缺口（已归因）

`javap -c thePirate.relics.BottledVoid` 的 `atBattleStartPreDraw` 逐字是：
`AbstractDungeon.player.drawPile.group` 逐个迭代，比较 `card.uuid`（**static** 字段 `card`）与
牌的 `uuid`，命中就把牌移进 `limbo`。覆盖率在"无局"状态下造这个遗物，`static card` 自然是 null →
`card.uuid` 抛 NPE。真机上这个字段在玩家捡牌时就绑好了。
**为什么不是沙盒缺口**：`thePirate.relics.BottledVoid#card` 已经出现在 statics 报告的
"NOT mirrored: static object ref(s)" 名单里（只读用途：只拿来比 uuid，沙盒不会改它）。
**报告口径**：`relic-threw` 现在每个条目都带 `[during construct|onEquip|atPreBattle|atBattleStart|
atBattleStartPreDraw|cardPlay|atTurnStart|endTurn]`，所以这类"跑局外初始化"的失败一眼可辨；
`onEquip` 阶段的失败另计入 `onEquip-outside-run`，不再混进 `threw`。

## B98 — 牌的"身份"只有 uuid 和 id 两档：uuid 跨不进真机，id 分不出两张同名牌（用户 m02302/m02332/m02347）

**症状（两条，同一个根因）**
1. **搜索少了一整类分支**：`Legal.legalActions` 的去重键是
   `cardID|timesUpgraded|cost|damage|block|magicNumber` —— 不含 `misc`。原版 Blood for Blood（打出的次数）、
   Rampage（成长值）、以及多数 mod 卡都把计数器放在 `misc`，于是手里"同一张牌的两份，其中一份已经计数 5 次"
   被折叠成**一个分支**：搜索从头到尾没考虑过另一份，算出来的收益是一个不存在的局面。
2. **回放会打错牌**：计划里记的是 `uuid`（`Action.cardUuid`）。uuid 是**每个 fork 私有的**（沙盒新造的牌每个
   分支各自 `randomUUID`，真机也从没见过），所以回放只能退回 `id + name`；而 id+name 恰好**合并同名两份**，
   于是"计划算的是计数 5 的那张、真机打出的是计数 0 的那张"。这类错误不会立刻报错，只会让 Digest 在下一步
   悄悄漂移。

**判据（离线实证）**
- `javap -p com.megacrit.cardcrawl.cards.AbstractCard`：262 个实例字段，其中 `public java.util.UUID uuid`
  与 `public int timesUpgraded`、`public int misc` 都在；`uuid` 每张实体牌唯一，洗牌/换堆不变，**复制/新造
  必重新生成**（`grep randomUUID src` = 0：uuid 由游戏构造器给，mod 代码不参与）。
- 原去重键确实丢了 `misc`：`legalActions` 里能看到旧的行内拼接，改成 `CardPrint.of(c)` 后两分支并存。

**修法（四层"身份"）**
1. 新增 `src/main/java/sts1solver/mirror/CardPrint.java`：
   `of(c)` = `cardID[+升级] |c=cost/costForTurn |m=misc [|free] |d=baseDamage |b=baseBlock |g=baseMagicNumber
   [|h=baseHeal][|r=baseDraw][|x=baseDiscard] |t=damageTypeForTurn`，再接**反射附加字段**（`extraFields(Class)`
   按类缓存，遍历到 `AbstractCard` 为止，只收 int/long/boolean/float/double/short/byte/String/Enum，跳过
   static/synthetic/transient、`EXPLICIT` 名单与 `UI_NAMES` 模式）。mod 卡自己的计数器因此自动进指纹。
   - `digestExtra(c)` 只输出 `*misc` 与 `F`(freeToPlayOnce)：派生值 `damage/block/magicNumber` **不进 Digest**，
     那是"对最后 applyPowers 的那个怪"算出来的，跨引擎必出假漂移。
   - 读字段失败 → `Log.warnEvery("cardprint-read-"+name, 60000L, ...)`（`Log` 没有 warnOnce，只有 warn/warnEvery）。
2. `mirror/Digest.pile()` 改调 `CardPrint.digestExtra(c)`：**规则只有一处**，digest 与身份匹配器不能各有一套
   "同一张牌"的定义。
3. `search/Legal.legalActions` 的去重键 = `CardPrint.of(c)`；`Action` 新增 `cardPrint` 与 `choicePrints`
   （最短构造器 15 参，其余按元数委托），`Solver.expandScreens` 给每一屏的每个选项都记下 `Driver.printsOf(...)`，
   `Choice.choicePrints` → `Action.choicePrints` → 面板/自动出牌都带着。
4. **信念顺序**（`Driver.matchOffered` 唯一一处实现，三条路径共用）：
   `uuid` → **`print`** → `id + name` → `id` → `name`。`Legal.handCard` / `Legal.sameIdentity` /
   `Gameplay.liveCardFor` 同样 print 优先，且**同 id 多份而 print 都不命中时拒绝**（`ReplayException` 或返回 null），
   不再按手牌下标猜；只有"全场只有一份这个 id"时才允许退化成 id 匹配并打 `print-drift-<id>` 警告。

**边界（写下来，不假装全镜像）**：`damage/block/magicNumber` 这类派生值只在 `of()` 里以 `base*` 形式参与；
`isCostModified*`、`price`、`chargeCost`、`energyOnUse`、`ignoreEnergyOnUse` 不进指纹（前三个是 UI 红绿与商店价，
后两个是 Action 的输入）；`cardsToPreview` 只取 `cardID`（递归会成环）。

**验证**：门禁 S150（`Probe.java`，S149 之后）钉 6 条规则：两份同 id 但 `misc` 不同 → print 不同 / 匹配器选中
计划记的那份 / print 不命中时一个都不选 / uuid 真机没见过 + print 也能解析 / `legalActions` 两分支并存 /
多份候选且都不命中 → 拒绝猜测。配套 core 套件无回归。

**补记（同日，指纹收紧）**：第一版 `CardPrint.of()` 的反射附加字段把一批**派生/回合级/UI 级**字段也纳入了指纹
（`damage/block/magicNumber` 是按「最后 applyPowers 的那只怪」算的、`isCostModified*`/`isSeen`/`fadingOut` 会随绘制与时序乱跳），
跨引擎对比会产生**假的身份不一致**，把能安全执行的计划推成「多份候选都不命中 → 拒绝」。已把这些名字写进
`CardPrint.EXPLICIT`（派生值只用 `base*` 参与；`damageType/target/assetUrl` 由 cardID 决定，另行由卡库比较报告），
并把 `varData` 加进 `UI_NAMES`。复跑 core 套件 347/0，无回归。

---

### B99 同一个动作、两条路径的读法不同：面板路径只入队，沙盒路径已结算（S151 首跑误判）

**现象**：新写的 P6-11 门禁 S151（mod 药水：面板路径 vs 沙盒路径）第一次跑就 FAIL，两边 Digest 差在
`q=0/0/0` vs `q=1/0/0`、`pw=AmoXilin1` vs `pw=-`（沙盒里力量已生效，实机里还挂在动作队列上）。

**根因（不是产品 bug，是量法的 bug）**：两条路径确实走同一个游戏类（`drive/PotionDrink`），差别只在**结算时机**——
`Gameplay.submitPotion`（面板/自动出牌）把药水效果**入队**，由帧循环下一帧做；`Driver.usePotion`（沙盒）内部
`drain()` 当场做完。门禁在 `submitPotionForTest` 之后立刻读 Digest，等于拿「已结算」和「排队中」比。

**修法**：门禁在读 Digest 前先泵动作，口径与自动出牌的帧循环一致：
`for (int pump = 0; pump < 400 && !Gameplay.engineSettledForTest(); pump++) AbstractDungeon.actionManager.update();`

**验证**：S151 三瓶 ninja 药水 `AmoXilin=ok LexKelaPotion=ok NH42SO4=ok`，PASS；core 套件 `passed=348 failed=0`。
结论：mod 药水的面板路径与沙盒路径**本来就一致**（B92 之后的药水名/消耗/遗留力顺序都对），S151 是把这条钉住。

**教训**：写完任何「两条路径对照」的门禁，先问一句「两边读到的是同一时刻吗」；入队/结算的差别会被误读成分歧。



---

### B100 「加载不到的类」被当成原版代码：任何不存在的名字都算游戏自带

**现象**：全量套件（`-Full`）唯一一条失败是 `probe/Probe.java:444` 的
`Probe.check(ModHooks.countsAsMod("someMod.Subscriber"), "a third-party subscriber counts as a hook", "")`
——一个第三方 mod 的订阅者本该被算成"外部钩子"，实际被判成原版。core 套件不跑这条扩展门禁，所以
core 的 350/0 一直没暴露它。

**根因**：`src/main/java/sts1solver/util/Provenance.java#isGameName` 第一版写的是
`try { return sourceOf(Class.forName(name,false,loader)) == null; } catch (Throwable t) { return true; }`
——名字根本不在游戏 jar 里、又加载不到时，`Class.forName` 抛 `ClassNotFoundException` 落到 catch，
于是**"不知道"被判成"原版"**。B90 引入"按 jar 内容判归属"时，把这次查询当成了兜底，没注意它的语义是
"不能证明是 mod 就算原版"。

**影响面**：`modsupport/ModHooks.countsAsMod`（面板/日志的"外部钩子"清单）、以及任何用名字判归属的调用点。
真实 mod 类都 loadable，所以 modchar/mod 统计没暴露；一旦有"注册了但类没加载"的名字（或被卸载/未装 mod 的
配置残留），清单会静默漏掉。

**修法**：catch 返回 `false`。名字既不在游戏 jar 里、又加载不到 ⇒ **不能证明是原版**，按 mod/未知处理；
`isGame(Class)`（针对已存在的 Class 实例）保持"无 CodeSource 即补丁生成的原版类"的语义不变。

**验证**：全量套件 `passed=925 failed=0` —— 原来唯一失败的 `a third-party subscriber counts as a hook` 现在 PASS。

**教训**："默认值"要按**最坏情况**选：想知道"是不是 mod 的"，未知必须落到"不是原版"；
反过来把未知当归属已知，会让清单看起来完整却没内容——和 B91 的 40 条静默截断是同一类错。

---

### B101 A3 重建结果从不校验：走完不报错，就当它回到了搜索那一刻

**现象**：用户日志（m02901）里搜索找到一条线，A3 promote 重建时反复报
`the line cannot be replayed: 谁来都一样 [CaiNongMod:AllTheSame] ... is not in hand (hand=按兵不动+, 防御, 打击, 缩头, 拍点点, 打击, 防御, 嗦开嗦开, 防御)`
——重建出来的手牌比搜索当时**少一张**（10 → 9），于是计划里"打这一张"找不到牌，整条线作废、A3 白跑。

**根因**：`src/main/java/sts1solver/search/Solver.java#materialize` 只在**异常**时判定失败。逐步
`fork → install → Legal.apply → answerForReplay → trimPresentation → readBack` 只要都不抛异常，
它就把 `entry.digest`（搜索当时的状态指纹）**直接写进重建出来的 world**：`cur.setDigest(entry.digest)`。
也就是说"重建成功"的定义是"没有异常"，而不是"回到了同一个状态"。一旦某个动作在两条路径上落在不同状态，
置换表会把这个错状态当成那条线的状态继续搜/继续报，错的指纹还会被当成正确身份写进计划。

**修法**：`materialize` 里每一步在**fork 仍然 install 着**的时候读一次 `Digest.stable(Digest.ofCurrent())`，
走完与 `Digest.stable(entry.digest)` 比对：不等就 `++promotionMismatch`、打
`A3 promote: the rebuilt state is not the parked one (N actions, first difference at char K: parked=<...> rebuilt=<...>)`
并返回 null（宁可不 promote，也不把错状态当那条线）。另：异常分支的日志补上 `at step N on <action>`。
新增 `Solver.promotionMismatchForTest()` 计数。

**为什么不能只在失败时查**：失败日志只说"牌不在手"，说不出"是重建走歪了"还是"计划本来就错"。
比对指纹把这两种情况分开，并且**成功路径**也能查——静默错位正是从成功路径漏出去的。

---

### B102 一个动作可以停在动作队列中间，而"停在中间"不是一个状态

**现象（与 B101 同源，形态不同）**：`Driver.play` / `usePotion` / `endTurn` 收队列用的是
`drain()` = `drain(600, 250L)` ——**250 毫秒墙钟预算**。机器空闲时 250ms 足够，真机自动出牌时
（游戏还在 60fps 渲染、搜索堆 8GB、GC 停顿）同一个动作会在**自己队列的不同位置**停下。搜索侧
`Solver.expand` 之后还有 `Driver.pumpUntilSelection(24)` 会再泵 24 帧把队列推完，于是`parked` 状态是
"抽牌已经发生"；`materialize` 走同一步没有那次泵，停在 `DrawCardAction` 还在队列里 —— 两边差一张牌，
正好等于 B101 日志里"`AllTheSame` 不在手"的那种差。

**根因**：墙钟预算被判成"状态"。队列没走完时读到的 `hand/drawPile/powers` 是**过程量**，不是任何合法局面；
把它当状态存进 `Deferred.digest`，就等于给搜索塞进一个只有这次运行才会出现的局面。

**修法**：`Driver.completeIfTruncated(Drain)`。一次 drain 只要既没 `settled`、也没 `awaitingSelection`、
也不是 `refused`（= 被墙钟截断），就用**步数界**（`COMPLETION_STEPS=4000`，时间上界 1 小时只为兜死循环）
再收一次，让状态只由"从哪儿开始"决定。`play` / `usePotion` / `endTurn` 三个入口统一走它；触发次数记在
`Driver.truncatedDrainsForTest()`，并 `Log.warnEvery` 限速报告。墙钟预算从此只表示"愿意等多久才多花点时间"，
不再表示"停在这里就是个状态"。

**验证**：新增门禁 **S154**（`probe/Probe.java`）"a searched line walks into the same state twice, and no step
leaves its queue truncated"：造一局（Ironclad，seed=424242，JawWorm）→ 搜 6s → 同一条线**独立走两遍**，
逐步 `Digest.stable` 必须完全相同，且 `truncatedDrainsForTest()` 增量为 0。辅助 `s154Walk` / `s154FirstDifference`。

**教训**：任何"用时间卡住"的地方，只要结果被当成状态保存下来，就一定有跑不通的那一天。
时间可以是"放弃"的判据，不能是"状态"的判据；真正的边界要么是步数/事件，要么是明确的失败。

### B103 停牌回放的上限是整次搜索共享的一个闩锁，第一个轮廓用光后其余轮廓一条都回放不了

**现象（user m03616）**：面板报"前沿跑完，但没回放被挤掉的线，最好：…"，日志里有
`A3 deferred: kept=2000 promoted=0 earlier=0 limit=60 costMs=9 samples=310 budgetLeftMs=249998100`
—— 2000 条被挤掉的线，预算还剩 2.5×10⁸ 毫秒，一条都没回放，也没有任何说明。

**根因**：停牌阶段（B59 的"前沿干涸后去回放被 beam 挤掉的线"）的上限是**整次搜索共享的静态闩锁**：
`Solver.deferredPhaseMs=6000` + `deferredPhaseStarted`，`deferredPhaseBegin()` 只在第一次置时，
`deferredPhaseExpired()` 此后恒 true，`deferredPhaseCap()` 只 warn 一次（`deferredCapped`）。
`resetGuards()` 只在**每次搜索开始时**调用一次，而面板的切片搜索里
`PortfolioSession.advance()` 在同一个 resetGuards 窗口里**顺序跑 4 个轮廓**（每个 `new Session`）：
轮廓 1 用掉唯一的 6 秒 → 轮廓 2..4 的 `promote()` 一律在 `deferredPhaseExpired()` 早退，
连 warn 都不会再打。于是"共享的时钟"变成了"只有第一个使用者有时钟"。

**证据（离线可复现）**：`probe/BideProbe.java` 新增 `slicedPortfolio(AbstractPlayer)`，用
`Solver.resetGuards()` + `new Solver.PortfolioSession(w, 20000L, 0)` + `advance(40L)` 复刻面板切片。
修复前：`promoted=13 / 8 / 0 / 0`，轮廓 3 的那行是 `kept=2000 promoted=0 budgetLeftMs=2886`
（还有 2.8 秒）；修复后同一条线上是 `13 / 8 / 6 / 1`，拒绝原因分别是"到本次搜索的上限"和"预算用完"。

**修法（三处，缺一不可）**：
1. **上限按轮廓算，不按搜索算**：新增 `static long parkedCapFor(long passBudgetMs)`
   = `min(deferredPhaseMs, max(500L, passBudgetMs / 4))`。B59 要限制的是"一次停牌 pass 别把整段预算吃掉"，
   不是"整次搜索只准用 6 秒"；按轮廓预算的 1/4 夹紧，既保留 B59 又让每个轮廓都有自己的份额。
2. **计费只算回放本身**：Session 里记 `parkedCapMs` / `parkedSpentMs`，每次 `materialize` 用
   `System.currentTimeMillis()` 夹一次并把**这次重建的耗时**累加进去。第一版按墙钟算（从首次 promote 起
   算到现在），切片搜索里两个 promote 之间夹着整层节点展开，结果轮廓 1 只回放 4 条就报"用完 1750ms"，
   而它还剩 4.5 秒没用 —— 计费必须绑在"这件事本身"上，不能绑在"这件事之后过了多久"。
3. **拒绝原因单点记录**：`promote()` 拆成 `promote()`（包装 + 记录）与 `promoteOne()`（原逻辑），
   任何出口（含 `left<=0` / 配额满 / 上限到 / 全被跳过）都写 `promoteWhy`，由包装在每个轮廓打**一条**
   `A3 promote: nothing replayed (<原因>) kept= promoted= limit= layer= maxLayer= depth-skip= earlier-skip=
   duplicate= rebuild-failed= budgetLeftMs=`；计数器 `skipDepth/skipEarlier/skipDup/skipRebuild` 从方法局部
   提升为 Session 字段，早退时也能报。`Solver.parkedRefusal(...)` 把四个计数翻成一句话（永不为空）。
   `Session.finish()` 把原因写进 `Result.deferredWhy`，`PlanText.parkedNote(...)` 的新重载把它拼进面板：
   "还有 N 条被挤掉的线没回放（<真实原因>）"，不再一律建议"可以再算"（对共用上限、层数上限、重建失败，
   再算一次都是错的建议）。

**验证**：门禁 **S155**（`probe/Probe.java`）"the parked-replay refusal reaches the panel in words"：
有原因时面板必须写出该原因且不出现"可以再算"，无原因时保持旧建议；`parkedRefusal` 五个分支都非空；
`parkedCapFor(20000)==min(6000,5000)` 且 `parkedCapFor(0)==6000`。

**教训**：写进静态量的闩锁会被下一个使用者继承成"既成事实"。上限要绑在"这一次的预算"上，而不是
"这一整次搜索"上；计费要绑在"这件事花的时间"上，而不是"这件事之后过了多久"。



### B104 面板还在叫玩家点一个已经不存在的按钮（2026-11-02，用户 m03942 第 2 问）

- **现象**：预算不够、停车候选阶段被缩短时，面板状态写「…；已缩短停车候选阶段，点「加深」还能继续找」。面板上
  `PanelLayout.IDS` 只有 8 个键（solve/stop/step/auto/seconds/nodes/collapse/hide），「加深」在 B43 就删了
  （`Panel.java:413`「deep is gone: calculate itself spends the whole budget」）。玩家照做会发现无处可点。
- **根因**：B43 删按钮时同步了多数文案，这条分支（`Gameplay` 里 `deferredPhaseCapped()` 的那一支）漏了；
  `deepened()` 的 javadoc 也还写着「"加深" runs the same search with twice the budget」，让人以为按钮还在。
- **修法**：状态改成「…；停车候选阶段被缩短（原因见上），可点「计算」再给一轮预算」；`deepened()` 的 javadoc
  改写成事实（按钮已删，唯一调用者是 Shift+F8 `startDeepSearch()`，预算已 unlimited 时原样返回）；`Solver` 里
  B59 那段注释（"the panel offers 加深"）改成指向 `parkedNote` 的拒绝原因；`docs/JUDGMENT.md` 预算一节同步
  （「加深」= ×2 的说法删掉，改成 Shift+F8；`（可加深）` → `（可再算）`）。
- **验证**：jar 重装 sha256 `BCF7B49541D26B988A9412CCAEA39878C9C8E1BD38C9FC5F98D6AA7B5295A9BE`，core 套件
  `passed=361 failed=0`。
- **教训**：删功能时要连"文案里承诺这个功能"的每一处一起删——注释里的承诺会变成下一轮自己骗自己。


### B105 P2-62 的廉价一半：不复制，先看它到底有没有被写过（2026-11-03，用户 m04121「先做廉价的」）

- **现象**：P2-62 一直只有一个报告行——`mod statics: … NOT mirrored: 225 static-final container(s), 103 static object ref(s)`。
  这句话说明"没镜像"，但没有任何证据说明这些字段**是否真的在搜索里被写过**。写过的那部分会留在真机上（跨窗口泄漏），
  而 sibling fork 也会互相看见对方的写入。
- **为什么不能靠全量复制解决**：实测 `fork ≈ 21–26µs`（`clone=339ms/15749 次`，player 克隆是大头）；每 fork 对 600 个
  静态引用各做 3 个字段浅快照 = 1800 次反射 ≈ 50–90µs，会把 fork 成本翻 2–4 倍。字段描述符还擦掉了元素类型
  （"计数器表"和"注册表"长得一样），猜错就是把引擎单例复制给 mod。
- **修法**：新增 `src/main/java/sts1solver/mirror/StaticWatch.java` —— 装时快照、卸时比较，不复制热路径：
  1. 候选 = `ModStatics.watchCandidates()`（同一次 jar 扫描里收集的 `Field`，枚举/String/贴图类跳过），
     再减去 `Statics.mirrorKeys()` 已拥有的键（否则会把镜像自己的还原当成沙盒写）。
  2. 抽样：前 4 次安装全量抽样，之后每 128 次抽样一次；**被写过一次的字段进 dirty 集合，此后每次安装都只看它**
     → 未抽样窗口的成本是 0 次到几次反射。
  3. 比较四形状：collection（clear+addAll 还原）、map（clear+putAll）、array（逐元素）、普通对象（最多 32 个非
     static 字段）；静态引用被换成别的对象时，非 final 用 `field.set(null, 原值)` 放回、final 只报警。
  4. 首次发现即 `Log.warn` 点名并说明"它会被每次安装监视、P2-62 里这是 live-shared，兄弟 fork 能看到这次写入"，
     窗口末再一条 `Log.warnEvery("static-watch-window", …)` 汇总；`report()` 给一行 `static watch: K ref(s), N window(s), S sampled, W write(s) in D field(s) dirty=[…]`。
- **接线**：`World.install()` 末尾 `StaticWatch.afterInstall()`；`World.uninstall()` 开头（`if (!installed) return;` 之后）
  `StaticWatch.beforeUninstall()` —— 先对比放回，再拆窗口，写不能活过窗口。
- **门禁 S156**（3 条 check）：把 `Probe.S156_HOLDER`（static-final ArrayList，正是镜像不能替换的那种）交给 watch，
  安装→写入→卸载后断言 ①写入被点名 ②活内容回到 `[live]` ③首次安装即全抽样。实跑 `passed=3 failed=0`，
  日志 `static watch: the sandbox wrote mod static sts1solver.probe.Probe#S156_HOLDER (collection) and the contents were put back…`。
- **顺带修掉一个环境敏感的 flake（S153）**：`a fight the belt wins is not lost without it` 在机器被 MuMu/MAA 抢占时
  报 `with=false without=true`。根因不是药水，是**预算**：带药水的搜索分支更多，同样的墙钟买到更少的深度。
  修法：预算 8000ms → 20000ms，并新增 `limited(Result)` —— 任一侧停在 `time`/`nodes` 就不判 dominance，
  改报"这次比较被预算截断，不当成药水结论"。教训：**把"预算不够"和"策略更差"分开，否则门禁会在忙碌的机器上撒谎**。
- **教训**：报告"没镜像"不等于"被写过"。把"是否发生"变成运行时事实（检测 + 还原 + 点名），比再列一张人工清单便宜得多，
  而且新装 mod 自动覆盖——这正是用户要的"一劳永逸"里能做到的那一半。


### B106 「结束回合选牌」在计划里没有答案：展开与回放用了两套判据（2026-11-03，user m04431/m04473）

- **现象**：静默猎手 A20 的一场战斗里，点「计算」后卡死 2.5–3 秒又恢复。日志 4440 行
  `selection refused (not-offered: the screen offers [Deadly Poison], the plan chose [])` + 2220 行
  `A3 promote: cannot rebuild a deferred path (N actions) at step 6 on 结束回合`，1318 条停牌线全卡在同一步。
  计划里「结束回合」那一步的 `choice` 是**空**的。
- **根因（两套判据）**：展开侧用 `Driver.isAwaitingSelection()`（`HAND_SELECT` 要求 `numCardsToSelect > 0`），
  回放侧 `answerForReplay` 用 `Driver.selectionKind()`（只看 `AbstractDungeon.screen == HAND_SELECT`）。
  于是三种"屏在、但已经不在等答案"的形态被回放当成决策：
  ① `numCardsToSelect == 0` 的 anyNumber/upTo 屏（保留/弃牌"最多 N 张"靠 `upTo`/`anyNumber` 表达上限）；
  ② `wereCardsRetrieved == true`（牌已被动作取走，屏只是在播放关闭动画）；
  ③ 屏刚打开、还没轮到自己的 `update()` 把状态填好。
  展开侧遇到这三种就"没有屏"→ `choice=""`；回放侧遇到第一种之外的就"有屏要答"→ not-offered。
  另一半原因是**沙盒只泵 actionManager**：`pumpUntilSelection`/`pumpWhileScreenOpen`/`settleForReplay` 全部只调
  `AbstractDungeon.actionManager.update()`，从不调屏幕自己的 `update()`，所以"差一帧 prep"的屏在沙盒里永远等不到。
- **修法（不是跳过，而是真的把它变成决策）**：
  1. `isAwaitingSelection()`：`HAND_SELECT` 先排除 `h == null || h.wereCardsRetrieved`，再判
     `h.numCardsToSelect > 0 || h.upTo || anyNumber(h)`（`anyNumber` 是私有字段，用 `Reflect.find` 读）。
  2. `selectionKind()`：`HAND_SELECT`/`GRID` 分支先判 `if (!isAwaitingSelection()) return SelectionKind.NONE;`
     ——**同一个问题只能由一个函数回答**。
  3. `selectionOptions()`：新增 `upTo` 分支，生成「保留 0 张 / 最好的 1 张 / … / 最好的 N 张」累积前缀
     （含空选择；`want` 对 `upTo` 屏取上限而不是 1）。
  4. `pumpUntilSelection()` 新增 `pumpScreenSelf(4)`：屏在且未等待时，有界地驱动**屏幕自身**的 `update()`；
     输入已静音，驱动不会选中/关闭任何牌。
  5. `Solver.answerForReplay` 改用 `Driver.isAwaitingSelection()`（与展开同一个问题）。
  6. 拒绝日志追加 `Driver.selectionState()`（screen/numCardsToSelect/upTo/canPickZero/anyNumber/
     wereCardsRetrieved/numSelected/kind/awaiting），下次一出现就能判定是哪一种形态。
  新增 `Driver.selectionMaxCards()`（"最多能选几张"：anyNumber→手牌数、upTo→上限、否则 numCardsToSelect）。
- **门禁 S157**「an up-to-N hand-select screen is a real decision: keep 0..N, and the sandbox stops at it」6/6：
  ① `open("S157", 2, false, true, false, false, true)` 后 `kind=HAND`；② `awaiting=true`（数量是上限不是答案）；
  ③ `optionSizes=[0,1,2]`（保留 0 张是一个答案）；④ 保留满额也是一个答案；⑤ 装一个 `RetainCardPower(player,2)`
  后 `Driver.endTurn()` **停在保留屏上**（`screen=HAND_SELECT numCardsToSelect=2 upTo=true ... awaiting=true`）；
  ⑥ 此时 `branches=3`。
  实跑日志：`S157 up-to screen: … optionSizes=[0,1,2]`、`S157 end of turn: … branches=3`、
  `AUTOTEST RESULT passed=6 failed=0`。
- **jar**：`F379DBA80DA15852D1A12084ED0629144331CA6681EAD5C84987BD079260C3C5`。
- **教训**：①"屏还在"不等于"在等人答"，UI 关闭是好几帧的动画；②同一个问题（有没有要答的屏）只能有一个函数回答，
  两套判据必然在边界上打架；③沙盒里泵 actionManager 不等于泵 UI —— 屏幕自己的 `update()` 也得有人驱动。
- **还没做（独立问题）**：回放失败后的重试仍然会**逐条**试完整张停牌表并每条打两行日志（`A3 promote` +
  `selection refused`），1318 条就能把帧拖到 2–3 秒。修法方向：失败即标记死条目、`promoteOne` 遍历按墙钟分片、
  这两条日志限速。**等用户批准后再动**。


### B107 回放失败后的「重试」是纯浪费：删掉它，并把「这一步重放不了」学一次（2026-11-03，user m04644/m04656/m04659）

- **现象**：B106 那场战斗里，1318 条停牌线全部卡在第 6 步「结束回合」，8.3 秒打出 6660 行日志、帧 2–3 秒。
- **为什么「重试」毫无意义**：回放是**确定性**的 —— 同一个 root、同一份镜像、同一串动作、同一个缺失的答案，
  第二次和第一次完全等价。日志里 **2220 条失败、4440 行拒绝，文本一字不差**就是证据。promotedKeys.remove(key)
  把「失败」当作「没试过」，于是每一拍都重新问一遍同一个不可能。
- **为什么「删掉重试」还不够**：promoteOne() 一次调用就是**遍历整张停牌表**（直到有一条成功或表走完），
  1318 条各试一次 ≈ 1318×3ms ≈ 4 秒 —— 一个帧就 4 秒。**用时间上限去切它是掩耳盗铃**（用户 m04656 明确指出），
  所以真正的修法是：让「这一步重放不了」这件事**只被发现一次**。
- **修法**：
  1. 删掉 promotedKeys.remove(key)：失败即终态，key 留在集合里，之后一律进 skipDup（**不再重试**）。
  2. 新增「投毒」（Solver.rebuildPoison，key = 步号 + "|" + 动作 label）：materialize 失败且**原因是计划自身缺答案**
     （异常文本含 not-offered 且该步 Action.choice 为空）时记一次，并打一条 WARN：
     「A3 promote: step N '动作' cannot be replayed in this search (the plan carries no answer for it);
     every parked line that reaches it is skipped from now on, no retry」。
  3. promoteOne / 阻塞路径 solveInternal 在重建前先问 poisonedStepOf(entry.path)：命中就跳过，
     计入 skipPoisoned（Session 字段）+ Solver.promotedSkippedPoisoned（搜索级计数），**不花那 3ms**。
  4. 报告：parkedRefusal(depth, earlier, dup, rebuild, poisoned) 新增分支 ——
     「N 条被挤掉的线要走一个无法重放的动作（本搜索已确认那一步缺答案），跳过；另有 M 条重建不回当时的状态（不再重试）」；
     「A3 promote: nothing replayed (poisoned=…)」也带上计数。resetGuards() 清空投毒表（一次搜索学一次）。
- **只对「计划缺答案」投毒，不对「状态不一致」投毒**：后者（屏上候选不同、指纹不一致）可能对状态不同的兄弟线成立，
  把它写成「这一步永远不行」会**静默丢掉好线**。这条边界写进代码注释，是 B107 的正确性前提。
- **门禁 S158**「a step this search cannot replay is learnt once and skipped, never retried」7/7：
  用真实 solvePortfolio 的计划取 Actions → poisonStepForTest(1, …) → ①命中的线被按名跳过 ②同一步只学一次
  （poisonedCountForTest()==1）③没走到那一步的线不被跳过 ④面板文案含「无法重放/跳过」且**不含「可以再算」**
  ⑤重建失败文案含「不再重试」。实测：S158 poison: key=1|打击 → 大颚虫 hit=… count=1、
  S158 refusal: with=2 条…跳过；另有 3 条重建不回当时的状态（不再重试）。
- **代价**：投毒表 = 每次搜索几条字符串；命中检查是字符串比较（列表长度 = 不同失败步数，通常 0–1）。
  **风险**：①若某步的失败其实是状态相关而被误归成「计划缺答案」，会误跳过后面的线 —— 因此判定条件收紧到
  not-offered **且** choice 为空；②投毒是「每搜索一次」的缓存，resetGuards() 清空，跨搜索不复用。
  **对 mod 的影响**：不改变任何仿真判据与自动出牌；只让「重建不了」的线更快被判定并**点名**（面板/日志都有原因）。
- **jar**：49A8E2633D3ABA7B0417BE4E1760B9831D0D7B78E7BA2847ADD40B4ED7EBFD6A。
- **教训**：①确定性失败的重试是纯浪费，唯一价值是「发现」，那就只发现一次；②「每拍遍历整张表」是放大器，
  删重试只把 2 轮变 1 轮，必须同时消除「同一步被 1318 条线各发现一次」；③时间上限只能掩盖，不能解释。

### B108 B106 顺手留下的一个静默缺口：计划里有答案、屏却没出现，被当成「这步没问」（2026-11-03，user m04659 第 2 问）

- **现象**：B106 把 `answerForReplay` 的判据从「screen 枚举亮着」改成「屏在等答案」，写法是
  `if (!Driver.isAwaitingSelection()) { return; }`。这修好了「屏在关却被要求答」，但同时把
  「**计划这一步记了答案，回放时屏却没出现**」也变成静默返回 —— 那条线看起来"重放成功"，其实少做了一步。
  这正是用户 m04606 禁止的「把 bug 静默当修好」。
- **修法**（`search/Solver.java` `answerForReplay`）：分两种情况，不再一视同仁：
  - `a.choice` 为空 + 没有屏在等 → 这一步本来就没问问题，正常返回（B106 场景）；
  - `a.choice` 非空 + 没有屏在等 → 先 `Driver.pumpUntilSelection(8)`（屏可能晚一帧开），仍然没有就
    `throw new ReplayException("not-settled", "the plan answered a screen (" + a.choice + ") but none opened (" + Driver.selectionState() + ")")`。
- **门禁 S159**「an answer the engine never asks for is refused, not skipped」3/3：
  取真实计划的 `结束回合` 动作 → `withChoice("Defend_R","Defend_R")` 造一个"带答案但不开屏"的步骤 →
  `Solver.replay` 必须失败且 `lastReplayFailure()` 含 `not-settled` 与「none opened」。
  实测：`S159 fake answer: failure=not-settled: the plan answered a screen (Defend_R) but none opened
  (screen=NONE numCardsToSelect=0 upTo=false canPickZero=false anyNumber=false wereCardsRetrieved=false
  numSelected=0 kind=NONE awaiting=false)`。
- **代价**：每次"应有屏"的步骤最多多泵 8 帧（约 8 次 actionManager.update，通常屏第一帧就在，所以实际 0–1 次）；
  失败路径多一条异常，无性能影响。
- **风险**：①如果某个 mod 屏在回放环境里确实打不开（例如依赖 live-only 状态），过去会静默跳过、现在会让整条线失败
  —— 这是**正确**的方向（那条线本来就不该被当成可执行），但会改变"某条线看起来能用"的结论；
  ②泵 8 帧可能让一条本该失败的线成功 —— 属期望行为（屏确实晚开）。
- **对 mod 的影响**：不改变仿真判据；失败时日志/面板会写明"计划答了一个屏，但屏没开"，含完整屏状态，便于定位是
  哪个 mod 的屏。
- **jar**：`7C288BB91D9D1E905C34421E47FD99B32EB07DB7DBCFA1B83CF2878B695E9BFC`。
- **教训**：放宽一个判据时，必须同时问"原来那条路径上的失败，现在变成什么了"——它没有消失，只是换了出口。

### B109 阶段 1：镜像表可以在运行期接收一个字段（2026-11-03，user m04866）

- **要解决的问题**：内置镜像表是**编译期**的一张表（`Statics.ROOTS/CONTAINERS/…` + 生成表 + mod 标量）。没见过的 mod
  带来的静态字段不可能提前写进表里，于是只能靠人读代码审计 —— 这不可持续。
- **阶段 1 只做基础设施，不做判定**：
  - `Statics.registerDynamic(Field, Kind)`：把字段追加进 `dynamicSpecs`，`specs()` 在末尾合并它们（按 `owner#field` 去重），
    并使 `specs` 缓存失效；重复注册返回 false；非 static 拒绝；不可访问时 warn 并返回 false。
  - `dynamicGeneration`：每次注册/重置递增；`World` 在构造时记住自己捕获时的代际。
  - `World.install()`：代际不一致直接抛
    `this world was captured before a run-time static registration (N -> M); capture a new world from the live game`。
    **宁可炸也不静默漏镜像**（用户 m04606：禁止把问题静默掉）。
  - `dynamicCount()` / `generation()` / `resetDynamicForTest()` 供报告与门禁使用。
- **门禁 S161**「a field registered at run time is mirrored, isolates siblings, and a stale world refuses」8/8：
  注册成功 / 重复注册 no-op / `dynamicCount()==1` / 注册前捕获的 world 安装被拒 / 两个兄弟 world 互不可见
  （`S161 isolation: live=true siblingSaw=[live] clean=true`）/ 活内容恢复 / 注册后字段出现在 `specs()`（`specs=676`）/ reset 归零。
- **代价**：每次注册重建一次 `specs` 数组（一次搜索开始时做，可以忽略）；注册后该字段进入每 fork 的深拷贝（`CLONE`/
  `MUTABLE_CONTAINER`），成本等于该字段内容的大小 —— 所以"只注册确实需要的字段"仍然重要。
- **风险**：①注册时机受限（必须先于 world 捕获，否则 install 抛错）；②把注册表/单例注册进镜像会让 `==`/`instanceof` 失效，
  这是阶段 2/3 必须用实测复核的原因，阶段 1 自己不判定。
- **对 mod 的影响**：阶段 1 不改任何行为（没有调用者，仅门禁使用）；它是阶段 2/3 的地基。
- **jar**：`152E116EF2525DAA00F6C4784464A6BDCFC9B75546FEA9E450A17060064B8523`；core **393/0**。

### B110 世界泄漏把实机的角色存档重定向进沙盒（2026-11-03，user m05088/m05182）

- **现象**：打菜农中途退出后重开，游戏回到之前的猎手存档。
- **证据链**：
  1. `solver.log` 15:42:17–15:44:53 共 **25022** 条 `WARN a world was left installed; restoring the live game`；
     触发点 `java.lang.NullPointerException at com.megacrit.cardcrawl.monsters.exordium.SlaverRed.changeState(SlaverRed.java:146)`
     → `ChangeStateAction.update` → `GameActionManager.update` → `Driver.drain(Driver.java:460)` →
     `Driver.endTurn(Driver.java:909/875)` → `Legal.apply(Legal.java:205)` → `Solver.expand(Solver.java:813)`；
     同窗口还有 151 次 `ERROR engine update threw: phase=EXECUTING_ACTIONS turnHasEnded=true queues=3/0/0`。
  2. `preferences/sts1solver-sandbox/` 有 `player-CAI_NONG`(15:44:53)、`player-STSDataTheSilent`(15:47:14)、
     `player-STSDataVagabond`(15:29:30) —— 实机玩家的角色存档目标在**真机游玩时段**被重定向。
  3. `prefs-backup/20261103-152818` 的 `PENDING` 未被消费，快照里的 `CAI_NONG` 是 11-02 18:01 的旧版 ——
     **下一次 `tools/run.ps1` 会把它覆盖回 preferences**，把玩家当天的档案回滚。
- **根因**：`World.uninstall()` 是一段没有 try/finally 的直线代码。中途任何一步抛异常（这次的
  `SlaverRed.changeState` NPE 从 `Driver.drain` 冒上来），`installed` / `installedInstance` 与
  `PlayerPrefs` 的沙盒重定向就都不还原：之后每次 `install()` 都 `forceRestore()` 一次（25022 条），
  而实机的 `Prefs.filepath` 一直指着沙盒目录，游戏自己的存档写进了 `preferences\sts1solver-sandbox\`。
- **修法**：
  1. `mirror/World.uninstall()` 整体套 `try/catch/finally`：finally 里**必定**执行
     `PlayerPrefs.restoreInstall()`、`InputMute.pop()`、清 `live*/installed`、`ConsoleMute.pop()`、
     清 `installedInstance`；中途失败只 `Log.error` 不重抛。
  2. `World.forceRestore()` 的 catch 里也清 `installedInstance`（永不留下指向已卸载 world 的指针）。
  3. `mirror/PlayerPrefs.restoreInstall()` 逐项**校验**：若还原后 `filepath` 仍在沙盒下，强制写回原值并
     `Log.error`（含计数 `redirectLeaks()`）。
  4. 新增 `PlayerPrefs.recover(Prefs)`（显式修补一个泄漏的存档目标）与
     `PlayerPrefs.verifyNoLiveRedirect()`（健康检查；**跳过** SESSION/INSTALL 作用域，否则会把合成玩家的
     合法重定向改回真实文件 —— S66 门禁正是抓这个）。
  5. `mirror/SandboxPrefs.originalOf(String)`：`preferences/sts1solver-sandbox/player-X` → `preferences/X`，
     让"修回"有确定的落点。
  6. `tools/run.ps1`：启动时遇到 `PENDING` 快照**默认不还原**（移成 `PENDING.stale-<HHmmss>` 并打印），
     只有 `$env:STS1_RESTORE_PENDING=1` 才还原 —— 崩溃门禁留下的旧快照不再可能覆盖玩家的新存档。
- **门禁 S164**「a failed teardown cannot keep the live save target in the sandbox」：①强制让 `uninstall` 中途抛异常后，
  `World.anyInstalled()==false` 且 `PlayerPrefs.installScopeCountForTest()==0`；②一个指向沙盒的 `Prefs` 经
  `recover()` 后被修回真实路径。实测 `S164 recovery: fixed=1 back=preferences/STSDataS164`。
- **回归**：core **`passed=405 failed=0`**（S1–S164）；install sha256 `9E101592809964678ADDE65B7D63CDCBB22C4486C984BDDD1054F9811B10E4A3`。
- **代价**：uninstall 多一层 try/catch（可忽略）；每窗口结束多一次 `restoreInstall` 校验（遍历 INSTALL，通常 0–1 项）；
  run.ps1 多一次目录时间比较。
- **风险**：①若某个调用点**从不 uninstall**，这次修复管不到它（只能靠 `recover()` 在实际写入前修回）——
  所以把"世界泄漏"本身当独立问题继续跟；②`recover()` 只能把路径改回 `preferences/<name>`，若原路径有子目录后缀，
  还原的是同名文件（目前所有存档都在 preferences 根下，成立）；③run.ps1 不再自动还原 PENDING，崩溃后 preferences 会保留
  门禁写入的计数，需要人工 `STS1_RESTORE_PENDING=1` 或忽略（不影响存档本身）。
- **对 mod 的影响**：面板/搜索/自动出牌不变；唯一行为变化是"崩溃/异常后不会再留下沙盒存档重定向"。
- **仍未修**：`SlaverRed.changeState` 的 NPE 本身（动画 stub 缺 state），它是这次的触发源；以及"从不 uninstall 的调用点"。

### B111 动画替身没有 current entry，SlaverRed.changeState 直接 NPE（2026-11-03，user m05311 继续）

- **现象**：搜索里对红衣奴隶主结束回合时抛 `java.lang.NullPointerException at com.megacrit.cardcrawl.monsters.exordium.SlaverRed.changeState(SlaverRed.java:146)`，
  异常穿过 `Driver.drain` → `Legal.apply` → `Solver.expand`，把 `World.uninstall()` 打断（B110 的引信）。
- **反编译定位**（javap -p -c -l 显示 line 146 对应 `this.state.getCurrent(0)`）：
  `float tmp = this.state.getCurrent(0).getTime();` → `TrackEntry e = this.state.setAnimation(0, "idleNoNet", true);` → `e.setTime(tmp);`。
- **根因**：`mirror/AnimationMute` 给每个沙盒生物换上一个全新的 `new AnimationState(data)`，它从未被推进过，
  `getCurrent(0)` 就是 null；而且 `idleNoNet` 未必在该 skeleton 的动画集合里，`setAnimation` 会返回 null，
  下一行 `e.setTime(...)` 再 NPE 一次。
- **修法**：`AnimationMute` 增加内部类 `SafeState extends AnimationState`：
  - `prime()` 用 `getData().getSkeletonData().getAnimations().first().getName()` 调一次 `super.setAnimation(0, name, true)`，留下一个真实 `TrackEntry` 作 fallback；
  - 重写 `setAnimation(int,String,boolean)`：真实返回非 null 就用它并记为 fallback，返回 null（动画不存在）时把 fallback 交回去；
  - 重写 `getCurrent(int)`：真实值为 null 时给 fallback。
  这样引擎自己的行为全部保留（仍是真 AnimationState 的子类），只是不再把 null 交给调用方。
- **门禁 S165**「the animation stub survives SlaverRed.changeState」3/3：造一局 SlaverRed → 装 world →
  断言沙盒怪物的 state 与实机不是同一对象 → 直接调 `((SlaverRed) sandbox).changeState("idleNoNet")` 不抛且 `getCurrent(0)` 非 null →
  卸载后实机怪物仍持有原来的 state 对象。实测：`S165: swapped=true ok entry=TrackEntry`。
- **代价**：每个 skeleton 数据多一个 stub 的第一次 prime（一次 `setAnimation`，微秒级）；没有每帧成本。
- **风险**：①若某生物的 skeleton 完全没有动画，fallback 仍是 null——这种生物本来就不能播放动画，NPE 与之前一致，但不会更糟；
  ②重写 `getCurrent` 只影响沙盒替身，实机对象不受影响（S165 第三条断言盯着这一点）。
- **对 mod 的影响**：面板/搜索/自动出牌都不变；mod 生物只要走沙盒替身就同样受保护。
- **回归**：core **`passed=408 failed=0`**（S1–S165）；install sha256 `36BDF0353786C11B3E481881304F99F7675156FE1C95A252343207030DFDD850`。

### B112 门禁跑完会在玩家的 saves 里留下一份"合成自动存档"（2026-11-03，user m05182 的直接后果）

- **现象**：一轮门禁跑完后，D:/Steam/steamapps/common/SlayTheSpire/saves 里出现 IRONCLAD.autosave(135328 B, 16:15:59) 与 .backUp(8984 B, 16:15:22)——不是玩家打的局。
- **根因**：门禁在沙盒里打的是合成战斗；套件结束时 ModTheSpire 关闭游戏，引擎走"退出时保存当前局"的常规路径，而那一刻**没有任何 install 窗口**，ExternalMute 的 saves 重定向不生效，于是合成局被写进玩家真实存档目录；下次启动游戏会把这份假局当成"继续"。
- **修法**：新增 src/main/java/sts1solver/mirror/SaveGuard.java：
  - begin() 在套件开始时记录 saves 目录里已存在的文件名；
  - sweep() 删除此后新出现的每个文件（含 .backUp），并逐条 Log.warn 点名；玩家原有存档因在 begin() 快照里而不会被碰；
  - Probe.autoTest() 开头调 begin()，AUTOTEST DONE 之前调 sweep()。
- **门禁 S166** 3/3：门禁内写入 saves/S166-probe.autosave → sweep() 删除它且玩家文件不动（实测 S166 save guard: made=true swept=1 gone=true total=1）。
- **整轮验证**：core 411/0（S1–S166）；跑完整套件后 saves 目录只剩 CAI_NONG.autosave、CAI_NONG.autosave.backUp、steam_autocloud.vdf，没有 IRONCLAD 假局。
- **副作用清理**：已把 16:15 产生的 IRONCLAD.autosave 与 .backUp 移到 _tmp/quarantine-saves 留档（未删除，便于对照）。
- **代价**：套件开始一次目录列表、结束一次目录列表加每个新增文件一次删除；正常游玩零成本。
- **风险**：①若玩家在套件运行期间手动存了局（不可能，同一进程），会被当成套件产物删除；②saves 目录里若有子目录，sweep 跳过目录不动。

### B113 排查：先排除「防御模式 Sharp Hide」与「卡内强制结束回合漏算玩家能力」（2026-11-03，user m00002/m00330）

- **现象**：面板给出的最终血量比实机多 3（守护者 A4，嗦开嗦开 → 按兵不动+ 那一步；两份 Digest 除 `P hp` 外完全一致，之后每一步都高 3）。
- **两条先验假设与各自探针**（都写进 core，不再靠人工推断）：
  - S167「attacking a Sharp Hide 3 Guardian costs the player 3 HP in the sandbox」：造守护者一局 → 装 world → 手动挂 `ModeShiftPower(30)` + `SharpHidePower(3)`（模拟 Close Up 后的防御模式）→ 打一张攻击 → 断言 hp == hpBefore-3。**结果 1/1 通过**，Sharp Hide 反弹与伤害管线正确。
  - S168「a card that ends the turn still runs the player end-of-turn powers」：Vault + `CombustPower` → 卡内强制结束回合后断言 `atEndOfTurn` 真的跑过。**结论：跑了**（详见 B114 的实参修正）。
- 结论：这 3 点不在攻击/反弹，也不在「power 的 atEndOfTurn」——它在**结束回合触发的卡**（灼伤）上，见 B114。

### B114 沙盒从不跑「结束回合卡队列」，手里灼伤不结算 → 面板血量比实机多 3（2026-11-03，user m00002/m00330）

- **实机链路**（bytecode 已核）：`PressEndTurnButtonAction.update()` → `GameActionManager.callEndTurnEarlySequence()`（清 cardQueue、烧掉 limbo 里的自动打出卡）→ `EndTurnButton.disable(true)`：
  1. 先 `addToBottom(new NewQueueCardAction())`；
  2. 置 `player.endTurnQueued = true`；
  3. `NewQueueCardAction.update()` 在 `queueContainsEndTurnCard()` 为假时压入**空 CardQueueItem 哨兵**；
  4. `GameActionManager.getNextAction()` 读到 `card == null` 才调 `callEndOfTurnActions()`：`room.applyEndOfTurnRelics()` + `room.applyEndOfTurnPreCardPowers()` + `TriggerEndOfTurnOrbsAction` + **手牌每张 `triggerOnEndOfTurnForPlayingCard()`** + `stance.onEndOfTurn()`；
  5. 哨兵消费完、cardQueue 空后，`AbstractPlayer.updateInput()` 才置 `isEndingTurn`，`AbstractRoom.update()` 调 `room.endTurn()`（`applyEndOfTurnTriggers` → ClearCardQueue → DiscardAtEndOfTurn → EndTurnAction）。
- **沙盒的错误顺序**：`Driver.drain` 一看到闩锁（`turnHasEnded || endTurnQueued || isEndingTurn`）就立刻 `room.endTurn()`。此时哨兵还没被压入，`ClearCardQueueAction` 把随后压入的哨兵一起清掉 → `callEndOfTurnActions()` **永不执行**。`Driver.endTurn()`（走面板的普通结束回合）同理，只是它连 NewQueueCardAction 都没排。
- **为什么 S168 绿、这里却错**：`CombustPower` 走的是 `atEndOfTurn`（在 `room.endTurn()` 的 `applyEndOfTurnTriggers` 里），而灼伤走的是 `triggerOnEndOfTurnForPlayingCard`（只在 `callEndOfTurnActions` 里）。两条不同的入口，S168 只覆盖了前一条。
- **3 点的算术（逐位对得上）**：15 格挡（防御 5 + 嗦开嗦开 10）− 3 张灼伤 × 2（`DamageInfo` 由 Burn 直接 new，`DamageAction` 不调 `applyPowers`，所以 NoseNib 的 ×2 不参与）= 9；守护者旋风 4 段 × 3（5 × NongBai 0.75 = 3.75 → floor 3）= 12 → 漏 3 → 41−3 = 38，正是实机 Digest 的 `P hp=38`；沙盒认为 0 点漏过来，写 41。
- **修法**：`drive/Driver.java` 新增 `runEndOfTurnQueue(GameActionManager gam, long capMillis)`——最多 512 次、墙钟上限 `min(capMillis,1000)`，每轮 `pumpRoomObjects()` + `gam.update()`，直到 `actions`/`cardQueue`/`currentAction` 全空；抛异常只记 `endturn-queue-threw` 并返回，超时记 `endOfTurnQueueTimeouts`。两处调用：
  1. `drain` 的闩锁分支：在清 `endTurnQueued`、置 `isEndingTurn`、调 `room.endTurn()` **之前**先跑完队列；
  2. `Driver.endTurn()`：先 `addToBottom(new NewQueueCardAction())` 再造哨兵，跑完队列再 `gam.endTurn()` + `room.endTurn()`。
  队列跑空后 `ClearCardQueueAction` 已无哨兵可清，所以 `callEndOfTurnActions` 每条路径**恰好跑一次**，不会重复扣血。
- **门禁**：S169（Vault 强制结束回合 + 3 张灼伤 → hp 80→74）、S170（`Driver.endTurn()` + 3 张灼伤 → 80→74）各 1/1；S168 修正实参为 `CombustPower(player, 1, 5)` 后 1/1。core **415/0**（S1–S170）。
- **代价**：只在「结束回合」这一拍多跑一个最多 1000ms 的队列循环（正常 1–3 次 `gam.update`，微秒级）；每个沙盒节点结束回合都走，属搜索热路径，但上限固定、无新增分配。
- **风险**：①若某条线上 `callEndOfTurnActions` 之后仍有自动打出卡（灼伤自己入队），队列循环会把它一起跑完——这正是实机行为，但**搜索分支会因此变宽/变深**（结束回合里可能出现额外的伤害与弃牌）；②队列上限 512 与 1000ms 是硬边界，极端 mod 组合下会被截断（记 `endturn-queue-threw`/`endOfTurnQueueTimeouts`）；③回滚=去掉两处调用即可编译，`runEndOfTurnQueue` 可留作死代码。
- **对 mod 的影响**：面板血量预测不再少扣灼伤（按兵不动/金库/冥想/Conclude 这些「卡内结束回合」的牌同理）；自动出牌不变；搜索在结束回合节点更真（可能更慢，见风险①）；其它 mod 只要用标准 `triggerOnEndOfTurnForPlayingCard` 就自动正确。
## B115：面板在计算期间不给任何路线（user m00748 第 1 条）

- **现象**：点「计算」后，面板只写「计算中 · 已查阅 N 条世界线」，一直到整轮算完（可能几十秒）才把路线打出来。用户不知道现在到底有没有候选线。
- **不是 bug 的地方**：`lines()` 的 quick 分支本来就有一行「最优 N 步（未证明）」（步数，不是路线），pill 条要等 `finishQuick` 才有——因为 `buildRoute()` 依赖 `Step.turn/hp/monsterHp`，只有 replay 才产出这些字段，每 3 秒在渲染线程上 replay 一次会卡。
- **修法**（不回放、只读搜索树当前最优）：
  1. `search/Solver.java`：`Session.bestPath()` 返回 `best.path` 的副本（best 为空/路径空 → null）；`PortfolioSession.previewPath()` 优先取当前 member 的 `bestPath()`，否则取已结束 member 的 `best.plan` 里的 `Step.action`。
  2. `search/QuickSession.java`：`previewPath()` 按 narrow → wide → portfolio → `this.best.plan` 依次取。
  3. `Gameplay.java`：静态 `previewLine`/`previewSig`/`previewAfterMs`（默认 3000）；`tickQuick` 的 !done 分支调 `refreshPreview(elapsed)`；签名（动作 label 用 `\u0001` 连接）没变就不重建；`lines()` 的 quick 分支把它追加在「已查阅」行之后；`clearPreview()` 在发布/取消/重算/`reset()` 时清；`setPreviewAfterMsForTest(int)` 供门禁。
     初版把这条预览做成**单行文本**（放不下就截断成「最多 N 步」），理由是 `buildRoute()` 需要 `Step.turn/hp`。**B117 换掉了这个做法**：预览改为与算完相同的按回合分行 pill 路线条（靠 `isEndTurn` 切行，不需要 `Step`），详见下文 B117 一节。
- **门禁 S171**（B115 时 6/6，B117 后 7/7）：(a) `previewAfterMs` 设 10 分钟 → 整轮搜索期间都不该出现预览；(b) 设 0 → 必须出现「暂定路线（仍在计算，未回放校验）: 」且后面至少一个动作、发布后消失。首跑红是因为第二次搜索命中 plan cache（`plan cache: hit (-1947769434)`），补 `PlanCache.clear()` 后全绿。
- **代价**：每个 tick 一次 `previewPath()`（复制 ≤几千个 Action 引用）+ 字符串重建（仅签名变化时）；不 replay、不分配沙盒。`previewAfterMs` 之前零开销。
- **风险**：①预览是「未回放校验」的搜索树最优线，可能包含之后被判不出的步骤——所以文案里明确写了；②`PortfolioSession.previewPath()` 在 member 切换时可能短暂回退到已结束 member 的线（更旧但更可信），只是显示层；③回滚=删 `refreshPreview`/`clearPreview` 调用与三个访问器。
- **对 mod 的影响**：只改面板显示，不参与搜索/自动出牌；其它 mod 无影响。

## B116：菜农打 2 小偷只给 8 步、不算可赢（user m00748 第 2 条）

- **现象**（`C:/Users/YOKI/sts1-engine-solver/solver.log` 19:18 段）：`solve: QUICK win … complete=true`，但 `plan replay failed at step 9: 拍点点 … is not in hand (hand=防御, 尖塔武术+, 防御, 按兵不动+)` → 「这条线重放不通过（第 9 步），只给前 8 步」。实机执行到第 8 步 按兵不动+ 时连续 5 次 `WARN execute: 游戏拒绝打出 按兵不动+（cost=0 energy=0 turnEnded=false）`。
- **排查过程（都被证伪的假设）**：①「回放少泵帧」（`Driver.pumpAfterAction` 24 帧，S172 负向测试证明不需要，已删）；②「`usingCard` 闩锁卡住动作链」（真问题但不是这个症状，`Driver.leftoverUsingCard` + 拒绝闸门保留）。
- **真正根因**：mod 静态 `CaiNongMod.cards.BideTime#isUseAttack` **根本不在镜像表**。`mirror/ModStatics.build()` 用 `Provenance.isGame(type)` 判来源，而该实现（`util/Provenance.java`）在 `shipsInGame` 为 false 时退回 `sourceOf(c) == null`；ModTheSpire 的 `MTSClassLoader` 不给 mod 类 CodeSource → 该类被当成「mod 打包的 vanilla 类」，`vanillaBundled++` 后 `continue`（`mod statics: … 192 vanilla bundled`）。
- **后果链**：静态不进镜像 → 不随 `World.install/uninstall` 保存还原，是进程级全局（探针实测 t3 uninstalled 后仍是 true）→ 搜索里每个节点读到的是「上一个窗口」的值 → 本回合已打过攻击的节点仍提供 按兵不动+ → 回放/实机按 `BideTime.canUse()`（`!isUseAttack`）拒绝 → `not-offered`，计划断在该步，`planVerified=false`，只有前面几步能打印。
- **修法**：`Provenance.shipsInGame(String)` 改为 `public static synchronized`；`ModStatics.build()` 改成 `Provenance.shipsInGame(type.getName())`——扫 jar 条目时本来就知道名字，不需要 CodeSource。
- **验证**：bide 探针（`tools/run.ps1 -Mode bide -AllMods`）修前「这条线重放不通过（第 4 步：按兵不动+），只给前 3 步；不算可赢」，修后 `BIDE sliced numbers: deferred=1703 promoted=7 earlier=6 frontierExhausted=true cutShort=false win=true`、`BIDE sliced headline: 可赢（已搜完可用路线，最好：掉血 8）`；`BIDE mirror: field key=CaiNongMod.cards.BideTime#isUseAttack mirrored=true`、`BIDE flag t3 uninstalled: flag=false`；静态扫描从 `192 vanilla bundled` 变 `0 vanilla bundled`（365 字段 / 1009 类 / 17 jar，75ms）。
- **门禁**：S172（回放里开屏的动作必须自己走完链：Burning Pact 弃牌后跟一张 Strike 仍能重放，两天 steps=2/2、digest 相同）2/2；S173（无 CodeSource 的 mod 静态仍进镜像表、窗口内写它在 uninstall 后还原）加载 CaiNongMod 时 2/2，不加载时 skipped。
- **代价**：`ModStatics.build()` 每次启动多判 365 个字段的名字（本来就要遍历，无新增 IO）；镜像表多 1 个字段 → 每个世界窗口多读写 1 个 boolean，可忽略；S173 只在有 mod 时跑一遍捕获/安装/还原。
- **风险**：①`Provenance.isGame` 的语义从「有 CodeSource 且不在 game jar」变成「名字不在 game 名单」（mod 里若真有和 game 同名的类，行为从「当作 vanilla 跳过」变「当作 mod 镜像」——这正是我们要的，但极端情况下会给 vanilla 类建镜像，由 `shipsInGame` 的名单兜住）；②镜像字段增多 → 每次 install/uninstall 的读写量线性增加（当前 365 字段，量级可忽略）；③回滚=改回 `Provenance.isGame(type)` 并保留 `shipsInGame` 为 public。
- **对 mod 的影响**：面板/自动出牌/搜索在「本回合能不能打 按兵不动」这类判断上与实机一致；`BideTime` 之外，所有 MTS 加载、无 CodeSource 的 mod 静态字段都会进入镜像（修前是静默跳过）——对其它 mod 是更正确的隔离，不改变它们的运行逻辑。

## B117：计算中的预览只有一行文字，不像算完那样有正常路线条（user m01814 第 2 条）

- **现象**：B115 的预览是一行 「暂定路线（仍在计算，未回放校验）: A → B → …」，最多截断到 12 步；算完之后却是按回合分行、带 HP/能量/格挡、可点击的 pill 路线条。用户要求「像计算完毕那样给出一个正常的面板，而不是现在的一行。只要之后能更新就可以」。
- **为什么不能直接复用 `buildRoute()`**：它按 `Step.turn` 分组、用 `Step.hp/monsterHp` 算每行统计，而这些字段只有 replay 才产出；每 3 秒在渲染线程上 replay 一次会卡（B114 已证明强制结束回合那一步 replay 很贵）。
- **修法**（纯显示层，不回放）：
  1. `Gameplay.java` 新增 `previewRoute`（`List<RouteRow>`）与 `PREVIEW_ROWS = 8`；`buildPreviewRoute(path)` 按 `Action.isEndTurn()` 切行、行号从 `GameActionManager.turn` 起、pill 的 step 记路径下标、超过 8 行就截断（标题行会写「只显示前 N 回合」）。
  2. `publishPreviewRoute()` 把预览行灌进面板真正读的 `route()/rowSteps`；`lines()` 的 quick 分支在预热后每次调用发布一次（一次 ≤8 行的列表拷贝）。
  3. `ui/Panel.java` 的 `hasPlanNow` 改成 `currentPlan() != null || Gameplay.previewActive()` —— 所以预览走的是和正式计划完全相同的绘制路径（同样的行标题、同样的 pill 高亮/滚动）。
  4. `clearPreview()` 一并清掉 `previewRoute` 与 `route()/rowSteps`，正式计划发布后不会残留两套路线。
- **已知近似**：卡内强制结束回合（按兵不动、金库、冥想、Conclude）在路径里看不出来（`Legal.PRESS_END_TURN_CARDS` 自 B89 起只含 `Vault` 且仅作提示），所以预览会把那两个回合并成一行、后续回合号可能偏 1；标题行写明「仍在计算，未回放校验」。
- **门禁 S171**（7/7）：(a) 预热时间设 10 分钟 → 整轮搜索都不出现预览且 `previewActive()==false`；(b) 设 0 → 出现预览，且**抽样必须是路线条**：`previewActive()` 为真、行数 > 0、pill 总数 > 0、首行 label 以「第 」开头；发布后预览行与路线条都消失。断言必须在循环里与预览文本同一拍采样 —— 搜索结束后 `finishQuick` 已清空，事后读只会是 false。
- **代价**：仅在预览存在时，每次 `lines()` 复制 ≤8 行 RouteRow（引用拷贝）；签名未变不重建行；不 replay、不分配沙盒。无预览时零开销。
- **风险**：①回合分行靠 `isEndTurn`，卡内强制结束回合会少分一行（见「已知近似」）；②pill 的 step 是路径下标，点击时 `requestStep` 因 `plan==null` 直接返回 false，不会误跳；③预览标题仍带「未回放校验」，不声称可赢；④回滚=把 Panel 的 `hasPlanNow` 改回只看 `currentPlan()` 并删掉 `buildPreviewRoute/publishPreviewRoute/previewActive`，预览退回单行文本。
- **对 mod 的影响**：纯显示层。搜索树、自动出牌、计划执行、其它 mod 均不变；区别只是计算期间玩家看到的是和算完后同款的路线条。
- **验证**：S171 7/7（`rows=1 pills=3 active=true`，首行「第 1 回合」）；core **425/0**（S1–S173）；jar sha256 `D3E8853B913F135CA96C27800FB92CD40225326B607F66D0DF47210D5528A38A`。



## B118 计算中的预览没有血量（user m01978）

**现象**：B117 之后计算一开始（3 秒后）面板就出按回合分行的暂定路线条，但只有牌的标签、没有血量；算完的正式面板有「预计 -N HP / 掉血 N」，预览没有。玩家看到路线也没法判断这条线值不值得等。

**要求**：「预览加上预计最终的血量（就和正常面板一样显示预计掉多少血）」。

**为什么不回放**：正式面板的血量来自 `Step.hp`（回放每一步才有），预览在渲染线程上每 3 秒回放一次会卡帧——B115 已经因此选了「只拼标签」。所以血量必须从搜索自己持有的节点上直接读。

**修法**：

1. `search/Solver.java`：`Session` 加 `public int bestHpLoss()`（`best == null ? -1 : best.hpLoss`，紧跟 `bestPath()` 之后）；`PortfolioSession` 加 `public int previewHpLoss()`（`current.bestHpLoss()` 优先，否则 `best.hpLoss`）。`Node.hpLoss` 就是搜索根开始累计的掉血，和 `Result.hpLoss` 同一口径。
2. `search/QuickSession.java`：`previewHpLoss()` 与 `previewPath()` 同一优先顺序（narrow → wide → portfolio → best）。
3. `Gameplay.java`：`refreshPreview()` 里取 `quick.previewHpLoss()` 存 `previewHpLoss`，再用 `hpPreviewText(int)` 拼进标题：「 · 预计 -N HP → 剩 M HP」（M 是当前实机血量减 N，clamp 到 0；无玩家时只给「预计 -N HP」）；0 写「 · 预计无伤」；-1 什么都不加。`clearPreview()` 重置为 -1。

**门禁**：S171 加一条断言「the preview says what the line is expected to cost in HP」——抓到的预览文本必须含「预计」且含「HP」或「无伤」。实跑预览：`暂定路线（仍在计算，未回放校验）· 3 步 · 预计无伤`。S171 8/8；core **426/0**（S1–S173）。

**代价**：每个切片多一次 `previewHpLoss()`（几个 getter，无分配、无回放）；预览字符串只在签名变化时重建一次。

**风险 / 边界**：①预览血是**整条线的累计**（和算完面板 headline 的「掉血 N」同口径），不是逐回合数字——逐回合数字要回放，代价是渲染线程卡顿，所以不做；②搜索没到任何可赢/可完成节点时 `hpLoss` 是当前最好节点的累计值，会随搜索变（这正是「暂定」的含义）；③实机血量在预览期间可能被玩家自己的操作改变，`剩 M HP` 用的是当下血量。

**对 mod 的影响**：面板预览多一段血量文字；正式面板、自动出牌、搜索行为都不变（纯只读 getter）。

---

## B119 游戏暂停时，计算没有暂停（user m02060）

**现象与要求**（m02060）：「游戏暂停的时候计算也暂停」。改前按 ESC 打开暂停菜单，搜索照跑：一个 60 秒预算的搜索，玩家暂停 30 秒回来，进度条已过半、再过几秒就被截断收尾——玩家等于用一半预算换了后面一局的答案。

**暂停是什么**：游戏里按 ESC / 点齿轮开的就是 `SettingsScreen`。`AbstractDungeon.CurrentScreen` 枚举里**没有 PAUSE**，`SettingsScreen.open()` 做的是 `isScreenUp = true`、`screen = CurrentScreen.SETTINGS`（TopPanel 在 COMBAT_REWARD/GRID/SHOP/MASTER_DECK_VIEW/SETTINGS/MAP 之外的画面才转发给它）。所以判据是 `isScreenUp && screen == CurrentScreen.SETTINGS`，不是「有屏」。

**为什么只「暂停时不推进」不够**：预算全是墙钟。`QuickSession.startedAt`、`Solver.Session.startedAt`、`Solver.PortfolioSession.startedAt`、`Solver.deferredPhaseStarted`、`Driver.selectionSinceMs` 都按 `System.currentTimeMillis()` 算差值；`Gameplay` 里还有 13 处时间戳（`quickStartedAt`/`deepStartedAt`/`planBuiltAt`/`lastActionAtMs`/`nextActionAtMs`/`pendingDeadline`/`pendingChoiceUntilMs`）。暂停期间这些差值照长，恢复的第一帧就判定「预算已用完 / 死线已过」。

**修法**（两层一起才成立）：

1. `Gameplay.tick()` 最前（`FrameStats.tick()` 之后、deep/quick 之前）：暂停菜单在 → `pauseForMenu()` 记起始时刻、日志一行、有搜索时面板写「计算已暂停（游戏暂停中）」并 `return`；菜单关 → `resumeFromMenu()` 算出暂停时长，`pausedTotalMs += ms`，并把它交给 `quick.compensatePause(ms)`、`deep.compensatePause(ms)`、`Driver.compensatePause(ms)`。整个 tick 提前返回也意味着**自动出牌、步执行、开屏应答、待处理动作全部一起停住**（这才是「和游戏一起停」）。
2. `Gameplay` 自己换成可暂停时钟：`now() = System.currentTimeMillis() - pausedTotalMs`，13 处决策用时钟点全部改用 `now()`；暂停期间面板不画「计算中 N%」那种会冻住的进度条（`lines()` 在暂停且有搜索时只给「[计算已暂停]」两行，冻住的进度条会被读成卡死）。
3. 各预算类新增 `compensatePause(long ms)`：`Solver.Session`（`startedAt` 去 final，顺带挪 `deferredPhaseStarted`）、`Solver.PortfolioSession`（自己的 `startedAt` + 转发给正在跑的 member）、`QuickSession`（`startedAt` + `lastProgressAt`，再转发 narrow/wide/portfolio）、`Driver`（`selectionSinceMs`，结算窗不该在暂停里走完）。把起点往后挪就是「这段时间不算数」，所有由它派生的死线自动正确，不需要动任何派生数字。

**门禁 S174**（`probe/Probe.java`）：造 JawWorm 战斗（seed 4343，预算 4s）→ 跑到有节点、`elapsed>0` → 把 `screen` 设成 SETTINGS 且 `isScreenUp=true` → `Gameplay.tick()`、`Thread.sleep(250)`、`Gameplay.tick()` → 断言：`pauseMenuUp()`、`pausedByMenu()`、面板文本含「暂停」、节点数不增（52→52）→ 关掉菜单 `tick()` → 断言 `!pausedByMenu()` 且 `elapsed` 只涨 40ms（250ms 暂停没被算进预算）→ 再跑到结束，断言搜索仍能自己收尾。实跑 **8/8**；core **434/0**（S1–S174）。

**代价**：每帧多两次静态读 + 一次 `isScreenUp/screen` 比较（纳秒级）；暂停期间**完全不做搜索**，所以暂停不计入预算、也不掉节点；`compensatePause` 只在恢复那一帧调用一次（几个 long 加法）。内存零新增（三个 long + 一个 boolean）。

**风险 / 边界**：①判据认的是 SETTINGS 屏。若某个 mod 也把 `screen` 设成 SETTINGS 又真的在跑战斗，会误判成暂停——本 mod 自己不开这个屏，门禁里几处把 screen 设成 SETTINGS 的输入静音检查不调 `tick()`，已核对；②暂停菜单里改音量/重绑键不推进游戏，按我们的定义就是暂停，恢复时一并补偿（符合预期）；③「游戏窗口失焦」不算暂停（游戏本身还在跑，也不该停）；④暂停期间玩家在菜单里操作（比如退出战斗）不会被我们拦；⑤回滚：`Gameplay.tick()` 顶部两段 gate 去掉即恢复旧行为（其余 `now()`/`compensatePause` 都是惰性的，不回滚也不会算错）。

**对 mod 的影响**：面板——暂停时显示「[计算已暂停]」两行，恢复后接着显示进度（百分比不跳变）；自动出牌——暂停期间不再落子，恢复后按原节奏继续（结算窗也被顺延，不会一恢复就误判）；搜索结果——**更准**，暂停不再吃预算，等于同样的预算全部用于搜索；其它 mod——只读 `AbstractDungeon.screen/isScreenUp`，不碰它们的字段，也不会因为别的屏（图鉴/弃牌堆/地图）而暂停。


## B120 已证明零伤获胜的切片搜索不停止（user m02336）

**现象**：面板预览写「预计无伤」，搜索却继续跑（用户原话：「为什么无伤了还在计算？不应该已经最优解吗」）。当时日志里 quick=solve、turn=1、约 900 节点/秒，25 秒已经 2.4 万条世界线，却一直不给正式路线。

**两层原因**：

1. **切片搜索从不读 provenOptimal**。`Solver.Session` 的字段注释（`Solver.java:2792-2793`）一直写着「keep expanding after the first win for up to REFINE_LAYERS more layers, stop immediately on a proven zero-damage win」，但 `microStep()` 只在 stopRequested / 时间 / 节点 / 层数 / 前沿干涸 五处收尾；`provenOptimal` 只在 `Solver.java:3199-3201`（`n==0 && n2==0` 的胜利）被置位，没有任何地方读它来停。对照：不分片的 `solve()` 在 `Solver.java:685-688` 有 `if (best != null && best.win && best.provenOptimal) break;`，`PortfolioSession` 在 `Solver.java:3562-3566` 也停——**只有面板跑的切片路径漏了**。`REFINE_LAYERS`（`Solver.java:41`）全库没人用，只是一句注释。
2. **文案把半条线读成了最优**。「预计无伤」来自 `best.hpLoss == 0`；那条线若还没打到胜利，它只表示「目前还没掉血」。

**修法**：

- `Solver.Session.microStep()` 在节点预算检查之后、层数检查之前：`if (this.provenOptimal && this.best != null && this.best.win) { this.reason = "win"; this.done = true; return; }`。零伤且不喝药的胜利不可能被改进（与参考实现 `IsProvenZeroDamageRoute` 同一条规则），所以答案到此为止。
- 新增 `Session.bestIsWin()`、`PortfolioSession.previewWin()`、`QuickSession.previewWin()`（与 `previewPath` 同一条优先级链）。
- `Gameplay`：`previewWin` 进签名（胜利状态变化要重建行）；`hpPreviewText(int hpLoss, boolean win)` 在未胜利时写「 · 目前掉 N HP（还没打到胜利）」，胜利且 0 才写「预计无伤」。

**门禁 S175**（`probe/Probe.java`）：JawWorm（seed 5175，80 HP）→ 窗口内给玩家 +60 力量（起手一击必杀）→ `readBack` → `Solver.Session(root, 24, 96, 120000L, 0, 0)` 跑到收尾 → 断言 `win && provenOptimal` 且 `nodes() < 400`。

**反向验证（门禁有牙）**：把那段 `if` 改成 `false &&` 重编再跑 → nodes=**5265**、第二条 FAIL；恢复后 nodes=**2**、2/2 PASS。

**代价**：一步一次布尔比较（纳秒级）；**反而更省**——零伤胜利不再展开剩余前沿（5265 → 2 个节点，省掉整片搜索）。内存零新增（一个 boolean + 两个访问器）。

**风险 / 边界**：①零伤胜利不再继续找「同样零伤但回合更少」的线——与不分片路径和参考实现一致；②`best.win` 作为额外保险，即使 provenOptimal 被置位也不会停在一个非胜利节点上；③如果某张卡/能力让 hpLoss 记 0 但实战会掉血（例如伤害绕过引擎记账），这里会提前收尾——这类状态本来就会让搜索结果失真，属已知边界；④回滚：删掉 `microStep` 那段 if（访问器与文案可单独保留）。

**对 mod 的影响**：面板——零伤最优线会很快出正式路线，不再长时间「计算中」；预览文案在未胜利时明确写「还没打到胜利」，不会再让人误以为已是最优；自动出牌——计划更早发布，行为不变；搜索结果——更早收尾，且因为省掉剩余前沿，同一预算下平均更快；其它 mod——只动本 mod 自己的搜索循环与面板文本。

## B121 计算中的预览会显示半条线（user m02524）

**现象**：B115/B117/B118 的预览一旦搜索手里攒出一条路径就发布，于是面板会出现「预计 -N HP（还没打到胜利）」这种半条线——用户明确说：「目前掉 N HP（还没打到胜利）就不应该给我这样的线。我一直都强调要走完不要给我打一半的线」。

**要求**：预览只允许显示**已经打到胜利**的完整路线；没胜利线就只显示进度。

**为什么不能显示半条线**：预览的路径是搜索当前的 best 前缀，它没有经过 replay 校验（B115 的标题行就写着「未回放校验」），也没有后续动作能保证这一回合之后还打得赢；用户看到一条半条线会以为那就是计划。

**修法**（`Gameplay.java`）：`refreshPreview` 在拿到 `quick.previewPath()` 之后加闸门——`if (!quick.previewWin()) { clearPreview(); return; }`，即没有已胜利的完整线就撤掉预览、只留进度行；`hpPreviewText` 回到单参（半条线文案「目前掉 N HP（还没打到胜利）」删除）；`previewWin` 字段删除、签名不再带胜利位；新增 `previewWinForTest()` 供门禁断言。访问器来自 B120（`QuickSession.previewWin`/`PortfolioSession.previewWin`/`Session.bestIsWin`）。

**门禁**：S171 改成 3 只不加强度的小怪（LouseNormal/LouseNormal/LouseDefensive，seed 4242）——单怪/加强度会被 B120 的 provenOptimal 在 3–70 节点内收尾，胜利线只在最后一片出现、预览采不到样；这版搜索跑到 970 节点/373ms，预览在 8% 处出现（「暂定路线（仍在计算，未回放校验）· 10 步 · 预计 -6 HP → 剩 74 HP」）。S171 共 10 条断言，新增「预览那条线必须已胜利」「永远不出现『还没打到胜利』」。新门禁 S176：5 张防御 vs 大颚虫（打不赢），整轮循环里 lines() 都不许出现「暂定路线」，且结束后 previewActive() 为假。实测 S171 10/10、S176 1/1，core **439/0**（S1–S176）。

**代价**：①性能：搜索手里没有胜利线时每帧只多一次 previewWin() 调用（读字段），可忽略；有胜利线时和 B117/B118 一样每帧可能重建一次预览文本（只在签名变化时）。②代码：Gameplay 少一个字段、一个双参方法；多一个 previewWinForTest。③搜索：无影响。

**风险**：①打不赢的战斗现在整轮没有任何路线条，只有进度——这是用户要的行为，但若某局其实能赢而搜索迟迟找不到胜利线，面板会看起来「什么都没给」；②预览比 B118 更晚出现（要等第一条胜利线），在很难的局里可能接近搜索尾声；③回滚：把 refreshPreview 的 previewWin 闸门去掉即可（单文件一处），其余访问器可留。

**对 mod 的影响**：面板——半条线消失，预览只在有完整胜利线时出现，「预计无伤」现在只可能来自已胜利的完整线；自动出牌——不受影响（预览只是显示，计划仍由 currentPlan() 决定）；搜索——不受影响；其它 mod——无。

## B122 昏睡打出后本该跳过下一回合却没跳过；面板不显示被动结束回合（user m02740）

**现象**：①用户原话：「昏睡这张牌打出后下一回合应该直接结束回合（你自己看效果）但是模拟没模拟出效果」。实测那一局（22:02–22:16，乐加维林）计划里 `… | 恶心观众+ | 昏睡 | 结束回合 | 拍点点 → 乐加维林 | …`——昏睡之后的下一回合仍在出牌；实机 TrancePower 会在回合开始直接按结束回合按钮，所以那一回合应该整回合作废。②用户原话：「结束回合无论是主动的还是被动的都要进面板，出牌也是的」。面板按 `Step.turn` 分行，被动结束回合会让回合号跳号（例如 N 直接到 N+2），被跳过的 N+1 那一行整行消失，看起来像是漏了一步。

**根因**：`Driver.endTurn(long)` 在 `gam.endTurn()` / `room.endTurn()` 之后把 `suppressRoomEndTurn` 置 true 再 drain，用来挡「进入 drain 前就已经闩住的那一次」。而昏睡那一次闩锁（`TrancePower.atStartOfTurn` → `PressEndTurnButtonAction` → `EndTurnButton.disable(true)` → `player.endTurnQueued=true`）恰好发生在 drain 内部，被同一条件一律挡掉：`endTurnQueued` 留在 true 没人消费，沙盒继续把实机已经结束的回合打完。探针实测：`turn=1->2`（应为 1->3）、`endTurnQueued=true isEndingTurn=false`，同时 TrancePower 扣层与回血（40→64）都已执行——即能力跑了、回合没结束。

**修法**：
- `drive/Driver.java`：drain 每 tick 只看「还没被消费的按钮按下」`boolean pressNow = endTurnQueued(gam);`，命中就跑 `runEndOfTurnQueue` + `room.endTurn()`，不再用 `!latchedAtEntry` 这类「进入时快照」判断，也不再让 `suppressRoomEndTurn` 挡掉 drain 期间新产生的按下（`suppressRoomEndTurn` 只用于防同一个按下被重复处理）。
- 顺手修掉首轮回归：旧的 `endTurnLatched(gam)` 把 `gam.turnHasEnded` 也算闩锁，于是「我们刚刚结束的那一回合」在下一 tick 被当成一次新按下，`room.endTurn()` 又跑一遍 → `applyEndOfTurnTriggers` 里 Combust 掉两次（门禁 S168 从 80→79 变成 80→78）。现在 `endTurnQueued(gam)` 刻意排除 `turnHasEnded`。
- `search/Action.java`：新增 `turnBefore`/`turnAfter`（默认 -1），`withChoice`/`withCardUuid`/`withCardPrint` 三处副本继承。
- `search/Solver.java`：`expand(...)` 在沙盒 install 之后、`Legal.apply` 之前记 `action.turnBefore = GameActionManager.turn`，在 `expandScreens` 之后记 `turnAfter`——即回合变化取自沙盒内真实推进，不靠猜。
- `Gameplay.java`：`buildPreviewRoute` 按 `turnBefore/turnAfter` 推进并给跳号补行；`buildRoute`（正式计划）在 `shown[i] > currentTurn + 1` 时补 `第 N 回合 · 自动结束` 行，pill 文案「结束回合（自动）」、step=-1（点击与高亮对 <0 有保护）；新增 `addSkippedRow`、`setPreviewPathForTest`、`previewRouteForTest`。

**门禁**：S177（`the panel draws the turn the fight skipped`）4/4：正式计划 turns={t0,t0,t0+2} → 3 行、第 2 行 `第 t0+1 回合`、唯一 pill「结束回合（自动）」；预览侧手工设 turnBefore/turnAfter 同样 3 行且 pill step=-1。S178（`a power that presses End Turn ends the turn inside the sandbox`）2/2：JawWorm 战斗里挂一个 `atStartOfTurn` 按结束回合按钮的匿名 AbstractPower → `turn 1 -> 3`、`endTurnQueued=false isEndingTurn=false`。另外 S174 场景从单只 JawWorm 改成 3 只小怪（单怪 55ms 就被 B120 的 provenOptimal 收尾，暂停门禁抢不到「运行中」的采样）。core **445/0**（S1–S178）。

**代价**：①性能：drain 每 tick 多一次字段读取（`endTurnQueued`），可忽略；补行只在面板重建时做（每计划一次）。②内存：每个 Action 多两个 int（搜索里动作对象数量级 1e5，约 0.8MB 峰值）。③复杂度：`Action` 多两个可变字段（与既有 `withChoice` 的复制语义一致），`buildRoute`/`buildPreviewRoute` 各多一段跳号分支。

**风险**：①跳号补行依赖 `turnBefore/turnAfter`；手工构造的 Action（门禁/测试/未来新调用点）若没设，退回旧行为（不补行）；②`pressNow` 只看 `endTurnQueued`，若某 mod 直接 `isEndingTurn=true` 而不置 `endTurnQueued`，那一拍不会被我们结束（当前游戏与 mod 链路都走 `disable(true)`，未观测到这种写法）；③回滚：Driver 恢复旧闩锁分支即可（B122 的 Driver 改动是独立的），面板补行可单独保留。

**对 mod 的影响**：面板——被动结束回合单独成行、写「结束回合（自动）」，回合号不再跳号；搜索——昏睡/能力强制结束的回合现在真的被跳过，计划不再包含实机不允许出牌的回合（与 B116 修 mod 静态同理，都是让沙盒更真）；自动出牌——不受影响（仍按计划逐步执行、每步 Digest 校验）；其它 mod——只要它用 `PressEndTurnButtonAction`/`EndTurnButton.disable` 结束回合就能被正确模拟；若它自己直接改 `isEndingTurn` 则不模拟（已知边界）。另一条已知边界：能力/遗物自动「打出」的牌不是计划里的独立 Action，面板无法单列（用户第二条里的「出牌也是的」只覆盖玩家自己的出牌步骤）。

## B123 唐（TangPower）自动打出的牌面板看不见，且计划牌被打掉后自动出牌停下（user m03233）

**现象（2026-11-03 23:25 六火亡魂战，C:/Users/YOKI/sts1-engine-solver/solver.log）**
- 计划第 15 步是「防御」，实机这一手已经不在手牌里（被唐打掉），`Gameplay.submit` → `findHandCard` 返回 null → `fail("计划里的「防御」不在手上了")` → 自动出牌停止，玩家必须手动再点「自动」。
- 面板只画计划：唐这回合自动打出的牌（实机打了两张防御）在面板上一张都看不到，看起来像求解器算错。
- 日志：23:25:57 `step 15 came out differently than predicted (结束回合)`（expected 里沙盒 TangPower 剩 1 层 + CandyBall、b=5；actual 唐把层数耗完、无 CandyBall、b=10）；23:25:59 `execute: card uuid c7049cac… (防御) is no longer in hand; refusing instead of playing a neighbour`。

**机制**
- CaiNongMod `TangPower.atStartOfTurnPostDraw` → `PlayRandomCardAction`（随机源 `AbstractDungeon.cardRandomRng`）→ `NewQueueCardAction(card, randomTarget, immediate, autoplay)` → 真实出牌经 `AbstractPlayer.useCard`；`onAfterUseCard` 还会再触发最多 amount-1 次。
- 沙盒跑同一份 mod 代码，所以搜索**有**模拟唐；但随机流与实机不同 → 计划假设的唐出牌与实机不一致，落到执行器就是「这张牌不在手上了」。

**修法**
1. 新 `drive/AutoPlays.java`：`@SpirePatch(clz = AbstractPlayer.class, method = "useCard", paramtypez = {AbstractCard.class, AbstractMonster.class, int.class})` + `@SpirePostfixPatch` 记录真实战斗的每次出牌；`World.anyInstalled()` 为真（沙盒窗口）时不记；`markOurs(uuid)` 标记求解器自己入队的牌；`wasAutoPlayed(cardId,name)` 只认本回合、非我方、同 cardID+卡名。
2. `Gameplay.submit` 的「牌不在手」分支先查 `wasAutoPlayed`：命中则 `skipAutoPlayed(step)`（step+1、清 pending、日志「已由自动打出完成（跳过第 N 步，接着执行下一步）」）并返回 false，下一帧继续，**不再 stopAutopilot**。
3. `Gameplay.lines()` 计划分支加「自动打出（本回合，实机）: 防御 x2」行；`Gameplay.reset()` 与新战斗检测处调用 `AutoPlays.clear()`。

**门禁 S179 4/4**
- Synthetic 合成战斗 → 手牌只留一张防御 → `addToTop(new NewQueueCardAction(card, true, false, true))` 走能力式自动出牌 → 记录命中且面板行含卡名；`installPlanForTest` 装一条以该牌为首步的计划 → `executeStep()` 返回 false 且 step=1、计划与 autopilot 都保留。修前 step 停在 0，第一条记录断言与跳步断言都会红。

**代价**
- 性能：每次出牌一次 list add + UUID 查重（上限 128），可忽略；面板有计划时每帧一次 ≤128 条遍历。
- 代码：新增 1 个类 + `Gameplay` 三处；补丁挂游戏自身 `AbstractPlayer.useCard`（MTS 已有大量同类补丁）。

**风险**
1. 只在非沙盒窗口记录；若某个 mod 在 install 期间也驱动真实出牌（未见），记录会漏。
2. 跳过判据「同 cardID + 同卡名 + 本回合 + 非我方」：多张同名牌里唐打了其中一张而另一张仍在手时，`findHandCard` 先命中，不会走到跳过分支 → 不会误跳。
3. 唐打掉的若是最后一张可付能量的牌，跳过之后下一步可能被 `refuseCard` 停住——真实状态问题，按 B77 停住并保留计划，不属于本条范围。
4. 回滚：删 `AutoPlays` 与 `Gameplay` 三处调用即可，互不依赖。

**对 mod 的影响**
- 面板：新增一行自动打出记录；计划路线条不变。
- 自动出牌：不再因为「计划牌被唐打掉」停下；真正的失败（能量/目标/屏幕）仍会停。
- 搜索：不变（唐仍在沙盒里以自身随机流模拟，差异属已知边界；搜索结果不读 AutoPlays）。
- 其它 mod：只依赖游戏自身 `useCard` 调用，任何用 `NewQueueCardAction`/`CardQueueItem` 自动出牌的内容都会被同样记录。

## B124 唐自动打出的牌没进计划（user m03444，撤销 B123）

### 用户原话（m03444）

1. 沙盒应该正确模拟唐和随机流但是没有正确模拟。
2. 唐是随机打出层数的数量张牌不是 1 张。
3. 不要专门给唐做适配而是改进沙盒模拟。

⇒ B123 的方向（用 @SpirePatch 监听 AbstractPlayer.useCard 记录实机自动出牌、面板另起一行）被否决，`drive/AutoPlays.java` 与 `Gameplay.lines()` 里的实机记录行、门禁 S179 全部删除。

### 先证伪：唐机制与随机流镜像都没有错

- `TangProbe`（单回合，CaiNong + 唐）：live 与沙盒的 digest 逐位一致（turn=2 rng=6 hp=80 block=15）。
- `LockstepProbe`：连续 4 次结束回合，7 条 RNG 流（cardRandomRng/shuffleRng/aiRng/monsterRng/miscRng 等）的 counter 与每步 digest 全部相同，打印 `LOCKSTEP first difference: none`（hp 80→76→72→54→46）。
- `DiscardAtEndOfTurnAction`（游戏源码）：末尾 `Collections.shuffle(cards)` 只影响 triggerOnEndOfPlayerTurn 的先后，不改牌堆顺序。

### 真正的缺陷：记录，不是模拟

搜索的沙盒**确实**跑了唐打出的牌（回合开始 PlayRandomCardAction → NewQueueCardAction → AbstractPlayer.useCard），但计划里只记「自己主动出的那一张」；面板按计划画，于是实机看到「唐打了两张防御」而面板一张都没有，自动出牌执行到被唐打掉的那张牌就 `findHandCard == null` → fail 停止。

### 修法（4 处）

1. `search/Action.java`：新增 `public List<String> autoPlays`（卡名列表，默认空），`withChoice`/`withCardUuid`/`withCardPrint` 三个副本构造都继承它。
2. `search/Solver.java`：`expand()` 在沙盒内 `Legal.apply` 前后各读一次 `cardsPlayedThisTurn`，差值里去掉玩家自己那张、前缀不一致视为回合重启，结果写进 `action.autoPlays`（`recordAutoPlays`）；另加 `cardsPlayedNames`、`simulateForTest(World,Action)`、`cardsPlayedNamesForTest()` 供门禁使用。判据只认本次计划自己的模拟，完全不知道「唐」这个卡。
3. `Gameplay.java`：`autoPlaySuffix(Step)` 把 `（自动：防御、打击）` 拼到计划与预览的 pill 标签上；`submit()` 里 `findHandCard` 为 null 时先看该步 `autoPlays` 是否含这张牌，含则 `skipAutoPlayed(step)`（++step、清 pending、写日志、不停自动出牌）。
4. `probe/Probe.java`：门禁 S180。

### 门禁 S180（5/5）

- 前半段不能用唐：唐的层数来自玩家打出的 CandyBall，且唐会在下回合开始把 3 点能量花光（`EnergyPanel.totalCount` 才是当前能量，`player.energy.energy` 只是充能额度），要观察的那一步会被合法拒绝。
- 改用匿名探针能力 `sts1solver:AutoPlayerProbe`（重写 `onAfterUseCard`，`addToBot(new NewQueueCardAction(c, null, false, true))`），走与唐同一条 `AbstractPlayer.useCard` 路径；再加 `gainEnergy(3)` 补回被唐花掉的能量。
- 断言：沙盒里自动打出的牌被记录进 `a.autoPlays`；面板 pill 带「（自动：…）」；执行遇到被自动打掉的计划牌时 `executeStep()` 返回 false 但 step 前进、自动出牌不停。

### 验证与产物

- S180 单跑 5/5；core **450/0**（S1–S180，449 − S179(4) + S180(5)）。
- 安装 jar：`D:/Steam/steamapps/common/SlayTheSpire/mods/sts1-engine-solver.jar`，sha256 `CD311FD24BBE0A118CC9AF92618FBD80DA94973BDD329ACFD0D3F1511DA8050A`（970761 B）。

### 代价 / 风险 / 对 mod 的影响

- **代价**：每次 `expand` 多两次 `cardsPlayedThisTurn` 读取（int，O(1)）与一次列表比较，可忽略；`Action` 每个节点多一个 List<String>（通常空，共享 Collections.emptyList 语义由调用点 new ArrayList 控制）；面板 pill 文本变长，`RouteRow` 单个 pill 允许两行。
- **风险**：①随机流与实机不同，计划里「（自动：X）」与实际自动打出的牌不保证一致——执行仍以实机为准（找不到牌且本步 autoPlays 含它就跳过），若实机自动打出的是另一张，计划里那张还在手上则照常执行；②前缀不一致判定为回合重启，极端情况（引擎在同一回合内重排 cardsPlayedThisTurn）会把某些自动牌漏记，后果只是少了后缀、不误判；③回滚：四处改动彼此独立，删 `autoPlays` 字段与 `autoPlaySuffix`/`skipAutoPlayed` 即回到 B122 行为。
- **影响**：面板——计划与预览都多出「（自动：…）」后缀；自动出牌——遇到被自动打掉的牌不再停（用户要的行为）；搜索——分支不增加，只是每个 Action 多一个记录字段；其它 mod——只依赖游戏自身的 `cardsPlayedThisTurn` 计数，不识别具体 mod 卡。

## B125 已决定的战斗里还能一直「结束回合」（面板第三回合 4 个结束回合）（user m03996）

**用户提的七条**：①笋干缪哈没有费用打出 ②面板第三回合有 4 个结束回合明显不合理 ③找到原因是哪里模拟错了 ④不要只让 bug 静默 ⑤不要做特判 ⑥不要用实机结果反推面板 ⑦沙盒本身要知道那一拍发生了什么。

**证据链（先证伪，再定位）**

- GrowthCredit 不是元凶：`search/GrowthCredit.java:42` 的 `valueOf` 在 `creditPerPlay=0`（默认关闭）时直接返回 0，且只按 8 张特定成长卡计数——不会让胜利后的节点反超分数。
- 分裂假设作废：`findstr Split` 只命中 AcidSlime_L / SlimeBoss / SpikeSlime_L / SplitPower，`SpikeSlime_M` 不分裂。
- 结束回合的常规结算没错：OverProbe2 在 Looter+SpikeSlime_M 战连按 4 次 `Driver.endTurn(1600L)`，HP 80→80→70→60→40、turn 1→2→3→4→5、每回合 energy=3/panel=3/hand=5 都重新充满；加 `TangPower(5)` 后结果完全相同。
- 用户那局的日志（`logs/solver-20261104-002850.log`）：`solve: QUICK win … hpLoss=0 … turns=1 nodes=3098 reason=win`；plan 共 17 步，前两个回合各 3+2 步各带一个「结束回合」，**第三回合 6 步之后连着 4 个「结束回合」**——这 4 步的 `Step.turn` 全等于 3（`Gameplay.buildRoute` 从不合并结束回合），也就是这 4 次结束回合根本没推进回合。

**根因（已证实的模型错误）**

游戏 `GameActionManager.getNextAction` 只在「下一玩家回合开始」那一段执行 `turn = turn + 1`，而这一段在 `turnHasEnded && getMonsters().areMonstersBasicallyDead()` 时会被跳过。即：**战斗已经决定（怪全在 isDying/isEscaping）时，结束回合是一个彻底的 no-op**。沙盒侧三处都没有判这件事：

1. `search/Legal.java:117` 无条件 `out.add(Action.endTurn())`，整个 `actions()` 从不看战斗是否结束；
2. `search/Solver.java:2148` 的 `candidates(World)` 也不判；
3. `drive/Driver.endTurn` 里 `drained.turnAdvanced = turn != turnBefore || combatOver()`，于是「战斗已结束、回合没涨」被当成成功的结束回合。

结果：搜索可以在已经赢了的节点上继续展开 no-op 的结束回合，越串越多（`score` 里更深一层只扣 1 分，串 4 个几乎不亏），这些步不花血也不花能量，`hpLoss=0` 被放大；面板按 `Step.turn` 分行，于是 4 个 no-op 结束回合全画在同一个「第 3 回合」行里。OverProbe 复现：已结束的世界 `Legal.actions()` 仍返回 1 个 `<end turn>`，连按 4 次 turn 3→3 且 Digest 完全相同；对它开新 `Solver.Session` 还会补 1 步「结束回合」。

**修法（不做任何卡牌特判）**

- `search/Legal.actions()` 开头（try 内）`if (Driver.combatOver()) { return out; }`；`search/Solver.candidates` 装好后同样 `if (Driver.combatOver()) return new ArrayList<Action>()`。**战斗已经决定的状态没有决策**。
- `drive/Driver.endTurn`：第一次 drain 后若「战斗没结束且回合没涨」，再给一轮 drain（慢帧不是卡死）；仍没推进就 `refused=true` + 写 `lastRefusal`（`the end turn did not finish the turn (turn stayed N, combatOver=…)`）+ `Log.warnEvery("endturn-refused", 30000L, …)`。判据抽成 `Driver.endTurnFinished(turnBefore, turnAfter, fightOver)`：推进=完成，结束战斗=完成，其余=拒绝。
- 兑现第 ⑦ 条：`Solver.Result.planTrace()` 逐步输出 `i+1:t=<turn> hp=<hp> b=<block> e=<energy> mhp=<怪总血>`，结束回合若 `turn <= 上一回合` 追加 `!stuck`；发布计划的两条路径（quick/deep）各写一条 `trace:` 日志。这样下一次复现时日志本身就能说清沙盒那一拍认为发生了什么。

**门禁**

- S181（4 条）：Thorns 让怪在敌方回合自杀 → 沿计划走到 `combatOver()` 的 ended 世界；断言 ended 里 `Legal.actions()` 为空、`Driver.endTurn(400L)` 之后仍是 decided、对 ended 开新 Session 一步都不出。
- S182（3 条）：开着选牌屏的合法结束回合**不被误拒**（B125 的重试不能把正常结束回合变成拒绝）；`endTurnFinished` 真值表：推进=完成、结束战斗=完成、卡住=拒绝。
- core **457/0**（S1–S182）；jar `C2288E68…7D0A`（984821 B）。

**代价**

- 性能：`Legal.actions()`/`candidates` 各多一次 `Driver.combatOver()`（几次 null 判 + 遍历怪物 isDying，微秒级，且只在生成候选时调用）；`endTurn` 只在「没推进且没结束」时多跑一轮 drain（正常结束回合不触发），最坏多花一个 `millisCap`；`planTrace()` 只在发布计划时拼一次字符串（几十步，微秒级）。
- 内存：无新增常驻，`planTrace` 是临时 StringBuilder。
- 复杂度：`Driver.endTurnFinished` 一个判据函数；`planTrace` 一个只读方法。

**风险**

- 搜索空间变窄：已决定的世界不再展开——这正是要的，但若 `combatOver()` 误判（例如某只怪 `isEscaping` 却还会回来），会把还能打的局面当结束。`Driver.combatOver()` 用的是游戏自己的 `areMonstersBasicallyDead()` 口径，误判概率与游戏判定同源。
- 慢机器上真结束回合被拒：极端卡顿时若两轮 drain 都没跑完敌方回合，这个结束回合会被判 refused，搜索丢掉这一条分支（面板可能给「算不出」，而不是给一条假的回合）。这是有意选择：宁可少一条线，也不写一条没发生过的回合。
- 回滚：`Legal.java` 一行、`Solver.candidates` 一行、`Driver.endTurn` 的重试+拒绝块、`planTrace`/两处日志——四处互相独立，任何一处单独退掉都能编译。

**对 mod 的影响**

- 面板：胜利局面里不再出现多余的「结束回合」；计划行的回合号与实际推进一致。日志新增 `trace:` 行（每步 `t=/hp=/b=/e=/mhp=`，卡住的步标 `!stuck`）与 `end turn refused: …`。
- 自动出牌：不变（结束回合的拒绝只会让计划里本来就不该有的步骤消失）。
- 搜索：已决定节点收尾更早、更省预算；少掉的是不可能发生的步。
- 其它 mod：不涉及任何 mod 卡牌/字段，判据只用引擎的 `areMonstersBasicallyDead()`。

**已知边界（未定论，不静默）**

- 用户第 ① 条「笋干缪哈没有费用打出」尚未定论。那一局执行到第 9 步（口渴+ 后的选牌屏）就停了，之后是用户手打；下次复现请看发布计划时的 `trace:` 行（`e=` 是沙盒认为的当回合能量）与实机的能量/手牌，两者一对就能定位是能量记账错还是随机流不同。

**观察（未定性）**：一次 core 全量里 S66「a simulated stat flush lands in the sandbox directory」红过一次（`landed=false`、无异常），单跑 S66 8/8 绿、紧接着的第二次全量也全绿。判为异步/磁盘时序偶发，未做改动。
## B126 唐打出昏睡后连跳回合，计划被整条判「过期」（user m04653）

**现象**：实机 01:17 一局（邪教徒 + 守护者，CaiNong，ascension 4）。唐（TangPower）在回合开始自动打出「昏睡+」，`TrancePower` 在回合开始 `addToTop(new PressEndTurnButtonAction())` 把那一回合直接结束；实机连跳了 2 个回合（回合 2 → 4），面板在第 7 步提交后 20 秒只有一行 `pending action did not settle within the deadline; plan kept`，紧接着 `plan stale: step 7 belongs to turn 2, live turn 4; dropping it` 把整条计划丢掉。

**先证伪的方向**（避免把「沙盒不会连跳」当结论）：

- 新探针 `probe/TranceChainProbe.java`（`-Mode chain -AllMods`）直接测沙盒：`skip1 turn 1 -> 3`、`skip2 turn 1 -> 4`；被跳过的回合里唐照样抽牌并自动出牌（`skip1+autoplay`）；真 `TangPower` + 5 张昏睡的牌库也 `turn 1 -> 4`、powers 清空。⇒ 沙盒的 TrancePower / 唐机制本身是对的。
- 用户日志里同一计划的 `trace:` 写着 `8:t=4`，实机那一刻正好 turn=4 ⇒ 沙盒对「跳过到第 4 回合」的预测也是对的。

**真实缺陷（三层，都在我们自己这一侧）**：

1. 计划没记「这一步让回合前进几次」。`Solver.Step.turn` 记的是动作**开始时**的回合；一次合法的跨回合跳过在事后看起来和「计划属于上一回合」一模一样。
2. `Gameplay.planIsStale()` 只看**当前**这一步：它属于上一回合就整条丢弃。于是实机（正确地）跳过回合后，一条仍然有效的计划被判死。
3. 待结算的 20 秒全程沉默：`pendingDeadline = now() + 20000L` 期间不写任何日志，只在到点时写一句「没结算」，无法判断在等什么。

**修法**：

- `search/Solver.java`：`Step` 新增 `turnBefore/turnAfter`；`replay()` 在每个动作前后读 `GameActionManager.turn`（`Legal.applyOrRefuse` 之前 / `answerForReplay` 之后）并写进 `Action.turnBefore/turnAfter`；`Result.planTrace()` 改成 `i+1:t=<before>-><after> hp= b= e= mhp=`，回合跨度 `skipped = turnAfter - turnBefore - (isEndTurn?1:0)` 大于 0 时追加 `skip=N`，只有**结束回合且 turnAfter == turnBefore** 才标 `!stuck`（B125 把紧跟同回合动作的正常结束回合误标成 `!stuck` 的问题一并修掉），末尾追加 `auto=`（本步引擎自动打出的牌）。
- `Gameplay.java`：`planIsStale()` 改为遍历**剩余**步骤，`reached = max(turn, turnAfter)`，只要还有一步 `reached >= live` 就不算过期；`pollPending()` 每 2 秒（限速，只在 pending 期间）打印 `settle wait: step i/N turn=before->after expectedEnd=… settled=… quiet=… digestMatch=… stableFrames=… selecting=… kind=… screenUp=… screen=… endingTurn=… turnHasEnded=…`；新增「越过即完成」：待结算动作不是结束回合、`GameActionManager.turn > 沙盒测得的这一步结束回合`、且 `actionQueueQuiet()` 为真时，记这一步完成（`noteDriftAndContinue` + `syncMarkers` + `verified`），状态写「第 N/M 步已越过（引擎自己跳过了回合）」，日志 `step N was passed by the fight: turn X -> Y, the sandbox ended it in Z`。
- 门禁：`S183`（用匿名「按结束回合」能力造出跳过，`Solver.replay` 单步结束回合后断言 `turnAfter - turnBefore >= 2`，实跑 `step=1->3`）、`S184`（`planIsStale` 真值表：剩余某步落在实机回合或更晚 ⇒ 不过期；全部落在已结束的回合 ⇒ 过期）。

**代价**：性能——`replay()` 每步多两次静态字段读（纳秒级）；`settle wait` 日志每步最多 10 行（20 s / 2 s，限速且只在 pending 期间）；`planIsStale()` 从 O(1) 变成 O(剩余步数)（≤几十步，调用频率是每帧一次的轻量判断，可忽略）。代码——`Solver.Step` 两个新字段（`Result`/`Step` 的构造点全部有默认值 -1，旧缓存路径不受影响）、`Gameplay` 三个私有方法。

**风险**：①「越过即完成」在「玩家自己先按了结束回合、而我们的动作还没结算」时也会触发，把这一步记成完成并继续——比原来空等到超时再丢计划更温和，但会多记一次 drift；②`settle wait` 在引擎长时间不结算时会持续刷（2 秒一行，单步最多 10 行），已限速；③`planIsStale()` 变宽松：若玩家真在上一回合手动结束了回合、而计划里恰好还有一步的 `turn` 落在更晚（例如旧计划里残留的深层步骤），计划不会被判过期而继续执行——由每步 `Digest` 校验兜底（不一致即停）。④回滚：`Solver` 两个字段 + `replay` 两行 + `planTrace` 与 `Gameplay` 三处方法彼此独立，可单独退回。

**对 mod 的影响**：面板/自动出牌——唐打出昏睡这类「引擎自己跳回合」的局不再把整条计划丢掉，自动出牌会接着往下走；出牌顺序与搜索结果本身不变（搜索早就在沙盒里正确模拟了跳过）。日志——新增 `settle wait:`、`step N was passed by the fight`、`trace:` 的 `t=a->b skip=N` 与 `auto=`。其它 mod——无新增特判，判据只用引擎自身的 `GameActionManager.turn` 与队列状态。

**已知边界**：01:17:20–01:17:32 那 11 秒究竟是什么让引擎迟迟没结算，当时的日志没有记录（B126 的 `settle wait` 行就是为下一次复现留的证据）；唐的随机流与实机仍然不同，具体自动打出哪张牌不保证一致。


---

## B127 面板给出「付不起」的牌：能不能打用了两套判据（user m04994 第 2 条 / m05130 决定只做修 1 与修 4）

### 现象
蛇花（Snake Plant）战，12:36:17 的搜索产出 34 步计划，开头是
`农盘+ | 农盒 | 按兵不动+ | 昏睡 | …`。实机 12:44:49 打出 农盘+（cost=3）→ 12:44:51 打出 农盒（cost=0），
能量已归零；12:45:02 面板仍要求执行第 3 步「按兵不动+（cost=3）」，实机点不出来，随后
`settle wait: step 3/34 turn=1->1 expectedEnd=2 settled=false quiet=false digestMatch=false` 静默 21 秒
（日志里**没有任何拒绝 WARN**），12:45:06 又出现一次 `world switched`。

搜索自己的 trace 就已经把这件事写出来了：
`1:t=1->1 hp=80 b=0 e=0` → `2:t=1->1 e=0` → `3:t=1->2 e=4 skip=1`
—— 第 3 步打出前 e=0，打出后 e=4，说明沙盒在 0 能量时打出了 3 费牌、还倒退了能量。即**沙盒里第一回合花了 6 点能量，而实际只有 3 点**。

### 根因
「这张牌现在能不能打」在实机有两套判据：

1. 鼠标点击（`AbstractPlayer.inputUpdate`，javap offset 1194/1206）：
   `hoveredCard.hasEnoughEnergy() && hoveredCard.canUse(this, null)`；能量不够时走 offset 675 弹 ThoughtBubble。
2. 出牌队列（`GameActionManager.getNextAction`）：
   `card.canUse(player, item.monster) || card.dontTriggerOnUseCard` —— 只看 canUse。

原版卡里两者等价，因为基类 `AbstractCard.canUse` 自己就是
`cardPlayable(monster) && hasEnoughEnergy()`；而 `hasEnoughEnergy()` 的末尾是
`EnergyPanel.totalCount >= costForTurn || freeToPlay() || isInAutoplay`。
但 mod 卡可以覆写 `canUse` 而不调 super —— CaiNong 的 `BideTime`（按兵不动）就是
`return !isUseAttack;`，**能量检查整个丢失**。

求解器这一侧，`Legal.actions()` 与 `Gameplay.submit()` 都走 `Driver.canUse()`（只调 canUse），
于是 0 能量也能把 3 费的按兵不动+ 列进候选、写进计划、甚至"执行"（沙盒里真扣不出钱，费用只从
`EnergyPanel` 里来）。`Legal.java:18` 的老注释「canUse 已经知道能量」对原版卡成立、对覆写卡不成立，
是这次被踩中的地方。

### 修法（不做卡片特判）
- `Driver` 新增 `public static boolean playerCanUse(AbstractCard, AbstractPlayer, AbstractMonster)`
  ＝ 判空 + `c.hasEnoughEnergy()`（try/catch 返回 false）+ `Driver.canUse(...)`，
  即「和玩家自己点这张牌同一套判据」。
- `Legal.actions()` 两处（`alive.get(k)` 分支与 null 分支）改调 `playerCanUse`。
- `Gameplay.submit()` 的出牌前检查改调 `playerCanUse`：自动出牌遇到付不起的牌会立刻记
  `游戏拒绝打出「X」… cost=… energy=…` 并走停/重算，不再静默挂 20 秒。
- `Driver.canUse()` **保持不动**：它表示引擎出牌队列的语义（还有 autoplay 的 `isInAutoplay`
  免费豁免，唐打出层数张牌就走这条路）。分层是为了不误伤 autoplay、Coverage/ActionAudit/探针。

### 门禁 S185（4 条）
Synthetic seed=9185 / 80HP / deck {Strike_R×2, Defend_R×3} / JawWorm → build + drain →
存 `EnergyPanel.totalCount` 后置 0 → 手牌放一个匿名子类
`new com.megacrit.cardcrawl.cards.red.Defend_Red(){ canUse(...) { return true; } }`
（cost/costForTurn=3、isCostModified=true，模拟「覆写 canUse 丢掉能量检查」的 mod 卡）：
1. 引擎判据 `Driver.canUse` 仍为 true（证明分层没被合并）；
2. `playerCanUse` 为 false；
3. `Legal.actions()` 里没有这张牌的候选；
4. 能量调回 3 后 `playerCanUse` 为 true 且候选重新出现。
实跑：`S185 energy gate: engineCanUse=true playerCanUse@0=false offered@0=false playerCanUse@3=true offered@3=true`。

### 同轮查证「修 4」：不是 bug，未改代码
原来怀疑「世界切换（读档/新地牢）后 `pending` 残留」，因为日志写
`world switched (save/load or new dungeon): solver state cleared | plan=34 steps pending=按兵不动+`。
查 `Gameplay.detectWorldSwitch()`（`Gameplay.java:1565`）→ 它在 `1601` 行已经有 `pending = null;`
（连同 plan/step/autoplay/armed 一起清），而 1591-1596 那行日志是**有意在清除前打印**当前值。
⇒ 是日志读法误导，不是缺陷；按用户「不要为消错放宽/加无意义改动」的规则，不改代码，只记在这里。

### 用户同时否决的两件事
- **修 2（把费用写进 digest）撤回**（m05126）：「费用都是模拟出来的…而且有时候（比如有蛇眼的时候）
  每次摸牌牌的费用不同」。确认这局与计划缓存无关：命中缓存时实机费用 3/0/3 与计划一致。
- **修 3（提交后校验）不做**（m05130 只批「修1和4」）。

### 代价
- 性能：`playerCanUse` 比 `canUse` 多一次 `hasEnoughEnergy()`（几次字段读取 + 可能的 powers 遍历），
  每个候选一次、每次面板计划提交一次，量级可忽略；搜索候选数不变（只是把付不起的牌裁掉，
  分支数会**变少**）。
- 代码：`Driver` +18 行（含 javadoc）、`Legal` 两行、`Gameplay` 一行、`Probe` +1 门禁。

### 风险
1. **autoplay 误伤**：若某条 autoplay 路径依赖「canUse 为真即可打出」，改判据后会少给候选。
   已用 `Driver.canUse` 保持原语义、只在新入口 `playerCanUse` 收紧来限缩；唐的牌走
   `NewQueueCardAction`（引擎队列）不经 `Legal`，因此不受影响。
2. **免费打牌类效果**（`freeToPlay()`、`isInAutoplay`）已被 `hasEnoughEnergy()` 内部放行，
   不会因为这次收紧变成"不可出"。若将来有 mod 卡用「canUse 返回 true 但 hasEnoughEnergy 为假」
   表达特殊规则（例如消耗生命代替能量），会被误判为不可出 —— 目前未发现这种卡。
3. **回滚**：三处调用各一行，把 `playerCanUse` 改回 `canUse` 即恢复旧行为（`Driver.playerCanUse`
   留着不接线即可），互相独立可编译。

### 对 mod 的影响
- 面板：不再出现付不起的牌；代价是有些局面候选变少（真实边界，不是剪枝）。
- 自动出牌：付不起时**立刻**在日志里报 `游戏拒绝打出 … cost=… energy=…` 并停/重算，
  不再"看起来卡住 20 秒然后世界切换"。
- 搜索：候选更少更真；`hpLoss`/`turns` 不再被"免费出牌"污染。
- 其它 mod：只对「覆写 `canUse` 且不调 super」的卡改变行为，且改的方向是**与实机点击一致**。

## B128 烟雾弹逃离：沙盒不知道玩家已经离开战斗（user m05245）

**用户两条**
1. 最新日志打蛇花时用了烟雾弹，但烟雾弹的效果是逃离这场战斗、没有奖励，而且 BOSS 不能用；如果算出来「战损过多、只能靠烟雾弹逃」，那也不该再用第二瓶药。
2. 烟雾弹使用后战斗应该结束了，但面板显示后面还有行动——「这可能是战斗结束的判定没做好」。

约束（延续 m03996）：不静默、不做特判、不用实机结果反推面板、沙盒自己要知道那一拍发生了什么。

**实机现象（用户日志 2026-11-04 14:12 那条，蛇花战）**
plan 第 14 步是烟雾弹；同一份 `trace:` 里该步之后怪物总血仍 20；计划后面还有 `农化 +`、`结束回合`；结果行是 `win` 且 `potions=2 potionHp=23`（技能药水 14 + 烟雾弹 9）。也就是说：沙盒把烟雾弹当成「花 9 点血、什么都不发生」，然后继续把这局打完并报可赢。

**根因（已证实）**
- 全库只有两处调 `AbstractRoom.endBattle()`：`AbstractMonster.die/escape`（怪死，属于引擎路径，沙盒一直在跑，所以普通胜利没问题）与 `AbstractPlayer.updateEscapeAnimation()`。
- `updateEscapeAnimation()` 只被 `AbstractPlayer.update()`（渲染帧）调用；本 mod 从不跑 `player.update()`/`combatUpdate()`/`endBattle()`（grep `src/main/java` 无命中）。所以 `escapeTimer` 停在 2.5 永不递减，`endBattle()` 永不发生：房间仍是 COMBAT、怪还活着、`Legal.actions()` 照旧给候选、搜索在烟雾弹之后继续出牌。
- 游戏语义（反编译）：`SmokeBomb.use` 设 `room.smoked=true`、`player.isEscaping=true`、`escapeTimer=2.5f`、`overlayMenu.endTurnButton.disable()`；`AbstractPlayer.updateEscapeAnimation()` = `if (escapeTimer != 0) escapeTimer -= delta; if (escapeTimer < 0) { room.endBattle(); flipHorizontal=false; isEscaping=false; escapeTimer=0; }`；`AbstractRoom.endBattle()` = `isBattleOver=true; ...; player.onVictory(); ...; actionManager.clear(); ...`。
- `SmokeBomb.canUse()` 本来就拒 BOSS（`m.type == BOSS` → false）与 BackAttack ⇒ 用户说的「BOSS 不能用」没有漏；它走真实药水对象，沙盒也走同一判据。

**修法**
1. `drive/Driver.java` 新增 `tickEscapeFrame()`：只在沙盒内（`World.anyInstalled()`）运行，逐行复刻 `updateEscapeAnimation()` 的**状态部分**（预览用的 drawX 位移不抄）：`escapeTimer != 0` 就减 `simDelta`；`< 0` 时 `room.endBattle()` + `flipHorizontal=false` + `isEscaping=false` + `escapeTimer=0`，并写一行限速日志 `sandbox: the player left the fight (escape animation finished): room.isBattleOver=true smoked=… turn=… hp=… escapes=N`（兑现「沙盒自己知道那一拍」）。
2. `drain(int,int)` 的每一步开头调 `tickEscapeFrame()`（必须在 idle 判断之前：逃离期间动作队列是空的）；idle 判据改成 `isIdle(gam) && !escapePending()`，否则逃离动画还没走完就被当成「已结算」；「状态 12 次不变 = stalled」的判据也对 `escapePending()` 网开一面（那 2.5 秒里只有计时器在动，按 0.05 的 SELECTION_DELTA 要 ~50 帧）。
3. `search/Solver.java`：`monstersDead()` 从 `Driver.combatOver()` 改成 `Driver.combatOver() && !Solver.roomEscaped()`——战斗结束不等于赢，`endBattle()` 清队列 + `onVictory` 回血（实测 69→75，Burning Blood）会让人误判胜利；逃了就是 `escaped` 终止态（已有 `EscapePolicy` 与 `PlanText` 的「只能逃跑（烟雾弹）」）。
4. `Solver.turnAfterOf(int)`：`turnAfter` 不允许小于 `turnBefore`。因为 `endBattle()` 里的 `actionManager.clear()` 会把 `GameActionManager.turn` 重置（本门禁实测 11→1），照抄会让面板在烟雾弹之后画出一行「第 1 回合」。

**门禁 S186**（`probe/Probe.java`，`Probe.core`）：seed 9186 / 80HP / 攻击牌两张 + 防御三张 / JawWorm，把怪做成 400 HP（不可赢），药水槽放烟雾弹，`EscapePolicy.ALLOW`，跑 `Solver.Session` 到结束。6 条断言：①结果是 `escaped && !win` ②计划最后一步是药水 ③回放该计划后 `room.smoked` ④`isEscaping/escapeTimer` 已清零 ⑤`Driver.combatOver()` ⑥逃离后 `Legal.actions()` 为空。实跑 6/6（`escaped=true win=false steps=40 lastPotion=true smoked=true leaving=false over=true offeredAfter=0`）。

**代价**
- 性能：`drain` 每步多一次静态判断 + 一次浮点减法（只有 `World.anyInstalled()` 时才对 `escapeTimer` 动手），逃离期间多跑 ~50 帧（每帧本来就在跑）；日志限速 2 秒一条（一个搜索会展开上百个烟雾弹节点，不加限速会按节点刷屏，而 `Log` 每条都 autoflush 在渲染线程上）。
- 代码：`Driver` 新增 2 个私有方法 + drain 三处判据；`Solver` 改 1 个判据 + 1 个夹取函数，expand/replay 各改一行。无新字段常驻。

**风险**
- 逃逸帧只在沙盒里跑（`World.anyInstalled()` 守门），实机完全不受影响。
- `room.endBattle()` 会触发 `player.onVictory()`（遗物回血、成就、`addEncounterData`）与 `actionManager.clear()`：这些都是游戏在真逃逸时本来就会做的事，沙盒只是补上那一帧；但 `metricData` 之类的静态可能被沙盒写（与普通胜利同一条路径，既有镜像机制覆盖）。
- `monstersDead()` 变更只影响「已逃」这一种终止态：普通胜利 `room.smoked == false`，判据不变。
- 回滚：`Driver.tickEscapeFrame/escapePending` 与 drain 的三处判据、`Solver.monstersDead/turnAfterOf` 各自独立；门禁 S186 是唯一依赖。

**对 mod 的影响**
- 面板：烟雾弹之后不再有计划；若整局只能逃，标题是「只能逃跑（烟雾弹）」，不再显示可赢。
- 自动出牌：逃跑线执行完药水即结束，不会再去打实机里已经结束的战斗。
- 搜索：烟雾弹成为**真正的终止态**（`EscapePolicy.terminalBonus()` 200000 低于胜利 10,000,000，所以只有没有胜线时才会推荐逃），因此「用两瓶药再赢」这种假分支消失；`escaped` 的结果不再计入 win。
- 其它 mod：逃离语义来自引擎（`room.endBattle`/`onVictory`），不认任何 mod 卡；BOSS 禁用走 `SmokeBomb.canUse()`。

**已知边界**
- 逃离线只计药水与 HP 代价，**没有建模「丢掉本场奖励」**（用户第 1 条说的是「不该用两瓶药」，这一条已由「逃离即终止」解决；奖励损失若要计价属于新的经济模型）。
- 沙盒随机流与实机不同这条老边界不变。

## B129 逃离要付代价：烟雾弹不再白送 100 HP（user m05700）

### 用户三条（m05700 原话）

1. 你做，不要留漏洞。
2. 烟雾弹使用至少要考虑烟雾弹逃离节约的血量是不是比损失的这场战斗的金币和卡牌（小怪逃离损失的卡牌算 2 血，boss 逃离损失的卡牌算 10 血值得）。
3. 并且要保证活下去的价值最大，不能为了金币和牌死了，其他收益也是一样不能大于活下去。

### 现象（承接 B128）

B128 修好「沙盒里玩家真的会离开战斗」以后，烟雾弹仍然被**主动**推荐：用户 2026-11-04 14:12 那局打蛇花，计划里第 14 步是烟雾弹，而且同一份结果里 potions=2、potionHp=23（技能药水 14 + 烟雾弹 9）。

### 根因（三个，都是真缺陷，不是配置问题）

1. **逃离标志比战斗结束早**：`Solver.roomEscaped()` 只读 `AbstractDungeon.getCurrRoom().smoked`。`SmokeBomb.use()` 一进 use() 就把 `room.smoked = true`，而真正的 `AbstractRoom.endBattle()` 要等逃离动画走完（`Driver.tickEscapeFrame()` 把 `escapeTimer` 递减到 < 0）。中间那几帧里战斗还没结束（`combatOver()` 为假、`Legal.actions()` 还在给候选），节点却已经被记成 «escaped»——既拿到逃离语义、又能继续把这局打完。
2. **逃离按白送奖励计、本场奖励一分不扣**：`EscapePolicy.terminalBonus()` 返回 200000 分（＝100 HP，按 2000 分/HP），而这一场战斗放弃的金币与卡牌奖励**完全没有计入**。一场只是暂时打不赢、一时半会还死不了的战斗里，喝一瓶 9 HP 的烟雾弹可以白赚 ≈91 HP ⇒ 搜索当然选它。这是「面板认为烟雾弹收益很高」的算术真相。
3. **`Result.escaped` 与印出来的计划不一致**：`Session.finish()` 把会话级 `bestEscaped` 传进 `Result`，而印出来的计划可能是从 `deepestPath` / split 子结果 / `finishLine` 追加重建的另一条线；`bestEscaped` 只在 `if (n3 > best.score)` 时跟着 best 更新。S186 实测：计划末步就是烟雾弹、`room.smoked=true`、`combatOver=true`，结果却报 `escaped=false`。

### 修法

1. `Solver.roomEscaped()` = `room != null && room.smoked && Driver.combatOver()`——「已逃离」必须同时满足「动画走完、战斗真的结束」。
2. 删掉 `EscapePolicy.terminalBonus()`，改为逃离**开销**：
   - 卡牌：普通怪 2 HP、精英/BOSS 10 HP（`CARD_HP_NORMAL=2`、`CARD_HP_BOSS=10`）。
   - 金币：按现成汇率 `GrowthBook.GOLD_UNIT = 25`（25 金 ≈ 1 HP）折算；金额取游戏里 `AbstractRoom.update()` 真实 `addGoldToRewards` roll 的中点——普通怪 `treasureRng.random(10,20)` → 15，精英 `random(25,35)` → 30，BOSS 100（升天 ≥13 时 ×0.75 → 75）。
   - 合计：普通怪 5200 分（2.6 HP）、精英 6400 分（3.2 HP）、BOSS 28000 分（14 HP）、升天 ≥13 的 BOSS 26000（13 HP）。`EscapePolicy.forfeitPoints()` 从当前房间读，`forfeitPointsFor(boss, elite, ascension, gold)` 是纯函数给门禁用，`expectedGold(...)` 给金币定价。
3. `Action.escaped`（`Solver.expand()` 里 `action.escaped = probe.escaped`，`withChoice`/`withCardUuid`/`withCardPrint` 三个副本方法各继承）+ `Solver.planEscaped(steps, fallback)`（末步 `action.escaped` 为真 → `EscapePolicy.mayRecommendEscape()`，否则用 fallback）。两处 `Result` 构造都改用它：`Session.finish()` 与 `solveInternal()`。

### 为什么「活下去最有价值」不需要新参数

`Solver.score()` 的既有结构已经保证：胜利 +10,000,000 分、死亡 −50,000,000 分，而任何 growth 收益（Feed/Genetic Algorithm/Ritual Dagger/Lesson Learned/Hand of Greed/金币）都折成 HP 计（上限 `GrowthBook.MAX_HP = 50`），量级只有几万到几十万分 ⇒ 永远压不过 ±千万的生死项。逃离 −5200 也恒优于死亡 −50,000,000。B129 只是把逃离从「白送 +200000」改成「扣本场奖励」，没有动这套优先级。

### 门禁

- **S187**（6 条，`Probe.core`）「leaving a fight is charged for the loot it forfeits (B129)」：纯函数定价表（normal=5200 / elite=6400 / boss=28000 / bossAsc13=26000，`expectedGold` 15/30/100），以及当前真实房间 `forfeitPoints()` 与定价一致（实测 5200）。
- **S186**（6 条，B128 的门禁按新语义改造）：原来靠「400 血虫 + 玩家 80 血」造「打不赢」，现在逃离要扣奖励 ⇒ 搜索不再在「还能打」的时候逃。改成真正的「不逃必死」：在沙盒内把怪设 400 血、玩家 `currentHealth=5` 并加 `CombustPower(player, 20, 1)`（S168 已证沙盒会跑回合结束自伤）当必死时钟。断言：`escaped && !win`、计划末步是药水、`room.smoked`、`!isEscaping && escapeTimer==0`、`combatOver()`、`Legal.actions()` 为空。实跑 `S186 escape: escaped=true win=false steps=4 lastPotion=true smoked=true leaving=false over=true offeredAfter=0`。
- S8 的断言从 `EscapePolicy.terminalBonus() == 0` 改成 `EscapePolicy.forfeitPoints() == 0`（DENY 档不产生逃离开销）。
- core **476/0**（S1–S187）。

### 调试过程中被证伪的假设（留给以后）

- 「给怪加 60 力量让它打死玩家」造不出必死：合成战里怪 `intent` 在 capture/install 往返后是 `DEBUG`、`getIntentDmg() = -1`（实测 `S186 peek: hp=5 intent=DEBUG dmg=-1`）⇒ 怪不出手。必须用会真实结算的自伤/回合结束伤害做时钟。
- 在 live 上 `addPower` 再 `World.captureLive()` 不会把能力带进沙盒；必须在 `w.install()` 之后加、再 `w.readBack()`。

### 代价 / 风险 / 影响（按 user m04593）

**代价**：`forfeitPoints()` 每次逃离候选评一次，读房间类型 + 升天数 + 常数，纳秒级；没有新增内存或常驻结构。代码上 EscapePolicy 少了一个方法、多了三个（约 40 行），Solver 两处评分表达式变化、新增 `planEscaped` 与一个 Action 字段。

**风险**：①价格是**估的**（卡牌 2/10 HP、金币 25 金＝1 HP、金额取 roll 中点），若真实掉落偏好不同，逃离的相对吸引力会偏一点；②`roomEscaped()` 变严 ⇒ 「烟雾弹刚打出、逃离动画未走完」的节点不再算逃离，若某条路径恰好在此处被预算截断，该节点会按「战斗还没结束」计分（不会误报胜利，因为 `monstersDead()` 也依赖 `combatOver()`）；③`Action.escaped` 是新字段，`withXxx` 三处已同步继承，回放重建的计划也带它——回滚只需删字段 + 把两处 `planEscaped` 换回原参数，互相独立。

**对 mod 的影响**：面板里「烟雾弹」只有在**必死**局面才该出现（默认 `EscapePolicy.ONLY_IF_LETHAL`），逃跑线仍显示「只能逃跑（烟雾弹）」、不报可赢；自动出牌不会再在还打得下去的战斗里喝烟雾弹；搜索里逃离是终止态、之后没有子节点（B128 已保证）且现在带了成本；其它 mod 的药水/卡若调用 `room.smoked` 语义，同一套判据适用。

### 已知边界（如实记录，没有为了消除报错而放宽）

- 沙盒**读不到**本场金币奖励的真实金额：发金币发生在 `AbstractRoom.update()` 的 `isBattleOver && actions.isEmpty()` 分支里，只在真机 UI 帧跑，沙盒从不到那里。因此按真实 roll 区间取中点显式定价，不假装读到了。
- 逃离只按卡牌 + 金币定价，遗物、药水位、事件/精英加成未建模。


## B130 地精头子战预计血量比实机多 10（死怪能力没有清，user m06143）

### 现象（用户日志 2026-11-04 19:00 那局，GremlinLeader，ascension 4，CaiNong）
求解是 QUICK win、hpLoss=10、20 步计划；发布计划时的 `trace:` 预测终血 61，实机打完 51，固定差 10。
唯一分叉在 step 16（防哈+）：

    step 16 came out differently than predicted (防哈+): expected P hp=63/80 b=49 ... actual P hp=63/80 b=39

HP 当时还没差（只差格挡 10）；同一对 digest 里那 4 只 0 血杂兵的 expected 仍带 `Minion/NongBai/NongHei`，actual 全部 `pw=-`。紧接着的结束回合之后 expected hp=61 vs actual hp=51，此后每一步都差 10。

### 根因（沙盒模拟错了哪一步）
1. 实机 `AbstractMonster.die(boolean)` **只起死亡动画**：`isDying=true`、依次跑每个 power 的 `onDeath()`、遗物 `onMonsterDeath`、HP<0 归 0、`deathTimer += 1.8f`（FAST_MODE 1.0f）。**它不清 powers。**
2. 1.8 秒后 `AbstractMonster.updateDeathAnimation()` 才做 `isDead=true` + `this.powers.clear()` + `dispose()`；而它只被渲染帧驱动：`AbstractMonster.update() ← MonsterGroup.update() ← AbstractRoom.update()`。
3. 沙盒从不跑这条链：`Driver.drain` 每轮只 `tickEscapeFrame()` + `pumpRoomObjects()`（只 `room.souls.update()`）+ `gam.update()`；全库唯一一处 `room.update()` 在 `pumpRoomFrames`（给渲染用的帧推进）。⇒ **沙盒里死掉的怪永远保留它死亡时的全部能力。**
4. 哪张牌暴露了它：防哈（`CaiNongMod:DefendToKill`，baseBlock 7、升级 10）的 `use()` 先给自身格挡，然后遍历 `AbstractDungeon.getCurrRoom().monsters.monsters`，对**每只** `hasPower(NongBaiPower.POWER_ID)` 的怪再加一次同样的格挡——**不过滤死怪**。实机那 4 只杂兵已经死了、能力已清，只 +1 次；沙盒里它们还带农白，于是 29+20=49 vs 实机 29+10=39。
5. 为什么 Digest 没报出来：`Digest.monsterStable` 刻意剥掉 `d=11`（死亡中）或 `hp<=0` 的怪的 powers/intent（B41/B75；门禁 S80 明确断言「死怪残留能力不算 drift」）。这条规则本身是为了避免无意义的抖动，但它让「死怪能力」变成一个**只在按能力计数的牌上才暴露**的盲区。

### 修法（方案 A，用户 m06264「按照 a」批准）
- 新增 `src/main/java/sts1solver/mirror/DeathSettle.java`：`settle()` 遍历 `monsters.monsters`，对 `m != null && m.isDying && !m.isDead` 的怪执行 `m.isDead = true` + `m.powers.clear()`。
  - **不调** `dispose()`：它会释放与实机共享的贴图资源。
  - **不调** `endBattle()`：战斗是否结束由 `Driver.combatOver()`/`areMonstersBasicallyDead()` 决定，死亡结算不该顺手结束房间。
  - 前 3 次结算写 `death settle: <name> (<id>) is dead, N power(s) dropped -- the live game clears powers when the death animation ends (B130)`，其余只计数（`describe()`）。
- 接线：`World.trimPresentation()`，紧跟 `EffectSettle.settle(...)` / 清空 effect 列表之后（同一个 try，异常不外抛）。这就是 P2-61/B76 建立的「表现层动画终态结算点」：它在 `Solver.replay`、`expand`、`materialize` 的每一步 `answerForReplay` 之后、`readBack`/digest 之前运行，所以搜索与回放看到的是同一个终态。

### 门禁
- **S188**（core，无 mod）4/4：合成战打死带 `StrengthPower` 的怪，断言「死前有 power」「打死只起动画、power 仍在」「settle 后 `isDying && isDead`」「settle 后 powers 为空」。
- **S189**（带 CaiNongMod）3/3：`ModStatics.resolve` 拿不到 `CaiNongMod.Powers.NongBaiPower`/`CaiNongMod.cards.DefendToKill` 时 skipped（记 1 条）；否则让杂兵带着农白被打死 → settle → 打防哈，断言 `gained == defend.block`（7，而不是 14）。
- **反向验证（证明门禁有牙）**：临时把 `DeathSettle.settle()` 注释掉重编，S188/S189 立刻 4 条红（`the settle point finishes the death animation`、`a finished death animation leaves no powers to count`、`the settle point drops the mod power`、`the dead monster does not pay the card a second time | block 0 -> 14`）；恢复后 7/7 全绿。

### 代价
- 性能：每次 `trimPresentation` 多一次对 `monsters.monsters` 的遍历（通常 1–5 项，判两个布尔），O(怪物数)，无分配；`powers.clear()` 只在真正有新死怪时发生一次。搜索每步一次，可忽略。
- 内存：无新增缓存，只有两个 int 与一个 String。
- 复杂度：新增 1 个 ~100 行文件 + `World` 里 6 行接线；语义与 `EffectSettle` 完全对称。

### 风险
- 早结算 1.8 秒的窗口：实机在死亡动画那 1.8 秒内打出的「按能力计数」的牌仍会算到那只怪，而沙盒现在算「已清算」→ 自动出牌（450ms 间隔）紧跟死亡那一步时可能**反向**差一次计数（手动出牌 2–4s 间隔通常已过窗口）。这是**已知边界**，不是回归：修前是「永远多算」，修后是「只在 1.8s 窗口内可能少算」。
- `isDead=true` 会让 `areMonstersDead()` 更早为真：但它只被 `updateDeathAnimation`/`endBattle` 使用，而这两个我们都不跑；战斗结束判定走的是 `areMonstersBasicallyDead()`（看 `isDying/isEscaping`），不受影响。core 481 条门禁（含逃离/结束回合/战斗结束相关）全绿佐证。
- 回滚：删掉 `World.trimPresentation()` 里那 6 行（或整个 `DeathSettle.java`）即可，无其它引用。

### 对 mod 的影响
- 面板：地精头子这类「死怪带农白」的战斗，预计血量/格挡不再偏高（本 bug 就是面板比实机多 10 HP 的差）。
- 自动出牌：不受影响（唯一相关的是上面那条 1.8 秒窗口边界）。
- 搜索：按能力计数的牌（防哈、攻哈跨怪数农黑、NosNib 类）的评估更接近实机；节点数/耗时无可测变化。
- 其它 mod：`DeathSettle` 只做引擎自己 1.8 秒后会做的事，不认任何具体卡/能力；任何带 `onDeath` 的能力在 `die()` 里仍照常跑（我们不介入那一拍）。

### 已知边界（如实记录）
- 死亡动画 1.8 秒窗口内打出的计数牌，实机仍会算到死者，沙盒按已清算算（见「风险」第一条）。
- 备选方案 B（在沙盒里按帧递减 `deathTimer`，仿 B128 的 `tickEscapeFrame`）能让窗口也精确，但要在每个 drain 里模拟 1.8 秒/帧率的时间推进，改动面与不确定性都更大，本轮未采用。

## B132 面板给出了实机付不起的牌：药水选屏每帧重掷，抽牌随机流被永久推偏（user m06435 / m06536）

### 现象

- 第一勇士（The Champ，A4，CaiNong）第七回合第 3 个行动，面板给「防御」，实机点不出来（能量不足）。
- 日志 21:43:23 起成片 `2 copies of CaiNongMod:Defend in hand and none carries the planned state (…|c=0/0|…); refusing instead of playing a neighbour`，随后 341 条 `计划里的「防御」不在手上了`，autopilot 卡在 step 20。
- 分叉点：`21:43:16.847 WARN step 18 came out differently than predicted (卡我启动了+ → 第一勇士（选：噶人闷,闷闷闷+）)`，expected `P hp=51/80 b=6 e=3` vs actual `e=1`，**两份 digest 除 `e=` 外逐字符相同**（手牌、牌堆、能力、遗物全部一致）。

### 根因

- 第 15 步是能力药水 → 原版 `PowerPotion` → `DiscoveryAction(CardType.POWER)`。
- 反编译 `DiscoveryAction.update()`（`_tmp/gensrc/com/megacrit/cardcrawl/actions/unique/DiscoveryAction.java:41-83`）：**第 43 行无条件** `generateCardChoices()` / `generateColorlessCardChoices()`，每次消耗 3~5 次 `AbstractDungeon.cardRandomRng`；随后才判 `duration == Settings.ACTION_DUR_FAST` 开屏。
- 沙盒答屏时自己泵帧（`pumpUntilSelection(24)` 等），泵的帧数与实机不同 ⇒ 多跑一次 `update` 就多掷 4 次左右 ⇒ 抽牌随机流永久偏移（不可逆，因为 RNG 是递推计数器）。
- 之后蛇眼 `ConfusionPower.onCardDraw` 对每张抽到的牌 `cardRandomRng.random(3)` 改写费用 ⇒ 牌名一样、费用全不同。药水之前 13 步连卡费都一致、药水之后第一次抽牌就全体不一致，恰好吻合。

### 证伪过的方向

- 镜像机制本身忠实：`SneckoProbe` 6 回合、遗物 {Snecko Eye, Unceasing Top, Bag of Marbles, Bag of Preparation, Ceramic Fish, Bottled Lightning, White Beast Statue}，每回合连打 3 张 + 结束回合，`SNECKO first difference: none`，7 条 RNG 流计数器逐回合相同。
- 保留牌重掷：`RestoreRetainedCardsAction` 只做 `card.onRetained()` + `hand.addToTop(card)`，不触发 `onCardDraw`。
- 缓存命中与本案无关：命中的计划与实机费用本来就一致（`plan cache: hit (895677140)` 之后的费用差异来自随机流）。
- `PlanCache.key()` 不含随机流状态是一个真实弱点，但用户否决「把随机流指纹写进缓存键」的路线（那仍是事后判定）。

### 用户裁定

- m06893：「那你现在是看实机的 update 什么时候会用、用多少次再反推沙盒吗，那样不就是结果反推沙盒吗，我觉得不行要修改计划」。
- m06879 三条：①能不能让沙箱药水的随机数与实机一样 ②这样改行不行 ③「没必要做这么多判定，只需要开始就做好就可以」。
- 最终批准的计划（m06908「开工」）：**按引擎自己的调度代码建模**——模态屏打开期间不推进管理器，这样 `DiscoveryAction.update()` 每次开屏只跑「开屏一帧 + 答屏一帧」，与实机帧数无关。

### 修法（B133）

- `drive/Driver.java` 新增 `public static boolean roomBlockedByScreen()` = `AbstractDungeon.isScreenUp && !peekingNow()`，且 `screen` 不属于 {NONE, NO_INTERACT, FTUE, BOSS_REWARD, HAND_SELECT}；`private static boolean peekingNow()` 反射读 `com.megacrit.cardcrawl.ui.buttons.PeekButton.isPeeking`（偷看时引擎自己仍会跑 room）。依据是 `AbstractDungeon.update()` 字节码的 switch：CARD_REWARD/GRID/HAND_SELECT 等分支只调屏幕自己的 `update()`，`ifeq` 跳过 `AbstractRoom.update()` 除非 `PeekButton.isPeeking`。
- `drain(int,long)`：在 `isAwaitingSelection` 分支之后、超时判定之前加闸门——屏等待期间 `++screenFrames; pumpScreenSelf(1); continue;`（超时仍记 `stalled`）。
- `pumpWhileScreenOpen()` 与 `pumpUntilSelection(int)` 内层：`if (roomBlockedByScreen()) pumpScreenSelf(1); else gam.update();`。
- 副产物（真 bug，不是权宜）：`scenario/Synthetic.java#initDungeonStatics` 在复位 `screen=NONE / isScreenUp=false` 之后清空 `AbstractDungeon.combatRewardScreen.rewards`。游戏自己的 `closeCurrentScreen()` 的 GRID 分支遇到非空奖励表会把 `screen` 置回 `COMBAT_REWARD`（真实战斗里不存在这种状态），新闸门一冻结管理器，链式第二屏就永远开不出来。

### 门禁

- **S190**（core，8 条断言）：合成局 → 喝能力药水 → 泵到开屏 → 断言 screen=CARD_REWARD 且 `roomBlockedByScreen()` → `pumpUntilSelection(24)` + `drain` 后 `cardRandomRng.counter` 不变、候选列表不变 → 能答屏、拿到牌进手 → 之后 counter 才增长。实跑 `pumping a screen that is waiting spends no randomness | rolls=0 offers=[Brutality, Inflame, Combust]`。
- 反向验证：把 `roomBlockedByScreen()` 短路成 `if (true) return false;` 重编 → S190 立刻 7/1 红（`FAIL the screen, not the room, owns this frame | screen=CARD_REWARD`），恢复后全绿。
- core **489/0**（S1–S190）。

### 代价

- 性能：屏等待期间不再每帧跑 `gam.update()`，实际上**更省**；只在模态屏打开时多一次 `isScreenUp`/反射读取判断（每帧一次字段读，反射结果已缓存 Field）。
- 代码：`Driver` 新增 2 个静态方法 + 3 处调用点分支；`Synthetic` 新增 3 行清表。

### 风险

- 屏幕判定用枚举白名单，若某个 mod 自定义屏（走 `AbstractDungeon.screen` 之外的路子）需要管理器继续跑，会被误冻结 → 表现为该屏期间不推进。已对照 vanilla 五个非模态屏；mod 屏目前只有 `HandCardSelectScreen`（属 HAND_SELECT，不冻结）。
- 偷看（`PeekButton.isPeeking`）走反射：字段名若被其它 mod 改掉会抛异常 → `peekingNow()` 捕获后返回 false（退化为不冻结，行为等同修前，不会更糟）。
- 回滚：删掉 3 处闸门分支与 2 个方法即可；`Synthetic` 的清表可独立保留（那本身就是真实战斗不会有的残留状态）。

### 对 mod 的影响

- 面板：随机流不再随「屏开了多久」漂移，蛇眼/混乱下给出的费用与实机一致的概率大幅提高；预览与正式计划同理。
- 自动出牌：答屏时机变早/变稳，不会再出现「泵了 24 帧才答」导致的多掷点。
- 搜索：每个节点若经过选屏，随机消耗变少且确定 ⇒ 同预算下分支的随机性与实机更接近；未改变节点数/预算口径。
- 其它 mod：只依赖 `AbstractDungeon.isScreenUp/screen` 与 `PeekButton.isPeeking`，不认任何 mod 类；mod 自绘屏按 `screen` 枚举走。

### 已知边界

- 只保证「开屏等待期间 0 掷点」。答屏之后每帧由引擎自己的 `duration` 决定（`DiscoveryAction` 每次 `update` 固定 3~5 次掷点），真机与沙盒的**帧数**仍可能不同；本次不建模真实 delta（那需要帧率模型，属另一件事）。
- `ScreenRngProbe`（mode=screenrng）的 live 侧数字是探针在游戏帧内跑出来的产物，不能当基准（`SCREENRNG first difference: 6` 不是缺陷）。

---

## B134 模拟死亡不再给外部发数据、也不再累积跑团记录（user m07304，2026-11-05）

### 现象

- `D:/Steam/steamapps/common/SlayTheSpire/sendToDevs/mts_process_launch.log` 一次会话就 415 MB（约 26495 行），其中含 `UPLOADING METRICS TO:` 的有 1171 条、合计约 122 MB（单行 10–49 KB）；另有成片的「80」「0」行。
- 「80」「0」不是另一份数据：它们是同一份 JSON 被多个线程同时写 stdout 时截断的碎片（碎片前面能看到 gson 栈帧与 `ConcurrentModificationException`）。

### 证据

- 抽第 300 条上传的 `damage_taken` 分桶：373 条记录里 floor 1×1、floor 3×348、floor 4×1、floor 6×23 —— 全部集中在用户当时所在楼层、同一只 Jaw Worm，turns/damage 各不相同 ⇒ 这些是**搜索里模拟的战斗**，不是玩家的真实跑团史。
- 追加点（字节码）：`AbstractRoom.endBattle()` offset 176-179 无条件 `CardCrawlGame.metricData.addEncounterData()`；`com/megacrit/cardcrawl/screens/DeathScreen.class` 也调 `addEncounterData`（模拟死亡走这一条）。`AbstractMonster.updateDeathAnimation()` 里也有 endBattle，但沙盒从不跑渲染帧。
- 上传链：模拟死亡真的构造 `DeathScreen` → Minty Spire 的 `@SpirePostfixPatch DeathScreen.submitDefeatMetrics`（守卫只看 `Settings.UPLOAD_DATA` 与 actNum/房间类型）→ `new Thread(t).start()` → `MintyMetrics.run()`（覆盖 run()，绕过 vanilla 的 `if (Settings.isModded) break;`）→ gson 序列化整份 run 数据 → `Metrics.sendPost` 第 66 行 `logger.info("UPLOADING METRICS TO: url=" + url + ",data=" + data)`，**在 `Gdx.net.sendHttpRequest` 之前**。
- 项目的 `ExternalMute.NetHandler` 只把请求丢掉（所以没有响应行），日志照样写、`metricData` 照样涨。

### 修法（三处，缺一不可）

1. `mirror/World.java` install()：`Settings.UPLOAD_DATA = false;`，并在 `CardCrawlGame.metricData != null` 时 `clearData()`（每个窗口从空开始）。
2. `mirror/Statics.java` build()：给 `metricData` 标 `discardOnReadBack`，模拟期间追加的记录不写回实机。
3. `mirror/ClonePolicy.java` shareClass()：不再把 `com.megacrit.cardcrawl.metrics.MetricData` 按引用共享（它原本命中 `com.megacrit.cardcrawl.metrics.` 前缀）。这条是门禁抓出来的：只做 1+2 时 S191 的「实机记录没长」断言红（before=0 after=1），而且 install 时的 `clearData()` 会真的清掉玩家当前的记录。它是纯数据容器（int / String / ArrayList<HashMap>），复制安全。

### 门禁 S191（7/7）

`Probe.core("S191 a simulated fight end neither uploads nor keeps the run's metrics (B134)")`：真造一场 Jaw Worm → 记下实机记录条数与开窗时的 `UPLOAD_DATA` → install 断言 `UPLOAD_DATA=false` 且记录为空 → 把玩家 HP 打到 1 再吃 99 点 HP_LOSS 制造模拟死亡 → 断言 `deathScreen != null`、记录只落在沙盒（1 条）→ readBack/trimPresentation/uninstall → 断言实机记录条数不变、`UPLOAD_DATA` 还原成开窗时的值 → 再 install 断言记录又是空。

### 代价 / 风险 / 对 mod 的影响

- 代价：性能**变好**。原来每次模拟死亡都要起一个线程 + 序列化整份跑团数据 + 写一行 10–49 KB 日志，并且 `metricData` 跨节点累积后每次 fork 都要深拷贝；现在窗口内没有上传线程、记录为空。代码量约 +6 行（World 2 行、Statics 3 行、ClonePolicy 2 行）。
- 风险：①`Settings.UPLOAD_DATA` 是镜像表里的 Kind.VALUE，窗口内改、uninstall 还原——S191 已断言还原；②若某个 mod 依赖「模拟里也能读到实机 metricData」，现在读不到（沙盒里为空）；③回滚＝删这三处。
- 对 mod 的影响：面板 / 自动出牌 / 搜索结果无变化；唯一的区别是模拟死亡不再给 Minty Spire 和原版上传器贡献假数据。实机自己打的那局照旧上传。

### 已知边界

- 只保证沙盒窗口内不上传；玩家真想彻底不传，得在游戏设置里关「上传数据」。
- 已经写出的 415 MB 旧日志不会自己消失，可以直接删（MTS 每次启动会新建）。
- 「日志过大」这件事的另一半原因是游戏进程 stdout 被重定向到该文件、且没有轮转——那是 MTS 启动器的行为，本轮不动。

---

## B135 豹指导选牌后「自动打出」标成了另一张牌（user m07728）

### 现象
用户日志 12:54 那局（乐加维林，CaiNong，第一回合）：打出「豹指导」并选「无人形态」，面板那一拍写的是「自动打出 嗦开嗦开」。计划原文里同类错位还有第 29 步「豹指导（选：牌烧完了我打什么）」→ 记成「拍点点」。实机 12:54:46.490 明确 `live selection answered with CaiNongMod:UnmannedForm (consumed by the waiting action)`。

### 根因（已证实）——但 2026-11-05 晚订正：这不是本步分叉的成因

> 用户 m09378 指出「刚刚也没有『一步开 4 个屏以上』啊」：实机那一步只开了 **3** 个屏（头追的手牌屏 + 两个交换 grid），
> 而计划里记了 **4** 段答案。这条观察推翻了下面「撞上限被截断」的推断——沙盒若只开 3 个屏，记录就只会是 3 段；
> 记录到 4 段说明**沙盒自己走了 4 个屏**，也就是沙盒多模拟了一屏实机没有的选牌。截断（撞上限）是**另一个真缺陷**（已在下面修掉、
> 有门禁 S196），但它不是这一步 expected/actual 分叉的成因。本步的主嫌改为「沙盒多出一屏」——下一步用本轮新装的
> `sandbox selection: kind=… offered=[…] picked=[…] changed=…` 与 `live selection: kind=… plan=[…]` 逐屏对照即可点名。

### 上一版写的（撞上限被截断）
自动出牌的记录只做一次：
1. `Solver.expand` 先在同一叉上按**每屏第一选项**（= 探针答案）跑一遍动作，用 `cardsPlayedThisTurn` 的前后差值 `recordAutoPlays(action, before, after)` 写进**父 Action**；
2. 真正的分支枚举（每个答案各起一个 fork）**从不记录** autoPlays；
3. `Action.withChoice(...)` 把父 Action 的 `autoPlays` 复制给每个分支。

于是 pill 的「（选：无人形态）」来自真分支，「（自动：嗦开嗦开）」来自探针分支——两者本可以完全不同。只有「计划选的那张 ≠ 探针第一选项」的步骤会暴露，所以同一条 trace 里多数步骤看起来是对的（选的就是探针第一选项）。

卡牌事实（反编译）：豹指导 `CaiNongMod:SealGuide` → `PlayCardAction` → `handCardSelectScreen.open("打出", …)` → 选定后 `addToTop(new NewQueueCardAction(card, true, false, true))`（autoplay=true）。无人形态不加格挡、嗦开嗦开加格挡+农白——面板那步 b 没变、只花 1 费，正是「真正打的是无人形态」的旁证。

### 影响
- 面板：「（自动：…）」标错牌（用户看到的）。
- 自动出牌：`Gameplay.submit` 在找不到计划牌时会用 `action.autoPlays.contains(cardNameOf(action))` 决定「跳过这一步」还是 `fail()`；清单陈旧会把本该跳过的步骤判成失败并停自动出牌（本局尚未触发）。
- 搜索评分不使用 autoPlays。

### 修法
1. `Solver.Choice` 新增 `java.util.List<String> autoPlays`（每分支一份）。
2. `recordAutoPlays` 拆出 `recordAutoPlaysInto(out, ownCard, ownName, before, after)`；旧签名仍委托它（`simulateForTest` 不变）。
3. `expand` 的探针叉只记 `probe.autoPlays`；分支循环在 `w.install()` 后取 before、答完屏后取 after，记 `choice.autoPlays`。
4. 新增 `Solver.actionFor(choice, action)`：把「该分支的答案（`withChoice`）＋该分支的自动出牌」写到子动作上。阻塞组合（`Solver.java` 原 1351 行）与切片会话（`expandAndRecord`）两处循环都改用它，不再经 `withChoice` 继承父列表。
5. `Solver.replay` 每步在 `Legal.applyOrRefuse` 前取 before、`answerForReplay` 后取 after，再 `recordAutoPlays(action, …)`：印出来、要执行的计划以**回放**为准（A3 promote/materialize 重建的线也覆盖）。
6. 走屏逻辑抽成 `expandCore(world, action, n, applyNow)`（`applyNow=false` 表示走「已经排进动作队列」的屏幕），新测试钩子 `expandQueuedAutoForTest` 复用同一条走法——门禁测的是产品代码，不是复制品。

### 门禁
- **S192 3/3**：测试替身 `probe/PlayChosenProbeAction`（选一张牌后 `NewQueueCardAction(..., autoplay=true)` 打出所选牌，形状与豹指导的 PlayCardAction 相同）→ 两个答案各起一个分支，断言「每个分支记录的牌 = 自己答案打出的牌」「两张牌都被记录，没有从探针复制」。
- **反向验证**：把分支记录屏蔽掉、重编，S192 立刻 2 条红（只剩探针那张 `Strike_R => 打击`、另一个分支空），恢复后全绿。
- core：**499/0（S1–S192）**；安装 jar sha256 `8742C02B527838B3071045DB69F8FB6244012B61BB7B84695DCE73104C3D284F`（1039909 B）。

### 代价
- 性能：每个候选动作的分支枚举各多做一次 `cardsPlayedThisTurn` 快照（一次小 ArrayList 复制，量级与既有的 fork/install 相比可忽略）；探针叉原本就记，不变。
- 内存：每个 `Choice` 多一个空/小列表；随节点释放。
- 复杂度：`Solver.java` 新增两个小方法 + 一个 `expandCore` 参数；`Action.autoPlays` 的语义从「父动作的探针记录」变成「这条分支的实况」。

### 风险
- 若某分支的答案在引擎里改变了 `cardsPlayedThisTurn` 的**前缀**（例如跨过结束回合），`recordAutoPlaysInto` 会按「前缀不一致 ⇒ 视为回合重启、不记」处理，该分支退化为「没有自动出牌」，不会记错牌。
- 回放侧重记会覆盖搜索时记的清单：如果回放与搜索的自动出牌不同（随机流不同），以回放为准——这正是执行时真正会发生的那次；面板显示也随之以回放为准。
- 回滚：`Choice.autoPlays` + `recordAutoPlaysInto` + `actionFor` 三处自洽，退回旧行为只需让 `actionFor` 不再覆盖（或删掉分支记录），探针叉仍可工作。

### 对 mod 的影响
- 面板：pill 上的「（自动：…）」现在是**该步实际会打出的那张**（豹指导/其它「选牌再打出」类卡）；其它步骤不变。
- 自动出牌：`skipAutoPlayed` 的判据更准，不会因为陈旧清单把可跳过的步骤判成失败。
- 搜索：评分与分支宽度不变（autoPlays 不参与评分）；随机流与实机不同这条老边界不变。
- 其它 mod：不引入任何针对豹指导/唐/昏睡的特判，只依赖 `AbstractPlayer.useCard` 与 `cardsPlayedThisTurn` 这两个引擎事实。

## B136 存档太慢 + 程序未响应 / 读档回到旧档（user m07926、m07947；方案批准 m08227）

### 现象

- 存档很慢，游戏出现「程序未响应」。
- 打别的怪时存档，读档却回到之前乐嘉维林那一场的旧存档。
- 用户 m08227 批准做方案第 1–4 项；第 5 项（改日志级别 / UPLOAD_DATA）不做，理由是「沙盒做好了应该不会上传日志，上传日志只上传玩家真实打的的话没必要改」。

### 证据（用户机器实测）

- 游戏日志 4763 条 `Dequeue`，其中 4727 条写的是 `preferences/sts1solver-sandbox/achievementPref`（24 条 `player-CAI_NONG`、12 条 `STSPlayer`）。
- `AsyncSaver.saveQueue` 从 638,475（16:29:13）降到 554,818（16:33:21）＝337 条/秒 ⇒ 清空要 ~30 分钟。
- `saves/` 里只有 `CAI_NONG.autosave`（86,676 B，11/04 23:45:31）与 `.backUp`，11/05 整局没有新档 —— 不是「存档被重定向进沙盒」（沙盒目录下没有 saves/），是**真存档排在队列尾巴上没轮到**。
- 日志 2 MB / 13–16 秒增长 ⇒ log4j 每 14 秒轮转一次；删除旧文件时 `FileRenameAction` 抛 `FileSystemException` ⇒ 渲染线程卡在 `RollingFileManager.checkRollover` 13,338 ms（dump: `hang-20261105-155659.txt`，堆 7530/7804 MB）。
- 读档卡 14,848 ms（dump: `hang-20261105-160656.txt`），栈是 `gson JsonReader.peekNumber ← CustomPlayer.<init> ← CaiNong.<init> ← CharacterManager.recreateCharacter ← CardCrawlGame.createCharacter` —— `CharStat.<init>`（`_tmp/savedecomp/.../screens/stats/CharStat.java:152-166`）把 `runs/<CLASS>` 下**全部** `.run` 反序列化一遍。
- `runs/CAI_NONG` 2530 个 `.run` / 637,535,446 B；`runs/IRONCLAD` 1813 个 / 183,967,102 B；最新几个写于 15:57:56、15:58:03（搜索进行中）。`Metrics.removeExcessRunFiles()` 开头 `if (!Settings.isConsoleBuild) return;` ⇒ PC 上永不清理。

### 根因链

1. 创意工坊模组 **AchievementEnabler**（1692554109）用 javassist 改写 `UnlockTracker` 的判据：`NeverModdedExprEdit` 把 `Settings.isModded` 的读取换成 `{ $_ = false; }`，`NeverNotStandardExprEdit` 把 `Settings.isStandardRun()` 换成 `{ $_ = true; }`（`_tmp/aesrc/AchievementEnabler/patches/UnlockTrackerPatches.java`）。原版「有 mod 就 return，不解锁也不写盘」的保护消失。
2. 于是 `UnlockTracker.java:500` 那句**无条件**的 `achievementPref.flush()` 每次都写一整份 prefs。最热的触发点是 `AbstractCreature.addBlock`：`currentBlock >= 99` 判 IMPERVIOUS、`== 999` 判 BARRICADED —— 菜农格挡轻松上百，搜索每秒上千节点，于是每秒几百次 flush。
3. 沙盒的 `SandboxPrefs` 只把 `Prefs.filepath` 改到 `preferences/sts1solver-sandbox/`（**内容**不落玩家文件），但 `Prefs.flush()` 仍然 `AsyncSaver.save(filepath, json)`；`AsyncSaver` 是**单线程**队列，而玩家的真存档 `SaveAndContinue`（`SaveAndContinue.java:253/255`）走同一条队列 ⇒ 真存档饿死。
4. 每次 Dequeue 还打 3 行 DEBUG（`File.save` 的读→备份→校验→删→写→校验），日志体积 ×N ⇒ log4j 轮转 + 删除失败 ⇒ 渲染线程停顿（「程序未响应」）。
5. 另有一条独立的 `runs/` 增长：`Metrics.gatherAllDataAndSave`（`Metrics.java:177-207`）写 `Gdx.files.local("runs"/<slot>_<CLASS>/<time>.run)`，沙盒以前只重定向 `saves/`，所以搜索里每次模拟死亡/胜利都追加一份跑团史 ⇒ 21 MB→637 MB，读档时 `CharStat` 逐份反序列化 ⇒ 14.8 秒。

### 修法（已落地）

- 新增 `src/main/java/sts1solver/mirror/SandboxWrites.java`：两个 `@SpirePatch` —— `Prefs.flush()` 与 `AsyncSaver.save(String,String)`。目标路径是沙盒重定向路径（`SandboxPrefs.owns(filepath)`）时直接 `SpireReturn.Return(null)`：**既不序列化、也不进队列、也不落盘**；计数 `dropped()`，前 3 条记 `sandbox writes: dropped a sandbox preference save (...)`。真实路径（玩家的档）原样放行。
- `src/main/java/sts1solver/mirror/ExternalMute.java`：`isSavePath` 改名 `isSandboxPath`，从 `saves/` 扩到 `runs/`；命中 `runs/` 单独计数 `runTouches()` 并记一条 `external mute: a simulated run-history write was redirected (...)`。
- 存量清理（方案第 4 项）：`runs/CAI_NONG`、`runs/IRONCLAD`、`runs/THE_SILENT`（合计 783.4 MB）整体移到 `D:/Steam/steamapps/common/SlayTheSpire/sts1solver-cleanup-backup/`（**没有删除**，需要时放回即可）；`sendToDevs/mts_process_launch.log` 删除（MTS 每次启动重建）。

### 门禁

- **S193** 2/2：窗口内 `new Prefs()`（filepath = `SandboxPrefs.relative(...)`）+ `flush()` ⇒ `SandboxWrites.dropped() >= 1`，且连沙盒副本文件都不存在。
- **S194** 3/3：窗口内 `Gdx.files.local("runs/STS1_GATE/<t>.run").writeString(...)` ⇒ `ExternalMute.runTouches()` 增加、真 `runs/` 没多文件、`sts1solver-sandbox/runs/...` 有那份副本。
- **S195** 1/1：窗口外正常 `Prefs.flush()` 仍能落盘（文件出现后删除），确保没把真存档一起挡掉。
- core **507/0**（S1–S195；旧基线 499）。

### 代价 / 风险 / 对 mod 的影响

- **代价**：每次 `Prefs.flush`/`AsyncSaver.save` 多一次 `String.startsWith` 判定（纳秒级）；沙盒里被丢弃的写请求不再排队 ⇒ 存档线程负载明显下降。内存无变化。
- **风险**：①判定只看路径前缀 `preferences/sts1solver-sandbox/` —— 若将来有别的沙盒目录忘了登记，写请求不会被丢（但也不会误伤真存档）；②`AsyncSaver.save` 的前缀补丁对所有调用方生效，若某个 mod 直接把路径写成沙盒目录，也会被丢（这正是意图）；③回滚＝删 `SandboxWrites` 的两个 patch 与 `isSandboxPath` 里 `runs` 两项即可，互不依赖。
- **对 mod 的影响**：面板 / 自动出牌 / 搜索无变化；**存档体验变好**（不再排在几十万条假写之后）；`runs/` 不再被搜索撑大；成就照旧由 AchievementEnabler 判定，只是沙盒不再把它的写请求排进玩家队列。

### 已知边界

- AchievementEnabler 本身没改：玩家在实机上真的打出一次 `addBlock >= 99`，游戏仍会写一次真 prefs（正常量级，不存在积压）。
- 被丢弃的写只针对「沙盒路径」；`PlayerPrefs` 的 leak 恢复逻辑不变。
- 存量 `runs/` 只是移走，没有删；`sts1solver-cleanup-backup/` 可以整个删掉，但删了就没有历史跑团记录了。

## B136 补：成就写盘的热点到底是哪个（user m08397 / m08552）

用户 m08397：「但是菜农不是随便上百防御啊，这点你是不是搞错了」。**是搞错了。**B136 汇编写的是「`AbstractCreature.addBlock` 的 `currentBlock >= 99` 是热触发点」，这条没有证据支撑，而且与那局数据矛盾。

### 已证实的（本轮实测）
- 用户 11-05 那局的所有归档 digest 里玩家格挡 `b=` 最大 21–28（`logs/` 全目录 1404 个 `b=\d+` 样本：max=28，>=50 为 0）⇒ `addBlock >= 99`（IMPERVIOUS）在他们那局**不可能**是热点。
- 成就写盘的唯一入口仍是 `UnlockTracker.unlockAchievement` 末尾的无条件 `achievementPref.flush()`（javap offset 118-121），`AchievementEnabler` 把 `Settings.isModded` 抹成 false 让这道门失效。
- **带 AchievementEnabler（`-AllMods`）的实测**（探针 mode=ache，现场构造条件）：
  - 不给成就条件时不写盘（`dropped=0`）；没有 `-AllMods`（AchievementEnabler 未加载）时，原版 `if (Settings.isModded) return;` 直接把一切挡掉——这也解释了为什么第一次跑「什么都没发生」。
  - 玩家身上第 10 个 BUFF 能力落下 → 写入 1 次（`from AbstractCreature.addPower:604`，POWERFUL）；第 11 个再加 1 次。
  - `currentBlock` 98 + 5 → 103 → 写入 1 次（`from AbstractCreature.addBlock:503`，IMPERVIOUS）。
  - **真实 CaiNong 搜索（10 个 BUFF 起步，564 节点 / 431 ms）：共丢 243 次写盘**，其中 `ApplyPowerAction.update:305` **167 次**、`CharStat.incrementEnemySlain:305` 90 次（`player-CAI_NONG`）。即「身上有 10+ BUFF 时，每一次 `ApplyPowerAction` 都会跑 POWERFUL 判定 → 无条件 flush」。
- 用户那局（`logs/solver-20261105-152947.log`）的 `quick search: started, budget=1000000000ms`：搜索**不限时**连跑 57 分钟，节点数巨大；队列峰值 638,475 条、337 次/秒，与「约 0.3 次 flush/节点」的量级一致；同期 `player-CAI_NONG` 只有 24 条（每杀一只怪才一次），也符合「杀怪远少于节点」。

### 结论（定案到机制，不假装知道那局的成就 id）
- 热点类别 = **条件一旦成立就每次动作都判定并在已解锁状态下仍整份写盘**的成就调用点；在这类菜农长战里最强的是 **POWERFUL（10+ BUFF，`ApplyPowerAction`/`addPower`）**，其次是**每杀一只怪的 `CharStat` 统计写盘**。
- `addBlock >= 99`（IMPERVIOUS）只在这局之外成立（普通原版战确实能上百格挡），不是这一局的原因。
- 那局的成就 id 已经无法反查：MTS 的 `mts_process_launch.log` 在 B136 的存量清理里被我删了（我的失误，本该保留），solver 日志里也没有成就行。**这条只影响诊断，不影响修复**——B136 的修法是「沙盒路径的写盘一律丢弃」，与具体成就、具体条件无关。
- 探针（`probe/AchievementProbe.java`、`SandboxWrites` 里的 callerSite/SITES 统计）已完成任务并**删除**：`Thread.getStackTrace()` 在每次丢写盘时都走一遍，而按上面的实测这发生在每节点量级，诊断代码本身会成为新开销。计数 `dropped()` 保留。

## B137 我们自己的日志把 solver.log 写成 17 MB/会话（user m08397 追问的日志侧）

**现象**：`C:/Users/YOKI/sts1-engine-solver/logs/solver-20261105-152947.log` 17,049,756 B / 248,963 行，其中 **247,866 行**是 `reward pick answered with <cardId>`（99.5%）；同批 `153859.log` 也是 248,980/17 MB。log4j 每十几秒轮转一次，每轮都要删旧文件+重开（B136 量到 `RollingFileManager.checkRollover` 卡渲染线程 13,338 ms）。

**来源**：`Driver.answerCardReward` 每答一次 Discovery / choose-one / Codex 屏就 `Log.info` 一行；搜索在沙盒里每开一次这种屏都会答一次 ⇒ 行数 ≈ 节点数。

**修法**：`Driver.noteRewardPick(cardId)`：一段突发只在第一条写 `reward pick answered with <id>`，之后最多每 5 秒写一行 `reward picks answered: N in the last Ss (last <id>, M so far)`；新增 `Driver.rewardPicks()` 计数。判据、答复行为、失败分支的 `WARN` 全部不动（失败是稀有事件，必须逐条可查）。

**代价**：每答一次屏一次 `System.currentTimeMillis()` + 两个 volatile 自增，可忽略；日志写盘量从「O(节点数) 行」降到「O(秒) 行」。
**风险**：复现某个具体节点的答屏细节不能再逐条查，只能看计数与「最后一张牌」；如需逐条，临时把 `REWARD_LOG_WINDOW_MS` 调成 0 即可（一处常量）。
**对 mod 的影响**：面板、自动出牌、搜索、其它 mod 兼容性均无变化——这是纯日志聚合。

**验证**：core `passed=507 failed=0`（S1–S195，无新门禁：日志聚合不改行为，不新增断言）；jar sha256 `CDF3F6477B377F9A18205EDA2B803F71CEA19249B8E303D33B228A95FF7FBC66`（1,049,395 B）。

## B139 一步开多个选牌屏时，沙盒被上限截断（user m08865 / m08974）

### 现象

守护者（A5，CaiNong）那局，唐（TangPower）在回合开始自动打出「豹指导」（SealGuide），豹指导再让你选一张牌打出——你选了「头追」（HeadChase），头追又连开两个 grid（先弃牌堆、再抽牌堆）把两张牌互换。这一整串都发生在**同一步**里。

面板/计划给出的答案是 4 段：「结束回合（选：头追 → 打击 → 豹指导+ → 嗦开嗦开）」，实机只用了前 3 段；drift 里 expected/actual 两份 digest 除一次换牌外逐字相同：

- expected `D=CaiNongMod:SealGuide+,Strike,Defend,...` / `X=SuoKai,HeadChase,Strike,Snack`（两个堆一张没动）
- actual `D=Strike,Defend,...,SuoKai,...` / `X=SealGuide+,HeadChase,Strike,Snack`（弃牌堆的嗦开嗦开与抽牌堆的豹指导+ 换了位置）

三次重载（19:08、19:09、19:21）的 expected 与 actual 逐字相同 ⇒ **确定性差异**，不是随机运气。

### 根因（已证实）

`Solver.expandScreens()` 走屏循环的上限是 `Limits.screens()`，默认档 `SCREENS_HIGH = 4`，而且**到上限就直接 return，既不补泵也不报错**：

- 沙盒走到第 4 个屏就停手，acting action 还停在半路（`ExchangeCardsOfTwoPilesAction` 要两个 grid 都答完才搬牌）；
- 紧接着 `trimPresentation()` / `readBack()` / `Digest.ofCurrent()` 把这个「半条链」当成这一步的结果存下来；
- 于是计划里记了 4 段答案（第 4 段在实机根本用不上），digest 描述的是两个堆还没换的状态，搜索之后的所有分叉都建在这个错状态上。

为什么以前没暴露：进入这个功能（B122 记 `turnBefore/turnAfter`、B135 记 `autoPlays`）之后，一步内开多个屏才变成常态；唐 + 豹指导 + 头追正好把屏数推过 4。

### 修法（不做卡牌特判）

1. `search/Limits.java`：`SCREENS_LOW 2→4`、`SCREENS_HIGH 4→16`、`SCREENS_UNLIMITED 8→32`（`limits.screens=` 仍可单独覆盖）。
2. `search/Solver.java` 的 `expandScreens()`：循环结束后（= 撞到上限才会走到这里）补一次 `Driver.pumpUntilSelection(24)`，让最后一个答案先结算；补泵后如果仍有屏在等，`Log.warnEvery("screens", 5000L, …)` 明说「这一步开的屏超过 N 个、记录的答案是半条链、回读的状态是半条链（调 limits.screens=）」——**不静默**。
3. 门禁 S196（`probe/Probe.java`）+ 测试替身 `probe/MultiScreenProbeAction.java`：一个动作在一步内对 6 个不同 CardGroup 连开 6 个 grid 屏，断言走屏记录到 6 屏。反向验证：把 `SCREENS_HIGH` 改回 4，同一门禁红 `screens=4 [screen 1..4]`；改回 16 绿。
4. 顺带订正 S140 的档位数字（`low=12/8/4/…`、`high=28/48/16/…`、`unlimited=…/32/…`）。

### 代价

- 性能：只有「一步开屏数 > 4」的少数步骤会多走几屏/多泵 ≤24 帧；普通步骤（0–2 屏）完全不变。核心套件耗时无变化。
- 代码：`Limits` 三个常量 + `expandScreens` 末尾 6 行；新增一个只被门禁使用的测试替身。
- 搜索：一步内屏更多时分支组合会变多（每屏的组合仍被 `Limits.choiceBranches` 与 `Limits.combos` 限住），极端情况下同预算下会「更宽而不更深」。

### 风险

1. 屏幕档位放宽后，理论上更长的屏链可以被走到，单步展开成本上升；由 `choiceBranches`/`combos` 兜底，core 全套 509/0 无回归。
2. `pumpUntilSelection(24)` 会推进 ≤24 帧：只发生在上限那个分支里，不影响正常收尾；若某个屏的 `update()` 在空选择时会自关，日志会先出现 `a step opened more than N …`，可据此把该键调低。
3. 回滚：把 `Limits` 三个常量改回 2/4/8 即恢复旧行为（S196 会随之变红）。

### 对 mod 的影响

- 面板：一步开多屏时，计划里的答案段数会与实机一致（不再多出用不掉的一段）。
- 自动出牌：`Gameplay.answerNextScreen()` 仍按位置作答；prepending 段数正确以后错位概率下降。
- 搜索：见上（分支变宽）。
- 其它 mod：不加载 mod 时行为不变；任何一步开 >16 个屏的 mod 行为（极罕见）会在日志里被点名而不是被静默截断。

### 已知边界

- 这次只修「走到上限就静默收尾」这一条路径；实机为什么把豹指导+ / 嗦开嗦开换成了与沙盒不同的两张牌，仍只能靠新加的 `sandbox selection: kind=… offered=[…] picked=[…] changed=…` 与 `live selection: kind=… plan=[…]` 两行在下一次复现时对照（日志已具备，本轮未再复现）。
- ~~`Driver.selectionQuestion()` 对「同一动作对着**同一个** group 连问两次」仍认不出是新问题（文档里写明已知限制，现有原版/mod 卡都不这么做）。~~ **已订正并修复（B141）**：后半句是错的（头追就是对弃牌堆/抽牌堆各开一块 grid，S199 还覆盖了同一 group 连问两次），现由 `mirror/ScreenOpens` 的引擎侧开屏计数识别，见 B141 节。

## B140 选牌屏的「答案」没对上「问题」：整段搬牌静默空转（user m09444 / m09491 / m09586）

### 现象（用户 19:2x 守护者战，logs/solver-20261105-192226.log）

- 计划第 32 步原文 `结束回合（选：头追 → 打击 → 豹指导+ → 嗦开嗦开）`，trace `32:t=6->7 hp=65 b=0 e=7 mhp=97`。
- 实机五次逐字复现同一处漂移（18:48:46.911 / 18:50:25.454 / 19:08:15.757 / 19:09:38.994 / 19:21:59.813）：`WARN step 32 came out differently than predicted`，两份 digest 除一次换牌外逐字相同 ——
  expected `D=SealGuide+,Strike,Defend,NongEye,Strike,Statuette,Strike,PaiDianDian,OffItShrug,Defend,Defend`、`X=SuoKai,HeadChase,Strike,Snack`；
  actual `D=Strike,Defend,NongEye,Strike,Statuette,Strike,SuoKai,...`、`X=SealGuide+,HeadChase,Strike,Snack`。即实机真的做了「弃牌堆 嗦开嗦开 ↔ 抽牌堆 豹指导+」，沙盒两个堆一张没动。
- 实机只开了 3 个屏（19:21:55.621 HeadChase / 56.393 Strike / 57.762 SealGuide），计划记了 4 段答案，第 4 段 嗦开嗦开 从未被用掉。两侧堆的张数都是 11/4（若移动块执行过会变），说明 `ExchangeCardsOfTwoPilesAction` 的搬牌循环根本没跑。

### 根因（已证实）

1. `Solver.expandScreens` 记录每屏的候选（ids/displays/uuids/prints），但**不记录这块屏是什么种类、也不保证链走完**；重放 `answerForReplay` 只按位置把第 i 段答案塞给当时打开的屏。
2. `Driver.fillGridSelection` 把答案填进 `screen.selectedCards` 时用 `rebind` 匹配：uuid → id+timesUpgraded，找不到就**兜底返回原对象**塞进去。答案属于另一块屏时，screen 里收到的是屏幕本来没有的卡对象。
3. `ExchangeCardsOfTwoPilesAction` 在 `chooseTimes==2 && !selectedCards.isEmpty()` 时才执行 `discardPile.removeCard(c1); hand.moveToDeck(c1,true); drawPile.removeCard(c2); hand.moveToDiscardPile(c2);`；`CardGroup.removeCard` 按身份匹配失败**不抛异常**，而 `moveToDeck` 又无条件往抽牌堆加 —— 结果就是「一张牌没动」，无日志无异常，`readBack/digest` 把实机不可能出现的状态当成这一步的结果存下，后续每一步都带着它算。
4. 同类缺口还有「屏链没答完」：`pumpUntilSelection` 不到屏就静默 return，`trimPresentation/readBack` 把半条链当结果（B139 只堵了撞上限那条路径）。

### 修法（不特判卡牌、不静默）

- `search/Action.java`：新增 `public String screenKinds`（每屏种类，`|` 连接），三个副本方法继承。
- `search/Solver.java`：`Choice.screenKinds` 逐分支记录，`actionFor` 写进子动作；`expandScreens` 改为返回「链是否走完」（空候选 / 答屏失败 / 撞上限 ⇒ false），`expandCore` 遇 false 丢弃这一步全部分支；分支枚举里 `!answered` 的分支不写入结果并打 `screens-branch` 警告；`answerForReplay` 每屏先 `checkScreenKind`（记录的种类与当前屏不符 ⇒ `ReplayException("screen-mismatch")`），答完最后一段再 `checkChainFinished`（仍有屏在等 ⇒ `ReplayException("chain-unfinished")`）。

### 门禁 S197（2/2）

一步开两屏的替身 `MultiScreenProbeAction({Strike_R,Defend_R})`，只给一段答案 ⇒ 必须抛 `kind=chain-unfinished`；把答案记成 `HAND` 而实际是 `GRID` ⇒ 必须抛 `kind=screen-mismatch`。（首版两条都 FAIL 因为断言查 message；kind 在 `ReplayException.kind` 字段里。）

### 代价 / 风险 / 对 mod 的影响

- 代价：每步多存一串屏种类（几十字节）；有屏的步骤每屏多一次 `settleForReplay(12)` 泵帧；半条链/错屏的线会被丢弃，搜索分支变少（少找到的路本来也是假的）。
- 风险：以前「多屏但答案不够」也能出计划，现在会判不可重放 ⇒ 某些局计划变短（是真话，不是退化）；`screenKinds` 为空的旧缓存计划行为不变（向后兼容）；回滚＝去掉两处 check 调用与 `expandScreens` 的 false 返回。
- 影响：面板不再显示「（自动：…）」式的假路线；自动出牌遇屏链不符会停下并留 `screen-mismatch`/`chain-unfinished` 日志；不涉及任何卡牌特判，其它 mod 的自定义屏同样按种类核对。

## B141 头追第二块选牌屏被关掉：实机搬牌整段没发生（user m09905）

### 现象

实机日志（C:/Users/YOKI/sts1-engine-solver/solver.log，23:47:29 与 23:48:08 两次逐字复现）：

- `INFO live selection answered with CaiNongMod:PaiDianDian (consumed by the waiting action)` 之后紧接
  `WARN the plan recorded 2 selection answers for this step but the fight opened 1; the rest were never used (CaiNongMod:PaiDianDian|Dazed)` 与 `WARN step 13 came out differently than predicted（头追 → 被拣选者（选：拍点点 → 晕眩））`。
- 实机只答了第一块 grid，第二块从未被答；`ExchangeCardsOfTwoPilesAction` 在 `chooseTimes==2` 才搬牌 ⇒ 两个堆一张没动，计划里第二个答案（Dazed）永远用不上。

### 根因（反编译源码逐行确认）

`{proj}/_tmp/hc2/src/CaiNongMod/actions/ExchangeCardsOfTwoPilesAction.java` 的 `update()`：`duration==ACTION_DUR_FASTER` 时 `case 0` 开第一块 grid 并 `++chooseTimes`；吃掉第一张后取 `c1`，然后 `if (chooseTimes < 2) { this.duration = Settings.ACTION_DUR_FASTER; return; }` —— **不清 selectedCards**，直接 return；第二块 grid 要到**下一帧**的 `case 1` 才 `open(drawPile)`，而 `GridCardSelectScreen.callOnOpen()` 在打开时同步 `selectedCards.clear()`（`_tmp/gsel/.../GridCardSelectScreen.java:493`）。

我们对 GRID 的「已消费」判据是 `gridSelectScreen.selectedCards.isEmpty()`（`Driver` 内 `selectionConsumed()`）：第二块屏一开就「空」⇒ 判成已消费 ⇒ `AbstractDungeon.closeCurrentScreen()` 把刚开的第二块关掉 ⇒ 动作之后读到空选择、`isDone`，无异常、无日志。

沙盒路径同源：`pumpWhileScreenOpen` 用 `selectionQuestion()` 的 **group identity 指纹**判「换屏」，同一动作对同一个堆连问两次时指纹相同（B139 节那条「已知限制」其实把事情说反了，见文末订正）。

### 修法（不特判卡名、不读 digest 反推）

1. 新增 `src/main/java/sts1solver/mirror/ScreenOpens.java`：`@SpirePatch` 打三个真实入口——`GridCardSelectScreen.open(CardGroup,int,String,boolean,boolean,boolean,boolean)`、`HandCardSelectScreen.open(String,int,boolean,boolean,boolean,boolean,boolean)`、`HandCardSelectScreen.open(String,int,boolean,boolean)`（4 参是独立实现、不委派），只做 `opened` 自增与 `lastKind#lastGroup` 记录。
2. `Driver.answerLiveSelection`：进函数记 `openedBefore = ScreenOpens.count()`；泵完后若计数增长且 `selectionKind() != NONE` ⇒ **不关屏**，`lastSelectionConsumed = true` 并返回 true，交由 `Gameplay.answerNextScreen` 用 `pendingChoiceAt` 递进答下一段。
3. `Driver.pumpWhileScreenOpen`（沙盒路径）：换屏判据改为 `ScreenOpens.count() != openedBefore || !now.equals(before)`，同堆连问两次也能认出是两块屏。

### 门禁

- **S198**（真机路径，seed 9198，测试替身 `probe/TwoScreenPickProbeAction`，形状照抄头追：答完第一块不清 selectedCards、下一帧才开第二块）：修前 **3 条红**——`the picker the action opened next stays up for the next answer | kind=NONE`、`the second answer is accepted | not-settled: no select screen is open`、`the action reads BOTH answers, not just the first | finished=false opened=2 picks=Strike_R,-`；修后 `finished=true opened=2 picks=Strike_R,Defend_R`。
- **S199**（沙盒路径，seed 9199，两块屏共用**同一个** CardGroup，走 `Driver.resolveSelectionByNames`）：修前修后都绿 ⇒ 它是**回归护栏**、不是复现器（沙盒路径此前已能过）。
- 修复后 `-Gates "S196,S197,S198,S199"` 16/16；core **523/0**（S1–S199）；jar `C8B7C7BD…6807`（1,072,996 B）已装。

### 代价 / 风险 / 对 mod 的影响

- **代价**：每开一块屏一次静态自增（纳秒级，只发生在开屏那一帧）；每个答案多两次 `ScreenOpens.count()` 读；搜索无额外开销（判据更准，分支不变）。
- **风险**：①若某 mod 在回答当前屏的同一帧里开一块**与该动作无关**的屏，我们会把「保留」当成链的下一段——`Gameplay` 拿计划里的下一段去答它，答不上会 `refused` 并停下，不会假装成功；②`ScreenOpens` 只覆盖两侧的公开 `open` 重载，若 mod 自绘屏/直接改字段，计数不动、行为回落到旧的指纹判据（不比现在差）；③回滚＝撤掉 `Driver` 两处 hunk（`ScreenOpens` 留着也不产生副作用）。
- **影响**：面板/计划里「（选：A → B）」的段数会真的被用掉；自动出牌能答第二块屏；其它 mod 的自定义屏仍按种类（GRID/HAND）走，不认 mod 类名。不涉及毒种/局外收益（m09905-2 已明确不做）。

### 订正（B139 节的已知边界）

B139 原文写「`Driver.selectionQuestion()` 对同一动作对着**同一个** group 连问两次仍认不出新问题（现有原版/mod 卡都不这么做）」——后半句是错的：头追 `ExchangeCardsOfTwoPilesAction` 正是对弃牌堆、抽牌堆各开一块 grid（S199 用同一个 group 覆盖更狠的形状）。该边界由本节的引擎侧开屏计数消除。

## B142 实机自动结束回合跳过了整个回合末结算（user m10519）

**现象**：自动出牌结束回合后，实机没有跑回合末该跑的东西；面板计划里那一步的掉血/能力结算因此对不上。

**根因（反编译 + 代码对齐）**：
- 引擎里发起结束回合的唯一正统入口是 `ui/buttons/EndTurnButton.disable(boolean)`：`InputHelper.moveCursorToNeutralPosition(); AbstractDungeon.actionManager.addToBottom(new NewQueueCardAction()); this.enabled=false; …; AbstractDungeon.player.endTurnQueued = true; AbstractDungeon.player.releaseCard();`（`_tmp/gensrc/com/megacrit/cardcrawl/ui/buttons/EndTurnButton.java:142-156`）。
- `GameActionManager.getNextAction()` 只有碰到那个空 `CardQueueItem` 哨兵（`NewQueueCardAction` 产生）才会调 `callEndOfTurnActions()`。
- 我们实机路径（B142 前 `Gameplay.java` submit 的 `action.isEndTurn()` 分支）只置 `player.endTurnQueued = true` ⇒ 没有哨兵 ⇒ 回合末遗物、PreCard 能力、充能球触发、所有手牌 `triggerOnEndOfTurnForPlayingCard()`（灼伤/腐朽/Regret）、姿态结算全部跳过。
- 沙盒自 B114 起在 `Driver.endTurn` 里显式 `addToBottom(new NewQueueCardAction())` 再 `runEndOfTurnQueue`，所以沙盒有、实机没有 —— **不对称本身即根因**，与具体卡/mod 无关。

**修法**：
- `drive/Driver.java` 新增 `pressEndTurnButton()`：`AbstractDungeon.overlayMenu.endTurnButton` 存在就 `disable(true)`（玩家点按钮的同一条路径）返回 true；没有按钮时退化为 `NewQueueCardAction` + `endTurnQueued = true` 返回 false。
- `Gameplay.java` 结束回合分支改调它；退化时打一行 WARN。
- 克隆安全：`ClonePolicy` 对 `ui.buttons.*` 返回「复制」，安装世界里的按钮是克隆，`disable` 不会改动玩家真实按钮的显示状态。

**门禁**：S200（4 条）——合成战斗塞入灼伤（回合末 2 伤、无格挡）→ 按按钮 → 断言按钮真的被按、灼伤先还在手牌、之后被回合末块消耗、玩家掉 2、回合 +1。

**验证**：`-Gates "S64,S200,S201"` 15/0；core **532/0**（S1–S201）；jar `F023BDD72255C2D1DF5D5498437367897F16155F7E52D568418B7A41EFD54D14`（1,078,365 B）。

---

## B143 面板：「停止」并入「计算」，「局外收益」开关（user m10519）

**需求**：①删掉面板上的「停止」按钮（与「计算」重复）②加一个「是否计算局外收益」的开关。

**实现**：
- `ui/PanelLayout.java`：`IDS` 去掉 `stop`、在 `potion` 后加 `growth`（按钮数不变，布局/命中判定无需改）。
- `ui/Panel.java`：`activate` 删 `stop` 分支（搜索中再点 `solve` 即停，标签一直写着「计算中…（点=停）」），新增 `growth` → `GrowthBook.toggleCounting()`；`labelFor` 给 `growth` 返回 `GrowthBook.countingLabel()`（`局外收益: 计` / `局外收益: 不计`）。
- `search/GrowthBook.java`：新增 `COUNTING = "count"`（settings.txt 键 `growth.count`，缺省＝计）、`counting()`（与预算共用 500ms 缓存）、`toggleCounting()`（写文件＋日志）、`countingLabel()`、`overrideCountingForTest()`；`credit()` 与 `enabled()` 开头统一按开关短路。写入复用新拆出的 `writeRaw(key, value)`（`write(key, tenths)` 现在委托给它）。
- 过时文案订正：`Gameplay.java` 4 处「面板「停止」」改成「再点「计算」」，`ui/Budget.java` 与 `ui/DeepBudget.java` 同步；`Panel`/`PanelLayout` 类注释改为当前按钮清单。

**门禁**：S64 的 expected 数组改为含 `growth`、删掉 `activate("stop")`，并新增一条「开关点一下翻转、再点回原样」；**S201**（4 条）钉住语义——开：同一份成长仍被计价；关：`credit=0` 且 `enabled()=false`；标签两种说法都对；测试后开关重新从 settings.txt 读。

**验证**：`-Gates "S64,S200,S201"` 15/0（S201 报 `on=true/true/38/局外收益: 计 off=false/false/0/局外收益: 不计`）；core **532/0**；jar 同上。

**订正（B144）**：本节写的两种标签文案 `局外收益: 计 / 局外收益: 不计` 在 B144 缩短为 `局外: 计 / 局外: 不计`（原名放到 138 单位宽的按钮里会被屏幕右缘裁掉），S201 的断言同步改了。

**边界（如实记录）**：关掉开关只影响**评分**，不影响搜索对局内状态的模拟；金币仍不进 digest/面板（B126 的「digest 不含 gold」未变），所以「实机多了 50 金」这种差异在面板上依然看不见 —— 要看它，需要另做「局外收益」显示面（未做，等用户点）。

---

## B144 面板「局外收益」按钮状态字被屏幕右缘裁掉（user m11047 第 4、5 条）

**现象**：用户看不到「局外收益」按钮当前是开还是关，原话是「现在『行』『不行』在屏幕边缘看不见」——源码里没有「行/不行」字样，实际被裁的是 `计` / `不计` 这两个状态字。

**根因（用引擎常量算出来的，不是猜）**：
- `ui/PanelLayout.java:43-92`：面板 `x0 = 屏宽 - 300*scale - 18*scale`，按钮宽 138*scale、两列间距 8*scale ⇒ 第二列按钮右边缘在 `x0 + 284*scale`，距屏幕右缘只剩 **34*scale**。
- `ui/Panel.java:446-457` `drawButton` 里 `font.draw(sb, label, x + 10.0f*Settings.scale, …)`：左内边距 10*scale，可用宽度 118*scale，**从不量宽、不缩放、不截断**。
- 字体：`FontHelper.topPanelInfoFont = prepFont(26, false)`，`prepFont` 里 `p.size = Math.round(size*fontScale*Settings.scale)` ⇒ CJK 字宽 ≈ 26*scale。
- 标签「局外收益: 不计」＝ 6 个汉字 + `: ` ≈ **171*scale**，比可用宽度多 53*scale，比右缘留白多 43*scale ⇒ 末尾两个字被屏幕右缘裁掉；「局外收益: 计」（5 字）不裁但会画出按钮外。

**修法（两条，均通用）**：
1. `search/GrowthBook.countingLabel()` 缩短为 `局外: 计` / `局外: 不计`（≈93/119*scale，落在 118*scale 内，即使超出一点点也会被第 2 条兜住）。
2. `ui/Panel.java`：`drawButton` 的文字绘制抽成 `drawLabel`——用 `GlyphLayout`（静态复用一个实例，不逐帧分配）量宽，超过 `w - pad*2` 就 `font.getData().setScale(maxW/textW)` 缩字，`finally` 里还原 `1.0`。这同时治好了另一处同一缺陷：`计算中…（点=停）`（≈9 字）、`自动: 算完开始`（≈7 字）原本会画出按钮、压到相邻按钮的文字上（那一列没被屏幕右缘裁，所以以前没被发现）。

**顺带修的门禁缺陷（如实记录，不是为过门禁而放宽）**：S201 最后一条原本写死「测试后 `GrowthBook.counting()` 应为 true」，而 2026-11-06 12:32 用户在游戏里把开关关了（`C:/Users/YOKI/sts1-engine-solver/settings.txt` 里 `growth.count=0`）⇒ 这条断言在依赖玩家自己的设置，带这份 settings.txt 跑必红。改为：`invalidate()` 后直接读 `settings.txt` 的 `growth.count` 实际值，再与 `counting()` 比较（本轮报 `file=false got=false`）。

**门禁**：`-Gates "S64,S201,S200"` **15/0**；S201 四条语义不变。

**验证**：core **532/0**（S1–S201）；jar `A0F4D95AC27B0D957A8324C22F1A477F5F02879CEB7B6C48D0FD22732B1E8D69`（1,079,451 B）。

**代价**：每个按钮每帧一次 `GlyphLayout.setText`（10 次量宽，微秒级，不分配内存）；缩字只在超宽时发生，最长标签约缩到 0.65 倍。**风险**：①缩字后基线不动，极端长标签会略微偏低（视觉可接受，点击区域不变）；②`font.getData().setScale` 在 `finally` 还原 1.0，若以后有调用方给这支字体设了非 1 的基准缩放会被重置——目前面板没有这种用法；③文案变短（`局外` 而非 `局外收益`），若觉得不好认，改回长文案也不会再被裁（第 2 条兜住）。**对 mod 的影响**：只改面板绘制与按钮文案，不影响搜索、评分、自动出牌、digest；`growth.count` 语义不变。

**边界**：这是排版修复；「关掉开关后点播为什么还会被打」是另一个问题（评分里没有「白打一张牌」的代价，见 PLAN-WORKLOG 同日记录），本轮未改评分。


## B145 S112 成长计价门禁依赖玩家设置（core 4 条红）

**现象**：安装 B144 的 jar 后跑全量 core，`passed=528 failed=4`，4 条红全在 **S112**（`max HP, gold and Genetic Algorithm plays are priced` / `Ritual Dagger pays 0.5 HP…` / `Lesson Learned pays 5 HP…` / `Alchemize pays for the potion…`），report 里 `credit=0 ritual=0 ritualScaled=0 lesson=0 alchemize=0`。

**根因**：`Probe.s112Growth()`（Probe.java:12640-12688）只钉预算（`GrowthBook.overrideForTest(int[])`），从不钉开关；而 `GrowthBook.credit()`（GrowthBook.java:257-260）第一行是 `if (now == null || start == null || !counting()) return 0;` ⇒ 玩家 2026-11-06 12:32 在游戏里把「局外收益」关掉后（`C:/Users/YOKI/sts1-engine-solver/settings.txt` 的 `growth.count=0`），这份 settings.txt 一存在，这个门禁就必红。与 S201 同一类缺陷（B144 已修 S201 那条）。

**修法（只改门禁，不动产品逻辑）**：`s112Growth()` 开头记 `countingWas = GrowthBook.counting()` 并 `overrideCountingForTest(Boolean.TRUE)`；结尾 `overrideForTest(null)` 之后再 `overrideCountingForTest(null)`，并把还原判据扩展成 `restored = restored && counting() == countingWas`（这样「测试改了开关却没收回去」也会被 S112 末条钉住）。

**验证**：`-Gates "S112,S201,S64"` **20/0**（S112 报 `credit=23 ritual=1 ritualScaled=15 lesson=10 alchemize=9 … restored=true`）；core **532/0**（S1–S201）；jar `ABD6F6E5499325D17076204E0F158638C5E7CC8995DB91A293EC82AB64A27BBA`（1,079,507 B）。

**订正（B146）**：本节的数字与门禁文案已被 B146 改掉——金币从 `GrowthBook` 退役后，S112 的期望是 **`credit=21 goldOnly=0`**，报告里不再有金币那一项；「4 条红」的报告文案也从 `max HP, gold and Genetic Algorithm plays are priced` 改成 `max HP and Genetic Algorithm plays are priced`。B145 的修法本身（钉开关）仍然有效，是 S112 现在能跑的前提。

**代价**：无（只跑在门禁里）。**风险**：若以后有人把 `counting` 的默认值改成关，S112 仍会绿（它显式钉 true），那是产品行为改变、该由 S201/面板复测覆盖，不属于本门禁的判据。**对 mod 的影响**：无——不改产品代码，开关语义、评分、面板都不变；只是「玩家关掉开关后跑自检」不再出现 4 条假红。

**边界**：`s113Scan()`（Probe.java:12725-12729）也调 `credit()` 算 `otherCredit`，但该值只打印、没有被断言（S113 只断言扫描结果），开关关闭时它会显示 0，属报告信息不准，未改。

## B146 金币改为直接计分：1 金 = 80 分，且跟随「局外收益」开关（user m11490 / m11767）

**用户要求（m11490 原话）**：「1.把金币单独拿出来算分，1金币=80分。2.花了金币相当于金币减少算负分，-1金币=-80分」。

**追加要求（m11767 原话）**：「1.没有做战斗以外的跑图功能所以删牌商店之类的没有影响。2.金币要计入局外收益，关掉局外收益金币不能算分」。⇒ 第一版把金币做成「常开项」，与这条冲突，已改为受 `GrowthBook.counting()` 控制（工作记录里保留第一版的经过，结论以本节为准）。

**改前口径（B146 之前）**：金币不在 `Solver.score` 里，而是经 `GrowthBook.credit()` 折成 HP 再从战损里抵扣——`tenths += (goldGained / 25) * t[5]`，`GOLD_UNIT=25`、`t[5]=10` ⇒ 25 金 = 1 HP = 2000 分（80 分/金）。三个副作用：①只算**增加**、不算**花费**（买药/买牌不掉分）②按整 25 取整（24 金 = 0 分）③受面板「局外收益: 计/不计」开关控制，关掉后金币一分不算。

**改后口径（最终）**：金币是 `score` 的直接项 `goldDelta * Solver.goldRate()`，`goldRate()`（`Solver.java:2192`）= `GrowthBook.counting() ? GOLD_POINTS : 0`，`GOLD_POINTS = 80`；`goldDelta` = 本节点金币 − **开战时金币**（`startGold`，在 `solveInternal`/`Session` 各读一次）。面板显示「局外: 计」时得 1 金 +80、花 1 金 −80；显示「局外: 不计」时金币项恒 0（一分不算、也不扣分）。同一开关本来就管最大生命/升级/仪式匕首等成长收益，金币现在与它们同一口径（user m11767）。80 分/金与旧汇率 25 金 = 2000 分**完全等价**；相比旧口径仍修掉了「只算增加、按整 25 取整」两个毛病（开关打开时生效）。

**为了避免重复计价，同时退役了两条旧通道**：
- `GrowthBook`：KEYS 去掉 `GOLD`，`DEFAULT_TENTHS` 由 7 项变 6 项 `{30,20,5,50,10,10}`，credit 里的金币行删除，`GOLD_UNIT` 删除，`GOLD` 常量保留但注明作废；`describe()` 少一行。
- `GrowthCredit`（默认关闭的「成长信用」策略）：`GROWTH_CARDS` 去掉 `HandOfGreed`——它的永久收益就是金币，金币已被直接计价，再按「每次成长牌 +N HP」付一次就是同一笔钱付两遍。
- `EscapePolicy.forfeitPointsFor`：逃离战斗的「丢掉的金币」原按 `gold * POINTS_PER_HP / GOLD_PER_HP` 算（= gold*80），改成直接 `gold * Solver.goldRate()`，两处汇率从此只有一个来源，且同样跟随开关（关掉时逃离开销只剩卡牌那半边）。`GOLD_PER_HP = 25` 保留（表达「一场架值多少金」）。

**实现点（文件:行）**：
- `search/Solver.java`：`GOLD_POINTS`（:655）；`goldRate()`（:2192，`GrowthBook.counting() ? GOLD_POINTS : 0`）；`score(...)` 9 参版委托 10 参版（末位 `goldDelta`，:2142/:2151），末尾 `+ goldDelta * goldRate()`；`scoreOf` 加 10 参重载（:2205）；主循环调用传 `choice2.gold - startGold`（:1577）；切片 `Session` 传 `choice.gold - this.startGold`（:3710）；贪婪 rollout 传 `gold1 - gold0`（:294-307）；`startGold` 在 :1494 与 :3405 从 `Solver.playerGold()` 读。
- `search/GrowthBook.java`：KEYS/`DEFAULT_TENTHS`/credit/describe 如上。
- `search/GrowthScan.java`：`keyOf("HandOfGreed")` 文案改为「金币由求解器直接计价（1 金 = 80 分）；growth.handOfGreed 已作废」。
- `search/GrowthCredit.java`：`GROWTH_CARDS` 去 HandOfGreed（带 B146 注释）。

**验证**：
- 新门禁 **S202**（4 条）：开关钉在 `TRUE` 时 `scoreOf(...,goldDelta=5) - base == 400`、`base - scoreOf(...,goldDelta=-3) == 240`、`GrowthBook.credit` 对「+50 金」返回 0（同一枚币不会付两次）；再钉 `FALSE` 断言「+5 金与 −3 金都不改变分数」（`offGained == offBase && offSpent == offBase`）。
- `S187` 从 5 条加到 6 条：前 5 条在开关钉 `TRUE` 下报 `normal=5200 elite=6400 boss=28000 bossAsc13=26000`（与改前一致，说明汇率等价），新第 6 条钉 `FALSE` 后 `forfeitPointsFor(false,false,0,15) == 2*2000`（金币半边为 0）。
- 改 `S112`：预算数组 6 项、期望 `credit=21`（150+60 tenths；旧 23 含 50 金 = 2 HP）、新增 `goldOnly=0` 断言与「gold is no longer credited here」。
- `-Gates "S15,S53,S112,S187,S200,S201,S202"` **46/0**（S202 report `gained=400 spent=-240 growthGold=0 offGained=0 offSpent=0 was=false`；S187 report 同上）。
- 全量 core：**538/0**（S1–S202，core-only 跑法，104 条 extended 跳过；比第一版多 2 条＝S202 与 S187 各新增 1 条）。
- jar sha256 `C6189FE2E8E7F7D5B92B632E1C950B83C81D51BC2475044DDD6D39B6C3880F25`（1,081,456 B）。

**代价**：每次 `score` 现在要读一次 `GrowthBook.counting()`（`synchronized` 方法 + 已缓存的预算，单线程无争用，纳秒级）；搜索本来就每节点调 `GrowthBook.credit()`，没有引入新的锁竞争或文件读；与金币项本身的一次乘法/加法一起，相对树搜索可忽略。`goldDelta` 在调用点算好，没有新字段。`Node.gold` 早就有（B135 前用于成长记账），没有新增字段或内存。代码上少了一条记账通道（GrowthBook 少 1 键、GrowthCredit 少 1 卡），但 `Solver` 的 `score` 多了一个重载（9 参版保留是为了不动几十处老调用）。

**风险**：①**取整语义变了（开关打开时）**：以前 24 金 = 0 分，现在 24 金 = 1920 分；小额度金币（跳蚤市场、商店抹零）现在会影响排序，属于用户要的「花也要算」。②**花费变负分（开关打开时）**：买药/删牌现在会掉分，可能出现「能赢但省着不买」的线；因为 1 HP = 2000 分 = 25 金，买一瓶 75 金的药相当于 3 HP，和以前的成长预算口径同量级，没有把胜负判定压过去的风险（胜负差 10000000）。③**胜利线额外项**：`GrowthCredit.valueOf(path)`（默认 0）与 `healPoints` 仍在，与金币项并列不冲突；若玩家手动把成长信用打开，Feed/Genetic Algorithm 等非金币卡仍各付一次，HandOfGreed 已排除。④**扩展胜线**（`Solver.java:1697/:1796` 一带的子解汇总）仍传 `goldDelta=0`——子解没有金币度量，这是已知边界，不是回归：它让「先赢再打」的合成线金币项为 0，而不是错值。⑤**回滚**：把 `goldRate()` 改回常量 `GOLD_POINTS` 即退回「常开」（第一版行为），把 `GOLD_POINTS` 置 0 则金币彻底不计分；真要整体退回 B145 口径（金币重新经 GrowthBook 折 HP）需还原 5 个文件的这一节，单文件回滚不成立，变更清单已记在 WORKLOG。⑥**门禁依赖玩家设置的坑**：`goldRate()` 读的是玩家 `settings.txt`（`growth.count`），所以 S202/S187 必须在门禁里钉住开关（已在 `try/finally` 里 `overrideCountingForTest`），否则玩家关掉局外收益后这两条必红——与 B144/B145 同一类问题。

**对 mod 的影响**：
- 面板：**不变**（金币仍不上面板；面板显示的 HP/路线是按分数选出来的结果，开关打开时金币参与排序，所以「买不买/打不打点播」这类选择可能和以前不同；开关关掉时与 B145 之前关掉开关的表现一致）。
- 自动出牌：**开关打开时更愿意花钱换收益**（例如 50 金的点播：金币 +50 ⇒ +4000 分，即使毒种代价不可见也会打）；**开关关掉时金币一分不算**，计划里不该再为了金币多打一张纯赚金币的牌（同分时仍按先找到的线走，这是 B146 之前的遗留：没有步数/资源 tie-break，未改）。面板按钮就是那个开关，没有额外配置。
- 搜索：分支评分多一项；束排序 `rankOf` 不变（仍用 `energy*40`，不看金币），所以金币只影响最终择优、不影响保留哪些分支。预算不变。
- 其它 mod：`Gold` 的增减全部经 `AbstractDungeon.player.gainGold/loseGold` 或直接改字段，两条都被 `playerGold()` 读到（读的是 `player.gold` 字段）；没有按 mod 类名/卡名特判。边界：沙盒里**未来**会发生的金币变化（如遗物在战斗结束时给钱、卡牌 onVictory 给钱）在本场战斗的节点里看不到——与「局外收益」开关关闭时的旧边界相同。



## B147 让「实机与模拟在哪一拍分叉」变成可读的事实 + 已证分叉时重算一次（user m12327「动手」，承接 m12123/m10519）

**用户要求（m12123）**：「最新日志打三奴隶贩子，第五回合头追的选牌面板给的是打击防御但是实际选不到。1.查找哪一步沙盒和实际偏差并找原因。2.如果我有错误就指出来。」⇒ 那一轮的结论是：分叉在「结束回合」这一步（计划第 20 步，turn 3→5），沙盒里唐打出的是防御+、实机打出的是农盘与嗦开开；两边 hp/能量相同而手牌/抽牌堆/格挡全不同，计划第 1–19 步 digest 字节级相同。**当时的困难不是结论，而是证据**：计划的 digest 不含随机计数，出牌靠手牌前后 diff，于是「唐到底打了哪张、在哪个随机位置、谁先谁后」谁都说不出来。

**用户要求（m12327）**：「动手」⇒ 本轮把这条证据链做成引擎事实，并且已证分叉时不再硬走错计划。

**改前的三处盲区**：
1. 手牌 diff 看不出顺序、看不出同一张同名牌（两张防御）打的是哪一张、看不出同一张牌被打两次（奶龙会让同一张牌再打一次）。
2. `Digest`（`mirror/Digest.java:34-85`）只含 t/ph/q/P hp/b/e/st/pw/rl/or/H/D/X/E/M，**不含任何随机计数**；而 `RngStamp` 记的 13 条流正是「同样一块板子，下一步会怎么走」的全部依据。
3. `PlanCache.key`（`search/PlanCache.java:54`）只含 digest/ascension/policy/content ⇒ 计划 08:26 算出、08:41 才执行（中间存档重载过）时被直接复用，随机流漂移完全不可见。

**改后（三件引擎事实 + 一条处置规则）**：
- `mirror/PlayTrace`（新文件）：`@SpirePatch(clz=AbstractPlayer.class, method="useCard", paramtypez={AbstractCard,AbstractMonster,int})` 后缀记录，环形缓冲 512 条，格式 `牌id@t回合#随机位置/费用[auto]`；**实机与沙盒走同一个 patch**，所以计划里存的与实机记下的是同一类证据。线程标记过滤，搜索换线程也不会混。
- `mirror/RngStamp`（新文件）：13 个 `AbstractDungeon.*Rng.counter`（`cardRandom/shuffle/ai/misc/monster/monsterHp/relic/potion/card/event/merchant/treasure/map`），`of()` 全长、`compact()` 五条短键（随每次出牌记录）、`same()` 判等（空＝未知）。
- `Solver.Step` 新增 `rng` 与 `plays`：`Solver.replay()` 在 `Legal.applyOrRefuse(action)` 前 `PlayTrace.mark()`、动作后取 `RngStamp.of()` 与 `PlayTrace.describe(mark)` ⇒ **计划自带沙盒那一侧的证据**。9 参构造保留（委托 11 参版，`resultForTest` 等老调用不变）。
- `PlanCache.key` 新增 5 参重载：键尾追加 `|rng:<stamp>`；4 参版委托 `rng=""`（20 处老调用不变）。`Gameplay` 三处取键（搜索入口、重算入口、切片）都传实时 `RngStamp.of()`，取/放同一处 `quickCacheKey` 复用该键。
- `Gameplay.noteDriftAndContinue(step, actual, livePlays, liveRng)`：失配时打 `plays sandbox/plays live/rng sandbox/rng live` 四行；**仅当两侧出牌序列不同**才 `replanAfterDivergence`。判据理由写在代码注释里：空证据（手搓 Step、老计划、测试钩子）算未知不算差异；随机计数单独不同**不**触发重算，因为计划里的计数会随整条重放一起推进，单独不同不能证明这一步走偏，而 sides 都一致的计数差异只说明「更早的地方就不是同一个世界」。
- `replanAfterDivergence`：每场战斗最多重算 1 次（`TRACE_REPLAN_LIMIT`），面板写「实机与模拟分叉（自动打出的牌不同）：已按实机局面重算」；第二次分叉直接 `fail` 停止自动出牌并说明原因。战斗开始（`combat detected` 那一拍）清零计数。
- 结束回合即使**正确**也打一条 `end turn plays: live … || sandbox … || rng same/DIFFERS` —— 下一次「面板和实机不一样」的报告不需要复现，日志里就有两侧的出牌序列。

**与 m16202-2 的关系（重要）**：m16202-2 的规则是「计算完就冻结，不许自己重算」，那一条针对的是**计划走完**的场景（玩家不想看到凭空又算一次）。B147 的重算针对**已证伪的模拟分叉**：计划建立在实机从未有过的状态上（出牌序列都不同），继续硬走只会把错推到更后面。两者边界写进了 `replanAfterDivergence` 的注释，且 B147 的重算是**有上限、有日志、上面板**的，不是静默。

**门禁**：
- **S203**（3 条）：合成战斗打一张牌后轨迹正好一条、轨迹文本含 `@t`/`c=`（牌/回合/随机位置）、`cardRandomRng.random(100)` 后 `RngStamp.of()` 变化。
- **S204**（3 条）：一行 `Action` 经 `Solver.replay` 回放成 1 步、该步 `rng` 含 `cardRandom=`、该步 `plays` 含实际打出的那张牌 id。
- 跑法：`-Gates "S127,S135,S136,S196,S197,S198,S199,S200,S201,S202,S203,S204"` **47/0**；core **544/0**（S1–S204）。
- jar sha256 `D2AE4CDED40BDF66330C98493E7D99E2BF0C7A7F18B4F8A743D6B9A9CD3CCA08`（1,092,013 B）。

**代价**：
- 性能/内存：每次 `AbstractPlayer.useCard` 多一次写入（`cardID` 拼一次字符串 + 13 个 int 读成 5 键短串 + 5 次 volatile 写），单次 <2 µs；轨迹是固定 512 条环形缓冲，预热后零分配、无常驻增长。沙盒里每节点每张牌多这几微秒，相对一次 `applyOrRefuse` 的动作泵（毫秒级）可忽略。`RngStamp.of()` 只在「每步完成」「每次取计划缓存键」「每次重放一步之后」调用，不在节点循环里。
- 代码：新增 2 个文件；`Solver.Step` 多 2 字段（旧 9 参构造保留）；`PlanCache` 多 1 个重载；`Gameplay` 多 1 个方法 + 4 个字段。
- 搜索：缓存键变长（多约 200 字符的随机戳），命中率**下降**是设计目的——随机位置不同就不该复用。

**风险**：
1. **误判/误停**：`plays` 是整段字符串比较，任何格式差异（未来给轨迹加字段）都会让两侧不等 ⇒ 重算一次、第二次停。已用「空证据＝未知」和「只有读得出两侧才比较」收敛，但真机上仍需一轮实测。回滚：把 `TRACE_REPLAN_LIMIT` 置 0 即退回「只记日志不停」。
2. **缓存命中率下降**：随机计数会随实机自己的动作推进（唐、遗物、抽牌洗牌），同一局面重算时几乎必然 miss ⇒ 同一节点重复计算变多。这是用一点算力换「不再复用错计划」；如果实测发现重复计算明显变慢，可只把 `cardRandom/shuffle/ai/misc` 四条进键（改 `RngStamp.compact()` 即可，键格式不用动）。
3. **轨迹边界**：只记 `AbstractPlayer.useCard`。不进这个门的效果（药水、遗物直接改血、能力 tick）不在轨迹里，两侧相同不代表发生的事相同 —— 这就是为什么仍然保留 digest 比对，轨迹只用来解释 digest 差异。mod 自建随机流（不走 13 个官方流）的位置差异看不见。
4. **回滚方式**：单文件可退——`PlayTrace`/`RngStamp` 删掉、`Step` 两字段保留（空串即退化成旧行为）、`PlanCache` 5 参重载传 `""` 即退回旧键、`replanAfterDivergence` 改为只打日志。

**对 mod 的实际影响**：
- 面板：**不变**（不新增行、不改布局）；唯一变化是分叉时状态行会写「实机与模拟分叉（…）：已按实机局面重算」，以及一次重算期间路线条会刷新为按实机算出的新线。
- 自动出牌：①出牌序列与计划不同的那一步之后，自动出牌会**按实机重算一次并继续**（以前会硬走错计划直到某张牌不在手上才停）；②同一场战斗第二次分叉 ⇒ 停手并说明原因；③结束回合即使正确也会在日志留一条两侧对照，便于用户回报。
- 搜索：缓存键含随机位置 ⇒ 同局面重复计算略增；评分与预算不变。
- 其它 mod：不认任何 mod 类名/卡名，全部走引擎的 `useCard` 与官方随机流；mod 自建随机流不可见（已记边界）。

## B148 读档重置随机流 ⇒ 旧计划被复用（users m12610）

**现象**：三奴隶贩子第 5 回合那步，面板给的选牌/血量/格挡与实机不一致（沙盒唐打防御+，实机唐打农盘与嗦开开），第 20 步之后全盘错位。前 19 步 digest 完全一致，所以看起来像「突然随机了」。

**根因**：读档会重置五个随机流。`AbstractDungeon.loadSeeds(SaveFile)`（`AbstractDungeon.java:394-421`）只恢复 `monster/event/merchant/card/treasure/relic/potion` 的 `*_seed_count`；`aiRng/shuffleRng/cardRandomRng/miscRng/monsterHpRng` 不存档。`nextRoomTransition(SaveFile)`（`:1667`，播种 `:1727-1731`）每次读档进房都无条件重新 `new Random(Settings.seed + floorNum)` ⇒ counter 归零。于是「可见状态一模一样、随机位置不同」：digest 相同（digest 不含随机计数）⇒ `PlanCache` 命中 ⇒ 复用读档前算的计划。计划里第一个消耗 `cardRandomRng` 的地方（唐在回合开始随机打一张）就与实机分叉。

**修法**（=「一开始就算对」，user m12610）：`PlanCache.key` 收 `RngStamp.of()`（B147 的 5 参重载，`Gameplay.java:536/:2035/:2164` 三处取键），读档后戳不同 ⇒ 未命中 ⇒ 用实机真实随机位置重新求解。同时**删除**上一轮被否决的「检测到分叉就重算」：`Gameplay.replanAfterDivergence`、`traceReplans`、`TRACE_REPLAN_LIMIT`、`noteDriftAndContinue` 的重算块全部移除，只留一行 WARN 指出「第一处不同的出牌就是该研究的那一步」。

**门禁**：S205（5 条）。子集 24/0；core 549/0（S1–S205）。jar `7FA7707B6FF310643E04CD626D9CE6E1BCE1165083CB8454E2E332621176B7A5`（1,094,294 B）。

**代价**：①性能——计划缓存命中率下降（随机计数会随实机自身动作推进，同一局面几乎必然 miss ⇒ 读档后必定重算一次，预算内）；键长多约 200 字符，可忽略。②代码——净减（删掉约 40 行重算逻辑），新增仅 `Probe` 里一个门禁。

**风险**：①读档后若玩家在意「又要算 100 秒」，这是真代价（以前是秒回旧计划，但那个计划是错的）。②`RngStamp` 只含 13 个官方流，mod 自建随机流仍不可见——修不了这类；③若某次读档后五个流恰好都回到同一位置（counter 相同），缓存仍会命中，这是正确行为。④回滚：`Gameplay` 三处取键去掉第 5 参即退回旧键行为；`replanAfterDivergence` 已删不保留（用户明确要求不留残余）。

**对 mod 的实际影响**：面板不变；自动出牌/搜索在**读档之后**会按实机随机位置重算一次（表现为「读档后要等一下才出路线」），之后与实机同流；识别规则不认任何 mod 卡名/类名，只认官方随机流与 `useCard` 轨迹，其它 mod 兼容性不变。


**撤销（B150，user m13154 第 2 条）**：本节全部代码已删除——`Gameplay` 三处 `RngStamp.of()` 取键恢复为 4 参 `PlanCache.key`、`PlanCache` 的 5 参重载删除、门禁 S205 整段删除；保留的只有 B147/B149 的取证设施（`PlayTrace`/`RngStamp`/`Step.rng/plays`/日志）。

**它为什么没达成「读档就重算」**：本节方案只在**随机计数不同**时换键，从不主动重算。而读档重新进房的播种是确定的（`cardRandomRng = new Random(Settings.seed + floorNum)`，counter 0），旧计划若也是在计数 0 时算出的，键就相同 ⇒ `plan cache: hit` 照旧 ⇒ 用户实测「不退游戏读档还是不重算」。即「读档必然换随机位置」这个前提不成立，方案从根上识别不了「这是新读的一次档」。

**撤销的代价/风险**：无新增运行时开销（净删代码）；风险是「读档后复用旧计划」这一现象重新变回不可识别（它就是本节没解决的那个问题）；回滚＝按本节原修法重新加回 5 参键与 S205 即可，但需先解决上述前提问题。对 mod 的影响：面板/自动出牌/搜索行为回到 B147 取证版，金币、成长、关屏等其它逻辑不变。
## B149 — 唐（TangPower）自动出牌开出选牌屏、面板没有该步答案：先把「谁开的屏」变成可读事实

**状态**：取证面已补，根因**未定**（等下一份日志）。

**现象**（user m12829 原话）：「第三回合结束后第四回合“唐”自动出牌出了“豹指导”要选择牌，但是面板第四回合没有选牌」。该局 `logs/solver-20261106-180259.log`：计划 35 步（18:10:32 算出，nodes=84141）；第 1–12 步 digest 全对；18:11:04 结束回合（t=2→3）那步两侧出牌与随机计数**完全一致**（`end turn plays: live CaiNongMod:JoeyWheelerTheSpire@t3#c=10,s=1,a=9,m=0,h=4/1e[auto] | …@t3#c=18… || sandbox 同样两条 || rng same`）。之后 24 秒无日志，18:11:28（HAND 9 张）/18:11:43（HAND 8 张）/18:12:03（GRID 7 张含 SealGuide、Wound×2、PaiDianDian）连开三块屏，面板都报 `selection open with no planned answer … autopilot=false`，计划里这三个答案一个都没有；18:12:09 `game paused`，18:12:18 玩家重开。

**卡与链（反编译事实）**：豹指导＝`CaiNongMod:SealGuide`，`use()` 里 `AbstractDungeon.handCardSelectScreen.open("打出", amount, anyNumber, canPickZero)`（`_tmp/sealguide/src/CaiNongMod/cards/SealGuide.java:90`）⇒ 被自动打出时必然开屏。唐＝`CaiNongMod.Powers.TangPower`：`atStartOfTurnPostDraw()` 记 `numOfUseCard=amount-1` 并排 `PlayRandomCardAction`；`onAfterUseCard(card, action)` → `playCards(card)` 在同一 `turnStart && numOfUseCard>=0` 下再排一张（奶龙 `NaiLong` 还会 `makeSameInstanceOf` 再打一次）。`PlayRandomCardAction.update()` 用 `AbstractDungeon.cardRandomRng.random(group.size()-1)` 选牌 ⇒ 沙盒与实机只要随机位置或「谁能打」的判定不同，唐就会挑到不同的牌；挑到豹指导的那一侧会开屏，另一侧不会。

**为什么以前查不动（真正的缺口）**：`Gameplay` 里唯一的出牌对比是 `end turn plays: live … || sandbox …`，而且条件是 `step.action.isEndTurn() && !step.plays.isEmpty()`（`Gameplay.java:3325-3333`）——**普通步里任何自动出牌（唐、奶龙、遗物）都不留痕**；等到屏开出来时 `pending == null`，日志只有候选牌表，没有「这块屏之前实机打了哪张」。

**本轮改动**：①`mirror/PlayTrace.java` 新增 `public static String recent(int n)`（当前线程最近 n 笔出牌，oldest first，从环形缓冲回溯，格式与 `describe` 相同）；②`Gameplay.answerLatentSelection()` 的 `selection open with no planned answer` 行追加 `| step=<当前步>/<计划步数> | last plays: <recent(8)>`；③在步校验处新增普通步分支：`else if (!fightEnded && step.action != null && (!livePlays.isEmpty() || !step.plays.isEmpty()))` → `Log.info("step plays: live … || sandbox … || rng same/DIFFERS planned … live …")`；④（user m12829 #3）`Gameplay.java:1778` 的提示从「再点「计算」就采用当前最优线；再点一次算下一轮」改成「再点「计算」就采用当前最优线」——再点一次并不会重算。

**门禁**：未新增（纯取证日志 + 一句文案）。core 套件 `passed=549 failed=0`（S1–S205）。jar `F96AE9D59D0720187F865D765473F1F34942D78A48CE6FFE756FD1A718DF4B31`（1,094,794 B，466 entries）。

**代价**：①性能——`recent(8)` 只在「屏开了但没答案」那一刻调用（一次环形回溯 ≤512 项，微秒级）；普通步的出牌对比多一次 `PlayTrace.describe`（原本结束回合也在做），日志量增加约「有自动出牌的步数」×1 行。②内存——`recent` 只分配一个 ≤8 元素的 `ArrayList`，无新增长期状态。③代码——`PlayTrace` +1 方法（约 30 行），`Gameplay` 两处日志改动。

**风险**：①日志变长（一局若唐每回合都打牌，多十几行），不影响任何判定；②`recent(n)` 与其他 API 一样是**按线程**取的，若将来把搜索搬到 worker 线程，这里看到的仍只会是当前线程的流水（与 `describe` 同一口径，不会串台）；③这两行只是事实，不会改行为——**唐那条分叉本身仍未修**，不能当成「已修好」。④回滚：`Gameplay` 两处日志与 `PlayTrace.recent` 可独立撤销，撤销后行为完全回到 B148。

**对 mod 的实际影响**：面板：唯一变化是计算中那行提示不再说「再点一次算下一轮」；自动出牌与搜索行为**零变化**（本轮没有动任何判定）；其它 mod：不新增任何按类名/卡名的识别，日志只打印牌 id。

**下一步（判定表）**：复现后看 `last plays`——(a) 若屏前那一笔是 `CaiNongMod:SealGuide@tN…[auto]` 而对应的 `step plays` 里沙盒侧没有它 ⇒ 唐链在沙盒里没跑（`playCards`/`numOfUseCard`/`canUseCard` 或 `onAfterUseCard` 时机）；(b) 若沙盒侧也有这笔、计划里却没有该屏的答案 ⇒ 屏被 `Driver.drain`/`runEndOfTurnQueue` 越过或 `Solver.expandScreens` 丢弃了该分支（B140 的 `screen-mismatch`/`chain-unfinished` 路径）；(c) 若两侧都没这笔而实机开了屏 ⇒ 开屏者不是唐，按 `last plays` 的最后一项继续追。

## B151 — 结束回合队列泵不看屏：唐的豹指导开屏后一张牌都没打

**状态**：已修（代码、门禁、修前必红都实测）。

**用户要求**（m13424/m13425）：「你的意思是在结束回合后，开启选牌屏的时候沙盒还在推进动作所以导致顺序出问题对吗？如果是这样照你的做，严格让沙盒出牌和实际出牌一样」。

**现象**：三奴隶贩子第 5 回合那步（日志 logs/solver-20261106-190048.log）计划第 20 步「结束回合」（t=3→5）两侧 digest 出现差异：expected b=16、actual b=2；实机多 NongAbacusPower2；出牌序列 sandbox …SealGuide+ → BideTime → Statuette → Defend+×2 vs live …SealGuide+ → Statuette → BideTime → NongAbacus，两侧 13 条 RNG 计数完全相同。

**根因（引擎事实，不是随机性）**：`AbstractRoom.update()` 里 `if (!AbstractDungeon.isScreenUp) { AbstractDungeon.actionManager.update(); … }`（`_tmp/saveload/com/megacrit/cardcrawl/rooms/AbstractRoom.java:250-259`）⇒ 任何屏一开，动作队列冻结。而 `Driver.runEndOfTurnQueue`（B114 为解决「结束回合漏跑回合末结算」引进的泵，`Driver.java:676`）循环里只有 `pumpRoomObjects()` 与 `gam.update()`，**没有任何屏判据**。于是：唐把豹指导的 `PlayCardAction` 押进队列 → 它 `handCardSelectScreen.open(「打出」, …)` → 泵继续 `gam.update()` → 它的下一次 update 看到 `wereCardsRetrieved == false` 且 `selectedCards.group` 空 ⇒ 循环一张都没打（`_tmp/sealguide/src/CaiNongMod/actions/PlayCardAction.java:100-108`），随后置 `wereCardsRetrieved=true`。免费牌消失、屏被后续清屏静默吃掉，沙盒从这一步起与实机分叉。

**同一缺陷的第二处**：`Driver.roomBlockedByScreen()`（`Driver.java:2649`）把 HAND_SELECT 判成「不挡管理器」，理由是 `AbstractDungeon.update()` 的 HAND_SELECT 分支会调 `room.update()`——但 `room.update()` 内部仍然要求 `!isScreenUp` 才推进管理器，旧注释读错了层级。

**改法**：①`runEndOfTurnQueue` 循环首 `if (managerFrozen()) return true;`；②`managerFrozen()` 改成引擎自己的门（`isScreenUp` → true；NONE/NO_INTERACT/FTUE/BOSS_REWARD/HAND_SELECT → false；GRID/CARD_REWARD → `!peekingNow()`；其余 → true）；③`drain` 的 `pressNow` 分支：泵被屏停下就 `awaitingSelection=true; break`，保留结束回合 latch，由调用方答屏后同一个 drain 跑完结束回合；④`Driver.endTurn(long)` 在屏开着时拒绝（实机 `EndTurnButton.update` 只在 `!isScreenUp` 时 disable）；⑤`clearSelection` 与 `Synthetic.java:456` 的 `previousScreen = NONE` 改 `null`（`AbstractDungeon.closeCurrentScreen()` 只在 `previousScreen == null` 时置 `isScreenUp=false`，`AbstractDungeon.java:2423`；旧写法关屏后屏仍算开着，管理器永久冻结）；⑥`resolveSelection` 改真实点击顺序「填 → 关屏 → drain」。

**门禁**：新增 S206（5 条：泵遇屏停下 / 屏在时后续不动 / 答完屏那张牌真被打出（怪物掉血）/ 结束回合随后完成）；S182 改两段（屏开着时结束回合被拒 + 答完屏同一结束回合走通）。修前必红实测：临时去掉第①条早退，S206 `passed=2 failed=3`；恢复后 5/5。子集 46/0；全量 core **550/0**（S1–S206）。

**代价**：①性能——每次结束回合多一次 `AbstractDungeon.isScreenUp` 字段读（纳秒级）；真开屏时提前返回，比原来少跑若干 `gam.update()`。②内存——无新增容器。③代码——`Driver` 净增约 60 行（`managerFrozen` 重写 + 两处早退 + 拒绝分支）、`Probe` 新增 5 条断言，并删掉 `pumpWhileScreenOpen`/`pumpForTest`/`screenSerial` 等死代码。

**风险**：①`managerFrozen()` 比旧的屏种类判断更严：任何屏开着都冻结管理器；若某个 mod 屏依赖「开着屏也推进队列」会被改变（引擎本身也不这么做，风险低）；②`endTurn` 在屏开着时返回 `refused=true`，任何依赖「结束回合总能生效」的调用方须先答屏（Legal/Solver 已按 `awaitingSelection` 处理）；③结束回合被屏打断后靠 `player.endTurnQueued` 续上，若某条路径把它清掉，这次结束回合会停在半途（S206 已断言跑完）；④回滚＝还原 `runEndOfTurnQueue`/`drain`/`endTurn(long)`/`resolveSelection` 与 `Synthetic.java:456`，彼此独立。

**对 mod 的实际影响**：面板：带「选一张牌再打出」的自动牌（豹指导、头追）那一步现在会**多一段选牌答案**，预计血量/格挡与实机一致；自动出牌：屏会先等答案再继续，不再吞掉免费牌；搜索：结束回合这一步可能多开一条选牌分支（分支变宽，靠 `limits.combos`/`limits.screens` 兜底）；其它 mod：不按类名/卡名特判，只用引擎自己的「屏开着不推进队列」规则。


## B152 — 门禁钉住的「局外收益」开关被缓存重读冲掉

**状态**：已修。

**现象**：装 B151 的 jar 后全量 core 出 1 条红：`FAIL the live room is billed the normal-fight amount | live=4000 expected=5200`（S187）。5200 = 卡牌奖励 2 HP×2000 + 金币 15×80；4000 正好等于紧随其后的「局外: 不计」断言值 ⇒ 那一刻 `GrowthBook.counting()` 是 false。

**根因**：`GrowthBook.overrideCountingForTest(TRUE)`（`GrowthBook.java:242`）只赋值 `counting` 字段；而 `counting()` 先调 `budgets()`，`budgets()` 在 500 ms 缓存过期后会重读 `settings.txt` 并把 `counting` 按文件内容覆盖（玩家 `settings.txt` 里 `growth.count=0`）。S187 先钉开关、再 `Synthetic.build(...)`（超过 500 ms），读「实况房间」时开关已被冲回 false。与 B145 同类，但深一层：B145 是「门禁没钉开关」，B152 是「钉了但被缓存重读冲掉」。

**改法**：`GrowthBook` 新增 `countingPinned`；`overrideCountingForTest` 同时置 `countingPinned` 与 `counting`；`budgets()` 只在 `countingPinned == null` 时才从文件写 `counting`；`invalidate()` 清 pin。

**门禁**：无新增（S187 就是它）；全量 core **550/0**（S1–S206）。

**代价**：一个 `Boolean` 字段、一次 null 判断；无性能影响。**风险**：pin 只影响测试路径（生产不会调用 `overrideCountingForTest`）；`invalidate()` 清 pin 后回到读文件，与以前一致。**对 mod 的影响**：生产行为零变化，只是让门禁不再读玩家的设置。

## B153 — 答完选牌屏只跑到半程：面板的回合号与血量停在半路

**状态**：已修（代码、门禁、修前必红都实测）。

**用户要求**（m14038）：「新bug，面板实际不一致。找原因」；（m14215）「继续」＝照上一轮定位的修法实施。

**现象**（C:/Users/YOKI/sts1-engine-solver/solver.log，38 步计划，2026-11-06 20:09:16 打印）：计划第 13 步「结束回合（选：防御 → 农盘）」存成 `t=3→3 hp=32 b=20`，而同一时刻实机把这次结束回合走完到 `t=6 hp=51`；此后面板整条线偏低/错位（面板 32/43 vs 实际 51），并触发 `plan stale: step 14 belongs to turn 3, live turn 6` 把计划丢掉。第 1–12 步两侧 `step plays` 逐张相同、rng same。

**关键旁证**：同状态跑 `Gameplay.predictEndTurnRisk()`（`Gameplay.java:2308-2338`）得到 51——预测路径与计划路径对**同一个结束回合**给了两个结果，说明不是游戏不同，是计划那一侧没跑完。

**根因（代码级）**：`Driver.resolveSelection` 的 HAND / GRID(空) / GRID(非空) / REWARD 四个出口答完屏都只调裸 `drain()`（`Driver.java:297-299`：`drain(600, 250L)`，600 步 / 250 ms）**并且丢弃返回的 `Drain`**。这次屏是「结束回合里排队的动作」开的（唐 TangPower → 豹指导 PlayCardAction 开「打出」屏），答完屏之后队列里还剩**整段怪物回合 + 下一回合开始**，远超 250 ms/600 步 ⇒ 被静默截断在半程；`step.digest` / `turnAfter` 就按半程状态存下（`expandCore` 在 `Driver.settleForReplay(12)` 之后直接 `w.readBack(); readChoice(choice)`，不会发现状态没 settle）。

**改法**：①`Driver` 新增 `private static volatile boolean answerLeftUnfinished;`、`public static boolean lastAnswerUnfinished()`、`private static void drainAfterAnswer()`——先 `drain()`；已 settled/awaitingSelection/refused 直接返回；否则走 B102 的 `completeIfTruncated(d)`（`COMPLETION_STEPS=4000` / `COMPLETION_MILLIS=3600000L`）；仍不 settle 则置 `answerLeftUnfinished=true` 并 `Log.warnEvery("answer-unfinished", 5000L, …)`。四个出口（`Driver.java:2519/2530/2541/2549`）全部改调它。②`Solver.expandScreens` 每次 `resolveSelection` 后、`Solver.expandCore` 分支循环里 `resolveSelectionByNames` 后，见 `Driver.lastAnswerUnfinished()` 就丢弃该分支（沿用 B140「半程状态不算一步终态」），**不存半成品**。

**门禁**：新增 S207「an answer finishes the queue it resumes (B153)」（5 条）——`LongAnswerProbeAction` 开一块 hand 选牌屏，答完后按 1200 帧继续占用队列（每帧 `currentBlock += 1`，让 `Digest.progressHash` 看得见；只增计数会被 drain 的 48 帧 stall 检测判成卡死），断言：屏停在自己开的选牌上 / 有牌可答 / 答案被接受 / 队列被跑到尾（`tail 0->1200/1200`）/ 状态已 settle（`unfinished=false`）。**修前必红**实测：把 `drainAfterAnswer()` 首行插 `if (true) { drain(); return; }` 重建（jar sha 013DFAC4…AFB7），S207 红 1 条 `tail 0->600/1200`；还原后 5/5。子集 `S206,S207,S196,S198,S199,S200` 29/0；全量 core **555/0**（S1–S207）。

**代价**：①性能——普通步 0（`drainAfterAnswer` 里第一次 `drain()` 不变）；只有「答完的屏后面还挂着队列」时才多跑一次完成泵，且上限有界（4000 步/3600 s），实际是这次结束回合本来就要跑的那些帧。②内存——一个 `volatile boolean` 字段，无新容器。③代码——`Driver` 约 +35 行、`Solver` 两处判断、新增 1 个探针动作类、1 个门禁（5 条）。

**风险**：①完成泵会把这次结束回合一路跑到下一玩家回合开始，若某 mod 的结束回合里有「等你答下一块屏」的环节，会在那里停下并正常返回 `awaitingSelection`（B151 已保证屏开着不推进），不会死循环；②真跑不完（例如某 mod 动作永不 isDone）会 `answerLeftUnfinished` + WARN，该分支被搜索**丢弃**而不是存半成品——宁可少一条线，也不给面板假数；③`answerLeftUnfinished` 是静态标志，每次应答入口先 `drainAfterAnswer` 时会被重写，但调用方必须在同一次应答后立即读（`Solver` 两处即如此），若将来新增应答路径要照抄这个「答完立刻读」的顺序。**回滚**：删 `drainAfterAnswer()` 改回四处 `drain()`、删 `Solver` 两处判断、删 S207 与 `LongAnswerProbeAction`，互不牵连。

**对 mod 的实际影响**：面板——结束回合中途开屏那一步的**回合号与血量**现在等于实机走完的结果（以前停在半路，后面整条线都错位），`plan stale` 应不再因这种错位误丢计划；自动出牌——答完屏会继续把这次结束回合跑完再判下一步，不再在半程就切下一步；搜索——这种步会多花一次完成泵的时间，状态更真；不可完成的 mod 结束回合会表现为「该分支消失 + 日志 `answer-unfinished`」，不影响其它分支与其它 mod 的屏（判据是引擎自己的 `isScreenUp`/settle，不认 mod 类名或卡名）。

---

## B154 — 结束回合闸门读到半程值（只加取证，未修实现）

**状态**：未修（列在 `docs/SANDBOX-MECHANISMS.md` 的 M30，优先级 P0）；本轮只加并排取证。

**订正（B156，user m14712「你确定吗？要一开始就对」）**：本节标题原写「面板『预计掉血』是半程值」，**错**。核对代码：面板/路线条的数字来自搜索——`Gameplay.refreshPreview()`（`Gameplay.java:739`）取 `quick.previewHpLoss()`，`hpPreviewText()`（`:754`）只把它写成「预计 -N HP → 剩 M HP」，终态行取自 `Step.hp`。`predictEndTurnRisk()`（`:2308`）只喂「结束回合 ALLOW / CONFIRM / STOP」闸门（`:2446-2467`），**不进面板数字**。所以下面这个 51-vs-43 是**闸门**读到的半程值，不是面板显示错。两者仍是同一类缺陷（同一步被模拟了两遍，一遍完整、一遍截断），但它不解释「面板和实际不一样」。

**用户要求**（m14492）：「不是要换计划是要一开始就对。我说了多少次了，换方案」＝否决「期限续期 / 保留 pending / 事后重算」这一类补丁，要求定位沙盒本身的第一处不同。

**根因**：`Gameplay.predictEndTurnRisk()`（`Gameplay.java:2308-2338`）只跑一次 `Driver.endTurn()` 就取 `currentHp()`，从不回答选牌屏。B151 之后「屏开着动作队列冻结」是正确语义，所以它在「唐 → 豹指导开选牌屏」那一拍停下，把开屏的那一帧当成回合末。日志 21:22:45.261 `end-turn risk: 结束回合预计 -0 HP → HP 51（来袭 ≥35） -> ALLOW`；同一计划该步（trace step 14，`t=3→6`）终值是 `hp=43`。51 = 32（当前 HP）+ 24（TrancePower 每跳治疗 maxHealth*3/10）− 5。⇒ **面板数字必然与实际不同，这是预测器口径错，不是计划算错**。

**本轮改动（只有日志）**：`Gameplay.reportSettleWait()`（原 `:3039`）在 `pending != null` 时多打两项——`digestMatch=<bool> live=|…| plan=|…|`（`Digest.ofCurrent()` vs `pending.digest`，用 `Digest.stable` 比较）与 `live plays: … || plan plays: …`（`mirror/PlayTrace.recent(8)` vs 该步 `plays`）。决策、评分、搜索、期限逻辑一行未动。

**代价**：等待期每 2 s 多约 400 字节日志；每轮多一次 `Digest.ofCurrent()`（微秒级）。**风险**：无决策变化；`plan.plan.get(step)` 越界已 try/catch 吞掉（只在 pending 期间调用）。**影响**：面板/自动出牌/搜索行为不变，只有 solver.log 多两行同一步的实机/计划并排。

**未验证**：计划该步终值 43 是否等于实机终值——21:23:05 的 20 s 期限先到，计划被 `plan stale … live turn 5; dropping it` 丢掉，两侧从未比过。

---

## B155 — 机制清单 + 门禁覆盖（user m14586）

**状态**：已交付（文档 + 首个缺口门禁）。

**背景**：B141–B154 里几乎每个「面板和实机不一样」最后都归结为「沙盒少复刻了一条引擎机制」。用户 m14586 在四个候选方法里选了③「优先机制清单 + 门禁覆盖」。

**交付 1：`docs/SANDBOX-MECHANISMS.md`**（9.4 KB）——把「沙盒为什么和实机不一样」从猜变成查表。30 条机制 M1–M30，分八节：A 回合与动作队列（M1–M6）、B 选牌屏（M7–M11）、C 死亡与结算（M12–M14）、D 随机流（M15–M17）、E 镜像与隔离（M18–M21）、F 战斗结束（M22–M24）、G 战斗内（M25–M28）、H 生命周期（M29–M30）。每行四列：机制 / 引擎依据（反编译文件与行为）/ 沙盒实现（哪个类/方法）/ 门禁（哪几条 S 号）/ 状态（✅⚠️❌）。

**未覆盖清单（写进文档，带优先级）**：P0＝M30 半程值（即 B154 的 51 vs 43）、M24 沙盒不跑 `onVictory`（毒种腐朽、Self Repair 这类局外效果不可见）；P1＝M27 多段攻击、M17 读档重播种（`nextRoomTransition` 把 5 条流播种成 `new Random(seed+floorNum)`，`loadSeeds` 只恢复 7 条）；P2＝M29 `Gameplay.tick()` 提前 return 跳过跨帧刷新。

**交付 2：门禁 S208（M2）**：回合末弃掉整手牌 + 抽牌堆空时把弃牌堆洗回抽牌堆——这两件事都跨 H/D/X 且 digest 对顺序敏感，之前没有任何门禁钉住。实现：合成战 seed 9208 / 80 HP / `Strike_R`×5 + `Defend_R`×5 / JawWorm → `Driver.drain(4000,800)` → `World.captureLive()+install()+Driver.begin()` → 手牌再加 3 张 Strike_R、把抽牌堆整堆搬进弃牌堆 → 记总数与回合 → `Driver.pressEndTurnButton()` → `Driver.drain(4000,1500)` → 5 条断言：按到按钮 / 回合前进 / `hand=5` / 三堆总数守恒 / `draw>0`。实测 `pressed=true turn 1->2 hand=5 draw=8 discard=0 total=13 now=13`。

**注意**：S208 是**回归护栏**（它守住的是本来就在的行为），没有「修前必红」——真正需要修前必红的是 ❌ 项补门禁时（例如 M30、M27）。

**代价**：无运行时开销（一份文档 + 一条门禁，只在测试跑）。**风险**：清单可能漏机制 ⇒ 维护规则要求每次分叉先归到机制编号，找不到就加行；❌ 只能靠门禁转 ✅，不能因为「暂时没出问题」就标守住。**对 mod 的实际影响**：面板输出、自动出牌、搜索结果、其它 mod 兼容性**均不变**；变化只在开发流程（先补门禁再改实现）。

**验证**：`-Gates "S208"` 5/0；全量 core `passed=560 failed=0`（S1–S208）；jar sha256 `01006A355843BEA7DDEE8CDA5CA384C68D321633E011DA7816DDB087005A1433`（1,102,888 B，469 entries）已装。

---

## B156 — 订正 B154 的对象：面板数字来自搜索，不是闸门（user m14712）

**状态**：只订正记录，未改代码（等确认）。

**用户质疑**（m14712）：「你确定吗？要一开始就对。」

**结论：不成立，我上一条说错了。** 面板/路线条的数字来自搜索：`Gameplay.refreshPreview()`（`Gameplay.java:739`）取 `quick.previewHpLoss()`，`hpPreviewText()`（`:754`）渲染「预计 -N HP → 剩 M HP」；终态行取自 `Step.hp`。`predictEndTurnRisk()`（`:2308-2338`）只喂 `decideEndTurnRisk()`（`:2291`）这个「结束回合 ALLOW / CONFIRM / STOP」闸门（调用点 `:2446-2467`），**不进面板**。所以 B154 的 51 vs 43 是闸门的半程值，不是面板显示错。

**21:22 那次实测日志复算**（`C:/Users/YOKI/sts1-engine-solver/logs/solver-20261106-212314.log:11060-11082`）：
- `11064 end-turn risk: 结束回合预计 -0 HP → HP 51 -> ALLOW`（闸门半程值）
- `11065 settle wait: step 14/38 turn=3->3 expectedEnd=6`（计划要的是 t=6）
- `11068-11074` 实机两块 HAND 屏，答案 `Defend`、`NongAbacus` —— 与计划该步的答案（防御 → 农盘）**一致**
- `11067/11079/11080` 实机 turn 3→4→5，仍在朝 `expectedEnd=6` 推进（两次 Trance 跳回合）
- `11081 pending action did not settle within the deadline; plan kept` + `11082 plan stale: step 14 belongs to turn 3, live turn 5; dropping it`
⇒ 那次**模拟是对的**、执行也按计划走，破坏面板一致性的是**固定 20 s 墙钟**把仍在正确推进的一步判成过期并丢掉。这与「沙盒算错」是两回事。

**「一开始就对」的正解（提议，未实施）**：
1. **同一步只有一份模拟**：闸门不许自己再截断跑一遍 `Driver.endTurn()`，而是沿用该步的模拟结果（`Step.hp` / 步终 digest）；若确实需要独立预测，就必须把屏也按计划的答案答完再取数，不许半程取数。
2. **一步什么时候结束由计划声明**：判据用 `turn == expectedEnd`（现在已有 `expectedTurn`/`expectedEnd`）或 digest 匹配，而不是固定墙钟；只有「分数秒内没有任何推进」或「越过 expectedEnd / digest 明确不符」才算失败。

**代价**：判据从时间改成状态后，正常步零开销（本来每 2 s 就取一次 turn/digest）；长结束回合（跳两次回合、答两块屏）不再被误判，日志里 `plan stale` 噪声下降。**风险**：若计划本身把 expectedEnd 算错，等待会变成「等一个不会来的状态」——所以必须有「无推进即失败」的兜底（保留现有 quiet/stableFrames 观测量），并且 `plan stale` 的判据仍保留 turn 超过 expectedEnd 的分支。**回滚**：两处判据各自独立，任一处退回都能编译。**对 mod 的影响**：面板数字与执行不再被墙钟打断（长结束回合那一步不再中途丢计划），自动出牌不会因为 `plan stale` 停；搜索算法与评分不变。

## B159 — 结束回合风险闸门读半程值（user m15391）

**用户原话**（m15391）：「改」——即修 B158 末尾点出的唯一未修项（`docs/SANDBOX-MECHANISMS.md` 的 M30 ⚠️）。

**现象/根因**：`Gameplay.predictEndTurnRisk()` 只做 captureLive → install → `Driver.endTurn()` → `currentHp()`。B151 之后「屏开着 actionManager 冻结」，所以它在结束回合里第一块选牌屏（唐 `TangPower` 自动打豹指导 `PlayCardAction`）处停下，把「开屏那一刻」当回合末。21:22 三奴隶那次它报 `结束回合预计 -0 HP → HP 51`，而同一 step 的计划终值是 HP 43 ⇒ 闸门偏乐观（`ALLOW` 放行了实际更危险的结束回合）。**面板数字不来自它**（来自 `Solver.previewHpLoss`/`Step.hp`，见 B156 订正）。

**改法**（全部在 `{proj}/src/main/java/sts1solver/Gameplay.java`）：
1. `predictEndTurnRisk()` 在 `Driver.endTurn()` 之后调 `answerEndTurnScreens(answers, hpBefore, incoming)`，`answers` 来自新增 `endTurnStepAnswers()`（当前 `step` 的 `Step.action`）；返回非 null 就直接把它当结果返回（ok=false），否则才读 `currentHp()`。
2. `answerEndTurnScreens`：把计划里的 `choice` / `choiceDisplay` / `choiceUuids` / `choicePrints` 用 `splitChain("\|")` 拆成逐块链，`while (Driver.isAwaitingSelection())` 逐块 `Driver.resolveSelectionByNames(...)`；答不进去先 `Driver.settleForReplay(16)` 重试一次；答案用完仍有屏 ⇒ unknown（`结束回合里开了一块计划没有答案的选牌屏（…）`）；单块失败 ⇒ unknown（`第 N 块选牌屏答不进去：…`）；超过 64 块 ⇒ unknown；循环结束再 `settleForReplay(24)` 校验没有残留屏。
3. 新增 `splitChain` / `chainAt`（链比屏短时缺位补 ""）。
4. `probe/PlayChosenProbeAction` 新增 `public static int played;`，`play()` 首行 `++played`（门禁据此断言屏真的被计划里的牌答了）。
5. 门禁 **S213**「the end-turn risk is read after the whole end turn, not at its first screen (B159)」，插在 `Probe.core("S201 …")` 之前：seed 9213 / maxHealth 80 / deck `Defend_R×3 + Strike_R×2` / JawWorm → `Driver.drain` → JawWorm `Intent.ATTACK 18` → 计划一步 `Action.endTurn().withChoice(手牌里的 Defend_R)` → `installPlanForTest` → 队列里塞 `PlayChosenProbeAction` → `predictEndTurnRisk()`；断言 `risk.ok`、`played==1`、`risk.damage>0`。

**验证**：S213 单跑 3/0（`damage=6 played=1`）；**修前必红已实测**（把 `answerEndTurnScreens` 的调用临时改成 `null` 即旧行为，jar `D7FED1B3…814F7`）⇒ `S213 whole turn: … -0 HP → HP 80 … ok=true damage=0 played=0`，红 2 条（`played` 与 `damage`）；还原后重编 3/0。全量 core **576/0（S1–S213）**。最终 jar sha256 `BA8D6273DAFD08D8D0104A253A65C7AEBDFA2FFD10EC5140D13AA53E2DA8D3CE`（1,115,759 B，474 entries）已装 `D:/Steam/steamapps/common/SlayTheSpire/mods/`。

**①代价**：只在**结束回合那一步**多跑「逐块答屏」的几次 `resolveSelectionByNames`（每块几百微秒级，最多 64 块上限）；非结束回合的步零调用。代码 +约 110 行（4 个私有方法 + 1 个静态计数器）。

**②风险**：①答案链是「计划里那一屏顺序」，若实机开屏顺序与计划不同，会答错屏 ⇒ 靠 `resolveSelectionByNames` 的牌名校验与 unknown 停下兜底（不会盲填）。②屏里没有计划那张牌时返回 unknown，闸门按 STOP/ALLOW 规则走（`ok=false` 时 manual?ALLOW:STOP）⇒ 自动出牌会停下等人，这是有意的。③闸门自身仍跑在沙盒世界（captureLive/install/readBack/uninstall），异常一律 catch 成 `ok=false`，不会污染实机。④回滚：把 `answerEndTurnScreens` 调用改回 `null` 并删掉两个新方法 + S213 即可，编译无依赖。

**③对 mod 的影响**：面板数字不变（B156 已订正来源）；自动出牌在「结束回合里要答屏」的场合拿到的风险值变准（以前偏乐观），可能在真正危险时会 STOP（正确行为）；搜索/自动出牌决策语义不变；不认任何 mod 类名或卡名（只看 `Driver.isAwaitingSelection()` 与计划答案链），其它 mod 兼容性不变。

## B158 — 第二处墙钟（选牌答案 6 秒过期）、猜牌、700ms 判步窗（user m15075）

**用户原话**（m15075）：「1.删除墙钟：字段、两处赋值、超时分支全删（不再有任何时限兜底）2.应该不止这个原因。之前没加时钟也是错的」。

**背景**：B157 删掉的是「每步 20 秒 `pendingDeadline`」。用户判断「不止这个原因」成立——同一条链上还有三处非状态判据，全在 `Gameplay.java`：

1. **答案过期**：`private static long pendingChoiceUntilMs;`。submit 时置 `now() + 6000L`，`answerNextScreen()` 每递进一屏再续 6 秒；`answerLatentSelection()` 的判据是 `if (planned.isEmpty() || now() > pendingChoiceUntilMs)`，过期后只打一行日志就返回 ⇒ 计划里已经算好的答案被时间丢掉，实机那块屏被空放（自动出牌不动，也不报错）。
2. **猜牌**：实机答屏分支 `if (Driver.isAwaitingSelection())` 里，`planned.isEmpty()` 时调 `Driver.resolveSelectionLive(planned, …)` 走「最优一张」兜底（日志 `execute: answered selection with best-first`，21:22 那次结束回合里出现过两次）。这张牌是搜索从未假设的分支，答完之后的每个数字都是虚构的——正是用户反复说的「面板和实机不一样」。卡牌奖励屏同理。
3. **判步窗**：`AutoPace.readyToJudge(now(), lastActionAtMs, AutoPace.verifyMs())`（`settings.txt autoplayVerifyMs`，默认 700ms）决定「可以判这一步了」。判据用的是墙钟差，不是引擎状态；`Gameplay.engineSettled()`（actions/cardQueue/monsterQueue/preTurnActions 空 + currentAction done + 无屏 + limbo 空 + 非 isEndingTurn）才是状态判据。选牌重试窗同样：`Driver.selectionRetrying() && !AutoPace.readyToJudge(…)` 会在 700ms 后把控制权交回，即使屏还在重试。

**改法**：删 `pendingChoiceUntilMs`（字段 / submit 赋值 / answerNextScreen 续期 / reset 清零 / 判断条件）；`answerLatentSelection()` 只判 `planned.isEmpty()`；实机选牌与奖励两处无答案时**停下**——停自动、保留计划、`Log.warnEvery("selection-no-answer"/"reward-no-answer", 5000L, …)`，面板文案「这一屏计划里没有答案（实机开了沙盒没算到的界面）；自动出牌已停下，请你选，选完点「自动」继续」；重试窗只判 `Driver.selectionRetrying()`，无上限（`selection-retry` 5 秒一条）；判步删掉 700ms 等待，保留「digest 仍在变就先记一帧」的判断。另加两个 test hook：`pendingChoiceForTest()` / `holdAnswerForTest(String)`。

**门禁**：S211（seed 9211，用 `PlayChosenProbeAction` 在自动出牌开着时开一块计划没有答案的屏：屏应仍开着、一张牌都没被填、计划保留；**修前实测红 2 条** `filled=1`、屏被关掉，jar `73CFBE14A64C2B19901F3BC77B3810A4B5454C3146A77E9C4C741CB293BD4699`）、S212（seed 9212，`holdAnswerForTest` 记下答案 → `Thread.sleep(6300L)` → 再开屏：答案仍被使用、用完即消费）。全量 core **573/0（S1–S212）**。

**代价**：删的都是等待窗口，普通步零开销；S212 让 core 多花 6.3 秒；代码净减。

**风险**：①计划里没有答案时现在会停（以前猜着继续），缓存里没有答案的老计划更容易停——有意为之，宁可停下也不编造状态；②重试改为无上限等待，真死锁只能靠 `selection-retry` 日志（5 秒一条）发现；③卡牌奖励屏也会停，若某 mod 的奖励候选枚举不到会停在奖励屏等你手选。**回滚**：三处互相独立（过期判据 / 猜牌分支 / 判步窗），任何一处单独退回都能编译。

**对 mod 的影响**：面板不再出现「按猜的牌往下算」的后续数字；自动出牌在实机开了沙盒没算到的屏时停下等你；搜索、评分、缓存键、其它 mod 兼容面不变（判据只用 `isScreenUp` / `selectionKind` / `engineSettled`，不认 mod 类名）。

**仍未修**：`Gameplay.predictEndTurnRisk()`（`Gameplay.java:2308-2338`）只跑一次 `Driver.endTurn()` 就取 `currentHp()`，遇屏就停 ⇒ 喂给结束回合 ALLOW/STOP 闸门的是半程值（M30 ⚠️）。B156 已确认它不进面板。

## B157 — 固定 20 秒墙钟把仍在正确推进的一步砍掉（面板与实机错位）

**触发**（user m14812「新bug，面板实际不一致」；m14819「1.失败的最新日志打三奴隶商人就是。2.不用管时限兜底，没做的话不用做。改好了之后根本不会出错。有不对的直接停下来 3.实机多跳了一回合自动出牌停下来等你」）。

**现象**：三奴隶商人的计划 38 步，前 13 步 `step plays` 两侧逐张一致；第 14 步（结束回合，计划 `t=3→6`）实机两块 HAND 屏答案与计划一致（`Defend` / `NongAbacus`），turn 也按计划在推进（3→4→5），但 20 秒一到：

- `logs/solver-20261106-212314.log:11082` `pending action did not settle within the deadline; plan kept`
- `:11083` `plan stale: step 14 belongs to turn 3, live turn 5; dropping it (plan age 109211ms)`

pending 被清空后，`planIsStale()` 的 `pending == null` 分支只看 `current.turn < live` ⇒ 整条计划被丢，此后面板与实机错位、自动出牌停摆。

**根因（代码级）**：{proj}/src/main/java/sts1solver/Gameplay.java 的 `pendingDeadline = now() + 20000L`（`executeStepInternal` 与 `startPendingForTest` 各一处）＋ `pollPending()` 里 `if (now > pendingDeadline) { … }`。这是一个**墙钟**判据，和「这一步有没有走完」无关；一次结束回合里两次 Trance 跳回合 + 两块选牌屏，正常就可能超过 20 秒。

**改法**：
1. 删除 `pendingDeadline` 字段、两处赋值与整个超时分支——**不再新增任何超时兜底**（user m14819 第 2 条）。
2. 结束回合的完成判据改为「必须走到沙盒测出的 `turnAfter`」：`if (endsTurn && GameActionManager.turn < expectedEnd && !fightFinished()) return;`（连跳 2–3 回合也照等）。
3. **越过即停**：`GameActionManager.turn > expectedStepEnd()` 时停下自动出牌、保留计划、不推进 step，状态写「实机已到第 X 回合，计划这一步只到第 Y 回合；自动出牌已停下，计划保留」。
4. **偏离即停**：某步 settled 后的 stable digest 与计划不符时，不再 `noteDriftAndContinue` 静默继续，而是停自动出牌 + status「第 N 步和计划不一样（局面已偏离）；自动出牌已停下，计划保留」；计划保留，不重算（user m14819 第 3 条）。

**门禁**：S209（越过即停，3 条）、S210（偏离即停，3 条）。修前必红已实测：把两处 `stopAutopilot` 改成 `if (false)` 后 S209 红 1 条（autopilot 仍为 true）、S210 红 1 条（status 不是「不一样」）。

**验证**：`-Gates "S209,S210"` 6/0；全量 core **566/0（S1–S210）**；jar sha256 B1C66D64F31DC9D1EEC6034AA1F01F0D1345D560396A28375A7539BB7AE1E408（1,107,252 B）已装。

**代价**：正常步零开销（判据本来每 2 s 就在读 turn/digest）；只有真正的长结束回合会多等几个滴答。代码 -1 个字段、-2 处赋值、-1 个分支，+2 个停止分支。

**风险**：①「越过即停」依赖计划给出的 `turnAfter`；若某步的 `turnAfter` 算小了，实机正常推进也会被判越过而停下——表现是「自动出牌停了但局面没错」，此时点「计算」即可继续，不会产出错误数字。②「偏离即停」会让以前被静默吞掉的分叉变成停止，用户会看到更多停止（这是 m14819 要求的「有不对的直接停下来」）。③没有任何墙钟兜底 ⇒ 若引擎真的卡死（队列永不动），自动出牌会一直等而不是 20 秒后放弃；此时按 ESC / 点「计算」可打断。④回滚＝恢复 `pendingDeadline` 字段与超时分支、把两处 `stopAutopilot` 去掉；门禁 S209/S210 会一起红，可直接当回滚检测。

**对 mod 的实际影响**：面板不再在长结束回合中途丢掉计划（三奴隶商人这类局面面板应与实机对齐）；自动出牌在真正的分叉/越过时停下等人而不是静默继续；搜索与评分口径不变；不涉及任何 mod 类名/卡名特判（判据只用 `GameActionManager.turn`、`Digest.stable`、`AbstractDungeon.isScreenUp`）。

---

## B160 — 面板删掉两行：遗物回血的「可能更多」与「战后药水」（user m15722）

**用户原话（m15722）**：「1.其他的文本都不要改…『还有没认出来的遗物，可能回更多』这个是为什么显示，沙盒模拟应该不会对某个遗物特判所以不应该存在认出哪个遗物。如果我说的是对的就删掉…正确的说有哪些遗物回了多少血。2.『战后药水: 会掉 Fire Potion（药水栏已满，喝药可换回）』这个又是什么，没有显示过也没必要显示，可以删掉。」

**判定：用户说得对 —— 两处都是「白名单」漏到面板上**

1. `search/PostCombatHealing.java` `describe()` 结尾 `+ (certified() ? "" : " · 还有没认出来的遗物，可能回更多")`。`certified()` 遍历 `player.relics`，只要有一件 relic 的类名不在 `KNOWN = {BurningBlood, BlackBlood, MeatOnTheBone}` 里就返回 false。⇒ 带着**任何**非原版遗物（哪怕跟回血毫无关系）这句话就出现；它不是在报告「有遗物能回血」，而是在报告「我们有一份白名单」。游戏本身并不「认出」遗物，它在胜利时跑遗物的 `onVictory`。
2. `search/PotionOutlook.java` `describe()` 的「战后药水: 会掉 X（药水栏已满，喝药可换回）」/「战后药水: 本场不会掉（药水栏已满）」。它只在**药水栏满**时才非空（`if (!beltFull || sozu) return "";`），所以平时看不到 —— 这就是用户「没有显示过」的原因。它唯一的信息是「满栏时喝掉一瓶会不会被奖励补回」，而这个判断早已以 8000 分（4 HP）的 credit 进了 `Solver.score`（`PotionOutlook.replacementHpCredit`）；面板这行既不是玩家在这里要做的决定，也无法据此改变出牌。

**改法（只删面板行，不动计价）**

- `search/PostCombatHealing.java`：`describe()` 只返回 `"打赢后固定回血 +N（燃烧之血 +6，带骨肉 +12，自我修复 +N）"`；删除 `certified()` 与 `KNOWN`（删掉这句后全仓唯一调用点是 extended 门禁 S14 的一条断言，一并删除）。
- `search/PotionOutlook.java`：删除 `describe()`（唯一调用点是面板）。
- `Gameplay.java`：删除 `potionRow = PotionOutlook.describe()` 的取行与 `rows.add(potionRow)`（原 :1826-1829），留 B160 注释。
- `probe/Probe.java` S14：删掉 `a vanilla relic set is certified` 断言与日志里的 `certified=` 字段（S14 由 5 条变 4 条；S14 是 extended 门禁，core 计数不变）。

**保留的边界（写在这里，不藏）**：`bound()` 仍然只统计四个原版来源（燃烧之血/黑血/带骨肉/自我修复）。mod 遗物的 `onVictory` 回血在沙盒里不跑（`docs/SANDBOX-MECHANISMS.md` M24 ❌），所以它依旧不会被计入 —— 这是**已知边界**，不是面板文案能表达的；要真算得靠「胜利节点跑引擎 onVictory」（独立任务，未做）。

**验证**：build+install OK，jar sha256 `5B41140D1F75C0B56897F712668B1AE6B89AFBFF363D0FC2C56338C8FFD6C22E`（1,115,020 B）；门禁子集 `S14,S27,S39,S171` **26/0**；全量 core **576/0**（S1–S213，核心计数不变，因为 S14 属 extended）。

**代价**：运行时 0（删两行文本与两个方法，无新分配）；代码 −约 40 行。
**风险**：①删掉 `certified()` 后面板不再提示「可能有 mod 回血没算」——这是用户要求的方向，但**信息缺口真实存在**，已写进本节与 SANDBOX-MECHANISMS；②S14 少一条断言，不再覆盖 certified 语义（该语义已不存在）。回滚＝还原三个文件 + S14 那条断言。
**对 mod 的实际影响**：面板少两行（`战后药水:` 与 `· 还有没认出来的遗物…`）；自动出牌/搜索结果/评分完全不变（`bound()` 与 `replacementHpCredit` 未改）；其它 mod 兼容性不变。

## B161 — anyNumber 选牌屏枚举全部子集（user m15864「134都要做」）

**现象（用户）**：赌博筹码这类「弃任意张」的屏，求解器给的答案总是「价值最高的前 k 张」（或空），永远选不出「弃第 3 好的那张、留第 1 好的那张」这种组合。

**引擎事实**：`_tmp/saveload/com/megacrit/cardcrawl/actions/unique/GamblingChipAction.java:36-60` 调 `AbstractDungeon.handCardSelectScreen.open(TEXT[0], 99, true, true)` —— quantity=99、anyNumber=true、canPickZero=true。取回后对每张 `moveToDiscardPile` + `triggerOnManualDiscard`，再 `DrawCardAction(选中张数)`。

**根因**：`{proj}/src/main/java/sts1solver/drive/Driver.java` 的 `selectionOptions(int max)` 在 `if (upTo && !scry)` 分支只发 `addOption(空集)` + 每层一张 `take(best,k)` 前缀。3 张手牌 ⇒ 只有 4 个候选；任何「非前缀」组合在进入搜索前就被丢掉了。这不是剪枝，是候选集生成缺失。

**改法**：同一分支改为 `k=0..maxKeep` 逐层调用既有 `combinations(best, k, cap - out.size())`（按牌价值总和 best-first，扫描上限 `COMBINATION_SCAN=4000L`），cap = `Limits.choiceBranches(...)`/`Limits.handBranches()`（HAND_LOW=16、HAND_HIGH=36、unlimited=Integer.MAX_VALUE）。旧前缀正好是 size-k 组合的第一个 ⇒ 旧行为是新集合的子集，不丢任何原答案。`addOption` 仍按 `identityOf`（cardID+timesUpgraded）顺序去重。

**门禁 S214(5)**：seed 9214、maxHealth 80、deck `{Strike_R, Defend_R, Bash}`、JawWorm → `Synthetic.build` → `Driver.drain(4000,800)` → `Gameplay.tick()` → 400 次 `actionManager.update()` 等 settle → `addToBottom(new GamblingChipAction(player))`（真实遗物动作）→ 200 次 update 等开屏 → `Driver.selectionOptions(8)`：断言屏已开 / hand==3 / singles==3 / pairs==3 / options.size()==8。

**验证**：新实现 `open=true hand=3 options=8 one=3 two=3 three=1`；修前必红实测（upTo 改回前缀，备份 `_tmp/Driver.java.b161bak`，jar 095EE7F9…79B6）`options=4 one=1 two=1`、`passed=2 failed=3`。还原后子系统 31/0，全量 core **581/0（S1–S214）**，jar sha256 `FFC7338A5E262F7801B0E438C7906DF17025989033BDEDAC0DF758D2284E253F`（1,117,383 B）已装。

**①代价**：搜索侧分支数从 n+1 涨到 min(cap, ΣC(n,k)) ≤ cap（n=3、cap=16 时 4→8）；运行时零开销（`selectionOptions` 只在生成分支时调用，非搜索路径不经过）。

**②风险**：①搜索树变宽 ⇒ 同节点预算下更宽更浅；②cap 命中时按「价值总和」截断，仍带启发式，不是真全枚举（想全枚举需调 `Limits`，unlimited 才是真全量）；③「保留 N 张」这类 upTo 屏的分支同样变多（受同一 cap 约束，不是只影响赌博筹码）；④回滚＝把 `upTo` 分支改回前缀循环 + 删 S214（单点，编译即回退）。

**③对 mod 的影响**：面板输出不变；自动出牌不变；搜索结果更宽、可能更慢一点但覆盖更多弃牌组合；其它 mod 的 anyNumber 屏同样受益，判据只看 `upTo/anyNumber` 与牌价值，不认具体卡名/遗物名。

**未做（待批/待做）**：① `Solver.candidates()` 仍只返回 `Legal.actions()`（root 没有「答屏」位置）；④ `Gameplay.startQueuedSolve()` 屏开着时仍丢弃排队计算、`engineSettled()` 仍把屏当未结算；换序（A 方案＝点击脚本 select/deselect）未获批。

## B162 — 选牌屏开着也能开算（①）+ 答屏本身是一步（④）（user m16041「继续做14」）

**现象（用户）**：赌博筹码 / 遗物 / 唐这类在回合开始就打开选牌屏的时候，点「计算」什么也不发生（搜索被丢弃）；屏只能等玩家自己点，求解器永远算不到「这块屏该怎么答」。

**引擎事实**：`AbstractRoom.update()` 只在 `!AbstractDungeon.isScreenUp` 时调 `Gameplay.actionManager.update()` ⇒ 屏开着时动作队列**冻结**（B151）；`HandCardSelectScreen.prep()`（`_tmp/saveload/.../screens/select/HandCardSelectScreen.java:600-628`）`selectedCards.clear(); wereCardsRetrieved=false; isScreenUp=true; screen=HAND_SELECT`；`GamblingChipAction.update()`（同目录 actions/unique/…:36-60）在 `duration==0.5f` 时 `handCardSelectScreen.open(TEXT[0], 99, true, true)`，下一拍 `if (!wereCardsRetrieved)` 时把选中的牌弃掉再 `DrawCardAction`。

**根因（分三层）**：①`Solver.candidates(World)` 只返回 `Legal.actions()`（手牌/药水/结束回合），root 上根本没有「答这块屏」这个位置；②`Gameplay.startQueuedSolve()` 遇 `isScreenUp` 直接丢排队计算，`engineSettled()` 也把屏当未结算；③（本轮挖出的真根因）`{proj}/src/main/java/sts1solver/mirror/World.java` 的 `readBack()` 对 `Statics.Spec.discardOnReadBack` 一律 `continue`，而 `AbstractDungeon.screen`/`isScreenUp` 自 B81 起就在这个名单里 ⇒ 「答完屏的世界」下一次 `install()` 又把那块屏装回来（`isScreenUp=true`）⇒ 动作队列永远冻结 ⇒ 之后每张牌、每次结束回合都**静默 no-op**（实测最深线 replay 290 步，`t=1 e=3 hp=80 mon=43` 全不变；搜索把 no-op 串成无限长线，最后 `route: the deepest line was not usable`）。

**改法**：①`search/Action.java` 增 `kind="sel"`、`screenKind` 字段、`Action.selection(cardIds, display, uuids, prints, screenKind)`、`isSelection()`；并把 `isEndTurn()` 从「handIndex<0 && potionSlot<0」收窄为 `"end".equals(kind)`（否则答屏步会被当成结束回合）。②`search/Legal.java` 增 `selectionActions()`（`Driver.selectionKind()` 非 NONE 时用 `Driver.selectionOptions(Limits.choiceBranches(kind,1))` 造候选）与 `apply()` 的答屏分支（`Driver.isAwaitingSelection()` 假 → `not-settled: no selection screen is open`；`Driver.resolveSelectionByNames(...)` 失败 → `not-offered: the screen does not offer it (<原因>)`）。③`Solver.candidates` 有等待中的屏时**只**返回答屏候选；`Solver.answerForReplay` 对答屏步早退（`Legal.apply` 已答过，再答会抛「answered a screen but none opened」）。④`Gameplay` 六处：`blockedReason(Action answering)` + `pickerWaiting()`、`startQueuedSolve`/`startQuickSolve` 允许屏开着（且在等答案）时开算、`executeStepInternal` 用 `blockedReason(step.action)`、`submit` 遇答屏步走 `Driver.resolveSelectionLive(...)`，失败即 `fail(...)`。⑤`World.readBack()` 对 `screen`/`isScreenUp` 特例（新私有 `isScreenState(Statics.Spec)`）：`waiting = isScreenUp && Driver.isAwaitingSelection()`，waiting 时回写实际值，否则 `screen` 写 `CurrentScreen.NONE`、`isScreenUp` 写 `FALSE`（死屏/死亡屏仍不带回，B81 意图保留）。⑥`Legal.apply` 在 `isEndTurn()` 之前加硬判据：`AbstractDungeon.isScreenUp` 且不是答屏步 ⇒ 抛 `not-settled: a screen is open (...) and no answer is waiting; the action manager is frozen`，把静默 no-op 变成显式失败。

**门禁 S215(7)**：seed 9215、maxHealth 80、deck `5×Strike_R + 5×Defend_R`、JawWorm → `Driver.drain(4000,800)` → `Gameplay.tick()` → 400×`actionManager.update()` 等 settle → `addToBottom(new GamblingChipAction(player))` → 200×update 直到 `Driver.isAwaitingSelection()`；断言：真屏已开 / root 前后屏状态相同（搜索不消耗 root 的屏）/ 计划第一步 `isSelection()` / 屏提供答案 / 答屏步不是结束回合 / 答完 waiting action 真的执行且 `discard==pick.size()` / `Gameplay.startQuickSolve(false)` 能开算。

**验证**：S215 `passed=7 failed=0`，日志 `PASS the first planned step answers the screen instead of playing a card | steps=12 first=答选牌（选：打击,打击）`；回归子集 S196,S197,S198,S199,S206,S207,S211,S212,S213,S214,S215 = **48/0**；全量 core **588/0（S1–S215）**；jar sha256 `AE237A93E31BD049D537DED870AACAB603E66C26D22B87A6CFE419515D67DB67`（1,123,015 B）。

**①代价**：每个 `readBack()` 多两次静态读 / 一次 `isScreenUp && isAwaitingSelection()`（搜索每步一次，量级忽略）；搜索多一类「答屏」分支（屏开时原本没有分支，现在等于该屏候选数，受 `Limits` 约束）；`Gameplay` 增约 40 行、`World` 增约 30 行、无新线程/无新分配。

**②风险**：①屏状态现在会随世界前进（以前被丢掉）——若某处代码假设「世界永远没有屏」，会看到 `isScreenUp=true`（全库 grep 只有 Driver/Gameplay 判它，均已按「等答案/冻结」处理）；②死屏（`isScreenUp=true` 但无人等答案）被归一成 `NONE`，若将来有 mod 用「僵尸屏」做特殊流程，会被视为已关闭；③答屏步进入搜索 ⇒ 计划里会出现「答选牌」这类步骤，旧计划/缓存（`PlanCache` 键含 digest 不含屏）在同 digest 下仍可命中——本轮未改缓存键；④回滚＝`readBack` 恢复 `continue` + 删 `Legal.apply` 的 not-settled + 删 `selectionActions`/`candidates` 注入 + 删 S215（四处独立可单独回退）。

**③对 mod 的影响**：面板——屏开着时点「计算」会真的算，面板会显示「答选牌（…）」为首步，其余不变；自动出牌——遇到屏先答再继续（以前丢弃计算、等玩家）；搜索——多一类答屏分支，屏开着也能从当前局面向后算；其它 mod——判据只看 `Driver.selectionKind()`/`selectionOptions`（HAND/GRID/REWARD 的通用候选），不认卡名/遗物名，任何用标准选牌屏的 mod 都受益；非标准自绘屏仍会被 `not-settled` 显式拒绝，不会静默。

**保留的边界**：①~~换序（select→deselect 改手牌顺序，A 方案）仍未做~~（已由 B163 做掉，见下节），但一步内连开多屏（`Solver.expandScreens`）的换序仍未做；②屏开着时的搜索不从「玩家可能先做别的操作」展开（屏开着时能做的只有答屏，符合引擎）；③`PlanCache` 键仍不含屏状态（同 digest + 不同屏的理论情况由 `not-settled` 兜住，不会静默算错）。

## B163 — 答选牌可以带换序（A 方案）（user m16041「那就做a」）

**现象（用户）**：站在「弃任意张 / 保留任意张」这类选牌屏前，玩家能做的事不只是「选哪几张」——他可以先把某张牌点一下再点一次（取消选择），净效果是这张牌落到手牌最后一位。手牌顺序是引擎状态：唐（`TangPower` → `PlayRandomCardAction`）用 `AbstractDungeon.cardRandomRng.random(hand.size()-1)` 从 `hand.group` 抽一张打，回合末弃牌也按 `hand.group` 顺序。求解器的答案只有「选哪几张」，所以「换序」这条线在搜索里根本不存在，自动出牌也不会去移动牌。

**引擎事实（反编译）**：①`HandCardSelectScreen.selectHoveredCard()`：`hand.removeCard(c); selectedCards.addToTop(c)`；②取消选择（点已选牌）：`AbstractDungeon.player.hand.addToTop(e)` 再从 `selectedCards` 移除；③`CardGroup.addToTop` = `group.add(c)`（追加到末尾），`addToBottom` = `add(0,c)`。⇒「点第一张 → 再点一次」的净效果就是这张牌落到手牌末尾，张数不变、`selectedCards` 不残留。

**改法**：①`search/Action.java`：新增 `moved`/`movedUuids`/`movedDisplay` 三字段（`withChoice`/`withCardUuid`/`withCardPrint` 三处复制块必须同步），新增 `Action.selectionWithMoves(...)`，label 追加「（换序：X、Y）」。②`drive/Driver.java`：新增 `moveToEndOfHand(List<AbstractCard>)` —— 逐张 `rebind` 到当前手牌、`hand.removeCard`+`hand.addToTop`+`setAngle(0,false)`，末尾 `hand.refreshHandLayout()`；`resolveSelectionByNames`（4 参→6 参重载，4 参委托）、`resolveSelectionLive`（4 参→6 参）、`resolveSelection(chosen, moved)` 都把 moved 传下去；非 HAND 屏要换序 ⇒ `refused("not-offered", "only a hand picker can be reordered (…)")`。顺手修掉「空答案无法回放」：以前 `chosen.isEmpty()` 一律 `not-offered`，现在用 `askedEmpty = cardIds 为空` 区分「计划明确要求一张都不选」与「计划要选但答不进去」。③`search/Legal.java`：`selectionActions()` 每屏在普通答案之外生成 ≤`REORDER_BRANCHES`(4) 个变体（取**未选中**的最高价值牌移到末尾，按 `Driver.orderValue` 降序；cap 由 `Limits.choiceBranches(kind,1)` 给，`variants = min(4, cap/4)`），`apply` 传 `a.moved/a.movedUuids`。④`Gameplay.submit` 的答屏分支传 moved（自动出牌真的会移动手牌）。

**门禁 S216(5)**：seed 9216、maxHealth 80、deck `Strike_R×2 + Defend_R×2 + Bash`、JawWorm → 真实手牌屏（`ReorderPickProbeAction`，`open("打出",99,true,true)`，只记录 `picked` 不动手牌）→ 答「选中 Bash + 把另一张移到末尾」。断言：①屏已开；②label 含「换序」；③等答案的动作读到的正好是 Bash；④手牌最后一张是移动的那张；⑤第二轮「一张都不选 + 移一张」也放行（`applied=true picked=[] last=移动的那张 stillOpen=false`）。

**验证**：S216 `passed=5 failed=0`；修前必红（把 `if (!moveToEndOfHand(moved))` 临时改成 `if (false)`）实测 `passed=3 failed=2`（「最后一张」与「空答案+换序」两条红）。还原后重编，全量 core **`passed=593 failed=0`（S1–S216）**。jar sha256 `4D1E6D4EB878169121AC6E23843E5C5641F543F4C2AF33D8AA55185089210B7C`（1,130,506 B）已装 `D:/Steam/steamapps/common/SlayTheSpire/mods/`。

**①代价**：搜索每屏多 ≤4 个分支（`REORDER_BRANCHES`，只在屏等待答案时生成，非搜索路径零开销）；`Driver`/`Action`/`Legal`/`Gameplay` 合计约 +120 行；`moveToEndOfHand` 每张一次 `removeCard`+`addToTop`+`refreshHandLayout`（布局重算 O(手牌数)，只在答屏那一帧）。

**②风险**：①换序会真的改变手牌顺序 ⇒ 唐打哪张、回合末弃哪张都会变，这是引擎真实语义，但意味着「同一个选中集、不同顺序」是两个不同的世界（digest 的 H 按 group 顺序输出，队形不同就不匹配，B157 会停）；②变体只移「未选中的最高价值牌」，不是全排列 ⇒ 某些需要移动多张/移动已选牌的换序仍不在搜索里（已知边界，不是 bug）；③空答案语义放宽：过去「空答案=not-offered」的调用方若依赖该拒绝行为，会看到合法通过（全库调用点只有 Legal/Gameplay/Solver，已同步）；④非 HAND 屏要换序直接 `not-offered`，不会静默移动；⑤回滚＝删 6 处改动（Action/Driver/Legal/Gameplay/ReorderPickProbeAction/S216）。

**③对 mod 的影响**：面板——计划行会多出「（换序：X）」，路线条与预计血量不受影响；自动出牌——遇到屏会按计划真的移动牌（以前只选不换）；搜索——每屏多 ≤4 个分支，可能稍微变宽变浅；其它 mod——只认引擎的 HAND 选牌屏与卡 id/uuid，不认 mod 名，任何用 `handCardSelectScreen.open` 的 mod 牌都同样支持；一步内连开多屏（`Solver.expandScreens`）目前不支持换序（保留边界）。

## B164 — 战后奖励页计入胜利评分（user m16960 / m17196 / m17363）

**现象（用户 m16960）**：最新日志打史莱姆+小偷，第 5 回合本可「痛击+头槌」杀掉小偷，计划却结束回合把它放跑。

**取证**：solver.log:1250 那一版 15 步计划最后就是「头槌→抢劫的 ｜ 结束回合」；step 14（solver.log:1368-1371）`step plays: live Headbutt@t4… || sandbox Headbutt@t4…`，Looter 8/50→3/50，随后 step 15 就是结束回合 ⇒ **计划主动预期它活着并于本回合逃跑**，不是执行失败、也不是漏算伤害。

**根因（四条，都在「价目表」而不是执行）**：
1. `Solver.score()`（{proj}/src/main/java/sts1solver/search/Solver.java:2208-2209）只有 `- deficit*POINTS_PER_HP - board - DeathSaves.points + goldDelta*goldRate()`，**胜利端没有任何战利品项**。
2. B129 设计的 `EscapePolicy.forfeitPoints()` 全仓库零调用者（只有门禁 S187/S202 调 `forfeitPointsFor` 验数字）——逃生开销从未接线。
3. `AbstractRoom.java:309`：普通房条件是 `this instanceof MonsterRoom && !AbstractDungeon.getMonsters().haveMonstersEscaped()` ⇒ **有怪逃跑时连 10–20 金币整段都不发**，这条也完全没进模型。
4. `Looter.die()`（_tmp/saveload/…/exordium/Looter.java:169-184）先 `if (stolenGold > 0) room.addStolenGoldToRewards(stolenGold)` 再 `super.die()`；贼赃只进 `room.rewards`，**不进 player.gold、不进 digest、不上面板** ⇒ 杀与放跑在分数上完全等价，而放跑还省一张牌。

**修法（不特判卡名/怪名，不读 digest 反推）**：
- 新增 `search/PostCombatLoot.java`：`settle()` 在**一次性窗口**里把 `room.isBattleOver=true`、`rewardAllowed=false`、`AbstractDungeon.isScreenUp=true`、`player.isEndingTurn=false`、`endBattleTimer=-1`，泵 `room.update()`（≤8 帧）到 `PHASE.COMPLETE`，再 `combatRewardScreen.setupItemReward()`，**直接读真实 `room.rewards`** 的 RewardItem（GOLD/STOLEN_GOLD/CARD/RELIC/POTION）计价；对 `isEscaping && !escaped` 的怪临时置 `escaped=true`，还原实机 `haveMonstersEscaped()` 的语义；`finally` 逐项还原（RNG counter、遗物池、kill 计数、`room.rewards` 元素、`endBattleTimer`、`isScreenUp`、`isEndingTurn`、`escaped`）。任何 Throwable → `warnEvery` 并回退 `formula()`。`formula()` 按 `AbstractRoom.java:276-321` 复刻期望值（金币按 `EscapePolicy.expectedGold`，普通房且有怪逃跑→0；卡=1 张，Prayer Wheel→2；遗物只 elite/boss；药水按 `PotionBook`）。
- 新增 `search/LootBook.java`：`settings.txt` 的 `loot.card.normal/elite/boss`=2/4/10、`loot.relic.common/uncommon/rare`=5/10/15（单位 HP，仿 `GrowthBook` 的 500ms 缓存）。
- `Solver`：`readChoice` 末尾 `if (choice.win && !choice.dead && GrowthBook.counting()) choice.loot = PostCombatLoot.settle();`；两处评分（阻塞 Solver.java:1595、切片 Solver.java:3813）把 `-EscapePolicy.forfeitPoints()` 换成 `+lootPoints(loot)`——**逃跑侧不再单独扣分**，因为胜利端加 loot 后逃跑自然拿不到，且贼赃已从 `player.gold` 扣过（`goldDelta` 单独计），双加会双算。`EscapePolicy.forfeitPoints()` 保留给门禁/提示，javadoc 标退休。
- `Gameplay`：**面板不显示**（user m17808 明确要求删掉那一行），改为 `Gameplay.logLoot(Result)` 在 `plan :` 之后写一行 `loot : engine gold=… cards=… total=…`；`PostCombatLoot.Loot.panel()` 已删除。

**门禁与踩坑**：S217(8)/S218(4)/S219(6)/S220(4)。首跑 `passed=19 failed=2`，两条都是真问题：
- S220 红 `engine=3/6 expected=1/2`：生产代码 `PostCombatLoot.read()` 的 CARD 分支原按 `r.cards.size()` 计张数，但 `RewardItem.cards` 是**奖励屏提供的选项（通常 3 张）**，一个 CARD item = 玩家拿 1 张（Prayer Wheel 才开第二个 item）。改成 `cards += 1`。
- S217 红 `screen null -> NONE`：`AbstractDungeon.screen` 在首个屏出现前是 `null`，语义等于 `CurrentScreen.NONE`；门禁先把两边都归一成 NONE 再比。

**验证**：S217 8/0、S218 4/0、S219 **6/0**、S220 4/0（`AUTOTEST RESULT passed=22 failed=0`）；全量 core **`passed=615 failed=0`**（S1–S220，`-TimeoutSeconds 900`）；jar sha256 `AD183F727DB8852C11EFE3060DDF69540E6B2AB531979344250CC9A6989E9F97`（1,154,935 B）已 install。

**①代价**：每个**胜利**节点一次 `settle()` ≈1–2 帧 `room.update()` + 一次 `setupItemReward()`（微秒到毫秒级），且只在 `GrowthBook.counting()` 时跑；`LootBook` 有 500ms 缓存；无新增常驻内存（Snapshot 随调用释放）。

**②风险**：①结算动的是真实 `AbstractRoom`/`CardCrawlGame` 字段，Snapshot 漏还原任何一项都会污染实机——已逐项覆盖（RNG counter、遗物池、kill 计数、`room.rewards`、`endBattleTimer`、`isScreenUp`、`isEndingTurn`、`escaped`），并有 S217 钉住「屏幕状态与 escaped 还原」；②`rewardAllowed=false` + `SfxMute` 保证不出声、不开奖励屏，`setupItemReward` 单独 try/catch；③失败回退 `formula()` 是期望值，会与真实随机数有小差（S220 只断言 ±5 金），且每次回退有 `warnEvery` 点名；④回滚＝把两处评分改回 `-forfeitPoints()`、删两个新文件与面板行、`Gameplay` 一行。

**③对 mod 的影响**：面板——**不新增任何行**（m17808）；自动出牌——不变（结算发生在选完最优线之后，属于搜索内部）；搜索——开始偏好「打死小偷/不让怪跑」，且精英/boss 的遗物价值进入评分；其它 mod——只读引擎自己的 `room.rewards`，不认 mod 卡名/怪名，mod 自己发奖励也照样读到。

**已知边界**：`onVictory` 仍不跑（M24）⇒ 毒种塞腐朽、Self Repair 这类**战后能力**效果不可见，本次只覆盖「奖励页」；药水按 `PotionBook` 计价；结算用沙盒当前随机流，不推进实机 RNG。

### 追加：面板行删除 + 金币取整复核（user m17808，同日）

**要求**：①「面板多出来的这一行删掉，战后多少金币什么的你知道就行」；②确认金币/卡牌现在是否仍按 HP 折算、会不会再出现「24 金币 = 0 HP」那种取整，有问题直接改。

**① 面板行删除**：`Gameplay.lines()` 不再输出「战后 +X 金 +Y 卡（Z HP）」；改为 `Gameplay.logLoot(Solver.Result)`，在 `plan :` 之后打一行 `loot : engine gold=… cards=… total=…`（`PostCombatLoot.Loot.describe()`）。`Loot.panel()` 方法整体删除，避免留一段没人调用的展示代码。计价与评分**未动**——奖励页价值仍进 `Solver.score`，这是「打死小偷 vs 放跑」的判据。

**② 取整复核结论：没有这个问题，不需要改**。当前路径：`Loot.points() = hp * POINTS_PER_HP + gold * Solver.goldRate()`，`POINTS_PER_HP = 2000`、`goldRate() = GOLD_POINTS = 80`（growth.counting 时）。所以金币是**逐枚**精确计价的：24 金 = 1920 分 = 0.96 HP，1 金 = 80 分，不存在任何 25 金一档的截断。卡/遗物/药水是先按 HP 定价（2/4/10、5/10/15、`PotionBook`），再 ×2000 换成与金币同一量纲；那三个价目表本来就是整数 HP，是我们定的价，不是取整产物。

老的「24 金币 = 0 HP」来自 B146 之前 `GrowthBook` 的金币预算键（`growth.handOfGreed`，25 金一档、tenths 后再 `tenths/10` 向下取整）。B146 已把金币改成 `Solver.score` 的直接项（80 分/枚），`GrowthBook.GOLD` 常量保留只为「认出旧 settings 行」而不是继续读它（GrowthBook.java:43-48）；`credit()` 里已经没有任何金币分支。

**验证**：S219 由 5 条变 6 条——新增 `odd24` 断言（`new Loot(24,0,0,0,0,0,true).points() == 24*80`），并把「面板文本」断言换成「`describe()` 带 gold=20 且总分 5600、不再构造面板行」。重跑 S217–S220 `passed=22 failed=0`；全量 core **`passed=615 failed=0`**（S1–S220）；jar sha256 `AD183F727DB8852C11EFE3060DDF69540E6B2AB531979344250CC9A6989E9F97`（1,154,935 B）已 install。

**代价**：0（删除一行文本 + 一个静态方法；`logLoot` 只在计划算出后调一次，守卫 `loot == null || isEmpty()` 时直接返回）。
**风险**：面板少一行信息，玩家无法从面板读战后收益——这是用户明确要的；日志仍在，排障不受影响。回滚＝恢复 `Gameplay.lines()` 那段与 `Loot.panel()`。
**③对 mod 的影响**：面板少一行；自动出牌、搜索、digest 完全不变；`loot :` 行对 mod 内容同样适用（值来自真实 `room.rewards`）。

## B165 — 开屏随机归一：一次开屏只抽一次（user m17913 / m17967 / m17993）

**现象**：打大脑袋，第 63 步能力药水之后，第 72 步技能药水开出的三张候选与计划不同（实机 `[Skim, Chill, Conserve Battery]`，计划要 `Undo`/均衡），局面偏离 ⇒ 自动出牌按 B158「与计划不符就停」停手。用户提出的猜测是帧率影响随机数、想按帧率做分支。

**根因（决定性的，不是帧率分支问题）**：`DiscoveryAction.update()` 第一行无条件调用 `generateCardChoices(type)` / `generateColorlessCardChoices()` 生成三张，但只有 `duration == Settings.ACTION_DUR_FAST` 那一帧的列表会被 `AbstractDungeon.cardRewardScreen.customCombatOpen(...)` 采用；`tickDuration()` 把 duration 减掉 `Gdx.graphics.getDeltaTime()` 之后，后续帧生成的三张直接丢弃。而我们的 `Driver.pumpLiveUntilConsumed()` 在屏开着时还要再跑最多 8 次 `gam.update()`，每次重入都再抽 3 张 ⇒ 一次喝药实机被白推约 24 抽；沙盒 `pumpUntilSelection()` 在 `managerFrozen()`（模态屏）时只 `pumpScreenSelf(1)`、不跑 manager，所以沙盒不浪费。两边随机流由此错位，之后所有 `cardRandomRng` 相关行为（蛇眼费用、洗牌、唐的随机出牌）都会不同。日志里的「42」是 RandomXS128 内部步数，不是抽数。

**修法（不特判卡名/药名、不读 digest 反推、不把帧率当分支）**：新增 `src/main/java/sts1solver/mirror/DiscoveryRandom.java`——`@SpirePatch` 打 `DiscoveryAction.generateCardChoices(AbstractCard.CardType)` 与 `generateColorlessCardChoices()`（两个都是 private），`@SpirePrefixPatch` 里用 `Reflect.get(DiscoveryAction.class, "duration", action)` 读 duration：不等于 `Settings.ACTION_DUR_FAST` 就 `SpireReturn.Return(new ArrayList<AbstractCard>())`（这一帧不生成），等于才 `++generations` 并 `Continue()`。**无状态、不缓存**：live 与沙盒 clone 共享动作实例，缓存会串线。因为 `customCombatOpen` 本来只在 FAST 帧执行，返回空表对非 FAST 帧完全等价。

**面板标注**：`Action` 新增 `public boolean rngNormalized`（三份 copy 都带）；`Solver.expandCore` 在 apply 前后取 `DiscoveryRandom.count()`，变了就标该步；`Gameplay.buildRoute` 给步骤行加 ` *`，`lines()` 在计划末尾加一行图例「该步开屏随机已归一（一次开屏只抽一次；手打若沿用原版逐帧重抽，随机流会不同）」。用户 m17993 要求「手打可能与模拟不同的地方要简单标注」，指的就是这条。

**F2 检测（只记日志）**：`Driver.noteFrameDraws(where, screenWasOpen, before)` 用 `cardRandomRng.counter + miscRng.counter` 的差；只在「update 前屏已经打开」时统计（开屏那一帧 screen 在 update 内才置位，不报），delta>0 就按 `screen-rng` 键 10s 限流告警。覆盖 live pump 与 sandbox-side pump 两条路径。作用是扫出其它同样「每帧重算」的动作，不在本修范围内改造它们。

**门禁与踩坑**：S221(6)/S222(6)。S221 反射调两个 private 生成方法，断言 FAST 帧各得 3 张且抽 3、duration=0.2f 时空表零抽、`generations==2`；S222 真开一次 `new DiscoveryAction(true, 1).update()`，断言屏开三张、之后 10 帧零抽且卡片 ID 不变、`rngNormalized` 经 `withChoice/withCardUuid/withCardPrint` 都保留。**踩坑**：全量首跑 626/1，唯一红在 **S190 末条**——旧断言 `counter > rolls`（「manager 再跑时继续抽」）钉住的正是这次要消灭的浪费。已订正为 `spentAfterAnswer == 0`（「答完屏不再抽」），卡仍照常进手（同门禁前一条通过）。这正是 m04606 说的「过时判据要跟着订正」：不订正就会为了绿而保留浪费。

**验证**：`-Gates "S221,S222"` → `passed=12 failed=0`（S222 详情 `screenUp=true openingDraws=3 extraDraws=0 cards=[Master of Strategy, Trip, Forethought] same=true`）；全量 core **`passed=627 failed=0`**（S1–S222，`-TimeoutSeconds 900`）；jar sha256 `62A49DFE2D255D1597EA4C72FBD98D86839D42318B9BA31A9E75102C276C2377`（1,163,941 B）已 install。

**代价**：运行时为「每次生成时一次 float 比较 + 一次 static long 自增」，无缓存、无新增字段（`rngNormalized` 是 boolean，随 Action 走）；反而**减少了约 24 抽/次喝药**的浪费；搜索不变慢（只多一次 `count()` 读）。
**风险**：①补丁作用于全局，玩家手打的随机流相对原版会变（只少浪费，不改变概率分布），而 live 手打/自动出牌/沙盒三者一致；②若有 mod 依赖「逐帧重掷」并在非 FAST 帧读这两个方法，会拿到空表——但原版只采用第一帧，这类依赖本身就没有可见效果；③精确比较 `duration == 0.25f`：构造器都置 `ACTION_DUR_FAST`，clone 后 duration 可能 >0.25 但那时原版也不会开屏；④回滚＝删 `DiscoveryRandom.java`、`Action.rngNormalized` 及其三处拷贝、`Solver` 两行、`Gameplay` 两处、`Driver` 两个辅助方法，彼此独立。
**③对 mod 的影响**：面板——用到归一化随机的那一步多一个 ` *` 与底部一行图例，其余不变；自动出牌——随机流与沙盒对齐，喝药后不再因候选牌不同而停手；搜索——不变（沙盒本来就不浪费）；其它 mod——帧相关动作若仍在屏开后抽数，只打 `screen-rng` 告警，不改变其行为。
**已知边界**：其它帧相关动作（mod 自定义）尚未归一，F3 兜底是「执行后重算」；手打路径与自动出牌共用同一补丁，所以不存在「只有自动出牌被改」的情况。

## B166 — 计算时意图徽章上下浮动变快：`BobEffect` 被按引用共享（user m18398）

**现象**：搜索/计算期间，怪物头顶的意图图标与伤害数字（例如 7＝打 7 点）上下浮动的速度明显比平时快，看起来像抖动/瞬移；停止计算或平时不打自动就正常。

**根因**：`BobEffect` 是纯表现对象（不是 `AbstractGameEffect`），但类名落在 `mirror/ClonePolicy.java` 的 `com.megacrit.cardcrawl.vfx.` 前缀规则里，被当作「VFX 按引用共享」——于是沙盒怪物与实机怪物用**同一个** `BobEffect` 实例。`BobEffect.update()` 是 `y = sin(timer)*dist; timer += Gdx.graphics.getDeltaTime()*speed`（speed=4），而沙盒窗口里 `Driver` 的 delta 代理给的是 `FAST_DELTA = 20.0f`（`src/main/java/sts1solver/drive/Driver.java:864`；`simDelta = FAST_DELTA` 在 :872，`SIM_DELTA = 2.0f` 只是常量）。搜索每评估一个胜利节点都会泵 `room.update()`（`search/Solver.java:1575`，在 `Driver.begin()/end()` 之间，由 `search/PostCombatLoot` 触发）⇒ `MonsterGroup.update()` → `AbstractMonster.updateIntent()` → `bobEffect.update()` 推进 20×4 = **80 rad ≈ 12.7 个周期**；几十个胜利节点下来，实机徽章就在快速抖动/瞬移。（先前记录里写的「8 rad / 2 s」是错的，已按 `FAST_DELTA` 订正。）

**修法（不动模拟、不特判 mod、不读 digest）**：`mirror/ClonePolicy.java` 在 `TintEffect` 规则之后加一条 `if (n.equals("com.megacrit.cardcrawl.vfx.BobEffect")) return false;`——拷贝而不是共享，沙盒有自己的 bob 对象，实机 bob 只由真实帧推进。

**门禁 S223（4 条，`probe/Probe.java`）**：合成 JawWorm（seed=9223）→ 取实机怪 → `World.captureLive()`+`install()` → 取沙盒怪 → 反射 `AbstractMonster.bobEffect` 与 `BobEffect.timer`；断言两者非 null、`liveBob != sandboxBob`、`Driver.begin()` 后 8 次 `getMonsters().update()` 沙盒 timer 前进而实机 timer **位级不变**（`Float.floatToIntBits`）。日志 `S223 sandboxAdvance=… liveAdvance=…`。
- 反向验证（把规则临时改回 `return true`）：`passed=2 failed=2`，详情节选 `the eight simulated frames left the LIVE badge exactly where it was | live 582.14795 -> 1222.148`，`sandboxAdvance=640.0 liveAdvance=640.0`（＝8×80 rad）⇒ 门禁有牙、根因复现。
- 修复后：`passed=4 failed=0`，`sandboxAdvance=640.0 liveAdvance=0.0`。

**验证**：全量 core **`passed=631 failed=0`**（S1–S223，245 个门禁）；jar sha256 `ECE17BD0434AFCC045044667FA7335B4F15CDDFA91AA38CEA93A73DB8873EA68`（1,166,416 B）已 install。

**代价**：每个世界窗口多拷一个 `BobEffect`（4 个 float，按玩家＋怪物数），无每帧新增调用、无新增日志、无搜索开销。
**风险**：①将来若有人让逻辑读 `bobEffect.y`，会读到沙盒自己的值——这是沙盒应有的语义；②`BobEffect.timer` 是 private，靠 Cloner 拷字段（构造器不跑），若 Cloner 日后改成只拷公开字段需回看这一行；③回滚＝删这一行 + S223 门禁，彼此独立。
**③对 mod 的影响**：面板数字、自动出牌、搜索结果**全部不变**（`bobEffect` 没有逻辑读者，只有 render 读）；唯一变化是实机意图徽章的抖动恢复正常；与其它 mod 无交互（`BobEffect` 是原版类，mod 自己 new 的同样各自持有）。
**已知边界**：表现类共享对象目前只剩 `BobEffect` 这一处（`AnimationMute` 处理的是 spine `AnimationState`，属 M21 另一机制）；今后若 `vfx` 前缀下新增非 `AbstractGameEffect` 的类，必须同样在 ClonePolicy 里排除，否则会重演同一类「沙盒推进实机表现」的问题（机制清单 M41）。


## B167 — 读档后计划不复用（缓存键含中途相位、首步答屏必拒）+ 选牌步的答案链不消费（user m18895）

**现象**：同一条 19 步计划在 6 秒内被拒 8 次（日志 18:52:27/28/31/33/39、18:53:52/54/59）：`plan cache: hit (-1270444116) entries=17 hits=N` 紧跟 `WARN plan cache: hit rejected, does not fit the live hand | digest=…`，digest 逐字相同、key 相同，却每次重算 610–4258ms，结果又是完全一样的 19 步。另每条选牌步之后都出现 `the plan recorded N selection answers for this step but the fight opened 0; the rest were never used`。

**根因（两个同源缺陷，都在「计划答案怎样才能算被用掉」这条链上）**：
1. `Gameplay.cacheFits(Solver.Result)` 只处理「首步是结束回合」与「首步是药水」，其余一律 `return liveCardFor(first.action) != null;`，而 `liveCardFor` 只在**手牌**里按 uuid→cardPrint→handIndex 找。首步是「答选牌」（`kind="sel"`、cardUuid 空、handIndex=-1）⇒ 必然 null ⇒ 缓存 100% 被拒。这是「读档后同一局面却重算」的直接原因（不是有意强制重算）。
2. 缓存键把 raw digest 原样拼进去，而 `Digest.ofCurrent()` 的首段含 `ph=/q=/use=/end=`（action manager 中途相位）⇒ 同一局面隔一帧就换 key（连「命中被拒」都不打印）。附带发现：`Digest.stable()` 的剔除循环只看得见**整段**以 `ph=` 开头的 segment，而 transient 字段其实在第一个 segment 内部，所以 `q=/use=/end=` 从来没被剔除过（`ph=` 只是被一次 plain replace 去掉）⇒ 「only transient fields moved」判据也只在怪物垂死字段变化时才成立。
3. `Gameplay.submit` 的 selection 分支直接调 `Driver.resolveSelectionLive(...)`，**绕过** `answerNextScreen()`——后者才会 `pendingChoiceAt++` 或清空 `pendingChoice/Display/Uuids/Prints`。于是屏被答了，但链簿记不动：每步都重复告警，旧答案还会漏给后面打开的无关屏。

**修法（不特判卡名/屏名、不读 digest 反推）**：
- `mirror/Digest.java`：`stable()` 末尾改成对四处 token 一律 `replaceAll(" ph=\\S+","")` 等（含首段内部）。
- `search/PlanCache.java`：`key()` 先 `Digest.stable(stateDigest)` 再拼。
- `Gameplay.java`：新增 `planPendingChoice(Action)` / `clearPendingChoice()`（含 moved/movedUuids 两条链，替换 4 处旧清空）；`submit` 的 selection 分支改调 `answerSelectionStep(action)`（空答案＝没有链可推进，直接答；否则走 `answerNextScreen()`）；`answerNextScreen` 把 moved/movedUuids 按段传下去（保留 B163 的换序）；`cacheFits` 在结束回合/药水之前加 selection 分支 `selectionAnswerFits(action)`（屏种类对得上 + 计划要选的每张牌都还能在 `Driver.selectionCandidates()` 里唯一定位，选 0 张放行，屏没开拒绝）。
- `probe/Probe.java`：门禁 **S224**(2)/**S225**(2)/**S226**(3)。

**门禁实测**：
- 反向验证（把 `answerSelectionStep` 临时改回直接 `resolveSelectionLive`）：`-Gates "S226"` → `passed=2 failed=1`，`pending=[Strike_R]`、`consumed=true`（屏答了但链没消费）⇒ 门禁有牙。
- 修复后：`-Gates "S224,S225,S226"` → **`passed=7 failed=0`**；日志 `S224 kind=NONE fitsOpen=true fitsClosed=false`、`S225 stableBusy=[|t=3 hp=70/80 …]` 与 idle 完全相同而 hurt 不同、`S226 opened=true answered=true pending=[] consumed=true`。
- 全量 core：**`passed=638 failed=0`**（S1–S226，248 个门禁）；jar sha256 `AF702048E57AE8248DAD63F8B71AAF878F8FFB37DEA9BE1B36CBF5B18B424D23`（1,173,143 B）已 install。

**代价**：缓存命中率上升（同一局面隔帧不再 miss）⇒ 单次开算耗时下降；缓存里多保存一条 `moved` 链（字符串，几十字节）；`cacheFits` 的 selection 分支每步最多扫一遍候选（手牌/奖励组，个位数到十几张），O(n·k)，相对搜索可忽略；无新增每帧调用。
**风险**：①`stable()` 现在会剥掉任何形如 ` q=…`/` use=…`/` end=…` 的 token——若将来某段真把这类文本当局面字段（目前没有），会被误剔；②缓存键更宽松（不含相位）⇒ 若某相位差异其实影响结果（目前不存在：沙盒每次 install 都从真实状态重建），命中会复用旧计划；③`selectionAnswerFits` 以「计划要选的每张牌都能唯一定位」为判据，若屏的候选在两次读之间变了（mod 动态改 targetGroup），会误拒并重算——重算永远安全，只是慢；④回滚＝`Digest.stable` 尾部四行 + `PlanCache.key` 一行 + `Gameplay` 的 answerSelectionStep/planPendingChoice/clearPendingChoice/selectionAnswerFits 四处，各自独立；S224–S226 一并删。
**③对 mod 的影响**：面板——被拒绝重算的情况消失（开算更快、状态行不再停在「缓存命中被拒」）；自动出牌——选牌步的答案执行完即消费，不会再拿上一步的选牌去答后面的屏（行为改变，属修 bug）；搜索——候选/评分不变，只是复用已有计划；其它 mod——判据只认「屏种类 + 候选可定位」，不认 mod 类名，任何 mod 的选牌屏同样适用；若某 mod 屏的候选枚举不全，会走「拒缓存 → 重算」而不是答错。

**已知边界**：①缓存键仍不含随机流计数器（B150 曾做过、按 user m13154 撤销），所以「同一局面、不同随机位置」仍可能命中原计划；②`selectionAnswerFits` 只校验第一段答案（一步内连开多屏时，后续段在执行时才会发现不匹配，那时按 B159 的「跑完整个队列再读血」处理）。

## B168 — 沙盒窗口内仍在派发纯表现事件（healthBarUpdatedEvent）⇒ 计算时卡帧（user m19477）

**现象**：用户 m19321/m19477「每一局计算都会卡住，之前不会」；m19477 选定 A 方案「让玩家实际打到半血会正常触发播放雨爱，但沙盒不会」。搜索期日志（553,680 B / 451 行那一份）：11:53:00.821 `quick search: started, budget=1000000000ms narrow=false ascension=20`；四次 slow frame 618/617/617/591ms；`frame stats: frames=17 over=5397ms p50=54ms p95=511ms max=511ms gc=21/803ms heap 2287/7106MB`；搜索结束后同场景 p50=16ms。即卡的是渲染线程被切片占住，但尖峰远大于 40ms 的切片预算。

**根因**：`com.megacrit.cardcrawl.core.AbstractCreature.healthBarUpdatedEvent()` 是无参**纯表现**方法（只写 `healthBarAnimTimer = 1.2f` 与 `targetHealthBarWidth`），但 mod（YuAi 雨爱）在它上面挂了 `@SpirePostfixPatch` 播半血特效；沙盒窗口里每次血量变化都会派发它 —— 全量字节码扫描（`{proj}/_tmp/gmfull`，字符串 `healthBarUpdatedEvent`）得到 **8 个类、14 个调用点**：`AbstractCreature`(increaseMaxHp(int,boolean) / decreaseMaxHealth(int) / heal(int,boolean))、`AbstractPlayer`(damage(DamageInfo) / preBattlePrep())、`AbstractMonster`(heal(int) / damage(DamageInfo) / init())、`InstantKillAction.update()`、`SuicideAction.update()`、**`TheGuardian.changeState(String)`（2 个，不在 damage 里）**、`NeowsLament.atBattleStart()`、`PreservedInsect.atBattleStart()`。搜索每个节点都在 `Driver.begin/end` 之间真跑伤害/治疗 ⇒ 每节点都触发一次雨爱特效（实机 GL/纹理工作），叠加在 40ms 切片上就是几百毫秒的帧。

关键引擎事实（实测，`{proj}/_tmp/javassist-test`、`javassist-test2`）：MTS 的 prefix 早退**挡不住别人的 postfix** —— `PrefixPatchInfo` 是 `insertBefore`（patchOrdering=1）、`PostfixPatchInfo` 是 `insertAfter`（=2），而 javassist 的 insertAfter 把代码插在**所有 RETURN 之前**（包括前缀新加的那个 return）⇒ 用 `SpireReturn.Return(null)` 屏蔽 `healthBarUpdatedEvent` 对雨爱无效。所以只能改**调用点**。

**修法（不动模拟、不特判 mod 类名、不读 digest）**：新增 `src/main/java/sts1solver/mirror/PresentationQuiet.java`：
- `public static boolean quiet() { return World.anyInstalled(); }`（沙盒窗口内为 true，实机 false）。
- 14 个 `@SpirePatch(clz=…, method=…, paramtypez=…)` 嵌套类，每个里面 `@SpireInstrumentPatch public static ExprEditor instrument()`；`ExprEditor.edit(MethodCall call)` 只认 `healthBarUpdatedEvent`，用 `call.replace("{ if (!sts1solver.mirror.PresentationQuiet.quiet()) { $0.healthBarUpdatedEvent(); } }")`（`$0`＝接收者，无返回值调用不需要 `$_`）。
- 为什么用 `@SpireInstrumentPatch`：`InstrumentPatchInfo.patchOrdering()=-1`（最先跑，早于 prefix/postfix），补丁方法必须 0 参数且返回 `ExprEditor`（`Patcher.findInstrumentMethod`）。
- `patchedSites` 用**系统属性** `sts1solver.presentationQuiet.patchedSites` 共享：MTS 会用另一个类副本加载补丁类，静态字段不共享（第一次门禁读到 0，而行为断言已通过）。

**门禁 S227（6 条，`probe/Probe.java` 在 S226 之后）**：`patchedSites()==14`；Synthetic seed 9227/80 HP/{Strike_R×2,Defend_R×2,Bash}/JawWorm → `install()` → `p.damage(3)` ⇒ HP 80→77 但 `healthBarAnimTimer` 位级仍 0.0（派发被吞）；直接调 `p.healthBarUpdatedEvent()` ⇒ timer 变 1.2（方法本身没改）；卸窗口后 `quiet()==false`。

**实测**：修前把 TheGuardian 的补丁挂在 `damage(DamageInfo)` 上 ⇒ `S227 patchedSites=12 expected=13` 红；用 `javap -p -c -classpath {proj}/_tmp/gmfull` 逐类 dump（`{proj}/_tmp/javap-sites.txt`）按「`invokevirtual …healthBarUpdatedEvent` 归属上一个方法头」重数，得到真实 14 处（并发现 javap 对自调用不打印 owner，按 `AbstractCreature.healthBarUpdatedEvent` 匹配会漏 9 条）。改正后：`S227 patchedSites=14 expected=14`、`S227 hp=80->77 timerAfterDamage=0.0 timerAfterDirect=1.2`、**`passed=6 failed=0`**。反向验证（`quiet()` 临时改 `return false && World.anyInstalled();`）⇒ `passed=4 failed=2`（`the window reports itself quiet | quiet=false`、`the muted dispatch left the health-bar timer alone | timerAfterDamage=1.2`）⇒ 门禁有牙。全量 core **644/0**（S1–S227，249 条门禁）。jar `941B00FE9F932A21ECAB211A967B2D8110831898BF5EA3EA883804346698D3AB`（1,185,933 B）已装。

**代价**：加载期多 14 处字节码重写（一次性，毫秒级）；运行期每次血量变化多一次静态布尔判断（纳秒级）；没有每帧开销、没有常驻内存（`patchedSites` 是一个 int 与一个系统属性）。

**风险**：①instrument 在 MTS 启动期执行，若游戏升级改了这些方法的签名或调用点，替换会打不到 ⇒ `patchedSites` 对不上 14，S227 直接红并打印数字（不会静默）；②`$0` 回放要求接收者非空（这 14 处都是实例方法调用，成立），若将来出现静态调用会 NPE —— 已在 `editor()` 里只按方法名匹配，静态调用也需改造；③修的是「派发」，不是「方法体」——沙盒里若有人直接调 `healthBarUpdatedEvent()` 仍会写 timer（S227 第 5 条就是钉这个）；④回滚＝删 `PresentationQuiet.java` 与 S227 两处，独立可退。

**③对 mod 的影响**：实机 `quiet()` 恒 false，**行为逐字不变**（雨爱在半血时照常播）；沙盒窗口内其它 mod 挂在 `healthBarUpdatedEvent` 上的表现监听不再被触发（它们本来也不应看到模拟中的血量变化）；面板输出、自动出牌、搜索候选与评分全部不变（没有任何逻辑读 `healthBarAnimTimer`）；其它 mod 兼容性——若某 mod 把**逻辑**写在这个方法上（不是表现），它在沙盒里会看不到事件；当前装的全部 mod 扫描后只有雨爱挂在这里，且只播特效。

**已知边界**：①只静音这一个事件；其它纯表现派发（spine 动画已由 `AnimationMute` 处理、`BobEffect` 已由 B166 拷贝隔离）若再发现同类卡顿，按同一模式加白名单式 instrument；②手打（实机）路径完全没变，所以「以前不卡、现在每局都卡」的解释是搜索/回放对局的节点数变多（B164 的 `PostCombatLoot` 每胜利节点还要泵 `room.update()`），本修把这条放大项去掉，但不改变节点数。
**订正（2026-11-15，B185）**：本节「沙盒窗口里 `YuAiMusic.play()` 根本没跑起来」只对**计划里没有回血步**的局面成立。回血步（香蕉、混沌药水、遗物回血等）会让沙盒真调 `AbstractCreature.heal(int)`，雨爱挂在 `heal(int)` 与 `heal(int, boolean)` 上的两个 postfix 在窗口内照常执行 → `javax.sound.sampled` 另开一条真实音频线，`MusicMute`(B45)/`SfxMute`(B65) 都管不到；症状不是卡帧，而是「后台雨爱声音重复播放」。已由 B185（`mirror/AudioQuiet`，机制 M50）在窗口卸载时回收并 `close` 掉这些 Clip。

## B169 — 雨爱在搜索后假死：静态监听把别的线程的「释放」回填掉了（user m20148）

**现象**：实机打到半血雨爱正常响；搜索/计算结束后音乐不再响（假死），需退出重进才恢复。`solver.log` 21:37 只有 2 条 `WARN static watch: … yuaimod.YuAiMusic#currentClip (object)`（21:37:07.561 首报 + 21:37:17.958 的 5s 限流汇总 `1 write(s)`），不是「每个沙盒窗口写一次」那种数量级。

**核实（用户 m20148 判断「雨爱其实已修好」成立）**：MTS 会把 mod 的 `System.out` 收进 `D:/Steam/steamapps/common/SlayTheSpire/sendToDevs/mts_process_launch.log`（3072 行，**中文是 GBK**，必须 `Get-Content -Encoding Default`）。全文件雨爱事件只有 4 条 + 1 条退出：`:2319` 音频加载完成（8 MB / 197.1 秒）、`:2320` 开始循环播放 `32/71`（21:33:50，实机 `Player Damaged` 之后）、`:2430` 停止播放（21:36:20，relic get 后）、`:2680` 开始循环播放 `34/81`（21:36:52）、`:3061` 已离开游戏（21:37:27）。⇒ **21:37:06–21:37:20 的地精头子搜索、以及 21:39 虱虫搜索期间，一条「开始循环播放」都没有，也没有「启动失败」** ⇒ 沙盒窗口里 `YuAiMusic.play()` 根本没跑起来。B168 的静音面（只挡 `healthBarUpdatedEvent`）因此**不需要**再扩到 `heal`/`updateVolume`。

**根因**：`src/main/java/sts1solver/mirror/StaticWatch.java` 的 `restore()` 只比较「安装时快照值 vs 卸窗口时的值」，不判线程归属。`YuAiMusic.play()` 成功后 `currentClip = clip`；内部类 `YuAiMusic$1` 的 `AudioSystem` `LineListener` 在**音频回调线程**收到 STOP 事件时 `clip.close()` 然后 `currentClip = null`（这是 `currentClip` 唯一不打印的写入者）。沙盒窗口正好开着 ⇒ `beforeUninstall()` 看到 `live == null`、快照非 null，旧 `restore()` 直接把**已关闭的旧 Clip** 塞回去 ⇒ `YuAiMusic.isPlaying()`（`currentClip != null`）永远 true，而 `play()` 首行 `if (currentClip != null) return true;` 永远早退 ⇒ 雨爱此后不再响。

**修法（不特判 mod 类名、不读 digest）**：`StaticWatch.beforeUninstall()` 在 `live == s.staticValue && bodyEquals(live, s)` 的 no-op 判断之后、计数之前插入「释放方向放行」：`live == null && s.staticValue != null` ⇒ `++released`、`Log.warnEvery("static-watch-release-" + w.key, 30000L, "static watch: … was released (set to null) while a window was open; left released. …")`、`continue`（不回填、不报为沙盒写）。回填只保留两个真泄漏方向：「引用被改成别的对象」「容器内容变了」。`report()` 末尾附 `", N release(s) left alone"`；新增 `releasedForTest()`，`resetForTest()` 归零。

**门禁 S228（5 条，`probe/Probe.java` 在 S40 之前）**：窗口内把 `Probe.S228_RELEASED`（初始 `"live"`）置 null ⇒ 卸窗口后仍为 null、且不出现在 `StaticWatch.writtenForTest()`；窗口内把 `Probe.S228_LEAKED` 从 null 塞进 `new Object()`（P2-62 那类真泄漏）⇒ 仍必须回填成 null 且被点名；`releasedForTest()==1`。

**实测**：`tools/build.ps1` BUILD OK（135 源文件）。绿：`tools/run.ps1 -Gates "S228"` ⇒ `S228 released=null leaked=null putBack=true written=[sts1solver.probe.Probe#S228_LEAKED] leftAlone=1`、**passed=5 failed=0**。反向验证（条件临时改 `if (false && …)`，jar `049543074330F26EEC9A233CE89C4B1D4B93001E45697FEEF9AC1A78B119D93A`）⇒ `S228 released=live … leftAlone=0`、**passed=2 failed=3**（三条红＝释放被回填、被误报成沙盒写、计数为 0）⇒ 门禁有牙。修后 jar `BC699BA4755CF8134E886A7BDE347A327B46D78EE28047F8BC5866AFD15C54C6`（1,188,646 B）已装；全量 core **649/0**（S1–S228，250 条门禁）。

**代价**：每窗口多一次判空 + 一次整数自增；无每帧开销、无常驻内存。代码 +约 15 行（`StaticWatch` 12 行 + `releasedForTest` 3 行）、门禁 S228 5 条。

**风险**：①沙盒若真的把实机静态清成 null（而非别的线程），现在不再回填 —— mod 会当作「已释放」重新初始化，通常正是它想要的；②「沙盒把对象塞进实机字段」这条真泄漏方向仍然回填（S228 第 3 条钉住），不会因为这次放宽而漏；③只对「裸引用被置 null」放行，容器/数组仍按内容比较，不会放过容器污染；④回滚＝删该 `if` 分支与 S228，独立可退。

**③对 mod 的影响**：面板输出、自动出牌、搜索候选与评分全部不变（没有任何逻辑读 `StaticWatch.released`）；实机雨爱（以及任何在有异步回调里写自身静态的 mod，如音频/网络/定时器线）不再被回滚成假死；B168 的「沙盒窗口不派发纯表现事件」结论不变 —— 证据表明窗口里 clip 本来就没启动过。已知边界：判据是「快照非 null → 现在 null」，无法区分「回调线程合法释放」与「沙盒真的清空」（后者目前没有已知场景，且清空不会把沙盒对象漏进实机）。

## B170 — 计算时卡帧：静态监听把雨爱的 8 MB 音频数组逐元素装箱（user m20574）

**现象**：用户 m20574 做了控制变量——**只有装雨爱（YuAiBloodMusic）时**，搜索/计算期间画面每隔几帧卡 500–700 ms；不装这个 mod 就不卡。22:22 那场（GremlinNob 精英，Defect，asc20）：`solve: QUICK win nodes=1797 expansions=580 17402ms`，`frame stats (search): frames=59 over=17399ms p50=58ms p95=511ms max=511ms slow33=58 gc=22/2454ms (+0/0ms)`，slow frame 实测 22:22:32.750 = 3477 ms（首帧）、之后 663/712/565/502/694/550/531/671/494/528 ms，每帧 `gfx=real nesting=0 leaks=0`、`gc=+0/0ms`，搜索结束 1 秒内 p50 回到 16–18 ms。每展开 30 ms（对照：18:12 精英 2.3 ms、21:24 三哨位 3.4 ms、21:20 抢劫的 1.5 ms）。

**根因**：`src/main/java/sts1solver/mirror/ModStatics.java` 的 `watchable(Field)` 只按类型名排除 texture/pixmap/sprite/atlas/string，于是 `yuaimod.YuAiMusic#audioData`（`private static byte[]`，整个 8 MB wav、197.1 秒）进了 `StaticWatch` 的候选表。`src/main/java/sts1solver/mirror/StaticWatch.java` 的 `snapshot()` 对任何数组都逐元素 `Array.get(v,i)` 拷进 `Object[n]`（8,000,000 个槽、约 32 MB），`bodyEquals()` 再逐元素 `Array.get` 比较一遍。采样窗口＝`installs <= FIRST_SAMPLES(4)` 与每 `SAMPLE_EVERY(128)` 个窗口；一次搜索有上千次 install ⇒ 十来个采样窗口，每个付两遍 8M 次反射。门禁 S229 修前实测 **snapshot=582 ms、compare=280 ms**（合计 862 ms/采样窗口），正是玩家看到的 500–700 ms 帧。这也解释了「为什么以前没发现」：其它已装 mod 最大的静态数组是 `relicstats.patches.relics.WhiteBeastStatueInfo#chances : double[]` 与 `mapmarker.render.MarkerRenderer#circleVertices : float[]`，都小到看不出来；`mod statics:` 那行只报字段数量、不报大小。

**修法（不改采样策略、不特判 mod 类名、不读 digest 反推）**：`StaticWatch` 新增 `private static final int MAX_ARRAY_ELEMENTS = 4096` 与形状 5「large array」：
- `snapshot()`：数组元素数 > 4096 ⇒ `Object copy = Array.newInstance(c.getComponentType(), n)` + `System.arraycopy(v, 0, copy, 0, n)`（同组件类型、整体复制、不装箱），返回 `Snap(v, 5, copy)`；阈值以下仍是原来的 shape 3。
- `bodyEquals()`：shape 5 ⇒ `sameElements(live, s.body)`，按组件类型分派到 `java.util.Arrays.equals(byte[]/short[]/int[]/long[]/char[]/float[]/double[]/boolean[]/Object[])`。
- `restore()`：shape 5 ⇒ 长度相同就 `System.arraycopy(s.body, 0, live, 0, n)`，长度变了仍只 `Log.warnEvery("static-watch-len-" …)`（与旧行为一致）。
- `shapeName()`：shape 5 = `"large array"`；新增 `shapeForTest(Field)` 给门禁读形状。

**门禁 S229（4 条，`src/main/java/sts1solver/probe/Probe.java` 插在 S40 之前）**：`Probe.S229_BIG = new byte[8_000_000]`，`StaticWatch.watchForTest(f)` 后连续做 3 个窗口（第一窗口包含候选表冷启动，所以只对温窗口设预算）：①形状必须是 `large array`；②在 `[4000000]` 写 42 仍被抓到（`written=[sts1solver.probe.Probe#S229_BIG]`）并在卸窗口时被还原成 0；③温窗口（第 2、3 个）snapshot+compare < 150 ms。

**实测**：`tools/build.ps1` BUILD OK（135 源文件）。修前（未改 StaticWatch，仅加门禁）`tools/run.ps1 -Gates "S229"` ⇒ `S229 shape=array snapshot=582ms compare=280ms element4000000=0 written=[…S229_BIG]`、**passed=2 failed=2**（两条红＝形状不是 large array、总耗时 862 ms 超预算）。修后 ⇒ `S229 shape=large array snapshotMs=[355,0,0] compareMs=[4,1,1] element4000000=0 written=[…S229_BIG]`、**passed=4 failed=0**；355 ms 是 `StaticWatch.all()` 第一次构建候选表（扫 jar + 反射，冷启动）的开销，不是数组拷贝，第 2/3 个窗口证明拷贝+比较已降到 <1 ms + 1 ms。全量 core **653/0**（S1–S229，251 条）。已装 jar sha256 `FDA091268049B0EE2F3BFB90515835E0E7BFAFFCDFD6E7DB7C1FB0AF829ED14E`（1,193,222 B）。

**代价**：每个采样窗口对大数组做一次整体 memcpy（8 MB ≈1 ms，临时内存 8 MB，替代原来的 `Object[8M]` 约 32 MB + 1600 万次反射）；阈值以下的数组完全不变；代码 +约 45 行（`StaticWatch`）+ 门禁 S229 4 条 + 一个测试钩子；无每帧开销。

**风险**：①`Arrays.equals(float[]/double[])` 的语义是「NaN 相等、-0.0 ≠ 0.0」，与旧的 `eq()`（`Float.equals`）一致，不会改变判定；②数组长度变化时仍只 WARN 不还原（与旧行为一致，避免把半个数组写回去）；③`MAX_ARRAY_ELEMENTS=4096` 是成本阈值，不是语义阈值：4097 个元素走整体比较、4096 个走逐元素，两边判定等价；④回滚＝删 shape 5 分支与阈值常量即可，S229 会立刻红。

**③对 mod 的影响**：面板输出、自动出牌、搜索候选/评分/路线、其它 mod 的战斗钩子**全部不变**（没有任何逻辑读这个形状或阈值）；雨爱（以及任何把大数组放静态字段的 mod）不再按「元素数 × 反射」收费；大数组的污染检测仍在（S229 第 2、3 条钉住写入检测与还原）。已知边界：采样频率没变（仍是前 4 个窗口 + 每 128 个窗口），只是每次便宜约 800 倍；`Object[]` 的大数组同样走 shape 5。另：本次只证明并修掉「静态监听的采样成本」这一条；卡顿的结构性根因（帧预算只在两个 microStep 之间检查、单步不可抢占）仍在，见 B170 之前对 `Solver.advance` 的定位。

## B171 — Boss/200HP 战的快搜「见赢即停」：窄档拿到任意胜利就收工（user m20878）

**现象**：打史莱姆 Boss，面板给的最好路线是 -33 HP、只剩 1 血；玩家按同样打法自己走还剩 21 血。用户怀疑出牌顺序固定/漏机制/去重/排序把线删了。日志（`C:/Users/YOKI/sts1-engine-solver/solver.log`，146,615 B / 266 行）：`00:16:20.603 quick search: started, budget=1000000000ms narrow=true ascension=20`，`00:17:21.899 solve: QUICK win splits=2348 deepest=51 hpLoss=33 potions=0 potionHp=0 saves=0 complete=true turns=1 nodes=39815 expansions=12109 61291ms reason=win layers=26 maxBranch=12 deferred=2000 promoted=60 ascension=20`。

**根因（不是漏算路线，是搜索提前收工）**：Boss 房（`com.megacrit.cardcrawl.rooms.MonsterRoomBoss`）或任一怪 `maxHealth >= 200` 时，`Gameplay.quickNarrowDeep()` 为真，实机快搜走 `src/main/java/sts1solver/search/QuickSession.java` 的 narrowDeep 分支：先跑 `new Solver.Session(root, 10, Solver.MAX_SEARCH_LAYERS, remaining(), 0, 0)`；这一档跑完后**只有** `best != null && !best.win && !best.escaped` 才启动唯一一档 `beam=40` 的尾搜，否则立刻 `finished = true`。合并规则同样是单向的：`if (w != null && w.win && (this.best == null || !this.best.win)) this.best = w;` ⇒ 窄档一旦拿到**任意**一条胜利线就收工，宽档即使跑出更好的线也会被丢弃。窄档 beam=10 会把大量前沿停放（deferred=2000/promoted=60），所以 hpLoss 只能看到 beam=10 能看到的那条。

对照：普通怪（narrowDeep=false）走 `Solver.PortfolioSession`——profile {10,28,64,128}、预算份额 35/25/25/15（`Solver.java:680-688`、`:4305`），每档都是完整 Session，用 `Solver.better` 合并，只有 `provenOptimal` 才提前结束 ⇒ 不会见赢即停（但同样有宽度与份额上限）。

**修法（不特判 Boss 名/卡名、不读 digest 反推）**：`QuickSession` 改成「加宽阶梯」：
- 新增常量 `NARROW_BEAM = 10` 与字段 `narrowStarted`/`widenIndex`/`beams`/`wideBeam`/`rungNodes`。
- 窄档跑完先 `if (r != null && Solver.better(r, this.best)) this.best = r;`；若 `proven()`（best 非空且 win 且 provenOptimal）立即 finished。
- 否则按 `widenBeams()` = {28, 64, Limits.beam()} 排序去重（只保留 > NARROW_BEAM）逐档起 tail Session：`new Solver.Session(root, beam, Limits.layers(), remaining(), max(1, rungNodes), 0)`；每档结束同样用 `Solver.better` 合并，proven 则收工，否则返回 false 让下一片继续。
- 没有下一档或 `remaining() < MIN_TAIL_MS(800L)` 时 finished。
- 每档 `maxNodes = rungNodes = max(20000, 窄档实际节点数 × 2)`。实测依据：GremlinNob 合成战 beam=28 一档跑 150 s 用掉 510,269 节点仍未收敛 ⇒ 束宽越大前沿越难跑干，无上限预算若真的「跑到前沿干」会变成永远算不完。所以「无上限」的语义＝不因时间预算砍掉正在跑的搜索、每一档都跑完，但每档工作量与窄档成比例有界。
- `Solver.better(Result, Result)` 由 `private` 改为包内可见（合并用）；`passes()` 改为 public（门禁读）；`phase()` 宽档文案改为「加宽搜 beam=N（第 k/总 档）」。

**门禁**（`src/main/java/sts1solver/probe/Probe.java`，插在 S40 之前）：
- **S230**(3)「an unlimited budget keeps widening after the first win (B171)」：Synthetic seed 5230、maxHealth 80、ascension 0、deck 5×Strike_R + 4×Defend_R、monsters {GremlinNob}（窄档赢下来会掉血 ⇒ 不是 zero-damage，不 proven）；`QuickSession(root, DeepBudget.UNLIMITED, true)`，deadline 300 s；断言 ①finished 且 win ②`passes() >= 2` ③`hpLoss` 不劣于单独跑 beam=10。
- **S231**(2)「a proven zero-damage win ends the ladder after one pass」：同 S175 夹具（seed 5231、+60 Strength 首击秒杀）⇒ 断言 win + provenOptimal 且 `passes() == 1`。

**实测**：`tools/build.ps1` BUILD OK（135 源文件）。夹具订正过程（三次真机失败）：①最初用 JawWorm + 5 Strike/4 Defend，窄档竟能零伤赢（`S230 quick: finished=true passes=1 win=true hpLoss=0 proven=true`、`passed=4 failed=1`，失败断言=`passes=1`）⇒ 这恰好证明门禁对「见赢即停」是有牙的（旧行为必红）；②改 maxHealth=300 + GremlinNob 后一档跑不完（`finished=false passes=2 nodes=613433`）⇒ 引出上面的 maxNodes 设计；③定稿后 `tools/run.ps1 -Gates "S230,S231"` ⇒ **passed=5 failed=0**：`S230 quick: finished=true passes=4 win=true hpLoss=35 proven=false nodes=251041`（窄档 + 28/64/96 三档）、`S230 narrow only: win=true hpLoss=35 nodes=35863`、`S231 quick: finished=true passes=1 win=true proven=true`。全量 core **658/0**（S1–S231，253 条）。已装 jar sha256 `BF8D6E5CCE9DBFB22A0E6ED544F6B41A44841FCD0BDC903A512E038AA18D07E3`（1,198,578 B）。

**代价**：①时间——最坏情况下 Boss 战要跑 1 个窄档 + 3 个加宽档，每档节点上限 2× 窄档 ⇒ 无上限预算时总时长可达原来的 1+3 倍量级（仍受 `remaining()` 与 `MIN_TAIL_MS` 约束）；有上限预算时每档预算从同一 `remaining()` 里扣，总时长不变，只是分配方式从「窄档吃到饱」变成「窄档 + 加宽档分」。②内存——每档结束后旧 Session 释放，峰值与单档相同。③代码 +约 40 行（QuickSession）+ 门禁 5 条；`Solver.better` 只是放宽可见性，无新逻辑。④每档是**重搜**而不是增量续跑（不共享 frontier），这是有意的简单选择。

**风险**：①无上限预算下 Boss 战会明显变久（玩家可随时暂停/停止，`stopRequested` 仍生效；预算设上限时不会）。②`maxNodes = 2× 窄档节点` 是经验值：若某场窄档极小（如 1 步秒杀），加宽档也只有 20000 节点下限，可能仍跑不完前沿 ⇒ 那档会被截断而不是报错（`Session` 到 maxNodes 正常收尾）。③`passes()` 语义从「窄档+40 档」变成「阶梯档数」，门禁 S230 依赖它。④回滚＝把 `advance()` 的 narrowDeep 分支恢复成「窄档见赢即 finished + 单档 beam=40」，S230 立刻红。

**③对 mod 的影响**：面板——phase 文案宽档从「宽搜收尾 beam=40」变成「加宽搜 beam=N（第 k/3 档）」，其余行不变；自动出牌——计划可能变成更好的线（更少 hpLoss），回放/执行逻辑完全不变；搜索——Boss/200HP 战更深更宽，普通怪路径（PortfolioSession）一行未改；其它 mod——无接触点（纯搜索策略）。已知边界：普通怪路径的分支/份额上限、以及「无上限 = 一直跑」在束宽大到前沿跑不干时的时长仍有界，这两条都写在上面「代价/风险」里。

## B172 — 普通怪搜索最宽档硬编码 128，比 Boss 阶梯的 Limits.beam() 还窄（user m21118）

**现象**：B171 之后 Boss/≥200HP 战按 `Limits.beam()` 逐档加宽（high=96、unlimited=256），但普通怪的 `Solver.PortfolioSession` 四个 profile 的最后一个是字面量 `{128, layers, 0}`（`Solver.portfolioProfiles()`）。在 `limits=unlimited` 档位下，普通怪最宽只到 128，比 Boss 阶梯的最宽档（256）窄一半；玩家把面板设成同档位时，两套路径的搜索宽度不一致。

**根因**：`portfolioProfiles()` 的最后一档写死 128（历史值，早于 `Limits.beam()` 引入），既没有跟随档位，也没有跟 Boss 阶梯共用同一来源。另外 `PortfolioSession` 用 `private static final int[][] PROFILES = Solver.portfolioProfiles();` 在类初始化时快照整张表 ⇒ 即使表本身改成动态的，切片路径也只在第一次类加载时读一次。

**修法（不改搜索算法、不改份额、不特判怪）**：
- `Solver.portfolioProfiles()`：`int top = Math.max(128, Limits.beam());`，第四档用 `top`。上限语义＝永不窄于历史 128、永不窄于当前档位；每次调用都读，所以改 settings 后下一次 solve 就生效。
- `Solver.PortfolioSession`：删掉类初始化快照的 `PROFILES`，`advance()` 在起下一档时现读 `Solver.portfolioProfiles()`（`phase()` 里的档数同样现读）；新增 `public static int topBeamForTest()`。
- `Limits`：新增测试钩子 `forceBeamForTest(Integer)` / `beamForTest()`（与 `forcePotionsForTest` 同风格，只影响门禁，不动 settings.txt）。

**门禁 S232（4 条，`src/main/java/sts1solver/probe/Probe.java` 插在 S40 之前）**：把 beam 档位钉成 96 / 256 / 45，分别断言「high 档最后一档仍是 128」「unlimited 档最后一档是 256 且 `PortfolioSession.topBeamForTest()` 也是 256」「low 档不会把 128 反向缩水」「10/28/64 三档不变」。

**实测**：`tools/build.ps1` BUILD OK（135 源文件）。修后 `tools/run.ps1 -Gates "S232"` ⇒ **passed=4 failed=0**，日志 `S232 highTop=128 wideTop=256 sessionTop=256 lowTop=128`；jar sha256 `F379B5B12ED4A9E6CB306DF395B4417330602588D0E5CC4F0CCBAAB4D378416B`（1,200,195 B；反向验证后改回同一源码重建，最终安装的是 `F3E4C1F8944F29BB5CD9630F96DDF858E7232A4B9AE7E7FF9FC22DF240E6D9E9`，1,200,195 B，jar 内时间戳不同所以 sha 不同）。反向验证：把 `int top = Math.max(128, Limits.beam())` 临时改成 `int top = 128`，重建安装（sha `758E6BEE…C27FC`，1,200,191 B）后同一门禁 ⇒ **passed=3 failed=1**，红的是「an unlimited beam widens the last profile, and the session reads it | wideTop=128 sessionTop=128」⇒ 门禁有牙。随后改回并重建安装。

**代价**：一次 solve 多读几次 settings（`Limits` 每秒缓存一次，等于一次 map 查表）；unlimited 档位下普通怪最后一档从 128 变成 256，束宽翻倍 ⇒ 该档每层展开的候选更多、耗时上升（受预算与份额 15% 约束，仍会到点收尾）；high/low 档（含默认 high=96）**完全不变**（`max(128, 96)=128`）。代码 +约 15 行 + 门禁 4 条。

**风险**：①`limits=unlimited` 且预算不限时，普通怪最后那一档会比以前更久（前沿更难跑干），玩家可暂停/停止；②`PortfolioSession` 从「类初始化快照」改成「每次现读」意味着运行中改 settings 会立刻影响下一档（以前要重启），这是期望行为但属于行为变化；③`topBeamForTest()` 只是读取现表，没别的语义；④回滚＝把 `top` 写回 128 并恢复 `PROFILES` 字段，S232 立刻红。

**③对 mod 的影响**：面板——`phase()` 的「member k/4」档数不变，只是最后一档的 beam 数可能变大（面板不显示 beam 数，行为不变）；自动出牌、回放、评分、digest 全不变；搜索——只在 `limits=unlimited` 时普通怪最宽档变宽（默认 high 档一行不变）；其它 mod 无接触点。已知边界：普通怪仍用固定份额 35/25/25/15（未改成 B171 的逐档 `remaining()` 阶梯，用户 m21118 只要这一处）。

## B173 — 面板「预计掉血」与跨档合并用两把尺子（user m21390）

**现象**：第二档搜索里，当前最佳节点报的掉血是十几 HP，但同一档后面又出现了扣血更多的节点——「最佳」的掉血数字会变大。用户原话（m21213 第 2 条）：「第二档搜索我发现问题，本来当前最佳节点扣血十几但是同一档后面显示了扣血更多的节点。你看最佳节点判定有没有问题」。m21390：「你先照着A改完，我再测试第一个是偶然电脑卡了还是mod的问题」⇒ 本轮只做这一条，第 1 条（第三档卡在 2 万多世界线）等他实测。

**根因（两把尺子，三处数字不一致）**：
- 档内 best：`Solver.Session.expandAndRecord` / 阻塞 `solveInternal` 用 `Solver.score(...)`，里面已经含成长抵扣（`GrowthBook.credit`）、战利品（`Solver.lootPoints(choice.loot)`）、战后回血（`Solver.healPoints(choice.postHeal)`）与金币（`goldDelta * goldRate()`）。
- 跨档合并：`Solver.better` → `Solver.compareRoutes` 只算 `max(0, hpLoss - max(0, postHeal)) + potionHp`，逐字不含成长抵扣与战利品。
- 面板：`Session.bestHpLoss()` 直接返回 `this.best.hpLoss`（原始值），`hpPreviewText` 也只打印它。
⇒ 同一个「最佳」在三处是三个数；档内 best 可以因为「拿战利品更划算」而让原始掉血上升，面板却只会照着原始值变大。**这不是 best 判定算错，而是对外报告的号码没统一。**

**修法（只统一对外报告的号码，不换排序）**：
- `Solver` 新增 `static int netHpLoss(hpLoss, growthHp, postHeal, potionHp) = max(0, max(0,hpLoss) - growthHp - max(0,postHeal)) + max(0,potionHp)`、`static int netOf(Result)`（`netHpLoss>=0` 直接用，否则回落旧口径）、以及门禁用 `netHpLossForTest/netOfForTest/betterForTest/resultWithLossForTest`。**第一版曾有第 5 个参数 `lootHp`（战利品按 2000 分/HP 折算抵扣），已按 user m21803 删除**，见下节「订正」。
- `Node.netHpLoss`（初值 -1）与 `Result.netHpLoss`（初值 -1）：两套搜索循环各自在落点赋值——切片 `Session.expandAndRecord`（含 root=0）、阻塞 `solveInternal` 的 node4/续线/promote（含 root=0）与「最深线替换」分支、`Session.finish()`（`bl ? n : this.best.netHpLoss`）。**两套循环必须同改**，否则切片与阻塞会报两个不同的最佳。
- `Solver.better` 改用 `Solver.netOf` 作 deficit（`postHeal/potionHp` 传 0，保持 `compareRoutes` 的判序不重复实现）。
- 面板：`QuickSession.previewNetHpLoss()` / `PortfolioSession.previewNetHpLoss()` / `Session.bestNetHpLoss()`；`Gameplay` 新增 `previewNetHpLoss` 字段（`clearPreview()` 重置），`hpPreviewText(int netHp, int rawHp)` 打净掉血，原始值更大时追加「（原始 N，成长/回血抵扣 D）」，剩余 HP 也按净值算。

**第一次全量 659/10 的教训（重要，已回退）**：第一版把**档内 best 的判据**也换成 `compareRoutes(net)`。结果 `score` 里的棋盘项（怪剩余 HP×20、活怪×400、block×2、层数）全丢：一条「0 伤害、0 回合、立刻结束」的短线和一条深线净掉血都是 0，`compareRoutes` 先比回合数（少者胜）⇒ 选中最短的空线，最深线/烟雾弹逃跑线/战后回血线全部被它顶掉（S 段 10 条红：`the reported line reaches the end of the battle finished=false steps=48`、`the headline still calls it the longest survival line 未找到胜线…`、`with a spare energy the search plays it instead of killing first`、`the search reports the smoke bomb line as an escape, not a win` 及其连带 3 条、S230）。**结论：A 方案只能换「对外报告的号码」，不能换「排序」**——档内 best 与 beam 排序维持 `Solver.score`，`Solver.netHpLoss` 只喂 `better` 与面板。

**门禁 S233（9 条，`src/main/java/sts1solver/probe/Probe.java`，插在 S232 之后）**：①尺子数学（`net(12,3,0,0)=9`、`net(12,0,6,0)=6`、`net(0,5,0,0)=0`、`net(4,0,10,2)=2`、`net(6,0,0,0)=6`——最后这条就是 m21803 的「掉 6 血不许被 6 血奖励抵成 0」）；①b（m21803）`resultWithLossForTest(6)` 挂一张 `worth=18000 分`（9 HP）的富奖励屏，`netOf` 仍须返回 6；②两条真实胜线的 `Result.netHpLoss>=0` 且 `== Solver.netOf(result)`；③`betterForTest(a,b)` 在 `netA=1 < netB=5` 时为真、反向为假；④切片 `Session.advance(1L)` 逐帧采样：`win` 不变时 `bestNetHpLoss()` 不上升，且 `result.netHpLoss == bestNetHpLoss()`。
**实测**：`tools/build.ps1` BUILD OK（135 源文件）。第一次只跑 S233 得 `passed=6 failed=1`，失败断言 `a finished line carries its own net HP | netA=-1 netB=-1` ⇒ 暴露「阻塞搜索是第二套独立实现、不经过 `Session.finish()`」，补完 `solveInternal` 四处赋值后 `7/0`（`S233 ruler: growth=9 loot=7 heal=6 noBank=0 potion=2`）。反向验证：`netHpLoss` 退回 `Math.max(0,hpLoss)+Math.max(0,potionHp)` ⇒ `passed=5 failed=2`（两红＝growth/loot/heal 与 noBank/potion 两条尺子断言）。**订正后（user m21803）**：`9/0`，日志 `S233 ruler: growth=9 heal=6 noBank=0 potion=2 sixHpWithLoot=6`、`S233 loot: worth=18000 points netWithLoot=6 rawLoss=6`；反向验证（`netOf` 回落式再减 2，模拟「奖励又抵血」）⇒ `passed=8 failed=1`，红的是 `netWithLoot=4` ⇒ 新断言有牙。

**结果**：全量 core **671/0**（S1–S233，258 条；B173 前是 662/0、S1–S232）；已装 jar sha256 `4938545B75C8647E594BBB5FFE712E80796FFFB463343A79209C85432936BE32`（1,204,585 B；首版含战利品抵扣的 `292C7123…4971DB` 已作废）。**顺带**：S230 的墙钟截止从 300 s 放宽到 900 s——同一台机器上节点数完全不变（narrow 35863、wide 各 71726），只是当天整机慢 2–4 倍（同 jar 的 35863 节点从 11.5 s 变成 44–48 s；玩家实测面板速率从 1100–1400 条/s 掉到 691–771 条/s，CPU 负载 21–40%、无残留 java 进程），判据仍是 `finished + passes>=2 + win`。

**代价**：每次展开多一次减法与一个 int 字段（无分配、无每帧开销）；`better` 每次合并多一次 `netOf` 调用（每档一次）；面板多一个 int 字段与一段文案。代码 +约 60 行 + 门禁 9 条。

**风险**：①档内 best 仍按 `score` ⇒ 非胜线的净掉血仍可能被棋盘项以 <1 HP 的幅度破同分上升（S233 只钉「面板数字不劣于 `hpLoss+potion`」与「最终 `Result` 与 best 一致」）；②跨档合并改用净值后，两条同胜线会按「净掉血少者」选，可能比以前更保血（m21803 之后跨档合并**根本不再考虑战利品**，奖励只在 `Solver.score` 里影响档内排序）；③`Result.netHpLoss` 对「最深线替换」分支固定取替换后的 `hpLoss`（无 heal/loot 可言），这是有意为之；④回滚＝删 `netHpLoss` 字段与 `beatsBest`-类调用（本轮已无）、`better` 恢复旧算式、面板回 `bestHpLoss`，S233 立刻红。

**③对 mod 的影响**：面板——「预计 -N HP」改为净掉血，原始值更大时多一段「（原始 N，成长/回血抵扣 D）」；自动出牌/回放/digest/beam 排序完全不变（排序仍是 `score`）；搜索分支数与预算不变；评级：跨档合并可能选更保血的线。其它 mod 无接触点。

**订正（user m21803，2026-11-10 12:32）**：用户否决「战利品抵扣」——「不要显示战利品抵扣，因为就算首先金币是固定的如果都算的话没必要一般。其次怕你搞错，把战利品抵扣和扣血加起来比如以为一局掉6血但是加相当于6血的金币这一个局面是6-6=0扣血等效0所以是最优解，这种错误情况不能出现」。①`Solver.lootHp` 删除，`netHpLoss` 从 5 参改 4 参（`hpLoss/growthHp/postHeal/potionHp`），签名里再也传不进战利品；②`netOf` 回落式与四处赋值点全部去掉 loot；③面板文案改「成长/回血抵扣」；④新增门禁断言 `netHpLossForTest(6,0,0,0)==6` 与「富奖励屏（worth=18000 分=9 HP）挂到掉 6 血的 Result 上，`netOf` 仍返回 6」；⑤补充 `Solver.resultWithLossForTest(int)` 测试工厂（`Result.hpLoss` 是 final，门禁没法直接改）。奖励仍只在 `Solver.score` 里参与**档内排位**（B164），不再参与任何对外报数。**定案（user m21969）**：胜线之间**仍然**按 `score` 排位（「掉血多 2 点但战利品多值 3 点的胜线取代上一条、面板净掉血随之变大」是预期行为，不改成按净掉血排胜线）；本次只订正了 `Solver.java:4051-4056` 那段与代码不符的注释。**代价**：少一个参数、少一次折算，无开销。**风险**：跨档合并不再偏好「多拿战利品」的线（这是用户明确要求）；「6 血换 6 血奖励」这类局面现在必然报 6 血。**③影响**：面板不再出现「战利品抵扣」字样；搜索排序、自动出牌、回放、digest 不变。

**已知边界**：①净掉血只对「报告 + 跨档合并」生效；档内排序仍有棋盘项；②S230 的 900 s 是负载安全网，不是判据（见上）；③第 1 条「第三档卡在 2 万多世界线」不在本修范围，等玩家实测的新 `solver.log`。


## B174 — 面板无意义推荐喝先古药水 / 药水代价在面板上不可见（user m21993·m22084）

**现象**：Defect 31/71、asc20，打地精帮（GremlinThief 12/12、GremlinFat 16/16、GremlinWizard 25/25、GremlinFat 17/17），腰带里一瓶**先古药水**（Ancient Potion＝1 层人工制品）。面板路线第 15 步是「先古药水」，结果行 `solve: QUICK win … hpLoss=22 potions=1 potionHp=9`；用户自己按同样顺序打、**一口不喝**照样过，两次都是这个结果。用户原话（m21993）：「明明不喝先古药水和喝没区别为什么面板提示喝药水。我自己打了一遍完全是一样的，白白浪费。2.是不是药水那里做的不对，喝药水没有算代价」；（m22084）：「我自己已经确定不喝药水可以打过了，不需要再确认。你自己检查逻辑。」

**本轮实测（全部是改代码之前先做的取证，不是推断）**：
- **药水代价确实进了排名用的净值**。门禁 **S236**（S97 同场景：seed 7071、全 Defend_R、AcidSlime_S、Fire Potion）断言「不喝就打不死」的那条胜线满足 `Result.netHpLoss == max(0, hpLoss - max(0, postHeal)) + max(0, potionHpSpent)`，实测 `S236 net: win=true hpLoss=0 postHeal=0 potionHp=9 net=9 expect=9`。⇒ 日志里的 potionHp=9 不是装饰（它由 B53 从计划重数），但搜索排名用的 score/netHpLoss 里那 9 HP 也确实扣了。
- **沙盒里喝药真的会清空槽位**。门禁 **S237** 在安装后的沙盒里调用 Driver.usePotion(0,-1)：`S237 price: before=9 after=0 drunk=true slot=PotionSlot`。若不清空，startPotionHp - potionHp 恒为 0，药水就成免费——不是这个原因。
- **合成扫描复现不出**。门禁 **S235** 用用户这场战斗的配置（Defect、31/71、asc20、地精帮、先古药水）跑 10 个种子：`S235 sweep: lines=10 drank=0 judged=0 worst=none`，10 场全部获胜（hpLoss 0–7）且**一次都没喝**。
- **这瓶药在这套对手模型里就值 0 HP**。门禁 **S238** 用上限 320 血、两只 GremlinFat（每回合各挂 1 层虚弱，asc17+ 再各挂 1 层脆弱）逐回合对照「不喝」与「喝先古药水」：两行**每回合血量完全相同**（t2 300 → t3 280 → t4 230 → t5 180 → t6 130 → t7 80 → t8 30），差别只有虚弱永久少 1 层（plain 虚弱2,3,4… 对 potion 虚弱1,2,3…）；而虚弱/脆弱的减伤幅度固定 25%（层数只决定持续回合），所以这 1 层不改变任何伤害数字。断言写死成两条：an Artifact does not change HP when nothing blocks、an Artifact leaves the player one layer of Weak behind。

**面板侧的真缺陷（本轮修）**：search/PlanText.java 的「预算内最好：掉血 22，用药 1 瓶」只打印**原始** r.hpLoss，药水价 9 一个字都不显示（B173 只把顶部「预计 -N HP」改成净值），读起来就像药水免费。修法：抽出 PlanText.costText(int hpLoss, int potionsUsed, int potionHpSpent)，有用药且价非 0 时追加「（价值 9 HP，合计 31）」，无药或无价时不追加。门禁 **S239**：`S239 paid=[掉血 22，用药 1 瓶（价值 9 HP，合计 31）] plain=[无伤] unpriced=[掉血 5，用药 1 瓶]`。

**仍未定案（如实记录，不猜、不静默）**：这次搜索是**中途被停下的**（reason=stopped、面板「还有 48 条没展开（受层数/预算限制，可以再算）」、solve wall clock: 79469ms (sliced)），「不喝药」的兄弟线就在那 48 条未展开里；面板给的「预算内最好」是这个截断搜索**已经找到**的最好线，不是这场战斗真正的最优线。已装只读探针 Solver.potionCounterfactual（任何花药水的计划在 Session.finish() 收尾时打一行 `potion counterfactual (B174): potions=1 plan=… stripped=… replayOk=… with{…} without{…} firstDiff=@k …`），用**同一条计划去掉药水步重放**来指出药水到底改变了哪一步；门禁 **S234**(5) 钉住它的格式（无药线必须为空、有药线必须 replayOk=true 且带 firstDiff=）。

**门禁**：S234(5)/S235(2)/S236(2)/S237(3)/S238(2)/S239(3)，全部绿（合计 17 条断言）。**结果**：核心套件 **688/0**（S1–S239，263 条门禁）；已装 jar sha256 **6ABB3A0A1325192068FBE94E631C58E571DE06560E479D402083DB3D50362C11**（1,219,570 B）。

**代价**：面板多一个静态方法（costText，只在收尾拼一次字符串，0 每帧开销）；探针只在**计划里真的花了药水**时跑一次「去掉药水重放」，普通局 0 成本，花药水那局多一次重放（毫秒级）；新增门禁让核心套件多约 30 s（S235 的十种子扫描最贵，约 40 s）。

**风险**：①costText 只是文案，不改任何数字或决策，回滚＝把那一行改回 "掉血 " + hpLoss（S239 立刻红）；②探针用 ThreadLocal 保存/还原 REPLAY_FAILED_*，若将来有人在重放里嵌套调用它，需要再看一次三个 ThreadLocal 的还原（当前只有收尾一处调用）；③S238 的对照依赖「GremlinFat 每回合挂 1 层虚弱、asc17+ 挂脆弱」这一引擎事实，游戏版本改了这条就会红——那正是它该红的时候。

**对 mod 的影响**：面板多显示「（价值 N HP，合计 M）」，与自己算的净掉血口径一致；自动出牌/计划/搜索分支/排名/预算**一律不变**（本轮没有改任何决策路径）；其它 mod 无交互（只碰自有的 PlanText 与 Solver 探针）。

**已知边界**：探针只写日志，不改搜索——「无用药水要不要自动从计划里删掉」是一个**会改变计划**的决策，未获用户批准，未做。

**补测（user m22566「现在你复刻刚刚的局面会不会去喝药」）**：门禁 **S240** 把用户那场的开局原样搭出来（Defect 31/71、asc20、手牌 AscendersBane/Defend_B/Apotheosis+/Hologram+、遗物 Cracked Core/Ancient Tea Set/Cables、地精帮四只、槽 0 先古药水、能量 2；抽牌堆顺序日志里没有，所以是「同开局不同洗牌」），每个种子跑两种预算：
- seed 9251（不喝就能赢的牌序）：budget=1500 → `win=true hpLoss=4 potions=0`；budget=8000 → `win=true hpLoss=4 potions=0`——**两次都不喝**。
- seed 9250（怎么算都凑不出胜线的牌序）：budget=1500 → `win=false hpLoss=15 potions=1`；budget=8000 → 同样 `win=false … potions=1`——**喝了，而且喝完还是没赢**。
⇒ 结论：搜索**只在「不喝就没有胜线」时才喝**；只要不喝能赢，它一口都不喝（与 S235 十种子一致）。用户那场面板给的是「可赢（用药 1 瓶）」，说明那次搜索在它跑过的层里只找到带药水的胜线，而它中途被停（还剩 48 条未展开）。这条也说明：把「无用药水自动从计划里删掉」做成策略之前，先得有反事实那一行日志（探针已装）。

## B175 — 面板把「成长抵扣」当成血，真掉 3 点显示「预计无伤」（user m22661）

**现象**：asc20 Defect 打 Looter（拆迁的），solver.log `15:50:33.146 solve: QUICK win splits=0 deepest=19 hpLoss=3 ... reason=win`，trace `4:t=1->2 hp=30`（33→30），实机也确实掉了 3 点；但面板那一行写的是「预计无伤」。用户判断：「可能是把金币这些计算最佳路线的判定和无伤无药水判定弄混了，以为无伤其实只有相应的金币掉落」。

**根因（与用户判断一致）**：面板的「预计 -N HP / 预计无伤」来自 `Gameplay.hpPreviewText(previewNetHpLoss, previewHpLoss)`，而 `previewNetHpLoss` 是排名用的净值 `Solver.Result.netHpLoss`。净值 = `max(0,hpLoss) - max(0,growthHp) - max(0,postHeal) + max(0,potionHp)`，其中 `growthHp = GrowthBook.credit(...)`，而成长预算把「升级过的牌」按 `Lesson Learned` 默认 50 tenths = **5 HP/张** 计价（`GrowthBook.DEFAULT_TENTHS = {30,20,5,50,10,10}`）。这一局第 9 步「神化」把整副牌升级（9 张）⇒ 成长抵扣 45 HP ⇒ `net = max(0, 3-45) = 0` ⇒ 面板判成「无伤」。S241 用固定预算实测：九张升级 = **45 HP** 抵扣，足以抹掉 3 点掉血。

注：金币在 B146 之后已经**不进** `GrowthBook.credit`，而是走 `Solver.score` 的 `GOLD_POINTS`(80/枚)；这次被抹掉的是成长抵扣（升级牌的估价）。`netHpLoss` 本身仍是合法的排名值，问题只在**面板把它当成「要掉的血」**。

**修法**：`Gameplay.hpPreviewText` 里数字与「无伤」二字一律取**原始掉血** `rawHp`（`shown = rawHp >= 0 ? rawHp : max(0, netHp)`）；成长/回血抵扣只作为注记「（成长/回血抵扣 X 不计入掉血）」。`Solver.netHpLoss`、`netOf`、`Solver.better` 的跨档合并一律不动 —— 排名继续用净值。

**门禁 S241**(6 条)：①净 0 原始 3 ⇒ 不含「无伤」且含 `-3 HP`；②净 0 原始 0 ⇒ 仍写「无伤」；③净 2 原始 3（回血抵扣 1）⇒ 也不许抹掉 3；④两个数字都没有 ⇒ 输出空串；⑤固定预算下九张升级的 credit = 45 HP。实测 `S241 credited= · 预计 -3 HP（成长/回血抵扣 3 不计入掉血） | clean= · 预计无伤 | healed= · 预计 -3 HP（成长/回血抵扣 1 不计入掉血） | unknown=[]`、`S241 growth credit for nine upgrades=45` ⇒ **6/0**。

**反向验证（门禁有牙）**：把 `shown` 改回 `Math.max(0, netHp)`（旧行为）→ 重建/安装 → `AUTOTEST RESULT passed=3 failed=3`，三条红行是 `a credited line still prints the HP it really loses | · 预计无伤`、`and it prints three, not zero | · 预计无伤`、`a heal credit does not hide the loss either | · 预计 -2 HP`。

**结果**：全量 core **695/0**（S1–S241，265 条，104 extended skipped）；安装 jar sha256 `2F96945955E0447C5902E09D6854346FC2D028BA72F43E6B7DCB81CF6F1FA29E`（1,224,037 B）。

**①代价**：零运行期开销（只改一条字符串分支，不是每帧/每节点路径）；新增 1 个 `public static` 测试钩子 `Gameplay.hpPreviewTextForTest`。

**②风险**：面板数字会比 B173 之后**变大**（回到原始掉血），可能让「最佳」看起来更贵——这是有意的（m21803：掉 6 血换 6 血战利品也必须显示 6）；成长抵扣仍影响 `netHpLoss`（排名/跨档合并），只是不再被当成血展示；回滚 = 把 `shown` 改回 `max(0, netHp)`，S241 立刻变红。

**③对 mod 的影响**：面板文案与数字变了；搜索、档内排序（`Solver.score`）、跨档合并（`netOf`）、自动出牌、回放校验、digest、计划内容**全都不变**；其它 mod 无交互。

**已知边界**：①净值为 0 但原始掉血 >0 时，注记里会出现「成长/回血抵扣 N 不计入掉血」，其中 N 是两者的合计，没有分开列（避免再出现两个数字并列造成新误解）；②`postHeal`（战后回血）仍算「真血」，会降低面板净值，但它现在也不改数字、只出现在注记里；③「净掉血」这个量仍被跨档合并使用，所以面板数字变大的同时，跨档挑线的规则没变（有意保留，见 B173）。

## B176 — 同一局面的「中途相位」被当成两条世界线（user m22848/m22904）

**现象**：同一位置在窄档（beam=10）与加宽档（beam=28/64/…）各被展开一次；用户判断「第一档出现的解法第二档还会出现，但是他们局面是一样的」，并问「是不是指纹没做好局面重复」。

**根因**：判重键是 `Digest.ofCurrent()` 的**原串**，而它的第一段是 `t=<回合> ph=<GameActionManager.phase> q=<actions>/<cardQueue>/<monsterQueue> use=<usingCard> end=<turnHasEnded>`（`mirror/Digest.java:34-46`）。同一局面只要一次在动作队列**未结算**时采样、另一次在结算后采样，两串就不同 ⇒ 转置表 `this.seen`（`Solver.java` 切片路径）与层内 `this.next` 都把它当两条世界线各展开一次。这**不产生错答案**（两条线各自的展开仍正确），只浪费算力，并让同一解法在两档各出现一次。

**修法**（只去重「纯相位/队列」差异，不碰候选枚举与 RNG）：
1. `drive/Driver.java` 新增 `public static boolean settled()`（队列空 + currentAction 完成 + 未 usingCard）与诊断用的 `public static String settleBlock()`（返回 `turn-ended`/`action-busy`/`selection`/`screen`/`limbo`/`ending-turn`/`error` 之一）。
2. `Solver` 新增 `static String positionKey(String digest, boolean settled)`：**引擎已静止**时取 `Digest.stable(digest)`（去掉 `ph/q/use/end`），未静止时保留原串（否则会把半截状态合并，那才是真错）。`null` → `""`。
3. `Choice.settled`/`Choice.positionKey` 在 `readChoice`（`choice.digest = Digest.ofCurrent()` 之后、同一世界已安装）里记录；`Node.positionKey`、`Deferred.positionKey`（构造器加一个参数）跟着设；`this.seen.add(...)` 与层内 `this.next.get/put(...)` 全部改用访问键。
4. 计数与观测：每趟结束打一行 `position keys (B176): nodes=… unsettled=… mergedByPhase=…`；未结算（保留原串）的前 5 次打 `position key kept raw (B176): <settleBlock()>`（每进程上限 5 条，防日志爆炸）。
5. 测试钩子：`positionKeyForTest`、`nodesForTest`、`unsettledNodesForTest`、`mergedByPhaseForTest`。

**门禁**：
- **S242**(4)：结算局面两帧（只差 `ph/q/use/end`）必须同键且键里不含 `ph=/q=/use=/end=`；未结算局面必须保留原串（含 `ph=EXECUTING_ACTIONS`）；`null` 也有键；规范键不允许与原串相撞（前缀 `|`，原串以 `t=` 开头）。
- **S243**(2)：真实切片 run（`Solver.Session.advance`）里 `nodes>0` 且 `unsettled==0`（即正常推进的节点都能拿到规范键）。
- 实测 `S242 settled=[|t=3 hp=70/80 b=0 e=3 st=- pw=- rl=- or=- H=Strike_R D=Defend_R X= E=] rawBusy=[t=3 ph=EXECUTING_ACTIONS q=3/1/0 use=1 end=0 | P hp=70/80 b=0 e=3 …]`、`S243 nodes=12 unsettled=0 mergedByPhase=1 steps=1` ⇒ **6/0**。
- **反向验证**：把 `positionKey` 改成恒用 `Digest.stable`（忽略 settled）⇒ `passed=2 failed=2`，红行正是「未结算局面必须保留原串」与「规范键不允许与原串相撞」。

**结果**：全量 core **701/0**（S1–S243，266 条，104 extended skipped）；`position keys (B176)` 在真实/门禁搜索里 `mergedByPhase` 多为正（实测 nodes=736 merged=91、nodes=418 merged=44，单场最多见过 9184），`unsettled` 每场只有 0–21 个 ⇒ 这条去重在真实搜索里确实在起作用。安装 jar sha256 `7C6444E7075E7C1E90F5A0EBBFBD2793377A4185B05CD29452939C909D7D2FEB`（1,228,383 B）。

**①代价**：每个节点多一次 `Driver.settled()`（几个字段读）+ 一个字符串（`Digest.stable` 的 4 次 `replaceAll`），相对一次展开（毫秒级引擎动作）可忽略；`seen`/`next` 的键串比原来略短（去掉了相位），内存不升反微降。

**②风险**：①如果某条路径在「引擎其实没静止」时被误判为静止，会把半截状态与完整状态合并 ⇒ 用 `settleBlock()` 的七种原因把判定写死（空队列 + currentAction 完成 + !usingCard），并靠 S242 的「未结算保留原串」钉住；②合并会改变**候选枚举顺序带来的 beam 名额分配**（同一层里被合并的节点不再占一格），因此结果可能与 B176 之前不同——这是有意的（重复局面本来就不该占名额），但要用 core 全量确认没有反向退化；③回滚 = 删 `positionKey`（键退回原串），S242/S243 立刻红。

**③对 mod 的影响**：面板多一行 `position keys` 观测（只在日志，不在 UI）；自动出牌、回放校验、digest 内容、计划格式、跨档合并规则、评分公式都不变；其它 mod 无交互。

**已知边界**：①它只解决**同一趟（同一 Session）内**的相位重复；窄档 → 加宽档的**跨档重算**仍在（每个加宽档都是新 `Session`、从根重建，`seen`/`next` 是实例字段，零跨档记忆）；②`Digest.stable` 只剥 `ph/q/use/end` 四个字段，若将来发现别的「纯表现」字段混进 digest，要一并加进去；③未结算采样仍按原串判重，不合并（宁可多算一次，也不冒合并半截状态的风险）。


## B177 — 每个加宽档都从同一个根重建 Session，窄档跑过的层被整层重算（user m23168「做c」）

- **现象**：同一场战斗里窄档（beam=10）跑完，后面每个加宽档又从同一局面重新开始；日志 10:18 那局窄档 39815 节点、第二档 79166 节点（上限 `max(20000, 39815×2)=79630`），几乎全部花在重算窄档已经跑过的层。面板上「已查阅 N 条世界线」在每一档开始时重新积累，但总节点数并没有换来更多不同的局面。
- **根因**：`search/QuickSession.java` 每加宽一档就 `new Solver.Session(root, wideBeam, …)`，从同一个根重建 Session；`seen`（跨层转置表）与层内 `next`（去重表）都是 Session 实例字段 ⇒ **档间零记忆**。窄档通常跑到前沿耗尽（frontier dry）才收工，宽档于是把前几层原样再展开一遍。同一局面的候选枚举与 RNG fork 都是状态的函数，重算必然得到完全相同的子节点与相同顺序 ⇒ 这部分计算不产生任何新信息；宽档唯一的新信息来源是「同一层保留集合更宽」。
- **修法（B177，会话内加宽）**：
  - `Solver.Session`：`beamWidth`/`maxNodes`/`nodeBudget` 去掉 `final`；新增 `int[] rungBeams; int rung; int rungCapNodes; int nodesAtRungStart; int promotedThisRung; int widenedAtLayer = -1; int expansionsAtWiden;`。
  - `advance()` 末尾：`if (this.done) { if (!this.widenToNextRung()) { this.finish(); } }`。
  - `widenToNextRung()`：`stopRequested`/`provenOptimal && best.win`/预算耗尽/阶梯走完 ⇒ 返回 false；否则保留层号、前沿、`seen`、`next`、deferred 停车区，只把 `beamWidth` 换成下一档、`nodeBudget = nodes + cap`（`cap` 在第一次加宽时按 `max(20000, 本档节点×2)` 定死，后续档复用，防止指数膨胀，B171 规则）、重置本档 promotion 配额（`promotedThisRung = 0`，总量 `promoted` 保留）、`done = false`、`frontierDead = false`、重算 `layerAllowance`；日志 `widening in place (B177): rung=k/n beam=… layer=… nodes=… cap=+… parked=…`。
  - `promoteOne()` 的配额比较改用 `promotedThisRung`（提示语「到本档的上限」）；
  - `QuickSession`：删掉 `narrow`/`wide`/`rungNodes`/`widenIndex`/`wideBeam`/`MIN_TAIL_MS`/`proven()`，只建一个 `Solver.Session` 并 `planWidening(widenBeams())`；`passes() = rung()+1`；`phase()` 用 `rung()/rungs()/beamWidth()`。
  - 新增测试钩子：`Session.planWidening/rung/rungs/beamWidth/passNodes/widenedAtLayerForTest/promotedForTest/expansionsAtWidenForTest/deferredCountForTest`。
- **门禁 S244（5 条，`probe/Probe.java`）**：seed 9245、80HP、asc0、`Defend_R×5`（打不死 ⇒ 窄档不可能 `provenOptimal`，只会耗尽前沿，正好是必须加宽的时机）、JawWorm；`new Solver.Session(root, 10, Limits.layers(), 12000L, 0, 0)` + `planWidening(new int[]{20, 40})`，循环 `advance(20L)` 至完成。实测 `S244 finished=true rungs=3 rung=2 beam=40 widenedAt=27 expansions=1082 atWiden=888 promoted=66 parked=230 reason=no-live-branches`，日志 `widening in place (B177): rung=2/3 beam=20 layer=17 nodes=1535 cap=+20000 parked=222`、`… rung=3/3 beam=40 layer=27 nodes=1823 cap=+20000 parked=230`，**passed=5 failed=0**。断言：①阶梯真的加宽过；②两档按序在同一个 Session 里跑完（`rung()==2 && beamWidth()==40`）；③首次加宽的层号 ≥1（不是从根重来）；④加宽时窄档已展开过（`atWiden>0`）；⑤更宽的档把窄档停车的线提了回来（`promoted>0`）。
- **数字说明**：S244 每次运行的 `widenedAt`/`expansions`/`promoted` 会在小范围内浮动（不同次分别测得 `widenedAt=27/26/8`、`promoted=66/49/50`），断言只看「是否加宽、是否按序、层号≥1、atWiden>0、promoted>0」，不钉具体数值。
- **门禁 S245（3 条，接线钉）**：S244 是直接 `new Solver.Session` 自己调 `planWidening`，它只能证明 Session 会加宽，证明不了 `QuickSession` 真的接线 —— 实测把 `QuickSession` 的 `planWidening` 调用注释掉后 S244 仍然 5/0。因此补一条驱动真实 `QuickSession` 的门禁：seed 9246、`Defend_R×5` + JawWorm、`new QuickSession(root, 12000L, true)` 跑完，断言 ①完成 ②`rungsForTest()>1`（会话拿到了阶梯）③`rungForTest()>=1`（阶梯真的推进过）。实测 `S245 finished=true passes=4 rungs=4 rung=3 nodes=3663`，**passed=3 failed=0**。反向验证（注释 `QuickSession` 的 `planWidening` 调用重建安装）⇒ `passed=6 failed=2`，两条红＝`the quick search gave its session a widening ladder | rungs=1`、`and the ladder actually advanced past the narrow rung | rung=0 passes=1`，而 S244 仍绿（两道门禁各钉一层：S244 钉 Session 机制、S245 钉接线）。
- **回滚判据的订正**：本文先前写「回滚＝不调 `planWidening`，S244 立刻红」是错的（S244 自建 Session，不受 `QuickSession` 影响）；实际是 **S245 红**。
- **门禁首版踩坑（记下来避免重犯）**：最初用「能赢的牌组」（Strike×3 + Bash）跑，窄档当场 `provenOptimal` 收工、根本不会加宽 ⇒ 5 条全红。换成打不死的牌组后变绿；这是真实加宽路径，不是特判。
- **第二版 off-by-one（构建期发现）**：`widenToNextRung()` 里写成 `rungBeams[rung + 1]` ⇒ 直接跳过 beam=28 升到 64（日志 `rung=2/3 beam=64`）。改成 `rungBeams[rung]`、`rungs() = rungBeams.length + 1`，并把 cap 固定为窄档测得的 `rungCapNodes`。
- **回归实测**：`S230 quick: finished=true passes=4 win=true hpLoss=35 proven=false nodes=251041` 与旧实现逐字相同（数值相同是 cap 算术巧合，不是同一份逻辑）；`S231 quick: finished=true passes=1 win=true proven=true`；`S230,S231,S232,S233,S243` 一起跑 **passed=20 failed=0**。
- **全量**：`tools/run.ps1 -TimeoutSeconds 2400` ⇒ **passed=709 failed=0**（S1–S245，268 条，104 extended skipped）。已装 jar sha256 **57DC8A8E3C28690C0A49DE3E7B212A738B7968431CB6233D4797E381EB85CDAA**（1,233,118 B）。
- **① 代价**：每节点零新增开销（只是判重键不再受档位影响）；每档切换只做一次状态重置 + 一行日志；窄档跑过的层不再重算 ⇒ 总节点数与耗时下降；每档仍有 `cap` 上限，不会无限膨胀。
- **② 风险**：①加宽只在同层继续、不重搜前几层 ⇒ 早层被窄 beam 丢掉的线只能靠 deferred 停车提回，deferred 被逐出时可能找不到「旧实现重搜一遍能找到」的更优线（结果可能变化，但不会出现错误局面）；②`provenOptimal` 早停保留（窄档证明最优就不加宽）；③回滚＝`QuickSession` 不调 `planWidening` 即回单档，S245 立刻红。
- **③ 对 mod 的影响**：面板「第 k/n 档」「本档」计数改由同一个 Session 提供（语义不变）；自动出牌 / 回放 / digest / 评分 / 计划格式都不变；其它 mod 无交互（不新增镜像、不新增补丁）。

## B178 — B176 的汇总日志写在「每个重复节点」分支里（user m23534）

- **现象**：用户 m23534 问「最新的日志有没有效率更好」「为什么我还是看到4档给出一个解法」。查 C:/Users/YOKI/sts1-engine-solver/logs/solver-20261110-191322.log（13,248 行 / 1.30 MB，19:09 启动）：**12,731 行（96%）是 **`position keys (B176)…`**，峰值 280 行/秒**；同一场战斗四档给出同一条解法。
- **根因 1（日志）**：B176 的汇总行被插在 `Solver.java` 的「已见过 ⇒ 跳过」分支里（原 :4292，在 `++this.nodeIndex;` 之前）⇒ 每跳过一个重复节点写一行；`util/Log.java` 的 `write()` 是 `synchronized` 且每行 `out.flush()` ⇒ 渲染线程上逐行磁盘 I/O。这与「日志不能写在每节点循环里」的既有约定冲突。
- **根因 2（诊断无效）**：`position key kept raw (B176): ` 后面的原因是在**写日志时**才调 `Driver.settleBlock()`，此时引擎已经 drain 完 ⇒ 恒为空串（本局 5 条全空），用户无法判断为什么会退回原始键。
- **根因 3（「4 档同一解法」不是指纹问题）**：B177 的单 Session 逐档加宽只覆盖 `Gameplay.quickNarrowDeep()`（`MonsterRoomBoss` 或最大血量 ≥200）；**普通战斗仍走 **`Solver.PortfolioSession`** 的四个 profile**（beam `{10,28,64,max(128,Limits.beam())}`，Solver.java:680-692），每个成员 `new Session(this.root, …)`（:4891）⇒ `seen`、层内 `next` 都是新实例的字段，同一棵树从根重算；第 1 档找到的最优线自然在每档再被找到，所以面板/日志每次都显示同一条。**这不是「同一局面的中途相位被当成两条世界线」**（那部分 B176 已修：同一 Session 内 8% 的命中被 `mergedByPhase` 合并）。
- **本场实测数字**（19:10:28 起，普通战斗，narrow=false）：四档分别 12,614 / 23,884 / 126,158 / 14,942 节点（合计 177,598），总 163 s；发布的最优线来自**第 1 档**（13 s，`nodes=12757`），后三档 15 万节点 / 150 s **没有改进**。四档节点份额与 `PORTFOLIO_SHARES={35,25,25,15}` 一致（第 3 档 beam=64 吃了 35%≈116 s）。
- **修法（只做日志/诊断，搜索行为不变）**：①汇总行移到 `Session.finish()` 每趟一行（`position keys (B176): nodes=… unsettled=… mergedByPhase=… skipped=…`），跳过分支只计数；②`Choice` 新增 `settleWhy`，在 `readChoice` 采样 `Driver.settled()` 的同一刻快照 `Driver.settleBlock()`，诊断行改打 `choice.settleWhy`。
- **门禁**：无新增（纯日志/诊断改动，现有 S242/S243 覆盖 positionKey 语义）；S242/S243 **6/0**，全量 **core 709/0**（S1–S245，268 条，104 extended skipped）不变。
- **已装 jar**：sha256 `FF10482E55384948FCC2B8620778ABEAB9F7D399ACFC3C19E06A2B96F95F8E71`（1,233,172 B）。
- **① 代价**：热路径**减少**每节点一次字符串拼接 + 一次 synchronized flush（本局 1.1 MB 日志、12,731 次 flush 消失）；每趟多一行日志；`Choice` 多一个字符串字段（引用，空串共享）。
- **② 风险**：①汇总数字从「实时」变「每趟一次」，搜索中途看不到 mergedByPhase 增长（只有结束时一行；加宽行仍实时）；②诊断原因现在来自采样时刻，若引擎在同一节点内被再次推进，日志描述的是采样点而非写日志点（这才是本意）；③回滚＝把两处日志位置改回 + 删 `settleWhy`，无门禁会红（纯日志）。
- **③ 对 mod 的影响**：面板、自动出牌、回放、digest、评分、计划、搜索结果**全不变**；日志体积与渲染线程 I/O 大幅下降；其它 mod 无交互。
- **已知边界 / 待批准**：普通战斗的四档 portfolio 仍各自从根重算（本场 15 万节点 / 150 s 的无用功）。方案 D＝把四档也收进同一个 `Session` 逐档加宽（`planWidening({28,64,top})`），并用累计时间份额 35/25/25/15 给每档设 deadline，保留「弱档不许吃掉全部预算」的原意。**未获批准，未实施**；代价约 1 天、会改变 beam 名额分配与搜索结果，需新增门禁（阶梯在单 Session 内、每档 deadline 生效、第 2 档不回根）。
## B179 — 普通战斗的四档各建 Session 重算同一棵树 + 日志只报「胜出那一档」的数字（user m23836）

- **用户原话（m23836）**：「都改，显示和普通战斗」——批准 B178 上报的方案 D（把普通战斗的 portfolio 四档收进单 Session 逐档加宽）与显示口径（本档 + 合计）。
- **现象**：19:13 那场普通战斗（非 Boss、无 200+ 血怪）日志 `solve: QUICK win splits=0 deepest=51 hpLoss=2 potions=0 nodes=12757 expansions=6865 13484ms reason=win`，但按 `position keys (B176)` 的 nodes 归零分段看，四档实际是 12,614 / 23,884 / 126,158 / 14,942 节点、合计 177,598 节点 / 163 s（`solve wall clock: 163575ms (sliced)`）；最优线（掉血 2）是**第 1 档 13 s** 就拿到的那条，后三档 15 万节点没有任何改进，也都给出同一解法。全日志 `widening in place` **0 行** ⇒ B177 的会话内加宽根本没触发。
- **根因（两条，互不相关）**：
  1. **普通战斗没走加宽**：`Gameplay.quickNarrowDeep()`（Gameplay.java:658-673）只在 `MonsterRoomBoss` 或最大血量 ≥200 时返回 true；普通战斗走 `Solver.PortfolioSession`，而 `Solver.solvePortfolioInternal` 对 `portfolioProfiles()` 的四个 profile（beam {10,28,64,max(128,Limits.beam())}）各 `new Session(root, …)`，`seen`（转置表）与层内 `next` 都是 Session 的实例字段 ⇒ 档间零记忆，每档从同一个根重建整棵树（同一局面的候选枚举与 RNG fork 都由状态决定，重算得到完全相同的子节点与相同顺序，零新信息）。
  2. **显示口径分裂**：`Result.summary()` 打的是 `nodes`/`expansions`，在 portfolio 下它只是「最终胜出那一档自己的数字」；面板（`Gameplay.worldlineText`）打的却是 `job.nodes()` 四档合计 ⇒ 同一次搜索两个数，日志与面板对不上。
- **修法（不改算法、不加特判、不读 digest 反推）**：
  1. `Solver.Session.planWidening(int[] beams)` 增加重载 `planWidening(int[] beams, long[] deadlinesMs, int[] nodeCaps)`：`rungDeadlines`（每档累计墙钟 deadline，相对 `startedAt`）、`rungNodeCaps`（每个加宽档的节点上限）都 clone；新增 `private long budgetNow()`＝当前档的 deadline（null/≤0 回落 `budgetMs`）；`microStep`、`promoteOne`、`parkedCapFor`、A3 promote 拒绝日志、`budgetLeftMs` 全部改读 `budgetNow()`；`widenToNextRung()` 开头仍按**总预算** `budgetMs` 判断（总预算耗尽就不再加宽）；`widenToNextRung()` 的节点 cap 优先用 `rungNodeCaps[rung]`（`max(200, …)`），否则回落 B171 的 `max(20000, (nodes-nodesAtRungStart)*2)`。
  2. `solvePortfolioInternal` 重写成单 Session：`rungBeams(profiles)` + `rungDeadlines(profiles.length, budgetMs)`（按 `PORTFOLIO_SHARES={35,25,25,15}` 累加），`new Session(world, profiles[0][0], profiles[0][1], budgetMs, 0, profiles[0][2])` + `planWidening(beams, deadlines, null)`，循环 `advance(max(4L, deadline-now))`，超时 `requestStop()`；删掉按成员 `solveInternal` 的循环与 `best==null` 的 `solve(16,48)` 兜底。
  3. `Solver.PortfolioSession` 整个类改成「一个 Session 的外壳」：字段 `root/budgetMs/maxNodes/current/best/stopRequested/finished`，`advance()` 首次按 `SHARES` 算 `deadlines` 与（`maxNodes>0` 时）`nodeCaps` 并 `planWidening(beams, deadlines, caps)`，日志 `portfolio ladder (B179): beams=… deadlinesMs=… nodes=…`；对外方法 `nodes()/passNodes()/expansions()/passes()/rungsForTest()/rungForTest()/beamForTest()` 与预览转发到 `current`。
  4. `QuickSession.passes()` 的 portfolio 分支改成 `return this.portfolio.passes();`，`stampTotals()` 的 `totalExpansions` 加上 `portfolio.expansions()`。
  5. `Solver.Result` 新增 `passNodes/passExpansions`（`Result.nodes`/`expansions` 是 final，构造时把本档值传进 n4/n5，`Session.finish()` 再补 `totalNodes/totalExpansions/passes=rung+1`）；`summary()` 同时输出 `nodes=<本档> totalNodes=<合计> expansions=<本档> totalExpansions=<合计>`，原有 token 全保留（S230/S231 等解析不受影响）。
- **门禁 S246（6 条，`probe/Probe.java`，插在 S245 之后）**：`S246 the ordinary fight's portfolio runs one widening ladder (B179)`。场景＝`Synthetic` seed 9247、maxHealth 80、ascension 0、deck `Defend_R×5`（打不死 ⇒ 只能靠加宽把前沿推进下去，不会 `provenOptimal` 提前收工）、JawWorm、`Driver.drain(4000,800L)`、`World root=World.captureLive()`；`new Solver.PortfolioSession(root, 12000L, 0)`，`while(!job.finished() && now<started+120000L) job.advance(20L)`。断言：①`finished` ②`rungsForTest()==4` ③`rungForTest()>=1`（真加宽过）④`beamForTest()>10` ⑤`r.totalNodes==job.nodes() && r.passNodes<=r.totalNodes`（合计与外壳一致、本档不大于合计）⑥`elapsed<=15000L`（每档 deadline 生效、不超 12 s 总预算太多）。
- **实测**：单跑 `AUTOTEST RESULT passed=6 failed=0`，日志 `S246 finished=true rungs=4 rung=3 beam=128 nodes=4111 pass=245 total=4111 ms=2586`；全量里同一门禁 `S246 finished=true rungs=4 rung=3 beam=128 nodes=4123 pass=245 total=4123 ms=1938`。**反向验证**：把 `this.current.planWidening(beams, deadlines, caps);` 注释掉 → `passed=3 failed=3`，红行 `S246 finished=true rungs=1 rung=0 beam=10 nodes=3513 pass=3513 total=3513 ms=1424`（rungs/rung/beam 三条红，`total==nodes` 与 `elapsed` 仍绿）⇒ 门禁有牙。恢复后重装。
- **结果**：core **713/0**（S1–S246；门禁总数 268 条 = core 164 + extended 104，日志证据 `only [S246] asked for, 267 gate(s) skipped` ⇒ 1+267=268）。已装 jar sha256 **9D189521EAD01511B270209B75D27B03EDA74BC77CE8F39115EE063F7DF8B379**（1,236,016 B）。反向验证用的临时 jar＝`0C76390D4E9F5A52B242FFEE87059D4E0392115840C45342C947D6A11D43F59F`（作废）。
- **计数订正（不静默）**：预期 709+6=715 条断言，实测 **713**，少 2 条。已核对：S246 固定 6 条；S67（Probe.java:3400-3475）6 条全是固定 `Probe.check`；`Probe.passed` 就是 check 数（`Probe.java:78`）。差 2 条未能逐条归因（最可能是某些门禁的断言数随运行态浮动，如循环里按怪物/药水/屏数决定的断言），**门禁全绿、failed=0**。同时订正旧基线记录：B178 之前写「S1–S245，268 条」多算 1，实际当时 267 条＝core 163 + extended 104（`only [S242, S243] asked for, 265 gate(s) skipped` ⇒ 2+265=267）。
- **①代价**：每档切换多一次数组查表（`budgetNow()`）与一个 `int`；`Result` 多两个 `int`；`PortfolioSession.advance()` 首次多算 4 个 deadline/节点上限（O(1)）。热路径**净减少**：不再有后三档的整树重算（B178 那场 177,598 节点 / 163 s，其中 15 万节点是重算）。
- **②风险**：①结果可能变化——单 Session 后 beam 名额按「每层更宽的保留集合」分配，与「四档各跑一遍」不逐位等价（不保证一定更优或更差，门禁只钉拓扑与预算）；②弱档吃满自己的 deadline 就加不宽（以前靠节点台阶 `max(20000, 上档×2)`，现在是 35/25/25/15 的累计墙钟，机器慢时弱档可能提前结束）；③`Result.nodes` 语义变成「最后一档的工作量」（合计在 `totalNodes`），任何只读 `nodes` 的外部脚本会看到更小的数；④回滚＝`PortfolioSession.advance()` 不调 `planWidening` / `solvePortfolioInternal` 还原按成员循环，S246 会立刻红 3 条。
- **③对 mod 的影响**：面板数字（`nodes` 是全量、`（本档 M）` 是当前档）语义不变但更准；自动出牌、回放、digest、评分、候选枚举全不变；普通战斗耗时预期大降（重算消失），Boss/200HP 战路径本来就是单 Session（B177）不受影响；其它 mod 无交互。
- **已知边界**：`totalNodes`/`passNodes` 两栏必须一起读；暂停时间由 `compensatePause` 补进预算，不补 deadline；deep（阻塞 `Solver.solve`）路径同样走 `solvePortfolioInternal` ⇒ 一次性深搜现在也是单 Session 阶梯。

## B180 — 时间不限的末档仍被节点预算截断 + 面板不说是哪个限制（user m24116 / m24185）

- **用户原话**：m24116「最新日志为什么我没有设置时间节点限制而且没提前暂停搜索但是还是显示有169条未展开」；m24157「你觉得有必要做A吗，为什么11张牌打小怪的局会有这么多世界线？…现在也没有药水也没有抽牌，每回合5张牌4费，为什么会有十多万世界线」；m24185「做AC，反正开销不大」。
- **现象**：20:43 那场普通战斗（Defect 打小怪，无药水）日志 `portfolio ladder (B179): beams=[28, 64, 128] deadlinesMs=[350000000, 600000000, 850000000, 1000000000] nodes=0`（时间不限），四档 `nodes=12,757 / 25,514 / 25,514 / 25,514`（合计 89,299），收尾 `solve: QUICK win deepest=52 hpLoss=2 … nodes=25514 totalNodes=89299 … reason=nodes`，面板写「还有 169 条没展开（受层数/预算限制，可以再算）」。玩家没设限制、也没暂停，却看到「还有 169 条」，读成搜索仍在跑。
- **根因（三条）**：
  1. **每档节点额度不是玩家设的限制**：`Solver.Session.widenToNextRung()` 给每个加宽档 `cap = max(20000, (nodes - nodesAtRungStart) * 2)`（B171），B179 的 portfolio 又按份额给 `rungNodeCaps`；末档 +25,514（=窄档 12,757×2）。四档合计到 89,299 就用尽 ⇒ `microStep()` 置 `reason="nodes"`、`done=true`，`finish()` 算 `cutShort=!frontierDead`、`frontierLeft=169`。169 是**节点预算的余数**，不是战斗还剩多少世界线（同一场理论空间约 31^6≈8.9 亿，按 1,372 节点/s 要 7 天；见交付答复的估算）。
  2. **面板不区分停止原因**：`PlanText.candidateNote(boolean cutShort, int open)` 对任何 `reason` 都输出「受层数/预算限制，可以再算」，时间用完、节点用完、层数到顶、手动停止全一样，玩家无法判断「再算一次」是否会更好。
  3. **停车候选被静默**：`PlanText.parkedNote` 只在 `frontierExhausted && promoted == 0 && deferred > 0` 时输出，而这局 `promoted=121` ⇒ 2,000 条被挤掉的线一条都不显示。
- **修法（不改搜索排序、不加特判）**：
  1. **A（末档去节点上限）**：`Solver.Session.widenToNextRung()` 在算 cap 前加 `boolean lastRung = this.rung == this.rungBeams.length - 1;` 与 `boolean noCap = lastRung && sts1solver.ui.DeepBudget.unlimited(this.budgetMs);`（`DeepBudget.UNLIMITED = 1000000000L`，即设置里时间预算「不限」）。`noCap` 时 `cap=0`、`this.nodeBudget=0`、`this.maxNodes=0`；中途加宽的 `layerAllowance` 改成 `noCap ? Integer.MAX_VALUE : max(64, left/layersLeft)`（否则无上限档会继承窄档的每层 64 次展开额度）。日志打 `cap=+unlimited`。Session 仍受 `maxLayer`、`provenOptimal`、前沿干、停止按钮约束 —— 只是不再有「第 4 档跑满 25,514 就停」这一条。
  2. **C（面板说清限制 + 显示停车候选）**：`PlanText` 新增 `candidateNote(boolean cutShort, int open, String reason, int deferred, int promoted)` 与 `limitText(String reason)`（nodes→「节点预算用完」、time→「时间预算用完」、layers→「到层数上限」、stopped→「手动停止」、其它/null→「受层数/预算限制」）；只有 nodes/time/stopped/未知才追加「，可以再算」（层数到顶时重算撞同一堵墙）。当 `deferred > 0 && promoted > 0`（`parkedNote` 被抑制的情形）追加「；另有 D 条被挤掉的线停着（本档回放 P 条）」。`headline(Result)` 改传 `r.reason, r.deferred, r.promoted`；旧 2 参重载保留原文案给旧门禁。
  3. **门禁 S153 的订正（非产品改动，必须记下）**：全量首跑 **721/1**，红的是 B174 时代就有的 `S153`：`with belt hpLoss=14 potions=0 win=true | without hpLoss=11`，两次都 `reason=time`。S153 自己的注释写着「belt 的行动集更大，同样墙钟预算买到的深度更少，这是预算效应不是药水效应」，但断言 `conclusive || !withBelt.win || withBelt.hpLoss <= noBelt.hpLoss` 在 `conclusive=false` 时仍会判 dominance（与注释相反）。改成 `!conclusive || …`（只在两边都真停下时才比），单跑 `passed=2 failed=0`、全量 722/0。**没有改任何产品判据**，只是让门禁遵守它自己声明的边界。
- **门禁（新增 S247/S248，`probe/Probe.java`，插在 S246 之后）**：
  - **S247(3)**「an unlimited job gives its last rung no node cap」：seed 9248、80HP、Defend_R×5、JawWorm；同一 root 上开两个 `Solver.Session(root, 10, 6, budget, 0, 0)`，都 `planWidening(new int[]{20, 40})`，分别用 `DeepBudget.UNLIMITED` 与 12000ms，循环 `advance(20L)` 到 `rung()==2` 再读 `maxNodesForTest()`。实测 `S247 unlimitedCap=0 rung=2 finiteCap=20053 finiteRung=2` ⇒ `passed=3 failed=0`。
  - **S248(6)**「the open-frontier tail names the limit that stopped the search」：`candidateNote(true,169,"nodes",2000,121)` 必须含 169 与「节点预算用完」且含 2000 与「另有」；`candidateNote(true,12,"layers",0,0)` 含「层数」且不含「可以再算」；`candidateNote(false,…)` 为空；旧 `candidateNote(true,120)` 仍含「受层数/预算限制」与「可以再算」；`limitText("time")` 含「时间」。实测 `S248 nodes=[ 还有 169 条没展开（节点预算用完，可以再算）；另有 2000 条被挤掉的线停着（本档回放 121 条）] layers=[ 还有 12 条没展开（到层数上限）] legacy=[ 还有 120 条没展开（受层数/预算限制，可以再算）]` ⇒ `passed=6 failed=0`。
  - **反向验证**：把 `noCap` 临时改成 `false && …` ⇒ jar `442AD8FE…431876`，`tools/run.ps1 -Gates "S247"` 得 `passed=2 failed=1`，日志 `S247 unlimitedCap=20053 rung=2 finiteCap=20053`（红在「末档不带节点上限」）；恢复后重装，`S247,S248` 合跑 `passed=9 failed=0`。
- **结果**：全量 core **722/0**（S1–S248，104 extended skipped；门禁总数 270 条＝旧 268+2）。已装 jar sha256 **9DDCBBD60C23D59DEADB876F8B28CCA1731DE000B19793B58F55A039ACFF6DE4**（1,240,061 B）。作废中间版：`A2CC09AE…C526E72`（仅 B180，未含 S153 订正）、`442AD8FE…431876`（反向验证）、`6849FF20F57E6FE2356FBF12DC3F24258BA8098DCFFAF743E4D50AF9B8F0436B`（含 S153 订正前最后一次 S247/S248 合跑）。
- **① 代价**：`widenToNextRung()` 每档多两次布尔判断与一次 `DeepBudget.unlimited` 比较（每档一次，非热路径）；面板每次刷新多一次字符串拼接（`limitText` 分支）。**运行期风险成本是时间**：不限预算下末档不再有节点上限，可胜战斗通常很快（找到胜线后 `provenOptimal`/前沿干即收），但打不死的战斗会一路跑到 `maxLayer`（high=400），节点数、`seen` 集合内存与耗时都可能数分钟级上涨。
- **② 风险**：①末档无上限后，同一场战斗的结果可能与「四档各 25,514 节点」不同（更多世界线被展开，可能找到更优或更差？——只会不差于原末档，因为它是同一前沿的继续）；②`seen` 无上限增长，长时间搜索的内存占用上升（没有新的上限兜底，只有停止按钮/maxLayer）；③面板文案改动只影响显示（`candidateNote` 2 参重载保留），但如果外部脚本解析面板文本需要适配「节点预算用完」等新词；④回滚＝把 `noCap` 写死 false + 删 S247，或删 `candidateNote` 新重载并让 headline 回 2 参（S248 会红）。
- **③ 对 mod 的影响**：面板：新增「节点预算用完/时间预算用完/到层数上限/手动停止」措辞与停车候选数，数字语义不变（`nodes` 仍是本档、`totalNodes` 仍是合计）。自动出牌/回放/digest/评分/候选枚举：不变（没有任何决策读这个 cap，A 只让搜索在末档继续展开）。其它 mod：无交互。
- **已知边界**：A 只对「时间预算不限」生效（`budgetMs >= 1000000000`）；有限时间预算下末档仍有节点上限（S247 的 finiteCap=20053 钉住），因为此时节点上限本来就是时间预算的近似保护。`nodes` 数是**生成位置数**，`expansions` 才是真展开数（本场 89,299 vs 48,272，差额约 4.1 万＝被位置指纹合并/未展开，约 46% 重复，B176 的判重在工作）。

## B181 — 沙盒帧长跨过动作的时间窗：Hexaghost 地狱火少 3 张灼伤，第 10 回合手牌对不上（user m24423 / m24488 / m24529）

- **用户原话**：m24423「看最新日志，找原因不要改代码，告诉我为什么第十回合对不上牌」；m24488「你的意思是不是六火这个回合两个意图升级灼烧和塞灼烧沙盒只执行了一个导致了错误，修的话要怎么修，影响什么的说一下」；m24529「改」。
- **现象**：`solver.log` 22:11:37.431 步 39「结束回合」t9→10，`step plays` 里 live 与 sandbox 的血量/能量/能力/怪物意图/RNG 计数器全部相同，只有牌堆多重集不同：沙盒 H(5)+D(12)=17 张、其中 3 张 Burn+；实机 H(5)+D(15)=20 张、其中 5 张 Burn+ ⇒ **实机正好多 3 张 Burn+**。随后 22:11:37/43/48/55 连续 `step 39/40/41/42 came out differently than predicted` + `autopilot stopped: 第 N 步和计划不一样（局面已偏离）`，22:11:55.983 起 5 次 `execute: card uuid … (防御) is no longer in hand; refusing instead of playing a neighbour`，22:12:01 `world switched` 清状态。
- **根因（引擎动作 + 沙盒时钟）**：
  1. Hexaghost 的**一个**意图（地狱火，move 6）里只排了**一条** `BurnIncreaseAction`（`_tmp/saveload/com/megacrit/cardcrawl/monsters/exordium/Hexaghost.java:192-203`），不是两个意图。
  2. `com/megacrit/cardcrawl/actions/unique/BurnIncreaseAction.update()` 分**两段**：`if (this.duration == 3.0F)` 升级 `player.discardPile.group` 与 `player.drawPile.group` 里所有 Burn；`if (this.duration < 1.5F && !this.gotBurned)` 才 `gotBurned = true` 并 new 3 个 Burn、逐个 `upgrade()`、`AbstractDungeon.effectList.add(new ShowCardAndAddToDiscardEffect(card))`；末尾才 `tickDuration()`（先判分支再减 delta，duration 初值 3.0f）。
  3. 沙盒一帧是 `Driver.FAST_DELTA = 20.0f` ⇒ 第一帧就把 3.0 打到 -17 并 isDone，第二段（`< 1.5`）**永远不会被看到** ⇒ 只升级、不新建 ⇒ 少 3 张 Burn+。实机 60 fps 会走过 3.0→…→<1.5（日志 22:11:30–34 实机弃牌堆先原地升级、后追加 3 张 Burn+ 与此一致）。
  4. 干扰项已排除：`ShowCardAndAddToDiscardEffect(AbstractCard)` 的构造器里就 `player.discardPile.addToTop(card)`，所以 `EffectSettle` 把它当「纯表现」丢弃（22:07:11.312 `effect dropped without terminal state … (audited: presentation only)`）**不是**这 3 张的原因。
- **修法（不动 simulate 逻辑、只统一帧长）**：
  1. `{proj}/src/main/java/sts1solver/drive/Driver.java`：`private static float simDelta = SELECTION_DELTA;`（原 `FAST_DELTA`），字段上方长注释写明 B181 与两段时间窗；`FAST_DELTA` 注释改成「只留作 A/B 测量（`-Dsts1solver.cardDelta=fast`）」；类注释里的帧长说明同步（`0.05 s per sandbox frame since B181`）。
  2. `Driver.endTurn(...)`：`boolean smallEndTurn = endTurnSmallFramesForTest != null ? endTurnSmallFramesForTest.booleanValue() : true;` + `if (smallEndTurn && needsSelectionDeltaAtEndOfTurn()) ++endTurnSlowFrames;` + `simDelta = smallEndTurn ? SELECTION_DELTA : FAST_DELTA;` ⇒ 生产默认小帧，`setEndTurnSmallFramesForTest(FALSE)` 重新等于 pre-B181 大帧（S133/S134 的 A/B 分支恢复）。`needsSelectionDeltaAtEndOfTurn()` 现在只喂诊断计数。
  3. `{proj}/src/main/java/sts1solver/probe/Probe.java` `runStep(...)` 的 finally 清掉 5 个测试钩子（`Limits.forcePotionsForTest` / `forceBeamForTest` / `Driver.setScreenDeltaForTest` / `setFastDeltaForTest` / `setEndTurnSmallFramesForTest`）——门禁不得把自己钉的开关留给下一个门禁。
  4. 四个药水门禁（S95/S100/S186/S236）开头自己钉 `Limits.forcePotionsForTest(true)`：玩家 `settings.txt` 第 25 行 `search.potions=ban` 会让 `Legal.actions()` 的药水循环整段不执行，之前那 9 条红是**玩家策略设置**、与 B181 无关（诊断跑 `S95 legal: offered=0 … potionsOffered=false size=3 usable1=true` 已证实）。
  5. S133 旧分支断言放宽：B181 之后旧 bug 的形态从「牌蒸发」变成「牌进弃牌堆」（`inHand=false discarded=true exhausted=false`），两种都算「没保留」，改断言 `inHand=false && exhausted=false`（这才是该行钉的性质）。
- **门禁 S249(3)**「a staged action gets its later time window too」：合成战斗（seed 9249、80 HP、JawWorm）→ `World.captureLive()`+`install()`+`Driver.begin()` → 清空沙盒两堆、放 3 张 `Burn`（2 弃牌堆 + 1 抽牌堆）→ `addToBottom(new BurnIncreaseAction())` → `Driver.drain(4000, 800L)` → 断言：起点 3 张；**原有 3 张全部 `upgraded`**；总数 +3。日志 `S249 burns=3->6 upgradedOriginals=3 timedOut=false`。
  - 修前（`simDelta = FAST_DELTA`）：`passed=2 failed=1`，`S249 burns=3->3 upgradedOriginals=3`，红行 `the later time window still adds its three new burns | after=3 before=3`。
  - 反向验证（修后把默认值改回 `FAST_DELTA`，build+install）：同样 `passed=2 failed=1` ⇒ 门禁有牙。
  - 门禁首版写错过一处（数全堆 upgrade 数，new 的 3 张也升级 ⇒ 实测 `upgraded=0->6` 误红），改成持有 3 张原始 Burn 引用逐张判 `upgraded`。
- **结果**：全量 core **`passed=725 failed=0`**（S1–S249，271 条＝core 164 + extended 104 + B180 新增 2 + B181 新增 3）。已装 jar sha256 **FB597787F4A65C56CEF3D1371BC494076FD1EC4566AA5EE4006D551F3970E8AD**（1,243,477 B）。
- **① 代价（实测）**：每节点变慢。同一门禁 S246：`nodes=3866 pass=123 total=3866 ms=7363`；B180 基线 `nodes=4123 … ms=2377`（`solver-20261110-210945.log`）与 `nodes=3919 … ms=3037`（`211915.log`）⇒ 节点数同量级、墙钟 2.4–3.8 倍，折合每节点 **0.58–0.78 ms → 1.90 ms**。原因就是预测的「一个 0.5 s 的动作从 1 帧变成 10 帧」。搜索**结果不变**：`S244 widenedAt=23 expansions=1072 atWiden=888` vs 基线 `expansions=1078`；`S230 quick: nodes=426727` 与基线逐字相同。core 整套墙钟仍约 10 分钟（23:27→23:37）。内存无变化（帧数不进任何容器）。
- **② 风险**：①`Driver.drain` 的步数上限（`drain(600,250L)`、`drain(4000,…)`）与 `completeIfTruncated`（4000 步 / 3.6e6 ms）理论上可能被更细的帧撞到——实测本套件没有出现 `drain-truncated` 日志，但极端长动作（数百个 0.5 s 动作）会比以前更早触到步数上限；②某些「靠一帧大 delta 直接跳到结束」的 action 现在要多跑若干帧，若它本身有 bug（比如每帧都重建卡）会更慢或暴露新分叉，属「变真」而不是「变错」；③`endTurnSmallFramesForTest` 的默认值改为 null→小帧，凡依赖旧默认大帧的外部脚本/门禁要显式钉 `FALSE`（S133/S134 已钉）；④回滚＝把 `simDelta` 默认改回 `FAST_DELTA` 一行即可，S249 立刻红，其余代码不依赖该默认值。
- **③ 对 mod 的影响**：面板/自动出牌更准（Hexaghost 这类分段动作的战斗不再从第 10 回合开始整条偏离）；普通战斗数值不变（每步结果相同，只是耗时上升）；搜索在同样时间预算下节点略少（面板 worldline 计数速率会略低）；其它 mod：任何在 `update()` 里按 `duration` 分段的动作（月读/自伤/延迟牌）现在都会被完整跑完，属修真；不改变候选枚举、评分、回放格式、存档与其它 mod 的交互。
- **已知边界**：`SELECTION_DELTA = 0.05 s` 仍是「一步 3 帧＝0.15 s」的近似，若某个动作的时间窗窄于 0.05 s（例如 `duration` 判 `== 0.0f` 之外的极短阈值）仍可能被跳过；本修把它从 20 s 缩到 0.05 s，涵盖原版与已装 mod 里所有已知的 0.5/0.4/0.1 秒级窗口。
- **文档**：PLAN.md（bug 表 B181 行、收尾状态 2026-11-12 / core 725/0 / 新 sha、复测第 61 条、历史范围改 B1–B181）、PLAN-P6（读数约定 + P6-91）、PLAN-WORKLOG（2026-11-12 B181）、TESTING（头部 sha/基线/S249 明细）、SANDBOX-MECHANISMS（机制 M46 + 页脚）。

## B182 — 沙盒不保留手牌发光队列：计算时实机手牌光圈被掐短（user m00001 / m00148 / m00171）

- **用户原话**：m00001「这个mod现在沙盒疑似没有隔离手牌的发光导致计算时卡牌发光和原本不一样。告诉我修改方案但是先不动手」；m00148「拷贝这个发光没什么用，沙盒不用拷贝这个了。」；m00171「做」。
- **现象**：计算中实机手牌里「可打」的牌本来有 1.2 s 一圈的脉冲光圈，一开始算就明显变短/几次后只剩静态描边，停算后要等一会儿才恢复正常节奏。面板数字、计划、自动出牌都不受影响，纯表现差异。
- **根因**：
  1. `AbstractCard.java:162` `private ArrayList<CardGlowBorder> glowList`，每 0.3 s 追加一个 `CardGlowBorder`（`:966`），每个 `duration = 1.2f`、`color = glowColor`。
  2. `CardGlowBorder extends AbstractGameEffect`，而 `ClonePolicy.java:74` 对所有 effect 一律按引用共享 ⇒ 沙盒 `AbstractCard` 克隆的 `glowList` 与实机是**同一个 list、同一批 border 对象**。
  3. 沙盒里每张牌判「现在能不能打」都走 `hand.glowCheck()`（`CardGroup.java:389`），对不能打的牌调 `AbstractCard.stopGlowing()`（`:1140`）——它把列表里**每个** border 的 `duration /= 5.0f`；`drawCard`（`AbstractPlayer.java:1337`）与 `gainEnergy`（`:1539`）、`UseCardAction.java:101`、`HandCheckAction.java:17` 都会触发。
  4. 沙盒不渲染，`updateGlow()` 只在 `renderInLibrary`（`:1017`）/`renderCard`（`:1064`）里跑 ⇒ 沙盒**只毁不建**，实机光圈被越掐越短。
  5. `isGlowing`/`glowTimer`/`glowColor` 这些字段本来就被正确隔离（全仓 grep `glowColor = ...GLOW_COLOR;` 命中 0），所以只有「队列」这一处泄漏。
- **修法（按 user m00148：不拷贝，清空）**：
  1. 新增 `{proj}/src/main/java/sts1solver/mirror/GlowReset.java`：缓存 `Reflect.find(AbstractCard.class, "glowList")`，扫沙盒 `player` 的 hand/drawPile/discardPile/exhaustPile/limbo/cardInUse 与 `actionManager.cardQueue` 里的牌，凡是 `glowList` 非空就用 `Reflect.set` **换成新的空表**（不是 `clear()`：万一某张卡意外还是共享引用，换表只让实机光圈重置一次、0.3 s 内自然重建；`clear()` 会把它永久清空）。`scanned()/dropped()/totalDropped()` 供门禁读。
  2. `{proj}/src/main/java/sts1solver/mirror/World.java`：`install()` 末尾（`StaticWatch.afterInstall()` 之后）加 `if (!glowReset) { glowReset = true; GlowReset.drop(); }`，`private boolean glowReset` 保证每个世界只扫一次。
  3. 不碰 `ClonePolicy`/`Cloner`：effect 共享规则是给真表现对象用的（`VfxGuard` 负责丢弃），发光队列只是「沙盒用不到的表现队列」，按世界清掉比给每个 fork 拷 40 个对象便宜。
- **门禁 S250(4)**（`probe/Probe.java`，插在 S249 之后）「the sandbox copy of the hand glow queue is dropped, not shared」：合成战斗（seed 9250、80 HP、`Strike_R`+`Defend_R`、JawWorm）→ `hand.clear()` 后 `hand.addToTop(new Strike_Red())`（`CardGroup.addToTop` 是追加到末尾，索引 0 唯一可靠），往这张牌的 `glowList` 塞一个真 `CardGlowBorder(card, card.glowColor)`（1.2 s）→ `World.captureLive()`+`install()` → 断言：①沙盒那张卡的队列为空（`sandboxSize=0`）；②`GlowReset.dropped()>0` 且沙盒与实机是同一张卡（`sameCard=true`）；③对沙盒调 `stopGlowing()`+`hand.glowCheck()` 后**实机** border 的 `duration` 仍是 1.2（旧代码 0.24000001）；④实机的 list 还是同一对象、仍含该 border，`uninstall()` 后也不变。
  - 修后：`passed=4 failed=0`，日志 `S250 glow before=1.2 after=1.2 liveSize=1 sandboxSize=0 scanned=1 dropped=1 sameCard=true liveSame=true liveAfter=true`。
  - 反向验证（把 `World.install()` 里那行注释掉、build+install）：`passed=2 failed=2`，红行 `the sandbox copy carried the ring and install dropped it there | dropped=0 sandboxSize=1`、`a simulated stopGlowing does not shorten the live ring | before=1.2 after=0.24000001` ⇒ 门禁有牙。
- **结果**：全量 core **`passed=729 failed=0`**（S1–S250，272 条＝core 165 + extended 104 + B180 新增 2 + B181 新增 3 + B182 新增 4）。已装 jar sha256 **559F67C208506FA2D6DBBC731B04F4E2C23D8E944A4F684E2AD7D6303A10E478**（1,247,390 B，539 entries）。作废中间版：`BDE232267DBC48A023BF81E6394397274834A04B2CAE8BF5C0A081C39BC94876`（1,247,364 B，反向验证用，清理逻辑被注释）。
- **① 代价**：每建一个世界，`GlowReset.drop()` 把沙盒约 30–50 张牌各读一次 `glowList` 私有字段（`Field` 只查一次），只有非空才 `new ArrayList(0)`；实测一次 install `scanned` 个位数到几十、`dropped` 0–2，µs 级，且 `install` 只在 fork 时发生、不在每节点热路径。内存：每个空表约 40 B、最多几十个、可 GC；不新增缓存、不拷贝对象（这正是 m00148 否掉的路线）。
- **② 风险**：①清空的是「沙盒的队列」，若将来有代码在 `glowList` 上存非表现状态会失效——目前只有 `AbstractCard` 自己（`updateGlow`/`stopGlowing`/`renderGlow`）读写它，`updateGlow` 只在渲染路径跑；②`glowList` 是 `private ArrayList<CardGlowBorder>`（非 final），反射可写、无需 `--add-opens`；`Field` 查询失败时 `drop()` 安全返回 0（不抛）；③万一某张卡真的与实机共享，换空表只让实机光圈重启一次（<0.3 s 重建），不会永久不发光；④回滚＝删掉 `World.install()` 里那三行（`S250` 立刻红，其它门禁不受影响）。
- **③ 对 mod 的影响**：面板/路线/自动出牌/评分/候选枚举**完全不变**（`Digest` 的 8 段哈希不含发光，`position key`、worker 根指纹、live 未污染校验都不读它）；搜索速度不变（不在节点循环里）；唯一可见变化是「计算时实机手牌保持原本的脉冲光圈」。其它 mod：不读 `glowList` 的 mod 无感；若有 mod 想在沙盒里读发光队列做判断，会读到空表（目前没发现这种 mod）。
- **已知边界**：只清「安装时刻」的队列；沙盒不渲染，`updateGlow` 不会在沙盒里新长 border，所以不需要在模拟过程中反复清；同一个世界对象再次 `install` 也不会重复扫（`glowReset` 已置位）。发光颜色/`isGlowing` 本来就隔离，本修不涉及。
- **文档**：PLAN.md（bug 表 B182 行、收尾状态 2026-11-13 / core 729/0 / 新 sha、复测第 62 条、历史范围改 B1–B182）、PLAN-P6（读数约定 + P6-92）、PLAN-WORKLOG（2026-11-13 B182）、TESTING（头部 sha/基线/S250 明细）、SANDBOX-MECHANISMS（机制 M47 + 页脚）。

## B183 — 沙盒帧长按动作分档 + 阈值落点：B181 的 0.05 s 统一小帧让搜索慢了 3–5 倍（user m00434）

> **状态：已按 user m00787 回退（2026-11-13 23:30）。** 用户实测「改的有问题，先回退。现在计算卡的要死」——门禁证明帧数确实从 61 降到 5，但实机反而更慢，原因尚未定性。已撤销全部 B183 代码（删除 `drive/FramePlan.java`、`Driver.engineFrame()` 与 `frameDelta()` 里的分档分支、Probe 的 S251/S252 与 S133/S134/S82 的分档分支），回到 B182 的帧长语义；已装 jar 回到 B182 量级（1,247,388 B，sha256 `8B0E1D9054CEF4600E03E6755520415EA43A72CBAD797E4757A73525F9E2531B`），门禁 `S82,S133,S134,S249,S250` 25/0、全量 core 见 PLAN-WORKLOG。下面的记录保留为**历史设计与否决理由**，回退后不再生效；复现路径与待查怀疑点见本节「回退记录」。其需求已由 **B184**（见下一节）以「只给 6 个审计过的窗类分档」的白名单方式重写并实施；本节保留为否决理由。

- **用户原话**：m00434「现在平均两百条世界线每秒，之前是1000/s。原因可能是B181修bug导致的。有没有方案能加快速度同时不会出bug」（随后「做」）。
- **现象**：B182 之后平均约 200 条世界线/秒，B181 之前约 1000/s；日志里 nodes 与 ms 的比值从 0.58–0.78 ms/节点涨到 1.90 ms/节点（S246 的 `nodes=3866 ms=7363`）。
- **根因**：B181 为了「一帧的 delta 不得跨过动作的时间窗」，把沙盒**所有**帧的 delta 从 20 s 压到 0.05 s。于是每个动作都按 0.05 s 逐帧 tick：`BurnIncreaseAction`（duration 3.0 s）要 61 帧才能跑完，`WaitAction(0.5f)` 要 10 帧，绝大多数「其实一帧就够」的动作都被拖成几十帧。真正的需求只是「**别跨过**窗」，不是「每帧都要小」。
- **离线审计**（`_tmp/audit/FrameAudit.java`，一次性工具，不属于 mod 源码）：javassist（classpath 直接用 `ModTheSpire.jar`，它内含 javassist 包）扫 `desktop-1.0.jar` + 21 个 workshop jar + 我们的 jar：`classes scanned from jars: 7206`、`actions=378 withDuration=143 exact=138 ownFloat=37`。阈值 token 直方图：72×`0.25/ne`、33×`0.5/ne`、16×`0.1/ne`、10×`0.2/ne`、5×`0/ge`、2×`0.01/ne`、2×`0.33/ne`、1×`1.5/ge`、1×`0.15/ne`、1×`0/ne`、1×`3/ne`。结论：绝大多数 `==` 是「首帧等值」（`duration == 构造初值`，只在第一帧成立）；**首帧之后**才成立的窗只有六处——`DrawCardAction`/`FastDrawCardAction`/`ScrapeAction`/`RipAndTearAction`/`loadout.actions.EasyXCostAction` 的 `this.duration < 0.0f`（每张牌一次跨越，跨越后 duration 被重置成 `ACTION_DUR_XFAST`/`ACTION_DUR_FASTER`）与 `BurnIncreaseAction` 的 `this.duration < 1.5F`（新建 3 张 Burn）。`ownFloat` 收窄到「字段声明在 actions/CaiNongMod/stslib 包」后剩 37 个类，字段名去重为 `startDuration, shakeDur, bubbleDuration, startDur, increasePercent, pitchVar, x, y` ⇒ 没发现「自带计时器字段、不读 duration」的盲区（`startDuration` 就是 `startingDuration` 的别称）。源码交叉核对（`build/decomp6`）：`ChannelDestructionAction`/`RetainCardsAction` 的 `== Settings.ACTION_DUR_FAST`、`== 0.5f` 都是首帧等值，`== 3.0F`/`< 1.5F`（BurnIncreaseAction）是首帧窗 + 后期窗。
- **修法（B183；不动 B181 的窗语义，只改帧长）**：
  1. 新增 `{proj}/src/main/java/sts1solver/drive/FramePlan.java`：`WINDOWS`（类名 → 窗数组，就上面六个类：`{0.0f}` 与 `{1.5f}`）、`windowsFor(Class)` 沿父类链查并缓存、`DURATION = Reflect.find(AbstractGameAction.class, "duration")`（**真实 jar 里是 protected，反编译出来的 public 骗人**，所以用 `Field.getFloat`，读失败→按未知类处理）。
  2. 规则：`currentAction == null || isDone` → 0.05 s；一个动作的前 6 帧、且 `duration` 还等于/大于它出现时的初值 → 0.05 s（安全阀：所有 `duration == 初值` 的判断、以及 `RetainCardsAction`/`ScryAction` 开选牌屏都发生在首帧）；否则取 `stop = max{t ∈ 窗 | t < 当前 duration}`，`delta = duration - stop + 0.02f`（落在窗**略下方**，下一帧 `< stop` 成立），夹在 [0.05, 20]；没有可落点的窗 → 20 s 直接收尾。未知类（取不到字节码/读失败）一律 0.05 s。
  3. `Driver` 接缝：新增 `engineFrame(GameActionManager gam)`（`FramePlan.begin(gam)` → `gam.update()` → `finally FramePlan.end()`），只包主循环与 `runEndOfTurnQueue` 两处 `gam.update()`；`frameDelta()` 在屏规则之后加 `if (FramePlan.planned()) return FramePlan.delta();`。其余 `gam.update()`（`settleForReplay`、live 选牌应答、`pumpUntilSelection`、各 Probe 的裸 update）不接管，仍走 `simDelta`。
  4. 回滚开关：`settings.txt search.frameTiers`（默认 on；`off/no/false/0/关闭` 关），关掉即完全退回 B181 的全局 0.05 s；`FramePlan.setEnabledForTest(Boolean)` 只给门禁用。
- **门禁 S251(4)**（`probe/Probe.java`，插在 S250 之后）「a staged action still gets its window on per-action frames」：合成战斗（seed 9251、80 HP、`Strike_R`+`Defend_R`、JawWorm）→ 弃牌堆 2 张 Burn + 抽牌堆 1 张 Burn → `addToBottom(new BurnIncreaseAction())` → `Driver.drain(4000, 800L)`。断言：①三张原 Burn 全升级；②总数 3→6（后段窗真的新建了 3 张）；③`d.steps <= 12`（B181 需要约 61 步）；④`fresh >= 1 && fast >= 1`（真的分了档）。实测 `S251 burns=3->6 upgradedOriginals=3 steps=5 fresh=1 fast=1`。
- **门禁 S252(3)**「frameTiers=off restores the B181 pacing」：同场景（seed 9252、2 张 Burn）但 `FramePlan.setEnabledForTest(FALSE)`；断言结果仍正确、`d.steps >= 40`、`fast == 0`。实测 `S252 burns=2->5 steps=49 fresh=0 fast=0` ⇒ 开关真的能退回旧帧长。
- **S82/S133/S134 的自钉**：这三条门禁原本要「强制旧行为来复现 bug」（20 s 大帧 / 关掉屏规则），分档会覆盖它们的钩子 ⇒ 现在这三处的旧行为分支都先 `FramePlan.setEnabledForTest(FALSE)`；另各加一条「分档开着也能正确」的分支（S133 第 4 条断言、S134 2 条断言），实测 `S133 tiered ... inHand=true orphans=0`、`S134 tiered ... scried=true screenFrames=1`。
- **反向验证**（做完已还原）：把 `FramePlan.begin()` 临时改成「恒返回 20 s」（`if (true) { planned = true; delta = FAST; return; }`）→ build+install → 跑 S249/S251：`passed=4 failed=3`，红行 `the later time window still adds its three new burns | after=3 before=3`（S249）、`... on per-action frames | after=3 before=3`、`the frames really were tiered ... | fresh=0 fast=0`（S251，`burns=3->3 steps=3`）⇒ 门禁有牙。
- **结果**：全量 core **`passed=739 failed=0`**（门禁 S1–S252；B183 新增 9 条断言＝S251 4 + S252 3 + S133 +1 + S134 +2）。已装 jar sha256 **C069E417E6BDF1B41B1F6DF3BBA232777882D31D03C42D757805B76FCEB9EB0E**（1,256,070 B）。
- **① 代价**：每个沙盒帧多一次 `FramePlan.begin()`：一次 `currentAction` 判空、一次 `Field.getFloat`（`Field` 只查一次）、一次按 Class 缓存的窗查表；实测 S251 的 steps 从 61 掉到 5，净收益是每节点帧数大幅下降；内存每个动作类缓存一条 `float[]`（最多 6 条），不新增每节点对象。`FramePlan` 是 static 单帧状态，与「一帧只驱动一个 world、并行靠独立进程 WorkerPool」的模型一致。
- **② 风险**：①新增 mod 动作类若在首帧之后还有时间窗**不会**被自动发现（`WINDOWS` 是白名单）——但「第一帧 0.05 s + 未知类 0.05 s」让无窗动作最多多跑几帧、不会跳窗；发现可疑就重跑 `_tmp/audit/FrameAudit.java` 并把类加进表；②`duration` 读失败（将来混淆/改名）时退化到 0.05 s，只是慢、不会错；③`FramePlan` 若异常路径漏掉 `end()` 会让下一帧沿用上一动作的 plan —— 已用 `try/finally` 包住，且 `begin()` 每帧重算、不依赖上一帧状态；④回滚＝`search.frameTiers=off`（无需重编），或删 `engineFrame` 两处调用。
- **③ 对 mod 的影响**：面板、路线、自动出牌、评分、候选枚举、`Digest` 都不变（帧长不进任何哈希）；只改「沙盒里每个动作要跑多少帧」。live 实机路径不经过 `FramePlan`（live 用真实 `Gdx.graphics.getDeltaTime`），屏开着时仍走 `screenDeltaApplies()` 的 1/60 s。其它 mod：只影响沙盒执行速度，不影响任何共享对象。
- **已知边界**：`WINDOWS` 是「按已装 jar 审计出来的白名单」，不是运行时推断；`exact=138` 那些 `==` 判断全靠「第一帧永远 0.05 s」兜住，若将来有人写出「中途 `duration == 0.9f`」这种非首帧的 `==` 判断，会被一次大跳跨过（审计脚本能查出来，需重跑）。速度按用户体感应回到 1000/s 左右（待实测）。
- **文档**：PLAN.md（bug 表 B183 行、收尾状态 2026-11-13 / core 739/0 / 新 sha、复测条目、历史范围改 B1–B183）、PLAN-P6（读数约定 + P6-93）、PLAN-WORKLOG（2026-11-13 B183）、TESTING（头部 sha/基线/明细）、SANDBOX-MECHANISMS（机制 M48 + 页脚）。
- **回退记录（user m00787「改的有问题，先回退。现在计算卡的要死」，2026-11-13 23:30）**：
  - **撤销范围**：删除 `src/main/java/sts1solver/drive/FramePlan.java`；`Driver.frameDelta()` 去掉 `FramePlan.planned()` 分支（回到 `screenDeltaApplies()` 之后直接 `return simDelta`）；删除 `Driver.engineFrame(GameActionManager)` 并把主循环、`runEndOfTurnQueue` 两处改回裸 `gam.update()`；`Probe` 删除 S251(4)/S252(3) 两段、S133/S134/S82 的 `FramePlan.setEnabledForTest` 钩子与各自的分档分支、`s133Retain`/`s134Scry` 的 `tiered` 参数与返回串字段。全仓 `grep FramePlan|frameTiers|B183` 在 `src/main/java` 下命中 0。
  - **回退后基线**：`S82,S133,S134,S249,S250` = **passed=25 failed=0, 267 gate(s) skipped**；全量 core 回到 B182 的 **729/0**（S251/S252/S133/S134 的 9 条新增断言随代码一起删除）。已装 jar 1,247,388 B / sha256 `8B0E1D9054CEF4600E03E6755520415EA43A72CBAD797E4757A73525F9E2531B`（与 B182 的 1,247,390 B 同量级；jar 非确定性，sha 必然不同）。
  - **为什么门禁说变快、实机却更慢（未定性，禁止盲改）**：①`frameDelta()` 与 `FramePlan.begin()` 的取帧时序可能错位（`frameDelta` 先于 `begin` 被读，读到上一动作的 delta）；②20 s 大帧可能触发静态监听/回写等其它按帧计价的路径；③每帧 `Field.getFloat` + 查表在热路径上的固定开销。要先用 `solver.log` 的 nodes/ms + Watchdog 采样（8×150 ms 同线程多帧）定位，再决定是否重做。
  - **教训**：B183 的验收只看「帧数下降」，没有把「实机 nodes/ms 与每秒世界线数」作为门禁的一部分，导致方向正确但整体更慢仍被判绿。以后改帧长/节流类功能，验收必须包含端到端吞吐（nodes/ms）对照，而不只是 steps 计数。

## B184 — 沙盒帧长按动作分档（白名单重写）：只给 6 个「靠 duration 逐帧推进」的动作计划快帧（user m01136「重写」）

> **状态：已实施（2026-11-14 00:35），等实机吞吐验收。** B183 把 372 个动作类一律分档、无窗就 20 s，实机「计算卡的要死」（user m00787）后全量回退。本版只保留 B183 里真正被审计证实的东西：**白名单 6 个类**、其余一律 0.05 s；落点不再越过时间窗；屏帧永远优先。下面的记录取代 B183 的设计段（B183 段落仅作历史）。

- **用户原话**：m01136「重写」（在 B183 回退 + 变卡归因之后）。
- **为什么重写而不是再调 B183**：B183 的帧长是「按类推断」——除 6 个窗类之外，凡是读不到窗的动作一律 20 s，等于对 372 个动作类集体改语义；实机表现为计算期间画面/输入彻底停摆。重写把默认值改回 0.05 s，把「快帧」变成**显式白名单福利**：漏掉一个类最多是慢，不会改变任何动作的中间态。
- **白名单（按类名精确匹配，不走父类链）**：
  - `com.megacrit.cardcrawl.actions.common.DrawCardAction`、`...common.FastDrawCardAction`、`...defect.ScrapeAction`、`...unique.RipAndTearAction`、`loadout.actions.EasyXCostAction` → 窗 `{0.0f}`（`duration < 0.0f` 时做一件事并把 duration 重置成 `ACTION_DUR_XFAST`/`ACTION_DUR_FASTER`，一张牌一个周期）。
  - `com.megacrit.cardcrawl.actions.unique.BurnIncreaseAction` → 窗 `{1.5f}`（`duration == 3.0f` 首帧升级两堆已有 Burn，`duration < 1.5f` 新建 3 张 Burn，然后一直 tick 到 <0）。
- **规则（`drive/FramePlan.java`）**：`currentAction == null || isDone` → 不分档；`search.frameTiers` 关 → 不分档；**非白名单类 → 永远 0.05 s**；白名单类的前 `FRESH_FRAMES = 3` 帧 → 0.05 s（保首帧 `duration == 初值` 这类判断）；`duration >= start`（还没被 tick 过）→ 0.05 s；`stop = max{t ∈ 窗 | t <= duration}`（无可落点窗则 `stop = 0`），`next = duration - stop + EPS(0.001)`，夹在 `[0.05, 20]`。**不越过**窗：`EPS` 只用来保证下一帧落在窗下方一点点，不再用 B183 的 `+0.02f`。
- **Driver 接缝**：`frameDelta()` 的顺序是 `screenDeltaApplies()`（1/60）→ `FramePlan.planned()` → `simDelta`；`engineFrame(gam)` = `FramePlan.begin(gam)` → `gam.update()` → `finally end()`，只包主循环 drain 与 `runEndOfTurnQueue` 两处（其余 3 处 `gam.update()` 保持裸调用）。
- **回滚开关**：`settings.txt search.frameTiers`（默认 on，`off/no/false/0/关闭` 关；1000 ms 缓存）。关掉即完全回到 B181/B182 的全局 0.05 s，**无需重编**。
- **顺带修好的死计数器（B184）**：`Solver.Profile` 的 `fork/install/apply/readBack/digest/uninstall` 六个字段此前一直打印 0（只创建不累加）。现在 `expandCore` 的探针路径给 fork、install、`Legal.apply`、readBack、uninstall 各包一段 `System.nanoTime()`，`readChoice` 给 `Digest.ofCurrent()` 包一段；`Session.finish()` 追加 `Log.info(profile.line())`，切片路径（面板/切片计算）终于也有 profile 行。没有这段计时，B184 的速度收益与残余开销都无法从日志里读出来。
- **门禁 S251(4)**「only the audited action classes get a planned frame」：合成战斗（seed 9251、80 HP、`Strike_R`+`Defend_R`、JawWorm）+ 弃牌堆 2 张 Burn + 抽牌堆 1 张 Burn + `BurnIncreaseAction` → `Driver.drain(4000, 800L)`；断言：①`owns(BurnIncreaseAction) && !owns(DamageAction)`；②`fresh >= 3 && fast >= 1`；③三张原 Burn 全升级且总数 3→6；④`d.steps <= 12`。实测 `S251 burns=3->6 steps=7 fresh=5 fast=2 auditedBurn=true`。
- **门禁 S252(3)**「search.frameTiers=off restores the uniform frame」：同场景（seed 9252）+ `setEnabledForTest(FALSE)`；断言 `fresh == 0 && fast == 0`、`d.steps >= 40`、总数仍 3→6。实测 `S252 burns=3->6 steps=49 fresh=0 fast=0`。
- **门禁 S253(3)**「a screen-up frame overrides the plan」：同场景（seed 9253）+ `Driver.setScreenDeltaForTest(TRUE)`；断言 ①`screenFrames >= 40`；②`fast >= 1`（计划确实为这个动作准备了快帧）；③`after == before`（快帧**没有**被采用——若采用，1.5 s 窗会被一次跳过去并造出 3 张 Burn）。实测 `S253 burns=3->3 steps=49 screenFrames=48 fast=45 settled=false stalled=true`：48 个屏帧后 drain 撞上 49 步的停滞护栏退出，正是「屏帧优先」的表现。
- **反向验证（已还原）**：把 `FramePlan.begin()` 临时改成「白名单类恒跳 `LIMIT`」→ build+install（sha `EEB3609DC259432372043EF1058723A4A243F1FC523780B97490014C755F1FE2`）→ 跑 S251/S252/S253：**`passed=8 failed=2`**，红行 `the head of the action runs at 0.05 s and a later frame is planned | fresh=1 fast=1`、`both time windows of the action still land | upgradedOriginals=3 after=3 before=3`（`S251 burns=3->3 steps=3`）；S252/S253 仍绿（关开关与屏优先本来就不经过计划）⇒ 门禁有牙且只咬该咬的地方。
- **结果**：全量 core **`passed=739 failed=0`**（门禁 S1–S253，282 条；B184 新增 10 条断言＝S251 4 + S252 3 + S253 3）。已装 jar 实测 **1,258,662 B，sha256 `56486847279F6FCF439EAF0A52C0289800495624DABCF794A87D2B7B8D633AF7`**（jar 非确定性，sha 以 `Get-FileHash` 实测为准）。
- **① 代价**：每个沙盒帧一次 `FramePlan.begin()`：读一次 `currentAction`、白名单查表（`HashMap` 按类名，仅切换动作时查）、一次 `Field.getFloat(duration)`（`Field` 只解析一次）。内存只有 6 条 `float[]` 窗表与每动作一次的状态（`action/start/age`）。白名单 6 条是**手工维护项**：新增同类动作需要人工加表并补审计。`Profile` 新计时是 6 次 `System.nanoTime()`/节点，纳秒级。
- **② 风险**：①6 个类之外的动作仍然是 B181 的 0.05 s/帧，所以**吞吐不可能回到 1000/s**，只能回收这 6 类（抽牌、抽牌型攻击、灼伤升级）占的那部分帧数；②白名单按类名精确匹配，子类或改名后的动作不会被自动分档（慢，但不会错）；③`duration` 读失败（未来混淆/改名）→ 该动作按未审计处理，0.05 s；④`begin()` 异常漏 `end()` 只会让当帧沿用上一计划——已用 `try/finally` 保证，且 `end()` 只清 `planned`；⑤回滚＝`search.frameTiers=off`，或删 `engineFrame` 两处调用后重编。
- **③ 对 mod 的影响**：不进 `Digest`（帧长不是任何哈希的一部分），不影响转移去重、worker 根指纹、live 未污染校验；面板、路线、自动出牌、评分、候选枚举、其它 mod 的共享对象全部不变；实机（live）路径不经过 `FramePlan`，屏打开时仍走 `screenDeltaApplies()` 的 1/60 s。唯一可见变化是**沙盒里每节点的帧数**，以及 `solver.log` 新增的 `profile:` 行（切片路径也会打一行）。
- **已知边界**：①白名单是「按已装 jar 审计出来的」，不是运行时推断（审计脚本 `_tmp/audit/FrameAudit.java`，一次性工具）；②`exact=138` 那类 `== 初值` 判断靠「白名单类前 3 帧 0.05 s」兜住，非白名单类本来就逐帧 0.05 s；③屏帧存在时（`isScreenUp`）任何计划都不生效，这对 `BurnIncreaseAction` 这种 3 s 动作意味着一次 drain 内跑不完（停滞护栏在 49 步退出）——实机同样要 180 个 1/60 帧，属正确行为；④本版仍未把「实机 nodes/ms / 每秒世界线数」写成门禁，验收要靠用户实测日志（B183 的教训）。
- **文档**：PLAN.md（bug 表 B184 行、收尾状态、新 sha、复测条目、历史范围改 B1–B184）、PLAN-P6（读数约定 + P6-94）、PLAN-WORKLOG（2026-11-14 B184）、TESTING（头部 sha/基线/明细）、SANDBOX-MECHANISMS（机制 M49 + 页脚）。

## B185 — 沙盒窗口里不许真开 javax.sound 音频线（新机制 M50：窗口级 Clip 回收）

> **状态：已实施（2026-11-15 01:20），已被 B187 取代（2026-11-15 晚）。** 本条的「窗口结束再 close」只消除了叠音、没有隔离执行：它会把**已关闭的实机 Clip 写回**字段，下一窗口 `stop()→flush()` 在 `DirectAudioDevice.nFlush` 原生崩溃（`hs_err_pid17280.log`）。B187 改为「窗口内把 Clip 静态换成惰性替身、一次线都不开」；本条的 `begin()/end()` 挂点与 `blind` 可读性断言保留，`close` 只用于窗口自己开出来的 Clip；B169 的「释放保持释放」在音频静态上被推翻。等你验收（m00149「做A，等我验收再上传创意工坊和github」）。 用户报「沙盒疑似没有隔离被『半血自动播放雨爱』mod 控制的接口，导致沙盒计算时雨爱声音在后台重复播放」，且明确「沙盒只需要模拟战斗相关的部分」。

- **用户原话**：m00001「查一下 sts1 战斗求解器 mod…这个 mod 现在沙盒疑似没有隔离由『半血自动播放雨爱』mod 控制的接口导致沙盒计算时雨爱声音在后台重复播放。查一下哪里没有隔离，沙盒只需要模拟战斗相关的部分。告诉我修改方案但是先不动手」；m00149「做A，等我验收再上传创意工坊和github」。
- **根因（不是「音量没关」，是「根本没经过我们的 mixer」）**：模拟回血时沙盒真的调用 AbstractCreature.heal(int) → 内部再调 heal(int, boolean)。雨爱（yuaimod.YuAiMusic）在这两个方法上都挂了 @SpirePostfixPatch，MTS 把 postfix 插在**每个 RETURN 之前**，因此窗口内两个 postfix 都会真跑：OnHealAmount / OnHealAmountShowEffect → YuAiController.evaluate(沙盒玩家) → YuAiMusic.play() → javax.sound.sampled.AudioSystem.getClip() 开一条**真实音频线**（loop）。而 mirror/MusicMute(B45) 只接管 CardCrawlGame.music、mirror/SfxMute(B65) 只接管 CardCrawlGame.sound，两者都管不到 javax.sound；drive/Driver.begin() 里的 Settings.SOUND_VOLUME = 0 也无效（雨爱音量＝MUSIC_VOLUME * MASTER_VOLUME，且 0 音量照样 getClip() 开线）。mirror/PresentationQuiet(B168) 只改写了 14 处 healthBarUpdatedEvent() 调用点，挡不住 heal 方法**自身**的 postfix（prefix 提前 return 也无效，postfix 仍挂在 RETURN 前）。
- **「重复播放」是怎么被放大的**：每个搜索节点开一个窗口；窗口结束时 mirror/StaticWatch 把 YuAiMusic#currentClip 还原成窗口前的快照值，窗口内新建的 Clip 于是**失去引用但仍在 loop、没人 close**；下一个回血节点再建一个 ⇒ N 条叠加。同时 mod 自己的 active 位也被回滚，evaluate 每次看到 active && !isPlaying 就打「播放意外终止」。
- **现场证据**：solver.log 01:26:31.875 quick search: started；01:26:36.172 yuaimod.YuAiMusic#currentClip was released (set to null) while a window was open; left released.；01:26:36.984 the sandbox wrote mod static yuaimod.YuAiBgm#STATE (map) … put back；01:26:37.170 1 write(s) to live-shared mod statics put back: [yuaimod.YuAiMusic#currentClip (object)]。YuAiBgm#STATE 只有 YuAiBgm.tick() 写，currentClip 只有 play/stop/LineListener 写 ⇒ 窗口内 evaluate/play 确实在跑。D:\Steam\steamapps\common\SlayTheSpire\sendToDevs\mts_process_launch.log 末尾 400 行统计：播放意外终止×70、开始循环播放×40、停止播放×24，且 7521–7597 行同一 HP 序列（22,27,15,17）重复两遍 —— 实机 HP 恒 17/74，那些 evaluate 收到的是沙盒玩家。
- **5 个钩子的隔离现状**（本次只修真正漏的）：①GameLoopPatch→CardCrawlGame.update postfix：窗口内不跑（搜索在 BaseMod.receivePostUpdate 里同步跑），未隔离但不触发；②OnHealthBarUpdated→healthBarUpdatedEvent：已由 B168 隔离；③OnHealAmount→heal(int)：**未隔离**；④OnHealAmountShowEffect→heal(int, boolean)：**未隔离（主漏点）**；⑤OnGameVolumeChange→MusicMaster.updateVolume→syncVolume：未隔离，风险低（窗口内不跑）。
- **修法（新文件 src/main/java/sts1solver/mirror/AudioQuiet.java，零 mod 类名依赖）**：初始化时扫描全部 mod 的静态字段，缓存所有 javax.sound.sampled.Clip 类型的字段（实测只命中 yuaimod.YuAiMusic#currentClip）；World.install() 在 MusicMute.begin(true) 之后 begin()，World.uninstall() 在 StaticWatch.beforeUninstall() **之前** end() —— 顺序是安全关键：先 close 掉窗口内新建的 Clip，再让 StaticWatch 把字段还原成实机快照。每个窗口都跑（不能像 StaticWatch 那样采样一次）。end() 时字段值 ≠ 记录值且新值是非空 Clip → stop(); flush(); close() 再还原；新值 null 沿 B169 的释放放行。
- **补丁 1（真实 bug，门禁最初没抓到）**：首次带雨爱跑 S255 时日志出现 WARN audio quiet: cannot read yuaimod.YuAiMusic#currentClip: java.lang.IllegalAccessException … modifiers "private static" —— 字段被**发现**却**读不了**（没 setAccessible），保护等于零，而门禁当时仍 PASS（只查成员资格）。现已：all() 对每个候选先 f.setAccessible(true)；打不开的进 blind 清单并 Log.warnEvery("audio-quiet-blind", 30000L, …)，**不让盲字段冒充受保护字段**；新增 readableForTest(key) / blindForTest() 供门禁断言。
- **门禁 S255(16)**「audio quiet: clips a sandbox window creates are closed on uninstall」：合成环境用假 Clip（Probe.S255Clip，Proxy 实现 Clip，记录 running/closed）+ 两个静态字段 Probe.S255_LIVE / Probe.S255_SANDBOX 模拟「实机字段」。断言覆盖：窗口内新建 Clip→close；沙盒覆写实机字段→原值还原且新 Clip 被 close；窗口计数＝2；沙盒对实机 Clip 调 stop() 被检出并报告；带真实雨爱时 readableForTest("yuaimod.YuAiMusic#currentClip") 为真且 blindForTest() 为空。
- **反向验证（已还原）**：注释掉 World.java 的 AudioQuiet.begin();（约 336 行）与 AudioQuiet.end();（约 380 行）→ build+install（sha 0D9615C7D882A6C0664393C1BCB28DDAD26E791706C130A682BEF35C030A64FC）→ 跑 S255：**passed=4 failed=10**，红行 a clip the window left in a static is closed | closed=false、a sandbox clip that overwrote the live field is closed too | closed=false、the window counts exactly the two sandbox clips | 0、a sandbox stop() of the LIVE clip is detected and reported | liveStops=0 ⇒ 门禁有牙。
- **结果**：S255 不带雨爱 passed=14 failed=0；加 setAccessible 后带 -Mods "YuAiBloodMusic" 跑 S255 → **passed=16 failed=0**、yuaiInstalled=true、watched=[yuaimod.YuAiMusic#currentClip, sts1solver.probe.Probe#S255_LIVE, sts1solver.probe.Probe#S255_SANDBOX]、无 IllegalAccessException、无 blind 字段；报告行 audio quiet: 3 mod clip static(s), 1 window(s), 2 sandbox clip(s) closed, 2 live reference(s) put back。全量 core **待复跑确认（S171 修复前全量 755/6 全在该门、其余 751 条全绿；S171 单跑 10/0；02:46 用户叫停复跑）**（门禁 S1–S255）。已装 jar 实测 **1,279,962 B，sha256 9D63ED91E329239EFB7E764DD22DFB6A9832A914AD401CB960CF0DC82C6432B2**（jar 非确定性，sha 以 Get-FileHash 实测为准）。
- **① 代价**：每个窗口一次「遍历 mod 静态字段（只首次扫描并缓存）」+ 每个受保护字段一次 Field.get(null)，卸载时最多一次 stop/flush/close（微秒级，与搜索节点数同阶，不是每帧）。内存只有字段表（实测 1 条）与每窗口的记录值。不新增线程、不改帧长。
- **② 风险**：①只保护**静态字段**里的 Clip，实例字段或集合（List/Map/数组）里藏的 Clip 抓不到 —— 记为已知边界；②同一 Clip 对象被 stop() 但字段未被改写时检测不到（A 方案的边界；B 方案才能彻底消音）；③close() 打真实音频线失败（驱动/设备占用）只记日志不抛；④扫描依赖 ModStatics 的候选枚举，mod 用非常规方式持有 Clip（native/本地库）则看不见；⑤回滚＝删 AudioQuiet.begin()/end() 两行后重编。
- **③ 对 mod 的影响**：只在**沙盒窗口**内生效；窗口外实机雨爱照常播放，不受任何影响；不改 mod 的字段语义（窗口内新建的 Clip 一律 close，实机快照原值照旧还原）；面板、自动出牌、搜索结果、评分、候选枚举、其它 mod 的共享对象全部不变。可见变化：叠加循环消失（最多剩一个窗口长度的碎片），solver.log 新增 audio quiet: … clip static(s) … 摘要行与（异常时）audio-quiet-blind 告警。
- **已知边界**：①「A 方案是通用兜底，不是彻底静音」——想在窗口内一声不出，需 B 方案（modsupport/YuAiQuiet，把 YuAiMusic.audioData 换成 44 字节静音 WAV、currentClip 换只读 Proxy）；本版按 m00149 只做 A；②B168 原结论「窗口里 clip 本来就没启动过」只在**计划里没有回血步**的局面成立，已在 B168 段落补订正；③未隔离 CardCrawlGame.update / MusicMaster.updateVolume 的 postfix —— 窗口内不跑、无实测症状，不做。
- **文档**：PLAN.md（bug 表 B185 行、收尾状态、新 sha、复测第 64 条、订正 B168 行）、PLAN-P6（P6-95 + 订正 P6-78）、PLAN-WORKLOG（2026-11-15 B185 + 订正 B168 段）、SANDBOX-MECHANISMS（机制 M50 + 页脚）、TESTING（头部 sha/基线）。

## B186 — S171 预览门禁把「机器速度」当常量（1200ms 预算 × B179 阶梯只给深档 35%）

> **状态：已实施（2026-11-15 02:36），只改测试代码（probe/Probe.java 的 S171），产品代码零改动；等你验收（m00149）。**

**起因**：B185 交付前跑全量 core 出 `failed=6`，6 条全部落在 S171（预览相关断言），其余 755 条通过。按项目规矩「门禁红了先怀疑门禁自己」，先做 A/B 对照，不动断言。

**A/B 对照（先证明与 B185 无关）**：把 `World.java` 两处挂点注释成 `// BASELINE-CHECK: AudioQuiet.begin(); / AudioQuiet.end();` → build+install（sha `57DB2A72F66BEE147FBC1BE4E9B247F90897136C706808E7332EB5E8E7EFFB55`）→ `-Gates S171` 仍 **passed=4 failed=6**，且 (b) 读数与装 B185 时几乎一致（`nodes=44 totalNodes=276 … 1255ms` 对 `nodes=41 totalNodes=278 … 1258ms`）⇒ 不是 AudioQuiet 的开销或副作用。

**根因**：S171 的 (b) 段只有在「搜索手里已经握着一条胜利线」时才有预览可采样，而这块板（seed 4242、80 HP、4×Strike_R+Bash+3×Defend_R、两只 LouseNormal + 一只 LouseDefensive）的第一条胜线约需 **281 个节点**。B179 的阶梯把 **35%** 预算给窄深档（1200ms → 420ms）：B121 记录的时代是 0.38 ms/节点，420ms ≈ 1100 节点，够；当前机器实测 4.5 ms/节点，420ms ≈ 90 节点，窄档没走到第 7 层就被加宽，于是整轮拿不到胜线，6 条断言全读 `null`（`S171 preview: null`、`rows=0 pills=0`）。验证：把该门预算临时改成 20000ms（`PROBE-EXPERIMENT`）→ **passed=10 failed=0**，预览在 3%（≈281 条世界线 / 1.3s）出现，读数为 `窄搜 beam=10 layers=9`、`暂定路线 · 10 步 · 预计 -6 HP` ⇒ 板、预览链路、断言都正常，红的是**预算里隐含的机器速度前提**。

**修法（只改 `probe/Probe.java` 的 S171）**：
- (a) 段 `setBudgetCapForTest` 1200→**2000ms**：这段只需要「有计划可发布」（生存线也会发布），不需要胜线；
- (b) 段 →**9000ms**，并把它当**上限**而非目标：循环里一旦采到「暂定路线」，在同一拍完成采样后调 `Gameplay.stopQuickSolve()` 提前收尾（`finishQuick` 照常发布计划并清预览，尾部断言「发布后预览消失」不变）；
- 日志改成 `S171 preview after N node(s): …`，万一为红也能直接看到当时搜了多少节点。
- 注释里写明这段预算与 0.38→4.5 ms/节点的事实，避免下一个人再把它当常量。

**门禁**：S171 仍是 **10 条断言**，一条没删、一条没放宽；(a) 覆盖「自然预算到期 → 发布计划」+「阈值不到绝不预览」，(b) 覆盖「采样预览条 → 停搜 → 发布后预览消失」，顺带把 `stopQuickSolve()` 的收尾路径也纳入门禁（此前无门禁覆盖）。

**结果**：`-Gates S171` → **passed=10 failed=0**，整门约 3.5s（(a) 2s + (b) 采到即止），日志 `S171 preview after 281 node(s): …`。全量 core **待复跑确认（S171 修复前全量 755/6 全在该门、其余 751 条全绿；S171 单跑 10/0；02:46 用户叫停复跑）**（门禁 S1–S255）。安装物 jar **1,280,074 B**，sha256 `F24DE109C2D77A211923B62D6226BC3AE7CE720754B5337A35510BAD743D1F83`。

**① 代价**：(b) 的墙钟上限从 1.2s 抬到 9s，但实际 1–2s 就采到并自行收尾，整门比原来（2×1.2s + 采样开销）大约多 1s；产品代码未改，游戏内运行时零开销、零内存增量。

**② 风险**：预算抬高后若搜索真的退化到「再也找不到胜线」，门禁仍会红，只是晚约 9s 红 —— 判据没有被放宽，去掉的只是「必须在一台足够快的机器上跑」这个隐含前提；(b) 不再跑满整段预算，长预算下的自然收尾路径由 (a)（自然到期）与其它搜索门禁覆盖；`stopQuickSolve()` 依赖 `finishQuick` 发布最优线这一既有行为（本门现在直接盯着它，一旦被改坏会红）。

**③ 对 mod 的影响**：无 —— 只动测试代码；面板输出、自动出牌节奏、搜索结果、评分、候选枚举、与其它 mod 的兼容性全部不变。

**环境备注（给下次跑门禁的人）**：2026-11-15 02:29 实测本机 **4.5 ms/节点**（B121 记录为 0.38），当时后台有 3 个 `cloudmusic` + `audiodg` 与 DSH 自身占 CPU（瞬时总占用 40%，24 逻辑核；MuMu/MAA 未运行）。跑验收门禁前建议先关掉占 CPU 的音频/模拟器，否则真实 1200ms 快搜只查得到 ~280 个节点，与 B184 口径不可比。

**文档**：本段 + PLAN.md（bug 表 B186 行、收尾状态与 core 计数）+ PLAN-P6.md（P6-96）+ PLAN-WORKLOG.md（2026-11-15 B186 段）+ TESTING.md（S171 说明补预算与提前收尾）。

## B187 — 沙盒隔离收口：窗口内不许执行任何非战斗副作用（闪退根因 + 防复发规则）

> **状态：已实施（2026-11-15，T1–T6 落地）。** 方案与实施结果见 `docs/PLAN-ISOLATION.md`（目标 G1–G5、任务 T1–T6、审计清单、规则 I1–I3、验收 5 步、第 11 节结果）。门禁 S255/S257 按验收阶段约定（user m00732）留待用户实机跑。用户 m01082「要修复隔离」、m01098「给我隔离修复的计划，不要让类似的bug出现，沙盒隔离好和战斗无关的东西。给我写进计划」。

**起因（闪退）**：2026-11-15 21:22:02 游戏崩溃，`hs_err_pid17280.log` 记录 `EXCEPTION_ACCESS_VIOLATION (0xc0000005, reading 0x0)`，native frame 指令 `48 8b 01`（`mov rax,[rcx]`，rcx=0）。栈：`DirectAudioDevice.nFlush` ← `DirectDL.flush` ← `YuAiMusic.stop()` ← `YuAiController.evaluate` ← `HealthSoundPatch$OnHealAmountShowEffect.Postfix` ← `AbstractCreature.heal(int,boolean)` ← `HealAction.update()` ← `Driver.runEndOfTurnQueue` ← `Driver.drain` ← `Driver.endTurn` ← `Legal.apply` ← `Solver.expandCore` ← `Session.expandAndRecord` ← `Gameplay.tick` ← `SolverMod.receivePostUpdate`。

**根因（比 B185 更深一层）**：沙盒窗口里仍**真执行**雨爱的 `heal` 后置钩子，它的 `evaluate→play()` 会开真实 `javax.sound` 线。B185 只做「窗口结束把沙盒新建的 Clip 关掉、把字段写回」，于是：①窗口内 evaluate 先 `stop()` 了**实机 clip L**（listener 关掉 L、字段置 null）→ ②`play()` 新建 clip A 写入字段 → ③窗口结束关掉 A、把**已关闭的 L 写回** → ④下一窗口 `stop()` 对 L 调 `flush()`（雨爱 `stop()` 在 flush 前不查 `isOpen()`）→ DirectSound 空指针 → JVM 崩溃。`liveStops=0` 说明字段已被 A 覆盖，B185 的「快照 vs 现值」比较看不见快照已死。solver.log 同秒有 `closed 1 sandbox javax.sound clip(s) ... put the live reference back`，下一窗口崩溃。

**结论**：B185 修的是叠音症状，没有隔离执行；「从外部 close 别人的 Clip」反而把残留升级成崩溃。**B185 段的「已兜住」按此订正。**

**修法（分层，已实施）**：
- **L1 崩溃防护**：`AudioQuiet.end()` 写回前查快照 `isOpen()`，已关闭就写 `null` 并 WARN/计数；`closeQuietly` 每步前查 `isOpen()`；end() 整体 try/finally。
- **L2 真隔离**：`begin()` 把受保护静态的 Clip 换成**惰性替身**（`java.lang.reflect.Proxy` 或 `SandboxClip implements Clip`）：`isOpen/isActive/isRunning→true`，`open/start/loop/stop/flush/close/drain→空操作`，位置/长度→0，其余返回类型正确的空值。⇒ `play()` 因 `currentClip != null` 早退，**一次线都不开**；`stop()/pause()` 只打替身。end() 还原快照；mod 在窗口内自己置 null 时**也还原**（推翻 B169 的「释放保持释放」）。
- **L3 顺序**：`AudioQuiet.begin()` 必须从 `World.java:336` 挪到 `StaticWatch.afterInstall()`（:348）之后，否则 StaticWatch 会把替身当实机值快照并写回；`end()` 仍保持在 `beforeUninstall()`（:383）之前。
- **L4 审计**：把非战斗副作用列成表（javax.sound / 音乐 / 音效 / HTTP metrics / 文件与存档 / 统计成就 / 鼠标输入 / 发光 / VFX / 动画骨架），逐项标「现有替身 / 门禁 / 缺口」。
- **门禁**：S255 改新契约（替身语义、不写回已关闭快照、StaticWatch 不记录替身），新增 S257 真实 `DirectAudioDevice` 冒烟（无设备则 SKIP 且不计通过），全部做反向验证。

**实施结果（2026-11-15）**：
- **T1 崩溃守卫**：`AudioQuiet.end()` 写回前查快照 `isOpen()`，已关闭就写 `null` 并计 `closedSnapshot` + WARN `audio quiet: writing null instead of putting a dead line back (B187)`；`closeQuietly` 的 stop/flush 每步前查 `isOpen()`（close 仍无条件执行）；`end()` 整体 `try/finally{snaps.clear()}`，单点异常不再打断后续字段与 StaticWatch。
- **T2 惰性替身**：新增 `mirror/SandboxClip.java`（`implements Clip` 单例，`isOpen/isActive/isRunning` 恒 true、生命周期方法全空操作并计 `swallowedCalls()`、位置/长度 0、格式用实机 `getFormat()` 缓存、`getControl` 返回私有 `SandboxFloatControl extends FloatControl`）。`AudioQuiet.begin()` 把每个非 final 的 Clip 型静态换成替身（**连当时为 null 的也换**——雨爱 `stop()` 会置 null，不补这步下一节点 `play()` 又会开真线），已关闭的替身按 stale 处理。`end()`：替身→写回快照（快照已死写 null）、窗口新建 Clip→close、`null`→**也写回快照**（推翻 B169，`released` 语义从「保持释放」改为「恢复」）。
- **T3 顺序**：`World.install()` 的 `AudioQuiet.begin()` 挪到 `StaticWatch.afterInstall()` 之后；`uninstall()` 仍先 `end()` 再 `beforeUninstall()`。`StaticWatch.snapshotValueForTest(Field)` 供门禁钉住「快照到的是实机 clip」。
- **T5 门禁**：S255 重写为 **37 条**（窗口 A–E：替身契约/play 早退/位置格式增益控件/StaticWatch 顺序/窗口自建 Clip 被 close/覆写还原/释放还原/快照已死写 null/liveStop 检出/stale 清理 + 雨爱可读性）；新增 **S257**（**2 条**，真实 `AudioSystem.getLine` 开 8kHz/8bit/mono 静音 → loop → stop → close；无设备只 WARN `S257 SKIP: …`，**0 条通过**，不伪装成绿）。
- **落地物**：jar **1,287,415 B**、sha256 `E85332F8C3B26B6B44C8CAF00CF04D9F2E3E1421013692557C38BB8E51E4A08D`，已装 `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar`。
- **实现偏差**：①`FloatControl` 是抽象类，必须写子类（计划写的「构造器 public」不成立）；②`adoptFormat` 首版误用实例字段导致编译失败，改为经 `INSTANCE.format`；③null 字段也塞替身（比计划严，原因见上）。门禁本轮**未跑**，S255/S257 与反向验证待用户执行。

**防复发规则（已写入 SANDBOX-MECHANISMS 维护规则）**：**I1** 门禁用假对象不能证明真资源安全（S255 用 Proxy 假 Clip 全绿而实机崩溃，即反例）；涉及原生资源的修复至少一条断言走真实资源，否则显式声明「门禁不覆盖原生层」。**I2** 窗口内只换引用，不碰实机对象的生命周期（stop/close/flush/dispose）；跨窗口写引用必须成对 begin/end 且写回前过 `isOpen()/isValid()` 校验。**I3** 沙盒默认不执行展示层：新增 mod 交互先问「这是战斗状态吗」，不是就隔离并登记。

**① 代价（实测）**：每窗口每字段一次 `Field.get`+`Field.set`（本机命中 1 个 `Clip` 静态，微秒级）；替身单例、无每节点分配、无新线程；`isOpen()` 只在 `end()` 每字段一次 JNI，不在搜索热路径；字节码 +7,341 B（1,280,074 → 1,287,415 B）。**② 风险**：替身返回值类型若与真实 Clip 有差异，mod 做类型断言可能抛异常（按表逐项给正确类型 + 用真实雨爱类跑 S255）；推翻 B169 会让「mod 主动置 null」的引用被还原（有意，窗口内外必须一致；替身连「字段为 null」也占位，窗口内 mod 看到 `currentClip != null` 是**有意**的——这是 `play()` 早退、G1 成立的前提，窗口外字段按快照恢复）；回滚＝还原 World 两处挂点与 AudioQuiet 改动后重编，T1/T2/T3 相互独立。**③ 对 mod 的影响**：只在沙盒窗口内生效，窗口外雨爱照常播放/停止；面板、自动出牌、搜索结果、评分、候选枚举、其它 mod 共享对象均不变；可见变化是窗口内不再有雨爱声音，日志 audio quiet 摘要改为「替身拦截 N 次 + 写回 N 次」。

**已知边界**：只保护静态字段里的 Clip（实例字段/集合里的抓不到）；不覆盖绕过 Clip 直接开 `SourceDataLine` 的 mod；真实 `DirectAudioDevice` 的极端时序崩溃门禁覆盖不到（S257 只做一次开关冒烟，无设备时 SKIP＝0 通过）；实机 clip 在窗口内被别的线程真实关闭时只能写 null、不能阻止关闭（音乐会停，属正确语义但观感有差）；详见 PLAN-ISOLATION.md 第 9 节。

**残余症状与决定（2026-11-16，user m00495「那不管这个bug」）**：
- **现象**：计算期间雨爱「正常放一段 → 中间卡一下 → 从 0 帧重放」。**机制**：窗口内 `AbstractCreature.heal` 的后置钩子仍会进 `YuAiController.evaluate`；替身 `getFramePosition()` 恒 0、`stop()` 把字段置 null、窗口结束再写回实机引用，于是每次窗口/血线切换都从 0 帧重新 `play()`。实机 clip 本身从未被关闭（`putBack` 持续增长、无 `dead`/`blind` 行），所以是**观感问题、不是崩溃**。
- **已评估、均未采纳**：**A 入口守卫**（`@SpirePatch(clz=yuaimod.YuAiController.class, method="evaluate", paramtypez={AbstractPlayer.class})` + `@SpirePrefixPatch`，`World.anyInstalled()` 时 `SpireReturn.Return(null)`）——技术上成立（`evaluate` 自身没有别人的 postfix，返回即跳过方法体），但要**编译期依赖创意工坊雨爱 jar**、还要自检补丁时序（类若在 MTS 注册前加载则贴不上），用户决定不做；**B 设备层**（自定义 `javax.sound.sampled.spi.MixerProvider`，窗口内 `AudioSystem.getClip()` 给惰性替身）——不引用雨爱、对全 mod 通用，但要手写 Mixer/Line/Clip 三层委托并全局劫持 `AudioSystem`，风险与工作量都过大；**C 绕开 `heal`（直接改 `currentHealth`）或替换其方法体**——会丢 `onPlayerHeal`/`onHeal`/`onNotBloodied` 语义（Magic Flower ×1.5、Mark of the Bloom 禁疗、红骷髅翻转加力量），属静默降低模拟真实性，**禁止**；而且沙盒里跑的是真游戏代码，`HealAction`/药水/遗物/power 自己会调 `heal`，只改我方调用点也绕不开。
- **为什么不能从 `heal` 侧拦截**：MTS 无运行期禁补丁 API；`@SpirePrefixPatch` 返回 `SpireReturn.Return(null)` 也拦不住别的 mod 的 postfix（postfix 插在每个 RETURN 前，含 prefix 新增的 RETURN）；`@SpireInstrumentPatch` 在 ordering −1、早于 postfix 插入，看不到这些调用点。
- **结论**：**B187 保留现状**（窗口内 Clip 替身：不崩、不残留、不真开线），把「计算期间雨爱重放」记为**已知边界、不修**。若将来要修，走 A 路线，需用户明确批准加编译期依赖。

## B188 — 药水在「该早喝」的局面被排到格挡之后（中间节点把喝药记成纯损失，先喝的分支进不了前沿）

> **状态：✅ 已实施并安装，待用户实测**（user m00201 已批准计划；user m00300 定「不写门禁，我自己可以测试」⇒ 实现按 docs/PLAN-P7.md 的 T1–T8 落地，T1–T6/T8 完成，T7 由用户实机验，清单见 PLAN-P7 §5）

**起因（user m00001，原话）**：「sts1-engine-solver自己看日志为什么打地精大块头敏捷药水没有第一时间喝？先喝敏捷药水再打出幽灵铠甲能少扣2血」。追加：user m00149 指出同瓶数下先喝 HP 更多、分数理应更高；user m00167 给出参考实现（`C:\Users\YOKI\Desktop\CombatSolver-main`，按瓶数分档）；user m00201 要求「每档 beam 适当加宽以适应喝药水」并写进计划。

**现象（solver.log，2026-11-16 10:43，物理行）**：
- 1786 行 plan（14 步）：第 10 步「幽灵铠甲」、第 11 步「敏捷药水」、第 12 步「防御」。
- 1788 行 trace：步 10 `t=3 b=10`；步 11 `t=3 b=10 mhp=60`；步 12 防御 `with b=17` / `without b=15`；最终 with loss=17、without loss=19。
- ⇒ 药水的 +2 敏只落在第 12 步的防御上；若在第 10 步之前喝，这 2 点格挡会加在幽灵铠甲上，正好少扣 2 血。
- 1779 行 B174 探针 `replayOk=false`、1778 行 replay 在 step 12 报「重锤+ [Bludgeon] -- not-settled: a screen is open (DEATH)」。**T1 已定性**：`Solver.planText()`（`Solver.java:3240-3249`）只按 `this.plan` 下标拼 `Step.action.label`；`Result.plan` 是 `Solver.replay`（`Solver.java:2069`）按 action 列表逐个执行后生成的 `List<Step>`（`Step.action` 就是被回放的那个 Action）⇒ 打印顺序 == 回放顺序，不存在错序。1778 行的错位是回放走到第 12 步时前序药水/格挡改变了手牌与能量、真实动作变成「重锤+」，且该步开着 DEATH 屏导致 `not-settled`。

**根因（代码级）**：
1. `Legal.actions()`（`Legal.java:84-125`）候选顺序＝卡牌 → 药水 → 结束回合；入口 `Solver.candidates`（`Solver.java:2936`）。
2. 叶子计分只有「已花」：`Solver.java:4474-4487` 的 `int n2 = Math.max(0, startPotionHp - choice.potionHp)`，`Solver.score(n2s, n2, …)` 按 `POINTS_PER_HP=2000` 扣分（一瓶 `potionHp=9` ⇒ 18000 分）。**喝下那一步整层瞬降 18000，而「继续持有」不加分** ⇒ 同一父节点下先喝的分支无条件落后。
3. 落后就被淘汰：`defer()` 上限 `DEFERRED_LIMIT=2000`（`Solver.java:48`）、`sortDeferred` 按 rank→score→digest（`:3418`）、每轮提升上限 `MAX_PROMOTIONS_CAP=60`（`:60`），先喝的分支既进不了 beam 也进不了停车场。
4. 次因：喝 0/1/2 瓶的线共用同一条一维前沿（`portfolioProfiles()`，`Solver.java:680-692`），「喝 N 瓶」这一档从未被独立评估。
5. 净效应：搜索系统性把喝药往后推，推到「没有别的可打」的回合才执行；此时能吃到加成的只剩后面的防御牌。

**修法（已实施；细节见 docs/PLAN-P7.md §11）**：
- **A 对称记账（实现口径已更正）**：计划原式 `+ choice.potionHp * POINTS_PER_HP` 经推导是错的——设起手药水价值 `S`、叶子仍持有 `H`，`n2 = S - H` 是已花，原式等于把叶子药水项记成 `-(S-H)+H = 2H-S`，喝一瓶会让分数再掉 `2v`（9 HP 药 ⇒ 36000 分），比原来的 18000 更糟。实际实现：**档内叶子不计药水价**（`Solver.java:4581` 的 `int n2Score = this.deferPotionCost ? 0 : n2;`），药水成本只在档间合并时由 `netHpLoss`/`compareRoutes` 收一次；`provenOptimal` 仍要求真实 `n2==0`。效果：喝药那一步不再瞬降，先喝提前拿到的格挡立刻体现为更高分。
- **B 分档搜索**：新增 `sts1-engine-solver/src/main/java/sts1solver/search/PotionTieredSession.java`（档循环，`startTier()` :137、`advance()` :271、`tierDone()` :247、`finishAll()` :228）。0 瓶 → 1 瓶 → … → K 瓶（K=min(根可用药水,5)），档内 `maxPotionUses` 剪枝（`Solver.java:4498-4500`，`candidates` 之后、`defer` 之前用 `Solver.withoutPotions` 过滤）；每档独立 `new Session` + `planWidening`；预算 0 瓶档 40%、其余 60% 按 2^(K-i) 归一，档内仍按 `{35,25,25,15}` 切 beam；某档 win 且（`provenOptimal` 或净扣血 ≤0）直接停；结果逐档合并，净扣血并列**少喝优先**（`PotionTieredSession.better` :218）。
- **每档 beam 加宽**（user m00201 第 1 条）：`tierBeam(base,tier) = tier<=0 ? base : min(Limits.beam(), base + ceil(base×step×tier/100))`（`PotionTieredSession.java:110`，`step` 默认 25）；仍过 `effectiveBeam()`（堆守卫可压到 `GUARDED_BEAM=48`）；`tier>0` 时同步放大停车场 `max(Limits.deferred(), 2000, 32×beam)` 与回放名额 `max(60, beam/2)`（`Session#setCaps`），否则宽出来的名额被上限吃掉（本局 deferred=2000/promoted=229 已顶格）。
- **B174 探针改口径**：档位模式（`limits.length > 1`）下 `Session.finish()` 不再跑「剥药回放」，由 `PotionTieredSession` 直接打 `potion tier: baseline tier=0 loss=…`；单档（无药或回滚开关）仍保留旧探针。
- 三条入口都接上了：`Solver.solvePortfolioInternal`、`PortfolioSession`（改为纯委托壳）、`QuickSession`（narrowDeep 分支）。阻塞式 `Solver.solve`（昂贵战斗：boss 房或怪 maxHealth≥200，`Gameplay.java:2332`）**本轮不接档位**，仍是单前沿 + 阻塞收尾，已记入 PLAN-P7 §8 边界。
- 回滚键 `settings.txt search.potionTiers=off`（另有 `search.potionBeam`、`search.potionBeamStep=25`）。

**验收（不写门禁，user m00300 自己实测；详见 docs/PLAN-P7.md §5）**：① `plan:` 步序与 `trace:` 逐步骤序一致；② 喝药那一步面板预计损失不再陡差约 9 HP；③ `potion tier:` 出现 tier=0..K 且 tier=1 不含第 2 瓶；④ `beam=` 随档位约 +25%/档、不超 `limits`；⑤ 各档累计用时单调、末档 ≤ 总预算；⑥ 主诉场景（地精大块头、敏捷药水 + 幽灵铠甲）药水步号 < 幽灵铠甲步号且预计损失 15（现状 17）。

**结果**：✅ 已构建安装——`mod\sts1-engine-solver.jar` 1,293,146 B，sha256 `9C8E9CA5A8E39686BC240D34C91E0658BB7DC6B1B91D84FD73F2BB4DA200AEC4`，已装到 `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar`。**本轮未跑 `tools/run.ps1`**（user m00732 自行实测；T7 由用户验收主诉场景：药水步号 < 幽灵铠甲步号、预计损失 17→15）。

**① 代价**：每档一次 Session 构造（几十 ms 级，档数 ≤ 6，最多 6 个档）；档内叶子省掉一次药水价乘加（比旧口径更便宜）；宽档节点数随 beam 线性增长，但预算按份额切分 ⇒ 总时长不增，首档 40% 先出结果；内存峰值≈最宽一档（受 `effectiveBeam` 堆守卫约束），档间顺序执行不叠加。
**② 风险**：档内不扣药水价 ⇒ 中间层「喝不喝」的边界会移动（终局净扣血口径不变，面板数字仍含药水价）；药水按 `PotionBook` 战略价计价，不等于真实价值，该留 Boss 的药可能被提前喝；`Solver.solve` 阻塞路径未接档位（见边界），极端昂贵战斗仍可能重演「先甲后药」；单档回滚路径已保证 `markTiered()`/`deferPotionCost()` 不生效，旧口径完整保留。回滚＝`search.potionTiers=off`。
**③ 影响**：面板 plan 与预计损失（药水更早出现）、自动出牌顺序、`solver.log` 新增 `potion tier:` 行、`settings.txt` 新增三键；与其它 mod 无交互（不改镜像/static 还原）。

**已知边界**：药水价值取 `PotionBook` 战略价（≈9–10 HP），不是本场真实边际价值；不建模「留到下一场」的跨战斗价值；开屏类药水随机流仍按实机 seed；§9 的 planText/replay 顺序疑点 T1 已定性为回放期 DEATH 屏所致、非打印错序，不并入本 bug 的修法；**阻塞式 `Solver.solve`（boss 房或任一怪 maxHealth≥200，`Gameplay.java:2332`）不接档位**，仍是单前沿。

**结论（T1 补充）**：`planText()` 的打印顺序就是回放执行顺序（`Solver.java:3240-3249` 遍历 `this.plan`，`Result.plan` 由 `replay` 按 action 顺序生成），所以「先甲后药」是搜索选线结果、不是打印错位；本 bug 的修法针对搜索本身。

**文档**：`docs/PLAN-P7.md`（方案与任务表）、`docs/PLAN.md` 板块表 P7 行 + 在跟条目 B188 行、`docs/PLAN-WORKLOG.md` 2026-11-16 小节。

### B188 / P7b 后续改造：cell 顺序（rung 外层）+ 药水档提示（2026-11-16，已构建安装，待实测）

**来源**：user m00331 要求「面板模仿 STS2 mod 提示当前在算用几瓶药水的世界线」＋「顺序改成第一档 beam 的 0→n 瓶跑完再第二档 beam」＋「先给计划」；计划＝`PLAN-P7` §12；实施指令＝user m00001「完成sts1-engine-solverP7 后续改造计划」。

**做法**：
- 执行单元从「药水档」改为 **cell=(beam rung, 药水档)**，严格 rung 外层：`r=0: t=0..K` → `r=1: …`。每 cell 一个独立 `Solver.Session`（单 beam、`planWidening(null)`、`limitPotionUses`、`deferPotionCost(true)`、`markTiered()`；t>0 放大停车场/回放名额）。
- 预算与节点按 `rungShare`（`PORTFOLIO_SHARES` 按 ladder 实际长度归一）× `potionShare`（0 瓶 40%、其余 60%×2^(K-i)/Σ）双权重切分；剩余 ≤300ms 收尾 `time`；早停＝任一 cell `win && (provenOptimal || netOf<=0)`。
- 新提示：`PotionTieredSession.hint()`（`药水 ≤1 瓶（2/3）· beam 28（2/4 档）· 0 瓶基线 净损 15`）并入 `phase()`，`QuickSession.potionHint()` 接到 `Gameplay.java:728-737` 状态行；深度搜索经 `PortfolioSession.hint()` 同样可见。`worldlineText()` 未改。
- 回滚：`settings.txt search.potionOrder=tier`（旧顺序）／`search.potionTiers=off`（总回滚，单 Session + 完整 ladder、无 cell 日志无提示）。

**验证状态**：`tools/build.ps1` BUILD OK；jar 1,295,185 B / sha256 `D6D9D262DD586CBB27C0018ABC1E4EE5A7029210913D345F6599FC5B62A24A6F`，已装入游戏 mods。**未跑门禁、未启动游戏**（user m00300/m00732），验收由用户实测（`PLAN-P7` §12.6 六条）。

**① 代价**：Session 数 (K+1)×R（K=2、R=4 ⇒ 12 vs 改造前 3），每 cell 从 root 重展开 ⇒ 同预算节点/耗时约 +5~15%，每档略浅；总时长仍受总预算封顶；内存峰值＝最宽单 cell。
**② 风险**：0 瓶宽档被推到最后，预算不足时最终线可能变差；早停更难触发、用时更贴上限；面板/状态行文案变化若有整行断言会红；每 cell 一次 `World.install/uninstall`（最多 12 次）；`potionOrder=tier` 分支无门禁覆盖。回滚见上。
**③ 影响**：面板/状态行新增药水档与基线净损提示；自动出牌顺序与预计损失仍由合并结果决定（净扣血优先、并列少喝）；`solver.log` 新增 `potion cell:` / `potion tiers: cells=` 汇总（旧 `potion tier:` 仅单档/旧顺序）；`settings.txt` 新键 `search.potionOrder`；不改镜像、不动 static 还原、与其它 mod 无交互。

**文档**：`docs/PLAN-P7.md` §12（状态）/ §13（实现记录与偏差）、`docs/PLAN.md` P7 行 + 安装行、`docs/PLAN-WORKLOG.md` 2026-11-16「B188 / P7b 实施」段。

### P7c：药水档上限改读游戏数据（user m00172，2026-11-16）

**现象/要求**：用户指出「药水并不是上限只有3，改成读取游戏内的数据」；旧实现唯一的写死上限是 `PotionTieredSession.java:53` `MAX_TIERS=6`，档数由 `min(potionCountUsable, 5)` 决定，于是「当前手里 2 瓶」就只能出 3 档。

**做法**：
- 读游戏：`Solver.potionSlots(World)` 读 `AbstractDungeon.player.potionSlots`（`javap` 确认为 `public int`），≤0 回退 `potions.size()`；`world.install()/finally uninstall()`，异常返回 0。
- 定档：`PotionTieredSession.tierCount(usable, slots, budgetMs, rungs)` —— usable≤0 ⇒ 0（保持单档旧路径）；否则 `cap=max(usable, slots)`，再按 `(budgetMs/300)/rungs-1` 的预算地板收窄，返回 `min(cap, byBudget)`。即档位上限＝本局药水槽容量（Potion Belt/mod 可 >3；Entropic Brew/Alchemize 可在战斗中补药）。
- 可见性：hint 追加「，槽 N」（`药水 ≤1 瓶（2/3，槽 3）· beam 28（2/4 档）· 0 瓶基线 净损 15`）；日志新增 `potion tiers: usable=… slots=… K=… tiers=…`，`portfolio ladder` 行追加 `potionSlots=/usablePotions=`；`worldlineText()` 未改。
- 订正旧注释：`potionCountUsable` 原 javadoc 说「limit 高于可喝瓶数只会重复上一档」，该结论在会生成药水的战斗里不成立，已改写。

**验证状态**：`tools/build.ps1` BUILD OK；jar 1,295,693 B / sha256 `4A155B622BF12691603A3D6ED4A5A7DBCE92A07FAA989070F6D8E5435077B644`，已装入游戏 mods。**未跑门禁、未启动游戏**，验收由用户实测（`PLAN-P7` §14）。

**① 代价**：构造多一次 `World.install/uninstall`；cell 数 `(K+1)×R` 随槽位增长（槽 3/持有 2 ⇒ 16 vs P7b 的 12），同预算每 cell 更浅；总时长仍封顶；内存峰值＝最宽单 cell。
**② 风险**：槽多药少时高档可能重复低档结论，浪费其份额（几何衰减，最坏 10~15%）；`potionSlots` 异常值靠回退与预算地板兜底；提示/日志文字变化撞整行断言会红；`potionTiers=off` 时字段为 0、不显示槽位。回滚：`potionTiers=off` / `potionOrder=tier` / 构造器改回 `min(usable,5)`。
**③ 影响**：面板显示「（i/K，槽 N）」，K 随本局槽位；自动出牌与预计损失口径不变；`solver.log` 新行；无新 settings 键；不改镜像、不动 static 还原、与其它 mod 无交互。








### P7d：顺序改回档外层 + 重复计算审查（user m00561，2026-11-16）

**现象/要求**：用户 m00561 ①「还是改回先计算0药水的1到4档再计算到n药水的0到4档。现在这样太慢了。」②「检查是否有重复计算，比如药水1第一档和药水2第一档会不会重复计算」。

**结论（重复计算审查）**：
- 同档内相邻 beam：tier 布局不重复（同一 `Solver.Session` 上 `planWidening`/`widenToNextRung` 复用前沿）；cell 布局**重复**（`startCell()` 每个 cell 都 `new Solver.Session(root, beam…)` + `planWidening(null)`，同档 4 个 beam 各从根展开一次，再乘 K+1 档）——这是“太慢”的主因。
- 药水 1 瓶档 vs 2 瓶档：**不是同一搜索**，不能互替。三处参数不同：`limitPotionUses(k)`、起始 beam `tierBeam(base,t)=base+ceil(base*25%*t)`、`t>0` 的 `setCaps(max(2000,32*beam), max(60,beam/2))`。
- 档间“不含药水”的根前缀：被重搜 K+1 次（结构性重复）。彻底消除需在同一 Session 上逐档放宽 `maxPotionUses` 并复用前沿，等于改 `Solver.java:4526` 的剪枝与停车场结算，本轮不做，记为已知边界。

**做法**：
- `Limits.potionOrderBeam()`（`Limits.java:273`）默认 `beam` → **`tier`**；只有 `beam|cell|cells|beam*` 进 cell 布局。默认回到 `startTier()`：档外层，档内 10→28→64→128 原地加宽。
- `PotionTieredSession.hint()`（`:683`）不再要求 cellMode（仅 `limits.length<=1` 时为空），tier 布局 beam 后跟「（加宽 r/R 档）」；`phase()`（`:656`）与 `QuickSession.phase()`（`QuickSession.java:271`）把 hint 前置，面板提示在两种顺序下都可见。
- cell 路径（`startCell()` :288 / `cellDone()` :465 / `nextCell()` :222）原样保留，供 A/B 与回滚使用。

**验证状态**：`tools/build.ps1` BUILD OK（JDK 21.0.11、`--release 8`、555 entries）；jar 1,295,807 B / sha256 `05D00831DD59261954FB63E659204680F8C32DED5095B740BA2587FF4067D973`，已装入游戏 mods。**未跑门禁、未启动游戏**，验收由用户实测（`PLAN-P7` §15）。

**① 代价**：默认 Session 数 16 → 4，同预算每档更深、档内无重复展开；总预算/节点上限公式不变；内存峰值仍为最宽一档的树。
**② 风险**：P7b 观察点（rung 外层、`potion cell:` 行）默认消失，需 `search.potionOrder=beam`；tier 布局早停按档（proven/净损≤0），无逐 cell 早停；档间前缀仍重复；状态行多出「加宽 r/R 档」，整行断言会红。回滚：`search.potionOrder=beam` / `search.potionTiers=off` / 默认值改回 `beam`。
**③ 影响**：面板/状态行继续显示药水档提示（现覆盖 tier 布局）；预计损失与自动出牌口径不变；`solver.log` 默认回到 `potion tier:` 行，`potion tiers:` 走 `tiers=` 分支，`potion cell:` 仅 opt-in；settings 键不变；不改镜像、不动 static 还原、与其它 mod 无交互。

**文档**：`docs/PLAN-P7.md` §15、`docs/PLAN.md` P7/B188/安装行、`docs/PLAN-WORKLOG.md` 2026-11-16「B188 / P7d」段。

**发布（1.0.2，2026-11-16）**：按 user m00678「那就不要做了，上传github和创意工坊」，P7e（单遍遍历按瓶数分配 beam 名额）与「K 收窄到可用瓶数+1」**两个提速方案均不实施**，B188 以 P7b/P7c/P7d 的现状定稿发版。版本号 1.0.1 → 1.0.2（`resources/ModTheSpire.json`、工坊 description）；构建 jar 1,295,807 B / sha256 `BD3FFAE6DABF3D6232D857FBE6A0BEB7AE28B2833EDF4402889D35C4EF7DB021`（与 P7d 同尺寸，只差版本号字符）；已装入 `D:\Steam\steamapps\common\SlayTheSpire\mods` 与 `D:\mods-workshop\sts1-engine-solver\content`，工坊条目 **3839053516** 上传成功（输出 `Successfully updated workshop item!`，visibility 仍 private），GitHub 已推送 `master`。产品代码与 1.0.2 前一致，无性能/内存变化；回滚＝重传 1.0.1 的 jar 与 `D:\mods-workshop\sts1-engine-solver\config.json.bak-20261116`。详见 `docs/PLAN-WORKLOG.md` 同日「发布 1.0.2」段。

### B188 结束回合里开的选牌屏（计划妥当）答完后，回合永远收不了尾（2026-11-17，用户 m00750 / m00778 / m00820）

- **现象**：带「计划妥当 / 留一手」（Well Laid Plans，走 `RetainCardPower`）的战斗里，模拟能出计划、也能把选牌屏答上，
  但答完之后这一局就再也不能行动：之后每一次「结束回合」都失败，日志刷 `lastRefusal = "the end turn did not finish the turn
  (turn stayed 3, combatOver=false)"`；`A3 promote: cannot rebuild a deferred path ... at step N on 结束回合` 单场 2942 条，
  而失败的动作在 `Legal.describe` 里只能兜底成 `the card at hand index -1`。用户实测口径（m00750）：
  「计划妥当这张牌确实有问题。选牌的牌可能都有问题。我已经实测有问题了。」
- **取证（引擎字节码，不是推测）**：`javap` 反编译 `desktop-1.0.jar`：
  1. `AbstractRoom.endTurn()` 只做 `applyEndOfTurnTriggers()` → `addToBottom(ClearCardQueueAction)` →
     `addToBottom(DiscardAtEndOfTurnAction)` → 三个牌堆 `resetAttributes()` → `addToBottom(new AbstractRoom$1())` →
     `player.isEndingTurn = false`。**它自己从不把 `turnHasEnded` 置 true。**
  2. `AbstractRoom$1.update()` 才 `addToBot(new EndTurnAction())`（→ `GameActionManager.endTurn()` 把 `turnHasEnded` 置 true）
     + `addToBot(new WaitAction(1.2f))`（1.2 秒，SELECTION_DELTA=0.05 下约 24 帧）+ 非 skipMonsterTurn 时 `MonsterStartTurnAction`。
  3. 真机消费「结束回合按钮」的地方：`AbstractRoom.update()` 先 `if (!isScreenUp) actionManager.update();`、再 `player.updateInput()`，
     然后**在 isScreenUp 判断之外** `if (player.isEndingTurn) room.endTurn();`；`AbstractPlayer.updateInput()` 尾部（偏移 1015-1022）
     在 `cardQueue.isEmpty() && !hasControl` 时置 `endTurnQueued=false; isEndingTurn=true`。
  ⇒ **`endTurnQueued/isEndingTurn` 是一个 latching 的状态量**，语义与「动作队列非空」同级：它没被消费，这一回合就没跑完。
- **根因**：`Driver.endTurn()` 先跑 `runEndOfTurnQueue()`（B151）；计划妥当的保留屏正是在这个结束回合块里打开的 ⇒ 走 B151 早退，
  只把 `player.endTurnQueued=true` 记住，**此时 `gam.endTurn()` 还没被调用，`turnHasEnded` 仍是 false**，返回 awaitingSelection。
  答完屏后 `resolveSelection() → drainAfterAnswer() → drain()`，此时动作队列已空、`turnHasEnded=false`，而 `Driver.isIdle()` 只看
  五个队列 + `currentAction` + `usingCard` ⇒ **判定 settled 直接 break**，`Driver.java:510-544` 的 pressNow 分支（本该
  `runEndOfTurnQueue + room.endTurn()`）一次都没执行 ⇒ 那个 press 永远挂在玩家身上，根状态变成「turn 3 且还欠一次结束回合」，
  之后每步都从这里重放失败。帧长（用户猜的「帧数不对」）不是原因：B181 之后 `simDelta` 默认就是 `SELECTION_DELTA=0.05f`（`Driver.java:931`），
  出牌与结束回合同一帧长；真正的漏点是把「队列空」当成了「这一段做完了」。
- **修法（只改 `drive/Driver.java`，约 +38 行，不放宽任何判据）**：
  1. `isIdle(GameActionManager)` 在 `turnTransitionFinished` 之后加 `if (endTurnPressWaiting(gam)) return false;`；
  2. 新增 `private static boolean endTurnPressWaiting(GameActionManager)`：`gam == null || !endTurnQueued(gam) || combatOver()` → false，
     否则按 `Log.allow("endturn-press-waiting", 4000L)` 限速打一行 `an end turn press is still latched (turnHasEnded=... turn=... actions=.../...)`，返回 true；
  3. 效果：答完屏后的 drain 不再假 settled，继续走到 pressNow → `runEndOfTurnQueue()` → `room.endTurn()` → `AbstractRoom$1` →
     `EndTurnAction` 才把 `turnHasEnded` 置 true → 怪物回合 → 回合号推进；`settleBlock()` 同理会返回 `action-busy` 而不是把半个回合当成可合并状态。
  判据是从「队列空」换成引擎自己的「press 未消费 = 未完成」——不是放宽，而是收紧到与真机一致；`combatOver()` 时豁免（B128 的逃跑动画另由 escapePending 管）。
- **代价 / 风险 / 影响**：`isIdle` 只有两个调用点（`drain()` 的 :427 与 `settleBlock()` 的 :845），不新增对象、无每节点开销，只在 press 挂着时多一次判空读取。
  风险一处：若某个动作在没有真结束回合的情况下残留 `endTurnQueued`，该步会从「假 settled」变成「按毫秒上限 stalled」（日志可见，不会死循环）。
  面板、路线、评分、Digest、自动出牌节奏、worker 指纹都不变，因为改动只影响「什么时候认为这一帧已经静止」。
- **验证**：`tools/build.ps1` BUILD OK，jar 1,296,005 B / sha256 `D780675CEB49A0FDD5A9786F2A8AB63809B88039A665FC883FE43436382B6A42`；`tools/install.ps1 -SkipBuild` INSTALL OK（已替换 `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar`，源/目标哈希一致）。
  **本轮未跑 `tools/run.ps1`**（用户 m00732 自行实测、m00778 要求减少门禁）。用户侧验收：打一场带计划妥当的战斗，看选牌答完后回合是否推进、
  `A3 promote ... on 结束回合` 是否归零。
- **教训**：沙盒里判断「这一段做完了没有」，要问引擎自己的规则（这个按钮被消费了吗、`turnHasEnded` 置了吗），不能问「我的队列空了吗」——
  队列只是引擎状态的一部分，latch 型的输入状态与它在同一逻辑层。另一个教训：`Legal.describe()` 对 `Action.endTurn()` 的兜底文案是
  `the card at hand index -1`，把「结束回合失败」写成了「找不到牌」，直接误导了上一轮定位（待办：给 endTurn 动作一个可读名字）。
### B189 结束回合途中开的选牌屏被当成「引擎拒绝」，整步作废、计划截断（2026-11-17，用户 m01083 / m01101 / m01110）

**现象**（`solver.log` 2026-11-17 00:42，大颚虫 JawWorm，HP 47/66，`pw=Retain Cards1`）

- `00:42:15.181 WARN end turn refused: the end turn did not finish the turn (turn stayed 1, combatOver=false) steps=0 settled=false stalled=false awaitingSelection=true`
- `00:42:23.293 WARN plan replay failed at step 4: sts1solver.search.ReplayException: the line cannot be replayed: the card at hand index -1 -- not-offered: the engine does not allow it (the end turn did not finish the turn ...)`
- `00:42:23.294 ... (replayed 4 of 21 steps, first failure at 4 on 结束回合（选：打击）); it is not offered as a win`
- 已装 jar 为 B188 构建（sha `D780675C…`），确认不是旧代码残留。

**根因**（逐点复核）

1. `Driver.endTurn()` 先 `gam.endTurn(); room.endTurn();`（`Driver.java:1404-1409`），随后 `drain(4000, …)`；保留屏在结束回合块里开，drain 在 `Driver.java:399-445` 里一旦 `isAwaitingSelection()` 就 `awaitingSelection=true; break` —— 这是正确行为。
2. B125 的重试 `Driver.java:1435` 只看 `GameActionManager.turn == turnBefore`，不看 `awaitingSelection`，于是再跑一次 drain；第二次在 i=0 就撞到同一块屏并立刻返回（`steps=0`），把第一次的统计**覆盖**掉。
3. `Driver.java:1444` 的拒绝判据同样只看 `turnAdvanced` ⇒ `refused=true`、`lastRefusal="the end turn did not finish the turn (turn stayed 1, combatOver=false)"`。
4. `Legal.applyOrRefuse`（`Legal.java:291-299`）把 `refused` 变成 `ReplayException(... -- not-offered ...)`；`Legal.describe()`（`:482-487`）在无 cardName/cardId 时兜底成 `the card at hand index -1`，于是「等答屏」被写成「找不到牌」。
5. B188 的 `endTurnPressWaiting()`（`Driver.java:848-858`）只在 press 已按下且没人消费时生效；本路径 press 已被 `room.endTurn()` 消费，所以 B188 修不到这里（这也是为什么 B188 之后仍有本 bug）。

**「两次 drain」定性**：不是真凶。只跑一次同样误判（第一次 drain 的结论就是「屏开着 + 回合没涨」）。它是 B125 为「慢帧不是卡死」加的保险，删掉会丢掉「第一次被 `COMPLETION_STEPS=4000` 截断时再补一截」的能力，只留下空转和统计覆盖两个副作用。

**修法**（只改 `drive/Driver.java` + `search/Legal.java`，约 8 行）

- `Driver.java:1435`：`if (!drained.awaitingSelection && !combatOver() && GameActionManager.turn == turnBefore)` —— 屏开着不再空转。
- `Driver.java:1444`：`if (drained.awaitingSelection) { Log.warnEvery("endturn-waiting", 30000L, "end turn stopped on a pick screen (turn X -> Y): the answer finishes it"); } else if (!drained.turnAdvanced) { …原 refused 块… }` —— 待答不是拒绝；discardMissing 记账保持在两分支之后。
- `Legal.describe()`：开头 `if (a.isEndTurn()) return "结束回合";`。

**代价 / 风险 / 影响**

- 代价：零新增常驻对象、无每节点开销；每步多一次布尔读。
- 风险：真正卡死的结束回合会晚一步暴露（下一次结束回合才 refused，日志照旧 `end turn refused`，不静默）；若某屏答完引擎仍不推进，本回合会被记为成功。回滚＝还原上述三处。
- 影响：面板/计划里「结束回合（选：X）」可走完；`A3 promote ... on 结束回合` 应归零；Digest、评分、live 路径、自动出牌节奏不变；不触碰任何 mod 类（不重演 B185/B187 的替身写回风险）。

**验证**：`tools/build.ps1` BUILD OK（141 源文件、555 entries、1,296,149 B、sha256 `28FCFF2C685CB1DCE5BDE5DAA0062540021CF8DCA86E24389C6439EF0F18E439`）；`tools/install.ps1 -SkipBuild` INSTALL OK。**未跑 `tools/run.ps1`**（用户 m00732 自行实测、m00778 减少测试）。验收＝带计划妥当打一场，看答屏后回合是否推进、`on 结束回合` 失败是否归零。既有 S182（`Probe.java:7294-7359`）只覆盖「屏先开着再按结束回合」，未覆盖「屏在结束回合途中才开」，本轮按用户要求不新增门禁。

**教训**：`turnAdvanced == false` 只说明「这一回合还没结束」，不等于「引擎不许结束回合」；判定拒绝前必须先问「是不是有块屏在等我答」。

### B190 选牌的「不选 + 确定」在计划里无法表示（计划妥当保留屏 / 任何 canPickZero 屏）（2026-11-17，用户 m01149）

**现象**：计划妥当打出后，结束回合途中开的保留屏被计划当成「没有屏」——该步 label 就是「结束回合」，没有「（选：X）」。自动出牌走到屏前打一行 `selection open with no planned answer: kind=HAND candidates=... autopilot=true` 然后停手（用户原话「自动出牌没有确定这个动作」）。同一局另有 103 条 `answering screen 1 (HAND) resumed work that did not settle`，那是 B188/B189 的结算问题，与本条不同。

**引擎事实（javap，不是推测）**

1. `RetainCardPower.atEndOfTurn(boolean)`：回合末、手牌非空、无 Runic Pyramid、无 Equilibrium ⇒ `addToBot(new RetainCardsAction(owner, amount))`。
2. `RetainCardsAction.update()`：`duration == 0.5f` 时 `AbstractDungeon.handCardSelectScreen.open(TEXT[0], amount, false, true, false, false, true)` 并 `addToBot(new WaitAction(0.25f))`；其余帧若 `!wereCardsRetrieved` 则遍历 `selectedCards.group` 置 `retain=true`（非虚无）、`player.hand.addToTop(c)`，最后 `wereCardsRetrieved = true`。⇒ 选 0 张合法，且动作自己收尾。
3. `HandCardSelectScreen.open(String,int,boolean,boolean,boolean,boolean,boolean)` 的赋值顺序：`numCardsToSelect=iload_2`、`anyNumber=iload_3`、`canPickZero=iload 4`、`selectionReason=iload_1`、`upTo=iload 7`；canPickZero 为真时把确认按钮 `isDisabled=true; enable()`，否则 disable。⇒ 保留屏 = anyNumber=false / canPickZero=true / upTo=true / numCardsToSelect=1。
4. `GamblingChipAction.update()`：`open(TEXT[0], 99, true, true)` ⇒ anyNumber=true / canPickZero=true，空集本来就合法。
5. `GridCardSelectScreen` 没有 canPickZero 字段；能空确认的只有 `anyNumber` 与 `isJustForConfirming`。

**根因：三处缺口**

1. `search/Action.java:250-271 withChoice` 的 suffix 只在 `chosen` 非空时追加「（选：X）」，空答案的 label 与「没有屏」同形。
2. `Gameplay.java:2688-2699 submit` 以 `choice` 非空为 `planPendingChoice` 的条件 ⇒ 空答案从不进 pending ⇒ `answerLatentSelection`（:3305-3344）判 `planned.isEmpty()` 后只打日志不代答。
3. 实机执行：`Driver.resolveSelectionLive`（:2203-2262）`if (cardIds == null || cardIds.isEmpty()) return resolveSelectionLiveHeuristically();` —— 空答案会被当成「猜最优一张」（B158 明令禁止）；`fillGridSelection`（:3288-3304）结尾 `return !screen.selectedCards.isEmpty()` 让 GRID 空答案永远失败。

（重放路径 `Solver.answerForReplay` 本来就能吃空答案：`resolveSelectionByNames` 有 `askedEmpty` 分支。缺的是「它认为这一步该有屏」——旧代码用 `a.choice` 非空当判据。）

**修法**（四个文件）

- `search/Action.java`：`withChoice(chosen, display, uuids, prints, kinds)` 新增 5 参重载 + `chainCount(String)` / `clickScript(raw, screens)`。有屏就按屏数生成点击脚本：「选：X → 确定」/「不选 → 确定」/多屏「1) …  2) …」；无屏保持旧文案。
- `search/Solver.java:945-953 actionFor`：把 `choice.screenKinds` 传进 `withChoice`；「choice 空但 screenKinds 非空」时也造分支（旧代码直接复用原 action，等于把答案丢了）。`answerForReplay`（:2377）的 `expected` 改为 `choice 非空 || screenKinds 非空`。
- `Gameplay.java`：新增 `pendingScreens` / `pendingChoiceKinds`（由 `Action.screenKinds` 段数来）；`submit` 条件 = 「choice 非空 || screenKinds 非空」；`choiceScreenCount()` = `max(choiceSegments, pendingScreens)`；`answerNextScreen` 对空段且 kind 已知的屏调 `Driver.resolveSelectionLiveEmpty(kind)`；`choiceSegmentText` 空段写「不选」；新增 `plannedScreens()` 并让 `answerLatentSelection` 与 tick 的「没有答案」停止条件只在屏数也是 0 时触发；`answerSelectionStep` 的空答案改走显式确定（不再落到启发式猜牌）。
- `drive/Driver.java`：新增 `resolveSelectionLiveEmpty(String kindHint)`（kind 不符 / REWARD / 屏不允许空选一律 `refused`，否则 `answerLiveSelection(空, "不选")`，走既有 fill → consumed → closeCurrentScreen 校验）与 `allowsEmptyPick(kind)`（HAND=`canPickZero`，GRID=`anyNumber || isJustForConfirming`）；`fillGridSelection` 空集按同两字段放行。

**对赌博筹码（用户第 2 问）**：不碰 `Action.moved`/`selectionWithMoves`、`Driver.moveToEndOfHand`、`picksFor` 的匹配顺序（uuid > print > id+name > id > name），也不碰 `selectionOptions` 的分支枚举（B161 的 anyNumber 全子集原样）。赌博筹码是 canPickZero=true 的屏，B190 之前它「选不选」这条分支在实机答不进去，修完是顺带修好，不是回归。

**代价 / 风险 / 影响**

- 代价：面板行每条多一次 split + 字符串拼接（不在节点循环里）；新增 2 个 static 字段；无每节点开销。
- 风险：label 文案变化（「（选：X）」→「（选：X → 确定）」）只影响人读与日志比对，不参与判断；计划记录的屏种与实机不符时拒绝而不是猜（宁停不猜）；某屏答完引擎仍不开下一步时，B189 的 `endturn-waiting` 仍然写日志，不静默。回滚＝还原四个文件的改动。
- 影响：面板/日志能看出「结束回合（不选 → 确定）」并被自动执行；搜索、评分、Digest、路线、live 路径与其它 mod 完全不变（不触碰任何 mod 类）。

**验证**：`tools/build.ps1` BUILD OK（141 源文件、555 entries、1,297,569 B、sha256 `9A4D5945D00793676AE5EBDF5B097D8FB9EE8824B94ADD35DF373A30E99A036D`）；`tools/install.ps1 -SkipBuild` INSTALL OK（已装 `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar`，源/目标哈希一致）。**未跑 `tools/run.ps1`**（用户 m00732 自测、m00778 减少测试）；S133/S214/S215 都不覆盖「空答案的执行」，按 m00778 不新增门禁。验收＝带计划妥当打一场，看保留屏是否被自动「不选 → 确定」。

**教训**：「答案为空」和「没有答案」是两件事，代码里不能用同一个空字符串表示。计划结构里已经有逐屏种类（`Action.screenKinds`），屏的个数一直是已知的——缺的只是没人把它当成答案的一部分。

### B191 选牌点击脚本印的是卡牌 id 而不是中文名（2026-11-17，用户 m01447）

**现象**：面板计划行写成「生存者（选：PiercingWail → 确定）」——同一份计划里其它步骤的牌名都是中文（「打击」「防御」），只有 B190 新加的「选：X → 确定」这一层露出了英文 id。

**根因**：`search/Action.java` 的 `withChoice` 里 `text = display 为空 ? raw : display` 本来已经拿到面板名称，但 B190 新加的 `suffix = （clickScript(raw, screens)）` 传的是 `raw`（`choice.choice`，卡牌内部 id），于是标签从「选：尖啸」退化成「选：PiercingWail」。

**修法**：`clickScript(String shownText, String raw, int screens)` 逐屏优先取 `shownText` 的段（面板名称），该段为空才用 `raw` 同下标的 id 段兜底；`withChoice` 调用改成 `clickScript(text, raw, screens)`。空答案屏（计划妥当保留屏）仍输出「不选 → 确定」。

**代价 / 风险 / 影响**

- 代价：每步多一次字符串 split（面板行构造，不在节点循环里），无新增字段。
- 风险：纯文案，无行为变化；若某个屏的 display 列表为空（新出现的屏种类），仍会显示 id——可见，不静默。回滚＝把调用改回 `clickScript(raw, screens)`。
- 影响：面板与日志的点击脚本统一显示中文名；搜索、评分、Digest、live 执行路径（`picksFor` 仍按 uuid/print/id/name 匹配）完全不变。

**验证**：BUILD OK（141 源文件、555 entries、1,297,615 B、sha256 `39BF1B640A378B4F652348FF8C8C233C9A36E45FCD5CE54F8EC17702829CA82E`）；INSTALL OK（已装游戏 mods 目录，哈希一致）。未跑门禁（用户 m00732 自测 / m00778 减少测试）。

**教训**：面板文案必须有且只有一个「显示名」来源；把 id 当文案用，平时看不出，一旦 id 与名称不同（`PiercingWail` 与「尖啸」）就暴露。

### B192 亡灵契约师计算后实机奥斯提血量增加（沙盒没隔离「静态字段指向的活体」）（2026-11-17，用户 m00005）

**现象**（用户 m00005 原话：「sts1-engine-solve最新日志亡灵契约师计算时沙盒没有正确隔离导致计算后奥斯提血量增加，给我一个计划修复这个bug以及类似bug。」）

- 点「计算」后，实机奥斯提（Osty）的血量/上限比计算前高（模拟里的回血写到了实机对象上）。
- 求解器日志 `C:\Users\YOKI\sts1-engine-solver\solver.log`（本次 161 行）只有一次计算，第 76/80/82/86 行是 settle 序列（`P hp=56/62`、`pw=TheNecrobinderPlus:OstyPower-1`），**没有任何一行记录 Osty 的 currentHealth/maxHealth**——求解器根本不看它。
- `solver.log:23` 镜像自检原文含 `NOT mirrored: … 300 static object ref(s) …`，other-objects 里明确有 `TheNecrobinderMod.OstyManager#osty : Osty`；全文没有 `static watch: the sandbox wrote mod static` 行 ⇒ `StaticWatch` 从未判出这次写入。

**根因（三层同时缺）**

1. **镜像不镜像静态对象引用**：`mirror\ModStatics.java:29-32` 明写边界「只镜像 primitive/String/enum/Color；指向可变对象的 mod 静态是 CLONE 候选，不在这里处理」。`OstyManager.osty` 是只由静态字段持有的 `AbstractCreature`，不在 player/monsters 对象图 ⇒ 安装时只替换引用，`heal/gainMaxHP` 直接写在实机实例上；`World.uninstall` 只还原引用，HP 增量留在实机。
2. **`StaticWatch` 的浅快照被 32 格截断**：`mirror\StaticWatch.java:240` `while (k != null && k != Object.class && fs.size() < 32)`。Osty 自己 7 个字段 + `AbstractCreature` 前 25 个（到 `hb_w`）正好用满 32 格，`currentHealth`（第 28）/ `maxHealth`（第 29）**永远不进快照**；`isDying/isDead` 反而在 ⇒ 只有血量静默泄漏、死亡标志被回写。加上 `:122` 的采样规则（前 4 次安装 + 每 128 次一次），这类字段在绝大多数窗口连比对都不做。
3. **`Digest` 看不见它**：`mirror\Digest.java:34-80` 只覆盖回合/队列、玩家、四堆、每怪 hp/block/意图 ⇒ 搜索前后 digest 相同，`Gameplay.java:428-435` 的「局面变了」判据放行。
4. 还有一层**模拟不忠实**（不只是污染实机）：`World.fork()`（`World.java:112-158`）只对 `Kind.CLONE` 逐叉深复制，`VALUE` 共享 ⇒ 当前一次「奥斯提回血」会改变同一轮其它分支的起点。

**为什么可以按 CLONE 修（已验证）**：`TheNecrobinderMod.Osty extends AbstractCreature`；`OstyManager` 只有 `private static Osty osty`、`get()/isAlive()/clear()/summon(int)`，`osty` 非 final。扫 `TheNecrobinderPlus.jar` 常量池里 `LTheNecrobinderMod/character/Osty;` 命中 12 个类，逐个 `javap -p` 后**没有任何类声明 Osty 类型字段**（只有参数/局部变量或 `OstyManager.get()`）⇒ 沙盒里替换静态后，牌/动作/patch 拿到的都是同一个克隆。

**修法**（计划已出：`docs/PLAN-MOD-OBJECTS.md`，T1–T10；用户 m00273 否掉特判，故不写 mod 类名表）

- 新增 `mirror/StaticEntities.java`：每次 `World.captureLive()` 前盘点非 final 静态对象引用，把「是引擎战斗基类（`AbstractCreature/AbstractPower/AbstractCard/AbstractRelic/AbstractPotion/AbstractOrb` 之一）且从世界图不可达」的值用现有 `Statics.registerDynamic(f, Kind.CLONE)` 动态注册成克隆根。判据零 mod 类名，任何 mod 的同类字段自动命中。
- `World.copyContainer`（`World.java:673`）对容器元素中的战斗基类做深拷贝（用主 Cloner），消除静态容器元素被实机共享的同类泄漏。
- `StaticWatch` 快照改为「按名字收状态字段（`currentHealth/maxHealth/currentBlock/isDying/isDead/isEscaping/halfDead/lastDamageTaken/block/energy/hp/maxHp/gold`）跨层级优先，候选先收 96 个再挑 32 个（`MAX_FIELDS=32` 不变），战斗基类静态每窗口采样」。
- `Digest` 对每个动态克隆根追加密片段 `sc=<类简称>#<状态指纹>`，接进前后比对与 `PlanCache` 键。
- 不维护 290 条逐条人工分类表：分类由判据实时得出并打摘要日志。

**代价 / 风险 / 影响**（详见 PLAN-MOD-OBJECTS §6）

- 代价：采样窗口多约 2.9 万次反射读（估 0.5–2 ms/窗口，非采样窗口不变）；capture/fork 每次多克隆一个 Osty（估 0.05–0.3 ms/叉，须用 `cloneCostReport()` 实测）；digest 多一行。
- 风险：第三方 patch 若缓存了实机 Osty（反射），克隆后会出现两套实例 ⇒ 由 T5 身份审计启动即报，报出就降级为 watch-only 并记已知边界（不放宽判据掩盖）；digest 新增行会让旧计划缓存失效一次。
- 影响：亡灵契约师计算不再改实机 Osty 血量，Osty 参与战斗时路线更准；其它 mod 在无 Osty 时零开销、写保护只变严；面板/自动出牌文案与节奏不变；不改 `Kind` 枚举与 `World` 协议，旧配置/旧计划可读。回滚＝删掉 `World.captureLive()` 首行的 `StaticEntities.sync()`、还原 `StaticWatch.snapshot` 与 digest 段。

**实施记录（2026-11-17，用户 m00356「改」）**

- 新增 `src/main/java/sts1solver/mirror/StaticEntities.java`（约 23.8 KB）：`sync()` 由 `World.captureLive()` 首行调用，读 `ModStatics.watchCandidates()` 的每个非 final 静态字段，值签名（类名）不变则只做 1 次读/字段，变化或每 512 次调用才走世界图 `walk`；`walk` 从 `AbstractDungeon.player`/五堆/遗物/药水/充能球/怪物/actionManager 出发收身份集合（depth≤8、对象≤20000），对「战斗基类实例 + 不可达」的字段 `Statics.registerDynamic(f, Kind.CLONE)`；final 字段持有不可达实体时只告警（`skippedFinal`，无法换引用）；`mirrorEntityRoots`（默认 auto）、`mirrorEntityIgnore=<owner#field>` 两个 settings 键。
- `mirror/Statics.java`：新增 `dynamicSpecs()`（返回拷贝）。
- `mirror/World.java`：`captureLive()` 首行 `StaticEntities.sync()`；新增 `copyContainer(Cloner,Object)` 重载（List/Set/Map 逐元素，战斗基类走主 Cloner 克隆、其余共享），`captureLive()`/`fork()` 的 CONTAINER 分支改用它；`readBack()` 仍用浅拷贝（那时读的是沙盒容器，不需要再克隆）。
- `mirror/Digest.java`：`ofCurrent()` 末尾追加 `StaticEntities.fingerprintLine()`（每个动态克隆根一段 `sc=类简称#FNV-1a`，最多 4 个，按类名排序；无动态根时返回空串）。`PlanCache` 经 `Digest.stable` 自动进键，未改 `PlanCache`。
- `mirror/StaticWatch.java`：`Watch.entity = StaticEntities.isBattleType(f.getType())`，采样条件加 `|| !w.entity`（战斗实体每窗口采样）；shape 4 先收 96 个候选字段，再按 `STATE_NAMES` 名字优先挑 32 个，剩余字段补满。
- 构建：`tools/build.ps1` **BUILD OK**，`mod/sts1-engine-solver.jar` 1,307,167 B，SHA-256 `606FF30B773DF85D22FF4A1F20B7E1FD0CCBB6F72DA1640512E8DC7381414AA5`（142 源文件，`--release 8`，557 entries）。
- 安装：**INSTALL OK**（`D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar`，哈希与构建物一致）。踩到的坑：`install.ps1` 的独占打开探针把 DSH 沙箱的「访问被拒绝」误报成「游戏正在运行（locked by a running game）」，实际游戏进程并不存在 —— 该脚本在沙箱内跑需要完整磁盘权限，否则永远报锁；可改为「先试写 `.probe` 再看异常是否 SharingViolation」来区分（本次未改脚本，只在需要时用完整权限跑）。
- **未做**：T1 证据文件（`evidence/` 目录尚不存在，证据仍在本文与 PLAN-MOD-OBJECTS §1）、T8 的 `holders=N` 计数（只实现了 final 持有者告警，没有「同一对象被几个静态字段持有」的统计）、T9 用户实测。
- 与计划的偏差：计划写「标量补到 128」，实现仍保持 `MAX_FIELDS=32`（先收 96 再按名字挑 32），因为 128 会让每个普通静态对象的快照成本翻倍；若实测仍漏字段再调。
- 回滚：删 `World.captureLive()` 首行 `StaticEntities.sync()` 即回到「只还不换内部状态」的现状；`copyContainer(cloner,…)` 改回 `copyContainer(v)` 回退深拷；删 `Digest` 的 `fingerprintLine()` 兼容旧缓存键。

**验证**（实现完成，待用户实机）：G1 计算前后实机 Osty 四值不变；G2 沙盒掉血/回血能改变路线；G3 连续三次计算不累积；G4 血量变化使计划缓存失效；G5 任何静态对象写入最迟在 uninstall 被点名；G6 每个战斗基类静态值都有明确归类（克隆/已可达/非战斗基类），无第四类。日志要看的行：`mirror entities: N static root(s) newly owned …`、`mirror entities: skipped … static-final …`、`digest … sc=Osty#…`。按用户 m00732/m00300：用户实机验收，不跑 `tools/run.ps1`、不新增 Probe 门禁。

**教训**：「静态字段被还原了」不等于「状态被隔离了」。镜像只保证引用，引用指向的活体的内部状态是另一个隔离层；快照按声明顺序截断会在父类字段多的情况下静默丢字段——白名单按名字收，不要按顺序数。另两条：**判据要看声明类型，不要只看当前值**（null 是最常见的漏网形态）；**自己加的指纹段必须两边同时生效**，否则诊断工具会变成误报源。
**第四轮（2026-11-17 13:40，用户 m00694「计算前奥斯提 1，计算后六十几」）**

- **诊断（反编译实证，不是推测）**：`javap -p TheNecrobinderMod.character.Osty` ⇒ `public class Osty extends com.megacrit.cardcrawl.core.AbstractCreature`；`OstyManager` 只有一个字段 `private static Osty osty`（非 final）；`HealOstyAction.update()` 的反编译直接是 `OstyManager.isAlive() ? OstyManager.get().restore(amount)`。所以模拟里每次治疗/召唤都通过 `OstyManager#osty` 直接写**活体**。
- **根因 1（漏网）**：`StaticEntities.isBattleEntity/isBattleType` 只认 `AbstractMonster`、`AbstractPlayer`、`AbstractPower/Card/Relic/Potion/Orb`。奥斯提是**直接继承 `AbstractCreature` 的宠物**，六个基类一个都不命中 ⇒ 该字段从第一轮起就没被注册，`newly owned` 里从来没有它，`static watch` 只能事后追认。日志实证：`solver.log:40` 的 8 个 newly owned 有 4 个 mintySpire 模板、2 个 `declared …, currently null`，没有 osty；`:78-79` 仍是 `wrote … OstyManager#osty (object) and the contents were put back`。
- **根因 2（追回也不可靠）**：`StaticWatch.snapshot` 收集实例字段时带 `all.size() < 96` 的**层级截断**，`Osty`（约 30 个字段）+ `AbstractCreature`（70+ 个）在父类中途就被截断；后面的「按名字优先收 32 格」只能从已经收进来的字段里挑，`currentHealth`/`maxHealth` 若落在截断之后，put back 根本没覆盖它们——这解释了为什么有时「put back 了」血量还是变了。
- **修法（本轮 3 处）**：
  1. `StaticEntities`：实体判据加 `AbstractCreature`（覆盖 `AbstractMonster`/`AbstractPlayer` 与一切 mod 宠物），另加按字段名的兜底 `hasHealthState(Class)`——同时声明非 static 的 `int currentHealth` 与 `int maxHealth` 就按实体处理（`ClassValue` 缓存，每种类型只扫一次）。`isBattleEntity` 同样加该兜底，非 `AbstractCreature` 的 mod 血条对象也能被接管。
  2. `StaticWatch`：新增 `ClassValue<Field[]> INSTANCE_FIELDS`（整条继承链一次性收集、缓存）；快照改为「全量收集 → 按名字挑 32 → 其余补齐」，**收集不再截断**，选择仍限 32。
  3. `LiveGuard.capture()`：除 player/怪/动态根外，遍历 `ModStatics.watchCandidates()`，把当前值是 `AbstractCreature` 的静态字段也纳入监视（标 `unowned static owner#field`，按身份去重）。这样**没被接管的活体**再被写，下一行日志会直接点名，而不是只说「奥斯提变了」。
- **代价**：①`walk` 现在会注册奥斯提根 ⇒ 每次抓取/分叉多克隆一个宠物对象（`Osty` 约 30 个实例字段，spine 的 `Skeleton`/`AnimationState` 在 `ClonePolicy` 里是共享的，不深拷纹理）；②`hasHealthState` 每种类型一次反射扫描（<0.1ms）；③`StaticWatch` 每类型第一次快照要收集整条继承链，之后走缓存；④`LiveGuard` 每次 install 多 ~395 次静态字段读（微秒级）。
- **风险**：①宠物克隆后，mod 里用 `==` 比较「当前奥斯提」的地方会看到沙盒对象——这是隔离的正确语义，但若 mod 把 `Osty` 身份写进跨战斗存档字段（本轮反编译未发现），会表现为「面板数字对、实机不跟随」，可用 `mirrorEntityIgnore=TheNecrobinderMod.OstyManager#osty` 回退；②`hasHealthState` 兜底可能把「恰好有同名血量字段的非战斗对象」当实体克隆，代价是多一次克隆，不会写实机；③`LiveGuard` 现在把未接管的活体也纳入比对，游戏自身在窗口外的合法变化不会被看到（capture/verify 同在窗口内）。
- **对 mod 的实际影响**：沙盒里的奥斯提成为独立实例，模拟治疗/召唤/死亡只改沙盒副本；实机 `OstyManager#osty` 的引用和内部状态在 install/uninstall 全程不被触碰（若仍变，`live guard` 会打印 `unowned static OstyManager#osty hp=1/… -> hp=…`）。求解结果层面，奥斯提血量现在参与沙盒状态与 `sc=` 指纹，路线评估会用「当前真实血量」而不是被模拟污染过的血量。
- **产物**：`mod/sts1-engine-solver.jar` 1,313,224 B，SHA-256 `EAAEA9C8CF7EB83B9798BE7DD77F4C1EA4878755F7CE5993CEB46B798EFACD0F`，`INSTALL OK`（143 源文件，561 entries）。
- **验证（用户实机）**：计算前后实机奥斯提血量/最大血量不变；日志应出现 `newly owned [… OstyManager#osty (Osty)]`；不应再出现 `static watch: … OstyManager#osty`；若仍出现 `live guard … CHANGED`，按行里的 label 继续查。
**第五轮（2026-11-17 13:33 之后，用户 m00827「动画没有隔离，其他都做好了」）**

- **现象与证据**：`solver.log` 13:32:28.004 `WARN quick search: SPINE STATE CHANGED by the search (2) | player MyCharacter anim=Idle t=2.030627 ts=1.0 skel=640.0,453.33334 flip=false,false tint=1,1,1,1 -> player … t=8.182082 …`。两条差异**只有 `t=` 变**，`anim/ts/skel/flip/tint` 全同，`t` 增量（6.15s / 7.69s）≈ 搜索墙钟（13:32:20→13:32:28）。因此本次日志只能证明**搜索级审计把实机正常播帧当成了沙盒写入**，不能证明沙盒没写 spine，只能说明这段代码没有按窗口看。
- **结构缺口（为什么必须修，不是只改日志）**：`mirror/ClonePolicy.java:104-106` 把整个 `com.esotericsoftware.spine.` 前缀按引用共享，克隆生物（含奥斯提）的 `skeleton`/`state` 与实机是**同一个对象**；`javap -p -c TheNecrobinderMod.character.Osty` 里确有 `state.update(F)`、`state.apply(Skeleton)`、`skeleton.setToSetupPose()`、`setPosition/setColor/setFlip/updateWorldTransform`、`stateData.setMix`。所以模拟里任何一次动画调用都会直接改实机，只是这次日志恰好没调用到。这属于 M52 同族：**静态引用被接管了，但克隆体内部仍指着实机对象**。
- **修法（零 mod 类名，只用引擎基类与引擎自带 API）**：
  1. 新增 `mirror/SpineIsolation.java`：`isolate(AbstractCreature copy)` 在克隆体字段复制完成后，把 `skeleton` 换成 `new Skeleton(live)`（引擎拷贝构造，`javap` 实证逐根 Bone 深拷），把 `state` 换成 `new AnimationState(live.getData())` 并逐 track `setAnimation(i, e.getAnimation(), e.getLoop())` + `setTime(e.getTime())` + `setTimeScale(e.getTimeScale())`；`stateData` 共享（它是配置：mix 时长，不是每只生物的运行时状态）。
  2. `mirror/Cloner.java`：两处 `return copy` 之前加 `if (copy instanceof AbstractCreature) SpineIsolation.isolate(...)` —— 判据是引擎基类，不出现任何 mod 名。
  3. `mirror/SkeletonFingerprint.java`：新增 `spineSignature(AbstractCreature)`（anim/ts/skel/flip/**pose=骨骼位姿哈希**/tint，**不含时钟**）与 `poseHash(Skeleton)`（FNV-1a 混每根 Bone 的 x/y/rotation/scaleX/scaleY），`line()` 改为「角色 类名 t=… + spineSignature」；新增 `clockOnly(diff)`（把 ` t=<num>` 抹平后比较）。
  4. `mirror/LiveGuard.java`：生物的窗口签名在 `hp/b/dead` 后追加 `spineSignature`。窗口是同一 tick、没有渲染帧，时钟不会动；一旦动画名、位姿或 tint 被写，下一行就会点名。
  5. `Gameplay.noteSkeletonDiff`：先剔除 `clockOnly` 的差异；全被剔除时记 `INFO … spine state unchanged (N animation clock(s) advanced by live frames)`，仍有真实差异才 `WARN SPINE STATE CHANGED`。**没有放宽判据**：anim/ts/skel/flip/pose/tint 任一变化仍是警告。
  6. `mirror/Statics.java` 新增 `isDynamicClone(Field)`；`mirror/StaticWatch.java` 的 `all()`/`afterInstall()`/`beforeUninstall()` 跳过它 —— 动态克隆根在窗口里的值是**我们自己的克隆**，此前每次 uninstall 都报一次 `wrote mod static TheNecrobinderMod.OstyManager#osty … put back`，还会把实机引用写回去，属假告警兼竞争。
  7. settings 键 `mirrorSpine`（默认 `copy`；`share`/`off`/`0`/`关` 回退旧的共享行为）。
- **代价**：每个 fork 的每只生物多一次 `new Skeleton(live)`（player 骨骼约 100 根，分配 Bone/Slot 数组）+ 一次 `new AnimationState(data)` + 每 track 一次 `setAnimation`；按 1852 fork/搜索、player+2~3 怪估算，克隆段（上一轮 profile `clone=644ms`）预计 +3%~8%，整个搜索（5.6s）约 +0.5%~1%。`LiveGuard` 每窗口多读一次位姿（约 100 根 × 5 个 float），与原有 hp 读同量级；`poseHash` 只在 capture/verify 时算，不进每节点热路径。
- **风险**：①克隆体的动画在抓取瞬间定格，之后与实机脱钩——这是隔离的正确语义（沙盒不渲染），但依赖动画时间做判定的 mod 逻辑在模拟里会看到恒定的 `t`；②mod 若把 `Skeleton`/`AnimationState` 另存到自己的静态字段或别的长生命周期对象里（不经 `AbstractCreature.skeleton/state`），仍会共享——本轮未发现，且窗口签名能抓到 anim/位姿/tint 变化（时钟被有意排除，所以「只推进时钟」这种写不会被点名），记入已知边界；③`AnimationState` 新建时不会重放 mod 在旧 state 上加的 listener，若 mod 依赖 listener 推进战斗（本轮反编译未发现 Osty 这么做），模拟会少跑回调；④`Skeleton(Skeleton)` 拷贝构造依赖游戏自带 spine 版本，已 `javap` 实证存在且逐根深拷。
- **对 mod 的实际影响**：沙盒里的生物动画不再改实机姿态/时钟；奥斯提的 `skeleton/state` 为沙盒所有，模拟动画只动副本；`static watch` 不再对 `OstyManager#osty` 误报/回写；面板、自动出牌、计划缓存键均不变。
- **产物**：`tools/build.ps1` **BUILD OK**（144 源文件，`--release 8`，562 entries，1,318,133 B），`tools/install.ps1` **INSTALL OK**，落盘 `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar` 1,318,132 B，sha256 `FAD91E8DAEA532741A4C64CD8686ACF6D66B197FACF3881BD97BE108C15D9B0E`。
- **验证（用户实机，按 m00732 不跑门禁）**：①计算后实机动画仍在播、姿态正常（不是定格画面）；②日志出现 `spine isolation: N detached spine state(s)`（N 应随节点数增长）；③不再出现只差 `t=` 的 `SPINE STATE CHANGED`，改为 `spine state unchanged (N animation clock(s) advanced by live frames)`；④不再出现 `static watch: … OstyManager#osty`；⑤若仍出现含 `anim=`/`pose=`/`tint=` 差异的 `SPINE STATE CHANGED` 或 `live guard … CHANGED`，按行内 label 继续查。
- **回滚**：`settings.txt` 写 `mirrorSpine=share`（无需重编译）；或删 `Cloner` 两处 `SpineIsolation.isolate` 调用。
**第三轮（2026-11-17 13:35，用户 m00661「上一轮修改后还会变」）**

- **先澄清时间线**：`solver.log` 最后写入是 13:17:26（`Get-Item` 实测），而第一轮 jar 是 13:0x 装、第二轮 jar 13:20 才装。13:17 那次跑的是**第一轮**代码；游戏进程若不重启，装进 `mods` 的新 jar 不会生效（class 已加载）。所以「还会变」目前只能证明第一轮没修好，不能证明第二轮无效。
- **新增度量（本轮代码）**：`mirror/LiveGuard.java` —— 每次 `World.install()` 前记下**实机** player、当前房间全部怪、所有动态克隆根的状态；`World.uninstall()` 第一步（早于 `StaticWatch.beforeUninstall()` 的任何还原）比对。变化即 `WARN live guard (B192): the window CHANGED N live object(s): <名字> hp=34/40 b=0 -> hp=40/40 b=0 …`；无变化则每 60s 记一行 `live guard (B192): N clean window(s)`。**只报告、不修复**，因为它是用来定位残余写入的判据，不是掩盖问题的补丁。
- **代价**：每个节点 install 时多 1 次 player + 房间怪 + 动态根（本轮 6 个）的反射读签名，uninstall 再读一遍；按 1137 节点估算 <1ms 总量级。
- **风险**：无状态改动，不改任何游戏对象；如果实机对象在窗口期间被游戏自身合法改动（理论上不会，窗口内是同一线程同步执行），会产生一次 `live guard` 警告——那是需要看的证据，不是误伤。
- **安装物**：`mod/sts1-engine-solver.jar`（第三轮）sha256 `EF3C8D826BD905EF068E2D1D653060543854EC2473D68D9313B65F590E7C07F5`，`INSTALL OK`。
- **下一步（用户）**：彻底退出游戏再启动，打一场带奥斯提的战斗，点计算，然后把 `solver.log` 末尾给我：若出现 `live guard … CHANGED`，行里会直接点名是哪个对象被谁改了；若只有 clean 行而血量仍变，说明变化发生在计算之外（例如自动出牌），再按那条线索查。
**第二轮（2026-11-17 13:20，用户 m00567「还是会改变实际游戏数据」＋「显示局面已变化不给计划」）**

- **新证据**（`solver.log` 13:17 那次，118 行）：
  - `:70` `WARN static watch: the sandbox wrote mod static TheNecrobinderMod.OstyManager#osty (object) and the contents were put back; it is now watched on every install.`；`:71` 汇总 `1 write(s) to live-shared mod statics put back: [TheNecrobinderMod.OstyManager#osty (object)]` ⇒ 抓取时它没被克隆，直到模拟里召唤才被 `StaticWatch` 事后追回。
  - `:109` `WARN quick search: LIVE STATE CHANGED DURING SEARCH | firstDiff@590 expected[… d=00] actual[… d=00|sc=DreamCatcher#…,NSoul#…,Necronomicon#…]` ⇒ `expected` 是抓取前的 digest（尚未注册动态根），`actual` 是注册后 ⇒ **误报**由我们新加的 `sc=` 段自己制造，面板据此不给计划。
- **根因（两条，均已在第一轮代码里验证）**：
  1. `StaticEntities.walk` 只用「当前值 `isBattleEntity(v)`」判，`OstyManager#osty` 在战斗开始时是 `null` ⇒ 直接 `continue`，从不按**声明类型**注册；等到 mod 在沙盒里召唤，字段仍是 live-shared，写入落到实机（对象图里超出 32 格快照的部分，例如 powers 列表，`put back` 覆盖不到）。
  2. `Digest.ofCurrent()` 的 `sc=` 段依赖「动态根已注册」，而注册发生在 `World.captureLive()` 首行；两次 digest 采样跨过注册点 ⇒ 前后不一致。
- **修法（本轮）**：
  - `StaticEntities.walk` 改为按声明类型判定：`boolean byType = isBattleType(f.getType()); if (!byType && !isBattleEntity(v)) continue;`，可达性只在 `v != null` 时检查；null 也注册成 `Kind.CLONE`（`Cloner.walk(null)` 返回 null，成本为零）。final 分支告警文案支持 null（「declared X, currently null」），不再 `v.getClass()` 空指针。
  - 注册成功时把字段记进 `fresh`（≤8 条），日志新增一行 `mirror entities: newly owned [owner#field (类型), …]`，下次可直接看出哪些根被接管。
  - `Digest.ofCurrent()` 首行调用 `StaticEntities.sync()`：抓取前的 `expected` 与抓取后的 `actual` 看到同一组根，消除假警报（窗口内 `sync()` 因 `World.anyInstalled()` 立即返回，只多一遍反射读）。
- **代价**：每次 `Digest.ofCurrent()` 多一遍「模组静态对象字段反射读＋值签名比对」（`sync()` 内部有 `calls % 512` 与签名不变则跳过 walk 的快路径，实测 19ms/次只在真正 walk 时发生）；每个新注册根在每次抓取/分叉时多一次克隆（本轮 6 个根，`profile` 里 `FuneraryMask.c=8ms/1163x`、`SeanceAction.c=7ms/1163x`）。
- **风险**：①按类型注册会把「当前为 null、以后才填」的字段也接管——若 mod 依赖「静态字段为 null 时走另一条分支」，行为可能变化（该字段现在永远是沙盒克隆/保持 null，实机值不再泄漏进来；这是隔离的正确语义，但 mod 若靠静态字段传递「本局已召唤」的状态，沙盒里会各自独立）。②`DreamCatcher`/`NSoul`/`Necronomicon` 这类**模板**卡/遗物被当根克隆，若 mod 用 `==` 比较模板身份会不等；实测异常时用 `settings.txt` 的 `mirrorEntityIgnore=<owner>#<field>` 关掉对应字段。③回滚：`walk` 的 `byType` 判定改回只按值、删掉 `Digest.ofCurrent()` 首行 `sync()`。
- **对 mod 实际影响**：面板/自动出牌不变；`sc=` 行现在从第一次抓取就稳定出现（不再触发「局面已变化」中止）；求解结果按新的根集合进 `PlanCache`，缓存键会整体换代一次。
- **安装**：`mod/sts1-engine-solver.jar` 1,307,526 B，sha256 `53ABDBE5989D0AB50987350AAA95DD08CBD9D37D17F7F1663A21FB9DEEC90B70`，`INSTALL OK` 到 `D:\Steam\steamapps\common\SlayTheSpire\mods`。
- **验证（待用户实测）**：①计算前后实机 Osty 血量不变；②日志不再出现 `static watch: … OstyManager#osty … put back`；③日志出现 `mirror entities: newly owned [… OstyManager#osty (declared Osty, currently null)]`；④不再出现 `LIVE STATE CHANGED`；⑤断网/普通战斗均能出计划。


---

## B200 「5 回合无伤拿下」实机第 5 回合两张打击打不死（user m00005，2026-11-18）

### 现象

- A20 邪教徒 50HP、遗物蛇眼（Snecko Eye）、角色 TheSilent 59/66。计划 19 步，trace 显示第 5 回合两张打击（6+6）杀 8HP 的怪。
- 实机第 5 回合手牌 **数量/multiset 与沙盒完全相同**（4 打击 + 2 防御 + 生存者），**顺序不同**：
  期望 H=Strike,Defend,Defend,Strike,Strike,Strike,Survivor，实际 H=Strike,Strike,Defend,Strike,Defend,Strike,Survivor。
- 第一张打击按计划状态 c=2 命中；第二张要求 c=0 的打击，实机 3 张打击没有一张是 0 费，只剩 1 能量 ⇒
  「3 copies of Strike_G in hand and none carries the planned state (Strike_G|c=0/0|...); refusing instead of playing a neighbour」，连拒 5 次后卡住，打不死。

### 取证（C:\\Users\\YOKI\\sts1-engine-solver\\solver.log，00:24）

- 偏离不是从第 5 回合开始：13510-13513 第 9 步「生存者（选：打击 → 确定）」首次 step 9 came out differently。
  选牌前两侧同为 H=Strike,Defend,Defend,Defend,Strike；选后 expected H=Strike,Defend,Defend,Defend（弃第 2 张打击），
  actual H=Defend,Defend,Defend,Strike（弃第 1 张打击）。两侧 **rng 计数完全相同**（cardRandom=20,shuffle=3,monster=38…），即不是随机流走偏。
- 13610/13611 第 5 回合：两侧 multiset 相同（抽牌堆 D 段内容也相同）但顺序不同，rng 亦相同。
- 13604/13322 的 static watch: … relicstats.patches.relics.SneckoInfo#cards/#costs 是 RelicStats 的**统计静态量**被沙盒写脏后原地还原（值已放回），与费用判定无关，排除。
- 出牌路径早就堵住了同一个洞：13491 同一场第 4 步就打印过「2 copies of Strike_G … refusing instead of playing a neighbour」，而**选牌路径当时是静默回退**，所以偏离晚一步才暴露。

### 根因（不是随机流，也不是蛇眼模拟错）

1. **选牌答案的身份两侧不一致**：Driver.matchOffered 的五趟匹配原来是 uuid → print → id+name → id → name。
   沙盒 fork 继承同一批 uuid，能精确到「第几张打击」；实机手里的牌是引擎自己重建的实例（日志反复出现 uuid … is sandbox-local），
   uuid 那趟打不中，只能退到 id/name，**同 id 多副本时取列表第一张**。
2. **第 9 步那两张打击状态相同**（同费；CardPrint 含 cost，同费即同 print）。沙盒按 uuid 弃第 2 张，实机按 id 弃第 1 张 ⇒ 手牌顺序分叉
   ⇒ 回合结束弃牌堆顺序分叉 ⇒ 下次洗牌把同一串随机序列作用在**不同顺序**的堆上 ⇒ 抽牌顺序不同 ⇒ 蛇眼 ConfusionPower.onCardDraw 掷出的费用落到不同的牌上
   ⇒ 计划里那张 0 费打击在实机不存在。
3. 附带缺陷（本次记录，不改）：13513 已打印「autopilot stopped … 自动出牌已停下」，但 00:24:13 起仍继续执行第 10-18 步，「已停下」并未真停（另案）。

### 修法（通用，不对任何卡牌/遗物特判）

src/main/java/sts1solver/drive/Driver.java：

1. matchOffered：**print 提到 uuid 之前** —— 两侧只能用「计划记下的状态」作为共同身份；同 print 多副本时都取第一张。
2. rebind（选牌填屏）：uuid 之后、id+upgrade 之前**加一趟 print**，与 matchOffered 同规则。
3. picksFor：记了 print、但候选里**没有一张带该状态且同 id 有多张**时**拒绝整条答案**（对齐出牌路径 liveCardFor 的严格性），
   日志 selection: N copies of X on offer and none carries the planned state，不再「拿个邻居凑数」。
4. identityOf（addOption 去重键）：id+升级 → **CardPrint**。蛇眼下「弃 0 费打击」与「弃 2 费打击」原本被折叠成同一分支。
5. fillHandSelection / fillGridSelection：候选池用 withoutIdentical（按引用）剔除本轮已选中的牌，避免「选两张同状态的牌」被 print 规则解析成同一张。
6. live selection 日志补 state=[所选项 print] planState=[计划 print]，下次可直接判定走的是哪一趟。

### 代价

- 性能：CardPrint.of 在选项去重与解析处多算几次（每张牌读少量字段，与每步 digest 同量级）。主要代价是**分支变多**：
  状态不同的同名副本不再合并（蛇眼手里 5 张防御 5 种费用 ⇒ 弃牌选项 1 → 5），由 Limits.handBranches 上限截断，unlimited 档宽度上升最明显。
- 代码：Driver 内 6 处改动 + 2 个辅助方法，无新文件、无新设置键、无 API 变更。

### 风险

- 第 3 条会让实机**更早停手**并打印拒绝原因：这是「不得静默」的取舍；过去会静默拿邻居继续，现在会停下。
- rebind 走 print 后，两张状态相同的牌按「第一张」约定解析；若有 mod 靠副本 uuid 记录选择，会看到「选了另一张」（引擎自身不这么做）。
- 回滚：6 处改动逐条改回即可；identityOf 改回 id+upgrade 即恢复原分支数（无需动 settings）。

### 对 mod 的实际影响

- 面板：选牌步显示的是计划要的那张；不会再把「弃第 1 张 / 弃第 2 张」当成同一答案。
- 自动出牌：第 9 步这类无声偏离消失，第 5 回合按计划出完两张打击。
- 搜索：同名不同状态成为不同分支，路线更贴近实机；代价是宽度/耗时上升（受上限约束）。
- 其它 mod：只用 CardPrint（引擎字段）与 AbstractCard.uuid，不认任何 mod 类。

### 验证（用户实机，按 m00732 不跑门禁）

1. 同样 A20 邪教徒 + 蛇眼，计算后第 5 回合两张打击能打完、怪死。
2. 日志不再出现第 9 步 step 9 came out differently。
3. 若出现 selection: … none carries the planned state，把它发出来——说明还有别处状态差，需要继续查而不是绕过。

---

## B201 打史莱姆王计算中崩溃：沙盒构造怪物时加载的贴图从不释放（user m00511，2026-11-19）

### 现象

- 打史莱姆王（卡组里没有华丽收场）开启不限时计算，约 17 分钟后「计算没了、游戏卡住」：
  最后一条正常日志是 00:07:17.153 `ERROR engine update threw: phase=WAITING_ON_USER turnHasEnded=true queues=9/0/1 player=ok hand=0`，
  之后 `expand failed (1x/2x/3x)` 反复重试同一分支，游戏整体卡死（watchdog: no frame for 4141 ms）。
- 不是用户猜的「卡组里没有华丽收场」之类卡牌问题，**也不是算力/复杂度算错**：全程 **OutOfMemoryError 0 次**。

### 取证（C:\Users\YOKI\sts1-engine-solver\solver.log，3169 行，00:06–00:07）

- 精确栈（log:2877-2888）：
  `com.badlogic.gdx.utils.GdxRuntimeException: Couldn't load file: images/monsters/theBottom/slimeL/skeleton.png`
  ← `Caused by: java.io.IOException: Error loading pixmap: decoder init failed for stream`
  ← `AbstractCreature.loadAnimation(AbstractCreature.java:401)` ← `AcidSlime_L.<init>(AcidSlime_L.java:86)`
  ← `SlimeBoss.takeTurn(SlimeBoss.java:155)` ← `GameActionManager.getNextAction` ← `Driver.engineFrame(Driver.java:1054)`
  ← `Driver.drain(557)` ← `Driver.endTurn(1427)` ← `Legal.apply(Legal.java:347)` ← `Solver.expandCore(Solver.java:1009)`。
- 计数：decoder init failed 3 次、Couldn't load file 9 次、`File not found: images\monsters\theBottom\slimeAltL\skeleton.atlas (Internal)` 6 次（log:3058-3105）、
  `expand failed` 14 次、`a world was left installed; restoring the live game` **70 次**（log:2963-3156）。
- 00:07:25 watchdog：`writing …\hang-20261119-000721.txt failed: java.io.FileNotFoundException … (系统资源不足，无法完成请求的服务。)`
  （=ERROR_NO_SYSTEM_RESOURCES，连临时文件都开不出来），20 个采样**同一个栈** `org.lwjgl.opengl.WindowsContextImplementation.nSwapBuffers`
  —— 卡在显卡驱动，不是我们的搜索循环。
- 规模：已查阅 609170 条世界线（本档 34023），601 条/s，beam 42（加宽 2/4 档），已用 **1012s（不限）**；
  `frame stats: frames=20984 over=1011569ms p50=44ms`；heap 334MB（23:24 启动）→ 振荡 1.2–3.1GB / 上限 7106MB，gc=754 次/10852ms。
- 机器：RTX 5070 Laptop **8GB 显存**（docs/RUNMAP-PLAN.md:304），31.2GB 内存。

### 根因

1. 史莱姆王分裂时引擎 `new AcidSlime_L(...)`/`new SpikeSlime_L(...)`，构造函数末尾调 `AbstractCreature.loadAnimation(atlasUrl, jsonUrl, scale)`，
   它 `new TextureAtlas(...)`：**真的解码 PNG 并往 GL 上传纹理**（沙盒与实机共用同一个 GL 上下文，这是真实的显存分配，GC 收不回来）。
2. 沙盒**从不** dispose 这些 atlas：`mirror/DeathSettle.java:29` 明确写「dispose() 会释放与实机共享的贴图资源」，于是 settle() 只置 `isDead`+`powers.clear()`。
   那条规则是为「实机战斗里已有的怪物」写的，套到**沙盒自己 new 出来的怪物**身上，结果就是每次分裂泄漏 1 份贴图（每次分裂 2 只）。
3. 不限时预算下跑 1012s、60 万条世界线，分裂次数以千计 ⇒ 8GB 显存耗尽 ⇒ 驱动卡死（nSwapBuffers 卡 4.1s、整帧不刷新）
   ⇒ 之后任何贴图解码/文件打开都失败（decoder init failed、文件 not found、临时文件写不出）。**这就是「计算量导致崩溃」的真身：不是算法复杂，是运行时间越长泄漏越多。**

### 修法（通用，不特判任何怪物/遗物/卡牌）

新增 `src/main/java/sts1solver/mirror/SandboxArt.java`：

1. `@SpirePatch(clz = AbstractCreature.class, method = "loadAnimation", paramtypez = {String.class, String.class, float.class})`
   的 `@SpirePostfixPatch`：**仅当 `World.anyInstalled()`**（即有沙盒窗口装着）时，用反射读 `AbstractCreature.atlas`（protected 字段，`Reflect.find`），
   把新建的 `TextureAtlas` 记进 `IdentityHashMap`。
2. `World.uninstall()` 的 `finally`（`installed = false` 之后、`ConsoleMute.pop()` 之前）调 `SandboxArt.close()`：把本窗口记下的 atlas 逐个 `dispose()`。
   正常出窗、抛异常出窗、forceRestore 都走这条路，所以半途失败也不会漏。
3. 实机的贴图**从不入表**（窗口外构造的怪物不会进来），因此永远只释放沙盒自己 new 出来的那份。
4. 计数与日志：首次加载、每 2000 次、以及每次真正释放过的窗口各打一行
   `sandbox art: loaded=X disposed=Y` / `sandbox art: closed window N (k atlas(es))`，供实机核对 `loaded == disposed`。

### 代价

- 性能：每个窗口结束时只遍历本窗口新建的 atlas（通常 0 个，分裂时 1–2 个），一次 `Texture.dispose()`（渲染线程，glDeleteTexture）——与窗口已经在做的静态量还原同量级。
- 内存：从「每次分裂永久 +1 份贴图/显存」降为「同时只有当前窗口新建的那几份」。**解码成本没有变化**（没有做缓存/共享，见「已知边界」）。
- 代码：新增 1 个文件（约 150 行，含注释），`World.uninstall()` 加 6 行；无新设置键、无 API 变更、不改搜索逻辑。

### 风险

- 只有「引擎让沙盒创建的生物在窗口结束后还被使用」时才会用到已释放的纹理；沙盒从不渲染，存储下来的世界线再装回来也不会重新绘制它的怪物，
  真要继续演算时新造出来的怪会重新加载贴图。即便如此，若将来出现「沙盒怪物贴图变黑/花」的现象，第一嫌疑就是这条，回滚方式是删掉 `SandboxArt.close()` 调用（一行）。
- 反射读 `atlas` 依赖引擎字段名；字段不存在时只打一次 warn 并跳过（不会中断搜索）。

### 对 mod 的实际影响

- 面板/搜索结果：不变。
- 长时间计算：显存不再随分裂次数增长；打史莱姆王/史莱姆类分裂怪可以跑满不限时预算。
- 其它 mod：只 patch 引擎的 `AbstractCreature.loadAnimation` 与引擎字段，不认任何 mod 类；不 dispose 实机贴图，因此不影响任何 mod 的显示。

### 已知边界（如实记录）

- 没有做「按美术路径共享同一份 atlas」的缓存：那需要 patch gdx 的 `TextureAtlas.load` 并改 `final` 容器，共享出去的 `AtlasRegion` 有被引擎改写的风险，收益只是省掉重复解码（不是本次崩溃的原因），故不做。
- 只覆盖 `AbstractCreature.loadAnimation` 这一条加载路径（引擎里怪物/角色的美术入口）。若将来有别的沙盒路径直接 `new TextureAtlas`，不会入表——届时同样现象会复现，需要在这条路径上补钩子。
- 堆占用（日志里 334MB → 振荡 1.2–3.1GB）是另一条线：搜索保存的世界线越多，Java 侧保留的对象越多，但它没有耗尽 7.1GB 上限（OutOfMemoryError 0 次），**不是本次崩溃的原因**；本次只修显存/原生资源这条。
- 本次没有加「失败重复 N 次就中止搜索」的保护：现在仍会一直重试（日志会先说 expand failed 3 次）。资源耗尽被修掉后这条不再是死路，但若再次出现，用户应手动停算。

### 验证（用户实机，按 m00732 不跑门禁）

1. 打史莱姆王，开不限时计算跑够 15 分钟以上：不再卡死；日志里 `sandbox art: loaded=X disposed=Y` 的 X 与 Y 应相等（或只差当前窗口里正在用的那几份）。
2. 结束后照常给出路线、自动出牌正常。
3. 若仍然卡死：把最后 200 行日志发出来，重点看 `sandbox art` 行是否增长到很大而 disposed 不跟。
