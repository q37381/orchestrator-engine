package com.tesla.ities.process.core.engine;

import com.tesla.ities.process.exception.ProcessException;
import com.tesla.ities.process.core.context.NodeContext;
import com.tesla.ities.process.core.define.ActionDef;
import com.tesla.ities.process.core.define.DslParser;
import com.tesla.ities.process.core.define.NodeDef;
import com.tesla.ities.process.core.model.NodeType;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.core.spi.ShardResolver;
import com.tesla.ities.process.core.spi.ShardResolverRegistry;
import com.tesla.ities.process.domain.ActionInstEntity;
import com.tesla.ities.process.domain.LaneEntity;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.util.IdGenerator;
import com.tesla.ities.process.util.IdKind;
import com.tesla.ities.process.util.Json;
import com.tesla.ities.process.mapper.ActionInstMapper;
import com.tesla.ities.process.mapper.LaneMapper;
import com.tesla.ities.process.mapper.NodeInstMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ACTION 叶子物化: 建 node_inst 行(仅运行期元数据 + 业务 config)。**lane/action 一律驱动期建**
 * (EAGER/LAZY 两档相同): lane 由 ActionStrategy 走查到该分片时 laneAt 逐个建, action 由
 * NodeDriver.runActionSequence 执行到第 i 个时 materializeAction 逐个建(#5/#7)。
 * 创建期不解析分片键、不预建子行——依赖运行期参数的 shardKey 到驱动期才解得出, 且未驱动到的
 * 子行不预建也就不会留永远 CREATED 的孤儿行; EAGER 预建的只有节点树(节点级分母稳定)。
 *
 * config 只存**业务配置**(物化期 PLAN 解析冻结: ${vars.*} 就地替换成 config 字面量, ${result.*} 留待激活期);
 * 监听器链不落库——定义版本钉死, 对齐时挂到 node.listeners() / 由 ActionDef 直接推导。
 *
 * 刚建的行就地带上自己的 NodeDef(setDef), 后续 attachCursor 对齐重挂同一对象(幂等)。
 *
 * error_policy 落的是**生效值**(行级冻结): 节点自己的声明优先, 未声明则用流程级全局 errorPolicy;
 * lane 直接继承本节点行的结果, 所以全局策略天然覆盖到分片 lane。
 */
@Component
public class LeafExprMaterializer implements ExprMaterializer {

    private final NodeInstMapper nodeMapper;
    private final LaneMapper laneMapper;
    private final ActionInstMapper actionMapper;
    private final ShardResolverRegistry shardRegistry;
    private final IdGenerator ids;

    public LeafExprMaterializer(NodeInstMapper nodeMapper, LaneMapper laneMapper, ActionInstMapper actionMapper,
                                ShardResolverRegistry shardRegistry, IdGenerator ids) {
        this.nodeMapper = nodeMapper;
        this.laneMapper = laneMapper;
        this.actionMapper = actionMapper;
        this.shardRegistry = shardRegistry;
        this.ids = ids;
    }

    @Override
    public Class<? extends DslParser.Expr> exprType() {
        return DslParser.Leaf.class;
    }

    @Override
    public String materialize(Materializer ctx, DriveContext dc, DslParser.Expr expr, String parentId, boolean eager) {
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
        Map<String, Object> cfg = Materializer.resolvePlan(dc, new LinkedHashMap<>(Materializer.asMap(nd.getConfig())));
        n.setConfig(Json.write(cfg));
        String actor = dc.processContext().userId();   // 建行者 = 流程创建人
        n.setCreatorId(actor);
        n.setUpdatorId(actor);
        n.setDef(nd);                                  // 建行即自带定义(attachCursor 对齐重挂同一对象, 幂等)
        nodeMapper.insertIgnore(n);

        // lane/action 不在这里建(eager 只预建节点树): 驱动期现解分片键后 laneAt/materializeAction 逐个补建
        return nodeId;
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
        laneMapper.insertIgnore(lane);
        return lane;
    }

    /**
     * 物化单个 action 行并返回(CREATED)。**幂等 find-or-create**:
     * 先按自然键 (node, lane, actionDefId) 查, 已存在则直接返回库里那行(重入/崩溃半物化恢复时
     * 复用, 不重复建)——非分片 shard_key=NULL 时 uk_node_def_shard 不去重,
     * 仅靠 INSERT IGNORE 会产生重复行, 故必须先查。缺行才新建。
     * runActionSequence 执行到第 i 个 action 时调用(两档同路径: 驱动期缺行才建)。
     */
    public ActionInstEntity materializeAction(DriveContext dc, String nodeId, String laneId, ActionDef ad, String shardKey) {
        String actionDefId = ad.getId() != null ? ad.getId() : ad.getHandler();
        ActionInstEntity existing = actionMapper.findByContainerAndDef(nodeId, laneId, actionDefId);
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
        a.setStatus(Status.CREATED.getStatus());
        a.setWhenExpr(ad.getWhen());
        Map<String, Object> cfg = Materializer.resolvePlan(dc, new LinkedHashMap<>(Materializer.asMap(ad.getConfig())));
        a.setConfig(Json.write(cfg));
        String actor = dc.processContext().userId();   // 建行者 = 流程创建人
        a.setCreatorId(actor);
        a.setUpdatorId(actor);
        actionMapper.insertIgnore(a);
        return a;
    }
}
