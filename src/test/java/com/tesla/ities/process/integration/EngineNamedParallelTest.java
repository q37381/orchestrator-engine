package com.tesla.ities.process.integration;

import com.tesla.ities.process.api.ProcessExecution;
import com.tesla.ities.process.core.model.ErrorPolicy;
import com.tesla.ities.process.core.model.NodeType;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.domain.ActionInstEntity;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.util.Json;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * 命名并行容器端到端: flow 里 {@code PARALLEL:标签(a,b)} 的命名后缀指向 nodes 下的一条纯容器声明,
 * 于是并行也拿到"就近声明"的三项容器能力——生效策略、guard、config + 节点级监听器链。
 *
 * 钉的四件事:
 *  1. **同一份定义里两条并行各有各的策略**(这正是匿名 PARALLEL 表达不出来的那一件事);
 *  2. guard 假 = 整条并行 SKIPPED, 一个子项都不物化(与 GROUP 同口径, 匿名并行不受影响);
 *  3. 容器级 config / 节点级监听器链确实挂得上(证明 attachCursor 认了 Parallel.name);
 *  4. 进度视图的 groups 里一个命名容器一行, 匿名并行不掺进来。
 *
 * 注意: 并行的 FAIL_FAST 是 drain 语义(子任务已全部提交, cancel 只撤还在排队的), 所以**不**用
 * "兄弟有没有跑"来断言策略生效——那取决于线程池饱和度, 不是策略语义。策略生效的硬证据是行上
 * 物化期冻结的 error_policy 值(第 1 条)与顶层根序列的收敛(那层永远只看全局策略)。
 */
public class EngineNamedParallelTest extends EngineE2ETestBase {

    // ============ 1) 各条并行各有各的生效策略 ============

    @Test
    public void eachNamedParallelCarriesItsOwnPolicy() {
        publish("parPolicy", ""
                + "errorPolicy: CONTINUE_ON_ERROR\n"                 // 全局: 逐项跑完
                + "flow: \"PARALLEL:strict(a, b),PARALLEL(c, d)\"\n"
                + "nodes:\n"
                + "  strict:\n    errorPolicy: FAIL_FAST\n"          // 这一条自己覆盖: 遇坏即停
                + "  a:\n    handler: ok\n  b:\n    handler: ok\n"
                + "  c:\n    handler: ok\n  d:\n    handler: ok\n");
        ProcessExecution r = run("parPolicy");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        List<NodeInstEntity> roots = nodeMapper.findRoots(r.getProcInstId());
        Assert.assertEquals(2, roots.size());
        Assert.assertEquals(NodeType.PARALLEL.getStatus(), roots.get(0).getType());
        Assert.assertEquals("命名并行吃自己声明的生效值",
                ErrorPolicy.FAIL_FAST.getStatus(), roots.get(0).getErrorPolicy());
        Assert.assertEquals("匿名并行照旧吃全局",
                ErrorPolicy.CONTINUE_ON_ERROR.getStatus(), roots.get(1).getErrorPolicy());
        Assert.assertTrue("四个子节点都跑到了",
                rec.order.contains("handler:ok:a") && rec.order.contains("handler:ok:b")
                        && rec.order.contains("handler:ok:c") && rec.order.contains("handler:ok:d"));
    }

    /** 命名并行也能只声明策略就覆盖全局(不写则仍回落全局), 且非法声明值不被当成"声明了"。 */
    @Test
    public void illegalDeclarationFallsBackToGlobal() {
        publish("parTypo", ""
                + "errorPolicy: CONTINUE_ON_ERROR\n"
                + "flow: \"PARALLEL:checks(a)\"\n"
                + "nodes:\n  checks:\n    errorPolicy: contin_on_errr\n  a:\n    handler: ok\n");
        ProcessExecution r = run("parTypo");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        Assert.assertEquals("声明值非法 = 没声明, 回落全局(而不是悄悄变 FAIL_FAST)",
                ErrorPolicy.CONTINUE_ON_ERROR.getStatus(),
                nodeMapper.findRoots(r.getProcInstId()).get(0).getErrorPolicy());
    }

    // ============ 2) 容器级 guard ============

    @Test
    public void guardFalseSkipsWholeNamedParallel() {
        publish("parGuard", ""
                + "flow: \"PARALLEL:checks(a),tail\"\n"
                + "nodes:\n"
                + "  checks:\n    when: \"${vars.absent} == true\"\n"    // vars.absent 未定义 -> 条件假
                + "  a:\n    handler: ok\n"
                + "  tail:\n    handler: ok\n");
        ProcessExecution r = run("parGuard");
        Assert.assertEquals("跳过不算失败", Status.SUCCESS, r.getStatus());

        NodeInstEntity par = nodeMapper.findRoots(r.getProcInstId()).get(0);
        Assert.assertEquals(Status.SKIPPED.getStatus(), par.getStatus());
        Assert.assertTrue("整条并行免跑 -> 子节点从未物化",
                nodeMapper.findChildren(r.getProcInstId(), par.getNodeInstId()).isEmpty());
        Assert.assertFalse("被跳过的子节点没跑", rec.order.contains("handler:ok:a"));
        Assert.assertTrue("同一层其余根不受影响(SKIPPED 不触发顶层 fail-fast)",
                rec.order.contains("handler:ok:tail"));
    }

    @Test
    public void guardTrueDrivesChildren() {
        publish("parGuardOn", ""
                + "vars:\n  runChecks: true\n"
                + "flow: \"PARALLEL:checks(a)\"\n"
                + "nodes:\n"
                + "  checks:\n    when: \"${vars.runChecks} == true\"\n"
                + "  a:\n    handler: ok\n");
        ProcessExecution r = run("parGuardOn");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        Assert.assertTrue(rec.order.contains("handler:ok:a"));
    }

    // ============ 3) 容器级 config 与节点级监听器链 ============

    /** 容器 config 落进行上的 config 列(PLAN 冻结), 子节点沿上下文父链上溯读得到——与 GROUP 同一条机制。 */
    @Test
    @SuppressWarnings("unchecked")
    public void containerConfigReadableByChildren() {
        publish("parCfg", ""
                + "flow: \"PARALLEL:checks(probe)\"\n"
                + "nodes:\n"
                + "  checks:\n    config:\n      threshold: 5\n"
                + "  probe:\n"
                + "    handler: echoConfig\n"
                + "    config:\n      t: \"${config.threshold}\"\n");
        ProcessExecution r = run("parCfg");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        NodeInstEntity par = nodeMapper.findRoots(r.getProcInstId()).get(0);
        NodeInstEntity leaf = nodeMapper.findChildren(r.getProcInstId(), par.getNodeInstId()).get(0);
        ActionInstEntity act = actionMapper.findInContainer(leaf.getNodeInstId(), null).get(0);
        Map<String, Object> res = Json.readMap(act.getResult());
        Assert.assertNull("占位符全部解出", res.get("__unresolved"));
        Assert.assertEquals(5, ((Map<String, Object>) res.get("cfg")).get("t"));
    }

    /** 声明挂在容器上就能带节点级监听器: 行上绑到了 NodeDef, nodeRef() 才报得出定义键。 */
    @Test
    public void containerNodeListenerChainFires() {
        publish("parListener", ""
                + "flow: \"PARALLEL:checks(a)\"\n"
                + "nodes:\n"
                + "  checks:\n    listeners:\n      - name: nRecord\n"
                + "  a:\n    handler: ok\n");
        ProcessExecution r = run("parListener");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        Assert.assertTrue("命名并行行的节点级链已合并生效(匿名行拿不到这条)",
                rec.order.contains("nodeBefore:checks"));
    }

    // ============ 4) 进度视图: 一个命名容器一行 ============

    @Test
    @SuppressWarnings("unchecked")
    public void progressGroupsOneRowPerNamedContainer() {
        publish("parProg", ""
                + "materialization: EAGER\n"
                + "flow: \"PARALLEL:checks(a, b),PARALLEL(c, d)\"\n"
                + "nodes:\n"
                + "  checks:\n    name: 校验清单\n    errorPolicy: CONTINUE_ON_ERROR\n"
                + "  a:\n    handler: ok\n  b:\n    handler: ok\n  c:\n    handler: ok\n  d:\n    handler: ok\n");
        ProcessExecution r = run("parProg");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        Map<String, Object> p = facade.progress(r.getProcInstId());
        Assert.assertEquals("扁平口径仍是全部 action 行", 4, p.get("total"));

        List<Map<String, Object>> groups = (List<Map<String, Object>>) p.get("groups");
        Assert.assertEquals("命名容器一行, 匿名并行不掺进来", 1, groups.size());
        Map<String, Object> checks = groups.get(0);
        Assert.assertEquals("checks", checks.get("group"));
        Assert.assertEquals("校验清单", checks.get("name"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), checks.get("status"));
        Assert.assertEquals(2, checks.get("total"));
        Assert.assertEquals(2, checks.get("done"));
    }
}
