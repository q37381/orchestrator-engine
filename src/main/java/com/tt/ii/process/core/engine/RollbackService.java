package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.define.ListenerDef;
import com.tt.ii.process.core.define.ProcessDefinition;
import com.tt.ii.process.core.runtime.ContextFactory;
import com.tt.ii.process.core.runtime.DefinitionCache;
import com.tt.ii.process.core.model.ActionSegment;
import com.tt.ii.process.core.model.RollbackStatus;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.core.spi.ActionHandler;
import com.tt.ii.process.core.spi.ActionHandlerRegistry;
import com.tt.ii.process.domain.*;
import com.tt.ii.process.mapper.*;
import com.tt.ii.process.util.Json;
import com.tt.ii.process.core.context.ActionContext;
import com.tt.ii.process.core.context.ProcessContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 回退/补偿服务(Saga, 向后恢复)。人工触发走门面; 定义配了顶层 {@code autoRollback: true} 时
 * 由 ExecutionEngine 在驱动收尾(终态 FAILED/STOPPED)自动调一次 {@link #rollback} —— 本类不感知是谁调的。
 *
 * 机制: 逆序对"已成功且 handler 按该 action 的 config 判定可补偿(compensable(ctx))"的 action 调 compensate()。
 * 遇不可补偿步骤 -> 停下、标记 BLOCKED、告警。补偿自身失败 -> COMPENSATE_FAILED, 挂起告警, 不无限重试。
 * **已成功补偿过的 action 不再重复撤销**(看该 action 最新一条 rollback_record 是否 COMPENSATED):
 * 原 action 状态是不可变的执行事实, 分不清补没补过, 而补过再补一次(二次删除/二次回滚)通常比漏补更糟。
 *
 * v1.2(#16): 补偿不再改原 action_inst.status(它是不可变的执行事实), 而是**每个补偿步骤单独入
 * rollback_record 一行**, 通过 action_inst_id 关联原实例。状态机: COMPENSATING -> COMPENSATED /
 * COMPENSATE_FAILED / BLOCKED。补偿历史与运行态彻底隔离, 可多次回退留痕。
 *
 * 回退前置/收尾(RollbackListener): 补偿不走 driveRoots, 所以 setup/setdown 与三类监听器在这里一个也不触发;
 * 回退自己的切点从定义的 rollbackListeners 段推导(与流程级 listeners 同构), before 可**否决整轮补偿**
 * (一条都不补, 报告记 veto), after 只收聚合 outcome。现场信息(连接串/凭据)在 before 里
 * {@code ctx.putResult("dbConn", ...)} 挂流程级 result 桶——**回退路径不刷 process_inst.result**(引擎只在
 * 驱动路径脏刷, 见 NodeDriver.flushProcessState), 所以凭据不入库; compensate 里
 * {@code ctx.resolveResult("dbConn.host")} 或 config 写 {@code ${result.dbConn.host}} 都能读到。
 *
 * 占位符: 补偿前对 action config 跑一次 ACTIVATION 解析(与驱动路径同口径), 否则 ${result.*} 与运行期
 * 新写入的 vars 在补偿路径永远是 PLAN 冻结后的原文。
 *
 * 协作件: 树遍历归 {@link InstanceTree}, 记录写入归 {@link RollbackTrail}, 上下文重建归
 * {@link CompensationContexts}; 本类只保留补偿决策与流程编排。
 */
@Service
public class RollbackService {

    private static final Logger log = LoggerFactory.getLogger(RollbackService.class);

    private final ProcessInstMapper processMapper;
    private final LaneMapper laneMapper;
    private final ActionInstMapper actionMapper;
    private final ActionHandlerRegistry handlers;
    private final ListenerEngine listeners;
    private final DefinitionCache defCache;
    private final ContextFactory ctxFactory;
    private final InstanceTree instanceTree;
    private final RollbackTrail rollbackTrail;
    private final CompensationContexts compensationContexts;

    public RollbackService(ProcessInstMapper processMapper, LaneMapper laneMapper,
                           ActionInstMapper actionMapper, ActionHandlerRegistry handlers,
                           ListenerEngine listeners, DefinitionCache defCache, ContextFactory ctxFactory,
                           InstanceTree instanceTree, RollbackTrail rollbackTrail,
                           CompensationContexts compensationContexts) {
        this.processMapper = processMapper;
        this.laneMapper = laneMapper;
        this.actionMapper = actionMapper;
        this.handlers = handlers;
        this.listeners = listeners;
        this.defCache = defCache;
        this.ctxFactory = ctxFactory;
        this.instanceTree = instanceTree;
        this.rollbackTrail = rollbackTrail;
        this.compensationContexts = compensationContexts;
    }

    /** 逆序补偿整个流程已成功的 action。每个补偿步骤写一行 rollback_record(关联原 action_inst)。 */
    public RollbackReport rollback(String procInstId, String operator) {
        RollbackReport report = new RollbackReport();
        ProcessInstEntity p = processMapper.findById(procInstId);
        if (p == null) {
            log.warn("回退目标流程不存在 proc={}", procInstId);
            return report;
        }

        List<ActionInstEntity> succeeded = collectSucceededActionsDesc(procInstId);
        if (succeeded.isEmpty()) {
            log.info("无可补偿的已成功 action, 回退空转 proc={}", procInstId);
            return report;   // 没东西可补: 不拉前置监听器(不白查一次连接信息)
        }

        ProcessContext pctx = ctxFactory.process(p);
        ProcessDefinition def = defCache.definition(p.getProcessDefId(), p.getDefVersion());
        pctx.attachDefinition(def);                    // 与驱动路径同口径: 监听器可读全部节点定义
        List<ListenerDef> chain = def.getRollbackListeners();

        // 回退前置(可否决): 现取连接信息/凭据之类的现场准备, 产出挂 pctx 内存桶(不落库)
        try {
            listeners.rollbackBefore(chain, pctx);
        } catch (Exception e) {
            log.error("回退前置否决, 整轮补偿不执行 proc={}", procInstId, e);
            report.vetoed(e.getMessage());
            finishRollback(chain, pctx, procInstId, operator, report);
            return report;
        }

        for (ActionInstEntity a : succeeded) {
            // 已经补成功的不再重复撤销(原 action 状态是不可变的执行事实, 单看它分不清"补过没补过"):
            // 自动回退(定义配了 autoRollback)会让同一流程的回退不止一次跑到(重试再失败/人工又点一次),
            // 重复 compensate 一个已撤销的副作用通常比漏补更糟(二次删除/二次回滚别人的资源), 所以这里当场跳过并留数。
            // 放在可补偿判定之前: 已撤销的步骤不欠账, 不该因为 handler 现在按 config 判成不可补偿又挡住前面的步骤。
            if (rollbackTrail.latestCompensated(a.getActionInstId())) {
                report.skipped(a.getActionDefId());
                log.info("action 已成功补偿过, 本次不重复撤销 proc={} action={}", procInstId, a.getActionDefId());
                continue;
            }

            ActionHandler h = handlers.get(a.getHandlerName());

            // 先落 COMPENSATING 一行(占位), 再执行补偿, 成功后更新为 COMPENSATED / 失败 COMPENSATE_FAILED
            String rid = rollbackTrail.begin(procInstId, a, RollbackStatus.COMPENSATING, operator, "compensating");

            // 上下文重建与补偿同在一个 try 里: buildPayload 可能因拓扑变更抛错,
            // 否则会把 COMPENSATING 占位行永远挂在那里、异常还穿透到调用方。
            try {
                ActionContext actx = compensationContexts.rebuild(pctx, a, "补偿");

                // 可补偿性按**本 action 的 config** 现判(与 compensate 同一份重建上下文):
                // false -> 占位行改 BLOCKED 并停下(FR-RBK-03), 不跳过它继续拆前面的步骤。
                if (!h.compensable(actx)) {
                    rollbackTrail.update(rid, RollbackStatus.BLOCKED, operator, "不可补偿, 回退到此停止", null);
                    report.blocked(a.getActionDefId(), "不可补偿, 回退到此停止");
                    log.warn("回退遇不可补偿步骤 proc={} action={}, 停止告警", procInstId, a.getActionDefId());
                    break;
                }

                h.compensate(actx);
                rollbackTrail.update(rid, RollbackStatus.COMPENSATED, operator, "compensated", null);
                report.compensated(a.getActionDefId());
            } catch (Exception e) {
                rollbackTrail.update(rid, RollbackStatus.COMPENSATE_FAILED, operator, "补偿失败", e.getMessage());
                report.failed(a.getActionDefId(), e.getMessage());
                log.error("补偿失败 proc={} action={}, 挂起告警", procInstId, a.getActionDefId(), e);
                break;   // 不无限重试
            }
        }
        finishRollback(chain, pctx, procInstId, operator, report);
        return report;
    }

    /** 回退收尾(纯通知) + 把前置监听器写过的 vars 刷回库(否则 putVar 静默丢)。
     *  result 桶**故意不刷**: 前置现取的连接串/凭据只活在本次回退的内存里。 */
    private void finishRollback(List<ListenerDef> chain, ProcessContext pctx,
                                String procInstId, String operator, RollbackReport report) {
        listeners.rollbackAfter(chain, pctx, report.outcome());
        if (pctx.isVarsDirty()) {
            processMapper.updateVars(procInstId, Json.write(pctx.vars()), operator);
            pctx.clearVarsDirty();
        }
    }

    /**
     * 收集"已成功且在主段(segment=main)"的 action, 按 auto_id 降序(逆序补偿的依据)。
     * setup/setdown 段行**不参与**回退: 段动作多为"暂停/恢复"这类不可补偿的编排辅助(compensable(ctx)=false),
     * 若纳入, 遇不可补偿即 blocked 会断掉每一次回退(FR-RBK-03); 需要被撤销的动作请放主段。
     */
    private List<ActionInstEntity> collectSucceededActionsDesc(String procInstId) {
        String main = ActionSegment.MAIN.getStatus();
        List<ActionInstEntity> result = new ArrayList<>();
        for (NodeInstEntity n : instanceTree.all(procInstId)) {
            for (ActionInstEntity a : actionMapper.findInContainer(n.getNodeInstId(), null, null, main)) {
                if (Status.SUCCESS.getStatus().equals(a.getStatus())) {
                    result.add(a);
                }
            }
            for (LaneEntity l : laneMapper.findByNode(n.getNodeInstId())) {
                for (ActionInstEntity a : actionMapper.findInContainer(n.getNodeInstId(), l.getLaneId(), null, main)) {
                    if (Status.SUCCESS.getStatus().equals(a.getStatus())) {
                        result.add(a);
                    }
                }
            }
        }
        result.sort(RowOrder.ACTION_DESC);
        return result;
    }

    /** 回退结果摘要。 */
    public static class RollbackReport {
        public final List<String> compensated = new ArrayList<>();
        public final List<String> blocked = new ArrayList<>();
        public final List<String> failed = new ArrayList<>();
        /** 因"上一次已补偿成功"而跳过撤销的 action(不重复撤销, 也不影响 outcome)。 */
        public final List<String> skipped = new ArrayList<>();
        /** 回退前置监听器否决时的原因; null = 未被否决。 */
        public String veto;
        void compensated(String a) {
            compensated.add(a);
        }
        void blocked(String a, String why) {
            blocked.add(a + ": " + why);
        }
        void failed(String a, String why) {
            failed.add(a + ": " + why);
        }
        void skipped(String a) {
            skipped.add(a);
        }
        void vetoed(String why) {
            veto = why;
        }

        /** 聚合结果(供回退收尾监听器与调用方): FAILED(被否决/有补偿失败) > STOPPED(遇不可补偿步骤停下) > SUCCESS。 */
        public Status outcome() {
            if (veto != null || !failed.isEmpty()) {
                return Status.FAILED;
            }
            return blocked.isEmpty() ? Status.SUCCESS : Status.STOPPED;
        }
    }
}
