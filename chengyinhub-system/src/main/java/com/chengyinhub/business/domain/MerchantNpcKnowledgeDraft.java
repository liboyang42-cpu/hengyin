package com.chengyinhub.business.domain;

/** Persistence row for unapproved merchant-authored public facts. Never a runtime source. */
public class MerchantNpcKnowledgeDraft {
    private Long id;
    private Long merchantId;
    private Long ownerMemberId;
    private Long version;
    private String requestId;
    private String contentJson;
    private String contentHash;
    private Long auditTaskId;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long value) { merchantId = value; }
    public Long getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(Long value) { ownerMemberId = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long value) { version = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String value) { contentJson = value; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String value) { contentHash = value; }
    public Long getAuditTaskId() { return auditTaskId; }
    public void setAuditTaskId(Long value) { auditTaskId = value; }
}
