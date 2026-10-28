package com.chengyinhub.business.service.impl;

import java.util.Collections;
import java.util.Date;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.chengyinhub.business.domain.NpcChatRequest;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.domain.dto.ai.NpcChatReq;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.mapper.NpcChatLogMapper;
import com.chengyinhub.business.mapper.NpcChatRequestMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.ai.AiGatewayResult;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.VoiceCloneClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 3 Chat 服务契约：所有模型调用必须先得到可重放 requestId 状态。
 *
 * 其中重放、同 requestId 跨用户、同会话并发是明确的负控；只测成功回答不足以证明
 * requestId 没有被做成展示字段。
 */
class NpcChatServiceTest {

    private static final Long USER_ONE = 2002L;
    private static final Long USER_TWO = 2003L;
    private static final Long SESSION_ID = 99L;

    private com.chengyinhub.business.mapper.MmsMerchantMapper audioMerchants;
    private com.chengyinhub.business.service.ai.impl.OssCardCutoutStorage generatedAudio;
    private AppMediaAssetService audioAssets;
    private NpcChatService service;
    private AiGatewayService aiGatewayService;
    private VoiceCloneClient voiceCloneClient;
    private NpcProfileMapper npcProfileMapper;
    private NpcMemoryBuilder memoryBuilder;
    private MerchantNpcFactsBuilder merchantNpcFactsBuilder;
    private NpcChatLogMapper npcChatLogMapper;
    private NpcChatRequestMapper npcChatRequestMapper;
    private RoamMapper roamMapper;
    private ScheduledExecutorService scheduledExecutorService;
    private ScheduledFuture<?> leaseHeartbeat;
    private com.chengyinhub.business.mapper.NpcAsrIntentMapper asrIntents;

    @BeforeEach
    void setUp() {
        aiGatewayService = mock(AiGatewayService.class);
        voiceCloneClient = mock(VoiceCloneClient.class);
        npcProfileMapper = mock(NpcProfileMapper.class);
        memoryBuilder = mock(NpcMemoryBuilder.class);
        merchantNpcFactsBuilder = mock(MerchantNpcFactsBuilder.class);
        npcChatLogMapper = mock(NpcChatLogMapper.class);
        npcChatRequestMapper = mock(NpcChatRequestMapper.class);
        roamMapper = mock(RoamMapper.class);
        scheduledExecutorService = mock(ScheduledExecutorService.class);
        leaseHeartbeat = mock(ScheduledFuture.class);

        when(memoryBuilder.buildRoam(any(RoamSession.class))).thenReturn("[漫游进度] 本次已探索 25 个唯一格子。");
        when(roamMapper.selectSessionById(SESSION_ID)).thenReturn(activeSession(USER_ONE));
        // Unit orchestration uses real write-service code over mapper doubles; MySQL IT proves locking.
        java.util.Map<String, NpcChatRequest> rows = new java.util.HashMap<>();
        when(npcChatRequestMapper.lockChatUser(anyLong())).thenAnswer(inv -> inv.getArgument(0));
        when(npcChatRequestMapper.insertNpcChatRequest(any(NpcChatRequest.class))).thenAnswer(inv -> {
            NpcChatRequest row = inv.getArgument(0);
            rows.put(row.getUserId() + ":" + row.getRequestId(), copyRequest(row));
            return 1;
        });
        when(npcChatRequestMapper.selectByUserAndRequestId(anyLong(), anyString())).thenAnswer(inv -> {
            NpcChatRequest row = rows.get(inv.getArgument(0) + ":" + inv.getArgument(1));
            return row == null ? null : copyRequest(row);
        });
        when(npcChatRequestMapper.finishIfLeaseOwned(any(NpcChatRequest.class))).thenAnswer(inv -> {
            NpcChatRequest row = inv.getArgument(0);
            rows.put(row.getUserId() + ":" + row.getRequestId(), copyRequest(row));
            return 1;
        });
        when(npcChatLogMapper.insertNpcChatLog(any())).thenReturn(1);
        org.springframework.transaction.PlatformTransactionManager transactions =
                mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(inv -> new org.springframework.transaction.support.SimpleTransactionStatus());
        NpcChatWriteService writes = new NpcChatWriteService(npcChatRequestMapper, npcChatLogMapper, transactions);
        asrIntents=mock(com.chengyinhub.business.mapper.NpcAsrIntentMapper.class);
        ReflectionTestUtils.setField(writes,"asrIntents",asrIntents);
        when(npcChatRequestMapper.renewLease(anyLong(), anyString(), anyString(), any(Date.class))).thenReturn(1);
        doReturn(leaseHeartbeat).when(scheduledExecutorService).scheduleAtFixedRate(any(Runnable.class),
                eq(5000L), eq(5000L), eq(TimeUnit.MILLISECONDS));
        NpcProfile globalProfile = new NpcProfile();
        globalProfile.setId(1L);
        globalProfile.setPersona("你是漫游向导。");
        when(npcProfileMapper.selectGlobalNpc()).thenReturn(globalProfile);

        audioMerchants=mock(com.chengyinhub.business.mapper.MmsMerchantMapper.class);
        generatedAudio=mock(com.chengyinhub.business.service.ai.impl.OssCardCutoutStorage.class);
        audioAssets=mock(AppMediaAssetService.class);
        service = new NpcChatService();
        ReflectionTestUtils.setField(service,"audioMerchantMapper",audioMerchants);
        ReflectionTestUtils.setField(service,"generatedAudioStorage",generatedAudio);
        ReflectionTestUtils.setField(service,"audioAssets",audioAssets);
        ReflectionTestUtils.setField(service, "aiGatewayService", aiGatewayService);
        ReflectionTestUtils.setField(service, "voiceCloneClient", voiceCloneClient);
        ReflectionTestUtils.setField(service, "npcProfileMapper", npcProfileMapper);
        ReflectionTestUtils.setField(service, "memoryBuilder", memoryBuilder);
        ReflectionTestUtils.setField(service, "merchantFactsBuilder", merchantNpcFactsBuilder);
        ReflectionTestUtils.setField(service, "npcChatWriteService", writes);
        ReflectionTestUtils.setField(service, "npcChatRequestMapper", npcChatRequestMapper);
        ReflectionTestUtils.setField(service, "roamMapper", roamMapper);
        ReflectionTestUtils.setField(service, "scheduledExecutorService", scheduledExecutorService);
    }

    private static NpcChatRequest copyRequest(NpcChatRequest request) {
        NpcChatRequest copy = new NpcChatRequest();
        org.springframework.beans.BeanUtils.copyProperties(request, copy);
        return copy;
    }

    @Test
    void chatGoesThroughGatewayWithServerDerivedRoamContext() {
        when(aiGatewayService.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace-1", "往左边看看。", "deepseek", 0));

        NpcChatResp resp = service.chat(req("req-100", "这附近有啥好玩的"), USER_ONE);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(aiGatewayService).generate(eq(USER_ONE), eq("player"), eq("npc_chat"),
                system.capture(), eq("这附近有啥好玩的"));
        assertTrue(system.getValue().contains("漫游进度"));
        assertEquals("SUCCEEDED", resp.getOutcomeStatus());
        assertEquals("PASS", resp.getSafetyDecision());
        assertEquals(requestId("req-100"), resp.getRequestId());
        assertEquals("往左边看看。", resp.getSafeText());
    }

    @Test
    void terminalReplayReturnsSameResultWithoutCallingGatewayAgain() {
        NpcChatRequest terminal = terminal("req-replay", USER_ONE, "SUCCEEDED", "PASS", null, false, "已完成。");
        when(npcChatRequestMapper.selectByUserAndRequestId(USER_ONE, requestId("req-replay"))).thenReturn(terminal);

        NpcChatResp resp = service.chat(req("req-replay", "无论这次带什么消息"), USER_ONE);

        assertEquals("SUCCEEDED", resp.getOutcomeStatus());
        assertEquals("已完成。", resp.getSafeText());
        verify(aiGatewayService, never()).generate(anyLong(), anyString(), anyString(), anyString(), anyString());
        verify(npcChatRequestMapper, never()).insertNpcChatRequest(any(NpcChatRequest.class));
    }

    /** 负控：同一个 requestId 不能跨 userId 重放终态。 */
    @Test
    void sameRequestIdForDifferentUsersStartsIndependentRequests() {
        when(roamMapper.selectSessionById(SESSION_ID)).thenReturn(activeSession(USER_ONE), activeSession(USER_TWO));
        when(aiGatewayService.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace", "好的。", "deepseek", 0));

        service.chat(req("same-request", "第一位用户"), USER_ONE);
        service.chat(req("same-request", "第二位用户"), USER_TWO);

        ArgumentCaptor<NpcChatRequest> inserted = ArgumentCaptor.forClass(NpcChatRequest.class);
        verify(npcChatRequestMapper, times(2)).insertNpcChatRequest(inserted.capture());
        assertEquals(USER_ONE, inserted.getAllValues().get(0).getUserId());
        assertEquals(USER_TWO, inserted.getAllValues().get(1).getUserId());
        assertEquals(requestId("same-request"), inserted.getAllValues().get(0).getRequestId());
        assertEquals(requestId("same-request"), inserted.getAllValues().get(1).getRequestId());
        verify(aiGatewayService, times(2)).generate(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    /** 负控：同一用户同一会话已有另一条 PROCESSING 请求时，不能二次调模型。 */
    @Test
    void activeRequestForSameSessionReturnsInProgressWithoutCallingGateway() {
        when(npcChatRequestMapper.insertNpcChatRequest(any(NpcChatRequest.class)))
                .thenThrow(new DuplicateKeyException("uk_npc_chat_request_active_session"));

        NpcChatResp resp = service.chat(req("new-request", "再问一次"), USER_ONE);

        assertEquals("PROCESSING", resp.getOutcomeStatus());
        assertEquals("REQUEST_IN_PROGRESS", resp.getErrorCode());
        assertTrue(resp.isRetryable());
        verify(aiGatewayService, never()).generate(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void gatewayRateLimitBecomesReplayableSafeTerminal() {
        when(aiGatewayService.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new AiServiceException(AiServiceException.Reason.RATE_LIMITED, "rate"));

        NpcChatResp resp = service.chat(req("rate-1", "hi"), USER_ONE);

        assertEquals("REJECTED", resp.getOutcomeStatus());
        assertEquals("RATE_LIMITED", resp.getErrorCode());
        assertEquals("DAY", resp.getLimitType());
        assertNotNull(resp.getRetryAfterSeconds());
        assertTrue(resp.isRetryable());

        ArgumentCaptor<NpcChatRequest> terminal = ArgumentCaptor.forClass(NpcChatRequest.class);
        verify(npcChatRequestMapper).finishIfLeaseOwned(terminal.capture());
        assertEquals("RATE_LIMITED", terminal.getValue().getErrorCode());
        assertEquals("DAY", terminal.getValue().getLimitType());
    }

    @Test
    void rejectsSessionOwnedByAnotherUserBeforeGateway() {
        when(roamMapper.selectSessionById(SESSION_ID)).thenReturn(activeSession(USER_TWO));

        NpcChatResp resp = service.chat(req("wrong-owner", "hi"), USER_ONE);

        assertEquals("REJECTED", resp.getOutcomeStatus());
        assertEquals("INVALID_SESSION", resp.getErrorCode());
        verify(aiGatewayService, never()).generate(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void ignoresClientProfileAndUsesGlobalProfileOnly() {
        NpcProfile profile = new NpcProfile();
        profile.setId(7L);
        profile.setPersona("你是小瘾。");
        when(npcProfileMapper.selectGlobalNpc()).thenReturn(profile);
        when(aiGatewayService.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace", "好啊。", "deepseek", 0));

        service.chat(req("profile", "hi"), USER_ONE);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(aiGatewayService).generate(anyLong(), anyString(), anyString(), system.capture(), anyString());
        assertTrue(system.getValue().startsWith("你是小瘾。"));
    }

    @Test
    void merchantChatUsesApprovedMerchantProfileAndSeparateSessionNamespace() {
        NpcProfile profile = new NpcProfile();
        profile.setId(8L);
        profile.setPersona("你是店主。 ");
        when(npcProfileMapper.selectEnabledByScope(2, 42L)).thenReturn(Collections.singletonList(profile));
        when(merchantNpcFactsBuilder.build(profile, 42L)).thenReturn("【本店事实】\n店名:老周咖啡");
        when(aiGatewayService.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace", "推荐手冲。", "deepseek", 0));

        NpcChatReq request = req("merchant", "有什么推荐");
        request.setBizId(42L);
        NpcChatResp response = service.chatWithMerchant(request, USER_ONE, 42L);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(aiGatewayService).generate(eq(USER_ONE), eq("player"), eq("merchant_npc_chat"),
                system.capture(), eq("有什么推荐"));
        assertEquals("你是店主。 \n【本店事实】\n店名:老周咖啡", system.getValue());
        ArgumentCaptor<NpcChatRequest> inserted = ArgumentCaptor.forClass(NpcChatRequest.class);
        verify(npcChatRequestMapper).insertNpcChatRequest(inserted.capture());
        assertTrue(inserted.getValue().getSessionId() < -42L);
        assertEquals("推荐手冲。", response.getSafeText());
    }

    @Test
    void merchantAudioRemainsPrivateUntilApprovalAndSameRequestReplaysWithoutSynthesis() {
        NpcProfile profile = new NpcProfile();
        profile.setId(8L);
        profile.setPersona("你是店主。");
        profile.setVoiceId("voice-ready");
        profile.setVoiceStatus(2);profile.setScopeType(2);profile.setScopeId(42L);
        when(npcProfileMapper.selectEnabledByScope(2, 42L)).thenReturn(Collections.singletonList(profile));
        when(merchantNpcFactsBuilder.build(profile, 42L)).thenReturn("【本店事实】\n店名:老周咖啡");
        when(aiGatewayService.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace", "推荐手冲。", "deepseek", 0));
        when(voiceCloneClient.synthesize("voice-ready", "推荐手冲。"))
                .thenReturn("https://cdn.example/reply.mp3");

        com.chengyinhub.business.domain.MmsMerchant merchant=new com.chengyinhub.business.domain.MmsMerchant();merchant.setMemberId(99L);
        when(audioMerchants.selectMmsMerchantById(42L)).thenReturn(merchant);
        when(generatedAudio.stageGeneratedAudio(99L,"https://cdn.example/reply.mp3")).thenReturn(java.util.Map.of("assetId",12L,"reviewState","HOLD"));
        when(npcChatRequestMapper.bindApprovedTextAudio(eq(USER_ONE),anyString(),eq(12L))).thenReturn(1);
        when(audioAssets.approvedUrl(12L)).thenThrow(new com.chengyinhub.common.exception.ServiceException("pending"));
        NpcChatReq request = req("merchant-audio", "有什么推荐");
        request.setBizId(42L);
        NpcChatResp response = service.chatWithMerchant(request, USER_ONE, 42L);

        assertEquals("推荐手冲。", response.getSafeText());
        assertNull(response.getAudioUrl());
        verify(generatedAudio).stageGeneratedAudio(99L,"https://cdn.example/reply.mp3");
        NpcChatRequest stored=new NpcChatRequest();stored.setUserId(USER_ONE);stored.setRequestId(request.getRequestId());
        stored.setStatus("SUCCEEDED");stored.setSafetyDecision("PASS");stored.setSafeText("推荐手冲。");stored.setAudioAssetId(12L);stored.setSessionId(-(Long.MAX_VALUE / 2 + 42L)); // A包：原 requestId 只在原商家会话(-合成节点)内回放
        when(npcChatRequestMapper.selectByUserAndRequestId(USER_ONE,request.getRequestId())).thenReturn(stored);
        doReturn("https://owned.example/approved.mp3").when(audioAssets).approvedUrl(12L);
        assertEquals("https://owned.example/approved.mp3",service.chatWithMerchant(request,USER_ONE,42L).getAudioUrl());
        verify(voiceCloneClient,times(1)).synthesize("voice-ready","推荐手冲。");
    }

    @Test
    void merchantChatKeepsSafeTextWhenSynthesisFails() {
        NpcProfile profile = new NpcProfile();
        profile.setId(8L);
        profile.setPersona("你是店主。");
        profile.setVoiceId("voice-ready");
        profile.setVoiceStatus(2);profile.setScopeType(2);profile.setScopeId(42L);
        when(npcProfileMapper.selectEnabledByScope(2, 42L)).thenReturn(Collections.singletonList(profile));
        when(merchantNpcFactsBuilder.build(profile, 42L)).thenReturn("【本店事实】\n店名:老周咖啡");
        when(aiGatewayService.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace", "推荐手冲。", "deepseek", 0));
        when(voiceCloneClient.synthesize("voice-ready", "推荐手冲。"))
                .thenThrow(new IllegalStateException("tts unavailable"));

        com.chengyinhub.business.domain.MmsMerchant merchant=new com.chengyinhub.business.domain.MmsMerchant();merchant.setMemberId(99L);
        when(audioMerchants.selectMmsMerchantById(42L)).thenReturn(merchant);
        NpcChatReq request = req("merchant-audio-fallback", "有什么推荐");
        request.setBizId(42L);
        NpcChatResp response = service.chatWithMerchant(request, USER_ONE, 42L);

        assertEquals("SUCCEEDED", response.getOutcomeStatus());
        assertEquals("推荐手冲。", response.getSafeText());
        assertNull(response.getAudioUrl());
    }

    /** 负控：requestId 不是 UUID 时不能进入状态机，更不能触达模型。 */
    @Test
    void rejectsNonUuidRequestIdBeforeStateOrGateway() {
        NpcChatReq invalid = req("ignored", "hi");
        invalid.setRequestId("not-a-uuid");

        NpcChatResp resp = service.chat(invalid, USER_ONE);

        assertEquals("REJECTED", resp.getOutcomeStatus());
        assertEquals("INVALID_REQUEST", resp.getErrorCode());
        verifyNoInteractions(npcChatRequestMapper, roamMapper, aiGatewayService);
    }

    /** 负控：未找到唯一、已审核、启用的全局 NPC 时 fail-close，不能用默认人设调模型。 */
    @Test
    void rejectsWhenNoUsableGlobalProfileExists() {
        when(npcProfileMapper.selectGlobalNpc()).thenReturn(null);

        NpcChatResp resp = service.chat(req("missing-profile", "hi"), USER_ONE);

        assertEquals("FAILED", resp.getOutcomeStatus());
        assertEquals("NPC_PROFILE_UNAVAILABLE", resp.getErrorCode());
        verify(aiGatewayService, never()).generate(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void renewsProcessingLeaseAndCancelsHeartbeatAfterTerminalState() {
        when(aiGatewayService.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace", "好啊。", "deepseek", 0));

        service.chat(req("lease-heartbeat", "hi"), USER_ONE);

        ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduledExecutorService).scheduleAtFixedRate(heartbeat.capture(), eq(5000L), eq(5000L),
                eq(TimeUnit.MILLISECONDS));
        heartbeat.getValue().run();
        verify(npcChatRequestMapper, times(2)).renewLease(eq(USER_ONE), eq(requestId("lease-heartbeat")), anyString(),
                any(Date.class));
        verify(leaseHeartbeat).cancel(false);
    }

    /** 负控：续租调度器不可用时，不能带着会在 20 秒后失效的 lease 调模型。 */
    @Test
    void rejectsWhenLeaseHeartbeatCannotStart() {
        doThrow(new RejectedExecutionException("scheduler down")).when(scheduledExecutorService)
                .scheduleAtFixedRate(any(Runnable.class), eq(5000L), eq(5000L), eq(TimeUnit.MILLISECONDS));

        NpcChatResp resp = service.chat(req("scheduler-down", "hi"), USER_ONE);

        assertEquals("FAILED", resp.getOutcomeStatus());
        assertEquals("REQUEST_STATE_UNAVAILABLE", resp.getErrorCode());
        verify(aiGatewayService, never()).generate(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    private NpcChatReq req(String requestId, String message) {
        NpcChatReq req = new NpcChatReq();
        req.setRequestId(requestId(requestId));
        req.setBizId(SESSION_ID);
        req.setMessage(message);
        return req;
    }

    private RoamSession activeSession(Long owner) {
        RoamSession session = new RoamSession();
        session.setId(SESSION_ID);
        session.setMemberId(owner);
        session.setStatus(0);
        return session;
    }

    private NpcChatRequest terminal(String requestId, Long userId, String status, String safetyDecision,
                                    String errorCode, boolean retryable, String safeText) {
        NpcChatRequest state = new NpcChatRequest();
        state.setUserId(userId);
        state.setRequestId(requestId(requestId));
        state.setSessionId(SESSION_ID);
        state.setStatus(status);
        state.setSafetyDecision(safetyDecision);
        state.setErrorCode(errorCode);
        state.setRetryable(retryable);
        state.setAi(true);
        state.setSafeText(safeText);
        return state;
    }

    private String requestId(String label) {
        return String.format("00000000-0000-4000-8000-%012d", Math.abs((long) label.hashCode()));
    }

    @Test
    void typedInputBlockIsNonRetryableAndNeverSynthesizesAudio() {
        when(aiGatewayService.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new AiServiceException(AiServiceException.Reason.INPUT_BLOCKED, "blocked"));
        NpcChatResp response = service.chat(req("blocked-1", "hi"), USER_ONE);
        assertEquals("REJECTED", response.getOutcomeStatus());
        assertEquals("INPUT_BLOCK", response.getErrorCode());
        assertEquals(false, response.isRetryable());
        ArgumentCaptor<NpcChatRequest> terminal = ArgumentCaptor.forClass(NpcChatRequest.class);
        verify(npcChatRequestMapper).finishIfLeaseOwned(terminal.capture());
        assertEquals("BLOCKED", terminal.getValue().getSafetyDecision());
        org.mockito.Mockito.verifyNoInteractions(voiceCloneClient);
    }

    @Test
    void providerUnknownIsStoredAndReplayedWithoutCallingModelAgain() {
        when(aiGatewayService.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException(
                        "unknown", new RuntimeException("timeout")));
        NpcChatResp response = service.chat(req("provider-unknown", "hi"), USER_ONE);
        assertEquals("PROCESSING", response.getOutcomeStatus());
        assertEquals("UNKNOWN", response.getSafetyDecision());
        assertEquals("REQUEST_OUTCOME_UNKNOWN", response.getErrorCode());
        assertEquals(false, response.isRetryable());
        ArgumentCaptor<NpcChatRequest> stored = ArgumentCaptor.forClass(NpcChatRequest.class);
        verify(npcChatRequestMapper).finishIfLeaseOwned(stored.capture());
        when(npcChatRequestMapper.selectByUserAndRequestId(USER_ONE, requestId("provider-unknown")))
                .thenReturn(stored.getValue());
        NpcChatResp replay = service.chat(req("provider-unknown", "hi"), USER_ONE);
        assertEquals(response.getRequestId(), replay.getRequestId());
        assertEquals("REQUEST_OUTCOME_UNKNOWN", replay.getErrorCode());
        assertEquals(false, replay.isRetryable());
        verify(aiGatewayService, times(1)).generate(anyLong(), anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(voiceCloneClient);
    }

    @Test
    void unknownReviewDecisionKeepsOriginalRetryableFailure() {
        when(aiGatewayService.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new AiServiceException(AiServiceException.Reason.UNKNOWN, "review"));
        NpcChatResp response = service.chat(req("review-1", "hi"), USER_ONE);
        assertEquals("FAILED", response.getOutcomeStatus());
        assertEquals("AI_UNAVAILABLE", response.getErrorCode());
        assertTrue(response.isRetryable());
        ArgumentCaptor<NpcChatRequest> terminal = ArgumentCaptor.forClass(NpcChatRequest.class);
        verify(npcChatRequestMapper).finishIfLeaseOwned(terminal.capture());
        assertEquals("NOT_RUN", terminal.getValue().getSafetyDecision());
        org.mockito.Mockito.verifyNoInteractions(voiceCloneClient);
    }
    @Test
    void leaseLostBeforeProviderDoesNotStartGateway() {
        when(npcChatRequestMapper.renewLease(anyLong(),anyString(),anyString(),any(Date.class))).thenAnswer(inv -> {
            // Preserve the original unavailable-state case: the persistent row cannot be read.
            when(npcChatRequestMapper.selectByUserAndRequestId(USER_ONE, requestId("lost-before-provider"))).thenReturn(null);
            return 0;
        });
        NpcChatResp result=service.chat(req("lost-before-provider","hi"),USER_ONE);
        assertEquals("REQUEST_STATE_UNAVAILABLE",result.getErrorCode());
        verify(aiGatewayService,never()).generate(anyLong(),anyString(),anyString(),anyString(),anyString());
        verify(npcChatRequestMapper,never()).finishIfLeaseOwned(any(NpcChatRequest.class));
        verify(leaseHeartbeat).cancel(false);
    }

    @Test
    void oldWorkerCannotReturnOrSynthesizeItsUnsavedResult() {
        when(aiGatewayService.generate(anyLong(),anyString(),anyString(),anyString(),anyString()))
                .thenReturn(new AiGatewayResult("trace","旧答案","real",0));
        when(npcChatRequestMapper.finishIfLeaseOwned(any(NpcChatRequest.class))).thenAnswer(inv -> {
            when(npcChatRequestMapper.selectByUserAndRequestId(USER_ONE, requestId("lost-after-provider"))).thenReturn(null);
            return 0;
        });
        NpcChatResp result=service.chat(req("lost-after-provider","hi"),USER_ONE);
        assertEquals("REQUEST_OUTCOME_UNKNOWN",result.getErrorCode());
        assertEquals("UNKNOWN",result.getSafetyDecision());
        assertTrue(!result.isRetryable());
        assertTrue(!"旧答案".equals(result.getSafeText()));
        verify(npcChatLogMapper, never()).insertNpcChatLog(any());
        verifyNoInteractions(voiceCloneClient);
    }

    @Test
    void revokedLeaseReturnsPersistentDeletionTombstoneInsteadOfWorkerAnswer() {
        String id = requestId("deleted-in-flight");
        NpcChatRequest erased = terminal("deleted-in-flight", USER_ONE, "PROCESSING", "UNKNOWN",
                "REQUEST_OUTCOME_UNKNOWN", false, null);
        when(aiGatewayService.generate(anyLong(), anyString(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            when(npcChatRequestMapper.selectByUserAndRequestId(USER_ONE, id)).thenReturn(erased);
            when(npcChatRequestMapper.finishIfLeaseOwned(any(NpcChatRequest.class))).thenReturn(0);
            return new AiGatewayResult("trace", "已删除的旧答案", "fixture", 0);
        });
        NpcChatResp result = service.chat(req("deleted-in-flight", "hi"), USER_ONE);
        assertEquals("REQUEST_OUTCOME_UNKNOWN", result.getErrorCode());
        assertEquals(false, result.isRetryable());
        assertTrue(!"已删除的旧答案".equals(result.getSafeText()));
        verify(npcChatLogMapper, never()).insertNpcChatLog(any());
        service.chat(req("deleted-in-flight", "hi"), USER_ONE);
        verify(aiGatewayService, times(1)).generate(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void storedShopAnswerKeepsOriginalNodeAndDoesNotCallProvider() {
        NpcChatRequest stored=new NpcChatRequest();stored.setUserId(USER_ONE);stored.setSessionId(-21L);
        stored.setRequestId(requestId("stored-shop"));stored.setStatus(NpcChatRequest.STATUS_SUCCEEDED);
        stored.setSafeText("原答案");stored.setSafetyDecision("PASS");
        when(npcChatRequestMapper.selectByUserAndRequestId(USER_ONE,stored.getRequestId())).thenReturn(stored);
        assertEquals("原答案",service.storedShopRequest(stored.getRequestId(),USER_ONE,21L).getSafeText());
        assertEquals("INVALID_REQUEST",service.storedShopRequest(stored.getRequestId(),USER_ONE,22L).getErrorCode());
        verifyNoInteractions(aiGatewayService,voiceCloneClient);
    }
    @Test void expiredLeaseReplayIsUnknownRatherThanClaimingNoProviderRan() {
        NpcChatRequest stored=new NpcChatRequest();stored.setUserId(USER_ONE);stored.setSessionId(-21L);
        stored.setRequestId(requestId("expired-shop"));stored.setStatus(NpcChatRequest.STATUS_PROCESSING);
        stored.setLeaseUntil(new Date(0L));
        when(npcChatRequestMapper.selectByUserAndRequestId(USER_ONE,stored.getRequestId())).thenReturn(stored);
        NpcChatResp result=service.storedShopRequest(stored.getRequestId(),USER_ONE,21L);
        assertEquals("UNKNOWN",result.getSafetyDecision());assertEquals("REQUEST_OUTCOME_UNKNOWN",result.getErrorCode());
        assertTrue(!result.isRetryable());verifyNoInteractions(aiGatewayService);
    }


    private com.chengyinhub.business.domain.NpcAsrIntent originalAsr(NpcChatReq request) {
        NpcProfile profile=new NpcProfile();profile.setId(8L);profile.setPersona("你是店主。");
        profile.setScopeType(2);profile.setScopeId(42L);
        when(npcProfileMapper.selectEnabledByScope(2,42L)).thenReturn(Collections.singletonList(profile));
        var row=new com.chengyinhub.business.domain.NpcAsrIntent();row.setId(1L);row.setUserId(USER_ONE);
        row.setRequestId(request.getRequestId());row.setNodeId(21L);row.setMerchantMemberId(42L);
        row.setState("RECEIVED");row.setHandoffStarted(true);row.setTranscript("原转写");
        row.setQuotaReservation("ai:gen:limit:gen:"+USER_ONE+":20261013|01234567890123456789012345678901");
        when(asrIntents.selectForUpdate(USER_ONE,request.getRequestId())).thenReturn(row);
        when(asrIntents.claimTextAdmission(USER_ONE,request.getRequestId(),-21L)).thenAnswer(call -> {
            if (row.isTextAdmitted()) return 0;row.setTextAdmitted(true);return 1;
        });
        return row;
    }
    @Test void asrTextWinnerTransfersOriginalQuotaAndRepeatedRequestDoesNotChargeAgain() {
        NpcChatReq request=req("asr-shared-quota","原转写");var original=originalAsr(request);
        when(aiGatewayService.generateNpcAsrHandoff(any(com.chengyinhub.business.service.ai.NpcAsrQuotaHandoff.class),anyString(),anyString()))
                .thenReturn(new AiGatewayResult("original-trace","原回答","deepseek",0));
        assertEquals("SUCCEEDED",service.chatWithShop(request.getRequestId(),USER_ONE,21L,42L,"本店事实","原转写").getOutcomeStatus());
        assertEquals("原回答",service.chatWithShop(request.getRequestId(),USER_ONE,21L,42L,"本店事实","原转写").getSafeText());
        var quota=org.mockito.ArgumentCaptor.forClass(com.chengyinhub.business.service.ai.NpcAsrQuotaHandoff.class);
        verify(aiGatewayService).generateNpcAsrHandoff(quota.capture(),anyString(),eq("原转写"));
        assertEquals(original.getQuotaReservation(),quota.getValue().reservation());assertEquals(Long.valueOf(42L),quota.getValue().merchantMemberId());
        assertEquals(USER_ONE,quota.getValue().memberId());assertEquals(Long.valueOf(21L),quota.getValue().nodeId());
        verify(aiGatewayService,never()).generate(anyLong(),anyString(),anyString(),anyString(),anyString());
        verify(asrIntents,times(1)).claimTextAdmission(USER_ONE,request.getRequestId(),-21L);
    }
    @Test void asrMerchantOrFrozenTranscriptMismatchCannotAdmitOrReachEitherGatewayPath() {
        NpcChatReq request=req("asr-frozen-owner","原转写");var original=originalAsr(request);
        original.setMerchantMemberId(43L);
        service.chatWithShop(request.getRequestId(),USER_ONE,21L,42L,"本店事实","原转写");
        original.setMerchantMemberId(42L);
        service.chatWithShop(request.getRequestId(),USER_ONE,21L,42L,"本店事实","替换内容");
        verify(asrIntents,never()).claimTextAdmission(anyLong(),anyString(),anyLong());
        verify(npcChatRequestMapper,never()).insertNpcChatRequest(any());org.mockito.Mockito.verifyNoInteractions(aiGatewayService);
    }

}
