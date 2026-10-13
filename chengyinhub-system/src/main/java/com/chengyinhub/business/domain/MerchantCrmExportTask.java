package com.chengyinhub.business.domain;

import java.util.Date;

public class MerchantCrmExportTask {
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String EXPIRED = "EXPIRED";

    private Long id;
    private Long ownerMemberId;
    private Long merchantRowId;
    private Long requesterMemberId;
    private String requesterRoleCode;
    private String filterJson;
    private Integer includePaidAmount;
    private String status;
    private Integer version;
    private Integer rowLimit;
    private Integer rowCount;
    private String storageKey;
    private String tokenHash;
    private String requestId;
    private String errorMessage;
    private Date expiresAt;
    private Date startedAt;
    private Date finishedAt;
    private Date downloadedAt;
    private Integer downloadCount;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(Long value) { ownerMemberId = value; }
    public Long getMerchantRowId() { return merchantRowId; }
    public void setMerchantRowId(Long value) { merchantRowId = value; }
    public Long getRequesterMemberId() { return requesterMemberId; }
    public void setRequesterMemberId(Long value) { requesterMemberId = value; }
    public String getRequesterRoleCode() { return requesterRoleCode; }
    public void setRequesterRoleCode(String value) { requesterRoleCode = value; }
    public String getFilterJson() { return filterJson; }
    public void setFilterJson(String value) { filterJson = value; }
    public Integer getIncludePaidAmount() { return includePaidAmount; }
    public void setIncludePaidAmount(Integer value) { includePaidAmount = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { version = value; }
    public Integer getRowLimit() { return rowLimit; }
    public void setRowLimit(Integer value) { rowLimit = value; }
    public Integer getRowCount() { return rowCount; }
    public void setRowCount(Integer value) { rowCount = value; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String value) { storageKey = value; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String value) { tokenHash = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String value) { errorMessage = value; }
    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date value) { expiresAt = value; }
    public Date getStartedAt() { return startedAt; }
    public void setStartedAt(Date value) { startedAt = value; }
    public Date getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Date value) { finishedAt = value; }
    public Date getDownloadedAt() { return downloadedAt; }
    public void setDownloadedAt(Date value) { downloadedAt = value; }
    public Integer getDownloadCount() { return downloadCount; }
    public void setDownloadCount(Integer value) { downloadCount = value; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date value) { createTime = value; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date value) { updateTime = value; }
}
