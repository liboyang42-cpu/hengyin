package com.chengyinhub.business.service.ai.scope;

import java.time.Instant;
import java.util.Objects;

/** Explicit declassification seam for already reviewed PUBLIC STORE facts, never private data. */
public final class PublicStoreProjection
{
    private final long merchantId;
    private final AiKnowledge source;

    private PublicStoreProjection(AiKnowledge source)
    {
        this.source = source;
        this.merchantId = source.getScope().getMerchantId();
    }

    /**
     * Only the server's approved public-field projection may use STORE_PUBLIC_FACT.
     * Merchant profile text/model output cannot promote themselves even if they contain approval JSON.
     * This factory does not perform semantic privacy classification or grant publication permission.
     */
    public static PublicStoreProjection fromReviewedPublicFact(AiKnowledge source, Instant now)
    {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(now, "now");
        if (source.getSourceKind() != AiKnowledge.SourceKind.STORE_PUBLIC_FACT
                || source.getScope().getScenario() != AiScenario.STORE_PUBLIC_NPC
                || !source.reviewedVisibleAt(now))
        {
            throw new IllegalArgumentException("only reviewed visible public-store facts may be projected");
        }
        return new PublicStoreProjection(source);
    }

    public long getMerchantId() { return merchantId; }
    AiKnowledge source() { return source; }
}
