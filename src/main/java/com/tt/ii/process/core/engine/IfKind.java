package com.tt.ii.process.core.engine;

import com.tt.ii.process.exception.ProcessException;
import com.tt.ii.process.constants.ReservedKeys;
import com.tt.ii.process.core.context.NodeContext;
import com.tt.ii.process.core.define.DslParser;
import com.tt.ii.process.core.model.NodeType;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.domain.NodeInstEntity;
import com.tt.ii.process.mapper.NodeInstMapper;
import com.tt.ii.process.util.IdGenerator;
import com.tt.ii.process.util.Json;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IF 档案: 评估条件 -> 惰性物化选中分支 -> 内联驱动该分支(单分支, 串行语义)。
 * 决策(__chosen + __skippedBranch)持久化进 config(运行时数据), 恢复时复用不重算。
 * 未选支**不驱动**, 但会在选中支跑完后补一行**已跳过**的节点行为它留痕(见
 * {@link #markUntakenBranch})——否则库里只剩一行匿名的 IF,
 * 想知道"跳掉了哪一支"得拿着 flow 文本自己去对。
 *
 * 分支表达式来自行内走查光标 node.expr()(不反查), 子行按下标对齐(第 0 行 ↔ 选中分支,
 * 第 1 行 ↔ 未选支留痕行), 对齐时选中分支表达式已补充进子行, 递归驱动不再传定义。
 * 无选中分支(条件假且无 else) -> SKIPPED(对控制流 = 成功完成)。
 *
 * **EAGER 也不预建分支**(materializesChildrenEagerly = false): 选哪支要到驱动期决策才算得出,
 * 两支**都当候选建**就会留下永远 CREATED 的孤儿行。所以条件表达式树里的节点级分母在 IF 之下不保证开跑前齐全;
 * 留痕行是**已跳过**的终态行(不是 CREATED 占位), 且从不驱动, 因此不踩这条线。
 *
 * 注意 condition_expr 的语义与其他类型不同: IF 那一列装的是**分支选择条件**, 不是"要不要跑"的 guard,
 * 所以本档案不走 {@link NodeGuard}(那是叶子/组/命名并行的口径)。
 */
@Component
public class IfKind extends ControlNodeKind {

    public IfKind(NodeInstMapper nodeMapper, IdGenerator ids) {
        super(nodeMapper, ids);
    }

    @Override public NodeType type() {
        return NodeType.IF;
    }

    @Override public Class<? extends DslParser.Expr> exprType() {
        return DslParser.If.class;
    }

    /** 分支恒惰性: 决策要到驱动期才算得出, EAGER 也不预建(未选支本就不该有行)。 */
    @Override public boolean materializesChildrenEagerly() {
        return false;
    }

    @Override
    public String materialize(DriveContext dc, DslParser.Expr expr, String parentId) {
        return newControlNode(dc, NodeType.IF, parentId, ((DslParser.If) expr).condition);   // 条件入 condition_expr
    }

    @Override
    public Status drive(NodeEnv env, DriveContext dc, NodeContext ctx) {
        NodeInstEntity node = ctx.inst();
        // 分发键双轨的自卫(与叶子/聚合同口径): 行 type 与本档案认领的表达式形状必须一致, 不符当场报清是哪一行
        if (!(node.getExpr() instanceof DslParser.If)) {
            throw new ProcessException("AU204310", node.getNodeInstId());
        }
        DslParser.If iff = (DslParser.If) node.getExpr();
        Map<String, Object> cfg = Json.readMap(node.getConfig());
        boolean settle = dc.isSettle();

        // 决策: 已持久化则复用, 否则现场评估(求值在**流程级上下文**上: 条件读的是流程级现场)。
        // 收尾轮不重算决策、也不补建分支行: 没决策/没选中支行说明没走到可收尾的状态, 直接 STOPPED。
        boolean cond;
        if (cfg.containsKey(ReservedKeys.CHOSEN)) {
            cond = "then".equals(cfg.get(ReservedKeys.CHOSEN));
        } else if (settle) {
            return Status.STOPPED;
        } else {
            cond = dc.evaluator().eval(node.getConditionExpr(), dc.processContext());
            cfg.put(ReservedKeys.CHOSEN, cond ? "then" : "else");
            DslParser.Expr untaken = cond ? iff.elseBranch : iff.thenBranch;
            if (untaken != null) {
                cfg.put(ReservedKeys.SKIPPED_BRANCH, describe(untaken));   // 一行就问出跳过了哪一支
            }
            nodeMapper.updateConfig(node.getNodeInstId(), Json.write(cfg), ctx.userId());
        }

        DslParser.Expr chosen = cond ? iff.thenBranch : iff.elseBranch;
        DslParser.Expr untaken = cond ? iff.elseBranch : iff.thenBranch;
        if (chosen == null) {
            if (!settle) {
                markUntakenBranch(env, dc, ctx, null, untaken);   // 无 else 可走: 被跳过的就是 then 支, 照样留一行
            }
            return Status.SKIPPED;     // 无选中分支
        }

        // 惰性物化选中分支(IF 分支总是执行到才建, 即便流程是 EAGER); 重入已存在则直接复用。
        // 收尾轮只取已存在行: 不补建分支, 也不补未选支留痕行。
        List<DslParser.Expr> branch = Collections.singletonList(chosen);
        NodeInstEntity child = settle
                ? env.children().childAtExisting(dc, node, branch, 0, false)
                : env.children().childAt(dc, node, branch, 0, dc.def().isEager());
        if (child == null) {
            return Status.STOPPED;   // 收尾轮: 选中支从未物化, 无现场可收
        }
        Status outcome = env.nodes().driveNode(dc, child, ctx);
        // 未选支的留痕行**必须在选中支之后**建: 子行按 auto_id 与分支表下标对齐, 选中支恒占第 0 格,
        // 先建留痕行就会把选中支挤到第 1 格 —— 重入时 childAt(.., 0) 取到的正是这行 skipped, 选中的分支永远不跑。
        if (!settle) {
            markUntakenBranch(env, dc, ctx, chosen, untaken);
        }
        return outcome;
    }

    /**
     * 把未选支物化成一行 **SKIPPED** 节点行, 只为可诊断(谁被跳过了、它叫什么)。
     * 不驱动、不建其子层与 action: 走 {@link ChildCursor#childAt} 而不自己拼行, 于是类型分派/展示名快照/
     * 就近 config 仍由那一支自己的档案说(IF 不必认识所有节点类型), eager 恒传 false 就是"不往深处建"。
     * 插行与翻状态是两条语句, 但翻状态走 CAS(created -&gt; skipped), 所以重入既不会重复插行
     * (行已在最后一格, childAt 直接复用), 也不会踩掉已成终态的行。
     *
     * @param chosen 选中支(无选中支时传 null, 此时留痕行就是第 0 个子行)
     * @param untaken 未选支(null = 什么都没被跳过)
     */
    private void markUntakenBranch(NodeEnv env, DriveContext dc, NodeContext ctx,
                                   DslParser.Expr chosen, DslParser.Expr untaken) {
        if (untaken == null) {
            return;
        }
        List<DslParser.Expr> ordered = chosen == null
                ? Collections.singletonList(untaken)
                : Arrays.asList(chosen, untaken);       // 选中支在前, 留痕行恒是最后一格
        NodeInstEntity marker = env.children().childAt(dc, ctx.inst(), ordered, ordered.size() - 1, false);
        Map<String, Object> res = new LinkedHashMap<>(Json.readMap(marker.getResult()));
        res.put(ReservedKeys.SKIP_REASON, ReservedKeys.BRANCH_NOT_TAKEN);
        nodeMapper.updateState(marker.getNodeInstId(), Status.CREATED.getStatus(),
                Status.SKIPPED.getStatus(), Json.write(res), null, ctx.userId());
    }

    /** 未选支的自述文本(与 flow 里写的形状一致: 叶子=引用键, 容器=关键字(...)), 供 config.__skippedBranch 回查。 */
    private static String describe(DslParser.Expr expr) {
        StringBuilder sb = new StringBuilder();
        expr.appendFlow(sb);
        return sb.toString();
    }
}
