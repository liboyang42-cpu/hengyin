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

    @Test
    void sameIdentityReachesOriginalCheckAndReceiptDoesNotCallCheck() throws Exception {
        Recording port = new Recording();
        port.checkBody = JSON.readTree("{\"runId\":9,\"requestId\":\"req-1\",\"status\":\"COMMITTED\"}");
        port.receiptBody = JSON.readTree("{\"runId\":9,\"requestId\":\"req-1\",\"status\":\"UNKNOWN\"}");
        OriginalRpgCheckCaller caller = new OriginalRpgCheckCaller(port);
        JsonNode checked = caller.check(7L, 3L, 1L, 4L, "spot", 9L, "req-1", 2, "ROLL_CHECK", false, null);
        assertEquals("COMMITTED", checked.path("status").asText());
        assertEquals(1, port.checks.size());
        JsonNode read = caller.receipt(7L, 9L, 4L, "spot", "req-1");
        assertEquals("UNKNOWN", read.path("status").asText());
        assertEquals(1, port.checks.size());
        assertEquals(1, port.receipts.size());
    }

    @Test
    void otherRunOrGameReceiptDoesNotOccupyTheOriginalCheck() throws Exception {
        Recording port = new Recording();
        port.checkBody = JSON.readTree("{\"runId\":8,\"requestId\":\"req-1\",\"status\":\"COMMITTED\"}");
        port.receiptBody = JSON.readTree("{\"runId\":9,\"requestId\":\"req-1\",\"status\":\"APPLIED\"}");
        OriginalRpgCheckCaller caller = new OriginalRpgCheckCaller(port);
        assertNull(caller.check(7L, 3L, 1L, 4L, "spot", 9L, "req-1", 2, "ROLL_CHECK", false, null));
        assertEquals(1, port.checks.size());
        assertNull(caller.receipt(7L, 9L, 4L, "spot", "req-1"));
        assertNull(caller.check(0L, 3L, 1L, 4L, "spot", 9L, "req-1", 2, "ROLL_CHECK", false, null));
        assertNull(caller.check(7L, 3L, 1L, 4L, "spot", 9L, "req-1", 2, "CREATE_CHARACTER", false, null));
        assertEquals(1, port.checks.size());
        assertTrue(port.receipts.isEmpty() == false);
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
