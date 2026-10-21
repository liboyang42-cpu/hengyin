package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.HandoffState;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.OngoingVerdict;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.Phase;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.SoloResult;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitService.Mode;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.web.controller.api.ApiRendezvousExitController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Standalone MVC for the proposed exit route. No application context and no production bean. */
class RendezvousSafeExitHttpTest {
    private final ObjectMapper json = new ObjectMapper();
    private RendezvousSafeExitServiceTest.Ports ports;
    private RendezvousSafeExitServiceTest.Memory memory;
    private MockMvc mvc;

    @BeforeEach void setup() {
        SecurityContextHolder.clearContext();
        ports = new RendezvousSafeExitServiceTest.Ports();
        memory = new RendezvousSafeExitServiceTest.Memory();
        RendezvousSafeExitService service = new RendezvousSafeExitService(Mode.ISOLATED_CONTRACT_EVALUATION, memory,
                ports, ports, ports, ports, null, ports, ports.clock);
        mvc = MockMvcBuilders.standaloneSetup(new ApiRendezvousExitController(service)).build();
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void exitThenReadbackStaysIncomplete() throws Exception {
        authenticate(1);
        JsonNode exited = response(post("/api/rendezvous/tickets/ticket-1/exit").contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"EXIT\"}"), 200).get("data");
        assertEquals("ticket-1", exited.get("ticketId").asText());
        assertEquals("EXIT", exited.get("choice").asText());
        assertFalse(exited.get("completed").asBoolean());
        JsonNode again = response(get("/api/rendezvous/tickets/ticket-1/exit"), 200).get("data");
        assertEquals(exited.get("choice").asText(), again.get("choice").asText());
        assertEquals(1, ports.releases);
        assertEquals(0, ports.launches);
    }

    @Test void soloWithoutAPublishedGoalOrLauncherIsRejected() throws Exception {
        authenticate(1);
        JsonNode denied = response(post("/api/rendezvous/tickets/ticket-1/exit").contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"SOLO\"}"), 409);
        assertEquals("SOLO_GOAL_UNAVAILABLE", denied.get("errorCode").asText());
        assertEquals(0, memory.rows.size());
        assertEquals(0, ports.launches);
    }

    @Test void handoffStatesOtherThanNotStartedRejectSolo() throws Exception {
        authenticate(1);
        ports.verdict = RendezvousSafeExitServiceTest.verdict(Phase.MUTUAL, "match-1");
        ports.goal = "published-solo-1";
        ports.answer = HandoffState.UNKNOWN;
        assertEquals("HANDOFF_UNKNOWN", response(post("/api/rendezvous/tickets/ticket-1/exit").contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"SOLO\"}"), 409).get("errorCode").asText());
        ports.answer = HandoffState.CREATED_JOIN_UNCONFIRMED;
        assertEquals("HANDOFF_JOIN_UNCONFIRMED", response(post("/api/rendezvous/tickets/ticket-1/exit").contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"SOLO\"}"), 409).get("errorCode").asText());
        ports.answer = HandoffState.COMPLETE;
        assertEquals("HANDOFF_COMPLETE", response(post("/api/rendezvous/tickets/ticket-1/exit").contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"SOLO\"}"), 409).get("errorCode").asText());
        assertEquals(0, ports.launches);
        assertEquals(0, memory.rows.size());
    }

    @Test void authoritativeResultIsTheOnlyCompletion() throws Exception {
        ports.verdict = RendezvousSafeExitServiceTest.verdict(Phase.ONE_SIDED, "match-1");
        ports.answer = HandoffState.NOT_STARTED;
        ports.goal = "published-solo-1";
        ports.launcherPresent = true;
        RendezvousSafeExitService service = new RendezvousSafeExitService(Mode.ISOLATED_CONTRACT_EVALUATION, memory,
                ports, ports, ports, ports, ports, ports, ports.clock);
        mvc = MockMvcBuilders.standaloneSetup(new ApiRendezvousExitController(service)).build();
        authenticate(1);
        JsonNode started = response(post("/api/rendezvous/tickets/ticket-1/exit").contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"SOLO\"}"), 200).get("data");
        assertEquals("STARTED", started.get("soloState").asText());
        assertFalse(started.get("completed").asBoolean());
        assertEquals("published-solo-1", started.get("goalRef").asText());
        ports.status = SoloResult.Status.COMPLETED;
        JsonNode done = response(get("/api/rendezvous/tickets/ticket-1/exit"), 200).get("data");
        assertTrue(done.get("completed").asBoolean());
        assertEquals("COMPLETED", done.get("soloState").asText());
        assertEquals(1, ports.launches);
        assertFalse(done.has("peer"));
        assertFalse(done.has("teamId"));
    }

    @Test void strayFieldsPenaltyAndStrangerAreRejected() throws Exception {
        authenticate(1);
        assertEquals("INVALID_REQUEST", response(post("/api/rendezvous/tickets/ticket-1/exit").contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"EXIT\",\"penalty\":1}"), 400).get("errorCode").asText());
        assertEquals("INVALID_REQUEST", response(post("/api/rendezvous/tickets/ticket-1/exit?difficulty=1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"EXIT\"}"), 400).get("errorCode").asText());
        assertEquals(0, ports.releases);
        response(post("/api/rendezvous/tickets/ticket-1/exit").contentType(MediaType.APPLICATION_JSON).content("{\"choice\":\"EXIT\"}"), 200);
        authenticate(2);
        assertEquals("NOT_FOUND", response(get("/api/rendezvous/tickets/ticket-1/exit"), 404).get("errorCode").asText());
        SecurityContextHolder.clearContext();
        assertEquals("AUTH_REQUIRED", response(post("/api/rendezvous/tickets/ticket-1/exit").contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"EXIT\"}"), 401).get("errorCode").asText());
    }

    private void authenticate(long actor) {
        AppUser app = new AppUser();
        app.setUserId(actor);
        LoginAppUser login = new LoginAppUser(999L, app);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, Collections.emptyList()));
    }

    private JsonNode response(org.springframework.test.web.servlet.RequestBuilder request, int status) throws Exception {
        var result = mvc.perform(request).andReturn();
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
        return json.readTree(result.getResponse().getContentAsString());
    }
}
