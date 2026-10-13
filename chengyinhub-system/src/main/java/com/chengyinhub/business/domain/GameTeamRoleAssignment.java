package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/** 单局剧情角色真源；仅一个分配可活跃，SUPERSEDED 行保留审计历史。 */
public class GameTeamRoleAssignment extends BaseEntity {
    private static final long serialVersionUID = 1L;
    private Long id;
    private Long gameSessionId;
    private Long teamId;
    private Long memberId;
    private String roleCode;
    private String roleName;
    private String status;
    private Long assignedBy;
    private Long supersededBy;
    private Date supersededAt;
    private Integer revision;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getGameSessionId() { return gameSessionId; }
    public void setGameSessionId(Long gameSessionId) { this.gameSessionId = gameSessionId; }
    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getAssignedBy() { return assignedBy; }
    public void setAssignedBy(Long assignedBy) { this.assignedBy = assignedBy; }
    public Long getSupersededBy() { return supersededBy; }
    public void setSupersededBy(Long supersededBy) { this.supersededBy = supersededBy; }
    public Date getSupersededAt() { return supersededAt; }
    public void setSupersededAt(Date supersededAt) { this.supersededAt = supersededAt; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer revision) { this.revision = revision; }
}
