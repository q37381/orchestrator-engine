package com.tesla.ities.process.core.context;

import com.tesla.ities.process.core.define.DefinitionLoader;
import com.tesla.ities.process.core.define.NodeDef;
import com.tesla.ities.process.core.define.ProcessDefinition;
import com.tesla.ities.process.core.runtime.ContextFactory;
import com.tesla.ities.process.core.spi.ShardResolverRegistry;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.domain.ProcessInstEntity;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * 上下文读法的两档口径: getXxx(key)/get(key) 只读本层(平铺键, 不上溯不下钻);
 * resolveXxx(key) 首段命中则桶内下钻、否则逐级上溯。整桶只有 config()/params()/result()。
 * 全部按 key 的读法返回泛型 T(调用方不强转); userId()/vars 与 putVar 同族: 任意层上溯到流程。
 * 分片解析不再包专用载体: ShardResolver 直接收本节点的 NodeContext。
 *
 * 装配口径: 上下文工厂 {@code @InjectMocks}(分片注册表是 @Mock —— 本类只走不碰它的 process/node/buildPayload
 * 空值分支), 上下文与实例行都是真实对象: 被测的正是它们自己的桶语义。
 */
@RunWith(MockitoJUnitRunner.class)
public class ContextResolveTest {

    @Mock
    private ShardResolverRegistry shardRegistry;

    @InjectMocks
    private ContextFactory factory;

    /** 造一个节点上下文: NodeContext 直接带实例行(此处行只装业务键, 不带定义)。 */
    private static NodeContext nodeCtx(ProcessContext parent, String nodeInstId) {
        NodeInstEntity row = new NodeInstEntity();
        row.setNodeInstId(nodeInstId);
        return new NodeContext(parent, row);
    }

    @Test
    public void resolveConfigReadsOwnLayerThenBubblesUp() {
        ProcessContext p = new ProcessContext("P1");
        p.putConfig("table", "t_proc");
        p.putConfig("db", java.util.Collections.singletonMap("host", "h1"));

        NodeContext n = nodeCtx(p, "N1");
        n.putConfig("table", "t_node");

        Assert.assertEquals("本层优先(innermost 胜)", "t_node", n.resolveConfig("table"));
        Assert.assertEquals("整桶只给本层", 1, n.config().size());
        String own = n.getConfig("table");                 // keyed 直取: 本层 + 泛型, 不强转
        Assert.assertEquals("t_node", own);

        NodeContext bare = nodeCtx(p, "N2");
        Assert.assertNull("直取不上溯: 父层有 table 也看不见", bare.getConfig("table"));
        Assert.assertEquals("本层未命中则上溯", "t_proc", bare.resolveConfig("table"));
        Assert.assertEquals("点号嵌套下钻", "h1", bare.resolveConfig("db.host"));
        Assert.assertNull("解不出返回 null", bare.resolveConfig("db.port"));
    }

    @Test
    public void resolveParamsAndResultKeepSameShape() {
        ProcessContext p = new ProcessContext("P1");
        p.putParams("env", "prod");
        // 注意: resolve* 按首段定位后下钻, 所以上溯读必须靠真嵌套 map(不是带点的平铺键)
        p.putResult("check", java.util.Collections.singletonMap("rows",
                java.util.Collections.singletonMap("n", 5)));

        ActionContext a = new ActionContext(p, "A1", "check", null, null);
        a.putParams("env", "stage");
        a.putResult("local", 1);

        Assert.assertEquals("stage", a.resolveParams("env"));
        java.util.Map<?, ?> rows = a.resolveResult("check.rows");     // 泛型直接接住嵌套 map, 不强转
        Assert.assertEquals(5, rows.get("n"));
        Object local = a.resolveResult("local");   // 接 Object 再断言: 泛型实参会让 assertEquals(long,long)/(Object,Object) 歧义
        Assert.assertEquals(1, local);
    }

    /**
     * getXxx(key) 只读**本层那一桶**(不上溯、不解析点号路径), 跟 resolveXxx(key) 的跳层下钻口径互不混淆。
     * 三桶之间**不做跨桶合并读**: 要哪桶就写哪桶, 别留"看调用方记不记得优先级"的暗坑。
     */
    @Test
    public void ownLayerReadsStayInTheirOwnBucket() {
        ProcessContext p = new ProcessContext("P1");
        p.putConfig("shared", "cfg-process");
        p.putParams("onlyParams", "par-process");
        p.putVar("dialect", "mysql");

        NodeContext n = nodeCtx(p, "N1");
        n.putConfig("shared", "cfg-node");
        n.putConfig("table.name", "flat-key");     // 带点的平铺键
        n.putResult("shared", "res-node");

        Assert.assertEquals("result 桶自己那份", "res-node", n.getResult("shared"));
        Assert.assertEquals("config 桶不受同名 result 影响", "cfg-node", n.getConfig("shared"));
        Assert.assertNull("params 桶没写过就是 null", n.getParams("shared"));
        Assert.assertEquals("平铺键整键命中", "flat-key", n.getConfig("table.name"));
        Assert.assertNull("本层直取不解析点号路径", n.getConfig("table.name.sub"));
        Assert.assertNull("本层直取不上溯", n.getParams("onlyParams"));
        Assert.assertEquals("要上溯就用 resolveParams", "par-process", n.resolveParams("onlyParams"));
        Assert.assertNull("三桶都没有", n.getConfig("nothing"));

        String typed = n.getResult("shared");              // 赋值即强转, 调用方不写 (String)
        Assert.assertEquals("res-node", typed);
        String dialect = n.getVar("dialect");              // vars 例外: 只有流程级, 本就必须委派
        Assert.assertEquals("mysql", dialect);

        NodeContext bare = nodeCtx(p, "N2");               // 本层三桶空 -> 看不见父层的值
        Assert.assertNull(bare.getConfig("shared"));
        Assert.assertEquals("cfg-process", bare.resolveConfig("shared"));
    }

    /**
     * vars 桶不收 null(顶层是 ConcurrentHashMap, put(null value) 直接 NPE):
     * 定义 vars / 创建参数里某键写了但留空, 或 handler putVar(key, null) 清空变量, 都不能炸驱动、不留残值。
     */
    @Test
    public void nullValuedVarsAreSkippedOrRemoveTheKey() {
        ProcessInstEntity pi = new ProcessInstEntity();
        pi.setProcInstId("P1");
        pi.setVars("{\"dialect\":null,\"table\":\"t1\",\"nested\":{\"a\":null}}");   // 顶层空值 + 嵌套空值

        ProcessContext p = factory.process(pi);        // 重建上下文不能因空值报 NPE
        Assert.assertEquals("t1", p.getVar("table"));
        Assert.assertNull("空值键被跳过(等于没这个变量)", p.<Object>getVar("dialect"));
        Assert.assertTrue("嵌套 map 本身非 null, 照常装", p.vars().containsKey("nested"));

        p.putVar("table", null);                       // 清空 = 删键, 不炸
        Assert.assertFalse(p.vars().containsKey("table"));
        p.putVar("nested.a", null);                    // 点号路径的叶子同样能清
        Assert.assertNull(p.<Object>getVar("nested.a"));
        Assert.assertTrue("跳过/清空都算刷过 vars, 收尾照旧刷库", p.isVarsDirty());
    }

    /**
     * 流程级 result 的两档口径: 从库重建的整桶灌入**不置脏**(否则驱动一次就把原值原样刷回去),
     * 只有本次驱动里的 putResult 才让引擎脏刷 process_inst.result。
     */
    @Test
    public void rebuiltProcessResultIsReadableButNotDirty() {
        ProcessInstEntity pi = new ProcessInstEntity();
        pi.setProcInstId("P1");
        pi.setResult("{\"check\":{\"rows\":5}}");

        ProcessContext p = factory.process(pi);
        Object rows = p.resolveResult("check.rows");             // 已落库的流程级 result 重建后照常上溯可读
        Assert.assertEquals(5, rows);
        Assert.assertFalse("重建不算脏写", p.isResultDirty());

        p.putResult("fresh", 1);
        Assert.assertTrue("本次驱动写过才脏", p.isResultDirty());
    }

    @Test
    public void userIdIsFlowLevel() {
        ProcessContext p = new ProcessContext("P1");
        p.setUserId("u1");

        NodeContext n = nodeCtx(p, "N1");
        LaneContext l = new LaneContext(n, "L1", "db-01");
        ActionContext a = new ActionContext(l, "A1", "check", null, null);

        Assert.assertEquals("u1", p.userId());
        Assert.assertEquals("子层上溯到流程创建人", "u1", a.userId());
        Assert.assertNull("未捕获用户时为 null(不阻断)",
                nodeCtx(new ProcessContext("P2"), "N2").userId());
    }

    /** 钉死版本的定义挂在流程级上下文上, 子层经上溯读不到定义 —— 定义访问是流程级能力。 */
    @Test
    public void definitionExposesNodeDefinitions() {
        ProcessContext p = new ProcessContext("P1");
        ProcessDefinition def = new ProcessDefinition();
        NodeDef nd = new NodeDef();
        def.setNodes(new java.util.LinkedHashMap<String, NodeDef>());
        def.getNodes().put("apply", nd);

        Assert.assertTrue("未挂载定义时为空而不抛", p.nodes().isEmpty());
        p.attachDefinition(def);
        Assert.assertSame(nd, p.nodeDefinition("apply"));
        Assert.assertEquals(1, p.nodes().size());
    }

    /**
     * 节点展示名与定义: YAML name 优先, 未声明回落定义 id, 无 NodeDef(控制节点/未对齐行)回落 nodeInstId。
     * name/definition 都是定义级属性, 不落实例表(同监听器链), 靠对齐期挂载的 transient def 解析。
     */
    @Test
    public void nodeNameResolvesNameThenIdThenInstId() {
        ProcessDefinition def = new DefinitionLoader().load(
                "nodes:\n"
              + "  migrateData:\n"
              + "    name: 数据迁移\n"
              + "    shard: dbTopology\n"
              + "    handler: sqlCheck\n"
              + "  postCheck:\n"
              + "    handler: sqlCheck\n");
        Assert.assertEquals("YAML name 被解析", "数据迁移", def.node("migrateData").getName());
        Assert.assertEquals("id 仍是 map 键(引用键不受改名影响)", "migrateData", def.node("migrateData").getId());
        Assert.assertEquals("未声明 name 回落 id", "postCheck", def.node("postCheck").displayName());

        // node() 不碰分片注册表(@Mock 的那份只在 buildPayload 命中 resolver 时才用), 直接测上下文构建
        ProcessContext p = new ProcessContext("P1");

        NodeInstEntity leaf = new NodeInstEntity();
        leaf.setNodeInstId("N1");
        leaf.setDef(def.node("migrateData"));
        NodeContext named = factory.node(p, leaf);
        Assert.assertEquals("对齐后的叶子行取定义 name", "数据迁移", named.name());
        Assert.assertSame("定义随上下文传入策略/resolver", def.node("migrateData"), named.definition());
        Assert.assertEquals("声明级引用键", "migrateData", named.nodeRef());
        Assert.assertEquals("节点级声明参数可读", "dbTopology", named.definition().getShard());

        leaf.setDef(def.node("postCheck"));
        Assert.assertEquals("无 name 则取定义 id", "postCheck", factory.node(p, leaf).name());

        leaf.setDef(null);                                   // 控制节点 / findById 直接取回的行
        NodeContext bare = factory.node(p, leaf);
        Assert.assertEquals("无定义时回落实例业务键", "N1", bare.name());
        Assert.assertNull("无定义时 definition/nodeRef 为 null", bare.definition());
        Assert.assertNull(bare.nodeRef());
    }

    /**
     * ShardResolver 的入参就是本节点的 NodeContext(已不再包一层 ShardContext):
     * 节点级 config/定义在本层读, 流程级创建参数(落库就叫 config)与 procInstId 沿父链上溯, 且逐节点一份不串参数。
     */
    @Test
    public void nodeContextServesShardResolverDirectly() {
        ProcessDefinition def = new DefinitionLoader().load(
                "nodes:\n"
              + "  migrateData:\n"
              + "    name: 数据迁移\n"
              + "    shard: dbTopology\n"
              + "    handler: sqlCheck\n");
        ProcessInstEntity pi = new ProcessInstEntity();
        pi.setProcInstId("P1");
        pi.setConfig("{\"endpoints\":[{\"host\":\"flow\"}]}");   // 创建输入落流程级 config(params 不落库)
        pi.setCreatorId("u1");

        NodeInstEntity leaf = new NodeInstEntity();
        leaf.setNodeInstId("N1");
        leaf.setShardResolver("dbTopology");
        leaf.setDef(def.node("migrateData"));
        leaf.setConfig("{\"endpoints\":[{\"host\":\"h1\",\"port\":3306}]}");   // 物化后落库的节点级 config

        // node()/process() 均由工厂从行重建(无需再读 JSON); params 桶无持久化来源
        NodeContext ctx = factory.node(factory.process(pi), leaf);

        java.util.List<?> nodeEps = ctx.resolveConfig("endpoints");    // 节点级优先(innermost 胜, 泛型直接接 List)
        Assert.assertEquals("节点级 config 可覆盖分片范围", "h1", ((java.util.Map<?, ?>) nodeEps.get(0)).get("host"));
        java.util.List<?> flowEps = nodeCtx(factory.process(pi), "N2")
                .resolveConfig("endpoints");                          // 本层没写 -> 沿父链上溯到流程 config 桶
        Assert.assertEquals("流程创建参数沿父链可读", "flow", ((java.util.Map<?, ?>) flowEps.get(0)).get("host"));
        Assert.assertTrue("流程层 params 桶不再有持久化来源", factory.process(pi).params().isEmpty());
        Assert.assertEquals("P1", ctx.process().procInstId());
        Assert.assertEquals("u1", ctx.userId());
        Assert.assertEquals("数据迁移", ctx.name());
        Assert.assertEquals("dbTopology", ctx.definition().getShard());

        // 非分片 action 无 shardKey / 节点未声明 resolver -> buildPayload 自然返回 null(不碰注册表)
        Assert.assertNull(factory.buildPayload(ctx, null));
        Assert.assertNull(factory.buildPayload(nodeCtx(factory.process(pi), "N9"), "db-01"));

        // 同流程内另一个无定义的行 -> 各自一份, 互不串参数
        NodeContext other = nodeCtx(factory.process(pi), "N2");
        Assert.assertNull(other.definition());
        Assert.assertNull(other.nodeRef());
        Assert.assertEquals("N2", other.name());
    }
}
