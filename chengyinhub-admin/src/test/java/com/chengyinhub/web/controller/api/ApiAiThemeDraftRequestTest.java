package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.ai.AiThemeDraftReq;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftPrepareReq;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftExecuteReq;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftIntentState;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftIntentView;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftRecoveryItem;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftResp;
import com.chengyinhub.business.service.IAiCreatorService;
import com.chengyinhub.business.service.impl.AiThemeDraftRequestService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Handler projection only; durable claims and provider classifications require the service/storage suites. */
class ApiAiThemeDraftRequestTest {
    private static final Long MEMBER = 2001L;
    private static final String RID = "td2_0123456789abcdef0123456789abcdef";
    private static final String PREPARE_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    private IAiCreatorService creator;
    private AiThemeDraftRequestService requests;
    private ApiAiController controller;

    @BeforeEach void setUp() {
        creator = mock(IAiCreatorService.class);
        requests = mock(AiThemeDraftRequestService.class);
        controller = controller(MEMBER);
    }
    private ApiAiController controller(Long actor) {
        ApiAiController value = new ApiAiController() {
            @Override public Long getAppUserId() { return actor; }
        };
        ReflectionTestUtils.setField(value, "aiCreatorService", creator);
        ReflectionTestUtils.setField(value, "themeDraftRequests", requests);
        return value;
    }
    private AiThemeDraftIntentView view(AiThemeDraftIntentState state, String reason, AiThemeDraftResp result) {
        return new AiThemeDraftIntentView(RID, state, true, true, result != null, result, reason,
                Instant.parse("2026-10-24T16:00:00Z"), Instant.parse("2026-10-25T00:00:00Z"), 1);
    }
    private AiThemeDraftPrepareReq prepare() { return new AiThemeDraftPrepareReq(PREPARE_ID, "咖啡", 1, null); }

    @Test void oldSubmissionWithOrWithoutIdCannotReachAnyExecutionPath() {
        AiThemeDraftReq old = new AiThemeDraftReq(); old.setRequestId(PREPARE_ID); old.setIdea("咖啡");
        for (AiThemeDraftReq input : new AiThemeDraftReq[]{old, new AiThemeDraftReq(), null}) {
            AjaxResult result = controller.themeDraft(input);
            assertEquals(400, result.get(AjaxResult.CODE_TAG));
            assertEquals("AI_DRAFT_PREPARE_REQUIRED", result.get("errorCode"));
        }
        verifyNoInteractions(requests, creator);
    }
    @Test void preparationProjectsOriginalIdentityEvenWhenItIsAnUnknownReplay() {
        AiThemeDraftIntentView original = view(AiThemeDraftIntentState.UNKNOWN, "AI_DRAFT_OUTCOME_UNKNOWN", null);
        AiThemeDraftPrepareReq input = prepare();
        when(requests.prepare(MEMBER, input)).thenReturn(original);
        AjaxResult result = controller.themeDraftPrepare(input);
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertSame(original, result.get(AjaxResult.DATA_TAG));
        verify(requests, never()).execute(anyLong(), any());
        verifyNoInteractions(creator);
    }
    @Test void successKeepsViewAndReplayedBodyInsteadOfUsingTheOldResponseShape() {
        AiThemeDraftIntentView original = view(AiThemeDraftIntentState.SUCCEEDED, null, new AiThemeDraftResp());
        AiThemeDraftExecuteReq input = new AiThemeDraftExecuteReq(RID);
        when(requests.execute(MEMBER, input)).thenReturn(original);
        AjaxResult result = controller.themeDraftExecute(input);
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertSame(original, result.get(AjaxResult.DATA_TAG));
        assertTrue(original.replayed());
        verifyNoInteractions(creator);
    }
    @Test void nonTerminalAndKnownFailedFactsKeepTheirOriginalMachineCodesAndIdentity() {
        AiThemeDraftExecuteReq input = new AiThemeDraftExecuteReq(RID);
        for (var entry : Map.of(AiThemeDraftIntentState.PROCESSING, "AI_DRAFT_IN_PROGRESS",
                AiThemeDraftIntentState.UNKNOWN, "AI_DRAFT_OUTCOME_UNKNOWN",
                AiThemeDraftIntentState.FAILED, "AI_DRAFT_FAILED").entrySet()) {
            AiThemeDraftIntentView original = view(entry.getKey(), entry.getValue(), null);
            when(requests.execute(MEMBER, input)).thenReturn(original);
            AjaxResult result = controller.themeDraftExecute(input);
            assertEquals(entry.getValue(), result.get("errorCode"));
            assertSame(original, result.get(AjaxResult.DATA_TAG));
            assertEquals(entry.getKey() == AiThemeDraftIntentState.FAILED ? 500 : 409, result.get(AjaxResult.CODE_TAG));
        }
        verifyNoInteractions(creator);
    }
    @Test void resultExpiryAndTemporaryBodyLossNeverDowngradeKnownSuccess() {
        AiThemeDraftExecuteReq input = new AiThemeDraftExecuteReq(RID);
        for (String reason : List.of("AI_DRAFT_RESULT_EXPIRED", "AI_DRAFT_RESULT_UNAVAILABLE")) {
            AiThemeDraftIntentView original = view(AiThemeDraftIntentState.SUCCEEDED, reason, null);
            when(requests.execute(MEMBER, input)).thenReturn(original);
            AjaxResult result = controller.themeDraftExecute(input);
            assertEquals(reason, result.get("errorCode"));
            assertEquals(reason.endsWith("EXPIRED") ? 409 : 503, result.get(AjaxResult.CODE_TAG));
            assertSame(original, result.get(AjaxResult.DATA_TAG));
            assertEquals(AiThemeDraftIntentState.SUCCEEDED, original.state());
        }
    }
    @Test void preciseRefusalsAndStateFailuresDoNotReachAnAlternativeModelPath() {
        AiThemeDraftPrepareReq input = prepare();
        when(requests.prepare(MEMBER, input)).thenThrow(new AiThemeDraftRequestService.IntentRejectedException(
                409, "AI_DRAFT_REQUEST_CONFLICT", null));
        assertEquals("AI_DRAFT_REQUEST_CONFLICT", controller.themeDraftPrepare(input).get("errorCode"));
        doThrow(mock(AiThemeDraftRequestService.StateUnavailableException.class)).when(requests).readIntent(MEMBER, RID);
        AjaxResult result = controller.themeDraftRequest(RID, null);
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("AI_DRAFT_STATE_UNAVAILABLE", result.get("errorCode"));
        assertNull(result.get(AjaxResult.DATA_TAG));
        verifyNoInteractions(creator);
    }
    @Test void readUsesOnlyAuthenticatedMemberAndExactlyOneIdentityParameter() {
        AiThemeDraftIntentView original = view(AiThemeDraftIntentState.UNKNOWN, "AI_DRAFT_OUTCOME_UNKNOWN", null);
        when(requests.readIntent(MEMBER, RID)).thenReturn(original);
        when(requests.readPrepared(MEMBER, PREPARE_ID)).thenReturn(original);
        assertSame(original, controller.themeDraftRequest(RID, null).get(AjaxResult.DATA_TAG));
        assertSame(original, controller.themeDraftRequest(null, PREPARE_ID).get(AjaxResult.DATA_TAG));
        for (String[] input : new String[][]{{null, null}, {RID, PREPARE_ID}, {"", null}, {null, ""}})
            assertEquals("AI_DRAFT_INVALID_REQUEST", controller.themeDraftRequest(input[0], input[1]).get("errorCode"));
        verify(requests, never()).readIntent(eq(MEMBER + 1), anyString());
        verify(requests, never()).execute(anyLong(), any());
        verifyNoInteractions(creator);
    }
    @Test void readLegacyUnknownDoesNotUseCurrentRoleToInventOrExecuteANewIntent() {
        AiThemeDraftIntentView legacy = new AiThemeDraftIntentView(PREPARE_ID, AiThemeDraftIntentState.UNKNOWN,
                true, false, false, null, "LEGACY_UNBOUND", null, null, null);
        when(requests.readIntent(MEMBER, PREPARE_ID)).thenReturn(legacy);
        when(creator.resolveRoleFor(MEMBER)).thenThrow(new IllegalStateException("role service down"));
        assertSame(legacy, controller.themeDraftRequest(PREPARE_ID, null).get(AjaxResult.DATA_TAG));
        verifyNoInteractions(creator);
        verify(requests, never()).prepare(anyLong(), any());
        verify(requests, never()).execute(anyLong(), any());
    }
    @Test void recoveryIsBoundedAndExposesOnlyTheReturnedOwnersCursor() {
        List<AiThemeDraftRecoveryItem> pending = new ArrayList<>();
        for (long i = 21; i >= 1; i--) pending.add(new AiThemeDraftRecoveryItem(i, PREPARE_ID, RID,
                AiThemeDraftIntentState.UNKNOWN, "AI_DRAFT_OUTCOME_UNKNOWN", Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, 1));
        when(requests.readRecovery(MEMBER, null)).thenReturn(pending);
        AjaxResult result = controller.themeDraftRecovery(null);
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<?, ?> data = (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(20, ((List<?>) data.get("items")).size());
        assertEquals("2", data.get("nextCursor"));
        verify(requests).readRecovery(MEMBER, null);
        verifyNoInteractions(creator);
    }
    @Test void recoverySerializesBigintCursorsWithoutJavascriptPrecisionLoss() {
        when(requests.readRecovery(MEMBER, Long.MAX_VALUE)).thenReturn(List.of(new AiThemeDraftRecoveryItem(
                Long.MAX_VALUE - 1, PREPARE_ID, RID, AiThemeDraftIntentState.SUCCEEDED, null,
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, 1)));
        String json = tools.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsString(controller.themeDraftRecovery(Long.MAX_VALUE));
        assertTrue(json.contains("\"cursor\":\"9223372036854775806\""), json);
        verify(requests).readRecovery(MEMBER, Long.MAX_VALUE);
        verifyNoInteractions(creator);
    }
    @Test void recoveryProjectsOriginalProductTypeAndPreservesUnknownModeWithoutCurrentRoleLookup() {
        when(requests.readRecovery(MEMBER, null)).thenReturn(List.of(
                new AiThemeDraftRecoveryItem(2L, PREPARE_ID, RID, AiThemeDraftIntentState.SUCCEEDED, null,
                        Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, 2),
                new AiThemeDraftRecoveryItem(1L, null, PREPARE_ID, AiThemeDraftIntentState.UNKNOWN, "LEGACY_UNBOUND",
                        Instant.EPOCH, null, null, null)));
        String json = tools.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsString(controller.themeDraftRecovery(null));
        assertTrue(json.contains("\"productType\":2"), json);
        assertTrue(json.contains("\"productType\":null"), json);
        verifyNoInteractions(creator);
        verify(requests, never()).execute(anyLong(), any());
    }
    @Test void missingAuthenticationRejectsEveryRouteBeforeStateOrModelAccess() {
        ApiAiController anonymous = controller(null);
        for (AjaxResult result : List.of(anonymous.themeDraft(new AiThemeDraftReq()), anonymous.themeDraftPrepare(prepare()),
                anonymous.themeDraftExecute(new AiThemeDraftExecuteReq(RID)), anonymous.themeDraftRequest(RID, null),
                anonymous.themeDraftRecovery(null))) assertEquals("请先登录", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(requests, creator);
    }
}
