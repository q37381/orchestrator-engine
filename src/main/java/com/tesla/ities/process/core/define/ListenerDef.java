package com.tesla.ities.process.core.define;

import java.util.Map;

/**
 * 监听器声明(YAML 里的一条)。scope 不在此表达 —— 由**声明位置 + 接口类型**决定:
 * 声明在流程级=ProcessListener, 节点级=NodeListener, action 级=ActionListener(#4)。
 *
 * 链的来源: 定义版本钉死(process_inst.def_version), 定义不可变 -> 推导结果恒定。驱动时直接把
 * {@code List<ListenerDef>} 交给 ListenerEngine 按声明顺序 inline 执行, 不再转成 Map 快照。
 *
 * 执行顺序 = 声明顺序(不再有 order 排序字段)。
 */
public class ListenerDef {
    private String name;                    // 对应监听器 name()
    private Map<String, Object> config;

    public String getName() {
        return name;
    }
    public void setName(String v) {
        this.name = v;
    }
    public Map<String, Object> getConfig() {
        return config;
    }
    public void setConfig(Map<String, Object> v) {
        this.config = v;
    }
}
