package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/**
 * E4 商家付费权益/订阅 merchant_subscription
 * 先做能力不真收费:本表记录已授予的权益(首批人工授予);定价/分成后台可配。
 */
public class MerchantSubscription extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 商家 memberId */
    private Long merchantId;

    /** 权益类型 premium_template/promotion_slot/brand_home/custom_event */
    private String subscriptionType;

    private Date startDate;

    /** 到期时间(NULL=永久) */
    private Date endDate;

    /** 可用次数(NULL=不限) */
    private Integer maxUsage;

    private Integer usedCount;

    /** 1生效 0停用 */
    private Integer isActive;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public String getSubscriptionType() { return subscriptionType; }
    public void setSubscriptionType(String subscriptionType) { this.subscriptionType = subscriptionType; }
    public Date getStartDate() { return startDate; }
    public void setStartDate(Date startDate) { this.startDate = startDate; }
    public Date getEndDate() { return endDate; }
    public void setEndDate(Date endDate) { this.endDate = endDate; }
    public Integer getMaxUsage() { return maxUsage; }
    public void setMaxUsage(Integer maxUsage) { this.maxUsage = maxUsage; }
    public Integer getUsedCount() { return usedCount; }
    public void setUsedCount(Integer usedCount) { this.usedCount = usedCount; }
    public Integer getIsActive() { return isActive; }
    public void setIsActive(Integer isActive) { this.isActive = isActive; }
}
