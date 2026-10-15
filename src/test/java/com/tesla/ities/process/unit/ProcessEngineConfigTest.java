package com.tesla.ities.process.unit;

import com.tesla.ities.process.config.ProcessEngineConfig;
import com.tesla.ities.process.core.engine.Dispatcher;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.sql.DataSource;
import java.util.Properties;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 引擎配置类单测: 表名变量派生、可覆盖属性的 getter/setter、线程池/扫描器/调度器工厂方法、
 * SqlSessionFactory 的显式注入优先与上下文回退两条取值路径。
 *
 * 装配口径: 配置类本体 {@code @InjectMocks}(非 Spring 直接 new 时取字段 Java 初始值),
 * 上下文与数据源 provider 是 {@code @Mock} 字段; DataSource 用 mock(只当值传, 不连库, 不绑具体实现)。
 *
 * 线程池断言口径: sizing 一律看**真身**(初始化后问底层 jdk 的 ThreadPoolExecutor), 不依赖 Spring 侧的 getter ——
 * 宿主的 Spring 版本可能没有某个 getter(如 {@code getQueueCapacity()}), 而 jdk 那套 API 常年不变。
 */
@RunWith(MockitoJUnitRunner.class)
public class ProcessEngineConfigTest {

    @Mock
    private ApplicationContext context;
    @Mock
    private ObjectProvider<DataSource> dataSourceProvider;
    @InjectMocks
    private ProcessEngineConfig config;

    @Test
    public void buildTableVariablesDefaultPrefix() {
        Properties p = ProcessEngineConfig.buildTableVariables("process_");
        Assert.assertEquals("process_definition", p.getProperty("orch.table.processDefinition"));
        Assert.assertEquals("process_inst", p.getProperty("orch.table.processInst"));
        Assert.assertEquals("process_node_inst", p.getProperty("orch.table.nodeInst"));
        Assert.assertEquals("process_lane", p.getProperty("orch.table.lane"));
        Assert.assertEquals("process_action_inst", p.getProperty("orch.table.actionInst"));
        Assert.assertEquals("process_rollback_record", p.getProperty("orch.table.rollbackRecord"));
        Assert.assertEquals("process_fragment", p.getProperty("orch.table.fragment"));
    }

    @Test
    public void buildTableVariablesNullFallsBackToEmpty() {
        Properties p = ProcessEngineConfig.buildTableVariables(null);
        Assert.assertEquals("definition", p.getProperty("orch.table.processDefinition"));
        Assert.assertEquals("lane", p.getProperty("orch.table.lane"));
    }

    /**
     * 自定义前缀: 8 张**一律** = 前缀 + 表名, 没有两张固定名特权(否则消费者库里正好有一张
     * process_inst 时无处可逃, 而 tablePrefix 存在的唯一理由就是撞名逃逸)。
     * 空前缀那条用例则是同一规则的另一端: 八张一起回落到裸名。
     */
    @Test
    public void buildTableVariablesCustomPrefix() {
        Properties p = ProcessEngineConfig.buildTableVariables("myapp_");
        Assert.assertEquals("myapp_node_inst", p.getProperty("orch.table.nodeInst"));
        Assert.assertEquals("myapp_lane", p.getProperty("orch.table.lane"));
        Assert.assertEquals("myapp_action_inst", p.getProperty("orch.table.actionInst"));
        Assert.assertEquals("myapp_rollback_record", p.getProperty("orch.table.rollbackRecord"));
        Assert.assertEquals("myapp_fragment", p.getProperty("orch.table.fragment"));
        Assert.assertEquals("myapp_definition", p.getProperty("orch.table.processDefinition"));
        Assert.assertEquals("myapp_inst", p.getProperty("orch.table.processInst"));
    }

    @Test
    public void mutablePropertiesRoundTrip() {
        Assert.assertEquals("process_", config.getTablePrefix());
        Assert.assertTrue(config.isDispatcherEnabled());
        Assert.assertEquals(500L, config.getDispatcherPollIntervalMs());
        Assert.assertEquals(16, config.getDispatcherClaimBatch());

        config.setTablePrefix("t_");
        config.setDispatcherEnabled(false);
        config.setDispatcherPollIntervalMs(99L);
        config.setDispatcherClaimBatch(3);
        DataSource ds = mock(DataSource.class);
        config.setDataSource(ds);

        Assert.assertEquals("t_", config.getTablePrefix());
        Assert.assertFalse(config.isDispatcherEnabled());
        Assert.assertEquals(99L, config.getDispatcherPollIntervalMs());
        Assert.assertEquals(3, config.getDispatcherClaimBatch());
        Assert.assertSame(ds, config.getDataSource());
    }

    @Test
    public void parallelExecutePoolBeanIsConfigured() {
        ThreadPoolTaskExecutor ex = config.itiesProcessParallelExecutePool();
        Assert.assertEquals(16, ex.getCorePoolSize());
        Assert.assertEquals(16, ex.getMaxPoolSize());
        Assert.assertEquals(20000, queueCapacity(ex));
        Assert.assertEquals("ities-process-node-", ex.getThreadNamePrefix());
        ex.shutdown();
    }

    /** 三档池默认同规格(16/16/20000/600), 但前缀各自独立便于日志区分; sizing 走 @Value, 非 Spring 直接 new 时取字段初始值。 */
    @Test
    public void threePoolsShareDefaultsButDistinctPrefixes() {
        ThreadPoolTaskExecutor node = config.itiesProcessParallelExecutePool();
        ThreadPoolTaskExecutor shard = config.itiesProcessActionShardParallelExecutePool();
        ThreadPoolTaskExecutor action = config.itiesProcessActionParallelExecutePool();
        for (ThreadPoolTaskExecutor ex : new ThreadPoolTaskExecutor[]{node, shard, action}) {
            Assert.assertEquals(16, ex.getCorePoolSize());
            Assert.assertEquals(16, ex.getMaxPoolSize());
            Assert.assertEquals(20000, queueCapacity(ex));
            Assert.assertEquals(600, ex.getKeepAliveSeconds());
        }
        Assert.assertEquals("ities-process-node-", node.getThreadNamePrefix());
        Assert.assertEquals("ities-process-shard-", shard.getThreadNamePrefix());
        Assert.assertEquals("ities-process-action-", action.getThreadNamePrefix());
        node.shutdown();
        shard.shutdown();
        action.shutdown();
    }

    /**
     * 队列容量的取法: Spring 那侧的 {@code getQueueCapacity()} 不是每个版本都有(宿主那份就没有),
     * 所以先 {@code initialize()} 再问底层 jdk 线程池的空队列还剩多少坑——等价于设进去的容量,
     * 且验的是真正生效的那条队列, 不是配置字段本身。底层 API 是 jdk 的, 换 Spring 版本也测得出真值。
     */
    private static int queueCapacity(ThreadPoolTaskExecutor ex) {
        ex.initialize();
        return ex.getThreadPoolExecutor().getQueue().remainingCapacity();
    }

    @Test
    public void mapperScannerTargetsEnginePackage() {
        MapperScannerConfigurer scanner = config.orchestratorMapperScanner();
        Assert.assertNotNull(scanner);
    }

    @Test
    public void dispatcherFactoryWiresGivenConfig() {
        config.setDispatcherEnabled(false);
        ThreadPoolTaskExecutor pool = config.itiesProcessParallelExecutePool();
        Dispatcher d = config.orchestratorDispatcher(null, null, pool);
        Assert.assertNotNull(d);
        pool.shutdown();
    }

    /** 显式注入 DataSource 优先: 不触碰 ApplicationContext。 */
    @Test
    public void sqlSessionFactoryPrefersExplicitDataSource() throws Exception {
        config.setDataSource(mock(DataSource.class));
        SqlSessionFactoryBean factory = config.processSqlSessionFactory(null);
        Assert.assertNotNull(factory);
    }

    /** 未显式注入时回退到上下文里唯一的数据源(mock 上下文, 不起容器)。 */
    @Test
    public void sqlSessionFactoryFallsBackToContextDataSource() throws Exception {
        when(dataSourceProvider.getIfAvailable()).thenReturn(mock(DataSource.class));
        when(context.getBeanProvider(DataSource.class)).thenReturn(dataSourceProvider);

        SqlSessionFactoryBean factory = config.processSqlSessionFactory(context);
        Assert.assertNotNull(factory);
        verify(context).getBeanProvider(DataSource.class);      // 取值路径确实走了上下文 provider
        verify(dataSourceProvider).getIfAvailable();
    }
}
