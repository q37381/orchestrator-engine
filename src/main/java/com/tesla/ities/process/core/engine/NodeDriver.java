package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.exception.ProcessException;
import com.tesla.ities.process.core.context.*;
import com.tesla.ities.process.core.define.ListenerDef;
import com.tesla.ities.process.core.define.ProcessDefinition;
import com.tesla.ities.process.core.model.NodeType;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.core.runtime.CancellationRegistry;
import com.tesla.ities.process.core.runtime.ContextFactory;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.mapper.NodeInstMapper;
import com.tesla.ities.process.util.Errors;
import com.tesla.ities.process.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点驱动器: **只做一件事**——把一个节点驱动到终态(handler 让渡时按非终态退出, 等重入续跑; 见 finishNode)——
 * 幂等跳过 -> markRunning -> 节点级监听器 ->
 * 按行 type 分发给类型档案 -> finishNode 落终态 + after 链, 并向档案与层类提供递归回调;
 * 另持顶层走查入口 {@link #driveRoots}(把定义翻成 {@link LayerWalker#walkRoots} 的入参)。
 *
 * 职责边界(拆分后): action 单项生命周期归 {@link ActionRunner}, 序列/层走查归 {@link LayerWalker},
 * 流程级脏桶归 {@link ProcessStateFlusher}, 行↔表达式对齐归 {@link Materializer}——本类不再替它们转发
 * (原先 13 个构造依赖里混着五类职责, 走查层与档案都被迫面向整个门面)。
 * 档案与走查引擎经 {@link NodeEnv} 拿到的是五组角色接口(NodeRecursion/ChildCursor/ActionExecution/
 * CancellationGate/LayerWalk), 而不是本类的全部 API 面; env 在驱动点现组(单点), 构造期依赖图保持单向无环。
 *
 * 走查模型: driveNode(dc, node, container) 只带实例行——定义表达式作为**走查光标**已由 Materializer
 * 对齐时补充进 node.expr(), 叶子的 NodeDef 与节点级监听器链也一并挂到 node.def() / node.listeners()
 * (均 transient, 不落库): 控制节点(PARALLEL/IF)没有 NodeDef(只存在于 flow DSL), 叶子行也不存 nodeRef,
 * 行↔表达式的配对只存在于对齐点手里; IF 的子表达式还依赖实例决策(__chosen)。行为分两档:
 *  - 已执行过(node 行为终态): 幂等直接返回原终态, 不再触发监听器/档案(重入/恢复快路径)。
 *  - 未执行过(含让渡后停在 running 的节点): markRunning -> 节点级 NodeListener.before(可否决) ->
 *    分发给该类型的档案 NodeKind(连同本节点 ctx 与协作 env) -> ... -> finishNode(落终态 + NodeListener.after)。
 *
 * NodeContext 自带实例行且在 driveNode 建一次, 下传给档案与 action 序列: 容器结果与节点收尾共用
 * 同一个实例(分叉写法已消失), 分片节点也直接把它交给 ShardResolver(节点级参数在本层, 流程级沿父链上溯)。
 * 审计列 updator_id 统一取 ctx.userId()(流程创建人; 创建时由外部传入)。
 *
 * 监听器链从**定义推导**(定义版本钉死, 结果恒定), 直接以 List&lt;ListenerDef&gt; 传入 ListenerEngine:
 * 节点级 -> node.listeners()(组装/对齐期从 NodeDef 挂载)。
 *
 * 失败与错误(#14): 节点级 catch(before 否决 / 档案抛错)经 {@link Errors#describe} 收成一句
 * "语境(定位): 类名: 消息"写进本行 result 的保留键 __error(并入原有 result), 并 recordFailure 上报
 * 流程级那**一条**失败记录(首个胜出), 由 ExecutionEngine 收尾落 process_inst.result; 本轮没失败则摘掉
 * 上一轮的 __error 残留——跑成功的节点不该还挂着失败痕迹。容器终态按 exit 聚合(坏终态优先 > 让渡 > severity)。
 */
@Component
public class NodeDriver implements NodeRecursion, CancellationGate {

    private static final Logger log = LoggerFactory.getLogger(NodeDriver.class);

    private final NodeInstMapper nodeMapper;
    private final ListenerEngine listeners;
    private final ObserverEngine observers;
    private final ContextFactory ctxFactory;
    private final CancellationRegistry cancellation;
    private final Materializer materializer;
    private final ActionRunner actionRunner;
    private final NodeKinds kinds;
    private final LayerWalker walker;

    public NodeDriver(NodeInstMapper nodeMapper, ListenerEngine listeners, ObserverEngine observers,
                      ContextFactory ctxFactory, CancellationRegistry cancellation,
                      Materializer materializer, ActionRunner actionRunner, NodeKinds kinds,
                      LayerWalker walker) {
        this.nodeMapper = nodeMapper;
        this.listeners = listeners;
        this.observers = observers;
        this.ctxFactory = ctxFactory;
        this.cancellation = cancellation;
        this.materializer = materializer;
        this.actionRunner = actionRunner;
        this.kinds = kinds;
        this.walker = walker;
    }

    /**
     * 顶层走查入口(setup + flow + setdown 三段): 走查口径全在 {@link LayerWalker#walkRoots} 的声明里——
     * flow 段就近声明收敛、setdown 段 finally 语义、顶层不设取消闸门; 本方法只把定义翻成入参
     * (有序根视图 + flow 段界)。异常兜底(收成流程级 __error)在 ExecutionEngine, 本类不外扩职责。
     */
    public Status driveRoots(DriveContext dc) {
        ProcessDefinition def = dc.def();
        int flowEnd = def.setupSize() + def.roots().size();   // setup 段 [0, setupSize), flow 段 [setupSize, flowEnd)
        return walker.walkRoots(dc, env(), def.orderedRoots(), flowEnd);
    }

    /** 驱动一个节点至终态(父链直接挂流程级)。定义取 node.expr()(对齐补充); 档案经本方法递归回调。 */
    public Status driveNode(DriveContext dc, NodeInstEntity node) {
        return driveNode(dc, node, dc.processContext());
    }

    /**
     * 同上, 显式给出**直属上层容器**: 聚合节点(GROUP)驱动子节点时传自己的 NodeContext,
     * 于是上下文父链与实例树同形——组级共享 config(${config.<k>})与使用者显式挂进组桶的 result 因此可读。
     * 引擎**不**向该容器自动写值(无冒泡/挂载), 跨层共享一律由使用者显式放置(见 GroupKind 注释)。
     */
    @Override
    public Status driveNode(DriveContext dc, NodeInstEntity node, Context container) {
        Status existing = statusOf(node.getStatus());
        if (existing != null && existing.isTerminal()) {
            return existing;   // 已执行过: 幂等跳过, 不再触发监听器/类型档案
        }
        if (node.getExpr() == null) {
            throw new ProcessException("AU204305", node.getNodeInstId());
        }

        NodeType type = NodeType.of(node.getType());   // of() 大小写不敏感(小写规范值, 也兼容存量大写行); valueOf 对小写抛异常
        NodeKind kind = kinds.ofType(type);            // 一种类型一份档案(NodeKinds 唯一注册表); 无档案(预留的 WHILE)当场报清楚

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
            // 协作环境现组: 档案只看到五组角色接口(递归/对齐/跑 action/判停/走查), 递归与判停就是本类的两个身份
            s = kind.drive(env(), dc, ctx);   // 节点信息全在 ctx 里(行 + 定义 + 节点级参数)
        } catch (Exception e) {
            log.error("节点驱动异常 proc={} node={}", dc.procInstId(), node.getNodeInstId(), e);
            s = Status.FAILED;
            error = Errors.describe(e, "节点驱动异常", at(ctx));   // 以前这里只打日志, 行上什么痕迹都没有
        }
        return finishNode(dc, ctx, nodeChain, s, error);
    }

    /** 协作环境现组(单点): 四组角色 + 走查引擎, 驱动与顶层走查共用同一份装配。 */
    private NodeEnv env() {
        return new NodeEnv(this, materializer, actionRunner, this, walker);
    }

    /**
     * 节点收尾: 落状态 + result(带失败摘要时并入保留键 __error, 先并入原有 result 不丢业务数据) + 发 after。
     * 有摘要的同时把它 {@code recordFailure} 上报流程级那一条失败记录(由 ExecutionEngine 收尾落库)。
     * 本轮没失败(error=null)则摘掉行上上一轮的 __error 残留——重入跑成功不该还挂着失败信息(与流程级同口径)。
     *
     * 让渡(非终态返回: pending/paused/suspended): 节点未完成——行保持 running(不落终态)、result 照写,
     * 但**不发 after**(节点级监听器/观察者只认终态, 等重入后真正完成才收尾); 重入时节点行非终态,
     * driveNode 不被幂等跳过, 会再进回路从非终态行继续(FR-STA-04 放宽为"终态或让渡态")。
     */
    private Status finishNode(DriveContext dc, NodeContext ctx, List<ListenerDef> nodeChain, Status s, String error) {
        Map<String, Object> res = new LinkedHashMap<>(ctx.result());
        if (error != null) {
            res.put(Errors.KEY, error);
            dc.processContext().recordFailure(error);
        } else {
            res.remove(Errors.KEY);
        }
        if (s != null && !s.isTerminal()) {
            nodeMapper.updateStatusResult(ctx.nodeInstId(), Status.RUNNING.getStatus(), Json.write(res), ctx.userId());
            return s;   // 让渡: 行停 running, 等重入续跑
        }
        nodeMapper.updateStatusResult(ctx.nodeInstId(), s.getStatus(), Json.write(res), ctx.userId());
        listeners.nodeAfter(nodeChain, ctx, s);   // 纯通知
        observers.nodeAfter(ctx, s);                // 全局 Observer.after
        return s;
    }

    private Status statusOf(String name) {
        return Status.of(name);   // 大小写不敏感反查; null/不识别返回 null
    }

    /** 取消闸门(子层每步查库用): 真值只有 process_inst.status 一份, 不留本机状态。 */
    @Override
    public boolean isCancelled(DriveContext dc) {
        return cancellation.isCancelled(dc.procInstId());
    }

    /** 失败定位(节点那一条): 展示名 = 定义 name > 定义 id, 控制节点回落实例业务键。 */
    private static String at(NodeContext ctx) {
        return "node=" + ctx.name();
    }
}
