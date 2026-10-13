package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;

public final class ApprovedSaleCanonical {
    private final Long reviewTaskId;
    private final String schemaVersion;
    private final Long parameterVersion;
    private final TopicSaleCanonicalV1 canonical;

    public ApprovedSaleCanonical(Long reviewTaskId, String schemaVersion, Long parameterVersion,
                                 TopicSaleCanonicalV1 canonical) {
        this.reviewTaskId = reviewTaskId;
        this.schemaVersion = schemaVersion;
        this.parameterVersion = parameterVersion;
        this.canonical = canonical;
    }
    public Long getReviewTaskId() { return reviewTaskId; }
    public String getSchemaVersion() { return schemaVersion; }
    public Long getParameterVersion() { return parameterVersion; }
    public TopicSaleCanonicalV1 getCanonical() { return canonical; }
}
