package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.exception.ProcessException;
import com.tesla.ities.process.core.context.NodeContext;
import com.tesla.ities.process.core.define.DslParser;
import com.tesla.ities.process.core.define.NodeDef;
import com.tesla.ities.process.core.model.ErrorPolicy;
import com.tesla.ities.process.core.model.NodeType;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.domain.LaneEntity;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.util.Errors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/**
 * ACTION 策略(#3): 叶子节点 = 实际跑 handler 的地方。
 *
 * NodeDef 来自行内走查对齐时挂上的 node.def()(不反查实例、不回查定义); action 行与 ActionDef
 * 按下标对齐(NodeDriver.runActionSequence 内推导), 监听器链从 ActionDef 推导(不落库)。
 * lane/action 物化(#5/#7): 走查到该分片才 laneAt 逐个建 lane, action 由 runActionSequence 执行到才逐个建
 *  (EAGER/LAZY 两档同路径: 创建期不预建子行, 分片键驱动期现解)。
 *
 * 非分片: 内联在驱动线程跑该节点的 action 序列。
 * 分片:   shardKeys 此刻解析(可读 live vars) -> 按 NodeDef.laneExecution 决定 lane 执行方式(#6):
 *         SERIAL(默认): lane 逐条串行(走查到才建); PARALLEL: 每条 lane 扇出 itiesProcessActionShardParallelExecutePool 并行 -> join 聚合。
 * FAIL_FAST(默认): 非分片在序列内 drain; 分片 lane 之间 drain(串行遇失败即停 / 并行不取消已跑 lane)。
 * 终态按 severity 优先级聚合(Status.aggregate): FAILED > STOPPED > SUCCESS。
 *
 * 分片参数(ctx): 策略收到 driveNode 下传的 NodeContext, 直接把它当两阶段解析的入参——
 * 节点级 config/定义在本层上读, 流程级 params/vars 沿父链上溯, 所以同一流程里不同分片节点各拿各的。
 */
@Component
public class ActionStrategy implements NodeStrategy {

    private static final Logger log = LoggerFactory.getLogger(ActionStrategy.class);

    private final LeafExprMaterializer leafMaterializer;
    private final ThreadPoolTaskExecutor shardExecutePool;

    public ActionStrategy(LeafExprMaterializer leafMaterializer,
                          @Qualifier("itiesProcessActionShardParallelExecutePool") ThreadPoolTaskExecutor shardExecutePool) {
        this.leafMaterializer = leafMaterializer;
        this.shardExecutePool = shardExecutePool;
    }

    @Override public NodeType type() {
        return NodeType.ACTION;
    }

    @Override
    public Status drive(NodeDriver driver, DriveContext dc, NodeContext ctx) {
        NodeInstEntity node = ctx.inst();
        if (!(node.getExpr() instanceof DslParser.Leaf)) {
            throw new ProcessException("AU204301", node.getNodeInstId());
        }
        NodeDef nd = node.getDef();   // 对齐期已一次取定
        if (nd == null) {
            throw new ProcessException("AU204302", ((DslParser.Leaf) node.getExpr()).nodeRef);
        }

        // 节点级 guard(when): 假则整节点 SKIPPED。在本节点上下文上求值(与 action guard 用 actx 口径一致,
        // vars/params/result 仍逐级上溯到流程)。
        if (node.getConditionExpr() != null && !node.getConditionExpr().isEmpty()
                && !dc.evaluator().eval(node.getConditionExpr(), ctx)) {
            return Status.SKIPPED;
        }

        // lane/action 不在这里批量建: 走查到分片/动作时才逐个物化(#5/#7; 两档同路径, 分片键此处现解)
        if (Boolean.TRUE.equals(node.getShardEnabled())) {
            return driveSharded(driver, dc, ctx, nd);
        }
        return driver.runActionSequence(dc, ctx, null);
    }

    /** 分片: 按 NodeDef.laneExecution(默认 SERIAL)决定 lane 串/并行; lane 走查到才 laneAt 物化。 */
    private Status driveSharded(NodeDriver driver, DriveContext dc, NodeContext ctx, NodeDef nd) {
        NodeInstEntity node = ctx.inst();
        List<String> shardKeys = leafMaterializer.resolveShardKeys(ctx);   // 阶段1: 解析分片键(ctx = 本节点上下文)
        boolean failFast = ErrorPolicy.of(node.getErrorPolicy()) == ErrorPolicy.FAIL_FAST;

        if (!nd.isLaneParallel()) {
            // 串行(默认): 逐条 lane 走查到才建(laneAt), 内联跑; FAIL_FAST 遇失败/停止即停后续 lane
            Status agg = Status.SUCCESS;
            for (String sk : shardKeys) {
                if (driver.isCancelled(dc)) {
                    agg = Status.aggregate(agg, Status.STOPPED);
                    break;
                }
                LaneEntity lane = leafMaterializer.laneAt(dc, node, sk);
                agg = Status.aggregate(agg, driver.runActionSequence(dc, ctx, lane));
                if ((agg == Status.FAILED || agg == Status.STOPPED) && failFast) {
                    break;
                }
            }
            return agg;
        }

        // 并行: 先建全 lane(扇出需同时在), 每 lane 提交并行池, join 聚合(FAIL_FAST drain: 不取消已跑 lane)
        List<LaneEntity> lanes = new ArrayList<>();
        for (String sk : shardKeys) {
            lanes.add(leafMaterializer.laneAt(dc, node, sk));
        }
        List<Future<Status>> futures = new ArrayList<>();
        for (final LaneEntity lane : lanes) {
            futures.add(shardExecutePool.submit(() -> driver.runActionSequence(dc, ctx, lane)));
        }
        Status agg = Status.SUCCESS;
        for (int i = 0; i < futures.size(); i++) {
            Status s;
            try {
                s = futures.get(i).get();
            } catch (Exception e) {
                // lane 任务异常 = 失败: 除了判状态, 还要留一句因由(日志 + 流程级失败记录), 不再静默吞掉
                log.error("分片 lane 异常 proc={} node={} lane={}", dc.procInstId(), ctx.nodeInstId(), i, e);
                dc.processContext().recordFailure(
                        Errors.describe(e, "分片 lane 异常", "node=" + ctx.name() + "/lane=" + i));
                s = Status.FAILED;
            }
            agg = Status.aggregate(agg, s);
            if (s == Status.FAILED && failFast) {
                for (int j = i + 1; j < futures.size(); j++) {
                    futures.get(j).cancel(false);
                }
            }
        }
        return agg;
    }
}
