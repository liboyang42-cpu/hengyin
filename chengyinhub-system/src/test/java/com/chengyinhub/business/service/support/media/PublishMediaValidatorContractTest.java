package com.chengyinhub.business.service.support.media;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.chengyinhub.business.service.support.media.PublishMediaContract.*;

/** Explicit Java main contract tests. Surefire/JUnit does not execute this main automatically. */
public final class PublishMediaValidatorContractTest {
    static final long NOW = 1700000000000L;
    static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
    static final Context CONTEXT = new Context(71, Purpose.COMMUNITY_POST, "draft-test-a");
    static final String DIGEST = PublishMediaValidator.sha256("synthetic stored primary bytes");
    static final String POSTER_DIGEST = PublishMediaValidator.sha256("synthetic stored poster bytes");
    private static int checks;

    public static void main(String[] args) {
        check("image reference succeeds with explicit test policy and server evidence", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            expect(f.validate(), Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY);
            require(f.calls == 1, "one read-only resolution");
        });
        check("video plus owned reviewed poster succeeds only with explicit test opt-in", () -> {
            Fixture f = new Fixture(Kind.VIDEO);
            f.policy.video = true;
            expect(f.validate(), Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY);
        });
        check("professional topic isolated contract accepts correctly bound evidence", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            f.context = new Context(71, Purpose.PROFESSIONAL_TOPIC, "draft-test-a");
            f.policy.purpose = Purpose.PROFESSIONAL_TOPIC;
            f.asset.binding = binding(f.context, 101, "op-a");
            f.receiptBinding = f.asset.binding;
            expect(f.validate(), Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY);
        });
        check("default policy video off makes zero resolver calls", () -> {
            Fixture f = new Fixture(Kind.VIDEO);
            expect(f.validate(), Status.HOLD, Reason.VIDEO_DISABLED);
            require(f.calls == 0, "video was resolved while disabled");
            PublishMediaPolicy defaultPolicy = new PublishMediaPolicy() {
                public Purpose purpose() { return Purpose.COMMUNITY_POST; }
                public PolicyValidity validity() { return new PolicyValidity("fixture-v1", NOW - 1000, NOW + 10000); }
                public PolicyDecision validateAsset(AssetEvidence e, Role r) { return PolicyDecision.ACCEPT; }
                public PolicyDecision validateSelection(List<VerifiedMedia> e) { return PolicyDecision.ACCEPT; }
            };
            require(!defaultPolicy.videoEnabled(), "interface default enabled video");
        });
        check("missing provider fails closed before resolver", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            expect(new PublishMediaValidator(null, f.resolver(), CLOCK).validate(CONTEXT, f.refs()), Status.HOLD, Reason.POLICY_MISSING);
            require(f.calls == 0, "missing policy performed evidence lookup");
        });
        check("missing per-purpose policy", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            expect(new PublishMediaValidator(p -> null, f.resolver(), CLOCK).validate(CONTEXT, f.refs()), Status.HOLD, Reason.POLICY_MISSING);
        });
        check("wrong-purpose policy", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.policy.purpose = Purpose.PROFESSIONAL_TOPIC;
            expect(f.validate(), Status.HOLD, Reason.POLICY_MISMATCH);
        });
        check("throwing policy provider", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            expect(new PublishMediaValidator(p -> { throw new IllegalStateException(); }, f.resolver(), CLOCK)
                    .validate(CONTEXT, f.refs()), Status.HOLD, Reason.POLICY_UNAVAILABLE);
        });
        check("missing trusted resolver", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            expect(new PublishMediaValidator(p -> f.policy, null, CLOCK).validate(CONTEXT, f.refs()), Status.HOLD, Reason.EVIDENCE_MISSING);
        });
        check("missing clock", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            expect(new PublishMediaValidator(p -> f.policy, f.resolver(), null).validate(CONTEXT, f.refs()), Status.HOLD, Reason.CLOCK_UNAVAILABLE);
            require(f.calls == 0, "missing clock performed evidence lookup");
        });
        check("null context", () -> expect(new Fixture(Kind.IMAGE).validator().validate(null, Collections.emptyList()), Status.REJECTED, Reason.INVALID_CONTEXT));
        check("invalid principal", () -> expect(new Fixture(Kind.IMAGE).validator().validate(new Context(0, Purpose.COMMUNITY_POST, "draft"), Collections.emptyList()), Status.REJECTED, Reason.INVALID_CONTEXT));
        check("blank draft", () -> expect(new Fixture(Kind.IMAGE).validator().validate(new Context(71, Purpose.COMMUNITY_POST, " "), Collections.emptyList()), Status.REJECTED, Reason.INVALID_CONTEXT));
        check("null selection is not clear-all", () -> expect(new Fixture(Kind.IMAGE).validator().validate(CONTEXT, null), Status.REJECTED, Reason.INVALID_REFERENCE));
        check("empty selection still needs explicit policy decision", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.policy.selectionDecision = PolicyDecision.HOLD;
            expect(f.validator().validate(CONTEXT, Collections.emptyList()), Status.HOLD, Reason.POLICY_HOLD);
        });
        check("null reference", () -> expect(new Fixture(Kind.IMAGE).validator().validate(CONTEXT, Arrays.asList((Reference) null)), Status.REJECTED, Reason.INVALID_REFERENCE));
        for (Reference invalid : Arrays.asList(new Reference(0, "op-a", "receipt-a", Kind.IMAGE),
                new Reference(101, " op-a", "receipt-a", Kind.IMAGE), new Reference(101, "op-a", "", Kind.IMAGE),
                new Reference(101, "op-a", "receipt-a", null), new Reference(101, "op\na", "receipt-a", Kind.IMAGE))) {
            check("malformed reference rejected " + checks, () -> expect(new Fixture(Kind.IMAGE).validator().validate(CONTEXT, Arrays.asList(invalid)), Status.REJECTED, Reason.INVALID_REFERENCE));
        }
        for (Reference duplicate : Arrays.asList(new Reference(101, "op-b", "receipt-b", Kind.IMAGE),
                new Reference(102, "op-a", "receipt-b", Kind.IMAGE), new Reference(102, "op-b", "receipt-a", Kind.IMAGE))) {
            check("duplicate identity operation or receipt rejected before reads " + checks, () -> {
                Fixture f = new Fixture(Kind.IMAGE);
                expect(f.validator().validate(CONTEXT, Arrays.asList(f.reference, duplicate)), Status.REJECTED, Reason.DUPLICATE_REFERENCE);
                require(f.calls == 0, "duplicate preflight performed lookup");
            });
        }
        for (Binding wrong : Arrays.asList(new Binding(72, Purpose.COMMUNITY_POST, "draft-test-a", 101, "op-a"),
                new Binding(71, Purpose.PROFESSIONAL_TOPIC, "draft-test-a", 101, "op-a"),
                new Binding(71, Purpose.COMMUNITY_POST, "draft-test-b", 101, "op-a"),
                new Binding(71, Purpose.COMMUNITY_POST, "draft-test-a", 102, "op-a"),
                new Binding(71, Purpose.COMMUNITY_POST, "draft-test-a", 101, "op-b"))) {
            check("asset complete binding mismatch " + checks, () -> {
                Fixture f = new Fixture(Kind.IMAGE); f.asset.binding = wrong;
                expect(f.validate(), Status.REJECTED, Reason.BINDING_MISMATCH);
            });
            check("receipt complete binding mismatch " + checks, () -> {
                Fixture f = new Fixture(Kind.IMAGE); f.receiptBinding = wrong;
                expect(f.validate(), Status.REJECTED, Reason.BINDING_MISMATCH);
            });
        }
        check("claimed media kind cannot replace probed kind", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.asset.kind = Kind.VIDEO;
            expect(f.validate(), Status.REJECTED, Reason.BINDING_MISMATCH);
        });
        check("receipt kind bound", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.receiptKind = Kind.VIDEO;
            expect(f.validate(), Status.REJECTED, Reason.BINDING_MISMATCH);
        });
        check("opaque receipt hash bound", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.receiptHash = PublishMediaValidator.sha256("other receipt");
            expect(f.validate(), Status.REJECTED, Reason.RECEIPT_MISMATCH);
        });
        check("content digest bound", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.receiptDigest = POSTER_DIGEST;
            expect(f.validate(), Status.REJECTED, Reason.RECEIPT_MISMATCH);
        });
        check("missing receipt digest rejected", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.receiptDigest = null;
            expect(f.validate(), Status.REJECTED, Reason.RECEIPT_MISMATCH);
        });
        for (long expires : new long[] {NOW - 1, NOW}) {
            check("expired receipt rejected including exact boundary " + expires, () -> {
                Fixture f = new Fixture(Kind.IMAGE); f.expires = expires;
                expect(f.validate(), Status.REJECTED, Reason.RECEIPT_EXPIRED);
            });
        }
        check("receipt immediately before expiry is valid", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.expires = NOW + 1;
            expect(f.validate(), Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY);
        });
        check("receipt expiring during resolver is rejected", () -> {
            Fixture f = new Fixture(Kind.IMAGE); MutableClock clock = new MutableClock();
            PublishMediaValidator validator = new PublishMediaValidator(p -> f.policy,
                    (c, r) -> { clock.now = NOW + 1000; return Resolution.verified(f.evidence()); }, clock);
            expect(validator.validate(CONTEXT, f.refs()), Status.REJECTED, Reason.RECEIPT_EXPIRED);
        });
        check("receipt expiring during selection policy is rejected at final return", () -> {
            Fixture f = new Fixture(Kind.IMAGE); MutableClock clock = new MutableClock();
            f.policy.selectionHook = () -> clock.now = NOW + 1000;
            expect(new PublishMediaValidator(p -> f.policy, f.resolver(), clock).validate(CONTEXT, f.refs()), Status.REJECTED, Reason.RECEIPT_EXPIRED);
        });
        check("clock failure during receipt validation holds", () -> {
            Fixture f = new Fixture(Kind.IMAGE); MutableClock clock = new MutableClock();
            PublishMediaValidator validator = new PublishMediaValidator(p -> f.policy,
                    (c, r) -> { clock.unavailable = true; return Resolution.verified(f.evidence()); }, clock);
            expect(validator.validate(CONTEXT, f.refs()), Status.HOLD, Reason.CLOCK_UNAVAILABLE);
        });
        check("two receipts use one final decision sample with the earlier exclusive expiry", () -> {
            Fixture a = new Fixture(Kind.IMAGE); a.expires = NOW + 5;
            Fixture b = new Fixture(Kind.IMAGE); b.setIdentity(102, "op-b", "receipt-b"); b.expires = NOW + 1000;
            SequenceClock clock = new SequenceClock(NOW, NOW, NOW, NOW + 4, NOW + 6);
            Result result = pair(a, b, clock);
            expect(result, Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY);
            require(clock.reads == 4 && clock.last == NOW + 4, "final sweep sampled time separately per receipt");
            require(result.decisionBoundary.decisionAtMillis == clock.last, "decision time not final sample");
            require(result.decisionBoundary.validUntilExclusiveMillis == NOW + 5, "batch lost earliest receipt expiry");
            for (ValidatedReference ref : result.references) require(ref.receiptExpiresAtMillis > result.decisionBoundary.decisionAtMillis, "expired reference at decision time");
        });
        check("two receipts reject first expiry crossed at their common final decision instant", () -> {
            Fixture a = new Fixture(Kind.IMAGE); a.expires = NOW + 5;
            Fixture b = new Fixture(Kind.IMAGE); b.setIdentity(102, "op-b", "receipt-b");
            SequenceClock clock = new SequenceClock(NOW, NOW, NOW, NOW + 6);
            Result result = pair(a, b, clock);
            expect(result, Status.REJECTED, Reason.RECEIPT_EXPIRED);
            require(result.referenceIndex == 0 && result.decisionBoundary == null, "failed batch leaked valid decision boundary");
        });
        check("policy validity window is required", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.policy.validity = null;
            expect(f.validate(), Status.HOLD, Reason.POLICY_VALIDITY_MISSING);
            require(f.calls == 0, "missing policy validity performed resolution");
        });
        check("policy invalid window is not unlimited", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.policy.validity = new PolicyValidity("fixture", NOW, NOW);
            expect(f.validate(), Status.HOLD, Reason.POLICY_VALIDITY_MISSING);
        });
        check("policy future start rejects before resolver", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.policy.validity = new PolicyValidity("fixture", NOW + 1, NOW + 10);
            expect(f.validate(), Status.HOLD, Reason.POLICY_NOT_YET_VALID);
            require(f.calls == 0, "future policy performed resolution");
        });
        check("policy exact start is inclusive and its expiry limits the result", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.policy.validity = new PolicyValidity("fixture-v2", NOW, NOW + 5);
            Result result = f.validate(); expect(result, Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY);
            require(result.decisionBoundary.validUntilExclusiveMillis == NOW + 5 && result.decisionBoundary.policyVersion.equals("fixture-v2"), "policy boundary missing");
            require(result.decisionBoundary.policyNotBeforeMillis == NOW && result.decisionBoundary.policyExpiresAtMillis == NOW + 5, "policy window not recorded");
        });
        check("policy exact expiry is exclusive", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.policy.validity = new PolicyValidity("fixture", NOW - 10, NOW);
            expect(f.validate(), Status.HOLD, Reason.POLICY_EXPIRED);
            require(f.calls == 0, "expired policy performed resolution");
        });
        check("policy expiring during work holds the complete batch", () -> {
            Fixture a = new Fixture(Kind.IMAGE); a.policy.validity = new PolicyValidity("fixture", NOW - 1, NOW + 5);
            Fixture b = new Fixture(Kind.IMAGE); b.setIdentity(102, "op-b", "receipt-b");
            expect(pair(a, b, new SequenceClock(NOW, NOW, NOW, NOW + 5)), Status.HOLD, Reason.POLICY_EXPIRED);
        });
        check("UTC epoch decisions are independent of display timezone", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            Result utc = f.validate();
            Clock zoned = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneId.of("Pacific/Honolulu"));
            Result other = new PublishMediaValidator(p -> f.policy, f.resolver(), zoned).validate(CONTEXT, f.refs());
            expect(other, Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY);
            require(utc.decisionBoundary.decisionAtMillis == other.decisionBoundary.decisionAtMillis
                    && utc.decisionBoundary.validUntilExclusiveMillis == other.decisionBoundary.validUntilExclusiveMillis, "timezone changed authorization boundary");
        });
        List<java.util.function.Consumer<AssetBuilder>> invalidMetadata = Arrays.asList(
                a -> a.key = "https://storage.example.invalid/long-lived-url",
                a -> a.key = "media/../other", a -> a.key = "media/key?signature=client",
                a -> a.key = "/media/key", a -> a.key = "media//key", a -> a.mime = "video/mp4",
                a -> a.mime = "image/png;client=true", a -> a.bytes = 0, a -> a.width = 0,
                a -> a.height = -1, a -> a.duration = -1, a -> a.container = null,
                a -> a.codec = "", a -> a.audio = true, a -> a.version = 0);
        for (java.util.function.Consumer<AssetBuilder> mutate : invalidMetadata) {
            check("invalid server metadata rejected " + checks, () -> {
                Fixture f = new Fixture(Kind.IMAGE); mutate.accept(f.asset);
                expect(f.validate(), Status.REJECTED, Reason.INVALID_SERVER_METADATA);
            });
        }
        check("video duration must be present", () -> {
            Fixture f = new Fixture(Kind.VIDEO); f.policy.video = true; f.asset.duration = 0;
            expect(f.validate(), Status.REJECTED, Reason.INVALID_SERVER_METADATA);
        });
        for (Processing state : Arrays.asList(Processing.PENDING, Processing.FAILED, Processing.UNAVAILABLE, null)) {
            check("unready processing never passes " + state, () -> {
                Fixture f = new Fixture(Kind.IMAGE); f.asset.processing = state;
                expect(f.validate(), Status.HOLD, Reason.PROCESSING_NOT_READY);
            });
        }
        for (Review state : Arrays.asList(Review.PENDING, Review.REJECTED, Review.UNAVAILABLE, null)) {
            check("unapproved review never passes " + state, () -> {
                Fixture f = new Fixture(Kind.IMAGE); f.asset.review = state;
                expect(f.validate(), Status.HOLD, Reason.REVIEW_NOT_PASS);
            });
        }
        for (Retention state : Arrays.asList(Retention.REVOKED, Retention.DELETED, null)) {
            check("inactive media never passes " + state, () -> {
                Fixture f = new Fixture(Kind.IMAGE); f.asset.retention = state;
                expect(f.validate(), Status.REJECTED, Reason.MEDIA_NOT_ACTIVE);
            });
        }
        check("video requires server poster", () -> {
            Fixture f = new Fixture(Kind.VIDEO); f.policy.video = true; f.poster = null;
            expect(f.validate(), Status.HOLD, Reason.POSTER_MISSING);
        });
        check("poster owner bound", () -> {
            Fixture f = new Fixture(Kind.VIDEO); f.policy.video = true;
            f.poster.binding = new Binding(72, Purpose.COMMUNITY_POST, "draft-test-a", 201, "poster-a");
            expect(f.validate(), Status.REJECTED, Reason.POSTER_BINDING_MISMATCH);
        });
        check("poster draft bound", () -> {
            Fixture f = new Fixture(Kind.VIDEO); f.policy.video = true;
            f.poster.binding = new Binding(71, Purpose.COMMUNITY_POST, "draft-other", 201, "poster-a");
            expect(f.validate(), Status.REJECTED, Reason.POSTER_BINDING_MISMATCH);
        });
        check("poster purpose bound", () -> {
            Fixture f = new Fixture(Kind.VIDEO); f.policy.video = true;
            f.poster.binding = new Binding(71, Purpose.PROFESSIONAL_TOPIC, "draft-test-a", 201, "poster-a");
            expect(f.validate(), Status.REJECTED, Reason.POSTER_BINDING_MISMATCH);
        });
        check("poster parent bound", () -> {
            Fixture f = new Fixture(Kind.VIDEO); f.policy.video = true; f.posterParentId = 102;
            expect(f.validate(), Status.REJECTED, Reason.POSTER_BINDING_MISMATCH);
        });
        check("poster ID is bound in authenticated receipt", () -> {
            Fixture f = new Fixture(Kind.VIDEO); f.policy.video = true;
            f.receiptPosterBinding = binding(CONTEXT, 202, "poster-a");
            expect(f.validate(), Status.REJECTED, Reason.POSTER_BINDING_MISMATCH);
        });
        check("poster operation is bound in authenticated receipt", () -> {
            Fixture f = new Fixture(Kind.VIDEO); f.policy.video = true;
            f.receiptPosterBinding = binding(CONTEXT, 201, "poster-other");
            expect(f.validate(), Status.REJECTED, Reason.POSTER_BINDING_MISMATCH);
        });
        check("poster digest bound to authenticated primary receipt", () -> {
            Fixture f = new Fixture(Kind.VIDEO); f.policy.video = true; f.receiptPosterDigest = DIGEST;
            expect(f.validate(), Status.REJECTED, Reason.POSTER_BINDING_MISMATCH);
        });
        check("poster must be image", () -> {
            Fixture f = new Fixture(Kind.VIDEO); f.policy.video = true; f.poster.kind = Kind.VIDEO;
            expect(f.validate(), Status.REJECTED, Reason.POSTER_BINDING_MISMATCH);
        });
        check("poster pending review holds video", () -> {
            Fixture f = new Fixture(Kind.VIDEO); f.policy.video = true; f.poster.review = Review.PENDING;
            expect(f.validate(), Status.HOLD, Reason.REVIEW_NOT_PASS);
        });
        check("poster pending processing holds video", () -> {
            Fixture f = new Fixture(Kind.VIDEO); f.policy.video = true; f.poster.processing = Processing.PENDING;
            expect(f.validate(), Status.HOLD, Reason.PROCESSING_NOT_READY);
        });
        check("poster revoked holds back video", () -> {
            Fixture f = new Fixture(Kind.VIDEO); f.policy.video = true; f.poster.retention = Retention.REVOKED;
            expect(f.validate(), Status.REJECTED, Reason.MEDIA_NOT_ACTIVE);
        });
        check("null evidence resolution", () -> expect(new Fixture(Kind.IMAGE).withResolver((c, r) -> null), Status.HOLD, Reason.EVIDENCE_MISSING));
        check("null evidence body", () -> expect(new Fixture(Kind.IMAGE).withResolver((c, r) -> Resolution.verified(null)), Status.HOLD, Reason.EVIDENCE_MISSING));
        check("missing authenticated proof", () -> {
            Fixture f = new Fixture(Kind.IMAGE);
            expect(f.withResolver((c, r) -> Resolution.verified(new VerifiedMedia(f.asset.build(), null, null))), Status.HOLD, Reason.EVIDENCE_MISSING);
        });
        check("resolver exception fails closed", () -> expect(new Fixture(Kind.IMAGE).withResolver((c, r) -> { throw new IllegalStateException(); }), Status.HOLD, Reason.EVIDENCE_UNAVAILABLE));
        check("resolver rejection is retained", () -> expect(new Fixture(Kind.IMAGE).withResolver((c, r) -> Resolution.reject(Reason.EVIDENCE_REJECTED)), Status.REJECTED, Reason.EVIDENCE_REJECTED));
        check("resolver HOLD is retained", () -> expect(new Fixture(Kind.IMAGE).withResolver((c, r) -> Resolution.hold(Reason.EVIDENCE_UNAVAILABLE)), Status.HOLD, Reason.EVIDENCE_UNAVAILABLE));
        check("policy asset rejection", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.policy.assetDecision = PolicyDecision.REJECT;
            expect(f.validate(), Status.REJECTED, Reason.POLICY_REJECTED);
        });
        check("policy missing asset decision", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.policy.assetDecision = null;
            expect(f.validate(), Status.HOLD, Reason.POLICY_HOLD);
        });
        check("policy missing selection decision", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.policy.selectionDecision = null;
            expect(f.validate(), Status.HOLD, Reason.POLICY_HOLD);
        });
        check("policy exception fails closed", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.policy.throwAsset = true;
            expect(f.validate(), Status.HOLD, Reason.POLICY_UNAVAILABLE);
        });
        check("selection policy exception fails closed", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.policy.selectionHook = () -> { throw new IllegalStateException(); };
            expect(f.validate(), Status.HOLD, Reason.POLICY_UNAVAILABLE);
        });
        check("fixture byte limit is injected and enforced", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.policy.maxBytes = 3;
            expect(f.validate(), Status.REJECTED, Reason.POLICY_REJECTED);
            f.policy.maxBytes = 5000;
            expect(f.validate(), Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY);
        });
        check("fixture count limit is injected and enforced", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.policy.maxCount = 0;
            expect(f.validate(), Status.REJECTED, Reason.POLICY_REJECTED);
        });
        check("fixture duration limit is injected and enforced", () -> {
            Fixture f = new Fixture(Kind.VIDEO); f.policy.video = true; f.policy.maxDuration = 999;
            expect(f.validate(), Status.REJECTED, Reason.POLICY_REJECTED);
            f.policy.maxDuration = 1000;
            expect(f.validate(), Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY);
        });
        check("fixture width and height limits are injected", () -> {
            Fixture f = new Fixture(Kind.IMAGE); f.policy.maxWidth = 15;
            expect(f.validate(), Status.REJECTED, Reason.POLICY_REJECTED);
            f.policy.maxWidth = 16; f.policy.maxHeight = 15;
            expect(f.validate(), Status.REJECTED, Reason.POLICY_REJECTED);
        });
        check("fixture audio choice is explicit", () -> {
            Fixture f = new Fixture(Kind.VIDEO); f.policy.video = true; f.asset.audio = true;
            expect(f.validate(), Status.REJECTED, Reason.POLICY_REJECTED);
            f.policy.allowAudio = true;
            expect(f.validate(), Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY);
        });
        check("fixture codec container and detected MIME are matched as a tuple", () -> {
            Fixture f = new Fixture(Kind.VIDEO); f.policy.video = true; f.asset.codec = "png";
            expect(f.validate(), Status.REJECTED, Reason.POLICY_REJECTED);
            f.asset.codec = "test-codec"; f.asset.container = "png";
            expect(f.validate(), Status.REJECTED, Reason.POLICY_REJECTED);
            f.asset.container = "test-container"; f.asset.mime = "video/unapproved";
            expect(f.validate(), Status.REJECTED, Reason.POLICY_REJECTED);
        });
        check("poster passes its own injected policy", () -> {
            Fixture f = new Fixture(Kind.VIDEO); f.policy.video = true; f.poster.bytes = f.policy.maxBytes + 1;
            expect(f.validate(), Status.REJECTED, Reason.POLICY_REJECTED);
            require(f.policy.posterCalls == 1 && f.policy.primaryCalls == 0, "poster bypassed policy");
        });
        check("fixture mixing choice is explicit and keeps order", () -> {
            Fixture a = new Fixture(Kind.IMAGE); Fixture b = new Fixture(Kind.VIDEO);
            b.setIdentity(102, "op-b", "receipt-b"); b.posterParentId = 102; a.policy.video = true;
            PublishMediaValidator validator = new PublishMediaValidator(p -> a.policy,
                    (c, r) -> Resolution.verified(r.mediaId == 101 ? a.evidence() : b.evidence()), CLOCK);
            expect(validator.validate(CONTEXT, Arrays.asList(b.reference, a.reference)), Status.REJECTED, Reason.POLICY_REJECTED);
            a.policy.allowMixed = true;
            Result result = validator.validate(CONTEXT, Arrays.asList(b.reference, a.reference));
            expect(result, Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY);
            require(result.references.get(0).kind == Kind.VIDEO && result.references.get(1).kind == Kind.IMAGE, "mix reordered");
        });
        check("selection preserves user order and outputs immutable references", () -> {
            Fixture a = new Fixture(Kind.IMAGE);
            Fixture b = new Fixture(Kind.IMAGE); b.setIdentity(102, "op-b", "receipt-b");
            Fixture c = new Fixture(Kind.IMAGE); c.setIdentity(103, "op-c", "receipt-c");
            List<Reference> refs = new ArrayList<>(Arrays.asList(c.reference, a.reference, b.reference));
            PublishMediaValidator validator = new PublishMediaValidator(p -> a.policy, (ctx, ref) ->
                    Resolution.verified(ref.mediaId == 101 ? a.evidence() : ref.mediaId == 102 ? b.evidence() : c.evidence()), CLOCK);
            Result result = validator.validate(CONTEXT, refs);
            expect(result, Status.VALIDATED, Reason.CONTRACT_VALIDATED_ONLY);
            require(result.references.get(0).mediaId == 103 && result.references.get(1).mediaId == 101
                    && result.references.get(2).mediaId == 102, "order changed");
            refs.clear(); require(result.references.size() == 3, "output aliased input");
            try { result.references.clear(); throw new AssertionError("mutable output"); }
            catch (UnsupportedOperationException expected) { }
        });
        check("one failure never leaks partial accepted references", () -> {
            Fixture a = new Fixture(Kind.IMAGE);
            Reference b = new Reference(102, "op-b", "receipt-b", Kind.IMAGE);
            Result result = a.validator().validate(CONTEXT, Arrays.asList(a.reference, b));
            expect(result, Status.REJECTED, Reason.BINDING_MISMATCH);
            require(result.references.isEmpty() && result.referenceIndex == 1, "partial acceptance escaped");
        });
        System.out.println("PASS PublishMediaValidatorContractTest checks=" + checks + " runtime=" + System.getProperty("java.version"));
    }

    static final class Fixture {
        Context context = CONTEXT;
        Reference reference;
        AssetBuilder asset;
        AssetBuilder poster;
        Binding receiptBinding;
        Kind receiptKind;
        String receiptHash;
        String receiptDigest = DIGEST;
        String receiptPosterDigest;
        Binding receiptPosterBinding;
        long expires = NOW + 1000;
        long posterParentId = 101;
        final TestPolicy policy = new TestPolicy();
        int calls;

        Fixture(Kind kind) {
            reference = new Reference(101, "op-a", "receipt-a", kind);
            asset = new AssetBuilder(kind, binding(context, 101, "op-a"));
            receiptBinding = asset.binding;
            receiptKind = kind;
            receiptHash = PublishMediaValidator.sha256(reference.uploadReceipt);
            if (kind == Kind.VIDEO) {
                poster = new AssetBuilder(Kind.IMAGE, binding(context, 201, "poster-a"));
                poster.digest = POSTER_DIGEST; poster.key = "media/poster-a";
                receiptPosterDigest = POSTER_DIGEST;
                receiptPosterBinding = poster.binding;
            }
        }
        void setIdentity(long id, String operation, String receipt) {
            reference = new Reference(id, operation, receipt, asset.kind);
            asset.binding = binding(context, id, operation);
            asset.key = "media/" + operation;
            receiptBinding = asset.binding;
            receiptHash = PublishMediaValidator.sha256(receipt);
        }
        List<Reference> refs() { return Collections.singletonList(reference); }
        VerifiedMedia evidence() {
            return new VerifiedMedia(asset.build(), new ReceiptProof(receiptBinding, receiptHash, receiptKind,
                    receiptDigest, receiptPosterBinding, receiptPosterDigest, expires), poster == null ? null : new PosterEvidence(posterParentId, poster.build()));
        }
        EvidenceResolver resolver() { return (ctx, ref) -> { calls++; return Resolution.verified(evidence()); }; }
        PublishMediaValidator validator() { return new PublishMediaValidator(p -> policy, resolver(), CLOCK); }
        Result validate() { return validator().validate(context, refs()); }
        Result withResolver(EvidenceResolver resolver) { return new PublishMediaValidator(p -> policy, resolver, CLOCK).validate(context, refs()); }
    }

    /** All numbers and allowed types below are synthetic test values, never production policy. */
    static final class TestPolicy implements PublishMediaPolicy {
        Purpose purpose = Purpose.COMMUNITY_POST;
        boolean video;
        PolicyValidity validity = new PolicyValidity("fixture-v1", NOW - 1000, NOW + 10000);
        long maxBytes = 1000000;
        int maxCount = 4;
        int maxWidth = 1000;
        int maxHeight = 1000;
        long maxDuration = 3000;
        boolean allowAudio;
        boolean allowMixed;
        int posterCalls;
        int primaryCalls;
        Runnable selectionHook;
        PolicyDecision assetDecision = PolicyDecision.ACCEPT;
        PolicyDecision selectionDecision = PolicyDecision.ACCEPT;
        boolean throwAsset;
        public Purpose purpose() { return purpose; }
        public PolicyValidity validity() { return validity; }
        public boolean videoEnabled() { return video; }
        public PolicyDecision validateAsset(AssetEvidence evidence, Role role) {
            if (role == Role.POSTER) posterCalls++; else primaryCalls++;
            if (throwAsset) throw new IllegalStateException();
            boolean format = evidence.kind == Kind.IMAGE
                    ? "image/png".equals(evidence.detectedMimeType) && "png".equals(evidence.codec) && "png".equals(evidence.container)
                    : "video/test-format".equals(evidence.detectedMimeType) && "test-codec".equals(evidence.codec) && "test-container".equals(evidence.container);
            if (evidence.byteSize > maxBytes || evidence.width > maxWidth || evidence.height > maxHeight || evidence.durationMs > maxDuration
                    || (evidence.containsAudio && !allowAudio) || !format) return PolicyDecision.REJECT;
            return assetDecision;
        }
        public PolicyDecision validateSelection(List<VerifiedMedia> orderedEvidence) {
            if (selectionHook != null) selectionHook.run();
            if (orderedEvidence.size() > maxCount) return PolicyDecision.REJECT;
            if (!allowMixed && !orderedEvidence.isEmpty()) {
                Kind first = orderedEvidence.get(0).asset.kind;
                for (VerifiedMedia media : orderedEvidence) if (media.asset.kind != first) return PolicyDecision.REJECT;
            }
            return selectionDecision;
        }
    }

    static final class MutableClock extends Clock {
        long now = NOW;
        boolean unavailable;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(millis()); }
        public long millis() { if (unavailable) throw new IllegalStateException(); return now; }
    }

    private static Result pair(Fixture a, Fixture b, Clock clock) {
        return new PublishMediaValidator(p -> a.policy, (c, r) -> Resolution.verified(r.mediaId == a.reference.mediaId ? a.evidence() : b.evidence()), clock)
                .validate(a.context, Arrays.asList(a.reference, b.reference));
    }

    static final class SequenceClock extends Clock {
        final long[] values;
        int reads;
        long last;
        SequenceClock(long... values) { this.values = values; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(millis()); }
        public long millis() { last = values[Math.min(reads++, values.length - 1)]; return last; }
    }

    static final class AssetBuilder {
        Binding binding;
        Kind kind;
        String key = "media/op-a";
        String digest = DIGEST;
        String mime;
        long bytes = 1000;
        int width = 16;
        int height = 16;
        long duration;
        String container;
        String codec;
        boolean audio;
        Processing processing = Processing.READY;
        Review review = Review.PASS;
        Retention retention = Retention.ACTIVE;
        long version = 1;
        AssetBuilder(Kind kind, Binding binding) {
            this.kind = kind; this.binding = binding;
            mime = kind == Kind.IMAGE ? "image/png" : "video/test-format";
            duration = kind == Kind.IMAGE ? 0 : 1000;
            container = kind == Kind.IMAGE ? "png" : "test-container";
            codec = kind == Kind.IMAGE ? "png" : "test-codec";
        }
        AssetEvidence build() {
            return new AssetEvidence(binding, kind, key, digest, mime, bytes, width, height, duration,
                    container, codec, audio, processing, review, retention, version);
        }
    }

    static Binding binding(Context c, long id, String op) { return new Binding(c.ownerMemberId, c.purpose, c.draftId, id, op); }
    static void expect(Result result, Status status, Reason reason) {
        require(result.status == status && result.reason == reason, "expected " + status + "/" + reason + " got " + result.status + "/" + result.reason);
        if (status != Status.VALIDATED) require(result.references.isEmpty(), "failure returned references");
    }
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void check(String name, Runnable test) { test.run(); checks++; System.out.println("PASS " + name); }
}
