# PLAN-MOD-OBJECTS — 静态持有对象隔离计划（B192 及同类；无 mod 类名特判）

> 状态：**计划阶段，未改代码**。本文只回答「怎么修、先修哪、怎么算修好」。
> 用户口径（m00005）：「sts1-engine-solve 最新日志亡灵契约师计算时沙盒没有正确隔离导致计算后奥斯提血量增加，给我一个计划修复这个 bug 以及类似 bug。」
> 用户否决（m00273）：「不是的，不要特判因为奥斯提不是原版角色，有没有不特判的方案」——因此**不写任何 mod 类名表**，判定全部由「引擎基类型 + 世界图可达性」推出。

## 0. 一句话结论

奥斯提（TheNecrobinderMod 的 Osty）是**只由静态字段持有的活体**。镜像按设计只复制标量、不复制静态对象引用，这类引用只能靠 `StaticWatch` 事后比对。**2026-11-17 四轮实测后的最终结论**（`javap` 实证）：`Osty extends AbstractCreature`，既不是 `AbstractMonster`，也不属于第一/二轮判据列出的六个引擎基类 ⇒ 该字段从未被注册成克隆根，模拟里的 `HealOstyAction.update() → OstyManager.get().restore(amount)` 直接写在实机 Osty 上，卸载只还原引用，**血量增量留在实机**；同时 `StaticWatch` 收集实例字段时按层级截断在 96 个，`Osty`(~30)+`AbstractCreature`(70+) 使 `currentHealth/maxHealth` 可能不进候选，事后回写也覆盖不到；`Digest` 不记录它，前后比对同样发现不了。（§1.2 里「第 28/29 位、32 格就停」是第一轮的初步推导，已被第四轮修正，保留作过程记录。）

修法分三层，缺一层就会复发，且**三层都不需要知道 mod 类名**：

1. **让模拟真正拥有它**：每次抓取前做一次「静态持有者盘点」——把「是战斗实体、又从世界图里不可达」的静态值动态注册成 `Kind.CLONE` 根（复用已有的 `Statics.registerDynamic`）。实体的判据是 `AbstractCreature`（覆盖 `AbstractMonster`/`AbstractPlayer` 与一切 mod 宠物，**这是第四轮补上的关键一条**）及其它五个引擎基类（`Power/Card/Relic/Potion/Orb`），再加一条不依赖继承名的兜底 `hasHealthState`（同时声明非 static `int currentHealth` 与 `int maxHealth`），配合世界图身份集合，任何 mod 的同类字段自动命中。
2. **让快照看得见**：`StaticWatch` 用 `ClassValue<Field[]>` **全量收集整条继承链**（不再按层级截断），再按**字段名**挑 32 个状态字段、其余补齐；战斗基类静态值每窗口采样。即使某字段没被克隆，写入也会被点名并回写。另由 `LiveGuard` 在窗口内直接比对实机 player/怪/已注册根/**未接管的 `AbstractCreature` 静态值**，变了立刻点名。
3. **让泄漏有功能判据**：`Digest` 对**每个动态克隆根**追加一行 `sc=<类简称>#<状态指纹>`，接进搜索前后比对与 `PlanCache` 键。

## 1. 故障与证据

### 1.1 现场

- 求解器日志 `C:\Users\YOKI\sts1-engine-solver\solver.log`（本次 161 行）：只有一次计算（12:43:18–12:43:47），第 76/80/82/86 行是 settle 序列，玩家 `P hp=56/62`、`pw=TheNecrobinderPlus:OstyPower-1`——**奥斯提确实在场**。
- 同一份日志 **没有一行记录 Osty 的 currentHealth/maxHealth**：求解器目前根本不看它（这就是盲区本身，不是日志开关问题）。
- `solver.log:23`（镜像自检）原文含：`NOT mirrored: … 300 static object ref(s) (… object refs: 10 art, 290 other)`，other-objects 里明确有 `TheNecrobinderMod.OstyManager#osty : Osty`。
- 全文没有出现 `static watch: the sandbox wrote mod static …`：`StaticWatch` 从未把这个写入判出来。

### 1.2 为什么 StaticWatch 没判出来（代码级）

- `src\main\java\sts1solver\mirror\StaticWatch.java:122`：采样窗口 = 前 4 次安装 + 每 128 次一次；未标 dirty 的字段在非采样窗口直接跳过。
- `StaticWatch.java:237-268`：值不是集合/Map/数组时走 `isPlainObject`，逐层收集 declared fields：
  `while (k != null && k != Object.class && fs.size() < 32)`（:240）。
- `Osty` 自己声明 7 个实例字段（`scale,targetScale,fade,tint,deathTimer,deathDuration,lastAttackFrame`），然后才轮到父类 `AbstractCreature`：`name,id,powers,isPlayer,isBloodied,drawX,drawY,dialogX,dialogY,hb,gold,displayGold,isDying,isDead,halfDead,flipHorizontal,flipVertical,escapeTimer,isEscaping,tips,healthHb,healthHideTimer,lastDamageTaken,hb_x,hb_y,hb_w,hb_h,currentHealth,maxHealth,…`。
  32 格在 `hb_w` 附近用尽 ⇒ **`currentHealth`/`maxHealth` 永远不在快照里**；`isDying`/`isDead` 反而在（所以「沙盒把奥斯提弄死」这一半会被回写，只有血量这一半静默泄漏）。
- **第四轮修正**：上面这段截断分析只解释「为什么 put back 覆盖不到」，不是漏网的主因——主因是实体判据不含 `AbstractCreature`（§3.4），只补快照并不能让 Osty 被 clone。第四轮已把收集改为 `ClassValue<Field[]>` 全量整条继承链、只在选择阶段限 32 格。
- `StaticWatch.java:395-397`：回写时跳过 final 字段（`Osty.tint` 是 final），血量字段非 final，回写路径本身没问题——问题是**没进入比对**。

### 1.3 为什么 Digest 也没判出来

`src\main\java\sts1solver\mirror\Digest.java:34-80` 只覆盖：回合/相位/队列、玩家 hp/block/energy/stance/powers/relics/orbs、四个牌堆、每只怪的血/甲/意图。Osty 不在 player/monsters 对象图里，**digest 前后完全相同**，`Gameplay.java:428-435` 的「搜索期间局面变化」闸门自然放行。

### 1.4 为什么只靠回写不够，必须克隆

- 回写发生在 `uninstall`，而搜索期间会有**多个窗口**和**同一次搜索内的兄弟分叉**：`World.fork()`（`World.java:112-158`）复制的是 `cells[]`，静态引用字段只有 `Kind.CLONE` 才会被 per-fork 复制；`VALUE` 语义下所有分叉共享同一个实机 Osty。
- 结果：一次「奥斯提回血」会改变同一轮搜索里其它分支的起点（模拟不忠实），而不只是污染实机。
- 另一方面，`Kind.CLONE` 的隔离是完整的：`World.install()`（`World.java:249`）把静态字段换成 `cells[i]`，`uninstall()`（`World.java:411-413`）把安装时记下的 `live[i]` 原引用写回。所以**写只落在克隆上，实机对象自始至终没被碰过**；不需要额外回写。

### 1.5 通用判据的可行性（不特判的证据）

- `javap -p`：`Osty extends AbstractCreature`，`OstyManager` 只有 `private static Osty osty` + `get()/isAlive()/clear()/summon(int)`。
- 扫描 `TheNecrobinderPlus.jar` 全部 class 常量池中的 `LTheNecrobinderMod/character/Osty;`，命中 12 个类；逐个 `javap -p` 后**没有任何类声明 Osty 类型的字段**（只有构造器/方法参数、局部变量，或 `OstyManager.get()` 调用）⇒ 换静态引用后不存在「一半指向实机」。
- 关键点：`Osty extends AbstractCreature` 这一条就足以被下面的通用判据命中，**不需要知道它叫 Osty、也不需要知道它属于哪个 mod**。同类对象（任何 mod 的宠物流/分身/召唤物/只由静态单例持有的怪物）共享同一判据。
- `OstyManager.osty` 非 final，`install()` 可替换、`uninstall()` 可还原；为 null 时克隆结果也是 null，不需要特判。

## 2. 目标与验收判据

| # | 判据 | 怎么验 |
| --- | --- | --- |
| G1 | 一次计算前后，实机 Osty 的 `currentHealth/maxHealth/isDying/isDead` 四位不变 | 日志新增搜索前/后 `sc=Osty#…` 摘要行（同一行给 before/after）；用户实机跑一次亡灵契约师 |
| G2 | 沙盒里让 Osty 掉血/回血/死亡，路线与评分随之变化（不是恒定值） | 同一局面 A/B：修复前 vs 修复后，计划里的 Osty 血量与掉血估值应不同 |
| G3 | 连续多次「计算 / 下一步 / 再计算」不累积回血；第 N 次结果不受第 N-1 次计算影响 | 用户实机连点 3 次，G1 的 before/after 每次都相等 |
| G4 | 任何动态克隆根的状态变化都会让计划缓存失效（不再复用旧计划） | `PlanCache` 键含 G1 的 `sc=` 片段；日志 `plan cache: key=…` 前后不同 |
| G5 | **任何**静态对象引用被沙盒写入内部状态，最迟在 `uninstall` 时被点名并回写；无「静默」类别 | `StaticWatch` 日志出现 `owner#field`；任意 mod 的同类对象都算 |
| G6 | 盘点结果可解释：每个战斗基类静态值要么「动态克隆」，要么「世界图已可达」，要么「非战斗基类」，没有第四类 | 日志一行分类计数 + 明细（前 20 条），无「未分类」 |

## 3. 任务表

> 估时按 0.25 天粒度；**不新增 Probe 门禁**（用户 m00732/m00300 自测口径）。T1 的现场证据已可直接用 §1。

| 编号 | 任务 | 文件 | 验收 | 估时 |
| --- | --- | --- | --- | --- |
| **T1** | 现场证据固化：日志行号、Osty 字段序、32 格推演、Digest 覆盖清单写进 `evidence/B192-OSTY-STATIC-20261117.txt` | `evidence/` | 只看证据文件能复现「为什么没判出来」 | 0.25d |
| **T2** | 新增 `mirror/StaticEntities.java`：静态持有者盘点 + 世界图身份集合 + `registerDynamic(f, CLONE)`（见 §4.1），`World.captureLive()` 开头调用 `sync()` | `mirror/StaticEntities.java`（新）、`mirror/World.java:46` | 日志 `mirror: static entity roots …`；计算前后 Osty 血量不变 | 1d |
| **T3** | `copyContainer` 深拷「战斗基类元素」（共享主 Cloner，见 §4.2），消除静态容器元素被实机共享的同类泄漏 | `mirror/World.java:61,135,673` | 容器里的卡/怪被沙盒改写后实机不变；`cloneCostReport()` 可归因 | 0.5d |
| **T4** | `StaticWatch` 快照改「按名字的状态字段优先 + 其余字段补齐」（候选收 96 个、挑 32 个；§4.3） | `mirror/StaticWatch.java`（snapshot 4 分支） | 日志 `static watch: body=… fields=…` 显示 Osty 快照含 `currentHealth` | 0.5d |
| **T5** | 战斗基类静态值每窗口采样（不受 `SAMPLE_EVERY` 限制，只读白名单字段） | `mirror/StaticWatch.java` | 人为在沙盒里改 Osty 血，下一次 `uninstall` 立刻点名 | 0.25d |
| **T6** | `Digest` 追加 `sc=` 通用片段（每个动态克隆根一个；§4.4），接进前后比对与 `PlanCache` 键 | `mirror/Digest.java`、`search/PlanCache.java` | G1/G4 | 0.5d |
| **T7** | 盘点日志与排除开关：`mirrorEntityRoots=auto|off`、`mirrorEntityIgnore=<owner#field>,…`（默认空），只用于「某个 mod 的误判」而非特判实现 | `settings` 读取处、`StaticEntities` | 关掉后行为回到现状；忽略某字段后日志说明跳过原因 | 0.25d |
| **T8** | 多持有者审计：注册前数「静态字段值 == 该对象」的持有者；>1 且其中含 final 字段时告警（克隆会分裂身份），记已知边界 | `StaticEntities` | 日志给出 `holders=N`，final 持有者单列 | 0.25d |
| **T9** | 用户实机验收（G1–G4），日志片段存 `evidence/` | — | 用户确认 | 0.5d |
| **T10** | 文档同步：`PLAN.md` 在跟条目、`PLAN-WORKLOG.md`、`PLAN-BUGS.md` B192、`SANDBOX-MECHANISMS.md`、`PLAN-ISOLATION.md` 交叉链接 | 见 §7 | 每份文件都能从索引走到本文 | 0.25d |

### 3.1 实施状态（2026-11-17，用户 m00356「改」）

| 任务 | 状态 | 落点 / 说明 |
| --- | --- | --- |
| T1 | ⬜ 未做 | `evidence/` 目录不存在；证据仍在本文 §1 与 PLAN-BUGS B192 |
| T2 | ✅ 已实现 | 新 `mirror/StaticEntities.java`（约 23.8 KB）；`World.captureLive()` 首行 `sync()` |
| T3 | ✅ 已实现 | `World.copyContainer(Cloner,Object)` 重载，capture/fork 的 CONTAINER 改用它；`readBack` 仍浅拷 |
| T4 | ✅ 已实现 | 候选 96 → 按 `STATE_NAMES` 挑 32 → 余下补满（`MAX_FIELDS` 仍 32，见下偏差） |
| T5 | ✅ 已实现 | `Watch.entity` + 采样条件 `|| !w.entity` |
| T6 | ✅ 已实现 | `Digest.ofCurrent()` 末尾 `StaticEntities.fingerprintLine()`；`PlanCache` 经 `Digest.stable` 自动进键，无需改 |
| T7 | ✅ 已实现 | `mirrorEntityRoots`（默认 auto）/ `mirrorEntityIgnore`（默认空），缓存 2s |
| T8 | ⚠️ 部分 | 只有 final 持有者告警（`skippedFinal`）；没有「同一对象几个持有者」的 `holders=N` 统计 |
| T9 | ⬜ 待用户实测 | 未跑 `tools/run.ps1`（m00732）；install 被运行中的游戏挡住 |
| T10 | ✅ 已做 | 见 §7，另加本文 §3.1/§10 与 PLAN-BUGS B192 实施记录 |

偏差与代价实测：快照上限仍是 32（计划写 128），理由是 128 会让每个普通静态对象的快照成本翻倍，先按 32+名字优先观察是否还漏字段。构建产物 `mod/sts1-engine-solver.jar` 1,307,167 B，SHA-256 `606FF30B…14AA5`，已 INSTALL OK 到 `D:\Steam\steamapps\common\SlayTheSpire\mods`。

### 3.2 第二轮修正（2026-11-17 13:20，用户 m00567「仍改实机数据」＋「局面已变化不给计划」）

| 项 | 状态 | 落点 / 说明 |
| --- | --- | --- |
| T2 修订：按**声明类型**注册 | ✅ | `walk` 用 `isBattleType(f.getType())`，`v == null` 也注册成 `Kind.CLONE`。修的是 `OstyManager#osty` 抓取时为 null 被跳过、召唤发生在 live-shared 字段里的泄漏 |
| T2 附加：注册可见 | ✅ | 日志新增 `mirror entities: newly owned [owner#field (类型/declared X, currently null), …]`（≤8 条/次） |
| T2 修订：final 告警 null 安全 | ✅ | 文案改 `declared X, currently null`，不再 `v.getClass()` 空指针 |
| T6 修订：digest 与注册同侧 | ✅ | `Digest.ofCurrent()` 首行 `StaticEntities.sync()`，抓取前后两次 digest 看到同一组根，消除 `LIVE STATE CHANGED` 假警报（窗口内 `sync()` 因 `World.anyInstalled()` 立即返回） |
| T9 | ⬜ 仍待用户实测 | 看：无 `static watch … put back`、有 `newly owned [… OstyManager#osty (declared …, currently null)]`、`sc=` 稳定、能出计划 |

产物：`mod/sts1-engine-solver.jar` 1,307,526 B，SHA-256 `53ABDBE5989D0AB50987350AAA95DD08CBD9D37D17F7F1663A21FB9DEEC90B70`，INSTALL OK。
已知边界：`DreamCatcher`/`NSoul`/`Necronomicon` 这类静态模板卡/遗物也会被当根克隆（首轮 6 个根里 4 个是它们）；若 mod 用 `==` 比较模板身份，用 `mirrorEntityIgnore` 排掉对应字段。

### 3.3 第三轮：给「实机是否被改」加度量（2026-11-17 13:35，用户 m00661）

| 项 | 状态 | 落点 / 说明 |
| --- | --- | --- |
| LiveGuard | ✅ | 新 `mirror/LiveGuard.java`；`World.install()` 在换静态之前 `capture()`，`World.uninstall()` 第一步 `verify()`（早于 `StaticWatch.beforeUninstall()` 的还原，否则会被自己的修复掩盖） |
| 判据 | ✅ | 变化 → `WARN live guard (B192): the window CHANGED N live object(s): <label> hp=34/40 b=0 -> hp=40/40 b=0`；无变化 → 每 60s `live guard (B192): N clean window(s)` |
| 为何不顺手修 | — | 用户口径（m04606）：修 bug 要修根因，不能用「检测到就改回去」掩盖；该行是定位残余写入的判据 |
| 产物 | ✅ | sha256 `EF3C8D82…07F5`，INSTALL OK |
| 待办 | ⬜ | 用户重启游戏实测；依据 `live guard` 行判断第二轮是否已修好、或残余写入走哪条路径 |

### 3.4 第四轮：奥斯提不是原版怪，`AbstractCreature` 才是判据（2026-11-17 13:40，用户 m00694「计算前 1，计算后六十几」）

| 项 | 内容 |
| --- | --- |
| 现象 | 第三轮 jar 算出 8 个 newly owned 根，仍报 `static watch: … OstyManager#osty … put back`；实机奥斯提 1 → 60+ |
| 反编译证据 | `javap -p`：`public class TheNecrobinderMod.character.Osty extends com.megacrit.cardcrawl.core.AbstractCreature`；`OstyManager` 唯一字段 `private static Osty osty`（非 final）；`HealOstyAction.update()` = `OstyManager.isAlive() ? OstyManager.get().restore(amount) : …` |
| 根因 1 | `isBattleEntity/isBattleType` 只列 `AbstractMonster/AbstractPlayer/AbstractPower/AbstractCard/AbstractRelic/AbstractPotion/AbstractOrb`；宠物直接继承 `AbstractCreature`，六个基类一个不中 ⇒ 字段从未注册，始终 live-shared |
| 根因 2 | `StaticWatch.snapshot` 收集实例字段时按层级 `all.size() < 96` 截断，`Osty`(~30)+`AbstractCreature`(70+) 使后段字段（含 `currentHealth/maxHealth`）根本不进候选，事后 put back 覆盖不到 |
| 修法 | ①`StaticEntities`：`isBattleEntity` 加 `instanceof AbstractCreature`，`isBattleType` 加 `AbstractCreature.class.isAssignableFrom(c)`；另加 `hasHealthState(Class)`（非 static `int currentHealth`+`int maxHealth`，`ClassValue` 缓存）作为不依赖继承名的兜底；②`StaticWatch`：`ClassValue<Field[]> INSTANCE_FIELDS` 全量收集整条继承链（缓存），快照改为「全量收集 → 按 `STATE_NAMES` 挑 32 → 其余补齐」，收集不截断、选择仍限 32；③`LiveGuard`：把 `ModStatics.watchCandidates()` 中值为 `AbstractCreature` 的静态字段也纳入比对（label `unowned static owner#field`，身份去重） |
| 代价 | 每次抓取/分叉多克隆一个宠物（spine 的 `Skeleton/AnimationState` 走 `ClonePolicy` 共享，不深拷纹理）；`hasHealthState` 每类型一次反射扫描；`INSTANCE_FIELDS` 每类型首次收集一次；`LiveGuard` 每 install 多约 395 次静态读（微秒级） |
| 风险 | 宠物克隆后 mod 中 `==` 比较会看到沙盒对象（隔离的正确语义）；`hasHealthState` 兜底可能把同名血量字段的非战斗对象当作实体克隆（多一次克隆，绝不写实机）；`LiveGuard` 只覆盖窗口内变化 |
| 回退 | `mirrorEntityIgnore=TheNecrobinderMod.OstyManager#osty`（沿用第一轮开关），或把 `isBattleEntity` 的 `AbstractCreature` 分支与 `hasHealthState` 兜底删掉 |
| 产物 | jar 1,313,224 B / sha256 `EAAEA9C8CF7EB83B9798BE7DD77F4C1EA4878755F7CE5993CEB46B798EFACD0F`（143 源文件，561 entries，BUILD OK + INSTALL OK） |
| 状态 | ⏳ 待用户重启游戏实测（验收见 PLAN-BUGS B192 第四轮「验证」） |

### 3.5 第五轮：spine 不再共享（2026-11-17 13:33，用户 m00827「动画没有隔离，其他都做好了」）

| 项 | 内容 |
| --- | --- |
| 现象 | `solver.log` 13:32:28.004 `WARN quick search: SPINE STATE CHANGED by the search (2)`；两条差异只有 `t=` 变，`anim/ts/skel/flip/tint` 全同，`t` 增量≈搜索墙钟 ⇒ 本次是搜索级审计误报，但结构上确实没隔离 |
| 根因 | `mirror/ClonePolicy.java:104-106` 把 `org.lwjgl.`/`com.esotericsoftware.spine.` 整包按引用共享 ⇒ 克隆生物的 `skeleton`/`state` 与实机同一对象；`javap -p -c TheNecrobinderMod.character.Osty` 有 `state.update/apply`、`skeleton.setToSetupPose/setPosition/setColor/setFlip/updateWorldTransform`、`stateData.setMix` ⇒ 模拟动画一调用就写实机 |
| 修法 | 新增 `mirror/SpineIsolation.java`（`isolate(AbstractCreature)`：`skeleton`→`new Skeleton(live)` 拷贝构造；`state`→`new AnimationState(live.getData())` + 逐 track `setAnimation(i, e.getAnimation(), e.getLoop())`/`setTime`/`setTimeScale`；`stateData` 共享）；`Cloner.walk()` 两处 `return copy` 前按 `instanceof AbstractCreature` 调用（零 mod 名）；`SkeletonFingerprint` 加 `spineSignature`（anim/ts/skel/flip/pose/tint，不含时钟）+ `poseHash(Bone 位姿 FNV-1a)` + `clockOnly(diff)`；`LiveGuard` 生物签名追加 `spineSignature`；`Gameplay.noteSkeletonDiff` 剔除 clock-only 行（全剔除记 INFO，其余仍 WARN）；`Statics.isDynamicClone(Field)` + `StaticWatch` 三处跳过动态克隆根（消除 Osty 假回写）；settings `mirrorSpine`（默认 `copy`，`share/off/0/关` 回退） |
| 代价 | 每 fork 每生物多一次 `new Skeleton`+`new AnimationState`+每 track `setAnimation`；按 1852 fork、player+2~3 怪估：克隆段（上次 `clone=644ms`）+3%~8%，整搜索（5.6s）+0.5%~1%。`poseHash` 只在 capture/verify 算 |
| 风险 | 克隆体动画在抓取瞬间定格（隔离正确语义，但依赖动画时间的 mod 逻辑会看到恒定 `t`）；mod 若把 spine 对象另存到自己的静态字段仍会共享（窗口签名不含时钟，只推进时钟的写不会点名）⇒ 记已知边界；新 `AnimationState` 不重放旧 state 上的 listener；依赖游戏自带 `Skeleton(Skeleton)` 拷贝构造（已 javap 实证） |
| 回退 | `settings.txt` 写 `mirrorSpine=share`（无需重编译）；或删 `Cloner` 两处 `SpineIsolation.isolate` 调用 |
| 产物 | BUILD OK（144 源文件，562 entries，1,318,133 B）+ INSTALL OK，jar 1,318,132 B / sha256 `FAD91E8DAEA532741A4C64CD8686ACF6D66B197FACF3881BD97BE108C15D9B0E` |
| 状态 | ⏳ 待用户重启游戏实测（验收见 PLAN-BUGS B192 第五轮「验证」） |
## 4. 方案细节

### 4.1 静态持有者盘点（T2，核心）

```java
// mirror/StaticEntities.java（新）
public static void sync() {
    if (World.anyInstalled()) return;                  // 只在没有窗口时改注册表（否则会读到沙盒值）
    Map<Field, Object> now = readCandidates();         // 非 final 静态对象引用，~300 次 Field.get
    if (!changed(now)) return;                         // 值的 null 态/类名没变 ⇒ 0 注册开销
    Set<Object> owned = reachableFromLiveWorld();      // 身份集合：深度 ≤8、对象 ≤20000
    for (Map.Entry<Field, Object> e : now.entrySet()) {
        Object v = e.getValue();
        if (v == null || !isBattleEntity(v)) continue; // 六个引擎基类，见下
        if (owned.contains(v)) continue;               // 已随某个 CLONE 根（玩家/怪/牌堆/动作）一起克隆
        if (ignored(e.getKey())) continue;             // settings: mirrorEntityIgnore
        Statics.registerDynamic(e.getKey(), Statics.Kind.CLONE);
    }
}
private static boolean isBattleEntity(Object v) {
    return v instanceof AbstractCreature   // 玩家/怪物/宠物/召唤物都在内
        || v instanceof AbstractPower
        || v instanceof AbstractCard
        || v instanceof AbstractRelic
        || v instanceof AbstractPotion
        || v instanceof AbstractOrb;
}
```

要点：

- **零 mod 类名**：命中条件是「引擎基类实例 + 世界图不可达」，奥斯提因为 `extends AbstractCreature` 自动入选；换成任何 mod 的同类对象都一样。
- `reachableFromLiveWorld()` 的起点：`AbstractDungeon.player`、`getMonsters()`、玩家四个牌堆、`relics`、`potions`、`orbs`、每只怪的 `powers`、`actionManager.actions`（战斗中）。遍历跳过 `ClonePolicy.share` 的类、不读静态字段、有界。
- 可达即视为「已被世界克隆拥有」：玩家克隆时会把整张图一起带走，静态字段无需再克隆（若注册也不会错——同一 Cloner 的 `identity()` 会返回同一个克隆）。
- 只在 `World.captureLive()` 开头调用，**不新增调用点**（`Gameplay.java:348,588,2209,2314,2438`、`Worker.java:219,296,356`、`WorkerPool.java:200` 及 probe 全部自动覆盖）。
- 注册后 `Statics.generation()` 变化会让旧 World 在 `install()` 时抛错（B109 既有契约）；因为 `sync()` 在抓取前、且抓取时读新 generation，所以捕获的 World 总是匹配的。
- 稳态（值没变）不注册、不变 generation，只花 ~300 次 `Field.get`。

### 4.2 克隆顺序与身份（T2/T3）

- `captureLive()` 与 `fork()` 各自只用一个 `Cloner`，`identity()` 保证同一对象只克隆一次。动态根排在 `dynamicSpecs`（`Statics.java:460`），即在 `ROOTS/CONTAINERS` 之后处理；玩家/怪物/牌堆先克隆，动态根的字段若反向指回它们，会映射到同一份克隆，不会复制出第二个玩家。
- `Kind.CONTAINER` 现在是**浅拷贝**（`World.copyContainer`:673 只 `new ArrayList`，元素仍是实机对象）⇒ 静态列表里的卡/怪被沙盒改写会漏到实机。T3 改为：容器元素若是战斗基类，用主 Cloner 深拷（`cloner.copy(e)`），其余元素仍按引用；`install()` 的 `freshContainer` 语义不变，`uninstall()` 仍整体还原 `live[i]`。
- `MUTABLE_CONTAINER`（static final 容器）已用 `deepContainer(cloner, v)`，不动。

### 4.3 快照字段选择（T4/T5）

```java
private static final String[] STATE_NAMES = {
    "currentHealth", "maxHealth", "currentBlock", "isDying", "isDead", "isEscaping",
    "halfDead", "lastDamageTaken", "block", "energy", "hp", "maxHp", "gold",
};
// 1) 跨层级（子类→父类全遍历）先收名字命中的字段，不受 32 限制；
// 2) 再按声明顺序补 primitive/String/enum 字段，总数上限 128；
// 3) 数组/集合/Map 仍走 shape 1/2/3/5（数组仍受 MAX_ARRAY_ELEMENTS=4096）。
```

- 名字命中用 `f.getName()`，不看声明类 ⇒ 字段顺序怎么变都收得到。
- 上限 32→128 只是给大对象兜底；真正保证覆盖的是白名单。
- T5：值是战斗基类时每窗口只读白名单字段（≤13 个 × 少量对象），不受 `SAMPLE_EVERY` 限制；非战斗值保持原采样节奏。

### 4.4 Digest 片段（T6）

```
sc=Osty#3f9a1c2b,Slime#0a71d2e4      // 类简称 + 白名单状态字段的 FNV-1a 指纹
sc=-                                  // 没有动态根
```

- 每个动态克隆根一行片段（最多 4 个，按类名排序），指纹只取 §4.3 的白名单字段 ⇒ 不看 HP 语义也能反映血量/死亡变化。
- 放在 digest 末尾；`Digest.stable()` 是文本投影，无需改。
- 价值两块：搜索前后比对（G1 的功能判据）与 `PlanCache` 键（G4）。

### 4.5 盘点日志（T2/T7/T8）

每次 `sync()` 有变化时打一行摘要（≤2 行，白天正常量），例如：

```
mirror: static entities: cloned=1 reachable=6 other=283 (new: TheNecrobinderMod.OstyManager#osty -> Osty)
mirror: static entities: holders=1 (TheNecrobinderMod.OstyManager#osty)
```

不维护手写分类表：分类由判据实时得出。旧版计划的「290 条逐条人工归类」取消（见 §5）。

### 4.6 为什么不是「把所有 objectRef 都克隆」

无脑克隆会复制引擎单例/纹理/字体/本地化表，破坏身份比较并让 fork 成本爆炸（`cloneCost` 已证明 clone 是分叉大头）。判据保持三条：引擎战斗基类、世界图不可达、非 final 字段。

## 5. 明确不做

1. 不写任何 mod 类名/字段名表（含 `OstyManager#osty`）；判据只依赖引擎基类与图可达性。
2. 不把 290 个引用一次性全量克隆（理由见 §4.6），也不维护逐条人工分类表。
3. 不加自动修复、不把「检测到」升级成「主动改内存」。
4. 不改 `Digest.stable()` 的语义（B148 撤销过类似改动）。
5. 不新增 Probe 门禁（用户 m00732/m00300）。

## 6. ①代价 ②风险 ③对 mod 的影响

### 6.1 代价

- T2 稳态：每次 `captureLive()` ~300 次 `Field.get`（≈0.05–0.15 ms）；有变化时才遍历世界图（通常 <3000 对象，≈1–3 ms），每次房间/召唤变化一次。
- T2 克隆：Osty 约 100 字段（skeleton/texture 走共享）⇒ 估 0.05–0.3 ms/叉；用 `World.cloneCostReport()` 实测，超过 0.5 ms/叉就重新评估（见 §6.2 回滚）。
- T3：容器深拷元素只在元素是战斗基类时发生，通常 0–5 个；`cloneCostReport()` 可见。
- T4：采样窗口每字段多约 96 次反射读（300 引用最坏 ≈2.9 万次）≈0.5–2 ms，仅采样窗口；非采样窗口不变。
- T5：每窗口 ≤13 字段 × 少量战斗基类静态 ⇒ <0.05 ms/窗口。
- T6：digest 多 ~40 字符 + 每根 13 次反射读，可忽略。

### 6.2 风险

| 风险 | 触发 | 缓解 / 回滚 |
| --- | --- | --- |
| 误判克隆（某个非战斗对象恰好是 AbstractCard 等且不可达） | 缓存型静态字段 | 冒烟日志点名；`mirrorEntityIgnore` 单字段排除；最坏回滚 T2 一行调用 |
| 克隆后仍有 final 静态持有者指向实机 | 其它 mod 用 final 字段缓存实例 | T8 启动即报 `holders=N`，记已知边界（不静默） |
| 分叉克隆成本上升 | Osty 字段多/层级深 | `cloneCostReport()` 实测；>0.5 ms/叉则先只保留 capture 克隆并重新评估（记录为边界，不偷偷放宽） |
| 快照变大触发卡顿 | 大对象 128 字段 | 白名单只收标量；数组仍受 4096 上限与 Bulk 比较 |
| digest 新增片段让旧计划缓存全部失效一次 | 升级后首次计算 | 一次性代价，可接受 |
| `sync()` 在窗口未关时被调用读到沙盒值 | 重入/异常路径 | 首行 `if (World.anyInstalled()) return;` |

### 6.3 对 mod 的实际影响

- 亡灵契约师：计算不再改实机 Osty 血量；面板/路线在 Osty 参与战斗时更准（沙盒里 Osty 掉血/死亡会真正影响结果）；每次「计算」后实机数值不变。
- 其它 mod：判据不认名字 ⇒ 任何 mod 的「静态单例持有的战斗实体」自动获得同样的隔离；普通静态（配置/统计/UI/markers）仍不克隆，零额外开销。
- 面板/自动出牌：动作与文案不变；计划缓存键多一段指纹，命中率略降（只在实体状态变化时失效，这是正确的）。
- 兼容性：不改 `Kind` 枚举、不改 `World` 协议；新增两个 settings 键（`mirrorEntityRoots` 默认 auto、`mirrorEntityIgnore` 默认空）；旧配置与旧计划文件可读。

## 7. 文档更新清单（T10，2026-11-17 已按不特判口径改完）

- `docs/PLAN-BUGS.md`：B192 修法改为「StaticEntities 通用盘点 + copyContainer 深拷 + 快照按名字 + 通用 digest」，并记下 m00273 决策。
- `docs/PLAN.md`：B192 行的 **计划** 段已同步（无 mod 类名表）。
- `docs/PLAN-WORKLOG.md`：B192 记录已改为「通用盘点 + 快照完整化 + 通用 digest」，并新增 m00273 决策行。
- `docs/SANDBOX-MECHANISMS.md`：M52 已改为「静态指向的战斗实体默认不被镜像；由 StaticEntities 盘点后按引擎基类动态克隆」。
- `docs/PLAN-ISOLATION.md`：§12 交叉链接已改为指向本文的通用方案。
- `docs/PLAN-BUGS.md` B192：新增「实施记录（2026-11-17）」（文件级改动、jar 大小/哈希、install 被锁、未做项 T1/T8、与计划偏差）。
- `docs/PLAN.md:178`：B192 状态列随每轮更新，现为「四轮累计根因 + 最新 jar `EAAEA9C8…CD0F` + 待用户实测」。
- `docs/PLAN-WORKLOG.md`：B192 已有第一/二轮实施行与第三/四轮小节（含 settings 两键、各轮 jar 哈希与回滚点）。

## 8. 已知边界

1. `StaticWatch` 的定位是**事后回写**，不是隔离；动态克隆覆盖不到的字段仍靠它兜底（T4/T5 让它至少不再静默）。
2. 只覆盖「静态字段直接指向」的战斗实体；管理器 → 列表 → 对象的更深层，由 `MUTABLE_CONTAINER/CONTAINER` 深拷处理，再深的不保证。
3. 有 final 静态字段指向同一实体时无法换引用 ⇒ 克隆会分裂身份，T8 只告警不修（记边界）。
4. `SpireField` 的 per-card 状态、`ResolveField` 之类的描述符不纳入（状态在 `get(card)` 内），另案。
5. 第三方 mod 若在非主线程改写同一个静态，仍走 B169 的「释放就不回写」规则，可能与沙盒快照不一致；出现时按 B169 记录。
6. 克隆的 Osty 共享 spine skeleton/atlas（ClonePolicy 共享规则），只影响表现，不进模拟结果。
7. 宠物被克隆后，mod 内部用 `==` 比较「当前奥斯提」的地方会拿到沙盒副本（隔离的正确语义）。若某 mod 把它写进跨战斗字段，会表现为「面板对、实机不跟随」，用 `mirrorEntityIgnore=TheNecrobinderMod.OstyManager#osty` 回退。
8. `hasHealthState` 兜底（同名 `int currentHealth`+`int maxHealth`）可能把非战斗对象也判成实体，代价仅是多重一次克隆，不会写实机。

## 9. 实施顺序与回滚

顺序：T1 → T4 → T5（先让泄漏可见、可还原）→ T2 → T3（再让它不被碰）→ T6 → T8 → T7 → T9（用户验收）→ T10。

回滚：

- T2：删掉 `World.captureLive()` 的首行 `StaticEntities.sync()` 即回到现状（其余 T4/T5/T6 独立有效）。
- T3：`copyContainer` 恢复浅拷贝一行即可。
- T4/T5：还原 `StaticWatch.snapshot` 与采样条件。
- T6：删 `sc=` 段即兼容旧缓存键。

## 附录 A — 本方案如何命中 Osty（逐条走判据，而不是列 mod 表）

| 判据 | Osty 的实际值 | 结果 |
| --- | --- | --- |
| 字段可换引用 | `OstyManager.osty` 非 final | 通过 |
| 值是引擎战斗基类 | `Osty extends AbstractCreature` | 通过 |
| 世界图不可达 | 只在 `OstyManager.get()` 里出现，不在 player/monsters/piles 图中 | 通过 ⇒ 动态注册 `Kind.CLONE` |
| 快照看得见（T4） | 名字白名单跨层级命中 `currentHealth/maxHealth/isDying/isDead` | 通过 |
| digest 可见（T6） | 动态根 ⇒ `sc=Osty#…` | 通过 |

同样的五条判据对以下典型形态自动成立（无需登记）：mod 的宠物/分身（`AbstractCreature` 子类）、只由静态单例持有的 `AbstractPower`、静态缓存的 `AbstractCard`（若不在牌堆里）。
