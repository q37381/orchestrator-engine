package com.tesla.ities.process.core.model;

import java.util.Locale;

/**
 * 流程定义发布态(process_definition.status)。
 *
 * 与运行态 {@link Status} 是两套语义, 但持久化口径统一(全仓枚举一致):
 *   - 常量名大写是 Java 标识, 落库/比较/SQL 字面量一律用小写 {@link #getStatus()};
 *   - 反查用 {@link #of(String)}(大小写不敏感), 禁止 DefinitionStatus.valueOf(对小写抛异常);
 *   - MyBatis 入参绑的是枚举本身, SQL 里经 #{status.status} 取小写规范值(不注册 TypeHandler)。
 *
 * 生命周期: draft(草稿可改) -> published(发布, 同 defId 可多版共存, 取 version 最大者)
 * -> archived(归档, 不再被"最新已发布"命中)。
 */
public enum DefinitionStatus {
    /** 草稿: 不被"取最新已发布"命中, 只能按 (defId, version) 精确定位 */
    DRAFT,
    /** 已发布: 缺省查询口径, version 传 null 时取它的最大版本 */
    PUBLISHED,
    /** 已归档: 下线, 不再参与最新版本认领 */
    ARCHIVED;

    private final String status;    // 小写状态英文名(持久化/比较的规范值)

    DefinitionStatus() {
        this.status = name().toLowerCase(Locale.ROOT);
    }

    /** 小写状态英文名: 落库/SQL 字面量统一用它(与 Status.getStatus() 同口径)。 */
    public String getStatus() {
        return status;
    }

    /** 按持久化的小写状态名反查枚举(大小写不敏感); null/空白/不识别返回 null。 */
    public static DefinitionStatus of(String v) {
        if (v == null || v.trim().isEmpty()) {
            return null;
        }
        for (DefinitionStatus s : values()) {
            if (s.status.equalsIgnoreCase(v.trim())) {
                return s;
            }
        }
        return null;
    }

    /** 实体里的裸字符串是否是合法发布态。 */
    public static boolean isValid(String v) {
        return of(v) != null;
    }
}
