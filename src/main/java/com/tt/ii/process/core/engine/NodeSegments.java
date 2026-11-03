package com.tt.ii.process.core.engine;

import com.tt.ii.process.constants.ReservedKeys;
import com.tt.ii.process.core.context.Context;
import com.tt.ii.process.core.context.NodeContext;
import com.tt.ii.process.core.define.ActionDef;
import com.tt.ii.process.core.model.ActionSegment;
import com.tt.ii.process.core.model.ErrorPolicy;
import com.tt.ii.process.core.model.Status;
import com.tt.ii.process.core.runtime.ContextFactory;
import com.tt.ii.process.domain.ActionInstEntity;
import com.tt.ii.process.domain.LaneEntity;
import com.tt.ii.process.domain.NodeInstEntity;
import com.tt.ii.process.util.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * 某个节点(可带一条 lane)的**段执行器**: 构造时绑定现场(dc/env/ctx/lane), 方法只带"段"本身。
 *
 * 之前的写法每个方法都要带 6~8 个参数(dc, env, ctx, lane, segment, defs, existing, finallyMode)且
 * 到处重复派生(laneId/shardKey/laneCtx/actor/policy), 调用点一长串位置参数很难读。现在现场只绑一次,
 * 段方法只回答"跑哪个段、要不要 finally 语义"。
 *
 * 职责(与拆分前逐字一致):
 *  - 重入: 终态行算 SUCCESS 计入聚合而不重跑; created/非终态行重跑(at-least-once);
 *  - 收敛: 普通段按行冻结策略; {@code finallyMode}(setdown 段)恒不收敛、不查取消闸门;
 *  - 档: 并行只跟随主段(NodeDef.actionExecution); 段与 action 级段恒串行;
 *  - 提前结束(收敛/取消)给段内**已物化但没跑到**的剩余行落 STOPPED/DRAIN(未物化的绝不补建);
 *  - action 级段(v1.2): 单项外挂"级 setup 段 -> 主体 -> 级 setdown 段(finally)", 定义驱动入口。
 *
 * 本类**不写容器行状态**(统一归回路收尾, 见不变式 I1)。
 */
final class NodeSegments {

    private final DriveContext dc;
    private final NodeEnv env;
    private final NodeContext ctx;
    private final LaneEntity lane;
    private final ActionRows actionRows;
    private final ContextFactory ctxFactory;
    private final WalkLoop loops;

    // 现场派生(构造一次, 段方法里不再重复取); laneCtx 仍按段现建(上下文是可变对象, 不能跨段共享)
    private final NodeInstEntity node;
    private final String laneId;
    private final String shardKey;
    private final String actor;
    private final ErrorPolicy policy;
    /** lane 上下文按**整条 lane 回路**复用: setup/main/setdown 共享同一桶, 不再按段重建丢交接。 */
    private Context laneCtx;

    NodeSegments(DriveContext dc, NodeEnv env, NodeContext ctx, LaneEntity lane,
                 ActionRows actionRows, ContextFactory ctxFactory, WalkLoop loops) {
        this.dc = dc;
        this.env = env;
        this.ctx = ctx;
        this.lane = lane;
        this.actionRows = actionRows;
        this.ctxFactory = ctxFactory;
        this.loops = loops;
        this.node = ctx.inst();
        this.laneId = lane != null ? lane.getLaneId() : null;
        this.shardKey = lane != null ? lane.getShardKey() : null;
        this.actor = ctx.userId();
        this.policy = ErrorPolicy.of(node.getErrorPolicy());
    }

    /**
     * 段级走查: 取本段全量行后走 {@link #walkRows}。
     * 空段短路——不查库不建行(容器行收尾归回路)。
     */
    Status walk(ActionSegment segment, List<ActionDef> defs, boolean finallyMode) {
        if (defs.isEmpty()) {
            return Status.SUCCESS;
        }
        List<ActionInstEntity> existing = actionRows.findInContainer(node.getNodeInstId(), laneId, segment);
        return walkRows(segment, defs, existing, finallyMode, laneContext());
    }

    /**
     * setup 段执行: 普通轮按段走查; 收尾轮只做行判定(不执行、不补建)。
     * 收尾轮口径归段执行器, 三种回路(节点/组内联/共享段)不再各写一遍这个分支。
     */
    Status walkSetup(List<ActionDef> defs) {
        return dc.isSettle() ? settle(ActionSegment.SETUP, defs) : walk(ActionSegment.SETUP, defs, false);
    }

    /**
     * 段级走查本体(主段/节点段/action 级段共用): existing 由调用方给定——
     * 主段/节点段 = 段值全量取行; **action 级段 = 段值 + 定义成员表过滤后的行集**(同节点多个级段互不串)。
     *
     * 串行档逐项: 定位(执行到第 i 个才落库) -> [主段的级段动作走单项电路] -> 终态跳过 -> 执行;
     * 并行档只跟随主段, 先补齐全部行(扇出需同时在), 只提交非终态行(重入的终态行不进池)。
     */
    private Status walkRows(ActionSegment segment, List<ActionDef> defs, List<ActionInstEntity> existing,
                            boolean finallyMode, Context laneCtx) {
        if (defs.isEmpty()) {
            return Status.SUCCESS;
        }
        SegmentPlan plan = new SegmentPlan(segment, defs, existing);
        SegmentStep step = new SegmentStep(plan, finallyMode, laneCtx);
        boolean parallel = segment == ActionSegment.MAIN && node.getDef().isActionParallel();
        if (parallel) {
            return loops.fanout(WalkLoop.Pool.ACTION, dc, ctx, defs.size(), step, policy,
                    "action 并行子任务异常", "action");
        }
        return loops.serial(dc, 0, defs.size(), step);
    }

    /**
     * 本 lane 的上下文: **构造期只建一次**, setup/main/setdown/action 级段共用。
     * 旧实现按段各建一份, 导致 setup 里 ctx.upward().putResult 写到 main/setdown 时读不到;
     * 非分片自然回落到节点 ctx。
     */
    private Context laneContext() {
        if (lane == null) {
            return ctx;
        }
        if (laneCtx == null) {
            laneCtx = ctxFactory.lane(ctx, lane);
        }
        return laneCtx;
    }

    /** lane 收尾随状态一起落库的 result JSON; 非分片 lane 返回 null(节点行由 NodeDriver 落)。 */
    String laneResultJson() {
        return lane == null ? null : Json.write(laneContext().result());
    }

    /**
     * 收尾轮(settle)的主段判定: **不执行 handler、绝不补建行**——
     *  - 已物化的 CREATED/PENDING 行落 STOPPED + DRAIN(没跑过, 停止后也不该再跑);
     *  - RUNNING 行不动(上一次 owner 的现场留给 retry 复位);
     *  - 只要存在未完成/坏终态行就返回 STOPPED; 全部 completedOk 才 SUCCESS。
     * 这里刻意不调用 runAction: 串行档的闸门兜底在 fanout 分支不生效, 收尾轮必须从结构上禁止推进主链。
     */
    Status settleMain(List<ActionDef> defs) {
        if (defs.isEmpty()) {
            return Status.SUCCESS;
        }
        Status agg = Status.SUCCESS;
        for (ActionInstEntity a : actionRows.findInContainer(node.getNodeInstId(), laneId, ActionSegment.MAIN)) {
            Status st = Status.of(a.getStatus());
            if (st == null || !st.isTerminal()) {
                drainRow(a);
                agg = Status.STOPPED;
            } else if (!st.isCompletedOk()) {
                agg = Status.STOPPED;   // 停止收尾不把上一轮坏态再放大成流程 FAILED(setdown 真失败才 FAILED)
            }
        }
        return agg;
    }

    /**
     * 收尾轮(settle)的段判定: **不执行任何动作**, 只看已物化行——
     *  - 段内行齐且全部"已完成"(SUCCESS/SKIPPED) => SUCCESS: 现场完好, 主体可以继续走到收尾;
     *  - 否则(缺行 / 未完成 / 坏终态) => STOPPED: 当作"前置没做完", 主体不再跑, setdown 照跑 finally。
     * 缺行判据按定义侧有效键去重(与 mainPending 同口径): LAZY 下没跑到尾就是缺行。
     * 不补建行、不 drain: 停止不是重试, 行状态保持执行事实。
     */
    Status settle(ActionSegment segment, List<ActionDef> defs) {
        if (defs.isEmpty()) {
            return Status.SUCCESS;
        }
        Set<String> keys = WalkSupport.keysOf(defs);
        List<ActionInstEntity> rows = actionRows.findInContainer(node.getNodeInstId(), laneId, segment);
        if (rows.size() < keys.size()) {
            return Status.STOPPED;   // 段没跑全(LAZY 缺行): 前置未完成
        }
        for (ActionInstEntity a : rows) {
            Status st = Status.of(a.getStatus());
            if (st == null || !st.isCompletedOk()) {
                return Status.STOPPED;   // 未完成/坏终态: 主体不再跑(setdown 仍 finally 补跑)
            }
        }
        return Status.SUCCESS;
    }

    /** 给本段已物化未执行行统一 drain(主段整体跳过/setup 段坏时用); 未物化的不补建。 */
    void drain(ActionSegment segment) {
        for (ActionInstEntity a : actionRows.findInContainer(node.getNodeInstId(), laneId, segment)) {
            drainRow(a);
        }
    }

    /**
     * action 级段电路(v1.2, 单项外挂: 级 setup 段 -> 主体 -> 级 setdown 段{finally}): "配置 -> 该动作 -> 恢复"
     * 整体包住, 收尾不必等主段全部完成(节点段表达不了的中段还原)。
     *
     * 定义驱动入口(关键): 主体行即使为终态也进入本方法——终态行按"已成功跳过"口径消化,
     * 未完成的级 setdown 段由此**天然补跑**("崩在主体成功后、级段收尾前"无需额外机制)。
     *
     * 级段重臂(判据按级段收窄): 级 setup 段或主体存在非终态行 且 级 setdown 段存在 success 行
     * -> 复位本级段 setup/setdown 的 success 行(只按本级段成员行复位, 不整段复位——多个级段并存会误伤)。
     */
    private Status actionCircuit(ActionDef ad, ActionInstEntity subject, Context laneCtx) {
        List<ActionDef> setupDefs = WalkSupport.orEmpty(ad.getSetup());
        List<ActionDef> setdownDefs = WalkSupport.orEmpty(ad.getSetdown());
        List<ActionInstEntity> setupRows = actionSegmentRows(ActionSegment.ACTION_SETUP, setupDefs);
        List<ActionInstEntity> setdownRows = actionSegmentRows(ActionSegment.ACTION_SETDOWN, setdownDefs);

        Status subjectStatus = Status.of(subject.getStatus());
        boolean willReRun = WalkSupport.hasNonTerminal(setupRows)
                || subjectStatus == null || !subjectStatus.isTerminal();
        if (willReRun && WalkSupport.hasSuccess(setdownRows)) {
            actionRows.reArmRows(setupRows, false, actor);
            actionRows.reArmRows(setdownRows, false, actor);
            // 复位后重取(内存副本还是旧状态, 不重取会被"终态跳过"口径吞掉)
            setupRows = actionSegmentRows(ActionSegment.ACTION_SETUP, setupDefs);
            setdownRows = actionSegmentRows(ActionSegment.ACTION_SETDOWN, setdownDefs);
        }
        return CircuitWalk.run(new ActionCircuit(ad, subject, subjectStatus,
                setupDefs, setdownDefs, setupRows, setdownRows, laneCtx));
    }

    /**
     * 本级段成员行: 段值取行 + **定义成员表**收窄(action 级段无锚列, 成员归属靠定义侧唯一性保证; 多个级段互不串)。
     * 行序 = auto_id 升序 = 段内声明顺序, 与定义按下标对齐(与定位口径一致)。
     */
    private List<ActionInstEntity> actionSegmentRows(ActionSegment segment, List<ActionDef> defs) {
        List<ActionInstEntity> all = actionRows.findInContainer(node.getNodeInstId(), laneId, segment);
        if (all.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> keys = WalkSupport.keysOf(defs);
        List<ActionInstEntity> out = new ArrayList<>();
        for (ActionInstEntity a : all) {
            if (keys.contains(a.getActionDefId())) {
                out.add(a);
            }
        }
        return out;
    }

    /** 段内剩余行清理: 只处理 CREATED/PENDING(没跑过), 落 STOPPED + __skipReason=DRAIN, 原 result 并入不丢。 */
    private void drainRow(ActionInstEntity a) {
        if (Status.CREATED.getStatus().equals(a.getStatus()) || Status.PENDING.getStatus().equals(a.getStatus())) {
            Map<String, Object> res = new LinkedHashMap<>(Json.readMap(a.getResult()));
            res.put(ReservedKeys.SKIP_REASON, ReservedKeys.DRAIN);
            env.actions().markActionDrained(a, Status.STOPPED.getStatus(), Json.write(res), actor);
        }
    }

    // ===================== 段内项的定位/驱动/清理(具名小件) =====================

    /**
     * 一段的定位口径: 执行到第 i 个才落库(LAZY 缺行补建; EAGER/重入命中已存在行);
     * 提前结束时清理 [from, 末尾) 的**已物化**行(未物化的绝不补建)。
     */
    private final class SegmentPlan {
        private final ActionSegment segment;
        private final List<ActionDef> defs;
        private final List<ActionInstEntity> existing;
        /** 本趟定位过的行(含扇出 prepare 新建的行; existing 只含进入本趟前库里的行)。 */
        private final Map<Integer, ActionInstEntity> located = new HashMap<>();

        SegmentPlan(ActionSegment segment, List<ActionDef> defs, List<ActionInstEntity> existing) {
            this.segment = segment;
            this.defs = defs;
            this.existing = existing;
        }

        ActionInstEntity locate(int i) {
            ActionInstEntity a = i < existing.size()
                    ? existing.get(i)
                    : actionRows.ensureActionRow(dc, node, laneId, defs.get(i), shardKey, segment);
            located.put(i, a);
            return a;
        }

        void drainFrom(int from) {
            // 旧行可能多于定义项(历史/重复键), 两边界取大: 只 drain 已存在/已定位的行, 未物化的不补建
            int end = Math.max(defs.size(), existing.size());
            for (int i = from; i < end; i++) {
                drainOne(i);
            }
        }

        /** 只 drain 已定位/已存在的行; 未物化的项绝不补建。 */
        void drainOne(int i) {
            ActionInstEntity a = located.get(i);
            if (a == null && i < existing.size()) {
                a = existing.get(i);
            }
            if (a != null) {
                drainRow(a);
            }
        }
    }

    /** 本层的一项: 定位 -> 级段电路(仅主段) -> 终态跳过 -> 执行; 扇出档只提交非终态行。 */
    private final class SegmentStep implements WalkStep, WalkLoop.FanoutTask {
        private final SegmentPlan plan;
        private final boolean finallyMode;
        private final Context laneCtx;

        SegmentStep(SegmentPlan plan, boolean finallyMode, Context laneCtx) {
            this.plan = plan;
            this.finallyMode = finallyMode;
            this.laneCtx = laneCtx;
        }

        @Override
        public Status drive(int index) {
            ActionInstEntity a = plan.locate(index);
            ActionDef ad = plan.defs.get(index);
            if (plan.segment == ActionSegment.MAIN && WalkSupport.hasActionSegments(ad)) {
                // action 级段: 定义驱动入口——主体行即使终态也进段电路(未完成的级 setdown 段由此天然补跑)
                return actionCircuit(ad, a, laneCtx);
            }
            Status st = Status.of(a.getStatus());
            if (st != null && st.isTerminal()) {
                return Status.SUCCESS;   // 已跑过(重入): 终态行不重跑, 也不把上一轮终态再算一遍
            }
            // created(没跑过)与非终态(上轮让渡/中断的运行态)都重跑 —— at-least-once 契约
            return env.actions().runAction(dc, laneCtx, a, ad);
        }

        /** 扇出备料(仅主段并行档): 已终态的重入行不提交; 非终态(上轮让渡/中断)照常重跑。 */
        @Override
        public Callable<Status> prepare(int index) {
            ActionInstEntity a = plan.locate(index);
            Status st = Status.of(a.getStatus());
            if (st != null && st.isTerminal()) {
                return null;
            }
            return new RunActionUnit(a, plan.defs.get(index), laneCtx);
        }

        @Override
        public void drainCancelled(int index) {
            plan.drainOne(index);
        }

        @Override
        public void drainRemaining(int from) {
            plan.drainFrom(from);
        }

        @Override
        public boolean stops(Status one, int index) {
            // setdown 段恒不收敛(finally, 某项坏不拦剩余项); 其余按行冻结策略
            return !finallyMode && policy == ErrorPolicy.FAIL_FAST
                    && (one == Status.FAILED || one == Status.STOPPED);
        }

        @Override
        public boolean cancelled() {
            // setdown 段不查(收尾必达); 其余每步查库
            return !finallyMode && env.gate().isCancelled(dc);
        }

        @Override
        public void drainFrom(int from) {
            plan.drainFrom(from);
        }
    }

    /** 扇出的一项: 跑单个 action(具名类代替 lambda, 栈里能看到单元来源)。 */
    private final class RunActionUnit implements Callable<Status> {
        private final ActionInstEntity action;
        private final ActionDef ad;
        private final Context laneCtx;

        RunActionUnit(ActionInstEntity action, ActionDef ad, Context laneCtx) {
            this.action = action;
            this.ad = ad;
            this.laneCtx = laneCtx;
        }

        @Override
        public Status call() {
            // 排队期间命中停止: 不再调用 handler; 行当场落 STOPPED/DRAIN(未开始时 join 也会走 drainCancelled)
            if (env.gate().isCancelled(dc)) {
                Map<String, Object> res = new LinkedHashMap<>(Json.readMap(action.getResult()));
                res.put(ReservedKeys.SKIP_REASON, ReservedKeys.DRAIN);
                env.actions().markActionDrained(action, Status.STOPPED.getStatus(), Json.write(res),
                        dc.processContext().userId());
                return Status.STOPPED;
            }
            return env.actions().runAction(dc, laneCtx, action, ad);
        }
    }

    /** action 级段的三段电路: 级 setup -> 主体单项 -> 级 setdown(finally)。 */
    private final class ActionCircuit implements CircuitWalk.SectionedCircuit {
        private final ActionDef ad;
        private final ActionInstEntity subject;
        private final Status subjectStatus;
        private final List<ActionDef> setupDefs;
        private final List<ActionDef> setdownDefs;
        private final List<ActionInstEntity> setupRows;
        private final List<ActionInstEntity> setdownRows;
        private final Context laneCtx;

        ActionCircuit(ActionDef ad, ActionInstEntity subject, Status subjectStatus,
                      List<ActionDef> setupDefs, List<ActionDef> setdownDefs,
                      List<ActionInstEntity> setupRows, List<ActionInstEntity> setdownRows,
                      Context laneCtx) {
            this.ad = ad;
            this.subject = subject;
            this.subjectStatus = subjectStatus;
            this.setupDefs = setupDefs;
            this.setdownDefs = setdownDefs;
            this.setupRows = setupRows;
            this.setdownRows = setdownRows;
            this.laneCtx = laneCtx;
        }

        @Override
        public Status setup() {
            return walkRows(ActionSegment.ACTION_SETUP, setupDefs, setupRows, false, laneCtx);
        }

        /** 主体(单项): 取消 -> drain + STOPPED(收尾照跑); 终态跳过; 否则执行并归一。 */
        @Override
        public Status main() {
            if (env.gate().isCancelled(dc)) {
                drainRow(subject);
                return Status.STOPPED;
            }
            if (subjectStatus != null && subjectStatus.isTerminal()) {
                return Status.SUCCESS;   // 已跑过(重入): 终态行不重跑
            }
            Status raw = env.actions().runAction(dc, laneCtx, subject, ad);
            Status one = Status.driveResult(raw);
            if (raw != one) {
                loops.reportIllegal(dc, raw, "node=" + ctx.name() + "/action");   // 与段模板同口径: 兜底归一必须留痕
            }
            return one;
        }

        @Override
        public void onSetupBad() {
            drainRow(subject);   // 级 setup 段坏: 主体跳过(drain 留痕; 未物化不补建)
        }

        @Override
        public Status setdown() {
            return walkRows(ActionSegment.ACTION_SETDOWN, setdownDefs, setdownRows, true, laneCtx);
        }
    }
}
