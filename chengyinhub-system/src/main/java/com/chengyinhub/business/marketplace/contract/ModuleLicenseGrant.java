package com.chengyinhub.business.marketplace.contract;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Persisted creator-asset license snapshot supplied only by ModuleInstallAuthority.
 * Constructor validation is not evidence verification. The adapter must verify source event/order
 * line/free claim and current status in its authoritative store; HTTP fields never instantiate this.
 * Player tickets, registration, template use_num and client paid flags are intentionally unrepresentable.
 */
public final class ModuleLicenseGrant {
    public enum Status { ACTIVE, SUSPENDED, REVOKED, EXPIRED }
    public enum EvidenceKind { VERIFIED_FREE_CLAIM, VERIFIED_PAID_ORDER_LINE }
    private final String licenseId;
    private final BuyerPrincipal buyer;
    private final LicenseOfferSnapshot offer;
    private final String purchasedVersionId;
    private final Map<String, String> grantedVersionHashes;
    private final Status status;
    private final EvidenceKind evidenceKind;
    private final String authorityReference;
    private final String sourceEventId;
    private final Instant validFrom;
    private final Instant validUntil;

    /** validUntil is exclusive; null explicitly means no expiration in the frozen grant. */
    public ModuleLicenseGrant(String licenseId, BuyerPrincipal buyer, LicenseOfferSnapshot offer,
                              String purchasedVersionId, Map<String, String> grantedVersionHashes,
                              Status status, EvidenceKind evidenceKind, String authorityReference,
                              String sourceEventId, Instant validFrom, Instant validUntil) {
        this.licenseId = ContractChecks.text(licenseId, "licenseId");
        this.buyer = Objects.requireNonNull(buyer, "buyer");
        this.offer = Objects.requireNonNull(offer, "offer");
        if (!offer.getBuyerKinds().contains(buyer.getKind())) throw new IllegalArgumentException("buyer kind not offered");
        this.purchasedVersionId = ContractChecks.text(purchasedVersionId, "purchasedVersionId");
        this.grantedVersionHashes = ContractChecks.map(grantedVersionHashes, "grantedVersionHashes");
        if (!this.grantedVersionHashes.containsKey(purchasedVersionId)) throw new IllegalArgumentException("purchased version missing");
        for (Map.Entry<String, String> version : this.grantedVersionHashes.entrySet()) {
            ContractChecks.text(version.getKey(), "versionId");
            ContractChecks.hash(version.getValue());
        }
        if (offer.getTerms().getUpdates() == LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION
                && this.grantedVersionHashes.size() != 1) throw new IllegalArgumentException("update rights absent");
        this.status = Objects.requireNonNull(status, "status");
        this.evidenceKind = Objects.requireNonNull(evidenceKind, "evidenceKind");
        boolean free = offer.getAcquisition() == LicenseOfferSnapshot.Acquisition.FREE;
        if (free != (evidenceKind == EvidenceKind.VERIFIED_FREE_CLAIM)) {
            throw new IllegalArgumentException("grant evidence does not match frozen offer");
        }
        this.authorityReference = ContractChecks.text(authorityReference, "authorityReference");
        this.sourceEventId = ContractChecks.text(sourceEventId, "sourceEventId");
        this.validFrom = Objects.requireNonNull(validFrom, "validFrom");
        if (validUntil != null && !validUntil.isAfter(validFrom)) throw new IllegalArgumentException("invalid validity interval");
        if (offer.getTerms().getUseDuration() == LicenseTermsSnapshot.UseDuration.PERPETUAL_PURCHASED_VERSION
                && (validUntil != null || status == Status.EXPIRED)) {
            throw new IllegalArgumentException("perpetual purchased version cannot expire");
        }
        this.validUntil = validUntil;
    }

    public boolean isActiveAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return status == Status.ACTIVE && !now.isBefore(validFrom) && (validUntil == null || now.isBefore(validUntil));
    }
    public String getLicenseId() { return licenseId; }
    public BuyerPrincipal getBuyer() { return buyer; }
    public LicenseOfferSnapshot getOffer() { return offer; }
    public String getPurchasedVersionId() { return purchasedVersionId; }
    public Map<String, String> getGrantedVersionHashes() { return grantedVersionHashes; }
    public Status getStatus() { return status; }
    public EvidenceKind getEvidenceKind() { return evidenceKind; }
    public String getAuthorityReference() { return authorityReference; }
    public String getSourceEventId() { return sourceEventId; }
    public Instant getValidFrom() { return validFrom; }
    public Instant getValidUntil() { return validUntil; }
}
