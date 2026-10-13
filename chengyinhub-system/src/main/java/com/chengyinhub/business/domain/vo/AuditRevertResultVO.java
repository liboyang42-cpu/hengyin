package com.chengyinhub.business.domain.vo;

public class AuditRevertResultVO {
    private Long taskId;
    private Integer status;
    private Integer version;
    private Long revertedRecordId;
    private String requestId;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Long getRevertedRecordId() { return revertedRecordId; }
    public void setRevertedRecordId(Long revertedRecordId) { this.revertedRecordId = revertedRecordId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
