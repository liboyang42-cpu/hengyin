package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/**
 * 活动结算DTO
 */
public class ActivitySettlementDTO {

    /** 活动ID */
    private Long activityId;

    /** 活动名称 */
    private String activityName;

    /** 活动总销售额 */
    private BigDecimal totalSales;

    /** 平台佣金比例 */
    private BigDecimal platformCommissionRate;

    /** 商户佣金比例 */
    private BigDecimal merchantCommissionRate;

    /** 主题设计者佣金比例 */
    private BigDecimal designerCommissionRate;

    /** 平台佣金金额 */
    private BigDecimal platformCommissionAmount;

    /** 商户佣金金额 */
    private BigDecimal merchantCommissionAmount;

    /** 主题设计者佣金金额 */
    private BigDecimal designerCommissionAmount;

    /** 主题设计者会员ID（无主题来源时为空） */
    private Long designerMemberId;

    // getter setter
    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
    }

    public String getActivityName() {
        return activityName;
    }

    public void setActivityName(String activityName) {
        this.activityName = activityName;
    }

    public BigDecimal getTotalSales() {
        return totalSales;
    }

    public void setTotalSales(BigDecimal totalSales) {
        this.totalSales = totalSales;
    }

    public BigDecimal getPlatformCommissionRate() {
        return platformCommissionRate;
    }

    public void setPlatformCommissionRate(BigDecimal platformCommissionRate) {
        this.platformCommissionRate = platformCommissionRate;
    }

    public BigDecimal getMerchantCommissionRate() {
        return merchantCommissionRate;
    }

    public void setMerchantCommissionRate(BigDecimal merchantCommissionRate) {
        this.merchantCommissionRate = merchantCommissionRate;
    }

    public BigDecimal getDesignerCommissionRate() {
        return designerCommissionRate;
    }

    public void setDesignerCommissionRate(BigDecimal designerCommissionRate) {
        this.designerCommissionRate = designerCommissionRate;
    }

    public BigDecimal getPlatformCommissionAmount() {
        return platformCommissionAmount;
    }

    public void setPlatformCommissionAmount(BigDecimal platformCommissionAmount) {
        this.platformCommissionAmount = platformCommissionAmount;
    }

    public BigDecimal getMerchantCommissionAmount() {
        return merchantCommissionAmount;
    }

    public void setMerchantCommissionAmount(BigDecimal merchantCommissionAmount) {
        this.merchantCommissionAmount = merchantCommissionAmount;
    }

    public BigDecimal getDesignerCommissionAmount() {
        return designerCommissionAmount;
    }

    public void setDesignerCommissionAmount(BigDecimal designerCommissionAmount) {
        this.designerCommissionAmount = designerCommissionAmount;
    }

    public Long getDesignerMemberId() {
        return designerMemberId;
    }

    public void setDesignerMemberId(Long designerMemberId) {
        this.designerMemberId = designerMemberId;
    }
}
