package com.chengyinhub.business.domain.vo;

/** 广播预览:只报服务端重算出的真实人数,不读客户端 memberId。 */
public class MerchantCrmBroadcastPreviewVO {
    /** 当前筛选(未收窄范围)命中总数,给商家对照「筛选共多少人」。 */
    private Long filterTotalCount;
    /** 本批冻结名单人数(已按 scope/groups 收窄,单次上限见 recipientLimit)。 */
    private Integer audienceCount;
    private Integer consentedCount;
    private Integer noConsentCount;
    private Integer frequencyLimitedCount;
    private Integer deliverableCount;
    private Integer recipientLimit;
    /** 同一商家每天最多发几条(常量)。 */
    private Integer merchantDailyLimit;
    private Integer merchantDailyUsed;
    private Integer merchantDailyRemaining;

    public Long getFilterTotalCount() { return filterTotalCount; }
    public void setFilterTotalCount(Long value) { filterTotalCount = value; }
    public Integer getAudienceCount() { return audienceCount; }
    public void setAudienceCount(Integer value) { audienceCount = value; }
    public Integer getConsentedCount() { return consentedCount; }
    public void setConsentedCount(Integer value) { consentedCount = value; }
    public Integer getNoConsentCount() { return noConsentCount; }
    public void setNoConsentCount(Integer value) { noConsentCount = value; }
    public Integer getFrequencyLimitedCount() { return frequencyLimitedCount; }
    public void setFrequencyLimitedCount(Integer value) { frequencyLimitedCount = value; }
    public Integer getDeliverableCount() { return deliverableCount; }
    public void setDeliverableCount(Integer value) { deliverableCount = value; }
    public Integer getRecipientLimit() { return recipientLimit; }
    public void setRecipientLimit(Integer value) { recipientLimit = value; }
    public Integer getMerchantDailyLimit() { return merchantDailyLimit; }
    public void setMerchantDailyLimit(Integer value) { merchantDailyLimit = value; }
    public Integer getMerchantDailyUsed() { return merchantDailyUsed; }
    public void setMerchantDailyUsed(Integer value) { merchantDailyUsed = value; }
    public Integer getMerchantDailyRemaining() { return merchantDailyRemaining; }
    public void setMerchantDailyRemaining(Integer value) { merchantDailyRemaining = value; }
}
