package com.tesla.ities.process.core.spi;

import com.tesla.ities.process.core.context.ProcessContext;
import com.tesla.ities.process.core.model.Status;

import java.util.Map;

/**
 * 流程级监听器(#4): 声明在流程上, 只收 ProcessContext(类型即 scope, 编译期不会与其他层混淆)。
 *
 * 事件只有 before / after 两个切点(#15):
 *  - before(ctx, config): 流程驱动前。抛异常 = 否决(流程 FAILED)。适合初始化/批量插入(如校验项专表)。
 *  - after(ctx, status, config): 流程收尾后, 把最终 status 传入, listener 自行按状态分支。
 * config = 该监听器声明上的配置(创建期固化)。
 *
 * 可读定义: ctx 已挂上钉死版本的流程定义——
 *  {@code ctx.nodes()} 取全部节点定义(Map&lt;String,NodeDef&gt;), {@code ctx.nodeDefinition(id)} 取单个,
 *  {@code ctx.definition()} 取整棵定义(flow 表达式序/setup/setdown/vars/监听器声明)。
 * 因此 before 就能拿到全量拓扑(不需要等节点行物化)。
 *
 * 收敛约定: 同步 inline 执行; 纯副作用监听器须自行 try/catch 吞异常, 否则会否决流程。
 * 按 name() 路由; 多个同层监听器**按声明顺序**执行(不再有 order 字段)。
 */
public interface ProcessListener {

    String name();

    default void before(ProcessContext ctx, Map<String, Object> config) throws Exception { }

    default void after(ProcessContext ctx, Status status, Map<String, Object> config) throws Exception { }
}
