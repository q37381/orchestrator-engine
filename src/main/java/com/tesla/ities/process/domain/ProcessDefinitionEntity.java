package com.tesla.ities.process.domain;

import java.time.LocalDateTime;

/**
 * process_definition 行(持久化实体, 不可变 blob)。一表多版本: (process_def_id, version) 唯一。
 * 注: 与 core.define.ProcessDefinition(YAML 反序列化的内存模型)靠 Entity 后缀区分(#1)。
 */
public class ProcessDefinitionEntity {
    private Long autoId;            // 物理主键
    private String processDefId;    // 业务键 varchar(20)
    private Integer version;
    private String name;
    private String definition;      // YAML/JSON 整棵树
    private String status;          // 发布态: 取值为 core.model.DefinitionStatus 的 name()(DRAFT|PUBLISHED|ARCHIVED)
    private LocalDateTime createdAt;
    private String createdBy;
    private String creatorId;       // 创建用户 varchar(20)
    private String updatorId;       // 最后更新用户 varchar(20)

    public Long getAutoId() {
        return autoId;
    }
    public void setAutoId(Long v) {
        this.autoId = v;
    }
    public String getProcessDefId() {
        return processDefId;
    }
    public void setProcessDefId(String v) {
        this.processDefId = v;
    }
    public Integer getVersion() {
        return version;
    }
    public void setVersion(Integer v) {
        this.version = v;
    }
    public String getName() {
        return name;
    }
    public void setName(String v) {
        this.name = v;
    }
    public String getDefinition() {
        return definition;
    }
    public void setDefinition(String v) {
        this.definition = v;
    }
    public String getStatus() {
        return status;
    }
    public void setStatus(String v) {
        this.status = v;
    }
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    public void setCreatedAt(LocalDateTime v) {
        this.createdAt = v;
    }
    public String getCreatedBy() {
        return createdBy;
    }
    public void setCreatedBy(String v) {
        this.createdBy = v;
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
}
