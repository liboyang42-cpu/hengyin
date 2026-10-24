package com.chengyinhub.business.service.support.rpg.adapter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * C02 exclusive adapter: project provided party membership. Does not invent a roster,
 * wallet, or second character ledger when the source is missing.
 */
public final class PartyFactAdapter {

    public enum Outcome {
        PRESENT,
        EMPTY,
        UNKNOWN
    }

    public record PartyFact(String originalRequestId, Set<Long> memberIds, boolean sourcePresent) {
        public PartyFact {
            originalRequestId = originalRequestId == null ? "" : originalRequestId;
            memberIds = memberIds == null ? Set.of() : Set.copyOf(memberIds);
        }
    }

    public record Decision(Outcome outcome, String originalRequestId, Set<Long> memberIds) {
        public Decision {
            outcome = outcome == null ? Outcome.UNKNOWN : outcome;
            originalRequestId = originalRequestId == null ? "" : originalRequestId;
            memberIds = memberIds == null ? Set.of() : Set.copyOf(memberIds);
        }
    }

    public Decision project(PartyFact fact) {
        if (fact == null || !fact.sourcePresent()) {
            return new Decision(Outcome.UNKNOWN, fact == null ? "" : fact.originalRequestId(), Set.of());
        }
        if (fact.memberIds().isEmpty()) {
            return new Decision(Outcome.EMPTY, fact.originalRequestId(), Set.of());
        }
        return new Decision(Outcome.PRESENT, fact.originalRequestId(), new LinkedHashSet<Long>(fact.memberIds()));
    }

    public boolean memberOf(PartyFact fact, Long memberId) {
        if (memberId == null) {
            return false;
        }
        Decision decision = project(fact);
        return decision.outcome() == Outcome.PRESENT && decision.memberIds().contains(memberId);
    }

    public Set<Long> membersOrUnknown(PartyFact fact) {
        Decision decision = project(fact);
        if (decision.outcome() != Outcome.PRESENT) {
            return Collections.emptySet();
        }
        return decision.memberIds();
    }

    @Override
    public String toString() {
        return Objects.toString(getClass().getSimpleName());
    }
}
