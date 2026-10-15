package com.tesla.ities.process.core.model;

import java.util.Locale;

/**
 * 统一状态机。命名定稿: CREATED -> PENDING -> RUNNING -> 终态。
 * PENDING = 可被调度器认领(原讨论中的 READY)。
 *
 * 每个状态带一个**小写英文名** status(getStatus()), 作为持久化/比较的规范值:
 * 落库、CAS、Mapper SQL 字面量一律用小写(created/running/...), 不再用 name() 的大写。
 * 反查用 {@link #of(String)}(大小写不敏感), 取代 Status.valueOf。
 *
 * 聚合优先级由 severity 字段声明(数值越大越"坏", 聚合取大者), 不再散落 switch/case 判断:
 *   FAILED(3) > STOPPED(2) > SUCCESS/SKIPPED(1) > 其余(0, 不参与终态聚合)。
 * 即: 只要有一个失败就算失败; 没有失败但有停止就算停止; 全成功/跳过才算完成。
 *
 * 让渡(yield, handler 返回过程态): PENDING/PAUSED/SUSPENDED 三态可作返回值, 语义是"本轮退出驱动、
 * 状态落库等重入"(见 {@link #isYieldState()}); severity=0 会被普通聚合吞掉, 层模板判定退出/收尾必须走
 * {@link #exitAwareAggregate}(坏终态优先 > 让渡 > severity 聚合)。
 * 归一化({@link #driveResult}): 驱动/聚合边界的合法输入只有"终态 + 让渡三态"; created/running 这类
 * 非终态不是让渡(落流程行会变成不可认领/僵尸态), 一律兜底归一为 FAILED——不得静默过聚合。
 */
public enum Status {
    /** 已计划、占位、不可认领(预插入的初始态) */
    CREATED(0),
    /** 已激活、等待调度器认领 */
    PENDING(0),
    /** 执行中 */
    RUNNING(0),
    /** 成功(终态) */
    SUCCESS(1),
    /** 失败(终态) */
    FAILED(3),
    /** 跳过(终态; 对控制流等价于成功完成) */
    SKIPPED(1),
    /** 停止(终态; 人工停或 drain 后) */
    STOPPED(2),
    /** 人工暂停(可恢复; 也可作 handler 的让渡返回值) */
    PAUSED(0),
    /** 系统挂起(也可作 handler 的让渡返回值: 停驻等外部信号/定时, 本期无唤醒入口) */
    SUSPENDED(0);

    private final String status;    // 小写状态英文名(持久化/比较的规范值)
    private final int severity;

    Status(int severity) {
        this.severity = severity;
        this.status = name().toLowerCase(Locale.ROOT);
    }

    /** 小写状态英文名: 落库/CAS/SQL 字面量统一用它(取代原 name() 的大写)。 */
    public String getStatus() {
        return status;
    }

    /** 聚合优先级(终态才有效, 越大越坏)。 */
    public int severity() {
        return severity;
    }

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == SKIPPED || this == STOPPED;
    }

    /** 对 join/控制流而言视为"已完成且不阻断后续" */
    public boolean isCompletedOk() {
        return this == SUCCESS || this == SKIPPED;
    }

    /**
     * 是否为**合法让渡态**(handler 可返回的过程态): PENDING/PAUSED/SUSPENDED。
     * 语义 = 本轮让渡: 退出驱动、状态落库等待重入(pending 由调度重捞 / paused 由 resume / suspended 停驻);
     * running/created 不是让渡态(见 ActionRunner 的返回值校验)。
     */
    public boolean isYieldState() {
        return this == PENDING || this == PAUSED || this == SUSPENDED;
    }

    /** 按 severity 取"更差"的一方(同优先级取 a)。 */
    public static Status aggregate(Status a, Status b) {
        return a == null ? b : (b == null ? a : (a.severity >= b.severity ? a : b));
    }

    /**
     * 驱动/聚合边界归一(层模板在读取驱动返回值时调用): 合法输入只有"终态 + 让渡三态"——
     * 终态与让渡态原样返回; 其余非终态(CREATED/RUNNING)视为非法返回值, 兜底归一为 FAILED
     * (与 FR-YLD-01 同口径: ActionRunner 已在单项边界归一并记 __error, 这里是防上游漏校验的兜底)。
     * null 原样返回(null 不参与聚合)。
     */
    public static Status driveResult(Status s) {
        return (s != null && !s.isTerminal() && !s.isYieldState()) ? FAILED : s;
    }

    /**
     * 让渡感知的聚合(层模板专用, 取代裸 {@link #aggregate}——severity=0 的让渡态会被普通聚合吞成 SUCCESS)。
     *  1) 任一侧是坏终态(FAILED/STOPPED) -> 坏终态优先(已失败就按现状收尾, 不让渡);
     *  2) 任一侧是合法让渡态(PENDING/PAUSED/SUSPENDED) -> 让渡保持;
     *  3) 全是终态 -> 按 severity 聚合(与 {@link #aggregate} 逐字一致)。
     * 入口先过 {@link #driveResult} 归一(幂等): 现行调用点都已在**读取处**归一并留痕(见 LayerWalker.runSerial /
     * Fanout.join), 这里再兜一层是**口径级防呆**——将来新增调用点即使漏归一, 非法非终态也跌不回聚合被吞成 SUCCESS;
     * 本层是纯函数、不留痕(排障靠读取处的日志 + 失败摘要)。
     */
    public static Status exitAwareAggregate(Status agg, Status one) {
        agg = driveResult(agg);   // 防呆 (幂等: 已归一的输入原样返回)
        one = driveResult(one);
        if (agg == FAILED || agg == STOPPED) {
            return agg;
        }
        if (one == FAILED || one == STOPPED) {
            return one;
        }
        if (agg != null && agg.isYieldState()) {
            return agg;
        }
        if (one != null && one.isYieldState()) {
            return one;
        }
        return aggregate(agg, one);
    }

    /** 由持久化的小写状态名反查枚举(大小写不敏感); null/不识别返回 null。 */
    public static Status of(String status) {
        if (status == null) {
            return null;
        }
        for (Status s : values()) {
            if (s.status.equalsIgnoreCase(status)) {
                return s;
            }
        }
        return null;
    }
}
