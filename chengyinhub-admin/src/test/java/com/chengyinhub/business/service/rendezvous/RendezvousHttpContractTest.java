package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.domain.ImBlock;
import com.chengyinhub.business.mapper.ImBlockMapper;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.web.controller.api.ApiRendezvousMeetingController;
import com.chengyinhub.web.controller.api.ApiRendezvousPoolController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Standalone MVC. Synthetic OPEN rows exist only inside the test that needs them. */
class RendezvousHttpContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private final MemoryCatalog catalog = new MemoryCatalog();
    private MockMvc mvc;
    private RendezvousConsentService consent;

    @BeforeEach void setup() {
        SecurityContextHolder.clearContext();
        RendezvousProtocolTest.Db db = new RendezvousProtocolTest.Db();
        RendezvousProtocolTest.ConsentStore store = new RendezvousProtocolTest.ConsentStore(db);
        RendezvousVersionedPolicy policy = new RendezvousVersionedPolicy(catalog, new RendezvousProtocolTest.Blocks());
        consent = new RendezvousConsentService(store, policy, clock);
        RendezvousRoleSecretService secrets = new RendezvousRoleSecretService(consent, new RendezvousProtocolTest.SecretStore(db));
        RendezvousMeetingService meetings = new RendezvousMeetingService(RendezvousMeetingService.Mode.DISABLED, new EmptyMeeting(), policy, new DisabledPolicy(), new DisabledHandoff(), clock, 1000);
        mvc = MockMvcBuilders.standaloneSetup(
                new ApiRendezvousPoolController(consent, policy, secrets),
                new ApiRendezvousMeetingController(meetings)).build();
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void joinWithoutApprovedTtlIsClosedAndStoresNothing() throws Exception {
        authenticate(1);
        JsonNode body = response(post("/api/rendezvous/pool/join").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pool\":\"pool\",\"eligibilityVersion\":\"v1\"}"), 503);
        assertEquals("POLICY_CLOSED", body.get("errorCode").asText());
        assertEquals("NOT_FOUND", assertThrows(Rejection.class, () -> consent.current(1)).getCode());
    }

    @Test void lostJoinResponseIsTheSameCurrentTicket() throws Exception {
        open();
        authenticate(1);
        JsonNode joined = response(post("/api/rendezvous/pool/join").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pool\":\"pool\",\"eligibilityVersion\":\"v1\"}"), 200).get("data");
        JsonNode current = response(get("/api/rendezvous/pool/current"), 200).get("data");
        assertEquals(joined.get("ticketId").asText(), current.get("ticketId").asText());
        response(post("/api/rendezvous/pool/join").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pool\":\"pool\",\"eligibilityVersion\":\"v1\",\"ttl\":1}"), 400);
        response(post("/api/rendezvous/pool/join").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pool\":\"pool\",\"eligibilityVersion\":\"v1\",\"latitude\":1}"), 400);
    }

    @Test void scanAndCoordinatesAreNotAMeeting() throws Exception {
        authenticate(1);
        response(post("/api/rendezvous/tickets/any/challenge/confirm").contentType(MediaType.APPLICATION_JSON).content("{\"scanned\":true}"), 400);
        response(post("/api/rendezvous/tickets/any/challenge/confirm").contentType(MediaType.APPLICATION_JSON).content("{\"challengeId\":\"x\",\"latitude\":1,\"longitude\":2}"), 400);
        response(post("/api/rendezvous/tickets/any/challenge/confirm").contentType(MediaType.APPLICATION_JSON).content("{\"doubleClick\":true}"), 400);
        assertEquals(404, mvc.perform(post("/api/rendezvous/tickets/any/scan")).andReturn().getResponse().getStatus());
        JsonNode disabled = response(post("/api/rendezvous/tickets/any/challenge/confirm").contentType(MediaType.APPLICATION_JSON).content("{\"challengeId\":\"x\"}"), 503);
        assertEquals("W16_MEETING_DISABLED", disabled.get("errorCode").asText());
    }

    @Test void strangerRejectIsNotFound() throws Exception {
        open();
        View mine = consent.join(1, "pool", "v1", 1000);
        authenticate(2);
        JsonNode denied = response(post("/api/rendezvous/tickets/" + mine.ticketId + "/reject"), 404);
        authenticate(1);
        JsonNode missing = response(post("/api/rendezvous/tickets/missing/reject"), 404);
        assertEquals(denied.get("errorCode").asText(), missing.get("errorCode").asText());
        assertEquals(TicketState.WAITING, consent.read(1, mine.ticketId).ticketState);
    }

    private void open() {
        for (String dimension : new String[]{"eligibility-source", "age", "region", "time-window"})
            catalog.rows.put(dimension + "\0v1", new RendezvousVersionedPolicy.Row("OPEN", null, null, null));
        catalog.rows.put("frequency\0pool", new RendezvousVersionedPolicy.Row("OPEN", 2, null, null));
        catalog.rows.put("ticket-ttl\0v1", new RendezvousVersionedPolicy.Row("OPEN", null, 1000L, null));
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

    static final class MemoryCatalog implements RendezvousVersionedPolicy.Catalog {
        final HashMap<String, RendezvousVersionedPolicy.Row> rows = new HashMap<>();
        final HashMap<Long, Integer> counts = new HashMap<>();
        public RendezvousVersionedPolicy.Row find(String dimension, String version) { return rows.get(dimension + "\0" + version); }
        public int contacts(long actor) { return counts.getOrDefault(actor, 0); }
    }
    static final class MutableClock extends Clock {
        long now = 10_000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
    static final class EmptyMeeting implements RendezvousMeetingService.Store, RendezvousMeetingService.Tx {
        public <T> T transaction(java.util.function.Function<RendezvousMeetingService.Tx, T> work) { return work.apply(this); }
        public Ticket activeTicket(long actor) { return null; }
        public Ticket ticket(String id) { return null; }
        public Match match(String id) { return null; }
        public void save(Ticket ticket) {}
        public void save(Match match) {}
        public RendezvousMeetingService.Meeting meeting(String matchId) { return null; }
        public void save(RendezvousMeetingService.Meeting meeting) {}
    }
    static final class DisabledPolicy implements RendezvousMeetingService.Policy {
        public long activity(Match match) { return 0; }
        public RendezvousMeetingService.Evidence evaluate(RendezvousMeetingService.Meeting meeting, Ticket first, Ticket second, long now) { return null; }
    }
    static final class DisabledHandoff implements RendezvousMeetingService.Handoff {
        public boolean available() { return false; }
        public long createAndJoin(long first, long second, long activity) { throw new AssertionError("disabled handoff"); }
    }
}
