package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.context.NodeContext;
import com.tt.ii.process.core.define.DslParser;
import com.tt.ii.process.core.runtime.ContextFactory;
import com.tt.ii.process.domain.LaneEntity;
import com.tt.ii.process.mapper.LaneMapper;
import com.tt.ii.process.mapper.NodeInstMapper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

/**
 * 走查门面内部的服务束: 协作件**一次性装配**, 各层走查按需取用。
 *
 * 存在的理由: 拆分前 LayerWalker 把 7 个构造依赖直接摊在 30 多个私有方法上, 几乎每个方法都要
 * (dc, env, ctx, lane, ...) 一长串位置参数。拆成协作件后, 如果每个小类再各自持 6~10 个依赖,
 * 只是把"一个复杂类"换成"多个难装配的类"。本类因此成为唯一的装配点:
 *  - 模板 {@link WalkLoop}(三档池) / 段重臂 {@link SegmentRearmer} / 三段回路 {@link CircuitWalk} 在此创建;
 *  - 按调用现场创建 {@link NodeSegments}(某节点某 lane 的段执行器)与 {@link ChildrenWalk}(某容器的子节点走查);
 *  - 层走查(RootsWalk/ChildrenWalk/LanesWalk)只带现场(dc/env/ctx/items) + 本束, 构造参数不超过 5 个。
 */
final class WalkServices {

    final WalkLoop loops;
    final ActionRows actionRows;
    final ContextFactory ctxFactory;
    final NodeInstMapper nodeMapper;
    final LaneMapper laneMapper;
    final SegmentRearmer rearm;
    final CircuitWalk circuits;

    WalkServices(ThreadPoolTaskExecutor nodePool, ThreadPoolTaskExecutor shardPool,
                 ThreadPoolTaskExecutor actionPool, ActionRows actionRows, ContextFactory ctxFactory,
                 NodeInstMapper nodeMapper, LaneMapper laneMapper) {
        this.loops = new WalkLoop(nodePool, shardPool, actionPool);
        this.actionRows = actionRows;
        this.ctxFactory = ctxFactory;
        this.nodeMapper = nodeMapper;
        this.laneMapper = laneMapper;
        this.rearm = new SegmentRearmer(actionRows, nodeMapper, laneMapper);
        this.circuits = new CircuitWalk(actionRows, ctxFactory, nodeMapper, laneMapper, loops, rearm);
    }

    /** 某节点(可带 lane)的段执行器: 现场绑定一次, 段方法只带段本身。 */
    NodeSegments segments(DriveContext dc, NodeEnv env, NodeContext ctx, LaneEntity lane) {
        return new NodeSegments(dc, env, ctx, lane, actionRows, ctxFactory, loops);
    }

    /** 某容器的子节点走查(组/并行/组级段切片)。 */
    ChildrenWalk children(DriveContext dc, NodeEnv env, NodeContext ctx, List<DslParser.Expr> items) {
        return new ChildrenWalk(dc, env, ctx, items, loops);
    }
}
