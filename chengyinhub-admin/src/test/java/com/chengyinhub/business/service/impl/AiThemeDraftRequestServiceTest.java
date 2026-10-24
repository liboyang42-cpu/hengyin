package com.chengyinhub.business.service.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftReq;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftRequestState;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftResp;
import com.chengyinhub.business.domain.vo.ai.AiThemePlan;
import com.chengyinhub.business.service.IAiCreatorService;
import com.chengyinhub.business.service.ai.AiServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** #22:同一 requestId 只调一次模型;回读只读;结果未知时如实 UNKNOWN。 */
class AiThemeDraftRequestServiceTest {
    private static final Long MEMBER = 501L;
    private static final String RID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    private final Map<Object, Object> store = new HashMap<>();
    private AiThemeDraftRequestService service;
    private IAiCreatorService creator;
    private ValueOperations<Object, Object> values;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        service = new AiThemeDraftRequestService();
        creator = mock(IAiCreatorService.class);
        RedisTemplate<Object, Object> redis = mock(RedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(any(), any(), anyLong(), eq(TimeUnit.HOURS))).thenAnswer(inv ->
                store.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
        org.mockito.Mockito.doAnswer(inv -> { store.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(values).set(any(), any(), anyLong(), eq(TimeUnit.HOURS));
        when(values.get(any())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        ReflectionTestUtils.setField(service, "aiCreatorService", creator);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
    }

    private static AiThemeDraftReq req() {
        AiThemeDraftReq req = new AiThemeDraftReq();
        req.setIdea("老城咖啡");
        req.setRequestId(RID);
        return req;
    }

    private static AiThemeDraftResp draft(String title, int remaining) {
        AiThemePlan plan = new AiThemePlan();
        plan.setTitle(title);
        AiThemeDraftResp resp = new AiThemeDraftResp();
        resp.setDraft(plan);
        resp.setRemainingQuota(remaining);
        return resp;
    }

    @Test
    void replayReturnsFirstDraftWithoutCallingModelAgain() {
        when(creator.themeDraft(any(), eq(MEMBER))).thenReturn(draft("第一份", 2), draft("第二份", 1));
        AiThemeDraftRequestState first = service.submit(req(), MEMBER);
        AiThemeDraftRequestState again = service.submit(req(), MEMBER);
        assertEquals(AiThemeDraftRequestState.SUCCEEDED, first.getState());
        assertFalse(first.isReplayed());
        assertEquals("第一份", again.getResult().getDraft().getTitle());
        assertTrue(again.isReplayed());
        assertEquals(Integer.valueOf(2), again.getResult().getRemainingQuota());
        verify(creator, times(1)).themeDraft(any(), eq(MEMBER));
        AiThemeDraftRequestState read = service.read(MEMBER, RID);
        assertEquals("第一份", read.getResult().getDraft().getTitle());
    }

    @Test
    void readIsOwnerScoped() {
        when(creator.themeDraft(any(), eq(MEMBER))).thenReturn(draft("我的", 2));
        service.submit(req(), MEMBER);
        AiThemeDraftRequestState other = service.read(MEMBER + 1, RID);
        assertEquals(AiThemeDraftRequestState.NOT_FOUND, other.getState());
        assertNull(other.getResult());
    }

    @Test
    void knownFailureIsTerminalAndNotRetriedUnderSameRequestId() {
        when(creator.themeDraft(any(), eq(MEMBER))).thenThrow(new AiServiceException("今日AI次数已用完"));
        AiThemeDraftRequestState failed = service.submit(req(), MEMBER);
        assertEquals(AiThemeDraftRequestState.FAILED, failed.getState());
        assertEquals("今日AI次数已用完", failed.getMessage());
        assertEquals(AiThemeDraftRequestState.FAILED, service.submit(req(), MEMBER).getState());
        verify(creator, times(1)).themeDraft(any(), eq(MEMBER));
    }

    @Test
    void inFlightIsProcessingThenUnknownAfterLeaseNeverNotGenerated() {
        JSONObject processing = new JSONObject();
        processing.put("state", "PROCESSING");
        processing.put("startedAt", System.currentTimeMillis());
        store.put(AiThemeDraftRequestService.key(MEMBER, RID), processing.toJSONString());
        assertEquals(AiThemeDraftRequestState.PROCESSING, service.submit(req(), MEMBER).getState());
        processing.put("startedAt", System.currentTimeMillis() - AiThemeDraftRequestService.LEASE_MILLIS - 1000L);
        store.put(AiThemeDraftRequestService.key(MEMBER, RID), processing.toJSONString());
        assertEquals(AiThemeDraftRequestState.UNKNOWN, service.read(MEMBER, RID).getState());
        assertEquals(AiThemeDraftRequestState.UNKNOWN, service.submit(req(), MEMBER).getState());
        verify(creator, never()).themeDraft(any(), any());
    }

    @Test
    void storageFailureBeforeClaimNeverCallsModel() {
        when(values.setIfAbsent(any(), any(), anyLong(), eq(TimeUnit.HOURS))).thenThrow(new RuntimeException("redis down"));
        assertThrows(AiThemeDraftRequestService.StateUnavailableException.class, () -> service.submit(req(), MEMBER));
        verify(creator, never()).themeDraft(any(), any());
    }

    @Test
    void lostTerminalWriteDegradesToUnknownNotNotFound() {
        when(creator.themeDraft(any(), eq(MEMBER))).thenReturn(draft("生成了", 2));
        org.mockito.Mockito.doThrow(new RuntimeException("redis down")).when(values).set(any(), any(), anyLong(), eq(TimeUnit.HOURS));
        assertEquals(AiThemeDraftRequestState.SUCCEEDED, service.submit(req(), MEMBER).getState());
        // 记录仍是 PROCESSING:租约内回读为 PROCESSING,绝不是 NOT_FOUND(否则客户端会以为可以放心重来)。
        assertEquals(AiThemeDraftRequestState.PROCESSING, service.read(MEMBER, RID).getState());
    }

    @Test
    void requestIdFormatIsStrict() {
        assertTrue(AiThemeDraftRequestService.isValidRequestId(RID));
        assertFalse(AiThemeDraftRequestService.isValidRequestId("abc"));
        assertFalse(AiThemeDraftRequestService.isValidRequestId(null));
    }

    // H60fc request-unknown.review.patch first case; the TTL case belongs to proposal 02.
    @Test
    void providerUnknownKeepsOriginalRequestUnknownOnSubmitReadAndReplay() {
        when(creator.themeDraft(any(), eq(MEMBER))).thenThrow(
                new com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException("provider outcome unknown", null));
        AiThemeDraftRequestState first = service.submit(req(), MEMBER);
        assertEquals(AiThemeDraftRequestState.UNKNOWN, first.getState());
        assertEquals(RID, first.getRequestId());
        assertEquals(AiThemeDraftRequestState.UNKNOWN, service.read(MEMBER, RID).getState());
        AiThemeDraftRequestState replay = service.submit(req(), MEMBER);
        assertEquals(AiThemeDraftRequestState.UNKNOWN, replay.getState());
        assertTrue(replay.isReplayed());
        assertEquals(AiThemeDraftRequestState.UNKNOWN,
                JSONObject.parseObject(String.valueOf(store.get(AiThemeDraftRequestService.key(MEMBER, RID)))).getString("state"));
        assertNull(first.getResult()); assertNull(replay.getResult());
        verify(creator, times(1)).themeDraft(any(), eq(MEMBER));
    }

    @Test
    void genericInputUnknownKeepsOriginalFailedClassification() {
        when(creator.themeDraft(any(), eq(MEMBER))).thenThrow(new AiServiceException(AiServiceException.Reason.UNKNOWN, "input review unavailable"));
        AiThemeDraftRequestState first = service.submit(req(), MEMBER);
        assertEquals(AiThemeDraftRequestState.FAILED, first.getState());
        assertEquals("input review unavailable", first.getMessage());
        assertEquals(AiThemeDraftRequestState.FAILED, service.read(MEMBER, RID).getState());
        assertEquals(AiThemeDraftRequestState.FAILED, service.submit(req(), MEMBER).getState());
        verify(creator, times(1)).themeDraft(any(), eq(MEMBER));
    }

    @Test
    void wrappedProviderUnknownAndLookalikeMessagesDoNotExpandTheTypedRule() {
        java.util.List<RuntimeException> failures = java.util.List.of(
                new AiServiceException(AiServiceException.Reason.UNKNOWN, "ordinary failure",
                        new com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException("nested", null)),
                new RuntimeException(new com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException("nested", null)),
                new AiServiceException("AiProviderOutcomeUnknownException UNKNOWN"));
        for (RuntimeException failure : failures) {
            AiThemeDraftReq request = req(); request.setRequestId(java.util.UUID.randomUUID().toString());
            org.mockito.Mockito.doThrow(failure).when(creator).themeDraft(any(), eq(MEMBER));
            assertEquals(AiThemeDraftRequestState.FAILED, service.submit(request, MEMBER).getState());
            assertEquals(AiThemeDraftRequestState.FAILED, service.read(MEMBER, request.getRequestId()).getState());
            assertEquals(AiThemeDraftRequestState.FAILED, service.submit(request, MEMBER).getState());
        }
        verify(creator, times(failures.size())).themeDraft(any(), eq(MEMBER));
    }

    @Test
    void invalidDraftFailureKeepsExistingFailureAndSameRequestDoesNotRegenerate() {
        when(creator.themeDraft(any(), eq(MEMBER))).thenThrow(new AiServiceException("AI 未生成可用草稿"));
        AiThemeDraftRequestState first = service.submit(req(), MEMBER);
        assertEquals(AiThemeDraftRequestState.FAILED, first.getState());
        assertEquals("AI 未生成可用草稿", first.getMessage());
        assertEquals(AiThemeDraftRequestState.FAILED, service.read(MEMBER, RID).getState());
        assertEquals(AiThemeDraftRequestState.FAILED, service.submit(req(), MEMBER).getState());
        verify(creator, times(1)).themeDraft(any(), eq(MEMBER));
    }
}
