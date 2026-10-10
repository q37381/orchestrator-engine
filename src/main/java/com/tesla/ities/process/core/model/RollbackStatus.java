package com.tesla.ities.process.core.model;

import java.util.Locale;

/**
 * 回退/补偿步骤状态机(process_rollback_record.status): COMPENSATING -> COMPENSATED / COMPENSATE_FAILED / BLOCKED。
 *
 * 原为 RollbackService 里的四个裸字符串常量, 提为枚举后与全仓同口径:
 * 落库/比较用 {@link #getStatus()} 小写, 反查用 {@link #of(String)}(大小写不敏感)。
 * 补偿历史与运行态 {@link Status} 彻底隔离(原 action_inst 是不可变的执行事实, 不被回写)。
 */
public enum RollbackStatus {
    /** 已占位、补偿执行中 */
    COMPENSATING,
    /** 补偿成功(终态) */
    COMPENSATED,
    /** 补偿自身失败(终态; 挂起告警, 不无限重试) */
    COMPENSATE_FAILED,
    /** 遇不可补偿步骤, 回退到此停止(终态) */
    BLOCKED;

    private final String status;    // 小写状态英文名(落库/比较的规范值)

    RollbackStatus() {
        this.status = name().toLowerCase(Locale.ROOT);
    }

    /** 小写状态英文名: rollback_record.status 列统一用它。 */
    public String getStatus() {
        return status;
    }

    /** 按持久化的小写状态名反查枚举(大小写不敏感); null/空白/不识别返回 null。 */
    public static RollbackStatus of(String v) {
        if (v == null || v.trim().isEmpty()) {
            return null;
        }
        for (RollbackStatus s : values()) {
            if (s.status.equalsIgnoreCase(v.trim())) {
                return s;
            }
        }
        return null;
    }
}
