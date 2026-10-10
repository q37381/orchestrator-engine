package com.tesla.ities.process.config;

import com.tesla.ities.process.core.engine.Dispatcher;
import com.tesla.ities.process.core.engine.ExecutionEngine;
import com.tesla.ities.process.mapper.ProcessInstMapper;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 编排引擎统一配置入口。
 *
 * <h3>集成方式</h3>
 * <b>Java Config:</b>
 * <pre>{@code @Import(ProcessEngineConfig.class)}</pre>
 * <b>XML:</b> 引擎未附带 XML 装配描述符, 需要 XML 集成时自行 {@code <bean class="..ProcessEngineConfig"/>} 声明本配置类。
 *
 * <h3>消费者必须提供</h3>
 * <ul>
 *   <li>{@code DataSource} — 上下文里必须有, 否则启动失败(引擎不创建默认数据源)
 *     <ul>
 *       <li>恰好一个(或有 @Primary): 自动取用, 无需配置</li>
 *       <li>多个且无法自动判定: 用 {@code setDataSource(..)} 直接注入指定那个数据源 Bean</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h3>MyBatis(引擎自建, 与消费者互不干扰)</h3>
 * <ul>
 *   <li>{@code orchestratorSqlSessionFactory} — 引擎专用, 用消费者的 DataSource</li>
 *   <li>{@code orchestratorMapperScanner} — 引擎专用, 只扫 {@code com.orchestrator.mapper}</li>
 *   <li>与消费者自己的 SqlSessionFactory / Mapper 在不同 Bean 名、不同 package, 完全隔离</li>
 * </ul>
 *
 * <h3>可选覆盖</h3>
 * 消费者如需自定义线程池, 在同上下文声明同名 Bean 即可覆盖引擎默认。三档并发各用一池, 互不抢占:
 * <ul>
 *   <li>{@code itiesProcessParallelExecutePool} — node 并发(PARALLEL 容器的子节点扇出)</li>
 *   <li>{@code itiesProcessActionShardParallelExecutePool} — shard 并发(分片节点的 lane 扇出)</li>
 *   <li>{@code itiesProcessActionParallelExecutePool} — action 并发(容器内 action 序列扇出, 需 {@code actionExecution: PARALLEL})</li>
 * </ul>
 * 每档 sizing 独立配置(上下文里 {@code ities.process.<node|shard|action>.*}), 键为
 * {@code core-size}/{@code max-size}/{@code queue-capacity}/{@code keep-alive-seconds}, 默认都是 16/16/20000/600;
 * 要更彻底地自定义某一档, 声明同名 Bean 覆盖即可。
 *
 * <h3>业务键序列(消费者提供五个 Bean)</h3>
 * 五类业务键各走一条序列, 一条序列 = 一个 {@code com.tesla.bcs.sequence.StringSequenceGenerator} Bean,
 * 取号走无参 {@code getStringSequence()}; 引擎按 <b>Bean 名</b>注入(名字对不上就不注入, 见下):
 * <ul>
 *   <li>{@code processInstSequenceGenerator} — process_inst.proc_inst_id</li>
 *   <li>{@code nodeInstSequenceGenerator} — process_node_inst.node_inst_id</li>
 *   <li>{@code laneSequenceGenerator} — process_lane.lane_id</li>
 *   <li>{@code actionInstSequenceGenerator} — process_action_inst.action_inst_id</li>
 *   <li>{@code rollbackRecordSequenceGenerator} — process_rollback_record.rollback_id</li>
 * </ul>
 * 引擎<b>不创建</b>这些 Bean, 也不要求它们存在(缺哪个就回落本地时间戳序列并打一条 WARN, 启动不受影响);
 * 宿主已有同名类时把引擎里那份替身 {@code com/tesla/bcs/sequence/StringSequenceGenerator.java} 删掉即可。
 * 想换 Bean 名只改 {@code IdKind} 那五行枚举声明(Bean 名只在那儿定义一次), 或调
 * {@code IdGenerator.setSequence(IdKind, generator)} 显式绑。
 *
 * <h3>异常文案(AU204001~AU204999)</h3>
 * 引擎侧抛出统一是 {@code ProcessException(messageKey, args...)}, 文案由 {@code ProcessException} 自己
 * 从 classpath 上的 {@code i18n/orchestrator-messages.properties}(默认那份即简体中文, UTF-8)懒加载,
 * <b>不声明任何 Bean</b>: 抛出点散布在非 Spring 管理的工具类(DslParser / Json / 直接 new 的 DefinitionLoader),
 * 拿到键就能解, 装配期也就没有"缺 Bean 起不来"这一说。要换文案来源(改语种/接消费者自己的国际化)调
 * {@code ProcessException.setResolver(..)}; 宿主工程已有同名 ProcessException 时, 本配置类不掺和,
 * 那份异常文件删掉即可(构造签名对齐就行)。
 *
 * <h3>当前用户</h3>
 * 引擎不管用户会话: 创建流程时把已获取好的 userId 传给门面, 落 process_inst.creator_id 并进 ProcessContext;
 * 驱动/干预期间的 updator_id 由调用方显式下传(流程创建人 / 操作人), 不依赖 ThreadLocal。
 */
@Configuration
@ComponentScan(basePackages = {
        "com.tesla.ities.process.core",
        "com.tesla.ities.process.api",
        "com.tesla.ities.process.util",
        "com.tesla.ities.process.executor"
})
public class ProcessEngineConfig {

    // ========== Dispatcher 配置(消费者可通过 property 覆盖) ==========

    private boolean dispatcherEnabled = true;
    private long dispatcherPollIntervalMs = 500;
    private int dispatcherClaimBatch = 16;

    // ========== Dispatcher 能力(分布式路由: 本 worker 服务哪个单元 / 哪个网络区域) ==========
    // 两者都为空 = 全局模式(claim 走老逻辑捞全部 pending, 不看调度表, 单机/存量部署零影响);
    // 配了 unit + zone = 路由模式(只主动认领调度表里命中本能力的流程)。一台节点只对应一个 unit + 一个 zone。
    @Value("${ities.process.dispatcher.unit:}")
    private String dispatcherUnit = "";
    @Value("${ities.process.dispatcher.zone:}")
    private String dispatcherZone = "";

    // ========== 表命名空间(嵌入式组件不假设裸表名可用) ==========

    /**
     * 表名前缀, 默认 "process_"(与 db/schema.sql 默认表名一致)。
     * 消费者库里撞名时改成自己的前缀; 存量裸名环境设 "" 逃逸(或给 RENAME TABLE 脚本迁到新名)。
     * 生效机制: mapper XML 里表名全部写成 ${orch.table.*}, MyBatis 解析 XML 时用 variables 做一次性文本替换。
     */
    private String tablePrefix = "process_";

    public String getTablePrefix() {
        return tablePrefix;
    }

    public void setTablePrefix(String v) {
        this.tablePrefix = v;
    }

    /**
     * 由前缀派生 7 张表的变量(引擎与测试共用同一口径, 不两处各写一份)。
     * 一律 **前缀 + 表名**, 七张都不例外: 默认前缀 "process_" 展开后就是 db/schema.sql 里的
     * process_definition / process_inst / process_node_inst..., 名字里那个 "process" 是前缀本身而非固定名。
     * 撞名时改 tablePrefix 让 7 张整体挪窝(设 "" 回落到存量裸名), 不给自己留换不掉的死角。
     * 变量必须显式 put(含空串): PropertyParser 对解不到的占位符原样留在 SQL 里 = 语法错误。
     */
    public static Properties buildTableVariables(String prefix) {
        String p = prefix == null ? "" : prefix;
        Properties v = new Properties();
        v.setProperty("orch.table.processDefinition", p + "definition");
        v.setProperty("orch.table.processInst", p + "inst");
        v.setProperty("orch.table.nodeInst", p + "node_inst");
        v.setProperty("orch.table.lane", p + "lane");
        v.setProperty("orch.table.actionInst", p + "action_inst");
        v.setProperty("orch.table.rollbackRecord", p + "rollback_record");
        v.setProperty("orch.table.fragment", p + "fragment");   // 预留表, schema.sql 里有 DDL 无 mapper
        v.setProperty("orch.table.processSched", p + "sched");  // 调度路由表(可选主动调度)
        return v;
    }

    // ========== DataSource 指定(可选: 多数据源时显式注入其一) ==========

    /**
     * 引擎使用的 DataSource。缺省为 null 时自动取上下文里唯一的数据源; 存在多个且无法自动判定时直接 set 本字段。
     */
    private DataSource dataSource;

    public DataSource getDataSource() {
        return dataSource;
    }

    public void setDataSource(DataSource v) {
        this.dataSource = v;
    }

    public boolean isDispatcherEnabled() {
        return dispatcherEnabled;
    }

    public void setDispatcherEnabled(boolean v) {
        this.dispatcherEnabled = v;
    }

    public long getDispatcherPollIntervalMs() {
        return dispatcherPollIntervalMs;
    }

    public void setDispatcherPollIntervalMs(long v) {
        this.dispatcherPollIntervalMs = v;
    }

    public int getDispatcherClaimBatch() {
        return dispatcherClaimBatch;
    }

    public void setDispatcherClaimBatch(int v) {
        this.dispatcherClaimBatch = v;
    }

    public String getDispatcherUnit() {
        return dispatcherUnit;
    }

    public void setDispatcherUnit(String v) {
        this.dispatcherUnit = v;
    }

    public String getDispatcherZone() {
        return dispatcherZone;
    }

    public void setDispatcherZone(String v) {
        this.dispatcherZone = v;
    }

    // ========== Dispatcher ==========

    // @Bean
    public Dispatcher orchestratorDispatcher(ProcessInstMapper processMapper,
                                             ExecutionEngine engine,
                                             ThreadPoolTaskExecutor parallelExecutePool) {
        String unit = trimToNull(dispatcherUnit);
        String zone = trimToNull(dispatcherZone);
        return new Dispatcher(processMapper, engine, parallelExecutePool,
                dispatcherEnabled, dispatcherPollIntervalMs, dispatcherClaimBatch, unit, zone);
    }

    /** 去空白; 空串/ null -> null(触发全局模式)。 */
    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    // ========== MyBatis: 引擎自建(隔离, 不干扰消费者) ==========

    /**
     * 引擎专用 SqlSessionFactory, 用消费者的 DataSource。
     * Bean 名 orchestratorSqlSessionFactory, 与消费者自己的 sqlSessionFactory 互不干扰。
     * <p>
     * SQL 全部在引擎自带的 mapper/orchestrator/*.xml 里(带 resultMap 显式列映射),
     * 路径独立于消费者的 mapper/, 互不误加载。
     */
    @Bean
    public SqlSessionFactoryBean processSqlSessionFactory(ApplicationContext applicationContext) throws IOException {
        // 优先用显式 set 的对象; 没 set 则从上下文取(单数据源/@Primary)
        DataSource ds = dataSource;
        if (ds == null) {
            ds = applicationContext.getBeanProvider(DataSource.class).getIfAvailable();
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setTypeAliasesPackage("com.tesla.ities.process.domain");
        factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:META-INF/mapper/process/*.xml"));
        // 表名变量: 自建 Configuration 只用于携带 variables(解析语句时会被拷进工厂内部配置),
        // 引擎的 Configuration 与消费者的互不相干。
        org.apache.ibatis.session.Configuration mybatisConf = new org.apache.ibatis.session.Configuration();
        mybatisConf.setVariables(buildTableVariables(tablePrefix));
        mybatisConf.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(mybatisConf);
        return factory;
    }

    /**
     * 引擎专用 MapperScanner, 只扫 com.orchestrator.mapper。
     * 消费者的 Mapper 在不同 package, 多个 scanner 共存不冲突。
     */
    @Bean
    public MapperScannerConfigurer orchestratorMapperScanner() {
        MapperScannerConfigurer scanner = new MapperScannerConfigurer();
        scanner.setBasePackage("com.tesla.ities.process.mapper");
        scanner.setSqlSessionFactoryBeanName("processSqlSessionFactory");
        return scanner;
    }

    // ========== 线程池(三档并发各用一池, 每档参数独立可配) ==========

    // node 并发池 sizing(默认即历史硬编码值; 上下文中用 ities.process.node.* 覆盖)
    @Value("${ities.process.node.core-size:16}")
    private int nodeCoreSize = 16;
    @Value("${ities.process.node.max-size:16}")
    private int nodeMaxSize = 16;
    @Value("${ities.process.node.queue-capacity:20000}")
    private int nodeQueueCapacity = 20000;
    @Value("${ities.process.node.keep-alive-seconds:600}")
    private int nodeKeepAliveSeconds = 600;

    // shard 并发池 sizing(ities.process.shard.*)
    @Value("${ities.process.shard.core-size:16}")
    private int shardCoreSize = 16;
    @Value("${ities.process.shard.max-size:16}")
    private int shardMaxSize = 16;
    @Value("${ities.process.shard.queue-capacity:20000}")
    private int shardQueueCapacity = 20000;
    @Value("${ities.process.shard.keep-alive-seconds:600}")
    private int shardKeepAliveSeconds = 600;

    // action 并发池 sizing(ities.process.action.*)
    @Value("${ities.process.action.core-size:16}")
    private int actionCoreSize = 16;
    @Value("${ities.process.action.max-size:16}")
    private int actionMaxSize = 16;
    @Value("${ities.process.action.queue-capacity:20000}")
    private int actionQueueCapacity = 20000;
    @Value("${ities.process.action.keep-alive-seconds:600}")
    private int actionKeepAliveSeconds = 600;

    /**
     * 造一档执行池: 三档参数各自独立(外层 node 扇出 / 内层 shard lane 扇出 / 最内层 action 扇出负载不同)。
     * 保留字段 Java 初始值是为兼容非 Spring 直接 new(如单测); Spring 上下文里 @Value 会在装配期覆盖初始值。
     * 消费者如需更彻底的自定义, 直接在上下文声明同名 Bean 覆盖引擎默认。
     */
    private ThreadPoolTaskExecutor newExecutePool(String threadNamePrefix, int coreSize, int maxSize,
                                                  int queueCapacity, int keepAliveSeconds) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(coreSize);
        ex.setMaxPoolSize(maxSize);
        ex.setQueueCapacity(queueCapacity);
        ex.setKeepAliveSeconds(keepAliveSeconds);
        ex.setThreadNamePrefix(threadNamePrefix);
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(30);
        ex.initialize();
        return ex;
    }

    /**
     * node 并发: PARALLEL 容器的子节点扇出。
     * CallerRunsPolicy: 池满时由驱动线程自己跑, 既背压又避免 join 饥饿。
     * 无需上下文装饰: 驱动参数全部走 DriveContext/Context 显式传递(含 userId)。
     * sizing 由 {@code ities.process.node.*} 配置。
     */
    @Bean("itiesProcessParallelExecutePool")
    public ThreadPoolTaskExecutor itiesProcessParallelExecutePool() {
        return newExecutePool("ities-process-node-", nodeCoreSize, nodeMaxSize, nodeQueueCapacity, nodeKeepAliveSeconds);
    }

    /**
     * shard 并发: 分片节点(laneExecution=PARALLEL)的 lane 扇出。
     * 与 node 并发分池, 避免"外层并行扇出"与"内层分片扇出"在同池里互等造成 join 饥饿。
     * sizing 由 {@code ities.process.shard.*} 配置; 其余拒绝/收尾策略同 node 并发池。
     */
    @Bean("itiesProcessActionShardParallelExecutePool")
    public ThreadPoolTaskExecutor itiesProcessActionShardParallelExecutePool() {
        return newExecutePool("ities-process-shard-", shardCoreSize, shardMaxSize, shardQueueCapacity, shardKeepAliveSeconds);
    }

    /**
     * action 并发: 容器内 action 序列(actionExecution=PARALLEL)的 action 扇出。
     * 位于扇出链最内层(分片 lane 里的每个 action), 单独成池以免与外层 node/shard 扇出互占线程。
     * sizing 由 {@code ities.process.action.*} 配置; 其余拒绝/收尾策略同 node 并发池。
     */
    @Bean("itiesProcessActionParallelExecutePool")
    public ThreadPoolTaskExecutor itiesProcessActionParallelExecutePool() {
        return newExecutePool("ities-process-action-", actionCoreSize, actionMaxSize, actionQueueCapacity, actionKeepAliveSeconds);
    }

}
