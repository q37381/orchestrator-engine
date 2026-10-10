package com.tesla.ities.process.domain;

import java.time.LocalDateTime;

/** process_inst 行(持久化实体)。config/vars/result 以 JSON 文本存储, 应用层转换(params 桶不落库)。 */
public class ProcessInstEntity {
    private Long autoId;            // 物理主键, 应用不直接使用
    private String procInstId;      // 业务键 varchar(20)
    private String processDefId;
    private Integer defVersion;
    private String status;
    private String config;          // 创建输入(流程级配置), 与其他层同名同语义
    private String vars;            // 运行期变量空间(含 dialect 选择/运行期写入)
    private String result;
    private String creatorId;       // 创建用户(创建流程时传入的 userId) varchar(20)
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
    public String getProcessDefId() {
        return processDefId;
    }
    public void setProcessDefId(String v) {
        this.processDefId = v;
    }
    public Integer getDefVersion() {
        return defVersion;
    }
    public void setDefVersion(Integer v) {
        this.defVersion = v;
    }
    public String getStatus() {
        return status;
    }
    public void setStatus(String v) {
        this.status = v;
    }
    public String getConfig() {
        return config;
    }
    public void setConfig(String v) {
        this.config = v;
    }
    public String getVars() {
        return vars;
    }
    public void setVars(String v) {
        this.vars = v;
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
