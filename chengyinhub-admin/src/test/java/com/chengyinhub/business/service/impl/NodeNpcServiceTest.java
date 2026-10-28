package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.service.IChapterMerchantNodeService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.VoiceCloneClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 节点 NPC 的业务合同(CR-927)。
 *
 * 守四件事:
 *   ① 编辑权只走 {@link IChapterMerchantNodeService} 的两条闸 —— 读 detail/voiceStatus 用不判冻结的
 *      requireOwnNode,写 save/enroll/reset 用 requireOwnLiveNode,本类不自己重复实现归属/承接闸;
 *   ② 文本同步送检拦在落库前,头像(非预设)落库后异步送检,且 bizType 必须是 NODE_NPC 不是商家资料;
 *   ③ 一节点一条:第二次保存是 update 同一条;下架过的行回到待审(audit_status=0 + enabled=0),
 *      过审前玩家读不到 —— 不写回 enabled=1 这条路;
 *   ④ 声音状态机:先落原身份/PENDING 再打上游,UNKNOWN 保留原任务,仅可信 NOT_SENT 可为 FAILED —— 与门店分身同一套语义。
 */
class NodeNpcServiceTest {

    private static final Long ME = 88L;
    private static final Long NODE = 31L;
    private static final String OPENID = "o_openid";

    private IChapterMerchantNodeService nodeGate;
    private NpcProfileMapper mapper;
    private IWxContentSecurityService wxSec;
    private VoiceCloneClient voice;
    private NodeNpcService service;
    private static final String CANONICAL_SAMPLE = "media-asset:41";
    private com.chengyinhub.business.service.ai.NpcVoiceEnrollmentFixture enrollment;


    private static final String REGISTRY_AUDIO_KEY = "upload/0123456789abcdef0123456789abcdef.mp3";
    private static final String REGISTRY_AUDIO_REF = "media-asset:73";
    private void actualAudioRegistry(String reviewState, Long uploaderMemberId) {
        com.chengyinhub.business.mapper.AppMediaAssetMapper assets = mock(com.chengyinhub.business.mapper.AppMediaAssetMapper.class);
        java.util.Map<String,Object> fact = java.util.Map.of("id",73L,"memberId",uploaderMemberId,"ownerType","APP",
                "objectKey",REGISTRY_AUDIO_KEY,"mediaType","AUDIO","reviewState",reviewState);
        when(assets.select(73L)).thenReturn(fact);
        when(assets.selectByObjectKey(REGISTRY_AUDIO_KEY)).thenReturn(fact);
        com.chengyinhub.business.service.support.AppMediaReferenceGuard actual = new com.chengyinhub.business.service.support.AppMediaReferenceGuard();
        org.springframework.test.util.ReflectionTestUtils.setField(actual,"mapper",assets);
        org.springframework.test.util.ReflectionTestUtils.setField(actual,"assets",mock(com.chengyinhub.business.service.impl.AppMediaAssetService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"mediaReferences",actual);
    }

    @Test void longSignedAliasesOfApprovedSampleReplayPendingAndReadyWithoutAnyProviderOrWrite() {
        actualAudioRegistry("APPROVED", ME);
        for (int state : new int[]{1,2}) {
            NpcProfile row = existing(555L, NODE, 1);
            row.setVoiceSample(REGISTRY_AUDIO_REF); row.setVoiceStatus(state);
            when(mapper.selectNodeNpcByNodeId(NODE)).thenReturn(row);
            for (String signature : new String[]{"x", "y"}) {
                String signed = "https://approved.example/"+REGISTRY_AUDIO_KEY+"?signature="+signature.repeat(320);
                assertEquals(state, service.voiceEnroll(ME, ME, NODE, signed).getVoiceStatus());
            }
        }
        org.mockito.Mockito.verifyNoInteractions(voice);
        verify(mapper,never()).updateNodeNpcVoice(any());
    }

    @Test void heldSampleCannotUseExistingVoiceStateAsAnApprovalBypass() {
        actualAudioRegistry("HOLD", ME);
        NpcProfile row = existing(555L, NODE, 1);
        row.setVoiceSample(REGISTRY_AUDIO_REF); row.setVoiceStatus(2);
        when(mapper.selectNodeNpcByNodeId(NODE)).thenReturn(row);
        org.junit.jupiter.api.Assertions.assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.voiceEnroll(ME, ME, NODE, REGISTRY_AUDIO_REF));
        org.mockito.Mockito.verifyNoInteractions(voice);
        verify(mapper,never()).updateNodeNpcVoice(any());
    }

    @Test void newApprovedSampleStoresCanonicalReferenceAndSignsOnlyOnceBeforeMockProviderCall() {
        actualAudioRegistry("APPROVED", ME);
        NpcProfile row = existing(555L, NODE, 1);
        when(mapper.selectNodeNpcByNodeId(NODE)).thenReturn(row);
        when(mapper.updateNodeNpcVoice(any())).thenReturn(1);
        when(voice.usable()).thenReturn(true);
        when(voice.enroll("provider-short-sample")).thenReturn("test-voice-id");
        when(voice.isReady("test-voice-id")).thenReturn(false);
        try (org.mockito.MockedStatic<com.chengyinhub.common.utils.file.FileOssUploadUtils> signatures =
                     org.mockito.Mockito.mockStatic(com.chengyinhub.common.utils.file.FileOssUploadUtils.class)) {
            signatures.when(() -> com.chengyinhub.common.utils.file.FileOssUploadUtils.generateShortLivedPresignedUrl(REGISTRY_AUDIO_KEY,300))
                    .thenReturn("provider-short-sample");
            service.voiceEnroll(ME, ME, NODE, "https://approved.example/"+REGISTRY_AUDIO_KEY+"?signature="+"x".repeat(320));
            signatures.verify(() -> com.chengyinhub.common.utils.file.FileOssUploadUtils.generateShortLivedPresignedUrl(REGISTRY_AUDIO_KEY,300),org.mockito.Mockito.times(1));
        }
        verify(voice,org.mockito.Mockito.times(1)).enroll("provider-short-sample");
        ArgumentCaptor<NpcProfile> writes = ArgumentCaptor.forClass(NpcProfile.class);
        verify(mapper,org.mockito.Mockito.times(2)).updateNodeNpcVoice(writes.capture());
        assertEquals(REGISTRY_AUDIO_REF,writes.getAllValues().get(0).getVoiceSample());
    }

    @Test
    void managerCannotEnrollOwnersApprovedAudioThroughOwnerNodeScope() {
        Long manager = 99L;
        actualAudioRegistry("APPROVED", ME);
        when(mapper.selectNodeNpcByNodeId(NODE)).thenReturn(existing(555L, NODE, 1));
        when(voice.usable()).thenReturn(true);
        com.chengyinhub.business.service.ConsentGuard consent =
                (com.chengyinhub.business.service.ConsentGuard)
                        org.springframework.test.util.ReflectionTestUtils.getField(service, "consentGuard");

        com.chengyinhub.common.exception.ServiceException denied = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> service.voiceEnroll(manager, ME, NODE, REGISTRY_AUDIO_REF));

        assertEquals(403, denied.getCode());
        verify(nodeGate).requireOwnLiveNode(ME, NODE);
        verify(consent).requireCurrentAgreement(manager, "voice_clone_processing", "merchant_voice");
        verify(consent, never()).requireCurrentAgreement(ME, "voice_clone_processing", "merchant_voice");
        verify(voice, never()).enroll(anyString());
        verify(voice, never()).isReady(anyString());
        verify(mapper, never()).updateNodeNpcVoice(any());
    }

    @Test
    void managerOwnAudioUsesManagerConsentButKeepsOwnerNodeScope() {
        Long manager = 99L;
        actualAudioRegistry("APPROVED", manager);
        when(mapper.selectNodeNpcByNodeId(NODE)).thenReturn(existing(555L, NODE, 1));
        when(mapper.updateNodeNpcVoice(any())).thenReturn(1);
        when(voice.usable()).thenReturn(true);
        when(voice.enroll("manager-private-sample")).thenReturn("manager-voice");
        when(voice.isReady("manager-voice")).thenReturn(true);
        com.chengyinhub.business.service.ConsentGuard consent =
                (com.chengyinhub.business.service.ConsentGuard)
                        org.springframework.test.util.ReflectionTestUtils.getField(service, "consentGuard");

        try (org.mockito.MockedStatic<com.chengyinhub.common.utils.file.FileOssUploadUtils> signatures =
                     org.mockito.Mockito.mockStatic(com.chengyinhub.common.utils.file.FileOssUploadUtils.class)) {
            signatures.when(() -> com.chengyinhub.common.utils.file.FileOssUploadUtils
                    .generateShortLivedPresignedUrl(REGISTRY_AUDIO_KEY, 300))
                    .thenReturn("manager-private-sample");
            assertEquals(NodeNpcService.VOICE_READY,
                    service.voiceEnroll(manager, ME, NODE, REGISTRY_AUDIO_REF).getVoiceStatus());
            signatures.verify(() -> com.chengyinhub.common.utils.file.FileOssUploadUtils
                    .generateShortLivedPresignedUrl(REGISTRY_AUDIO_KEY, 300));
        }
        verify(nodeGate).requireOwnLiveNode(ME, NODE);
        verify(nodeGate, never()).requireOwnLiveNode(manager, NODE);
        verify(consent).requireCurrentAgreement(manager, "voice_clone_processing", "merchant_voice");
        verify(consent, never()).requireCurrentAgreement(ME, "voice_clone_processing", "merchant_voice");
        verify(voice).enroll("manager-private-sample");
    }

    @Test
    void managerCannotUseOwnersConsentForOwnAudio() {
        Long manager = 99L;
        actualAudioRegistry("APPROVED", manager);
        com.chengyinhub.business.service.ConsentGuard consent =
                (com.chengyinhub.business.service.ConsentGuard)
                        org.springframework.test.util.ReflectionTestUtils.getField(service, "consentGuard");
        org.mockito.Mockito.doThrow(new com.chengyinhub.common.exception.ServiceException("请先单独同意", 403))
                .when(consent).requireCurrentAgreement(manager, "voice_clone_processing", "merchant_voice");

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.voiceEnroll(manager, ME, NODE, REGISTRY_AUDIO_REF));

        verify(nodeGate).requireOwnLiveNode(ME, NODE);
        verify(consent, never()).requireCurrentAgreement(ME, "voice_clone_processing", "merchant_voice");
        verifyNoInteractions(voice, mapper);
    }

    @BeforeEach
    void setUp() {
        nodeGate = mock(IChapterMerchantNodeService.class);
        mapper = mock(NpcProfileMapper.class);
        wxSec = mock(IWxContentSecurityService.class);
        voice = mock(VoiceCloneClient.class);
        service = new NodeNpcService(nodeGate, mapper, wxSec, voice);
        com.chengyinhub.business.service.support.AppMediaReferenceGuard media = mock(com.chengyinhub.business.service.support.AppMediaReferenceGuard.class);
        when(media.requireAttachment(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(true)))
                .thenAnswer(call -> ((String)call.getArgument(1)).startsWith("media-asset:") ? call.getArgument(1) : CANONICAL_SAMPLE);
        when(media.processingUrl(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq("AUDIO"))).thenReturn("/profile/a.mp3");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "mediaReferences", media);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "consentGuard", org.mockito.Mockito.mock(com.chengyinhub.business.service.ConsentGuard.class));

        when(nodeGate.requireOwnLiveNode(ME, NODE)).thenReturn(node());
        when(mapper.selectNodeNpcByNodeId(NODE)).thenReturn(null);
        enrollment = new com.chengyinhub.business.service.ai.NpcVoiceEnrollmentFixture(
                mapper, voice, 4, NODE, () -> mapper.selectNodeNpcByNodeId(NODE));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "voiceEnrollmentService", enrollment.service);
    }

    private static CmsTopicNode node() {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(NODE);
        n.setChapterId(9L);
        n.setTopicId(100L);
        n.setMerchantMemberId(ME);
        return n;
    }

    private static NpcProfile draft(String name, String avatar, String greeting) {
        NpcProfile p = new NpcProfile();
        p.setName(name);
        p.setAvatar(avatar);
        p.setGreeting(greeting);
        return p;
    }

    private static NpcProfile existing(long id, long scopeId, int enabled) {
        NpcProfile p = new NpcProfile();
        p.setId(id);
        p.setScopeType(4);
        p.setScopeId(scopeId);
        p.setName("旧名");
        p.setAvatar("px1:p01");
        p.setGreeting("旧台词");
        p.setPersona("");
        p.setSort(0);
        p.setAuditStatus(1);
        p.setEnabled(enabled);
        return p;
    }

    // ===== 读 =====

    @Test
    void separateConsentRefusalPreventsAnyProviderCallOrMediaWrite() {
        com.chengyinhub.common.config.ComplianceProperties versions = new com.chengyinhub.common.config.ComplianceProperties();
        versions.setDocVersions(java.util.Map.of("photo_model_processing", "v1.0", "voice_clone_processing", "v1.0"));
        com.chengyinhub.business.service.ConsentGuard guard = new com.chengyinhub.business.service.ConsentGuard(
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IConsentRecordService.class), versions);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "consentGuard", guard);
        org.junit.jupiter.api.Assertions.assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.voiceEnroll(ME, ME, NODE, "/profile/voice.mp3"));
        org.mockito.Mockito.verifyNoInteractions(voice);
    }

    @Test
    @Timeout(5)
    void detail_gatesBeforeReading() {
        NpcProfile row = existing(555L, NODE, 1);
        when(mapper.selectNodeNpcByNodeId(NODE)).thenReturn(row);

        assertEquals(555L, service.detailForMerchant(ME, NODE).getId());

        // 读闸仍是实闸:归属/承接不通过必须抛 —— 拆掉的是冻结,不是门。
        when(nodeGate.requireOwnNode(ME, NODE)).thenThrow(new IllegalStateException("点位不存在或无权操作"));
        assertThrows(IllegalStateException.class, () -> service.detailForMerchant(ME, NODE));
        assertThrows(IllegalStateException.class, () -> service.voiceStatus(ME, NODE));
        verify(nodeGate, never()).requireOwnLiveNode(ME, NODE);
    }

    /**
     * ★N1:冻结只挡商家写,不挡读。主题开跑后写闸(requireOwnLiveNode)会抛,
     * 但详情回填与声音状态查询必须照常 —— 否则商家点「节点NPC」只有失败半屏,
     * 生成中的声音再没人查,状态永远卡在「复刻中」。
     */
    @Test
    @Timeout(5)
    void detailAndVoiceStatus_readDespiteFrozenEditGate() {
        when(nodeGate.requireOwnLiveNode(ME, NODE))
                .thenThrow(new IllegalStateException("主题已开始,不能再改节点内容"));
        NpcProfile row = existing(555L, NODE, 1);
        when(mapper.selectNodeNpcByNodeId(NODE)).thenReturn(row);

        assertEquals(555L, service.detailForMerchant(ME, NODE).getId(), "冻结不该拦详情回填");
        assertEquals(555L, service.voiceStatus(ME, NODE).getId(), "冻结不该拦声音状态查询");
        verify(nodeGate, never()).requireOwnLiveNode(ME, NODE);
    }

    // ===== 保存:参数与文本闸 =====

    @Test
    @Timeout(5)
    void save_rejectsMissingNameAvatarAndOverlongFields() {
        assertEquals("请填写角色名字",
                assertThrows(IllegalStateException.class,
                        () -> service.save(ME, NODE, draft("  ", "px1:p01", null), OPENID)).getMessage());

        assertEquals("名字最多 32 个字",
                assertThrows(IllegalStateException.class,
                        () -> service.save(ME, NODE, draft("x".repeat(33), "px1:p01", null), OPENID)).getMessage());

        assertEquals("请选择形象",
                assertThrows(IllegalStateException.class,
                        () -> service.save(ME, NODE, draft("阿福", " ", null), OPENID)).getMessage());

        assertEquals("形象格式不支持",
                assertThrows(IllegalStateException.class,
                        () -> service.save(ME, NODE, draft("阿福", "javascript:alert(1)", null), OPENID)).getMessage());

        assertEquals("台词最多 255 个字",
                assertThrows(IllegalStateException.class,
                        () -> service.save(ME, NODE, draft("阿福", "px1:p01", "x".repeat(256)), OPENID)).getMessage());

        verifyNoInteractions(wxSec);
        verify(mapper, never()).insertNodeNpc(any());
    }

    @Test
    @Timeout(5)
    void save_blocksWhenTextSecurityRejects() {
        when(wxSec.checkText(anyString(), eq(OPENID))).thenReturn("内容涉嫌违规，请修改");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.save(ME, NODE, draft("阿福", "px1:p01", "台词"), OPENID));
        assertEquals("内容涉嫌违规，请修改", e.getMessage());
        verify(mapper, never()).insertNodeNpc(any());
    }

    // ===== 保存:落库形态与送检 =====

    @Test
    @Timeout(5)
    void save_insertsScopedAuditedRowAndChecksUploadedAvatar() throws Exception {
        doAnswer(inv -> {
            inv.getArgument(0, NpcProfile.class).setId(555L);
            return 1;
        }).when(mapper).insertNodeNpc(any(NpcProfile.class));

        NpcProfile saved = service.save(ME, NODE,
                draft("  阿福  ", "https://oss.example.com/a.png", "  欢迎来店里  "), OPENID);

        ArgumentCaptor<NpcProfile> cap = ArgumentCaptor.forClass(NpcProfile.class);
        verify(mapper).insertNodeNpc(cap.capture());
        NpcProfile inserted = cap.getValue();
        assertEquals(Integer.valueOf(NodeNpcService.SCOPE_NODE), inserted.getScopeType(), "必须是 scope_type=4");
        assertEquals(NODE, inserted.getScopeId(), "scope_id 必须是节点 id");
        assertEquals("阿福", inserted.getName(), "名字两端空白要去掉");
        assertEquals("欢迎来店里", inserted.getGreeting());
        assertEquals(Integer.valueOf(1), inserted.getAuditStatus(), "先发后审:文本已过 msgSecCheck,落库即通过");
        assertEquals(Integer.valueOf(1), inserted.getEnabled());
        assertEquals("", inserted.getPersona(), "persona NOT NULL,店铺/节点 NPC 不用人设提示词");
        assertEquals(555L, saved.getId(), "返回体要带自增主键,前端才能继续配声音");
        verify(wxSec).submitMediaCheckOne(IWxContentSecurityService.BIZ_TYPE_NODE_NPC, 555L,
                "https://oss.example.com/a.png", OPENID);
    }

    @Test
    @Timeout(5)
    void save_presetAvatarSkipsMediaCheck() {
        doAnswer(inv -> {
            inv.getArgument(0, NpcProfile.class).setId(555L);
            return 1;
        }).when(mapper).insertNodeNpc(any(NpcProfile.class));

        service.save(ME, NODE, draft("阿福", "px1:p04", null), OPENID);

        verify(wxSec, never()).submitMediaCheckOne(anyInt(), anyLong(), anyString(), anyString());
        verify(wxSec).checkText(anyString(), eq(OPENID));
    }

    /**
     * ★N2:下架过的行重新保存 ≠ 重新上架 —— 必须回到待审(audit_status=0 + enabled=0),
     * 玩家读闸两道都不过、过审前不可见;否则换一张预设头像(不送检)再存一次就把下架勾销了。
     */
    @Test
    @Timeout(5)
    void save_secondTimeAfterTakedownGoesBackToPendingAndStaysHidden() {
        NpcProfile row = existing(555L, NODE, 0);   // 被内容安全回调下架过
        when(mapper.selectNodeNpcByNodeId(NODE)).thenReturn(row);
        when(mapper.updateNodeNpc(any(NpcProfile.class))).thenReturn(1);

        NpcProfile saved = service.save(ME, NODE,
                draft("阿福", "https://oss.example.com/b.png", "新台词"), OPENID);

        ArgumentCaptor<NpcProfile> cap = ArgumentCaptor.forClass(NpcProfile.class);
        verify(mapper).updateNodeNpc(cap.capture());
        verify(mapper, never()).insertNodeNpc(any());
        assertEquals(555L, cap.getValue().getId(), "改的必须是原来那条");
        assertEquals(Integer.valueOf(0), cap.getValue().getEnabled(), "重新保存不得重新点亮");
        assertEquals(Integer.valueOf(0), cap.getValue().getAuditStatus(), "必须回到待审");
        assertEquals("新台词", cap.getValue().getGreeting());
        assertEquals(555L, saved.getId());
        assertEquals(Integer.valueOf(0), saved.getEnabled());
        assertEquals(Integer.valueOf(0), saved.getAuditStatus());
        verify(wxSec).submitMediaCheckOne(IWxContentSecurityService.BIZ_TYPE_NODE_NPC, 555L,
                "https://oss.example.com/b.png", OPENID);
    }

    /** 在架的行正常编辑维持先发后审:不写 audit_status/enabled(文本同步送检、图片异步送检)。 */
    @Test
    @Timeout(5)
    void save_secondTimeOnLiveRowKeepsItVisible() {
        NpcProfile row = existing(555L, NODE, 1);   // 在架:audit_status=1 且 enabled=1
        when(mapper.selectNodeNpcByNodeId(NODE)).thenReturn(row);
        when(mapper.updateNodeNpc(any(NpcProfile.class))).thenReturn(1);

        NpcProfile saved = service.save(ME, NODE, draft("阿福", "px1:p04", "改一句台词"), OPENID);

        ArgumentCaptor<NpcProfile> cap = ArgumentCaptor.forClass(NpcProfile.class);
        verify(mapper).updateNodeNpc(cap.capture());
        assertNull(cap.getValue().getEnabled(), "在架编辑不写 enabled,别把冻结/下架的口子开回来");
        assertNull(cap.getValue().getAuditStatus(), "在架编辑不回待审");
        assertEquals(Integer.valueOf(1), saved.getEnabled());
        assertEquals(Integer.valueOf(1), saved.getAuditStatus());
    }

    @Test
    @Timeout(5)
    void save_rejectsRowWhoseScopeIdIsNotThisNode() {
        when(mapper.selectNodeNpcByNodeId(NODE)).thenReturn(existing(555L, 9999L, 1));
        assertEquals("无权修改该形象",
                assertThrows(IllegalStateException.class,
                        () -> service.save(ME, NODE, draft("阿福", "px1:p01", null), OPENID)).getMessage());
        verify(mapper, never()).updateNodeNpc(any());
    }

    // ===== 声音 =====

    @Test
    @Timeout(5)
    void voiceEnroll_requiresSavedNpcThenOpenService() {
        assertEquals("请先保存节点 NPC,再给他配声音",
                assertThrows(IllegalStateException.class,
                        () -> service.voiceEnroll(ME, ME, NODE, "/profile/a.mp3")).getMessage());

        when(mapper.selectNodeNpcByNodeId(NODE)).thenReturn(existing(555L, NODE, 1));
        when(voice.usable()).thenReturn(false);
        assertEquals("语音服务未开通",
                assertThrows(IllegalStateException.class,
                        () -> service.voiceEnroll(ME, ME, NODE, "/profile/a.mp3")).getMessage());
        verify(voice, never()).enroll(anyString());
    }

    @Test
    @Timeout(5)
    void voiceEnroll_writesPendingBeforeUpstreamThenReady() {
        when(mapper.selectNodeNpcByNodeId(NODE)).thenReturn(existing(555L, NODE, 1));
        when(mapper.updateNodeNpcVoice(any(NpcProfile.class))).thenReturn(1);
        when(voice.usable()).thenReturn(true);
        when(voice.enroll("/profile/a.mp3")).thenReturn("cosyvoice-v2-abc");
        when(voice.isReady("cosyvoice-v2-abc")).thenReturn(true);

        service.voiceEnroll(ME, ME, NODE, "/profile/a.mp3");

        ArgumentCaptor<NpcProfile> cap = ArgumentCaptor.forClass(NpcProfile.class);
        verify(mapper, org.mockito.Mockito.times(3)).updateNodeNpcVoice(cap.capture());
        List<NpcProfile> writes = cap.getAllValues();
        assertEquals(Integer.valueOf(NodeNpcService.VOICE_PENDING), writes.get(0).getVoiceStatus(),
                "必须先落复刻中再打上游 —— 反过来的话上游成功而落库失败 = 花了钱没记住");
        assertEquals(CANONICAL_SAMPLE, writes.get(0).getVoiceSample());
        assertEquals(Integer.valueOf(NodeNpcService.VOICE_PENDING), writes.get(1).getVoiceStatus());
        assertEquals("cosyvoice-v2-abc", writes.get(1).getVoiceId());
        assertEquals("cosyvoice-v2-abc", writes.get(2).getVoiceId());
        assertEquals(Integer.valueOf(NodeNpcService.VOICE_READY), writes.get(2).getVoiceStatus());
        InOrder order = inOrder(mapper, voice);
        order.verify(mapper).updateNodeNpcVoice(any(NpcProfile.class));
        order.verify(voice).enroll("/profile/a.mp3");
    }

    @Test
    @Timeout(5)
    void voiceEnroll_unconfirmedFailureKeepsOriginalUnknownIdentity() {
        when(mapper.selectNodeNpcByNodeId(NODE)).thenReturn(existing(555L, NODE, 1));
        when(mapper.updateNodeNpcVoice(any(NpcProfile.class))).thenReturn(1);
        when(voice.usable()).thenReturn(true);
        when(voice.enroll(anyString())).thenThrow(new AiServiceException("录音太短"));
        NpcProfile result = service.voiceEnroll(ME, ME, NODE, "/profile/a.mp3");
        assertEquals("UNKNOWN", result.getVoiceIntentState());
        assertEquals("VOICE_PROVIDER_OUTCOME_UNKNOWN", result.getVoiceReasonCode());
        ArgumentCaptor<NpcProfile> cap = ArgumentCaptor.forClass(NpcProfile.class);
        verify(mapper).updateNodeNpcVoice(cap.capture());
        assertEquals(Integer.valueOf(NodeNpcService.VOICE_PENDING), cap.getValue().getVoiceStatus());
        assertNull(cap.getValue().getVoiceId());
        assertEquals(result.getVoiceIntentId(), service.voiceEnroll(ME, ME, NODE, "/profile/a.mp3").getVoiceIntentId());
        verify(voice, org.mockito.Mockito.times(1)).enroll(anyString());
    }

    @Test
    void voiceEnroll_trustedNotSentKeepsTheKnownFailedStateWithoutReexecution() {
        when(mapper.selectNodeNpcByNodeId(NODE)).thenReturn(existing(555L, NODE, 1));
        when(mapper.updateNodeNpcVoice(any(NpcProfile.class))).thenReturn(1);
        when(voice.usable()).thenReturn(true);
        when(voice.enroll(anyString())).thenThrow(new AiServiceException("local admission", new com.chengyinhub.business.service.ai.AiProviderNotSentException()));
        NpcProfile result = service.voiceEnroll(ME, ME, NODE, "/profile/a.mp3");
        assertEquals("NOT_SENT", result.getVoiceIntentState());
        ArgumentCaptor<NpcProfile> cap = ArgumentCaptor.forClass(NpcProfile.class);
        verify(mapper, org.mockito.Mockito.times(2)).updateNodeNpcVoice(cap.capture());
        NpcProfile failed = cap.getAllValues().get(1);
        assertEquals(Integer.valueOf(NodeNpcService.VOICE_FAILED), failed.getVoiceStatus());
        assertNull(failed.getVoiceId(), "明确未派发没有新的音色 id");
        assertEquals(result.getVoiceIntentId(), service.voiceEnroll(ME, ME, NODE, "/profile/a.mp3").getVoiceIntentId());
        verify(voice, org.mockito.Mockito.times(1)).enroll(anyString());
    }

    @Test
    @Timeout(5)
    void voiceEnroll_sameSampleWhilePendingIsIdempotent() {
        NpcProfile row = existing(555L, NODE, 1);
        row.setVoiceSample(CANONICAL_SAMPLE);
        row.setVoiceStatus(NodeNpcService.VOICE_PENDING);
        when(mapper.selectNodeNpcByNodeId(NODE)).thenReturn(row);

        assertEquals(NodeNpcService.VOICE_PENDING, service.voiceEnroll(ME, ME, NODE, "/profile/a.mp3").getVoiceStatus());
        verify(voice, never()).enroll(anyString());
    }

    @Test
    @Timeout(5)
    void voiceStatus_refreshesPendingFromUpstream() {
        NpcProfile row = existing(555L, NODE, 1);
        row.setVoiceId("cosyvoice-v2-abc");
        row.setVoiceStatus(NodeNpcService.VOICE_PENDING);
        when(mapper.selectNodeNpcByNodeId(NODE)).thenReturn(row);
        when(voice.usable()).thenReturn(true);
        when(voice.isReady("cosyvoice-v2-abc")).thenReturn(true);
        when(mapper.updateNodeNpcVoice(any(NpcProfile.class))).thenReturn(1);

        assertEquals(Integer.valueOf(NodeNpcService.VOICE_READY), service.voiceStatus(ME, NODE).getVoiceStatus());

        // 上游挂了保持原状,不因为查不到就判失败
        when(mapper.selectNodeNpcByNodeId(NODE)).thenReturn(row);
        row.setVoiceStatus(NodeNpcService.VOICE_PENDING);
        when(voice.isReady("cosyvoice-v2-abc")).thenThrow(new AiServiceException("timeout"));
        assertEquals(Integer.valueOf(NodeNpcService.VOICE_PENDING), service.voiceStatus(ME, NODE).getVoiceStatus());
    }

    @Test
    @Timeout(5)
    void voiceReset_clearsVoiceIdAndStatus() {
        when(mapper.selectNodeNpcByNodeId(NODE)).thenReturn(existing(555L, NODE, 1));
        when(mapper.updateNodeNpcVoice(any(NpcProfile.class))).thenReturn(1);

        service.voiceReset(ME, NODE);

        ArgumentCaptor<NpcProfile> cap = ArgumentCaptor.forClass(NpcProfile.class);
        verify(mapper).updateNodeNpcVoice(cap.capture());
        assertNull(cap.getValue().getVoiceId());
        assertEquals(Integer.valueOf(NodeNpcService.VOICE_NONE), cap.getValue().getVoiceStatus());
    }

    /**
     * 外部动作不在业务事务中；原身份/UNKNOWN/原音色在短独立事务保存，不能随请求异常回滚。
     */
    @Test
    @Timeout(5)
    void voiceEnrollMustNotRunInTransactionOrFailureEvidenceIsRolledBack() throws Exception {
        java.lang.reflect.Method m = NodeNpcService.class.getMethod(
                "voiceEnroll", Long.class, Long.class, Long.class, String.class);
        assertNull(m.getAnnotation(org.springframework.transaction.annotation.Transactional.class),
                "voiceEnroll 不能加 @Transactional:失败状态要落库,不能随异常回滚");
    }

    /** ★并发双击的库侧唯一键报错要变成可行动的话,别让 1062 冒到全局处理器变成「系统繁忙」。 */
    @Test
    @Timeout(5)
    void save_duplicateKeyFromConcurrentTapGivesActionableError() {
        when(mapper.insertNodeNpc(any(NpcProfile.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_active_scope"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.save(ME, NODE, draft("阿福", "px1:p01", null), OPENID));
        assertEquals("保存冲突，请重试", e.getMessage());
    }

    @Test
    @Timeout(5)
    void voiceStatus_withNoNpcReturnsNull() {
        assertNull(service.voiceStatus(ME, NODE));
        assertNull(service.detailForMerchant(ME, NODE), "没配过时 detail 返回 null,前端据此显示空表单");
    }
}
