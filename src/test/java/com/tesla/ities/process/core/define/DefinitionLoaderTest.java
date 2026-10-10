package com.tesla.ities.process.core.define;

import com.tesla.ities.process.core.model.ErrorPolicy;
import com.tesla.ities.process.exception.ProcessException;
import com.tesla.ities.process.util.Json;
import org.junit.Assert;
import org.junit.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * YAML -> ProcessDefinition 的解析回归(顺带钉住 SnakeYAML 2.x 迁移):
 *  - 2.0 起 SafeConstructor 无参构造被删, 只留 (LoaderOptions) —— 这里真实跑三份示例定义, 保证不只是编得过;
 *  - 三份示例定义(definitions/*.yml)在 main/resources 下, 测试类路径直接可读。
 */
public class DefinitionLoaderTest {

    private final DefinitionLoader loader = new DefinitionLoader();

    private ProcessDefinition loadResource(String path) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            Assert.assertNotNull("找不到定义资源: " + path, in);
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return loader.load(new String(out.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            throw new AssertionError("解析失败: " + path + " -> " + e, e);
        }
    }

    @Test
    public void dbMigrationKeepsSegmentsShardsAndDialectPkg() {
        ProcessDefinition def = loadResource("definitions/db-migration.yml");

        Assert.assertFalse("示例声明 LAZY", def.isEager());
        Assert.assertEquals(1, def.setupSize());
        Assert.assertEquals(1, def.setdownSize());
        Assert.assertEquals("flow 顶层扁平化: 逗号分隔的 5 个根", 5, def.roots().size());
        Assert.assertEquals("根行序 = setup + flow + setdown", 7, def.orderedRoots().size());

        NodeDef preCheck = def.node("preCheck");
        Assert.assertTrue(preCheck.isSharded());
        Assert.assertFalse("分片 lane 默认串行", preCheck.isLaneParallel());
        Assert.assertEquals("节点内 actions 直接按声明序解析", 3, preCheck.effectiveActions().size());
        Assert.assertEquals("声明单 handler 的节点由 effectiveActions 合成一条", 1,
                def.node("initTopology").effectiveActions().size());
        Assert.assertEquals("节点级监听器链按声明顺序挂定义", "authGuard",
                preCheck.getListeners().get(0).getName());

        NodeDef migrate = def.node("migrateData");
        Assert.assertTrue("laneExecution: PARALLEL", migrate.isLaneParallel());
        Assert.assertEquals("CONTINUE_ON_ERROR", migrate.getErrorPolicy());
        Assert.assertNull("本例逐节点写清, 不用全局 errorPolicy", def.getErrorPolicy());
        Assert.assertEquals("节点与全局都没声明 = 默认 FAIL_FAST", ErrorPolicy.FAIL_FAST,
                def.effectiveErrorPolicy(def.node("fetchTopology").getErrorPolicy()));
        Assert.assertEquals("节点内多 action 与行按下标对齐", 3, migrate.effectiveActions().size());

        // 方言包就是 vars 下的普通嵌套(引擎不认保留键); handler 自有模板 ${table} 原样保留
        Map<?, ?> dialects = (Map<?, ?>) def.getVars().get("dialects");
        Assert.assertEquals("mysql", def.getVars().get("dialect"));
        Map<?, ?> mysql = (Map<?, ?>) dialects.get("mysql");
        Assert.assertEquals("ALTER TABLE ${table} DISABLE KEYS", mysql.get("lockSql"));
        Assert.assertEquals("间接键写法不被提前吃掉", "${vars.dialects.${vars.dialect}.lockSql}",
                migrate.effectiveActions().get(0).getConfig().get("sql"));
    }

    @Test
    public void dataValidationKeepsEagerMaterializationAndActionListeners() {
        ProcessDefinition def = loadResource("definitions/data-validation.yml");

        Assert.assertTrue(def.isEager());
        List<DslParser.Expr> roots = def.roots();
        Assert.assertEquals("loadMeta,PARALLEL(...),summary -> 3 个根", 3, roots.size());
        Assert.assertTrue("第二个根是 PARALLEL", roots.get(1) instanceof DslParser.Parallel);
        Assert.assertEquals("PARALLEL 内 3 个校验", 3, ((DslParser.Parallel) roots.get(1)).items.size());

        NodeDef schema = def.node("schemaCheck");
        Assert.assertEquals("表结构校验", schema.displayName());
        Assert.assertTrue(schema.isSharded());
        Assert.assertEquals("全局 errorPolicy 生效于未声明的节点", ErrorPolicy.CONTINUE_ON_ERROR, def.effectiveErrorPolicy(schema.getErrorPolicy()));
        Assert.assertEquals("action 级监听器解析到 ActionDef 上", "authGuard",
                schema.getActions().get(0).getListeners().get(0).getName());
        Assert.assertEquals("采样率作为普通变量放顶层", 0.1, ((Number) def.getVars().get("sampleRate")).doubleValue(), 0.0);
    }

    /** 聚合节点示例: 一个校验项一个组, 组内子节点被提升、flow 被重写成 {@code GROUP:名(子项...)}。 */
    @Test
    public void spaceValidationExpandsGroupsAndKeepsContainerAttributes() {
        ProcessDefinition def = loadResource("definitions/space-validation.yml");

        Assert.assertTrue(def.isEager());
        Assert.assertEquals("flow 顶层只有一个 PARALLEL 根", 1, def.roots().size());
        DslParser.Parallel par = (DslParser.Parallel) def.roots().get(0);
        Assert.assertEquals("两个校验项 = 两个聚合节点", 2, par.items.size());
        Assert.assertTrue(par.items.get(0) instanceof DslParser.Group);
        Assert.assertEquals("spaceCheck", ((DslParser.Group) par.items.get(0)).name);
        Assert.assertEquals("组内两个步骤", 2, ((DslParser.Group) par.items.get(0)).items.size());

        // 组自己是纯容器: 只保留容器级属性(name/errorPolicy/when/config/listeners)
        NodeDef space = def.node("spaceCheck");
        Assert.assertEquals("空间占用校验", space.displayName());
        Assert.assertNull(space.getHandler());
        Assert.assertEquals(ErrorPolicy.FAIL_FAST, def.effectiveErrorPolicy(space.getErrorPolicy()));
        Assert.assertEquals("组级 config 供子节点沿父链上溯", "t_validate_space", space.getConfig().get("itemTable"));
        Assert.assertEquals(ErrorPolicy.CONTINUE_ON_ERROR,
                def.effectiveErrorPolicy(def.node("rowCountCheck").getErrorPolicy()));

        // 子节点提升进顶层 nodes, 键 = "<组>.<子>"
        Assert.assertEquals("spaceProbeSource", def.node("spaceCheck.fetchSource").getHandler());
        NodeDef diff = def.node("spaceCheck.diffPerShard");
        Assert.assertTrue(diff.isSharded());
        Assert.assertTrue("laneExecution: PARALLEL", diff.isLaneParallel());
        Assert.assertEquals("组内显式放置后的读法原样保留到运行期解析",
                "${result.fetchSource.bytes}", diff.effectiveActions().get(0).getConfig().get("srcBytes"));
        Assert.assertEquals("action 级监听器挂在子节点的 action 上", "checkItemRecorder",
                diff.effectiveActions().get(0).getListeners().get(0).getName());

        Assert.assertEquals("流程级监听器不受展开影响", "checkItemInitializer", def.getListeners().get(0).getName());
        Assert.assertEquals(1, def.orderedRoots().size());
    }

    /** 非法/外来模板与重复键不炸定义解析(SafeConstructor 只认 YAML 安全类型)。 */
    @Test
    public void unknownPlaceholderIsKeptVerbatim() {
        ProcessDefinition def = loader.load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n    config:\n"
                + "      sql: \"SELECT ${nope.col} FROM t\"\n");
        Assert.assertEquals("SELECT ${nope.col} FROM t", def.node("a").getConfig().get("sql"));
    }

    /**
     * 段名写了但底下留空(YAML 把值解成 null, 而 getOrDefault 对"键存在值为 null"仍返回 null):
     * vars/nodes/节点 config/actions 一律回落空容器——创建期 new LinkedHashMap<>(def.getVars()) 合并
     * baseVars, 留着 null 就是直接 NPE。
     */
    @Test
    public void emptySectionsFallBackToEmptyContainers() {
        ProcessDefinition def = loader.load("vars:\nflow: \"a\"\nnodes:\n  a:\n    handler: h\n    config:\n    actions:\n");

        Assert.assertNotNull("vars 段留空不能让 getVars() 变 null", def.getVars());
        Assert.assertTrue(def.getVars().isEmpty());
        Assert.assertNotNull(def.node("a"));
        Assert.assertTrue("节点 config 段留空回落空 map", def.node("a").getConfig().isEmpty());
        Assert.assertEquals("actions 段留空不影响单 handler 合成", 1, def.node("a").effectiveActions().size());

        Assert.assertTrue("整段缺失同样是空 map", loader.load("flow: \"a\"\n").getVars().isEmpty());

        ProcessDefinition bare = new ProcessDefinition();
        bare.setVars(null);
        Assert.assertNotNull("setter 归一化: 外部传 null 也不留 NPE 种子", bare.getVars());
        bare.setNodes(null);
        Assert.assertNotNull(bare.getNodes());
        Assert.assertNull(bare.node("nope"));
    }

    /** 回退级监听器链与流程级同构解析(补偿路径靠它拿 before/after 切点), 且两条链互不干扰。 */
    @Test
    public void rollbackListenersParseInDeclarationOrder() {
        ProcessDefinition def = loader.load("flow: \"a\"\n"
                + "listeners:\n  - name: procOne\n"
                + "rollbackListeners:\n  - name: connFetch\n    config:\n      source: cmdb\n"
                + "  - name: connRelease\nnodes:\n  a:\n    handler: h\n");

        List<ListenerDef> chain = def.getRollbackListeners();
        Assert.assertEquals(2, chain.size());
        Assert.assertEquals("声明顺序即执行顺序", "connFetch", chain.get(0).getName());
        Assert.assertEquals("cmdb", chain.get(0).getConfig().get("source"));
        Assert.assertEquals("connRelease", chain.get(1).getName());
        Assert.assertEquals("流程级链独立不受影响", 1, def.getListeners().size());

        Assert.assertTrue("未声明 rollbackListeners 时为空链(parseListeners 对 null 也回空列表, 切点自然 no-op)",
                loader.load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n").getRollbackListeners().isEmpty());
    }

    /** 全局节点级/action 级监听器段与 listeners 同构解析(生效链在消费点合并, 见 GlobalListenerChainTest)。 */
    @Test
    public void globalNodeAndActionListenersParseInDeclarationOrder() {
        ProcessDefinition def = loader.load("flow: \"a\"\n"
                + "globalNodeListeners:\n  - name: nodeAudit\n    config:\n      level: node\n"
                + "  - name: nodeGate\n"
                + "globalActionListeners:\n  - name: actAudit\n"
                + "nodes:\n  a:\n    handler: h\n");

        List<ListenerDef> nodeChain = def.getGlobalNodeListeners();
        Assert.assertEquals(2, nodeChain.size());
        Assert.assertEquals("声明顺序即执行顺序", "nodeAudit", nodeChain.get(0).getName());
        Assert.assertEquals("node", nodeChain.get(0).getConfig().get("level"));
        Assert.assertEquals("nodeGate", nodeChain.get(1).getName());
        Assert.assertEquals(1, def.getGlobalActionListeners().size());
        Assert.assertEquals("actAudit", def.getGlobalActionListeners().get(0).getName());

        ProcessDefinition bare = loader.load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        Assert.assertTrue("未声明时为空链(不干扰合并: mergeWithGlobals 对空链退化成原链)",
                bare.getGlobalNodeListeners().isEmpty() && bare.getGlobalActionListeners().isEmpty());

        ProcessDefinition blank = loader.load("globalNodeListeners:\nglobalActionListeners:\nflow: \"a\"\n");
        Assert.assertTrue("段名写了但底下留空同样回空列表", blank.getGlobalNodeListeners().isEmpty());
    }

    // ===================== 自动回退开关(autoRollback) =====================

    /** 声明值两档都解得到: true 才算开; 不写 / 写 false 都关(YAML 的 yes/on 本就解成 Boolean)。 */
    @Test
    public void autoRollbackSwitchParsesBothWays() {
        ProcessDefinition on = loader.load("autoRollback: true\nflow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        Assert.assertEquals("true", on.getAutoRollback());
        Assert.assertTrue("声明 true 才算开了自动回退", on.autoRollbackEnabled());

        ProcessDefinition off = loader.load("autoRollback: false\nflow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        Assert.assertEquals("false", off.getAutoRollback());
        Assert.assertFalse("显式关 = 不开", off.autoRollbackEnabled());

        ProcessDefinition absent = loader.load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        Assert.assertNull("不写 = 没声明(回退照旧只走人工)", absent.getAutoRollback());
        Assert.assertFalse(absent.autoRollbackEnabled());

        Assert.assertTrue("YAML yes 解成 Boolean true",
                loader.load("autoRollback: yes\nflow: \"a\"\nnodes:\n  a:\n    handler: h\n").autoRollbackEnabled());
        Assert.assertTrue("大小写与首尾空白不敏感",
                loader.load("autoRollback: \" TRUE \"\nflow: \"a\"\nnodes:\n  a:\n    handler: h\n").autoRollbackEnabled());
        Assert.assertNull("段名写了但底下留空 = 没声明(与整段缺失同口径)",
                loader.load("autoRollback:\nflow: \"a\"\nnodes:\n  a:\n    handler: h\n").getAutoRollback());
    }

    /** 写了但拼错: 装载期当场拒, 不静默按"没配"跑——"我明明配了自动回退却没回退"这种坑不该拖到事故现场。 */
    @Test
    public void malformedAutoRollbackRejectedAtLoadTime() {
        try {
            loader.load("autoRollback: yesplease\nflow: \"a\"\nnodes:\n  a:\n    handler: h\n");
            Assert.fail("非法 autoRollback 声明该在装载期就拒");
        } catch (ProcessException e) {
            Assert.assertEquals("AU204214", e.getMessageKey());
        }
    }

    // ===================== flowOrder: 按 flow 顺序取全部节点 =====================

    /** 收成 id 列表, 断言顺序时一眼看得清。 */
    private static List<String> flowOrderIds(ProcessDefinition def) {
        List<String> ids = new ArrayList<>();
        for (NodeDef nd : def.flowOrder()) {
            ids.add(nd.getId());
        }
        return ids;
    }

    /**
     * 核心口径: 顺序来自 flow(setup + flow + setdown, 容器前序展开), **不是 nodes 段的书写顺序**——
     * 下面 nodes 故意倒着声明, 于是"用 nodes 的键序当执行序"这种写法当场红。
     * PARALLEL 两支按声明序(实际并发), IF 两支都算候选(运行期只跑选中的那支)。
     */
    @Test
    public void flowOrderFollowsFlowNotDeclarationOrder() {
        ProcessDefinition def = loader.load("setup:\n  - lock\n"
                + "flow: \"b,PARALLEL(c,d),IF(${vars.flag},e,f)\"\n"
                + "setdown:\n  - unlock\n"
                + "nodes:\n"
                + "  unlock:\n    handler: h\n"
                + "  f:\n    handler: h\n"
                + "  e:\n    handler: h\n"
                + "  d:\n    handler: h\n"
                + "  c:\n    handler: h\n"
                + "  b:\n    handler: h\n"
                + "  lock:\n    handler: h\n");

        Assert.assertEquals(Arrays.asList("lock", "b", "c", "d", "e", "f", "unlock"), flowOrderIds(def));
        Assert.assertEquals("nodes 键序确实是书写序(所以不能拿来当流程序)",
                Arrays.asList("unlock", "f", "e", "d", "c", "b", "lock"),
                new ArrayList<>(def.getNodes().keySet()));
    }

    /** 聚合节点: 组自己占一项(承载 name/when/errorPolicy/config), 组内子节点紧随其后。 */
    @Test
    public void flowOrderPutsGroupAheadOfItsPromotedChildren() {
        ProcessDefinition def = loader.load("flow: \"spaceCheck,rowCount\"\n"
                + "nodes:\n"
                + "  rowCount:\n    handler: h\n"
                + "  spaceCheck:\n"
                + "    name: 空间占用校验\n"
                + "    group:\n"
                + "      flow: \"fetchSrc,diff\"\n"
                + "      nodes:\n"
                + "        fetchSrc:\n          handler: h\n"
                + "        diff:\n          handler: h\n");

        Assert.assertEquals(Arrays.asList("spaceCheck", "spaceCheck.fetchSrc", "spaceCheck.diff", "rowCount"),
                flowOrderIds(def));
    }

    /**
     * 匿名容器与解不出定义的引用都不出声: 手写 {@code GROUP:anon(...)} 没对应 NodeDef = 纯串行容器;
     * flow 里引用了 nodes 下不存在的键(ghost)留给驱动期报错, 这里不替引擎判错。
     */
    @Test
    public void flowOrderSkipsAnonymousContainersAndUndeclaredRefs() {
        ProcessDefinition def = loader.load("flow: \"GROUP:anon(x,ghost),PARALLEL(y,GROUP:anon2(z))\"\n"
                + "nodes:\n"
                + "  x:\n    handler: h\n"
                + "  y:\n    handler: h\n"
                + "  z:\n    handler: h\n");

        Assert.assertEquals(Arrays.asList("x", "y", "z"), flowOrderIds(def));
    }

    /**
     * 派生方法绝不能写进定义快照: 借出副本回读时没有对应 setter 会直接失配抛异常(同 isEager/roots 的教训)。
     * 顺带验副本上的顺序与原件一致(flow 字符串自带展开结果, 副本重解析即可)。
     */
    @Test
    public void flowOrderIsNotPartOfTheDefinitionSnapshot() {
        ProcessDefinition def = loader.load("flow: \"a,PARALLEL(b,c)\"\nnodes:\n"
                + "  c:\n    handler: h\n  b:\n    handler: h\n  a:\n    handler: h\n");
        String snapshot = Json.write(def);

        Assert.assertFalse("flowOrder 是派生视图, 不入快照", snapshot.contains("flowOrder"));
        List<String> before = flowOrderIds(def);
        List<String> after = flowOrderIds(Json.read(snapshot, ProcessDefinition.class));
        Assert.assertEquals(Arrays.asList("a", "b", "c"), before);
        Assert.assertEquals("副本重解析 DSL 后顺序一致", before, after);
    }
}
