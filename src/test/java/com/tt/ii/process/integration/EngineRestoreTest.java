package com.tt.ii.process.integration;

import com.tt.ii.process.core.model.RollbackStatus;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.domain.NodeInstEntity;
import com.tt.ii.process.domain.RollbackRecordEntity;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 向前重放(恢复现场)端到端(装配见 {@link EngineE2ETestBase}): 回退把已成功的副作用逆序撤销后,
 * 一旦重试重新驱动, 引擎在 driveRoots 之前按执行顺序把它们正序施加回来(RestoreService)。
 *
 * 覆盖 Saga 闭环"handle -> compensate -> restore -> 续跑"的两条关键性质:
 *  - <b>能续跑</b>: 回退过的流程重试时, 被撤销的前置副作用先重放回来, 失败节点在恢复好的现场上跑完;
 *    且原 action 行始终不动(它是不可变执行事实), 重放只往 rollback_record 追加历史。
 *  - <b>可重复</b>: 重放失败当场挂起(不向前驱动), 修好现场再重试仍从没立稳那条接着放;
 *    补偿与重放严格交替, 多轮"回退->重放"既不重复撤销也不重复施加。
 */
public class EngineRestoreTest extends EngineE2ETestBase {

    /** 回退过 -> 重试: 驱动前把 a 撤销过的副作用重放回来, b 在恢复好的现场上续跑成功。 */
    @Test
    public void retryAfterRollbackReplaysCompensatedSideEffectsThenCompletes() {
        publish("rsOk", ""
                + "autoRollback: true\n"
                + "flow: \"a,b\"\n"
                + "nodes:\n"
                + "  a:\n    handler: compOk\n"          // 可补偿 + restore 记一笔
                + "  b:\n    handler: flaky\n");          // 首次抛, 重试那次成功
        String pid = run("rsOk").getProcInstId();
        Assert.assertEquals(Status.FAILED, Status.of(processMapper.findById(pid).getStatus()));
        Assert.assertEquals("首轮收尾自动逆序补偿 a", RollbackStatus.COMPENSATED.getStatus(), latestRollbackStatus(pid, "a"));

        Assert.assertTrue(facade.retry(pid, "op1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertTrue("回退后再重试: 驱动前把 a 的副作用重新施加回来", rec.order.contains("restore:compOk"));
        Assert.assertEquals(RollbackStatus.RESTORED.getStatus(), latestRollbackStatus(pid, "a"));
        // 原 action 行是不可变执行事实: a 始终 SUCCESS, 只是多了重放留痕, 且从没被重新 handle(已 SUCCESS -> driveRoots 幂等跳过)
        Assert.assertEquals(Status.SUCCESS.getStatus(), actionByDef(pid, "a").getStatus());
        Assert.assertEquals(Status.SUCCESS.getStatus(), actionByDef(pid, "b").getStatus());
        // 只有真正要续跑的 b 被 handle 了两次(首轮抛 + 重试成); a 的 handle 没被重放以外的路径重复触发
        Assert.assertEquals(2, Collections.frequency(rec.order, "handler:flaky:b"));
    }

    /** 重放失败当场挂起: 落 FAILED 且<b>不向前驱动</b>(driveRoots 整段跳过), 也不触发 autoRollback。 */
    @Test
    public void restoreFailureSuspendsAndDoesNotDriveForward() {
        publish("rsFail", ""
                + "autoRollback: true\n"
                + "flow: \"a,b\"\n"
                + "nodes:\n"
                + "  a:\n    handler: restoreFail\n"      // 补偿成功(默认空实现), 但 restore 恒抛
                + "  b:\n    handler: flaky\n");
        String pid = run("rsFail").getProcInstId();
        Assert.assertEquals(RollbackStatus.COMPENSATED.getStatus(), latestRollbackStatus(pid, "a"));

        Assert.assertTrue(facade.retry(pid, "op1"));
        Assert.assertEquals("重放失败当场挂起", Status.FAILED, facade.start(pid).getStatus());

        Assert.assertEquals(RollbackStatus.RESTORE_FAILED.getStatus(), latestRollbackStatus(pid, "a"));
        // driveRoots 被整段跳过: b 从未被重新驱动 -> flaky 只跑过首轮那一次, 且停在复位后的 created
        Assert.assertEquals(1, Collections.frequency(rec.order, "handler:flaky:b"));
        Assert.assertEquals(Status.CREATED.getStatus(), actionByDef(pid, "b").getStatus());
        // 挂起这条不触发 autoRollback: a 的补偿只发生在首轮一次, 之后只有那一条重放失败记录
        Assert.assertEquals(Arrays.asList(
                RollbackStatus.COMPENSATED.getStatus(), RollbackStatus.RESTORE_FAILED.getStatus()),
                rollbackStatusesOf(pid, "a"));
    }

    /** 闭环可重复: 重放失败挂起后, 修好现场再重试 -> 从没立稳那条接着放 -> 成功后续跑跑完。 */
    @Test
    public void restoreLoopIsRepeatableAcrossRetries() {
        publish("rsLoop", ""
                + "autoRollback: true\n"
                + "flow: \"a,b\"\n"
                + "nodes:\n"
                + "  a:\n    handler: flakyRestore\n"      // restore 头一次抛, 之后成功
                + "  b:\n    handler: flaky\n");
        String pid = run("rsLoop").getProcInstId();
        Assert.assertEquals(RollbackStatus.COMPENSATED.getStatus(), latestRollbackStatus(pid, "a"));

        // 重试一: 重放抛(第一试) -> RESTORE_FAILED 挂起, b 没往前
        Assert.assertTrue(facade.retry(pid, "op1"));
        Assert.assertEquals(Status.FAILED, facade.start(pid).getStatus());
        Assert.assertEquals(RollbackStatus.RESTORE_FAILED.getStatus(), latestRollbackStatus(pid, "a"));
        Assert.assertEquals(1, Collections.frequency(rec.order, "restore:flakyRestore"));
        Assert.assertEquals(Status.CREATED.getStatus(), actionByDef(pid, "b").getStatus());

        // 重试二: a 最新 RESTORE_FAILED 仍欠重放 -> 再放成功(第二试) -> RESTORED; driveRoots 续跑 b -> SUCCESS
        Assert.assertTrue(facade.retry(pid, "op1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());
        Assert.assertEquals(RollbackStatus.RESTORED.getStatus(), latestRollbackStatus(pid, "a"));
        Assert.assertEquals(2, Collections.frequency(rec.order, "restore:flakyRestore"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), actionByDef(pid, "b").getStatus());
    }

    /** 补偿与重放严格交替: b 恒失败, 每轮重试都是"重放 a -> b 又崩 -> 再补偿 a", 不重复撤销也不重复施加。 */
    @Test
    public void repeatedRollbackAndRestoreAlternateWithoutDoubleUndo() {
        publish("rsAlt", ""
                + "autoRollback: true\n"
                + "flow: \"a,b\"\n"
                + "nodes:\n"
                + "  a:\n    handler: compOk\n"
                + "  b:\n    handler: thrower\n");         // 永远失败
        String pid = run("rsAlt").getProcInstId();          // 首轮: 补偿 a -> COMPENSATED
        Assert.assertTrue(facade.retry(pid, "op1"));
        Assert.assertEquals(Status.FAILED, facade.start(pid).getStatus());   // 重放 a -> RESTORED; b 又崩 -> 再补偿 a

        // a 的历史严格交替: compensated -> restored -> compensated(最新又欠一次重放), 没有连续两次同类
        Assert.assertEquals(Arrays.asList(
                RollbackStatus.COMPENSATED.getStatus(),
                RollbackStatus.RESTORED.getStatus(),
                RollbackStatus.COMPENSATED.getStatus()),
                rollbackStatusesOf(pid, "a"));
        Assert.assertEquals(2, Collections.frequency(rec.order, "compensate:compOk"));
        Assert.assertEquals(1, Collections.frequency(rec.order, "restore:compOk"));
    }

    /** 从没回退过的正常流程: 重放接线只多一次空查询即返回, 一行历史都不产生。 */
    @Test
    public void processThatNeverRolledBackReplaysNothing() {
        publish("rsNone", "flow: \"a\"\nnodes:\n  a:\n    handler: compOk\n");
        String pid = run("rsNone").getProcInstId();
        Assert.assertEquals(Status.SUCCESS, Status.of(processMapper.findById(pid).getStatus()));
        Assert.assertTrue("没回退过就没有重放留痕", rollbackMapper.findByProcess(pid).isEmpty());
        Assert.assertFalse(rec.order.contains("restore:compOk"));
    }

    // ---------- 读库小助手 ----------

    /** 某 action 的最新一条补偿/重放记录状态(按 auto_id 升序取最后一条); 无记录返回 null。 */
    private String latestRollbackStatus(String procInstId, String actionDefId) {
        List<String> statuses = rollbackStatusesOf(procInstId, actionDefId);
        return statuses.isEmpty() ? null : statuses.get(statuses.size() - 1);
    }

    /** 某 action 的全部补偿/重放状态, 按步骤执行顺序(auto_id 升序)。 */
    private List<String> rollbackStatusesOf(String procInstId, String actionDefId) {
        List<String> out = new ArrayList<>();
        for (RollbackRecordEntity r : rollbackMapper.findByProcess(procInstId)) {
            if (actionDefId.equals(r.getActionDefId())) {
                out.add(r.getStatus());
            }
        }
        return out;
    }

    /** 按 action 定义 id 找到那一条 action 行(本夹具里每个单 handler 节点各一条, defId = 节点引用名)。 */
    private ActionInstEntity actionByDef(String procInstId, String actionDefId) {
        for (NodeInstEntity n : nodeMapper.findRoots(procInstId)) {
            for (ActionInstEntity a : actionMapper.findInContainer(n.getNodeInstId(), null)) {
                if (actionDefId.equals(a.getActionDefId())) {
                    return a;
                }
            }
        }
        return null;
    }
}
