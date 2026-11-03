package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.context.ProcessContext;
import com.tt.ii.process.core.define.DslParser;
import com.tt.ii.process.core.model.NodeType;
import com.tt.ii.process.core.runtime.ConditionEvaluator;
import com.tt.ii.process.core.runtime.ContextFactory;
import com.tt.ii.process.core.runtime.VariableResolver;
import com.tt.ii.process.core.spi.ShardResolverRegistry;
import com.tt.ii.process.domain.NodeInstEntity;
import com.tt.ii.process.domain.ProcessInstEntity;
import com.tt.ii.process.exception.ProcessException;
import com.tt.ii.process.mapper.ActionInstMapper;
import com.tt.ii.process.mapper.LaneMapper;
import com.tt.ii.process.mapper.NodeInstMapper;
import com.tt.ii.process.util.IdGenerator;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Collections;

/**
 * 节点类型档案注册表({@link NodeKinds})的归口回归: 它取代了旧的 NodeStrategy + ExprMaterializer 两张平行注册表,
 * 所以这里钉的正是"合并"要守住的四件事:
 *  1. 在用的四种类型**各有且只有一份**档案(预留的 WHILE 除外);
 *  2. 两处分发(物化按表达式类 / 驱动按行 type)**指向同一个对象**——同源注册, 不存在"物化认领了、驱动没认领";
 *  3. EAGER 递归能力位按类型正确(容器预建子层, 叶子没有子层, IF 分支恒惰性);
 *  4. 装配期就把重复认领拒掉, 取不到档案时报清楚是哪一头缺(不留静默回落)。
 *
 * 装配口径: 四份档案都是 {@code @InjectMocks}, 构造依赖(node/lane/action mapper、IdGenerator、
 * 分片注册器)一律 {@code @Mock} 字段——档案构造期只存引用(不碰任何 mapper/资源),
 * 所以本用例只判分发与能力位, 既跑不了物化也不需要打桩。
 */
@RunWith(MockitoJUnitRunner.class)
public class NodeKindsTest {

    @Mock
    private NodeInstMapper nodeMapper;
    @Mock
    private LaneMapper laneMapper;
    @Mock
    private ActionInstMapper actionMapper;
    @Mock
    private ShardResolverRegistry shardRegistry;
    @Mock
    private IdGenerator ids;
    /** 档案构造期只存引用(本类不物化), 真身即可, 顺带解开构造子的类型匹配。 */
    @Spy
    private ConfigFreeze freeze = new ConfigFreeze();

    @InjectMocks
    private ActionKind action;
    @InjectMocks
    private IfKind ifKind;
    @InjectMocks
    private ParallelKind parallel;
    /** 同一类型的第二份替身: 只用来验"重复认领"被拒。 */
    @InjectMocks
    private ParallelKind parallelTwin;
    @InjectMocks
    private GroupKind group;

    @Spy
    private VariableResolver resolver = new VariableResolver();
    @InjectMocks
    private ConditionEvaluator evaluator;
    @InjectMocks
    private ContextFactory cf;

    private NodeKinds fourKinds() {
        return new NodeKinds(Arrays.<NodeKind>asList(action, ifKind, parallel, group));
    }

    /** 在用的四种类型各有档案, 且物化分发与驱动分发拿到的是同一份(一次注册, 两处分发)。 */
    @Test
    public void everyUsedTypeIsCoveredByOneKindFromBothDispatchKeys() {
        NodeKinds kinds = fourKinds();
        Assert.assertEquals("在用类型四份( WHILE 预留不算)", 4, kinds.size());

        NodeKind act = kinds.ofType(NodeType.ACTION);
        NodeKind par = kinds.ofType(NodeType.PARALLEL);
        NodeKind iff = kinds.ofType(NodeType.IF);
        NodeKind grp = kinds.ofType(NodeType.GROUP);

        Assert.assertSame(act, kinds.ofExpr(new DslParser.Leaf("a")));
        Assert.assertSame(par, kinds.ofExpr(new DslParser.Parallel()));
        Assert.assertSame(iff, kinds.ofExpr(new DslParser.If()));
        Assert.assertSame(grp, kinds.ofExpr(new DslParser.Group("g")));

        // 档案自己的两个键必须成对(漏配一个就是"建得了行、跑不了"或反之)
        Assert.assertEquals(NodeType.ACTION, act.type());
        Assert.assertEquals(DslParser.Leaf.class, act.exprType());
        Assert.assertEquals(NodeType.GROUP, grp.type());
        Assert.assertEquals(DslParser.Group.class, grp.exprType());
    }

    /**
     * EAGER 能力位: 容器(PARALLEL/GROUP)创建期递归预建子节点行; 叶子没有子节点行; IF 两支都是候选、
     * 要到驱动期决策才知道建哪一支, 所以恒惰性(建两支就会留下永远 CREATED 的孤儿行)。
     */
    @Test
    public void eagerRecursionCapabilityMatchesEachTypeSemantics() {
        NodeKinds kinds = fourKinds();
        Assert.assertTrue(kinds.ofType(NodeType.PARALLEL).materializesChildrenEagerly());
        Assert.assertTrue(kinds.ofType(NodeType.GROUP).materializesChildrenEagerly());
        Assert.assertFalse("IF 分支恒惰性", kinds.ofType(NodeType.IF).materializesChildrenEagerly());
        Assert.assertFalse("叶子没有子节点行", kinds.ofType(NodeType.ACTION).materializesChildrenEagerly());
    }

    /** 一种类型被两份档案认领: 装配期当场拒(后一份静默覆盖前一份是最难查的装配错)。 */
    @Test
    public void duplicateClaimRejectedAtConstruction() {
        try {
            new NodeKinds(Arrays.<NodeKind>asList(parallel, parallelTwin));
            Assert.fail("同一类型重复认领必须拒");
        } catch (ProcessException e) {
            Assert.assertEquals("AU204309", e.getMessageKey());
        }
    }

    /** 预留类型没有档案: 取用时报清楚是驱动侧缺(不回落默认驱动)。 */
    @Test
    public void reservedTypeReportsMissingKind() {
        try {
            fourKinds().ofType(NodeType.WHILE);
            Assert.fail("WHILE 未实现, 必须报清楚");
        } catch (ProcessException e) {
            Assert.assertEquals("AU204306", e.getMessageKey());
        }
    }

    /** 表达式没有档案认领: 报的是物化侧那条键(与本类型未注册成对区分, 一眼看出缺在哪一头)。 */
    @Test
    public void unclaimedExprReportsMissingMaterializer() {
        NodeKinds onlyLeaf = new NodeKinds(Collections.<NodeKind>singletonList(action));
        try {
            onlyLeaf.ofExpr(new DslParser.Group("g"));
            Assert.fail("没有档案认领 GROUP 必须报清楚");
        } catch (ProcessException e) {
            Assert.assertEquals("AU204304", e.getMessageKey());
        }
    }

    /**
     * 容器级 guard 三条同口径(叶子 / 组 / 命名并行)收在一处: 没写就**短路**通过(连求值都不花),
     * 写了就在本节点上下文上求值(沿父链上溯读 vars), 假则整节点免跑。
     * IF 不在其列: 它那一列装的是分支选择条件。
     */
    @Test
    public void guardShortCircuitsWhenUndeclaredAndEvaluatesOnNodeContext() {
        ProcessContext pctx = new ProcessContext("p1");

        // 没声明 guard: evaluator 传 null 也不该被碰到(短路的那一句就是它存在的理由)
        DriveContext noEvaluator = DriveContext.create(new ProcessInstEntity(), null, pctx, resolver, null);
        Assert.assertTrue(NodeGuard.holds(noEvaluator, cf.node(pctx, new NodeInstEntity())));

        NodeInstEntity declared = new NodeInstEntity();
        declared.setConditionExpr("${vars.on} == 1");
        DriveContext dc = DriveContext.create(new ProcessInstEntity(), null, pctx, resolver, evaluator);
        pctx.putVar("on", 1);
        Assert.assertTrue(NodeGuard.holds(dc, cf.node(pctx, declared)));
        pctx.putVar("on", 0);
        Assert.assertFalse("guard 假 = 整个节点免跑", NodeGuard.holds(dc, cf.node(pctx, declared)));
    }
}
