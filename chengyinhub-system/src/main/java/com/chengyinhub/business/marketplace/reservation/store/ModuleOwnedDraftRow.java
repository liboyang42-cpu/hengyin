package com.chengyinhub.business.marketplace.reservation.store;

/** Internal SQL write/read carrier; never a request or public projection. */
public final class ModuleOwnedDraftRow {
    private long installedBy;
    private Long id;
    private String reservationKey;
    private String buyerKind;
    private long buyerId;
    private long targetDraftId;
    private long targetRevision;
    private byte[] sourceContent;
    private byte[] termsDocument;
    private String rightsJson;
    private String contentJson;
    private java.sql.Timestamp installedAt;
    public long getInstalledBy() { return installedBy; }
    public void setInstalledBy(long value) { installedBy = value; }
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getReservationKey() { return reservationKey; }
    public void setReservationKey(String value) { reservationKey = value; }
    public String getBuyerKind() { return buyerKind; }
    public void setBuyerKind(String value) { buyerKind = value; }
    public long getBuyerId() { return buyerId; }
    public void setBuyerId(long value) { buyerId = value; }
    public long getTargetDraftId() { return targetDraftId; }
    public void setTargetDraftId(long value) { targetDraftId = value; }
    public long getTargetRevision() { return targetRevision; }
    public void setTargetRevision(long value) { targetRevision = value; }
    public byte[] getSourceContent() { return sourceContent; }
    public void setSourceContent(byte[] value) { sourceContent = value; }
    public byte[] getTermsDocument() { return termsDocument; }
    public void setTermsDocument(byte[] value) { termsDocument = value; }
    public String getRightsJson() { return rightsJson; }
    public void setRightsJson(String value) { rightsJson = value; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String value) { contentJson = value; }
    public java.sql.Timestamp getInstalledAt() { return installedAt; }
    public void setInstalledAt(java.sql.Timestamp value) { installedAt = value; }
}
