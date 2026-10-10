package com.tesla.ities.process.domain;

import java.time.LocalDateTime;

/** action_inst 行(持久化实体): 顺序由物理 auto_id 升序承载。失败/跳过信息写入 result(__error/__skipReason)。
 *  name 是展示用列(物化期取 ActionDef.displayName, 未声明即 actionDefId), 与 node_inst.nodeDefId/name 同族, 视图直接读行展示。 */
public class ActionInstEntity {
    private Long autoId;          // 物理主键, 单调递增 = 声明顺序
    private String actionInstId;  // 业务键 varchar(20)
    private String nodeInstId;
    private String laneId;        // 非分片为 null
    private String actionDefId;   // 定义引用键(= ActionDef.id, 未声明 id 则 handler)
    private String name;          // 展示名(物化期取 ActionDef.displayName, 未声明即 actionDefId)
    private String handlerName;   // ActionHandler.name()
    private String shardKey;
    private String status;
    private String whenExpr;      // 声明式 guard
    private Integer visitCount;
    private String config;
    private String result;        // 含业务结果 + 保留键 __error/__skipReason
    private String creatorId;     // 创建用户(= 流程创建人) varchar(20)
    private String updatorId;     // 最后更新用户
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getAutoId() {
        return autoId;
    }
    public void setAutoId(Long v) {
        this.autoId = v;
    }
    public String getActionInstId() {
        return actionInstId;
    }
    public void setActionInstId(String v) {
        this.actionInstId = v;
    }
    public String getNodeInstId() {
        return nodeInstId;
    }
    public void setNodeInstId(String v) {
        this.nodeInstId = v;
    }
    public String getLaneId() {
        return laneId;
    }
    public void setLaneId(String v) {
        this.laneId = v;
    }
    public String getActionDefId() {
        return actionDefId;
    }
    public void setActionDefId(String v) {
        this.actionDefId = v;
    }
    public String getName() {
        return name;
    }
    public void setName(String v) {
        this.name = v;
    }
    public String getHandlerName() {
        return handlerName;
    }
    public void setHandlerName(String v) {
        this.handlerName = v;
    }
    public String getShardKey() {
        return shardKey;
    }
    public void setShardKey(String v) {
        this.shardKey = v;
    }
    public String getStatus() {
        return status;
    }
    public void setStatus(String v) {
        this.status = v;
    }
    public String getWhenExpr() {
        return whenExpr;
    }
    public void setWhenExpr(String v) {
        this.whenExpr = v;
    }
    public Integer getVisitCount() {
        return visitCount;
    }
    public void setVisitCount(Integer v) {
        this.visitCount = v;
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
}
