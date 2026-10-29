package com.chengyinhub.business.service.support.rpg.mechanism;

import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MechanismRequestIdBinderTest {
    @Test
    void bindReusesOriginalIdAndUnknownRecoverDoesNotMint() {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        MechanismRequestIdBinder.LedgerEntry first = binder.bind("r1", "progressClock", "TICK_CLOCK");
        MechanismRequestIdBinder.LedgerEntry again = binder.bind("r1", "progressClock", "TICK_CLOCK");
        assertEquals(first, again);
        assertEquals("r1", again.getRequestId());

        binder.markUnknown("r1");
        MechanismRequestIdBinder.Play play = new MechanismRequestIdBinder.Play();
        play.setOriginalRequestId("r1");
        Map<String, Object> unknown = binder.recoverUnknown(play);
        assertEquals("r1", unknown.get("requestId"));
        assertEquals(MechanismRequestIdBinder.UNKNOWN, unknown.get("status"));
        assertEquals(Boolean.FALSE, unknown.get("mintedNewRequest"));

        TopicRouteAction original = new TopicRouteAction();
        original.setActionId("r1");
        original.setReceiptId("r1");
        original.setResponseJson("{\"requestId\":\"r1\",\"value\":3,\"status\":\"APPLIED\"}");
        play.setOriginalAction(original);
        Map<String, Object> restored = binder.recoverUnknown(play);
        assertEquals("r1", restored.get("requestId"));
        assertEquals(MechanismRequestIdBinder.APPLIED, restored.get("status"));
        assertEquals(3, ((Number) restored.get("value")).intValue());
    }

    @Test
    void oneShotRunsEffectOnceForOriginalRequestId() {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        MechanismRequestIdBinder.Play play = new MechanismRequestIdBinder.Play();
        play.setOriginalRequestId("r2");
        AtomicInteger runs = new AtomicInteger();
        Map<String, Object> first = binder.oneShot(play, "progressClock", "TICK_CLOCK", () -> {
            runs.incrementAndGet();
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("value", Integer.valueOf(runs.get()));
            return result;
        });
        Map<String, Object> second = binder.oneShot(play, "progressClock", "TICK_CLOCK", () -> {
            runs.incrementAndGet();
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("value", Integer.valueOf(runs.get()));
            return result;
        });
        assertEquals(1, runs.get());
        assertEquals("r2", first.get("requestId"));
        assertEquals("r2", second.get("requestId"));
        assertEquals(Boolean.TRUE, second.get("replay"));
        assertEquals(1, ((Number) second.get("value")).intValue());
    }

    @Test
    void emptyRequestIdIsRejected() {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        assertThrows(IllegalArgumentException.class, () -> binder.bind("  ", "x", "y"));
        MechanismRequestIdBinder.Play play = new MechanismRequestIdBinder.Play();
        assertThrows(IllegalArgumentException.class, () -> binder.recoverUnknown(play));
        assertThrows(IllegalArgumentException.class, () -> binder.recoverOriginal(play));
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueBindApplies() {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        MechanismRequestIdBinder.Play unseen = new MechanismRequestIdBinder.Play();
        unseen.setOriginalRequestId("c03-bind-never");
        Map<String, Object> recovered = binder.recoverOriginal(unseen);
        assertEquals("c03-bind-never", recovered.get("requestId"));
        assertEquals(MechanismRequestIdBinder.UNKNOWN, recovered.get("status"));
        assertEquals(Boolean.FALSE, recovered.get("mintedNewRequest"));
        assertEquals(Boolean.FALSE, recovered.get("replay"));
        assertNull(binder.existing("c03-bind-never"), "未见 id 的 recoverOriginal 不得占账");
        MechanismRequestIdBinder.LedgerEntry first = binder.bind("c03-bind-never", "progressClock", "TICK_CLOCK");
        assertEquals("c03-bind-never", first.getRequestId());
        assertEquals(MechanismRequestIdBinder.PENDING, first.getStatus());
        assertEquals(first, binder.existing("c03-bind-never"));
        MechanismRequestIdBinder.LedgerEntry replay = binder.bind("c03-bind-never", "progressClock", "TICK_CLOCK");
        assertEquals(first, replay);
        assertEquals("c03-bind-never", binder.existing("c03-bind-never").getRequestId());
    }

    @Test
    void recoverFromRouteActionReadsExistingMapperRowWithoutOccupyingLedger() {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        MechanismOriginalActionAdapter missing = new MechanismOriginalActionAdapter(binder);
        MechanismOriginalActionAdapter.WaitingSourceException waiting = assertThrows(
                MechanismOriginalActionAdapter.WaitingSourceException.class,
                () -> missing.recoverFromRouteAction(30L, 7L, "check:1:alarm:tick"));
        assertEquals(MechanismOriginalActionAdapter.SELECT_ACTION_BY_ACTION, waiting.getWaitingCapability());
        assertEquals("WAITING_SOURCE " + MechanismOriginalActionAdapter.SELECT_ACTION_BY_ACTION, waiting.getMessage());
        assertNull(missing.recoverFromRouteAction(null, 7L, "check:1:alarm:tick"));
        assertNull(missing.recoverFromRouteAction(30L, null, "check:1:alarm:tick"));
        assertNull(missing.recoverFromRouteAction(30L, 7L, null));
        assertNull(missing.recoverFromRouteAction(30L, 7L, "   "));
        assertNull(binder.existing("check:1:alarm:tick"));

        TopicRouteSessionMapper mapper = mock(TopicRouteSessionMapper.class);
        when(mapper.selectActionByAction(30L, 7L, "c03-route-missing")).thenReturn(null);
        TopicRouteAction stored = new TopicRouteAction();
        stored.setSessionId(30L);
        stored.setMemberId(7L);
        stored.setActionId("check:1:alarm:tick");
        stored.setResponseJson("{\"requestId\":\"check:1:alarm:tick\",\"status\":\"APPLIED\",\"value\":1}");
        when(mapper.selectActionByAction(30L, 7L, "check:1:alarm:tick")).thenReturn(stored);

        MechanismOriginalActionAdapter.OriginalActionSource source =
                new MechanismOriginalActionAdapter.MapperOriginalActionSource(mapper);
        MechanismOriginalActionAdapter wired = new MechanismOriginalActionAdapter(binder, source);
        assertNull(wired.recoverFromRouteAction(30L, 7L, "c03-route-missing"));
        assertNull(binder.existing("c03-route-missing"), "未见 actionId 不得占内存账");
        MechanismRequestIdBinder.LedgerEntry first = binder.bind("c03-route-missing",
                MechanismRequestIdBinder.MECH_PROGRESS_CLOCK, "TICK_CLOCK");
        assertEquals(MechanismRequestIdBinder.PENDING, first.getStatus());
        assertEquals(first, binder.existing("c03-route-missing"));

        TopicRouteAction other = new TopicRouteAction();
        other.setSessionId(99L);
        other.setMemberId(8L);
        other.setActionId("game:done");
        when(mapper.selectActionByAction(30L, 7L, "c03-other-action")).thenReturn(other);
        assertNull(wired.recoverFromRouteAction(30L, 7L, "c03-other-action"));
        TopicRouteAction recovered = wired.recoverFromRouteAction(30L, 7L, "check:1:alarm:tick");
        assertEquals("check:1:alarm:tick", recovered.getActionId());
        assertEquals(stored.getResponseJson(), recovered.getResponseJson());
        assertNull(binder.existing("check:1:alarm:tick"), "回读原动作不得写入内存账");
        verify(mapper).selectActionByAction(30L, 7L, "c03-route-missing");
        verify(mapper).selectActionByAction(30L, 7L, "check:1:alarm:tick");
        verify(mapper, never()).insertAction(any());
    }
}
