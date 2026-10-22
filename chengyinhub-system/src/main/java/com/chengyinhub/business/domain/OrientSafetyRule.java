package com.chengyinhub.business.domain;

import java.io.Serializable;
import java.util.Date;

/** 城市定向主题级安全规则(orient_safety_rule)。 */
public class OrientSafetyRule implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long topicId;
    private Integer insuranceRequired;
    private Integer nightStartMinute;
    private Integer nightEndMinute;
    private Integer weatherBlock;
    private String weatherReason;
    private Date weatherBlockUntil;
    private Long updateBy;
    private Date createTime;
    private Date updateTime;

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Integer getInsuranceRequired() { return insuranceRequired; }
    public void setInsuranceRequired(Integer insuranceRequired) { this.insuranceRequired = insuranceRequired; }
    public Integer getNightStartMinute() { return nightStartMinute; }
    public void setNightStartMinute(Integer nightStartMinute) { this.nightStartMinute = nightStartMinute; }
    public Integer getNightEndMinute() { return nightEndMinute; }
    public void setNightEndMinute(Integer nightEndMinute) { this.nightEndMinute = nightEndMinute; }
    public Integer getWeatherBlock() { return weatherBlock; }
    public void setWeatherBlock(Integer weatherBlock) { this.weatherBlock = weatherBlock; }
    public String getWeatherReason() { return weatherReason; }
    public void setWeatherReason(String weatherReason) { this.weatherReason = weatherReason; }
    public Date getWeatherBlockUntil() { return weatherBlockUntil; }
    public void setWeatherBlockUntil(Date weatherBlockUntil) { this.weatherBlockUntil = weatherBlockUntil; }
    public Long getUpdateBy() { return updateBy; }
    public void setUpdateBy(Long updateBy) { this.updateBy = updateBy; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
