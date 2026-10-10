package com.tesla.ities.process.integration;

import com.tesla.ities.process.api.ProcessExecution;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.domain.ActionInstEntity;
import com.tesla.ities.process.domain.LaneEntity;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.domain.ProcessInstEntity;
import com.tesla.ities.process.util.Errors;
import com.tesla.ities.process.util.Json;
import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 失败异常记录端到端(装配见 {@link EngineE2ETestBase}): 以前只打在日志里的那句异常, 现在按"谁炸的就在谁行上记一条"落库——
 *  action 行 / node 行各自一句(__error 保留键, 语境 + 定位 + 类名: 消息),
 *  流程行只记本次驱动收到的**第一条**, 坏终态才写、跑成功反向摘掉残留;
 *  定义装载失败(连 DriveContext 都建不出来)也落一句并翻 FAILED, 不再僵在 running。
 * 门面的执行返回值顺手把流程那一条带回来({@link ProcessExecution#getError()}); 其余信息照旧走 progress。
 */
public class EngineErrorRecordTest extends EngineE2ETestBase {

    /** 流程行上那一条失败摘要。 */
    private String processError(String procInstId) {
        return Errors.readFrom(processMapper.findById(procInstId).getResult());
    }

    // ===================== 流程级 before 否决 =====================

    @Test
    public void processBeforeVetoRecordsReasonOnProcessRow() {
        publish("vetoProc", ""
                + "flow: \"x\"\n"
                + "listeners:\n  - name: pVeto\n"
                + "nodes:\n  x:\n    handler: ok\n");
        ProcessExecution r = run("vetoProc");
        Assert.assertEquals(Status.FAILED, r.getStatus());

        String err = processError(r.getProcInstId());
        Assert.assertNotNull("流程级否决的因由要落流程行 __error", err);
        Assert.assertTrue(err, err.startsWith("流程 before 否决(process=" + r.getProcInstId() + "): "));
        Assert.assertTrue(err, err.contains("java.lang.IllegalStateException"));
        Assert.assertTrue(err, err.contains("process veto"));
        Assert.assertEquals(err, r.getError());        // 门面当场带回同一句, 不必再查库
        Assert.assertTrue(r.hasError());
    }

    // ===================== 顶层走查抛错(flow 引用未定义节点) =====================

    @Test
    public void rootMaterializeFailureRecordsReasonOnProcessRow() {
        publish("ghostRoot", "flow: \"doesNotExist\"\nnodes:\n  other:\n    handler: ok\n");
        ProcessExecution r = run("ghostRoot");
        Assert.assertEquals(Status.FAILED, r.getStatus());
        Assert.assertTrue(nodeMapper.findRoots(r.getProcInstId()).isEmpty());   // 根都没建成, 没有下层行可记

        String err = processError(r.getProcInstId());
        Assert.assertTrue(err, err.startsWith("驱动流程异常(process=" + r.getProcInstId() + "): "));
        Assert.assertTrue(err, err.contains("AU204302"));          // 键必须可见: 类型统一后只能靠它定位错误种类
        Assert.assertTrue(err, err.contains("未定义的节点"));
        Assert.assertEquals(err, r.getError());
    }

    // ===================== 策略驱动抛错: 节点行记一条, 流程行记同一句 =====================

    @Test
    public void strategyFailureRecordsReasonOnNodeAndProcess() {
        // LAZY 下 PARALLEL 的子行驱动期才物化: 第一个子就引用未定义节点 -> childAt 抛错被本节点的 driveNode 接住
        publish("ghostChild", ""
                + "flow: \"PARALLEL(ghost, good)\"\n"
                + "nodes:\n  good:\n    handler: ok\n");
        ProcessExecution r = run("ghostChild");
        Assert.assertEquals(Status.FAILED, r.getStatus());

        NodeInstEntity par = nodeMapper.findRoots(r.getProcInstId()).get(0);
        String nodeErr = Errors.readFrom(par.getResult());
        Assert.assertNotNull("节点驱动异常要落节点行 result(以前这里只打日志)", nodeErr);
        Assert.assertTrue(nodeErr, nodeErr.startsWith("节点驱动异常(node="));
        Assert.assertTrue(nodeErr, nodeErr.contains("AU204302"));
        Assert.assertTrue(nodeErr, nodeErr.contains("未定义的节点"));
        Assert.assertEquals(nodeErr, processError(r.getProcInstId()));   // 流程级记的就是收到的第一条
        Assert.assertEquals(nodeErr, r.getError());
    }

    // ===================== handler 抛错: action 行 + 流程行同一句 =====================

    @Test
    public void handlerFailureRecordsReasonOnActionAndProcess() {
        publish("boom", ""
                + "flow: \"x\"\n"
                + "nodes:\n  x:\n    handler: thrower\n");
        ProcessExecution r = run("boom");
        Assert.assertEquals(Status.FAILED, r.getStatus());

        NodeInstEntity node = nodeMapper.findRoots(r.getProcInstId()).get(0);
        ActionInstEntity a = actionMapper.findInContainer(node.getNodeInstId(), null).get(0);
        String actErr = Errors.readFrom(a.getResult());
        // 单 handler 节点合成一条 action(声明 id = 节点 id), 定位串两层都给全
        Assert.assertEquals("action 执行异常(node=x/action=x): java.lang.IllegalStateException: handler boom", actErr);
        Assert.assertEquals(actErr, processError(r.getProcInstId()));
        Assert.assertEquals(actErr, r.getError());
    }

    /** 分片 action: 定位串带上那一片的 shardKey, 才知道是哪一个端点炸的。 */
    @Test
    public void shardedFailureLocatorCarriesShardKey() {
        publish("shardBoom", ""
                + "flow: \"x\"\n"
                + "nodes:\n"
                + "  x:\n"
                + "    handler: thrower\n"
                + "    shard: dbTopology\n");
        ProcessExecution r = run("shardBoom");
        Assert.assertEquals(Status.FAILED, r.getStatus());

        // 分片节点的 action 挂在各自 lane 下(非分片才在 node 容器上), 取第一条 lane 的行看定位
        NodeInstEntity node = nodeMapper.findRoots(r.getProcInstId()).get(0);
        List<LaneEntity> lanes = laneMapper.findByNode(node.getNodeInstId());
        Assert.assertFalse("分片应建出 lane", lanes.isEmpty());
        ActionInstEntity a = actionMapper.findInContainer(node.getNodeInstId(), lanes.get(0).getLaneId()).get(0);
        Assert.assertNotNull(a.getShardKey());
        String err = Errors.readFrom(a.getResult());
        Assert.assertTrue(err, err.contains("/action=x@" + a.getShardKey()));
        Assert.assertEquals(err, r.getError());
    }

    /** action 级 before 否决同样记一条。 */
    @Test
    public void actionBeforeVetoRecordsReason() {
        publish("vetoAction", ""
                + "flow: \"x\"\n"
                + "nodes:\n"
                + "  x:\n"
                + "    actions:\n"
                + "      - {id: a, handler: ok, listeners: [{name: aVeto}]}\n");
        ProcessExecution r = run("vetoAction");
        Assert.assertEquals(Status.FAILED, r.getStatus());

        NodeInstEntity node = nodeMapper.findRoots(r.getProcInstId()).get(0);
        ActionInstEntity a = actionMapper.findInContainer(node.getNodeInstId(), null).get(0);
        String err = Errors.readFrom(a.getResult());
        Assert.assertTrue(err, err.startsWith("action before 否决(node=x/action=a): "));
        Assert.assertTrue(err, err.contains("action veto"));
    }

    // ===================== 定义装载失败: 不僵在 running, 原因落流程行 =====================

    @Test
    public void definitionLoadFailureRecordsReasonAndFails() {
        // 直接插一条流程行指向不存在的定义版本(绕过 PlanService 的定位), 模拟定义被清掉/钉错版本
        ProcessInstEntity p = new ProcessInstEntity();
        p.setProcInstId("ghostproc000000001");
        p.setProcessDefId("noSuchDef");
        p.setDefVersion(9);
        p.setStatus(Status.PENDING.getStatus());
        p.setCreatorId("u1");
        processMapper.insert(p);

        ProcessExecution r = facade.start(p.getProcInstId());
        Assert.assertEquals(Status.FAILED, r.getStatus());
        String err = processError(p.getProcInstId());
        Assert.assertTrue(err, err.startsWith("流程定义装载异常(process=" + p.getProcInstId() + "): "));
        Assert.assertTrue(err, err.contains("noSuchDef"));
    }

    // ===================== 好终态: 不写、并摘掉上一轮残留 =====================

    @Test
    public void successfulRunWritesNothingAndClearsStaleRecord() {
        publish("clean", "flow: \"x\"\nnodes:\n  x:\n    handler: ok\n");
        String pid = facade.create("clean", 1, null, "u1");
        Map<String, Object> stale = new LinkedHashMap<>();
        stale.put(Errors.KEY, "上一轮炸过");
        processMapper.updateState(pid, null, null, Json.write(stale), null, "u1");
        Assert.assertNotNull(processError(pid));

        ProcessExecution r = facade.start(pid);
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        Assert.assertNull("跑成功后不该还挂着失败记录", processError(pid));
        Assert.assertNull(r.getError());
        Assert.assertFalse(r.hasError());
        // 业务键之外没往流程行里塞别的东西(原有 result 内容照旧)
        Assert.assertTrue(Json.readMap(processMapper.findById(pid).getResult()).isEmpty());
    }

    /** 进度接口那一份口径不变: 仍从 action 行的保留键读 error/skipReason。 */
    @Test
    @SuppressWarnings("unchecked")
    public void progressStillReadsActionLevelError() {
        publish("prog", ""
                + "flow: \"x\"\n"
                + "nodes:\n  x:\n    handler: thrower\n");
        ProcessExecution r = run("prog");
        Map<String, Object> p = facade.progress(r.getProcInstId());
        Assert.assertEquals(1, p.get("total"));
        Map<String, Object> item = (Map<String, Object>) ((List<Object>) p.get("items")).get(0);
        Assert.assertEquals(Status.FAILED.getStatus(), item.get("status"));
        Assert.assertTrue(String.valueOf(item.get("error")).contains("handler boom"));
    }
}
