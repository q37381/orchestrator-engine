package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.core.context.ProcessContext;
import com.tesla.ities.process.core.define.DslParser;
import com.tesla.ities.process.core.define.ListenerDef;
import com.tesla.ities.process.core.define.ProcessDefinition;
import com.tesla.ities.process.core.model.ErrorPolicy;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.util.Errors;
import com.tesla.ities.process.util.Json;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.core.runtime.*;
import com.tesla.ities.process.domain.ProcessInstEntity;
import com.tesla.ities.process.mapper.ProcessInstMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行引擎(流程级认领 + 单 owner 走查驱动 + 流程级监听器)。
 *
 * 模型: start() 认领流程(CAS PENDING->RUNNING) -> 构造 DriveContext(含 live ProcessContext/vars) ->
 *   流程级 ProcessListener.before -> **按定义顺序前向走查**根节点 -> 收尾 finalizeProcess(+ ProcessListener.after)。
 *
 * 顶层走查 = setup(前置) -> flow(主流程, 扁平化的顶层表达式) -> setdown(收尾, finally 语义):
 *  - setup/flow 按声明顺序逐个驱动, fail-fast 与否看**流程级全局 errorPolicy**(未声明 = FAIL_FAST):
 *    遇 FAILED/STOPPED 不再启动后续根, 配 CONTINUE_ON_ERROR 则跑完剩余根再聚合;
 *  - setdown **无论成败都执行**(清理锁/上报), 其结果也参与终态聚合(FAILED > STOPPED > SUCCESS)。
 *  - 实例行与有序根表达式按下标对齐(表达式作为走查光标补充进行内, 不落库):
 *    已执行过的行由 NodeDriver 幂等跳过; 缺行按 LAZY/EAGER 补物化——
 *    EAGER 创建期已建全节点树, LAZY 走查到第 i 个才建第 i 个(Materializer.rootAt); lane/action 一律驱动期建。
 *
 * 流程级监听器链从**定义推导**(process_inst 钉死 def_version, 定义不可变, 结果恒定), 直接以
 * List&lt;ListenerDef&gt; 传入 ListenerEngine, 不再固化快照列、不再转 Map。
 *
 * 流程级 vars/result 都由脏标记驱动落库(NodeDriver.flushProcessState): 驱动中随时刷, 收尾再刷一次,
 * 所以 after 监听器写进流程级 result 的内容也能落住。
 *
 * 失败记录(#14 延伸): 引擎各层 catch 到的异常都经 ProcessContext.recordFailure 攒成**一条**摘要(首个胜出),
 * 收尾时坏终态才写进 process_inst.result.__error、好终态反向摘残留, 不再只躺在日志里;
 * 定义装载失败发生在 DriveContext 建成之前(没有 result 桶可脏刷), 由 abortClaimed 直接读写这一行翻 FAILED,
 * 免得流程僵在 running 又查不到原因。门面要那句摘要就走 {@link #errorOf}(读不到只回 null, 不抛)。
 *
 * 取消(#6): 闸门只有 process_inst.status 一份真值, 由 NodeDriver 经 CancellationRegistry 当场读库,
 * 所以干预落在哪个节点都即时可见; 本引擎不持有任何取消状态, 收尾也无需清信号。
 *
 * 自动回退(定义顶层 {@code autoRollback: true}): 收尾终态为 FAILED/STOPPED 时, 本引擎在**落库终态与发完流程
 * after 监听器之后**调一次 RollbackService(见 {@link #autoRollback})。PAUSED 不触发(暂停可恢复), SUCCESS/SKIPPED
 * 不触发; 回退失败只记日志, 不改调用方拿到的终态。除此之外回退照旧是人工入口(门面 doRollback)。
 *
 * 启动结果返回: 终态原本算完就丢(finalizeProcess 的 fin 只落库/发监听), 现在 `start` 直接回调用结束时
 * 读到的流程状态, 让同步启动的门面能当场报成败, 不必再查一次库。三种"什么都没干"(行不存在/非 PENDING/
 * 被人抢先 claim)不抛异常也不造假状态, 只把当下读到的状态回去(读不到行 = null)。
 *
 * 当前用户(#1): 创建人由外部传入并存进 ProcessContext.userId(); 驱动期所有写库的 updator_id 直接取
 * dc.processContext().userId()(无 ThreadLocal, 跨线程靠 DriveContext 本身传递)。
 */
@Service
public class ExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);

    /**
     * 自动回退写进 rollback_record 的操作人标记: 人工回退带的是真实 operator, 两者在补偿历史里分得开
     * (查得到"这一轮是谁拆的"), 也不必为这个给回退再开一个来源参数。
     */
    private static final String AUTO_ROLLBACK_OPERATOR = "auto";

    private final ProcessInstMapper processMapper;
    private final ListenerEngine listeners;
    private final ObserverEngine observers;
    private final DefinitionCache defCache;
    private final ContextFactory ctxFactory;
    private final NodeDriver driver;
    private final VariableResolver resolver;
    private final ConditionEvaluator evaluator;
    private final Materializer materializer;
    private final RollbackService rollback;

    public ExecutionEngine(ProcessInstMapper processMapper,
                           ListenerEngine listeners, ObserverEngine observers, DefinitionCache defCache,
                           ContextFactory ctxFactory, NodeDriver driver,
                           VariableResolver resolver, ConditionEvaluator evaluator, Materializer materializer,
                           RollbackService rollback) {
        this.processMapper = processMapper;
        this.listeners = listeners;
        this.observers = observers;
        this.defCache = defCache;
        this.ctxFactory = ctxFactory;
        this.driver = driver;
        this.resolver = resolver;
        this.evaluator = evaluator;
        this.materializer = materializer;
        this.rollback = rollback;
    }

    // ===================== 流程启动(认领 + 驱动) =====================

    /**
     * 认领并驱动一个流程。CAS 抢占保证同一流程只被一个 owner 驱动。供门面/手动启动调用。
     *
     * @return 调用结束时读到的流程状态: 本调用驱动完毕 = 落库终态; 未接手(行不存在/非 PENDING/被别的 owner 抢先)
     *         = 当下状态; 行不存在 = null
     */
    public Status start(String procInstId) {
        ProcessInstEntity p = processMapper.findById(procInstId);
        if (p == null || !Status.PENDING.getStatus().equals(p.getStatus())) {
            return p == null ? null : Status.of(p.getStatus());
        }
        if (processMapper.claim(procInstId, p.getCreatorId()) != 1) {
            return readStatus(procInstId);   // 抢占失败 = 别的 owner 拿了
        }
        return driveClaimed(procInstId);
    }

    /** 驱动一个已被认领(PENDING->RUNNING 已完成)的流程。Dispatcher 认领后调用, 避免重复 claim。 */
    public Status driveClaimed(String procInstId) {
        ProcessInstEntity p = processMapper.findById(procInstId);
        if (p == null) {
            return null;
        }
        log.info("认领流程 proc={} user={}", procInstId, p.getCreatorId());
        DriveContext dc;
        try {
            dc = buildDriveContext(p);
        } catch (Exception e) {
            // 连定义都取不出(版本缺失/解析失败): 不能让流程僵在 running、原因只躺在日志里
            log.error("流程定义装载异常 proc={} def={} v={}", procInstId, p.getProcessDefId(), p.getDefVersion(), e);
            return abortClaimed(p, Errors.describe(e, "流程定义装载异常", "process=" + procInstId));
        }
        ProcessDefinition def = dc.def();
        List<ListenerDef> procChain = def.getListeners();   // 从定义推导, 直接传对象

        // 全局 Observer.before(纯观察)
        observers.processBefore(dc.processContext());

        // 流程级 before(可否决)
        try {
            listeners.processBefore(procChain, dc.processContext());
        } catch (Exception e) {
            log.error("流程 before 否决 proc={}", procInstId, e);
            dc.processContext().recordFailure(Errors.describe(e, "流程 before 否决", "process=" + procInstId));
            return autoRollback(dc, def, finalizeProcess(dc, procChain, Status.FAILED));
        }

        return autoRollback(dc, def, finalizeProcess(dc, procChain, driveRoots(dc, def)));
    }

    /**
     * 驱动还没铺开就失败(定义装载不了)的兜底收尾: 一句因由写进 process_inst.result.__error, 并翻 FAILED。
     * 这条路没有 DriveContext(定义都取不出), 所以直接读写这一行, 不走 finalizeProcess(不发监听器)。
     */
    private Status abortClaimed(ProcessInstEntity p, String error) {
        Map<String, Object> res = new LinkedHashMap<>(Json.readMap(p.getResult()));
        res.put(Errors.KEY, error);
        processMapper.updateState(p.getProcInstId(), Status.RUNNING.getStatus(), Status.FAILED.getStatus(),
                Json.write(res), null, p.getCreatorId());
        Status actual = readStatus(p.getProcInstId());
        log.info("流程未铺开即失败 proc={} status={}", p.getProcInstId(), actual);
        return actual == null ? Status.FAILED : actual;
    }

    /**
     * 读流程行上那**一条**失败摘要(process_inst.result.__error), 给门面的执行返回值用。
     * 只是取附加信息, 读不到/读坏都回 null, 不把调用方的执行结果搞成异常。
     */
    public String errorOf(String procInstId) {
        try {
            ProcessInstEntity p = processMapper.findById(procInstId);
            return p == null ? null : Errors.readFrom(p.getResult());
        } catch (RuntimeException e) {
            log.warn("流程失败摘要读取失败 proc={}", procInstId, e);
            return null;
        }
    }

    /** 读流程当前状态(行不存在 = null)。DB 是真值。 */
    private Status readStatus(String procInstId) {
        ProcessInstEntity p = processMapper.findById(procInstId);
        return p == null ? null : Status.of(p.getStatus());
    }

    /**
     * 顶层走查: setup(前置) + flow(主流程) + setdown(收尾 finally)。
     * 行与 orderedRoots 按下标对齐; setup/flow 段按**全局 errorPolicy** 决定遇坏是否跳过剩余, setdown 段始终执行。
     * LAZY 下走查到第 i 个才物化第 i 个根(rootAt); EAGER 下节点行创建期已建全, rootAt 只定位对齐。
     */
    private Status driveRoots(DriveContext dc, ProcessDefinition def) {
        List<DslParser.Expr> ordered = def.orderedRoots();
        boolean eager = def.isEager();
        // 根序列属于流程这一层容器: 看全局 errorPolicy(节点级声明只管自己容器内的 action/lane/子节点)
        boolean failFast = def.globalErrorPolicy() == ErrorPolicy.FAIL_FAST;
        int flowStart = def.setupSize();                              // setup 段 [0, flowStart)
        int flowEnd = flowStart + def.roots().size();                 // flow 段 [flowStart, flowEnd)
        Status s = Status.SUCCESS;

        try {
            for (int i = 0; i < ordered.size(); i++) {
                NodeInstEntity root = materializer.rootAt(dc, ordered, i, eager);
                s = Status.aggregate(s, driver.driveNode(dc, root));
                if (failFast && i < flowEnd && (s == Status.FAILED || s == Status.STOPPED)) {
                    i = flowEnd - 1;   // FAIL_FAST: 跳过 setup/flow 剩余(循环自增后从 setdown 段继续)
                }
            }
        } catch (Exception e) {
            log.error("驱动流程异常 proc={}", dc.procInstId(), e);
            // 顶层走查自己炸了(如 flow 引用未定义节点、物化失败): 因由除了进日志也记进流程那一条
            dc.processContext().recordFailure(Errors.describe(e, "驱动流程异常", "process=" + dc.procInstId()));
            s = Status.FAILED;
        }
        return s;
    }

    private DriveContext buildDriveContext(ProcessInstEntity p) {
        ProcessDefinition def = defCache.definition(p.getProcessDefId(), p.getDefVersion());
        ProcessContext pctx = ctxFactory.process(p);            // 加载 live vars + userId(=creator_id)
        pctx.attachDefinition(def);                              // 挂定义: ProcessListener 可据 ctx 读全部节点定义
        return DriveContext.create(p, def, pctx, resolver, evaluator);
    }

    // ===================== 流程收尾 =====================

    /**
     * 流程收尾: 落那一条失败记录 + 落终态 + 发 after。
     *
     * @return 落库成功 = 聚合终态 fin; 被人工 STOP/PAUSE 抢先翻走 = 重读 DB 拿实际状态(以 DB 为真值)
     */
    private Status finalizeProcess(DriveContext dc, List<ListenerDef> procChain, Status aggStatus) {
        String procInstId = dc.procInstId();
        // SKIPPED/SUCCESS 对流程都算成功完成; 终态按 severity 优先级聚合(FAILED > STOPPED > SUCCESS)
        Status fin = Status.aggregate(Status.SUCCESS, aggStatus);
        // 坏终态才把驱动中记下的首条异常摘要落 process_inst.result.__error; 好终态传 null = 摘掉上一轮残留
        // (重跑成功后不该还挂着失败信息)。写走脏标记, 随下面这次 flush 与 vars 同条 updateState 落库。
        dc.processContext().settleFailure(fin == Status.FAILED || fin == Status.STOPPED
                ? dc.processContext().failure() : null);
        driver.flushProcessState(dc, dc.processContext());
        // 仅当仍是 RUNNING(未被人工 STOP/PAUSE 抢先翻走)才落终态
        boolean settled = processMapper.casStatus(procInstId, Status.RUNNING.getStatus(), fin.getStatus(),
                dc.processContext().userId()) == 1;
        if (settled) {
            ProcessInstEntity fresh = processMapper.findById(procInstId);
            dc.processContext().putAllVars(Json.readMap(fresh.getVars()));
            listeners.processAfter(procChain, dc.processContext(), fin);   // 纯通知
            observers.processAfter(dc.processContext(), fin);              // 全局 Observer.after
            driver.flushProcessState(dc, dc.processContext());             // 收尾监听器写的 vars/result 也落库
            log.info("流程结束 proc={} status={}", procInstId, fin);
            return fin;
        }
        Status actual = readStatus(procInstId);
        log.info("流程收尾时状态被抢先翻走 proc={} 聚合={} 实际={}", procInstId, fin, actual);
        return actual;
    }

    // ===================== 自动回退(定义级 autoRollback) =====================

    /**
     * 定义配了顶层 {@code autoRollback: true} 时, 本次驱动的终态是 FAILED/STOPPED 就当场逆序补偿。
     *
     * 口径:
     *  - 判的是**收尾后库上那个状态**({@link #finalizeProcess} 的返回值), 所以人工 stop 抢先翻成 STOPPED
     *    同样算"停止后回退"; pause 停在 PAUSED 不算——暂停是可恢复的, 不该顺手把副作用拆掉;
     *  - 排在 setdown(finally) 与流程 after 监听器之后: 监听器看到的终态与库里一致, 回退那对
     *    RollbackListener 切点随后单独触发;
     *  - 回退自身的任何异常都不外抛: 终态已经落库, 不能让补偿失败改写调用方拿到的结果; 原因进日志,
     *    补偿失败本身还会单独落一条 COMPENSATE_FAILED 的 rollback_record;
     *  - 不改流程终态: action_inst / process_inst 的状态都是执行事实, 撤销历史只留在 rollback_record。
     *
     * 定义装载失败那条路(abortClaimed)取不到 autoRollback 声明, 也就无从自动回退——那时流程体还没铺开。
     */
    private Status autoRollback(DriveContext dc, ProcessDefinition def, Status fin) {
        if (def == null || !def.autoRollbackEnabled() || (fin != Status.FAILED && fin != Status.STOPPED)) {
            return fin;
        }
        String procInstId = dc.procInstId();
        log.info("自动回退触发 proc={} status={}(定义声明 autoRollback)", procInstId, fin);
        try {
            RollbackService.RollbackReport report = rollback.rollback(procInstId, AUTO_ROLLBACK_OPERATOR);
            log.info("自动回退结束 proc={} outcome={} 已补偿={} 不可补偿={} 补偿失败={} 跳过={} 否决={}",
                    procInstId, report.outcome(), report.compensated.size(), report.blocked.size(),
                    report.failed.size(), report.skipped.size(), report.veto);
        } catch (Exception e) {
            log.error("自动回退异常 proc={}", procInstId, e);
        }
        return fin;
    }
}
