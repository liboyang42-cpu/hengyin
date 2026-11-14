package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.UsageConsentMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UsageConsentRecordsTest {
    @Test void documentAvailabilityNeverInventsSource() {
        assertEquals("MISSING", UsageConsentRecords.document("privacy_policy", "v1", null).get("availability"));
        Map<String, Object> draft = Map.of("status", 0, "title", "隐私", "content", "x", "publishTime", LocalDateTime.now());
        Map<String, Object> d = UsageConsentRecords.document("privacy_policy", "v1", draft);
        assertEquals("UNPUBLISHED", d.get("availability")); assertNull(d.get("title")); assertNull(d.get("content"));
        Map<String, Object> empty = new HashMap<>(Map.of("status", 1, "title", "隐私", "content", " ")); empty.put("publishTime", LocalDateTime.now());
        assertEquals("INCOMPLETE", UsageConsentRecords.document("privacy_policy", "v1", empty).get("availability"));
        Map<String, Object> ok = Map.of("status", 1, "title", "隐私", "content", "正文", "publishTime", LocalDateTime.now());
        assertEquals("SOURCE_AVAILABLE", UsageConsentRecords.document("privacy_policy", "v1", ok).get("availability"));
    }

    @Test void historyPagesByFiftyAndOriginalIs404WhenAbsent() {
        UsageConsentMapper m = mock(UsageConsentMapper.class);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 60; i > 10; i--) rows.add(Map.of("id", (long) i, "docType", "privacy", "docVersion", "1", "eventType", "AGREE", "requestId", "r" + i, "occurredAt", LocalDateTime.now()));
        when(m.selectHistory(9L, 70L)).thenReturn(rows);
        Map<String, Object> page = new UsageConsentRecords(m).history(9L, "70");
        assertEquals(11L, page.get("nextBeforeId")); assertEquals("UNPROVEN", page.get("nativeMarketBinding")); assertEquals(true, page.get("readOnly"));
        assertThrows(ServiceException.class, () -> new UsageConsentRecords(m).history(9L, "0"));
        assertEquals(404, assertThrows(ServiceException.class, () -> new UsageConsentRecords(m).original(9L, "nope")).getCode());
    }
}
