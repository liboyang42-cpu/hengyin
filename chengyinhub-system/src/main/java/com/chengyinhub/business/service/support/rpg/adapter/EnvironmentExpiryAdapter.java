package com.chengyinhub.business.service.support.rpg.adapter;

import java.time.Instant;
import java.util.Objects;

/**
 * C02 exclusive adapter: equipment / status-duration expiry for RPG checks.
 * Uses server time only. Does not write {@code TopicRouteRuntimeServiceImpl}.
 * Missing source is UNKNOWN, never a successful grant. Client clock is ignored.
 */
public final class EnvironmentExpiryAdapter {

    public enum Outcome {
        ACTIVE,
        EXPIRED,
        UNKNOWN
    }

    public record TimedFact(String id, String kind, Instant expiresAtServer, String originalRequestId) {
        public TimedFact {
            id = id == null ? "" : id;
            kind = kind == null ? "" : kind;
            originalRequestId = originalRequestId == null ? "" : originalRequestId;
        }
    }

    public record Decision(Outcome outcome, String originalRequestId, boolean grantsCheckSuccess) {
        public Decision {
            outcome = outcome == null ? Outcome.UNKNOWN : outcome;
            originalRequestId = originalRequestId == null ? "" : originalRequestId;
        }
    }

    /**
     * @param clientNowIgnored present so callers cannot accidentally swap clocks; never used
     */
    public Decision evaluate(TimedFact fact, Instant serverNow, Instant clientNowIgnored) {
        Objects.requireNonNull(clientNowIgnored, "client clock must be passed so it can be ignored");
        if (fact == null || serverNow == null) {
            return new Decision(Outcome.UNKNOWN, fact == null ? "" : fact.originalRequestId(), false);
        }
        if (fact.expiresAtServer() == null || fact.id().isEmpty()) {
            return new Decision(Outcome.UNKNOWN, fact.originalRequestId(), false);
        }
        if (!serverNow.isBefore(fact.expiresAtServer())) {
            return new Decision(Outcome.EXPIRED, fact.originalRequestId(), false);
        }
        return new Decision(Outcome.ACTIVE, fact.originalRequestId(), true);
    }

    /**
     * Weather is a separate ENV_IN fact. Fake or unknown weather must not revive an
     * expired duration; it also cannot be used as the clock.
     */
    public boolean grantsCheckSuccess(TimedFact fact, Instant serverNow, Instant clientNowIgnored,
                                      String environmentWeather) {
        return evaluate(fact, serverNow, clientNowIgnored).grantsCheckSuccess();
    }
}
