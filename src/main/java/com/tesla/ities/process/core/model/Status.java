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
    /** 人工暂停(可恢复) */
    PAUSED(0),
    /** 系统挂起(预留: 等待外部信号/定时) */
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

    /** 按 severity 取"更差"的一方(同优先级取 a)。 */
    public static Status aggregate(Status a, Status b) {
        return a == null ? b : (b == null ? a : (a.severity >= b.severity ? a : b));
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
