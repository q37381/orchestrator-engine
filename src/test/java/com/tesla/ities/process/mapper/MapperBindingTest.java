package com.tesla.ities.process.mapper;

import com.tesla.ities.process.config.ProcessEngineConfig;
import com.tesla.ities.process.core.model.DefinitionStatus;
import com.tesla.ities.process.core.model.RollbackStatus;
import com.tesla.ities.process.domain.ActionInstEntity;
import com.tesla.ities.process.domain.LaneEntity;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.domain.ProcessDefinitionEntity;
import com.tesla.ities.process.domain.ProcessInstEntity;
import com.tesla.ities.process.domain.RollbackRecordEntity;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Properties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mapper XML 与接口的绑定校验(不需要数据库):
 *  1) 六份 XML 能被 MyBatis 解析(resultMap / 动态标签合法), 且 resultMap 覆盖实体全部持久化字段;
 *  2) 接口里每个非 default 方法都有同名语句, 反向也不留多余语句(精简后没有死 SQL);
 *  3) 合并出的动态语句(updateState / findByParent / findInContainer / findByNode / find)
 *     在各种入参组合下拼出的 SQL 与原注解语义一致。
 */
public class MapperBindingTest {

    /** 接口名 -> 接口 + 实体(用于双向比对)。 */
    private static final Map<String, Class<?>[]> MAPPERS = new LinkedHashMap<>();

    private static Configuration conf;

    static {
        MAPPERS.put("ProcessInstMapper", new Class<?>[]{ProcessInstMapper.class, ProcessInstEntity.class});
        MAPPERS.put("NodeInstMapper", new Class<?>[]{NodeInstMapper.class, NodeInstEntity.class});
        MAPPERS.put("LaneMapper", new Class<?>[]{LaneMapper.class, LaneEntity.class});
        MAPPERS.put("ActionInstMapper", new Class<?>[]{ActionInstMapper.class, ActionInstEntity.class});
        MAPPERS.put("ProcessDefinitionMapper", new Class<?>[]{ProcessDefinitionMapper.class, ProcessDefinitionEntity.class});
        MAPPERS.put("RollbackRecordMapper", new Class<?>[]{RollbackRecordMapper.class, RollbackRecordEntity.class});
    }

    private static final String NS = "com.tesla.ities.process.mapper.";

    @BeforeClass
    public static void parseXmls() throws Exception {
        conf = new Configuration();
        conf.setVariables(ProcessEngineConfig.buildTableVariables("process_"));   // 默认命名空间
        for (String name : MAPPERS.keySet()) {
            String path = "META-INF/mapper/process/" + name + ".xml";
            try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
                Assert.assertNotNull("找不到 " + path, in);
                new XMLMapperBuilder(in, conf, path, conf.getSqlFragments()).parse();
            }
        }
    }

    // ===================== 1) resultMap 覆盖实体字段 =====================

    /** 可由 JDBC 读写的类型 = 落库字段; 其余(expr/def/listeners 这类)是 transient 走查光标, 不该进 resultMap。 */
    private static final List<Class<?>> PERSISTABLE = java.util.Arrays.asList(
            Long.class, Integer.class, Boolean.class, String.class, java.time.LocalDateTime.class);

    @Test
    public void resultMapsCoverEntityColumns() {
        for (Map.Entry<String, Class<?>[]> e : MAPPERS.entrySet()) {
            Class<?> entity = e.getValue()[1];
            List<String> mapped = new ArrayList<>();
            for (org.apache.ibatis.mapping.ResultMapping r :
                    conf.getResultMap(NS + e.getKey() + ".BaseResultMap").getResultMappings()) {
                mapped.add(r.getProperty());
            }
            List<String> columns = new ArrayList<>();
            List<String> transientProps = new ArrayList<>();
            for (Field f : entity.getDeclaredFields()) {
                if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (PERSISTABLE.contains(f.getType())) {
                    columns.add(f.getName());
                } else {
                    transientProps.add(f.getName());
                }
            }
            Collections.sort(mapped);
            Collections.sort(columns);
            Assert.assertEquals(entity.getSimpleName() + " 的 resultMap 应覆盖全部落库字段", columns, mapped);
            for (String t : transientProps) {
                Assert.assertFalse(entity.getSimpleName() + " 的 transient 字段 " + t + " 不该落库", mapped.contains(t));
            }
        }
    }

    // ===================== 2) 接口方法 <-> XML 语句一一对应 =====================

    @Test
    public void abstractMethodsAndStatementsMatch() {
        for (Map.Entry<String, Class<?>[]> e : MAPPERS.entrySet()) {
            String ns = e.getKey();
            Class<?> iface = e.getValue()[0];
            for (Method m : iface.getDeclaredMethods()) {
                if (m.isDefault()) {
                    continue;   // default 语义快捷不发 SQL
                }
                if (m.isSynthetic() || m.isBridge()) {
                    continue;   // 字节码插桩(如 JaCoCo 的 $jacocoInit)注入的合成方法, 非业务 SQL
                }
                Assert.assertTrue(ns + " 缺语句: " + m.getName(), conf.hasStatement(NS + ns + "." + m.getName()));
            }
            for (String id : statementIds(ns)) {
                String method = id.substring(id.lastIndexOf('.') + 1);
                Assert.assertTrue(ns + " 有多余语句: " + method, hasMethod(iface, method));
            }
        }
    }

    // ===================== 3) 合并后的动态 SQL 口径 =====================

    /** updateState: 只写传入的非 null 列; fromStatus 非 null 才追加 CAS 条件。 */
    @Test
    public void updateStateWritesOnlyGivenColumns() {
        Map<String, Object> p = params("id", "P1", "fromStatus", null, "toStatus", "running", "result", null, "vars", null, "config", null);
        Assert.assertEquals("UPDATE process_inst SET status = ? WHERE proc_inst_id = ?", sql("ProcessInstMapper.updateState", p));

        p.put("fromStatus", "pending");
        p.put("vars", "{}");
        Assert.assertEquals("UPDATE process_inst SET status = ?, vars = ? WHERE proc_inst_id = ? AND status = ?",
                sql("ProcessInstMapper.updateState", p));

        // node: CAS 激活(created -> pending) 与 只落 config(IF 决策) 走同一条语句
        p = params("id", "N1", "fromStatus", "created", "toStatus", "pending", "result", null, "config", null);
        Assert.assertEquals("UPDATE process_node_inst SET status = ? WHERE node_inst_id = ? AND status = ?",
                sql("NodeInstMapper.updateState", p));
        p.put("fromStatus", null);
        p.put("toStatus", null);
        p.put("config", "{\"__chosen\":\"then\"}");
        Assert.assertEquals("UPDATE process_node_inst SET config = ? WHERE node_inst_id = ?", sql("NodeInstMapper.updateState", p));
        p.put("config", null);
        p.put("result", "{}");
        Assert.assertEquals("UPDATE process_node_inst SET result = ? WHERE node_inst_id = ?", sql("NodeInstMapper.updateState", p));

        // action: 落 result 才 visit_count++(纯翻状态不涨)
        p = params("id", "A1", "fromStatus", null, "toStatus", "success", "result", "{}");
        Assert.assertEquals("UPDATE process_action_inst SET status = ?, result = ?, visit_count = visit_count + 1 WHERE action_inst_id = ?",
                sql("ActionInstMapper.updateState", p));
        p.put("fromStatus", "failed");
        p.put("toStatus", "created");
        p.put("result", null);
        Assert.assertEquals("UPDATE process_action_inst SET status = ? WHERE action_inst_id = ? AND status = ?",
                sql("ActionInstMapper.updateState", p));

        // lane: 只刷 result 不动 status
        p = params("id", "L1", "fromStatus", null, "toStatus", null, "result", "{}");
        Assert.assertEquals("UPDATE process_lane SET result = ? WHERE lane_id = ?", sql("LaneMapper.updateState", p));
    }

    /** 合并后的定位谓词: null 走 IS NULL / 不收窄分支, 非 null 走等值 + LIMIT 1。 */
    @Test
    public void mergedFindersKeepOriginalSemantics() {
        // node: 顶层根 vs 子行
        Map<String, Object> p = params("procInstId", "P1", "parentId", null);
        Assert.assertTrue(sql("NodeInstMapper.findByParent", p).contains("AND parent_id IS NULL"));
        Assert.assertFalse(sql("NodeInstMapper.findByParent", p).contains("parent_id ="));
        p.put("parentId", "N1");
        String child = sql("NodeInstMapper.findByParent", p);
        Assert.assertTrue(child.contains("AND parent_id = ?"));
        Assert.assertTrue("声明顺序由 auto_id 承载", child.endsWith("ORDER BY auto_id ASC"));

        // action: laneId=null -> 非分片行; actionDefId 非 null -> 自然键单条
        p = params("nodeInstId", "N1", "laneId", null, "actionDefId", null);
        String all = sql("ActionInstMapper.findInContainer", p);
        Assert.assertTrue(all.contains("AND lane_id IS NULL"));
        Assert.assertFalse(all.contains("action_def_id ="));
        Assert.assertTrue(all.endsWith("ORDER BY auto_id ASC"));
        p.put("laneId", "L1");
        p.put("actionDefId", "check-sql");
        String one = sql("ActionInstMapper.findInContainer", p);
        Assert.assertTrue(one.contains("AND lane_id = ?"));
        Assert.assertTrue(one.contains("AND action_def_id = ?"));
        Assert.assertTrue("非分片 shard_key=NULL 不去重, 自然键定位必须 LIMIT 1",
                one.endsWith("ORDER BY auto_id ASC LIMIT 1"));

        // lane: shardKey 非 null 才收窄到一条
        p = params("nodeInstId", "N1", "shardKey", null);
        Assert.assertEquals("SELECT auto_id, lane_id, proc_inst_id, node_inst_id, shard_key, status, "
                        + "error_policy, config, result, creator_id, updator_id, created_at, updated_at "
                        + "FROM process_lane WHERE node_inst_id = ? ORDER BY auto_id ASC",
                sql("LaneMapper.findByNode", p));
        p.put("shardKey", "db-01");
        Assert.assertTrue(sql("LaneMapper.findByNode", p).endsWith("ORDER BY auto_id ASC LIMIT 1"));

        // definition: version=null + status=null -> 缺省最新已发布; 显式传 DefinitionStatus -> SQL 取其小写规范值
        p = params("defId", "db-migration", "version", null, "status", null);
        Assert.assertTrue(sql("ProcessDefinitionMapper.find", p).contains("AND status = 'published' ORDER BY version DESC LIMIT 1"));
        p.put("status", DefinitionStatus.DRAFT);
        Assert.assertTrue("枚举入参经 #{status.status} 绑定", sql("ProcessDefinitionMapper.find", p).contains("AND status = ? ORDER BY version DESC LIMIT 1"));
        p.put("status", null);
        p.put("version", 3);
        Assert.assertTrue(sql("ProcessDefinitionMapper.find", p).contains("AND version = ?"));

        // action: 列表查询两个条件都可空, 各自独立收窄(不按 lane, 不加 LIMIT)
        p = params("nodeInstId", "N1", "actionInstId", null);
        String byNode = sql("ActionInstMapper.find", p);
        Assert.assertTrue(byNode.contains("WHERE node_inst_id = ?"));
        Assert.assertFalse("不按 lane 收窄", byNode.contains("lane_id = ?") || byNode.contains("lane_id IS NULL"));
        Assert.assertTrue(byNode.endsWith("ORDER BY auto_id ASC"));
        p.put("nodeInstId", null);
        p.put("actionInstId", "A1");
        Assert.assertTrue(sql("ActionInstMapper.find", p).contains("WHERE action_inst_id = ?"));

        // rollback: 两个条件都可空
        p = params("procInstId", "P1", "actionInstId", null);
        Assert.assertTrue(sql("RollbackRecordMapper.find", p).contains("WHERE proc_inst_id = ?"));
        p.put("procInstId", null);
        p.put("actionInstId", "A1");
        Assert.assertTrue(sql("RollbackRecordMapper.find", p).contains("WHERE action_inst_id = ?"));
    }

    /** 状态字面量小写: 全仓枚举统一落 getStatus() 小写(运行态/发布态/补偿态都是)。 */
    @Test
    public void statusLiteralsFollowConvention() {
        Assert.assertTrue(sql("ProcessInstMapper.findClaimableIds", params("limit", 16)).contains("status = 'pending'"));
        Assert.assertTrue(sql("NodeInstMapper.claim", params("id", "N1")).endsWith("AND n.status = 'pending' AND p.status = 'running'"));
        Assert.assertTrue(sql("LaneMapper.findClaimableLaneIds", params("limit", 16)).contains("l.status = 'pending'"));
        Assert.assertTrue(sql("ProcessDefinitionMapper.find", params("defId", "d", "version", null, "status", null)).contains("'published'"));
        Assert.assertTrue(sql("RollbackRecordMapper.updateStatus", params("id", "R1", "to", RollbackStatus.COMPENSATED.getStatus(), "detail", "{}", "updatorId", null))
                .contains("SET status = ?, detail = ?"));
    }

    /**
     * 表命名空间: ${orch.table.*} 在 XML 装载期做一次性文本替换(不是运行期拼接)。
     * 契约(与 ProcessEngineConfig.buildTableVariables 同口径): **7 张表一律 = 前缀 + 表名**,
     * process_definition / process_inst 也不例外——名字里的 "process" 就是默认前缀本身, 不是固定名的一部分。
     *  1) 默认前缀 process_ 已由其余用例的期望串证明;
     *  2) 自定义前缀下连 JOIN 链上的流程表一起换名(三条语句段同一前缀, 不留换不掉的死角);
     *  3) 空前缀(存量裸名逃逸)一律回落到裸名。
     */
    @Test
    public void tableNamespaceSwitchesAtParseTime() throws Exception {
        Configuration custom = parseWithPrefix("myapp_");
        Assert.assertEquals("UPDATE myapp_lane SET result = ? WHERE lane_id = ?",
                boundSql(custom, "LaneMapper.updateState",
                        params("id", "L1", "fromStatus", null, "toStatus", null, "result", "{}")));
        String join = boundSql(custom, "ActionInstMapper.claim", params("id", "A1"));
        Assert.assertTrue("JOIN 链上三张表都随前缀换名: " + join,
                join.startsWith("UPDATE myapp_action_inst a")
                        && join.contains("JOIN myapp_node_inst n")
                        && join.contains("JOIN myapp_inst p"));

        Configuration bare = parseWithPrefix("");   // 存量裸名环境: tablePrefix="" 逃逸
        Assert.assertEquals("UPDATE lane SET result = ? WHERE lane_id = ?",
                boundSql(bare, "LaneMapper.updateState",
                        params("id", "L1", "fromStatus", null, "toStatus", null, "result", "{}")));
        Assert.assertTrue("流程表同样回落裸名(消费者库里撞 process_inst 时有处可逃)",
                boundSql(bare, "ProcessInstMapper.findClaimableIds", params("limit", 16)).contains("FROM inst"));
    }

    /** 用指定前缀重新装载六份 XML(变量在解析期烙进语句文本, 故必须新建 Configuration 重解)。 */
    private Configuration parseWithPrefix(String prefix) throws Exception {
        Properties vars = ProcessEngineConfig.buildTableVariables(prefix);
        Configuration c = new Configuration();
        c.setVariables(vars);
        for (String name : MAPPERS.keySet()) {
            String path = "META-INF/mapper/process/" + name + ".xml";
            try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
                Assert.assertNotNull("找不到 " + path, in);
                new XMLMapperBuilder(in, c, path, c.getSqlFragments()).parse();
            }
        }
        return c;
    }

    private String boundSql(Configuration c, String stmt, Object param) {
        return oneLine(c.getMappedStatement(NS + stmt).getBoundSql(param).getSql());
    }

    // ---------- 内部 ----------

    private List<String> statementIds(String ns) {
        List<String> ids = new ArrayList<>();
        Collection<String> names = conf.getMappedStatementNames();
        for (Object o : names.toArray()) {
            String name = String.valueOf(o);
            if (name.startsWith(NS + ns + ".")) {
                ids.add(name);
            }
        }
        return ids;
    }

    private boolean hasMethod(Class<?> iface, String name) {
        for (Method m : iface.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private String sql(String stmt, Object param) {
        MappedStatement ms = conf.getMappedStatement(NS + stmt);
        return oneLine(ms.getBoundSql(param).getSql());
    }

    private String oneLine(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    /** 成对构造入参(k1,v1,k2,v2...), 值可为 null。 */
    private Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
