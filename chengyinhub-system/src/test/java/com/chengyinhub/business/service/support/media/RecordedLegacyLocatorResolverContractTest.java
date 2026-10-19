package com.chengyinhub.business.service.support.media;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.chengyinhub.business.service.support.media.PublishMediaContract.*;
import static com.chengyinhub.business.service.support.media.RecordedLegacyLocatorResolver.*;
import static com.chengyinhub.business.service.support.media.PublishMediaValidatorContractTest.*;

/** Explicit JDK main tests with synthetic trusted ports, not a DB/OSS or production receipt test. */
public final class RecordedLegacyLocatorResolverContractTest {
    private static int checks;
    private static final String URL = "https://storage.example.invalid/legacy/different-path.png?Expires=2000000000&Signature=opaque";
    private static final String KEY = "upload/0123456789abcdef0123456789abcdef.png";
    private static final String OP = "upload-operation-0001";

    public static void main(String[] args) {
        check("known exact HTTPS pair preserves canonical server fileName", () -> {
            Harness h = new Harness(); mapping(h, h.resolve());
            require(h.uploadReads == 1 && h.currentReads == 1 && h.policyReads == 1, "wrong read sequence");
            require(!URL.contains(KEY), "positive accidentally strips URL path");
        });
        check("known exact slash locator has separately recorded canonical key", () -> {
            Harness h = new Harness(); h.locator = "/profile/legacy-name.png"; mapping(h, h.resolve());
        });
        check("constructor without policy does no source reads", () -> {
            Harness h = new Harness(); require(new RecordedLegacyLocatorResolver(h.uploads(), h.currents())
                    .resolve(h.f.context, h.f.reference, h.locator) == null, "default enabled"); noReads(h);
        });
        check("policy interface defaults off even if evaluate accepts", () -> {
            Harness h = new Harness(); h.policy = (u, c) -> PolicyDecision.ACCEPT;
            absent(h); noReads(h);
        });
        negative("missing policy", h -> h.policy = null);
        negative("missing upload port", h -> h.missingUploads = true);
        negative("missing current port", h -> h.missingCurrent = true);
        negative("disabled policy", h -> h.enabled = false);
        negative("policy enablement throws", h -> h.throwEnabled = true);
        for (Context c : new Context[] {null, new Context(0, Purpose.COMMUNITY_POST, "draft"),
                new Context(71, null, "draft"), new Context(71, Purpose.COMMUNITY_POST, " "),
                new Context(71, Purpose.PROFESSIONAL_TOPIC, "draft-test-a")}) {
            check("bad or unsupported context " + checks, () -> {
                Harness h = new Harness(); require(h.resolver().resolve(c, h.f.reference, h.locator) == null, "bad context mapped"); noReads(h);
            });
        }
        for (Reference r : new Reference[] {null, new Reference(0, OP, "receipt", Kind.IMAGE),
                new Reference(101, " ", "receipt", Kind.IMAGE), new Reference(101, OP, null, Kind.IMAGE),
                new Reference(101, OP, "receipt", null)}) {
            check("bad reference " + checks, () -> {
                Harness h = new Harness(); require(h.resolver().resolve(h.f.context, r, h.locator) == null, "bad reference mapped"); noReads(h);
            });
        }
        for (String s : new String[] {null, "", " ", " " + URL, URL + " ", URL + "\n", "media/a.png", "http://host/a.png",
                "javascript:alert(1)", "//foreign.example.invalid/a.png", repeat('x', 501)}) {
            check("invalid locator rejected before records " + checks, () -> {
                Harness h = new Harness(); require(h.resolver().resolve(h.f.context, h.f.reference, s) == null, "bad locator mapped"); noReads(h);
            });
        }
        for (String s : new String[] {URL.replace("storage.", "foreign."), URL.replace("opaque", "changed"),
                URL.replace("different-path", "unknown"), URL.replace("different-path", "%64ifferent-path"),
                URL.replace("Expires=2000000000&Signature=opaque", "Signature=opaque&Expires=2000000000"),
                URL.replace("https://storage", "https://user@storage"), "/profile/unrecorded.png"}) {
            check("unknown exact locator never normalized or trusted " + checks, () -> {
                Harness h = new Harness(); require(h.resolver().resolve(h.f.context, h.f.reference, s) == null, "unknown locator mapped");
                require(h.currentReads == 0 && h.policyReads == 0, "unknown pair reached later ports");
            });
        }
        negative("upload source unavailable", h -> h.throwUpload = true);
        negative("current source unavailable", h -> h.throwCurrent = true);
        for (int mode : new int[] {0, 2, 3, 4}) negative("missing or ambiguous upload records " + mode, h -> h.recordMode = mode);
        negative("missing current record", h -> h.nullCurrent = true);
        for (Binding b : wrongBindings()) {
            negative("upload full binding rejected " + checks, h -> h.uploadBinding = b);
            negative("current full binding rejected " + checks, h -> h.currentBinding = b);
        }
        negative("upload record ID absent", h -> h.recordId = null);
        negative("upload record ID whitespace", h -> h.recordId = " ");
        negative("capture provenance absent", h -> h.provenance = null);
        negative("capture provenance control", h -> h.provenance = "capture\n1");
        negative("upload kind differs", h -> h.uploadKind = Kind.VIDEO);
        negative("upload kind absent", h -> h.uploadKind = null);
        negative("current kind differs", h -> h.currentKind = Kind.VIDEO);
        negative("current kind absent", h -> h.currentKind = null);
        negative("record link differs", h -> h.currentRecordId = "other-record");
        negative("record link absent", h -> h.currentRecordId = null);
        negative("upload object missing", h -> h.uploadObject = null);
        negative("current object missing", h -> h.currentObject = null);
        for (StorageObjectIdentity o : new StorageObjectIdentity[] {
                new StorageObjectIdentity(null, KEY, "version-1"), new StorageObjectIdentity(" ", KEY, "version-1"),
                new StorageObjectIdentity("storage-a", KEY, null), new StorageObjectIdentity("storage-a", KEY, " "),
                new StorageObjectIdentity("storage-a", KEY, "version\n1")}) {
            negative("bad upload object identity " + checks, h -> h.uploadObject = o);
            negative("bad current object identity " + checks, h -> h.currentObject = o);
        }
        for (String key : new String[] {null, "", URL, "/profile/a.png", "a/../b", "a/./b", "a//b", "a/", "a\\b", "a?x=y", "a#x", "a%2fb", " a"}) {
            negative("unsafe key cannot map even when both records agree " + checks, h -> {
                h.uploadObject = object("storage-a", key, "version-1"); h.currentObject = h.uploadObject;
            });
        }
        negative("cross storage authority", h -> h.currentObject = object("storage-b", KEY, "version-1"));
        negative("cross canonical object", h -> h.currentObject = object("storage-a", "upload/other.png", "version-1"));
        negative("cross immutable object version", h -> h.currentObject = object("storage-a", KEY, "version-2"));
        negative("both records in unapproved storage are denied by policy", h -> {
            h.uploadObject = object("unapproved", KEY, "version-1"); h.currentObject = h.uploadObject;
        });
        negative("unapproved capture provenance", h -> h.provenance = "unapproved-capture");
        for (String digest : new String[] {null, "", "old-row-url-hash", repeat('A', 64), repeat('a', 63), repeat('a', 65)}) {
            negative("invalid upload digest " + checks, h -> h.uploadDigest = digest);
            negative("invalid current digest " + checks, h -> h.currentDigest = digest);
            negative("matching malformed digests never become content proof " + checks, h -> { h.uploadDigest = digest; h.currentDigest = digest; });
        }
        negative("content digest mismatch", h -> h.currentDigest = POSTER_DIGEST);
        negative("zero media snapshot", h -> h.version = 0);
        negative("negative media snapshot", h -> h.version = -1);
        for (Retention r : new Retention[] {null, Retention.REVOKED, Retention.DELETED}) negative("inactive current media " + r, h -> h.retention = r);
        for (PolicyDecision d : new PolicyDecision[] {null, PolicyDecision.HOLD, PolicyDecision.REJECT}) negative("policy did not accept " + d, h -> h.decision = d);
        negative("policy evaluation unavailable", h -> h.throwPolicy = true);
        check("copied upload list remains stable across source callback", () -> {
            Harness h = new Harness(); h.clearLoaded = true; mapping(h, h.resolve());
            require(h.loaded.isEmpty(), "test did not mutate original collection");
        });
        check("concrete resolver integrates through R1 and retains existing media identity", () -> {
            Harness h = new Harness(); Result result = h.publish(h.f.resolver());
            expect(result, Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY);
            require(result.references.size() == 1 && result.references.get(0).mediaId == 101, "changed media ID");
        });
        check("missing upload pair holds before evidence resolver", () -> {
            Harness h = new Harness(); h.recordMode = 0;
            expect(h.publish(h.f.resolver()), Status.HOLD, Reason.LOCATOR_MAPPING_MISSING);
            require(h.f.calls == 0, "unmapped row reached strong evidence");
        });
        check("valid mapping and legacy HMAC cannot invent strong receipt", () -> {
            Harness h = new Harness(); h.f.setIdentity(101, OP, "2000000000.legacy-hmac"); h.f.asset.key = KEY;
            expect(h.publish((c, r) -> Resolution.verified(new VerifiedMedia(h.f.asset.build(), null, null))),
                    Status.HOLD, Reason.EVIDENCE_MISSING);
        });
        check("missing strong evidence still holds before row lookup", () -> {
            Harness h = new Harness(); expect(h.publish(null), Status.HOLD, Reason.EVIDENCE_MISSING); noReads(h);
        });
        check("mapping does not bypass server review", () -> {
            Harness h = new Harness(); h.f.asset.review = Review.PENDING;
            expect(h.publish(h.f.resolver()), Status.HOLD, Reason.REVIEW_NOT_PASS);
        });
        check("mapping does not bypass changed evidence snapshot", () -> {
            Harness h = new Harness(); h.f.asset.version = 2;
            expect(h.publish(h.f.resolver()), Status.REJECTED, Reason.LEGACY_ROW_MISMATCH);
        });
        check("mapping does not bypass changed evidence digest", () -> {
            Harness h = new Harness(); h.f.asset.digest = POSTER_DIGEST;
            expect(h.publish(h.f.resolver()), Status.REJECTED, Reason.LEGACY_ROW_MISMATCH);
        });
        check("mapping does not bypass evidence canonical key", () -> {
            Harness h = new Harness(); h.f.asset.key = "upload/substitute.png";
            expect(h.publish(h.f.resolver()), Status.REJECTED, Reason.LEGACY_ROW_MISMATCH);
        });
        check("mapping does not enable video publish", () -> {
            Harness h = new Harness(Kind.VIDEO);
            expect(h.publish(h.f.resolver()), Status.HOLD, Reason.VIDEO_DISABLED);
        });
        check("record value objects are final and expose final fields only", () -> {
            for (Class<?> c : new Class<?>[] {StorageObjectIdentity.class, UploadBindingRecord.class, MediaBindingSnapshot.class}) {
                require(Modifier.isFinal(c.getModifiers()), "mutable record type");
                for (Field field : c.getDeclaredFields()) require(Modifier.isFinal(field.getModifiers()), "mutable record field");
            }
        });
        System.out.println("PASS RecordedLegacyLocatorResolverContractTest checks=" + checks + " runtime=" + System.getProperty("java.version"));
    }

    private static final class Harness {
        final Fixture f;
        String locator = URL, recordId = "server-record-1", currentRecordId = "server-record-1", provenance = "capture-v1";
        Binding uploadBinding, currentBinding;
        Kind uploadKind, currentKind;
        StorageObjectIdentity uploadObject = object("storage-a", KEY, "version-1"), currentObject = uploadObject;
        String uploadDigest = DIGEST, currentDigest = DIGEST;
        long version = 1;
        Retention retention = Retention.ACTIVE;
        PolicyDecision decision = PolicyDecision.ACCEPT;
        boolean enabled = true, throwEnabled, throwPolicy, throwUpload, throwCurrent, nullCurrent, missingUploads, missingCurrent, clearLoaded;
        int recordMode = 1, uploadReads, currentReads, policyReads;
        List<UploadBindingRecord> loaded;
        SourcePolicy policy;
        Harness() { this(Kind.IMAGE); }
        Harness(Kind kind) {
            f = new Fixture(kind); f.setIdentity(101, OP, "strong-receipt-placeholder"); f.asset.key = KEY;
            uploadBinding = binding(f.context, 101, OP); currentBinding = uploadBinding;
            uploadKind = kind; currentKind = kind;
            policy = new SourcePolicy() {
                public boolean enabled() { if (throwEnabled) throw new IllegalStateException(); return enabled; }
                public PolicyDecision evaluate(UploadBindingRecord u, MediaBindingSnapshot c) {
                    policyReads++; if (throwPolicy) throw new IllegalStateException();
                    if (!"storage-a".equals(u.object.storageAuthority) || !"capture-v1".equals(u.captureProvenanceVersion)) return PolicyDecision.HOLD;
                    return decision;
                }
            };
        }
        TrustedUploadRecords uploads() {
            if (missingUploads) return null;
            return b -> {
                uploadReads++; exactRequest(b); if (throwUpload) throw new IllegalStateException();
                if (recordMode == 3) return null;
                UploadBindingRecord record = new UploadBindingRecord(recordId, provenance, uploadBinding, uploadKind, locator, uploadObject, uploadDigest);
                loaded = new ArrayList<>();
                if (recordMode != 0) loaded.add(recordMode == 4 ? null : record);
                if (recordMode == 2) loaded.add(record);
                return loaded;
            };
        }
        CurrentMediaBindings currents() {
            if (missingCurrent) return null;
            return b -> {
                currentReads++; exactRequest(b); if (throwCurrent) throw new IllegalStateException();
                if (clearLoaded) loaded.clear(); if (nullCurrent) return null;
                return new MediaBindingSnapshot(currentRecordId, currentBinding, currentKind, currentObject, currentDigest, version, retention);
            };
        }
        void exactRequest(Binding b) {
            require(PublishMediaValidator.matches(f.context, f.reference, b), "lookup did not carry full expected binding");
            require(OP.equals(b.operationId), "operation alias introduced");
        }
        RecordedLegacyLocatorResolver resolver() { return new RecordedLegacyLocatorResolver(uploads(), currents(), policy); }
        CommunityMediaReferenceAdapter.LocatorMapping resolve() { return resolver().resolve(f.context, f.reference, locator); }
        Result publish(EvidenceResolver evidence) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", 101L); row.put("owner_member_id", 71L); row.put("upload_request_id", OP);
            row.put("media_type", f.reference.declaredKind.name()); row.put("object_key", locator);
            row.put("upload_state", "COMMITTED"); row.put("review_state", "PASS");
            row.put("upload_receipt_hash", PublishMediaValidator.sha256(f.reference.uploadReceipt));
            return f.withResolver(new CommunityMediaReferenceAdapter((owner, op) -> row, evidence, resolver()));
        }
    }

    private static List<Binding> wrongBindings() {
        return Arrays.asList(null, new Binding(72, Purpose.COMMUNITY_POST, "draft-test-a", 101, OP),
                new Binding(71, Purpose.PROFESSIONAL_TOPIC, "draft-test-a", 101, OP),
                new Binding(71, Purpose.COMMUNITY_POST, "draft-other", 101, OP),
                new Binding(71, Purpose.COMMUNITY_POST, "draft-test-a", 102, OP),
                new Binding(71, Purpose.COMMUNITY_POST, "draft-test-a", 101, "other-operation"));
    }
    private static StorageObjectIdentity object(String authority, String key, String version) { return new StorageObjectIdentity(authority, key, version); }
    private static String repeat(char c, int count) { char[] a = new char[count]; Arrays.fill(a, c); return new String(a); }
    private static void noReads(Harness h) { require(h.uploadReads == 0 && h.currentReads == 0 && h.policyReads == 0, "disabled/invalid input performed source reads"); }
    private static void absent(Harness h) { require(h.resolve() == null, "invalid mapping accepted"); }
    private static void mapping(Harness h, CommunityMediaReferenceAdapter.LocatorMapping m) {
        require(m != null && KEY.equals(m.canonicalObjectKey) && h.locator.equals(m.exactLegacyLocator)
                && DIGEST.equals(m.contentSha256) && m.mediaSnapshotVersion == 1
                && PublishMediaValidator.matches(h.f.context, h.f.reference, m.binding), "mapping lost record identity");
    }
    private static void negative(String label, Consumer<Harness> change) { check(label, () -> { Harness h = new Harness(); change.accept(h); absent(h); }); }
    private static void check(String name, Runnable action) { try { action.run(); checks++; } catch (AssertionError e) { throw new AssertionError(name + ": " + e.getMessage(), e); } }
}
