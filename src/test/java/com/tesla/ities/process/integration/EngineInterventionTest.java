package com.tesla.ities.process.integration;

import com.tesla.ities.process.api.ProcessExecution;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.domain.ActionInstEntity;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.util.Json;
import org.junit.Assert;
import org.junit.Test;

/**
 * 人工干预服务端到端(装配见 {@link EngineE2ETestBase}): pause/resume/stop 翻闸门, retry/skip 向前恢复并重新入队,
 * requestRollback 把闸门翻成 STOPPED(一律落库, 不留本机信号)。前置态靠直接操纵实例行构造(单 owner inline 模型下驱动线程早已退出)。
 */
public class EngineInterventionTest extends EngineE2ETestBase {

    /** 跑一个会失败的单 action 流程, 返回 FAILED 的 action 行(其流程/节点也已终态)。 */
    private ActionInstEntity failedAction(String defId) {
        publish(defId, "flow: \"x\"\nnodes:\n  x:\n    handler: thrower\n");
        String pid = facade.createAndStart(defId, 1, null, "u1").getProcInstId();
        NodeInstEntity node = nodeMapper.findRoots(pid).get(0);
        return actionMapper.findInContainer(node.getNodeInstId(), null).get(0);
    }

    // ===================== retry =====================

    @Test
    public void retryFailedActionRequeuesProcess() {
        ActionInstEntity a = failedAction("retry");
        Assert.assertEquals(Status.FAILED.getStatus(), a.getStatus());
        String pid = processMapper.findById(nodeMapper.findById(a.getNodeInstId()).getProcInstId()).getProcInstId();

        Assert.assertTrue(facade.retryAction(a.getActionInstId(), "op1"));
        // action 复位 CREATED, 所属节点复位 CREATED, 流程 FAILED -> PENDING
        Assert.assertEquals(Status.CREATED.getStatus(), actionMapper.findById(a.getActionInstId()).getStatus());
        Assert.assertEquals(Status.PENDING.getStatus(), processMapper.findById(pid).getStatus());
    }

    @Test
    public void retryOnNonFailedActionReturnsFalse() {
        ActionInstEntity a = failedAction("retry2");
        Assert.assertTrue(facade.retryAction(a.getActionInstId(), "op1"));   // 第一次 CREATED 成功
        // 现在 action 是 created, 再 retry 不满足 FAILED 前置 -> false
        Assert.assertFalse(facade.retryAction(a.getActionInstId(), "op1"));
    }

    @Test
    public void retryOnMissingActionReturnsFalse() {
        Assert.assertFalse(facade.retryAction("ghost-action", "op1"));
    }

    // ===================== skip =====================

    @Test
    public void skipActionMarksSkippedAndRequeues() {
        ActionInstEntity a = failedAction("skip");
        String pid = processMapper.findById(nodeMapper.findById(a.getNodeInstId()).getProcInstId()).getProcInstId();

        Assert.assertTrue(facade.skipAction(a.getActionInstId(), "op1"));
        ActionInstEntity back = actionMapper.findById(a.getActionInstId());
        Assert.assertEquals(Status.SKIPPED.getStatus(), back.getStatus());
        Assert.assertEquals("MANUAL_SKIP", Json.readMap(back.getResult()).get("__skipReason"));
        Assert.assertEquals(Status.PENDING.getStatus(), processMapper.findById(pid).getStatus());
    }

    @Test
    public void skipOnMissingActionReturnsFalse() {
        Assert.assertFalse(facade.skipAction("ghost-action", "op1"));
    }

    // ===================== pause / resume / stop =====================

    private String runningProcess(String defId) {
        publish(defId, "flow: \"x\"\nnodes:\n  x:\n    handler: ok\n");
        String pid = facade.create(defId, 1, null, "u1");
        processMapper.claim(pid, "u1");   // pending -> running
        return pid;
    }

    @Test
    public void pauseThenResumeDrivesToCompletion() {
        String pid = runningProcess("pause");
        Assert.assertTrue(facade.pause(pid, "op1"));            // running -> paused
        Assert.assertEquals(Status.PAUSED.getStatus(), processMapper.findById(pid).getStatus());

        Assert.assertFalse(facade.pause(pid, "op1"));           // 已 paused, 再 pause 前态不符 -> false

        Assert.assertTrue(facade.resume(pid, "op1"));           // paused -> pending(重新入队)
        Assert.assertEquals(Status.PENDING.getStatus(), processMapper.findById(pid).getStatus());

        ProcessExecution r = facade.start(pid);                 // 手动驱动跑完
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
    }

    @Test
    public void stopFromRunningAndFromPaused() {
        String pid = runningProcess("stop");
        Assert.assertTrue(facade.stop(pid, "op1"));             // running -> stopped
        Assert.assertEquals(Status.STOPPED.getStatus(), processMapper.findById(pid).getStatus());
        Assert.assertFalse(facade.stop(pid, "op1"));            // 已 stopped -> 两个 CAS 都落空

        String pid2 = runningProcess("stop2");
        facade.pause(pid2, "op1");                              // running -> paused
        Assert.assertTrue(facade.stop(pid2, "op1"));            // paused -> stopped(回退分支)
    }

    // ===================== requestRollback =====================

    @Test
    public void requestRollbackOnExistingAndMissing() {
        String pid = runningProcess("rb");
        Assert.assertTrue(facade.requestRollback(pid, "op1"));
        // 闸门必须入库(多节点: 驱动可能在别的节点, 本机信号到不了)
        Assert.assertEquals(Status.STOPPED.getStatus(), processMapper.findById(pid).getStatus());
        Assert.assertFalse(facade.requestRollback("ghost-proc", "op1"));
    }

    @Test
    public void requestRollbackKeepsAlreadyCancelledGateAsIs() {
        // 已 FAILED 的流程本就代表取消态, 请求回退不再改写它(免得把"因何停住"的原态抹平)
        ActionInstEntity a = failedAction("rbf");
        String pid = processMapper.findById(nodeMapper.findById(a.getNodeInstId()).getProcInstId()).getProcInstId();
        Assert.assertTrue(facade.requestRollback(pid, "op1"));
        Assert.assertEquals(Status.FAILED.getStatus(), processMapper.findById(pid).getStatus());
    }

    // ===================== retry 时容器链复位(有父节点) =====================

    @Test
    public void retryResetsAncestorContainersUpward() {
        // PARALLEL 里一个子失败; retry 该子 action 时沿父链把 RUNNING/FAILED 容器复位 CREATED
        publish("anc", ""
                + "materialization: EAGER\n"
                + "flow: \"PARALLEL(bad, good)\"\n"
                + "nodes:\n"
                + "  bad:\n    handler: thrower\n"
                + "  good:\n    handler: ok\n");
        String pid = facade.createAndStart("anc", 1, null, "u1").getProcInstId();
        NodeInstEntity par = nodeMapper.findRoots(pid).get(0);
        NodeInstEntity badChild = null;
        for (NodeInstEntity c : nodeMapper.findChildren(pid, par.getNodeInstId())) {
            if (Status.FAILED.getStatus().equals(c.getStatus())) {
                badChild = c;
            }
        }
        Assert.assertNotNull(badChild);
        ActionInstEntity a = actionMapper.findInContainer(badChild.getNodeInstId(), null).get(0);

        Assert.assertTrue(facade.retryAction(a.getActionInstId(), "op1"));
        // 父容器(PARALLEL, FAILED)应被复位 CREATED
        Assert.assertEquals(Status.CREATED.getStatus(), nodeMapper.findById(par.getNodeInstId()).getStatus());
        Assert.assertEquals(Status.PENDING.getStatus(), processMapper.findById(pid).getStatus());
    }
}
