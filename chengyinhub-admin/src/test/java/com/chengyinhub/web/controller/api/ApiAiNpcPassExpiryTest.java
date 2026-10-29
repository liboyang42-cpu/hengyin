package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.vo.ai.NpcChatDataExportVO;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ai.SpeechToTextService;
import com.chengyinhub.business.service.impl.NpcChatDataService;
import com.chengyinhub.business.service.impl.NpcChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Controller boundaries only; expiry SQL and renewal rows are exercised separately against MySQL. */
class ApiAiNpcPassExpiryTest {
    private MockMvc mvc;
    private PlayTeamMapper registrations;
    private SpeechToTextService speech;
    private NpcChatService chat;
    private NpcChatDataService history;

    @BeforeEach
    void setUp() {
        ApiAiNpcController controller = spy(new ApiAiNpcController());
        doReturn(1001L).when(controller).getAppUserId();
        NpcFeatureFlags flags = mock(NpcFeatureFlags.class);
        when(flags.shopChatOn()).thenReturn(true);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(21L);
        node.setTopicId(31L);
        node.setMerchantMemberId(41L);
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        when(nodes.selectCmsTopicNodeById(21L)).thenReturn(node);
        registrations = mock(PlayTeamMapper.class);
        // A paid but expired pass must not fall back to this shared, expiry-agnostic query.
        when(registrations.hasPaidRegistration(1001L, 1, 31L)).thenReturn(1);
        speech = mock(SpeechToTextService.class);
        when(speech.available()).thenReturn(true);
        when(speech.transcribe(any(byte[].class), eq("aac"))).thenReturn("你好");
        chat = mock(NpcChatService.class);
        NpcChatResp response = new NpcChatResp();
        response.setSafeText("欢迎来店里");
        when(chat.chatWithShop(anyString(), eq(1001L), eq(21L), eq(41L), anyString(), eq("你好")))
                .thenReturn(response);
        history = mock(NpcChatDataService.class);
        when(history.exportForUser(1001L)).thenReturn(new NpcChatDataExportVO());
        ReflectionTestUtils.setField(controller, "npcFeatureFlags", flags);
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", nodes);
        ReflectionTestUtils.setField(controller, "playTeamMapper", registrations);
        ReflectionTestUtils.setField(controller, "speechToTextService", speech);
        com.chengyinhub.business.service.impl.NpcAsrTestFixture.wireController(controller,speech);
        ReflectionTestUtils.setField(controller, "npcChatService", chat);
        ReflectionTestUtils.setField(controller, "npcChatDataService", history);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void expiredPassBlocksTextBeforeGenerating() throws Exception {
        text(403);
        verify(registrations).hasCurrentNpcRegistration(1001L, 31L);
        verify(registrations, never()).hasPaidRegistration(anyLong(), anyInt(), anyLong());
        verify(chat, never()).chatWithShop(anyString(),anyLong(),anyLong(),anyLong(),anyString(),anyString());
    }

    @Test
    void expiredPassBlocksVoiceBeforeTranscribing() throws Exception {
        voice(403);
        verify(speech, never()).transcribe(any(byte[].class), anyString());
        verify(chat, never()).chatWithShop(anyString(),anyLong(),anyLong(),anyLong(),anyString(),anyString());
    }

    @Test
    void passExpiringDuringAsrBlocksModelAfterTranscription() throws Exception {
        when(registrations.hasCurrentNpcRegistration(1001L, 31L)).thenReturn(1, 0);
        voice(403);
        InOrder order = inOrder(registrations, speech);
        order.verify(registrations).hasCurrentNpcRegistration(1001L, 31L);
        order.verify(speech).transcribe(any(byte[].class), eq("aac"));
        order.verify(registrations).hasCurrentNpcRegistration(1001L, 31L);
        verify(chat, never()).chatWithShop(anyString(),anyLong(),anyLong(),anyLong(),anyString(),anyString());
    }

    @Test
    void renewedCurrentPassRestoresTextEntry() throws Exception {
        text(403);
        when(registrations.hasCurrentNpcRegistration(1001L, 31L)).thenReturn(1);
        text(200);
        verify(chat).chatWithShop(eq("text-1"), eq(1001L), eq(21L), eq(41L), anyString(), eq("你好"));
    }

    @Test
    void renewedCurrentPassRestoresVoiceThroughAsrAndChat() throws Exception {
        voice(403);
        clearInvocations(registrations, speech);
        when(registrations.hasCurrentNpcRegistration(1001L, 31L)).thenReturn(1);
        voice(200);
        InOrder order = inOrder(registrations, speech, chat);
        order.verify(registrations).hasCurrentNpcRegistration(1001L, 31L);
        order.verify(speech).transcribe(any(byte[].class), eq("aac"));
        order.verify(registrations).hasCurrentNpcRegistration(1001L, 31L);
        order.verify(chat).chatWithShop(eq("voice-1"), eq(1001L), eq(21L), eq(41L), anyString(), eq("你好"));
    }

    @Test
    void expiredPassDoesNotBlockHistoryExport() throws Exception {
        text(403);
        clearInvocations(registrations,chat);
        mvc.perform(get("/api/ai/npc/chat/data/export"))
                .andExpect(jsonPath("$.code").value(200));
        verify(history).exportForUser(1001L);
        verifyNoInteractions(registrations, chat);
    }

    @Test
    void expiredPassKeepsOriginalTextAnswerRecoveryWithoutNewGeneration() throws Exception {
        NpcChatResp stored=new NpcChatResp();stored.setSafeText("原答案");stored.setOutcomeStatus("SUCCEEDED");
        when(chat.storedShopRequest("old-request",1001L,21L)).thenReturn(stored);
        mvc.perform(post("/api/ai/npc/shop-chat").contentType("application/json")
                .content("{\"nodeId\":21,\"requestId\":\"old-request\",\"message\":\"同原请求\"}"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.safeText").value("原答案"));
        verifyNoInteractions(registrations,speech);
        verify(chat,never()).chatWithShop(anyString(),anyLong(),anyLong(),anyLong(),anyString(),anyString());
    }

    @Test
    void voiceOriginalAnswerRecoveryDoesNotRepeatAsrAfterExpiry() throws Exception {
        NpcChatResp stored=new NpcChatResp();stored.setSafeText("原语音答案");stored.setOutcomeStatus("SUCCEEDED");
        when(chat.storedShopRequest("old-voice",1001L,21L)).thenReturn(stored);
        mvc.perform(multipart("/api/ai/npc/voice-chat")
                .file(new MockMultipartFile("file","old.aac","audio/aac",new byte[]{1}))
                .param("nodeId","21").param("requestId","old-voice"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.safeText").value("原语音答案"));
        verifyNoInteractions(registrations,speech);
        verify(chat,never()).chatWithShop(anyString(),anyLong(),anyLong(),anyLong(),anyString(),anyString());
    }

    private void text(int code) throws Exception {
        ResultActions result = mvc.perform(post("/api/ai/npc/shop-chat").contentType("application/json")
                        .content("{\"nodeId\":21,\"requestId\":\"text-1\",\"message\":\"你好\"}"))
                .andExpect(jsonPath("$.code").value(code));
        if (code == 200) result.andExpect(jsonPath("$.data.safeText").value("欢迎来店里"));
    }

    private void voice(int code) throws Exception {
        // The same complete ADTS frame shape accepted by the public audio-format tests.
        byte[] aac = {(byte) 0xFF, (byte) 0xF1, 0x60, (byte) 0x80, 0x01, 0x1F, (byte) 0xFC, 0};
        ResultActions result = mvc.perform(multipart("/api/ai/npc/voice-chat")
                        .file(new MockMultipartFile("file", "voice.aac", "audio/aac", aac))
                        .param("nodeId", "21").param("requestId", "voice-1"))
                .andExpect(jsonPath("$.code").value(code));
        if (code == 200) result.andExpect(jsonPath("$.asr").value("你好"))
                .andExpect(jsonPath("$.data.safeText").value("欢迎来店里"));
    }
}
