package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ai.SpeechToTextService;
import com.chengyinhub.business.service.impl.NpcChatService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Models a receipt appearing while ASR is executing, without real ASR/provider calls. */
class OWN_C04_11Test {
    private static final String ID = "00000000-0000-4000-8000-000000000011";
    private ApiAiNpcController controller;
    private NpcChatService chat;
    private SpeechToTextService speech;
    private PlayTeamMapper registration;
    private NpcChatResp stored;

    @BeforeEach void setup() {
        controller = spy(new ApiAiNpcController()); doReturn(1001L).when(controller).getAppUserId();
        NpcFeatureFlags flags = mock(NpcFeatureFlags.class); when(flags.shopChatOn()).thenReturn(true);
        chat = mock(NpcChatService.class); speech = mock(SpeechToTextService.class);
        registration = mock(PlayTeamMapper.class);
        // A post-ASR eligibility check would now fail; existing receipt recovery must come first.
        when(registration.hasCurrentNpcRegistration(1001L, 31L)).thenReturn(1, 0);
        when(speech.available()).thenReturn(true);
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        CmsTopicNode node = new CmsTopicNode(); node.setId(21L); node.setTopicId(31L); node.setMerchantMemberId(41L);
        when(nodes.selectCmsTopicNodeById(21L)).thenReturn(node);
        stored = new NpcChatResp(); stored.setRequestId(ID); stored.setOutcomeStatus("SUCCEEDED");
        stored.setSafeText("original answer");
        when(chat.storedShopRequest(ID, 1001L, 21L)).thenReturn(null, stored);
        ReflectionTestUtils.setField(controller, "npcFeatureFlags", flags);
        ReflectionTestUtils.setField(controller, "npcChatService", chat);
        ReflectionTestUtils.setField(controller, "speechToTextService", speech);
        com.chengyinhub.business.service.impl.NpcAsrTestFixture.wireController(controller,speech);
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", nodes);
        ReflectionTestUtils.setField(controller, "playTeamMapper", registration);
    }
    private MockMultipartFile file() {
        ByteBuffer wav = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(38);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        wav.putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000);
        wav.putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(2).putShort((short) 0);
        return new MockMultipartFile("file", "fixture.wav", "audio/wav", wav.array());
    }
    private void assertOriginal(AjaxResult result) {
        assertSame(stored, result.get("data"));
        assertFalse(result.containsKey("asr"), "new transcript must not be attached to an older receipt");
        verify(registration, times(1)).hasCurrentNpcRegistration(1001L, 31L);
        verify(chat, never()).chatWithShop(anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
        verify(speech, times(1)).transcribe(any(byte[].class), eq("wav"));
    }
    @Test void asrExceptionPrefersOriginalReceiptThatAppearedInFlight() {
        when(speech.transcribe(any(byte[].class), eq("wav"))).thenThrow(new IllegalStateException("fixture ASR timeout"));
        assertOriginal(controller.voiceChat(file(), 21L, ID));
    }
    @Test void emptyAsrPrefersOriginalReceiptInsteadOfAskingToResend() {
        when(speech.transcribe(any(byte[].class), eq("wav"))).thenReturn(" ");
        assertOriginal(controller.voiceChat(file(), 21L, ID));
    }
    @Test void successfulAsrPrefersProcessingReceiptBeforeCurrentEligibility() {
        stored.setOutcomeStatus("PROCESSING"); stored.setErrorCode("REQUEST_OUTCOME_UNKNOWN");
        when(speech.transcribe(any(byte[].class), eq("wav"))).thenReturn("new transcript");
        assertOriginal(controller.voiceChat(file(), 21L, ID));
    }
    @Test void noReceiptKeepsExistingFreshReplyAndAsrEcho() {
        when(chat.storedShopRequest(ID, 1001L, 21L)).thenReturn(null);
        when(registration.hasCurrentNpcRegistration(1001L, 31L)).thenReturn(1);
        when(speech.transcribe(any(byte[].class), eq("wav"))).thenReturn("new transcript");
        when(chat.chatWithShop(eq(ID), eq(1001L), eq(21L), eq(41L), anyString(), eq("new transcript")))
                .thenReturn(stored);
        AjaxResult result = controller.voiceChat(file(), 21L, ID);
        assertSame(stored, result.get("data")); assertEquals("new transcript", result.get("asr"));
        verify(chat, times(1)).chatWithShop(eq(ID), eq(1001L), eq(21L), eq(41L), anyString(), eq("new transcript"));
    }
}
