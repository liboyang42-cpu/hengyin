package com.chengyinhub.business.marketplace.editor.store;

/** Internal allowlisted query row; never a response or request type. */
public final class InstalledDraftReceiptRow {
    private long installationId;
    private long targetDraftId;
    private long targetRevision;
    private java.sql.Timestamp installedAt;
    private String versionId;
    private String contentHash;
    private String termsHash;
    private String scopePayloadHash;
    public long getInstallationId(){return installationId;}
    public void setInstallationId(long value){installationId=value;}
    public long getTargetDraftId(){return targetDraftId;}
    public void setTargetDraftId(long value){targetDraftId=value;}
    public long getTargetRevision(){return targetRevision;}
    public void setTargetRevision(long value){targetRevision=value;}
    public java.sql.Timestamp getInstalledAt(){return installedAt;}
    public void setInstalledAt(java.sql.Timestamp value){installedAt=value;}
    public String getVersionId(){return versionId;}
    public void setVersionId(String value){versionId=value;}
    public String getContentHash(){return contentHash;}
    public void setContentHash(String value){contentHash=value;}
    public String getTermsHash(){return termsHash;}
    public void setTermsHash(String value){termsHash=value;}
    public String getScopePayloadHash(){return scopePayloadHash;}
    public void setScopePayloadHash(String value){scopePayloadHash=value;}
}
