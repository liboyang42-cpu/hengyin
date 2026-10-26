package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CheckReceiptProjectionTest {
    @Test void hiddenFirstReceiptAndHistoricalNestedStateDoNotExposeTotalsOrMargins() {
        PlayCheckReceipt receipt = new PlayCheckReceipt();
        receipt.setCheckId("hidden-check"); receipt.setTotal(17); receipt.setKept(12); receipt.setDc(13);
        receipt.setDice(List.of(12)); receipt.setBreakdown(Map.of("skill", Map.of("hidden", true, "value", 5), "total", 17));
        JsonNode first = CheckReceiptProjection.project(receipt);
        JsonNode nested = CheckReceiptProjection.response(Map.of("view", Map.of("check", receipt))).path("view").path("check");
        for (JsonNode view : List.of(first, nested)) {
            assertTrue(view.path("detailsHidden").asBoolean());
            assertTrue(view.path("total").isNull()); assertTrue(view.path("dc").isNull());
            assertEquals(0, view.path("dice").size()); assertEquals(0, view.path("mods").size());
            assertFalse(view.path("breakdown").has("skill"));
        }
        assertEquals(17, receipt.getTotal()); assertEquals(List.of(12), receipt.getDice(), "stored calculation is unchanged");
    }

    @Test void nestedHistoricalIdsKeepExactPrecisionAtTheJsonTreeBoundary() {
        JsonNode view = CheckReceiptProjection.response(Map.of("sessionId", 9007199254740993L,
                "check", Map.of("nodeId", 9007199254740995L), "version", 3));
        assertEquals("9007199254740993", view.path("sessionId").textValue());
        assertEquals("9007199254740995", view.path("check").path("nodeId").textValue());
        assertEquals(3, view.path("version").asInt());
    }

    @Test void visibleReceiptAndUnrelatedHiddenConfigKeepTheirContract() {
        Map<String, Object> check = Map.of("dice", List.of(12), "total", 17, "dc", 13);
        Map<String, Object> config = Map.of("hidden", true, "label", "secret");
        JsonNode result = CheckReceiptProjection.response(Map.of("check", check, "config", config));
        assertEquals(17, result.path("check").path("total").asInt());
        assertFalse(result.path("check").has("detailsHidden"));
        assertEquals(2, result.path("config").size());
    }
}
