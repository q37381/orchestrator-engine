package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.core.context.ProcessContext;
import com.tesla.ities.process.core.define.DefinitionLoader;
import com.tesla.ities.process.core.define.DslParser;
import com.tesla.ities.process.core.define.ProcessDefinition;
import com.tesla.ities.process.core.model.ErrorPolicy;
import com.tesla.ities.process.core.runtime.VariableResolver;
import com.tesla.ities.process.core.spi.ShardResolverRegistry;
import com.tesla.ities.process.domain.LaneEntity;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.domain.ProcessInstEntity;
import com.tesla.ities.process.mapper.ActionInstMapper;
import com.tesla.ities.process.mapper.LaneMapper;
import com.tesla.ities.process.mapper.NodeInstMapper;
import com.tesla.ities.process.util.IdGenerator;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 全局 errorPolicy(流程级声明, 节点未声明时回落)的生效回归:
 *  - 叶子行: ActionKind 落**生效值**——节点自己声明优先, 未声明才用全局, 都没声明才是 FAIL_FAST;
 *  - 控制行(PARALLEL/IF): 无 NodeDef 可就近声明, 直接取全局(旧实现恒 FAIL_FAST, 全局策略一改就生效);
 *  - lane 行: 继承所属节点行的生效值, 所以全局策略天然覆盖分片;
 *  - 根序列: ExecutionEngine.driveRoots 的收敛**就近声明优先**(见 sequenceConvergencePrefersNearestDeclaration),
 *    这一项自己声明了 errorPolicy 就听它的, 没声明才回落全局(单值覆盖, 不像监听器链那样拼接)。
 *
 * 装配口径: 两份档案 {@code @InjectMocks}, mapper 一律 {@code @Mock} 且只把 insertIgnore 的行收进 {@link #sink}
 * (落库什么不重要, 重要的是那一行的 error_policy 列); IdGenerator 也 {@code @Mock}, 只要求它给出的业务键
 * 在本用例内不重复(lane 行要靠它挂父键)。
 */
@RunWith(MockitoJUnitRunner.class)
public class GlobalErrorPolicyTest {

    /** insertIgnore 落库的行都收这儿, 逐条用例只看自己那一条。 */
    private final List<Object> sink = new ArrayList<>();

    /** 替身号源: 每次取号给一个本用例内唯一的键。 */
    private final AtomicInteger keySeq = new AtomicInteger();

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
    /** 物化要走 PLAN 冻结, 真身即可(零状态, 只吃传入的 resolver)。 */
    @Spy
    private ConfigFreeze freeze = new ConfigFreeze();

    @InjectMocks
    private ActionKind actionKind;
    @InjectMocks
    private ParallelKind parallelKind;

    @Before
    public void captureInsertedRows() {
        when(ids.next(any())).thenAnswer(inv -> "K" + keySeq.incrementAndGet());
        when(nodeMapper.insertIgnore(any())).thenAnswer(inv -> {
            sink.add(inv.getArgument(0));
            return 1;
        });
        when(laneMapper.insertIgnore(any())).thenAnswer(inv -> {
            sink.add(inv.getArgument(0));
            return 1;
        });
    }

    private static DriveContext dc(String yaml) {
        ProcessDefinition def = new DefinitionLoader().load(yaml);
        ProcessInstEntity p = new ProcessInstEntity();
        p.setProcInstId("p1");
        return DriveContext.create(p, def, new ProcessContext("p1"), new VariableResolver(), null);
    }

    /** 物化一个叶子节点, 返回落库行(只看它自己: 物化只建 node 行, lane/action 驱动期建)。 */
    private NodeInstEntity materializeLeaf(DriveContext dc, String nodeRef) {
        sink.clear();
        actionKind.materialize(dc, new DslParser.Leaf(nodeRef), null);
        Assert.assertEquals(1, sink.size());
        return (NodeInstEntity) sink.get(0);
    }

    private NodeInstEntity materializeParallel(DriveContext dc) {
        sink.clear();
        parallelKind.materialize(dc, new DslParser.Parallel(), null);
        Assert.assertEquals(1, sink.size());
        return (NodeInstEntity) sink.get(0);
    }

    /** 叶子没写 errorPolicy -> 吃全局; 写了 -> 覆盖全局。 */
    @Test
    public void leafRowTakesGlobalOnlyWhenItSaysNothing() {
        DriveContext dc = dc("flow: \"inherits,overrides\"\nerrorPolicy: CONTINUE_ON_ERROR\n"
                + "nodes:\n  inherits:\n    handler: h\n"
                + "  overrides:\n    handler: h\n    errorPolicy: FAIL_FAST\n");

        Assert.assertEquals("未声明 -> 全局", ErrorPolicy.CONTINUE_ON_ERROR.getStatus(), materializeLeaf(dc, "inherits").getErrorPolicy());
        Assert.assertEquals("自己声明 -> 覆盖全局", ErrorPolicy.FAIL_FAST.getStatus(), materializeLeaf(dc, "overrides").getErrorPolicy());
    }

    /** 节点声明了但值非法: 不当成"声明了", 回落全局(而不是悄悄变成 FAIL_FAST)。 */
    @Test
    public void illegalNodeValueFallsBackToGlobal() {
        DriveContext dc = dc("flow: \"typo\"\nerrorPolicy: CONTINUE_ON_ERROR\n"
                + "nodes:\n  typo:\n    handler: h\n    errorPolicy: contin_on_errr\n");
        Assert.assertEquals(ErrorPolicy.CONTINUE_ON_ERROR.getStatus(), materializeLeaf(dc, "typo").getErrorPolicy());

        DriveContext noGlobal = dc("flow: \"t\"\nnodes:\n  t:\n    handler: h\n    errorPolicy: nope\n");
        Assert.assertEquals("全局也没声明 + 节点值非法 -> 默认 FAIL_FAST", ErrorPolicy.DEFAULT.getStatus(), materializeLeaf(noGlobal, "t").getErrorPolicy());
    }

    /** 什么都没声明 = 默认 FAIL_FAST(旧行为不变)。 */
    @Test
    public void nothingDeclaredStaysFailFast() {
        DriveContext dc = dc("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        Assert.assertEquals(ErrorPolicy.DEFAULT.getStatus(), materializeLeaf(dc, "a").getErrorPolicy());
        Assert.assertEquals(ErrorPolicy.DEFAULT.getStatus(), materializeParallel(dc).getErrorPolicy());
    }

    /** 控制节点(PARALLEL/IF)无 NodeDef, 旧实现恒 FAIL_FAST; 现在取全局策略。 */
    @Test
    public void controlRowTakesGlobalPolicy() {
        DriveContext dc = dc("flow: \"PARALLEL(a,b)\"\nerrorPolicy: CONTINUE_ON_ERROR\nnodes:\n  a:\n    handler: h\n");
        Assert.assertEquals(ErrorPolicy.CONTINUE_ON_ERROR.getStatus(), materializeParallel(dc).getErrorPolicy());
    }

    /** lane 继承节点行的生效值 -> 全局策略不必逐节点重复声明也覆盖分片。 */
    @Test
    public void laneInheritsEffectivePolicy() {
        DriveContext dc = dc("flow: \"sharded\"\nerrorPolicy: CONTINUE_ON_ERROR\n"
                + "nodes:\n  sharded:\n    handler: h\n    shard: dbTopology\n");
        NodeInstEntity node = materializeLeaf(dc, "sharded");

        sink.clear();
        LaneEntity lane = actionKind.laneAt(dc, node, "db1:3306");
        Assert.assertEquals("只建了 lane 行", 1, sink.size());
        Assert.assertSame(lane, sink.get(0));
        Assert.assertEquals(ErrorPolicy.CONTINUE_ON_ERROR.getStatus(), lane.getErrorPolicy());
    }

    /**
     * 序列收敛口径(stopsSequence): **就近声明优先**, 与本层生效值(effectiveErrorPolicy)是两条独立口径。
     * 项自己声明了就听它的, 没声明/声明不合法/无定义(匿名 PARALLEL/IF)才回落本层。
     */
    @Test
    public void sequenceConvergencePrefersNearestDeclaration() {
        ProcessDefinition loose = new DefinitionLoader().load(""
                + "flow: \"stops,silent,inherit\"\nerrorPolicy: CONTINUE_ON_ERROR\n"
                + "nodes:\n  stops:\n    handler: h\n    errorPolicy: FAIL_FAST\n"
                + "  silent:\n    handler: h\n    errorPolicy: bogus\n"
                + "  inherit:\n    handler: h\n");
        ErrorPolicy layer = loose.globalErrorPolicy();
        Assert.assertTrue("声明了 FAIL_FAST -> 要求上层序列停下",
                loose.stopsSequence(loose.node("stops"), layer));
        Assert.assertFalse("声明值非法 = 没吭声, 回落本层",
                loose.stopsSequence(loose.node("silent"), layer));
        Assert.assertFalse("什么都没声明 = 回落本层",
                loose.stopsSequence(loose.node("inherit"), layer));
        Assert.assertFalse("无定义(匿名 PARALLEL/IF)恒回落本层",
                loose.stopsSequence(null, ErrorPolicy.CONTINUE_ON_ERROR));
        Assert.assertTrue("本层是 FAIL_FAST 时, 没吭声的项同样即停",
                loose.stopsSequence(null, ErrorPolicy.FAIL_FAST));

        // 反方向: 全局 FAIL_FAST(默认)里把某一项标成 CONTINUE_ON_ERROR = 它失败也不拦后面
        ProcessDefinition strict = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n    errorPolicy: CONTINUE_ON_ERROR\n");
        Assert.assertFalse(strict.stopsSequence(strict.node("a"), strict.globalErrorPolicy()));
        // declaredErrorPolicy 只认"这一项自己说了什么": 无定义/没声明/声明不合法一律 null, 不吃全局也不兜默认
        Assert.assertNull(strict.declaredErrorPolicy(strict.node("nonexistent")));
        Assert.assertEquals(ErrorPolicy.CONTINUE_ON_ERROR, strict.declaredErrorPolicy(strict.node("a")));
        Assert.assertNull("非法声明不当成声明", loose.declaredErrorPolicy(loose.node("silent")));
        Assert.assertNull("什么都没写不算声明", loose.declaredErrorPolicy(loose.node("inherit")));
    }

    /** 定义侧口径: 全局声明值大小写不敏感, 非法/未声明回落 FAIL_FAST; 节点声明优先。 */
    @Test
    public void definitionSideResolution() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nerrorPolicy: continue_on_error\nnodes:\n  a:\n    handler: h\n");
        Assert.assertEquals("大小写不敏感", ErrorPolicy.CONTINUE_ON_ERROR, def.globalErrorPolicy());
        Assert.assertEquals(ErrorPolicy.CONTINUE_ON_ERROR, def.effectiveErrorPolicy(null));
        Assert.assertEquals(ErrorPolicy.FAIL_FAST, def.effectiveErrorPolicy("FAIL_FAST"));

        Assert.assertEquals("整段缺失 = 默认 FAIL_FAST", ErrorPolicy.FAIL_FAST, new ProcessDefinition().globalErrorPolicy());
        Assert.assertEquals("非法全局值也回落默认", ErrorPolicy.FAIL_FAST,
                new DefinitionLoader().load("flow: \"a\"\nerrorPolicy: bogus\n").globalErrorPolicy());
        Assert.assertNull("YAML 里写了段名但留空 -> 当没声明",
                new DefinitionLoader().load("errorPolicy:\nflow: \"a\"\n").getErrorPolicy());
    }
}
