# 沙盒机制清单（每条机制 = 一条门禁）

> 用途：把「沙盒为什么和实机不一样」从**猜**变成**查表**。B141–B158 里几乎每个 bug 都是沙盒少复刻了一条引擎机制。
> 规则：发现一次分叉，先在本表找到**是哪条机制**；该机制没有门禁就**先补门禁**（用合成动作复刻引擎行为，不需要 mod 内容），再修实现。

状态图例：✅ 有门禁守住 / ⚠️ 只有部分覆盖或只在间接门禁里 / ❌ 未覆盖（见文末优先级）

---

## A. 回合与动作队列

| 机制 | 引擎依据 | 沙盒实现 | 门禁 | 状态 |
|---|---|---|---|---|
| M1 动作队列只在**没有屏**时推进；屏开着时整条队列冻结 | `AbstractRoom.update()`：`if (!AbstractDungeon.isScreenUp) actionManager.update()`；`AbstractDungeon.update()` 只对 NONE/FTUE/BOSS_REWARD/HAND_SELECT 调 room.update | `Driver.managerFrozen()` / `Driver.runEndOfTurnQueue()` 循环首判断 | S206、S190、S182 | ✅ |
| M2 回合末把手牌全部弃掉；抽牌堆空时把弃牌堆洗回抽牌堆 | `AbstractRoom.endTurn()` → `AbstractPlayer.discardAtEndOfTurn()`；`EmptyDeckShuffleAction` | `Driver.endTurn()` / `Driver.pressEndTurnButton()` + `Driver.drain()` | **S208（新增）** | ✅ |
| M3 「结束回合」= 按 End Turn 按钮（`disable()` 排 `NewQueueCardAction` 哨兵 → 引擎才 `callEndOfTurnActions`），不是只置 latch | `EndTurnButton.disable(boolean)` | `Driver.pressEndTurnButton()` | S200 | ✅ |
| M4 卡内强制结束回合也要跑回合末：遗物 / PreCard powers / 充能球 / 手牌 `triggerOnEndOfTurnForPlayingCard`（灼伤、腐朽、Regret、BideTime） | `GameActionManager.callEndOfTurnActions()` | `Driver.runEndOfTurnQueue()` 先补 `NewQueueCardAction` | S169、S170 | ✅ |
| M5 能力打出的「结束回合」（`PressEndTurnButtonAction`）按真实按钮走 | `PressEndTurnButtonAction` → `EndTurnButton.disable(true)` | 同一按钮路径 | S178、S145、S137 | ✅ |
| M6 一回合里可能跳过多个回合（Trance 每层跳一次），步的起点/终点回合要分开记 | 引擎 `getNextAction()` 的 turn++ 位置 | `Action.turnBefore/turnAfter`、`Solver.Step.turnBefore/turnAfter` | S183、S184、S177 | ✅ |
| M46 沙盒一帧的 delta 不得**跨过**动作的时间窗：凡按 `duration` 分段推进的 action，每一段都要各跑到一次（B181） | `BurnIncreaseAction.update()`：`duration == 3.0f` 时升级牌堆里已有 Burn；`duration < 1.5f && !gotBurned` 才新建 3 张 Burn 并逐个 upgrade（Hexaghost 地狱火 move 6 只排一条这个 action） | `Driver.simDelta` 默认 `SELECTION_DELTA`(0.05 s)、`FAST_DELTA`(20 s) 只在 `-Dsts1solver.cardDelta=fast` 时用于 A/B；`endTurn` 用 `endTurnSmallFramesForTest`（null→小帧）选帧长 | S249(3) | ✅ |
| M47 发光队列是实机表现，沙盒既不渲染也不保留：安装世界时把沙盒卡上的 `glowList` 换成空表（B182） | `AbstractCard.glowList`（:162，元素 `CardGlowBorder extends AbstractGameEffect`）被 `ClonePolicy` 按引用共享；沙盒 `hand.glowCheck()` → `stopGlowing()`（:1140）对每个 border `duration /= 5`，而 `updateGlow()` 只在 `renderCard`/`renderInLibrary` 里跑（沙盒不渲染）⇒ 只毁不建；`isGlowing`/`glowTimer`/`glowColor` 本身已隔离 | `mirror/GlowReset.drop()`（`Reflect.set` 换空表，不 `clear()`）+ `World.install()` 末尾每世界一次 | S250(4) | ✅ |
| M48 ⚠️ **已回退（user m00787，2026-11-13）**：沙盒帧长按**当前动作**分档：动作第一帧固定 0.05 s，之后一次跳到「该动作下一个时间窗之下 0.02 s」，过了所有窗才用 20 s 收尾（B183；`FramePlan` 已删除，机制不再生效，帧长回到 M46 的全局 0.05 s） | `AbstractGameAction.duration`（protected，`Reflect.find` 读）；全游戏只有 6 个动作类在**首帧之后**还有时间窗：`DrawCardAction`/`FastDrawCardAction`/`ScrapeAction`/`RipAndTearAction`/`loadout.actions.EasyXCostAction` 的 `duration < 0` 循环抽牌、`BurnIncreaseAction` 的 `duration < 1.5f`；其余 `duration == 0.25f/0.5f/3.0f` 一类判断都只在首帧成立，被「第一帧永远 0.05 s」天然保住 | `drive/FramePlan.java`（`WINDOWS` 表 + `windowsFor(Class)` 沿父类链缓存）由 `Driver.engineFrame()` 在每次 `gam.update()` 前后 `begin()/end()`，结果经 `FramePlan.planned()/delta()` 进入 `frameDelta()`；开关 `settings.txt search.frameTiers=off` 退回 M46 的全局 0.05 s | S249、S251（4）、S252（3）、S82、S133、S134 | ✅ |
| M49 沙盒帧长只给**白名单 6 个动作类**计划快帧，其余一律 0.05 s（B184） | `AbstractGameAction.duration`（protected，`Reflect.find` 读）；只有这些类的首帧之后还有时间窗：`DrawCardAction`/`FastDrawCardAction`/`defect.ScrapeAction`/`unique.RipAndTearAction`/`loadout.actions.EasyXCostAction` 的 `duration < 0.0f`（一次抽一张并把 duration 重置成 `ACTION_DUR_XFAST`/`ACTION_DUR_FASTER`）与 `unique.BurnIncreaseAction` 的 `duration < 1.5f`（新建 3 张 Burn；`duration == 3.0f` 首帧升级已有 Burn） | `drive/FramePlan.java`：类名精确匹配（不走父类链）→ 窗数组；非白名单类**永远** 0.05 s；白名单类前 3 帧 0.05 s；`duration >= start` 不分档；`stop = max{t ∈ 窗 | t <= duration}`、`next = duration - stop + 0.001`，夹 `[0.05, 20]`（**不越窗**）；`Driver.frameDelta()` 顺序：`screenDeltaApplies()`(1/60) → `FramePlan.planned()` → `simDelta`；`Driver.engineFrame()` 只包主循环 drain 与 `runEndOfTurnQueue` | 漏一个类只是慢（0.05 s/帧），不会跳窗；屏打开时计划一律不生效（S253）；回滚 `settings.txt search.frameTiers=off`（无需重编）；S251/S252/S253 |
| M50 沙盒**窗口内不许真开 `javax.sound` 音频线**：mod 静态字段里的 `Clip` 型字段在卸窗口时 `stop/flush/close` 并还原（B185） | `javax.sound.sampled.Clip`（实测 `yuaimod.YuAiMusic#currentClip`，`private static`，反射不 `setAccessible` 就读不了）；触发链：模拟回血真调 `AbstractCreature.heal(int)`（内含 `heal(int,boolean)`）→ 雨爱 `@SpirePostfixPatch` → `YuAiController.evaluate(沙盒玩家)` → `YuAiMusic.play()` → `AudioSystem.getClip()`；`MusicMute`(B45) 只管 `CardCrawlGame.music`、`SfxMute`(B65) 只管 `CardCrawlGame.sound`，`SOUND_VOLUME=0` 也挡不住 `getClip()` | `mirror/AudioQuiet.java`（零 mod 类名）：扫全部 mod 静态字段缓存 `Clip` 型字段；`World.install()` 在 `MusicMute.begin(true)` 之后 `begin()`、`World.uninstall()` 在 `StaticWatch.beforeUninstall()` **之前** `end()`（先 close、再让 StaticWatch 还原快照）；每窗口必跑；卸载时「字段值≠记录值且新值非空」→ `stop/flush/close`，新值 null 沿 B169 放行；候选字段强制 `setAccessible`，打不开的进 `blind` 并 `warnEvery`；回滚＝删 `World` 两处挂点 | S255(16)；反向验证 `passed=4 failed=10`；带 `-Mods YuAiBloodMusic` `16/0`（`readableForTest`/`blindForTest` 钉住反射可读性）；**B187 订正：只 close 不够——把已关闭的实机 Clip 写回字段，会让下一窗口 `stop()→flush()` 在 `DirectAudioDevice.nFlush` 原生崩溃；已由 M51 的窗口内惰性替身取代（2026-11-15 实施）** |
| M51 沙盒窗口内**默认不执行任何非战斗副作用**：mod 的 `Clip` 静态在窗口内换成惰性替身（`isOpen/isActive/isRunning→true`，`open/start/loop/stop/flush/close/drain→`空操作，位置/长度→0），让 `play()` 因 `currentClip != null` 早退、**一次音频线都不开**（B187，2026-11-15 已实施；取代 M50 的「只 close」） | `javax.sound.sampled.Clip`；雨爱 `play()` 首指令 `getstatic currentClip; ifnull` 即返回 true；`stop()` 在 `flush()` 前不查 `isOpen()`，对已关闭对象调用会在 `DirectAudioDevice.nFlush` 原生崩溃；`FloatControl` 在 JDK 8 是 `abstract`，替身必须用私有子类 `SandboxFloatControl` | `mirror/SandboxClip.java`(新，单例替身 + `adoptFormat` 缓存实机 `AudioFormat`) + `mirror/AudioQuiet.java`（每窗口把 `Clip` 静态（含 null 的）换成替身、`end()` 写回前 `isOpen()` 校验、已死快照写 null 并计 `closedSnapshot`、计数器 swapped/stale/closed/cleared/putBack/released/closedSnapshot/liveStops）；`World.java` 的 `AudioQuiet.begin()` 挪到 `StaticWatch.afterInstall()` 之后、`end()` 仍在 `beforeUninstall()` 之前；`StaticWatch.snapshotValueForTest(Field)` 供门禁断言顺序 | S255 新契约 **37 条** + 新增 S257(2) 真机开关冒烟（无设备 SKIP 不算通过）；反向验证必须变红；**本轮未跑（用户 m00732 自行实测）** | ✅ 已实施（2026-11-15；代码完成，门禁待跑）。**残余**：计算期间雨爱从 0 帧重放＝已知边界不修（2026-11-16 user m00495；见 PLAN-BUGS B187） |

## B. 选牌屏

| 机制 | 引擎依据 | 沙盒实现 | 门禁 | 状态 |
|---|---|---|---|---|
| M7 手牌屏的「点击」当场把牌移出手牌；Grid 屏只 add、不动 targetGroup | `HandCardSelectScreen.selectHoveredCard()` vs `GridCardSelectScreen` | `Driver.fillHandSelection()` / `fillGridSelection()` | S141、S198、S199 | ✅ |
| M8 一个动作可以连开多块屏（如 CaiNong 的换堆动作开两块 grid） | mod `ExchangeCardsOfTwoPilesAction`；屏的 `callOnOpen()` 清 `selectedCards` | `Solver.expandScreens()` + `Gameplay.pendingChoiceAt`；屏内计数 `ScreenOpens` | S196、S197、S138 | ✅ |
| M9 一直等着不回答的屏**不消耗随机数** | 屏开着动作队列冻结（同 M1） | `Driver.managerFrozen()` | S190 | ✅ |
| M10 沙盒开出的屏不能带回实机（B162 补充：世界自己停在「有屏在等答案」时，屏状态必须能随世界带过去，否则再 install 屏会丢；其余一律归一成 `NONE`/`false`） | `World.readBack()` 对 UI 状态标 `discardOnReadBack`；B151 真机门＝`!isScreenUp` | `Statics` 的 `discardOnReadBack` + B162 `isScreenState(...)` 特例 | S131、S17、S215 | ✅ |
| M34「弃任意张」（anyNumber，如赌博筹码 `open(…,99,true,true)`）的候选集必须是**所有子集**，不能只发「价值前 k 前缀」 | `GamblingChipAction` 取回后逐张 `moveToDiscardPile`+`triggerOnManualDiscard`，再 `DrawCardAction(选中张数)` | `Driver.selectionOptions` 的 upTo 分支 k=0..maxKeep 全组合 + `combinations(best,k,cap)`，cap=`Limits.handBranches`（16/36/∞） | S214 | ✅ |
| M35 **答选牌本身是一步**：屏在等答案时，计划/搜索的合法动作只有「答这块屏」（候选来自 `Driver.selectionOptions`），不能同时打牌或结束回合 | 屏开着 `AbstractRoom.update()` 不推进 action 队列（M1/M9），玩家能做的只有答屏 | `Legal.selectionActions()`、`Solver.candidates` 优先返回答屏候选、`Gameplay.submit` 走 `Driver.resolveSelectionLive`；屏开着也能开算 | S215 | ✅ |
| M36 屏开着但**没人等答案**时，任何非答屏动作必须显式失败（`not-settled`），不得静默 no-op | B162 实测：丢弃屏状态后世界再 install 会带着僵尸屏，`actionManager` 永不推进，290 步出牌全部无效 | `Legal.apply` 的 `AbstractDungeon.isScreenUp` 抛 `not-settled` | S215（附带回归：S196/S198/S199/S206/S207/S211/S212/S213/S214 全绿） | ✅ |
| M37 答选牌可以带**换序**：把某张牌移到手牌末尾，等价「点一下再点一次」；空答案（计划明确要求一张都不选）是合法答案 | `HandCardSelectScreen.selectHoveredCard()` 选中 `hand.removeCard`+`selectedCards.addToTop`，取消选择 `player.hand.addToTop(e)`，`CardGroup.addToTop` = 追加末尾；手牌顺序影响唐 `PlayRandomCardAction` 的 `cardRandomRng` 抽取与回合末弃牌顺序 | `Driver.moveToEndOfHand()`（`rebind`→`removeCard`+`addToTop`+`setAngle`→`refreshHandLayout`）；`Action.moved/movedUuids` + `Action.selectionWithMoves(...)`；`Legal.selectionActions()` 每屏 ≤4 个变体（`REORDER_BRANCHES`）；`resolveSelectionByNames` 用 `askedEmpty` 放行空答案；非 HAND 屏要换序报 `not-offered` | S216 | ✅ |
| M42 计划里「答选牌」这一段的答案**必须由同一条链消费**（`submit` → `answerSelectionStep` → `answerNextScreen`），且缓存复用判据必须是**局面**（`Digest.stable` + 「计划要选的每张牌仍能在 `Driver.selectionCandidates()` 里唯一定位」），不是手牌身份、也不是动作管理器的中途相位（B167） | `answerNextScreen()` 是唯一会推进 `pendingChoiceAt`/清空 pending 的地方；`Digest.ofCurrent()` 首段带 `ph=/q=/use=/end=`；`liveCardFor()` 只查手牌，而「答选牌」步没有手牌身份 | `Gameplay` 的 `answerSelectionStep`/`planPendingChoice`/`clearPendingChoice`/`selectionAnswerFits`、`PlanCache.key` 先 `Digest.stable`、`Digest.stable` 剥首段内部四处 token | S224、S225、S226 | ✅ |
| M43 沙盒窗口内**不派发纯表现事件**（`healthBarUpdatedEvent`）：实机照常（雨爱半血播放），窗口内静音（B168） | `AbstractCreature.healthBarUpdatedEvent()` 只写 `healthBarAnimTimer = 1.2f` / `targetHealthBarWidth`，但 mod（YuAi）在它上面挂 `@SpirePostfixPatch` 播特效；MTS 的 prefix 早退**挡不住别人的 postfix**（javassist insertAfter 插在所有 RETURN 之前，实测 `_tmp/javassist-test`），所以只能改调用点 | `mirror/PresentationQuiet.java`：`quiet() = World.anyInstalled()`，14 个 `@SpirePatch`+`@SpireInstrumentPatch` 用 ExprEditor 把 8 个类的 14 处调用换成 `if (!quiet()) { $0.healthBarUpdatedEvent(); }`（AbstractCreature×3、AbstractPlayer×2、AbstractMonster×3、InstantKillAction、SuicideAction、TheGuardian.changeState×2、NeowsLament、PreservedInsect）；`patchedSites` 用系统属性跨类副本共享 | S227 | ✅ |
| M44 静态监听只把「沙盒把对象塞进实机静态」当泄漏回滚；**别的线程在窗口内把实机静态清成 null（释放）时留在 null**（B169） | 实测：`YuAiMusic$1` 的 AudioSystem LineListener 在音频回调线程里 `clip.close()` 后 `currentClip = null`；旧 `restore()` 见 `live == null` 且快照非 null 就 `field.set(null, 快照值)`，把已关闭的旧 Clip 放回去 ⇒ `YuAiMusic.isPlaying()` 永远 true、雨爱此后不再响（`play()` 首行 `if (currentClip != null) return true`） | `mirror/StaticWatch.beforeUninstall()`：`live == null && 快照非 null` ⇒ 计数 `released`、`warnEvery(30s)` 说明后 `continue`（不回填、不报为沙盒写）；回填只保留「引用被改成别的对象 / 容器内容变了」两个方向 | S228(5) | ✅ |
| M45 静态监听遇到**大数组**要整体复制/整体比较，绝不逐元素装箱；采样成本必须与元素个数近似无关（B170） | `yuaimod.YuAiMusic#audioData` 是 `private static byte[]`（整个 8 MB wav、197.1 秒），`ModStatics.watchable()` 只按类型名排除 texture/pixmap/sprite/atlas/string ⇒ 它进了候选表；旧 `snapshot()` 逐元素 `Array.get` 装箱进 `Object[8M]`（约 32 MB）、`bodyEquals()` 再逐元素比 8M 次，而采样窗口是 installs 1–4 与每 128 个 ⇒ 一次搜索十来个窗口各付 **582 ms + 280 ms**，正是「只有装雨爱才卡 500–700 ms」 | `mirror/StaticWatch.java`：`MAX_ARRAY_ELEMENTS = 4096`；超过阈值走形状 5「large array」——`Array.newInstance(componentType, n)` + `System.arraycopy` 快照、按组件类型分派 `Arrays.equals` 比较、`System.arraycopy` 写回；阈值以下仍是旧 shape 3 | S229(4) | ✅ |
| M11 答完一屏后必须把屏**恢复出来的整条队列**跑完，否则该步状态是半程值 | `AbstractRoom.update()` 队列语义 | `Driver.drainAfterAnswer()` + `completeIfTruncated()` | S197、S207、S172 | ✅ |

## C. 死亡与结算

| 机制 | 引擎依据 | 沙盒实现 | 门禁 | 状态 |
|---|---|---|---|---|
| M12 沙盒不跑死亡动画 ⇒ 死怪的能力必须自己清掉，否则防哈/加格挡会按死怪算 | 实机 `AbstractCreature.dispose()` 在动画后 | `mirror/DeathSettle.java` | S188、S189、S125 | ✅ |
| M13 效果（药水/能力）的终态要在效果被丢弃前应用 | 引擎效果队列 | `mirror/EffectSettle.java` | S144 | ✅ |
| M14 玩家真死就是死，回血不能把尸体拉起来 | `AbstractPlayer.isDead` | 死亡判定 | S110 | ✅ |

## D. 随机流

| 机制 | 引擎依据 | 沙盒实现 | 门禁 | 状态 |
|---|---|---|---|---|
| M15 13 条官方随机流按**位置**复制（不是共享计数器） | `com.megacrit.cardcrawl.random.Random` | `Cloner` / `Statics` 的 CLONE 根 | S146 | ✅ |
| M16 每次出牌要能说出它落在随机流的哪个位置 | `AbstractPlayer.useCard()` | `mirror/PlayTrace`、`mirror/RngStamp` | S203、S204 | ✅ |
| M17 读档进房会把 5 条流**重新播种**成 `new Random(seed + floorNum)`（counter 归零），`loadSeeds` 只恢复 7 条 | `AbstractDungeon.nextRoomTransition()`；`loadSeeds(SaveFile)` | 无（B148 的「计数进缓存键」已撤销：归零是确定值，键不变） | — | ❌ 见 P2 |

## E. 镜像与隔离

| 机制 | 引擎依据 | 沙盒实现 | 门禁 | 状态 |
|---|---|---|---|---|
| M18 静态字段（含 mod 的）必须在窗口内可换、窗口外还原 | 引擎全静态单例 | `mirror/World`、`Statics`、`StaticSweep` | S28、S17、S105、S161、S162、S163 | ✅ |
| M19 mod 的 `static final` 容器改不动引用，只能原地改写再还原 | Java 反射语义 | `Statics` 的 `MUTABLE_CONTAINER` | S142、S143 | ✅ |
| M20 窗口内不许写偏好 / 存档 / 成就 / 指标上传 | `Prefs.flush`、`AsyncSaver.save`、`UnlockTracker` | `mirror/SandboxWrites`、`ExternalMute`、`SaveGuard` | S193、S194、S195、S191、S166、S164 | ✅ |
| M21 音频 / 输入 / VFX / 控制台在窗口内静音并在窗口外还原 | `SoundMaster`、`MusicMaster`、`InputHelper` | `SfxMute`、`MusicMute`、`InputMute`、`VfxGuard`、`AnimationMute` | S111、S120、S69、S73、S165 | ✅ |
| M41 纯表现对象（`BobEffect`）也必须在窗口内**拷贝**、不能按引用共享（B166） | 它没有状态逻辑，却落在 `com.megacrit.cardcrawl.vfx.` 前缀下被 ClonePolicy 当 VFX 共享 ⇒ 沙盒与实机同一个 bob；`BobEffect.update()` 读 `Gdx.graphics.getDeltaTime()`，窗口代理 delta 是 `FAST_DELTA=20.0f`（`Driver.java:864`）、speed=4，`PostCombatLoot` 每评估一个胜利节点就泵 `room.update()`（`Solver.java:1575`）⇒ 实机 timer 每帧前进 80 rad≈12.7 周期（意图徽章抖动/瞬移） | `mirror/ClonePolicy.java` 给 `BobEffect` 加例外（返回 false＝拷贝）；实机 bob 只由真实帧推进 | S223（修前实测 `liveAdvance=640.0`，修后 `0.0`） | ✅ |
| M52 mod 静态字段指向的**活体**（`OstyManager.osty`）只换引用、不隔离其内部状态（B192） | 静态引用字段按设计不镜像（`ModStatics.java:29-32`）；第一/二轮 `StaticEntities` 的实体判据只列 `AbstractMonster/AbstractPlayer/Power/Card/Relic/Potion/Orb`，而奥斯提是**直接继承 `AbstractCreature` 的宠物**（`javap` 实证）⇒ 字段始终未接管，`HealOstyAction.update()` 直接 `OstyManager.get().restore()` 写活体；`StaticWatch` 收集实例字段按层级截断到 96 个（`Osty`~30 + `AbstractCreature` 70+），`currentHealth/maxHealth` 可能不进候选 ⇒ put back 覆盖不到；`Digest` 不含这类对象 ⇒ 前后比对放行 | `mirror/StaticEntities` 每次抓取前把「战斗实体（含 `AbstractCreature` 宠物、或带 `int currentHealth`+`int maxHealth` 的对象）+ 世界图不可达」的静态值动态注册为 `Kind.CLONE`（零 mod 类名）+ `copyContainer` 深拷容器内战斗元素 + `StaticWatch` 全量收集继承链再按名字挑 32（收集不截断）+ `Digest` 追加通用 `sc=` 行 + `LiveGuard` 比对未接管活体；第五轮 `SpineIsolation` 让克隆体的 `Skeleton`/`AnimationState` 独立（`new Skeleton(live)` 拷贝构造 + 按 track 重建 `AnimationState`，`stateData` 共享），`SkeletonFingerprint` 加位姿哈希、搜索后审计忽略只差时钟的行，`StaticWatch` 跳过动态克隆根（`docs/PLAN-MOD-OBJECTS.md` T1–T10、§3.4、§3.5） | 已实现五轮并安装（最新第五轮 jar `FAD91E8D…9B0E`：spine 不再共享、Osty 假回写消除；第四轮 jar `EAAEA9C8…CD0F`：实体判据加 `AbstractCreature` + 血量字段兜底，`OstyManager#osty` 终于被接管；`StaticWatch` 收集不再截断；`LiveGuard` 覆盖未接管活体；`mirrorEntityRoots=off` / `mirrorEntityIgnore=owner#field` 可回退）；用户 m00732 自测，不新增门禁 | ✅ 待实测 |

## F. 战斗结束

| 机制 | 引擎依据 | 沙盒实现 | 门禁 | 状态 |
|---|---|---|---|---|
| M22 「不能输」的平局守卫、分裂不是胜利 | `AbstractRoom` 胜负判定 | 搜索侧守卫 | S40、S16、S21 | ✅ |
| M23 逃跑 / 烟雾弹 / 死亡结算 | `AbstractRoom.endBattle` 分支 | `search/EscapePolicy` | S186、S8、S123 | ✅ |
| M24 胜利时的**战后结算**（`onVictory`：能力、遗物、mod 的战后惩罚/奖励）在沙盒里**不执行** | `AbstractPlayer.onVictory()` / `AbstractPower.onVictory()` | 无；战后回血是 `search/PostCombatHealing` 的白名单硬编码（四个原版来源） | — | ❌ 见 P1（毒种腐朽、Self Repair 一类）。B160：面板已删掉「还有没认出来的遗物，可能回更多」——缺口本身仍在，见 PLAN-BUGS B160「保留的边界」 |

## G. 战斗内机制

| 机制 | 引擎依据 | 沙盒实现 | 门禁 | 状态 |
|---|---|---|---|---|
| M25 姿态（Wrath/Calm/Divinity）、充能球、中毒、侦察 | 引擎对应 power/action | 真引擎对象（无需建模） | S22、S26、S24、S23、S20 | ✅ |
| M26 费用改写（蛇眼 Confusion、mod 的 canUse 覆写）与「付不起就不能出」 | `AbstractCard.cost`/`canUse` | `Legal` 的出牌闸门 `playerCanUse`/`hasEnoughEnergy` | S185、S150 | ✅ |
| M27 多段攻击的伤害 / 格挡 / 荆棘结算顺序 | `DamageAllEnemiesAction` / `DamageAction` | 真引擎对象 | — | ❌ 见 P1 |
| M28 遗物触发顺序（回合开始/结束、受击、击杀） | `AbstractRelic.atTurnStart` 等 | 真引擎对象 | 间接（S167 Sharp Hide、S130 Self Repair） | ⚠️ |

## H. 生命周期（我们的驱动，不是引擎）

| 机制 | 依据 | 沙盒实现 | 门禁 | 状态 |
|---|---|---|---|---|
| M29 一帧只驱动一次，跨帧可见状态写在 `finally` 里 | B59 教训 | `Gameplay.tick()` | — | ❌ 见 P2 |
| M31 计划里的答案**不会过期** | B158：「选牌答案 6 秒后失效」曾让实机那块屏被空放，计划已算好的答案被时间丢掉 | `Gameplay.pendingChoice`（无时间戳）；`answerLatentSelection()` 只判 `planned.isEmpty()` | S212 | ✅ |
| M32 计划没有答案时**停下**，不猜牌 | B158：best-first 兜底会答出一张搜索从未假设的牌，之后每个数字都是虚构 | 实机选牌/卡牌奖励两处无答案即停自动 + 保留计划 + `selection-no-answer`/`reward-no-answer` | S211 | ✅ |
| M33 判步只看引擎状态，不看墙钟 | B158：`AutoPace.readyToJudge(…, verifyMs())`（默认 700ms）曾在引擎未 settle 时判步 | `Gameplay.engineSettled()`（actions/cardQueue/monsterQueue/preTurnActions 空 + currentAction done + 无屏 + limbo 空 + 非 isEndingTurn）+ digest 是否还在变 | S210/S212 | ✅ |
| M30 任何**未 settle** 的状态不得进入计划或风险判断（B158 追加：也不得由任何**时间**窗口代替状态判断）（面板数字另走搜索那条路，订正见 PLAN-BUGS B156） | B154/B158：`Gameplay.predictEndTurnRisk()` 报半程 HP 51，而同一结束回合的计划终值是 43；面板「预计 -N HP」来自 `Solver` 的 `previewHpLoss`/`Step.hp`，**不是**这个闸门 | **计划/执行侧（B157）**：删掉 20 秒墙钟，结束回合必须走到 `turnAfter` 才算完，`turn > expectedEnd` 停自动出牌，stable digest 不符即停。**风险闸门（B159）**：`predictEndTurnRisk()` 现在用该步计划里的答案逐块答完结束回合中的选牌屏（`answerEndTurnScreens`，最多 64 块），屏没有答案就报 unknown 并让闸门停下，不猜、也不半程取数 | S209、S210（计划侧）；S213（闸门跑完整个结束回合） | ✅ |
| M39 胜利后的**奖励页**是这一场的价值（B164）：金币/卡/遗物/药水都算进胜利评分，逃生侧不再单独扣分（逃跑自然拿不到），避免双算 | B164 前 `Solver.score()` 只有 HP 亏损 + `goldDelta`，战利品一律不可见；`EscapePolicy.forfeitPoints()` 全仓库零调用者（B129 设计了却从未接线）；`Looter.die()` 的贼赃只进 `room.rewards`。判据：`PostCombatLoot.settle()` 在一次性窗口里把 `rewardAllowed=false`、`isScreenUp=true`、`endBattleTimer=-1`，泵 `room.update()` 到 `COMPLETE` 再 `setupItemReward()`，直接读真实 `room.rewards`；只在 `GrowthBook.counting()` 时跑；面板**不显示**这行（m17808），结果写日志 `loot :` | **风险**：必须还原 RNG counter/遗物池/kill 计数/`room.rewards` 元素/`endBattleTimer`/`isScreenUp`/`isEndingTurn`/`escaped`；`rewardAllowed=false` 保证不开奖励屏、`SfxMute` 吞胜利音效；任何 Throwable 回退 `formula()`（按 `AbstractRoom.java:276-321` 复刻，含「有怪逃跑不发普通金币」）。**仍未覆盖**：`onVictory` 类能力效果（M24） | S217(8)、S218(4)、S219(6)、S220(4) | ✅ |

| M40 **一次开屏只抽一次随机**（B165）：`DiscoveryAction` 型的「三选一」只在第一帧生成，之后每帧调用两个生成方法都返回空表，所以实机泵/沙盒/手打三者的随机流一致 | B165 前 `DiscoveryAction.update()` 每帧重生成三张、只有 `duration == ACTION_DUR_FAST` 那帧被 `customCombatOpen` 采用；`Driver.pumpLiveUntilConsumed()` 屏开后还要跑 ≤8 次 `gam.update()`，每次再抽 3 张（≈24 抽），沙盒泵在模态屏期间不跑 manager ⇒ 实机随机流被白推、之后蛇眼/洗牌/唐全错位（第 72 步技能药水候选与计划不同就是它）。判据：`mirror/DiscoveryRandom.java` 的 prefix 只放行 FAST 帧并 `++generations`；屏开后若仍有帧抽数，`screen-rng` 告警会点名 | **风险**：补丁是全局的，手打随机流相对原版变（只少浪费）；依赖逐帧重掷的 mod 只拿到第一帧；回滚删补丁类即可。**边界**：其它 mod 的帧相关动作只告警（F2），不改造 | S221(6)、S222(6) | ✅ |

---

## 未覆盖清单（按优先级）

**P0 — 会产出错误判断（B156 订正：不是面板数字）**
1. **M30 半程值（B159 已修）**：B157 删掉 20 秒墙钟，B159 把风险闸门也修完——`Gameplay.predictEndTurnRisk()` 不再只跑一次 `Driver.endTurn()` 就取血量，而是用该步计划记录的答案逐块答完结束回合里开的选牌屏（`answerEndTurnScreens`，单次最多 64 块），屏没有答案就返回 unknown 让闸门停下（不猜），答完取到的才是回合末血量。门禁 S213：合成「结束回合中途开屏 + 怪物 18 点攻击」，断言 `risk.ok` / 屏被计划里的牌答了（`played==1`）/ 屏之后的怪物回合算进来（`damage > 0`）；修前实测 `damage=0 played=0`（半程 -0 HP）⇒ 必红。仍然不进面板数字（面板来自 `Solver` 的 `previewHpLoss`/`Step.hp`）。
2. **M24 战后结算**：沙盒不跑 `onVictory`，所以毒种塞腐朽、Self Repair 回血这类局外效果完全不可见。**B164 订正**：奖励页（金币/卡/遗物/药水）已由 `PostCombatLoot` 覆盖并计入胜利评分（M39）；仍不覆盖的是 `onVictory` 类能力效果本身。建议门禁：带一个 `onVictory` 计数的测试能力，钉住「沙盒里它不执行」这一边界，避免有人误以为执行了。

**P1 — 机制真实存在且会改变结果**
3. **M27 多段攻击**：合成 2×6 的攻击打穿 5 格挡，断言 HP 掉 7、格挡归零、荆棘按次反伤。
4. **M17 读档重播种**：断言「同一场读档后 5 条流的 counter 是 seed+floorNum 的起点」，用它解释为什么同 digest 的两份状态可能在不同随机位置。

**P2 — 结构性风险**
5. **M29 生命周期**：给 `Gameplay.tick()` 加一条「提前 return 也不跳过跨帧刷新」的门禁（合成 tick 序列）。

---

## 维护规则

1. 任何一次「面板和实机不一样 / 计划算错」的排查，先在表里定位机制编号；找不到机制的，说明是新机制，先加行再加门禁。
2. 新门禁一律用**合成动作**复刻引擎行为，不依赖具体 mod 内容（M8 用 `MultiScreenProbeAction`、M1 用 `PlayChosenProbeAction` 就是这个做法）。
3. ❌ 的行只能在有门禁后改成 ✅；不能因为「暂时没出问题」就标成守住。
4. known-limit（M17/M24）要同时写在 `docs/PLAN-BUGS.md` 的对应条目里，两边不能不一致。
5. **I1（门禁用假对象不能证明真资源安全）**：涉及原生资源（音频线/文件句柄/纹理）的修复，门禁至少一条断言走真实资源；跑不了（无设备/无头）就显式 `SKIP` 并在 bug 条目写「门禁不覆盖原生层」，禁止拿 Proxy 假对象当通过（S255 全绿而实机崩溃即反例）。
6. **I2（窗口内只换引用，不碰实机对象的生命周期）**：窗口内不得对实机对象调用 `stop/close/flush/dispose`；跨窗口写引用必须成对 begin/end，且写回前过 `isOpen()/isValid()` 校验；新增一类资源（Clip/Line/Image/连接）在同一处登记。
7. **I3（沙盒默认不执行展示层）**：新增任何 mod 交互（heal/audio/network/save）先问「这是战斗状态吗」；不是就隔离，并在本表加行（没有门禁的行不能标 ✅）。

_最后更新：2026-11-15（B187 **已实施：沙盒隔离收口（机制 M51）**——窗口内闪退根因与方案见 `docs/PLAN-ISOLATION.md` 第 11 节。落地：新增 `mirror/SandboxClip.java`（`Clip` 惰性单例替身，`isOpen/isActive/isRunning` 恒 true、`open/start/loop/stop/flush/drain/close` 空操作、`getFormat` 用 `adoptFormat` 缓存的实机格式、`getControl(FloatControl.Type)` 返回自建 `SandboxFloatControl`，JDK 8 的 `FloatControl` 是 abstract）；`mirror/AudioQuiet.java` 每窗口把所有 mod `Clip` 静态（**连 null 的也塞替身**，否则雨爱 `stop()` 置 null 后下一节点 `play()` 又开真线）换成替身、`end()` 按快照写回且每步前 `isOpen()`，快照已关闭则写 null 并计 `closedSnapshot`，计数器 swapped/stale/closed/cleared/putBack/released/closedSnapshot/liveStops；`World.install()` 的 `begin()` 挪到 `StaticWatch.afterInstall()` 之后（否则 StaticWatch 快照到替身），`end()` 仍在 `beforeUninstall()` 之前；`StaticWatch.snapshotValueForTest(Field)` 供门禁断言顺序。S255 重写 **37 条**新契约、新增 S257(2) 真机开关冒烟（无设备 SKIP 不算通过）；**本轮未跑门禁（用户 m00732 自行实测）**；jar 1,287,415 B / sha256 `E85332F8C3B26B6B44C8CAF00CF04D9F2E3E1421013692557C38BB8E51E4A08D`；安装需 danger-full-access（`install.ps1` 在 workspace-write 下把沙箱 ACL 拒绝误报成 jar 被游戏占用））。旧 2026-11-15（B187 **计划**：沙盒隔离收口——闪退根因是窗口内仍执行雨爱 `heal` 钩子并开真实 `javax.sound` 线；B185 的「关沙盒 Clip + 写回字段」把叠音升级成「写回已关闭 Clip → 下一窗口 `flush()` 原生空指针」；方案 L1 崩溃守卫 / L2 Clip 惰性替身（新机制 M51）/ L3 与 StaticWatch 的顺序 / L4 非战斗副作用审计 + S255 改契约 + S257 真机冒烟，规则 I1–I3，详见 `docs/PLAN-ISOLATION.md`，产品代码零改动）。旧 2026-11-15（B186 **S171 门禁预算去掉「机器速度」前提**（只改测试代码）：(a) 2000ms、(b) 9000ms 当上限 + 采到预览即 `Gameplay.stopQuickSolve()`，断言仍 10 条、S171 **10/0**；core 待复跑确认（S171 修复前全量 755/6 全在该门、其余 751 条全绿；S171 单跑 10/0；02:46 用户叫停复跑）、jar 1,280,074 B / sha256 `F24DE109C2D77A211923B62D6226BC3AE7CE720754B5337A35510BAD743D1F83`。旧 2026-11-15（B185 **机制 M50＝沙盒窗口内不许真开 `javax.sound` 音频线**：新增 `mirror/AudioQuiet.java` —— 扫全部 mod 静态字段缓存 `Clip` 型字段（实测只有 `yuaimod.YuAiMusic#currentClip`），`World.install()` 在 `MusicMute.begin(true)` 之后 `begin()`、`uninstall()` 在 `StaticWatch.beforeUninstall()` 之前 `end()`，卸窗口时 `stop/flush/close` 新 Clip 后还原快照；候选字段强制 `setAccessible`，扫不开的进 `blind` 并 `warnEvery`；回滚＝删 `World` 两处挂点；S255(16)，反向验证 `passed=4 failed=10`，带 `-Mods YuAiBloodMusic` `16/0`；core 待复跑确认（S171 修复前全量 755/6 全在该门、其余 751 条全绿；S171 单跑 10/0；02:46 用户叫停复跑）、jar 1,279,962 B / sha256 `9D63ED91E329239EFB7E764DD22DFB6A9832A914AD401CB960CF0DC82C6432B2`。旧 2026-11-14（B184 **机制 M49＝白名单帧长分档**：只给 6 个动作类计划快帧，其余 372 类回到 M46 的 0.05 s；`drive/FramePlan.java` 重建为标准白名单版、`Driver.frameDelta()` 顺序为屏帧(1/60) → 计划 → `simDelta`、回滚键 `settings.txt search.frameTiers`；S251(4)/S252(3)/S253(3)，反向验证 `passed=8 failed=2`；core 739、jar 1,258,662 B / sha256 `56486847279F6FCF439EAF0A52C0289800495624DABCF794A87D2B7B8D633AF7`。旧 2026-11-13（B183 **机制 M48 已按 user m00787 全部回退**：`drive/FramePlan` / `Driver.engineFrame()` / S251 / S252 均已删除，帧长回到 M46 的全局 0.05 s、core 729/0、jar 1,247,388 B / sha256 `8B0E1D9054CEF4600E03E6755520415EA43A72CBAD797E4757A73525F9E2531B`；分档后实机反而更卡的原因未定性，重做前必须先拿 nodes/ms 采样。以下为回退前的记录。旧 2026-11-13（B183 **新增机制 M48：沙盒帧长按当前动作分档**：`drive/FramePlan` 在每个动作的第一帧固定 0.05 s（保住所有 `duration == 初值` 与开选牌屏的逻辑），此后一次跳到「该动作下一个时间窗之下 0.02 s」，窗口过了才用 20 s 收尾；全游戏只有 6 个动作类在首帧之后还有窗（`DrawCardAction`/`FastDrawCardAction`/`ScrapeAction`/`RipAndTearAction`/`EasyXCostAction` 的 `duration < 0` 循环、`BurnIncreaseAction` 的 `< 1.5f`），其余 `==` 判断都在首帧；S251(4)/S252(3) 钉住，反向验证 3 红；开关 `settings.txt search.frameTiers=off` 退回 M46 的全局 0.05 s。旧 2026-11-13 B182 **新增机制 M47：沙盒不保留手牌发光队列**：`mirror/GlowReset` 每世界一次把沙盒卡容器上非空 `glowList` 换成空表（不拷贝，user m00148 定案）；S250(4)，反向验证 2 红；代价每世界 µs 级、非热路径。旧 2026-11-12：B181 **新增机制 M46：沙盒帧长不得跨过动作的时间窗**：`Driver.simDelta` 默认从 `FAST_DELTA`(20 s) 改成 `SELECTION_DELTA`(0.05 s)，`FAST_DELTA` 只在 `-Dsts1solver.cardDelta=fast` 时用于 A/B 测量；Hexaghost 地狱火的 `BurnIncreaseAction` 两个时间窗（先升级已有灼伤、后段新建 3 张）因此都会跑到，第 10 回合不再少 3 张 Burn+；S249(3) 钉住，反向验证 1 红；代价是每节点 0.58–0.78 ms → 1.90 ms。B180 **不新增镜像机制、只改末档的节点上限与面板文案**：时间预算不限（`DeepBudget.unlimited`）时，最后一档 `widenToNextRung()` 不再沿用 `rungCapNodes`，而是 `cap=0`/`nodeBudget=0`/`layerAllowance=Integer.MAX_VALUE`（日志 `cap=+unlimited`；仍受 maxLayer、provenOptimal、前沿干、停止按钮约束），`PlanText.candidateNote(...)`/`limitText(...)` 把「还有 N 条没展开」的原因写成具体限制并追加停车候选数；镜像/回写/静音/候选枚举/判重都不受影响，S247(3)/S248(6) 钉住（反向 noCap=false 时 1 红），另修 S153 截断断言（非产品行为）。B179 **不新增镜像机制、只改搜索的预算拓扑与日志口径**：普通战斗的 portfolio 四档不再各建 `Solver.Session`，而是同一个 Session 逐档加宽 —— `planWidening(beams, deadlinesMs, nodeCaps)` 给每档一个累计墙钟 deadline（份额 35/25/25/15）与按份额的节点上限，`budgetNow()` 让 `microStep`/`promoteOne`/`parkedCapFor` 读当前档的 deadline（总预算 `budgetMs` 仍是加宽的上限），`Result` 新增 `passNodes/passExpansions` 让 `summary()` 同时打本档与合计；镜像/回写/静音/候选枚举都不受影响；S246(6) 钉住拓扑与预算（反向注释 `planWidening` 时 3 红）。B178 **不新增镜像机制**：只把 B176 的汇总日志从「每个重复节点一行」移到 `Solver.Session.finish()` 每趟一行，并让 `Choice.settleWhy` 在采样时快照 `Driver.settleBlock()` 的原因；镜像/回写/静音/候选枚举/搜索行为都不变。B177 **不新增镜像机制、只改搜索的加宽拓扑**：加宽阶梯从「每档 `new Solver.Session(root, …)`」改成同一个 `Solver.Session` 内逐档加宽（`planWidening` / `widenToNextRung`：保留层号/前沿/转置表，只换 beam 与节点预算并重置本档 promotion 配额），沙盒的镜像/回写/静音与候选枚举都不受影响；S244(5) 钉 Session 机制、S245(3) 钉 `QuickSession` 真的接线（注释掉 `planWidening` 调用时 S245 红 2 条、S244 仍绿），回滚＝不调 `planWidening`。B176 **不新增镜像机制、但新增一条判重口径**：判重键从 `Digest.ofCurrent()` 原串改成 `Solver.positionKey(digest, settled)` —— 引擎已静止（`Driver.settled()`：队列空 + currentAction 完成 + !usingCard）时用 `Digest.stable()` 去掉 `ph/q/use/end` 这类「中途相位」，未静止时仍保留原串（宁可多算一次，也不把半截状态合并）。它只改「这条世界线是否已经见过」，不碰镜像/回写/静音与候选枚举；S242/S243 钉住，反向验证 2 红。B175 **不新增机制**：面板「预计 -N HP / 预计无伤」只认原始掉血，成长抵扣退成注记（`Gameplay.hpPreviewText`，S241 六条 + 反向验证 3 红），`netHpLoss`/`netOf`/`Solver.better` 的排名口径未动；B174、B173、B172 与 B171 见上一条说明）。