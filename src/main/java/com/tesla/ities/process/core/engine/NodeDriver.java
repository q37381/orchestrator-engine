package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.exception.ProcessException;
import com.tesla.ities.process.core.context.*;
import com.tesla.ities.process.core.define.ActionDef;
import com.tesla.ities.process.core.define.ListenerDef;
import com.tesla.ities.process.core.define.NodeDef;
import com.tesla.ities.process.core.define.ProcessDefinition;
import com.tesla.ities.process.core.model.ErrorPolicy;
import com.tesla.ities.process.core.model.NodeType;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.core.runtime.ContextFactory;
import com.tesla.ities.process.core.runtime.CancellationRegistry;
import com.tesla.ities.process.core.runtime.VariableResolver;
import com.tesla.ities.process.core.spi.ActionHandler;
import com.tesla.ities.process.core.spi.ActionHandlerRegistry;
import com.tesla.ities.process.domain.ActionInstEntity;
import com.tesla.ities.process.domain.LaneEntity;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.util.Errors;
import com.tesla.ities.process.util.Json;
import com.tesla.ities.process.mapper.ActionInstMapper;
import com.tesla.ities.process.mapper.LaneMapper;
import com.tesla.ities.process.mapper.NodeInstMapper;
import com.tesla.ities.process.mapper.ProcessInstMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * 节点驱动器(#8): 承接"如何递归驱动一棵子树 + 如何跑一个容器内的 action 序列"。
 *
 * 存在的意义: 策略(ParallelStrategy 等)需要递归回调驱动子节点/动作序列, 若回调 ExecutionEngine 会造成
 * 策略↔引擎相互依赖。抽出本类作为递归的"外部调用者": 策略只依赖 NodeDriver, 引擎也只用 NodeDriver。
 *
 * 走查模型: driveNode(dc, node) 只带实例行——定义表达式作为**走查光标**已由 Materializer 对齐时补充进
 * node.expr(), 叶子的 NodeDef 与节点级监听器链也一并挂到 node.def() / node.listeners()(均 transient, 不落库):
 * 控制节点(PARALLEL/IF)没有 NodeDef(只存在于 flow DSL), 叶子行也不存 nodeRef, 行↔表达式的配对只存在于对齐点手里;
 * IF 的子表达式还依赖实例决策(__chosen)。行为分两档:
 *  - 已执行过(node 行为终态): 幂等直接返回原终态, 不再触发监听器/策略(重入/恢复快路径)。
 *  - 未执行过: markRunning -> 节点级 NodeListener.before(可否决) -> 分发给 NodeStrategy(**连同本节点 ctx**)
 *    -> ... -> finishNode(落终态 + NodeListener.after)。
 *
 * NodeContext 自带实例行且在 driveNode 建一次, 下传给策略与 runActionSequence: 容器结果与节点收尾共用
 * 同一个实例(分叉写法已消失), 分片节点也直接把它交给 ShardResolver(节点级参数在本层, 流程级沿父链上溯)。
 * 审计列 updator_id 统一取 ctx.userId()(流程创建人; 创建时由外部传入)。
 *
 * 监听器链从**定义推导**(定义版本钉死, 结果恒定), 直接以 List&lt;ListenerDef&gt; 传入 ListenerEngine:
 *  节点级 -> node.listeners()(对齐期从 NodeDef 挂载); action 级 -> ActionDef.listeners(与 action 行按下标对齐);
 *  两级链各自再与流程级声明的 globalNodeListeners / globalActionListeners 合并(全局在前)后再触发。
 *
 * action 物化(#5/#7): **执行到哪个 action 才落库哪个**(materializeAction 逐个建), 未跑到的不建行;
 *  EAGER 只预建节点树不预建 action 行, 两档走查同路径(重入时已存在行直接命中)。
 *
 * 结果与错误(#14): handler 只返回 Status; 结果由 handler 写 ctx.putResult, **只留在 action 自己的桶里**——
 * 引擎不再把 action result 以 actionDefId 为 key 自动挂到上层容器(节点间相互隔离, 也免掉容器整份落库的存储放大)。
 * 要跨 action/跨层级共享, 由使用者显式放置: ctx.upward().putResult(...) 挂到所属 lane/node 桶,
 * ctx.node().parent().putResult(...) 挂到直属上层容器(如组)桶, ctx.putVar(...) 写流程级 vars, 或直接写业务专表。
 * 失败/跳过信息写入 action result 的保留键
 * __error/__skipReason(不再用独立列); 落这些保留键时**先并入原有 result**, 不丢失已持久化的业务结果。
 * config 经两档解析后仍残留的占位符写进保留键 __unresolved(仅告警, 不阻断执行)。
 *
 * 失败摘要不再只打在日志里: 节点级 catch(before 否决 / 策略抛错)与 action 级 catch 都用
 * {@link Errors#describe} 收成一句"语境(定位): 类名: 消息"写进本行的 __error, 并 recordFailure
 * 上报流程级那**一条**失败记录(首个胜出), 由 ExecutionEngine 收尾落 process_inst.result。
 *
 * 容器终态按 severity 优先级聚合(Status.aggregate): FAILED > STOPPED > SUCCESS。
 */
@Component
public class NodeDriver {

    private static final Logger log = LoggerFactory.getLogger(NodeDriver.class);

    private final NodeInstMapper nodeMapper;
    private final LaneMapper laneMapper;
    private final ActionInstMapper actionMapper;
    private final ProcessInstMapper processMapper;
    private final ActionHandlerRegistry handlerRegistry;
    private final ListenerEngine listeners;
    private final ObserverEngine observers;
    private final ContextFactory ctxFactory;
    private final CancellationRegistry cancellation;
    private final LeafExprMaterializer leafMaterializer;
    private final ThreadPoolTaskExecutor actionExecutePool;
    private final Map<NodeType, NodeStrategy> strategies = new java.util.EnumMap<>(NodeType.class);

    public NodeDriver(NodeInstMapper nodeMapper, LaneMapper laneMapper, ActionInstMapper actionMapper,
                      ProcessInstMapper processMapper,
                      ActionHandlerRegistry handlerRegistry, ListenerEngine listeners,
                      ObserverEngine observers, ContextFactory ctxFactory,
                      CancellationRegistry cancellation, LeafExprMaterializer leafMaterializer,
                      @Qualifier("itiesProcessActionParallelExecutePool") ThreadPoolTaskExecutor actionExecutePool,
                      List<NodeStrategy> strategyList) {
        this.nodeMapper = nodeMapper;
        this.laneMapper = laneMapper;
        this.actionMapper = actionMapper;
        this.processMapper = processMapper;
        this.handlerRegistry = handlerRegistry;
        this.listeners = listeners;
        this.observers = observers;
        this.ctxFactory = ctxFactory;
        this.cancellation = cancellation;
        this.leafMaterializer = leafMaterializer;
        this.actionExecutePool = actionExecutePool;
        for (NodeStrategy s : strategyList) {
            strategies.put(s.type(), s);
        }
    }

    // ===================== 节点驱动(已执行跳过 + 策略分发 + 节点级监听器) =====================

    /** 驱动一个节点至终态(父链直接挂流程级)。定义取 node.expr()(对齐补充); 策略经本方法递归回调。 */
    public Status driveNode(DriveContext dc, NodeInstEntity node) {
        return driveNode(dc, node, dc.processContext());
    }

    /**
     * 同上, 显式给出**直属上层容器**: 聚合节点(GROUP)驱动子节点时传自己的 NodeContext,
     * 于是上下文父链与实例树同形——组级共享 config(${config.<k>})与使用者显式挂进组桶的 result 因此可读。
     * 引擎**不**向该容器自动写值(无冒泡/挂载), 跨层共享一律由使用者显式放置(见 GroupStrategy 注释)。
     * 顶层走查与匿名控制容器(PARALLEL/IF)仍走上面的两参入口(父 = 流程级), 行为不变。
     */
    public Status driveNode(DriveContext dc, NodeInstEntity node, Context container) {
        Status existing = statusOf(node.getStatus());
        if (existing != null && existing.isTerminal()) {
            return existing;   // 已执行过: 幂等跳过, 不再触发监听器/策略
        }
        if (node.getExpr() == null) {
            throw new ProcessException("AU204305", node.getNodeInstId());
        }

        NodeType type = NodeType.of(node.getType());   // of() 大小写不敏感(小写规范值, 也兼容存量大写行); valueOf 对小写抛异常
        NodeStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new ProcessException("AU204306", type);
        }

        nodeMapper.markRunning(node.getNodeInstId(), dc.processContext().userId());
        NodeContext ctx = ctxFactory.node(container, node);   // 上下文带行, 下游只传它
        List<ListenerDef> nodeChain = node.getListeners();   // 组装/对齐期已挂载

        // 全局 Observer.before(纯观察)
        observers.nodeBefore(ctx);

        // 节点级 before(可否决)
        try {
            listeners.nodeBefore(nodeChain, ctx);
        } catch (Exception e) {
            log.error("节点 before 否决 proc={} node={}", dc.procInstId(), node.getNodeInstId(), e);
            return finishNode(dc, ctx, nodeChain, Status.FAILED,
                    Errors.describe(e, "节点 before 否决", at(ctx)));
        }

        Status s;
        String error = null;
        try {
            s = strategy.drive(this, dc, ctx);   // 节点信息全在 ctx 里(行 + 定义 + 节点级参数)
        } catch (Exception e) {
            log.error("节点驱动异常 proc={} node={}", dc.procInstId(), node.getNodeInstId(), e);
            s = Status.FAILED;
            error = Errors.describe(e, "节点驱动异常", at(ctx));   // 以前这里只打日志, 行上什么痕迹都没有
        }
        return finishNode(dc, ctx, nodeChain, s, error);
    }

    /**
     * 节点收尾: 落状态 + result(带失败摘要时并入保留键 __error, 先并入原有 result 不丢业务数据) + 发 after。
     * 有摘要的同时把它 {@code recordFailure} 上报流程级那一条失败记录(由 ExecutionEngine 收尾落库)。
     */
    private Status finishNode(DriveContext dc, NodeContext ctx, List<ListenerDef> nodeChain, Status s, String error) {
        Map<String, Object> res = new LinkedHashMap<>(ctx.result());
        if (error != null) {
            res.put(Errors.KEY, error);
            dc.processContext().recordFailure(error);
        }
        nodeMapper.updateStatusResult(ctx.nodeInstId(), s.getStatus(), Json.write(res), ctx.userId());
        listeners.nodeAfter(nodeChain, ctx, s);   // 纯通知
        observers.nodeAfter(ctx, s);                // 全局 Observer.after
        return s;
    }

    private Status statusOf(String name) {
        return Status.of(name);   // 大小写不敏感反查; null/不识别返回 null
    }

    public boolean isCancelled(DriveContext dc) {
        return cancellation.isCancelled(dc.procInstId());
    }

    // ===================== action 序列(容器内联执行) =====================

    /**
     * 执行容器(lane 或非分片 node)内的 action 序列。action 定义取 ctx.inst().getDef(),
     * 行与 ActionDef 按下标对齐(声明顺序), action 级监听器链从 ActionDef 推导。
     *
     * 执行方式按 NodeDef.actionExecution(默认 SERIAL)分流:
     *  - SERIAL(默认): 逐个 action 走查到才建(LAZY 缺行补建)、内联跑, FAIL_FAST 遇失败即停并 drain 后续;
     *  - PARALLEL: 先补齐全部 action 行(扇出需同时在), 每个 CREATED 的 action 扇出 itiesProcessActionParallelExecutePool
     *    并行 -> join 聚合(FAIL_FAST drain: 不取消已跑的, 只取消尚未开始的排队任务)。
     * 已跑过(非 CREATED)的 action 两种模式都重入跳过。由 ActionStrategy 调用(非分片内联 / 分片每 lane 扇出)。
     *
     * @param ctx 本节点上下文(driveNode 建一次后下传: 自带实例行, 所以不再单独传 node);
     *            分片 action 的 payload 重建也用它(节点级 config/定义可读, 非分片自然返回 null)。
     */
    public Status runActionSequence(DriveContext dc, NodeContext ctx, LaneEntity lane) {
        NodeInstEntity node = ctx.inst();
        String actor = ctx.userId();
        String laneId = lane != null ? lane.getLaneId() : null;

        NodeDef nd = node.getDef();   // 对齐期已按 expr.nodeRef 一次取定, 不再回查定义
        if (nd == null) {
            throw new ProcessException("AU204307", node.getNodeInstId());
        }
        List<ActionDef> defs = nd.effectiveActions();

        // 已存在行(按 auto_id = 声明顺序, 与 defs 按下标对齐)
        List<ActionInstEntity> existing = actionMapper.findInContainer(node.getNodeInstId(), laneId);
        boolean failFast = ErrorPolicy.of(node.getErrorPolicy()) == ErrorPolicy.FAIL_FAST;
        // 分片时 lane 行重建出 LaneContext, 只作 action 上下文的父层(读 lane 级 config/沿父链上溯), 不再当挂载容器
        Context laneCtx = lane != null ? ctxFactory.lane(ctx, lane) : ctx;

        Status agg = nd.isActionParallel()
                ? runActionsParallel(dc, ctx, laneCtx, lane, defs, existing, failFast)
                : runActionsSerial(dc, ctx, laneCtx, lane, defs, existing, failFast, actor);

        // 收尾只落状态: action 结果一律留在各自 action 行, 不再把容器桶整份复制进 lane/node 行(存储放大)
        if (lane != null) {
            laneMapper.setStatus(lane.getLaneId(), agg.getStatus(), actor);
        } else {
            nodeMapper.setStatus(node.getNodeInstId(), agg.getStatus(), actor);
        }
        return agg;
    }

    /** 串行 action 序列(默认): 走查到第 i 个才建(LAZY 缺行补建; EAGER/重入命中已存在行), 内联跑。 */
    private Status runActionsSerial(DriveContext dc, NodeContext ctx, Context laneCtx, LaneEntity lane,
                                    List<ActionDef> defs, List<ActionInstEntity> existing, boolean failFast, String actor) {
        NodeInstEntity node = ctx.inst();
        String laneId = lane != null ? lane.getLaneId() : null;
        String shardKey = lane != null ? lane.getShardKey() : null;
        Status agg = Status.SUCCESS;
        for (int i = 0; i < defs.size(); i++) {
            if (isCancelled(dc)) {
                markRestStopped(existing, i, actor);
                agg = Status.aggregate(agg, Status.STOPPED);
                break;
            }
            ActionDef ad = defs.get(i);
            // 执行到第 i 个才落库(LAZY 缺行补建; EAGER/重入命中已存在行)
            ActionInstEntity a = i < existing.size()
                    ? existing.get(i)
                    : leafMaterializer.materializeAction(dc, node.getNodeInstId(), laneId, ad, shardKey);
            if (!Status.CREATED.getStatus().equals(a.getStatus())) {
                continue;   // 已跑过(重入)跳过
            }
            agg = Status.aggregate(agg, runAction(dc, laneCtx, a, ad));
            if ((agg == Status.FAILED || agg == Status.STOPPED) && failFast) {
                markRestStopped(existing, i + 1, actor);
                break;
            }
        }
        return agg;
    }

    /** 并行 action 序列: 先补齐全部 action 行(扇出需同时在), 每个 CREATED 的 action 扇出 action 池, join 聚合。 */
    private Status runActionsParallel(DriveContext dc, NodeContext ctx, Context laneCtx, LaneEntity lane,
                                      List<ActionDef> defs, List<ActionInstEntity> existing, boolean failFast) {
        NodeInstEntity node = ctx.inst();
        String laneId = lane != null ? lane.getLaneId() : null;
        String shardKey = lane != null ? lane.getShardKey() : null;

        // 扇出前 action 必须全部就位: LAZY 下补齐缺行(串行才"执行到才建", 并行无此序)
        List<ActionInstEntity> actions = new ArrayList<>();
        for (int i = 0; i < defs.size(); i++) {
            actions.add(i < existing.size()
                    ? existing.get(i)
                    : leafMaterializer.materializeAction(dc, node.getNodeInstId(), laneId, defs.get(i), shardKey));
        }

        // 每个 CREATED 的 action 提交 action 池(已跑过/重入的不重复提交)
        List<Future<Status>> futures = new ArrayList<>();
        for (int i = 0; i < defs.size(); i++) {
            final ActionInstEntity a = actions.get(i);
            final ActionDef ad = defs.get(i);
            if (!Status.CREATED.getStatus().equals(a.getStatus())) {
                continue;
            }
            futures.add(actionExecutePool.submit(() -> runAction(dc, laneCtx, a, ad)));
        }

        Status agg = Status.SUCCESS;
        for (int i = 0; i < futures.size(); i++) {
            Status s;
            try {
                s = futures.get(i).get();
            } catch (Exception e) {
                // action 任务异常 = 失败: 除了判状态, 还要留一句因由(日志 + 流程级失败记录), 不静默吞掉
                log.error("action 并行子任务异常 proc={} node={} action={}", dc.procInstId(), ctx.nodeInstId(), i, e);
                dc.processContext().recordFailure(
                        Errors.describe(e, "action 并行子任务异常", "node=" + ctx.name() + "/action=" + i));
                s = Status.FAILED;
            }
            agg = Status.aggregate(agg, s);
            if (s == Status.FAILED && failFast) {
                // FAIL_FAST drain: 取消尚未开始的排队任务(已跑的不强行中断)
                for (int j = i + 1; j < futures.size(); j++) {
                    futures.get(j).cancel(false);
                }
            }
        }
        return agg;
    }

    /** 执行单个 action: 解析 config -> guard -> BEFORE -> handler -> 持久化 -> AFTER。 */
    private Status runAction(DriveContext dc, Context parent,
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

        Map<String, Object> rawCfg = Json.readMap(action.getConfig());
        // 链 = 全局 action 级链 + action 自己声明的链合并(全局在前); 分片时每 lane 的每个 action 都走
        List<ListenerDef> actionChain = ProcessDefinition.mergeWithGlobals(dc.def().getGlobalActionListeners(),
                ad == null ? null : ad.getListeners());

        // 1) 激活期解析 config(${result.*} + 运行期新写入 vars, 如方言)
        actx.replaceConfig(Materializer.asMap(dc.resolver().resolve(rawCfg, actx, VariableResolver.Phase.ACTIVATION)));

        // 1b) 两档都解不出的键(如指向不存在变量的 ${vars.x}): 告警 + 记进 result.__unresolved,
        //     不再静默把占位符喂给 handler; 与 __error/__skipReason 同族, 供 guard/进度读。
        List<String> pending = dc.resolver().findUnresolved(actx.config());
        if (!pending.isEmpty()) {
            log.warn("config 仍含未解析占位符 proc={} action={} keys={}", procInstId, action.getActionDefId(), pending);
            actx.putResult("__unresolved", pending);
        }

        // 2) 声明式 guard(when)
        if (action.getWhenExpr() != null && !action.getWhenExpr().isEmpty()
                && !dc.evaluator().eval(action.getWhenExpr(), actx)) {
            return skipAction(action, actx, actionChain, "GUARD_FALSE");
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

        // 5) handler 执行
        ActionHandler handler = handlerRegistry.get(action.getHandlerName());
        Status s;
        try {
            s = handler.handle(actx);
            if (s == null) {
                s = Status.SUCCESS;
            }
        } catch (Exception e) {
            log.warn("action 执行异常 proc={} action={}", procInstId, action.getActionDefId(), e);
            return failAction(dc, action, actx, actionChain, pctx,
                    Errors.describe(e, "action 执行异常", at(ctx, action)));
        }

        // 6) 持久化 result(handler 自身返回 SKIPPED 记 __skipReason); 结果只留在 action 自己的行, 不上挂容器
        Map<String, Object> res = new LinkedHashMap<>(actx.result());
        if (s == Status.SKIPPED) {
            res.put("__skipReason", "HANDLER_SKIP");
        }
        actionMapper.updateResult(action.getActionInstId(), s.getStatus(), Json.write(res), actx.userId());

        // 7) 刷回流程级脏桶(handler putVar/putResult 后)
        flushProcessState(dc, pctx);

        // 8) AFTER(纯通知, 收最终 status)
        listeners.actionAfter(actionChain, actx, s);
        observers.actionAfter(actx, s);              // 全局 Observer.after
        return s;
    }

    /** guard=false 跳过: 写 __skipReason 到 result(并入原有), 触发 after(SKIPPED)。 */
    private Status skipAction(ActionInstEntity action, ActionContext actx,
                              List<ListenerDef> chain, String reason) {
        Map<String, Object> res = new LinkedHashMap<>(actx.result());
        res.put("__skipReason", reason);
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
        flushProcessState(dc, pctx);
        listeners.actionAfter(chain, actx, Status.FAILED);
        observers.actionAfter(actx, Status.FAILED);
        return Status.FAILED;
    }

    /** 失败定位(节点那一条): 展示名 = 定义 name > 定义 id, 控制节点回落实例业务键。 */
    private static String at(NodeContext ctx) {
        return "node=" + ctx.name();
    }

    /** 从 action 的父链上下文里取所属节点(lane 父即 node; 顶层节点直接命中)。 */
    private static NodeContext nodeOf(Context c) {
        while (c != null && !(c instanceof NodeContext)) {
            c = c.parent();
        }
        return (NodeContext) c;
    }

    /** 失败定位(action 那一条): 节点 + action 声明 id, 分片再带那一片的 shardKey。 */
    private static String at(NodeContext ctx, ActionInstEntity action) {
        String where = at(ctx) + "/action=" + action.getActionDefId();
        return action.getShardKey() == null ? where : where + "@" + action.getShardKey();
    }

    // ===================== 辅助 =====================

    /**
     * 刷回流程级的两个脏桶: vars(ctx.putVar)与 result(ctx.putResult)。
     * 任一脏就走一条 updateState(只写脏的那几列、不动状态); 引擎收尾也复用。
     * 回退路径不调本方法——前置监听器挂的现场信息只留在内存(见 RollbackService)。
     */
    public void flushProcessState(DriveContext dc, ProcessContext pctx) {
        boolean vars = pctx.isVarsDirty();
        boolean result = pctx.isResultDirty();
        if (!vars && !result) {
            return;
        }
        processMapper.updateState(dc.procInstId(), null, null,
                result ? Json.write(pctx.resultSnapshot()) : null,
                vars ? Json.write(pctx.vars()) : null, pctx.userId());
        pctx.clearVarsDirty();
        pctx.clearResultDirty();
    }

    /**
     * drain: 把**已物化**但还没跑的兄弟 action 置 STOPPED(写 __skipReason=DRAIN)。
     * 只处理已存在行(LAZY 下未跑到的 action 从未建行, 不补建); 并入原有 result, 不丢失已持久化数据。
     */
    private void markRestStopped(List<ActionInstEntity> actions, int from, String actor) {
        for (int i = from; i < actions.size(); i++) {
            ActionInstEntity a = actions.get(i);
            if (Status.CREATED.getStatus().equals(a.getStatus()) || Status.PENDING.getStatus().equals(a.getStatus())) {
                Map<String, Object> res = new LinkedHashMap<>(Json.readMap(a.getResult()));
                res.put("__skipReason", "DRAIN");
                actionMapper.updateResult(a.getActionInstId(), Status.STOPPED.getStatus(), Json.write(res), actor);
            }
        }
    }
}
