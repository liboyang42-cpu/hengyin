package com.chengyinhub.business.marketplace.contract;

import java.util.Objects;
import java.util.Set;

/** Frozen acquisition terms. Price here is descriptive, never a payment or license grant. */
public final class LicenseOfferSnapshot {
    public enum Acquisition { FREE, PAID }
    private final String offerId;
    private final String offerVersion;
    private final String moduleId;
    private final BuyerPrincipal seller;
    private final Set<BuyerPrincipal.Kind> buyerKinds;
    private final Acquisition acquisition;
    private final long priceMinor;
    private final String currency;
    private final LicenseTermsSnapshot terms;

    public LicenseOfferSnapshot(String offerId, String offerVersion, String moduleId, BuyerPrincipal seller,
                                Set<BuyerPrincipal.Kind> buyerKinds, Acquisition acquisition,
                                long priceMinor, String currency, LicenseTermsSnapshot terms) {
        this.offerId = ContractChecks.text(offerId, "offerId");
        this.offerVersion = ContractChecks.text(offerVersion, "offerVersion");
        this.moduleId = ContractChecks.text(moduleId, "moduleId");
        this.seller = Objects.requireNonNull(seller, "seller");
        this.buyerKinds = ContractChecks.set(buyerKinds, "buyerKinds");
        if (this.buyerKinds.isEmpty()) throw new IllegalArgumentException("buyer kinds required");
        this.acquisition = Objects.requireNonNull(acquisition, "acquisition");
        if ((acquisition == Acquisition.FREE && priceMinor != 0)
                || (acquisition == Acquisition.PAID && priceMinor <= 0)) {
            throw new IllegalArgumentException("price does not match acquisition type");
        }
        this.priceMinor = priceMinor;
        if (currency == null || !currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("currency required");
        this.currency = currency;
        this.terms = Objects.requireNonNull(terms, "terms");
        if (terms.getUseDuration() != null && acquisition != Acquisition.PAID)
            throw new IllegalArgumentException("purchased-version duration requires paid offer");
    }

    /**
     * New Workshop purchase contract, not a payment operation. Historical constructors remain
     * readable without retroactively approving new terms. Price/currency must come from the issuer.
     */
    public static LicenseOfferSnapshot oneTimePurchase(String offerId, String offerVersion, String moduleId,
            BuyerPrincipal seller, Set<BuyerPrincipal.Kind> buyerKinds, long priceMinor, String currency,
            LicenseTermsSnapshot terms) {
        if (Objects.requireNonNull(terms, "terms").getUseDuration()
                != LicenseTermsSnapshot.UseDuration.PERPETUAL_PURCHASED_VERSION)
            throw new IllegalArgumentException("explicit perpetual purchased-version terms required");
        return new LicenseOfferSnapshot(offerId, offerVersion, moduleId, seller, buyerKinds,
                Acquisition.PAID, priceMinor, currency, terms);
    }

    public String getOfferId() { return offerId; }
    public String getOfferVersion() { return offerVersion; }
    public String getModuleId() { return moduleId; }
    public BuyerPrincipal getSeller() { return seller; }
    public Set<BuyerPrincipal.Kind> getBuyerKinds() { return buyerKinds; }
    public Acquisition getAcquisition() { return acquisition; }
    public long getPriceMinor() { return priceMinor; }
    public String getCurrency() { return currency; }
    public LicenseTermsSnapshot getTerms() { return terms; }
}
