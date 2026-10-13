package com.chengyinhub.business.domain.dto;

/** NF-17 审批撤销命令；expectedVersion 是 CAS 前提，idempotencyKey 是稳定重放键。 */
public class AuditRevertDTO {
    private Long taskId;
    private Integer expectedVersion;
    private String idempotencyKey;
    private String reason;
    /** 仅由已鉴权 controller 覆盖写入，不信任请求体。 */
    private String actorRoleCode;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Integer getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Integer expectedVersion) { this.expectedVersion = expectedVersion; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getActorRoleCode() { return actorRoleCode; }
    public void setActorRoleCode(String actorRoleCode) { this.actorRoleCode = actorRoleCode; }
}
