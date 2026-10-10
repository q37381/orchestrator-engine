package com.tesla.ities.process.core.spi;

import com.tesla.ities.process.core.context.NodeContext;
import com.tesla.ities.process.core.model.Status;

import java.util.Map;

/**
 * 节点级监听器(#4): 声明在节点上, 只收 NodeContext(类型即 scope)。
 *  - before(ctx, config): 节点驱动前。抛异常 = 否决(节点 FAILED)。
 *  - after(ctx, status, config): 节点收尾后, 传入最终 status。
 *
 * 同步 inline; 纯副作用须自吞异常。按 name() 路由; 多个同层监听器**按声明顺序**执行。
 */
public interface NodeListener {

    String name();

    default void before(NodeContext ctx, Map<String, Object> config) throws Exception { }

    default void after(NodeContext ctx, Status status, Map<String, Object> config) throws Exception { }
}
