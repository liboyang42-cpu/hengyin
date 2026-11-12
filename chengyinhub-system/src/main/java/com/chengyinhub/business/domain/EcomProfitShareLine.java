package com.chengyinhub.business.domain;

/** 分账明细（ecom_profit_share_line）。 */
public class EcomProfitShareLine {
    private Long id;
    private Long shareOrderId;
    private Long settlementId;
    private String lineType;
    private String receiverKind;
    private String receiverMchid;
    private Long receiverMemberId;
    private Long amountFen;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getShareOrderId() { return shareOrderId; }
    public void setShareOrderId(Long shareOrderId) { this.shareOrderId = shareOrderId; }
    public Long getSettlementId() { return settlementId; }
    public void setSettlementId(Long settlementId) { this.settlementId = settlementId; }
    public String getLineType() { return lineType; }
    public void setLineType(String lineType) { this.lineType = lineType; }
    public String getReceiverKind() { return receiverKind; }
    public void setReceiverKind(String receiverKind) { this.receiverKind = receiverKind; }
    public String getReceiverMchid() { return receiverMchid; }
    public void setReceiverMchid(String receiverMchid) { this.receiverMchid = receiverMchid; }
    public Long getReceiverMemberId() { return receiverMemberId; }
    public void setReceiverMemberId(Long receiverMemberId) { this.receiverMemberId = receiverMemberId; }
    public Long getAmountFen() { return amountFen; }
    public void setAmountFen(Long amountFen) { this.amountFen = amountFen; }
    private String receiverType = "MERCHANT_ID";
    public String getReceiverType() { return receiverType; }
    public void setReceiverType(String receiverType) { this.receiverType = receiverType; }
    private String receiverAppid = "";
    public String getReceiverAppid() { return receiverAppid; }
    public void setReceiverAppid(String receiverAppid) { this.receiverAppid = receiverAppid; }
    private Long clubId;
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    private String clubName;
    public String getClubName() { return clubName; }
    public void setClubName(String clubName) { this.clubName = clubName; }
    public String getSourceLabel() { return clubId == null ? null : "来自俱乐部 " + clubName; }
}
