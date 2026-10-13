package com.chengyinhub.business.domain;

import java.util.Date;

/** REQUIRED_FULFILLMENT 发布检查使用的结构化责任覆盖需求。 */
public class OfficialEventFulfillmentRequirement {
    private Long id;
    private Long eventId;
    private Long missionId;
    /** 制作台保存时使用的 stable mission_code；持久化仍以 missionId 为准。 */
    private String missionCode;
    private String targetType;
    private Long targetId;
    private Date windowStart;
    private Date windowEnd;
    private String requiredRole;
    private Integer requiredCapacity;
    private Integer critical;
    private String status;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public Long getMissionId() { return missionId; }
    public void setMissionId(Long missionId) { this.missionId = missionId; }
    public String getMissionCode() { return missionCode; }
    public void setMissionCode(String missionCode) { this.missionCode = missionCode; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public Date getWindowStart() { return windowStart; }
    public void setWindowStart(Date windowStart) { this.windowStart = windowStart; }
    public Date getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Date windowEnd) { this.windowEnd = windowEnd; }
    public String getRequiredRole() { return requiredRole; }
    public void setRequiredRole(String requiredRole) { this.requiredRole = requiredRole; }
    public Integer getRequiredCapacity() { return requiredCapacity; }
    public void setRequiredCapacity(Integer requiredCapacity) { this.requiredCapacity = requiredCapacity; }
    public Integer getCritical() { return critical; }
    public void setCritical(Integer critical) { this.critical = critical; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
