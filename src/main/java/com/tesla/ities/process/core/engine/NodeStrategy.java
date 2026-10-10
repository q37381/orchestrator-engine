package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.core.context.NodeContext;
import com.tesla.ities.process.core.model.NodeType;
import com.tesla.ities.process.core.model.Status;

/**
 * 节点驱动策略(#3)。一种 NodeType 一个策略: PARALLEL(并行) / IF(条件) / ACTION(叶子)。
 * 顶层顺序串行由引擎直接逐个驱动 root 节点承担(flow 扁平化, 无 SERIAL 虚拟类型)。
 *
 * 模型: 流程被单 owner 认领后递归驱动整棵 flow。驱动按**定义顺序前向走查**:
 * 定义表达式作为走查光标, 由 Materializer.rootAt/childAt(EAGER 批量用 ensureRoots)按下标对齐补充进
 * ctx.inst().getExpr()(transient, 不落库), 策略一律**从上下文行内自取定义**——不再单独传 node 行。
 * 已执行过的行由 NodeDriver 幂等跳过, 缺行用行内表达式补物化(LAZY 执行到才逐个建)。
 * 真正的子节点驱动 / 动作序列执行回调到 NodeDriver(#8)——策略与 ExecutionEngine 不再相互依赖。
 * 状态用返回值向上传(不再用 pending 计数异步 join); 落库仅供观测与进度。
 *
 * ctx = 本节点的 live 上下文(NodeDriver 建一次后贯串传递): **自带实例行** ctx.inst(),
 * 并派生 definition()/nodeRef()/name(); 分片节点直接把它作为 ShardResolver 两阶段解析的入参。
 */
public interface NodeStrategy {

    NodeType type();

    /**
     * 驱动该节点至终态(SUCCESS/FAILED/SKIPPED/STOPPED)。定义取 ctx.inst().getExpr(); 递归经 driver 回调。
     *
     * @param ctx 本节点上下文(自带实例行 + 定义 + 节点级 config/params/result/vars/userId)
     */
    Status drive(NodeDriver driver, DriveContext dc, NodeContext ctx);
}
