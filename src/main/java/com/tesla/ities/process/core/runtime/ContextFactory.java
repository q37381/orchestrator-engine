package com.tesla.ities.process.core.runtime;

import com.tesla.ities.process.core.context.*;
import com.tesla.ities.process.core.spi.ShardResolverRegistry;
import com.tesla.ities.process.domain.ActionInstEntity;
import com.tesla.ities.process.domain.LaneEntity;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.domain.ProcessInstEntity;
import com.tesla.ities.process.util.Json;
import org.springframework.stereotype.Component;

/**
 * 上下文工厂: 从持久化行重建 ProcessContext/NodeContext/LaneContext/ActionContext。
 *
 * 本版变更:
 *  - ProcessContext 额外加载 process_inst.vars(运行期变量空间, #7)与 creator_id(当前用户, #1);
 *    已落库的 result 走 putAllResults 灌入(**不置脏**, 否则驱动一次就把原值刷回去)。
 *  - NodeContext 直接携带实例行(行内含对齐期挂载的定义与走查光标), 展示名由定义解析。
 *  - 持久化只认 config/result(流程层的 config 就是创建输入): **四张表都不建 params 列**,
 *    params 桶只装当下驱动里 putParams 的运行期参数, 不从库重建。
 *  - shard payload 由 buildPayload(NodeContext, shardKey) 在建 action 上下文时重建一次(不落库, #1),
 *    整条 lane 的 action 复用; ActionContext 只持有这个原始对象。
 */
@Component
public class ContextFactory {

    private final ShardResolverRegistry shardRegistry;

    public ContextFactory(ShardResolverRegistry shardRegistry) {
        this.shardRegistry = shardRegistry;
    }

    public ProcessContext process(ProcessInstEntity p) {
        ProcessContext c = new ProcessContext(p.getProcInstId());
        c.setUserId(p.getCreatorId());               // 创建流程时传入的 userId(各级 creator_id 来源)
        c.putAllVars(Json.readMap(p.getVars()));     // 运行期变量空间
        loadConfig(c, p.getConfig());                // 流程层落库的配置桶就叫 config(= 创建输入)
        c.putAllResults(Json.readMap(p.getResult()));
        return c;
    }

    /**
     * 建节点级上下文。parent = **直属上层容器**: 顶层节点与匿名控制容器(PARALLEL/IF)的子节点传 ProcessContext;
     * 聚合节点(GROUP)的子节点传组的 NodeContext(上下文父链与实例树同形, 组级 config 与显式挂进组桶的
     * result 对子节点可读; 引擎不自动向容器桶写值, 见 GroupStrategy)。
     */
    public NodeContext node(Context parent, NodeInstEntity n) {
        NodeContext c = new NodeContext(parent, n);          // 上下文直接带行(定义/走查光标都在行上)
        loadConfig(c, n.getConfig());
        loadResult(c, n.getResult());
        return c;
    }

    public LaneContext lane(NodeContext parent, LaneEntity l) {
        LaneContext c = new LaneContext(parent, l.getLaneId(), l.getShardKey());
        loadConfig(c, l.getConfig());
        loadResult(c, l.getResult());
        return c;
    }

    public ActionContext action(Context parent, ActionInstEntity a, Object shardPayload) {
        ActionContext c = new ActionContext(parent, a.getActionInstId(), a.getActionDefId(),
                a.getShardKey(), shardPayload);
        loadConfig(c, a.getConfig());
        loadResult(c, a.getResult());
        return c;
    }

    /**
     * 现场重建分片 payload(阶段2), 不落库。
     * 无 shardKey(非分片 action) / 节点未声明 resolver 均返回 null——resolver 名从上下文行上自取。
     */
    public Object buildPayload(NodeContext node, String shardKey) {
        if (node == null || shardKey == null) {
            return null;
        }
        String resolverName = node.inst().getShardResolver();
        if (resolverName == null) {
            return null;
        }
        return shardRegistry.get(resolverName).buildPayload(shardKey, node);
    }

    /**
     * 行 JSON -> 上下文的 config 桶(四张表同语义, 已解析成 Map, SPI 不需要再读列)。
     * params 不在此列: 它无持久化来源, 只能由当下驱动里的 ctx.putParams 写入。
     */
    private void loadConfig(Context c, String config) {
        Json.readMap(config).forEach(c::putConfig);
    }

    /** 行 JSON -> 上下文的 result 桶(流程级重建不走这里, 见 ProcessContext.putAllResults)。 */
    private void loadResult(Context c, String result) {
        Json.readMap(result).forEach(c::putResult);
    }
}
