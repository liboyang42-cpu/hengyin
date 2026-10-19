package com.chengyinhub.business.marketplace.contract;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.TreeSet;
import java.util.Objects;
import java.util.Set;

/** Explicit reviewed policy, with no implicit commercial defaults. No automatic update/redistribution path. */
public final class LicenseTermsSnapshot {
    /** Duration only: no copyright transfer, expanded scope or future-version entitlement. */
    public enum UseDuration { PERPETUAL_PURCHASED_VERSION }
    public enum Permission { ALLOWED, PROHIBITED }
    public enum Adaptation { BIND_RESOURCES_ONLY, LOCAL_ADAPTATION }
    public enum Updates { EXACT_PURCHASED_VERSION, EXPLICIT_VERSION_GRANTS }
    public enum Redistribution { PROHIBITED }

    /** Zero is a valid explicit limit (e.g. no merchant use); unlimited is an explicit choice. */
    public static final class Limit {
        private final boolean unlimited;
        private final long maximum;
        private Limit(boolean unlimited, long maximum) {
            if (maximum < 0) throw new IllegalArgumentException("negative limit");
            this.unlimited = unlimited;
            this.maximum = maximum;
        }
        public static Limit unlimited() { return new Limit(true, 0); }
        public static Limit atMost(long maximum) { return new Limit(false, maximum); }
        public boolean isUnlimited() { return unlimited; }
        public long getMaximum() { return maximum; }
        public boolean permits(long count) { return count >= 0 && (unlimited || count <= maximum); }
    }

    private final UseDuration useDuration;
    private final String termsVersion;
    private final String termsHash;
    private final String termsDocumentHash;
    private final String termsDocumentReference;
    private final Permission commercialUse;
    private final Adaptation adaptation;
    private final Permission translation;
    private final Updates updates;
    private final Redistribution redistribution;
    private final Set<String> allowedRegions;
    private final Limit themeLimit;
    private final Limit merchantLimit;
    private final Limit runLimit;

    public LicenseTermsSnapshot(String termsVersion, byte[] approvedTerms, String approvedTermsDocumentHash,
                                String termsDocumentReference, Permission commercialUse,
                                Adaptation adaptation, Permission translation, Updates updates,
                                Redistribution redistribution, Set<String> allowedRegions,
                                Limit themeLimit, Limit merchantLimit, Limit runLimit) {
        this(termsVersion, approvedTerms, approvedTermsDocumentHash, termsDocumentReference, commercialUse,
                adaptation, translation, updates, redistribution, allowedRegions, themeLimit, merchantLimit, runLimit, null);
    }

    /**
     * Explicit reviewed duration. Null is historical/unspecified, never an inferred perpetual right.
     * Old snapshots retain their v1 digest and wire shape; explicit duration creates a v2 digest.
     */
    public LicenseTermsSnapshot(String termsVersion, byte[] approvedTerms, String approvedTermsDocumentHash,
                                String termsDocumentReference, Permission commercialUse,
                                Adaptation adaptation, Permission translation, Updates updates,
                                Redistribution redistribution, Set<String> allowedRegions,
                                Limit themeLimit, Limit merchantLimit, Limit runLimit, UseDuration useDuration) {
        this.useDuration = useDuration;
        this.termsVersion = ContractChecks.text(termsVersion, "termsVersion");
        this.termsDocumentHash = ContractChecks.hash(approvedTermsDocumentHash);
        if (!this.termsDocumentHash.equals(ContractChecks.sha256(approvedTerms))) {
            throw new IllegalArgumentException("terms hash mismatch");
        }
        this.termsDocumentReference = ContractChecks.text(termsDocumentReference, "termsDocumentReference");
        this.commercialUse = Objects.requireNonNull(commercialUse, "commercialUse");
        this.adaptation = Objects.requireNonNull(adaptation, "adaptation");
        this.translation = Objects.requireNonNull(translation, "translation");
        this.updates = Objects.requireNonNull(updates, "updates");
        this.redistribution = Objects.requireNonNull(redistribution, "redistribution");
        this.allowedRegions = ContractChecks.set(allowedRegions, "allowedRegions");
        if (this.allowedRegions.isEmpty()) throw new IllegalArgumentException("explicit regions required");
        for (String region : this.allowedRegions) ContractChecks.text(region, "region");
        this.themeLimit = Objects.requireNonNull(themeLimit, "themeLimit");
        this.merchantLimit = Objects.requireNonNull(merchantLimit, "merchantLimit");
        this.runLimit = Objects.requireNonNull(runLimit, "runLimit");
        this.termsHash = snapshotHash();
    }

    // Length-prefixed fields and sorted region set make identity independent of insertion order.
    // The digest binds both reviewed text and every typed policy; it is not a legal approval itself.
    private String snapshotHash() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            write(out, useDuration == null ? "W18-license-terms-snapshot-v1" : "W18-license-terms-snapshot-v2");
            if (useDuration != null) write(out, useDuration.name());
            write(out, termsVersion);
            write(out, termsDocumentHash);
            write(out, termsDocumentReference);
            write(out, commercialUse.name());
            write(out, adaptation.name());
            write(out, translation.name());
            write(out, updates.name());
            write(out, redistribution.name());
            out.writeInt(allowedRegions.size());
            for (String region : new TreeSet<String>(allowedRegions)) write(out, region);
            for (Limit limit : new Limit[] { themeLimit, merchantLimit, runLimit }) {
                out.writeBoolean(limit.isUnlimited());
                out.writeLong(limit.getMaximum());
            }
            out.flush();
            return ContractChecks.sha256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode in-memory terms snapshot", impossible);
        }
    }

    private static void write(DataOutputStream out, String field) throws IOException {
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /** UI/read consumers must not display lifetime ownership when this is absent. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public UseDuration getUseDuration() { return useDuration; }
    public String getTermsDocumentHash() { return termsDocumentHash; }
    public String getTermsVersion() { return termsVersion; }
    public String getTermsHash() { return termsHash; }
    public String getTermsDocumentReference() { return termsDocumentReference; }
    public Permission getCommercialUse() { return commercialUse; }
    public Adaptation getAdaptation() { return adaptation; }
    public Permission getTranslation() { return translation; }
    public Updates getUpdates() { return updates; }
    public Redistribution getRedistribution() { return redistribution; }
    public Set<String> getAllowedRegions() { return allowedRegions; }
    public Limit getThemeLimit() { return themeLimit; }
    public Limit getMerchantLimit() { return merchantLimit; }
    public Limit getRunLimit() { return runLimit; }
}
