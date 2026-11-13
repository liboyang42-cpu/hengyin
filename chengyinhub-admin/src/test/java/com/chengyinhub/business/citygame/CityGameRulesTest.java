package com.chengyinhub.business.citygame;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CityGameRulesTest {

    @Test
    void weightedDiceMapsEveryTicketToItsFace() {
        CityGameRules.Dice dice = CityGameRules.dice("[1,2,3]", "[1,2,3]");
        int[] expected = {1, 2, 2, 3, 3, 3};
        for (int ticket = 0; ticket < expected.length; ticket++) {
            int t = ticket;
            assertEquals(expected[ticket], dice.roll(n -> { assertEquals(6, n); return t; }));
        }
        assertEquals(3, dice.maxFace());
    }

    @Test
    void diceRejectsInvalidTablesAndOutOfRangeRandom() {
        assertThrows(IllegalStateException.class, () -> CityGameRules.dice("[1,2]", "[1]"));
        assertThrows(IllegalStateException.class, () -> CityGameRules.dice("[1,2]", "[1,0]"));
        assertThrows(IllegalStateException.class, () -> CityGameRules.dice("[]", "[]"));
        CityGameRules.Dice dice = CityGameRules.dice("[1,2]", "[1,1]");
        assertThrows(IllegalStateException.class, () -> dice.roll(n -> n));
    }

    @Test
    void trackWrapsAndChallengeTiesKeepDefender() {
        assertEquals(1, CityGameRules.advance(4, 2, 5));
        assertEquals(0, CityGameRules.advance(0, 5, 5));
        assertThrows(IllegalStateException.class, () -> CityGameRules.advance(0, 1, 0));
        assertFalse(CityGameRules.challengerWins(3, 3));
        assertTrue(CityGameRules.challengerWins(4, 3));
        assertEquals(6, CityGameRules.settlementScore(3, 2));
        assertEquals(0, CityGameRules.settlementScore(-1, 2));
    }

    @Test
    void actionCostsComeFromPublishedBoard() {
        Map<String, Object> board = new HashMap<>();
        board.put("rollCost", 1);
        board.put("acquisitionCost", 2);
        board.put("serviceCost", 3);
        board.put("challengeCost", 4);
        board.put("taskCost", 5);
        assertEquals(1, CityGameRules.actionCost(CityGameRules.Action.ROLL_ROUND, board));
        assertEquals(2, CityGameRules.actionCost(CityGameRules.Action.ACQUIRE_PROPERTY, board));
        assertEquals(3, CityGameRules.actionCost(CityGameRules.Action.ACCEPT_SERVICE_VISIT, board));
        assertEquals(4, CityGameRules.actionCost(CityGameRules.Action.START_CHALLENGE, board));
        assertEquals(5, CityGameRules.actionCost(CityGameRules.Action.ACCEPT_TASK, board));
        assertTrue(CityGameRules.Action.ACQUIRE_PROPERTY.requiresQuote());
        assertFalse(CityGameRules.Action.ROLL_ROUND.requiresQuote());
        assertNull(CityGameRules.Action.parse("ROLL"));
    }

    @Test
    void requestHashIsKeyOrderIndependentAndBodySensitive() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("requestId", "r1");
        a.put("roundSeq", 1);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("roundSeq", 1);
        b.put("requestId", "r1");
        assertEquals(CityGameRules.requestHash(a), CityGameRules.requestHash(b));
        b.put("roundSeq", 2);
        assertNotEquals(CityGameRules.requestHash(a), CityGameRules.requestHash(b));
    }

    @Test
    void wireIdentifiersFollowAppValidation() {
        assertTrue(CityGameRules.id("board-1_A"));
        assertFalse(CityGameRules.id("-board"));
        assertFalse(CityGameRules.id("a".repeat(97)));
        assertTrue(CityGameRules.uuid("0f8fad5b-d9cb-469f-a165-70867728950e"));
        assertFalse(CityGameRules.uuid("0F8FAD5B-D9CB-469F-A165-70867728950E"));
        assertTrue(CityGameRules.hash("a".repeat(64)));
        assertFalse(CityGameRules.version(-1L));
    }

    @Test
    void effectiveStatusTurnsExpiredProtectionIntoHeld() {
        Map<String, Object> p = new HashMap<>();
        p.put("status", "PROTECTED");
        p.put("protectedUntil", new Date(1_000));
        assertEquals("PROTECTED", CityGameActionService.effectiveStatus(p, 999));
        assertEquals("HELD", CityGameActionService.effectiveStatus(p, 1_000));
        p.put("status", "PAUSED");
        assertEquals("PAUSED", CityGameActionService.effectiveStatus(p, 5_000));
    }

    private static Map<String, Object> outing(long member, String id, long t0) {
        Map<String, Object> row = new HashMap<>();
        row.put("memberId", member);
        row.put("outingId", id);
        row.put("t0", new Date(t0));
        return row;
    }

    @Test
    void fairnessCohortCountsReturnersAmongMatureMembersOnly() {
        long day = 86_400_000L;
        List<Map<String, Object>> outings = new ArrayList<>();
        outings.add(outing(1, "a1", 0));            // mature, returned on day 2
        outings.add(outing(1, "a2", 2 * day));
        outings.add(outing(2, "b1", day));          // mature, returned after the delta
        outings.add(outing(2, "b2", 9 * day));
        outings.add(outing(3, "c1", 10 * day));     // not yet mature
        CityFairnessService.Cohort c = CityFairnessService.cohort(outings, 1, 10 * day, 7 * day, 3 * day);
        assertEquals(2, c.denominator);
        assertEquals(1, c.matureCount);
        assertEquals("MATURE", c.ownMaturity);
        assertEquals("inside-delta", c.ownReason);
        assertEquals("a2", c.ownSecondOutingId);
        assertEquals(2, c.own.size());

        CityFairnessService.Cohort other = CityFairnessService.cohort(outings, 2, 10 * day, 7 * day, 3 * day);
        assertEquals("after-horizon", other.ownReason);
        CityFairnessService.Cohort newcomer = CityFairnessService.cohort(outings, 3, 10 * day, 7 * day, 3 * day);
        assertEquals("PENDING", newcomer.ownMaturity);
        assertEquals("first-outing", newcomer.ownReason);
        CityFairnessService.Cohort absent = CityFairnessService.cohort(outings, 9, 10 * day, 7 * day, 3 * day);
        assertNull(absent.ownFirstT0);
        assertTrue(absent.own.isEmpty());
    }

    @Test
    void snapshotViewportMustStayInsideHalfDegree() {
        assertTrue(CityGameReadService.bounded(new java.math.BigDecimal("31.0"), new java.math.BigDecimal("121.0"),
                new java.math.BigDecimal("31.5"), new java.math.BigDecimal("121.5")));
        assertFalse(CityGameReadService.bounded(new java.math.BigDecimal("31.0"), new java.math.BigDecimal("121.0"),
                new java.math.BigDecimal("31.6"), new java.math.BigDecimal("121.5")));
        assertFalse(CityGameReadService.bounded(new java.math.BigDecimal("31.5"), new java.math.BigDecimal("121.0"),
                new java.math.BigDecimal("31.0"), new java.math.BigDecimal("121.5")));
    }
}
