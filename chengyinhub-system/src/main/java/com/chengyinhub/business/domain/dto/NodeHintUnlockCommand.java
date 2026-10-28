package com.chengyinhub.business.domain.dto;

/** Actor is supplied by the trusted application caller, never HTTP body binding. */
public record NodeHintUnlockCommand(Long memberId, Long nodeId, String activityId, String topicId) {}
