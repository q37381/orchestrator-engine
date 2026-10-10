package com.tesla.ities.process.core.spi;

import com.tesla.ities.process.core.context.ActionContext;
import com.tesla.ities.process.core.model.Status;

/**
 * 动作处理器 SPI。一个 action 对应一个实现, 按 name() 路由(#11/#17)。
 *
 * 收敛(#10): handler 只返回状态, 不再有 ActionResult/NextDirective。
 *  - 返回 SUCCESS / SKIPPED / STOPPED 等; 抛异常 = FAILED(引擎捕获, 异常信息写 result.__error)。
 *  - 要存结果: 在 handler 内 ctx.putResult(...) / ctx.putVar(...), 引擎跑完持久化回对应行。
 *  - 条件跳过: 用声明式 when 守卫(定义层), 不在 handler 里做动态跳转。
 *
 * 契约: at-least-once 执行 -> handle 必须幂等(重跑安全)。
 */
public interface ActionHandler {

    /** 唯一名称, 对应定义里 action 的 handler 字段。 */
    String name();

    /** 执行业务逻辑, 返回终态(通常为 SUCCESS)。抛异常 = 失败。 */
    Status handle(ActionContext ctx) throws Exception;

    /** 补偿(向后恢复)。默认空; 仅可补偿的 handler 重写。 */
    default void compensate(ActionContext ctx) throws Exception { }

    /** 是否可补偿。回退时不可补偿的步骤会停下并告警。 */
    default boolean compensable() {
        return false;
    }

}
