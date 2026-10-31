package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.service.support.rpg.adapter.EnvironmentExpiryAdapter.Decision;
import com.chengyinhub.business.service.support.rpg.adapter.EnvironmentExpiryAdapter.Outcome;
import com.chengyinhub.business.service.support.rpg.adapter.EnvironmentExpiryAdapter.TimedFact;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentExpiryAdapterTest {

    private static final Instant SERVER_NOW = Instant.parse("2026-10-23T19:50:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-23T19:40:00Z");
    private static final Instant CLIENT_FUTURE = Instant.parse("2026-10-23T20:50:00Z");
    private final EnvironmentExpiryAdapter adapter = new EnvironmentExpiryAdapter();

    @Test
    void expiredStatusDoesNotGrantCheckSuccessEvenIfClientClockIsInTheFuture() {
        TimedFact rainGear = new TimedFact("equip.rain-cloak", "equipment", EXPIRES, "req-rain-1");
        Decision decision = adapter.evaluate(rainGear, SERVER_NOW, CLIENT_FUTURE);
        assertEquals(Outcome.EXPIRED, decision.outcome());
        assertEquals("req-rain-1", decision.originalRequestId());
        assertFalse(decision.grantsCheckSuccess());
        assertFalse(adapter.grantsCheckSuccess(rainGear, SERVER_NOW, CLIENT_FUTURE, "rain"));
    }

    @Test
    void fakeOrUnknownWeatherCannotReviveExpiredDuration() {
        TimedFact expired = new TimedFact("status.wet", "status", EXPIRES, "req-wet-1");
        assertFalse(adapter.grantsCheckSuccess(expired, SERVER_NOW, CLIENT_FUTURE, "rain"));
        assertFalse(adapter.grantsCheckSuccess(expired, SERVER_NOW, CLIENT_FUTURE, "unknown"));
        TimedFact stillActive = new TimedFact("status.wet", "status", Instant.parse("2026-10-23T20:00:00Z"), "req-wet-1");
        assertTrue(adapter.grantsCheckSuccess(stillActive, SERVER_NOW, CLIENT_FUTURE, "unknown"));
    }

    @Test
    void missingExpiryIsUnknownNotSuccessAndKeepsOriginalRequestId() {
        TimedFact noSource = new TimedFact("status.wet", "status", null, "req-wet-2");
        Decision decision = adapter.evaluate(noSource, SERVER_NOW, CLIENT_FUTURE);
        assertEquals(Outcome.UNKNOWN, decision.outcome());
        assertEquals("req-wet-2", decision.originalRequestId());
        assertFalse(decision.grantsCheckSuccess());
    }

    @Test
    void clientClockArgumentIsRequiredSoCallersCannotOmitTheIgnoredClock() {
        TimedFact fact = new TimedFact("equip.lamp", "equipment", Instant.parse("2026-10-23T20:00:00Z"), "req-lamp");
        assertThrows(NullPointerException.class, () -> adapter.evaluate(fact, SERVER_NOW, null));
    }
}
