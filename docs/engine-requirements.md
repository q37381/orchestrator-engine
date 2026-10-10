# 通用逻辑编排引擎 需求规格说明书

| 项 | 内容 |
| --- | --- |
| 文档版本 | v1.0（由现有实现逆向提炼） |
| 适用工程 | `orchestrator-engine`（groupId `com.orchestrator`，artifactId `orchestrator-engine`） |
| 文档用途 | 作为**设计与代码生成的唯一输入**：按编号逐条实现即可复现等价引擎；改动引擎时按编号回归 |
| 读者 | 引擎维护者、接入方（宿主业务系统）开发、代码生成代理 |
| 措辞约定 | **必须**＝刚性约束，违反即缺陷；**应当**＝默认做法，偏离需在实现说明中给出理由；**可选/预留**＝当前不实现但保留扩展位 |
| 编号约定 | `FR-*` 功能需求，`DM-*` 数据模型，`CF-*` 配置，`EX-*` 异常文案，`NFR-*` 非功能，`OUT-*` 范围外，`GAP-*` 现状偏差，`AC-*` 验收标准 |

---

## 1 文档目的与使用方法

### 1.1 目的

本引擎解决"多个业务步骤需要按声明顺序/并行/条件分支编排，且要可观测、可干预、可补偿、可崩溃恢复"的问题。本文档把已实现的语义固化为可验证的条款，使实现可以从零生成而不丢失既有语义。

### 1.2 如何用本文档生成代码

1. 先建骨架：包结构、DTO/枚举、实体与 Mapper、配置类（第 12 节给出清单与职责矩阵）。
2. 按依赖顺序实现：`define`（DSL 解析/装载校验）→ `domain/mapper` → `context` → `runtime`（解析器/求值器/工厂/缓存）→ `core.engine`（物化 → 驱动 → 策略 → 引擎 → 服务）→ `api` → `config` → `web`。
3. 每完成一节需求，用第 11 节的验收条目自检；**任何一条 `FR` 无法对应到实现或测试，即视为未完成**。
4. 遇到本文档与实现细节冲突时，以 `GAP-*`（第 10.2 节）列出的判定为准，不要静默选边。

### 1.3 与实现的关系

本文档描述的是**行为契约**，不要求类名/文件名与现状一致（第 12 节的类清单是推荐布局，不是硬约束）。但**表名、列名、状态字符串、错误码、占位符语法、配置键**属于对外契约，必须逐字一致。

---

## 2 产品定位与交付形态

### 2.1 定位

- **嵌入式组件库**（打成 jar 被宿主 Spring 容器引用），不是独立服务、不是 Spring Boot Starter。
- 面向"长链路、可并行、可分片、需要人工介入与业务补偿"的编排场景：数据库迁移、批量校验、跨系统数据比对等。
- **DB 是真值，线程池是加速器**：所有状态、闸门、进度都落库；内存只承载单次驱动的执行栈。

### 2.2 交付形态与装配

| 编号 | 需求 |
| --- | --- |
| FR-DEPLOY-01 | 引擎**必须**以单个 Java 配置类 `ProcessEngineConfig` 作为统一装配入口，宿主通过 `@Import(ProcessEngineConfig.class)` 集成；配置类**必须**组件扫描 `core`、`api`、`util` 三个包 |
| FR-DEPLOY-02 | 引擎**必须**自建独立的 `SqlSessionFactory`（Bean 名 `processSqlSessionFactory`）与 `MapperScannerConfigurer`（只扫引擎 mapper 包），与宿主自己的 MyBatis 设施在 Bean 名与包名上双向隔离 |
| FR-DEPLOY-03 | 引擎**必须**不托管事务：全部写语句裸执行，不使用 `@Transactional`，不要求宿主提供事务管理器；跨语句的原子性靠"前置资格判定 + 闸门最后翻"实现（见 FR-INT-05） |
| FR-DEPLOY-04 | 引擎**必须**只依赖宿主提供的 `DataSource`：上下文里恰好一个或标 `@Primary` 时自动取用；多数据源无法判定时允许通过 `setDataSource(..)` 显式指定；引擎不得创建默认数据源 |
| FR-DEPLOY-05 | REST 入口**必须**放在扫描范围之外的独立包（`process.web`），且 `spring-webmvc` 依赖**必须**声明为 `optional`：未引入 webmvc 的宿主不得因引擎装配而失败 |
| FR-DEPLOY-06 | 表名**必须**可整体换命名空间：7 张表一律由 `tablePrefix`（默认 `process_`）+ 表名派生，经 MyBatis `${orch.table.*}` 变量在解析 XML 时一次性文本替换；设 `""` 即回落到裸表名。任何一张表都不得享有"固定名特权" |

### 2.3 运行依赖（版本为契约下界）

| 依赖 | 版本 | 用途 | 约束 |
| --- | --- | --- | --- |
| JDK | 8 | 语言级别 | **必须**能用 Java 8 编译运行（源码/目标 1.8，编码 UTF-8） |
| Spring Framework | 5.3.x（`spring-context`、`spring-jdbc`） | 容器、线程池、数据源 | **必须**不依赖 Spring Boot |
| MyBatis / mybatis-spring | 3.5.x / 2.1.x | 持久化 | 枚举入参**必须**经 `#{x.status}` 取小写规范值，**禁止**注册 TypeHandler |
| SnakeYAML | 2.x | 定义解析 | **必须**用 `SafeConstructor(LoaderOptions)`（2.0 起无参构造已删除）；**必须**显式放宽 `codePointLimit`（8MB）以容纳大定义 |
| Jackson | 2.1x | JSON 桶、定义快照 | 派生方法**必须** `@JsonIgnore`（见 FR-LOAD-08） |
| SLF4J | 1.7.x | 日志门面 | 引擎只依赖 API，实现由宿主提供 |
| H2 / JUnit4 / Mockito | test | 测试 | **必须**不起 Spring 容器（见 NFR-TEST-01） |

### 2.4 明确不做（避免生成出不需要的东西）

- 不做模板继承、不做跨流程片段复用语法（`process_fragment` 仅留 DDL，见 OUT-01）。
- 不做用户会话/鉴权：当前用户由调用方取好后传入（见 FR-AUDIT）。
- 不做业务专表写入示范：校验项清单、结论总表等业务表由宿主的 handler/listener 负责。
- 不做消息队列、不做分布式锁服务、不做定时调度器（cron 语义属预留）。

---

## 3 术语与概念模型

### 3.1 术语表

| 术语 | 定义 |
| --- | --- |
| 流程定义 | 一棵 YAML/JSON 描述的不可变执行规格，定位键 `(process_def_id, version)` |
| 流程实例 | 定义的一次执行，业务键 `proc_inst_id`；钉死 `def_version` |
| 节点实例 | 实例树上的一行，业务键 `node_inst_id`；`type ∈ {action, parallel, if, group}`；`parent_id` 为空即顶层根 |
| 叶子节点（ACTION） | 实际跑业务动作的节点，内含 1..n 个 action |
| 匿名控制节点 | 只存在于 `flow` 表达式里的 `PARALLEL`/`IF`，`nodes` 下无声明，`node_def_id/name` 为 NULL |
| 命名容器 | `GROUP:名(...)` 聚合节点，或带命名后缀的 `PARALLEL:名(...)`：`nodes` 下有纯容器声明，可就近挂 `errorPolicy/when/config/listeners` |
| 聚合节点（GROUP） | 有名字的**串行**容器，用于"一个业务校验项 = 一个节点" |
| 分片节点 | 声明了 `shard: <resolver 名>` 的叶子节点，执行时按分片键展开为多条 lane |
| lane（泳道） | 分片节点内一个分片键的执行容器，业务键 `lane_id`，是 action 的容器之一 |
| action | 最小执行单元，绑定一个 `ActionHandler`，业务键 `action_inst_id` |
| 桶 | 每层上下文里的 `config`/`params`/`result` 三个 Map，加上流程级独有的 `vars` |
| 占位符 | `${路径}` 文本；路径首 token 在保留集（`vars/params/config/result/process/node/lane/action`）内才由引擎解析 |
| 间接键 | 占位符路径里再嵌占位符：`${vars.dialects.${vars.dialect}.lockSql}` |
| 物化 | 由定义表达式创建实例行的过程 |
| 走查对齐 | 把定义表达式作为"走查光标"按下标挂到实例行的 transient 字段上（行 ↔ 表达式配对只在这里发生） |
| 闸门 | `process_inst.status` 这一份唯一真值，驱动路径每步读它判取消 |
| 认领 | CAS 把流程从 `pending` 翻 `running`，取得唯一驱动权 |
| 补偿/回退 | 对已成功且可补偿的 action **逆序**调用 `compensate()`（Saga 向后恢复） |
| 监听器 / 观察者 | 声明式切点（可否决、带 config）/ 全局自动切点（纯观察、吞异常） |

### 3.2 概念模型：四层实例树 = 四层上下文父链

```
ProcessContext（流程：config=创建输入，vars，result）
  └─ NodeContext（顶层根：ACTION / PARALLEL / IF / GROUP，自带实例行）
       ├─ NodeContext（容器子节点：父 = 所属容器的 NodeContext）
       │    └─ ...（GROUP 可嵌套 GROUP/PARALLEL/IF/ACTION）
       └─ LaneContext（分片节点的一条 lane）
            └─ ActionContext（lane 内或节点内的一个 action）
```

| 编号 | 需求 |
| --- | --- |
| FR-MODEL-01 | 上下文父链**必须**与实例树（`parent_id`）同形：容器的子节点上下文挂在容器的 `NodeContext` 上，而不是直接挂流程。因此 `NodeContext.process()` **必须**沿父链上溯而不得等于 `parent()` |
| FR-MODEL-02 | 各层桶**必须**相互隔离：引擎**不得**自动把下层值写入任何上层桶（无 action 结果上挂、无组内冒泡）。跨层共享一律由使用者显式放置（`ctx.upward().putResult`、`ctx.node().parent().putResult`、`ctx.putVar`）或写业务专表 |
| FR-MODEL-03 | `NodeContext` **必须**直接持有实例行（`NodeInstEntity`），驱动与策略只传这一个对象：行内字段（type/expr/errorPolicy/shard/config）与派生信息（定义、展示名）同源，**禁止**再单独传节点参数形成两套真相 |
| FR-MODEL-04 | `vars` 与 `userId` **必须**只在流程级存储：任意层的 `putVar/getVar/vars()/userId()` 一律逐级向上委派到 `ProcessContext` |

---

## 4 角色与典型场景

| 角色 | 关注点 | 入口 |
| --- | --- | --- |
| 流程作者 | 写 YAML：形状、策略、guard、交接 | 定义表 `process_definition` |
| 业务开发 | 实现 `ActionHandler`/`ShardResolver`/四类 Listener/Observer | Spring Bean + `name()` 路由 |
| 调用方系统 | 创建、启动、查进度、干预、回退 | `ProcessFacade`（或 `run()` 语法糖 / REST） |
| 运维 | 暂停/恢复/停止/重试/跳过/回退 | 门面干预入口 + REST |
| 宿主平台 | 提供 `DataSource`、5 个业务键序列 Bean、发布定义行、按需注册 Dispatcher | 配置 |

- **场景 A 全自动迁移**：`setup → flow(含 PARALLEL/IF) → setdown`，`createAndStart` 同步跑完，返回值即终态。
- **场景 B 批量校验**：`materialization: EAGER` + 全局 `errorPolicy: CONTINUE_ON_ERROR`；一个校验项 = 一个聚合节点；前端轮询 `progress` 拿 `groups` 分组视图；流程 `before` 监听器批量插校验项专表，action `after` 监听器逐片回写。
- **场景 C 失败人工处置**：失败后现场修复 → `retry(procInstId)` 从断点续跑；或 `doRollback` 逆序撤销副作用；定义配 `autoRollback: true` 则终态 `failed/stopped` 时引擎当场补一轮。
- **场景 D 多机部署**：创建时给 `schedUnit(+schedZone)` 写调度行，只有能力命中的 worker 主动认领；未写调度行的流程只能同步 `start` 触发。

---

## 5 功能需求

### 5.1 流程定义 DSL

顶层段（**全部可选**，缺省即"没声明"，语义见各段）：

| 段 | 类型 | 语义 | 缺省 |
| --- | --- | --- | --- |
| `materialization` | 字符串 | `EAGER`＝创建期物化整棵**节点树**；`LAZY`＝执行到一个物化一个 | `LAZY` |
| `vars` | 嵌套 Map | 流程级变量空间初值；**必须**可含任意嵌套子包（引擎不认任何保留键名） | 空 Map |
| `flow` | 字符串 | 节点执行顺序 DSL（见 5.2） | 空（无根） |
| `setup` | 字符串列表 | 前置节点引用，`flow` 之前按序执行；**必须**只放叶子节点 | 无 |
| `setdown` | 字符串列表 | 收尾节点引用，**finally 语义：无论成败都执行**；**必须**只放叶子节点 | 无 |
| `listeners` | 列表 | 流程级监听器（`ProcessListener`），按声明顺序执行 | 无 |
| `rollbackListeners` | 列表 | 回退级监听器（`RollbackListener`），补偿路径自己的 before/after | 无 |
| `globalNodeListeners` | 列表 | 全局节点级监听器：对每个节点（含控制节点）都生效 | 无 |
| `globalActionListeners` | 列表 | 全局 action 级监听器：对每个 action（含每 lane 每个）都生效 | 无 |
| `errorPolicy` | 字符串 | **流程级全局**错误策略（`FAIL_FAST`/`CONTINUE_ON_ERROR`），节点未声明即回落它；同时决定顶层根序列的收敛 | `FAIL_FAST` |
| `autoRollback` | 字符串 | 自动回退开关，**只认 `true`/`false`**（大小写不敏感） | 关 |
| `nodes` | Map | 节点声明表；**Map 的键 = `flow` 里的引用 id** | 空 |

节点段属性（`nodes.<id>`）：

| 属性 | 类型 | 语义 |
| --- | --- | --- |
| `name` | 字符串 | 展示名（进度/日志用）；未声明回落 id。**改名不必动 flow** |
| `handler` | 字符串 | 单 action 节点：`ActionHandler.name()` |
| `actions` | 列表 | 节点内多 action（与 `handler` 二选一；同时存在时 `actions` 生效） |
| `shard` | 字符串 | `ShardResolver.name()`；非空即分片节点 |
| `laneExecution` | 字符串 | 分片 lane 串/并行：`SERIAL`（默认）/`PARALLEL` |
| `actionExecution` | 字符串 | 容器内 action 序列串/并行：`SERIAL`（默认）/`PARALLEL` |
| `when` | 字符串 | 节点/容器级 guard；假则该节点（或整容器及其子项）`SKIPPED` |
| `errorPolicy` | 字符串 | 节点自己声明的策略；优先于流程级全局。**同时决定它在串行序列里"遇坏是否叫停上层"** |
| `config` | Map | 业务配置，物化期 PLAN 解析后冻结落库；子层用 `${config.k}` 沿父链可读 |
| `listeners` | 列表 | 节点级监听器（`NodeListener`） |
| `group` | 段 | `{flow: ..., nodes: ...}`，声明即成为聚合节点（见 5.4） |

action 段属性（`nodes.<id>.actions[i]`）：`id`（节点内唯一，也是实例行 `action_def_id`）、`name`（展示名，未声明回落 id）、`handler`、`when`、`config`、`listeners`（`ActionListener`）。

监听器声明条目：只有 `name`（路由到对应 SPI 的 `name()`）+ `config`（原样传给监听器）两项；**没有 `order` 字段**，执行顺序＝声明顺序。

| 编号 | 需求 |
| --- | --- |
| FR-DEF-01 | 定义**必须**不再声明 `name`/`version`：以 `process_definition` 表的 `(process_def_id, version, name)` 列为准，创建/发布时外部传入 |
| FR-DEF-02 | 定义**必须**不认任何业务保留键名：方言字典、片段库等一律是 `vars`/`config` 下的普通嵌套包，"按运行期值挑一支"由间接键表达 |
| FR-DEF-03 | 节点**必须**不需要 `type` 字段：`nodes` 下声明的一律是叶子 ACTION；控制结构（PARALLEL/IF/GROUP）由 `flow` 表达式表达 |
| FR-DEF-04 | `vars`/`nodes`/`config`/`actions` 段"写了但留空"（YAML 解成 null）**必须**与"整段缺失"同口径回落空容器，不得留 NPE 种子 |
| FR-DEF-05 | 单 handler 节点的生效 action 列表**必须**合成一条（id=节点 id、name=节点展示名、handler/config 继承节点），使"行与 ActionDef 按下标对齐"这一条路径在两形态下统一 |

### 5.2 flow 表达式语法

EBNF（规范化，空白仅允许出现在标注处）：

```
flow        ::= items
items       ::= expr ( "," expr )*                       # 顶层逗号 = 平级多个根，不包虚拟 SERIAL 根
expr        ::= parallel | if_ | group | NODE_REF
parallel    ::= "PARALLEL" [ ":" NAME ] "(" items ")"     # 命名后缀可省 = 匿名并行
if_         ::= "IF" "(" CONDITION "," expr [ "," expr ] ")"   # CONDITION 可含空格/比较符，按顶层逗号或右括号截断
group       ::= "GROUP" ":" NAME "(" items ")"            # 名字**必须**写
NODE_REF    ::= 不含逗号、括号、冒号、空白的 token
```

| 编号 | 需求 |
| --- | --- |
| FR-DSL-01 | `flow` 文本**必须**可被解析器重新解析，且解析结果**必须**能通过 `toFlow` 写回与原文一致的文本（往返互逆）。任何语法扩展若不能往返，禁止合入 |
| FR-DSL-02 | 顶层逗号**必须**扁平化为多个根（无虚拟 SERIAL 节点）：顶层行序 = `setup 引用 + flow 顶层 + setdown 引用` 拼接后的表达式序 |
| FR-DSL-03 | **命名一律写在关键字与括号之间**（`PARALLEL:checks(a,b)`），括号里只放执行形状。`IF` **必须**拒绝命名后缀并报 `AU204110`（IF 的条件占着括号首位，没有"纯容器声明"口径） |
| FR-DSL-04 | 冒号是保留字符：普通节点引用中出现冒号**必须**当场拒绝（`AU204107`），使旧写法 `PARALLEL(checks: a,b)` 在括号内冒号处报错，而不是被静默读成"一个名叫 checks 的节点引用" |
| FR-DSL-05 | `GROUP` **必须**带命名后缀，无名 `GROUP(a,b)` 一律拒绝（`AU204102`）。缘由：旧写法 `GROUP(名字,a,b)` 与匿名容器 `GROUP(a,b)` 文本同形而形状不同（1 个容器 vs 2 个子项），猜错会导致实例行与表达式树错位多出一行 |
| FR-DSL-06 | 命名容器空子项**必须**拒绝：`PARALLEL:checks()`/`PARALLEL:checks( )` 报 `AU204108`；`GROUP:名()` 报 `AU204103`。匿名 `PARALLEL()` 允许（无子项即空扇出） |
| FR-DSL-07 | 关键字后只有冒号没有名字（`PARALLEL:(a,b)`）**必须**拒绝（`AU204109`）；表达式无法识别的表达式类型写回时报 `AU204104`；缺节点引用报 `AU204105`；缺期望字符报 `AU204106`；解析结束仍有多余字符报 `AU204101` |
| FR-DSL-08 | 容器子项列表允许尾随逗号（`PARALLEL(a,b,)`），**不得**因此多解析出一个空表达式 |
| FR-DSL-09 | 实例定位**必须**不做路径反查：驱动按定义顺序前向走查，实例行按 `auto_id`（声明顺序）升序与表达式列表**按下标对齐**；已有终态行幂等跳过，缺行用手里的表达式补物化 |

### 5.3 表达式的运行期语义

| 表达式 | 语义 | 收敛口径 | 物化时机 |
| --- | --- | --- | --- |
| `NODE_REF`（ACTION） | 跑节点内 action 序列（分片则按 lane 展开） | 见 FR-EXE-08 | LAZY：走查到才建；EAGER：创建期已建 |
| `PARALLEL(...)` | 子表达式全部扇出并发，join 按 severity 聚合 | 容器自己那一条策略；兄弟并发跑，无从"事后叫停" | 容器行创建期/走查期；子行 LAZY 建一个扇出一个 |
| `IF(cond,then[,else])` | 求值 → 只驱动选中分支；无选中分支（假且无 else）→ `SKIPPED` | 单分支串行语义 | **分支恒惰性**：即便 EAGER 也只在建 IF 行时不建分支，决策后补建选中分支 |
| `GROUP:名(...)` | 命名**串行**容器：子表达式按声明顺序逐个驱动 | 就近声明优先（同根序列） | 同 ACTION/GROUP 自身 |

| 编号 | 需求 |
| --- | --- |
| FR-DSL-10 | `PARALLEL` 的 `FAIL_FAST` **必须**是 drain 语义：不取消已在跑的兄弟（无法安全中断），只取消尚未开始的排队任务，等全部 join 完再聚合失败 |
| FR-DSL-11 | `IF` 的决策**必须**持久化进节点 `config` 的保留键 `__chosen`（值 `then`/`else`）：恢复/重试时复用不重算，保证分支选择确定 |
| FR-DSL-12 | 控制节点（匿名 PARALLEL/IF）**必须**没有 `NodeDef`：其策略取流程级全局、`condition_expr` 留空（IF 例外，落条件文本）、`node_def_id/name` 为 NULL |

### 5.4 聚合节点（GROUP）与装载期展开

作者侧写法（对外只暴露一个名字）：

```yaml
flow: "spaceCheck"
nodes:
  spaceCheck:
    name: 空间占用校验
    errorPolicy: FAIL_FAST
    config: { itemTable: t_validate_space }
    group:
      flow: "fetchSource,diffPerShard"
      nodes:
        fetchSource: { handler: spaceProbeSource }
        diffPerShard: { shard: targetTopology, laneExecution: PARALLEL, handler: spaceDiff }
```

装载期展开为：`flow: "GROUP:spaceCheck(spaceCheck.fetchSource,spaceCheck.diffPerShard)"`，并在顶层 `nodes` 增加 `spaceCheck.fetchSource`、`spaceCheck.diffPerShard` 两条（**组自己那条保留**，承载 name/when/errorPolicy/config）。

| 编号 | 需求 |
| --- | --- |
| FR-GRP-01 | 展开结果**必须**落在可序列化字段上（重写 `flow` 字符串 + 提升子节点进 `nodes`），**不得**只活在内存：定义缓存存 canonical JSON 快照，而 `roots`（解析结果）是 transient，借出的副本各自重解析 `flow` |
| FR-GRP-02 | 提升键分隔符**必须**固定为 `.`（`<组>.<子>`）；提升后与既有键冲突**必须**拒绝（`AU204208`） |
| FR-GRP-03 | 组内 `flow` 的引用**必须**按四种情况重绑定：① 组内嵌套聚合 → 换成其 `Group` 表达式；② 组内普通子节点 → 加组前缀；③ 引用**另一个顶层聚合节点** → 拒绝（`AU204209`，否则会物化成一条无 handler 的 ACTION 行、驱动期才炸）；④ 引用外层普通节点 → 原样保留（合法，成为本组子行）；既不在组内也不在外层 → 拒绝（`AU204210`） |
| FR-GRP-04 | 声明了 `group` 的节点**必须**是纯容器：再声明 `handler`/`actions`/`shard`/`laneExecution`/`actionExecution` 一律拒绝（`AU204204`）——否则这些字段被容器语义静默忽略 |
| FR-GRP-05 | `group` 段**必须**有 `flow`（缺 → `AU204206`）与非空 `nodes`（缺 → `AU204207`）；嵌套深度上限 8 层（超限 → `AU204205`） |
| FR-GRP-06 | 聚合节点**必须**只能出现在 `flow` 里：出现在 `setup`/`setdown` 段拒绝（`AU204202`/`AU204203`，那两段是字符串列表，表达不了容器） |
| FR-GRP-07 | 手写 `GROUP:名(...)` 而 `nodes` 下无该键**必须**允许，语义为"退化成匿名串行容器"（吃全局策略、无组级 guard）；该退化口径不适用于并行命名后缀（见 FR-PRAL-02） |
| FR-GRP-08 | 组内跨步骤交接**必须**靠显式放置：引擎**不得**把子节点 result 冒泡进组桶；作者文档/示例应指导 `ctx.node().parent().putResult(k, v)` + 后续 `${result.k}` |
| FR-GRP-09 | 聚合节点**必须**不支持自身带 `shard`（语义会变成"每 lane 跑一遍整个子流程"）；分片永远在叶子子节点上 |

### 5.5 命名并行容器

| 编号 | 需求 |
| --- | --- |
| FR-PRAL-01 | `PARALLEL:名(a,b)` 的后缀**必须**指向 `nodes` 下的一条纯容器声明，使并行容器获得与聚合节点同构的三项能力：自己的生效策略（声明 > 全局 > 默认）、容器级 `when`（假则整条并行 `SKIPPED`、一个子项也不物化/不提交）、`config` + 节点级监听器链 |
| FR-PRAL-02 | 并行命名是用户手敲的，**必须**装载期成对校验（区别于 FR-GRP-07 的宽容退化）：① 后缀无对应声明 → 拒绝（`AU204212`）；② 声明不是纯容器 → 拒绝（`AU204204`）；③ 声明成了纯容器却未被任何地方引用（死配置）→ 拒绝（`AU204213`）。理由：禁止"我明明设了策略却没生效"拖到运行期 |
| FR-PRAL-03 | 匿名 `PARALLEL(a,b)` 行为**必须**保持不变（恒吃全局策略、无 guard/config、无展示快照），保证存量定义零影响 |
| FR-PRAL-04 | 展开/替换重建表达式树时**必须**把 `Parallel.name` 一起带走；漏一行＝静默丢命名 |

### 5.6 定义装载与校验

| 编号 | 需求 |
| --- | --- |
| FR-LOAD-01 | YAML **必须**用 `SafeConstructor` 解析，禁止任意对象实例化 |
| FR-LOAD-02 | `materialization` 声明值非法**必须**拒绝（`AU204201`）；反查**必须**大小写不敏感且与 Locale 无关（不得使用依赖默认 Locale 的 `toUpperCase()`） |
| FR-LOAD-03 | 顶层布尔开关（目前只有 `autoRollback`）**必须**"写了拼错当场拒"（`AU204214`），未写/空白视为没声明；落库文本**必须**归一化为小写 `true`/`false` |
| FR-LOAD-04 | 监听器链的生效值**必须**由"全局链 + 本地声明"合并：**全局在前、本地在后**，各按声明顺序，**不做同名去重**（同名视为有意的两次触发，各带各的 config）；两边都没声明返回 `null`（＝该切点 no-op） |
| FR-LOAD-05 | 错误策略的生效值**必须**按分层单值覆盖：节点声明 > 流程级全局 > 默认 `FAIL_FAST`。与监听器链的"拼接"语义不同 |
| FR-LOAD-06 | 串行序列（顶层根序列、GROUP 组内序列）的收敛**必须**"就近声明优先"：该子项自己写了 `errorPolicy` 就听它的（`FAIL_FAST`＝我坏了叫停剩余，`CONTINUE_ON_ERROR`＝我失败也别拦后面），没写才回落本层策略。匿名 PARALLEL/IF 无声明恒回落本层 |
| FR-LOAD-07 | **一次驱动只准取一次定义**：定义缓存按 (defId, version) 存 canonical JSON 快照，`definition()` 每次反序列化出一份**私有副本**（防止监听器/handler 通过活的 setter 永久污染缓存）。调用点必须复用 `DriveContext.def()`，不得在循环/策略里重复借出 |
| FR-LOAD-08 | 定义类的**派生方法必须** `@JsonIgnore`（`isEager()`、`roots()`、`orderedRoots()`、`flowOrder()`、`isSharded()` 等）：类要 JSON 往返，按 bean 约定写出去而回读无 setter 会直接失配抛异常 |
| FR-LOAD-09 | 严格校验的落点**必须**分档：`load()` 同时被驱动路径（定义缓存借出重解析）调用，新增的严格规则不得无条件塞进 `load()`，否则线上存量已发布定义会在驱动路径被新规则拒死；严格档应挂在发布/新建入口（独立校验器或 `strict` 开关） |

### 5.7 流程实例创建与版本定位

| 编号 | 需求 |
| --- | --- |
| FR-CRT-01 | `version` 为空**必须**取该 `defId` 的**最新已发布版**（`status='published'` 且 version 最大）；无已发布版报 `AU204401`。给定 version 则按 `(defId, version)` 精确定位，行不存在报 `AU204402` |
| FR-CRT-02 | 流程实例初态**必须**是 `pending`（可被认领），并落 `process_def_id`、`def_version`（钉死版本＝监听器等一切由定义推导的数据从此恒定）、`creator_id`/`updator_id` |
| FR-CRT-03 | 创建输入**必须**落在**流程级 `config`**（与 node/lane/action 同名同语义，故 `${config.*}` 能沿父链上溯到它）；**必须**不建 `params` 列、不持久化 params 桶 |
| FR-CRT-04 | 初始 `vars` **必须** = 定义 `vars` 合并创建参数（供物化期 PLAN 解析）；两桶同名键**必须** warn 并让参数覆盖（物化期两种写法取同一值、运行期才分岔，属可观测陷阱） |
| FR-CRT-05 | `EAGER` 时创建期**必须**一次性递归物化**整棵节点树**（含组、组的子节点、命名并行的子节点），但 **lane/action 一律驱动期建**（叶子物化创建期不预建子行）；`LAZY` 创建期**必须**不建任何节点行 |
| FR-CRT-06 | 路由**必须**是可选能力：给了 `schedUnit` 才往调度表写一行（进主动调度池），`schedZone` 可选（NULL＝同单元任意区域可认领）；没给 `schedUnit` 则不写行，调度器不主动认领（仍可同步 `start`）。两行写入非同一事务：**先插流程再插调度行**，调度行写失败最坏是"pending 但未被主动调度"，属安全失败 |
| FR-CRT-07 | `vars` 桶**必须**不收 null 值（`ConcurrentHashMap` 硬约束）：初值里某键为 null 则跳过；`putVar(k, null)` 语义为删键 |

### 5.8 执行与驱动

| 编号 | 需求 |
| --- | --- |
| FR-EXE-01 | `start(procInstId)` **必须**先做前置判定再 CAS 认领（`pending → running`）：行不存在/已非 pending/被别的 owner 抢先，一律**不抛异常、不造假状态**，把当下读到的状态随返回值给出（行不存在＝`null`） |
| FR-EXE-02 | 驱动骨架**必须**是：认领 → 建 `DriveContext`（live ProcessContext + 定义 + 无状态解析器/求值器 + baseVars）→ 挂定义到上下文 → 全局 Observer.before → 流程级 Listener.before（可否决）→ 顶层走查 → 收尾落终态 + Listener.after + Observer.after → 按需自动回退 |
| FR-EXE-03 | 顶层走查序**必须**是 `setup → flow → setdown` 的拼接（`orderedRoots()`）：行与表达式按下标对齐；`setup`/`flow` 段索引区间为 `[0, setupSize)` 与 `[setupSize, setupSize+roots().size())` |
| FR-EXE-04 | `setdown` 段**必须**无论成败都执行（清理锁/回写状态），且其结果**必须**参与终态聚合 |
| FR-EXE-05 | 根序列的收敛判定**必须**只看**本根那一条**状态（`driveNode` 返回值），**不得**用聚合值判：聚合值会带上更早那次的坏，用它判会把没吭声的后续根一起误杀 |
| FR-EXE-06 | 定义装载失败发生在 `DriveContext` 建成之前（无桶可脏刷）：引擎**必须**直接读写流程行，把一句因由写进 `result.__error` 并 CAS 翻 `failed`，**不发监听器**；绝不允许流程僵在 running 且查不到原因 |
| FR-EXE-07 | 节点驱动**必须**分两档：行已是终态 → 幂等直接返回原终态（不触发监听器/策略）；未执行 → `markRunning` → Observer.before → NodeListener.before（可否决）→ 策略分发 → 收尾落终态 + NodeListener.after |
| FR-EXE-08 | 串行 action 序列**必须**"执行到第 i 个才建第 i 个"（LAZY 缺行补建；EAGER/重入命中已存在行），内联跑；`FAIL_FAST` 遇坏即停并 drain 后续已物化行。**并行** action 序列**必须**先补齐全部行再扇出（扇出需同时在） |
| FR-EXE-09 | action 执行流水**必须**是：重建 payload → 建 `ActionContext` → 摘掉上一轮 `__error`/`__skipReason`/`__unresolved` → ACTIVATION 解析 config → 检出残留占位符（warn + 记 `__unresolved`）→ `when` guard → Observer.before → Listener.before → `handler.handle` → 落终态 + result → 脏刷流程级桶 → Listener.after → Observer.after |
| FR-EXE-10 | 收尾落终态**必须**用 CAS（`running → 终态`）：被人工 `stop`/`pause` 抢先翻走时，**必须以 DB 为真值**重读并返回实际状态，不得覆盖人工干预 |
| FR-EXE-11 | 流程级 `vars` 与 `result` **必须**由脏标记驱动落库：驱动中随时刷（每个 action 之后）、收尾再刷一次，因此 `after` 监听器写进流程级 result 的内容也能落住 |
| FR-EXE-12 | `handler` 返回 `null` **必须**视为 `SUCCESS`；抛异常＝`FAILED`（异常摘要写 `result.__error`） |

### 5.9 并发模型

| 编号 | 需求 |
| --- | --- |
| FR-CNC-01 | 三档扇出**必须**各用一池，不得同池互占（否则外层扇出与内层扇出互等造成 join 饥饿）：`itiesProcessParallelExecutePool`（node 扇出）、`itiesProcessActionShardParallelExecutePool`（shard lane 扇出）、`itiesProcessActionParallelExecutePool`（action 扇出）；线程名前缀分别为 `ities-process-node-`/`ities-process-shard-`/`ities-process-action-` |
| FR-CNC-02 | 三池**必须**用 `CallerRunsPolicy`：池满时由调用线程自跑，既形成背压又保证必有进展（不 hold owner 线程做 join 饥饿）；**必须** `waitForTasksToCompleteOnShutdown`，优雅等待 30s |
| FR-CNC-03 | 并行扇出的子任务**必须**递归携带**本容器的 NodeContext** 作为直属上层容器（上下文父链与实例树同形；容器嵌在 GROUP 里时，组级 config 与显式挂进组桶的 result 对扇出的子节点依然可读） |
| FR-CNC-04 | 跨线程上下文**必须**靠显式对象传递（`DriveContext`/`Context`），**禁止 ThreadLocal**（含 userId）：线程池装饰因此不需要 |
| FR-CNC-05 | 并发安全：**流程级** `vars`/`result` 的读写**必须**加锁串行化（顶层 `ConcurrentHashMap` + `synchronized` 方法），刷库前**必须**取加锁快照（`result` 是 LinkedHashMap，并行 lane/子节点线程可能正在写） |
| FR-CNC-06 | 并行子任务从 `Future.get()` 抛出的异常**必须**判为 `FAILED`，并**必须**留一句因由（日志 + 上报流程级失败记录），不得静默吞掉；同一句必须拆掉 `ExecutionException`/`CompletionException` 包装 |

---

### 5.10 上下文与数据传递

| 编号 | 需求 |
| --- | --- |
| FR-CTX-01 | 每层上下文**必须**持有 `config`/`params`/`result` 三桶 + 父指针；`vars` 只在流程级 |
| FR-CTX-02 | 读法**必须**分两档且**不得**混用：① 本层直取 `getConfig(k)/getParams(k)/getResult(k)`（平铺整键命中，不解析点号、不上溯）；② 上溯解析 `resolveConfig(k)/resolveParams(k)/resolveResult(k)`（首段在本层则桶内下钻，否则逐级向父，innermost 胜）。**禁止**存在跨三桶合并的 `get(k)`（同名 result 与 config 读到哪个全看调用方记不记得优先级） |
| FR-CTX-03 | 整桶读法只有一套：`config()/params()/result()`，返回本层视图，**不得**与 `getXxx` 重名 |
| FR-CTX-04 | 按 key 的读法**必须**返回泛型 `T`（调用点直接赋值、编译器插 checkcast，类型不符当场 `ClassCastException` 不后漂）；需要先探类型再分支的场景必须显式接 `Object` 再 `instanceof` |
| FR-CTX-05 | 落库口径**必须**是：四层各自的 `result` 列 + 四层各自的 `config` 列（流程层 config＝创建输入）；`params` **不持久化**，只装当下驱动里 `putParams` 的运行期值 |
| FR-CTX-06 | 引擎**必须**零自动上写：action 结果只留在 action 自己的行，不以 actionDefId 为 key 挂到上层容器（避免容器桶整份落库的存储放大，也保证节点间隔离） |
| FR-CTX-07 | 容器收尾**必须**只落状态，不再复制容器桶内容进上层行 |
| FR-CTX-08 | `ActionContext` **必须**提供 `node()`（沿父链找 `NodeContext`，GROUP 内会多一层）与 `upward()`（直属父），作为显式放置的样板入口：`ctx.upward().putResult(...)` 挂所属 lane/node 桶，`ctx.node().parent().putResult(...)` 挂直属上层容器（组）桶 |
| FR-CTX-09 | 取消钩子**必须**是 `ActionContext.isCancelled()`：引擎建 action 上下文时按流程闸门置位，业务长循环**自愿**检查，框架不强制 `interrupt` |

### 5.11 占位符解析（VariableResolver）

保留作用域首 token：`vars`、`params`、`config`、`result`、`process`、`node`、`lane`、`action`。

| 编号 | 需求 |
| --- | --- |
| FR-VAR-01 | 首 token 不在保留集**必须**当作外来模板语法（`${table}`、`${srcDb}`、handler 自用任何写法）：原样保留、不报错、不算残留——**引擎不替业务定性** |
| FR-VAR-02 | 解析器**必须**无状态（Spring 单例，`baseVars` 走方法入参），可注入、可测、可并发共享 |
| FR-VAR-03 | 两档时机 `Phase` **必须**是：`PLAN`（`ctx=null`，`vars/params` 用入参 baseVars；`result.*`/`config.*` 一律解不出、原样保留）与 `ACTIVATION`（`ctx` 为当前上下文，`vars` 用 live 值，`result.*`/`config.*` 正常解析） |
| FR-VAR-04 | 真实边界**必须**按"物化时刻能否解出"定性而**不是**按桶名：PLAN 解得出 → 烙成字面量冻结进 config 列（此后 vars 再变也不回退）；PLAN 解不出 → 该值退化为活引用，每次执行现取 |
| FR-VAR-05 | `${result.*}` 与运行期新写入的 `${vars.*}` **必须**只在激活期解析；`when`/`IF 条件`**不过 PLAN**，永远运行期现解。注意边界：节点级 config 无激活期重解（写在节点 config 里的活引用不会被二次解析） |
| FR-VAR-06 | 间接键**必须**支持：路径内嵌 `${...}` 由内向外先插值、再按合成后的路径取值。指针变量此刻无值 → 整条原样保留（选支因而天然落到激活期） |
| FR-VAR-07 | **部分解析**必须成立：指针有值但外层取不到值时，回写**已插值的路径**（`${config.xxx.table}`）而非原文——指针在解算那一刻固化，后续不再依赖指针变量当时的值 |
| FR-VAR-08 | 解析轮次**必须**限深为 2：外层 `FULL`（允许插值 + 对取到的值再填一轮）、内层 `FRAGMENT`（既不插值也不再填值）。删掉这层守卫，自引用（`vars={a:"${vars.b}", b:"${vars.a}"}` 或片段指向自己）会栈溢出 |
| FR-VAR-09 | `${config.*}` **必须**从本层沿父链上溯、innermost 胜，每层"先平铺精确命中再点号下钻"；**锁定层写法**（`${process|node|lane|action}.<bucket>.*`）**必须**只读那一层本桶、不再上溯 |
| FR-VAR-10 | 桶内取值**必须**兼容"键名本身带点"：先平铺精确命中，未命中再按点号下钻嵌套 |
| FR-VAR-11 | 解析过程**必须**一律不抛异常；解不出就原样保留 |
| FR-VAR-12 | 可见性**必须**具备：`findUnresolved()` 只报"保留写法却没解出"的路径（外来模板不算），由驱动路径 warn 并写进 `result.__unresolved`（仅告警，不阻断执行） |
| FR-VAR-13 | 标量类型漂移**必须**作为已知约束写进作者文档：定义经 JSON 快照往返后，裸日期等非标量会变形（`2026-10-08` → 长整数）。`vars`/`config` 的值只允许 `String/Number/Boolean/null/List/Map`，日期一律加引号 |

### 5.12 条件求值与 guard

| 编号 | 需求 |
| --- | --- |
| FR-CND-01 | **必须**支持：`== != > < >= <=`、`&& || !`、括号分组、单/双引号字符串字面量；先用解析器替换 `${...}` 再求值 |
| FR-CND-02 | 空表达式**必须**为真（不写 guard＝恒通过） |
| FR-CND-03 | 裸字面量**必须**只有 `"true"`（大小写不敏感）为真；`false`/`null`/任意其他串皆为假 |
| FR-CND-04 | 整体被一对括号包裹（含 `((...))` 层层）**必须**先脱壳再递归，否则 `(a || b)` 会走字面量分支误判；`topLevel` 扫描**必须**忽略括号深度与单引号内的运算符 |
| FR-CND-05 | 两侧都能解成数字**必须**按数值比较；否则按字符串等值比较；对非数值操作数使用大小比较**必须**抛 `AU204501` |
| FR-CND-06 | 求值前含未解析占位符**必须**打 warn（它不报错，而是连同 `${...}` 原文参与字面量比较，结果通常为 false → 静默跳过），便于排障 |
| FR-CND-07 | guard **必须**只能读持久化的 `result/config/vars`（铁律：不在求值里查活系统，保证恢复确定性）；要读创建参数一律 `${config.*}`（`params` 不持久化） |
| FR-CND-08 | 条件里的操作数**必须**带 `${}` 才会被替换：裸路径当字符串字面量比较（作者文档必须写明） |
| FR-CND-09 | guard 分三处生效且**必须**都是"判假即 SKIPPED，不算失败"：节点级 `when`（整节点跳过）、容器级 `when`（命名 GROUP/命名 PARALLEL 整体跳过、子项一个也不物化/不提交）、action 级 `when`（单条跳过，`__skipReason=GUARD_FALSE`） |

### 5.13 分片（Shard）

| 编号 | 需求 |
| --- | --- |
| FR-SHD-01 | 分片节点**必须**两阶段解析：阶段 1 `resolveShardKeys(NodeContext)` 在驱动期该节点执行时跑一次（决定"跑哪些"，要求稳定幂等，结果不单独落库但 lane 行按它创建）；阶段 2 `buildPayload(shardKey, NodeContext)` 每次执行/恢复时现场重建强类型 payload，**绝不落库** |
| FR-SHD-02 | 两阶段的入参**必须**都是**本分片节点的 `NodeContext`**（不设专用分片上下文对象）：节点级 `config/definition/nodeRef/inst` 在本层读，流程级 `vars/userId/definition/config/result` 沿父链上溯——同一 resolver 被多个分片节点引用时各拿各的上下文 |
| FR-SHD-03 | `shardKeys` 即本节点的执行范围：**空集合必须静默成功**（节点 `SUCCESS`、一条 lane 也不建）。作者文档必须写明"resolver 返回空列表应主动抛异常"，否则"拓扑查空"会伪装成"校验通过" |
| FR-SHD-04 | lane 执行方式由 `laneExecution` 决定：`SERIAL`（默认，走查到才 `laneAt` 建一条、内联跑，`FAIL_FAST` 遇坏即停后续 lane）/ `PARALLEL`（先建全 lane 再扇出 shard 池，join 聚合，`FAIL_FAST` 只取消未开始的） |
| FR-SHD-05 | lane 行**必须** find-or-create 幂等（自然键 `(node_inst_id, shard_key)` 唯一），重入命中已建行不重复插 |
| FR-SHD-06 | lane 的 `error_policy` **必须**直接继承所属分片节点行的生效值——于是全局策略天然覆盖到分片 lane |
| FR-SHD-07 | payload 取值**必须**两档：`ctx.shardPayload()` 直接泛型转型（常态，不走转换）；`ctx.shardPayload(Class)` 仅在需要跨类型转换时走 Jackson，payload 为 null 时报 `AU204001` |
| FR-SHD-08 | 分片键**不得**通过占位符读取（`${lane.shardKey}` 不是引擎可解析路径）；业务在 handler 里用 `ctx.shardKey()`/`ctx.shardPayload()` 取 |

### 5.14 监听器与观察者

| 类型 | 声明位置 | 入参上下文 | before 语义 | after 语义 |
| --- | --- | --- | --- | --- |
| `ProcessListener` | 流程 `listeners` | `ProcessContext` | 抛异常＝否决（流程 `FAILED`） | 收最终 status，纯通知 |
| `NodeListener` | 节点 `listeners` | `NodeContext` | 抛异常＝否决（节点 `FAILED`） | 同上 |
| `ActionListener` | action `listeners` | `ActionContext` | 抛异常＝否决（action `FAILED`） | 同上 |
| `RollbackListener` | 流程 `rollbackListeners` | `ProcessContext` | 抛异常＝**否决整轮回退**（一条都不补，报告记 `veto`） | 收本轮补偿聚合 outcome |
| `ProcessObserver` | 不声明，注册 Bean 即全局生效 | 按方法名区分层级 | 纯观察 | 纯观察 |

| 编号 | 需求 |
| --- | --- |
| FR-LST-01 | scope **必须**由"声明位置 + 接口类型"表达：`ListenerDef` 只有 `name/config`，不带 scope 字段；类型即 scope，编译期不会串层 |
| FR-LST-02 | 同层多个监听器**必须**按声明顺序 inline 执行；**必须**没有 `order` 排序字段 |
| FR-LST-03 | 调用次序**必须**是：`Observer.before → Listener.before → 实际执行 → Listener.after → Observer.after` |
| FR-LST-04 | `before` 抛异常＝可否决（向上传播，调用方据此落 `FAILED`/中止回退）；`after` 异常**必须**只记录、不改已定终态 |
| FR-LST-05 | Observer 的**所有**异常**必须**内部吞掉，实现方无需自行 try/catch（适合审计/指标/日志等横切关注点） |
| FR-LST-06 | 监听器链**必须**从定义推导而不落实例表：定义版本钉死＋定义不可变 ⇒ 推导结果恒定；流程级链不固化快照列，节点级链在走查对齐时挂 transient，action 级链由 `ActionDef` 现场合并 |
| FR-LST-07 | `ProcessListener`/`RollbackListener` **必须**能读全部拓扑：上下文已挂钉死版本的定义（`ctx.nodes()`、`ctx.nodeDefinition(id)`、`ctx.definition()`），因此 `before` 就能批量插入校验项，无需等节点行物化 |
| FR-LST-08 | 纯副作用监听器**必须**在作者文档里提示自行吞异常，否则会否决流程/回退 |

### 5.15 SPI 契约与注册表

| 编号 | 需求 |
| --- | --- |
| FR-SPI-01 | 所有可插拔组件**必须**通过 `String name()` 路由（定义里写的是 name，不是 bean 名/类名）；实现类注册为 Spring Bean，注册表在构造期按 `name()` 收集 |
| FR-SPI-02 | 注册**必须**在装配期查重并拒重复：同名 `ActionHandler`/四类 Listener 重复注册分别报 `AU204601/204603/204605/204607/204609`；未注册即调用报 `AU204602/204604/204606/204608/204610`，`ShardResolver` 未注册报 `AU204611` |
| FR-SPI-03 | `ActionHandler.handle(ActionContext)` **必须**只返回 `Status`（收敛为无 `ActionResult`/无跳转指令）：要存结果就 `ctx.putResult/putVar`，要条件跳过就用声明式 `when`，**不得**在 handler 里做动态跳转 |
| FR-SPI-04 | 执行契约是 **at-least-once**：`handle` **必须**幂等（重跑安全）。这条**必须**写进 SPI 注释与接入文档，因为重试/崩溃恢复都会重入 |
| FR-SPI-05 | 补偿能力**必须**由 handler 自己声明：`compensable()` 默认 `false`，`compensate(ctx)` 默认空；不可补偿的步骤在回退时导致 `BLOCKED` |
| FR-SPI-06 | 注册表**必须**容忍"集合注入为空"的装配场景：宿主一个 SPI 都没注册时，引擎装配不得失败；但依赖这些 Bean 的装配点（配置类里按类型集合注入处）**必须**有冒烟入口验证 |

### 5.16 状态机与聚合

```
created ──▶ pending ──▶ running ──▶ { success | failed | skipped | stopped }
   ▲                        │        paused（流程级，可恢复）  suspended（预留，未落地）
   └──── 干预复位 ──────────┘
```

| 编号 | 需求 |
| --- | --- |
| FR-STA-01 | 状态**必须**统一为一个枚举，携带**小写英文规范名**（落库/CAS/SQL 字面量一律小写）与 `severity`；反查一律用大小写不敏感的 `of(String)`，**禁止** `valueOf` |
| FR-STA-02 | 聚合**必须**按 severity 取更差者：`FAILED(3) > STOPPED(2) > SUCCESS/SKIPPED(1) > 其余(0 不参与)`；`SKIPPED` 对流程与控制流等价于成功完成 |
| FR-STA-03 | 分层状态口径**必须**是：`running` **只写在节点行**（`markRunning`）；action 行由 `created` 直接落终态；lane 行只落聚合终态；`PAUSED` 只在流程级存在；`SUSPENDED` 为预留未落地 |
| FR-STA-04 | `driveNode` **必须**总是返回终态（内部所有分支都收敛到 `finishNode`） |
| FR-STA-05 | `CREATED` 与 `PENDING` **必须**语义分离：`created`＝已计划、不可认领（预插入初态）；`pending`＝已激活、等待认领 |

### 5.17 失败记录

| 编号 | 需求 |
| --- | --- |
| FR-ERR-01 | 失败/跳过信息**必须**不设独立列，统一写进 `result` 的保留键：`__error`、`__skipReason`、`__unresolved` |
| FR-ERR-02 | 摘要形态**必须**是 `语境(定位): 异常类名: 消息`，语境与日志首词一致便于对照；取 `Throwable.toString()` 而非 `getMessage()`（消息为 null 的异常也还有类名可读）；ProcessException 的 `toString()` **必须**带消息键（`类名[AU204302]: 文案`）以便按码检索 |
| FR-ERR-03 | 各层 catch **必须**同时做两件事：写本行 `__error` + 经 `recordFailure` 上报流程级**那一条**摘要（首个胜出，并行同时炸也只留最先那句） |
| FR-ERR-04 | 写保留键**必须**先并入原有 result，不丢失已持久化的业务结果 |
| FR-ERR-05 | 重入**必须**反向摘残留：执行前摘掉上一轮 `__error/__skipReason/__unresolved`；流程收尾时好终态传 null 摘掉 `process_inst.result.__error`（跑成功的流程不该还挂着失败信息） |
| FR-ERR-06 | `__skipReason` 取值**必须**至少覆盖：`GUARD_FALSE`（when 判假）、`HANDLER_SKIP`（handler 自返 SKIPPED）、`DRAIN`（FAIL_FAST 取消未跑）、`MANUAL_SKIP`（人工跳过） |

### 5.18 人工干预

| 编号 | 需求 |
| --- | --- |
| FR-INT-01 | 取消**闸门**只有一份真值＝`process_inst.status`：驱动每步（每个 action/lane/子节点判定）**必须**当场读库，**不得**留本机内存状态。前提是多节点部署——干预落在 A 节点、驱动跑在 B 节点，只有 DB 两边都读得到 |
| FR-INT-02 | 判"已取消"的状态集合**必须**是 `{stopped, paused, failed}`；**查不到行保守判取消**（拿不到真值就停）；状态不识别判不取消 |
| FR-INT-03 | `pause`：`running → paused`（CAS）；`resume`：`paused → pending`（重新入队，由 Dispatcher 再认领，已完成项重入跳过）；`stop`：`running → stopped`，失败再试 `paused → stopped` |
| FR-INT-04 | 重试复位集**必须**是"本轮没跑成"的那些：`{failed, stopped, running, pending}`；`success` 是既成事实、`skipped` 含人工跳过与 guard 判假（重跑等于否定那次干预），都**不得**复位 |
| FR-INT-05 | `retry(procInstId)` **必须**是默认入口（整轮重试）：① 先做资格前置判定（闸门翻不动就**一行都不碰**，避免把已完成流程里历史残留的 stopped/failed 行翻成 created 却没人跑）；② 扫整棵实例树逐条 CAS 复位 action 与节点行；③ **闸门最后翻**回 `pending`。返回值＝是否真排进队列；一行都没复位也照样翻闸门再驱动（定义装载失败/before 否决那条路实例行还全是 created） |
| FR-INT-06 | `retry` **必须**可重复调用（无事务下的自愈设计）：中途异常时流程还不是 pending，调度器捞不到，不存在"带着一半复位就开跑"；残留半截状态只是"没排进队"，再点一次即补完 |
| FR-INT-07 | `retryAction(actionInstId)` **必须**保留为定点重跑的细粒度入口；`skipAction` **必须**把该行置 `skipped` + `__skipReason=MANUAL_SKIP` 并重新入队 |
| FR-INT-08 | 复位**必须**走 CAS 而非裸置状态：流程仍 running（老 owner 还活着）时，它刚写的终态不该被干预一脚踩掉，抢不过就让它留着 |
| FR-INT-09 | 复位**不得**动 `result`：业务结果要留着给重跑与排障参考，上一轮痕迹由驱动侧在本轮收尾摘（干预不越俎代庖） |
| FR-INT-10 | 作者文档**必须**提示：流程仍在 running 且 owner 还活着时重试存在双驱动窗口，稳妥做法是先 `stop` 再 `retry` |

### 5.19 回退与补偿（Saga 向后恢复）

| 编号 | 需求 |
| --- | --- |
| FR-RBK-01 | 回退**必须**逆序补偿"已成功且 `compensable()` 为真"的 action（逆序依据：实例行 `auto_id` 降序＝声明/执行逆序） |
| FR-RBK-02 | 补偿**不得**改写原 `action_inst.status`（它是不可变的执行事实）；**必须**每个补偿步骤单独写一行 `rollback_record`，状态机 `compensating → compensated | compensate_failed | blocked` |
| FR-RBK-03 | 遇不可补偿步骤**必须**停下并记 `blocked` + 告警；补偿自身失败**必须**记 `compensate_failed` + 告警且**不无限重试**（当场 break） |
| FR-RBK-04 | **已成功补偿过的 action 不得重复撤销**（判定：该 action 最新一条 `rollback_record` 是否 `compensated`）。理由：补过再补一次（二次删除/二次回滚别人资源）通常比漏补更糟；`compensate_failed`/`blocked` 仍欠着，下次回退必须再试 |
| FR-RBK-05 | 回退路径**必须**有自己的一对切点（`rollbackListeners`）：补偿不走顶层走查，因此 `setup/setdown` 与流程/节点/action 三类监听器在回退时**一个也不触发** |
| FR-RBK-06 | 前置监听器产出的现场信息（连接串/凭据/拓扑）**必须**只进流程级内存 result 桶（`ctx.putResult`）：回退路径**不得**脏刷 `process_inst.result`，凭据不入库；要跨回退保留则用 `ctx.putVar`（回退收尾会刷 vars）。compensate 里 `ctx.resolveResult("dbConn.host")` 或 config 写 `${result.dbConn.host}` 都读得到 |
| FR-RBK-07 | 补偿前**必须**对 action config 跑一次 ACTIVATION 解析（与驱动路径同口径），否则 `${result.*}` 与运行期新写入的 vars 在补偿路径永远是 PLAN 冻结后的原文。上下文重建与 compensate **必须**在同一个 try 内（payload 重建可能因拓扑变更抛错，否则占位行永远挂在 compensating、异常还穿透调用方） |
| FR-RBK-08 | 回退路径的 `NodeContext.definition()/nodeRef()` **必须**允许为 null（无走查对齐，行直接重建）；依赖定义的 resolver 必须做退化处理 |
| FR-RBK-09 | 回退报告**必须**含 `compensated/blocked/failed/skipped/veto` 与聚合 `outcome()`：`failed`（有补偿失败或被否决）> `stopped`（遇不可补偿）> `success` |
| FR-RBK-10 | `autoRollback: true` 时**必须**在驱动收尾**落库终态并发完流程 after 之后**调一次回退（operator 固定 `auto`，与人工回退在补偿历史里分得开）；判定的是**收尾后库上那个状态**，所以人工 stop 抢先翻成 stopped 同样触发；`paused` 不触发（暂停可恢复，不该顺手拆掉副作用），`success/skipped` 不触发；回退失败只记日志、不改调用方拿到的终态 |
| FR-RBK-11 | 回退**必须**只在流程级声明（撤销的是整条流程的副作用，单个节点无权决定）；未声明/`false`＝只有人工入口 |
| FR-RBK-12 | 无可补偿项时**必须**空转返回，**不得**拉前置监听器（不白查一次连接信息） |

### 5.20 进度与视图

| 编号 | 需求 |
| --- | --- |
| FR-PRG-01 | 进度视图**必须**是扁平口径：`procInstId/total/done/percent/counts/items`；`done = success + skipped`；`counts` **必须**含全部状态键（初值 0），按已物化 action 行统计 |
| FR-PRG-02 | **必须**另给命名容器分组视图 `groups`：每个命名容器一项（标题/状态/自己的 total·done·percent/明细），支撑"一个校验项一行"的前端展示 |
| FR-PRG-03 | 命名容器的判据**必须**是行上 `node_def_id` 非空（GROUP 恒有；并行只有带命名后缀的才有），**不得**逐个 type 硬编码分支——新增容器形态不必回来加分支，也不再解 config 里的自述键 |
| FR-PRG-04 | 标题取值顺序**必须**是：`name`（展示名）> `node_def_id`（定义 id）> 业务键；明细**必须**带 `nodeInstId/nodeName/action/actionName/status/shardKey/skipReason/error`，让调用方可自行归组 |
| FR-PRG-05 | 视图**必须**直接读实例行（不回查定义）——所以物化期**必须**把 `node_def_id/name`、`action_def_id/name` 烙成展示用快照列 |
| FR-PRG-06 | 作者文档**必须**写明口径：EAGER 下节点级分母创建即稳定；lane/action 一律驱动期建，故 `total/done` 随驱动增长，是"已物化部分"的口径 |

### 5.21 对外 API

| 编号 | 需求 |
| --- | --- |
| FR-API-01 | 对外**必须**只有一个统一门面（`ProcessFacade`），组合 Plan/Execution/Intervention/Progress/Rollback；所有业务键为字符串（**物理主键 `auto_id` 不外露**） |
| FR-API-02 | 返回口径**必须**分三类：只创建（`create`）返 `procInstId`；带执行（`createAndStart`/`start`）返 `ProcessExecution`（实例 id + 调用结束时状态 + 坏终态时那一条失败摘要）；干预类返 `boolean`，回退返报告，进度返 Map |
| FR-API-03 | 执行类入口**不得**返回持久化实体行（`auto_id` 外露、裸 JSON 文本需自解、对象可变易被外部改写）；**必须**用不可变值对象 |
| FR-API-04 | 状态口径**必须**明确"不承诺是终态"：同线程 inline 驱动下通常是终态；未接手（行不存在/非 pending/被抢先）＝当下读到的样子；被人工抢先翻走时以 DB 为真值；`status=null` 唯一含义＝查不到这行 |
| FR-API-05 | 门面**必须**提供链式语法糖 `run(defId).version(v).user(u).param(k,v).on(unit,zone).create()/start()`，且**必须**只是攒参数末尾转调既有方法——引擎侧签名与语义一律不动 |
| FR-API-06 | 语法糖三条拍板口径**必须**保持：① 一个参数都没填时传 `null`（与不走糖时落库文本一字不差）；② 键名不做点号展开（`param("db.host",v)` 就是平铺键，`${config.db.host}` 本来就能下钻）；③ 同名键后写覆盖先写、值可显式为 null（照旧进桶不静默丢） |
| FR-API-07 | REST 入口**必须**与门面一一对应（创建/启动/pause/resume/stop/rollback/retry/skip/progress），且**必须**不引入引擎没有的语义；`operator` 走 query 参数（默认 `system`） |
| FR-API-08 | 引擎**必须**不管用户会话：`userId`/`operator` 由调用方取好后显式传入；`null`＝不记创建人/不刷审计列 |

### 5.22 调度与分布式

| 编号 | 需求 |
| --- | --- |
| FR-SCH-01 | 调度器**必须**是"轮询 pending → CAS 认领 → 交并行池驱动整棵树"的模型；认领批量受池剩余容量约束（`min(claimBatch, freeSlots)`），**不得超发**，要给流程内并行留容量；DB 队列当缓冲 |
| FR-SCH-02 | **必须**采用单 owner 驱动整流程（节点内并行/lane 仍用 owner 自己的池铺开）；取舍**必须**在文档写明：owner 进程崩溃则流程僵在 running，需人工干预翻回 pending（心跳/lease 属后续扩展） |
| FR-SCH-03 | 路由模式**必须**向后兼容全局模式：worker 未声明 `unit`＝捞全部 pending（不看调度表，单机/存量部署零影响）；配了 `unit`＝只主动认领调度表里命中本能力（`unit` 必匹配，`zone` 流程为 NULL 时同单元任意 zone 可认领）的一批。认领闸门仍在流程表（单行 CAS 保原子），调度表只承载路由维度 |
| FR-SCH-04 | 无人值守认领**必须**不刷 `updator_id`（传 null 保留原值），驱动期再按流程创建人刷 |
| FR-SCH-05 | node/lane/action 级认领（跨机并行）的 mapper 语句**必须**作为备用路径保留但默认不启用；`activate`（`created → pending`）等语义快捷**不得**被驱动主路径调用 |
| FR-SCH-06 | 调度器**必须**可完全关闭（`dispatcherEnabled=false` 时只打一条日志不启线程），且**必须**以 `SmartInitializingSingleton` 在所有单例就绪后启动；线程为守护线程 |

---

## 6 数据模型需求

### 6.1 通用约定

| 编号 | 需求 |
| --- | --- |
| DM-GEN-01 | 每张表**必须**有 `auto_id BIGINT AUTO_INCREMENT PRIMARY KEY` 作为**物理主键**，应用不直接使用、不外露；`node_inst`/`action_inst` 的**声明顺序由 `auto_id` 升序承载**（这是"行 ↔ 表达式按下标对齐"的物理基础） |
| DM-GEN-02 | 原业务 id 列（`*_id`）**必须**是字符串唯一键（列宽 ≥20；现状 DDL 为 `VARCHAR(32)`），由应用生成并降为唯一索引；表间关联一律用业务键而不用 `auto_id` |
| DM-GEN-03 | 审计列**必须**每表统一：`creator_id`/`updator_id`（用户身份，与业务键同宽）。`creator_id`＝建行时写流程创建人；`updator_id`＝每次 UPDATE 由调用方从上下文显式下传（驱动期＝流程创建人，干预期＝操作人），**传 null 则不刷该列**（无人值守认领场景） |
| DM-GEN-04 | 时间列**必须**由 DB 默认维护（`created_at DEFAULT CURRENT_TIMESTAMP`、`updated_at ... ON UPDATE CURRENT_TIMESTAMP`） |
| DM-GEN-05 | `config`/`result`/`vars`/`definition`/`detail` **必须**以 JSON **文本**存储（`MEDIUMTEXT`），**不得**用 DB 原生 JSON 类型；应用层用 Jackson 解析为 Map/List |
| DM-GEN-06 | 枚举持久化**必须**全仓统一：Java 常量名大写只是标识，落库/比较/SQL 字面量一律小写 `getStatus()`；反查用 `of(String)`（大小写不敏感）；MyBatis 入参绑枚举本身、SQL 里经 `#{x.status}` 取小写，**不注册 TypeHandler**。适用 `Status`/`DefinitionStatus`/`NodeType`/`ErrorPolicy`/`RollbackStatus`/`Materialization` |
| DM-GEN-07 | 实例表**必须**只存运行期数据。可由定义（版本钉死、不可变）推导的信息**不得**落实例（监听器链、子层形状、`actionExecution` 等）。**唯一例外**：展示用快照 `node_def_id`/`name`、`action_def_id`/`name`——为让进度视图直接读列，物化期烙一次，与回查定义同源不漂移 |
| DM-GEN-08 | 各层落库的配置桶**必须**统一叫 `config`（流程层那份＝创建输入）；**不得**建 `params` 列 |
| DM-GEN-09 | 失败/跳过信息**不得**建独立列，一律写进 `result` 保留键（`__error`/`__skipReason`/`__unresolved`）；`process_inst.result` 只记本次驱动的**首条**摘要（坏终态才写，成功会摘掉残留） |

### 6.2 表清单与关键列

**① 定义表**（一表多版本，定义不可变）

| 列 | 约束/语义 |
| --- | --- |
| `process_def_id` + `version` | 联合唯一；`version` 由发布方分配（引擎不自动生成） |
| `name` | 必填（不再从 YAML 解析） |
| `definition` | MEDIUMTEXT，整棵 YAML 树 |
| `status` | 发布态 `draft/published/archived`；索引 `(process_def_id, status, version)` 支撑"取最新已发布" |

- 生命周期：`draft`（可改，不被"最新已发布"命中）→ `published`（缺省查询口径，同 defId 多版共存取 version 最大）→ `archived`（下线，不再被命中）。
- 引擎**只有** `find`（version 非 null 精确；否则按 status 取最新，status 也 null 回落 `published`）与 `insert` 两条语句：**不得**有 UPDATE——定义的不可变性由"只 find/insert"保证，也让定义缓存天然不需要失效入口。

**② 流程实例表**

| 列 | 语义 |
| --- | --- |
| `process_def_id` + `def_version` | 钉死版本 |
| `status` | **闸门 + 认领**双重职责：`pending` 可被认领，`running` 表示有 owner 在驱动；索引 `(status, auto_id)` |
| `config` | 创建输入（流程级配置桶） |
| `vars` | 运行期变量空间（含初值合并与运行期写入） |
| `result` | 流程级 result 桶（驱动路径脏刷；回退前置挂的现场信息不刷；坏终态含 `__error`） |

**③ 节点实例表**

| 列 | 语义 |
| --- | --- |
| `node_def_id` / `name` | 展示用定义快照；匿名 PARALLEL/IF 为 NULL |
| `type` | `parallel/if/group/action`（控制/叶子由 type 区分） |
| `parent_id` | 父节点业务键，顶层根为 NULL；索引 `(parent_id, status, auto_id)` 支撑按声明顺序取子行 |
| `status` | 见 FR-STA-03（唯一写 `running` 的层） |
| `pending` | join 计数（**预留**：节点级认领路径用，当前无调用方读写） |
| `error_policy` | **生效值**（物化期行级冻结）：节点声明 > 流程级全局 > 默认 `fail_fast`；默认值取枚举 `DEFAULT.getStatus()` |
| `shard_enabled` / `shard_resolver` | 分片开关与 resolver 名（行上自取，驱动期不再回查定义） |
| `condition_expr` | IF 条件 **或** 节点/容器级 guard（两者复用同一列） |
| `config` | 业务配置（PLAN 冻结）+ `__chosen`（IF 决策） |
| `result` | 本层桶：节点=自己显式写入的值（GROUP 行=使用者显式挂进组桶的共享值），引擎不自动上下复制 |

**④ 泳道表**：`lane_id`、`proc_inst_id`、`node_inst_id`、`shard_key`（唯一键 `(node_inst_id, shard_key)`）、`status`、`error_policy`（继承节点行）、`config`、`result`。

**⑤ action 实例表**

| 列 | 语义 |
| --- | --- |
| `node_inst_id` / `lane_id` | 容器归属；`lane_id` NULL＝非分片；索引 `(lane_id, node_inst_id, status, auto_id)` |
| `action_def_id` | 声明 id（无 id 时取 handler 名），与 `ActionDef` 按下标对齐的自然键之一 |
| `handler_name` | `ActionHandler.name()` |
| `shard_key` | 冗余便于查询 |
| `when_expr` | 声明式 guard |
| `visit_count` | 重跑计数：**落 result 时同句 ++**（幂等观测） |
| `config` / `result` | PLAN 冻结的配置 / 业务结果 + 保留键 |

**⑥ 回退记录表**：`rollback_id`、`proc_inst_id`、`action_inst_id`（关联原实例）、`action_def_id`、`status`、`operator`、`detail`（JSON：reason/error）。索引 `(proc_inst_id, auto_id)`、`(action_inst_id)`；`auto_id` 升序＝补偿执行顺序。

**⑦ 调度表**：`proc_inst_id`（唯一，一对一）、`sched_unit`（必填，路由过滤维度）、`sched_zone`（可空）。闸门仍在流程表，本表只承载路由维度。

**⑧ fragment 表**（预留）：只有 DDL，无 mapper、无 DSL 展开机制。

### 6.3 Mapper 层需求

| 编号 | 需求 |
| --- | --- |
| DM-MAP-01 | 每张实例表的写**必须**收敛为**一条动态 `updateState`**（只写传入的非 null 列），语义入口（`casStatus`/`setStatus`/`markRunning`/`updateStatusResult`/`updateConfig`/`activate`/`updateVars`/`claim`）**必须**是 `default` 快捷而不产生新 SQL |
| DM-MAP-02 | 查询同理合并：`findRoots`/`findChildren` → `findByParent(procInstId, parentId)`（parentId=null 即根）；`findInContainer`/`findByContainerAndDef` → 一个可空参数的 `findInContainer`；`findByProcess`/`findLatestByAction` → 一个 `find` |
| DM-MAP-03 | 幂等物化**必须**用 `INSERT ... INSERT IGNORE`（命中唯一键即忽略），保证崩溃后重放不重复插 |
| DM-MAP-04 | **NULL 参与唯一键不去重**：`uk_node_def_shard(node_inst_id, action_def_id, shard_key)` 在非分片（`shard_key IS NULL`）时不能靠 `INSERT IGNORE` 去重，**必须**先按自然键查、存在则复用（取声明顺序最早一条），否则会产生重复行 |
| DM-MAP-05 | SQL **必须**全部集中在 mapper XML（`META-INF/mapper/process/*.xml`），带显式 `resultMap` 列映射；Java 侧不拼 SQL |
| DM-MAP-06 | 表名**必须**写成 `${orch.table.*}` 变量（装配期按 `tablePrefix` 一次性文本替换）；变量**必须**显式全部 put（含空串前缀）——PropertyParser 对解不到的占位符原样留在 SQL 里＝语法错误 |

---

## 7 配置项需求

| 编号 | 键 / 入口 | 默认 | 需求 |
| --- | --- | --- | --- |
| CF-01 | `ities.process.node.core-size / max-size / queue-capacity / keep-alive-seconds` | 16 / 16 / 20000 / 600 | node 扇出池 sizing；**必须**四参数各自独立可配，不得用一组数派生 |
| CF-02 | `ities.process.shard.*` | 同上 | shard lane 扇出池 sizing |
| CF-03 | `ities.process.action.*` | 同上 | action 扇出池 sizing |
| CF-04 | `ities.process.dispatcher.unit` / `.zone` | 空（＝全局模式） | 本 worker 能力；空白归一为 null |
| CF-05 | `tablePrefix`（配置类 setter） | `process_` | 8 张表一律随前缀；`""`＝裸表名逃逸 |
| CF-06 | `dispatcherEnabled` / `dispatcherPollIntervalMs` / `dispatcherClaimBatch`（配置类 setter） | true / 500ms / 16 | 调度开关、轮询间隔、单轮认领上限 |
| CF-07 | `dataSource`（配置类 setter） | null＝取上下文唯一/`@Primary` | 多源歧义时显式指定 |
| CF-08 | 宿主**必须**（或可省略）提供 5 个 `StringSequenceGenerator` Bean，按 **Bean 名**注入：`processInstSequenceGenerator`、`nodeInstSequenceGenerator`、`laneSequenceGenerator`、`actionInstSequenceGenerator`、`rollbackRecordSequenceGenerator` | 缺则回落本地时间戳序列 | 引擎**不得**创建这些 Bean，也**不得**要求它们存在；缺失只打一条 WARN（列出还缺哪些名字）并继续启动 |
| CF-09 | Bean 名口径**必须**只在一处定义（`IdKind` 枚举的 5 行声明）；换名只改枚举或调 `setSequence(kind, generator)`，调用点一律不动 | — | 取号一律带种类：`ids.next(IdKind.NODE_INST)` |
| CF-10 | 消费者**必须**可在同上下文声明同名 Bean 覆盖任一默认线程池 | — | 覆盖不需改引擎 |
| CF-11 | 每档 sizing 的 `@Value` 默认值**必须**同时保留 Java 字段初始值，以兼容"非 Spring 直接 new 配置类"（单测/手工装配） | — | 两处数值必须一致 |

---

## 8 异常与文案需求

| 编号 | 需求 |
| --- | --- |
| EX-01 | 引擎侧抛出的异常**必须**统一为一个运行时异常类型，携带 `messageKey` + `args`（可变参数），**不得**在抛出点拼句子 |
| EX-02 | 消息键区间 `AU204001 ~ AU204999` 由引擎占用，分段固定：`2040xx` 上下文、`2041xx` flow DSL 解析、`2042xx` 装载与聚合展开、`2043xx` 物化与驱动、`2044xx` 计划与定义缓存、`2045xx` 条件求值、`2046xx` SPI 注册表、`2047xx` JSON、`2048xx` 业务键序列（全表见附录 B） |
| EX-03 | 文案**必须**由 classpath 上 `i18n/orchestrator-messages.properties`（UTF-8，默认语种即简体中文，不另建 `_zh_CN` 副本）由异常类**自己懒加载**：不声明 Bean、不经 Spring——因为抛出点散布在非 Spring 管理的工具类（DSL 解析器、JSON 工具、可直接 new 的加载器） |
| EX-04 | 取文案这件事**必须**绝不抛异常：键缺失/bundle 读不到/pattern 写坏，一律降级为 `键: 参数1 参数2`（参数不静默丢） |
| EX-05 | 消息**必须**在构造时刻解析一次并存进 `getMessage()`：日志与落库摘要不受后续 Locale 切换影响 |
| EX-06 | 参数**必须**先一律转字符串再进 `MessageFormat`（否则 id/数量被 locale 加成千分位）；文案里**不得**出现单引号（`MessageFormat` 把 `'` 当转义符） |
| EX-07 | `toString()` **必须**形如 `全类名[AU204302]: 文案`——异常类型统一后类名不再区分错误种类，全靠键查文案与按码统计 |
| EX-08 | 宿主**必须**可整体替换文案源（`setResolver((key,args)->文本)`，返回 null 回落内置）；宿主已有同名异常类时，引擎那份**可以整份删除**，只要有这两个构造签名：`(String key, Object... args)`、`(String key, Throwable cause, Object... args)`，并满足 EX-07 |
| EX-09 | 业务键取号**必须**当场拦截空值与超长（`AU204801`/`AU204802`，上限 20）：空业务键会落成一条查不到的死行，超长顶唯一索引列宽——不留到 SQL 报模糊错 |

---

## 9 非功能需求

| 编号 | 需求 |
| --- | --- |
| NFR-01 | **幂等与可重入**：同一流程可被反复驱动而不重复执行已完成项（节点终态幂等返回、action 非 `created` 跳过、lane/action find-or-create、`INSERT IGNORE`）。这是重试/崩溃恢复/双驱动窗口的共同地基 |
| NFR-02 | **崩溃恢复语义**：半物化的实例行必须由走查按需补齐（LAZY 缺行补建、EAGER 只定位对齐），恢复不重算已完成项、不复算 IF 决策（读 `__chosen`） |
| NFR-03 | **无事务可接受**：所有多语句写入路径**必须**设计成"可重复调用 + 失败方向安全"（见 FR-INT-05/06、FR-CRT-06），不得依赖回滚 |
| NFR-04 | **多节点一致**：任何跨节点可见的判定**必须**读 DB（闸门、认领、状态），本机不持有可漂移的权威状态 |
| NFR-05 | **定义不可污染**：定义对象以 JSON 快照承载、每次借出私有副本；实例侧对定义**只读** |
| NFR-06 | **可观测**：关键节点必须有一行式结构化日志——认领（proc + user）、节点/ action 异常（带定位）、未解析占位符（带键列表）、回退触发与结束（带计数）、状态抢先翻走（聚合 vs 实际）、Dispatcher 启停与参数。失败因由除日志外**必须**落库可读（FR-ERR） |
| NFR-07 | **可诊断**：只读流程行/进度接口就能定位"为什么坏了、坏在哪一层的哪一片"（`__error` 带语境与定位，progress 带 nodeName/actionName/shardKey） |
| NFR-08 | **性能量级**：定义借出为一次 Jackson 反序列化（典型亚毫秒）；取消闸门每 action/lane/子节点一次主键 `findById`（这些路径本就读写同一行，不引入额外往返）；单流程节点数量级到千、action 数量级到万时按下标对齐的查询仍走索引 |
| NFR-09 | **资源**：线程池三档默认 16/16 + 2 万队列；关停**必须**等待在跑任务完成（30s 上限）；Dispatcher 线程为守护线程 |
| NFR-10 | **兼容性**：存量库若仍有枚举大写行（如 `FAIL_FAST`/`COMPENSATING`），`of()` 大小写不敏感可读出，但认领/比较的 SQL 字面量是小写——迁移**必须**提供 `LOWER()` 刷数据脚本 |
| NFR-TEST-01 | 测试**必须**能在不起 Spring 容器、不依赖 DB 的前提下驱动端到端行为：用内存替身表（Mockito）+ 构造器逐个 new 装配夹具复现真实依赖图；mapper XML 的 SQL 级回归单独用内存 DB |
| NFR-TEST-02 | 构建**必须**在"陈旧 class"下不可信：CI/本地验证**必须**以 `clean test` 为准（陈旧 test-classes 既会假绿灯，也会跑出已删除的测试名） |
| NFR-DOC-01 | 文档与示例 YAML 的每个属性、每个占位符写法、每条错误码**必须**能在代码里找到对应实现或被明确标注为预留；发现不一致**必须**记入 `GAP-*` 而不是静默保留 |

---

## 10 范围外与现状偏差

### 10.1 明确预留（不实现，但保留扩展位）

| 编号 | 项 | 现状 |
| --- | --- | --- |
| OUT-01 | `process_fragment` 表 / DSL 的 `include`/`ref` 片段展开 | 只有 DDL；无 mapper、无展开机制 |
| OUT-02 | `WHILE` 循环节点 | 枚举里有值，无策略、无物化器（需 `visit_count` + 迭代物化） |
| OUT-03 | `SUSPENDED` 状态（等待外部信号/定时） | 枚举里有值，驱动路径不产生、不识别 |
| OUT-04 | `node_inst.pending`（join 计数） | 列存在，无读写方 |
| OUT-05 | node/lane/action 级跨机认领 | mapper 语句保留，默认不启用 |
| OUT-06 | owner 心跳 / lease 抢占回收 | 未实现（崩溃后靠人工 `retry`/`stop` 翻闸门） |
| OUT-07 | `IF` 就近声明策略（命名后缀） | 语法位已被 `AU204110` 占住，待"纯容器声明"口径统一后再开 |
| OUT-08 | 定义发布/校验工具链（版本递增、draft→published、发布期严格校验档） | 引擎只提供 `find`/`insert`，由宿主落库 |

### 10.2 现状偏差（生成代码时**必须**按"目标"列处置，不得静默复制缺陷）

| 编号 | 现象 | 目标处置 |
| --- | --- | --- |
| GAP-01 | `Dispatcher` 的 `@Bean` 处于注释状态：装配里不产生调度器，**主动调度默认不生效**，只有同步 `start` 与 `resume/retry` 后的"重新入队"没人捞 | 要么恢复 `@Bean` 装配（受 `dispatcherEnabled` 控制），要么在接入文档显著声明"宿主须自行注册调度器 Bean"。二者选一，不得两头都不做 |
| GAP-02 | 门面注释承诺"回退后重试会先把被撤销的副作用**向前重放**（`RestoreService`）"，但该类不存在；`rollback_record.status` 注释含 `restoring/restored/restore_failed`，枚举无对应值 | 要么落地重放组件（撤销历史三元组 + 驱动前触发），要么删除注释承诺并把枚举值范围收回到实际四态 |
| GAP-03 | 示例 `db-migration.yml` 用 `${lane.shardKey}` 取分片键，而引擎不解析该路径（保留前缀＋非桶路径）→ 落 `__unresolved` | 示例改为 handler 内 `ctx.shardKey()`；或在解析器补 `lane.shardKey`/`action.shardKey` 一档取值（四处扩展点：RESERVED、LEVELS 分支、findUnresolved、文档） |
| GAP-04 | `ProcessDefinitionMapper` 注释称"枚举按 `name()` 大写绑定、库里是 `DRAFT/PUBLISHED/ARCHIVED`"，与 XML 的 `#{status.status}`（小写）及 DDL 注释冲突 | 以 DM-GEN-06（小写）为准，修正注释 |
| GAP-05 | `NodeDef.condition` 被解析并落 `condition_expr` 列的通道留给 `when`，`condition` 无任何消费点 | 要么删字段，要么明确其语义；当前**必须**在作者文档标注"写 `condition` 不生效，请用 `when`" |
| GAP-06 | "未知键即拒"未实现：`load()` 对未识别的顶层段、节点属性、action 属性一律静默忽略（只有 materialization/autoRollback/命名容器成对/引用完整性/纯容器冲突五处会拒） | 增加发布期严格档：白名单校验所有已知键，未识别即拒（错误码续 `AU2042xx`）；按 FR-LOAD-09 落点分档，不得塞进运行期 `load()` |
| GAP-07 | lane 级桶不持久化：`lane.result`/`lane.config` 列从不写入，分片内 `ctx.upward().putResult` 只在本次内存有效，崩溃重驱动即丢 | 要么补 lane 收尾脏写 `result`，要么在文档规定"分片内跨 action 交接走流程级 `vars` 或业务专表"。禁止保留"看起来能持久、实际不能"的中间态 |
| GAP-08 | 节点级/泳道级桶是普通 `LinkedHashMap`：`laneExecution: PARALLEL` 时多个 lane 线程可并发写同一 `NodeContext.result`，收尾快照与写入无同步保护（只有 `ProcessContext` 加了锁） | 明确分层并发契约：要么容器桶写入纳入同步（与流程级同口径），要么硬性规定"并行 lane 禁止写节点桶，只写流程级 vars/各自 action 行"，并在作者文档给出 |
| GAP-09 | `EAGER` + `FAIL_FAST` 下被 `cancel(false)` 的子孙行已预建，会永远停在 `created`（孤儿行），不影响终态但污染进度分母 | 保留现状但写明口径：`total/done` 以 action 行为分母，节点层孤儿行不算未完成；后续可对收敛序列补 drain 置 `stopped` |
| GAP-10 | 业务键列宽三处口径不一：DDL `VARCHAR(32)`、门面注释"varchar(20)"、应用侧硬上限 20 | 统一为"应用侧上限 20，列宽 ≥20（保持 32 有余量即可）"，注释与 DDL 对齐 |
| GAP-11 | `@ComponentScan` 含不存在的包 `com.tesla.ities.process.executor` | 删除残留包名，或补齐该包的装配职责 |
| GAP-12 | 装配注释仍写"只扫 `com.orchestrator.mapper`"、"SQL 在 `mapper/orchestrator/*.xml`"、"7 张表"，实际是 `com.tesla.ities.process.mapper`、`META-INF/mapper/process/*.xml`、**8 张表**（含调度表） | 按 DM/CF 章口径刷新注释；表数量口径统一为"7 张业务表 + 1 张调度表 = 8 张，一律随 `tablePrefix`" |
| GAP-13 | 各 Mapper 接口注释统一指向 `resources/mapper/orchestrator/*.xml`（旧路径），实际文件在 `resources/META-INF/mapper/process/` | 修正注释路径，避免排障时找不到 SQL |

---

## 11 验收标准与测试矩阵

### 11.1 端到端必须通过的行为（AC）

| 编号 | 场景 | 通过判据 |
| --- | --- | --- |
| AC-01 | 正常路径：`setup → 串行根 → PARALLEL → IF 选支 → setdown` | 流程 `success`；实例树形状与 `orderedRoots()` 前序展开一致；`setdown` 无论前后成败都执行；每层 `result` 只含本层显式写入的键 |
| AC-02 | 命名并行分组 | `PARALLEL:checks(a,b)` 产生一行容器（`node_def_id=checks`），`progress.groups` 里出现该项，标题取 `name`；匿名并行不出现在 `groups` |
| AC-03 | 聚合节点组内交接 | 子步骤 `ctx.node().parent().putResult` 写组桶 → 后续子节点 `${result.k}` 读到；组桶随组节点行落库，重驱动后仍在 |
| AC-04 | 分片 | `resolveShardKeys` 返回 N 个键 → N 条 lane；`laneExecution: PARALLEL` 时 lane 扇出；resolver 返回空集合 → 节点 `success` 且无 lane |
| AC-05 | guard 三处 | 节点 `when` 假→节点 `skipped`；命名容器 `when` 假→容器 `skipped` 且**子项一个都不物化**；action `when` 假→该 action `skipped` + `__skipReason=GUARD_FALSE` |
| AC-06 | 全局错误策略回落 | 顶层 `CONTINUE_ON_ERROR` 时未声明的节点逐项跑完；节点自己声明 `FAIL_FAST` 时只影响它自己那层；命名容器声明影响上层序列收敛 |
| AC-07 | 就近声明优先 | 全局 `CONTINUE_ON_ERROR` 下某个关键根声明 `FAIL_FAST` → 该根坏后跳过剩余 `flow` 根，但 `setdown` 仍执行；全局 `FAIL_FAST` 下锦上添花项声明 `CONTINUE_ON_ERROR` → 失败不拦后面 |
| AC-08 | 失败记录 | handler 抛异常 → action 行 `failed` + `__error` 带语境与定位；流程行 `result.__error` 只有一条（首个胜出）；重试成功后 `__error` 被摘掉 |
| AC-09 | 回退 | 逆序补偿 `compensable()` 为真的成功 action；遇不可补偿→`blocked` 并停；重复调用回退不二次撤销（`skipped`）；`rollback_record` 每步一行且不改原 action 状态 |
| AC-10 | 自动回退 | `autoRollback: true` + 终态 `failed/stopped` → 收尾自动补一轮（operator=`auto`）；`paused` 不触发；回退异常不外抛、不改终态 |
| AC-11 | 人工干预 | `stop` 后在跑的 drain（未发的 action 置 `stopped` + `DRAIN`）；`pause`→`resume` 重新入队；`retry` 复位"没跑成的行"并从头走查（已完成项跳过）；已 `success` 的流程 `retry` 返 `false` 且不动任何行 |
| AC-12 | 抢先翻状态 | 收尾时人工已把流程翻 `paused/stopped` → 引擎 CAS 失败，**返回并重读 DB 的实际状态**，不覆盖人工意图 |
| AC-13 | 定义装载失败 | 定义版本缺失/解析失败 → 流程落 `failed` 且 `result.__error` 有一句因由，不僵在 `running` |
| AC-14 | 全局监听器链 | `globalNodeListeners`/`globalActionListeners` 对每个节点（含控制节点）、每个 action（含每 lane 每片）各触发一次，且顺序为"全局在前、本地在后" |
| AC-15 | 回退切点隔离 | 补偿路径不触发 `setup/setdown` 与三类监听器，只触发 `rollbackListeners`；前置否决→整轮不补且报告带 `veto` |
| AC-16 | Observer | 全局 Observer 在六切点各触发一次；其内部异常一律不影响流程 |
| AC-17 | 占位符语义 | PLAN 可解→烙字面量（此后改 vars 不影响该行）；PLAN 不可解→激活期现取；间接键指针无值→整条保留；指针有值而外层无值→回写**已插值**路径；自引用不递归 |
| AC-18 | 外来模板 | `${table}`、`${srcDb}` 之类原样透传给 handler，且不出现在 `__unresolved` |
| AC-19 | 进度口径 | EAGER 创建即有全部节点行（`created`）；`progress` 的 `total/done/percent/counts/groups/items` 齐全；`done=success+skipped` |
| AC-20 | 门面返回值 | `createAndStart` 返 `ProcessExecution`：`id` + 结束时状态 + 坏终态那一条摘要；成功路径不额外查库；未接手时状态＝当下读到的样子 |
| AC-21 | 语法糖等价性 | `run(defId).start()` 与 `createAndStart(defId, null, null, null)` 行为一致；一个参数都没填时落库 `config` 文本与不走糖一字不差 |
| AC-22 | 表命名空间 | 改 `tablePrefix` 后 8 张表整体换名可用；设 `""` 回裸表名可用 |

### 11.2 测试分层（现状套件 → 覆盖对象）

| 层 | 套件 | 覆盖 |
| --- | --- | --- |
| 单元 | DSL/装载：`DefinitionLoaderTest`、`GroupDefinitionTest`、`DefinitionCopyTest`（快照往返） | 5.2/5.4/5.5/5.6 |
| 单元 | 解析器：`VariableResolverTest`、`ConditionEvaluatorTest`、`ContextResolveTest` | 5.11/5.12/5.10 |
| 单元 | 引擎件：`DispatcherTest`、`CancellationRegistryTest`、`ObserverEngineTest`、`RegistryTest`、`EngineDefensiveTest`、`NamedParallelTest`、`ErrorsTest`、`EntityTest`、`MapperDefaultsTest`、`MiscUnitTest`、`GapCoverageTest`、`ProcessEngineConfigTest`、`ProcessExceptionTest` | 5.9/5.15/5.17/7/8 |
| 特性 | `GlobalErrorPolicyTest`、`GlobalListenerChainTest` | FR-LOAD-04/05/06、FR-LST |
| 端到端 | `EngineHappyPathTest`、`EngineGroupFlowTest`、`EngineNamedParallelTest`、`EngineErrorRecordTest`、`EngineErrorRollbackTest`、`EngineInterventionTest`（共用内存表夹具） | AC-01～AC-15 |
| 持久化 | `MapperBindingTest`（SQL 文本绑定）、`MapperCrudTest`（内存 DB 真 CRUD） | 第 6 章 |

**测试纪律**（与现状工程实践一致）：测试红灯先归因（区分存量红/新引入红）再动手；行为契约变更时**优先改测试以匹配新契约**，但生产语义与既有拍板决策冲突时以保留生产代码为准；断言必须与需求编号可对应。

---

## 12 代码生成指引

### 12.1 包结构与类清单（推荐布局）

```
com.tesla.ities.process
├─ api        ProcessFacade（唯一对外门面）· ProcessRequest（链式语法糖）· ProcessExecution（不可变执行结果）
├─ config     ProcessEngineConfig（装配入口：数据源/MyBatis/三池/调度器/表前缀）
├─ core
│  ├─ define  ProcessDefinition · NodeDef · ActionDef · ListenerDef · DslParser · DefinitionLoader
│  ├─ context Context（基类，三桶+父链）· ProcessContext · NodeContext · LaneContext · ActionContext
│  ├─ engine  ExecutionEngine · NodeDriver · Materializer + ExprMaterializer{Leaf,Parallel,If,Group} ·
│  │          NodeStrategy{Action,Parallel,If,Group} · Dispatcher · DriveContext · ListenerEngine ·
│  │          ObserverEngine · PlanService · InterventionService · RollbackService · ProgressService
│  ├─ model   Status · NodeType · ErrorPolicy · DefinitionStatus · RollbackStatus · Scope
│  ├─ runtime VariableResolver · ConditionEvaluator · ContextFactory · DefinitionCache · CancellationRegistry
│  └─ spi     ActionHandler · ShardResolver · ProcessListener · NodeListener · ActionListener ·
│             RollbackListener · ProcessObserver ·（以上各一个 *Registry）
├─ domain     7 个实体（一行一表；transient 字段只挂"走查期"派生数据）
├─ exception  ProcessException（messageKey + args，自懒加载 bundle）
├─ mapper     7 个接口（一表一接口）
├─ util       IdGenerator · IdKind · Json · Errors
└─ web        OrchestratorController（可选 REST，不在扫描范围）
```

### 12.2 职责矩阵（生成时不得越界）

| 组件 | 只做 | 禁止 |
| --- | --- | --- |
| `DslParser` | 文本 ↔ 表达式树、往返写回、语法级拒绝 | 读定义、碰 DB |
| `DefinitionLoader` | YAML → 定义模型、聚合展开并重写 flow、命名成对校验 | 运行期判定、写实例表 |
| `Materializer` 家族 | 表达式 → 实例行；按下标对齐并挂 transient 光标/定义/链 | 执行 handler、判策略 |
| `NodeStrategy` 家族 | 一种节点类型的"怎么跑子树"；返回聚合终态 | 直接写节点行状态（收尾归 NodeDriver） |
| `NodeDriver` | 节点/ action 序列驱动、guard、监听器、状态与 result 落库、流程级脏刷 | 递归回调引擎（避免环依赖） |
| `ExecutionEngine` | 认领、顶层走查、流程级监听器、收尾终态、自动回退触发 | 持有本机取消状态、跨流程缓存 |
| `*Service`（Plan/Intervention/Progress/Rollback） | 单向叶子：可被 engine/api 调用，**不**反向依赖引擎内部 | 被策略回调 |
| `*Registry` | `name()` 路由 + 装配期查重 | 惰性注册、运行期改路由 |
| Mapper/XML | SQL 与列映射 | Java 侧拼 SQL |

### 12.3 实现顺序（依赖驱动，可并行度标注）

1. `model`（枚举）→ `util`（`IdKind/IdGenerator/Json/Errors`）→ `exception`。
2. `define`（先 `DslParser` 纯函数 + 单测，再 `DefinitionLoader`）。
3. `domain` + `mapper` 接口 + XML + `schema.sql`（含表前缀变量），跑 `MapperBinding/MapperCrud`。
4. `context`（基类三桶与上溯）→ `runtime`（解析器 → 求值器 → 工厂 → 缓存 → 取消闸门）。
5. `engine`：`DriveContext` → `ExprMaterializer` 四个 → `Materializer` → `NodeStrategy` 四个 → `NodeDriver` → `ExecutionEngine` → `PlanService` → `InterventionService`/`RollbackService`/`ProgressService` → `Dispatcher`。
6. `spi` 接口 + `Registry`（可与 5 并行）。
7. `api` 门面 + 语法糖 → `config` → `web`。
8. 端到端夹具与 AC 套件。

### 12.4 编码约定（与现状工程一致，生成时**必须**遵守）

- **注释风格**：每个类头写"它为什么存在 / 与相邻方案的差别 / 被否决过的写法"，关键不变量写在紧邻的字段或方法注释上；注释是防漂移的第一道闸，禁止只写"做什么"。
- **枚举**：一律带 `getStatus()`（小写）+ 静态 `of(String)`（大小写不敏感、Locale 无关）；禁 `valueOf`。
- **派生视图**：所有非属性方法（`isXxx`/`xxx()` 形式的计算值）在可 JSON 往返的类上**必须** `@JsonIgnore`。
- **null 归一**：setter 一律把 null 归一成空容器（`vars/nodes/config`），不给下游留 NPE 种子；反之**读法**要区分"没声明"与"声明了但不合法"（`parseOrNull` vs `of`）。
- **语义快捷用 `default`**：mapper 的多条相似语句合并为一条动态 SQL，语义名靠 default 方法承载。
- **就近原则**：能写在声明处的策略不放进引擎保留字；引擎不认任何业务键名。
- **可读性优先**：流程编排 DSL 的形状（`PARALLEL:名(...)`、`GROUP:名(...)`）以人读为先；语法扩展必须先满足往返互逆。
- **一处真相**：默认策略、Bean 名、表名、错误码各自只在一处定义（`ErrorPolicy.DEFAULT`、`IdKind`、`buildTableVariables`、bundle 文件）。

---

## 附录 A 完整 DSL 示例（覆盖全部段）

```yaml
materialization: EAGER              # 创建期建全节点树（lane/action 仍驱动期建）；校验类流程用，分母稳定
errorPolicy: CONTINUE_ON_ERROR      # 流程级全局：没自己声明的节点（含匿名容器与其 lane）都用它
autoRollback: true                  # 驱动终态 failed/stopped 时收尾自动逆序补偿

vars:
  tolerance: 0.05
  dialect: mysql
  dialects:                         # 引擎不认这个键名，只是业务字典
    mysql:    { lockSql: "ALTER TABLE ${table} DISABLE KEYS" }
    goldendb: { lockSql: "SET TABLE ${table} READONLY" }

setup:                              # 前置：只接受叶子引用
  - detectDialect
setdown:                            # 收尾：finally 语义，无论成败都跑
  - cleanupLock

flow: >-
  PARALLEL:checks(spaceCheck,rowCountCheck),
  IF(${vars.dialect}=='goldendb',gdbVerify,mysqlVerify)

listeners:                          # 流程级（before 可否决；这里批量插校验项专表）
  - name: checkItemInitializer
    config: { itemTable: t_validate_item }
rollbackListeners:                  # 回退级（before 现取连接信息，只进内存桶）
  - name: dbConnFetcher
    config: { source: cmdb }
globalNodeListeners:                # 全局节点级（每个节点都走，含控制节点）
  - name: nodeAudit
globalActionListeners:              # 全局 action 级（每个 action、每片都走）
  - name: metricsCounter

nodes:
  checks:                           # 命名并行容器的声明：纯容器，只挂策略/guard/config
    name: 校验清单
    errorPolicy: FAIL_FAST
  detectDialect:
    name: 探测方言
    handler: dbDialectProbe         # 内部 ctx.putVar("dialect", ...)
  cleanupLock:
    handler: lockRelease
    config: { unlockSql: "${vars.dialects.${vars.dialect}.unlockSql}" }   # 间接键：指针未定则拖到激活期
  spaceCheck:
    name: 空间占用校验
    errorPolicy: FAIL_FAST
    when: "${vars.skipSpace} == false"        # 容器级 guard：假则整组 skipped，子节点不物化
    config: { itemTable: t_validate_space }
    group:
      flow: "fetchSource,diffPerShard"
      nodes:
        fetchSource:
          name: 取源端空间
          handler: spaceProbeSource # 显式交接：ctx.node().parent().putResult("fetchSource", map)
        diffPerShard:
          name: 全分片空间对比
          shard: targetTopology             # 分片轴＝要产出结论的一侧；resolver 返回空列表应主动抛异常
          laneExecution: PARALLEL
          actions:
            - id: diffSpace
              handler: spaceDiff
              config: { srcBytes: "${result.fetchSource.bytes}", tolerance: "${vars.tolerance}" }
              listeners: [ { name: checkItemRecorder } ]
  rowCountCheck:
    name: 行数校验
    shard: targetTopology
    actionExecution: PARALLEL       # 容器内 action 序列并行（默认 SERIAL）
    actions:
      - id: fetchRows
        handler: rowCountSource
      - id: countRows
        handler: rowCountDiff
        when: "${vars.needCount} == true"
  gdbVerify:  { handler: gdbVerify }
  mysqlVerify: { handler: mysqlVerify }
```

## 附录 B 错误码总表

| 键 | 语义 | 触发点 |
| --- | --- | --- |
| AU204001 | 非分片 action 没有分片 payload，无法取值 | `ActionContext.shardPayload(Class)` |
| AU204101 | flow DSL 末尾有多余字符 | `DslParser.parseFlow` |
| AU204102 | 控制节点缺少命名后缀（GROUP 无名） | 读容器名（必填处） |
| AU204103 | 聚合节点 `GROUP:名(...)` 至少一个子节点 | 解析 GROUP |
| AU204104 | 无法写回 flow DSL 的表达式类型 | `toFlow` |
| AU204105 | 期望一个节点引用（空 token） | `readToken` |
| AU204106 | 期望字符 `(`/`)` 失配 | `expect` |
| AU204107 | 节点引用与容器名不能含冒号 | `readToken` |
| AU204108 | 命名并行容器无子节点 | 解析 `PARALLEL:名()` |
| AU204109 | 关键字后冒号缺少命名后缀 | 读容器名 |
| AU204110 | 控制节点暂不支持命名后缀（IF） | 解析 IF |
| AU204201 | 非法 materialization | `load()` |
| AU204202 / 204203 | 聚合节点放在 setup / setdown 段 | `expandGroups` |
| AU204204 | 命名容器不是纯容器（带 handler/actions/shard/lane/actionExecution） | 展开与成对校验 |
| AU204205 | 聚合节点嵌套超过 8 层 | `buildGroup` |
| AU204206 / 204207 | 聚合节点缺 `group.flow` / `group.nodes` | `buildGroup` |
| AU204208 | 提升后的子节点键与既有键冲突 | `buildGroup` |
| AU204209 | 组内 flow 引用另一个顶层聚合节点 | `rebind` |
| AU204210 | 组内 flow 引用未定义节点 | `rebind` |
| AU204211 | 组内不支持的表达式类型 | `rebind` |
| AU204212 | 并行命名后缀在 nodes 下无声明 | `validateNamedParallels` |
| AU204213 | 容器形状的节点未被 flow 引用（死配置） | `validateNamedParallels` |
| AU204214 | 非法 autoRollback 声明值 | `declaredSwitch` |
| AU204301 | ACTION 节点行的走查表达式不是叶子 | `ActionStrategy` |
| AU204302 | flow 引用了未定义的节点 | 叶子物化 |
| AU204303 | GROUP 行的走查表达式不是聚合表达式 | `GroupStrategy` |
| AU204304 | 没有物化策略支持的表达式类型 | `Materializer` |
| AU204305 | 节点行缺少走查光标（未经物化对齐） | `NodeDriver.driveNode` |
| AU204306 | 没有驱动策略支持的节点类型 | `NodeDriver.driveNode` |
| AU204307 | ACTION 行缺少对齐时挂载的节点定义 | `runActionSequence` |
| AU204308 | 监听器声明缺少 name | `ListenerEngine` |
| AU204401 | 流程定义没有已发布版本 | `PlanService` |
| AU204402 | 流程定义版本不存在 | `DefinitionCache` |
| AU204501 | 非数值操作数不可比较大小 | `ConditionEvaluator` |
| AU204601…204611 | SPI 重复注册 / 未注册（handler、四类 listener、shard resolver） | 各 `*Registry` |
| AU204701…204704 | JSON 序列化/反序列化失败 | `Json` |
| AU204801 / 204802 | 业务键取号为空 / 超长（上限 20） | `IdGenerator` |

## 附录 C 需求 → 实现锚点（现行代码定位）

| 需求段 | 锚点 |
| --- | --- |
| 5.2/5.3 DSL 语法与表达式 | `DslParser`、`ProcessDefinition.roots/orderedRoots/flowOrder` |
| 5.4/5.5 聚合与命名并行 | `DefinitionLoader.expandGroups/validateNamedParallels`、`GroupExprMaterializer`、`ParallelExprMaterializer` |
| 5.6 装载与校验 | `DefinitionLoader`、`ProcessDefinition.mergeWithGlobals/effectiveErrorPolicy/stopsSequence` |
| 5.7 创建 | `PlanService.createProcess` |
| 5.8 执行 | `ExecutionEngine`、`NodeDriver`、`Materializer.rootAt/childAt/attachCursor` |
| 5.9 并发 | `ProcessEngineConfig` 三池、`ParallelStrategy`、`ActionStrategy`、`runActionsParallel` |
| 5.10 上下文 | `Context`、`ProcessContext`、`NodeContext`、`LaneContext`、`ActionContext`、`ContextFactory` |
| 5.11/5.12 变量与条件 | `VariableResolver`、`ConditionEvaluator` |
| 5.13 分片 | `ShardResolver`、`LeafExprMaterializer.resolveShardKeys/laneAt` |
| 5.14/5.15 扩展点 | `ListenerEngine`、`ObserverEngine`、`core.spi.*` |
| 5.16/5.17 状态与失败 | `Status`、`Errors`、`ProcessContext.recordFailure/settleFailure` |
| 5.18/5.19 干预与回退 | `InterventionService`、`RollbackService`、`CancellationRegistry`、`ExecutionEngine.autoRollback` |
| 5.20/5.21/5.22 视图/API/调度 | `ProgressService`、`ProcessFacade`/`ProcessRequest`/`ProcessExecution`、`Dispatcher` |
| 第 6 章 数据模型 | `resources/db/schema.sql`、`mapper/*`、`META-INF/mapper/process/*.xml`、`domain/*` |
| 第 7/8 章 | `ProcessEngineConfig`、`IdGenerator`/`IdKind`、`ProcessException` + `i18n/orchestrator-messages.properties` |
