package com.chengyinhub.business.citygame;

import java.util.Map;
import java.util.UUID;

/**
 * Server-side verification of an in-place task (challenge task §2.7 step 2, chance task §2.9 B).
 * The client never reports its own result. Without a bean, challenges skip the task step (modifier 0, no refund path)
 * and chance tasks cannot be completed (fail closed). The production bean is {@link CityOnSiteTaskService}
 * (challenge photo + presence tasks only).
 */
public interface CityTaskVerifier {
    /** @return the verified result of {@code submissionId} for the task issued under {@code taskRef}. */
    CityMonopolyRules.TaskResult verify(long memberId, String taskRef, String submissionId);

    /** Whether this verifier grades tasks of {@code taskRef}'s kind ({@code challenge:…}, {@code chance:…}). */
    default boolean supports(String taskRef) { return true; }

    /** The task block a challenge quote carries; at least {@code taskRef}. */
    default Map<String, Object> issue(String territoryId, Object title) {
        return Map.of("taskRef", "challenge:" + territoryId + ":" + UUID.randomUUID());
    }
}
