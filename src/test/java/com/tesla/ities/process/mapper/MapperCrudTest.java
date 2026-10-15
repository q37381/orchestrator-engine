package com.tesla.ities.process.mapper;

import com.tesla.ities.process.core.model.ActionSegment;
import com.tesla.ities.process.core.model.DefinitionStatus;
import com.tesla.ities.process.core.model.ErrorPolicy;
import com.tesla.ities.process.core.model.NodeType;
import com.tesla.ities.process.core.model.RollbackStatus;
import com.tesla.ities.process.domain.ActionInstEntity;
import com.tesla.ities.process.domain.LaneEntity;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.domain.ProcessDefinitionEntity;
import com.tesla.ities.process.domain.ProcessInstEntity;
import com.tesla.ities.process.domain.RollbackRecordEntity;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.tesla.ities.process.mapper.MockJdbc.row;

/**
 * 六张表 CRUD 的**接线回归**(真实 MyBatis + 替身 JDBC, 不起容器、不连库):
 *  1) 插入/更新的每个 {@code #{ }} 按 XML 声明顺序绑上实体属性, 动态 updateState 只把**传入的列**写进 SET;
 *  2) resultMap 把下划线列映射进驼峰属性(含 Long/Integer/Boolean/LocalDateTime 各类型, 库里为空的列给 null);
 *  3) {@code useGeneratedKeys}(action/rollback 两处)真把 auto_id 回填进实体;
 *  4) 列表查询按 JDBC 返回顺序交回(顺序本身由库负责)。
 *
 * <h3>不再声称的部分(与替身口径一起说清)</h3>
 * INSERT IGNORE 是否真去重、CAS 是否真命中 0 行、auto_id 是否真按插入升序、列默认值是否真落 main ——
 * 这些都是 **MySQL 的语义**, 替身只是回放用例编程给它的行数/行值, 拿它们当结论是自欺。
 * 需要库级回归时把 {@code jdbc.calls()} 录下的语句拿到目标库上跑一遍。
 */
public class MapperCrudTest {

    private MockJdbc jdbc;
    private SqlSession session;

    @Before
    public void setUp() throws Exception {
        jdbc = new MockJdbc();
        SqlSessionFactory factory = jdbc.sqlSessionFactory("process_");
        session = factory.openSession(true);
    }

    @After
    public void close() {
        if (session != null) {
            session.close();
        }
    }

    // ===================== process_inst =====================

    /** 插入: 九个占位符按 XML 列序绑实体属性, 没给的列(result/creator_id/updator_id)绑 null(列在 SQL 里, 值为空). */
    @Test
    public void processInstInsertBindsColumnsInDeclarationOrder() {
        ProcessInstEntity p = new ProcessInstEntity();
        p.setProcInstId("P1");
        p.setProcessDefId("db-migration");
        p.setDefVersion(3);
        p.setStatus("pending");
        p.setConfig("{\"a\":1}");        // 创建输入落流程级 config; params 桶不持久化
        p.setVars("{\"dialect\":\"mysql\"}");

        Assert.assertEquals(1, session.getMapper(ProcessInstMapper.class).insert(p));

        MockJdbc.Call c = jdbc.last();
        Assert.assertEquals("INSERT INTO process_inst(proc_inst_id, process_def_id, def_version, status, config, vars,"
                        + " result, creator_id, updator_id) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)", c.sql());
        Assert.assertEquals(Arrays.<Object>asList("P1", "db-migration", 3, "pending", "{\"a\":1}",
                        "{\"dialect\":\"mysql\"}", null, null, null), c.params());
    }

    /** 读取: 下划线列 -> 驼峰属性(不靠 mapUnderscoreToCamelCase), 各类型都落对; 库里为空的列给 null 而不是空串. */
    @Test
    public void findByIdMapsUnderscoreColumnsToCamelProperties() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 14, 9, 30, 0);
        jdbc.rows("WHERE proc_inst_id = ?", row(
                "auto_id", 7L, "proc_inst_id", "P1", "process_def_id", "db-migration", "def_version", 3,
                "status", "running", "config", "{\"a\":1}", "vars", "{\"k\":1}", "result", null,
                "creator_id", "u1", "updator_id", "u2", "created_at", now, "updated_at", now));

        ProcessInstEntity back = session.getMapper(ProcessInstMapper.class).findById("P1");

        Assert.assertEquals(Long.valueOf(7L), back.getAutoId());
        Assert.assertEquals("P1", back.getProcInstId());
        Assert.assertEquals("db-migration", back.getProcessDefId());
        Assert.assertEquals(Integer.valueOf(3), back.getDefVersion());
        Assert.assertEquals("running", back.getStatus());
        Assert.assertEquals("{\"a\":1}", back.getConfig());
        Assert.assertEquals("{\"k\":1}", back.getVars());
        Assert.assertNull("空列映射成 null", back.getResult());
        Assert.assertEquals("u1", back.getCreatorId());
        Assert.assertEquals("u2", back.getUpdatorId());
        Assert.assertEquals(now, back.getCreatedAt());
        Assert.assertEquals("审计列各归各位", "u2", back.getUpdatorId());

        Assert.assertEquals("SELECT auto_id, proc_inst_id, process_def_id, def_version, status, config, vars, result,"
                + " creator_id, updator_id, created_at, updated_at FROM process_inst WHERE proc_inst_id = ?", jdbc.lastSql());
        Assert.assertEquals(Collections.<Object>singletonList("P1"), jdbc.lastParams());
    }

    /** 动态 updateState: 只写传入的非 null 列; fromStatus 非 null 才追加 CAS 条件——原"不传 vars 保留原值"就靠这条. */
    @Test
    public void updateStateWritesOnlyTheColumnsGiven() {
        ProcessInstMapper m = session.getMapper(ProcessInstMapper.class);

        Assert.assertEquals(1, m.updateState("P1", null, "running", null, null, null));
        Assert.assertEquals("UPDATE process_inst SET status = ? WHERE proc_inst_id = ?", jdbc.lastSql());
        Assert.assertEquals(Arrays.<Object>asList("running", "P1"), jdbc.lastParams());

        // 只落 result(流程级结果): SET 段里没有 status/vars, 库里那两列才不会被 NULL 抹掉
        Assert.assertEquals(1, m.updateState("P1", null, null, "{\"proc\":1}", null, "u2"));
        Assert.assertEquals("UPDATE process_inst SET result = ?, updator_id = ? WHERE proc_inst_id = ?", jdbc.lastSql());
        Assert.assertEquals(Arrays.<Object>asList("{\"proc\":1}", "u2", "P1"), jdbc.lastParams());

        // 刷 vars 不动状态
        Assert.assertEquals(1, m.updateState("P1", null, null, null, "{\"n\":2}", null));
        Assert.assertEquals("UPDATE process_inst SET vars = ? WHERE proc_inst_id = ?", jdbc.lastSql());

        // CAS: 前态进了 WHERE, 命中与否由库判断(替身按编程行数透传)
        jdbc.updateCount(0);
        Assert.assertEquals(0, m.updateState("P1", "running", "paused", null, null, null));
        Assert.assertEquals("UPDATE process_inst SET status = ? WHERE proc_inst_id = ? AND status = ?", jdbc.lastSql());
        Assert.assertEquals(Arrays.<Object>asList("paused", "P1", "running"), jdbc.lastParams());
    }

    /** 认领闸门: pending -> running 走同一条 updateState, updatorId 传 null 就不刷审计列(调度器无人值守). */
    @Test
    public void claimIsAPendingGateOnUpdateState() {
        ProcessInstMapper m = session.getMapper(ProcessInstMapper.class);

        Assert.assertEquals(1, m.claim("P1", "starter"));
        Assert.assertEquals("UPDATE process_inst SET status = ?, updator_id = ? WHERE proc_inst_id = ? AND status = ?", jdbc.lastSql());
        Assert.assertEquals(Arrays.<Object>asList("running", "starter", "P1", "pending"), jdbc.lastParams());

        jdbc.clearCalls();
        Assert.assertEquals(1, m.claim("P1", null));               // 无人值守: 不给 updator_id
        Assert.assertEquals("UPDATE process_inst SET status = ? WHERE proc_inst_id = ? AND status = ?", jdbc.lastSql());
        Assert.assertEquals(Arrays.<Object>asList("running", "P1", "pending"), jdbc.lastParams());
    }

    /** 捞可认领: 闸门字面量小写 + LIMIT 由入参绑; 返回的行按 JDBC 顺序交回. */
    @Test
    public void findClaimableIdsBindsLimitAndKeepsRowOrder() {
        jdbc.rows("WHERE status = 'pending'", row("proc_inst_id", "P1"), row("proc_inst_id", "P2"));

        List<String> ids = session.getMapper(ProcessInstMapper.class).findClaimableIds(16);

        Assert.assertEquals(Arrays.asList("P1", "P2"), ids);
        Assert.assertEquals("SELECT proc_inst_id FROM process_inst WHERE status = 'pending'"
                + " ORDER BY auto_id ASC LIMIT ?", jdbc.lastSql());
        Assert.assertEquals(Collections.<Object>singletonList(16), jdbc.lastParams());
    }

    /** 路由认领: JOIN 调度表只多一层过滤, 闸门仍是 status='pending'; zone 可为 null(不钉区域). */
    @Test
    public void routedClaimJoinsSchedTableWithoutLoosingTheGate() {
        jdbc.rows("JOIN process_sched", row("proc_inst_id", "P9"));

        List<String> ids = session.getMapper(ProcessInstMapper.class).findClaimableIdsByRoute("unitA", null, 8);

        Assert.assertEquals(Collections.singletonList("P9"), ids);
        String sql = jdbc.lastSql();
        Assert.assertTrue("闸门字面量不许丢: " + sql, sql.contains("WHERE i.status = 'pending'"));
        Assert.assertTrue("区域可钉可不钉: " + sql,
                sql.contains("AND (s.sched_zone IS NULL OR s.sched_zone = ?)"));
        Assert.assertEquals(Arrays.<Object>asList("unitA", null, 8), jdbc.lastParams());
    }

    // ===================== node_inst =====================

    /** 幂等物化的插入: INSERT IGNORE 文本 + 十四个绑值按列序; 备用认领路径 claim 走 JOIN 不改口径. */
    @Test
    public void nodeInstInsertIgnoreBindsEveryColumn() {
        NodeInstEntity n = new NodeInstEntity();
        n.setNodeInstId("N1");
        n.setProcInstId("P1");
        n.setNodeDefId("parallel-1");
        n.setName("校验组");
        n.setType(NodeType.PARALLEL.getStatus());
        n.setStatus("created");
        n.setErrorPolicy(ErrorPolicy.DEFAULT.getStatus());
        n.setShardEnabled(Boolean.FALSE);
        n.setShardResolver("dbShards");
        n.setConfig("{}");
        n.setCreatorId("u1");

        Assert.assertEquals(1, session.getMapper(NodeInstMapper.class).insertIgnore(n));

        MockJdbc.Call c = jdbc.last();
        Assert.assertTrue("列序即绑序: " + c.sql(), c.sql().startsWith("INSERT IGNORE INTO process_node_inst(node_inst_id,"
                + " proc_inst_id, node_def_id, name, type, parent_id, status, error_policy, shard_enabled,"
                + " shard_resolver, condition_expr, config, creator_id, updator_id) VALUES("));
        Assert.assertEquals(Arrays.<Object>asList("N1", "P1", "parallel-1", "校验组", "parallel", null, "created",
                        ErrorPolicy.DEFAULT.getStatus(), Boolean.FALSE, "dbShards", null, "{}", "u1", null),
                c.params());
    }

    /** 顶层根 vs 子行只差一个谓词; 声明顺序由 auto_id 承载(顺序本身是库的事). */
    @Test
    public void nodeInstRootsAndChildrenUseOneStatement() {
        NodeInstMapper m = session.getMapper(NodeInstMapper.class);
        jdbc.rows("AND parent_id IS NULL", row("node_inst_id", "N1", "type", "parallel"),
                row("node_inst_id", "N2", "type", "if"));
        jdbc.rows("AND parent_id = ?", row("node_inst_id", "N11", "parent_id", "N1", "status", "created"));

        List<NodeInstEntity> roots = m.findRoots("P1");
        Assert.assertEquals(2, roots.size());
        Assert.assertEquals("N1", roots.get(0).getNodeInstId());
        Assert.assertEquals("parallel", roots.get(0).getType());
        Assert.assertEquals("SELECT auto_id, node_inst_id, proc_inst_id, node_def_id, name, type, parent_id, status,"
                + " error_policy, shard_enabled, shard_resolver, condition_expr, config, result, creator_id,"
                + " updator_id, created_at, updated_at FROM process_node_inst WHERE proc_inst_id = ?"
                + " AND parent_id IS NULL ORDER BY auto_id ASC", jdbc.lastSql());
        Assert.assertEquals(Collections.<Object>singletonList("P1"), jdbc.lastParams());

        List<NodeInstEntity> children = m.findChildren("P1", "N1");
        Assert.assertEquals("N11", children.get(0).getNodeInstId());
        Assert.assertEquals("N1", children.get(0).getParentId());
        Assert.assertTrue("子行谓词: " + jdbc.lastSql(), jdbc.lastSql().contains("AND parent_id = ? ORDER BY auto_id ASC"));
        Assert.assertEquals(Arrays.<Object>asList("P1", "N1"), jdbc.lastParams());
    }

    /** 落 config(IF 决策)与落 result(容器聚合)是同一条语句的两种列组合; CAS 激活把前态写进 WHERE. */
    @Test
    public void nodeInstStateColumnsChosenByWhatIsPassed() {
        NodeInstMapper m = session.getMapper(NodeInstMapper.class);

        Assert.assertEquals(1, m.updateState("N2", null, null, null, "{\"__chosen\":\"then\"}", null));
        Assert.assertEquals("UPDATE process_node_inst SET config = ? WHERE node_inst_id = ?", jdbc.lastSql());
        Assert.assertEquals(Arrays.<Object>asList("{\"__chosen\":\"then\"}", "N2"), jdbc.lastParams());

        Assert.assertEquals(1, m.updateState("N1", null, "success", "{\"agg\":1}", null, "u2"));
        Assert.assertEquals("UPDATE process_node_inst SET status = ?, result = ?, updator_id = ? WHERE node_inst_id = ?", jdbc.lastSql());
        Assert.assertEquals(Arrays.<Object>asList("success", "{\"agg\":1}", "u2", "N1"), jdbc.lastParams());

        Assert.assertEquals(1, m.updateState("N2", "created", "pending", null, null, null));
        Assert.assertEquals("UPDATE process_node_inst SET status = ? WHERE node_inst_id = ? AND status = ?", jdbc.lastSql());
    }

    /** 备用的节点认领路径: JOIN 流程行做闸门, 状态写死小写字面量, 只绑一个主键. */
    @Test
    public void nodeInstClaimJoinsProcessGate() {
        Assert.assertEquals(1, session.getMapper(NodeInstMapper.class).claim("N1"));

        Assert.assertEquals("UPDATE process_node_inst n JOIN process_inst p ON p.proc_inst_id = n.proc_inst_id"
                + " SET n.status = 'running' WHERE n.node_inst_id = ? AND n.status = 'pending' AND p.status = 'running'",
                jdbc.lastSql());
        Assert.assertEquals(Collections.<Object>singletonList("N1"), jdbc.lastParams());
    }

    // ===================== lane =====================

    /** lane 幂等物化: 九个绑值按列序(uk_node_shard 去重是库的事). */
    @Test
    public void laneInsertIgnoreBindsColumns() {
        LaneEntity l = new LaneEntity();
        l.setLaneId("L1");
        l.setProcInstId("P1");
        l.setNodeInstId("NS");
        l.setShardKey("db-01");
        l.setStatus("created");
        l.setErrorPolicy(ErrorPolicy.DEFAULT.getStatus());
        l.setCreatorId("u1");

        Assert.assertEquals(1, session.getMapper(LaneMapper.class).insertIgnore(l));

        Assert.assertEquals("INSERT IGNORE INTO process_lane(lane_id, proc_inst_id, node_inst_id, shard_key, status,"
                        + " error_policy, config, creator_id, updator_id) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)", jdbc.lastSql());
        Assert.assertEquals(Arrays.<Object>asList("L1", "P1", "NS", "db-01", "created",
                        ErrorPolicy.DEFAULT.getStatus(), null, "u1", null), jdbc.lastParams());
    }

    /** 同一条 findByNode: 不给 shardKey 拿全部(无 LIMIT), 给了就按唯一键收窄到一条; 查不到给 null. */
    @Test
    public void laneFindByNodeNarrowsOnlyWhenShardKeyGiven() {
        LaneMapper m = session.getMapper(LaneMapper.class);
        jdbc.rows("node_inst_id = ? ORDER BY auto_id ASC", row("lane_id", "L1"), row("lane_id", "L2"));
        jdbc.rows("AND shard_key = ?", row("lane_id", "L1", "shard_key", "db-01", "status", "created"));

        List<LaneEntity> all = m.findByNode("NS");
        Assert.assertEquals(2, all.size());
        Assert.assertEquals("L1", all.get(0).getLaneId());
        Assert.assertEquals("SELECT auto_id, lane_id, proc_inst_id, node_inst_id, shard_key, status, error_policy,"
                + " config, result, creator_id, updator_id, created_at, updated_at FROM process_lane"
                + " WHERE node_inst_id = ? ORDER BY auto_id ASC", jdbc.lastSql());
        Assert.assertEquals(Collections.<Object>singletonList("NS"), jdbc.lastParams());

        LaneEntity one = m.findByNodeAndShard("NS", "db-01");
        Assert.assertEquals("L1", one.getLaneId());
        Assert.assertEquals("db-01", one.getShardKey());
        Assert.assertTrue("唯一键收窄到一条: " + jdbc.lastSql(),
                jdbc.lastSql().endsWith("WHERE node_inst_id = ? AND shard_key = ? ORDER BY auto_id ASC LIMIT 1"));
        Assert.assertEquals(Arrays.<Object>asList("NS", "db-01"), jdbc.lastParams());

        jdbc.clearRows();            // 库里没这一行(替身不再给任何行)
        Assert.assertNull("查不到 -> find-or-create 要走建行", m.findByNodeAndShard("NS", "db-99"));
    }

    /** lane 收尾: 只刷 result(不动状态)与状态+结果一起刷是两条不同列组合. */
    @Test
    public void laneUpdateStateKeepsStatusOutOfSetWhenNotGiven() {
        LaneMapper m = session.getMapper(LaneMapper.class);

        Assert.assertEquals(1, m.updateState("L1", null, null, "{\"lane\":1}", null));
        Assert.assertEquals("UPDATE process_lane SET result = ? WHERE lane_id = ?", jdbc.lastSql());

        Assert.assertEquals(1, m.updateState("L1", null, "success", "{\"lane\":2}", "u2"));
        Assert.assertEquals("UPDATE process_lane SET status = ?, result = ?, updator_id = ? WHERE lane_id = ?", jdbc.lastSql());
        Assert.assertEquals(Arrays.<Object>asList("success", "{\"lane\":2}", "u2", "L1"), jdbc.lastParams());
    }

    // ===================== action_inst =====================

    /** useGeneratedKeys: auto_id 回填进实体(声明顺序的载体), 十三个绑值按列序. */
    @Test
    public void actionInsertBackfillsAutoIdFromGeneratedKeys() {
        jdbc.generatedKey(9001L);
        ActionInstEntity a = new ActionInstEntity();
        a.setActionInstId("A1");
        a.setNodeInstId("NS");
        a.setLaneId("L1");
        a.setActionDefId("check");
        a.setHandlerName("check-handler");
        a.setShardKey("db-01");
        a.setSegment(ActionSegment.MAIN.getStatus());
        a.setStatus("created");
        a.setConfig("{}");

        Assert.assertEquals(1, session.getMapper(ActionInstMapper.class).insertIgnore(a));
        Assert.assertEquals("替身回放的自增主键要落回实体", Long.valueOf(9001L), a.getAutoId());

        MockJdbc.Call c = jdbc.last();
        Assert.assertTrue("列序即绑序: " + c.sql(), c.sql().startsWith("INSERT IGNORE INTO process_action_inst("
                + "action_inst_id, node_inst_id, lane_id, action_def_id, name, handler_name, shard_key, segment,"
                + " status, when_expr, config, creator_id, updator_id) VALUES("));
        Assert.assertEquals(Arrays.<Object>asList("A1", "NS", "L1", "check", null, "check-handler", "db-01", "main",
                "created", null, "{}", null, null), c.params());
    }

    /** 容器定位: laneId=null 走 IS NULL; 给 actionDefId 才按自然键收窄并 LIMIT 1; 段过滤独立可选. */
    @Test
    public void actionContainerFindersNarrowBySegmentAndNaturalKey() {
        ActionInstMapper m = session.getMapper(ActionInstMapper.class);
        jdbc.rows("AND lane_id = ?", row("action_inst_id", "A1", "segment", "main", "lane_id", "L1"));
        jdbc.rows("lane_id IS NULL", row("action_inst_id", "A2", "lane_id", null));
        jdbc.rows("action_def_id = ?", row("action_inst_id", "A1"));

        Assert.assertEquals("A1", m.findByContainerAndDef("NS", "L1", "check", ActionSegment.MAIN.getStatus()).getActionInstId());
        Assert.assertEquals(Arrays.<Object>asList("NS", "L1", "main", "check"), jdbc.lastParams());
        Assert.assertTrue("自然键定位必须 LIMIT 1: " + jdbc.lastSql(), jdbc.lastSql().endsWith(
                "AND lane_id = ? AND segment = ? AND action_def_id = ? ORDER BY auto_id ASC LIMIT 1"));

        List<ActionInstEntity> general = m.findInContainer("NS", null);
        Assert.assertEquals("A2", general.get(0).getActionInstId());
        Assert.assertNull("非分片行的 lane_id 就是空", general.get(0).getLaneId());
        Assert.assertTrue("非分片行走 IS NULL: " + jdbc.lastSql(),
                jdbc.lastSql().contains("WHERE node_inst_id = ? AND lane_id IS NULL ORDER BY auto_id ASC"));
        Assert.assertFalse("不给段就不按段收窄(进度视图要全量)", jdbc.lastSql().contains("segment ="));
        Assert.assertEquals(Collections.<Object>singletonList("NS"), jdbc.lastParams());

        jdbc.clearRows();
        Assert.assertNull("自然键没命中", m.findByContainerAndDef("NS", "L1", "apply", ActionSegment.MAIN.getStatus()));
    }

    /** 列表查询与容器查询是两条口径: 不按 lane 收窄, 两个条件各说各话(都不给就是全表, 由调用方保证至少给一个). */
    @Test
    public void actionListFindKeepsLaneOutOfPredicates() {
        ActionInstMapper m = session.getMapper(ActionInstMapper.class);
        jdbc.rows("WHERE node_inst_id = ? ORDER BY auto_id ASC", row("action_inst_id", "A1"), row("action_inst_id", "A2"));
        jdbc.rows("WHERE action_inst_id = ?", row("action_inst_id", "A2"));

        List<ActionInstEntity> byNode = m.find("NS", null);
        Assert.assertEquals(2, byNode.size());
        Assert.assertEquals("A1", byNode.get(0).getActionInstId());
        Assert.assertEquals("SELECT auto_id, action_inst_id, node_inst_id, lane_id, action_def_id, name,"
                + " handler_name, shard_key, segment, status, when_expr, visit_count, config, result, creator_id,"
                + " updator_id, created_at, updated_at FROM process_action_inst"
                + " WHERE node_inst_id = ? ORDER BY auto_id ASC", jdbc.lastSql());
        Assert.assertEquals(Collections.<Object>singletonList("NS"), jdbc.lastParams());

        Assert.assertEquals("A2", m.find(null, "A2").get(0).getActionInstId());
        Assert.assertTrue(jdbc.lastSql().endsWith("FROM process_action_inst WHERE action_inst_id = ? ORDER BY auto_id ASC"));
        Assert.assertFalse(jdbc.lastSql().contains("LIMIT"));
    }

    /** 落 result 才在同一条语句里 bump visit_count(重跑观测); 纯翻状态不涨. */
    @Test
    public void actionUpdateStateBumpsVisitCountOnlyWithResult() {
        ActionInstMapper m = session.getMapper(ActionInstMapper.class);

        Assert.assertEquals(1, m.updateState("A1", null, "success", "{\"rows\":5}", "tester"));
        Assert.assertEquals("UPDATE process_action_inst SET status = ?, result = ?, visit_count = visit_count + 1,"
                + " updator_id = ? WHERE action_inst_id = ?", jdbc.lastSql());
        Assert.assertEquals(Arrays.<Object>asList("success", "{\"rows\":5}", "tester", "A1"), jdbc.lastParams());

        Assert.assertEquals(1, m.updateState("A2", "running", "failed", null, null));
        Assert.assertEquals("UPDATE process_action_inst SET status = ? WHERE action_inst_id = ? AND status = ?", jdbc.lastSql());
        Assert.assertFalse("不涨观测次数就不该出现 visit_count", jdbc.lastSql().contains("visit_count"));
    }

    /** 备用的 action 认领: 三表 JOIN + 认领即计一次访问. */
    @Test
    public void actionClaimJoinsNodeAndProcessGates() {
        Assert.assertEquals(1, session.getMapper(ActionInstMapper.class).claim("A1"));

        String sql = jdbc.lastSql();
        Assert.assertTrue(sql.startsWith("UPDATE process_action_inst a JOIN process_node_inst n"));
        Assert.assertTrue(sql.contains("JOIN process_inst p ON p.proc_inst_id = n.proc_inst_id"));
        Assert.assertTrue(sql.endsWith("SET a.status = 'running', a.visit_count = a.visit_count + 1"
                + " WHERE a.action_inst_id = ? AND a.status = 'pending' AND p.status = 'running'"));
        Assert.assertEquals(Collections.<Object>singletonList("A1"), jdbc.lastParams());
    }

    // ===================== process_definition =====================

    /** 发布态以枚举入参, SQL 经 #{status.status} 绑**小写规范值**(不是 name() 的大写). */
    @Test
    public void definitionFindBindsLowercasePublishedStatus() {
        ProcessDefinitionMapper m = session.getMapper(ProcessDefinitionMapper.class);
        jdbc.rows("AND version = ?", row("process_def_id", "db-migration", "version", 1, "status", "draft"));
        jdbc.rows("AND status = ?", row("process_def_id", "db-migration", "version", 3, "status", "published"));
        jdbc.rows("AND status = 'published'", row("process_def_id", "db-migration", "version", 3, "status", "published"));

        ProcessDefinitionEntity byVersion = m.find("db-migration", 1, DefinitionStatus.DRAFT);
        Assert.assertEquals(Integer.valueOf(1), byVersion.getVersion());
        Assert.assertEquals("db-migration", byVersion.getProcessDefId());
        Assert.assertTrue("给了 version 就按唯一键精确定位, 状态不参与收窄也不绑值: " + jdbc.lastSql(),
                jdbc.lastSql().endsWith("WHERE process_def_id = ? AND version = ?"));
        Assert.assertEquals(Arrays.<Object>asList("db-migration", 1), jdbc.lastParams());

        // 枚举入参经 #{status.status} 绑小写规范值(不是 name() 的大写)
        m.find("db-migration", null, DefinitionStatus.DRAFT);
        Assert.assertEquals(Arrays.<Object>asList("db-migration", "draft"), jdbc.lastParams());
        Assert.assertTrue(jdbc.lastSql().endsWith("AND status = ? ORDER BY version DESC LIMIT 1"));

        Assert.assertEquals(Integer.valueOf(3), m.find("db-migration", null, DefinitionStatus.PUBLISHED).getVersion());
        Assert.assertEquals(Arrays.<Object>asList("db-migration", "published"), jdbc.lastParams());

        // 缺省口径: version/status 都不给 -> SQL 里直接烙 published 字面量, 只绑一个 defId
        Assert.assertEquals(Integer.valueOf(3), m.find("db-migration", null, null).getVersion());
        Assert.assertTrue("缺省回落成 published 字面量: " + jdbc.lastSql(),
                jdbc.lastSql().contains("AND status = 'published' ORDER BY version DESC LIMIT 1"));
        Assert.assertEquals(Collections.<Object>singletonList("db-migration"), jdbc.lastParams());

        // 快捷方法就是显式给 PUBLISHED 的那一条(不再多出一句 SQL)。注: 同语句同入参在同一个 session 里会
        // 命中 MyBatis 一级缓存而不再走 JDBC, 所以取证前先清缓存(引擎每层都用自己的短 session, 不靠这个缓存)。
        session.clearCache();
        Assert.assertEquals(Integer.valueOf(3), m.findLatestPublished("db-migration").getVersion());
        Assert.assertFalse("快捷方法走枚举入参那条分支: " + jdbc.lastSql(), jdbc.lastSql().contains("'published'"));
        Assert.assertEquals(Arrays.<Object>asList("db-migration", "published"), jdbc.lastParams());
    }

    /** 插入定义版本: 八个绑值按列序, 发布态同样落小写规范值. */
    @Test
    public void definitionInsertBindsVersionRow() {
        ProcessDefinitionEntity d = new ProcessDefinitionEntity();
        d.setProcessDefId("db-migration");
        d.setVersion(2);
        d.setName("db-migration");
        d.setDefinition("flow: []");
        d.setStatus(DefinitionStatus.PUBLISHED.getStatus());
        d.setCreatedBy("tester");

        Assert.assertEquals(1, session.getMapper(ProcessDefinitionMapper.class).insert(d));

        Assert.assertEquals("INSERT INTO process_definition(process_def_id, version, name, definition, status,"
                + " created_by, creator_id, updator_id) VALUES(?, ?, ?, ?, ?, ?, ?, ?)", jdbc.lastSql());
        Assert.assertEquals(Arrays.<Object>asList("db-migration", 2, "db-migration", "flow: []", "published",
                "tester", null, null), jdbc.lastParams());
    }

    // ===================== rollback_record =====================

    /** 补偿/重放历史落一行: useGeneratedKeys 回填 + 九个绑值; to 由调用方从枚举取小写值. */
    @Test
    public void rollbackInsertBackfillsKeyAndStatusUpdateWritesDetail() {
        jdbc.generatedKey(501L);
        RollbackRecordMapper m = session.getMapper(RollbackRecordMapper.class);
        RollbackRecordEntity r = new RollbackRecordEntity();
        r.setRollbackId("R1");
        r.setProcInstId("P1");
        r.setActionInstId("A1");
        r.setActionDefId("check");
        r.setStatus(RollbackStatus.COMPENSATING.getStatus());
        r.setOperator("tester");
        r.setDetail("{}");

        Assert.assertEquals(1, m.insert(r));
        Assert.assertEquals(Long.valueOf(501L), r.getAutoId());
        Assert.assertEquals("INSERT INTO process_rollback_record(rollback_id, proc_inst_id, action_inst_id,"
                        + " action_def_id, status, operator, detail, creator_id, updator_id)"
                        + " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)", jdbc.lastSql());
        Assert.assertEquals(Arrays.<Object>asList("R1", "P1", "A1", "check", "compensating", "tester", "{}", null, null),
                jdbc.lastParams());

        Assert.assertEquals(1, m.updateStatus("R1", RollbackStatus.COMPENSATE_FAILED.getStatus(), "{\"error\":\"x\"}", "tester"));
        String sql = jdbc.lastSql();
        Assert.assertTrue("给操作人才刷审计列: " + sql, sql.contains("SET status = ?, detail = ?"));
        Assert.assertTrue(sql.contains("updator_id = ? WHERE rollback_id = ?"));
        Assert.assertEquals(Arrays.<Object>asList("compensate_failed", "{\"error\":\"x\"}", "tester", "R1"), jdbc.lastParams());

        jdbc.clearCalls();
        Assert.assertEquals(1, m.updateStatus("R1", RollbackStatus.BLOCKED.getStatus(), "{}", null));
        Assert.assertFalse("无人下传就不该动 updator_id", jdbc.lastSql().contains("updator_id"));
        Assert.assertEquals(Arrays.<Object>asList("blocked", "{}", "R1"), jdbc.lastParams());
    }

    /** 历史按步骤顺序交回; "最新一条"是 default 方法自己取的最后一行(与库的顺序无关). */
    @Test
    public void rollbackHistoryRoundTripTakesLastRowAsLatest() {
        RollbackRecordMapper m = session.getMapper(RollbackRecordMapper.class);
        jdbc.rows("WHERE proc_inst_id = ?", row("rollback_id", "R1", "action_inst_id", "A1"),
                row("rollback_id", "R2", "action_inst_id", "A1"), row("rollback_id", "R3", "action_inst_id", "A2"));
        jdbc.rows("WHERE action_inst_id = ?", row("rollback_id", "R1", "action_inst_id", "A1"),
                row("rollback_id", "R2", "action_inst_id", "A1"));

        List<RollbackRecordEntity> all = m.findByProcess("P1");
        Assert.assertEquals(3, all.size());
        Assert.assertEquals("R1", all.get(0).getRollbackId());
        Assert.assertEquals("A2", all.get(2).getActionInstId());
        Assert.assertEquals("SELECT auto_id, rollback_id, proc_inst_id, action_inst_id, action_def_id, status,"
                + " operator, detail, creator_id, updator_id, created_at, updated_at FROM process_rollback_record"
                + " WHERE proc_inst_id = ? ORDER BY auto_id ASC", jdbc.lastSql());

        Assert.assertEquals("R2", m.findLatestByAction("A1").getRollbackId());
        Assert.assertEquals(Collections.<Object>singletonList("A1"), jdbc.lastParams());
        Assert.assertTrue(jdbc.lastSql().endsWith("WHERE action_inst_id = ? ORDER BY auto_id ASC"));

        jdbc.clearRows();
        Assert.assertTrue(m.find(null, "A9").isEmpty());
    }
}
