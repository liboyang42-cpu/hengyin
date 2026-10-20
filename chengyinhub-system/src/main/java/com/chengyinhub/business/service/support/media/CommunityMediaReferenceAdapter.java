package com.chengyinhub.business.service.support.media;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static com.chengyinhub.business.service.support.media.PublishMediaContract.*;

/**
 * Read-only bridge to ce61 CommunityPostMapper.selectMediaByOwnerRequest(owner, uploadRequestId).
 * It reuses existing media IDs; it creates no second media registry and writes no row.
 * There is deliberately no default trusted resolver: legacy HMAC/row PASS is insufficient.
 * Wire this only after the separate server-evidence and atomic publish-fencing contracts exist.
 */
public final class CommunityMediaReferenceAdapter implements EvidenceResolver {
    public interface OwnedMediaLookup {
        Map<String, Object> selectMediaByOwnerRequest(long ownerMemberId, String uploadRequestId);
    }

    /**
     * Read-only authoritative mapping for the exact saved legacy locator. Never fetch the URL,
     * strip its host or infer ownership from URL shape. No production implementation is supplied.
     */
    public interface LegacyLocatorResolver {
        LocatorMapping resolve(Context context, Reference reference, String exactLegacyLocator);
    }

    /** Server mapping bound to the exact media snapshot subsequently returned by the evidence port. */
    public static final class LocatorMapping {
        public final Binding binding;
        public final String exactLegacyLocator;
        public final String canonicalObjectKey;
        public final String contentSha256;
        public final long mediaSnapshotVersion;

        public LocatorMapping(Binding binding, String exactLegacyLocator, String canonicalObjectKey,
                              String contentSha256, long mediaSnapshotVersion) {
            this.binding = binding;
            this.exactLegacyLocator = exactLegacyLocator;
            this.canonicalObjectKey = canonicalObjectKey;
            this.contentSha256 = contentSha256;
            this.mediaSnapshotVersion = mediaSnapshotVersion;
        }
    }

    private final OwnedMediaLookup lookup;
    private final EvidenceResolver trustedResolver;
    private final LegacyLocatorResolver locatorResolver;

    public CommunityMediaReferenceAdapter(OwnedMediaLookup lookup, EvidenceResolver trustedResolver) {
        this(lookup, trustedResolver, null);
    }

    public CommunityMediaReferenceAdapter(OwnedMediaLookup lookup, EvidenceResolver trustedResolver,
                                         LegacyLocatorResolver locatorResolver) {
        this.lookup = lookup;
        this.trustedResolver = trustedResolver;
        this.locatorResolver = locatorResolver;
    }

    @Override
    public Resolution verifyAndResolve(Context context, Reference reference) {
        if (!PublishMediaValidator.validContext(context)) return Resolution.reject(Reason.INVALID_CONTEXT);
        if (!PublishMediaValidator.validReference(reference)) return Resolution.reject(Reason.INVALID_REFERENCE);
        if (context.purpose != Purpose.COMMUNITY_POST) return Resolution.hold(Reason.PURPOSE_NOT_SUPPORTED);
        // Missing authoritative evidence blocks before any legacy row lookup.
        if (lookup == null || trustedResolver == null) return Resolution.hold(Reason.EVIDENCE_MISSING);
        if (locatorResolver == null) return Resolution.hold(Reason.LOCATOR_MAPPING_MISSING);
        final Map<String, Object> row;
        try {
            Map<String, Object> loaded = lookup.selectMediaByOwnerRequest(context.ownerMemberId, reference.operationId);
            row = loaded == null ? null : new HashMap<>(loaded);
        } catch (RuntimeException e) {
            return Resolution.hold(Reason.EVIDENCE_UNAVAILABLE);
        }
        if (row == null) return Resolution.hold(Reason.LEGACY_ROW_MISSING);
        Long owner = exactPositiveLong(row.get("owner_member_id"));
        Long id = exactPositiveLong(row.get("id"));
        if (owner == null || id == null) return Resolution.reject(Reason.LEGACY_ROW_INVALID);
        if (owner != context.ownerMemberId || id != reference.mediaId
                || !reference.operationId.equals(row.get("upload_request_id"))
                || !reference.declaredKind.name().equals(row.get("media_type"))) {
            return Resolution.reject(Reason.LEGACY_ROW_MISMATCH);
        }
        Object upload = row.get("upload_state");
        if (!("COMMITTED".equals(upload) || "ATTACHED".equals(upload))) {
            return Resolution.reject(Reason.LEGACY_ROW_INVALID);
        }
        if ("REJECTED".equals(row.get("review_state"))) return Resolution.reject(Reason.EVIDENCE_REJECTED);
        Object rawLocator = row.get("object_key");
        if (!(rawLocator instanceof String) || !legacyLocatorShape((String) rawLocator)) {
            return Resolution.reject(Reason.LEGACY_ROW_INVALID);
        }
        String exactLocator = (String) rawLocator;
        final LocatorMapping mapping;
        try {
            mapping = locatorResolver.resolve(context, reference, exactLocator);
        } catch (RuntimeException e) {
            return Resolution.hold(Reason.EVIDENCE_UNAVAILABLE);
        }
        if (mapping == null) return Resolution.hold(Reason.LOCATOR_MAPPING_MISSING);
        if (!PublishMediaValidator.matches(context, reference, mapping.binding)
                || !exactLocator.equals(mapping.exactLegacyLocator)
                || mapping.canonicalObjectKey == null || mapping.contentSha256 == null
                || mapping.mediaSnapshotVersion <= 0) {
            return Resolution.reject(Reason.LOCATOR_MAPPING_MISMATCH);
        }
        final Resolution resolved;
        try {
            resolved = trustedResolver.verifyAndResolve(context, reference);
        } catch (RuntimeException e) {
            return Resolution.hold(Reason.EVIDENCE_UNAVAILABLE);
        }
        if (resolved == null) return Resolution.hold(Reason.EVIDENCE_MISSING);
        if (resolved.status != Status.VALIDATED) return resolved;
        if (resolved.evidence == null || resolved.evidence.asset == null) return Resolution.hold(Reason.EVIDENCE_MISSING);
        AssetEvidence asset = resolved.evidence.asset;
        if (!PublishMediaValidator.matches(context, reference, asset.binding)
                || asset.kind != reference.declaredKind || asset.storageObjectKey == null || asset.contentSha256 == null
                || !asset.storageObjectKey.equals(mapping.canonicalObjectKey)
                || !asset.contentSha256.equals(mapping.contentSha256)
                || asset.snapshotVersion != mapping.mediaSnapshotVersion) {
            return Resolution.reject(Reason.LEGACY_ROW_MISMATCH);
        }
        // The validator still checks full receipt, draft, content, retention, probe and review evidence.
        // Existing mime/size/width/height/duration/PASS are intentionally never copied into evidence.
        return resolved;
    }

    /** Mirrors ce61's accepted locator families only; this is never proof of trust or origin. */
    private static boolean legacyLocatorShape(String locator) {
        String normalized = locator.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("https://") || normalized.startsWith("/");
    }

    private static Long exactPositiveLong(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) return null;
        try {
            long result = new BigDecimal(value.toString()).longValueExact();
            return result > 0 ? result : null;
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }
}
