package com.tt.ii.process.integration;

import com.tt.ii.process.api.ProcessExecution;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.domain.LaneEntity;
import com.tt.ii.process.domain.NodeInstEntity;
import com.tt.ii.process.util.Json;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 过程态让渡(yield)与重入端到端:
 *  - pending 让渡: 首轮退出(流程落 pending、节点停 running、setdown 不跑、后续根不跑),
 *    重捞后从断点续跑至 success(让渡行重跑、setup 已成功不重跑);
 *  - paused 让渡: 走现有 resume 重新入队后完成;
 *  - suspended 让渡: 落停驻态, 本期无唤醒入口(重试翻不动、再次 start 不重跑);
 *  - 监听器口径: actionAfter 照发(带让渡态), nodeAfter/processAfter 不发(未完成);
 *  - 让渡不触发 autoRollback(只认 FAILED/STOPPED);
 *  - 非法过程态(running)返回值: 当场按 FAILED 处理并写明 __error。
 *
 * 装配与替身见 {@link EngineE2ETestBase}(内存表 + Mockito, 不起容器)。
 */
public class EngineYieldTest extends EngineE2ETestBase {

    /** 数据同步故事的 YAML: setup=暂停复制, 主段=同步(可让渡), setdown=恢复复制。 */
    private static String yieldDef(String syncHandler) {
        return "flow: \"preSteps, dataSync, postSteps\"\n"
                + "nodes:\n"
                + "  preSteps:\n    actions:\n      - id: pre\n        handler: ok\n"
                + "  dataSync:\n"
                + "    setup:\n      - id: pauseReplication\n        handler: ok\n"
                + "    actions:\n      - id: doSync\n        handler: " + syncHandler + "\n"
                + "    setdown:\n      - id: resumeReplication\n        handler: ok\n"
                + "  postSteps:\n    actions:\n      - id: post\n        handler: ok\n";
    }

    // ===================== pending 让渡 -> 重捞续跑 =====================

    /** 首轮退出态: 流程 pending、节点 running、让渡行 pending、setdown 与后续根都不跑。 */
    @Test
    public void pendingYieldExitsThenReentrantDriveCompletes() {
        publish("yieldP", yieldDef("pendingOnce"));
        ProcessExecution r1 = run("yieldP");
        String pid = r1.getProcInstId();

        Assert.assertEquals("让渡态落流程行", Status.PENDING, r1.getStatus());
        Assert.assertFalse("非终态: 调用方拿不到成功假象", r1.isTerminal());
        Assert.assertEquals("节点停 running(不落终态)", Status.RUNNING.getStatus(),
                nodeByDef(pid, "dataSync").getStatus());
        Assert.assertEquals("让渡行原值落库", "pending", actionByDef(pid, "doSync").getStatus());
        Assert.assertEquals("后续根不跑", 0, Collections.frequency(rec.order, "handler:ok:post"));
        Assert.assertNull("setdown 不跑(收尾属于完成路径)", findAction(pid, "resumeReplication"));

        // 重入(pending 由调度重捞; 这里手动 start 同步触发): 终态行跳过、让渡行重跑
        ProcessExecution r2 = facade.start(pid);
        Assert.assertEquals(Status.SUCCESS, r2.getStatus());
        Assert.assertEquals("让渡行重跑成功", Status.SUCCESS.getStatus(), actionByDef(pid, "doSync").getStatus());
        Assert.assertEquals("重跑观测: visit_count=2", Integer.valueOf(2),
                actionByDef(pid, "doSync").getVisitCount());
        Assert.assertEquals("setup 已成功: 不重跑", 1,
                Collections.frequency(rec.order, "handler:ok:pauseReplication"));
        Assert.assertEquals("收尾段补跑", 1,
                Collections.frequency(rec.order, "handler:ok:resumeReplication"));
        Assert.assertEquals("后续根续跑", 1, Collections.frequency(rec.order, "handler:ok:post"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "dataSync").getStatus());
    }

    // ===================== paused 让渡 -> resume =====================

    /** paused 让渡: 流程落 paused, 走现有 resume 重新入队后完成。 */
    @Test
    public void pausedYieldResumesViaResumeEntry() {
        publish("yieldPa", "flow: \"a\"\nnodes:\n  a:\n    actions:\n      - id: work\n        handler: pausedOnce\n");
        ProcessExecution r1 = run("yieldPa");
        String pid = r1.getProcInstId();
        Assert.assertEquals(Status.PAUSED, r1.getStatus());
        Assert.assertEquals("paused", actionByDef(pid, "work").getStatus());

        Assert.assertTrue("resume 翻回可认领态", facade.resume(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());
        Assert.assertEquals(Status.SUCCESS.getStatus(), actionByDef(pid, "work").getStatus());
        Assert.assertEquals("让渡行重跑一次", 2, Collections.frequency(rec.order, "handler:pausedOnce:work"));
    }

    // ===================== suspended 让渡 -> 停驻(本期无唤醒入口) =====================

    /** suspended 让渡: 落停驻态; 重试翻不动, 再次 start 也不重跑(非 pending 不驱动)。 */
    @Test
    public void suspendedParksWithoutWakeEntry() {
        publish("yieldSu", "flow: \"a\"\nnodes:\n  a:\n    actions:\n      - id: hold\n        handler: suspended\n");
        ProcessExecution r1 = run("yieldSu");
        String pid = r1.getProcInstId();
        Assert.assertEquals(Status.SUSPENDED, r1.getStatus());
        Assert.assertEquals(Status.RUNNING.getStatus(), nodeByDef(pid, "a").getStatus());

        Assert.assertFalse("重试翻不动停驻流程", facade.retry(pid, "u1"));
        Assert.assertEquals("再次 start: 非 pending 不驱动, 回当下状态",
                Status.SUSPENDED, facade.start(pid).getStatus());
        Assert.assertEquals("停驻: 重入从未发生", 1, Collections.frequency(rec.order, "handler:suspended:hold"));
        Assert.assertEquals("suspended", actionByDef(pid, "hold").getStatus());
    }

    // ===================== 分片 lane 让渡: lane 级 result 不丢 =====================

    /**
     * 分片 lane 让渡: 本轮显式放进 lane 桶的交接值必须随让渡落库(lane 状态仍停 created, 不写 RUNNING);
     * 重入时上下文从行重建, 收尾后值仍在——不刷这一段就是"只在内存有效, 重驱动即丢"。
     */
    @Test
    public void shardedLaneResultSurvivesYield() {
        publish("yieldShard", "flow: \"syncAll\"\n"
                + "nodes:\n"
                + "  syncAll:\n"
                + "    actions:\n"
                + "      - id: write\n        handler: flagTrue\n"
                + "      - id: wait\n        handler: pendingOnce\n"
                + "    shard: dbTopology\n");
        ProcessExecution r1 = run("yieldShard");
        Assert.assertEquals(Status.PENDING, r1.getStatus());
        String pid = r1.getProcInstId();
        NodeInstEntity node = nodeByDef(pid, "syncAll");

        // 让渡轮: 首条 lane 停在 created(未完成不写 running), 但显式放置的交接值已落库
        LaneEntity yielded = laneByShard(node, "db-primary:3306");
        Assert.assertEquals("让渡: lane 行不落终态", Status.CREATED.getStatus(), yielded.getStatus());
        Assert.assertEquals("让渡轮的 lane 级 result 已落库", true, placedWrite(yielded));

        // 重入: 上下文从行重建, 交接值不得丢
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());
        LaneEntity done = laneByShard(node, "db-primary:3306");
        Assert.assertEquals(Status.SUCCESS.getStatus(), done.getStatus());
        Assert.assertEquals("重入后交接值仍在", true, placedWrite(done));
    }

    private LaneEntity laneByShard(NodeInstEntity node, String shardKey) {
        LaneEntity lane = laneMapper.findByNodeAndShard(node.getNodeInstId(), shardKey);
        Assert.assertNotNull("找不到 lane: " + shardKey, lane);
        return lane;
    }

    /** flagTrue 把 {ok:true} 挂到所属 lane 桶(键 = 动作 id "write")。 */
    private Object placedWrite(LaneEntity lane) {
        Map<String, Object> res = Json.readMap(lane.getResult());
        Object placed = res.get("write");
        Assert.assertNotNull("lane 行 result 未落库: " + lane.getShardKey(), placed);
        return ((Map<?, ?>) placed).get("ok");
    }

    // ===================== 监听器口径与自动回退 =====================

    /** 让渡轮: actionAfter 照发(带让渡态), nodeAfter/processAfter 不发(节点/流程未完成)。 */
    @Test
    public void yieldFiresActionAfterOnly() {
        publish("yieldLst", ""
                + "flow: \"a\"\n"
                + "listeners:\n  - name: pRecord\n"
                + "nodes:\n"
                + "  a:\n"
                + "    listeners:\n      - name: nRecord\n"
                + "    actions:\n"
                + "      - id: work\n        handler: pendingOnce\n"
                + "        listeners:\n          - name: aRecord\n");
        ProcessExecution r1 = run("yieldLst");
        Assert.assertEquals(Status.PENDING, r1.getStatus());
        Assert.assertEquals("actionAfter 照发(带让渡态)", 1,
                Collections.frequency(rec.order, "actionAfter:pending"));
        Assert.assertEquals("nodeAfter 不发", 0, rec.nodeAfter.get());
        Assert.assertEquals("processAfter 不发", 0, rec.processAfter.get());
    }

    /** 让渡不触发自动回退(只认 FAILED/STOPPED): autoRollback 定义 + 让渡 -> 无任何补偿记录。 */
    @Test
    public void yieldDoesNotTriggerAutoRollback() {
        publish("yieldRb", "autoRollback: true\nflow: \"a\"\nnodes:\n"
                + "  a:\n    actions:\n      - id: work\n        handler: pendingOnce\n");
        ProcessExecution r1 = run("yieldRb");
        Assert.assertEquals(Status.PENDING, r1.getStatus());
        Assert.assertTrue("无补偿记录", rollbackMapper.findByProcess(r1.getProcInstId()).isEmpty());
        Assert.assertNull("无失败摘要残留", r1.getError());
    }

    // ===================== 非法过程态 =====================

    /** 非法过程态(running): 当场按 FAILED 处理, __error 写明原因——不得静默落库被聚合吞成成功。 */
    @Test
    public void illegalRunningReturnBecomesFailed() {
        publish("yieldBad", "flow: \"a\"\nnodes:\n"
                + "  a:\n    actions:\n      - id: work\n        handler: runningReturn\n");
        ProcessExecution r = run("yieldBad");
        String pid = r.getProcInstId();

        Assert.assertEquals(Status.FAILED, r.getStatus());
        ActionInstEntity work = actionByDef(pid, "work");
        Assert.assertEquals("failed", work.getStatus());
        Assert.assertTrue("action 行 __error 明示非法返回值", work.getResult().contains("非法过程态"));
        Assert.assertTrue("流程行/门面也能看到那条因由", r.getError().contains("非法过程态"));
        Assert.assertEquals(Status.FAILED.getStatus(), nodeByDef(pid, "a").getStatus());
    }

    // ===================== 辅助 =====================

    private ActionInstEntity actionByDef(String pid, String defId) {
        ActionInstEntity a = findAction(pid, defId);
        Assert.assertNotNull("找不到 action: " + defId, a);
        return a;
    }

    private ActionInstEntity findAction(String pid, String defId) {
        for (NodeInstEntity n : allNodes(pid)) {
            for (ActionInstEntity a : actionMapper.find(n.getNodeInstId(), null)) {
                if (defId.equals(a.getActionDefId())) {
                    return a;
                }
            }
        }
        return null;
    }

    private NodeInstEntity nodeByDef(String pid, String defId) {
        for (NodeInstEntity n : allNodes(pid)) {
            if (defId.equals(n.getNodeDefId())) {
                return n;
            }
        }
        Assert.fail("找不到节点: " + defId);
        return null;
    }

    private List<NodeInstEntity> allNodes(String pid) {
        List<NodeInstEntity> out = new ArrayList<>();
        for (NodeInstEntity root : nodeMapper.findRoots(pid)) {
            collect(root, out, pid);
        }
        return out;
    }

    private void collect(NodeInstEntity n, List<NodeInstEntity> acc, String pid) {
        acc.add(n);
        for (NodeInstEntity c : nodeMapper.findChildren(pid, n.getNodeInstId())) {
            collect(c, acc, pid);
        }
    }
}
