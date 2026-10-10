package com.tesla.ities.process.core.model;

import java.util.Locale;

/**
 * 错误策略。FAIL_FAST 是默认策略(不配置即生效), 采用 drain 语义: 不启动新的, 在跑的让它跑完。
 *
 * 持久化口径与 Status 一致: 实例行的 error_policy 列落小写 {@link #getStatus()};
 * YAML 声明值大小写不敏感(解析走 of/parseOrNull, 不依赖常量名原文)。
 */
public enum ErrorPolicy {
    FAIL_FAST,
    CONTINUE_ON_ERROR;

    /** 默认策略的单一真相源(schema DEFAULT / 物化回落都引用它, 不各处写字面量)。 */
    public static final ErrorPolicy DEFAULT = FAIL_FAST;

    private final String status;    // 小写策略英文名(落库/SQL 的规范值)

    ErrorPolicy() {
        this.status = name().toLowerCase(Locale.ROOT);
    }

    /** 小写策略英文名: 实例行 error_policy 列统一用它(默认值 DEFAULT 即它的值)。 */
    public String getStatus() {
        return status;
    }

    /** 解析声明值; 未配置/空/非法一律回落默认 {@link #DEFAULT}。 */
    public static ErrorPolicy of(String v) {
        ErrorPolicy p = parseOrNull(v);
        return p == null ? DEFAULT : p;
    }

    /**
     * 只认声明值本身: null/空白/非法一律返回 null(= "没声明"), 让调用方自己决定回落给谁。
     * 分层声明(节点 > 全局 > 默认)靠它区分"未声明"与"声明了但不合法"。
     */
    public static ErrorPolicy parseOrNull(String v) {
        if (v == null || v.trim().isEmpty()) {
            return null;
        }
        for (ErrorPolicy p : values()) {
            if (p.status.equalsIgnoreCase(v.trim())) {
                return p;
            }
        }
        return null;
    }
}
