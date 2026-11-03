package com.tt.ii.process.core.model;

/**
 * action 执行段(节点/GROUP 回路的生命周期三段 + v1.2 action 级段两段):
 *  - SETUP:         主体之前的前置段(段坏则跳过主体, 收尾段照跑; 叶子主体 = MAIN 动作序列, GROUP 主体 = 子节点序列);
 *  - MAIN:          主段(声明 actions / 单 handler 合成的动作序列; GROUP 回路不落本段——组没有 MAIN 动作);
 *  - SETDOWN:       收尾段(finally 语义: 恒执行、段内不收敛);
 *  - ACTION_SETUP:   action 级 setup 段(v1.2, 段动作专用): 主体 action 之前; 独立段值使存量回路查询天然看不到段行;
 *  - ACTION_SETDOWN: action 级 setdown 段(finally: 主体 action 之后必达)。
 *
 * 持久化用 {@link #getStatus()} 小写规范值(action_inst.segment);
 * 存量行/未设置段一律按 {@link #MAIN} 计(兼容口径, 见 ActionInstEntity.segment 注释)。
 */
public enum ActionSegment {
    SETUP("setup"),
    MAIN("main"),
    SETDOWN("setdown"),
    ACTION_SETUP("action-setup"),
    ACTION_SETDOWN("action-setdown");

    private final String status;    // 小写声明值(与 Status/NodeType 统一口径)

    ActionSegment(String status) {
        this.status = status;
    }

    /** 小写规范值。 */
    public String getStatus() {
        return status;
    }

    /** 由持久化的小写值反查枚举(大小写不敏感); null/不识别返回 null。 */
    public static ActionSegment of(String status) {
        if (status == null) {
            return null;
        }
        for (ActionSegment s : values()) {
            if (s.status.equalsIgnoreCase(status)) {
                return s;
            }
        }
        return null;
    }
}
