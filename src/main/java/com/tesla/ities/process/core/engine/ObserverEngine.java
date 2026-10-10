package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.core.context.ActionContext;
import com.tesla.ities.process.core.context.NodeContext;
import com.tesla.ities.process.core.context.ProcessContext;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.core.spi.ProcessObserver;
import com.tesla.ities.process.core.spi.ProcessObserverRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 全局观察者引擎: 在流程/节点/action 的前后自动调用所有已注册的 Observer。
 *
 * 与 ListenerEngine 的区别:
 *  - ListenerEngine: 按 YAML 声明的链(name 路由 + config)执行, before 可否决。
 *  - ObserverEngine: 全局自动生效, 遍历所有注册的 Observer, 纯观察(异常仅记录, 不影响流程)。
 *
 * 调用时机: 在 ListenerEngine 的同名切点**之前**调用 before, 在**之后**调用 after。
 * 即: Observer.before -> Listener.before -> 实际执行 -> Listener.after -> Observer.after
 */
@Component
public class ObserverEngine {

    private static final Logger log = LoggerFactory.getLogger(ObserverEngine.class);

    private final ProcessObserverRegistry registry;

    public ObserverEngine(ProcessObserverRegistry registry) {
        this.registry = registry;
    }

    // ===================== PROCESS =====================

    public void processBefore(ProcessContext ctx) {
        for (ProcessObserver o : registry.all()) {
            try {
                o.beforeProcess(ctx);
            } catch (Exception e) {
                log.warn("OrchestrationObserver.beforeProcess 异常(忽略) observer={}", o.getClass().getSimpleName(), e);
            }
        }
    }

    public void processAfter(ProcessContext ctx, Status status) {
        for (ProcessObserver o : registry.all()) {
            try {
                o.afterProcess(ctx, status);
            } catch (Exception e) {
                log.warn("OrchestrationObserver.afterProcess 异常(忽略) observer={}", o.getClass().getSimpleName(), e);
            }
        }
    }

    // ===================== NODE =====================

    public void nodeBefore(NodeContext ctx) {
        for (ProcessObserver o : registry.all()) {
            try {
                o.beforeNode(ctx);
            } catch (Exception e) {
                log.warn("OrchestrationObserver.beforeNode 异常(忽略) observer={}", o.getClass().getSimpleName(), e);
            }
        }
    }

    public void nodeAfter(NodeContext ctx, Status status) {
        for (ProcessObserver o : registry.all()) {
            try {
                o.afterNode(ctx, status);
            } catch (Exception e) {
                log.warn("OrchestrationObserver.afterNode 异常(忽略) observer={}", o.getClass().getSimpleName(), e);
            }
        }
    }

    // ===================== ACTION =====================

    public void actionBefore(ActionContext ctx) {
        for (ProcessObserver o : registry.all()) {
            try {
                o.beforeAction(ctx);
            } catch (Exception e) {
                log.warn("OrchestrationObserver.beforeAction 异常(忽略) observer={}", o.getClass().getSimpleName(), e);
            }
        }
    }

    public void actionAfter(ActionContext ctx, Status status) {
        for (ProcessObserver o : registry.all()) {
            try {
                o.afterAction(ctx, status);
            } catch (Exception e) {
                log.warn("OrchestrationObserver.afterAction 异常(忽略) observer={}", o.getClass().getSimpleName(), e);
            }
        }
    }
}
