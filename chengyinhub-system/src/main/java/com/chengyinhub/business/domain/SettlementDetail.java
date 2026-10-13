package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.math.BigDecimal;

/**
 * P3 结算明细 settlement_detail。
 */
public class SettlementDetail extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long claimId;
    private String entryKind;
    private String eventKey;
    private Long batchId;
    private Long merchantId;
    private String sourceType;
    private Long sourceId;
    private String receiverType;
    private Long receiverId;
    private BigDecimal grossAmount;
    private BigDecimal commissionAmount;
    private BigDecimal settlementAmount;
    private Integer status;
    private String failReason;
    private String idempotencyKey;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClaimId() { return claimId; }
    public void setClaimId(Long value) { this.claimId = value; }
    public String getEntryKind() { return entryKind; }
    public void setEntryKind(String value) { this.entryKind = value; }
    public String getEventKey() { return eventKey; }
    public void setEventKey(String value) { this.eventKey = value; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public String getReceiverType() { return receiverType; }
    public void setReceiverType(String receiverType) { this.receiverType = receiverType; }
    public Long getReceiverId() { return receiverId; }
    public void setReceiverId(Long receiverId) { this.receiverId = receiverId; }
    public BigDecimal getGrossAmount() { return grossAmount; }
    public void setGrossAmount(BigDecimal grossAmount) { this.grossAmount = grossAmount; }
    public BigDecimal getCommissionAmount() { return commissionAmount; }
    public void setCommissionAmount(BigDecimal commissionAmount) { this.commissionAmount = commissionAmount; }
    public BigDecimal getSettlementAmount() { return settlementAmount; }
    public void setSettlementAmount(BigDecimal settlementAmount) { this.settlementAmount = settlementAmount; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
