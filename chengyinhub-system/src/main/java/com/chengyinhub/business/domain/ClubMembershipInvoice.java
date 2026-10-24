package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/** 会费账单；金额、计划、权益和政策快照在创建后不可变。 */
public class ClubMembershipInvoice extends BaseEntity {
    private static final long serialVersionUID = 1L;
    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_PAYING = "PAYING";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_CLOSED = "CLOSED";

    private Long id;
    private Long verifiedPaymentFactId;
    private String verifiedPaymentFactHash;
    private String invoiceNo;
    private String outTradeNo;
    private Long clubId;
    private Long ownerMemberIdSnapshot;
    private Long memberId;
    private Long planId;
    private Integer planVersionSnapshot;
    private String planNameSnapshot;
    private Integer durationDaysSnapshot;
    private Long amountFen;
    private String benefitsSnapshot;
    private Integer graceDaysSnapshot;
    private Integer pastDueDaysSnapshot;
    private String exitPolicySnapshot;
    private String refundPolicySnapshot;
    private String snapshotHash;
    private String status;
    private String idempotencyKey;
    private String requestFingerprint;
    private String transactionId;
    private String payParamsJson;
    private Date resumeBlockedAt;
    private Date paidAt;
    private Integer version;

    /** Internal immutable successful consumption proof, never a client-writable API field. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public Long getVerifiedPaymentFactId() { return verifiedPaymentFactId; }
    public void setVerifiedPaymentFactId(Long value) { verifiedPaymentFactId = value; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getVerifiedPaymentFactHash() { return verifiedPaymentFactHash; }
    public void setVerifiedPaymentFactHash(String value) { verifiedPaymentFactHash = value; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getInvoiceNo() { return invoiceNo; }
    public void setInvoiceNo(String invoiceNo) { this.invoiceNo = invoiceNo; }
    public String getOutTradeNo() { return outTradeNo; }
    public void setOutTradeNo(String outTradeNo) { this.outTradeNo = outTradeNo; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getOwnerMemberIdSnapshot() { return ownerMemberIdSnapshot; }
    public void setOwnerMemberIdSnapshot(Long ownerMemberIdSnapshot) { this.ownerMemberIdSnapshot = ownerMemberIdSnapshot; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }
    public Integer getPlanVersionSnapshot() { return planVersionSnapshot; }
    public void setPlanVersionSnapshot(Integer planVersionSnapshot) { this.planVersionSnapshot = planVersionSnapshot; }
    public String getPlanNameSnapshot() { return planNameSnapshot; }
    public void setPlanNameSnapshot(String planNameSnapshot) { this.planNameSnapshot = planNameSnapshot; }
    public Integer getDurationDaysSnapshot() { return durationDaysSnapshot; }
    public void setDurationDaysSnapshot(Integer durationDaysSnapshot) { this.durationDaysSnapshot = durationDaysSnapshot; }
    public Long getAmountFen() { return amountFen; }
    public void setAmountFen(Long amountFen) { this.amountFen = amountFen; }
    public String getBenefitsSnapshot() { return benefitsSnapshot; }
    public void setBenefitsSnapshot(String benefitsSnapshot) { this.benefitsSnapshot = benefitsSnapshot; }
    public Integer getGraceDaysSnapshot() { return graceDaysSnapshot; }
    public void setGraceDaysSnapshot(Integer graceDaysSnapshot) { this.graceDaysSnapshot = graceDaysSnapshot; }
    public Integer getPastDueDaysSnapshot() { return pastDueDaysSnapshot; }
    public void setPastDueDaysSnapshot(Integer pastDueDaysSnapshot) { this.pastDueDaysSnapshot = pastDueDaysSnapshot; }
    public String getExitPolicySnapshot() { return exitPolicySnapshot; }
    public void setExitPolicySnapshot(String exitPolicySnapshot) { this.exitPolicySnapshot = exitPolicySnapshot; }
    public String getRefundPolicySnapshot() { return refundPolicySnapshot; }
    public void setRefundPolicySnapshot(String refundPolicySnapshot) { this.refundPolicySnapshot = refundPolicySnapshot; }
    public String getSnapshotHash() { return snapshotHash; }
    public void setSnapshotHash(String snapshotHash) { this.snapshotHash = snapshotHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public String getPayParamsJson() { return payParamsJson; }
    public void setPayParamsJson(String payParamsJson) { this.payParamsJson = payParamsJson; }
    public Date getResumeBlockedAt() { return resumeBlockedAt; }
    public void setResumeBlockedAt(Date resumeBlockedAt) { this.resumeBlockedAt = resumeBlockedAt; }
    public Date getPaidAt() { return paidAt; }
    public void setPaidAt(Date paidAt) { this.paidAt = paidAt; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
