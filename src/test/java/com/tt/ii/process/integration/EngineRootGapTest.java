package com.tt.ii.process.integration;

import com.tt.ii.process.api.ProcessExecution;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.domain.NodeInstEntity;
import com.tt.ii.process.util.Json;
import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RootsWalk 的 flow 失败 + setdown finally 口径:
 * 失败点之后的 flow 根不能被补成 CREATED 孤儿; 为保持 auto_id 下标对齐而补位的行落 STOPPED/DRAIN,
 * setdown 根本身照常补建并完整执行。retry 时这些 STOPPED 行在复位集内, 修好后可继续跑。
 */
public class EngineRootGapTest extends EngineE2ETestBase {

    private static String gapFlow() {
        return "setup: [s]\n"
                + "flow: \"a,b,c\"\n"
                + "setdown: [d]\n"
                + "nodes:\n"
                + "  s:\n    handler: ok\n"
                + "  a:\n    handler: ok\n"
                + "  b:\n    handler: flaky\n"
                + "  c:\n    handler: ok\n"
                + "  d:\n    handler: ok\n";
    }

    private Map<String, NodeInstEntity> rootsByDef(String procInstId) {
        Map<String, NodeInstEntity> out = new LinkedHashMap<>();
        for (NodeInstEntity n : nodeMapper.findRoots(procInstId)) {
            out.put(n.getNodeDefId(), n);
        }
        return out;
    }

    /** flow 在 b 失败(FAIL_FAST): c 不执行, 但为 setdown 保位落 STOPPED/DRAIN; d 照常 finally 执行。 */
    @Test
    public void failedFlowDoesNotLeaveLaterRootsCreated() {
        publish("rootGap", gapFlow());
        ProcessExecution r = run("rootGap");
        Assert.assertEquals(Status.FAILED, r.getStatus());

        Map<String, NodeInstEntity> roots = rootsByDef(r.getProcInstId());
        Assert.assertEquals(Status.SUCCESS.getStatus(), roots.get("a").getStatus());
        Assert.assertEquals(Status.FAILED.getStatus(), roots.get("b").getStatus());
        Assert.assertEquals("失败点之后的 flow 根不作为 CREATED 孤儿",
                Status.STOPPED.getStatus(), roots.get("c").getStatus());
        Assert.assertEquals("保位行带 DRAIN 留痕",
                "DRAIN", Json.readMap(roots.get("c").getResult()).get("__skipReason"));
        Assert.assertEquals("setdown 仍按 finally 完整执行",
                Status.SUCCESS.getStatus(), roots.get("d").getStatus());
    }

    /** retry 把保位的 STOPPED 行复位后, c 与 d 都能继续执行到成功。 */
    @Test
    public void retryRunsDrainedGapRoots() {
        publish("rootGapRetry", gapFlow());
        ProcessExecution first = run("rootGapRetry");
        Assert.assertEquals(Status.FAILED, first.getStatus());

        // 修好现场: 仅把 b 的失败动作复位(与人工 retry 的复位集口径一致)
        Assert.assertTrue(facade.retry(first.getProcInstId(), "u1"));
        ProcessExecution second = facade.start(first.getProcInstId());
        Assert.assertEquals(Status.SUCCESS, second.getStatus());

        Map<String, NodeInstEntity> roots = rootsByDef(first.getProcInstId());
        Assert.assertEquals(Status.SUCCESS.getStatus(), roots.get("c").getStatus());
        Assert.assertEquals(Status.SUCCESS.getStatus(), roots.get("d").getStatus());
    }
}
