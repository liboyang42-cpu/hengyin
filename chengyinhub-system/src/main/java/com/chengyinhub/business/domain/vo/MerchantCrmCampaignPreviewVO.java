package com.chengyinhub.business.domain.vo;

/** 服务端重算预览；不返回 memberId 或手机号。 */
public class MerchantCrmCampaignPreviewVO {
    private Long segmentId;
    private String channel;
    private Integer totalCount;
    private Integer consentedCount;
    private Integer frequencyLimitedCount;
    private Integer deliverableCount;
    private Integer recipientLimit;

    public Long getSegmentId() { return segmentId; }
    public void setSegmentId(Long value) { segmentId = value; }
    public String getChannel() { return channel; }
    public void setChannel(String value) { channel = value; }
    public Integer getTotalCount() { return totalCount; }
    public void setTotalCount(Integer value) { totalCount = value; }
    public Integer getConsentedCount() { return consentedCount; }
    public void setConsentedCount(Integer value) { consentedCount = value; }
    public Integer getFrequencyLimitedCount() { return frequencyLimitedCount; }
    public void setFrequencyLimitedCount(Integer value) { frequencyLimitedCount = value; }
    public Integer getDeliverableCount() { return deliverableCount; }
    public void setDeliverableCount(Integer value) { deliverableCount = value; }
    public Integer getRecipientLimit() { return recipientLimit; }
    public void setRecipientLimit(Integer value) { recipientLimit = value; }
}
