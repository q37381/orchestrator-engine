package com.tt.ii.process.integration;

import com.tt.ii.process.api.ProcessExecution;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.domain.NodeInstEntity;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * action 级段(v1.2)端到端: 主段里"声明了级段的动作"(动作同时挂 setup/setdown)走单项电路——
 * 级 setup 段 -> 主体 -> 级 setdown 段(finally), 收尾不必等主段全部完成(中段还原):
 *  - 顺序: 配置 -> 暖身 -> **立即**恢复 -> 窗外动作照常;
 *  - finally: 主体失败 / 级 setup 段坏, 级 setdown 段照跑; 级 setup 段坏时主体 drain 留痕;
 *  - 补跑: 崩在"主体成、级 setdown 未跑" -> 重驱动定义驱动入口只补级 setdown 段;
 *  - 级段重臂: 主体/级 setup 失败 + 级 setdown 已成 + retry -> 成对复位重跑(§5.6.1 判据按级段收窄);
 *  - 与节点级段共存; 分片每 lane 独立成段电路。
 *
 * 装配与替身见 {@link EngineE2ETestBase}(内存表 + Mockito, 不起容器)。
 */
public class EngineActionSegmentTest extends EngineE2ETestBase {

    /** 数据同步故事: warmUp 声明级段(配置 -> 暖身 -> 恢复), pollStatus 在段外, after 是后续根。 */
    private static String segDef(String warmHandler, String elevateHandler, String restoreHandler) {
        return "flow: \"dataSync, after\"\n"
                + "nodes:\n"
                + "  dataSync:\n"
                + "    actions:\n"
                + "      - id: warmUp\n"
                + "        handler: " + warmHandler + "\n"
                + "        setup:\n"
                + "          - id: elevateW\n"
                + "            handler: " + elevateHandler + "\n"
                + "        setdown:\n"
                + "          - id: restoreW\n"
                + "            handler: " + restoreHandler + "\n"
                + "      - id: pollStatus\n"
                + "        handler: ok\n"
                + "  after:\n"
                + "    actions:\n"
                + "      - id: afterStep\n"
                + "        handler: ok\n";
    }

    // ===================== 中段还原 / 落库 =====================

    /** 顺序: 配置 -> 暖身 -> 立即恢复 -> 段外动作照常; 级段行 segment 落库正确。 */
    @Test
    public void actionSegmentRestoresMidMainBeforeLaterActions() {
        publish("asegMid", segDef("ok", "ok", "ok"));
        ProcessExecution r = run("asegMid");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        String pid = r.getProcInstId();

        int elevate = rec.order.indexOf("handler:ok:elevateW");
        int warm = rec.order.indexOf("handler:ok:warmUp");
        int restore = rec.order.indexOf("handler:ok:restoreW");
        int poll = rec.order.indexOf("handler:ok:pollStatus");
        Assert.assertTrue("配置 -> 暖身 -> 恢复 -> 段外动作",
                elevate >= 0 && elevate < warm && warm < restore && restore < poll);

        Assert.assertEquals("action-setup", actionByDef(pid, "elevateW").getSegment());
        Assert.assertEquals("action-setdown", actionByDef(pid, "restoreW").getSegment());
        Assert.assertEquals("main", actionByDef(pid, "warmUp").getSegment());
    }

    // ===================== finally: 主体失败收尾照跑 =====================

    /** 主体挂 -> 级 setdown 段照跑(finally), 段外后续不跑(FAIL_FAST), 流程 failed。 */
    @Test
    public void actionSetdownRunsWhenSubjectFails() {
        publish("asegFail", segDef("thrower", "ok", "ok"));
        ProcessExecution r = run("asegFail");
        Assert.assertEquals(Status.FAILED, r.getStatus());
        Assert.assertTrue("级 setup 跑了", rec.order.contains("handler:ok:elevateW"));
        Assert.assertTrue("主体挂了级 setdown 照跑(finally)", rec.order.contains("handler:ok:restoreW"));
        Assert.assertFalse("FAIL_FAST: 段外后续不跑", rec.order.contains("handler:ok:pollStatus"));
    }

    // ===================== 级 setup 失败 + 级段重臂 =====================

    /** 级 setup 首挂: 主体跳过、级 setdown 照跑; retry 后级段重臂(级 setup 重施 -> 主体续跑 -> 级 setdown 重收)。 */
    @Test
    public void actionSetupFailureRetriesIntoPairedReArm() {
        publish("asegSetupFlaky", segDef("ok", "flaky", "ok"));
        ProcessExecution r1 = run("asegSetupFlaky");
        Assert.assertEquals(Status.FAILED, r1.getStatus());
        String pid = r1.getProcInstId();
        Assert.assertFalse("级 setup 首挂: 主体未跑", rec.order.contains("handler:ok:warmUp"));
        Assert.assertTrue("级 setdown 仍照跑", rec.order.contains("handler:ok:restoreW"));

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("级 setup 重跑成", 2, Collections.frequency(rec.order, "handler:flaky:elevateW"));
        Assert.assertEquals("主体首次执行", 1, Collections.frequency(rec.order, "handler:ok:warmUp"));
        Assert.assertEquals("级 setdown 重新收尾", 2, Collections.frequency(rec.order, "handler:ok:restoreW"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "dataSync").getStatus());
    }

    /** 主体首挂(级 setdown finally 已跑): retry 成对重臂——级 setup 重施 -> 主体续跑 -> 级 setdown 重收。 */
    @Test
    public void actionSubjectRetryRearmsPairedRows() {
        publish("asegSubjFlaky", segDef("flaky", "ok", "ok"));
        ProcessExecution r1 = run("asegSubjFlaky");
        Assert.assertEquals(Status.FAILED, r1.getStatus());
        Assert.assertTrue("级 setdown finally 已跑", rec.order.contains("handler:ok:restoreW"));
        String pid = r1.getProcInstId();

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("级 setup 重施", 2, Collections.frequency(rec.order, "handler:ok:elevateW"));
        Assert.assertEquals("主体续跑成", 2, Collections.frequency(rec.order, "handler:flaky:warmUp"));
        Assert.assertEquals("级 setdown 重收", 2, Collections.frequency(rec.order, "handler:ok:restoreW"));
        Assert.assertEquals(1, Collections.frequency(rec.order, "handler:ok:pollStatus"));
    }

    // ===================== 崩溃补跑(定义驱动入口) =====================

    /** 崩在"主体成、级 setdown 未跑": 重驱动只补级 setdown 段, 级 setup/主体不重跑。 */
    @Test
    public void actionSegmentCrashBeforeSetdownOnlyCompletesSetdown() {
        publish("asegCrash", segDef("ok", "ok", "ok"));
        ProcessExecution r1 = run("asegCrash");
        Assert.assertEquals(Status.SUCCESS, r1.getStatus());
        String pid = r1.getProcInstId();

        // 倒回"主体成、级 setdown 未跑"(模拟 owner 崩溃残留: 级 setdown 行 created + 节点/流程 running)
        NodeInstEntity sync = nodeByDef(pid, "dataSync");
        ActionInstEntity restore = actionByDef(pid, "restoreW");
        actionMapper.updateState(restore.getActionInstId(), null, Status.CREATED.getStatus(), null, "u1");
        nodeMapper.setStatus(sync.getNodeInstId(), Status.RUNNING.getStatus(), "u1");
        processMapper.casStatus(pid, Status.SUCCESS.getStatus(), Status.RUNNING.getStatus(), "u1");

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("级 setup 已成功: 不重跑", 1, Collections.frequency(rec.order, "handler:ok:elevateW"));
        Assert.assertEquals("主体已成功: 不重跑", 1, Collections.frequency(rec.order, "handler:ok:warmUp"));
        Assert.assertEquals("定义驱动入口只补级 setdown", 2, Collections.frequency(rec.order, "handler:ok:restoreW"));
        Assert.assertEquals("段外已成功: 不重跑", 1, Collections.frequency(rec.order, "handler:ok:pollStatus"));
    }

    // ===================== 共存与分片 =====================

    /** 与节点级段共存: 节点 setup -> [级 setup -> 主体 -> 级 setdown] -> 段外动作 -> 节点 setdown。 */
    @Test
    public void actionSegmentCoexistsWithNodeSegments() {
        publish("asegCoexist", ""
                + "flow: \"dataSync\"\n"
                + "nodes:\n"
                + "  dataSync:\n"
                + "    setup:\n      - id: nPre\n        handler: ok\n"
                + "    actions:\n"
                + "      - id: warmUp\n        handler: ok\n"
                + "        setup:\n          - id: elevateW\n            handler: ok\n"
                + "        setdown:\n          - id: restoreW\n            handler: ok\n"
                + "      - id: pollStatus\n        handler: ok\n"
                + "    setdown:\n      - id: nPost\n        handler: ok\n");
        ProcessExecution r = run("asegCoexist");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        int nPre = rec.order.indexOf("handler:ok:nPre");
        int elevate = rec.order.indexOf("handler:ok:elevateW");
        int warm = rec.order.indexOf("handler:ok:warmUp");
        int restore = rec.order.indexOf("handler:ok:restoreW");
        int poll = rec.order.indexOf("handler:ok:pollStatus");
        int nPost = rec.order.indexOf("handler:ok:nPost");
        Assert.assertTrue("节点段包住整个回路",
                nPre >= 0 && nPre < elevate && elevate < warm && warm < restore && restore < poll && poll < nPost);
    }

    /** 分片: 每 lane 独立成段电路(3 分片各跑一遍 配置->暖身->恢复)。 */
    @Test
    public void shardedActionSegmentRunsPerLane() {
        publish("asegShard", ""
                + "flow: \"dataSync\"\n"
                + "nodes:\n"
                + "  dataSync:\n"
                + "    shard: dbTopology\n"
                + "    actions:\n"
                + "      - id: warmUp\n        handler: ok\n"
                + "        setup:\n          - id: elevateW\n            handler: ok\n"
                + "        setdown:\n          - id: restoreW\n            handler: ok\n");
        ProcessExecution r = run("asegShard");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        Assert.assertEquals("3 分片各跑一遍级 setup", 3, Collections.frequency(rec.order, "handler:ok:elevateW"));
        Assert.assertEquals(3, Collections.frequency(rec.order, "handler:ok:warmUp"));
        Assert.assertEquals(3, Collections.frequency(rec.order, "handler:ok:restoreW"));
    }

    // ===================== 辅助 =====================

    private ActionInstEntity actionByDef(String pid, String defId) {
        for (NodeInstEntity n : allNodes(pid)) {
            for (ActionInstEntity a : actionMapper.find(n.getNodeInstId(), null)) {
                if (defId.equals(a.getActionDefId())) {
                    return a;
                }
            }
        }
        Assert.fail("找不到 action: " + defId);
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
