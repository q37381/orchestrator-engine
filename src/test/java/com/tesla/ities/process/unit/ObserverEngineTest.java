package com.tesla.ities.process.unit;

import com.tesla.ities.process.core.context.ActionContext;
import com.tesla.ities.process.core.context.NodeContext;
import com.tesla.ities.process.core.context.ProcessContext;
import com.tesla.ities.process.core.engine.ObserverEngine;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.core.spi.ProcessObserver;
import com.tesla.ities.process.core.spi.ProcessObserverRegistry;
import com.tesla.ities.process.domain.NodeInstEntity;
import org.junit.Test;

import java.util.Collections;

/**
 * 全局观察者引擎: 每个切点都包 try/catch, 观察者抛异常只 warn 不上抛。用一个"处处抛"的观察者驱动六个切点,
 * 验证异常被吞掉(方法正常返回即证明 catch 分支生效)。
 */
public class ObserverEngineTest {

    private static ProcessObserver throwing() {
        return new ProcessObserver() {
            @Override public void beforeProcess(ProcessContext ctx) {
                throw new RuntimeException("x");
            }
            @Override public void afterProcess(ProcessContext ctx, Status s) {
                throw new RuntimeException("x");
            }
            @Override public void beforeNode(NodeContext ctx) {
                throw new RuntimeException("x");
            }
            @Override public void afterNode(NodeContext ctx, Status s) {
                throw new RuntimeException("x");
            }
            @Override public void beforeAction(ActionContext ctx) {
                throw new RuntimeException("x");
            }
            @Override public void afterAction(ActionContext ctx, Status s) {
                throw new RuntimeException("x");
            }
        };
    }

    @Test
    public void allSixCutPointsSwallowObserverExceptions() {
        ObserverEngine engine = new ObserverEngine(
                new ProcessObserverRegistry(Collections.singletonList(throwing())));
        ProcessContext p = new ProcessContext("proc");
        NodeInstEntity inst = new NodeInstEntity();
        inst.setNodeInstId("N1");
        NodeContext nc = new NodeContext(p, inst);
        ActionContext ac = new ActionContext(nc, "A1", "def", null, null);

        engine.processBefore(p);
        engine.processAfter(p, Status.SUCCESS);
        engine.nodeBefore(nc);
        engine.nodeAfter(nc, Status.SUCCESS);
        engine.actionBefore(ac);
        engine.actionAfter(ac, Status.SUCCESS);
        // 未抛异常即通过
    }
}
