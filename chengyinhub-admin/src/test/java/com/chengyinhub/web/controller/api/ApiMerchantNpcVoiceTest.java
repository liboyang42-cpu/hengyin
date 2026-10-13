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

    private NpcProfileMapper npcProfileMapper;
    private VoiceCloneClient voiceClient;
    private ApiMerchantController controller;

    @BeforeEach
    void setUp() {
        npcProfileMapper = mock(NpcProfileMapper.class);
        when(npcProfileMapper.updateMerchantNpcVoice(any())).thenReturn(1);
        voiceClient = mock(VoiceCloneClient.class);
        controller = new TestableController(MEMBER_ID);

        IMerchantAccessService accessService = mock(IMerchantAccessService.class);
        when(accessService.require(MEMBER_ID, MerchantPermission.PROFILE_WRITE))
                .thenReturn(new MerchantAccessContext(
                        MEMBER_ID, MERCHANT_ID, MEMBER_ID, MerchantRoleCode.MERCHANT_OWNER));

        ReflectionTestUtils.setField(controller, "npcProfileMapper", npcProfileMapper);
        ReflectionTestUtils.setField(controller, "voiceCloneClient", voiceClient);
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);
    }

    @Test
    void noPersistedPendingMeansNoUpstreamEnrollment() throws Exception {
        haveOwnNpc();
        when(voiceClient.usable()).thenReturn(true);
        when(npcProfileMapper.updateMerchantNpcVoice(any())).thenReturn(0);
        assertEquals(500, controller.npcVoiceEnroll(body(SAMPLE)).get(AjaxResult.CODE_TAG));
        verify(voiceClient, never()).enroll(anyString());
    }

    @Test
    void retryOfSameCompletedSampleDoesNotEnrollAgain() throws Exception {
        NpcProfile mine = new NpcProfile();
        mine.setId(10L); mine.setScopeId(MERCHANT_ID);
        mine.setVoiceStatus(2); mine.setVoiceSample(SAMPLE);
        when(npcProfileMapper.selectMerchantNpcList(MERCHANT_ID, null))
                .thenReturn(new ArrayList<>(Arrays.asList(mine)));
        assertEquals(2, controller.npcVoiceEnroll(body(SAMPLE)).get("voiceStatus"));
        verify(voiceClient, never()).enroll(anyString());
        verify(npcProfileMapper, never()).updateMerchantNpcVoice(any());
    }

    private void haveOwnNpc() {
        NpcProfile mine = new NpcProfile();
        mine.setId(NPC_ID);
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
        verify(npcProfileMapper, times(2)).updateMerchantNpcVoice(cap.capture());
        assertEquals(Integer.valueOf(1), cap.getAllValues().get(0).getVoiceStatus());
        assertNull(cap.getAllValues().get(0).getVoiceId(), "复刻中不该有音色 id");
        assertEquals(Integer.valueOf(2), cap.getAllValues().get(1).getVoiceStatus());
        assertEquals("cosyvoice-v2-chengyin-abc", cap.getAllValues().get(1).getVoiceId());
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
    void enrollMarksFailedWhenUpstreamRejects() throws Exception {
        haveOwnNpc();
        when(voiceClient.usable()).thenReturn(true);
        when(voiceClient.enroll(SAMPLE)).thenThrow(new AiServiceException("录音太短"));

        AjaxResult r = controller.npcVoiceEnroll(body(SAMPLE));

        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("录音太短", r.get(AjaxResult.MSG_TAG));   // 上游的拒绝原因要原样带回
        ArgumentCaptor<NpcProfile> cap = ArgumentCaptor.forClass(NpcProfile.class);
        verify(npcProfileMapper, times(2)).updateMerchantNpcVoice(cap.capture());
        assertEquals(Integer.valueOf(3), cap.getAllValues().get(1).getVoiceStatus());
        assertNull(cap.getAllValues().get(1).getVoiceId(), "失败了不能留下音色 id");
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
        other.setId(999L);
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
        mine.setId(NPC_ID);
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
        mine.setId(NPC_ID);
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
