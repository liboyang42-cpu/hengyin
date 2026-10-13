package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/** 一张已付会费账单产生的一段会员权益期；与免费 club_member 身份完全分离。 */
public class ClubMembershipTerm extends BaseEntity {
    private static final long serialVersionUID = 1L;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_GRACE = "GRACE";
    public static final String STATUS_PAST_DUE = "PAST_DUE";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_REFUNDED = "REFUNDED";

    private Long id;
    private Long clubId;
    private Long memberId;
    private Long planId;
    private Long sourceInvoiceId;
    private String status;
    private Date startsAt;
    private Date endsAt;
    private Date graceEndsAt;
    private Date expiresAt;
    private String benefitsSnapshot;
    private String exitPolicySnapshot;
    private String refundPolicySnapshot;
    private Integer version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }
    public Long getSourceInvoiceId() { return sourceInvoiceId; }
    public void setSourceInvoiceId(Long sourceInvoiceId) { this.sourceInvoiceId = sourceInvoiceId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Date getStartsAt() { return startsAt; }
    public void setStartsAt(Date startsAt) { this.startsAt = startsAt; }
    public Date getEndsAt() { return endsAt; }
    public void setEndsAt(Date endsAt) { this.endsAt = endsAt; }
    public Date getGraceEndsAt() { return graceEndsAt; }
    public void setGraceEndsAt(Date graceEndsAt) { this.graceEndsAt = graceEndsAt; }
    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }
    public String getBenefitsSnapshot() { return benefitsSnapshot; }
    public void setBenefitsSnapshot(String benefitsSnapshot) { this.benefitsSnapshot = benefitsSnapshot; }
    public String getExitPolicySnapshot() { return exitPolicySnapshot; }
    public void setExitPolicySnapshot(String exitPolicySnapshot) { this.exitPolicySnapshot = exitPolicySnapshot; }
    public String getRefundPolicySnapshot() { return refundPolicySnapshot; }
    public void setRefundPolicySnapshot(String refundPolicySnapshot) { this.refundPolicySnapshot = refundPolicySnapshot; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
