package com.chengyinhub.business.domain.vo;

/**
 * 章节招商读侧统计。申请与生效供给分开计数，reservedMerchantCount 用于名额与开放状态。
 */
public class MerchantChapterRecruitmentStats {
    private Integer pendingApplicationCount;
    private Integer approvedApplicationCount;
    private Integer activeOfferCount;
    /** 已通过申请与已生效供给按 merchantId 去重后的名额占用数。 */
    private Integer reservedMerchantCount;
    /** null 表示不限商家数，0 表示已满。 */
    private Integer remainingMerchantCount;

    public Integer getPendingApplicationCount() { return pendingApplicationCount; }
    public void setPendingApplicationCount(Integer pendingApplicationCount) { this.pendingApplicationCount = pendingApplicationCount; }
    public Integer getApprovedApplicationCount() { return approvedApplicationCount; }
    public void setApprovedApplicationCount(Integer approvedApplicationCount) { this.approvedApplicationCount = approvedApplicationCount; }
    public Integer getActiveOfferCount() { return activeOfferCount; }
    public void setActiveOfferCount(Integer activeOfferCount) { this.activeOfferCount = activeOfferCount; }
    public Integer getReservedMerchantCount() { return reservedMerchantCount; }
    public void setReservedMerchantCount(Integer reservedMerchantCount) { this.reservedMerchantCount = reservedMerchantCount; }
    public Integer getRemainingMerchantCount() { return remainingMerchantCount; }
    public void setRemainingMerchantCount(Integer remainingMerchantCount) { this.remainingMerchantCount = remainingMerchantCount; }
}
