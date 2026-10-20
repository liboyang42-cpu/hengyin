package com.chengyinhub.business.marketplace.reservation.store;

/** Internal persistence row; never deserialize from requests or expose as market content. */
public final class ModuleReservationRow {
    private String reservationKey;
    private String buyerKind;
    private long buyerId;
    private String requestId;
    private String licenseKey;
    private String licenseId;
    private String payloadHash;
    private String authorityHash;
    private String moduleId;
    private String versionId;
    private String contentHash;
    private String offerVersion;
    private String termsHash;
    private String sellerKind;
    private long sellerId;
    private String sourceEventId;
    private String grantEvidenceReference;
    private String approvalReference;
    private long draftId;
    private long draftRevision;
    private long actorId;
    private String state;
    private java.sql.Timestamp reservedAt;
    private java.sql.Timestamp expiresAt;
    private java.sql.Timestamp terminalAt;
    public String getReservationKey() { return reservationKey; }
    public void setReservationKey(String value) { this.reservationKey = value; }
    public String getBuyerKind() { return buyerKind; }
    public void setBuyerKind(String value) { this.buyerKind = value; }
    public long getBuyerId() { return buyerId; }
    public void setBuyerId(long value) { this.buyerId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { this.requestId = value; }
    public String getLicenseKey() { return licenseKey; }
    public void setLicenseKey(String value) { this.licenseKey = value; }
    public String getLicenseId() { return licenseId; }
    public void setLicenseId(String value) { this.licenseId = value; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String value) { this.payloadHash = value; }
    public String getAuthorityHash() { return authorityHash; }
    public void setAuthorityHash(String value) { this.authorityHash = value; }
    public String getModuleId() { return moduleId; }
    public void setModuleId(String value) { this.moduleId = value; }
    public String getVersionId() { return versionId; }
    public void setVersionId(String value) { this.versionId = value; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String value) { this.contentHash = value; }
    public String getOfferVersion() { return offerVersion; }
    public void setOfferVersion(String value) { this.offerVersion = value; }
    public String getTermsHash() { return termsHash; }
    public void setTermsHash(String value) { this.termsHash = value; }
    public String getSellerKind() { return sellerKind; }
    public void setSellerKind(String value) { this.sellerKind = value; }
    public long getSellerId() { return sellerId; }
    public void setSellerId(long value) { this.sellerId = value; }
    public String getSourceEventId() { return sourceEventId; }
    public void setSourceEventId(String value) { this.sourceEventId = value; }
    public String getGrantEvidenceReference() { return grantEvidenceReference; }
    public void setGrantEvidenceReference(String value) { this.grantEvidenceReference = value; }
    public String getApprovalReference() { return approvalReference; }
    public void setApprovalReference(String value) { this.approvalReference = value; }
    public long getDraftId() { return draftId; }
    public void setDraftId(long value) { this.draftId = value; }
    public long getDraftRevision() { return draftRevision; }
    public void setDraftRevision(long value) { this.draftRevision = value; }
    public long getActorId() { return actorId; }
    public void setActorId(long value) { this.actorId = value; }
    public String getState() { return state; }
    public void setState(String value) { this.state = value; }
    public java.sql.Timestamp getReservedAt() { return reservedAt; }
    public void setReservedAt(java.sql.Timestamp value) { this.reservedAt = value; }
    public java.sql.Timestamp getExpiresAt() { return expiresAt; }
    public void setExpiresAt(java.sql.Timestamp value) { this.expiresAt = value; }
    public java.sql.Timestamp getTerminalAt() { return terminalAt; }
    public void setTerminalAt(java.sql.Timestamp value) { this.terminalAt = value; }
}
