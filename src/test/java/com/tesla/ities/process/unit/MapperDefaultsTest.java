package com.tesla.ities.process.unit;

import com.tesla.ities.process.core.model.ActionSegment;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.mapper.ActionInstMapper;
import com.tesla.ities.process.mapper.LaneMapper;
import com.tesla.ities.process.mapper.NodeInstMapper;
import com.tesla.ities.process.mapper.ProcessInstMapper;
import com.tesla.ities.process.mapper.RollbackRecordMapper;
import com.tesla.ities.process.mapper.MockJdbc;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static com.tesla.ities.process.mapper.MockJdbc.row;

/**
 * mapper 语义快捷 default 方法的**委托回归**: 这些便捷方法(setStatus / casStatus / activate /
 * markRunning / updateStatusResult / updateResult / updateConfig / claim / updateVars / findRoots ...)
 * 不发自己的 SQL, 只是把列组合与状态字面量喂给合并后的动态语句。
 *
 * 口径: 真实 MyBatis + 替身 JDBC(见 {@link MockJdbc}), 逐条断言"快捷方法发出去的 SQL 与绑的值"
 * 就等于手写法对应的 updateState / findByParent / find 形态——委托错一列、少一个 CAS 条件、
 * 状态字面量写错都会在这里炸。库语义(命中几行、顺序)不在本用例范围。
 */
public class MapperDefaultsTest {

    private MockJdbc jdbc;
    private SqlSession session;

    @Before
    public void setUp() throws Exception {
        jdbc = new MockJdbc();
        SqlSessionFactory factory = jdbc.sqlSessionFactory("process_");
        session = factory.openSession(true);
    }

    @After
    public void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    // ===================== node_inst =====================

    /** 激活 = CAS(created -> pending), 且下传了操作人才顺带刷 updator_id. */
    @Test
    public void nodeActivateIsCasFromCreatedToPending() {
        session.getMapper(NodeInstMapper.class).activate("N1", "u");

        assertLast("UPDATE process_node_inst SET status = ?, updator_id = ? WHERE node_inst_id = ? AND status = ?",
                Status.PENDING.getStatus(), "u", "N1", Status.CREATED.getStatus());
    }

    /** markRunning / setStatus 是同一条语句: 不校验前态, 只写 status(+ 可选审计列). */
    @Test
    public void nodeMarkRunningEqualsSetStatusWithoutGate() {
        NodeInstMapper m = session.getMapper(NodeInstMapper.class);

        m.markRunning("N1", "u");
        assertLast("UPDATE process_node_inst SET status = ?, updator_id = ? WHERE node_inst_id = ?",
                Status.RUNNING.getStatus(), "u", "N1");

        m.setStatus("N1", Status.RUNNING.getStatus(), "u");
        assertLast("UPDATE process_node_inst SET status = ?, updator_id = ? WHERE node_inst_id = ?",
                Status.RUNNING.getStatus(), "u", "N1");

        jdbc.clearCalls();
        m.markRunning("N1", null);          // 无人下传 -> SET 段里没有审计列
        assertLast("UPDATE process_node_inst SET status = ? WHERE node_inst_id = ?",
                Status.RUNNING.getStatus(), "N1");
        Assert.assertEquals("每个快捷方法只发一条语句", 1, jdbc.calls().size());
    }

    /** casStatus / updateStatusResult / updateConfig 各自的列组合都落在同一条 updateState 上. */
    @Test
    public void nodeShortcutsPickTheirColumns() {
        NodeInstMapper m = session.getMapper(NodeInstMapper.class);

        m.casStatus("N1", Status.RUNNING.getStatus(), Status.SUCCESS.getStatus(), "u");
        assertLast("UPDATE process_node_inst SET status = ?, updator_id = ? WHERE node_inst_id = ? AND status = ?",
                Status.SUCCESS.getStatus(), "u", "N1", Status.RUNNING.getStatus());

        m.updateStatusResult("N1", Status.FAILED.getStatus(), "{\"e\":1}", "u");
        assertLast("UPDATE process_node_inst SET status = ?, result = ?, updator_id = ? WHERE node_inst_id = ?",
                Status.FAILED.getStatus(), "{\"e\":1}", "u", "N1");

        m.updateConfig("N1", "{\"__chosen\":\"then\"}", null);
        assertLast("UPDATE process_node_inst SET config = ? WHERE node_inst_id = ?",
                "{\"__chosen\":\"then\"}", "N1");
    }

    /** findRoots / findChildren 都只发一条 findByParent, 差别全在 parentId 给不给. */
    @Test
    public void nodeRootAndChildFindersShareOneStatement() {
        NodeInstMapper m = session.getMapper(NodeInstMapper.class);
        jdbc.rows("AND parent_id IS NULL", row("node_inst_id", "N1"), row("node_inst_id", "N2"));
        jdbc.rows("AND parent_id = ?", row("node_inst_id", "N11", "parent_id", "N1"));

        Assert.assertEquals(2, m.findRoots("P1").size());
        String rootsSql = jdbc.lastSql();
        Assert.assertTrue("顶层根走 IS NULL: " + rootsSql, rootsSql.contains("AND parent_id IS NULL"));
        Assert.assertEquals(Arrays.<Object>asList("P1"), jdbc.lastParams());

        Assert.assertEquals("N11", m.findChildren("P1", "N1").get(0).getNodeInstId());
        Assert.assertEquals("同一张表同一条语句, 只换谓词", rootsSql.substring(0, rootsSql.indexOf("WHERE")),
                jdbc.lastSql().substring(0, jdbc.lastSql().indexOf("WHERE")));
        Assert.assertTrue(jdbc.lastSql().contains("AND parent_id = ?"));
        Assert.assertEquals(Arrays.<Object>asList("P1", "N1"), jdbc.lastParams());
    }

    // ===================== lane =====================

    /** lane 那一组的五档快捷(activate/setStatus/casStatus/updateStatusResult/updateResult)列组合各自对上. */
    @Test
    public void laneShortcutsDelegateToUpdateState() {
        LaneMapper m = session.getMapper(LaneMapper.class);

        m.activate("L1", "u");
        assertLast("UPDATE process_lane SET status = ?, updator_id = ? WHERE lane_id = ? AND status = ?",
                Status.PENDING.getStatus(), "u", "L1", Status.CREATED.getStatus());

        m.setStatus("L1", Status.RUNNING.getStatus(), null);
        assertLast("UPDATE process_lane SET status = ? WHERE lane_id = ?", Status.RUNNING.getStatus(), "L1");

        m.casStatus("L1", Status.RUNNING.getStatus(), Status.SUCCESS.getStatus(), "u");
        assertLast("UPDATE process_lane SET status = ?, updator_id = ? WHERE lane_id = ? AND status = ?",
                Status.SUCCESS.getStatus(), "u", "L1", Status.RUNNING.getStatus());

        m.updateStatusResult("L1", Status.SUCCESS.getStatus(), "{\"r\":1}", null);
        assertLast("UPDATE process_lane SET status = ?, result = ? WHERE lane_id = ?",
                Status.SUCCESS.getStatus(), "{\"r\":1}", "L1");

        m.updateResult("L1", "{\"r\":2}", "u");        // 只刷 result, 不动状态
        assertLast("UPDATE process_lane SET result = ?, updator_id = ? WHERE lane_id = ?",
                "{\"r\":2}", "u", "L1");
    }

    /** findByNode(单参) = 不收窄 shard; findByNodeAndShard = 唯一键收窄到一条(空结果给 null 是 default 自己的判断). */
    @Test
    public void laneFindShortcutsOnlyChangeTheNarrowing() {
        LaneMapper m = session.getMapper(LaneMapper.class);
        jdbc.rows("WHERE node_inst_id = ? ORDER BY auto_id ASC", row("lane_id", "L1"), row("lane_id", "L2"));
        jdbc.rows("AND shard_key = ?", row("lane_id", "L1", "shard_key", "db-01"));

        Assert.assertEquals(2, m.findByNode("NS").size());
        Assert.assertFalse("不收窄就不是唯一键定位: " + jdbc.lastSql(), jdbc.lastSql().contains("shard_key ="));

        Assert.assertEquals("L1", m.findByNodeAndShard("NS", "db-01").getLaneId());
        Assert.assertTrue(jdbc.lastSql().contains("AND shard_key = ?"));
        Assert.assertTrue(jdbc.lastSql().endsWith("LIMIT 1"));
        Assert.assertEquals(Arrays.<Object>asList("NS", "db-01"), jdbc.lastParams());

        jdbc.clearRows();
        Assert.assertNull(m.findByNodeAndShard("NS", "db-99"));
    }

    // ===================== action_inst =====================

    /** action 的 activate/setStatus/casStatus 都落在 updateState; 落 result 的那档顺带 bump visit_count. */
    @Test
    public void actionShortcutsDelegateToUpdateState() {
        ActionInstMapper m = session.getMapper(ActionInstMapper.class);

        m.activate("A1", "u");
        assertLast("UPDATE process_action_inst SET status = ?, updator_id = ? WHERE action_inst_id = ? AND status = ?",
                Status.PENDING.getStatus(), "u", "A1", Status.CREATED.getStatus());

        m.setStatus("A1", Status.RUNNING.getStatus(), null);
        assertLast("UPDATE process_action_inst SET status = ? WHERE action_inst_id = ?",
                Status.RUNNING.getStatus(), "A1");

        m.casStatus("A1", Status.RUNNING.getStatus(), Status.FAILED.getStatus(), "u");
        assertLast("UPDATE process_action_inst SET status = ?, updator_id = ? WHERE action_inst_id = ? AND status = ?",
                Status.FAILED.getStatus(), "u", "A1", Status.RUNNING.getStatus());

        m.updateResult("A1", Status.SUCCESS.getStatus(), "{\"ok\":1}", "u");
        assertLast("UPDATE process_action_inst SET status = ?, result = ?, visit_count = visit_count + 1,"
                        + " updator_id = ? WHERE action_inst_id = ?",
                Status.SUCCESS.getStatus(), "{\"ok\":1}", "u", "A1");
    }

    /** 两参 findInContainer = 不按段不收窄自然键; findByContainerAndDef 必须显式给段(否则会串段). */
    @Test
    public void actionContainerFindShortcutsKeepSegmentExplicit() {
        ActionInstMapper m = session.getMapper(ActionInstMapper.class);
        jdbc.rows("FROM process_action_inst", row("action_inst_id", "A1", "segment", ActionSegment.MAIN.getStatus()));

        Assert.assertEquals(1, m.findInContainer("NS", "L1").size());
        Assert.assertFalse("两参版不收窄段", jdbc.lastSql().contains("segment ="));
        Assert.assertEquals(Arrays.<Object>asList("NS", "L1"), jdbc.lastParams());

        Assert.assertEquals("A1", m.findByContainerAndDef("NS", "L1", "d", ActionSegment.SETDOWN.getStatus()).getActionInstId());
        Assert.assertTrue(jdbc.lastSql().contains("AND segment = ? AND action_def_id = ?"));
        Assert.assertEquals(Arrays.<Object>asList("NS", "L1", "setdown", "d"), jdbc.lastParams());

        jdbc.clearRows();
        Assert.assertNull("自然键没命中 -> 建行", m.findByContainerAndDef("NS", "L1", "ghost", ActionSegment.MAIN.getStatus()));
    }

    // ===================== process_inst / rollback_record =====================

    /** 流程侧三档: claim = pending -> running 的 CAS; updateVars 不碰状态; casStatus 由调用方给前后态. */
    @Test
    public void processInstShortcutsDelegateToUpdateState() {
        ProcessInstMapper m = session.getMapper(ProcessInstMapper.class);

        m.claim("P1", null);                                    // 调度器无人值守
        assertLast("UPDATE process_inst SET status = ? WHERE proc_inst_id = ? AND status = ?",
                Status.RUNNING.getStatus(), "P1", Status.PENDING.getStatus());

        m.updateVars("P1", "{\"n\":2}", "u2");
        assertLast("UPDATE process_inst SET vars = ?, updator_id = ? WHERE proc_inst_id = ?",
                "{\"n\":2}", "u2", "P1");

        m.casStatus("P1", Status.RUNNING.getStatus(), Status.PAUSED.getStatus(), "u2");
        assertLast("UPDATE process_inst SET status = ?, updator_id = ? WHERE proc_inst_id = ? AND status = ?",
                Status.PAUSED.getStatus(), "u2", "P1", Status.RUNNING.getStatus());
    }

    /** findByProcess 收 procInstId, findLatestByAction 收 actionInstId 并**取最后一行**(取尾是 default 自己的判断). */
    @Test
    public void rollbackShortcutsShareFindAndTakeTheLastRow() {
        RollbackRecordMapper m = session.getMapper(RollbackRecordMapper.class);
        jdbc.rows("WHERE proc_inst_id = ?", row("rollback_id", "R1"), row("rollback_id", "R2"));
        jdbc.rows("WHERE action_inst_id = ?", row("rollback_id", "R1"), row("rollback_id", "R2"),
                row("rollback_id", "R3"));

        Assert.assertEquals(2, m.findByProcess("P1").size());
        Assert.assertEquals(Arrays.<Object>asList("P1"), jdbc.lastParams());

        Assert.assertEquals("R3", m.findLatestByAction("A1").getRollbackId());
        Assert.assertEquals(Arrays.<Object>asList("A1"), jdbc.lastParams());
        Assert.assertTrue(jdbc.lastSql().endsWith("WHERE action_inst_id = ? ORDER BY auto_id ASC"));

        jdbc.clearRows();
        Assert.assertNull("没有历史 = 从没回退过", m.findLatestByAction("A9"));
    }

    // ---------- 内部 ----------

    /** 断言最后一条语句的 SQL 与绑值(替身把每个 {@code #{ }} 按 SQL 出现顺序记下来). */
    private void assertLast(String expectedSql, Object... expectedParams) {
        MockJdbc.Call c = jdbc.last();
        Assert.assertNotNull("用例该发过语句", c);
        Assert.assertEquals(expectedSql, c.sql());
        Assert.assertEquals(Arrays.<Object>asList(expectedParams), c.params());
    }
}
