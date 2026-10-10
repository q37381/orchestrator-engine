package com.tesla.ities.process.integration;

import com.tesla.ities.process.api.ProcessExecution;
import com.tesla.ities.process.core.model.NodeType;
import com.tesla.ities.process.core.model.Status;
import com.tesla.ities.process.domain.ActionInstEntity;
import com.tesla.ities.process.domain.LaneEntity;
import com.tesla.ities.process.domain.NodeInstEntity;
import com.tesla.ities.process.util.Json;
import org.junit.Assert;
import org.junit.Test;

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
}
