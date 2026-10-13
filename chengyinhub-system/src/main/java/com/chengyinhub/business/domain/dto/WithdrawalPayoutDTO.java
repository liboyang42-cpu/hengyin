package com.chengyinhub.business.domain.dto;

/** 审核通过后的银行打款确认。 */
public class WithdrawalPayoutDTO {
    private Long id;
    private String payoutReference;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPayoutReference() { return payoutReference; }
    public void setPayoutReference(String payoutReference) { this.payoutReference = payoutReference; }
}
