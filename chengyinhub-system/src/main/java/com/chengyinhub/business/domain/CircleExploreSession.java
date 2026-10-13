package com.chengyinhub.business.domain;

import java.io.Serializable;
import java.util.Date;

/** 圈层主题玩家会话。 */
public class CircleExploreSession implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long id;
    private Long topicId;
    private Long ownerMemberId;
    private String inviteCode;
    private String status;
    private Date createTime;
    private Date completedAt;

    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { this.topicId = value; }
    public Long getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(Long value) { this.ownerMemberId = value; }
    public String getInviteCode() { return inviteCode; }
    public void setInviteCode(String value) { this.inviteCode = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date value) { this.createTime = value; }
    public Date getCompletedAt() { return completedAt; }
    public void setCompletedAt(Date value) { this.completedAt = value; }
}
