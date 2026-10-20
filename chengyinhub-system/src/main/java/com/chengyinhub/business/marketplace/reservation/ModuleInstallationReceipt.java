package com.chengyinhub.business.marketplace.reservation;

import java.time.Instant;

/** Historical durable result only. Contains no licensed content, secrets or execution capability. */
public final class ModuleInstallationReceipt {
    private final String reservationKey;
    private final long ownedDraftId;
    private final Instant installedAt;
    private final String versionId;
    private final String contentHash;
    private final String termsHash;
    ModuleInstallationReceipt(String key, long draftId, Instant at, String version, String content, String terms) {
        reservationKey = key; ownedDraftId = draftId; installedAt = at;
        versionId = version; contentHash = content; termsHash = terms;
    }
    public String getReservationKey() { return reservationKey; }
    public long getOwnedDraftId() { return ownedDraftId; }
    public Instant getInstalledAt() { return installedAt; }
    public String getVersionId() { return versionId; }
    public String getContentHash() { return contentHash; }
    public String getTermsHash() { return termsHash; }
    public String getState() { return "DRAFT"; }
    public boolean isExecutable() { return false; }
    public boolean isPublished() { return false; }
    public boolean grantsPlayerAdmission() { return false; }
}
