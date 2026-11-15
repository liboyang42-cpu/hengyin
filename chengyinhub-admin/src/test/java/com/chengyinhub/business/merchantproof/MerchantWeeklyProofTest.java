package com.chengyinhub.business.merchantproof;

import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.merchantproof.MerchantWeeklyProof.Kind;
import com.chengyinhub.business.merchantproof.MerchantWeeklyProof.Touch;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Q1 merchant-reach MER-07: weekly proof arithmetic and the returning-player cohort. */
class MerchantWeeklyProofTest {
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 9);

    private static Instant at(int day, int hour) {
        return LocalDateTime.of(2026, 11, day, hour, 0).atZone(MerchantWeeklyProof.ZONE).toInstant();
    }

    @Test
    void weekStartIsMondayInShanghai() {
        assertEquals(MONDAY, MerchantWeeklyProof.weekStart(LocalDate.of(2026, 11, 15)));
        assertEquals(MONDAY, MerchantWeeklyProof.weekStart(MONDAY));
        assertEquals("2026-11-08T16:00:00Z", MerchantWeeklyProof.startOf(MONDAY).toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void countsVerifiedVisitsPerPlayerPerDay() {
        List<Touch> week = List.of(
                new Touch(1, at(9, 10), Kind.TICKET_REDEMPTION),
                new Touch(1, at(9, 11), Kind.COUPON_REDEMPTION),   // same player, same day: one visit
                new Touch(1, at(10, 11), Kind.NODE_REDEMPTION),    // next day: second visit
                new Touch(2, at(11, 9), Kind.COUPON_REDEMPTION),
                new Touch(3, at(12, 9), Kind.NODE_CHECKIN),
                new Touch(3, at(12, 9), Kind.CITY_POINT_VISIT),
                new Touch(0, at(12, 9), Kind.COUPON_REDEMPTION));  // no player: ignored
        Map<String, Object> out = MerchantWeeklyProof.compute(MONDAY, week, Set.of(1L), true);
        assertEquals("REPORTED", out.get("status"));
        assertEquals("2026-11-09", out.get("weekStart"));
        assertEquals("2026-11-16", out.get("weekEnd"));
        assertEquals(3, ((Map<String, Object>) out.get("verifiedVisits")).get("count"));
        Map<String, Object> redemptions = (Map<String, Object>) out.get("redemptions");
        assertEquals(4, redemptions.get("count"));
        assertEquals(1, redemptions.get("ticket"));
        assertEquals(2, redemptions.get("coupon"));
        assertEquals(1, redemptions.get("cityNode"));
        Map<String, Object> plays = (Map<String, Object>) out.get("playsAtNode");
        assertEquals("MEASURED", plays.get("status"));
        assertEquals(2, plays.get("count"));
        assertEquals(1, plays.get("players"));
        Map<String, Object> cohort = (Map<String, Object>) out.get("returningPlayers");
        assertEquals("MEASURED", cohort.get("status"));
        assertEquals(MerchantWeeklyProof.COHORT_DEFINITION, cohort.get("definition"));
        assertEquals(3, cohort.get("players"));
        assertEquals(1, cohort.get("returning"));
        assertEquals(2, cohort.get("firstTime"));
        assertEquals(1, cohort.get("count"));
        assertEquals("1/3", cohort.get("rate"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void honestStatesWhenNothingToMeasure() {
        Map<String, Object> out = MerchantWeeklyProof.compute(MONDAY, List.of(), Set.of(), false);
        assertEquals("MISSING_COVERAGE", ((Map<String, Object>) out.get("playsAtNode")).get("status"));
        assertEquals(0, ((Map<String, Object>) out.get("verifiedVisits")).get("count"));
        Map<String, Object> cohort = (Map<String, Object>) out.get("returningPlayers");
        assertEquals("MISSING_COVERAGE", cohort.get("status"));
        assertFalse(cohort.containsKey("count"), "no made-up zero for an empty cohort");
    }

    @Test
    void weekParameterIsStrict() {
        LocalDate today = LocalDate.of(2026, 11, 15);
        assertEquals(MONDAY, MerchantWeeklyProofService.weekStart(null, today));
        assertEquals(LocalDate.of(2026, 11, 2), MerchantWeeklyProofService.weekStart(Map.of("weekStart", "2026-11-05"), today));
        assertThrows(MerchantGapException.class, () -> MerchantWeeklyProofService.weekStart(Map.of("weekStart", "2026-11-31"), today));
        assertThrows(MerchantGapException.class, () -> MerchantWeeklyProofService.weekStart(Map.of("weekStart", "2026-11-20"), today));
        assertThrows(MerchantGapException.class, () -> MerchantWeeklyProofService.weekStart(Map.of("weekStart", "2024-01-01"), today));
        assertThrows(MerchantGapException.class, () -> MerchantWeeklyProofService.weekStart(Map.of("merchantId", 9), today));
    }

    @Test
    void readsOnlyTheShopsOwnRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> row = new HashMap<>();
        row.put("member_id", 7L);
        row.put("at", java.sql.Timestamp.from(at(10, 12)));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));
        MerchantWeeklyProofService service = new MerchantWeeklyProofService(jdbc, null);
        List<Touch> touches = service.touches(11L, 22L, at(9, 0), at(16, 0));
        assertEquals(5, touches.size(), "one row from each of the five sources");
        verify(jdbc, times(3)).queryForList(contains("?"), eq(11L), any(), any());
        verify(jdbc, times(2)).queryForList(contains("merchant_id = ?"), eq(22L), any(), any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
