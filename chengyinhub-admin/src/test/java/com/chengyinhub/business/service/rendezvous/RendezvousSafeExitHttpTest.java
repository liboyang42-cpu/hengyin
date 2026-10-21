package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.web.controller.api.ApiRendezvousExitController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Standalone MVC. The controller is not registered as a production bean. */
class RendezvousSafeExitHttpTest {
    private final ObjectMapper json = new ObjectMapper();
    private RendezvousExitDatabase db;
    private MockMvc mvc;

    @BeforeEach void setup() throws Exception {
        SecurityContextHolder.clearContext();
        db = RendezvousExitDatabase.open();
        mvc = MockMvcBuilders.standaloneSetup(new ApiRendezvousExitController(db.service)).build();
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        if (db != null) db.close();
    }

    @Test void routeStaysProfileGated() {
        assertEquals("w16-consent-contract", ApiRendezvousExitController.class.getAnnotation(Profile.class).value()[0]);
        assertEquals(RendezvousSafeExitService.class, ApiRendezvousExitController.class.getAnnotation(ConditionalOnBean.class).value()[0]);
    }

    @Test void exitThenReadbackStaysIncomplete() throws Exception {
        RendezvousConsentService.View ticket = db.consent.join(1, "pool", "v1", 100_000);
        authenticate(1);
        JsonNode exited = response(post("/api/rendezvous/tickets/" + ticket.ticketId + "/exit")
                .contentType(MediaType.APPLICATION_JSON).content("{\"choice\":\"EXIT\"}"), 200).get("data");
        assertEquals("EXIT", exited.get("choice").asText());
        assertFalse(exited.get("completed").asBoolean());
        assertFalse(exited.get("soloAllowed").asBoolean());
        assertEquals("NOT_STARTED", exited.get("handoff").asText());
        JsonNode again = response(get("/api/rendezvous/tickets/" + ticket.ticketId + "/exit"), 200).get("data");
        assertEquals("EXIT", again.get("choice").asText());
        assertFalse(again.get("completed").asBoolean());
        assertEquals(0, db.count("w16_match_reservation"));
    }

    @Test void soloIsRejectedAndDoesNotRelease() throws Exception {
        RendezvousConsentService.View[] pair = db.pair();
        authenticate(1);
        JsonNode denied = response(post("/api/rendezvous/tickets/" + pair[0].ticketId + "/exit")
                .contentType(MediaType.APPLICATION_JSON).content("{\"choice\":\"SOLO\"}"), 409);
        assertEquals("HANDOFF_UNKNOWN", denied.get("errorCode").asText());
        assertEquals(2, db.count("w16_match_reservation"));
        assertEquals(0, db.count("w16_safe_exit"));
    }

    @Test void strayFieldsAndStrangerAreRejected() throws Exception {
        RendezvousConsentService.View ticket = db.consent.join(1, "pool", "v1", 100_000);
        authenticate(1);
        String path = "/api/rendezvous/tickets/" + ticket.ticketId + "/exit";
        assertEquals("INVALID_REQUEST", response(post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"EXIT\",\"penalty\":1}"), 400).get("errorCode").asText());
        assertEquals("INVALID_REQUEST", response(post(path + "?difficulty=1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"EXIT\"}"), 400).get("errorCode").asText());
        assertEquals(0, db.count("w16_safe_exit"));
        response(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"choice\":\"EXIT\"}"), 200);
        authenticate(2);
        assertEquals("NOT_FOUND", response(get(path), 404).get("errorCode").asText());
        SecurityContextHolder.clearContext();
        assertEquals("AUTH_REQUIRED", response(post(path).contentType(MediaType.APPLICATION_JSON)
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
