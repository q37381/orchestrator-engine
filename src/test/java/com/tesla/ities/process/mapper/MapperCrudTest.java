package com.tesla.ities.process.mapper;

import com.tesla.ities.process.config.ProcessEngineConfig;
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
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * mapper XML 的 DB 级回归: 用真实 schema.sql(H2 MySQL 模式)跑 CRUD, 验三件事——
 *  resultMap 把下划线列映射进驼峰属性(不靠 mapUnderscoreToCamelCase)、
 *  合并后的动态语句实际执行结果与各自原语义一致(CAS 命中/未命中、IS NULL 匹配、LIMIT 1 收窄)、
 *  幂等物化(INSERT IGNORE + useGeneratedKeys)。
 */
public class MapperCrudTest {

    private SqlSessionFactory factory;
    private SqlSession session;

    @Before
    public void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:orch;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
            for (String stmt : statements(schema())) {
                st.execute(stmt);
            }
        }
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(ds);
        bean.setTypeAliasesPackage("com.tesla.ities.process.domain");
        bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:META-INF/mapper/process/*.xml"));
        // 表名变量走引擎同一口径(默认 process_ 前缀); schema() 直接跑真实 db/schema.sql,
        // 两边对不上就会在第一条 CRUD 上炸表名不存在——这本身就是命名空间生效的 DB 级证据。
        Configuration conf = new Configuration();
        conf.setVariables(ProcessEngineConfig.buildTableVariables("process_"));
        bean.setConfiguration(conf);
        factory = bean.getObject();
        session = factory.openSession(true);   // autocommit, 各 mapper 调用即时可见
    }

    @After
    public void close() {
        if (session != null) {
            session.close();
        }
    }

    // ===================== process_inst =====================

    @Test
    public void processInstRoundTripAndCas() {
        ProcessInstMapper m = session.getMapper(ProcessInstMapper.class);
        ProcessInstEntity p = new ProcessInstEntity();
        p.setProcInstId("P1");
        p.setProcessDefId("db-migration");
        p.setDefVersion(3);
        p.setStatus("pending");
        p.setConfig("{\"a\":1}");        // 创建输入落流程级 config(与其他层同名); params 桶不持久化
        p.setVars("{\"dialect\":\"mysql\"}");
        Assert.assertEquals(1, m.insert(p));

        ProcessInstEntity back = m.findById("P1");
        Assert.assertEquals("P1", back.getProcInstId());               // 下划线列 -> 驼峰属性
        Assert.assertEquals("db-migration", back.getProcessDefId());
        Assert.assertEquals("{\"a\":1}", back.getConfig());             // config 列往返
        Assert.assertEquals(Integer.valueOf(3), back.getDefVersion());
        Assert.assertEquals("{\"dialect\":\"mysql\"}", back.getVars());
        Assert.assertNotNull(back.getCreatedAt());

        Assert.assertEquals(1, m.findClaimableIds(10).size());        // 只有 pending 可捞
        Assert.assertEquals(0, m.casStatus("P1", "running", "paused", null));   // CAS 前态不符 = 0 行
        Assert.assertEquals(1, m.claim("P1", null));                            // pending -> running
        Assert.assertEquals("running", m.findById("P1").getStatus());
        Assert.assertEquals(0, m.findClaimableIds(10).size());

        m.updateVars("P1", "{\"dialect\":\"mysql\",\"n\":2}", null);
        Assert.assertEquals("running", m.findById("P1").getStatus());   // 刷 vars 不动状态
        Assert.assertEquals("{\"dialect\":\"mysql\",\"n\":2}", m.findById("P1").getVars());

        // 流程级 result 落库: 与 vars 同一条 updateState, 只写传入的列
        Assert.assertNull("插入时未给 result 则为空", m.findById("P1").getResult());
        Assert.assertEquals(1, m.updateState("P1", null, null, "{\"proc\":1}", null, "u2"));
        ProcessInstEntity withResult = m.findById("P1");
        Assert.assertEquals("{\"proc\":1}", withResult.getResult());
        Assert.assertEquals("running", withResult.getStatus());            // 只落 result 不动状态
        Assert.assertEquals("{\"dialect\":\"mysql\",\"n\":2}", withResult.getVars());   // 不传 vars 则保留原值
        Assert.assertEquals("u2", withResult.getUpdatorId());
    }

    /** 审计列: creator_id 只随插入落库; updator_id 由调用方显式下传刷写(null 则保留原值)。 */
    @Test
    public void auditColumnsWrittenOnInsertAndStateUpdate() {
        ProcessInstMapper m = session.getMapper(ProcessInstMapper.class);
        ProcessInstEntity p = new ProcessInstEntity();
        p.setProcInstId("AUD");
        p.setProcessDefId("db-migration");
        p.setDefVersion(1);
        p.setStatus("pending");
        p.setCreatorId("u1");
        p.setUpdatorId("u1");
        Assert.assertEquals(1, m.insert(p));
        Assert.assertEquals("u1", m.findById("AUD").getCreatorId());     // 下划线列仍由 resultMap 映射
        Assert.assertEquals("u1", m.findById("AUD").getUpdatorId());

        Assert.assertEquals(1, m.updateVars("AUD", "{\"k\":1}", "u2"));
        ProcessInstEntity after = m.findById("AUD");
        Assert.assertEquals("creator_id 不可被更新改写", "u1", after.getCreatorId());
        Assert.assertEquals("updator_id = 下传的操作人", "u2", after.getUpdatorId());

        m.updateVars("AUD", "{\"k\":2}", null);        // 无人下传(无人值守) -> 不刷 updator_id, 保留原值
        Assert.assertEquals("u2", m.findById("AUD").getUpdatorId());
    }

    // ===================== node_inst =====================

    @Test
    public void nodeInstRootsChildrenAndState() {
        NodeInstMapper m = session.getMapper(NodeInstMapper.class);
        seedNode(m, "N1", null, NodeType.PARALLEL.getStatus());
        seedNode(m, "N2", null, NodeType.IF.getStatus());
        seedNode(m, "N11", "N1", NodeType.ACTION.getStatus());

        List<NodeInstEntity> roots = m.findRoots("P1");
        Assert.assertEquals("顶层根按 auto_id 升序 = 声明顺序", 2, roots.size());
        Assert.assertEquals("N1", roots.get(0).getNodeInstId());
        Assert.assertEquals("N2", roots.get(1).getNodeInstId());
        Assert.assertEquals(1, m.findChildren("P1", "N1").size());
        Assert.assertEquals("created", m.findChildren("P1", "N1").get(0).getStatus());

        Assert.assertEquals(0, m.insertIgnore(node("N1", "P1", null, NodeType.PARALLEL.getStatus())));   // 幂等: 唯一键命中则忽略

        Assert.assertEquals(1, m.markRunning("N1", null));
        Assert.assertEquals(1, m.updateStatusResult("N1", "success", "{\"agg\":1}", null));
        NodeInstEntity n = m.findById("N1");
        Assert.assertEquals("success", n.getStatus());
        Assert.assertEquals("{\"agg\":1}", n.getResult());

        Assert.assertEquals(1, m.updateConfig("N2", "{\"__chosen\":\"then\"}", null));
        Assert.assertEquals("created", m.findById("N2").getStatus());   // 落 config 不动状态
        Assert.assertEquals("{\"__chosen\":\"then\"}", m.findById("N2").getConfig());

        Assert.assertEquals(1, m.casStatus("N2", "created", "pending", null));
        Assert.assertEquals("pending", m.findById("N2").getStatus());
    }

    // ===================== lane + action =====================

    @Test
    public void laneAndActionContainerQueries() {
        NodeInstMapper nm = session.getMapper(NodeInstMapper.class);
        LaneMapper lm = session.getMapper(LaneMapper.class);
        ActionInstMapper am = session.getMapper(ActionInstMapper.class);
        seedNode(nm, "NS", null, NodeType.ACTION.getStatus());

        LaneEntity l1 = lane("L1", "NS", "db-01");
        LaneEntity l2 = lane("L2", "NS", "db-02");
        Assert.assertEquals(1, lm.insertIgnore(l1));
        Assert.assertEquals(0, lm.insertIgnore(lane("LX", "NS", "db-01")));   // uk_node_shard 命中
        Assert.assertEquals(1, lm.insertIgnore(l2));
        Assert.assertEquals(2, lm.findByNode("NS").size());
        Assert.assertEquals("L1", lm.findByNodeAndShard("NS", "db-01").getLaneId());
        Assert.assertNull(lm.findByNodeAndShard("NS", "db-99"));

        // 分片行(带 shard_key)与非分片行(lane_id/shard_key 均 NULL)分别落在两个容器里
        Assert.assertEquals(1, am.insertIgnore(action("A1", "NS", "L1", "check", "db-01")));
        ActionInstEntity gen = action("A2", "NS", null, "apply", null);
        Assert.assertEquals(1, am.insertIgnore(gen));
        Assert.assertNotNull("useGeneratedKeys 应回填 autoId(承载声明顺序)", gen.getAutoId());
        Assert.assertEquals(0, am.insertIgnore(action("AX", "NS", "L1", "check", "db-01")));  // uk_node_def_shard 命中

        Assert.assertEquals(1, am.findInContainer("NS", "L1").size());
        Assert.assertEquals("A2", am.findInContainer("NS", null).get(0).getActionInstId());
        Assert.assertEquals("A1", am.findByContainerAndDef("NS", "L1", "check").getActionInstId());
        Assert.assertNull(am.findByContainerAndDef("NS", "L1", "apply"));

        // 列表查询: 给 nodeInstId = 跨 lane 全拿(声明顺序), 给 actionInstId = 按主键 0/1 条
        List<ActionInstEntity> byNode = am.find("NS", null);
        Assert.assertEquals(2, byNode.size());
        Assert.assertEquals("A1", byNode.get(0).getActionInstId());
        Assert.assertEquals("A2", am.find(null, "A2").get(0).getActionInstId());
        Assert.assertTrue(am.find(null, "A9").isEmpty());
        Assert.assertEquals(1, am.find("NS", "A1").size());
        Assert.assertTrue(am.find("NX", null).isEmpty());

        // CAS 前态不符 = 0 行, 且不涨 visit_count
        Assert.assertEquals(0, am.casStatus("A2", "failed", "created", null));
        Assert.assertEquals(Integer.valueOf(0), am.findById("A2").getVisitCount());

        // 落 result 才 visit_count++(重跑观测)
        Assert.assertEquals(1, am.updateResult("A1", "success", "{\"rows\":5}", "tester"));
        Assert.assertEquals(Integer.valueOf(1), am.findById("A1").getVisitCount());
        Assert.assertEquals("success", am.findById("A1").getStatus());

        LaneEntity agg = lm.findByNodeAndShard("NS", "db-01");
        Assert.assertEquals(1, lm.updateStatusResult(agg.getLaneId(), "success", "{\"lane\":1}", null));
        Assert.assertEquals("{\"lane\":1}", lm.findById(agg.getLaneId()).getResult());
        Assert.assertEquals("success", lm.findById(agg.getLaneId()).getStatus());
    }

    // ===================== process_definition =====================

    @Test
    public void definitionResolvesVersionAndLatestPublished() {
        ProcessDefinitionMapper m = session.getMapper(ProcessDefinitionMapper.class);
        Assert.assertEquals(1, m.insert(def("db-migration", 1, DefinitionStatus.DRAFT)));
        Assert.assertEquals(1, m.insert(def("db-migration", 2, DefinitionStatus.PUBLISHED)));
        Assert.assertEquals(1, m.insert(def("db-migration", 3, DefinitionStatus.PUBLISHED)));

        Assert.assertEquals(Integer.valueOf(1), m.find("db-migration", 1, null).getVersion());
        Assert.assertEquals("取大版本优先", Integer.valueOf(3), m.findLatestPublished("db-migration").getVersion());
        Assert.assertEquals("枚举入参按发布态取最新", Integer.valueOf(1), m.find("db-migration", null, DefinitionStatus.DRAFT).getVersion());
        Assert.assertNull("DRAFT 口径不命中已发布版", m.find("no-such-def", null, DefinitionStatus.DRAFT));
        Assert.assertNull(m.find("db-migration", 9, null));
        Assert.assertNull(m.find("no-such-def", null, null));
    }

    // ===================== rollback_record =====================

    @Test
    public void rollbackHistoryOrderedAndUpdatable() {
        RollbackRecordMapper m = session.getMapper(RollbackRecordMapper.class);
        Assert.assertEquals(1, m.insert(rollback("R1", "A1", RollbackStatus.COMPENSATING)));
        Assert.assertEquals(1, m.insert(rollback("R2", "A1", RollbackStatus.COMPENSATED)));
        Assert.assertEquals(1, m.insert(rollback("R3", "A2", RollbackStatus.BLOCKED)));

        List<RollbackRecordEntity> proc = m.findByProcess("P1");
        Assert.assertEquals("按补偿执行顺序(auto_id)升序", "R1", proc.get(0).getRollbackId());
        Assert.assertEquals(3, proc.size());
        Assert.assertEquals("R2", m.findLatestByAction("A1").getRollbackId());
        Assert.assertEquals("A2", m.findLatestByAction("A2").getActionInstId());
        Assert.assertNull(m.findLatestByAction("A9"));

        Assert.assertEquals(1, m.updateStatus("R1", RollbackStatus.COMPENSATE_FAILED.getStatus(), "{\"error\":\"x\"}", "tester"));
        RollbackRecordEntity r1 = m.find("P1", "A1").get(0);
        Assert.assertEquals(RollbackStatus.COMPENSATE_FAILED.getStatus(), r1.getStatus());
        Assert.assertEquals("{\"error\":\"x\"}", r1.getDetail());
        Assert.assertNotNull(r1.getOperator());
    }

    // ---------- 造数 ----------

    /**
     * 直接跑真实 db/schema.sql, 只去掉 H2 不兼容的一点:
     * 二级索引名在 H2 里全库唯一(MySQL 是按表唯一), 而 process_node_inst/process_lane/process_action_inst 都叫 idx_proc。
     * 二级索引与 mapper 行为无关, 剔掉即可(顺带清掉因而产生的尾逗号)。
     */
    private String schema() throws Exception {
        StringBuilder out = new StringBuilder();
        try (java.io.InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream("db/schema.sql")) {
            Assert.assertNotNull("找不到 db/schema.sql", in);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            for (String line : new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                String t = line.trim();
                if (t.startsWith("--")) {      // 整行注释先剔掉: 注释文本里也有 ';', 会干扰切句
                    continue;
                }
                if (t.startsWith("KEY ")) {     // 保留 UNIQUE KEY(它们才是幂等物化依赖的约束)
                    continue;
                }
                out.append(line).append('\n');
            }
        }
        return out.toString().replaceAll(",\\s*\\)", ")");
    }

    /** 按 ';' 切句, 但字面量里的 ';' 不算分隔符(schema.sql 的 COMMENT 文本里有)。 */
    private List<String> statements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (char c : sql.toCharArray()) {
            if (c == '\'') {
                quoted = !quoted;
                cur.append(c);
            } else if (c == ';' && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        out.removeIf(s -> s.trim().isEmpty());
        return out;
    }

    private void seedNode(NodeInstMapper m, String id, String parentId, String type) {
        Assert.assertEquals(1, m.insertIgnore(node(id, "P1", parentId, type)));
    }

    private NodeInstEntity node(String id, String proc, String parentId, String type) {
        NodeInstEntity n = new NodeInstEntity();
        n.setNodeInstId(id);
        n.setProcInstId(proc);
        n.setType(type);
        n.setParentId(parentId);
        n.setStatus("created");
        n.setErrorPolicy(ErrorPolicy.DEFAULT.getStatus());
        n.setShardEnabled(Boolean.FALSE);
        return n;
    }

    private LaneEntity lane(String id, String node, String shardKey) {
        LaneEntity l = new LaneEntity();
        l.setLaneId(id);
        l.setProcInstId("P1");
        l.setNodeInstId(node);
        l.setShardKey(shardKey);
        l.setStatus("created");
        l.setErrorPolicy(ErrorPolicy.DEFAULT.getStatus());
        return l;
    }

    private ActionInstEntity action(String id, String node, String laneId, String defId, String shardKey) {
        ActionInstEntity a = new ActionInstEntity();
        a.setActionInstId(id);
        a.setNodeInstId(node);
        a.setLaneId(laneId);
        a.setActionDefId(defId);
        a.setHandlerName(defId + "-handler");
        a.setShardKey(shardKey);
        a.setStatus("created");
        a.setConfig("{}");
        return a;
    }

    private ProcessDefinitionEntity def(String defId, int version, DefinitionStatus status) {
        ProcessDefinitionEntity d = new ProcessDefinitionEntity();
        d.setProcessDefId(defId);
        d.setVersion(version);
        d.setName(defId);
        d.setDefinition("flow: []");
        d.setStatus(status.getStatus());
        d.setCreatedBy("tester");
        return d;
    }

    private RollbackRecordEntity rollback(String id, String actionInstId, RollbackStatus status) {
        RollbackRecordEntity r = new RollbackRecordEntity();
        r.setRollbackId(id);
        r.setProcInstId("P1");
        r.setActionInstId(actionInstId);
        r.setActionDefId("check");
        r.setStatus(status.getStatus());
        r.setOperator("tester");
        r.setDetail("{}");
        return r;
    }
}
