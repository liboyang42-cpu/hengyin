package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.ClubMembershipPlan;
import com.chengyinhub.business.domain.ClubMembershipInvoice;
import com.chengyinhub.business.domain.ClubMembershipRefundReview;
import com.chengyinhub.business.domain.ClubMembershipTerm;

import java.util.Collections;
import java.util.List;

public class ClubMembershipOverviewVO {
    private boolean clubMembershipEnabled;
    private boolean autoRenewAvailable;
    private String membershipType;
    private String effectiveStatus;
    private boolean hasEntitlement;
    private ClubMembershipTerm term;
    private List<ClubMembershipPlan> plans = Collections.emptyList();
    private List<ClubMembershipRefundReview> refundReviews = Collections.emptyList();
    private List<ClubMembershipInvoice> invoices = Collections.emptyList();

    public boolean isClubMembershipEnabled() { return clubMembershipEnabled; }
    public void setClubMembershipEnabled(boolean clubMembershipEnabled) { this.clubMembershipEnabled = clubMembershipEnabled; }
    public boolean isAutoRenewAvailable() { return autoRenewAvailable; }
    public void setAutoRenewAvailable(boolean autoRenewAvailable) { this.autoRenewAvailable = autoRenewAvailable; }
    public String getMembershipType() { return membershipType; }
    public void setMembershipType(String membershipType) { this.membershipType = membershipType; }
    public String getEffectiveStatus() { return effectiveStatus; }
    public void setEffectiveStatus(String effectiveStatus) { this.effectiveStatus = effectiveStatus; }
    public boolean isHasEntitlement() { return hasEntitlement; }
    public void setHasEntitlement(boolean hasEntitlement) { this.hasEntitlement = hasEntitlement; }
    public ClubMembershipTerm getTerm() { return term; }
    public void setTerm(ClubMembershipTerm term) { this.term = term; }
    public List<ClubMembershipPlan> getPlans() { return plans; }
    public void setPlans(List<ClubMembershipPlan> plans) { this.plans = plans; }
    public List<ClubMembershipRefundReview> getRefundReviews() { return refundReviews; }
    public void setRefundReviews(List<ClubMembershipRefundReview> refundReviews) { this.refundReviews = refundReviews; }
    public List<ClubMembershipInvoice> getInvoices() { return invoices; }
    public void setInvoices(List<ClubMembershipInvoice> invoices) { this.invoices = invoices; }
}
