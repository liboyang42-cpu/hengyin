package com.chengyinhub.business.citygame.analytics.value;

import java.util.Date;

/** 已接受的服务端价值事件。去重键由服务端按自然身份计算。 */
public class ValueEventRow {
    private Long id;
    private String dedupeKey;
    private String payloadHash;
    private String sourceId;
    private String kind;
    private String regionId;
    private String boardId;
    private String seasonId;
    private String rulesReleaseId;
    private Long memberId;
    private String visitId;
    private String serviceId;
    private String actionCode;
    private String goalId;
    private String reasonCode;
    private Long amount;
    private String unit;
    private String currency;
    private Integer rating;
    private String detailJson;
    private Date occurredAt;
    private Date createTime;

    public ValueEventRow copy() {
        ValueEventRow row = new ValueEventRow();
        row.id = id;
        row.dedupeKey = dedupeKey;
        row.payloadHash = payloadHash;
        row.sourceId = sourceId;
        row.kind = kind;
        row.regionId = regionId;
        row.boardId = boardId;
        row.seasonId = seasonId;
        row.rulesReleaseId = rulesReleaseId;
        row.memberId = memberId;
        row.visitId = visitId;
        row.serviceId = serviceId;
        row.actionCode = actionCode;
        row.goalId = goalId;
        row.reasonCode = reasonCode;
        row.amount = amount;
        row.unit = unit;
        row.currency = currency;
        row.rating = rating;
        row.detailJson = detailJson;
        row.occurredAt = occurredAt == null ? null : new Date(occurredAt.getTime());
        row.createTime = createTime == null ? null : new Date(createTime.getTime());
        return row;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String dedupeKey) { this.dedupeKey = dedupeKey; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getRegionId() { return regionId; }
    public void setRegionId(String regionId) { this.regionId = regionId; }
    public String getBoardId() { return boardId; }
    public void setBoardId(String boardId) { this.boardId = boardId; }
    public String getSeasonId() { return seasonId; }
    public void setSeasonId(String seasonId) { this.seasonId = seasonId; }
    public String getRulesReleaseId() { return rulesReleaseId; }
    public void setRulesReleaseId(String rulesReleaseId) { this.rulesReleaseId = rulesReleaseId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getVisitId() { return visitId; }
    public void setVisitId(String visitId) { this.visitId = visitId; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }
    public String getActionCode() { return actionCode; }
    public void setActionCode(String actionCode) { this.actionCode = actionCode; }
    public String getGoalId() { return goalId; }
    public void setGoalId(String goalId) { this.goalId = goalId; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
    public Date getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Date occurredAt) { this.occurredAt = occurredAt; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
