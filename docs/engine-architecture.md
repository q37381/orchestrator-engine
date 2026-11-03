# 引擎架构与分层约定

本文是 `orchestrator-engine` 的**代码导航**：包职责、依赖方向、驱动/补偿内核、配置热更新边界、错误与日志口径。
外部契约（门面方法名、SPI、YAML/DSL 关键字、配置键、状态字面量、消息键、表结构）以
`docs/engine-capability-requirements.md` §24 为准，本文不重复、也不放宽。

## 1. 分层与依赖方向

```text
web -> api -> core.engine -> core.runtime -> core.define / core.context / core.model -> domain -> mapper / util
                         \-> core.spi（engine/runtime 消费，spi 不反向依赖 engine）
config -> core.engine + mapper（只做 Spring 装配）
```

| 包 | 职责 | 允许依赖 |
| --- | --- | --- |
| `web` | REST 入口（可选 spring-webmvc，不参与组件扫描） | `api` |
| `api` | 对外门面 `ProcessFacade` / `ProcessRequest` / `ProcessExecution` | `core.engine` |
| `core.engine` | 驱动编排、走查引擎、节点档案、干预、补偿、查询、创建 | `core.runtime/define/context/model/spi`、`domain`、`mapper`、`util` |
| `core.runtime` | 定义缓存、上下文工厂、占位符/条件求值、取消信号、脏桶刷新 | `core.define/context/spi`、`domain`、`mapper` |
| `core.define` | YAML/DSL 解析、定义模型、装载期校验 | `core.model`、`util`、`exception` |
| `core.context` | 四层上下文（流程/节点/lane/action）与桶读取 | `core.define/model` |
| `core.model` | 状态机、错误策略、节点/动作段等纯模型 | 无 |
| `core.spi` | 宿主扩展点（handler/listener/observer/shard resolver/registry） | 无 `engine` 依赖 |
| `domain` / `mapper` | 实例行实体与 MyBatis 访问 | `core.model`、`util` |
| `config` | 引擎装配（MyBatis、三档线程池、Dispatcher 工厂、表名变量） | `core.engine`、`mapper` |
| `util` / `exception` | JSON、错误摘要、业务键、统一异常 | 无 `engine` 依赖 |

**禁止**：`domain`/`mapper`/`util` 反向依赖 `engine`；`core.context`/`core.spi` 依赖 `engine`；
`engine` 依赖 `api`/`web`/`config`。新增依赖前先对照本表。

## 2. `core.engine` 内部四条线

| 线 | 入口 | 主要协作件 |
| --- | --- | --- |
| drive | `ExecutionEngine` -> `NodeDriver` -> `NodeKind` 档案 | `LayerWalker` 走查内核、`ActionRunner`、`Materializer`、`NodeKinds` |
| intervention | `InterventionService` | `InstanceTree`、`StopRequest` |
| compensation | `RollbackService` / `RestoreService` | `InstanceTree`、`RollbackTrail`、`CompensationContexts` |
| query | `ProgressService` | `InstanceTree` |
| plan | `PlanService` | `Materializer`、`DefinitionCache`、`ContextFactory` |

对外只暴露 `ProcessFacade`；`ProcessEngineConfig` 只做装配。四条线之间不直接互调（补偿/重放由
`ExecutionEngine` 在收尾/驱动前显式调用），避免形成运行时循环。

## 3. 走查内核（LayerWalker 拆分经验）

```text
LayerWalker（门面，5 个入口）
  └─ WalkServices（装配点：三档池 + 行定位 + 上下文工厂 + 重臂 + 回路）
       ├─ WalkLoop       串行/扇出两份算法模板（全仓唯一）
       │    ├─ WalkStep        串行一步：drive/stops/cancelled/drainFrom（具名实现按层）
       │    └─ FanoutTask      扇出备料：prepare(i) -> Callable（具名单元）
       ├─ CircuitWalk      三段骨架 SectionedCircuit（节点/组/共享段/action 级段）
       ├─ NodeSegments     某节点某 lane 的段执行器（定位/重入/收敛/drain/级段）
       ├─ SegmentRearmer   四种段重臂（流程/叶子/组/组级节点）
       ├─ RootsWalk        顶层根序列
       ├─ ChildrenWalk     容器子节点（串/扇出）+ 组级段切片
       └─ LanesWalk        分片 lane（逐 lane 回路 / 共享段档）
```

约定：
- **一个算法只有一份实现**（`WalkLoop.serial/fanout`、`CircuitWalk.run`）；
- 层差异用 `WalkStep` / `CircuitWalk.SectionedCircuit` / `WalkLoop.FanoutTask` 的**具名实现**表达，不写匿名 lambda；
- 场景对象（`NodeSegments` / `ChildrenWalk` / `LanesWalk` / `RootsWalk`）在构造时绑定现场，方法只带段/范围；
- 新增层或段时先判断能否复用现有 `WalkStep`，不要在 `LayerWalker` 门面里加分支。

## 4. 补偿内核

```text
InstanceTree        实例树 DFS / subtree / pathToRoot（Progress/Intervention/Rollback 共用）
RollbackTrail       rollback_record 的 begin/update/latestCompensated/history（字段与 detail JSON 唯一口径）
CompensationContexts 补偿/重放 action 上下文重建（节点 -> lane -> payload -> 激活期解析）
```

约定：
- 记录字段、detail JSON 形状（`reason` 在前、`error` 可选）只在 `RollbackTrail` 定义；
- 上下文重建顺序只在 `CompensationContexts` 定义；两条补偿路径不得再内联复制；
- 树遍历顺序只在 `InstanceTree` 定义；进度分母、补偿逆序、干预复位共用同一口径。

## 5. 参数对象与装配

- `WalkServices`：走查协作件装配点，层走查只带现场 + 本束；
- `PlanService.CreateRequest`：创建请求值对象，原 3/4/6 参重载保留并委托；
- `ProcessEngineConfig.PoolSizing`：单档线程池 sizing 值对象，构造期校验；
- `RollbackService` / `RestoreService`：构造依赖收敛到“mapper + handler + 共享内核”，不再各持一整套工厂。

新增参数超过 4 个时，优先考虑值对象；新增协作件时优先考虑“一个职责一个类”，避免继续膨胀门面。

## 6. 配置与热更新边界

配置入口集中在 `ProcessEngineConfig`（表名前缀、DataSource、Dispatcher、三档线程池 sizing）。

| 配置 | 生效时机 | 热更新 |
| --- | --- | --- |
| 定义内容 | 按 `(defId, version)` 缓存，借出私有副本 | **天然热更**：发布新 version 即新 key；同 version 原地 UPDATE 需重启或后续 `evict` |
| 三档线程池 sizing | `@Bean` 创建时 | 启动期固定（JDK 线程池不适合运行期缩容）；非法值构造 `PoolSizing` 时即拒 |
| Dispatcher enabled/poll/batch/unit/zone | 工厂创建时 | 启动期固定；运行期刷新列入后续 batch |
| 表名前缀 | `processSqlSessionFactory` 创建时 | 启动期固定 |

默认值：三档池均为 `16/16/20000/600`（core/max/queue/keepAlive）；`@Value` 字段初值保证非 Spring
直接 new 也能跑。配置错误属于部署/编程错误，用 `IllegalArgumentException` 在启动期报清字段与值。

## 7. 错误与日志口径

三级错误处理，禁止在业务路径随手 `catch` 后静默吞掉：

| 级别 | 场景 | 处理 |
| --- | --- | --- |
| veto | before 监听器、节点 guard 否决 | 抛异常，由调用方落 FAILED；不写 `recordFailure` |
| record-and-continue | 驱动异常、非法返回值、并行子任务异常、补偿失败 | `FailureRecorder.record` -> `Errors.describe` + `ProcessContext.recordFailure` + 现场日志 |
| observe-only | after 监听器、Observer | 只记日志，不改已定终态 |

日志统一使用 `proc=/node=/lane=/action=` 键值定位；线程池任务用 `DriveTask` 等具名 `Runnable.toString()`
带上流程 id。失败摘要最终落 `process_inst.result.__error`（`Errors.KEY`），不在日志里另造一套格式。

## 8. 扩展点速查

| 要扩展什么 | 改哪里 |
| --- | --- |
| 新节点类型 | 新增 `DslParser.Expr` 子类 + `NodeKind` 档案（`materialize`/`drive`），注册进 `NodeKinds` |
| 新驱动层/新段语义 | `WalkStep` / `CircuitWalk.SectionedCircuit` 的具名实现，复用 `WalkLoop` 模板 |
| 新补偿/重放动作 | `ActionHandler.compensable/compensate/restore`；记录写入走 `RollbackTrail` |
| 新查询视图 | `ProgressService`（后续 batch 引入 `ProgressView` 类型化视图） |
| 新宿主扩展 | `core.spi` 下新增接口 + `*Registry`，不要在 engine 里直接依赖实现 |
| 新配置项 | `ProcessEngineConfig`（默认值 + 启动校验），保持既有键名不变 |

## 9. 回归基线

- 全量：`mvn -o test`（当前 515 个用例）。
- 重点关注：drain 只碰已物化行、让渡不 drain、setdown finally、不变式 I1、补偿/重放记录字段与 detail 键序、
  进度 Map 键名与顺序、Dispatcher 认领 CAS/不超发/异常续循环。
- 每次结构调整先跑全量；涉及外部契约的改动以 `docs/engine-capability-requirements.md` §24 为准。
