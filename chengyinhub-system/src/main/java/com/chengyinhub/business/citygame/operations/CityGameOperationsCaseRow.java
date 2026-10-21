package com.chengyinhub.business.citygame.operations;

import java.util.Date;

public class CityGameOperationsCaseRow {
    private Long id;
    private String actionId;
    private String regionId;
    private Long version;
    private Long expectedVersion;
    private String status;
    private String correctionState;
    private String execution;
    private String bindingStatus;
    private String sourceRequestId;
    private Long ownerUserId;
    private String objectRole;
    private Long proposerUserId;
    private Long reviewerUserId;
    private String reason;
    private String kind;
    private String merchantId;
    private String objectType;
    private String objectId;
    private String traceJson;
    private String proposalJson;
    private String rollbackJson;
    private String classification;
    private String opsNote;
    private String blocker;
    private Date createTime;
    private Date updateTime;

    public CityGameOperationsCaseRow copy() {
        CityGameOperationsCaseRow row = new CityGameOperationsCaseRow();
        row.id = id;
        row.actionId = actionId;
        row.regionId = regionId;
        row.version = version;
        row.expectedVersion = expectedVersion;
        row.status = status;
        row.correctionState = correctionState;
        row.execution = execution;
        row.bindingStatus = bindingStatus;
        row.sourceRequestId = sourceRequestId;
        row.ownerUserId = ownerUserId;
        row.objectRole = objectRole;
        row.proposerUserId = proposerUserId;
        row.reviewerUserId = reviewerUserId;
        row.reason = reason;
        row.kind = kind;
        row.merchantId = merchantId;
        row.objectType = objectType;
        row.objectId = objectId;
        row.traceJson = traceJson;
        row.proposalJson = proposalJson;
        row.rollbackJson = rollbackJson;
        row.classification = classification;
        row.opsNote = opsNote;
        row.blocker = blocker;
        row.createTime = createTime;
        row.updateTime = updateTime;
        return row;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getActionId() { return actionId; }
    public void setActionId(String actionId) { this.actionId = actionId; }
    public String getRegionId() { return regionId; }
    public void setRegionId(String regionId) { this.regionId = regionId; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Long getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCorrectionState() { return correctionState; }
    public void setCorrectionState(String correctionState) { this.correctionState = correctionState; }
    public String getExecution() { return execution; }
    public void setExecution(String execution) { this.execution = execution; }
    public String getBindingStatus() { return bindingStatus; }
    public void setBindingStatus(String bindingStatus) { this.bindingStatus = bindingStatus; }
    public String getSourceRequestId() { return sourceRequestId; }
    public void setSourceRequestId(String sourceRequestId) { this.sourceRequestId = sourceRequestId; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getObjectRole() { return objectRole; }
    public void setObjectRole(String objectRole) { this.objectRole = objectRole; }
    public Long getProposerUserId() { return proposerUserId; }
    public void setProposerUserId(Long proposerUserId) { this.proposerUserId = proposerUserId; }
    public Long getReviewerUserId() { return reviewerUserId; }
    public void setReviewerUserId(Long reviewerUserId) { this.reviewerUserId = reviewerUserId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }
    public String getObjectId() { return objectId; }
    public void setObjectId(String objectId) { this.objectId = objectId; }
    public String getTraceJson() { return traceJson; }
    public void setTraceJson(String traceJson) { this.traceJson = traceJson; }
    public String getProposalJson() { return proposalJson; }
    public void setProposalJson(String proposalJson) { this.proposalJson = proposalJson; }
    public String getRollbackJson() { return rollbackJson; }
    public void setRollbackJson(String rollbackJson) { this.rollbackJson = rollbackJson; }
    public String getClassification() { return classification; }
    public void setClassification(String classification) { this.classification = classification; }
    public String getOpsNote() { return opsNote; }
    public void setOpsNote(String opsNote) { this.opsNote = opsNote; }
    public String getBlocker() { return blocker; }
    public void setBlocker(String blocker) { this.blocker = blocker; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
