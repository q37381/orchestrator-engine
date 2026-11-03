package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.context.ActionContext;
import com.tt.ii.process.core.context.Context;
import com.tt.ii.process.core.context.LaneContext;
import com.tt.ii.process.core.context.NodeContext;
import com.tt.ii.process.core.context.ProcessContext;
import com.tt.ii.process.core.runtime.ContextFactory;
import com.tt.ii.process.core.runtime.VariableResolver;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.domain.LaneEntity;
import com.tt.ii.process.domain.NodeInstEntity;
import com.tt.ii.process.mapper.LaneMapper;
import com.tt.ii.process.mapper.NodeInstMapper;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 补偿/重放上下文重建单测: 锁死"节点 -> lane(可选) -> payload -> action -> 激活期解析"这条链。
 *
 * 这条链原来 RollbackService 内联一份、RestoreService 再抄一份; 抽成 CompensationContexts 后
 * 在这里一次锁住, 两条补偿路径不会再出现"一边补了 lane, 一边忘了"的漂移。
 */
@RunWith(MockitoJUnitRunner.class)
public class CompensationContextsTest {

    @Mock
    private NodeInstMapper nodeMapper;
    @Mock
    private LaneMapper laneMapper;
    @Mock
    private ContextFactory ctxFactory;
    @Mock
    private VariableResolver resolver;
    @Mock
    private NodeContext nctx;
    @Mock
    private LaneContext lctx;
    @Mock
    private ActionContext actx;

    private CompensationContexts contexts;

    @Before
    public void setUp() {
        contexts = new CompensationContexts(nodeMapper, laneMapper, ctxFactory, resolver, new ConfigFreeze());
        when(ctxFactory.node(any(ProcessContext.class), any(NodeInstEntity.class))).thenReturn(nctx);
        when(ctxFactory.lane(any(NodeContext.class), any(LaneEntity.class))).thenReturn(lctx);
        when(ctxFactory.buildPayload(any(NodeContext.class), any())).thenReturn("payload");
        when(ctxFactory.action(any(Context.class), any(ActionInstEntity.class), any())).thenReturn(actx);
        when(actx.config()).thenReturn(new LinkedHashMap<String, Object>());
        when(resolver.resolve(any(), any(), any())).thenReturn(new LinkedHashMap<String, Object>());
        when(resolver.findUnresolved(any())).thenReturn(Collections.<String>emptyList());
    }

    /** 非分片: node -> payload -> action; config 走激活期解析(与驱动路径同口径)。 */
    @Test
    public void rebuildNonShardedActionUsesNodeContextAndActivationConfig() {
        ActionInstEntity action = action("A1", "N1", null, "{\"sql\":\"${vars.x}\"}");
        when(nodeMapper.findById("N1")).thenReturn(new NodeInstEntity());

        ActionContext out = contexts.rebuild(new ProcessContext("P"), action, "补偿");

        Assert.assertSame(actx, out);
        verify(ctxFactory).node(any(ProcessContext.class), any(NodeInstEntity.class));
        verify(ctxFactory).buildPayload(nctx, null);
        verify(ctxFactory).action(eq(nctx), eq(action), eq("payload"));
        verify(actx).replaceConfig(any(Map.class));
        verify(resolver).findUnresolved(actx.config());
    }

    /** 分片 lane: lane 行存在时以 LaneContext 为父; payload 用 action.shardKey。 */
    @Test
    public void rebuildLaneActionUsesLaneContext() {
        ActionInstEntity action = action("A1", "N1", "L1", "{}");
        action.setShardKey("shard-1");
        NodeInstEntity node = new NodeInstEntity();
        LaneEntity lane = new LaneEntity();
        when(nodeMapper.findById("N1")).thenReturn(node);
        when(laneMapper.findById("L1")).thenReturn(lane);

        ActionContext out = contexts.rebuild(new ProcessContext("P"), action, "重放");

        Assert.assertSame(actx, out);
        verify(ctxFactory).lane(nctx, lane);
        verify(ctxFactory).buildPayload(nctx, "shard-1");
        verify(ctxFactory).action(eq(lctx), eq(action), eq("payload"));
    }

    private static ActionInstEntity action(String instId, String nodeId, String laneId, String config) {
        ActionInstEntity a = new ActionInstEntity();
        a.setActionInstId(instId);
        a.setNodeInstId(nodeId);
        a.setLaneId(laneId);
        a.setActionDefId("a1");
        a.setConfig(config);
        return a;
    }
}
