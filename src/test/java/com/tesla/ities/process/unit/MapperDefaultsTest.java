package com.tesla.ities.process.unit;

import com.tesla.ities.process.config.ProcessEngineConfig;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.domain.ActionInstEntity;
import com.tesla.ities.process.domain.LaneEntity;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.mapper.ActionInstMapper;
import com.tesla.ities.process.mapper.LaneMapper;
import com.tesla.ities.process.mapper.NodeInstMapper;
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

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * mapper 语义快捷 default 方法的转发回归: 这些便捷方法(setStatus/casStatus/activate/updateResult)
 * 由引擎外的调用方或备用认领路径使用, 主链未触达, 用真实 H2 逐条打通它们到 updateState 的委托。
 */
public class MapperDefaultsTest {

    private SqlSession session;

    @Before
    public void setUp() throws Exception {
        DataSource ds = dataSource();
        clean(ds);
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(ds);
        bean.setTypeAliasesPackage("com.tesla.ities.process.domain");
        bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:META-INF/mapper/process/*.xml"));
        Configuration conf = new Configuration();
        conf.setVariables(ProcessEngineConfig.buildTableVariables("process_"));
        bean.setConfiguration(conf);
        SqlSessionFactory factory = bean.getObject();
        session = factory.openSession(true);
    }

    @After
    public void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private static DataSource dataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:orchMapperDefaults;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        return ds;
    }

    /** 重建表结构: 跑真实 db/schema.sql, 只滤掉 H2 不兼容的全库唯一二级索引名(KEY 行), 保留 UNIQUE KEY。 */
    private static void clean(DataSource ds) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
            for (String stmt : statements(schemaText())) {
                st.execute(stmt);
            }
        }
    }

    private static String schemaText() throws Exception {
        StringBuilder out = new StringBuilder();
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream("db/schema.sql")) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            for (String line : new String(bos.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
                String t = line.trim();
                if (t.startsWith("--") || t.startsWith("KEY ")) {
                    continue;
                }
                out.append(line).append('\n');
            }
        }
        return out.toString().replaceAll(",\\s*\\)", ")");
    }

    private static List<String> statements(String sql) {
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

    private NodeInstEntity nodeRow(String id) {
        NodeInstEntity n = new NodeInstEntity();
        n.setNodeInstId(id);
        n.setProcInstId("P");
        n.setType("action");
        n.setStatus(Status.CREATED.getStatus());
        n.setErrorPolicy("fail_fast");
        n.setShardEnabled(false);
        return n;
    }

    private LaneEntity laneRow(String id) {
        LaneEntity l = new LaneEntity();
        l.setLaneId(id);
        l.setProcInstId("P");
        l.setNodeInstId("N");
        l.setShardKey("db-01");
        l.setStatus(Status.CREATED.getStatus());
        l.setErrorPolicy("fail_fast");
        return l;
    }

    private ActionInstEntity actionRow(String id) {
        ActionInstEntity a = new ActionInstEntity();
        a.setActionInstId(id);
        a.setNodeInstId("N");
        a.setActionDefId("d");
        a.setHandlerName("h");
        a.setStatus(Status.CREATED.getStatus());
        a.setConfig("{}");
        return a;
    }

    @Test
    public void nodeInstActivateMarkRunningCas() {
        NodeInstMapper m = session.getMapper(NodeInstMapper.class);
        Assert.assertEquals(1, m.insertIgnore(nodeRow("N1")));
        // activate: created -> pending
        Assert.assertEquals(1, m.activate("N1", "u"));
        Assert.assertEquals(Status.PENDING.getStatus(), m.findById("N1").getStatus());
        // setStatus 直翻
        Assert.assertEquals(1, m.setStatus("N1", Status.RUNNING.getStatus(), "u"));
        Assert.assertEquals(Status.RUNNING.getStatus(), m.findById("N1").getStatus());
        // markRunning: -> running(已是 running 也返回 1)
        Assert.assertEquals(1, m.markRunning("N1", "u"));
        // casStatus 命中
        Assert.assertEquals(1, m.casStatus("N1", Status.RUNNING.getStatus(), Status.SUCCESS.getStatus(), "u"));
        Assert.assertEquals(0, m.casStatus("N1", Status.RUNNING.getStatus(), Status.SUCCESS.getStatus(), "u")); // 前态不符
        Assert.assertEquals(1, m.updateStatusResult("N1", Status.FAILED.getStatus(), "{\"e\":1}", "u"));
        Assert.assertEquals(1, m.updateConfig("N1", "{\"__chosen\":\"then\"}", "u"));
    }

    @Test
    public void laneActivateCasStatusUpdateResult() {
        LaneMapper m = session.getMapper(LaneMapper.class);
        Assert.assertEquals(1, m.insertIgnore(laneRow("L1")));
        Assert.assertEquals(1, m.activate("L1", "u"));                     // created -> pending
        Assert.assertEquals(1, m.setStatus("L1", Status.RUNNING.getStatus(), "u"));
        Assert.assertEquals(1, m.casStatus("L1", Status.RUNNING.getStatus(), Status.SUCCESS.getStatus(), "u"));
        Assert.assertEquals(1, m.updateStatusResult("L1", Status.SUCCESS.getStatus(), "{\"r\":1}", "u"));
        Assert.assertEquals(1, m.updateResult("L1", "{\"r\":2}", "u"));    // 只刷 result
        Assert.assertEquals("{\"r\":2}", m.findById("L1").getResult());
        Assert.assertNotNull(m.findByNodeAndShard("N", "db-01"));
    }

    @Test
    public void actionActivateStatusCasUpdateResult() {
        ActionInstMapper m = session.getMapper(ActionInstMapper.class);
        Assert.assertEquals(1, m.insertIgnore(actionRow("A1")));
        Assert.assertEquals(1, m.activate("A1", "u"));                      // created -> pending
        Assert.assertEquals(1, m.setStatus("A1", Status.RUNNING.getStatus(), "u"));
        Assert.assertEquals(1, m.casStatus("A1", Status.RUNNING.getStatus(), Status.FAILED.getStatus(), "u"));
        // updateResult 落 result -> visit_count++
        int before = m.findById("A1").getVisitCount();
        Assert.assertEquals(1, m.updateResult("A1", Status.SUCCESS.getStatus(), "{\"ok\":1}", "u"));
        Assert.assertEquals(before + 1, m.findById("A1").getVisitCount().intValue());
        // findByContainerAndDef 命中/未命中
        Assert.assertNotNull(m.findByContainerAndDef("N", null, "d"));
        Assert.assertNull(m.findByContainerAndDef("N", null, "ghost"));
    }
}
