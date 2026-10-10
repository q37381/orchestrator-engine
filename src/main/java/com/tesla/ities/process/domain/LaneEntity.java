package com.tesla.ities.process.domain;

import java.time.LocalDateTime;

/** lane 行(持久化实体): 分片节点的同 shardKey 共享边界 + 调度实体。 */
public class LaneEntity {
    private Long autoId;          // 物理主键
    private String laneId;        // 业务键 varchar(20)
    private String procInstId;
    private String nodeInstId;    // 所属分片节点业务键
    private String shardKey;
    private String status;
    private String errorPolicy;
    private String config;
    private String result;        // lane 级上下文(同 shardKey 共享)
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
    public String getLaneId() {
        return laneId;
    }
    public void setLaneId(String v) {
        this.laneId = v;
    }
    public String getProcInstId() {
        return procInstId;
    }
    public void setProcInstId(String v) {
        this.procInstId = v;
    }
    public String getNodeInstId() {
        return nodeInstId;
    }
    public void setNodeInstId(String v) {
        this.nodeInstId = v;
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
    public String getErrorPolicy() {
        return errorPolicy;
    }
    public void setErrorPolicy(String v) {
        this.errorPolicy = v;
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
