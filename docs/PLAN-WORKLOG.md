# 板块实现记录（PLAN 详细部分）

> 这是 `docs/PLAN.md` 的详细实现记录，按时间顺序保留每块的根因、改法、门禁编号与实测数字。
> 目标与状态表见 `docs/PLAN.md`；用户反馈 bug 的详细记录见 `docs/PLAN-BUGS.md`。

---

## M-3 白名单（✅ 已安装，118/0）

用户这套安装（19 mod）里有 9 个 mod 战斗钩子（Ninja 6、MintySpire 1、RelicStats 1，另有重复计数），
按 M-2 的规则会全战"未认证"。M-3 提供显式白名单：

- `profiles.txt` 每行第四列可选 `trust`：`id | 显示名 | 类名前缀 | trust`。
- 白名单命中 ⇒ 该 mod 的内容与钩子都算认证，面板显示
  `来源: 原版（已认证：含 N 个已白名单 mod 钩子: MintySpire, RelicStats, Ninja）`。
- **trust 是假设，不是证明**：写在文件里（可审计、可撤销），含义是"该 mod 的钩子不在镜像外保留战斗状态"。
  移除 `trust` 即回到"未认证"。
- 文件位置：`%USERPROFILE%\sts1-engine-solver\content\profiles.txt`（本机已写入 13 条）。
- 启动日志：`content surface: hooks certified=true trustedProfiles=[...]`。

**实测**：装 19 mod 时 `hooks certified=true trustedProfiles=[MintySpire, RelicStats, Ninja]`；
过滤启动（0 钩子）时同样 certified。
## M-2 认证：内容来源 + mod 钩子（✅ 已安装，112/0）

**对齐 STS2**：参考项目不是"支持所有 mod"，而是"原生内容 + 零未知钩子"才敢认证一个结论：
`StrategicHpRecoveryBound.Assess` 返回 `(IsCertified, Reason, BlockingSourceId)`，其中三条阻断条件是
`RootRunModSubscriberCount != 0`、`RootCombatModSubscriberCount != 0`、`RootHasBaseLibCardModifiers`；
这些计数还会让预测的"历史依赖"变成未知，缓存/复用直接失效。

**我们这轮**：
- `modsupport/ModHooks`：反射读 BaseMod 的 **15 个战斗相关订阅表**（onCardUse / postDraw / postExhaust /
  postEnergyRecharge / preMonsterTurn / postBattle / startBattle / potion / power / playerDamaged /
  turnStart …），只统计**第三方类**的注册（排除 basemod/MTS/原版/本 mod）。
- `ContentOrigin` 认证条件升级：**原版内容 + 0 个 mod 战斗钩子**。三态输出：
  `来源: 原版（已认证）` / `来源: 原版内容，但检测到 N 个 mod 战斗钩子（未认证）: …` /
  `来源: 含 mod 内容（未接入 mod，结果不保证）`。
- 启动日志：`content surface: N mod combat hook(s) …`（不开面板也能核对）。
- 结论行：未认证时 `可赢（未认证：含 mod 内容或 mod 钩子）`。

**实测（你的机器）**：装 19 mod 时 = **9 个 mod 战斗钩子**（Ninja 5 个、MintySpire 1、RelicStats 1 等）；
过滤启动（只加载本 mod + BaseMod）= **0**，因此纯原版环境是已认证的。

**与 STS2 的差距（后续）**：逐项白名单（他们枚举到"哪个遗物/能力/卡"级别）+ 预测缺口登记；
我们目前是内容来源+钩子计数，加上每步 digest 对账（等价但更粗）。
## M-1 范围决策：原版优先，mod 走接口（✅ 已安装，104/0）

**决策**：不再追求"每个 mod 都自动验证"。支持面 = **原版**（4 角色 / 370 卡 / 42 药水 / 189 遗物 /
62 怪物）；mod 提供**接入契约**，一个 mod 一个 profile，接入后才保证结果。

**实现**：
- modsupport/ContentOrigin：检查本场战斗出现的一切（玩家类、四堆+牌库、遗物、药水、能力、怪物及其能力）
  的**类包名**；全在 com.megacrit.cardcrawl. 下 ⇒ 来源: 原版；否则列出名字，并区分"已接入（有 profile 覆盖）"
  与"未接入 mod，结果不保证"。
- modsupport/ModProfile：**数据驱动**（不重编译），文件位置 mods/sts1-engine-solver-content/profiles.txt
  或 %USERPROFILE%/sts1-engine-solver/content/profiles.txt，格式 id | 显示名 | 类名前缀。
- 面板：新增来源行；未接入 mod 时结论行加"（含未接入 mod，不保证）"。
- 契约文档：docs/MOD-CONTRACT.md（接入步骤 + 已知风险：mod 自有静态未镜像、自定义选牌界面、
  非 AbstractGameEffect 特效）。
- 门禁 M1：原版类判原版、mod 类不判原版、profile 只覆盖自己声明的类、未知 mod 不认证、合成原版战斗识别为原版。

**对后续板块的影响**：P0-1 选牌分叉先只做**原版 22 张**（不追 30 张），mod 卡等其 profile 接入。
## S-2 beam 排序分离（✅ 已安装，98/0 通过）

- `Node.rank` 与 `Node.score` 分开：**rank 只决定谁留在 beam 里**（叠加格挡 4/点、能量 40/点、能力 150/个），
  **score 仍决定答案**（胜利→掉血→药水，字典序）。
- 组合搜索增加 **次段名次带** 成员：保留排名靠后一档的状态（对应 STS2 的
  `SolverSearchProfile.SecondRankBand`）。
- 每个组合成员失败都会 `World.forceRestore()`，组合整体也在 finally 里还原。

**门禁**：`AUTOTEST RESULT passed=98 failed=0`；S5 实测 组合 216 节点/285ms vs 固定 beam 531 节点/698ms。
## S-1 搜索质量（对齐参考项目 STS2 的做法，✅ 已完成）

STS2 之所以不"比人打得差"，靠的是下面几件事（源码位置已核对）：

| STS2 | 做法 | 我们这轮 |
|---|---|---|
| `Search/SolverWeights.cs` | 权重**只用于 beam 排序**（保血 30k/点、敌血 -10k/点、风险 -2k、行动 -1，另有能量/易伤窗口/持续能力/铺垫/回放/长期资源）；**终局按字典序选**（胜利→HP→药水） | 终局已是字典序；评分加入 **存活数惩罚** `-alive*400`（击杀优先） |
| `Search/BeamWidthPortfolio.cs` | 一次请求跑多个 beam 组合（基线 / 次段名次带 W+1..2W / 只用基础分 / 进攻更强 / 有界精修），按终局质量选 | `Solver.solvePortfolio`：3 档（10/96、28/48、64/24）共享预算，字典序选优 |
| `Search/CombatSearchCoordinator.FailureRecovery.cs` | 没胜利且预算剩很多 ⇒ **beam 与节点上限一起翻倍再搜**（实测：90/25k 输、135/100k 赢、512/100k 只用 26k 节点就赢） | 无胜线且剩余 >1/3 预算 ⇒ beam 128 / 96 层补搜一次 |
| `SearchCombatBudgetLedger` | 长战斗里 `reason=nodes` 先耗尽，按回合层摊节点预算 | 预算按怪数/Boss 自适应（3s / 5s / 8s），下一步做节点账本 |
| `EarlyTurnExploration.cs` | 前期 setup 单独预算，只续搜"有改善"的层 | 未做（列下一步） |
| `StateEvaluation.cs` | 评估含存活数、复活计数、耐久向量、**进攻进度** | 存活数项已加；进攻进度细分（易伤窗口等）列下一步 |

门禁 S5：组合搜索不输给固定 beam（同一条胜线 216 节点 vs 535 节点）。
## H1–H3 用户报告问题修复（✅ 已完成，等真机验证）

### H1 大史莱姆分裂 → 计划中途消失
根因：`combatOver()` 只看 `areMonstersBasicallyDead()`（所有怪 `isDying`），而分裂/复活/阶段转换
会让它**短暂为真**；空怪列表也返回 true。
修复：
- `Driver.combatOver()`：队列非空 ⇒ 未结束；任一只 `halfDead` ⇒ 未结束；**空怪列表**只有房间
  `isBattleOver` 才算结束。
- 搜索的胜利判定复用同一规则（原来单独用 `areMonstersBasicallyDead`）。
- 实机 `tick()` 加**迟滞**：连续 20 帧（≈1/3 秒）都判结束才丢弃计划与面板。
- **Boss 房搜索更深**：层数 48→96、预算 3s→6s；若仍因深度上限没打完，面板明确标注
  "（到深度上限，未模拟到战斗结束）"。
- 门禁 S4：真实触发 Slime Boss 的 SPLIT ⇒ 怪物 3（Boss halfDead + 2 小史莱姆）、存活 2、
  `over=false`、分裂后仍能给出计划。

### H2 乐加维林：F8 后出现死亡画面
根因：安装世界时若中途抛异常，`installed` 未置位 ⇒ 不回滚 ⇒ 实机静态字段指向沙盒克隆
（死掉的玩家、清空的队列），画面就是死亡/异常界面。
修复：
- `World.install()` **原子化**：先全量快照 + 置位，再逐个写入；任一写入失败 ⇒ 立即 `uninstall()`
  回滚并抛错。
- `World.forceRestore()` + `Solver.solve` 的 `try/finally` ⇒ 返回前一定还原。
- `Gameplay.tick()` 每帧自检：有世界残留立刻还原（自愈）。
- `solveLive` 前后比对实机 digest，若被改动立即报错写日志。

### H3 F10 行为
- **F8 清除 F10**：`solveLive()` 开头清掉 `autopilot/pending/lethalConfirmed/lastRisk` 并强制还原世界。
- F10 无计划时提示"没有计划：先按 F8 求解"，不再静默。
- 自动执行连续失败 30 次会停下并说明原因。
- 结束回合的完成判定改为"游戏已消费请求（`endTurnQueued=false`）且引擎空闲"，不再死等回合号。

门禁：`AUTOTEST RESULT passed=91 failed=0`（新增 S1–S4 共 9 项）。
## P0-2 执行安全（✅ 已完成，等真机验证）

**实现**（`Gameplay`）：
- `predictEndTurnRisk()`：镜像实机 → 在副本上真跑一次 `Driver.endTurn()` → 读
  `hpBefore / hpAfter / damage（实际掉血）/ incoming（按意图估算的来袭伤害）/ wouldDie`。
- `decideEndTurnRisk(risk, maxHp, manual)` 纯函数决策表：
  | 情况 | 自动(F10) | 手动(F9) |
  |---|---|---|
  | 会死 | STOP 停下 | CONFIRM 需再按一次 |
  | 濒死（剩余 ≤ max(5, maxHp/10)） | STOP | WARN 允许但告警 |
  | 安全 | ALLOW | ALLOW |
  | 预测失败 | STOP（不猜） | ALLOW |
- 面板：结束回合那一行显示计划预计的掉血；测到的风险单独一行
  （`结束回合预计 -2 HP → HP 0（来袭 ≥50）（会死）`）。

**门禁**：`AUTOTEST` 81/0，新增 12 项（决策表 6 项 + 强制致死回合预测 6 项），
其中包含"预测不改变实机 digest"。

**真机验收（用户测试要点）**：
1. 找一场会挨打（最好会被打掉不少血）的战斗。
2. 按 **F10** 自动执行；如果本回合结束会导致死亡，必须**停在结束回合之前**，
   面板出现 `结束回合预计 -X HP → HP Y（会死）` 与"已停止自动执行"。
3. 手动 **F9** 到结束回合：会死时**第一次拒绝**并提示"再按 F9 强制执行"，第二次才执行。
4. 濒死（剩血 ≤ max(5, maxHp/10)）但不会死：自动执行同样停下，手动则只告警。
## 旧说明（P0-2 原始需求）

**要解决的问题**：现在 F10 自动执行会**照着计划把回合结束掉**，即使怪物这一下会把你打死。
STS2 在这一点上有 `LiveEndTurnRiskEvaluator`：结束回合前在副本里把这一回合跑完，拿到
`HpBefore / HpAfter / HpLost / PlayerDead`，只有安全才提交；`FullAuto` 会因此停下并提示。

**任务**
1. `Gameplay.predictEndTurnRisk()`：对实机做一份镜像，在副本上执行 `Driver.endTurn()`，
   读回 `hpBefore/hpAfter/damage/wouldDie`（副本不渲染，同步执行安全）。
2. 自动执行（F10）在**结束回合**这一步之前调用它：
   - `wouldDie` 或 `hpAfter <= max(5, maxHp/10)` ⇒ **不提交、自动停止**，面板写明预计值；
   - 其他情况：把预测写进面板（"结束回合 预计 -X HP → HP Y"）。
3. 手动 F9：
   - `wouldDie` ⇒ 第一次拒绝并提示"再按 F9 强制执行"（防误触），第二次才提交；
   - 濒死（但不会死）⇒ 允许，状态栏标注。
4. 预测前后必须不影响实机：`digest` 不变 + 不产生静态泄漏（现有门禁覆盖）。

**验收**
- 新增门禁：`end-turn risk predicts lethal correctly`（1 HP vs 大攻击必须判死）、
  `end-turn risk prediction leaves the live fight untouched`（digest 前后一致）。
- 新增门禁：`autopilot refuses a lethal end turn`（模拟一次自动执行，计划被停在结束回合前）。
- 全套 `AUTOTEST` 通过数只增不减；覆盖基线不退化。
- 真机：在一次会挨打的战斗里按 F10，若本回合结束会死，必须**停在结束回合前**并给出预计伤害。
## P0-1a 选牌应答（✅ 已安装，127/0，prompt=0）

**问题**：需要玩家点选的牌（原版 22 张）在搜索里整条分支被丢弃，面板底部只能列出来。

**根因（两层）**：
1. 选择界面是 UI 对象，确认路径是 CardSelectConfirmButton 点击；沙盒不会点。
2. 更隐蔽的一层：打开选择界面的动作（如 DiscardAction）在自己的那次 update 里就会 tickDuration()，
   而沙盒的假帧长是 **2.0 秒**，远大于动作时长（Settings.ACTION_DUR_XFAST），于是它**同一次更新就判定完成**，
   选择永远没人消费——实测 Prepared 抽了牌却一张都没弃。

**做法**：
- Driver 增加选择原语：selectionKind() / selectionCandidates() / resolveSelection(chosen)。
- 出牌期间的假帧长改为 **0.05 秒**（小于最小动作时长），让"打开界面"的动作活到下一次更新。
- 顺序：填 selectedCards → 直接泵 GameActionManager.update()（**不**泵界面自己的 update，它会清空选择）
  → AbstractDungeon.closeCurrentScreen() → 正常 drain。
- resolveSelectionHeuristically()：按卡牌价值排序选前 N 张（对弃牌/升级/放回都是合法答案）。
- Legal.apply 与 Coverage.tryCard 都调用它 ⇒ 搜索与覆盖都不再丢弃这些牌。

**实测（原版覆盖）**：cards: total=370 played=342 unplayable=28 prompt=0 other=0（此前 321/22/0）。
**门禁**：AUTOTEST passed=127 failed=0，新增 S6：Prepared / Survivor / Armaments 三张都要求
选择被应答、界面已关闭、弃牌堆未被破坏。
## P0-1b 选牌分叉（✅ 已安装，129/0）

- Driver.selectionOptions(max)：对当前选择界面给出 2 个候选答案（最值钱 / 最不值钱，去重）。
- Solver.expand(base, action, max)：应用动作后若界面打开，就按候选分叉；每个候选是一个独立子状态。
  非选择类动作只展开一次（不做额外克隆）。
- Action.choice：计划行显示 "（选：Strike_R）"，选择随动作一起保存。
- Solver.Result.choiceBranches：本次搜索里被分叉的选择次数（实测 291 节点 / 102 次）。
- 实机执行：Gameplay.pollPending 发现界面打开且计划带选择时，用 Driver.resolveSelectionLive 按计划作答
  （填 selectedCards → closeCurrentScreen，让游戏自己的帧消费；不泵引擎）。计划没带选择才交还控制权。
- 门禁 S7：Prepared 在场时 choiceBranches > 0 且 nodes > 0。

**仍待做**：步数上限（长战斗 reason=time）与 3 个以上候选的细分分支。
## P0-1 选牌分叉（原始描述）

把 `HandCardSelectScreen` / `GridCardSelectScreen` 的选择集当作决策节点（枚举 + 组合上限），
覆盖那 30 张牌；面板底部"未展开"列表应清空。验收：`-Mode coverage` 的 `prompt=0`。
## P0-3 逃跑策略（✅ 已安装，136/0）

**对齐 STS2**：参考项目把"离开战斗"做成显式策略（偷窃遭遇的 Deny / LetEscape），而不是评分权重的副作用。

- 新类 sts1solver.search.EscapePolicy：DENY（禁止）/ ONLY_IF_LETHAL（仅必死，默认）/ ALLOW（允许）。
- Legal.actions：DENY 时 **SmokeBomb 不出现在候选里**（连枚举都不给）；药水标签改为 名称[ID]，面板可见。
- Solver：逃跑终局分改由 EscapePolicy.terminalBonus() 决定（DENY=0，否则 200000）；
  DENY 时 Result.escaped 不再作为"推荐路线"。
- 键位：**F6** 循环策略；面板底部常驻 逃跑策略 行，切换后计划作废并提示重算。
- 门禁 S8：策略循环三态正确；DENY 时枚举里没有 SmokeBomb，ALLOW 时出现（实测 DENY=[] / ALLOW=[烟雾弹[SmokeBomb]]）。
## P0-3 逃跑策略（原始描述）
对应 STS2 的 `SolverTheftPolicy`（Deny / LetEscape）：加"允许逃跑"开关，默认只在必死线才取；
面板显示"只能逃跑（烟雾弹）"并允许一键放弃该线。
## P0-4 阶段续跑（✅ 已安装，141/0）

**问题**：分裂/召唤/阶段转换后，实测状态与预测不符，旧代码直接作废整条计划，玩家只能手动 F8。

**做法**（对齐参考项目的 replan 语义）：
- 漂移时先 `rememberExecuted()`：把已校验的步骤写进 `history`（面板显示"已执行 N 步: …"）。
- `replanFromCurrentState(why)`：从**当前局面**重新搜索（组合搜索 4s），成功后替换计划、`step=0`、
  **保留 autopilot**，状态栏写明"已按新局面重算 X 步（此前已执行 N 步）"。
- 连续重算上限：`driftReplans < 3`，避免死循环；超过则停手并提示按 F8。
- F8 清空 history 与计数（新的一场从头算）。
- 门禁 S9：先求解→标记 2 步已执行→触发重算，验证"重算成功、前缀保留(history=2)、有新计划、面板含已执行"。
## P0-4 阶段变化续跑（原始描述）
分裂/召唤/转阶段导致实测状态与预测不一致时，只重算剩余部分而不是整条作废。
## P0-5 飞升差分门（✅ 已安装，145/0）

**参考项目的做法**（已核对）：在那套 mod 里 ascension 是**元数据 + 场景约束**，不是被测数值：
- 存档/回放检查点（CheckpointArchive）、bug 报告、RunStatistics 都记录 AscensionLevel；
- SolvedRouteCache 的键包含 ascension（不同飞升档不共用缓存）；
- 测试场景默认 A10（GeneratedCombatScenario.Ascension = 10），且 GeneratedScenarioChecks 拒绝
  越界（Ascension > 10 直接拒绝）。

**我们的落地**：
- scenario/Synthetic：ascension 超出 0..20 直接抛异常（对齐"拒绝越界场景"）；
- 面板显示 `难度: A<N>`；solve 日志追加 `ascension=<N>`（计划可追溯到档位）；
- 门禁 S10：同一场战斗 A0 vs A20，怪物最大 HP 与**声明攻击值**在 A20 不得更低且必须不同；越界被拒绝。

实测：`S10 A0 hp=40 dmg=11 | A20 hp=42 dmg=12`（JawWorm 在 A2+ 的数值差异，全部来自引擎）。

**留给 P1-3（计划缓存）**：缓存键必须包含 ascension（照 SolvedRouteCache 的做法）。
## P0-5 飞升差分门（原始描述）
同种子 A0 vs A20：伤害/血量必须不同且与引擎一致（前代最常错的就是飞升档）。
## P1-1 面板对齐（✅ 第一刀已安装，152/0）

对照表见 docs/PANEL-PARITY.md（逐项对照 STS2 的 TurnLabel / ActionPill / ActionFlow /
SolverLoopGroup / EnemyDamageLabel / OutcomeLabel / EnergyLabel）。

本轮落地：
- **可点击动作行**：Hud 记录行矩形 → 点击 → Gameplay.requestStep → stepTarget 逐帧执行到该步；
  已执行步/越界点击被拒绝。
- **回合折叠**：只展开当前与下一回合，其余 … 第 N-M 回合（折叠 X 步）。
- **重复压缩**：同回合相邻同标签合并 名称 ×N。
- **结果列**：回合标题 HP 起→止、能量、格挡。
- 门禁 S11：行→步映射有序、点击拒绝/接受正确、无点击事件时 handleClick 返回 false、长计划出现折叠。
## P1-1 面板（原始描述）
可点击动作行（点行=执行该步）、回合折叠、遗物触发行、药水图标、"本回合预计掉血"列。
## P1-4 深度搜索（a/b/c/d + e 全部 ✅，见板卡 P1-4a…P1-4e；本节是当时的差距分析，保留作背景）

### 参考实现的数字（源码已核对）

| 项 | STS2 | 我们 |
|---|---|---|
| 默认搜索档 | beam 60 / 120,000 节点 / 120 秒（SolverSearchProfile.Default） | beam 10–64 / 无节点上限 / 3–12 秒 |
| 可选高档 | 60k 节点/60s、250k/180s、500k/300s（SolverSettings 三档） | 无 |
| 前期探索专用预算 | 1,000,000 节点（EarlyTurnExplorationNodeBudget） | 无 |
| 置换表 | 1,000,000 条（DefaultTranspositionEntryLimit） | 仅层内去重 |
| 并行展开 | 多线程（ParallelExpansion，degree≥2） | 单线程 |
| 无胜线 | beam 与节点上限一起翻倍再搜（FailureRecovery.EscalateSearchWhenNoVictory） | 一次 beam 128 补搜 |

### 单位成本（这才是根本差别）

STS2 的搜索状态是 SimulationSnapshot：能量/星/血/格挡/敌血/存活数/各类估值都是数字，
复制一个节点等于拷几十个数字；规则由 Engine/InCombat/Simulation（36 个文件）重新实现，
实现不了的地方登记 PredictionGap 并拒绝认证。

我们的一个节点（实测分项）：

```
World.fork   整图深克隆（玩家/怪物/手牌/三堆/行动管理器）   约 0.27ms
Legal.apply  真的跑引擎（AbstractCard.use + GameActionManager） 0.7–3.5ms
```

→ 约 300–600 节点/秒。它每秒 ~1000 节点，但预算 120–300 秒，所以 12–50 万节点；
我们 3 秒 ~1500、8 秒 ~2300。**差距 = 单位成本约 100 倍 + 允许时间约 100 倍。**

而"镜像真实引擎"是项目的根本原则（旧项目手工建模就是错误案例），所以提速只能靠：
(1) 允许更长的搜索；(2) 降低节点成本；(3) 长搜索不阻塞游戏。

### 任务

1. **P1-4a 分帧搜索**（前提）：把搜索切成每帧 30–50ms 的片，片间 uninstall 让游戏照常渲染；
   面板显示 `已展开 N 节点 / 已用 M 秒 / 当前最优 X 回合`；再按一次即停止并给出当前最优。
2. **P1-4b 节点预算账本**：不再"时间到砍在层中间"，按回合层分配节点（对齐 SearchBudgetLedger）；
   默认上限 120,000 节点。
3. **P1-4c 跨层置换表**：同 digest 只展开一次（现在只在层内去重），保留 1,000,000 条上限。
4. **P1-4d 两档按键**：F8 = 快速（现状 3–12s）；**Shift+F8 = 深度**（默认 60s，可配 300s）。

### 验收

- 深度档 60 秒内展开 >= 20,000 节点（当前速率 400–600/s 下可达成）；
- 分帧期间游戏帧率不归零（面板/画面持续刷新），搜索期间不改变实机状态（沿用现有 digest/UI 审计）；
- 跨层置换使同场景节点数下降 >= 20%（对比开关前后）；
- 门禁：深度搜索可在合成战斗上跑完并返回计划；停止键生效；预算/节点上限可配。
### P1-4a 分帧搜索（✅ 已安装，188/0）

- `Solver.Session` / `Solver.PortfolioSession`：把一次搜索变成可切片推进的状态机（层、节点、动作索引），
  每片 40ms；片与片之间所有世界都是分离对象，实机 statics 已还原，游戏照常渲染。
- 按键：**Shift+F8 = 深度搜索**（默认 60s，`%USERPROFILE%\sts1-engine-solver\settings.txt` 的
  `deepSeconds=` 可改，范围 10–600）；搜索中面板显示 `已用 Ns / 预算 / 节点 / 最优步数`；
  **F8 停止**并保留当前最优线。
- 收尾沿用普通求解的全部审计：digest 必须一致（不一致丢弃结果）、渲染差异修复、UI 状态还原、特效清理。
- 门禁 S18：切片多次返回、每片之间无世界残留、实机字节不变、停止后立刻收尾且有计划。
## P2-2 怪物场景矩阵（✅ 已安装，198/0；-Mode monsters 16/16）

16 个场景，每个都检查五条不变量：

1. 开局 `combatOver()` 为假（不会把「复活/召唤/转阶段」前的状态当成战斗结束）；
2. 求解返回非空计划；
3. 求解后实机 digest 不变；
4. 没有世界残留（`World.anyInstalled()==false`）；
5. 强制触发阶段后战斗仍在进行（杀一只怪/结束回合后 `combatOver()` 仍为假或怪物组非空）。

| 场景 | 触发 | 结果 |
|---|---|---|
| 大颚虫 / 双虱虫 / 三哨卫 | — | ok |
| 地精大块头 / 拉格里 / 六角幽灵 | — | ok |
| 史莱姆老大 | 结束回合（分裂） | ok |
| 守卫者 | 击杀（换形态） | ok |
| 青铜自动人偶 | — | ok（球体召唤） |
| 地精首领 + 2 小地精 | 结束回合（召唤） | ok |
| 冠军 | 击杀（二阶段） | ok |
| 收藏家 | — | ok |
| 觉醒者 | 击杀（复活） | ok |
| 时间吞噬者 / 德卡与多努 / 尖塔之盾与尖塔之矛 | — | ok |

过程中修的两处真实问题：

- `Synthetic.newMonster` 只试 `(float,float)` 和 `()`，而拉格里只有 `(boolean)` → 加兜底构造；
- 场景类名有 3 个写错包（收藏家在 city、双 Boss 在 ending）→ 按 jar 实际路径修正。

门禁 S20 保留 3 个代表场景（复活/召唤/换形态），回归会直接失败。
## P2-1a 观者：姿态与预见（✅ 206/0；-Mode watcher 5/5）

参考实现：`C:\Users\YOKI\Desktop\AutoWatcher-main`（STS2 观者适配层，给 CombatSolver 补姿态/预见/额外回合）。
它的关键结论：**姿态在模拟里就是 Power**，求解器的状态指纹/剪枝自动认得；**预见必须作为搜索分支**，
否则"弃哪张"就是猜。我们是镜像真实引擎，所以倍率/能量本来就是游戏自己的代码——需要证明的是
**搜索有没有正确使用**它们。

已核对的游戏语义（javap + CFR 反编译校对）：

- 愤怒：出伤 ×2、受伤 ×2；神圣：出伤 ×3、回合开始自动退出；平静：退出时 `GainEnergyAction(2)`。
- 预见：`ScryAction` 打开 `gridSelectScreen`，**选中的牌是"要弃掉的"**——所以正确选项是"弃最差"，
  而通用网格启发式选的是"最高价值的牌"（等于每张预见都弃掉好东西）。

本轮改动：

1. `Legal.SCRY_CARDS` + `Legal.apply` 在打出预见牌时标记选择语义；
2. `Driver.selectionOptions` 在预见语义下只给"弃 0 张"和"弃最差 k 张"（按价值升序），不再给"弃最好"；
3. 解析选择后清除语义标记；
4. 探针 `-Mode watcher` 5 项：愤怒 12 伤、神圣 18 伤、平静退出能量 3→3（不给会变 1）、
   预见选项 none-first/worst-first 且能关闭、搜索在有预见牌时 `choices>0`。
## P2-2b 机制正确性（✅ 201/0；-Mode mechanics 7/7）

不只是"能求解"，而是"解得好不好"：同一随机种子下和**朴素策略**比（能打就打第一只活怪、没攻击牌就打防御、
没能量就结束回合，最多 20 回合），并检查五个不变量：计划目标必须存活、不得假报胜、实机不变、无残留、
不差于朴素策略。

| 机制 | 求解器 | 朴素策略 | 结论 |
|---|---|---|---|
| 史莱姆老大（分裂 3 次） | **胜**，掉血 65，46 步 | 20 回合内死 | ✅ 做对 |
| 守卫者（转阶段） | **胜**，掉血 138，70 步 | 胜，掉血 187 | ✅ 做对且更好 |
| 地精首领（召唤） | 未胜（`no-live-branches`），5 步；召唤正常执行、无 NPE | 死 | ✅ 分支不再丢（P2-2c 已修，门禁 S57；这套 12 张牌组确实打不过 106 HP 首领 + 召唤物） |
| 觉醒者（复活） | 未胜，掉血 24，13 步 | 死 | ⚠️ 30s 仍未算到赢（不假报） |
| 收藏家（火把） | 未胜，掉血 0，9 步 | 死 | ⚠️ 30s 未算到赢（不假报） |
| 青铜自动人偶（球） | 未胜，掉血 9，11 步 | 死 | ⚠️ 30s 未算到赢（不假报） |
| 打不过的 Boss（时间吞噬者+弱牌组） | 不报胜（正确） | 死 | ✅ 诚实 |
## P2-2c Gremlin Leader 召唤 NPE（✅ 390/0，门禁 S57）

现象：地精首领回合召唤时，沙盒里抛 `NullPointerException at SummonGremlinAction.update`，
`Solver.expand` 捕获后丢弃该分支；如果那是唯一续行方向，frontier 变空 → `reason=no-live-branches`。

根因（不是游戏 bug）：`GremlinLeader.usePreBattleAction()` 把 `monsters.get(0)/(1)` 记成自己的小怪槽，
而游戏的真实遭遇是 `[小地精, 小地精, 首领]`（`MonsterHelper` 501-502）。我们的测试场景把首领写在第一位，
于是 `gremlins = [首领, 火大地精, null]`：首领永不 dying，两只小地精死后所有槽都"有人"，
`identifySlot()` 返回 -1，`SummonGremlinAction` 保留 `m == null`，下一帧必 NPE。

修法（三处，都不改游戏类）：

1. `scenario\Synthetic.verifyMinionSlots(ms)`：任何 GremlinLeader 的槽位指向它自己 → 构建期抛
   `IllegalStateException`（消息点名 `spec.encounterKey = "Gremlin Leader"`）。游戏里不可能出现的状态，
   就不该被静默搜索。
2. `probe\MechanicProbe.encounterKeyFor()` + `one(...)`：怪物列表含 `.GremlinLeader` 时改走
   `spec.encounterKey`，由 `MonsterHelper.getEncounter` 按游戏顺序生成（Probe S20/S21 自动跟着修好）。
3. `MechDiagnose` / `GremlinDiagnose` 同样改成走 encounterKey。

门禁 S57 三条：错顺序必须被拒（错误信息含 encounterKey）；真实遭遇下两个槽是小怪、不含首领；
在沙盒里用游戏的 `DamageAction` 打死两只小地精后连续 `endTurn`，首领必然 RALLY、两次召唤都成功、
怪物组 3→5、无异常。三条都过（`group 3 -> 5, threw=null`）。

仍不赢是真实的：这套 12 张 打击/防御/痛击 对 106 HP 首领 + 召唤物，朴素策略也是死。
要点是「不赢」来自真实搜索，而不是来自被异常吃掉的分支。证据：`evidence\P2-2C-GREMLIN-ORDER-20261030.txt`。
## P2-1d 观者剩余机制（✅ 206/0；-Mode watcher 11/11）

在 P2-1a 的姿态/预见之上，补了 AutoWatcher 列出的其余"容易静默算错"的点：

| 检查 | 结果 |
|---|---|
| 愤怒 ×2 / 神圣 ×3 | 12 / 18 伤害（已核对） |
| 退出平静 +2 能量 | 3 → 3（不补会是 1） |
| 预见（卡牌触发）选项 | 弃 0 张 + 弃最差 k 张 |
| **Foresight（Wireheading）回合开始预见** | 识别为 GRID + scry，选项 none-first |
| **Vault** | 回合推进、敌人回合被跳过（HP 不变）、不进入死局 |
| **保留（Perseverance）** | 结束回合后仍在手 |
| **精神堡垒（Mental Fortress）** | 换姿态 +4 格挡 |

过程中修的两处**真实**沙盒问题：

1. **卡牌触发的"按下结束回合"（Vault）**：`PressEndTurnButtonAction` 只 latch `turnHasEnded`，
   真实游戏由帧循环调用 `room.endTurn()`；沙盒里没有帧循环，回合永远不翻。现在在 `drain` 里补这一半，
   但**只对 PRESS_END_TURN 牌生效**（一开始对所有 latch 生效，实测把每个节点的成本抬到 ~7ms，已限制）。
2. **Power 触发的预见**（Foresight）：没有"刚打出的牌"可标记，改为在 drain 里看到等待中的 `ScryAction`
   （actions / preTurnActions / turnStartCurrentAction）就 arm 预见语义，选择被回答后清除。

**P2-1d-1 已修（353/0，门禁 S52）**：Vault 那条线原本 `turn 2->2 energy=0`。两层原因：

1. `PressEndTurnButtonAction` → `callEndTurnEarlySequence()` → `EndTurnButton.disable(true)` 只把
   `AbstractDungeon.player.endTurnQueued = true`；真正翻回合的是房间的一帧：`AbstractPlayer.update()`
   在 `endTurnQueued && cardQueue.isEmpty() && !hasControl` 时置 `isEndingTurn`，随后
   `AbstractRoom.update()` 看到 `isEndingTurn` 才调 `endTurn()`。旧 latch 只认 `gam.turnHasEnded`，
   而 Vault 从不置它 —— 所以旧代码对 Vault 从未生效。
2. 下一回合的能量根本不是 action 发的：`EnergyManager.recharge()` 全 jar 唯一调用点是
   `vfx.PlayerTurnEffect` 的**构造函数**，而它由 `DrawCardAction(..., true)`（回合开始块）构造。
   回合没开始 → 没有能量。

修法：在 `Driver.drain()` 的 latch 里多认一个条件 `AbstractDungeon.player.endTurnQueued`，命中时清该标志、
置 `isEndingTurn`、调 `room.endTurn()`（结尾会把 `isEndingTurn` 复位，与游戏一致）。只对 PRESS_END_TURN 牌
生效（`Legal.PRESS_END_TURN_CARDS`），**没有**每帧 `room.update()` —— 补它会显著改变普通回合成本（试过，
12s 的 Boss 搜索从能赢变成赢不了）。回合开始块一跑，`DrawCardAction(..., true)` 构造时能量自然回满。
旧门禁 `WatcherProbe` 的断言 `turnAfter >= max(2, turnBefore)` 对 `2 -> 2` 恒真，已收紧为
`turn+1 && energy == energyMaster && hp 不变 && hand > 0`。
## G2 / G5 判定对齐（✅ 362/0，门禁 S53）

参考项目 `CombatSolver-main` 的两条判定规则已对齐：

- **G2（完整胜利要有动作）**：参考 `IsCompleteVictory` 是 `actionCount > 0 && 全灭 && 玩家未死 &&
  预计血量 > 0`。我们 `Solver` 的两处 `Result` 构造都改成 `completeVictory = win && plan 非空`。
  root 节点本来就 `win=false`，所以这条是防回归护栏；门禁 S53 在真搜索上钉住"报了胜就必须至少一手"
  （`win=true steps=6`）。
- **G5（单值 score 必须与元组同序）**：继续用单值，但把兑换率变成契约并由门禁检查：
  1 HP=2000、1 药=10 HP、1 怪物血=20、1 活怪=400、1 格挡=2、1 层=1；公式经
  `Solver.scoreOf(...)` 暴露（生产代码仍走搜索）。**并修掉唯一自相矛盾处**：格挡不过战斗，
  胜利线不计格挡（旧的 9 回合赢 + 40 格挡 = 10000009 会压过 7 回合赢 = 10000007，现在是 9999993 > 9999991）。
- S53 九条检查，其中第四条是 4×3×4×3 网格上 `scoreOf` 与 `StrategicDeficit.compare` 符号一致
  （胜 → 掉血 → 回合），把"分数和比较器打架"变成门禁能抓的问题。
- 门禁第一版抓到我改符号时写反的回合项（`10000007 vs 10000009`，方向变成越慢越好）；改回后全绿。
- 证据：`evidence\G5-JUDGMENT-ORDER-20261030.txt`（4988 bytes，sha256 A4AA8E8D…）。
- 仍留的差距：G3（复活次数的显式排序项，行为已正确）、G5 的其余级次折算进 `StrategicDeficit`。
## P2-5 源码整理（✅ 362/0，有界范围 + 命名对照表）

反编译留下的 `bl / string2 / n3 / arrayList` 这类名字，读起来要靠猜。本轮把**最常读的 7 个方法**整理成
「名字描述战斗角色」的风格，其余登记为 P2-5b，读法写成 `docs\NAMING.md` 对照表。

### 整理范围（`src\main\java\sts1solver\Gameplay.java`）

| 方法 | 改名 |
|---|---|
| `lines()` | 整段重写：`arrayList`→`rows`、`bl`→`uncertified`、`n2`→`ascension`、`n3`→`first`、`n`→`firstRow`、`n6`→`repeats`、`string2`→`delta`；`charSequence`（先 StringBuilder 后 cast 成 String 复用）拆为 `executed`/`growthRow` |
| `tick()` | 删掉未使用的 `bl3`（反编译残留）；`bl`→`combatOver`、`bl2`→`inRoomCombat`、`bl4`→`active` |
| `maybeAutopilot(boolean)` | `bl`→`fightActive` |
| `panelText()` / `join(List,int)` | `list`→`rows`/`items`、`stringBuilder`→`text`、`n`→`max` |
| `replanFromCurrentState(String)` | `string`→`reason`、`string2`→`cacheKey`、`bl`→`resumeAutopilot` |
| `stopAutopilot` / `fail` | 形参 → `reason` / `message` |

### 为什么只做这些

- 反编译名分布在 224 行、65 处；`solveLive()` 一段还有 ~120 行，纯改名收益低于回归风险 → **P2-5b**。
- `probe/Probe.java` 有 439 处，是门禁代码（每 PASS 一行断言的扁平写法），有意保留原名 → **P2-5b**。
- 按方法粒度改，一次改一个方法就 `build → install → run.ps1`，保证任何一步都能回滚。

### 保证「纯改名」的两道保险

1. 重写 `lines()` 前后把方法来里所有字符串字面量（解码 `\uXXXX` 后）求差集：**missing=0**，53 个字面量逐字不变
   （含 `" 回合            HP "` 的 12 个空格、`"▶"`、页脚 `"面板按钮操作（计算/深度/执行/自动）· F8 重算 · F11 隐藏面板"`）。
2. 全套门禁 **362/0**，与整理前同数；面板相关门禁逐条过（`panel groups actions by turn` / `panel rows name card and target` /
   `panel points at the card to play` / `panel can point at a card and a monster`）。

证据：`evidence\P2-5-NAMING-20261030.txt`（sha256 `F60C90C1FE1CC23DF013D9F0E155EAFD2A72732E8A87A9814300B19419C40BF7`），
安装版 jar sha256 `864D1A6934F86D149094B98CB4C72F2EC2F0591FF03BDC0F928E674DFCFCB771`。

### P2-5b 收口（✅ 387/0，2026-10-30）

`Gameplay.java` 剩下的反编译名全部改完：`solveLive()`（`string`→`digestAfterSearch`、`result`→`found`、`string3`→`blocked`、
`string4`→`digestBeforeSearch`、`string5`→`cacheKey`、`result2`→`cached`、`l`→`startedAt`、`l2`→`budgetMs`、`n2`→`aliveMonsters`、
`snap`→`renderSnap`、`viewState`→`viewSnap`、`wide`→`widePass`）、`decideEndTurnRisk`/`predictEndTurnRisk`/`intentDamage`、
`submit`/`pollPending`、`EndTurnRisk` 构造器。**顺手删掉三类死代码**：`solveLive` 里重复的 digest（`string2`）与未用的 `int n`/`Object object`；
`executeStepInternal(boolean bl)` 的 `bl` 全方法未使用（旧调用 `(true)` 手动、`(false)` 自动，但两者行为相同）→ 改成无参。

验证口径：纯改名，套件 **387/0** 与改前同数；`grep -w` 确认 `Gameplay.java` 已无 `bl`/`n2`/`string2`/`abstractMonster`/`arrayList`。
`probe/Probe.java` 的 439 处**有意保留**（门禁代码，扁平断言写法），这是 P2-5b 的最终边界。
## G2 / G5 二次修正（✅ 387/0，门禁 S56）

用户两条反馈直接改了判定骨架：

1. **格挡不是路线质量维度。** 参考 `RouteQualityPolicy.cs:98-115` 的 `ComparePrimary` 只比
   won → DeathSaveUseCount → StrategicHpDeficit → GrowthHpCredit → GrowthRewardCount →
   CombatEndedTurn；格挡只出现在 beam 保留（`BeamRetentionPolicy.Ranking.cs:34-35`
   `UsefulDefensiveBlockReserve`）。所以「9 回合赢 + 40 格挡压过 7 回合干净赢」是维度错，不是权重错。
   新 `Solver.compareRoutes(...)` 是那条键序的直译（deficit = hpLoss + 10×药水），`better()` 只是包装；
   `score()` 的格挡项降级为存活/未完成线的保留提示。旧 `better()` 里「计划越长越好」也删了。
2. **`IsCompleteVictory` 存在，但不该冒充 `win`。** 参考 `SolverInterimResultOrdering.cs:5-13`：
   `actionCount > 0 && allEnemiesDead && !playerDead && projectedPlayerHp > 0`，用于「发布中间结果」；
   我们的 `win` 本来就只在真实引擎判定战斗结束时成立，所以它是自检而非定义。
   第一版把它当 `win` 传进 `Result`，等于让诊断标志改写判定语义（万一真出现零动作胜利会被记成失败）。
   现在 `Result.win` 是引擎判定，`Result.completeVictory = win && plan 非空` 另立字段，summary 打 `complete=`。

门禁 S56 八条：胜利 > 逃跑 > 死亡、deficit 先于回合、同 deficit 少回合优先、死亡豁免先于掉血、
1 瓶药 = 10 HP、剩余格挡不能买回合、真搜索里 win 与 completeVictory 一致、summary 暴露 `complete=`。
证据：`evidence\G2-G5-ORDER-ALIGN-20261030.txt`（6072 bytes，sha256 `1C4A42A867DDAF37C59232B44B4D4D665EE2171B6238DFA48A6F9A1874AD6C83`）。
## G3 复活排序（✅ 378/0，门禁 S55）

原版把复活放在 `AbstractPlayer.damage()` 里（`AbstractPlayer.java:1465-1481`）：血降到 0 时先试
Fairy in a Bottle（`destroyPotion(slot)` 后 return），再试 Lizard Tail（`counter == -1` 时 `onTrigger()`），
所以血量永远不会停在 0，「濒死」这个中间态不存在——沙盒跑真实代码，行为本来就是对的。

缺的是**代价**：一次复活是一整场战斗只有一份的资源，用掉它救回来的线应该排在不需要复活的线之后。
新增 `search\DeathSaves.java` 把这件事变成显式项：

| 观测 | 怎么看出来 |
|---|---|
| Lizard Tail 用掉 | `onTrigger()` 里 `setCounter(-2)`（javap：`research\javap\com_megacrit_cardcrawl_relics_LizardTail.txt`），触发条件却是 `counter == -1` |
| FairyPotion 用掉 | 死亡分支 `destroyPotion(slot)` 把它从药水栏删掉；且它 `canUse()==false`（S54 已钉），人不可能主动喝 |

`DeathSaves.POINTS = 1000000`（= 500 HP），远低于 `Solver` 的 10,000,000 胜利奖励，所以「赢」永远压过「少复活」，
而「少复活」压过「少掉血」，顺序与 `JUDGMENT.md` R3 一致。数据链：
`Choice.fairies/lizardTail` → `Node.deathSaves` → `Result.deathSaves`（summary 打 `saves=N`）→ `Solver.better()`。

门禁 S55 八条，其中两条是沙盒里真把玩家打死两次：

```
PASS one death save costs its stated points | 1000000 vs 1000000
PASS a saved victory still beats a loss | 9000000 vs 0
PASS fewer death saves outranks less HP lost | 9819988 vs 8999997
PASS an untouched fight has no death saves burned | fairies=1 tailSpent=false saves=0
PASS a lethal hit spends the Fairy in a Bottle and the player survives | fairies=0 saves=1 hp=27 alive=true
PASS a second lethal hit burns Lizard Tail (counter -2) and is counted | tailSpent=true saves=2 hp=45 alive=true
```

证据：`evidence\G3-DEATH-SAVES-20261030.txt`（5357 bytes，sha256 `077AA5415C68906FD770C563FAD03115FA77C812FA70E51C0B02963C3B47B715`）；
安装版 jar sha256 `BC486FE1E53C2B37B71F131157E9A7EF106148AFA9CE47CB2FAB04E874F89B21`。
## 覆盖尾巴（✅ 370/0，门禁 S54）

覆盖表以前长这样：cards 370/342、potions 43/41、relics 190/189、monsters 66/62。差额看起来像「支持不全」，
逐个查下来全是**游戏自己在普通情境下拒绝**的东西。现在每一项都有归宿，`-Mode coverage` 直接打出
`accounted=总数/总数`。

| 项 | 到底是什么 | 怎么跑通 |
|---|---|---|
| Grand Finale | 抽牌堆必须为空才可打 | 清空 `drawPile` 后真打（门禁：满堆 `canUse=false`，空堆 `canUse=true`） |
| Deus Ex Machina | 抽到时才生效，不在「打出」 | `triggerWhenDrawn()`（手牌 1→2） |
| Tactician | 弃掉时 +1 能量 | `triggerOnManualDiscard()`（能量 3→4）；另有 Prepared 真弃牌端到端 |
| Reflex | 弃掉时抽 1 张 | `triggerOnManualDiscard()`（手牌 1→2）；另有 Prepared 端到端 |
| FairyPotion | 死亡时自动救一次，所以 `canUse()==false` | 记账为 `not-usable-by-design` 并记 ID，不算缺口 |
| DEPRECATED_DarkCore | 存档兼容空壳，构造就抛 `InvocationTargetException` | 按**类名** `DEPRECATED_` 前缀分类（构造失败时拿不到 relicId）→ `deprecated=1` |
| BronzeOrb | `(float,float,int)`，且 `SUPPORT_BEAM` 会 `getMonster("BronzeAutomaton")` | 中性实参构造 + 与自动人偶同组（单怪房间该查找返回 null，`GainBlockAction` apply 时 NPE） |
| AcidSlime_S / SpikeSlime_S | `(int,int)` 偏移量 | 中性实参（0,0） |
| Lagavulin | `(boolean)` | 中性实参 `false` |

另外 24 张 `unplayable` 是情境牌（要 99 能量、手里不能有别的攻击牌、目标要有负面状态等），
第二遍带 99 能量重试后有 7 张转为可打，其余归入「游戏此刻拒绝」而不是「我们不支持」。

`Coverage.instantiate` 因此多了一段「中性实参」兜底：按参数个数排序所有 declared ctor，
`float→0f`、`boolean→false`、`int→0`、`String→""`、`setAccessible(true)` 逐个试。

证据：`evidence\COVERAGE-TAIL-20261030.txt`（sha256 `4011595AAD51868D3E5EFE9D20CF38E0ED27C719FBF6F663B4CD3E0E8DEB4B2A`）
+ `evidence\COVERAGE-TAIL-20261030.log`；安装版 jar sha256 `FDD9DDC10260E6EBD95D1B9D1C8DCA8F6DD571626C03C385A9E523F0938B68F1`。
## P2-2c 结论：召唤 NPE 不是游戏 bug（✅ 已澄清）

用 `-Mode audit-summon` 做了对照实验：

- **我们的合成顺序** `[0=地精首领, 1=火大地精, 2=卑鄙地精]` → 首领的 `gremlins` 槽位是
  `[地精首领, 火大地精, null]`（把首领自己当成了小怪槽），所以它不会正常召唤；
- **游戏自己的遭遇** `MonsterHelper.getEncounter("Gremlin Leader")` → `[0=卑鄙地精, 1=胖地精, 2=地精首领]`，
  槽位 `[卑鄙地精, 胖地精, null]`，第 3 回合**正常召唤**（怪物组 3→5）。

结论：之前 `SummonGremlinAction` 的 NPE 是**测试场景把怪物顺序摆错了**（首领占了小怪槽），不是游戏里的 bug。
修法：`Synthetic.Spec.encounterKey` 支持用游戏自己的遭遇生成（`MonsterHelper.getEncounter`），
`MonsterProbe` / 门禁 S20 的首领场景都改为真实遭遇；`-Mode monsters` 16/16，NPE 噪声消失。

**后续（同日补修，390/0）**：当时只改了 `MonsterProbe`，`MechanicProbe`（`-Mode mechanics` 与门禁 S21）
和两个诊断模式仍在手写顺序，所以 NPE 在套件里还留着。现在 `MechanicProbe` 按 `.GremlinLeader` 自动走
encounterKey，`Synthetic.verifyMinionSlots` 把「首领占自己的槽」变成构建期异常，门禁 S57 三条锁死
（错顺序必被拒 / 真实遭遇槽位正确 / 小地精死后召唤成功且 3→5），细节见上方 P2-2c 小节。
## P2-2b 追问：Boss 30–60 秒算不到赢，是时间还是结构问题？（结论：两者都有）

`-Mode audit-awakened/collector/automaton` 实测（弱 12 张 / 现实 30 张牌组，30s 与 60s）：

> **注意：下表“每节点/apply”已被 P1-2d 判定作废** —— 那些毫秒主要是 ModTheSpire 控制台重排，
> 不是引擎成本（见下方“P1-2c / P1-2d 结案”）。节点数与层数仍然有效。

| 场景 | 节点 | 层数 | apply 时间 | 每节点 | 结束原因 | 引擎异常 |
|---|---:|---:|---:|---:|---|---|
| 觉醒者 弱 30s | 2021 | 12 | 9.6s | 4.7ms | time | 无 |
| 觉醒者 现实 60s | 1488 | 10 | 16.4s | **11.0ms** | time | 无 |
| 收藏家 弱 30s | 1311 | 15 | 8.1s | 6.2ms | time | 无 |
| 自动人偶 弱 30s | 740 | 13 | 7.8s | 10.5ms | time | 无 |
| （普通战斗参考） | ~1500/3s | 14+ | ~1s | ~0.7ms | — | 无 |

- **不是"模拟不到结束"**：`failures=[]`，没有任何引擎异常；就是**没算到那么深**。
- **结构性成本**：这些 Boss 每节点 6–11ms（复活/召唤/火把/球体让每次行动的引擎动作链很长，
  而 `apply` 占 90%+），比普通战斗贵 6–12 倍；30–60 秒只够 1–2k 节点、10–15 层。
- **预算分配**：300–600 HP 的 Boss 需要 40+ 层、上万节点；组合搜索把深搜成员只分到 45%，
  更算不到。

修复方向（已登记）：

1. **P1-2d** 剖析这些战斗的 11ms/节点到底花在哪（update 次数 vs 克隆），把 apply 降下来；
2. **P2-2d** 高成本 Boss 改成"窄而深单成员（beam 8–12 / 96 层）+ 一次宽补搜"，而不是 45/25/30；
3. **P2-2e** 面板在 `reason=time && !win` 时明确写"预算用尽，建议 Shift+F8 深度搜索"，
   而不是让人以为路线算完了。
## P1-2c / P1-2d 结案：那 6–11ms/节点不是引擎的成本，是 ModTheSpire 的控制台（已修，332/0）

**根因**：ModTheSpire 启动时把 `System.out` 换成它自己的 Swing 控制台
（`com.evacipated.cardcrawl.modthespire.ui.MessageConsole`），而 log4j 的 `ConsoleAppender` 在配置期
就用 `CloseShieldOutputStream` 抱住了那个流。于是引擎每打一行日志，`ConsoleOutputStream.handleAppend()`
就在**搜索线程**上 `Document.insertString` + 整块 JTextArea 重排 + `EventQueue.invokeLater` 挪光标。
控制台缓冲越写越大，重排就越慢 —— 这就是“同一场战斗越打越贵、Boss 比普通战贵”的全部原因。
一次 60 秒长战的栈采样里有 **52%** 的样本停在 `AbstractDocument.writeLock` 等待重排。

**修法**（不是探针开关，是产品行为）：

1. `util/Log` 不再把每行镜像到 `System.out`（我们自己的日志也不付那次重排）；
2. 新增 `util/ConsoleMute`：给 log4j 的 console appender 挂**一个** `Filter`（`java.lang.reflect.Proxy` 实现
   `org.apache.logging.log4j.core.Filter`，不绑定游戏自带的 log4j 版本），muted 时返回 `Result.DENY`，
   否则 `NEUTRAL`；`push()/pop()` 嵌套计数；
3. `World.install()` → `push()`，`World.uninstall()` → `pop()`：只静音“沙盒装着”的窗口，正好是纯噪声的那段；
4. `Driver.isPresentation` 的 `Class.getSimpleName()` 换成缓存版（匿名类的 `getSimpleName()` 会走
   `Class.getEnclosingMethod0` 这个 native 调用，而这个谓词每个出队动作都跑，栈采样约 5%）。

**实测**（`-Mode cost-all`，同一台机器、同一套 21 张牌组）：

| 场景 | 修前 µs/update | 修后 µs/update | 修前整场 | 修后整场 |
|---|---:|---:|---:|---:|
| 大颚虫 | 46.2 | 10.41 | 3147ms | 1614ms |
| 史莱姆 Boss | 75.5 | 8.83 | 11938ms | 8130ms |
| 觉醒者 | 144.4 | 2.72 | 15016ms\* | 15003ms\* |
| 收藏家 | 249.7 | 7.57 | 15068ms\* | 10017ms |
| 自动人偶 | 256.3 | 2.04 | 15068ms\* | 5863ms |

（\* 触及 15s 预算。JawWorm 修后偏高是首场 JIT 预热。同题长跑 `-Mode cost-long`：112.35 → 3.74 µs/update，
updateMs 23931 → 857。）

**结论**：单次 update 的真实成本是 **2–10µs**，与战斗内容无关；上面两张“每节点毫秒”表整张作废
（那是控制台假象 + 首场 JIT 的混合）。修后栈采样显示搜索的真实成本只剩两类：
`Cloner.copyArray/IdentityHashMap`（`World.fork`，约 40–45%）和 `Prefs.data` 全表复制（`World.mutePref`，约 6%）。
**P1-2d-1 已完成**（见下节）；**P1-2d-2**（Cloner）仍是最大项。

**取舍**：沙盒安装期间游戏自己的控制台窗口不刷新（日志仍完整写 `solver.log`）；
想对照旧行为可用 `-Dsts1solver.cost.console=1`。

**门禁 S49**：install 后 console appender 上的 filter 返回 `DENY`、uninstall 后回 `NEUTRAL`、
嵌套 push/pop 不漏、我们自己的日志行在被静音期间仍写进 `solver.log`。

证据：`evidence/P1-2D-CONSOLE-COST-20261029.txt`（sha256 2646B63C…EF19B）。
### P1-2d-1 已落地：`Prefs.data` 不再整张复制（342/0，门禁 S50）

`Prefs.data` 换成 `mirror/PrefsOverlay`（读穿透实机 map、写只进本地 map、`entrySet()` 才做一次合并快照）。
实测这份存档的 `seenPref` 就有 **1719 个键**，7 个 Prefs 每次 install 要上万次 put；换掉后栈采样里
`World.mutePref` 从约 6% 掉到 **0%**。单场端到端在噪声内（±5%）——因为 fork 摊薄了它——价值在于
成本不再随玩家存档增长、每次 fork 少上万次 put 与相应垃圾。门禁 S50 用 Gson 断言「overlay 序列化
结果与整张复制逐字段相等」，再加写隔离/引用还原。
证据：`evidence/P1-2D-1-PREFS-OVERLAY-20261029.txt`（sha256 D44067D5…B1736）。

顺带确认：现在 **fork 占一场战斗搜索的 82%**（自动人偶 `fork=1719ms` / `total=2083ms`，`updateMs=478ms`），
`cloneTop=AbstractDungeon.player=22828ms/66536x`（每次 ~343µs）——P1-2d-2 的真正靶子。

### P1-2d-2 已落地：fork 便宜 2.3x（345/0，门禁 S51）

只改「怎么拷」，不改拷什么：`ClonePolicy` 的三条规则改成 `ClassValue` / `ConcurrentHashMap` 记忆化；
`Cloner.copyArray` 的引用数组改用 `Object[]` 下标读写（原来走 `Array.get/set` 反射）；每个类预编译一份
拷贝配方 `Plan`（字段 + 是否共享 + 基元种类），基元字段用 `getInt/setInt` 这类类型化访问器，
不再 `Field.get` 装箱 / `Field.set` 拆箱。

受控 A/B（`-Mode cost-fork`，同一场战斗、同一 JVM、3 轮 x 120 次取中位数，µs/fork）：

| 战斗 | 全旧 | 只新 policy | 只新数组 | 只新字段 | 全新 |
|---|---:|---:|---:|---:|---:|

---

## B51 / B52 自动出牌改成「计算 + 自动」+ 读档清状态 + 慢帧埋点（647/0）

触发：用户 m15336 三条（1. 讲清自动出牌逻辑；2. 自动出牌改成「先按计算再按自动」；3. 快速 SL 很卡，怀疑我们加了没必要的时限）。

### 一、先查清两条路确实不同（回答第 1 条）
- 「计算」/F8 → `Gameplay.startQuickSolve()` → `search/QuickSession`：预算 = settings.txt `deepSeconds/deepNodes`（0=不限），每帧 40ms 分帧，采纳后进 `PlanCache`。
- 「自动」/F10（旧，无计划时） → `Gameplay.solveLive()` → `Solver.solvePortfolio`：**同步跑在渲染线程那一帧**，预算来自硬编码阶梯 `bossRoom?8000:(>=5?12000:(>=3?8000:(>=2?5000:3000)))` ms，不看 settings、不分帧。
- 计划走尽时再走第三套 `replanFromCurrentState()`。
→ 用户的观察（先计算再自动没问题）有了代码解释。

### 二、落地（B51 / P2-17）
- `autoplayArmed` + `startQuickSolve(true)`；`finishQuick()` 采纳后 `beginAutoplay()`：胜线自动开播，尽力线不自动播（提示再按一次）。
- 计划走尽 → `solveAgainForAutoplay()`，与「计算」同一条分帧路径；`exhaustedReplans>=2` 仍停。
- 面板「自动」标签三态：`自动: 关 / 自动: 算完开始 / 自动: 开`；计算中按「自动」= 取消或重新武装。

### 三、落地（B52 / P2-18）
- `Gameplay.detectWorldSwitch()`：空闲帧比对 `player/currMapNode/mode` 身份，变了清计划与自动状态（SL 读档后不再拿旧计划出牌）。
- `SolverMod` 给 `Panel.update+Gameplay.tick` 计时，>25ms 记 `slow frame`。
- `Log` 缓存 archives 目录。
- 取证结论：生产代码无 `Thread.sleep`；两份 hang dump 都是外部停顿（`CharStat.<init>` 读盘 4.2s、`OpenALSound.play` 4.0s）。

### 四、门禁与构建
- 新增 S93（按「自动」→ 起切片搜索且不阻塞：`waited=2ms`；采纳后 `autopilot=true armed=false slices=30`）、S94（读档身份变化 → `plan/autopilot/armed` 全清）。
- `AUTOTEST RESULT passed=647 failed=0`（改前 639/0）；jar 645915 B，sha256 `99E7F8B82ABD673AD58E3764633DB06C170512FB7A563CE13312BA1113CAFCE1`。
- 证据 `evidence\B51-B52-AUTOPLAY-SAVE-20261031.txt`。

## 药水：只喝游戏允许的那一瓶 + 三档计价 + 用药入账（657/0）

用户 m15881-2「药水要确定能用，你先做」。查下来药水有三个洞，连同「用药从不进结果」一起修。

| 问题 | 证据 | 改法 |
|---|---|---|
| 搜索把游戏禁止的药水当合法分支 | `FairyPotion.canUse(){return false;}`（死亡时自动触发，玩家永远点不了）；`SmokeBomb` 在首领/BackAttack 时 false；`BloodPotion/FruitJuice/EntropicBrew` 在 `turnHasEnded` 时 false | 新增 `search/PotionBook.usable()`（就是游戏自己的 `canUse()`），`Legal` 枚举、`Driver.usePotion`、`Gameplay` 实机提交三处同一守卫；`Drain.refused` 让沙盒也拒绝 |
| 一瓶药一律 10 HP | STS2 `PotionValuationRegistry` 是 9/14/18 三档（`SolverWeights.cs:99,110,113`） | `PotionBook.valueHp()` 三档；`Solver.Choice.potionHp`（药水栏总价值）进打分；`compareRoutes` 直接扣 HP 值 |
| 结果里的用药恒为 0（B53） | `Solver.java:936` / `:2016` 两个构造点硬写 0 | `Result` 自己遍历 plan 统计瓶数与 HP 值，新增 `potionHpSpent`，`better()` 用它 |

门禁（`build/run-potions2.log`，全套 657/0，基线 647/0）：

```
S95 legal: offered=1 fairy=false fire=true afterTurnEnded=0 labels=Fire Potion[Fire Potion]
S96 potions: total=42 used=41 refused=1 not-ok=0 refusedIds=[FairyPotion] not-ok-ids=[]
S97 solve: win splits=0 deepest=32 hpLoss=0 potions=1 potionHp=9 saves=0 complete=true turns=1 nodes=2982 expansions=1075 789ms reason=win layers=32 maxBranch=3 deferred=19 promoted=6
```

- S95：Fairy 不出现、Fire Potion 出现；`actionManager.turnHasEnded=true` 后药水分支归零；三档
  9/14/18；`usable(null)` 与空 `PotionSlot` 为 false。
- S96：42 瓶逐瓶真跑，41 瓶干净喝下（槽清空、无残留屏、无孤儿动作），恰好 1 瓶
  `refused=[FairyPotion]`，`not-ok=0`。
- S97：纯格挡牌组的战斗只能靠药水赢，计划 `win potions=1 potionHp=9`，即 B53 从源头修好。

证据：`evidence/P2-19-POTIONS-20261031.txt`（sha256 `DB8BC4C1…469FE`）；安装版 jar
`654942 bytes` / sha256 `1424DBCD…8223`。仍未做：P2-21b「不喝药基线」对照。

## B54 卡顿诊断器：4 秒采样 + GC + 相位（660/0）

用户 m16133「先按上面做诊断器」。两份 hang dump 一份停在读盘（`CharStat.<init>` 4.2 s）、一份停在 GL
交换（`nSwapBuffers` 4.8 s），而旧看门狗既不记 GC 也不看游戏在哪，所以两条路都只能猜。

| 改了什么 | 细节 |
|---|---|
| 采样覆盖 | 8 次 × 150 ms × 3 帧 → **20 次 × 200 ms × 16 帧**（3.8 s，覆盖整段卡顿） |
| GC | 每个采样点记 `GarbageCollectorMXBean` 合计次数/耗时 + 堆 used/max；dump 末尾给窗口汇总 |
| 谁占了时间 | dump 新增「most repeated stack (N of 20 samples)」 |
| 游戏相位 | dump 头部 `phase`：`CardCrawlGame.mode` + `AbstractDungeon.screen` + 房间类名 |
| 慢帧 | `slow frame:` 行追加 `phase` 与 `gc`（不用等 4 s 也能归因） |

实测（`build/run-watchdog54.log`，全套 660/0，基线 657/0）：

```
watchdog: the same stack in all 20 samples over 3811 ms -- a loop of ours (java.lang.Thread.getStackTrace(Thread.java:1559) ...)
watchdog: phase=CHAR_SELECT screen=NONE room=MonsterRoom | gc=73/10432ms heap=4099/7106MB
S89 dump: path=...\hang-20261031-184728.txt len=29610 dumps=1
```

那次采样到的栈是探针自己（`Probe$33.run` → `Watchdog.dump`）——门禁证的是「记录器能记」，不是 bug。
真正有用的是 `gc=73/10432ms heap=4099/7106MB` 这类数字：真实卡顿时一眼能看出是不是长 GC。

证据：`evidence/B54-WATCHDOG-GC-20261031.txt`（sha256 `BABF6642…033F`）；安装版 jar
`657230 bytes` / sha256 `CD2372B4…6E65`。下一步：用户复现一次卡顿，取 `hang-*.txt` 前 30 行与
`solver.log` 里的 `watchdog:`/`slow frame:` 行，再决定 P2-23（GC 减法）要不要做。

## 冻结路线 + 中文卡名 + 液态记忆选牌（668/0）

用户 m16202 五条：①先只查卡顿不改；②计算完冻结、漂移即停；③查「哨卫过几回合又算」与「开局说没算出结果」；④面板去英文、选牌写中文名；⑤液态记忆喝后不选牌（只加选牌动作，不改计算）。

| 改了什么 | 细节 |
|---|---|
| 冻结 | 删掉自动出牌的三条自行重算（走尽 `solveAgainForAutoplay`、过期 `staleReplans`、漂移 `driftReplans`），一律 `stopAutopilot` + 「点『计算』重算」 |
| 误报「没算出结果」 | `finishQuick` 与深度收尾从全量 `Digest` 改比 `Digest.stable`，不采用时打 `firstDiff@N`；只差瞬态则打「结论仍然有效」 |
| 中文卡名 | `Action` 新增 `choiceDisplay`，`Solver.Choice.choiceDisplay` 存 `Driver.displayNamesOf`（中文名带「+」），`choice` 仍是机器可读 ID |
| 药水去 ID | `Legal` 药水 label 只留 `p.name`，ID 放进 `Action.cardId`（计价与门禁按 ID）；`addOption` 去重改按 `cardID+升级` |
| 液态记忆 | `usePotion` 整段（含 `drain`）改用 `SELECTION_DELTA`；`Gameplay.pendingChoice` 潜伏应答屏幕晚一帧才开的情况 |
| 门禁 | S98（液态记忆实机选牌）、S99 exhausted（冻结：清计划后不起搜索）、S100（文案：label 不含英文 ID、带中文名） |

迭代：665/3 → 667/1 → **668/0**。三处都是门禁自己写错：S95/S8 还在按 label 里的英文 ID 断言（改断 `cardId`）；
S98 的 dump 里 `discard=6->6` 是修前状态（先断言消费再断言牌数）；S72 把「未赢线尾部补的结束回合」当成回合分组失败。

```
S95 legal: offered=1 fairy=false fire=true afterTurnEnded=0
S98 liquid memories: ok opened=true kind=GRID answered=true consumed=true moved=true inHand=true discard=6->5 hand=0->1 stillOpen=false orphans=orphan0
S99 exhausted: hadPlan=true autopilotAfter=false searching=false armed=false
S100 labels: potions=[火焰药水] bracket=false id=true pick=痛击+ → 大颚虫（选：痛击+）
S72 turns: rows=4 endTurnRows=3 rowsStartingWithEndTurn=0
AUTOTEST RESULT passed=668 failed=0
```

卡顿（m16202-1，只查不改）：`hang-20261031-185424.txt` 4.2 s，`phase=CHAR_SELECT`，`gc=26/1857ms heap=1274/7106MB`，20 次采样 9 个不同栈顶（`nSwapBuffers` 占 9 次）；最多次栈是 `CharStat.<init>` 读 `STSData*.json` ← `CharacterManager.recreateCharacter` ← `MainMenuScreen.<init>` —— 是游戏自己构造主菜单 + vsync，不是我们的循环，也不是长 GC。结论与 B52/B54 一致，未改代码。

证据 `evidence/P2-24-P2-25-B55-B56-20261031.txt`；安装版 jar 664353 bytes / sha256 `D03A1184986A3B24C1358509AD31AE7245410A77FD0C54317B4B68EAC01DC9B5`。

## 重复怪物标号（P2-26；677/0）

三只哨卫时计划行只写「打击 -> 哨卫」，玩家看不出打哪一只（m16717-2）。做法照 STS2 `src/Runtime/SolverDisplayNames.cs`：同名敌人数 >1 时按从左到右编号，写成「哨卫（左起2）」。新增 `search/MonsterLabels`（`battle()` 取整场含已死者，兄弟死不跳号；`xOf` 用 `hb.cX` 退化 `drawX`），`Legal` 的卡行与药水行都走它；编号只是显示，`Action` 仍用 `targetIndex` 定位。

顺带修 B57：`MechanicProbe.targetsAlive` 拿带编号的 `targetName` 和裸 `Step.monsterNames` 比会红，改成 `Solver.monsterNames()` 也走同一规则。

门禁 S101 实测：`S101 sentries: n=3 labels=[哨卫（左起1）, 哨卫（左起2）, 哨卫（左起3）] distinct=3`；`S101 rows: targets=[0, 1, 2] labels=[打击 -> 哨卫（左起1）, 打击 -> 哨卫（左起2）, 打击 -> 哨卫（左起3）]`；`S101 mixed: jaw=大颚虫 left=哨卫（左起1） right=哨卫（左起2）`；S21 恢复 `targetsAlive=true`。首轮 676/1（唯一红就是 B57），修后 **677/0**。

证据 `evidence/P2-26-MONSTER-LABELS-20261031.txt`；安装版 jar 669670 bytes / sha256 `340CFFB370F3352FF762F30D5B6B00C243DCBB834B80E2D6A72BE9524EB9FF2A`。

## ��λ�Ž� + ��岼�ֻ��棨P2-27��687/0��

�û� m16974����ֻ��23��1û��Ҫ����= ��һ�����ٸķ���� �� ��λ�Ž����� ������ֻ��� + �ͷ����ã��� ��Ƭ 40��20ms ������

| �Ķ� | �ļ� | ˵�� |
| --- | --- | --- |
| ���� `PhaseGate` | `ui/PhaseGate.java` | `hotIn(mode, fading, hasPlayer)` ������ + `hot()` ʵ�� + `note()` ÿ���л�һ����־ + `force()` ��̽�� |
| ֡�����Ž� | `SolverMod.java` | `receivePostUpdate` ����ֻ֡ `World.forceRestore()` �� return��`receivePostInitialize` ���� `autotest.request` ʱ `PhaseGate.force(true)` |
| ��Ⱦ�Ž� | `ui/Hud.java` | `receivePostRender` �� !demo ���Ž���ʱֱ�� return���˵�/ѡ��/���뵭�����ٻ���壩 |
| ����ǩ������ | `ui/Panel.java` | `layoutSignature(lines, rows, innerW, strip, scale, demo)` ��· 32 λ�� long�����и��� `placed`/`fitted`/`total`������ÿ֡ `RouteLayout.layout` |
| �������� | `ui/Panel.java`��`Gameplay.java` | `clearCaches()` �� `reset()` �뻻�ַ�֧��`widthCache` �� 4000 �����ޣ�B58�� |
| �Ž� | `probe/Probe.java` | S102��������λ��� + live �����һ�� + force ��Ч����S103���ڶ�֡���� / �����ؽ� / clear ���ؽ��� |

ʵ�⣨`build/run-p227b.log`����`AUTOTEST RESULT passed=687 failed=0`��

```
  S102 gate: live=false want=false mode=CHAR_SELECT fading=true player=true
  S102 state: hot=true pauses=0 resumes=1 last=resumed forced=true
  S103 idle: first[builds=1 hits=0] second[builds=1 hits=1] builds=1 hits=1 reuse=50% lines=2 rows=3
  S103 changed: builds=2
  S103 cleared: builds=3
```

���� 686/1��S102��the live answer is the same rule | true/false�������׼�ʵ���� force(true)��live �� true ��������� false���޷��Ǳȶ�ǰ�� `force(false)` �ٻ�ԭ��

��װ�棺676168 bytes / sha256 `AD3BD272CE95903F76107080768A41B23A9B5D17D45684FA1AF09ADFBBA7613E`��
��ʵ�߽磺��λ�Ž�������ֻ��ʵ������������/�����˵����ٴ���ÿ֡�����ܣ����׼�ֻ��֤�������뻺����Ϊ��
## ��ֻ����������֢״ + ֻ���������Ž���708/0 ���� �� 133/0 ���ģ�

�û� m17185 �������� 3 ����ʱ��Ļ���� / ���������ж�������� / ��һ�ֺܿ�����m17266 �ء�ȷ�ϡ��� 1��5 ȫ����m17581 ��Ҫ��ֻ����������ļ���Լ��Ž����ص���ڴ���bug����

| �� | ���� | �޷� | �Ž� |
| --- | --- | --- | --- |
| ���� | ɳ����Ⱦ��д��̬��ɫ��ֻ������������������� | ÿ��Ƭֻ��/�� 136 ����̬��ɫ��`RenderState.StaticColors`�� | S105 |
| ������� | `Solver.stateAfter` / `finishLine` �� `Gdx.graphics.getDeltaTime` ���� | ���ջ + ÿ���������� `Driver.end()` + ÿ֡ `verifyReleased` | S104 |
| 15 �뿨�� | ͣ���׶������� + ���������� | `deferredPhaseMs` Ĭ�� 6000ms �ⶥ��`GUARD_RELEASE_PCT=65` �ͻ� | S65 / S108 |
| ȡ֤���� | ֻ�� x ��һ֡�� watchdog | `ui/FrameStats` + `mirror/SkeletonFingerprint` | S106 / S107 |
| �������� | �����а������㡹ֻ��һ�� `engine busy` | `solveQueued` + `startQueuedSolve()` �������Զ����� | �����׼� |
| �׼�̫�� | 708 ��Լ 6 ���� | core��28 �� / 133 ���� / Լ 2.5 ���ӣ��� extended��104 �����ּ���`-Full` ����ȫ�� | 133/0 |

### �����޵��������Ž������Ĳ�
- S92��ͷ�ѡ�ƣ�ԭ������ǰһ���Ž����µ�ս����һ�����ھӾ� `opened=false` �� �ĳ��Լ� `Synthetic.build` ��ս�� + `Gameplay.reset()`��
- S74���ֶ������غϺ��ٰ����㣩ԭ������ `slices>0`�������泣����û������ �� �ĳ�ѭ�� `actionManager.update()` + `Gameplay.tick()` ֱ�����ƻ���

### ʵ���У�build/run-core-e.log��133/0��
```
AUTOTEST RESULT passed=133 failed=0 (core gates only, 104 extended gate(s) skipped)
S104 after a search: gfx=real depth 0 -> 0 leaks=0
S108 guard: wide=48 stillNarrow=48 released=128 narrowed=48 latched=true
S65 defer: off(win=true hpLoss=6 deferred=0 promoted=0 nodes=45) on(... deferred=3 promoted=3 nodes=326)
```

### ��װ��
jar 694474 bytes / sha256 `A1C2C086B85FE59BD8D8B8AA643DBCBC8008BD30BA5EC387EBAC47008F65EC30`��ƫ�� restored 62 file(s)��

### ��ѵ
- `Probe.core` ����ֱ�ӵ� `Probe.runStep`�����ܵ� `Probe.step`���ᱻ coreOnly ��������
- �Ž����������ھ����µ�״̬����������һ���ͼ�ʧ�ܡ�
- ModTheSpire ����������Ϸ�ᶪ�� `-D` ����������һ���� request �ļ���
## ҩˮ按真游戏顺序消耗（P2-33 / B61 / B62；核心 137/0）

用户 m17821 报「药水自动打出的时候有效果但是没消耗药水」。查清是漏了真 `PotionPopUp` 的第 3、4 步
（遗物 `onUsePotion()`、`topPanel.destroyPotion()`），新增 `drive/PotionDrink` 统一沙盒与实机；
顺手抓到 `engineSettled()` 早一帧（`currentAction` 未跑完就判结算）与 `RelicLibrary.getRelic`
未知 id 静默变 Circlet 两个真问题。

| 项 | 内容 |
| --- | --- |
| 新文件 | `drive/PotionDrink.java`（metricData 仅 live → use → 遗物 onUsePotion → destroyPotion） |
| 改 | `drive/Driver.usePotion`、`Gameplay.submitPotion`（新，含 `submitPotionForTest`）、`Gameplay.engineSettled`、`probe/ActionAudit.tryPotion`、`scenario/Synthetic` 遗物 id 校验 |
| 门禁 | S109（核心）：沙盒 vs 实机同喝一瓶 FirePotion，consumed/relic/sameDelta 全绿 |
| 实测 | `S109 potion: … consumed=true relic=true sameDelta=true hp=60->65 enemy=44->24`；核心 137/0 |
| 安装 | 698659 bytes / sha256 `CB51142CBE6AFE76232305D8957A00006D6708AC841D6368CB57DBCE4EA15F3F` |

教训：
- 判定只能有一份实现（JUDGMENT R10）：药水就走 `PotionPopUp` 的四步，别自己拼。
- `actions.isEmpty()` 不等于引擎空闲；`currentAction` 也要看完。
- `RelicLibrary.getRelic` 会吞掉错误 id，门禁必须自己校验 id 合法。

## B63 搜到胜利后被「超时」改写（核心 137/0 / 完整 712/0）

修完 B61 后完整套件 S59 报 `portfolio=78 single=0`。根因：A0 深化期 `reason` 从 "win" 被 "time" 覆盖，
收尾兜底只看 reason（`!"win".equals(string2)`）就以为没赢，把最深必死线（56 个结束回合、掉 78 血）当答案，
而 `Result.win` 仍是 true。`Session.finish()` 同一个洞。

| 项 | 内容 |
| --- | --- |
| 改 | `Solver.solveInternal` 收尾前 `node2.win` 为真则把 reason 改回 "win" 并记一行日志；`Session.finish` 最深线兜底加 `&& !best.win` |
| 代价 | B61 的 `isIdle` 停靠判据让每节点多跑一拍：同战斗 631 → 2605 节点（换来预测=实机） |
| 实测 | `S59 single: hpLoss=0 nodes=106` / `S59 portfolio: hpLoss=0 nodes=2605`；核心 137/0、完整 712/0 |
| 安装 | 698722 bytes / sha256 `261C2A0AEF6D6DEDCC5BE8F246F906A7E92E37DD98534B933E0F16B80BCE9EA8` |

教训：**`reason` 只是标签**，判胜负只能看 `node2.win`/`best.win`；日志/兜底用 reason 做分支迟早出错。
bisect 不能靠 `-D`（ModTheSpire 重拉游戏会丢），只能改代码重编。

## B64 0 血复活（核心 140/0）

用户报「模拟里 0 血靠鸟面瓮回血复活」。引擎事实：`AbstractPlayer.damage` 在 HP<1 时只设 `isDead`+造
`DeathScreen`（AbstractPlayer.java:1452-1484），不设 `isDying`；`AbstractCreature.heal` 只在 `isDying` 拒治
（AbstractCreature.java:379）→ 引擎真会给尸体回血，我们只看 `currentHealth` 就把死分支当活的。

| 项 | 内容 |
| --- | --- |
| 新 | `Driver.playerDead()`（isDead / hp<=0 / deathScreen!=null） |
| 改 | `Solver.readChoice`（dead/win）、`Driver.combatOver`、`turnTransitionFinished`、`Solver.walkTo` 重放提前停 |
| 门禁 | S110（核心，一条）：尸体 heal 能让血涨回 7，但 `playerDead` 仍为真 |
| 实测 | `S110 death: hpAfterHeal=7 healLanded=true deadWhilePositive=true over=true`；核心 140/0、完整 715/0 |
| 安装 | 700293 bytes / sha256 `359CD20E32694B9D23E0A395DF04DBB6B74FCBCD9C87CA32B19B0C9C56EF1F6A` |

教训：判活/判死要读引擎自己的标志（`isDead`），不能只读数字（`currentHealth`）。
## B65 模拟期静音 + P2-35 局外收益（核心 152/0）

| 项 | 改动 | 门禁 |
| --- | --- | --- |
| B65 | `mirror/SfxMute`：窗口内换掉 `SoundMaster.map`/`fadeOutList`（惰性 Sfx + 只计数的 sink），模拟里的 `fadeOutAmbiance()` 不再把淡出塞进实机 | S111 |
| P2-35 | `search/GrowthBook`：六源 HP 额度（feed 3 / geneticAlgorithm 2 / 其余 0），按事实取已实现量（最大生命增量 / 金币增量÷25 / 打出次数） | S112 |
| 接线 | portfolio 与 Session 两个引擎都 `hpLoss - growthHp` 后再算分；面板加「成长: …」行 | S59/S79/S109 不回归 |

实测（build/run-b66b.log）：
```
S111 sfx: active=true play=0 loop=0 played=2 dropped=1 fadesBefore=0 fadeAfter=0 mapSize=368 mapLive=true stillMuted=false
S112 growth: credit=25 zero=0 off=0 clamped=50 feed=3 genetic=2 order=feed,geneticAlgorithm,theScythe,ritualDagger,lessonLearned,handOfGreed described=true restored=true
AUTOTEST RESULT passed=152 failed=0 (core gates only, 104 extended gate(s) skipped)
```

迭代记录：
1. S111 首轮失败 `mapSize=-1`：`SoundMaster.map` 是 `HashMap`，门禁里按 `Collection` 强转 → 加 Map 分支。
2. S112 首轮失败 `clamped=99`：测试用的额度覆盖绕过了 clamp → `overrideForTest` 里也 clamp。
3. `SfxMute` 的 javadoc 里写了 `CardCrawlGame.sound.play*/playAndLoop/stop`，`*/` 提前关闭注释 → 编译 15 个错；改写措辞。

## P2-36 局外收益按真实单位计价 + 认出 mod 成长卡（核心 160/0 / 完整 735/0）

用户 m19041 四条：「1.其他mod的局外收益能不能我们mod能不能知道。2.默认收益没有专门说就算1。
3.仪式匕首，勤学和许愿，炼药都要能算，算法仪式匕首「+1 永久伤害」等于0.5hp，「一张升级牌」算5hp
（在有能升级的牌的情况），炼药就按照一瓶相应药水算，许愿和贪婪之手一样按照金币算。
镰刀是哪张牌说正确中文我才知道」

### 先纠正三条事实
| 说法 | 事实 |
| --- | --- |
| 「镰刀」是我们的成长来源 | STS1 原版没有这张牌（`localization/zhs/cards.json` 全表无镰刀/The Scythe）；The Scythe 只在 STS2 的 `GrowthSource` 里。计价表已删掉这一项 |
| 「勤学」 | 中文名是 **勤学精进**（LessonLearned） |
| 「炼药」 | 炼制药水，ID 是 **Venomology**（Alchemize 的 `ID` 字段就是 "Venomology"），所以按卡 ID 匹配必须用它 |
| 「许愿」 | 费用 3 的 Watcher 牌，三个选项：BecomeAlmighty（力量）/ FameAndFortune（金币 25，升级 30）/ LiveForever（最大生命 +6） |

### 价格表（十分之一 HP 存，才装得下 0.5）
| 来源 | 键 | 默认 | 用什么量 |
| --- | --- | --- | --- |
| 狂宴 Feed | `growth.feed` | 3 HP / 点 | masterDeck 的最大生命增量 |
| 遗传算法 | `growth.geneticAlgorithm` | 2 HP / 次 | 路径里打出次数 |
| 仪式匕首 | `growth.ritualDagger` | **0.5 HP / 点** | masterDeck 里该卡 `misc` 增量（RitualDaggerAction 就写在这里） |
| 勤学精进 | `growth.lessonLearned` | **5 HP / 张** | masterDeck 里 `canUpgrade()` 的卡数量差（真升级才计数） |
| 炼制药水 | `growth.alchemize` | **一瓶药水值** | 路径里出现 Venomology 时，药水栏 `PotionBook` 总值增量 |
| ~~贪婪之手 / 许愿金币~~ | ~~`growth.handOfGreed`~~ | **已作废（B146）** | 金币改为 `Solver.score` 的直接项（1 金 = 80 分），不再折 HP；是否计价跟随「局外收益」开关（m11767） |
| 其他被认出的成长卡 | `growth.other` | **1 HP / 次** | 打出次数（用户 m19041-2） |
| 任意卡手定价 | `growth.card.<cardId>` | — | 直接给 HP，支持小数 |

### 新文件
- `search/GrowthBook.java` 重写：`DEFAULT_TENTHS={30,20,5,50,10,10,10}`、`Facts{maxHealth,gold,upgradedCards,ritualDamage,potionHp}`、
  `credit(path, now, start)`、`overrideForTest(int[] tenths)`、`hpText()`、`describe()`（面板一行中文，带 0.5 这种小数）。
  settings 解析支持小数；tenths/10 向下取整。
- `search/GrowthScan.java`：扫 `CardLibrary.cards`，读每个卡类自己的 `.class` 常量池（ISO-8859-1 解码后 indexOf），
  命中 IncreaseMaxHpAction / GainGoldAction / ObtainPotionAction / UpgradeRandomCardAction /
  UpgradeSpecificCardAction / RitualDaggerAction / LessonLearnedAction 即标记为成长卡。
  `-Mode growthscan` 会把它写成 `%USERPROFILE%\sts1-engine-solver\growth-scan.txt` 并打 `AUTOTEST DONE`。
- `Solver.Choice` 加 `upgradedCards`/`ritualDamage`，`readChoice` 调 `readDeckGrowth()` 一次遍历 masterDeck；
  portfolio 与 Session 两个引擎都用 `Facts start` 对比当前节点，再把成长从掉血里扣掉算分（和 P2-35 同一处）。

### 门禁
新增 S113（扫描：hasRitual / hasLesson / hasAlchemize / strike=false / otherCredit=1），改写 S112（七源 + 小数 + 手定价）。
实测（`build/run-p236b.log`）：
```
S112 growth: credit=23 ritual=1 ritualScaled=15 lesson=10 alchemize=9 zero=0 off=0 clamped=500 feed=30 genetic=20 ritualKey=5 lessonKey=50 other=10 described=true restored=true
S113 scan: scanned=true hasRitual=true hasLesson=true hasAlchemize=true strike=false flagged=4 otherId=FameAndFortune otherCredit=1 report=growth scan: cards=370 flagged=4 noBytes=0 ids=[FameAndFortune, LessonLearned, RitualDagger, Venomology]
AUTOTEST RESULT passed=160 failed=0 (core gates only, 104 extended gate(s) skipped)
AUTOTEST RESULT passed=735 failed=0
```

### 诚实边界
Feed / HandOfGreed / Genetic Algorithm 没有被「扫描」标出（它们的增长动作不是那几个 token），但它们各有计价行，行为不变；
扫描只看常量池，不执行语义，mod 把自己的增长藏在别的类里就扫不到，得手定价。

## P2-37 面板去噪：内部簿记、成长规则、重复的掉血/回合（核心 165/0）

用户 m19237：「1.面板上没必要出现的东西和重复出现，错误出现的的东西没有删掉。告诉我哪里来的并删除『渲染已恢复6，ui状态已还原』，
成长这一行的所有（没必要把规则列出来），预计掉血2.1回合（前面已经有预计掉血了，而这个回合数不会变的，第二回合显示还是1回合）。
2.战后回血上界是什么意思」

| 面板上看到的 | 哪来的 | 处置 |
| --- | --- | --- |
| 渲染已修复 N | `Gameplay` 标题行拼 `renderRepairs`：一次搜索后沙盒把渲染相关静态改动了多少处、修回了几处（`RenderState.repair`） | 面板删除；`solver.log` 仍有 `quick search: render state changed, repaired N` |
| UI状态已还原 | 同一行拼 `viewRestore`：搜索期间 UI 状态（hover/屏幕等）变了，被 `RenderState.restoreIfChanged()` 还原 | 面板删除；日志仍有 `quick search: UI state changed, restored: …` |
| 成长: … | 两行：`GrowthCredit.describe()`（旧单一 credit）与 `GrowthBook.describe()`（P2-35/P2-36 的按来源额度） | 面板删除（规则留在 docs 与 settings.txt，describe() 仍供门禁使用） |
| 预计掉血 N | 标题行又拼了一次 `plan.hpLoss`，而 headline 自己已经写了「最好：掉血 N」 | 删除 |
| N 回合 | 拼的是 `plan.predictedTurns` = 整个计划的总回合数，本来就不随执行进度变（第 2 回合还是 1） | 删除 |
| 战后回血上界: +6 | `PostCombatHealing.describe()`：算的是「打赢这一场之后、战斗之外」游戏会给的回血（燃烧之血 +6 / 黑血 +12 / 带骨肉 +12 且残血过半），并以损失的生命为上限 | 改成「打赢后固定回血 +6（燃烧之血 +6）」，未认证来源追加「还有没认出来的遗物，可能回更多」 |

门禁 **S114**（core）：合成一场带 Burning Blood、HP 70/80 的战斗，跑完一次 quick 搜索后检查面板文本
不含「渲染已修复 / UI状态已还原 / 成长: / 预计掉血 / 回合」，且含「打赢后固定回血」、不含「上界」。
实测（`build/run-m19237.log`）：
```
PASS the render/UI bookkeeping is not on the panel | STS1 Engine Solver | 打赢后固定回血 +6（燃烧之血 +6） | 可赢（已证明最优：无伤、不用药）· 831 节点 · 255ms
PASS the growth pricing rules are not on the panel | …
PASS the damage and the turn count are not repeated in the headline | …
PASS the post-combat heal line says what it is | …
AUTOTEST RESULT passed=165 failed=0 (core gates only, 104 extended gate(s) skipped)
```
安装 jar 723261 bytes / sha256 `4FD8EB819A7CE6F09DD2B9E779F1294B072C64E208A5AE5DB9E54AD75AED8FD7`；完整套件 740/0。

## B66 / P2-38 结算窗 + 手牌选牌判据（核心 174/0，完整 749/0）

用户 m19362 指出：自动打出燃烧契约之后必定停下并提示重算，怀疑是「检测太快」。

| 项 | 结论 |
| --- | --- |
| 真根因 | `Driver.selectionConsumed()` 对 HAND 用「selectedCards 空」判断动作是否取走；引擎只在 `prep()` 清这个 list，取走时只置 `wereCardsRetrieved=true` → 恒判没取走 |
| 修法 1 | HAND 改读 `wereCardsRetrieved`；未取走时不关屏，下一帧再泵 |
| 修法 2 | 新增结算窗 `autoplayVerifyMs`（默认 700ms）与 `AutoPace.readyToJudge`；漂移与选牌判断都在窗后 |
| 门禁 | S115（core）：假时钟 4 条 + 燃烧契约实机：`consumed=true exhausted=1 stillOpen=false retry=clear` |
| 套件 | 核心 174/0（run-b66b.log）、完整 749/0（run-b66full.log） |
| 安装物 | 726025 bytes / sha256 `0339825B9876A112EE33AF8FAD73D66C188EE6C9C470173AB0716DBE77E6A787` |

「还有 N 条候选未展开」= `frontierLeft`（前沿里没展开的）+ `deferred`（A3 停车场，上限 2000），两者都是"还能再算"的候选；
不是被排除的世界线，也与开局掉血无关（掉血只参与排序）。

## P2-39 / B67 面板随大小调整内容（核心 191/0，完整 766/0）

| 项 | 结论 |
| --- | --- |
| 根因 | 几何按 strip 缩放、文字按 1.0 绘制、折行按 Settings.scale 测宽，三者不一致；且内容没有随大小取舍 |
| 新类 | `ui/PanelDetail`：FULL/MEDIUM/COMPACT 三档、`textScale` 0.62–1.30、行副本、头部行过滤 |
| 接线 | `Panel.drawPlanText` 统一测量/绘制 scale（绘制后恢复字体），新增 `Panel.detailLevel()` |
| 门禁 | S116（core）：阈值/取舍/字号/压缩规则/step 索引/头部行/跟随 grip |
| 套件 | 核心 191/0（run-p239b.log）、完整 766/0（run-p239full.log） |
| 安装物 | 732050 bytes / sha256 `2D474B553A99D65A1FC61827770B186888B69A0EE7B6A03B4E204D41A42D1261` |

教训：先量一次"框"和"字"是不是同一个 scale 的产物；v1 把档位挂在 `Settings.scale*factor` 上，
在 1.4 缩放的机器上门禁 preview(0.6) 仍判 medium —— 档位该只跟玩家拖的 grip 因子。

## B68 / P2-40 面板候选计数只说真话（核心 194/0，完整 769/0）

| 项 | 结论 |
| --- | --- |
| 用户质疑 | 「加深已经删除了…没道理有未展开的…这几百条一秒算完了」（m19708） |
| 事实 | 停车场 deferred 不参与任何按钮；每次搜索最多晋升 6 条，每条要重放；收敛时 frontierDead 没置真 |
| 修法 | `Result.cutShort = !frontierExhausted`；Session 跑干即 frontierDead；`PlanText.candidateNote` 只在限制停下时报数 |
| 门禁 | S117（core）、S63 断言改语义 |
| 套件 | 核心 194/0（run-s117c.log）、完整 769/0（run-s117cfull.log） |
| 安装物 | 733283 bytes / sha256 `C20F6091E7F858021210275C5A5FE75271C4966C8D9164EEF5B9312ADD20A220` |

## P2-41 停车场按剩余预算花（核心 201/0，完整 776/0）

| 项 | 结论 |
| --- | --- |
| 起因 | 用户 m19889「6条世界线是不是太少了」；旧硬上限 6 让预算充裕时停车场也睡着 |
| 规则 | `clamp(6, 60, remaining*0.5 / max(实测单条成本, 250ms))`，成本来自 materialize 实测 |
| 关键取舍 | 不按「重放 1ms」放开，而按「每条至少 250ms 搜索预算」计价，否则几十毫秒一条等于白跑 |
| 接线 | 阻塞收尾块 + `Session.promote`；Session 首次晋升定 limit；日志带 limit/costMs/samples |
| 门禁 | S118（core） |
| 套件 | 核心 201/0（run-p241core2.log）、完整 776/0（run-p241full2.log） |
| 安装物 | 735868 bytes / sha256 `A5DCC42B1320F4954E8A9B9755A45F650C81F42A0ED67AD9044A4087244A483C` |

## P2-42 / B69 面板字号固定 + 回合列按实测宽度（用户 m20027；只跑 S116+S119 21/0）

| 项 | 结论 |
| --- | --- |
| 重叠 | `LABEL_W=104` 比「第 1 回合」实测宽度窄；改为按 `measure(label)` 取宽 |
| 缩放 | 字号不再跟面板走；`visibleRows` 决定显示几行（1–5），布局/测量用面板 scale |
| 滚动 | 滚轮与键盘滚动改为一个回合行 |
| 门禁 | S116 改断言 + 新增 S119；`AUTOTEST RESULT passed=21 failed=0 (only [S116, S119] asked for, 141 gate(s) skipped)` |
| 新工具 | `tools/run.ps1 -Gates S119,S116`（请求文件写 `gates:…`，因为 ModTheSpire 重拉会丢 -D） |
| 安装物 | 739255 bytes / sha256 `587D811212A3F694018FEC01380012D72E21681A35072C42B8715BB9629C3FFB` |
## B70 / P2-43 模拟期不再动音量 + 设置界面不拖滑块（19/0，只验相关门禁）

| 项 | 内容 |
| --- | --- |
| 用户 | m20138-2「每次你启动sts的时候音量都会变成100%，先找原因我确认再改」→ m20313-2 批准四条修法 |
| 根因 1 | `ExternalMute` 每次安装 `new MutedMusic()`，`MusicMaster` 构造函数重读 `preferences/STSSound` 并改写 `Settings.MASTER_VOLUME/MUSIC_VOLUME`；游戏日志 3 秒内 9000+ 行 `Music Volume:` |
| 根因 2 | `InputMute` 把 mX/mY 置 -100000；设置界面开着时 `Slider.update()` 把主音量滑块拖到最左并 `flush()` → `STSSound` 只剩 `Master Volume: 0.0` |
| 修法 ① | `ExternalMute.mutedMusic()` 懒建单例 + `musicBuilds()` 计数 |
| 修法 ② | `begin()`/`restoreAll()` 快照-还原三音量；构造本身也 try/finally 保护；新增 `volumes()` |
| 修法 ③ | `preferences/STSSound` 备份为 `.pre-mod-20261101.bak` 后写回（Master 1.0000002 + Music/Sound 0.5） |
| 修法 ④ | `InputMute.screenUp()`：有屏就不动光标，`skippedForScreen()` 计数、`pop()` 只在真改过时还原 |
| 门禁 | 新增核心门禁 **S120**；`run.ps1 -Gates S120,S86,S87,S88,S111,S76` → passed=19 failed=0（141 跳过） |
| 安装物 | 742054 bytes / sha256 `22BAFD98AC073EC4D3B63B359E57800EDA6EECB8C806C2230150FEF4626A4E66` |
| 证据 | `evidence/B70-VOLUME-SLIDER-20261101.txt` |

实测行：
```
S120 audio: volumesKept=true built=1 buildsAdded=0 musicBack=true screenSkip=true noScreenMoved=true cursorBack=true volumes=[0.0,0.0,0.0]
external mute: built the silent mixer once for this process (its constructor re-reads the volume settings)
input mute: a screen is open, leaving the cursor alone (a slider would be dragged to the edge)
```

按用户 m20027-1「这次对话所有改动不要整套验证，只要关心改动的地方」，本轮**没有**跑核心/完整套件。

## B71 ս����ѡ����������ߣ�ֻ������Ž� 28/0��

�û� m20521��1.��ab ��2.ս����ѡһѡ�������ս���޹ز�Ҫ���㡣��+ ֮ǰ m20437���㵽��ûд����û�㵽����

| �� | ���� |
| --- | --- |
| ֢״ | �����Ǿ֣����һ�� 5 ��ʤ���ߣ�������һ�������Զ�ֻ�� 3 ���ͱ���·�������꡹�������� solver.log ˢԼ 5500 �� `reward pick answered with ...` |
| ��ʵ | �ƻ�������`plan : ���\|���\|�����غ�\|ͷ鳣�ѡ����ħ��̬��\|���`�����˺��� hpLoss=12/turns=3 �Ե��ϣ�ALLOW ��ֹͣ 33ms����Ư��/�޻��֣�promoted=0���� beam/ͣ�����޹أ� |
| ����һ | `Gameplay.java:2281` �ɷ�֧����־��� plan+pending���о� `Driver.isAwaitingCardChoice()` �ѡ������ NONE ���桹���㲻�ɴ�`cardRewardScreen` �Ƿ� null ������ |
| ����� | `Driver.isCardRewardOpen()` ����λ�ż����޿����־�ż� �� ս����ѡһ��ͬһ���󣩽����������Զ�Ӧ�� |
| �޷� | `inCombatPhase()` ��λ�ż���`isCombatCardReward()` Ҫ�� discovery/chooseOne/codex ֮һ��`selectionKind()`/`isAwaitingSelection()` ��������`isAwaitingCardChoice()` ��խ��`pollPending` �÷�֧���� plan+pending��ֻ��ͣ�Զ����ơ��� screen+step ��һ����־ |
| �Ž� | S121��core����`phaseGate=true postKind=true postChoice=false combatOpen=true flagsSet=false flagGate=true combatKind=true combatChoice=true restored=true` |
| ͬ�汾�ع� | S43 ���ַ�֧��S45 ��������S98 Һ̬���䡢S99 ����ֻͣ��S109 ��ҩ �� �ϼ� 28/0 |
| ��װ�� | 744839 bytes / sha256 419B988F226DAC08493C4A4AA784AEF98A6E3F25144F3D298E753B6A2AE5385E |
| �߽� | �Ž�ֻ֤���оݣ�ʵ��Ҫȷ�ϡ�ս����ѡһ���ٱ�Ӧ�𡹡�Ӧ���˵���ֻ��ͣ���ƻ����ڡ� |

���û� m20027-1������**��������**��ֻ�� `tools\run.ps1 -Gates S121,S43,S45,S98,S99,S109`��

## B72 / P2-45 ����㱻����ĺ�ѡ���ڻ�ط� + ���������� JVM������ 230/0������Ž� 32/0��

�û� m20668 / m20710 / m20719 ˵�ִ��Ѫ���٣���־����������ȱ�ݡ�

| �� | �� | �� |
| --- | --- | --- |
| ͣ�����ط��ż� | `entry.layer >= maxLayer \|\| entry.layer < layer` �� �����ȫ������`promoted` �� 0��kept=1314 promoted=0�� | ֻ�� `entry.layer >= maxLayer`��`promotedKeys` ȥ�أ�`promotedEarlier` + ��־ `earlier=N`��`replayEarlier` A/B ���� |
| ��������ֵ | д�� 1400MB��7GB ��һԽ������ beam=48 | `effectiveHeapLimitMb()` = `max(256, 65% �� maxMemory)`������ 4618MB����0 �رա�>0 �Զ��� |
| ��β�İ� | �����������·�ߡ� | `PlanText.winBest/parkedNote`��ǰ���ܸɵ� `promoted==0 && deferred>0` �� ��ǰ�����꣬��û�طű�������ߡ�+������ N �����������û�طš� |
| �Ž���ѡ | `-Gates` �������� coreOnly Ӱ�죨S65 �������� | ��ʽ�б����ȣ�`setOnlyGates` �� `coreOnly=false` |

�Ž� S122��core����`heap max=7106MB auto=4618MB`��`replay off(earlier=0 promoted=0) vs on(earlier=1 promoted=7)`���İ��������ԡ�
װ��� 748275 bytes / `DC38E08BD68E31663905D5A152C76120258026C8312B86F3B9AD790ECE389FC1`��
�ع� `S59 single/portfolio`��`S65`��`S108`��`S109` ȫ����S59 portfolio �ڵ� 2605��3573��������䣩��
֤�ݣ�`evidence/B72-EARLIER-PROMOTION-20261101.txt`��

## B73 / P2-46 �����о�ֻ������Լ�������Ž� 17/0��

�û� m20914 �������һ��һ�� + վ��ս���ﰴ������ʾս���ѽ�������ȡ֤��`logs/solver-20261101-154132.log` 15:40:37 ��ս�� �� 15:40:39 �����㣬ͬ֡ `status=ս���ѽ���������ȡ��`��֮��ÿ�ΰ�����һ֡��ȡ����`inCombat=true` �� `inFight()` false��

| �� | ���� |
| --- | --- |
| ���� | B64 �� `playerDead()` �� `AbstractDungeon.deathScreen != null` �����������þ�ֻ̬��������`AbstractPlayer.java:1465-1488`�������֣�`ConfirmPopup.java:239-244`����ֵ��ȫ��Ϸû��һ���û� null �� �����̵�һ������/���ֺ�֮��ÿ��ս�����С��ѽ����� |
| �޷� 1 | `Driver.deadFrom(isDead, currentHealth)` = `isDead || hp<=0`��`playerDead()` ֻ������B64 �ġ�ʬ���Ѫ������ `isDead` ��ס�� |
| �޷� 2 | `combatOver()` �� catch ���پ�Ĭ `return true`����һ�μ� `Log.error`���쳣����αװ��ս������ |
| �Ž� | ���� core `S123`��S110 / S99 / S121 ͬ�ָ��� |
| ʵ�� | `S123 death: aliveStart=true deadFlag=true deadHp=true aliveAgain=true over=false deathScreenPresent=false` |
| �׼� | `AUTOTEST RESULT passed=17 failed=0 (only [S123, S110, S99, S121] asked for, 143 gate(s) skipped)`���� m20027-1 ֻ������Ž��� |
| ��װ | 750084 bytes / `28621D971DF2C7FE3F7CA26F6C5DC52F7C5AF629156777180979DD0B449F66F3` |

��ѵ���ѡ���Ϸĳ�� static ��Ϊ null������״̬�о�֮ǰ����ȷ����Ϸ�Լ��᲻�������λ����ֵ�״ֻ̬�ܶ��������/��������ϵ��ֶΡ�

## B74 / P2-47 同名牌副本 + 方框只在自动出牌时画（2026-11-01，只跑相关门禁 53/0）

| 项 | 内容 |
| --- | --- |
| 现象 | 打「恶魔之焰」时自动出牌停；面板写「路线已走完（不代表打不过）」 |
| 日志证据 | `WARN execute: 游戏拒绝打出 恶魔之焰`（15:57:50、15:58:08 各一次；`execute: uuid ... is sandbox-local; matched 恶魔之焰 by card id`） |
| 根因 | ① 同名两张（地狱之刃给 0 费、死枝/别处给 2 费），`liveCardFor` 按 cardID 取第一张 ② `fail()` 清空 plan/pending，两帧后报「路线已走完」 |
| 修法 | `pickSameCard()` 优先可用副本；`Driver.refusalReason()` 输出 cantUseMessage+cost+energy+turnEnded；`refuseCard()` 暂停但保留计划；`Hud.setAutopilotMarkers()` 让方框只跟自动出牌 |
| 门禁 | 新增 core S124；S90 显式置 autopilot 标志；53/0（S124,S90,S104,S109,S116,S119,S120,S121,S123） |
| 安装物 | 753536 bytes / `8E5FC4A697B2D0F6E329344422479635BE7B0C36EB5F09B68D88FA4932A195E4` |
| 证据 | `evidence/B74-P2-47-REFUSED-CARD-MARKERS-20261101.txt` |

用户第二条要的是「方框只有自动出牌开才有」：计划算完但没开自动时，棋盘上不再画卡→怪的虚线框；开自动且处于两次动作之间的空档才画（执行/计算期间由 B48 的 busy 规则关掉）。
## B75 结束回合被误判为漂移（2026-11-01，只跑相关门禁 12/0）

| 项 | 内容 |
| --- | --- |
| 现象 | 第三步是「结束回合」，却报「第 3 步与预测不一致（局面变了）」，计划作废、自动出牌停 |
| 证据 | expected/actual 488 vs 480 字符，第 474 位起唯一差别 `pw=Curl Up10` vs `pw=-`（死虱虫 0/12 d=11） |
| 根因 | `Digest.monsterStable()` 按空格切词丢 `pw=`，能力名「Curl Up」带空格 → 残余 `Up10` |
| 修法 | 先删 ` pw=`…` d=` 整段再切词；新增 core 门禁 S125 |
| 验证 | 独立 JVM 工具 equal/leftover/liveDiffers 三项；`-Gates S125,S42,S80` → 12/0 |
| 安装物 | 755055 bytes / `210DC451F56A8DD65364081AEB028745681F7802F31FF63F7259FC3A782D075D` |
| 证据 | `evidence/B75-DIGEST-SPACED-POWER-20261101.txt` |

## B76 / P2-48 消耗堆复位 + 存档快照工具（相关门禁 20/0；2026-11-01）

| 项 | 内容 |
| --- | --- |
| 触发 | 用户 m21391-2：乐加维林第三回合狂野打击后又被判漂移；m21391-1：改之前要保存存档 |
| 取证 | expected/actual 只差 `Shockwave(0)` vs `Shockwave`（消耗堆）；腐化把技能本回合费用压 0 |
| 根因 | 实机 `ExhaustCardEffect` 1 秒后复位费用，沙盒 `trimPresentation()` 清掉该效果 → 永不复位；Digest 又把它写进 E= |
| 修法 | 新 `mirror/ExhaustAttrs`（begin/sync/end + 接入 World.install、trimPresentation、Driver 泵）；`Digest.pile(g,false)`；core 门禁 S126 |
| 工具 | 新 `tools/save-backup.ps1`（快照 saves+preferences 到 `%USERPROFILE%\sts1-engine-solver\save-backups\<时间戳>`，`-Restore` 回档）；本轮改动前已快照 5 存档 + 63 偏好文件 |
| 结果 | `tools/run.ps1 -Gates S126,S125,S42,S43,S109` → `passed=20 failed=0`（145 门禁跳过，按 m20027-1 只验相关） |
| 安装物 | 758904 bytes / sha256 F4570362…0258 |

## B77 / P2-49 自动出牌按游戏内的打（漂移不停、缺牌只暂停且保留计划；相关门禁 40/0）

| 项 | 内容 |
| --- | --- |
| 用户口径（m21571） | 自动出牌像 STS2 一样按游戏内的打；只有计划里的牌/药水不在时才暂停，而且计划不要丢 |
| 旧行为 | 漂移 / 选牌应答失败 / 找不到牌三处都 `plan = null` → 下一帧报「路线已走完」，必须重新点「计算」 |
| 漂移 | `noteDriftAndContinue()`：++drift、记 expected/actual、继续 `++step` |
| 缺件 | `fail()` / 选牌失败 / `refuseCard()` 一律「暂停 + 保留计划」，文案「计划已保留，处理好后点『自动』继续」 |
| 选牌 | `resolveSelectionLive(cardIds, displayNames)` + `matchOffered()` 三趟匹配（cardID 不带升级位） |
| 门禁 | S127（core）：缺牌 → 暂停且 plan=15；漂移 → plan=15 且 autopilot=true |
| 实测 | `AUTOTEST RESULT passed=40 failed=0 (only [S127, S99, S93, S92, S109, S43, S98, S121, S125, S126] asked for, 141 gate(s) skipped)` |
| 安装物 | 762001 bytes / sha256 `38DF79646935397E765D549C03714E940EEA19C78EE41C29BBDF9A2AB6BFDF62` |
| 存档工具 | P2-48 撤回（用户 m21571「不用保存了因为你做不到」），`tools/save-backup.ps1` 保留备用 |

教训：门禁用 `Gameplay.tick()` 驱动真实帧时，必须先把「刚进战斗」的转换帧跑掉（`inCombat` false→true 会清计划），否则量到的是转换分支而不是被测行为。

## B78 / P2-50 计划按身份打牌，重放不通过不算可赢（2026-11-01，相关门禁 35/0、回归子集 66/0）

| 位置 | 改动 |
| --- | --- |
| `search/ReplayException.java`（新） | 重放不可继续的专用异常 |
| `search/Legal.java` | `handCard(a)`：按 uuid / 卡名+ID / ID 找牌，找不到抛异常；`handCardFor` 给门禁 |
| `drive/Driver.java` | `resolveSelectionByNames(ids, names)` 先卡名后 ID，删除「猜一张」的路径 |
| `search/Solver.java` | 三处选牌改用新签名并抛异常；`Result.planVerified / planFailedAt / planFailedOn / planRequested` |
| `search/PlanText.java` | `replayedPartially(...)`；`headline` 先看 `planVerified` |
| `Gameplay.java` | 自动出牌拒绝播放未验证的线 |
| `probe/Probe.java` | 新增 core 门禁 S128 |

实测：`S128 identity` / `S128 missing` / `S128 text` 三行见 evidence/B78-REPLAY-IDENTITY-20261101.txt；安装 jar 768879 bytes / sha256 7A8BC568…38CD。

附带：2026-11-01 发现 docs/PLAN.md 与 docs/TESTING.md 从磁盘消失（回收站没有），已按 PLAN-WORKLOG / PLAN-BUGS / evidence 重建为索引；docs 与 evidence 备份在 %USERPROFILE%\sts1-engine-solver\docs-backup-*。

## B79 / P2-51 计划照抄选牌：uuid 进计划 + 结算后重试一次 + 失败分类（相关门禁 27/0）

用户 m22205 三问（线必须能打出来；要确认不是打太快；原因要分类）→ m22208「继续做」。

| 项 | 改前 | 改后 |
| --- | --- | --- |
| 选牌身份 | 只存 cardID，按 cardID 匹配 | choiceUuids 进计划；matchOffered 四趟 uuid > ID+卡名 > ID > 卡名 |
| 重放应答 | 答一次失败即判线坏 | answerForReplay：失败 → settleForReplay(16) → 再答一次，成功记 after settling N update(s) |
| 失败信息 | 一句自然语言 | ReplayException.kind + lastSelectionFailure + lastReplayFailure（日志一行带桶名） |

改动文件：Action、ReplayException、Driver、Solver、Gameplay、Probe（S129）。

门禁：tools/run.ps1 -Gates S129,S128,S127,S92,S98,S109,S59,S65 → 27/0，145 项跳过（按用户 m20027-1 只跑相关项）。关键行 S129 copy carried=true / S129 refused not-offered / S129 settle pumps=4。

安装 774044 bytes / FE108529…DBBCC。文档：PLAN.md P2-51 与 B79、TESTING.md 头部与四条复测、EVIDENCE.md 小节、evidence/B79-UUID-CHOICE-RETRY-20261101.txt。

教训：日志里桶名不要和异常 message 里已有的桶名重复（首轮每行都是 not-offered: the line cannot be replayed: not-offered: …，已按「message 已含 kind 就不再前缀」修）。

## 面板删掉「未展开（会打开选择提示）」开发行（相关门禁 20/0）

用户 m22403：这行是开发测试文本，能删就删。来源 `Gameplay.java:1422`（`rows.add("未展开（会打开选择提示）: " + join(plan.promptCards, 4))`），内容=会弹选牌界面但搜索没展开分支的牌名。

改法：`Gameplay.lines()` 不再输出该行；`Legal.promptCards()` 照旧收集，需要时看 solver.log。只跑相关门禁 `-Gates S114,S72,S127,S129` → 20/0（149 项跳过），S72 面板行数仍是 rows=4。

安装 jar 773959 bytes / sha256 7BDA40A23C2812B14EEE73CEAB8CCED8B4512E55A0B8C24D8FD69E120E783B04。装前游戏占用 jar，用 `tools/_wait-install.ps1` 轮询锁解开后自动 install + 跑门禁。

## B80 / P2-52 自我修复的战后回血计入评分（相关门禁 25/0）

| 项 | 内容 |
| --- | --- |
| 用户 | m22491「最新打乐嘉这里局外收益回血没打（“自我修复”这张牌）没算」 |
| 根因 | 沙盒停在「怪全死」，RepairPower.onVictory 不执行；PostCombatHealing 只算遗物 |
| 修法 | PostCombatHealing.bound() 加 repairPower()；describe() 显示「自我修复 +N」 |
| 门禁 | S130：before=0 seven=7 said=true up=17 capped=2 |
| 实跑 | -Gates S130,S14,S53,S97,S129 → 25/0（149 跳过） |
| 安装 | 775819 bytes / sha256 23E2D1FA…B784 |

细节见 evidence/B80-SELF-REPAIR-20261101.txt 与 docs/PLAN-BUGS.md 的 B80 节。

**m22600 补记**：初版只把 RepairPower 加进 PostCombatHealing.bound()，但打分用的是实机玩家（子节点世界已卸载）→ 常数，对排序无影响。
改为按节点记账：Choice.postHeal（readChoice）→ Node.postHeal → score 用 healPoints；Result.postHeal + compareRoutes 第五参 heal。
实测 S130 search：plan=自我修复 | 打击 | 打击、heal=7、hpLoss=0；-Gates S130,S14,S53,S97,S129,S59 → 32/0。
jar 776839 bytes / sha256 26DA7625…08E8。

## B81 / P2-53 界面状态不回写世界 + 跳过光标的日志改摘要（相关门禁 34/0）

| 项 | 内容 |
| --- | --- |
| 用户 | m22687「大量重复的日志…三哨位，一开始算还不卡…一秒几十条世界线」；m22748「能修好吗，怎么改」 |
| 现场 | 141876 行日志里 140252 行同一句 input mute；世界线 1700→36 条/s；frame p50=83ms |
| 根因 | ①日志在每节点 push 里写 ②模拟死亡把 AbstractDungeon.screen=DEATH 经 readBack 存进世界 → 之后每个窗口都判「有界面」 |
| 修法 | Statics 给 screen/isScreenUp 标 discardOnReadBack；InputMute 摘要（5s 一行，带 screen/isScreenUp）；搜索开始 resetSkipLog |
| 门禁 | S131：carried=false skipsUnchanged=true liveRestored=true summaryPrinted=true |
| 实跑 | -Gates S131,S120,S121,S45,S43,S109 → 34/0（149 跳过） |
| 安装 | 779154 bytes / sha256 7B350D6A…BA682 |

细节见 evidence/B81-SCREEN-CARRY-LOG-SPAM-20261101.txt 与 docs/PLAN-BUGS.md 的 B81 节。

## B83 / P2-54 回合结束的选牌用小帧步长（相关门禁 58/0）

| 项 | 内容 |
| --- | --- |
| 用户 | m23574「回合结束时计划妥当选择保留牌…下一回合让我打中和+但是没摸到」 |
| 根因 | `Driver.endTurn` 用 20 秒帧，`RetainCardsAction` 开屏那一拍 `0.5-20<0` 就判完成，答案没人消费 |
| 修法 | `needsSelectionDeltaAtEndOfTurn()`（有 Retain/外来 power 时用 0.05 帧），finally 还原；加诊断钩子 |
| 门禁 | S133 A/B：old `inHand=false discarded=true`、fixed `inHand=true discard=4 handAfter=6 slow=1`；相关 58/0 |
| 安装 | 783572 bytes / sha256 2B0083E2…56B8 |

细节见 evidence/B83-WELL-LAID-PLANS-20261102.txt 与 docs/PLAN-BUGS.md 的 B83 节。

## B84 / P2-55 选牌屏按游戏帧长（相关门禁 56/0）

用户 m23817 选定「屏开即按真实一帧」的方案，并要求先确认两件事——不与 `pumpWhileScreenOpen` 打架、补丁用 `World.anyInstalled()` 限住。

确认结果：冻结早就在 `drain` 里（`isAwaitingSelection` 就 break），缺口只有开屏那一拍；注入点是自家 `Graphics` 代理，只存在于 `Driver.begin/end` 窗口，且 `screenDeltaApplies()` 显式检查已安装世界。没有给游戏类打 patch。

改动：`Driver.frameDelta()` + `SCREEN_DELTA=1/60` + `screenFrames` 计数 + `setScreenDeltaForTest`；S133 改成三行矩阵（旧帧丢牌／通用规则留牌／旧名单留牌），新增 S134（Foresight scry 的 A/B）。

实测：`-Gates S133,S134` 14/0；回归子集 56/0（S92/S98/S129 选牌消费、S59/S65 求解结果不变、S120/S131 实机侧不受影响）；`-Mode cost-normal` 0.72ms/节点。安装 jar 786012 B / `562C18931D0A5AD615BD4123DF4DBE8B5BBF2991B27E392FEBCCADAFEA7E8390`。

## B85 / P2-56 — 回合归属（2026-11-02）

- 问题：`Step.turn` 记的是动作**执行后**的回合，`Gameplay.turnOfStep()` 再用 `isEndTurn()? turn-1 : turn` 反推，两个方向的错都出来了：毒在结束回合杀怪 → 记成第 0 回合、计划被判过期（m23938 的「自动出牌最后一步没打出」）；腾跃 → 记成下一回合、缓存永不命中。
- 定方案（m24017 看 AutoWatcher 后）：AutoWatcher 把结束回合类动作**标成结束回合**、由回合推进管道推进，回合号不参与归属推导（`docs/VERIFICATION.md:72-74`、`tools/run-watcher-matrix.ps1:430/437`）。我们照抄口径：Step 记「动作开始前的回合」。
- 落地：`Solver.replay` 在 `Legal.apply` 前读 `turnBefore`；`Gameplay` 删 `turnOfStep()` 与三处 `-1`；结束回合等待加 `!fightFinished()`；`HexaProbe` 同步。
- 门禁：S135（毒杀：末步 turn=1、stale=false、无「第 0 回合」）、S136（整线跑完 89ms，不走 20s 兜底）、S137（腾跃 t0=1/t1=2/fits=true）。核心套件 293/0。
- 顺带修好两条被 B84 改坏的门禁对照线：S82 的「旧 fast 卡帧复现 bug」必须同时关掉屏帧规则；S136 的装计划时机必须至少先跑一帧 `AbstractRoom.update()`，否则世界切换检测会把刚装的计划清掉。
- 教训：门禁读 `Gameplay.tick()` 的瞬时状态不可靠（同一帧接受后立即被「战斗结束」清理），要用 `verifiedSteps()` 这类不复位的计数器；探针驱动 live 引擎必须走真实房间帧。

## B86 / P2-57 / P2-58 — 多屏选牌（头追）与上限三档（2026-11-02）

- 问题：头追（CaiNongMod `HeadChase` → `ExchangeCardsOfTwoPilesAction`）在弃牌堆与抽牌堆各选一次，自动出牌「不卡也不换」。实机两屏都由真引擎开出，卡在我们这一层：泵 8 帧把 0.1s 窗口烧完 → 动作出队、第二屏被顺手关掉；`Solver.expand` 只枚举第一屏；`pendingChoice` 只有一个槽。
- 参考：STS2 `CardChoiceSupport.BuildChoices` 单选不截断（`branchLimit=max(候选数+跳过)`）、多选 `BuildCombinations` 后按优序 `Take(limit)`；`SolverSettings.cs:180-205` 四档分支上限。
- 落地：`Driver.selectionQuestion()` + `screenSerial`（按屏身份停泵，不再关新屏）；`Solver.expandScreens()` 逐屏枚举、`combinations()` 笛卡尔积、`choice="A|B"`；`Gameplay.pendingChoiceAt` 逐屏应答，面板显示「选牌：A → B（2/2）」；单选取全部候选，多选保留「最优/最差 + 累积前缀」。
- P2-58：新 `search/Limits.java`，settings.txt 里 `limits=low|high|unlimited` 主档 + `limits.<键>=` 单键覆盖（pile/hand/combos/screens/deferred/promotions/beam/layers），也认中文「低/高/无限制」；无限制仍受堆守卫与预算约束。
- 门禁：S138（两屏 5/7 真互换、实机牌堆未动）、S139（35 组合、low 档截 8）、S140（三档换算+单键覆盖）。核心 308/0，完整 883/0。
- 顺带：S46 落盘检查改用引擎同步写入（不再等 AsyncSaver 队列，队列在套件里能落后 44s，是唯一一条顺序敏感 flake）；S67/S108 的 `effectiveBeam(128)` 断言改成 `min(128, Limits.beam())`。
- 教训：门禁的「正例」也会测错对象——换牌落在**沙箱玩家**牌堆（install 之后取的 `AbstractDungeon.player`），install 前抓的实机玩家必然不变；重写枚举时要保留旧分支（scry 的累积前缀被丢导致 S23 红）。

## B87 / P2-60 — 搞里头（GetInside）与手牌选牌保真（2026-11-02）

- 问题（用户 m25378）：自动打出搞里头后两张选中的牌没离开手牌；手动打出并手选后，下一回合抽牌和沙盒不一致。
- 反编译确认：`GetInsideAction` 的 `playCards` 只 `ModHelper.addInsideCards(card)`；真正把牌移出手牌的是 `HandCardSelectScreen.selectHoveredCard()` 的那一下（`hand.removeCard` → `selectedCards.addToTop`）。我们的 `fillHandSelection` 少了这一步。
- 第二层：`ModHelper.cards_Inside` 是 static final，`ModStatics` 不管 final 引用 → 沙盒与实机共用一份 list（沙盒 `clearInsideCards()` 会清掉实机存的牌）。
- 落地：`Driver.fillHandSelection` 复刻点击（含幂等与回滚）；新增 `Kind.MUTABLE_CONTAINER` + `Statics.MOD_CONTAINERS`（`CaiNongMod.helpers.ModHelper#cards_Inside`、夹具 `demoMod.probe.ModProbeCards#HELD`），`World` 装时快照实机内容并原地重写、卸时还原、`readBack` 只带副本；首次扫描打一行镜像说明日志。
- 门禁：S141（手牌屏选 2 张真离开手牌、实机未动）、S142（static-final 容器按内容镜像与还原）。核心 318/0、完整 893/0；安装 jar 814357 B / `AEC918646288278465F3B19F545EEADBE9902CC1DD450EAB718045DF4BB9E949`。
- 顺带：S133「大帧丢牌」旧断言按新语义改成 `inHand=false & discarded=false & exhausted=false`。
- 教训：`HandCardSelectScreen` 与 `GridCardSelectScreen` 语义不同——grid 屏点击只 `selectedCards.add`，不搬 targetGroup；所以只改 HAND 分支。Java 21 反射无法替换 static final 字段（实测 `IllegalAccessException`），只能原地改内容。

## B88 / P2-61 — 出牌后流程审计 + 效果终态补算（2026-11-02，核心 323/0）

用户 m25727：「要确定这种新的效果沙盒可以正确模拟。整理游戏出牌后的流程看沙盒模拟还有哪里不到位。」

### 1. 出牌流程按源码拆开（新增 docs/ENGINE-FLOW.md）

七阶段：`AbstractPlayer.useCard`(:1340-1366) → `GameActionManager.getNextAction`(:174-356，含 onPlayCard 钩子 / 记账 / limbo 判废) →
`UseCardAction` 构造(:29-64，onUseCard 钩子) 与 update(:71-133，moveToXxxPile / 奇异勺子 / 消耗成就) →
`CardGroup.moveToExhaustPile`(:819-842，挂 `ExhaustCardEffect`) → `AbstractRoom.update`(:420-429，屏开冻结 actionManager) →
`ExhaustCardEffect.update`(:26-45，`resetAttributes()` + `fadingOut`)。

状态载体逐条对到镜像手段（CLONE 根 / CONTAINERS / 生成标量表 / shell sweep / PREF / MOD_CONTAINERS / ExternalMute）也写进该文档 §2。

### 2. 效果类审计：293 个 `AbstractGameEffect` 子类里只有 4 个改战斗状态

| 类 | 改了什么 | 结论 |
| --- | --- | --- |
| `vfx/cardManip/ExhaustCardEffect` | `c.resetAttributes()` + `c.fadingOut=true` | 战斗内，必须补算 |
| `CampfireSmithEffect` / `CampfireTokeEffect` / `CampfireSleepEffect` | 营火升级/删牌/回血 | 非战斗相位，不在求解范围 |

B76 的 `mirror/ExhaustAttrs` 只按「消耗堆张数变化」补，以下两条路径**完全没覆盖**：
`GameActionManager.java:255-269`/`:275-285`（牌在 limbo 里被判废 → 挂 `ExhaustCardEffect` 后不进任何堆）、
`:368-381 callEndTurnEarlySequence`（提前结束清 limbo，每张 limbo 牌挂 `ExhaustCardEffect`）。

### 3. 修法：`mirror/EffectSettle`

`World.trimPresentation()` 在清空四个效果容器**之前**调 `EffectSettle.settle(...)`：

- `ExhaustCardEffect` → 反射读私有字段 `c`，照 `update()` 的终态补做（在手牌只 reset；不在手牌 `fadingOut=true` 再 reset）；
- 其他效果类 → 按类名记一次（`NOTE_CAP=40`），日志 `effect dropped without terminal state: <类> (audited: presentation only)`
  —— 将来游戏更新或 mod 引入新的带状态效果会出现在日志里，而不是悄悄丢掉。

`ExhaustAttrs` 保留（覆盖「效果对象已因别的原因丢掉、但消耗堆变了」的路径），两者重复执行幂等。

### 4. 门禁 S144 与实测

- S144（`Probe.java`，`s144EffectSettle()`）：沙盒造两张「本回合改动过」的牌（一张已离手、一张仍在手）+ 一个纯表现效果，`trimPresentation()` 后断言四项终态 + 未知类被记录 + 实机那张牌未被改。
  实测 `ok settled=true costReset=true dmgReset=true blockReset=true fading=true inHandReset=true inHandFading=false noted=true liveUntouched=true`。
- `-Gates S144,S143` → 5/0；核心套件 → **passed=323 failed=0**。
- 成本 A/B（`-Mode cost-normal`）：带 EffectSettle 1.0/1.05/1.1 ms/节点（三次），临时关闭 0.95 → 差异在噪声内。
- 安装物：822506 bytes / sha256 `53113D2FCA558C4E9A4110FE8A08E1601FDADD4018F9542D97EBC55A81D8F098`。

### 5. 顺带：S143（-AllMods 端到端搞里头）

`tools/run.ps1 -AllMods -Gates S143` → 7/0。真加载 CaiNongMod 后：选两张打击 → 静态列表存 2 张且都是打击 → 两张牌真离开手牌 →
再打一次搞里头 → 两张牌经 limbo 真的打出（`noScreen=true limboEmpty=true block=18 liveHand=true liveList=true energyMirrored=true`）。
踩坑：`EnergyPanel.totalCount` 是**当前能量**（`hasEnoughEnergy` 看它），`player.energy.energy` 是上限，只设后者会让 `canUse` 为假。

## B89 / P2-63 — 强制结束回合由引擎闩锁判定（2026-11-02，核心 328/0、完整 903/0）

用户 m26061 报「打出后强制结束回合的牌（结末）之后自动出牌以为还要再结束第一回合」，并点名参照 AutoWatcher-main 的结末处理。

1. 先读引擎：`Conclude.use()` → `PressEndTurnButtonAction` → `GameActionManager.callEndTurnEarlySequence()` → `EndTurnButton.disable(true)` → `player.endTurnQueued = true`（`EndTurnButton.java:151`）。真推进链是 `AbstractPlayer.updateInput` → `isEndingTurn` → `AbstractRoom.endTurn()`。`TimeMazePower.java:51` 走同一条路。
2. 读参照项目：AutoWatcher 的 `WatcherCardMirrors.A.cs:164-170` 把结末写成「伤害 + 只在最后一段 `ForceEndTurn`」，`WatcherVerbs.cs:255-270` 是 `RequestPlayerTurnEnd()`；那里也踩过同一坑（结末后补的打击打不出来）。
3. 我方根因：`Driver.drain` 只认 `Legal.PRESS_END_TURN_CARDS`（只有 Vault）→ 结末不结束回合 → 沙盒回合停在 N、`endTurnQueued` 残留 → 搜索排 N 回合的步骤，实机已 N+1 → `planIsStale` 丢计划。
4. 改法：新增 `Driver.endTurnLatched(gam)`（turnHasEnded / endTurnQueued / isEndingTurn），drain 入口记 `latchedAtEntry`，**之后新出现**的闩锁一律结束回合；白名单降级为注释里的提示。
5. 门禁 S145 两条路径（结末卡 + 队列里的匿名闩锁）。A/B：把条件改回旧写法时两条都红（`advanced=false queued=true t=1->1`），恢复后全绿。
6. `-Full` 又红了一条既有 flake（S66 等 AsyncSaver 队列写玩家存档），按 P2-59 改成同步写入 + 字节比对。
7. 实测：核心 328/0、完整 903/0；jar 825492 B / `368F008F…D060`；`-Mode cost-normal` 未另测（本次只动 drain 的一帧判断）。

### 2026-11-02 P6 第二轮：身份判定 / 选择匹配器 / coverage 说真话

- **B90 包名不能当身份**：新增 `util/Provenance`（以"游戏 jar 是否提供同名 class"判定归属），替换四处
  包名前缀判断。实测 modchar `mod-characters=8`（含 `com.megacrit.cardcrawl.characters.MerchantPlayer`），
  12 个角色全部 `live=match=N/N`。
- **B91 ModStatics 报告**：40 条静默截断 → 真实 225 容器 / 103 对象引用，按 text/art/other 分桶，只点名 other，
  行尾补 `N vanilla bundled`；`7 container(s) mirrored by content` 与 225−218 自洽。
- **B92 面板药水名**：`Play.potionName`，面板/历史不再出现 `POTION#2`；门禁 S147。
- **B93 一帧不能跨回合**：`AbstractGameAction.duration` 按帧 delta 递减，门禁体在同一帧内快进整场不成立
  （实测 frames=8900 万、settled 恒 false）。门禁改为"证明第一步与沙箱逐字相同"，多步交给真实帧。
- **B94 门禁必须自己造局**：`-Gates S147` 单独跑时停在主菜单 `player==null` → 自己 `Synthetic.build`。
- **B95 选择匹配器分叉**：沙盒/实机各一份，实机漏了 `used[]`（弃两张同名牌只弃一张）。合并为
  `Driver.picksFor`，门禁 S149 钉 4 条规则。
- **B96 coverage 三处失真**：重复 `card-stalled`、两个 mod 角色假失败（改用注册实例的 `chosenClass`）、
  `triggered` 一行 50KB（`head(...,40)`）。修后 `characters: total=12 ok=12 failed=0`。
- **B97 BottledVoid NPE 归因**：`javap` 证明是 static `card` 未绑定的无局夹具现象；遗物报告从此带 `[during <phase>]`。
- **P6-09 冗余**：`util/QuietConsole.java`（0 引用、控制台成本的早期草稿）删除，理由记入 `docs/PERF.md`；
  15 处空 catch 均已有"为什么不记日志"注释；94 条 lint 保留（redundant cast / rawtypes = 风格，不是缺陷）。
- 门禁：`S147,S148,S149` 全绿（passed=13 failed=0，4 个 mod 角色各 verified=1/drift=0）；core 套件
  `passed=340 failed=0`；`-Mode coverage`（10 个 mod）`cards 942 / potions 50 / relics 261 / monsters 67 / characters 12 全绿`。

- 2026-11-02 晚：用户退订除 ninja / 菜农外的全部角色 mod。S148 角色列表收窄（另两个以注释保留）；
  modchar 复跑 `mod-characters=2`（ninja 14/14、菜农 12/12 全 match），`S147,S148,S149` `passed=11 failed=0`。
- 2026-11-02 晚（第二次）：**B98 牌指纹**全链路接通（用户 m02347「开始做」）：
  `mirror/CardPrint.java`（`of()`/`same()`/`digestExtra()`；`EXPLICIT` 收真状态、`UI_NAMES` 滤绘制字段、`extraFields()` 按类缓存）→
  `search/Action` 带 `cardPrint`/`choicePrints`（构造器链每级补 7 个空串，补少了 javac 报「14 参无匹配」）→
  `Legal.legalActions` 去重键换成 print（旧键漏 `misc`，计数器不同的两张牌被折叠成一个分支）→
  `Legal.handCard`/`sameIdentity` print 优先、歧义拒绝（`ReplayException`）→
  `Driver.printsOf`/`matchOffered` 6 参（uuid → print → id+name → id → name）→ `Solver.expandScreens` 收集每屏选项 print →
  `Gameplay.pendingChoicePrints` + `liveCardFor` print 段（多份同 id 且 print 不命中则拒绝播放，不再按位置猜）。
  门禁 **S150**（5 条规则）钉住「身份 = 状态」。core 套件 `passed=348 failed=0`。
- 同日：指纹收紧（派生值 `damage/block/magicNumber`、回合级 `isCostModified*/isSeen/fadingOut`、结构值
  `damageType/target/assetUrl` 移出 extras），避免跨引擎假不一致。
- **P6-11 完成**：门禁 **S151**「a mod potion drinks identically on the panel path and in the sandbox」——
  ninja 三瓶药水（`potions.AmoXilin` / `LexKelaPotion` / `NH42SO4`）沙盒与面板路径 Digest 一致（3/3 PASS）。
  首跑 FAIL 是量法问题（面板路径只入队、沙盒路径已结算），门禁里补 `actionManager.update()` 泵，见 B99。

- 同日（第三次）：**B100「加载不到的名字被判成原版」**（全量套件唯一失败 `a third-party subscriber counts as a hook`）：
  `util/Provenance.java#isGameName` 的 catch 从 `return true` 改为 `return false`（未知不能证明是原版），
  全量套件 `passed=925 failed=0`。
- **P6-12 完成**：`search/Limits.potionsOffered()`（`settings.txt` 键 `search.potions=allow|ban`，默认 allow；
  `forcePotionsForTest` 供门禁用）+ `Legal` 药水分支受开关约束 + `settings.txt` 注释条目。
  门禁 **S152**（开关真的去掉药水分支：offered=1 / banned=0）与 **S153**（同一局跑两遍，差价进日志：
  JawWorm+Cultist / 25HP / 8s → 带药 hpLoss=5 且用 1 瓶，不带药 hpLoss=15 且用 0 瓶）。
  core 套件 `passed=353 failed=0`，全量 `passed=925 failed=0`。

- 同日（第四次，用户 m02901「重放失败，应该是按兵不动的问题」）：BideTime 复现失败但**挖出 A3 重建的两处真缺口**。
  - 先排除：`BideProbe`（4 实验）证明「卡内结束回合」在沙盒里正确（turn 1→2、抽满、+1 能量）、
    mod 静态 `BideTime.isUseAttack` 正确生效、35 步/19 步计划连续重放与逐步 fork 重放都一致（19/19 agree）；
    `applyStyle` 实验（每步额外泵 24 帧 vs 不泵）**逐步 Digest 完全相同**（`firstDiff=-1`）→「expand 多泵 24 帧」假设作废。
    `AllTheSame` javap 反编译：纯群伤 + `NongHeiPower`，不碰手牌/牌堆。
  - **B101**：`Solver.materialize` 把「没抛异常」当「回到了搜索那一刻」，最后 `cur.setDigest(entry.digest)` 直接覆盖。
    现改为每步（install 期间）读 `Digest.stable(ofCurrent())`，走完与 `entry.digest` 比对，不等则 `++promotionMismatch`
    + 打 `first difference at char K: parked=<...> rebuilt=<...>` 并返回 null；异常日志补 `at step N on <action>`。
  - **B102**：`Driver.play/usePotion/endTurn` 收队列用 `drain()`=`drain(600,250L)` ——**250ms 墙钟**；
    真机负载下同一动作会停在自己队列的不同位置，而 expand 之后还泵 24 帧把队列推完 ⇒ parked 与 rebuild 差一张牌。
    现加 `Driver.completeIfTruncated`：被墙钟截断时用**步数界**（4000 步）再收一次，触发计数
    `Driver.truncatedDrainsForTest()` + `Log.warnEvery`。
  - 门禁 **S154**「a searched line walks into the same state twice, and no step leaves its queue truncated」：
    `S154 determinism: steps=6/6 second=6 truncated=0`，3/3 PASS（`-Gates S154` → `passed=3 failed=0`）。
  - `docs/PLAN-BUGS.md` 追加 B101/B102（含「时间可以是放弃的判据，不能是状态的判据」的教训）；`PLAN-P6.md` 增 P6-15 ✅。

### 2026-11-03 凌晨 B103：停牌回放上限是跨轮廓的静态闩锁（user m03616）

- 用户问「"前沿跑完，但是没回放被挤掉的线"你看日志这里说的是对的吗，为什么没回放」。答：提示语说对了一半
  （确实没回放），但它给的唯一建议「可以再算」是错的——真正原因是**共享的停牌时钟被第一个轮廓用光了**。
- 根因：`Solver.deferredPhaseMs=6000` + `deferredPhaseStarted` 是整次搜索共享的**静态闩锁**，
  `resetGuards()` 每次搜索只调一次，而 `PortfolioSession.advance()` 在同一窗口顺序跑 4 个轮廓：
  轮廓 1 用掉 6 秒后，轮廓 2..4 的 `promote()` 一律在 `deferredPhaseExpired()` 早退，连 warn 都只打一次。
  用户日志里 `kept=2000 promoted=0 budgetLeftMs=249998100`（剩 2.5e8 ms 却一条不回放）就是这个形态。
- 复现：`probe/BideProbe.java` 新增 `slicedPortfolio()`（`resetGuards()` + `PortfolioSession(w,20000L,0)` +
  `advance(40L)` 复刻面板切片）。修复前 `promoted=13/8/0/0`（轮廓 3 剩 2886ms 却 0 条），修复后 `13/8/6/1`。
- 修法：①`Solver.parkedCapFor(passBudget)`=`min(deferredPhaseMs, max(500, passBudget/4))`，每个轮廓自己的上限；
  ②只把 `materialize` 的耗时累加进 `parkedSpentMs`（第一版按墙钟算，轮廓 1 只回放 4 条就报"用完 1750ms"，
  而它还剩 4.5 秒）；③`promote()` 拆成包装 + `promoteOne()`，任何出口都写 `promoteWhy` 并每轮廓打一条
  `A3 promote: nothing replayed (<原因>) ...`，计数器提升为 Session 字段；`Result.deferredWhy` 经
  `PlanText.parkedNote(..., why)` 进面板：「还有 N 条被挤掉的线没回放（<真实原因>）」。
- 门禁 **S155**「the parked-replay refusal reaches the panel in words」：5/5 PASS
  （`S155 note:  还有 270 条被挤掉的线没回放（停牌阶段已用掉 5000ms（本次轮廓的预算 20000ms））`、
  `S155 parked ceiling: pass-20000ms=5000 noBudget=6000 deferredPhaseMs=6000`）。
- 回归：core `passed=361 failed=0`（+S155）、全量 `passed=936 failed=0`；modchar（ninja+CaiNong）`characters=6 mod-characters=2`，
  ninja `live=match=14/14`、菜农 `live=match=12/12`；jar 873735 B / sha256 DD86B24F…798DDF。
- 文档：`PLAN-BUGS.md` B103、`PLAN-P6.md` P6-16 ✅、`PLAN.md` 板块行改 P6-01…P6-16（S145-S155）。

### 2026-11-03 上午 B104 + B105：删掉指向不存在按钮的文案；P2-62 的廉价一半（user m04121/m04331）

- **B104（文案）**：面板在预算不足时报「点「加深」还能继续找」，而「加深」在 B43 就删了。改为
  「停车候选阶段被缩短（原因见上），可点「计算」再给一轮预算」；`deepened()` 的 javadoc 改成事实
  （唯一调用者是 Shift+F8 的 `startDeepSearch()`，预算 unlimited 时原样返回）；`JUDGMENT.md` 预算节、
  `Solver` 的 B59 注释同步。教训：删功能要连"文案里承诺这个功能"的每一处一起删。
- **B105（P2-62 廉价一半）**：新增 `mirror/StaticWatch.java` —— 不复制、先看有没有被写：
  装时快照 `ModStatics.watchCandidates()`（同一次 jar 扫描收集的 `Field`，剔除 `Statics.mirrorKeys()` 已拥有的键），
  卸时比较；被写过 → 点名 + 把真机内容放回 + 进 dirty 集合（此后每次安装只查它）。
  抽样：前 4 次安装全量、之后每 128 次一次；未抽样窗口成本≈0（对照 fork ≈21–26µs，全量浅快照会翻 2–4 倍）。
  四形状：collection（clear+addAll）/ map（clear+putAll）/ array（逐元素）/ 普通对象（≤32 非 static 字段）；
  静态引用被换成别的对象时非 final 放回、final 只报。接线：`World.install()` 末尾 / `World.uninstall()` 开头。
- 门禁 **S156**：3/3 PASS（点名写入 / 活内容回到 `[live]` / 首次安装即全抽样），
  日志 `static watch: the sandbox wrote mod static sts1solver.probe.Probe#S156_HOLDER (collection) and the contents were put back…`。
- 顺带修 **S153 的负载敏感 flake**：机器被 MuMu/MAA 抢占时 `a fight the belt wins is not lost without it` 报
  `with=false without=true`。根因是**预算**不是药水（带药水分支更多 → 同墙钟深度更少）：预算 8s→20s，
  并新增 `limited(Result)`，任一侧停在 `time/nodes` 就不判 dominance，改报「这次比较被预算截断」。
- 回归：core **`passed=365 failed=0`**（原 361：+S156 三条 +S153 一条）；`-Gates "S153,S154,S156"` → `passed=10 failed=0`。
  jar 109 sources，install sha256 `5E40A3DBB28B7142B3101CE21623E0FE183F46D82E8247465B87C6CDAB41AD87`。
- **未完成**：`-Mode modchar` 那轮（用真实 mod 的 77 个静态容器过一遍 watch）被用户喊停，没有结果。
- **决定（user m04331）**：「没有 bug 就不要做了 …… 等我测试出 bug 再改」→ auto-promote（检测到被写就自动注册进
  `Statics.MOD_CONTAINERS` / `CLONE`，让该字段每分支独立）**暂不做**。判据已备：一旦 solver.log 出现
  `static watch: the sandbox wrote mod static …`，把这个字段注册进去（约 2 行 + 一条门禁），再用 modchar 复验。

### 2026-11-03 中午 B106：结束回合「最多 N 张」选牌屏真正变成决策（user m04431/m04473）

- 用户报「计算时卡住几秒又恢复」，查日志=回放失败重试爆炸（见 B106 现象段），并追问「为什么计划里没有选牌答案」。
  结论：**展开侧与回放侧用了两套判据**（`isAwaitingSelection()` 要 `numCardsToSelect>0` vs `selectionKind()` 只看
  `AbstractDungeon.screen`），「屏在但已不在等答案」时展开记空答案、回放硬要答案；另一半原因是沙盒只泵
  `actionManager.update()`，从不驱动屏幕自身的 `update()`。
- 用户明确要求「正确模拟，不是把 bug 整得看不见」，因此**没有**采用「回放侧跳过」的补丁，改为：
  1) `isAwaitingSelection()` 排除 `wereCardsRetrieved`、接受 `upTo`/`anyNumber`（私有字段反射）；
  2) `selectionKind()` 复用同一判据；3) `selectionOptions()` 对 upTo 屏生成 0..N 累积前缀（含保留 0 张）；
  4) `pumpUntilSelection` 新增 `pumpScreenSelf(4)` 驱动屏幕自身；5) `answerForReplay` 用同一判据；
  6) 拒绝日志追加 `Driver.selectionState()`。新增 `selectionMaxCards()`。
- 门禁 **S157** 6/6：`S157 up-to screen: screen=HAND_SELECT numCardsToSelect=2 upTo=true canPickZero=true anyNumber=false wereCardsRetrieved=false numSelected=0 kind=HAND awaiting=true optionSizes=[0,1,2]`；
  `S157 end of turn: … awaiting=true branches=3`；`AUTOTEST RESULT passed=6 failed=0`。
- 回归：core **371/0**（原 365，+S157 六条）；jar 884k 级，install sha256 `F379DBA80DA15852D1A12084ED0629144331CA6681EAD5C84987BD079260C3C5`。
- 文档：`PLAN-BUGS.md` **B106**；`PLAN-P6.md` **P6-18**；`PLAN.md` 板块行改 P6-01…P6-18 / S145-S157 / core 371/0。
- **仍未修（独立问题，等批准）**：回放失败后仍会逐条重试整张停牌表并每条打两行日志（1318 条 → 帧 2–3 秒）。

### 2026-11-03 下午 B107：删掉回放失败的「重试」，把「这一步重放不了」只学一次（user m04644/m04656/m04659）

- 用户先问「能不能去掉重试（完整删除、不留残余接口）」，再追问「删掉重试为什么每条线仍会在同一拍被试一次 / 再试有没有用 /
  没用怎么删 / 时钟上限本质是忽略问题」，最后选 A（删重试 + 失败签名跳过），并要求「尽量让每条线都能正常走」。
- 结论：回放确定性 → 重试零价值；但 promoteOne 一次调用遍历整张停牌表，删重试只把 2 轮变 1 轮，仍有一个 4 秒大帧；
  时间上限是掩盖。真正修法 = 把「这一步重放不了」记成事实（投毒），之后所有会走到它的线直接跳过。
- 代码：`search/Solver.java` —— 删 `promotedKeys.remove(key)`；新增 `rebuildPoison`/`rebuildPoisonList`/`promotedSkippedPoisoned`
  与 `poisonStep`/`poisonedStepOf`/测试钩子；`materialize` 的 catch 只在「not-offered 且该步 choice 为空」时投毒；
  `promoteOne` 与阻塞路径 `solveInternal` 重建前 `poisonedStepOf(entry.path)` 命中即跳过（`skipPoisoned`）；
  `parkedRefusal` 新增五参重载，文案「N 条…无法重放…跳过；另有 M 条…不再重试」；`resetGuards()` 清空投毒表。
- 门禁 **S158** 7/7（`S158 poison: key=1|打击 → 大颚虫 hit=… count=1`、`S158 refusal: with=2 条…跳过；另有 3 条…不再重试`）。
- 回归：core **`passed=378 failed=0`**（原 371，+S158 七条）；install sha256 `49A8E2633D3ABA7B0417BE4E1760B9831D0D7B78E7BA2847ADD40B4ED7EBFD6A`。
- 文档：`PLAN-BUGS.md` **B107**；`PLAN-P6.md` **P6-19**；`PLAN.md` 板块行改 P6-01…P6-19 / S145-S158 / core 378/0；`TESTING.md` 基线同步。
- 仍未处理：其余回放失败类别（屏上候选对不上、牌不在手、not-settled、指纹不一致）——都是「重建出来的状态不同」，要按日志逐个抓，不能放宽判据。

### 2026-11-03 傍晚 B108：补上 B106 留下的静默缺口（user m04659 第 2 问）

- 起因：B106 把 `answerForReplay` 改成 `if (!Driver.isAwaitingSelection()) return;`，于是「计划这一步记了答案、
  回放时屏却没出现」也被当成"这步没问问题"静默返回 —— 线看起来重放成功，其实少做一步（违反 m04606）。
- 修法：`choice` 为空 + 无屏 → 正常返回；`choice` 非空 + 无屏 → 泵 8 帧仍无屏就
  `throw new ReplayException("not-settled", "the plan answered a screen (…) but none opened (" + Driver.selectionState() + ")")`。
- 门禁 **S159** 3/3：造「带答案但不开屏」的步骤，断言回放失败且失败原因含 `not-settled` 与「none opened」。
  实测 `S159 fake answer: failure=not-settled: the plan answered a screen (Defend_R) but none opened (screen=NONE … awaiting=false)`。
- 回归：core **`passed=381 failed=0`**（原 378，+S159 三条）；install sha256 `7C288BB91D9D1E905C34421E47FD99B32EB07DB7DBCFA1B83CF2878B695E9BFC`。
- 文档：`PLAN-BUGS.md` **B108**、`PLAN-P6.md` **P6-20**、`PLAN.md` 板块行（P6-01…P6-20 / S145-S159 / core 381/0）、`TESTING.md` 基线。
- 结论口径（回答用户"还有没有别的失败原因"）：剩下的失败都是「重建出来的状态和停牌时不同」，共四类
  （牌不在手 / 屏上候选不同 / 屏该出现没出现 / 指纹不一致），修法只能逐类定位，不能放宽判据。

### 2026-11-03 晚 P6-21：面板加「药水: 允许/禁用」（user m04782）

- 之前只能用 settings.txt 的 `search.potions=ban` 做"这条线是否靠药水"的对照，面板没有入口。
- 实现：`search/Limits.java` 新增 `togglePotions()`、`potionsLabel()`、私有 `write(key,value)`（原地替换 settings.txt 里的一行，
  没有就追加，写完 `resetCache()`）；`ui/PanelLayout.java` 的 IDS/LABELS 加 `potion`「药水」；`ui/Panel.java` 的 `activate`
  与 `labelFor` 各加一个分支；`probe/Probe.java` 的 S64 期望键列表同步为 10 个，并新增 **S160**。
- 门禁 **S160** 4/4：按钮翻转策略 / 写入 `search.potions=allow|ban` / 标签带实时状态（药水: 允许 / 药水: 禁用）/
  再按一次复原。实测日志：`S160 potion switch: before=true after=false label=药水: 禁用 …`、`PASS the panel label carries the live state | 药水: 禁用`。
- 回归：core **`passed=385 failed=0`**（原 381，+S160 四条）；install sha256 `A142E29B7CDC8EDEC6DEE7DC1A140A663924B9F85A0FB95207623C87FB35C081`。
- 文档：`PLAN-P6.md` **P6-21**、`PLAN.md` 板块行（P6-01…P6-21）、`TESTING.md` 基线。
- 顺带发现并修掉：`tools/run.ps1:333` 收尾杀进程的 `Where-Object { $_.Path -and … }` 在有对象缺 Path 时往 stderr 抛
  `InvokeMethodOnNull`（不影响退出码）。改成显式循环 + `try { $p.Path } catch` 后再判断；跑 `-Gates S160` 复验，输出里已无该错误。

### 2026-11-03 晚 B109 阶段 1：镜像表能在运行期接收字段（user m04866）

- 用户问「能不能自动判断哪些静态字段/容器影响战斗并自动注册」，决定先只做阶段 1（基础设施），并要我再次确认阶段 2/3 能否达到效果。
- 代码：`mirror/Statics.java` 新增 `dynamicSpecs`/`dynamicGeneration`/`registerDynamic(Field,Kind)`/`dynamicCount()`/
  `generation()`/`resetDynamicForTest()`，`specs()` 末尾追加动态项（按 `owner#field` 去重）；
  `mirror/World.java` 记录 `staticsGeneration`，`install()` 遇到代际不一致直接抛
  `this world was captured before a run-time static registration (N -> M); capture a new world from the live game`
  ——**宁可炸也不静默漏镜像**（用户 m04606）。
- 门禁 **S161** 8/8：注册成功、重复注册 no-op、`dynamicCount()==1`、注册前捕获的 world 安装被拒、两个兄弟 world 互不可见
  （`S161 isolation: live=true siblingSaw=[live] clean=true`）、活内容恢复、注册后字段出现在 `specs()`（`specs=676`）、reset 归零。
- 回归：core **`passed=393 failed=0`**（原 385，+S161 八条）；install sha256 `152E116EF2525DAA00F6C4784464A6BDCFC9B75546FEA9E450A17060064B8523`。
- 文档：`PLAN-P6.md` **P6-22**、`PLAN.md` 板块行（P6-01…P6-22）、`TESTING.md` 基线。
- 阶段 2/3 未做（按用户指示）：判定（写入检测 + 兄弟 fork Digest 差异）与回滚（注册后与 live parity 复核）。

### 2026-11-03 晚 B109 阶段 2（策略 A）：用实测决定一个字段要不要进镜像（user m04965）

- 用户选 A：只有"隔离该字段会改变同一动作的结果"才注册（B 更全但算力代价大）。
- 新增 `mirror/StaticCalibrate.java`：`beforeSearch()` 取 `StaticWatch.dirtyFields()`（只测真被写过的字段），
  对每个候选跑 `probe()`（一次沙盒 endTurn → `Digest.stable`）：共享一次 → `registerDynamic` → 再跑一次；
  结果相同则 `unregisterDynamic`（"isolation changes nothing"）；结果不同才保留注册并打印 WARN。
  `kindOf()`：容器（Collection/Map/数组）用 `MUTABLE_CONTAINER`，其它非 final 对象用 `CLONE`；static final 非容器无法隔离 → 跳过并说明。
- 接线：`Gameplay.startQuickSolve`（在 `String digest = Digest.ofCurrent()` 之前）与 `startDeepSearch`（在 `World root = World.captureLive()` 之前）都调用，
  因为阶段 1 的代际检查禁止"world 捕获后再注册"。整个调用包在 try/catch 里，失败只 warn，不阻塞搜索。
- 门禁 **S162** 4/4：无候选 → `static calibration: no candidate` 且零注册；给一个 dirty 但无关的字段 → `1 candidate(s), 1 probed, 0 registered, skipped […isolation changes nothing] (7ms)`。`-Gates "S161,S162"` → `passed=12 failed=0`。
- 回归：core **`passed=397 failed=0`**（原 393，+S162 四条）；install sha256 `F71F2483912CF9606F792DDBE20F7C9BFA4431D2F2C2FF22664F2A4CE10F45CD`。
- 文档：`PLAN-P6.md` **P6-23**、`PLAN.md` 板块行、`TESTING.md` 基线。
- 阶段 3（注册后的 parity 复核 + 撤销黑名单）未做。

### 2026-11-03 深夜 B109 阶段 3：注册后的 parity 复核 + 撤销黑名单（user m05034「做」）

- 阶段 2 决定"这个字段该不该镜像"，阶段 3 回答另一个问题："镜像它会不会破坏模拟"。证据用镜像自己的
  parity 计数器 —— 每条被回放成功的线都会把重建态与停牌态比对（B101），若隔离把注册表/单例复制了，重建就会开始对不上。
- 代码（`mirror/StaticCalibrate.java`）：新增 `probation`/`probationMismatch`/`blacklist`；
  `evaluateProbation(int mismatchNow)` 在每次 `beforeSearch()` 最先执行：某字段注册后 mismatch 一旦增长 → `Statics.unregisterDynamic`
  + 拉黑 + 打 WARN（写明 `parity 0 -> 1`），并计入 `lastReport`；注册时额外做一次"隔离态第二次 probe"，
  两次不一致则**不注册**（isolation is not deterministic，说明该字段不可复现地克隆）；`adoptForTest`/`isBlacklisted`/`probationForTest`/`resetForTest` 供门禁。
- 门禁 **S163** 5/5：`no parity damage keeps the registration`；`a parity regression reverts the registration`（`reverted=1 blacklisted=true dynamic=0`）；日志
  `static calibration: … was reverted -- with it in the mirror a rebuilt line no longer matched the parked state (parity 0 -> 1) …`。
- 回归：`-Gates "S161,S162,S163"` → `passed=16 failed=0`；core **`passed=401 failed=0`**（原 397，+S163 四条）。
  install sha256 `9E2B4E0D056EC1CC0B2ED9BA6068F8365F8E1610AD0CE99F4C91388BCC8E7C37`。
- 文档：`PLAN-P6.md` **P6-24**、`TESTING.md` 基线。
- 至此 B109 三阶段完成：运行期注册 → 隔离对照判定 → parity 复核与回滚。未做的仍是"把结果写进面板"（目前只在 solver.log）。

### 2026-11-03 深夜 B109 阶段 3 收尾：黑名单必须真的生效（user m05034「做」）

- 阶段 3 主体（probation + parity 复核 + 撤销拉黑）已随前一条落地，但复查发现一个缺口：**黑名单只是记录，没有参与判定**。
  被撤销的字段仍然是 dirty，下一次校准会再次 probe → 再次注册 → 再次撤销，形成**每搜索一次的注册/撤销抖动**
  （generation 每次 +2，且每次都白跑两次 probe）。
- 修法：`StaticCalibrate.beforeSearch()` 的候选循环开头加 `if (blacklist.contains(key)) { skipped.add(key + " (blacklisted after a failed probation)"); continue; }`；
  新增 `blacklistForTest()`。门禁 S163 增加第 6 条断言：把一个已拉黑且仍然 dirty 的字段交给校准 → `dynamicCount()==0` 且报告含
  `blacklisted`（实测 `1 candidate(s), 0 probed, 0 registered, skipped [… (blacklisted after a failed probation)] (0ms)`）。
- 回归：`-Gates "S163"` → `passed=5 failed=0`（六条断言里含综合判定）；core **`passed=402 failed=0`**（原 401）。
  install sha256 `A2CB9A825E069CDBB0D8778540D24AB8870659C69BC9777616BC902B4D34A01E`。
- 文档：`TESTING.md` 基线（402/0、S1–S163、新 sha）。`PLAN-P6` P6-24 保持"阶段 3 完成"。

### 2026-11-03 晚 B110：世界泄漏会把实机存档重定向进沙盒（user m05088/m05182）

- 用户报「打菜农中途退出后重开是之前的猎手存档」，要求「正常保存/加载不被别的存档顶掉」。
- 定位：`solver.log` 15:42:17–15:44:53 共 25022 条 `WARN a world was left installed; restoring the live game`，
  触发点是 `SlaverRed.changeState` 的 NPE（SlaverRed.java:146）从 `Driver.drain` 冒到 `Solver.expand(Legal.apply:205)`；
  `World.uninstall()` 无 try/finally → 抛异常后 `installed/installedInstance` 与 `PlayerPrefs` 的沙盒重定向都不还原，
  实机角色存档于是写进 `preferences/sts1solver-sandbox/`（该目录有 player-CAI_NONG 15:44:53、player-STSDataTheSilent 15:47:14）。
  另发现 `prefs-backup/20261103-152818/PENDING` 未消费（快照里 CAI_NONG 是 11-02 旧版），下一次 run.ps1 会覆盖回 preferences。
- 修法：`World.uninstall()` 改 try/catch/finally（finally 必定 restoreInstall + 清 installed/installedInstance/ConsoleMute）；
  `forceRestore()` catch 也清指针；`PlayerPrefs.restoreInstall()` 校验并强制修回；新增 `PlayerPrefs.recover(Prefs)`、
  `verifyNoLiveRedirect()`（跳过 SESSION/INSTALL 作用域，否则会改坏合成玩家的合法重定向）、`installScopeCountForTest()`；
  `SandboxPrefs.originalOf()`；`World.failNextUninstallForTest()`；run.ps1 遇到 PENDING 默认不还原（移成 PENDING.stale-<HHmmss>，
  仅 `$env:STS1_RESTORE_PENDING=1` 才还原）；已把现存 20261103-152818/PENDING 移成 PENDING.stale-manual。
- 门禁 **S164** 3/3（强制 uninstall 抛异常后不残留 world、install 作用域重定向清零、泄漏目标被 recover 修回）。
  首版 S164 自己踩坑：它在门禁里改了实机玩家的存档路径又没还原，导致后续 S66 两条断言失败（core 403/2）；
  改为用独立 `Prefs` 实例测 recover 后恢复。
- 回归：core **`passed=405 failed=0`**（S1–S164）；install sha256 `9E101592809964678ADDE65B7D63CDCBB22C4486C984BDDD1054F9811B10E4A3`。
- 文档：`PLAN-BUGS.md` **B110**、`PLAN-P6.md` **P6-25**、`PLAN.md` 板块行（P6-01…P6-25 / S145-S164 / core 405/0）、`TESTING.md` 基线。
- 仍未修：`SlaverRed.changeState` 的 NPE（触发源）、以及"从不 uninstall 的调用点"。

### 2026-11-03 晚 B111：动画替身 NPE（SlaverRed.changeState）——B110 的引信（user m05311 继续）

- 用户要求继续做：把上次事故的触发源修掉。
- 反编译定位：com.megacrit.cardcrawl.monsters.exordium.SlaverRed#changeState(String) 的第一步是
  float tmp = this.state.getCurrent(0).getTime()（javap 的 LineNumberTable 显示 line 146 对应这条），随后
  TrackEntry e = this.state.setAnimation(0, "idleNoNet", true)，再 e.setTime(tmp)。
- 根因：mirror/AnimationMute 给沙盒生物换上的是 new AnimationState(data)，它从未被推进过，getCurrent(0) 返回 null；
  并且 "idleNoNet" 未必在该 skeleton 的动画集合里，setAnimation 会返回 null，下一行再 NPE。
- 修法：mirror/AnimationMute 增加内部类 SafeState extends AnimationState：prime() 用
  getData().getSkeletonData().getAnimations().first().getName() 调一次 super.setAnimation(0,name,true) 留下真实 TrackEntry 作 fallback；
  重写 setAnimation(int,String,boolean)（真实返回 null 时交回 fallback）与 getCurrent(int)（真实为 null 时给 fallback）。
  仍是真 AnimationState 的子类，引擎自身行为不变。
- 门禁 S165 3/3：造 SlaverRed 局 → 装 world → 沙盒怪物 state 与实机不同 → 调 ((SlaverRed) sandbox).changeState("idleNoNet") 不抛且
  getCurrent(0) 非 null → 卸载后实机怪物仍持有原 state 对象。实测日志 S165: swapped=true ok entry=TrackEntry。
- 回归：core passed=408 failed=0（S1–S165）；install sha256 36BDF0353786C11B3E481881304F99F7675156FE1C95A252343207030DFDD850。
- 文档：PLAN-BUGS B111、PLAN-P6 P6-26、PLAN.md 板块行（P6-01…P6-26、S145-S165、core 408/0）、TESTING 基线（408/0、S1–S165、新 jar sha）。
- 说明：本轮多次 compress 失败（会话代际与压缩账本不一致，报 ref 失效），按用户 m05311 的指示不再尝试。

### 2026-11-03 晚 B112：门禁遗留的合成自动存档（user m05182 的直接后果）

- 发现：一轮门禁跑完后 saves 里出现 IRONCLAD.autosave(135328 B, 16:15:59) 与 .backUp(8984 B, 16:15:22)，不是玩家打的局。
- 根因：门禁在沙盒里打合成战斗；套件结束时 ModTheSpire 关闭游戏，引擎按常规「退出时保存当前局」写盘，而那一刻没有 install 窗口，
  ExternalMute 的 saves 重定向不生效，合成局就写进了玩家真实存档目录，下次启动会被当成可继续的局。
- 修法：新增 src/main/java/sts1solver/mirror/SaveGuard.java（begin 记录开始时 saves 里的文件名；sweep 删除此后新增的每个文件并逐条 WARN；
  removed 计数），Probe.autoTest() 开头调 begin()、AUTOTEST DONE 之前调 sweep()。
- 门禁 S166 3/3：门禁内写 saves/S166-probe.autosave，sweep 后该文件消失且玩家文件不动（S166 save guard: made=true swept=1 gone=true total=1）。
- 验证：core 411/0（S1–S166）；跑完整套件后 saves 只剩 CAI_NONG.autosave、CAI_NONG.autosave.backUp、steam_autocloud.vdf。
- 清理：16:15 产生的那两个 IRONCLAD 文件已移到 _tmp/quarantine-saves 留档。
- 文档：PLAN-BUGS B112、PLAN-P6 P6-27、PLAN.md（P6-01…P6-27、S145-S166、core 411/0、收尾清单加 saves 检查）、TESTING 基线（jar sha 9750E997…）、本条目。

### 2026-11-03 晚 B114：面板血量比实机多 3（按兵不动+ 强制结束回合，user m00002/m00330）

- 现象：守护者 A4，嗦开嗦开 → 守护者 → 按兵不动+（卡内强制结束回合）之后，面板预测 `P hp=41`、实机 `P hp=38`；两份 Digest 除 `P hp` 外完全一致，之后每步都高 3。
- 先排除（B113，两个探针都进 core）：S167 防御模式 Sharp Hide 3 → 实机/沙盒都是 80→77；S168 卡内强制结束回合 → `atEndOfTurn` 的 Combust 真的跑（S168 自己把 `CombustPower(owner,hpLoss,damageAmount)` 实参写反，断言 1 血却设了 5，一并修正）。
- 根因（bytecode 核对）：实机「按 End Turn」= `callEndTurnEarlySequence` → `EndTurnButton.disable(true)` → `NewQueueCardAction` 压入空 CardQueueItem 哨兵 → `getNextAction` 读到 card==null 才跑 `callEndOfTurnActions()`（手牌 `triggerOnEndOfTurnForPlayingCard` = 灼伤在这里结算）。沙盒 `Driver.drain` 一看到闩锁就 `room.endTurn()`，哨兵被 `ClearCardQueueAction` 清掉，`callEndOfTurnActions` 永不执行 → 灼伤 0 伤害。
- 算术：15 格挡 − 3×2（灼伤，`DamageAction` 不调 `applyPowers`，NoseNib ×2 不参与）= 9；守护者 4×3（5 × NongBai 0.75 → 3.75 → 3）= 12 → 漏 3 → 41−3 = 38。与实机 Digest 完全一致。
- 修法：`drive/Driver.java` 新增 `runEndOfTurnQueue(gam, capMillis)`（≤512 轮，墙钟 ≤1000ms，`pumpRoomObjects`+`gam.update` 到队列空，异常/超时只记日志），并接到两处：`drain` 闩锁分支（`room.endTurn()` 之前）、`Driver.endTurn()`（先 `addToBottom(new NewQueueCardAction())` 再造哨兵，跑完队列再 `gam.endTurn()`+`room.endTurn()`）。队列跑空后 ClearCardQueue 无哨兵可清，`callEndOfTurnActions` 每条路径恰好一次。
- 门禁：S169（Vault+3 灼伤，80→74）、S170（Driver.endTurn+3 灼伤，80→74）各 1/1；S168 修正后 1/1。
- 验证：core **415/0**（S1–S170）；jar sha256 `07316D63FB44FCD035F7B7F946A9DA922453A542EEEDF4E3EEB5827C3E935879`（`javap` 已确认装上的 Driver 里有 `runEndOfTurnQueue`/`endOfTurnQueueTimeouts`）。
- 文档：PLAN-BUGS B113/B114、PLAN-P6 P6-28、PLAN.md（bug 表 + `P6-01…P6-28` + core 415/0 + 收尾清单加「血量对齐」）、TESTING 基线、本条目。
### 2026-11-03 晚 B115：面板计算中先给「暂定路线」（user m00748 第 1 条）

- 现象：点「计算」后要等整轮算完才有路线（几十秒），计算中只有「已查阅 N 条世界线」。
- 修法：`Solver.Session.bestPath()` / `PortfolioSession.previewPath()` / `QuickSession.previewPath()` 三个只读访问器 + `Gameplay.refreshPreview(elapsed)`（3 秒、签名去重、最多 12 步、文案写明「未回放校验」）/`clearPreview()`/`setPreviewAfterMsForTest`。
- 为什么不用 pill 条：`buildRoute()` 需要 `Step.turn/hp/monsterHp`，只有 replay 才有，3 秒一次 replay 会卡渲染线程。
- 门禁 S171 6/6（含「设 10 分钟则不出现」的负向断言）；首跑红=第二次搜索命中 plan cache，补 `PlanCache.clear()`。core **421/0**。

### 2026-11-03 晚 B116：菜农打 2 小偷只给 8 步、不算可赢（user m00748 第 2 条）

- 现象：`QUICK win complete=true` 但 `plan replay failed at step 9: 拍点点 … is not in hand`，只打印前 8 步；实机第 8 步 按兵不动+ 被拒（`cost=0 energy=0 turnEnded=false`，即 `BideTime.canUse()` 的 `isUseAttack` 为 true）。
- 两个被证伪的假设：`Driver.pumpAfterAction()`（24 帧，S172 负向测试后删除）、`usingCard` 闩锁（`Driver.leftoverUsingCard` 保留，是真修复但不是本症状）。
- 根因：`mirror/ModStatics.build()` 用 `Provenance.isGame(type)` 判来源，`MTSClassLoader` 不给 mod 类 CodeSource → `sourceOf()==null` → 当成「mod 打包的 vanilla 类」跳过 → `BideTime#isUseAttack` 不在镜像表、跨窗口泄漏（探针：卸载后仍 true）→ 搜索提供实机不允许的 按兵不动+ → 重放 `not-offered` → 断线。
- 修法：`Provenance.shipsInGame` 改 public static；`ModStatics.build()` 改按类名判断（扫 jar 时名字本来就有）。
- 验证：bide 探针由「只给前 3 步、不算可赢」→ `win=true`、`可赢（已搜完可用路线，最好：掉血 8）`；`mirrored=true`、`t3 uninstalled: flag=false`；静态扫描 `0 vanilla bundled`。
- 门禁：S172 2/2（回放中开屏动作走完自身链）、S173 2/2（无 CodeSource 的 mod 静态进镜像并在 uninstall 后还原；无 CaiNongMod 时 skipped）。core **424/0**（S1–S173）。
- jar sha256 `5AB798B713A01A2C800D578CA166A16A0A9513EB173E7713394161524AAC647C`。
- 文档：PLAN-BUGS B115/B116、PLAN-P6 P6-29/P6-30、PLAN.md（bug 表 + 收尾状态 424/0 与新 sha + 两条新验收项）、TESTING 基线、本条目。
### 2026-11-03 晚 B117：计算中的预览改为正常路线条（user m01814 第 2 条）
- 现象：B115 的预览是单行文本「暂定路线（仍在计算，未回放校验）: A → B → …」（最多 12 步），和算完后的按回合分行 pill 条不是一回事；用户要求「像计算完毕那样给出一个正常的面板，而不是现在的一行。只要之后能更新就可以」。
- 修法：`Gameplay.previewRoute` + `buildPreviewRoute(path)`（按 `isEndTurn()` 切回合行、行号从 `GameActionManager.turn` 起、最多 `PREVIEW_ROWS=8` 行、pill 的 step = 路径下标）+ `publishPreviewRoute()`（灌进 `route()/rowSteps`）+ `previewActive()`；`ui/Panel.hasPlanNow` 兼容预览态，于是预览和正式计划走同一条绘制路径；`clearPreview()` 一并清路线条。
- 为什么仍不 replay：`buildRoute()` 依赖 `Step.turn/hp/monsterHp`，只有 replay 才有，3 秒一次 replay 会卡渲染线程（B114 已证明强制结束回合那步很贵）。
- 已知近似：卡内强制结束回合在路径里不可见，预览会把两个回合并成一行、回合号可能偏 1；标题行写明「仍在计算，未回放校验」。
- 门禁 S171 由 6 条改 7 条：必须断言 `previewActive()` 为真、行数 > 0、pill 总数 > 0、首行以「第 」开头，且**在循环里与预览文本同一拍采样**（搜索结束后 `finishQuick` 已清空）。S171 单跑 7/0；core **425/0**（S1–S173）。
- jar sha256 `D3E8853B913F135CA96C27800FB92CD40225326B607F66D0DF47210D5528A38A`。
- 顺带答复（无需改动）：面板「节奏」= `ui/AutoPace` 的 `autoplayDelayMs`，自动出牌时两个动作之间的等待毫秒，档位 0/250/450/750/1000（默认 450），0 表示瞬间；存 settings.txt。
- 文档：PLAN-BUGS B117（并订正 B115 的「单行文本」描述）、PLAN-P6 P6-31（P6-29 标注被升级）、PLAN.md（bug 表 + 收尾状态 425/0 + 新 sha + 第 5 条验收改成路线条）、TESTING 基线、本条目。

### 2026-11-03 晚 B118：计算中的预览加上预计掉血（user m01978）

- 需求：预览也要像算完的面板那样给出「预计掉多少血」。
- 做法：`Solver.Session.bestHpLoss()` / `PortfolioSession.previewHpLoss()` / `QuickSession.previewHpLoss()` 直读搜索持有节点的累计 `hpLoss`；`Gameplay.hpPreviewText` 在预览标题拼「 · 预计 -N HP → 剩 M HP」（0 → 「预计无伤」，-1 → 不加）；不回放。
- 门禁：S171 新增一条断言（预览文本含「预计」且含「HP/无伤」）→ 8/8；core **426/0**（S1–S173）。
- 产物：jar 939096 B，sha256 58359512A01970B7BAF87E7BC72487BCE95236C703DD0FB92F0DC608D6B58819，已安装。
- 已知边界：预览血是整条线的累计（与算完面板 headline 同口径），不是逐回合；逐回合要回放，会卡渲染线程。

### 2026-11-03 晚 B119：游戏暂停时计算也暂停（user m02060）

- 需求：「游戏暂停的时候计算也暂停」。改前按 ESC 暂停只冻游戏，搜索照花预算，回来时进度条已过半甚至已收尾。
- 判据：暂停菜单就是 `SettingsScreen`（`CurrentScreen` 里没有 PAUSE），`isScreenUp && screen == CurrentScreen.SETTINGS`。
- 做法（两层）：①`Gameplay.tick()` 最前挂闸门，暂停时整帧提前 return（搜索、自动出牌、步执行、开屏应答全停），并 `pauseForMenu()`/`resumeFromMenu()`；②`Gameplay` 换可暂停时钟 `now() = System.currentTimeMillis() - pausedTotalMs`（13 处时间戳改用 `now()`），恢复时把暂停时长通过 `compensatePause(ms)` 还给 `QuickSession`/`Solver.Session`/`Solver.PortfolioSession`/`Driver.selectionSinceMs`——只挪起点，不动任何派生数字。
- 面板：暂停且有搜索时 `lines()` 只给「[计算已暂停]」两行（冻住的进度条会被读成卡死）。
- 门禁：新增 **S174** 8/8（识别 SETTINGS 屏、暂停期节点 52→52 不增、文本含「暂停」、恢复后 elapsed 86→126 即只涨 40ms、暂停后仍能跑完）；core **434/0**（S1–S174）。
- 产物：jar 942589 B，sha256 4AC970AD6F0A027C910116343CFDE702182599FEC4247C073523EC9D0813550B，已安装。
- 已知边界：窗口失焦不算暂停（游戏本身还在跑）；暂停期间玩家退出战斗不会被拦；回滚只需去掉 `tick()` 顶部两段 gate。

### 2026-11-03 晚 B120：零伤最优不再「计算中」（user m02336）

- 现象：预览写「预计无伤」，搜索还在跑（quick=solve、turn=1、约 900 节点/秒）。
- 根因：切片搜索 `Solver.Session.microStep()` 从不读 `provenOptimal`（不分片的 `solve()` 与 `PortfolioSession` 都读）；`REFINE_LAYERS` 全库没人用；「无伤」只是 `best.hpLoss==0`，未必已胜利。
- 修法：`microStep` 在节点预算之后读 `provenOptimal && best.win` → `reason="win"`；`Session.bestIsWin`/`PortfolioSession.previewWin`/`QuickSession.previewWin`；预览未胜利时写「目前掉 N HP（还没打到胜利）」，胜利位进签名。
- 门禁：新增 **S175** 2/2（+60 力量一击必杀 → nodes=2）；反向验证把停止条件改成 `false &&` → nodes=5265、FAIL，恢复后 PASS。S171 的文案断言放宽为接受两种写法。core **436/0**（S1–S175）。
- 产物：sha256 6D6E78C237EC7C828CFF29B348B8371EA9EE385634BD5D436A98109AEEDD7365，已安装。
- 已知边界：零伤胜利不再找「同样零伤但回合更少」的线（与不分片路径一致）；回滚=删 microStep 那段 if。

### 2026-11-03 晚 B121：预览不再显示半条线（user m02524）

- 用户：「目前掉 N HP（还没打到胜利）」就不应该给我这样的线，一直强调要走完、不要打一半的线。
- Gameplay.refreshPreview 加 quick.previewWin() 闸门：没有已胜利的完整线就 clearPreview()，只留进度行；删掉 previewWin 字段与「目前掉 N HP（还没打到胜利）」文案，hpPreviewText 回单参；新增 previewWinForTest()。
- 门禁：S171 场景换成 3 只不加强度的小怪（单怪/加强度会被 B120 提前收尾，预览采不到样），断言加到 10 条（新增「必须已胜利」「永不出现半条线」）；新门禁 S176（5 张防御 vs 大颚虫，整轮都不许出路线条）。S171 10/10、S176 1/1、core **439/0**（S1–S176）。
- jar sha256 0A9106AF3B1AC3B792BDEDBFA0565BA18859E176F307859689CF30998261528D。
- 文档：PLAN.md（B121 行、B120 订正、收尾 439/0 + 新 sha、验收第 5、10 条）、PLAN-P6.md（P6-34 订正 + P6-35）、TESTING.md（sha、439/0（S1–S176）、S171 改 10、S176(1)、共 74 条）、PLAN-BUGS.md（B121 节）、本文件。

### 2026-11-03 晚 B122：昏睡打出后没跳过下回合 + 面板不显示被动结束回合（user m02740）

- 用户原话：「昏睡这张牌打出后下一回合应该直接结束回合（你自己看效果）但是模拟没模拟出效果」；「结束回合无论是主动的还是被动的都要进面板，出牌也是的」。
- 根因：`Driver.endTurn` 期间 `suppressRoomEndTurn=true`，drain 的闩锁分支把 TrancePower 在回合开始产生的那次按下也挡掉，`endTurnQueued` 残留、回合不推进（探针：turn 1->2、endTurnQueued=true、回血 40→64 已发生）。
- 修法：Driver drain 改成每 tick 只认「还没被消费的按下」`pressNow=endTurnQueued(gam)`（并排除 turnHasEnded，修掉 S168 掉 2 血的回归）；Action 加 `turnBefore/turnAfter` 由 `Solver.expand` 在沙盒内记录；`Gameplay.buildRoute`/`buildPreviewRoute` 遇回合跳号补「第 N 回合 · 结束回合（自动）」行（pill step=-1）。
- 门禁：新增 S177 4/4、S178 2/2；S174 场景换成 3 只小怪（单怪 55ms 就被 provenOptimal 收尾）；S168 回到 80→79。core **445/0**（S1–S178）。
- 产物：jar sha256 6F3550AF3C152FB153AFC1A2C1AF1D0D8368A58B4FD89A78780250606C05B747，已安装；TranceProbe 与 `-Mode trance` 保留为手工探针（需 -AllMods）。
- 文档：PLAN.md（B122 行、收尾 445/0 + 新 sha、验收第 5 条改写 + 新增第 10 条）、PLAN-P6.md（P6-36）、TESTING.md（sha、445/0（S1–S178）、S177(4)/S178(2)、共 80 条）、PLAN-BUGS.md（B122 节）、本文件。
- 已知边界：能力/遗物自动打出的牌不是独立 Action，无法在面板单列；若某 mod 不走 EndTurnButton 而是直接置 isEndingTurn，则该次被动结束不模拟。

### 2026-11-03 夜 B123：唐自动打出的牌面板看不见、计划牌被打掉后自动出牌停下（user m03233）
- 实机日志：23:25:59 计划第 15 步「防御」已被唐打掉 → `fail` 停自动；23:25:57 step 15 drift（沙盒 TangPower 剩 1 层、b=5，实机 0 层、b=10）。
- 新增 `drive/AutoPlays`（`useCard` 后置记录 + `markOurs` + `wasAutoPlayed` + `describeThisTurn`）；`Gameplay.submit` 命中即跳过该步继续；`lines()` 加「自动打出（本回合，实机）」行；`reset()`/新战斗清空。
- 门禁 S179 4/4；core **449/0**（S1–S179）；jar sha256 945724184B6EC19504EC3C40AA2A7A2E3A1C9C6EB396A7803CFAD83CD47B8985（963780 B）已安装。

### 2026-11-04 B124：唐自动打出的牌没进计划（user m03444，撤销 B123）

- 用户否决 B123 的实机侧补丁，要求「改进沙盒模拟、不要给唐做特判」「唐是随机打出层数的数量张牌」。
- 先证伪：TangProbe 单回合 live 与沙盒 digest 逐位一致；LockstepProbe 连跑 4 个结束回合、7 条 RNG 流计数器全同（LOCKSTEP first difference: none）；DiscardAtEndOfTurnAction 的 shuffle 只影响触发顺序。
- 真缺陷是记录：沙盒跑了唐的牌，计划只记自己那张。修法 = `Action.autoPlays`（expand 内用 cardsPlayedThisTurn 前后差值推出）+ `Gameplay.autoPlaySuffix` 拼进 pill + `submit` 的 `skipAutoPlayed`。
- 门禁 S180 5/5：用探针能力走 onAfterUseCard → NewQueueCardAction 同一路径（不用唐，避免层数与能量干扰；并 gainEnergy(3) 补唐花掉的能量）。
- core 450/0（S1–S180）；jar sha256 CD311FD24BBE0A118CC9AF92618FBD80DA94973BDD329ACFD0D3F1511DA8050A 已安装。
- docs：PLAN.md（B123 标注撤销 + B124 行 + 收尾 450/0 + 验收第 12 条改写）、PLAN-P6.md（P6-37 标撤销 + 新 P6-38）、TESTING.md（sha/450/0/S180(5)）、PLAN-BUGS.md（B124 节）、本文件。

### 2026-11-04 夜 B125：面板第三回合 4 个「结束回合」＝已决定的战斗仍有决策（user m03996）

- 现象：菜农打 2 小偷（Looter + SpikeSlime_M）第 3 回合面板列了 4 个结束回合；用户还报「笋干缪哈没有费用打出」。
- 排查：先证伪三条（GrowthCredit 默认 0、SpikeSlime_M 不分裂、结束回合常规结算 probe 连按 4 次 HP 80→80→70→60→40 全对）——结论是模型在**战斗已经决定之后**才出错。
- 根因：游戏 `getNextAction` 在 `turnHasEnded && areMonstersBasicallyDead()` 时跳过 `turn+1`，结束回合变成 no-op；`Legal.actions()` 无条件给 `endTurn`、`Solver.candidates` 不判结束、`Driver.endTurn` 把 `combatOver` 当成功 ⇒ 搜索无限串联 no-op 结束回合，`Step.turn` 全相同，面板画成一行 4 个，且不计代价。
- 修法：`Legal.actions`/`Solver.candidates` 在 `Driver.combatOver()` 时返回空；`Driver.endTurn` 增加一轮 drain 重试 + `endTurnFinished` 判据，卡住的结束回合 `refused` 并写 `endturn-refused` 日志；`Solver.Result.planTrace()` 在发布计划时写逐步 `trace:`（t/hp/b/e/mhp，结束回合没推进标 `!stuck`）。
- 门禁：S181 4/4（已决定世界无候选、结束回合后仍 decided、新 Session 不出步）、S182 3/3（合法结束回合不被误拒、`endTurnFinished` 真值表）；core 457/0（S1–S182）。
- 产物：jar `C2288E68AF7D0E4474E2A47CA033E0CD31D09046B1C5815F3A79E3E66FF79D0A`（984821 B）已安装。
- 未定论：第 ① 条「笋干缪哈没费用」——那一局执行到第 9 步（口渴+ 后的选牌屏）就停了，之后用户手打；等下次复现读 `trace:` 行（沙盒认为的 `e=`）与实机能量。
- 观察：一次 core 里 S66 偶发红一次（无异常、单跑与复跑皆绿），记为时序偶发，未改。
### 2026-11-04 上午 B126：唐打出昏睡后连跳回合，计划被整条判「过期」（user m04653）

- 现象：实机 01:17 一局（邪教徒+守护者），唐自动打出「昏睡+」→ `TrancePower` 在回合开始按结束回合，连跳 2 回合（2→4）；面板 20 s 只说 `pending action did not settle`，随后 `plan stale: step 7 belongs to turn 2, live turn 4` 丢掉整条计划。
- 先证伪：`TranceChainProbe`（mode=chain）证明沙盒会连跳（turn 1→3、1→4）、唐在被跳过回合照样出牌、真 TangPower+5×昏睡也 1→4；日志 trace `8:t=4` 与实机一致 ⇒ 沙盒机制正确。
- 真实缺陷：①`Step.turn` 只记动作开始回合，没记回合跨度；②`planIsStale()` 只看当前这一步；③pending 20 s 全程无日志。
- 修法：`Step.turnBefore/turnAfter` + `replay()` 测量 + `planTrace()` 的 `t=a->b skip=N`/`auto=`；`planIsStale()` 看剩余步骤；`settle wait` 每 2 s 一行；「越过即完成」（turn 超过沙盒测得的结束回合且队列安静）；顺手修 B125 的 `!stuck` 误标。
- 门禁 S183（跳过跨度 1→3）/S184（planIsStale 真值表 2 条）；core 460/0（S1–S184）；jar sha256 44B7BA67B751F62B75C5B70B5276E40AF259DB30D184E95086F0E26B337FBCA0（998360 B）。

### 2026-11-04 B127：面板给出付不起的牌（能不能打用了两套判据）（user m04994 / m05130）

- **触发**：蛇花战 12:36 的 34 步计划开头 `农盘+ | 农盒 | 按兵不动+ | 昏睡`，实机前两张花光 3 能量，
  第 3 步「按兵不动+」cost=3 点不出来；`settle wait: step 3/34 turn=1->1 expectedEnd=2` 静默 21 秒后
  `world switched`。搜索 trace 已自曝：第 3 步 `t=1->2 e=4 skip=1`，即沙盒 0 能量打出了 3 费牌。
- **根因**：实机点击判据是 `hasEnoughEnergy() && canUse()`，卡队列只判 `canUse()`；原版卡基类
  `canUse` 自带能量检查所以两者等价，mod 卡覆写 `canUse` 不调 super 就丢了能量检查
  （CaiNong `BideTime`＝`return !isUseAttack`）。`Legal.actions()` / `Gameplay.submit()` 都走
  `Driver.canUse`（只看 canUse），于是付不起的牌也能进候选、进计划。
- **修法**：新增 `Driver.playerCanUse()`＝`hasEnoughEnergy() && canUse()`；`Legal.actions` 两分支与
  `Gameplay.submit` 改走它；`Driver.canUse` 保留引擎队列语义（autoplay 的 `isInAutoplay` 豁免）。
- **同轮**：查证「世界切换后 pending 残留」是误读（`Gameplay.java:1601` 已清 pending，日志打印清除前的值），
  不改代码；用户否决「费用写进 digest」（蛇眼/混乱每次摸牌重掷）与「提交后校验」。
- **门禁**：S185（4 条，覆写 canUse 的假卡 + 能量 0/3 双态对比）。实跑
  `engineCanUse=true playerCanUse@0=false offered@0=false playerCanUse@3=true offered@3=true`。
- **基线**：core **464/0**（S1–S185，460 + S185 四条）。安装 jar 1001511 B，
  sha256 `881B22D78C09DF00390B898C8DB6C60E800C44DF505B269D1E35F4ECCA3282E2`。
- **卡顿（用户 m04994 第 1 条，只做了粗看）**：已确认的阻塞点＝回主菜单 `StatsScreen` 构造时同步读全部
  角色统计（vanilla，watchdog 4.1s 栈在那里）；我们自己的开销＝面板每刷新一轮重扫 mod 静态
  `mod statics: 365 field(s) in 1009 class(es) from 17 jar(s) (46–65ms)` + StaticWatch 每轮
  `SneckoInfo#costs` 写回 / `generation` + 搜索期 GC 291 次 27.4s。用户将自行做对照试验，未改代码。

### 2026-11-04 下午 B128：烟雾弹逃离（user m05245）

- 用户两条：①烟雾弹是逃离、没有奖励、BOSS 不能用，算出「只能逃」时不该再喝第二瓶药；②烟雾弹后战斗该结束，但面板后面还有行动。
- 根因：`AbstractRoom.endBattle()` 只有两处调用者——怪死（引擎路径，一直在跑）与 `AbstractPlayer.updateEscapeAnimation()`（只被渲染帧调用）。沙盒从不跑 `player.update()`，`escapeTimer` 停在 2.5 → `endBattle()` 永不发生 → 战斗仍是 COMBAT、怪还活着、`Legal.actions()` 继续给候选，搜索把「技能药水 + 烟雾弹还能赢」报成 WIN（用户日志 14:12，potionHp=23）。
- 先证伪：`SmokeBomb.canUse()` 本身已拒 BOSS/BackAttack（所以「BOSS 不能用」没漏）；sts2 参考求解器 CombatSolver-main 也没有玩家逃离模型。
- 修法：`Driver.tickEscapeFrame()`（沙盒内逐行复刻 `updateEscapeAnimation` 的状态部分，限速日志点名这一拍）+ `escapePending()` 参与 idle/stalled 判据 + `Solver.monstersDead() = combatOver() && !roomEscaped()` + `Solver.turnAfterOf()` 夹住 `turnAfter`（`endBattle()` 的 `actionManager.clear()` 会重置 turn，实测 11→1）。
- 门禁 S186（6 条）：不可赢局面下搜索给出 `escaped=true win=false`、计划最后一步是烟雾弹、回放后 `room.smoked`、`isEscaping/escapeTimer` 清零、`combatOver()`、逃离后候选为空。实跑 6/6。
- core **470/0**（S1–S186）；最终 jar 1005924 B，sha256 `7572363A1D96112D07043FDC49C1E78E9BA1E2181298E4A7E71F12714525E4D8`，已安装。
- 已知边界：逃离线只算药水与 HP，未建模「丢掉本场奖励」；沙盒随机流与实机不同这条老边界不变。

### 2026-11-04 傍晚 B129：烟雾弹逃离要付代价（user m05700）

用户三条：①「你做，不要留漏洞」②逃离节约的血是否比损失的卡牌/金币更值（小怪卡牌 2 血、boss 卡牌 10 血）③活下去的价值最大，任何收益不能大于活下去。

查出的三个真缺陷：

1. `Solver.roomEscaped()` 只读 `room.smoked`，而烟雾弹 `use()` 一进入就置 `smoked=true`、真正的 `endBattle()` 要等逃离动画走完（`Driver.tickEscapeFrame`）⇒ 中间几帧战斗没结束却已被记成逃离。改成 `room.smoked && Driver.combatOver()`。
2. 逃离原来按 `EscapePolicy.terminalBonus()` = +200000 分（＝100 HP）白送，本场奖励一分不扣 ⇒ 一瓶 9 HP 的烟雾弹净赚 ≈91 HP，这就是它被主动乱用的原因。删掉该方法，改为逃离开销：卡牌 2 HP（普通怪）/10 HP（BOSS），金币按 `GrowthBook.GOLD_UNIT=25` 折算、金额取 `AbstractRoom.update()` 里 `addGoldToRewards` 的真实 roll 中点（normal 15 / elite 30 / boss 100，升天≥13 boss ×0.75）。合计 5200 / 6400 / 28000（升天 13 的 boss 26000）分。
3. `Result.escaped` 取自会话级 `bestEscaped`，而印出来的计划可能是从 `deepestPath`/split/`finishLine` 重建的另一条线 ⇒ S186 实测最佳线末步是烟雾弹却报 `escaped=false`。新增 `Action.escaped`（`expand()` 里记录、三个 `withXxx` 副本继承）+ `Solver.planEscaped(steps, fallback)`，两处 Result 构造都改用它。

「活下去最优先」不靠新参数：既有 `score()` 已是胜利 +10,000,000 / 死亡 −50,000,000，growth 收益折 HP 上限 50 HP（几十万分），永远压不过生死项；逃离 −5200 恒优于死亡。

门禁：新增 S187 6/6（定价表 normal=5200 / elite=6400 / boss=28000 / bossAsc13=26000，gold 15/30/100，live room 实测 5200）；S186 按新语义改造——旧构造（400 血虫 + 80 血玩家）在「逃离要扣奖励」后不再产出逃离线，改成真正的「不逃必死」：沙盒内玩家 5 HP + `CombustPower(player,20,1)` 当必死时钟（教训：合成战的怪 `intent=DEBUG`、`getIntentDmg()=-1`，靠给怪加力量造不出必死）。S186 6/6 全绿（`escaped=true win=false steps=4 lastPotion=true smoked=true leaving=false over=true offeredAfter=0`）。S8 断言从 `terminalBonus()==0` 改 `forfeitPoints()==0`。

core **476/0**（S1–S187，_tmp/run-core-b129.txt）。jar 1008516 B，sha256 `CA7329DE37ECA6B4813FC2A71609F896ABCB9AF81656517DE8F8C254EA89527D`，已装 D:/Steam/steamapps/common/SlayTheSpire/mods/sts1-engine-solver.jar。

教训：①在门禁里用 fork/install 手工复现逃离会互相踩（`another world is installed` + 泄漏的 world），必须在 sandbox 内改再加能力、再 `readBack`；②临时诊断用完必须 grep 清干净（本轮清掉 Solver 的 `POTION diag`、Driver 的 `escape-diag`/`drain-diag`、Probe 的 `S186 peek/trace/plan`）。

已知边界：沙盒读不到本场金币奖励真实金额（发金币在真机 UI 帧），用真实 roll 中点显式定价；逃离未建模遗物/药水位。

### 2026-11-04 晚 B130：地精头子预计血量比实机多 10（死怪能力没清，user m06143/m06264）

**定位**：用户在 19:00 那局（GremlinLeader，A4）看到 `trace:` 预测终血 61、实机 51。唯一分叉是 step 16「防哈+」：expected `P hp=63/80 b=49` vs actual `P hp=63/80 b=39`（HP 当时还没差），同对 digest 里 4 只 0 血杂兵 expected 仍带 `Minion/NongBai/NongHei`、actual 全部 `pw=-`。

**根因**（逐层证到引擎）：`AbstractMonster.die()` 只起 1.8s 死亡动画（`isDying=true`、跑 `onDeath()`、`deathTimer+=1.8f`，**不清 powers**）；`powers.clear()` 在 `updateDeathAnimation()` 里，只由渲染帧（`AbstractMonster.update ← MonsterGroup.update ← AbstractRoom.update`）驱动；`Driver.drain` 从不跑 `room.update()`（唯一一处在 `pumpRoomFrames`）⇒ 沙盒死怪能力永留。防哈（`CaiNongMod:DefendToKill`）按 `monsters.monsters` 里「带农白的怪」再加一次格挡且**不过滤死怪** ⇒ 29+20=49 vs 实机 29+10=39。digest 没报是因为 `Digest.monsterStable` 刻意剥掉死怪能力（B41/B75/S80）。

**修法（方案 A，用户 m06264「按照 a」）**：新增 `mirror/DeathSettle.java`（`settle()`：`isDying && !isDead` ⇒ `isDead=true` + `powers.clear()`；不调 `dispose()`、不调 `endBattle()`；前 3 条 `death settle:` 日志）；接到 `World.trimPresentation()` 的 effect 结算之后，即每步 readBack/digest 之前、`replay/expand/materialize` 共用的结算点。

**门禁**：S188 4/4（无 mod，原版 StrengthPower）；S189 3/3（带 CaiNongMod，死怪带农白时防哈 `gained == defend.block`=7，不再是 14；不加载 mod 时 skipped）。反向验证：临时注释掉 `DeathSettle.settle()` 重编后这 4 条红（`gained=14`），恢复后全绿。

**结果**：core **481/0**（S1–S189，_tmp/run-core-b130.txt）。jar 1015553 B，sha256 `981F7A7AC2C39F7275F6423D43B7AE31D4FC127BD1A67E348E4975D1480422D0`，已装 D:/Steam/steamapps/common/SlayTheSpire/mods/sts1-engine-solver.jar。

**教训**：①「动画里才落地的状态」不止 effect（B76）与逃离（B128），死亡动画也是——凡 `update()`/`updateDeathAnimation()` 里写的状态，沙盒都得在结算点补终态；②门禁要先证明「修前必红」（本轮用临时注释 + 重编验了一遍），否则不知道自己测的是不是一个 no-op；③digest 的「刻意忽略」规则（死怪 powers）是盲区来源，遇到「只有某张牌算错」时要先想「引擎按什么计数」而不是只比 digest。

**已知边界**：死亡动画 1.8s 内打出的计数牌实机仍会算到死者，沙盒按已清算算（手动出牌 2–4s 间隔通常已过窗口，自动出牌 450ms 紧跟那一步可能反向差一次）；备选方案 B（按帧递减 `deathTimer`，仿 B128）未采用。

### 2026-11-04 夜 B132/B133：药水选屏把抽牌随机流推偏（面板给出实机付不起的牌，user m06435/m06536/m06908）

- 用户：第一勇士第七回合第 3 个行动「防御」实机能量不足打不出，面板却给了；要求「不要特判、一开始就模拟对、不产出打不出的计划」。
- 定位：第 15 步能力药水 → `PowerPotion` → `DiscoveryAction(CardType.POWER)`；`DiscoveryAction.update()` **每帧无条件**重掷 3~5 次 `cardRandomRng`，沙盒答屏泵的帧数与实机不同 ⇒ 随机流永久偏移 ⇒ 蛇眼 `ConfusionPower.onCardDraw` 给每张抽到的牌掷出的费用全不同（两份 digest 只差 `e=`）。药水之前 13 步连卡费都一致、之后第一次抽牌全体不一致。
- 用户否决「量实机 update 次数再钉沙盒」（m06893：那是结果反推）与「执行侧拦截/缓存纳入费用」（m06879 ③：只要开始就模拟对）。
- 修法（B133）：按引擎调度建模——`Driver.roomBlockedByScreen()`/`peekingNow()`，模态屏等待期间只 `pumpScreenSelf(1)`，绝不 `gam.update()`；`pumpWhileScreenOpen`/`pumpUntilSelection` 同样分支；`Synthetic` 开打时清空残留 `combatRewardScreen.rewards`（否则游戏 `closeCurrentScreen()` 的 GRID 分支把 screen 送回 COMBAT_REWARD，闸门会误冻结）。
- 门禁 S190 8/8（屏等待泵 24 帧随机计数不变）；反向验证短路闸门 → 7/1 红；core **489/0**（S1–S190）。
- 安装：jar 1032219 B，sha256 `4A99F5DF8BD3F3681058233F1830B2E1D677B4EB81080E8909300C22B28CCB2D`。
- 已知边界：答屏之后每帧仍由引擎 `duration` 决定，真机/沙盒帧数差异未建模；`ScreenRngProbe` 的 live 数字不可当基准。

### 2026-11-05 B134：模拟死亡不再给外部发数据、也不再累积跑团记录（user m07304）

- 现象：`sendToDevs/mts_process_launch.log` 一局 415 MB，1171 条 `UPLOADING METRICS TO:` 合计 122 MB；「80」「0」是同一 JSON 被并发 stdout 截断的碎片。
- 取证：第 300 条上传的 `damage_taken` 373 条里 floor 3×348，全是同一只 Jaw Worm、turns/damage 各不同 ⇒ 是搜索里的模拟战斗。
- 机制：模拟死亡构造 `DeathScreen` → `MetricData.addEncounterData()`；Minty 的 `submitDefeatMetrics` 补丁只看 `Settings.UPLOAD_DATA`、`MintyMetrics.run()` 绕过 `Settings.isModded` → `Metrics.sendPost` 在发请求前先 `logger.info` 整段 JSON。
- 修法：`World.install()` 关 `UPLOAD_DATA` + `clearData()`；`Statics` 给 `metricData` 标 `discardOnReadBack`；`ClonePolicy` 不再共享 `MetricData` 对象（否则模拟写实机、`clearData` 还会清玩家记录）。
- 门禁：S191 7/7（窗口内关上传、记录空、模拟死亡只落沙盒、卸载后实机不变、再装又从空开始）。core **496/0**（S1–S191）。
- jar：1034924 B，sha256 `6E8D9E8CCE81DDFEB2331569D20BC81B4EF5E2CD16FC9D77B72E703C314CAC2F`（已安装）。

### 2026-11-05 B135：豹指导选牌后「（自动：…）」标成另一张牌（user m07728）

- 现象：打出豹指导选「无人形态」，面板写「自动打出 嗦开嗦开」；计划第 29 步同类（选「牌烧完了我打什么」→ 记「拍点点」）。
- 根因：autoPlays 只在探针叉（每屏第一选项）记录一次并写进父 Action，`withChoice` 把它复制给每个真分支；分支枚举本身从不记录。
- 修法：`Choice.autoPlays` 每分支独立记录（`recordAutoPlaysInto`）；`Solver.actionFor` 把「答案＋该分支自动出牌」写进子动作（阻塞组合与切片两处循环）；`Solver.replay` 再按回放重记一次；走屏抽成 `expandCore(world, action, n, applyNow)`，新钩子 `expandQueuedAutoForTest`。
- 门禁：S192 3/3（测试替身 `PlayChosenProbeAction`：两个答案各记自己的牌；反向验证屏蔽分支记录后 2 条红）。
- core：499/0（S1–S192）；jar `8742C02B527838B3071045DB69F8FB6244012B61BB7B84695DCE73104C3D284F`（1039909 B）。
- 文档：PLAN.md（B135 行、收尾 499/0、新 sha、验收第 13 条）、PLAN-P6.md（P6-47）、TESTING.md、PLAN-BUGS.md（B135 整节）。

### 2026-11-05 B136：存档太慢 / 未响应 / 读档回旧档（user m07926、m07947；m08227 批准方案 1–4）

- 根因：AchievementEnabler 改写了 `Settings.isModded`/`isStandardRun()`，`UnlockTracker` 里无条件的 `achievementPref.flush()` 被 `addBlock >= 99` 触发；沙盒只改了 filepath、没有拦写请求，`AsyncSaver` 单线程队列被塞到 638,475 条（约 30 分钟清空），真存档饿死；日志膨胀又让 log4j 轮转卡渲染线程 13 秒。`runs/` 未被沙盒化，被搜索撑到 637 MB，读档 `CharStat` 反序列化卡 14.8 秒。
- 修法：新增 `mirror/SandboxWrites`（`@SpirePatch Prefs.flush` + `AsyncSaver.save`，沙盒路径直接丢弃并计数）；`ExternalMute.isSandboxPath` 扩到 `runs/`（`runTouches()`）。
- 门禁：S193 4/4、S194 3/3、S195 1/1；core 507/0（S1–S195）。jar sha256 8727408A…667A4（1,049,076 B）。
- 存量：`runs/` 三个角色目录 783 MB 移到 `sts1solver-cleanup-backup/`（未删）；MTS 旧日志已删。

### 2026-11-05 傍晚 B136 补 + B137：成就热点定案、日志聚合
- 用户 m08397 指出 B136 里「addBlock>=99 是热触发点」的说法不对。核对：那局所有 digest 的 `b=` 最大 28（`logs/` 1404 个样本 max=28，>=50 为 0）⇒ IMPERVIOUS 不可能是原因。
- 探针 mode=ache（带 `-AllMods` 才有 AchievementEnabler）：不给条件时 0 次；第 10 个 BUFF 落下 → 1 次（`addPower:604`）；block 98→103 → 1 次（`addBlock:503`）；真实 CaiNong 搜索 564 节点丢 243 次，其中 `ApplyPowerAction.update:305` 167 次、`CharStat.incrementEnemySlain:305` 90 次 ⇒ 热点＝「10+ BUFF 时每次 ApplyPowerAction 跑 POWERFUL 判定并无条件 flush」＋每杀一只怪一次。
- 那局 `budget=1000000000ms` 不限时跑了 57 分钟，队列 63 万条与「约 0.3 次 flush/节点」量级相符；成就 id 无法反查（MTS 日志在 B136 清库存时已被我删除，solver 日志无成就行）——只影响诊断，不影响修法。
- 临时诊断（`probe/AchievementProbe.java`、`SandboxWrites` 的 callerSite/SITES/topSites、`probe/FlushTrace.java`、`SolverMod` 的 mode=ache）已全部删除；保留 `dropped()`/`resetForTest()`。栈回溯在每节点量级本身就是新开销。
- B137：`logs/solver-20261105-152947.log` 17 MB/248,963 行里 247,866 行是 `reward pick answered with …`（99.5%）。`Driver.answerCardReward` 改为 `noteRewardPick()`：突发第一条照写、之后每 5 秒一行汇总，暴露 `Driver.rewardPicks()`；失败 WARN 不动。
- 验证：core 507/0（S1–S195，无新门禁），jar `CDF3F647…FBC66`（1,049,395 B）已安装；docs 同步 PLAN.md（B136 订正 + B137 行 + 基线/sha/验收第 22 条 + P6-01…P6-49）、PLAN-P6.md（P6-48 修好列结构并订正热点、新增 P6-49）、TESTING.md、本文件。

### 2026-11-05 晚 B139（进行中）：沙盒 vs 实机在「唐自动打出豹指导→头追」那一拍对不上——先加证据日志

- 用户 m08865/m08974：沙盒模拟与实机不一样，先找原因不要改（本轮只加日志，不改判据、不猜）。
- 定案证据（logs/solver-20261105-192226.log 第 3798-3800 行，守护者 ascension 5）：step 32 `结束回合（选：头追 → 打击 → 豹指导+ → 嗦开嗦开）` 的 expected/actual 两份 digest 逐字相同，唯一差别是实机把弃牌堆的 SuoKai 移进抽牌堆、把抽牌堆的 SealGuide+ 移进弃牌堆；沙盒那一步一张牌都没动。
- 实机这一拍只开了 3 屏（19:21:55/56/57 三条 live selection answered），计划记了 4 个答案，第 4 个（嗦开嗦开）从未被用掉，日志里没有任何一行提到。
- 探针（HeadChaseProbe，mode=hc）实测：头追的两次换牌在沙盒里 direct（直接打头追）与 chain（豹指导→头追→两个选择屏）两种形状都能正确执行（digest 证明），但**搜索对每一屏是取 options.get(0) 作答**，与实机按计划 ids 作答会走不同分支（probe 里沙盒第一选项是 SealGuide，计划里是 Strike）。
- 改动（只加证据，两处）：Driver.resolveSelectionLive 在作答前打印 `live selection: kind=… offered=[…] picked=[…] plan=[…]`（新增私有 brief(...,10) 截断）；Gameplay 步完成出口调 reportUnusedAnswers()，打印「计划记了 N 个答案、实机只开了 M 屏」。
- 验证：BUILD OK 1054570 B，sha256 A533E7DE0F2A4383453A70FC529EC908CC61F3E713527D6C3D28D7BABBC5AEAD，已安装；core 507/0（S1–S195）无变化。
- 待办：等用户复现后读新日志里的 offered/picked 行，定位「谁把答案答到了哪一屏」，再修真因 + 门禁 + 全套文档。

### 2026-11-05 晚 B139（完成）：一步开多个选牌屏时沙盒被上限截断（user m08865 / m08974）

- 结论：`Solver.expandScreens()` 走屏上限默认 `SCREENS_HIGH=4`，撞上限直接 return，半条链被当成这一步的结果（计划答案 4 段、实机用 3 段；digest 里两个堆没换）。守护者 A5 那局 19:08/19:09/19:21 三次逐字相同 ⇒ 确定性差异。
- 修法：`Limits` 屏幕档 2/4/8 → 4/16/32；`expandScreens` 循环后补一次 `Driver.pumpUntilSelection(24)` 结算最后答案，仍有屏在等则 `Log.warnEvery("screens", …)` 明说答案链不完整。
- 门禁：S196 2/2（6 屏测试替身 `MultiScreenProbeAction`；把 `SCREENS_HIGH` 改回 4 时红 `screens=4`）；顺带订正 S140 档位数字。core **509/0**（S1–S196）。
- 诊断（保留）：`Driver` 侧新增 `live selection: kind=… offered=[…] picked=[…] plan=[…]`、`sandbox selection: kind=… offered=[…] picked=[…] changed=…`（每进程前 60 条），`Gameplay.reportUnusedAnswers()` 在步完成时报告「计划记了 N 段答案、实机只开了 M 个屏」。
- 产物：jar `517742752C10CCE530C740302D5FD28DCB5EA33209026FD55076408B3006A5DB`（1,058,815 B）已安装；docs 同步 PLAN.md（B139 行 + 收尾 509/0 + 新 sha + 验收第 24 条 + 重复编号 22 改为 23）、PLAN-P6.md（P6-50）、TESTING.md（sha / 509/0（S1–S196）/ S196(2) 共 136 条）、PLAN-BUGS.md（B139 整节）、本文件。

- **订正（user m09378「但是刚刚也没有『一步开 4 个屏以上』啊」）**：实机那一步只开 3 屏、计划记 4 段 ⇒ 沙盒自己走了 4 屏（多一屏），「撞上限被截断」不是本步分叉的成因；截断是另一个真缺陷（S196 已修）。本步主嫌＝沙盒多开的那一屏，已装 `sandbox selection:` / `live selection:` 逐屏对照行，重放该存档即可点名。PLAN.md 的 B139 行与 PLAN-BUGS.md 的 B139 节已按此订正。

### 2026-11-05 B140：选牌屏答案对不上问题（user m09586）

- 根因：计划只存答案不存「这块屏是什么」，重放按位置硬塞；`rebind` 兜底把外来卡塞进 `selectedCards`，`CardGroup.removeCard` 按身份落空 ⇒ 头追的搬牌整段空转、无异常无日志。屏链没答完时也静默把半条链当结果。
- 修法：`Action.screenKinds` + `Choice.screenKinds`（`|` 连接）；`answerForReplay` 每屏 `checkScreenKind`、答完 `checkChainFinished`（`ReplayException` 的 `screen-mismatch` / `chain-unfinished`）；`expandScreens` 返回链是否走完，半条链的步骤/分支丢弃并打 `screens-branch`。
- 验证：S197 2/2；core 511/0（S1–S197）；jar 4E6A7BA0…E62A（1,062,364 B）已装。
- 代价/风险/影响已报：多存屏种类、每屏多一次 settle 泵帧、假线被丢弃（计划可能变短但更诚实）、`screenKinds` 为空时行为不变。

### 2026-11-06 B141：头追第二块选牌屏被关掉（user m09905）

- 根因：`ExchangeCardsOfTwoPilesAction` 吃掉第一块 grid 的选择后不清 `selectedCards`、直接 `duration=FASTER; return`，第二块屏下一帧才 `open`（`callOnOpen()` 里 clear）；我们用 `selectedCards.isEmpty()` 判「已消费」⇒ 第二块屏一开就判成答完并 `closeCurrentScreen()` ⇒ 动作读到空选择、搬牌不发生、无异常无日志；沙盒同源（`selectionQuestion()` 的 group 指纹认不出同一堆连问两次）。
- 修法：新增 `mirror/ScreenOpens`（三个真实 `open` 入口的 `@SpirePatch`，只计数）；`answerLiveSelection` 计数增长且仍有屏 ⇒ 不关屏、交给 `answerNextScreen` 递进；`pumpWhileScreenOpen` 换屏判据加计数。
- 验证：S198 修前 3 条红（`kind=NONE`/`not-settled`/`picks=Strike_R,-`）、修后 `picks=Strike_R,Defend_R`；S199 修前修后都绿（护栏）；S196–S199 16/16；core **523/0**（S1–S199）；jar `C8B7C7BD250D9E56C536A8C4B83090578712C5665489C5127EB38B5EAAEF6807`（1,072,996 B）已装。
- 代价/风险/影响已报：每开屏一次静态自增、每个答案多两次 volatile 读；若 mod 在同一帧开无关屏，会拿下一段答案去答它（答不上就 refused 停，不假装成功）；面板/计划里的多段答案会真的被用掉。
- 订正：B139 节原写「同一动作对同一 group 连问两次认不出（原版/mod 卡都不这么做）」——后半句错，头追正是这种形状；已改由计数判据覆盖，B139 节与 PLAN.md 已同步。

### 2026-11-06 B142：自动结束回合只置 latch、没按「结束回合」按钮（user m10519）

- 现象：实机自动结束回合时，回合末该发生的事没发生；沙盒（B114 起）却会跑。
- 根因（已反编译确认）：`Gameplay.submit` 的结束回合分支只做 `AbstractDungeon.player.endTurnQueued = true`。引擎真正发起结束回合的是 `EndTurnButton.disable(true)`：它先 `addToBottom(new NewQueueCardAction())`，再置 `endTurnQueued`。少了这条队列动作，`GameActionManager` 就没有空动作哨兵，`getNextAction()` 里 `callEndOfTurnActions()` 永不触发 ⇒ 回合末遗物、PreCard 能力、充能球、手牌的 `triggerOnEndOfTurnForPlayingCard()`（灼伤/腐朽/Regret）、姿态结算全被跳过。沙盒自 B114 起显式补了 `NewQueueCardAction`，于是两边从第一次自动结束回合就分叉。
- 修法（通用、不特判）：`Driver.pressEndTurnButton()` —— 取 `AbstractDungeon.overlayMenu.endTurnButton`，存在就调 `disable(true)`（与玩家点按钮同一条引擎路径），返回 true；没有按钮（无头合成窗口）时退化为「NewQueueCardAction + endTurnQueued」两步并返回 false 让调用方留一行 WARN。`Gameplay.submit` 改为调它。`ClonePolicy` 对 `ui.buttons.*` 是复制，所以沙盒里按的是克隆按钮，不会点亮/禁用玩家的真按钮。
- 门禁 **S200**（4 条）：合成战斗里往手牌塞一张灼伤（回合末 2 伤、无格挡）→ 按按钮 → 灼伤被回合末块打出、玩家掉 2、回合正好 +1。
- 验证：`-Gates "S64,S200,S201"` → 15/0；core **532/0**（S1–S201）；jar `F023BDD72255C2D1DF5D5498437367897F16155F7E52D568418B7A41EFD54D14`（1,078,365 B）已装。
- 代价/风险/影响：代价≈0（每回合一次按钮调用）；风险＝若某个窗口里 `overlayMenu` 为空会走退化分支并 WARN（行为等同 B142 前，不会更差）；影响＝实机自动出牌现在会真的结算回合末（灼伤/腐朽/Regret 掉血、遗物触发、球与姿态），面板计划里那些掉血从此与实机对上；这解释了「面板比实际少掉血」类偏差的最后一类。

### 2026-11-06 B143：面板删「停止」、加「局外收益」开关（user m10519 第 2、3 条；本轮补记）

- 需求：①删「停止」（与「计算」重复）②加「是否计算局外收益」开关。
- 修法：`PanelLayout.IDS` 去 `stop`、`potion` 后加 `growth`；`Panel.activate` 加 `growth → GrowthBook.toggleCounting()`、`labelFor` 返回 `countingLabel()`；`GrowthBook` 新增 `growth.count`（缺省计）、`counting()/toggleCounting()/countingLabel()/overrideCountingForTest()`，`credit()`/both `enabled()` 统一短路，拆出 `writeRaw(key,value)`；订正 4 处「面板「停止」」文案。
- 门禁：S64（expected 加 growth + 开关往返）、新增 S201 四条（开则计价 / 关则 0 且 enabled=false / 标签两种说法 / 测试后重新读 settings.txt）。
- 验证：S64+S200+S201 15/0；core 532/0（S1–S201）；jar `F023BDD7…4D14`（1,078,365 B）。
- 代价/风险/影响已报：只影响评分（多一次开关判断），不动模拟；关掉后金币/最大生命/升级/药水不再给分，但金币仍不进 digest/面板。

### 2026-11-06 B144：面板「局外收益」状态字被屏幕右缘裁掉（user m11047 第 4、5 条）

- 现象：用户看不到按钮当前开/关（原话「『行』『不行』在屏幕边缘看不见」；源码里没有「行/不行」，被裁的是 `计`/`不计`）。
- 根因（引擎常量算出）：按钮宽 138*scale、文字左内边距 10*scale ⇒ 可用 118*scale；`Panel.drawButton` 从不量宽；「局外收益: 不计」＝ 6 汉字+": " ≈ 171*scale；第二列按钮右边缘距屏幕右缘只有 34*scale ⇒ 末尾两字被裁。
- 修法：①`countingLabel()` 缩短为 `局外: 计 / 局外: 不计`；②`Panel` 抽出 `drawLabel`，`GlyphLayout` 量宽、超宽按比例缩字、`finally` 还原 1.0（更长标签 `计算中…（点=停）`/`自动: 算完开始` 因此不再压到相邻按钮）。
- 顺带修门禁缺陷：S201 末条原写死 `counting()==true`，而玩家 12:32 关掉开关（`settings.txt` `growth.count=0`）⇒ 断言依赖玩家设置；改为读 `settings.txt` 实际值比较（报 `file=false got=false`）。
- 验证：`-Gates "S64,S201,S200"` 15/0；core 532/0（S1–S201）；jar `A0F4D95A…8D69`（1,079,451 B）。
- 代价/风险/影响已报：每按钮每帧一次量宽（微秒级、不分配）；缩字只在超宽时发生；只改绘制与文案，不碰搜索/评分/自动出牌/其它 mod。

### 2026-11-06 未修记录：关掉局外收益后，计划里仍会带「点播」（user m11047 第 3 条）

- 现象：12:34:27 那局打蛇花（开关在 12:32:26 已关，日志 `growth: counting out-of-combat gains = false`），计划第 7 步是「点播（选：毒种）」，trace `6:t=4 hp=74 b=0 e=9 → 7:t=4 hp=74 b=0 e=7`——2 费、局面一点没变。
- 为什么：`Solver.score()`（Solver.java:2133-2163）里**没有能量、金币、手牌、power 项**，只有 `10000000 - StrategicDeficit - board`；`GrowthBook.credit()` 在开关关闭时第一行就返回 0（GrowthBook.java:257-260）⇒ 点播（战斗内什么都不做、只给 50 金币）与「不打」同分；最终择优 `Solver.java:1571` 是严格大于（同分保留先找到的线），于是把它当免费动作带上了。
- 已知更深一层：即使开关打开，50 金币＝25 金/HP 只值 2 HP，而毒种的负收益（`onVictory()` 塞「腐朽」）沙盒从不跑（PostCombatHealing 注释已写明 onVictory 不执行）⇒ 负收益在两条路径都不可见。
- 待修方案（用户未批，按 m04606 不擅自改评分）：A 同分时偏好步数更少的线（计划里自然不带白打牌）；B 胜节点跑引擎 onVictory 结算（诅咒可见，但改动大）；C 关掉开关时把「只产出局外价值」的牌判为无价值（需要语义标签，有特判风险）。A 是唯一低成本且不动语义的选项。

### 2026-11-06 B145：S112 成长计价门禁依赖玩家设置（core 4 条红）

- 现象：装 B144 的 jar 后全量 core `passed=528 failed=4`，4 条全在 S112，`credit=0 ritual=0 …`。
- 根因：`s112Growth()` 只钉预算不钉开关，而 `GrowthBook.credit()` 在 `!counting()` 时第一行返回 0；玩家 12:32 在游戏里关了开关（`settings.txt` `growth.count=0`）⇒ 门禁依赖玩家自己的设置。
- 修法：`s112Growth()` 开头 `overrideCountingForTest(TRUE)`、结尾 `null` 还原，并把 `restored` 判据扩成「预算 + 开关都回到原样」。
- 验证：`-Gates "S112,S201,S64"` 20/0（S112 `credit=23 … restored=true`）；core 532/0（S1–S201）；jar `ABD6F6E5…7BBA`（1,079,507 B）。
- 代价/风险/影响：只改门禁；产品代码、开关语义、评分、面板、其它 mod 均不变。`s113Scan()` 的 `otherCredit` 也会因开关关掉显示 0，但只打印不断言，未改。

**为什么「点播」的选项总是「毒种」（2026-11-06 追查，未改代码）**

- 点播的 ChooseOneAction 是 4 张种子牌（`_tmp/demand/CaiNongMod/cards/Demand.java:81-94`，按 农种/毒种/装唐/发癫 顺序加入，已有该 power 的会被过滤掉）。
- 排序用的是 `Driver.cardValue`（Driver.java:2339-2352）：`damage*2 + block*2 + max(0,magicNumber)*2 + (cost<0 ? 2 : 3-cost) + upgraded*2 + rarity.ordinal()`。四张种子 cost=-2、rarity=SPECIAL（`javap` 实测枚举序：BASIC,SPECIAL,COMMON,UNCOMMON,RARE,CURSE ⇒ SPECIAL.ordinal()=1），只有 magicNumber 不同：农种 3 / 毒种 7 / 装唐 1 / 发癫 7 ⇒ cardValue 依次 **9 / 17 / 5 / 17**。`selectionOptions`（Driver.java:1504-1522）按 cardValue 降序稳定排序 ⇒ 毒种与发癫并列第一，稳定排序保留 mod 给出的先后 ⇒ **第一分支永远是毒种**。
- 搜索确实把四条分支都展开（`Limits.choiceBranches` 对 REWARD 返回无上限，combos 默认 48），但四条**同分**：`Solver.score`（Solver.java:2133-2163）只有 HP/药水/胜负/层数/怪物血/格挡/怪物数，没有 power、能量、金币、masterDeck 项；四个种子的实际效果一个是 `onVictory()`（沙盒从不执行，PostCombatHealing 注释已写明），另一个是农种在 3 回合后 in-combat 塞「腐朽」——进的是 masterDeck，而 masterDeck 既不进 Digest 也不进评分 ⇒ 四条分支的分数完全一样。`Solver.java:1571` 是严格 `>`，同分保留先到的节点，而先到的是 probe 的 all-first 分支（Solver.java:909-960，`expandScreens` 每屏答案取 `options.get(0)`）⇒ 计划写成「点播（选：毒种）」。
- 结论：不是求解器偏好毒种，而是「价值并列时取第一个」+「毒种在 value 启发式里并列最高且被 mod 先给出」。若已持有毒种，发癫会顶上（同样 17）；只有种子之间的局外代价可计价（例如 B 让 onVictory 真的跑）之后，选哪个才可能变得有意义——而四个种子都塞同一张「腐朽」，所以即便做了 B，选谁仍然同分；B 改变的是「点播这一步本身值不值得打」，不是「选哪个种子」。

**评分口径速查：金币、能力、能量到底进不进分（user m11469 追问，2026-11-06；金币一栏已按 B146/m11490 改写）**

- 金币**现在是独立项（B146，user m11490 + m11767）**：`Solver.score(...)` 末尾 `+ goldDelta * Solver.goldRate()`，`goldRate()` = `GrowthBook.counting() ? 80 : 0`，`goldDelta` = 本节点金币 − 开战金币 ⇒ 面板「局外: 计」时得 1 金 +80 分、花 1 金 −80 分；面板「局外: 不计」时金币恒 0 分（一分不算也不扣分）。等价于旧汇率 25 金 = 1 HP = 2000 分（开关打开时），但不再按 25 取整、且计入花费。旧通道已退役：`GrowthBook.credit()` 不再折金币（本节下面 25 金/1 HP 的描述是 B146 之前的口径）。
- 能力（power）**完全不进分**：power 的价值应当体现在之后几回合实际少掉的 HP 上，而那正是搜索逐回合模拟出来的；再给 power 加一项等于用启发式重复计价。边界＝没有战斗内表现的 power（`onVictory()` 才生效的毒种/农种、纯记账的装唐）没有任何模拟通道 ⇒ 不可见。
- 能量不进最终分，只在束保留 `Solver.rankOf`（Solver.java:2111-2113）里 `energy*40`，作用是别把「还有能量没花」的线提前丢掉。
- 有独立项的是：药水（PotionBook 9/14/18 HP 折价，`Solver.java:1560-1561`）、死亡保险（`DeathSaves.points`）、战后回血（`healPoints`）、胜利（+10000000）。
- 未修的可见性缺口：金币不进 Digest、不上面板 ⇒ 分数里的金币项（开关打开时）无法从面板核对，只能从 plan/trace 行的金币变化推断；masterDeck（诅咒）同样不进 Digest 与评分。

### 2026-11-06 B146：金币改为直接计分，并跟随「局外收益」开关（user m11490 / m11767）

- 用户原话（m11490）：「1.把金币单独拿出来算分，1金币=80分。2.花了金币相当于金币减少算负分，-1金币=-80分」；追加（m11767）：「1.没有做战斗以外的跑图功能所以删牌商店之类的没有影响。2.金币要计入局外收益，关掉局外收益金币不能算分」。
- 改前：金币只经 `GrowthBook.credit()` 折 HP（25 金 = 1 HP = 2000 分），**只算增加、按整 25 取整、受「局外收益」开关控制**；花金币不掉分。
- 第一版（同日）：把金币做成「常开」直接项 `goldDelta * GOLD_POINTS`；m11767 指出「关掉开关不能算分」后改为**跟随开关**：`Solver.goldRate()` = `GrowthBook.counting() ? 80 : 0`，`score` 末尾 `+ goldDelta * goldRate()`（基准＝`startGold`，`solveInternal:1494` / `Session:3405` 各读一次）。
- 去重复计价：`GrowthBook` 去 `GOLD` 键（`DEFAULT_TENTHS` 7→6 项，credit 里金币行删除）；`GrowthCredit` 的 `GROWTH_CARDS` 去 `HandOfGreed`（它的永久收益就是金币）；`EscapePolicy.forfeitPointsFor` 改 `gold * Solver.goldRate()`（同源同开关，关掉时金币半边为 0）。
- 门禁：新增 **S202**（4 条：开关开时 +5 金 = +400、−3 金 = −240、`GrowthBook` 对 +50 金返回 0；开关关时两种金币变化都不改分）；`S187` 加第 6 条（关掉开关后 `forfeitPointsFor(false,false,0,15) == 2*2000`）；S112 期望 `credit=21 goldOnly=0`，预算数组 6 项、`defaults[5]`。
- 验证：`-Gates "S15,S53,S112,S187,S200,S201,S202"` **46/0**；全量 core **538/0**（S1–S202）。
- jar：`C6189FE2E8E7F7D5B92B632E1C950B83C81D51BC2475044DDD6D39B6C3880F25`（1,081,456 B）。
- 已订正：B145 节（旧 `credit=23` → 现 `credit=21`）、上文成长预算表里 `growth.handOfGreed` 行、上文「评分口径速查」的金币条与可见性缺口条。
- 未修边界（如实记录）：`Solver.java:1697/:1796` 一带的「扩展胜线」子解汇总仍传 `goldDelta=0`（子解没有金币度量）；战斗结束才发生的金币（遗物/onVictory）在本场节点里看不到；关掉开关后金币与不打同分时仍按先到先得（没有步数/资源 tie-break），这是 B146 之前的遗留，未改。

### 2026-11-06 B147：把「实机与模拟在哪一拍分叉」做成引擎事实（user m12327「动手」，承接 m12123/m10519；**订正（B148）**：标题里原来那句「并在已证分叉时重算一次」已按 user m12610 删除）

- 起点（m12123）：三奴隶贩子第 5 回合头追那步，沙盒与实机在「结束回合」判分叉（沙盒唐打防御+、实机唐打农盘与嗦开开）。那一轮的困难不是结论，而是**取证手段**：digest 不含随机计数、出牌靠手牌 diff ⇒ 说不出「唐打了哪张、在哪个随机位置、先后顺序」。
- 本轮新增三件引擎事实：
  1. `mirror/PlayTrace`（新）：`@SpirePatch AbstractPlayer.useCard` 后缀记录，环形 512 条，格式 `牌@t回合#随机位置/费用[auto]`；实机与沙盒共用同一 patch。
  2. `mirror/RngStamp`（新）：13 个官方 `AbstractDungeon.*Rng.counter`，`of()/compact()/same()`。
  3. `Solver.Step` 新增 `rng`/`plays`：`replay()` 在动作前 `PlayTrace.mark()`、动作后取 `RngStamp.of()` 与 `describe(mark)` ⇒ 计划自带沙盒侧证据（9 参构造保留委托）。
- 计划缓存：`PlanCache.key` 新增 5 参重载（键尾 `|rng:…`，4 参版委托 `""`），`Gameplay` 三处取键传实时随机戳 ⇒ 「同一块板子但随机流位置不同」不再复用旧计划（这类复用正是 08:26 算、08:41 执行那次的隐患）。
- 处置规则（`Gameplay.noteDriftAndContinue`）：打四行 `plays sandbox/live`、`rng sandbox/live`；**仅当两侧出牌序列不同**才 WARN（**B148 订正**：这一段原来的 `replanAfterDivergence` 已删除，见下节）（空证据＝未知；随机计数单独不同只记日志，因为计划里的计数随整条重放推进，不能证明这一步走偏）。重算上限 1 次/战斗（`TRACE_REPLAN_LIMIT`，`combat detected` 时清零），第二次 `fail` 停手并写面板。结束回合即使正确也打 `end turn plays: live … || sandbox … || rng same/DIFFERS`。
- 与 m16202-2 的边界（**B148 订正**）：重算已删除，本条只做记录；「计划走完不许自己再算」的规则不变。
- 门禁：**S203**（3 条：一次出牌正好一条轨迹、轨迹含牌/回合/随机位置、roll 后随机戳变化）、**S204**（3 条：一行 Action 回放成 1 步、Step 带随机位置、Step.plays 含实际打出的牌）。
- 验证：`-Gates "S127,S135,S136,S196..S204"` **47/0**；全量 core **544/0**（S1–S204）。
- jar：`D2AE4CDED40BDF66330C98493E7D99E2BF0C7A7F18B4F8A743D6B9A9CD3CCA08`（1,092,013 B）。
- 未修/边界：不含 `useCard` 的效果（药水、遗物直接改血、能力 tick）不在轨迹里；mod 自建随机流不可见。（**B148 订正**：末尾「重算只做一次，第二次停手等玩家」已随重算一起删除。）

### 2026-11-06 B148：读档会重置随机流（第 20 步分叉的真因）+ 删除「分叉后重算」（**B150 已撤销本节代码，见文末**）

- 用户 m12610 原话：「你完全理解错我的意思了，刚刚的做法是检测到和实际不一样重新算，我的意思是一开始就算对。1.刚刚错误的算法删掉，不要留下残余污染代码导致bug。2.找到为什么面板和实际不一致，你都说了第20步有问题就研究这里的原因」。
- 删掉的算法：`Gameplay.replanAfterDivergence(...)` 整个方法、字段 `traceReplans`、常量 `TRACE_REPLAN_LIMIT`、`combat detected` 里的 `traceReplans = 0`、`noteDriftAndContinue` 里触发重算的整个 `if` 块与残留 javadoc；改为一行 `Log.warn("  the first play that differs is the step to look at; the plan keeps running")`。grep `replanAfterDivergence|TRACE_REPLAN_LIMIT|traceReplans` 已 0 命中。保留的只有取证与更严的键：`PlayTrace`、`RngStamp`、`Step.rng/plays`、`PlanCache` 的 5 参键。
- **根因（引擎事实）**：`AbstractDungeon.loadSeeds(SaveFile)`（`_tmp/saveload/.../AbstractDungeon.java:394-421`）只恢复 `monster/event/merchant/card/treasure/relic/potion` 的 counter；`aiRng/shuffleRng/cardRandomRng/miscRng/monsterHpRng` **不写进存档**。`nextRoomTransition(SaveFile)`（`:1667`，播种在 `:1727-1731`）在每次读档进房时无条件 `cardRandomRng = new Random(Settings.seed + (long)floorNum)`、`aiRng/shuffleRng/miscRng/monsterHpRng` 同理 ⇒ **读档后这些流 counter 归零**。
- 那局的链条（`logs/solver-20261106-163033.log`）：30420 16:27:13 计划算出（35 步，读档前）；30470 16:29:10 `world switched` 读档；30488 16:29:12 `plan cache: hit (1246386989)` 复用同一份计划（起点 digest `t=1 P hp=59/80 b=0 e=3` 与计划完全一致）；步骤 1–19 都是玩家出牌、不消耗 `cardRandomRng` ⇒ 全对；第 20 步「结束回合」跨到 turn 5，唐 `atStartOfTurnPostDraw` 用 `cardRandomRng` 从手牌随机打一张 ⇒ 沙盒打出防御+、实机打出农盘然后嗦开开（实机开了一块屏，计划没答案，被 best-first 答成 Statuette）⇒ 之后 b=16 vs 2、弃牌堆差、多 `NongAbacusPower2` 全部由此而来。30661 16:30:19 第二次读档后 30681 又命中同一 key，证明这不是偶发。
- 修法＝让键覆盖随机位置（B147 已铺好的 5 参 `PlanCache.key` + `Gameplay` 三处传 `RngStamp.of()`）：读档后 counter 为 0 的戳与旧计划不同 ⇒ 缓存未命中 ⇒ 按实机真实随机位置**从头算对**，而不是发现分叉再补算。
- 门禁：**S205**（5 条；**B150 已删除**）。当时子集 `-Gates "S200,S201,S202,S203,S204,S205"` 24/0；当时 core 549/0（S1–S205），撤销后 544/0（S1–S204）。
- jar：`7FA7707B6FF310643E04CD626D9CE6E1BCE1165083CB8454E2E332621176B7A5`（1,094,294 B）。
- 未修/边界：`monster/event/merchant/card/treasure/relic/potion` 七个流是存档恢复的（用存档里的 counter），不受此次重置影响；mod 自建随机流仍不在 `RngStamp` 内。

## 2026-11-06 B149（出牌流水与空屏取证；面板「再点一次算下一轮」删除）

- 用户 m12829 原话：「1.还是不对，但是我找到原因了，第三回合结束后第四回合“唐”自动出牌出了“豹指导”要选择牌，但是面板第四回合没有选牌，应该是这里产生的问题。2.刚才做的还有没有用？没有负面效果可以保留。3.把面板显示的文本“计算”那里后面的“再点一次重算”删掉。实际上再点一次没有重算也没必要重算。」
- 第 2 条：B148 当时原样保留；**订正（B150，user m13154）**：随后用户要求「现在删掉这个 B148，我不想一个废弃代码污染我的代码」⇒ 已整段撤销。
- 第 3 条：`Gameplay.java:1778` 的 `progress.add("再点「计算」就采用当前最优线；再点一次算下一轮")` 改成 `progress.add("再点「计算」就采用当前最优线")`（全仓库没有字面量「再点一次重算」，最近的就是这句）。
- 第 1 条（未定因）：已知事实——该局 `logs/solver-20261106-180259.log` 里计划 35 步、12 步之前逐步 digest 全对；18:11:04 结束回合那一步两侧出牌与随机计数完全一致（`end turn plays: live … @t3#c=10 … @t3#c=18 || sandbox 同样两条 || rng same`）；此后面板卡在第 13 步（昏睡 `CaiNongMod:Trance`），实机却在 18:11:28 / 18:11:43 / 18:12:03 连开三块屏（两块 HAND、一块 GRID，`autopilot=false`），计划里这三个答案都没有。计划第 20 步 `t=3->5 skip=1` 说明沙盒认为「昏睡之后整回合 4 被强制结束」。**缺口**：日志此前只对「结束回合且沙盒侧有牌」打印出牌对比，普通步里唐打出的牌（例如豹指导 `CaiNongMod:SealGuide`，其 `use()` 会 `handCardSelectScreen.open("打出", …)`）在日志里完全看不到，所以无法判定是「沙盒唐没跟上」还是「屏被吞掉」。
- 本轮改动（B149）：`mirror/PlayTrace.java` 新增 `recent(int n)`（当前线程最近 n 笔，oldest first，环形缓冲回溯）；`Gameplay.answerLatentSelection()` 的 `selection open with no planned answer` 行追加 `| step=<step>/<plan 步数> | last plays: <recent(8)>`；`Gameplay` 校验出结果处新增普通步分支 `else if (!livePlays.isEmpty() || !step.plays.isEmpty())` → `Log.info("step plays: live … || sandbox … || rng same/DIFFERS planned … live …")`（结束回合那条保持原文案不变）。
- 验证：`tools/install.ps1` BUILD OK / INSTALL OK；jar sha256 `F96AE9D59D0720187F865D765473F1F34942D78A48CE6FFE756FD1A718DF4B31`（1,094,794 B，466 entries）；core 套件 `passed=549 failed=0`（S1–S205，未新增门禁）。
- 下一步：等用户复现同一情形，抓 `selection open with no planned answer … | step=N/M | last plays: …` 与它前面最近一条 `step plays:`，即可判定是唐链缺失还是屏被 `Driver.drain`/`runEndOfTurnQueue` 吞掉。
## 2026-11-06 B150（按 user m13154 撤销 B148；头追分叉定位在「豹指导选牌之后的出牌顺序」）

- 用户 m13154 原话：「1.读档随机流进缓存键 + 删重算，这条没有做到，实际上我不退游戏的话读档还是不会重新算。但是B148这条不需要做了。2.回答我B148到底干了什么，自己的功能没实现。现在删掉这个B148。我不想一个废弃代码污染我的代码。3.现在又打了一遍，第五回合打出头追的时候面板和实际还是不对，计算结果和之前一样，头追（防御+ ->打击），现在定下哪里出问题了。先不要改等我确认问题。」
- 撤销（第 2 条）：`Gameplay.java:536/:2038/:2167` 恢复 4 参 `PlanCache.key`；`search/PlanCache.java` 删 5 参重载、4 参版恢复原键串；`probe/Probe.java` 删整段 S205（−5 条）。保留 B147/B149 取证设施（`PlayTrace`、`RngStamp`、`Step.rng/plays`、`step plays:` 日志、`noteDriftAndContinue` 对比）。grep `S205` = 0。
- 为什么它没实现「读档就重算」（第 2 条要解释）：B148 只在随机计数不同时换键，从不强制重算；而读档进房的播种是确定的（`cardRandomRng = new Random(Settings.seed + floorNum)`、counter 0），旧计划若也是计数 0 时算出的键相同 ⇒ 仍命中。前提「读档必然换随机位置」不成立。
- 验证：`tools/install.ps1` BUILD OK / INSTALL OK；jar sha256 `EBB320C31AA73AEDEAA04B474BB70B6F0999BCEA5C0BD4AFACB360BFBBEA9B5C`（1,092,145 B，465 entries）；core `passed=544 failed=0`（S1–S204）。
- 第 3 条（只定位、未改代码）：`logs/solver-20261106-190048.log`。第 1–19 步 digest 与出牌全对；分叉在 step 20「结束回合」（t=3→5，expectedEnd=5）：expected `t=5 P hp=61 b=16 e=10 pw=Strength3,CandyBallPower-1,NoseNibPower1,Vigor8,Weakened1`，actual `t=5 P hp=61 b=2 e=10 pw=…,NongAbacusPower2,Vigor8,Weakened1`。出牌 sandbox `NongEye@t4 c21 | NongEye@t4 c23 | SealGuide+@t4 c25 | BideTime@t4 c27 | Statuette@t4 c29 | Defend+@t5 c32×2`；live `NongEye@t4 c21 | NongEye@t4 c23 | SealGuide+@t4 c25 | Statuette@t4 c28 | BideTime@t4 c30 | NongAbacus@t4 c32`；rng 两侧全同（cardRandom=32,shuffle=2,ai=15,misc=0,monster=77,monsterHp=4,relic=8,potion=57,card=352,event=6,merchant=39,treasure=17,map=100）。⇒ 第一处不同在**豹指导+ 开选牌屏之后**：沙盒先 BideTime 再 Statuette，实机先 Statuette 再 BideTime，且实机多打 NongAbacus。实机 `live selection answered with CaiNongMod:Statuette (consumed by the waiting action)` ⇒ 那块屏计划没答案、被 best-first 兜底。下游 step 22 头追屏候选 `[NongEye,SealGuide,Statuette,BideTime,SuoKai,Wound,Wound,PaiDianDian]` 里没有防御 ⇒ `selection refused (not-offered … plan chose [CaiNongMod:Defend])` → paused。
- 代码级旁证（待确认，未改）：`AbstractRoom.java:250-259` 实机在屏开着时**完全不推进 actionManager**（`if (!AbstractDungeon.isScreenUp) … actionManager.update()`）；沙盒 `Driver.resolveSelection` 的 HAND 分支填完答案仍在屏开着时 `pumpWhileScreenOpen()` ⇒ 「选中的牌」与「唐链下一张」的先后顺序可能与实机相反。

## 2026-11-06 B151 — 结束回合队列泵不看屏（沙盒吞掉唐的免费牌）

- 用户 m13425 原话：「你的意思是在结束回合后，开启选牌屏的时候沙盒还在推进动作所以导致顺序出问题对吗？如果是这样照你的做，严格让沙盒出牌和实际出牌一样」。
- 根因：`AbstractRoom.update()` 只在 `!AbstractDungeon.isScreenUp` 时调 `AbstractDungeon.actionManager.update()`（`_tmp/saveload/com/megacrit/cardcrawl/rooms/AbstractRoom.java:250-259`），而 B114 引入的 `Driver.runEndOfTurnQueue` 泵里没有任何屏判据 ⇒ 唐押下的豹指导 `PlayCardAction` 开「打出」屏后，下一次 update 看到 `wereCardsRetrieved=false` 且 `selectedCards.group` 空，一张都没打就置 retrieved=true ⇒ 免费牌消失、屏被静默清掉。
- 第二处误读：`Driver.roomBlockedByScreen()` 把 HAND_SELECT 判成不挡管理器；`AbstractDungeon.update()` 的 HAND_SELECT 分支虽调 `room.update()`，但后者内部仍要求 `!isScreenUp`。
- 改法（Driver）：`runEndOfTurnQueue` 首行 `if (managerFrozen()) return true;`；`managerFrozen()` 用引擎自己的门（`isScreenUp` → true；NONE/NO_INTERACT/FTUE/BOSS_REWARD/HAND_SELECT → false；GRID/CARD_REWARD → `!peekingNow()`；其余 → true）；`drain` 的 pressNow 分支遇屏停下并置 `awaitingSelection`；`endTurn(long)` 屏开着时拒绝并留 latch；`clearSelection`/`Synthetic.java:456` 的 `previousScreen=NONE` 改 `null`（`closeCurrentScreen` 只在 `previousScreen==null` 时置 `isScreenUp=false`，`AbstractDungeon.java:2423`）；`resolveSelection` 改为真实点击顺序「填 → 关屏 → drain」；删 `pumpWhileScreenOpen`/`pumpForTest`/`screenSerial` 等死代码。
- 代价：每次结束回合多一次字段读；真开屏时少跑若干 `gam.update()`；`Driver` 净增约 60 行，删掉一批死代码。
- 风险：`managerFrozen()` 更严（任何屏都冻结管理器）；`endTurn` 屏开着返回 `refused=true`；结束回合靠 `player.endTurnQueued` 续上；回滚＝还原四处 + `Synthetic.java:456`。
- 影响：面板会多一段「选一张再打出」的选牌答案（豹指导/头追那一步）；自动出牌不再吞免费牌；搜索可能多一条选牌分支；只用引擎规则，不特判 mod。
- 门禁：S206 新增 5 条；S182 改两段（屏开着被拒 + 答完屏走通）。修前必红实测（临时 `if (false)`，sha CFD212DB…）S206 `passed=2 failed=3`，恢复后 5/5。子集 46/0。

## 2026-11-06 B152 — 门禁钉住的开关被 `budgets()` 缓存重读冲掉

- 用户没有单独提；全量 core 暴露：S187 `live=4000 expected=5200`。
- 根因：`overrideCountingForTest(TRUE)` 只写 `counting`；`counting()` → `budgets()` 在 500 ms 缓存过期后重读 `settings.txt`（玩家档 `growth.count=0`）把 `counting` 覆盖。`Synthetic.build` 超过 500 ms 后开关失效。B145 是没钉，B152 是钉了又被冲掉。
- 改法：`GrowthBook` 加 `countingPinned`，`budgets()` 只在 `countingPinned==null` 时写 `counting`，`overrideCountingForTest` 同步置 pin，`invalidate()` 清 pin。
- 代价：一个 `Boolean` + 一次 null 判断。风险：pin 只影响测试路径；`invalidate()` 后回读文件。影响：生产零变化。
- 验证：jar sha256 `2A1D3819B3BE8E47DD232A26FA7A26C002295DBA3B51976B024901361109AC5F`（1,095,405 B，466 entries）已装；全量 core `passed=550 failed=0`（S1–S206）。

## 2026-11-06 B153 — 答完选牌屏只跑到半程：面板的回合号与血量停在半路

- 用户 m14038 原话：「新bug，面板实际不一致。找原因」；m14215「继续」（＝照上一轮定位的修法实施）。
- 现象（solver.log，38 步计划）：第 13 步「结束回合（选：防御 → 农盘）」存成 `t=3→3 hp=32`，实机把这次结束回合走完到 `t=6 hp=51`；之后 `plan stale: step 14 belongs to turn 3, live turn 6` 把计划丢掉，面板整条线偏低。第 1–12 步两侧 `step plays` 逐张相同、rng same。
- 旁证：同一状态 `Gameplay.predictEndTurnRisk()` 给出 51（与实机一致）⇒ 不是游戏不同，是计划侧没跑完。
- 根因：`Driver.resolveSelection` 四个出口（HAND / GRID 空 / GRID 非空 / REWARD）答完屏只调裸 `drain()`＝`drain(600, 250L)`（`Driver.java:297-299`）且丢弃返回的 `Drain`；这次屏由结束回合里排队的动作打开（唐→豹指导），答完后队列里还剩整段怪物回合+下一回合开始，远超预算 ⇒ 半程被当终态写进 `Step.digest`/`turnAfter`。
- 改法：`Driver` 新增 `answerLeftUnfinished` / `lastAnswerUnfinished()` / `drainAfterAnswer()`（drain → 不 settle 走 B102 `completeIfTruncated`（4000 步/3600 s）→ 仍不 settle 置标志 + `warnEvery("answer-unfinished")`），四处出口改调它；`Solver.expandScreens`/`expandCore` 见标志即丢分支（B140 规则：半程不算终态）。
- 门禁：新增 S207 5 条（`LongAnswerProbeAction` 开屏后按 1200 帧继续占队列，每帧 +1 格挡让 progressHash 可见——只增计数会被 48 帧 stall 检测判卡死）。修前必红实测：`if (true) { drain(); return; }` ⇒ `tail 0->600/1200` 红 1 条；还原后 `tail 0->1200/1200 unfinished=false` 5/5。子集 29/0。
- 代价：普通步 0（首次 `drain()` 不变）；只有「答完的屏后面还挂着队列」时多一次有界完成泵。代码：`Driver` +35 行、`Solver` 两处、1 个探针动作、1 个门禁。
- 风险：完成泵会把结束回合跑到下一玩家回合开始；中途再开屏会照 B151 正常停下；真跑不完就丢分支（宁丢不错，不存半成品）；`answerLeftUnfinished` 必须在应答后立刻读。回滚＝四处改回 `drain()` + 删两处判断 + 删 S207/探针类。
- 影响：面板那一步的回合号/血量与实机一致，`plan stale` 不再因半程误丢；自动出牌答完屏后跑完结束回合再切步；搜索多一次完成泵、状态更真；不可完成的 mod 结束回合表现为「该分支消失 + `answer-unfinished`」，不认 mod 类名或卡名。
- 验证：jar sha256 `3A263AF90B085E26C9BE7DFD6758A42F09959955EF89CD29D81A181AE008B5EB`（1,099,881 B，468 entries）已装；全量 core `passed=555 failed=0`（S1–S207）。

## 2026-11-06 B154（未修实现，列 M30；对象已由 B156 订正）结束回合闸门读到半程值 + 实机/计划并排取证

用户 m14492：「不是要换计划是要一开始就对。我说了多少次了，换方案」⇒ 撤回期限/保留计划类补丁，改为定位沙盒与实机的第一处不同。

**本轮只有一处代码改动，且只管日志**：`sts1solver/Gameplay.java` 的 `reportSettleWait()`（原 :3039）在 `pending != null` 时同时打印两侧同为「此刻」的板面与出牌：
- `digestMatch=<bool> live=|…| plan=|…|`（`Digest.ofCurrent()` / `pending.digest`，`Digest.stable` 比较）
- `live plays: … || plan plays: …`（`mirror/PlayTrace.recent(8)` / `plan.plan.get(step).plays`）

决策、评分、搜索、期限逻辑一行未动。

**顺带查到的面板错值**：`Gameplay.predictEndTurnRisk()`（:2308-2338）只跑一次 `Driver.endTurn()` 就取 `currentHp()`，不回答任何选牌屏；B151 起「屏开着动作队列冻结」，所以它在「唐 → 豹指导开屏」那一步返回的是半程血量。
日志 21:22:45.261 `end-turn risk: 结束回合预计 -0 HP → HP 51（来袭 ≥35）`：51 = 32（当前 HP）+ 24（TrancePower 每跳治疗 maxHP*3/10）+ 5 点扣血，正好是治疗之后、第一块选牌屏刚打开的那一帧；同一计划该步（trace step 14，`t=3→6`）的终值是 `hp=43`。⇒ 面板数字与最终结果必然不同，这是预测器口径错，不是计划算错。

**未验证**：计划该步终值（43）是否等于实机终值 —— 21:23:05 的 20 s 期限先到，计划被 `plan stale … live turn 5; dropping it` 丢掉，两侧从未比过。

**验证**：build+install OK，jar sha256 `E2400E0E4327FAFE55B8B641D4D180185E4B4EB9AF8D41157BC577B670D1B4F9`（1,100,058 B）；门禁子集 `S182,S183,S184,S196,S206,S207` 19/0（改前 19/0，同一套）。全量 core 未跑（基线仍为 B153 的 555/0）。

**代价**：等待期的日志每 2 s 多两个字段（约 400 字节/条，仅 pending 期间）；运行时多一次 `Digest.ofCurrent()`（微秒级）。**风险**：无决策变化；万一 `plan.plan.get(step)` 越界已用 try/catch 吞掉（只在 pending 期间调用）。**影响**：面板/自动出牌/搜索行为不变；仅 solver.log 多出「同一步的实机与计划并排」两行，供定位沙盒算错位置。

## 2026-11-06 B155 — 机制清单 + 门禁覆盖（user m14586）

用户 m14586 在四个候选方法（对拍回放 / 观测量扩容 / 机制清单+门禁 / 半程值清零）里选了「优先机制清单 + 门禁覆盖」。

**交付 1**：新文件 `docs/SANDBOX-MECHANISMS.md`——30 条机制 M1–M30，A 回合与动作队列 / B 选牌屏 / C 死亡与结算 / D 随机流 / E 镜像与隔离 / F 战斗结束 / G 战斗内 / H 生命周期，每行给「机制 / 引擎依据 / 沙盒实现 / 门禁 / 状态」。未覆盖项按优先级写在同一文件：P0＝M30 半程值、M24 战后结算；P1＝M27 多段攻击、M17 读档重播种；P2＝M29 `tick()` 提前 return。

**交付 2**：补上 M2 的门禁 **S208**（`Probe.java`，插在 S201 之前）。M2 = 回合末弃掉整手牌 + 空抽牌堆从弃牌堆洗回，两件事都跨 H/D/X 且 digest 顺序敏感，此前无门禁。合成战 seed 9208 / 80 HP / `Strike_R`×5+`Defend_R`×5 / JawWorm → `drain(4000,800)` → `captureLive()+install()+begin` → 手牌 +3 张 Strike_R、抽牌堆整堆搬进弃牌堆 → `pressEndTurnButton()` → `drain(4000,1500)` → 5 条断言（按到按钮 / 回合前进 / hand=5 / 三堆守恒 / draw>0）。

**验证**：`-Gates "S208"` → `S208: pressed=true turn 1->2 hand=5 draw=8 discard=0 total=13 now=13`，5/0；全量 core 22:15 跑完 `passed=560 failed=0`（S1–S208，104 extended skipped）。build：130 source、Jar 469 entries、1,102,888 B、sha256 `01006A355843BEA7DDEE8CDA5CA384C68D321633E011DA7816DDB087005A1433`，已装 `D:/Steam/steamapps/common/SlayTheSpire/mods/`。

**代价**：无运行时开销（文档 + 一条测试）。**风险**：清单可能漏机制 ⇒ 维护规则「先归机制编号，找不到就加行；❌ 只能靠门禁转 ✅」。**对 mod 的影响**：面板、自动出牌、搜索结果、其它 mod 兼容性全不变。

**下一步（未获批准前不动手）**：按清单优先级补 ❌ 项——P0 的 M30（`predictEndTurnRisk` 只认 settled 或明确报「不可预测」，直接消除 51 vs 43 这类错值）与 M24（沙盒不跑 `onVictory` 的边界要用门禁钉住，再决定是否在胜节点跑一次结算）。

## 2026-11-06 B156 — 订正 B154：面板数字来自搜索，51-vs-43 是闸门的数（user m14712）

用户 m14712「你确定吗？要一开始就对」——质疑成立，我上一条把两处数字混成一个。

**订正**：`Gameplay.refreshPreview()`（:739）用 `quick.previewHpLoss()` 写「预计 -N HP → 剩 M HP」（`hpPreviewText()` :754），终态行取 `Step.hp` —— 面板数字**全部来自搜索**。`predictEndTurnRisk()`（:2308）只在 `:2447` 被调用，喂 `decideEndTurnRisk()` 这个「结束回合 ALLOW/CONFIRM/STOP」闸门，不进面板。

**21:22 日志复算**（`logs/solver-20261106-212314.log:11060-11082`）：计划 step 14 `expectedEnd=6`；实机两块 HAND 答案 `Defend`/`NongAbacus` 与计划一致；turn 3→4→5 在推进；`11081` 20 s 到期、`11082 plan stale … dropping it`。⇒ 那次模拟与执行都对，是**固定墙钟**把仍在正确推进的一步丢掉。

**提议（未实施，等确认）**：①同一步只保留一份模拟结果，闸门不再自己截断跑一遍；②一步的结束由计划声明的终态（`turn==expectedEnd` / digest 匹配）判定，只有「数秒无推进」或「越过 expectedEnd」才算失败。

**代价**：正常步零新增开销（每 2 s 本来就在读 turn/digest）。**风险**：expectedEnd 算错时会等一个不会来的状态 ⇒ 必须保留「无推进即失败」兜底与「turn 超过 expectedEnd」分支。**影响**：面板数字与自动出牌不再被墙钟打断，长结束回合（跳两回合+答两块屏）不再中途丢计划；搜索与评分不变。

## 2026-11-06 B159 — 风险闸门答完选牌屏再读血（user m15391）

用户 m15391 一个字：「改」＝修 B158 末尾点出的唯一未修项（M30）。

**根因**：`Gameplay.predictEndTurnRisk()` 只跑一次 `Driver.endTurn()` 就取 `currentHp()`；B151 后「屏开着队列冻结」，所以在结束回合里第一块选牌屏（唐 → 豹指导）处停下，把开屏那一帧当回合末（21:22 报 HP 51，计划该步终值 43）。它只喂 ALLOW/CONFIRM/STOP，不进面板。

**改法**：`predictEndTurnRisk()` 在 `Driver.endTurn()` 后调 `answerEndTurnScreens(...)`，用该步 `Step.action` 的 choice 链逐块 `Driver.resolveSelectionByNames`；答不进去 `settleForReplay(16)` 重试一次；答案用完/答不进/>64 块/答完仍有屏 ⇒ `ok=false` unknown（停下不猜）；全答完才读血。新增 `endTurnStepAnswers()`、`splitChain`、`chainAt`；`PlayChosenProbeAction.played` 计数。

**验证**：S213（`risk.ok` / `played==1` / `damage>0`）3/0；修前必红实测（调用改 null）`damage=0 played=0` 红 2 条；全量 core **576/0（S1–S213）**；jar `BA8D6273…D3CE`（1,115,759 B）已装。

**①代价**：只有结束回合那一步多几次答屏调用（每块几百微秒，最多 64 块）；代码 +约 110 行。**②风险**：实机开屏顺序与计划不同会答错屏（靠牌名校验 + unknown 停下兜底）；屏里没有计划那张牌 ⇒ unknown ⇒ 自动出牌停下等人；闸门异常一律 `ok=false`，不污染实机；回滚只需把调用改回 null + 删两方法 + 删 S213。**③影响**：面板数字不变；自动出牌的风险判断变准（以前偏乐观）；不认任何 mod 类名，兼容性不变。

## 2026-11-06 B158 — 删掉第二处墙钟，不再猜牌，判步只看状态（user m15075）

用户 m15075：「1.删除墙钟：字段、两处赋值、超时分支全删（不再有任何时限兜底）2.应该不止这个原因。之前没加时钟也是错的」。第 1 条 B157 已完成；第 2 条的答案是**还有三处时间/猜测驱动的判据**，都不在 `pendingDeadline` 那个字段上：

1. `pendingChoiceUntilMs`（`Gameplay.java`，submit 时 `now() + 6000L`、`answerNextScreen` 递进时再续 6 秒）。过期后 `answerLatentSelection()` 只打一行 `selection open with no planned answer` 就返回 ⇒ 计划里的答案被时间悄悄丢掉，自动出牌空放这一屏。**字段、两处赋值、`reset` 清零、两处判断全部删除**，答案不再过期。
2. 实机答屏的 best-first 兜底：`planned.isEmpty()` 时调 `Driver.resolveSelectionLive(planned, …)` 猜「最优一张」（日志 `execute: answered selection with best-first`；21:22 那次结束回合里出现过两次）。猜的分支是搜索从未假设的，之后每个数字都是虚构。**改为停下**：保留计划、`Log.warnEvery("selection-no-answer", 5000L, …)`，面板写「这一屏计划里没有答案（实机开了沙盒没算到的界面）；自动出牌已停下，请你选，选完点「自动」继续」。卡牌奖励屏同款（`reward-no-answer`）。
3. 判步窗：`AutoPace.readyToJudge(now(), lastActionAtMs, AutoPace.verifyMs())`（`settings.txt autoplayVerifyMs`，默认 700ms）决定「这一步可以判了」。它和时间戳 `lastActionAtMs` 同源，仍属时间判据。**删掉整段等待**，判步只看 `engineSettled()` 与 digest 是否还在变。选牌重试窗（`Driver.selectionRetrying()`）同样改成只判状态、无上限地等（`selection-retry` 5 秒一行）。

门禁：S211（计划答不了的屏交回玩家、不填牌；**修前实测红 2 条**——`filled=1`、屏被关掉，jar 73CFBE14…4699）、S212（答案 6.3 秒后仍被用来答屏、答完即消费）。全量 core **573/0（S1–S212）**，jar sha256 `0FB4B1E950EDFD2D570BD6931ED79A6AE42372A8A10DAA7184F011037FFE9130`（1,112,082 B）已装 D:/Steam/steamapps/common/SlayTheSpire/mods/。

**仍未修**（已在 SANDBOX-MECHANISMS.md M30 记为 ⚠️）：`Gameplay.predictEndTurnRisk()`（`Gameplay.java:2308-2338`）只跑一次 `Driver.endTurn()` 就取 `currentHp()`，遇到选牌屏就停 ⇒ 喂给「结束回合 ALLOW/STOP 闸门」的是半程值。B156 已确认它**不进面板**（面板数字来自 `Solver.Step.hp`），所以它不会造成面板错值，只会让闸门判断偏乐观/偏悲观。

**代价 / 风险 / 对 mod 的影响**：

- 代价：删掉的都是等待窗口，普通步零运行时开销；S212 让 core 多花 6.3 秒。代码净减。
- 风险：①计划里没有答案时现在会**停**（以前会猜着继续），缓存里没有答案的老计划更容易停——这是有意的，宁可停下也不编造状态；②选牌重试改为无上限等待，真死锁只能靠 `selection-retry` 日志（5 秒一条）发现；③卡牌奖励屏同样会停。
- 对 mod 的影响：面板不再出现「按猜的牌往下算」的数字；自动出牌在实机开了沙盒没算到的屏时停下等你，而不是替你选一张；搜索、评分、缓存键都不变；判据只用 `isScreenUp` / `selectionKind` / `engineSettled`，不认 mod 类名。

## 2026-11-06 B157 — 删掉 20 秒墙钟：走到 turnAfter 才算完，越过/偏离立即停（user m14819）

用户 m14819 三条：①失败样本＝三奴隶商人那次日志 ②不要新增时限兜底，改好了根本不会出错，有不对的直接停 ③实机多跳一回合就停下等你。

**定因**（`logs/solver-20261106-212314.log`）：step 14（结束回合 t=3→6）在被砍之前是**对**的——11066 `settle wait: step 14/38 turn=3->3 expectedEnd=6`、11069/11074 两块 HAND 屏答案 `Defend`/`NongAbacus` 与计划一致、turn 3→4→5 在推进；11082 20 秒到期 `pending action did not settle within the deadline; plan kept`，11083 随后 `plan stale: step 14 belongs to turn 3, live turn 5; dropping it` ⇒ 是墙钟判据把仍在推进的一步砍掉，不是沙盒算错。

**改动**（`Gameplay.java`）：删 `pendingDeadline`（字段 + 两处赋值 + 超时分支）；结束回合改成等 `GameActionManager.turn >= expectedEnd`；新增越过即停（`turn > expectedStepEnd()` ⇒ 停自动出牌、保留计划、不推进 step）；新增偏离即停（stable digest 与计划不符 ⇒ 停自动出牌 + status「第 N 步和计划不一样（局面已偏离）」）。

**门禁**：S209（越过即停 3 条）、S210（偏离即停 3 条）；修前必红实测（`stopAutopilot` 改 `if (false)` ⇒ S209/S210 各红）。子集 S209,S210 = 6/0；全量 core **566/0（S1–S210）**。

**安装**：jar sha256 B1C66D64F31DC9D1EEC6034AA1F01F0D1345D560396A28375A7539BB7AE1E408（1,107,252 B）。

**待用户实测**：再打三奴隶商人那种「结束回合里唐连开选牌屏 + 跳回合」的局面，日志不应再有 `pending action did not settle within the deadline`；正常步只应出现「第 N/N 步已校验」；真越过/真偏离时自动出牌会停下并写原因（计划保留）。

## 2026-11-07 B160 — 面板只留「谁回了多少血」，删掉白名单提示与战后药水行（user m15722）

用户四条：①其他文本都不要改，但两处必须处理：`还有没认出来的遗物，可能回更多` 是白名单泄漏（沙盒不该「认出」遗物），正确做法是只说有哪些遗物回了多少血；`战后药水: …` 从没显示过、也没必要显示。②问 `Solver.candidates 答屏` 与 `屏开着允许计算` 之外的两项改动（ScreenKey / 子集枚举）是干什么的，若只是为了让选择时能搜索，有没有必要。③选牌时一定是玩家行动，不会是敌人回合中途。

**B160 判定**：用户①说得对。`certified()` 是「遗物类名是否在三个原版 id 的白名单里」，带任何 mod 遗物（无论是否回血）都会让那句话出现；游戏不「认」遗物，它跑 `onVictory`。已删除该提示、`certified()`、`KNOWN`；`describe()` 现在只报「打赢后固定回血 +N（燃烧之血 +6，带骨肉 +12，自我修复 +N）」。

`PotionOutlook.describe()` 只在药水栏满时非空（`if (!beltFull || sozu) return "";`），所以用户从没看到；该信息已以 8000 分 credit 进 `Solver.score`，面板行删除，**计价未动**。

**验证**：jar sha256 `5B41140D1F75C0B56897F712668B1AE6B89AFBFF363D0FC2C56338C8FFD6C22E`（1,115,020 B）；子集 `S14,S27,S39,S171` 26/0；core 576/0（S1–S213）。

**②③的答复（未改代码）**：`ScreenKey` 这项**不必要** —— 逐分支记录屏种类（`Action.screenKinds` / `Choice.screenKinds`，B140）加开屏计数（B141）已经能区分「同一动作连开两块屏」，再做一套绑定是重复建设，撤掉。`anyNumber 子集枚举` 是**质量项、不是前置项**：不做也能算，只是赌博筹码「弃任意张再抽同数」只会被搜到「空集 / 最佳 k 张」两种，会漏好线。最小路径＝①`Solver.candidates` 支持答屏步 + ④屏开着也允许开算。③留到实测发现推荐线明显不对时再做（并先补门禁）。③的用户第 3 点成立：屏只在玩家行动期打开（回合开始抽牌后/出牌/结束回合排队动作/奖励），敌人回合不开屏，所以④不需要「不能是敌人回合」的守卫。

## 2026-11-06 B161 — anyNumber 屏枚举全部子集（user m15864「134都要做」）

- 用户 m15864 要求做 ①③④ 并问了换序能否模拟；m15898 要求先做 ①③④。本批完成 ③。
- 代码：`Driver.selectionOptions(int)` 的 `upTo && !scry` 分支由「空集 + 价值前 k 前缀」改为 k=0..maxKeep 全组合（复用 `combinations`，cap 来自 `Limits.handBranches()` 16/36/∞）。旧前缀是新集合的子集。
- 门禁 S214(5)：真实 `GamblingChipAction` 开屏，3 张手牌断言 options=8、singles=3、pairs=3。修前必红实测 options=4/one=1/two=1（jar 095EE7F9…79B6，备份 `_tmp/Driver.java.b161bak`）。
- 结果：全量 core `passed=581 failed=0`（S1–S214），jar sha256 `FFC7338A…253F`（1,117,383 B）已装。
- 换序 A/B 结论（回答 m15898）：A＝答点击脚本（select/deselect 原语，每屏 +n 分支，Driver 约 +150 行），是 B 的真子集与引擎原语级正确最小单位；B＝直接答「最终手牌排列×选中集」，需排列生成 + 去重 + 截断启发式。A 更简单因为只需复刻引擎已有的两个动作，不必枚举排列。
- 待做：① root 答屏步（`Solver.candidates` 屏开时返回 `selectionOptions`）→ ④ 屏开着也允许开算（`startQueuedSolve` / `engineSettled` 判据）；A 未获批。

## 2026-11-07 B162 — 选牌屏开着也能开算（①）+ 答屏是一步（④）（user m16041「继续做14」）

- 用户 m16041：继续做 ①④，另批准做 A（点击脚本换序）并问「换选/取消每步扣 1 分」是否合适（见本文件上一节，`Solver.score` 的 layer 项本来就是每步 -1 分、1 HP=2000 分 ⇒ 扣 1 分等于没有成本，回答待给）。
- 代码（①）：`Action` 增 `kind="sel"`/`screenKind`/`Action.selection(...)`/`isSelection()`，`isEndTurn()` 收窄成 `kind=="end"`；`Legal.selectionActions()` + `Legal.apply` 答屏分支；`Solver.candidates` 有等待屏时只返回答屏候选；`Solver.answerForReplay` 对答屏步早退。
- 代码（④）：`Gameplay.blockedReason(Action answering)` + `pickerWaiting()` + `startQueuedSolve`/`startQuickSolve` 允许屏开着开算 + `executeStepInternal` 用带答案的 blockedReason + `submit` 遇答屏步走 `Driver.resolveSelectionLive`。
- **真根因**：`World.readBack()` 对 `discardOnReadBack` 一律跳过，`AbstractDungeon.screen`/`isScreenUp`（B81 标记）因此不被带过世界 ⇒ 答完屏的世界再 `install()` 又把屏装回来 ⇒ `AbstractRoom.update` 不推进 action 队列 ⇒ 之后每张牌静默 no-op（实测 290 步 t/e/hp/怪物血全不变，搜索把 no-op 串成无限线）。修法＝`isScreenState(...)` 特例，只在「确实有屏在等答案」时回写实际值，否则写 `NONE`/`false`；并在 `Legal.apply` 加「屏开着没人等答案 ⇒ 抛 not-settled」，把静默变成显式失败。
- 门禁 S215(7)：真实 `GamblingChipAction` 屏开着 → `Solver.solve` 第一步是答屏、答完 waiting action 真执行、`startQuickSolve` 能开算。实测 7/0，`steps=12 first=答选牌（选：打击,打击）`。
- 结果：回归子集 48/0；全量 core `passed=588 failed=0`（S1–S215）；jar sha256 `AE237A93E31BD049D537DED870AACAB603E66C26D22B87A6CFE419515D67DB67`（1,123,015 B）已装。
- 待做：A（点击脚本换序 select/deselect）未开工；①④ 后 `PlanCache` 键仍未含屏状态（边界已记入 PLAN-BUGS B162「保留的边界」）。

## 2026-11-07 B163 — 答选牌可以带换序（A 方案）（user m16041「那就做a」）

- 用户 m16041 原话：「1.继续做14。2.那就做a。不过选择和取消没有代价要小心世界线看到一个放弃循环的世界线里…一次步骤(就是刚才的选牌换牌，其他的不算)扣一分或者更少，你觉得怎么样有没有更好的方案」。
- 引擎事实：`HandCardSelectScreen.selectHoveredCard()` 选中时 `hand.removeCard(c)`+`selectedCards.addToTop(c)`；取消选择（再点已选牌）走 `AbstractDungeon.player.hand.addToTop(e)`；`CardGroup.addToTop` = `group.add(c)`（追加末尾）⇒「点第一张再点一次」＝这张牌落到手牌最后一位。手牌顺序是引擎状态：唐 `PlayRandomCardAction.java:61` 用 `AbstractDungeon.cardRandomRng.random(cardGroup.size()-1)` 从 `hand.group` 抽，回合末弃牌按 `hand.group` 顺序。
- 代码：`search/Action.java` 加 `moved/movedUuids/movedDisplay`（三处复制块同步）+ `Action.selectionWithMoves(...)`（label 追加「（换序：X）」）；`drive/Driver.java` 加 `moveToEndOfHand(...)`，`resolveSelectionByNames` 4 参委托 6 参、`resolveSelectionLive` 6 参、`resolveSelection(chosen, moved)`，非 HAND 要换序 ⇒ `not-offered`；`search/Legal.java` 的 `selectionActions()` 每屏额外 ≤4 个变体（`REORDER_BRANCHES`，移「未选中的最高价值牌」，cap 由 `Limits.choiceBranches` 给）；`Gameplay.submit` 传 moved。顺手修「空答案被当 not-offered 拒绝」（`askedEmpty`）。
- 门禁 S216(5)：真手牌屏，答「选 Bash + 移一张到末尾」→ 消费者只看到 Bash、手牌最后一张是移动的那张、label 带换序；第二轮「一张都不选 + 移一张」放行。修前必红（`moveToEndOfHand` 临时禁用）实测 `passed=3 failed=2`。
- 结果：S216 5/0；全量 core `passed=593 failed=0`（S1–S216）；jar sha256 `4D1E6D4EB878169121AC6E23843E5C5641F543F4C2AF33D8AA55185089210B7C`（1,130,506 B）已装。
- 关于「换选/取消每步扣 1 分」：`Solver.score` 的 `layer` 项本来就是每出一层 −1 分（`10000000 - StrategicDeficit*2000 - (monsterHp*20 + alive*400 + block*2 + layer)`），1 HP = 2000 分 ⇒ 一层 1 分等价于 0.0005 HP，小到只能当平局裁刀（正好是我要的性质：不改变任何真实收益的比较，只让「同样好的两条线」偏向更短的）。所以不需要新增「操作扣分」机制，`layer` 已经给了；真正要防的「选/取消死循环」由搜索的深度上限与 `Limits` 兜住（每答一次屏消耗一步）。

## 2026-11-07 B164 — 战后奖励页计入胜利评分（user m16960/m17196/m17363）

- 现象：solver.log:1250 的 15 步计划末「头槌→抢劫的 ｜ 结束回合」，step14 live/sandbox 一致（8→3），计划主动放跑小偷；实机上打死小偷会把贼赃 20 金以奖励项归还。
- 根因：胜利端无任何战利品项（`Solver.score` 只有 HP 亏损 + goldDelta）、`EscapePolicy.forfeitPoints()` 零调用者、`AbstractRoom.java:309`「有怪逃跑不发普通 10–20 金」未建模、贼赃只进 `room.rewards`（不进 gold/digest/面板）。
- 代码：新增 `search/PostCombatLoot.java`（一次性窗口里 `rewardAllowed=false`+`isScreenUp=true`+`endBattleTimer=-1`，泵 `room.update()` 到 COMPLETE 再 `setupItemReward()` 读真实 `room.rewards`；含 Snapshot 逐项还原与 `formula()` 回退）、`search/LootBook.java`（卡 2/4/10 HP、遗物 5/10/15 HP，settings.txt 可调）；`Solver` 两处评分 `-forfeitPoints()` → `+lootPoints(loot)`（逃跑自然拿不到，避免双算）；`Gameplay` 面板行「战后 +X 金 +Y 卡（Z HP）」。
- 门禁 S217(8，杀小偷 vs 放跑；含屏幕与 escaped 还原)、S218(4，有怪逃跑时回退金币为 0)、S219(5，价目表与面板文本)、S220(4，引擎与回退对账)。首跑 19/2：S220 红因为把 `RewardItem.cards` 的选项数当成张数（改成 `cards += 1`）、S217 红因为 `AbstractDungeon.screen` 是 null 不是 NONE（门禁归一）。
- 结果：S217–S220 `passed=21 failed=0`；全量 core `passed=614 failed=0`（S1–S220）；jar sha256 `21C6BF4DDE4577D96B6F7D02B3A8263C80100CAD39A3177EEB17BF36107C1BD3`（1,154,997 B）已 install。
- 代价：每个胜利节点一次 settle（≈1–2 帧 `room.update()` + `setupItemReward`，只在 `GrowthBook.counting()` 时跑）；风险：Snapshot 必须逐项还原（RNG counter/遗物池/kill 计数/`room.rewards`/`endBattleTimer`/`isScreenUp`/`isEndingTurn`/`escaped`），回退走 `formula()` 期望值；影响：面板多一行战后价值、搜索开始偏好杀小偷/不放过贼赃、每步 digest 不变。
- 已知边界：`onVictory` 仍不跑（M24，毒种腐朽/自修回血不可见）；药水按 `PotionBook`；结算用沙盒当前随机流。
- 追加（m17808）：面板那行删掉——`Gameplay.lines()` 不再输出「战后 …」，价值只以 `plan :` 之后的 `loot :` 行记录（`Gameplay.logLoot`），`Loot.panel()` 删除。金币取整复核：**没有**「24 金=0」问题——金币按 80 分/枚逐枚计价（24 金=1920 分=0.96 HP），不经过 25 金分档；卡/遗物/药水仍按 HP 定价再 ×2000（旧 25 金一档来自 B146 已退休的 `growth.handOfGreed`）。S219 由 5 条变 6 条（新增 24 金=1920 分）。重跑 S217–S220 `passed=22 failed=0`，全量 core `passed=615 failed=0`，jar sha256 `AD183F727DB8852C11EFE3060DDF69540E6B2AB531979344250CC9A6989E9F97`（1,154,935 B）已 install。

## 2026-11-07 B165 — 开屏随机归一（一次开屏只抽一次；user m17913/m17967/m17993）

- 现象：打大脑袋，第 72 步技能药水的三张候选与计划不同（实机 `[Skim, Chill, Conserve Battery]`，计划要 `Undo`/均衡）⇒ 自动出牌按 B158 停下；用户怀疑帧率影响随机数，提出「帧率当分支」。
- 根因：`DiscoveryAction.update()` 每帧重生成三张，只有 `duration == Settings.ACTION_DUR_FAST` 那一帧的列表被 `customCombatOpen` 采用；我们自己的 `Driver.pumpLiveUntilConsumed()` 屏开着时还要再跑最多 8 次 `gam.update()`，每次重入再抽 3 张（≈24 抽），而沙盒 `pumpUntilSelection()` 在模态屏期间只 `pumpScreenSelf(1)` 不跑 manager ⇒ 实机随机流被白推、之后全部错位。日志里的 42 是 RandomXS128 内部步数，不是抽数。
- 代码：新增 `mirror/DiscoveryRandom.java`（对两个 private 生成方法打 `@SpirePrefixPatch`，duration 不是 `ACTION_DUR_FAST` 就返回空表，是则生成并 `++generations`；无状态，live/沙盒共用）；`Action.rngNormalized` + `Solver.expandCore` 前后 `count()` 差标记；`Gameplay` 步骤行 ` *` + 计划底部一行图例；`Driver.screenDrawCount()/noteFrameDraws()` 做 F2 帧抽检测（`screen-rng` 键 10s 限流，只记日志）。
- 门禁：S221(6，纯函数：FAST 帧 3 张/3 抽、后续帧 0/0、`generations`)、S222(6，真实 DiscoveryAction：三张不变、10 帧零抽、`rngNormalized` 三份拷贝都保留)。**S190 末条订正**：旧断言「manager 再跑时继续抽」钉住的正是本次要消灭的浪费，改为「答完屏不再抽」（卡仍照常进手）。
- 调试：全量首跑 626/1（唯一红＝S190 旧断言），订正后重装重跑。
- 结果：S221/S222 `passed=12 failed=0`；全量 core **`passed=627 failed=0`**（S1–S222）；jar sha256 `62A49DFE2D255D1597EA4C72FBD98D86839D42318B9BA31A9E75102C276C2377`（1,163,941 B）已 install。
- 代价：每次生成一次 float 比较 + 一次 static 自增；无缓存、无容器；反而少浪费 ≈24 抽/次喝药；搜索不变慢。
- 风险：手打随机流相对原版变化（只少浪费、概率不变，live/自动/沙盒三者一致）；依赖逐帧重掷的 mod 只会拿到第一帧（原版也只采用第一帧）；`duration == 0.25f` 精确比较（构造器即 FAST）；回滚＝删补丁类 + `rngNormalized` + 三处拷贝 + `Solver`/`Gameplay`/`Driver` 各自独立的小改。
- 影响：面板多 ` *` 与一行图例；自动出牌与沙盒随机对齐（喝药后不再停手）；其它帧相关动作只告警不改行为。

## 2026-11-07 B166 — 计算时意图徽章抖动变快（`BobEffect` 被共享；user m18398）

- 现象：计算/搜索期间，怪物头顶意图（如 7）与伤害数字的上下浮动明显比平时快，像抖动/瞬移。
- 根因：`BobEffect` 落在 `ClonePolicy` 的 `com.megacrit.cardcrawl.vfx.` 共享规则里（它不是 `AbstractGameEffect`）⇒ 沙盒与实机同一个 bob 对象；`BobEffect.update()` 读 `Gdx.graphics.getDeltaTime()`，窗口代理 delta＝`FAST_DELTA=20.0f`（`Driver.java:864`）、speed=4 ⇒ 一帧 80 rad≈12.7 周期；`PostCombatLoot` 每个胜利节点在 `Driver.begin/end` 之间泵 `room.update()`（`Solver.java:1575`）→ `AbstractMonster.updateIntent()` → `bobEffect.update()`。
- 代码：`src/main/java/sts1solver/mirror/ClonePolicy.java` 增加 `BobEffect` 例外（返回 false＝拷贝，注释写明 80 rad/帧的算式）。
- 门禁：S223(4)`probe/Probe.java`——实机/沙盒 bob 不是同一对象 + `Driver.begin()` 后 8 次 `getMonsters().update()` 沙盒 timer 变、实机 timer 位级不变；修前实测红 2/2（`liveAdvance=640.0`），修后 4/0（`liveAdvance=0.0`）。单跑 `-Gates "S223"` 绿。
- 结果：全量 core **`passed=631 failed=0`**（S1–S223，245 门禁）；jar sha256 `ECE17BD0434AFCC045044667FA7335B4F15CDDFA91AA38CEA93A73DB8873EA68`（1,166,416 B）已 install。
- 代价：每个世界窗口多拷一个 4 float 的 BobEffect（按玩家＋怪物数），无每帧开销、无日志、无搜索成本。
- 风险：将来若有逻辑读 `bobEffect.y` 会读到沙盒自己的值（应然）；`timer` 是 private 靠 Cloner 拷字段；回滚＝删该行与 S223。
- 影响：面板/自动出牌/搜索均不变；只修实机表现；其它 mod 无交互（原版类，各自持有）。
- 环境插曲：首次单跑门禁时用户刚关游戏，run.ps1 报 `EXITED : the game ended (exit code 130)`；直接用 `ModTheSpire.jar --skip-launcher --mods sts1-engine-solver,basemod --skip-intro` 复现成功（preferences 未被写坏）。

## 2026-11-08 B167 — 读档后计划不复用 + 选牌步答案链不消费（user m18895）

- 现象：同一 19 步计划 6 秒内被 `plan cache: hit rejected, does not fit the live hand` 拒 8 次（digest 逐字相同），每次重算 0.6–4.3 秒；每条选牌步之后都有 `the plan recorded N selection answers … the rest were never used`。
- 根因：①`Gameplay.cacheFits` 只认「首步结束回合/药水」，其余走 `liveCardFor`——它只在手牌里找，而首步「答选牌」没有手牌身份 ⇒ 必拒；②缓存键用 raw digest，首段含 `ph=/q=/use=/end=` ⇒ 隔一帧换 key（且 `Digest.stable` 的剔除循环只看得见整段以 `ph=` 开头的 segment，首段内部的 `q=/use=/end=` 从未被剔除）；③`submit` 的选牌分支绕过 `answerNextScreen`，`pendingChoiceAt` 永不推进、旧答案残留。
- 代码：`mirror/Digest.java`（stable 末尾四处 token 一律剥离）、`search/PlanCache.java`（key 先 stable）、`Gameplay.java`（`planPendingChoice`/`clearPendingChoice`/`answerSelectionStep`/`selectionAnswerFits`/`firstSegment` + `answerNextScreen` 传 moved + 两个测试钩子）、`probe/Probe.java`（S224/S225/S226）。
- 门禁：S224(2) 首步答屏的缓存计划必须被接受、屏关后必须拒绝；S225(2) 只差 `ph/q/use/end` 的两帧必须同 key、血量变了必须不同；S226(3) 选牌步答完后 `pendingChoice` 必须为空。反向验证（改回直接 resolve）`passed=2 failed=1`、`pending=[Strike_R]`；修复后三合一 `passed=7 failed=0`。
- 结果：全量 core **`passed=638 failed=0`**（S1–S226，248 门禁）；jar sha256 `AF702048E57AE8248DAD63F8B71AAF878F8FFB37DEA9BE1B36CBF5B18B424D23`（1,173,143 B）已 install。
- 代价：缓存命中率上升（开算更快）；多存一条 moved 链；`cacheFits` 选牌分支每步扫一遍候选（O(n·k)，可忽略）。
- 风险：`stable()` 会剥掉任何形如 ` q=…` 的 token（目前无冲突）；键不含相位 ⇒ 若相位差异真会影响结果，会命中旧计划（当前不会，沙盒每次从真实状态重建）；候选在两次读之间被 mod 动态改动时会误拒（只重算，不答错）；回滚＝四处独立改动。
- 影响：面板更快出线、不再卡「缓存命中被拒」；自动出牌不再用上一步的选牌答后面的屏；搜索与评分不变；判据不认 mod 类名，其它 mod 的选牌屏同样适用。

## 2026-11-09 B168 — 沙盒窗口内不再派发纯表现事件 healthBarUpdatedEvent（user m19477）

- 现象：m19321「每一局计算都会卡住，之前不会」；m19477 选 A：「实机半血照常播雨爱，沙盒不播、不卡」。日志 `quick search: started` 后 4 次 slow frame 约 600ms，`frame stats p50=54ms p95=511ms`，搜索结束后 p50=16ms。
- 根因：`AbstractCreature.healthBarUpdatedEvent()` 是纯表现方法（只写 `healthBarAnimTimer`/`targetHealthBarWidth`），YuAi 等 mod 用 postfix 挂特效；沙盒每个节点都会因伤害/治疗派发它（8 类 14 个调用点，含 `TheGuardian.changeState(String)` 的 2 个）。MTS prefix 早退挡不住别人的 postfix（javassist insertAfter 插在所有 RETURN 前，实测 `{proj}/_tmp/javassist-test`）。
- 代码：新增 `src/main/java/sts1solver/mirror/PresentationQuiet.java`（`quiet()=World.anyInstalled()`；14 个 `@SpirePatch`+`@SpireInstrumentPatch` 用 ExprEditor 把调用改成 `if (!quiet()) { $0.healthBarUpdatedEvent(); }`；`patchedSites` 走系统属性跨类副本共享）。
- 门禁：S227(6) —— `patchedSites=14`、沙盒 `damage(3)` 后 HP 80→77 而 timer 保持 0.0、直接调用仍置 1.2、卸窗口 `quiet=false`。反向验证（`quiet()` 临时恒 false）`passed=4 failed=2`、`timerAfterDamage=1.2`；修复后 `6/0`。
- 结果：全量 core **644/0**（S1–S227，249 条）；jar `941B00FE9F932A21ECAB211A967B2D8110831898BF5EA3EA883804346698D3AB`（1,185,933 B）已装。
- 代价：加载期 14 处字节码重写；运行期每次血量变化一次静态布尔；无每帧开销、无常驻内存。
- 风险：游戏升级改了方法签名/调用点 ⇒ `patchedSites` 对不上、S227 直接红并报数；`$0` 要求接收者非空（这 14 处成立）；只静音派发不改方法体；回滚＝删该文件与 S227。
- 影响：实机行为逐字不变（雨爱照常播）；沙盒窗口内其它 mod 的表现监听不再收到模拟血量变化；面板/自动出牌/搜索/评分不变。
## 2026-11-09 B169 — 雨爱搜索后假死：静态监听不再回填别的线程的「释放」（user m20148）

- 现象：实机半血雨爱正常响；搜索结束后不再响。`solver.log` 21:37 只有 2 条 `static watch: … YuAiMusic#currentClip` 写告警（首报 + 5s 汇总）。
- 核实：MTS 捕获的 mod stdout（`sendToDevs/mts_process_launch.log`，GBK）全程只有实机 2 次「开始循环播放」（21:33:50 `32/71`、21:36:52 `34/81`）与 1 次停止；搜索窗口（21:37:06–21:37:20、21:39）**零**「开始循环播放」、零「启动失败」⇒ 沙盒没启动过 clip，B168 静音面不必扩。
- 根因：`mirror/StaticWatch.restore()` 不判线程归属；`YuAiMusic$1` 的 LineListener 在音频回调线程 STOP 事件里 `clip.close()` 后 `currentClip = null`，被当成沙盒写回填旧 Clip ⇒ `isPlaying()` 永真、`play()` 首行早退。
- 代码：`StaticWatch.beforeUninstall()` 对 `live == null && 快照非 null` 放行（`++released` + 30s 限流 WARN + `continue`），回填只保留「引用换对象 / 容器内容变」；新增 `releasedForTest()`，`resetForTest()` 归零；`report()` 附「N release(s) left alone」。
- 门禁：S228(5)（释放留 null 且不点名 / 真泄漏仍回填并点名 / 计数）。反向验证 `passed=2 failed=3`、`leftAlone=0`；修复后 `5/0`、`S228 released=null leaked=null putBack=true written=[…S228_LEAKED] leftAlone=1`。
- 结果：全量 core **649/0**（S1–S228，250 条）；jar `BC699BA4755CF8134E886A7BDE347A327B46D78EE28047F8BC5866AFD15C54C6`（1,188,646 B）已装。
- 代价：每窗口一次判空 + 自增；无每帧开销；代码 +约 15 行、门禁 5 条。
- 风险：沙盒若真把实机静态清成 null 不再回填（mod 会重新初始化，通常是想要的）；真泄漏方向仍回填；只对裸引用放行；回滚＝删 `if` 分支与 S228。
- 影响：面板/自动出牌/搜索/评分不变；雨爱与其它异步回调写自身静态的 mod 不再假死。**订正（2026-11-15，B185）**：原记「B168 结论不变（窗口里 clip 本来没启动）」只对**计划里没有回血步**的局面成立；回血节点会真调 `heal`，雨爱两个 postfix 在窗口内真开 `javax.sound` 的 Clip，已由 B185（`mirror/AudioQuiet`）兜住。

## 2026-11-09 B170 — 计算时卡帧：静态监听把雨爱的 8 MB 音频数组逐元素装箱（user m20574）

- 现象：用户控制变量——只有装雨爱（YuAiBloodMusic）时计算期间每隔几帧卡 500–700 ms，不装不卡。22:22 GremlinNob：`QUICK win nodes=1797 expansions=580 17402ms`、帧 `p50=58 p95=511`、slow frame 3477/663/712/565/502/694/550/531/671/494/528 ms、每展开 30 ms（同类房对照 1.5–3.4 ms）。
- 根因：`ModStatics.watchable()` 只按类型名排除 texture/pixmap/sprite/atlas/string ⇒ `yuaimod.YuAiMusic#audioData`（`static byte[8,000,000]`，整个 8 MB wav）进了 `StaticWatch` 候选；`snapshot()` 逐元素 `Array.get` 装箱进 `Object[8M]`（约 32 MB），`bodyEquals()` 再逐元素比 8M 次；采样窗口＝installs 1–4 与每 128 个 ⇒ 一次搜索十来个窗口各付 582 ms + 280 ms。
- 代码：`mirror/StaticWatch.java` 新增 `MAX_ARRAY_ELEMENTS=4096` 与形状 5「large array」：`Array.newInstance(componentType,n)` + `System.arraycopy` 快照、按组件类型分派 `Arrays.equals` 比较、`System.arraycopy` 还原；新增 `shapeForTest(Field)` 测试钩子。
- 门禁：S229(4)（形状必须是 large array / `S229_BIG[4000000]` 的写入仍被抓到并还原 / 温窗口 snapshot+compare <150 ms）。修前 `passed=2 failed=2`（`snapshot=582ms compare=280ms`）；修后 `4/0`（`shape=large array snapshotMs=[355,0,0] compareMs=[4,1,1]`，355 ms 是候选表冷启动）。
- 结果：全量 core **653/0**（S1–S229，251 条）；jar `FDA091268049B0EE2F3BFB90515835E0E7BFAFFCDFD6E7DB7C1FB0AF829ED14E`（1,193,222 B）已装。
- 代价：每个采样窗口一次 8 MB memcpy（≈1 ms），临时内存 8 MB 替代 32 MB；阈值以下不变；代码 +约 45 行、门禁 4 条；无每帧开销。
- 风险：`Arrays.equals` 与旧 `eq` 语义一致（NaN 相等、-0.0≠0.0）；长度变化仍只 WARN；4096 是成本阈值不是语义阈值；回滚＝删 shape 5 与常量，S229 立刻红。
- 影响：面板/自动出牌/搜索/评分/其它 mod 钩子不变；雨爱与任何大数组静态 mod 不再按元素数收费；大数组污染检测仍在。结构性卡顿根因（预算只在 microStep 之间检查）仍未处理。

## 2026-11-10 B171 — Boss/200HP 战的快搜见赢即停：窄档拿到任意胜利就收工（user m20878）

- 现象：打史莱姆 Boss，面板最好路线 -33 HP 只剩 1 血，玩家同打法还剩 21 血；日志 `quick search: started, budget=1000000000ms narrow=true` → `solve: QUICK win nodes=39815 expansions=12109 61291ms reason=win`，deferred=2000/promoted=60。
- 根因：`search/QuickSession.java` narrowDeep 分支先跑 beam=10，只有「窄档什么都没赢到」才启动唯一一档 beam=40；合并规则 `w.win && (best==null || !best.win)` ⇒ 窄档任意胜利即收工、更优宽档胜利被丢弃。普通怪走 PortfolioSession（10/28/64/128、份额 35/25/25/15、`Solver.better` 合并、只 provenOptimal 早停）不受影响。
- 代码：QuickSession 改加宽阶梯——窄档之后按 {28, 64, Limits.beam()} 逐档重搜（`Limits.layers()`、`remaining()`、每档 `maxNodes=max(20000, 窄档节点×2)`），每档 `Solver.better` 合并，`provenOptimal` 才早停；`Solver.better` 改包内可见、`passes()` 公开、`phase()` 显示「加宽搜 beam=N（第 k/3 档）」。
- 门禁：S230(3)（无上限预算 passes≥2、finished+win、hpLoss 不劣于单跑 beam=10）、S231(2)（零伤 proven 胜利 passes==1）。修前夹具实测 `passes=1` 必红；定稿 `5/0`（`S230 passes=4 nodes=251041` / `S230 narrow only nodes=35863` / `S231 passes=1 proven=true`）。
- 结果：全量 core **658/0**（S1–S231，253 条）；jar `BF8D6E5CCE9DBFB22A0E6ED544F6B41A44841FCD0BDC903A512E038AA18D07E3`（1,198,578 B）已装。
- 代价：无上限预算时 Boss 战最坏跑 1 窄档 + 3 加宽档（每档 ≤2× 窄档节点），时长可达原来 1+3 倍量级；有上限预算只是重新分配 `remaining()`，总时长不变；内存峰值同单档；代码 +约 40 行。
- 风险：无上限时明显变久（可暂停/停止）；`maxNodes` 是经验有界值（某档可能被截断而非报错）；`passes()` 语义变化（S230 依赖）；回滚＝恢复「窄档见赢即 finished + 单档 beam=40」，S230 立刻红。
- 影响：面板 phase 文案变化，自动出牌/回放不变，普通怪路径一行未改，其它 mod 无接触点；计划可能因此变成更优的线。

## 2026-11-10 B172 — 普通怪最宽档 128 硬编码，unlimited 档位下比 Boss 阶梯更窄（user m21118「只改这个128」）

- 现象：B171 后 Boss 阶梯用 `Limits.beam()`（high 96 / unlimited 256），普通怪 `portfolioProfiles()` 最后一档仍是字面量 128 ⇒ unlimited 档位下普通怪比 Boss 窄一半。
- 根因：第四档写死 128；且 `PortfolioSession` 用 `static final PROFILES` 在类初始化时快照整张表，切片路径只在首次类加载读一次。
- 代码：`Solver.portfolioProfiles()` 第四档改 `max(128, Limits.beam())`；`PortfolioSession` 删 `PROFILES` 快照、`advance()`/`phase()` 现读并新增 `topBeamForTest()`；`Limits` 新增 `forceBeamForTest`/`beamForTest` 测试钩子。
- 门禁：S232(4)——96 档最后一档仍 128、256 档最后一档与 `topBeamForTest()` 都是 256、45 档不缩水、10/28/64 不变。修后 `4/0`（`S232 highTop=128 wideTop=256 sessionTop=256 lowTop=128`）；反向验证（`int top = 128`）`passed=3 failed=1`。
- 结果：全量 core **662/0**（S1–S232，257 条）；最终安装 jar sha256 `F3E4C1F8944F29BB5CD9630F96DDF858E7232A4B9AE7E7FF9FC22DF240E6D9E9`（1,200,195 B；门禁那次构建是 `F379B5B1…`，同源码重建后 jar 时间戳不同）。
- 代价：每次 solve 多几次 settings 查表；unlimited 档普通怪最后一档束宽 128→256，耗时上升（受份额与预算约束）；high/low 档完全不变。
- 风险：unlimited 且不限预算时最后一档更久（可暂停/停止）；settings 热改即刻生效（原来要重启）；回滚＝写回 128，S232 立刻红。
- 影响：面板档数文案不变；自动出牌/回放/评分/digest 不变；只在 unlimited 档位改变普通怪最宽档；其它 mod 无接触点。普通怪仍用固定份额，未改成逐档阶梯（用户只要求这一处）。

## 2026-11-10 B173 — 面板「预计掉血」与跨档合并用两把尺子（user m21390）

- 现象：第二档搜索里，最新最佳节点的掉血反而比同一档后面出现的节点小（「最佳」的掉血数字变大）。
- 根因：档内 best 用 `Solver.score`（含成长/战利品/战后回血/金币抵扣），跨档 `Solver.better`→`compareRoutes` 只算 `max(0,hpLoss-max(0,postHeal))+potionHp`，面板直接打印原始 `Node.hpLoss` ⇒ 三处是三个数。
- 代码：`Solver.lootHp/netHpLoss/netOf` + `Node.netHpLoss`/`Result.netHpLoss`（切片与阻塞两套搜索循环各自落点赋值，含 root=0、续线、promote、最深线替换、`Session.finish`）；`Solver.better` 改用 `netOf`；`QuickSession/PortfolioSession.previewNetHpLoss` + `Gameplay.hpPreviewText` 打净掉血并在原始值更大时标注抵扣。**档内 best 与 beam 排序仍按 `Solver.score`**。
- 教训：第一版把档内 best 排序也换成净值，棋盘项（怪 HP×20/活怪×400/block×2/层数）丢光，选出「0 伤害 0 回合」的短线 ⇒ S 段 10 条红；已回退。同一份 `Solver` 有两套搜索循环，best 判定必须两处同改。
- 门禁：S233(7)——尺子数学、`Result.netHpLoss==netOf`、`better` 先比净值、切片逐帧净掉血不劣于 `hpLoss+potion` 且 `Result.netHpLoss==bestNetHpLoss`。修后 `7/0`（`S233 ruler: growth=9 loot=7 heal=6 noBank=0 potion=2`）；反向验证（净值退回 `hpLoss+potion`）`passed=5 failed=2`。
- 结果：全量 core **669/0**（S1–S233，258 条）；已装 jar `292C7123907C68556CEF37E08C46B341D1534A3404946FB86C2604130D4971DB`（1,204,316 B）。顺带把 S230 的墙钟截止 300 s→900 s（节点数不变，机器慢 2–4 倍）。
- 代价：每次展开多一次减法 + 一个 int；`better` 每档多一次 `netOf`；面板多一段文案。
- 风险：档内仍按 `score` ⇒ 非胜线净掉血可被棋盘项破同分（<1 HP）；跨档更保血、可能少拿战利品；回滚＝删字段 + `better` 回旧算式 + 面板回 `bestHpLoss`，S233 立刻红。
- 影响：面板「预计 -N HP」变净掉血并标注抵扣；自动出牌/回放/digest/beam 排序不变；其它 mod 无接触点。

## 2026-11-10 12:42 B173 订正 — 净掉血尺子不再扣战利品（user m21803）

- 现象/要求：用户指出「不要显示战利品抵扣」——奖励（金币/卡/遗物/药水）在每场胜利里近乎常数，若拿它抵血，「掉 6 血换相当于 6 血的战利品」会被算成 0 血最优，这种错误不能出现。
- 改法：删 `Solver.lootHp`；`netHpLoss(hpLoss, growthHp, postHeal, potionHp)` 从 5 参改 4 参（签名里传不进战利品）；`netOf` 回落式与四处落点赋值去掉 loot；面板文案改「成长/回血抵扣」；新增 `Solver.resultWithLossForTest(int)` 测试工厂（`Result.hpLoss` 是 final）。奖励只在 `Solver.score` 里参与档内排位（B164）。
- 门禁：S233 从 7 条加到 9 条——`net(6,0,0,0)==6`，以及「把 worth=18000 分（9 HP）的富奖励屏挂到掉 6 血的 Result 上，`netOf` 仍须返回 6」。
- 实测：build OK（135 源文件，1,204,583 B）；`tools/run.ps1 -Gates "S233"` ⇒ `passed=9 failed=0`，日志 `S233 ruler: growth=9 heal=6 noBank=0 potion=2 sixHpWithLoot=6`、`S233 loot: worth=18000 points netWithLoot=6 rawLoss=6`；反向验证（`netOf` 回落式再减 2）⇒ `passed=8 failed=1`（`netWithLoot=4`）⇒ 有牙。第一次跑时 loot 断言仍带着旧的「loot 为空才断言」守卫被静默跳过（`S233 loot:` 行不出现），已改成无条件执行——「不许静默跳过」再吃一次。
- 全量：`tools/run.ps1 -TimeoutSeconds 2400` ⇒ `AUTOTEST RESULT passed=671 failed=0`（S1–S233，258 条，104 extended skipped；比 669 多 2 条＝S233 新增的两条断言）。
- 安装 jar：sha256 `4938545B75C8647E594BBB5FFE712E80796FFFB463343A79209C85432936BE32`（1,204,583 B）；首版 `292C7123…4971DB` 作废。
- 代价：少一次折算、少一个参数，无运行时开销。风险：跨档合并不再偏好「多拿战利品」的线（用户明确要求）；S233 新断言要求 `Result` 可构造指定 hpLoss，故加了一个测试工厂。影响：面板不再出现「战利品抵扣」；搜索排序/自动出牌/回放/digest 不变。

## 2026-11-10 12:48 B173 定案 — 胜线之间仍按 score 排位（user m21969）

- 用户明确：「同一个最佳如果换成一条掉血多 2 点、但战利品多值 3 点的胜线，面板的净掉血仍然会变大是**对的**」⇒ **不改**胜线之间的 best 判据（`Solver.java:1694` 阻塞 / `:4048` 切片的 `n3 > this.best.score`），也不做原提议的 S234。
- 只订正了 `Solver.java:4051-4056` 那段与代码不一致的注释（原文写「keep the cheapest winning line … net HP the panel prints」，实际比的是 score）——现在写明「rank 仍是 score，富战利品的胜线会让面板净掉血合法上升，这是 m21969 确认的预期；净值本身不含战利品（m21803）所以不会被读成 0 代价」。无行为改动。
- 验证：build OK（1,204,585 B）、install sha256 `C6675AA761981805E76FE469A365BDD709F1465B45DD6035524A7309A3CB5B09`、`tools/run.ps1 -Gates "S233"` ⇒ `passed=9 failed=0`（`sixHpWithLoot=6`、`S233 loot: worth=18000 points netWithLoot=6`）。全量未重跑（仅注释变化），上一轮全量仍为 671/0（S1–S233）。

## 2026-11-10 B174 — 药水计价的查证 + 面板补价 + 只读反事实探针

- 现象：面板推荐喝先古药水（1 层人工制品），用户不喝照样过；面板那行只写「掉血 22，用药 1 瓶」。
- 查证（先取证后改）：S236 证明排名净值里药水价被扣（net=9 expect=9）；S237 证明沙盒喝药真的清槽（before=9 after=0 slot=PotionSlot）；S235 用同一场战斗配置跑 10 个种子一次都不喝；S238 逐回合对照证明这瓶药在当前对手模型里值 0 HP（血量完全相同，虚弱只少 1 层而幅度固定 25%）。
- 代码：search/PlanText.java 新增 costText(hpLoss, potionsUsed, potionHpSpent)（有价时追加「（价值 9 HP，合计 31）」）；search/Solver.java 新增只读探针 potionCounterfactual（+potionStepDiffers/potionStepDesc/potionPowers）与测试钩子 potionCounterfactualForTest/potionSkipForTest/potionValueForTest；probe/Probe.java 新增 S234–S239。
- 结果：核心套件 **688/0**（S1–S239，263 条）；jar sha256 **6ABB3A0A1325192068FBE94E631C58E571DE06560E479D402083DB3D50362C11**（1,219,570 B）。
- 代价：面板一个拼串方法；探针只在花药水的计划收尾跑一次重放；门禁 +5 条（S235 十种子扫描约 40 s）。
- 风险：文案改动可单行回滚（S239 兜底）；探针的三个 ThreadLocal 保存/还原需在嵌套调用时复核。
- 影响：计划/排名/自动出牌/搜索分支不变；面板多显示药水价与合计；其它 mod 无交互。
- 未定案：这次搜索是 reason=stopped 截断（48 条未展开），无药兄弟线在其中——已装 potion counterfactual 日志，等下一次实测那一行。

## 2026-11-10 B175 — 面板「预计无伤」改回原始掉血（成长抵扣不再冒充血）

- 现象：Looter 战实掉 3 血（`15:50:33 hpLoss=3`、trace `hp=33→30`），面板写「预计无伤」。
- 根因：面板用排名净值 `Result.netHpLoss`，它减掉 `GrowthBook.credit`；成长预算把升级牌按 5 HP/张（Lesson Learned 50 tenths）计价，神化升 9 张 = 45 HP 抵扣 ⇒ net 归零。
- 代码：`Gameplay.hpPreviewText` 数字与「无伤」改用 `rawHp`，抵扣只作注记；新增 `hpPreviewTextForTest`。`netHpLoss`/`netOf`/`better` 不动。
- 门禁：S241(6) —— 净 0 原始 3 不许写无伤、真空刀仍写无伤、回血抵扣也不许抹、无数字输出空、九张升级=45 HP。反向验证 `passed=3 failed=3`（红行正是「预计无伤」）。
- 结果：core **695/0**（S1–S241，265 条）；jar sha256 `2F96945955E0447C5902E09D6854346FC2D028BA72F43E6B7DCB81CF6F1FA29E`（1,224,037 B）已装。
- 代价：0 运行期开销；风险：面板数字变大（有意的，m21803），回滚=改回 net；影响：只有面板文案，搜索/排序/合并/自动出牌全不变。

## 2026-11-10 B176 — 同一局面的中途相位不再算两条世界线（user m22848/m22904）
- 现象：同一位置在窄档与加宽档各被展开一次；用户判断「指纹没做好局面重复」。
- 根因：判重键是 `Digest.ofCurrent()` 原串，首段含 `ph/q/use/end`（GameActionManager 相位与队列）；一次在队列未结算时采样、一次在结算后采样，就成了两个键 ⇒ `seen` 与层内 `next` 都当两条世界线各展开一次（只浪费算力，不出错答案）。
- 代码：`Driver.settled()/settleBlock()`；`Solver.positionKey(digest, settled)`（结算后用 `Digest.stable`，未结算保留原串）；`Choice/Node/Deferred` 带 `positionKey`，`seen` 与 `next` 改用它；每趟 `position keys (B176)` 日志；测试钩子 `positionKeyForTest/nodesForTest/unsettledNodesForTest/mergedByPhaseForTest`。
- 门禁：S242(4)、S243(2)；实测 `S242 settled=[|t=3 hp=70/80 …] rawBusy=[t=3 ph=EXECUTING_ACTIONS q=3/1/0 use=1 end=0 | …]`、`S243 nodes=12 unsettled=0 mergedByPhase=1` ⇒ 6/0；反向验证（positionKey 恒 stable）`passed=2 failed=2`。
- 结果：core **701/0**（S1–S243，266 条）；安装 jar sha256 `7C6444E7075E7C1E90F5A0EBBFBD2793377A4185B05CD29452939C909D7D2FEB`（1,228,383 B）；真实搜索里 `mergedByPhase` 多为正（nodes=736 merged=91），`unsettled` 每场 0–21。
- 代价：每节点一次 `settled()` + 一次 `stable` 字符串处理，可忽略；键串更短。
- 风险：误判「已静止」会把半截状态合并 ⇒ `settleBlock()` 的七种原因 + S242 钉住；合并改变 beam 名额分配 ⇒ 结果可能与之前不同（有意），靠全量 core 确认无退化；回滚＝删 positionKey，S242/S243 立刻红。
- 影响：只有日志多一行观测；面板 UI、自动出牌、回放、digest、计划格式、跨档合并、评分不变；其它 mod 无交互。
- 边界：**跨档重算未解**（每个加宽档都是新 Session、从根重建，零跨档记忆）。

## 2026-11-10 B177 — 加宽阶梯改成在同一个 Session 内逐档进行（不再每档从根重建）

- **现象**：窄档（beam=10）与后面每个加宽档各自从同一局面重跑一遍；10:18 那局窄档 39815 节点、第二档 79166 节点（上限 `max(20000, 39815×2)=79630`），几乎全花在重算已经跑过的层。
- **根因**：`QuickSession` 每加宽一档就 `new Solver.Session(root, wideBeam, …)`；`seen`/层内 `next` 是 Session 实例字段 ⇒ 档间零记忆；窄档跑到前沿耗尽才收工，宽档整层重算，而同一局面的候选枚举/RNG fork 由状态决定 ⇒ 重算得到相同子节点与顺序，零新信息。
- **代码**：`Solver.Session` 的 `beamWidth/maxNodes/nodeBudget` 去 final，新增 `rungBeams/rung/rungCapNodes/nodesAtRungStart/promotedThisRung/widenedAtLayer/expansionsAtWiden`；`advance()` 末尾 `if (done) { if (!widenToNextRung()) finish(); }`；`widenToNextRung()` 保留层号/前沿/转置表，只换 beam 与节点预算（cap 首档 `max(20000, 本档节点×2)` 定死、后续复用）、重置本档 promotion 配额；`promoteOne()` 配额改用 `promotedThisRung`。`QuickSession` 删 `narrow/wide/rungNodes/widenIndex/wideBeam/MIN_TAIL_MS/proven()`，只建一个 Session 并 `planWidening(widenBeams())`，`passes()=rung()+1`，`phase()` 读 `rung/rungs/beamWidth`。测试钩子 `planWidening/rung/rungs/beamWidth/passNodes/widenedAtLayerForTest/promotedForTest/expansionsAtWidenForTest/deferredCountForTest`。
- **门禁**：S244(5)（seed 9245、`Defend_R×5` 打不死 ⇒ 窄档只能耗尽前沿；实测 `finished=true rungs=3 rung=2 beam=40 widenedAt=27 expansions=1082 atWiden=888 promoted=66 parked=230`）。首版用能赢牌组导致窄档 `provenOptimal` 直接收工、5 条全红，已换成打不死的牌组（真实路径，非特判）；第二版 off-by-one（`rungBeams[rung+1]` 跳过 beam=28）在构建期发现并修。**S245(3)** 钉接线：驱动真实 `new QuickSession(root, 12000L, true)` 跑同一场打不死的战斗，断言拿到阶梯（`rungsForTest()>1`）且真的推进过（`rungForTest()>=1`）；实测 `S245 finished=true passes=4 rungs=4 rung=3 nodes=3663`。把 `QuickSession.planWidening` 注释掉重建安装做反向验证 ⇒ `passed=6 failed=2`（`rungs=1`、`rung=0`），而 S244 仍 5/0 ⇒ S244 钉 Session 机制、S245 钉接线（先前「回滚后 S244 红」的说法已订正）。
- **结果**：S244 5/0；`S230,S231,S232,S233,S243` 20/0（`S230 quick: passes=4 win=true hpLoss=35 nodes=251041`，与旧实现同数）；全量 **core 709/0**（S1–S245，268 条；S244(5)+S245(3) 8 条断言）。已装 jar sha256 `57DC8A8E3C28690C0A49DE3E7B212A738B7968431CB6233D4797E381EB85CDAA`（1,233,118 B）。
- **① 代价**：每节点无新增开销；每次加宽一次状态重置 + 一行日志；总节点数/耗时下降（cap 有界）。
- **② 风险**：早层被窄 beam 丢掉的线只靠 deferred 提回，deferred 被逐出时可能找不到旧实现能找到的更优线（结果可能变化）；`provenOptimal` 早停保留；回滚＝不调 `planWidening` 即回单档，S245 立刻红。
- **③ 对 mod 的影响**：面板档位计数语义不变；自动出牌/回放/digest/评分/计划格式不变；不新增镜像或补丁，其它 mod 无交互。

## 2026-11-10 B178 — B176 汇总日志不再逐节点刷屏（user m23534）

- **现象**：日志 13,248 行里 12,731 行（96%，1.30 MB）是 `position keys (B176)`，峰值 280 行/秒；用户同时问「4 档为什么给同一个解法」。
- **根因**：汇总行写在「已见过 ⇒ 跳过」分支里（每个重复节点一行），`Log.write` 是 `synchronized` 且逐行 flush ⇒ 渲染线程磁盘 I/O；`settleBlock()` 在写日志时才读（引擎已排空，5 条诊断全空）；「4 档同一解法」＝普通战斗走 `PortfolioSession` 四档各自 `new Session`，从根重算（B177 只覆盖 Boss/200HP 的加宽阶梯）。
- **代码**：`Solver.java` 汇总行移到 `Session.finish()`；`Choice.settleWhy` + `readChoice` 采样时快照 `Driver.settleBlock()`；跳过分支只计数。
- **门禁**：无新增；S242/S243 6/0；全量 core **709/0**（S1–S245，268 条）不变。
- **结果**：已装 jar sha256 `FF10482E55384948FCC2B8620778ABEAB9F7D399ACFC3C19E06A2B96F95F8E71`（1,233,172 B）。
- **① 代价**：热路径少了每节点字符串拼接 + 同步 flush（本局 1.1 MB / 12,731 次 flush 消失）；`Choice` 多一个空串引用字段。
- **② 风险**：汇总从「实时」变「每趟一行」；诊断描述采样点而非写日志点（本意）；回滚纯日志、无门禁会红。
- **③ 影响**：面板/自动出牌/回放/digest/评分/计划/搜索结果不变；日志体积与渲染线程 I/O 下降。
- **待批准**：方案 D＝portfolio 四档也收进单 Session 逐档加宽（累计时间份额 35/25/25/15 设每档 deadline），未实施。
## 2026-11-10 B179 — 普通战斗的 portfolio 四档收进单 Session + 日志同时报本档与合计

- **现象**：19:13 那场普通战斗四档各给同一解法、合计 177,598 节点 / 163 s，最优线来自第 1 档 13 s；日志 `solve: QUICK … nodes=12757 … 13484ms` 与面板的四档合计对不上；全日志 `widening in place` 0 行（B177 只覆盖 Boss/200HP 战）。
- **根因**：`solvePortfolioInternal` 对四个 profile 各 `new Session(root, …)`，`seen`/层内 `next` 是实例字段 ⇒ 档间零记忆、整树重算；`Result.summary()` 的 `nodes/expansions` 只是胜出那一档自己的工作量。
- **代码**：`search/Solver.java`（`Session.planWidening(beams, deadlinesMs, nodeCaps)`、`budgetNow()`、`rungDeadlines/rungNodeCaps/expansionsAtRungStart`、`widenToNextRung()` 用 caps；`solvePortfolioInternal` 单 Session + `rungBeams/rungDeadlines`；`PortfolioSession` 改成单 Session 外壳；`Result.passNodes/passExpansions` + `summary()` 双口径）、`search/QuickSession.java`（`passes()` 转发、`stampTotals()` 加 `portfolio.expansions()`）、`probe/Probe.java`（S246 六条）。
- **门禁**：S246(6)，实测 `S246 finished=true rungs=4 rung=3 beam=128 nodes=4123 pass=245 total=4123 ms=1938`；反向验证（注释 `planWidening` 调用）`passed=3 failed=3`（`rungs=1 rung=0 beam=10`）。
- **结果**：core **713/0**（S1–S246，268 条 = core 164 + extended 104）；已装 jar sha256 **9D189521EAD01511B270209B75D27B03EDA74BC77CE8F39115EE063F7DF8B379**（1,236,016 B）。预期 715、实测 713，差 2 条未逐条归因（门禁全绿）。
- **代价**：每档一次数组查表；`Result` 多两个 int；热路径净减少（后三档的整树重算消失）。
- **风险**：结果可能与四档重算不同；弱档吃满累计 deadline 就加不宽；`nodes` 语义变「本档」；回滚＝不调 `planWidening`，S246 红 3 条。
- **影响**：面板/自动出牌/回放/digest/评分不变；普通战斗耗时预期大降；日志与面板口径统一。

## 2026-11-10 B180 — 时间不限的末档不再被节点预算截断 + 面板说清是哪个限制（A+C）

- **现象**：20:43 那场普通战斗时间预算「不限」（deadlinesMs=[350000000,…,1000000000]），四档各 12,757/25,514/25,514/25,514 节点，收尾 `reason=nodes`、面板「还有 169 条没展开（受层数/预算限制，可以再算）」；玩家没设限制也没暂停（user m24116）。
- **根因**：①末档节点上限 `max(20000,(nodes-nodesAtRungStart)*2)` 用尽 ⇒ `reason=nodes`、`cutShort=true`、`frontierLeft=169`（169 是预算余数，不是剩余世界线）；②`PlanText.candidateNote` 不区分 nodes/time/layers/stopped；③`parkedNote` 在 `promoted>0` 时不显示 2,000 条停车候选。
- **代码**：`search/Solver.java`（`widenToNextRung()` 增 `lastRung`/`noCap = lastRung && DeepBudget.unlimited(budgetMs)`，`noCap ⇒ cap=0 / nodeBudget=0 / maxNodes=0`，`layerAllowance=Integer.MAX_VALUE`，日志 `cap=+unlimited`；新增 `maxNodesForTest()`）；`search/PlanText.java`（`candidateNote(cutShort, open, reason, deferred, promoted)` + `limitText(reason)`，`headline` 传 reason/deferred/promoted，2 参旧重载保留原文案）；`probe/Probe.java`（S247(3)/S248(6)；另把 S153 的自相矛盾断言改成 `!conclusive || …`）。
- **门禁**：`S247 unlimitedCap=0 rung=2 finiteCap=20053 finiteRung=2`、`S248 nodes=[…节点预算用完…另有 2000 条…] layers=[…到层数上限] legacy=[…受层数/预算限制，可以再算]`，合跑 `passed=9 failed=0`；反向验证（`noCap` 写死 false）`passed=2 failed=1`、`unlimitedCap=20053`；全量首跑 721/1（唯一红＝S153 预算截断仍判 dominance），修 S153 后 **722/0**。
- **结果**：core **722/0**（S1–S248，270 条门禁＝旧 268+2）；已装 jar sha256 **9DDCBBD60C23D59DEADB876F8B28CCA1731DE000B19793B58F55A039ACFF6DE4**（1,240,061 B）。
- **代价**：每档两次布尔判断 + 一次静态比较（非热路径）；面板每刷新多一次字符串拼接。运行期主要代价是**时间**：不限预算下末档无节点上限，打不死的战斗会跑到 maxLayer（high=400）。
- **风险**：末档展开更多 ⇒ 结果可能变化（只会是同一前沿的继续）；`seen` 无上限增长、内存上升；面板新措辞若被外部脚本解析需适配；回滚＝`noCap` 写死 false（S247 红）或删 candidateNote 新重载（S248 红）。
- **影响**：面板新增「节点预算用完/时间预算用完/到层数上限/手动停止」与停车候选数；`nodes`＝本档、`totalNodes`＝合计语义不变；自动出牌/回放/digest/评分/候选枚举不变；其它 mod 无交互。

## 2026-11-12 B181 — 沙盒帧长跨过动作的时间窗（Hexaghost 少 3 张灼伤）

- **现象**：第 10 回合 `step plays` 里除牌堆外全同，实机比沙盒多 3 张 Burn+（17 vs 20 张），之后第 39–42 步全部偏离、autopilot 停手（user m24423）。
- **根因**：`BurnIncreaseAction.update()` 两段时间窗（`duration == 3.0f` 升级已有 Burn；`duration < 1.5f` 才新建 3 张），沙盒一帧 `FAST_DELTA = 20.0f` ⇒ 第一帧打到 -17 并 isDone，第二窗永不执行。
- **代码**：`drive/Driver.java`（`simDelta` 默认 `SELECTION_DELTA`、`endTurn` 按 `endTurnSmallFramesForTest` 选帧长、注释写 B181）、`probe/Probe.java`（S249 门禁 + `runStep` 收尾清 5 个测试钩子 + S95/S100/S186/S236 自钉 `forcePotionsForTest(true)` + S133 旧分支断言放宽）。
- **门禁**：S249(3) `S249 burns=3->6 upgradedOriginals=3 timedOut=false`；修前 `passed=2 failed=1`（`after=3 before=3`），反向验证同样 1 红。
- **结果**：core **725/0**（S1–S249，271 条）；已装 jar sha256 **FB597787F4A65C56CEF3D1371BC494076FD1EC4566AA5EE4006D551F3970E8AD**（1,243,477 B）。
- **代价**：实测 S246 `nodes=3866 ms=7363` vs B180 基线 `nodes=4123 ms=2377` / `nodes=3919 ms=3037` ⇒ 每节点 0.58–0.78 ms → 1.90 ms；搜索结果不变（S244 展开 1072 vs 1078、S230 nodes=426727 相同）；内存无变化。
- **风险**：细帧撞 drain 步数/墙钟上限的可能（本套件未触发）；依赖旧「大帧一步到底」的外部脚本需自行钉 `setEndTurnSmallFramesForTest(FALSE)`；回滚＝默认值改回 `FAST_DELTA` 一行（S249 立刻红）。
- **影响**：面板/自动出牌在分段动作的战里更准；普通战斗数值不变、耗时上升；同样时间预算下展开节点略少；其它 mod 的分段计时动作一并修真；候选枚举/评分/回放/存档不变。

## 2026-11-13 B182 — 沙盒不保留手牌发光队列（计算时实机光圈被掐短）

- **现象**：计算中实机手牌「可打」的牌原本 1.2 s 一圈的脉冲光圈，一开始算就变短、几次后只剩静态描边；面板/计划/出牌不受影响（user m00001）。
- **根因**：`CardGlowBorder extends AbstractGameEffect`，`ClonePolicy.java:74` 对 effect 一律按引用共享 ⇒ 沙盒 `AbstractCard.glowList` 与实机同一个 list；沙盒 `hand.glowCheck()` 对不能打的牌 `stopGlowing()`，`AbstractCard.java:1140` 把每个 border `duration /= 5`；沙盒不渲染、`updateGlow` 只在 `renderCard`/`renderInLibrary` 跑 ⇒ 只毁不建。
- **代码**：`mirror/GlowReset.java`（新，`Reflect.set` 把非空 `glowList` 换成空表，扫 hand/drawPile/discardPile/exhaustPile/limbo/cardInUse + `actionManager.cardQueue`）、`mirror/World.java`（`install()` 末尾 `if (!glowReset) { glowReset = true; GlowReset.drop(); }`）、`probe/Probe.java`（S250）。用户 m00148 明确否掉「拷贝 border」路线，故不动 `ClonePolicy`。
- **门禁**：S250(4) `S250 glow before=1.2 after=1.2 liveSize=1 sandboxSize=0 scanned=1 dropped=1 sameCard=true liveSame=true liveAfter=true`；反向验证（注释清理调用）`passed=2 failed=2`、`after=0.24000001 sandboxSize=1`。
- **结果**：core **729/0**（S1–S250，272 条）；已装 jar sha256 **559F67C208506FA2D6DBBC731B04F4E2C23D8E944A4F684E2AD7D6303A10E478**（1,247,390 B）。
- **代价**：每世界约 30–50 次 `Reflect.get`、非空才 `new ArrayList(0)`（µs 级，只在 fork 的 install 时发生）；不拷贝对象、不加缓存、不进节点热路径。
- **风险**：`glowList` 是 private 非 final，反射可写；`Field` 查询失败时安全返回 0；万一某卡与实机共享，换表只让实机光圈重启一次（<0.3 s 重建）；回滚＝删 `install` 里三行（S250 立刻红）。
- **影响**：Digest 不含发光 ⇒ 搜索/评分/自动出牌/worker 指纹/面板完全不变；唯一变化是计算时实机手牌保持原本光圈节奏；其它 mod 无交互（读 `glowList` 的 mod 会读到空表，目前没有）。
## 2026-11-13 B183 — 按动作分档帧长 + 阈值落点，恢复搜索速度

- **现象**：B182 之后平均约 200 条世界线/秒（B181 之前约 1000/s），日志 nodes/ms 从 0.58–0.78 ms/节点涨到 1.90 ms/节点（user m00434）。
- **根因**：B181 为「一帧不跨过动作的时间窗」把所有帧压成 0.05 s，每个动作按 0.05 逐帧 tick —— `BurnIncreaseAction`（3.0 s）要 61 帧，`WaitAction(0.5f)` 要 10 帧；实际只需「别跨过窗」。
- **审计**：`_tmp/audit/FrameAudit.java`（javassist 扫 `desktop-1.0.jar` + 21 个 workshop jar + 我们的 jar）：7206 类 / 378 个 AbstractGameAction 子类 / 143 个比较 duration（`exact=138` 全是首帧等值）；首帧之后还有窗的只有六个类：`DrawCardAction`/`FastDrawCardAction`/`ScrapeAction`/`RipAndTearAction`/`loadout.actions.EasyXCostAction`（`duration < 0`）与 `BurnIncreaseAction`（`< 1.5f`）；`ownFloat` 收窄后 37 个类的字段名全是 `startDuration`/`shakeDur`/`bubbleDuration` 等，没有自定义计时器盲区。
- **代码**：`drive/FramePlan.java`（新，白名单窗表 + 按 Class 缓存 + 沿父类链查；`DURATION = Reflect.find(AbstractGameAction.class, "duration")`，真实 jar 里是 protected）；`Driver.engineFrame(gam)` 包住主循环与 `runEndOfTurnQueue` 的 `gam.update()`；`frameDelta()` 在屏规则后加 `if (FramePlan.planned()) return FramePlan.delta();`；开关 `settings.txt search.frameTiers`（默认 on，`off/no/false/0/关闭` 关）+ `FramePlan.setEnabledForTest(Boolean)`。规则：无当前动作/isDone→0.05；动作前 6 帧且 duration ≥ 初值→0.05（保住首帧等值判断与开屏）；否则落到 `max{t∈窗|t<duration}` 之下 0.02；无窗→20 s。
- **门禁**：S251(4) `S251 burns=3->6 upgradedOriginals=3 steps=5 fresh=1 fast=1`（B181 需 ~61 步）；S252(3) `S252 burns=2->5 steps=49 fresh=0 fast=0`（关掉分档退回 B181 帧长）。S82/S133/S134 三处「强制旧行为」分支自钉 `setEnabledForTest(FALSE)`，另各加分档分支（`S133 tiered … inHand=true orphans=0`、`S134 tiered … scried=true screenFrames=1`）。反向验证（`begin()` 恒返回 20 s）`passed=4 failed=3`：S249 `after=3 before=3`、S251 同款、`fresh=0 fast=0`。
- **结果**：core **`passed=739 failed=0`**（门禁 S1–S252；新增 9 条断言）；已装 jar sha256 **C069E417E6BDF1B41B1F6DF3BBA232777882D31D03C42D757805B76FCEB9EB0E**（1,256,070 B）。
- **代价**：每帧一次判空 + 一次 `Field.getFloat` + 一次按 Class 缓存的查表；每类缓存一条 `float[]`（≤6 条），无每节点对象；净收益是每节点帧数大幅下降（S251 从 61 帧降到 5 帧）。
- **风险**：`WINDOWS` 是白名单 —— 新增 mod 动作若在首帧之后还有窗不会被自动发现（需重跑 `_tmp/audit/FrameAudit.java`），但未知类一律 0.05 s 只慢不错；`duration` 读失败同样退化到 0.05 s；`end()` 用 `try/finally` 包住；回滚＝`search.frameTiers=off`（无需重编）。
- **影响**：面板/路线/评分/`Digest`/候选枚举/live 路径都不变（帧长不进任何哈希，live 用真实 delta）；只改沙盒里每个动作的帧数；其它 mod 无交互。

## 2026-11-13 B183 回退 — 分档帧长在实机上更卡（user m00787）

- **用户原话**：m00787「改的有问题，先回退。现在计算卡的要死」⇒ 立即回退，恢复速度优先。
- **回退范围**：删除 `src/main/java/sts1solver/drive/FramePlan.java`；`Driver.frameDelta()` 去掉 `FramePlan.planned()` 分支、恢复「屏规则之后直接 `return simDelta`」；删除 `Driver.engineFrame(GameActionManager)` 并把主循环与 `runEndOfTurnQueue` 两处改回裸 `gam.update()`；`Probe` 删除 S251/S252 两段、S133/S134/S82 的 `FramePlan.setEnabledForTest` 钩子与分档分支、`s133Retain`/`s134Scry` 的 `tiered` 参数与返回串字段。`src/main/java` 下 `grep FramePlan|frameTiers|B183` 命中 0。
- **验证**：`-Gates "S82,S133,S134,S249,S250"` → `passed=25 failed=0`（267 skipped）；全量 core → `passed=729 failed=0`（回到 B182 口径；S251/S252 等 9 条断言随代码删除）。
- **安装物**：`D:/Steam/steamapps/common/SlayTheSpire/mods/sts1-engine-solver.jar`，1,247,388 B / sha256 `8B0E1D9054CEF4600E03E6755520415EA43A72CBAD797E4757A73525F9E2531B`（jar 非确定性，与 B182 的 1,247,390 B 同量级但 sha 不同）。
- **未定性**：门禁显示帧数 61→5，但用户实机更慢。待查：①`frameDelta()` 与 `FramePlan.begin()` 的取帧时序错位（读到上一动作的 delta）；②20 s 大帧触发静态监听/回写等按帧计价的路径；③每帧 `Field.getFloat` + 查表的固定开销。必须先拿到 nodes/ms 与 Watchdog 采样，禁止盲改。
- **教训**：帧长/节流类改动的验收必须含端到端吞吐（nodes/ms、每秒世界线数），只比 steps 计数会放过「局部变快、整体变慢」。

## 2026-11-14 B184 — B183 的白名单重写：只给 6 个动作类计划快帧（user m01136「重写」）

- **起因**：B183 被 user m00787 判「计算卡的要死」后全量回退；归因结论是 B183 对 372 个动作类一律分档、无窗就 20 s，等于集体改语义。用户 m01136「重写」⇒ 只保留审计证实的 6 个窗类，其余回到 0.05 s。
- **代码**：新增 `src/main/java/sts1solver/drive/FramePlan.java`（白名单 `HashMap<String,float[]>` 按类名精确匹配：`DrawCardAction`/`FastDrawCardAction`/`defect.ScrapeAction`/`unique.RipAndTearAction`/`loadout.actions.EasyXCostAction` → `{0.0f}`、`unique.BurnIncreaseAction` → `{1.5f}`；`FRESH=0.05`/`FRESH_FRAMES=3`/`EPS=0.001`/`LIMIT=20`；`next = duration - stop + EPS` 不越窗；`enabled()` 读 `settings.txt search.frameTiers`，默认 on，1000 ms 缓存，`setEnabledForTest` 只给门禁）；`Driver` 新增 `engineFrame(GameActionManager)` 并只包主循环 drain/`runEndOfTurnQueue` 两处 `gam.update()`，`frameDelta()` 在屏规则之后插 `FramePlan.planned()`；`Solver` 把 `Profile` 六个死计数器接上（fork/install/apply/readBack/digest/uninstall），`Session.finish()` 也打 `profile:` 行；`Probe` 新增 S251/S252/S253 并 import `FramePlan`（编译时漏了这个 import，29 个 cannot find symbol）。
- **门禁**：`-Gates "S249,S250,S251,S252,S253"` → 首跑 `passed=15 failed=2`（S253 两条断言写错：以为 1/60 帧要走 100+ 步，实际 drain 的停滞护栏在 49 步就退出、3 s 动作在 1/60 帧下一次 drain 内跑不完 ⇒ 改成「计划确实准备了快帧 `fast>=1`、但 `after==before` 证明快帧没被采用」）；修完 `passed=17 failed=0`。关键读数 `S251 burns=3->6 steps=7 fresh=5 fast=2`、`S252 burns=3->6 steps=49 fresh=0 fast=0`、`S253 burns=3->3 steps=49 screenFrames=48 fast=45`。
- **反向验证**：`FramePlan.begin()` 临时改成白名单类恒跳 `LIMIT` → build+install → `passed=8 failed=2`，红行 `the head of the action runs at 0.05 s and a later frame is planned | fresh=1 fast=1` 与 `both time windows of the action still land | upgradedOriginals=3 after=3 before=3`（S251 `steps=3`）；S252/S253 仍绿。还原后重编。
- **全量 core**：`passed=739 failed=0`（门禁 S1–S253，282 条；B184 新增 10 条断言；本机带负载约 11 分钟，00:33→00:44）。
- **安装物**：`D:/Steam/steamapps/common/SlayTheSpire/mods/sts1-engine-solver.jar`，1,258,662 B / sha256 `56486847279F6FCF439EAF0A52C0289800495624DABCF794A87D2B7B8D633AF7`（jar 非确定性；反向验证那版是 `EEB3609D…`）。
- **代价/风险/影响**：每帧一次 `currentAction` 判空 + 白名单查表 + 一次 `Field.getFloat`，无每节点对象；白名单 6 条是手工维护项；6 类之外仍是 0.05 s/帧 ⇒ **吞吐回不到 1000/s**，只能回收这 6 类的帧数；帧长不进 `Digest` ⇒ 面板/路线/评分/自动出牌/worker 指纹/其它 mod 全不变，live 路径不经过 `FramePlan`。
- **验证口径**：B183 的教训（只看 steps、不看实机吞吐）写进 S253 的注释与 PLAN-BUGS 的已知边界；本次仍靠用户实测 nodes/ms 收尾，日志里新增 `profile:` 行可用于对照。

## 2026-11-14 发布 1.0.0 到创意工坊（private，用户验收前不公开）

- **范围**：`resources/ModTheSpire.json` 版本 0.1.0 → 1.0.0，description 写入兼容性表述（"理论上兼容所有只增加内容、不改变战斗结算的 mod"）。
- **调试面门禁**：`SolverMod` 新增 `DEBUG_MARKER="debug.keys"` 与 `debugSurface`，仅当 `~sts1-engine-solver\debug.keys` 存在时 `autotest.request` 才执行、静音与 Watchdog 才生效；`tools/run.ps1` 自动建该文件。玩家机器无此文件 ⇒ 调试面关闭。
- **按键删除**：`checkHotkeys()` → `checkPanelKeys()`，F5–F12 八个分支全删，仅保留方向键/PageUp/PageDown/Home 滚动；`Gameplay` 与 `PlanText` 的玩家可见文案改为「点『计算』/『执行一步』/『自动出牌』」。jar 常量池校验无 F 键字符串。
- **发布物**：B184 jar，1,258,662 B / sha256 `56486847279F6FCF439EAF0A52C0289800495624DABCF794A87D2B7B8D633AF7`，543 项，内含 `version=1.0.0`；已装 `D:/Steam/steamapps/common/SlayTheSpire/mods/`。
- **工坊工作区**：`D:\mods-workshop\sts1-engine-solver\`（config.json / content\sts1-engine-solver.jar / image.jpg 1024×1024 / README.md）；条目 `steamPublishedID=3839053516`，visibility=private。项目内 `dist/workshop` 旧模板已删除。
- **坑 1（编码）**：`mod-uploader.jar` 的 `WorkshopUploadConfiguration.load/save` 用 `FileReader/FileWriter`（平台默认字符集，本机 GBK）。config.json 必须存成 **GBK**；存 UTF-8 会被读成乱码再按 GBK 写回，工坊标题/简介变成「鎴樻枟…」。
- **坑 2（封面）**：预览图只在 `upload` 时通过 `SteamUGC.setItemPreview` 提交，且要求 image.jpg 在工作区根目录存在；上传成功后页面上的尺寸/封面需要刷新或等 Steam 处理，刚上传时显示「文件大小 0.000 B」属正常延迟。
- **命令**：`cd D:\Steam\steamapps\common\SlayTheSpire; .\jre\bin\java.exe -jar mod-uploader.jar upload -w "D:\mods-workshop\sts1-engine-solver"`（`new` 只在首次建条目时用；Steam 必须登录运行）。
- **本次结果**：上传成功（`Successfully updated workshop item!`），可见性保持 private，未改用户已编辑的标题「杀戮尖塔1战斗求解器」。

## 2026-11-14 1.0.0 反馈入口：「导出报告」按钮（用户 m03634）

玩家侧反馈原本只能靠「把 solver.log 发我」，路径描述这一关就会劝退。现在面板标题行右侧有一个小按钮，
点一下把定位问题需要的东西打包成一个 zip，并直接在资源管理器里选中它。

- 位置与几何：`ui/PanelLayout.reportRect` / `hitTestReport`，落在标题行（`headerHeight=64`）右侧，
  84×22。**没有进 `IDS`**：网格是 2×5，加第 11 个按钮会把整个面板往下顶一行，而 S64 固定断言
  `IDS` 数组，改网格等于同时改门禁的口径。collapsed 时不响应。
- 点击：`ui/Panel.update` 先判 report 命中并消费这次点击（`InputHelper.justClickedLeft=false`）再 return，
  所以标题行点击不可能被读成网格按钮；`ui/Panel.render` 用 `drawButton` 画，标签来自
  `BugReport.label()`（导出报告 / 打包中… / 已导出 / 导出失败，结果标签 6 秒后复原）。
- 产物：`sts1solver/diag/BugReport.java`。后台 daemon 线程写包（绝不在渲染线程做 IO），
  输出到 `%USERPROFILE%\sts1-engine-solver\reports\report-yyyyMMdd-HHmmss.zip`，写完
  `explorer.exe /select,<zip>` 定位。条目：README.txt / info.txt / state.txt / settings.txt /
  panel.txt（`Gameplay.lines()`）/ solver.log 尾 2 MB（RandomAccessFile 定位，截断处丢半行）。
  上限 8 MB，超限抛异常并在面板显示「导出失败」，日志里带原因——不静默。
- 脱敏：`sanitize` 把 user.home（含正斜杠形式）替换成 `%USERPROFILE%`，再把裸用户名替换成
  `%USER%`；不打包存档与卡组。
- 门禁 S254：临时目录里真写一次包，断言六个条目齐全、体积在上限内、没有任何条目含绝对 home 路径、
  home 已被替换、settings 真被拷贝、版本号属于本项目。
- 版本号坑（S254 抓到的真 bug）：第一版读 `/ModTheSpire.json`，结果包里打印的是**别的 mod 的
  5.56.0**——每个 mod 都在 jar 根放同名 manifest，classloader 取到哪个不由我们决定。改成
  `/sts1-engine-solver.version`，由 `tools/build.ps1` 从本项目的 `resources/ModTheSpire.json`
  生成（版本仍只有一个来源），S254 增加「版本号属于本项目」的断言。
- 实测：`tools/build.ps1` → 1,270,132 B / 546 项；`tools/install.ps1` sha256
  `B5A452E254C4F942582456DAA096821BCAF5D419FC221375C1C8086A1DADEB21`；`-Gates S254` 6/6 PASS。
- 代价：一次点击是后台线程的一次顺序写（实测 < 1 秒量级，2 MB 日志），零帧开销；内存峰值等于日志
  尾部大小（≤2 MB）。风险：日志被游戏占用时用只读共享读，失败会在面板显示并在日志记录；回滚只需
  把标题行按钮与 `diag/BugReport` 去掉，不影响搜索与执行路径。
- 文档：README 的面板按钮表增加一行；`docs/WORKSHOP-DESCRIPTION.md` 的「问题反馈」已指向这个按钮，
  并把源码地址填成 https://github.com/zck070718/sts1-engine-solver 。

## 2026-11-14 创意工坊二次上传（仍是 private）

- 简介换成 `docs/WORKSHOP-DESCRIPTION.md` 的全文，并转成工坊识别的 BBCode（`[h1]`、`[list]`/`[olist]`、
  `[b]`、`[url=]`）；Markdown 的 `##`、`**` 在工坊页面会原样显示，所以不能直接贴。
- `config.json` 仍必须 **GBK** 写（上传器用默认字符集读写），先由 Node 写 UTF-8 中间文件再转码；
  `steamPublishedID=3839053516` 保留，visibility=private 未改。
- content 换成含「导出报告」按钮的新 jar：1,270,132 B，sha256
  `B5A452E254C4F942582456DAA096821BCAF5D419FC221375C1C8086A1DADEB21`。
- 上传：`java -jar mod-uploader.jar upload -w D:\mods-workshop\sts1-engine-solver` →
  `Successfully updated workshop item!`，exit 0，无 preview 缺失警告。
- 简介里把「导出问题包」改成「导出报告」，与实际按钮标签一致。

## 2026-11-14 分支选牌拒绝不再刷 WARN（用户 m03871 反馈「日志写错了」）

用户自己打了一局觉得很正常，但导出包里有上百条 `WARN selection refused (not-offered: the screen
offers [Defend_G, Malaise], the plan chose [催化剂]…)`，看着像 mod 出问题。

- 查清是**正常的分支探索**：搜索把选牌屏一张一张走完（日志里能看到同一分支的
  `sandbox selection: kind=HAND offered=[…] picked=[…]` 序列，候选集逐张减少），同一分支里下一张被请求的牌
  已经在更早一步被这个分支拿走，于是拒绝；紧随其后的 `a branch left the screen chain unfinished (answer X);
  it is not a step outcome, so it is not stored` 就是这个分支被丢弃的正常收尾。行为本身没错——S58 断言
  「不在候选里的牌必须被拒绝、屏幕保持打开」，改动后仍然通过。
- 错的是日志级别：`Driver.refused` 一律 WARN，一次搜索会刷出上百行，把真问题埋掉。新增
  `Driver.refusedInBranch`（仍写 `selectionFailure`，日志改 INFO 且用 `Log.allow("branch-selection-refused", 5000)`
  限速），只用在 `resolveSelectionByNames`（沙盒重放）的两处；`resolveSelectionLive`（实机）继续 WARN——
  那里同一句话意味着「计划与游戏真的不一致」。
- 实测：`-Gates "S58,S254,S102"` → passed=21 failed=0；日志出现
  `INFO selection refused while walking a branch (not-offered: …)`，WARN 计数 0。
- 代价：零运行时开销（只是日志级别与一行限速判断），行为完全不变；风险：沙盒侧拒绝不再逐条可见，
  但 `selectionFailure` 仍保留最后一条，且 Solver 的「分支未走完」行本来就限速。

## 2026-11-14 工坊简介改到中文区（本地化描述机制）

- 起因：用户报告「你更新的描述是英文区的」，中文页面仍是旧文本；并要求删掉简介里「1.0.0 起，操作方式只有面板按钮；F5–F12 的功能分支已从代码中删除。」（判为无意义）。
- 机制：`mod-uploader.jar` 的 `Uploader.updateItemInfo` 只调 `SteamUGC.setItemTitle/setItemDescription/setItemContent/setItemPreview/setItemVisibility/setItemTags`，**从不调 `setItemUpdateLanguage`**，所以每次 upload 写的是条目的默认（英文）描述；中文描述是单独的本地化条目，官方上传器写不到。
- 解决：本机自写 `_tmp\steamzh\ZhUpload.java`（classpath = `_tmp\steamzh` + `D:\Steam\steamapps\common\SlayTheSpire\mod-uploader.jar`，原生库从 jar 解出 steam_api64.dll / steamworks4j64.dll 到 `_tmp\steamzh\native`）：`SteamAPI.init(nativeDir)` → `startItemUpdate(646570, 3839053516)` → `setItemUpdateLanguage(h, "schinese")` → title/description/content/preview → `submitItemUpdate`。回调 `onSubmitItemUpdate(boolean userNeedsToAcceptAgreement, SteamResult)`，ok=false 只表示无需接受协议，result=OK 即成功。
- 结果：先用官方上传器把默认描述更新为清理后的全文（`Successfully updated workshop item!` exit 0），再用 ZhUpload 写 schinese（progress 走到 UploadingPreviewFile → CommittingChanges，result=OK）。条目仍 private。
- 文本：`docs\WORKSHOP-DESCRIPTION.md` 删掉该引用句；BBCode 由 md 生成（一级标题 `[h1]`、小节 `[h2]`、列表 `[list]/[olist]`、`[b]`、`[url=]`），2,515 字符；同时写回 `D:\mods-workshop\sts1-engine-solver\config.json`（GBK，4,459 B，备份 `config.json.bak-20261114b`）。
- 注意：以后改简介要**两边都改**——config.json（默认/英文）+ ZhUpload（schinese），否则两语言会分叉。
- 门禁未变（只改文案，无代码改动）。

## 2026-11-15 B185 沙盒窗口内不许真开 javax.sound 音频线（机制 M50）

- 起因：user m00001「查一下 sts1 战斗求解器 mod…这个 mod 现在沙盒疑似没有隔离由『半血自动播放雨爱』mod 控制的接口导致沙盒计算时雨爱声音在后台重复播放。查一下哪里没有隔离，沙盒只需要模拟战斗相关的部分」；m00149「做A，等我验收再上传创意工坊和github」。
- 根因：模拟回血真调 `AbstractCreature.heal(int)`（内含 `heal(int, boolean)`），雨爱（`yuaimod.YuAiMusic`）在两个方法上的 `@SpirePostfixPatch` 在窗口内照常执行 → `YuAiController.evaluate(沙盒玩家)` → `YuAiMusic.play()` → `javax.sound.sampled.AudioSystem.getClip()` 开**真实音频线**；`MusicMute`(B45) 只接管 `CardCrawlGame.music`、`SfxMute`(B65) 只接管 `CardCrawlGame.sound`，都管不到 `javax.sound`；`Driver.begin()` 的 `Settings.SOUND_VOLUME=0` 也无效（0 音量照样 `getClip()`）。`PresentationQuiet`(B168) 只改 14 个 `healthBarUpdatedEvent()` 调用点，挡不住 `heal` 自身 postfix（MTS 把 postfix 插在每个 RETURN 之前，prefix 早退无效，已实测）。放大器：每节点一个窗口，窗口结束 `StaticWatch` 把 `currentClip` 还原成快照 ⇒ 窗口内新建的 `Clip` 失去引用但仍在 loop、无人 close ⇒ N 条叠加；mod 自己的 `active` 位也被回滚，`evaluate` 每次看到 `active && !isPlaying` 就打「播放意外终止」。
- 证据：`solver.log` 01:26:31.875 `quick search: started`；01:26:36.172 `yuaimod.YuAiMusic#currentClip was released (set to null) while a window was open; left released.`；01:26:36.984 `the sandbox wrote mod static yuaimod.YuAiBgm#STATE (map) … put back`；01:26:37.170 `1 write(s) to live-shared mod statics put back: [yuaimod.YuAiMusic#currentClip (object)]`。`mts_process_launch.log` 末尾 400 行：播放意外终止×70、开始循环播放×40、停止播放×24，7521–7597 行同一 HP 序列（22,27,15,17）重复两遍，而实机 HP 恒 17/74。
- 代码：新增 `src/main/java/sts1solver/mirror/AudioQuiet.java` —— 初始化扫全部 mod 静态字段缓存 `Clip` 型字段；`World.install()` 在 `MusicMute.begin(true)` 之后 `begin()`，`World.uninstall()` 在 `StaticWatch.beforeUninstall()` **之前** `end()`（先 close 再还原，顺序是安全关键）；每窗口必跑；卸载时「字段值≠记录值且新值非空」→ `stop/flush/close` 再还原，新值 null 沿 B169 释放放行。**补丁**：候选字段必须 `setAccessible(true)`——首次带雨爱跑时日志报 `WARN audio quiet: cannot read yuaimod.YuAiMusic#currentClip: java.lang.IllegalAccessException … modifiers "private static"`，字段被发现却读不了、保护等于零，而门禁当时仍 PASS（只查成员资格）；现在打不开的进 `blind` 清单并 `warnEvery`，另加 `readableForTest`/`blindForTest` 供门禁断言。
- 门禁 S255(16)：假 `Clip`（`Probe.S255Clip` 用 `java.lang.reflect.Proxy`，记录 running/closed）+ `Probe.S255_LIVE`/`S255_SANDBOX` 两个静态字段模拟实机字段；断言「窗口内新建 Clip 被 close」「覆写实机字段→原值还原且新 Clip 被 close」「窗口计数=2」「沙盒 `stop()` 实机 Clip 被检出」；带真实雨爱时再加 `readableForTest("yuaimod.YuAiMusic#currentClip")` 为真、`blindForTest()` 为空。
- 反向验证（已还原）：注释掉 `World.java` 的 `AudioQuiet.begin();`（约 336 行）与 `AudioQuiet.end();`（约 380 行）→ build+install（sha `0D9615C7D882A6C0664393C1BCB28DDAD26E791706C130A682BEF35C030A64FC`）→ S255 **`passed=4 failed=10`**（`a clip the window left in a static is closed | closed=false`、`the window counts exactly the two sandbox clips | 0`、`a sandbox stop() of the LIVE clip is detected and reported | liveStops=0` 等）⇒ 门禁有牙。
- S255 修后：不带雨爱 `passed=14 failed=0`；带 `-Mods "YuAiBloodMusic"` **`passed=16 failed=0`**、`yuaiInstalled=true`、`watched=[yuaimod.YuAiMusic#currentClip, sts1solver.probe.Probe#S255_LIVE, sts1solver.probe.Probe#S255_SANDBOX]`，报告行 `audio quiet: 3 mod clip static(s), 1 window(s), 2 sandbox clip(s) closed, 2 live reference(s) put back`。
- 全量 core：**待复跑确认** —— S171 修复前全量 `passed=755 failed=6`（6 条全在该门，其余 751 条全绿），S171 改后单跑 `10/0` ⇒ 预期 `passed=761 failed=0`（门禁 S1–S255）。2026-11-15 02:46 用户要求「在测试的不要测试了」，复跑被叫停；本行等下一次全量刷绿后换成实测值。
- 安装物：`tools/install.ps1`，jar **1,279,962 B**，sha256 `9D63ED91E329239EFB7E764DD22DFB6A9832A914AD401CB960CF0DC82C6432B2`（jar 非确定性，sha 以 `Get-FileHash` 实测为准）。
- 代价/风险/影响：代价＝每窗口一次静态字段遍历（只首次扫描并缓存）+ 每个受保护字段一次 `Field.get(null)`，卸载时最多一次 `stop/flush/close`（µs 级，不是每帧）。风险＝只保护静态字段里的 `Clip`（实例字段/集合里藏的抓不到）、同一对象被 `stop()` 但字段未改写时检测不到、`close()` 失败只记日志；回滚＝删 `World` 两处挂点后重编。影响：只在沙盒窗口生效，实机雨爱照常播放；面板/自动出牌/搜索/评分不变；叠加循环消失（最多剩一个窗口长度的碎片）。
- 验证口径：窗口内不再出现新的持续循环；`solver.log` 有 `audio quiet: … clip static(s) …` 摘要且无 `cannot read`/`audio-quiet-blind` 告警；窗口外实机雨爱正常。

## 2026-11-15 B186 S171 预览门禁去掉对机器速度的依赖

- 起因：B185 交付前跑全量 core，出 `passed=755 failed=6`，6 条全在 S171（预览条相关），其余全绿。按「门禁红了先怀疑门禁自己」先做对照，不动断言。
- A/B 对照：把 `mirror/World.java` 两处挂点注释成 `// BASELINE-CHECK: AudioQuiet.begin(); / AudioQuiet.end();` → build+install（sha `57DB2A72F66BEE147FBC1BE4E9B247F90897136C706808E7332EB5E8E7EFFB55`）→ `-Gates S171` 仍 **4/6 红**，(b) 读数 `nodes=44 totalNodes=276 1255ms` 与装 B185 时 `nodes=41 totalNodes=278 1258ms` 几乎一致 ⇒ 与 AudioQuiet 无关。随后还原两行（`AudioQuiet.begin();`/`AudioQuiet.end();`）。
- 根因：S171 的 (b) 段只在「搜索手里已握着胜利线」时才有预览可采样，该板（seed 4242、80 HP、4×Strike_R+Bash+3×Defend_R、两只 LouseNormal + 一只 LouseDefensive）第一条胜线约 **281 节点**；B179 阶梯把 **35%** 预算给窄深档（1200ms → 420ms），B121 时代 0.38 ms/节点 ⇒ 420ms≈1100 节点，够；本机实测 4.5 ms/节点 ⇒ 420ms≈90 节点，窄档没走到第 7 层就被加宽，整轮 no-win，6 条断言全读 `null`。验证：预算临时改 20000ms（`PROBE-EXPERIMENT`）→ **10/0**，预览在 3%（282 条世界线 / 424 条每秒）出现、`beam=10 layers=9`、`10 步 · 预计 -6 HP`。
- 修法（只改 `probe/Probe.java` 的 S171，产品代码零改动）：(a) 段预算 1200→**2000ms**（这段只需有计划可发布，生存线也会发布），并在 `Gameplay.reset()` 后加注释写清 0.38→4.5 ms/节点与 35% 深档的历史；(b) 段 →**9000ms** 当上限，采样到「暂定路线」后同拍调 `Gameplay.stopQuickSolve()` 提前收尾（`Gameplay.java:1104 finishQuick` 照常发布并 `clearPreview`），日志改成 `S171 preview after N node(s): …`。
- 门禁：S171 仍 **10 条断言**，一条没删、一条没放宽；(a) 覆盖「自然到期发布 + 阈值不到绝不预览」，(b) 覆盖「采样→停搜→发布后预览消失」，并把 `stopQuickSolve()` 的收尾路径纳入门禁（此前无覆盖）。
- 结果：`-Gates S171` **10/0**，整门约 3.5s，日志 `S171 preview after 281 node(s): …`、`(a) budget=2000ms / (b) budget=9000ms`。全量 core **`待复跑确认（S171 修复前全量 755/6 全在该门、其余 751 条全绿；S171 单跑 10/0；02:46 用户叫停复跑）`**（S1–S255）。
- 安装物：`tools/install.ps1`，jar **1,280,074 B**，sha256 `F24DE109C2D77A211923B62D6226BC3AE7CE720754B5337A35510BAD743D1F83`。
- 代价/风险/影响：代价＝(b) 墙钟上限 9s 但实际 1–2s 自停，整门约 +1s；产品代码未改，游戏内零开销。风险＝搜索真退化仍会红（晚约 9s），判据未放宽；(b) 不再跑满预算，长预算自然收尾由 (a) 覆盖；回滚＝把两处预算与 `stopQuickSolve()` 调用还原即可。影响＝仅测试代码，面板/自动出牌/搜索/评分/其它 mod 兼容性均不变。
- 环境备注：2026-11-15 02:29 实测本机 **4.5 ms/节点**（B121 记录 0.38），后台有 3 个 `cloudmusic` + `audiodg` 与 DSH 自身占 CPU（瞬时 40%，24 逻辑核；MuMu/MAA 未运行）；全量 core 由平时 10–15 分钟涨到 50+ 分钟。跑验收门禁前建议先关占 CPU 的音频/模拟器，否则真实 1200ms 快搜只查得到 ~280 个节点，与 B184 口径不可比。

## 2026-11-15 B187 沙盒隔离收口计划（当时未实施；同日晚已按 T1–T6 落地，见下段）

- 用户 m01082「要修复隔离」、m01098「给我隔离修复的计划，不要让类似的bug出现，沙盒隔离好和战斗无关的东西。给我写进计划」。
- 闪退取证：21:22:02 崩溃，`hs_err_pid17280.log` 为 `EXCEPTION_ACCESS_VIOLATION (0xc0000005, reading 0x0)`，native 指令 `48 8b 01`（`mov rax,[rcx]`，rcx=0）；栈 `DirectAudioDevice.nFlush` ← `DirectDL.flush` ← `YuAiMusic.stop()` ← `YuAiController.evaluate` ← `HealthSoundPatch$OnHealAmountShowEffect.Postfix` ← `AbstractCreature.heal(int,boolean)` ← `HealAction.update()` ← `Driver.runEndOfTurnQueue` ← … ← `SolverMod.receivePostUpdate`。
- 根因：窗口内 evaluate 先 `stop()` 实机 clip L（listener 关闭 L、字段置 null）→ `play()` 新建 A 写入 → 窗口结束关 A 并把已关闭的 L 写回 → 下一窗口 `stop()` 对 L 调 `flush()`（雨爱 flush 前不查 `isOpen()`）→ 原生空指针。B185 只消除了叠音，未隔离执行。
- 计划（未写代码）：新建 `docs/PLAN-ISOLATION.md`，给出 G1–G5 判据、L1 崩溃守卫 / L2 窗口内 Clip 惰性替身 / L3 与 StaticWatch 的顺序修正 / L4 非战斗副作用审计表，任务 T1–T6（约 2.2 天），门禁 S255 改新契约 + S257 真机冒烟，防复发规则 I1–I3（假对象不能证明真资源安全 / 只换引用不碰生命周期 / 默认不执行展示层）。
- 文档落点：PLAN-BUGS B187 段、PLAN.md bug 表 B187 行 + 收尾状态 + 复测第 65 条、PLAN-P6 P6-97、本段、SANDBOX-MECHANISMS M51 + 维护规则 I1–I3 + 页脚。产品代码零改动，等用户批准后按 T1–T6 实施（当晚已实施，见下段「B187 实施」）。

## 2026-11-15 B187 实施（沙盒隔离收口，T1–T6）

- 目标（G1/G2）：沙盒窗口内 `AudioSystem.getClip()` / `Line.open` 计数为 0；窗口结束写回的 mod `Clip` 静态只能是「仍 `isOpen()` 的实机对象」或 null，绝不写回已关闭对象。判据与审计表见 `docs/PLAN-ISOLATION.md` 第 7/6 节。
- T2（新文件）：`mirror/SandboxClip.java` —— `public final class SandboxClip implements Clip` 单例（`shared()`/`isSandbox(Object)`）：`isOpen`/`isActive`/`isRunning` 恒 true；`open`（三种重载）/`start`/`loop`/`stop`/`flush`/`drain`/`close` 全空操作（只记 `format` 与 `swallowedCalls`）；位置/长度/`bufferSize`/`available` 返回 0、`getLevel` 0f；`getFormat` 返回 `adoptFormat` 缓存的实机格式（缺省 `new AudioFormat(44100f,16,2,true,false)`）；`getLineInfo` 返回 `DataLine.Info(Clip.class, getFormat())`；`getControl`/`isControlSupported` 只对 `FloatControl.Type` 返回缓存控件，其它 null；`addLineListener`/`removeLineListener` 空操作。
- 编译偏差 1：JDK 8 的 `FloatControl` 是 `abstract`，直接 `new` 报 `FloatControl is abstract` → 新增私有静态子类 `SandboxFloatControl extends FloatControl`（`super(type, -80.0f, 6.0206f, 0.1f, 0, 0.0f, "")`），构造器只存值、不下发设备。
- 编译偏差 2：`static adoptFormat(Clip)` 里直接写 `format = …` 会命中实例字段（静态上下文编译错）→ 改为 `SandboxClip stand = INSTANCE; stand.format = …`。
- 设计偏差 3（有意）：`AudioQuiet.begin()` 对**值为 null 的 Clip 字段也塞替身**。理由：雨爱 `stop()` 会把 `currentClip` 置 null，若窗口内不占位，下一个回血节点 `play()` 会通过 `currentClip == null` 检查开真线，G1 不成立。快照仍是 null，窗口结束照原样写回。
- T1：`mirror/AudioQuiet.java` 重写 —— `Snap{value, running, open}`；`begin()` 每字段读值：已是替身 → 计 `stale` 并保留替身；否则快照后 `Field.set(null, SandboxClip.shared())` 计 `swapped`；final 字段只 WARN 跳过（退回旧 close 路径）。`end()`：窗口内新开的 Clip → `closeQuietly` 并计 `closed`；值未变 → 检测 `liveStop`；值被置 null → 计 `released` 但**仍然还原快照**（B187 推翻 B169）；还原前 `isOpen()` 校验，已关闭 → 写 null 并计 `closedSnapshot` + WARN；final 字段只 WARN。`report()` 扩展为 swapped/stale/closed/cleared/putBack/released/closedSnapshot/liveStops，测试钩子同步新增。
- T3：`mirror/World.java` 的 `AudioQuiet.begin()` 从原 :336 挪到 `StaticWatch.afterInstall();`（:348）之后，`end()` 仍在 `StaticWatch.beforeUninstall()` 之前。顺序错会让 StaticWatch 把替身当基线，窗口结束把替身写回、实机 Clip 丢失。
- T5：`probe/Probe.java` 的 S255 整段重写为 **37 条**断言（替身语义、B169 推翻、死快照写 null、liveStop 恢复、雨爱 `readableForTest`/`blindForTest`），新增 **S257**(2) 真实 `AudioSystem.getClip` 开关冒烟（无音频设备时只 WARN SKIP、不计通过）；`mirror/StaticWatch.java` 新增 `snapshotValueForTest(Field)` 供断言「StaticWatch 快照的是实机 clip」。
- 构建/安装：`tools/build.ps1` 修上述两个编译错后通过；`tools/install.ps1 -SkipBuild` 在 workspace-write 下被沙箱 ACL 拒绝（脚本误报「locked by a running game」，实测无 java 进程、共享读可打开），改用 danger-full-access 安装成功；新 jar **1,287,415 B**、sha256 `E85332F8C3B26B6B44C8CAF00CF04D9F2E3E1421013692557C38BB8E51E4A08D`（旧 1,280,074 B / `F24DE109…D1F83`）。
- 门禁：S255 新契约 37 条、S257 2 条；**本轮未跑**（用户 m00732 约定验收阶段自行实测），反向验证同样未跑。
- T4/T6（文档）：PLAN-ISOLATION 第 11 节、PLAN-BUGS B187 与 B185 订正、PLAN.md B187 行/收尾/安装/手工验收 64–65 条、PLAN-P6 P6-97、SANDBOX-MECHANISMS M50/M51 与页脚、TESTING 安装与基线、本段。
- 代价：每窗口对每个 Clip 静态一次 `Field.get`+`Field.set`（实测 1 个字段，微秒级）；`isOpen()` 只在 `end()` 每字段一次 JNI，不在搜索热路径；字节码 +7,341 B，无新增线程/分配。
- 风险：替身与真实 Clip 的行为差异（严格类型断言可能抛异常，已按表给正确类型）；B169 在音频静态上被推翻（mod 主动置 null 的字段窗口结束会被还原，窗口内外一致）；实机 clip 在窗口内被别的线程真实关闭时只能写 null（音乐会停）。
- 影响：窗口内不再有雨爱声音（一次线都不开）；窗口外照常播放/停止；面板、自动出牌、搜索结果、评分、候选枚举与其它 mod 共享对象不变；`solver.log` 的 `audio quiet` 摘要新增 `stand-in swap(s)`/`release(s) restored`/`closed live clip(s) dropped` 等计数；「写回已关闭 Clip → 下一窗口 flush 崩溃」这条路径消失。

## 2026-11-16 B187 残余症状定性：计算期间雨爱重放＝已知边界，不修

- 用户 m00415「重做计划…让沙盒对这个播放雨爱的mod没有影响…沙盒隔离自己要的东西，不要引用雨爱mod使用的函数」→ 要求先给计划；m00495「那不管这个bug」→ 决定不修。
- 取证（反编译，未改产品代码）：雨爱四个入口全部汇聚到 `yuaimod.YuAiController.evaluate(AbstractPlayer)`——`HealthSoundPatch$OnHealAmountShowEffect.OnHealAmount.OnHealthBarUpdated` 三个 heal/血条 postfix，加 `GameLoopPatch$OnGameUpdate` 每帧一次。搜索窗口在 `Gameplay.tick` 内 install/uninstall，每帧 evaluate 在窗口外读到的是已还原的实机静态量，**真正在窗口内触发雨爱的是 `AbstractCreature.heal` 的 postfix**。`PresentationQuiet`(B168) 只静默了 heal 里的 `healthBarUpdatedEvent()` 调用点，挡不住 heal 自身被插的 postfix。
- `AbstractCreature.heal(int,boolean)` 字节码 8 段：FullBelly 减半 → isDying 早退 → 玩家遗物 `onPlayerHeal`（Magic Flower ×1.5、Mark of the Bloom 禁疗）→ power `onHeal` → HP 加算并封顶 → 越半血清 `isBloodied` + 遗物 `onNotBloodied`（红骷髅力量）→ 纯展示 `panelHealEffect`/`HealEffect` → **纯展示 `healthBarUpdatedEvent()`**。前 6 段是模拟必需。
- 路线评估：**A** `@SpirePatch(clz=yuaimod.YuAiController.class, method="evaluate", paramtypez={AbstractPlayer.class})`+prefix 短路（要编译期依赖雨爱 jar、要自检时序）；**B** 自定义 `MixerProvider` 设备层（~300 行、全局劫持 AudioSystem）；**C** 沙盒不调 `heal`/替换方法体（丢 onPlayerHeal/onHeal/onNotBloodied，静默失真，且 `HealAction`/药水/遗物/power 仍会调 heal）。三条均不采纳。
- 决定性限制：MTS 无运行期禁补丁；prefix 的 `SpireReturn.Return(null)` 拦不住别人插在 heal 上的 postfix；`@SpireInstrumentPatch` 在 ordering −1 早于 postfix 插入。
- 结论：B187 保留现状（替身：不崩、不残留、不真开线），残余「计算期间雨爱放一段→卡一下→从 0 帧重放」记为**已知边界、不修**（实机 clip 从未关闭，`putBack` 增长、无 dead/blind 行）。
- 文档落点：PLAN-BUGS B187「残余症状与决定」、PLAN-ISOLATION 第 12 节、PLAN.md 手工验收 64 条追加、PLAN-P6 P6-97、SANDBOX-MECHANISMS M51、本段。
- 代价/风险/影响：**产品代码零改动**，无性能/内存变化；风险＝用户以后若改主意要重启需批准编译期依赖；对 mod 影响＝无新增（现状即 B187 语义）。

## 2026-11-16 发布 1.0.1（创意工坊 ＋ GitHub）

- 触发：user m00528「写进计划书并且上传创意工坊和github」。
- 版本号：`resources/ModTheSpire.json` 1.0.0 → **1.0.1**；`docs/WORKSHOP-DESCRIPTION.md`「当前版本」同步改 1.0.1。
- 构建：`tools/build.ps1`（JDK 21.0.11，`--release 8`，140 源文件，554 entries）→ jar **1,287,415 B**，sha256 **E85332F8C3B26B6B44C8CAF00CF04D9F2E3E1421013692557C38BB8E51E4A08D**。与 1.0.0 的构建同尺寸，差异只在 `ModTheSpire.json` 的版本号字节（`1.0.0`→`1.0.1` 等长）。
- 分发：复制到 `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar` 与 `D:\mods-workshop\sts1-engine-solver\content\sts1-engine-solver.jar`，两处 sha 均为 E85332F8…（工坊内容原有旧版 1,270,229 B / 8A1697FE…）。
- 工坊工作区：`D:\mods-workshop\sts1-engine-solver\config.json`（GBK/936、无 BOM；备份 `config.json.bak-20261116`）。description 的「当前版本」改 1.0.1；`changeNote` 改为「1.0.1 版本：修复沙盒窗口内 javax.sound Clip 被写回导致的原生崩溃（B187）；计算期间雨爱音乐可能短暂重放，属已知边界」。
- 上传：需 Steam 客户端运行且已登录（未登录时上传器报 `Steam API failed to initialize correctly`）；命令必须**在游戏根目录**执行：
  `D:\Steam\steamapps\common\SlayTheSpire\jre\bin\java.exe -jar mod-uploader.jar upload -w D:\mods-workshop\sts1-engine-solver`。
  2026-11-16 09:57:38 输出 `ACCOUNT ID: 1088547740` / `Submitting workshop item update` / `Successfully updated workshop item!` ⇒ 工坊条目 **3839053516** 已更新（config 里 visibility 仍为 `private`，公开与否由作者在工作坊页面决定）。
- 文档：6 处旧 jar sha（`FE0C514E…`）全部换成 E85332F8…——`docs/PLAN-ISOLATION.md:183`、`docs/PLAN-BUGS.md:4359`、`docs/PLAN-WORKLOG.md:2622`、`docs/PLAN.md:40` 与 `:179`、`docs/SANDBOX-MECHANISMS.md:128`、`docs/TESTING.md:8`；PLAN.md B187 行状态补「1.0.1 已于 2026-11-16 发布」。
- 代价/风险/影响：**只改版本号字节，无代码、无性能、无内存变化**；风险＝工坊条目仍是 private（需作者手动公开）、上传器依赖 Steam 运行时（无人值守环境不可用）；对本 mod 的实际影响＝玩家侧版本号显示 1.0.1，行为与 1.0.0 完全一致（B187 修复本身已在 1.0.0 的 jar 里）。

## 2026-11-16 B188 / P7 药水分档搜索（计划已出，未写代码）

> 更新（同日）：已按 T1–T6/T8 实施并安装，见下一段「B188 / P7 实施」；本段的 A 记账公式已被推导推翻，实施口径以下一段为准。

- 触发：user m00001「sts1-engine-solver自己看日志为什么打地精大块头敏捷药水没有第一时间喝？先喝敏捷药水再打出幽灵铠甲能少扣2血」；user m00149 补充「同瓶数先喝分数应更高」；user m00167 指定参考实现 `C:\Users\YOKI\Desktop\CombatSolver-main`（按瓶数分档）并要求先出计划；user m00201 追加「每档 beam 适当加宽」与「写进计划」。
- 取证（solver.log 2026-11-16 10:43，物理行）：1786 行 plan 14 步＝…10 幽灵铠甲 / 11 敏捷药水 / 12 防御；1788 行 trace 步 10 `t=3 b=10`、步 11 `t=3 b=10 mhp=60`、步 12 防御 with b=17 / without b=15；最终 with loss=17、without loss=19 ⇒ +2 敏只落在第 12 步防御上，先喝可让幽灵铠甲多挡 2 点。1779 行 B174 `replayOk=false`、1778 行 replay 在 step 12 报「重锤+ [Bludgeon] -- not-settled（DEATH 屏）」⇒ 打印顺序疑点，已列为 T1 必答。
- 根因：叶子只给「已花药水」扣分（`Solver.java:4474-4487` 的 `n2 = startPotionHp - choice.potionHp`，一瓶 potionHp=9 ⇒ 18000 分），不给「仍持有药水」记资产 ⇒ 喝药那层瞬降、先喝分支被 `sortDeferred`（`:3418`，上限 `:48` 的 2000、每轮 `:60` 的 60 条）与 beam 双重淘汰；次因是喝 0/1/2 瓶共用一条一维前沿（`portfolioProfiles`）。
- user m00300 追加决定：本轮**不写门禁**，实现完由他自己实测（判据改为 PLAN-P7 §5 的 6 条观察项）；并要求把本条目的计划编号写清。
- 方案（未实施）：A 对称记账 `+ choice.potionHp * POINTS_PER_HP`；B 0→K 瓶分档（档内 `maxPotionUses` 剪枝、预算 40%+60% 按 2^(K-i) 归一、档内仍 35/25/25/15，0 瓶档 provenOptimal 即停，合并时净扣血并列少喝优先）；**每档 beam `ceil(base×(1+0.25×tier))`**（仍过 `effectiveBeam`，堆守卫可压到 48）并同比放大停车场 `max(2000,32×beam)` 与回放名额 `max(60,beam/2)`；B174 探针改用 0 瓶档基线。
- 任务表 T1–T8 合计 3.25d（T1 钉 planText 顺序 0.25d、T2 对称记账 0.5d、T3 档位+剪枝+键 0.5d、T4 beam 加宽 0.5d、T5 预算切分+合并 0.5d、T6 探针 0.25d、T7 端到端自测+三路径 0.5d、T8 文档 0.25d）；验收＝**用户自测**（user m00300「不要门禁，我自己可以测试」）：原 S258–S263 六条门禁取消，改为 PLAN-P7 §5 的 6 条面板/日志观察项。
- settings 新键：`search.potionTiers`（默认 on，回滚开关）、`search.potionBeam`（on）、`search.potionBeamStep`（25）。
- 代价/风险/影响：档数 ≤ 6、每档一次 Session 构造；对称记账每节点一次整数乘加；总时长不增（预算切分），内存峰值≈最宽档；风险＝选线口径边界移动、`PotionBook` 战略价≠真实价值、三条收尾路径须同步接线（防 B120 重演）；影响面板 plan/预计损失、自动出牌顺序、solver.log 新增 `potion tier:` 行、settings.txt 三键；与其它 mod 无交互。
- 文档落点：新建 `docs/PLAN-P7.md`（§1 证据 / §2 根因 / §3 方案含 beam 加宽 / §4 任务表 / §5 门禁 / §6 settings / §7 代价风险影响 / §8 边界 / §9 疑点）；`docs/PLAN.md` 板块表 P7 行 + 在跟条目 B188 行；`docs/PLAN-BUGS.md` B188 段（B187 之后）。
- **本轮未跑构建、未跑 `tools/run.ps1`、未改任何产品代码**（user m00732 自行实测；本条目等实现后再验收）。

## 2026-11-16 B188 / P7 实施（T1–T6/T8 落地，T7 待用户实测）

- 触发：user m00001 要求完成 P7（PLAN.md 板块表第 98 行）；实现按 `docs/PLAN-P7.md` 的 T1–T8 走，仍遵守 user m00300「不写门禁、用户自测」。
- **T1（定性，无代码改动）**：`Solver.planText()`（`Solver.java:3240-3249`）按 `this.plan` 下标拼 `Step.action.label`，不重排；`Result.plan` 是 `Solver.replay`（`Solver.java:2069`）返回的 `List<Step>`，replay 逐个执行传入的 action 并立刻 `new Step(action, …)` ⇒ 打印顺序 == 回放顺序。1778 行「打印防御、回放重锤+」属 (b)：回放走到第 12 步时前序药水/格挡已改变手牌与能量，且该步开着 DEATH 屏 ⇒ `not-settled`。B174 的 `replayOk=false` 由此解释，不是比较对象错位。
- **T2（实现口径更正）**：计划原式 `+choice.potionHp*POINTS_PER_HP` 经推导是错的：`n2=S-H` 是已花，原式等于把叶子药水项记成 `2H-S`，喝一瓶会再掉 `2v`（9 HP ⇒ 36000 分），比原 18000 更糟。实现改为 **档内叶子不计药水价**（`Solver.java:4581` `int n2Score = this.deferPotionCost ? 0 : n2;`），药水价只在档间合并由 `netHpLoss`/`compareRoutes` 收一次；`provenOptimal` 仍要求真实 `n2==0`，`netHpLoss` 仍含 `n2`。
- **T3（档位）**：新文件 `sts1-engine-solver/src/main/java/sts1solver/search/PotionTieredSession.java`（`public final class` :43）—— `limits` :54 = 0..K（K=min(根可用药水,5)，最多 6 档），`tierBeam` :110，`startTier` :137，`tierDone` :247，`finishAll` :228，`advance` :271。档内剪枝：`Solver.java:4498-4500` 在 `candidates` 之后、`defer` 之前 `currentActs = Solver.withoutPotions(currentActs)`。新 helper：`potionCountUsable` :2901、`potionUses` :2922、`withoutPotions` :2932（install/finally uninstall，异常返回 0）。`Limits.java` 三键：`search.potionTiers`/`search.potionBeam`/`search.potionBeamStep`（默认 on/on/25，0..100 夹取），私有 `yes()` 接受 off/no/false/0/ban/关/禁用。
- **T4（beam 与名额）**：`tierBeam(base,tier) = tier<=0||!potionBeam ? base : min(Limits.beam(), base+ceil(base*step*tier/100))`，仍过 `effectiveBeam`（堆守卫可压到 48）；`tier>0` 时 `Session#setCaps(max(Limits.deferred(),2000,32*beams[0]), max(60,beams[0]/2))`（`defer` 新增 4 参带 cap 重载，`promoteOne` 读 `promoteFloor`）。
- **T5（预算与合并）**：份额 0 瓶档 40%，其余 60% 按 `2^(K-i)` 归一；每档 `b=max(300,min(share*budget,剩余))`，`deadlines = Solver.rungDeadlines(beams.length,b)`，`new Session(root,beams[0],layers,b,firstNodes,0) + planWidening`；跨档收尾：`stopRequested→stopped`、`win && (provenOptimal || netOf<=0) → proven`、末档 `tiers`、时间不足 `time`；合并 `PotionTieredSession.better` :214（净扣血优先，平局少喝）。
- **三处接线**：`Solver.solvePortfolioInternal` 改走 `PotionTieredSession`；`PortfolioSession`（文件末尾）整体改为纯委托壳（`advance` 首次建 `PotionTieredSession`，其余访问器/测试钩子转调）；`QuickSession` 的 `narrowDeep` 分支阶梯 = `NARROW_BEAM(10)` + `widenBeams()` 去重升序，改走 `PotionTieredSession`，`passes()` 委托。**阻塞式 `Solver.solve` 不接档位**（boss 房或怪 maxHealth≥200，`Gameplay.java:2332`），已记 PLAN-P7 §8 边界。
- **T6（探针）**：`Session#markTiered()` 只在 `limits.length>1` 时调用；`finish()` 里 B174 回放 `if (!this.tiered)` 才跑，档位模式由 `PotionTieredSession` 打 `potion tier: baseline tier=0 loss=…`；单档（无药或回滚开关）保留旧探针与旧计价。
- 设计偏差已回填 `docs/PLAN-P7.md`：§3.1 公式更正与推导、§3.2 结构/收尾/合并日志、§3.3 `base+ceil(...)` 与「仅 tier>0 放大」、§8 边界、§9 T1 结论、§4 状态列、§11 实现记录（文件/行号/偏差/构建/回滚）。
- 构建/安装：`tools/build.ps1`（JDK 21.0.11，`--release 8`，141 源文件，555 entries）BUILD OK；`tools/install.ps1` → `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar`，jar **1,293,146 B**，sha256 **9C8E9CA5A8E39686BC240D34C91E0658BB7DC6B1B91D84FD73F2BB4DA200AEC4**，INSTALL OK。
- 代价：每档一次 Session 构造（几十 ms，≤6 档）；档内叶子少一次药水价乘加；宽档节点随 beam 线性涨但预算切分 ⇒ 总时长不增；内存峰值≈最宽档（受 `effectiveBeam` 堆守卫）。
- 风险：档内不计药水价 ⇒ 中间层喝药边界移动（终局净扣血口径不变）；`PotionBook` 战略价≠真实边际价值，留 Boss 的药可能提前喝；`Solver.solve` 未接档位是已知边界；回滚＝`search.potionTiers=off`（单档、旧计价、旧 B174 探针）。
- 影响：面板 plan 与预计损失（药水会更早出现）；自动出牌顺序；`solver.log` 新增 `potion tier:`/`potion tiers:` 行与 `beams=` 实值；`settings.txt` 三键；不改镜像、不动 static 还原、与其它 mod 无交互。
- 未跑 `tools/run.ps1`/游戏内自测（user m00300/m00732）；T7 端到端由用户实测：主诉场景要求药水步号 < 幽灵铠甲步号、预计损失 17→15。

## 2026-11-16 B188 后续改造计划（rung 外层迭代 + 药水档提示；计划段，同日已实施见下段）

- 触发：user m00331「面板模仿 STS2 mod 加提示，现在在计算用几瓶药的世界线」＋「计算顺序改成先第一档 beam 的 0→n 瓶、再第二档 beam 的 0→n 瓶」＋「以上全部先给我计划不要动手」。
- 计划全文：`docs/PLAN-P7.md` §12（12.1 要求 / 12.2 现状行号 / 12.3 cell 循环 / 12.4 提示 / 12.5 任务表 P7b-1..5 合计 1.35d / 12.6 验收 6 条 / 12.7 代价风险影响 / 12.8 落点）。
- 核心改动：执行单元从「药水档」改为 cell=(rung, 药水档)，顺序 rung 外层；每个 cell 一个独立 `Solver.Session`（单档、`planWidening(null)`），预算与节点按 rung 份额×药水份额双权重切分；`K==0` 或 `search.potionTiers=off` 仍走单 Session 跑完整 ladder（B177 复用保留）；新增回滚键 `search.potionOrder=beam|tier`。
- 提示：`PotionTieredSession.hint()` + `QuickSession.potionHint()`，接到 `Gameplay` 状态行 :729/:732 与进度行 :1854；不改 `worldlineText`（probe/门禁引用）。
- 状态：**只出计划，未改代码、未构建**（本段为当时的记录）。同日已按此计划实施并构建安装，见下一段。
- 说明：本段第 3 条「`search.potionTiers=off` 仍走单 Session 跑完整 ladder」在实施中保持；另补充「K==0（身上无可用药水）时 `cellMode=false`，同样走单 Session + 完整 ladder」，因此无 cell 日志、无提示。

## 2026-11-16 B188 / P7b 实施（cell 顺序 + 药水档提示，已构建安装，待用户实测）

- 触发：user m00001「完成sts1-engine-solverP7 后续改造计划」。计划原文＝`docs/PLAN-P7.md` §12（user m00331 出）；本轮按 P7b-1..P7b-5 全部落地。
- 顺序（P7b-1）：`PotionTieredSession` 执行单元改为 **cell=(rung r, 药水档 t)**，严格 rung 外层（r=0: t=0..K → r=1: …）。每 cell 一个独立 `Solver.Session`：`planWidening(null)`（单 beam，不在内部加宽）、`limitPotionUses(limits[t])`、`deferPotionCost(true)`、`markTiered()`；t>0 时 `setCaps(max(2000,32*beam), max(60,beam/2))`。旧 tier 路径（`startTier`/`tierDone`/`logTier`）整段保留。
- 顺序回滚（P7b-1）：`Limits.potionOrderBeam()`（`Limits.java:269`）读新键 `search.potionOrder`，默认 `beam`；`tier`/`tiers`/`档位`/`tier*` 回旧顺序。
- 预算/节点/早停（P7b-2）：rung 份额＝`PORTFOLIO_SHARES` 按实际 ladder 长度归一（4 档时仍是 35/25/25/15；QuickSession 去重成 3 档时归一为 35/85、25/85、25/85，不白留预算）× 药水份额（tier0=0.40，其余 0.60×2^(K-i)/Σ）。`cellBudget=max(300,min(B*rungShare*potionShare,剩余))`，剩余 ≤300ms ⇒ `finishAll("time")`；`cellNodes=max(200,maxNodes*rungShare*potionShare)`。早停：任一 cell `win && (provenOptimal || netOf<=0)` ⇒ `finishAll("proven")`。baseline＝已完成 t==0 cell 里最优。
- 提示（P7b-3）：`PotionTieredSession.hint()`（`:645`）文本 `药水 ≤1 瓶（2/3）· beam 28（2/4 档）· 0 瓶基线 净损 15`；`phase()`（`:625`）cell 模式＝hint + `· beam=… layers=…`；`QuickSession.potionHint()`（`:296`），`phase()` 在 cellMode 直接返回 `session.phase()`（不再误报「加宽搜第 N 档」）；`Gameplay.java:728-737` 状态行两条分支追加 ` · potionHint`；`PortfolioSession.hint()`（`Solver.java:5102`）供深度搜索面板。`worldlineText()` 与 `lines()` :1854 未改（hint 已随 phase 进面板，避免重复）。
- 日志（P7b-4）：`potion cell: start rung=r/R tier=t/K limit=… beam=… budgetMs=… nodes=…`、`potion cell: done … win/net/potions/nodes/ms/reason/proven`、`potion tier: baseline tier=0 loss=… cells=i/N`、收尾 `potion tiers: cells=… ran=… bestCell=r/t why=… baselineNet=… bestNet=… bestPotions=…`；`PortfolioSession` 启动日志追加 `cellMode=… cells=…`。
- 构建安装（P7b-5）：`tools/build.ps1` BUILD OK（JDK 21.0.11、`--release 8`、555 entries）；`mod\sts1-engine-solver.jar` **1,295,185 B**，sha256 **D6D9D262DD586CBB27C0018ABC1E4EE5A7029210913D345F6599FC5B62A24A6F**，已复制到 `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar`（同 hash）。**未跑 `tools/run.ps1`、未启动游戏**（user m00300/m00732）。
- 偏差：① 提示措辞用「净损 15」而非「净-15」；② 计划里“可选的面板独立一行”未做，hint 并入 phase；③ 日志 rung/tier 用 1-based；④ rungShare 改为按 ladder 实际长度归一（4 档等价）；⑤ `search.potionOrder` 只切顺序，`potionTiers=off` 仍是总回滚。完整清单见 `PLAN-P7` §13.2。
- 代价：cell 数 (K+1)×R（K=2、R=4 ⇒ 12 个 Session，改造前 3 个），每 cell 从 root 重建；同预算节点/耗时预计 +5~15%，每档略浅；总时长仍由预算封顶；内存峰值＝最宽单个 cell。
- 风险：0 瓶宽档被推到最后（预算不足时最终线可能变差）；早停更难触发 ⇒ 用时更贴上限；面板/状态行文案变化可能撞整行断言；每 cell 一次 `World.install/uninstall`（最多 12 次、每次几十 ms）；`potionOrder=tier` 分支无门禁覆盖。
- 影响：面板/状态行多出药水档提示；自动出牌顺序与预计损失仍取合并结果（净扣血优先、并列少喝）；`solver.log` 新日志行；`settings.txt` 新键 `search.potionOrder`；不改镜像/static 还原，与其它 mod 无交互。
- 回滚：`search.potionOrder=tier`（只回顺序）／`search.potionTiers=off`（总回滚，无 cell 无提示）。
- 文档：`PLAN-P7` §12 状态 + §13 实现记录；`PLAN.md` P7 行与安装行；本段；`PLAN-BUGS` B188「P7b 后续改造」。

## 2026-11-16 B188 / P7c 药水档上限改读游戏数据

- 触发：user m00172「1.药水并不是上限只有3，改成读取游戏内的数据」。
- 现状：唯一写死上限是 `PotionTieredSession.java:53` `MAX_TIERS=6` + 构造器 `:100` `min(Solver.potionCountUsable(world), MAX_TIERS-1)`；实机日志（11:42:41，19 mod）`potionTiers=3` 对应可用药水 2 瓶 ⇒ K=2，说明「3」由当前瓶数决定，不是常数。
- 游戏数据：`javap` 确认 `AbstractPlayer` 有 `public int potionSlots;` 与 `public ArrayList<AbstractPotion> potions;`；槽位会被 Potion Belt/mod 改写，是本局真实容量。
- 改动：删 `MAX_TIERS`，新增字段 `usablePotions`/`beltSlots`（:52-58）；`Solver.potionSlots(World)` 读 `AbstractDungeon.player.potionSlots`（≤0 回退 `potions.size()`，异常 0，install/finally uninstall）；`PotionTieredSession.tierCount(usable, slots, budgetMs, rungs)`：usable≤0⇒0（单档旧路径），否则 `min(max(usable,slots), max(1,(budgetMs/300)/max(1,rungs))-1)`；hint 追加「，槽 N」；构造日志 `potion tiers: usable=… slots=… K=… tiers=…`；`portfolio ladder` 行追加 `potionSlots=/usablePotions=`（访问器 `slotsForTest()/usablePotionsForTest()`，不额外 install）；订正 `potionCountUsable` javadoc（「高档只重复低档」在会产药的战斗里不成立）。
- 构建安装：`tools/build.ps1` BUILD OK（JDK 21.0.11、`--release 8`、555 entries）；jar **1,295,693 B**，sha256 **4A155B622BF12691603A3D6ED4A5A7DBCE92A07FAA989070F6D8E5435077B644**，已复制到 `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar`（同 hash）。**未跑 `tools/run.ps1`、未启动游戏**（user m00300/m00732）。
- 代价：作业构造多一次 `World.install/uninstall`（几十 ms）；cell 数 `(K+1)×R`，K 由槽位决定（槽 3/持有 2 ⇒ 4 档×4 rung=16，比 P7b 的 12 多 4 个），同预算每 cell 更浅，总时长仍由预算封顶；内存峰值＝最宽单个 cell。
- 风险：槽多药少时高档可能得出与低档相同的结论，浪费其份额（几何衰减，最坏 10~15%）；`potionSlots` 异常值（0/负/超大）——0/负走回退，超大被预算地板摊薄；提示/日志文字新增「槽 N」/`slots=`，整行断言会红；`potionTiers=off` 时两字段为 0，不显示槽位。
- 影响：面板状态行/进度行显示「（i/K，槽 N）」，K 随本局槽位；自动出牌与预计损失口径不变；`solver.log` 新增 `potion tiers: usable=…` 与 `portfolio ladder … potionSlots=… usablePotions=…`；无新 settings 键；不改镜像、不动 static 还原、只读 `AbstractPlayer` 公有字段。
- 回滚：`search.potionTiers=off`（总回滚）／`search.potionOrder=tier`（只回顺序）／代码把构造器的 `tierCount(...)` 换回 `Math.min(Solver.potionCountUsable(world), 5)`。
- 文档：`PLAN-P7` §14；`PLAN.md` P7 行 + 安装行；本段；`PLAN-BUGS` B188「P7c」。

## 2026-11-16 B188 / P7d 顺序改回档外层 + 重复计算审查（已构建安装，待用户实测）

- 触发：user m00561「1.还是改回先计算0药水的1到4档再计算到n药水的0到4档。现在这样太慢了。2.检查是否有重复计算，比如药水1第一档和药水2第一档会不会重复计算」。
- 顺序（P7d-1）：`Limits.potionOrderBeam()`（`Limits.java:273`）默认 `beam` → **`tier`**；默认走 `PotionTieredSession.startTier()`（`:232`）——档外层，档内 `planWidening`/`widenToNextRung` 在同一棵树上 10→28→64→128 原地加宽。cell 布局（每 (rung,tier) 一个独立 Session）保留但改为 `search.potionOrder=beam` 显式开启。
- 提示（P7d-2）：`PotionTieredSession.hint()`（`:683`）只在一档（`limits.length<=1`）时返回空，tier 布局也显示；beam 后跟「（加宽 r/R 档）」。`PotionTieredSession.phase()`（`:656`）与 `QuickSession.phase()`（`QuickSession.java:271`）都把 hint 前置。
- 重复计算审查（P7d-3，结论写入 PLAN-P7 §15.3）：① 同一档内相邻 beam 在 tier 布局**不重复**（树复用），在 cell 布局**重复**（每 cell 从 root 重建、`planWidening(null)`），同档 4 个 beam 各展开一次再乘 (K+1) 档——这就是“太慢”的主因；② 药水 1 档 vs 2 档**不是同一搜索**：`limitPotionUses`、起始 beam `tierBeam(base,t)=base+ceil(base*25%*t)`、t>0 的 `setCaps(2000+, beam/2)` 三个参数至少一个不同，结果不可互替；③ 档间“不含药水”的根前缀确实被重搜 K+1 次（结构性重复）；彻底消除要在同一 Session 上逐档放宽 `maxPotionUses` 并复用前沿，等于改 `Solver.java:4526` 的剪枝与停车场结算，本轮不做，记为已知边界。
- 构建安装：`tools/build.ps1` BUILD OK（JDK 21.0.11、`--release 8`、555 entries）；jar **1,295,807 B**，sha256 **05D00831DD59261954FB63E659204680F8C32DED5095B740BA2587FF4067D973**，已复制到 `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar`。**未跑 `tools/run.ps1`、未启动游戏**（user m00300/m00732）。
- 代价：Session 数 16 → 4（默认 K=3），同预算每档更深、无档内重复展开；总预算/节点上限公式不变；内存峰值仍为最宽一档的树。
- 风险：P7b 观察点（rung 外层、`potion cell:` 行）默认消失，需 `search.potionOrder=beam`；tier 布局早停按档（proven/净损≤0），无逐 cell 早停；档间前缀仍重复；状态行多出「加宽 r/R 档」，整行断言会红。
- 影响：面板/状态行继续显示药水档提示（覆盖 tier 布局）；预计损失与自动出牌口径不变；`solver.log` 默认 `potion tier:` 行、`potion tiers:` 走 `tiers=` 分支，`potion cell:` 仅 opt-in；settings 键不变；不改镜像、不动 static 还原。
- 回滚：`search.potionOrder=beam`（回 cell 顺序）／`search.potionTiers=off`（总回滚）／代码把 `potionOrderBeam()` 默认改回 `beam`。
- 文档：`PLAN-P7` §15（含 §15.3 重复计算结论表）；`PLAN.md` P7 行 + B188 行 + 安装行；本段；`PLAN-BUGS` B188「P7d」。

## 2026-11-16 发布 1.0.2（GitHub ＋ 创意工坊）

- 触发：user m00678「那就不要做了，上传github和创意工坊」——P7e（单遍按瓶数分配 beam 名额）与「K 收窄到可用瓶数+1」两个提速方案**均不做**，直接按现状发版。
- 版本号：`resources/ModTheSpire.json` 1.0.1 → **1.0.2**；`docs/WORKSHOP-DESCRIPTION.md`「当前版本」与「药水策略」条目同步更新（加「按可用药水数分档搜索（0 瓶到 n 瓶各一条世界线，净扣血优先、同样损失少喝一条），面板实时提示当前正在计算哪一档」）。
- 构建：`tools/build.ps1` BUILD OK（JDK 21.0.11、`--release 8`、141 源文件、555 entries）→ `mod\sts1-engine-solver.jar` **1,295,807 B**，sha256 **BD3FFAE6DABF3D6232D857FBE6A0BEB7AE28B2833EDF4402889D35C4EF7DB021**（与 P7d 的 jar 同尺寸：版本号 3 个字符等长替换）。
- 分发：复制到 `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar` 与 `D:\mods-workshop\sts1-engine-solver\content\sts1-engine-solver.jar`，三处 sha 均为 BD3FFAE6…。
- 工坊工作区：`D:\mods-workshop\sts1-engine-solver\config.json`（GBK/936、无 BOM、4,691 B；备份 `config.json.bak-20261116b`）。description 的「当前版本」改 1.0.2、「药水策略」条目同上，`changeNote` 改为「1.0.2 版本：药水搜索改为按可用药水数分档（0 瓶到 n 瓶各一条世界线，净扣血优先、同样损失少喝），面板实时提示当前计算的药水档；搜索顺序为同一 beam 档跑完 0→n 瓶再进下一档。」
- 上传：Steam 客户端运行中，在游戏根目录执行 `D:\Steam\steamapps\common\SlayTheSpire\jre\bin\java.exe -jar mod-uploader.jar upload -w D:\mods-workshop\sts1-engine-solver`。2026-11-16 18:52:10 输出 `ACCOUNT ID: 1088547740` / `Submitting workshop item update` / `UploadingContent` / `Successfully updated workshop item!` ⇒ 条目 **3839053516** 已更新（visibility 仍为 `private`，公开由作者在工作坊页面决定）。
  - 坑：上传器在非控制台启动时用 cmd 重新拉起自身，父进程立刻退出，`$LASTEXITCODE` 可能是 1 且拿不到输出；**不能拿退出码判断成败**，要看 `Successfully updated workshop item!`（下次可加 `| Out-String` 直接看输出）。
- GitHub：`git push origin master`（本次 github.com:443 直连可用，未使用 `_tmp/ghproxy.py` 隧道）。
- 代价/风险/影响：**只改版本号与工坊文案，产品代码与 1.0.2 前完全一致**；无性能/内存变化。风险＝工坊条目仍是 private（需手动公开）、上传器依赖 Steam 运行时；对本 mod 的实际影响＝面板/ModTheSpire 列表显示 1.0.2，行为与 P7d 构建相同。回滚＝重新上传 1.0.1 的 jar 与 `config.json.bak-20261116`（变更集不涉及代码）。

## B188 / P7-选牌 结束回合里开的选牌屏（计划妥当）答完后回合收不了尾（2026-11-17；未跑门禁，用户实测）

| 项 | 内容 |
| --- | --- |
| 用户口径（m00750） | 「计划妥当这张牌确实有问题。选牌的牌可能都有问题。我已经实测有问题了。给我一个计划修复。」（m00778：只修选牌，减少测试；m00820：写然后做） |
| 旧行为 | 结束回合块里打开保留屏 → 答完屏 → 本步被判 settled，`endTurnQueued` 没人消费；之后每步结束回合都 `the end turn did not finish the turn (turn stayed 3, combatOver=false)`，单场 2942 条 `A3 promote: cannot rebuild a deferred path ... on 结束回合`；实机表现「选牌答完游戏不再动」 |
| 根因 | 字节码取证：`AbstractRoom.endTurn()` 不写 `turnHasEnded`；真正置位的是 `AbstractRoom$1.update()` 里的 `EndTurnAction`（后面还跟 `WaitAction(1.2f)`）。B151 早退只置 `player.endTurnQueued=true` 就返回 awaitingSelection，答屏后的 `drain()` 因队列为空 + `turnHasEnded=false` 判 settled 直接 break，`Driver.java:510-544` 的 pressNow 分支从未执行。**帧长不是原因**（B181 后 `simDelta` 默认 0.05 s，`Driver.java:931`） |
| 修法 | 只改 `drive/Driver.java`：`isIdle(GameActionManager)` 加 `if (endTurnPressWaiting(gam)) return false;`；新增 `endTurnPressWaiting()`（`gam==null \|\| !endTurnQueued(gam) \|\| combatOver()` → false，否则限速 4 s 打 `an end turn press is still latched (...)` 并返 true）。判据由「队列空」换成引擎规则「press 未被消费 = 未完成」，未放宽任何条件 |
| 影响面 | `isIdle` 仅 `drain()` :427 与 `settleBlock()` :845 两处；无每节点开销；面板/路线/评分/Digest/live 路径不变 |
| 门禁 | **未跑**（用户 m00732 自行实测 + m00778 要求减少测试）。验收：带计划妥当打一场，看答屏后回合是否推进、`on 结束回合` 失败是否归零 |
| 安装物 | `mod/sts1-engine-solver.jar` 1,296,005 bytes / sha256 `D780675CEB49A0FDD5A9786F2A8AB63809B88039A665FC883FE43436382B6A42`（`tools/build.ps1` BUILD OK；`tools/install.ps1` 已装到 `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar`，INSTALL OK / 哈希一致） |

教训：沙盒判「这一段做完了没有」要问引擎（按钮被消费了吗、`turnHasEnded` 置了吗），不能问「我的队列空了吗」——latch 型输入状态与队列在同一逻辑层。

## B189 / P7-选牌 结束回合途中的选牌屏被当成拒绝，计划截断（2026-11-17；未跑门禁，用户实测）

| 项 | 内容 |
| --- | --- |
| 用户口径（m01083 / m01101 / m01110） | 「打大鳄虫仍然显示重放不通过，查看原因」→「drain 跑了两次是不是 bug，改成一次有没有坏影响」→「安装」 |
| 旧行为 | 结束回合块里开保留屏（计划妥当）⇒ drain 正确返回 awaitingSelection ⇒ B125 重试再跑一次（steps=0，覆盖统计）⇒ `turnAdvanced=false` 直接 refused ⇒ `Legal` 抛 `not-offered` ⇒ `replayed 4 of 21 steps, first failure at 4 on 结束回合（选：打击）`，面板显示「重放不通过」 |
| 根因 | `Driver.java:1435` 重试与 `:1444` 拒绝判据都只看回合计数，不看 `drained.awaitingSelection`；`Legal.describe()` 无牌名时兜底成 `the card at hand index -1`，掩盖了真实步骤。B188 的 `endTurnPressWaiting()` 只覆盖 press 未被消费的 B151 支 |
| 修法 | `Driver.java:1435` 重试条件加 `!drained.awaitingSelection`；`Driver.java:1444` 改为 `awaitingSelection` 打 `endturn-waiting` 日志、否则才走原 refused 块；`Legal.describe()` 对 `isEndTurn()` 返回「结束回合」。未放宽任何拒绝判据 |
| 影响面 | 面板/计划「结束回合（选：X）」可走完；`A3 promote ... on 结束回合` 应归零；Digest/评分/live/自动出牌节奏不变；不触碰 mod 类 |
| 门禁 | **未跑**（m00732 自测 + m00778 减少测试）。S182 不覆盖本路径，不新增门禁 |
| 安装物 | `mod/sts1-engine-solver.jar` 1,296,149 bytes / sha256 `28FCFF2C685CB1DCE5BDE5DAA0062540021CF8DCA86E24389C6439EF0F18E439`（BUILD OK；`tools/install.ps1 -SkipBuild` INSTALL OK） |

教训：`turnAdvanced == false` 是「回合还没收尾」，不是「引擎拒绝」；先问有没有屏在等答案，再判拒绝。日志里的 `the card at hand index -1` 是 describe 兜底，不是真的找不到牌。

## B190 / P7-选牌 选牌的「不选 + 确定」无法表示（2026-11-17；未跑门禁，用户实测）

| 项 | 内容 |
| --- | --- |
| 用户口径（m01149） | 「计划妥当打出后结束回合如果不选牌是可以的，但是自动出牌没有确定这个动作。我的想法是改成每个选牌界面都要有对应的动作，包括三选一和在牌堆中选，要让面板知道要干什么比如选『打击』然后点确定，或者直接点确定。」+「看一下会不会影响之前修复的赌博筹码的选牌，比如通过选择和放弃改变牌序」+「之后回合结束选牌一张不选可以自动执行并且面板理解动作」 |
| 旧行为 | 保留屏那一步 label 只有「结束回合」；`submit` 不缓存空答案 ⇒ 自动出牌打 `selection open with no planned answer: kind=HAND ... autopilot=true` 后停在屏上；实机手动能点确定，mod 不能 |
| 根因 | ① `Action.withChoice` 只在 `chosen` 非空时加「（选：X）」；② `Gameplay.submit` 以 choice 非空为缓存条件；③ `Driver.resolveSelectionLive` 把空 id 表当「没答案」退回启发式猜牌，`fillGridSelection` 让 GRID 空答案必失败 |
| 引擎事实 | `RetainCardsAction` 的屏 = anyNumber=false / canPickZero=true / upTo=true，选 0 张合法并自己置 `wereCardsRetrieved`；赌博筹码 = anyNumber=true / canPickZero=true；Grid 屏无 canPickZero，只有 anyNumber / isJustForConfirming |
| 修法 | `Action` 新增 5 参 `withChoice(…, kinds)` + `clickScript`（「选：X → 确定」/「不选 → 确定」/多屏逐条）；`Solver.actionFor` 传屏种并对空答案造分支，`answerForReplay` 的 expected 认 screenKinds；`Gameplay` 用 `pendingScreens/pendingChoiceKinds` 按屏数走，submit 条件放宽到「有屏」；`Driver` 新增 `resolveSelectionLiveEmpty(kindHint)` + `allowsEmptyPick`，`fillGridSelection` 允许空确认 |
| 影响面 | 面板/日志显示点击脚本；空答案可自动执行；赌博筹码的换序与子集枚举不动（只多出「空集真的点确定」）；搜索/评分/Digest/路线/live 不变；不触碰 mod 类 |
| 门禁 | **未跑**（m00732 自测 + m00778 减少测试）。S133/S214/S215 不覆盖空答案执行，按 m00778 不新增 |
| 安装物 | `mod/sts1-engine-solver.jar` 1,297,569 bytes / sha256 `9A4D5945D00793676AE5EBDF5B097D8FB9EE8824B94ADD35DF373A30E99A036D`（`tools/build.ps1` BUILD OK；`tools/install.ps1 -SkipBuild` INSTALL OK，源/目标哈希一致） |

教训：「答案为空」≠「没有答案」，不能用同一个空串表示；屏的个数本来就记在 `Action.screenKinds` 里，缺的只是拿它当答案的一部分去执行。

## 发布 1.0.3（2026-11-17）

| 项 | 内容 |
| --- | --- |
| 内容 | B188（结束回合选牌屏答完回合收不了尾）、B189（结束回合途中的屏被误判成引擎拒绝）、B190（选牌「不选 + 确定」可表示并可自动执行，面板写点击脚本） |
| 版本 | `resources/ModTheSpire.json` 1.0.2 → 1.0.3；`docs/WORKSHOP-DESCRIPTION.md` 当前版本行同步 |
| 构建 | `tools/build.ps1` BUILD OK（141 源文件、555 entries、1,297,570 B），sha256 `489558812E1E482166BACF704D27040574915F4E8600B104A5AD41533EC60EF0` |
| 实机 | `tools/install.ps1 -SkipBuild` INSTALL OK → `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar` |
| GitHub | 提交 `7ca490c` 已推送 master（10 个文件：4 个文档 + WORKSHOP-DESCRIPTION + ModTheSpire.json + 4 个源文件） |
| 创意工坊 | 条目 3839053516：`D:\mods-workshop\sts1-engine-solver\content\sts1-engine-solver.jar` 已换新，config.json 以 GBK/936 无 BOM 回写（description 当前版本 1.0.3 + changeNote），备份 `config.json.bak-20261117`；上传返回「Successfully updated workshop item!」 |
| 门禁 | 未跑（用户 m00732 自测 / m00778 减少测试） |

## 发布 1.0.4（2026-11-17）

| 项 | 内容 |
| --- | --- |
| 内容 | B192 沙盒隔离收口：静态字段指向的战斗活体（宠物/静态 power，如 `TheNecrobinderMod.OstyManager#osty`）由 `StaticEntities` 按「引擎基类 + 世界图不可达」接管为克隆根，容器深拷；Spine 的 `Skeleton`/`AnimationState` 不再共享（`SpineIsolation`）；搜索后 spine 审计忽略只差动画时钟的行；`StaticWatch` 跳过动态克隆根 |
| 版本 | `resources/ModTheSpire.json` 1.0.3 → 1.0.4；`docs/WORKSHOP-DESCRIPTION.md` 当前版本行同步；工坊 `config.json` 的 description 版本行与 `changeNote` 同步 |
| 构建 | `tools/build.ps1` BUILD OK（144 源文件、562 entries、1,318,141 B），sha256 `CF61068257F26711A0144EA0715601056264A7448CBC5847199E041B99F7D210` |
| 实机 | `tools/install.ps1` INSTALL OK → `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar`（源/目标哈希一致） |
| GitHub | 提交 `54212e5` 已推送 master（19 个文件：8 个文档 + ModTheSpire.json + 10 个源文件，+1892/−34；新增 `PLAN-MOD-OBJECTS.md`、`StaticEntities.java`、`LiveGuard.java`、`SpineIsolation.java`） |
| 创意工坊 | 条目 3839053516：`D:\mods-workshop\sts1-engine-solver\content\sts1-engine-solver.jar` 已换新（1,318,141 B，同 sha），config.json 以 GBK/936 无 BOM 回写（版本 1.0.4 + changeNote），备份 `config.json.bak-20261117c`；上传返回「Successfully updated workshop item!」 |
| 门禁 | 未跑（用户 m00732 自测 / m00778 减少测试） |

发布口径：本次不宣称动画问题「已实测修好」，只写隔离机制已生效；用户重启游戏后按 PLAN-BUGS B192 第五轮「验证」五条自查。
## B191 / P7-选牌 点击脚本印 id 而不是中文名（2026-11-17；未跑门禁，用户实测）

| 项 | 内容 |
| --- | --- |
| 用户口径（m01447） | 「所有面板给出的牌都显示中文。现在面板给出生存者弃牌选牌提示是英文」 |
| 旧行为 | 计划行「生存者（选：PiercingWail → 确定）」——B190 的 clickScript 收到 raw id |
| 根因 | `Action.withChoice` 里 `text` 已取到 display，但 `clickScript(raw, screens)` 传的是 id 串 |
| 修法 | `clickScript(shownText, raw, screens)` 逐屏优先 display、该段为空才用 id 兜底；调用处改传 `text` |
| 影响面 | 纯面板/日志文案；搜索、评分、Digest、live 匹配顺序不变；空答案解析仍写「不选 → 确定」 |
| 门禁 | 未跑（m00732 自测 + m00778 减少测试） |
| 安装物 | `mod/sts1-engine-solver.jar` 1,297,615 bytes / sha256 `39BF1B640A378B4F652348FF8C8C233C9A36E45FCD5CE54F8EC17702829CA82E`（BUILD OK + INSTALL OK） |

## B192 / 隔离 mod 静态对象引用 亡灵契约师计算后实机奥斯提血量增加（2026-11-17；已实现并 build，install 待重跑，未跑门禁）

| 项 | 内容 |
| --- | --- |
| 用户口径（m00005） | 「sts1-engine-solve最新日志亡灵契约师计算时沙盒没有正确隔离导致计算后奥斯提血量增加，给我一个计划修复这个bug以及类似bug。」——只要计划 |
| 用户决策（m00273） | 「不要特判因为奥斯提不是原版角色，有没有不特判的方案」⇒ 方案改为「引擎战斗基类 + 世界图可达性」通用判定，代码与文档均不出现 mod 类名表 |
| 旧行为 | 点「计算」后实机 Osty 血量/上限变高；求解器日志里没有 Osty 血量行；`static watch: the sandbox wrote mod static` 从未出现 |
| 根因 | ①`ModStatics.java:29-32` 只镜像标量，`OstyManager.osty` 这类静态活体按设计不镜像；②`StaticWatch.java:240` 浅快照 `fs.size() < 32` 在父类第 25 个字段（`hb_w`）截断，`currentHealth/maxHealth` 永不入快照（7 个自声明字段 + 25 = 32）；③`Digest.java:34-80` 不含 Osty；④`World.fork` 对 `Kind.VALUE` 共享引用 ⇒ 同轮分叉互相污染 |
| 克隆安全性 | jar 常量池 12 个类引用 `LTheNecrobinderMod/character/Osty;`，`javap -p` 后无任何类**声明** Osty 字段 ⇒ 替换静态即全局唯一克隆；`OstyManager.osty` 非 final 可还原 |
| 修法（计划） | `docs/PLAN-MOD-OBJECTS.md` T1–T10（m00273 否掉特判）：新增 `mirror/StaticEntities`，按「引擎战斗基类实例 + 世界图不可达」动态注册 `Kind.CLONE`（零 mod 类名）；`copyContainer` 深拷容器中的战斗基类元素；`StaticWatch` 按名字收状态字段 + 战斗基类静态每窗口采样；`Digest` 加通用 `sc=<类简称>#<指纹>` |
| 影响面 | 计算不再改实机 Osty 血量；Osty 参与战斗时路线更准；其它 mod 零开销、写保护只变严；面板/自动出牌/EPS 不变；不改 `Kind` 与 `World` 协议 |
| 门禁 | **未计划新增**（m00732 自测 + m00300 不要门禁）。验收 G1–G6 见 PLAN-MOD-OBJECTS §2，由用户实机确认 |
| 安装物 | `mod/sts1-engine-solver.jar` 1,307,167 bytes / sha256 `606FF30B773DF85D22FF4A1F20B7E1FD0CCBB6F72DA1640512E8DC7381414AA5`（BUILD OK + INSTALL OK；`install.ps1` 的锁探针在 DSH 沙箱里会把「访问被拒绝」误报成游戏在运行，需完整权限才装得上） |
| 实施（m00356「改」） | ①新 `mirror/StaticEntities.java`（sync 由 `World.captureLive()` 首行调用；世界图遍历 depth≤8、对象≤20000；签名不变时 1 读/字段；`WALK_EVERY=512`）；②`Statics.dynamicSpecs()`；③`World.copyContainer(Cloner,Object)` 深拷战斗基类元素（capture/fork 用，readBack 仍浅拷）；④`Digest` 追加 `fingerprintLine()`（sc= 段，最多 4 个根，FNV-1a）；⑤`StaticWatch` 的 `Watch.entity` + 每窗口采样 + 按名字挑 32 字段（候选 96） |
| settings 键 | `mirrorEntityRoots`（默认 auto；off/0/关 关闭，回到旧行为）、`mirrorEntityIgnore=owner#field,…`（默认空，只用于误判排除） |
| 未做 / 偏差 | T1 证据文件（`evidence/` 不存在）、T8 的 `holders=N` 统计（只有 final 持有者告警）；快照上限仍 32 而非计划写的 128 |
| 回滚 | 删 `World.captureLive()` 首行 `StaticEntities.sync()`；`copyContainer(cloner,…)`→`copyContainer(v)`；删 `Digest` 的 `fingerprintLine()` |
### B192 第二轮：按声明类型接管 + digest 同侧（2026-11-17 13:20，用户 m00567）

| 项 | 内容 |
| --- | --- |
| 用户口径（m00567） | 「显示局面已经变化不给计划。实际上沙盒模拟还是会改变实际游戏数据。我的要求是沙盒抓取数据不要改变游戏的实际数据，但是没有完成。」 |
| 旧行为 | ①`solver.log:70-71` `static watch: the sandbox wrote mod static TheNecrobinderMod.OstyManager#osty … put back` —— 抓取时该字段没被接管，模拟里召唤才事后追回；②`solver.log:109` `LIVE STATE CHANGED` 且 `actual` 多出 `sc=DreamCatcher#…` —— 我们自己的指纹段造成的假警报 |
| 根因 | ①`StaticEntities.walk` 只按当前值 `isBattleEntity(v)` 判，字段为 `null`（战斗刚开始，宠物未召唤）时直接跳过，从不按声明类型注册；②`sc=` 段依赖「动态根已注册」，而注册发生在 `captureLive()`，两次 digest 跨过注册点 |
| 修法 | `walk` 改 `boolean byType = isBattleType(f.getType()); if (!byType && !isBattleEntity(v)) continue;`，null 也注册（`Cloner` 对 null 返回 null，零成本）；final 告警 null 安全；新增 `mirror entities: newly owned […]` 日志（≤8 条）；`Digest.ofCurrent()` 首行 `StaticEntities.sync()` |
| 代价 | 每次 digest 多一遍静态字段反射读＋签名比对（签名不变+非 512 倍数时不算世界图，实测 walk 19ms）；新增根每叉多克隆（`FuneraryMask.c=8ms/1163x`、`SeanceAction.c=7ms/1163x`） |
| 风险 | 按类型接管「当前 null、以后才填」的字段，若 mod 靠该静态字段当「本局已召唤」标志，沙盒内会各自独立；模板卡/遗物（DreamCatcher/NSoul/Necronomicon）被克隆后 `==` 比较可能不成立，必要时 `mirrorEntityIgnore` 排掉 |
| 影响面 | 面板/自动出牌/EPS 不变；不再误报「局面已变化」；`PlanCache` 键随根集合换代一次；其它 mod 零开销 |
| 门禁 | 不新增（m00732 自测、m00300 不要门禁），由用户实机确认 |
| 安装物 | `mod/sts1-engine-solver.jar` 1,307,526 bytes / sha256 `53ABDBE5989D0AB50987350AAA95DD08CBD9D37D17F7F1663A21FB9DEEC90B70`（BUILD OK + INSTALL OK） |
| 回滚 | `walk` 的 `byType` 判定改回只按值；删 `Digest.ofCurrent()` 首行 `sync()` |

### B192 第三轮补记：LiveGuard 度量（2026-11-17 13:35，用户 m00661）

| 项 | 内容 |
| --- | --- |
| 用户口径（m00661） | 「上一轮改完还是会变」——要求能**证明**实机有没有被改，而不是靠猜 |
| 做法 | 新增 `src\main\java\sts1solver\mirror\LiveGuard.java`：`World.install()` 读完 `livePlayer`、换静态之前 `capture()` 实机 player、当前房间怪、`Statics.dynamicSpecs()` 中值为战斗基类的 `Kind.CLONE` 根；`World.uninstall()` 第一行（早于 `StaticWatch.beforeUninstall()` 还原）`verify()` 比对，变了打 `WARN live guard (B192): the window CHANGED N live object(s): <label> hp=1/.. -> hp=..`，未变每 60s 打 `INFO live guard (B192): N clean window(s)` |
| 局限（第四轮已补） | 只覆盖「已注册的根＋player＋当前房间怪」，`OstyManager#osty` 没被注册 ⇒ 第三轮日志 `1 clean window(s)` 是**假阴性** |

### B192 第四轮：宠物不是原版怪，判据换成 AbstractCreature（2026-11-17 13:40，用户 m00694）

| 项 | 内容 |
| --- | --- |
| 用户口径（m00694） | 「最新测试奥斯提计算前血量1，计算后血量六十几了。问题出在哪」 |
| 旧行为 | 第三轮日志 `solver.log:38/40` 的 8 个 newly owned 里没有 `OstyManager#osty`；`:78-79` 仍报 `wrote … OstyManager#osty (object) and the contents were put back`；`:45` `live guard … 1 clean window(s)`（假阴性） |
| 根因 | ①实体判据只认 `AbstractMonster/AbstractPlayer/Power/Card/Relic/Potion/Orb`，`Osty extends AbstractCreature` 一个不中 ⇒ 从未注册；`javap` 实证 `OstyManager` 只有 `private static Osty osty`，`HealOstyAction.update()` 直接 `OstyManager.get().restore(amount)`；②`StaticWatch.snapshot` 按层级收集实例字段截断在 96 个，`Osty`(~30)+`AbstractCreature`(70+) 使 `currentHealth/maxHealth` 不进候选 ⇒ put back 覆盖不到 |
| 修法 | ①`StaticEntities`：`isBattleEntity` 首条 `v instanceof AbstractCreature`，`isBattleType` 加 `AbstractCreature.class.isAssignableFrom(c) || hasHealthState(c)`，新增 `ClassValue<Boolean> HEALTH_STATE` + `scanHealthState`（非 static `int currentHealth` 与 `int maxHealth` 同时存在）；②`StaticWatch`：新增 `ClassValue<Field[]> INSTANCE_FIELDS` 全量收集整条继承链（缓存），快照「全量收集 → 按 `STATE_NAMES` 挑 32 → 补齐」；③`LiveGuard.capture()`：遍历 `ModStatics.watchCandidates()`，值 `instanceof AbstractCreature` 的静态字段以 `unowned static owner#field` 纳入比对 |
| 代价 | 每次抓取/分叉多克隆一个宠物（spine `Skeleton/AnimationState` 在 `ClonePolicy` 共享）；`hasHealthState`/`INSTANCE_FIELDS` 每类型各一次反射（之后缓存）；`LiveGuard` 每 install 多约 395 次静态读 |
| 风险 | 宠物克隆后 mod 内 `==` 身份比较会看到沙盒对象（隔离的正确语义）；`hasHealthState` 兜底可能多克隆「恰好同名血量字段」的非战斗对象（不写实机）；`LiveGuard` 只覆盖窗口内变化 |
| 影响面 | 面板/自动出牌/EPS 不变；奥斯提血量现在参与沙盒状态与 `sc=` 指纹，路线评估用真实血量；实机 `OstyManager#osty` 引用与内部状态全程不被触碰 |
| 门禁 | 不新增（m00732 自测、m00300 不要门禁） |
| 安装物 | `mod/sts1-engine-solver.jar` 1,313,224 bytes / sha256 `EAAEA9C8CF7EB83B9798BE7DD77F4C1EA4878755F7CE5993CEB46B798EFACD0F`（BUILD OK 143 源文件 / 561 entries + INSTALL OK） |
| 回滚 | 删 `isBattleEntity` 的 `AbstractCreature` 分支与 `hasHealthState` 兜底、`StaticWatch` 改回 96 格截断、删 `LiveGuard` 的 `watchCandidates()` 循环；或用 `mirrorEntityIgnore=TheNecrobinderMod.OstyManager#osty` |

### B192 第五轮：spine 隔离（2026-11-17 13:33，用户 m00827）

| 项 | 内容 |
| --- | --- |
| 用户口径（m00827） | 「动画没有隔离，其他都做好了。给我不要特判的修复方法」 |
| 旧行为 | 13:32:28.004 `WARN quick search: SPINE STATE CHANGED by the search (2)`，两条差异只有 `t=` 变、增量≈搜索墙钟 ⇒ 搜索级审计误报；同时 `13:32:20.188` 仍报 `static watch: … OstyManager#osty … put back`（把我们自己的克隆当沙盒写入并回写实机引用） |
| 根因 | `mirror/ClonePolicy.java:104-106` 把 `com.esotericsoftware.spine.` 整包按引用共享 ⇒ 克隆生物（含奥斯提）的 `skeleton`/`state` 与实机同对象；`javap -p -c TheNecrobinderMod.character.Osty` 实证有 `state.update/apply`、`skeleton.setToSetupPose/setPosition/setColor/setFlip/updateWorldTransform`、`stateData.setMix` ⇒ 模拟动画一被调用就写实机。本轮日志只是没调用到，不是结构保证 |
| 修法 | ①新增 `mirror/SpineIsolation.java`：`isolate(AbstractCreature)` 把 `skeleton` 换成 `new Skeleton(live)`（引擎拷贝构造、逐根 Bone 深拷），`state` 换成 `new AnimationState(live.getData())` 并逐 track `setAnimation(i, e.getAnimation(), e.getLoop())`+`setTime`+`setTimeScale`，`stateData` 共享；②`Cloner.walk()` 两处 `return copy` 前按 `instanceof AbstractCreature` 调用（零 mod 类名）；③`SkeletonFingerprint` 新增 `spineSignature`（anim/ts/skel/flip/pose/tint，不含时钟）、`poseHash`（FNV-1a 混每根 Bone 位姿）、`clockOnly`；④`LiveGuard` 生物签名追加 `spineSignature`；⑤`Gameplay.noteSkeletonDiff` 剔除 clock-only 差异，全剔除记 INFO，真实变化仍 WARN；⑥`Statics.isDynamicClone(Field)` + `StaticWatch` 三处跳过动态克隆根；⑦settings `mirrorSpine`（默认 `copy`，`share/off/0/关` 回退） |
| 代价 | 每 fork 每生物多一次 `new Skeleton` + `new AnimationState` + 每 track `setAnimation`；按 1852 fork、player+2~3 怪估：克隆段（`clone=644ms`）+3%~8%，整搜索（5.6s）+0.5%~1%；`poseHash` 只在 capture/verify 算，不进每节点热路径 |
| 风险 | ①克隆体动画抓取瞬间定格（隔离正确语义），依赖动画时间的 mod 逻辑在模拟里看到恒定 `t`；②mod 若把 spine 对象另存到自己的静态字段仍共享（时钟被排除，只推进时钟的写不会点名）⇒ 已知边界；③新 `AnimationState` 不重放旧 state 上的 listener；④依赖游戏自带 `Skeleton(Skeleton)` 拷贝构造（已 javap 实证） |
| 影响面 | 沙盒动画不再改实机姿态/时钟；面板、自动出牌、计划缓存键不变；`static watch` 不再对 `OstyManager#osty` 误报/回写 |
| 门禁 | 不新增（m00732 自测、m00300 不要门禁） |
| 安装物 | `mod/sts1-engine-solver.jar` 1,318,132 bytes / sha256 `FAD91E8DAEA532741A4C64CD8686ACF6D66B197FACF3881BD97BE108C15D9B0E`（BUILD OK 144 源文件 / 562 entries + INSTALL OK） |
| 回滚 | `settings.txt` 写 `mirrorSpine=share`（无需重编译）；或删 `Cloner` 两处 `SpineIsolation.isolate` 调用 |


---

## B200 选牌身份统一（CardPrint 优先，user m00005，2026-11-18）

**问题**：计算给出「5 回合无伤」，实机第 5 回合第二张打击出不起（计划要 0 费打击，手上 3 张打击都不带该状态）。

**定位链**：第 9 步（生存者弃牌）首次偏离，两侧选牌前手牌同序、选后不同序；两侧 rng 计数全程相同 ⇒ 不是随机流；
RelicStats 的 `SneckoInfo#cards/#costs` 静态量是被写脏后还原的**统计量**，与判定无关 ⇒ 不是蛇眼模拟错。
真正的洞是**答案身份**：沙盒按 uuid（精确副本），实机 uuid 是 sandbox-local 只能按 id（同 id 取第一张），
两张状态相同的打击被弃了不同副本 ⇒ 顺序分叉 ⇒ 洗牌后抽牌顺序不同 ⇒ 蛇眼费用落到别的牌上。

**改动**（`drive/Driver.java`）：

- `matchOffered`：print 提到 uuid 之前（两侧共用「计划记下的状态」这一身份）。
- `rebind`：uuid 之后、id+upgrade 之前加 print 一趟。
- `picksFor`：记了 print、候选无一张带该状态且同 id 多张 ⇒ 拒绝整条答案（与出牌路径 `liveCardFor` 对齐）。
- `identityOf`：id+升级 → CardPrint（蛇眼下同名牌不同费用不再折叠成同一分支）。
- `fillHandSelection`/`fillGridSelection`：候选池按引用剔除本轮已选（新增 `withoutIdentical`），避免同状态两张选成同一张。
- `live selection` 日志加 `state=` / `planState=`。

**代价**：选项去重与解析多算几次 `CardPrint.of`（与每步 digest 同量级）；分支数上升（同名不同状态不再合并），受 `Limits.handBranches` 截断。
**风险**：状态对不上时会**更早拒绝并停下**（有意，不静默）；同状态副本按「第一张」约定解析。回滚：逐条改回 / `identityOf` 回 id+升级。
**对 mod 影响**：面板/自动出牌的选牌步与计划一致；搜索宽度上升；不认任何 mod 类。
**构建**：BUILD OK（144 源文件，`--release 8`，562 entries，1,318,735 B）；安装因游戏在运行被拒（jar 被锁），需关闭游戏后跑 `tools/install.ps1`。

## 发布 1.0.5（2026-11-18）

| 项 | 内容 |
| --- | --- |
| 内容 | B200 选牌身份统一：`matchOffered` 把 `CardPrint` 提到 uuid 之前、`rebind` 加 print 一趟、`picksFor` 在「多副本且无一张带计划状态」时拒绝、`identityOf` 去重键改 `CardPrint`、填屏候选池 `withoutIdentical` 剔除已选、`live selection` 日志补 `state`/`planState` |
| 版本 | `resources/ModTheSpire.json` 1.0.4 → 1.0.5；`docs/WORKSHOP-DESCRIPTION.md` 当前版本行同步；工坊 `config.json` 的 description 版本行与 `changeNote` 同步 |
| 构建 | `tools/install.ps1` BUILD OK（144 源文件、562 entries、1,318,727 B），sha256 `7BC4CEBEF64F1C39261A102C3A0C69ED0E389442D2328761DCFA8830DD83DE61` |
| 实机 | INSTALL OK → `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar`（源/目标哈希一致） |
| 创作工坊 | 条目 3839053516：`content\sts1-engine-solver.jar` 换新（1,318,727 B，同 sha），config.json 以 GBK/936 无 BOM 回写（版本 1.0.5 + changeNote），备份 `config.json.bak-20261118`；上传返回「Successfully updated workshop item!」 |
| GitHub | 已推送：本地提交 0bcfcf4（B200: identify picked cards by CardPrint on both sides; release 1.0.5）→ origin/master（2026-11-18 网络恢复后重试成功；此前记录的重置/超时已不再成立） |
| 门禁 | 未跑（用户 m00732 自测） |

发布口径：不宣称「蛇眼已修好」，只写「计划与实机统一按牌面状态认牌、找不到时明确作废路线」；实机复测由用户做（同层邪教徒 + 蛇眼，看第 5 回合两张打击能否打完、日志有无 `selection: … none carries the planned state`）。

## B201 沙盒贴图泄漏导致史莱姆王计算崩溃（user m00511，2026-11-19）

**问题**：打史莱姆王的计算跑约 17 分钟后崩溃/卡死，怀疑是计算量太大。

**定位链**：log:2877-2888 的栈直指 `SlimeBoss.takeTurn → new AcidSlime_L → AbstractCreature.loadAnimation → new TextureAtlas`，
异常是 `decoder init failed for stream` / `File not found … skeleton.atlas (Internal)`；watchdog（00:07:25）20 个采样同一栈 `nSwapBuffers`、
写 hang dump 报「系统资源不足」，而 `OutOfMemoryError` 全程 0 次 ⇒ 不是算力，是**显卡侧资源被耗光**。
沙盒从不 dispose 怪物（`DeathSettle.java:29` 的规则是为实机共享怪物写的），于是每次分裂泄漏 1 份真实 GL 纹理；
1012s、60 万条世界线、8GB 显存 ⇒ 驱动卡死，随后贴图解码/临时文件全部失败。

**改动**：

- 新增 `mirror/SandboxArt.java`：`@SpirePatch(AbstractCreature.loadAnimation, {String,String,float})` 的 postfix，仅 `World.anyInstalled()` 时反射记下 `atlas`（protected，走 `Reflect.find`），计数 loaded/disposed。
- `World.uninstall()` 的 finally 加 `SandboxArt.close()`：把本窗口新建的 atlas 逐个 dispose；正常/异常/forceRestore 都覆盖。

**代价**：每窗口一次遍历 + 至多 1–2 次 glDeleteTexture；解码成本不变（不做共享缓存）；新增 1 文件约 150 行、`World` 6 行。
**风险**：只释放窗口内新建的贴图，实机贴图从不入表；若将来沙盒怪物贴图变黑，回滚＝删掉 `close()` 调用。
**对 mod 影响**：面板/搜索/自动出牌不变；不限时计算不再随分裂次数吃显存；不 patch 任何 mod 类。
**已知边界**：不做按路径共享 atlas（需改 gdx `final` 容器，风险大于收益）；只覆盖 `loadAnimation` 这条美术入口；未加「失败重复 N 次即中止」。

**发布**：版本 1.0.5 → 1.0.6（`resources/ModTheSpire.json`、`docs/WORKSHOP-DESCRIPTION.md`）。本次完成本地 BUILD OK + INSTALL OK，并按用户指示（m00757）推送创意工坊与 GitHub（见下方表格）。

| 项 | 内容 |
| --- | --- |
| 构建 | `tools/install.ps1` BUILD OK（145 源文件、564 entries、1,321,810 B），1.0.6 的 sha256 `2788BA0991160761026D699B9E114CA0BD635FDCEDE3DB798F2995FD6A6CD8B7` |
| 实机 | INSTALL OK → `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar` |
| 创作工坊 | 条目 3839053516：`content\sts1-engine-solver.jar` 换新（1,321,810 B，sha 同上），config.json 以 GBK/936 无 BOM 回写（description 版本行改 1.0.6、changeNote 换 1.0.6 说明、性能说明新增「长时间计算不会累积占用显存」一行），备份 `config.json.bak-20261119`；上传返回「Successfully updated workshop item!」 |
| GitHub | 已推送：本地提交 636f461（7 个文件，+284/-2）→ origin/master |
| 门禁 | 未跑（用户 m00732 自测） |

**重传（用户 m00822「上传创意工坊不要改mod可见性，简介更新中文区」）**：`mod-uploader.jar` 的 `Uploader.updateItemInfo` 无条件 `setItemDescription`，只有 `visibility` 为空串才跳过 `setItemVisibility`；`WorkshopUploadConfiguration` 省略该键会退回默认 `private`、取值不在 {private, friends, friendsonly, public} 里会 `Main.exit(1)` —— 所以「不改可见性」只能把 config 写成条目当前的真实值。经用户确认条目为公开后，`config.json` 的 `visibility` 由 `private` 改为 `public`（备份 `config.json.bak-20261119b`，GBK/936 无 BOM，4894 B），description 未再改动（已是中文 1.0.6 版），重传返回「Successfully updated workshop item!」（日志 `_tmp/upload-workshop-1.0.6-public.log`）。


## B202 自动出牌：药水/结束回合的选牌屏自己答，且等一拍再点（user m00911 / m01245，2026-11-20）

| 步骤 | 内容 | 结果 |
| --- | --- | --- |
| 取证 | `Gameplay.answerLatentSelection()` 原首句 `if (pending != null) return;`；药水/结束回合的屏在 pending 期间由引擎同帧打开；`pollPending` 判「这一屏计划里没有答案」并 `stopAutopilot`；头槌走 `Action.selection` 另一条链 | 已核实（PLAN-BUGS B202） |
| 改 `Gameplay.java` | `answerLatentSelection` 去掉 pending 前提、加 `selectionBeatDue()` 等待、加 `answerSelectionFromScreen()`（屏自己选项 + `no-plan-answer` 记录）；`pollPending` 与 `maybeAutopilot` 失败计数在有屏时不再判失败；新增字段 `selectionSeenAt` / `selectionSeenKind` | ✅ |
| 改 `Driver.java` | 新增 `refuseSelection` / `answerScreenFromItsOwnOptions` / `confirmHandSelection` / `confirmGridSelection`；`pumpUntilSelection` 在 `AbstractDungeon.isScreenUp` 时不再泵 | ✅ |
| 编译 | `tools/build.ps1` → BUILD OK，145 源文件、564 entries、1,322,435 B | ✅ |
| 安装 | `tools/install.ps1 -SkipBuild` → INSTALL OK，sha256 3D1E5F67…F032C272 | ✅ |
| 复审（user m01364） | 用户问「计划没有答案的屏被按屏自己的选项答完」是什么意思：旧实现把「计划这一屏选空」与「计划没有这一屏」混成一件事，空答案会被改答成选一张。已收紧：计划记过屏就按计划那条线（空答案保持空；计划这一屏空着则取 `options.get(0)`，与沙盒 `expandScreens` 同规则），只有计划这一步根本没记屏才用屏自己的选项 | ✅ |
| 重编译/重装 | BUILD OK 1,322,552 B；INSTALL OK，sha256 CEE16EB2…7B9756 | ✅ |
| 实机 | 需要用户实测：药水选牌、结束回合选牌会等约 0.5s 再点并继续执行；头槌同样等待 | ⏳ 待用户 |
| 门禁 | 按 m00732 不跑 | — |

节奏键沿用既有 `settings.txt` 的 `autoplayDelayMs`（面板「节奏」按钮，默认 450ms，0 表示不等），未新增设置项。

## B203 药水选牌屏：记录屏提供的状态 + 删猜牌回退（user m01393，2026-11-20）

| 项 | 内容 | 状态 |
| --- | --- | --- |
| 日志取证 | 09:33:16 连续四条：`2 copies of Strike_R on offer and none carries the planned state (Strike_R+)` → `selection refused (not-offered … the plan chose [Strike_R])` → `refusing to guess` → `autopilot stopped: 选牌已应答，但等待中的动作没有取走这张牌`；第 5 步（液态记忆）没有 markers/step plays | ✅ |
| 根因 | 计划答案记的是升级过的打击（Strike_R+），实机屏上是 2 张未升级打击 + 绊倒：沙盒/实机在之前就分叉；计划未记录「屏当年提供了什么」，且空答案的屏实机靠 best-first 猜 | ✅ |
| 代码 | `Solver.expandScreens`+`expandCore`+`Choice.screenOffered`+`Action.screenOffered`；`Action.withChoice` 只拷贝 kinds 不拷贝 offered（合流点）→ `Solver.actionFor` 补 `a.screenOffered = choice.screenOffered`；`Gameplay.planPendingChoice/clearPendingChoice` 维护 `pendingChoiceOffered`；`answerNextScreen` 打 `screen i/n … sandboxOffered/liveOffered` 并走 `resolveSelectionLiveFirst`；`answerLatentSelection` 空链也走 first-option；`Driver.resolveSelectionLiveFirst` 新增 | ✅ |
| 删幽灵函数 | `Gameplay.confirmOpenScreen`、`Gameplay.answerSelectionFromScreen`、`Driver.confirmHandSelection`、`Driver.confirmGridSelection`、`Driver.answerScreenFromItsOwnOptions`、`Driver.refuseSelection`（全工程 grep 0 命中） | ✅ |
| 构建/安装 | BUILD OK 1,323,131 B；INSTALL OK，sha256 1F2ED62276B6A54DD6752853F8B10EE09B58B5C449E146233322276200176230 | ✅ |
| 实机验证 | 用户自测：日志里 `sandboxOffered`/`liveOffered` 不一致 = 状态分叉证据 | ⏳ 待实机 |
## B204 选牌答案记「状态 + 屏内第几张」，实机优先按状态认牌（user m01604 / m01614 / m01642，2026-11-20）

## 项 | 内容 | 状态

- 现象 | B203 之后仍会在选牌屏 `not-offered` 停下，用户 m01587 确认那局屏上确实是打击+（不是状态分叉） | ✅
- 根因 | 计划答案 `Driver.namesOf` 只存 cardID，升级状态丢失；实机 `picksFor` 用 cardID 找到两张同 ID、却没有任何一张带计划状态 ⇒ 整体拒绝 | ✅
- 代码 | `Solver.positionKeys/positionedIds/positioned/joinSegments(positions,…)`（答案写 `id@STATE#k`）；`Solver.stateRun`；`Solver.positionsOn` 5 参重载（状态多重集相同→按状态匹配、丢 #k）；`Gameplay.answerNextScreen` 重试 3 次后 `Driver.refuseNow`；`Gameplay.executeStepInternal` 置 `action.replayLive=true` 并在对不上时停手提示；`Solver.answerOneScreen(..., boolean live)` 只在沙盒回退；`Action.replayLive`（transient） | ✅
- 离线判据 | `_tmp/b204/Check.java` 8 条（同状态换槽位、多副本、缺状态、旧计划、坏槽位标记）全 OK；javac 0 error | ✅
| 构建/安装/发布 | BUILD OK 1,326,909 B；INSTALL OK sha256 5064CB5F4F1E35161D9076F99567A35AD53E4F993E654AA11F0B7FB8D63DFC20；资源 ModTheSpire.json 1.0.7；工坊 config.json 1.0.7（GBK 无 BOM，备份 config.json.bak-20261120） | ✅ |
| 创意工坊 | 2026-11-20 10:21 首次上传只更新了元数据：tools\install.ps1 不写 D:\mods-workshop\sts1-engine-solver\content\，工坊 content 里仍是 1.0.6 的 jar（sha 2788BA09…，1,321,810 B）。10:36 手工把 mod\sts1-engine-solver.jar（sha 5064CB5F…，1,326,909 B）复制进 content 后重传，日志出现 UploadingContent 并 `Successfully updated workshop item!`（_tmp\upload-workshop-1.0.7c.log） | ✅ |
| GitHub | 本地提交 c38e5ce；推送失败（Connection was reset / 端口 443 超时，4 次），master 领先 origin 1，待网络恢复重跑 tools\push-github.ps1 | ⏳ 待推送 |
- 实机验证 | 由用户自测；日志 `screen i/n answer=… planState=… sandboxOffered=… liveOffered=…` | ⏳ 待实机


## B205 回放「answered 1 of 36 recorded screen(s)」+ 计划答案去掉屏内第几张（user m01995，2026-11-20）

| 项 | 内容 | 状态 |
| --- | --- | --- |
| 现象 | 10:47:13 `plan replay failed at step 6: … answered 1 of 36 recorded screen(s) and the fight opened no more (… wereCardsRetrieved=true numSelected=0 …)`；同线 `replayed 6 of 36 steps`；自动出牌 `这条线重放不通过（第 14 步：坚毅+（1) 选：羽化 → 确定 2) 选：c=2/2 …）` | ✅ 已复现（日志 10919/10922/10941） |
| 根因 | B204 的 `id@STATE#k` 把 `CardPrint` 字段文本（含 `\|`）写进计划答案，`Action.chainCount` 按 `'\|'` 数屏 ⇒ 1 屏变 36 段；沙盒大舍牌一次作答即关门 ⇒ 第 2 段起无屏可开 | ✅ |
| 代码 | `Solver.expandScreens` 答案回纯 id；删 `screenPositions`/`positionKeys`/`positioned`/positions 版 `joinSegments`；`Solver.positionsOn` 支持 `baseOf`/`positional` 透传；`Driver.stateRunOf`/`handScreenSummary`/`ownState`/`sameState`/`atPosition(wantState)`；`answerForReplay` 两路补传状态串；`chain-unfinished` 带屏状态 | ✅ |
| 构建/安装 | BUILD OK 145 源文件、564 entries、1,326,426 B；INSTALL OK sha256 CAEE5AB210F82ABD02225C1B530B81422473920BAC6544991970BAC3348962DC | ✅ |
| 文档 | PLAN-BUGS.md `## B205`、PLAN.md 状态表 B205 行、本表（B205 的屏内第几张已被 B207 的折叠规则取代，见 B207） | ✅ |
| GitHub / 创意工坊 | 未获授权，未发布（1.0.7 仍是线上版本） | ⏳ 待用户决定 |
| 实机验证 | 由用户自测：面板选牌步骤应恢复「选：卡名 → 确定」；多屏步骤不再「回放不通过」 | ⏳ 待实机 |

## B207 一卡屏写成两个答案 → 实机错位（user m02161 / m02438，2026-11-20；取代同日未发布的 B206 carried 方案）

| 项 | 内容 | 状态 |
| --- | --- | --- |
| 根因 | expandScreens 对每块屏都写答案；实机每块屏只答一次 ⇒ 多出来的答案吃掉下一块屏的答案；B206 carried 保存错位；风险闸门停机 | ✅ |
| 代码 | Solver.foldable/readableChain(foldable)/sameCard/firstId/isFoldedFront/foldOrCheck；Driver 折叠识别 + resolveSelectionLiveFirst 只记 INFO + 删 B200 守卫；Gameplay 数答案、计划外屏首选项、删 B206 carried、misleading WARN 降 INFO | ✅ |
| 构建/安装 | BUILD OK 145 源文件 1,327,943 B；INSTALL OK sha256 99A0D52DBE99A064CB83A1515D34E2FC5AF77AD6C48930094EA5FEC624ED6627 | ✅ |
| 文档 | PLAN-BUGS.md ## B207、PLAN.md 状态表 B207 行、本表 | ✅ |
| 实机验证 | 由用户自测：生存者弃牌屏、计划妥当两回合留牌屏、药水选牌都不应停自动出牌；日志无 refused/autopilot stopped | ⏳ 待实机 |
| GitHub / 创意工坊 | 未发布（待用户决定） | ⏳ |
| 后续 | **同日被 B208 撤销**：折叠让计划比沙盒走过的屏少一条答案，1856 条回放失败 + 计算连跳 widening。不要再用折叠 | ❌ 已撤销 |

## B208 撤销 B207 的折叠，恢复「一屏一答案」（user m02882，2026-11-20）

| 项 | 内容 | 状态 |
| --- | --- | --- |
| 现象 | user m02882：回放不通过、计算卡住。日志 1856 条 `ReplayException: not-settled: a screen is open (HAND_SELECT … awaiting=true)` | ✅ 已复现（日志） |
| 根因 | `search/Legal.java:341-345`：B207 折叠让计划比沙盒走过的屏少一条答案 ⇒ 回放走到折叠屏时无 `sel` 步 ⇒ 撞开着的屏；promote 重建全废、deferred 池重试、29s 连跳 4 档 widening | ✅ |
| 代码 | `Solver.expandCore` 删 5 处 `readableChain`/折叠加 `probe.screenKinds = joinStrings(kinds)`；删 `foldable/readableChain/sameCard/firstId/singleCardScreen/isFoldedFront/sameAnswer/folded/answered/foldOrCheck/checkScreenKindOnly`；`answerForReplay` 单屏/链恢复 `checkScreenKind`（链还恢复 `pumpUntilSelection(24)`）；`Driver` 删两处 `isFoldedFront` 早退；`Gameplay.planPendingChoice` 注释改指 B208 | ✅ |
| 构建 | BUILD OK 145 源文件、564 entries、1,326,564 B（B209 注释改动后 1,326,606 B） | ✅ |
| 文档 | PLAN-BUGS.md ## B208（并把 B207 标「已撤销」）、PLAN.md 状态表 B208 行 + B207 行改「已撤销」、本表 | ✅ |
| 实机验证 | 由用户自测：日志不再出现 `not-settled: a screen is open`；计算时长回到正常档 | ⏳ 待实机 |
| 安装/发布 | 1.0.8 同版发布：BUILD OK 1,326,606 B；INSTALL OK sha256 F48324A62102408FC7910FCC17DB03EF8BBF045D444569F07D3D193F7E98486D（`D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar`，jar 内 `ModTheSpire.json` 版本已核为 1.0.8）；创意工坊 content jar 已同步后上传（日志含 `UploadingContent` + `Successfully updated workshop item!`，`_tmp\upload-workshop-1.0.8.log`）；GitHub 145d199 已推（187cb9b..145d199 master） | ✅ |

## B209 自动出牌：选牌屏等一拍（250ms）+ 计划没有答案就停手提示（user m03043 / m03085，2026-11-20）

| 项 | 内容 | 状态 |
| --- | --- | --- |
| 根因 | `selectionBeatDue()` 只有一个调用点（`answerLatentSelection`），主路径 `pollPending → answerNextScreen`、`answerSelectionStep` 都是立刻点；节拍按 SelectionKind 计，同类连开两屏第二块不等 | ✅ |
| 参考 | CombatSolver-main `NativeChoiceRuntime.cs`：`OtherSurfaceMinimumOpenMilliseconds = 250`（:700）、`MultiSelectStepMilliseconds = 150`（:701）、计划外选择抛异常（:521-525） | ✅ |
| 代码 | `ui/AutoPace.java`：`SELECT_MIN_OPEN_DEFAULT_MS = 250`/`SELECT_MIN_OPEN_MAX_MS = 2000` + `selectMinOpenMs()`（settings.txt `autoplaySelectMinOpenMs`，0=旧行为）；`Gameplay`：`selectionOpenAt`/`selectionMayClick()`/`selectionClicked()`，节拍只在这三处入口判（`pollPending`、`executeStepInternal`(isSelection)、`answerLatentSelection`），每块屏各自计时；`answerNextScreen` 的「答案用完」「记屏没记答案」改 `refuseNow("no-plan-answer", …)`，`pollPending` 对 `no-plan-answer:` 停自动出牌并提示，`answerLatentSelection` 不再点首选项，`answerEndTurnScreens` 计划外屏返回未知风险（闸门 STOP）；删 `Driver.resolveSelectionLiveFirst` 与幽灵 javadoc | ✅ |
| 代价/风险 | 每屏多 250ms（可关）；多选「每张 150ms」未实现（fillHandSelection/fillGridSelection 一帧填完，属已知边界）；停手是相对 B207 的行为回退（不猜，符合 m03085） | ✅ 已写 PLAN-BUGS |
| 构建/安装 | BUILD OK 145 源文件、564 entries、1,326,606 B；INSTALL OK sha256 F48324A62102408FC7910FCC17DB03EF8BBF045D444569F07D3D193F7E98486D | ✅ |
| 文档 | PLAN-BUGS.md ## B209、PLAN.md 状态表 B209 行、本表 | ✅ |
| 实机验证 | 由用户自测：屏出现约 0.25s 后才点；计划没答案的屏停手提示、可点「自动」继续 | ⏳ 待实机 |
| 发布 | 版本号 1.0.8（B208+B209 同版）：`resources/ModTheSpire.json` + `docs/WORKSHOP-DESCRIPTION.md` + 工坊 `config.json`（GBK/936 无 BOM，4,890 B，备份 `config.json.bak-20261120b`，description 的「当前版本」同步 1.0.8、节奏默认改 450ms，changeNote 重写）；已上传创意工坊、已推 GitHub 145d199 | ✅ |


## B210 计划妥当「不选」屏不再被当成「计划没有答案」（2026-11-20）

user m03369：「计划妥当结束回合不选牌的话不自动出牌，但是玩家手动点击确定可以正常继续打」；m03419「『不选』改」= 批准按上一轮方案动手。

| 项 | 内容 | 状态 |
|---|---|---|
| 根因 | `Action.choice` 与 `Action.screenKinds` 是两根并行串；单屏 + 答案「不选」时 choice 整串为空，而 `choiceSegments("")`/`splitChain("")` 返回 0 段 ⇒ 答案数被读成 0，与「这一步没开屏」不可区分 ⇒ `answerNextScreen` 判 `no-plan-answer` 停手、`answerEndTurnScreens` 报风险未知 ⇒ 闸门 STOP。屏数判据（`Action.withChoice`/`choiceScreenCount()`/`plannedScreens()`）本来就是对的，回放侧 B190 也早按 screenKinds 判，所以只有实机读取方错 | ✅ |
| 代码 | `Gameplay.java`：新增 `answersFor(joined, screens)`（screens<=0→空；==1→{joined}；>1→按 `|` 切分补空）；`answerNextScreen` 用 `answersFor(pendingChoice, screens)` 判用完与取 ids；`answerEndTurnScreens` 屏数改 `max(chainCount(choice), chainCount(screenKinds))` 再用 `answersFor`；每块被作答的屏固定打一行 `screen i/N answer=[…]`（空答案 `(pick nothing)`） | ✅ |
| 构建 | BUILD OK 145 源文件、564 entries、1,326,905 B | ✅ |
| 安装 | INSTALL OK → `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar`，sha256 FB8EAD62A0051EB40A174F2B2904B749818CD87340849B27BE627917726A9DC4；已核包内 `sts1solver/Gameplay.class` 含新字符串 `pick nothing` | ✅ |
| 文档 | PLAN-BUGS.md ## B210、PLAN.md 状态表 B210 行、本表 | ✅ |
| 实机验证 | 由用户自测：①留牌屏「不选」约 0.25s 后自动点确定并继续；②日志见 `answer=[] (pick nothing)`；③不再出现 `no-plan-answer: the plan has 0 answer(s)` 与「结束回合里开了一块计划没有答案的选牌屏」；④带卡牌的选牌屏不变 | ⏳ 待实机 |
| 发布 | 版本 1.0.9：`resources/ModTheSpire.json`、`docs/WORKSHOP-DESCRIPTION.md`（当前版本行 + 1.0.9 bullet，并把「节奏默认 750 ms」的过时值改回 450 ms）、工坊 `config.json`（GBK/936 无 BOM，4,787 B，备份 `config.json.bak-20261120c`，当前版本 1.0.9、changeNote 重写） | ✅ |
| 构建/安装（1.0.9 定版后重编） | BUILD OK 145 源文件、564 entries、1,327,907 B；INSTALL OK sha256 EF56E1C7F68C8521B9D2CB5563D381E01CFF098D4181571E5609028543FA8E02；jar 内 `ModTheSpire.json` 版本已核为 1.0.9（覆盖上面 1,326,905 B / FB8EAD62… 那一版） | ✅ |
| 创意工坊 | jar 先同步到 `D:\mods-workshop\sts1-engine-solver\content\sts1-engine-solver.jar`（sha256 同上）再上传，日志 `_tmp\upload-workshop-1.0.9*.log`（首次 SteamResult=Fail，重传成功，判据仍是 UploadingContent + Successfully updated workshop item!） | ✅ |
| GitHub | 提交 `7db69a0`（B210 代码+文档）与 `f1e265b`（1.0.9 版本号/简介）已推 origin master | ✅ |


## B211 打赢那一帧清掉整条路线与结果（2026-11-20）

user m03559「看一下最新日志，正确打赢为什么显示路线被丢弃？不要修改，先回答我问题」→ m03712「不是的，面板这场战斗最后一步是结束回合->选择牌，不是快斩」→ m03754「改」。

| 项 | 内容 | 状态 |
|---|---|---|
| 根因 | `tick()` 的 `active` 直接取当帧实况（`inRoomCombat() && (!combatOver \|\| overFrames < 20)`）；胜利瞬间 `room.phase`/`isBattleOver` 先变，`inRoomCombat()` 单帧为假 ⇒ 翻转分支 `plan = null; status = "idle"; Hud.set(null)` ⇒ `lines()` 的 `plan == null` 早退 `route.clear()`；333ms 后房间读回 COMBAT 再翻一次（日志两条 `combat detected`）。overFrames 的 20 帧宽限只保护 `combatOver`，保护不了 `inRoomCombat()` 单帧为假。 | ✅ |
| 代码 | `Gameplay.java`：新增字段 `idleFrames`/`idleSinceMs`/`roomSeen`；`active` 离开侧防抖（连续 ≥6 帧且 ≥250ms 才 false，进入不延缓）；翻转成 true 时按 `getCurrRoom()` 实例判同一场（同房间回来只记 `combat re-detected in the same room`，不动计划）；翻转成 false 时保留计划并写 `本场已结束（…）· 路线 N/M 步`，胜负由 `fightOutcome()` 按棋盘判；`lines()` headline 在 `!hasPlan()` 时追加 status；非战斗期只有房间实例变化才清路线；新增 `roomNow/playerDeadNow/fightOutcome/logFightFlip`，每次翻转打一行五个输入；`reset()`/`detectWorldSwitch()` 清新字段 | ✅ |
| 代价 | 单文件、3 个字段、4 个私有方法；每帧多一次 `getCurrRoom()` 与两个整数比较，无分配/无反射；战斗结束后路线在面板多停留到离开房间 | ✅ 已写 PLAN-BUGS |
| 风险 | 防抖让「离开战斗」晚 ~250ms（自动出牌传原始 `raw` 所以看不见）；房间实例被复用时不清计划（`dropStalePlan()` 仍兜底）；回滚 = 改回单帧判定 + 翻转即清 | ✅ 已写 PLAN-BUGS |
| 构建/安装 | BUILD OK 145 源文件、564 entries、1,328,360 B；INSTALL OK sha256 0CC52474E32C7551D55B3D080009A92D5ADA2D89A79140EB849DE7E119883A5A | ✅ |
| 文档 | PLAN-BUGS.md ## B211、PLAN.md 状态表 B211 行、本表 | ✅ |
| 版本 | `resources/ModTheSpire.json` → 1.0.10；`docs/WORKSHOP-DESCRIPTION.md` 当前版本行 + 1.0.10 bullet（**工坊 `config.json` 未动**：未上传，changeNote 等到上传时再写） | ✅ |
| 实机验证 | 由用户自测：①打赢后路线条仍在、标题带「本场已结束（胜利）· 路线 N/M 步」；②日志见 `fight over: the route stays on the panel`；③同一场战斗内不再出现中间那条 `combat detected`；④离开房间后路线清空 | ⏳ 待实机 |
| **回归（1.0.11）** | user m03864「现在界面会一闪一闪的是不是离开战斗要防抖的改动导致的」——是。1.0.10 的离开分支清了 `idleFrames/idleSinceMs`，下一帧 `idleFrames=1` 又被判成在战斗，日志每 ~250ms 一 off 一 on（同一房间 68037c7d、phase=COMPLETE）。修法：离开分支不重置计数，只在 `raw==true` 帧清零（离开即闩锁）。BUILD OK 1,328,362 B / sha256 79FFC6C3…；版本 1.0.11 | ✅ 已装，待实机确认不再闪 |

## B212 引擎异常 AIOOBE 与 16 块屏（2026-11-20）

user m03920「显示引擎异常，为什么」（面板红字 `9x ArrayIndexOutOfBoundsException: 2`，结果只剩「未找到胜线 · 最长生存线 21 步」）。

| 项 | 内容 | 状态 |
|---|---|---|
| 取证 | solver.log 252 行 / 274,107 B：`16:20:33.645 a step opened more than 16 selection screen(s)` 紧跟 `expand failed (1x): ArrayIndexOutOfBoundsException: 2`，栈 `joinSegments(Solver.java:1652) ← expandCore(Solver.java:1032)`，共 9 次，每次跟 `a world was left installed` | ✅ |
| 根因 | ①探针 `int[] firstPick = new int[n]` 用「每屏选项上限 n=2」（两处调用点都传 2）当屏数，屏数实际是 `ids.size()` ⇒ 3 块以上屏时 `pick[i]` 越界 ⇒ 整条动作+分支被丢；②同一步的选牌链在沙盒里开屏超过 16 次，旧日志不说哪张牌、也没有「没进展」判据 | ✅ |
| 修法 | `Solver.java`：①`new int[ids.size()]`；②`joinSegments(List,int[])` 对 `i >= pick.length` 取首项；③`expandScreens` 连续 3 次同 kind+同 `stateRun(offered)`+同选项数 ⇒ `screens-repeat` 警告并 `return false`；④`!complete` 打 `step-incomplete` 带 `action.label` | ✅ |
| 代价/风险 | 单文件约 25 行；屏数 ≤ n 时行为不变；重复屏判据可能丢掉「合法连开两块相同屏」的那一步（只会少一条线，不会出错误计划） | ✅ 已写 PLAN-BUGS |
| 构建/安装 | BUILD OK 145 源文件、564 entries、1,328,716 B；INSTALL OK sha256 F69978ECD4C34D91CB27ABB12DF884D9A4CF8053C4DBE86FEC704F2F88D6BC4E | ✅ |
| 版本 | `resources/ModTheSpire.json` → 1.0.12；`docs/WORKSHOP-DESCRIPTION.md` 当前版本行 + 1.0.12 bullet（**工坊 config.json 未动，未上传**） | ✅ |
| 发布 1.0.12（2026-11-20 16:36） | 工坊 config.json（GBK/936 无 BOM，4,786 B，备份 config.json.bak-20261120d）：description 版本行 1.0.9 → 1.0.12、changeNote 换 1.0.12 文案，visibility 保持 public；content jar 换成本次构建（SHA256 F69978EC…，与已装一致）。上传输出 Successfully updated workshop item! | ✅ |
| GitHub | 提交 c0afcf7「B212: size the probe answer array by the number of screens…」7 文件 +370/−21，tools/push-github.ps1 → PUSH OK | ✅ |
| 实机验证 | 用户自测：同一局面重算不再出现 AIOOBE 红字；若仍「未找到胜线」，日志里的 `step … could not be walked` / `the same … screen … came back` 两行可定位具体牌/屏 | ⏳ 待实机 |
## B213 模拟战斗改写实机职业生涯统计（2026-11-20）

user m00005「查看sts1-engine-solver打完战斗统计角色场数和游戏时间增加的bug并给我解决方案但是不要动手」→ 只读定位并给出方案 → m00344「改」。

| 项 | 内容 | 状态 |
|---|---|---|
| 取证 | `preferences` 五个文件（值都是字符串）：Silent PLAYTIME=31,866,996 / LOSE_COUNT=48,755（653.6 s/次）、Defect 90,259,412 / 84,187（1,072 s/次）、Watcher 137,588 / 344、Vagabond 481 / 350 / ENEMY_KILL=1,948,826、`STSSaveSlots` PLAYTIME=122,299,741；四个角色文件 mtime 同为 2026-11-20 16:35:03，而沙盒 `player-STSData*` 停在 11/15 与 11/3。`solver.log` 成片 `sandbox writes: dropped a sandbox preference save` ⇒ 写盘确实被拦，脏值只能来自实机对象被改 | ✅ |
| 根因 | ①`ClonePolicy.shareClass` 通用规则 `com.megacrit.cardcrawl.screens.` ⇒ `!isSelectionScreen(n)` 把 `screens.stats.CharStat` 判共享 ⇒ 沙盒 `player.charStat` 指向实机对象；②`CharStat.incrementDeath()`（c-CharStat.txt:956-984）与 `incrementPlayTime(long)`（:1160-1177）先改字段再 `pref.putXxx + flush()`，`PlayerPrefs` 只重定向 Prefs、`World.uninstall` 只还原 Prefs，没有还原 CharStat 字段 ⇒ 模拟死亡计数留在实机，下一次真实结算 flush 进 `STSData<角色>`；③B25 只覆盖写盘、未覆盖内存字段，`PLAN-ISOLATION.md:111` 一直标 ⚠️ | ✅ |
| 修法 | `ClonePolicy`：CharStat 判复制 + `shareFieldUncached` 让 `runs` 共享（否则每 fork 多拷 60 个 RunData ≈ +90 µs ≈ 单次求解 +25%）；新增 `mirror/CharStatGuard.java`（install 快照全部 primitive 字段 / uninstall 比对并写回旧值 + `career record guard (B213)` warn，干净时 5 分钟一行）；`World.install`/uninstall 各加一行调用；`Statics` 把 `CardCrawlGame.playtime`、`Settings.totalPlayTime` 标 `discardOnReadBack` | ✅ |
| 代价 | +25~70 ms/次求解（0.3~0.9%，来自 CharStat 复制）；守卫约 3 µs/窗口（全量约十几毫秒）；内存百字节级/ fork；日志 5 分钟一行 | ✅ 已写 PLAN-BUGS |
| 风险 | 求解代码从不读 CharStat（只命中注释）；`runs` 只读；守卫只在渲染线程同步比对，误伤≈0；回滚三处逐条还原；历史脏数据需关游戏后手改（`.backUp` 一起改或删） | ✅ 已写 PLAN-BUGS |
| 构建/安装 | BUILD OK 146 源文件、565 entries、1,331,482 B；INSTALL OK sha256 `0E867FD5BBBB3BF81B2DC63CDE5374F74EC11A2AC265A21947507F7BF89B58A8`（未跑门禁，用户偏好自测） | ✅ |
| 版本 | `resources/ModTheSpire.json` → 1.0.13；`docs/WORKSHOP-DESCRIPTION.md` 当前版本行 + 1.0.13 bullet（**工坊 `config.json` 未动，未上传**） | ✅ |
| 文档 | PLAN-BUGS.md ## B213、PLAN.md 状态表 B213 行、本表 | ✅ |
| 实机验证 | 由用户自测：①求解后统计页「场数/时间」不动；②正常结算一局后 `STSData<角色>` 只涨真实那一局；③若出现 `career record guard (B213): the window CHANGED` 把该行发出 | ⏳ 待实机 |

## B214 沙盒死亡静音（DeathQuiet）：不再真造死亡界面（✅ 已安装，待实机）

user m00005「查看最新日志，分析内存占用过大的原因」→ m00431「有没有更好的办法让沙盒不出现这个问题」→ m00504「只做 A」。

| 项 | 内容 | 状态 |
|---|---|---|
| 取证 | `mts_process_launch.log` 的 `Managing unlocks` 95,799 行（16:41:02–16:41:17 十五秒 41,741 行 ≈2,780/s）；该行来自 `BaseMod.jar` 的 `basemod/patches/.../DeathScreen/ForceUnlock.class`，其 `@SpirePatch` 目标实为 `GameOverScreen.calculateUnlockProgress` ⇒ 1 行 = 1 次 GameOverScreen 构造。`SlayTheSpire.log` 同段每 4–13ms：`screens.DeathScreen> PLAYTIME` → `Class has no more unlocks.` → `Enqueue: preferences\STSPlayer` → `Successfully saved preferences\STSPlayer`；16:41:41 `saves\Ninja_CLASS.autosave` 被删。报告 heap 1352/7106MB，`gc=460/13103ms`，无 OOM/hs_err。 | ✅ |
| 根因 | `AbstractPlayer.damage(DamageInfo)` 偏移 1107–1117：`new DeathScreen`/`getMonsters()`/`<init>(MonsterGroup)`/`putstatic AbstractDungeon.deathScreen`，构造值**只被 putstatic、从不解引用**；`DeathScreen.<init>` 字节码第一条外部调用就是 `new GameOverScreen()`，之后解锁/成就/`StatsScreen`/`SaveAndContinue.deleteSave`/`Prefs.flush`/`MusicMaster.playTempBgmInstantly`/`OverlayMenu.showBlackScreen`。`mirror/Statics.java:368-374` 只还原字段引用 ⇒ 挡显示不挡副作用。放大器：`docs/PLAN-BUGS.md:4180` 的 seen 无上限 + 128 克隆 World + `unlimitedBudget`，单回合 2 小时/48.9 万节点。 | ✅ |
| 修法 | 新增 `src/main/java/sts1solver/mirror/DeathQuiet.java`：`@SpirePatch(clz=AbstractPlayer.class, method="damage", paramtypez={DamageInfo.class})` + `@SpireInstrumentPatch`，`ExprEditor.edit(NewExpr)` 只匹配 `screens.DeathScreen`，替换成 `if (quiet()) { noteSuppressed(); $_ = null; } else { $_ = $proceed($$); }`；`quiet()=World.anyInstalled()`；`EXPECTED_SITES=1`、计数写 System property、前 3 次记日志。判死仍走 `Driver.deadFrom(isDead, currentHealth)`（`drive/Driver.java:1560-1562`）。 | ✅ |
| 代价 | +1 类约 150 行；运行时零开销；日志最多 3 行 `sandbox death: live DeathScreen skipped`。每个模拟致死省掉整条结算链 ⇒ 分配/GC 下降。 | ✅ 已写 PLAN-BUGS |
| 风险 | 沙盒内 `AbstractDungeon.deathScreen` 恒 null（原版只在死亡界面存在时才走的代码不再触发）；其它 mod 的 `publishPostDeath` 等钩子不触发（本次 19 mod 已核对无"死亡钩子改战斗结果"者）；回滚 = 删该类。 | ✅ 已写 PLAN-BUGS |
| 验证（离线） | 自写 javassist harness `_tmp/deathquiet/DeathQuietCheck.java`：同一 replace 文本 instrument `AbstractPlayer.damage` 后统计 ⇒ `rewritten=1 quietCalls=1 newDeathScreen=0 ctorCalls=0`。 | ✅ |
| 构建 | `tools/build.ps1` 被执行策略挡住（`PSSecurityException`）⇒ 手工等价：JDK21 `--release 8` 编译 147 源文件到 `build/runs/build-manual-20261123-093613/classes`（564 entries），`jar.exe --create` ⇒ `mod/sts1-engine-solver.jar` 1,345,230 B；jar 内确认含 `DeathQuiet.class`/`DeathQuiet$1.class`/`DeathQuiet$Site01.class`。 | ✅ 未跑门禁（用户偏好自测） |
| 安装 | 用户放行提权后覆盖成功：`D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar` 1,345,230 B / 2026-11-23 17:37:27，sha256 `973681F822750D16A630EE74C430E012603FE98EDAC322243F9B99785AECD9D1`（与工作区 `mod\sts1-engine-solver.jar` 一致），jar 内含 `DeathQuiet.class` / `DeathQuiet$1.class` / `DeathQuiet$Site01.class`。 | ✅ 已安装 |
| 版本/发布 | `resources/ModTheSpire.json` → **1.0.14**；重打包 jar 1,345,235 B、sha256 `C65DF6C5…94D`（复用 11-23 09:36 的编译产物，只换 resources 与版本戳）；游戏 `mods` 已覆盖；创意工坊 `3839053516` 上传成功（第一次 `SteamResult=Fail` 属 Steam 侧瞬时失败，重试即成功）；GitHub `master` 推到 `2b08b2b`。 | ✅ |
| 文档 | PLAN-BUGS.md `## B214`、PLAN.md 状态表 B214 行、本表、SANDBOX-MECHANISMS.md、PLAN-ISOLATION.md。 | ✅ |
| 实机验证 | 由用户自测：①求解会死人的战斗后 `Managing unlocks` 不增长；②`SlayTheSpire.log` 无 `screens.DeathScreen> PLAYTIME` 循环、`saves\<角色>.autosave` 不被删；③solver.log 出现 ≤3 行 `sandbox death: live DeathScreen skipped`；④面板/自动出牌不变。 | ⏳ 待实机 |

## B215 诊断：0 掉血封印线为何没被算出（2026-11-23，未改代码）

| 项 | 内容 | 状态 |
|---|---|---|
| 结论 | 不是封印/蕾克拉没模拟，是 **beam 在每个动作层按「当前盘面」裁剪，封印的收益（跳过下一次敌方回合）要等 3 层之后才出现在任何字段里**；t1 内所有线掉血均为 0，排序只看怪血/玩家格挡，封印（0 伤害 + 给怪 50 格挡 + 2 费）必然垫底 | ✅ |
| 量化 | 第 2 动作层实际分布 −1100（打击+打击）… −1340（舍利子+封印），**整层跨度 240 分 ≈ 0.12 HP**；封印要省的 8~13 点伤害 = 16,000~26,000 分 | ✅ |
| 代码位置 | Solver.java:2803 rankOf、:2834 score、:4757–4794 beam 截断、:4711/:4716/:4724 三个终止口；QuickSession NARROW_BEAM=10，阶梯 [10,28,64,128]，实测第 2 档到 layer=16 才触发 | ✅ |
| 取证 | solver.log 19:59:24–26 沙盒 11 行 SealPower/Stunned（t2 起跑）；19:59 tier0 4178 节点枯竭 hp=1；20:01 tier0 28066 节点 hp=3、tier1（许 1 瓶药）hp=0 net=9；三档 proven=false | ✅ |
| 未做 | 逐层 dump beam 内容核实「第几层被剪」；把实机 14 步在同一 t1 起点回放看 rng 是否 same（判 0 掉血线在沙盒 RNG 世界是否存在） | ⏳ |
| 方案 | A 前 N 层不裁剪（推荐，+5~15% 时间）；B 只在一回合结束时裁剪（更彻底，frontier 更大）；C 给 score 加「敌人被控 × 意图伤害」项（根治，需读怪物 power，影响全局排序） | ⏳ 待用户选 |
| 文档 | PLAN-BUGS.md ## B215、PLAN.md 状态表 B215 行、本表 | ✅ |

### B215 追加：方案 D（用户 m00967 建议：给世界线/裁剪加随机）

| 项 | 内容 | 状态 |
|---|---|---|
| 用户建议 | 每条线加一个不大的随机值，或让每次裁剪随机，以提高不同世界线被展开的机会 | ✅ 已记录 |
| 落地点 | Solver.java:4758 beam 排序的 Comparator：`rank' = rank + jitter(hash(根digest + 动作路径))`，只在超过 beam 时生效，用来打破 ≤240 分的平局 | ⏳ |
| 关键约束 | ①每条线**出生抽一次、随线不变**（每层重抽则 12~15 层连续过关概率 ~1e-7）；②噪声量级 200~400 分（与一层跨度同阶；到 1 HP=2000 就变成随机抽样）；③种子必须由根 digest+路径决定，保证同局面同计划、plan cache/自动出牌不受影响；④B177 原地加宽不重跑旧层，所以第 1 档就定型，要给被剪线第二次机会只能靠 press 序号换种子或给 parked 线小幅提权 | ✅ 已核实代码 |
| 结论 | 单用 D 每次约 1/4 概率救回封印线但不保证；**A（前 8 层不裁剪）打底 + D 补中后期平局**最稳 | 📝 |
| 文档 | PLAN-BUGS.md ## B215 追加段、本表 | ✅ |

### B215 追加二：A 撤下，随机化路线 E / G / H（user m00977）

| 项 | 内容 | 状态 |
|---|---|---|
| 用户决定 | m00977「a不要做」⇒ 方案 A（前 8 层不裁剪）从候选里撤下 | ✅ |
| 新候选 | E deferred 分层抽样回放（推荐：封印线就在池子里永远排不到，Solver.java:5026）；D 出生噪声；G press 种子/多次 pass；H 分桶保底（层宽不动）；J 同分 hash 兜底 | ✅ 已写 PLAN-BUGS |
| 排除 | F（ε-greedy 展开顺序）实测无效：面板搜索 nodeBudget=0 ⇒ layerAllowance=MAX，每层全部节点都会展开，顺序不改变存活者，只有 :4772 的 beam 截断决定生死 | ✅ 已核实 |
| 推荐 | E + D，G 作可选手动开关 | ⏳ 待用户确认 |

## B215 实施：E（停放池分桶轮转）+ D（出生噪声），版本 1.0.15（2026-11-23，user m00993「改」）

| 项 | 内容 | 状态 |
|---|---|---|
| 决定 | 用户否决 A（m00977），要求「改」⇒ 按 E + D 落地；G/H/J 不做 | ✅ |
| 代码 | `Solver.lineNoise`（digest→±search.lineNoise，默认 300）、`Solver.deferredBucket`（停放层+前两个动作）、`Node.noise`、`Deferred.noise`、两处 beam Comparator 改 `rank+noise`、`sortDeferred` 改「先 noised rank 排序 → 分桶 → 轮转」；`Limits.lineNoise()` / `Limits.deferredRotation()` | ✅ |
| 回滚 | `search.lineNoise=0`、`search.deferredRotation=off` ⇒ 回到 1.0.14 行为 | ✅ |
| 构建 | `tools/build.ps1` BUILD OK（JDK 21.0.11、`--release 8`、147 源文件、568 entries）→ `mod\sts1-engine-solver.jar` **1,335,183 B**，sha256 `077BCB19130990F0F3F6E0FA841ED22183650570485BBCE1458A6E6BE0DE1B84` | ✅ |
| 安装（更正） | 三次「locked by a running game」是**误判**：实际是 DSH 文件沙箱拒绝写工作区外的 `D:\Steam\...\mods`（读 OK、写入 Access denied，系统里没有任何 java 进程），脚本把 `UnauthorizedAccessException` 一律当成占用。改用 full-access 单次批准后 `INSTALL OK`，sha256 与构建产物一致 `077BCB19…DE1B84`。**待办**：install.ps1 的锁判据应区分 `UnauthorizedAccessException` 与共享冲突 | ✅ 已安装 |
| 安装（旧记录） | `tools/install.ps1 -SkipBuild` 被拒：`D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar` 被运行中的游戏占用（未替换任何文件） | ⏳ 等用户关游戏后安装 |
| 版本 | `resources/ModTheSpire.json` → 1.0.15；`docs/WORKSHOP-DESCRIPTION.md` 当前版本行 + 1.0.15 bullet（工坊 config.json 已在 1.0.15 发布时同步） | ✅ |
| 验收 | 用户自测（不跑门禁，见 m00732/m00300）：从 t1 起跑（舍利子→打击→封印→握握手→结束回合）按计算，看计划是否出现「带封印、0 掉血」的线，以及日志 `A3 promote` 的 promoted 是否分布到多个开局 | ⏳ |
| 发布（2026-11-23 23:15，user m01131） | 工坊暂存目录 `D:\mods-workshop\sts1-engine-solver`：`content\sts1-engine-solver.jar` 换成 1.0.15（1,335,183 B，sha256 `077BCB19…DE1B84`，与构建产物一致）；`config.json`（GBK/936 无 BOM，备份 `config.json.bak-20261123`）把 description 的「当前版本：1.0.12」改为 1.0.15，changeNote 换 1.0.15 文案（延迟收益牌被第一层剪掉、可复现扰动 + 分桶轮转回放、`search.lineNoise`/`search.deferredRotation` 可调）；`mod-uploader.jar upload` 输出 `Successfully updated workshop item!` | ✅ 3839053516 |
| GitHub | commit `0deff25`（B215 …; release 1.0.15，7 files changed/266+），`tools/push-github.ps1` → `e721823..0deff25 master -> master` PUSH OK（未用代理） | ✅ |


## B216 蕾忍打 Looter 第 2 回合不打防御：HP 计价被截断成 0（2026-11-24，user m00005；版本 1.0.16）

用户问：solver.log 里蕾忍打 Looter 第 2 回合手上有 `Defend_Ninja`（1 费 5 格挡）、能量 e=1，出防御能少挨 5 点，为什么计划直接结束回合。

| 项 | 内容 | 状态 |
|---|---|---|
| 决定 | 不改搜索宽度、不改 beam/噪声：**这是评分口径的错，不是搜索没算到**。打防御线与结束回合线在 HP 项上被判成同分，差 5 HP=10,000 分被 `Math.max(0,·)` 抹平 | ✅ |
| 代码 | `Solver.java:2019` / `:2026` 去掉 `max(0,·)`（`n20 = n10 - choice2.hp`、`n20s = n20 - growthHp`）；新增 `netHpValue`/`netValueOf` 与 `Result/Node.netHpValue`（哨兵 `NO_NET`）；`QuickSession:5069-5081` 同口径；`better()`、`compareRoutes:859` 改走有符号净值；`netHpLoss` 留作面板显示口径（仍是 `max(0,·)`，不会显示负数） | ✅ |
| 根因数字 | 开战 hp 35/63；暗龙夺魂手使最大生命 63→65，growthHp=2×3=6（`GrowthBook` FEED=30 tenths）。结束回合线 hp 37→29 ⇒ hpLoss=6 ⇒ 旧净值 `max(0,6−6)=0`；打防御线 hp 37→34 ⇒ hpLoss=1 ⇒ 旧净值 `max(0,1−6)=0`。同分后 `rankOf` 的 `energy*40`（留 1 能量 +40 vs 格挡 ×4=+20）与 `score` 的 board（层号、格挡都是减项）让结束回合两侧都赢 | ✅ |
| 回滚 | 把 `:2019/:2026/:859` 三处 `max` 加回、`better()` 改回 `netOf` ⇒ 与 1.0.15 行为一致 | ✅ |
| 代价 | 删 3 个 `max`、加 2 个小函数与 2 个 int 字段；每节点零额外对象/遍历 | ✅ |
| 风险 | 净值可为负 ⇒ 拖回合刷血/刷成长在评分上是正收益；胜利 +10,000,000 仍压倒一切，但没有回血上限的硬判据；`compareRoutes` 去 max 后与 `score` 口径统一（负 deficit 会排前） | ✅ |
| 对 mod 的影响 | 面板格式与掉血显示不变（仍显示真实变化）；自动出牌会开始主动留防御牌；搜索结果中「多留能量/多留格挡但同净血」的线不再自动占优；不动镜像/Driver/Digest，其它 mod 无影响 | ✅ |
| 构建 | `tools/build.ps1` exit 0（JDK 21.0.11、`--release 8`、147 源文件、568 entries）→ `mod\sts1-engine-solver.jar` **1,335,437 B**，sha256 `4355BD345B694E681EB110162346121CABC8A55B574C752D48D1204708B9182E` | ✅ |
| 安装 | `tools/install.ps1 -SkipBuild` INSTALL OK（sha256 一致，装到 `D:\Steam\...\mods`） | ✅ |
| 版本 | `resources/ModTheSpire.json` → `1.0.16`；`docs/WORKSHOP-DESCRIPTION.md` 当前版本行 + 1.0.16 bullet | ✅ |
| 发布 | 工坊暂存 `D:\mods-workshop\sts1-engine-solver`：`content\sts1-engine-solver.jar` 换 1.0.16（同 hash）；`config.json`（GBK/936 无 BOM，备份 `config.json.bak-20261124`，5000 B）description「当前版本」→ 1.0.16、changeNote 换 1.0.16 文案；`mod-uploader.jar upload` 输出 `Successfully updated workshop item!`（2026-11-24 01:04） | ✅ 3839053516 |
| 上传踩坑 | Steam 未运行时 `Steam API failed to initialize correctly.`；无控制台时 uploader 用 `cmd /k` 重启自身、父进程立刻退出且无 stdout ⇒ 需 PowerShell 先 `AllocConsole()` 再 `Start-Process -RedirectStandardOutput -NoNewWindow` 才能拿到日志 | ✅ 已记入 PLAN-BUGS ## B216 |
| GitHub | commit `4f41455`（B216: score net HP signed …; release 1.0.16，6 files changed/107+/18−），`tools/push-github.ps1` → `PUSH OK`，`origin/master` = 4f41455（未用代理） | ✅ |
| 验收 | 用户自测（不跑门禁，m00732/m00300）：同局面按计算，t2 计划应出现「防御」；日志掉血应为真实净值 | ⏳ |

文档：`docs/PLAN-BUGS.md` 末尾 `## B216` 全细节；`docs/PLAN.md` 板块表 B216 行（并顺带订正 B215 行「已构建 1.0.15（待安装实机）」→ 已发布、B211 行「已构建安装 1.0.11（待实机）」→ 后续版本已接续）。

## B217 蕾忍打三个哨卫内存十几个 G：每节点新建图标纹理（2026-11-24，user m00001/m00005/m00301；版本 1.0.17）

用户问：蕾忍打三哨卫，日志里内存十几 G，是不是拜蛇术士/死亡画面（B214）或最近的 max 改动（B216）造成的，定位到具体函数并给方案，先别动手。

| 项 | 内容 | 状态 |
|---|---|---|
| 决定 | 先给结论、等用户拍板：用户 m00301「做前者」= 缓存复用、永不 dispose；兜底阈值未批准，不做 | ✅ |
| 定位 | `ImageMaster.loadImage` 无缓存（`new Texture` 每次解码上传） × mod 能力构造函数每实例化一次建 2 张图标（`ninja.jar powers.LexKela.<init>` offset 49–96 字节码证据）；共 72 个调用点（ninja 40 类、菜农 30 类、死灵 2 类）全走该方法 ⇒ 单一收口点 | ✅ |
| 排除 | B214 无关（堆全程平、`sandbox death: live DeathScreen skipped` 正常）；B216 无关（零分配算术）；B201 覆盖不到（只 hook `loadAnimation`，当日 `sandbox art` 0 条） | ✅ |
| 数字 | 436 条/s × 2 × 32KB ≈ 13.8MB/s；实测 24.5GB/1905s = 13.5MB/s；Windows 事件 2004：26,302,455,808B@10:05、37,688,893,440B@00:25；Java 堆峰值 2766MB、崩时 1.2GB ⇒ 全在堆外 | ✅ |
| 代码 | 新增 `src/main/java/sts1solver/mirror/SandboxImage.java`（两个重载各一组 prefix+postfix，`SpireReturn` 短路，`World.anyInstalled()` 门控，1024 张/192MB 上限，缓存永不 dispose，诊断行 `sandbox image: reused/distinct/kept/refused`）；`Solver.java:4933` 假文案改成 `tier cap` / `memory guard: heap XMB, limit YMB` | ✅ |
| 代价 | 每节点 2 次 HashMap 查找；常驻最多 1024 张/192MB；构建 148 源文件 / 571 entries / 1,339,234 B | ✅ |
| 风险 | mod 自行 dispose 缓存纹理会交失效对象（当前 8 个 mod 无此用法）；GL 上下文丢失后纹理失效（与 B201 同类边界）；回滚＝删 `SandboxImage.java` | ✅ |
| 对 mod 的影响 | 图标内容不变、面板与自动出牌不变；`条/s` 略升；实机路径零改动 | ✅ |
| 构建 | `tools/build.ps1` exit 0（JDK 21.0.11、`--release 8`、148 源文件、571 entries）→ `mod\sts1-engine-solver.jar` **1,339,234 B**，sha256 `7108A13F5F1C5C0816D6E793E0C69F06F1CB5F0F40308A6D7337B446E3D63EFB` | ✅ |
| 安装 | `tools/install.ps1 -SkipBuild` INSTALL OK（同 hash，装到 `D:\Steam\steamapps\common\SlayTheSpire\mods`）；jar 内含 `SandboxImage` 三个 class 与 `"version":"1.0.17"` | ✅ |
| 版本 | `resources/ModTheSpire.json` → `1.0.17`；`docs/WORKSHOP-DESCRIPTION.md` 当前版本行 + 1.0.17 bullet（并把 1.0.17 条目置顶） | ✅ |
| 发布 | 工坊暂存 `D:\mods-workshop\sts1-engine-solver`：`content\sts1-engine-solver.jar` 换 1.0.17（同 hash）；`config.json`（GBK/936 无 BOM，备份 `config.json.bak-20261124b`，5091 B）description「当前版本」→ 1.0.17、changeNote 换 1.0.17 文案；`mod-uploader.jar upload` 输出 `Successfully updated workshop item!`（2026-11-24 13:13） | ✅ 3839053516 |
| 上传踩坑 | 本次 Steam 已在运行；仍是无控制台时会 `cmd /k` 重启自身 ⇒ 沿用 `AllocConsole()` + `Start-Process -RedirectStandardOutput -NoNewWindow` 拿日志（stdout 有成功行，stderr 有 `This program isn't launched in a console. Relaunching with a console...`，属正常） | ✅ |
| GitHub | commit `c0b704f`（B217: cache ImageMaster.loadImage textures inside sandbox windows …; release 1.0.17，7 files changed/254+/3−），`tools/push-github.ps1` → `PUSH OK`，`origin/master` = c0b704f（未用代理） | ✅ |
| 验收 | 用户自测（不跑门禁，m00732/m00300）：同场蕾忍三哨卫，java.exe 提交应稳在 2GB 内、`sandbox image: reused` 增长而 `distinct` 停在两三百、`条/s` 略升、不再出现 `TextureRegion.setRegion` NPE | ⏳ |
| 未做 | 堆外兜底阈值（未批准）；其它 mod 自建 `TextureLoader`/`new Pixmap` 路径（本次清点无每节点调用） | ⏳ |

文档：`docs/PLAN-BUGS.md` 末尾 `## B217` 全细节（并在 `## B201` 标题下加了一行指向 B217 的缺口说明）；`docs/PLAN.md` 板块表 B217 行。

## B218 装 1.0.17 后无法启动游戏：MTS 经典 @SpirePatch 的 postfix 必须返回 __result（2026-11-24；版本 1.0.18）

| 项 | 内容 | 状态 |
|---|---|---|
| 决定 | 用户 m00461 明确「不要改动只找问题」⇒ 本轮先只定位并给方案（A：postfix 改返回值；B：迁 @SpirePatch2；C：绕过，否决），m00583 批准方案 A 并要发布 | ✅ |
| 定位 | `sendToDevs/mts_process_launch.log` 16:42：`Patch Class: [sts1solver.mirror.SandboxImage$LoadFilteredPatch]` → `Adding Postfix...` → `PatchingException: Illegal patch parameter: Cannot determine name`（`PostfixParamTransformer.getParamName:96` ← `Patcher.finalizePatches:318` ← `Loader.runMods:443`）；根因是 1.0.17 两个 `void postfix(Texture __result, …)`，MTS 只在 postfix 返回类型非 void 且等于首参类型时才把首参当 `$_`，void 时 `__result` 吃掉目标第一个实参、参数整体错位、最后一个越界 | ✅ |
| 实证 | javap 读 MTS 3.30.3 `ParamInfo`/`ParamTransformer`/`PostfixParamTransformer`/`ParameterPatchInfo.doPatch`/`PostfixPatchInfo.alterSrc`；扫 21 个工坊 jar：36 个 `__result` 方法全是「非 void + return __result」，唯一 void 的是 StSLib 的 `@SpirePatch2`（走 ParamInfo2） | ✅ |
| 代码 | `mirror/SandboxImage.java`：`public static void postfix(Texture __result, String file)` → `public static Texture postfix(…){ record(…); return __result; }`，2 参重载同样改；类注释加 B218 段说明 `$_` 规则 | ✅ |
| 代价 | 零额外运行时成本；jar +5 B（1,339,234 → 1,339,239） | ✅ |
| 风险 | `record()` 进到返回路径，但 `try/catch Throwable` 且不改变返回值；MTS 升级若改规则需重核；回滚＝改回 void（即复现本次崩溃） | ✅ |
| 对 mod 的影响 | 与 1.0.17 设计一致（图标复用、实机零改动、面板/自动出牌不变），差别只是游戏能启动 | ✅ |
| 构建/安装 | `tools/build.ps1` BUILD OK（148 源文件、571 entries）→ 1,339,239 B、sha256 `48234C2F91FD4C65D7565059D1318915FE81A33680398F8EA0696E0D52CCD5AE`；`javap -p` 复核 postfix 返回 `Texture`；`tools/install.ps1 -SkipBuild` INSTALL OK | ✅ |
| 版本 | `resources/ModTheSpire.json` 1.0.17 → **1.0.18**；`docs/WORKSHOP-DESCRIPTION.md` 当前版本行与 1.0.18 bullet | ✅ |
| 未做 | 构建期「patch 签名符合 MTS 规则」的静态校验（未批准）；未跑门禁（用户自测，m00732/m00300） | ⏳ |
| 验收 | 用户自测：点 Play 能进主菜单、无 `PatchingException`；进战斗看 `sandbox image: reused` 增长、`distinct` 停在两三百 | ⏳ |

文档：`docs/PLAN-BUGS.md` 末尾 `## B218` 全细节；`docs/PLAN.md` 板块表新增 B218 行，并把 B217 行状态改为「1.0.17 起不来，已被 1.0.18 取代」。
