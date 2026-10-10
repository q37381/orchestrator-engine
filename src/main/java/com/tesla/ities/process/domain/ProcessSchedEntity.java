package com.tesla.ities.process.domain;

import java.time.LocalDateTime;

/**
 * process_sched 行(调度路由实体)。
 *
 * 只有本表有行的流程才参与"主动调度": 创建时给了 (schedUnit, schedZone) 才落这一行。
 * 认领闸门/状态仍在 process_inst, 本表只承载路由维度(服务单元 + 网络区域), 供调度器按能力 JOIN 过滤。
 */
public class ProcessSchedEntity {
    private Long autoId;            // 物理主键, 应用不直接使用
    private String procInstId;      // 流程业务键, 一对一关联 process_inst
    private String schedUnit;       // 服务单元(路由过滤维度)
    private String schedZone;       // 网络区域(路由过滤维度)
    private String creatorId;       // 创建用户
    private String updatorId;       // 最后更新用户
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getAutoId() {
        return autoId;
    }
    public void setAutoId(Long v) {
        this.autoId = v;
    }
    public String getProcInstId() {
        return procInstId;
    }
    public void setProcInstId(String v) {
        this.procInstId = v;
    }
    public String getSchedUnit() {
        return schedUnit;
    }
    public void setSchedUnit(String v) {
        this.schedUnit = v;
    }
    public String getSchedZone() {
        return schedZone;
    }
    public void setSchedZone(String v) {
        this.schedZone = v;
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
