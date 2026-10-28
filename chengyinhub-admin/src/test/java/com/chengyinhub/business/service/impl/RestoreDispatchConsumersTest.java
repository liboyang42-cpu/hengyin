package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.notify.WxSubscribeChannel;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.ai.*;
import com.chengyinhub.business.service.ai.impl.*;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.business.task.*;
import com.chengyinhub.common.config.AiVoiceConfig;
import com.chengyinhub.common.config.RestoreDispatchGuard;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import com.chengyinhub.common.utils.sms.AliSmsUtil;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.MockedStatic;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real service entry calls with collaborator doubles; never a Spring/MySQL/provider acceptance claim. */
class RestoreDispatchConsumersTest {
    private MockedStatic<RestoreDispatchGuard> restored() {
        MockedStatic<RestoreDispatchGuard> flag = mockStatic(RestoreDispatchGuard.class, CALLS_REAL_METHODS);
        flag.when(RestoreDispatchGuard::isRestoreMode).thenReturn(true);
        return flag;
    }

    private void blocked(Executable entry) {
        RestoreDispatchGuard.Blocked failure = assertThrows(RestoreDispatchGuard.Blocked.class, entry);
        assertEquals("RECOVERY_DISPATCH_BLOCKED", failure.getMessage());
        assertNull(failure.getCause());
    }

    private PlatformTransactionManager transactions() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return manager;
    }

    @Test void creatorAdmissionsCannotConsumeOrAllocateInRestore() {
        AiCreatorServiceImpl creator = new AiCreatorServiceImpl();
        AiGatewayService gateway = mock(AiGatewayService.class);
        ReflectionTestUtils.setField(creator, "gateway", gateway);
        try (var flag = restored()) {
            for (Executable entry : List.<Executable>of(
                    () -> creator.merchantPlan(null, 7L), () -> creator.themeDraft(null, 7L),
                    () -> creator.planThemeDraft(7L, "original"),
                    () -> creator.themeDraftPrepared(null, 7L, null),
                    () -> creator.nodeGenerate(null, 7L), () -> creator.templateFill(null, 7L),
                    () -> creator.chapterOverview(null, 7L), () -> creator.safetyPrecheck(null, 7L),
                    () -> creator.merchantTaskSuggest(null, 7L), () -> creator.clubDesign(null, 7L),
                    () -> creator.merchantInsight("{}", 7L))) blocked(entry);
        }
        verifyNoInteractions(gateway);
    }

    @Test void gatewayDoesNotTurnRestoreIntoQuotaRefundOrProviderFailure() {
        AiGatewayServiceImpl gateway = new AiGatewayServiceImpl();
        RedisTemplate<?, ?> redis = mock(RedisTemplate.class);
        AiQuotaRecoveryService recovery = mock(AiQuotaRecoveryService.class);
        ReflectionTestUtils.setField(gateway, "redisTemplate", redis);
        ReflectionTestUtils.setField(gateway, "quotaRecovery", recovery);
        try (var flag = restored()) {
            blocked(() -> gateway.generate(7L, "player", "theme_draft", "s", "u"));
            blocked(() -> gateway.generateThemeDraftPrepared(7L, null, "s", "u"));
            blocked(() -> gateway.generateNpcAsrHandoff(null, "s", "u"));
            blocked(() -> gateway.consumeQuota(7L, "npc_shop_chat"));
            blocked(() -> gateway.consumePlannedQuota(7L, "npc_shop_chat", "original"));
            blocked(() -> gateway.refundQuota(7L, "npc_shop_chat", "original"));
        }
        verifyNoInteractions(redis, recovery);
    }

    @Test void themeIntentNeitherCreatesClaimsNorTerminalizesWhenPaused() {
        AiThemeDraftRequestService service = new AiThemeDraftRequestService();
        AiThemeDraftIntentMapper mapper = mock(AiThemeDraftIntentMapper.class);
        ReflectionTestUtils.setField(service, "intentMapper", mapper);
        try (var flag = restored()) {
            blocked(() -> service.prepare(7L, null));
            blocked(() -> service.execute(7L, null));
        }
        verifyNoInteractions(mapper);
    }

    @Test void photoCannotClaimOldPreparedOrReleaseOriginalReservation() {
        PhotoCheckIntentMapper mapper = mock(PhotoCheckIntentMapper.class);
        AiGatewayService quota = mock(AiGatewayService.class);
        PhotoCheckVisionService vision = mock(PhotoCheckVisionService.class);
        PlatformTransactionManager manager = transactions();
        PhotoCheckIntentService intents = new PhotoCheckIntentService(mapper, quota, vision,
                mock(ConsentGuard.class), mock(AppMediaReferenceGuard.class), manager);
        try (var flag = restored()) {
            blocked(() -> intents.prepare(null));
            blocked(() -> intents.executeOnce(7L, 41L, "original"));
            blocked(() -> new PhotoCheckVisionService().review(7L, "media-asset:1", "original"));
        }
        verifyNoInteractions(mapper, quota, vision);
        verify(manager, never()).getTransaction(any());
    }

    @Test void asrAndTextAdmissionsKeepOriginalRowsAndQuotaUntouched() {
        NpcAsrIntentMapper asr = mock(NpcAsrIntentMapper.class);
        NpcChatRequestMapper requests = mock(NpcChatRequestMapper.class);
        NpcChatLogMapper logs = mock(NpcChatLogMapper.class);
        SpeechToTextService speech = mock(SpeechToTextService.class);
        AiGatewayService quota = mock(AiGatewayService.class);
        PlatformTransactionManager manager = transactions();
        NpcAsrIntentService service = new NpcAsrIntentService(asr, requests, manager, speech, quota);
        NpcChatWriteService text = new NpcChatWriteService(requests, logs, manager);
        try (var flag = restored()) {
            blocked(() -> service.recognize(7L, "original", 2L, 3L, new byte[]{1}, "wav"));
            blocked(() -> service.beginHandoff(7L, "original", 2L, 3L));
            blocked(() -> text.admit(null, "original"));
            blocked(() -> text.admitShop(null, "original", 3L, "text"));
            NpcChatService chat = new NpcChatService();
            blocked(() -> chat.chat(null, 7L));
            blocked(() -> chat.chatWithMerchant(null, 7L, 3L));
            blocked(() -> chat.chatWithShop("original", 7L, 2L, 3L, "", "text"));
        }
        verifyNoInteractions(asr, requests, logs, speech, quota);
        verify(manager, never()).getTransaction(any());
    }

    @Test void restoredAsrUnknownCanStillBeReadWithoutProviderOrCompensation() {
        NpcAsrIntentMapper asr = mock(NpcAsrIntentMapper.class);
        NpcChatRequestMapper requests = mock(NpcChatRequestMapper.class);
        SpeechToTextService speech = mock(SpeechToTextService.class);
        AiGatewayService quota = mock(AiGatewayService.class);
        when(requests.lockChatUser(7L)).thenReturn(7L);
        NpcAsrIntent original = new NpcAsrIntent();
        original.setUserId(7L); original.setRequestId("original"); original.setNodeId(2L);
        original.setState("UNKNOWN"); original.setQuotaReservation("original-reservation");
        when(asr.selectForUpdate(7L, "original")).thenReturn(original);
        NpcAsrIntentService service = new NpcAsrIntentService(asr, requests, transactions(), speech, quota);
        try (var flag = restored()) {
            assertSame(original, service.read(7L, "original", 2L));
        }
        assertEquals("UNKNOWN", original.getState());
        assertEquals("original-reservation", original.getQuotaReservation());
        verify(asr).selectForUpdate(7L, "original"); verifyNoMoreInteractions(asr);
        verifyNoInteractions(speech, quota);
    }

    @Test void quotaRecoveryDoesNotClaimExpireRefundOrReclassifyOldEvidence() {
        AiQuotaReservationMapper mapper = mock(AiQuotaReservationMapper.class);
        NpcAsrIntentMapper asr = mock(NpcAsrIntentMapper.class);
        RedisTemplate<Object, Object> redis = mock(RedisTemplate.class);
        PlatformTransactionManager manager = transactions();
        AiQuotaRecoveryService service = new AiQuotaRecoveryService(mapper, redis, manager, asr);
        try (var flag = restored()) {
            service.runDue();
            blocked(() -> service.requireRefund("original", "SERVICE_FAILURE"));
            blocked(() -> service.refundNpcAsrNotSent(7L, "original"));
            blocked(() -> service.prepare(7L, "npc_shop_chat", "gen", "original", "original"));
        }
        verifyNoInteractions(mapper, redis, asr);
        verify(manager, never()).getTransaction(any());
    }

    @Test void providerAndMediaPortsRefuseBeforeAnyRealIo() throws Exception {
        try (var flag = restored()) {
            OpenAiCompatibleLlmClient llm = new OpenAiCompatibleLlmClient("p", "https://invalid", "key", "m", true, 1000);
            blocked(() -> llm.chat("s", "u"));
            blocked(() -> llm.chatVision("s", "image", "u"));
            blocked(() -> new DashScopeVoiceClient().enroll("image"));
            blocked(() -> new DashScopeVoiceClient().synthesize("voice", "text"));
            blocked(() -> new DashScopeSpeechToTextService(new AiVoiceConfig()).transcribe(new byte[]{1}, "wav"));
            blocked(() -> new TencentMapClient().get("https://apis.map.qq.com/"));
            blocked(() -> new ImageSegClient().segment("image"));
            blocked(() -> new ObjectNamer().name("image"));
            blocked(() -> new OssCardCutoutStorage().transfer("image", 7L));
            blocked(() -> new OssCardCutoutStorage().stageGeneratedAudio(7L, "audio"));
            blocked(() -> new HttpWebhookTransport(100, 100).post("https://invalid", "{}"));
            blocked(() -> FileOssUploadUtils.upload(null));
            blocked(() -> FileOssUploadUtils.uploadWithOwner(null, "7", "IMAGE"));
            blocked(() -> FileOssUploadUtils.uploadPendingWithOwner(null, "7", "IMAGE"));
            blocked(() -> FileOssUploadUtils.uploadAftercareEvidence(null, "7", 1L, 2L));
            blocked(() -> FileOssUploadUtils.deleteOwnedObjectIfPresent("original"));
            blocked(() -> AliSmsUtil.sendCode("a", "b", "s", "t", "phone", "code"));
        }
    }

    @Test void notificationAndMarketingEntriesRefuseBeforeGrantClaimOrMutation() {
        try (var flag = restored()) {
            blocked(() -> new NotifyServiceImpl().notifySignupSuccess(7L, "original"));
            blocked(() -> new WxSubscribeChannel().send(null));
            blocked(() -> new WxSubscribeMsgServiceImpl().send("original", "template", "page", Map.of()));
            blocked(() -> new WxSubscribeMsgServiceImpl().post("https://invalid", "{}"));
            blocked(() -> new OfficialBroadcastServiceImpl().publish(null, 7L));
            blocked(() -> new OfficialBroadcastServiceImpl().dispatch(null));
            blocked(() -> new MerchantCrmCampaignServiceImpl().create(7L, null));
            blocked(() -> new MerchantCrmCampaignServiceImpl().dispatch(7L, 1L));
            blocked(() -> new MerchantCrmCampaignServiceImpl().retry(7L, 1L));
            blocked(() -> new MerchantCrmRecipientDeliveryServiceImpl().deliver(1L, 2L, 3L));
            blocked(() -> new ImServiceImpl().deliverSystemNotification(41L));
            blocked(() -> new WxContentSecurityServiceImpl().checkText("original", "openid"));
            blocked(() -> new WxContentSecurityServiceImpl().checkMediaAsync("image", "openid"));
            blocked(() -> new WxContentSecurityServiceImpl().submitMediaCheck(9, 41L, "image", "openid"));
            blocked(() -> new WxContentSecurityServiceImpl().submitMediaCheckOne(9, 41L, "image", "identity", "openid"));
        }
    }

    @Test void durableNotificationWorkersPauseBeforeEnumeratingOrClaiming() {
        CommittedOfficialNoticeWxMapper mapper = mock(CommittedOfficialNoticeWxMapper.class);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        ISystemNotificationIntentService intents = mock(ISystemNotificationIntentService.class);
        IImService im = mock(IImService.class);
        WxSubscribeChannel wx = mock(WxSubscribeChannel.class);
        PlatformTransactionManager manager = transactions();
        CommittedOfficialNoticeService service = new CommittedOfficialNoticeService(mapper, intents, im, wx, manager, executor);
        CommunityPostMapper communityRows = mock(CommunityPostMapper.class);
        IContentSafetyGate communitySafety = mock(IContentSafetyGate.class);
        IAuditTaskService communityAudit = mock(IAuditTaskService.class);
        CommunityTrustService communityTrust = mock(CommunityTrustService.class);
        ICommunityMediaReceiptService communityReceipts = mock(ICommunityMediaReceiptService.class);
        CommunityPostServiceImpl communityPosts = new CommunityPostServiceImpl(communityRows,
                new CommunityPostLifecyclePolicy(), communitySafety, communityAudit, communityTrust, communityReceipts);
        try (var flag = restored()) {
            service.deliver(41L);
            assertEquals(0, service.reconcilePending());
            new OfficialBroadcastServiceImpl().runDueScheduled();
            assertEquals(0, new MerchantCrmCampaignServiceImpl().recoverStaleProcessing());
            new ImNotificationDeliveryTask().deliverPending();
            new BeforeStartNotifyTask().run();
            blocked(() -> communityPosts.runMaintenance(10));
        }
        verifyNoInteractions(mapper, executor, intents, im, wx);
        verifyNoInteractions(communityRows, communitySafety, communityAudit, communityTrust, communityReceipts);
        verify(manager, never()).getTransaction(any());
    }

    @Test void legacyRunnableBoundaryCannotCallOrReportSupplierSuccessWhenRestored() {
        CommittedBusinessNotice service = new CommittedBusinessNotice(mock(INotifyService.class),
                mock(AlarmService.class), transactions(), mock(ScheduledExecutorService.class));
        java.util.function.Supplier<Boolean> delivery = mock(java.util.function.Supplier.class);
        try (var flag = restored()) {
            blocked(() -> service.dispatchInNewTransaction("original", 1L, delivery));
            blocked(() -> service.deliverInNewTransaction("original", 1L, delivery));
        }
        verifyNoInteractions(delivery);
    }

    @Test void normalModeStillDelegatesAnExistingPublicGenerationContract() {
        AiCreatorServiceImpl creator = new AiCreatorServiceImpl();
        IAiTopicPlanService plans = mock(IAiTopicPlanService.class);
        ReflectionTestUtils.setField(creator, "aiTopicPlanService", plans);
        var response = new com.chengyinhub.business.domain.vo.ai.AiMerchantPlanResp();
        when(plans.generate(null, 7L)).thenReturn(response);
        try (var flag = mockStatic(RestoreDispatchGuard.class, CALLS_REAL_METHODS)) {
            flag.when(RestoreDispatchGuard::isRestoreMode).thenReturn(false);
            assertSame(response, creator.merchantPlan(null, 7L));
        }
        verify(plans).generate(null, 7L);
    }

    @Test void voiceEnrollmentDoesNotCreateAnIntentOrResolveAProcessingUrl() {
        NpcVoiceEnrollmentMapper mapper = mock(NpcVoiceEnrollmentMapper.class);
        NpcProfileMapper profiles = mock(NpcProfileMapper.class);
        VoiceCloneClient provider = mock(VoiceCloneClient.class);
        PlatformTransactionManager manager = transactions();
        NpcVoiceEnrollmentService service = new NpcVoiceEnrollmentService(mapper, profiles, provider, manager);
        java.util.function.Supplier<String> url = mock(java.util.function.Supplier.class);
        try (var flag = restored()) {
            blocked(() -> service.enroll(7L, 2, 3L, 4L, "media-asset:1", url));
            blocked(() -> service.enroll(7L, 2, 3L, 4L, "media-asset:1", "original", 5L, true, url));
            NodeNpcService caller = new NodeNpcService(mock(IChapterMerchantNodeService.class), profiles,
                    mock(IWxContentSecurityService.class), provider);
            blocked(() -> caller.voiceEnroll(7L, 7L, 3L, "media-asset:1"));
        }
        verifyNoInteractions(mapper, profiles, provider, url);
        verify(manager, never()).getTransaction(any());
    }

    @Test void originalVoiceStatusQueryRemainsAQueryInRestore() {
        com.chengyinhub.common.config.AiVoiceConfig config = mock(com.chengyinhub.common.config.AiVoiceConfig.class);
        when(config.isUsable()).thenReturn(true);
        when(config.getTimeoutMs()).thenReturn(5000);
        when(config.baseUrl()).thenReturn("https://voice.invalid/api");
        when(config.getApiKey()).thenReturn("synthetic");
        DashScopeVoiceClient client = new DashScopeVoiceClient();
        ReflectionTestUtils.setField(client, "config", config);
        ReflectionTestUtils.setField(client, "admission", new AiHttpAdmission(List.of("https://voice.invalid/api")));
        try (var flag = restored(); var http = mockStatic(com.chengyinhub.common.utils.http.HttpUtils.class)) {
            http.when(() -> com.chengyinhub.common.utils.http.HttpUtils.sendJsonPost(
                    anyString(), anyString(), anyString(), anyLong(),
                    any(com.chengyinhub.common.utils.http.HttpUtils.AttemptLease.class)))
                    .thenReturn("{\"output\":{\"status\":\"OK\"}}");
            assertTrue(client.isReady("original-voice-id"));
            http.verify(() -> com.chengyinhub.common.utils.http.HttpUtils.sendJsonPost(
                    eq("https://voice.invalid/api/customization"),
                    argThat(body -> body.contains("query_voice") && body.contains("original-voice-id")
                            && !body.contains("create_voice")), eq("synthetic"), anyLong(),
                    any(com.chengyinhub.common.utils.http.HttpUtils.AttemptLease.class)));
        }
    }

    @Test void originalMissingBeforeStartReadDoesNotCreateOrClaimAnIntent() {
        CommittedOfficialNoticeWxMapper mapper = mock(CommittedOfficialNoticeWxMapper.class);
        ISystemNotificationIntentService intents = mock(ISystemNotificationIntentService.class);
        IImService im = mock(IImService.class);
        WxSubscribeChannel wx = mock(WxSubscribeChannel.class);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        CommittedOfficialNoticeService service = new CommittedOfficialNoticeService(
                mapper, intents, im, wx, transactions(), executor);
        try (var flag = restored()) {
            var result = service.beforeStart(11L, 7L, "original", new java.util.Date());
            assertEquals(new com.chengyinhub.business.domain.dto.BeforeStartNotifyResult.Delivery(
                    null, "NOT_FOUND", false), result);
        }
        verify(mapper).selectByKeyForUpdate("before-start:11:7"); verifyNoMoreInteractions(mapper);
        verifyNoInteractions(intents, im, wx, executor);
    }

    @Test void mediaAdmissionAndLegacyUnknownReadHaveNoRepairSideEffects() {
        AppMediaAssetMapper assets = mock(AppMediaAssetMapper.class);
        org.springframework.beans.factory.ObjectProvider<IAuditTaskService> audits =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        AppMediaAssetService media = new AppMediaAssetService(assets, audits);
        AiThemeDraftRequestService requests = new AiThemeDraftRequestService();
        RedisTemplate<Object, Object> redis = mock(RedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<Object, Object> values =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ReflectionTestUtils.setField(requests, "redisTemplate", redis);
        String id = "00000000-0000-0000-0000-000000000001";
        try (var flag = restored()) {
            blocked(() -> media.stage(7L, "original"));
            blocked(() -> media.retry(1L, 7L));
            var original = requests.read(7L, id);
            assertEquals("UNKNOWN", original.getState());
            assertEquals("LEGACY_UNBOUND", original.getMessage());
        }
        verifyNoInteractions(assets, audits);
        verify(values).get(AiThemeDraftRequestService.KEY_PREFIX + 7L + ":" + id);
        verifyNoMoreInteractions(values);
    }

    @Test void clubAndRecallPauseBeforeClaimsOrSubscriptionAttempts() {
        var access = mock(com.chengyinhub.business.access.club.IClubAccessService.class);
        ClubEventOpsMapper mapper = mock(ClubEventOpsMapper.class);
        IClubEventNotificationTransactionService transactions = mock(IClubEventNotificationTransactionService.class);
        ClubEventNotificationServiceImpl club = new ClubEventNotificationServiceImpl(access, mapper, transactions);
        RoamRecallService recall = new RoamRecallService();
        RoamMapper roam = mock(RoamMapper.class);
        INotifyService notify = mock(INotifyService.class);
        ReflectionTestUtils.setField(recall, "enabled", true);
        ReflectionTestUtils.setField(recall, "roamMapper", roam);
        ReflectionTestUtils.setField(recall, "notifyService", notify);
        try (var flag = restored()) {
            blocked(() -> club.send(7L, null));
            blocked(() -> club.retryFailed(7L, 1L));
            assertEquals(0, club.retryDue());
            assertEquals(0, recall.runDue(new java.util.Date()));
        }
        verifyNoInteractions(access, mapper, transactions, roam, notify);
    }

    @Test void cachedQrIsReadableButMissingQrCannotAllocateOrCallItsGateway() {
        PlayerWxacodeServiceImpl.WxCodeGateway gateway = mock(PlayerWxacodeServiceImpl.WxCodeGateway.class);
        PlayerWxacodeServiceImpl.WxCodeStorage storage = mock(PlayerWxacodeServiceImpl.WxCodeStorage.class);
        var redis = mock(com.chengyinhub.common.core.redis.RedisCache.class);
        PlayerWxacodeServiceImpl service = new PlayerWxacodeServiceImpl(gateway, storage);
        ReflectionTestUtils.setField(service, "redisCache", redis);
        ReflectionTestUtils.setField(service, "signingSecret", "synthetic-source-test-secret");
        when(redis.getCacheObject("wx:player-code:v1:7")).thenReturn("original-url");
        try (var flag = restored()) {
            assertEquals("original-url", service.getOrCreate(7L));
            blocked(() -> service.getOrCreate(8L));
            blocked(() -> service.getOrCreateScene("original-scene"));
        }
        verifyNoInteractions(gateway, storage);
        verify(redis).getCacheObject("wx:player-code:v1:7");
        verify(redis).getCacheObject("wx:player-code:v1:8");
        verify(redis).getCacheObject("wx:door-code:v1:original-scene");
        verifyNoMoreInteractions(redis);
    }

    @Test void objectMintRefusesBeforeAnyWorkIsQueuedOrAnyCardIsWritten() {
        ObjectCardServiceImpl service = new ObjectCardServiceImpl();
        java.util.concurrent.ThreadPoolExecutor executor = (java.util.concurrent.ThreadPoolExecutor)
                ReflectionTestUtils.getField(service, "mintTasks");
        try (var flag = restored()) {
            blocked(() -> service.mint(7L, "media-asset:1", null, "play", "original"));
            assertEquals(0L, executor.getTaskCount());
            assertEquals(0, executor.getQueue().size());
        } finally { executor.shutdownNow(); }
    }

    @Test void mapMissCannotBecomeANormalProviderFailureOrEnterItsRequestSlot() {
        TencentMapClient client = mock(TencentMapClient.class);
        when(client.isReady()).thenReturn(true);
        MapGeocodeServiceImpl geocode = new MapGeocodeServiceImpl();
        ReflectionTestUtils.setField(geocode, "tencentMapClient", client);
        try (var flag = restored()) {
            blocked(() -> geocode.resolveCity(120.0, 30.0));
            blocked(() -> geocode.suggestCoords("original", "original"));
        }
        verify(client, times(2)).isReady();
        verifyNoMoreInteractions(client);
    }

    @Test void guardFromADeeperCreatorDependencyCannotTriggerFinallyCompensation() {
        AiCreatorServiceImpl creator = new AiCreatorServiceImpl();
        var fixture = new AiNotSentGatewayFixture(7L);
        AiGatewayService gateway = mock(AiGatewayService.class);
        ReflectionTestUtils.setField(creator, "gateway", gateway);
        ReflectionTestUtils.setField(creator, "redisTemplate", fixture.redis);
        ReflectionTestUtils.setField(creator, "quotaRecovery", fixture.recovery);
        ReflectionTestUtils.setField(creator, "umsMemberService", mock(IUmsMemberService.class));
        var request = new com.chengyinhub.business.domain.dto.ai.AiThemeDraftReq();
        request.setIdea("original");
        RestoreDispatchGuard.Blocked original = new RestoreDispatchGuard.Blocked();
        when(gateway.generate(eq(7L), eq("player"), eq("theme_draft"), anyString(), anyString())).thenThrow(original);
        try (var flag = mockStatic(RestoreDispatchGuard.class, CALLS_REAL_METHODS)) {
            flag.when(RestoreDispatchGuard::isRestoreMode).thenReturn(false);
            assertSame(original, assertThrows(RestoreDispatchGuard.Blocked.class, () -> creator.themeDraft(request, 7L)));
        }
        verify(fixture.recovery).reserved(anyString(), eq(1L));
        verify(fixture.recovery, never()).requireRefund(anyString(), anyString());
        verify(fixture.recovery, never()).retain(anyString(), anyString());
    }

    @Test void guardFromModelIsNotReclassifiedAsNotSentOrUnknownByGateway() {
        AiNotSentGatewayFixture fixture = new AiNotSentGatewayFixture(7L);
        Object config = ReflectionTestUtils.getField(fixture.gateway, "aiModelConfig");
        ReflectionTestUtils.setField(config, "enabled", true);
        ReflectionTestUtils.setField(config, "timeoutMs", 1000);
        RestoreDispatchGuard.Blocked original = new RestoreDispatchGuard.Blocked();
        when(fixture.model.chat(anyString(), anyString())).thenThrow(original);
        try (var flag = mockStatic(RestoreDispatchGuard.class, CALLS_REAL_METHODS)) {
            flag.when(RestoreDispatchGuard::isRestoreMode).thenReturn(false);
            assertSame(original, assertThrows(RestoreDispatchGuard.Blocked.class,
                    () -> fixture.gateway.generate(7L, "club", "theme_draft", "s", "u")));
        }
        verify(fixture.recovery, never()).requireRefund(anyString(), anyString());
        verify(fixture.recovery, never()).retain(anyString(), anyString());
        verify(fixture.model).chat("s", "u");
    }
}
