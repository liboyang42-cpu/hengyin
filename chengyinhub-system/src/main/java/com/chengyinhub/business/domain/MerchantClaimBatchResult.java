package com.chengyinhub.business.domain;

public class MerchantClaimBatchResult {
    private Long id;
    private Long batchCommandId;
    private Long claimId;
    private boolean success;
    private String code;
    private String message;
    private String currentStatus;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBatchCommandId() { return batchCommandId; }
    public void setBatchCommandId(Long batchCommandId) { this.batchCommandId = batchCommandId; }
    public Long getClaimId() { return claimId; }
    public void setClaimId(Long claimId) { this.claimId = claimId; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getCurrentStatus() { return currentStatus; }
    public void setCurrentStatus(String currentStatus) { this.currentStatus = currentStatus; }
}
