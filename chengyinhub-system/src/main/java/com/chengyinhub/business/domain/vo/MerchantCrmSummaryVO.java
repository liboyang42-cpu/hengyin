package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;

/**
 * P3 商家 CRM 汇总。
 */
public class MerchantCrmSummaryVO {
    private Long merchantId;
    private Long customerCount;
    private Long activeCustomerCount;
    private Long couponCustomerCount;
    private BigDecimal orderAmount;

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getCustomerCount() { return customerCount; }
    public void setCustomerCount(Long customerCount) { this.customerCount = customerCount; }
    public Long getActiveCustomerCount() { return activeCustomerCount; }
    public void setActiveCustomerCount(Long activeCustomerCount) { this.activeCustomerCount = activeCustomerCount; }
    public Long getCouponCustomerCount() { return couponCustomerCount; }
    public void setCouponCustomerCount(Long couponCustomerCount) { this.couponCustomerCount = couponCustomerCount; }
    public BigDecimal getOrderAmount() { return orderAmount; }
    public void setOrderAmount(BigDecimal orderAmount) { this.orderAmount = orderAmount; }
}
