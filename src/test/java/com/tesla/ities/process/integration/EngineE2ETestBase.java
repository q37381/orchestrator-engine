package com.tesla.ities.process.integration;

import com.tesla.ities.process.api.ProcessExecution;
import com.tesla.ities.process.api.ProcessFacade;
import com.tesla.ities.process.core.context.ActionContext;
import com.tesla.ities.process.core.context.NodeContext;
import com.tesla.ities.process.core.context.ProcessContext;
import com.tesla.ities.process.core.define.DefinitionLoader;
import com.tesla.ities.process.core.define.ProcessDefinition;
import com.tesla.ities.process.core.engine.ActionKind;
import com.tesla.ities.process.core.engine.ActionRunner;
import com.tesla.ities.process.core.engine.ConfigFreeze;
import com.tesla.ities.process.core.engine.ExecutionEngine;
import com.tesla.ities.process.core.engine.GroupKind;
import com.tesla.ities.process.core.engine.IfKind;
import com.tesla.ities.process.core.engine.InterventionService;
import com.tesla.ities.process.core.engine.LayerWalker;
import com.tesla.ities.process.core.engine.ListenerEngine;
import com.tesla.ities.process.core.engine.Materializer;
import com.tesla.ities.process.core.engine.NodeDriver;
import com.tesla.ities.process.core.engine.NodeKind;
import com.tesla.ities.process.core.engine.NodeKinds;
import com.tesla.ities.process.core.engine.ObserverEngine;
import com.tesla.ities.process.core.engine.ParallelKind;
import com.tesla.ities.process.core.engine.PlanService;
import com.tesla.ities.process.core.engine.ProcessStateFlusher;
import com.tesla.ities.process.core.engine.ProgressService;
import com.tesla.ities.process.core.engine.RestoreService;
import com.tesla.ities.process.core.engine.RollbackService;
import com.tesla.ities.process.core.model.ActionSegment;
import com.tesla.ities.process.core.model.DefinitionStatus;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.core.runtime.CancellationRegistry;
import com.tesla.ities.process.core.runtime.ConditionEvaluator;
import com.tesla.ities.process.core.runtime.ContextFactory;
import com.tesla.ities.process.core.runtime.DefinitionCache;
import com.tesla.ities.process.core.runtime.VariableResolver;
import com.tesla.ities.process.core.spi.ActionHandler;
import com.tesla.ities.process.core.spi.ActionHandlerRegistry;
import com.tesla.ities.process.core.spi.ActionListener;
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
import com.tesla.ities.process.domain.ActionInstEntity;
import com.tesla.ities.process.domain.LaneEntity;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.domain.ProcessDefinitionEntity;
import com.tesla.ities.process.domain.ProcessInstEntity;
import com.tesla.ities.process.domain.RollbackRecordEntity;
import com.tesla.ities.process.mapper.ActionInstMapper;
import com.tesla.ities.process.mapper.LaneMapper;
import com.tesla.ities.process.mapper.NodeInstMapper;
import com.tesla.ities.process.mapper.ProcessDefinitionMapper;
import com.tesla.ities.process.mapper.ProcessInstMapper;
import com.tesla.ities.process.mapper.RollbackRecordMapper;
import com.tesla.ities.process.util.IdGenerator;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 端到端用例的共享夹具: **不起 Spring 容器、不连库**, Mockito 搭两张台子。
 *
 * <ol>
 *   <li><b>数据层</b>: 六个 mapper 接口各 mock 一份, 语义对着 {@code META-INF/mapper/process/*.xml} 逐条实现
 *       (动态 updateState 只刷非 null 列、fromStatus 即 CAS、action 落 result 顺带 visit_count++、
 *       INSERT IGNORE 按各自唯一键、列表查询一律 auto_id 升序)。出入口都收发**行副本**, 与 MyBatis 每次查询
 *       返回新对象一致: node 行上的 expr/def/listeners 属非持久字段, 回读时不会跟着泄漏回引擎。</li>
 *   <li><b>引擎层</b>: 组件全是构造器注入, 逐个 new; 替身 handler/listener/resolver 一律 Mockito mock 按
 *       {@code name()} 注册(可选的回退监听器用 mock 的 {@link ObjectProvider} 喂给注册表)。</li>
 * </ol>
 *
 * 本类 abstract 且不带 {@code @Test}, surefire 不会把它当用例跑。每个用例一套全新装配
 * (内存表 + DefinitionCache 都是新的), 于是"跨类同 defId 撞定义缓存、谁先跑谁定内容"那类串味问题不复存在。
 */
public abstract class EngineE2ETestBase {

    /** 各级 before/after 与 handler/补偿触发的计数与顺序, 供断言监听器链。 */
    protected static final class Recorder {
        final AtomicInteger processBefore = new AtomicInteger();
        final AtomicInteger processAfter = new AtomicInteger();
        final AtomicInteger nodeBefore = new AtomicInteger();
        final AtomicInteger nodeAfter = new AtomicInteger();
        final AtomicInteger actionBefore = new AtomicInteger();
        final AtomicInteger actionAfter = new AtomicInteger();
        final List<String> order = Collections.synchronizedList(new ArrayList<String>());
    }

    protected final Recorder rec = new Recorder();

    protected ProcessFacade facade;
    protected ExecutionEngine engine;
    protected ProcessDefinitionMapper defMapper;
    protected ProcessInstMapper processMapper;
    protected NodeInstMapper nodeMapper;
    protected ActionInstMapper actionMapper;
    protected LaneMapper laneMapper;
    protected RollbackRecordMapper rollbackMapper;

    private final Db db = new Db();
    // 三档并发各用一池(与生产装配同口径): node 扇出 / shard lane 扇出 / action 序列扇出
    private ThreadPoolTaskExecutor nodePool;
    private ThreadPoolTaskExecutor shardPool;
    private ThreadPoolTaskExecutor actionPool;

    // ====================================================================== 装配

    @Before
    public void wireEngine() throws Exception {
        nodePool = newPool("orch-e2e-node-");
        shardPool = newPool("orch-e2e-shard-");
        actionPool = newPool("orch-e2e-action-");

        defMapper = mapper(ProcessDefinitionMapper.class, this::definition);
        processMapper = mapper(ProcessInstMapper.class, this::processInst);
        nodeMapper = mapper(NodeInstMapper.class, this::nodeInst);
        laneMapper = mapper(LaneMapper.class, this::lane);
        actionMapper = mapper(ActionInstMapper.class, this::actionInst);
        rollbackMapper = mapper(RollbackRecordMapper.class, this::rollbackRecord);

        VariableResolver resolver = new VariableResolver();
        ConditionEvaluator evaluator = new ConditionEvaluator(resolver);
        // 业务键生成器替身: 引擎只问 IdGenerator 要号(号源在宿主那边), 这里给一串本用例内唯一的键即可
        IdGenerator ids = mock(IdGenerator.class);
        final AtomicInteger keySeq = new AtomicInteger();
        when(ids.next(any())).thenAnswer(inv -> "K" + keySeq.incrementAndGet());
        DefinitionLoader loader = new DefinitionLoader();

        ActionHandlerRegistry handlers = new ActionHandlerRegistry(Arrays.asList(
                ok(), thrower(), skipper(), nuller(), varWriter(), flagTrue(), echoConfig(), placeToGroup(),
                compOk(), compDefault(), compFail(), nonComp(), dbMigrate(), stopper(), flaky(),
                restoreFail(), flakyRestore(), pendingOnce(), pausedOnce(), suspended(), runningReturn()));
        ShardResolverRegistry shardResolvers = new ShardResolverRegistry(
                Collections.singletonList(dbTopology()));
        ProcessListenerRegistry processListeners = new ProcessListenerRegistry(
                Arrays.asList(pRecord(), pVeto(), pAfterBoom()));
        NodeListenerRegistry nodeListeners = new NodeListenerRegistry(
                Arrays.asList(nRecord(), nVeto(), nAfterBoom()));
        ActionListenerRegistry actionListeners = new ActionListenerRegistry(
                Arrays.asList(aRecord(), aVeto(), aAfterBoom()));
        RollbackListenerRegistry rollbackListeners = new RollbackListenerRegistry(providersOf(Arrays.asList(
                rbRecord(), rbVeto(), rbAfterOnly(), rbAfterBoom())));
        ProcessObserverRegistry observers = new ProcessObserverRegistry(
                Collections.singletonList(mock(ProcessObserver.class)));

        ListenerEngine listenerEngine = new ListenerEngine(
                processListeners, nodeListeners, actionListeners, rollbackListeners);
        ObserverEngine observerEngine = new ObserverEngine(observers);
        ContextFactory ctxFactory = new ContextFactory(shardResolvers);
        DefinitionCache defCache = new DefinitionCache(defMapper, processMapper, loader);
        CancellationRegistry cancellation = new CancellationRegistry(processMapper);

        // 一种节点类型一份档案(与生产装配同口径): 一次注册进 NodeKinds, 物化按表达式分发、驱动按行 type 分发
        ConfigFreeze freeze = new ConfigFreeze();
        ActionKind actionKind = new ActionKind(nodeMapper, laneMapper, actionMapper, shardResolvers, ids, freeze);
        NodeKinds nodeKinds = new NodeKinds(Arrays.asList(
                actionKind, new IfKind(nodeMapper, ids), new ParallelKind(nodeMapper, ids, freeze),
                new GroupKind(nodeMapper, ids, freeze)));
        Materializer materializer = new Materializer(nodeMapper, nodeKinds);
        ProcessStateFlusher flusher = new ProcessStateFlusher(processMapper);

        // 走查引擎(与生产装配同口径): 三档池 + action 行定位(口径归 ActionKind) + 容器行收尾(node/lane mapper)
        LayerWalker walker = new LayerWalker(nodePool, shardPool, actionPool, actionKind, ctxFactory,
                nodeMapper, laneMapper);

        ActionRunner actionRunner = new ActionRunner(actionMapper,
                handlers, listenerEngine, observerEngine, ctxFactory, cancellation, freeze, flusher);
        NodeDriver driver = new NodeDriver(nodeMapper, listenerEngine, observerEngine, ctxFactory,
                cancellation, materializer, actionRunner, nodeKinds, walker);

        RollbackService rollbackService = new RollbackService(processMapper, nodeMapper, laneMapper, actionMapper,
                rollbackMapper, handlers, ctxFactory, listenerEngine, defCache, resolver, ids, freeze);
        // 向前重放(回退后重试驱动前恢复现场): 与回退共用同一批 handler/上下文工厂, 但不牵监听器
        RestoreService restoreService = new RestoreService(processMapper, nodeMapper, laneMapper, actionMapper,
                rollbackMapper, handlers, ctxFactory, resolver, ids, freeze);
        // 自动回退(定义顶层 autoRollback)由引擎收尾直接调 RollbackService, 所以它与门面用的是**同一个**实例
        engine = new ExecutionEngine(processMapper, listenerEngine, observerEngine, defCache, ctxFactory,
                driver, resolver, evaluator, flusher, rollbackService, restoreService);
        PlanService planService = new PlanService(processMapper, defMapper, defCache, materializer,
                ctxFactory, resolver, evaluator, ids);
        facade = new ProcessFacade(planService, engine,
                new InterventionService(processMapper, nodeMapper, actionMapper),
                new ProgressService(nodeMapper, laneMapper, actionMapper),
                rollbackService);
    }

    @After
    public void shutdownPool() {
        nodePool.shutdown();
        shardPool.shutdown();
        actionPool.shutdown();
    }

    /** 造一个 e2e 用小池: 三档并发同规格, 只换线程名前缀便于日志区分。 */
    private static ThreadPoolTaskExecutor newPool(String prefix) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(64);
        ex.setThreadNamePrefix(prefix);
        ex.initialize();
        return ex;
    }

    /** 发布一份 YAML 定义(version=1, published), 顺带自检它能被装载。 */
    protected String publish(String defId, String yaml) {
        ProcessDefinition parsed = new DefinitionLoader().load(yaml);   // 定义非法当场炸, 别拖到驱动期
        Assert.assertNotNull(parsed);
        ProcessDefinitionEntity e = new ProcessDefinitionEntity();
        e.setProcessDefId(defId);
        e.setVersion(1);
        e.setName(defId);
        e.setDefinition(yaml);
        e.setStatus(DefinitionStatus.PUBLISHED.getStatus());
        e.setCreatedBy("tester");
        defMapper.insert(e);
        return defId;
    }

    /** create + start 一步到位(创建人 u1)。 */
    protected ProcessExecution run(String defId) {
        return facade.createAndStart(defId, 1, null, "u1");
    }

    // ====================================================================== mapper 替身

    /** 一条语句的分派口: 方法名进, 结果出。 */
    private interface Statement {
        Object run(String method, Object[] args);
    }

    /** 抽象方法按方法名走内存表; default 快捷方法跑真身(它们只是转发到抽象方法, 转发后再回到本 Answer)。 */
    private static <T> T mapper(Class<T> type, Statement statement) {
        return mock(type, inv -> inv.getMethod().isDefault()
                ? inv.callRealMethod()
                : statement.run(inv.getMethod().getName(), inv.getArguments()));
    }

    private Object definition(String method, Object[] a) {
        switch (method) {
            case "insert":
                return db.insertDefinition((ProcessDefinitionEntity) a[0]);
            case "find":
                return db.findDefinition((String) a[0], (Integer) a[1], (DefinitionStatus) a[2]);
            default:
                throw unsupported(method);
        }
    }

    private Object processInst(String method, Object[] a) {
        switch (method) {
            case "insert":
                return db.insertProcess((ProcessInstEntity) a[0]);
            case "findById":
                return db.findProcess((String) a[0]);
            case "updateState":
                return db.updateProcess((String) a[0], (String) a[1], (String) a[2],
                        (String) a[3], (String) a[4], (String) a[5]);
            case "findClaimableIds":
                return db.claimableProcessIds((Integer) a[0]);
            default:
                throw unsupported(method);
        }
    }

    private Object nodeInst(String method, Object[] a) {
        switch (method) {
            case "insertIgnore":
                return db.insertNode((NodeInstEntity) a[0]);
            case "findById":
                return db.findNode((String) a[0]);
            case "findByParent":
                return db.findNodesByParent((String) a[0], (String) a[1]);
            case "updateState":
                return db.updateNode((String) a[0], (String) a[1], (String) a[2],
                        (String) a[3], (String) a[4], (String) a[5]);
            case "claim":
                return db.claimNode((String) a[0]);
            case "findClaimableNodeIds":
                return db.claimableNodeIds((Integer) a[0]);
            default:
                throw unsupported(method);
        }
    }

    private Object lane(String method, Object[] a) {
        switch (method) {
            case "insertIgnore":
                return db.insertLane((LaneEntity) a[0]);
            case "findById":
                return db.findLane((String) a[0]);
            case "findByNode":
                return db.findLanes((String) a[0], (String) a[1]);
            case "updateState":
                return db.updateLane((String) a[0], (String) a[1], (String) a[2],
                        (String) a[3], (String) a[4]);
            case "claim":
                return db.claimLane((String) a[0]);
            case "findClaimableLaneIds":
                return db.claimableLaneIds((Integer) a[0]);
            default:
                throw unsupported(method);
        }
    }

    private Object actionInst(String method, Object[] a) {
        switch (method) {
            case "insertIgnore":
                return db.insertAction((ActionInstEntity) a[0]);
            case "findById":
                return db.findAction((String) a[0]);
            case "findInContainer":
                return db.findActions((String) a[0], (String) a[1], (String) a[2], (String) a[3]);
            case "find":
                return db.findActionsAny((String) a[0], (String) a[1]);
            case "updateState":
                return db.updateAction((String) a[0], (String) a[1], (String) a[2],
                        (String) a[3], (String) a[4]);
            case "claim":
                return db.claimAction((String) a[0]);
            default:
                throw unsupported(method);
        }
    }

    private Object rollbackRecord(String method, Object[] a) {
        switch (method) {
            case "insert":
                return db.insertRollbackRecord((RollbackRecordEntity) a[0]);
            case "updateStatus":
                return db.updateRollbackRecord((String) a[0], (String) a[1], (String) a[2], (String) a[3]);
            case "find":
                return db.findRollbackRecords((String) a[0], (String) a[1]);
            default:
                throw unsupported(method);
        }
    }

    private static UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException("夹具未实现的 mapper 语句: " + method);
    }

    // ====================================================================== SPI 替身

    private interface Body {
        Status run(ActionContext ctx) throws Exception;
    }

    /** 记一行 result 并成功。 */
    private ActionHandler ok() throws Exception {
        return handler("ok", ctx -> {
            ctx.putResult("rows", 1);
            rec.order.add("handler:ok:" + ctx.actionDefId());
            return Status.SUCCESS;
        });
    }

    private ActionHandler thrower() throws Exception {
        return handler("thrower", ctx -> {
            throw new IllegalStateException("handler boom");
        });
    }

    private ActionHandler skipper() throws Exception {
        return handler("skipper", ctx -> Status.SKIPPED);
    }

    /** 返 null -> 引擎兜底 SUCCESS。 */
    private ActionHandler nuller() throws Exception {
        return handler("nuller", ctx -> null);
    }

    private ActionHandler varWriter() throws Exception {
        return handler("varWriter", ctx -> {
            ctx.putVar("marker", "set");            // 触发脏 vars 随流程状态刷回
            return Status.SUCCESS;
        });
    }

    /** 运行期把标记**显式挂到所属容器桶**(引擎不再自动挂载, guard 要读就得自己放)。 */
    private ActionHandler flagTrue() throws Exception {
        return handler("flagTrue", ctx -> {
            ctx.putResult("ok", true);
            Map<String, Object> flag = new LinkedHashMap<>();
            flag.put("ok", true);
            ctx.upward().putResult(ctx.actionDefId(), flag);
            return Status.SUCCESS;
        });
    }

    /** 把激活期解析后的 config 原样写进 result(断言占位符解析结果用)。 */
    private ActionHandler echoConfig() throws Exception {
        return handler("echoConfig", ctx -> {
            ctx.putResult("cfg", new LinkedHashMap<>(ctx.config()));
            rec.order.add("handler:echoConfig:" + ctx.actionDefId());
            return Status.SUCCESS;
        });
    }

    /** 显式放置的样板: result 留在本 action 行的同时, 把 {rows:1} 挂进**直属上层容器(组)桶**供后代上溯读。 */
    private ActionHandler placeToGroup() throws Exception {
        return handler("placeToGroup", ctx -> {
            ctx.putResult("rows", 1);
            Map<String, Object> shared = new LinkedHashMap<>();
            shared.put("rows", 1);
            String defId = ctx.actionDefId();
            int dot = defId.lastIndexOf('.');
            ctx.node().parent().putResult(dot < 0 ? defId : defId.substring(dot + 1), shared);
            rec.order.add("handler:placeToGroup:" + defId);
            return Status.SUCCESS;
        });
    }

    private ActionHandler compOk() throws Exception {
        ActionHandler h = compensable("compOk", true);
        doAnswer(i -> rec.order.add("compensate:compOk")).when(h).compensate(any());
        doAnswer(i -> rec.order.add("restore:compOk")).when(h).restore(any());
        return h;
    }

    /** compensate 不 stub = 等价 {@code ActionHandler} 的默认空实现。 */
    private ActionHandler compDefault() throws Exception {
        return compensable("compDefault", true);
    }

    private ActionHandler compFail() throws Exception {
        ActionHandler h = compensable("compFail", true);
        doAnswer(i -> {
            throw new IllegalStateException("compensate boom");
        }).when(h).compensate(any());
        return h;
    }

    /** 可补偿、补偿默认空实现即成功, 但 restore 恒抛: 验证"重放失败当场挂起、不向前驱动"。 */
    private ActionHandler restoreFail() throws Exception {
        ActionHandler h = compensable("restoreFail", true);
        doAnswer(i -> {
            throw new IllegalStateException("restore boom");
        }).when(h).restore(any());
        return h;
    }

    /**
     * 可补偿, restore 头一次抛、之后成功(每个用例一套新装配, 计数天然独立):
     * 验证"重放失败挂起 -> 修好现场再重试 -> 从没立稳那条接着放"的可重复闭环。
     */
    private ActionHandler flakyRestore() throws Exception {
        ActionHandler h = compensable("flakyRestore", true);
        AtomicInteger tries = new AtomicInteger();
        doAnswer(i -> {
            rec.order.add("restore:flakyRestore");
            if (tries.getAndIncrement() == 0) {
                throw new IllegalStateException("restore flaky boom");
            }
            return null;
        }).when(h).restore(any());
        return h;
    }

    /** 不可补偿(回退 BLOCKED)。 */
    private ActionHandler nonComp() throws Exception {
        return compensable("nonComp", false);
    }

    /** 分片迁移 handler: 每 lane 一条, 可补偿。 */
    private ActionHandler dbMigrate() throws Exception {
        ActionHandler h = mock(ActionHandler.class);
        when(h.name()).thenReturn("dbMigrate");
        when(h.handle(any())).thenAnswer(i -> {
            ActionContext ctx = i.getArgument(0);
            ctx.putResult("shardKey", ctx.shardKey());
            return Status.SUCCESS;
        });
        when(h.compensable()).thenReturn(true);
        return h;
    }

    /**
     * 头一次失败、之后成功: 给"重试后跑通"的用例用(每个用例一套新装配, 计数因此天然独立)。
     * 顺带验证重跑会把上一轮的 __error 痕迹摘干净。
     */
    private ActionHandler flaky() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        return handler("flaky", ctx -> {
            ctx.putResult("rows", 1);
            rec.order.add("handler:flaky:" + ctx.actionDefId());
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("handler flaky boom");
            }
            return Status.SUCCESS;
        });
    }

    /** 让渡(待重入): 首次返回 PENDING(退出等重入), 重入后成功——验证"让渡行重跑 + 完成后收尾"。 */
    private ActionHandler pendingOnce() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        return handler("pendingOnce", ctx -> {
            ctx.putResult("attempts", attempts.incrementAndGet());
            rec.order.add("handler:pendingOnce:" + ctx.actionDefId());
            return attempts.get() == 1 ? Status.PENDING : Status.SUCCESS;
        });
    }

    /** 让渡(暂停): 首次返回 PAUSED, 走 resume 重新入队后成功。 */
    private ActionHandler pausedOnce() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        return handler("pausedOnce", ctx -> {
            rec.order.add("handler:pausedOnce:" + ctx.actionDefId());
            return attempts.incrementAndGet() == 1 ? Status.PAUSED : Status.SUCCESS;
        });
    }

    /** 让渡(挂起): 恒返 SUSPENDED(停驻态, 本期无唤醒入口)。 */
    private ActionHandler suspended() throws Exception {
        return handler("suspended", ctx -> {
            rec.order.add("handler:suspended:" + ctx.actionDefId());
            return Status.SUSPENDED;
        });
    }

    /** 非法过程态: 返回 RUNNING -> 引擎按 FAILED 处理并写明 __error。 */
    private ActionHandler runningReturn() throws Exception {
        return handler("runningReturn", ctx -> Status.RUNNING);
    }

    /**
     * 运行中把所属流程 CAS 成 STOPPED(模拟人工 stop 抢先落库), 自己则成功返回。
     * 可补偿: 自动回退用例要靠它把终态做成 STOPPED, 从而验证"停止后也回退"这条触发条件。
     */
    private ActionHandler stopper() throws Exception {
        ActionHandler h = handler("stopper", ctx -> {
            String pid = ctx.node().inst().getProcInstId();
            processMapper.casStatus(pid, Status.RUNNING.getStatus(), Status.STOPPED.getStatus(), "op1");
            rec.order.add("handler:stopper");
            return Status.SUCCESS;
        });
        when(h.compensable()).thenReturn(true);
        return h;
    }

    private static ActionHandler handler(String name, Body body) throws Exception {
        ActionHandler h = mock(ActionHandler.class);
        when(h.name()).thenReturn(name);
        when(h.handle(any())).thenAnswer(i -> body.run(i.getArgument(0)));
        return h;
    }

    /** 成功 + 写 rows + 指定 compensable 的通用可补偿 handler。 */
    private ActionHandler compensable(String name, boolean compensable) throws Exception {
        ActionHandler h = handler(name, ctx -> {
            ctx.putResult("rows", 1);
            return Status.SUCCESS;
        });
        when(h.compensable()).thenReturn(compensable);
        return h;
    }

    /** 一主两从 = 3 个 shardKey; payload 现场重建、绝不落库。 */
    private ShardResolver dbTopology() {
        ShardResolver r = mock(ShardResolver.class);
        when(r.name()).thenReturn("dbTopology");
        when(r.resolveShardKeys(any())).thenReturn(
                Arrays.asList("db-primary:3306", "db-replica-1:3306", "db-replica-2:3306"));
        when(r.buildPayload(any(), any())).thenAnswer(i -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source", "db-primary:3306");
            payload.put("target", i.getArgument(0));
            return payload;
        });
        return r;
    }

    private ProcessListener pRecord() throws Exception {
        ProcessListener l = mock(ProcessListener.class);
        when(l.name()).thenReturn("pRecord");
        doAnswer(i -> {
            ProcessContext ctx = i.getArgument(0);
            Map<String, Object> config = i.getArgument(1);
            rec.processBefore.incrementAndGet();
            rec.order.add("processBefore");
            if (config != null && config.containsKey("writeVar")) {
                ctx.putVar("listenerVar", config.get("writeVar"));
            }
            if (config != null && config.containsKey("writeResult")) {
                ctx.putResult("listenerResult", config.get("writeResult"));   // 流程级 result 脏刷
            }
            return null;
        }).when(l).before(any(), any());
        doAnswer(i -> {
            rec.processAfter.incrementAndGet();
            rec.order.add("processAfter:" + ((Status) i.getArgument(1)).getStatus());
            return null;
        }).when(l).after(any(), any(), any());
        return l;
    }

    private ProcessListener pVeto() throws Exception {
        ProcessListener l = mock(ProcessListener.class);
        when(l.name()).thenReturn("pVeto");
        doAnswer(i -> {
            throw new IllegalStateException("process veto");
        }).when(l).before(any(), any());
        return l;
    }

    private ProcessListener pAfterBoom() throws Exception {
        ProcessListener l = mock(ProcessListener.class);
        when(l.name()).thenReturn("pAfterBoom");
        doAnswer(i -> {
            throw new IllegalStateException("process after boom");   // ListenerEngine 应吞掉
        }).when(l).after(any(), any(), any());
        return l;
    }

    private NodeListener nRecord() throws Exception {
        NodeListener l = mock(NodeListener.class);
        when(l.name()).thenReturn("nRecord");
        doAnswer(i -> {
            rec.nodeBefore.incrementAndGet();
            rec.order.add("nodeBefore:" + ((NodeContext) i.getArgument(0)).nodeRef());
            return null;
        }).when(l).before(any(), any());
        doAnswer(i -> {
            rec.nodeAfter.incrementAndGet();
            rec.order.add("nodeAfter:" + ((Status) i.getArgument(1)).getStatus());
            return null;
        }).when(l).after(any(), any(), any());
        return l;
    }

    private NodeListener nVeto() throws Exception {
        NodeListener l = mock(NodeListener.class);
        when(l.name()).thenReturn("nVeto");
        doAnswer(i -> {
            throw new IllegalStateException("node veto");
        }).when(l).before(any(), any());
        return l;
    }

    private NodeListener nAfterBoom() throws Exception {
        NodeListener l = mock(NodeListener.class);
        when(l.name()).thenReturn("nAfterBoom");
        doAnswer(i -> {
            throw new IllegalStateException("node after boom");
        }).when(l).after(any(), any(), any());
        return l;
    }

    private ActionListener aRecord() throws Exception {
        ActionListener l = mock(ActionListener.class);
        when(l.name()).thenReturn("aRecord");
        doAnswer(i -> {
            rec.actionBefore.incrementAndGet();
            rec.order.add("actionBefore:" + ((ActionContext) i.getArgument(0)).actionDefId());
            return null;
        }).when(l).before(any(), any());
        doAnswer(i -> {
            rec.actionAfter.incrementAndGet();
            rec.order.add("actionAfter:" + ((Status) i.getArgument(1)).getStatus());
            return null;
        }).when(l).after(any(), any(), any());
        return l;
    }

    private ActionListener aVeto() throws Exception {
        ActionListener l = mock(ActionListener.class);
        when(l.name()).thenReturn("aVeto");
        doAnswer(i -> {
            throw new IllegalStateException("action veto");
        }).when(l).before(any(), any());
        return l;
    }

    private ActionListener aAfterBoom() throws Exception {
        ActionListener l = mock(ActionListener.class);
        when(l.name()).thenReturn("aAfterBoom");
        doAnswer(i -> {
            throw new IllegalStateException("action after boom");
        }).when(l).after(any(), any(), any());
        return l;
    }

    private RollbackListener rbRecord() throws Exception {
        RollbackListener l = mock(RollbackListener.class);
        when(l.name()).thenReturn("rbRecord");
        doAnswer(i -> {
            ProcessContext ctx = i.getArgument(0);
            rec.order.add("rollbackBefore");
            ctx.putResult("dbConn", "jdbc:mysql://db-01:3306/ord");   // 现场信息(回退路径不刷流程级 result)
            ctx.putVar("rollbackMarker", "1");
            return null;
        }).when(l).before(any(), any());
        doAnswer(i -> {
            rec.order.add("rollbackAfter:" + ((Status) i.getArgument(1)).getStatus());
            return null;
        }).when(l).after(any(), any(), any());
        return l;
    }

    private RollbackListener rbVeto() throws Exception {
        RollbackListener l = mock(RollbackListener.class);
        when(l.name()).thenReturn("rbVeto");
        doAnswer(i -> {
            throw new IllegalStateException("rollback veto");
        }).when(l).before(any(), any());
        return l;
    }

    /** 只 stub after: before 保持默认空实现(不否决)。 */
    private RollbackListener rbAfterOnly() throws Exception {
        RollbackListener l = mock(RollbackListener.class);
        when(l.name()).thenReturn("rbAfterOnly");
        doAnswer(i -> {
            rec.order.add("rbAfterOnly");
            return null;
        }).when(l).after(any(), any(), any());
        return l;
    }

    private RollbackListener rbAfterBoom() throws Exception {
        RollbackListener l = mock(RollbackListener.class);
        when(l.name()).thenReturn("rbAfterBoom");
        doAnswer(i -> {
            throw new IllegalStateException("rollback after boom");
        }).when(l).after(any(), any(), any());
        return l;
    }

    /** 回退监听器是可选能力, 注册表收 ObjectProvider: mock 一个只负责被遍历的 provider。 */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providersOf(List<T> beans) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.iterator()).thenReturn(beans.iterator());
        return provider;
    }

    // ====================================================================== 内存表

    /**
     * 六张定义/实例表的进程内替身: 列口径照 {@code db/schema.sql}, 语句口径照各 mapper XML。
     * 全部出入口持同一把锁并收发副本 —— 并行扇出会跨线程读写同一张表。
     */
    private static final class Db {

        /** node 行上的定义侧挂件: 非持久列, 回读必须丢干净(与真库同一口径)。 */
        private static final List<String> TRANSIENT =
                Arrays.asList("NodeInstEntity#expr", "NodeInstEntity#def", "NodeInstEntity#listeners");

        private final Map<String, ProcessDefinitionEntity> definitions = new LinkedHashMap<>();
        private final Map<String, ProcessInstEntity> processes = new LinkedHashMap<>();
        private final List<NodeInstEntity> nodes = new ArrayList<>();
        private final List<LaneEntity> lanes = new ArrayList<>();
        private final List<ActionInstEntity> actions = new ArrayList<>();
        private final List<RollbackRecordEntity> rollbacks = new ArrayList<>();
        private long autoId;

        // ---------------- process_definition ----------------

        synchronized int insertDefinition(ProcessDefinitionEntity e) {
            e.setAutoId(++autoId);
            definitions.put(e.getProcessDefId() + ":" + e.getVersion(), copy(e));
            return 1;
        }

        /** version 非 null 精确定位; 否则按发布态取最新版(status 为 null 回落 published)。 */
        synchronized ProcessDefinitionEntity findDefinition(String defId, Integer version, DefinitionStatus status) {
            String wanted = status == null ? DefinitionStatus.PUBLISHED.getStatus() : status.getStatus();
            ProcessDefinitionEntity best = null;
            for (ProcessDefinitionEntity row : definitions.values()) {
                if (!row.getProcessDefId().equals(defId)) {
                    continue;
                }
                if (version != null) {
                    if (version.equals(row.getVersion())) {
                        return copy(row);
                    }
                    continue;
                }
                if (!wanted.equals(row.getStatus())) {
                    continue;
                }
                if (best == null || row.getVersion() > best.getVersion()) {
                    best = row;
                }
            }
            return copy(best);
        }

        // ---------------- process_inst ----------------

        synchronized int insertProcess(ProcessInstEntity e) {
            e.setAutoId(++autoId);
            stampNew(e);
            processes.put(e.getProcInstId(), copy(e));
            return 1;
        }

        synchronized ProcessInstEntity findProcess(String id) {
            return copy(processes.get(id));
        }

        synchronized int updateProcess(String id, String fromStatus, String toStatus,
                                       String result, String vars, String updatorId) {
            ProcessInstEntity row = processes.get(id);
            if (row == null || !matches(row.getStatus(), fromStatus)) {
                return 0;
            }
            if (toStatus != null) {
                row.setStatus(toStatus);
            }
            if (result != null) {
                row.setResult(result);
            }
            if (vars != null) {
                row.setVars(vars);
            }
            touch(row, updatorId);
            return 1;
        }

        synchronized List<String> claimableProcessIds(int limit) {
            List<String> ids = new ArrayList<>();
            for (ProcessInstEntity row : ordered(new ArrayList<>(processes.values()))) {
                if (Status.PENDING.getStatus().equals(row.getStatus())) {
                    ids.add(row.getProcInstId());
                }
                if (ids.size() >= limit) {
                    break;
                }
            }
            return ids;
        }

        // ---------------- process_node_inst ----------------

        /** uk_node_inst(node_inst_id): 业务键撞了即忽略(崩溃重放不重复插)。 */
        synchronized int insertNode(NodeInstEntity e) {
            for (NodeInstEntity row : nodes) {
                if (row.getNodeInstId().equals(e.getNodeInstId())) {
                    return 0;
                }
            }
            e.setAutoId(++autoId);
            if (e.getStatus() == null) {
                e.setStatus(Status.CREATED.getStatus());
            }
            if (e.getErrorPolicy() == null) {
                e.setErrorPolicy("fail_fast");
            }
            if (e.getShardEnabled() == null) {
                e.setShardEnabled(Boolean.FALSE);
            }
            stampNew(e);
            nodes.add(copy(e));
            return 1;
        }

        synchronized NodeInstEntity findNode(String id) {
            return copy(row(nodes, r -> r.getNodeInstId().equals(id)));
        }

        /** parentId=null 取顶层根(setup + flow + setdown 三段), 否则取该父的子行; 一律 auto_id 升序。 */
        synchronized List<NodeInstEntity> findNodesByParent(String procInstId, String parentId) {
            List<NodeInstEntity> out = new ArrayList<>();
            for (NodeInstEntity row : ordered(nodes)) {
                if (row.getProcInstId().equals(procInstId)
                        && (parentId == null ? row.getParentId() == null : parentId.equals(row.getParentId()))) {
                    out.add(copy(row));
                }
            }
            return out;
        }

        synchronized int updateNode(String id, String fromStatus, String toStatus,
                                   String result, String config, String updatorId) {
            NodeInstEntity row = row(nodes, r -> r.getNodeInstId().equals(id));
            if (row == null || !matches(row.getStatus(), fromStatus)) {
                return 0;
            }
            if (toStatus != null) {
                row.setStatus(toStatus);
            }
            if (result != null) {
                row.setResult(result);
            }
            if (config != null) {
                row.setConfig(config);
            }
            touch(row, updatorId);
            return 1;
        }

        /** 备用认领路径: 节点 pending 且所属流程 running 才翻 running。 */
        synchronized int claimNode(String id) {
            NodeInstEntity row = row(nodes, r -> r.getNodeInstId().equals(id));
            if (row == null || !Status.PENDING.getStatus().equals(row.getStatus())
                    || !processRunning(row.getProcInstId())) {
                return 0;
            }
            row.setStatus(Status.RUNNING.getStatus());
            return 1;
        }

        synchronized List<String> claimableNodeIds(int limit) {
            List<String> ids = new ArrayList<>();
            for (NodeInstEntity row : ordered(nodes)) {
                if (Status.PENDING.getStatus().equals(row.getStatus()) && processRunning(row.getProcInstId())) {
                    ids.add(row.getNodeInstId());
                }
                if (ids.size() >= limit) {
                    break;
                }
            }
            return ids;
        }

        // ---------------- process_lane ----------------

        /** uk_node_shard(node_inst_id, shard_key) + uk_lane(lane_id)。 */
        synchronized int insertLane(LaneEntity e) {
            for (LaneEntity row : lanes) {
                if (row.getLaneId().equals(e.getLaneId())
                        || (row.getNodeInstId().equals(e.getNodeInstId()) && eq(row.getShardKey(), e.getShardKey()))) {
                    return 0;
                }
            }
            e.setAutoId(++autoId);
            if (e.getStatus() == null) {
                e.setStatus(Status.CREATED.getStatus());
            }
            if (e.getErrorPolicy() == null) {
                e.setErrorPolicy("fail_fast");
            }
            stampNew(e);
            lanes.add(copy(e));
            return 1;
        }

        synchronized LaneEntity findLane(String id) {
            return copy(row(lanes, r -> r.getLaneId().equals(id)));
        }

        /** shardKey 非 null 时按唯一键收窄并只取一条。 */
        synchronized List<LaneEntity> findLanes(String nodeInstId, String shardKey) {
            List<LaneEntity> out = new ArrayList<>();
            for (LaneEntity row : ordered(lanes)) {
                if (!row.getNodeInstId().equals(nodeInstId)) {
                    continue;
                }
                if (shardKey == null) {
                    out.add(copy(row));
                } else if (shardKey.equals(row.getShardKey())) {
                    out.add(copy(row));
                    break;      // LIMIT 1
                }
            }
            return out;
        }

        synchronized int updateLane(String id, String fromStatus, String toStatus,
                                   String result, String updatorId) {
            LaneEntity row = row(lanes, r -> r.getLaneId().equals(id));
            if (row == null || !matches(row.getStatus(), fromStatus)) {
                return 0;
            }
            if (toStatus != null) {
                row.setStatus(toStatus);
            }
            if (result != null) {
                row.setResult(result);
            }
            touch(row, updatorId);
            return 1;
        }

        synchronized int claimLane(String id) {
            LaneEntity row = row(lanes, r -> r.getLaneId().equals(id));
            if (row == null || !Status.PENDING.getStatus().equals(row.getStatus())
                    || !processRunning(row.getProcInstId())) {
                return 0;
            }
            row.setStatus(Status.RUNNING.getStatus());
            return 1;
        }

        synchronized List<String> claimableLaneIds(int limit) {
            List<String> ids = new ArrayList<>();
            for (LaneEntity row : ordered(lanes)) {
                if (Status.PENDING.getStatus().equals(row.getStatus()) && processRunning(row.getProcInstId())) {
                    ids.add(row.getLaneId());
                }
                if (ids.size() >= limit) {
                    break;
                }
            }
            return ids;
        }

        // ---------------- process_action_inst ----------------

        /**
         * uk_node_def_shard(node_inst_id, action_def_id, shard_key) + uk_action_inst。
         * 非分片行 shard_key 为 NULL 时唯一键不去重(NULL != NULL), 与 MySQL 一致 —— 故只在 shardKey 非 null
         * 时才按自然键忽略; 撞业务键一律忽略。
         */
        synchronized int insertAction(ActionInstEntity e) {
            for (ActionInstEntity row : actions) {
                if (row.getActionInstId().equals(e.getActionInstId())) {
                    return 0;
                }
                if (e.getShardKey() != null && row.getNodeInstId().equals(e.getNodeInstId())
                        && row.getActionDefId().equals(e.getActionDefId()) && eq(row.getShardKey(), e.getShardKey())
                        && segmentOf(row).equals(segmentOf(e))) {
                    return 0;
                }
            }
            e.setAutoId(++autoId);
            if (e.getStatus() == null) {
                e.setStatus(Status.CREATED.getStatus());
            }
            if (e.getVisitCount() == null) {
                e.setVisitCount(0);
            }
            stampNew(e);
            actions.add(copy(e));
            return 1;
        }

        synchronized ActionInstEntity findAction(String id) {
            return copy(row(actions, r -> r.getActionInstId().equals(id)));
        }

        /** 容器内 action: laneId=null 匹配 lane_id IS NULL 的非分片行; actionDefId 非 null 只取一条。 */
        synchronized List<ActionInstEntity> findActions(String nodeInstId, String laneId, String actionDefId, String segment) {
            List<ActionInstEntity> out = new ArrayList<>();
            for (ActionInstEntity row : ordered(actions)) {
                if (!row.getNodeInstId().equals(nodeInstId)) {
                    continue;
                }
                if (laneId == null ? row.getLaneId() != null : !laneId.equals(row.getLaneId())) {
                    continue;
                }
                if (segment != null && !segment.equals(segmentOf(row))) {
                    continue;
                }
                if (actionDefId != null && !actionDefId.equals(row.getActionDefId())) {
                    continue;
                }
                out.add(copy(row));
                if (actionDefId != null) {
                    break;      // LIMIT 1
                }
            }
            return out;
        }

        /** 段值口径: 未设置按 main 计(与真实库经 segment 列默认值落 main 同口径)。 */
        private static String segmentOf(ActionInstEntity row) {
            return row.getSegment() == null ? ActionSegment.MAIN.getStatus() : row.getSegment();
        }

        /** 不按 lane 收窄的 action 列表查询(两个条件都可选)。 */
        synchronized List<ActionInstEntity> findActionsAny(String nodeInstId, String actionInstId) {
            List<ActionInstEntity> out = new ArrayList<>();
            for (ActionInstEntity row : ordered(actions)) {
                if (nodeInstId != null && !nodeInstId.equals(row.getNodeInstId())) {
                    continue;
                }
                if (actionInstId != null && !actionInstId.equals(row.getActionInstId())) {
                    continue;
                }
                out.add(copy(row));
            }
            return out;
        }

        /** 落 result 时同句 visit_count++(重跑观测)。 */
        synchronized int updateAction(String id, String fromStatus, String toStatus,
                                     String result, String updatorId) {
            ActionInstEntity row = row(actions, r -> r.getActionInstId().equals(id));
            if (row == null || !matches(row.getStatus(), fromStatus)) {
                return 0;
            }
            if (toStatus != null) {
                row.setStatus(toStatus);
            }
            if (result != null) {
                row.setResult(result);
                row.setVisitCount(row.getVisitCount() == null ? 1 : row.getVisitCount() + 1);
            }
            touch(row, updatorId);
            return 1;
        }

        synchronized int claimAction(String id) {
            ActionInstEntity row = row(actions, r -> r.getActionInstId().equals(id));
            if (row == null || !Status.PENDING.getStatus().equals(row.getStatus())
                    || !processRunning(processOfNode(row.getNodeInstId()))) {
                return 0;
            }
            row.setStatus(Status.RUNNING.getStatus());
            row.setVisitCount(row.getVisitCount() == null ? 1 : row.getVisitCount() + 1);
            return 1;
        }

        // ---------------- process_rollback_record ----------------

        synchronized int insertRollbackRecord(RollbackRecordEntity e) {
            e.setAutoId(++autoId);
            stampNew(e);
            rollbacks.add(copy(e));
            return 1;
        }

        synchronized int updateRollbackRecord(String id, String to, String detail, String updatorId) {
            RollbackRecordEntity row = row(rollbacks, r -> r.getRollbackId().equals(id));
            if (row == null) {
                return 0;
            }
            row.setStatus(to);
            row.setDetail(detail);
            touch(row, updatorId);
            return 1;
        }

        synchronized List<RollbackRecordEntity> findRollbackRecords(String procInstId, String actionInstId) {
            List<RollbackRecordEntity> out = new ArrayList<>();
            for (RollbackRecordEntity row : ordered(rollbacks)) {
                if (procInstId != null && !procInstId.equals(row.getProcInstId())) {
                    continue;
                }
                if (actionInstId != null && !actionInstId.equals(row.getActionInstId())) {
                    continue;
                }
                out.add(copy(row));
            }
            return out;
        }

        // ---------------- 公共小件 ----------------

        private static boolean matches(String current, String fromStatus) {
            return fromStatus == null || fromStatus.equals(current);
        }

        private static boolean eq(Object a, Object b) {
            return a == null ? b == null : a.equals(b);
        }

        private boolean processRunning(String procInstId) {
            ProcessInstEntity p = processes.get(procInstId);
            return p != null && Status.RUNNING.getStatus().equals(p.getStatus());
        }

        private String processOfNode(String nodeInstId) {
            NodeInstEntity n = row(nodes, r -> r.getNodeInstId().equals(nodeInstId));
            return n == null ? null : n.getProcInstId();
        }

        /** 按 auto_id(= 声明顺序)升序的副本列表。 */
        private static <T> List<T> ordered(List<T> rows) {
            List<T> out = new ArrayList<>(rows);
            out.sort(Comparator.comparingLong(Db::autoIdOf));
            return out;
        }

        private static long autoIdOf(Object row) {
            Long v = (Long) get(row, "autoId");
            return v == null ? 0L : v;
        }

        private static <T> T row(List<T> rows, Predicate<T> hit) {
            for (T r : rows) {
                if (hit.test(r)) {
                    return r;
                }
            }
            return null;
        }

        /** DEFAULT CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP 的替身。 */
        private static void stampNew(Object row) {
            set(row, "createdAt", LocalDateTime.now());
            set(row, "updatedAt", LocalDateTime.now());
        }

        private static void touch(Object row, String updatorId) {
            if (updatorId != null) {
                set(row, "updatorId", updatorId);
            }
            set(row, "updatedAt", LocalDateTime.now());
        }

        private static Object get(Object row, String field) {
            try {
                Field f = row.getClass().getDeclaredField(field);
                f.setAccessible(true);
                return f.get(row);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        private static void set(Object row, String field, Object value) {
            try {
                Field f = row.getClass().getDeclaredField(field);
                f.setAccessible(true);
                f.set(row, value);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        /** 只搬持久列: 声明期挂上的定义侧对象(expr/def/listeners)不回读, 与真库同一口径。 */
        @SuppressWarnings("unchecked")
        private static <T> T copy(T src) {
            if (src == null) {
                return null;
            }
            try {
                T dst = (T) src.getClass().getConstructor().newInstance();
                for (Field f : src.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())
                            || TRANSIENT.contains(src.getClass().getSimpleName() + "#" + f.getName())) {
                        continue;
                    }
                    f.setAccessible(true);
                    f.set(dst, f.get(src));
                }
                return dst;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("实体副本构造失败: " + src.getClass(), e);
            }
        }
    }
}
