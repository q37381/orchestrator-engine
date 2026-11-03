package com.tt.ii.process.core.engine;

import com.tt.ii.process.exception.ProcessException;
import com.tt.ii.process.core.context.NodeContext;
import com.tt.ii.process.core.define.ActionDef;
import com.tt.ii.process.core.define.DslParser;
import com.tt.ii.process.core.define.NodeDef;
import com.tt.ii.process.core.model.ActionSegment;
import com.tt.ii.process.core.model.NodeType;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.core.spi.ShardResolver;
import com.tt.ii.process.core.spi.ShardResolverRegistry;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.domain.LaneEntity;
import com.tt.ii.process.domain.NodeInstEntity;
import com.tt.ii.process.mapper.ActionInstMapper;
import com.tt.ii.process.mapper.LaneMapper;
import com.tt.ii.process.mapper.NodeInstMapper;
import com.tt.ii.process.util.IdGenerator;
import com.tt.ii.process.util.IdKind;
import com.tt.ii.process.util.Json;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * ACTION 叶子档案: 定义侧的一个节点 = 一行 node_inst, 实际跑 handler 的地方。
 * (前身是 LeafExprMaterializer + ActionStrategy 两个 Bean, 合并后叶子的建行/跑法在一处。)
 *
 * NodeDef 来自行内走查对齐时挂上的 node.def()(不反查实例、不回查定义); action 行与 ActionDef
 * 按下标对齐({@link ActionRows} 的实现口径在本档案, 由 {@link LayerWalker} 走查时取行), 监听器链从 ActionDef 推导(不落库)。
 *
 * 物化口径: **lane/action 一律驱动期建**(EAGER/LAZY 两档相同)——lane 由走查引擎走查到该分片时 {@link #laneAt}
 * 逐个建, action 由 action 层走查执行到第 i 个时 {@link #materializeAction} 逐个建(#5/#7)。创建期不解析分片键、
 * 不预建子行: 依赖运行期参数的 shardKey 到驱动期才解得出, 且未驱动到的子行不预建
 * 也就不会留永远 CREATED 的孤儿行; EAGER 预建的只有节点树(节点级分母稳定)。
 *
 * config 只存**业务配置**(物化期 PLAN 解析冻结: ${vars.*} 就地替换成 config 字面量, ${result.*} 留待激活期);
 * 监听器链不落库——定义版本钉死, 对齐时挂到 node.listeners() / 由 ActionDef 直接推导。
 * 刚建的行就地带上自己的 NodeDef(setDef), 后续 attachCursor 对齐重挂同一对象(幂等)。
 *
 * error_policy 落的是**生效值**(行级冻结): 节点自己的声明优先, 未声明则用流程级全局 errorPolicy;
 * lane 直接继承本节点行的结果, 所以全局策略天然覆盖到分片 lane。
 *
 * 非分片: 走查引擎内联驱动本节点的 action 序列。
 * 分片:   shardKeys 此刻解析(可读 live vars) -> 交 {@link LayerWalk#walkLanes} 走查, 档由
 *         NodeDef.laneExecution 派生(#6): SERIAL(默认) lane 逐条串行(走查到才建); PARALLEL 每条 lane 扇
 *         itiesProcessActionShardParallelExecutePool 并行 -> join 聚合(两档口径都在走查引擎内)。
 * FAIL_FAST(默认): 非分片在序列内 drain; 分片 lane 之间 drain(串行遇失败即停 / 并行不取消已跑 lane)。
 *
 * 分片参数(ctx): 本档案收到 driveNode 下传的 NodeContext, 直接把它当两阶段解析的入参——
 * 节点级 config/定义在本层上读, 流程级 params/vars 沿父链上溯, 所以同一流程里不同分片节点各拿各的。
 */
@Component
public class ActionKind implements NodeKind, ActionRows {

    private final NodeInstMapper nodeMapper;
    private final LaneMapper laneMapper;
    private final ActionInstMapper actionMapper;
    private final ShardResolverRegistry shardRegistry;
    private final IdGenerator ids;
    private final ConfigFreeze freeze;

    public ActionKind(NodeInstMapper nodeMapper, LaneMapper laneMapper, ActionInstMapper actionMapper,
                      ShardResolverRegistry shardRegistry, IdGenerator ids,
                      ConfigFreeze freeze) {
        this.nodeMapper = nodeMapper;
        this.laneMapper = laneMapper;
        this.actionMapper = actionMapper;
        this.shardRegistry = shardRegistry;
        this.ids = ids;
        this.freeze = freeze;
    }

    @Override public NodeType type() {
        return NodeType.ACTION;
    }

    @Override public Class<? extends DslParser.Expr> exprType() {
        return DslParser.Leaf.class;
    }

    /** 叶子没有子节点行(lane/action 是执行细节, 一律驱动期建)。 */
    @Override public boolean materializesChildrenEagerly() {
        return false;
    }

    // ===================== 物化: 建节点行 =====================

    @Override
    public String materialize(DriveContext dc, DslParser.Expr expr, String parentId) {
        String nodeRef = ((DslParser.Leaf) expr).nodeRef;
        NodeDef nd = dc.def().node(nodeRef);
        if (nd == null) {
            throw new ProcessException("AU204302", nodeRef);
        }
        String nodeId = ids.next(IdKind.NODE_INST);
        NodeInstEntity n = new NodeInstEntity();
        n.setNodeInstId(nodeId);
        n.setProcInstId(dc.procInstId());
        n.setNodeDefId(nodeRef);                     // 展示用定义快照(与 def.id 同源)
        n.setName(nd.displayName());                 // 展示名(name > id), 视图直接读行
        n.setType(NodeType.ACTION.getStatus());
        n.setParentId(parentId);
        n.setStatus(Status.CREATED.getStatus());
        n.setErrorPolicy(dc.def().effectiveErrorPolicy(nd.getErrorPolicy()).getStatus());   // 节点自己优先, 未声明用全局 errorPolicy(都没则 FAIL_FAST)
        n.setShardEnabled(nd.isSharded());
        n.setShardResolver(nd.getShard());
        n.setConditionExpr(nd.getWhen());          // 节点级 guard 复用 condition_expr 列
        Map<String, Object> cfg = freeze.freezePlan(dc, nd.getConfig());
        n.setConfig(Json.write(cfg));
        String actor = dc.processContext().userId();   // 建行者 = 流程创建人
        n.setCreatorId(actor);
        n.setUpdatorId(actor);
        n.setDef(nd);                                  // 建行即自带定义(attachCursor 对齐重挂同一对象, 幂等)
        nodeMapper.insert(n);

        // lane/action 不在这里建(eager 只预建节点树): 驱动期现解分片键后 laneAt/materializeAction 逐个补建
        return nodeId;
    }

    // ===================== 驱动: 跑该节点的 action 序列(非分片内联 / 分片按 lane 串或并) =====================

    @Override
    public Status drive(NodeEnv env, DriveContext dc, NodeContext ctx) {
        NodeInstEntity node = ctx.inst();
        if (!(node.getExpr() instanceof DslParser.Leaf)) {
            throw new ProcessException("AU204301", node.getNodeInstId());
        }
        NodeDef nd = node.getDef();   // 对齐期已一次取定
        if (nd == null) {
            throw new ProcessException("AU204302", ((DslParser.Leaf) node.getExpr()).nodeRef);
        }

        // 节点级 guard(when): 假则整节点 SKIPPED(在本节点上下文上求值, 与 action guard 口径一致)
        if (!NodeGuard.holds(dc, ctx)) {
            return Status.SKIPPED;
        }

        // lane/action 不在这里批量建: 走查到分片/动作时才逐个物化(#5/#7; 两档同路径, 分片键此处现解)
        if (Boolean.TRUE.equals(node.getShardEnabled())) {
            return driveSharded(env, dc, ctx);
        }
        // 非分片: 声明"节点回路三段(setup/main/setdown)"走查意图, 段口径全在 LayerWalker.walkCircuit 的声明里
        return env.walk().walkCircuit(dc, env, ctx, null);
    }

    /**
     * 分片: 本档案现解分片键(阶段1), 再声明 lane 层走查意图——lane 串/并行两档
     * (NodeDef.laneExecution, 默认 SERIAL)的定位/扇出/汇聚口径都在 {@link LayerWalker#walkLanes} 的声明里。
     * 判停/跑序列/行定位经 env 的角色接口与本档案实现的 {@link ActionRows}。
     */
    private Status driveSharded(NodeEnv env, DriveContext dc, NodeContext ctx) {
        if (dc.isSettle()) {
            // 收尾轮: 不重解分片键(resolver 可能依赖的外部拓扑已不可用), 只处理已物化 lane
            return env.walk().walkLanes(dc, env, ctx, Collections.<String>emptyList());
        }
        final List<String> shardKeys = resolveShardKeys(ctx);   // 阶段1: 解析分片键(ctx = 本节点上下文)
        return env.walk().walkLanes(dc, env, ctx, shardKeys);
    }

    // ===================== ActionRows(行定位口径归本档案, 层类只面向接口) =====================

    @Override public ActionInstEntity ensureActionRow(DriveContext dc, NodeInstEntity node, String laneId,
                                                      ActionDef ad, String shardKey, ActionSegment segment) {
        return materializeAction(dc, node.getNodeInstId(), laneId, ad, shardKey, segment);
    }

    @Override public LaneEntity ensureLaneRow(DriveContext dc, NodeInstEntity node, String shardKey) {
        return laneAt(dc, node, shardKey);
    }

    @Override public List<ActionInstEntity> findInContainer(String nodeId, String laneId, ActionSegment segment) {
        return actionMapper.findInContainer(nodeId, laneId, null, segment.getStatus());
    }

    @Override public boolean hasAnyRow(String nodeId, String laneId) {
        return !actionMapper.findInContainer(nodeId, laneId, null, null).isEmpty();
    }

    @Override public int reArmSegment(String nodeId, String laneId, ActionSegment segment, boolean allTerminal, String actor) {
        return reArmRows(findInContainer(nodeId, laneId, segment), allTerminal, actor);
    }

    @Override public int reArmRows(List<ActionInstEntity> rows, boolean allTerminal, String actor) {
        int n = 0;
        for (ActionInstEntity a : rows) {
            Status st = Status.of(a.getStatus());
            if (st == null || !st.isTerminal() || (!allTerminal && st != Status.SUCCESS)) {
                continue;   // 非终态行本轮本就会重跑; 成对重臂只复位 success(skipped/failed/stopped 的复位归 retry)
            }
            n += actionMapper.casStatus(a.getActionInstId(), st.getStatus(), Status.CREATED.getStatus(), actor);
        }
        return n;   // 复位口径同 InterventionService.resetAction: 只翻状态、不动 result
    }

    // ===================== 驱动期逐个物化(执行到才建, 两档共用) =====================

    /** 阶段1: 解析本分片节点的全部分片键(**驱动期现解**, 创建期不调; 每次驱动重解, 拓扑稳定则结果一致)。resolver 名从行上自取。 */
    public List<String> resolveShardKeys(NodeContext ctx) {
        ShardResolver sr = shardRegistry.get(ctx.inst().getShardResolver());
        return sr.resolveShardKeys(ctx);
    }

    /** find-or-create 单个 lane(按 node+shardKey 幂等); 走查到该分片才建(重入命中已建行)。 */
    public LaneEntity laneAt(DriveContext dc, NodeInstEntity node, String shardKey) {
        LaneEntity lane = laneMapper.findByNodeAndShard(node.getNodeInstId(), shardKey);
        if (lane != null) {
            return lane;
        }
        lane = new LaneEntity();
        lane.setLaneId(ids.next(IdKind.LANE));
        lane.setProcInstId(dc.procInstId());
        lane.setNodeInstId(node.getNodeInstId());
        lane.setShardKey(shardKey);
        lane.setStatus(Status.CREATED.getStatus());
        lane.setErrorPolicy(node.getErrorPolicy());
        String actor = dc.processContext().userId();   // 建行者 = 流程创建人
        lane.setCreatorId(actor);
        lane.setUpdatorId(actor);
        laneMapper.insert(lane);
        return lane;
    }

    /**
     * 物化单个 action 行并返回(CREATED)。**幂等 find-or-create**:
     * 先按自然键 (node, lane, segment, actionDefId) 查, 已存在则直接返回库里那行(重入/崩溃半物化恢复时
     * 复用, 不重复建)——幂等只靠这一步, 插入用普通 INSERT(不带 IGNORE: 它把唯一键冲突与列错误
     * 静默成警告, dup 了也看不出原因; 非分片 shard_key=NULL 时 uk_node_def_shard 本就不去重)。
     * 走查执行到第 i 个 action 时调用(经 {@link NodeSegments} 定位; 两档同路径: 驱动期缺行才建)。
     */
    public ActionInstEntity materializeAction(DriveContext dc, String nodeId, String laneId, ActionDef ad,
                                              String shardKey, ActionSegment segment) {
        String actionDefId = ad.getId() != null ? ad.getId() : ad.getHandler();
        ActionInstEntity existing = actionMapper.findByContainerAndDef(nodeId, laneId, actionDefId, segment.getStatus());
        if (existing != null) {
            return existing;   // 已存在: 复用持久化行(actionInstId 与库一致), 不重复建
        }
        ActionInstEntity a = new ActionInstEntity();
        a.setActionInstId(ids.next(IdKind.ACTION_INST));
        a.setNodeInstId(nodeId);
        a.setLaneId(laneId);
        a.setActionDefId(actionDefId);
        a.setName(ad.displayName());                 // 展示名(name > id), 未声明即 actionDefId
        a.setHandlerName(ad.getHandler());
        a.setShardKey(shardKey);
        a.setSegment(segment.getStatus());           // 执行段(setup|main|setdown), 进落库列与唯一键
        a.setStatus(Status.CREATED.getStatus());
        a.setWhenExpr(ad.getWhen());
        Map<String, Object> cfg = freeze.freezePlan(dc, ad.getConfig());
        a.setConfig(Json.write(cfg));
        String actor = dc.processContext().userId();   // 建行者 = 流程创建人
        a.setCreatorId(actor);
        a.setUpdatorId(actor);
        actionMapper.insert(a);
        return a;
    }
}
