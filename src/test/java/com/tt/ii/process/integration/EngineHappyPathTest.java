package com.tt.ii.process.integration;

import com.tt.ii.process.api.ProcessExecution;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.domain.LaneEntity;
import com.tt.ii.process.domain.NodeInstEntity;
import com.tt.ii.process.util.Json;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * 引擎端到端主链: 门面上进, 驱动整棵 flow, 覆盖
 * ExecutionEngine/NodeDriver/Materializer/ActionKind/ParallelKind/IfKind/GroupKind/
 * ContextFactory/DefinitionCache/PlanService/ProgressService/ProcessFacade 的正常与常见分支。
 *
 * 装配与替身见 {@link EngineE2ETestBase}(内存表 + Mockito, 不起容器)。defId 只在单个用例内有效,
 * 每个用例都是全新装配, 因此不存在跨用例/跨类的定义缓存串味。
 */
public class EngineHappyPathTest extends EngineE2ETestBase {

    // ============ 1) 顺序 happy: setup + flow 多根 + setdown, 流程级监听器 + vars 刷回 ============

    @Test
    public void sequentialHappyPathDrivesAllRootsAndSetdown() {
        publish("seq", ""
                + "materialization: LAZY\n"
                + "flow: \"s1,s2\"\n"
                + "setup:\n  - pre\n"
                + "setdown:\n  - post\n"
                + "listeners:\n  - name: pRecord\n"
                + "nodes:\n"
                + "  pre:\n    handler: ok\n"
                + "  s1:\n    handler: ok\n"
                + "  s2:\n    handler: varWriter\n"
                + "  post:\n    handler: ok\n");

        ProcessExecution r = run("seq");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        Assert.assertTrue(r.isTerminal());
        Assert.assertTrue(r.isSuccess());

        // 4 个根行(pre/s1/s2/post), 每个 1 action
        Assert.assertEquals(4, nodeMapper.findRoots(r.getProcInstId()).size());
        int success = 0;
        for (NodeInstEntity n : nodeMapper.findRoots(r.getProcInstId())) {
            for (ActionInstEntity a : actionMapper.findInContainer(n.getNodeInstId(), null)) {
                if (Status.SUCCESS.getStatus().equals(a.getStatus())) {
                    success++;
                }
            }
        }
        Assert.assertEquals(4, success);
        // 流程级监听器 before/after 各一次
        Assert.assertEquals(1, rec.processBefore.get());
        Assert.assertEquals(1, rec.processAfter.get());
        // varWriter putVar 触发脏刷: marker 落进流程行 vars
        Assert.assertTrue(processMapper.findById(r.getProcInstId()).getVars().contains("marker"));
        // 没人写流程级 result 就不落(不凭空刷一份空 JSON)
        Assert.assertNull(processMapper.findById(r.getProcInstId()).getResult());
    }

    // ============ 1b) 流程级 result 脏刷落库(与 vars 同一口径) ============

    @Test
    public void processLevelResultWrittenByListenerIsPersisted() {
        publish("presult", ""
                + "flow: \"s\"\n"
                + "listeners:\n  - {name: pRecord, config: {writeResult: done}}\n"
                + "nodes:\n  s:\n    handler: ok\n");

        ProcessExecution r = run("presult");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        String raw = processMapper.findById(r.getProcInstId()).getResult();
        Assert.assertNotNull("流程级 ctx.putResult 应脏刷进流程行 result", raw);
        Assert.assertEquals("done", Json.readMap(raw).get("listenerResult"));
    }

    // ============ 2) EAGER 预建节点树 + PARALLEL ============

    @Test
    public void eagerParallelBuildsAllRowsAtCreateTime() {
        publish("par", ""
                + "materialization: EAGER\n"
                + "flow: \"PARALLEL(a,b)\"\n"
                + "nodes:\n"
                + "  a:\n    handler: ok\n"
                + "  b:\n    handler: ok\n");

        // 只 create 不 start: EAGER 创建期建全 PARALLEL 根 + 两个子行(action 属执行细节, 驱动期才建)
        String pid = facade.create("par", 1, null, "u1");
        List<NodeInstEntity> roots = nodeMapper.findRoots(pid);
        Assert.assertEquals(1, roots.size());
        Assert.assertEquals("parallel", roots.get(0).getType());
        Assert.assertEquals(2, nodeMapper.findChildren(pid, roots.get(0).getNodeInstId()).size());

        ProcessExecution r = facade.start(pid);
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
    }

    // ============ 3) PARALLEL 失败(默认 FAIL_FAST) ============

    @Test
    public void parallelFailFastAggregatesFailure() {
        publish("parfail", ""
                + "flow: \"PARALLEL(bad, good)\"\n"
                + "nodes:\n"
                + "  bad:\n    handler: thrower\n"
                + "  good:\n    handler: ok\n");
        ProcessExecution r = run("parfail");
        Assert.assertEquals(Status.FAILED, r.getStatus());
        Assert.assertFalse(r.isSuccess());
    }

    // ============ 4) CONTINUE_ON_ERROR 节点内 action 全跑, FAIL_FAST drain ============

    @Test
    public void continueOnErrorRunsAllActions() {
        publish("cont", ""
                + "flow: \"cn\"\n"
                + "nodes:\n"
                + "  cn:\n"
                + "    errorPolicy: CONTINUE_ON_ERROR\n"
                + "    actions:\n"
                + "      - {id: one, handler: thrower}\n"
                + "      - {id: two, handler: ok}\n");
        ProcessExecution r = run("cont");
        Assert.assertEquals(Status.FAILED, r.getStatus());
        NodeInstEntity node = nodeMapper.findRoots(r.getProcInstId()).get(0);
        // 两个 action 都建了行且都跑了(continue 不 break): one=failed, two=success
        List<ActionInstEntity> acts = actionMapper.findInContainer(node.getNodeInstId(), null);
        Assert.assertEquals(2, acts.size());
        Assert.assertEquals("failed", acts.get(0).getStatus());
        Assert.assertEquals("success", acts.get(1).getStatus());
    }

    @Test
    public void failFastLeavesUnstartedSiblingsUnmaterialized() {
        // lane/action 创建期不预建: FAIL_FAST 遇失败即停, 没跑到的第二个 action 连行都没建(不留孤儿 created)
        publish("drain", ""
                + "materialization: EAGER\n"
                + "flow: \"n\"\n"
                + "nodes:\n"
                + "  n:\n"
                + "    errorPolicy: FAIL_FAST\n"
                + "    actions:\n"
                + "      - {id: one, handler: thrower}\n"
                + "      - {id: two, handler: ok}\n");
        ProcessExecution r = run("drain");
        Assert.assertEquals(Status.FAILED, r.getStatus());
        NodeInstEntity node = nodeMapper.findRoots(r.getProcInstId()).get(0);
        List<ActionInstEntity> acts = actionMapper.findInContainer(node.getNodeInstId(), null);
        Assert.assertEquals("只有执行过的那条建了行, 未跑到的兄弟根本无行", 1, acts.size());
        Assert.assertEquals("failed", acts.get(0).getStatus());
    }

    // ============ 5) IF: then / else / 无 else 跳过 + 未选支留痕行 ============

    @Test
    public void ifPicksThenBranch() {
        publish("ifthen", ""
                + "vars:\n  mode: fast\n"
                + "flow: \"IF(${vars.mode}=='fast', fastNode, slowNode)\"\n"
                + "nodes:\n"
                + "  fastNode:\n    name: 快速通道\n    handler: ok\n"
                + "  slowNode:\n    name: 慢速通道\n    handler: ok\n");
        ProcessExecution r = run("ifthen");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        // 选中支被驱动, 未选支另有一行留痕: 1 个 IF 根 + 2 个子行
        List<NodeInstEntity> roots = nodeMapper.findRoots(r.getProcInstId());
        Assert.assertEquals(1, roots.size());
        Assert.assertEquals("if", roots.get(0).getType());
        List<NodeInstEntity> kids = nodeMapper.findChildren(r.getProcInstId(), roots.get(0).getNodeInstId());
        Assert.assertEquals(2, kids.size());
        Assert.assertEquals("选中支恒占第 0 格(留痕行只能接在它后面)", "fastNode", kids.get(0).getNodeDefId());
        Assert.assertEquals(Status.SUCCESS.getStatus(), kids.get(0).getStatus());
        assertUntakenBranch(kids.get(1), "slowNode", "慢速通道");
        // IF 行自带未选支自述: 不 join 子行也问得出跳过了哪一支
        Map<String, Object> cfg = Json.readMap(roots.get(0).getConfig());
        Assert.assertEquals("then", cfg.get("__chosen"));
        Assert.assertEquals("slowNode", cfg.get("__skippedBranch"));
    }

    @Test
    public void ifPicksElseBranch() {
        publish("ifelse", ""
                + "vars:\n  mode: slow\n"
                + "flow: \"IF(${vars.mode}=='fast', fastNode, slowNode)\"\n"
                + "nodes:\n"
                + "  fastNode:\n    name: 快速通道\n    handler: ok\n"
                + "  slowNode:\n    name: 慢速通道\n    handler: ok\n");
        ProcessExecution r = run("ifelse");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        NodeInstEntity root = nodeMapper.findRoots(r.getProcInstId()).get(0);
        List<NodeInstEntity> kids = nodeMapper.findChildren(r.getProcInstId(), root.getNodeInstId());
        // 走的是 slowNode(第 0 格): 其 action 行已 success; fastNode 只留下未选支留痕行
        Assert.assertEquals("slowNode", kids.get(0).getNodeDefId());
        Assert.assertEquals("success", actionMapper.findInContainer(kids.get(0).getNodeInstId(), null).get(0).getStatus());
        assertUntakenBranch(kids.get(1), "fastNode", "快速通道");
        Map<String, Object> cfg = Json.readMap(root.getConfig());
        Assert.assertEquals("else", cfg.get("__chosen"));
        Assert.assertEquals("fastNode", cfg.get("__skippedBranch"));
    }

    @Test
    public void ifWithoutElseAndFalseConditionSkips() {
        publish("ifnone", ""
                + "vars:\n  mode: never\n"
                + "flow: \"IF(${vars.mode}=='fast', onlyNode)\"\n"
                + "nodes:\n"
                + "  onlyNode:\n    name: 唯一支\n    handler: ok\n");
        ProcessExecution r = run("ifnone");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());   // SKIPPED 对流程算完成
        NodeInstEntity root = nodeMapper.findRoots(r.getProcInstId()).get(0);
        Assert.assertEquals(Status.SKIPPED.getStatus(), root.getStatus());
        // 无 else 可走也留得下痕迹: 被跳过的就是 then 支(没有留痕行时它只能靠 config 里的文本对号)
        List<NodeInstEntity> kids = nodeMapper.findChildren(r.getProcInstId(), root.getNodeInstId());
        Assert.assertEquals(1, kids.size());
        assertUntakenBranch(kids.get(0), "onlyNode", "唯一支");
        Map<String, Object> cfg = Json.readMap(root.getConfig());
        Assert.assertEquals("else", cfg.get("__chosen"));
        Assert.assertEquals("onlyNode", cfg.get("__skippedBranch"));
    }

    /** 重入不重复插行、也不抢下标: 选中支让渡后再接着跑, 留痕行仍然只有一行且仍在第 1 格。 */
    @Test
    public void ifReentryKeepsUntakenBranchMarkerSingle() {
        publish("ifyield", ""
                + "vars:\n  mode: fast\n"
                + "flow: \"IF(${vars.mode}=='fast', fastNode, slowNode)\"\n"
                + "nodes:\n"
                + "  fastNode:\n    actions:\n      - id: work\n        handler: pendingOnce\n"
                + "  slowNode:\n    name: 慢速通道\n    handler: ok\n");
        ProcessExecution r1 = run("ifyield");
        String pid = r1.getProcInstId();
        Assert.assertEquals("选中支让渡: 流程落 pending", Status.PENDING, r1.getStatus());
        NodeInstEntity root = nodeMapper.findRoots(pid).get(0);
        Assert.assertEquals("让渡轮已补上未选支留痕", 2,
                nodeMapper.findChildren(pid, root.getNodeInstId()).size());
        Assert.assertEquals("让渡不拖累留痕行: 插行那一轮就翻成终态(不留 CREATED 窗口)",
                Status.SKIPPED.getStatus(),
                nodeMapper.findChildren(pid, root.getNodeInstId()).get(1).getStatus());

        ProcessExecution r2 = facade.start(pid);
        Assert.assertEquals(Status.SUCCESS, r2.getStatus());
        List<NodeInstEntity> kids = nodeMapper.findChildren(pid, root.getNodeInstId());
        Assert.assertEquals("重入不重复插行", 2, kids.size());
        Assert.assertEquals("第 0 格仍是选中支(已跑成)", "fastNode", kids.get(0).getNodeDefId());
        Assert.assertEquals(Status.SUCCESS.getStatus(), kids.get(0).getStatus());
        assertUntakenBranch(kids.get(1), "slowNode", "慢速通道");
    }

    /**
     * 未选支的留痕行: 有键有名、终态 skipped、result 记 __skipReason=BRANCH_NOT_TAKEN,
     * 且底下**没有任何 action 行**(没被驱动过)。displayName 传 null = 只校行已跳过。
     */
    private void assertUntakenBranch(NodeInstEntity marker, String nodeDefId, String displayName) {
        Assert.assertEquals(nodeDefId, marker.getNodeDefId());
        if (displayName != null) {
            Assert.assertEquals(displayName, marker.getName());
        }
        Assert.assertEquals(Status.SKIPPED.getStatus(), marker.getStatus());
        Assert.assertEquals("没被选中就不该跑过 handler",
                0, actionMapper.findInContainer(marker.getNodeInstId(), null).size());
        Assert.assertEquals("BRANCH_NOT_TAKEN", Json.readMap(marker.getResult()).get("__skipReason"));
    }

    // ============ 6) 分片: SERIAL 与 PARALLEL lane, payload 现场重建 ============

    @Test
    public void shardedSerialLanesRunPerShard() {
        publish("shardSerial", ""
                + "flow: \"shardSerial\"\n"
                + "nodes:\n"
                + "  shardSerial:\n"
                + "    handler: dbMigrate\n"
                + "    shard: dbTopology\n"
                + "    laneExecution: SERIAL\n");
        ProcessExecution r = run("shardSerial");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        NodeInstEntity node = nodeMapper.findRoots(r.getProcInstId()).get(0);
        // dbTopology 一主两从 = 3 lane
        List<LaneEntity> lanes = laneMapper.findByNode(node.getNodeInstId());
        Assert.assertEquals(3, lanes.size());
        for (LaneEntity l : lanes) {
            Assert.assertEquals(Status.SUCCESS.getStatus(), laneMapper.findById(l.getLaneId()).getStatus());
        }
    }

    @Test
    public void shardedParallelLanesFanOut() {
        publish("shardPar", ""
                + "flow: \"shardPar\"\n"
                + "nodes:\n"
                + "  shardPar:\n"
                + "    handler: dbMigrate\n"
                + "    shard: dbTopology\n"
                + "    laneExecution: PARALLEL\n");
        ProcessExecution r = run("shardPar");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        NodeInstEntity node = nodeMapper.findRoots(r.getProcInstId()).get(0);
        Assert.assertEquals(3, laneMapper.findByNode(node.getNodeInstId()).size());
    }

    /** lane 级 result: handler 显式放进 lane 桶的交接值随 lane 收尾落库(status + result 同一条 UPDATE)。 */
    @Test
    public void shardedLaneResultPersistsAtTerminal() {
        publish("laneRes", ""
                + "flow: \"laneRes\"\n"
                + "nodes:\n"
                + "  laneRes:\n"
                + "    handler: flagTrue\n"
                + "    shard: dbTopology\n");
        ProcessExecution r = run("laneRes");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        NodeInstEntity node = nodeMapper.findRoots(r.getProcInstId()).get(0);
        List<LaneEntity> lanes = laneMapper.findByNode(node.getNodeInstId());
        Assert.assertEquals(3, lanes.size());
        for (LaneEntity l : lanes) {
            Assert.assertEquals(Status.SUCCESS.getStatus(), l.getStatus());
            // 单 handler 节点的 action 与节点 1:1, 动作 id = 节点定义 id; 值由 flagTrue 挂到所属 lane 桶
            Object placed = Json.readMap(l.getResult()).get("laneRes");
            Assert.assertNotNull("lane 行 result 未落库: " + l.getShardKey(), placed);
            Assert.assertEquals(true, ((Map<?, ?>) placed).get("ok"));
        }
    }

    // ============ 7) 声明式 guard: action when 假 -> 跳过; 节点 when 假 -> 整节点跳过 ============

    @Test
    public void actionGuardFalseSkips() {
        publish("guard", ""
                + "flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    actions:\n"
                + "      - {id: gate, handler: flagTrue}\n"
                + "      - {id: skipped, handler: thrower, when: \"${result.gate.ok} == false\"}\n");
        ProcessExecution r = run("guard");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        NodeInstEntity node = nodeMapper.findRoots(r.getProcInstId()).get(0);
        List<ActionInstEntity> acts = actionMapper.findInContainer(node.getNodeInstId(), null);
        Assert.assertEquals("success", acts.get(0).getStatus());
        Assert.assertEquals("skipped", acts.get(1).getStatus());
        Assert.assertEquals("GUARD_FALSE", Json.readMap(acts.get(1).getResult()).get("__skipReason"));
    }

    @Test
    public void nodeGuardFalseSkipsWholeNode() {
        publish("nodeguard", ""
                + "flow: \"sk\"\n"
                + "nodes:\n"
                + "  sk:\n"
                + "    handler: thrower\n"
                + "    when: \"${vars.absent} == true\"\n");   // vars.absent 未定义 -> 条件 false
        ProcessExecution r = run("nodeguard");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        NodeInstEntity node = nodeMapper.findRoots(r.getProcInstId()).get(0);
        Assert.assertEquals(Status.SKIPPED.getStatus(), node.getStatus());
        // LAZY 下节点被 guard 跳过, 其 action 从未执行(未物化)
        Assert.assertTrue(actionMapper.findInContainer(node.getNodeInstId(), null).isEmpty());
    }

    // ============ 8) 进度查询 ============

    @Test
    public void progressAggregatesActionCounts() {
        publish("prog", ""
                + "materialization: EAGER\n"
                + "flow: \"p1,p2\"\n"
                + "nodes:\n"
                + "  p1:\n    handler: ok\n"
                + "  p2:\n    handler: skipper\n");
        ProcessExecution r = run("prog");
        Map<String, Object> p = facade.progress(r.getProcInstId());
        Assert.assertEquals(r.getProcInstId(), p.get("procInstId"));
        Assert.assertEquals(2, p.get("total"));
        Assert.assertEquals(2, p.get("done"));   // success + skipped 都算 done
        Assert.assertEquals(100, p.get("percent"));
    }

    // ============ 9) start 分支: 行不存在 / 已终态 ============

    @Test
    public void startOnMissingRowReturnsNull() {
        ProcessExecution r = facade.start("no-such-proc");
        Assert.assertNull(r.getStatus());
        Assert.assertEquals("no-such-proc", r.getProcInstId());
        Assert.assertNull(r.getStatusName());
        Assert.assertFalse(r.isTerminal());
    }

    @Test
    public void startOnAlreadyTerminalReturnsCurrentStatus() {
        publish("once", "flow: \"x\"\nnodes:\n  x:\n    handler: ok\n");
        ProcessExecution first = run("once");
        Assert.assertEquals(Status.SUCCESS, first.getStatus());
        // 再次 start: 行已终态非 PENDING, 直接回当下状态, 不重跑
        ProcessExecution again = facade.start(first.getProcInstId());
        Assert.assertEquals(Status.SUCCESS, again.getStatus());
    }

    // ============ 10) EAGER 分片: 创建期只建节点树, 分片键驱动期现解并补建 lane/action ============

    @Test
    public void eagerShardedDefersLanesAndActionsToDrive() {
        publish("shardEager", ""
                + "materialization: EAGER\n"
                + "flow: \"s\"\n"
                + "nodes:\n"
                + "  s:\n"
                + "    handler: dbMigrate\n"
                + "    shard: dbTopology\n");
        String pid = facade.create("shardEager", 1, null, "u1");
        NodeInstEntity node = nodeMapper.findRoots(pid).get(0);
        // EAGER 创建期只建节点行: 分片键驱动期现解, lane/action 执行到才补建
        Assert.assertTrue("创建期不建 lane", laneMapper.findByNode(node.getNodeInstId()).isEmpty());
        Assert.assertEquals("创建期不建 action", 0, actionMapper.find(node.getNodeInstId(), null).size());
        ProcessExecution r = facade.start(pid);
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        Assert.assertEquals("驱动期补建 3 lane", 3, laneMapper.findByNode(node.getNodeInstId()).size());
        Assert.assertEquals("每 lane 各 1 action 建全", 3, actionMapper.find(node.getNodeInstId(), null).size());
    }

    // ============ 11) flow 引用未定义节点: 物化抛错被 driveRoots 捕获 -> FAILED ============

    @Test
    public void flowReferencingUndefinedNodeFails() {
        publish("ghostNode", "flow: \"doesNotExist\"\nnodes:\n  other:\n    handler: ok\n");
        ProcessExecution r = run("ghostNode");
        Assert.assertEquals(Status.FAILED, r.getStatus());
        Assert.assertTrue(nodeMapper.findRoots(r.getProcInstId()).isEmpty());
    }

    // ============ 12) version 传 null: 取最新已发布版 ============

    @Test
    public void createWithNullVersionResolvesLatestPublished() {
        publish("latest", "flow: \"x\"\nnodes:\n  x:\n    handler: ok\n");
        ProcessExecution r = facade.createAndStart("latest", null, null, "u1");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
    }

    // ============ 13) 创建参数与定义 vars 同名: 覆盖 def vars ============

    @Test
    public void createParamOverlappingDefVarIsMerged() {
        publish("dupvar", "vars:\n  dup: defval\nflow: \"x\"\nnodes:\n  x:\n    handler: ok\n");
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("dup", "paramval");
        ProcessExecution r = facade.createAndStart("dupvar", 1, params, "u1");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        // 同名参数覆盖 def vars 落进初始 vars
        Assert.assertEquals("paramval",
                Json.readMap(processMapper.findById(r.getProcInstId()).getVars()).get("dup"));
    }

    // ============ 14) 三参 create 重载(无创建人入口) ============

    @Test
    public void threeArgCreateHasNoCreator() {
        publish("threearg", "flow: \"x\"\nnodes:\n  x:\n    handler: ok\n");
        String pid = facade.create("threearg", 1, null);
        Assert.assertNull(processMapper.findById(pid).getCreatorId());
    }

    // ============ 15) 进度含分片 lane 下 action 汇总 ============

    @Test
    public void progressIncludesShardedLaneActions() {
        publish("progShard", ""
                + "materialization: EAGER\n"
                + "flow: \"s\"\n"
                + "nodes:\n  s:\n    handler: ok\n    shard: dbTopology\n");
        ProcessExecution r = run("progShard");
        Map<String, Object> p = facade.progress(r.getProcInstId());
        Assert.assertEquals(3, p.get("total"));   // 3 lane × 1 action
    }

    // ============ 16) driveClaimed 目标不存在 -> null ============

    @Test
    public void driveClaimedOnMissingProcessReturnsNull() {
        Assert.assertNull(engine.driveClaimed("ghost-proc"));
    }

    // ============ 17) handler 返 null 兜底 SUCCESS / 主动 SKIPPED 记原因 ============

    @Test
    public void handlerReturningNullDefaultsToSuccessAndSkipperRecordsReason() {
        publish("nullskip", ""
                + "flow: \"n,s\"\n"
                + "nodes:\n"
                + "  n:\n    handler: nuller\n"
                + "  s:\n    handler: skipper\n");
        ProcessExecution r = run("nullskip");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        boolean sawNullSuccess = false;
        boolean sawSkipReason = false;
        for (NodeInstEntity node : nodeMapper.findRoots(r.getProcInstId())) {
            for (ActionInstEntity a : actionMapper.findInContainer(node.getNodeInstId(), null)) {
                if ("nuller".equals(a.getHandlerName())) {
                    sawNullSuccess = Status.SUCCESS.getStatus().equals(a.getStatus());
                }
                if ("skipper".equals(a.getHandlerName())) {
                    sawSkipReason = "HANDLER_SKIP".equals(Json.readMap(a.getResult()).get("__skipReason"));
                }
            }
        }
        Assert.assertTrue(sawNullSuccess);
        Assert.assertTrue(sawSkipReason);
    }
}
