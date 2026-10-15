package com.tesla.ities.process.core.model;

import java.util.Locale;

/**
 * 回退/补偿 + 向前重放步骤状态机(process_rollback_record.status)。两条各自独立的状态机共用一列:
 *  - 向后恢复(补偿): COMPENSATING -> COMPENSATED / COMPENSATE_FAILED / BLOCKED;
 *  - 向前重放(恢复现场): RESTORING -> RESTORED / RESTORE_FAILED。
 *
 * 某 action 当前处于哪一步, 只看它**最新一条** rollback_record 的状态(见 {@code findLatestByAction}):
 * 最新是 COMPENSATED(或重放中途的 RESTORING/RESTORE_FAILED)= 副作用还欠着恢复, 重试驱动前需再重放;
 * 最新是 RESTORED = 现场已重新施加, 不必再重放。补偿与重放交替留痕, 于是"回退->重试->再回退->再重试"可重复闭环。
 *
 * 原为 RollbackService 里的四个裸字符串常量, 提为枚举后与全仓同口径:
 * 落库/比较用 {@link #getStatus()} 小写, 反查用 {@link #of(String)}(大小写不敏感)。
 * 补偿/重放历史与运行态 {@link Status} 彻底隔离(原 action_inst 是不可变的执行事实, 不被回写)。
 */
public enum RollbackStatus {
    /** 已占位、补偿执行中 */
    COMPENSATING,
    /** 补偿成功(终态): 副作用已撤销, 欠一次向前重放 */
    COMPENSATED,
    /** 补偿自身失败(终态; 挂起告警, 不无限重试) */
    COMPENSATE_FAILED,
    /** 遇不可补偿步骤, 回退到此停止(终态) */
    BLOCKED,
    /** 重放执行中: 回退后再重试驱动前, 正把撤销过的副作用按执行顺序重新施加 */
    RESTORING,
    /** 重放成功(终态): 现场已恢复, 该 action 不再欠重放 */
    RESTORED,
    /** 重放自身失败(终态; 现场处于半恢复的坏状态, 挂起不向前驱动) */
    RESTORE_FAILED;

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
