package com.tesla.ities.process.core.define;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点内单个 action 定义。
 * handler = ActionHandler 的 name()(#11/#17); 无 output(结果由 handler 自行写 ctx)。
 * listeners = action 级监听器(ActionListener, #4)。
 * name = 展示名(YAML 可选), id 仍是节点内引用键——与 NodeDef 同口径, 未声明 name 时 displayName 回落 id。
 * setup/setdown = **action 级段**(v1.2): 仅主段动作可携带, 同挂两段才成对——级 setup 段在主体 action 之前跑、
 *   级 setdown 段(finally)之后必达; 段动作不再内联(深度锁 1, 装载期拒)。
 */
public class ActionDef {
    private String id;                          // 节点内唯一
    private String name;                         // 展示名(人可读); 未声明则回落 id
    private String handler;                     // ActionHandler.name()
    private String when;                        // 声明式 guard
    private Map<String, Object> config = new LinkedHashMap<>();
    private List<ListenerDef> listeners;         // action 级(ACTION scope)
    private List<ActionDef> setup;               // action 级 setup 段(v1.2, 与 actions 同构解析)
    private List<ActionDef> setdown;             // action 级 setdown 段(finally 语义)

    public String getId() {
        return id;
    }
    public void setId(String v) {
        this.id = v;
    }
    public String getName() {
        return name;
    }
    public void setName(String v) {
        this.name = v;
    }
    public String getHandler() {
        return handler;
    }
    public void setHandler(String v) {
        this.handler = v;
    }
    public String getWhen() {
        return when;
    }
    public void setWhen(String v) {
        this.when = v;
    }
    public Map<String, Object> getConfig() {
        return config;
    }
    public void setConfig(Map<String, Object> v) {
        this.config = v;
    }
    public List<ListenerDef> getListeners() {
        return listeners;
    }
    public void setListeners(List<ListenerDef> v) {
        this.listeners = v;
    }
    public List<ActionDef> getSetup() {
        return setup;
    }
    public void setSetup(List<ActionDef> v) {
        this.setup = v;
    }
    public List<ActionDef> getSetdown() {
        return setdown;
    }
    public void setSetdown(List<ActionDef> v) {
        this.setdown = v;
    }

    /** 展示名: 声明了 name 用 name, 否则回落 action id(与 NodeDef.displayName 同口径; 都空返回 null, 由调用方再回落)。 */
    public String displayName() {
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return id != null && !id.isEmpty() ? id : null;
    }
}
