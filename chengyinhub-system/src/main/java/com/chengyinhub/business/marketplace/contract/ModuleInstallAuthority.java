package com.chengyinhub.business.marketplace.contract;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Unimplemented server trust boundary. Implementations must use authenticated server context and
 * authoritative stores, never accept these snapshots or affirmative flags from client payloads.
 * Null means unavailable/denied; infrastructure failures must propagate, never become approval.
 * This read-only preview contract does not reserve quota, clone content or grant rights.
 */
public interface ModuleInstallAuthority {
    /** Resolve existing individual/organization/merchant authority; recheck revocations per call. */
    BuyerPrincipal resolveBuyer(long authenticatedMemberId, BuyerPrincipal requestedBuyer);

    /** Verify durable free-claim or paid-order-line/source-event evidence and current license status. */
    ModuleLicenseGrant findVerifiedGrant(String licenseId);

    /** Return exact immutable reviewed version only if rights/safety policy currently permits installation. */
    ModuleVersionSnapshot findInstallableVersion(String versionId);

    OwnedDraft findDraft(long draftId);

    /** Check real POI/location access and product/SKU merchant permissions plus current availability. */
    BindingApproval approveBinding(BuyerPrincipal buyer, long draftId, String placeholder,
                                   ModuleInstallRequest.ResourceRef requestedResource, Instant now);

    /** Read distinct already-consumed theme/merchant scope for this license, never use_num. */
    UsageSnapshot findUsage(String licenseId);

    final class OwnedDraft {
        public enum State { DRAFT, REVIEW, PUBLISHED, RETIRED }
        private final long draftId;
        private final BuyerPrincipal owner;
        private final long revision;
        private final String region;
        private final State state;
        public OwnedDraft(long draftId, BuyerPrincipal owner, long revision, String region, State state) {
            this.draftId = ContractChecks.positive(draftId, "draftId");
            this.owner = Objects.requireNonNull(owner, "owner");
            this.revision = ContractChecks.positive(revision, "revision");
            this.region = ContractChecks.text(region, "region");
            this.state = Objects.requireNonNull(state, "state");
        }
        public long getDraftId() { return draftId; }
        public BuyerPrincipal getOwner() { return owner; }
        public long getRevision() { return revision; }
        public String getRegion() { return region; }
        public State getState() { return state; }
    }

    final class BindingApproval {
        private final String placeholder;
        private final ModuleInstallRequest.ResourceRef resource;
        private final BuyerPrincipal buyer;
        private final long draftId;
        private final Long merchantId;
        private final String region;
        private final String authorityReference;
        private final Instant validUntil;
        public BindingApproval(String placeholder, ModuleInstallRequest.ResourceRef resource,
                               BuyerPrincipal buyer, long draftId, Long merchantId, String region,
                               String authorityReference, Instant validUntil) {
            this.placeholder = ContractChecks.text(placeholder, "placeholder");
            this.resource = Objects.requireNonNull(resource, "resource");
            this.buyer = Objects.requireNonNull(buyer, "buyer");
            this.draftId = ContractChecks.positive(draftId, "draftId");
            if (merchantId != null) ContractChecks.positive(merchantId, "merchantId");
            if (resource.getKind() == ModuleVersionSnapshot.BindingKind.PRODUCT && merchantId == null) {
                throw new IllegalArgumentException("product merchant required");
            }
            this.merchantId = merchantId;
            this.region = ContractChecks.text(region, "region");
            this.authorityReference = ContractChecks.text(authorityReference, "authorityReference");
            this.validUntil = Objects.requireNonNull(validUntil, "validUntil");
        }
        public String getPlaceholder() { return placeholder; }
        public ModuleInstallRequest.ResourceRef getResource() { return resource; }
        public BuyerPrincipal getBuyer() { return buyer; }
        public long getDraftId() { return draftId; }
        public Long getMerchantId() { return merchantId; }
        public String getRegion() { return region; }
        public String getAuthorityReference() { return authorityReference; }
        public Instant getValidUntil() { return validUntil; }
    }

    final class UsageSnapshot {
        private final String licenseId;
        private final long revision;
        private final Set<Long> themeIds;
        private final Set<Long> merchantIds;
        private final long completedOrReservedRuns;
        public UsageSnapshot(String licenseId, long revision, Set<Long> themeIds, Set<Long> merchantIds,
                             long completedOrReservedRuns) {
            this.licenseId = ContractChecks.text(licenseId, "licenseId");
            this.revision = ContractChecks.positive(revision, "revision");
            this.themeIds = ContractChecks.set(themeIds, "themeIds");
            this.merchantIds = ContractChecks.set(merchantIds, "merchantIds");
            for (Long id : this.themeIds) ContractChecks.positive(id, "themeId");
            for (Long id : this.merchantIds) ContractChecks.positive(id, "merchantId");
            if (completedOrReservedRuns < 0) throw new IllegalArgumentException("negative run usage");
            this.completedOrReservedRuns = completedOrReservedRuns;
        }
        public String getLicenseId() { return licenseId; }
        public long getRevision() { return revision; }
        public Set<Long> getThemeIds() { return themeIds; }
        public Set<Long> getMerchantIds() { return merchantIds; }
        public long getCompletedOrReservedRuns() { return completedOrReservedRuns; }
    }
}
