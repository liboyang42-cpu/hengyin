package com.chengyinhub.business.cityboard;

import java.util.Date;

public class CityBoardDraftRow {
    private Long id;
    private Long ownerUserId;
    private String regionId;
    private Long version;
    private Long expectedVersion;
    private String status;
    private String contentJson;
    private String contentHash;
    private String approvalHash;
    private Long approverUserId;
    private String approvalRequestId;
    private Long parentReleaseId;
    private Date createTime;
    private Date updateTime;

    public CityBoardDraftRow copy() {
        CityBoardDraftRow row = new CityBoardDraftRow();
        row.id = id;
        row.ownerUserId = ownerUserId;
        row.regionId = regionId;
        row.version = version;
        row.expectedVersion = expectedVersion;
        row.status = status;
        row.contentJson = contentJson;
        row.contentHash = contentHash;
        row.approvalHash = approvalHash;
        row.approverUserId = approverUserId;
        row.approvalRequestId = approvalRequestId;
        row.parentReleaseId = parentReleaseId;
        row.createTime = createTime;
        row.updateTime = updateTime;
        return row;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getRegionId() { return regionId; }
    public void setRegionId(String regionId) { this.regionId = regionId; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Long getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String contentJson) { this.contentJson = contentJson; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getApprovalHash() { return approvalHash; }
    public void setApprovalHash(String approvalHash) { this.approvalHash = approvalHash; }
    public Long getApproverUserId() { return approverUserId; }
    public void setApproverUserId(Long approverUserId) { this.approverUserId = approverUserId; }
    public String getApprovalRequestId() { return approvalRequestId; }
    public void setApprovalRequestId(String approvalRequestId) { this.approvalRequestId = approvalRequestId; }
    public Long getParentReleaseId() { return parentReleaseId; }
    public void setParentReleaseId(Long parentReleaseId) { this.parentReleaseId = parentReleaseId; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
