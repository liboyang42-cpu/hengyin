package com.chengyinhub.business.domain.vo;

import java.util.Date;
import java.util.Map;

/** 一次履约一行；金额未成立时 settlementAmount 必须为 null。 */
public class MerchantRedemptionView {
    private String recordKey;
    private String recordType;
    private String recordId;
    private String topicName;
    private String chapterName;
    private String storeName;
    private String customerDisplayName;
    private String verificationCodeTail;
    private String settlementAmount;
    private String fulfillmentState;
    private String settlementState;
    private String settlementRoute;
    private String displayState;
    private String noCashReason;
    private String refundState;
    private Date occurredAt;
    private Map<String, Object> publicSettlement;

    public String getRecordKey() { return recordKey; }
    public void setRecordKey(String value) { recordKey = value; }
    public String getRecordType() { return recordType; }
    public void setRecordType(String value) { recordType = value; }
    public String getRecordId() { return recordId; }
    public void setRecordId(String value) { recordId = value; }
    public String getTopicName() { return topicName; }
    public void setTopicName(String value) { topicName = value; }
    public String getChapterName() { return chapterName; }
    public void setChapterName(String value) { chapterName = value; }
    public String getStoreName() { return storeName; }
    public void setStoreName(String value) { storeName = value; }
    public String getCustomerDisplayName() { return customerDisplayName; }
    public void setCustomerDisplayName(String value) { customerDisplayName = value; }
    public String getVerificationCodeTail() { return verificationCodeTail; }
    public void setVerificationCodeTail(String value) { verificationCodeTail = value; }
    public String getSettlementAmount() { return settlementAmount; }
    public void setSettlementAmount(String value) { settlementAmount = value; }
    public String getFulfillmentState() { return fulfillmentState; }
    public void setFulfillmentState(String value) { fulfillmentState = value; }
    public String getSettlementState() { return settlementState; }
    public void setSettlementState(String value) { settlementState = value; }
    public String getSettlementRoute() { return settlementRoute; }
    public void setSettlementRoute(String value) { settlementRoute = value; }
    public String getDisplayState() { return displayState; }
    public void setDisplayState(String value) { displayState = value; }
    public String getNoCashReason() { return noCashReason; }
    public void setNoCashReason(String value) { noCashReason = value; }
    public String getRefundState() { return refundState; }
    public void setRefundState(String value) { refundState = value; }
    public Date getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Date value) { occurredAt = value; }
    public Map<String, Object> getPublicSettlement() { return publicSettlement; }
    public void setPublicSettlement(Map<String, Object> value) { publicSettlement = value; }
}
