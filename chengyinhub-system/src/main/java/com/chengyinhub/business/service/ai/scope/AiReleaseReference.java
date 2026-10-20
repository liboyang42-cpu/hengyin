package com.chengyinhub.business.service.ai.scope;

import java.util.Objects;

/** Server-owned release metadata, not evidence of entitlement or a client DTO. */
public final class AiReleaseReference
{
    public enum Readiness { INCOMPLETE, FROZEN_REVIEWED }

    private final long themeId;
    private final Long releaseId;
    private final String contentHash;
    private final int schemaVersion;
    private final Readiness readiness;

    public AiReleaseReference(long themeId, Long releaseId, String contentHash,
                              int schemaVersion, Readiness readiness)
    {
        this.themeId = AiContractSupport.positive(themeId, "themeId");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        if (releaseId != null) AiContractSupport.positive(releaseId, "releaseId");
        if (readiness == Readiness.FROZEN_REVIEWED && releaseId == null)
        {
            throw new IllegalArgumentException("assigned releaseId is required");
        }
        this.releaseId = releaseId;
        this.contentHash = AiContractSupport.hash(contentHash, "contentHash");
        if (schemaVersion != 1) throw new IllegalArgumentException("unsupported release schemaVersion");
        this.schemaVersion = schemaVersion;
    }

    public void requireFrozenReviewed()
    {
        if (readiness != Readiness.FROZEN_REVIEWED)
        {
            throw new IllegalArgumentException("incomplete release cannot scope AI execution");
        }
    }

    public long getThemeId() { return themeId; }
    public Long getReleaseId() { return releaseId; }
    public String getContentHash() { return contentHash; }
    public int getSchemaVersion() { return schemaVersion; }
    public Readiness getReadiness() { return readiness; }

    String fingerprint()
    {
        return AiContractSupport.fingerprint(themeId, releaseId, contentHash, schemaVersion, readiness);
    }
}
