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
    /** 已结算钱的去向:商家自有活动票结进 App 内余额(PERSONAL_ACCOUNT);章节核销走平台批次打款为 null。 */
    private String destination;
    private String displayState;
    private String noCashReason;
    private String refundState;
    private Date occurredAt;
    private Map<String, Object> publicSettlement;
    /** 第十二轮 k5:俱乐部场次核销费的冻结单价(¥/人)与计费人数;其它记录为空。 */
    private String unitFee;
    private Integer headCount;

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
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
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
    public String getUnitFee() { return unitFee; }
    public void setUnitFee(String value) { unitFee = value; }
    public Integer getHeadCount() { return headCount; }
    public void setHeadCount(Integer value) { headCount = value; }
}
