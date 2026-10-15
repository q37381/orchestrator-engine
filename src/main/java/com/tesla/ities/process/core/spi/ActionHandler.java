package com.tesla.ities.process.core.spi;

import com.tesla.ities.process.core.context.ActionContext;
import com.tesla.ities.process.core.model.Status;

/**
 * 动作处理器 SPI。一个 action 对应一个实现, 按 name() 路由(#11/#17)。
 *
 * 收敛(#10): handler 只返回状态, 不再有 ActionResult/NextDirective。
 *  - 返回终态 SUCCESS / SKIPPED / STOPPED / FAILED; 抛异常 = FAILED(引擎捕获, 异常信息写 result.__error)。
 *  - 返回**让渡态** PENDING / PAUSED / SUSPENDED = 本轮让渡: 引擎当场退出驱动(后续段/根不跑、setdown
 *    不跑、容器行不落终态), 流程行落该状态等重入——pending 由调度器重捞、paused 由人工 resume、
 *    suspended 停驻(本期无唤醒入口); 重入时该行重跑(handle 必须幂等)。
 *    running/created 不是合法返回值(当场按 FAILED 处理)。
 *  - 要存结果: 在 handler 内 ctx.putResult(...) / ctx.putVar(...), 引擎跑完持久化回对应行。
 *  - 条件跳过: 用声明式 when 守卫(定义层), 不在 handler 里做动态跳转。
 *
 * 契约: at-least-once 执行 -> handle 必须幂等(重跑安全)。
 */
public interface ActionHandler {

    /** 唯一名称, 对应定义里 action 的 handler 字段。 */
    String name();

    /**
     * 执行业务逻辑, 返回终态(通常为 SUCCESS)或让渡态(pending/paused/suspended: 本轮退出等重入,
     * 见类注释)。抛异常 = 失败。
     */
    Status handle(ActionContext ctx) throws Exception;

    /** 补偿(向后恢复)。默认空; 仅可补偿的 handler 重写。 */
    default void compensate(ActionContext ctx) throws Exception { }

    /**
     * 向前重放(恢复现场): 回退把副作用撤销后, 重试重新驱动之前引擎按执行顺序调本方法把它**重新施加**回来,
     * 于是失败节点能在恢复好的现场上续跑(而非从流程头重跑)。默认直接委托 {@link #handle}:
     * handle 幂等即天然可重放(重跑一次 = 把撤销掉的现场再立起来)。现场凭据(连接串/凭据等)在本方法内
     * 经 {@code ctx.resolveResult/resolveVar} 或查外部系统现取, 引擎不额外传参、也不引入 restoreListeners。
     * 只有被成功补偿过(compensate 生效)的 action 才会被重放, 故无需重写者自行判"要不要恢复"。
     */
    default void restore(ActionContext ctx) throws Exception {
        handle(ctx);
    }

    /** 是否可补偿。回退时不可补偿的步骤会停下并告警。 */
    default boolean compensable() {
        return false;
    }

}
