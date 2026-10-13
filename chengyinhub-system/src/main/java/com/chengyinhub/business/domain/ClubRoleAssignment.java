package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/** 俱乐部委派角色。Owner/Member 由既有主体关系派生，不写入本表。 */
public class ClubRoleAssignment extends BaseEntity {
    public static final String SCOPE_CLUB = "CLUB";
    public static final String SCOPE_EVENT = "EVENT";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    private Long id;
    private Long clubId;
    private Long targetMemberId;
    private String roleCode;
    private String scopeType;
    private Long scopeId;
    private String status;
    private Date expiresAt;
    private Long assignedByMemberId;
    private Date assignedAt;
    private Long revokedByMemberId;
    private Date revokedAt;
    private String revokeReason;
    private Integer version;
    /** 查询活动作用域权限时由 cms_activity.topic_id 回填，不持久化到委派表。 */
    private Long scopeTopicId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getTargetMemberId() { return targetMemberId; }
    public void setTargetMemberId(Long targetMemberId) { this.targetMemberId = targetMemberId; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long scopeId) { this.scopeId = scopeId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }
    public Long getAssignedByMemberId() { return assignedByMemberId; }
    public void setAssignedByMemberId(Long assignedByMemberId) { this.assignedByMemberId = assignedByMemberId; }
    public Date getAssignedAt() { return assignedAt; }
    public void setAssignedAt(Date assignedAt) { this.assignedAt = assignedAt; }
    public Long getRevokedByMemberId() { return revokedByMemberId; }
    public void setRevokedByMemberId(Long revokedByMemberId) { this.revokedByMemberId = revokedByMemberId; }
    public Date getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Date revokedAt) { this.revokedAt = revokedAt; }
    public String getRevokeReason() { return revokeReason; }
    public void setRevokeReason(String revokeReason) { this.revokeReason = revokeReason; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Long getScopeTopicId() { return scopeTopicId; }
    public void setScopeTopicId(Long scopeTopicId) { this.scopeTopicId = scopeTopicId; }
}
