package com.tesla.ities.process.unit;

import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.core.spi.ActionHandler;
import com.tesla.ities.process.core.spi.ActionListener;
import com.tesla.ities.process.core.spi.ActionHandlerRegistry;
import com.tesla.ities.process.core.spi.ActionListenerRegistry;
import com.tesla.ities.process.core.spi.NodeListener;
import com.tesla.ities.process.core.spi.NodeListenerRegistry;
import com.tesla.ities.process.core.spi.ProcessListener;
import com.tesla.ities.process.core.spi.ProcessListenerRegistry;
import com.tesla.ities.process.core.spi.ProcessObserver;
import com.tesla.ities.process.core.spi.ProcessObserverRegistry;
import com.tesla.ities.process.core.spi.RollbackListener;
import com.tesla.ities.process.core.spi.RollbackListenerRegistry;
import com.tesla.ities.process.core.spi.ShardResolver;
import com.tesla.ities.process.core.spi.ShardResolverRegistry;
import com.tesla.ities.process.exception.ProcessException;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SPI 注册表单测: 各表按 name() 收集、命中、缺失抛异常、重名抛异常; Observer 表容忍空/null;
 * Rollback 表走 ObjectProvider(可零实现)。替身一律 Mockito mock, 只关心 name() 的注册语义。
 */
public class RegistryTest {

    private static ActionHandler handler(String name) throws Exception {
        ActionHandler h = mock(ActionHandler.class);
        when(h.name()).thenReturn(name);
        when(h.handle(any())).thenReturn(Status.SUCCESS);
        return h;
    }

    @Test
    public void actionHandlerRegistryGetContainsAndMiss() throws Exception {
        ActionHandlerRegistry reg = new ActionHandlerRegistry(Arrays.asList(handler("h1"), handler("h2")));
        Assert.assertEquals("h1", reg.get("h1").name());
        Assert.assertTrue(reg.contains("h2"));
        Assert.assertFalse(reg.contains("nope"));
        try {
            reg.get("nope");
            Assert.fail("未注册应抛异常");
        } catch (ProcessException expected) {
            Assert.assertEquals("AU204602", expected.getMessageKey());
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("nope"));
        }
    }

    @Test(expected = ProcessException.class)
    public void actionHandlerRegistryDuplicateNameThrows() throws Exception {
        new ActionHandlerRegistry(Arrays.asList(handler("dup"), handler("dup")));
    }

    private static ActionListener actionListener(String name) {
        ActionListener l = mock(ActionListener.class);
        when(l.name()).thenReturn(name);
        return l;
    }

    @Test
    public void actionListenerRegistryPaths() {
        ActionListenerRegistry ok = new ActionListenerRegistry(Collections.singletonList(actionListener("l1")));
        Assert.assertEquals("l1", ok.get("l1").name());
        Assert.assertTrue(ok.contains("l1"));
        Assert.assertFalse(ok.contains("x"));
        try {
            ok.get("x");
            Assert.fail();
        } catch (ProcessException expected) {
            // miss
        }
    }

    @Test(expected = ProcessException.class)
    public void actionListenerRegistryDup() {
        new ActionListenerRegistry(Arrays.asList(actionListener("d"), actionListener("d")));
    }

    private static NodeListener nodeListener(String name) {
        NodeListener l = mock(NodeListener.class);
        when(l.name()).thenReturn(name);
        return l;
    }

    @Test
    public void nodeListenerRegistryPaths() {
        NodeListenerRegistry ok = new NodeListenerRegistry(Collections.singletonList(nodeListener("n1")));
        Assert.assertEquals("n1", ok.get("n1").name());
        Assert.assertTrue(ok.contains("n1"));
        Assert.assertFalse(ok.contains("x"));
        try {
            ok.get("x");
            Assert.fail();
        } catch (ProcessException expected) {
            // miss
        }
    }

    @Test(expected = ProcessException.class)
    public void nodeListenerRegistryDup() {
        new NodeListenerRegistry(Arrays.asList(nodeListener("d"), nodeListener("d")));
    }

    private static ProcessListener processListener(String name) {
        ProcessListener l = mock(ProcessListener.class);
        when(l.name()).thenReturn(name);
        return l;
    }

    @Test
    public void processListenerRegistryPaths() {
        ProcessListenerRegistry ok = new ProcessListenerRegistry(Collections.singletonList(processListener("p1")));
        Assert.assertEquals("p1", ok.get("p1").name());
        Assert.assertTrue(ok.contains("p1"));
        try {
            ok.get("x");
            Assert.fail();
        } catch (ProcessException expected) {
            // miss
        }
    }

    @Test(expected = ProcessException.class)
    public void processListenerRegistryDup() {
        new ProcessListenerRegistry(Arrays.asList(processListener("d"), processListener("d")));
    }

    private static ShardResolver resolver(String name) {
        ShardResolver r = mock(ShardResolver.class);
        when(r.name()).thenReturn(name);
        when(r.resolveShardKeys(any())).thenReturn(Collections.emptyList());
        when(r.buildPayload(any(), any())).thenAnswer(i -> i.getArgument(0));
        return r;
    }

    @Test
    public void shardResolverRegistryGetAndMiss() {
        ShardResolverRegistry reg = new ShardResolverRegistry(Collections.singletonList(resolver("dbTopology")));
        Assert.assertEquals("dbTopology", reg.get("dbTopology").name());
        try {
            reg.get("nope");
            Assert.fail();
        } catch (ProcessException expected) {
            // miss
        }
    }

    private static ProcessObserver observer() {
        return mock(ProcessObserver.class);
    }

    @Test
    public void observerRegistryAcceptsNullAndList() {
        Assert.assertTrue(new ProcessObserverRegistry(null).all().isEmpty());
        List<ProcessObserver> all = new ProcessObserverRegistry(Collections.singletonList(observer())).all();
        Assert.assertEquals(1, all.size());
    }

    private static RollbackListener rbListener(String name) {
        RollbackListener l = mock(RollbackListener.class);
        when(l.name()).thenReturn(name);
        return l;
    }

    /** provider 只被注册表遍历一次, 其余方法引擎侧不使用: mock 掉。 */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<RollbackListener> providerOf(List<RollbackListener> beans) {
        ObjectProvider<RollbackListener> provider = mock(ObjectProvider.class);
        when(provider.iterator()).thenReturn(beans.iterator());
        return provider;
    }

    @Test
    public void rollbackRegistryToleratesEmptyAndResolves() {
        // 空 provider 构造不报错(回退是可选能力)
        Assert.assertNotNull(new RollbackListenerRegistry(providerOf(Collections.emptyList())));
        RollbackListenerRegistry reg = new RollbackListenerRegistry(
                providerOf(Collections.singletonList(rbListener("rb"))));
        Assert.assertEquals("rb", reg.get("rb").name());
        Assert.assertTrue(reg.contains("rb"));
        try {
            reg.get("nope");
            Assert.fail();
        } catch (ProcessException expected) {
            // miss
        }
    }

    @Test(expected = ProcessException.class)
    public void rollbackRegistryDup() {
        new RollbackListenerRegistry(providerOf(Arrays.asList(rbListener("d"), rbListener("d"))));
    }
}
