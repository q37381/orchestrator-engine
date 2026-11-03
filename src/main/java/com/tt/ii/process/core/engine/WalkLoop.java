package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.context.NodeContext;
import com.tt.ii.process.core.model.ErrorPolicy;
import com.tt.ii.process.core.model.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 走查的两份算法模板(**全仓唯一**): 串行 {@link #serial} 与扇出 {@link #fanout}。
 *
 * 层差异不在这里——串行由 {@link WalkStep} 的具名实现表达, 扇出由 {@link FanoutTask} 的具名实现备料;
 * 本类只管循环、归一、聚合、停止口径, 以及三档执行池的路由(node/shard/action 互不抢占)。
 *
 * 串行模板的停止优先级(与原实现逐字一致): 取消闸门 > 驱动 > 归一 + exit 聚合 > 让渡短路 > 收敛判定 > drain。
 * 只有"取消/收敛"才触发 drainFrom; 合法让渡(handler 返回 pending/paused/suspended)立即退出且**不 drain**,
 * 剩余项(含已物化行)留给重入续跑。
 */
final class WalkLoop {

    private static final Logger log = LoggerFactory.getLogger(WalkLoop.class);

    private final ThreadPoolTaskExecutor nodePool;
    private final ThreadPoolTaskExecutor shardPool;
    private final ThreadPoolTaskExecutor actionPool;

    WalkLoop(ThreadPoolTaskExecutor nodePool, ThreadPoolTaskExecutor shardPool,
             ThreadPoolTaskExecutor actionPool) {
        this.nodePool = nodePool;
        this.shardPool = shardPool;
        this.actionPool = actionPool;
    }

    /** 三档执行池的标识: 调用层只说"这层用哪档", 池对象与路由归模板(node/shard/action 互不抢占)。 */
    enum Pool {
        NODE, SHARD, ACTION
    }

    /** 池标识 -> 池对象(选池只发生在这里, 不再散到调用点)。 */
    private ThreadPoolTaskExecutor poolOf(Pool pool) {
        switch (pool) {
            case NODE:
                return nodePool;
            case SHARD:
                return shardPool;
            default:
                return actionPool;
        }
    }

    /**
     * 串行走查模板: 逐项 [取消闸门 -> 定位驱动 -> 归一+exit 聚合 -> 让渡短路 -> 收敛判定 -> 剩余项清理]。
     *
     * 收敛判的是**本项终态**而不是聚合值(STOPPED 与取消无关时留作防御性即停, 取消在闸门处 break 走不到这里)。
     * 驱动返回值先过 {@link Status#driveResult} 归一: created/running 这类非法非终态 -> FAILED(不静默过聚合),
     * 归一触发时留痕(日志 + 流程级失败摘要), 用户不会只看到一个"莫名 FAILED"。
     */
    Status serial(DriveContext dc, int from, int to, WalkStep step) {
        Status agg = Status.SUCCESS;
        for (int i = from; i < to; i++) {
            if (step.cancelled()) {
                agg = Status.exitAwareAggregate(agg, Status.STOPPED);
                step.drainFrom(i);          // 取消时本项还没跑
                break;
            }
            Status raw = step.drive(i);
            Status one = Status.driveResult(raw);   // 归一: 非法非终态 -> FAILED
            if (raw != one) {
                reportIllegal(dc, raw, "process=" + dc.procInstId());   // 兜底归一必须留痕(源头白名单已拦, 这条防上游漏校验)
            }
            agg = Status.exitAwareAggregate(agg, one);
            if (one != null && one.isYieldState()) {
                break;                     // 让渡: 本轮退出, 不 drain 剩余项(重入后从这里继续)
            }
            if (step.stops(one, i)) {
                step.drainFrom(i + 1);     // 收敛时剩下的都比本项新
                break;
            }
        }
        return agg;
    }

    /**
     * 扇出模板(全仓唯一一份扇出路径): 逐项请 {@code taskAt} 备任务(备料在提交线程同步做——
     * "扇出需同时在"的物化/定位就发生在这里), 非 null 的提交进池, 最后 join 聚合。
     * 收敛口径仍读同一份行冻结策略——与本层串行档判的是同一条, 两档因此不会各说各话。
     *
     * @param node 本层所属节点上下文(join 记因由与定位用)
     */
    Status fanout(Pool pool, DriveContext dc, NodeContext node,
                  int count, FanoutTask taskAt, ErrorPolicy policy, String fault, String unitKey) {
        ThreadPoolTaskExecutor executor = poolOf(pool);
        List<Future<Status>> futures = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        AtomicBoolean[] started = new AtomicBoolean[count];
        for (int i = 0; i < count; i++) {
            started[i] = new AtomicBoolean(false);
        }
        boolean cancelled = false;
        for (int i = 0; i < count; i++) {
            if (taskAt.cancelled()) {
                taskAt.drainRemaining(i);   // 已定位/已物化的剩余项落 STOPPED/DRAIN, 未物化的不补建
                cancelled = true;
                break;
            }
            Callable<Status> task = taskAt.prepare(i);
            if (task != null) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    started[index].set(true);
                    return task.call();
                }));
                indexes.add(index);
            }
        }
        Status agg = join(dc, node, futures, indexes, started, taskAt, policy, fault, unitKey);
        // 提交前命中取消: 即使一个任务都没提交, 也要把本层聚合为 STOPPED(不能静默成 SUCCESS)
        return cancelled ? Status.exitAwareAggregate(agg, Status.STOPPED) : agg;
    }

    /**
     * join 已提交的任务并聚合成终态(让渡感知: 非终态保留, 坏终态优先)。
     * 让渡(handler 返回 pending/paused/suspended): 兄弟已提交不可撤回, 逐个 join 完再定终局——
     * 任一让渡态保留为结果(除非已有 FAILED/STOPPED: 坏终态优先); 让渡不触发 cancelPending(不是失败)。
     * 每个返回值先过 {@link Status#driveResult} 归一, 归一触发时留痕(日志 + 流程级失败摘要);
     * 子任务抛异常按 FAILED 计并记一句因由, 不再静默吞掉。
     * 不 hold owner 线程做 CompletableFuture.join 饥饿: CallerRunsPolicy 保证池满时调用线程自跑, 必有进展。
     *
     * @param futures 已提交的任务(顺序 = 声明顺序)
     * @param policy  本容器行冻结的生效策略(FAIL_FAST 则某项失败即 drain 掉尚未开始的兄弟)
     * @param fault   任务抛异常时的语境名(如"并行子任务异常")
     * @param unitKey 定位用的单元名(child / lane / action)
     */
    private Status join(DriveContext dc, NodeContext node, List<Future<Status>> futures,
                        List<Integer> indexes, AtomicBoolean[] started, FanoutTask taskAt,
                        ErrorPolicy policy, String fault, String unitKey) {
        Status agg = Status.SUCCESS;
        for (int k = 0; k < futures.size(); k++) {
            int index = indexes.get(k);
            Status s;
            boolean interrupted = false;
            try {
                Status raw = futures.get(k).get();
                s = Status.driveResult(raw);   // 归一: 非法非终态 -> FAILED
                if (raw != s) {
                    reportIllegal(dc, raw, "node=" + node.name() + "/" + unitKey + "=" + index);
                }
            } catch (CancellationException e) {
                s = Status.STOPPED;   // 本模板取消的排队项: 不是失败, 不留失败摘要
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                s = Status.STOPPED;
                interrupted = true;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                log.error("{} proc={} node={} {}={}", fault, dc.procInstId(), node.nodeInstId(), unitKey, index, cause);
                FailureRecorder.record(dc.processContext(), cause, fault,
                        "node=" + node.name() + "/" + unitKey + "=" + index);
                s = Status.FAILED;
            } catch (Exception e) {
                // 子任务异常 = 失败: 除了判状态, 还要留一句因由(日志 + 流程级失败记录), 不再静默吞掉
                log.error("{} proc={} node={} {}={}", fault, dc.procInstId(), node.nodeInstId(), unitKey, index, e);
                FailureRecorder.record(dc.processContext(), e, fault,
                        "node=" + node.name() + "/" + unitKey + "=" + index);
                s = Status.FAILED;
            }
            agg = Status.exitAwareAggregate(agg, s);   // 非终态(让渡)不被 severity 聚合吞掉; FAILED/STOPPED 仍优先
            if ((s == Status.FAILED || s == Status.STOPPED) && policy == ErrorPolicy.FAIL_FAST) {
                cancelNotStarted(futures, indexes, started, index + 1, taskAt);   // 只取消尚未开始的; 已跑的不动
            }
            if (interrupted) {
                break;
            }
        }
        return agg;
    }

    /** 取消 index 之后**尚未开始**的任务; 已开始/已完成的兄弟不取消(其结果仍参与 join)。 */
    private static void cancelNotStarted(List<Future<Status>> futures, List<Integer> indexes,
                                         AtomicBoolean[] started, int fromIndex, FanoutTask taskAt) {
        for (int k = 0; k < futures.size(); k++) {
            int index = indexes.get(k);
            if (index < fromIndex || started[index].get()) {
                continue;
            }
            if (futures.get(k).cancel(false)) {
                taskAt.drainCancelled(index);
            }
        }
    }

    /** 模板层兜底归一触发时的留痕: 日志 + 流程级失败摘要(最终落 process_inst.result.__error, 与既有错误留痕同通道)。 */
    void reportIllegal(DriveContext dc, Status raw, String where) {
        String msg = FailureRecorder.record(dc.processContext(),
                new IllegalStateException("非法过程态返回值: " + raw.getStatus()
                        + "(只允许终态或 pending/paused/suspended 让渡态), 已按 FAILED 归一"),
                "走查层返回值非法", where);
        log.warn(msg);
    }

    /**
     * 扇出备料: 在第 i 个位置备一个可执行单元(null = 本项跳过不提交)。
     * 具名实现类代替裸 lambda——提交进池后栈里能看到是谁的单元。
     */
    interface FanoutTask {
        Callable<Status> prepare(int index);

        /** 提交前闸门: 命中即停止继续提交, 已物化未跑项由 drainFrom 落 STOPPED/DRAIN。 */
        default boolean cancelled() {
            return false;
        }

        /** 提前结束: 清理 [from, end) 中已物化/已定位的剩余项(未物化的不补建)。 */
        default void drainRemaining(int from) {
        }

        /** 某个尚未开始的任务被取消: 清理它已物化的行。 */
        default void drainCancelled(int index) {
        }
    }
}
