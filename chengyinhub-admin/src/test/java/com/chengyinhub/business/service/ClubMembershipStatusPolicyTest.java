package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubMembershipTerm;
import com.chengyinhub.business.service.impl.ClubMembershipStatusPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClubMembershipStatusPolicyTest {

    private final ClubMembershipStatusPolicy policy = new ClubMembershipStatusPolicy();

    @Test
    void statusBoundariesAreDeterministicAndDoNotGrantBenefitsPastGrace() {
        ClubMembershipTerm term = term(
                "2026-08-24T00:00:00Z",
                "2026-09-24T00:00:00Z",
                "2026-09-27T00:00:00Z",
                "2026-10-27T00:00:00Z");

        assertEquals("PENDING", at(term, "2026-08-23T23:59:59Z"));
        assertEquals("ACTIVE", at(term, "2026-08-24T00:00:00Z"));
        assertEquals("ACTIVE", at(term, "2026-09-23T23:59:59Z"));
        assertEquals("GRACE", at(term, "2026-09-24T00:00:00Z"));
        assertEquals("PAST_DUE", at(term, "2026-09-27T00:00:00Z"));
        assertEquals("EXPIRED", at(term, "2026-10-27T00:00:00Z"));
    }

    @Test
    void terminalStatesNeverComeBackWhenClockMoves() {
        ClubMembershipTerm term = term(
                "2026-08-24T00:00:00Z",
                "2026-09-24T00:00:00Z",
                "2026-09-27T00:00:00Z",
                "2026-10-27T00:00:00Z");
        term.setStatus("CANCELLED");
        assertEquals("CANCELLED", at(term, "2026-08-25T00:00:00Z"));
        term.setStatus("REFUNDED");
        assertEquals("REFUNDED", at(term, "2026-08-25T00:00:00Z"));
    }

    private String at(ClubMembershipTerm term, String instant) {
        return policy.evaluate(term, Instant.parse(instant));
    }

    private ClubMembershipTerm term(String startsAt, String endsAt, String graceEndsAt, String expiresAt) {
        ClubMembershipTerm term = new ClubMembershipTerm();
        term.setStatus("PENDING");
        term.setStartsAt(Date.from(Instant.parse(startsAt)));
        term.setEndsAt(Date.from(Instant.parse(endsAt)));
        term.setGraceEndsAt(Date.from(Instant.parse(graceEndsAt)));
        term.setExpiresAt(Date.from(Instant.parse(expiresAt)));
        return term;
    }
}
