package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 原账本收入行（只读）：毛额行与冲回行共用同一形状。
 *
 * <p>{@code receiverKind} 来自 {@code coop_settlement_original_component.receiver_kind}：
 * 只有 {@code MEMBER} 且 {@code memberId > 0} 才是会员收入；{@code ACCOUNTING}（如平台行）
 * 单列，绝不摊给任何会员。
 */
public class CreatorRevenueRow {
    private String receiverKind;
    private Long memberId;
    private BigDecimal grossAmount;
    private BigDecimal refundedAmount;
    private Long componentCount;
    private Long settlementCount;
    private Long maxComponentId;
    private Date watermarkAt;

    public String getReceiverKind() { return receiverKind; }
    public void setReceiverKind(String receiverKind) { this.receiverKind = receiverKind; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public BigDecimal getGrossAmount() { return grossAmount; }
    public void setGrossAmount(BigDecimal grossAmount) { this.grossAmount = grossAmount; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public void setRefundedAmount(BigDecimal refundedAmount) { this.refundedAmount = refundedAmount; }
    public Long getComponentCount() { return componentCount; }
    public void setComponentCount(Long componentCount) { this.componentCount = componentCount; }
    public Long getSettlementCount() { return settlementCount; }
    public void setSettlementCount(Long settlementCount) { this.settlementCount = settlementCount; }
    public Long getMaxComponentId() { return maxComponentId; }
    public void setMaxComponentId(Long maxComponentId) { this.maxComponentId = maxComponentId; }
    public Date getWatermarkAt() { return watermarkAt; }
    public void setWatermarkAt(Date watermarkAt) { this.watermarkAt = watermarkAt; }
}
