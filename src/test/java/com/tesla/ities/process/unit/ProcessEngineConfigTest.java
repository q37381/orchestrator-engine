package com.tesla.ities.process.unit;

import com.tesla.ities.process.config.ProcessEngineConfig;
import com.tesla.ities.process.core.engine.Dispatcher;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Assert;
import org.junit.Test;
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
 */
public class ProcessEngineConfigTest {

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
     * 自定义前缀: 7 张**一律** = 前缀 + 表名, 没有两张固定名特权(否则消费者库里正好有一张
     * process_inst 时无处可逃, 而 tablePrefix 存在的唯一理由就是撞名逃逸)。
     * 空前缀那条用例则是同一规则的另一端: 七张一起回落到裸名。
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
        ProcessEngineConfig c = new ProcessEngineConfig();
        Assert.assertEquals("process_", c.getTablePrefix());
        Assert.assertTrue(c.isDispatcherEnabled());
        Assert.assertEquals(500L, c.getDispatcherPollIntervalMs());
        Assert.assertEquals(16, c.getDispatcherClaimBatch());

        c.setTablePrefix("t_");
        c.setDispatcherEnabled(false);
        c.setDispatcherPollIntervalMs(99L);
        c.setDispatcherClaimBatch(3);
        DataSource ds = new JdbcDataSource();
        c.setDataSource(ds);

        Assert.assertEquals("t_", c.getTablePrefix());
        Assert.assertFalse(c.isDispatcherEnabled());
        Assert.assertEquals(99L, c.getDispatcherPollIntervalMs());
        Assert.assertEquals(3, c.getDispatcherClaimBatch());
        Assert.assertSame(ds, c.getDataSource());
    }

    @Test
    public void parallelExecutePoolBeanIsConfigured() {
        ThreadPoolTaskExecutor ex = new ProcessEngineConfig().itiesProcessParallelExecutePool();
        Assert.assertEquals(8, ex.getCorePoolSize());
        Assert.assertEquals(64, ex.getMaxPoolSize());
        Assert.assertEquals(256, ex.getQueueCapacity());
        Assert.assertEquals("ities-process-node-", ex.getThreadNamePrefix());
        ex.shutdown();
    }

    /** 三档池默认同规格(8/64/256/60), 但前缀各自独立便于日志区分; sizing 走 @Value, 非 Spring 直接 new 时取字段初始值。 */
    @Test
    public void threePoolsShareDefaultsButDistinctPrefixes() {
        ProcessEngineConfig c = new ProcessEngineConfig();
        ThreadPoolTaskExecutor node = c.itiesProcessParallelExecutePool();
        ThreadPoolTaskExecutor shard = c.itiesProcessActionShardParallelExecutePool();
        ThreadPoolTaskExecutor action = c.itiesProcessActionParallelExecutePool();
        for (ThreadPoolTaskExecutor ex : new ThreadPoolTaskExecutor[]{node, shard, action}) {
            Assert.assertEquals(8, ex.getCorePoolSize());
            Assert.assertEquals(64, ex.getMaxPoolSize());
            Assert.assertEquals(256, ex.getQueueCapacity());
            Assert.assertEquals(60, ex.getKeepAliveSeconds());
        }
        Assert.assertEquals("ities-process-node-", node.getThreadNamePrefix());
        Assert.assertEquals("ities-process-shard-", shard.getThreadNamePrefix());
        Assert.assertEquals("ities-process-action-", action.getThreadNamePrefix());
        node.shutdown();
        shard.shutdown();
        action.shutdown();
    }

    @Test
    public void mapperScannerTargetsEnginePackage() {
        MapperScannerConfigurer scanner = new ProcessEngineConfig().orchestratorMapperScanner();
        Assert.assertNotNull(scanner);
    }

    @Test
    public void dispatcherFactoryWiresGivenConfig() {
        ProcessEngineConfig c = new ProcessEngineConfig();
        c.setDispatcherEnabled(false);
        Dispatcher d = c.orchestratorDispatcher(null, null, new ProcessEngineConfig().itiesProcessParallelExecutePool());
        Assert.assertNotNull(d);
    }

    /** 显式注入 DataSource 优先: 不触碰 ApplicationContext。 */
    @Test
    public void sqlSessionFactoryPrefersExplicitDataSource() throws Exception {
        ProcessEngineConfig c = new ProcessEngineConfig();
        c.setDataSource(new JdbcDataSource());
        SqlSessionFactoryBean factory = c.processSqlSessionFactory(null);
        Assert.assertNotNull(factory);
    }

    /** 未显式注入时回退到上下文里唯一的数据源(mock 上下文, 不起容器)。 */
    @Test
    @SuppressWarnings("unchecked")
    public void sqlSessionFactoryFallsBackToContextDataSource() throws Exception {
        DataSource ds = new JdbcDataSource();
        ObjectProvider<DataSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(ds);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeanProvider(DataSource.class)).thenReturn(provider);

        SqlSessionFactoryBean factory = new ProcessEngineConfig().processSqlSessionFactory(ctx);
        Assert.assertNotNull(factory);
        verify(ctx).getBeanProvider(DataSource.class);      // 取值路径确实走了上下文 provider
    }
}
