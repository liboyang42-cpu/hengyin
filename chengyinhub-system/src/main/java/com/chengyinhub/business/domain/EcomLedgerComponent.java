package com.chengyinhub.business.domain;

/** 账本份额行（coop_settlement_original_component 的只读投影）。 */
public class EcomLedgerComponent {
    private Long settlementId;
    private String lineType;
    private String receiverKind;
    private Long receiverMemberId;
    private java.math.BigDecimal originalAmount;

    public Long getSettlementId() { return settlementId; }
    public void setSettlementId(Long settlementId) { this.settlementId = settlementId; }
    public String getLineType() { return lineType; }
    public void setLineType(String lineType) { this.lineType = lineType; }
    public String getReceiverKind() { return receiverKind; }
    public void setReceiverKind(String receiverKind) { this.receiverKind = receiverKind; }
    public Long getReceiverMemberId() { return receiverMemberId; }
    public void setReceiverMemberId(Long receiverMemberId) { this.receiverMemberId = receiverMemberId; }
    public java.math.BigDecimal getOriginalAmount() { return originalAmount; }
    public void setOriginalAmount(java.math.BigDecimal originalAmount) { this.originalAmount = originalAmount; }
    private Long clubId;
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    private String clubName;
    public String getClubName() { return clubName; }
    public void setClubName(String clubName) { this.clubName = clubName; }
    private String personalOpenid;
    public String getPersonalOpenid() { return personalOpenid; }
    public void setPersonalOpenid(String personalOpenid) { this.personalOpenid = personalOpenid; }
    private String personalAppid;
    public String getPersonalAppid() { return personalAppid; }
    public void setPersonalAppid(String personalAppid) { this.personalAppid = personalAppid; }
}
