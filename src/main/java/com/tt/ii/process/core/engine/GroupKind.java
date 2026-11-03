package com.tt.ii.process.core.engine;

import com.tt.ii.process.exception.ProcessException;
import com.tt.ii.process.core.context.NodeContext;
import com.tt.ii.process.core.define.DslParser;
import com.tt.ii.process.core.define.NodeDef;
import com.tt.ii.process.core.model.NodeType;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.domain.NodeInstEntity;
import com.tt.ii.process.mapper.NodeInstMapper;
import com.tt.ii.process.util.IdGenerator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GROUP(聚合节点)档案: 命名**串行**容器 —— 子表达式按声明顺序逐个走查(定位 + 驱动),
 * 走查口径全在 {@link LayerWalk#walkChildren} 的 SERIAL 档声明里(骨架唯一一份在 {@link WalkLoop})。
 *
 * 存在的意义: 让"一个校验项 = 一个节点"成立。校验项内部的多个步骤(如"取源端"不分片 + "逐片对比"分片并行)
 * 各自是一个子节点, 但对外只暴露聚合节点这一个名字: flow 里写一项、进度里一项一行、errorPolicy/组级 guard 一处声明。
 *
 * 三个容器级语义(与 PARALLEL/IF 的差别):
 *  1. **组级 guard**: condition_expr 落的是 NodeDef.when, 假则整组 SKIPPED(与叶子节点的 guard 同口径, 见 NodeGuard)。
 *  2. **组级共享靠显式放置**: 引擎**不再**把子节点 result 冒泡进组桶——各层桶只装自己层的值。
 *     子节点要交给兄弟/上层, 由使用者在 handler/监听器里自己放: {@code ctx.node().parent().putResult(k, v)}
 *     挂进组桶、{@code ctx.upward().putResult(k, v)} 挂进所属节点/lane 桶、{@code ctx.putVar(k, v)} 写流程级 vars、
 *     或写业务专表; 后续子节点写 {@code ${result.<k>}} 沿父链上溯即可读到显式挂进组桶的值。组桶随 finishNode 落
 *     process_node_inst.result, 所以显式放置的值崩溃重驱动后仍在。
 *  3. **组级 setup/setdown 段**(v1.3): 声明在节点上与 group: 同级, 包住**整个子节点序列**——
 *     组 setup -> 子节点逐个走查 -> 组 setdown(finally); 与节点级段同语义(段坏跳主体、收尾必达、成对重臂、
 *     repeatSegments), 主体是子节点序列而非 MAIN 动作; 无段组零开销退回普通串行走查。
 *
 * 不支持: GROUP 自己带 shard(语义会变成"每 lane 跑一遍整个子流程", 与 lane 容器冲突); 分片永远在叶子子节点上。
 *
 * 组内序列的**收敛口径**只看组行自己的生效值(组声明 &gt; 流程级全局 &gt; 默认 FAIL_FAST), 组内子节点的声明只作用于
 * 它自己容器内。**组外**(根序列)遇这条组坏要不要继续, 由上一层序列按就近声明判定(见 ProcessDefinition.stopsSequence)。
 *
 * 物化侧与**匿名** PARALLEL/IF 的三点差别(都因为 GROUP **在 nodes 下有声明**, 行上能挂 NodeDef;
 * 带命名后缀的 {@code PARALLEL:标签(a,b)} 走 {@link ParallelKind} 拿到同样三项):
 *  1. error_policy 取自己声明的生效值(ProcessDefinition.effectiveErrorPolicy), 不吃全局——
 *     于是"某个校验项遇错即停、另一项逐项跑完"能各自表达;
 *  2. condition_expr 落 NodeDef.when = **组级 guard**: 假则整组 SKIPPED(一个校验项整体免跑);
 *  3. 定义引用键与展示名落 **node_def_id/name 列**(不再塞 config 保留键), 供进度视图直接读行展示。
 *
 * 手写 {@code GROUP:名字(...)} 而 nodes 下无同名声明时 nd 为 null: 退化成匿名串行容器(全局策略、无 guard、config 空),
 * 但 node_def_id 仍落声明键(那是既有"命名容器自述"口径), name 留 null 由视图回落 node_def_id。
 */
@Component
public class GroupKind extends ControlNodeKind {

    private final ConfigFreeze freeze;

    public GroupKind(NodeInstMapper nodeMapper, IdGenerator ids, ConfigFreeze freeze) {
        super(nodeMapper, ids);
        this.freeze = freeze;
    }

    @Override public NodeType type() {
        return NodeType.GROUP;
    }

    @Override public Class<? extends DslParser.Expr> exprType() {
        return DslParser.Group.class;
    }

    @Override
    public String materialize(DriveContext dc, DslParser.Expr expr, String parentId) {
        DslParser.Group group = (DslParser.Group) expr;
        NodeDef nd = dc.def() == null ? null : dc.def().node(group.name);

        Map<String, Object> cfg = nd == null ? new LinkedHashMap<>() : freeze.freezePlan(dc, nd.getConfig());

        return newControlNode(dc, NodeType.GROUP, parentId, nd == null ? null : nd.getWhen(),
                nd == null ? null : dc.def().effectiveErrorPolicy(nd.getErrorPolicy()).getStatus(), cfg,
                group.name, nd == null ? null : nd.displayName());
    }

    @Override
    public Status drive(NodeEnv env, DriveContext dc, NodeContext ctx) {
        NodeInstEntity node = ctx.inst();
        if (!(node.getExpr() instanceof DslParser.Group)) {
            throw new ProcessException("AU204303", node.getNodeInstId());
        }
        // 组级 guard(when): 假则整组 SKIPPED(与叶子的节点级 guard 同口径)
        if (!NodeGuard.holds(dc, ctx)) {
            return Status.SKIPPED;
        }

        // 本档案只声明"这一层怎么走": **组回路**(三段编排)——无段组零开销回落串行子节点走查;
        // 段口径/子节点定位/驱动/闸门/收敛全在走查引擎的声明里(子行走查到才建; EAGER 只对齐; 组行策略收敛)。
        // 传本组 ctx 作为子节点的直属容器: 上下文父链与实例树同形, 组级共享 config 与使用者
        // 显式挂进组桶的 result 才对子节点可读(引擎不自动向组桶写值)
        return env.walk().walkGroupCircuit(dc, env, ctx, node.getExpr().children());
    }
}
