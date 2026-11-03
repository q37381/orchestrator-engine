package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.context.ActionContext;
import com.tt.ii.process.core.context.ProcessContext;
import com.tt.ii.process.core.model.RollbackStatus;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.core.runtime.ContextFactory;
import com.tt.ii.process.core.spi.ActionHandler;
import com.tt.ii.process.core.spi.ActionHandlerRegistry;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.domain.ProcessInstEntity;
import com.tt.ii.process.domain.RollbackRecordEntity;
import com.tt.ii.process.mapper.ActionInstMapper;
import com.tt.ii.process.mapper.ProcessInstMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向前重放服务(Saga, 恢复现场)。回退(向后恢复)把已成功的副作用逆序撤销后, 一旦重试重新驱动,
 * 本服务在驱动路径上把这些副作用**按执行顺序正序**重新施加回来, 于是失败节点能在恢复好的现场上续跑,
 * 而不必从流程头重跑(见 {@link ExecutionEngine} 对 {@link #replay} 的接线)。
 *
 * 与 {@link RollbackService} 的分工: 那边只管"拆"(人工/auto 触发的逆序补偿), 这边只管"再立起来"(驱动前重放);
 * 两者都不改原 action_inst 状态(它是不可变的执行事实), 只在 rollback_record 上各自追加一段历史。
 *
 * 重放哪些 action 由每条 action 的**最新一条 rollback_record** 决定(与补偿"最新是 COMPENSATED 就跳过"同口径):
 *  - 最新 = COMPENSATED(或重放中途崩坏的 RESTORING / RESTORE_FAILED): 副作用还欠着恢复 -> 重放;
 *  - 最新 = RESTORED: 现场已经立起来 -> 跳过(幂等, 于是闭环可反复执行: 回退->重放->再回退->再重放)。
 * 从没回退过的流程只多一条 {@code findByProcess} 查询返回空即当场返回, 不给正常驱动加重负担。
 *
 * 现场凭据: 不引入 restoreListeners、不新增 DSL 段——重放上下文与补偿路径同口径重建(节点/lane/payload +
 * 激活期占位符解析), 需要连接串/凭据的 handler 在自己的 {@link ActionHandler#restore} 里现取。默认 restore()
 * 直接委托 handle(), 故只要 handle 幂等就天然可重放。
 *
 * 一条重放失败即当场挂起(打 RESTORE_FAILED 并停止, 不继续重放其余、更不向前驱动): 现场处于半恢复的坏状态,
 * 硬着头皮往下跑只会把不一致扩散, 交给运维修好后再次重试(retry 会重新入队, 本服务再从没立稳的那条接着放)。
 *
 * 协作件: 记录写入归 {@link RollbackTrail}, 上下文重建归 {@link CompensationContexts};
 * 本类只保留"哪些 action 欠重放、按什么顺序放、失败如何停"的决策。
 */
@Service
public class RestoreService {

    private static final Logger log = LoggerFactory.getLogger(RestoreService.class);

    private final ProcessInstMapper processMapper;
    private final ActionInstMapper actionMapper;
    private final ActionHandlerRegistry handlers;
    private final ContextFactory ctxFactory;
    private final RollbackTrail rollbackTrail;
    private final CompensationContexts compensationContexts;

    public RestoreService(ProcessInstMapper processMapper, ActionInstMapper actionMapper,
                          ActionHandlerRegistry handlers, ContextFactory ctxFactory,
                          RollbackTrail rollbackTrail, CompensationContexts compensationContexts) {
        this.processMapper = processMapper;
        this.actionMapper = actionMapper;
        this.handlers = handlers;
        this.ctxFactory = ctxFactory;
        this.rollbackTrail = rollbackTrail;
        this.compensationContexts = compensationContexts;
    }

    /**
     * 驱动前重放: 把回退撤销过的副作用按原执行顺序重新施加回来。
     *
     * @param operator 本次驱动的归属人(取流程创建人, 与驱动路径其它写库同一 updator 口径)
     */
    public RestoreReport replay(String procInstId, String operator) {
        RestoreReport report = new RestoreReport();
        ProcessInstEntity p = processMapper.findById(procInstId);
        if (p == null) {
            log.warn("重放目标流程不存在 proc={}", procInstId);
            return report;
        }

        List<RollbackRecordEntity> history = rollbackTrail.history(procInstId);
        if (history.isEmpty()) {
            return report;                       // 从没回退过: 绝大多数流程走这条, 一次查询即返回
        }
        List<ActionInstEntity> pending = collectPendingRestore(history);
        if (pending.isEmpty()) {
            return report;                       // 回退过但现场已全部重放到位(最新一条都是 RESTORED)
        }

        ProcessContext pctx = ctxFactory.process(p);
        for (ActionInstEntity a : pending) {
            // 先落 RESTORING 占位一行(与补偿同一手法), 成功后转 RESTORED / 失败转 RESTORE_FAILED
            String rid = rollbackTrail.begin(procInstId, a, RollbackStatus.RESTORING, operator, "restoring");
            try {
                ActionHandler h = handlers.get(a.getHandlerName());
                ActionContext actx = compensationContexts.rebuild(pctx, a, "重放");
                h.restore(actx);
                rollbackTrail.update(rid, RollbackStatus.RESTORED, operator, "restored", null);
                report.restored(a.getActionDefId());
            } catch (Exception e) {
                rollbackTrail.update(rid, RollbackStatus.RESTORE_FAILED, operator, "重放失败", e.getMessage());
                report.failed(a.getActionDefId(), e.getMessage());
                log.error("向前重放失败 proc={} action={}, 挂起不向前驱动", procInstId, a.getActionDefId(), e);
                break;                            // 现场处于半恢复: 不再重放其余, 交由调用方挂起
            }
        }
        if (!report.restored.isEmpty()) {
            log.info("向前重放结束 proc={} 已恢复={} 失败={}", procInstId, report.restored.size(), report.failed.size());
        }
        return report;
    }

    /**
     * 从补偿/重放历史里挑出"当前欠着恢复"的 action: 按 action 归并取最新一条(列表已 auto_id 升序, 后写覆盖前写),
     * 只留下最新态还处在 COMPENSATED / RESTORING / RESTORE_FAILED 的那些, 再按原 action 执行顺序(auto_id 升序)排好。
     */
    private List<ActionInstEntity> collectPendingRestore(List<RollbackRecordEntity> history) {
        Map<String, String> latestByAction = new LinkedHashMap<>();
        for (RollbackRecordEntity r : history) {
            latestByAction.put(r.getActionInstId(), r.getStatus());   // 升序遍历 -> 留下的就是最新一条
        }
        List<ActionInstEntity> pending = new ArrayList<>();
        for (Map.Entry<String, String> e : latestByAction.entrySet()) {
            if (!needsRestore(e.getValue())) {
                continue;
            }
            ActionInstEntity a = actionMapper.findById(e.getKey());
            if (a != null && Status.SUCCESS.getStatus().equals(a.getStatus())) {
                pending.add(a);
            }
        }
        pending.sort(RowOrder.ACTION_ASC);   // 正序 = 原执行顺序(补偿是逆序, 重放要正序)
        return pending;
    }

    /** 最新态是否需要(再)重放: 撤销过但没恢复好 = 欠恢复; 恢复成功 = 不欠; 其余补偿坏态 = 与本服务无关。 */
    private static boolean needsRestore(String status) {
        RollbackStatus s = RollbackStatus.of(status);
        return s == RollbackStatus.COMPENSATED       // 已成功撤销, 现场还没重新施加
                || s == RollbackStatus.RESTORING      // 上次重放中途崩溃: handle 幂等, 再来一次
                || s == RollbackStatus.RESTORE_FAILED; // 上次重放失败: 修好后重试再来一次
    }

    /** 重放结果摘要。 */
    public static class RestoreReport {
        /** 本次成功恢复(重新施加现场)的 action 定义 id。 */
        public final List<String> restored = new ArrayList<>();
        /** 本次重放失败的 action 定义 id + 因由(一旦有失败即停止, 至多一条)。 */
        public final List<String> failed = new ArrayList<>();

        void restored(String a) {
            restored.add(a);
        }
        void failed(String a, String why) {
            failed.add(a + ": " + why);
        }

        /** 是否有重放失败: 调用方据此挂起、不向前驱动。 */
        public boolean hasFailure() {
            return !failed.isEmpty();
        }
        /** 什么都没重放(没回退过 / 现场已到位)。 */
        public boolean isEmpty() {
            return restored.isEmpty() && failed.isEmpty();
        }
        /** 失败因由首条(供写入流程级失败摘要); 无失败返回 null。 */
        public String firstFailure() {
            return failed.isEmpty() ? null : failed.get(0);
        }
    }
}
