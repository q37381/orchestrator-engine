package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.model.RollbackStatus;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.domain.RollbackRecordEntity;
import com.tt.ii.process.mapper.RollbackRecordMapper;
import com.tt.ii.process.util.IdGenerator;
import com.tt.ii.process.util.Json;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 补偿/重放记录写入口径单测: 锁死 rollback_record 的字段与 detail JSON 形状。
 *
 * 这些字段是补偿历史与排障的对外事实, 原来 RollbackService/RestoreService 各写一份;
 * 收成 RollbackTrail 后在这里一次锁住, 避免两条路径再漂移。
 */
@RunWith(MockitoJUnitRunner.class)
public class RollbackTrailTest {

    @Mock
    private RollbackRecordMapper rollbackMapper;
    @Mock
    private IdGenerator ids;

    private RollbackTrail trail;

    @Before
    public void setUp() {
        trail = new RollbackTrail(rollbackMapper, ids);
        when(ids.next(any())).thenReturn("R1");
    }

    /** begin: 先落占位行, 字段与 detail 形状与既有落库逐字一致。 */
    @Test
    public void beginWritesPlaceholderRecordWithExpectedFields() {
        ActionInstEntity action = action("A1", "a1", 10);

        String rid = trail.begin("P", action, RollbackStatus.COMPENSATING, "op", "compensating");

        Assert.assertEquals("R1", rid);
        ArgumentCaptor<RollbackRecordEntity> captor = ArgumentCaptor.forClass(RollbackRecordEntity.class);
        verify(rollbackMapper).insert(captor.capture());
        RollbackRecordEntity r = captor.getValue();
        Assert.assertEquals("R1", r.getRollbackId());
        Assert.assertEquals("P", r.getProcInstId());
        Assert.assertEquals("A1", r.getActionInstId());
        Assert.assertEquals("a1", r.getActionDefId());
        Assert.assertEquals(RollbackStatus.COMPENSATING.getStatus(), r.getStatus());
        Assert.assertEquals("op", r.getOperator());
        Assert.assertEquals("op", r.getCreatorId());
        Assert.assertEquals("op", r.getUpdatorId());
        Assert.assertEquals("compensating", Json.readMap(r.getDetail()).get("reason"));
    }

    /** update: 只改 status 与 detail; detail 的键序保持 reason -> error。 */
    @Test
    public void updateWritesStatusAndOrderedDetail() {
        trail.update("R1", RollbackStatus.COMPENSATE_FAILED, "op", "补偿失败", "boom");

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(rollbackMapper).updateStatus(eq("R1"), eq(RollbackStatus.COMPENSATE_FAILED.getStatus()),
                detail.capture(), eq("op"));
        Map<String, Object> m = Json.readMap(detail.getValue());
        Assert.assertEquals("补偿失败", m.get("reason"));
        Assert.assertEquals("boom", m.get("error"));
        Assert.assertTrue("detail 键序 reason 在 error 之前",
                detail.getValue().indexOf("\"reason\"") < detail.getValue().indexOf("\"error\""));
    }

    /** latestCompensated: 只看最新一条; COMPENSATE_FAILED / BLOCKED 仍欠撤销。 */
    @Test
    public void latestCompensatedOnlyAcceptsCompensated() {
        when(rollbackMapper.findLatestByAction("A1")).thenReturn(record(RollbackStatus.COMPENSATED));
        when(rollbackMapper.findLatestByAction("A2")).thenReturn(record(RollbackStatus.COMPENSATE_FAILED));
        when(rollbackMapper.findLatestByAction("A3")).thenReturn(null);

        Assert.assertTrue(trail.latestCompensated("A1"));
        Assert.assertFalse(trail.latestCompensated("A2"));
        Assert.assertFalse(trail.latestCompensated("A3"));
    }

    /** history 直接透传 mapper 的升序结果。 */
    @Test
    public void historyDelegatesToMapper() {
        List<RollbackRecordEntity> rows = Collections.singletonList(record(RollbackStatus.RESTORING));
        when(rollbackMapper.findByProcess("P")).thenReturn(rows);

        Assert.assertSame(rows, trail.history("P"));
    }

    private static ActionInstEntity action(String instId, String defId, long autoId) {
        ActionInstEntity a = new ActionInstEntity();
        a.setActionInstId(instId);
        a.setActionDefId(defId);
        a.setAutoId(autoId);
        return a;
    }

    private static RollbackRecordEntity record(RollbackStatus status) {
        RollbackRecordEntity r = new RollbackRecordEntity();
        r.setStatus(status.getStatus());
        return r;
    }
}
