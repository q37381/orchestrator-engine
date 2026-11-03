package com.tt.ii.process.integration;

import com.tt.ii.process.api.ProcessExecution;
import com.tt.ii.process.core.engine.RollbackService;
import com.tt.ii.process.core.model.ActionSegment;
import com.tt.ii.process.core.model.NodeType;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.domain.LaneEntity;
import com.tt.ii.process.domain.NodeInstEntity;
import com.tt.ii.process.util.Json;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 聚合节点(GROUP)端到端: 一个校验项 = 一个节点(对外一个名字), 内部各步骤是子节点。
 *
 * 盯四件事:
 *  1. 串行驱动 + 状态聚合(与顶层走查同构), EAGER/LAZY 两档物化都对;
 *  2. **各层桶相互隔离 + 显式放置** —— 引擎不自动挂载/冒泡, handler 自己 ctx.node().parent().putResult(...)
 *     挂进组桶后, 后续子节点用 ${result.<key>} 沿父链上溯直读, 不需要 vars/业务专表;
 *  3. 组级 errorPolicy(自己声明优先于全局)与组级 guard(when 假 = 整组 SKIPPED);
 *  4. 进度视图的 groups 分组: 一个校验项一行。
 *
 * 定义走 publish -> DefinitionCache 的 JSON 快照 -> 借出副本重解析 flow, 所以这些用例同时兜住了
 * "展开结果必须活在可序列化字段里"那条约束(展开只放内存的话这里会直接报 flow 引用未定义节点)。
 */
public class EngineGroupFlowTest extends EngineE2ETestBase {

    // ============ 1) 串行驱动与实例树形状 ============

    @Test
    public void groupDrivesChildrenInOrderAndBuildsSubtree() {
        publish("grpBasic", ""
                + "flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    name: 校验项G\n"
                + "    group:\n"
                + "      flow: \"step1,step2\"\n"
                + "      nodes:\n"
                + "        step1:\n          handler: ok\n"
                + "        step2:\n          handler: ok\n");
        ProcessExecution r = run("grpBasic");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        List<NodeInstEntity> roots = nodeMapper.findRoots(r.getProcInstId());
        Assert.assertEquals("对外只有一个根(聚合节点)", 1, roots.size());
        NodeInstEntity group = roots.get(0);
        Assert.assertEquals(NodeType.GROUP.getStatus(), group.getType());
        Assert.assertEquals(Status.SUCCESS.getStatus(), group.getStatus());

        List<NodeInstEntity> children = nodeMapper.findChildren(r.getProcInstId(), group.getNodeInstId());
        Assert.assertEquals(2, children.size());
        for (NodeInstEntity c : children) {
            Assert.assertEquals(NodeType.ACTION.getStatus(), c.getType());
            Assert.assertEquals(Status.SUCCESS.getStatus(), c.getStatus());
        }
        Assert.assertEquals("子节点按声明顺序串行驱动",
                "[handler:ok:g.step1, handler:ok:g.step2]", rec.order.toString());
    }

    @Test
    public void eagerMaterializesWholeGroupAtCreation() {
        publish("grpEager", ""
                + "materialization: EAGER\n"
                + "flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    name: 校验项G\n"
                + "    group:\n"
                + "      flow: \"step1,step2\"\n"
                + "      nodes:\n"
                + "        step1:\n          handler: ok\n"
                + "        step2:\n          handler: ok\n");
        String pid = facade.create("grpEager", 1, null, "u1");   // 只创建不执行

        NodeInstEntity group = nodeMapper.findRoots(pid).get(0);
        Assert.assertEquals(Status.CREATED.getStatus(), group.getStatus());
        List<NodeInstEntity> children = nodeMapper.findChildren(pid, group.getNodeInstId());
        Assert.assertEquals("EAGER 创建期就把子节点建全", 2, children.size());
        for (NodeInstEntity c : children) {
            Assert.assertEquals(Status.CREATED.getStatus(), c.getStatus());
            Assert.assertEquals("创建期不预建 action 行(EAGER 只预建节点树, action 执行到才建)",
                    0, actionMapper.find(c.getNodeInstId(), null).size());
        }
        Assert.assertEquals("展示名落进组行的 name 列(不再塞 config 保留键)", "校验项G", group.getName());
        Assert.assertEquals("定义引用键落进组行的 node_def_id 列", "g", group.getNodeDefId());
    }

    @Test
    public void nestedGroupsDrive() {
        publish("grpNested", ""
                + "flow: \"outer\"\n"
                + "nodes:\n"
                + "  outer:\n"
                + "    group:\n"
                + "      flow: \"inner,tail\"\n"
                + "      nodes:\n"
                + "        inner:\n"
                + "          group:\n"
                + "            flow: \"deep\"\n"
                + "            nodes:\n"
                + "              deep:\n                handler: ok\n"
                + "        tail:\n          handler: ok\n");
        ProcessExecution r = run("grpNested");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        NodeInstEntity outer = nodeMapper.findRoots(r.getProcInstId()).get(0);
        List<NodeInstEntity> level1 = nodeMapper.findChildren(r.getProcInstId(), outer.getNodeInstId());
        Assert.assertEquals(2, level1.size());
        Assert.assertEquals(NodeType.GROUP.getStatus(), level1.get(0).getType());
        List<NodeInstEntity> level2 = nodeMapper.findChildren(r.getProcInstId(), level1.get(0).getNodeInstId());
        Assert.assertEquals(1, level2.size());
        Assert.assertEquals(Status.SUCCESS.getStatus(), level2.get(0).getStatus());
        Assert.assertEquals("[handler:ok:outer.inner.deep, handler:ok:outer.tail]", rec.order.toString());
    }

    // ============ 2) 组内显式放置交接(核心) ============

    @Test
    public void childReadsSiblingResultWithoutVars() {
        publish("grpBubble", ""
                + "flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    group:\n"
                + "      flow: \"fetchSrc,diffSpace\"\n"
                + "      nodes:\n"
                + "        fetchSrc:\n"
                + "          handler: placeToGroup\n"              // 显式把 {rows:1} 挂进组桶(键 = actionDefId)
                + "        diffSpace:\n"
                + "          handler: echoConfig\n"
                + "          config:\n"
                + "            srcRows: \"${result.fetchSrc.rows}\"\n");   // 组内直读, 不经 vars
        ProcessExecution r = run("grpBubble");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        NodeInstEntity group = nodeMapper.findRoots(r.getProcInstId()).get(0);
        List<NodeInstEntity> children = nodeMapper.findChildren(r.getProcInstId(), group.getNodeInstId());
        ActionInstEntity diff = actionMapper.findInContainer(children.get(1).getNodeInstId(), null).get(0);

        Map<String, Object> res = Json.readMap(diff.getResult());
        Assert.assertNull("占位符全部解出, 不留 __unresolved", res.get("__unresolved"));
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) res.get("cfg");
        Assert.assertEquals("兄弟子节点显式挂进组桶的值沿父链可读", 1, cfg.get("srcRows"));

        // 组桶落了库(崩溃重驱动时显式放置的结果能随 finishNode 重建)
        Map<String, Object> groupResult = Json.readMap(nodeMapper.findById(group.getNodeInstId()).getResult());
        Assert.assertNotNull(groupResult.get("fetchSrc"));
    }

    /** 隔离口径: handler 只写自己桶(不显式放置)时, action result 不再被复制进 node 行。 */
    @Test
    public void actionResultStaysInOwnRowWithoutExplicitPlacement() {
        publish("isoBucket", ""
                + "flow: \"n\"\n"
                + "nodes:\n"
                + "  n:\n"
                + "    actions:\n"
                + "      - {id: a1, handler: ok}\n"               // 只 ctx.putResult("rows",1), 不上挂
                + "      - {id: a2, handler: ok}\n");
        ProcessExecution r = run("isoBucket");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        NodeInstEntity node = nodeMapper.findRoots(r.getProcInstId()).get(0);
        List<ActionInstEntity> acts = actionMapper.findInContainer(node.getNodeInstId(), null);
        Assert.assertEquals(1, Json.readMap(acts.get(0).getResult()).get("rows"));
        Assert.assertEquals(1, Json.readMap(acts.get(1).getResult()).get("rows"));
        Assert.assertTrue("node 行 result 不再携带 action 结果副本(只剩本层显式写入)",
                Json.readMap(nodeMapper.findById(node.getNodeInstId()).getResult()).isEmpty());
    }

    @Test
    public void shardedChildInsideGroupStillFansOut() {
        publish("grpShard", ""
                + "flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    group:\n"
                + "      flow: \"perShard\"\n"
                + "      nodes:\n"
                + "        perShard:\n"
                + "          handler: dbMigrate\n"
                + "          shard: dbTopology\n"
                + "          laneExecution: PARALLEL\n");
        ProcessExecution r = run("grpShard");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        NodeInstEntity group = nodeMapper.findRoots(r.getProcInstId()).get(0);
        NodeInstEntity child = nodeMapper.findChildren(r.getProcInstId(), group.getNodeInstId()).get(0);
        List<LaneEntity> lanes = laneMapper.findByNode(child.getNodeInstId());
        Assert.assertEquals("dbTopology 一主两从 = 3 lane", 3, lanes.size());
        for (LaneEntity l : lanes) {
            Assert.assertEquals(Status.SUCCESS.getStatus(), laneMapper.findById(l.getLaneId()).getStatus());
        }
    }

    /** actionExecution: PARALLEL —— 非分片节点内的 action 序列扇出到 action 池并行, 全部跑完聚合成 SUCCESS。 */
    @Test
    public void actionSequenceParallelFansOutAndSucceeds() {
        publish("actPar", ""
                + "flow: \"n\"\n"
                + "nodes:\n"
                + "  n:\n"
                + "    actionExecution: PARALLEL\n"
                + "    actions:\n"
                + "      - {id: a1, handler: ok}\n"
                + "      - {id: a2, handler: ok}\n"
                + "      - {id: a3, handler: ok}\n");
        ProcessExecution r = run("actPar");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        NodeInstEntity node = nodeMapper.findRoots(r.getProcInstId()).get(0);
        List<ActionInstEntity> acts = actionMapper.findInContainer(node.getNodeInstId(), null);
        Assert.assertEquals("并行扇出前需补齐全部 action 行", 3, acts.size());
        for (ActionInstEntity a : acts) {
            Assert.assertEquals("并行 action 逐个落 SUCCESS", Status.SUCCESS.getStatus(),
                    actionMapper.findById(a.getActionInstId()).getStatus());
        }
    }

    /** 分片 lane 并行(shard 池)与 lane 内 action 并行(action 池)两档嵌套扇出: 三条 lane × 两条 action 全绿。 */
    @Test
    public void shardAndActionParallelFanOutTogether() {
        publish("shardActPar", ""
                + "flow: \"n\"\n"
                + "nodes:\n"
                + "  n:\n"
                + "    shard: dbTopology\n"                 // 一主两从 = 3 lane
                + "    laneExecution: PARALLEL\n"           // lane 扇出走 shard 池
                + "    actionExecution: PARALLEL\n"         // lane 内 action 扇出走 action 池
                + "    actions:\n"
                + "      - {id: a1, handler: ok}\n"
                + "      - {id: a2, handler: ok}\n");
        ProcessExecution r = run("shardActPar");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        NodeInstEntity node = nodeMapper.findRoots(r.getProcInstId()).get(0);
        List<LaneEntity> lanes = laneMapper.findByNode(node.getNodeInstId());
        Assert.assertEquals(3, lanes.size());
        for (LaneEntity l : lanes) {
            Assert.assertEquals(Status.SUCCESS.getStatus(), laneMapper.findById(l.getLaneId()).getStatus());
            List<ActionInstEntity> acts = actionMapper.findInContainer(node.getNodeInstId(), l.getLaneId());
            Assert.assertEquals("每 lane 两条 action 全建全跑", 2, acts.size());
            for (ActionInstEntity a : acts) {
                Assert.assertEquals(Status.SUCCESS.getStatus(),
                        actionMapper.findById(a.getActionInstId()).getStatus());
            }
        }
    }

    /** 组级共享 config: 子节点乃至其分片 lane 里的 action 沿父链上溯(action->lane->子节点->组)就能读到。 */
    @Test
    @SuppressWarnings("unchecked")
    public void groupConfigIsSharedWithDescendants() {
        publish("grpCfg", ""
                + "flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    config:\n"
                + "      itemTable: t_item\n"
                + "    group:\n"
                + "      flow: \"step\"\n"
                + "      nodes:\n"
                + "        step:\n"
                + "          shard: dbTopology\n"
                + "          handler: echoConfig\n"
                + "          config:\n"
                + "            table: \"${config.itemTable}\"\n");
        ProcessExecution r = run("grpCfg");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        NodeInstEntity group = nodeMapper.findRoots(r.getProcInstId()).get(0);
        NodeInstEntity step = nodeMapper.findChildren(r.getProcInstId(), group.getNodeInstId()).get(0);
        List<LaneEntity> lanes = laneMapper.findByNode(step.getNodeInstId());
        Assert.assertEquals(3, lanes.size());
        for (LaneEntity l : lanes) {
            Map<String, Object> res = Json.readMap(
                    actionMapper.findInContainer(step.getNodeInstId(), l.getLaneId()).get(0).getResult());
            Assert.assertNull(res.get("__unresolved"));
            Assert.assertEquals("t_item", ((Map<String, Object>) res.get("cfg")).get("table"));
        }
    }

    // ============ 3) 目标形状: 源端一次 + 目标端分片并行"查+比" ============

    /**
     * 用户场景的验收用例: 一个校验项 = 一个聚合节点, 内部"取源端(不分片, 一次)" + "逐片对比(分片并行)"。
     * 关键点: 分片 lane 的父链是 action -> lane -> 子节点 -> **组** -> 流程, 源端 handler **显式**把结果
     * 挂进组桶后, 每条 lane 都上溯读得到 —— 不需要 vars, 不需要业务专表, 也不存在跨 lane 时序竞态(两步在组内串行)。
     */
    @Test
    @SuppressWarnings("unchecked")
    public void validationItemShapeSourceOnceThenShardedParallelDiff() {
        publish("grpValidate", ""
                + "materialization: EAGER\n"
                + "errorPolicy: CONTINUE_ON_ERROR\n"
                + "flow: \"spaceCheck\"\n"
                + "nodes:\n"
                + "  spaceCheck:\n"
                + "    name: 空间占用校验\n"
                + "    errorPolicy: FAIL_FAST\n"
                + "    group:\n"
                + "      flow: \"fetchSource,diffPerShard\"\n"
                + "      nodes:\n"
                + "        fetchSource:\n"
                + "          handler: placeToGroup\n"              // 源端只查一次(不分片), 显式挂组桶 {rows:1}
                + "        diffPerShard:\n"
                + "          shard: dbTopology\n"
                + "          laneExecution: PARALLEL\n"
                + "          handler: echoConfig\n"
                + "          config:\n"
                + "            srcRows: \"${result.fetchSource.rows}\"\n");
        ProcessExecution r = run("grpValidate");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        NodeInstEntity group = nodeMapper.findRoots(r.getProcInstId()).get(0);
        List<NodeInstEntity> children = nodeMapper.findChildren(r.getProcInstId(), group.getNodeInstId());
        Assert.assertEquals(2, children.size());
        Assert.assertTrue("源端那步不分片", laneMapper.findByNode(children.get(0).getNodeInstId()).isEmpty());
        List<LaneEntity> lanes = laneMapper.findByNode(children.get(1).getNodeInstId());
        Assert.assertEquals("对比那步按分片轴扇出(源端不在轴上 = 天然少一片)", 3, lanes.size());

        for (LaneEntity l : lanes) {
            ActionInstEntity a = actionMapper
                    .findInContainer(children.get(1).getNodeInstId(), l.getLaneId()).get(0);
            Map<String, Object> res = Json.readMap(a.getResult());
            Assert.assertNull("占位符全部解出", res.get("__unresolved"));
            Map<String, Object> cfg = (Map<String, Object>) res.get("cfg");
            Assert.assertEquals("每条 lane 都读到源端那一步的结果", 1, cfg.get("srcRows"));
        }

        Map<String, Object> p = facade.progress(r.getProcInstId());
        List<Map<String, Object>> groups = (List<Map<String, Object>>) p.get("groups");
        Assert.assertEquals("一个校验项一行", 1, groups.size());
        Assert.assertEquals("空间占用校验", groups.get(0).get("name"));
        Assert.assertEquals("明细 = 源端 1 + 分片 3", 4, groups.get(0).get("total"));
        Assert.assertEquals(4, groups.get(0).get("done"));
        Assert.assertEquals(4, p.get("total"));
    }

    // ============ 4) 组级 errorPolicy 与 guard ============

    @Test
    public void groupFailFastStopsRemainingChildren() {
        publish("grpFF", ""
                + "errorPolicy: CONTINUE_ON_ERROR\n"          // 全局逐项跑完
                + "flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    errorPolicy: FAIL_FAST\n"              // 组自己覆盖: 遇坏即停
                + "    group:\n"
                + "      flow: \"boom,after\"\n"
                + "      nodes:\n"
                + "        boom:\n          handler: thrower\n"
                + "        after:\n          handler: ok\n");
        ProcessExecution r = run("grpFF");
        Assert.assertEquals(Status.FAILED, r.getStatus());

        NodeInstEntity group = nodeMapper.findRoots(r.getProcInstId()).get(0);
        Assert.assertEquals(Status.FAILED.getStatus(), group.getStatus());
        Assert.assertFalse("第二个子节点没跑", rec.order.contains("handler:ok:g.after"));
    }

    @Test
    public void groupContinueOnErrorRunsAllChildren() {
        publish("grpCOE", ""
                + "flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    errorPolicy: CONTINUE_ON_ERROR\n"
                + "    group:\n"
                + "      flow: \"boom,after\"\n"
                + "      nodes:\n"
                + "        boom:\n          handler: thrower\n"
                + "        after:\n          handler: ok\n");
        ProcessExecution r = run("grpCOE");
        Assert.assertEquals("组内有一步失败 -> 整组 FAILED", Status.FAILED, r.getStatus());
        Assert.assertTrue("但后续子节点照跑", rec.order.contains("handler:ok:g.after"));
    }

    @Test
    public void groupGuardFalseSkipsWholeGroup() {
        publish("grpGuard", ""
                + "flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    when: \"${vars.absent} == true\"\n"     // vars.absent 未定义 -> 条件假
                + "    group:\n"
                + "      flow: \"step1\"\n"
                + "      nodes:\n"
                + "        step1:\n          handler: ok\n");
        ProcessExecution r = run("grpGuard");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        NodeInstEntity group = nodeMapper.findRoots(r.getProcInstId()).get(0);
        Assert.assertEquals(Status.SKIPPED.getStatus(), group.getStatus());
        Assert.assertTrue("整组跳过 -> 子节点从未物化",
                nodeMapper.findChildren(r.getProcInstId(), group.getNodeInstId()).isEmpty());
        Assert.assertTrue(rec.order.isEmpty());
    }

    /**
     * 显式放置的值对组的**任意深度后代**都可读: PARALLEL/IF 的子节点也挂在各自容器上
     * (上下文父链与实例树 parent_id 同形), 所以组 -> PARALLEL -> 叶子这条链上 ${result.<key>} 照样解得开。
     */
    @Test
    @SuppressWarnings("unchecked")
    public void placedResultIsReadableInsideNestedParallel() {
        publish("grpDeepPar", ""
                + "flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    group:\n"
                + "      flow: \"fetchSrc,PARALLEL(a,b)\"\n"
                + "      nodes:\n"
                + "        fetchSrc:\n          handler: placeToGroup\n"
                + "        a:\n"
                + "          handler: echoConfig\n"
                + "          config:\n            srcRows: \"${result.fetchSrc.rows}\"\n"
                + "        b:\n"
                + "          handler: echoConfig\n"
                + "          config:\n            srcRows: \"${result.fetchSrc.rows}\"\n");
        ProcessExecution r = run("grpDeepPar");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        NodeInstEntity group = nodeMapper.findRoots(r.getProcInstId()).get(0);
        NodeInstEntity par = nodeMapper.findChildren(r.getProcInstId(), group.getNodeInstId()).get(1);
        Assert.assertEquals(NodeType.PARALLEL.getStatus(), par.getType());
        for (NodeInstEntity leaf : nodeMapper.findChildren(r.getProcInstId(), par.getNodeInstId())) {
            Map<String, Object> res = Json.readMap(
                    actionMapper.findInContainer(leaf.getNodeInstId(), null).get(0).getResult());
            Assert.assertNull(res.get("__unresolved"));
            Assert.assertEquals("并行子节点也读到组桶", 1, ((Map<String, Object>) res.get("cfg")).get("srcRows"));
        }
    }

    /** 同上, IF 分支: 选中的分支节点也是组的后代, 组桶里显式放置的值可读。 */
    @Test
    @SuppressWarnings("unchecked")
    public void placedResultIsReadableInsideNestedIf() {
        publish("grpDeepIf", ""
                + "flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    group:\n"
                + "      flow: \"fetchSrc,IF(${vars.absent} == true,thenStep,elseStep)\"\n"
                + "      nodes:\n"
                + "        fetchSrc:\n          handler: placeToGroup\n"
                + "        thenStep:\n"
                + "          handler: thrower\n"
                + "        elseStep:\n"
                + "          handler: echoConfig\n"
                + "          config:\n            srcRows: \"${result.fetchSrc.rows}\"\n");
        ProcessExecution r = run("grpDeepIf");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        NodeInstEntity group = nodeMapper.findRoots(r.getProcInstId()).get(0);
        NodeInstEntity iff = nodeMapper.findChildren(r.getProcInstId(), group.getNodeInstId()).get(1);
        Assert.assertEquals(NodeType.IF.getStatus(), iff.getType());
        NodeInstEntity branch = nodeMapper.findChildren(r.getProcInstId(), iff.getNodeInstId()).get(0);
        Map<String, Object> res = Json.readMap(
                actionMapper.findInContainer(branch.getNodeInstId(), null).get(0).getResult());
        Assert.assertNull(res.get("__unresolved"));
        Assert.assertEquals("IF 分支节点也读到组桶", 1, ((Map<String, Object>) res.get("cfg")).get("srcRows"));
    }

    // ============ 5) 进度视图: 一个校验项一行 ============

    @Test
    @SuppressWarnings("unchecked")
    public void progressExposesOneLinePerGroup() {
        publish("grpProg", ""
                + "materialization: EAGER\n"
                + "flow: \"spaceCheck,rowCountCheck\"\n"
                + "nodes:\n"
                + "  spaceCheck:\n"
                + "    name: 空间占用校验\n"
                + "    group:\n"
                + "      flow: \"fetchSrc,diffSpace\"\n"
                + "      nodes:\n"
                + "        fetchSrc:\n          handler: ok\n"
                + "        diffSpace:\n          handler: skipper\n"
                + "  rowCountCheck:\n"
                + "    name: 行数校验\n"
                + "    group:\n"
                + "      flow: \"countRows\"\n"
                + "      nodes:\n"
                + "        countRows:\n          handler: ok\n");
        ProcessExecution r = run("grpProg");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        Map<String, Object> p = facade.progress(r.getProcInstId());
        Assert.assertEquals("扁平口径仍是全部 action 行", 3, p.get("total"));

        List<Map<String, Object>> groups = (List<Map<String, Object>>) p.get("groups");
        Assert.assertEquals("一个校验项一行", 2, groups.size());

        Map<String, Object> space = groups.get(0);
        Assert.assertEquals("空间占用校验", space.get("name"));
        Assert.assertEquals("spaceCheck", space.get("group"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), space.get("status"));
        Assert.assertEquals(2, space.get("total"));
        Assert.assertEquals("success + skipped 都算 done", 2, space.get("done"));
        Assert.assertEquals(100, space.get("percent"));
        Assert.assertEquals(2, ((List<Object>) space.get("items")).size());

        Map<String, Object> rows = groups.get(1);
        Assert.assertEquals("rowCountCheck", rows.get("group"));
        Assert.assertEquals(1, rows.get("total"));
    }

    // ============ 6) 干预与恢复: GROUP 行参与容器链复位 ============

    /**
     * 重驱动不依赖内存态: GROUP 行被 resetNodeUpward 复位后, 已终态子节点走 driveNode 的幂等快路径,
     * 显式挂进组桶的值随组收尾 finishNode 落库、重建时从组行读回 —— 组内交接因此对崩溃/干预免疫。
     */
    @Test
    @SuppressWarnings("unchecked")
    public void groupRecoversAfterSkippingFailedChild() {
        publish("grpRetry", ""
                + "errorPolicy: CONTINUE_ON_ERROR\n"
                + "flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    group:\n"
                + "      flow: \"fetchSrc,boom,probe\"\n"
                + "      nodes:\n"
                + "        fetchSrc:\n          handler: placeToGroup\n"
                + "        boom:\n          handler: thrower\n"
                + "        probe:\n"
                + "          handler: echoConfig\n"
                + "          config:\n            srcRows: \"${result.fetchSrc.rows}\"\n");
        ProcessExecution first = run("grpRetry");
        Assert.assertEquals(Status.FAILED, first.getStatus());

        NodeInstEntity group = nodeMapper.findRoots(first.getProcInstId()).get(0);
        List<NodeInstEntity> children = nodeMapper.findChildren(first.getProcInstId(), group.getNodeInstId());
        Assert.assertEquals(Status.FAILED.getStatus(), group.getStatus());

        // 失败兄弟之后仍继续跑(全局 CONTINUE_ON_ERROR), 且照样读到显式挂组桶的值
        Map<String, Object> probeRes = Json.readMap(
                actionMapper.findInContainer(children.get(2).getNodeInstId(), null).get(0).getResult());
        Assert.assertEquals(1, ((Map<String, Object>) probeRes.get("cfg")).get("srcRows"));

        // 人工跳过失败那一步 -> 容器链(含 GROUP 行)复位 -> 重驱动跑成好终态
        ActionInstEntity failed = actionMapper.findInContainer(children.get(1).getNodeInstId(), null).get(0);
        Assert.assertTrue(facade.skipAction(failed.getActionInstId(), "op1"));
        Assert.assertEquals("GROUP 行随容器链复位", Status.CREATED.getStatus(),
                nodeMapper.findById(group.getNodeInstId()).getStatus());

        ProcessExecution second = facade.start(first.getProcInstId());
        Assert.assertEquals(Status.SUCCESS, second.getStatus());
        NodeInstEntity groupAfter = nodeMapper.findById(group.getNodeInstId());
        Assert.assertEquals(Status.SUCCESS.getStatus(), groupAfter.getStatus());
        Assert.assertNotNull("组桶里的显式放置值随组行落库重建, 不靠内存",
                Json.readMap(groupAfter.getResult()).get("fetchSrc"));
    }

    // ============ 7) 组内引用外层节点 / 匿名容器子项 ============

    @Test
    public void groupChildMayReferenceOuterNode() {
        publish("grpOuterRef", ""
                + "flow: \"g\"\n"
                + "nodes:\n"
                + "  shared:\n    handler: ok\n"
                + "  g:\n"
                + "    group:\n"
                + "      flow: \"shared,own\"\n"
                + "      nodes:\n"
                + "        own:\n          handler: ok\n");
        ProcessExecution r = run("grpOuterRef");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        NodeInstEntity group = nodeMapper.findRoots(r.getProcInstId()).get(0);
        List<NodeInstEntity> children = nodeMapper.findChildren(r.getProcInstId(), group.getNodeInstId());
        Assert.assertEquals("外层节点被当作本组的一个子行", 2, children.size());
        Assert.assertEquals("[handler:ok:shared, handler:ok:g.own]", rec.order.toString());
    }

    @Test
    public void groupChildMayBeAnonymousParallel() {
        publish("grpPar", ""
                + "flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    group:\n"
                + "      flow: \"PARALLEL(a,b),tail\"\n"
                + "      nodes:\n"
                + "        a:\n          handler: ok\n"
                + "        b:\n          handler: ok\n"
                + "        tail:\n          handler: ok\n");
        ProcessExecution r = run("grpPar");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        NodeInstEntity group = nodeMapper.findRoots(r.getProcInstId()).get(0);
        List<NodeInstEntity> children = nodeMapper.findChildren(r.getProcInstId(), group.getNodeInstId());
        Assert.assertEquals("PARALLEL 容器 + tail", 2, children.size());
        Assert.assertEquals(NodeType.PARALLEL.getStatus(), children.get(0).getType());
        Assert.assertEquals(2, nodeMapper.findChildren(r.getProcInstId(), children.get(0).getNodeInstId()).size());
        Assert.assertTrue("tail 在并行段之后", rec.order.indexOf("handler:ok:g.tail") > 1);
    }

    // ============ 8) group 级 setup/setdown 段(组回路三段) ============

    /** 组级三段故事的 YAML: 组 setup -> 子节点序列 -> 组 setdown, 后续根 post 验证收敛口径。 */
    private static String groupSyncDef(String setupHandler, String childHandler, String setdownHandler, String groupPolicy) {
        return "flow: \"g,post\"\n"
                + "nodes:\n"
                + "  g:\n"
                + (groupPolicy == null ? "" : "    errorPolicy: " + groupPolicy + "\n")
                + "    setup:\n      - id: prepare\n        handler: " + setupHandler + "\n"
                + "    group:\n      flow: \"c1,c2\"\n      nodes:\n"
                + "        c1:\n          handler: " + childHandler + "\n"
                + "        c2:\n          handler: ok\n"
                + "    setdown:\n      - id: restore\n        handler: " + setdownHandler + "\n"
                + "  post:\n    handler: ok\n";
    }

    /** 正常路径: 组 setup -> c1 -> c2 -> 组 setdown -> 后续根; 段行挂组自己的 nodeInstId, lane/shard 恒空。 */
    @Test
    public void groupSegmentsRunAroundChildrenAndPersistOnGroupRow() {
        publish("grpSegOk", groupSyncDef("ok", "ok", "ok", null));
        ProcessExecution r = run("grpSegOk");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        String pid = r.getProcInstId();

        int prepare = rec.order.indexOf("handler:ok:prepare");
        int c1 = rec.order.indexOf("handler:ok:g.c1");
        int c2 = rec.order.indexOf("handler:ok:g.c2");
        int restore = rec.order.indexOf("handler:ok:restore");
        int post = rec.order.indexOf("handler:ok:post");
        Assert.assertTrue("组 setup -> c1 -> c2 -> 组 setdown -> 后续根",
                prepare >= 0 && prepare < c1 && c1 < c2 && c2 < restore && restore < post);

        NodeInstEntity group = nodeByDef(pid, "g");
        List<ActionInstEntity> segs = actionMapper.findInContainer(group.getNodeInstId(), null);
        Assert.assertEquals("组容器只有两条段行(组没有 MAIN 动作)", 2, segs.size());
        Assert.assertEquals("setup", segs.get(0).getSegment());
        Assert.assertEquals("setdown", segs.get(1).getSegment());
        Assert.assertNull("段行不挂 lane", segs.get(0).getLaneId());
        Assert.assertNull("段行不分片", segs.get(0).getShardKey());
        Assert.assertEquals(Status.SUCCESS.getStatus(), group.getStatus());
    }

    /** 三段均成、组未收尾即崩溃: 重驱动不重跑段动作也不重跑子节点, 只补组行收尾(无重复行)。 */
    @Test
    public void groupReDriveAfterUnsettledCompletionIsIdempotent() {
        publish("grpSegResume", groupSyncDef("ok", "ok", "ok", null));
        ProcessExecution r1 = run("grpSegResume");
        Assert.assertEquals(Status.SUCCESS, r1.getStatus());
        String pid = r1.getProcInstId();

        // 倒回"三段刚成、组行未落终态"的崩溃残留
        NodeInstEntity group = nodeByDef(pid, "g");
        nodeMapper.setStatus(group.getNodeInstId(), Status.RUNNING.getStatus(), "u1");
        processMapper.casStatus(pid, Status.SUCCESS.getStatus(), Status.RUNNING.getStatus(), "u1");

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("setup 已成功: 不重跑", 1, Collections.frequency(rec.order, "handler:ok:prepare"));
        Assert.assertEquals("子节点已终态: 不重跑", 1, Collections.frequency(rec.order, "handler:ok:g.c1"));
        Assert.assertEquals("setdown 已成功: 不重跑", 1, Collections.frequency(rec.order, "handler:ok:restore"));
        Assert.assertEquals("无重复段行", 2, actionMapper.findInContainer(group.getNodeInstId(), null).size());
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "g").getStatus());
    }

    /** 组 setup 失败: 子节点一个都不物化(LAZY), 组 setdown 照跑(finally); 流程 FAILED, 后续根不跑。 */
    @Test
    public void groupSetupFailureSkipsChildrenButRestores() {
        publish("grpSegSetupFail", groupSyncDef("thrower", "ok", "ok", null));
        ProcessExecution r = run("grpSegSetupFail");
        Assert.assertEquals(Status.FAILED, r.getStatus());
        String pid = r.getProcInstId();

        NodeInstEntity group = nodeByDef(pid, "g");
        Assert.assertEquals(Status.FAILED.getStatus(), segmentRow(group, ActionSegment.SETUP).getStatus());
        Assert.assertTrue("setdown 照跑(finally)", rec.order.contains("handler:ok:restore"));
        Assert.assertFalse("子节点不跑", rec.order.contains("handler:ok:g.c1"));
        Assert.assertFalse("FAIL_FAST: 后续根不跑", rec.order.contains("handler:ok:post"));
        Assert.assertEquals(Status.FAILED.getStatus(), group.getStatus());
        Assert.assertTrue("setup 失败 -> 子节点从未物化",
                nodeMapper.findChildren(pid, group.getNodeInstId()).isEmpty());
    }

    /** 子节点坏而组策略 CONTINUE: 后续子节点照跑, 组 setdown 照跑; 组声明对根序列生效 = 后续根照跑。 */
    @Test
    public void groupChildFailureWithContinuePolicyKeepsRootGoing() {
        publish("grpSegChildFail", groupSyncDef("ok", "thrower", "ok", "CONTINUE_ON_ERROR"));
        ProcessExecution r = run("grpSegChildFail");
        Assert.assertEquals(Status.FAILED, r.getStatus());
        String pid = r.getProcInstId();

        Assert.assertTrue("c1 挂了", rec.order.contains("handler:ok:prepare"));
        Assert.assertTrue("CONTINUE: c2 照跑", rec.order.contains("handler:ok:g.c2"));
        Assert.assertTrue("setdown 照跑", rec.order.contains("handler:ok:restore"));
        Assert.assertTrue("组声明 CONTINUE 对根序列生效: post 照跑", rec.order.contains("handler:ok:post"));
        Assert.assertEquals(Status.FAILED.getStatus(), nodeByDef(pid, "g").getStatus());
    }

    /** 组 setdown 多项: 前一项坏不拦后一项(段内不收敛), 收尾必达。 */
    @Test
    public void groupSetdownNeverConverges() {
        publish("grpSegSetdownPartial", ""
                + "flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    setup:\n      - id: prepare\n        handler: ok\n"
                + "    group:\n      flow: \"c1\"\n      nodes:\n"
                + "        c1:\n          handler: ok\n"
                + "    setdown:\n"
                + "      - id: restore1\n        handler: thrower\n"
                + "      - id: restore2\n        handler: ok\n");
        ProcessExecution r = run("grpSegSetdownPartial");
        Assert.assertEquals(Status.FAILED, r.getStatus());

        Assert.assertTrue("第一项坏不拦第二项", rec.order.contains("handler:ok:restore2"));
        Assert.assertEquals(Status.FAILED.getStatus(), nodeByDef(r.getProcInstId(), "g").getStatus());
    }

    /** 成对重臂(子节点侧): 子节点失败 + setdown 已成 + retry -> 组 setup 重跑(现场重施), 成功兄弟不重跑, setdown 重跑。 */
    @Test
    public void groupPairedReArmAfterChildRetry() {
        publish("grpSegRearm", groupSyncDef("ok", "flaky", "ok", "CONTINUE_ON_ERROR"));
        ProcessExecution r1 = run("grpSegRearm");
        Assert.assertEquals(Status.FAILED, r1.getStatus());
        String pid = r1.getProcInstId();
        Assert.assertEquals(1, Collections.frequency(rec.order, "handler:ok:prepare"));
        Assert.assertEquals(1, Collections.frequency(rec.order, "handler:ok:g.c2"));

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("setup 重跑(重新准备)", 2, Collections.frequency(rec.order, "handler:ok:prepare"));
        Assert.assertEquals("失败子节点续跑成功", 2, Collections.frequency(rec.order, "handler:flaky:g.c1"));
        Assert.assertEquals("成功兄弟不重跑", 1, Collections.frequency(rec.order, "handler:ok:g.c2"));
        Assert.assertEquals("setdown 重新收尾", 2, Collections.frequency(rec.order, "handler:ok:restore"));
        Assert.assertEquals("post 首轮已成: 不重跑", 1, Collections.frequency(rec.order, "handler:ok:post"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "g").getStatus());
    }

    /** 只补收尾: 子节点全成、setdown 失败重试 -> 不碰 setup(不触发成对重臂), 成功子节点也不重跑。 */
    @Test
    public void groupOnlySetdownRetriedWithoutSetupRerun() {
        publish("grpSegSetdownRetry", groupSyncDef("ok", "ok", "flaky", "CONTINUE_ON_ERROR"));
        ProcessExecution r1 = run("grpSegSetdownRetry");
        Assert.assertEquals(Status.FAILED, r1.getStatus());
        String pid = r1.getProcInstId();
        Assert.assertEquals("首轮 restore 挂", 1, Collections.frequency(rec.order, "handler:flaky:restore"));

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("setup 不重跑", 1, Collections.frequency(rec.order, "handler:ok:prepare"));
        Assert.assertEquals("成功子节点不重跑", 1, Collections.frequency(rec.order, "handler:ok:g.c1"));
        Assert.assertEquals("收尾复位重跑成", 2, Collections.frequency(rec.order, "handler:flaky:restore"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "g").getStatus());
    }

    /** 崩溃补跑(默认成对口径): 子节点成、setdown 未跑 -> 只补 setdown, setup 与成功子节点都不碰。 */
    @Test
    public void groupCrashBeforeSetdownReplaysOnlySetdown() {
        publish("grpSegCrash", groupSyncDef("ok", "ok", "ok", null));
        ProcessExecution r1 = run("grpSegCrash");
        Assert.assertEquals(Status.SUCCESS, r1.getStatus());
        String pid = r1.getProcInstId();

        NodeInstEntity group = nodeByDef(pid, "g");
        ActionInstEntity restore = segmentRow(group, ActionSegment.SETDOWN);
        actionMapper.updateState(restore.getActionInstId(), null, Status.CREATED.getStatus(), null, "u1");
        nodeMapper.setStatus(group.getNodeInstId(), Status.RUNNING.getStatus(), "u1");
        processMapper.casStatus(pid, Status.SUCCESS.getStatus(), Status.RUNNING.getStatus(), "u1");

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("setup 已成功且现场仍在: 不重跑", 1, Collections.frequency(rec.order, "handler:ok:prepare"));
        Assert.assertEquals("成功子节点不重跑", 1, Collections.frequency(rec.order, "handler:ok:g.c1"));
        Assert.assertEquals("只补收尾", 2, Collections.frequency(rec.order, "handler:ok:restore"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "g").getStatus());
    }

    /** repeatSegments: 同一崩溃窗口下每趟驱动段重跑——setup 无条件重施、成功子节点仍不重跑。 */
    @Test
    public void groupRepeatSegmentsRerunsOnEveryDrive() {
        publish("grpSegRepeat", ""
                + "flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    repeatSegments: true\n"
                + "    setup:\n      - id: prepare\n        handler: ok\n"
                + "    group:\n      flow: \"c1\"\n      nodes:\n"
                + "        c1:\n          handler: ok\n"
                + "    setdown:\n      - id: restore\n        handler: ok\n");
        ProcessExecution r1 = run("grpSegRepeat");
        Assert.assertEquals(Status.SUCCESS, r1.getStatus());
        String pid = r1.getProcInstId();

        NodeInstEntity group = nodeByDef(pid, "g");
        ActionInstEntity restore = segmentRow(group, ActionSegment.SETDOWN);
        actionMapper.updateState(restore.getActionInstId(), null, Status.CREATED.getStatus(), null, "u1");
        nodeMapper.setStatus(group.getNodeInstId(), Status.RUNNING.getStatus(), "u1");
        processMapper.casStatus(pid, Status.SUCCESS.getStatus(), Status.RUNNING.getStatus(), "u1");

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("每趟驱动 setup 都重跑", 2, Collections.frequency(rec.order, "handler:ok:prepare"));
        Assert.assertEquals("子节点已成功: 不重跑", 1, Collections.frequency(rec.order, "handler:ok:g.c1"));
        Assert.assertEquals("本趟收尾补跑", 2, Collections.frequency(rec.order, "handler:ok:restore"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "g").getStatus());
    }

    /** 缺行判据: setup 后、第二子节点物化前被停掉(setdown 已成功) -> retry 重臂: setup 重施、已成功子节点不重跑、缺的子节点首跑。 */
    @Test
    public void groupReArmsWhenChildrenIncompletelyMaterialized() {
        publish("grpSegMiss", ""
                + "flow: \"g,post\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    setup:\n      - id: prepare\n        handler: ok\n"
                + "    group:\n      flow: \"c1,c2\"\n      nodes:\n"
                + "        c1:\n          handler: stopper\n"
                + "        c2:\n          handler: ok\n"
                + "    setdown:\n      - id: restore\n        handler: ok\n"
                + "  post:\n    handler: ok\n");
        ProcessExecution r1 = run("grpSegMiss");
        Assert.assertEquals("stopper 把闸门翻 STOPPED, 组内序列就地停", Status.STOPPED, r1.getStatus());
        String pid = r1.getProcInstId();

        NodeInstEntity group = nodeByDef(pid, "g");
        Assert.assertEquals(Status.STOPPED.getStatus(), group.getStatus());
        Assert.assertTrue("setdown 照跑(finally)", rec.order.contains("handler:ok:restore"));
        Assert.assertFalse("子节点序列就地停: c2 未跑", rec.order.contains("handler:ok:g.c2"));
        Assert.assertEquals("只物化了 c1", 1, nodeMapper.findChildren(pid, group.getNodeInstId()).size());
        Assert.assertFalse("停住后后续根不跑", rec.order.contains("handler:ok:post"));

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("缺行触发成对重臂: setup 重施", 2, Collections.frequency(rec.order, "handler:ok:prepare"));
        Assert.assertEquals("已成功子节点不重跑(stopper 只跑过一趟)", 1, Collections.frequency(rec.order, "handler:stopper"));
        Assert.assertEquals("缺的子节点首跑", 1, Collections.frequency(rec.order, "handler:ok:g.c2"));
        Assert.assertEquals("本趟重新收尾", 2, Collections.frequency(rec.order, "handler:ok:restore"));
        Assert.assertEquals("续跑完成后 post 到达", 1, Collections.frequency(rec.order, "handler:ok:post"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "g").getStatus());
    }

    /** 组 guard=false: 整组 SKIPPED, 段一个都不跑、子节点不物化, 也没有段行。 */
    @Test
    public void groupGuardFalseSkipsSegments() {
        publish("grpSegGuard", ""
                + "flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    when: \"${vars.absent} == true\"\n"
                + "    setup:\n      - id: prepare\n        handler: ok\n"
                + "    group:\n      flow: \"c1\"\n      nodes:\n"
                + "        c1:\n          handler: ok\n"
                + "    setdown:\n      - id: restore\n        handler: ok\n");
        ProcessExecution r = run("grpSegGuard");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        NodeInstEntity group = nodeByDef(r.getProcInstId(), "g");
        Assert.assertEquals(Status.SKIPPED.getStatus(), group.getStatus());
        Assert.assertTrue("段与子节点都没物化",
                actionMapper.findInContainer(group.getNodeInstId(), null).isEmpty());
        Assert.assertTrue(rec.order.isEmpty());
    }

    /** 三层嵌套组合: 外层组段包内层组回路, 内层组的子节点是分片节点(自带节点段) —— 各层闭环、互为包裹。 */
    @Test
    public void nestedCircuitsWrapInOrderWithShardedGrandchild() {
        publish("grpSegNest", ""
                + "flow: \"outer\"\n"
                + "nodes:\n"
                + "  outer:\n"
                + "    setup:\n      - id: outerPrepare\n        handler: ok\n"
                + "    setdown:\n      - id: outerRestore\n        handler: ok\n"
                + "    group:\n"
                + "      flow: \"inner\"\n"
                + "      nodes:\n"
                + "        inner:\n"
                + "          setup:\n            - id: innerPrepare\n              handler: ok\n"
                + "          setdown:\n            - id: innerRestore\n              handler: ok\n"
                + "          group:\n"
                + "            flow: \"shardStep\"\n"
                + "            nodes:\n"
                + "              shardStep:\n"
                + "                shard: dbTopology\n"
                + "                setup:\n                  - id: shardPrepare\n                    handler: ok\n"
                + "                actions:\n                  - id: doMigration\n                    handler: dbMigrate\n"
                + "                setdown:\n                  - id: shardRestore\n                    handler: ok\n");
        ProcessExecution r = run("grpSegNest");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        String pid = r.getProcInstId();

        int outerPrepare = rec.order.indexOf("handler:ok:outerPrepare");
        int innerPrepare = rec.order.indexOf("handler:ok:innerPrepare");
        int shardPrepare = rec.order.indexOf("handler:ok:shardPrepare");
        int shardRestore = rec.order.indexOf("handler:ok:shardRestore");
        int innerRestore = rec.order.indexOf("handler:ok:innerRestore");
        int outerRestore = rec.order.indexOf("handler:ok:outerRestore");
        Assert.assertTrue("外 -> 内 -> 分片 -> 内收尾 -> 外收尾 的递归包裹",
                outerPrepare >= 0 && outerPrepare < innerPrepare && innerPrepare < shardPrepare
                        && shardPrepare < shardRestore && shardRestore < innerRestore && innerRestore < outerRestore);
        Assert.assertEquals("分片节点每 lane 一个回路: setup 每片一趟",
                3, Collections.frequency(rec.order, "handler:ok:shardPrepare"));
        Assert.assertEquals(3, Collections.frequency(rec.order, "handler:ok:shardRestore"));

        NodeInstEntity outer = nodeByDef(pid, "outer");
        NodeInstEntity inner = nodeByDef(pid, "outer.inner");
        NodeInstEntity shardStep = nodeByDef(pid, "outer.inner.shardStep");
        Assert.assertEquals("外层组两条段行", 2, actionMapper.findInContainer(outer.getNodeInstId(), null).size());
        Assert.assertEquals("内层组两条段行", 2, actionMapper.findInContainer(inner.getNodeInstId(), null).size());
        List<LaneEntity> lanes = laneMapper.findByNode(shardStep.getNodeInstId());
        Assert.assertEquals(3, lanes.size());
        for (LaneEntity l : lanes) {
            Assert.assertEquals("每 lane 三段各一行", 3,
                    actionMapper.findInContainer(shardStep.getNodeInstId(), l.getLaneId()).size());
        }
    }

    /** 同一份组定义实例化两处: 重臂只作用失败实例的容器, 另一实例的段行零改动(visit_count 不涨)。 */
    @Test
    public void groupReArmIsIsolatedPerInstance() {
        publish("grpSegIso", ""
                + "flow: \"g,g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    setup:\n      - id: prepare\n        handler: ok\n"
                + "    group:\n      flow: \"c1\"\n      nodes:\n"
                + "        c1:\n          handler: flaky\n"
                + "    setdown:\n      - id: restore\n        handler: ok\n");
        ProcessExecution r1 = run("grpSegIso");
        Assert.assertEquals("首实例子节点 flaky 挂, FAIL_FAST 根序列就地停", Status.FAILED, r1.getStatus());
        String pid = r1.getProcInstId();

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        List<NodeInstEntity> roots = nodeMapper.findRoots(pid);
        Assert.assertEquals("两处实例化 = 两个组行", 2, roots.size());
        Assert.assertEquals("第一实例: 重臂后 setup 重跑", 2,
                segmentRow(roots.get(0), ActionSegment.SETUP).getVisitCount().intValue());
        Assert.assertEquals("第二实例: 首驱零重臂", 1,
                segmentRow(roots.get(1), ActionSegment.SETUP).getVisitCount().intValue());
        Assert.assertEquals("setup 共跑 3 趟(实例1 两趟 + 实例2 一趟)", 3,
                Collections.frequency(rec.order, "handler:ok:prepare"));
        Assert.assertEquals("子节点共跑 3 趟(实例1: 失败1+重跑1; 实例2: 首跑1)", 3,
                Collections.frequency(rec.order, "handler:flaky:g.c1"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeMapper.findById(roots.get(0).getNodeInstId()).getStatus());
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeMapper.findById(roots.get(1).getNodeInstId()).getStatus());
    }

    /** 回退只扫主段: 组段行的不可补偿不 blocked 整轮回退, 子节点主段的可补偿动作照常撤销。 */
    @Test
    public void rollbackIgnoresGroupSegmentRows() {
        publish("grpSegRb", ""
                + "flow: \"g\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    setup:\n      - id: prepare\n        handler: ok\n"
                + "    group:\n      flow: \"c1\"\n      nodes:\n"
                + "        c1:\n          handler: compOk\n"
                + "    setdown:\n      - id: restore\n        handler: ok\n");
        ProcessExecution r = run("grpSegRb");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        RollbackService.RollbackReport rep = facade.doRollback(r.getProcInstId(), "u1");
        Assert.assertEquals("只有子节点主段的 compOk 进补偿", 1, rep.compensated.size());
        Assert.assertTrue("组段行(不可补偿)不参与回退: 不 blocked", rep.blocked.isEmpty());
        Assert.assertTrue("补偿的是子节点那一条", rep.compensated.get(0).contains("g.c1"));
        Assert.assertEquals(Status.SUCCESS, rep.outcome());
    }

    /** 进度: 组段行进 items(setup/setdown 可见), 但 total/done 只算主段; 组视图分母不变。 */
    @Test
    @SuppressWarnings("unchecked")
    public void progressSeesGroupSegmentsWithoutInflatingTotals() {
        publish("grpSegProg", groupSyncDef("ok", "ok", "ok", null));
        ProcessExecution r = run("grpSegProg");

        Map<String, Object> p = facade.progress(r.getProcInstId());
        Assert.assertEquals("分母只算主段: c1 + c2 + post", 3, p.get("total"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) p.get("items");
        Assert.assertEquals("明细全量: prepare + c1 + c2 + restore + post", 5, items.size());
        int setup = 0;
        int setdown = 0;
        for (Map<String, Object> it : items) {
            if ("setup".equals(it.get("segment"))) {
                setup++;
            } else if ("setdown".equals(it.get("segment"))) {
                setdown++;
            }
        }
        Assert.assertEquals("组段行在明细里带段可见", 1, setup);
        Assert.assertEquals(1, setdown);

        List<Map<String, Object>> groups = (List<Map<String, Object>>) p.get("groups");
        Assert.assertEquals(1, groups.size());
        Assert.assertEquals("组视图分母仍只算主段", 2, groups.get(0).get("total"));
        Assert.assertEquals(2, groups.get(0).get("done"));
        Assert.assertEquals("组视图明细含两条段行", 4, ((List<Object>) groups.get(0).get("items")).size());
    }

    // ============ 9) v1.4: 组级段 = 叶子子节点引用 ============

    /** 组级节点段: setup 段节点 -> 主体子节点 -> setdown 段节点 -> 后续根; 段是组的子行, 组容器零段行。 */
    @Test
    public void groupNodeSegmentsWrapChildrenInOrder() {
        publish("grpNodeSegOk", groupNodeSegDef("ok", "ok", "ok", null));
        ProcessExecution r = run("grpNodeSegOk");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        String pid = r.getProcInstId();

        Assert.assertEquals("段节点 -> 主体 -> 段节点 -> 后续根",
                "[handler:ok:g.prepare, handler:ok:g.c1, handler:ok:g.c2, handler:ok:g.restore, handler:ok:post]",
                rec.order.toString());

        NodeInstEntity group = nodeByDef(pid, "g");
        Assert.assertEquals("组容器零段行(段是子节点)", 0,
                actionMapper.findInContainer(group.getNodeInstId(), null).size());
        List<NodeInstEntity> children = nodeMapper.findChildren(pid, group.getNodeInstId());
        Assert.assertEquals("段节点 + 主体 = 4 条子行", 4, children.size());
        Assert.assertEquals("头部就是 setup 段节点", "g.prepare", children.get(0).getNodeDefId());
        Assert.assertEquals("尾部就是 setdown 段节点", "g.restore", children.get(3).getNodeDefId());
        Assert.assertEquals(Status.SUCCESS.getStatus(), group.getStatus());
    }

    /** 声明位置等价: 段/开关写在 group: 段内(与 flow 级 setup/flow/setdown 同形)走同一条组回路, 子行序不变。 */
    @Test
    public void groupSegmentsDeclaredInsideGroupSectionRunSameCircuit() {
        publish("grpNodeSegInner", ""
                + "flow: \"g,post\"\n"
                + "nodes:\n"
                + "  g:\n"
                + "    group:\n"
                + "      setup: [prepare]\n"
                + "      flow: \"c1\"\n"
                + "      setdown: [restore]\n"
                + "      nodes:\n"
                + "        prepare:\n          handler: ok\n"
                + "        c1:\n          handler: ok\n"
                + "        restore:\n          handler: ok\n"
                + "  post:\n    handler: ok\n");
        ProcessExecution r = run("grpNodeSegInner");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());
        String pid = r.getProcInstId();

        Assert.assertEquals("段节点 -> 主体 -> 段节点 -> 后续根",
                "[handler:ok:g.prepare, handler:ok:g.c1, handler:ok:g.restore, handler:ok:post]",
                rec.order.toString());

        NodeInstEntity group = nodeByDef(pid, "g");
        Assert.assertEquals("组容器零段行(段是子节点)", 0,
                actionMapper.findInContainer(group.getNodeInstId(), null).size());
        Assert.assertEquals("段节点 + 主体 = 3 条子行", 3,
                nodeMapper.findChildren(pid, group.getNodeInstId()).size());
    }

    /**
     * setup 段节点失败: 主体不驱动(段是节点引用 — 下标对齐会把主体行补齐为 CREATED, 留给 retry 续跑,
     * 与流程级段根同口径), setdown 段节点照跑(finally); 流程 FAILED, 后续根不跑。
     */
    @Test
    public void groupNodeSegmentSetupFailureSkipsBodyButRestores() {
        publish("grpNodeSegSetupFail", groupNodeSegDef("thrower", "ok", "ok", null));
        ProcessExecution r = run("grpNodeSegSetupFail");
        Assert.assertEquals(Status.FAILED, r.getStatus());
        String pid = r.getProcInstId();

        NodeInstEntity group = nodeByDef(pid, "g");
        List<NodeInstEntity> children = nodeMapper.findChildren(pid, group.getNodeInstId());
        Assert.assertEquals("setup 段节点挂了", Status.FAILED.getStatus(), children.get(0).getStatus());
        Assert.assertTrue("setdown 段节点照跑(finally)", rec.order.contains("handler:ok:g.restore"));
        Assert.assertFalse("主体不驱动", rec.order.contains("handler:ok:g.c1"));
        Assert.assertEquals("主体行补齐但停在 CREATED(下标对齐, 留给 retry)",
                Status.CREATED.getStatus(), children.get(1).getStatus());
        Assert.assertFalse("FAIL_FAST: 后续根不跑", rec.order.contains("handler:ok:post"));
        Assert.assertEquals(Status.FAILED.getStatus(), group.getStatus());
    }

    /** 主体让渡: setdown 段节点不跑、组行不落终态; 重入后段节点幂等跳过、主体续跑、收尾补上。 */
    @Test
    public void groupNodeSegmentBodyYieldDefersSetdownUntilReentrant() {
        publish("grpNodeSegYield", groupNodeSegDef("ok", "pendingOnce", "ok", null));
        ProcessExecution r1 = run("grpNodeSegYield");
        String pid = r1.getProcInstId();
        Assert.assertEquals(Status.PENDING, r1.getStatus());
        Assert.assertFalse("让渡不跑 setdown 段节点", rec.order.contains("handler:ok:g.restore"));
        Assert.assertEquals("组行不落终态", Status.RUNNING.getStatus(), nodeByDef(pid, "g").getStatus());

        ProcessExecution r2 = facade.start(pid);   // pending 由调度重捞; 这里手动重入同步触发
        Assert.assertEquals(Status.SUCCESS, r2.getStatus());
        Assert.assertEquals("已成功的 setup 段节点幂等跳过", 1,
                Collections.frequency(rec.order, "handler:ok:g.prepare"));
        Assert.assertEquals("主体续跑成功", 2, Collections.frequency(rec.order, "handler:pendingOnce:g.c1"));
        Assert.assertEquals("让渡段收尾补上", 1, Collections.frequency(rec.order, "handler:ok:g.restore"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "g").getStatus());
    }

    /** 成对重臂: 主体失败 + setdown 段节点已成 + retry -> 段节点行(含其动作行)成对复位重跑, 成功主体不重跑。 */
    @Test
    public void groupNodeSegmentsPairedReArmAfterBodyRetry() {
        publish("grpNodeSegRearm", groupNodeSegDef("ok", "flaky", "ok", "CONTINUE_ON_ERROR"));
        ProcessExecution r1 = run("grpNodeSegRearm");
        Assert.assertEquals(Status.FAILED, r1.getStatus());
        String pid = r1.getProcInstId();
        Assert.assertEquals(1, Collections.frequency(rec.order, "handler:ok:g.prepare"));
        Assert.assertEquals(1, Collections.frequency(rec.order, "handler:ok:g.c2"));

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("成对重臂: setup 段节点重跑(现场重施)", 2,
                Collections.frequency(rec.order, "handler:ok:g.prepare"));
        Assert.assertEquals("失败主体续跑成功", 2, Collections.frequency(rec.order, "handler:flaky:g.c1"));
        Assert.assertEquals("成功主体不重跑", 1, Collections.frequency(rec.order, "handler:ok:g.c2"));
        Assert.assertEquals("setdown 段节点重新收尾", 2, Collections.frequency(rec.order, "handler:ok:g.restore"));
        Assert.assertEquals("post 首轮已成: 不重跑", 1, Collections.frequency(rec.order, "handler:ok:post"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "g").getStatus());
    }

    /** repeatSegments: 每趟驱动段节点无条件重跑(节点行 + 其动作行一起复位), 成功主体不重跑。 */
    @Test
    public void groupNodeSegmentRepeatSegmentsRerunsEveryDrive() {
        publish("grpNodeSegRepeat", "flow: \"g\"\nnodes:\n  g:\n    repeatSegments: true\n"
                + "    setup: [prepare]\n    setdown: [restore]\n"
                + "    group:\n      flow: \"c1\"\n      nodes:\n"
                + "        c1:\n          handler: ok\n"
                + "        prepare:\n          handler: ok\n"
                + "        restore:\n          handler: ok\n");
        ProcessExecution r1 = run("grpNodeSegRepeat");
        Assert.assertEquals(Status.SUCCESS, r1.getStatus());
        String pid = r1.getProcInstId();

        // 倒回"三段刚成、组行未落终态"窗口
        NodeInstEntity group = nodeByDef(pid, "g");
        nodeMapper.setStatus(group.getNodeInstId(), Status.RUNNING.getStatus(), "u1");
        processMapper.casStatus(pid, Status.SUCCESS.getStatus(), Status.RUNNING.getStatus(), "u1");

        Assert.assertTrue(facade.retry(pid, "u1"));
        Assert.assertEquals(Status.SUCCESS, facade.start(pid).getStatus());

        Assert.assertEquals("每趟驱动 setup 段节点都重跑", 2,
                Collections.frequency(rec.order, "handler:ok:g.prepare"));
        Assert.assertEquals("主体已成功: 不重跑", 1, Collections.frequency(rec.order, "handler:ok:g.c1"));
        Assert.assertEquals("本趟收尾补跑", 2, Collections.frequency(rec.order, "handler:ok:g.restore"));
        Assert.assertEquals(Status.SUCCESS.getStatus(), nodeByDef(pid, "g").getStatus());
    }

    /** 段节点可自带分片: setup 段节点逐 lane 一圈(3 片 -> 3 趟), 主体与收尾不受影响。 */
    @Test
    public void groupNodeSegmentShardedSetupRunsPerLane() {
        publish("grpNodeSegShard", "flow: \"g\"\nnodes:\n  g:\n"
                + "    setup: [prepare]\n    setdown: [restore]\n"
                + "    group:\n      flow: \"c1\"\n      nodes:\n"
                + "        c1:\n          handler: ok\n"
                + "        prepare:\n          shard: dbTopology\n          actions:\n"
                + "            - id: stop\n              handler: ok\n"
                + "        restore:\n          handler: ok\n");
        ProcessExecution r = run("grpNodeSegShard");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        Assert.assertEquals("分片段节点每 lane 一趟", 3, Collections.frequency(rec.order, "handler:ok:stop"));
        NodeInstEntity prepare = nodeByDef(r.getProcInstId(), "g.prepare");
        Assert.assertEquals("段节点自带 lane 行", 3, laneMapper.findByNode(prepare.getNodeInstId()).size());
        Assert.assertEquals("分片段节点每 lane 一条 main 动作行", 3,
                actionMapper.find(prepare.getNodeInstId(), null).size());
    }

    /** 回退口径(刻意差异): 段节点的动作行 segment=main -> 可补偿动作与主体一起进回退(与内联段行被排除不同)。 */
    @Test
    public void rollbackSeesGroupNodeSegmentActions() {
        publish("grpNodeSegRb", "flow: \"g\"\nnodes:\n  g:\n"
                + "    setup: [prepare]\n    setdown: [restore]\n"
                + "    group:\n      flow: \"c1\"\n      nodes:\n"
                + "        c1:\n          handler: compOk\n"
                + "        prepare:\n          handler: compOk\n"
                + "        restore:\n          handler: compOk\n");
        ProcessExecution r = run("grpNodeSegRb");
        Assert.assertEquals(Status.SUCCESS, r.getStatus());

        RollbackService.RollbackReport rep = facade.doRollback(r.getProcInstId(), "u1");
        Assert.assertEquals("段节点与主体的可补偿动作全进补偿", 3, rep.compensated.size());
        Assert.assertTrue("含 setup 段节点那条(与内联段行被排除不同)", rep.compensated.contains("g.prepare"));
        Assert.assertTrue("含 setdown 段节点那条", rep.compensated.contains("g.restore"));
        Assert.assertTrue("不 blocked", rep.blocked.isEmpty());
        Assert.assertEquals(Status.SUCCESS, rep.outcome());
    }

    // ============ 辅助 ============

    /** 按定义引用键找树上的节点行(组/子节点都行)。 */
    private NodeInstEntity nodeByDef(String pid, String defId) {
        for (NodeInstEntity root : nodeMapper.findRoots(pid)) {
            NodeInstEntity hit = findInSubtree(root, defId, pid);
            if (hit != null) {
                return hit;
            }
        }
        Assert.fail("找不到节点: " + defId);
        return null;
    }

    private NodeInstEntity findInSubtree(NodeInstEntity n, String defId, String pid) {
        if (defId.equals(n.getNodeDefId())) {
            return n;
        }
        for (NodeInstEntity c : nodeMapper.findChildren(pid, n.getNodeInstId())) {
            NodeInstEntity hit = findInSubtree(c, defId, pid);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** v1.4 组级节点段定义: setup=[prepare] / setdown=[restore], 主体 c1,c2 与段节点同在 group 内声明。 */
    private static String groupNodeSegDef(String setupHandler, String childHandler, String setdownHandler, String policy) {
        return "flow: \"g,post\"\n"
                + "nodes:\n"
                + "  g:\n"
                + (policy == null ? "" : "    errorPolicy: " + policy + "\n")
                + "    setup: [prepare]\n"
                + "    setdown: [restore]\n"
                + "    group:\n      flow: \"c1,c2\"\n      nodes:\n"
                + "        c1:\n          handler: " + childHandler + "\n"
                + "        c2:\n          handler: ok\n"
                + "        prepare:\n          handler: " + setupHandler + "\n"
                + "        restore:\n          handler: " + setdownHandler + "\n"
                + "  post:\n    handler: ok\n";
    }

    /** 找组容器内某段的单行(组没有 MAIN 动作, 容器行就是 setup/setdown 两条段行)。 */
    private ActionInstEntity segmentRow(NodeInstEntity group, ActionSegment segment) {
        for (ActionInstEntity a : actionMapper.findInContainer(group.getNodeInstId(), null)) {
            if (segment.getStatus().equals(a.getSegment())) {
                return a;
            }
        }
        Assert.fail("找不到段行: " + segment);
        return null;
    }
}
