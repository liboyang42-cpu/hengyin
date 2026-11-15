package com.chengyinhub.business.creatorearnings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CreatorEarningsPolicyTest {

    private final CreatorEarningsPolicy policy = new CreatorEarningsPolicy(new CreatorEarningsPolicy.Config(5, 4, 1, 3, 1, 7000));

    @Test
    void eventKeysAreDeterministicAndBounded() {
        assertEquals("PLAY:42", CreatorEarningsPolicy.eventKey("PLAY", "42"));
        assertEquals(CreatorEarningsPolicy.eventKey("SALE", "WKO_x"), CreatorEarningsPolicy.eventKey("SALE", "WKO_x"));
        assertEquals("creator-earning:PLAY:42", CreatorEarningsPolicy.pointsBusinessKey("PLAY:42"));
        assertThrows(IllegalArgumentException.class, () -> CreatorEarningsPolicy.eventKey("PLAY", " "));
        assertThrows(IllegalArgumentException.class, () -> CreatorEarningsPolicy.eventKey("PLAY", "x".repeat(130)));
    }

    @Test
    void aCreatorNeverEarnsFromThemselves() {
        assertFalse(CreatorEarningsPolicy.countable(7L, 7L));
        assertFalse(CreatorEarningsPolicy.countable(0L, 8L));
        assertFalse(CreatorEarningsPolicy.countable(null, 8L));
        assertTrue(CreatorEarningsPolicy.countable(7L, 8L));
    }

    @Test
    void pointsPerEventAndDailyCaps() {
        assertEquals(5, policy.pointsFor(CreatorEarningsPolicy.ADOPT));
        assertEquals(4, policy.pointsFor(CreatorEarningsPolicy.INSTALL));
        assertEquals(1, policy.pointsFor(CreatorEarningsPolicy.PLAY));
        assertEquals(0, policy.pointsFor(CreatorEarningsPolicy.SALE), "a sale pays no points; its money waits for money on");
        assertTrue(policy.playWithinCap(2, 0));
        assertFalse(policy.playWithinCap(3, 0), "creator daily cap");
        assertFalse(policy.playWithinCap(0, 1), "one rewarded play per player per item per day");
    }

    @Test
    void saleShareRoundsDownAndMoneyIsNeverPaid() {
        assertEquals(699, policy.creatorShare(999));
        assertEquals(0, policy.creatorShare(0));
        assertEquals(0, policy.creatorShare(-5));
        assertEquals("HELD_MONEY_OFF", CreatorEarningsPolicy.moneyState(false));
        assertEquals("PENDING_PAYOUT", CreatorEarningsPolicy.moneyState(true));
    }

    @Test
    void incomeOrderKeyMatchesTheAppWireFormat() {
        String key = CreatorEarningsPolicy.incomeOrderKey("WKO_0123456789abcdef0123456789abcdef");
        assertTrue(key.matches("MOD[0-9a-f]{29}"), key);
        assertEquals(key, CreatorEarningsPolicy.incomeOrderKey("WKO_0123456789abcdef0123456789abcdef"));
        assertTrue(CreatorEarningsPolicy.isIncomeOrderKey(key));
        assertFalse(CreatorEarningsPolicy.isIncomeOrderKey("MOD123"));
        assertFalse(CreatorEarningsPolicy.isIncomeOrderKey(null));
    }

    @Test
    void configRejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new CreatorEarningsPolicy.Config(-1, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CreatorEarningsPolicy.Config(0, 0, 0, 0, 0, 10001));
    }
}
