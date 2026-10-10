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
 * GROUP(聚合节点)物化: 建一条 GROUP 容器行; EAGER 时递归物化子层(LAZY 由 GroupStrategy 走查补物化)。
 *
 * 与**匿名** PARALLEL/IF 的三点差别(都因为 GROUP **在 nodes 下有声明**, 行上能挂 NodeDef;
 * 带命名后缀的 {@code PARALLEL:标签(a,b)} 走 {@link ParallelExprMaterializer} 拿到同样三项):
 *  1. error_policy 取自己声明的生效值(ProcessDefinition.effectiveErrorPolicy), 不吃全局——
 *     于是"某个校验项遇错即停、另一项逐项跑完"能各自表达;
 *  2. condition_expr 落 NodeDef.when = **组级 guard**: 假则整组 SKIPPED(一个校验项整体免跑);
 *  3. 定义引用键与展示名落 **node_def_id/name 列**(不再塞 config 保留键), 供进度视图分组直接读行展示。
 *
 * 手写 {@code GROUP:名字(...)} 而 nodes 下无同名声明时 nd 为 null: 退化成匿名串行容器(全局策略、无 guard、config 空),
 * 但 node_def_id 仍落声明键(那是既有"命名容器自述"口径), name 留 null 由视图回落 node_def_id。
 */
@Component
public class GroupExprMaterializer extends ControlExprMaterializer {

    public GroupExprMaterializer(NodeInstMapper nodeMapper, IdGenerator ids) {
        super(nodeMapper, ids);
    }

    @Override
    public Class<? extends DslParser.Expr> exprType() {
        return DslParser.Group.class;
    }

    @Override
    public String materialize(Materializer ctx, DriveContext dc, DslParser.Expr expr, String parentId, boolean eager) {
        DslParser.Group group = (DslParser.Group) expr;
        NodeDef nd = dc.def() == null ? null : dc.def().node(group.name);

        Map<String, Object> cfg = nd == null
                ? new LinkedHashMap<>()
                : Materializer.resolvePlan(dc, new LinkedHashMap<>(Materializer.asMap(nd.getConfig())));

        String id = newControlNode(dc, NodeType.GROUP, parentId, nd == null ? null : nd.getWhen(),
                nd == null ? null : dc.def().effectiveErrorPolicy(nd.getErrorPolicy()).getStatus(), cfg,
                group.name, nd == null ? null : nd.displayName());

        if (eager) {
            for (DslParser.Expr item : group.items) {
                ctx.materializeNode(dc, item, id, true);
            }
        }
        return id;
    }
}
