package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.service.ai.*;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Supplier calls are mocked; real locking/generation semantics live in W2VoiceRecoveryMyBatisIT. */
class ApiMerchantNpcVoiceTest {
    private static final String SAMPLE = "https://cdn.example.com/voice/sample.mp3";
    private NpcProfileMapper mapper;
    private VoiceCloneClient client;
    private ApiMerchantController controller;
    private NpcProfile mine;
    private NpcProfile saved;
    private IMerchantAccessService access;

    @BeforeEach void setup() {
        mapper = mock(NpcProfileMapper.class); client = mock(VoiceCloneClient.class);
        access = mock(IMerchantAccessService.class);
        when(access.require(201L, MerchantPermission.PROFILE_WRITE)).thenReturn(
                new MerchantAccessContext(201L, 11L, 201L, MerchantRoleCode.MERCHANT_OWNER));
        controller = new ApiMerchantController() { @Override public Long getAppUserId() { return 201L; } };
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        ReflectionTestUtils.setField(controller, "voiceTransactionManager", tm);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "npcProfileMapper", mapper);
        ReflectionTestUtils.setField(controller, "voiceCloneClient", client);
        mine = new NpcProfile(); mine.setId(42L); mine.setScopeId(11L); mine.setVoiceStatus(0); mine.setVoiceGeneration(0L);
        when(mapper.selectMerchantNpcList(11L, null)).thenAnswer(c -> mine == null ? Collections.emptyList() : Collections.singletonList(copy(mine)));
        when(mapper.selectMerchantVoiceForUpdate(42L, 11L)).thenAnswer(c -> copy(mine));
        when(mapper.selectMerchantVoiceRequest(eq(11L), anyString())).thenAnswer(c -> saved == null ? null : copy(saved));
        when(mapper.claimMerchantNpcVoice(any())).thenReturn(1);
        when(mapper.insertMerchantVoiceRequest(any())).thenAnswer(c -> { saved = copy(c.getArgument(0)); mine = copy(saved); return 1; });
        when(mapper.finishMerchantVoiceRequest(any())).thenAnswer(c -> { saved = copy(c.getArgument(0)); return 1; });
        when(mapper.updateMerchantNpcVoice(any())).thenAnswer(c -> { mine = copy(c.getArgument(0)); return 1; });
        when(mapper.resetMerchantNpcVoice(any())).thenReturn(1);
        when(client.usable()).thenReturn(true);
    }
    static NpcProfile body(String sample) { NpcProfile p = new NpcProfile(); p.setVoiceSample(sample); p.setVoiceConsent(true); p.setVoiceRequestId("original"); return p; }
    private static NpcProfile copy(NpcProfile p) {
        if (p == null) return null;
        NpcProfile n = new NpcProfile(); n.setId(p.getId()); n.setScopeId(p.getScopeId()); n.setVoiceStatus(p.getVoiceStatus());
        n.setVoiceGeneration(p.getVoiceGeneration()); n.setVoiceRequestId(p.getVoiceRequestId());
        n.setVoiceRequestMemberId(p.getVoiceRequestMemberId()); n.setVoiceSample(p.getVoiceSample()); n.setVoiceId(p.getVoiceId()); return n;
    }
    @Test void noPersistedClaimMeansNoUpstreamEnrollment() throws Exception {
        when(mapper.claimMerchantNpcVoice(any())).thenReturn(0);
        assertEquals(500, controller.npcVoiceEnroll(body(SAMPLE)).get("code")); verify(client, never()).enroll(anyString());
    }
    @Test void retryOfSameCompletedSampleDoesNotEnrollAgain() throws Exception {
        mine.setVoiceStatus(2); mine.setVoiceSample(SAMPLE);
        assertEquals(2, controller.npcVoiceEnroll(body(SAMPLE)).get("voiceStatus")); verify(client, never()).enroll(anyString());
    }
    @Test void enrollRefusesWhenServiceNotConfigured() throws Exception {
        when(client.usable()).thenReturn(false);
        assertEquals(500, controller.npcVoiceEnroll(body(SAMPLE)).get("code")); verify(mapper, never()).claimMerchantNpcVoice(any()); verify(client, never()).enroll(anyString());
    }
    @Test void claimCommitsBeforeProviderAndVendorIdIsSavedBeforeReadiness() throws Exception {
        when(client.enroll(SAMPLE)).thenAnswer(c -> { assertNotNull(saved); assertEquals(4, saved.getVoiceStatus()); return "v-1"; });
        when(client.isReady("v-1")).thenAnswer(c -> { assertEquals("v-1", saved.getVoiceId()); assertEquals(1, saved.getVoiceStatus()); return true; });
        AjaxResult result = controller.npcVoiceEnroll(body(SAMPLE));
        assertEquals(2, result.get("voiceStatus")); assertEquals("original", result.get("voiceRequestId"));
        verify(access).require(201L, MerchantPermission.PROFILE_WRITE);
    }
    @Test void enrollKeepsPendingWhenVoiceNotDeployedYet() throws Exception {
        when(client.enroll(SAMPLE)).thenReturn("v-1");
        assertEquals(1, controller.npcVoiceEnroll(body(SAMPLE)).get("voiceStatus"));
    }
    @Test void ambiguousSupplierFailureRetainsUnknownAndNeverRetries() throws Exception {
        when(client.enroll(SAMPLE)).thenThrow(new AiServiceException("录音太短或响应丢失"));
        assertEquals(4, controller.npcVoiceEnroll(body(SAMPLE)).get("voiceStatus"));
        assertEquals(4, controller.npcVoiceEnroll(body(SAMPLE)).get("voiceStatus"));
        assertEquals(4, controller.npcVoiceStatus(body(SAMPLE)).get("voiceStatus"));
        assertEquals(SAMPLE, saved.getVoiceSample()); verify(client, times(1)).enroll(SAMPLE); verify(client, never()).isReady(anyString());
    }
    @Test void missingVendorIdIsUnknown() throws Exception {
        when(client.enroll(SAMPLE)).thenReturn(" ");
        assertEquals(4, controller.npcVoiceEnroll(body(SAMPLE)).get("voiceStatus")); verify(client, never()).isReady(anyString());
    }
    @Test void enrollRequiresExplicitVoiceConsent() throws Exception {
        NpcProfile p = body(SAMPLE); p.setVoiceConsent(null);
        assertEquals(500, controller.npcVoiceEnroll(p).get("code")); verify(client, never()).enroll(anyString());
    }
    @Test void enrollRejectsBadSampleAndRequestId() {
        for (String sample : new String[]{null, " ", "javascript:x", "file:///etc/passwd", String.join("", Collections.nCopies(256, "a"))})
            assertEquals(500, controller.npcVoiceEnroll(body(sample)).get("code"));
        NpcProfile p = body(SAMPLE); p.setVoiceRequestId("invalid id"); assertEquals(500, controller.npcVoiceEnroll(p).get("code"));
        verify(mapper, never()).claimMerchantNpcVoice(any());
    }
    @Test void enrollRequiresProfileFirst() { mine = null; assertEquals(500, controller.npcVoiceEnroll(body(SAMPLE)).get("code")); }
    @Test void enrollRefusesRowOwnedByAnotherMerchant() throws Exception {
        mine.setScopeId(12L); assertEquals(500, controller.npcVoiceEnroll(body(SAMPLE)).get("code")); verify(client, never()).enroll(anyString());
    }
    @Test void statusPromotesLegacyPendingUsingOnlyPersistedVendorId() throws Exception {
        mine.setVoiceStatus(1); mine.setVoiceId("v-1"); when(client.isReady("v-1")).thenReturn(true);
        assertEquals(2, controller.npcVoiceStatus().get("voiceStatus")); verify(client).isReady("v-1"); verify(client, never()).enroll(anyString());
    }
    @Test void statusKeepsPendingWhenUpstreamIsDown() throws Exception {
        mine.setVoiceStatus(1); mine.setVoiceId("v-1"); when(client.isReady("v-1")).thenThrow(new AiServiceException("timeout"));
        assertEquals(1, controller.npcVoiceStatus().get("voiceStatus")); verify(mapper, never()).updateMerchantNpcVoice(any());
    }
    @Test void legacyPendingWithoutVendorIdIsUnknown() throws Exception {
        mine.setVoiceStatus(1); assertEquals(4, controller.npcVoiceStatus().get("voiceStatus")); verify(client, never()).isReady(anyString());
    }
    @Test void statusReturnsNoneWhenNoProfile() { mine = null; assertEquals(0, controller.npcVoiceStatus().get("voiceStatus")); }
    @Test void resetUsesGenerationAndOwnership() {
        assertEquals(200, controller.npcVoiceReset().get("code"));
        verify(mapper).resetMerchantNpcVoice(argThat(p -> p.getVoiceGeneration() == 0 && p.getScopeId() == 11));
        verify(mapper, never()).updateMerchantNpcVoice(any());
    }
    @Test void statusRouteAcceptsOriginalJsonAndLegacyEmptyBody() throws Exception {
        org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/merchant/npc/voice/status")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"voiceRequestId\":\"missing-original\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(500));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/merchant/npc/voice/status"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.voiceStatus").value(0));
    }
    @Test void missingSecondReadRemainsUnknownWithOriginalMetadata() throws Exception {
        when(client.enroll(SAMPLE)).thenReturn("v-1");
        when(mapper.selectMerchantVoiceRequest(11L,"original")).thenReturn(null);
        AjaxResult r = controller.npcVoiceEnroll(body(SAMPLE));
        assertEquals(4,r.get("voiceStatus")); assertEquals("original",r.get("voiceRequestId")); assertEquals(SAMPLE,r.get("voiceSample"));
    }
    @Test void corruptSecondReadCannotMasqueradeAsUnconfigured() throws Exception {
        when(client.enroll(SAMPLE)).thenReturn("v-1");
        NpcProfile corrupt = body(SAMPLE); corrupt.setVoiceStatus(0);
        when(mapper.selectMerchantVoiceRequest(11L,"original")).thenReturn(null, corrupt);
        AjaxResult result = controller.npcVoiceEnroll(body(SAMPLE));
        assertEquals(4,result.get("voiceStatus")); assertEquals("original",result.get("voiceRequestId"));
    }
    @Test void reusedIdWithChangedPayloadOrActorDoesNotReachSupplier() throws Exception {
        saved = body(SAMPLE); saved.setVoiceRequestMemberId(201L); saved.setVoiceStatus(4);
        assertEquals(500, controller.npcVoiceEnroll(body(SAMPLE + "?changed")).get("code"));
        saved.setVoiceRequestMemberId(202L);
        assertEquals(500, controller.npcVoiceEnroll(body(SAMPLE)).get("code"));
        assertEquals(500, controller.npcVoiceStatus(body(SAMPLE)).get("code")); verify(client, never()).enroll(anyString());
    }
}
