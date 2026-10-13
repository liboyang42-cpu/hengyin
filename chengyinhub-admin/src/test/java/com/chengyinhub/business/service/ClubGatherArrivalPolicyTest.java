package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.service.support.ClubGatherArrivalPolicy;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class ClubGatherArrivalPolicyTest {
    private final ClubGatherArrivalPolicy policy = new ClubGatherArrivalPolicy();
    private final long now = 1800000000000L;

    private CmsActivity activity() {
        CmsActivity value = new CmsActivity(); value.setId(100L); value.setClubId(5L); value.setDelFlag(0);
        value.setStartDate(new Date(now + 3600000L)); value.setEndDate(new Date(now + 7200000L));
        value.setLatitude("0"); value.setLongitude("0"); return value;
    }

    @Test void exactlySixtyMinutesOpensButOneMillisecondEarlierDoesNot() {
        CmsActivity value = activity();
        assertNull(policy.validate(value, 0D, 0D, new Date(now)));
        assertNotNull(policy.validate(value, 0D, 0D, new Date(now - 1)));
    }

    @Test void exactFiveHundredMetersPassesWithoutRoundingOutsideIntoTheFence() {
        assertNull(policy.validate(activity(), Math.toDegrees(500D / 6371000D), 0D, new Date(now)));
        assertNotNull(policy.validate(activity(), Math.toDegrees(500.001D / 6371000D), 0D, new Date(now)));
        assertNotNull(policy.validate(activity(), Math.toDegrees(504D / 6371000D), 0D, new Date(now)));
    }

    @Test void afterGatheringStillAllowedBeforeActualEnd() {
        CmsActivity value = activity();
        assertNull(policy.validate(value, 0D, 0D, new Date(now + 3600000L)));
        assertNull(policy.validate(value, 0D, 0D, new Date(now + 7199999L)));
        assertNotNull(policy.validate(value, 0D, 0D, new Date(now + 7200000L)));
    }

    @Test void cancelledDeletedUnknownOrNonLeadActivityCannotCheckIn() {
        CmsActivity value = activity(); value.setCancelTime(new Date(now));
        assertNotNull(policy.validate(value, 0D, 0D, new Date(now)));
        value = activity(); value.setDelFlag(2); assertNotNull(policy.validate(value, 0D, 0D, new Date(now)));
        value.setDelFlag(null); assertNotNull(policy.validate(value, 0D, 0D, new Date(now)));
        value = activity(); value.setClubId(null); assertNotNull(policy.validate(value, 0D, 0D, new Date(now)));
        assertNotNull(policy.validate(null, 0D, 0D, new Date(now)));
    }

    @Test void missingAndInvalidTimesFailClosed() {
        CmsActivity value = activity(); value.setStartDate(null);
        assertNotNull(policy.validate(value, 0D, 0D, new Date(now)));
        value.setStartDate(new Date(0)); assertNotNull(policy.validate(value, 0D, 0D, new Date(now)));
        value = activity(); value.setEndDate(value.getStartDate());
        assertNotNull(policy.validate(value, 0D, 0D, new Date(now)));
        assertNotNull(policy.validate(activity(), 0D, 0D, null));
    }

    @Test void missingMalformedAndOutOfRangeMeetingPointNeverMeansZeroDistance() {
        for (String input : new String[]{null, "", "NaN", "Infinity", "abc", "91"}) {
            CmsActivity value = activity(); value.setLatitude(input);
            assertNotNull(policy.validate(value, 0D, 0D, new Date(now)), String.valueOf(input));
        }
        CmsActivity value = activity(); value.setLongitude("181");
        assertNotNull(policy.validate(value, 0D, 0D, new Date(now)));
    }

    @Test void missingNonFiniteAndIllegalReportedPositionFailsClosed() {
        for (Double input : new Double[]{null, Double.NaN, Double.POSITIVE_INFINITY, 91D, -91D})
            assertNotNull(policy.validate(activity(), input, 0D, new Date(now)));
        for (Double input : new Double[]{null, Double.NaN, Double.NEGATIVE_INFINITY, 181D, -181D})
            assertNotNull(policy.validate(activity(), 0D, input, new Date(now)));
    }
}
