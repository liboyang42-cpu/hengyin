package com.chengyinhub.web.controller.api;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.VoiceCloneClient;
import com.chengyinhub.common.core.domain.AjaxResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 门店 AI 形象的声音:复刻、查状态、清除。
 *
 * 守的是四条会静默出错的性质:
 *  1) 没开通时必须明说 —— 返回「成功但没声音」会让商家反复重试一件做不到的事;
 *  2) 音色还在部署就写「可用」,失败会推迟到第一次说话时才炸,长得像网络抖动;
 *  3) 先落「复刻中」再打上游 —— 反过来的话,上游成功而落库失败 = 花了钱没记住;
 *  4) 越权:音色也挂在同一张表上,只按 id 更新等于谁都能换别人的声音。
 */
class ApiMerchantNpcVoiceTest {

    private static final Long MEMBER_ID = 201L;
    private static final Long MERCHANT_ID = 11L;
    private static final Long NPC_ID = 42L;
    private static final String SAMPLE = "https://cdn.example.com/voice/sample.mp3";
    private static final String CANONICAL_SAMPLE = "media-asset:41";

    private NpcProfileMapper npcProfileMapper;
    private VoiceCloneClient voiceClient;
    private ApiMerchantController controller;
    private com.chengyinhub.business.service.ai.NpcVoiceEnrollmentFixture enrollment;


    private static final String REGISTRY_AUDIO_KEY = "upload/0123456789abcdef0123456789abcdef.mp3";
    private static final String REGISTRY_AUDIO_REF = "media-asset:73";
    private void actualAudioRegistry(String reviewState) {
        com.chengyinhub.business.mapper.AppMediaAssetMapper assets = mock(com.chengyinhub.business.mapper.AppMediaAssetMapper.class);
        java.util.Map<String,Object> fact = java.util.Map.of("id",73L,"memberId",MEMBER_ID,"ownerType","APP",
                "objectKey",REGISTRY_AUDIO_KEY,"mediaType","AUDIO","reviewState",reviewState);
        when(assets.select(73L)).thenReturn(fact);
        when(assets.selectByObjectKey(REGISTRY_AUDIO_KEY)).thenReturn(fact);
        com.chengyinhub.business.service.support.AppMediaReferenceGuard actual = new com.chengyinhub.business.service.support.AppMediaReferenceGuard();
        org.springframework.test.util.ReflectionTestUtils.setField(actual,"mapper",assets);
        org.springframework.test.util.ReflectionTestUtils.setField(actual,"assets",mock(com.chengyinhub.business.service.impl.AppMediaAssetService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"mediaReferences",actual);
    }

    @Test void longSignedAliasesOfApprovedSampleReplayPendingAndReadyWithoutAnyProviderOrWrite() {
        actualAudioRegistry("APPROVED");
        for (int state : new int[]{1,2}) {
            NpcProfile row = new NpcProfile();
            row.setId(NPC_ID); row.setScopeType(2); row.setScopeId(MERCHANT_ID);
            row.setVoiceSample(REGISTRY_AUDIO_REF); row.setVoiceStatus(state);
            when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null)).thenReturn(new ArrayList<>(Arrays.asList(row)));
            for (String signature : new String[]{"x", "y"}) {
                String signed = "https://approved.example/"+REGISTRY_AUDIO_KEY+"?signature="+signature.repeat(320);
                assertEquals(state, controller.npcVoiceEnroll(body(signed)).get("voiceStatus"));
            }
        }
        org.mockito.Mockito.verifyNoInteractions(voiceClient);
        verify(npcProfileMapper,never()).updateMerchantNpcVoice(any());
    }

    @Test void heldSampleCannotUseExistingVoiceStateAsAnApprovalBypass() {
        actualAudioRegistry("HOLD");
        NpcProfile row = new NpcProfile();
        row.setId(NPC_ID); row.setScopeType(2); row.setScopeId(MERCHANT_ID);
        row.setVoiceSample(REGISTRY_AUDIO_REF); row.setVoiceStatus(2);
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null)).thenReturn(new ArrayList<>(Arrays.asList(row)));
        org.junit.jupiter.api.Assertions.assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> controller.npcVoiceEnroll(body(REGISTRY_AUDIO_REF)));
        org.mockito.Mockito.verifyNoInteractions(voiceClient);
        verify(npcProfileMapper,never()).updateMerchantNpcVoice(any());
    }

    @Test void newApprovedSampleStoresCanonicalReferenceAndSignsOnlyOnceBeforeMockProviderCall() {
        actualAudioRegistry("APPROVED");
        NpcProfile row = new NpcProfile();
        row.setId(NPC_ID); row.setScopeType(2); row.setScopeId(MERCHANT_ID);
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null)).thenReturn(new ArrayList<>(Arrays.asList(row)));
        when(npcProfileMapper.updateMerchantNpcVoice(any())).thenReturn(1);
        when(voiceClient.usable()).thenReturn(true);
        when(voiceClient.enroll("provider-short-sample")).thenReturn("test-voice-id");
        when(voiceClient.isReady("test-voice-id")).thenReturn(false);
        try (org.mockito.MockedStatic<com.chengyinhub.common.utils.file.FileOssUploadUtils> signatures =
                     org.mockito.Mockito.mockStatic(com.chengyinhub.common.utils.file.FileOssUploadUtils.class)) {
            signatures.when(() -> com.chengyinhub.common.utils.file.FileOssUploadUtils.generateShortLivedPresignedUrl(REGISTRY_AUDIO_KEY,300))
                    .thenReturn("provider-short-sample");
            controller.npcVoiceEnroll(body("https://approved.example/"+REGISTRY_AUDIO_KEY+"?signature="+"x".repeat(320)));
            signatures.verify(() -> com.chengyinhub.common.utils.file.FileOssUploadUtils.generateShortLivedPresignedUrl(REGISTRY_AUDIO_KEY,300),org.mockito.Mockito.times(1));
        }
        verify(voiceClient,org.mockito.Mockito.times(1)).enroll("provider-short-sample");
        ArgumentCaptor<NpcProfile> writes = ArgumentCaptor.forClass(NpcProfile.class);
        verify(npcProfileMapper,org.mockito.Mockito.times(2)).updateMerchantNpcVoice(writes.capture());
        assertEquals(REGISTRY_AUDIO_REF,writes.getAllValues().get(0).getVoiceSample());
    }

    @BeforeEach
    void setUp() {
        npcProfileMapper = mock(NpcProfileMapper.class);
        when(npcProfileMapper.updateMerchantNpcVoice(any())).thenReturn(1);
        voiceClient = mock(VoiceCloneClient.class);
        controller = new TestableController(MEMBER_ID);
        com.chengyinhub.business.service.support.AppMediaReferenceGuard media = mock(com.chengyinhub.business.service.support.AppMediaReferenceGuard.class);
        when(media.requireAttachment(MEMBER_ID, SAMPLE, true)).thenReturn(CANONICAL_SAMPLE);
        when(media.processingUrl(CANONICAL_SAMPLE, MEMBER_ID, "AUDIO")).thenReturn(SAMPLE);
        ReflectionTestUtils.setField(controller, "mediaReferences", media);

        IMerchantAccessService accessService = mock(IMerchantAccessService.class);
        when(accessService.require(MEMBER_ID, MerchantPermission.PROFILE_WRITE))
                .thenReturn(new MerchantAccessContext(
                        MEMBER_ID, MERCHANT_ID, MEMBER_ID, MerchantRoleCode.MERCHANT_OWNER));

        ReflectionTestUtils.setField(controller, "npcProfileMapper", npcProfileMapper);
        ReflectionTestUtils.setField(controller, "consentGuard", mock(com.chengyinhub.business.service.ConsentGuard.class));
        ReflectionTestUtils.setField(controller, "voiceCloneClient", voiceClient);
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);
        enrollment = new com.chengyinhub.business.service.ai.NpcVoiceEnrollmentFixture(
                npcProfileMapper, voiceClient, 2, MERCHANT_ID, () -> {
                    java.util.List<NpcProfile> list = npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null);
                    return list == null || list.isEmpty() ? null : list.get(0);
                });
        ReflectionTestUtils.setField(controller, "voiceEnrollmentService", enrollment.service);
    }

    @Test
    void separateConsentRefusalPreventsAnyProviderCallOrMediaWrite() {
        com.chengyinhub.common.config.ComplianceProperties versions = new com.chengyinhub.common.config.ComplianceProperties();
        versions.setDocVersions(java.util.Map.of("photo_model_processing", "v1.0", "voice_clone_processing", "v1.0"));
        com.chengyinhub.business.service.ConsentGuard guard = new com.chengyinhub.business.service.ConsentGuard(
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IConsentRecordService.class), versions);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "consentGuard", guard);
        org.junit.jupiter.api.Assertions.assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> controller.npcVoiceEnroll(body(SAMPLE)));
        org.mockito.Mockito.verifyNoInteractions(voiceClient);
    }

    @Test
    void noPersistedPendingMeansNoUpstreamEnrollment() throws Exception {
        haveOwnNpc();
        when(voiceClient.usable()).thenReturn(true);
        when(npcProfileMapper.updateMerchantNpcVoice(any())).thenReturn(0);
        org.junit.jupiter.api.Assertions.assertThrows(
                com.chengyinhub.business.service.ai.NpcVoiceEnrollmentService.StateUnavailableException.class,
                () -> controller.npcVoiceEnroll(body(SAMPLE)));
        verify(voiceClient, never()).enroll(anyString());
    }

    @Test
    void retryOfSameCompletedSampleDoesNotEnrollAgain() throws Exception {
        NpcProfile mine = new NpcProfile();
        mine.setId(10L); mine.setScopeType(2); mine.setScopeId(MERCHANT_ID);
        mine.setVoiceStatus(2); mine.setVoiceSample(CANONICAL_SAMPLE); mine.setVoiceId("existing");
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null))
                .thenReturn(new ArrayList<>(Arrays.asList(mine)));
        assertEquals(2, controller.npcVoiceEnroll(body(SAMPLE)).get("voiceStatus"));
        verify(voiceClient, never()).enroll(anyString());
        verify(npcProfileMapper, never()).updateMerchantNpcVoice(any());
    }

    private void haveOwnNpc() {
        NpcProfile mine = new NpcProfile();
        mine.setId(NPC_ID); mine.setScopeType(2);
        mine.setScopeId(MERCHANT_ID);
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null))
                .thenReturn(new ArrayList<>(Arrays.asList(mine)));
    }

    // ===== 未开通 =====

    @Test
    void enrollRefusesWhenServiceNotConfigured() throws Exception {
        haveOwnNpc();
        when(voiceClient.usable()).thenReturn(false);

        AjaxResult r = controller.npcVoiceEnroll(body(SAMPLE));

        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        // 关键:不能落任何状态,也不能打上游 —— 报「成功但没声音」会让商家一直重试
        verify(npcProfileMapper, never()).updateMerchantNpcVoice(any());
        verify(voiceClient, never()).enroll(anyString());
    }

    // ===== 状态流转 =====

    @Test
    void enrollWritesPendingBeforeCallingUpstream() throws Exception {
        haveOwnNpc();
        when(voiceClient.usable()).thenReturn(true);
        when(voiceClient.enroll(SAMPLE)).thenReturn("cosyvoice-v2-chengyin-abc");
        when(voiceClient.isReady(anyString())).thenReturn(true);

        controller.npcVoiceEnroll(body(SAMPLE));

        // 顺序不能反:先落「复刻中」再打上游。反过来的话上游成功而落库失败,
        // 商家看到失败会再点一次,于是又生成一个音色 —— 钱花了,记录没有。
        InOrder order = inOrder(npcProfileMapper, voiceClient);
        order.verify(npcProfileMapper).updateMerchantNpcVoice(any());
        order.verify(voiceClient).enroll(SAMPLE);

        ArgumentCaptor<NpcProfile> cap = ArgumentCaptor.forClass(NpcProfile.class);
        verify(npcProfileMapper, times(3)).updateMerchantNpcVoice(cap.capture());
        assertEquals(Integer.valueOf(1), cap.getAllValues().get(0).getVoiceStatus());
        assertNull(cap.getAllValues().get(0).getVoiceId(), "复刻中不该有音色 id");
        assertEquals(Integer.valueOf(1), cap.getAllValues().get(1).getVoiceStatus());
        assertEquals("cosyvoice-v2-chengyin-abc", cap.getAllValues().get(1).getVoiceId());
        assertEquals(Integer.valueOf(2), cap.getAllValues().get(2).getVoiceStatus());
        assertEquals("cosyvoice-v2-chengyin-abc", cap.getAllValues().get(2).getVoiceId());
    }

    @Test
    void enrollKeepsPendingWhenVoiceNotDeployedYet() throws Exception {
        haveOwnNpc();
        when(voiceClient.usable()).thenReturn(true);
        when(voiceClient.enroll(SAMPLE)).thenReturn("v-1");
        when(voiceClient.isReady("v-1")).thenReturn(false);   // 还在部署

        AjaxResult r = controller.npcVoiceEnroll(body(SAMPLE));

        assertEquals(200, r.get(AjaxResult.CODE_TAG));
        assertEquals(1, r.get("voiceStatus"));
        ArgumentCaptor<NpcProfile> cap = ArgumentCaptor.forClass(NpcProfile.class);
        verify(npcProfileMapper, times(2)).updateMerchantNpcVoice(cap.capture());
        // 没就绪就写「可用」的话,失败会推迟到第一次说话,长得像网络抖动
        assertEquals(Integer.valueOf(1), cap.getAllValues().get(1).getVoiceStatus());
    }

    @Test
    void enrollRecordsUnknownWhenUpstreamResponseIsUnconfirmed() throws Exception {
        haveOwnNpc();
        when(voiceClient.usable()).thenReturn(true);
        when(voiceClient.enroll(SAMPLE)).thenThrow(new AiServiceException("录音太短"));
        AjaxResult r = controller.npcVoiceEnroll(body(SAMPLE));
        assertEquals(200, r.get(AjaxResult.CODE_TAG));
        assertEquals("UNKNOWN", r.get("voiceIntentState"));
        assertEquals("VOICE_PROVIDER_OUTCOME_UNKNOWN", r.get("voiceReasonCode"));
        ArgumentCaptor<NpcProfile> cap = ArgumentCaptor.forClass(NpcProfile.class);
        verify(npcProfileMapper).updateMerchantNpcVoice(cap.capture());
        assertEquals(Integer.valueOf(1), cap.getValue().getVoiceStatus());
        assertNull(cap.getValue().getVoiceId());
        assertEquals(r.get("voiceIntentId"), controller.npcVoiceEnroll(body(SAMPLE)).get("voiceIntentId"));
        verify(voiceClient, times(1)).enroll(SAMPLE);
    }

    @Test
    void trustedNotSentRetainsKnownFailedStatusWithoutAnotherAttempt() {
        haveOwnNpc();
        when(voiceClient.usable()).thenReturn(true);
        when(voiceClient.enroll(SAMPLE)).thenThrow(new AiServiceException("local admission", new com.chengyinhub.business.service.ai.AiProviderNotSentException()));
        AjaxResult r = controller.npcVoiceEnroll(body(SAMPLE));
        assertEquals("NOT_SENT", r.get("voiceIntentState"));
        ArgumentCaptor<NpcProfile> cap = ArgumentCaptor.forClass(NpcProfile.class);
        verify(npcProfileMapper, times(2)).updateMerchantNpcVoice(cap.capture());
        assertEquals(Integer.valueOf(3), cap.getAllValues().get(1).getVoiceStatus());
        assertNull(cap.getAllValues().get(1).getVoiceId(), "明确未派发没有新的音色 id");
        assertEquals(r.get("voiceIntentId"), controller.npcVoiceEnroll(body(SAMPLE)).get("voiceIntentId"));
        verify(voiceClient, times(1)).enroll(SAMPLE);
    }

    // ===== 入参 =====

    @Test
    void enrollRequiresExplicitVoiceConsent() {
        haveOwnNpc();
        when(voiceClient.usable()).thenReturn(true);
        NpcProfile request = new NpcProfile();
        request.setVoiceSample(SAMPLE);

        AjaxResult r = controller.npcVoiceEnroll(request);

        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        verify(npcProfileMapper, never()).updateMerchantNpcVoice(any());
        verify(voiceClient, never()).enroll(anyString());
    }

    @Test
    void enrollRejectsBadSample() {
        haveOwnNpc();
        when(voiceClient.usable()).thenReturn(true);
        for (String bad : new String[]{null, "  ", "javascript:x", "file:///etc/passwd"}) {
            assertEquals(500, controller.npcVoiceEnroll(body(bad)).get(AjaxResult.CODE_TAG),
                    "应拒绝:" + bad);
        }
        verify(npcProfileMapper, never()).updateMerchantNpcVoice(any());
    }

    @Test
    void enrollRequiresProfileFirst() {
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null))
                .thenReturn(new ArrayList<NpcProfile>());
        when(voiceClient.usable()).thenReturn(true);

        AjaxResult r = controller.npcVoiceEnroll(body(SAMPLE));
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
    }

    // ===== 越权 =====

    @Test
    void enrollRefusesRowOwnedByAnotherMerchant() throws Exception {
        NpcProfile other = new NpcProfile();
        other.setId(999L); other.setScopeType(2);
        other.setScopeId(MERCHANT_ID + 1);
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null))
                .thenReturn(new ArrayList<>(Arrays.asList(other)));
        when(voiceClient.usable()).thenReturn(true);

        AjaxResult r = controller.npcVoiceEnroll(body(SAMPLE));

        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        verify(npcProfileMapper, never()).updateMerchantNpcVoice(any());
        verify(voiceClient, never()).enroll(anyString());
    }

    // ===== 查状态 =====

    @Test
    void statusPromotesPendingToReadyWhenUpstreamSaysSo() throws Exception {
        NpcProfile mine = new NpcProfile();
        mine.setId(NPC_ID); mine.setScopeType(2);
        mine.setScopeId(MERCHANT_ID);
        mine.setVoiceId("v-1");
        mine.setVoiceStatus(1);
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null))
                .thenReturn(new ArrayList<>(Arrays.asList(mine)));
        when(voiceClient.usable()).thenReturn(true);
        when(voiceClient.isReady("v-1")).thenReturn(true);

        AjaxResult r = controller.npcVoiceStatus();

        assertEquals(2, r.get("voiceStatus"));
        verify(npcProfileMapper).updateMerchantNpcVoice(any());
    }

    @Test
    void statusKeepsPendingWhenUpstreamIsDown() throws Exception {
        NpcProfile mine = new NpcProfile();
        mine.setId(NPC_ID); mine.setScopeType(2);
        mine.setScopeId(MERCHANT_ID);
        mine.setVoiceId("v-1");
        mine.setVoiceStatus(1);
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null))
                .thenReturn(new ArrayList<>(Arrays.asList(mine)));
        when(voiceClient.usable()).thenReturn(true);
        when(voiceClient.isReady("v-1")).thenThrow(new AiServiceException("上游超时"));

        AjaxResult r = controller.npcVoiceStatus();

        // 查不到 ≠ 失败。把「问不到」判成「复刻失败」会让商家白白重录一遍
        assertEquals(1, r.get("voiceStatus"));
        verify(npcProfileMapper, never()).updateMerchantNpcVoice(any());
    }

    @Test
    void statusReturnsNoneWhenNoProfile() {
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null))
                .thenReturn(new ArrayList<NpcProfile>());
        assertEquals(0, controller.npcVoiceStatus().get("voiceStatus"));
    }

    // ===== 清除 =====

    @Test
    void resetClearsVoiceId() {
        haveOwnNpc();
        controller.npcVoiceReset();
        ArgumentCaptor<NpcProfile> cap = ArgumentCaptor.forClass(NpcProfile.class);
        verify(npcProfileMapper).updateMerchantNpcVoice(cap.capture());
        assertNull(cap.getValue().getVoiceId(), "清除必须真的把音色 id 置空");
        assertEquals(Integer.valueOf(0), cap.getValue().getVoiceStatus());
    }

    private static NpcProfile body(String sample) {
        NpcProfile p = new NpcProfile();
        p.setVoiceSample(sample);
        p.setVoiceConsent(true);
        return p;
    }

    private static class TestableController extends ApiMerchantController {
        private final Long memberId;
        private TestableController(Long memberId) { this.memberId = memberId; }
        @Override public Long getAppUserId() { return memberId; }
    }
}
