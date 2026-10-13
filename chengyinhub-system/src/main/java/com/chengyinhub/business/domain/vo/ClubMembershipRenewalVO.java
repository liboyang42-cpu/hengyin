package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.ClubMembershipInvoice;

import java.util.Collections;
import java.util.Map;

public class ClubMembershipRenewalVO {
    private ClubMembershipInvoice invoice;
    private Map<String, String> payParams = Collections.emptyMap();
    private boolean recoverable;
    private String paymentState;

    public ClubMembershipInvoice getInvoice() { return invoice; }
    public void setInvoice(ClubMembershipInvoice invoice) { this.invoice = invoice; }
    public Map<String, String> getPayParams() { return payParams; }
    public void setPayParams(Map<String, String> payParams) { this.payParams = payParams; }
    public boolean isRecoverable() { return recoverable; }
    public void setRecoverable(boolean recoverable) { this.recoverable = recoverable; }
    public String getPaymentState() { return paymentState; }
    public void setPaymentState(String paymentState) { this.paymentState = paymentState; }
}
