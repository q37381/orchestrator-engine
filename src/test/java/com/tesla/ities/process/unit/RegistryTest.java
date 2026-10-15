package com.tesla.ities.process.unit;

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
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.when;

/**
 * SPI 注册表单测: 各表按 name() 收集、命中、缺失抛异常、重名抛异常; Observer 表容忍空/null;
 * Rollback 表走 ObjectProvider(可零实现)。
 *
 * 替身一律 {@code @Mock} 字段, 只关心 name() 的注册语义(注册表也只会读 name()), 所以业务方法一个都不打桩。
 * 注册表本身按用例逐个 new —— 它们的构造子收的是 List/ObjectProvider 集合, 不是 {@code @InjectMocks} 能凑出来的形状。
 */
@RunWith(MockitoJUnitRunner.class)
public class RegistryTest {

    @Mock
    private ActionHandler handlerOne;
    @Mock
    private ActionHandler handlerTwo;
    @Mock
    private ActionHandler duplicateHandlerA;
    @Mock
    private ActionHandler duplicateHandlerB;

    @Mock
    private ActionListener actionListenerOne;
    @Mock
    private ActionListener duplicateActionListenerA;
    @Mock
    private ActionListener duplicateActionListenerB;

    @Mock
    private NodeListener nodeListenerOne;
    @Mock
    private NodeListener duplicateNodeListenerA;
    @Mock
    private NodeListener duplicateNodeListenerB;

    @Mock
    private ProcessListener processListenerOne;
    @Mock
    private ProcessListener duplicateProcessListenerA;
    @Mock
    private ProcessListener duplicateProcessListenerB;

    @Mock
    private ShardResolver dbTopologyResolver;
    @Mock
    private ProcessObserver observer;

    @Mock
    private RollbackListener rollbackOne;
    @Mock
    private RollbackListener duplicateRollbackA;
    @Mock
    private RollbackListener duplicateRollbackB;
    @Mock
    private ObjectProvider<RollbackListener> rollbackProvider;

    /** 每个替身只有一件事可说: 自己的注册名。 */
    @Before
    public void giveEverySpiBeanAName() {
        when(handlerOne.name()).thenReturn("h1");
        when(handlerTwo.name()).thenReturn("h2");
        when(duplicateHandlerA.name()).thenReturn("dup");
        when(duplicateHandlerB.name()).thenReturn("dup");
        when(actionListenerOne.name()).thenReturn("l1");
        when(duplicateActionListenerA.name()).thenReturn("d");
        when(duplicateActionListenerB.name()).thenReturn("d");
        when(nodeListenerOne.name()).thenReturn("n1");
        when(duplicateNodeListenerA.name()).thenReturn("d");
        when(duplicateNodeListenerB.name()).thenReturn("d");
        when(processListenerOne.name()).thenReturn("p1");
        when(duplicateProcessListenerA.name()).thenReturn("d");
        when(duplicateProcessListenerB.name()).thenReturn("d");
        when(dbTopologyResolver.name()).thenReturn("dbTopology");
        when(rollbackOne.name()).thenReturn("rb");
        when(duplicateRollbackA.name()).thenReturn("d");
        when(duplicateRollbackB.name()).thenReturn("d");
    }

    /** ObjectProvider 只被注册表遍历一次: 直接回放这批 Bean 的迭代器。 */
    private void servedByProvider(RollbackListener... beans) {
        when(rollbackProvider.iterator()).thenReturn(Arrays.asList(beans).iterator());
    }

    @Test
    public void actionHandlerRegistryGetContainsAndMiss() {
        ActionHandlerRegistry reg = new ActionHandlerRegistry(Arrays.asList(handlerOne, handlerTwo));
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
    public void actionHandlerRegistryDuplicateNameThrows() {
        new ActionHandlerRegistry(Arrays.asList(duplicateHandlerA, duplicateHandlerB));
    }

    @Test
    public void actionListenerRegistryPaths() {
        ActionListenerRegistry ok = new ActionListenerRegistry(Collections.singletonList(actionListenerOne));
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
        new ActionListenerRegistry(Arrays.asList(duplicateActionListenerA, duplicateActionListenerB));
    }

    @Test
    public void nodeListenerRegistryPaths() {
        NodeListenerRegistry ok = new NodeListenerRegistry(Collections.singletonList(nodeListenerOne));
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
        new NodeListenerRegistry(Arrays.asList(duplicateNodeListenerA, duplicateNodeListenerB));
    }

    @Test
    public void processListenerRegistryPaths() {
        ProcessListenerRegistry ok = new ProcessListenerRegistry(Collections.singletonList(processListenerOne));
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
        new ProcessListenerRegistry(Arrays.asList(duplicateProcessListenerA, duplicateProcessListenerB));
    }

    @Test
    public void shardResolverRegistryGetAndMiss() {
        ShardResolverRegistry reg = new ShardResolverRegistry(Collections.singletonList(dbTopologyResolver));
        Assert.assertEquals("dbTopology", reg.get("dbTopology").name());
        try {
            reg.get("nope");
            Assert.fail();
        } catch (ProcessException expected) {
            // miss
        }
    }

    @Test
    public void observerRegistryAcceptsNullAndList() {
        Assert.assertTrue(new ProcessObserverRegistry(null).all().isEmpty());
        List<ProcessObserver> all = new ProcessObserverRegistry(Collections.singletonList(observer)).all();
        Assert.assertEquals(1, all.size());
    }

    @Test
    public void rollbackRegistryToleratesEmptyAndResolves() {
        // 空 provider 构造不报错(回退是可选能力)
        servedByProvider();
        Assert.assertNotNull(new RollbackListenerRegistry(rollbackProvider));
        servedByProvider(rollbackOne);
        RollbackListenerRegistry reg = new RollbackListenerRegistry(rollbackProvider);
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
        servedByProvider(duplicateRollbackA, duplicateRollbackB);
        new RollbackListenerRegistry(rollbackProvider);
    }
}
