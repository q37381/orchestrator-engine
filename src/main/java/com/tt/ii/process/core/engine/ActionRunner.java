package com.tt.ii.process.core.engine;

import com.tt.ii.process.constants.ReservedKeys;
import com.tt.ii.process.core.context.ActionContext;
import com.tt.ii.process.core.context.Context;
import com.tt.ii.process.core.context.NodeContext;
import com.tt.ii.process.core.context.ProcessContext;
import com.tt.ii.process.core.define.ActionDef;
import com.tt.ii.process.core.define.ListenerDef;
import com.tt.ii.process.core.define.ProcessDefinition;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.core.runtime.CancellationRegistry;
import com.tt.ii.process.core.runtime.ContextFactory;
import com.tt.ii.process.core.spi.ActionHandler;
import com.tt.ii.process.core.spi.ActionHandlerRegistry;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.mapper.ActionInstMapper;
import com.tt.ii.process.util.Errors;
import com.tt.ii.process.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * action 运行期执行器: 单个 action 从解析到落库的**全生命周期**归口(激活期解析 -> guard -> BEFORE ->
 * handler -> 持久化 -> AFTER), 外加 drain 落行({@link #markActionDrained})。
 *
 * 从 {@link NodeDriver} 拆出的理由(Temporal 的 workflow/activity 分工同款): 节点递归编排与 action 副作用
 * 生命周期是两件事, 混在一类里既撑胖了门面(原 13 个构造依赖), 又让走查层类被迫面向整个驱动器。
 * 容器内的**序列走查**(定位/档/收敛/drain 判定)随后收进 {@link LayerWalker}, 本类只剩单项执行一职——
 * 三方都只实现自己那一份角色接口。
 *
 * 行为口径:
 *  - 结果只留在 action 自己的桶(引擎不自动上挂容器), 跨层共享由使用者显式放置;
 *  - 失败/跳过/残留占位符落保留键 __error/__skipReason/__unresolved, 写入前并入原有 result;
 *    重入(干预复位后重跑)先摘上一轮痕迹, 跑成功的行不该还挂着失败信息;
 *  - 让渡(handler 返回 pending/paused/suspended): 照常落行状态 + 发 after, 但状态就是让渡态——
 *    层模板据此短路退出(后续段/根不跑), 流程行落该状态等重入(见 ExecutionEngine.yieldProcess);
 *    重入时非终态行重跑(at-least-once, 终态行才跳过); running/created 不是合法让渡态, 当场拒;
 *  - 取消位当场读库(actx.cancel, 供业务自愿收敛, 不强行中断), 流程级脏桶经 {@link ProcessStateFlusher} 刷写。
 */
@Component
public class ActionRunner implements ActionExecution {

    private static final Logger log = LoggerFactory.getLogger(ActionRunner.class);

    private final ActionInstMapper actionMapper;
    private final ActionHandlerRegistry handlerRegistry;
    private final ListenerEngine listeners;
    private final ObserverEngine observers;
    private final ContextFactory ctxFactory;
    private final CancellationRegistry cancellation;
    private final ConfigFreeze freeze;
    private final ProcessStateFlusher flusher;

    public ActionRunner(ActionInstMapper actionMapper, ActionHandlerRegistry handlerRegistry,
                        ListenerEngine listeners, ObserverEngine observers, ContextFactory ctxFactory,
                        CancellationRegistry cancellation, ConfigFreeze freeze, ProcessStateFlusher flusher) {
        this.actionMapper = actionMapper;
        this.handlerRegistry = handlerRegistry;
        this.listeners = listeners;
        this.observers = observers;
        this.ctxFactory = ctxFactory;
        this.cancellation = cancellation;
        this.freeze = freeze;
        this.flusher = flusher;
    }

    // ===================== 单项生命周期 =====================

    /**
     * 执行单个 action: 解析 config -> guard -> BEFORE -> handler -> 持久化 -> AFTER。
     */
    @Override
    public Status runAction(DriveContext dc, Context parent,
                            ActionInstEntity action, ActionDef ad) {
        String procInstId = dc.procInstId();
        ProcessContext pctx = dc.processContext();

        // 分片 payload 现场重建(阶段2, 不落库): 非分片 action 无 shardKey -> null
        NodeContext ctx = nodeOf(parent);
        Object payload = ctxFactory.buildPayload(ctx, action.getShardKey());
        ActionContext actx = ctxFactory.action(parent, action, payload);
        if (cancellation.isCancelled(procInstId)) {
            actx.cancel();
        }
        // 重入(干预复位后重跑)先把上一轮留下的痕迹从本行桶里摘干净: 与流程级 settleFailure 同口径,
        // "重跑成功后不该还挂着失败信息"。本轮该记的 __error/__skipReason/__unresolved 由后续各分支重新写。
        actx.result().remove(Errors.KEY);
        actx.result().remove(ReservedKeys.SKIP_REASON);
        actx.result().remove(ReservedKeys.UNRESOLVED);

        Map<String, Object> rawCfg = Json.readMap(action.getConfig());
        // 链 = 全局 action 级链 + action 自己声明的链合并(全局在前); 分片时每 lane 的每个 action 都走
        List<ListenerDef> actionChain = ProcessDefinition.mergeWithGlobals(dc.def().getGlobalActionListeners(),
                ad == null ? null : ad.getListeners());

        // 1) 激活期解析 config(${result.*} + 运行期新写入 vars, 如方言)
        actx.replaceConfig(freeze.freezeActivation(dc.resolver(), actx, rawCfg));

        // 1b) 两档都解不出的键(如指向不存在变量的 ${vars.x}): 告警 + 记进 result.__unresolved,
        //     不再静默把占位符喂给 handler; 与 __error/__skipReason 同族, 供 guard/进度读。
        List<String> pending = dc.resolver().findUnresolved(actx.config());
        if (!pending.isEmpty()) {
            log.warn("config 仍含未解析占位符 proc={} action={} keys={}", procInstId, action.getActionDefId(), pending);
            actx.putResult(ReservedKeys.UNRESOLVED, pending);
        }

        // 2) 声明式 guard(when)
        if (action.getWhenExpr() != null && !action.getWhenExpr().isEmpty()
                && !dc.evaluator().eval(action.getWhenExpr(), actx)) {
            return skipAction(action, actx, actionChain, ReservedKeys.GUARD_FALSE);
        }

        // 3) 全局 Observer.before(纯观察)
        observers.actionBefore(actx);

        // 4) BEFORE(可否决)
        try {
            listeners.actionBefore(actionChain, actx);
        } catch (Exception e) {
            log.warn("action before 否决 proc={} action={}", procInstId, action.getActionDefId(), e);
            return failAction(dc, action, actx, actionChain, pctx,
                    Errors.describe(e, "action before 否决", at(ctx, action)));
        }

        // 5) handler 执行(返回 null => SUCCESS; 终态照旧; pending/paused/suspended = 让渡, 见下)
        // 注意 handler 查找也放在 try 内: 未注册/重复注册要在 action 行留 __error, 不能只飘到节点层
        Status s;
        try {
            ActionHandler handler = handlerRegistry.get(action.getHandlerName());
            s = handler.handle(actx);
            if (s == null) {
                s = Status.SUCCESS;
            }
        } catch (Exception e) {
            log.warn("action 执行异常 proc={} action={}", procInstId, action.getActionDefId(), e);
            return failAction(dc, action, actx, actionChain, pctx,
                    Errors.describe(e, "action 执行异常", at(ctx, action)));
        }

        // 5b) 过程态校验(让渡): 只认 pending/paused/suspended——三态=本轮让渡, 退出驱动等重入
        //     (见 Status.isYieldState); running/created 不是让渡态(流程行没有"有 owner 在驱动"之外的
        //     挂起语义), severity=0 静默落库还会被聚合吞成成功——当场报错, 把契约缺陷暴露在事发点。
        if (!s.isTerminal() && !s.isYieldState()) {
            // 源头留痕三件套: 日志(与 handler 异常同款) + action 行 __error + 流程级失败摘要(见 failAction)
            log.warn("action 返回值非法 proc={} action={} status={}", procInstId, action.getActionDefId(), s.getStatus());
            String reason = "handler 返回非法过程态: " + s.getStatus()
                    + "(过程态只允许 pending/paused/suspended; 终态 success/failed/skipped/stopped)";
            return failAction(dc, action, actx, actionChain, pctx,
                    Errors.describe(new IllegalStateException(reason), "action 返回值非法", at(ctx, action)));
        }

        // 6) 持久化 result(handler 自身返回 SKIPPED 记 __skipReason); 结果只留在 action 自己的行, 不上挂容器
        Map<String, Object> res = new LinkedHashMap<>(actx.result());
        if (s == Status.SKIPPED) {
            res.put(ReservedKeys.SKIP_REASON, ReservedKeys.HANDLER_SKIP);
        }
        actionMapper.updateResult(action.getActionInstId(), s.getStatus(), Json.write(res), actx.userId());

        // 7) 刷回流程级脏桶(handler putVar/putResult 后)
        flusher.flush(dc, pctx);

        // 8) AFTER(纯通知, 收最终 status)
        listeners.actionAfter(actionChain, actx, s);
        observers.actionAfter(actx, s);              // 全局 Observer.after
        return s;
    }

    /** guard=false 跳过: 写 __skipReason 到 result(并入原有), 触发 after(SKIPPED)。 */
    private Status skipAction(ActionInstEntity action, ActionContext actx,
                              List<ListenerDef> chain, String reason) {
        Map<String, Object> res = new LinkedHashMap<>(actx.result());
        res.put(ReservedKeys.SKIP_REASON, reason);
        actionMapper.updateResult(action.getActionInstId(), Status.SKIPPED.getStatus(), Json.write(res), actx.userId());
        listeners.actionAfter(chain, actx, Status.SKIPPED);
        observers.actionAfter(actx, Status.SKIPPED);
        return Status.SKIPPED;
    }

    /** handler/before 失败: 写 __error 到 result(并入原有), 上报流程级失败记录, 触发 after(FAILED)。不挂容器 result。 */
    private Status failAction(DriveContext dc, ActionInstEntity action, ActionContext actx,
                              List<ListenerDef> chain, ProcessContext pctx, String error) {
        Map<String, Object> res = new LinkedHashMap<>(actx.result());
        res.put(Errors.KEY, error);
        actionMapper.updateResult(action.getActionInstId(), Status.FAILED.getStatus(), Json.write(res), actx.userId());
        pctx.recordFailure(error);   // 流程级那一条失败摘要(首个胜出), 由 ExecutionEngine 收尾落 process_inst.result
        flusher.flush(dc, pctx);
        listeners.actionAfter(chain, actx, Status.FAILED);
        observers.actionAfter(actx, Status.FAILED);
        return Status.FAILED;
    }

    /**
     * drain 落一行({@link NodeSegments} 的 action 层专用): action 行的持久化写法只在本类——
     * 置 STOPPED 并把 __skipReason=DRAIN **并入原有 result**, 不丢失已持久化数据。
     */
    @Override
    public void markActionDrained(ActionInstEntity action, String status, String resultJson, String actor) {
        actionMapper.updateResult(action.getActionInstId(), status, resultJson, actor);
    }

    // ===================== 辅助 =====================

    /** 从 action 的父链上下文里取所属节点(lane 父即 node; 顶层节点直接命中)。 */
    private static NodeContext nodeOf(Context c) {
        while (c != null && !(c instanceof NodeContext)) {
            c = c.parent();
        }
        return (NodeContext) c;
    }

    /** 失败定位(action 那一条): 节点 + action 声明 id, 分片再带那一片的 shardKey。 */
    private static String at(NodeContext ctx, ActionInstEntity action) {
        String where = "node=" + ctx.name() + "/action=" + action.getActionDefId();
        return action.getShardKey() == null ? where : where + "@" + action.getShardKey();
    }
}
