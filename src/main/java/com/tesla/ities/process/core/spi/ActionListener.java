package com.tesla.ities.process.core.spi;

import com.tesla.ities.process.core.context.ActionContext;
import com.tesla.ities.process.core.model.Status;

import java.util.Map;

/**
 * 动作级监听器(#4): 声明在 action 上, 只收 ActionContext(类型即 scope)。
 *  - before(ctx, config): action 执行前。抛异常 = 否决(action FAILED)。适合鉴权/参数校验。
 *  - after(ctx, status, config): action 执行后, 传入最终 status。适合回写校验项专表状态。
 *
 * 同步 inline; 纯副作用须自吞异常。按 name() 路由; 多个同层监听器**按声明顺序**执行。
 */
public interface ActionListener {

    String name();

    default void before(ActionContext ctx, Map<String, Object> config) throws Exception { }

    default void after(ActionContext ctx, Status status, Map<String, Object> config) throws Exception { }
}
