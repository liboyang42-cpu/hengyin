package com.chengyinhub.business.service.channel.media;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.channel.media.ImMediaPolicy.Kind;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;

import static org.junit.jupiter.api.Assertions.*;

class ImMediaPolicyTest
{
    static byte[] ftyp(String brand)
    {
        byte[] b = new byte[32];
        b[3] = 0x20;
        System.arraycopy("ftyp".getBytes(StandardCharsets.ISO_8859_1), 0, b, 4, 4);
        System.arraycopy(brand.getBytes(StandardCharsets.ISO_8859_1), 0, b, 8, 4);
        return b;
    }

    static String code(Runnable r)
    {
        try { r.run(); return null; }
        catch (ChannelRejection e) { return e.getStatus() + " " + e.getErrorCode(); }
    }

    @Test void filesAreRecognisedByContentNotByName()
    {
        assertEquals(new ImMediaPolicy.Detected("pdf", "application/pdf"), ImMediaPolicy.detect(Kind.FILE, "报告.pdf", "%PDF-1.7 x".getBytes()));
        assertEquals("docx", ImMediaPolicy.detect(Kind.FILE, "a.DOCX", new byte[] { 0x50, 0x4b, 0x03, 0x04, 1 }).ext());
        assertEquals("xls", ImMediaPolicy.detect(Kind.FILE, "a.xls", new byte[] { (byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1 }).ext());
        assertEquals("text/plain", ImMediaPolicy.detect(Kind.FILE, "notes.txt", "你好\nworld".getBytes(StandardCharsets.UTF_8)).mime());
        assertEquals("415 MEDIA_TYPE_MISMATCH", code(() -> ImMediaPolicy.detect(Kind.FILE, "fake.pdf", "MZ....".getBytes())));
        assertEquals("415 MEDIA_TYPE_MISMATCH", code(() -> ImMediaPolicy.detect(Kind.FILE, "bin.txt", new byte[] { 'a', 0, 'b' })));
        assertEquals("415 MEDIA_TYPE_UNSUPPORTED", code(() -> ImMediaPolicy.detect(Kind.FILE, "run.exe", "MZ".getBytes())));
        assertEquals("400 MEDIA_NAME_INVALID", code(() -> ImMediaPolicy.detect(Kind.FILE, "../a.pdf", "%PDF-".getBytes())));
        assertEquals("400 MEDIA_NAME_INVALID", code(() -> ImMediaPolicy.detect(Kind.FILE, " a.pdf", "%PDF-".getBytes())));
        assertEquals("400 MEDIA_EMPTY", code(() -> ImMediaPolicy.detect(Kind.FILE, "a.pdf", new byte[0])));
    }

    @Test void sizeLimitsPerKind()
    {
        byte[] big = new byte[(int) Kind.VOICE.maxBytes + 1];
        System.arraycopy(ftyp("M4A "), 0, big, 0, 32);
        assertEquals("413 MEDIA_TOO_LARGE", code(() -> ImMediaPolicy.detect(Kind.VOICE, "voice.m4a", big)));
        assertEquals(10L * 1024 * 1024, Kind.FILE.maxBytes);
        assertEquals(10L * 1024 * 1024, Kind.VIDEO.maxBytes);
    }

    @Test void videoAndVoiceNeedIsoContainers()
    {
        assertEquals(new ImMediaPolicy.Detected("mp4", "video/mp4"), ImMediaPolicy.detect(Kind.VIDEO, "video.mov", ftyp("isom")));
        assertEquals(new ImMediaPolicy.Detected("mov", "video/quicktime"), ImMediaPolicy.detect(Kind.VIDEO, "video.mov", ftyp("qt  ")));
        assertEquals("audio/mp4", ImMediaPolicy.detect(Kind.VOICE, "voice.m4a", ftyp("M4A ")).mime());
        assertEquals("415 MEDIA_TYPE_MISMATCH", code(() -> ImMediaPolicy.detect(Kind.VOICE, "voice.m4a", ftyp("qt  "))));
        assertEquals("415 MEDIA_TYPE_MISMATCH", code(() -> ImMediaPolicy.detect(Kind.VIDEO, "video.mov", "RIFF....AVI ".getBytes())));
    }

    @Test void identifiersAreStrict()
    {
        assertEquals("400 INVALID_FIELD", code(() -> ImMediaPolicy.requestId("8E3F0C8A-1111-4222-8333-944455556666")));
        assertNotNull(ImMediaPolicy.requestId("8e3f0c8a-1111-4222-8333-944455556666"));
        assertEquals("400 INVALID_FIELD", code(() -> ImMediaPolicy.hash("abc")));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ImMediaPolicy.sha256("abc".getBytes()));
        assertEquals(Kind.FILE, Kind.ofMsgType(12));
        assertEquals(Kind.VIDEO, Kind.ofMsgType(11));
        assertEquals(Kind.VOICE, Kind.ofMsgType(10));
        assertNull(Kind.ofMsgType(2));
    }
}
