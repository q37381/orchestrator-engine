
package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.exception.ProcessException;
import com.tesla.ities.process.core.context.NodeContext;
import com.tesla.ities.process.core.define.DslParser;
import com.tesla.ities.process.core.model.ErrorPolicy;
import com.tesla.ities.process.core.model.NodeType;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.domain.NodeInstEntity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * GROUP(聚合节点)策略: 命名**串行**容器 —— 子表达式按声明顺序逐个 childAt 物化 + driveNode,
 * 状态按 severity 聚合(Status.aggregate), 与 ExecutionEngine.driveRoots 的顶层走查同构(只是作用在子节点层)。
 *
 * 存在的意义: 让"一个校验项 = 一个节点"成立。校验项内部的多个步骤(如"取源端"不分片 + "逐片对比"分片并行)
 * 各自是一个子节点, 但对外只暴露聚合节点这一个名字: flow 里写一项、进度里一项一行、errorPolicy/组级 guard 一处声明。
 *
 * 两个容器级语义(与 PARALLEL/IF 的差别):
 *  1. **组级 guard**: condition_expr 落的是 NodeDef.when, 假则整组 SKIPPED(与 ActionStrategy 的节点级 guard 同口径)。
 *  2. **组级共享靠显式放置**: 引擎**不再**把子节点 result 冒泡进组桶——各层桶只装自己层的值。
 *     子节点要交给兄弟/上层, 由使用者在 handler/监听器里自己放: {@code ctx.node().parent().putResult(k, v)}
 *     挂进组桶、{@code ctx.upward().putResult(k, v)} 挂进所属节点/lane 桶、{@code ctx.putVar(k, v)} 写流程级 vars、
 *     或写业务专表; 后续子节点写 {@code ${result.<k>}} 沿父链上溯即可读到显式挂进组桶的值。组桶随 finishNode 落
 *     process_node_inst.result, 所以显式放置的值崩溃重驱动后仍在。
 *
 * 不支持: GROUP 自己带 shard(语义会变成"每 lane 跑一遍整个子流程", 与 lane 容器冲突); 分片永远在叶子子节点上。
 *
 * 组内序列的**收敛口径**只看组行自己的生效值(组声明 > 流程级全局 > 默认 FAIL_FAST), 组内子节点的声明只作用于
 * 它自己容器内。**组外**(根序列)遇这条组坏要不要继续, 由上一层序列按就近声明判定(见 ProcessDefinition.stopsSequence)。
 */
@Component
public class GroupStrategy implements NodeStrategy {

    private final Materializer materializer;

    public GroupStrategy(Materializer materializer) {
        this.materializer = materializer;
    }

    @Override public NodeType type() {
        return NodeType.GROUP;
    }

    @Override
    public Status drive(NodeDriver driver, DriveContext dc, NodeContext ctx) {
        NodeInstEntity node = ctx.inst();
        if (!(node.getExpr() instanceof DslParser.Group)) {
            throw new ProcessException("AU204303", node.getNodeInstId());
        }
        DslParser.Group group = (DslParser.Group) node.getExpr();

        // 组级 guard(when): 假则整组 SKIPPED(与 ActionStrategy 的节点级 guard 同口径)
        if (node.getConditionExpr() != null && !node.getConditionExpr().isEmpty()
                && !dc.evaluator().eval(node.getConditionExpr(), ctx)) {
            return Status.SKIPPED;
        }

        List<DslParser.Expr> items = group.items;
        boolean eager = dc.def().isEager();
        boolean failFast = ErrorPolicy.of(node.getErrorPolicy()) == ErrorPolicy.FAIL_FAST;
        Status agg = Status.SUCCESS;

        for (int i = 0; i < items.size(); i++) {
            if (driver.isCancelled(dc)) {
                agg = Status.aggregate(agg, Status.STOPPED);
                break;
            }
            // 走查到第 i 个子才物化(LAZY 建一个驱动一个; EAGER 子节点行创建期已建, childAt 只定位对齐; action 执行到才建)
            NodeInstEntity child = materializer.childAt(dc, node, items, i, eager);
            // 传本组 ctx 作为子节点的直属容器: 上下文父链与实例树同形, 组级共享 config 与使用者
            // 显式挂进组桶的 result 才对子节点可读(引擎不自动向组桶写值)
            agg = Status.aggregate(agg, driver.driveNode(dc, child, ctx));
            if ((agg == Status.FAILED || agg == Status.STOPPED) && failFast) {
                break;   // 组内 FAIL_FAST: 跳过剩余子节点(组外是否继续由上一层序列按就近声明判)
            }
        }
        return agg;
    }
}
