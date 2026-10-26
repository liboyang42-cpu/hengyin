package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CoopInvite;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoopInviteExactIdJsonTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void responseAddsExactStringWithoutChangingExistingNumericIdOrFacts() throws Exception {
        CoopInvite invite = new CoopInvite();
        invite.setId(9007199254740993L);
        invite.setStatus(5);
        invite.setDepositRefundPending(true);
        JsonNode response = json.readTree(json.writeValueAsBytes(invite));
        assertEquals("9007199254740993", response.path("inviteId").asText());
        assertTrue(response.path("inviteId").isTextual());
        assertTrue(response.path("id").isIntegralNumber());
        assertEquals(9007199254740993L, response.path("id").longValue());
        assertEquals(5, response.path("status").intValue());
        assertTrue(response.path("depositRefundPending").booleanValue());
    }

    @Test
    void requestAliasCannotSetOrOverwriteAuthoritativeId() throws Exception {
        CoopInvite request = json.readValue("{\"id\":\"9007199254740993\",\"inviteId\":\"7\",\"status\":1}", CoopInvite.class);
        assertEquals(9007199254740993L, request.getId());
        assertEquals("9007199254740993", json.readTree(json.writeValueAsBytes(request)).path("inviteId").asText());
        assertNull(json.readValue("{\"inviteId\":\"7\"}", CoopInvite.class).getId());
    }
    @Test
    void originalRequestProjectionKeepsEveryLongExactAndUnavailableLeaksNoMetadata() throws Exception {
        com.chengyinhub.business.domain.CoopInviteRequestReceipt row = new com.chengyinhub.business.domain.CoopInviteRequestReceipt();
        row.setRequestId("vr_original"); row.setInviteId(9007199254740993L); row.setInviteType(0);
        row.setTopicId(9007199254740995L); row.setToType("merchant"); row.setToId(9007199254740997L); row.setScope("");
        JsonNode created = json.readTree(json.writeValueAsBytes(
                com.chengyinhub.business.domain.dto.CoopInviteRequestResult.created(row, true)));
        for (String field : java.util.List.of("inviteId", "topicId", "toId")) assertTrue(created.path(field).isTextual());
        assertEquals("9007199254740993", created.path("inviteId").asText());
        assertEquals("", created.path("scope").asText());
        assertTrue(created.path("replayed").booleanValue());
        JsonNode unavailable = json.readTree(json.writeValueAsBytes(
                com.chengyinhub.business.domain.dto.CoopInviteRequestResult.state("UNAVAILABLE", "vr_original")));
        assertFalse(unavailable.has("inviteId")); assertFalse(unavailable.has("scope")); assertFalse(unavailable.has("topicId"));
    }

}
