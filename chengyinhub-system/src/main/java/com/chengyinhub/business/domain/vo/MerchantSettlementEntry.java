package com.chengyinhub.business.domain.vo;

import java.util.Date;
import java.util.Map;

/** 已成立的收入/调整账务事实；待主题结算不使用此对象。 */
public class MerchantSettlementEntry {
    private String entryKey;
    private Map<String, Object> claimRef;
    private String settlementRoute;
    private String entryKind;
    private String source;
    private String sourceId;
    private String destination;
    private String signedAmount;
    private Integer headCount;
    private Date occurredAt;
    private Date arrivalAt;
    private String displayState;
    private String refKind;
    private String refId;
    private Map<String, String> ref;
    /** 对公倒扣命中的批次，仅用于批次详情归集；不替代安全说明 ref。 */
    private String targetBatchId;

    public String getEntryKey() { return entryKey; }
    public void setEntryKey(String entryKey) { this.entryKey = entryKey; }
    public Map<String, Object> getClaimRef() { return claimRef; }
    public void setClaimRef(Map<String, Object> claimRef) { this.claimRef = claimRef; }
    public String getSettlementRoute() { return settlementRoute; }
    public void setSettlementRoute(String settlementRoute) { this.settlementRoute = settlementRoute; }
    public String getEntryKind() { return entryKind; }
    public void setEntryKind(String entryKind) { this.entryKind = entryKind; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public String getSignedAmount() { return signedAmount; }
    public void setSignedAmount(String signedAmount) { this.signedAmount = signedAmount; }
    public Integer getHeadCount() { return headCount; }
    public void setHeadCount(Integer headCount) { this.headCount = headCount; }
    public Date getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Date occurredAt) { this.occurredAt = occurredAt; }
    public Date getArrivalAt() { return arrivalAt; }
    public void setArrivalAt(Date arrivalAt) { this.arrivalAt = arrivalAt; }
    public String getDisplayState() { return displayState; }
    public void setDisplayState(String displayState) { this.displayState = displayState; }
    public String getRefKind() { return refKind; }
    public void setRefKind(String refKind) { this.refKind = refKind; }
    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }
    public Map<String, String> getRef() { return ref; }
    public void setRef(Map<String, String> ref) { this.ref = ref; }
    public String getTargetBatchId() { return targetBatchId; }
    public void setTargetBatchId(String targetBatchId) { this.targetBatchId = targetBatchId; }
}
