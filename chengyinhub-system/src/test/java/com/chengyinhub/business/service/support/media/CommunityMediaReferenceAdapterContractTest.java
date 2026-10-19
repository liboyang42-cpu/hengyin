package com.chengyinhub.business.service.support.media;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static com.chengyinhub.business.service.support.media.PublishMediaContract.*;
import static com.chengyinhub.business.service.support.media.PublishMediaValidatorContractTest.*;

/** Explicit Java main tests; no database, OSS, codec, Spring context or JUnit execution. */
public final class CommunityMediaReferenceAdapterContractTest {
    private static int checks;

    public static void main(String[] args) {
        check("reuses existing owned community media identity", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            int[] reads = {0};
            CommunityMediaReferenceAdapter adapter = new CommunityMediaReferenceAdapter((owner, op) -> {
                require(owner == CONTEXT.ownerMemberId && op.equals("op-a"), "lookup not owner/operation scoped");
                reads[0]++; return row(f);
            }, f.resolver(), mapping(f));
            expect(run(f, adapter), Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY);
            require(reads[0] == 1 && f.calls == 1, "wrong read counts");
        });
        check("old HMAC plus row PASS does not become trusted evidence", () -> {
            Fixture f = new Fixture(Kind.VIDEO); f.policy.video = true;
            int[] reads = {0};
            CommunityMediaReferenceAdapter adapter = new CommunityMediaReferenceAdapter((owner, op) -> {
                reads[0]++; return row(f);
            }, null);
            expect(run(f, adapter), Status.HOLD, Reason.EVIDENCE_MISSING);
            require(reads[0] == 0, "missing evidence adapter queried legacy rows");
        });
        check("ce61 absolute HTTPS locator maps through an explicit exact server record", () -> {
            Fixture f = new Fixture(Kind.IMAGE); Map<String, Object> row = row(f);
            String saved = "https://storage.example.invalid/profile/old-file.png?Expires=old&Signature=opaque";
            row.put("object_key", saved); int[] mappings = {0};
            CommunityMediaReferenceAdapter adapter = new CommunityMediaReferenceAdapter((owner, op) -> row, f.resolver(),
                    (c, ref, raw) -> {
                        mappings[0]++; require(saved.equals(raw), "legacy URL normalized or rewritten before trusted lookup");
                        return new CommunityMediaReferenceAdapter.LocatorMapping(binding(c, ref.mediaId, ref.operationId), raw, "media/op-a", DIGEST, 1);
                    });
            expect(run(f, adapter), Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY);
            require(mappings[0] == 1 && !saved.endsWith(f.asset.key), "test accidentally used URL path stripping");
        });
        check("ce61 slash-leading locator maps without pretending it is an object key", () -> {
            Fixture f = new Fixture(Kind.IMAGE); Map<String, Object> row = row(f);
            String saved = "/profile/uploads/old-image.png"; row.put("object_key", saved);
            CommunityMediaReferenceAdapter adapter = new CommunityMediaReferenceAdapter((owner, op) -> row, f.resolver(),
                    (c, ref, raw) -> saved.equals(raw) ? new CommunityMediaReferenceAdapter.LocatorMapping(
                            binding(c, ref.mediaId, ref.operationId), saved, "media/op-a", DIGEST, 1) : null);
            expect(run(f, adapter), Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY);
        });
        check("missing explicit locator mapping holds before legacy lookup", () -> {
            Fixture f = new Fixture(Kind.IMAGE); int[] reads = {0};
            CommunityMediaReferenceAdapter adapter = new CommunityMediaReferenceAdapter((owner, op) -> { reads[0]++; return row(f); }, f.resolver());
            expect(run(f, adapter), Status.HOLD, Reason.LOCATOR_MAPPING_MISSING);
            require(reads[0] == 0 && f.calls == 0, "missing mapping performed reads");
        });
        for (Object invalid : new Object[] {null, 101L, "media/op-a", "http://storage.example.invalid/key"}) {
            check("relative fake positive or invalid legacy locator rejected " + invalid, () -> {
                Fixture f = new Fixture(Kind.IMAGE); Map<String, Object> row = row(f); row.put("object_key", invalid);
                expect(run(f, adapter(f, row)), Status.REJECTED, Reason.LEGACY_ROW_INVALID);
                require(f.calls == 0, "invalid legacy family reached evidence");
            });
        }
        for (String unknown : new String[] {"https://foreign.example.invalid/community/original-101.png?signature=old",
                "/profile/unregistered.png", "//foreign.example.invalid/path", "https://storage.example.invalid/community/original-101.png?signature=changed"}) {
            check("unregistered exact locator never gains trust " + unknown, () -> {
                Fixture f = new Fixture(Kind.IMAGE); Map<String, Object> row = row(f); row.put("object_key", unknown);
                expect(run(f, adapter(f, row)), Status.HOLD, Reason.LOCATOR_MAPPING_MISSING);
                require(f.calls == 0, "unknown locator reached evidence port");
            });
        }
        for (Binding wrong : new Binding[] {
                new Binding(72, Purpose.COMMUNITY_POST, "draft-test-a", 101, "op-a"),
                new Binding(71, Purpose.PROFESSIONAL_TOPIC, "draft-test-a", 101, "op-a"),
                new Binding(71, Purpose.COMMUNITY_POST, "draft-other", 101, "op-a"),
                new Binding(71, Purpose.COMMUNITY_POST, "draft-test-a", 102, "op-a"),
                new Binding(71, Purpose.COMMUNITY_POST, "draft-test-a", 101, "op-other")}) {
            check("locator mapping complete binding mismatch " + checks, () -> {
                Fixture f = new Fixture(Kind.IMAGE);
                CommunityMediaReferenceAdapter adapter = new CommunityMediaReferenceAdapter((owner, op) -> row(f), f.resolver(),
                        (c, ref, raw) -> new CommunityMediaReferenceAdapter.LocatorMapping(wrong, raw, f.asset.key, DIGEST, 1));
                expect(run(f, adapter), Status.REJECTED, Reason.LOCATOR_MAPPING_MISMATCH);
                require(f.calls == 0, "wrong binding reached evidence resolver");
            });
        }
        check("mapping original locator is exact including host and query", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            CommunityMediaReferenceAdapter adapter = new CommunityMediaReferenceAdapter((owner, op) -> row(f), f.resolver(),
                    (c, ref, raw) -> new CommunityMediaReferenceAdapter.LocatorMapping(binding(c, ref.mediaId, ref.operationId),
                            "https://other.example.invalid/community/original-101.png?signature=old", f.asset.key, DIGEST, 1));
            expect(run(f, adapter), Status.REJECTED, Reason.LOCATOR_MAPPING_MISMATCH);
            require(f.calls == 0, "wrong source locator reached evidence");
        });
        check("mapping canonical object key must match trusted evidence", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            expect(run(f, mapped(f, "media/other", DIGEST, 1)), Status.REJECTED, Reason.LEGACY_ROW_MISMATCH);
        });
        check("mapping content digest must match trusted evidence", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            expect(run(f, mapped(f, f.asset.key, POSTER_DIGEST, 1)), Status.REJECTED, Reason.LEGACY_ROW_MISMATCH);
        });
        check("mapping media snapshot version must match trusted evidence", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            expect(run(f, mapped(f, f.asset.key, DIGEST, 2)), Status.REJECTED, Reason.LEGACY_ROW_MISMATCH);
        });
        check("mapping missing snapshot version holds back reference", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            expect(run(f, mapped(f, f.asset.key, DIGEST, 0)), Status.REJECTED, Reason.LOCATOR_MAPPING_MISMATCH);
        });
        check("mapping port failure holds without evidence access", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            expect(run(f, new CommunityMediaReferenceAdapter((owner, op) -> row(f), f.resolver(), (c, ref, raw) -> { throw new IllegalStateException(); })), Status.HOLD, Reason.EVIDENCE_UNAVAILABLE);
            require(f.calls == 0, "failed mapping reached evidence resolver");
        });
        check("missing row lookup holds", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            expect(run(f, new CommunityMediaReferenceAdapter(null, f.resolver())), Status.HOLD, Reason.EVIDENCE_MISSING);
            require(f.calls == 0, "missing row lookup called resolver");
        });
        check("professional topic cannot impersonate existing community adapter", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.context = new Context(71, Purpose.PROFESSIONAL_TOPIC, "draft-test-a");
            f.policy.purpose = Purpose.PROFESSIONAL_TOPIC;
            expect(run(f, adapter(f, row(f))), Status.HOLD, Reason.PURPOSE_NOT_SUPPORTED);
            require(f.calls == 0, "topic crossed community port");
        });
        check("missing legacy row", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            expect(run(f, adapter(f, null)), Status.HOLD, Reason.LEGACY_ROW_MISSING);
            require(f.calls == 0, "unregistered reference resolved");
        });
        check("lookup unavailable holds", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            expect(run(f, new CommunityMediaReferenceAdapter((owner, op) -> { throw new IllegalStateException(); }, f.resolver(), mapping(f))), Status.HOLD, Reason.EVIDENCE_UNAVAILABLE);
        });
        for (String key : new String[] {"owner_member_id", "id"}) {
            for (Object invalid : new Object[] {null, 0L, -1L, new BigDecimal("71.5"), "9223372036854775808", "NaN"}) {
                check("invalid exact row identity " + key + " " + invalid, () -> {
                    Fixture f = new Fixture(Kind.IMAGE); Map<String, Object> row = row(f); row.put(key, invalid);
                    expect(run(f, adapter(f, row)), Status.REJECTED, Reason.LEGACY_ROW_INVALID);
                    require(f.calls == 0, "invalid row consulted trusted port");
                });
            }
        }
        for (String key : new String[] {"owner_member_id", "id", "upload_request_id", "media_type"}) {
            check("wrong row binding " + key, () -> {
                Fixture f = new Fixture(Kind.IMAGE); Map<String, Object> row = row(f);
                row.put(key, key.equals("owner_member_id") ? 72L : key.equals("id") ? 102L : "different");
                expect(run(f, adapter(f, row)), Status.REJECTED, Reason.LEGACY_ROW_MISMATCH);
                require(f.calls == 0, "wrong identity consulted trusted port");
            });
        }
        for (String state : new String[] {null, "CANCELLED", "PROCESSING", "DELETED"}) {
            check("inactive legacy upload cannot pass " + state, () -> {
                Fixture f = new Fixture(Kind.IMAGE); Map<String, Object> row = row(f); row.put("upload_state", state);
                expect(run(f, adapter(f, row)), Status.REJECTED, Reason.LEGACY_ROW_INVALID);
            });
        }
        check("ATTACHED state still requires current trusted draft binding", () -> {
            Fixture f = new Fixture(Kind.IMAGE); Map<String, Object> row = row(f); row.put("upload_state", "ATTACHED");
            f.receiptBinding = new Binding(71, Purpose.COMMUNITY_POST, "different-draft", 101, "op-a");
            expect(run(f, adapter(f, row)), Status.REJECTED, Reason.BINDING_MISMATCH);
        });
        check("legacy review rejection blocks even forged future evidence", () -> {
            Fixture f = new Fixture(Kind.IMAGE); Map<String, Object> row = row(f); row.put("review_state", "REJECTED");
            expect(run(f, adapter(f, row)), Status.REJECTED, Reason.EVIDENCE_REJECTED);
            require(f.calls == 0, "rejected row resolved");
        });
        check("legacy PASS cannot override server review pending", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.asset.review = Review.PENDING;
            expect(run(f, adapter(f, row(f))), Status.HOLD, Reason.REVIEW_NOT_PASS);
        });
        check("legacy COMMITTED cannot override server processing pending", () -> {
            Fixture f = new Fixture(Kind.VIDEO); f.policy.video = true; f.asset.processing = Processing.PENDING;
            expect(run(f, adapter(f, row(f))), Status.HOLD, Reason.PROCESSING_NOT_READY);
        });
        check("legacy metadata never replaces probed evidence", () -> {
            Fixture f = new Fixture(Kind.IMAGE); Map<String, Object> row = row(f);
            row.put("mime_type", "video/client-spoofed"); row.put("byte_size", Long.MAX_VALUE);
            row.put("width", -1); row.put("height", -1); row.put("duration_ms", -1);
            expect(run(f, adapter(f, row)), Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY);
            require(f.evidence().asset.byteSize == 1000 && f.evidence().asset.detectedMimeType.equals("image/png"), "legacy claims entered evidence");
        });
        check("legacy content_hash is not a stored-byte digest proof", () -> {
            Fixture f = new Fixture(Kind.IMAGE); Map<String, Object> row = row(f); row.put("content_hash", DIGEST);
            f.receiptDigest = POSTER_DIGEST;
            expect(run(f, adapter(f, row)), Status.REJECTED, Reason.RECEIPT_MISMATCH);
        });
        check("unrecognized legacy locator cannot obtain canonical mapping", () -> {
            Fixture f = new Fixture(Kind.IMAGE); Map<String, Object> row = row(f); row.put("object_key", "https://unrecognized.example.invalid/media/other");
            expect(run(f, adapter(f, row)), Status.HOLD, Reason.LOCATOR_MAPPING_MISSING);
        });
        check("legacy long-lived URL never becomes new object key evidence", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.asset.key = "https://storage.example.invalid/old-url";
            expect(run(f, adapter(f, row(f))), Status.REJECTED, Reason.INVALID_SERVER_METADATA);
        });
        check("trusted resolver mismatch blocked in bridge", () -> {
            Fixture f = new Fixture(Kind.IMAGE); Map<String, Object> row = row(f);
            f.asset.binding = new Binding(72, Purpose.COMMUNITY_POST, "draft-test-a", 101, "op-a");
            expect(run(f, adapter(f, row)), Status.REJECTED, Reason.LEGACY_ROW_MISMATCH);
        });
        check("trusted resolver exception", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            expect(run(f, new CommunityMediaReferenceAdapter((owner, op) -> row(f), (c, r) -> { throw new IllegalStateException(); }, mapping(f))), Status.HOLD, Reason.EVIDENCE_UNAVAILABLE);
        });
        check("trusted resolver unavailable resolution", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            expect(run(f, new CommunityMediaReferenceAdapter((owner, op) -> row(f), (c, r) -> Resolution.hold(Reason.EVIDENCE_UNAVAILABLE), mapping(f))), Status.HOLD, Reason.EVIDENCE_UNAVAILABLE);
        });
        check("trusted resolver null response", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            expect(run(f, new CommunityMediaReferenceAdapter((owner, op) -> row(f), (c, r) -> null, mapping(f))), Status.HOLD, Reason.EVIDENCE_MISSING);
        });
        check("same bound request repeat remains read-only and deterministic", () -> {
            Fixture f = new Fixture(Kind.IMAGE); Map<String, Object> row = row(f); Map<String, Object> before = new HashMap<>(row);
            CommunityMediaReferenceAdapter adapter = adapter(f, row);
            Result first = run(f, adapter); Result second = run(f, adapter);
            expect(first, Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY);
            expect(second, Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY);
            require(first.references.get(0).mediaId == second.references.get(0).mediaId && row.equals(before), "reference or row changed");
        });
        System.out.println("PASS CommunityMediaReferenceAdapterContractTest checks=" + checks + " runtime=" + System.getProperty("java.version"));
    }

    private static Result run(Fixture f, CommunityMediaReferenceAdapter adapter) {
        return new PublishMediaValidator(p -> f.policy, adapter, CLOCK).validate(f.context, f.refs());
    }
    private static CommunityMediaReferenceAdapter adapter(Fixture f, Map<String, Object> row) {
        return new CommunityMediaReferenceAdapter((owner, op) -> row, f.resolver(), mapping(f));
    }
    private static CommunityMediaReferenceAdapter mapped(Fixture f, String key, String digest, long version) {
        return new CommunityMediaReferenceAdapter((owner, op) -> row(f), f.resolver(), (c, ref, raw) ->
                new CommunityMediaReferenceAdapter.LocatorMapping(binding(c, ref.mediaId, ref.operationId), raw, key, digest, version));
    }
    private static String legacyLocator(Fixture f) {
        return "https://storage.example.invalid/community/original-" + f.reference.mediaId + ".png?signature=old";
    }
    private static CommunityMediaReferenceAdapter.LegacyLocatorResolver mapping(Fixture f) {
        return (context, reference, locator) -> legacyLocator(f).equals(locator)
                ? new CommunityMediaReferenceAdapter.LocatorMapping(binding(f.context, f.reference.mediaId, f.reference.operationId),
                        locator, f.asset.key, DIGEST, 1) : null;
    }
    private static Map<String, Object> row(Fixture f) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", f.reference.mediaId); row.put("owner_member_id", CONTEXT.ownerMemberId);
        row.put("upload_request_id", f.reference.operationId); row.put("media_type", f.reference.declaredKind.name());
        row.put("object_key", legacyLocator(f)); row.put("derived_object_key", "https://storage.example.invalid/old-derived-url");
        row.put("upload_state", "COMMITTED"); row.put("review_state", "PASS");
        row.put("upload_receipt_hash", PublishMediaValidator.sha256("legacy owner/key/size-only HMAC"));
        return row;
    }
    private static void check(String name, Runnable test) { test.run(); checks++; System.out.println("PASS " + name); }
}
