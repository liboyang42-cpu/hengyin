package com.chengyinhub.business.domain;

/** Immutable persisted authoring evidence; only INSERT is exposed by the mapper. */
public class MerchantAuthoringSource {
    private Long id;
    private Long candidateId;
    private Long ownerMemberId;
    private Long merchantRowId;
    private Long actualTemplateId;
    private String templateSnapshotJson;
    private String templateContentHash;
    private String confirmationHash;
    private String contentHash;
    private String sourceJson;
    private java.util.Date createdAt;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getCandidateId() { return candidateId; }
    public void setCandidateId(Long value) { candidateId = value; }
    public Long getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(Long value) { ownerMemberId = value; }
    public Long getMerchantRowId() { return merchantRowId; }
    public void setMerchantRowId(Long value) { merchantRowId = value; }
    public Long getActualTemplateId() { return actualTemplateId; }
    public void setActualTemplateId(Long value) { actualTemplateId = value; }
    public String getTemplateSnapshotJson() { return templateSnapshotJson; }
    public void setTemplateSnapshotJson(String value) { templateSnapshotJson = value; }
    public String getTemplateContentHash() { return templateContentHash; }
    public void setTemplateContentHash(String value) { templateContentHash = value; }
    public String getConfirmationHash() { return confirmationHash; }
    public void setConfirmationHash(String value) { confirmationHash = value; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String value) { contentHash = value; }
    public String getSourceJson() { return sourceJson; }
    public void setSourceJson(String value) { sourceJson = value; }
    public java.util.Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.util.Date value) { createdAt = value; }
}
