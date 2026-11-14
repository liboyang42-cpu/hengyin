package com.chengyinhub.business.merchantgap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.common.exception.ServiceException;

/** One test per store-NPC route: voice/script, voice/revoke, avatar/status, avatar/generate, knowledge-review, knowledge-review/request. */
class MerchantNpcGapServiceTest {
    static final long OWNER = 9002L, STORE = 8001L, PLAYER = 9004L;
    static final String RID = "5B8F1D2A-0C3E-4F6A-9B7C-1D2E3F4A5B6C";

    MerchantGapH2 db;
    IMerchantAccessService access;
    NpcProfileMapper profiles;
    IAuditTaskService audit;

    @BeforeEach
    void setUp() throws Exception {
        db = MerchantGapH2.create();
        access = mock(IMerchantAccessService.class);
        when(access.require(eq(OWNER), anyString())).thenReturn(new MerchantAccessContext(OWNER, STORE, OWNER, MerchantRoleCode.MERCHANT_OWNER));
        when(access.require(eq(PLAYER), anyString())).thenThrow(new ServiceException("商家身份无效或权限不足", 403));
        profiles = mock(NpcProfileMapper.class);
        audit = mock(IAuditTaskService.class);
        when(audit.submit(anyString(), any(), any(), anyString(), anyString())).thenReturn(77L);
    }

    MerchantNpcGapService service(MerchantAvatarProvider provider) {
        return new MerchantNpcGapService(access, profiles, audit, db.jdbc, db.transactions, provider);
    }

    NpcProfile profile(int voiceStatus) {
        NpcProfile p = new NpcProfile();
        p.setId(3L);
        p.setScopeId(STORE);
        p.setVoiceStatus(voiceStatus);
        p.setVoiceVersion(4L);
        return p;
    }

    @Test
    void voiceScriptIsFiveLinesWithConsentFirstAndOnlyForTheStore() {
        Map<String, Object> out = service(null).voiceScript(OWNER);
        assertEquals(true, out.get("available"));
        assertEquals(5, ((List<?>) out.get("script")).size());
        assertEquals(0, out.get("consentIndex"));
        MerchantGapException denied = assertThrows(MerchantGapException.class, () -> service(null).voiceScript(PLAYER));
        assertEquals(403, denied.getStatus());
    }

    @Test
    void voiceRevokeClearsOnceAndRepeatsAsNoChange() {
        when(profiles.selectMerchantNpcList(STORE, null)).thenReturn(List.of(profile(2)), List.of(profile(0)));
        when(profiles.updateMerchantNpcVoice(any())).thenReturn(1);
        Map<String, Object> body = Map.of("requestId", RID);
        Map<String, Object> first = service(null).voiceRevoke(OWNER, body);
        assertEquals(true, first.get("changed"));
        assertEquals(0, first.get("voiceStatus"));
        Map<String, Object> again = service(null).voiceRevoke(OWNER, body);
        assertEquals(false, again.get("changed"));
        verify(profiles, times(1)).updateMerchantNpcVoice(any());
        assertThrows(MerchantGapException.class, () -> service(null).voiceRevoke(OWNER, Map.of("requestId", "x")));
        assertThrows(MerchantGapException.class, () -> service(null).voiceRevoke(OWNER, Map.of("requestId", RID, "extra", 1)));
    }

    @Test
    void avatarStatusWithoutProviderIsUnavailableWithNoJob() {
        Map<String, Object> out = service(null).avatarStatus(OWNER);
        assertEquals(false, out.get("available"));
        assertEquals(List.of(), out.get("styles"));
        assertNull(out.get("job"));
    }

    @Test
    void avatarGenerateRefusedWithoutProviderAndRecordedWithOne() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("imageUrl", "https://img.example.test/a.jpg");
        body.put("style", "CARTOON");
        MerchantGapException closed = assertThrows(MerchantGapException.class, () -> service(null).avatarGenerate(OWNER, body));
        assertEquals(503, closed.getStatus());
        assertEquals("AVATAR_PROVIDER_NOT_CONFIGURED", closed.getErrorCode());

        when(profiles.selectMerchantNpcList(STORE, null)).thenReturn(List.of(profile(0)));
        MerchantNpcGapService live = service(new E2eTestMerchantAvatarProvider());
        Map<String, Object> job = live.avatarGenerate(OWNER, body);
        assertEquals("SUCCEEDED", job.get("status"));
        assertEquals("CARTOON", job.get("style"));
        assertEquals("https://img.example.test/a.jpg", job.get("modelUrl"));
        Map<String, Object> status = live.avatarStatus(OWNER);
        assertEquals(true, status.get("available"));
        assertEquals(job.get("jobId"), ((Map<?, ?>) status.get("job")).get("jobId"));

        body.put("imageUrl", "http://img.example.test/a.jpg");
        assertEquals(400, assertThrows(MerchantGapException.class, () -> live.avatarGenerate(OWNER, body)).getStatus());
        body.put("imageUrl", "https://img.example.test/a.jpg");
        body.put("style", "OIL");
        assertEquals(400, assertThrows(MerchantGapException.class, () -> live.avatarGenerate(OWNER, body)).getStatus());
    }

    Map<String, Object> knowledge(String requestId, String persona) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("name", "阿猫");
        body.put("greeting", "欢迎光临");
        body.put("persona", persona);
        body.put("knowledge", "营业到晚上九点");
        return body;
    }

    @Test
    void knowledgeReviewStoresOnceSubmitsAuditAndReplaysOrConflicts() {
        MerchantNpcGapService svc = service(null);
        Map<String, Object> first = svc.submitKnowledge(OWNER, knowledge(RID, "热情"));
        assertEquals(RID, first.get("requestId"));
        assertEquals(OWNER, first.get("memberId"));
        assertEquals("热情", first.get("persona"));
        assertEquals("PENDING_REVIEW", first.get("status"));
        assertEquals(77L, first.get("auditTaskId"));
        assertFalse(first.containsKey("errorCode"));
        Map<String, Object> replay = svc.submitKnowledge(OWNER, knowledge(RID, "热情"));
        assertEquals(first, replay);
        verify(audit, times(1)).submit(eq(MerchantNpcGapService.KNOWLEDGE_AUDIT), anyLong(), eq(OWNER), anyString(), anyString());
        MerchantGapException conflict = assertThrows(MerchantGapException.class, () -> svc.submitKnowledge(OWNER, knowledge(RID, "冷淡")));
        assertEquals(409, conflict.getStatus());
        assertEquals(400, assertThrows(MerchantGapException.class, () -> svc.submitKnowledge(OWNER, knowledge(RID, " "))).getStatus());
    }

    @Test
    void knowledgeRequestReadsTheSameFourFieldsOrIsNotFound() {
        MerchantNpcGapService svc = service(null);
        svc.submitKnowledge(OWNER, knowledge(RID, "热情"));
        Map<String, Object> read = svc.knowledgeRequest(OWNER, RID);
        assertEquals("阿猫", read.get("name"));
        assertEquals("欢迎光临", read.get("greeting"));
        assertEquals("营业到晚上九点", read.get("knowledge"));
        MerchantGapException missing = assertThrows(MerchantGapException.class,
                () -> svc.knowledgeRequest(OWNER, "00000000-0000-4000-8000-000000000000"));
        assertEquals(404, missing.getStatus());
        assertTrue(assertThrows(MerchantGapException.class, () -> svc.knowledgeRequest(PLAYER, RID)).getStatus() == 403);
        verify(audit, never()).review(anyLong(), anyLong(), any());
    }

    @Test
    void permissionIsProfileWrite() {
        service(null).voiceScript(OWNER);
        verify(access).require(OWNER, MerchantPermission.PROFILE_WRITE);
    }
}
