package com.chengyinhub.business.service.support.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.Test;

class VersionedLocaleDisplayTest {

    private static final Instant FIXED = Instant.parse("2026-10-23T12:00:00Z");

    @Test
    void cnAndUsKeepEstablishedLanguageTimezoneAndPaymentDifferences() {
        VersionedLocaleDisplay.Profile cn = VersionedLocaleDisplay.profile(VersionedLocaleDisplay.Market.CN);
        VersionedLocaleDisplay.Profile us = VersionedLocaleDisplay.profile(VersionedLocaleDisplay.Market.US);

        assertEquals(2, cn.version);
        assertEquals(2, us.version);
        assertEquals("zh-CN", cn.languageTag);
        assertEquals("en-US", us.languageTag);
        assertEquals("Asia/Shanghai", cn.zoneId.getId());
        assertEquals("America/New_York", us.zoneId.getId());
        VersionedLocaleDisplay.WaitingSourceException waiting = assertThrows(
                VersionedLocaleDisplay.WaitingSourceException.class,
                () -> VersionedLocaleDisplay.format(VersionedLocaleDisplay.Market.CN, FIXED));
        assertEquals(VersionedLocaleDisplay.EVENT_TIMEZONE_SOURCE, waiting.getWaitingCapability());
        assertEquals("2026-10-23 20:00:00", VersionedLocaleDisplay.format(
                VersionedLocaleDisplay.Market.CN, FIXED, ZoneId.of("Asia/Shanghai")));
        assertEquals("Oct 23, 2026 8:00 AM", asciiSpaces(VersionedLocaleDisplay.format(
                VersionedLocaleDisplay.Market.US, FIXED, ZoneId.of("America/New_York"))));
        assertNotEquals("Oct 23, 2026 8:00 AM", asciiSpaces(VersionedLocaleDisplay.format(
                VersionedLocaleDisplay.Market.US, FIXED, ZoneId.of("America/Los_Angeles"))));
        assertEquals("WAITING_SOURCE", cn.asView().get("eventTimezone"));
        assertEquals("2026-10-23 20:00:00", VersionedLocaleDisplay.format(
                VersionedLocaleDisplay.Market.CN, FIXED, eventId -> ZoneId.of("Asia/Shanghai"), "evt-1"));
        VersionedLocaleDisplay.WaitingSourceException missing = assertThrows(
                VersionedLocaleDisplay.WaitingSourceException.class,
                () -> VersionedLocaleDisplay.format(VersionedLocaleDisplay.Market.US, FIXED, eventId -> null, "evt-2"));
        assertEquals(VersionedLocaleDisplay.EVENT_TIMEZONE_SOURCE, missing.getWaitingCapability());
        assertNotEquals(cn.paymentChannel, us.paymentChannel);
        assertTrueContainsWeChat(cn.paymentChannel);
        assertTrueContainsIap(us.paymentChannel);
    }

    @Test
    void unknownInstantStaysUnknownNotEmptySuccess() {
        assertEquals("UNKNOWN", VersionedLocaleDisplay.format(VersionedLocaleDisplay.Market.CN, null));
    }

    @Test
    void legalAndStoreGatesStayNotRunAndNeverPass() {
        Map<VersionedLocaleDisplay.LegalGate, String> gates = VersionedLocaleDisplay.legalGateStatuses();
        assertEquals(VersionedLocaleDisplay.LegalGate.values().length, gates.size());
        for (VersionedLocaleDisplay.LegalGate gate : VersionedLocaleDisplay.LegalGate.values()) {
            assertEquals(VersionedLocaleDisplay.NOT_RUN, gates.get(gate));
            assertEquals(VersionedLocaleDisplay.NOT_RUN, VersionedLocaleDisplay.legalGateStatus(gate));
            assertNotEquals("PASS", VersionedLocaleDisplay.legalGateStatus(gate));
            assertNotEquals("PASSED", VersionedLocaleDisplay.legalGateStatus(gate));
        }
        assertEquals(VersionedLocaleDisplay.NOT_RUN,
                VersionedLocaleDisplay.profile(VersionedLocaleDisplay.Market.CN).asView().get("legalGates"));
    }

    private static String asciiSpaces(String value) {
        return value == null ? null : value.replace('\u202f', ' ').replace('\u00a0', ' ');
    }

    private static void assertTrueContainsWeChat(String paymentChannel) {
        if (paymentChannel == null || !paymentChannel.contains("微信")) {
            throw new AssertionError("CN payment channel must keep WeChat: " + paymentChannel);
        }
    }

    private static void assertTrueContainsIap(String paymentChannel) {
        if (paymentChannel == null || !paymentChannel.contains("IAP")) {
            throw new AssertionError("US payment channel must keep Apple IAP: " + paymentChannel);
        }
    }
}
