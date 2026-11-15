package com.chengyinhub.business.channelevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;

class ChannelEventDraftTest
{
    static final long HOUR = 3600_000L;
    static final Date NOW = new Date(1_800_000_000_000L);

    static Map<String, Object> form()
    {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("title", "周六夜跑");
        b.put("startAt", NOW.getTime() + 24 * HOUR);
        b.put("endAt", NOW.getTime() + 26 * HOUR);
        b.put("placeName", "滨江步道");
        b.put("lat", "31.2304");
        b.put("lng", "121.4737");
        b.put("capacity", 20);
        b.put("description", "配速 6 分");
        b.put("requestId", "req-12345678");
        return b;
    }

    static String code(Map<String, Object> body)
    {
        return assertThrows(ChannelRejection.class, () -> ChannelEventDraft.parse(body, NOW)).getErrorCode();
    }

    @Test
    void acceptsAFreeEventAndNormalisesTheNumbers()
    {
        ChannelEventDraft d = ChannelEventDraft.parse(form(), NOW);
        assertEquals("周六夜跑", d.title());
        assertEquals(20, d.capacity());
        assertFalse(d.paid());
        assertEquals(new BigDecimal("0.00"), d.price());
        assertEquals(7, d.lat().scale());
    }

    @Test
    void acceptsTheActivityApiTimeFormatInShanghaiTime()
    {
        Map<String, Object> b = form();
        b.put("startAt", "2027-01-16 08:00:00");
        b.put("endAt", "2027-01-16 10:00:00");
        ChannelEventDraft d = ChannelEventDraft.parse(b, NOW);
        assertEquals(2 * HOUR, d.endAt().getTime() - d.startAt().getTime());
        assertEquals(0, d.startAt().getTime() % (24 * HOUR), "08:00 +08:00 is midnight UTC");
    }

    @Test
    void refusesMissingOrBrokenFields()
    {
        Map<String, Object> b = form();
        b.remove("title");
        assertEquals("EVENT_TITLE_REQUIRED", code(b));
        b = form();
        b.put("title", "长".repeat(61));
        assertEquals("EVENT_TITLE_TOO_LONG", code(b));
        b = form();
        b.put("endAt", b.get("startAt"));
        assertEquals("EVENT_TIME_ORDER", code(b));
        b = form();
        b.put("startAt", NOW.getTime() - HOUR);
        assertEquals("EVENT_TIME_PAST", code(b));
        b = form();
        b.put("endAt", NOW.getTime() + 24 * 9 * HOUR);
        assertEquals("EVENT_TOO_LONG", code(b));
        b = form();
        b.remove("lat");
        assertEquals("EVENT_PIN_REQUIRED", code(b));
        b = form();
        b.put("lat", "0");
        b.put("lng", "0");
        assertEquals("EVENT_PIN_INVALID", code(b));
        b = form();
        b.put("capacity", 0);
        assertEquals("EVENT_CAPACITY_REQUIRED", code(b));
        b = form();
        b.put("capacity", 1001);
        assertEquals("EVENT_CAPACITY_TOO_LARGE", code(b));
        b = form();
        b.put("capacity", "2.5");
        assertEquals("EVENT_NUMBER_INVALID", code(b));
        b = form();
        b.put("price", "-1");
        assertEquals("EVENT_PRICE_INVALID", code(b));
        b = form();
        b.put("price", "1.234");
        assertEquals("EVENT_PRICE_INVALID", code(b));
        b = form();
        b.put("requestId", "x");
        assertEquals("EVENT_REQUEST_ID_INVALID", code(b));
        assertEquals("EVENT_FORM_INVALID", assertThrows(ChannelRejection.class, () -> ChannelEventDraft.parse(null, NOW)).getErrorCode());
    }

    @Test
    void aPriceAboveZeroIsAPaidEvent()
    {
        Map<String, Object> b = form();
        b.put("price", "39.9");
        assertTrue(ChannelEventDraft.parse(b, NOW).paid());
    }

    @Test
    void publishRequestIsAClubActivityWithOneTicketCarryingCapacityAndPin()
    {
        ChannelEventDraft d = ChannelEventDraft.parse(form(), NOW);
        CmsActivityPublishRequest r = ChannelEventService.toPublishRequest(new ChannelEventService.PostTarget(5L, 42L), d);
        assertEquals(42L, r.getClubId());
        assertEquals("chev-req-12345678", r.getRequestId());
        assertEquals("周六夜跑", r.getName());
        assertEquals(1, r.getTickets().size());
        ActivityTicketRequest t = r.getTickets().get(0);
        assertEquals(20, t.getTotalStock());
        assertEquals(0, t.getPrice().signum());
        assertEquals(1, t.getMode(), "classic ticket: the existing before-start reminder job covers it");
        assertEquals(d.startAt(), t.getStartTime());
        assertEquals(d.lat(), t.getGatherLat());
        assertEquals("滨江步道", t.getMeetingPoint());
    }
}
