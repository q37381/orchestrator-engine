package com.tesla.ities.process.core.model;

import java.util.Locale;

/**
 * 节点类型。顶层逗号扁平化后不再有 SERIAL 虚拟类型(顶层顺序由根节点行的 auto_id 承载);
 * WHILE 为预留(需 visit_count + 迭代物化)。
 * GROUP = 聚合节点(命名串行容器, 由 nodes.&lt;key&gt;.group 声明并在装载期展开进 flow);
 * 与 PARALLEL/IF 同为控制容器, 但它在 nodes 下有声明, 所以行上带 NodeDef(展示名/自己的 errorPolicy/组级 guard)。
 *
 * 持久化口径与 Status 一致: process_node_inst.type 落小写 {@link #getStatus()},
 * 反查用 {@link #of(String)}(大小写不敏感, 兼容存量大写行), 不用 valueOf。
 */
public enum NodeType {
    PARALLEL,
    IF,
    WHILE,
    GROUP,
    ACTION;

    private final String status;    // 小写类型英文名(落库/SQL 的规范值)

    NodeType() {
        this.status = name().toLowerCase(Locale.ROOT);
    }

    /** 小写类型英文名: 实例行 type 列统一用它。 */
    public String getStatus() {
        return status;
    }

    /** 按落库字符串反查枚举(大小写不敏感); null/空白/不识别返回 null。 */
    public static NodeType of(String v) {
        if (v == null || v.trim().isEmpty()) {
            return null;
        }
        for (NodeType t : values()) {
            if (t.status.equalsIgnoreCase(v.trim())) {
                return t;
            }
        }
        return null;
    }
}
