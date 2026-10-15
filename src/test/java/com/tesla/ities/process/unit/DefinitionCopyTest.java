package com.tesla.ities.process.unit;

import com.tesla.ities.process.core.define.ActionDef;
import com.tesla.ities.process.core.define.DefinitionLoader;
import com.tesla.ities.process.core.define.DslParser;
import com.tesla.ities.process.core.define.ProcessDefinition;
import com.tesla.ities.process.core.model.ErrorPolicy;
import com.tesla.ities.process.domain.ProcessDefinitionEntity;
import com.tesla.ities.process.mapper.ProcessDefinitionMapper;
import com.tesla.ities.process.mapper.ProcessInstMapper;
import com.tesla.ities.process.core.runtime.DefinitionCache;
import com.tesla.ities.process.util.Json;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定义缓存的**借出隔离**契约(DefinitionCache 缓存 JSON 快照, 每次读回私有副本):
 *  - 同一 (defId, version) 两次借出是**不同对象**, 且嵌套容器也不共享引用;
 *  - 改副本(含 vars 嵌套子 map、节点 config、监听器 config、nodes 表、flow、actions 列表)**不会污染缓存**,
 *    下一次借出仍是定义原样——监听器/handler 手滑赋值不再留下永久脏定义;
 *  - 副本内容与原定义**逐字节等价**(canonical JSON 比对), 标量/嵌套结构都不丢;
 *  - 流程级单值声明(errorPolicy / autoRollback)同样随快照往返, 副本上改写不回灌缓存;
 *  - YAML 只在快照未命中时解析一次(第二次借出不得再查库)。
 *
 * 装配口径: 两张 mapper 是 {@code @Mock}(只回放 find), 缓存本体 {@code @InjectMocks};
 * DefinitionLoader 跑真身(@Spy)——本类钉的是缓存的隔离语义, 解析必须是真 YAML。
 */
@RunWith(MockitoJUnitRunner.class)
public class DefinitionCopyTest {

    private static final String YAML = String.join("\n",
            "materialization: eager",
            "errorPolicy: continue_on_error",
            "autoRollback: true",
            "vars:",
            "  table: t_order",
            "  limit: 5",
            "  ratio: 0.5",
            "  flag: true",
            "  dialects:",
            "    mysql:",
            "      lockSql: SELECT 1",
            "  list:",
            "    - a",
            "    - b",
            "setup: [acquire]",
            "setdown: [release]",
            "flow: \"migrate,PARALLEL(checkA,checkB),IF(v.ok, okBranch, skipBranch)\"",
            "listeners:",
            "  - name: audit",
            "    config:",
            "      level: info",
            "rollbackListeners:",
            "  - name: dbConn",
            "    config:",
            "      host: h1",
            "globalNodeListeners:",
            "  - name: gn",
            "    config:",
            "      gk: gv",
            "globalActionListeners:",
            "  - name: ga",
            "nodes:",
            "  acquire:",
            "    name: 取锁",
            "    handler: lockHandler",
            "    config:",
            "      key: lock",
            "      nested:",
            "        deep: 1",
            "  migrate:",
            "    shard: dbTopology",
            "    laneExecution: PARALLEL",
            "    actions:",
            "      - id: a1",
            "        handler: h1",
            "        config:",
            "          c: 1",
            "      - id: a2",
            "        handler: h2",
            "        when: \"${vars.flag} == true\"",
            "        listeners:",
            "          - name: al",
            "            config:",
            "              ak: av",
            "    listeners:",
            "      - name: nl",
            "        config:",
            "          nk: nv",
            "  checkA: { handler: a }",
            "  checkB: { handler: b }",
            "  okBranch: { handler: y }",
            "  skipBranch: { handler: n }",
            "  release: { handler: rel }");

    @Mock
    private ProcessDefinitionMapper defMapper;
    @Mock
    private ProcessInstMapper procMapper;
    @Spy
    private DefinitionLoader loader = new DefinitionLoader();
    @InjectMocks
    private DefinitionCache cache;

    /** 主用例那一条已发布定义(按 (defId, version) 精确定位, status 不参与收窄)。 */
    @Before
    public void publishCopyTestDefinition() {
        when(defMapper.find("copy-test", 1, null)).thenReturn(defRow("copy-test", 1, YAML));
    }

    private static ProcessDefinitionEntity defRow(String defId, int version, String yaml) {
        ProcessDefinitionEntity row = new ProcessDefinitionEntity();
        row.setProcessDefId(defId);
        row.setVersion(version);
        row.setDefinition(yaml);
        return row;
    }

    @Test
    public void eachBorrowReturnsADistinctObjectGraph() {
        ProcessDefinition a = cache.definition("copy-test", 1);
        ProcessDefinition b = cache.definition("copy-test", 1);

        Assert.assertNotSame("每次借出必须是新对象", a, b);
        Assert.assertNotSame("vars 顶层容器不共享", a.getVars(), b.getVars());
        Assert.assertNotSame("vars 嵌套子 map 也不共享(间接键选支就写在这层)",
                a.getVars().get("dialects"), b.getVars().get("dialects"));
        Assert.assertNotSame("nodes 表不共享", a.getNodes(), b.getNodes());
        Assert.assertNotSame("NodeDef 不共享", a.node("migrate"), b.node("migrate"));
        Assert.assertNotSame("监听器声明不共享", a.getListeners().get(0), b.getListeners().get(0));
        Assert.assertNotSame("监听器 config 不共享",
                a.getListeners().get(0).getConfig(), b.getListeners().get(0).getConfig());
        Assert.assertEquals("副本内容与原定义逐字节等价", Json.write(a), Json.write(b));
        // YAML 只解析一次: 第二次借出没回库
        verify(defMapper, times(1)).find("copy-test", 1, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void mutatingTheCopyDoesNotPolluteTheCache() {
        ProcessDefinition dirty = cache.definition("copy-test", 1);

        // 把各个已知的"外泄口"全写一遍: 这些在旧实现里都会永久改掉缓存
        dirty.getVars().put("table", "hacked");
        ((Map<String, Object>) dirty.getVars().get("dialects"))
                .put("oracle", new LinkedHashMap<>());
        dirty.node("acquire").getConfig().put("key", "hacked");
        ((Map<String, Object>) dirty.node("acquire").getConfig().get("nested")).put("deep", 999);
        dirty.getListeners().get(0).getConfig().put("level", "hacked");
        dirty.getRollbackListeners().get(0).getConfig().put("password", "明文口令");
        dirty.getGlobalNodeListeners().get(0).getConfig().put("gk", "hacked");
        dirty.node("migrate").getActions().get(0).getConfig().put("c", "hacked");
        dirty.node("migrate").getActions().add(new ActionDef());
        dirty.getNodes().remove("checkB");
        dirty.setFlow("evil");
        dirty.setErrorPolicy("fail_fast");
        dirty.setAutoRollback("false");
        dirty.setMaterialization(ProcessDefinition.Materialization.LAZY);

        ProcessDefinition fresh = cache.definition("copy-test", 1);
        Assert.assertEquals("vars 未被污染", "t_order", fresh.getVars().get("table"));
        Assert.assertEquals("嵌套 vars 未被污染", "SELECT 1",
                ((Map<?, ?>) ((Map<?, ?>) fresh.getVars().get("dialects")).get("mysql")).get("lockSql"));
        Assert.assertFalse("新加的 vars 键没漏进缓存", fresh.getVars().containsKey("oracle"));
        Assert.assertEquals("节点 config 未被污染", "lock", fresh.node("acquire").getConfig().get("key"));
        Assert.assertEquals("嵌套 config 未被污染", 1,
                ((Map<?, ?>) fresh.node("acquire").getConfig().get("nested")).get("deep"));
        Assert.assertEquals("监听器 config 未被污染", "info", fresh.getListeners().get(0).getConfig().get("level"));
        Assert.assertFalse("回退监听器里写的凭据没落进缓存",
                fresh.getRollbackListeners().get(0).getConfig().containsKey("password"));
        Assert.assertEquals("全局监听器链未被污染", "gv",
                fresh.getGlobalNodeListeners().get(0).getConfig().get("gk"));
        Assert.assertEquals("action config 未被污染", 1,
                fresh.node("migrate").getActions().get(0).getConfig().get("c"));
        Assert.assertEquals("actions 列表未被增删", 2, fresh.node("migrate").getActions().size());
        Assert.assertTrue("nodes 表未被删键", fresh.getNodes().containsKey("checkB"));
        Assert.assertEquals("flow 未被改写",
                "migrate,PARALLEL(checkA,checkB),IF(v.ok, okBranch, skipBranch)", fresh.getFlow());
        Assert.assertEquals("物化时机未被改写", ProcessDefinition.Materialization.EAGER, fresh.getMaterialization());
        Assert.assertTrue("物化时机未被改写", fresh.isEager());
        Assert.assertEquals("全局错误策略未被改写", ErrorPolicy.CONTINUE_ON_ERROR, fresh.globalErrorPolicy());
        Assert.assertEquals("自动回退开关未被改写", "true", fresh.getAutoRollback());
        Assert.assertTrue("副本上写的 false 没跟着污染缓存", fresh.autoRollbackEnabled());
    }

    @Test
    public void copyKeepsDeclarationTypesAndTopology() {
        ProcessDefinition d = cache.definition("copy-test", 1);

        // 标量类型保真(String/Integer/Double/Boolean 原样回来; 裸日期不在保证范围内, 见 DefinitionCache 类注释)
        Assert.assertEquals("t_order", d.getVars().get("table"));
        Assert.assertEquals(5, d.getVars().get("limit"));
        Assert.assertEquals(0.5, d.getVars().get("ratio"));
        Assert.assertEquals(Boolean.TRUE, d.getVars().get("flag"));
        Assert.assertEquals(java.util.Arrays.asList("a", "b"), d.getVars().get("list"));

        // DSL 不入快照, 副本各自惰性重解析: 拓扑必须一致
        List<DslParser.Expr> roots = d.roots();
        Assert.assertEquals(3, roots.size());
        Assert.assertTrue(roots.get(0) instanceof DslParser.Leaf);
        Assert.assertTrue(roots.get(1) instanceof DslParser.Parallel);
        Assert.assertEquals("PARALLEL 两子项", 2, ((DslParser.Parallel) roots.get(1)).items.size());
        Assert.assertTrue(roots.get(2) instanceof DslParser.If);
        Assert.assertEquals("v.ok", ((DslParser.If) roots.get(2)).condition);
        Assert.assertEquals("setup + flow + setdown", 5, d.orderedRoots().size());
        Assert.assertEquals(1, d.setupSize());
        Assert.assertEquals(1, d.setdownSize());
        Assert.assertEquals("自动回退开关随快照往返", "true", d.getAutoRollback());
        Assert.assertTrue("开关的派生判定读的是声明值", d.autoRollbackEnabled());

        // 派生标记走定义字段而非快照属性(节点自己优先/未声明回落全局)
        Assert.assertTrue(d.node("migrate").isSharded());
        Assert.assertTrue(d.node("migrate").isLaneParallel());
        Assert.assertEquals(ErrorPolicy.FAIL_FAST, d.effectiveErrorPolicy("fail_fast"));
        Assert.assertEquals(ErrorPolicy.CONTINUE_ON_ERROR, d.effectiveErrorPolicy(null));
        Assert.assertEquals("取锁", d.node("acquire").getName());
    }

    /** 新版本天然是新条目(不需要失效入口): 两个版本各查各的, 互不覆盖。 */
    @Test
    public void newVersionGetsItsOwnSnapshot() {
        ProcessDefinitionEntity v1 = defRow("ver-test", 1, "flow: a\nnodes:\n  a: { handler: h1 }");
        ProcessDefinitionEntity v2 = defRow("ver-test", 2, "flow: a,b\nnodes:\n  a: { handler: h1 }\n  b: { handler: h2 }");
        when(defMapper.find(eq("ver-test"), anyInt(), isNull()))
                .thenAnswer(inv -> Integer.valueOf(2).equals(inv.<Integer>getArgument(1)) ? v2 : v1);

        Assert.assertEquals(1, cache.definition("ver-test", 1).roots().size());
        Assert.assertEquals("新版本天然新条目, 不需失效入口", 2, cache.definition("ver-test", 2).roots().size());
        Assert.assertEquals(1, cache.definition("ver-test", 1).roots().size());
        // 每个版本各回库一次; 第三次借出命中快照(说明老版本没被新版本挤掉)
        verify(defMapper, times(1)).find("ver-test", 1, null);
        verify(defMapper, times(1)).find("ver-test", 2, null);
    }
}
