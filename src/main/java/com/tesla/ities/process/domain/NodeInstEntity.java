package com.tesla.ities.process.domain;

import com.tesla.ities.process.core.define.DslParser;
import com.tesla.ities.process.core.define.ListenerDef;
import com.tesla.ities.process.core.define.NodeDef;

import java.time.LocalDateTime;
import java.util.List;

/**
 * node_inst 行(持久化实体): 存**运行期数据** + 两个展示用定义快照。顺序由 auto_id 承载, 驱动时按定义顺序与表达式按下标对齐。
 *
 * nodeDefId/name 是**展示用**列(定义快照, 物化期烙一次): 进度/视图直接读行就能拿到"哪个定义节点 + 展示名",
 * 不必再把自述键塞进 config, 也不必依赖 transient 的 {@link #def}(只有走查对齐后才在)。
 */
public class NodeInstEntity {
    private Long autoId;          // 物理主键, 承载声明顺序
    private String nodeInstId;    // 业务键 varchar(32)
    private String procInstId;
    private String nodeDefId;     // 定义引用键(flow DSL 引用键; 匿名 PARALLEL/IF 无定义为 NULL); 与 action_inst.action_def_id 同族
    private String name;          // 展示名(物化期取 NodeDef.displayName, 未声明 name 即 nodeDefId; 匿名控制节点 NULL, 视图回落 nodeInstId)
    private String type;          // PARALLEL|IF|ACTION(控制/叶子由 type 区分, kind 已移除)
    private String parentId;      // 父节点业务键(顶层根为 NULL)
    private String status;
    private String errorPolicy;
    private Boolean shardEnabled;
    private String shardResolver;
    private String conditionExpr;
    private String config;        // 业务配置(PLAN 解析冻结) + __chosen(IF 决策); 定义可推导数据不落库
    private String result;
    private String creatorId;       // 创建用户(= 流程创建人) varchar(20)
    private String updatorId;       // 最后更新用户
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 走查光标: 本行的定义表达式(transient, 不落库)。由 Materializer 对齐时(rootAt/childAt/ensureRoots)
     *  补充; 驱动/监听器链/action 定义一律自行内取, 不再作为参数外部传递。 */
    private DslParser.Expr expr;

    /** 本行的节点定义(transient, 不落库): 对齐时按 expr.nodeRef 从钉死版本的 ProcessDefinition 取出并挂载,
     *  驱动期直接取用, 不再每次回查定义。控制节点(PARALLEL/IF)只存在于 flow DSL, 无 NodeDef -> null。
     *  定义是缓存中的共享对象, 实例侧只读不可改写。 */
    private NodeDef def;

    /** 节点级监听器链(transient, 不落库): 组装/对齐节点时从钉死版本的 NodeDef 挂载,
     *  驱动时直接取用(NodeDriver 不再回查定义)。控制节点无声明为 null。 */
    private List<ListenerDef> listeners;

    public Long getAutoId() {
        return autoId;
    }
    public void setAutoId(Long v) {
        this.autoId = v;
    }
    public String getNodeInstId() {
        return nodeInstId;
    }
    public void setNodeInstId(String v) {
        this.nodeInstId = v;
    }
    public String getProcInstId() {
        return procInstId;
    }
    public void setProcInstId(String v) {
        this.procInstId = v;
    }
    public String getNodeDefId() {
        return nodeDefId;
    }
    public void setNodeDefId(String v) {
        this.nodeDefId = v;
    }
    public String getName() {
        return name;
    }
    public void setName(String v) {
        this.name = v;
    }
    public String getType() {
        return type;
    }
    public void setType(String v) {
        this.type = v;
    }
    public String getParentId() {
        return parentId;
    }
    public void setParentId(String v) {
        this.parentId = v;
    }
    public String getStatus() {
        return status;
    }
    public void setStatus(String v) {
        this.status = v;
    }
    public String getErrorPolicy() {
        return errorPolicy;
    }
    public void setErrorPolicy(String v) {
        this.errorPolicy = v;
    }
    public Boolean getShardEnabled() {
        return shardEnabled;
    }
    public void setShardEnabled(Boolean v) {
        this.shardEnabled = v;
    }
    public String getShardResolver() {
        return shardResolver;
    }
    public void setShardResolver(String v) {
        this.shardResolver = v;
    }
    public String getConditionExpr() {
        return conditionExpr;
    }
    public void setConditionExpr(String v) {
        this.conditionExpr = v;
    }
    public String getConfig() {
        return config;
    }
    public void setConfig(String v) {
        this.config = v;
    }
    public String getResult() {
        return result;
    }
    public void setResult(String v) {
        this.result = v;
    }
    public String getCreatorId() {
        return creatorId;
    }
    public void setCreatorId(String v) {
        this.creatorId = v;
    }
    public String getUpdatorId() {
        return updatorId;
    }
    public void setUpdatorId(String v) {
        this.updatorId = v;
    }
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(LocalDateTime v) {
        this.createdAt = v;
    }
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    public void setUpdatedAt(LocalDateTime v) {
        this.updatedAt = v;
    }
    public DslParser.Expr getExpr() {
        return expr;
    }
    public void setExpr(DslParser.Expr v) {
        this.expr = v;
    }
    public NodeDef getDef() {
        return def;
    }
    public void setDef(NodeDef v) {
        this.def = v;
    }
    public List<ListenerDef> getListeners() {
        return listeners;
    }
    public void setListeners(List<ListenerDef> v) {
        this.listeners = v;
    }
}
