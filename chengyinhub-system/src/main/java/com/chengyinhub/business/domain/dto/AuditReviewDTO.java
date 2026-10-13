package com.chengyinhub.business.domain.dto;

/**
 * P3 审核处理请求。
 */
public class AuditReviewDTO {
    private Long taskId;
    private Integer expectedVersion;
    public Integer getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Integer expectedVersion) { this.expectedVersion = expectedVersion; }
    private Integer status;
    private String reasonCode;
    private String reasonText;
    private String requestId;
    /** 仅由已鉴权 controller 覆盖写入，不信任请求体。 */
    private String actorRoleCode;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getReasonText() { return reasonText; }
    public void setReasonText(String reasonText) { this.reasonText = reasonText; }
    /** NF-01 新契约名；保留 reasonText 兼容既有管理端。 */
    public void setReason(String reason) { this.reasonText = reason; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getActorRoleCode() { return actorRoleCode; }
    public void setActorRoleCode(String actorRoleCode) { this.actorRoleCode = actorRoleCode; }
}
