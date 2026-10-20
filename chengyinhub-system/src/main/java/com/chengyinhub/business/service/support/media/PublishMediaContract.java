package com.chengyinhub.business.service.support.media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Internal, non-HTTP contract for validating references to existing media IDs.
 * No type here is a persistence model, upload service, receipt issuer or permission grant.
 * Server evidence must never be populated by deserializing client metadata.
 */
public final class PublishMediaContract {
    private PublishMediaContract() { }

    public enum Purpose { COMMUNITY_POST, PROFESSIONAL_TOPIC }
    public enum Kind { IMAGE, VIDEO }
    public enum Processing { READY, PENDING, FAILED, UNAVAILABLE }
    public enum Review { PASS, PENDING, REJECTED, UNAVAILABLE }
    public enum Retention { ACTIVE, REVOKED, DELETED }
    public enum Status { VALIDATED, REJECTED, HOLD }
    public enum PolicyDecision { ACCEPT, REJECT, HOLD }
    public enum Role { PRIMARY, POSTER }
    public enum Reason {
        CONTRACT_VALIDATED_ONLY, INVALID_CONTEXT, INVALID_REFERENCE, DUPLICATE_REFERENCE,
        POLICY_MISSING, POLICY_MISMATCH, POLICY_UNAVAILABLE, POLICY_REJECTED, POLICY_HOLD,
        VIDEO_DISABLED, EVIDENCE_MISSING, EVIDENCE_UNAVAILABLE, EVIDENCE_REJECTED,
        BINDING_MISMATCH, RECEIPT_MISMATCH, RECEIPT_EXPIRED, INVALID_SERVER_METADATA,
        PROCESSING_NOT_READY, REVIEW_NOT_PASS, MEDIA_NOT_ACTIVE, POSTER_MISSING,
        POSTER_BINDING_MISMATCH, CLOCK_UNAVAILABLE, PURPOSE_NOT_SUPPORTED,
        LEGACY_ROW_MISSING, LEGACY_ROW_INVALID, LEGACY_ROW_MISMATCH,
        LOCATOR_MAPPING_MISSING, LOCATOR_MAPPING_MISMATCH,
        POLICY_VALIDITY_MISSING, POLICY_NOT_YET_VALID, POLICY_EXPIRED
    }

    /** Must be constructed from the authenticated principal and authorized draft context. */
    public static final class Context {
        public final long ownerMemberId;
        public final Purpose purpose;
        public final String draftId;

        public Context(long ownerMemberId, Purpose purpose, String draftId) {
            this.ownerMemberId = ownerMemberId;
            this.purpose = purpose;
            this.draftId = draftId;
        }
    }

    /** Explicit immutable server policy snapshot; UTC epoch milliseconds, exclusive expiry. */
    public static final class PolicyValidity {
        public final String version;
        public final long notBeforeMillis;
        public final long expiresAtMillis;

        public PolicyValidity(String version, long notBeforeMillis, long expiresAtMillis) {
            this.version = version;
            this.notBeforeMillis = notBeforeMillis;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    /** All accepted receipts and the policy were valid at this one server decision instant. */
    public static final class DecisionBoundary {
        public final long decisionAtMillis;
        public final long validUntilExclusiveMillis;
        public final String policyVersion;
        public final long policyNotBeforeMillis;
        public final long policyExpiresAtMillis;

        DecisionBoundary(long decisionAtMillis, long validUntilExclusiveMillis, PolicyValidity policy) {
            this.decisionAtMillis = decisionAtMillis;
            this.validUntilExclusiveMillis = validUntilExclusiveMillis;
            policyVersion = policy.version;
            policyNotBeforeMillis = policy.notBeforeMillis;
            policyExpiresAtMillis = policy.expiresAtMillis;
        }
    }

    /** The only untrusted media input: existing identity plus opaque receipt, never status/probe fields. */
    public static final class Reference {
        public final long mediaId;
        public final String operationId;
        public final String uploadReceipt;
        public final Kind declaredKind;

        public Reference(long mediaId, String operationId, String uploadReceipt, Kind declaredKind) {
            this.mediaId = mediaId;
            this.operationId = operationId;
            this.uploadReceipt = uploadReceipt;
            this.declaredKind = declaredKind;
        }
    }

    public static final class Binding {
        public final long ownerMemberId;
        public final Purpose purpose;
        public final String draftId;
        public final long mediaId;
        public final String operationId;

        public Binding(long ownerMemberId, Purpose purpose, String draftId, long mediaId, String operationId) {
            this.ownerMemberId = ownerMemberId;
            this.purpose = purpose;
            this.draftId = draftId;
            this.mediaId = mediaId;
            this.operationId = operationId;
        }
    }

    /**
     * Authoritative stored-byte probe plus current server processing/review/lifecycle snapshot.
     * The resolver must verify content digest and storage identity against those exact bytes.
     * Existing client-supplied community row metadata is not sufficient evidence.
     */
    public static final class AssetEvidence {
        public final Binding binding;
        public final Kind kind;
        public final String storageObjectKey;
        public final String contentSha256;
        public final String detectedMimeType;
        public final long byteSize;
        public final int width;
        public final int height;
        public final long durationMs;
        public final String container;
        public final String codec;
        public final boolean containsAudio;
        public final Processing processing;
        public final Review review;
        public final Retention retention;
        public final long snapshotVersion;

        public AssetEvidence(Binding binding, Kind kind, String storageObjectKey, String contentSha256,
                             String detectedMimeType, long byteSize, int width, int height, long durationMs,
                             String container, String codec, boolean containsAudio, Processing processing,
                             Review review, Retention retention, long snapshotVersion) {
            this.binding = binding;
            this.kind = kind;
            this.storageObjectKey = storageObjectKey;
            this.contentSha256 = contentSha256;
            this.detectedMimeType = detectedMimeType;
            this.byteSize = byteSize;
            this.width = width;
            this.height = height;
            this.durationMs = durationMs;
            this.container = container;
            this.codec = codec;
            this.containsAudio = containsAudio;
            this.processing = processing;
            this.review = review;
            this.retention = retention;
            this.snapshotVersion = snapshotVersion;
        }
    }

    /**
     * Claims of an already authenticated receipt, supplied only by the trusted resolver.
     * Receipt authentication, revocation and operation fencing remain the resolver's job.
     * The ce61 owner/key/size HMAC cannot produce this stronger proof.
     */
    public static final class ReceiptProof {
        public final Binding binding;
        public final String receiptSha256;
        public final Kind kind;
        public final String contentSha256;
        public final Binding posterBinding;
        public final String posterContentSha256;
        public final long expiresAtMillis;

        public ReceiptProof(Binding binding, String receiptSha256, Kind kind, String contentSha256,
                            Binding posterBinding, String posterContentSha256, long expiresAtMillis) {
            this.binding = binding;
            this.receiptSha256 = receiptSha256;
            this.kind = kind;
            this.contentSha256 = contentSha256;
            this.posterBinding = posterBinding;
            this.posterContentSha256 = posterContentSha256;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    public static final class PosterEvidence {
        public final long parentMediaId;
        public final AssetEvidence asset;

        public PosterEvidence(long parentMediaId, AssetEvidence asset) {
            this.parentMediaId = parentMediaId;
            this.asset = asset;
        }
    }

    /** Immutable, server-only evidence. Construction alone does not authenticate anything. */
    public static final class VerifiedMedia {
        public final AssetEvidence asset;
        public final ReceiptProof receipt;
        public final PosterEvidence poster;

        public VerifiedMedia(AssetEvidence asset, ReceiptProof receipt, PosterEvidence poster) {
            this.asset = asset;
            this.receipt = receipt;
            this.poster = poster;
        }
    }

    /** Read-only port; a production implementation must authenticate and resolve current server evidence. */
    public interface EvidenceResolver {
        Resolution verifyAndResolve(Context context, Reference reference);
    }

    public static final class Resolution {
        public final Status status;
        public final Reason reason;
        public final VerifiedMedia evidence;

        private Resolution(Status status, Reason reason, VerifiedMedia evidence) {
            this.status = status;
            this.reason = reason;
            this.evidence = evidence;
        }

        public static Resolution verified(VerifiedMedia evidence) {
            return new Resolution(Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY, evidence);
        }
        public static Resolution hold(Reason reason) {
            return new Resolution(Status.HOLD, reason, null);
        }
        public static Resolution reject(Reason reason) {
            return new Resolution(Status.REJECTED, reason, null);
        }
    }

    /** A short-lived validation snapshot, never a URL, bearer grant or permission to publish. */
    public static final class ValidatedReference {
        public final Binding binding;
        public final long mediaId;
        public final Kind kind;
        public final String contentSha256;
        public final long snapshotVersion;
        public final long receiptExpiresAtMillis;
        public final Long posterMediaId;
        public final Long posterSnapshotVersion;

        ValidatedReference(VerifiedMedia media) {
            binding = media.asset.binding;
            mediaId = media.asset.binding.mediaId;
            kind = media.asset.kind;
            contentSha256 = media.asset.contentSha256;
            snapshotVersion = media.asset.snapshotVersion;
            receiptExpiresAtMillis = media.receipt.expiresAtMillis;
            posterMediaId = media.poster == null ? null : media.poster.asset.binding.mediaId;
            posterSnapshotVersion = media.poster == null ? null : media.poster.asset.snapshotVersion;
        }
    }

    public static final class Result {
        public final Status status;
        public final Reason reason;
        public final int referenceIndex;
        public final List<ValidatedReference> references;
        public final DecisionBoundary decisionBoundary;

        private Result(Status status, Reason reason, int referenceIndex, List<ValidatedReference> references,
                       DecisionBoundary decisionBoundary) {
            this.status = status;
            this.reason = reason;
            this.referenceIndex = referenceIndex;
            this.references = Collections.unmodifiableList(new ArrayList<>(references));
            this.decisionBoundary = decisionBoundary;
        }
        static Result failure(Status status, Reason reason, int index) {
            return new Result(status, reason, index, Collections.<ValidatedReference>emptyList(), null);
        }
        static Result validated(List<VerifiedMedia> evidence, DecisionBoundary boundary) {
            List<ValidatedReference> refs = new ArrayList<>();
            for (VerifiedMedia media : evidence) refs.add(new ValidatedReference(media));
            return new Result(Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY, -1, refs, boundary);
        }
    }
}
