package com.tesla.ities.process.integration;

import com.tesla.ities.process.api.ProcessExecution;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.domain.NodeInstEntity;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * **序列收敛的就近声明**端到端: 一条串行序列(顶层根序列 / GROUP 组内)遇坏要不要就地收敛,
 * 先看**失败那一项自己**声明的 errorPolicy, 没声明才回落本层(根序列 = 流程级全局, 组 = 组声明的生效值)。
 *
 * 钉四件事:
 *  1. 全局 CONTINUE_ON_ERROR 里, 命名并行({@code PARALLEL:strict})自己声明 FAIL_FAST 且失败 -> 后续根不跑;
 *  2. 反方向对称: 全局 FAIL_FAST(默认)里, 某项声明 CONTINUE_ON_ERROR 且失败 -> 后续根照跑;
 *  3. 没声明/声明值非法一律回落本层(匿名并行与旧行为逐字节不变);
 *  4. 就近声明**只作用于根序列这一层**——容器内部(组内子节点/并行扇出)的收敛不看子项声明, 仍只看容器自己那一条。
 *
 * 并行**容器内部**的扇出不在本类断言范围: 兄弟是并发提交的, "谁没跑起来"取决于线程池饱和度而非策略语义
 * (见 {@link EngineNamedParallelTest}), 这里只断言**上层序列**放没放行。
 */
public class EngineErrorPolicyScopeTest extends EngineE2ETestBase {

    // ============ 1) 关键项: 全局放行, 它偏要停 ============

    @Test
    public void namedParallelFailFastStopsRemainingRoots() {
        publish("scopeParStop", ""
                + "errorPolicy: CONTINUE_ON_ERROR\n"                  // 流程整体逐项跑完
                + "flow: \"PARALLEL:strict(boom, mate),tail\"\n"
                + "nodes:\n"
                + "  strict:\n    errorPolicy: FAIL_FAST\n"           // 就近声明: 这条并行是关键项, 坏了叫停
                + "  boom:\n    handler: thrower\n"
                + "  mate:\n    handler: ok\n"
                + "  tail:\n    handler: ok\n");
        ProcessExecution r = run("scopeParStop");
        Assert.assertEquals(Status.FAILED, r.getStatus());

        Assert.assertFalse("后续根没跑起来", rec.order.contains("handler:ok:tail"));
        List<NodeInstEntity> roots = nodeMapper.findRoots(r.getProcInstId());
        Assert.assertEquals("LAZY 下被叫停的根连节点行都没物化", 1, roots.size());
    }

    /** 全局 FAIL_FAST(默认)里给某一项标 CONTINUE_ON_ERROR = "我失败也别拦后面", 与第 1 条对称。 */
    @Test
    public void nodeContinueOnErrorLetsRemainingRootsPass() {
        publish("scopeLeafPass", ""
                + "flow: \"soft,tail\"\n"                             // 没写全局 = 默认 FAIL_FAST
                + "nodes:\n"
                + "  soft:\n    handler: thrower\n    errorPolicy: CONTINUE_ON_ERROR\n"
                + "  tail:\n    handler: ok\n");
        ProcessExecution r = run("scopeLeafPass");
        Assert.assertEquals("失败仍计入终态", Status.FAILED, r.getStatus());
        Assert.assertTrue("但这一项声明了失败不拦路, 后续根照跑", rec.order.contains("handler:ok:tail"));
    }

    // ============ 2) 没吭声的一律回落本层(旧行为不变) ============

    /** 匿名 PARALLEL 无 NodeDef 可声明 -> 只吃全局, 坏了也放行(与改造前逐字一致)。 */
    @Test
    public void undeclaredRootFollowsGlobalLayer() {
        publish("scopeParFollowsGlobal", ""
                + "errorPolicy: CONTINUE_ON_ERROR\n"
                + "flow: \"PARALLEL(boom, mate),tail\"\n"
                + "nodes:\n"
                + "  boom:\n    handler: thrower\n"
                + "  mate:\n    handler: ok\n"
                + "  tail:\n    handler: ok\n");
        ProcessExecution r = run("scopeParFollowsGlobal");
        Assert.assertEquals(Status.FAILED, r.getStatus());
        Assert.assertTrue("并行容器没声明 -> 回落全局 CONTINUE_ON_ERROR, 后续根照跑",
                rec.order.contains("handler:ok:tail"));
    }

    /** 子节点上的 FAIL_FAST 只管它自己容器内, 拦不住并行这一层, 更拦不住根序列(旧误区的回归)。 */
    @Test
    public void childDeclarationDoesNotReachTheRootSequence() {
        publish("scopeParChildOnly", ""
                + "errorPolicy: CONTINUE_ON_ERROR\n"
                + "flow: \"PARALLEL(boom, mate),tail\"\n"
                + "nodes:\n"
                + "  boom:\n    handler: thrower\n    errorPolicy: FAIL_FAST\n"   // 声明挂在叶子上
                + "  mate:\n    handler: ok\n"
                + "  tail:\n    handler: ok\n");
        ProcessExecution r = run("scopeParChildOnly");
        Assert.assertEquals(Status.FAILED, r.getStatus());
        Assert.assertTrue("要叫停根序列得声明在并行容器上(命名后缀), 叶子声明只管自己",
                rec.order.contains("handler:ok:tail"));
    }

    /** 声明了但拼错 = 没声明, 回落本层, 不会"错个值反倒把流程停了"。 */
    @Test
    public void illegalDeclarationIsNotADeclaration() {
        publish("scopeTypo", ""
                + "errorPolicy: CONTINUE_ON_ERROR\n"
                + "flow: \"PARALLEL:strict(boom, mate),tail\"\n"
                + "nodes:\n"
                + "  strict:\n    errorPolicy: fail_fastt\n"
                + "  boom:\n    handler: thrower\n"
                + "  mate:\n    handler: ok\n"
                + "  tail:\n    handler: ok\n");
        ProcessExecution r = run("scopeTypo");
        Assert.assertEquals(Status.FAILED, r.getStatus());
        Assert.assertTrue("非法声明回落全局 CONTINUE_ON_ERROR", rec.order.contains("handler:ok:tail"));
    }

    // ============ 3) 聚合节点作为根: 组行自己声明也能叫停根序列 ============

    /**
     * 聚合节点(GROUP)作为根时, 组行的生效值参与根序列的收敛判定(命名容器三条路都通)。
     * 注意口径: 就近声明**只作用于根序列这一层**, 组内子节点声明不跨层(见 GroupStrategy)。
     */
    @Test
    public void groupFailFastStopsRemainingRoots() {
        publish("scopeGroupRootStop", ""
                + "errorPolicy: CONTINUE_ON_ERROR\n"
                + "flow: \"g,tail\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    errorPolicy: FAIL_FAST\n"
                + "    group:\n"
                + "      flow: \"boom\"\n"
                + "      nodes:\n"
                + "        boom:\n          handler: thrower\n"
                + "  tail:\n    handler: ok\n");
        ProcessExecution r = run("scopeGroupRootStop");
        Assert.assertEquals(Status.FAILED, r.getStatus());
        Assert.assertFalse(rec.order.contains("handler:ok:tail"));
    }
}
