package com.chengyinhub.business.marketplace.free.store;

/** Internal persistence row; never an HTTP request or response. */
public final class FreePublicationRow {
    private String ownershipKey;
    public String getOwnershipKey() { return ownershipKey; }
    public void setOwnershipKey(String value) { ownershipKey=value; }
    private String publicationId;
    private Long sellerMemberId;
    private Long sourceTemplateId;
    private String documentHash;
    private String snapshotJson;
    private byte[] content;
    private byte[] terms;
    private String state;
    public String getPublicationId() { return publicationId; }
    public void setPublicationId(String value) { publicationId = value; }
    public Long getSellerMemberId() { return sellerMemberId; }
    public void setSellerMemberId(Long value) { sellerMemberId = value; }
    public Long getSourceTemplateId() { return sourceTemplateId; }
    public void setSourceTemplateId(Long value) { sourceTemplateId = value; }
    public String getDocumentHash() { return documentHash; }
    public void setDocumentHash(String value) { documentHash = value; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String value) { snapshotJson = value; }
    public byte[] getContent() { return content; }
    public void setContent(byte[] value) { content = value; }
    public byte[] getTerms() { return terms; }
    public void setTerms(byte[] value) { terms = value; }
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    private java.sql.Timestamp grantValidUntil;
    public java.sql.Timestamp getGrantValidUntil() { return grantValidUntil; }
    public void setGrantValidUntil(java.sql.Timestamp value) { grantValidUntil=value; }
}
