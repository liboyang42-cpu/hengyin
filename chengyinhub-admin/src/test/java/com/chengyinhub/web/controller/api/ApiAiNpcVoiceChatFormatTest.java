package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ai.SpeechToTextService;
import com.chengyinhub.business.service.impl.NpcChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class ApiAiNpcVoiceChatFormatTest
{
    private MockMvc mvc;
    private SpeechToTextService speechToTextService;
    private NpcChatService npcChatService;

    @BeforeEach
    void setUp()
    {
        ApiAiNpcController controller = spy(new ApiAiNpcController());
        doReturn(1001L).when(controller).getAppUserId();

        NpcFeatureFlags featureFlags = mock(NpcFeatureFlags.class);
        when(featureFlags.shopChatOn()).thenReturn(true);

        speechToTextService = mock(SpeechToTextService.class);
        when(speechToTextService.available()).thenReturn(true);
        when(speechToTextService.transcribe(any(byte[].class), anyString())).thenReturn("你好");

        CmsTopicNode node = new CmsTopicNode();
        node.setId(21L);
        node.setTopicId(31L);
        node.setMerchantMemberId(41L);
        ICmsTopicNodeService nodeService = mock(ICmsTopicNodeService.class);
        when(nodeService.selectCmsTopicNodeById(21L)).thenReturn(node);

        PlayTeamMapper playTeamMapper = mock(PlayTeamMapper.class);
        when(playTeamMapper.hasCurrentNpcRegistration(1001L, 31L)).thenReturn(1);

        npcChatService = mock(NpcChatService.class);
        NpcChatResp response = new NpcChatResp();
        response.setRequestId("voice-request-1");
        response.setOutcomeStatus("SUCCEEDED");
        response.setSafetyDecision("PASS");
        response.setSafeText("欢迎来店里");
        when(npcChatService.chatWithShop(anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(response);

        ReflectionTestUtils.setField(controller, "npcFeatureFlags", featureFlags);
        ReflectionTestUtils.setField(controller, "speechToTextService", speechToTextService);
        com.chengyinhub.business.service.impl.NpcAsrTestFixture.wireController(controller,speechToTextService);
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", nodeService);
        ReflectionTestUtils.setField(controller, "playTeamMapper", playTeamMapper);
        ReflectionTestUtils.setField(controller, "npcChatService", npcChatService);
        ReflectionTestUtils.setField(controller, "cmsMemberTemplateService", mock(ICmsMemberTemplateService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void appleM4aIsDetectedFromBytesAndPassedToAsr() throws Exception
    {
        MockMultipartFile file = new MockMultipartFile(
                "file", "misleading.mp3", "audio/mpeg", realAacLcM4aBytes());

        mvc.perform(multipart("/api/ai/npc/voice-chat")
                        .file(file)
                        .param("nodeId", "21")
                        .param("requestId", "voice-request-1"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.asr").value("你好"))
                .andExpect(jsonPath("$.data.requestId").value("voice-request-1"))
                .andExpect(jsonPath("$.data.outcomeStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.safetyDecision").value("PASS"))
                .andExpect(jsonPath("$.data.safeText").value("欢迎来店里"));

        verify(speechToTextService).transcribe(eq(realAacLcM4aBytes()), eq("m4a"));
        verify(npcChatService).chatWithShop(
                eq("voice-request-1"), eq(1001L), eq(21L), eq(41L), anyString(), eq("你好"));
    }

    @Test
    void appleCoreAudioAacLcM4aIsAccepted() throws Exception
    {
        byte[] m4a = appleCoreAudioM4aBytes();

        mvc.perform(multipart("/api/ai/npc/voice-chat")
                        .file(new MockMultipartFile("file", "recording.m4a", "audio/mp4", m4a))
                        .param("nodeId", "21")
                        .param("requestId", "voice-request-1"))
                .andExpect(jsonPath("$.code").value(200));

        verify(speechToTextService).transcribe(eq(m4a), eq("m4a"));
    }

    @Test
    void appleAacAdtsIsDetectedFromBytesAndPassedToAsr() throws Exception
    {
        byte[] aac = new byte[] {
                (byte) 0xFF, (byte) 0xF1, 0x60, (byte) 0x80, 0x01, 0x1F, (byte) 0xFC, 0x00
        };
        MockMultipartFile file = new MockMultipartFile(
                "file", "misleading.bin", "application/octet-stream", aac);

        mvc.perform(multipart("/api/ai/npc/voice-chat")
                        .file(file)
                        .param("nodeId", "21")
                        .param("requestId", "voice-request-1"))
                .andExpect(jsonPath("$.code").value(200));

        verify(speechToTextService).transcribe(eq(aac), eq("aac"));
    }

    @Test
    void pcmWavIsDetectedFromRiffChunksAndPassedToAsr() throws Exception
    {
        byte[] wav = wavBytes();
        MockMultipartFile file = new MockMultipartFile("file", "voice.wav", "audio/wav", wav);

        mvc.perform(multipart("/api/ai/npc/voice-chat")
                        .file(file)
                        .param("nodeId", "21")
                        .param("requestId", "voice-request-1"))
                .andExpect(jsonPath("$.code").value(200));

        verify(speechToTextService).transcribe(eq(wav), eq("wav"));
    }

    @Test
    void mp3FrameIsStillAcceptedWithoutTrustingMetadata() throws Exception
    {
        byte[] mp3 = mp3Bytes();
        MockMultipartFile file = new MockMultipartFile("file", "voice.txt", "text/plain", mp3);

        mvc.perform(multipart("/api/ai/npc/voice-chat")
                        .file(file)
                        .param("nodeId", "21")
                        .param("requestId", "voice-request-1"))
                .andExpect(jsonPath("$.code").value(200));

        verify(speechToTextService).transcribe(eq(mp3), eq("mp3"));
    }

    @Test
    void disguisedOrUnsupportedContentFailsClosedBeforeAsr() throws Exception
    {
        assertRejected(new MockMultipartFile(
                "file", "voice.m4a", "audio/mp4", "<html>not audio</html>".getBytes(StandardCharsets.UTF_8)));
        assertRejected(new MockMultipartFile(
                "file", "voice.mp3", "audio/mpeg", new byte[] { 'O', 'g', 'g', 'S', 0, 0, 0, 0 }));
        assertRejected(new MockMultipartFile(
                "file", "voice.m4a", "audio/mp4", box("ftyp", concat(ascii("M4A "), new byte[4]))));

        verify(speechToTextService, never()).transcribe(any(byte[].class), anyString());
        verify(npcChatService, never()).chatWithShop(
                anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void malformedAacFrameFailsClosedBeforeAsr() throws Exception
    {
        byte[] truncated = new byte[] {
                (byte) 0xFF, (byte) 0xF1, 0x60, (byte) 0x80, 0x20, 0x1F, (byte) 0xFC
        };
        assertRejected(new MockMultipartFile("file", "voice.aac", "audio/aac", truncated));

        verify(speechToTextService, never()).transcribe(any(byte[].class), anyString());
    }

    @Test
    void fakeM4aWithoutAacTrackFailsClosedBeforeAsr() throws Exception
    {
        assertRejected(new MockMultipartFile("file", "voice.m4a", "audio/mp4", fakeM4aBytes()));

        verify(speechToTextService, never()).transcribe(any(byte[].class), anyString());
    }

    @Test
    void truncatedMpegHeaderFailsClosedBeforeAsr() throws Exception
    {
        assertRejected(new MockMultipartFile("file", "voice.mp3", "audio/mpeg",
                new byte[] { (byte) 0xFF, (byte) 0xFB, 0x40, 0x00 }));

        verify(speechToTextService, never()).transcribe(any(byte[].class), anyString());
    }

    @Test
    void mpegLayerTwoFramesAreNotMislabelledAsMp3() throws Exception
    {
        byte[] layerTwo = mpegLayerTwoBytes();
        assertRejected(new MockMultipartFile("file", "voice.mp3", "audio/mpeg", layerTwo));

        verify(speechToTextService, never()).transcribe(any(byte[].class), anyString());
    }

    @Test
    void m4aWithNonAacLcDecoderConfigFailsClosed() throws Exception
    {
        byte[] nonLc = realAacLcM4aBytes();
        int audioSpecificConfig = indexOf(nonLc, new byte[] {
                0x05, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x05, 0x14, 0x08
        });
        org.junit.jupiter.api.Assertions.assertTrue(audioSpecificConfig >= 0, "fixture 必须含 AudioSpecificConfig");
        nonLc[audioSpecificConfig + 5] = 0x2C; // audioObjectType=5，不再是 AAC-LC(2)

        assertRejected(new MockMultipartFile("file", "voice.m4a", "audio/mp4", nonLc));

        verify(speechToTextService, never()).transcribe(any(byte[].class), anyString());
    }

    @Test
    void m4aWithNonAacObjectTypeFailsClosed() throws Exception
    {
        byte[] nonAac = realAacLcM4aBytes();
        int decoderConfig = indexOf(nonAac, new byte[] {
                0x04, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x17, 0x40, 0x15
        });
        org.junit.jupiter.api.Assertions.assertTrue(decoderConfig >= 0, "fixture 必须含 DecoderConfigDescriptor");
        nonAac[decoderConfig + 5] = 0x69; // MPEG-2 Audio，不是 MPEG-4 AAC

        assertRejected(new MockMultipartFile("file", "voice.m4a", "audio/mp4", nonAac));

        verify(speechToTextService, never()).transcribe(any(byte[].class), anyString());
    }

    @Test
    void m4aWithContradictoryChannelMetadataFailsClosed() throws Exception
    {
        byte[] mismatched = realAacLcM4aBytes();
        int mp4aType = indexOf(mismatched, ascii("mp4a"));
        org.junit.jupiter.api.Assertions.assertTrue(mp4aType >= 0, "fixture 必须含 mp4a sample entry");
        mismatched[mp4aType + 20] = 0;
        mismatched[mp4aType + 21] = 2; // sample entry=stereo，ASC=mono，且没有 Apple sbtd(I16) 标记

        assertRejected(new MockMultipartFile("file", "voice.m4a", "audio/mp4", mismatched));

        verify(speechToTextService, never()).transcribe(any(byte[].class), anyString());
    }

    @Test
    void aacReservedSampleRateOrTrailingGarbageFailsClosed() throws Exception
    {
        byte[] reservedRate = new byte[] {
                (byte) 0xFF, (byte) 0xF1, 0x74, (byte) 0x80, 0x01, 0x1F, (byte) 0xFC, 0x00
        };
        byte[] trailingGarbage = new byte[] {
                (byte) 0xFF, (byte) 0xF1, 0x60, (byte) 0x80, 0x01, 0x1F, (byte) 0xFC, 0x00, 0x00
        };
        assertRejected(new MockMultipartFile("file", "voice.aac", "audio/aac", reservedRate));
        assertRejected(new MockMultipartFile("file", "voice.aac", "audio/aac", trailingGarbage));

        verify(speechToTextService, never()).transcribe(any(byte[].class), anyString());
    }

    @Test
    void recordingOverTwoMegabytesIsRejectedBeforeAsr() throws Exception
    {
        byte[] oversized = new byte[2 * 1024 * 1024 + 1];
        oversized[0] = (byte) 0xFF;
        oversized[1] = (byte) 0xFB;
        oversized[2] = 0x40;
        MockMultipartFile file = new MockMultipartFile("file", "voice.mp3", "audio/mpeg", oversized);

        mvc.perform(multipart("/api/ai/npc/voice-chat")
                        .file(file)
                        .param("nodeId", "21")
                        .param("requestId", "voice-request-1"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("这段录音太长了，说短一点"));

        verify(speechToTextService, never()).transcribe(any(byte[].class), anyString());
    }

    @Test
    void asrFailureIsNotReportedAsPlayerMishearing() throws Exception
    {
        when(speechToTextService.transcribe(any(byte[].class), anyString()))
                .thenThrow(new IllegalStateException("asr down"));

        mvc.perform(multipart("/api/ai/npc/voice-chat")
                        .file(new MockMultipartFile("file", "voice.mp3", "audio/mpeg", mp3Bytes()))
                        .param("nodeId", "21")
                        .param("requestId", "voice-request-1"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("语音识别没成功，稍后再试"))
                .andExpect(jsonPath("$.data.outcomeStatus").value("PROCESSING"))
                .andExpect(jsonPath("$.data.errorCode").value("REQUEST_OUTCOME_UNKNOWN"))
                .andExpect(jsonPath("$.data.asrState").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.asrReason").value("ASR_OUTCOME_UNKNOWN"));

        verify(npcChatService, never()).chatWithShop(
                anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
    }

    private void assertRejected(MockMultipartFile file) throws Exception
    {
        mvc.perform(multipart("/api/ai/npc/voice-chat")
                        .file(file)
                        .param("nodeId", "21")
                        .param("requestId", "voice-request-1"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("录音格式不支持"));
    }

    private static byte[] realAacLcM4aBytes()
    {
        // ffmpeg 生成并由 ffprobe 确认：AAC-LC / 16000 Hz / mono；不是只拼几个文件头字节。
        return Base64.getDecoder().decode(
                "AAAAHGZ0eXBNNEEgAAACAE00QSBpc29taXNvMgAAAwdtb292AAAAbG12aGQAAAAAAAAAAAAAAAAAAAPoAAAAZAABAAABAAAA" +
                "AAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAC" +
                "AAACMXRyYWsAAABcdGtoZAAAAAMAAAAAAAAAAAAAAAEAAAAAAAAAZAAAAAAAAAAAAAAAAQEAAAAAAQAAAAAAAAAAAAAAAAAA" +
                "AAEAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAACRlZHRzAAAAHGVsc3QAAAAAAAAAAQAAAGQAAAQAAAEAAAAAAaltZGlh" +
                "AAAAIG1kaGQAAAAAAAAAAAAAAAAAAD6AAAAKQFXEAAAAAAAtaGRscgAAAAAAAAAAc291bgAAAAAAAAAAAAAAAFNvdW5kSGFu" +
                "ZGxlcgAAAAFUbWluZgAAABBzbWhkAAAAAAAAAAAAAAAkZGluZgAAABxkcmVmAAAAAAAAAAEAAAAMdXJsIAAAAAEAAAEYc3Ri" +
                "bAAAAGpzdHNkAAAAAAAAAAEAAABabXA0YQAAAAAAAAABAAAAAAAAAAAAAQAQAAAAAD6AAAAAAAA2ZXNkcwAAAAADgICAJQAB" +
                "AASAgIAXQBUAAAAAAF3AAAAFhgWAgIAFFAhW5QAGgICAAQIAAAAgc3R0cwAAAAAAAAACAAAAAgAABAAAAAABAAACQAAAABxz" +
                "dHNjAAAAAAAAAAEAAAABAAAAAwAAAAEAAAAgc3RzegAAAAAAAAAAAAAAAwAAABUAAAAEAAAABAAAABRzdGNvAAAAAAAAAAEA" +
                "AAMzAAAAGnNncGQBAAAAcm9sbAAAAAIAAAAB//8AAAAcc2JncAAAAAByb2xsAAAAAQAAAAMAAAABAAAAYnVkdGEAAABabWV0" +
                "YQAAAAAAAAAhaGRscgAAAAAAAAAAbWRpcmFwcGwAAAAAAAAAAAAAAAAtaWxzdAAAACWpdG9vAAAAHWRhdGEAAAABAAAAAExh" +
                "dmY2Mi4xMi4xMDEAAAAIZnJlZQAAACVtZGF03gIATGF2YzYyLjI4LjEwMQACMEAOARggBwEYIAc=");
    }

    private static byte[] appleCoreAudioM4aBytes() throws IOException
    {
        // macOS /usr/bin/afconvert(CoreAudio) 生成，ffprobe 确认 AAC-LC / 16000 Hz / mono。
        byte[] compressed = Base64.getDecoder().decode(
                "H4sICB3poWoAA25wYy12b2ljZS1hcHBsZS1hZmNvbnZlcnQubTRhAO2XMU/CQBTHXwHFwUSGDgwMDSFuEtCGMBlkcyAx0VETC3cNDb226R0kOjH4JTTxmzg4mLg6+BkcHB2dfHctUkBNdHG5X+763r137fu/u6kAUHHFRdSzDyxApGWRvevxkAHkuywMJxj22WRIZP7l8fpWToD9KUAJwFBjzsJidd2BH8nhC88idkbon4qRqlmc15x9rbRa4ld1jYgRz0HHYuSbvhKqQ+LHswUPx8GyYGObeYErJXGWfCmlRpJ4hcTUzWjaHMe+lfqvXPR9tBMuOMnscfEGnJU+coksqQ/Zo5xw6eSn02kVbQGt2TGhLIPnAGf3axjJmRvraAw8WNjifaHKHDZb+CxzIXimSkENVIxqBpl4mlO2hrnLTJeFL6bJxSCcv69Ev4+JkB29MSpmnS2cLd5H7ESRv3C4D57PBdq7HUQqY9RRNzAIWV3upnXvZBxQ2YUZOIzKnIwc94666F4RR1VTZ2g1UhKnbadOs934TCladmNp758dvO4bN6YUNBqNRqPRaDQajeY/KTP8PYInKGbnB9T2Bt0QEAAA");
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed)))
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    private static byte[] fakeM4aBytes() throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(box("ftyp", concat(ascii("M4A "), new byte[4], ascii("M4A "), ascii("isom"))));
        out.write(box("moov", new byte[0]));
        out.write(box("mdat", new byte[] { 1, 2, 3, 4 }));
        return out.toByteArray();
    }

    private static byte[] box(String type, byte[] payload) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int size = 8 + payload.length;
        out.write(new byte[] {
                (byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size
        });
        out.write(ascii(type));
        out.write(payload);
        return out.toByteArray();
    }

    private static byte[] wavBytes() throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii("RIFF"));
        writeLe32(out, 38);
        out.write(ascii("WAVE"));
        out.write(ascii("fmt "));
        writeLe32(out, 16);
        writeLe16(out, 1);
        writeLe16(out, 1);
        writeLe32(out, 16000);
        writeLe32(out, 32000);
        writeLe16(out, 2);
        writeLe16(out, 16);
        out.write(ascii("data"));
        writeLe32(out, 2);
        out.write(new byte[] { 0, 0 });
        return out.toByteArray();
    }

    private static byte[] mp3Bytes()
    {
        int frameSize = 144 * 56000 / 44100;
        byte[] bytes = new byte[frameSize * 2];
        for (int offset = 0; offset < bytes.length; offset += frameSize)
        {
            bytes[offset] = (byte) 0xFF;
            bytes[offset + 1] = (byte) 0xFB;
            bytes[offset + 2] = 0x40;
            bytes[offset + 3] = 0x00;
        }
        return bytes;
    }

    private static byte[] mpegLayerTwoBytes()
    {
        int frameSize = 144 * 128000 / 44100;
        byte[] bytes = new byte[frameSize * 2];
        for (int offset = 0; offset < bytes.length; offset += frameSize)
        {
            bytes[offset] = (byte) 0xFF;
            bytes[offset + 1] = (byte) 0xFD;
            bytes[offset + 2] = (byte) 0x80;
            bytes[offset + 3] = 0x00;
        }
        return bytes;
    }

    private static int indexOf(byte[] bytes, byte[] needle)
    {
        for (int offset = 0; offset + needle.length <= bytes.length; offset++)
        {
            boolean matches = true;
            for (int i = 0; i < needle.length; i++)
            {
                if (bytes[offset + i] != needle[i])
                {
                    matches = false;
                    break;
                }
            }
            if (matches) return offset;
        }
        return -1;
    }

    private static void writeLe16(ByteArrayOutputStream out, int value)
    {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeLe32(ByteArrayOutputStream out, int value)
    {
        writeLe16(out, value & 0xFFFF);
        writeLe16(out, (value >>> 16) & 0xFFFF);
    }

    private static byte[] concat(byte[]... parts) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part);
        return out.toByteArray();
    }

    private static byte[] ascii(String value)
    {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
