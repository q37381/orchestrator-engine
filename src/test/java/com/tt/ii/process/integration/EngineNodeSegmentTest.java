package com.tt.ii.process.integration;

import com.tt.ii.process.api.ProcessExecution;
import com.tt.ii.process.core.engine.RollbackService;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.domain.LaneEntity;
import com.tt.ii.process.domain.NodeInstEntity;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 节点级 setup/setdown 段(回路三段: setup -> 主段 -> setdown)端到端:
 *  - finally: 主段失败, setdown 照跑(数据同步场景: 暂停复制 -> 同步 -> 恢复复制);
 *  - 崩溃补跑: "主段成功后、setdown 前中断" -> retry 重驱动只补 setdown(已成功段幂等跳过);
 *  - setdown 失败可重试: retry 复位该行重跑, 恢复动作必达;
 *  - 分片: 每 lane 一个回路(每分片各跑一遍三段);
 *  - 段不跟随分片(v1.3): segmentsFollowShard: false -> setup/setdown 整节点各一次(段行挂节点, 主体仍逐 lane 一行);
 *    空分片首驱不进回路、重入遇空分片仍收尾到位; 缺行重臂(取消停在动作边界)见末组(见 v1.3 用例组);
 *  - 回退只扫主段: 段行的不可补偿 handler 不 blocked 整轮回退;
 *  - 进度: items 全量带 segment, total/done 只算主段(分母不变)。
 *
 * 装配与替身见 {@link EngineE2ETestBase}(内存表 + Mockito, 不起容器)。
 */
public class EngineNodeSegmentTest extends EngineE2ETestBase {

    /** 数据同步故事的 YAML: setup=暂停复制, 主段=同步, setdown=恢复复制(收尾必达)。 */
    private static String syncDef(String syncHandler, String resumeHandler) {
        return "flow: \"preSteps, dataSync, postSteps\"\n"
                + "nodes:\n"
                + "  preSteps:\n    actions:\n      - id: pre\n        handler: ok\n"
                + "  dataSync:\n"
                + "    setup:\n      - id: pauseReplication\n        handler: ok\n"
                + "    actions:\n      - id: doSync\n        handler: " + syncHandler + "\n"
                + "    setdown:\n      - id: resumeReplication\n        handler: " + resumeHandler + "\n"
                + "  postSteps:\n    actions:\n      - id: post\n        handler: ok\n";
    }

    private static String syncDef(String syncHandler) {
        return syncDef(syncHandler, "ok");
    }

    // ===================== 正常路径 / segment 落库 =====================

    /** 三段按序驱动(pause -> sync -> resume), 段行 segment 列落库正确。 */
    @Test
    public void segmentsRunInOrderAndPersistSegmentColumn() {
        publish("segOk", syncDef("ok"));
        ProcessExecution r = run("segOk");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        String pid = r.getProcInstId();

        int pause = rec.order.indexOf("handler:ok:pauseReplication");
        int sync = rec.order.indexOf("handler:ok:doSync");
        int resume = rec.order.indexOf("handler:ok:resumeReplication");
        Assert.assertTrue("pause -> sync -> resume 顺序", pause >= 0 && pause < sync && sync < resume);

        Assert.assertEquals("setup", actionByDef(pid, "pauseReplication").getSegment());
        Assert.assertEquals("main", actionByDef(pid, "doSync").getSegment());
        Assert.assertEquals("setdown", actionByDef(pid, "resumeReplication").getSegment());
    }

    // ===================== finally: 主段失败收尾照跑 =====================

    /** doSync 失败 -> resumeReplication 照跑, 节点 failed; FAIL_FAST 下后续根不跑。 */
    @Test
    public void setdownRunsAfterMainFailure() {
        publish("segFail", syncDef("thrower"));
        ProcessExecution r = run("segFail");
        Assert.assertEquals(Status.FAILED, r.getStatus());
        String pid = r.getProcInstId();

        Assert.assertTrue("前置段跑了", rec.order.contains("handler:ok:pauseReplication"));
        Assert.assertTrue("主段挂了收尾段照跑", rec.order.contains("handler:ok:resumeReplication"));
        Assert.assertFalse("FAIL_FAST: 后续根不跑", rec.order.contains("handler:ok:post"));
        Assert.assertEquals(Status.FAILED.getStatus(), nodeByDef(pid, "dataSync").getStatus());
    }

    // ===================== 崩溃补跑 / 收尾失败重试 =====================

    /** 崩溃补跑: "主段成功后、setdown 前中断" -> retry 重驱动: 已成功段跳过, 只补 setdown。 */
    @Test
    public void retryAfterCrashReplaysOnlyUnfinishedSetdown() {
        publish("segCrash", syncDef("ok"));
        ProcessExecution r1 = run("segCrash");
        Assert.assertEquals(Status.SUCCESS, r1.getStatus());
        String pid = r1.getProcInstId();

        // 把现场倒回"主段刚成、setdown 未跑"(模拟 owner 崩溃残留: 节点 running + setdown 行 created + 流程 running)
        NodeInstEntity sync = nodeByDef(pid, "dataSync");
        ActionInstEntity resume = actionByDef(pid, "resumeReplication");
        actionMapper.updateState(resume.getActionInstId(), null, Status.CREATED.getStatus(), null, "u1");
        nodeMapper.setStatus(sync.getNodeInstId(), Status.RUNNING.getStatus(), "u1");
        processMapper.casStatus(pid, Status.SUCCESS.getStatus(), Status.RUNNING.getStatus(), "u1");

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("setup 段已成功: 不重跑", 1, Collections.frequency(rec.order, "handler:ok:pauseReplication"));
        Assert.assertEquals("主段已成功: 不重跑", 1, Collections.frequency(rec.order, "handler:ok:doSync"));
        Assert.assertEquals("未完成的收尾段补跑", 2, Collections.frequency(rec.order, "handler:ok:resumeReplication"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), actionByDef(pid, "resumeReplication").getStatus());
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "dataSync").getStatus());
    }

    /** setdown 失败可重试: 收尾首轮挂 -> retry 复位该行重跑成, 节点/流程 success。 */
    @Test
    public void failedSetdownIsRetriedAndCompletes() {
        publish("segFlaky", syncDef("ok", "flaky"));
        ProcessExecution r1 = run("segFlaky");
        Assert.assertEquals(Status.FAILED, r1.getStatus());
        String pid = r1.getProcInstId();
        Assert.assertEquals("收尾首轮挂", Status.FAILED.getStatus(), actionByDef(pid, "resumeReplication").getStatus());
        Assert.assertTrue("setup 已跑过", rec.order.contains("handler:ok:pauseReplication"));

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("收尾段复位后重跑成", 2, Collections.frequency(rec.order, "handler:flaky:resumeReplication"));
        Assert.assertEquals("setup 段已成功: 不重跑", 1, Collections.frequency(rec.order, "handler:ok:pauseReplication"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "dataSync").getStatus());
    }

    // ===================== 分片: 每 lane 一个回路 =====================

    /** dbTopology 一主两从 = 3 lane -> 每 lane 独立跑一遍三段, 段行挂本 lane。 */
    @Test
    public void shardedNodeRunsCircuitPerLane() {
        publish("segShard", ""
                + "flow: \"dataSync\"\n"
                + "nodes:\n"
                + "  dataSync:\n"
                + "    shard: dbTopology\n"
                + "    setup:\n      - id: pauseReplication\n        handler: ok\n"
                + "    actions:\n      - id: doSync\n        handler: ok\n"
                + "    setdown:\n      - id: resumeReplication\n        handler: ok\n");
        ProcessExecution r = run("segShard");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        String pid = r.getProcInstId();

        Assert.assertEquals("3 分片各跑一遍 setup",
                3, Collections.frequency(rec.order, "handler:ok:pauseReplication"));
        Assert.assertEquals(3, Collections.frequency(rec.order, "handler:ok:doSync"));
        Assert.assertEquals(3, Collections.frequency(rec.order, "handler:ok:resumeReplication"));

        NodeInstEntity n = nodeByDef(pid, "dataSync");
        List<LaneEntity> lanes = laneMapper.findByNode(n.getNodeInstId());
        Assert.assertEquals(3, lanes.size());
        for (LaneEntity l : lanes) {
            List<ActionInstEntity> rows = actionMapper.findInContainer(n.getNodeInstId(), l.getLaneId());
            Assert.assertEquals("每 lane 三段各一行", 3, rows.size());
            Assert.assertEquals("setup", rows.get(0).getSegment());
            Assert.assertEquals("main", rows.get(1).getSegment());
            Assert.assertEquals("setdown", rows.get(2).getSegment());
            Assert.assertEquals("段行归属本 lane", l.getLaneId(), rows.get(0).getLaneId());
        }
    }

    // ===================== 回退只扫主段 =====================

    /** 段行的 handler 不可补偿不 blocked 整轮回退; 主段可补偿动作照常撤销。 */
    @Test
    public void rollbackSkipsSegmentRows() {
        publish("segRb", ""
                + "flow: \"dataSync\"\n"
                + "nodes:\n"
                + "  dataSync:\n"
                + "    setup:\n      - id: pauseReplication\n        handler: ok\n"
                + "    actions:\n      - id: doSync\n        handler: compOk\n"
                + "    setdown:\n      - id: resumeReplication\n        handler: ok\n");
        ProcessExecution r = run("segRb");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        RollbackService.RollbackReport rep = facade.doRollback(r.getProcInstId(), "u1");
        Assert.assertEquals("只有主段的 compOk 进补偿", 1, rep.compensated.size());
        Assert.assertTrue("段行不参与回退: 不因不可补偿而 blocked", rep.blocked.isEmpty());
        Assert.assertEquals(Status.SUCCESS, rep.outcome());
    }

    // ===================== 进度口径 =====================

    /** items 全量带 segment(段行可见); total 只算主段(分母 = 一个节点一个校验项的心智不变)。 */
    @Test
    @SuppressWarnings("unchecked")
    public void progressCountsMainSegmentOnly() {
        publish("segProg", syncDef("ok"));
        ProcessExecution r = run("segProg");

        Map<String, Object> p = facade.progress(r.getProcInstId());
        Assert.assertEquals("分母只算主段: pre + doSync + post", 3, p.get("total"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) p.get("items");
        Assert.assertEquals("明细全量: 5 条(含 setup/setdown)", 5, items.size());
        int setdown = 0;
        for (Map<String, Object> it : items) {
            if ("setdown".equals(it.get("segment"))) {
                setdown++;
            }
        }
        Assert.assertEquals("收尾段行在明细里带段可见", 1, setdown);
    }

    // ===================== v1.1: 成对重臂 / repeatSegments =====================

    /** 成对重臂(main 侧): 主段失败 + setdown 已成 + retry -> setup 重跑(现场重施)、main 续跑、setdown 重跑(重新收尾)。 */
    @Test
    public void pairedReArmRerunsSetupAndSetdownAfterMainRetry() {
        publish("segRearm", syncDef("flaky"));
        ProcessExecution r1 = run("segRearm");
        Assert.assertEquals(Status.FAILED, r1.getStatus());
        String pid = r1.getProcInstId();
        Assert.assertEquals("首轮 setup 跑过一次", 1, Collections.frequency(rec.order, "handler:ok:pauseReplication"));

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("setup 重跑(现场重施)", 2, Collections.frequency(rec.order, "handler:ok:pauseReplication"));
        Assert.assertEquals("main 在恢复好的现场上续跑", 2, Collections.frequency(rec.order, "handler:flaky:doSync"));
        Assert.assertEquals("setdown 重跑(重新收尾)", 2, Collections.frequency(rec.order, "handler:ok:resumeReplication"));
        Assert.assertEquals("后续根续跑", 1, Collections.frequency(rec.order, "handler:ok:post"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "dataSync").getStatus());
    }

    /** 成对重臂(setup 侧): setup 失败(main 未物化) + setdown 已成 + retry -> setup 重跑成、main 首次执行、setdown 重新收尾。 */
    @Test
    public void pairedReArmRerunsAfterSetupFailureRetry() {
        publish("segRearmSetup", ""
                + "flow: \"dataSync\"\n"
                + "nodes:\n"
                + "  dataSync:\n"
                + "    setup:\n      - id: pauseReplication\n        handler: flaky\n"
                + "    actions:\n      - id: doSync\n        handler: ok\n"
                + "    setdown:\n      - id: resumeReplication\n        handler: ok\n");
        ProcessExecution r1 = run("segRearmSetup");
        Assert.assertEquals(Status.FAILED, r1.getStatus());
        String pid = r1.getProcInstId();
        Assert.assertTrue("首个 setup 尝试挂", rec.order.contains("handler:flaky:pauseReplication"));

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("setup 复位重跑成功", 2, Collections.frequency(rec.order, "handler:flaky:pauseReplication"));
        Assert.assertEquals("main 首次执行(原被跳过)", 1, Collections.frequency(rec.order, "handler:ok:doSync"));
        Assert.assertEquals("setdown 重新收尾", 2, Collections.frequency(rec.order, "handler:ok:resumeReplication"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "dataSync").getStatus());
    }

    /** repeatSegments: 每趟驱动段重跑——"主段成、setdown 未跑"的补跑窗口里与成对重臂的差异可见。 */
    @Test
    public void repeatSegmentsRerunsSegmentsEveryDrive() {
        publish("segRepeat", ""
                + "flow: \"dataSync\"\n"
                + "nodes:\n"
                + "  dataSync:\n"
                + "    repeatSegments: true\n"
                + "    setup:\n      - id: pauseReplication\n        handler: ok\n"
                + "    actions:\n      - id: doSync\n        handler: ok\n"
                + "    setdown:\n      - id: resumeReplication\n        handler: ok\n");
        ProcessExecution r1 = run("segRepeat");
        Assert.assertEquals(Status.SUCCESS, r1.getStatus());
        String pid = r1.getProcInstId();

        // 倒回"主段成、setdown 未跑"崩溃残留(现场仍生效): 成对重臂在此窗口不触发, repeatSegments 每趟都重跑
        NodeInstEntity sync = nodeByDef(pid, "dataSync");
        ActionInstEntity resume = actionByDef(pid, "resumeReplication");
        actionMapper.updateState(resume.getActionInstId(), null, Status.CREATED.getStatus(), null, "u1");
        nodeMapper.setStatus(sync.getNodeInstId(), Status.RUNNING.getStatus(), "u1");
        processMapper.casStatus(pid, Status.SUCCESS.getStatus(), Status.RUNNING.getStatus(), "u1");

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("每趟驱动 setup 都重跑", 2, Collections.frequency(rec.order, "handler:ok:pauseReplication"));
        Assert.assertEquals("主段已成功: 不重跑", 1, Collections.frequency(rec.order, "handler:ok:doSync"));
        Assert.assertEquals("本趟收尾补跑", 2, Collections.frequency(rec.order, "handler:ok:resumeReplication"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "dataSync").getStatus());
    }

    /** 分片: 重臂 per-lane——只重臂失败重试的那条 lane, 其余 lane 首跑不受影响。 */
    @Test
    public void shardedReArmAppliesPerLane() {
        publish("segShardRearm", ""
                + "flow: \"dataSync\"\n"
                + "nodes:\n"
                + "  dataSync:\n"
                + "    shard: dbTopology\n"
                + "    setup:\n      - id: pauseReplication\n        handler: ok\n"
                + "    actions:\n      - id: doSync\n        handler: flaky\n"
                + "    setdown:\n      - id: resumeReplication\n        handler: ok\n");
        ProcessExecution r1 = run("segShardRearm");
        Assert.assertEquals(Status.FAILED, r1.getStatus());
        String pid = r1.getProcInstId();
        Assert.assertEquals("FAIL_FAST: 只有首条 lane 跑了 setup", 1, Collections.frequency(rec.order, "handler:ok:pauseReplication"));
        Assert.assertEquals(1, Collections.frequency(rec.order, "handler:flaky:doSync"));
        Assert.assertEquals("首条 lane 的收尾照跑(finally)", 1, Collections.frequency(rec.order, "handler:ok:resumeReplication"));

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("首条 lane 重臂一次 + 后两条 lane 首跑", 4, Collections.frequency(rec.order, "handler:ok:pauseReplication"));
        Assert.assertEquals(4, Collections.frequency(rec.order, "handler:flaky:doSync"));
        Assert.assertEquals(4, Collections.frequency(rec.order, "handler:ok:resumeReplication"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "dataSync").getStatus());
    }

    // ===================== v1.3: 段不跟随分片(segmentsFollowShard: false) =====================

    /** 分片 + 段不跟随分片的数据同步 YAML: setup/setdown 声明为"整节点各一次"那一档。 */
    private static String sharedDef(String syncHandler) {
        return "flow: \"dataSync\"\n"
                + "nodes:\n"
                + "  dataSync:\n"
                + "    shard: dbTopology\n"
                + "    segmentsFollowShard: false\n"
                + "    setup:\n      - id: pauseReplication\n        handler: ok\n"
                + "    actions:\n      - id: doSync\n        handler: " + syncHandler + "\n"
                + "    setdown:\n      - id: resumeReplication\n        handler: ok\n";
    }

    /** 共享段: setup/setdown 整节点各一次(段行挂节点, lane 恒 null), 主体仍逐 lane 一行。 */
    @Test
    public void sharedSegmentsRunOnceForWholeNode() {
        publish("segShared", sharedDef("ok"));
        ProcessExecution r = run("segShared");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        String pid = r.getProcInstId();

        Assert.assertEquals("setup 整节点一次", 1, Collections.frequency(rec.order, "handler:ok:pauseReplication"));
        Assert.assertEquals("主体逐 lane", 3, Collections.frequency(rec.order, "handler:ok:doSync"));
        Assert.assertEquals("setdown 整节点一次", 1, Collections.frequency(rec.order, "handler:ok:resumeReplication"));

        int pause = rec.order.indexOf("handler:ok:pauseReplication");
        int firstSync = rec.order.indexOf("handler:ok:doSync");
        int lastSync = rec.order.lastIndexOf("handler:ok:doSync");
        int resume = rec.order.indexOf("handler:ok:resumeReplication");
        Assert.assertTrue("setup 在所有 lane 之前", pause >= 0 && pause < firstSync);
        Assert.assertTrue("setdown 在全部 lane 之后", lastSync < resume);

        NodeInstEntity n = nodeByDef(pid, "dataSync");
        ActionInstEntity setupRow = actionByDef(pid, "pauseReplication");
        ActionInstEntity setdownRow = actionByDef(pid, "resumeReplication");
        Assert.assertNull("共享段行不挂 lane", setupRow.getLaneId());
        Assert.assertNull("共享段行无分片键", setupRow.getShardKey());
        Assert.assertEquals("setup", setupRow.getSegment());
        Assert.assertNull(setdownRow.getLaneId());
        Assert.assertEquals("setdown", setdownRow.getSegment());

        List<LaneEntity> lanes = laneMapper.findByNode(n.getNodeInstId());
        Assert.assertEquals(3, lanes.size());
        for (LaneEntity l : lanes) {
            List<ActionInstEntity> rows = actionMapper.findInContainer(n.getNodeInstId(), l.getLaneId());
            Assert.assertEquals("每 lane 只剩主段一行(节点段被外层摘走)", 1, rows.size());
            Assert.assertEquals("main", rows.get(0).getSegment());
        }
    }

    /** 共享段 + laneExecution: PARALLEL: setup 一次先行、setdown 一次最后, 主体 3 lane 照并行。 */
    @Test
    public void sharedSegmentsRunOnceUnderParallelLanes() {
        publish("segSharedPar", "flow: \"dataSync\"\n"
                + "nodes:\n"
                + "  dataSync:\n"
                + "    shard: dbTopology\n"
                + "    laneExecution: PARALLEL\n"
                + "    segmentsFollowShard: false\n"
                + "    setup:\n      - id: pauseReplication\n        handler: ok\n"
                + "    actions:\n      - id: doSync\n        handler: ok\n"
                + "    setdown:\n      - id: resumeReplication\n        handler: ok\n");
        ProcessExecution r = run("segSharedPar");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        Assert.assertEquals("setup 整节点一次", 1, Collections.frequency(rec.order, "handler:ok:pauseReplication"));
        Assert.assertEquals(3, Collections.frequency(rec.order, "handler:ok:doSync"));
        Assert.assertEquals("setdown 整节点一次", 1, Collections.frequency(rec.order, "handler:ok:resumeReplication"));
        int pause = rec.order.indexOf("handler:ok:pauseReplication");
        int firstSync = rec.order.indexOf("handler:ok:doSync");
        int lastSync = rec.order.lastIndexOf("handler:ok:doSync");
        int resume = rec.order.indexOf("handler:ok:resumeReplication");
        Assert.assertTrue("setup 先于全部 lane", pause >= 0 && pause < firstSync);
        Assert.assertTrue("setdown 后于全部 lane", lastSync < resume);
    }

    /** 共享段 finally: 主段首条 lane 挂 -> FAIL_FAST 停后续 lane, 共享 setdown 照跑一次; 节点 failed。 */
    @Test
    public void sharedSetdownRunsAfterMainFailure() {
        publish("segSharedFail", sharedDef("thrower"));
        ProcessExecution r = run("segSharedFail");
        Assert.assertEquals(Status.FAILED, r.getStatus());
        String pid = r.getProcInstId();

        Assert.assertEquals("共享 setup 一次", 1, Collections.frequency(rec.order, "handler:ok:pauseReplication"));
        NodeInstEntity n = nodeByDef(pid, "dataSync");
        Assert.assertEquals("FAIL_FAST: 只物化首条 lane", 1, laneMapper.findByNode(n.getNodeInstId()).size());
        Assert.assertEquals(Status.FAILED.getStatus(), actionByDef(pid, "doSync").getStatus());
        Assert.assertEquals("主段挂了共享收尾照跑", 1, Collections.frequency(rec.order, "handler:ok:resumeReplication"));
        Assert.assertEquals(Status.FAILED.getStatus(), n.getStatus());
    }

    /** 共享 setup 挂: 主体整体跳过(lane 一行都不物化), 共享 setdown 照跑; 节点 failed。 */
    @Test
    public void sharedSetupFailureSkipsMainAndStillSettles() {
        publish("segSharedSetup", "flow: \"dataSync\"\n"
                + "nodes:\n"
                + "  dataSync:\n"
                + "    shard: dbTopology\n"
                + "    segmentsFollowShard: false\n"
                + "    setup:\n      - id: pauseReplication\n        handler: thrower\n"
                + "    actions:\n      - id: doSync\n        handler: ok\n"
                + "    setdown:\n      - id: resumeReplication\n        handler: ok\n");
        ProcessExecution r = run("segSharedSetup");
        Assert.assertEquals(Status.FAILED, r.getStatus());
        String pid = r.getProcInstId();

        Assert.assertEquals("主体整体跳过", 0, Collections.frequency(rec.order, "handler:ok:doSync"));
        NodeInstEntity n = nodeByDef(pid, "dataSync");
        Assert.assertTrue("lane 一行都没物化", laneMapper.findByNode(n.getNodeInstId()).isEmpty());
        Assert.assertEquals("共享收尾照跑一次", 1, Collections.frequency(rec.order, "handler:ok:resumeReplication"));
        Assert.assertEquals(Status.FAILED.getStatus(), n.getStatus());
    }

    /** 共享崩溃补跑: "主体全成、共享 setdown 未跑" -> retry 只补共享收尾(setup 与各 lane 主体都不重跑)。 */
    @Test
    public void sharedCrashBeforeSetdownOnlyCompletesSetdown() {
        publish("segSharedCrash", sharedDef("ok"));
        ProcessExecution r1 = run("segSharedCrash");
        Assert.assertEquals(Status.SUCCESS, r1.getStatus());
        String pid = r1.getProcInstId();

        NodeInstEntity sync = nodeByDef(pid, "dataSync");
        ActionInstEntity resume = actionByDef(pid, "resumeReplication");
        actionMapper.updateState(resume.getActionInstId(), null, Status.CREATED.getStatus(), null, "u1");
        nodeMapper.setStatus(sync.getNodeInstId(), Status.RUNNING.getStatus(), "u1");
        processMapper.casStatus(pid, Status.SUCCESS.getStatus(), Status.RUNNING.getStatus(), "u1");

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("共享 setup 已成功: 不重跑", 1, Collections.frequency(rec.order, "handler:ok:pauseReplication"));
        Assert.assertEquals("各 lane 主体已成功: 不重跑", 3, Collections.frequency(rec.order, "handler:ok:doSync"));
        Assert.assertEquals("未完成的共享收尾补跑", 2, Collections.frequency(rec.order, "handler:ok:resumeReplication"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), actionByDef(pid, "resumeReplication").getStatus());
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "dataSync").getStatus());
    }

    /** 共享成对重臂: 主体首条 lane 挂 + 共享 setdown 已成 + retry -> 共享 setup/setdown 成对复位重跑、lane 续跑。 */
    @Test
    public void sharedPairedReArmAfterRetry() {
        publish("segSharedRearm", sharedDef("flaky"));
        ProcessExecution r1 = run("segSharedRearm");
        Assert.assertEquals(Status.FAILED, r1.getStatus());
        String pid = r1.getProcInstId();
        Assert.assertEquals("首轮共享 setup 一次", 1, Collections.frequency(rec.order, "handler:ok:pauseReplication"));
        Assert.assertEquals("首轮共享 setdown 照跑一次", 1, Collections.frequency(rec.order, "handler:ok:resumeReplication"));

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("共享 setup 重跑(现场重施)", 2, Collections.frequency(rec.order, "handler:ok:pauseReplication"));
        Assert.assertEquals("首条 lane 主体重跑 + 后两条首跑", 4, Collections.frequency(rec.order, "handler:flaky:doSync"));
        Assert.assertEquals("共享 setdown 重跑(重新收尾)", 2, Collections.frequency(rec.order, "handler:ok:resumeReplication"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "dataSync").getStatus());
    }

    /** 两级段共存: 节点段不跟随分片(各一次), action 级段仍每 lane 各跑一遍(级段随主体走)。 */
    @Test
    public void sharedNodeSegmentsCoexistWithPerLaneActionSegments() {
        publish("segSharedMixed", "flow: \"dataSync\"\n"
                + "nodes:\n"
                + "  dataSync:\n"
                + "    shard: dbTopology\n"
                + "    segmentsFollowShard: false\n"
                + "    setup:\n      - id: nPre\n        handler: ok\n"
                + "    actions:\n"
                + "      - id: warmUp\n        handler: ok\n"
                + "        setup:\n          - id: elevateW\n            handler: ok\n"
                + "        setdown:\n          - id: restoreW\n            handler: ok\n"
                + "    setdown:\n      - id: nPost\n        handler: ok\n");
        ProcessExecution r = run("segSharedMixed");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        Assert.assertEquals("节点段各一次", 1, Collections.frequency(rec.order, "handler:ok:nPre"));
        Assert.assertEquals("级段随主体逐 lane", 3, Collections.frequency(rec.order, "handler:ok:elevateW"));
        Assert.assertEquals(3, Collections.frequency(rec.order, "handler:ok:warmUp"));
        Assert.assertEquals(3, Collections.frequency(rec.order, "handler:ok:restoreW"));
        Assert.assertEquals(1, Collections.frequency(rec.order, "handler:ok:nPost"));

        int nPre = rec.order.indexOf("handler:ok:nPre");
        int firstElev = rec.order.indexOf("handler:ok:elevateW");
        int lastRestore = rec.order.lastIndexOf("handler:ok:restoreW");
        int nPost = rec.order.indexOf("handler:ok:nPost");
        Assert.assertTrue("节点 setup 包住全部 lane 的级段", nPre >= 0 && nPre < firstElev);
        Assert.assertTrue("节点 setdown 在所有 lane 级段之后", lastRestore < nPost);
    }

    /** 共享段 + repeatSegments: 每趟驱动共享 setup/setdown 无条件重跑, 各 lane 主体已成功仍不重跑。 */
    @Test
    public void sharedSegmentsRepeatEveryDrive() {
        publish("segSharedRepeat", "flow: \"dataSync\"\n"
                + "nodes:\n"
                + "  dataSync:\n"
                + "    shard: dbTopology\n"
                + "    segmentsFollowShard: false\n"
                + "    repeatSegments: true\n"
                + "    setup:\n      - id: pauseReplication\n        handler: ok\n"
                + "    actions:\n      - id: doSync\n        handler: ok\n"
                + "    setdown:\n      - id: resumeReplication\n        handler: ok\n");
        ProcessExecution r1 = run("segSharedRepeat");
        Assert.assertEquals(Status.SUCCESS, r1.getStatus());
        String pid = r1.getProcInstId();

        // 倒回"主段成、共享 setdown 未跑"窗口: 默认成对重臂在此不触发, repeatSegments 每趟都重跑两段
        NodeInstEntity sync = nodeByDef(pid, "dataSync");
        ActionInstEntity resume = actionByDef(pid, "resumeReplication");
        actionMapper.updateState(resume.getActionInstId(), null, Status.CREATED.getStatus(), null, "u1");
        nodeMapper.setStatus(sync.getNodeInstId(), Status.RUNNING.getStatus(), "u1");
        processMapper.casStatus(pid, Status.SUCCESS.getStatus(), Status.RUNNING.getStatus(), "u1");

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("每趟驱动共享 setup 重跑", 2, Collections.frequency(rec.order, "handler:ok:pauseReplication"));
        Assert.assertEquals("各 lane 主体已成功: 不重跑", 3, Collections.frequency(rec.order, "handler:ok:doSync"));
        Assert.assertEquals("本趟共享收尾补跑", 2, Collections.frequency(rec.order, "handler:ok:resumeReplication"));
    }

    /** 首驱空分片: 不存在回路, 共享段一行不物化(与逐 lane 口径一致)。 */
    @Test
    public void emptyShardsSkipSharedSegments() {
        publish("segSharedEmpty", "flow: \"dataSync\"\n"
                + "nodes:\n"
                + "  dataSync:\n"
                + "    shard: emptyTopology\n"
                + "    segmentsFollowShard: false\n"
                + "    setup:\n      - id: pauseReplication\n        handler: ok\n"
                + "    actions:\n      - id: doSync\n        handler: ok\n"
                + "    setdown:\n      - id: resumeReplication\n        handler: ok\n");
        ProcessExecution r = run("segSharedEmpty");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        String pid = r.getProcInstId();

        Assert.assertEquals(0, Collections.frequency(rec.order, "handler:ok:pauseReplication"));
        Assert.assertEquals(0, Collections.frequency(rec.order, "handler:ok:doSync"));
        Assert.assertEquals(0, Collections.frequency(rec.order, "handler:ok:resumeReplication"));
        NodeInstEntity n = nodeByDef(pid, "dataSync");
        Assert.assertTrue("段与 lane 一行都不物化", actionMapper.find(n.getNodeInstId(), null).isEmpty());
        Assert.assertEquals(Status.SUCCESS.getStatus(), n.getStatus());
    }

    /** 重入时分片键解析为空但现场已施加(曾跑过 lane): 共享收尾必须补完, 不能靠空 keys 静默成功。 */
    @Test
    public void vanishedShardsOnRetryStillSettleSharedSetdown() {
        publish("segSharedGone", "flow: \"dataSync\"\n"
                + "nodes:\n"
                + "  dataSync:\n"
                + "    shard: flakyTopology\n"
                + "    segmentsFollowShard: false\n"
                + "    setup:\n      - id: pauseReplication\n        handler: ok\n"
                + "    actions:\n      - id: doSync\n        handler: ok\n"
                + "    setdown:\n      - id: resumeReplication\n        handler: ok\n");
        ProcessExecution r1 = run("segSharedGone");
        Assert.assertEquals(Status.SUCCESS, r1.getStatus());
        String pid = r1.getProcInstId();
        Assert.assertEquals("首轮 3 lane 各跑主体", 3, Collections.frequency(rec.order, "handler:ok:doSync"));

        // 倒回"主体全成、共享 setdown 未跑"; 重试时拓扑消失(resolveShardKeys 返空)
        NodeInstEntity sync = nodeByDef(pid, "dataSync");
        ActionInstEntity resume = actionByDef(pid, "resumeReplication");
        actionMapper.updateState(resume.getActionInstId(), null, Status.CREATED.getStatus(), null, "u1");
        nodeMapper.setStatus(sync.getNodeInstId(), Status.RUNNING.getStatus(), "u1");
        processMapper.casStatus(pid, Status.SUCCESS.getStatus(), Status.RUNNING.getStatus(), "u1");

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("空 keys 不补跑主体", 3, Collections.frequency(rec.order, "handler:ok:doSync"));
        Assert.assertEquals("共享 setdown 照常补完", 2, Collections.frequency(rec.order, "handler:ok:resumeReplication"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "dataSync").getStatus());
    }

    // ===================== 缺行重臂(全 success 前缀 + 未物化尾巴) =====================

    /** 单分片 + 停在动作边界(stopper 在第二条动作中途把流程置 STOPPED)的 YAML。 */
    private static String shortRowsDef(boolean segmentsShared) {
        return "flow: \"dataSync\"\n"
                + "nodes:\n"
                + "  dataSync:\n"
                + "    shard: singleTopology\n"
                + (segmentsShared ? "    segmentsFollowShard: false\n" : "")
                + "    setup:\n      - id: pauseReplication\n        handler: ok\n"
                + "    actions:\n"
                + "      - id: pre1\n        handler: ok\n"
                + "      - id: stopHere\n        handler: stopper\n"
                + "      - id: tail\n        handler: ok\n"
                + "    setdown:\n      - id: resumeReplication\n        handler: ok\n";
    }

    /** 缺行重臂(共享档): 中断停在末条 lane 的动作边界——已物化的是一段全 success 前缀、尾巴未物化。
     *  缺行也是"主体待跑": retry 仍成对重臂, 否则尾巴会在已被 setdown 撤除的现场上续跑。 */
    @Test
    public void sharedCancelMidMainRetryRearmsOnUnmaterializedTail() {
        publish("segSharedShortRows", shortRowsDef(true));
        ProcessExecution r1 = run("segSharedShortRows");
        Assert.assertEquals(Status.STOPPED, r1.getStatus());
        String pid = r1.getProcInstId();
        Assert.assertFalse("取消落在动作边界: 尾巴未跑", rec.order.contains("handler:ok:tail"));
        Assert.assertEquals("共享 setdown 照跑一次(finally)",
                1, Collections.frequency(rec.order, "handler:ok:resumeReplication"));

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("缺行也是主体待跑: 共享 setup 重施",
                2, Collections.frequency(rec.order, "handler:ok:pauseReplication"));
        Assert.assertEquals("尾巴在重施的现场上续跑", 1, Collections.frequency(rec.order, "handler:ok:tail"));
        Assert.assertEquals("共享 setdown 重新收尾", 2, Collections.frequency(rec.order, "handler:ok:resumeReplication"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "dataSync").getStatus());
    }

    /** 缺行重臂(逐 lane 默认档同款判据): 中断停在 lane 内动作边界——全 success 前缀 + 未物化尾巴同样算
     *  "主体待跑", retry 后该 lane 的 setup/setdown 成对重跑(v1.1 只查非终态的漏判形状)。 */
    @Test
    public void laneCancelMidMainRetryRearmsOnUnmaterializedTail() {
        publish("segLaneShortRows", shortRowsDef(false));
        ProcessExecution r1 = run("segLaneShortRows");
        Assert.assertEquals(Status.STOPPED, r1.getStatus());
        String pid = r1.getProcInstId();
        Assert.assertFalse("取消落在动作边界: 尾巴未跑", rec.order.contains("handler:ok:tail"));
        Assert.assertEquals("lane 收尾照跑一次(finally)",
                1, Collections.frequency(rec.order, "handler:ok:resumeReplication"));

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("缺行也是主体待跑: lane setup 重施",
                2, Collections.frequency(rec.order, "handler:ok:pauseReplication"));
        Assert.assertEquals("尾巴在重施的现场上续跑", 1, Collections.frequency(rec.order, "handler:ok:tail"));
        Assert.assertEquals("lane 收尾重新收束", 2, Collections.frequency(rec.order, "handler:ok:resumeReplication"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "dataSync").getStatus());
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
