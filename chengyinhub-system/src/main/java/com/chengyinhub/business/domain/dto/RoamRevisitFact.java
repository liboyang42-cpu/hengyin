package com.chengyinhub.business.domain.dto;

import java.util.Date;

/** 复访报表只读事实；商家身份按 source_type + source_id 保持 source-scoped。 */
public final class RoamRevisitFact {
    private final Long memberId;
    private final Long sessionId;
    private final Integer sourceType;
    private final Long sourceId;
    private final Date occurredAt;

    public RoamRevisitFact(Long memberId, Long sessionId, Integer sourceType, Long sourceId,
                           Date occurredAt) {
        this.memberId = memberId;
        this.sessionId = sessionId;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.occurredAt = occurredAt == null ? null : new Date(occurredAt.getTime());
    }

    public Long getMemberId() { return memberId; }
    public Long getSessionId() { return sessionId; }
    public Integer getSourceType() { return sourceType; }
    public Long getSourceId() { return sourceId; }
    public Date getOccurredAt() { return occurredAt == null ? null : new Date(occurredAt.getTime()); }
}
