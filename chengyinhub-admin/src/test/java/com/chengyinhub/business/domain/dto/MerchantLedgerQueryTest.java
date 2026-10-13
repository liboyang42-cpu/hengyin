package com.chengyinhub.business.domain.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MerchantLedgerQueryTest {

    @Test
    void resolvesTodayAndMonthAsHalfOpenTimeRanges() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 10, 14, 30);

        MerchantLedgerQuery.TimeRange today = MerchantLedgerQuery.resolveTimeRange("today", now);
        MerchantLedgerQuery.TimeRange month = MerchantLedgerQuery.resolveTimeRange("month", now);
        MerchantLedgerQuery.TimeRange all = MerchantLedgerQuery.resolveTimeRange("all", now);

        assertEquals(LocalDateTime.of(2026, 7, 10, 0, 0), today.getStart());
        assertEquals(LocalDateTime.of(2026, 7, 11, 0, 0), today.getEnd());
        assertEquals(LocalDateTime.of(2026, 7, 1, 0, 0), month.getStart());
        assertEquals(LocalDateTime.of(2026, 8, 1, 0, 0), month.getEnd());
        assertNull(all.getStart());
        assertNull(all.getEnd());
    }

    @Test
    void capsPageInputAndOnlyAllowsKnownStatusFilters() {
        MerchantLedgerQuery query = new MerchantLedgerQuery();
        query.setPageNum(0);
        query.setPageSize(500);
        query.setStatus("unexpected");

        assertEquals(1, query.safePageNum());
        assertEquals(50, query.safePageSize());
        assertEquals("all", query.safeStatus());
    }
}
