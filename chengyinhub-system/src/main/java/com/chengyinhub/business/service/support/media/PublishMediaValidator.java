package com.chengyinhub.business.service.support.media;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.chengyinhub.business.service.support.media.PublishMediaContract.*;

/** Pure validation only. It cannot upload, attach, publish, issue receipts or grant read access. */
public final class PublishMediaValidator {
    private final PublishMediaPolicy.Provider policies;
    private final EvidenceResolver resolver;
    private final Clock clock;

    /** clock must be a trusted server clock, never a device/client-supplied time source. */
    public PublishMediaValidator(PublishMediaPolicy.Provider policies, EvidenceResolver resolver, Clock clock) {
        this.policies = policies;
        this.resolver = resolver;
        this.clock = clock;
    }

    public Result validate(Context context, List<Reference> references) {
        if (!validContext(context)) return reject(Reason.INVALID_CONTEXT, -1);
        if (references == null) return reject(Reason.INVALID_REFERENCE, -1);
        // Snapshot the caller's order; no reorder, deduplication or partial acceptance.
        List<Reference> requested = new ArrayList<>(references);
        PublishMediaPolicy policy;
        PolicyValidity policyValidity;
        boolean videoEnabled;
        try {
            policy = policies == null ? null : policies.forPurpose(context.purpose);
            if (policy == null) return hold(Reason.POLICY_MISSING, -1);
            if (policy.purpose() != context.purpose) return hold(Reason.POLICY_MISMATCH, -1);
            policyValidity = policy.validity();
            if (policyValidity == null || !identifier(policyValidity.version)
                    || policyValidity.notBeforeMillis >= policyValidity.expiresAtMillis) {
                return hold(Reason.POLICY_VALIDITY_MISSING, -1);
            }
            videoEnabled = policy.videoEnabled();
        } catch (RuntimeException e) {
            return hold(Reason.POLICY_UNAVAILABLE, -1);
        }
        Set<Long> ids = new HashSet<>();
        Set<String> operations = new HashSet<>();
        Set<String> receipts = new HashSet<>();
        for (int i = 0; i < requested.size(); i++) {
            Reference ref = requested.get(i);
            if (!validReference(ref)) return reject(Reason.INVALID_REFERENCE, i);
            if (!ids.add(ref.mediaId) || !operations.add(ref.operationId)
                    || !receipts.add(sha256(ref.uploadReceipt))) {
                return reject(Reason.DUPLICATE_REFERENCE, i);
            }
            if (ref.declaredKind == Kind.VIDEO && !videoEnabled) return hold(Reason.VIDEO_DISABLED, i);
        }
        if (resolver == null) return hold(Reason.EVIDENCE_MISSING, -1);
        if (clock == null) return hold(Reason.CLOCK_UNAVAILABLE, -1);
        try {
            Result failure = checkPolicyValidity(policyValidity, clock.millis());
            if (failure != null) return failure;
        } catch (RuntimeException e) {
            return hold(Reason.CLOCK_UNAVAILABLE, -1);
        }
        List<VerifiedMedia> verified = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            Reference ref = requested.get(i);
            Resolution resolution;
            try {
                resolution = resolver.verifyAndResolve(context, ref);
            } catch (RuntimeException e) {
                return hold(Reason.EVIDENCE_UNAVAILABLE, i);
            }
            if (resolution == null) return hold(Reason.EVIDENCE_MISSING, i);
            if (resolution.status != Status.VALIDATED) {
                Reason reason = resolution.reason == null ? Reason.EVIDENCE_UNAVAILABLE : resolution.reason;
                return Result.failure(resolution.status, reason, i);
            }
            VerifiedMedia media = resolution.evidence;
            if (media == null || media.asset == null || media.receipt == null) {
                return hold(Reason.EVIDENCE_MISSING, i);
            }
            AssetEvidence asset = media.asset;
            ReceiptProof receipt = media.receipt;
            if (!matches(context, ref, asset.binding) || !matches(context, ref, receipt.binding)
                    || asset.kind != ref.declaredKind || receipt.kind != asset.kind) {
                return reject(Reason.BINDING_MISMATCH, i);
            }
            if (asset.kind == Kind.VIDEO && !videoEnabled) return hold(Reason.VIDEO_DISABLED, i);
            if (!validDigest(receipt.receiptSha256) || !validDigest(receipt.contentSha256)
                    || !sha256(ref.uploadReceipt).equals(receipt.receiptSha256)
                    || !receipt.contentSha256.equals(asset.contentSha256)) {
                return reject(Reason.RECEIPT_MISMATCH, i);
            }
            // Exact expiry is expired. The resolver must independently enforce revocation/fencing.
            Result expiryFailure = checkExpiry(receipt, i);
            if (expiryFailure != null) return expiryFailure;
            Result failure = validateAsset(asset, i);
            if (failure != null) return failure;
            if (asset.kind == Kind.VIDEO) {
                if (media.poster == null || media.poster.asset == null) return hold(Reason.POSTER_MISSING, i);
                AssetEvidence poster = media.poster.asset;
                if (media.poster.parentMediaId != asset.binding.mediaId
                        || !sameContext(context, poster.binding)
                        || !validBinding(poster.binding)
                        || !sameBinding(poster.binding, receipt.posterBinding)
                        || poster.binding.mediaId == asset.binding.mediaId
                        || poster.kind != Kind.IMAGE
                        || !validDigest(receipt.posterContentSha256)
                        || !receipt.posterContentSha256.equals(poster.contentSha256)) {
                    return reject(Reason.POSTER_BINDING_MISMATCH, i);
                }
                failure = validateAsset(poster, i);
                if (failure != null) return failure;
                failure = applyAssetPolicy(policy, poster, Role.POSTER, i);
                if (failure != null) return failure;
            } else if (media.poster != null || receipt.posterBinding != null || receipt.posterContentSha256 != null) {
                return reject(Reason.POSTER_BINDING_MISMATCH, i);
            }
            failure = applyAssetPolicy(policy, asset, Role.PRIMARY, i);
            if (failure != null) return failure;
            verified.add(media);
        }
        try {
            Result failure = policyResult(policy.validateSelection(Collections.unmodifiableList(verified)), -1);
            if (failure != null) return failure;
        } catch (RuntimeException e) {
            return hold(Reason.POLICY_UNAVAILABLE, -1);
        }
        // One decision instant for the entire batch. Never sample a different time per receipt.
        final long decisionAtMillis;
        try {
            decisionAtMillis = clock.millis();
        } catch (RuntimeException e) {
            return hold(Reason.CLOCK_UNAVAILABLE, -1);
        }
        long validUntilExclusiveMillis = policyValidity.expiresAtMillis;
        for (int i = 0; i < verified.size(); i++) {
            long expiry = verified.get(i).receipt.expiresAtMillis;
            if (expiry <= decisionAtMillis) return reject(Reason.RECEIPT_EXPIRED, i);
            validUntilExclusiveMillis = Math.min(validUntilExclusiveMillis, expiry);
        }
        Result policyFailure = checkPolicyValidity(policyValidity, decisionAtMillis);
        if (policyFailure != null) return policyFailure;
        return Result.validated(verified, new DecisionBoundary(decisionAtMillis, validUntilExclusiveMillis, policyValidity));
    }

    private Result checkPolicyValidity(PolicyValidity validity, long atMillis) {
        if (atMillis < validity.notBeforeMillis) return hold(Reason.POLICY_NOT_YET_VALID, -1);
        if (atMillis >= validity.expiresAtMillis) return hold(Reason.POLICY_EXPIRED, -1);
        return null;
    }

    private Result checkExpiry(ReceiptProof receipt, int index) {
        try {
            return receipt.expiresAtMillis <= clock.millis() ? reject(Reason.RECEIPT_EXPIRED, index) : null;
        } catch (RuntimeException e) {
            return hold(Reason.CLOCK_UNAVAILABLE, index);
        }
    }

    private Result validateAsset(AssetEvidence asset, int index) {
        if (!validBinding(asset.binding) || asset.kind == null || !safeStorageKey(asset.storageObjectKey)
                || !validDigest(asset.contentSha256) || !canonicalMime(asset.detectedMimeType, asset.kind)
                || asset.byteSize <= 0 || asset.width <= 0 || asset.height <= 0 || asset.durationMs < 0
                || (asset.kind == Kind.VIDEO && asset.durationMs == 0)
                || (asset.kind == Kind.IMAGE && asset.containsAudio)
                || !identifier(asset.container) || !identifier(asset.codec) || asset.snapshotVersion <= 0) {
            return reject(Reason.INVALID_SERVER_METADATA, index);
        }
        if (asset.retention != Retention.ACTIVE) return reject(Reason.MEDIA_NOT_ACTIVE, index);
        if (asset.processing != Processing.READY) return hold(Reason.PROCESSING_NOT_READY, index);
        if (asset.review != Review.PASS) return hold(Reason.REVIEW_NOT_PASS, index);
        return null;
    }

    private Result applyAssetPolicy(PublishMediaPolicy policy, AssetEvidence asset, Role role, int index) {
        try {
            return policyResult(policy.validateAsset(asset, role), index);
        } catch (RuntimeException e) {
            return hold(Reason.POLICY_UNAVAILABLE, index);
        }
    }

    private Result policyResult(PolicyDecision decision, int index) {
        if (decision == PolicyDecision.ACCEPT) return null;
        if (decision == PolicyDecision.REJECT) return reject(Reason.POLICY_REJECTED, index);
        return hold(Reason.POLICY_HOLD, index);
    }

    static boolean validContext(Context context) {
        return context != null && context.ownerMemberId > 0 && context.purpose != null && identifier(context.draftId);
    }

    static boolean validReference(Reference reference) {
        return reference != null && reference.mediaId > 0 && identifier(reference.operationId)
                && identifier(reference.uploadReceipt) && reference.declaredKind != null;
    }

    static boolean validBinding(Binding binding) {
        return binding != null && binding.ownerMemberId > 0 && binding.purpose != null
                && identifier(binding.draftId) && binding.mediaId > 0 && identifier(binding.operationId);
    }

    static boolean sameContext(Context context, Binding binding) {
        return binding != null && context.ownerMemberId == binding.ownerMemberId
                && context.purpose == binding.purpose && context.draftId.equals(binding.draftId);
    }

    static boolean matches(Context context, Reference ref, Binding binding) {
        return validBinding(binding) && sameContext(context, binding) && ref.mediaId == binding.mediaId
                && ref.operationId.equals(binding.operationId);
    }

    private static boolean sameBinding(Binding left, Binding right) {
        return validBinding(left) && validBinding(right) && left.ownerMemberId == right.ownerMemberId
                && left.purpose == right.purpose && left.draftId.equals(right.draftId)
                && left.mediaId == right.mediaId && left.operationId.equals(right.operationId);
    }

    private static boolean identifier(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())) return false;
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return false;
        return true;
    }

    private static boolean validDigest(String digest) {
        return digest != null && digest.matches("[0-9a-f]{64}");
    }

    private static boolean canonicalMime(String mime, Kind kind) {
        return mime != null && mime.matches(kind == Kind.IMAGE ? "image/[a-z0-9.+-]+" : "video/[a-z0-9.+-]+");
    }

    private static boolean safeStorageKey(String key) {
        if (!identifier(key) || key.startsWith("/") || key.contains(":") || key.contains("?")
                || key.contains("#") || key.contains("\\")) return false;
        for (String part : key.split("/", -1)) if (part.isEmpty() || ".".equals(part) || "..".equals(part)) return false;
        return true;
    }

    static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >>> 4) & 15, 16));
                hex.append(Character.forDigit(b & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Required SHA-256 unavailable", e);
        }
    }

    private static Result reject(Reason reason, int index) { return Result.failure(Status.REJECTED, reason, index); }
    private static Result hold(Reason reason, int index) { return Result.failure(Status.HOLD, reason, index); }
}
