package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.context.Context;
import com.tt.ii.process.core.context.LaneContext;
import com.tt.ii.process.core.context.NodeContext;
import com.tt.ii.process.core.context.ProcessContext;
import com.tt.ii.process.core.define.ActionDef;
import com.tt.ii.process.core.define.DefinitionLoader;
import com.tt.ii.process.core.define.DslParser;
import com.tt.ii.process.core.define.NodeDef;
import com.tt.ii.process.core.define.ProcessDefinition;
import com.tt.ii.process.core.model.ActionSegment;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.core.runtime.ContextFactory;
import com.tt.ii.process.core.runtime.VariableResolver;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.domain.LaneEntity;
import com.tt.ii.process.domain.NodeInstEntity;
import com.tt.ii.process.domain.ProcessInstEntity;
import com.tt.ii.process.mapper.LaneMapper;
import com.tt.ii.process.mapper.NodeInstMapper;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 走查引擎({@link LayerWalker})的层级口径回归——前身是 SerialSequenceTest 对"基类 + 五个层类"的回归,
 * 场景逐条平移(断言逐字保留), 只是 new 侧对象从具体序列类换成走查引擎的四个入口:
 *  1. 根序列**就近声明优先**, 且收敛只看本项终态(聚合值带着更早那次的坏, 用它判会误杀没吭声的后续项);
 *     setdown 段 finally 语义恒不收敛; 顶层一次都不读取消闸门;
 *  2. 子层(Group/Lane/Action)每步读库判取消, 命中即停并把聚合翻成 STOPPED;
 *  3. drain 只发生在**持有已物化行**的 action 层: 剩余 CREATED/PENDING 行落 STOPPED/DRAIN 并保留原有 result,
 *     未物化的项绝不补建; "执行到才建"的层(根/组/lane)提前结束时手里没有剩余行;
 *  4. 重入语义: 已落终态的 action 算 SUCCESS 计入聚合而不重跑; **非终态行(上轮让渡/中断残留)重跑**(at-least-once);
 *     severity 聚合不被兄弟成功冲掉;
 *  5. 并行档经唯一一份扇出模板: 逐项先定位再提交, FAIL_FAST 时只取消尚未开始的排队任务(不中断已跑的);
 *  6. 让渡(handler 返回 pending/paused/suspended): 立即停且**不 drain**、后续段(含 setdown)不跑、
 *     容器行不落终态; 坏终态优先于让渡; 并行档由 Fanout join 保留让渡态(不 cancelPending)。
 *
 * 装配口径: 走查引擎按**构造期依赖**(三档池 + 行定位 {@link ActionRows} + 上下文工厂 + 容器行收尾 mapper)
 * 建真身, 协作方(递归 {@link NodeRecursion}、对齐 {@link ChildCursor}、执行 {@link ActionExecution}、
 * 判停 {@link CancellationGate})各 mock 一份经 {@link NodeEnv} 现组传入; 定义走 DefinitionLoader 真解析
 * (就近声明的判据本体在 ProcessDefinition.stopsSequence, 不打桩); 执行档由行内 NodeDef 派生。
 */
@RunWith(MockitoJUnitRunner.class)
public class LayerWalkerTest {

    @Mock
    private NodeRecursion nodes;
    @Mock
    private ChildCursor children;
    @Mock
    private ActionExecution actions;
    @Mock
    private ActionRows rows;
    @Mock
    private CancellationGate gate;
    @Mock
    private ContextFactory ctxFactory;
    @Mock
    private NodeInstMapper nodeMapper;
    @Mock
    private LaneMapper laneMapper;
    @Mock
    private ThreadPoolTaskExecutor nodePool;
    @Mock
    private ThreadPoolTaskExecutor shardPool;
    @Mock
    private ThreadPoolTaskExecutor actionPool;

    private LayerWalker walker;
    private NodeEnv env;

    /** 本轮夹具建过的节点行(按 id 找回同一对象; rootAt/childAt 桩与断言共用)。 */
    private final List<NodeInstEntity> nodeRows = new ArrayList<>();

    @Before
    public void setUp() {
        walker = new LayerWalker(nodePool, shardPool, actionPool, rows, ctxFactory, nodeMapper, laneMapper);
        env = new NodeEnv(nodes, children, actions, gate, walker);
    }

    // ===================== 夹具 =====================

    private static DriveContext dc(ProcessDefinition def) {
        ProcessInstEntity p = new ProcessInstEntity();
        p.setProcInstId("p1");
        return DriveContext.create(p, def, new ProcessContext("p1"), new VariableResolver(), null);
    }

    private NodeInstEntity row(String id, String errorPolicy) {
        for (NodeInstEntity r : nodeRows) {
            if (id.equals(r.getNodeInstId())) {
                return r;
            }
        }
        NodeInstEntity n = new NodeInstEntity();
        n.setNodeInstId(id);
        n.setErrorPolicy(errorPolicy);
        n.setDef(new NodeDef());   // 走查读 getDef() 派生执行档; 默认档 = SERIAL
        nodeRows.add(n);
        return n;
    }

    private static ActionInstEntity actionRow(String id, String status, String resultJson) {
        ActionInstEntity a = new ActionInstEntity();
        a.setActionInstId(id);
        a.setStatus(status);
        a.setResult(resultJson);
        return a;
    }

    /** 段行替身: 必须带 actionDefId——级段成员按"段值 + 定义成员表"收窄(与真库物化口径一致)。 */
    private static ActionInstEntity segmentRow(String id, String defId, String status) {
        ActionInstEntity a = actionRow(id, status, null);
        a.setActionDefId(defId);
        return a;
    }

    private static ActionDef actionDef(String id) {
        ActionDef ad = new ActionDef();
        ad.setId(id);
        ad.setHandler("h");
        return ad;
    }

    /** 驱动桩: 命中 failIds 的行报 FAILED, 其余 SUCCESS(按行分发, 与调用次序无关)。 */
    private void stubDriveNodeFailing(Set<String> failIds) {
        when(nodes.driveNode(any(DriveContext.class), any(NodeInstEntity.class), any(Context.class)))
                .thenAnswer(inv -> failIds.contains(((NodeInstEntity) inv.getArgument(1)).getNodeInstId())
                        ? Status.FAILED : Status.SUCCESS);
    }

    /** 按 ordered 逐个请 rootAt 桩(返回可找回的夹具行, id = n-<下标>)。 */
    private void stubRootRows(DriveContext dc, List<DslParser.Expr> ordered) {
        for (int i = 0; i < ordered.size(); i++) {
            when(children.rootAt(eq(dc), anyList(), eq(i), anyBoolean())).thenReturn(row("n-" + i, null));
            when(children.rootAtForFinally(eq(dc), anyList(), eq(i), anyBoolean())).thenReturn(row("n-" + i, null));
        }
    }

    private static Set<String> fail(String... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    /** 顶层入口的驱动计数(容器位 = 流程级上下文)。 */
    private void verifyDrivenTimes(DriveContext dc, int times) {
        verify(nodes, times(times))
                .driveNode(any(DriveContext.class), any(NodeInstEntity.class), eq(dc.processContext()));
    }

    /** 容器内的驱动计数(容器位 = 该容器的 NodeContext)。 */
    private void verifyDrivenInContainerTimes(Context container, int times) {
        verify(nodes, times(times))
                .driveNode(any(DriveContext.class), any(NodeInstEntity.class), eq(container));
    }

    // ===================== 1) 根序列: 就近声明优先 =====================

    /** 全局 CONTINUE + a 自己声明 FAIL_FAST: a 失败必须叫停后续根(项声明压过本层)。 */
    @Test
    public void rootItemDeclarationBeatsLayerPolicy() {
        ProcessDefinition def = new DefinitionLoader().load(
                "flow: \"a,b\"\nerrorPolicy: CONTINUE_ON_ERROR\n"
                        + "nodes:\n  a:\n    handler: h\n    errorPolicy: FAIL_FAST\n  b:\n    handler: h\n");
        DriveContext dc = dc(def);
        List<DslParser.Expr> ordered = def.orderedRoots();
        stubRootRows(dc, ordered);
        stubDriveNodeFailing(fail("n-0"));

        Status agg = walker.walkRoots(dc, env, ordered, ordered.size());

        Assert.assertEquals(Status.FAILED, agg);
        verifyDrivenTimes(dc, 1);
    }

    /**
     * 全局 FAIL_FAST 下的逐项判定: a 声明 CONTINUE_ON_ERROR, 它失败**不**拦后面的 b——
     * 更早的坏留在聚合值里(agg 已 FAILED), 但收敛只看 b 自己的终态; b 没吭声且自己成功, 走查继续;
     * b 换成失败的 c(也没吭声)时才回落本层 FAIL_FAST 叫停 d。若用聚合值判定, b 会被误杀。
     */
    @Test
    public void rootConvergenceJudgesOnlyThisItemOutcome() {
        ProcessDefinition def = new DefinitionLoader().load(
                "flow: \"a,b,c,d\"\nerrorPolicy: FAIL_FAST\n"
                        + "nodes:\n  a:\n    handler: h\n    errorPolicy: CONTINUE_ON_ERROR\n"
                        + "  b:\n    handler: h\n  c:\n    handler: h\n  d:\n    handler: h\n");
        DriveContext dc = dc(def);
        List<DslParser.Expr> ordered = def.orderedRoots();
        stubRootRows(dc, ordered);
        stubDriveNodeFailing(fail("n-0", "n-2"));   // a 与 c 坏, b/d 好

        Status agg = walker.walkRoots(dc, env, ordered, ordered.size());

        Assert.assertEquals(Status.FAILED, agg);
        verifyDrivenTimes(dc, 3);   // a(声明 CONTINUE, 不拦) -> b(成功) -> c(回落本层 FAIL_FAST, 叫停 d)
        verify(children, never()).rootAt(eq(dc), anyList(), eq(3), anyBoolean());
    }

    /** setdown 段是 finally 语义: flow 段失败照常叫停剩余 flow 根, 但 setdown 恒不收敛、成败都跑完。 */
    @Test
    public void setdownRunsEvenAfterFlowFailure() {
        ProcessDefinition def = new DefinitionLoader().load(
                "setup: [s]\nflow: \"a,b\"\nsetdown: [d1,d2]\nnodes:\n"
                        + "  s:\n    handler: h\n  a:\n    handler: h\n  b:\n    handler: h\n"
                        + "  d1:\n    handler: h\n  d2:\n    handler: h\n");
        DriveContext dc = dc(def);
        List<DslParser.Expr> ordered = def.orderedRoots();
        Assert.assertEquals("有序视图 = setup+flow+setdown", 5, ordered.size());
        stubRootRows(dc, ordered);
        stubDriveNodeFailing(fail("n-1"));

        Status agg = walker.walkRoots(dc, env, ordered, def.setupSize() + def.roots().size());

        Assert.assertEquals(Status.FAILED, agg);
        verifyDrivenTimes(dc, 2);   // s, a 跑了; b 被叫停
        verify(nodes, times(2)).driveNodeCleanup(any(DriveContext.class), any(NodeInstEntity.class),
                any(Context.class));   // d1, d2(setdown)走 finally 完整执行档
    }

    /** 顶层不查取消(原 NO_GATE 口径): 整段走查一次都不读取消闸门。 */
    @Test
    public void rootSequenceNeverReadsCancelGate() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        List<DslParser.Expr> ordered = def.orderedRoots();
        stubRootRows(dc, ordered);
        stubDriveNodeFailing(Collections.<String>emptySet());

        Assert.assertEquals(Status.SUCCESS, walker.walkRoots(dc, env, ordered, 1));
        verify(gate, never()).isCancelled(any(DriveContext.class));
    }

    // ===================== 2) 组内序列: 查库 + 组行冻结策略 =====================

    /** 组行 FAIL_FAST: 子项失败即停, 剩余子节点不定位("执行到才建", 手里没有可清理的行)。 */
    @Test
    public void groupWalkStopsOnFailFastRowPolicy() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity group = row("g", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), group);
        List<DslParser.Expr> items = Arrays.asList(
                new DslParser.Leaf("x"), new DslParser.Leaf("y"), new DslParser.Leaf("z"));
        when(gate.isCancelled(dc)).thenReturn(false);
        for (int i = 0; i < items.size(); i++) {
            when(children.childAt(eq(dc), eq(group), eq(items), eq(i), anyBoolean()))
                    .thenReturn(row("c-" + i, null));
        }
        stubDriveNodeFailing(fail("c-1"));

        Status agg = walker.walkChildren(dc, env, ctx, items, WalkMode.SERIAL);

        Assert.assertEquals(Status.FAILED, agg);
        verifyDrivenInContainerTimes(ctx, 2);
        verify(children, never()).childAt(eq(dc), eq(group), eq(items), eq(2), anyBoolean());
    }

    /** 组内每步读取消闸门(约束: 取消真值只有 process_inst 一份): 第 2 项前命中即停, 聚合翻 STOPPED。 */
    @Test
    public void groupWalkReadsCancelGateEachStep() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity group = row("g", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), group);
        List<DslParser.Expr> items = Arrays.asList(new DslParser.Expr[]{
                new DslParser.Leaf("x"), new DslParser.Leaf("y")});
        when(gate.isCancelled(dc)).thenReturn(false, true);
        when(children.childAt(eq(dc), eq(group), eq(items), eq(0), anyBoolean())).thenReturn(row("c-0", null));
        stubDriveNodeFailing(Collections.<String>emptySet());

        Status agg = walker.walkChildren(dc, env, ctx, items, WalkMode.SERIAL);

        Assert.assertEquals("取消把本段聚合成 STOPPED(兄弟成功不冲掉)", Status.STOPPED, agg);
        verify(children, never()).childAt(eq(dc), eq(group), eq(items), eq(1), anyBoolean());
    }

    // ===================== 3) lane 序列: 建 lane 与跑 lane 同点, 提前结束手里没有剩余行 =====================

    /** lane FAIL_FAST: 第 2 条 lane 失败即停, 第 3 片**连 lane 行都不建**(走查到该分片才建)。 */
    @Test
    public void laneWalkStopsBeforeMaterializingRemainingShards() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");   // 空 NodeDef: defs = 单 handler 合成的 1 条
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        List<String> shardKeys = Arrays.asList("d1", "d2", "d3");
        when(gate.isCancelled(dc)).thenReturn(false);
        when(rows.ensureLaneRow(eq(dc), eq(node), anyString())).thenAnswer(inv -> {
            LaneEntity l = new LaneEntity();
            l.setLaneId("lane-" + inv.getArgument(2));
            l.setShardKey(inv.getArgument(2));
            return l;
        });
        when(ctxFactory.lane(any(NodeContext.class), any(LaneEntity.class))).thenAnswer(inv ->
                new LaneContext(inv.getArgument(0), ((LaneEntity) inv.getArgument(1)).getLaneId(),
                        ((LaneEntity) inv.getArgument(1)).getShardKey()));
        when(rows.findInContainer(anyString(), any(), any())).thenReturn(Collections.<ActionInstEntity>emptyList());
        when(rows.ensureActionRow(eq(dc), eq(node), any(), any(ActionDef.class), any(), any()))
                .thenAnswer(inv -> actionRow("ax", "created", null));
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenReturn(Status.SUCCESS, Status.FAILED);

        Status agg = walker.walkLanes(dc, env, ctx, shardKeys);

        Assert.assertEquals(Status.FAILED, agg);
        verify(rows, times(2)).ensureLaneRow(eq(dc), eq(node), anyString());
        verify(rows, never()).ensureLaneRow(dc, node, "d3");
    }

    // ===================== 4) action 序列: drain / 重入 / 未物化不补建 =====================

    /** action 层 FAIL_FAST 收敛: 只 drain 手里已有的行, 未物化的第 3 项既不补建也无行可标。 */
    @Test
    public void actionConvergeNeverMaterializesRemainingItems() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        List<ActionDef> defs = Arrays.asList(actionDef("a1"), actionDef("a2"), actionDef("a3"));
        node.getDef().setActions(defs);   // 走查层从行内定义自取 defs
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.MAIN))
                .thenReturn(Collections.singletonList(actionRow("x0", "created", null)));   // 只有第 1 项已有行
        when(gate.isCancelled(dc)).thenReturn(false);
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenReturn(Status.SUCCESS, Status.FAILED);
        when(rows.ensureActionRow(eq(dc), eq(node), any(), any(ActionDef.class), any(), any()))
                .thenAnswer(inv -> actionRow("x1", "created", null));   // 第 2 项: 执行到才建

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals(Status.FAILED, agg);
        verify(rows, times(1)).ensureActionRow(eq(dc), eq(node), any(), any(ActionDef.class), any(), any());   // 只建到第 2 项
        verify(rows, never()).ensureActionRow(eq(dc), eq(node), any(), eq(defs.get(2)), any(), any());          // 第 3 项从不补建
        verify(actions, never()).markActionDrained(any(), any(), any(), any());   // 第 3 项没建过行, 手里无行可 drain
    }

    /** drain 只碰已物化行: 剩余 CREATED 行落 STOPPED/DRAIN, 原有业务 result 并入不丢; RUNNING 行不是"没跑过"不动。 */
    @Test
    public void actionDrainKeepsExistingBusinessResult() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        List<ActionDef> defs = Arrays.asList(actionDef("a1"), actionDef("a2"), actionDef("a3"));
        node.getDef().setActions(defs);
        List<ActionInstEntity> existing = Arrays.asList(
                actionRow("x0", "created", null),
                actionRow("x1", "running", null),
                actionRow("x2", "created", "{\"biz\":\"keep\"}"));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.MAIN)).thenReturn(existing);
        when(gate.isCancelled(dc)).thenReturn(false);
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenReturn(Status.FAILED);

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals(Status.FAILED, agg);
        verify(actions, times(1)).markActionDrained(any(), any(), any(), any());
        verify(actions).markActionDrained(eq(existing.get(2)), eq("stopped"), contains("\"DRAIN\""), any());
        verify(actions, never()).markActionDrained(eq(existing.get(1)), any(), any(), any());
    }

    /** 取消落在第 1 项之前: 手里三行全落 STOPPED/DRAIN(含 PENDING), 一个 action 都不跑, 聚合 STOPPED。 */
    @Test
    public void actionCancelDrainsAllMaterializedRows() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        List<ActionDef> defs = Arrays.asList(actionDef("a1"), actionDef("a2"), actionDef("a3"));
        node.getDef().setActions(defs);
        List<ActionInstEntity> existing = Arrays.asList(
                actionRow("x0", "created", null), actionRow("x1", "pending", null), actionRow("x2", "created", null));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.MAIN)).thenReturn(existing);
        when(gate.isCancelled(dc)).thenReturn(true);

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals(Status.STOPPED, agg);
        verify(actions, times(3)).markActionDrained(any(), eq("stopped"), any(), any());
        verify(actions, never()).runAction(any(), any(), any(), any());
    }

    // ===================== 5) 并行档(action / lane 两层): 扇出 + Fanout join =====================

    /** action PARALLEL: 缺行先补齐(扇出需同时在), CREATED 的才提交 action 池, 已跑过的重入行不进池。 */
    @Test
    @SuppressWarnings("unchecked")
    public void actionParallelFansOutCreatedRowsOnly() throws Exception {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        List<ActionDef> defs = Arrays.asList(actionDef("a1"), actionDef("a2"), actionDef("a3"));
        node.getDef().setActions(defs);
        node.getDef().setActionExecution("PARALLEL");   // 执行档从行内定义派生
        List<ActionInstEntity> existing = Arrays.asList(
                actionRow("x0", "created", null), actionRow("x1", "success", null));   // 第 3 项缺行
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.MAIN)).thenReturn(existing);
        Future<Status> future = org.mockito.Mockito.mock(Future.class);
        when(future.get()).thenReturn(Status.SUCCESS);
        when(actionPool.submit(any(Callable.class))).thenReturn(future);
        when(rows.ensureActionRow(eq(dc), eq(node), any(), any(ActionDef.class), any(), any()))
                .thenAnswer(inv -> actionRow("x2", "created", null));   // 缺的第 3 行按需补齐

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals(Status.SUCCESS, agg);
        verify(rows, times(1)).ensureActionRow(eq(dc), eq(node), any(), eq(defs.get(2)), any(), any());
        verify(actionPool, times(2)).submit(any(Callable.class));   // x0 与补齐的 x2 进池; 已跑过的 x1 不重复提交
    }

    /** lane PARALLEL: 先建全 lane(扇出需同时在), 每 lane 提交分片池, join 聚合。 */
    @Test
    @SuppressWarnings("unchecked")
    public void laneParallelMaterializesAllLanesBeforeFanout() throws Exception {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        node.getDef().setLaneExecution("PARALLEL");   // 执行档从行内定义派生
        List<String> shardKeys = Arrays.asList("d1", "d2", "d3");
        Future<Status> future = org.mockito.Mockito.mock(Future.class);
        when(future.get()).thenReturn(Status.SUCCESS);
        when(shardPool.submit(any(Callable.class))).thenReturn(future);
        when(rows.ensureLaneRow(eq(dc), eq(node), anyString())).thenAnswer(inv -> {
            LaneEntity l = new LaneEntity();
            l.setLaneId("lane-" + inv.getArgument(2));
            return l;
        });

        Status agg = walker.walkLanes(dc, env, ctx, shardKeys);

        Assert.assertEquals(Status.SUCCESS, agg);
        verify(rows, times(3)).ensureLaneRow(eq(dc), eq(node), anyString());   // 三条 lane 先全部就位
        verify(shardPool, times(3)).submit(any(Callable.class));
    }

    /** 重入: 上一轮已 FAILED 的行不重跑, 本轮按 SUCCESS 计入聚合(旧终态不重复算进这一轮)。 */
    @Test
    public void reenteredTerminalActionCountsAsSuccessWithoutRerun() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        List<ActionDef> defs = Arrays.asList(actionDef("a1"), actionDef("a2"));
        node.getDef().setActions(defs);
        List<ActionInstEntity> existing = Arrays.asList(
                actionRow("x0", "failed", null), actionRow("x1", "created", null));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.MAIN)).thenReturn(existing);
        when(gate.isCancelled(dc)).thenReturn(false);
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenReturn(Status.SUCCESS);

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals("上一轮的 FAILED 不重复算进这一轮", Status.SUCCESS, agg);
        verify(actions, times(1)).runAction(eq(dc), any(Context.class), eq(existing.get(1)), any(ActionDef.class));
        verify(actions, never()).runAction(eq(dc), any(Context.class), eq(existing.get(0)), any(ActionDef.class));
    }

    // ===================== 6) 子节点扇出层: 恒走扇出模板 =====================

    /** 三条子项: 逐条 childAt 定位后全部提交 node 池(备料在提交线程同步做), join 聚合成 SUCCESS。 */
    @Test
    @SuppressWarnings("unchecked")
    public void parallelChildrenLocateThenFanOutAll() throws Exception {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("p", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        List<DslParser.Expr> items = Arrays.asList(
                new DslParser.Leaf("x"), new DslParser.Leaf("y"), new DslParser.Leaf("z"));
        for (int i = 0; i < items.size(); i++) {
            when(children.childAt(eq(dc), eq(node), eq(items), eq(i), anyBoolean()))
                    .thenReturn(row("k-" + i, null));
        }
        Future<Status> future = org.mockito.Mockito.mock(Future.class);
        when(future.get()).thenReturn(Status.SUCCESS);
        when(nodePool.submit(any(Callable.class))).thenReturn(future);

        Status agg = walker.walkChildren(dc, env, ctx, items, WalkMode.FANOUT);

        Assert.assertEquals(Status.SUCCESS, agg);
        verify(children, times(3)).childAt(eq(dc), eq(node), eq(items), anyInt(), anyBoolean());
        verify(nodePool, times(3)).submit(any(Callable.class));
    }

    /** FAIL_FAST 汇聚口径交给 Fanout: 首条失败后只取消**尚未开始**的排队任务, 已提交的不强行中断。 */
    @Test
    @SuppressWarnings("unchecked")
    public void parallelFailFastCancelsOnlyPendingSiblings() throws Exception {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("p", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        List<DslParser.Expr> items = Arrays.asList(new DslParser.Expr[]{
                new DslParser.Leaf("x"), new DslParser.Leaf("y")});
        when(children.childAt(eq(dc), eq(node), eq(items), anyInt(), anyBoolean())).thenReturn(row("k-0", null));
        Future<Status> boom = org.mockito.Mockito.mock(Future.class);
        Future<Status> pending = org.mockito.Mockito.mock(Future.class);
        when(boom.get()).thenReturn(Status.FAILED);
        when(pending.get()).thenReturn(Status.SUCCESS);
        when(nodePool.submit(any(Callable.class))).thenReturn(boom, pending);

        Status agg = walker.walkChildren(dc, env, ctx, items, WalkMode.FANOUT);

        Assert.assertEquals(Status.FAILED, agg);
        verify(pending).cancel(false);              // 未开始的排队任务被 drain
        verify(boom, never()).cancel(anyBoolean());  // 已经跑完的那条不动
    }

    // ===================== 7) 节点回路: setup/main/setdown 三段 =====================

    /** 三段按序驱动: setup -> main -> setdown; 每段定位都带段参数(段过滤是语义关键); 容器行状态只在收尾落一次。 */
    @Test
    public void circuitDrivesSetupMainSetdownInOrder() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        ActionDef p1 = actionDef("p1");
        ActionDef m1 = actionDef("m1");
        ActionDef r1 = actionDef("r1");
        node.getDef().setSetup(Collections.singletonList(p1));
        node.getDef().setActions(Collections.singletonList(m1));
        node.getDef().setSetdown(Collections.singletonList(r1));
        when(rows.findInContainer(eq(node.getNodeInstId()), any(), any()))
                .thenReturn(Collections.<ActionInstEntity>emptyList());
        when(rows.ensureActionRow(eq(dc), eq(node), any(), any(ActionDef.class), any(), any()))
                .thenAnswer(inv -> actionRow("x-" + ((ActionDef) inv.getArgument(3)).getId(), "created", null));
        when(gate.isCancelled(dc)).thenReturn(false);
        final List<String> ran = new ArrayList<>();
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenAnswer(inv -> {
                    ran.add(((ActionDef) inv.getArgument(3)).getId());
                    return Status.SUCCESS;
                });

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals(Status.SUCCESS, agg);
        Assert.assertEquals("段序 setup -> main -> setdown", Arrays.asList("p1", "m1", "r1"), ran);
        verify(rows).ensureActionRow(eq(dc), eq(node), any(), eq(p1), any(), eq(ActionSegment.SETUP));
        verify(rows).ensureActionRow(eq(dc), eq(node), any(), eq(m1), any(), eq(ActionSegment.MAIN));
        verify(rows).ensureActionRow(eq(dc), eq(node), any(), eq(r1), any(), eq(ActionSegment.SETDOWN));
        // 不变式 I1: 容器行状态不在这里落——唯一写入口是 NodeDriver.finishNode(status + result 同一条 UPDATE)
        verify(nodeMapper, never()).setStatus(eq(node.getNodeInstId()), anyString(), any());
    }

    /** setup 段坏 -> 主段整体跳过(预物化行 drain), setdown 照跑; 终态三段聚合 FAILED。 */
    @Test
    public void circuitSkipsMainAndStillRunsSetdownWhenSetupFails() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        ActionDef p1 = actionDef("p1");
        ActionDef m1 = actionDef("m1");
        ActionDef r1 = actionDef("r1");
        node.getDef().setSetup(Collections.singletonList(p1));
        node.getDef().setActions(Collections.singletonList(m1));
        node.getDef().setSetdown(Collections.singletonList(r1));
        ActionInstEntity preMain = actionRow("m0", "created", null);   // 上一轮已物化、本轮没跑
        when(rows.findInContainer(eq(node.getNodeInstId()), any(), any()))
                .thenReturn(Collections.<ActionInstEntity>emptyList());
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.MAIN))
                .thenReturn(Collections.singletonList(preMain));       // 跳主段时统一 drain
        when(rows.ensureActionRow(eq(dc), eq(node), any(), any(ActionDef.class), any(), any()))
                .thenAnswer(inv -> actionRow("x-" + ((ActionDef) inv.getArgument(3)).getId(), "created", null));
        when(gate.isCancelled(dc)).thenReturn(false);
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenAnswer(inv -> "p1".equals(((ActionDef) inv.getArgument(3)).getId()) ? Status.FAILED : Status.SUCCESS);

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals(Status.FAILED, agg);
        verify(actions).markActionDrained(eq(preMain), eq("stopped"), contains("\"DRAIN\""), any());
        verify(actions, never()).runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), eq(m1));    // 主段跳过
        verify(actions, times(1)).runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), eq(r1));   // setdown 照跑
        verify(nodeMapper, never()).setStatus(eq(node.getNodeInstId()), anyString(), any());
    }

    /** setdown 段恒不收敛: 项坏不拦剩余项(与主段 FAIL_FAST 收敛相反), 坏项照样进终态聚合。 */
    @Test
    public void setdownSegmentNeverConverges() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        ActionDef m1 = actionDef("m1");
        ActionDef r1 = actionDef("r1");
        ActionDef r2 = actionDef("r2");
        node.getDef().setActions(Collections.singletonList(m1));
        node.getDef().setSetdown(Arrays.asList(r1, r2));
        when(rows.findInContainer(eq(node.getNodeInstId()), any(), any()))
                .thenReturn(Collections.<ActionInstEntity>emptyList());
        when(rows.ensureActionRow(eq(dc), eq(node), any(), any(ActionDef.class), any(), any()))
                .thenAnswer(inv -> actionRow("x-" + ((ActionDef) inv.getArgument(3)).getId(), "created", null));
        when(gate.isCancelled(dc)).thenReturn(false);
        final List<String> ran = new ArrayList<>();
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenAnswer(inv -> {
                    String id = ((ActionDef) inv.getArgument(3)).getId();
                    ran.add(id);
                    return "r1".equals(id) ? Status.FAILED : Status.SUCCESS;
                });

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals(Status.FAILED, agg);
        Assert.assertEquals("收尾段项坏不拦后续项", Arrays.asList("m1", "r1", "r2"), ran);
    }

    /** 取消闸门: 主段取消即停并 drain, setdown 段不查闸门(收尾必达)。 */
    @Test
    public void setdownIgnoresCancelGate() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        ActionDef m1 = actionDef("m1");
        ActionDef r1 = actionDef("r1");
        node.getDef().setActions(Collections.singletonList(m1));
        node.getDef().setSetdown(Collections.singletonList(r1));
        List<ActionInstEntity> mainRows = Arrays.asList(
                actionRow("m0", "created", null), actionRow("m1x", "pending", null));
        when(rows.findInContainer(eq(node.getNodeInstId()), any(), any()))
                .thenReturn(Collections.<ActionInstEntity>emptyList());
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.MAIN)).thenReturn(mainRows);
        when(gate.isCancelled(dc)).thenReturn(true);
        when(rows.ensureActionRow(eq(dc), eq(node), any(), any(ActionDef.class), any(), any()))
                .thenAnswer(inv -> actionRow("x-" + ((ActionDef) inv.getArgument(3)).getId(), "created", null));
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenReturn(Status.SUCCESS);

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals(Status.STOPPED, agg);
        verify(actions, times(2)).markActionDrained(any(), eq("stopped"), any(), any());   // 主段两行全 drain
        verify(actions, never()).runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), eq(m1));
        verify(actions, times(1)).runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), eq(r1));   // setdown 照跑
        verify(gate, times(1)).isCancelled(dc);   // 只在主段查过一次(命中取消即 break)
    }

    // ===================== 8) 让渡(yield): 立即停且不 drain / 不跑 setdown / 容器不落终态 =====================

    /** 主段让渡(pending): 立即停且不 drain(留给重入)、setdown 不跑、容器行不落终态, 状态原值上传。 */
    @Test
    public void mainYieldSkipsSetdownWithoutDrainAndSettle() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        ActionDef p1 = actionDef("p1");
        ActionDef m1 = actionDef("m1");
        ActionDef m2 = actionDef("m2");
        ActionDef r1 = actionDef("r1");
        node.getDef().setSetup(Collections.singletonList(p1));
        node.getDef().setActions(Arrays.asList(m1, m2));
        node.getDef().setSetdown(Collections.singletonList(r1));
        when(rows.findInContainer(eq(node.getNodeInstId()), any(), any()))
                .thenReturn(Collections.<ActionInstEntity>emptyList());
        when(rows.ensureActionRow(eq(dc), eq(node), any(), any(ActionDef.class), any(), any()))
                .thenAnswer(inv -> actionRow("x-" + ((ActionDef) inv.getArgument(3)).getId(), "created", null));
        when(gate.isCancelled(dc)).thenReturn(false);
        final List<String> ran = new ArrayList<>();
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenAnswer(inv -> {
                    String id = ((ActionDef) inv.getArgument(3)).getId();
                    ran.add(id);
                    return "m1".equals(id) ? Status.PENDING : Status.SUCCESS;
                });

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals("让渡态原值上传", Status.PENDING, agg);
        Assert.assertEquals("setup 跑过; 主段第 1 项让渡后立即停(后续项与 setdown 都不跑)",
                Arrays.asList("p1", "m1"), ran);
        verify(actions, never()).markActionDrained(any(), any(), any(), any());   // 让渡不 drain(留给重入)
        verify(nodeMapper, never()).setStatus(anyString(), anyString(), any());   // 容器行不落终态(节点停 running)
    }

    /** setup 段让渡(suspended): 主段与 setdown 都不跑、不补建主段行, 容器行不落终态。 */
    @Test
    public void setupYieldSkipsMainAndSetdown() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        ActionDef p1 = actionDef("p1");
        ActionDef m1 = actionDef("m1");
        ActionDef r1 = actionDef("r1");
        node.getDef().setSetup(Collections.singletonList(p1));
        node.getDef().setActions(Collections.singletonList(m1));
        node.getDef().setSetdown(Collections.singletonList(r1));
        when(rows.findInContainer(eq(node.getNodeInstId()), any(), any()))
                .thenReturn(Collections.<ActionInstEntity>emptyList());
        when(rows.ensureActionRow(eq(dc), eq(node), any(), any(ActionDef.class), any(), any()))
                .thenAnswer(inv -> actionRow("x-" + ((ActionDef) inv.getArgument(3)).getId(), "created", null));
        when(gate.isCancelled(dc)).thenReturn(false);
        final List<String> ran = new ArrayList<>();
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenAnswer(inv -> {
                    ran.add(((ActionDef) inv.getArgument(3)).getId());
                    return Status.SUSPENDED;
                });

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals(Status.SUSPENDED, agg);
        Assert.assertEquals("只跑了 setup 那一项", Collections.singletonList("p1"), ran);
        verify(rows, never()).ensureActionRow(eq(dc), eq(node), any(), eq(m1), any(), any());   // 主段行都不补建
        verify(nodeMapper, never()).setStatus(anyString(), anyString(), any());
    }

    /** 坏终态优先于让渡: 本轮先有 FAILED(CONTINUE 档)再遇 pending, 按 FAILED 现状收尾——setdown 照跑、容器落 FAILED。 */
    @Test
    public void failureBeatsYieldWhenBothInSameSegment() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "continue_on_error");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        ActionDef m1 = actionDef("m1");
        ActionDef m2 = actionDef("m2");
        ActionDef m3 = actionDef("m3");
        ActionDef r1 = actionDef("r1");
        node.getDef().setActions(Arrays.asList(m1, m2, m3));
        node.getDef().setSetdown(Collections.singletonList(r1));
        when(rows.findInContainer(eq(node.getNodeInstId()), any(), any()))
                .thenReturn(Collections.<ActionInstEntity>emptyList());
        when(rows.ensureActionRow(eq(dc), eq(node), any(), any(ActionDef.class), any(), any()))
                .thenAnswer(inv -> actionRow("x-" + ((ActionDef) inv.getArgument(3)).getId(), "created", null));
        when(gate.isCancelled(dc)).thenReturn(false);
        final List<String> ran = new ArrayList<>();
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenAnswer(inv -> {
                    String id = ((ActionDef) inv.getArgument(3)).getId();
                    ran.add(id);
                    return "m1".equals(id) ? Status.FAILED : ("m2".equals(id) ? Status.PENDING : Status.SUCCESS);
                });

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals("已出现 FAILED: 不让渡, 按坏终态收尾", Status.FAILED, agg);
        Assert.assertEquals("m1 坏 -> m2 让渡停 -> m3 不跑 -> setdown 照跑",
                Arrays.asList("m1", "m2", "r1"), ran);
        verify(actions, never()).markActionDrained(any(), any(), any(), any());
        verify(nodeMapper, never()).setStatus(eq(node.getNodeInstId()), anyString(), any());
    }

    /** 重入翻面: 终态行(success)跳过算 SUCCESS 不重跑; 非终态行(pending/running)与 created 都重跑。 */
    @Test
    public void reenteredNonTerminalActionReruns() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        List<ActionDef> defs = Arrays.asList(actionDef("a1"), actionDef("a2"), actionDef("a3"));
        node.getDef().setActions(defs);
        List<ActionInstEntity> existing = Arrays.asList(
                actionRow("x0", "success", null),
                actionRow("x1", "pending", null),
                actionRow("x2", "created", null));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.MAIN)).thenReturn(existing);
        when(gate.isCancelled(dc)).thenReturn(false);
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenReturn(Status.SUCCESS);

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals(Status.SUCCESS, agg);
        verify(actions, never()).runAction(eq(dc), any(Context.class), eq(existing.get(0)), any(ActionDef.class));
        verify(actions, times(1)).runAction(eq(dc), any(Context.class), eq(existing.get(1)), any(ActionDef.class));
        verify(actions, times(1)).runAction(eq(dc), any(Context.class), eq(existing.get(2)), any(ActionDef.class));
    }

    /** 并行档让渡: join 后任一非终态保留为让渡态、不 cancelPending; setdown 不跑、容器行不落终态。 */
    @Test
    @SuppressWarnings("unchecked")
    public void parallelYieldKeepsYieldStateAndSkipsSetdown() throws Exception {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        List<ActionDef> defs = Arrays.asList(actionDef("a1"), actionDef("a2"));
        node.getDef().setActions(defs);
        node.getDef().setActionExecution("PARALLEL");
        node.getDef().setSetdown(Collections.singletonList(actionDef("r1")));
        List<ActionInstEntity> existing = Arrays.asList(
                actionRow("x0", "created", null), actionRow("x1", "created", null));
        when(rows.findInContainer(eq(node.getNodeInstId()), any(), any())).thenReturn(existing);
        Future<Status> ok = org.mockito.Mockito.mock(Future.class);
        Future<Status> yielded = org.mockito.Mockito.mock(Future.class);
        when(ok.get()).thenReturn(Status.SUCCESS);
        when(yielded.get()).thenReturn(Status.PAUSED);
        when(actionPool.submit(any(Callable.class))).thenReturn(ok, yielded);

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals("兄弟成功不冲掉让渡态", Status.PAUSED, agg);
        verify(yielded, never()).cancel(anyBoolean());   // 让渡不是失败: 不取消排队兄弟
        verify(rows, never()).findInContainer(anyString(), any(), eq(ActionSegment.SETDOWN));   // setdown 段不进入
        verify(nodeMapper, never()).setStatus(anyString(), anyString(), any());
    }

    /** 顶层让渡: flow 段某根让渡 -> 顶层 setdown 不跑, 让渡态原值返回。 */
    @Test
    public void rootYieldSkipsTopSetdown() {
        ProcessDefinition def = new DefinitionLoader().load(
                "setup: [s]\nflow: \"a,b\"\nsetdown: [d1]\nnodes:\n"
                        + "  s:\n    handler: h\n  a:\n    handler: h\n  b:\n    handler: h\n  d1:\n    handler: h\n");
        DriveContext dc = dc(def);
        List<DslParser.Expr> ordered = def.orderedRoots();
        stubRootRows(dc, ordered);
        when(nodes.driveNode(any(DriveContext.class), any(NodeInstEntity.class), any(Context.class)))
                .thenAnswer(inv -> "n-1".equals(((NodeInstEntity) inv.getArgument(1)).getNodeInstId())
                        ? Status.PENDING : Status.SUCCESS);

        Status agg = walker.walkRoots(dc, env, ordered, 3);   // flowEnd = setup(1) + roots(2)

        Assert.assertEquals(Status.PENDING, agg);
        verifyDrivenTimes(dc, 2);   // s, a 跑了; a 让渡后 b 与 setdown 都不跑
    }

    /** lane 让渡: 第 2 条 lane 让渡 -> 第 3 片连 lane 行都不建, 让渡态上传; 让渡 lane 不落 lane 行状态。 */
    @Test
    public void laneYieldStopsSubsequentLanes() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        List<String> shardKeys = Arrays.asList("d1", "d2", "d3");
        when(gate.isCancelled(dc)).thenReturn(false);
        when(rows.ensureLaneRow(eq(dc), eq(node), anyString())).thenAnswer(inv -> {
            LaneEntity l = new LaneEntity();
            l.setLaneId("lane-" + inv.getArgument(2));
            l.setShardKey(inv.getArgument(2));
            return l;
        });
        when(ctxFactory.lane(any(NodeContext.class), any(LaneEntity.class))).thenAnswer(inv ->
                new LaneContext(inv.getArgument(0), ((LaneEntity) inv.getArgument(1)).getLaneId(),
                        ((LaneEntity) inv.getArgument(1)).getShardKey()));
        when(rows.findInContainer(anyString(), any(), any())).thenReturn(Collections.<ActionInstEntity>emptyList());
        when(rows.ensureActionRow(eq(dc), eq(node), any(), any(ActionDef.class), any(), any()))
                .thenAnswer(inv -> actionRow("ax", "created", null));
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenReturn(Status.SUCCESS, Status.PENDING);

        Status agg = walker.walkLanes(dc, env, ctx, shardKeys);

        Assert.assertEquals(Status.PENDING, agg);
        verify(rows, times(2)).ensureLaneRow(eq(dc), eq(node), anyString());
        verify(rows, never()).ensureLaneRow(dc, node, "d3");
        verify(laneMapper, times(1)).updateStatusResult(anyString(), anyString(), any(), any());   // 只有第 1 条 lane 落了状态+result
    }

    /** 非法非终态(上游漏校验时的兜底): runSerial 把 RUNNING 归一为 FAILED——不静默过聚合、按失败口径收敛 drain。 */
    @Test
    public void illegalDriveResultNormalizesToFailed() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        List<ActionDef> defs = Arrays.asList(actionDef("a1"), actionDef("a2"));
        node.getDef().setActions(defs);
        List<ActionInstEntity> existing = Arrays.asList(
                actionRow("x0", "created", null), actionRow("x1", "created", null));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.MAIN)).thenReturn(existing);
        when(gate.isCancelled(dc)).thenReturn(false);
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenReturn(Status.RUNNING);   // 绕过 ActionRunner 白名单的非法返回

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals("非法非终态归一为 FAILED, 不当让渡保持", Status.FAILED, agg);
        Assert.assertTrue("归一必须留痕: 流程级失败摘要带得上(用户不会只看到莫名 FAILED)",
                dc.processContext().failure() != null && dc.processContext().failure().contains("非法过程态"));
        verify(actions, times(1)).runAction(eq(dc), any(Context.class), eq(existing.get(0)), any(ActionDef.class));
        verify(actions, never()).runAction(eq(dc), any(Context.class), eq(existing.get(1)), any(ActionDef.class));
        verify(actions).markActionDrained(eq(existing.get(1)), eq("stopped"), contains("\"DRAIN\""), any());   // 按失败口径收敛
        verify(nodeMapper, never()).setStatus(eq(node.getNodeInstId()), anyString(), any());
    }

    /** 并行档非法返回值: join 归一为 FAILED 并留痕(不静默); 归一后按失败口径参与 FAIL_FAST 取消。 */
    @Test
    @SuppressWarnings("unchecked")
    public void illegalParallelResultNormalizesAndReports() throws Exception {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        List<ActionDef> defs = Arrays.asList(actionDef("a1"), actionDef("a2"));
        node.getDef().setActions(defs);
        node.getDef().setActionExecution("PARALLEL");
        List<ActionInstEntity> existing = Arrays.asList(
                actionRow("x0", "created", null), actionRow("x1", "created", null));
        when(rows.findInContainer(eq(node.getNodeInstId()), any(), any())).thenReturn(existing);
        Future<Status> bad = org.mockito.Mockito.mock(Future.class);
        Future<Status> sibling = org.mockito.Mockito.mock(Future.class);
        when(bad.get()).thenReturn(Status.RUNNING);   // 绕过源头白名单的非法返回
        when(sibling.get()).thenReturn(Status.SUCCESS);
        when(actionPool.submit(any(Callable.class))).thenReturn(bad, sibling);

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals(Status.FAILED, agg);
        verify(sibling).cancel(false);   // 归一为失败后参与 FAIL_FAST 取消未开始兄弟
        Assert.assertTrue("并行档归一同样留痕",
                dc.processContext().failure() != null && dc.processContext().failure().contains("非法过程态"));
    }

    // ===================== v1.1: 段重臂判据矩阵 =====================

    /** 成对重臂命中(main 侧): main 有非终态行 + setdown 有 success -> setup/setdown 的 success 行成对复位。 */
    @Test
    public void pairedReArmFiresWhenMainWillRerunAndSetdownTorn() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        node.getDef().setSetup(Collections.singletonList(actionDef("p1")));
        node.getDef().setActions(Collections.singletonList(actionDef("m1")));
        node.getDef().setSetdown(Collections.singletonList(actionDef("r1")));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.SETUP))
                .thenReturn(Collections.singletonList(actionRow("x0", "success", null)));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.MAIN))
                .thenReturn(Collections.singletonList(actionRow("x1", "created", null)));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.SETDOWN))
                .thenReturn(Collections.singletonList(actionRow("x2", "success", null)));
        when(gate.isCancelled(dc)).thenReturn(false);
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenReturn(Status.SUCCESS);

        walker.walkCircuit(dc, env, ctx, null);

        verify(rows).reArmSegment(eq(node.getNodeInstId()), eq(null), eq(ActionSegment.SETUP), eq(false), any());
        verify(rows).reArmSegment(eq(node.getNodeInstId()), eq(null), eq(ActionSegment.SETDOWN), eq(false), any());
    }

    /** 成对重臂命中(setup 侧): setup 有非终态行(主段全终态) + setdown 有 success -> 照常成对复位(修正漏判路径)。 */
    @Test
    public void pairedReArmFiresWhenSetupWillRerunEvenIfMainDone() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        node.getDef().setSetup(Collections.singletonList(actionDef("p1")));
        node.getDef().setActions(Collections.singletonList(actionDef("m1")));
        node.getDef().setSetdown(Collections.singletonList(actionDef("r1")));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.SETUP))
                .thenReturn(Collections.singletonList(actionRow("x0", "created", null)));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.MAIN))
                .thenReturn(Collections.singletonList(actionRow("x1", "success", null)));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.SETDOWN))
                .thenReturn(Collections.singletonList(actionRow("x2", "success", null)));
        when(gate.isCancelled(dc)).thenReturn(false);
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenReturn(Status.SUCCESS);

        walker.walkCircuit(dc, env, ctx, null);

        verify(rows).reArmSegment(eq(node.getNodeInstId()), eq(null), eq(ActionSegment.SETUP), eq(false), any());
        verify(rows).reArmSegment(eq(node.getNodeInstId()), eq(null), eq(ActionSegment.SETDOWN), eq(false), any());
    }

    /** 不命中(现场仍在): main 有非终态行但 setdown 无 success -> 一处都不复位(崩溃补跑路径逐字不变)。 */
    @Test
    public void pairedReArmSkippedWhenSetdownNotTorn() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        node.getDef().setSetup(Collections.singletonList(actionDef("p1")));
        node.getDef().setActions(Collections.singletonList(actionDef("m1")));
        node.getDef().setSetdown(Collections.singletonList(actionDef("r1")));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.SETUP))
                .thenReturn(Collections.singletonList(actionRow("x0", "success", null)));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.MAIN))
                .thenReturn(Collections.singletonList(actionRow("x1", "created", null)));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.SETDOWN))
                .thenReturn(Collections.singletonList(actionRow("x2", "created", null)));
        when(gate.isCancelled(dc)).thenReturn(false);
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenReturn(Status.SUCCESS);

        walker.walkCircuit(dc, env, ctx, null);

        verify(rows, never()).reArmSegment(any(), any(), any(), anyBoolean(), any());
    }

    /** 不命中(零重复执行口径): 三段全终态 -> 不复位(容器未落库的重驱动仍幂等跳过)。 */
    @Test
    public void pairedReArmSkippedWhenNothingWillRerun() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        node.getDef().setSetup(Collections.singletonList(actionDef("p1")));
        node.getDef().setActions(Collections.singletonList(actionDef("m1")));
        node.getDef().setSetdown(Collections.singletonList(actionDef("r1")));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.SETUP))
                .thenReturn(Collections.singletonList(actionRow("x0", "success", null)));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.MAIN))
                .thenReturn(Collections.singletonList(actionRow("x1", "success", null)));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.SETDOWN))
                .thenReturn(Collections.singletonList(actionRow("x2", "success", null)));
        when(gate.isCancelled(dc)).thenReturn(false);

        walker.walkCircuit(dc, env, ctx, null);

        verify(rows, never()).reArmSegment(any(), any(), any(), anyBoolean(), any());
    }

    /** repeatSegments=true: 每趟驱动无条件复位段内全部终态行(allTerminal=true)。 */
    @Test
    public void repeatSegmentsReArmsUnconditionally() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        node.getDef().setRepeatSegments("true");
        node.getDef().setSetup(Collections.singletonList(actionDef("p1")));
        node.getDef().setActions(Collections.singletonList(actionDef("m1")));
        node.getDef().setSetdown(Collections.singletonList(actionDef("r1")));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.SETUP))
                .thenReturn(Collections.singletonList(actionRow("x0", "success", null)));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.MAIN))
                .thenReturn(Collections.singletonList(actionRow("x1", "success", null)));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.SETDOWN))
                .thenReturn(Collections.singletonList(actionRow("x2", "success", null)));
        when(gate.isCancelled(dc)).thenReturn(false);

        walker.walkCircuit(dc, env, ctx, null);

        verify(rows).reArmSegment(eq(node.getNodeInstId()), eq(null), eq(ActionSegment.SETUP), eq(true), any());
        verify(rows).reArmSegment(eq(node.getNodeInstId()), eq(null), eq(ActionSegment.SETDOWN), eq(true), any());
    }

    // ===================== v1.2: action 级段 =====================

    /** action 级段顺序: 级 setup 段 -> 主体 -> 级 setdown 段; 三段 exit 聚合即本项结果。 */
    @Test
    public void actionLevelSegmentsRunInOrder() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        ActionDef m1 = actionDef("m1");
        m1.setSetup(Collections.singletonList(actionDef("w1")));
        m1.setSetdown(Collections.singletonList(actionDef("w2")));
        node.getDef().setActions(Collections.singletonList(m1));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.MAIN))
                .thenReturn(Collections.singletonList(actionRow("x0", "created", null)));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.ACTION_SETUP))
                .thenReturn(Collections.singletonList(segmentRow("wx0", "w1", "created")));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.ACTION_SETDOWN))
                .thenReturn(Collections.singletonList(segmentRow("wx1", "w2", "created")));
        when(gate.isCancelled(dc)).thenReturn(false);
        final List<String> ran = new ArrayList<>();
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenAnswer(inv -> {
                    ran.add(((ActionDef) inv.getArgument(3)).getId());
                    return Status.SUCCESS;
                });

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals(Status.SUCCESS, agg);
        Assert.assertEquals("级 setup -> 主体 -> 级 setdown", Arrays.asList("w1", "m1", "w2"), ran);
    }

    /** 主体失败: 级 setdown 段照跑(finally), 电路聚合 FAILED。 */
    @Test
    public void actionSetdownRunsAfterSubjectFailure() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        ActionDef m1 = actionDef("m1");
        m1.setSetup(Collections.singletonList(actionDef("w1")));
        m1.setSetdown(Collections.singletonList(actionDef("w2")));
        node.getDef().setActions(Collections.singletonList(m1));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.MAIN))
                .thenReturn(Collections.singletonList(actionRow("x0", "created", null)));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.ACTION_SETUP))
                .thenReturn(Collections.singletonList(segmentRow("wx0", "w1", "created")));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.ACTION_SETDOWN))
                .thenReturn(Collections.singletonList(segmentRow("wx1", "w2", "created")));
        when(gate.isCancelled(dc)).thenReturn(false);
        final List<String> ran = new ArrayList<>();
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenAnswer(inv -> {
                    String id = ((ActionDef) inv.getArgument(3)).getId();
                    ran.add(id);
                    return "m1".equals(id) ? Status.FAILED : Status.SUCCESS;
                });

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals(Status.FAILED, agg);
        Assert.assertEquals("主体失败: 级 setdown 段照跑", Arrays.asList("w1", "m1", "w2"), ran);
    }

    /** 级 setup 段坏: 主体跳过(drain 留痕), 级 setdown 段照跑(镜像段间闸门)。 */
    @Test
    public void actionSetupFailureSkipsSubjectButRestores() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        ActionDef m1 = actionDef("m1");
        m1.setSetup(Collections.singletonList(actionDef("w1")));
        m1.setSetdown(Collections.singletonList(actionDef("w2")));
        node.getDef().setActions(Collections.singletonList(m1));
        final ActionInstEntity subject = actionRow("x0", "created", null);
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.MAIN))
                .thenReturn(Collections.singletonList(subject));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.ACTION_SETUP))
                .thenReturn(Collections.singletonList(segmentRow("wx0", "w1", "created")));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.ACTION_SETDOWN))
                .thenReturn(Collections.singletonList(segmentRow("wx1", "w2", "created")));
        when(gate.isCancelled(dc)).thenReturn(false);
        final List<String> ran = new ArrayList<>();
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenAnswer(inv -> {
                    String id = ((ActionDef) inv.getArgument(3)).getId();
                    ran.add(id);
                    return "w1".equals(id) ? Status.FAILED : Status.SUCCESS;
                });

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals(Status.FAILED, agg);
        Assert.assertEquals("级 setup 已跑(挂) -> 主体跳过 -> 级 setdown 照跑", Arrays.asList("w1", "w2"), ran);
        verify(actions).markActionDrained(eq(subject), eq("stopped"), contains("\"DRAIN\""), any());
    }

    /** 定义驱动入口(补跑): 主体与级 setup 已终态, 级 setdown 未完成 -> 只补级 setdown 段。 */
    @Test
    public void actionCircuitEntryCompletesOnlyUnfinishedSetdown() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        ActionDef m1 = actionDef("m1");
        m1.setSetup(Collections.singletonList(actionDef("w1")));
        m1.setSetdown(Collections.singletonList(actionDef("w2")));
        node.getDef().setActions(Collections.singletonList(m1));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.MAIN))
                .thenReturn(Collections.singletonList(actionRow("x0", "success", null)));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.ACTION_SETUP))
                .thenReturn(Collections.singletonList(segmentRow("wx0", "w1", "success")));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.ACTION_SETDOWN))
                .thenReturn(Collections.singletonList(segmentRow("wx1", "w2", "created")));
        when(gate.isCancelled(dc)).thenReturn(false);
        final List<String> ran = new ArrayList<>();
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenAnswer(inv -> {
                    ran.add(((ActionDef) inv.getArgument(3)).getId());
                    return Status.SUCCESS;
                });

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals(Status.SUCCESS, agg);
        Assert.assertEquals("只补级 setdown 段", Collections.singletonList("w2"), ran);
    }

    /** 级段重臂: 主体非终态 + 级 setdown 有 success -> 成对复位(按本级段成员行, 不整段)。 */
    @Test
    public void actionSegmentReArmResetsPairedRows() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"a\"\nnodes:\n  a:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity node = row("n", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), node);
        ActionDef m1 = actionDef("m1");
        m1.setSetup(Collections.singletonList(actionDef("w1")));
        m1.setSetdown(Collections.singletonList(actionDef("w2")));
        node.getDef().setActions(Collections.singletonList(m1));
        List<ActionInstEntity> wSetup = Collections.singletonList(segmentRow("wx0", "w1", "success"));
        List<ActionInstEntity> wSetdown = Collections.singletonList(segmentRow("wx1", "w2", "success"));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.MAIN))
                .thenReturn(Collections.singletonList(actionRow("x0", "created", null)));
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.ACTION_SETUP)).thenReturn(wSetup);
        when(rows.findInContainer(node.getNodeInstId(), null, ActionSegment.ACTION_SETDOWN)).thenReturn(wSetdown);
        when(gate.isCancelled(dc)).thenReturn(false);
        final List<String> ran = new ArrayList<>();
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenAnswer(inv -> {
                    ran.add(((ActionDef) inv.getArgument(3)).getId());
                    return Status.SUCCESS;
                });

        Status agg = walker.walkCircuit(dc, env, ctx, null);

        Assert.assertEquals(Status.SUCCESS, agg);
        verify(rows).reArmRows(eq(wSetup), eq(false), any());
        verify(rows).reArmRows(eq(wSetdown), eq(false), any());
        Assert.assertEquals("模拟行未真复位: 级 setup/级 setdown 按旧状态跳过, 主体重跑", Collections.singletonList("m1"), ran);
    }

    // ===================== v1.3: 组回路(GROUP 级段) =====================

    /** 组回路按序: 组 setup -> 子节点逐个 -> 组 setdown; 组行不落终态(收尾归 NodeDriver.finishNode)。 */
    @Test
    public void groupCircuitDrivesSetupChildrenSetdownInOrder() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"g\"\nnodes:\n  g:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity group = row("g", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), group);
        ActionDef p1 = actionDef("p1");
        ActionDef r1 = actionDef("r1");
        group.getDef().setSetup(Collections.singletonList(p1));
        group.getDef().setSetdown(Collections.singletonList(r1));
        List<DslParser.Expr> items = Arrays.asList(new DslParser.Leaf("x"), new DslParser.Leaf("y"));
        when(rows.findInContainer(eq(group.getNodeInstId()), any(), any()))
                .thenReturn(Collections.<ActionInstEntity>emptyList());
        when(nodeMapper.findChildren(dc.procInstId(), group.getNodeInstId()))
                .thenReturn(Collections.<NodeInstEntity>emptyList());
        when(children.childAt(eq(dc), eq(group), eq(items), anyInt(), anyBoolean()))
                .thenAnswer(inv -> row("c-" + inv.getArgument(3), null));
        when(rows.ensureActionRow(eq(dc), eq(group), any(), any(ActionDef.class), any(), any()))
                .thenAnswer(inv -> actionRow("x-" + ((ActionDef) inv.getArgument(3)).getId(), "created", null));
        when(gate.isCancelled(dc)).thenReturn(false);
        final List<String> ran = new ArrayList<>();
        when(nodes.driveNode(eq(dc), any(NodeInstEntity.class), eq(ctx)))
                .thenAnswer(inv -> {
                    ran.add(((NodeInstEntity) inv.getArgument(1)).getNodeInstId());
                    return Status.SUCCESS;
                });
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenAnswer(inv -> {
                    ran.add(((ActionDef) inv.getArgument(3)).getId());
                    return Status.SUCCESS;
                });

        Status agg = walker.walkGroupCircuit(dc, env, ctx, items);

        Assert.assertEquals(Status.SUCCESS, agg);
        Assert.assertEquals("组 setup 包住整段子节点序列, 组 setdown 收尾",
                Arrays.asList("p1", "c-0", "c-1", "r1"), ran);
        verify(rows).ensureActionRow(eq(dc), eq(group), any(), eq(p1), any(), eq(ActionSegment.SETUP));
        verify(rows).ensureActionRow(eq(dc), eq(group), any(), eq(r1), any(), eq(ActionSegment.SETDOWN));
        verify(nodeMapper, never()).setStatus(anyString(), anyString(), any());   // 组行收尾归 NodeDriver.finishNode
    }

    /** setup 段坏: 子节点序列整体跳过且不递归 drain, 组 setdown 照跑; 聚合 FAILED。 */
    @Test
    public void groupCircuitSetupFailureSkipsChildrenButRunsSetdown() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"g\"\nnodes:\n  g:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity group = row("g", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), group);
        group.getDef().setSetup(Collections.singletonList(actionDef("p1")));
        group.getDef().setSetdown(Collections.singletonList(actionDef("r1")));
        List<DslParser.Expr> items = Arrays.asList(new DslParser.Leaf("x"), new DslParser.Leaf("y"));
        when(rows.findInContainer(eq(group.getNodeInstId()), any(), any()))
                .thenReturn(Collections.<ActionInstEntity>emptyList());
        when(nodeMapper.findChildren(dc.procInstId(), group.getNodeInstId()))
                .thenReturn(Collections.<NodeInstEntity>emptyList());
        when(rows.ensureActionRow(eq(dc), eq(group), any(), any(ActionDef.class), any(), any()))
                .thenAnswer(inv -> actionRow("x-" + ((ActionDef) inv.getArgument(3)).getId(), "created", null));
        when(gate.isCancelled(dc)).thenReturn(false);
        final List<String> ran = new ArrayList<>();
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenAnswer(inv -> {
                    String id = ((ActionDef) inv.getArgument(3)).getId();
                    ran.add(id);
                    return "p1".equals(id) ? Status.FAILED : Status.SUCCESS;
                });

        Status agg = walker.walkGroupCircuit(dc, env, ctx, items);

        Assert.assertEquals(Status.FAILED, agg);
        Assert.assertEquals("setup 已跑(挂) -> 子节点跳过 -> setdown 照跑", Arrays.asList("p1", "r1"), ran);
        verify(children, never()).childAt(any(DriveContext.class), any(NodeInstEntity.class),
                anyList(), anyInt(), anyBoolean());
        verify(actions, never()).markActionDrained(any(), any(), any(), any());   // 组主体不递归 drain
        verify(nodeMapper, never()).setStatus(anyString(), anyString(), any());
    }

    /** 子节点坏: 组内序列按组行策略收敛(FAIL_FAST 停), 组 setdown 照跑(finally), 聚合 FAILED。 */
    @Test
    public void groupCircuitChildFailureStillRunsSetdown() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"g\"\nnodes:\n  g:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity group = row("g", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), group);
        group.getDef().setSetup(Collections.singletonList(actionDef("p1")));
        group.getDef().setSetdown(Collections.singletonList(actionDef("r1")));
        List<DslParser.Expr> items = Arrays.asList(new DslParser.Leaf("x"), new DslParser.Leaf("y"));
        when(rows.findInContainer(eq(group.getNodeInstId()), any(), any()))
                .thenReturn(Collections.<ActionInstEntity>emptyList());
        when(nodeMapper.findChildren(dc.procInstId(), group.getNodeInstId()))
                .thenReturn(Collections.<NodeInstEntity>emptyList());
        when(children.childAt(eq(dc), eq(group), eq(items), anyInt(), anyBoolean()))
                .thenAnswer(inv -> row("c-" + inv.getArgument(3), null));
        when(rows.ensureActionRow(eq(dc), eq(group), any(), any(ActionDef.class), any(), any()))
                .thenAnswer(inv -> actionRow("x-" + ((ActionDef) inv.getArgument(3)).getId(), "created", null));
        when(gate.isCancelled(dc)).thenReturn(false);
        final List<String> ran = new ArrayList<>();
        when(nodes.driveNode(eq(dc), any(NodeInstEntity.class), eq(ctx))).thenReturn(Status.FAILED);
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenAnswer(inv -> {
                    ran.add(((ActionDef) inv.getArgument(3)).getId());
                    return Status.SUCCESS;
                });

        Status agg = walker.walkGroupCircuit(dc, env, ctx, items);

        Assert.assertEquals(Status.FAILED, agg);
        Assert.assertEquals("首个子节点坏: 后续子节点不驱动, setdown 照跑", Arrays.asList("p1", "r1"), ran);
        verify(children, never()).childAt(eq(dc), eq(group), eq(items), eq(1), anyBoolean());
        verify(nodeMapper, never()).setStatus(anyString(), anyString(), any());
    }

    /** 子节点让渡: 立即退出且不 drain、组 setdown 不跑、组行不落终态, 让渡态原值上传。 */
    @Test
    public void groupChildYieldSkipsSetdownWithoutSettle() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"g\"\nnodes:\n  g:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity group = row("g", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), group);
        ActionDef r1 = actionDef("r1");
        group.getDef().setSetup(Collections.singletonList(actionDef("p1")));
        group.getDef().setSetdown(Collections.singletonList(r1));
        List<DslParser.Expr> items = Arrays.asList(new DslParser.Leaf("x"), new DslParser.Leaf("y"));
        when(rows.findInContainer(eq(group.getNodeInstId()), any(), any()))
                .thenReturn(Collections.<ActionInstEntity>emptyList());
        when(nodeMapper.findChildren(dc.procInstId(), group.getNodeInstId()))
                .thenReturn(Collections.<NodeInstEntity>emptyList());
        when(children.childAt(eq(dc), eq(group), eq(items), anyInt(), anyBoolean()))
                .thenAnswer(inv -> row("c-" + inv.getArgument(3), null));
        when(rows.ensureActionRow(eq(dc), eq(group), any(), any(ActionDef.class), any(), any()))
                .thenAnswer(inv -> actionRow("x-" + ((ActionDef) inv.getArgument(3)).getId(), "created", null));
        when(gate.isCancelled(dc)).thenReturn(false);
        when(nodes.driveNode(eq(dc), any(NodeInstEntity.class), eq(ctx))).thenReturn(Status.PENDING);
        when(actions.runAction(eq(dc), any(Context.class), any(ActionInstEntity.class), any(ActionDef.class)))
                .thenReturn(Status.SUCCESS);

        Status agg = walker.walkGroupCircuit(dc, env, ctx, items);

        Assert.assertEquals("让渡态原值上传", Status.PENDING, agg);
        verify(children, never()).childAt(eq(dc), eq(group), eq(items), eq(1), anyBoolean());   // 后续子项不跑
        verify(rows, never()).ensureActionRow(eq(dc), eq(group), any(), eq(r1), any(), any());  // setdown 行都不补建
        verify(actions, never()).markActionDrained(any(), any(), any(), any());
        verify(nodeMapper, never()).setStatus(anyString(), anyString(), any());
    }

    /** 无段组: 零开销回落普通子节点串行走查, 段行一次都不查、重臂判据也不跑。 */
    @Test
    public void groupCircuitWithoutSegmentsFallsBackToChildrenWalk() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"g\"\nnodes:\n  g:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity group = row("g", "fail_fast");   // 空 NodeDef: 无段
        NodeContext ctx = new NodeContext(dc.processContext(), group);
        List<DslParser.Expr> items = Collections.<DslParser.Expr>singletonList(new DslParser.Leaf("x"));
        when(children.childAt(eq(dc), eq(group), eq(items), eq(0), anyBoolean())).thenReturn(row("c-0", null));
        when(gate.isCancelled(dc)).thenReturn(false);
        when(nodes.driveNode(eq(dc), any(NodeInstEntity.class), eq(ctx))).thenReturn(Status.SUCCESS);

        Status agg = walker.walkGroupCircuit(dc, env, ctx, items);

        Assert.assertEquals(Status.SUCCESS, agg);
        verify(rows, never()).findInContainer(anyString(), any(), any());           // 段行一次都不查
        verify(rows, never()).reArmSegment(any(), any(), any(), anyBoolean(), any());
        verify(nodeMapper, never()).findChildren(anyString(), anyString());         // 重臂判据也不跑
    }

    /** 组级成对重臂: 子行有非终态(重试复位过) + setdown 已 success -> 两段 success 行成对复位。 */
    @Test
    public void groupReArmFiresWhenChildPendingAndSetdownTorn() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"g\"\nnodes:\n  g:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity group = row("g", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), group);
        group.getDef().setSetup(Collections.singletonList(actionDef("p1")));
        group.getDef().setSetdown(Collections.singletonList(actionDef("r1")));
        List<DslParser.Expr> items = Arrays.asList(new DslParser.Leaf("x"), new DslParser.Leaf("y"));
        when(rows.findInContainer(group.getNodeInstId(), null, ActionSegment.SETUP))
                .thenReturn(Collections.singletonList(actionRow("s0", "success", null)));
        when(rows.findInContainer(group.getNodeInstId(), null, ActionSegment.SETDOWN))
                .thenReturn(Collections.singletonList(actionRow("d0", "success", null)));
        when(nodeMapper.findChildren(dc.procInstId(), group.getNodeInstId()))
                .thenReturn(Arrays.asList(row("c-0", null), row("c-1", null)));   // 子行未跑(status 空)
        when(children.childAt(eq(dc), eq(group), eq(items), anyInt(), anyBoolean()))
                .thenAnswer(inv -> row("c-" + inv.getArgument(3), null));
        when(gate.isCancelled(dc)).thenReturn(false);
        when(nodes.driveNode(eq(dc), any(NodeInstEntity.class), eq(ctx))).thenReturn(Status.SUCCESS);

        walker.walkGroupCircuit(dc, env, ctx, items);

        verify(rows).reArmSegment(eq(group.getNodeInstId()), eq(null), eq(ActionSegment.SETUP), eq(false), any());
        verify(rows).reArmSegment(eq(group.getNodeInstId()), eq(null), eq(ActionSegment.SETDOWN), eq(false), any());
    }

    /** 缺行判据: 子行物化不全(LAZY 未走到)仍算主体待跑 -> 成对复位重新准备现场。 */
    @Test
    public void groupReArmFiresOnMissingChildRows() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"g\"\nnodes:\n  g:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity group = row("g", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), group);
        group.getDef().setSetup(Collections.singletonList(actionDef("p1")));
        group.getDef().setSetdown(Collections.singletonList(actionDef("r1")));
        List<DslParser.Expr> items = Arrays.asList(new DslParser.Leaf("x"), new DslParser.Leaf("y"));
        NodeInstEntity only = row("c-0", null);
        only.setStatus(Status.SUCCESS.getStatus());
        when(rows.findInContainer(group.getNodeInstId(), null, ActionSegment.SETUP))
                .thenReturn(Collections.singletonList(actionRow("s0", "success", null)));
        when(rows.findInContainer(group.getNodeInstId(), null, ActionSegment.SETDOWN))
                .thenReturn(Collections.singletonList(actionRow("d0", "success", null)));
        when(nodeMapper.findChildren(dc.procInstId(), group.getNodeInstId()))
                .thenReturn(Collections.singletonList(only));   // 只物化了一条(< 2)
        when(children.childAt(eq(dc), eq(group), eq(items), anyInt(), anyBoolean()))
                .thenAnswer(inv -> row("c-" + inv.getArgument(3), null));
        when(gate.isCancelled(dc)).thenReturn(false);
        when(nodes.driveNode(eq(dc), any(NodeInstEntity.class), eq(ctx))).thenReturn(Status.SUCCESS);

        walker.walkGroupCircuit(dc, env, ctx, items);

        verify(rows).reArmSegment(eq(group.getNodeInstId()), eq(null), eq(ActionSegment.SETUP), eq(false), any());
        verify(rows).reArmSegment(eq(group.getNodeInstId()), eq(null), eq(ActionSegment.SETDOWN), eq(false), any());
    }

    /** 不命中(只补收尾): 子节点全终态 + 只 setdown 有未完成行 -> 不重臂, setup 一行也不重跑。 */
    @Test
    public void groupReArmSkippedWhenChildrenAllTerminal() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"g\"\nnodes:\n  g:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity group = row("g", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), group);
        group.getDef().setSetup(Collections.singletonList(actionDef("p1")));
        group.getDef().setSetdown(Collections.singletonList(actionDef("r1")));
        List<DslParser.Expr> items = Arrays.asList(new DslParser.Leaf("x"), new DslParser.Leaf("y"));
        NodeInstEntity c0 = row("c-0", null);
        NodeInstEntity c1 = row("c-1", null);
        c0.setStatus(Status.SUCCESS.getStatus());
        c1.setStatus(Status.SUCCESS.getStatus());
        when(rows.findInContainer(group.getNodeInstId(), null, ActionSegment.SETUP))
                .thenReturn(Collections.singletonList(actionRow("s0", "success", null)));
        when(rows.findInContainer(group.getNodeInstId(), null, ActionSegment.SETDOWN))
                .thenReturn(Arrays.asList(actionRow("d0", "success", null), actionRow("d1", "created", null)));
        when(nodeMapper.findChildren(dc.procInstId(), group.getNodeInstId()))
                .thenReturn(Arrays.asList(c0, c1));
        when(children.childAt(eq(dc), eq(group), eq(items), anyInt(), anyBoolean()))
                .thenAnswer(inv -> row("c-" + inv.getArgument(3), null));
        when(gate.isCancelled(dc)).thenReturn(false);
        when(nodes.driveNode(eq(dc), any(NodeInstEntity.class), eq(ctx))).thenReturn(Status.SUCCESS);

        Status agg = walker.walkGroupCircuit(dc, env, ctx, items);

        Assert.assertEquals(Status.SUCCESS, agg);
        verify(rows, never()).reArmSegment(any(), any(), any(), anyBoolean(), any());
        verify(actions, never()).runAction(any(), any(), any(), any());   // 两段都按旧状态跳过
    }

    /** 单边声明不触发重臂、也不查主体(没有"改—还原"契约); 未声明的段一侧零查询。 */
    @Test
    public void groupSingleSidedSegmentsNeverReArm() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"g\"\nnodes:\n  g:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity group = row("g", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), group);
        group.getDef().setSetup(Collections.singletonList(actionDef("p1")));   // 只声明 setup, 无 setdown
        List<DslParser.Expr> items = Collections.<DslParser.Expr>singletonList(new DslParser.Leaf("x"));
        when(rows.findInContainer(group.getNodeInstId(), null, ActionSegment.SETUP))
                .thenReturn(Collections.singletonList(actionRow("s0", "success", null)));
        when(children.childAt(eq(dc), eq(group), eq(items), eq(0), anyBoolean())).thenReturn(row("c-0", null));
        when(gate.isCancelled(dc)).thenReturn(false);
        when(nodes.driveNode(eq(dc), any(NodeInstEntity.class), eq(ctx))).thenReturn(Status.SUCCESS);

        Status agg = walker.walkGroupCircuit(dc, env, ctx, items);

        Assert.assertEquals(Status.SUCCESS, agg);
        verify(rows, never()).reArmSegment(any(), any(), any(), anyBoolean(), any());
        verify(nodeMapper, never()).findChildren(anyString(), anyString());   // 单边不查主体
        verify(rows, never()).findInContainer(anyString(), any(), eq(ActionSegment.SETDOWN));   // 空段零查询
    }

    /** 组级 repeatSegments: 每趟无条件复位两段全部终态行, 且不查主体(开关语义不依赖子节点)。 */
    @Test
    public void groupRepeatSegmentsReArmsUnconditionally() {
        ProcessDefinition def = new DefinitionLoader().load("flow: \"g\"\nnodes:\n  g:\n    handler: h\n");
        DriveContext dc = dc(def);
        NodeInstEntity group = row("g", "fail_fast");
        NodeContext ctx = new NodeContext(dc.processContext(), group);
        group.getDef().setRepeatSegments("true");
        group.getDef().setSetup(Collections.singletonList(actionDef("p1")));
        group.getDef().setSetdown(Collections.singletonList(actionDef("r1")));
        List<DslParser.Expr> items = Collections.<DslParser.Expr>singletonList(new DslParser.Leaf("x"));
        when(rows.findInContainer(group.getNodeInstId(), null, ActionSegment.SETUP))
                .thenReturn(Collections.singletonList(actionRow("s0", "success", null)));
        when(rows.findInContainer(group.getNodeInstId(), null, ActionSegment.SETDOWN))
                .thenReturn(Collections.singletonList(actionRow("d0", "success", null)));
        when(children.childAt(eq(dc), eq(group), eq(items), eq(0), anyBoolean())).thenReturn(row("c-0", null));
        when(gate.isCancelled(dc)).thenReturn(false);
        when(nodes.driveNode(eq(dc), any(NodeInstEntity.class), eq(ctx))).thenReturn(Status.SUCCESS);

        walker.walkGroupCircuit(dc, env, ctx, items);

        verify(rows).reArmSegment(eq(group.getNodeInstId()), eq(null), eq(ActionSegment.SETUP), eq(true), any());
        verify(rows).reArmSegment(eq(group.getNodeInstId()), eq(null), eq(ActionSegment.SETDOWN), eq(true), any());
        verify(nodeMapper, never()).findChildren(anyString(), anyString());   // 不查主体
    }
}
