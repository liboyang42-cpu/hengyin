package com.chengyinhub.web.controller.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.dto.ai.AiThemeDraftReq;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftRequestState;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftResp;
import com.chengyinhub.business.service.IAiCreatorService;
import com.chengyinhub.business.service.impl.AiThemeDraftRequestService;
import com.chengyinhub.common.core.domain.AjaxResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** #22 控制器:带 requestId 走幂等服务并给机器错误码;不带的旧客户端行为不变。 */
class ApiAiThemeDraftRequestTest {
    private static final Long MEMBER = 2001L;
    private static final String RID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    private IAiCreatorService creator;
    private AiThemeDraftRequestService requests;
    private ApiAiController controller;

    @BeforeEach
    void setUp() {
        creator = mock(IAiCreatorService.class);
        when(creator.resolveRoleFor(MEMBER)).thenReturn("player");
        requests = mock(AiThemeDraftRequestService.class);
        controller = new ApiAiController() {
            @Override public Long getAppUserId() { return MEMBER; }
        };
        ReflectionTestUtils.setField(controller, "aiCreatorService", creator);
        ReflectionTestUtils.setField(controller, "themeDraftRequests", requests);
    }

    private static AiThemeDraftReq req(String rid) {
        AiThemeDraftReq req = new AiThemeDraftReq();
        req.setIdea("咖啡");
        req.setRequestId(rid);
        return req;
    }

    private static AiThemeDraftRequestState state(String s) {
        AiThemeDraftRequestState st = new AiThemeDraftRequestState();
        st.setRequestId(RID);
        st.setState(s);
        return st;
    }

    @Test
    void legacyClientWithoutRequestIdKeepsDirectPath() {
        when(creator.themeDraft(any(), eq(MEMBER))).thenReturn(new AiThemeDraftResp());
        assertEquals(200, controller.themeDraft(req(null)).get(AjaxResult.CODE_TAG));
        verify(requests, never()).submit(any(), anyLong());
    }

    @Test
    void succeededReplayKeepsDraftShapeAndMarksReplay() {
        AiThemeDraftRequestState ok = state(AiThemeDraftRequestState.SUCCEEDED);
        AiThemeDraftResp resp = new AiThemeDraftResp();
        ok.setResult(resp);
        ok.setReplayed(true);
        when(requests.submit(any(), eq(MEMBER))).thenReturn(ok);
        AjaxResult result = controller.themeDraft(req(RID));
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(resp, result.get(AjaxResult.DATA_TAG));
        assertEquals(Boolean.TRUE, result.get("replayed"));
        verify(creator, never()).themeDraft(any(), anyLong());
    }

    @Test
    void nonTerminalStatesCarryMachineCodes() {
        when(requests.submit(any(), eq(MEMBER))).thenReturn(state(AiThemeDraftRequestState.PROCESSING));
        assertEquals("AI_DRAFT_IN_PROGRESS", controller.themeDraft(req(RID)).get("errorCode"));
        when(requests.submit(any(), eq(MEMBER))).thenReturn(state(AiThemeDraftRequestState.UNKNOWN));
        AjaxResult unknown = controller.themeDraft(req(RID));
        assertEquals("AI_DRAFT_OUTCOME_UNKNOWN", unknown.get("errorCode"));
        assertEquals(409, unknown.get(AjaxResult.CODE_TAG));
        AiThemeDraftRequestState failed = state(AiThemeDraftRequestState.FAILED);
        failed.setMessage("今日AI次数已用完");
        when(requests.submit(any(), eq(MEMBER))).thenReturn(failed);
        AjaxResult f = controller.themeDraft(req(RID));
        assertEquals("AI_DRAFT_FAILED", f.get("errorCode"));
        assertEquals("今日AI次数已用完", f.get(AjaxResult.MSG_TAG));
    }

    @Test
    void invalidRequestIdAndUnavailableStoreNeverReachModel() {
        assertEquals("AI_DRAFT_INVALID_REQUEST", controller.themeDraft(req("nope")).get("errorCode"));
        when(requests.submit(any(), eq(MEMBER))).thenThrow(mock(AiThemeDraftRequestService.StateUnavailableException.class));
        assertEquals("AI_DRAFT_STATE_UNAVAILABLE", controller.themeDraft(req(RID)).get("errorCode"));
        verify(creator, never()).themeDraft(any(), anyLong());
    }

    @Test
    void readbackIsLoginScopedAndValidated() {
        when(requests.read(MEMBER, RID)).thenReturn(state(AiThemeDraftRequestState.NOT_FOUND));
        AjaxResult result = controller.themeDraftRequest(RID);
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(requests).read(MEMBER, RID);
        assertEquals("AI_DRAFT_INVALID_REQUEST", controller.themeDraftRequest("x").get("errorCode"));
        verify(requests, never()).read(anyLong(), eq("x"));
        verify(requests, never()).read(eq(MEMBER + 1), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void actualRequestServiceKeepsUnknownMachineCodeAndOriginalRidAcrossSubmitReplayAndReadback() {
        java.util.Map<Object, Object> store = new java.util.HashMap<>();
        org.springframework.data.redis.core.RedisTemplate<Object, Object> redis = mock(org.springframework.data.redis.core.RedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<Object, Object> values = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(any(), any(), anyLong(), eq(java.util.concurrent.TimeUnit.HOURS)))
                .thenAnswer(call -> store.putIfAbsent(call.getArgument(0), call.getArgument(1)) == null);
        when(values.get(any())).thenAnswer(call -> store.get(call.getArgument(0)));
        org.mockito.Mockito.doAnswer(call -> { store.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(any(), any(), anyLong(), eq(java.util.concurrent.TimeUnit.HOURS));
        AiThemeDraftRequestService actual = new AiThemeDraftRequestService();
        ReflectionTestUtils.setField(actual, "aiCreatorService", creator);
        ReflectionTestUtils.setField(actual, "redisTemplate", redis);
        ReflectionTestUtils.setField(controller, "themeDraftRequests", actual);
        when(creator.themeDraft(any(), eq(MEMBER))).thenThrow(
                new com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException("provider outcome unknown", null));

        for (int attempt = 0; attempt < 2; attempt++) {
            AjaxResult result = controller.themeDraft(req(RID));
            assertEquals(409, result.get(AjaxResult.CODE_TAG));
            assertEquals("AI_DRAFT_OUTCOME_UNKNOWN", result.get("errorCode"));
            assertEquals(RID, result.get("requestId"));
            assertEquals(AiThemeDraftRequestState.UNKNOWN, result.get("requestState"));
            org.junit.jupiter.api.Assertions.assertNull(result.get(AjaxResult.DATA_TAG));
        }
        AjaxResult read = controller.themeDraftRequest(RID);
        assertEquals(200, read.get(AjaxResult.CODE_TAG));
        AiThemeDraftRequestState original = (AiThemeDraftRequestState) read.get(AjaxResult.DATA_TAG);
        assertEquals(RID, original.getRequestId()); assertEquals(AiThemeDraftRequestState.UNKNOWN, original.getState());
        verify(creator, org.mockito.Mockito.times(1)).themeDraft(any(), eq(MEMBER));
        org.mockito.Mockito.verifyNoInteractions(requests);
    }
}
