package com.tesla.ities.process.core.model;

import java.util.Locale;

/**
 * 上下文/事件作用域层级。
 * 监听器 scope 由声明位置决定: 声明在流程上=PROCESS, 声明在节点上=NODE。
 *
 * 与全仓枚举同口径: 常量名大写是 Java 标识, 需要字符串形态(日志/将来的声明值)用小写 {@link #getStatus()},
 * 反查用 {@link #of(String)}(大小写不敏感)。
 */
public enum Scope {
    PROCESS,
    NODE,
    LANE,
    ACTION;

    private final String status;    // 小写作用域名(字符串形态的规范值)

    Scope() {
        this.status = name().toLowerCase(Locale.ROOT);
    }

    /** 小写作用域名。 */
    public String getStatus() {
        return status;
    }

    /** 按字符串反查枚举(大小写不敏感); null/空白/不识别返回 null。 */
    public static Scope of(String v) {
        if (v == null || v.trim().isEmpty()) {
            return null;
        }
        for (Scope s : values()) {
            if (s.status.equalsIgnoreCase(v.trim())) {
                return s;
            }
        }
        return null;
    }
}
