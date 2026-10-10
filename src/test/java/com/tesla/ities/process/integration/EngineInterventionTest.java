package com.tesla.ities.process.integration;

import com.tesla.ities.process.api.ProcessExecution;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.domain.ActionInstEntity;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.util.Json;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

/**
 * 人工干预服务端到端(装配见 {@link EngineE2ETestBase}): pause/resume/stop 翻闸门, retry 向前恢复并重新入队
 * (流程实例粒度: 一次复位所有"本轮没跑成"的行; action 粒度留作定点重跑), skip 定点跳过,
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

    /** 某 action 行所属的流程实例 id。 */
    private String pidOf(ActionInstEntity a) {
        return nodeMapper.findById(a.getNodeInstId()).getProcInstId();
    }

    // ===================== retry(流程实例粒度) =====================

    /** 重试以流程实例 id 发起: 一次把整条坏链(失败 action + 其节点 + 沿途容器)复位, 已成功的原样保留。 */
    @Test
    public void retryProcessResetsFailedChainAndRequeues() {
        publish("pRetry", ""
                + "materialization: EAGER\n"
                + "flow: \"PARALLEL(bad, good)\"\n"
                + "nodes:\n"
                + "  bad:\n    handler: thrower\n"
                + "  good:\n    handler: ok\n");
        String pid = facade.createAndStart("pRetry", 1, null, "u1").getProcInstId();
        NodeInstEntity par = nodeMapper.findRoots(pid).get(0);
        NodeInstEntity bad = null;
        NodeInstEntity good = null;
        for (NodeInstEntity c : nodeMapper.findChildren(pid, par.getNodeInstId())) {
            if (Status.FAILED.getStatus().equals(c.getStatus())) {
                bad = c;
            } else {
                good = c;
            }
        }
        Assert.assertNotNull(bad);
        ActionInstEntity badAction = actionMapper.findInContainer(bad.getNodeInstId(), null).get(0);
        ActionInstEntity goodAction = actionMapper.findInContainer(good.getNodeInstId(), null).get(0);

        Assert.assertTrue(facade.retry(pid, "op1"));
        Assert.assertEquals(Status.CREATED.getStatus(), actionMapper.findById(badAction.getActionInstId()).getStatus());
        // 已成功那条是既成事实: 不重算、不复位
        Assert.assertEquals(Status.SUCCESS.getStatus(), actionMapper.findById(goodAction.getActionInstId()).getStatus());
        Assert.assertEquals(Status.CREATED.getStatus(), nodeMapper.findById(bad.getNodeInstId()).getStatus());
        Assert.assertEquals(Status.CREATED.getStatus(), nodeMapper.findById(par.getNodeInstId()).getStatus());
        Assert.assertEquals(Status.PENDING.getStatus(), processMapper.findById(pid).getStatus());
    }

    /** 复位后重新驱动 = 从断点续跑: 只重跑被复位的那条, 后面还没物化的项补建后照常跑完; 上一轮痕迹被摘干净。 */
    @Test
    public void retryProcessRerunsOnlyResetItemAndContinuesToEnd() {
        publish("pRetry2", ""
                + "flow: \"n\"\n"
                + "nodes:\n"
                + "  n:\n"
                + "    actions:\n"
                + "      - {id: first, handler: flaky}\n"        // 头一次抛, 重试那次成功
                + "      - {id: second, handler: ok}\n");        // FAIL_FAST 下首轮根本没建行
        String pid = facade.createAndStart("pRetry2", 1, null, "u1").getProcInstId();
        NodeInstEntity node = nodeMapper.findRoots(pid).get(0);
        Assert.assertEquals(1, actionMapper.findInContainer(node.getNodeInstId(), null).size());

        Assert.assertTrue(facade.retry(pid, "op1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        List<ActionInstEntity> acts = actionMapper.findInContainer(node.getNodeInstId(), null);
        Assert.assertEquals(2, acts.size());                              // 缺行按 LAZY 补建
        Assert.assertEquals(Status.SUCCESS.getStatus(), acts.get(0).getStatus());
        Assert.assertEquals(Status.SUCCESS.getStatus(), acts.get(1).getStatus());
        // 重跑成功不该还挂着上一轮的失败痕迹(进度视图直接读这些保留键)
        Assert.assertFalse(Json.readMap(acts.get(0).getResult()).containsKey("__error"));
        Assert.assertFalse(Json.readMap(nodeMapper.findById(node.getNodeInstId()).getResult()).containsKey("__error"));
        Assert.assertNull(engine.errorOf(pid));
        // 只有被复位那条重跑了一次, 补建那条从头到尾只跑一次
        Assert.assertEquals(2, Collections.frequency(rec.order, "handler:flaky:first"));
        Assert.assertEquals(1, Collections.frequency(rec.order, "handler:ok:second"));
    }

    /** 什么都没复位(已成功的流程)就是空转: 返 false 且不动闸门 —— 重试不该把跑成功的流程再跑一遍。 */
    @Test
    public void retryProcessOnSucceededProcessIsNoOp() {
        publish("pRetry3", "flow: \"x\"\nnodes:\n  x:\n    handler: ok\n");
        String pid = facade.createAndStart("pRetry3", 1, null, "u1").getProcInstId();
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertFalse(facade.retry(pid, "op1"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), processMapper.findById(pid).getStatus());
        Assert.assertEquals(Status.SUCCESS.getStatus(),
                actionMapper.findInContainer(nodeMapper.findRoots(pid).get(0).getNodeInstId(), null).get(0).getStatus());
    }

    /**
     * 闸门翻不动(SUCCESS)就**一行都不碰**: 已完成流程里可能留着上一轮 drain 的 stopped 行(重入时被 CREATED
     * 过滤跳过, 流程照样收 SUCCESS), 把它们翻成 CREATED 却没人来跑, 就是"改了一半还不能重跑"。
     */
    @Test
    public void retryProcessOnSuccessLeavesResidualRowsUntouched() {
        publish("pRetry6", ""
                + "flow: \"n\"\n"
                + "nodes:\n"
                + "  n:\n"
                + "    actions:\n"
                + "      - {id: a1, handler: ok}\n"
                + "      - {id: a2, handler: ok}\n");
        String pid = facade.createAndStart("pRetry6", 1, null, "u1").getProcInstId();
        NodeInstEntity node = nodeMapper.findRoots(pid).get(0);
        List<ActionInstEntity> acts = actionMapper.findInContainer(node.getNodeInstId(), null);
        Assert.assertEquals(2, acts.size());
        // 造残留: 上一轮 drain 留下的 stopped 兄弟行
        actionMapper.setStatus(acts.get(1).getActionInstId(), Status.STOPPED.getStatus(), "op1");

        Assert.assertFalse(facade.retry(pid, "op1"));
        Assert.assertEquals("资格不符就不该碰这行", Status.STOPPED.getStatus(),
                actionMapper.findById(acts.get(1).getActionInstId()).getStatus());
        Assert.assertEquals(Status.SUCCESS.getStatus(), processMapper.findById(pid).getStatus());
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeMapper.findById(node.getNodeInstId()).getStatus());
    }

    /** drain 掉的 stopped 项同样能靠流程重试捞回来(它们本就还没执行过)。 */
    @Test
    public void retryProcessResetsStoppedRows() {
        ActionInstEntity a = failedAction("pRetry4");
        String pid = pidOf(a);
        // 造前置态: 人工 stop 抢先翻走 + drain 把没跑的兄弟置 stopped
        processMapper.casStatus(pid, Status.FAILED.getStatus(), Status.STOPPED.getStatus(), "op1");
        nodeMapper.setStatus(a.getNodeInstId(), Status.STOPPED.getStatus(), "op1");
        actionMapper.setStatus(a.getActionInstId(), Status.STOPPED.getStatus(), "op1");

        Assert.assertTrue(facade.retry(pid, "op1"));
        Assert.assertEquals(Status.CREATED.getStatus(), actionMapper.findById(a.getActionInstId()).getStatus());
        Assert.assertEquals(Status.CREATED.getStatus(), nodeMapper.findById(a.getNodeInstId()).getStatus());
        Assert.assertEquals(Status.PENDING.getStatus(), processMapper.findById(pid).getStatus());
    }

    /**
     * 一行都没跑成的坏终态(流程 before 否决, EAGER 下节点行还都是 CREATED): 没有可复位项, 但闸门照旧翻回
     * PENDING 再驱动一次 —— 重试在这条路上等价"从头跑", 因为确实什么都没跑过。
     */
    @Test
    public void retryProcessRequeuesWhenNothingRanYet() {
        publish("pRetry5", ""
                + "materialization: EAGER\n"
                + "flow: \"x\"\n"
                + "listeners:\n  - name: pVeto\n"
                + "nodes:\n  x:\n    handler: ok\n");
        String pid = facade.createAndStart("pRetry5", 1, null, "u1").getProcInstId();
        Assert.assertEquals(Status.FAILED.getStatus(), processMapper.findById(pid).getStatus());
        NodeInstEntity node = nodeMapper.findRoots(pid).get(0);
        Assert.assertEquals(Status.CREATED.getStatus(), node.getStatus());

        Assert.assertTrue(facade.retry(pid, "op1"));
        Assert.assertEquals(Status.PENDING.getStatus(), processMapper.findById(pid).getStatus());
        Assert.assertEquals(Status.CREATED.getStatus(), nodeMapper.findById(node.getNodeInstId()).getStatus());
        Assert.assertTrue("否决路径没建 action 行", actionMapper.findInContainer(node.getNodeInstId(), null).isEmpty());
    }

    @Test
    public void retryProcessOnMissingProcessReturnsFalse() {
        Assert.assertFalse(facade.retry("ghost-proc", "op1"));
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

    /** 复位集是"本轮没跑成"的那些: drain 掉的 stopped 也允许定点重跑(旧口径只认 failed, 那些行会永远没人再跑)。 */
    @Test
    public void retryActionAlsoResetsStoppedAction() {
        ActionInstEntity a = failedAction("retryStopped");
        actionMapper.setStatus(a.getActionInstId(), Status.STOPPED.getStatus(), "op1");

        Assert.assertTrue(facade.retryAction(a.getActionInstId(), "op1"));
        Assert.assertEquals(Status.CREATED.getStatus(), actionMapper.findById(a.getActionInstId()).getStatus());
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
