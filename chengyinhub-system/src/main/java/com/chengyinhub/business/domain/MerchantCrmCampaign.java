package com.chengyinhub.business.domain;

import java.util.Date;

/** 服务端重算保存分群后冻结的商家 CRM 触达任务。 */
public class MerchantCrmCampaign {
    public static final String READY = "READY";
    public static final String PROCESSING = "PROCESSING";
    public static final String SUCCESS = "SUCCESS";
    public static final String PARTIAL_FAILED = "PARTIAL_FAILED";
    public static final String NO_ELIGIBLE = "NO_ELIGIBLE";

    private Long id;
    private Long ownerMemberId;
    private Long merchantRowId;
    private String merchantNameSnapshot;
    private Long segmentId;
    private String segmentFilterJson;
    private String channel;
    private Long couponId;
    private String title;
    private String content;
    private Long actorMemberId;
    private String actorRoleCode;
    private String requestId;
    private String status;
    private Integer recipientCount;
    private Integer deliveredCount;
    private Integer noConsentCount;
    private Integer frequencySkippedCount;
    private Integer failedCount;
    private Integer retryableCount;
    private Date startedAt;
    private Date finishedAt;
    private String processingToken;
    private Date processingLeaseUntil;
    private Long version;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(Long value) { ownerMemberId = value; }
    public Long getMerchantRowId() { return merchantRowId; }
    public void setMerchantRowId(Long value) { merchantRowId = value; }
    public String getMerchantNameSnapshot() { return merchantNameSnapshot; }
    public void setMerchantNameSnapshot(String value) { merchantNameSnapshot = value; }
    public Long getSegmentId() { return segmentId; }
    public void setSegmentId(Long value) { segmentId = value; }
    public String getSegmentFilterJson() { return segmentFilterJson; }
    public void setSegmentFilterJson(String value) { segmentFilterJson = value; }
    public String getChannel() { return channel; }
    public void setChannel(String value) { channel = value; }
    public Long getCouponId() { return couponId; }
    public void setCouponId(Long value) { couponId = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public Long getActorMemberId() { return actorMemberId; }
    public void setActorMemberId(Long value) { actorMemberId = value; }
    public String getActorRoleCode() { return actorRoleCode; }
    public void setActorRoleCode(String value) { actorRoleCode = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Integer getRecipientCount() { return recipientCount; }
    public void setRecipientCount(Integer value) { recipientCount = value; }
    public Integer getDeliveredCount() { return deliveredCount; }
    public void setDeliveredCount(Integer value) { deliveredCount = value; }
    public Integer getNoConsentCount() { return noConsentCount; }
    public void setNoConsentCount(Integer value) { noConsentCount = value; }
    public Integer getFrequencySkippedCount() { return frequencySkippedCount; }
    public void setFrequencySkippedCount(Integer value) { frequencySkippedCount = value; }
    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer value) { failedCount = value; }
    public Integer getRetryableCount() { return retryableCount; }
    public void setRetryableCount(Integer value) { retryableCount = value; }
    public Date getStartedAt() { return startedAt; }
    public void setStartedAt(Date value) { startedAt = value; }
    public Date getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Date value) { finishedAt = value; }
    public String getProcessingToken() { return processingToken; }
    public void setProcessingToken(String value) { processingToken = value; }
    public Date getProcessingLeaseUntil() { return processingLeaseUntil; }
    public void setProcessingLeaseUntil(Date value) { processingLeaseUntil = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date value) { createTime = value; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date value) { updateTime = value; }
}
