package com.chengyinhub.business.marketplace.free.store;

/** Internal persistence row; never an HTTP request or response. */
public final class FreeClaimRow {
    private String claimId;
    private Long buyerMemberId;
    private String requestKey;
    private String publicationId;
    private String documentHash;
    private String state;
    private java.sql.Timestamp createdAt;
    public String getClaimId() { return claimId; }
    public void setClaimId(String value) { claimId = value; }
    public Long getBuyerMemberId() { return buyerMemberId; }
    public void setBuyerMemberId(Long value) { buyerMemberId = value; }
    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String value) { requestKey = value; }
    public String getPublicationId() { return publicationId; }
    public void setPublicationId(String value) { publicationId = value; }
    public String getDocumentHash() { return documentHash; }
    public void setDocumentHash(String value) { documentHash = value; }
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    public java.sql.Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.sql.Timestamp value) { createdAt = value; }
    private java.sql.Timestamp validUntil;
    public java.sql.Timestamp getValidUntil() { return validUntil; }
    public void setValidUntil(java.sql.Timestamp value) { validUntil=value; }
}
