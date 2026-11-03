package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.context.NodeContext;
import com.tt.ii.process.core.define.DslParser;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.core.runtime.ContextFactory;
import com.tt.ii.process.domain.LaneEntity;
import com.tt.ii.process.mapper.LaneMapper;
import com.tt.ii.process.mapper.NodeInstMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 走查引擎门面: {@link LayerWalk} 的唯一实现——五个 walk 入口在这里**只做分派**, 各层口径归各自的协作件。
 *
 * 分工(每个入口一句话):
 *  <li>顶层根序列 -> {@link RootsWalk}(就近声明收敛 / setdown finally / 顶层不查闸门 / 流程级段重臂);</li>
 *  <li>容器子节点 -> {@link ChildrenWalk}(SERIAL/FANOUT; 也是组级段子节点切片的执行器);</li>
 *  <li>分片 lane -> {@link LanesWalk}(逐 lane 回路 / 共享段档 / 收尾轮只碰在途 lane);</li>
 *  <li>节点回路 / 组回路 -> {@link CircuitWalk}(setup -> main -> setdown 的三段骨架, 节点与组两种主体)。</li>
 *
 * 共用底座:
 *  <li>{@link WalkLoop}: 串行/扇出两份算法模板(全仓唯一), 层差异由 {@link WalkStep} / {@link WalkLoop.FanoutTask}
 *      的**具名实现**表达, 不再用 lambda 拼四个钩子;</li>
 *  <li>{@link NodeSegments}: 某节点(某 lane)的段执行器——行定位、重入、收敛、drain、action 级段;</li>
 *  <li>{@link SegmentRearmer}: 流程/叶子/组/组级节点段四种成对重臂;</li>
 *  <li>{@link WalkSupport}: 定义侧空值/有效键 + 行状态判定(纯函数);</li>
 *  <li>{@link WalkServices}: 以上协作件的一次性装配点(层走查只带现场 + 本束, 不再摊一长串位置参数)。</li>
 *
 * 层口径速查(细节见各协作件注释):
 *  <li>顶层: 不查取消 / 就近声明收敛 / setdown 恒不收敛; 无剩余项清理;</li>
 *  <li>节点回路: setup 段(行策略收敛) + 主段(现状口径) + setdown 段(恒不收敛/不查闸门);
 *      容器行状态收尾**推迟到 setdown 段之后**(三段聚合, 不变式 I1);</li>
 *  <li>组回路: 组 setup + 子节点序列(SERIAL) + 组 setdown —— 骨架与节点回路同一份;
 *      setup 坏 -> 子节点序列整体跳过且**不递归 drain**; 组行收尾归 NodeDriver.finishNode;</li>
 *  <li>lane: 每步查库 / 节点行冻结策略收敛 / 档由 NodeDef.laneExecution 派生;
 *      segmentsFollowShard: false 时切共享段档(setup/setdown 整节点各一次, 段行 lane 恒 null);</li>
 *  <li>action: 唯一持已物化行、唯一有 drain(FR-EXE-08/FR-ERR-06); 终态行不重跑、非终态行重跑(at-least-once);
 *      档由 NodeDef.actionExecution 派生(只跟随主段, setup/setdown 段恒串行);</li>
 *  <li>让渡(handler 返回 pending/paused/suspended): 本轮退出——段内立即停且不 drain、后续段(含 setdown)不跑、
 *      容器行不落终态(节点停 running); 坏终态(FAILED/STOPPED)优先于让渡; 非法非终态(created/running)
 *      经 {@link Status#driveResult} 归一为 FAILED 并留痕;</li>
 *  <li>收尾轮(settle, 见 {@link DriveContext#isSettle()}): 遍历不设闸门、主链只碰已开工行(不补建)、
 *      setup 段只做行判定(不重跑)、主段被闸门拦下并 drain、setdown 段照常补跑; 段重臂一律不触发。</li>
 */
@Component
public class LayerWalker implements LayerWalk {

    private final WalkServices services;

    public LayerWalker(@Qualifier("itiesProcessParallelExecutePool") ThreadPoolTaskExecutor nodePool,
                       @Qualifier("itiesProcessActionShardParallelExecutePool") ThreadPoolTaskExecutor shardPool,
                       @Qualifier("itiesProcessActionParallelExecutePool") ThreadPoolTaskExecutor actionPool,
                       ActionRows actionRows, ContextFactory ctxFactory,
                       NodeInstMapper nodeMapper, LaneMapper laneMapper) {
        this.services = new WalkServices(nodePool, shardPool, actionPool, actionRows, ctxFactory, nodeMapper, laneMapper);
    }

    /** 顶层根序列: setup+flow 段就近声明收敛, setdown 段 finally; 见 {@link RootsWalk}。 */
    @Override
    public Status walkRoots(DriveContext dc, NodeEnv env, List<DslParser.Expr> ordered, int flowEnd) {
        return new RootsWalk(dc, env, ordered, flowEnd, services).run();
    }

    /** 容器子节点层(SERIAL/FANOUT); 见 {@link ChildrenWalk}。 */
    @Override
    public Status walkChildren(DriveContext dc, NodeEnv env, NodeContext ctx,
                               List<DslParser.Expr> items, WalkMode mode) {
        return services.children(dc, env, ctx, items).walk(mode);
    }

    /** 分片 lane 层(逐 lane 回路 / 共享段档); 见 {@link LanesWalk}。 */
    @Override
    public Status walkLanes(DriveContext dc, NodeEnv env, NodeContext ctx, List<String> shardKeys) {
        return new LanesWalk(dc, env, ctx, shardKeys, services).run();
    }

    /** 节点回路(三段编排, lane 可为 null); 见 {@link CircuitWalk#node}。 */
    @Override
    public Status walkCircuit(DriveContext dc, NodeEnv env, NodeContext ctx, LaneEntity lane) {
        return services.circuits.node(dc, env, ctx, lane);
    }

    /** 组回路(三段编排: 组段 / 子节点序列 / 组级节点段); 见 {@link CircuitWalk#group}。 */
    @Override
    public Status walkGroupCircuit(DriveContext dc, NodeEnv env, NodeContext ctx,
                                   List<DslParser.Expr> items) {
        return services.circuits.group(dc, env, ctx, items);
    }
}
