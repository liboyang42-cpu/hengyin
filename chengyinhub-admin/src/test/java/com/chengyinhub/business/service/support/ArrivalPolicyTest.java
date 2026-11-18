package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BE-09:冻结策略缺省沿 50m/80m；精度与半径独立，低精度不放大范围；
 * 非法/非有限/越界/陈旧失败关闭；fallbackMode 不产生任何授权。
 */
class ArrivalPolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode station(String policyJson) throws Exception {
        ObjectNode station = JSON.createObjectNode();
        station.put("nodeId", 10L);
        if (policyJson != null) station.set(ArrivalPolicy.FIELD, JSON.readTree(policyJson));
        return station;
    }

    /** F08 v2:开局 300 米,定位差时最多放宽到 500 米,精度上限 200 米。 */
    @Test
    void startCheckRelaxesUpToFiveHundredMetresWhenAccuracyIsPoor() {
        assertEquals(300d, ArrivalPolicy.START_RADIUS_METERS, 0d);
        assertEquals(ArrivalPolicy.Fence.PASS, ArrivalPolicy.startViolation(0d, 10d));
        assertEquals(ArrivalPolicy.Fence.PASS, ArrivalPolicy.startViolation(300d, 0d));
        assertEquals(ArrivalPolicy.Fence.TOO_FAR, ArrivalPolicy.startViolation(301d, 0d));
        assertEquals(ArrivalPolicy.Fence.PASS, ArrivalPolicy.startViolation(420d, 150d));
        assertEquals(ArrivalPolicy.Fence.TOO_FAR, ArrivalPolicy.startViolation(460d, 150d));
        assertEquals(ArrivalPolicy.Fence.PASS, ArrivalPolicy.startViolation(500d, 200d));
        assertEquals(ArrivalPolicy.Fence.ACCURACY_LOW, ArrivalPolicy.startViolation(10d, 200.01d));
        assertEquals(ArrivalPolicy.Fence.ACCURACY_LOW, ArrivalPolicy.startViolation(10d, 250d));
        assertEquals(ArrivalPolicy.Fence.INVALID, ArrivalPolicy.startViolation(Double.NaN, 10d));
        assertEquals(ArrivalPolicy.Fence.INVALID, ArrivalPolicy.startViolation(10d, -1d));
    }

    /** FE-STORY 5.2:自由探索到访半径默认 300,夹到 300–500,精度最多放宽 100,缺精度按 80。 */
    @Test
    void freeExploreVisitIsLenientButBounded() {
        assertEquals(300, ArrivalPolicy.visitRadius(null));
        assertEquals(300, ArrivalPolicy.visitRadius(50));
        assertEquals(500, ArrivalPolicy.visitRadius(900));
        assertEquals(ArrivalPolicy.Fence.PASS, ArrivalPolicy.visitViolation(290d, 5d, null));
        assertEquals(ArrivalPolicy.Fence.TOO_FAR, ArrivalPolicy.visitViolation(310d, 5d, null));
        assertEquals(ArrivalPolicy.Fence.PASS, ArrivalPolicy.visitViolation(370d, null, null));
        assertEquals(ArrivalPolicy.Fence.TOO_FAR, ArrivalPolicy.visitViolation(390d, null, null));
        assertEquals(ArrivalPolicy.Fence.ACCURACY_LOW, ArrivalPolicy.visitViolation(10d, 250d, null));
        assertEquals(ArrivalPolicy.Fence.INVALID, ArrivalPolicy.visitViolation(Double.NaN, 5d, null));
    }

    @Test
    void missingPolicyKeepsLegacyFence() throws Exception {
        ArrivalPolicy policy = ArrivalPolicy.fromStation(station(null));
        assertEquals(ArrivalPolicy.LEGACY_RADIUS_METERS, policy.radiusMeters(), 0d);
        assertEquals(ArrivalPolicy.LEGACY_MAX_ACCURACY_METERS, policy.maxAccuracyMeters(), 0d);
        assertNull(policy.maxAgeSeconds(), "旧局不校验定位时效");
        assertEquals(ArrivalPolicy.FALLBACK_NONE, policy.fallbackMode());
        assertEquals(ArrivalPolicy.Fence.PASS, policy.fenceViolation(50d, 80d));
        assertEquals(ArrivalPolicy.Fence.TOO_FAR, policy.fenceViolation(50.01d, 10d));
        assertEquals(ArrivalPolicy.Fence.ACCURACY_LOW, policy.fenceViolation(10d, 80.01d));
    }

    @Test
    void missingFieldsInsidePolicyFallBackToLegacyValues() throws Exception {
        ArrivalPolicy policy = ArrivalPolicy.fromStation(station("{\"version\":1}"));
        assertEquals(50d, policy.radiusMeters(), 0d);
        assertEquals(80d, policy.maxAccuracyMeters(), 0d);
        assertNull(policy.maxAgeSeconds());
        assertEquals(ArrivalPolicy.FALLBACK_NONE, policy.fallbackMode());
    }

    @Test
    void lowAccuracyNeverWidensRadius() throws Exception {
        ArrivalPolicy policy = ArrivalPolicy.fromStation(
                station("{\"version\":1,\"radiusMeters\":50,\"maxAccuracyMeters\":80}"));
        // 精度通过也不能越过半径
        assertEquals(ArrivalPolicy.Fence.TOO_FAR, policy.fenceViolation(50.1d, 80d));
        // 精度不通过时，即使距离很远也只报精度问题；半径值始终不变
        assertEquals(ArrivalPolicy.Fence.ACCURACY_LOW, policy.fenceViolation(500d, 81d));
        assertEquals(50d, policy.radiusMeters(), 0d);
        assertEquals(80d, policy.maxAccuracyMeters(), 0d);
    }

    @Test
    void finiteBoundariesAndRangeAreEnforced() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> ArrivalPolicy.normalize(JsonNodeFactory.instance.objectNode().put("radiusMeters", Double.NaN)));
        assertThrows(IllegalArgumentException.class,
                () -> ArrivalPolicy.normalize(JsonNodeFactory.instance.objectNode().put("radiusMeters", Double.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class,
                () -> ArrivalPolicy.normalize(JsonNodeFactory.instance.objectNode().put("radiusMeters", -1d)));
        assertThrows(IllegalArgumentException.class,
                () -> ArrivalPolicy.normalize(JsonNodeFactory.instance.objectNode().put("radiusMeters", 0d)));
        assertThrows(IllegalArgumentException.class,
                () -> ArrivalPolicy.normalize(JsonNodeFactory.instance.objectNode().put("maxAccuracyMeters", 10_001d)));
        assertThrows(IllegalArgumentException.class,
                () -> ArrivalPolicy.normalize(JsonNodeFactory.instance.objectNode().put("maxAgeSeconds", 0)));
        assertThrows(IllegalArgumentException.class,
                () -> ArrivalPolicy.normalize(JsonNodeFactory.instance.objectNode().put("maxAgeSeconds", 86_401)));
    }

    @Test
    void unknownOrUnsupportedPolicyIsRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> ArrivalPolicy.normalize(JSON.readTree("{\"radiusMetres\":60}")));
        assertThrows(IllegalArgumentException.class,
                () -> ArrivalPolicy.normalize(JSON.readTree("{\"version\":2}")));
        assertThrows(IllegalArgumentException.class,
                () -> ArrivalPolicy.normalize(JSON.readTree("{\"fallbackMode\":\"AUTO_SKIP\"}")));
    }

    @Test
    void staleLocationOnlyCheckedWhenPolicyRequiresIt() throws Exception {
        long now = 1_800_000_000_000L;
        assertFalse(ArrivalPolicy.legacy().locationTooOld(null, now));
        assertFalse(ArrivalPolicy.legacy().locationTooOld(now - 86_400_000L, now));

        ArrivalPolicy fresh = ArrivalPolicy.fromStation(station("{\"version\":1,\"maxAgeSeconds\":60}"));
        assertTrue(fresh.locationTooOld(null, now), "要求时效时缺时间必须失败关闭");
        assertTrue(fresh.locationTooOld(now - 60_001L, now));
        assertFalse(fresh.locationTooOld(now - 59_999L, now));
        assertTrue(fresh.locationTooOld(now + ArrivalPolicy.CLOCK_SKEW_MILLIS + 1L, now));
    }

    @Test
    void fallbackModeNeverGrantsAnythingByItself() throws Exception {
        ArrivalPolicy policy = ArrivalPolicy.fromStation(
                station("{\"version\":1,\"fallbackMode\":\"SKIP_ALLOWED\"}"));
        assertEquals(ArrivalPolicy.FALLBACK_SKIP_ALLOWED, policy.fallbackMode());
        assertTrue(ArrivalPolicy.approvedFallbacks(station("{\"version\":1,\"fallbackMode\":\"SKIP_ALLOWED\"}")).isEmpty(),
                "作者声明 skip 不等于获批 fallback");
    }

    @Test
    void approvedFallbacksComeOnlyFromFrozenApprovedList() throws Exception {
        ObjectNode station = JSON.createObjectNode();
        station.putArray("approvedFallbacks").add("12").add("12").add("bad").add("13");
        assertEquals(Arrays.asList("12", "13"), ArrivalPolicy.approvedFallbacks(station));
        assertTrue(ArrivalPolicy.approvedFallbacks(null).isEmpty());
    }

    @Test
    void contractApprovedFallbacksOnlyForBoundMatchingNode() throws Exception {
        String contract = "{\"schemaVersion\":1,"
                + "\"locationSlots\":[{\"slotKey\":\"door\",\"purpose\":\"SPACE\"}],"
                + "\"locationBindings\":[{\"slotKey\":\"door\",\"nodeId\":\"10\",\"bindingRevision\":1,"
                + "\"verificationStatus\":\"BOUND_UNVERIFIED\",\"approvedFallbackNodeIds\":[\"10\",\"11\",\"12\"]}]}";
        List<String> approved = TopicLocationContract.approvedFallbacksForNode(contract, 10L,
                new LinkedHashSet<Long>(Arrays.asList(10L, 11L, 12L)));
        assertEquals(Arrays.asList("11", "12"), approved);
        assertTrue(TopicLocationContract.approvedFallbacksForNode(contract, 12L,
                new LinkedHashSet<Long>(Arrays.asList(10L, 11L, 12L))).isEmpty());
        assertEquals(Arrays.asList("11"), TopicLocationContract.approvedFallbacksForNode(contract, 10L,
                new LinkedHashSet<Long>(Arrays.asList(10L, 11L))));
        assertTrue(TopicLocationContract.approvedFallbacksForNode("not-json", 10L, null).isEmpty(),
                "坏合同按没有获批替代处理，不阻断冻结");
    }
}
