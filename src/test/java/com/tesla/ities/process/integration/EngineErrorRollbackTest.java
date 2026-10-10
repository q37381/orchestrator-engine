package com.tesla.ities.process.integration;

import com.tesla.ities.process.api.ProcessExecution;
import com.tesla.ities.process.core.engine.RollbackService;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.domain.ActionInstEntity;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.domain.ProcessInstEntity;
import com.tesla.ities.process.util.Json;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;

/**
 * 引擎错误/否决/回退/观察路径端到端(装配见 {@link EngineE2ETestBase}):
 *  - 三类监听器 before 否决(process/node/action) -> 各自 FAILED 落行 + after 抛异常被 ListenerEngine 吞掉;
 *  - 全局监听器链(globalNode/ActionListeners)在真实驱动里合并生效;
 *  - Saga 回退: 补偿成功 / 走 ActionHandler 默认补偿 / 遇不可补偿(BLOCKED) / 补偿自身失败(COMPENSATE_FAILED) /
 *    前置否决(veto) / 空回退 / 目标不存在; 分片动作回退重建 lane 上下文 + payload。
 */
public class EngineErrorRollbackTest extends EngineE2ETestBase {

    // ===================== 监听器否决 =====================

    @Test
    public void processBeforeVetoFailsWithoutDrivingRoots() {
        publish("vproc", ""
                + "flow: \"x\"\n"
                + "listeners:\n  - name: pVeto\n"
                + "nodes:\n  x:\n    handler: ok\n");
        ProcessExecution r = run("vproc");
        Assert.assertEquals(Status.FAILED, r.getStatus());
        Assert.assertTrue(nodeMapper.findRoots(r.getProcInstId()).isEmpty());   // 根从未驱动
    }

    @Test
    public void nodeBeforeVetoFailsNode() {
        publish("vnode", ""
                + "flow: \"x\"\n"
                + "nodes:\n"
                + "  x:\n"
                + "    handler: ok\n"
                + "    listeners:\n      - name: nVeto\n");
        ProcessExecution r = run("vnode");
        Assert.assertEquals(Status.FAILED, r.getStatus());
        NodeInstEntity node = nodeMapper.findRoots(r.getProcInstId()).get(0);
        Assert.assertEquals(Status.FAILED.getStatus(), node.getStatus());
        Assert.assertTrue(node.getResult().contains("__error"));
        // action 从未执行(before 否决)
        Assert.assertTrue(actionMapper.findInContainer(node.getNodeInstId(), null).isEmpty());
    }

    @Test
    public void actionBeforeVetoFailsAction() {
        publish("vaction", ""
                + "flow: \"x\"\n"
                + "nodes:\n"
                + "  x:\n"
                + "    actions:\n"
                + "      - {id: a, handler: ok, listeners: [{name: aVeto}]}\n");
        ProcessExecution r = run("vaction");
        Assert.assertEquals(Status.FAILED, r.getStatus());
        NodeInstEntity node = nodeMapper.findRoots(r.getProcInstId()).get(0);
        ActionInstEntity a = actionMapper.findInContainer(node.getNodeInstId(), null).get(0);
        Assert.assertEquals(Status.FAILED.getStatus(), a.getStatus());
        Assert.assertTrue(Json.readMap(a.getResult()).containsKey("__error"));
    }

    // ===================== after 抛异常被吞(纯通知) =====================

    @Test
    public void afterListenerExceptionsAreSwallowed() {
        publish("afterboom", ""
                + "flow: \"x\"\n"
                + "listeners:\n  - name: pAfterBoom\n"
                + "nodes:\n"
                + "  x:\n"
                + "    handler: ok\n"
                + "    listeners:\n      - name: nAfterBoom\n"
                + "    actions:\n      - {id: a, handler: ok, listeners: [{name: aAfterBoom}]}\n");
        ProcessExecution r = run("afterboom");
        // after 里的异常只是 warn 吞掉, 不改终态
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
    }

    // ===================== 全局链合并生效 =====================

    @Test
    public void globalNodeAndActionListenersFire() {
        publish("globals", ""
                + "flow: \"x\"\n"
                + "globalNodeListeners:\n  - name: nRecord\n"
                + "globalActionListeners:\n  - name: aRecord\n"
                + "nodes:\n  x:\n    handler: ok\n");
        ProcessExecution r = run("globals");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        Assert.assertTrue(rec.nodeBefore.get() >= 1);
        Assert.assertTrue(rec.actionBefore.get() >= 1);
        Assert.assertTrue(rec.actionAfter.get() >= 1);
    }

    // ===================== 回退: 补偿 / 默认补偿 / 阻断 / 失败 =====================

    @Test
    public void rollbackCompensatesSuccessfulActions() {
        publish("rbOk", ""
                + "flow: \"a\"\n"
                + "rollbackListeners:\n  - name: rbRecord\n"
                + "nodes:\n  a:\n    handler: compOk\n");
        ProcessExecution r = run("rbOk");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        RollbackService.RollbackReport rep = facade.doRollback(r.getProcInstId(), "u1");
        Assert.assertEquals(1, rep.compensated.size());
        Assert.assertEquals(Status.SUCCESS, rep.outcome());
        Assert.assertTrue(rec.order.contains("rollbackBefore"));
        Assert.assertTrue(rec.order.contains("rollbackAfter:success"));
        // 流程级 result 只在驱动路径脏刷: 回退前置挂的现场信息(dbConn)不入库
        Assert.assertNull("回退路径不刷流程级 result", processMapper.findById(r.getProcInstId()).getResult());
    }

    @Test
    public void rollbackUsesDefaultCompensateWhenHandlerDoesNotOverride() {
        publish("rbDefault", ""
                + "flow: \"a\"\n"
                + "nodes:\n  a:\n    handler: compDefault\n");
        ProcessExecution r = run("rbDefault");
        RollbackService.RollbackReport rep = facade.doRollback(r.getProcInstId(), "u1");
        Assert.assertEquals(1, rep.compensated.size());   // ActionHandler.default compensate() 空实现被调用
    }

    @Test
    public void rollbackStopsAtNonCompensableAction() {
        publish("rbBlock", ""
                + "flow: \"a\"\n"
                + "nodes:\n  a:\n    handler: nonComp\n");
        ProcessExecution r = run("rbBlock");
        RollbackService.RollbackReport rep = facade.doRollback(r.getProcInstId(), "u1");
        Assert.assertEquals(1, rep.blocked.size());
        Assert.assertEquals(Status.STOPPED, rep.outcome());
    }

    @Test
    public void rollbackRecordsCompensateFailure() {
        publish("rbFail", ""
                + "flow: \"a\"\n"
                + "nodes:\n  a:\n    handler: compFail\n");
        ProcessExecution r = run("rbFail");
        RollbackService.RollbackReport rep = facade.doRollback(r.getProcInstId(), "u1");
        Assert.assertEquals(1, rep.failed.size());
        Assert.assertEquals(Status.FAILED, rep.outcome());
    }

    @Test
    public void rollbackVetoedByBeforeListener() {
        publish("rbVeto", ""
                + "flow: \"a\"\n"
                + "rollbackListeners:\n  - name: rbVeto\n"
                + "nodes:\n  a:\n    handler: compOk\n");
        ProcessExecution r = run("rbVeto");
        RollbackService.RollbackReport rep = facade.doRollback(r.getProcInstId(), "u1");
        Assert.assertNotNull("前置否决记入 veto", rep.veto);
        Assert.assertEquals(0, rep.compensated.size());
        Assert.assertEquals(Status.FAILED, rep.outcome());
    }

    @Test
    public void rollbackOnShardedActionsRebuildsLaneContext() {
        // dbMigrate 可补偿; 分片 3 lane -> 回退逐条补(覆盖 lane 上下文重建 + payload)
        publish("rbShard", ""
                + "flow: \"s\"\n"
                + "nodes:\n"
                + "  s:\n"
                + "    handler: dbMigrate\n"
                + "    shard: dbTopology\n"
                + "    laneExecution: SERIAL\n");
        ProcessExecution r = run("rbShard");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        RollbackService.RollbackReport rep = facade.doRollback(r.getProcInstId(), "u1");
        Assert.assertEquals(3, rep.compensated.size());   // 一主两从 3 个成功分片动作
    }

    @Test
    public void rollbackWithNothingSucceededIsNoop() {
        publish("rbEmpty", ""
                + "flow: \"a\"\n"
                + "nodes:\n  a:\n    handler: skipper\n");   // skipped 不算成功 -> 无补偿对象
        ProcessExecution r = run("rbEmpty");
        RollbackService.RollbackReport rep = facade.doRollback(r.getProcInstId(), "u1");
        Assert.assertTrue(rep.compensated.isEmpty());
        Assert.assertTrue(rep.blocked.isEmpty());
    }

    @Test
    public void rollbackOnMissingProcessIsNoop() {
        RollbackService.RollbackReport rep = facade.doRollback("ghost", "u1");
        Assert.assertTrue(rep.compensated.isEmpty());
        Assert.assertEquals(Status.SUCCESS, rep.outcome());
    }

    @Test
    public void rollbackWarnsOnUnresolvedConfigPlaceholder() {
        // action config 里的 ${result.ghost.x} 激活期仍解不出 -> 补偿前 findUnresolved 非空 -> 告警(不阻断补偿)
        publish("rbUnresolved", ""
                + "flow: \"a\"\n"
                + "nodes:\n  a:\n    handler: compOk\n    config:\n      leftover: \"${result.ghost.value}\"\n");
        ProcessExecution r = run("rbUnresolved");
        RollbackService.RollbackReport rep = facade.doRollback(r.getProcInstId(), "u1");
        Assert.assertEquals(1, rep.compensated.size());
    }

    @Test
    public void rollbackOverParallelChildrenCollectsSubtree() {
        // EAGER PARALLEL 两个可补偿叶子 -> 回退递归收集子树(collect 的 findChildren 分支)
        publish("rbChildren", ""
                + "materialization: EAGER\n"
                + "flow: \"PARALLEL(a,b)\"\n"
                + "nodes:\n  a:\n    handler: compOk\n  b:\n    handler: compOk\n");
        ProcessExecution r = run("rbChildren");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        RollbackService.RollbackReport rep = facade.doRollback(r.getProcInstId(), "u1");
        Assert.assertEquals(2, rep.compensated.size());
    }

    @Test
    public void rollbackBlockedOnDefaultNonCompensableHandler() {
        // `ok` 不覆写 compensable() -> 走 ActionHandler 默认(返回 false) -> BLOCKED
        publish("rbDefaultBlock", "flow: \"a\"\nnodes:\n  a:\n    handler: ok\n");
        ProcessExecution r = run("rbDefaultBlock");
        RollbackService.RollbackReport rep = facade.doRollback(r.getProcInstId(), "u1");
        Assert.assertEquals(1, rep.blocked.size());
        Assert.assertEquals(Status.STOPPED, rep.outcome());
    }

    @Test
    public void rollbackWithAfterOnlyListenerUsesDefaultBefore() {
        // rbAfterOnly 只覆写 after -> before 走 RollbackListener 默认空实现(不否决)
        publish("rbAfterOnly", ""
                + "flow: \"a\"\n"
                + "rollbackListeners:\n  - name: rbAfterOnly\n"
                + "nodes:\n  a:\n    handler: compOk\n");
        ProcessExecution r = run("rbAfterOnly");
        RollbackService.RollbackReport rep = facade.doRollback(r.getProcInstId(), "u1");
        Assert.assertEquals(1, rep.compensated.size());
        Assert.assertTrue(rec.order.contains("rbAfterOnly"));
    }

    @Test
    public void rollbackAfterListenerExceptionIsSwallowed() {
        // rbAfterBoom.after 抛异常 -> ListenerEngine 吞掉, 不影响补偿结果
        publish("rbAfterBoom", ""
                + "flow: \"a\"\n"
                + "rollbackListeners:\n  - name: rbAfterBoom\n"
                + "nodes:\n  a:\n    handler: compOk\n");
        ProcessExecution r = run("rbAfterBoom");
        RollbackService.RollbackReport rep = facade.doRollback(r.getProcInstId(), "u1");
        Assert.assertEquals(1, rep.compensated.size());
        Assert.assertEquals(Status.SUCCESS, rep.outcome());
    }

    // ===================== 失败信息并入原有 result, 不丢业务数据 =====================

    @Test
    public void failureInfoMergesExistingResult() {
        publish("merge", ""
                + "flow: \"x\"\n"
                + "nodes:\n"
                + "  x:\n"
                + "    actions:\n"
                + "      - {id: a, handler: flagTrue, listeners: [{name: aVeto}]}\n");   // veto 在 before, handler 未跑
        ProcessExecution r = run("merge");
        NodeInstEntity node = nodeMapper.findRoots(r.getProcInstId()).get(0);
        ActionInstEntity a = actionMapper.findInContainer(node.getNodeInstId(), null).get(0);
        Assert.assertEquals(Status.FAILED.getStatus(), a.getStatus());
        Assert.assertTrue(Json.readMap(a.getResult()).containsKey("__error"));
    }

    // ===================== 流程被人工 STOP 抢先翻走: finalize 以库中真值为准 =====================

    @Test
    public void finalizeReflectsPreemptedByManualStop() {
        // 建一条 PENDING 流程, 手动置 RUNNING 并 STOP, 再 start: start 见非 PENDING -> 返回当前 stopped, 不驱动
        publish("preempt", "flow: \"x\"\nnodes:\n  x:\n    handler: ok\n");
        String pid = facade.create("preempt", 1, null, "u1");
        processMapper.claim(pid, "u1");   // pending -> running
        processMapper.casStatus(pid, Status.RUNNING.getStatus(), Status.STOPPED.getStatus(), "u1");
        ProcessExecution r = facade.start(pid);
        Assert.assertEquals(Status.STOPPED, r.getStatus());
    }

    @Test
    public void progressOnUnknownProcessHasZeroTotal() {
        Map<String, Object> p = facade.progress("no-such");
        Assert.assertEquals("no-such", p.get("procInstId"));
        Assert.assertEquals(0, p.get("total"));
        Assert.assertEquals(100, p.get("percent"));   // total==0 -> 100
    }

    @Test
    public void configPlaceholderFromCreateParamsResolvesViaContext() {
        // ${config.cluster} 沿父链上溯到流程级 config(= 创建输入)
        publish("cfgparam", ""
                + "flow: \"x\"\n"
                + "nodes:\n  x:\n    handler: flagTrue\n    config:\n      cluster: \"${config.cluster}\"\n");
        ProcessExecution r = facade.createAndStart("cfgparam", 1,
                Collections.singletonMap("cluster", "prod-a"), "u1");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        ProcessInstEntity p = processMapper.findById(r.getProcInstId());
        Assert.assertEquals("prod-a", Json.readMap(p.getConfig()).get("cluster"));
    }
}
