package com.chengyinhub.business.service.support.appattest;

import java.time.Instant;

/** Validated evidence only; does not establish account ownership or physical activity. */
public final class EnrollmentResult
{
    private final byte[] publicKeySpki;
    private final String keyId;
    private final byte[] receipt;
    private final Instant receiptCreatedAt;
    private final Instant receiptExpiresAt;
    private final String bundleVersion;
    private final long distributionCategory;

    EnrollmentResult(byte[] publicKeySpki, String keyId, byte[] receipt, Instant receiptCreatedAt,
                     Instant receiptExpiresAt, String bundleVersion, long distributionCategory)
    {
        this.publicKeySpki = publicKeySpki.clone();
        this.keyId = keyId;
        this.receipt = receipt.clone();
        this.receiptCreatedAt = receiptCreatedAt;
        this.receiptExpiresAt = receiptExpiresAt;
        this.bundleVersion = bundleVersion;
        this.distributionCategory = distributionCategory;
    }

    public byte[] getPublicKeySpki() { return publicKeySpki.clone(); }
    public String getKeyId() { return keyId; }
    public byte[] getReceipt() { return receipt.clone(); }
    public Instant getReceiptCreatedAt() { return receiptCreatedAt; }
    public Instant getReceiptExpiresAt() { return receiptExpiresAt; }
    public String getReceiptEnvironment() { return "production"; }
    public String getReceiptType() { return "ATTEST"; }
    public String getBundleVersion() { return bundleVersion; }
    public long getDistributionCategory() { return distributionCategory; }
}
