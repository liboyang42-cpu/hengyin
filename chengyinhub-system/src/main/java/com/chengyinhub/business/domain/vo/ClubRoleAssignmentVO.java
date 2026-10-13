package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.ClubRoleAssignment;

import java.util.Date;

/** 小程序角色管理白名单投影，不下发审计操作者、撤销原因等内部字段。 */
public final class ClubRoleAssignmentVO {
    private final Long id;
    private final Long targetMemberId;
    private final String roleCode;
    private final String scopeType;
    private final Long scopeId;
    private final Date expiresAt;
    private final Date assignedAt;
    private final Integer version;

    private ClubRoleAssignmentVO(ClubRoleAssignment row) {
        this.id = row.getId();
        this.targetMemberId = row.getTargetMemberId();
        this.roleCode = row.getRoleCode();
        this.scopeType = row.getScopeType();
        this.scopeId = row.getScopeId();
        this.expiresAt = row.getExpiresAt();
        this.assignedAt = row.getAssignedAt();
        this.version = row.getVersion();
    }

    public static ClubRoleAssignmentVO from(ClubRoleAssignment row) {
        return row == null ? null : new ClubRoleAssignmentVO(row);
    }

    public Long getId() { return id; }
    public Long getTargetMemberId() { return targetMemberId; }
    public String getRoleCode() { return roleCode; }
    public String getScopeType() { return scopeType; }
    public Long getScopeId() { return scopeId; }
    public Date getExpiresAt() { return expiresAt; }
    public Date getAssignedAt() { return assignedAt; }
    public Integer getVersion() { return version; }
}
