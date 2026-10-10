package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.core.define.DslParser;
import com.tesla.ities.process.core.define.NodeDef;
import com.tesla.ities.process.core.model.NodeType;
import com.tesla.ities.process.mapper.NodeInstMapper;
import com.tesla.ities.process.util.IdGenerator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PARALLEL 物化: 建 PARALLEL 控制行; EAGER 时递归物化子层(LAZY 由 ParallelStrategy 走查补物化)。
 *
 * 两种形态, 差别只在**关键字后有没有命名后缀**(flow 里 {@code PARALLEL:标签(a,b)}):
 *  - 匿名 {@code PARALLEL(a,b)}: 无 NodeDef 可挂, error_policy 取流程级全局, condition_expr/config 留空(旧行为不变),
 *    nodeDefId/name 留 NULL(视图回落 nodeInstId, 不算命名容器);
 *  - 命名 {@code PARALLEL:checks(a,b)}: 后缀指向 nodes 下的一条纯容器声明, 于是与 {@link GroupExprMaterializer}
 *    同构地拿到三项容器级能力——自己声明的**生效策略**(节点声明 > 全局 > 默认)、**guard**({@code when},
 *    假则整条并行 SKIPPED)、**config**(PLAN 解析冻结) + 节点级监听器链(由 Materializer.attachCursor 挂载);
 *    定义引用键与展示名落 **node_def_id/name 列**(与 GROUP 同族), 于是"一个容器一行"的判据是 node_def_id 非空, 与 type 无关。
 *    装载期已保证命名后缀与声明成对(DefinitionLoader.validateNamedParallels), 这里只可能拿到 null 或纯容器。
 */
@Component
public class ParallelExprMaterializer extends ControlExprMaterializer {

    public ParallelExprMaterializer(NodeInstMapper nodeMapper, IdGenerator ids) {
        super(nodeMapper, ids);
    }

    @Override
    public Class<? extends DslParser.Expr> exprType() {
        return DslParser.Parallel.class;
    }

    @Override
    public String materialize(Materializer ctx, DriveContext dc, DslParser.Expr expr, String parentId, boolean eager) {
        DslParser.Parallel par = (DslParser.Parallel) expr;
        NodeDef nd = par.name == null || dc.def() == null ? null : dc.def().node(par.name);

        String id;
        if (nd == null) {
            id = newControlNode(dc, NodeType.PARALLEL, parentId, null);   // 匿名并行: 全局策略, 无 guard/config, 无 nodeDefId/name
        } else {
            Map<String, Object> cfg = Materializer.resolvePlan(dc, new LinkedHashMap<>(Materializer.asMap(nd.getConfig())));
            id = newControlNode(dc, NodeType.PARALLEL, parentId, nd.getWhen(),
                    dc.def().effectiveErrorPolicy(nd.getErrorPolicy()).getStatus(), cfg,
                    par.name, nd.displayName());
        }

        if (eager) {
            for (DslParser.Expr item : par.items) {
                ctx.materializeNode(dc, item, id, true);
            }
        }
        return id;
    }
}
