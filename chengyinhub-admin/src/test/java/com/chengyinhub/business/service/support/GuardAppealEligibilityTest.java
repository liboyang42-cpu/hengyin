package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.PlayArriveGuardLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuardAppealEligibilityTest {
    private PlayArriveGuardLog log() {
        PlayArriveGuardLog row = new PlayArriveGuardLog(); row.setId(Long.MAX_VALUE); row.setMemberId(10L);
        row.setNodeId(41L); row.setVerdict("REJECT"); row.setReasonCode(ArriveLocationGuardPolicy.SPEED); return row;
    }
    private ObjectNode receipt() {
        ObjectNode node = new ObjectMapper().createObjectNode(); node.put("guardLogId", Long.toString(Long.MAX_VALUE));
        node.put("nodeId", "41"); node.put("routeSessionId", "32"); node.put("appealable", true);
        node.put("errorCode", ArriveLocationGuardPolicy.SPEED); node.put("appealBizType", ArriveLocationGuard.APPEAL_BIZ_TYPE); return node;
    }
    @Test void onlyExactOwnedRejectionAndOriginalRunCanBeUsed() {
        assertTrue(GuardAppealEligibility.matchesOwnedRejection(10L, log(), receipt()));
        assertFalse(GuardAppealEligibility.matchesOwnedRejection(11L, log(), receipt()));
        ObjectNode partial = receipt(); partial.remove("routeSessionId");
        assertFalse(GuardAppealEligibility.matchesOwnedRejection(10L, log(), partial));
        partial = receipt(); partial.put("guardLogId", Long.MAX_VALUE);
        assertFalse(GuardAppealEligibility.matchesOwnedRejection(10L, log(), partial));
        partial = receipt(); partial.put("nodeId", "42");
        assertFalse(GuardAppealEligibility.matchesOwnedRejection(10L, log(), partial));
        partial = receipt(); partial.put("runtimeInstance", "game:7");
        assertFalse(GuardAppealEligibility.matchesOwnedRejection(10L, log(), partial));
        partial.put("authorityEpoch", 4); assertTrue(GuardAppealEligibility.matchesOwnedRejection(10L, log(), partial));
        PlayArriveGuardLog throttle = log(); throttle.setReasonCode(ArriveLocationGuardPolicy.THROTTLE);
        assertFalse(GuardAppealEligibility.matchesOwnedRejection(10L, throttle, receipt()));
        PlayArriveGuardLog shadow = log(); shadow.setVerdict("SHADOW");
        assertFalse(GuardAppealEligibility.matchesOwnedRejection(10L, shadow, receipt()));
    }
    @Test void resultNeverAuthorizesEndedRunOrUnacceptedAppeal() {
        assertTrue(GuardAppealEligibility.mayReverify("ACCEPTED", true, true));
        assertFalse(GuardAppealEligibility.mayReverify("ACCEPTED", false, true));
        assertFalse(GuardAppealEligibility.mayReverify("ACCEPTED", true, false));
        for (String state : new String[]{"SUBMITTED", "REVIEWING", "REJECTED", "WITHDRAWN"})
            assertFalse(GuardAppealEligibility.mayReverify(state, true, true));
    }
    @Test void missingOrMalformedScopeDeniesEvenAdministrator() {
        assertFalse(GuardAppealReviewerScope.configured(null, 1L, 77L));
        assertFalse(GuardAppealReviewerScope.configured("{}", 1L, 77L));
        assertFalse(GuardAppealReviewerScope.configured("{\"1\":[77]}", 1L, 77L));
        assertFalse(GuardAppealReviewerScope.configured("{\"1\":[\"*\"]}", 1L, 77L));
        assertFalse(GuardAppealReviewerScope.configured("{\"1\":[\"77\",\"9223372036854775808\"]}", 1L, 77L));
        assertTrue(GuardAppealReviewerScope.configured("{\"1\":[\"77\"]}", 1L, 77L));
        assertFalse(GuardAppealReviewerScope.configured("{\"1\":[\"77\"]}", 2L, 77L));
        assertFalse(GuardAppealReviewerScope.configured("{\"1\":[\"77\"]}", 1L, 78L));
    }
}
