# 节点级 setup/setdown 段 设计说明书

| 项 | 内容 |
| --- | --- |
| 文档版本 | v1.5（**已落地**: 组级段/开关声明位置放开——可写进 `group:` 段内（与 flow 级 setup/flow/setdown 三段同形）, 与写在 `group:` 同级等价, 同一字段两处都写拒（AU204235）; 零 DDL, 全量 501 测试绿; 落点见 §3.1/§3.2/§5.9/§7.8/§8）。v1.4（**已落地**: 组级段 = 叶子子节点引用——组 setup/setdown 值按**声明形状**分流（字符串列表=节点引用 / map 列表=内联动作）, 引用重绑定为提升键并注入 Group 子项头尾（**零 DDL**, 全量 491 测试绿; 落点见 §2.1/§3.1/§3.2/§5.11/§7.7/§8/§10））。v1.3（**已落地**: GROUP 级段——聚合节点的 setup/setdown 包住**整个子节点序列**（**零 DDL**, 全量 463 测试绿; 落点见 §2.1/§3.1/§3.2/§5.9/§7.5/§8/§10）; **追加已落地**: 节点级段"不跟随分片"开关 `segmentsFollowShard`（**零 DDL**, 全量 477 测试绿; 落点见 §2.1/§3.1/§3.2/§5.10/§7.6/§8/§10）。v1.2（**已落地**: action 级段——主段内单动作的局部 setup/setdown 段（**零 DDL**, 全量 432 测试绿）, 见 §3.2/§4.4/§5.8/§7.4/§8/§10）。v1.1（**已落地**: 节点级 + 流程级"成对重臂"与 `repeatSegments` 开关, 全量 419 测试绿; 落点见 §3.1/§3.2/§5.6.1~§5.6.3/§7.3/§8/§10）。v1.0（已落地: 桩代码/DDL/走查/服务层与测试均已实现, 全量 368 测试绿; §9 复议项均按默认口径实现） |
| 适用工程 | `orchestrator-engine` |
| 文档用途 | 定义节点级"前置/收尾必达段"（setup/setdown）的语义、数据模型与实现落点；评审通过后作为实现与测试的唯一输入 |
| 读者 | 引擎维护者、评审人、接入方（宿主业务系统）开发 |
| 措辞约定 | **必须**＝刚性约束，违反即缺陷；**应当**＝默认做法，偏离需给出理由；**复议**＝待评审确认的默认口径 |
| 编号约定 | `FR-NSD-*` 功能需求，`DM-NSD-*` 数据模型，`EX-NSD-*` 错误码（建议），`AC-NSD-*` 验收标准 |
| 与既有文档关系 | 本特性是 `engine-requirements.md` 的增量；评审通过后合并进该文档（FR-DEF/FR-EXE/FR-RBK 相关章节）与 `engine-capability-requirements.md` |

---

## 1 背景与目标

### 1.1 场景

节点的执行需要"前后必达"的伴随动作，且必须跨失败、跨中断、跨崩溃可恢复。典型（数据同步只是迁移流程中的一环）：

```java
pauseReplication();               // 同步前：暂停主从复制
try   { syncData(); }             // 主逻辑 = node 的主段
finally { resumeReplication(); }  // 同步后：无论成败恢复主从复制
```

### 1.2 现有机制的盲区（NodeListener 切面）

- `NodeListener.before/after` 不落实例行（无 action_inst 行），切面是否执行过无痕可查；
- `NodeDriver.finishNode` 先落节点 `success`、后触发 `after`——**崩溃发生在两者之间**时：节点已是终态，`InterventionService` 复位集 `RESETTABLE={FAILED,STOPPED,RUNNING,PENDING}` **不含 SUCCESS**，retry 重驱动被 `driveNode` 幂等跳过，**恢复动作永远不再执行**；
- `after` 异常被引擎吞掉（纯通知），恢复失败不可见、不可补。

结论：需要一种**落实例行**的段机制，让"执行与否"持久化、让"未执行的"可补跑。

### 1.3 目标

1. **节点级 finally**：setup 在主段前、setdown 在主段后，setdown 无论成败必执行；
2. **崩溃可恢复**：段动作以 action 实例行落库，"已成功跳过、未完成补跑"；节点终态只在 setdown 走完后才落；
3. **零破坏**：未声明段的定义与存量数据，行为逐字不变。

### 1.4 非目标（范围外）

- ~~不做 action 级 setup/setdown~~（v1.0 口径；**v1.2 已扩展为"action 级段"**——节点主段内单个 action 的局部 setup/setdown 段（v1.2 仅同动作成对, 拆分式多动作留后续版本候选）, 见 §5.8; 分片节点因 lane 不连贯不可拆节点、ActionListener 因不落行/编排硬编码被否）；
- 不做模板/片段复用（`process_fragment` 仍为预留）；
- 不做段行参与回退补偿（默认排除，见 FR-NSD-13）。

---

## 2 概念模型

### 2.1 执行回路（execution circuit）

段的宿主是"回路"：

- **非分片节点**：回路 = 节点自身（三段各跑一次）；
- **分片节点**：回路 = **每个 lane**（每个分片独立形成"setup → 主段 → setdown"闭环；段内可读 `${lane.shardKey}`，适配"每分片一套主从"的场景）；**例外（v1.3）**：声明 `segmentsFollowShard: false` 时节点级段不跟随分片——回路 = 节点自身：setup/setdown 整节点各一次（段行 `lane_id` 恒 NULL）、主体仍逐 lane（见 §5.10）；
- **聚合节点 GROUP（v1.3）**：回路 = 组自身——组 `setup` → 组内子节点序列（逐个子节点；子节点各自还可有自己的回路）→ 组 `setdown`；组没有 MAIN 动作段，主体就是子节点序列（见 §5.9）。

### 2.2 与顶层三段式的同构关系

| 顶层（已实现） | 节点级（本设计） |
| --- | --- |
| `setup` + `flow` + `setdown`（`orderedRoots()`） | 回路内 `setup` + 主段 + `setdown`（段序固定） |
| `setdown` 恒不收敛、finally 语义 | 同（FR-NSD-06） |
| 行按 auto_id 与表达式下标对齐（执行到才建） | 同（段内独立对齐，FR-NSD-08） |
| 重入：已终态行越过、未执行补跑 | 同（§5.6；v1.1 增量：现场已被 setdown 撤除时按 §5.6.1 成对重臂） |

走查实现复用同一思想：`LayerWalker.walkRoots` 的两段模板（`[0, flowEnd)` 收敛段 + `[flowEnd, end)` 恒跑段）下沉为**回路三段模板**。

注意区分：顶层 `setup/setdown` 的值是**节点引用列表**（`List<String>`），节点级段的值是**内联动作序列**（`List<ActionDef>`）——名字同、心智同（前置/收尾）、载体不同。

---

## 3 DSL 与校验

### 3.1 声明形态

节点（叶子 ACTION，含分片叶子）新增可选段 `setup` / `setdown`，解析口径与 `actions` 段**完全同构**（列表；每项结构 = 现有 ActionDef 的解析字段集合：`id`/`handler`/`when`/`config`/`listeners`）。

```yaml
flow: "preSteps, dataSync, postSteps"        # 节点只是迁移流程中的一环

nodes:
  dataSync:
    setup:                                    # 前置段: 主段之前逐项执行
      - id: pauseReplication
        handler: slavePause
        config:
          channel: "${config.cluster}"
    actions:                                  # 主段: 现状语义逐字不变
      - id: doSync
        handler: dataSync
    setdown:                                  # 收尾段: finally 恒执行
      - id: resumeReplication
        handler: slaveResume
```

口径：

- FR-NSD-01 节点定义**必须**支持可选 `setup`/`setdown` 段；两段可省略，"写了但留空"与"缺失"同口径（与流程级 setup/setdown 一致）；
- 段**不进 flow DSL**（不参与 `orderedRoots()` 表达式走查），仅存在于节点内部；
- 段内项支持项级 `when`（guard）与项级监听器（照搬 ActionDef 能力）；
- FR-NSD-17（v1.1）节点与**流程顶层**均**必须**支持可选布尔声明 `repeatSegments: true`（默认 false）——段"每趟驱动重跑"显式开关, 语义见 §5.6.2/§5.6.3；字段是普通属性, 随定义 JSON 快照往返（同 FR-NSD-04 口径）。
- FR-NSD-23（v1.3/v1.5）**GROUP 级段**: 聚合节点的 `setup`/`setdown`/`repeatSegments` 可写在 **`group:` 段内**（v1.5, 与 flow 级 setup/flow/setdown 三段同形, 推荐）或**与 `group:` 同级**（旧写法）, 两处等价; 段包住**整个子节点序列**（组 setup -> 组内子节点逐个走查 -> 组 setdown）；段不进 flow 与 `GROUP:名(...)` 子项列表（展开形状不变）; 语义与重臂见 §5.9。
- FR-NSD-26（v1.3）**段不跟随分片**: 分片叶子可声明可选布尔 `segmentsFollowShard`（默认 true = 跟随分片）; 声明 `false` 时节点级 setup/setdown **不跟随分片**——整节点各跑一次（setup 一次 -> 各 lane 主体 -> setdown 一次, 段行 `lane_id` 恒 NULL）, 主体仍逐 lane; 字段随定义 JSON 快照往返（同 FR-NSD-04 口径）; 仅节点级段适用（action 级段恒随主体逐 lane, 跨 lane 共享的级段为后续候选, 见 §5.10）。

### 3.2 装载期校验

| 编号 | 规则 | 错误码（建议） |
| --- | --- | --- |
| FR-NSD-02 | 容器/控制节点的段约束（v1.3 收窄）: **命名并行** `PARALLEL:标签` 仍是纯容器, 声明 setup/setdown/repeatSegments **必须**拒绝; **聚合节点 GROUP** 是 v1.3 的正当能力（段包住子节点序列）——GROUP 仍不得声明 handler/actions/shard/laneExecution/actionExecution | AU204204 |
| FR-NSD-03 | **显式声明 id** 的段动作与主段/另一段合并唯一；重复**必须**拒绝（空 id 不参与） | AU204214 |
| FR-NSD-04 | 段字段**必须**是定义对象的普通属性——随定义 JSON 快照往返（无派生字段陷阱） | — |
| FR-NSD-17（v1.1） | `repeatSegments: true` 但 setup/setdown **两段均未声明** → **必须**拒绝（死配置；仅声明其中一段合法） | AU204217 |
| FR-NSD-17（v1.1） | 顶层同款死配置（无流程级 setup/setdown 段）与节点级/顶层的非法声明值 → **必须**拒绝 | AU204218 / AU204219 |
| FR-NSD-19（v1.2） | action 级段**必须**同动作成对: 只挂 setup / 只挂 setdown（含拆分式多动作写法——两侧各命中单边）→ **必须**拒绝; 段动作不得携带段标记（深度锁 1） | AU204220 / AU204221 / AU204222 |
| FR-NSD-19（v1.2） | 段动作 id 与主段/节点段/其他 action 级段合并唯一——段行识别的**唯一依据**；重复**必须**拒绝（空 id 不参与） | AU204223 |
| FR-NSD-21（v1.2） | 声明 action 级段的节点与 `actionExecution: PARALLEL` **必须**拒绝（级段有先后语义） | AU204224 |
| FR-NSD-24（v1.3/v1.5） | GROUP 段声明位置: 段/开关写进 `group:` 段内与写在节点上（与 `group:` 同级）**等价**; 同一字段两处都写 → **必须**拒绝（声明歧义） | AU204235 |
| FR-NSD-24（v1.3） | 同一段内两条段动作的**有效键**（id, 未声明回落 handler）重复 → **必须**拒绝（段行定位键会复用同一行） | AU204225 |
| FR-NSD-25（v1.3） | 同一份声明既是 GROUP（group 段展开或被 `GROUP:名(...)` 引用）又当叶子引用, 且声明了段/开关 → **必须**拒绝（段的宿主无唯一解, 请拆成两条声明） | AU204226 |
| FR-NSD-26（v1.3） | `segmentsFollowShard: false` 但未声明 setup/setdown 段（死配置）→ **必须**拒绝；非分片节点声明 false（没有分片可"不跟随"）→ **必须**拒绝 | AU204228 / AU204229 |
| FR-NSD-26（v1.3） | `segmentsFollowShard` 非法声明值（非 true/false）→ **必须**拒绝；容器（GROUP / 命名并行）声明该字段 → **必须**拒绝 | AU204227 / AU204204 |
| FR-NSD-27（v1.4） | 组级段值按**声明形状**分流: 纯字符串列表 = 叶子子节点引用, 纯动作 map 列表 = 内联动作; 同一条里混用、出现其他类型、setup/setdown 两段形状不一致 → **必须**拒绝 | AU204230 |
| FR-NSD-27（v1.4） | 节点引用出现在**叶子**声明的段上 → **必须**拒绝（节点级段只接受内联动作） | AU204231 |
| FR-NSD-27（v1.4） | 组级段引用必须命中本组 `group.nodes` 下直接声明的叶子子节点（嵌套聚合节点/组外节点/同段重复引用 → 拒） | AU204232 / AU204225 |
| FR-NSD-27（v1.4） | 段引用的子节点不得同时被组主体 flow 引用（段与主体无重叠） | AU204233 |

---

## 4 数据模型

### 4.1 segment 列

DM-NSD-01 `process_action_inst` 新增：

```sql
segment VARCHAR(16) NOT NULL DEFAULT 'main' COMMENT 'setup|main|setdown, 小写规范值'
```

- 建议 Java 侧引入 `ActionSegment` 枚举（`core/model`），小写值风格与 `Status`/`NodeType` 的 `getStatus()` 一致；
- **存量行自动落 `main`** → 走查/回退/进度口径与改造前逐字一致；
- 段行与主段行**同表同生命周期**：走 `ActionRunner`（guard → before → handler → 落库 → after），config 冻结/result/visit_count/监听器链全部同口径。

### 4.2 唯一键与索引

- DM-NSD-02 唯一键 `uk_node_def_shard` 扩列为 `(node_inst_id, action_def_id, shard_key, segment)`；
  - 理由：跨段同名 id 必须物理可区分（防御绕过装载校验的直插；插入用普通 `INSERT`，唯一键只做最后一道防线——撞键即报错，不靠 `INSERT IGNORE` 静默去重）；
- DM-NSD-03 索引 `idx_container` **应当**扩展为 `(lane_id, node_inst_id, segment, status, auto_id)`（段过滤是走查/进度的高频谓词）。

### 4.3 查询口径

- `findInContainer` 系列**必须**支持按 segment 过滤（段内定位/对齐/drain/进度全基于该维度）；
- 自然键 find-or-create（现 `findInContainer` 合并的 `findByContainerAndDef` 口径）**必须**把 segment 纳入自然键（`node, lane, defId, segment`）。

### 4.4 action 级段行模型（v1.2 增量: 零 DDL）

- DM-NSD-04（v1.2 定稿: **不加锚列**）action 级段下**不需要** `parent_action_inst_id`: 每组级段的成员 = 主体动作声明的 setup/setdown 定义列表（**定义侧成员表**）, 而运行期所有路径（走查/补跑/重臂）本就定义驱动——从行侧反查"属于哪组级段"在任何路径上都不发生; 跨级段重名由 AU204223 合并唯一挡死, 4 列 uk 原样承担物理幂等（分片行）。备选锚列被否: 其剩余价值只剩"视图按级段分组"（段行按执行顺序物化, 平铺视图天然可读; 要分组回查定义即可）, 不值 1 列 + 实体/映射/替身全套; 若 v1.3 恢复拆分式多动作级段（跨下标跨度确需锚）, 随该增量再评估;
- DM-NSD-05 `segment` 值域扩为 `setup|main|setdown|action-setup|action-setdown`（`ActionSegment` 枚举 +2 值，小写规范值）：段动作用**独立段值**使存量回路查询**零改动**（节点段查询 `segment='setup'` 天然看不到段行；级段查询 `segment='action-*'` 天然看不到节点段行）；
- 索引：`idx_container (lane_id, node_inst_id, segment, status, auto_id)` **原样够用**（按级段查询 = `segment='action-*'` 收窄, 已在索引前缀里）；
- 查询口径：**零新增查询**——段行物化/定位复用既有 `ensureActionRow`（本就带 segment 参）与 `findInContainer`; 级段成员筛选用"段值 + 定义成员表"; 重臂复位**新增窄写方法** `reArmRows(rows, allTerminal)`（按定义成员过滤后的行集复位——不可整段复位, 多个级段会误伤）;
- 存量库升级：**无**（零 DDL——无新列、无索引/uk 变更；segment 值域扩展走既有 VARCHAR 列）。

---

## 5 运行语义（核心）

### 5.1 回路三段编排

```
进入回路（非分片: 取节点行 / 分片: 建或复用 lane 行）
  ├─ setup 段     : 逐项驱动（段内收敛见 5.3）
  ├─ 段间闸门     : setup 段聚合含 FAILED/STOPPED → 跳过主段（已物化行 drain, FR-NSD-10）
  ├─ 主段         : 现状动作序列逐字保留（actionExecution 档 / FAIL_FAST 收敛 + drain）
  ├─ setdown 段   : 恒执行、段内不收敛、不查取消闸门
  └─ 容器行收尾   : 三段 severity 聚合 → lane 行 / 非分片 node 行落终态（FR-NSD-09）
```

### 5.2 语义矩阵

| # | 事件 | setup 段 | 主段 | setdown 段 | 回路终态 |
| --- | --- | --- | --- | --- | --- |
| 1 | 全部正常 | 跑完 | 跑完 | 跑完 | success |
| 2 | setup 项坏 | 段内按策略收敛 | **跳过** + 已物化行 drain | **照跑** | failed / stopped |
| 3 | 主段项坏 | — | 按行冻结策略收敛 + drain | **照跑** | failed |
| 4 | 取消（stop） | 闸门处停 | 闸门 / drain | **照跑** | stopped |
| 5 | setdown 项坏 | — | — | 余项继续（不收敛） | failed |
| 6 | 重入：行已 success | 跳过 | 跳过 | 跳过 | 不重复计终态（按 success 计入） |
| 7 | 崩溃后 retry | 未完成补跑 | 未完成补跑 | **未完成补跑** | 完成即聚合落终态 |

### 5.3 收敛与取消口径

- FR-NSD-05 setup 段：段内每项收敛沿用**行冻结策略**（节点行 `error_policy`，就近声明优先的既有口径）；段聚合出现 FAILED/STOPPED → **固定**跳过主段（与段内策略无关）；
- FR-NSD-06 setdown 段：**恒不收敛**（某项坏不拦剩余项）、**不查取消闸门**（finally 优先）——与顶层 setdown 逐字同口径；
- FR-NSD-07 段**必须**恒串行（不跟随 `actionExecution: PARALLEL`；主段档位不变）。理由：准备/收尾有先后语义，且段通常 1–2 项。

### 5.4 行物化与状态收尾（关键不变式）

- FR-NSD-08 段行"执行到才建"（LAZY 缺行补建、重入 find-or-create 复用），与主段同口径；
- FR-NSD-09 **不变式 I1**：回路容器行（lane / 非分片 node）的状态收尾**必须**推迟到 setdown 段完成之后，值 = 三段聚合；主段结束时**不得**提前落终态。分片节点的 node 行仍由 `NodeDriver.finishNode` 按各 lane 聚合收尾。
  - 这条是崩溃可恢复性的根基：容器行非终态 ⇒ 重驱动会补完剩余段；容器终态 ⇒ 三段已全部走完（或已明确失败留痕）；
  - 现状 `walkActions` 末尾的 `laneMapper.setStatus` / `nodeMapper.setStatus` 需上移到回路收尾点。
- FR-NSD-10 主段整体跳过的 drain：对主段**已物化且未执行**（CREATED/PENDING）的行统一落 `stopped` + `__skipReason=DRAIN`（与现有收敛 drain 同格式），**不补建**未物化行。

### 5.5 分片（每 lane 一个回路）

- FR-NSD-11 分片节点的每个 lane **必须**独立执行完整三段：lane 串行档逐 lane 闭环、lane 并行档各 lane 并行闭环；段行挂该 lane 下（`lane_id` 非空）；段内项可读 `${lane.shardKey}`；
- FR-NSD-12 lane 之间的收敛沿用现状（节点行冻结策略：FAIL_FAST 停后续 lane / CONTINUE 继续）；段只作用于**回路内部**，不改变 lane 间行为；
- 空分片（resolver 返回空）不存在回路，段不执行（现状语义不变）。

### 5.6 重入与崩溃恢复

恢复入口沿用 `retry`（`retryProcess`：RESETTABLE 复位 CREATED + 重驱动按行续跑）。段行带来的效果：

| 崩溃点 | 库上状态 | retry 后 |
| --- | --- | --- |
| 主段完成后、setdown 前 | 容器行 running；段行部分 CREATED | setdown 补跑，三段聚合落终态 |
| setdown 执行中 | 容器行 running；部分段行 success | 剩余行补跑 |
| 三段全部完成后、容器收尾前 | 容器行 running；段行全部 success | 全部幂等跳过，落终态（**零重复执行**） |
| setdown 行 failed | 容器行 failed；该行 failed | 复位重跑该行（其余跳过） |
| 主段行 failed 且 setdown 已有 success 行（人工 retry 后） | 容器行 failed；main 有未跑成行、setdown 有 success 行 | **成对重臂**（v1.1）：setup/setdown 的 success 行复位重跑、main 失败行复位续跑——见 §5.6.1 |

宿主职责：孤儿 RUNNING 流程的重捞属调度侧策略；引擎提供 retry 入口（现状不变）。

### 5.6.1 成对重臂（v1.1 增量, FR-NSD-16）

**问题**: 主段失败 → setdown（finally）已把现场还原 → 人工 retry。既有口径下 setup 行 success 不复位、不重跑, 而现场已被 setdown 撤除, 重试的主段只能在"没有准备"的环境上续跑（典型: 为迁移调大的超时类参数不会重新施加）。

**语义**: 节点**同时声明** setup 与 setdown 时, 回路（分片按每 lane、非分片按节点自身）每次进入前判一次:

> **判据**: setup 或 main 段存在非终态行或 main 行不齐（缺行：本轮将重跑，详见下条）**且** setdown 段存在 success 行（现场已撤）→ 把 setup 段与 setdown 段中的 **success 行** CAS 复位为 CREATED（"成对重臂"）, 随后按 §5.1 既有三段走查。

- **判据为什么精确**: 自然状态下 setdown 能执行的前提是 main 段全部终态; "setup/main 有非终态行 + setdown 有 success 行"只可能由一次显式重试（retry 复位 FAILED/STOPPED 行）制造——恰是"现场已被还原、人要求重跑"的场景（setup 一侧覆盖"setup 失败且主段从未物化"的路径: 只认 main 会在该路径漏判, setup 重跑成功而 setdown 不再收尾）。§5.6 主矩阵前四行因此逐字不变;
- **缺行也算法"待跑"**（实现期修正）: main 已物化行数少于定义侧有效键数（取消/中断停在**动作边界**——已物化的只剩一段全 success 前缀, 未物化的尾巴本趟会补跑）同样算"本轮将重跑"; 只查非终态行会漏判——尾巴随后在**已被 setdown 撤除**的现场上续跑, 且 setdown 行已是终态、不会再收尾（流程还会带 success 收尾）。口径与组级重臂的"尚有子节点未物化"同向; 行数按定义侧有效键去重计（段行按定义键定位, 主段重复键只物化一行）; 该组合只会出现在"取消/中断已收尾 + 显式重试重新驱动"时（段全成未收尾 / 收尾失败补跑等场景行是齐的, 不触发）;
- **守恒性**:
  - 只复位 success 行（skipped 是 guard/人工判定的既成事实, 不复位）, 不动 result（口径同 `InterventionService.resetAction`）; 重复执行 visit_count 照常累加留痕;
  - **必须**幂等: 重臂是多条 CAS, 中途崩溃下次驱动重判、重复复位零副作用; 复位后的三段走查本就可中断可重入;
  - 与不变式 I1 不冲突: 重臂发生在回路入口、容器行收尾之前;
  - 与回退/重放不交叉（FR-NSD-13 不动）: 段行依旧不参与补偿, `RestoreService` 重放名单只含 main 的补偿历史;
- **场景矩阵**（与 §5.6 主矩阵叠加）:

| 场景 | 判据 | 结果 |
| --- | --- | --- |
| main 失败 + setdown 已成 + retry | 触发 | setup 重跑（现场重施）→ main 续跑 → setdown 重跑（重新收尾） |
| setup 失败（主段未物化）+ setdown 已成 + retry | 触发（setup 侧命中） | setup 重跑成功 → main 全量首次执行 → setdown 重跑（重新收尾） |
| 崩在 setdown 前（现场仍生效） | 不触发 | setup 跳过、setdown 补跑（现状） |
| 三段全成、容器未落库 | 不触发 | 全部幂等跳过、落终态（"零重复执行"） |
| setdown 行 failed + retry | 不触发 | setup 跳过、setdown 补跑（现状） |
| 分片 | 每 lane 独立判（`findInContainer(node, laneId, 段)`） | 只重臂失败重试的那个 lane |

### 5.6.2 repeatSegments 段重复执行开关（v1.1 增量, FR-NSD-17）

节点声明 `repeatSegments: true` 时, 回路每次进入前**无条件**把 setup/setdown 段的**全部终态行**（success/skipped/failed/stopped）复位为 CREATED——段按"每趟驱动重跑"语义执行。

- **用途**: 段与"改—还原"配对无关、但每趟驱动都要重新执行的场合; 与 `NodeListener.before`"每次驱动都触发"的心智对齐, 但**落实例行**（有 action_inst 行: 可观、可补跑、进进度明细）;
- **代价**（**应当**写进接入方文档）: 现场仍生效的崩溃窗口（如崩在 setdown 前）重驱动会把 setup 二次施加、setdown 重复收尾 → **段动作必须幂等**; skipped 行复位后 guard 会重新求值（与 `when` 既有口径一致）;
- **优先级**: 为 true 时按本条执行, 成对重臂（FR-NSD-16）不再追加效果（其复位范围是本条的子集）;
- 让渡/取消/收敛口径不变: 复位后完全走既有路径（让渡短路、setdown finally、drain 不补建）。

### 5.6.3 流程级对等重臂（v1.1 增量, FR-NSD-18）

与 §5.6.1 同判据、同目的, 判据落在**顶层根行**上（行按 auto_id 与 `orderedRoots()` 按下标前缀对齐, 与物化/驱动同口径）:

> **判据**: 流程级 setdown 段存在 success 根行 **且** 流程级 setup 段或 flow 段存在非终态根行 → 把流程级 setup/setdown 段中的 **success 根行** CAS 复位为 CREATED（"流程级成对重臂"）, 随后按 §5.1 既有顶层三段走查。

- **适用场景**（与节点级镜像一致）: 流程级 setup 节点改参数成功 → flow 失败 → 流程级 setdown 段已还原 → 人工 retry。若无本机制, retry 后流程级 setup 节点（success 终态）不复位不重跑, flow 在已还原的环境上续跑;
- **顶层 `repeatSegments: true`**（FR-NSD-17 顶层镜像）: 每趟驱动无条件复位顶层 setup/setdown 段全部终态根行;
- **复位对象**（实现期发现并修正）: 段根**节点行 + 该节点的 main 动作行**（分片段根含每 lane）——只复位节点行的话, 重驱动会在节点回路里被"终态动作行跳过"口径消化成空转（handler 不再执行而节点照收 success）; 只动 status、不动 result; 崩在 setdown 前 / 三段全成未收尾 / 收尾失败补跑三类场景不触发; 幂等（多条 CAS 中途崩溃下次驱动重判）; 判据只认**根行摆放位置**, 节点自身声明的节点级段另按 §5.6.1 在各自回路内判。

### 5.7 既有语义不变清单

- `finishNode`（节点行落终态 + `__error` 记录 + `NodeListener.after`）对含段节点同样生效——不变；
- action 级监听器链（含 `globalActionListeners`）、Observer 对段行同样触发（段行就是 action）——不变且有意为之（一致性）；
- v1.1 增量的零破坏面：未成对声明段的节点、以及 §5.6 矩阵前四行的驱动路径逐字不变（重臂判据在这些场景不触发）；
- 流程级三段编排（`orderedRoots()` 的 setup/flow/setdown 段）是**节点引用摆位**: 编排/收敛/finally/重入语义零改动; v1.1 另加流程级对等重臂（§5.6.3, 只复位根行状态, 不改编排本体）; 节点自身声明了节点级段的, 按节点声明生效, 与摆放位置（流程级段 / flow 段 / 组容器 / 分片）无关。

### 5.8 action 级段（v1.2 已落地: 仅同动作成对）

**问题**: 节点级段的作用域是**整段 main**（setdown 等主段全部收敛后才跑）, 表达不了"启用前配置、启用后**立即**恢复"这类**主段中段的局部现场**; 拆独立节点在分片下 lane 不连贯（分片集合解析不原子 / lane 上下文不共享 / 每 lane finally 跨节点丢失）, ActionListener 不落行（不可补跑、恢复失败被吞、配对逻辑只能硬编码）。action 级段即为此而设（v1.2 仅同动作成对）。

#### 5.8.1 声明形态与装载校验

一个动作**同时**挂 `setup:`（级 setup 段）与 `setdown:`（级 setdown 段, finally）即声明一组 **action 级段**——"配置 -> 该动作 -> 恢复"整体包住; 两段与 actions 同构解析。**v1.2 不支持**"拆分式多动作级段"（把两段分别挂到首尾两个动作上）: 单边标记一律装载拒, 该形态留 v1.3 候选——设计上其"段内失败即提前收尾(finally)"是成立的, 本次按评审口径先收窄（实现也显著更简: 无跨下标复合项）。

```yaml
nodes:
  dataSync:
    shard: dbTopology
    actions:
      - id: warmUp                      # 主体 action: 配置 -> warmUp -> 立即恢复
        handler: ...
        setup:                          # 级 setup 段: warmUp 之前跑
          - id: elevateConfig
            handler: ...
        setdown:                        # 级 setdown 段(finally: 成败恒跑): warmUp 之后立即收尾
          - id: restoreConfig
            handler: ...
      - id: pollStatus                  # 段外: 收尾之后继续, 不必等主段全部完成
        handler: ...
```

- FR-NSD-19 动作的 `setup`/`setdown` **必须**同挂或同不挂: 只挂一段（含把两标记拆到两个动作的写法——两侧各自命中单边）-> 拒（有 setup 无 setdown: AU204220; 有 setdown 无 setup: AU204221）;
- 段动作**不得**携带段标记（深度锁 1, AU204222）;
- 段动作与主段/节点段/其他 action 级段的显式 id **必须**合并唯一（AU204223）——段行识别的**唯一依据**（§4.4: 无锚列）;
- 声明 action 级段的节点与 `actionExecution: PARALLEL` **必须**拒绝（AU204224）;
- action 级段可与节点级段共存（行靠段值天然分开）; 分片节点在每 lane 回路内（与节点级段同源, lane 参数连贯）。

#### 5.8.2 行模型与走查语义

- FR-NSD-20 行: 主体 action = **普通 main 行**（进度/回退/断点续跑口径全复用, 不新增语义）; 级 setup/setdown 段的段动作 = 普通动作行——仅以 `segment=action-setup|action-setdown` 区分, 成员归属由**定义侧成员表**收窄（无锚列, 详 §4.4）;
- FR-NSD-21 走查（单项外挂的段电路, 不跨下标）: 主段项回调在**声明级段的动作**上改为——级 setup 段（段内按行冻结策略收敛、每步查取消闸门）-> 主体动作（**终态跳过**口径不变; guard/监听器/让渡/收敛/drain 全复用现状）-> 级 setdown 段（finally: 恒不收敛、不查取消闸门）; 三段 exit-aware 聚合即该项结果, 未声明级段的动作逐字不变;
- **段电路入口由定义驱动, 与行状态无关**（关键）: 即使主体动作与段行全为终态, 走到该动作仍进段电路——终态行按"已成功跳过"口径消化, 未完成的级 setdown 段由此**天然补跑**（"崩在段收尾前"无需额外机制; 节点级靠不变式 I1 推迟容器收尾, action 级段没有容器行可推迟, 改用定义驱动入口达成同效, ActionRunner 零改动）;
- 让渡: 级 setup 段让渡 -> 整段退出（主体+收尾都不跑）; 主体让渡 -> 级 setdown 段不跑、主段让渡退出; 重入从非终态行续跑（段电路入口定义驱动, 天然续上）;
- 级 setup 段坏（FAILED/STOPPED, 非让渡）-> 主体动作跳过（已物化行 drain、未物化不补建）+ 级 setdown 段**照跑**（镜像段间闸门）;
- 主体失败（FAILED/STOPPED, 非让渡）-> 级 setdown 段**照跑**（finally; 与节点级"主段失败、setdown 照跑"逐字同口径）;
- 取消: 级 setup 段每步查闸门; 级 setdown 段恒不查（与段口径一致）; FAIL_FAST 提前结束时未走到的声明级段动作不物化、无剩余项清理;
- 回退/重放: 段动作行 segment≠main, 与段行同口径**不参与**补偿与重放（RollbackService/RestoreService 零改动）; 主体 action 是 main 行, 照常参与。

#### 5.8.3 重臂与崩溃恢复（v1.1 判据的级段镜像, FR-NSD-22）

- action 级段重臂（默认语义, 按级段收窄）: 级 setup 段或主体动作存在**非终态行** 且 级 setdown 段存在 **success 行** -> 把级 setup/setdown 段的 success 行 CAS 复位 CREATED（判据与理由同 §5.6.1, 收窄维度从节点换到级段: 段值 `action-*` + 定义侧成员表）;
- 崩溃矩阵（镜像 §5.6）: 崩在段收尾前（现场仍在）/ 级段全成未续走 / 级段收尾 failed 补跑 均不误触发; "主体失败 + 级段收尾已成 + retry" -> 成对重臂: 级 setup 段重跑（现场重施）-> 主体续跑 -> 级 setdown 段重跑（重新收尾）;
- action 级段**不设** repeatSegments 开关（YAGNI, 复议再议）; 节点级 `repeatSegments` 只作用于节点段、不作用于 action 级段;
- 三条重臂轴线（流程级 / 节点级 / action 级）按各自段值/成员收窄独立判、互不干扰。

#### 5.8.4 边界与复议项

- 主体动作的 `when`（v1.2 口径: **直白版**）: 段照进——级 setup 段照跑、主体由 runAction 常态裁决（假 -> SKIPPED 留痕）、级 setdown 段照跑（finally）; 要"条件段"用段动作自己的 `when`（项级 guard, 零新代码）; "整段开关"（when 假整段跳过）留复议（需段前置 guard 求值, 本次不引入）;
- 段动作 `when`: 同段项口径（项级 guard）;
- 拆分式多动作级段（两标记分挂首尾动作）为 v1.3 候选: 设计上"失败即提前收尾（finally）"成立, 本次按评审口径收窄未纳入; 恢复时增量为就近配对规则 + 跨下标复合项走查;
- 深度锁 1（段动作不得携带标记, AU204222）; 声明级段的节点不支持并行档（AU204224 已拒）——复议再论;
- "拆独立节点"在分片下不可用（分片集合解析不原子 / lane 上下文不共享 / 每 lane finally 跨节点丢失）, 非分片场景宿主仍可自愿选择拆节点。

### 5.9 GROUP 级段（v1.3 已落地）

**声明形态**（v1.5: 段/开关写在 `group:` 段内, 与 flow 级 setup/flow/setdown 三段同形; 与写在 `group:` 同级等价。段值 = 内联动作序列或叶子子节点引用, 与节点级完全同构）:

```yaml
nodes:
  spaceCheck:
    config: { itemTable: t_validate_space }
    group:
      setup:                    # 组前置段: 包住整个子节点序列（fetchSource + diffPerShard 之前）
        - id: lockItemTable
          handler: spaceLock
      flow: "fetchSource,diffPerShard"
      nodes: { ... }
      setdown:                  # 组收尾段: 子节点序列无论成败都跑（finally: 段内不收敛/不查取消闸门/恒串行）
        - id: unlockItemTable
          handler: spaceUnlock
```

同一字段只写一处: 写进 `group:` 段内（推荐）或与 `group:` 同级（旧写法）等价; 两处都写 = 歧义, 装载期拒（AU204235）。

**走查**: `LayerWalker.walkGroupCircuit` = 组 setup 段 → 子节点序列（`walkChildren` SERIAL 档, 逐个子节点）→ 组 setdown 段（finally）; 与节点回路**共用同一份三段骨架**（`runSectionedCircuit`）。与节点回路的差异两条:

- 组没有 MAIN 动作段——段行挂**组自己的 nodeInstId**（lane/shard 恒 null, 段值 setup/setdown）; 组容器行收尾**不走查层落**, 三段聚合交 `NodeDriver.finishNode` 一次写状态与 result 再发 after;
- setup 段坏 → 子节点序列整体跳过且**不递归 drain**（子节点"走查到才建", 手里没有剩余行; 未开始的子节点留给重试）; setdown 照跑。

**重臂**（§5.6.1 判据的组级镜像, 主体待跑换成**直属子节点**）:

- 判据: 组 setup 段存在非终态行 **或** 子节点序列待跑（存在非终态子行, **或** 已物化子行数 < 表达式数 = LAZY 尚有子节点未物化, 覆盖"setup 后、首个子节点物化前就被停掉"的零行窗口）**且** 组 setdown 段存在 success 行 → 组 setup/setdown 的 success 行成对复位（只动 status, 不动 result）;
- 只动本组 nodeInstId 下的段行, **不复位任何子节点**——已成功的业务子节点不被重跑; 子行复位（失败续跑）归 `retryProcess`/`retryAction` 既有口径（retryAction 复位祖先链, 故直属子行能反映后代重试）;
- 组 `repeatSegments: true` 同节点级（每趟无条件复位两段全部终态行, 且不查主体）; 单边声明不触发成对重臂;
- 嵌套组各自闭环: 外层组 setup -> 内层组完整回路 -> 外层组 setdown; 重臂按各自 nodeInstId 独立判, 同定义多实例互不串。

**口径不变**: 回退/重放只扫 main（组段行天然排除, FR-NSD-13）; 进度统计只算 main、组段行进 items 带 `segment`（FR-NSD-15）; 组 `when` 假 = 整组 SKIPPED（段一个不跑、子节点不物化）。

### 5.10 段不跟随分片（v1.3 已落地）

**问题**: 分片叶子默认"每 lane 一个完整三段回路"（§5.5）适配"每分片一套主从"的场景; 但存在"整节点共享的公共现场"——如全局暂停/恢复公共链路的编排动作, 按 lane 各跑一遍既重复又有并发窗口。声明 `segmentsFollowShard: false` 即切"整节点各一次"那一档。

**语义**（`LayerWalker.walkSharedSegmentLanes`, 骨架复用 `runSectionedCircuit`——与逐 lane 回路、组回路同一份）:

```
进入节点回路（分片, 段不跟随分片）
  ├─ 节点 setup 段   : 整节点一次（段行 lane_id 恒 NULL; 恒串行/段内按行冻结策略收敛/查取消闸门）
  ├─ 主体            : 各 lane 只跑自己的 MAIN 段（lane 间串/并档不变; action 级段仍每 lane 各跑一遍）
  ├─ 节点 setdown 段 : 整节点一次（finally: 恒不收敛/不查取消闸门）
  └─ 节点行收尾      : 共享三段聚合落节点行（I1 同款: 共享 setdown 未跑完前不落终态）
```

- 让渡/取消/收敛/finally 口径与既有段逐字一致（换的只是宿主: 段容器从 lane 换成节点自身）; setup 段坏 -> 各 lane 已物化 MAIN 行 drain 留痕（不补建）;
- **lane 行口径**: 各 lane 只反映自己的主体结果（共享 setdown 失败只压节点行, 不反压已成的 lane）;
- **空分片**: 首驱（段行/lane 行一行都没有）整段跳过——与逐 lane 口径一致（"空分片不存在回路, 段不执行"）; 反之已开过工必须把已施加的现场收尾完——**重入时哪怕本次分片键解析为空也要跑共享 setdown**, 不能靠空 keys 静默成功;
- **重臂**（§5.6.1 判据的共享档镜像）: 段行按 `lane_id IS NULL` 收窄; 主体待跑的取证换成**跨 lane**——本轮任一 lane 未物化（lane 行缺）或其 MAIN 行待跑（行不齐/存在非终态行, §5.6.1 缺行判据同口径）即视为待跑; `repeatSegments` 同口径（每趟无条件复位共享段行）;
- **段内上下文**: 共享段无 lane 上下文（读不到 shardKey——共享段本就不属于某一分片; 需要分片键的段动作请保持跟随分片）;
- **约束**: 仅节点级段适用; 与 action 级段可共存（级段随主体逐 lane, 不受本开关影响）; action 级段跨 lane 共享（"按阶段跨 lane 推进"）会改变 lane 既有执行顺序（`laneExecution: SERIAL` 的语义）, 列为后续候选, 本版不引入;
- **回退/进度**: 段行（segment≠main）照旧不参与补偿/重放; 进度统计只算 main（FR-NSD-15 零改动）; 节点行终态仍在共享 setdown 之后落（finishNode 同值覆盖）。

---

### 5.11 组级段 = 叶子子节点引用（v1.4 已落地）

**问题**: 组级段（v1.3）是内联动作——段行 `lane_id/shard_key` 恒 NULL, 既不能分片也读不到 payload。"段也要只在某几台机器上执行"（如只停主库的复制线程）在段里表达不出来; 而把每一步做成组内子节点又丢了"改—还原"的段语义。

**语义**（段值形状分流 + `LayerWalker.walkGroupNodeSegments`, 骨架复用 `runSectionedCircuit` 同一份核心）:

```
组声明: setup / setdown 的值为节点引用**块列表**(与流程级 setup/setdown 同格式;
         写在 group: 段内或与 group: 同级等价):
         setup:
           - stopSql
         setdown:
           - startSql
装载期: 引用重绑定为提升键(g.stopSql)写回 NodeDef(存快照)
       + 段节点 Leaf 注入 Group 子项头尾 -> GROUP:g(g.stopSql,g.c1,g.startSql)
驱动期: 组回路三段
  ├─ setup 段   : 子节点切片 [0, setupEnd) 驱动(收敛吃组行策略 / 查取消闸门)
  ├─ 主体        : 切片 [setupEnd, setdownStart) 驱动(walkChildren SERIAL 同款)
  ├─ setdown 段 : 切片 [setdownStart, end) 驱动(finally: 恒不收敛 / 不查闸门)
  └─ 组行收尾    : 三段 exit 聚合原样返回, 由 NodeDriver.finishNode 落状态
```

- **同一批子行**: 段节点与主体同 index 对齐物化（LAZY 建一个走一个 / EAGER 预建对齐; 段与主体分界由 `setupRefs/setdownRefs` 与子项头尾**同键前缀/后缀**切出）; 段节点自动获得叶子的全部能力——`shard`/`laneExecution`/`errorPolicy`/`when`/监听器, 以及**它自己的节点级三段回路**;
- **运行语义与内联段逐字一致**: 段坏 → 主体整体跳过（段是节点引用: 下标对齐会把主体行补齐为 CREATED 留给 retry, 与流程级段根同口径, 不递归 drain）、setdown 照跑; 让渡 → 收尾不跑、组行不落终态; 段节点自身让渡同样按让渡退出;
- **重臂**（§5.6.1 判据的节点段镜像）: 复位对象从段行换成**段子节点行 + 其 MAIN 动作行（含 per-lane）**（复用 `reArmNodeRows`——与流程级段根同款; 段节点自己的节点级段由它自己的回路入口再臂）; 主体待跑 = 主体切片有非终态行或子行不齐（缺行判据）; `repeatSegments` 每趟无条件复位段子行;
- **口径差异（刻意）**: 段节点的动作行 `segment=main` → **参与回退收集与进度分母**（与内联段行被排除不同）; 需要"回退时自动撤销"的段动作可直接获得该语义, 但需防与 setdown 段重复执行;
- **手写 GROUP 退化**: 手写 `GROUP:名(...)` 形状与声明引用不符时刻不出段（前缀=0）——整段退化为按主体驱动, 不误伤;
- **兼容**: 内联动作段（v1.3 口径）原样保留, 同一份声明按值形状二选一（AU204230 拒混用）。

---

## 6 周边交互

### 6.1 回退与补偿（FR-NSD-13）

- `RollbackService` 收集成功 action（`collectSucceededActionsDesc`）**必须**只取 `segment='main'` 的行（含 lane 下）；
- 理由：段行多为不可补偿的编排辅助（暂停/恢复类，默认 `compensable(ctx)=false`）；若纳入，"遇不可补偿即 `blocked` 并停"（FR-RBK-03）会让**每次人工回退都断在段行上**，破坏现有回退路径。排除后与现状（所有行都是 main）逐字兼容；
- 复议：未来若需补偿段动作，优先做法是"把该动作放主段"；再考虑加显式声明开关。

### 6.2 干预（FR-NSD-14）

`InterventionService` **零改动**：retry 复位 + 重入续跑天然覆盖段行；PAUSE/STOP 语义见 §5.3。

### 6.3 进度视图（FR-NSD-15）

- items 明细**必须**新增 `segment` 字段；
- total/done/percent/counts **应当**只统计 `main` 段（"一个节点 = 一个校验项"的心智与现状分母不变；段行在明细中可见）；
- 复议：若希望"暂停/同步/恢复"以三格进度展示，视图侧按 segment 分组渲染即可（引擎已给足字段）。

### 6.4 自动回退

段行 failed → 回路/节点终态 failed → `autoRollback: true` 照常触发；由于 §6.1 的收集口径，自动回退不扫段行——语义自洽。

---

## 7 实现落点

### 7.1 文件清单

| 层 | 文件 | 改动 |
| --- | --- | --- |
| 定义 | `core/define/NodeDef.java` | +`setup`/`setdown` 字段（`List<ActionDef>`） |
| 定义 | `core/define/DefinitionLoader.java` | `parseNode` 解析段；`requirePureContainer` 扩展；段 id 唯一校验 |
| 模型 | `core/model/ActionSegment.java`（新，建议） | setup/main/setdown |
| 实体 | `domain/ActionInstEntity.java` | +`segment` 字段 |
| 映射 | `mapper/ActionInstMapper.java` + `META-INF/mapper/process/ActionInstMapper.xml` | insert 落 segment；findInContainer 段过滤；自然键含 segment |
| 走查 | `core/engine/LayerWalker.java` | 回路三段模板（含 I1 收尾）；段级执行（现有 walkActions 逻辑 + 段过滤） |
| 走查 | `core/engine/LayerWalk.java` | 接口新增回路入口（ActionKind 声明意图用） |
| 档案 | `core/engine/ActionKind.java` | `drive` 声明"回路三段意图"；`materializeAction` 段参 |
| 档案 | `core/engine/ActionRows.java` | `ensureActionRow` 段参；段过滤定位 |
| 回退 | `core/engine/RollbackService.java` | 收集只扫 main |
| 进度 | `core/engine/ProgressService.java` | items + `segment`；统计 main |
| DDL | `resources/db/schema.sql` | segment 列、uk / 索引调整 |
| 示例 | `resources/definitions/db-migration.yml`（可选） | 补段示例（注释展示"暂停/迁移/恢复"） |
| 测试 | 见 §8 | |

零改动：`ActionRunner`、`ExecutionEngine`、`InterventionService`、`ObserverEngine`、`ListenerEngine`、`RestoreService`。

### 7.2 走查结构变化（要点）

- 三段编排**归 LayerWalker**（与 `walkRoots` 同构，ActionKind 只声明意图）；
- 原 `walkActions` 收窄为"单段执行"：加段过滤、去掉"写容器行状态"职责；
- `laneMapper.setStatus` / `nodeMapper.setStatus` 上移到回路收尾点（FR-NSD-09 不变式 I1）。

### 7.3 v1.1 增量落点（待评审后实施）

| 层 | 文件 | 改动 |
| --- | --- | --- |
| 定义 | `core/define/NodeDef.java`、`DefinitionLoader.java` | `repeatSegments` 字段解析 + 死配置校验（AU204217, §3.2） |
| 走查 | `core/engine/LayerWalker.java` | `walkCircuit` 入口新增"段重臂"前置步骤: repeatSegments 为 true → 无条件复位; 否则按成对重臂判据复位（约 15 行, 抽私有方法）; 三段走查本体零改动 |
| 走查 | `core/engine/LayerWalker.java`（顶层） | `walkRoots` 入口新增"流程级成对重臂"前置步骤（§5.6.3）: 同判据落在根行上; 复位 = 节点行 + 该节点 main 动作行（含分片每 lane, 经 `actionRows.reArmSegment`; 实现期修正: 只复位节点行会在节点回路里被按行幂等跳过而空转）; 顶层走查本体零改动 |
| 走查 | `core/engine/ActionRows.java`（实现归 `ActionKind`, 叶子的建行知识所有者） | 新增窄方法（如 `reArmSegment(nodeId, laneId, segment)`）: 段行 CAS 复位 CREATED, 落库口径同 `InterventionService.resetAction`（不动 result） |

零改动: DDL（无新列）、`ActionRunner`、`NodeDriver`、`InterventionService`（RESETTABLE 不动——重臂发生在驱动期回路入口）、`RollbackService`、`RestoreService`、`ProgressService`, DSL 其余语法。

### 7.4 v1.2 增量落点（待评审后实施）

| 层 | 文件 | 改动 |
| --- | --- | --- |
| 定义 | `core/define/ActionDef.java` / `DefinitionLoader.java` | +`setup`/`setdown` 字段与同构解析; 成对/嵌套/段动作 id 合并唯一/并行互斥校验（AU204220~224） |
| 模型 | `core/model/ActionSegment.java` | +`ACTION_SETUP`/`ACTION_SETDOWN`（小写值 `action-setup`/`action-setdown`） |
| 映射 | `mapper/ActionInstMapper.java` + XML | **零改动**（段行复用既有语句: 段值收窄天然分流; 无新列/新查询） |
| 走查 | `core/engine/LayerWalker.java` | 声明级段的动作走段电路（定义驱动入口: 级 setup 段 -> 主体 -> 级 setdown 段）+ 级段重臂 |
| 走查 | `core/engine/ActionRows.java` / `ActionKind.java` | 建段行/定位零改动（复用已带 segment 参的 `ensureActionRow`/`findInContainer`）; 新增窄写方法 `reArmRows(rows, allTerminal)`（级段重臂按定义成员收窄的复位） |
| 进度 | `core/engine/ProgressService.java` | **零改动**（`action-*` 段行照常进 items、按物化顺序天然可读; 统计口径不变 main 才计数） |
| DDL | — | **零 DDL**（无新列/无 uk/无索引变更; segment 值域扩展走既有 VARCHAR 列） |
| i18n | `orchestrator-messages.properties` | AU204220~224 |

零改动: DDL、`uk_node_def_shard`、`ActionInstEntity`/`ActionInstMapper`（段行复用既有语句与列）、`ProgressService`、`ActionRunner`、`RollbackService`/`RestoreService`（段值天然排除段动作）、`InterventionService`（按状态族复位天然覆盖段行）。

### 7.5 v1.3 增量落点（GROUP 级段, 已落地）

| 层 | 文件 | 改动 |
| --- | --- | --- |
| 定义 | `core/define/DefinitionLoader.java` | 段校验统一推迟到**展开后**按用途分类（`validateSegmentsByUsage`）: GROUP 用途走 `validateGroupSegments`（形状 + 跨段 id 唯一 + 同段有效键唯一 + 死开关）; 叶子用途走 `validateSegments`; `requireGroupShape`/`requirePureContainer` 拆分（命名并行仍纯容器）; 组级段/开关两处声明等价（v1.5 `groupScoped`: `group:` 段内 / 与 `group:` 同级）, 同一字段重复拒（AU204235） |
| 定义 | `core/define/NodeDef.java` / `core/model/ActionSegment.java` | 仅语义注释更新（无新字段/新枚举值: 组段复用既有 setup/setdown 字段与段值） |
| 走查 | `core/engine/LayerWalker.java` / `LayerWalk.java` | 新增 `walkGroupCircuit`（组回路入口）; 三段骨架抽为 `runSectionedCircuit`（叶子/组共用）; `reArmSegments` 的主体待跑改为惰性 `BooleanSupplier`; 新增 `reArmGroupSegments`/`childrenPending`（含缺行判据） |
| 档案 | `core/engine/GroupKind.java` | guard 后转入 `walkGroupCircuit`（无段组在走查引擎里零开销回落原 `walkChildren` 路径） |
| i18n | `orchestrator-messages.properties` | AU204204 文案更新（GROUP 可声明段/开关, 命名并行仍纯容器）; +AU204225/AU204226 |
| 模板 | `resources/definitions/flow-template.yml` | 四层段速查 + spaceCheck 组段示例 |
| 测试 | 见 §8 | |

零改动: DDL/映射/`ActionSegment` 枚举值（组段行复用既有 `setup/setdown` 值与语句）、`ActionRunner`、`NodeDriver`、`InterventionService`、`RollbackService`、`RestoreService`、`ProgressService`。

### 7.6 v1.3 增量落点（段不跟随分片, 已落地）

| 层 | 文件 | 改动 |
| --- | --- | --- |
| 定义 | `core/define/NodeDef.java` / `DefinitionLoader.java` | +`segmentsFollowShard` 字段与派生视图（未声明/true = 跟随分片）; 死配置/非法值校验（AU204227/228/229）; 容器拒（AU204204 扩展） |
| 走查 | `core/engine/LayerWalker.java` / `LayerWalk.java` | `walkLanes` 按声明分档: 默认每 lane 完整三段回路; `segmentsFollowShard: false` 走 `walkSharedSegmentLanes`（共享段 + 跨 lane 主体 + 空分片短路）; lane 序列循环抽为唯一一份 `walkLaneSequence`; 新增 `walkLaneMain`/`drainSharedMain`/`sharedMainPending`/`sharedCircuitStarted` |
| i18n | `orchestrator-messages.properties` | +AU204227/AU204228/AU204229 |
| 模板 | `resources/definitions/flow-template.yml` | ④ migrateData 注释示例 + 四层段速查补充 |
| 测试 | 见 §8 | |

零改动: DDL/映射/实体、`ActionSegment`、`ActionKind`（分片入口与物化零改动——共享段行经既有 `ensureActionRow(lane=null)` 物化）、`ActionRunner`、`NodeDriver`、`InterventionService`、`RollbackService`、`RestoreService`、`ProgressService`。

---

### 7.7 v1.4 增量落点（组级段=节点引用, 已落地）

| 层 | 文件 | 改动 |
| --- | --- | --- |
| 定义 | `core/define/NodeDef.java` / `DefinitionLoader.java` | +`setupRefs/setdownRefs` 字段（随快照往返）; `parseNode` 按值形状分流（AU204230）; `buildGroup` 引用重绑定为提升键 + 段节点注入 Group 子项头尾 + 引用域校验（AU204232/233/225）; 叶子用途拒引用（AU204231）; `hasSegmentDecl`/`validateGroupSegments` 纳入引用 |
| 走查 | `core/engine/LayerWalker.java` | `walkGroupCircuit` 分流节点段路径; 新增 `walkGroupNodeSegments`/`driveChildSlice`/`setupPrefixLen`/`setdownSuffixLen`/`reArmGroupNodeSegments`/`hasNonTerminalRows`/`hasSuccessRows`; `runSectionedCircuit` 抽为三段核心（Supplier 注入, 内联段重载委托）; `reArmRoots` 改名 `reArmNodeRows`（顶层段根与组级段子节点共用） |
| i18n | `orchestrator-messages.properties` | +AU204230/AU204231/AU204232/AU204233 |
| 模板 | `resources/definitions/flow-template.yml` | 四层段速查改"组级=节点引用或内联动作" + spaceCheck 注释示例 + 速查补"组级段两种形状" |
| 测试 | 见 §8 | |

零改动: DDL/映射/实体, `ActionSegment`, `ActionKind`, `Materializer`（注入后的 children 序天然对齐, EAGER 预建照常）, `ActionRunner`, `NodeDriver`, `GroupKind`, `InterventionService`, `RollbackService`, `RestoreService`, `ProgressService`。

### 7.8 v1.5 增量落点（组级段声明位置, 已落地）

| 层 | 文件 | 改动 |
| --- | --- | --- |
| 定义 | `core/define/DefinitionLoader.java` | 新增 `groupScoped`（setup/setdown/repeatSegments 取值: `group:` 段内 / 与 `group:` 同级两处等价, 同一字段重复拒 AU204235）; `parseNode` 三个组级字段统一走它; 删除 `rejectNestedSegmentFields`（原“段写进 group: 段内拒 AU204225”） |
| i18n | `orchestrator-messages.properties` | +AU204235 |
| 模板 | `resources/definitions/flow-template.yml` | spaceCheck 组段示例改“写在 group: 段内（与 flow 级三段同形）” + 速查补声明位置 |
| 测试 | `DefinitionLoaderTest` / `EngineGroupFlowTest` | 段内声明（引用 + 内联动作 + repeatSegments）解析/注入/快照往返; 两处同字段声明拒; E2E 段内声明走同一组回路 |

零改动: NodeDef/走查/物化/DDL/映射/服务层（声明位置在解析期归一, 驱动期拿到的仍是同一份 NodeDef）。

---

## 8 测试计划

| 组 | 用例 |
| --- | --- |
| DefinitionLoaderTest | 段解析（列表 / 空段同口径）；容器声明段拒（AU204204 扩展）；段 id 冲突拒（AU204214）；定义快照往返保留段 |
| LayerWalkerTest | 三段顺序；setup 坏 → 主段跳 + drain → setdown 跑；取消 → setdown 恒跑；setdown 段内不收敛；重入跳过；容器行收尾时序（I1：主段后不得落终态） |
| Mapper 组 | insert 落 segment；findInContainer 段过滤；uk 扩列行为；存量行默认 main |
| E2E | ① 主段失败 → setdown 照跑（暂停/同步/恢复顺序断言）② 崩半途（主段成、setdown 未跑）retry 补跑 ③ setdown failed → retry 重跑成功 → 节点 success ④ 回退不触段行 ⑤ 分片两 lane 各三段闭环、`${lane.shardKey}` 可读 |
| 兼容回归 | 全部既有测试零改动通过（无段定义逐字不变） |
| v1.1 重臂组 | 成对重臂: main/setup 失败 + setdown 成 + retry → setup/setdown 各重跑一次（频次与段序断言）; 判据矩阵（setup/main 终态否 × setdown success 否）; 重臂中途崩溃幂等; 分片只重臂该 lane; 流程级对等重臂（同矩阵落根行 + 闭环）; repeatSegments: 每趟重跑（含"崩在 setdown 前"窗口与顶层）、死配置拒（AU204217/AU204218）、非法值拒（AU204219）、默认 false 与 v1.0 逐字一致 |
| v1.2 级段组 | 成对同挂/单边拒/段动作带标记/段动作 id 冲突/并行互斥校验（AU204220~224）; 级段顺序与**中段还原**（收尾不等主段全成）; finally（主体失败 / 级 setup 段坏 / 让渡）; 定义驱动入口的收尾补跑; 级段成对重臂矩阵; 分片每 lane; 与节点段共存; 快照往返保留段声明 |
| v1.3 组级段组 | 声明位置/用途分类校验（段不进 flow、组/叶子混用拒 AU204226、命名并行仍拒）; 组回路顺序（setup -> 子节点逐个 -> setdown）与"组行收尾归 finishNode"; setup 坏跳子节点不递归 drain; setdown 段内不收敛; guard 假整组跳过; 成对重臂（子行非终态/缺行触发, 已成功兄弟不重跑）; repeatSegments 与崩溃窗口对比; 嵌套组 + 分片孙节点递归包裹; 同定义两实例重臂隔离（visit_count）; 回退/进度口径不变 |
| v1.3 段不跟随分片组 | 开关解析/归一化/快照往返; 死配置（AU204228/AU204229）与非法值（AU204227）、容器拒（AU204204）; 共享段整节点各一次（段行 lane 恒 NULL、lane 行只差 main; 串行与并行 lane 两档）; finally（主段挂 / setup 挂）; 崩溃补跑只补 setdown; 共享成对重臂与 repeatSegments; 与 action 级段共存（级段仍逐 lane）; 空分片首驱不执行段、重入遇空分片仍收尾; 缺行重臂（取消停在动作边界: 全 success 前缀 + 未物化尾巴, 共享与逐 lane 两档）; 回退/进度口径不变 |
| v1.4 组级节点段组（DefinitionLoaderTest + EngineGroupFlowTest） | 引用重绑定/注入/快照往返（flow 文本含段节点）; 形状拒（同段混用/跨段不一致 AU204230）、叶子用途拒引用（AU204231）、引用域拒（组外/嵌套组 AU204232、同段重复 AU204225）、与主体重叠拒（AU204233）; 顺序（段节点 → 主体 → 段节点 → 后续根）与"组容器零段行"（段是子行）; setup 段节点失败 → 主体不驱动（行补齐 CREATED）、setdown 照跑; 主体让渡 → setdown 不跑、组行不落终态、重入段节点幂等跳过; 成对重臂（段节点行+动作行复位, 成功主体不重跑）; repeatSegments 每趟重跑; 段节点自带分片（逐 lane 一趟 + lane/动作行落库）; 回退口径（段节点动作进补偿, 与内联段行不同） |
| v1.5 组段声明位置组 | `group:` 段内声明 setup/setdown/repeatSegments（内联动作与节点引用两种形状）解析/注入/快照往返; 与同级声明等价（E2E 同一条组回路）; 同一字段两处都写拒（AU204235） |

---

## 9 默认口径与可复议项

1. 段恒串行，不跟 `actionExecution` 档（FR-NSD-07）；
2. setdown 段不查取消闸门（FR-NSD-06，与顶层 setdown 一致）；
3. 段行不参与回退/重放（FR-NSD-13）；
4. 进度统计只算 main 段（FR-NSD-15）；
5. 段 id 与主段合并唯一（FR-NSD-03）；
6. 错误码 AU204214 / AU204204 扩展的编号待评审确认（注: 实装中段 id 唯一为 AU204215、段缺主段为 AU204216、AU204214 已归 autoRollback 校验——一并随本次评审校正）。
7. （v1.1）成对重臂为**默认语义**（成对声明 setup/setdown 即自动获得, 无开关; 节点级与流程级同款）; 判据取"setup/main 非终态行 + setdown success 行"双条件（setup 一侧覆盖"setup 失败且主段未物化"路径）——不收窄、不放宽（放宽会打破"三段全成零重复执行"口径）;
8. （v1.1）`repeatSegments: true` 为显式强语义（节点级/流程级同名）, 开启即承担段动作幂等责任; 两段均未声明时拒绝装载（节点级 AU204217 / 顶层 AU204218）, 非法值拒（AU204219）。
9. （v1.1）流程级同类盲区已随本增量落地为"流程级对等重臂"（§5.6.3, FR-NSD-18）: 同判据落在根行上（setdown 段根行存在 success + setup/flow 段存在非终态行）; 顶层 `repeatSegments` 为同款显式开关。
10. （v1.2）级段声明形态 = 同动作双挂 setup/setdown（§5.8.1）; 拆分式多动作级段为后续版本候选; 主体动作 `when` 取直白口径（§5.8.4）, "整段开关"留复议;
11. （v1.2 复议）action 级段不设 repeatSegments; 不支持段动作携带标记（嵌套）; 拆分式多动作留后续版本候选。
12. （v1.3/v1.5）GROUP 级段 = 内联动作序列或叶子子节点引用, 可写在 `group:` 段内（推荐, 与 flow 级 setup/flow/setdown 三段同形）或与 `group:` 同级; 包住**整个子节点序列**; 组仍不可声明 handler/actions/shard/执行档, 命名并行不开放段; 重臂的主体待跑按**直属子节点**取证（含缺行判据）; 内联段行不参与回退/重放、不进进度分母。
13. （v1.3）段不跟随分片仅**节点级**提供（action 级段跨 lane 共享会改变 lane 既有执行顺序, 列为后续候选）; 共享段无 lane 上下文（读不到 shardKey）; 空分片首驱不执行段、重入遇空分片仍收尾到位（不能靠空 keys 静默成功）; 与 action 级段可共存（级段仍逐 lane）。
14. （v1.4）组级段**新增**"叶子子节点引用"形态（值形状分流, §5.11）——覆盖第 12 条中"不引入组级节点引用列表"的旧取舍（用户需求: 段要能单独指定节点集合）; 段节点动作行是 main ⇒ 参与回退与进度分母（与内联段行不同, 刻意口径）; 段引用域限本组直接声明的叶子子节点（无外层节点/嵌套组引用）; 引用注入 Group 子项是"快照可复现"的必要条件（`roots` transient, 借出副本按 flow 字符串重解析）。

---

## 10 验收标准

| 编号 | 判据 |
| --- | --- |
| AC-NSD-01 | 顺序：pause → sync → resume；主段失败时 resume 仍执行、节点 failed |
| AC-NSD-02 | setup 失败：主段未执行（drain 留痕）、setdown 已执行 |
| AC-NSD-03 | stop：主段 drain、setdown 执行、回路终态聚合为 stopped |
| AC-NSD-04 | 崩溃恢复矩阵（§5.6）逐行可复现 |
| AC-NSD-05 | 分片：2 lane → 每 lane 各三段、段行 lane 归属正确 |
| AC-NSD-06 | 回退：带段流程的人工回退行为与无段流程一致（段行不 blocked） |
| AC-NSD-07 | 兼容：存量定义/存量行的走查、进度、回退输出与改造前一致 |
| AC-NSD-08 | 快照：定义 JSON 往返后段声明不丢失 |
| AC-NSD-09 | 成对重臂：main 失败 + setdown 已 success + retry → setup 重跑（现场重施）→ main 续跑 → setdown 重跑（重新收尾）；§5.6 主矩阵前四行与 §5.6.1 矩阵其余行行为逐字不变 |
| AC-NSD-10 | repeatSegments：true 时每趟驱动段重跑（含"崩在 setdown 前"窗口）；两段均未声明拒绝装载（AU204217）；默认 false 行为与 v1.0 一致 |
| AC-NSD-11 | 分片：重臂与 repeatSegments 均 per-lane 生效, 其他 lane 的行不受影响 |
| AC-NSD-12 | 流程级对等重臂：流程级 setup 成功 + flow 失败 + 流程级 setdown 已还原 + retry → setup/setdown 根节点成对重跑；崩在 setdown 前等三类场景不触发；顶层 repeatSegments 每趟驱动重跑 |
| AC-NSD-13 | action 级段顺序：配置 → 主体 action → **立即**收尾 → 段外动作照常（中段还原, 不等主段全部完成） |
| AC-NSD-14 | 级段 finally：主体失败 / 级 setup 段坏 → 级 setdown 段照跑；让渡 → 收尾不跑、重入续跑 |
| AC-NSD-15 | 级段崩溃恢复：崩在段收尾前 → 定义驱动入口只补收尾；主体失败 + 收尾已成 + retry → 成对重臂；矩阵其余行不误触发 |
| AC-NSD-16 | 分片：action 级段在每 lane 回路内独立成环（lane 参数连贯、段行挂本 lane）；与节点级段共存互不干扰 |
| AC-NSD-17 | 校验：单边标记 / 段动作携带标记 / 段动作 id 冲突 / 并行互斥 装载期拒（AU204220~224）；快照往返保留段声明 |
| AC-NSD-18 | 组级段（v1.3）: 段包住整个子节点序列（顺序断言）; setup 坏 → 子节点不物化、setdown 照跑; 子节点坏+setdown 成+retry → 组 setup/setdown 成对重跑且成功子节点不重跑; 缺行（首个子节点物化前被停）同样触发重臂; repeatSegments 每趟重跑; 两实例重臂互不串; 组段行不进回退与进度分母 |
| AC-NSD-19 | 段不跟随分片（v1.3）: `segmentsFollowShard: false` -> setup/setdown 整节点各一次（段行 lane 恒 NULL）, 主体仍逐 lane; 主段失败/setup 失败 -> 共享 setdown 照跑; 崩溃窗口 retry 只补收尾; 共享成对重臂只复位共享段行（跨 lane 取证）; repeatSegments 每趟重跑; 空分片首驱段不执行、重入遇空分片仍收尾完; action 级段仍每 lane 各跑一遍; 回退/进度口径不变 |
| AC-NSD-20 | 组级节点段（v1.4）: 段值形状分流（字符串=引用/map=内联）且混用拒; 引用重绑定为提升键并随快照往返（flow 文本含段节点, 副本重解析后切片复现）; 顺序（段节点→主体→段节点→后续根）; 组容器零段行、段是组内子行; setup 段节点挂 → 主体不驱动、setdown 照跑; 主体让渡 → setdown 不跑、重入段节点幂等跳过; 成对重臂/ repeatSegments 以段子节点行为复位对象; 段节点分片逐 lane 一圈; 段节点动作进回退（与内联段行不同） |
