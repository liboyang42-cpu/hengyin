package com.chengyinhub.web.controller.api;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 第四轮(多票价):确认终价的 ticketPrices 解析。缺省走单价老路径,格式不对按原话退回。 */
class ApiPricingTicketPricesParseTest {

    private static Map<String, Object> row(Object id, Object price) {
        Map<String, Object> m = new HashMap<>();
        m.put("ticketId", id);
        m.put("price", price);
        return m;
    }

    @Test
    void absentOrEmptyMeansSinglePrice() {
        assertNull(ApiPricingController.ticketPrices(null));
        assertNull(ApiPricingController.ticketPrices(Collections.emptyList()));
    }

    @Test
    void parsesEachTicketInOrder() {
        Map<Long, BigDecimal> m = ApiPricingController.ticketPrices(Arrays.asList(row(931, 79), row("932", "109.5")));
        assertEquals(Arrays.asList(931L, 932L), new java.util.ArrayList<>(m.keySet()));
        assertEquals(0, new BigDecimal("109.5").compareTo(m.get(932L)));
    }

    @Test
    void refusesMissingPriceDuplicateIdOrGarbage() {
        assertThrows(IllegalStateException.class, () -> ApiPricingController.ticketPrices(Collections.singletonList(row(931, null))));
        assertThrows(IllegalStateException.class, () -> ApiPricingController.ticketPrices(Arrays.asList(row(931, 1), row(931, 2))));
        assertThrows(IllegalStateException.class, () -> ApiPricingController.ticketPrices(Collections.singletonList(row("x", 1))));
        assertThrows(IllegalStateException.class, () -> ApiPricingController.ticketPrices("931:79"));
    }
}
