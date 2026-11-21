package com.chengyinhub.business.merchantproof;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.merchantplans.MerchantPlan;
import com.chengyinhub.business.merchantplans.MerchantPlanService;
import com.chengyinhub.business.merchantproof.MerchantWeeklyProof.Kind;
import com.chengyinhub.business.merchantproof.MerchantWeeklyProof.Touch;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** T5: plan feature weeklyProofDetail. Free (or no / unreadable plan) = today's response exactly. */
class WeeklyProofPlanDetailTest {
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 9);

    private static Instant at(int day, int hour) {
        return LocalDateTime.of(2026, 11, day, hour, 0).atZone(MerchantWeeklyProof.ZONE).toInstant();
    }

    private static MerchantWeeklyProofService service(MerchantPlanService plans) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> row = new HashMap<>();
        row.put("member_id", 7L);
        row.put("at", java.sql.Timestamp.from(Instant.now()));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(anyLong(), anyString())).thenReturn(new MerchantAccessContext(7L, 70L, 7L, "OWNER"));
        MerchantWeeklyProofService s = new MerchantWeeklyProofService(jdbc, access);
        if (plans != null) s.plans(plans);
        return s;
    }

    private static MerchantPlanService plan(Object detail) {
        MerchantPlanService plans = mock(MerchantPlanService.class);
        when(plans.feature(70L, MerchantPlan.F_WEEKLY_PROOF_DETAIL)).thenReturn(detail);
        return plans;
    }

    @Test
    void freeAndMissingAndBrokenPlansAnswerExactlyAsToday() {
        Map<String, Object> today = service(null).weekly(7L, null);
        assertFalse(today.containsKey("daily"));
        assertEquals(today, service(plan(false)).weekly(7L, null), "Free");
        assertEquals(today, service(plan(null)).weekly(7L, null), "feature missing");
        MerchantPlanService broken = mock(MerchantPlanService.class);
        when(broken.feature(anyLong(), anyString())).thenThrow(new RuntimeException("db down"));
        assertEquals(today, service(broken).weekly(7L, null), "plan unreadable");
        assertEquals(MerchantPlan.todaysFeatures().get(MerchantPlan.F_WEEKLY_PROOF_DETAIL), false);
    }

    @Test
    void aPlanWithDetailAddsTheDailyBlockAndKeepsTheSummary() {
        Map<String, Object> today = service(null).weekly(7L, null);
        Map<String, Object> detailed = service(plan(true)).weekly(7L, null);
        assertEquals(7, ((List<?>) detailed.get("daily")).size());
        Map<String, Object> rest = new LinkedHashMap<>(detailed);
        rest.remove("daily");
        assertEquals(today, rest);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dailySplitsTheWeekByLocalDay() {
        List<Touch> week = List.of(
                new Touch(1, at(9, 10), Kind.TICKET_REDEMPTION),
                new Touch(1, at(9, 11), Kind.COUPON_REDEMPTION),
                new Touch(2, at(9, 12), Kind.NODE_CHECKIN),
                new Touch(3, at(15, 23), Kind.NODE_REDEMPTION),
                new Touch(0, at(10, 9), Kind.COUPON_REDEMPTION));
        List<Map<String, Object>> days = MerchantWeeklyProof.daily(MONDAY, week);
        assertEquals("2026-11-09", days.get(0).get("date"));
        assertEquals(1, days.get(0).get("verifiedVisits"));
        assertEquals(2, days.get(0).get("redemptions"));
        assertEquals(1, days.get(0).get("playsAtNode"));
        assertEquals(2, days.get(0).get("players"));
        assertEquals(0, days.get(1).get("players"), "no-player rows ignored");
        assertEquals("2026-11-15", days.get(6).get("date"));
        assertEquals(1, days.get(6).get("verifiedVisits"));
        int sum = 0;
        for (Map<String, Object> d : days) sum += (Integer) d.get("verifiedVisits");
        Map<String, Object> summary = MerchantWeeklyProof.compute(MONDAY, week, java.util.Set.of(), true);
        assertEquals(((Map<String, Object>) summary.get("verifiedVisits")).get("count"), sum);
    }
}
