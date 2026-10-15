package com.tesla.ities.process.unit;

import com.tesla.ities.process.core.context.ActionContext;
import com.tesla.ities.process.core.context.NodeContext;
import com.tesla.ities.process.core.context.ProcessContext;
import com.tesla.ities.process.core.engine.ObserverEngine;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.core.spi.ProcessObserver;
import com.tesla.ities.process.core.spi.ProcessObserverRegistry;
import com.tesla.ities.process.domain.NodeInstEntity;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 全局观察者引擎: 每个切点都包 try/catch, 观察者抛异常只 warn 不上抛。
 *
 * 装配口径: 注册表与观察者都是 {@code @Mock} 字段(观察者六个切点一律打桩成"抛"), 引擎 {@code @InjectMocks}。
 * 六个切点各调一次并各 verify 一次: 正常返回 = catch 生效, verify 到位 = 确实走到了观察者而不是压根没调。
 */
@RunWith(MockitoJUnitRunner.class)
public class ObserverEngineTest {

    @Mock
    private ProcessObserverRegistry registry;
    @Mock
    private ProcessObserver observer;
    @InjectMocks
    private ObserverEngine engine;

    /** 注册表只回放一份"处处抛"的观察者。 */
    @Before
    public void observerThrowsOnEveryCutPoint() throws Exception {
        when(registry.all()).thenReturn(Collections.singletonList(observer));
        RuntimeException boom = new RuntimeException("x");
        doThrow(boom).when(observer).beforeProcess(any());
        doThrow(boom).when(observer).afterProcess(any(), any());
        doThrow(boom).when(observer).beforeNode(any());
        doThrow(boom).when(observer).afterNode(any(), any());
        doThrow(boom).when(observer).beforeAction(any());
        doThrow(boom).when(observer).afterAction(any(), any());
    }

    @Test
    public void allSixCutPointsSwallowObserverExceptions() throws Exception {
        ProcessContext p = new ProcessContext("proc");
        NodeInstEntity inst = new NodeInstEntity();
        inst.setNodeInstId("N1");
        NodeContext nc = new NodeContext(p, inst);
        ActionContext ac = new ActionContext(nc, "A1", "def", null, null);

        engine.processBefore(p);            // 未抛异常即证明 catch 生效
        engine.processAfter(p, Status.SUCCESS);
        engine.nodeBefore(nc);
        engine.nodeAfter(nc, Status.SUCCESS);
        engine.actionBefore(ac);
        engine.actionAfter(ac, Status.SUCCESS);

        verify(observer, times(1)).beforeProcess(p);
        verify(observer, times(1)).afterProcess(p, Status.SUCCESS);
        verify(observer, times(1)).beforeNode(nc);
        verify(observer, times(1)).afterNode(nc, Status.SUCCESS);
        verify(observer, times(1)).beforeAction(ac);
        verify(observer, times(1)).afterAction(ac, Status.SUCCESS);
    }
}
