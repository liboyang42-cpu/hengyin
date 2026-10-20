package com.chengyinhub.business.marketplace.editor.store;

/** Internal allowlisted query row; never a response or request type. */
public final class InstalledDraftTargetRow {
    private long id;
    private long ownerMemberId;
    private long version;
    private String payloadHash;
    private String businessType;
    private String status;
    public long getId(){return id;}
    public void setId(long value){id=value;}
    public long getOwnerMemberId(){return ownerMemberId;}
    public void setOwnerMemberId(long value){ownerMemberId=value;}
    public long getVersion(){return version;}
    public void setVersion(long value){version=value;}
    public String getPayloadHash(){return payloadHash;}
    public void setPayloadHash(String value){payloadHash=value;}
    public String getBusinessType(){return businessType;}
    public void setBusinessType(String value){businessType=value;}
    public String getStatus(){return status;}
    public void setStatus(String value){status=value;}
}
