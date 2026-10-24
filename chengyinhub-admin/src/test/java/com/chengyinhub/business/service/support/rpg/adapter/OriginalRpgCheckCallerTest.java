package com.chengyinhub.business.service.support.rpg.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OriginalRpgCheckCallerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EXECUTE = "{\"runId\":9,\"attemptId\":\"4\",\"checkId\":\"spot\","
            + "\"stateVersion\":3,\"characterVersion\":1,\"definitionVersion\":1,\"releaseId\":\"rel\"}";

    @Test
    void legalPostSuccessIsKeptAndSameKeyReceiptDoesNotWrite() throws Exception {
        Recording port = new Recording();
        port.checkBody = JSON.readTree(EXECUTE);
        port.receiptBody = JSON.readTree("{\"runId\":9,\"requestId\":\"req-1\",\"status\":\"UNKNOWN\"}");
        OriginalRpgCheckCaller caller = new OriginalRpgCheckCaller(port);
        JsonNode checked = caller.check(7L, 3L, 1L, 4L, "spot", 9L, "req-1", 2, "ROLL_CHECK", false, "4");
        assertEquals(9L, checked.path("runId").asLong());
        assertEquals("spot", checked.path("checkId").asText());
        assertEquals(3L, checked.path("stateVersion").asLong());
        assertTrue(checked.path("requestId").isMissingNode());
        JsonNode read = caller.receipt(7L, 9L, 4L, "spot", "req-1");
        assertEquals("UNKNOWN", read.path("status").asText());
        assertEquals(1, port.checks.size());
        assertEquals(1, port.receipts.size());
        assertNull(OriginalRpgCheckCaller.acceptOriginalOrLegacy(null, true, JSON.readTree("{\"legacy\":true}")));
        assertEquals(true, OriginalRpgCheckCaller.acceptOriginalOrLegacy(null, false,
                JSON.readTree("{\"legacy\":true}")).path("legacy").asBoolean());
    }

    @Test
    void receiptShapedPostAndWrongRunOrVersionAreNotTheExecuteResult() throws Exception {
        Recording port = new Recording();
        port.checkBody = JSON.readTree("{\"runId\":9,\"requestId\":\"req-1\",\"status\":\"COMMITTED\"}");
        OriginalRpgCheckCaller caller = new OriginalRpgCheckCaller(port);
        assertNull(caller.check(7L, 3L, 1L, 4L, "spot", 9L, "req-1", 2, "ROLL_CHECK", false, "4"));
        port.checkBody = JSON.readTree(EXECUTE.replace("\"runId\":9", "\"runId\":8"));
        assertNull(caller.check(7L, 3L, 1L, 4L, "spot", 9L, "req-1", 2, "ROLL_CHECK", false, "4"));
        port.checkBody = JSON.readTree(EXECUTE.replace("\"stateVersion\":3", "\"stateVersion\":1"));
        JsonNode earlier = caller.check(7L, 3L, 1L, 4L, "spot", 9L, "req-1", 2, "ROLL_CHECK", false, "4");
        assertEquals(1L, earlier.path("stateVersion").asLong());
        port.checkBody = JSON.readTree(EXECUTE);
        assertNull(caller.check(0L, 3L, 1L, 4L, "spot", 9L, "req-1", 2, "ROLL_CHECK", false, "4"));
        assertNull(caller.receipt(7L, 9L, 4L, "spot", "req-1"));
        port.receiptBody = JSON.readTree("{\"runId\":9,\"requestId\":\"req-1\",\"status\":\"APPLIED\"}");
        assertNull(caller.receipt(7L, 9L, 4L, "spot", "req-1"));
        port.receiptBody = JSON.readTree("{\"runId\":9,\"requestId\":\"req-1\",\"status\":\"COMMITTED\"}");
        assertNull(caller.receipt(7L, 9L, 4L, "spot", "req-1"));
        port.receiptBody = JSON.readTree("{\"runId\":9,\"requestId\":\"req-1\",\"status\":\"COMMITTED\","
                + "\"receipt\":{\"runId\":9,\"stateVersion\":2}}");
        JsonNode committed = caller.receipt(7L, 9L, 4L, "spot", "req-1");
        assertEquals("COMMITTED", committed.path("status").asText());
        assertEquals(2L, committed.path("receipt").path("stateVersion").asLong());
    }

    @Test
    void frozenSameStageReplayKeepsExpectedVersion() throws Exception {
        Recording port = new Recording();
        port.checkBody = JSON.readTree(EXECUTE.replace("\"stateVersion\":3", "\"stateVersion\":2"));
        OriginalRpgCheckCaller caller = new OriginalRpgCheckCaller(port);
        JsonNode replay = caller.check(7L, 3L, 1L, 4L, "spot", 9L, "req-1", 2, "ROLL_CHECK", false, "4");
        assertEquals(2L, replay.path("stateVersion").asLong());
        port.checkBody = JSON.readTree(EXECUTE.replace("\"stateVersion\":3", "\"stateVersion\":2.5"));
        assertNull(caller.check(7L, 3L, 1L, 4L, "spot", 9L, "req-1", 2, "ROLL_CHECK", false, "4"));
    }

    private static final class Recording implements OriginalRpgCheckCaller.Port {
        final List<String> checks = new ArrayList<String>();
        final List<String> receipts = new ArrayList<String>();
        JsonNode checkBody;
        JsonNode receiptBody;

        public JsonNode check(Long memberId, Long topicId, Long activityId, Long nodeId, String checkId,
                Long runId, String requestId, Integer expectedVersion, String operation,
                boolean useInspiration, String attemptId) {
            checks.add(memberId + ":" + runId + ":" + requestId + ":" + operation);
            return checkBody;
        }

        public JsonNode receipt(Long memberId, Long runId, Long nodeId, String checkId, String requestId) {
            receipts.add(memberId + ":" + runId + ":" + requestId);
            return receiptBody;
        }
    }
}
