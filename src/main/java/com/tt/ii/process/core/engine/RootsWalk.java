package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.define.DslParser;
import com.tt.ii.process.core.define.ProcessDefinition;
import com.tt.ii.process.core.model.ErrorPolicy;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.domain.NodeInstEntity;

import java.util.List;

/**
 * 顶层根序列的走查(setup + flow + setdown 三段): 根序列这一层容器的生效口径 = 流程级全局。
 *
 * 口径(与拆分前逐字一致):
 *  - setup+flow 段 [0, flowEnd) **就近声明收敛**(该根自己声明了 errorPolicy 就听它的, 没声明才回落流程级全局,
 *    且判的只是本项终态——聚合值带着更早那次的坏, 用它判会误杀没吭声的后续根);
 *  - setdown 段 [flowEnd, end) 是 finally 语义恒不收敛; **顶层不设取消闸门**(停止信号落在正在驱动的
 *    那条链上, 由内层与 handler 处判); 未走到的根"执行到才建", 无剩余项清理;
 *  - 让渡(返回合法让渡态 pending/paused/suspended)短路: flow 段任一让渡 -> 顶层 setdown 段不跑,
 *    原状态返回(重入后继续); 非法非终态(created/running)经归一为 FAILED, 按坏终态口径收尾;
 *  - 流程级段重臂(v1.1): 入口按"setdown 段根行存在 success + setup/flow 段存在非终态行"把顶层
 *    setup/setdown 段的 success 根行成对复位(顶层 repeatSegments 开启时无条件复位), 让流程级"改—还原"
 *    段在显式重试后成对重跑。
 *
 * 两段各走各的: flow 段按就近声明收敛, setdown 段跑完, 两段结果按 exit 聚合。
 * 若并成一段, flow 段的 FAIL_FAST 收敛会把 setdown 一起跳过——那不是 finally。
 */
final class RootsWalk {

    private final DriveContext dc;
    private final NodeEnv env;
    private final List<DslParser.Expr> ordered;
    private final int flowEnd;
    private final WalkServices services;

    RootsWalk(DriveContext dc, NodeEnv env, List<DslParser.Expr> ordered, int flowEnd, WalkServices services) {
        this.dc = dc;
        this.env = env;
        this.ordered = ordered;
        this.flowEnd = flowEnd;
        this.services = services;
    }

    Status run() {
        ProcessDefinition def = dc.def();
        boolean settle = dc.isSettle();   // 停止收尾轮(settleOnStop): 只收尾不推进
        if (!settle) {
            services.rearm.reArmProcess(dc, def, ordered, flowEnd);   // v1.1 流程级成对重臂
        }
        RootStep step = new RootStep(def, def.isEager(), settle, def.globalErrorPolicy());
        Status flowAgg = services.loops.serial(dc, 0, flowEnd, step);
        if (!settle && !flowAgg.isTerminal()) {
            return flowAgg;   // 让渡: 顶层 setdown 段不跑(finally 属于完成路径), 等重入后继续
        }
        // setdown 是 finally: flow 段 FAILED 也照跑(setdown 段本身恒不收敛)。
        // 但注意只物化 setdown 根: RootStep 对 setdown 切片走 rootAtForFinally, 失败点之后的 flow 根
        // 不会以 CREATED 身份被补建(需要保位的空洞落 STOPPED/DRAIN, retry 时可复位)。
        return Status.exitAwareAggregate(flowAgg, services.loops.serial(dc, flowEnd, ordered.size(), step));
    }

    /** 顶层的一项: 驱动第 i 个根; 收敛判据按该根自己的就近声明。 */
    private final class RootStep implements WalkStep {
        private final ProcessDefinition def;
        private final boolean eager;
        private final boolean settle;
        private final ErrorPolicy layer;

        RootStep(ProcessDefinition def, boolean eager, boolean settle, ErrorPolicy layer) {
            this.def = def;
            this.eager = eager;
            this.settle = settle;
            this.layer = layer;
        }

        @Override
        public Status drive(int index) {
            return driveRoot(index);
        }

        @Override
        public boolean stops(Status one, int index) {
            // 收敛**就近声明优先**且只看本项终态; setdown 段(i >= flowEnd)恒不收敛(finally 语义);
            // 收尾轮也不收敛: 每个在途根都要走到自己的 setdown
            return !settle && index < flowEnd && (one == Status.FAILED || one == Status.STOPPED)
                    && def.stopsSequence(def.node(ordered.get(index).defRef()), layer);
        }

        /**
         * 顶层第 i 个根的驱动口径。普通驱动 = 缺行补物化后照常驱动;
         * **收尾轮**(settle) 分两段:
         *  - flow 段 [0, flowEnd): 只碰**已开工**的行(缺行/CREATED/已终态 一律跳过, 不补建)——
         *    从没跑过 setup 的节点不该在停止时被拉起来跑 setdown; 在途(RUNNING)节点照常驱动——
         *    取消闸门(标识已落)会拦住 setup/main, 节点自己的 setdown 段(finally)补跑;
         *  - setdown 段 [flowEnd, end): 收尾节点是"清理动作本身", 必须能被**完整执行**(闸门关, 允许补建)。
         */
        private Status driveRoot(int index) {
            boolean setdownSlice = index >= flowEnd;
            if (!settle) {
                // 普通轮的 setdown 切片也是 finally: 用收尾驱动(关闸门), 否则并发 stop 会把清理动作 drain 掉。
                // setdown 缺行时用 rootAtForFinally: 只补 setdown 根, 失败点之后的 flow 根不落 CREATED。
                NodeInstEntity row = setdownSlice
                        ? env.children().rootAtForFinally(dc, ordered, index, eager)
                        : env.children().rootAt(dc, ordered, index, eager);
                return setdownSlice
                        ? env.nodes().driveNodeCleanup(dc, row, dc.processContext())
                        : env.nodes().driveNode(dc, row, dc.processContext());
            }
            NodeInstEntity row = setdownSlice
                    ? env.children().rootAtForFinally(dc, ordered, index, eager)      // 收尾切片: 允许补建
                    : env.children().rootAtExisting(dc, ordered, index, eager);      // 主链: 只碰已开工的行
            if (row == null) {
                return Status.SKIPPED;   // 从未物化: 从未开始, 无现场可收
            }
            if (!setdownSlice && !WalkSupport.started(row)) {
                return Status.SKIPPED;   // 已物化未开工 / 已收尾过: 都不再驱动
            }
            return setdownSlice
                    ? env.nodes().driveNodeCleanup(dc, row, dc.processContext())
                    : env.nodes().driveNode(dc, row, dc.processContext());
        }
    }
}
