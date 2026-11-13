package com.chengyinhub.business.service.channel.interaction;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Policy eligibility only. No reward is issued here. Dates must be resolved on the server
 * using the published policy timezone. A write needs a unique channel/member/date record,
 * an immutable policy version and existing reward-service idempotency in the same durable
 * workflow. Recovered existing records return their stored receipt, never a new award.
 */
public final class CheckinMakeupDecision {
    private CheckinMakeupDecision() { }
    public enum Result { ORIGINAL_RECEIPT_ONLY, ELIGIBLE, NOT_CONFIGURED, OUTSIDE_WINDOW }
    public record Policy(long version, boolean published, LocalDate firstDate, LocalDate lastDate) {
        public Policy {
            if (version <= 0) throw new IllegalArgumentException("Invalid policy version");
            Objects.requireNonNull(firstDate, "firstDate");
            Objects.requireNonNull(lastDate, "lastDate");
            if (firstDate.isAfter(lastDate)) throw new IllegalArgumentException("Invalid makeup window");
        }
    }

    public static Result decide(LocalDate date, LocalDate serverToday, Policy policy,
            boolean existingCheckinOrMakeup) {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(serverToday, "serverToday");
        // Original records remain recoverable even if today's policy changes or is removed.
        if (existingCheckinOrMakeup) return Result.ORIGINAL_RECEIPT_ONLY;
        if (policy == null || !policy.published()) return Result.NOT_CONFIGURED;
        if (!date.isBefore(serverToday) || date.isBefore(policy.firstDate())
                || date.isAfter(policy.lastDate())) return Result.OUTSIDE_WINDOW;
        return Result.ELIGIBLE;
    }
}
