package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.GameSessionRuntimeMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlayOriginalSessionIntentTest {
    PlayBasicIntentApplication app;
    GameSessionRuntimeMapper receipts;
    TopicRouteSessionMapper routes;
    TopicRouteSession original;
    AtomicReference<TopicRouteSession> executed;

    @BeforeEach void prepare() {
        app = new PlayBasicIntentApplication();
        ReflectionTestUtils.setField(app, "responseObjectMapper", org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json()
                .timeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai")).build());
        receipts = mock(GameSessionRuntimeMapper.class);
        routes = mock(TopicRouteSessionMapper.class);
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        ReflectionTestUtils.setField(app, "playCommandReceiptMapper", receipts);
        ReflectionTestUtils.setField(app, "playCommandRouteMapper", routes);
        ReflectionTestUtils.setField(app, "cmsTopicNodeService", nodes);
        ReflectionTestUtils.setField(app, "cmsRegistrationService", registrations);
        CmsTopicNode node = new CmsTopicNode(); node.setId(3L); node.setTopicId(2L);
        when(nodes.selectCmsTopicNodeById(3L)).thenReturn(node);
        CmsRegistration pass = new CmsRegistration(); pass.setMemberId(1L);
        pass.setOwnerType(1L); pass.setOwnerId(2L); pass.setRegistrationStatus(2);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(List.of(pass));
        original = new TopicRouteSession(); original.setId(71L); original.setMemberId(1L);
        original.setTopicId(2L); original.setActivityId(0L); original.setPlayInstanceId("run-71");
        original.setStatus(TopicRouteSession.STATUS_RUNNING); original.setVersion(4);
        when(routes.selectAccessibleByIdForUpdate(71L, 1L)).thenReturn(original);
        when(routes.selectCurrentByOwner(0L, 2L, 1L)).thenReturn(original);
        when(receipts.insertReceiptClaim(any())).thenReturn(1);
        when(receipts.completeReceipt(any())).thenReturn(1);
        executed = new AtomicReference<>();
    }

    AjaxResult invoke(String action, Long session) {
        return app.executeBasicIntent(1L, "ANSWER", null, "2", "3", action, 4, null,
                session, null, null, List.of("answer"), locked -> {
                    executed.set(locked.session()); return AjaxResult.success("saved");
                });
    }

    @Test void locksExactOriginalBeforeClaimAndPassesItToWriter() {
        assertEquals(200, invoke("a", 71L).get("code"));
        assertSame(original, executed.get());
        var order = inOrder(routes, receipts);
        order.verify(routes).selectAccessibleByIdForUpdate(71L, 1L);
        order.verify(receipts).insertReceiptClaim(any());
        order.verify(receipts).completeReceipt(any());
        verify(routes, never()).selectAccessibleByIdForUpdate(eq(72L), anyLong());
    }

    @Test void missingOriginalDoesNotLockCurrentOrClaim() {
        assertEquals(409, invoke("a", 72L).get("code"));
        verify(routes, never()).selectCurrentByOwner(anyLong(), anyLong(), anyLong());
        verify(receipts, never()).insertReceiptClaim(any());
        assertNull(executed.get());
    }

    @Test void wrongOwnerOrEndedOriginalNeverExecutes() {
        original.setMemberId(99L);
        assertEquals(409, invoke("owner", 71L).get("code"));
        original.setMemberId(1L); original.setStatus(TopicRouteSession.STATUS_COMPLETED);
        assertEquals(409, invoke("ended", 71L).get("code"));
        verify(receipts, never()).insertReceiptClaim(any());
    }

    @Test void originalReceiptPrecedesOldSessionChecksAndChangedPayloadConflicts() {
        GameCommandReceipt receipt = new GameCommandReceipt();
        receipt.setActorPerspective("PLAYER"); receipt.setAction("PLAY_BASIC_ANSWER");
        receipt.setOutcome("APPLIED"); receipt.setResultJson(app.basicJson(AjaxResult.success("original")));
        receipt.setRequestHash(app.basicHash(app.basicJson(java.util.Arrays.asList(
                "ANSWER", 0L, 2L, 3L, 4, null, 71L, null, null, List.of("answer")))));
        when(receipts.selectReceiptForUpdate(eq(0L), eq(1L), anyString())).thenReturn(receipt);
        assertEquals(200, invoke("a", 71L).get("code"));
        assertEquals(409, invoke("a", 72L).get("code"));
        verifyNoInteractions(routes);
        assertNull(executed.get());
    }

    @Test void replayPreservesTheHttpTimestampRepresentation() throws Exception {
        var wire = (com.fasterxml.jackson.databind.ObjectMapper) ReflectionTestUtils.getField(app, "responseObjectMapper");
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("arrivedAt", new java.util.Date(1793200000000L));
        AjaxResult first = app.executeBasicIntent(1L, "ANSWER", null, "2", "3", "date", 4, null,
                71L, null, null, List.of("answer"), locked -> AjaxResult.success(payload));
        var saved = org.mockito.ArgumentCaptor.forClass(GameCommandReceipt.class);
        verify(receipts).completeReceipt(saved.capture());
        when(receipts.selectReceiptForUpdate(eq(0L), eq(1L), anyString())).thenReturn(saved.getValue());
        AjaxResult replay = invoke("date", 71L);
        assertEquals(wire.readTree(wire.writeValueAsString(first)), wire.readTree(wire.writeValueAsString(replay)));
        verify(receipts, times(1)).insertReceiptClaim(any());
    }

    @Test void terminalReceiptFailurePropagatesInsteadOfReturningSuccess() {
        when(receipts.completeReceipt(any())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> invoke("a", 71L));
    }
}
