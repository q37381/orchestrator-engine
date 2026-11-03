package com.tt.ii.process.core.engine;

import com.tt.ii.process.exception.ProcessException;
import com.tt.ii.process.core.context.NodeContext;
import com.tt.ii.process.core.define.ActionDef;
import com.tt.ii.process.core.define.DslParser;
import com.tt.ii.process.core.define.NodeDef;
import com.tt.ii.process.core.model.ActionSegment;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.core.runtime.ContextFactory;
import com.tt.ii.process.domain.LaneEntity;
import com.tt.ii.process.domain.NodeInstEntity;
import com.tt.ii.process.mapper.LaneMapper;
import com.tt.ii.process.mapper.NodeInstMapper;

import java.util.Collections;
import java.util.List;

/**
 * 三段编排(setup -> main -> setdown)的**唯一骨架**, 以及它在两种主体上的落地:
 *  - {@link #node}: 叶子节点动作回路(主体 = MAIN 段, 可逐 lane 一遍), 收尾时落 lane/node 行;
 *  - {@link #group}: 组回路(主体 = 子节点序列 / 组级段子节点切片), 组行收尾归 NodeDriver.finishNode。
 *
 * 前身是 {@code LayerWalker} 里两个 {@code runSectionedCircuit} 重载 + 四个 lambda 组装点: 骨架本身只有一份,
 * 但"setup 怎么跑 / 主体怎么跑 / setup 坏时怎么处置"散在各调用点的闭包里, 排障要先找到拼装处。
 * 现在骨架只认 {@link SectionedCircuit} 的四个具名方法, 每种回路一份具名实现(字段即现场)。
 *
 * 骨架口径(与拆分前逐字一致):
 *  - setup 让渡 -> 主体与收尾段都不跑、容器行不落终态(保持 running), 等重入续跑;
 *  - setup 坏(FAILED/STOPPED) -> 主体整体跳过(处置由 onSetupBad 决定), setdown 照跑(finally);
 *  - 主体让渡 -> 收尾段不跑、容器行不落终态, 等重入续跑;
 *  - 三段 exit 聚合(坏终态优先 > 让渡 > severity)即回路结果。
 */
final class CircuitWalk {

    private final ActionRows actionRows;
    private final ContextFactory ctxFactory;
    private final NodeInstMapper nodeMapper;
    private final LaneMapper laneMapper;
    private final WalkLoop loops;
    private final SegmentRearmer rearm;

    CircuitWalk(ActionRows actionRows, ContextFactory ctxFactory, NodeInstMapper nodeMapper,
                LaneMapper laneMapper, WalkLoop loops, SegmentRearmer rearm) {
        this.actionRows = actionRows;
        this.ctxFactory = ctxFactory;
        this.nodeMapper = nodeMapper;
        this.laneMapper = laneMapper;
        this.loops = loops;
        this.rearm = rearm;
    }

    /**
     * 节点回路(三段编排, {@code lane} 可为 null): 每 lane 一个完整回路, 或非分片节点自身一个回路。
     *
     * 段重臂(v1.1): 现场已被 setdown 撤除而本趟又要重跑段时, 让 setup/setdown 成对重跑
     * (或 repeatSegments 每趟重跑); 收尾轮不做重臂(复位 setup 行会让已成功的现场看起来像"从没施加过")。
     *
     * 不变式 I1: 容器行状态收尾**只在 setdown 段走完之后**落(值 = 三段聚合)——
     * 主段结束就落终态会让"主段成功后、setdown 前崩溃"的重驱动被幂等跳过, 收尾段永远不再执行。
     * lane 写 lane 行; 非分片写 node 行(随后 NodeDriver.finishNode 同值覆盖)。
     * 让渡(非终态返回): lane 行不落终态, 但 lane 级 result 照写(重入不丢本轮显式放置的交接值)。
     */
    Status node(DriveContext dc, NodeEnv env, NodeContext ctx, LaneEntity lane) {
        NodeInstEntity node = ctx.inst();
        NodeDef nd = node.getDef();   // 对齐期已按 expr.nodeRef 一次取定, 不再回查定义
        if (nd == null) {
            throw new ProcessException("AU204307", node.getNodeInstId());
        }
        String laneId = lane != null ? lane.getLaneId() : null;
        if (!dc.isSettle()) {
            rearm.reArmLeaf(ctx, laneId, nd);
        }

        NodeSegments segments = segments(dc, env, ctx, lane);
        Status agg = run(new NodeCircuit(segments, nd, dc.isSettle(), env.cleanup()));
        if (lane != null) {
            // lane 行状态与 lane 级 result 同一条 UPDATE: 分片内显式放置的交接值不再随段重建丢失
            String result = segments.laneResultJson();
            if (agg.isTerminal()) {
                laneMapper.updateStatusResult(lane.getLaneId(), agg.getStatus(), result, ctx.userId());
            } else {
                // 让渡: lane 行不落终态(不写 RUNNING, 见 FR-STA-03), 但本轮显式放置的 result 照写——
                // 重入时上下文从行重建, 不刷这一段就"只在内存有效, 重驱动即丢"(同 NodeDriver.finishNode 口径)
                laneMapper.updateResult(lane.getLaneId(), result, ctx.userId());
            }
        }
        // 非分片节点行不在这里写: 唯一写入口是 NodeDriver.finishNode(status + result 同一条 UPDATE)
        return agg;
    }

    /**
     * 组回路(v1.3/v1.4, 三段编排): group.setup -> 组内子节点序列(串行档) -> group.setdown(finally)。
     *
     *  - 无段组(含匿名手写 GROUP 无声明): 直接回落 {@link ChildrenWalk}, 不查段行、零额外开销;
     *  - 组行状态收尾**不在这里**落: 三段聚合原样返回, 由 NodeDriver.finishNode 一次写状态 + result 再发 after;
     *  - setup 段坏: 子节点序列整体跳过, **不递归 drain**(子节点"走查到才建", 手里没有剩余行;
     *    已物化未跑的子树留在原状交给重试), setdown 照跑;
     *  - 组级节点段(v1.4): 段 = 叶子子节点引用, 与主体同一批子行按 index 对齐, 分界由同键前缀/后缀切出。
     */
    Status group(DriveContext dc, NodeEnv env, NodeContext ctx, List<DslParser.Expr> items) {
        NodeInstEntity node = ctx.inst();
        NodeDef nd = node.getDef();   // 匿名手写 GROUP 无声明 -> null(退化成普通串行容器, 无段可跑)
        List<String> setupRefs = nd == null ? Collections.<String>emptyList() : WalkSupport.orEmpty(nd.getSetupRefs());
        List<String> setdownRefs = nd == null ? Collections.<String>emptyList() : WalkSupport.orEmpty(nd.getSetdownRefs());
        if (!setupRefs.isEmpty() || !setdownRefs.isEmpty()) {
            return groupNodeSegments(dc, env, ctx, nd, items, setupRefs, setdownRefs);
        }
        List<ActionDef> setupDefs = nd == null ? Collections.<ActionDef>emptyList() : WalkSupport.orEmpty(nd.getSetup());
        List<ActionDef> setdownDefs = nd == null ? Collections.<ActionDef>emptyList() : WalkSupport.orEmpty(nd.getSetdown());
        if (setupDefs.isEmpty() && setdownDefs.isEmpty()) {
            return new ChildrenWalk(dc, env, ctx, items, loops).walk(WalkMode.SERIAL);   // 无段组: 零开销快路径
        }
        if (!dc.isSettle()) {
            rearm.reArmGroup(dc, ctx, nd, setupDefs, setdownDefs, items.size());
        }
        return run(new GroupInlineCircuit(dc, env, ctx, items,
                segments(dc, env, ctx, null), setupDefs, setdownDefs));
    }

    /**
     * 组级段=叶子子节点引用(v1.4)的组回路: 段节点与主体是**同一批子行**(与子节点走查同 index 对齐物化),
     * 分界由 nd.setupRefs/setdownRefs 与子项头尾的**同键前缀/后缀**切出——展开期段节点已被注入 Group 子项头尾,
     * 键序天然吻合; 手写 GROUP:名(...) 形状不符时切不出段(前缀=0), 整段退化按主体驱动, 不误伤。
     *
     * 运行语义与内联段逐字一致: 段坏 -> 主体整体跳过(子节点"走查到才建", 无剩余行可 drain)、setdown 照跑;
     * 让渡 -> 收尾不跑、组行不落终态(重入续跑); 重臂把"段子节点行 + 其 MAIN 动作行(含 per-lane)"成对复位。
     */
    private Status groupNodeSegments(DriveContext dc, NodeEnv env, NodeContext ctx, NodeDef nd,
                                     List<DslParser.Expr> items, List<String> setupRefs,
                                     List<String> setdownRefs) {
        int setupEnd = setupPrefixLen(items, setupRefs);
        int tailLen = setdownSuffixLen(items, setdownRefs);
        int setdownStart = Math.max(setupEnd, items.size() - tailLen);   // max: 防御切片重叠
        if (!dc.isSettle()) {
            rearm.reArmGroupNodeSegments(dc, ctx, nd, setupRefs, setdownRefs,
                    setupEnd, setdownStart, items.size());
        }
        return run(new GroupNodeSegmentsCircuit(dc, env, ctx, items, setupEnd, setdownStart));
    }

    /**
     * 三段编排核心骨架(唯一一份): 段执行、段间闸门、让渡短路与三段 exit 聚合口径全在这里,
     * 与拆分前逐字一致——三段怎么跑由 {@link SectionedCircuit} 的具名实现注入。
     */
    static Status run(SectionedCircuit circuit) {
        // 段 1/3: setup(主体之前; 段内按行冻结策略收敛、查取消闸门; 恒串行)
        Status setupAgg = circuit.setup();
        if (!setupAgg.isTerminal()) {
            return setupAgg;   // 让渡: 主体与收尾段都不跑、容器行不落终态(保持 running), 等重入续跑
        }

        // 段间闸门: setup 段聚合坏 -> 主体整体跳过(处置由 onSetupBad 决定), setdown 照跑
        Status mainAgg = null;
        if (setupAgg == Status.FAILED || setupAgg == Status.STOPPED) {
            circuit.onSetupBad();
        } else {
            mainAgg = circuit.main();
            if (!mainAgg.isTerminal()) {
                return mainAgg;   // 让渡: 收尾段不跑、容器行不落终态, 等重入续跑(重入时非终态行重跑)
            }
        }

        // 段 3/3: setdown(finally: 段内恒不收敛、不查取消闸门、恒串行)
        Status setdownAgg = circuit.setdown();

        // 三段 exit 聚合: 坏终态优先 > 让渡 > severity; setdown 段自身让渡同样按让渡退出(容器行不落终态)
        return Status.exitAwareAggregate(Status.exitAwareAggregate(setupAgg, mainAgg), setdownAgg);
    }

    private NodeSegments segments(DriveContext dc, NodeEnv env, NodeContext ctx, LaneEntity lane) {
        return new NodeSegments(dc, env, ctx, lane, actionRows, ctxFactory, loops);
    }

    /** 段前缀长度: items 开头与 setupRefs 逐项同键的项数(展开注入保证吻合; 手写形状不符 = 0, 退化按主体走)。 */
    private static int setupPrefixLen(List<DslParser.Expr> items, List<String> refs) {
        int n = 0;
        while (n < refs.size() && n < items.size() && refs.get(n).equals(items.get(n).defRef())) {
            n++;
        }
        return n;
    }

    /** 段后缀长度: items 末尾与 setdownRefs 逆序逐项同键的项数(同 setupPrefixLen 口径)。 */
    private static int setdownSuffixLen(List<DslParser.Expr> items, List<String> refs) {
        int n = 0;
        while (n < refs.size() && n < items.size()
                && refs.get(refs.size() - 1 - n).equals(items.get(items.size() - 1 - n).defRef())) {
            n++;
        }
        return n;
    }

    /**
     * 三段电路(层差异的唯一入口): setup 怎么跑、主体怎么跑、setdown 怎么跑、setup 坏时怎么处置。
     * 默认四件事 = 无行为; 各回路只覆盖自己关心的那几个。
     */
    interface SectionedCircuit {

        /** 段 1/3: setup(主体之前)。 */
        Status setup();

        /** 段 2/3: 主体(setup 成功才跑)。 */
        Status main();

        /** 段 3/3: setdown(finally, 恒执行)。 */
        Status setdown();

        /** setup 段坏时的处置(默认什么都不做)。 */
        default void onSetupBad() {
        }
    }

    /** 叶子节点回路: setup 段 -> MAIN 段 -> setdown 段; setup 坏时主段已物化行 drain 留痕。 */
    private final class NodeCircuit implements SectionedCircuit {
        private final NodeSegments segments;
        private final NodeDef nd;
        private final boolean settle;
        private final boolean cleanup;

        NodeCircuit(NodeSegments segments, NodeDef nd, boolean settle, boolean cleanup) {
            this.segments = segments;
            this.nd = nd;
            this.settle = settle;
            this.cleanup = cleanup;
        }

        @Override
        public Status setup() {
            return segments.walkSetup(WalkSupport.orEmpty(nd.getSetup()));   // 收尾轮自动切行判定口径
        }

        @Override
        public Status main() {
            // 收尾轮不推进主链: 并行档的 fanout 不会被串行闸门拦下, 必须从结构上禁止 runAction。
            // cleanup 例外: setdown 切片节点的主段就是清理动作本身, 必须完整执行。
            return settle && !cleanup
                    ? segments.settleMain(nd.effectiveActions())
                    : segments.walk(ActionSegment.MAIN, nd.effectiveActions(), false);
        }

        @Override
        public void onSetupBad() {
            segments.drain(ActionSegment.MAIN);   // setup 段坏: 主段已物化未跑行 drain 留痕
        }

        @Override
        public Status setdown() {
            return segments.walk(ActionSegment.SETDOWN, WalkSupport.orEmpty(nd.getSetdown()), true);
        }
    }

    /** 组内联段回路: 组 setup 段 -> 子节点序列(串行) -> 组 setdown 段; 主体是子节点序列, 不递归 drain。 */
    private final class GroupInlineCircuit implements SectionedCircuit {
        private final NodeSegments segments;
        private final List<ActionDef> setupDefs;
        private final List<ActionDef> setdownDefs;
        private final ChildrenWalk childWalk;

        GroupInlineCircuit(DriveContext dc, NodeEnv env, NodeContext ctx, List<DslParser.Expr> items,
                           NodeSegments segments, List<ActionDef> setupDefs, List<ActionDef> setdownDefs) {
            this.segments = segments;
            this.setupDefs = setupDefs;
            this.setdownDefs = setdownDefs;
            this.childWalk = new ChildrenWalk(dc, env, ctx, items, loops);
        }

        @Override
        public Status setup() {
            return segments.walkSetup(setupDefs);   // 收尾轮自动切行判定口径
        }

        @Override
        public Status main() {
            return childWalk.walk(WalkMode.SERIAL);
        }

        @Override
        public Status setdown() {
            return segments.walk(ActionSegment.SETDOWN, setdownDefs, true);
        }
    }

    /** 组级节点段回路: 三个切片都是子节点驱动(段节点本身就是子行), setup 坏时主体切片整体跳过。 */
    private final class GroupNodeSegmentsCircuit implements SectionedCircuit {
        private final List<DslParser.Expr> items;
        private final int setupEnd;
        private final int setdownStart;
        private final ChildrenWalk childWalk;

        GroupNodeSegmentsCircuit(DriveContext dc, NodeEnv env, NodeContext ctx,
                                 List<DslParser.Expr> items, int setupEnd, int setdownStart) {
            this.items = items;
            this.setupEnd = setupEnd;
            this.setdownStart = setdownStart;
            this.childWalk = new ChildrenWalk(dc, env, ctx, items, loops);
        }

        @Override
        public Status setup() {
            return slice(0, setupEnd, false);
        }

        @Override
        public Status main() {
            return slice(setupEnd, setdownStart, false);
        }

        @Override
        public Status setdown() {
            return slice(setdownStart, items.size(), true);   // 组 setdown 段节点: 清理动作完整执行
        }

        private Status slice(int from, int to, boolean finallyMode) {
            return childWalk.slice(from, to, finallyMode);
        }
    }
}
