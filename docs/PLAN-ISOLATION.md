# PLAN-ISOLATION — 沙盒隔离计划（只模拟战斗，其余一律不执行）

> 触发：user m01082「要修复隔离」、m01098「给我隔离修复的计划，不要让类似的bug出现，沙盒隔离好和战斗无关的东西。给我写进计划」。
> 状态：**已实施（2026-11-15，T1–T6 全部落地）**，实施结果见第 11 节；门禁按验收阶段约定由用户实机跑（不自动执行 `tools\run.ps1`）。
> 关联：docs/PLAN-BUGS.md B185（症状修补）、B187（本计划的 bug 条目）；docs/SANDBOX-MECHANISMS.md M50/M51；docs/PLAN-P6.md P6-97。

## 0. 一句话结论

B185 修的是**症状**（把窗口里开着的音频线关掉），不是**根因**（窗口内仍在执行 mod 的展示层代码）。
雨爱的 `heal` 钩子不在隔离名单里，它自己 `AudioSystem.getClip()` 开线；B185 从外部 `close` 别人的
`Clip`、又把「已经 close 的快照」写回字段，把一个「叠音」缺陷升级成了 JVM 原生崩溃。
修复方向不是再补一个 close，而是：**窗口内把 mod 的音频对象换成惰性替身，让代码根本走不到开线那一步**，
并把「非战斗副作用」从「按类隔离」改成**按副作用逐项登记 + 逐项门禁**。

## 1. 故障与现状（证据）

- 崩溃：`hs_err_pid17280.log`（21:22:02，JRE 1.8.0_144），`EXCEPTION_ACCESS_VIOLATION (0xc0000005, reading 0x0)`，
  native frame 指令 `48 8b 01`（`mov rax,[rcx]`，rcx=0）⇒ 空指针虚表调用；线程 `LWJGL Application`。
- 栈（自顶向下）：`DirectAudioDevice.nFlush` ← `DirectDL.flush` ← `YuAiMusic.stop()`
  ← `YuAiController.evaluate` ← `HealthSoundPatch$OnHealAmountShowEffect.Postfix` ← `AbstractCreature.heal(int,boolean)`
  ← `HealAction.update()` ← `Driver.runEndOfTurnQueue` ← `Driver.drain` ← `Driver.endTurn`
  ← `Legal.apply` ← `Solver.expandCore` ← `Session.expandAndRecord` ← `advance` ← `Gameplay.tick`
  ← `SolverMod.receivePostUpdate` ← `CardCrawlGame.update`。
- solver.log 同秒：`WARN audio quiet: closed 1 sandbox javax.sound clip(s) left looping by the window and put the live reference back: [yuaimod.YuAiMusic#currentClip]`，下一个窗口崩溃。整段会话 29 次 audio quiet 事件（26 次「close + 写回」、3 次 B169「释放保持释放」），`stopped the LIVE clip` 计数为 0。
- 雨爱字节码（`_tmp/yuai_javap.txt`，`javap -p -c`）：`play()` 只要 `currentClip != null` 就返回 true，**不再开线**；
  `stop()` 先 `isRunning()` 再 `flush()`，**flush 前不查 isOpen()**；`YuAiMusic$1.update` 收到 STOP 事件就 `close()` 并把字段置 null。
- 失败链：窗口内 evaluate 先 `stop()` 了**实机 clip L**（listener 关掉 L、字段置 null）→ `play()` 新建 clip A 写进字段
  → 窗口结束 AudioQuiet 关掉 A、把**已 close 的 L 写回** → 下个窗口 `stop()` 对 L 调 `flush()` → 原生空指针 → JVM 崩溃。
  `liveStops=0` 正是因为字段已被 A 覆盖，end() 的「快照 vs 现值」比较看不见「快照已死」。

**结论口径**：B185 的「已兜住」必须订正为「只消除了叠音，未隔离执行，并引入了崩溃风险」。

## 2. 目标（验收判据）

| 编号 | 判据 | 观测手段 |
|---|---|---|
| **G1** | 沙盒窗口内**不产生任何** `javax.sound` 调用：`AudioSystem.getClip()`、`Line.open`、`clip.open` 计数为 0 | 计数包装器（测试用）+ 真实冒烟门禁 S257 |
| **G2** | 窗口结束时 mod 的 `Clip` 静态只能是「仍 `isOpen()` 的实机对象」或 `null`；**绝不写回已关闭对象** | S255 断言 + 每次写回前 `isOpen()` 校验 |
| **G3** | 每个恢复动作前有守卫；关闭流程逐步校验，任何一步失败只记日志、不抛出、不中断后续恢复 | 代码审查 + 反向验证（去掉守卫必须红） |
| **G4** | 其它非战斗副作用（网络/存档/文件/统计/输入/渲染资源/Steam/剪贴板）在窗口内同样静默，逐项有替身或明确 known-limit | 第 5 节审计清单逐行核对 |
| **G5** | 隔离不了的东西必须**显式**：一条 WARN + 计数进 `report()`，禁止静默通过 | 日志断言（blind 字段、closedSnapshot、liveStops） |

## 3. 任务表

| 编号 | 任务 | 主要文件 | 验收 | 估时 |
|---|---|---|---|---|
| **T1** ✅ | L1 崩溃防护：`end()` 恢复前查快照 `isOpen()`；`closeQuietly` 每步前查 `isOpen()`；新增 `closedSnapshot` 计数与 WARN | `mirror/AudioQuiet.java` | S255 新增「快照已关闭 → 写 null 且计数」断言 | 0.2 天 |
| **T2** ✅ | L2 真隔离：窗口内把受保护 `Clip` 静态换成惰性替身（`java.lang.reflect.Proxy` 或 `SandboxClip implements Clip`） | `mirror/AudioQuiet.java`（可选新增 `mirror/SandboxClip.java`） | S255 新增「窗口内字段是替身」「替身上的 stop/flush/close 不触实机」「play 不开线」 | 0.5 天 |
| **T3** ✅ | L3 顺序修正：`AudioQuiet.begin()` 从 `World.java:336` 挪到 `StaticWatch.afterInstall()`（:348）之后；`end()` 保持在 `beforeUninstall()`（:383）之前 | `mirror/World.java` | S255 新增「StaticWatch 不会把替身当实机值快照/写回」 | 0.2 天 |
| **T4** ✅ | L4 审计清单：把第 5 节逐行落成「替身类 / 门禁 / 缺口」，缺的写 TODO 并进未覆盖清单 | `docs/PLAN-ISOLATION.md`、`docs/SANDBOX-MECHANISMS.md` | 清单无空行；每行有状态 | 0.5 天 |
| **T5** ✅ 代码 | 门禁改造：S255 换新契约（含 B169 语义推翻）；新增 S257 真实设备冒烟（不可用则 SKIP 并记日志，**不计通过**）；全部反向验证 | `probe/Probe.java` | 临时禁用 T1/T2 后必须红；记录 sha | 0.5 天 |
| **T6** ✅ | 文档：PLAN-BUGS B187 结果段、PLAN.md 状态/复测条目、PLAN-P6 P6-97、WORKLOG、SANDBOX-MECHANISMS M50 订正 + M51、TESTING 观察点 | docs | 交叉引用一致，无残留占位 | 0.3 天 |

## 4. 方案细节

### 4.1 L1 崩溃防护（先止血，独立可回滚）

- `end()` 只写回**通过 `isOpen()` 校验**的快照；快照已关闭 → 写 `null` + `WARN audio quiet: snapshot clip was already closed ...`，计 `closedSnapshot`。
- `closeQuietly(clip)`：每步（stop/flush/close）前查 `isOpen()`；任一步抛 `Throwable` 只记日志。
- `end()` 整体 try/finally，保证后续字段与 StaticWatch 流程不被单点异常打断（对齐 B110 的教训）。
- 这一层单独就能消除本次崩溃；即使 T2 被回退，崩溃也不会复发。

### 4.2 L2 窗口内惰性替身（真正的隔离）

`begin()` 时把每个受保护静态的**非空 Clip**换成**同一个惰性单例替身**（按类加载器缓存），并把实机对象记进快照：

| 调用 | 替身行为 | 理由 |
|---|---|---|
| `isOpen/isActive/isRunning` | `true` | 让 `isPlaying()`/`play()` 的早退分支成立，代码不再往下走 |
| `open/start/loop/stop/flush/close/drain` | 空操作 | 不碰真实音频线，也不产生原生调用 |
| `getFramePosition/getMicrosecondPosition/getFrameLength/getMicrosecondLength` | `0` | 供 pause 的 `getFramePosition` 读取 |
| `setFramePosition/setMicrosecondPosition` | 空操作 | 同上 |
| `getFormat` | 记录到的实机格式（没有则 `null`） | 雨爱构造 `AudioInputStream` 需要 |
| `getControl` | `null`；若实测 `applyVolume` 对 null 不设防，改返回一个**不接线**的 `FloatControl`（构造器 public，不下发设备） | 避免替身自己 NPE |
| `getControls` | 空数组 | 类型正确 |
| `getLineInfo` | `new DataLine.Info(Clip.class, format)` | 类型正确，不引用真实 Line |
| `addLineListener/removeLineListener` | 空操作 | 不注册真实监听器（`YuAiMusic$1` 因此永远不会收到事件） |
| `equals/hashCode/toString` | 特判（同一替身相等，toString 带 sandbox） | 便于日志与断言 |

效果：`play()` 首指令看到 `currentClip != null` 直接返回 true ⇒ **G1 成立**（一次 `AudioSystem.getClip()` 都不会发生）；
`stop()/pause()` 打到替身，实机 clip L 既不被 stop 也不被 close ⇒ 本次崩溃链的两个前提同时消失。

`end()`：字段当前是替身 → 写回快照（快照 open）或 `null`（快照已关闭）；
字段当前是 `null`（mod 在窗口里自己置空）→ **也写回快照**（快照仍 open 时）。
**这一条推翻 B169 的「释放保持释放」语义**：B169 的假设是「mod 主动释放 = 意图」，但本次证明它可能只是窗口内替身行为
的回声；对音频类静态，只有「实机对象真的关了」才算释放。S255 的 C 段断言同步改写。

### 4.3 L3 与 StaticWatch 的顺序（不改会互相破坏）

`StaticWatch.afterInstall()`（`World.java:348`）晚于 `AudioQuiet.begin()`（:336）。若 begin 里已换成替身，
StaticWatch 会把替身当「实机值」快照，并在 `beforeUninstall()` 写回 ⇒ 替身泄漏到实机。
因此 **begin() 必须挪到 afterInstall() 之后**；end() 保持在 beforeUninstall() 之前（此时字段已是实机值，
StaticWatch 比较相等、不写）。快照已关闭时 end() 写 null，StaticWatch 的 B169 分支会保持 null，安全。
动完顺序要在 S255 里加「StaticWatch 未记录替身」的断言，防止以后有人再调次序。

### 4.4 明确不做的

- 不隔离 `CardCrawlGame.update` / `MusicMaster.updateVolume` 的 postfix（窗口内不跑，无实测症状）。
- 不把 `heal` 本身「假造」（那会改战斗语义）；隔离点在副作用一侧。
- 不引入 `-D` 开关（ModTheSpire 重拉会丢），行为开关只走 settings.txt。

## 5. 非战斗副作用审计清单（G4）

| 副作用 | 窗口内会不会发生 | 现有隔离 | 门禁 | 状态/计划 |
|---|---|---|---|---|
| mod 自建 `javax.sound` Clip/Line | 会（本次崩溃） | `AudioQuiet`（B185 只 close → B187 窗口内换成惰性替身 `mirror/SandboxClip`，卸窗口只写回仍 open 的实机引用） | S255(37)、S257(2) | ✅ B187（原生层只由 S257 覆盖，无设备则 SKIP 不算通过） |
| 游戏 mixer 音乐（`CardCrawlGame.music`） | 会 | `MusicMute`（B45） | 见 B45/M 段 | ✅ 见机制表 |
| 游戏 mixer 音效（`CardCrawlGame.sound`） | 会 | `SfxMute`（B65） | 见 B65 段 | ✅ |
| HTTP / metrics 上传 | 会（死亡屏） | `ExternalMute`（`Gdx.net` 代理） | 见 B47 段 | ✅ |
| 文件写入 / 存档重定向 | 会 | `ExternalMute`（`Gdx.files` 代理）、`SandboxWrites`、`PlayerPrefs`、`PrefsOverlay` | B136/B112 | ✅（逐项核对 B136 记录） |
| 统计/成就类写盘 | 会（模拟死亡/模拟时长会走 `CharStat`） | `Settings.UPLOAD_DATA=false`、`metricData.clearData()`（`World.install()`）、`PlayerPrefs`（写盘重定向，B25）、**`CharStat` 按玩家复制 + `CharStatGuard` 快照/还原实机字段（B213）** | 无独立门禁（用户自测） | ✅ B213 已盖住职业生涯记录（场数/游戏时间/击杀/连胜）：`CharStat` 不再共享、实机字段在 install/uninstall 前后比对还原；仍未覆盖的是 Steam 成就（下一行）与 mod 自定义的统计写入 |
| 鼠标输入 | 会 | `InputMute`（B37） | 见 B37 段 | ✅ |
| 手牌发光队列 | 会 | `GlowReset`（B182） | 见 M47 | ✅ |
| VFX / 粒子 / 渲染列表 | 会 | `VfxGuard`、`EffectSettle`、`RenderState` | 见对应段 | ✅ |
| 动画骨架 | 会 | `AnimationMute`（B34） | S255 噪声行 | ✅ |
| 剪贴板 / 外部进程 | 不会（引擎无此路径） | — | — | 不需要 |
| Steam 成就 | 不确定（需看 mod 钩子） | 无 | 无 | ⚠️ 未覆盖（T4：雨爱无 Steamworks 钩子；其它 mod 直接调 `SteamUserStats` 抓不到，按 I1 记为门禁不覆盖） |

> 表的用途：**新增任何 mod 交互（heal/audio/network/save）时先问「这是战斗状态吗」**；不是就隔离，并在本表加一行。
> 没有门禁的行不能标 ✅（沿用 SANDBOX-MECHANISMS 的维护规则 3）。

## 6. 防止复发的规则（I1–I3，进维护规则）

- **I1 门禁用假对象不能证明真资源安全。** S255 用 `S255Clip`（`java.lang.reflect.Proxy` 假 Clip）测了 close/还原，
  但真实环境是 `DirectAudioDevice`，假对象永远不会原生崩溃 —— 所以门禁全绿、实机崩溃。
  规则：涉及**原生资源**（音频线、文件句柄、D3D 纹理）的修复，门禁至少要有一条走真实资源；
  跑不了（无设备/无头）就显式 `SKIP` 并在 bug 条目里写「门禁不覆盖原生层」，禁止拿假对象当通过。
- **I2 跨窗口只换引用，不碰实机对象的生命周期。** 窗口内不得对实机对象调用 `stop/close/flush/dispose` 这类会改变
  生命周期的操作；只允许替换字段引用。任何跨窗口写引用必须成对 begin/end，且 end 只写回**通过 `isOpen()/isValid()` 校验**
  的引用；新增一类资源（Clip/Line/Image/连接）时必须在同一处登记，不能各写各的。
- **I3 沙盒默认不执行展示层。** 新增 mod 交互时先判定「这是战斗状态吗」；展示层（音频/动画/粒子/网络/存档/统计）
  一律隔离，并在 SANDBOX-MECHANISMS 加行、在门禁里至少覆盖「窗口内不产生该副作用」。

## 7. 验收（等用户允许再跑门禁）

1. `tools/build.ps1` + `tools/install.ps1`（用户已改 danger-full-access，不需要提权参数）。
2. `tools/run.ps1 -Gates "S255" -Mods "YuAiBloodMusic"`：新契约 37 条全绿；反向验证（把 T1/T2 任一处临时禁用）必须红。
3. `tools/run.ps1 -Gates "S257"`：真实设备冒烟；无设备时 SKIP。
4. 手工（用户）：半血 + 回血局面搜索一次，窗口内**不应**听到雨爱；搜索结束后实机雨爱照常；
   `solver.log` 出现 `audio quiet: ... 0 clip(s) opened` 一类摘要，且无 `cannot read`/`snapshot clip was already closed` 告警；
   连续搜索 10 次后任务管理器里游戏 CPU/句柄数不增长。
5. 崩溃复测：同一局面连续搜索 + 半血触发 heal ≥20 次，不应再出 `hs_err_pid*.log`。

## 8. ①代价 ②风险 ③对 mod 的影响

- **① 代价**：每个窗口多一次「扫描 + 替换引用」（字段数实测 1，微秒级）；替身做成单例、无每节点分配；
  内存只多一张字段表与一个替身对象。T1 的 `isOpen()` 是 JNI 调用，只在 end() 时发生，不在搜索热路径。
- **② 风险**：①替身返回的类型/值可能与真实 `Clip` 有细微差异（`getControl`、`getLineInfo`），mod 若对返回值做类型断言可能抛异常
  —— 按 4.2 表逐项给正确类型，并在 S255 里用真实雨爱类跑一次；②推翻 B169 的「释放保持释放」会让某些 mod 主动释放的引用被还原，
  若该 mod 依赖「我置 null 就是不播了」可能与预期不符 —— 这是有意的（窗口外必须与窗口内一致），记为已知边界；
  ③回滚＝还原 `World.java` 的两处挂点与 `AudioQuiet` 的改动后重编（T1/T2/T3 相互独立，可单独回退）。
- **③ 对 mod 的影响**：只在**沙盒窗口**内生效；窗口外雨爱照常播放、照常停止；面板输出、自动出牌、搜索结果、评分、
  候选枚举、与其它 mod 的共享对象均不变。可见变化：窗口内不再有雨爱声音（这正是目标），
  `solver.log` 的 audio quiet 摘要改为「替身拦截 N 次 + 写回 N 次」。

## 9. 已知边界

1. 只保护**静态字段**里的 `Clip`（实测雨爱就是静态）；实例字段、`List/Map/数组` 里藏的 Clip 抓不到 —— 记入未覆盖清单。
2. 替身只拦截 `Clip` 接口调用；mod 若绕过 `Clip` 直接 `AudioSystem.getLine(...)` 开 `SourceDataLine`，本方案不覆盖（需按 I3 新增一类）。
3. 实机 clip 若在窗口内被**真实**关闭（例如玩家在搜索期间手动关音乐，或曲目自然结束），快照 `isOpen()=false` 时按 G2 写 `null`，
   实机音乐会在窗口结束后停止 —— 这是正确语义，但会与「窗口内不动实机」有观感差异，记日志。
4. `getControl` 等返回值若有 mod 做严格类型断言，可能抛异常；届时按 4.2 表补具体类型。
5. 门禁覆盖不到「真实 `DirectAudioDevice` 在极端时序下的原生崩溃」；S257 只做一次开/关冒烟。

## 10. 文档更新清单（本计划落地时同步）

- `docs/PLAN-BUGS.md`：B187 段（本计划的 bug 条目，状态与结果）+ B185 段「已知边界」订正（叠音 ≠ 隔离，且引入崩溃）。
- `docs/PLAN.md`：bug 表 B187 行、收尾状态条目、安装 sha、复测清单新增「连续 20 次半血回血搜索不崩」。
- `docs/PLAN-P6.md`：P6-97（隔离收口，含 T1–T6）。
- `docs/PLAN-WORKLOG.md`：2026-11-15 B187 段。
- `docs/SANDBOX-MECHANISMS.md`：M50 订正为「B185 只 close，不够」+ M51「窗口内 Clip 用惰性替身」+ 维护规则新增 I1–I3。
- `docs/TESTING.md`：S255 新契约、S257 说明、手工验收 5 步。
## 11. 实施结果（2026-11-15）

**代码（4 个文件，B187 全部落地）**

- 新增 `src/main/java/sts1solver/mirror/SandboxClip.java`：`public final class SandboxClip implements Clip` 单例（`shared()`/`isSandbox(Object)`/`adoptFormat(Clip)`/`swallowedCalls()`）。`isOpen/isActive/isRunning` 恒 `true`；`open×3/start/loop/stop/flush/drain/close/setFramePosition/setMicrosecondPosition/setLoopPoints` 全空操作（生命周期的 6 个在 `AUDIO_CALLS` 里计数）；位置/长度/`getBufferSize`/`available`→0，`getLevel`→0f；`getFormat()` 返回第一次 `adoptFormat` 从实机 Clip 读到的格式（读不到用 `new AudioFormat(44100.0f,16,2,true,false)`）；`getLineInfo()` 给 `new DataLine.Info(Clip.class, format)`；`getControl/isControlSupported` 只认 `FloatControl.Type`，返回私有 `SandboxFloatControl extends FloatControl`（`super(type,-80.0f,6.0206f,0.1f,0,0.0f,"")`，不接设备），其它类型 `null`；`addLineListener/removeLineListener` 空操作。`FloatControl` 本身是 `abstract`，所以必须有这个子类（计划 4.2 写的「构造器 public」并不成立，实现时按真实抽象类改）。
- 重写 `mirror/AudioQuiet.java`：`Snap{value,running,open}`；`begin()` 逐字段把**所有非 final 的 Clip 型静态**（**包括当时为 null 的**）换成替身并 `swapped++`（比计划更强：null 字段也塞替身，否则 mod 在窗口内 `play()` 又会开真线）；已经是替身的字段记 `stale` 快照置 null（不留脏写回）；`end()`：替身→写回快照（快照 `isOpen()` 为假则写 `null` + `closedSnapshot++` + WARN）、窗口新建的实机 Clip→`stop/flush/close` + `closed++`、字段为 null→**也写回快照**（`released++`，推翻 B169）、字段已变回实机→检测 `liveStops`；`closeQuietly` 的 stop/flush 每步前查 `isOpen()`。`report()` 现为 `audio quiet: N mod clip static(s), M window(s), S stand-in swap(s), C sandbox clip(s) closed, P live reference(s) put back[, K field(s) cleared][, R release(s) restored][, D closed live clip(s) dropped][, T stale stand-in(s)][, L live stop(s) detected]`；测试钩子新增 `swappedForTest/clearedForTest/closedSnapshotForTest/staleForTest`。
- `mirror/World.java`：`AudioQuiet.begin()` 从 `MusicMute/SfxMute` 之后、`StaticWatch.afterInstall()` **之前**挪到**之后**；`uninstall()` 里 `end()` 仍保持在 `StaticWatch.beforeUninstall()` 之前。
- `mirror/StaticWatch.java`：新增 `snapshotValueForTest(Field)`（返回该字段被采样的实机值，未采样返回字符串 `"not sampled"`），供 S255 钉住「快照的是实机 clip，不是替身」。
- `probe/Probe.java`：S255 换新契约（**37 条断言**，5 个窗口 A–E：替身契约/play 早退/替身返回值/StaticWatch 顺序/窗口自建 Clip 被 close/覆写还原/释放还原（B169 推翻）/快照已死写 null/liveStop 检出/stale 清理 + 原雨爱可读性断言）；新增 **S257**（**2 条断言**：真实 `AudioSystem.getLine` 开 8kHz/8bit/mono 400 字节静音 → `loop(1)` → `stop()` 后仍 open、`close()` 后不 open；无设备时只 `Log.warn("S257 SKIP: …")`，**0 条通过**）。

**构建/安装**：`tools/install.ps1` → javac 21 `--release 8`，140 个源文件，jar **1,287,415 B**、sha256 `E85332F8C3B26B6B44C8CAF00CF04D9F2E3E1421013692557C38BB8E51E4A08D`，已装到 `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar`（安装需要写工作区外路径，用了 danger-full-access 一次性授权）。

**与原计划的偏差（都是实现时被编译器/语义推翻的）**

1. `FloatControl` 是抽象类，不能直接 `new` → 私有 `SandboxFloatControl` 子类（行为等价，仍是「只存数字、不接设备」）。
2. `adoptFormat` 是 static，第一版直接读写实例字段 `format` 编译失败 → 改为经 `INSTANCE.format`。
3. `begin()` 连 **null** 的 Clip 字段也换替身（计划只写「非空 Clip」）：雨爱 `stop()` 会把字段置 null，下一节点 `play()` 就会开真线——不补这一步 G1 不成立。这是「更严」而不是放宽。
4. S255 从计划里的「16 条扩到 ~30 条」实际落到 **37 条**（窗口 A 的替身契约细分到位置/格式/增益控件/StaticWatch 快照）。

**门禁状态**：按验收阶段约定（用户 m00732），本轮**没有**跑 `tools/run.ps1`；S255/S257 的反向验证也留待用户跑门禁时一并做（第 7 节第 2、3 步）。

**未做/未覆盖**：见第 9 节，全部保留；另加一条实践结论——`AudioQuiet.begin()` 若在 `StaticWatch.afterInstall()` 之前调用，S255 的 `snapshotValueForTest(fLive)==liveClip` 会立刻红，这条断言是防止顺序被改回去的唯一门禁。

## 12. 后续结论：计算期间雨爱重放＝已知边界，不修（2026-11-16，user m00495）

用户 m00415 要求「沙盒对雨爱零影响、不要引用雨爱用的函数」，m00495 决定「那不管这个bug」。技术定性已完成，三条路线均**不采纳**：

| 路线 | 做法 | 为什么不做 |
| --- | --- | --- |
| A 入口守卫 | `@SpirePatch(clz=yuaimod.YuAiController.class, method="evaluate", paramtypez={AbstractPlayer.class})` + prefix，窗口内 `SpireReturn.Return(null)` | 唯一能做到「窗口内完全不进雨爱、不碰字段」的方案，但要编译期依赖创意工坊 jar、并需自检补丁时序；用户不做 |
| B 设备层 | 自定义 `javax.sound.sampled.spi.MixerProvider`（`META-INF/services`），窗口内 `AudioSystem.getClip()` 给惰性替身 | 不引用雨爱但需 ~300 行 Mixer/Line/Clip 委托、全局劫持 JDK 音频单例，枚举顺序不保证，风险/工作量过大 |
| C 绕开 heal | 沙盒不调 `heal` 或替换其方法体 | 丢 `onPlayerHeal`/`onHeal`/`onNotBloodied`（Magic Flower、Mark of the Bloom、红骷髅），静默失真；且 `HealAction`/药水/遗物/power 仍会调 `heal`，绕不干净 |

**根因限制**（决定了 C 不可行、A 只能打在被调方）：MTS 补丁是加载期字节码插入、无运行期开关；prefix 的 `Return(null)` 拦不住别人插在 `AbstractCreature.heal` 上的 postfix（postfix 落在每个 RETURN 之前，包括 prefix 新增的那个）；`@SpireInstrumentPatch` 在 ordering −1 先执行，看不到 MTS 后插的 mod 调用。

**保留现状**：窗口内 `Clip` 仍换 `SandboxClip` 惰性替身（不崩、不残留、一次真线都不开）；残余症状是「计算期间雨爱放一段→卡一下→从 0 帧重放」，已由用户接受。若日后重启，按 A 路线做，先批准编译期依赖。

> 同类隔离缺口另见 `docs/PLAN-MOD-OBJECTS.md`（B192）：本文只处理「引擎/音频副作用」，**静态字段指向的活体内部状态**（如 `OstyManager.osty`）不在这套机制内，按该文档的 `StaticEntities`（引擎基类 + 图可达性，不特判 mod）方案处理。

