package com.tt.ii.process.integration;

import com.tt.ii.process.api.ProcessExecution;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.domain.NodeInstEntity;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;

/**
 * 流程级 setup/setdown 段(顶层三段: setup + flow + setdown)的**对等重臂**端到端(v1.1):
 *  - 流程级 setup 节点成功、flow 失败、流程级 setdown 段已还原(finally)后 retry:
 *    setup/setdown 根节点成对重跑(判据落在根行上, 与节点级 §LayerWalker.reArmSegments 同形);
 *  - 崩在"流程级 setdown 前"(现场仍生效)的补跑窗口: 成对重臂不触发(根行不复位);
 *  - 顶层 repeatSegments: 每趟驱动无条件重跑顶层 setup/setdown 段;
 *  - 装载校验(AU204217/218/219)在 DefinitionLoaderTest 覆盖, 此处只验运行语义。
 *
 * 装配与替身见 {@link EngineE2ETestBase}(内存表 + Mockito, 不起容器)。
 */
public class EngineProcessSegmentTest extends EngineE2ETestBase {

    /** 顶层三段故事: setup=全局暂停 -> flow=同步 -> setdown=全局恢复(finally 语义)。 */
    private static String topDef(String syncHandler) {
        return "setup: [gPause]\n"
                + "flow: \"gSync\"\n"
                + "setdown: [gResume]\n"
                + "nodes:\n"
                + "  gPause:\n    actions:\n      - id: pause\n        handler: ok\n"
                + "  gSync:\n    actions:\n      - id: sync\n        handler: " + syncHandler + "\n"
                + "  gResume:\n    actions:\n      - id: resume\n        handler: ok\n";
    }

    // ===================== 流程级成对重臂 =====================

    /** flow 失败 + 顶层 setdown 已跑(现场已撤) + retry: setup/setdown 根节点成对重跑, flow 在恢复好的现场上续跑。 */
    @Test
    public void processPairedReArmRerunsRootsAfterFlowRetry() {
        publish("topRearm", topDef("flaky"));
        ProcessExecution r1 = run("topRearm");
        Assert.assertEquals(Status.FAILED, r1.getStatus());
        String pid = r1.getProcInstId();
        Assert.assertEquals("首轮: setup 跑过", 1, Collections.frequency(rec.order, "handler:ok:pause"));
        Assert.assertEquals("首轮: setdown 照跑(finally)", 1, Collections.frequency(rec.order, "handler:ok:resume"));

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("setup 根节点重跑(现场重施)", 2, Collections.frequency(rec.order, "handler:ok:pause"));
        Assert.assertEquals("flow 在恢复好的现场上续跑", 2, Collections.frequency(rec.order, "handler:flaky:sync"));
        Assert.assertEquals("setdown 根节点重跑(重新收尾)", 2, Collections.frequency(rec.order, "handler:ok:resume"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), rootByDef(pid, "gPause").getStatus());
        Assert.assertEquals(Status.SUCCESS.getStatus(), rootByDef(pid, "gResume").getStatus());
    }

    /** 补跑窗口(现场仍生效): 主链成功、顶层 setdown 未跑 -> 成对重臂不触发, setup 不重跑、只补 setdown。 */
    @Test
    public void processCrashBeforeSetdownKeepsSetupUntouched() {
        publish("topCrash", topDef("ok"));
        ProcessExecution r1 = run("topCrash");
        Assert.assertEquals(Status.SUCCESS, r1.getStatus());
        String pid = r1.getProcInstId();

        // 倒回"flow 成、顶层 setdown 未跑"(模拟 owner 崩溃残留: 节点 running + resume 动作行 created + 流程 running)
        NodeInstEntity resume = rootByDef(pid, "gResume");
        ActionInstEntity resumeAct = actionByDef(pid, "resume");
        actionMapper.updateState(resumeAct.getActionInstId(), null, Status.CREATED.getStatus(), null, "u1");
        nodeMapper.setStatus(resume.getNodeInstId(), Status.RUNNING.getStatus(), "u1");
        processMapper.casStatus(pid, Status.SUCCESS.getStatus(), Status.RUNNING.getStatus(), "u1");

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("setup 不重跑(现场仍在)", 1, Collections.frequency(rec.order, "handler:ok:pause"));
        Assert.assertEquals("flow 已成功: 不重跑", 1, Collections.frequency(rec.order, "handler:ok:sync"));
        Assert.assertEquals("只补顶层 setdown", 2, Collections.frequency(rec.order, "handler:ok:resume"));
    }

    /** 顶层 repeatSegments: 每趟驱动无条件重跑顶层 setup/setdown 段(补跑窗口里也重跑)。 */
    @Test
    public void processRepeatSegmentsRerunsEveryDrive() {
        publish("topRepeat", "repeatSegments: true\n" + topDef("ok"));
        ProcessExecution r1 = run("topRepeat");
        Assert.assertEquals(Status.SUCCESS, r1.getStatus());
        String pid = r1.getProcInstId();

        NodeInstEntity resume = rootByDef(pid, "gResume");
        ActionInstEntity resumeAct = actionByDef(pid, "resume");
        actionMapper.updateState(resumeAct.getActionInstId(), null, Status.CREATED.getStatus(), null, "u1");
        nodeMapper.setStatus(resume.getNodeInstId(), Status.RUNNING.getStatus(), "u1");
        processMapper.casStatus(pid, Status.SUCCESS.getStatus(), Status.RUNNING.getStatus(), "u1");

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("每趟驱动顶层 setup 都重跑", 2, Collections.frequency(rec.order, "handler:ok:pause"));
        Assert.assertEquals("flow 已成功: 不重跑", 1, Collections.frequency(rec.order, "handler:ok:sync"));
        Assert.assertEquals("本趟收尾补跑", 2, Collections.frequency(rec.order, "handler:ok:resume"));
    }

    // ===================== 辅助 =====================

    private NodeInstEntity rootByDef(String pid, String defId) {
        for (NodeInstEntity n : nodeMapper.findRoots(pid)) {
            if (defId.equals(n.getNodeDefId())) {
                return n;
            }
        }
        Assert.fail("找不到根节点: " + defId);
        return null;
    }

    private ActionInstEntity actionByDef(String pid, String defId) {
        for (NodeInstEntity n : nodeMapper.findRoots(pid)) {
            for (ActionInstEntity a : actionMapper.find(n.getNodeInstId(), null)) {
                if (defId.equals(a.getActionDefId())) {
                    return a;
                }
            }
        }
        Assert.fail("找不到 action: " + defId);
        return null;
    }
}
