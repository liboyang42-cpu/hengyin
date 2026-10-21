package com.chengyinhub.business.domain.merchantauthoring;

/** Row shapes for the W05 authoring tables. MyBatis writes the fields directly. */
public final class MerchantAuthoringRows {
    private MerchantAuthoringRows() {}

    public static class Job {
        public Long id;
        public Long ownerId;
        public String requestId;
        public String payloadFingerprint;
        public Long merchantId;
        public Long themeId;
        public String draftId;
        public Long productId;
        public Long skuId;
        public String templateId;
        public String factVersion;
        public String promptVersion;
        public String modelVersion;
        public String costRef;
        public String outputHash;
        public String errorCode;
        public String status;
        public String scene;
        public String runId;
        public String bindingKey;
        public Integer expectedVersion;
        public Long createdAtMs;
        public Long updatedAtMs;
    }

    public static class Draft {
        public String id;
        public Long ownerId;
        public Long merchantId;
        public Long themeId;
        public Long productId;
        public Long skuId;
        public String templateId;
        public String scene;
        public String runId;
        public String bindingKey;
        public String factVersion;
        public Integer currentVersion;
        public Long createdAtMs;
    }

    public static class Revision {
        public Long id;
        public String draftId;
        public Integer version;
        public String bodyJson;
        public String bodyHash;
        public String factHash;
        public String contentHash;
        public Long createdBy;
        public Long createdAtMs;
    }

    public static class Approval {
        public Long id;
        public String draftId;
        public Long revisionId;
        public Integer revisionVersion;
        public String kind;
        public Long actorId;
        public String subjectHash;
        public Long auditTaskId;
        public String status;
        public Long createdAtMs;
    }

    public static class Release {
        public Long id;
        public Long ownerId;
        public String requestId;
        public String payloadFingerprint;
        public String draftId;
        public Long revisionId;
        public Integer revisionVersion;
        public Long merchantId;
        public Long themeId;
        public String runId;
        public String bindingKey;
        public String snapshotJson;
        public String snapshotHash;
        public String factHash;
        public String contentHash;
        public String status;
        public String visibility;
        public Long publishedAtMs;
        public Long withdrawnAtMs;
        public String withdrawRequestId;
    }

    public static class Environment {
        public Long id;
        public Long releaseId;
        public String provider;
        public String source;
        public Long observedAtMs;
        public Long expiresAtMs;
        public String timezone;
        public String accuracy;
        public String branch;
        public String weather;
        public String action;
        public String storeHours;
        public Long createdAtMs;
    }
}
