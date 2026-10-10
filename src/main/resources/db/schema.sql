-- ============================================================
-- 通用逻辑编排引擎 DDL  (MySQL 5.7+/8.0)
-- 枚举持久化总口径(全仓统一): Java 常量名大写是标识, 落库/比较/SQL 字面量一律用**小写** getStatus(),
--   反查用枚举的 of(String)(大小写不敏感), 禁用 valueOf(对小写抛异常)。适用于:
--   Status(运行态) / DefinitionStatus(发布态) / NodeType(节点类型) / ErrorPolicy(错误策略) / RollbackStatus(补偿态)。
-- 运行态(created -> pending -> running -> 终态): created/pending/running/success/failed/skipped/stopped/paused/suspended
--   终态: success / failed / skipped / stopped
--   暂停: paused(人工可恢复) / suspended(系统挂起,预留)
-- 存量库若仍有上述枚举的大写行(如 FAIL_FAST / COMPENSATING), 需 LOWER() 刷一遍, 否则认领/比较失配。
--
-- 主键约定(本版改造):
--   每表 auto_id BIGINT AUTO_INCREMENT 为物理主键, 应用不直接使用;
--   原业务 id 列(*_id)改 VARCHAR(20), 由应用生成(IdGenerator: 每类业务键各一个序列号生成器 Bean), 降为唯一索引;
--   action_inst / node_inst 的声明顺序由 auto_id 升序承载。
--   表间外键一律用 VARCHAR(20) 业务键。
--
-- 表命名空间(嵌入式组件不假设裸表名可用): 7 张表统一 process_ 前缀
--   (process_definition / process_inst / process_node_inst / process_lane /
--    process_action_inst / process_rollback_record / process_fragment);
--   列名与索引名不加前缀(MySQL 索引名仅表内唯一)。消费者撞名时改
--   ProcessEngineConfig.tablePrefix, mapper XML 经 ${orch.table.*} 变量整体换名:
--   7 张**一律** = 前缀 + 表名, 上表名字里的 "process" 就是默认前缀本身, 没有两张固定名特权。
--
-- 审计列(每表统一): creator_id / updator_id VARCHAR(20) —— 用户身份, 与业务键同宽;
--   creator_id = 建行时写流程创建人(创建流程时由调用方传入 userId, 落 process_inst.creator_id 并进 ProcessContext.userId;
--     各级实例行由物化器取同一值, 所以驱动线程/子线程在哪建行都不影响归属);
--   updator_id = 每次 UPDATE 由调用方从上下文显式下传(驱动期 = 流程创建人, 干预期 = 操作人);
--     传 null(如调度器无人值守认领) 则不刷该列, 保留原值。引擎不依赖 ThreadLocal。
--
-- config/result/vars 一律以 JSON **文本**存储(不用 DB 原生 JSON 类型),
-- 引擎用 Jackson 在应用层解析/回写为 Map/List。失败/跳过信息不设独立列, 写入 result 的保留键
-- __error / __skipReason(#14); __error 存一句失败摘要(语境 + 定位 + 异常类名: 消息),
-- action/node 行各自记自己那条, process_inst 只记本次驱动的**首条**(坏终态才写, 跑成功会摘掉残留)。
-- 实例表只存运行期数据: 监听器链、子层/分支等可由
-- 定义(process_inst 钉死 def_version, 定义不可变)推导的信息不落实例。
--   例外(展示用快照): node_inst.node_def_id/name 与 action_inst.name 虽是定义可推导, 但为让进度/视图**直接读行**
--   就能拿到"哪个定义节点 + 人可读展示名"(不必回查定义、也不必把自述键塞进 config), 物化期烙一次;
--   定义不可变, 故这份快照与回查定义同源, 不产生漂移。匿名控制节点(PARALLEL/IF)无定义 -> node_def_id/name 为 NULL, 视图回落业务键。
--
-- 各层**落库的配置桶统一叫 config**(流程层 = 创建输入, 与 node/lane/action 同名同语义,
--   ${config.*} 因而能沿父链上溯到流程级); **params 不建列、不持久化**: 它只是上下文里的运行期参数桶。
-- ============================================================

-- 流程定义: 一表多版本(原 header 表已删, version 表改名 process_definition)
CREATE TABLE process_definition (
  auto_id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键, 应用不用',
  process_def_id   VARCHAR(32)  NOT NULL COMMENT '业务键(逻辑定义标识, 多版本共享)',
  version          INT          NOT NULL,
  name             VARCHAR(128) NOT NULL,
  definition       MEDIUMTEXT   NOT NULL COMMENT 'YAML/JSON 整棵定义树, 不可变',
  status           VARCHAR(16)  NOT NULL COMMENT '发布态: DefinitionStatus.getStatus() 小写(draft|published|archived)',
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by       VARCHAR(64),
  creator_id       VARCHAR(20)  COMMENT '创建用户(与业务键同宽)',
  updator_id       VARCHAR(20)  COMMENT '最后更新用户',
  PRIMARY KEY (auto_id),
  UNIQUE KEY uk_def_version (process_def_id, version),
  KEY idx_def_published (process_def_id, status, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- fragment 预留(当前单流程+方言+IF, 不依赖 fragment/overlay)
CREATE TABLE process_fragment (
  auto_id      BIGINT       NOT NULL AUTO_INCREMENT,
  fragment_id  VARCHAR(32)  NOT NULL COMMENT '业务键',
  name         VARCHAR(128) NOT NULL,
  type         VARCHAR(16)  NOT NULL COMMENT 'SETUP|SUBFLOW|LISTENER_GROUP',
  version      INT          NOT NULL,
  body         MEDIUMTEXT,
  status       VARCHAR(16),
  creator_id   VARCHAR(20)  COMMENT '创建用户',
  updator_id   VARCHAR(20)  COMMENT '最后更新用户',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (auto_id),
  UNIQUE KEY uk_fragment (fragment_id),
  UNIQUE KEY uk_name_version (name, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE process_inst (
  auto_id           BIGINT      NOT NULL AUTO_INCREMENT,
  proc_inst_id      VARCHAR(32) NOT NULL COMMENT '业务键',
  process_def_id    VARCHAR(32) NOT NULL,
  def_version       INT         NOT NULL COMMENT '绑定定义版本(定义不可变, 监听器链等由定义推导)',
  status            VARCHAR(16) NOT NULL COMMENT '闸门+认领: pending 可被认领, running 表示有 owner 在驱动',
  config            MEDIUMTEXT  COMMENT '创建输入(流程级配置), 持久化, JSON; 与其他层同名(${config.*} 沿父链上溯可读)',
  vars              MEDIUMTEXT  COMMENT '运行期变量空间(含 dialect 选择/运行期写入), 持久化, JSON',
  result            MEDIUMTEXT  COMMENT '流程级 result 桶, 持久化, JSON(驱动路径脏刷; 回退前置挂的现场信息不刷; 坏终态含首条失败摘要 __error)',
  creator_id        VARCHAR(20) COMMENT '创建人(启动流程的当前用户)',
  updator_id        VARCHAR(20) COMMENT '最后更新用户',
  created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (auto_id),
  UNIQUE KEY uk_proc_inst (proc_inst_id),
  KEY idx_claim (status, auto_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 调度表(可选路由, 独立维护): 只有在本表有行的流程才参与"主动调度"(调度器按能力认领)。
--   创建流程时路由是可选的: 给了 (sched_unit, sched_zone) 才写这里一行 -> 进主动调度池;
--   没给就没有本表行 -> 调度器不主动认领(仍可同步 facade.start 触发)。
--   status/认领闸门仍在 process_inst(单行 CAS 保原子), 本表只承载路由维度; 认领命中靠 JOIN 本表收窄。
--   sched_unit/sched_zone 是**过滤维度**(走索引)故独立成列; 后期若要机器id/节点id 等元数据另议扩展。
CREATE TABLE process_sched (
  auto_id       BIGINT      NOT NULL AUTO_INCREMENT COMMENT '物理主键, 应用不直接使用',
  proc_inst_id  VARCHAR(32) NOT NULL COMMENT '流程业务键, 一对一关联 process_inst',
  sched_unit    VARCHAR(64) NOT NULL COMMENT '服务单元(路由过滤维度)',
  sched_zone    VARCHAR(64) NOT NULL COMMENT '网络区域(路由过滤维度)',
  creator_id    VARCHAR(20) COMMENT '创建用户',
  updator_id    VARCHAR(20) COMMENT '最后更新用户',
  created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (auto_id),
  UNIQUE KEY uk_sched_proc (proc_inst_id),
  KEY idx_sched_route (sched_unit, sched_zone, proc_inst_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE process_node_inst (
  auto_id        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键, 承载声明顺序(驱动时与定义表达式按下标对齐)',
  node_inst_id   VARCHAR(32)  NOT NULL COMMENT '业务键',
  proc_inst_id   VARCHAR(32)  NOT NULL,
  node_def_id    VARCHAR(128) COMMENT '定义引用键(flow DSL 引用键; 匿名 PARALLEL/IF 为 NULL); 展示用快照, 物化期烙一次; 与 action_def_id/process_def_id 同族',
  name           VARCHAR(128) COMMENT '展示名(NodeDef.displayName: 声明 name > 定义 id; 匿名控制节点 NULL, 视图回落 node_inst_id); 展示用快照',
  type           VARCHAR(16)  NOT NULL COMMENT 'NodeType.getStatus() 小写: parallel|if|group|action(控制/叶子由 type 区分; node_path/kind 已移除)',
  parent_id      VARCHAR(32)  COMMENT '父节点业务键, 顶层根为 NULL',
  status         VARCHAR(16)  NOT NULL DEFAULT 'created',
  pending        INT          COMMENT 'join 计数(记录用); NULL=未激活',
  error_policy   VARCHAR(24)  NOT NULL DEFAULT 'fail_fast' COMMENT '生效值(物化期冻结, ErrorPolicy.getStatus() 小写): 节点声明 > 流程级全局 errorPolicy > 默认 FAIL_FAST',
  shard_enabled  TINYINT      NOT NULL DEFAULT 0,
  shard_resolver VARCHAR(64)  COMMENT 'ShardResolver.name()',
  condition_expr VARCHAR(512) COMMENT 'IF 条件 / 节点级 guard',
  config         MEDIUMTEXT   COMMENT 'JSON, 业务配置(物化期 PLAN 冻结) + __chosen(IF 决策); 监听器链由定义推导不落库, 命名容器自述改落 node_def_id/name 列',
  result         MEDIUMTEXT   COMMENT 'JSON(各层只装本层显式写入的值: action 行=handler 结果; node/lane 行=本层现场如 __error, 引擎不自动复制上层/下层内容; GROUP 行=使用者显式挂进组桶的共享值)',
  creator_id     VARCHAR(20)  COMMENT '创建用户',
  updator_id     VARCHAR(20)  COMMENT '最后更新用户',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (auto_id),
  UNIQUE KEY uk_node_inst (node_inst_id),
  KEY idx_parent (parent_id, status, auto_id),
  KEY idx_proc (proc_inst_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE process_lane (
  auto_id       BIGINT       NOT NULL AUTO_INCREMENT,
  lane_id       VARCHAR(32)  NOT NULL COMMENT '业务键',
  proc_inst_id  VARCHAR(32)  NOT NULL,
  node_inst_id  VARCHAR(32)  NOT NULL COMMENT '所属分片节点业务键',
  shard_key     VARCHAR(128) NOT NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'created',
  error_policy  VARCHAR(24)  NOT NULL DEFAULT 'fail_fast' COMMENT '继承所属分片节点的生效值(ErrorPolicy.getStatus() 小写)',
  config        MEDIUMTEXT   COMMENT 'JSON',
  result        MEDIUMTEXT   COMMENT 'lane 级上下文(同 shardKey 共享), JSON',
  creator_id    VARCHAR(20)  COMMENT '创建用户',
  updator_id    VARCHAR(20)  COMMENT '最后更新用户',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (auto_id),
  UNIQUE KEY uk_lane (lane_id),
  UNIQUE KEY uk_node_shard (node_inst_id, shard_key),
  KEY idx_proc (proc_inst_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE process_action_inst (
  auto_id        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键, 单调递增 = 声明顺序',
  action_inst_id VARCHAR(32)  NOT NULL COMMENT '业务键',
  node_inst_id   VARCHAR(32)  NOT NULL,
  lane_id        VARCHAR(32)  COMMENT '所属 lane 业务键, 非分片为 NULL',
  action_def_id  VARCHAR(128) NOT NULL,
  name           VARCHAR(128) COMMENT '展示名(ActionDef.displayName: 声明 name > action id; 未声明即 action_def_id); 展示用快照, 物化期烙一次',
  handler_name   VARCHAR(128) NOT NULL COMMENT 'ActionHandler.name()',
  shard_key      VARCHAR(128) COMMENT '冗余, 便于查询',
  status         VARCHAR(16)  NOT NULL DEFAULT 'created',
  when_expr      VARCHAR(512) COMMENT '声明式 guard',
  visit_count    INT          NOT NULL DEFAULT 0 COMMENT '重跑计数(幂等观测)',
  config         MEDIUMTEXT   COMMENT 'JSON, 业务配置; 物化期 ${vars.*} 已替换成字面量(监听器链由定义推导不落库)',
  result         MEDIUMTEXT   COMMENT 'JSON, 业务结果 + 保留键 __error/__skipReason',
  creator_id     VARCHAR(20)  COMMENT '创建用户',
  updator_id     VARCHAR(20)  COMMENT '最后更新用户',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (auto_id),
  UNIQUE KEY uk_action_inst (action_inst_id),
  UNIQUE KEY uk_node_def_shard (node_inst_id, action_def_id, shard_key),
  KEY idx_container (lane_id, node_inst_id, status, auto_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 回退/补偿历史(#16): 每个补偿步骤一行, 关联原 action_inst(不改其状态)。
-- 原 action_inst 是不可变的执行事实; 补偿是独立可追溯的 Saga 历史, 可多次回退留痕。
CREATE TABLE process_rollback_record (
  auto_id        BIGINT      NOT NULL AUTO_INCREMENT,
  rollback_id    VARCHAR(32) NOT NULL COMMENT '业务键',
  proc_inst_id   VARCHAR(32) NOT NULL COMMENT '所属流程业务键',
  action_inst_id VARCHAR(32) NOT NULL COMMENT '关联的原 action 业务键',
  action_def_id  VARCHAR(128),
  status         VARCHAR(24)  NOT NULL COMMENT 'RollbackStatus.getStatus() 小写: compensating|compensated|compensate_failed|blocked',
  operator       VARCHAR(64),
  detail         MEDIUMTEXT  COMMENT 'JSON 文本: reason/异常信息等',
  creator_id     VARCHAR(20) COMMENT '创建用户',
  updator_id     VARCHAR(20) COMMENT '最后更新用户',
  created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (auto_id),
  UNIQUE KEY uk_rollback (rollback_id),
  KEY idx_proc (proc_inst_id, auto_id),
  KEY idx_action (action_inst_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
