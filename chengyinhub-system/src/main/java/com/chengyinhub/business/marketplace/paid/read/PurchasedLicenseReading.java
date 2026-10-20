package com.chengyinhub.business.marketplace.paid.read;
public interface PurchasedLicenseReading {
    PurchasedLicenseMetadata.Page list(long actor,Long beforeOrderLineId);
    PurchasedLicenseMetadata.Detail detail(long actor,String licenseId);
}
