package com.chengyinhub.business.domain.vo;

import java.util.Date;

/** 广播结果:逐类人数与原因计数,真实投递结果只来自服务端。 */
public class MerchantCrmBroadcastVO {
    private Long id;
    private String status;
    private Integer audienceCount;
    private Integer consentedCount;
    private Integer noConsentCount;
    private Integer frequencySkippedCount;
    private Integer deliveredCount;
    private Integer failedCount;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Integer getAudienceCount() { return audienceCount; }
    public void setAudienceCount(Integer value) { audienceCount = value; }
    public Integer getConsentedCount() { return consentedCount; }
    public void setConsentedCount(Integer value) { consentedCount = value; }
    public Integer getNoConsentCount() { return noConsentCount; }
    public void setNoConsentCount(Integer value) { noConsentCount = value; }
    public Integer getFrequencySkippedCount() { return frequencySkippedCount; }
    public void setFrequencySkippedCount(Integer value) { frequencySkippedCount = value; }
    public Integer getDeliveredCount() { return deliveredCount; }
    public void setDeliveredCount(Integer value) { deliveredCount = value; }
    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer value) { failedCount = value; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date value) { createTime = value; }
}
