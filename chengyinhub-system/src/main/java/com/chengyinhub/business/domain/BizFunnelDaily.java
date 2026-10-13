package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.math.BigDecimal;
import java.util.Date;

/**
 * P3 每日漏斗汇总对象 biz_funnel_daily。
 */
public class BizFunnelDaily extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Date statDate;
    private String funnelName;
    private String stepName;
    private Integer stepOrder;
    private String bizType;
    private String cityCode;
    private Long memberCount;
    private Long eventCount;
    private BigDecimal conversionRate;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Date getStatDate() { return statDate; }
    public void setStatDate(Date statDate) { this.statDate = statDate; }
    public String getFunnelName() { return funnelName; }
    public void setFunnelName(String funnelName) { this.funnelName = funnelName; }
    public String getStepName() { return stepName; }
    public void setStepName(String stepName) { this.stepName = stepName; }
    public Integer getStepOrder() { return stepOrder; }
    public void setStepOrder(Integer stepOrder) { this.stepOrder = stepOrder; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public String getCityCode() { return cityCode; }
    public void setCityCode(String cityCode) { this.cityCode = cityCode; }
    public Long getMemberCount() { return memberCount; }
    public void setMemberCount(Long memberCount) { this.memberCount = memberCount; }
    public Long getEventCount() { return eventCount; }
    public void setEventCount(Long eventCount) { this.eventCount = eventCount; }
    public BigDecimal getConversionRate() { return conversionRate; }
    public void setConversionRate(BigDecimal conversionRate) { this.conversionRate = conversionRate; }
}
