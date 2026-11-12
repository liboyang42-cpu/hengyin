package com.chengyinhub.business.domain;

/** 创作者打款请求（creator_payout_request）。 */
public class CreatorPayoutRequest {
    private Long id;
    private String outPayoutNo;
    private Long creatorMemberId;
    private String sourceType;
    private Long sourceId;
    private Long amountFen;
    private String provider;
    private String state;
    private String providerRef;
    private String failReason;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOutPayoutNo() { return outPayoutNo; }
    public void setOutPayoutNo(String outPayoutNo) { this.outPayoutNo = outPayoutNo; }
    public Long getCreatorMemberId() { return creatorMemberId; }
    public void setCreatorMemberId(Long creatorMemberId) { this.creatorMemberId = creatorMemberId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Long getAmountFen() { return amountFen; }
    public void setAmountFen(Long amountFen) { this.amountFen = amountFen; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getProviderRef() { return providerRef; }
    public void setProviderRef(String providerRef) { this.providerRef = providerRef; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
}
