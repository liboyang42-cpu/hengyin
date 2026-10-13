package com.chengyinhub.business.domain.dto;

import java.util.Date;
import java.util.List;

/** 广场帖文的动作型 API 入参；不同命令不复用整行数据库对象。 */
public final class CommunityPostRequests {
    private CommunityPostRequests() {}

    public static class Upsert {
        private String clientRequestId;
        private Integer expectedVersion;
        private Long communityId;
        private String postType;
        private String title;
        private String body;
        private String audience;
        private String commentPolicy;
        private Integer replyApprovalEnabled;
        private Integer slowModeSeconds;
        private String locationPrecision;
        private String cityCode;
        private String poiName;
        private String approximateGeohash;
        private String disclosureType;
        private List<String> safetyLabels;
        private Long guidelineVersionId;
        private List<Long> mediaIds;
        private List<String> topicCodes;
        private List<Long> mentionedMemberIds;
        private List<Reference> references;
        public String getClientRequestId() { return clientRequestId; }
        public void setClientRequestId(String v) { clientRequestId = v; }
        public Integer getExpectedVersion() { return expectedVersion; }
        public void setExpectedVersion(Integer v) { expectedVersion = v; }
        public Long getCommunityId() { return communityId; }
        public void setCommunityId(Long v) { communityId = v; }
        public String getPostType() { return postType; }
        public void setPostType(String v) { postType = v; }
        public String getTitle() { return title; }
        public void setTitle(String v) { title = v; }
        public String getBody() { return body; }
        public void setBody(String v) { body = v; }
        public String getAudience() { return audience; }
        public void setAudience(String v) { audience = v; }
        public String getCommentPolicy() { return commentPolicy; }
        public void setCommentPolicy(String v) { commentPolicy = v; }
        public Integer getReplyApprovalEnabled() { return replyApprovalEnabled; }
        public void setReplyApprovalEnabled(Integer v) { replyApprovalEnabled = v; }
        public Integer getSlowModeSeconds() { return slowModeSeconds; }
        public void setSlowModeSeconds(Integer v) { slowModeSeconds = v; }
        public String getLocationPrecision() { return locationPrecision; }
        public void setLocationPrecision(String v) { locationPrecision = v; }
        public String getCityCode() { return cityCode; }
        public void setCityCode(String v) { cityCode = v; }
        public String getPoiName() { return poiName; }
        public void setPoiName(String v) { poiName = v; }
        public String getApproximateGeohash() { return approximateGeohash; }
        public void setApproximateGeohash(String v) { approximateGeohash = v; }
        public String getDisclosureType() { return disclosureType; }
        public void setDisclosureType(String v) { disclosureType = v; }
        public List<String> getSafetyLabels() { return safetyLabels; }
        public void setSafetyLabels(List<String> v) { safetyLabels = v; }
        public Long getGuidelineVersionId() { return guidelineVersionId; }
        public void setGuidelineVersionId(Long v) { guidelineVersionId = v; }
        public List<Long> getMediaIds() { return mediaIds; }
        public void setMediaIds(List<Long> v) { mediaIds = v; }
        public List<String> getTopicCodes() { return topicCodes; }
        public void setTopicCodes(List<String> v) { topicCodes = v; }
        public List<Long> getMentionedMemberIds() { return mentionedMemberIds; }
        public void setMentionedMemberIds(List<Long> v) { mentionedMemberIds = v; }
        public List<Reference> getReferences() { return references; }
        public void setReferences(List<Reference> v) { references = v; }
    }

    public static class Reference {
        private String referenceType;
        private Long referenceId;
        private String snapshotJson;
        private String privacySnapshot;
        public String getReferenceType() { return referenceType; }
        public void setReferenceType(String v) { referenceType = v; }
        public Long getReferenceId() { return referenceId; }
        public void setReferenceId(Long v) { referenceId = v; }
        public String getSnapshotJson() { return snapshotJson; }
        public void setSnapshotJson(String v) { snapshotJson = v; }
        public String getPrivacySnapshot() { return privacySnapshot; }
        public void setPrivacySnapshot(String v) { privacySnapshot = v; }
    }

    public static class State {
        private Integer expectedVersion;
        private String requestId;
        public Integer getExpectedVersion() { return expectedVersion; }
        public void setExpectedVersion(Integer v) { expectedVersion = v; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String v) { requestId = v; }
    }

    public static class Action {
        private String actionType;
        private String requestId;
        private String source;
        public String getActionType() { return actionType; }
        public void setActionType(String v) { actionType = v; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String v) { requestId = v; }
        public String getSource() { return source; }
        public void setSource(String v) { source = v; }
    }

    public static class Comment {
        private String clientRequestId;
        private String body;
        private Long rootId;
        private Long parentId;
        private Long repliedToMemberId;
        public String getClientRequestId() { return clientRequestId; }
        public void setClientRequestId(String v) { clientRequestId = v; }
        public String getBody() { return body; }
        public void setBody(String v) { body = v; }
        public Long getRootId() { return rootId; }
        public void setRootId(Long v) { rootId = v; }
        public Long getParentId() { return parentId; }
        public void setParentId(Long v) { parentId = v; }
        public Long getRepliedToMemberId() { return repliedToMemberId; }
        public void setRepliedToMemberId(Long v) { repliedToMemberId = v; }
    }

    public static class GuidelineAck {
        private Long guidelineVersionId;
        private String requestId;
        private String scene;
        public Long getGuidelineVersionId() { return guidelineVersionId; }
        public void setGuidelineVersionId(Long v) { guidelineVersionId = v; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String v) { requestId = v; }
        public String getScene() { return scene; }
        public void setScene(String v) { scene = v; }
    }

    public static class RequestId {
        private String requestId;
        public String getRequestId() { return requestId; }
        public void setRequestId(String v) { requestId = v; }
    }

    public static class NotificationPreferences {
        private Boolean interactionEnabled;
        private Boolean mentionEnabled;
        private Boolean socialEnabled;
        public Boolean getInteractionEnabled() { return interactionEnabled; }
        public void setInteractionEnabled(Boolean v) { interactionEnabled = v; }
        public Boolean getMentionEnabled() { return mentionEnabled; }
        public void setMentionEnabled(Boolean v) { mentionEnabled = v; }
        public Boolean getSocialEnabled() { return socialEnabled; }
        public void setSocialEnabled(Boolean v) { socialEnabled = v; }
    }

    public static class MediaRegister {
        private String uploadRequestId;
        private String mediaType;
        private String objectKey;
        private String uploadReceipt;
        private String mimeType;
        private Long byteSize;
        private Integer width;
        private Integer height;
        private Integer durationMs;
        private String altText;
        public String getUploadRequestId() { return uploadRequestId; }
        public void setUploadRequestId(String v) { uploadRequestId = v; }
        public String getMediaType() { return mediaType; }
        public void setMediaType(String v) { mediaType = v; }
        public String getObjectKey() { return objectKey; }
        public void setObjectKey(String v) { objectKey = v; }
        public String getUploadReceipt() { return uploadReceipt; }
        public void setUploadReceipt(String v) { uploadReceipt = v; }
        public String getMimeType() { return mimeType; }
        public void setMimeType(String v) { mimeType = v; }
        public Long getByteSize() { return byteSize; }
        public void setByteSize(Long v) { byteSize = v; }
        public Integer getWidth() { return width; }
        public void setWidth(Integer v) { width = v; }
        public Integer getHeight() { return height; }
        public void setHeight(Integer v) { height = v; }
        public Integer getDurationMs() { return durationMs; }
        public void setDurationMs(Integer v) { durationMs = v; }
        public String getAltText() { return altText; }
        public void setAltText(String v) { altText = v; }
    }

    public static class Appeal {
        private Long enforcementId;
        private String reason;
        private List<Long> evidenceAssetIds;
        private String requestId;
        public Long getEnforcementId() { return enforcementId; }
        public void setEnforcementId(Long v) { enforcementId = v; }
        public String getReason() { return reason; }
        public void setReason(String v) { reason = v; }
        public List<Long> getEvidenceAssetIds() { return evidenceAssetIds; }
        public void setEvidenceAssetIds(List<Long> v) { evidenceAssetIds = v; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String v) { requestId = v; }
    }

    public static class Featured {
        private String requestId;
        private String collectionCode;
        private String cityCode;
        private String reason;
        private Date startsAt;
        private Date endsAt;
        public String getRequestId() { return requestId; }
        public void setRequestId(String v) { requestId = v; }
        public String getCollectionCode() { return collectionCode; }
        public void setCollectionCode(String v) { collectionCode = v; }
        public String getCityCode() { return cityCode; }
        public void setCityCode(String v) { cityCode = v; }
        public String getReason() { return reason; }
        public void setReason(String v) { reason = v; }
        public Date getStartsAt() { return startsAt; }
        public void setStartsAt(Date v) { startsAt = v; }
        public Date getEndsAt() { return endsAt; }
        public void setEndsAt(Date v) { endsAt = v; }
    }

    public static class Enforcement {
        private Long targetMemberId;
        private String enforcementType;
        private String ruleCode;
        private Long guidelineVersionId;
        private String reasonCode;
        private String evidenceJson;
        private Date startsAt;
        private Date endsAt;
        private String requestId;
        public Long getTargetMemberId() { return targetMemberId; }
        public void setTargetMemberId(Long v) { targetMemberId = v; }
        public String getEnforcementType() { return enforcementType; }
        public void setEnforcementType(String v) { enforcementType = v; }
        public String getRuleCode() { return ruleCode; }
        public void setRuleCode(String v) { ruleCode = v; }
        public Long getGuidelineVersionId() { return guidelineVersionId; }
        public void setGuidelineVersionId(Long v) { guidelineVersionId = v; }
        public String getReasonCode() { return reasonCode; }
        public void setReasonCode(String v) { reasonCode = v; }
        public String getEvidenceJson() { return evidenceJson; }
        public void setEvidenceJson(String v) { evidenceJson = v; }
        public Date getStartsAt() { return startsAt; }
        public void setStartsAt(Date v) { startsAt = v; }
        public Date getEndsAt() { return endsAt; }
        public void setEndsAt(Date v) { endsAt = v; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String v) { requestId = v; }
    }

    public static class AppealReview {
        private String decision;
        private String reason;
        private Integer expectedVersion;
        public String getDecision() { return decision; }
        public void setDecision(String v) { decision = v; }
        public String getReason() { return reason; }
        public void setReason(String v) { reason = v; }
        public Integer getExpectedVersion() { return expectedVersion; }
        public void setExpectedVersion(Integer v) { expectedVersion = v; }
    }
}
