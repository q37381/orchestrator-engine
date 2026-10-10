package com.tesla.ities.process.core.context;

import com.tesla.ities.process.exception.ProcessException;

/**
 * 动作级上下文。父为 LaneContext(分片)或 NodeContext(非分片)。
 *
 * 本版变更(#1): 不再持有 ShardResolverRegistry / resolverType。payload 由 ContextFactory 在建
 * LaneContext 时现场重建一次(不落库), 整条 lane 的 action 复用; ActionContext 只持有这个原始 payload 对象。
 * 取法有两档: shardPayload() 直接转型拿走(常态), shardPayload(Class) 只在需要跨类型转换时走 Jackson。
 */
public class ActionContext extends Context {
    private final String actionInstId;
    private final String actionDefId;
    private final String shardKey;          // 非分片为 null
    private final Object shardPayload;      // 运行期重建的原始 payload; 非分片为 null
    private volatile boolean cancelled;     // 取消钩子, 引擎按流程状态置位

    public ActionContext(Context parent, String actionInstId, String actionDefId,
                         String shardKey, Object shardPayload) {
        super(parent);
        this.actionInstId = actionInstId;
        this.actionDefId = actionDefId;
        this.shardKey = shardKey;
        this.shardPayload = shardPayload;
    }

    public String actionInstId() {
        return actionInstId;
    }
    public String actionDefId() {
        return actionDefId;
    }
    public String shardKey() {
        return shardKey;
    }

    /** 业务长循环自愿检查; 框架不强制 interrupt。 */
    public boolean isCancelled() {
        return cancelled;
    }
    public void cancel() {
        this.cancelled = true;
    }

    /**
     * 直接取回 ShardResolver.buildPayload 交的那个对象: 只做转型, **不走 Jackson 转换**。
     * 类型由赋值目标推: {@code DbShardPayload p = ctx.shardPayload();}
     * 转型不符当场 ClassCastException(不后漂); payload 本身只在本次执行内存在, 绝不落库。
     */
    @SuppressWarnings("unchecked")
    public <P> P shardPayload() {
        return (P) shardPayload;
    }

    /** 需要**转换**时才走这条(resolver 交回的是 Map/JSON 而业务要 POJO), 目标类型由入参给定。 */
    public <P> P shardPayload(Class<P> type) {
        if (shardPayload == null) {
            throw new ProcessException("AU204001");
        }
        return MAPPER.convertValue(shardPayload, type);
    }

    /**
     * 沿父链上溯到所属节点上下文(GROUP 内还会再上一层才是组)。
     * 显式向更高桶挂值的样板: {@code ctx.node().parent().putResult(k, v)} 挂进直属上层容器(组)桶。
     */
    public NodeContext node() {
        Context c = parent;
        while (c != null && !(c instanceof NodeContext)) {
            c = c.parent();
        }
        return (NodeContext) c;
    }

    /** 向上访问各级上下文(用于监听器/引擎内部)。 */
    public Context upward() {
        return parent;
    }
}
