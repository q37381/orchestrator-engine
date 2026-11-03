package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.context.ActionContext;
import com.tt.ii.process.core.context.Context;
import com.tt.ii.process.core.context.NodeContext;
import com.tt.ii.process.core.context.ProcessContext;
import com.tt.ii.process.core.runtime.ContextFactory;
import com.tt.ii.process.core.runtime.VariableResolver;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.domain.LaneEntity;
import com.tt.ii.process.domain.NodeInstEntity;
import com.tt.ii.process.mapper.LaneMapper;
import com.tt.ii.process.mapper.NodeInstMapper;
import com.tt.ii.process.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 补偿/重放路径的 action 上下文重建**唯一口径**。
 *
 * 前身 RollbackService 内联一段、RestoreService.rebuild 再抄一段, 节点/lane/payload/激活期解析
 * 四步顺序必须一致; 现在只留一份, 两条路径共用, 任何一处口径变化不会再只改一边。
 *
 * 口径(与拆分前逐字一致):
 *  - 节点行按 action.nodeInstId 重建 NodeContext; lane 行存在才补 LaneContext(缺失回落节点上下文);
 *  - 分片 payload 现场重建(不落库);
 *  - config 走激活期解析(与驱动路径同口径), 让 ${result.*} / 运行期 vars / 回退前置现取的值可用;
 *  - 解不出的占位符只告警, 不改变执行(与驱动路径同口径)。
 *
 * 线程安全: 无状态; 所有现场都来自入参与 mapper 返回的行。
 */
@Component
public class CompensationContexts {

    private static final Logger log = LoggerFactory.getLogger(CompensationContexts.class);

    private final NodeInstMapper nodeMapper;
    private final LaneMapper laneMapper;
    private final ContextFactory ctxFactory;
    private final VariableResolver resolver;
    private final ConfigFreeze freeze;

    public CompensationContexts(NodeInstMapper nodeMapper, LaneMapper laneMapper,
                                ContextFactory ctxFactory, VariableResolver resolver, ConfigFreeze freeze) {
        this.nodeMapper = nodeMapper;
        this.laneMapper = laneMapper;
        this.ctxFactory = ctxFactory;
        this.resolver = resolver;
        this.freeze = freeze;
    }

    /**
     * 重建补偿/重放用的 action 上下文。
     *
     * @param scene 语境名("补偿"/"重放"), 只用于告警定位, 不参与执行
     */
    public ActionContext rebuild(ProcessContext pctx, ActionInstEntity action, String scene) {
        NodeInstEntity node = nodeMapper.findById(action.getNodeInstId());
        NodeContext nctx = ctxFactory.node(pctx, node);
        Context parent = nctx;
        if (action.getLaneId() != null) {
            LaneEntity lane = laneMapper.findById(action.getLaneId());
            if (lane != null) {
                parent = ctxFactory.lane(nctx, lane);
            }
        }
        // 现场重建分片 payload(不落库): 补偿/重放路径无走查对齐, nctx 由行直接重建
        Object payload = ctxFactory.buildPayload(nctx, action.getShardKey());
        ActionContext actx = ctxFactory.action(parent, action, payload);

        // 激活期解析(与驱动路径同口径): 让 ${result.*} / 运行期 vars / 回退前置现取的值可用
        actx.replaceConfig(freeze.freezeActivation(resolver, actx, Json.readMap(action.getConfig())));
        List<String> pending = resolver.findUnresolved(actx.config());
        if (!pending.isEmpty()) {
            log.warn("{} config 仍含未解析占位符 proc={} action={} keys={}",
                    scene, pctx.procInstId(), action.getActionDefId(), pending);
        }
        return actx;
    }
}
