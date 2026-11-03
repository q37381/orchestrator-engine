package com.tt.ii.process.core.engine;

import com.tt.ii.process.exception.ProcessException;
import com.tt.ii.process.core.context.NodeContext;
import com.tt.ii.process.core.define.NodeDef;
import com.tt.ii.process.core.model.ActionSegment;
import com.tt.ii.process.core.model.ErrorPolicy;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.domain.LaneEntity;
import com.tt.ii.process.domain.NodeInstEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 分片 lane 层的走查: 逐 lane 定位(find-or-create)后执行 laneBody。
 *
 * 两档(串/并)与拆分前逐字一致: 并行档先建全 lane(扇出需同时在)、每 lane 提交分片池 join
 * (FAIL_FAST 不取消已跑 lane); 串行档每步查取消闸门、走查到该分片才建 lane 行
 * (未走到的没有行, 无剩余项清理)。
 *
 * 形变(v1.3): 节点声明 segmentsFollowShard: false 时切**共享段**档——节点 setup 一次 -> 各 lane 主体 ->
 * 节点 setdown 一次, 段只物化一份(lane 恒 null), 与主体是否逐 lane 无关; 节点行只在共享 setdown 走完后
 * 落终态(不变式 I1 同款), 期间各 lane 行按各自主体结果独立收尾。
 *
 * 收尾轮(settle): 只处理**在途**的 lane(不按本轮分片键新建, 也不碰已终态/未开工的 lane——
 * 否则已完成的 lane 会被回路重蹈覆辙地把状态改写成 STOPPED); 串/并档不变。
 */
final class LanesWalk {

    private final DriveContext dc;
    private final NodeEnv env;
    private final NodeContext ctx;
    private final List<String> shardKeys;
    private final WalkServices services;

    private final NodeInstEntity node;
    private final ErrorPolicy policy;

    LanesWalk(DriveContext dc, NodeEnv env, NodeContext ctx, List<String> shardKeys, WalkServices services) {
        this.dc = dc;
        this.env = env;
        this.ctx = ctx;
        this.shardKeys = shardKeys;
        this.services = services;
        this.node = ctx.inst();
        this.policy = ErrorPolicy.of(node.getErrorPolicy());   // lane 继承本节点行的生效值
    }

    Status run() {
        NodeDef nd = node.getDef();
        if (nd == null) {
            throw new ProcessException("AU204307", node.getNodeInstId());   // 与 CircuitWalk.node 同口径: 缺对齐定义不当 SUCCESS
        }
        if (!nd.segmentsFollowShardEnabled()) {
            return sharedSegmentLanes(nd);   // v1.3: 段不跟随分片(整节点各一次)
        }
        // 默认口径: 每 lane 一个完整三段回路(段行挂该 lane)
        return laneSequence(new FullCircuitBody());
    }

    /**
     * lane 序列走查(串/并两档, 全仓唯一一份): 逐 lane 定位后执行 laneBody。
     * 每 lane 完整三段回路({@link FullCircuitBody})与共享段模式的 lane 主体({@link MainOnlyBody})共用本循环。
     * 收尾轮只跑在途 lane(不按本轮分片键新建、不查闸门); 两档的差异只在"档"与"lane 从哪来"两处。
     */
    private Status laneSequence(LaneBody body) {
        if (dc.isSettle()) {
            List<LaneEntity> inFlight = settleLanes();
            return runLanes(inFlight.size(), new InFlightLanes(inFlight), body, ErrorPolicy.CONTINUE_ON_ERROR);
        }
        return runLanes(shardKeys.size(), new NewShardLanes(), body, policy);   // 档跟着本层行的声明走
    }

    /** 选档 + 执行: lane 的来处归 {@link LaneLocator}, 串/并档归本节点行声明。 */
    private Status runLanes(int count, LaneLocator locator, LaneBody body, ErrorPolicy stopPolicy) {
        LaneStep step = new LaneStep(locator, body, stopPolicy, !dc.isSettle());
        if (node.getDef().isLaneParallel()) {
            // 并行档: 定位(建行/取在途)在提交线程同步做, 每 lane 提交分片池 join; FAIL_FAST 不取消已跑 lane
            return services.loops.fanout(WalkLoop.Pool.SHARD, dc, ctx, count,
                    step, stopPolicy, "分片 lane 异常", "lane");
        }
        return services.loops.serial(dc, 0, count, step);
    }

    /**
     * 收尾轮的在途 lane: lane 行只落聚合终态(不写 RUNNING, 见 FR-STA-03), 所以不能靠状态识别在途;
     * 判据改成**开工痕迹**: 行非终态且已有任一 action 行(laneAt 之后崩在 setup 前的不算, 没有现场可收)。
     * 不按本轮分片键新建, 也不碰已终态/从未开工的 lane。
     */
    private List<LaneEntity> settleLanes() {
        List<LaneEntity> inFlight = new ArrayList<>();
        for (LaneEntity l : services.laneMapper.findByNode(node.getNodeInstId())) {
            Status st = Status.of(l.getStatus());
            if (st != null && st.isTerminal()) {
                continue;
            }
            if (services.actionRows.hasAnyRow(node.getNodeInstId(), l.getLaneId())) {
                inFlight.add(l);
            }
        }
        return inFlight;
    }

    /**
     * 共享段模式(节点声明 segmentsFollowShard: false): 节点 setup 一次 -> 各 lane 主体 -> 节点 setdown 一次,
     * 段只物化一份(lane 恒 null), 与主体是否逐 lane 无关——这就是"不分片执行"的那一档。
     *
     * 骨架复用 {@link CircuitWalk#run}: setup 段坏 -> 各 lane 已物化 MAIN 行 drain 留痕;
     * 共享 setdown 未跑完前节点行不落终态(不变式 I1 同款), 期间各 lane 行按各自主体结果独立收尾.
     *
     * 空分片短路: 首驱(段行与 lane 行一行都没有)整段跳过——与逐 lane 口径一致("空分片不存在回路, 段不执行");
     * 反之已开过工(哪怕本次分片键解析为空)必须把已施加的现场收尾完, 不能靠空 keys 静默成功。
     */
    private Status sharedSegmentLanes(NodeDef nd) {
        String nodeId = node.getNodeInstId();
        if (shardKeys.isEmpty() && !sharedCircuitStarted(nodeId)) {
            return Status.SUCCESS;   // 首驱空分片: 不存在回路, 段不执行(与逐 lane 口径一致)
        }
        // 段重臂(v1.1): 判据同节点级, 主体待跑的取证换成**跨 lane**; 收尾轮不做重臂
        if (!dc.isSettle()) {
            services.rearm.reArmLeafShared(ctx, nd, shardKeys);
        }
        Status agg = CircuitWalk.run(new SharedCircuit(nd));
        if (!agg.isTerminal()) {
            return agg;   // 让渡: 节点行不落终态, 等重入续跑
        }
        // 不变式 I1 同款: 节点行收尾只在共享 setdown 走完之后;
        // 节点行唯一写入口是 NodeDriver.finishNode(本层只返回聚合值, 不再自己 setStatus)。
        return agg;
    }

    /** 共享段模式的 lane 主体: 只跑本 lane 的 MAIN 段(含 action 级段), 跑完写 lane 行; 节点段在 lane 外层共享执行。 */
    private Status laneMain(LaneEntity lane) {
        NodeSegments segs = segments(lane);
        Status agg = dc.isSettle() && !env.cleanup()
                ? segs.settleMain(node.getDef().effectiveActions())   // 收尾轮不推进主链, 只判定 + drain
                : segs.walk(ActionSegment.MAIN, node.getDef().effectiveActions(), false);
        // lane 行状态与 lane 级 result 同一条 UPDATE; 让渡时状态不落终态(不写 RUNNING), 但 result 照写——
        // 重入时上下文从行重建, 不刷本轮显式放置的交接值就"只在内存有效, 重驱动即丢"(同 CircuitWalk.node 口径)
        String result = segs.laneResultJson();
        if (agg.isTerminal()) {
            services.laneMapper.updateStatusResult(lane.getLaneId(), agg.getStatus(), result, ctx.userId());
        } else {
            services.laneMapper.updateResult(lane.getLaneId(), result, ctx.userId());
        }
        return agg;
    }

    /** 共享 setup 段坏、主体整体跳过: 给各 lane 已物化未跑的 MAIN 行 drain 留痕(未物化的不补建)。 */
    private void drainSharedMain() {
        for (LaneEntity lane : services.laneMapper.findByNode(node.getNodeInstId())) {
            segments(lane).drain(ActionSegment.MAIN);
        }
    }

    /** 共享回路是否已开过工(空分片短路判据): 段行或任一 lane 行已存在即算——现场施加过就必须收尾到位。 */
    private boolean sharedCircuitStarted(String nodeId) {
        if (!services.laneMapper.findByNode(nodeId).isEmpty()) {
            return true;
        }
        return !services.actionRows.findInContainer(nodeId, null, ActionSegment.SETUP).isEmpty()
                || !services.actionRows.findInContainer(nodeId, null, ActionSegment.SETDOWN).isEmpty();
    }

    private NodeSegments segments(LaneEntity lane) {
        return services.segments(dc, env, ctx, lane);
    }

    // ===================== lane 主体(两种形态的具名实现) =====================

    /** 一条 lane 要做什么: 完整三段回路, 或只跑 MAIN 主体(共享段模式)。 */
    private interface LaneBody {
        Status run(LaneEntity lane);
    }

    /** 第 i 条 lane 从哪来: 普通轮按分片键建行(执行到才建), 收尾轮从在途集合取(不新建)。 */
    private interface LaneLocator {
        LaneEntity locate(int index);
    }

    /** 普通轮: 走查到该分片才建 lane 行, 未走到的没有行。 */
    private final class NewShardLanes implements LaneLocator {
        @Override
        public LaneEntity locate(int index) {
            return services.actionRows.ensureLaneRow(dc, node, shardKeys.get(index));
        }
    }

    /** 收尾轮: 只跑在途 lane, 不按本轮分片键新建。 */
    private final class InFlightLanes implements LaneLocator {
        private final List<LaneEntity> inFlight;

        InFlightLanes(List<LaneEntity> inFlight) {
            this.inFlight = inFlight;
        }

        @Override
        public LaneEntity locate(int index) {
            return inFlight.get(index);
        }
    }

    /** 默认口径: 每 lane 一个完整三段回路。 */
    private final class FullCircuitBody implements LaneBody {
        @Override
        public Status run(LaneEntity lane) {
            return services.circuits.node(dc, env, ctx, lane);
        }
    }

    /** 共享段模式: 只跑本 lane 的 MAIN 主体(节点 setup/setdown 在 lane 外层各一次)。 */
    private final class MainOnlyBody implements LaneBody {
        @Override
        public Status run(LaneEntity lane) {
            return laneMain(lane);
        }
    }

    /** 共享段模式的电路: 节点 setup/setdown 各一次共享执行, 主体 = 逐 lane 主体序列。 */
    private final class SharedCircuit implements CircuitWalk.SectionedCircuit {
        private final NodeDef nd;
        private final NodeSegments nodeSegments;

        SharedCircuit(NodeDef nd) {
            this.nd = nd;
            this.nodeSegments = segments(null);
        }

        @Override
        public Status setup() {
            return nodeSegments.walkSetup(WalkSupport.orEmpty(nd.getSetup()));   // 收尾轮自动切行判定口径
        }

        @Override
        public Status main() {
            return laneSequence(new MainOnlyBody());   // lane 间串/并档不变, action 级段仍每 lane 各跑一遍
        }

        @Override
        public void onSetupBad() {
            drainSharedMain();
        }

        @Override
        public Status setdown() {
            return nodeSegments.walk(ActionSegment.SETDOWN, WalkSupport.orEmpty(nd.getSetdown()), true);
        }
    }

    // ===================== 串行/扇出的具名小件 =====================

    /**
     * 本层的一项: 定位(建行/取在途)后跑 lane 主体。串行档查闸门/收敛;
     * 扇出档的定位在提交线程同步做(扇出需同时在)。收尾轮两者都不查闸门、不收敛。
     */
    private final class LaneStep implements WalkStep, WalkLoop.FanoutTask {
        private final LaneLocator locator;
        private final LaneBody body;
        private final ErrorPolicy stopPolicy;
        private final boolean checkGate;

        LaneStep(LaneLocator locator, LaneBody body, ErrorPolicy stopPolicy, boolean checkGate) {
            this.locator = locator;
            this.body = body;
            this.stopPolicy = stopPolicy;
            this.checkGate = checkGate;
        }

        @Override
        public Status drive(int index) {
            return body.run(locator.locate(index));
        }

        @Override
        public Callable<Status> prepare(int index) {
            return new LaneUnit(body, locator.locate(index), checkGate);
        }

        @Override
        public boolean stops(Status one, int index) {
            return stopPolicy == ErrorPolicy.FAIL_FAST && (one == Status.FAILED || one == Status.STOPPED);
        }

        @Override
        public boolean cancelled() {
            return checkGate && env.gate().isCancelled(dc);
        }
    }

    /** 扇出的一项: 跑一条 lane(具名类代替 lambda, 栈里能看到单元来源)。 */
    private final class LaneUnit implements Callable<Status> {
        private final LaneBody body;
        private final LaneEntity lane;
        private final boolean checkGate;

        LaneUnit(LaneBody body, LaneEntity lane, boolean checkGate) {
            this.body = body;
            this.lane = lane;
            this.checkGate = checkGate;
        }

        @Override
        public Status call() {
            if (checkGate && env.gate().isCancelled(dc)) {
                return Status.STOPPED;   // 排队期间命中停止: 不再开这条 lane 的回路(行留给 retry 复位)
            }
            return body.run(lane);
        }
    }
}
