package com.tt.ii.process.core.define;

import com.tt.ii.process.core.model.ErrorPolicy;
import com.tt.ii.process.exception.ProcessException;
import com.tt.ii.process.util.Json;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * YAML -> ProcessDefinition 的解析回归(顺带钉住 SnakeYAML 2.x 迁移):
 *  - 2.0 起 SafeConstructor 无参构造被删, 只留 (LoaderOptions) —— 这里真实跑四份示例定义, 保证不只是编得过;
 *  - 四份示例定义(definitions/*.yml, 含 flow-template.yml 全特性模板)在 main/resources 下, 测试类路径直接可读。
 *
 * 装载器没有协作者(读的是入参字符串/类路径资源), 按 {@code @InjectMocks} 口径由 Mockito 造。
 */
@RunWith(MockitoJUnitRunner.class)
public class DefinitionLoaderTest {

    @InjectMocks
    private DefinitionLoader loader;

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

    /** 全特性模板: 四层"前置/收尾"(流程级段/组级段/节点级段/action 级段)与编排特性均须装载成功(防模板漂移)。 */
    @Test
    public void flowTemplateCoversAllFourSegmentLayers() {
        ProcessDefinition def = loadResource("definitions/flow-template.yml");

        Assert.assertEquals("流程级 setup 段一个节点", 1, def.setupSize());
        Assert.assertEquals("流程级 setdown 段一个节点", 1, def.setdownSize());
        Assert.assertEquals("flow 五个根", 5, def.roots().size());
        Assert.assertEquals("根行序 = setup + flow + setdown", 7, def.orderedRoots().size());

        NodeDef space = def.node("spaceCheck");
        Assert.assertEquals("组级前置段(与 group: 同级, 包住整个子节点序列)", "lockItemTable",
                space.getSetup().get(0).getId());
        Assert.assertEquals("组级收尾段", "unlockItemTable", space.getSetdown().get(0).getId());
        Assert.assertNull("组自身仍无 handler/actions(纯容器)", space.getHandler());
        Assert.assertNull(space.getActions());

        NodeDef migrate = def.node("migrateData");
        Assert.assertEquals("节点级前置段", "pauseReplication", migrate.getSetup().get(0).getId());
        Assert.assertEquals("节点级收尾段", "resumeReplication", migrate.getSetdown().get(0).getId());
        Assert.assertTrue("lane 并行", migrate.isLaneParallel());
        Assert.assertEquals("主段三项", 3, migrate.getActions().size());

        NodeDef counted = def.node("rowCountCheck.countPerShard");   // 组内子节点已提升为 "组.子"
        Assert.assertEquals("action 级前置段(只包一个 action)", "elevateQuota",
                counted.getActions().get(0).getSetup().get(0).getId());
        Assert.assertEquals("action 级收尾段", "restoreQuota",
                counted.getActions().get(0).getSetdown().get(0).getId());
        Assert.assertEquals("同节点第二组级段与第一组互不串",
                "elevateSample", counted.getActions().get(1).getSetup().get(0).getId());

        Assert.assertTrue("节点级 repeatSegments 开关", def.node("summary").repeatSegmentsEnabled());
        Assert.assertTrue("主段并发档", def.node("buildIndex").isActionParallel());
        Assert.assertNull("命名并行容器是纯容器", def.node("checks").getActions());
        Assert.assertEquals("聚合子节点提升后共 15 条节点定义", 15, def.getNodes().size());
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

    /**
     * flow 的多行块写法: 换行/缩进/空白与单行等价, 容器子项列表的尾随逗号照常收口(FR-DSL-08)。
     * 定义快照存的仍是原文本, roots 由借出的副本各自惰性重解析。
     */
    @Test
    public void multilineFlowParsesLikeSingleLine() {
        ProcessDefinition def = loader.load("flow: |\n"
                + "  a,\n"
                + "  PARALLEL(\n"
                + "    b,\n"
                + "    c,\n"
                + "  ),\n"
                + "  IF(${vars.ok} == 'true',\n"
                + "     d,\n"
                + "     e)\n"
                + "nodes:\n"
                + "  a:\n    handler: h\n"
                + "  b:\n    handler: h\n"
                + "  c:\n    handler: h\n"
                + "  d:\n    handler: h\n"
                + "  e:\n    handler: h\n");

        Assert.assertEquals("顶层三个表达式(与单行写法同形)", 3, def.roots().size());
        Assert.assertTrue(def.roots().get(1) instanceof DslParser.Parallel);
        Assert.assertEquals(2, ((DslParser.Parallel) def.roots().get(1)).items.size());
        Assert.assertEquals("多行不影响 IF 条件截断", "${vars.ok} == 'true'",
                ((DslParser.If) def.roots().get(2)).condition);
        Assert.assertEquals("行序与单行一致", 5, def.flowOrder().size());
        Assert.assertSame(def.node("b"), def.flowOrder().get(1));
    }

    /**
     * flow 写成 YAML 列表: 每项是一个顶层表达式, 装载期按顶层逗号拼回 DSL 字符串。
     * 不兼容列表写法时, str(List) 会得到 "[a, b]" 这种伪 DSL, 要到驱动期才报未定义节点。
     */
    @Test
    public void flowListIsJoinedAsTopLevelCommaSequence() {
        ProcessDefinition def = loader.load("flow:\n"
                + "  - a\n"
                + "  - PARALLEL(b, c)\n"
                + "nodes:\n"
                + "  a:\n    handler: h\n"
                + "  b:\n    handler: h\n"
                + "  c:\n    handler: h\n");

        Assert.assertEquals("列表项原样拼接, 空白交给解析器跳过", "a,PARALLEL(b, c)", def.getFlow());
        Assert.assertEquals(2, def.roots().size());
        Assert.assertEquals(3, def.flowOrder().size());

        ProcessDefinition copy = Json.read(Json.write(def), ProcessDefinition.class);
        Assert.assertEquals("列表写法落快照后仍是同一条 flow", "a,PARALLEL(b, c)", copy.getFlow());
        Assert.assertEquals("副本重解析得到同一拓扑", 2, copy.roots().size());
    }

    /** 组内 flow 同样接受多行块/列表写法: 展开后的提升键与单行写法一致。 */
    @Test
    public void groupInnerFlowAcceptsMultilineAndList() {
        String nodes = "      nodes:\n"
                + "        fetch:\n          handler: h\n"
                + "        count:\n          handler: h\n";
        ProcessDefinition multi = loader.load("flow: \"g\"\nnodes:\n"
                + "  g:\n"
                + "    group:\n"
                + "      flow: |\n"
                + "        fetch,\n"
                + "        count,\n"
                + nodes);
        Assert.assertEquals("GROUP:g(g.fetch,g.count)", multi.getFlow());

        ProcessDefinition list = loader.load("flow: \"g\"\nnodes:\n"
                + "  g:\n"
                + "    group:\n"
                + "      flow:\n"
                + "        - fetch\n"
                + "        - count\n"
                + nodes);
        Assert.assertEquals("GROUP:g(g.fetch,g.count)", list.getFlow());
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

    // ===================== 停止先收尾开关(settleOnStop) =====================

    /** 声明值两档都解得到: true 才算开; 不写/写 false 都 = 硬停; 非法值装载期拒(AU204234); 开关随快照往返。 */
    @Test
    public void settleOnStopSwitchParsesAndRejectsMalformed() {
        ProcessDefinition on = loader.load("settleOnStop: true\nflow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        Assert.assertEquals("归一化小写文本", "true", on.getSettleOnStop());
        Assert.assertTrue("声明 true 才是两阶段停止", on.settleOnStopEnabled());

        ProcessDefinition off = loader.load("settleOnStop: false\nflow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        Assert.assertFalse("显式关 = 硬停", off.settleOnStopEnabled());

        ProcessDefinition absent = loader.load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        Assert.assertNull("不写 = 没声明", absent.getSettleOnStop());
        Assert.assertFalse("未声明 = 硬停(行为逐字不变)", absent.settleOnStopEnabled());

        try {
            loader.load("settleOnStop: yesplease\nflow: \"a\"\nnodes:\n  a:\n    handler: h\n");
            Assert.fail("非法 settleOnStop 声明该在装载期就拒");
        } catch (ProcessException e) {
            Assert.assertEquals("AU204234", e.getMessageKey());
        }

        String snapshot = Json.write(on);
        Assert.assertFalse("派生视图不入快照", snapshot.contains("settleOnStopEnabled"));
        Assert.assertTrue("开关随定义快照往返",
                Json.read(snapshot, ProcessDefinition.class).settleOnStopEnabled());
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

    // ===================== 节点级 setup/setdown 段 =====================

    /** 段与 actions 同构解析(内联动作列表挂 NodeDef); 未声明/留空同口径回落 null。 */
    @Test
    public void nodeSegmentsParseInlineActionLists() {
        ProcessDefinition def = loader.load("flow: \"a\"\n"
                + "nodes:\n  a:\n    actions:\n      - id: m1\n        handler: h\n"
                + "    setup:\n      - id: p1\n        handler: h\n        config:\n          mode: pause\n"
                + "    setdown:\n      - id: r1\n        handler: h\n");
        NodeDef a = def.node("a");
        Assert.assertEquals(1, a.getSetup().size());
        Assert.assertEquals("p1", a.getSetup().get(0).getId());
        Assert.assertEquals("段项 config 同构解析", "pause", a.getSetup().get(0).getConfig().get("mode"));
        Assert.assertEquals(1, a.getSetdown().size());
        Assert.assertEquals("r1", a.getSetdown().get(0).getId());
        Assert.assertEquals("主段不受影响", 1, a.effectiveActions().size());

        NodeDef bare = loader.load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n").node("a");
        Assert.assertNull("未声明段 = null", bare.getSetup());
        Assert.assertNull(bare.getSetdown());
        NodeDef blank = loader.load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n    setup:\n    setdown:\n").node("a");
        Assert.assertNull("段名写了但留空 = null(与整段缺失同口径)", blank.getSetup());
        Assert.assertNull(blank.getSetdown());
    }

    /** 段装载校验: id 合并唯一 / 段缺主段 / 命名并行声明段, 全部当场拒(GROUP 声明段已是正当能力, 见 v1.3 用例)。 */
    @Test
    public void nodeSegmentValidationsRejectAtLoadTime() {
        assertLoadFails("flow: \"a\"\nnodes:\n  a:\n"
                + "    actions:\n      - id: dup\n        handler: h\n"
                + "    setup:\n      - id: dup\n        handler: h\n", "AU204215");
        assertLoadFails("flow: \"a\"\nnodes:\n  a:\n    handler: h\n"
                + "    setdown:\n      - id: a\n        handler: h\n", "AU204215");   // 与单 handler 合成的主段 id(节点 id)撞
        assertLoadFails("flow: \"a\"\nnodes:\n  a:\n"
                + "    setup:\n      - id: p1\n        handler: h\n", "AU204216");   // 段缺主段
        assertLoadFails("flow: \"PARALLEL:g(a)\"\nnodes:\n  g:\n    setup:\n      - handler: h\n"
                + "  a:\n    handler: h\n", "AU204204");   // 命名并行是纯容器, 段同样拒
    }

    /** 段与 repeatSegments 字段随定义 JSON 快照往返(定义缓存借出的副本仍带声明)。 */
    @Test
    public void nodeSegmentsSurviveSnapshotRoundTrip() {
        ProcessDefinition def = loader.load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n"
                + "    repeatSegments: true\n"
                + "    setup:\n      - id: p1\n        handler: h\n"
                + "    setdown:\n      - id: r1\n        handler: h\n");
        String snapshot = Json.write(def);
        Assert.assertFalse("派生视图不入快照(repeatSegmentsEnabled 无 setter)", snapshot.contains("repeatSegmentsEnabled"));
        ProcessDefinition copy = Json.read(snapshot, ProcessDefinition.class);
        Assert.assertEquals("p1", copy.node("a").getSetup().get(0).getId());
        Assert.assertEquals("r1", copy.node("a").getSetdown().get(0).getId());
        Assert.assertTrue("开关随快照往返", copy.node("a").repeatSegmentsEnabled());
    }

    // ===================== v1.1: repeatSegments 开关 =====================

    /** 开关解析与三层校验: 节点级/流程级合法解析; 死配置(AU204217/218)、非法值(AU204219)、容器声明(AU204204)当场拒。 */
    @Test
    public void repeatSegmentsParsesAndRejectsDeadConfig() {
        ProcessDefinition def = loader.load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n"
                + "    repeatSegments: true\n"
                + "    setup:\n      - id: p1\n        handler: h\n");
        Assert.assertTrue("节点级开关解析", def.node("a").repeatSegmentsEnabled());
        Assert.assertEquals("归一化小写文本", "true", def.node("a").getRepeatSegments());

        ProcessDefinition top = loader.load("repeatSegments: true\nsetup: [s]\nflow: \"a\"\nnodes:\n"
                + "  s:\n    handler: h\n  a:\n    handler: h\n");
        Assert.assertTrue("流程级开关解析", top.repeatSegmentsEnabled());

        assertLoadFails("flow: \"a\"\nnodes:\n  a:\n    handler: h\n    repeatSegments: true\n", "AU204217");
        assertLoadFails("repeatSegments: true\nflow: \"a\"\nnodes:\n  a:\n    handler: h\n", "AU204218");
        assertLoadFails("flow: \"a\"\nnodes:\n  a:\n    handler: h\n    repeatSegments: abc\n", "AU204219");
        assertLoadFails("flow: \"g\"\nnodes:\n  g:\n    repeatSegments: true\n"
                + "    group:\n      flow: \"s\"\n      nodes:\n        s:\n          handler: h\n", "AU204217");   // GROUP 死开关同口径
    }

    // ===================== v1.3: segmentsFollowShard 开关 =====================

    /** 分片叶子声明段不跟随分片: 解析/归一化/快照往返; 死配置(无段/非分片)、非法值、容器声明当场拒。 */
    @Test
    public void segmentsFollowShardParsesAndRejectsDeadConfig() {
        ProcessDefinition def = loader.load("flow: \"a\"\nnodes:\n  a:\n"
                + "    shard: dbTopology\n"
                + "    segmentsFollowShard: false\n"
                + "    setup:\n      - id: p1\n        handler: h\n"
                + "    actions:\n      - id: m1\n        handler: h\n"
                + "    setdown:\n      - id: r1\n        handler: h\n");
        Assert.assertFalse("false = 段整节点各一次", def.node("a").segmentsFollowShardEnabled());
        Assert.assertEquals("归一化小写文本", "false", def.node("a").getSegmentsFollowShard());

        ProcessDefinition dft = loader.load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        Assert.assertTrue("未声明 = 跟随分片(默认)", dft.node("a").segmentsFollowShardEnabled());
        ProcessDefinition on = loader.load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n"
                + "    segmentsFollowShard: true\n");
        Assert.assertTrue("显式 true 等同默认(不报死配置, 与 repeatSegments: false 同口径)",
                on.node("a").segmentsFollowShardEnabled());

        String snapshot = Json.write(def);
        Assert.assertFalse("派生视图不入快照(segmentsFollowShardEnabled 无 setter)",
                snapshot.contains("segmentsFollowShardEnabled"));
        ProcessDefinition copy = Json.read(snapshot, ProcessDefinition.class);
        Assert.assertFalse("开关随快照往返", copy.node("a").segmentsFollowShardEnabled());

        assertLoadFails("flow: \"a\"\nnodes:\n  a:\n    shard: dbTopology\n"
                + "    segmentsFollowShard: maybe\n    handler: h\n", "AU204227");   // 非法值
        assertLoadFails("flow: \"a\"\nnodes:\n  a:\n    shard: dbTopology\n"
                + "    segmentsFollowShard: false\n    handler: h\n", "AU204228");   // 无段可作用
        assertLoadFails("flow: \"a\"\nnodes:\n  a:\n    segmentsFollowShard: false\n"
                + "    setup:\n      - id: p1\n        handler: h\n"
                + "    actions:\n      - id: m1\n        handler: h\n", "AU204229");   // 非分片节点
        assertLoadFails("flow: \"g\"\nnodes:\n  g:\n    segmentsFollowShard: false\n"
                + "    group:\n      flow: \"s\"\n      nodes:\n        s:\n          handler: h\n", "AU204204");   // 聚合容器拒
        assertLoadFails("flow: \"PARALLEL:g(a)\"\nnodes:\n  g:\n    segmentsFollowShard: false\n"
                + "  a:\n    handler: h\n", "AU204204");   // 命名并行是纯容器
    }

    // ===================== v1.3: GROUP 级段 =====================

    /** GROUP 与 group: 同级声明段/开关(与子节点序列无关); 段挂组自己的 NodeDef, 随快照往返, 子节点不受影响。 */
    @Test
    public void groupSegmentsParseAndSurviveSnapshot() {
        ProcessDefinition def = loader.load("flow: \"g\"\nnodes:\n  g:\n"
                + "    repeatSegments: false\n"
                + "    setup:\n      - id: prepare\n        handler: h\n"
                + "    group:\n      flow: \"s\"\n      nodes:\n        s:\n          handler: h\n"
                + "    setdown:\n      - id: restore\n        handler: h\n");
        NodeDef g = def.node("g");
        Assert.assertEquals("prepare", g.getSetup().get(0).getId());
        Assert.assertEquals("restore", g.getSetdown().get(0).getId());
        Assert.assertEquals("false", g.getRepeatSegments());
        Assert.assertFalse("段开关归一化解析", g.repeatSegmentsEnabled());
        Assert.assertEquals("段不进子节点序列, 展开形状不变",
                "GROUP:g(g.s)", def.getFlow());

        ProcessDefinition copy = Json.read(Json.write(def), ProcessDefinition.class);
        Assert.assertEquals("段随快照往返", "prepare", copy.node("g").getSetup().get(0).getId());
        Assert.assertEquals("restore", copy.node("g").getSetdown().get(0).getId());
        Assert.assertEquals("提升后的子节点不受影响", "h", copy.node("g.s").getHandler());
    }

    /**
     * 组级段/开关写进 group: 段内(与 flow 级 setup/flow/setdown 三段同形)与写在节点上等价:
     * 节点引用重绑定/注入不变, 内联动作照旧解析, repeatSegments 同位置解析; 同一字段两处都写当场拒(AU204235)。
     */
    @Test
    public void groupSegmentsParseInsideGroupSection() {
        ProcessDefinition def = loader.load("flow: \"g\"\nnodes:\n  g:\n"
                + "    group:\n"
                + "      repeatSegments: true\n"
                + "      setup:\n        - prepare\n"
                + "      flow: \"c1\"\n"
                + "      setdown:\n        - restore\n"
                + "      nodes:\n"
                + "        prepare:\n          handler: p\n"
                + "        c1:\n          handler: h\n"
                + "        restore:\n          handler: r\n");
        NodeDef g = def.node("g");
        Assert.assertEquals("段引用与流程级 setup 同形(写在本组 nodes 的子节点名)",
                Arrays.asList("g.prepare"), g.getSetupRefs());
        Assert.assertEquals(Arrays.asList("g.restore"), g.getSetdownRefs());
        Assert.assertTrue("段开关同位置解析", g.repeatSegmentsEnabled());
        Assert.assertEquals("GROUP:g(g.prepare,g.c1,g.restore)", def.getFlow());

        ProcessDefinition copy = Json.read(Json.write(def), ProcessDefinition.class);
        Assert.assertEquals("段声明随快照往返", Arrays.asList("g.prepare"), copy.node("g").getSetupRefs());

        ProcessDefinition inline = loader.load("flow: \"g\"\nnodes:\n  g:\n"
                + "    group:\n"
                + "      setup:\n        - id: p1\n          handler: h\n"
                + "      flow: \"c1\"\n"
                + "      setdown:\n        - id: r1\n          handler: h\n"
                + "      nodes:\n        c1:\n          handler: h\n");
        Assert.assertEquals("内联动作段同样可写进 group:", "p1", inline.node("g").getSetup().get(0).getId());
        Assert.assertEquals("r1", inline.node("g").getSetdown().get(0).getId());
    }

    /** GROUP 段装载校验: 跨段 id 唯一 / 同段有效动作键唯一 / 同一字段两处声明 / 手写 GROUP 与叶子混用 都当场拒。 */
    @Test
    public void groupSegmentValidationsRejectAtLoadTime() {
        String head = "flow: \"g\"\nnodes:\n  g:\n";
        String tail = "    group:\n      flow: \"s\"\n      nodes:\n        s:\n          handler: h\n";
        assertLoadFails(head + "    setup:\n      - id: dup\n        handler: h\n"
                + "    setdown:\n      - id: dup\n        handler: h\n" + tail, "AU204215");   // 跨段 id 撞
        assertLoadFails(head + "    setup:\n      - handler: h\n      - handler: h\n" + tail, "AU204225");   // 同段同 handler 无 id
        assertLoadFails(head + "    setdown:\n      - id: h\n        handler: x\n      - handler: h\n" + tail, "AU204225");   // id 与另一条的 handler 键撞
        assertLoadFails(head + "    setup:\n      - id: p1\n        handler: h\n"
                + "    group:\n      flow: \"s\"\n      setup:\n        - id: p2\n          handler: h\n"
                + "      nodes:\n        s:\n          handler: h\n", "AU204235");   // 同一字段两处都写
        assertLoadFails("flow: \"GROUP:g(a),g\"\nnodes:\n  g:\n    setup:\n      - id: p1\n        handler: h\n"
                + "  a:\n    handler: h\n", "AU204226");   // 同一份声明既当组又当叶子
    }

    /** 组段与子节点自己的节点段/action 级段互不干扰: 三条校验各按各的成员表跑, 合法声明照常装载。 */
    @Test
    public void groupSegmentsCoexistWithChildSegments() {
        ProcessDefinition def = loader.load("flow: \"g\"\nnodes:\n  g:\n"
                + "    setup:\n      - id: prepare\n        handler: h\n"
                + "    setdown:\n      - id: restore\n        handler: h\n"
                + "    group:\n      flow: \"s\"\n      nodes:\n        s:\n"
                + "          setup:\n            - id: sp\n              handler: h\n"
                + "          setdown:\n            - id: sd\n              handler: h\n"
                + "          actions:\n            - id: m1\n              handler: h\n"
                + "              setup:\n                - id: w1\n                  handler: h\n"
                + "              setdown:\n                - id: w2\n                  handler: h\n");
        Assert.assertEquals("prepare", def.node("g").getSetup().get(0).getId());
        Assert.assertEquals("子节点节点级段不受组段影响", "sp", def.node("g.s").getSetup().get(0).getId());
        Assert.assertEquals("子节点 action 级段同理", "w1",
                def.node("g.s").getActions().get(0).getSetup().get(0).getId());
    }

    // ===================== v1.4: 组级段 = 叶子子节点引用 =====================

    /** 组级段声明为节点引用: 提升键重绑定 + 段节点注入 Group 子项头尾; 快照往返后驱动切片可复现。 */
    @Test
    public void groupNodeRefSegmentsRebindInjectAndRoundTrip() {
        ProcessDefinition def = loader.load("flow: \"g,post\"\nnodes:\n"
                + "  g:\n"
                + "    setup:\n"                       // 块列表写法与流程级 setup/setdown 一致
                + "      - stopSql\n"
                + "    setdown:\n"
                + "      - startSql\n"
                + "    group:\n      flow: \"c1\"\n      nodes:\n"
                + "        c1:\n          handler: h\n"
                + "        stopSql:\n          handler: stop\n"
                + "        startSql:\n          handler: start\n"
                + "  post:\n    handler: h\n");
        NodeDef g = def.node("g");
        Assert.assertEquals("引用重绑定为提升键", Arrays.asList("g.stopSql"), g.getSetupRefs());
        Assert.assertEquals(Arrays.asList("g.startSql"), g.getSetdownRefs());
        Assert.assertNull("节点引用不落内联动作段", g.getSetup());
        Assert.assertEquals("段节点注入 Group 子项头尾(驱动切片与物化对齐的同一份序)",
                "GROUP:g(g.stopSql,g.c1,g.startSql),post", def.getFlow());

        ProcessDefinition copy = Json.read(Json.write(def), ProcessDefinition.class);
        Assert.assertEquals("引用随快照往返", Arrays.asList("g.stopSql"), copy.node("g").getSetupRefs());
        Assert.assertEquals("注入后的子项序随 flow 快照往返", def.getFlow(), copy.getFlow());
        Assert.assertEquals("段节点的声明随子节点提升保留", "stop", copy.node("g.stopSql").getHandler());
    }

    /** 组级段引用的装载校验: 形状(同段混用/跨段不一致)、叶子用途、引用域(组外/嵌套组/重复)、与主体重复 都当场拒。 */
    @Test
    public void groupNodeRefSegmentsRejectIllegalShapes() {
        String tail = "    group:\n      flow: \"c1\"\n      nodes:\n        c1:\n          handler: h\n";
        // 同一条声明里 map 与字符串混用
        assertLoadFails("flow: \"g\"\nnodes:\n  g:\n    setup:\n      - stopSql\n      - handler: h\n" + tail, "AU204230");
        // setup 引用 / setdown 内联: 两段形状不一致
        assertLoadFails("flow: \"g\"\nnodes:\n  g:\n    setup: [stopSql]\n    setdown:\n      - id: r1\n        handler: h\n"
                + tail + "        stopSql:\n          handler: h\n", "AU204230");
        // 叶子用途的段声明写成了节点引用
        assertLoadFails("flow: \"a\"\nnodes:\n  a:\n    setup: [x]\n    actions:\n      - id: m1\n        handler: h\n",
                "AU204231");
        // 引用不在本组声明里(组外节点)
        assertLoadFails("flow: \"g,out\"\nnodes:\n  g:\n    setup: [out]\n" + tail + "  out:\n    handler: h\n",
                "AU204232");
        // 引用嵌套聚合节点(它自带回路, 不能当段)
        assertLoadFails("flow: \"g\"\nnodes:\n  g:\n    setup: [inner]\n    group:\n      flow: \"inner\"\n      nodes:\n"
                + "        inner:\n          group:\n            flow: \"d\"\n            nodes:\n              d:\n                handler: h\n",
                "AU204232");
        // 段引用与主体 flow 引用同一个子节点
        assertLoadFails("flow: \"g\"\nnodes:\n  g:\n    setup: [c1]\n" + tail, "AU204233");
        // 同一段内引用重复(段子节点按 index 对齐, 重复会建两行相同定义的子行)
        assertLoadFails("flow: \"g\"\nnodes:\n  g:\n    setup: [stopSql, stopSql]\n" + tail
                + "        stopSql:\n          handler: h\n", "AU204225");
    }

    // ===================== v1.2: action 级段 =====================

    /** action 级段解析(同动作双挂)与快照往返; 单边/段动作带标记/ id 冲突/并行互斥 装载期拒。 */
    @Test
    public void actionLevelSegmentsParseAndValidate() {
        ProcessDefinition def = loader.load("flow: \"a\"\nnodes:\n  a:\n    actions:\n"
                + "      - id: m1\n        handler: h\n"
                + "        setup:\n          - id: w1\n            handler: h\n"
                + "        setdown:\n          - id: w2\n            handler: h\n");
        Assert.assertEquals("w1", def.node("a").getActions().get(0).getSetup().get(0).getId());
        Assert.assertEquals("w2", def.node("a").getActions().get(0).getSetdown().get(0).getId());
        ProcessDefinition copy = Json.read(Json.write(def), ProcessDefinition.class);
        Assert.assertEquals("段声明随快照往返", "w1",
                copy.node("a").getActions().get(0).getSetup().get(0).getId());

        assertLoadFails("flow: \"a\"\nnodes:\n  a:\n    actions:\n"
                + "      - id: m1\n        handler: h\n"
                + "        setup:\n          - id: w1\n            handler: h\n", "AU204220");
        assertLoadFails("flow: \"a\"\nnodes:\n  a:\n    actions:\n"
                + "      - id: m1\n        handler: h\n"
                + "        setdown:\n          - id: w2\n            handler: h\n", "AU204221");
        assertLoadFails("flow: \"a\"\nnodes:\n  a:\n    actions:\n"
                + "      - id: m1\n        handler: h\n"
                + "        setup:\n          - id: w1\n            handler: h\n"
                + "            setdown:\n              - id: w2\n                handler: h\n", "AU204222");
        assertLoadFails("flow: \"a\"\nnodes:\n  a:\n    actions:\n"
                + "      - id: m1\n        handler: h\n"
                + "        setup:\n          - id: dup\n            handler: h\n"
                + "        setdown:\n          - id: s1\n            handler: h\n"
                + "      - id: m2\n        handler: h\n"
                + "        setup:\n          - id: dup\n            handler: h\n"
                + "        setdown:\n          - id: s2\n            handler: h\n", "AU204223");
        assertLoadFails("flow: \"a\"\nnodes:\n  a:\n    actionExecution: PARALLEL\n    actions:\n"
                + "      - id: m1\n        handler: h\n"
                + "        setup:\n          - id: w1\n            handler: h\n"
                + "        setdown:\n          - id: w2\n            handler: h\n", "AU204224");
    }

    private void assertLoadFails(String yaml, String code) {
        try {
            loader.load(yaml);
            Assert.fail("应装载期拒绝: " + code);
        } catch (ProcessException e) {
            Assert.assertEquals(code, e.getMessageKey());
        }
    }
}
