package com.chengyinhub.business.domain.dto;

/**
 * P3 成长任务完成请求。
 */
public class GrowthMissionCompleteDTO {
    private String missionCode;
    private String bizType;
    private Long bizId;

    public String getMissionCode() { return missionCode; }
    public void setMissionCode(String missionCode) { this.missionCode = missionCode; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
}
