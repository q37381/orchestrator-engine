package com.tt.ii.process.integration;

import com.tt.ii.process.api.ProcessExecution;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.domain.NodeInstEntity;
import com.tt.ii.process.domain.ProcessInstEntity;
import com.tt.ii.process.util.Json;
import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 停止先收尾(settleOnStop)端到端: 等待态(让渡 PENDING / PAUSED)上的停止走**两阶段**——
 * stop 只落 {@code __stopRequested} 标识(状态不动), 下一次调度进入**收尾轮**: 主段被闸门拦下并 drain、
 * setup 不重跑、节点 setdown 与流程级 setdown 按 finally 补跑, 之后才落 STOPPED 并清标识。
 *
 * 未声明 settleOnStop 的定义保持**硬停**: 停止即落 STOPPED, 不等收尾(等待态上的 setup 现场不再补收)。
 */
public class EngineSettleOnStopTest extends EngineE2ETestBase {

    /** 迁移节点: setup 施加现场 -> 主段让渡等待 -> setdown 恢复现场; 流程级 setdown 节点做最后清理。 */
    private static String settleFlow() {
        return "settleOnStop: true\n"
                + "flow: \"m\"\n"
                + "setdown: [\"cleanup\"]\n"
                + "nodes:\n"
                + "  m:\n"
                + "    setup:\n"
                + "      - id: lockParams\n"
                + "        handler: ok\n"
                + "    actions:\n"
                + "      - id: wait\n"
                + "        handler: pendingOnce\n"
                + "    setdown:\n"
                + "      - id: unlockParams\n"
                + "        handler: ok\n"
                + "  cleanup:\n"
                + "    handler: ok\n";
    }

    /** 同形状定义但不声明 settleOnStop(默认硬停)。 */
    private static String hardStopFlow() {
        return "flow: \"m\"\n"
                + "nodes:\n"
                + "  m:\n"
                + "    setup:\n"
                + "      - id: lockParams\n"
                + "        handler: ok\n"
                + "    actions:\n"
                + "      - id: wait\n"
                + "        handler: pendingOnce\n"
                + "    setdown:\n"
                + "      - id: unlockParams\n"
                + "        handler: ok\n";
    }

    /** 节点内 action 行按 action_def_id 索引(非分片: laneId=null)。 */
    private Map<String, ActionInstEntity> actionsByDef(String nodeInstId) {
        Map<String, ActionInstEntity> out = new LinkedHashMap<>();
        for (ActionInstEntity a : actionMapper.findInContainer(nodeInstId, null)) {
            out.put(a.getActionDefId(), a);
        }
        return out;
    }

    private static boolean stopRequested(ProcessInstEntity p) {
        return Boolean.TRUE.equals(Json.readMap(p.getConfig()).get("__stopRequested"));
    }

    // ====================================================================== 两阶段停止(settleOnStop)

    /**
     * 核心场景: setup 已落库、主段让渡等待中停止——stop 只落标识(状态仍 PENDING),
     * 下一次调度直接进收尾轮: 主段 drain、setdown 补跑、流程落 STOPPED、标识清除。
     */
    @Test
    public void stopWhileWaitingSettlesSetdownOnNextDispatch() {
        publish("settleA", settleFlow());
        String pid = facade.createAndStart("settleA", 1, null, "u1").getProcInstId();
        Assert.assertEquals("主段让渡: 流程等待(PENDING), 节点停 running",
                Status.PENDING.getStatus(), processMapper.findById(pid).getStatus());

        // 阶段1: 停止 = 只落标识, 状态不动(等收尾轮)
        Assert.assertTrue(facade.stop(pid, "op1"));
        ProcessInstEntity flagged = processMapper.findById(pid);
        Assert.assertEquals("两阶段停止: 状态等收尾轮再落",
                Status.PENDING.getStatus(), flagged.getStatus());
        Assert.assertTrue("停止标识已落库", stopRequested(flagged));

        // 阶段2: 下一次调度(Dispatcher 认领的同一入口) -> 收尾轮
        ProcessExecution r = facade.start(pid);
        Assert.assertEquals(Status.STOPPED, r.getStatus());
        Assert.assertEquals(Status.STOPPED.getStatus(), processMapper.findById(pid).getStatus());
        Assert.assertFalse("收尾完成后标识清除", stopRequested(processMapper.findById(pid)));

        NodeInstEntity node = nodeMapper.findRoots(pid).get(0);
        Assert.assertEquals("节点收尾落 STOPPED",
                Status.STOPPED.getStatus(), nodeMapper.findById(node.getNodeInstId()).getStatus());
        Map<String, ActionInstEntity> acts = actionsByDef(node.getNodeInstId());
        Assert.assertEquals("setup 现场保留(不重跑不重臂)",
                Status.SUCCESS.getStatus(), acts.get("lockParams").getStatus());
        Assert.assertEquals("主段被闸门拦下并 drain",
                Status.STOPPED.getStatus(), acts.get("wait").getStatus());
        Assert.assertEquals("DRAIN 留痕",
                "DRAIN", Json.readMap(acts.get("wait").getResult()).get("__skipReason"));
        Assert.assertEquals("让渡行没有被重跑",
                Integer.valueOf(1), Json.readMap(acts.get("wait").getResult()).get("attempts"));
        Assert.assertEquals("节点 setdown 按 finally 补跑",
                Status.SUCCESS.getStatus(), acts.get("unlockParams").getStatus());
        Assert.assertTrue("流程级 setdown 节点同样完整执行(清理动作不被闸门拦下)",
                rec.order.contains("handler:ok:cleanup"));
    }

    /** 默认(未声明 settleOnStop) = 硬停: 停止即落 STOPPED, 不跑收尾轮——等待态上的 setup 现场保持原样。 */
    @Test
    public void hardStopByDefaultStopsImmediatelyWithoutSettle() {
        publish("settleB", hardStopFlow());
        String pid = facade.createAndStart("settleB", 1, null, "u1").getProcInstId();
        Assert.assertEquals(Status.PENDING.getStatus(), processMapper.findById(pid).getStatus());

        Assert.assertTrue("硬停: PENDING 也能直接停(旧口径翻不动)", facade.stop(pid, "op1"));
        Assert.assertEquals("默认: 停止即落 STOPPED",
                Status.STOPPED.getStatus(), processMapper.findById(pid).getStatus());

        NodeInstEntity node = nodeMapper.findRoots(pid).get(0);
        Map<String, ActionInstEntity> acts = actionsByDef(node.getNodeInstId());
        Assert.assertFalse("不做收尾: setdown 行根本不存在", acts.containsKey("unlockParams"));
        Assert.assertEquals("等待态硬停: 节点行没有 owner 收尾, 保持 running(既有口径)",
                Status.RUNNING.getStatus(), nodeMapper.findById(node.getNodeInstId()).getStatus());
        Assert.assertEquals("setup 现场保留(硬停不补收)",
                Status.SUCCESS.getStatus(), acts.get("lockParams").getStatus());
    }

    /** 让渡到 PAUSED 的等待态: stop 落标识 + 翻回 PENDING 交给调度, 收尾轮补跑 setdown。 */
    @Test
    public void stopWhilePausedYieldRequeuesForSettle() {
        publish("settleC", "settleOnStop: true\n"
                + "flow: \"m\"\n"
                + "nodes:\n"
                + "  m:\n"
                + "    setup:\n"
                + "      - id: lockParams\n"
                + "        handler: ok\n"
                + "    actions:\n"
                + "      - id: wait\n"
                + "        handler: pausedOnce\n"
                + "    setdown:\n"
                + "      - id: unlockParams\n"
                + "        handler: ok\n");
        String pid = facade.createAndStart("settleC", 1, null, "u1").getProcInstId();
        Assert.assertEquals(Status.PAUSED.getStatus(), processMapper.findById(pid).getStatus());

        Assert.assertTrue(facade.stop(pid, "op1"));
        ProcessInstEntity flagged = processMapper.findById(pid);
        Assert.assertEquals("落标识并翻回 PENDING 等调度",
                Status.PENDING.getStatus(), flagged.getStatus());
        Assert.assertTrue(stopRequested(flagged));

        Assert.assertEquals(Status.STOPPED, facade.start(pid).getStatus());
        Map<String, ActionInstEntity> acts = actionsByDef(nodeMapper.findRoots(pid).get(0).getNodeInstId());
        Assert.assertEquals("setdown 补跑成功",
                Status.SUCCESS.getStatus(), acts.get("unlockParams").getStatus());
        Assert.assertEquals("让渡行保留执行事实(paused; 重试时非终态行会重跑)",
                Status.PAUSED.getStatus(), acts.get("wait").getStatus());
    }

    /** 收尾失败 ≠ 停止成功: setdown 自己坏了就落 FAILED(让运维看得到), 不粉饰成 STOPPED。 */
    @Test
    public void settleFailureLeavesFailedNotStopped() {
        publish("settleD", "settleOnStop: true\n"
                + "flow: \"m\"\n"
                + "nodes:\n"
                + "  m:\n"
                + "    actions:\n"
                + "      - id: wait\n"
                + "        handler: pendingOnce\n"
                + "    setdown:\n"
                + "      - id: unlockParams\n"
                + "        handler: thrower\n");
        String pid = facade.createAndStart("settleD", 1, null, "u1").getProcInstId();
        Assert.assertEquals(Status.PENDING.getStatus(), processMapper.findById(pid).getStatus());

        Assert.assertTrue(facade.stop(pid, "op1"));
        Assert.assertEquals("收尾轮失败: 流程落 FAILED",
                Status.FAILED, facade.start(pid).getStatus());
        Assert.assertEquals(Status.FAILED.getStatus(), processMapper.findById(pid).getStatus());
        Assert.assertFalse("失败也把标识消化掉了", stopRequested(processMapper.findById(pid)));
    }

    /** 停完再重试: 重试把"本轮没跑成"的行复位并重驱动——setup 与 setdown 成对重臂, 主段续跑成功。 */
    @Test
    public void retryAfterSettleStopResumesWork() {
        publish("settleE", settleFlow());
        String pid = facade.createAndStart("settleE", 1, null, "u1").getProcInstId();
        Assert.assertTrue(facade.stop(pid, "op1"));                  // 停请求已落(等待态)
        Assert.assertEquals(Status.STOPPED, facade.start(pid).getStatus());   // 收尾轮停机

        Assert.assertTrue(facade.retry(pid, "op1"));                 // 改主意: 复位待跑行重新入队
        ProcessExecution r = facade.start(pid);
        Assert.assertEquals("重入后续跑成功", Status.SUCCESS, r.getStatus());

        Map<String, ActionInstEntity> acts = actionsByDef(nodeMapper.findRoots(pid).get(0).getNodeInstId());
        Assert.assertEquals("主段第二次让渡后成功",
                Status.SUCCESS.getStatus(), acts.get("wait").getStatus());
        Assert.assertEquals("setup 与 setdown 成对重臂后重跑成功",
                Status.SUCCESS.getStatus(), acts.get("unlockParams").getStatus());
        Assert.assertEquals("setup 也重新施加了一次(成对重臂)",
                Status.SUCCESS.getStatus(), acts.get("lockParams").getStatus());
    }
}
