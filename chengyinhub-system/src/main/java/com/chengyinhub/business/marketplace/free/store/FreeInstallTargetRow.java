package com.chengyinhub.business.marketplace.free.store;

/** Existing content_draft identity and its immutable, revision-specific installation scope. */
public final class FreeInstallTargetRow {
    private Long id, ownerMemberId, version;
    private String businessType, status, payloadHash, region;
    public Long getId(){return id;} public void setId(Long value){id=value;}
    public Long getOwnerMemberId(){return ownerMemberId;} public void setOwnerMemberId(Long value){ownerMemberId=value;}
    public Long getVersion(){return version;} public void setVersion(Long value){version=value;}
    public String getBusinessType(){return businessType;} public void setBusinessType(String value){businessType=value;}
    public String getStatus(){return status;} public void setStatus(String value){status=value;}
    public String getPayloadHash(){return payloadHash;} public void setPayloadHash(String value){payloadHash=value;}
    public String getRegion(){return region;} public void setRegion(String value){region=value;}
}
