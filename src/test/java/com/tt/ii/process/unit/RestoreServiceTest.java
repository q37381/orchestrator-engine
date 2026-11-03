package com.tt.ii.process.unit;

import com.tt.ii.process.core.context.ActionContext;
import com.tt.ii.process.core.context.NodeContext;
import com.tt.ii.process.core.context.ProcessContext;
import com.tt.ii.process.core.engine.CompensationContexts;
import com.tt.ii.process.core.engine.ConfigFreeze;
import com.tt.ii.process.core.engine.RestoreService;
import com.tt.ii.process.core.engine.RollbackTrail;
import com.tt.ii.process.core.model.RollbackStatus;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.core.runtime.ContextFactory;
import com.tt.ii.process.core.runtime.VariableResolver;
import com.tt.ii.process.core.spi.ActionHandler;
import com.tt.ii.process.core.spi.ActionHandlerRegistry;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.domain.ProcessInstEntity;
import com.tt.ii.process.domain.RollbackRecordEntity;
import com.tt.ii.process.mapper.ActionInstMapper;
import com.tt.ii.process.mapper.LaneMapper;
import com.tt.ii.process.mapper.NodeInstMapper;
import com.tt.ii.process.mapper.ProcessInstMapper;
import com.tt.ii.process.mapper.RollbackRecordMapper;
import com.tt.ii.process.util.IdGenerator;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 向前重放服务单测(不启 Spring): 只验 RestoreService 自己的决策与留痕口径, 协作对象一律 mock。
 *
 * 关注点(E2E 走真实装配覆盖驱动接线, 这里补的是纯服务侧的分支):
 *  - 目标流程不存在 / 从没回退过 / 现场已到位(最新 RESTORED) —— 三条短路都不碰 handler;
 *  - 最新 COMPENSATED 才重放: 先落 RESTORING 占位、成功后把同一行改 RESTORED;
 *  - 重放按 <b>action 执行顺序(auto_id 升序)</b> 施加, 与补偿历史自身的逆序无关;
 *  - 一条重放失败当场打 RESTORE_FAILED 并 break, 其余 action 一律不碰。
 *
 * 上下文重建链在非分片路径上只碰 ctxFactory.node/action + resolver, 统一 stub 成可用替身;
 * 用的是 {@link MockitoJUnitRunner.Silent}(短路用例天然用不到部分通用 stub)。
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class RestoreServiceTest {

    @Mock
    private ProcessInstMapper processMapper;
    @Mock
    private NodeInstMapper nodeMapper;
    @Mock
    private LaneMapper laneMapper;
    @Mock
    private ActionInstMapper actionMapper;
    @Mock
    private RollbackRecordMapper rollbackMapper;
    @Mock
    private ActionHandlerRegistry handlers;
    @Mock
    private ContextFactory ctxFactory;
    @Mock
    private VariableResolver resolver;
    @Mock
    private ActionContext actx;
    @Mock
    private NodeContext nctx;

    /** 业务键生成器替身: 重放每步都要给 rollback_record 一个号, 本用例内不重复即可。 */
    @Mock
    private IdGenerator ids;
    private final AtomicInteger keySeq = new AtomicInteger();
    private RestoreService service;

    @Before
    public void wire() {
        when(ids.next(any())).thenAnswer(inv -> "R" + keySeq.incrementAndGet());
        // 用真的 RollbackTrail/CompensationContexts 组合, 只 mock 最底层 mapper/工厂:
        // 既验 RestoreService 的决策, 也验共享内核写出的记录字段与上下文重建链。
        RollbackTrail trail = new RollbackTrail(rollbackMapper, ids);
        CompensationContexts contexts = new CompensationContexts(nodeMapper, laneMapper, ctxFactory, resolver,
                new ConfigFreeze());
        service = new RestoreService(processMapper, actionMapper, handlers, ctxFactory, trail, contexts);
        // 非分片重放路径的上下文重建链: 返回可直接用的替身, 让被测代码走到 handler.restore()
        when(ctxFactory.process(any())).thenReturn(mock(ProcessContext.class));
        when(ctxFactory.node(any(), any())).thenReturn(nctx);
        when(ctxFactory.action(any(), any(), any())).thenReturn(actx);
        when(actx.config()).thenReturn(new LinkedHashMap<String, Object>());
        when(resolver.resolve(any(), any(), any())).thenReturn(new LinkedHashMap<String, Object>());
        when(resolver.findUnresolved(any())).thenReturn(Collections.emptyList());
    }

    @Test
    public void replayOnUnknownProcessIsNoop() {
        when(processMapper.findById("ghost")).thenReturn(null);

        RestoreService.RestoreReport report = service.replay("ghost", "op");

        Assert.assertTrue(report.isEmpty());
        Assert.assertFalse(report.hasFailure());
        verify(rollbackMapper, never()).findByProcess(any());
        verify(handlers, never()).get(any());
    }

    @Test
    public void replayWithNoRollbackHistoryIsNoop() {
        when(processMapper.findById("P")).thenReturn(proc("P"));
        when(rollbackMapper.findByProcess("P")).thenReturn(Collections.emptyList());

        RestoreService.RestoreReport report = service.replay("P", "op");

        Assert.assertTrue(report.isEmpty());
        verify(handlers, never()).get(any());
    }

    /** 最新一条已是 RESTORED = 现场已重新施加, 不该再重放(闭环可重复的关键幂等护栏)。 */
    @Test
    public void alreadyRestoredActionIsSkipped() {
        when(processMapper.findById("P")).thenReturn(proc("P"));
        when(rollbackMapper.findByProcess("P"))
                .thenReturn(Collections.singletonList(rec("A1", "a", RollbackStatus.RESTORED)));

        RestoreService.RestoreReport report = service.replay("P", "op");

        Assert.assertTrue(report.isEmpty());
        verify(handlers, never()).get(any());
        verify(rollbackMapper, never()).insert(any());
    }

    @Test
    public void compensatedActionIsReplayedAndMarkedRestored() throws Exception {
        when(processMapper.findById("P")).thenReturn(proc("P"));
        when(rollbackMapper.findByProcess("P"))
                .thenReturn(Collections.singletonList(rec("A1", "a", RollbackStatus.COMPENSATED)));
        when(actionMapper.findById("A1")).thenReturn(action("A1", "a", "ha", 10));
        ActionHandler ha = mock(ActionHandler.class);
        when(handlers.get("ha")).thenReturn(ha);

        RestoreService.RestoreReport report = service.replay("P", "op");

        Assert.assertEquals(1, report.restored.size());
        Assert.assertFalse(report.hasFailure());
        verify(ha).restore(actx);
        // 先落 RESTORING 占位一行, 再把同一行改 RESTORED(不新增第二条)
        ArgumentCaptor<RollbackRecordEntity> inserted = ArgumentCaptor.forClass(RollbackRecordEntity.class);
        verify(rollbackMapper).insert(inserted.capture());
        Assert.assertEquals(RollbackStatus.RESTORING.getStatus(), inserted.getValue().getStatus());
        verify(rollbackMapper).updateStatus(eq(inserted.getValue().getRollbackId()),
                eq(RollbackStatus.RESTORED.getStatus()), any(), eq("op"));
    }

    /** 重放顺序取 action 的执行顺序(auto_id 升序), 而不是补偿历史自身的逆序留痕顺序。 */
    @Test
    public void replayAppliesInActionExecutionOrder() throws Exception {
        when(processMapper.findById("P")).thenReturn(proc("P"));
        // 历史按补偿执行顺序(逆序)落库: A1(后执行)在前, A2(先执行)在后
        when(rollbackMapper.findByProcess("P")).thenReturn(Arrays.asList(
                rec("A1", "a", RollbackStatus.COMPENSATED),
                rec("A2", "b", RollbackStatus.COMPENSATED)));
        when(actionMapper.findById("A1")).thenReturn(action("A1", "a", "ha", 20));   // auto_id 大 = 后执行
        when(actionMapper.findById("A2")).thenReturn(action("A2", "b", "hb", 5));    // auto_id 小 = 先执行
        List<String> order = new ArrayList<>();
        ActionHandler ha = mock(ActionHandler.class);
        ActionHandler hb = mock(ActionHandler.class);
        doAnswer(i -> order.add("A1")).when(ha).restore(any());
        doAnswer(i -> order.add("A2")).when(hb).restore(any());
        when(handlers.get("ha")).thenReturn(ha);
        when(handlers.get("hb")).thenReturn(hb);

        RestoreService.RestoreReport report = service.replay("P", "op");

        Assert.assertEquals(Arrays.asList("A2", "A1"), order);   // 正序: auto_id 5 先于 20
        Assert.assertEquals(2, report.restored.size());
    }

    /** 一条重放失败: 打 RESTORE_FAILED 并当场 break, 其余 action 一律不碰(不向前 = 现场半恢复不硬跑)。 */
    @Test
    public void replayStopsAtFirstFailureAndMarksRestoreFailed() throws Exception {
        when(processMapper.findById("P")).thenReturn(proc("P"));
        when(rollbackMapper.findByProcess("P")).thenReturn(Arrays.asList(
                rec("A1", "a", RollbackStatus.COMPENSATED),
                rec("A2", "b", RollbackStatus.COMPENSATED)));
        when(actionMapper.findById("A1")).thenReturn(action("A1", "a", "ha", 5));     // 先执行这条
        when(actionMapper.findById("A2")).thenReturn(action("A2", "b", "hb", 20));    // 后执行这条(不该被触及)
        ActionHandler ha = mock(ActionHandler.class);
        doThrow(new IllegalStateException("restore boom")).when(ha).restore(any());
        ActionHandler hb = mock(ActionHandler.class);
        when(handlers.get("ha")).thenReturn(ha);
        when(handlers.get("hb")).thenReturn(hb);

        RestoreService.RestoreReport report = service.replay("P", "op");

        Assert.assertTrue(report.hasFailure());
        verify(ha).restore(any());
        verify(hb, never()).restore(any());
        verify(rollbackMapper, times(1)).insert(any());
        verify(rollbackMapper).updateStatus(any(), eq(RollbackStatus.RESTORE_FAILED.getStatus()), any(), eq("op"));
    }

    // ---------- 造行小件 ----------

    private static ProcessInstEntity proc(String id) {
        ProcessInstEntity p = new ProcessInstEntity();
        p.setProcInstId(id);
        return p;
    }

    private static ActionInstEntity action(String instId, String defId, String handler, long autoId) {
        ActionInstEntity a = new ActionInstEntity();
        a.setActionInstId(instId);
        a.setActionDefId(defId);
        a.setHandlerName(handler);
        a.setNodeInstId("N-" + instId);
        a.setAutoId(autoId);
        a.setStatus(Status.SUCCESS.getStatus());     // 只有 SUCCESS 且被补偿过的才欠重放
        return a;
    }

    private static RollbackRecordEntity rec(String instId, String defId, RollbackStatus status) {
        RollbackRecordEntity r = new RollbackRecordEntity();
        r.setActionInstId(instId);
        r.setActionDefId(defId);
        r.setStatus(status.getStatus());
        return r;
    }
}
