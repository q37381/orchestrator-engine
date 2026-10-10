package com.tesla.ities.process.api;

import com.tesla.ities.process.core.engine.ExecutionEngine;
import com.tesla.ities.process.core.engine.InterventionService;
import com.tesla.ities.process.core.engine.PlanService;
import com.tesla.ities.process.core.engine.ProgressService;
import com.tesla.ities.process.core.engine.RollbackService;
import com.tesla.ities.process.core.model.Status;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 统一门面: 对外只暴露一个入口, 组合 Plan / Execution / Intervention / Progress / Rollback。
 * 所有业务键为 varchar(20) 字符串(auto_id 物理主键不外露)。
 *
 * 返回口径: 只创建不执行的(create)返 procInstId; **带执行的**(createAndStart / start)返
 * {@link ProcessExecution}(流程实例 id + 调用结束时的状态, 坏终态再附那一条失败摘要), 免调用方再查一次库;
 * 干预类仍返 boolean, 回退返报告。
 *
 * 语法糖: 嫌 {@code (defId, version, params, userId)} 四个位置参数 + 手搓 Map 啰嗦, 就
 * {@code facade.run(defId).version(v).user(u).param(k, v).start()} —— 见 {@link ProcessRequest},
 * 它只是把参数攒起来末尾转调本类的 create/createAndStart, 引擎侧签名与行为一律没动。
 *
 * 当前用户(#1): 引擎不管会话——创建方把自己取好的 userId 传给 create/createAndStart, 落 creator_id 并进
 * ProcessContext.userId; 驱动期 updator_id 用的就是它。干预类入口的 operator 即为本次改动的 updator_id。
 */
@Service
public class ProcessFacade {

    private final PlanService planService;
    private final ExecutionEngine engine;
    private final InterventionService intervention;
    private final ProgressService progress;
    private final RollbackService rollback;

    public ProcessFacade(PlanService planService, ExecutionEngine engine,
                         InterventionService intervention, ProgressService progress,
                         RollbackService rollback) {
        this.planService = planService;
        this.engine = engine;
        this.intervention = intervention;
        this.progress = progress;
        this.rollback = rollback;
    }

    /**
     * 流式构建入口(语法糖): 链式填版本/创建人/参数, 末尾 {@code create()} 只建、{@code start()} 建并跑。
     * 参数一条都没填时传 null —— 与直接调 {@code createAndStart(defId, version, null, userId)} 完全等价。
     */
    public ProcessRequest run(String defId) {
        return new ProcessRequest(this, defId);
    }

    /** 流式构建入口 + 批量灌已有参数 Map(等价 {@code run(defId).params(m)})。 */
    public ProcessRequest run(String defId, Map<String, Object> params) {
        return new ProcessRequest(this, defId).params(params);
    }

    /** 创建流程实例(流程 PENDING; 物化按定义 EAGER/LAZY), 返回 procInstId。 */
    public String create(String defId, Integer version, Map<String, Object> params) {
        return planService.createProcess(defId, version, params);
    }

    /** 创建流程实例并指定创建人(由调用方获取好传入; null = 不记创建人)。 */
    public String create(String defId, Integer version, Map<String, Object> params, String userId) {
        return planService.createProcess(defId, version, params, userId);
    }

    /**
     * 创建流程实例 + **可选路由**(分布式调度): 给了 schedUnit/schedZone 才写调度行 -> 参与主动调度;
     * 任一为空则不写 -> 调度器不主动认领(仍可 {@link #start(String)} 同步触发)。老的四参调用等价于不路由。
     */
    public String create(String defId, Integer version, Map<String, Object> params, String userId,
                         String schedUnit, String schedZone) {
        return planService.createProcess(defId, version, params, userId, schedUnit, schedZone);
    }

    /** 创建并立即启动: create + start 一步到位(全自动执行主入口)。 */
    public ProcessExecution createAndStart(String defId, Integer version, Map<String, Object> params) {
        return createAndStart(defId, version, params, null);
    }

    /** 创建并立即启动, 显式指定创建人。 */
    public ProcessExecution createAndStart(String defId, Integer version, Map<String, Object> params, String userId) {
        String procInstId = planService.createProcess(defId, version, params, userId);
        return execution(procInstId, engine.start(procInstId));
    }

    /** 创建并立即启动 + 可选路由(同 {@link #create(String, Integer, Map, String, String, String)} 的路路口径)。 */
    public ProcessExecution createAndStart(String defId, Integer version, Map<String, Object> params, String userId,
                                           String schedUnit, String schedZone) {
        String procInstId = planService.createProcess(defId, version, params, userId, schedUnit, schedZone);
        return execution(procInstId, engine.start(procInstId));
    }

    /**
     * 启动已创建但仍 PENDING 的流程(调度器也会自动捞, 此方法用于同步触发)。
     * 已非 PENDING / 行不存在 / 被别的 owner 抢先都不抛异常: 当下状态就在返回值里。
     */
    public ProcessExecution start(String procInstId) {
        return execution(procInstId, engine.start(procInstId));
    }

    /**
     * 执行类入口的统一出口: 坏终态(FAILED/STOPPED)才多读一次流程行, 把那条失败摘要捎带给调用方
     * (成功路径不查库, 也不改变原有语义); 完整进度/明细照旧走 {@link #progress}。
     */
    private ProcessExecution execution(String procInstId, Status status) {
        boolean bad = status == Status.FAILED || status == Status.STOPPED;
        return new ProcessExecution(procInstId, status, bad ? engine.errorOf(procInstId) : null);
    }

    public boolean pause(String procInstId, String operator) {
        return intervention.pause(procInstId, operator);
    }
    public boolean resume(String procInstId, String operator) {
        return intervention.resume(procInstId, operator);
    }
    public boolean stop(String procInstId, String operator) {
        return intervention.stop(procInstId, operator);
    }

    public boolean retryAction(String actionInstId, String operator) {
        return intervention.retryAction(actionInstId, operator);
    }
    public boolean skipAction(String actionInstId, String operator) {
        return intervention.skipAction(actionInstId, operator);
    }

    public boolean requestRollback(String procInstId, String operator) {
        return intervention.requestRollback(procInstId, operator);
    }

    /** 执行回退(逆序补偿), 返回报告。 */
    public RollbackService.RollbackReport doRollback(String procInstId, String operator) {
        return rollback.rollback(procInstId, operator);
    }

    /** 进度查询(校验流程前端轮询用)。 */
    public Map<String, Object> progress(String procInstId) {
        return progress.progress(procInstId);
    }
}
