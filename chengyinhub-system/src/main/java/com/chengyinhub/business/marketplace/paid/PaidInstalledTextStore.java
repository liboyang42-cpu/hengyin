package com.chengyinhub.business.marketplace.paid;
import org.apache.ibatis.annotations.Param;
import java.util.List;
/** NEW bounded owner-body reader; no writes and no ordinary CMS template access. */
public interface PaidInstalledTextStore {
    OwnedRow lockOwned(@Param("actor")long actor,@Param("owned")long owned,@Param("reservation")String reservation);
    List<ComponentRow> lockComponents(@Param("owned")long owned);
    final class OwnedRow {
        private Long id; public Long getId(){return id;} public void setId(Long value){id=value;}
        private Long buyerId; public Long getBuyerId(){return buyerId;} public void setBuyerId(Long value){buyerId=value;}
        private Long targetDraftId; public Long getTargetDraftId(){return targetDraftId;} public void setTargetDraftId(Long value){targetDraftId=value;}
        private Long targetRevision; public Long getTargetRevision(){return targetRevision;} public void setTargetRevision(Long value){targetRevision=value;}
        private Long revision; public Long getRevision(){return revision;} public void setRevision(Long value){revision=value;}
        private Long installedBy; public Long getInstalledBy(){return installedBy;} public void setInstalledBy(Long value){installedBy=value;}
        private Long usageOwnedDraftId; public Long getUsageOwnedDraftId(){return usageOwnedDraftId;} public void setUsageOwnedDraftId(Long value){usageOwnedDraftId=value;}
        private Long sourceLength; public Long getSourceLength(){return sourceLength;} public void setSourceLength(Long value){sourceLength=value;}
        private Long termsLength; public Long getTermsLength(){return termsLength;} public void setTermsLength(Long value){termsLength=value;}
        private Long rightsCharacterCount; public Long getRightsCharacterCount(){return rightsCharacterCount;} public void setRightsCharacterCount(Long value){rightsCharacterCount=value;}
        private String reservationKey; public String getReservationKey(){return reservationKey;} public void setReservationKey(String value){reservationKey=value;}
        private String buyerKind; public String getBuyerKind(){return buyerKind;} public void setBuyerKind(String value){buyerKind=value;}
        private String state; public String getState(){return state;} public void setState(String value){state=value;}
        private String rightsJson; public String getRightsJson(){return rightsJson;} public void setRightsJson(String value){rightsJson=value;}
        private String usageReservationKey; public String getUsageReservationKey(){return usageReservationKey;} public void setUsageReservationKey(String value){usageReservationKey=value;}
        private Integer reviewStatus; public Integer getReviewStatus(){return reviewStatus;} public void setReviewStatus(Integer value){reviewStatus=value;}
        private Integer publishStatus; public Integer getPublishStatus(){return publishStatus;} public void setPublishStatus(Integer value){publishStatus=value;}
        private Integer isSync; public Integer getIsSync(){return isSync;} public void setIsSync(Integer value){isSync=value;}
        private byte[] sourceContent; public byte[] getSourceContent(){return sourceContent;} public void setSourceContent(byte[] value){sourceContent=value;}
        private byte[] termsDocument; public byte[] getTermsDocument(){return termsDocument;} public void setTermsDocument(byte[] value){termsDocument=value;}
        private java.sql.Timestamp installedAt; public java.sql.Timestamp getInstalledAt(){return installedAt;} public void setInstalledAt(java.sql.Timestamp value){installedAt=value;}
        private java.sql.Timestamp committedAt; public java.sql.Timestamp getCommittedAt(){return committedAt;} public void setCommittedAt(java.sql.Timestamp value){committedAt=value;}
    }
    final class ComponentRow {
        private Long draftId; public Long getDraftId(){return draftId;} public void setDraftId(Long value){draftId=value;}
        private Long contentCharacterCount; public Long getContentCharacterCount(){return contentCharacterCount;} public void setContentCharacterCount(Long value){contentCharacterCount=value;}
        private String localKey; public String getLocalKey(){return localKey;} public void setLocalKey(String value){localKey=value;}
        private String componentKind; public String getComponentKind(){return componentKind;} public void setComponentKind(String value){componentKind=value;}
        private String contentJson; public String getContentJson(){return contentJson;} public void setContentJson(String value){contentJson=value;}
        private Integer schemaVersion; public Integer getSchemaVersion(){return schemaVersion;} public void setSchemaVersion(Integer value){schemaVersion=value;}
    }
}
