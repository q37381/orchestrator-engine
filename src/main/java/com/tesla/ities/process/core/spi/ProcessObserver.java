package com.tesla.ities.process.core.spi;

import com.tesla.ities.process.core.context.ActionContext;
import com.tesla.ities.process.core.context.NodeContext;
import com.tesla.ities.process.core.context.ProcessContext;
import com.tesla.ities.process.core.model.Status;

/**
 * 全局编排观察者: 实现本接口并注册为 Spring Bean, 自动对所有流程/节点/action 生效(无需 YAML 声明)。
 *
 * 通过方法名区分 scope:
 *  - beforeProcess / afterProcess: 流程级
 *  - beforeNode / afterNode: 节点级
 *  - beforeAction / afterAction: 动作级
 *
 * 与 Listener 的区别:
 *  - Listener: 声明在 YAML 上, 按 name() 路由, 带 config, before 可否决。
 *  - Observer: 全局自动生效, 无 name/config, 纯观察(不可否决), 适合审计/指标/日志等横切关注点。
 *
 * 收敛约定: 同步 inline 执行; 所有异常内部吞掉, 实现方无需自行 try/catch。
 */
public interface ProcessObserver {

    // ===================== Process =====================

    default void beforeProcess(ProcessContext ctx) { }

    default void afterProcess(ProcessContext ctx, Status status) { }

    // ===================== Node =====================

    default void beforeNode(NodeContext ctx) { }

    default void afterNode(NodeContext ctx, Status status) { }

    // ===================== Action =====================

    default void beforeAction(ActionContext ctx) { }

    default void afterAction(ActionContext ctx, Status status) { }
}
