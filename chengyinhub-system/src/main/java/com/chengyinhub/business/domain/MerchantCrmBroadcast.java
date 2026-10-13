package com.chengyinhub.business.domain;

import java.util.Date;

/** 商家定向广播任务(客户页「当前筛选」一次性通知)。 */
public class MerchantCrmBroadcast {
    public static final String SENDING = "SENDING";
    public static final String SUCCESS = "SUCCESS";
    public static final String PARTIAL_FAILED = "PARTIAL_FAILED";
    public static final String FAILED = "FAILED";

    private Long id;
    private Long merchantId;
    private Long merchantOwnerMemberId;
    private String merchantNameSnapshot;
    private Long actorMemberId;
    private String actorRoleCode;
    private String requestId;
    private Date broadcastDate;
    private Integer dailySlot;
    private String filterJson;
    private String content;
    private String status;
    private Integer audienceCount;
    private Integer consentedCount;
    private Integer noConsentCount;
    private Integer frequencySkippedCount;
    private Integer deliveredCount;
    private Integer failedCount;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    /** mms_merchant.id;门店级归属主键。 */
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long value) { merchantId = value; }
    /** mms_merchant.member_id;来源查询与审计快照。 */
    public Long getMerchantOwnerMemberId() { return merchantOwnerMemberId; }
    public void setMerchantOwnerMemberId(Long value) { merchantOwnerMemberId = value; }
    public String getMerchantNameSnapshot() { return merchantNameSnapshot; }
    public void setMerchantNameSnapshot(String value) { merchantNameSnapshot = value; }
    public Long getActorMemberId() { return actorMemberId; }
    public void setActorMemberId(Long value) { actorMemberId = value; }
    public String getActorRoleCode() { return actorRoleCode; }
    public void setActorRoleCode(String value) { actorRoleCode = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public Date getBroadcastDate() { return broadcastDate; }
    public void setBroadcastDate(Date value) { broadcastDate = value; }
    public Integer getDailySlot() { return dailySlot; }
    public void setDailySlot(Integer value) { dailySlot = value; }
    public String getFilterJson() { return filterJson; }
    public void setFilterJson(String value) { filterJson = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
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
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date value) { updateTime = value; }
}
