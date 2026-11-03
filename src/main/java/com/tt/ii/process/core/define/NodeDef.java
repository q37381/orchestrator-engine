package com.tt.ii.process.core.define;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点定义。nodes 下声明的都是叶子 ACTION(单 handler 或节点内多 action);
 * 控制节点(PARALLEL/IF)由 flow DSL 表达, 不在这里声明, 所以不再需要 type 字段(默认即 ACTION)。
 *
 * 变更: 去掉 output(结果由 handler 自行写 ctx); ref→handler(#11/#17);
 *      listeners 声明在节点=NodeListener(#4); laneExecution(分片 lane 串/并行, 默认 SERIAL, #6);
 *      actionExecution(容器内 action 序列串/并行, 默认 SERIAL);
 *      type/kind/isControl 等冗余判别全部移除(控制/叶子由实例行的 type 列区分);
 *      name = 展示名(YAML 可选), id 仍是 flow DSL 里的引用键——两者分开, 改名不动流程体。
 *      监听器链等定义可推导的数据不落实例表(经 NodeInstEntity.def() / NodeContext.name() 读);
 *      但 id/name 作为**展示用快照**物化期烙进 NodeInst 的 nodeDefId/name 列, 让进度/视图直接读行就能展示(不必回查定义)。
 *      segmentsFollowShard(v1.3): 分片叶子的节点级段"是否跟随分片"开关——仅显式 false 才改"整节点各一次"。
 *      setupRefs/setdownRefs(v1.4): **组级段 = 叶子子节点引用**(提升键, 与流程级 setup/setdown 同形);
 *      setup/setdown(内联动作)与它按声明形状二选一——组用引用、叶用内联动作。
 */
public class NodeDef {
    private String id;
    private String name;                       // 展示名(人可读); 未声明则回落 id
    private String handler;                    // 叶子(单 action): ActionHandler.name()
    private String shard;                      // ShardResolver.name(); 非空 = 分片节点
    private String laneExecution;              // SERIAL|PARALLEL, 分片 lane 执行方式(默认 SERIAL, #6)
    private String actionExecution;            // SERIAL|PARALLEL, 容器内 action 序列执行方式(默认 SERIAL)
    private String when;                       // 节点级 guard
    private String condition;                  // IF 条件(沿用列, 预留)
    private String errorPolicy;                // FAIL_FAST|CONTINUE_ON_ERROR; 未声明则回落流程级全局 errorPolicy, 都没声明才是 FAIL_FAST
    private Map<String, Object> config = new LinkedHashMap<>();
    private List<ActionDef> actions;           // 节点内多 action(主段)
    private List<ActionDef> setup;             // 前置段(节点回路: MAIN 动作之前); 与 actions 同构解析
    private List<ActionDef> setdown;           // 收尾段(回路内, finally 语义: 恒执行/段内不收敛); 与 actions 同构解析
    private List<String> setupRefs;            // 组级前置段(v1.4): 叶子子节点引用(提升键); 与 setup(内联动作)二选一
    private List<String> setdownRefs;          // 组级收尾段(v1.4): 同上
    private String repeatSegments;             // 段重复执行开关(true/false 归一化文本, v1.1; GROUP 级同字段); 未声明 = null(走成对重臂默认语义)
    private String segmentsFollowShard;        // 节点级段是否跟随分片(false = setup/setdown 整节点各一次, v1.3; 仅分片叶子); 未声明 = null(跟随分片)
    private List<ListenerDef> listeners;       // 节点级监听器(NodeListener)

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
    public String getShard() {
        return shard;
    }
    public void setShard(String v) {
        this.shard = v;
    }
    public String getLaneExecution() {
        return laneExecution;
    }
    public void setLaneExecution(String v) {
        this.laneExecution = v;
    }
    public String getActionExecution() {
        return actionExecution;
    }
    public void setActionExecution(String v) {
        this.actionExecution = v;
    }
    public String getWhen() {
        return when;
    }
    public void setWhen(String v) {
        this.when = v;
    }
    public String getCondition() {
        return condition;
    }
    public void setCondition(String v) {
        this.condition = v;
    }
    public String getErrorPolicy() {
        return errorPolicy;
    }
    public void setErrorPolicy(String v) {
        this.errorPolicy = v;
    }
    public Map<String, Object> getConfig() {
        return config;
    }
    public void setConfig(Map<String, Object> v) {
        this.config = v;
    }
    public List<ActionDef> getActions() {
        return actions;
    }
    public void setActions(List<ActionDef> v) {
        this.actions = v;
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
    public List<String> getSetupRefs() {
        return setupRefs;
    }
    public void setSetupRefs(List<String> v) {
        this.setupRefs = v;
    }
    public List<String> getSetdownRefs() {
        return setdownRefs;
    }
    public void setSetdownRefs(List<String> v) {
        this.setdownRefs = v;
    }
    public String getRepeatSegments() {
        return repeatSegments;
    }
    public void setRepeatSegments(String v) {
        this.repeatSegments = v;
    }
    public String getSegmentsFollowShard() {
        return segmentsFollowShard;
    }
    public void setSegmentsFollowShard(String v) {
        this.segmentsFollowShard = v;
    }
    public List<ListenerDef> getListeners() {
        return listeners;
    }
    public void setListeners(List<ListenerDef> v) {
        this.listeners = v;
    }

    /** 派生视图(非属性): 定义快照 JSON 往返时不能写出去, 否则回读无 setter 失配。同 ProcessDefinition.isEager。 */
    @JsonIgnore
    public boolean isSharded() {
        return shard != null && !shard.isEmpty();
    }
    @JsonIgnore
    public boolean isLaneParallel() {
        return "PARALLEL".equalsIgnoreCase(laneExecution);
    }
    @JsonIgnore
    public boolean isActionParallel() {
        return "PARALLEL".equalsIgnoreCase(actionExecution);
    }

    /** 段重复执行开关是否开启(v1.1 派生视图, 非属性): 声明值算出 true 才算开; 未声明按关闭算(拼错的装载期已拒)。 */
    @JsonIgnore
    public boolean repeatSegmentsEnabled() {
        return Boolean.TRUE.equals(ProcessDefinition.parseSwitch(repeatSegments));
    }

    /**
     * 节点级段是否跟随分片(v1.3 派生视图, 非属性): 未声明/true = 每 lane 一个完整三段回路(向来做口径);
     * 仅显式 false 才切"整节点各一次"——setup 一次 -> 各 lane 主体 -> setdown 一次(段行 lane 恒 null)。
     * 拼错的装载期已拒(与 repeatSegments 同口径); 派生视图名不带 is/get 前缀, 不会成为快照属性。
     */
    @JsonIgnore
    public boolean segmentsFollowShardEnabled() {
        return !Boolean.FALSE.equals(ProcessDefinition.parseSwitch(segmentsFollowShard));
    }

    /** 展示名: 声明了 name 用 name, 否则回落定义 id(两者都空时返回 null, 由调用方再回落实例键)。 */
    public String displayName() {
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return id != null && !id.isEmpty() ? id : null;
    }

    /** 生效的 action 列表: 声明了 actions 用列表; 否则由单 handler 合成一条(与物化/驱动 zip 对齐)。 */
    public List<ActionDef> effectiveActions() {
        if (actions != null && !actions.isEmpty()) {
            return actions;
        }
        ActionDef ad = new ActionDef();
        ad.setId(id);
        ad.setName(displayName());   // 单 handler 节点 action 与节点 1:1, 继承节点展示名(未声明即 id)
        ad.setHandler(handler);
        ad.setConfig(config);
        List<ActionDef> one = new ArrayList<>();
        one.add(ad);
        return one;
    }
}