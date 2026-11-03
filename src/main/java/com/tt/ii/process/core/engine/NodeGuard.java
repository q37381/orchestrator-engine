package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.context.NodeContext;

/**
 * 节点/容器级 guard: 实例行 {@code condition_expr} 落的是定义侧 {@code NodeDef.when} ——
 * 没写 = 恒通过; 写了且**在本节点上下文上**求值为假 = 整个节点免跑(SKIPPED, 对控制流算成功完成)。
 *
 * ACTION 叶子、GROUP 组、命名 PARALLEL 三条同口径(以前是三份逐字相同的判断, 收这一处)。
 * **IF 不在其列**: 它那一列装的是分支选择条件(选 then 还是 else), 与"要不要跑"是两回事, 别顺手并进来。
 *
 * 求值口径与 action 级 guard({@code ActionDef.when}, 在 ActionContext 上求值)一致:
 * vars/params/result 沿父链逐级上溯, 所以同一表达式在节点层与动作层读到的是同一份现场。
 */
public final class NodeGuard {

    private NodeGuard() {
    }

    /** 本节点的 guard 是否成立(成立才继续往下跑)。 */
    public static boolean holds(DriveContext dc, NodeContext ctx) {
        String expr = ctx.inst().getConditionExpr();
        return expr == null || expr.isEmpty() || dc.evaluator().eval(expr, ctx);
    }
}
