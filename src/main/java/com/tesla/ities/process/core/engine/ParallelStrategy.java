package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.core.context.NodeContext;
import com.tesla.ities.process.core.define.DslParser;
import com.tesla.ities.process.core.model.ErrorPolicy;
import com.tesla.ities.process.core.model.NodeType;
import com.tesla.ities.process.core.model.Status;
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
 * PARALLEL 策略(#3): 孩子 = 子节点, 全部扇出到 itiesProcessParallelExecutePool 并行, join 汇聚。
 *
 * 子表达式来自行内走查光标 node.expr()(Parallel.items), 子行与其按下标对齐。
 * 物化(#5/#7): EAGER 创建期预建子节点行; LAZY 走查到第 i 个子才 childAt 建第 i 个(建一个扇出一个);
 * 叶子下的 lane/action 两档一律驱动期建。每个子任务递归时定义已在子行上。
 * 不 hold owner 线程做 CompletableFuture.join 饥饿: CallerRunsPolicy 保证池满时调用线程自跑, 必有进展。
 * **容器级 guard**(condition_expr): 只有带命名后缀的命名并行才有({@code PARALLEL:checks(a,b)}, condition_expr 落
 *   NodeDef.when), 假则整条并行 SKIPPED、一个子项都不物化/不提交——与 GroupStrategy 同口径。匿名并行该行恒为
 *   null, 因此这一段对旧行为零影响。
 * FAIL_FAST(默认) = drain: 不取消已在跑的兄弟(无法安全中断), 取消尚未开始的排队任务, 等全部 join 完再聚合失败;
 *   仅靠 isCancelled 钩子让业务自愿收敛。CONTINUE_ON_ERROR: 跑完聚合。
 * 终态按 severity 优先级聚合(Status.aggregate): FAILED > STOPPED > SUCCESS。
 */
@Component
public class ParallelStrategy implements NodeStrategy {

    private static final Logger log = LoggerFactory.getLogger(ParallelStrategy.class);

    private final Materializer materializer;
    private final ThreadPoolTaskExecutor parallelExecutePool;

    public ParallelStrategy(Materializer materializer,
                            @Qualifier("itiesProcessParallelExecutePool") ThreadPoolTaskExecutor parallelExecutePool) {
        this.materializer = materializer;
        this.parallelExecutePool = parallelExecutePool;
    }

    @Override public NodeType type() {
        return NodeType.PARALLEL;
    }

    @Override
    public Status drive(NodeDriver driver, DriveContext dc, NodeContext ctx) {
        NodeInstEntity node = ctx.inst();
        List<DslParser.Expr> items = ((DslParser.Parallel) node.getExpr()).items;
        boolean eager = dc.def().isEager();
        boolean failFast = ErrorPolicy.of(node.getErrorPolicy()) == ErrorPolicy.FAIL_FAST;

        // 容器级 guard(命名并行的 when): 假则整条并行免跑, 一个子项都不提交(与 GroupStrategy 同口径)
        if (node.getConditionExpr() != null && !node.getConditionExpr().isEmpty()
                && !dc.evaluator().eval(node.getConditionExpr(), ctx)) {
            return Status.SKIPPED;
        }

        // 走查到第 i 个子才物化(LAZY 建一个扇出一个; EAGER 子节点行创建期已建, childAt 只定位对齐; action 执行到才建)
        // 子节点的直属容器 = 本 PARALLEL 的 ctx: 上下文父链与实例树(parent_id)保持同形,
        // 于是本容器若嵌在聚合节点里, 组级 config 与使用者显式挂进组桶的 result 对扇出的子节点依然可读。
        List<Future<Status>> futures = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            final NodeInstEntity child = materializer.childAt(dc, node, items, i, eager);
            futures.add(parallelExecutePool.submit(() -> driver.driveNode(dc, child, ctx)));
        }

        Status agg = Status.SUCCESS;
        for (int i = 0; i < futures.size(); i++) {
            Status s;
            try {
                s = futures.get(i).get();      // join; drain: 失败也继续收集其余兄弟结果
            } catch (Exception e) {
                // 子任务异常 = 失败: 除了判状态, 还要留一句因由(日志 + 流程级失败记录), 不再静默吞掉
                log.error("并行子任务异常 proc={} node={} child={}", dc.procInstId(), ctx.nodeInstId(), i, e);
                dc.processContext().recordFailure(
                        Errors.describe(e, "并行子任务异常", "node=" + ctx.name() + "/child=" + i));
                s = Status.FAILED;
            }
            agg = Status.aggregate(agg, s);
            if (s == Status.FAILED && failFast) {
                // FAIL_FAST: 取消尚未开始的排队任务(已跑的 drain), 不强行中断
                for (int j = i + 1; j < futures.size(); j++) {
                    futures.get(j).cancel(false);
                }
            }
        }
        return agg;
    }
}
