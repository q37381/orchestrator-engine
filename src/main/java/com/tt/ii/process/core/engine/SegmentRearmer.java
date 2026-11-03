package com.tt.ii.process.core.engine;

import com.tt.ii.process.core.context.NodeContext;
import com.tt.ii.process.core.define.ActionDef;
import com.tt.ii.process.core.define.DslParser;
import com.tt.ii.process.core.define.NodeDef;
import com.tt.ii.process.core.define.ProcessDefinition;
import com.tt.ii.process.core.model.ActionSegment;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.domain.LaneEntity;
import com.tt.ii.process.domain.NodeInstEntity;
import com.tt.ii.process.mapper.LaneMapper;
import com.tt.ii.process.mapper.NodeInstMapper;

import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 段重臂(FR-NSD-16/17): 段行"已成功永久跳过"的重入语义只对"现场仍生效"的崩溃恢复成立;
 * 一旦 setdown 已跑过(现场已撤)而本轮又有人复位了待跑行(显式 retry), setup 必须重新施加、
 * setdown 必须重新收尾——否则重试的主体在"没有准备"的环境上续跑(典型: 为迁移调大的超时类参数不会重新施加)。
 *
 * 判据(成对重臂, 双条件缺一不可): setup 段存在非终态行 **或主体待跑** 且 setdown 段存在 success 行(现场已撤)。
 * 自然状态下 setdown 能执行的前提是主体全部终态, 故两个条件同时成立只可能是"显式 retry"制造——
 * 崩在 setdown 前(现场仍在) / 三段全成未落容器 / 收尾失败补跑 三类既有场景都不触发, 行为逐字不变。
 * 声明 repeatSegments 时无条件复位(每趟驱动重跑, 承担幂等责任), 且不查主体(开关语义不依赖它)。
 *
 * 本类有四个入口, 对应四种"段"的持有者: 流程级段根 / 叶子节点段 / 组内联段 / 组级节点段。
 * 复位只动段行状态、不动 result; 多条 CAS 中途崩溃下次驱动重判, 幂等。
 */
final class SegmentRearmer {

    private final ActionRows actionRows;
    private final NodeInstMapper nodeMapper;
    private final LaneMapper laneMapper;

    SegmentRearmer(ActionRows actionRows, NodeInstMapper nodeMapper, LaneMapper laneMapper) {
        this.actionRows = actionRows;
        this.nodeMapper = nodeMapper;
        this.laneMapper = laneMapper;
    }

    /**
     * 流程级成对重臂(v1.1, 节点级回路重臂的顶层镜像): 流程级 setup 段节点已成功、setdown 段已跑过(现场已撤),
     * 一次显式重试又把 setup/flow 段的行复位成待跑时, 把 setup/setdown 段的 success 根行一并复位, 让"改—还原"
     * 成对重跑; 顶层 repeatSegments 开启时无条件复位(每趟驱动重跑, 承担幂等责任)。
     *
     * 判据落在根行上(行按 auto_id 与表达式按下标前缀对齐, 与物化/驱动同口径): [0, flowEnd) 存在非终态行
     * 且 [flowEnd, end) 存在 success 行。复位只动 status 不动 result; 多条 CAS 中途崩溃下次驱动重判, 幂等。
     */
    void reArmProcess(DriveContext dc, ProcessDefinition def, List<DslParser.Expr> ordered, int flowEnd) {
        boolean hasSetup = def.setupSize() > 0;
        boolean hasSetdown = ordered.size() > flowEnd;
        if (!hasSetup && !hasSetdown) {
            return;   // 无流程级段: 零开销快路径(不查库)
        }
        if (!def.repeatSegmentsEnabled() && !(hasSetup && hasSetdown)) {
            return;   // 成对重臂只在成对声明时触发(单边声明没有"改—还原"契约)
        }
        List<NodeInstEntity> roots = nodeMapper.findRoots(dc.procInstId());
        if (roots.isEmpty()) {
            return;   // 首驱还没物化任何根行, 没有可重臂的对象
        }
        String actor = dc.processContext().userId();
        if (def.repeatSegmentsEnabled()) {
            reArmNodeRows(roots, 0, def.setupSize(), true, actor);
            reArmNodeRows(roots, flowEnd, ordered.size(), true, actor);
            return;
        }
        boolean willReRun = WalkSupport.hasNonTerminalRows(roots, 0, flowEnd);
        boolean torn = WalkSupport.hasSuccessRows(roots, flowEnd, ordered.size());
        if (willReRun && torn) {
            reArmNodeRows(roots, 0, def.setupSize(), false, actor);
            reArmNodeRows(roots, flowEnd, ordered.size(), false, actor);
        }
    }

    /**
     * 叶子节点段的成对重臂: 主体待跑判据 = 本容器(按 lane 收窄)的 MAIN 段存在非终态行或已物化行数不足。
     * 分片节点每 lane 独立判(按传入的 laneId 收窄容器)。
     */
    void reArmLeaf(NodeContext ctx, String laneId, NodeDef nd) {
        String nodeId = ctx.inst().getNodeInstId();
        reArmPair(ctx, laneId, nd, () -> mainPending(nodeId, laneId, nd.effectiveActions()));
    }

    /**
     * 共享段模式(段不跟随分片)的成对重臂: 段行以 laneId=null 收窄共享段行, 主体待跑换成**跨 lane** 取证
     * (本轮任一 lane 尚未物化, 或该 lane 的 MAIN 行待跑即视为待跑; 只看本轮分片键, 不回扫历史 lane)。
     */
    void reArmLeafShared(NodeContext ctx, NodeDef nd, List<String> shardKeys) {
        String nodeId = ctx.inst().getNodeInstId();
        reArmPair(ctx, null, nd, () -> sharedMainPending(nodeId, shardKeys, nd.effectiveActions()));
    }

    /**
     * 组内联段的成对重臂(v1.3): 判据形状与节点级一致, 主体待跑的取证换成**直属子节点**。
     * 只动本组 nodeInstId 下的段行(lane 恒 null), 不复位任何子节点——已成功的业务子节点不被重跑。
     */
    void reArmGroup(DriveContext dc, NodeContext ctx, NodeDef nd,
                    List<ActionDef> setupDefs, List<ActionDef> setdownDefs, int childExprCount) {
        String nodeId = ctx.inst().getNodeInstId();
        String actor = ctx.userId();
        if (nd.repeatSegmentsEnabled()) {
            if (!setupDefs.isEmpty()) {
                actionRows.reArmSegment(nodeId, null, ActionSegment.SETUP, true, actor);
            }
            if (!setdownDefs.isEmpty()) {
                actionRows.reArmSegment(nodeId, null, ActionSegment.SETDOWN, true, actor);
            }
            return;
        }
        if (setupDefs.isEmpty() || setdownDefs.isEmpty()) {
            return;   // 成对重臂只在成对声明时触发
        }
        boolean willReRun = WalkSupport.hasNonTerminal(
                actionRows.findInContainer(nodeId, null, ActionSegment.SETUP))
                || childrenPending(dc, nodeId, childExprCount);
        if (willReRun && WalkSupport.hasSuccess(
                actionRows.findInContainer(nodeId, null, ActionSegment.SETDOWN))) {
            actionRows.reArmSegment(nodeId, null, ActionSegment.SETUP, false, actor);
            actionRows.reArmSegment(nodeId, null, ActionSegment.SETDOWN, false, actor);
        }
    }

    /**
     * 组级节点段的成对重臂(v1.4, 判据与内联段同构, 复位对象换成段子节点行):
     *  - repeatSegments: 每趟无条件复位段子行(节点行 + 其 MAIN 动作行, 含 per-lane);
     *  - 默认成对: 段有非终态行 / 主体待跑(非终态或缺行) 且 setdown 段有 success 行 -> 两段成对复位。
     * 与内联段的差异只在取证口径: 内联段查段行, 这里查 [0,setupEnd) 与 [setdownStart,end) 的子节点行。
     */
    void reArmGroupNodeSegments(DriveContext dc, NodeContext ctx, NodeDef nd,
                                List<String> setupRefs, List<String> setdownRefs,
                                int setupEnd, int setdownStart, int total) {
        List<NodeInstEntity> children = nodeMapper.findChildren(dc.procInstId(), ctx.inst().getNodeInstId());
        String actor = ctx.userId();
        if (nd.repeatSegmentsEnabled()) {
            reArmNodeRows(children, 0, setupEnd, true, actor);
            reArmNodeRows(children, setdownStart, children.size(), true, actor);
            return;
        }
        if (setupRefs.isEmpty() || setdownRefs.isEmpty()) {
            return;   // 成对重臂只在成对声明时触发(单边声明没有"改—还原"契约)
        }
        boolean willReRun = WalkSupport.hasNonTerminalRows(children, 0, setupEnd)
                || children.size() < total                                  // LAZY 尚有子节点未物化: 本轮必有未跑主体
                || WalkSupport.hasNonTerminalRows(children, setupEnd, setdownStart);
        if (willReRun && WalkSupport.hasSuccessRows(children, setdownStart, children.size())) {
            reArmNodeRows(children, 0, setupEnd, false, actor);
            reArmNodeRows(children, setdownStart, children.size(), false, actor);
        }
    }

    /**
     * 叶子/共享段共用的成对复位本体: 无段/单边 -> 零查询快路径; repeatSegments -> 无条件复位两段终态行;
     * 默认 -> setup 有非终态行或主体待跑 且 setdown 有 success 行时成对复位。
     */
    private void reArmPair(NodeContext ctx, String laneId, NodeDef nd, BooleanSupplier pending) {
        List<ActionDef> setupDefs = WalkSupport.orEmpty(nd.getSetup());
        List<ActionDef> setdownDefs = WalkSupport.orEmpty(nd.getSetdown());
        if (setupDefs.isEmpty() && setdownDefs.isEmpty()) {
            return;   // 无段节点: 零开销快路径(不查库)
        }
        String nodeId = ctx.inst().getNodeInstId();
        String actor = ctx.userId();
        if (nd.repeatSegmentsEnabled()) {
            if (!setupDefs.isEmpty()) {
                actionRows.reArmSegment(nodeId, laneId, ActionSegment.SETUP, true, actor);
            }
            if (!setdownDefs.isEmpty()) {
                actionRows.reArmSegment(nodeId, laneId, ActionSegment.SETDOWN, true, actor);
            }
            return;
        }
        if (setupDefs.isEmpty() || setdownDefs.isEmpty()) {
            return;   // 成对重臂只在成对声明时触发(单边声明没有"改—还原"契约)
        }
        boolean willReRun = WalkSupport.hasNonTerminal(
                actionRows.findInContainer(nodeId, laneId, ActionSegment.SETUP))
                || pending.getAsBoolean();
        if (willReRun && WalkSupport.hasSuccess(
                actionRows.findInContainer(nodeId, laneId, ActionSegment.SETDOWN))) {
            actionRows.reArmSegment(nodeId, laneId, ActionSegment.SETUP, false, actor);
            actionRows.reArmSegment(nodeId, laneId, ActionSegment.SETDOWN, false, actor);
        }
    }

    /**
     * 把节点行 [from, to) 中命中口径的**段节点**复位重跑(allTerminal=false 只复位 success); 下标越界防御在循环内。
     * 节点行复位只是"允许重驱动"——段节点的**动作行**必须一并复位, 否则重驱动在节点回路里会被按行幂等跳过(空转);
     * 分片段节点的动作行按 lane 各复位一次(与节点级每 lane 一个回路同口径); 只动 status、不动 result。
     */
    private void reArmNodeRows(List<NodeInstEntity> rows, int from, int to, boolean allTerminal, String actor) {
        for (int i = from; i < to && i < rows.size(); i++) {
            NodeInstEntity n = rows.get(i);
            Status st = Status.of(n.getStatus());
            if (st == null || !st.isTerminal() || (!allTerminal && st != Status.SUCCESS)) {
                continue;   // 非终态行本轮本就会重跑; 成对重臂只复位 success
            }
            nodeMapper.casStatus(n.getNodeInstId(), st.getStatus(), Status.CREATED.getStatus(), actor);
            actionRows.reArmSegment(n.getNodeInstId(), null, ActionSegment.MAIN, allTerminal, actor);
            for (LaneEntity lane : laneMapper.findByNode(n.getNodeInstId())) {
                actionRows.reArmSegment(n.getNodeInstId(), lane.getLaneId(), ActionSegment.MAIN, allTerminal, actor);
            }
        }
    }

    /**
     * 主体待跑(单容器, 段重臂的惰性判据): 已物化 MAIN 行**不齐**(少于定义侧有效键数)或存在非终态行
     * 即视为"本轮将跑"。缺行也是待跑: LAZY 下尾巴会在本趟回路里补物化并执行——取消/中断停在动作边界时,
     * 已物化的只剩一段全 success 前缀, 只查非终态会漏判(尾巴随后在"已被 setdown 撤除"的现场上续跑,
     * 且 setdown 行已是终态、不会再收尾)。行数按**定义侧有效键去重**计(段行按定义键定位, 主段重复键只物化一行)。
     */
    private boolean mainPending(String nodeId, String laneId, List<ActionDef> defs) {
        Set<String> keys = WalkSupport.keysOf(defs);
        List<ActionInstEntity> main = actionRows.findInContainer(nodeId, laneId, ActionSegment.MAIN);
        return main.size() < keys.size() || WalkSupport.hasNonTerminal(main);
    }

    /** 共享段模式的主体待跑: 本轮任一 lane 尚未物化, 或该 lane 的 MAIN 行待跑(跨全部分片)。 */
    private boolean sharedMainPending(String nodeId, List<String> shardKeys, List<ActionDef> defs) {
        for (String key : shardKeys) {
            LaneEntity lane = laneMapper.findByNodeAndShard(nodeId, key);
            if (lane == null) {
                return true;   // lane 未物化: 本轮必有未跑主体
            }
            if (mainPending(nodeId, lane.getLaneId(), defs)) {
                return true;   // 行不齐/存在待跑行(与组级 childrenPending 的缺行判据同向)
            }
        }
        return false;
    }

    /**
     * 组主体(直属子节点序列)是否待跑: 存在非终态子行, 或已物化行数不足(LAZY 未走到——尤其
     * "setup 成功后、首个子节点物化前就被停掉"手里一行都没有, 全靠缺行判据兜住)。
     * 只看**直属**子行(与物化器同一对齐口径): 不递归扫描终态子组内部的残留行。
     */
    private boolean childrenPending(DriveContext dc, String groupId, int childExprCount) {
        List<NodeInstEntity> rows = nodeMapper.findChildren(dc.procInstId(), groupId);
        if (rows.size() < childExprCount) {
            return true;   // 尚有子节点未物化: 本轮必有未跑主体
        }
        return WalkSupport.hasNonTerminalRows(rows, 0, rows.size());
    }
}
