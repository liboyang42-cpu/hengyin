package com.chengyinhub.business.domain;

/** Immutable persisted authoring evidence; only INSERT is exposed by the mapper. */
public class MerchantAuthoringCandidate {
    private Long id;
    private Long principalMemberId;
    private Long merchantRowId;
    private Long ownerMemberId;
    private String factsJson;
    private String factsHash;
    private String constraintsJson;
    private String constraintsHash;
    private String aiTraceId;
    private String templateJson;
    private java.util.Date createdAt;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getPrincipalMemberId() { return principalMemberId; }
    public void setPrincipalMemberId(Long value) { principalMemberId = value; }
    public Long getMerchantRowId() { return merchantRowId; }
    public void setMerchantRowId(Long value) { merchantRowId = value; }
    public Long getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(Long value) { ownerMemberId = value; }
    public String getFactsJson() { return factsJson; }
    public void setFactsJson(String value) { factsJson = value; }
    public String getFactsHash() { return factsHash; }
    public void setFactsHash(String value) { factsHash = value; }
    public String getConstraintsJson() { return constraintsJson; }
    public void setConstraintsJson(String value) { constraintsJson = value; }
    public String getConstraintsHash() { return constraintsHash; }
    public void setConstraintsHash(String value) { constraintsHash = value; }
    public String getAiTraceId() { return aiTraceId; }
    public void setAiTraceId(String value) { aiTraceId = value; }
    public String getTemplateJson() { return templateJson; }
    public void setTemplateJson(String value) { templateJson = value; }
    public java.util.Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.util.Date value) { createdAt = value; }
}
