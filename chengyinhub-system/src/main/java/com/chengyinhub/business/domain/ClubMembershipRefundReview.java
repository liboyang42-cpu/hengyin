package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/** 只表达退款人工审核事实；本对象不代表微信已退款。 */
public class ClubMembershipRefundReview extends BaseEntity {
    private static final long serialVersionUID = 1L;
    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";

    private Long id;
    private Long clubId;
    private Long memberId;
    private Long invoiceId;
    private String status;
    private String reason;
    private String requestId;
    private Integer version;
    /** W7 MERCHANT-12: APPROVED / REJECTED once operations decided; null while the review is open. */
    private String decision;
    /** W7 MERCHANT-12: NOT_SENT / SUBMITTING / ACCEPTED / FAILED / UNKNOWN for an approval. */
    private String refundState;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getInvoiceId() { return invoiceId; }
    public void setInvoiceId(Long invoiceId) { this.invoiceId = invoiceId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getRefundState() { return refundState; }
    public void setRefundState(String refundState) { this.refundState = refundState; }
}
