package com.chengyinhub.business.citygame;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for weighted dice preview/ticket mapping and Roam+CITY territory plausibility anti-cheat.
 */
class CityBoardAndTerritoryUnitTest {

    @Test
    void diceWeightedMappingAndProbabilityPreviewAreDeterministicAndNormalized() {
        CityGameRules.Dice dice = CityGameRules.dice("[1,2,3,4]", "[1,2,3,4]");
        assertEquals(10, dice.totalWeight());
        assertEquals(4, dice.faceCount());
        assertEquals(0, dice.indexForTicket(0));
        assertEquals(1, dice.indexForTicket(1));
        assertEquals(1, dice.indexForTicket(2));
        assertEquals(2, dice.indexForTicket(5));
        assertEquals(3, dice.indexForTicket(9));
        List<Map<String, Object>> preview = dice.probabilityPreview();
        assertEquals(4, preview.size());
        double sum = 0.0;
        for (Map<String, Object> p : preview) sum += ((Number) p.get("probability")).doubleValue();
        assertEquals(1.0, sum, 1e-6);

        assertThrows(IllegalStateException.class, () -> CityGameRules.dice("[1,2]", "[1,0]"));
        assertThrows(IllegalStateException.class, () -> CityGameRules.dice("[1,2]", "[1]"));
    }

    @Test
    void territoryPlausibilityBlocksTeleportsAndInvalidGpsAndFlagsHighSpeed() {
        long t0 = 1_700_000_000_000L;
        // Invalid coordinates
        assertEquals("BLOCKED", RoamTerritoryService.evaluatePlausibility(0.0, 0.0, 10.0, null, null, null, t0).status);
        assertEquals("BLOCKED", RoamTerritoryService.evaluatePlausibility(95.0, 121.0, 10.0, null, null, null, t0).status);
        // Poor GPS accuracy
        RoamTerritoryService.PlausibilityVerdict poorAcc = RoamTerritoryService.evaluatePlausibility(
                31.2300, 121.4700, 220.0, null, null, null, t0);
        assertEquals("BLOCKED", poorAcc.status);
        assertEquals("POOR_ACCURACY", poorAcc.reasonCode);

        // Normal walking (~100m in 60s -> PASS)
        RoamTerritoryService.PlausibilityVerdict walk = RoamTerritoryService.evaluatePlausibility(
                31.2309, 121.4700, 15.0, 31.2300, 121.4700, t0, t0 + 60_000L);
        assertEquals("PASS", walk.status);

        // High speed (~1500m in 50s = ~30 m/s -> FLAGGED)
        RoamTerritoryService.PlausibilityVerdict fast = RoamTerritoryService.evaluatePlausibility(
                31.2435, 121.4700, 15.0, 31.2300, 121.4700, t0, t0 + 50_000L);
        assertEquals("FLAGGED", fast.status);
        assertEquals("HIGH_SPEED", fast.reasonCode);

        // Teleport (~5km in 2s = ~2500 m/s -> BLOCKED)
        RoamTerritoryService.PlausibilityVerdict teleport = RoamTerritoryService.evaluatePlausibility(
                31.2750, 121.4700, 15.0, 31.2300, 121.4700, t0, t0 + 2_000L);
        assertEquals("BLOCKED", teleport.status);
        assertEquals("TELEPORT_SPEED", teleport.reasonCode);

        // Deterministic 7-char geohash
        String gh = RoamTerritoryService.encodeGeohash7(31.2304, 121.4737);
        assertEquals(7, gh.length());
        assertTrue(gh.startsWith("wtw3s"));
    }
}
