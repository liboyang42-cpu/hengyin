package com.chengyinhub.business.marketplace.paid;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.marketplace.free.store.FreeInstallTargetRow;
import java.util.List;
/** NEW mapper, no component scan/bean registration. Queries always bind the authenticated owner. */
public interface PaidInstallCommandStore {
    List<Row> history(@Param("actor")long actor,@Param("license")String license,@Param("before")String before);
    Row find(@Param("actor")long actor,@Param("key")String key);
    Row lock(@Param("actor")long actor,@Param("key")String key);
    int insert(Row row);
    List<FreeInstallTargetRow> targets(@Param("actor")long actor,@Param("before")Long before);
    List<Progress> progress(@Param("actor")long actor,@Param("request")String request);
    final class Row {
        private String commandKey;public String getCommandKey(){return commandKey;}public void setCommandKey(String v){commandKey=v;}
        private String commandHash;public String getCommandHash(){return commandHash;}public void setCommandHash(String v){commandHash=v;}
        private long buyerMemberId;public long getBuyerMemberId(){return buyerMemberId;}public void setBuyerMemberId(long v){buyerMemberId=v;}
        private String requestId;public String getRequestId(){return requestId;}public void setRequestId(String v){requestId=v;}
        private String coreRequestId;public String getCoreRequestId(){return coreRequestId;}public void setCoreRequestId(String v){coreRequestId=v;}
        private String licenseId;public String getLicenseId(){return licenseId;}public void setLicenseId(String v){licenseId=v;}
        private String moduleId;public String getModuleId(){return moduleId;}public void setModuleId(String v){moduleId=v;}
        private String versionId;public String getVersionId(){return versionId;}public void setVersionId(String v){versionId=v;}
        private String contentHash;public String getContentHash(){return contentHash;}public void setContentHash(String v){contentHash=v;}
        private String termsHash;public String getTermsHash(){return termsHash;}public void setTermsHash(String v){termsHash=v;}
        private long targetDraftId;public long getTargetDraftId(){return targetDraftId;}public void setTargetDraftId(long v){targetDraftId=v;}
        private long targetRevision;public long getTargetRevision(){return targetRevision;}public void setTargetRevision(long v){targetRevision=v;}
        private String businessType;public String getBusinessType(){return businessType;}public void setBusinessType(String v){businessType=v;}
        private String targetPayloadHash;public String getTargetPayloadHash(){return targetPayloadHash;}public void setTargetPayloadHash(String v){targetPayloadHash=v;}
        private String planningRegion;public String getPlanningRegion(){return planningRegion;}public void setPlanningRegion(String v){planningRegion=v;}
        private boolean commercialUse;public boolean getCommercialUse(){return commercialUse;}public void setCommercialUse(boolean v){commercialUse=v;}
        private String offerVersion;public String getOfferVersion(){return offerVersion;}public void setOfferVersion(String v){offerVersion=v;}
        private java.sql.Timestamp requestedUntil;public java.sql.Timestamp getRequestedUntil(){return requestedUntil;}public void setRequestedUntil(java.sql.Timestamp v){requestedUntil=v;}
        private java.sql.Timestamp createdAt;public java.sql.Timestamp getCreatedAt(){return createdAt;}public void setCreatedAt(java.sql.Timestamp v){createdAt=v;}
        PaidInstallCommand command(){return new PaidInstallCommand(requestId,licenseId,moduleId,versionId,contentHash,termsHash,targetDraftId,targetRevision,businessType,targetPayloadHash,planningRegion,commercialUse);}
    }
    final class Progress {
        private Long usageDraftId;public Long getUsageDraftId(){return usageDraftId;}public void setUsageDraftId(Long v){usageDraftId=v;}
        private java.sql.Timestamp committedAt;public java.sql.Timestamp getCommittedAt(){return committedAt;}public void setCommittedAt(java.sql.Timestamp v){committedAt=v;}
        private String state;public String getState(){return state;}public void setState(String v){state=v;}
        private String licenseId;public String getLicenseId(){return licenseId;}public void setLicenseId(String v){licenseId=v;}
        private String moduleId;public String getModuleId(){return moduleId;}public void setModuleId(String v){moduleId=v;}
        private String versionId;public String getVersionId(){return versionId;}public void setVersionId(String v){versionId=v;}
        private String contentHash;public String getContentHash(){return contentHash;}public void setContentHash(String v){contentHash=v;}
        private String termsHash;public String getTermsHash(){return termsHash;}public void setTermsHash(String v){termsHash=v;}
        private String offerVersion;public String getOfferVersion(){return offerVersion;}public void setOfferVersion(String v){offerVersion=v;}
        private Long targetDraftId;public Long getTargetDraftId(){return targetDraftId;}public void setTargetDraftId(Long v){targetDraftId=v;}
        private Long targetRevision;public Long getTargetRevision(){return targetRevision;}public void setTargetRevision(Long v){targetRevision=v;}
        private Long actorId;public Long getActorId(){return actorId;}public void setActorId(Long v){actorId=v;}
        private java.sql.Timestamp expiresAt;public java.sql.Timestamp getExpiresAt(){return expiresAt;}public void setExpiresAt(java.sql.Timestamp v){expiresAt=v;}
        private Long ownedDraftId;public Long getOwnedDraftId(){return ownedDraftId;}public void setOwnedDraftId(Long v){ownedDraftId=v;}
        private java.sql.Timestamp installedAt;public java.sql.Timestamp getInstalledAt(){return installedAt;}public void setInstalledAt(java.sql.Timestamp v){installedAt=v;}
    }
}
