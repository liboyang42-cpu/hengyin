package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F08:后端与小程序、iOS 读同一份合同 docs/contracts/arrival-policy.v2.json。
 * 常量与每一条用例都按合同判,改数值必须三端同改。
 */
class ArrivalPolicyContractV2Test {

    private static JsonNode contract() throws Exception {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            File file = dir.resolve("docs/contracts/arrival-policy.v2.json").toFile();
            if (file.isFile()) return new ObjectMapper().readTree(Files.readAllBytes(file.toPath()));
        }
        throw new IllegalStateException("找不到 docs/contracts/arrival-policy.v2.json");
    }

    @Test
    void constantsMatchContract() throws Exception {
        JsonNode c = contract();
        assertEquals(2, c.path("version").asInt());
        assertEquals(c.at("/start/radiusMeters").asDouble(), ArrivalPolicy.START_RADIUS_METERS, 0d);
        assertEquals(c.at("/start/maxRelaxMeters").asDouble(), ArrivalPolicy.START_MAX_RELAX_METERS, 0d);
        assertEquals(c.at("/start/maxAccuracyMeters").asDouble(), ArrivalPolicy.START_MAX_ACCURACY_METERS, 0d);
        assertEquals(c.at("/freeExploreVisit/defaultRadiusMeters").asInt(), ArrivalPolicy.VISIT_DEFAULT_RADIUS_METERS);
        assertEquals(c.at("/freeExploreVisit/minRadiusMeters").asInt(), ArrivalPolicy.VISIT_MIN_RADIUS_METERS);
        assertEquals(c.at("/freeExploreVisit/maxRadiusMeters").asInt(), ArrivalPolicy.VISIT_MAX_RADIUS_METERS);
        assertEquals(c.at("/freeExploreVisit/maxRelaxMeters").asDouble(), ArrivalPolicy.VISIT_MAX_RELAX_METERS, 0d);
        assertEquals(c.at("/freeExploreVisit/missingAccuracyMeters").asDouble(), ArrivalPolicy.VISIT_MISSING_ACCURACY_METERS, 0d);
        assertEquals(c.at("/freeExploreVisit/maxAccuracyMeters").asDouble(), ArrivalPolicy.VISIT_MAX_ACCURACY_METERS, 0d);
    }

    @Test
    void everyContractCaseMatches() throws Exception {
        JsonNode cases = contract().path("cases");
        assertTrue(cases.isArray() && cases.size() > 0);
        for (JsonNode c : cases) {
            double distance = c.path("distance").asDouble();
            ArrivalPolicy.Fence actual;
            if ("start".equals(c.path("kind").asText())) {
                actual = ArrivalPolicy.startViolation(distance, c.path("accuracy").asDouble());
            } else {
                Double accuracy = c.path("accuracy").isNull() ? null : c.path("accuracy").asDouble();
                Integer radius = c.path("radius").isNull() || c.path("radius").isMissingNode() ? null : c.path("radius").asInt();
                actual = ArrivalPolicy.visitViolation(distance, accuracy, radius);
            }
            assertEquals(c.path("expect").asText(), actual.name(), c.toString());
        }
    }
}
