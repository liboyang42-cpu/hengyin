package com.chengyinhub.business.citygame;

/**
 * Server-side verification of an in-place task (challenge task §2.7 step 2, chance task §2.9 B).
 * The client never reports its own result. Without a bean, challenges skip the task step (modifier 0, no refund path)
 * and chance tasks cannot be completed (fail closed).
 */
public interface CityTaskVerifier {
    /** @return the verified result of {@code submissionId} for the task issued under {@code taskRef}. */
    CityMonopolyRules.TaskResult verify(long memberId, String taskRef, String submissionId);
}
