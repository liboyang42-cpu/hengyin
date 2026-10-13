package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.dto.GameActorContext;
import com.chengyinhub.business.domain.dto.GameCommandReceiptView;
import com.chengyinhub.business.domain.dto.GameSessionCommand;
import com.chengyinhub.business.domain.dto.GameSessionView;
import com.chengyinhub.business.service.GameSessionException;
import com.chengyinhub.business.service.IGameSessionRuntimeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiGameSessionControllerTest {
    private static final long OWNER = 66L;
    private static final long MERCHANT_ROW = 12L;

    private ApiGameSessionController controller;
    private IGameSessionRuntimeService service;
    private IMerchantAccessService merchantAccessService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiGameSessionController());
        service = mock(IGameSessionRuntimeService.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        ReflectionTestUtils.setField(controller, "runtimeService", service);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
        doReturn(77L).when(controller).getAppUserId();
        when(merchantAccessService.resolve(77L)).thenReturn(
                new MerchantAccessContext(77L, MERCHANT_ROW, OWNER, MerchantRoleCode.MERCHANT_OWNER));
    }

    @Test
    void merchantPerspectiveCarriesResolvedOperatorOwnerScopeAndRole() {
        when(merchantAccessService.resolve(77L)).thenReturn(
                new MerchantAccessContext(77L, MERCHANT_ROW, OWNER, MerchantRoleCode.MERCHANT_CHECKIN));
        GameSessionCommand command = new GameSessionCommand();
        command.setActivityId(10L);
        command.setNodeId(11L);
        command.setRequestId("req-staff");
        command.setExpectedRevision(2);
        command.setAction("STATION_ACCEPT");
        when(service.command(any(), any())).thenReturn(receipt(10L, "req-staff"));

        AjaxResult result = controller.command(command);

        assertEquals(200, result.get("code"));
        ArgumentCaptor<GameActorContext> actor = ArgumentCaptor.forClass(GameActorContext.class);
        verify(service).command(actor.capture(), any());
        assertEquals(77L, actor.getValue().getMemberId());
        assertEquals(OWNER, actor.getValue().getMerchantOwnerMemberId(),
                "站点归属必须按商家主体判断,不是登录员工本人");
        assertEquals(MerchantRoleCode.MERCHANT_CHECKIN, actor.getValue().getMerchantRoleCode());
    }

    @Test
    void merchantPerspectiveRejectsPrincipalWithoutStationPermissionBeforeService() {
        when(merchantAccessService.resolve(77L)).thenReturn(
                new MerchantAccessContext(77L, MERCHANT_ROW, OWNER, MerchantRoleCode.MERCHANT_FINANCE));
        GameSessionCommand command = new GameSessionCommand();
        command.setActivityId(10L);
        command.setNodeId(11L);
        command.setRequestId("req-finance");
        command.setExpectedRevision(2);
        command.setAction("STATION_PAUSE");

        AjaxResult result = controller.command(command);

        assertEquals(500, result.get("code"));
        assertEquals("GAME_FORBIDDEN_SCOPE",
                ((Map<?, ?>) result.get("data")).get("reasonCode"));
        verify(service, never()).command(any(), any());
    }

    @Test
    void merchantPerspectiveRejectsNonMemberBeforeService() {
        when(merchantAccessService.resolve(77L)).thenReturn(null);

        AjaxResult result = controller.merchantEntries();

        assertEquals(500, result.get("code"));
        assertEquals("GAME_FORBIDDEN_SCOPE",
                ((Map<?, ?>) result.get("data")).get("reasonCode"));
        verify(service, never()).merchantEntries(any());
    }

    @Test
    void commandDerivesMerchantActorFromActionAndNeverAcceptsActorIdFromBody() {
        GameSessionCommand command = new GameSessionCommand();
        command.setActivityId(10L);
        command.setNodeId(11L);
        command.setRequestId("req-1");
        command.setExpectedRevision(2);
        command.setAction("STATION_ACCEPT");
        when(service.command(any(), any())).thenReturn(receipt(10L, "req-1"));

        AjaxResult result = controller.command(command);

        assertEquals(200, result.get("code"));
        ArgumentCaptor<GameActorContext> actor = ArgumentCaptor.forClass(GameActorContext.class);
        verify(service).command(actor.capture(), any());
        assertEquals(77L, actor.getValue().getMemberId());
        assertEquals("MERCHANT", actor.getValue().getPerspective());
    }

    @Test
    void merchantEntriesDeriveCurrentMerchantWithoutClientSuppliedScope() {
        when(service.merchantEntries(any())).thenReturn(Collections.emptyList());

        AjaxResult result = controller.merchantEntries();

        assertEquals(200, result.get("code"));
        ArgumentCaptor<GameActorContext> actor = ArgumentCaptor.forClass(GameActorContext.class);
        verify(service).merchantEntries(actor.capture());
        assertEquals(77L, actor.getValue().getMemberId());
        assertEquals("MERCHANT", actor.getValue().getPerspective());
    }

    @Test
    void recapExportDerivesClubActorAndActivityScopeFromTheRequestOnly() {
        Map<String, Object> exported = Collections.singletonMap("schemaVersion", "GAME_RECAP_EXPORT_V1");
        when(service.exportRecap(any(), any())).thenReturn(exported);

        AjaxResult result = controller.exportRecap("10");

        assertEquals(200, result.get("code"));
        assertEquals(exported, result.get("data"));
        ArgumentCaptor<GameActorContext> actor = ArgumentCaptor.forClass(GameActorContext.class);
        ArgumentCaptor<Long> activityId = ArgumentCaptor.forClass(Long.class);
        verify(service).exportRecap(actor.capture(), activityId.capture());
        assertEquals(77L, actor.getValue().getMemberId());
        assertEquals("CLUB", actor.getValue().getPerspective());
        assertEquals(10L, activityId.getValue());
    }

    @Test
    void gameErrorReturnsStableReasonCodeInData() {
        GameSessionCommand command = new GameSessionCommand();
        command.setActivityId(10L);
        command.setRequestId("req-2");
        command.setExpectedRevision(2);
        command.setAction("START");
        when(service.command(any(), any())).thenThrow(
                new GameSessionException("GAME_STATE_CONFLICT", "游戏状态已变化"));

        AjaxResult result = controller.command(command);

        assertEquals(500, result.get("code"));
        assertEquals("GAME_STATE_CONFLICT", ((Map<?, ?>) result.get("data")).get("reasonCode"));
        verify(service).recordFailure(any(GameActorContext.class), any(GameSessionCommand.class),
                any(GameSessionException.class));
    }

    @Test
    void receiptPendingEchoesExactActivityAndRequest() {
        GameCommandReceiptView pending = receipt(10L, "req-pending");
        pending.setOutcome("PENDING");
        pending.setReceiptId(null);
        when(service.receipt(any(), any(), any())).thenReturn(pending);

        AjaxResult result = controller.receipt("10", "req-pending");

        GameCommandReceiptView data = (GameCommandReceiptView) result.get("data");
        assertEquals(10L, data.getActivityId());
        assertEquals("req-pending", data.getRequestId());
        assertEquals("PENDING", data.getOutcome());
        assertFalse(data.isReplayed());
        ArgumentCaptor<GameActorContext> actor = ArgumentCaptor.forClass(GameActorContext.class);
        verify(service).receipt(actor.capture(), any(), any());
        assertEquals(77L, actor.getValue().getMemberId());
        assertEquals(null, actor.getValue().getPerspective());
    }

    @Test
    void unexpectedRuntimeFailureNeverEchoesInternalMessage() {
        when(service.view(any(), any())).thenThrow(new RuntimeException("jdbc password leaked"));

        AjaxResult result = controller.view("10", "PLAYER");

        assertEquals(500, result.get("code"));
        assertEquals("游戏服务暂时不可用，请稍后重试", result.get("msg"));
        assertFalse(String.valueOf(result).contains("jdbc password leaked"));
    }

    private GameCommandReceiptView receipt(Long activityId, String requestId) {
        GameCommandReceiptView receipt = new GameCommandReceiptView();
        receipt.setActivityId(activityId);
        receipt.setRequestId(requestId);
        receipt.setOutcome("APPLIED");
        return receipt;
    }
}
