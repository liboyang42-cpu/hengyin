package com.chengyinhub.business.service.support.media;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.chengyinhub.business.service.support.media.PublishMediaContract.*;
import static com.chengyinhub.business.service.support.media.CommunityMediaReferenceAdapter.*;

/**
 * Exact, read-only join of an authoritative saved upload pair and a current authorized media binding.
 * Not a URL parser, receipt verifier/issuer, storage client, persistence model or Spring component.
 * ce61 exposes fileName/url together but does not persist the stronger records required here.
 * These ports therefore have no production implementation. Constructors do not confer trust.
 */
public final class RecordedLegacyLocatorResolver implements LegacyLocatorResolver {
    /**
     * Server-only record source. It must preserve fileName/url from the SAME trusted upload result,
     * then associate them with the server-authorized full binding, verified stored bytes and version.
     * Never populate this port from a request Map, old media row or old owner/URL/size HMAC.
     * Return every matching record: zero or ambiguous results fail closed, including duplicate rows.
     */
    public interface TrustedUploadRecords {
        List<UploadBindingRecord> find(Binding expectedBinding);
    }

    /** Current server-authorized snapshot, including revocation and the same complete binding. */
    public interface CurrentMediaBindings {
        MediaBindingSnapshot find(Binding expectedBinding);
    }

    /**
     * Explicit source/purpose policy, separate from publish/media limits. No origins or buckets are
     * approved by this class. The policy must authorize the records' storage authority and capture
     * provenance. An ACCEPT result cannot authenticate records supplied by an untrusted caller.
     */
    public interface SourcePolicy {
        default boolean enabled() { return false; }
        PolicyDecision evaluate(UploadBindingRecord upload, MediaBindingSnapshot current);
    }

    /** Opaque configured storage namespace plus immutable object version, never a host-derived scope. */
    public static final class StorageObjectIdentity {
        public final String storageAuthority;
        public final String canonicalObjectKey;
        public final String objectVersion;

        public StorageObjectIdentity(String storageAuthority, String canonicalObjectKey, String objectVersion) {
            this.storageAuthority = storageAuthority;
            this.canonicalObjectKey = canonicalObjectKey;
            this.objectVersion = objectVersion;
        }
    }

    /** Server association of a preserved upload pair with an already authorized existing media ID. */
    public static final class UploadBindingRecord {
        public final String recordId;
        public final String captureProvenanceVersion;
        public final Binding binding;
        public final Kind kind;
        public final String exactLegacyLocator;
        public final StorageObjectIdentity object;
        public final String contentSha256;

        public UploadBindingRecord(String recordId, String captureProvenanceVersion, Binding binding,
                                   Kind kind, String exactLegacyLocator, StorageObjectIdentity object,
                                   String contentSha256) {
            this.recordId = recordId;
            this.captureProvenanceVersion = captureProvenanceVersion;
            this.binding = binding;
            this.kind = kind;
            this.exactLegacyLocator = exactLegacyLocator;
            this.object = object;
            this.contentSha256 = contentSha256;
        }
    }

    public static final class MediaBindingSnapshot {
        public final String uploadRecordId;
        public final Binding binding;
        public final Kind kind;
        public final StorageObjectIdentity object;
        public final String contentSha256;
        public final long mediaSnapshotVersion;
        public final Retention retention;

        public MediaBindingSnapshot(String uploadRecordId, Binding binding, Kind kind,
                                    StorageObjectIdentity object, String contentSha256,
                                    long mediaSnapshotVersion, Retention retention) {
            this.uploadRecordId = uploadRecordId;
            this.binding = binding;
            this.kind = kind;
            this.object = object;
            this.contentSha256 = contentSha256;
            this.mediaSnapshotVersion = mediaSnapshotVersion;
            this.retention = retention;
        }
    }

    private final TrustedUploadRecords uploads;
    private final CurrentMediaBindings bindings;
    private final SourcePolicy policy;

    /** Missing policy is disabled; no lookup occurs. */
    public RecordedLegacyLocatorResolver(TrustedUploadRecords uploads, CurrentMediaBindings bindings) {
        this(uploads, bindings, null);
    }

    public RecordedLegacyLocatorResolver(TrustedUploadRecords uploads, CurrentMediaBindings bindings,
                                         SourcePolicy policy) {
        this.uploads = uploads;
        this.bindings = bindings;
        this.policy = policy;
    }

    /**
     * Null means no mapping: the R1 adapter holds and never calls its evidence port. Returned data
     * only describes the read snapshot. Strong receipt authentication, operation fencing and the
     * final transactional recheck of current policy/authorization/version remain mandatory.
     */
    @Override
    public LocatorMapping resolve(Context context, Reference reference, String exactLegacyLocator) {
        if (!PublishMediaValidator.validContext(context) || !PublishMediaValidator.validReference(reference)
                || context.purpose != Purpose.COMMUNITY_POST || !legacyLocator(exactLegacyLocator)
                || uploads == null || bindings == null || policy == null) return null;
        try {
            if (!policy.enabled()) return null;
            Binding expected = new Binding(context.ownerMemberId, context.purpose, context.draftId,
                    reference.mediaId, reference.operationId);
            List<UploadBindingRecord> loaded = uploads.find(expected);
            // Snapshot the collection before calling another collaborator; elements are immutable.
            List<UploadBindingRecord> records = loaded == null ? null : new ArrayList<>(loaded);
            if (records == null || records.size() != 1) return null;
            UploadBindingRecord upload = records.get(0);
            if (!validUpload(context, reference, exactLegacyLocator, upload)) return null;
            MediaBindingSnapshot current = bindings.find(expected);
            if (!validCurrent(context, reference, current)
                    || !upload.recordId.equals(current.uploadRecordId)
                    || !sameObject(upload.object, current.object)
                    || !upload.contentSha256.equals(current.contentSha256)) return null;
            if (policy.evaluate(upload, current) != PolicyDecision.ACCEPT) return null;
            return new LocatorMapping(current.binding, exactLegacyLocator,
                    current.object.canonicalObjectKey, current.contentSha256, current.mediaSnapshotVersion);
        } catch (RuntimeException unavailable) {
            // No partial result or raw locator/receipt in error output. Absence cannot be success.
            return null;
        }
    }

    private static boolean validUpload(Context context, Reference reference, String locator,
                                       UploadBindingRecord upload) {
        return upload != null && identifier(upload.recordId) && identifier(upload.captureProvenanceVersion)
                && PublishMediaValidator.matches(context, reference, upload.binding)
                && upload.kind == reference.declaredKind && locator.equals(upload.exactLegacyLocator)
                && validObject(upload.object) && digest(upload.contentSha256);
    }

    private static boolean validCurrent(Context context, Reference reference, MediaBindingSnapshot current) {
        return current != null && identifier(current.uploadRecordId)
                && PublishMediaValidator.matches(context, reference, current.binding)
                && current.kind == reference.declaredKind && validObject(current.object)
                && digest(current.contentSha256) && current.mediaSnapshotVersion > 0
                && current.retention == Retention.ACTIVE;
    }

    private static boolean sameObject(StorageObjectIdentity left, StorageObjectIdentity right) {
        return left.storageAuthority.equals(right.storageAuthority)
                && left.canonicalObjectKey.equals(right.canonicalObjectKey)
                && left.objectVersion.equals(right.objectVersion);
    }

    private static boolean validObject(StorageObjectIdentity object) {
        return object != null && identifier(object.storageAuthority) && identifier(object.objectVersion)
                && canonicalKey(object.canonicalObjectKey);
    }

    private static boolean canonicalKey(String key) {
        if (!identifier(key) || key.startsWith("/") || key.contains(":") || key.contains("?")
                || key.contains("#") || key.contains("\\") || key.contains("%")) return false;
        for (String part : key.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) return false;
        }
        return true;
    }

    /** Shape is only a precondition. Exact trusted records, never this shape, establish a mapping. */
    private static boolean legacyLocator(String locator) {
        if (!identifier(locator) || locator.length() > 500 || locator.startsWith("//")) return false;
        return locator.toLowerCase(Locale.ROOT).startsWith("https://") || locator.startsWith("/");
    }

    private static boolean identifier(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())) return false;
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return false;
        return true;
    }

    private static boolean digest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
}
