package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.context.NodeContext;
import com.tt.ii.process.core.define.DslParser;
import com.tt.ii.process.core.model.ErrorPolicy;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.domain.NodeInstEntity;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * 容器子节点层的走查(组/并行, 以及组级段子节点切片):
 *  {@code walk} = SERIAL 每步查取消闸门 + 行策略收敛, FANOUT 不查闸门、由扇出模板汇聚;
 *  {@code slice} = 组级段对子项的一段切片(非收尾段与 SERIAL 同口径; 收尾段允许补建并关闸门完整执行)。
 *
 * 子行"走查到第 i 个才定位"(LAZY 建一个走一个; EAGER 创建期已建, 此处只对齐); 扇出档的备料
 * 在提交线程同步做——"扇出需同时在"的物化就发生在这里; 未走到的没有行, 无剩余项清理。
 *
 * 收尾轮(settle)口径: 只碰**已开工**的行(不补建); 收尾切片(组 setdown 段节点)是清理动作本身,
 * 允许补建并用**收尾驱动**完整执行(不查闸门), 否则组收尾会被停止信号空转掉。
 */
final class ChildrenWalk {

    private final DriveContext dc;
    private final NodeEnv env;
    private final NodeContext ctx;
    private final List<DslParser.Expr> items;
    private final WalkLoop loops;

    // 现场派生(构造一次): 本容器行冻结的生效策略 + 流程档 + 是否收尾轮
    private final NodeInstEntity node;
    private final ErrorPolicy policy;
    private final boolean eager;
    private final boolean settle;

    ChildrenWalk(DriveContext dc, NodeEnv env, NodeContext ctx, List<DslParser.Expr> items, WalkLoop loops) {
        this.dc = dc;
        this.env = env;
        this.ctx = ctx;
        this.items = items;
        this.loops = loops;
        this.node = ctx.inst();
        this.policy = ErrorPolicy.of(node.getErrorPolicy());
        this.eager = dc.def().isEager();
        this.settle = dc.isSettle();
    }

    /** 普通子节点走查: SERIAL/FANOUT 两档, 同一份项适配器(定位 -> 驱动)。 */
    Status walk(WalkMode mode) {
        if (mode == WalkMode.FANOUT) {
            // 扇出档不查闸门, 由扇出模板汇聚与 drain(只取消尚未开始的排队任务, 不强行中断已在跑的兄弟)
            return loops.fanout(WalkLoop.Pool.NODE, dc, ctx, items.size(), new ChildStep(),
                    settle ? ErrorPolicy.CONTINUE_ON_ERROR : policy, "并行子任务异常", "child");
        }
        return slice(0, items.size(), false);   // SERIAL 档与切片同口径, 循环模板仍只有一份
    }

    /**
     * 组级段子节点切片: 非收尾段与 {@link #walk} SERIAL 同口径(让渡短路/收敛/闸门都在同一份模板里);
     * 收尾段(finallyMode)走清理口径——允许补建、不查闸门、用收尾驱动完整执行。
     */
    Status slice(int from, int to, boolean finallyMode) {
        if (to <= from) {
            return Status.SUCCESS;
        }
        return finallyMode
                ? loops.serial(dc, from, to, new CleanupStep())
                : loops.serial(dc, from, to, new ChildStep());
    }

    /** 普通驱动/非收尾切片: 缺行补物化后照常驱动; 收尾轮只取已存在行, CREATED/终态一律不驱动。 */
    private NodeInstEntity locate(int index) {
        if (!settle) {
            return env.children().childAt(dc, node, items, index, eager);
        }
        return existingStarted(index);
    }

    /** 收尾轮定位: 只取已开工的在途行(缺行/已物化未开工/已收尾过都不驱动)。 */
    private NodeInstEntity existingStarted(int index) {
        NodeInstEntity child = env.children().childAtExisting(dc, node, items, index, eager);
        return WalkSupport.started(child) ? child : null;
    }

    /**
     * 本层的一项: 定位后驱动。串行档按行策略收敛/查闸门; 扇出档的备料在提交线程同步做
     * ("扇出需同时在"的物化就发生在这里, null = 本项不驱动)。
     */
    private final class ChildStep implements WalkStep, WalkLoop.FanoutTask {
        @Override
        public Status drive(int index) {
            NodeInstEntity child = locate(index);
            return child == null ? Status.SKIPPED : env.nodes().driveNode(dc, child, ctx);
        }

        @Override
        public Callable<Status> prepare(int index) {
            NodeInstEntity child = locate(index);
            return child == null ? null : new DriveUnit(child);
        }

        @Override
        public boolean stops(Status one, int index) {
            return !settle && policy == ErrorPolicy.FAIL_FAST
                    && (one == Status.FAILED || one == Status.STOPPED);
        }

        @Override
        public boolean cancelled() {
            return !settle && env.gate().isCancelled(dc);
        }
    }

    /** 收尾段的一项: 组 setdown 段节点是清理动作本身——允许补建、关闸门, 按收尾驱动完整执行。 */
    private final class CleanupStep implements WalkStep {
        @Override
        public Status drive(int index) {
            NodeInstEntity child = env.children().childAt(dc, node, items, index, eager);
            // finally 切片不区分普通轮/收尾轮: 清理动作本身必须完整执行, 不能被停止信号 drain 掉
            return env.nodes().driveNodeCleanup(dc, child, ctx);
        }
    }

    /** 扇出的一项: 驱动单个子节点(具名类代替 lambda, 栈里能看到单元来源)。 */
    private final class DriveUnit implements Callable<Status> {
        private final NodeInstEntity child;

        DriveUnit(NodeInstEntity child) {
            this.child = child;
        }

        @Override
        public Status call() {
            return env.nodes().driveNode(dc, child, ctx);
        }
    }
}
