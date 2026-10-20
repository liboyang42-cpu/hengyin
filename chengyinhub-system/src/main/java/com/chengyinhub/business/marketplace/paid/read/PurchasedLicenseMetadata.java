package com.chengyinhub.business.marketplace.paid.read;

import com.chengyinhub.business.marketplace.contract.*;
import java.time.Instant;
import java.util.*;

/** New owner metadata projection. Never serializes the complete grant or payment/source evidence. */
public final class PurchasedLicenseMetadata {
    private final String licenseId,moduleId,purchasedVersionId,contentHash,status,acquiredAt,termsVersion,termsHash;
    private final String commercialUse,adaptation,translation,updates,redistribution;
    private final Set<String> allowedRegions;
    private final Limit themeLimit,merchantLimit,runLimit;
    PurchasedLicenseMetadata(ModuleLicenseGrant grant) {
        LicenseTermsSnapshot terms=grant.getOffer().getTerms();
        if(grant.getEvidenceKind()!=ModuleLicenseGrant.EvidenceKind.VERIFIED_PAID_ORDER_LINE
                || grant.getOffer().getAcquisition()!=LicenseOfferSnapshot.Acquisition.PAID
                || terms.getUseDuration()!=LicenseTermsSnapshot.UseDuration.PERPETUAL_PURCHASED_VERSION || grant.getValidUntil()!=null
                || grant.getBuyer().getKind()!=BuyerPrincipal.Kind.INDIVIDUAL)
            throw new IllegalArgumentException("purchased-version metadata unavailable");
        licenseId=grant.getLicenseId();moduleId=grant.getOffer().getModuleId();purchasedVersionId=grant.getPurchasedVersionId();
        contentHash=grant.getGrantedVersionHashes().get(purchasedVersionId);status=grant.getStatus().name();acquiredAt=grant.getValidFrom().toString();
        termsVersion=terms.getTermsVersion();termsHash=terms.getTermsHash();commercialUse=terms.getCommercialUse().name();adaptation=terms.getAdaptation().name();
        translation=terms.getTranslation().name();updates=terms.getUpdates().name();redistribution=terms.getRedistribution().name();
        allowedRegions=Collections.unmodifiableSet(new TreeSet<String>(terms.getAllowedRegions()));
        if(!bounded(licenseId,96)||!bounded(moduleId,128)||!bounded(purchasedVersionId,128)||!bounded(termsVersion,128)
                || !contentHash.matches("[0-9a-f]{64}") || !termsHash.matches("[0-9a-f]{64}") || allowedRegions.size()>32)
            throw new IllegalArgumentException("metadata bounds");
        for(String region:allowedRegions)if(!bounded(region,128))throw new IllegalArgumentException("region bounds");
        themeLimit=new Limit(terms.getThemeLimit());merchantLimit=new Limit(terms.getMerchantLimit());runLimit=new Limit(terms.getRunLimit());
    }
    private static boolean bounded(String value,int max){return value!=null&&!value.isEmpty()&&value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=max;}
    public String getLicenseId(){return licenseId;} public String getModuleId(){return moduleId;}
    public String getPurchasedVersionId(){return purchasedVersionId;}public String getContentHash(){return contentHash;}
    public String getStatus(){return status;}public String getAcquiredAt(){return acquiredAt;}
    public String getAcquisition(){return "PAID";}public String getBuyerKind(){return "INDIVIDUAL";}
    public String getUseDuration(){return "PERPETUAL_PURCHASED_VERSION";}
    public String getTermsVersion(){return termsVersion;}public String getTermsHash(){return termsHash;}
    public String getCommercialUse(){return commercialUse;}public String getAdaptation(){return adaptation;}
    public String getTranslation(){return translation;}public String getUpdates(){return updates;}public String getRedistribution(){return redistribution;}
    public Set<String> getAllowedRegions(){return allowedRegions;}
    public Limit getThemeLimit(){return themeLimit;}public Limit getMerchantLimit(){return merchantLimit;}public Limit getRunLimit(){return runLimit;}
    public String getContentUseStatus(){return "PAID_INSTALL_AUTHORITY_UNAVAILABLE";}
    public String getPurchaseActionStatus(){return "CHANNEL_APPROVAL_REQUIRED";}
    @Override public String toString(){return "PurchasedLicenseMetadata[redacted]";}
    public static final class Limit {
        private final boolean unlimited;private final long maximum;
        Limit(LicenseTermsSnapshot.Limit value){unlimited=value.isUnlimited();maximum=value.getMaximum();}
        public boolean isUnlimited(){return unlimited;}public long getMaximum(){return maximum;}
    }
    public static final class Page {
        private final List<PurchasedLicenseMetadata> items;private final boolean hasMore;private final Long nextBeforeOrderLineId;private final String checkedAt;
        Page(List<PurchasedLicenseMetadata> items,Long next,Instant checkedAt){this.items=Collections.unmodifiableList(new ArrayList<PurchasedLicenseMetadata>(items));hasMore=next!=null;nextBeforeOrderLineId=next;this.checkedAt=checkedAt.toString();}
        public String getSchema(){return "workshop-purchased-owned-v1";}public String getScope(){return "PAID_INDIVIDUAL_PURCHASED_VERSION_METADATA_ONLY";}
        public List<PurchasedLicenseMetadata> getItems(){return items;}public boolean getHasMore(){return hasMore;}public Long getNextBeforeOrderLineId(){return nextBeforeOrderLineId;}public String getCheckedAt(){return checkedAt;}
    }
    public static final class Detail {
        private final PurchasedLicenseMetadata item;private final String checkedAt;
        Detail(PurchasedLicenseMetadata item,Instant checkedAt){this.item=item;this.checkedAt=checkedAt.toString();}
        public String getSchema(){return "workshop-purchased-owned-v1";}public String getScope(){return "PAID_INDIVIDUAL_PURCHASED_VERSION_METADATA_ONLY";}
        public PurchasedLicenseMetadata getItem(){return item;}public String getCheckedAt(){return checkedAt;}
    }
}
