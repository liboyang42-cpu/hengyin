package com.chengyinhub.business.cityboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityBoardDraftDocumentTest {

    @Test
    void keyOrderDoesNotChangeTheContentHash() throws Exception {
        JsonNode left = read(valid());
        ObjectNode right = (ObjectNode) left.deepCopy();
        right.remove("title");
        right.put("title", "Synthetic CITY");
        CityBoardDraftDocument first = CityBoardDraftDocument.parse(left);
        CityBoardDraftDocument second = CityBoardDraftDocument.parse(right);
        assertTrue(first.structural.isEmpty());
        assertEquals(first.contentHash, second.contentHash);
        assertEquals(64, first.contentHash.length());
        assertTrue(first.publicationViolations(new CityBoardSourceProbe()).isEmpty());
        assertNull(first.canonical.get("probability"));
        assertEquals("0", first.canonical.path("pointCost").path("amount").asText());
    }

    @Test
    void rejectsUnknownProtectedStrategyMerchantAndRates() throws Exception {
        assertCode(valid().replace("\"title\": \"Synthetic CITY\"", "\"title\": \"Synthetic CITY\", \"nickname\": \"x\""), "UNKNOWN_FIELD");
        assertCode(valid().replace("\"title\": \"Synthetic CITY\"", "\"owner\": 7, \"title\": \"Synthetic CITY\""), "PROTECTED_FIELD");
        assertCode(valid().replace("\"title\": \"Synthetic CITY\"", "\"strategy\": {\"bid\": 1}, \"title\": \"Synthetic CITY\""), "PLAYER_STRATEGY");
        assertCode(valid().replace("\"title\": \"Synthetic CITY\"", "\"hours\": \"09-18\", \"title\": \"Synthetic CITY\""), "MERCHANT_FACT");
        assertCode(valid().replace("\"diceId\": \"dice-1\"", "\"diceId\": \"dice-1\", \"probability\": [0.25, 0.25, 0.25, 0.25]"), "UNDECLARED_RATE");
        assertCode(valid().replace("\"serviceFee\": {\"currency\": \"CNY\", \"amountMinor\": 100}",
                "\"serviceFee\": {\"currency\": \"CNY\", \"amountMinor\": 100, \"feeRate\": 0.1}"), "UNDECLARED_RATE");
        assertCode(valid().replace("\"confirmed\": true", "\"confirmed\": true, \"threshold\": 3"), "UNDECLARED_RATE");
    }

    @Test
    void missingOrInvalidMoneyBlocksPublicationAndDoesNotInventAFee() throws Exception {
        CityBoardDraftDocument missing = CityBoardDraftDocument.parse(read(valid().replace("\"serviceFee\": {\"currency\": \"CNY\", \"amountMinor\": 100},", "")));
        assertTrue(missing.structural.isEmpty());
        assertNull(missing.canonical.get("serviceFee"));
        assertEquals("MISSING_FEE", missing.publicationViolations(new CityBoardSourceProbe()).get(0).code);

        CityBoardDraftDocument negative = CityBoardDraftDocument.parse(read(valid().replace(
                "\"serviceFee\": {\"currency\": \"CNY\", \"amountMinor\": 100}",
                "\"serviceFee\": {\"currency\": \"CNY\", \"amountMinor\": -1}")));
        assertEquals("INVALID_FEE", negative.structural.get(0).code);

        CityBoardDraftDocument over = CityBoardDraftDocument.parse(read(valid().replace(
                "\"serviceFee\": {\"currency\": \"CNY\", \"amountMinor\": 100}",
                "\"serviceFee\": {\"currency\": \"CNY\", \"amountMinor\": 10001}")));
        assertTrue(over.structural.isEmpty());
        assertEquals("FEE_EXCEEDS_BUDGET", code(over.publicationViolations(new CityBoardSourceProbe()), "FEE_EXCEEDS_BUDGET"));
    }

    @Test
    void externalMerchantClaimStaysUnavailableAndABrokenTaskCannotPublish() throws Exception {
        String claimed = valid().replace(
                "\"domain\": \"PLATFORM_VIRTUAL\"",
                "\"domain\": \"MERCHANT\", \"ref\": {\"domain\": \"MERCHANT\", \"source\": \"mms\", \"id\": \"shop-1\"}");
        claimed = claimed.replace("\"locationPermits\": []",
                "\"locationPermits\": [{\"permitId\": \"permit-1\", \"pointId\": \"point-1\", \"source\": \"place\"}], "
                        + "\"references\": [{\"domain\": \"MERCHANT\", \"source\": \"mms\", \"id\": \"shop-1\"}]");
        CityBoardDraftDocument document = CityBoardDraftDocument.parse(read(claimed));
        assertTrue(document.structural.isEmpty());
        List<CityBoardDraftDocument.Violation> blocked = document.publicationViolations(new CityBoardSourceProbe());
        assertEquals("SOURCE_UNAVAILABLE", code(blocked, "SOURCE_UNAVAILABLE"));
        assertTrue(document.json.contains("\"id\":\"shop-1\""));
        assertTrue(!document.json.contains("hours"));

        CityBoardDraftDocument task = CityBoardDraftDocument.parse(read(
                valid().replace("\"title\": \"Visit gate\", \"pointId\": \"point-1\"",
                        "\"title\": \"Visit gate\", \"pointId\": \"missing\"")));
        assertEquals("TASK_NOT_COMPLETABLE", code(task.publicationViolations(new CityBoardSourceProbe()), "TASK_NOT_COMPLETABLE"));
    }

    private static void assertCode(String json, String code) throws Exception {
        CityBoardDraftDocument document = CityBoardDraftDocument.parse(read(json));
        assertEquals(code, document.structural.get(0).code);
        assertNull(document.contentHash);
    }

    private static String code(List<CityBoardDraftDocument.Violation> violations, String expected) {
        return violations.stream().map(item -> item.code).filter(expected::equals).findFirst().orElse("");
    }

    private static JsonNode read(String json) throws Exception {
        return CityBoardDraftDocument.mapper().readTree(json);
    }

    private static String valid() {
        return "{"
                + "\"title\": \"Synthetic CITY\","
                + "\"regionId\": \"city-1\","
                + "\"gameId\": \"game-1\","
                + "\"boardId\": \"board-1\","
                + "\"season\": {\"seasonId\": \"season-1\", \"budget\": {\"currency\": \"CNY\", \"amountMinor\": 10000}},"
                + "\"seasonSettlement\": {\"budgetCurrency\": \"CNY\", \"budgetAmountMinor\": 10000},"
                + "\"rules\": {\"rulesId\": \"rules-1\", \"text\": \"Walk every point.\"},"
                + "\"dice\": {\"diceId\": \"dice-1\", \"faces\": [1, 2, 3, 4]},"
                + "\"pointCost\": {\"unit\": \"POINT\", \"amount\": 0},"
                + "\"serviceFee\": {\"currency\": \"CNY\", \"amountMinor\": 100},"
                + "\"protection\": {\"challengeId\": \"protect-1\", \"text\": \"Stay on marked path\"},"
                + "\"safety\": {\"statement\": \"Operator confirmed the board text\", \"confirmed\": true},"
                + "\"pointRoles\": [{\"roleId\": \"walker\", \"title\": \"Walker\"}],"
                + "\"points\": [{\"pointId\": \"point-1\", \"title\": \"Gate\", \"latitude\": 31.2, \"longitude\": 121.5, \"roleId\": \"walker\", \"domain\": \"PLATFORM_VIRTUAL\"}],"
                + "\"tasks\": [{\"taskId\": \"task-1\", \"title\": \"Visit gate\", \"pointId\": \"point-1\", \"steps\": [\"Arrive\"]}],"
                + "\"locationPermits\": [],"
                + "\"permissionMatrix\": [{\"permission\": \"cityboard:player:read\", \"scope\": \"region:city-1\"}]"
                + "}";
    }
}
