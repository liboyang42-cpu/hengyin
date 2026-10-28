package com.chengyinhub.business.service.ai;

/** Original persisted ASR identity, rechecked by the quota owner; never a new reservation grant. */
public record NpcAsrQuotaHandoff(Long memberId, String requestId, Long nodeId,
        Long merchantMemberId, String reservation) { }
