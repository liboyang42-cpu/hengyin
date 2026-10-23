package com.chengyinhub.business.service.support.rpg.mechanism;

/**
 * C03 exclusive trigger-actor projection. Records who explicitly chose a
 * mechanism. Does not default-fire, does not roll dice, does not write
 * {@code TopicRouteRuntimeServiceImpl}.
 */
public final class TriggerActorProjection {

    public enum Outcome {
        BOUND,
        REJECTED,
        UNKNOWN
    }

    public record ActorFact(
            String originalRequestId,
            String actorId,
            boolean explicitChoice,
            boolean defaultFired) {
    }

    public record View(
            Outcome outcome,
            String originalRequestId,
            String actorId,
            boolean defaultFired,
            String reason) {

        public View {
            outcome = outcome == null ? Outcome.UNKNOWN : outcome;
            reason = reason == null ? "" : reason;
        }
    }

    public View project(ActorFact fact) {
        if (fact == null) {
            return new View(Outcome.UNKNOWN, null, null, false, "actor fact missing");
        }
        if (fact.originalRequestId() == null || fact.originalRequestId().isBlank()) {
            return new View(Outcome.UNKNOWN, null, fact.actorId(), false, "originalRequestId missing");
        }
        if (fact.defaultFired()) {
            return new View(Outcome.REJECTED, fact.originalRequestId(), fact.actorId(), true,
                    "must not default-fire");
        }
        if (!fact.explicitChoice()) {
            return new View(Outcome.REJECTED, fact.originalRequestId(), fact.actorId(), false,
                    "player must explicitly choose");
        }
        if (fact.actorId() == null || fact.actorId().isBlank()) {
            return new View(Outcome.UNKNOWN, fact.originalRequestId(), null, false,
                    "trigger actor missing");
        }
        return new View(Outcome.BOUND, fact.originalRequestId(), fact.actorId(), false, "explicit actor bound");
    }
}
