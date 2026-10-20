package com.chengyinhub.business.marketplace.paid;

/** NEW immutable sidecar write/read DTO. Not a license, HTTP input or publication approval.
 * Guard callers depend only on template_id existence, regardless of these fields' validity. */
public final class PaidProfessionalProvenanceRow {
    private Long templateId;public Long getTemplateId(){return templateId;}public void setTemplateId(Long v){templateId=v;}
    private String schemaVersion;public String getSchemaVersion(){return schemaVersion;}public void setSchemaVersion(String v){schemaVersion=v;}
    private Long ownerMemberId;public Long getOwnerMemberId(){return ownerMemberId;}public void setOwnerMemberId(Long v){ownerMemberId=v;}
    private String requestKey;public String getRequestKey(){return requestKey;}public void setRequestKey(String v){requestKey=v;}
    private String commandHash;public String getCommandHash(){return commandHash;}public void setCommandHash(String v){commandHash=v;}
    private String bindingKey;public String getBindingKey(){return bindingKey;}public void setBindingKey(String v){bindingKey=v;}
    private String sourceKind;public String getSourceKind(){return sourceKind;}public void setSourceKind(String v){sourceKind=v;}
    private Long ownedDraftId;public Long getOwnedDraftId(){return ownedDraftId;}public void setOwnedDraftId(Long v){ownedDraftId=v;}
    private Long ownedDraftRevision;public Long getOwnedDraftRevision(){return ownedDraftRevision;}public void setOwnedDraftRevision(Long v){ownedDraftRevision=v;}
    private String installationRequestId;public String getInstallationRequestId(){return installationRequestId;}public void setInstallationRequestId(String v){installationRequestId=v;}
    private String licenseId;public String getLicenseId(){return licenseId;}public void setLicenseId(String v){licenseId=v;}
    private String moduleId;public String getModuleId(){return moduleId;}public void setModuleId(String v){moduleId=v;}
    private String purchasedVersionId;public String getPurchasedVersionId(){return purchasedVersionId;}public void setPurchasedVersionId(String v){purchasedVersionId=v;}
    private String contentHash;public String getContentHash(){return contentHash;}public void setContentHash(String v){contentHash=v;}
    private String componentHash;public String getComponentHash(){return componentHash;}public void setComponentHash(String v){componentHash=v;}
    private String termsHash;public String getTermsHash(){return termsHash;}public void setTermsHash(String v){termsHash=v;}
    private Long originalTargetDraftId;public Long getOriginalTargetDraftId(){return originalTargetDraftId;}public void setOriginalTargetDraftId(Long v){originalTargetDraftId=v;}
    private Long originalTargetRevision;public Long getOriginalTargetRevision(){return originalTargetRevision;}public void setOriginalTargetRevision(Long v){originalTargetRevision=v;}
    private String originalTargetPayloadHash;public String getOriginalTargetPayloadHash(){return originalTargetPayloadHash;}public void setOriginalTargetPayloadHash(String v){originalTargetPayloadHash=v;}
    private Long confirmedDraftRevision;public Long getConfirmedDraftRevision(){return confirmedDraftRevision;}public void setConfirmedDraftRevision(Long v){confirmedDraftRevision=v;}
    private String confirmedDraftPayloadHash;public String getConfirmedDraftPayloadHash(){return confirmedDraftPayloadHash;}public void setConfirmedDraftPayloadHash(String v){confirmedDraftPayloadHash=v;}
    private Long targetTopicId;public Long getTargetTopicId(){return targetTopicId;}public void setTargetTopicId(Long v){targetTopicId=v;}
    private String confirmedMode;public String getConfirmedMode(){return confirmedMode;}public void setConfirmedMode(String v){confirmedMode=v;}
    private Long contentRoleId;public Long getContentRoleId(){return contentRoleId;}public void setContentRoleId(Long v){contentRoleId=v;}
    private Long routeConfigVersion;public Long getRouteConfigVersion(){return routeConfigVersion;}public void setRouteConfigVersion(Long v){routeConfigVersion=v;}
    private String ownerModeFingerprint;public String getOwnerModeFingerprint(){return ownerModeFingerprint;}public void setOwnerModeFingerprint(String v){ownerModeFingerprint=v;}
    private String templateContentHash;public String getTemplateContentHash(){return templateContentHash;}public void setTemplateContentHash(String v){templateContentHash=v;}
    private java.util.Date createdAt;public java.util.Date getCreatedAt(){return createdAt;}public void setCreatedAt(java.util.Date v){createdAt=v;}
    boolean sameOrigin(PaidProfessionalProvenanceRow other){return other!=null
            && java.util.Objects.equals(schemaVersion,other.schemaVersion)
            && java.util.Objects.equals(ownerMemberId,other.ownerMemberId)
            && java.util.Objects.equals(requestKey,other.requestKey)
            && java.util.Objects.equals(commandHash,other.commandHash)
            && java.util.Objects.equals(bindingKey,other.bindingKey)
            && java.util.Objects.equals(sourceKind,other.sourceKind)
            && java.util.Objects.equals(ownedDraftId,other.ownedDraftId)
            && java.util.Objects.equals(ownedDraftRevision,other.ownedDraftRevision)
            && java.util.Objects.equals(installationRequestId,other.installationRequestId)
            && java.util.Objects.equals(licenseId,other.licenseId)
            && java.util.Objects.equals(moduleId,other.moduleId)
            && java.util.Objects.equals(purchasedVersionId,other.purchasedVersionId)
            && java.util.Objects.equals(contentHash,other.contentHash)
            && java.util.Objects.equals(componentHash,other.componentHash)
            && java.util.Objects.equals(termsHash,other.termsHash)
            && java.util.Objects.equals(originalTargetDraftId,other.originalTargetDraftId)
            && java.util.Objects.equals(originalTargetRevision,other.originalTargetRevision)
            && java.util.Objects.equals(originalTargetPayloadHash,other.originalTargetPayloadHash)
            && java.util.Objects.equals(confirmedDraftRevision,other.confirmedDraftRevision)
            && java.util.Objects.equals(confirmedDraftPayloadHash,other.confirmedDraftPayloadHash)
            && java.util.Objects.equals(targetTopicId,other.targetTopicId)
            && java.util.Objects.equals(confirmedMode,other.confirmedMode)
            && java.util.Objects.equals(contentRoleId,other.contentRoleId)
            && java.util.Objects.equals(routeConfigVersion,other.routeConfigVersion)
            && java.util.Objects.equals(ownerModeFingerprint,other.ownerModeFingerprint);
    }
}
