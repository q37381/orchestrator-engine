package com.tesla.ities.process.core.spi;

import com.tesla.ities.process.core.context.NodeContext;

import java.util.List;

/**
 * 分片解析 SPI, 两阶段(按 name() 路由, #11/#17):
 *  阶段1 resolveShardKeys: 物化该分片节点时跑一次, 结果落库(决策, 要稳定/幂等)。
 *  阶段2 buildPayload: 每次执行/恢复时按 shardKey 现场重建(不落库)。
 *
 * ctx = **本分片节点的 NodeContext**(与其他 SPI 一致: ActionHandler 收 ActionContext, NodeListener 收 NodeContext),
 * 节点级与流程级参数都在这条链上:
 *  - 节点级: getConfig(k)/config()(物化后落库的节点 config)、definition()(声明定义: shard/laneExecution/errorPolicy/原始 config)、
 *            nodeRef()、name()、inst()(实例行);
 *  - 流程级: process() 取 ProcessContext——procInstId()/vars()/userId()/definition()(整棵定义),
 *            config/result 直接 resolveConfig(k)/resolveResult(k) 逐级上溯(已解析成对象, 不需再读 JSON);
 *            创建输入就存在流程级 config 桶里(params 不落库)。
 * 所以同一 resolver 被多个分片节点引用时, 各节点拿到的是自己的 ctx。
 *
 * 注: 补偿(rollback)路径无走查对齐, definition()/nodeRef() 可能为 null, 依赖定义的解析器需做退化处理。
 */
public interface ShardResolver {

    String name();

    /** 阶段1: 解析出本节点的全部分片键。必须幂等。 */
    List<String> resolveShardKeys(NodeContext ctx);

    /** 阶段2: 按 shardKey 重建强类型 payload。必须幂等且能独立重建; 不落库。 */
    Object buildPayload(String shardKey, NodeContext ctx);
}
