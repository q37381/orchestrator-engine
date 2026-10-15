package com.tesla.ities.process.domain;

import java.time.LocalDateTime;

/**
 * rollback_record 行(持久化实体): 回退/补偿 + 向前重放的独立可追溯历史(#16)。
 * 关联原 action_inst(不改其状态), 每个补偿/重放步骤一行。detail 以 JSON 文本存补充信息。
 */
public class RollbackRecordEntity {
    private Long autoId;            // 物理主键
    private String rollbackId;      // 业务键 varchar(32)
    private String procInstId;      // 所属流程业务键
    private String actionInstId;    // 关联的原 action 业务键
    private String actionDefId;
    private String status;          // RollbackStatus.getStatus() 小写: compensating|compensated|compensate_failed|blocked|restoring|restored|restore_failed
    private String operator;
    private String detail;          // JSON 文本: reason/异常信息等
    private String creatorId;       // 创建用户(发起回退的操作人) varchar(20)
    private String updatorId;       // 最后更新用户
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getAutoId() {
        return autoId;
    }
    public void setAutoId(Long v) {
        this.autoId = v;
    }
    public String getRollbackId() {
        return rollbackId;
    }
    public void setRollbackId(String v) {
        this.rollbackId = v;
    }
    public String getProcInstId() {
        return procInstId;
    }
    public void setProcInstId(String v) {
        this.procInstId = v;
    }
    public String getActionInstId() {
        return actionInstId;
    }
    public void setActionInstId(String v) {
        this.actionInstId = v;
    }
    public String getActionDefId() {
        return actionDefId;
    }
    public void setActionDefId(String v) {
        this.actionDefId = v;
    }
    public String getStatus() {
        return status;
    }
    public void setStatus(String v) {
        this.status = v;
    }
    public String getOperator() {
        return operator;
    }
    public void setOperator(String v) {
        this.operator = v;
    }
    public String getDetail() {
        return detail;
    }
    public void setDetail(String v) {
        this.detail = v;
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
