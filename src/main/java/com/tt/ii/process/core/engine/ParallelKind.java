package com.tt.ii.process.core.engine;

import com.tt.ii.process.exception.ProcessException;
import com.tt.ii.process.core.context.NodeContext;
import com.tt.ii.process.core.define.DslParser;
import com.tt.ii.process.core.define.NodeDef;
import com.tt.ii.process.core.model.NodeType;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.mapper.NodeInstMapper;
import com.tt.ii.process.util.IdGenerator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * PARALLEL 档案: 孩子 = 子节点, 全部扇出到 itiesProcessParallelExecutePool 并行, join 汇聚
 * (扇出骨架唯一一份在 {@link WalkLoop}, 池由走查引擎路由; 本档案只声明 FANOUT 一档)。
 *
 * 子表达式由行内走查光标自述({@code node.getExpr().children()} = Parallel.items), 子行与其按下标对齐。
 * 物化: EAGER 创建期预建子节点行(递归点在 Materializer, 本类只建自己这一行);
 * LAZY 走查到第 i 个子才 childAt 建第 i 个(建一个扇出一个); 叶子下的 lane/action 两档一律驱动期建。
 * 每个子任务递归时定义已在子行上。
 *
 * **容器级 guard**(condition_expr): 只有带命名后缀的命名并行才有({@code PARALLEL:checks(a,b)}, condition_expr 落
 *   NodeDef.when), 假则整条并行 SKIPPED、一个子项都不物化/不提交——与 GROUP 同口径(见 NodeGuard)。匿名并行该行恒为
 *   null, 因此这一段对旧行为零影响。
 *
 * 两种形态, 差别只在**关键字后有没有命名后缀**(flow 里 {@code PARALLEL:标签(a,b)}):
 *  - 匿名 {@code PARALLEL(a,b)}: 无 NodeDef 可挂, error_policy 取流程级全局, condition_expr/config 留空(旧行为不变),
 *    nodeDefId/name 留 NULL(视图回落 nodeInstId, 不算命名容器);
 *  - 命名 {@code PARALLEL:checks(a,b)}: 后缀指向 nodes 下的一条纯容器声明, 于是与 {@link GroupKind}
 *    同构地拿到三项容器级能力——自己声明的**生效策略**(节点声明 &gt; 全局 &gt; 默认)、**guard**({@code when},
 *    假则整条并行 SKIPPED)、**config**(PLAN 解析冻结) + 节点级监听器链(由 Materializer.attachCursor 挂载);
 *    定义引用键与展示名落 **node_def_id/name 列**(与 GROUP 同族), 于是"一个容器一行"的判据是 node_def_id 非空, 与 type 无关。
 *    装载期已保证命名后缀与声明成对(DefinitionLoader.validateNamedParallels), 这里只可能拿到 null 或纯容器。
 *
 * FAIL_FAST(默认) 与 CONTINUE_ON_ERROR 的汇聚口径见 {@link WalkLoop}; 终态按 severity 优先级聚合。
 * **本行策略还决定上层要不要跟着停**: 命名容器的声明就是它的生效值, 顶层根序列/GROUP 组内序列据此判
 *   "这一项坏了叫停还是放行"(见 ProcessDefinition.stopsSequence)——匿名并行无声明, 恒回落上一层的口径。
 */
@Component
public class ParallelKind extends ControlNodeKind {

    private final ConfigFreeze freeze;

    public ParallelKind(NodeInstMapper nodeMapper, IdGenerator ids, ConfigFreeze freeze) {
        super(nodeMapper, ids);
        this.freeze = freeze;
    }

    @Override public NodeType type() {
        return NodeType.PARALLEL;
    }

    @Override public Class<? extends DslParser.Expr> exprType() {
        return DslParser.Parallel.class;
    }

    @Override
    public String materialize(DriveContext dc, DslParser.Expr expr, String parentId) {
        DslParser.Parallel par = (DslParser.Parallel) expr;
        NodeDef nd = par.name == null || dc.def() == null ? null : dc.def().node(par.name);
        if (nd == null) {
            return newControlNode(dc, NodeType.PARALLEL, parentId, null);   // 匿名并行: 全局策略, 无 guard/config, 无 nodeDefId/name
        }
        Map<String, Object> cfg = freeze.freezePlan(dc, nd.getConfig());
        return newControlNode(dc, NodeType.PARALLEL, parentId, nd.getWhen(),
                dc.def().effectiveErrorPolicy(nd.getErrorPolicy()).getStatus(), cfg,
                par.name, nd.displayName());
    }

    @Override
    public Status drive(NodeEnv env, DriveContext dc, NodeContext ctx) {
        // 行 type 与本档案认领的表达式形状必须一致: 否则 expr.children() 可能为空, 静默跳过整条并行
        if (!(ctx.inst().getExpr() instanceof DslParser.Parallel)) {
            throw new ProcessException("AU204311", ctx.inst().getNodeInstId());
        }
        // 容器级 guard(命名并行的 when): 假则整条并行免跑, 一个子项都不提交(与 GROUP 同口径)
        if (!NodeGuard.holds(dc, ctx)) {
            return Status.SKIPPED;
        }

        // 子节点的直属容器 = 本 PARALLEL 的 ctx: 上下文父链与实例树(parent_id)保持同形,
        // 于是本容器若嵌在聚合节点里, 组级 config 与使用者显式挂进组桶的 result 对扇出的子节点依然可读。
        // 定位/扇出/汇聚口径都在 LayerWalker.walkChildren(FANOUT) 的声明里(骨架唯一一份)
        return env.walk().walkChildren(dc, env, ctx, ctx.inst().getExpr().children(), WalkMode.FANOUT);
    }
}
