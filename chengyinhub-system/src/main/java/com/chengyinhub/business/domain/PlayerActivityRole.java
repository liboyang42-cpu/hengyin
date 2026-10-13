package com.chengyinhub.business.domain;

import java.io.Serializable;
import java.util.Date;

/**
 * 俱乐部活动自动分配角色(升级2)。一人一活动一角色,唯一键 (activity_id, member_id)。
 */
public class PlayerActivityRole implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long activityId;
    private Long memberId;
    /** NAVIGATOR/OBSERVER/RECORDER/NEGOTIATOR/DECODER */
    private String roleCode;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
