package com.tesla.ities.process.unit;

import com.tesla.ities.process.core.define.DefinitionLoader;
import com.tesla.ities.process.core.define.DslParser;
import com.tesla.ities.process.core.define.ProcessDefinition;
import com.tesla.ities.process.util.Json;
import com.tesla.ities.process.exception.ProcessException;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;

/**
 * 聚合节点(group)的**定义层**语义: DSL 语法、装载期展开、以及"展开结果必须活在可序列化字段里"这条硬约束。
 *
 * 为什么单独立一个类盯这条: DefinitionCache 存的是定义 JSON 快照, ProcessDefinition.roots 是 transient,
 * 借出的副本各自按 flow 字符串重解析 DSL。展开结果若只放内存, 副本就会退回未展开状态,
 * 驱动期报"flow 引用了未定义节点"——所以 {@link #snapshotRoundTripKeepsExpansion} 是本类的核心用例。
 *
 * 装载器无协作者(纯 YAML/DSL 解析), 按 {@code @InjectMocks} 口径由 Mockito 造; 断言全在定义层, 不需要替身。
 */
@RunWith(MockitoJUnitRunner.class)
public class GroupDefinitionTest {

    @InjectMocks
    private DefinitionLoader loader;

    private static final String SPACE_CHECK =
            "flow: \"spaceCheck,summary\"\n"
          + "nodes:\n"
          + "  spaceCheck:\n"
          + "    name: 空间占用校验\n"
          + "    errorPolicy: FAIL_FAST\n"
          + "    when: \"${vars.runSpace} == true\"\n"
          + "    config:\n"
          + "      cluster: c1\n"
          + "    group:\n"
          + "      flow: \"fetchSrc,diffSpace\"\n"
          + "      nodes:\n"
          + "        fetchSrc:\n"
          + "          name: 取源端\n"
          + "          handler: ok\n"
          + "        diffSpace:\n"
          + "          shard: dbTopology\n"
          + "          laneExecution: PARALLEL\n"
          + "          handler: ok\n"
          + "          config:\n"
          + "            srcBytes: \"${result.fetchSrc.bytes}\"\n"
          + "  summary:\n"
          + "    handler: ok\n";

    private ProcessDefinition load(String yaml) {
        return loader.load(yaml);
    }

    // ===================== 展开形状 =====================

    @Test
    public void groupExpandsIntoFlowAndLiftsChildren() {
        ProcessDefinition def = load(SPACE_CHECK);

        Assert.assertEquals("组名重写成 GROUP:名(子项...), 子节点键带组前缀",
                "GROUP:spaceCheck(spaceCheck.fetchSrc,spaceCheck.diffSpace),summary", def.getFlow());

        Assert.assertNotNull("组内子节点被提升进顶层 nodes", def.node("spaceCheck.fetchSrc"));
        Assert.assertEquals("spaceCheck.fetchSrc", def.node("spaceCheck.fetchSrc").getId());
        Assert.assertEquals("取源端", def.node("spaceCheck.fetchSrc").getName());
        Assert.assertEquals("dbTopology", def.node("spaceCheck.diffSpace").getShard());
        Assert.assertEquals("PARALLEL", def.node("spaceCheck.diffSpace").getLaneExecution());
        Assert.assertEquals("子节点自己的 config 原样保留",
                "${result.fetchSrc.bytes}", def.node("spaceCheck.diffSpace").getConfig().get("srcBytes"));

        // 组节点本身是纯容器: 只保留容器级属性
        Assert.assertEquals("空间占用校验", def.node("spaceCheck").getName());
        Assert.assertEquals("FAIL_FAST", def.node("spaceCheck").getErrorPolicy());
        Assert.assertEquals("${vars.runSpace} == true", def.node("spaceCheck").getWhen());
        Assert.assertEquals("c1", def.node("spaceCheck").getConfig().get("cluster"));
        Assert.assertNull(def.node("spaceCheck").getHandler());
        Assert.assertNull(def.node("spaceCheck").getActions());

        // 非组节点不受影响
        Assert.assertEquals("ok", def.node("summary").getHandler());
    }

    @Test
    public void rootsExposeGroupExprWithChildrenInOrder() {
        ProcessDefinition def = load(SPACE_CHECK);
        List<DslParser.Expr> roots = def.roots();
        Assert.assertEquals(2, roots.size());
        Assert.assertTrue("第一个根是聚合节点", roots.get(0) instanceof DslParser.Group);

        DslParser.Group g = (DslParser.Group) roots.get(0);
        Assert.assertEquals("spaceCheck", g.name);
        Assert.assertEquals(2, g.items.size());
        Assert.assertEquals("spaceCheck.fetchSrc", ((DslParser.Leaf) g.items.get(0)).nodeRef);
        Assert.assertEquals("spaceCheck.diffSpace", ((DslParser.Leaf) g.items.get(1)).nodeRef);
        Assert.assertTrue("非组节点仍是叶子", roots.get(1) instanceof DslParser.Leaf);

        // orderedRoots = setup + flow + setdown, 展开结果同样在里面
        Assert.assertTrue(def.orderedRoots().get(0) instanceof DslParser.Group);
    }

    /** 核心约束: 定义 JSON 快照往返后, 展开结果必须还在(否则缓存借出的副本会退回未展开状态)。 */
    @Test
    public void snapshotRoundTripKeepsExpansion() {
        ProcessDefinition def = load(SPACE_CHECK);
        ProcessDefinition copy = Json.read(Json.write(def), ProcessDefinition.class);

        Assert.assertEquals("flow 文本自带展开结果", def.getFlow(), copy.getFlow());
        Assert.assertTrue("副本重解析 flow 仍得到 Group", copy.roots().get(0) instanceof DslParser.Group);
        Assert.assertEquals(2, ((DslParser.Group) copy.roots().get(0)).items.size());
        Assert.assertNotNull("提升后的子节点定义随快照走", copy.node("spaceCheck.fetchSrc"));
        Assert.assertNotNull(copy.node("spaceCheck.diffSpace"));
        Assert.assertEquals("spaceCheck", ((DslParser.Group) copy.roots().get(0)).name);
    }

    @Test
    public void nestedGroupsUseCompoundKeys() {
        ProcessDefinition def = load(
                "flow: \"outer\"\n"
              + "nodes:\n"
              + "  outer:\n"
              + "    group:\n"
              + "      flow: \"inner,tail\"\n"
              + "      nodes:\n"
              + "        inner:\n"
              + "          group:\n"
              + "            flow: \"deep\"\n"
              + "            nodes:\n"
              + "              deep:\n"
              + "                handler: ok\n"
              + "        tail:\n"
              + "          handler: ok\n");

        Assert.assertEquals("GROUP:outer(GROUP:outer.inner(outer.inner.deep),outer.tail)", def.getFlow());
        Assert.assertNotNull(def.node("outer.inner"));
        Assert.assertNotNull(def.node("outer.inner.deep"));
        Assert.assertNotNull(def.node("outer.tail"));

        DslParser.Group outer = (DslParser.Group) def.roots().get(0);
        Assert.assertTrue("嵌套组也是 Group 表达式", outer.items.get(0) instanceof DslParser.Group);
        Assert.assertEquals("outer.inner", ((DslParser.Group) outer.items.get(0)).name);
    }

    @Test
    public void groupChildMayReferenceOuterNode() {
        ProcessDefinition def = load(
                "flow: \"g\"\n"
              + "nodes:\n"
              + "  shared:\n"
              + "    handler: ok\n"
              + "  g:\n"
              + "    group:\n"
              + "      flow: \"shared,own\"\n"
              + "      nodes:\n"
              + "        own:\n"
              + "          handler: ok\n");

        // 组内引用外层节点: 不加前缀(它会成为本组的一个子行, 与在 flow 里直接引用同一个 NodeDef 等价)
        Assert.assertEquals("GROUP:g(shared,g.own)", def.getFlow());
        Assert.assertNotNull(def.node("shared"));
        Assert.assertNotNull(def.node("g.own"));
        Assert.assertNull("不会凭空造出一个 g.shared", def.node("g.shared"));
    }

    @Test
    public void groupInsideParallelAndIfIsSubstituted() {
        ProcessDefinition def = load(
                "flow: \"PARALLEL(g,other),IF(${vars.x}=='y',g,other)\"\n"
              + "nodes:\n"
              + "  g:\n"
              + "    group:\n"
              + "      flow: \"s1\"\n"
              + "      nodes:\n"
              + "        s1:\n"
              + "          handler: ok\n"
              + "  other:\n"
              + "    handler: ok\n");

        Assert.assertEquals("PARALLEL(GROUP:g(g.s1),other),IF(${vars.x}=='y',GROUP:g(g.s1),other)", def.getFlow());
        Assert.assertEquals(2, def.roots().size());
    }

    /** 手写 {@code GROUP:名字(...)} 而 nodes 下无同名声明: 退化成匿名串行容器(合法, 组行无 NodeDef)。 */
    @Test
    public void handWrittenGroupWithoutDeclarationSurvives() {
        ProcessDefinition def = load(
                "flow: \"GROUP:adhoc(a,b)\"\n"
              + "nodes:\n"
              + "  a:\n"
              + "    handler: ok\n"
              + "  b:\n"
              + "    handler: ok\n");

        Assert.assertEquals("没有 group 段就不重写 flow", "GROUP:adhoc(a,b)", def.getFlow());
        Assert.assertNull(def.node("adhoc"));
        DslParser.Group g = (DslParser.Group) def.roots().get(0);
        Assert.assertEquals("adhoc", g.name);
        Assert.assertEquals(2, g.items.size());
    }

    // ===================== DSL 语法与写回 =====================

    @Test
    public void dslParsesGroupAndRoundTripsThroughToFlow() {
        String flow = "GROUP:g(a,PARALLEL(b,c),IF(x==1,d,e))";
        List<DslParser.Expr> exprs = DslParser.parseFlow(flow);
        Assert.assertEquals(1, exprs.size());

        DslParser.Group g = (DslParser.Group) exprs.get(0);
        Assert.assertEquals("g", g.name);
        Assert.assertEquals(3, g.items.size());
        Assert.assertTrue(g.items.get(1) instanceof DslParser.Parallel);
        Assert.assertTrue(g.items.get(2) instanceof DslParser.If);
        Assert.assertEquals("x==1", ((DslParser.If) g.items.get(2)).condition);

        Assert.assertEquals("写回与原文一致(装载期重写 flow 依赖这条)", flow, DslParser.toFlow(exprs));
    }

    @Test
    public void dslToFlowKeepsPlainShapesUnchanged() {
        String flow = "a,PARALLEL(b,c),IF(cond,d,e),IF(cond2,f)";
        Assert.assertEquals(flow, DslParser.toFlow(DslParser.parseFlow(flow)));
    }

    @Test
    public void groupNeedsAtLeastOneChild() {
        try {
            DslParser.parseFlow("GROUP:g()");
            Assert.fail("空聚合节点应当场报错");
        } catch (ProcessException e) {
            Assert.assertEquals("AU204103", e.getMessageKey());
        }
        try {
            DslParser.parseFlow("GROUP:g(,)");
            Assert.fail("括号里以逗号开头 = 子项位置空着");
        } catch (ProcessException e) {
            Assert.assertEquals("AU204105", e.getMessageKey());
        }
    }

    /**
     * 名字必须写在括号**之外**: 旧写法 {@code GROUP(g,a,b)} 与"匿名容器带两个子项"文本一模一样,
     * 静默按新规则解读会多物化一行组名(实例行与表达式错位), 所以缺命名后缀直接拒而不是猜。
     */
    @Test
    public void groupNeedsNameOutsideParens() {
        for (String flow : new String[]{"GROUP(g,a,b)", "GROUP(g a)", "GROUP(g)"}) {
            try {
                DslParser.parseFlow(flow);
                Assert.fail("缺命名后缀应当场报错: " + flow);
            } catch (ProcessException e) {
                Assert.assertEquals(flow, "AU204102", e.getMessageKey());
                Assert.assertTrue(e.getMessage(), e.getMessage().contains("GROUP"));   // 参数已填进文案
            }
        }
    }

    // ===================== 装载期校验(发布时就炸, 不拖到驱动) =====================

    @Test
    public void groupChildReferencingUnknownNodeFails() {
        assertLoadFails("flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    group:\n"
                + "      flow: \"ghost\"\n"
                + "      nodes:\n"
                + "        real:\n"
                + "          handler: ok\n", "AU204210");
    }

    @Test
    public void groupNodeDeclaringHandlerFails() {
        assertLoadFails("flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    handler: ok\n"
                + "    group:\n"
                + "      flow: \"s\"\n"
                + "      nodes:\n"
                + "        s:\n"
                + "          handler: ok\n", "AU204204");
    }

    @Test
    public void groupNodeDeclaringActionsFails() {
        assertLoadFails("flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    actions:\n"
                + "      - {id: a, handler: ok}\n"
                + "    group:\n"
                + "      flow: \"s\"\n"
                + "      nodes:\n"
                + "        s:\n"
                + "          handler: ok\n", "AU204204");
    }

    @Test
    public void groupNodeDeclaringShardFails() {
        assertLoadFails("flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    shard: dbTopology\n"
                + "    group:\n"
                + "      flow: \"s\"\n"
                + "      nodes:\n"
                + "        s:\n"
                + "          handler: ok\n", "AU204204");
    }

    @Test
    public void groupMissingFlowOrNodesFails() {
        assertLoadFails("flow: \"g\"\nnodes:\n  g:\n    group:\n      nodes:\n        s:\n          handler: ok\n",
                "AU204206");
        assertLoadFails("flow: \"g\"\nnodes:\n  g:\n    group:\n      flow: \"s\"\n", "AU204207");
    }

    @Test
    public void liftedChildKeyCollisionFails() {
        assertLoadFails("flow: \"g\"\n"
                + "nodes:\n"
                + "  g.s:\n"
                + "    handler: ok\n"
                + "  g:\n"
                + "    group:\n"
                + "      flow: \"s\"\n"
                + "      nodes:\n"
                + "        s:\n"
                + "          handler: ok\n", "AU204208");
    }

    @Test
    public void groupInSetupOrSetdownFails() {
        assertLoadFails("setup: [g]\nflow: \"x\"\n"
                + "nodes:\n"
                + "  x:\n"
                + "    handler: ok\n"
                + "  g:\n"
                + "    group:\n"
                + "      flow: \"s\"\n"
                + "      nodes:\n"
                + "        s:\n"
                + "          handler: ok\n", "AU204202");
        assertLoadFails("setdown: [g]\nflow: \"x\"\n"
                + "nodes:\n"
                + "  x:\n"
                + "    handler: ok\n"
                + "  g:\n"
                + "    group:\n"
                + "      flow: \"s\"\n"
                + "      nodes:\n"
                + "        s:\n"
                + "          handler: ok\n", "AU204203");
    }

    @Test
    public void groupReferencingAnotherTopLevelGroupFails() {
        assertLoadFails("flow: \"a,b\"\n"
                + "nodes:\n"
                + "  a:\n"
                + "    group:\n"
                + "      flow: \"b\"\n"                 // 组内引用另一个顶层聚合节点(不是本组声明的子节点)
                + "      nodes:\n"
                + "        own:\n"
                + "          handler: ok\n"
                + "  b:\n"
                + "    group:\n"
                + "      flow: \"s\"\n"
                + "      nodes:\n"
                + "        s:\n"
                + "          handler: ok\n", "AU204209");
    }

    @Test
    public void definitionWithoutGroupsIsUntouched() {
        ProcessDefinition def = load("flow: \"a,PARALLEL(b,c)\"\nnodes:\n  a:\n    handler: ok\n"
                + "  b:\n    handler: ok\n  c:\n    handler: ok\n");
        Assert.assertEquals("没有 group 段时 flow 原样(不重写、不引入语法差异)", "a,PARALLEL(b,c)", def.getFlow());
        Assert.assertEquals(3, def.getNodes().size());
    }

    /** 装载期校验统一断言**消息键**: 文案可以改, 码是对外契约。 */
    private void assertLoadFails(String yaml, String expectKey) {
        try {
            load(yaml);
            Assert.fail("应当装载失败: " + expectKey);
        } catch (ProcessException e) {
            Assert.assertEquals(expectKey, e.getMessageKey());
        }
    }
}
