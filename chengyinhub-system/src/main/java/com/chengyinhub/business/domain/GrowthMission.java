package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * P3 成长任务 growth_mission。
 */
public class GrowthMission extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String missionCode;
    private String missionName;
    private String missionDesc;
    private String triggerEvent;
    private Integer expReward;
    private Integer dailyLimit;
    private String status;
    private Integer sortNo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMissionCode() { return missionCode; }
    public void setMissionCode(String missionCode) { this.missionCode = missionCode; }
    public String getMissionName() { return missionName; }
    public void setMissionName(String missionName) { this.missionName = missionName; }
    public String getMissionDesc() { return missionDesc; }
    public void setMissionDesc(String missionDesc) { this.missionDesc = missionDesc; }
    public String getTriggerEvent() { return triggerEvent; }
    public void setTriggerEvent(String triggerEvent) { this.triggerEvent = triggerEvent; }
    public Integer getExpReward() { return expReward; }
    public void setExpReward(Integer expReward) { this.expReward = expReward; }
    public Integer getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(Integer dailyLimit) { this.dailyLimit = dailyLimit; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
}
