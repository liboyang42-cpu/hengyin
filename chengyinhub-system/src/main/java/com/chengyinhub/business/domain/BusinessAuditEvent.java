package com.chengyinhub.business.domain;

import java.util.Date;

/** 小程序高风险动作的不可变业务审计；只允许追加与读取，不提供更新/删除。 */
public class BusinessAuditEvent {
    public static final String ACTOR_TYPE_MEMBER = "MEMBER";
    public static final String ACTOR_TYPE_SYS_USER = "SYS_USER";
    public static final String ACTOR_TYPE_UNKNOWN = "UNKNOWN";

    private Long id;
    private String domainType;
    private Long domainId;
    private Long ownerMemberId;
    private String actorType;
    private Long actorMemberId;
    private String actorRoleCode;
    private String action;
    private String targetType;
    private Long targetId;
    private Long relationId;
    private String beforeJson;
    private String afterJson;
    private String reason;
    private String requestId;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDomainType() { return domainType; }
    public void setDomainType(String domainType) { this.domainType = domainType; }
    public Long getDomainId() { return domainId; }
    public void setDomainId(Long domainId) { this.domainId = domainId; }
    public Long getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(Long ownerMemberId) { this.ownerMemberId = ownerMemberId; }
    public String getActorType() { return actorType; }
    public void setActorType(String actorType) { this.actorType = actorType; }
    public Long getActorMemberId() { return actorMemberId; }
    public void setActorMemberId(Long actorMemberId) { this.actorMemberId = actorMemberId; }
    public String getActorRoleCode() { return actorRoleCode; }
    public void setActorRoleCode(String actorRoleCode) { this.actorRoleCode = actorRoleCode; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public Long getRelationId() { return relationId; }
    public void setRelationId(Long relationId) { this.relationId = relationId; }
    public String getBeforeJson() { return beforeJson; }
    public void setBeforeJson(String beforeJson) { this.beforeJson = beforeJson; }
    public String getAfterJson() { return afterJson; }
    public void setAfterJson(String afterJson) { this.afterJson = afterJson; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    /** NF-15 查询契约别名；底表沿用既有 business_audit_event，避免新建第二套审计真源。 */
    public Long getActorId() { return actorMemberId; }
    public String getScopeType() { return domainType; }
    public Long getScopeId() { return domainId; }
    public Date getServerTime() { return createTime; }
}
