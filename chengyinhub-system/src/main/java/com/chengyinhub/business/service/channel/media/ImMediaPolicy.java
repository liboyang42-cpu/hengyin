package com.chengyinhub.business.service.channel.media;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

import com.chengyinhub.business.service.channel.platform.ChannelRejection;

/**
 * IM 文件/视频/语音的类型与大小闸（docs/channels/contract.md §6.14）。只看真实字节头，不信扩展名或客户端 MIME；
 * 不探测时长、不转码、不生成封面。上限受全局 multipart（单文件 10 MB）约束。
 */
public final class ImMediaPolicy
{
    public enum Kind
    {
        FILE(10L * 1024 * 1024, 12, "[文件]"), VIDEO(10L * 1024 * 1024, 11, "[视频]"), VOICE(2L * 1024 * 1024, 10, "[语音]");

        public final long maxBytes;
        public final int msgType;
        public final String summary;

        Kind(long maxBytes, int msgType, String summary)
        {
            this.maxBytes = maxBytes;
            this.msgType = msgType;
            this.summary = summary;
        }

        public String wire() { return name().toLowerCase(Locale.ROOT); }

        public static Kind of(String path)
        {
            for (Kind k : values()) if (k.wire().equals(path)) return k;
            throw ChannelRejection.notFound();
        }

        public static Kind ofMsgType(int type)
        {
            for (Kind k : values()) if (k.msgType == type) return k;
            return null;
        }
    }

    /** 视频策略：App IMVideoPolicy 要求 maximumDurationMS == 30000、outputMIME == video/mp4；本批不转码，时长不由服务端证明。 */
    public static final int VIDEO_MAX_DURATION_MS = 30_000;

    public static final Map<String, String> FILE_MIMES = Map.of(
            "pdf", "application/pdf", "doc", "application/msword",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xls", "application/vnd.ms-excel",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "ppt", "application/vnd.ms-powerpoint",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "zip", "application/zip", "txt", "text/plain");

    private ImMediaPolicy() { }

    public record Detected(String ext, String mime) { }

    /** 名字、大小与字节头三者一致才放行；失败一律 400 + 稳定码，不落 OSS。 */
    public static Detected detect(Kind kind, String fileName, byte[] bytes)
    {
        if (bytes == null || bytes.length == 0) throw reject("MEDIA_EMPTY", "文件不能为空");
        if (bytes.length > kind.maxBytes) throw new ChannelRejection(413, "MEDIA_TOO_LARGE", "文件超过大小上限");
        switch (kind)
        {
            case FILE ->
            {
                String name = fileName(fileName);
                int dot = name.lastIndexOf('.');
                String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
                String mime = FILE_MIMES.get(ext);
                if (mime == null) throw new ChannelRejection(415, "MEDIA_TYPE_UNSUPPORTED", "不支持的文件类型");
                if (!fileMagic(ext, bytes)) throw new ChannelRejection(415, "MEDIA_TYPE_MISMATCH", "文件内容与类型不符");
                return new Detected(ext, mime);
            }
            case VIDEO ->
            {
                String brand = ftypBrand(bytes);
                if (brand == null) throw new ChannelRejection(415, "MEDIA_TYPE_MISMATCH", "视频需为 MP4/MOV");
                return "qt  ".equals(brand) ? new Detected("mov", "video/quicktime") : new Detected("mp4", "video/mp4");
            }
            default ->
            {
                String brand = ftypBrand(bytes);
                if (brand == null || !(brand.startsWith("M4A") || brand.startsWith("mp4") || brand.startsWith("isom")))
                    throw new ChannelRejection(415, "MEDIA_TYPE_MISMATCH", "语音需为 AAC/M4A");
                return new Detected("m4a", "audio/mp4");
            }
        }
    }

    public static String fileName(String raw)
    {
        if (raw == null || raw.isEmpty() || !raw.equals(raw.strip()) || raw.getBytes(StandardCharsets.UTF_8).length > 240
                || raw.contains("/") || raw.contains("\\") || raw.codePoints().anyMatch(Character::isISOControl))
            throw reject("MEDIA_NAME_INVALID", "文件名不合法");
        return raw;
    }

    static boolean fileMagic(String ext, byte[] b)
    {
        return switch (ext)
        {
            case "pdf" -> startsWith(b, 0x25, 0x50, 0x44, 0x46, 0x2d);
            case "zip", "docx", "xlsx", "pptx" -> startsWith(b, 0x50, 0x4b, 0x03, 0x04) || startsWith(b, 0x50, 0x4b, 0x05, 0x06);
            case "doc", "xls", "ppt" -> startsWith(b, 0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1);
            case "txt" -> utf8Text(b);
            default -> false;
        };
    }

    /** ISO BMFF：第 4..8 字节为 "ftyp"，返回主品牌；否则 null。 */
    static String ftypBrand(byte[] b)
    {
        if (b.length < 12 || b[4] != 'f' || b[5] != 't' || b[6] != 'y' || b[7] != 'p') return null;
        return new String(b, 8, 4, StandardCharsets.ISO_8859_1);
    }

    static boolean utf8Text(byte[] b)
    {
        for (byte x : b) if (x == 0) return false;
        try
        {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b));
            return true;
        }
        catch (CharacterCodingException e)
        {
            return false;
        }
    }

    private static boolean startsWith(byte[] b, int... prefix)
    {
        if (b.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((b[i] & 0xff) != prefix[i]) return false;
        return true;
    }

    public static String sha256(byte[] bytes)
    {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String requestId(Object raw)
    {
        if (!(raw instanceof String s) || !s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw ChannelRejection.badField("upload_request_id");
        return s;
    }

    public static String hash(Object raw)
    {
        if (!(raw instanceof String s) || !s.matches("[0-9a-f]{64}")) throw ChannelRejection.badField("source_sha256");
        return s;
    }

    private static ChannelRejection reject(String code, String message) { return new ChannelRejection(400, code, message); }
}
