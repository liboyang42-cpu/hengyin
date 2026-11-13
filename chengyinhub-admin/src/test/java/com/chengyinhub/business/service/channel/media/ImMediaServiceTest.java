package com.chengyinhub.business.service.channel.media;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.mapper.channel.media.ImMediaMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelGovernanceMapper;
import com.chengyinhub.business.service.channel.media.ImMediaPolicy.Kind;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.common.exception.ServiceException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImMediaServiceTest
{
    static final long ME = 7, CONV = 70, ASSET = 900, UPLOAD = 55, MSG = 5000;
    static final String REQ = "8e3f0c8a-1111-4222-8333-944455556666";
    static final byte[] PDF = "%PDF-1.7 test".getBytes();
    static final String HASH = ImMediaPolicy.sha256(PDF);

    ImMediaMapper mapper;
    ImMediaStore store;
    ChannelGovernanceMapper conversations;
    ImMediaService service;
    Map<String, Object> row;

    @BeforeEach void setUp()
    {
        // 台账查询默认「无行」（Mockito 默认会给空 Map，那等于「已有原请求」）。
        mapper = mock(ImMediaMapper.class, i -> i.getMethod().getReturnType() == int.class ? 0 : null);
        store = mock(ImMediaStore.class);
        conversations = mock(ChannelGovernanceMapper.class);
        when(conversations.countConversationMember(CONV, ME)).thenReturn(1);
        service = new ImMediaService();
        ReflectionTestUtils.setField(service, "media", mapper);
        ReflectionTestUtils.setField(service, "store", store);
        ReflectionTestUtils.setField(service, "conversations", conversations);
        row = new HashMap<>(Map.of("id", UPLOAD, "kind", "FILE", "ownerMemberId", ME, "conversationId", CONV, "uploadRequestId", REQ,
                "sourceSha256", HASH, "assetId", ASSET, "fileName", "报告.pdf", "ext", "pdf", "mime", "application/pdf"));
        row.put("byteSize", (long) PDF.length);
        row.put("state", "UPLOADED");
        row.put("reviewState", "HOLD");
    }

    static String code(Runnable r)
    {
        try { r.run(); return null; }
        catch (ChannelRejection e) { return e.getStatus() + " " + e.getErrorCode(); }
        catch (ServiceException e) { return e.getCode() + " " + e.getDetailMessage(); }
    }

    @Test void uploadStoresOnceAndProjectsTheFileReceipt()
    {
        when(mapper.insertUpload(anyMap())).thenAnswer(i -> { ((Map<String, Object>) i.getArgument(0)).put("id", UPLOAD); return 1; });
        when(store.put(ME, PDF, "pdf", "application/pdf")).thenReturn(ASSET);
        when(mapper.markUploaded(UPLOAD, ASSET)).thenReturn(1);
        when(mapper.selectById(UPLOAD)).thenReturn(row);
        Map<String, Object> out = service.upload(ME, Kind.FILE, "70", REQ, HASH, "报告.pdf", PDF);
        assertEquals("original-im-file-upload-v1", out.get("source"));
        assertEquals("UPLOADED", out.get("state"));
        assertEquals("HOLD", out.get("review_state"));
        assertEquals("PENDING", out.get("scan_state"));
        assertEquals("7", out.get("owner_member_id"));
        Map<?, ?> ref = (Map<?, ?>) out.get("reference");
        assertEquals("900", ref.get("attachmentID"));
        assertEquals(REQ, ref.get("uploadRequestID"));
        assertEquals("报告.pdf", ref.get("name"));
        assertEquals(HASH, ref.get("sha256"));
        assertEquals(false, out.get("transcoded"));
    }

    @Test void uploadRejectsBeforeStoring()
    {
        assertEquals("400 SOURCE_HASH_MISMATCH", code(() -> service.upload(ME, Kind.FILE, "70", REQ, "0".repeat(64), "a.pdf", PDF)));
        assertEquals("415 MEDIA_TYPE_MISMATCH", code(() -> service.upload(ME, Kind.FILE, "70", REQ, ImMediaPolicy.sha256("MZ".getBytes()), "a.pdf", "MZ".getBytes())));
        assertEquals("404 CONVERSATION_NOT_FOUND", code(() -> service.upload(8, Kind.FILE, "70", REQ, HASH, "a.pdf", PDF)));
        verify(store, never()).put(anyLong(), any(), anyString(), anyString());
    }

    @Test void sameRequestReplaysAndDifferentBytesConflict()
    {
        when(mapper.lockByRequest(ME, "FILE", REQ)).thenReturn(row);
        assertEquals("UPLOADED", service.upload(ME, Kind.FILE, "70", REQ, HASH, "报告.pdf", PDF).get("state"));
        assertEquals("409 REQUEST_ID_CONFLICT", code(() -> service.upload(ME, Kind.FILE, "70", REQ, "a".repeat(64), "x.pdf", PDF)));
        row.put("state", "DELETED");
        assertEquals("409 MEDIA_DELETED", code(() -> service.upload(ME, Kind.FILE, "70", REQ, HASH, "报告.pdf", PDF)));
        verify(store, never()).put(anyLong(), any(), anyString(), anyString());
    }

    @Test void receiptIsNotFoundForOtherConversationsAndDeletedRows()
    {
        when(conversations.countConversationMember(71, ME)).thenReturn(1);
        when(mapper.selectByRequest(ME, "FILE", REQ)).thenReturn(row);
        assertEquals("NOT_FOUND", service.receipt(ME, Kind.FILE, "71", REQ, HASH).get("state"));
        assertNull(service.receipt(ME, Kind.FILE, "71", REQ, HASH).get("reference"));
        row.put("reviewState", "APPROVED");
        assertEquals("CLEAN", service.receipt(ME, Kind.FILE, "70", REQ, HASH).get("scan_state"));
        row.put("state", "DELETED");
        assertEquals("NOT_FOUND", service.receipt(ME, Kind.FILE, "70", REQ, HASH).get("state"));
    }

    @Test void videoAndVoiceReceiptsLeaveUnprobedFieldsNull()
    {
        row.put("kind", "VIDEO");
        Map<String, Object> video = service.receipt(Kind.VIDEO, row, ME, CONV, REQ, HASH);
        assertEquals("original-im-video-upload-v1", video.get("source"));
        assertEquals("HOLD", video.get("video_review_state"));
        assertTrue(video.containsKey("cover_review_state") && video.get("cover_review_state") == null);
        Map<?, ?> ref = (Map<?, ?>) video.get("reference");
        assertNull(ref.get("durationMS"));
        assertNull(ref.get("coverSHA256"));
        row.put("kind", "VOICE");
        row.put("state", "RESERVED");
        Map<String, Object> voice = service.receipt(Kind.VOICE, row, ME, CONV, REQ, HASH);
        assertEquals("PENDING", voice.get("state"));
        assertEquals("900", voice.get("asset_id"));
        assertNull(voice.get("duration_ms"));
        assertEquals("NOT_AVAILABLE", voice.get("asr_state"));
    }

    static String fileCard(String asset, long size)
    {
        return "{\"attachmentId\":\"" + asset + "\",\"ext\":\"pdf\",\"mime\":\"application/pdf\",\"sha256\":\"" + HASH
                + "\",\"size\":" + size + ",\"source\":\"original-im-file-v1\",\"uploadRequestId\":\"" + REQ + "\"}";
    }

    @Test void attachGateOnlyAcceptsTheApprovedUnsentUploadItDescribes()
    {
        when(mapper.lockByRequest(ME, "FILE", REQ)).thenReturn(row);
        String card = fileCard("900", PDF.length);
        assertEquals("409 MEDIA_NOT_APPROVED", code(() -> service.prepare(ME, CONV, 12, "报告.pdf", card)));
        row.put("reviewState", "REJECTED");
        assertEquals("409 MEDIA_REJECTED", code(() -> service.prepare(ME, CONV, 12, "报告.pdf", card)));
        row.put("reviewState", "APPROVED");
        assertEquals(UPLOAD, service.prepare(ME, CONV, 12, "报告.pdf", card));
        assertEquals("409 MEDIA_CARD_MISMATCH", code(() -> service.prepare(ME, CONV, 12, "other.pdf", card)));
        assertEquals("409 MEDIA_CARD_MISMATCH", code(() -> service.prepare(ME, CONV, 12, "报告.pdf", fileCard("901", PDF.length))));
        assertEquals("404 MEDIA_NOT_FOUND", code(() -> service.prepare(ME, 71, 12, "报告.pdf", card)));
        assertEquals("400 MEDIA_CARD_INVALID", code(() -> service.prepare(ME, CONV, 11, "[视频]", card)));
        row.put("messageId", 1L);
        assertEquals("409 MEDIA_ALREADY_SENT", code(() -> service.prepare(ME, CONV, 12, "报告.pdf", card)));
        assertEquals("[文件]", service.summary(12));
        assertTrue(service.handles(10) && service.handles(11) && service.handles(12) && !service.handles(2));
    }

    @Test void recipientsGetAFreshAuthorizationAndShortLivedUrlOnlyWhenApproved()
    {
        long OTHER = 8;
        when(conversations.countConversationMember(CONV, OTHER)).thenReturn(1);
        Map<String, Object> msg = new LinkedHashMap<>(Map.of("id", MSG, "conversationId", CONV, "senderId", ME, "msgType", 12,
                "content", "报告.pdf", "extraJson", fileCard("900", PDF.length), "status", 0, "checkStatus", 1));
        when(mapper.selectMessage(MSG)).thenReturn(msg);
        when(mapper.selectByMessage(MSG)).thenReturn(row);
        assertEquals("409 MEDIA_NOT_APPROVED", code(() -> service.authorization(OTHER, Kind.FILE, MSG)));
        row.put("reviewState", "APPROVED");
        Map<String, Object> auth = service.authorization(OTHER, Kind.FILE, MSG);
        assertEquals("original-im-file-authorization-v1", auth.get("source"));
        assertEquals(MSG, ((Map<?, ?>) auth.get("message")).get("id"));
        when(store.shortLivedUrl(ASSET, 300)).thenReturn("https://oss.example.invalid/upload/x.pdf?sig");
        Map<String, Object> preview = service.preview(OTHER, Kind.FILE, MSG);
        assertEquals("READY", preview.get("state"));
        assertEquals(300, preview.get("expires_in_seconds"));
        assertEquals("5000", preview.get("message_id"));
        assertEquals("https://oss.example.invalid/upload/x.pdf?sig", preview.get("url"));
        assertEquals("404 MEDIA_NOT_FOUND", code(() -> service.authorization(OTHER, Kind.VIDEO, MSG)));
        assertEquals("404 MESSAGE_NOT_FOUND", code(() -> service.authorization(9, Kind.FILE, MSG)));
        msg.put("status", 1);
        assertEquals("409 MEDIA_UNAVAILABLE", code(() -> service.authorization(OTHER, Kind.FILE, MSG)));
        Map<?, ?> withheld = (Map<?, ?>) service.message(OTHER, "5000").get("message");
        assertFalse(withheld.containsKey("content"));
    }

    @Test void deleteOnlyBeforeSend()
    {
        when(mapper.lockByRequest(ME, "FILE", REQ)).thenReturn(row);
        row.put("messageId", MSG);
        assertEquals("409 MEDIA_ATTACHED", code(() -> service.delete(ME, Kind.FILE, "70", REQ)));
        row.remove("messageId");
        when(mapper.markDeleted(UPLOAD)).thenReturn(1);
        assertEquals(true, service.delete(ME, Kind.FILE, "70", REQ).get("changed"));
        verify(store).delete(ASSET);
        row.put("state", "DELETED");
        assertEquals(false, service.delete(ME, Kind.FILE, "70", REQ).get("changed"));
        assertEquals("404 MEDIA_NOT_FOUND", code(() -> service.delete(ME, Kind.FILE, "71", REQ)));
    }

    @Test void videoPolicyMatchesTheAppContract()
    {
        Map<String, Object> policy = service.videoPolicy(ME, "70");
        assertEquals("original-im-video-policy-v1", policy.get("source"));
        assertEquals(30_000, policy.get("maximumDurationMS"));
        assertEquals("video/mp4", policy.get("outputMIME"));
        assertTrue((Long) policy.get("maximumSourceBytes") <= 64L * 1024 * 1024);
        assertEquals(List.of(), List.of());
    }
}
