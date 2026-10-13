package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/**
 * 主题结算DTO
 */
public class TopicSettlementDTO {

    /** 主题ID */
    private Long topicId;

    /** 主题名称 */
    private String topicName;

    /** 主题总销售额 */
    private BigDecimal totalSales;

    /** 平台佣金比例（占销售额） */
    private BigDecimal platformCommissionRate;

    /** 商家佣金比例（占销售额） */
    private BigDecimal merchantCommissionRate;

    /** 发布者佣金比例（占销售额） */
    private BigDecimal publisherCommissionRate;

    /** 邀请人佣金比例（占商家分润的比例） */
    private BigDecimal inviteCommissionRate; // 修改：这是占商家分润的比例

    /** 平台佣金金额 */
    private BigDecimal platformCommissionAmount;

    /** 商家总佣金金额 */
    private BigDecimal merchantTotalCommissionAmount;

    /** 发布者佣金金额 */
    private BigDecimal publisherCommissionAmount;

    // getter setter
    public Long getTopicId() {
        return topicId;
    }

    public void setTopicId(Long topicId) {
        this.topicId = topicId;
    }

    public String getTopicName() {
        return topicName;
    }

    public void setTopicName(String topicName) {
        this.topicName = topicName;
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

    public BigDecimal getPublisherCommissionRate() {
        return publisherCommissionRate;
    }

    public void setPublisherCommissionRate(BigDecimal publisherCommissionRate) {
        this.publisherCommissionRate = publisherCommissionRate;
    }

    public BigDecimal getInviteCommissionRate() {
        return inviteCommissionRate;
    }

    public void setInviteCommissionRate(BigDecimal inviteCommissionRate) {
        this.inviteCommissionRate = inviteCommissionRate;
    }

    public BigDecimal getPlatformCommissionAmount() {
        return platformCommissionAmount;
    }

    public void setPlatformCommissionAmount(BigDecimal platformCommissionAmount) {
        this.platformCommissionAmount = platformCommissionAmount;
    }

    public BigDecimal getMerchantTotalCommissionAmount() {
        return merchantTotalCommissionAmount;
    }

    public void setMerchantTotalCommissionAmount(BigDecimal merchantTotalCommissionAmount) {
        this.merchantTotalCommissionAmount = merchantTotalCommissionAmount;
    }

    public BigDecimal getPublisherCommissionAmount() {
        return publisherCommissionAmount;
    }

    public void setPublisherCommissionAmount(BigDecimal publisherCommissionAmount) {
        this.publisherCommissionAmount = publisherCommissionAmount;
    }
}