package com.chengyinhub.business.service.ai.scope;

import java.time.Instant;
import java.util.Objects;

/** Untrusted content and separate server-owned provenance. Text is never parsed as metadata. */
public final class AiKnowledge
{
    public enum SourceKind
    {
        PLATFORM_INTERNAL, CREATOR_AUTHORIZED, STORE_PUBLIC_FACT,
        MERCHANT_SALES, MERCHANT_EMPLOYEE, MERCHANT_CUSTOMER, MERCHANT_PRIVATE_OTHER,
        THEME_RELEASE, FREE_FORM_PROFILE, MODEL_OUTPUT
    }
    public enum Review { APPROVED, DRAFT, REJECTED }

    private final AiScope scope;
    private final SourceKind sourceKind;
    private final Review review;
    private final String reviewReference;
    private final String sourceId;
    private final String revision;
    private final String untrustedText;
    private final String contentHash;
    private final boolean unlocked;
    private final Instant validUntil;

    /** Server metadata only; a client-provided DTO must never be deserialized into this type. */
    public AiKnowledge(AiScope scope, SourceKind sourceKind, Review review, String reviewReference,
                       String sourceId, String revision, String untrustedText, boolean unlocked,
                       Instant validUntil)
    {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        this.review = Objects.requireNonNull(review, "review");
        this.reviewReference = review == Review.APPROVED
                ? AiContractSupport.text(reviewReference, "reviewReference") : null;
        this.sourceId = AiContractSupport.text(sourceId, "sourceId");
        this.revision = AiContractSupport.text(revision, "revision");
        this.untrustedText = AiContractSupport.text(untrustedText, "untrustedText");
        this.contentHash = AiContractSupport.sha256(untrustedText);
        this.unlocked = unlocked;
        this.validUntil = Objects.requireNonNull(validUntil, "validUntil");
    }

    public AiScope getScope() { return scope; }
    public SourceKind getSourceKind() { return sourceKind; }
    public Review getReview() { return review; }
    public String getSourceId() { return sourceId; }
    public String getUntrustedText() { return untrustedText; }
    public String getRevision() { return revision; }
    public String getContentHash() { return contentHash; }
    public Instant getValidUntil() { return validUntil; }

    boolean reviewedVisibleAt(Instant now)
    {
        return review == Review.APPROVED && unlocked && now.isBefore(validUntil)
                && scope.isActiveAt(now);
    }

    String fingerprint()
    {
        return AiContractSupport.fingerprint(scope.getFingerprint(), sourceKind, review, reviewReference,
                sourceId, revision, contentHash, unlocked, validUntil);
    }
}
