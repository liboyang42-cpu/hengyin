package com.chengyinhub.business.marketplace.contract;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.chengyinhub.business.marketplace.contract.ModuleInstallDeniedException.Reason.*;

/**
 * INACTIVE W18 seam: pure read-only preflight, deliberately not a Spring bean or HTTP route.
 * The actor must come from server authentication when an adapter is eventually approved.
 * Does not mutate legacy templates, reserve usage, install assets, open payment or publish.
 */
public final class ModuleDraftInstallValidator {
    private final ModuleInstallAuthority authority;
    private final Clock clock;

    public ModuleDraftInstallValidator(ModuleInstallAuthority authority, Clock clock) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DraftInstallPlan preview(long authenticatedMemberId, ModuleInstallRequest request) {
        ContractChecks.positive(authenticatedMemberId, "authenticatedMemberId");
        Objects.requireNonNull(request, "request");
        Instant now = clock.instant();
        BuyerPrincipal buyer = authority.resolveBuyer(authenticatedMemberId, request.getBuyer());
        require(request.getBuyer().equals(buyer), BUYER_SCOPE_DENIED);
        ModuleLicenseGrant grant = authority.findVerifiedGrant(request.getLicenseId());
        require(grant != null && request.getLicenseId().equals(grant.getLicenseId()), LICENSE_NOT_VERIFIED);
        require(grant.isActiveAt(now), LICENSE_INACTIVE);
        require(buyer.equals(grant.getBuyer()), LICENSE_SCOPE_MISMATCH);
        LicenseOfferSnapshot offer = grant.getOffer();
        LicenseTermsSnapshot terms = offer.getTerms();
        require(request.getExpectedOfferVersion().equals(offer.getOfferVersion())
                && request.getExpectedTermsHash().equals(terms.getTermsHash()), TERMS_MISMATCH);
        require(request.getModuleId().equals(offer.getModuleId()), LICENSE_SCOPE_MISMATCH);
        ModuleVersionSnapshot source = authority.findInstallableVersion(request.getVersionId());
        require(source != null, VERSION_NOT_INSTALLABLE);
        require(offer.getSeller().equals(source.getCreator()), SOURCE_AUTHOR_MISMATCH);
        require(request.getModuleId().equals(source.getModuleId())
                && request.getVersionId().equals(source.getVersionId())
                && request.getExpectedContentHash().equals(source.getContentHash()), VERSION_MISMATCH);
        require(source.getContentHash().equals(grant.getGrantedVersionHashes().get(source.getVersionId())), VERSION_NOT_GRANTED);
        require(terms.getUpdates() == LicenseTermsSnapshot.Updates.EXPLICIT_VERSION_GRANTS
                || source.getVersionId().equals(grant.getPurchasedVersionId()), VERSION_NOT_GRANTED);

        ModuleInstallAuthority.OwnedDraft draft = authority.findDraft(request.getDraftId());
        require(draft != null && draft.getDraftId() == request.getDraftId()
                && buyer.equals(draft.getOwner()), DRAFT_SCOPE_DENIED);
        require(draft.getState() == ModuleInstallAuthority.OwnedDraft.State.DRAFT, DRAFT_NOT_EDITABLE);
        require(draft.getRevision() == request.getExpectedDraftRevision(), DRAFT_REVISION_MISMATCH);
        require(terms.getAllowedRegions().contains(draft.getRegion()), REGION_NOT_GRANTED);
        require(!request.isCommercialUse() || terms.getCommercialUse() == LicenseTermsSnapshot.Permission.ALLOWED,
                COMMERCIAL_USE_DENIED);
        require(!request.getChanges().contains(ModuleInstallRequest.Change.ADAPT_CONTENT)
                || terms.getAdaptation() == LicenseTermsSnapshot.Adaptation.LOCAL_ADAPTATION, ADAPTATION_DENIED);
        require(!request.getChanges().contains(ModuleInstallRequest.Change.TRANSLATE)
                || terms.getTranslation() == LicenseTermsSnapshot.Permission.ALLOWED, TRANSLATION_DENIED);
        require(!request.getChanges().contains(ModuleInstallRequest.Change.REDISTRIBUTE), REDISTRIBUTION_DENIED);
        require(source.getRequiredBindings().keySet().equals(request.getBindings().keySet()), BINDING_MISMATCH);

        Map<String, ModuleInstallAuthority.BindingApproval> bindings = new LinkedHashMap<String, ModuleInstallAuthority.BindingApproval>();
        for (Map.Entry<String, ModuleInstallRequest.ResourceRef> entry : request.getBindings().entrySet()) {
            ModuleInstallRequest.ResourceRef resource = entry.getValue();
            require(source.getRequiredBindings().get(entry.getKey()) == resource.getKind(), BINDING_MISMATCH);
            ModuleInstallAuthority.BindingApproval approved = authority.approveBinding(
                    buyer, draft.getDraftId(), entry.getKey(), resource, now);
            require(approved != null && approved.getPlaceholder().equals(entry.getKey())
                    && approved.getResource().equals(resource) && approved.getBuyer().equals(buyer)
                    && approved.getDraftId() == draft.getDraftId() && now.isBefore(approved.getValidUntil())
                    && approved.getRegion().equals(draft.getRegion()), BINDING_NOT_APPROVED);
            bindings.put(entry.getKey(), approved);
        }
        ModuleInstallAuthority.UsageSnapshot usage = authority.findUsage(grant.getLicenseId());
        require(usage != null && usage.getLicenseId().equals(grant.getLicenseId()), USAGE_UNAVAILABLE);
        Set<Long> themes = new LinkedHashSet<Long>(usage.getThemeIds());
        themes.add(draft.getDraftId());
        Set<Long> merchants = new LinkedHashSet<Long>(usage.getMerchantIds());
        if (buyer.getKind() == BuyerPrincipal.Kind.MERCHANT) merchants.add(buyer.getEntityId());
        for (ModuleInstallAuthority.BindingApproval binding : bindings.values()) {
            if (binding.getMerchantId() != null) merchants.add(binding.getMerchantId());
        }
        require(terms.getThemeLimit().permits(themes.size()) && terms.getMerchantLimit().permits(merchants.size())
                && terms.getRunLimit().permits(usage.getCompletedOrReservedRuns()), USE_LIMIT_EXCEEDED);
        return new DraftInstallPlan(authenticatedMemberId, request, source, grant, draft, bindings, usage.getRevision(), now);
    }

    private static void require(boolean condition, ModuleInstallDeniedException.Reason reason) {
        if (!condition) throw new ModuleInstallDeniedException(reason);
    }
}
