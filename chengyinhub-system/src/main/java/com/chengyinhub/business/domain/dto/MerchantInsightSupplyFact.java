package com.chengyinhub.business.domain.dto;

/**
 * 商家店铺参谋:在架供给(merchant_chapter_offer)一行。
 *
 * @author chengyinhub
 */
public class MerchantInsightSupplyFact
{
    private String topicName;
    private Integer quotaTotal;
    private Integer quotaUsed;

    public String getTopicName() { return topicName; }
    public void setTopicName(String topicName) { this.topicName = topicName; }
    public Integer getQuotaTotal() { return quotaTotal; }
    public void setQuotaTotal(Integer quotaTotal) { this.quotaTotal = quotaTotal; }
    public Integer getQuotaUsed() { return quotaUsed; }
    public void setQuotaUsed(Integer quotaUsed) { this.quotaUsed = quotaUsed; }
}
