package com.tesla.ities.process.unit;

import com.tesla.ities.process.core.define.DefinitionLoader;
import com.tesla.ities.process.core.define.DslParser;
import com.tesla.ities.process.core.define.NodeDef;
import com.tesla.ities.process.core.define.ProcessDefinition;
import com.tesla.ities.process.core.model.ErrorPolicy;
import com.tesla.ities.process.exception.ProcessException;
import com.tesla.ities.process.util.Json;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * 命名并行容器(flow DSL 的命名后缀): {@code PARALLEL:标签(a,b)} —— 后缀指向 nodes 下的一条纯容器声明,
 * 于是并行也能就近声明 errorPolicy / when / config / 节点级监听器, 而不必只能吃流程级全局策略。
 * 命名写在**关键字与括号之间**(旧写法 {@code PARALLEL(标签: a,b)} 占的是子项位置, 已废弃并当场拒)。
 *
 * 本类盯三件事:
 *  1. **解析无歧义**: 命名后缀只在"关键字之后、冒号之后紧跟一个名字"时成立, 嵌套的 {@code PARALLEL(...)}/{@code IF(...)}
 *     不能被判成命名; 不写后缀就是原来的匿名并行, 一字不变;
 *  2. **写回互逆**: 定义快照只存 flow 字符串(roots 是 transient), 聚合节点展开又要重写它,
 *     所以命名后缀必须能过 toFlow ↔ parseFlow 往返回合(见 {@link #nameSurvivesGroupExpansion});
 *  3. **装载期配对**: 拼错的后缀、指向叶子节点的命名、声明了却没人引用的容器, 全部当场拒——
 *     "我明明设了策略却没生效"不该拖到运行期才发现。
 */
public class NamedParallelTest {

    private static ProcessDefinition load(String yaml) {
        return new DefinitionLoader().load(yaml);
    }

    /** 装载必须炸, 且炸在预期的键上。 */
    private static void assertLoadFails(String yaml, String key) {
        try {
            load(yaml);
            Assert.fail("应装载失败: " + yaml);
        } catch (ProcessException e) {
            Assert.assertEquals(key, e.getMessageKey());
        }
    }

    // ===================== 1) 解析 =====================

    @Test
    public void namedParallelCarriesDeclarationKey() {
        List<DslParser.Expr> roots = DslParser.parseFlow("PARALLEL:checks(schemaCheck, rowCountCheck)");
        DslParser.Parallel p = (DslParser.Parallel) roots.get(0);
        Assert.assertEquals("checks", p.name);
        Assert.assertEquals(2, p.items.size());
    }

    @Test
    public void anonymousParallelHasNoKey() {
        List<DslParser.Expr> roots = DslParser.parseFlow("PARALLEL(a, b)");
        Assert.assertNull(((DslParser.Parallel) roots.get(0)).name);
        Assert.assertEquals(2, ((DslParser.Parallel) roots.get(0)).items.size());
    }

    /** 嵌套调用不是命名: 命名只在"关键字与括号之间"那个位置成立, 括号里的首项永远是子表达式。 */
    @Test
    public void nestedCallsAreNotMistakenForNames() {
        DslParser.Parallel nested = (DslParser.Parallel)
                DslParser.parseFlow("PARALLEL(PARALLEL(a,b), c)").get(0);
        Assert.assertNull(nested.name);
        Assert.assertTrue(nested.items.get(0) instanceof DslParser.Parallel);

        DslParser.Parallel withIf = (DslParser.Parallel)
                DslParser.parseFlow("PARALLEL(IF(v.ok, x, y), z)").get(0);
        Assert.assertNull(withIf.name);
        Assert.assertTrue(withIf.items.get(0) instanceof DslParser.If);
    }

    /** 关键字/冒号/名字/括号之间都容空白; 冒号后面光秃秃(直接跟括号)是漏写名字, 单独报错而不是当节点引用。 */
    @Test
    public void nameTolerantOfWhitespace() {
        DslParser.Parallel p = (DslParser.Parallel) DslParser.parseFlow("PARALLEL : checks ( a, b )").get(0);
        Assert.assertEquals("checks", p.name);
        Assert.assertEquals(2, p.items.size());

        try {
            DslParser.parseFlow("PARALLEL:(a, b)");
            Assert.fail("冒号后缺少名字应报错");
        } catch (ProcessException e) {
            Assert.assertEquals("AU204109", e.getMessageKey());
        }
    }

    // ===================== 2) 写回互逆 =====================

    @Test
    public void nameSurvivesFlowRoundTrip() {
        String flow = "loadMeta,PARALLEL:checks(schemaCheck,rowCountCheck),summary";
        String written = DslParser.toFlow(DslParser.parseFlow(flow));
        Assert.assertEquals(flow, written);
        Assert.assertEquals("checks", ((DslParser.Parallel) DslParser.parseFlow(written).get(1)).name);
    }

    /** 定义是缓存中的共享对象、借出的是 JSON 副本: 命名只能活在 flow 字符串里, 否则副本会退回匿名并行。 */
    @Test
    public void nameSurvivesDefinitionSnapshot() {
        ProcessDefinition def = load(""
                + "flow: \"PARALLEL:checks(a, b)\"\n"
                + "nodes:\n"
                + "  checks:\n    errorPolicy: CONTINUE_ON_ERROR\n"
                + "  a:\n    handler: h\n  b:\n    handler: h\n");
        ProcessDefinition copy = Json.read(Json.write(def), ProcessDefinition.class);

        Assert.assertTrue("flow 文本自带命名后缀", copy.getFlow().contains("PARALLEL:checks("));
        DslParser.Parallel p = (DslParser.Parallel) copy.roots().get(0);
        Assert.assertEquals("副本重解析仍拿得到命名", "checks", p.name);
    }

    /** 同上, 但真正要钉的是: 副本里那条声明照旧解得开(命名↔声明成对的契约对副本同样成立)。 */
    @Test
    public void declarationResolvableFromBorrowedCopy() {
        ProcessDefinition def = load(""
                + "flow: \"PARALLEL:checks(a, b)\"\n"
                + "nodes:\n"
                + "  checks:\n    errorPolicy: CONTINUE_ON_ERROR\n"
                + "  a:\n    handler: h\n  b:\n    handler: h\n");
        ProcessDefinition copy = Json.read(Json.write(def), ProcessDefinition.class);
        String label = ((DslParser.Parallel) copy.roots().get(0)).name;
        NodeDef nd = copy.node(label);
        Assert.assertNotNull("借出的副本也认得这条声明", nd);
        Assert.assertEquals("副本上算出的生效值仍是自己声明的那条(物化期烙进 error_policy 的就是它)",
                ErrorPolicy.CONTINUE_ON_ERROR.getStatus(), copy.effectiveErrorPolicy(nd.getErrorPolicy()).getStatus());
    }

    /**
     * 关键回归: 声明了聚合节点时装载期会**重写 flow**(子节点提升 + 组名换成 GROUP 表达式),
     * 重写要重建整棵表达式树 —— 并行分支若不带 name, 命名就在这一步被静默吃掉。
     */
    @Test
    public void nameSurvivesGroupExpansion() {
        ProcessDefinition def = load(""
                + "flow: \"PARALLEL:p(g, tail)\"\n"
                + "nodes:\n"
                + "  p:\n    errorPolicy: CONTINUE_ON_ERROR\n"
                + "  g:\n"
                + "    group:\n"
                + "      flow: \"s1\"\n"
                + "      nodes:\n"
                + "        s1:\n          handler: h\n"
                + "  tail:\n    handler: h\n");

        Assert.assertTrue("重写后的 flow 仍带命名: " + def.getFlow(), def.getFlow().contains("PARALLEL:p("));
        DslParser.Parallel p = (DslParser.Parallel) def.roots().get(0);
        Assert.assertEquals("p", p.name);
        Assert.assertTrue("组名已被换成 Group 表达式", p.items.get(0) instanceof DslParser.Group);
    }

    // ===================== 3) 装载期配对与语法闸 =====================

    /** 命名写了但 nodes 下没那条声明: 当场拒(手写 GROUP 允许退化匿名, 并行命名不允许——拼错就会静默回落全局)。 */
    @Test
    public void unknownNameRejected() {
        assertLoadFails("flow: \"PARALLEL:chekcs(a)\"\nnodes:\n  a:\n    handler: h\n", "AU204212");
    }

    /** 命名指向一个普通叶子节点(带 handler): 命名容器必须是纯容器。 */
    @Test
    public void nameOnLeafNodeRejected() {
        assertLoadFails("flow: \"PARALLEL:a(b)\"\nnodes:\n  a:\n    handler: h\n  b:\n    handler: h\n",
                "AU204204");
    }

    /** 只声明了容器属性却哪儿都没引用 = 死配置, 正是"设了没生效"的形状, 当场拒。 */
    @Test
    public void unreferencedContainerDeclarationRejected() {
        assertLoadFails("flow: \"a\"\nnodes:\n"
                + "  checks:\n    errorPolicy: CONTINUE_ON_ERROR\n"
                + "  a:\n    handler: h\n", "AU204213");
    }

    @Test
    public void emptyNamedParallelRejected() {
        try {
            DslParser.parseFlow("PARALLEL:checks()");
            Assert.fail("光有命名没有子项应报错");
        } catch (ProcessException e) {
            Assert.assertEquals("AU204108", e.getMessageKey());
        }
    }

    /**
     * 冒号是保留字符且**只**允许出现在关键字之后: 括号里再出现它当场拒。
     * 这一条同时兜住旧写法 {@code PARALLEL(checks: a,b)}——不能被当成一个叫 checks 的子项静默跑掉。
     */
    @Test
    public void colonInNodeReferenceRejected() {
        for (String flow : new String[]{"a:b", "PARALLEL(c, d:e)", "PARALLEL(checks: a, b)", "GROUP:g(a:b)"}) {
            try {
                DslParser.parseFlow(flow);
                Assert.fail("应拒: " + flow);
            } catch (ProcessException e) {
                Assert.assertEquals(flow, "AU204107", e.getMessageKey());
            }
        }
    }

    /** 不写命名的定义一律照旧: 匿名并行、纯容器声明不存在、flowOrder 里没有多余项。 */
    @Test
    public void anonymousParallelUnchanged() {
        ProcessDefinition def = load("flow: \"PARALLEL(a,b)\"\nnodes:\n  a:\n    handler: h\n  b:\n    handler: h\n");
        Assert.assertNull(((DslParser.Parallel) def.roots().get(0)).name);
        Assert.assertEquals("匿名并行不产生容器项", 2, def.flowOrder().size());
    }

    /** 命名容器在 flowOrder 里自己算一项(与 GROUP 同口径), 排在它的子项之前。 */
    @Test
    public void namedContainerAppearsInFlowOrder() {
        ProcessDefinition def = load(""
                + "flow: \"PARALLEL:checks(a, b)\"\n"
                + "nodes:\n"
                + "  checks:\n    name: 校验清单\n    errorPolicy: CONTINUE_ON_ERROR\n"
                + "  a:\n    handler: h\n  b:\n    handler: h\n");
        List<NodeDef> order = def.flowOrder();
        Assert.assertEquals(3, order.size());
        Assert.assertEquals("checks", order.get(0).getId());
        Assert.assertEquals("校验清单", order.get(0).displayName());
        Assert.assertEquals("a", order.get(1).getId());
    }
}
