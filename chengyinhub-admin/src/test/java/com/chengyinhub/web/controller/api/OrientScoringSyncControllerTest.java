package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IOrientScoringService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Spring MVC form/query binding; no authentication-policy changes. */
class OrientScoringSyncControllerTest {
    @Test
    void syncFormAndReceiptKeepExactSessionAndOriginalKeyAndIgnoreForgedOwner() throws Exception {
        IOrientScoringService service = mock(IOrientScoringService.class);
        ApiOrientScoringController controller = new ApiOrientScoringController() {
            @Override public Long getAppUserId() { return 7L; }
        };
        ReflectionTestUtils.setField(controller, "orientScoringService", service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        Map<String, Object> receipt = Map.of("state", "SYNCED", "kind", "SYNC", "memberId", 7L,
                "sessionId", Long.MAX_VALUE, "idempotencyKey", "sync:original", "added", 0, "totalCenti", -25L);
        when(service.syncSession(7L, Long.MAX_VALUE, "sync:original")).thenReturn(receipt);
        when(service.receipt(7L, "SYNC", Long.MAX_VALUE, "sync:original")).thenReturn(receipt);
        mvc.perform(post("/api/orient/scoring/session/" + Long.MAX_VALUE + "/sync")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("idempotencyKey", "sync:original").param("memberId", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sessionId").value(Long.toString(Long.MAX_VALUE)))
                .andExpect(jsonPath("$.data.idempotencyKey").value("sync:original"));
        mvc.perform(get("/api/orient/scoring/receipt").param("kind", "SYNC")
                .param("sessionId", Long.toString(Long.MAX_VALUE)).param("idempotencyKey", "sync:original"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("SYNCED"));
        verify(service).syncSession(7L, Long.MAX_VALUE, "sync:original");
        verify(service).receipt(7L, "SYNC", Long.MAX_VALUE, "sync:original");
        verifyNoMoreInteractions(service);
    }

    @Test
    void unauthenticatedSyncDoesNotReachService() throws Exception {
        IOrientScoringService service = mock(IOrientScoringService.class);
        ApiOrientScoringController controller = new ApiOrientScoringController() {
            @Override public Long getAppUserId() { return null; }
        };
        ReflectionTestUtils.setField(controller, "orientScoringService", service);
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/api/orient/scoring/session/10/sync").param("idempotencyKey", "sync:original"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500));
        verifyNoInteractions(service);
    }
    @Test
    void recoveryKeepsLongIdsExactAndBoardScopeComesFromOwnedSession() throws Exception {
        IOrientScoringService service = mock(IOrientScoringService.class);
        ApiOrientScoringController controller = new ApiOrientScoringController() {
            @Override public Long getAppUserId() { return 7L; }
        };
        ReflectionTestUtils.setField(controller, "orientScoringService", service);
        var recovery = new com.chengyinhub.business.domain.OrientScoreRecovery();
        recovery.setId(Long.MAX_VALUE); recovery.setSessionId(Long.MAX_VALUE); recovery.setMemberId(7L);
        recovery.setKind("SYNC"); recovery.setStatus(0); recovery.setHandledBy("private-reviewer-account");
        when(service.submitRecovery(7L, Long.MAX_VALUE, "SYNC")).thenReturn(recovery);
        when(service.leaderboard(7L, Long.MAX_VALUE)).thenReturn(Map.of("sessionId", Long.MAX_VALUE, "mode", "SCORE", "list", java.util.List.of()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/orient/scoring/session/" + Long.MAX_VALUE + "/recovery").param("kind", "SYNC").param("memberId", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(Long.toString(Long.MAX_VALUE)))
                .andExpect(jsonPath("$.data.memberId").value("7")).andExpect(jsonPath("$.data.handledBy").doesNotExist());
        mvc.perform(get("/api/orient/scoring/session/" + Long.MAX_VALUE + "/leaderboard").param("topicId", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value("SCORE"));
        verify(service).submitRecovery(7L, Long.MAX_VALUE, "SYNC");
        verify(service).leaderboard(7L, Long.MAX_VALUE);
        verifyNoMoreInteractions(service);
    }

}
