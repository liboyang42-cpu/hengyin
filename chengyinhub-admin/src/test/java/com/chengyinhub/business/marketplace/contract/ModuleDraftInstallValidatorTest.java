package com.chengyinhub.business.marketplace.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.chengyinhub.business.marketplace.contract.ModuleInstallDeniedException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class ModuleDraftInstallValidatorTest {
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final long ACTOR = 42;
    private static final BuyerPrincipal BUYER = new BuyerPrincipal(BuyerPrincipal.Kind.MERCHANT, 7);
    private static final BuyerPrincipal SELLER = new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL, 8);
    private JsonNode fixture;
    private byte[] content;
    private byte[] termsBytes;
    private String contentHash;
    private String termsHash;
    private String termsDocumentHash;
    private ModuleVersionSnapshot source;
    private LicenseTermsSnapshot terms;
    private FakeAuthority authority;
    private ModuleDraftInstallValidator validator;
    private Set<ModuleInstallRequest.Change> changes;
    private Map<String, ModuleInstallRequest.ResourceRef> bindings;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/contracts/marketplace/inactive-module-license-v1.json")) {
            assertNotNull(input);
            fixture = new ObjectMapper().readTree(input);
        }
        content = fixture.path("canonicalModule").asText().getBytes(StandardCharsets.UTF_8);
        termsBytes = fixture.path("canonicalTerms").asText().getBytes(StandardCharsets.UTF_8);
        contentHash = ContractChecks.sha256(content);
        termsDocumentHash = ContractChecks.sha256(termsBytes);
        source = version("fixture-version-1", content, contentHash);
        terms = terms(LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION, 1, 1, 10,
                LicenseTermsSnapshot.Permission.ALLOWED, LicenseTermsSnapshot.Adaptation.LOCAL_ADAPTATION);
        termsHash = terms.getTermsHash();
        changes = EnumSet.of(ModuleInstallRequest.Change.BIND_RESOURCES);
        bindings = new LinkedHashMap<String, ModuleInstallRequest.ResourceRef>();
        bindings.put("start", new ModuleInstallRequest.ResourceRef(ModuleVersionSnapshot.BindingKind.LOCATION, 501));
        bindings.put("reward", new ModuleInstallRequest.ResourceRef(ModuleVersionSnapshot.BindingKind.PRODUCT, 601));
        authority = new FakeAuthority();
        authority.buyer = BUYER;
        authority.source = source;
        authority.grant = grant(BUYER, offer(LicenseOfferSnapshot.Acquisition.PAID, "paid-v1", terms),
                ModuleLicenseGrant.Status.ACTIVE, NOW.minusSeconds(60), NOW.plusSeconds(60),
                Collections.singletonMap(source.getVersionId(), contentHash));
        authority.draft = draft(BUYER, 1, "test-region", ModuleInstallAuthority.OwnedDraft.State.DRAFT);
        authority.usage = usage(Collections.<Long>emptySet(), Collections.<Long>emptySet(), 0);
        validator = new ModuleDraftInstallValidator(authority, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void paidVerifiedLicenseProducesOnlyInactiveOwnedDraftPlan() {
        DraftInstallPlan plan = preview(request(BUYER));
        assertEquals(ModuleInstallAuthority.OwnedDraft.State.DRAFT, plan.getResultState());
        assertFalse(plan.isExecutable());
        assertFalse(plan.isPublished());
        assertFalse(plan.grantsPlayerAdmission());
        assertEquals(ACTOR, plan.getAuthenticatedMemberId());
        assertEquals(101, plan.getTarget().getDraftId());
        assertEquals(contentHash, plan.getSource().getContentHash());
        assertEquals("reviewed-template-revision-5", plan.getSource().getSourceRevision());
        assertEquals(termsHash, plan.getGrant().getOffer().getTerms().getTermsHash());
        assertEquals(2, plan.getBindings().size());
        assertEquals(1, plan.getUsageRevision());
        assertEquals(NOW, plan.getCheckedAt());
    }

    @Test
    void freeGrantIsExplicitAndKeepsFrozenRightsAfterPaidOfferAppears() {
        LicenseOfferSnapshot free = offer(LicenseOfferSnapshot.Acquisition.FREE,
                fixture.path("frozenFreeOffer").path("version").asText(), terms);
        authority.grant = grant(BUYER, free, ModuleLicenseGrant.Status.ACTIVE, NOW.minusSeconds(1), null,
                Collections.singletonMap(source.getVersionId(), contentHash));
        LicenseOfferSnapshot laterPaid = offer(LicenseOfferSnapshot.Acquisition.PAID,
                fixture.path("laterPaidOffer").path("version").asText(), terms);
        assertEquals("paid-v2", laterPaid.getOfferVersion());
        DraftInstallPlan plan = preview(request(BUYER));
        assertEquals(0, plan.getGrant().getOffer().getPriceMinor());
        assertEquals(ModuleLicenseGrant.EvidenceKind.VERIFIED_FREE_CLAIM, plan.getGrant().getEvidenceKind());
        assertEquals("free-v1", plan.getGrant().getOffer().getOfferVersion());
    }

    @Test
    void paidFlagUseCountTicketAndRegistrationNeverSupplyGrantAuthority() {
        assertTrue(fixture.path("legacyClientHints").path("paid").asBoolean());
        assertTrue(fixture.path("legacyClientHints").path("use_num").asInt() > 0);
        assertTrue(fixture.path("legacyClientHints").has("registrationId"));
        ModuleInstallRequest request = request(BUYER);
        authority.grant = null;
        denied(LICENSE_NOT_VERIFIED, request);
    }

    @Test
    void freeOfferWithoutVerifiedClaimIsDeniedToo() {
        authority.grant = grant(BUYER, offer(LicenseOfferSnapshot.Acquisition.FREE, "free-v1", terms),
                ModuleLicenseGrant.Status.ACTIVE, NOW.minusSeconds(1), null,
                Collections.singletonMap(source.getVersionId(), contentHash));
        ModuleInstallRequest request = request(BUYER);
        authority.grant = null;
        denied(LICENSE_NOT_VERIFIED, request);
    }

    @Test
    void equalNumericIdsNeverCrossIndividualOrganizationMerchantScope() {
        for (BuyerPrincipal.Kind kind : BuyerPrincipal.Kind.values()) {
            BuyerPrincipal principal = new BuyerPrincipal(kind, 7);
            authority.buyer = principal;
            if (kind == BuyerPrincipal.Kind.MERCHANT) assertNotNull(preview(request(principal)));
            else denied(LICENSE_SCOPE_MISMATCH, request(principal));
        }
    }

    @Test
    void eachBuyerKindWorksOnlyWithItsOwnVerifiedGrantAndDraft() {
        for (BuyerPrincipal.Kind kind : BuyerPrincipal.Kind.values()) {
            BuyerPrincipal buyer = new BuyerPrincipal(kind, 7);
            authority.buyer = buyer;
            authority.draft = draft(buyer, 1, "test-region", ModuleInstallAuthority.OwnedDraft.State.DRAFT);
            authority.grant = grant(buyer, offer(LicenseOfferSnapshot.Acquisition.PAID, "paid-v1", terms),
                    ModuleLicenseGrant.Status.ACTIVE, NOW.minusSeconds(1), null,
                    Collections.singletonMap(source.getVersionId(), contentHash));
            assertEquals(buyer, preview(request(buyer)).getTarget().getOwner());
        }
    }

    @Test
    void unauthorizedOperatorCannotUseAValidBuyerLicense() {
        authority.buyer = null;
        denied(BUYER_SCOPE_DENIED, request(BUYER));
        assertEquals(0, authority.grantReads);
        assertThrows(ModuleInstallDeniedException.class, () -> validator.preview(999, request(BUYER)));
    }

    @Test
    void wrongReturnedLicenseCannotBeReused() {
        authority.grant = new ModuleLicenseGrant("another-license", BUYER, authority.grant.getOffer(),
                source.getVersionId(), authority.grant.getGrantedVersionHashes(), ModuleLicenseGrant.Status.ACTIVE,
                ModuleLicenseGrant.EvidenceKind.VERIFIED_PAID_ORDER_LINE, "order-line:2", "event:2",
                NOW.minusSeconds(1), null);
        ModuleInstallRequest request = request(BUYER);
        denied(LICENSE_NOT_VERIFIED, request);
    }

    @Test
    void suspendedRevokedExpiredAndFutureGrantsAreDenied() {
        LicenseOfferSnapshot offer = authority.grant.getOffer();
        for (ModuleLicenseGrant.Status status : Arrays.asList(ModuleLicenseGrant.Status.SUSPENDED,
                ModuleLicenseGrant.Status.REVOKED, ModuleLicenseGrant.Status.EXPIRED)) {
            authority.grant = grant(BUYER, offer, status, NOW.minusSeconds(1), null,
                    Collections.singletonMap(source.getVersionId(), contentHash));
            denied(LICENSE_INACTIVE, request(BUYER));
        }
        authority.grant = grant(BUYER, offer, ModuleLicenseGrant.Status.ACTIVE, NOW, NOW.plusSeconds(1),
                Collections.singletonMap(source.getVersionId(), contentHash));
        assertNotNull(preview(request(BUYER))); // inclusive start
        authority.grant = grant(BUYER, offer, ModuleLicenseGrant.Status.ACTIVE, NOW.minusSeconds(1), NOW,
                Collections.singletonMap(source.getVersionId(), contentHash));
        denied(LICENSE_INACTIVE, request(BUYER)); // exclusive end
        authority.grant = grant(BUYER, offer, ModuleLicenseGrant.Status.ACTIVE, NOW.plusSeconds(1), null,
                Collections.singletonMap(source.getVersionId(), contentHash));
        denied(LICENSE_INACTIVE, request(BUYER));
    }

    @Test
    void contentHashAndRequestedVersionAndModuleMustMatch() {
        denied(VERSION_MISMATCH, request(BUYER, "fixture-package", "fixture-version-1", repeat('0'), "paid-v1", termsHash, false));
        denied(VERSION_MISMATCH, request(BUYER, "fixture-package", "other-version", contentHash, "paid-v1", termsHash, false));
        denied(LICENSE_SCOPE_MISMATCH, request(BUYER, "other-package", "fixture-version-1", contentHash, "paid-v1", termsHash, false));
    }

    @Test
    void changedOfferOrTermsFailInsteadOfSilentlyChangingRights() {
        denied(TERMS_MISMATCH, request(BUYER, "fixture-package", "fixture-version-1", contentHash, "new-offer", termsHash, false));
        denied(TERMS_MISMATCH, request(BUYER, "fixture-package", "fixture-version-1", contentHash, "paid-v1", repeat('0'), false));
    }

    @Test
    void currentTakedownOrUnapprovedVersionIsDeniedWithoutInferringRevocationOfOldRuns() {
        authority.source = null;
        denied(VERSION_NOT_INSTALLABLE, request(BUYER));
    }

    @Test
    void updateRightsDoNotAutomaticallyGrantLatestVersion() {
        LicenseTermsSnapshot updateTerms = terms(LicenseTermsSnapshot.Updates.EXPLICIT_VERSION_GRANTS,
                1, 1, 10, LicenseTermsSnapshot.Permission.ALLOWED, LicenseTermsSnapshot.Adaptation.LOCAL_ADAPTATION);
        authority.grant = grant(BUYER, offer(LicenseOfferSnapshot.Acquisition.PAID, "paid-v1", updateTerms),
                ModuleLicenseGrant.Status.ACTIVE, NOW.minusSeconds(1), null,
                Collections.singletonMap(source.getVersionId(), contentHash));
        termsHash = updateTerms.getTermsHash();
        authority.source = version("fixture-version-2", content, contentHash);
        ModuleInstallRequest newer = request(BUYER, "fixture-package", "fixture-version-2", contentHash, "paid-v1", termsHash, false);
        denied(VERSION_NOT_GRANTED, newer);
        Map<String, String> explicit = new LinkedHashMap<String, String>(authority.grant.getGrantedVersionHashes());
        explicit.put("fixture-version-2", contentHash);
        authority.grant = grant(BUYER, authority.grant.getOffer(), ModuleLicenseGrant.Status.ACTIVE,
                NOW.minusSeconds(1), null, explicit);
        assertEquals("fixture-version-2", preview(newer).getSource().getVersionId());
        assertEquals("fixture-version-1", source.getVersionId());
    }

    @Test
    void exactVersionTermsRejectAnyAdditionalVersionGrant() {
        Map<String, String> versions = new LinkedHashMap<String, String>(authority.grant.getGrantedVersionHashes());
        versions.put("fixture-version-2", contentHash);
        assertThrows(IllegalArgumentException.class, () -> grant(BUYER, authority.grant.getOffer(),
                ModuleLicenseGrant.Status.ACTIVE, NOW.minusSeconds(1), null, versions));
    }

    @Test
    void grantedVersionCannotChangeBytesUnderSameVersionId() {
        byte[] changed = "changed bytes".getBytes(StandardCharsets.UTF_8);
        String changedHash = ContractChecks.sha256(changed);
        authority.source = version(source.getVersionId(), changed, changedHash);
        denied(VERSION_NOT_GRANTED, request(BUYER, "fixture-package", source.getVersionId(), changedHash, "paid-v1", termsHash, false));
    }

    @Test
    void onlyOwnedDraftWithExpectedRevisionCanBeTarget() {
        authority.draft = draft(SELLER, 1, "test-region", ModuleInstallAuthority.OwnedDraft.State.DRAFT);
        denied(DRAFT_SCOPE_DENIED, request(BUYER));
        for (ModuleInstallAuthority.OwnedDraft.State state : Arrays.asList(ModuleInstallAuthority.OwnedDraft.State.REVIEW,
                ModuleInstallAuthority.OwnedDraft.State.PUBLISHED, ModuleInstallAuthority.OwnedDraft.State.RETIRED)) {
            authority.draft = draft(BUYER, 1, "test-region", state);
            denied(DRAFT_NOT_EDITABLE, request(BUYER));
        }
        authority.draft = draft(BUYER, 2, "test-region", ModuleInstallAuthority.OwnedDraft.State.DRAFT);
        denied(DRAFT_REVISION_MISMATCH, request(BUYER));
    }

    @Test
    void regionCommercialAdaptationTranslationAndRedistributionAreExplicitPolicies() {
        authority.draft = draft(BUYER, 1, "other-region", ModuleInstallAuthority.OwnedDraft.State.DRAFT);
        denied(REGION_NOT_GRANTED, request(BUYER));
        authority.draft = draft(BUYER, 1, "test-region", ModuleInstallAuthority.OwnedDraft.State.DRAFT);
        terms = terms(LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION, 1, 1, 10,
                LicenseTermsSnapshot.Permission.PROHIBITED, LicenseTermsSnapshot.Adaptation.BIND_RESOURCES_ONLY);
        termsHash = terms.getTermsHash();
        authority.grant = grant(BUYER, offer(LicenseOfferSnapshot.Acquisition.PAID, "paid-v1", terms),
                ModuleLicenseGrant.Status.ACTIVE, NOW.minusSeconds(1), null,
                Collections.singletonMap(source.getVersionId(), contentHash));
        denied(COMMERCIAL_USE_DENIED, request(BUYER, "fixture-package", source.getVersionId(), contentHash, "paid-v1", termsHash, true));
        changes.add(ModuleInstallRequest.Change.ADAPT_CONTENT);
        denied(ADAPTATION_DENIED, request(BUYER));
        changes = EnumSet.of(ModuleInstallRequest.Change.TRANSLATE);
        denied(TRANSLATION_DENIED, request(BUYER));
        changes = EnumSet.of(ModuleInstallRequest.Change.REDISTRIBUTE);
        denied(REDISTRIBUTION_DENIED, request(BUYER));
    }

    @Test
    void everyPlaceholderMustBePresentAndHaveCorrectTypeWithNoExtras() {
        ModuleInstallRequest.ResourceRef original = bindings.remove("reward");
        denied(BINDING_MISMATCH, request(BUYER));
        bindings.put("reward", original);
        bindings.put("unexpected", original);
        denied(BINDING_MISMATCH, request(BUYER));
        bindings.remove("unexpected");
        bindings.put("reward", bindings.get("start"));
        denied(BINDING_MISMATCH, request(BUYER));
    }

    @Test
    void absentExpiredCrossBuyerCrossDraftCrossResourceAndCrossRegionApprovalsAreDenied() {
        for (int failure = 1; failure <= 7; failure++) {
            authority.bindingFailure = failure;
            denied(BINDING_NOT_APPROVED, request(BUYER));
        }
    }

    @Test
    void authoritativeUseLimitsCountDistinctThemesAndMerchants() {
        authority.usage = usage(Collections.singleton(201L), Collections.<Long>emptySet(), 0);
        denied(USE_LIMIT_EXCEEDED, request(BUYER));
        authority.usage = usage(Collections.singleton(101L), Collections.singleton(7L), 10);
        assertNotNull(preview(request(BUYER))); // same draft/store, no new run authorized
        authority.usage = usage(Collections.<Long>emptySet(), Collections.singleton(9L), 0);
        denied(USE_LIMIT_EXCEEDED, request(BUYER));
        authority.usage = usage(Collections.<Long>emptySet(), Collections.<Long>emptySet(), 11);
        denied(USE_LIMIT_EXCEEDED, request(BUYER));
        authority.usage = null;
        denied(USAGE_UNAVAILABLE, request(BUYER));
    }

    @Test
    void dependencyFailurePropagatesAndNeverBecomesApproval() {
        authority.failLookup = true;
        assertThrows(IllegalStateException.class, () -> preview(request(BUYER)));
    }

    @Test
    void snapshotCollectionsAndReturnedBindingsCannotBeMutated() {
        ModuleInstallRequest request = request(BUYER);
        bindings.clear();
        changes.add(ModuleInstallRequest.Change.REDISTRIBUTE);
        assertEquals(2, request.getBindings().size());
        assertFalse(request.getChanges().contains(ModuleInstallRequest.Change.REDISTRIBUTE));
        assertThrows(UnsupportedOperationException.class, () -> request.getBindings().clear());
        assertThrows(UnsupportedOperationException.class, () -> source.getRequiredBindings().clear());
        assertThrows(UnsupportedOperationException.class, () -> authority.grant.getGrantedVersionHashes().clear());
        assertThrows(UnsupportedOperationException.class, () -> terms.getAllowedRegions().clear());
        assertThrows(UnsupportedOperationException.class, () -> preview(request).getBindings().clear());
    }

    @Test
    void contentAndTermsRequireExactHashesAndExplicitPolicyValues() {
        assertThrows(IllegalArgumentException.class, () -> version("fixture-version-1", content, repeat('0')));
        assertThrows(IllegalArgumentException.class, () -> version("fixture-version-1", content, "legacy-no-hash"));
        assertThrows(IllegalArgumentException.class, () -> new LicenseTermsSnapshot("1", termsBytes, repeat('0'),
                "terms:1", LicenseTermsSnapshot.Permission.ALLOWED, LicenseTermsSnapshot.Adaptation.LOCAL_ADAPTATION,
                LicenseTermsSnapshot.Permission.PROHIBITED, LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION,
                LicenseTermsSnapshot.Redistribution.PROHIBITED, Collections.singleton("test-region"),
                LicenseTermsSnapshot.Limit.atMost(1), LicenseTermsSnapshot.Limit.atMost(1), LicenseTermsSnapshot.Limit.atMost(10)));
        assertThrows(NullPointerException.class, () -> terms(null, 1, 1, 10,
                LicenseTermsSnapshot.Permission.ALLOWED, LicenseTermsSnapshot.Adaptation.LOCAL_ADAPTATION));
        assertThrows(IllegalArgumentException.class, () -> LicenseTermsSnapshot.Limit.atMost(-1));
        assertTrue(LicenseTermsSnapshot.Limit.unlimited().permits(Long.MAX_VALUE));
        assertFalse(LicenseTermsSnapshot.Limit.atMost(0).permits(1));
    }

    @Test
    void freeAndPaidEvidenceCannotBeInterchanged() {
        assertThrows(IllegalArgumentException.class, () -> new ModuleLicenseGrant("license-1", BUYER,
                authority.grant.getOffer(), source.getVersionId(), authority.grant.getGrantedVersionHashes(),
                ModuleLicenseGrant.Status.ACTIVE, ModuleLicenseGrant.EvidenceKind.VERIFIED_FREE_CLAIM,
                "free-claim:1", "event:1", NOW, null));
        assertThrows(IllegalArgumentException.class, () -> new LicenseOfferSnapshot("offer-1", "1", "fixture-package",
                SELLER, EnumSet.allOf(BuyerPrincipal.Kind.class), LicenseOfferSnapshot.Acquisition.FREE, 1, "CNY", terms));
        assertThrows(IllegalArgumentException.class, () -> new LicenseOfferSnapshot("offer-1", "1", "fixture-package",
                SELLER, EnumSet.allOf(BuyerPrincipal.Kind.class), LicenseOfferSnapshot.Acquisition.PAID, 0, "CNY", terms));
    }

    @Test
    void sourceCreatorMustMatchFrozenSeller() {
        authority.source = new ModuleVersionSnapshot(BUYER, "fixture-package", source.getVersionId(), "1.0.0", 1,
                ModuleVersionSnapshot.SourceKind.MEMBER_TEMPLATE, 7001, "reviewed-template-revision-5",
                content, contentHash, "approved-review:2", source.getRequiredBindings());
        denied(SOURCE_AUTHOR_MISMATCH, request(BUYER));
    }

    @Test
    void termsDigestBindsEveryPolicyIncludingLimitsRatherThanOnlyDocumentBytes() {
        LicenseTermsSnapshot baseline = terms;
        assertEquals(termsDocumentHash, baseline.getTermsDocumentHash());
        assertNotEquals(termsDocumentHash, baseline.getTermsHash());
        assertEquals(baseline.getTermsHash(), terms(LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION,
                1, 1, 10, LicenseTermsSnapshot.Permission.ALLOWED, LicenseTermsSnapshot.Adaptation.LOCAL_ADAPTATION).getTermsHash());
        for (LicenseTermsSnapshot modified : Arrays.asList(
                terms(LicenseTermsSnapshot.Updates.EXPLICIT_VERSION_GRANTS, 1, 1, 10,
                        LicenseTermsSnapshot.Permission.ALLOWED, LicenseTermsSnapshot.Adaptation.LOCAL_ADAPTATION),
                terms(LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION, 2, 1, 10,
                        LicenseTermsSnapshot.Permission.ALLOWED, LicenseTermsSnapshot.Adaptation.LOCAL_ADAPTATION),
                terms(LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION, 1, 2, 10,
                        LicenseTermsSnapshot.Permission.ALLOWED, LicenseTermsSnapshot.Adaptation.LOCAL_ADAPTATION),
                terms(LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION, 1, 1, 11,
                        LicenseTermsSnapshot.Permission.ALLOWED, LicenseTermsSnapshot.Adaptation.LOCAL_ADAPTATION),
                terms(LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION, 1, 1, 10,
                        LicenseTermsSnapshot.Permission.PROHIBITED, LicenseTermsSnapshot.Adaptation.LOCAL_ADAPTATION),
                terms(LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION, 1, 1, 10,
                        LicenseTermsSnapshot.Permission.ALLOWED, LicenseTermsSnapshot.Adaptation.BIND_RESOURCES_ONLY))) {
            assertNotEquals(baseline.getTermsHash(), modified.getTermsHash());
            assertEquals(baseline.getTermsDocumentHash(), modified.getTermsDocumentHash());
        }
    }

    @Test
    void termsDigestCanonicalizesRegionOrderAndDefensivelyCopiesIt() {
        Set<String> forward = new java.util.LinkedHashSet<String>(Arrays.asList("region-a", "region-b"));
        Set<String> reverse = new java.util.LinkedHashSet<String>(Arrays.asList("region-b", "region-a"));
        LicenseTermsSnapshot a = regionTerms(forward);
        LicenseTermsSnapshot b = regionTerms(reverse);
        assertEquals(a.getTermsHash(), b.getTermsHash());
        forward.add("new-region");
        assertEquals(2, a.getAllowedRegions().size());
        assertNotEquals(a.getTermsHash(), regionTerms(forward).getTermsHash());
    }

    private LicenseTermsSnapshot regionTerms(Set<String> regions) {
        return new LicenseTermsSnapshot("terms-v1", termsBytes, termsDocumentHash, "terms-document:1",
                LicenseTermsSnapshot.Permission.ALLOWED, LicenseTermsSnapshot.Adaptation.LOCAL_ADAPTATION,
                LicenseTermsSnapshot.Permission.PROHIBITED, LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION,
                LicenseTermsSnapshot.Redistribution.PROHIBITED, regions, LicenseTermsSnapshot.Limit.atMost(1),
                LicenseTermsSnapshot.Limit.atMost(1), LicenseTermsSnapshot.Limit.atMost(10));
    }

    @Test
    void malformedUtf16IsRejectedWithoutReplacingOrNormalizingIdentity() {
        for (int code = Character.MIN_HIGH_SURROGATE; code <= Character.MAX_LOW_SURROGATE; code++) {
            final String loneSurrogate = "identity-" + (char) code;
            assertThrows(IllegalArgumentException.class, () -> ContractChecks.text(loneSurrogate, "identity"));
        }
        for (String malformed : Arrays.asList("\uD800a", "\uD800\uD801", "\uDC00\uD800",
                "a\uDC00b", "\uD83D\uDE00\uDC00", "\uD83D\uDE00\uD800")) {
            assertThrows(IllegalArgumentException.class, () -> ContractChecks.text(malformed, "identity"));
        }
        for (String valid : Arrays.asList("identity-?", "身份-\uD83D\uDE00", "\uD800\uDC00", "\uDBFF\uDFFF")) {
            assertEquals(valid, ContractChecks.text(valid, "identity"));
        }
    }

    @Test
    void malformedTextCannotEnterVersionGrantOfferRequestOrAuthoritySnapshots() {
        String invalid = "identity-\uD800";
        assertThrows(IllegalArgumentException.class, () -> version(invalid, content, contentHash));
        assertThrows(IllegalArgumentException.class, () -> offer(LicenseOfferSnapshot.Acquisition.FREE, invalid, terms));
        assertThrows(IllegalArgumentException.class, () -> request(BUYER, invalid, source.getVersionId(),
                contentHash, "paid-v1", termsHash, false));
        assertThrows(IllegalArgumentException.class, () -> new ModuleLicenseGrant(invalid, BUYER,
                authority.grant.getOffer(), source.getVersionId(), authority.grant.getGrantedVersionHashes(),
                ModuleLicenseGrant.Status.ACTIVE, ModuleLicenseGrant.EvidenceKind.VERIFIED_PAID_ORDER_LINE,
                "order-line:1", "event:1", NOW, null));
        assertThrows(IllegalArgumentException.class, () -> draft(BUYER, 1, invalid,
                ModuleInstallAuthority.OwnedDraft.State.DRAFT));
        assertThrows(IllegalArgumentException.class, () -> new ModuleInstallAuthority.BindingApproval(invalid,
                bindings.get("start"), BUYER, 101, null, "test-region", "approval:1", NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ModuleInstallAuthority.UsageSnapshot(invalid, 1,
                Collections.<Long>emptySet(), Collections.<Long>emptySet(), 0));
    }

    @Test
    void termsConstructorsRejectAllCollisionInputsBeforeDigestCreation() {
        for (String malformed : Arrays.asList("region-\uD800", "region-\uDC00")) {
            assertThrows(IllegalArgumentException.class, () -> textTerms(malformed, "reference", "region-?"));
            assertThrows(IllegalArgumentException.class, () -> textTerms("version", malformed, "region-?"));
            assertThrows(IllegalArgumentException.class, () -> textTerms("version", "reference", malformed));
        }
        assertNotNull(textTerms("version-?", "reference-?", "region-?"));
    }

    @Test
    void validSupplementaryPairsPreserveDistinctStableTermsDigests() {
        LicenseTermsSnapshot supplementary = textTerms("version-\uD83D\uDE00", "reference-\uD83D\uDE00", "region-\uD83D\uDE00");
        assertEquals("version-\uD83D\uDE00", supplementary.getTermsVersion());
        assertEquals("reference-\uD83D\uDE00", supplementary.getTermsDocumentReference());
        assertTrue(supplementary.getAllowedRegions().contains("region-\uD83D\uDE00"));
        assertEquals(supplementary.getTermsHash(),
                textTerms("version-\uD83D\uDE00", "reference-\uD83D\uDE00", "region-\uD83D\uDE00").getTermsHash());
        assertNotEquals(supplementary.getTermsHash(), textTerms("version-?", "reference-?", "region-?").getTermsHash());
        LicenseTermsSnapshot literal = textTerms("version", "reference", "region-?");
        assertNotEquals(literal.getTermsHash(), textTerms("version", "reference", "region-\uD83D\uDE00").getTermsHash());
        assertNotEquals(literal.getTermsHash(), textTerms("version", "reference", "region-\uD83D\uDE01").getTermsHash());
    }

    @Test
    void previewRejectsOriginalDigestWhenAuthorityReturnsChangedUnicodePolicy() {
        for (int changedField = 0; changedField < 3; changedField++) {
            LicenseTermsSnapshot original = textTerms("version-\uD83D\uDE00", "reference-\uD83D\uDE00", "region-\uD83D\uDE00");
            LicenseTermsSnapshot changed = textTerms(changedField == 0 ? "version-?" : original.getTermsVersion(),
                    changedField == 1 ? "reference-?" : original.getTermsDocumentReference(),
                    changedField == 2 ? "region-?" : "region-\uD83D\uDE00");
            authority.grant = grant(BUYER, offer(LicenseOfferSnapshot.Acquisition.PAID, "paid-v1", changed),
                    ModuleLicenseGrant.Status.ACTIVE, NOW.minusSeconds(1), null,
                    Collections.singletonMap(source.getVersionId(), contentHash));
            ModuleInstallRequest originalRequest = request(BUYER, "fixture-package", source.getVersionId(),
                    contentHash, "paid-v1", original.getTermsHash(), false);
            denied(TERMS_MISMATCH, originalRequest);
        }
    }

    @Test
    void malformedAuthorityPolicyFailsClosedInsteadOfYieldingAPlan() {
        authority.malformedPolicyOnRead = true;
        assertThrows(IllegalArgumentException.class, () -> preview(request(BUYER)));
    }

    private LicenseTermsSnapshot textTerms(String version, String reference, String region) {
        return new LicenseTermsSnapshot(version, termsBytes, termsDocumentHash, reference,
                LicenseTermsSnapshot.Permission.ALLOWED, LicenseTermsSnapshot.Adaptation.LOCAL_ADAPTATION,
                LicenseTermsSnapshot.Permission.PROHIBITED, LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION,
                LicenseTermsSnapshot.Redistribution.PROHIBITED, Collections.singleton(region),
                LicenseTermsSnapshot.Limit.atMost(1), LicenseTermsSnapshot.Limit.atMost(1), LicenseTermsSnapshot.Limit.atMost(10));
    }

    @Test
    void contractsAreFinalUnregisteredAndContainNoSourceAnswerPayload() throws Exception {
        assertTrue(Modifier.isFinal(ModuleVersionSnapshot.class.getModifiers()));
        assertTrue(Modifier.isFinal(ModuleLicenseGrant.class.getModifiers()));
        assertTrue(Modifier.isFinal(LicenseTermsSnapshot.class.getModifiers()));
        assertEquals(0, ModuleDraftInstallValidator.class.getAnnotations().length);
        assertEquals(0, ModuleInstallAuthority.class.getAnnotations().length);
        String serialized = new ObjectMapper().writeValueAsString(source);
        assertFalse(serialized.contains("fixture-answer"));
        assertFalse(serialized.contains("canonicalModule"));
        assertFalse(fixture.path("active").asBoolean());
    }

    private LicenseTermsSnapshot perpetualTerms() {
        return new LicenseTermsSnapshot(terms.getTermsVersion(), termsBytes, termsDocumentHash,
                terms.getTermsDocumentReference(), terms.getCommercialUse(), terms.getAdaptation(),
                terms.getTranslation(), terms.getUpdates(), terms.getRedistribution(), terms.getAllowedRegions(),
                terms.getThemeLimit(), terms.getMerchantLimit(), terms.getRunLimit(),
                LicenseTermsSnapshot.UseDuration.PERPETUAL_PURCHASED_VERSION);
    }

    private LicenseOfferSnapshot perpetualOffer() {
        LicenseTermsSnapshot perpetual = perpetualTerms();
        termsHash = perpetual.getTermsHash();
        return LicenseOfferSnapshot.oneTimePurchase("offer-1", "paid-v1", "fixture-package", SELLER,
                EnumSet.allOf(BuyerPrincipal.Kind.class), 1200, "CNY", perpetual);
    }

    @Test
    void perpetualPurchaseSurvivesElapsedTimeAndReconstructedReadService() {
        LicenseOfferSnapshot offer = perpetualOffer();
        authority.grant = grant(BUYER, offer, ModuleLicenseGrant.Status.ACTIVE, NOW, null,
                Collections.singletonMap(source.getVersionId(), contentHash));
        for (Instant time : Arrays.asList(NOW, NOW.plusSeconds(86400L * 365 * 100))) {
            // Fresh service models an authenticated read after restart/device change. There is no
            // subscription/device entitlement input capable of expiring the frozen purchased right.
            DraftInstallPlan plan = new ModuleDraftInstallValidator(authority, Clock.fixed(time, ZoneOffset.UTC))
                    .preview(ACTOR, request(BUYER));
            assertEquals(BUYER, plan.getGrant().getBuyer());
            assertEquals(source.getVersionId(), plan.getGrant().getPurchasedVersionId());
            assertEquals(contentHash, plan.getGrant().getGrantedVersionHashes().get(source.getVersionId()));
            assertEquals(LicenseTermsSnapshot.UseDuration.PERPETUAL_PURCHASED_VERSION,
                    plan.getGrant().getOffer().getTerms().getUseDuration());
            assertNull(plan.getGrant().getValidUntil());
            assertFalse(plan.isExecutable());
        }
    }

    @Test
    void perpetualDurationCannotBeTimedOrExpiredButRevocationStillDenies() {
        LicenseOfferSnapshot offer = perpetualOffer();
        Map<String,String> versions = Collections.singletonMap(source.getVersionId(), contentHash);
        assertThrows(IllegalArgumentException.class, () -> grant(BUYER, offer, ModuleLicenseGrant.Status.ACTIVE,
                NOW, NOW.plusSeconds(60), versions));
        assertThrows(IllegalArgumentException.class, () -> grant(BUYER, offer, ModuleLicenseGrant.Status.EXPIRED,
                NOW, null, versions));
        for (ModuleLicenseGrant.Status status : Arrays.asList(ModuleLicenseGrant.Status.SUSPENDED, ModuleLicenseGrant.Status.REVOKED)) {
            authority.grant = grant(BUYER, offer, status, NOW, null, versions);
            denied(LICENSE_INACTIVE, request(BUYER));
        }
        authority.grant = grant(BUYER, offer, ModuleLicenseGrant.Status.ACTIVE, NOW.plusSeconds(1), null, versions);
        denied(LICENSE_INACTIVE, request(BUYER));
    }

    @Test
    void perpetualPurchaseDoesNotExpandScopeOrRightsOrGrantLaterVersions() {
        terms = terms(LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION, 1, 1, 10,
                LicenseTermsSnapshot.Permission.PROHIBITED, LicenseTermsSnapshot.Adaptation.BIND_RESOURCES_ONLY);
        authority.grant = grant(BUYER, perpetualOffer(), ModuleLicenseGrant.Status.ACTIVE, NOW, null,
                Collections.singletonMap(source.getVersionId(), contentHash));
        denied(COMMERCIAL_USE_DENIED, request(BUYER, "fixture-package", source.getVersionId(), contentHash, "paid-v1", termsHash, true));
        changes = EnumSet.of(ModuleInstallRequest.Change.ADAPT_CONTENT);
        denied(ADAPTATION_DENIED, request(BUYER));
        changes = EnumSet.of(ModuleInstallRequest.Change.TRANSLATE);
        denied(TRANSLATION_DENIED, request(BUYER));
        changes = EnumSet.of(ModuleInstallRequest.Change.REDISTRIBUTE);
        denied(REDISTRIBUTION_DENIED, request(BUYER));
        changes = EnumSet.of(ModuleInstallRequest.Change.BIND_RESOURCES);
        authority.usage = usage(Collections.singleton(999L), Collections.emptySet(), 0);
        denied(USE_LIMIT_EXCEEDED, request(BUYER));
        authority.usage = usage(Collections.emptySet(), Collections.emptySet(), 0);
        authority.buyer = new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL, BUYER.getEntityId());
        denied(LICENSE_SCOPE_MISMATCH, request(authority.buyer));
        authority.buyer = BUYER;
        authority.source = version("fixture-version-2", content, contentHash);
        denied(VERSION_NOT_GRANTED, request(BUYER, "fixture-package", "fixture-version-2", contentHash, "paid-v1", termsHash, false));
        byte[] changed = "new bytes".getBytes(StandardCharsets.UTF_8);
        authority.source = version(source.getVersionId(), changed, ContractChecks.sha256(changed));
        denied(VERSION_NOT_GRANTED, request(BUYER, "fixture-package", source.getVersionId(), authority.source.getContentHash(), "paid-v1", termsHash, false));
    }

    @Test
    void durationIsHashBoundAndAvailableThroughExistingOfferReadContract() throws Exception {
        LicenseTermsSnapshot perpetual = perpetualTerms();
        assertNotEquals(terms.getTermsHash(), perpetual.getTermsHash());
        assertEquals(terms.getTermsDocumentHash(), perpetual.getTermsDocumentHash());
        ObjectMapper json = new ObjectMapper();
        assertFalse(json.valueToTree(terms).has("useDuration")); // strict v1 free-registry compatibility
        assertNull(terms.getUseDuration()); // legacy null expiry alone never implies approved perpetuity
        assertEquals("PERPETUAL_PURCHASED_VERSION", json.valueToTree(perpetualOffer())
                .path("terms").path("useDuration").asText());
        assertThrows(IllegalArgumentException.class, () -> LicenseOfferSnapshot.oneTimePurchase(
                "offer", "v1", "module", SELLER, EnumSet.allOf(BuyerPrincipal.Kind.class), 1200, "CNY", terms));
        assertThrows(IllegalArgumentException.class, () -> offer(LicenseOfferSnapshot.Acquisition.FREE, "free-v1", perpetual));
        authority.grant = grant(BUYER, perpetualOffer(), ModuleLicenseGrant.Status.ACTIVE, NOW, null,
                Collections.singletonMap(source.getVersionId(), contentHash));
        denied(TERMS_MISMATCH, request(BUYER, "fixture-package", source.getVersionId(), contentHash,
                "paid-v1", terms.getTermsHash(), false));
    }

    private DraftInstallPlan preview(ModuleInstallRequest request) { return validator.preview(ACTOR, request); }
    private void denied(ModuleInstallDeniedException.Reason expected, ModuleInstallRequest request) {
        assertEquals(expected, assertThrows(ModuleInstallDeniedException.class, () -> preview(request)).getReason());
    }
    private ModuleInstallRequest request(BuyerPrincipal buyer) {
        return request(buyer, "fixture-package", "fixture-version-1", contentHash,
                authority.grant == null ? "paid-v1" : authority.grant.getOffer().getOfferVersion(), termsHash, false);
    }
    private ModuleInstallRequest request(BuyerPrincipal buyer, String module, String version, String hash,
                                         String offer, String termsHash, boolean commercial) {
        return new ModuleInstallRequest(buyer, "request-1", "license-1", module, version, hash, offer, termsHash,
                101, 1, commercial, changes, bindings);
    }
    private ModuleVersionSnapshot version(String versionId, byte[] bytes, String hash) {
        Map<String, ModuleVersionSnapshot.BindingKind> placeholders = new LinkedHashMap<String, ModuleVersionSnapshot.BindingKind>();
        placeholders.put("start", ModuleVersionSnapshot.BindingKind.LOCATION);
        placeholders.put("reward", ModuleVersionSnapshot.BindingKind.PRODUCT);
        return new ModuleVersionSnapshot(SELLER, "fixture-package", versionId, "1.0.0", 1,
                ModuleVersionSnapshot.SourceKind.MEMBER_TEMPLATE, 7001, "reviewed-template-revision-5",
                bytes, hash, "approved-review:1", placeholders);
    }
    private LicenseTermsSnapshot terms(LicenseTermsSnapshot.Updates updates, long themes, long merchants, long runs,
                                        LicenseTermsSnapshot.Permission commercial, LicenseTermsSnapshot.Adaptation adaptation) {
        return new LicenseTermsSnapshot("terms-v1", termsBytes, termsDocumentHash, "terms-document:1", commercial, adaptation,
                LicenseTermsSnapshot.Permission.PROHIBITED, updates, LicenseTermsSnapshot.Redistribution.PROHIBITED,
                Collections.singleton("test-region"), LicenseTermsSnapshot.Limit.atMost(themes),
                LicenseTermsSnapshot.Limit.atMost(merchants), LicenseTermsSnapshot.Limit.atMost(runs));
    }
    private LicenseOfferSnapshot offer(LicenseOfferSnapshot.Acquisition kind, String version, LicenseTermsSnapshot terms) {
        return new LicenseOfferSnapshot("offer-1", version, "fixture-package", SELLER,
                EnumSet.allOf(BuyerPrincipal.Kind.class), kind, kind == LicenseOfferSnapshot.Acquisition.FREE ? 0 : 1200, "CNY", terms);
    }
    private ModuleLicenseGrant grant(BuyerPrincipal buyer, LicenseOfferSnapshot offer, ModuleLicenseGrant.Status status,
                                     Instant from, Instant until, Map<String, String> versions) {
        return new ModuleLicenseGrant("license-1", buyer, offer, "fixture-version-1", versions, status,
                offer.getAcquisition() == LicenseOfferSnapshot.Acquisition.FREE
                        ? ModuleLicenseGrant.EvidenceKind.VERIFIED_FREE_CLAIM : ModuleLicenseGrant.EvidenceKind.VERIFIED_PAID_ORDER_LINE,
                "verified-source:1", "source-event:1", from, until);
    }
    private ModuleInstallAuthority.OwnedDraft draft(BuyerPrincipal buyer, long revision, String region,
                                                   ModuleInstallAuthority.OwnedDraft.State state) {
        return new ModuleInstallAuthority.OwnedDraft(101, buyer, revision, region, state);
    }
    private ModuleInstallAuthority.UsageSnapshot usage(Set<Long> themes, Set<Long> merchants, long runs) {
        return new ModuleInstallAuthority.UsageSnapshot("license-1", 1, themes, merchants, runs);
    }
    private String repeat(char c) {
        char[] chars = new char[64];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private final class FakeAuthority implements ModuleInstallAuthority {
        BuyerPrincipal buyer;
        ModuleLicenseGrant grant;
        ModuleVersionSnapshot source;
        OwnedDraft draft;
        UsageSnapshot usage;
        int grantReads;
        int bindingFailure;
        boolean failLookup;
        boolean malformedPolicyOnRead;
        public BuyerPrincipal resolveBuyer(long actor, BuyerPrincipal requested) { return actor == ACTOR ? buyer : null; }
        public ModuleLicenseGrant findVerifiedGrant(String licenseId) {
            grantReads++;
            if (failLookup) throw new IllegalStateException("authoritative store unavailable");
            if (malformedPolicyOnRead) textTerms("version", "reference", "region-\uD800");
            return grant;
        }
        public ModuleVersionSnapshot findInstallableVersion(String versionId) { return source; }
        public OwnedDraft findDraft(long draftId) { return draft; }
        public UsageSnapshot findUsage(String licenseId) { return usage; }
        public BindingApproval approveBinding(BuyerPrincipal holder, long draftId, String placeholder,
                                              ModuleInstallRequest.ResourceRef resource, Instant now) {
            if (bindingFailure == 1) return null;
            return new BindingApproval(bindingFailure == 7 ? "wrong-placeholder" : placeholder,
                    bindingFailure == 4 ? new ModuleInstallRequest.ResourceRef(resource.getKind(), 999) : resource,
                    bindingFailure == 2 ? SELLER : holder, bindingFailure == 3 ? 999 : draftId, 7L,
                    bindingFailure == 5 ? "other-region" : "test-region", "approved-binding:1",
                    bindingFailure == 6 ? now : now.plusSeconds(60));
        }
    }
}
