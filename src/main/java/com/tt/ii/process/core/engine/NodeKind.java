package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.context.NodeContext;
import com.tt.ii.process.core.define.DslParser;
import com.tt.ii.process.core.model.NodeType;
import com.tt.ii.process.core.model.Status;

/**
 * 节点类型档案(#3/#5/#7 归口): **一种 NodeType 的全部运行期知识放在同一个类里**——
 * 认领哪个表达式({@link #exprType()})、认领哪个类型({@link #type()})、怎么建行({@link #materialize})、
 * 怎么跑到终态({@link #drive}), 外加 EAGER 时子层跟不跟着预建({@link #materializesChildrenEagerly})。
 *
 * 前身是 {@code NodeStrategy}(驱动) 与 {@code ExprMaterializer}(物化)两张**平行**注册表: 同一个类型的知识
 * 被"动作"切成两半, 新增类型要成对注册两个 Bean, 漏一个要到驱动期才报 AU204304/AU204306。合并成一份档案后
 * 由 {@link NodeKinds} 一次注册、两处分发(Materializer 按表达式分发物化, NodeDriver 按行 type 分发驱动)。
 *
 * 依赖方向(装配期单向, 协作走**方法参数回调**): 档案不在构造期拿 Materializer/NodeDriver/ActionRunner——
 * 驱动期要用的能力由 {@link NodeEnv} 按角色接口分组下传(递归 {@link NodeRecursion}、对齐
 * {@link ChildCursor}、跑 action {@link ActionExecution}、判停 {@link CancellationGate}), 于是档案 →
 * NodeKinds → Materializer → NodeDriver 是一条无环链, 不会出现构造期互相引用。
 *
 * 模型: 流程被单 owner 认领后递归驱动整棵 flow。驱动按**定义顺序前向走查**: 定义表达式作为走查光标,
 * 由 Materializer.rootAt/childAt(EAGER 批量用 ensureRoots)按下标对齐补充进 ctx.inst().getExpr()(transient, 不落库),
 * 档案一律**从上下文行内自取定义**——不再单独传 node 行。已执行过的行由 NodeDriver 幂等跳过,
 * 缺行用行内表达式补物化(LAZY 执行到才逐个建)。
 * 状态用返回值向上传(不用 pending 计数异步 join); 落库仅供观测与进度。
 *
 * ctx = 本节点的 live 上下文(NodeDriver 建一次后贯串传递): **自带实例行** ctx.inst(),
 * 并派生 definition()/nodeRef()/name(); 分片节点直接把它作为 ShardResolver 两阶段解析的入参。
 */
public interface NodeKind {

    /** 本档案负责的节点类型(= 实例行 type 列, 驱动分发键)。 */
    NodeType type();

    /** 本档案负责的 DSL 表达式类型(= 行内走查光标的类, 物化分发键)。 */
    Class<? extends DslParser.Expr> exprType();

    /** EAGER 创建期是否递归预建本节点的**子节点行**。 */
    default boolean materializesChildrenEagerly() {
        return true;
    }

    /**
     * 物化: 表达式 -> node_inst 行(必要时连带 lane/action 行, 全部 CREATED), 返回本节点业务键 nodeInstId。
     * 本方法只建**自己这一行**(不做子层递归): EAGER 创建期的子层递归由 {@link Materializer} 按
     * {@link #materializesChildrenEagerly()} 统一负责; lane/action 两档一律驱动期建。
     * 实例行只存**运行期数据**(状态/结果/业务 config/IF 决策); 监听器链等定义可推导数据不落实例。
     * config 的 PLAN 冻结走 {@link ConfigFreeze}(档案构造期注入), 对齐与递归由 {@link Materializer} 在调用方负责。
     */
    String materialize(DriveContext dc, DslParser.Expr expr, String parentId);

    /**
     * 驱动该节点至终态(SUCCESS/FAILED/SKIPPED/STOPPED)。定义取 ctx.inst().getExpr()。
     * 协作能力一律经 {@link NodeEnv} 的角色接口取用(递归/对齐/跑 action/判停/走查各取所需), 档案不认识实现类;
     * 容器类档案的走查只**声明意图**(env.walk().walkXxx), 不再自己 new 序列。
     *
     * @param env 本次驱动的协作环境(NodeDriver 在驱动点现组)
     * @param ctx 本节点上下文(自带实例行 + 定义 + 节点级 config/params/result/vars/userId)
     */
    Status drive(NodeEnv env, DriveContext dc, NodeContext ctx);
}
