package com.chengyinhub.common.utils.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.springframework.web.multipart.MultipartFile;

/** Read MP4 container facts before entering the existing human media review queue. */
public final class ChapterVideoPolicy {
    private ChapterVideoPolicy() {}
    public static final long MAX_BYTES = 10 * 1024 * 1024;

    public static void validate(MultipartFile file) {
        if (file.getSize() <= 0 || file.getSize() > MAX_BYTES)
            throw new IllegalArgumentException("视频不能超过 10 MB");
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("视频不能超过 10 MB");
            Facts facts = new Facts();
            boxes(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN), 0, bytes.length, 0, facts);
            if (!facts.video || !facts.movie || facts.duration <= 0 || facts.duration > 30)
                throw new IllegalArgumentException("请上传不超过 30 秒的 mp4 视频");
        } catch (IOException | IndexOutOfBoundsException invalid) {
            throw new IllegalArgumentException("无法读取视频，请更换 mp4 文件", invalid);
        }
    }

    private static final class Facts { boolean video, movie; double duration; }
    private static void boxes(ByteBuffer b, int start, int end, int depth, Facts facts) {
        if (depth > 5) throw new IllegalArgumentException("视频结构无效");
        for (int at = start; at < end;) {
            if (end - at < 8) throw new IllegalArgumentException("视频文件不完整");
            long size = Integer.toUnsignedLong(b.getInt(at));
            int type = b.getInt(at + 4), header = 8;
            if (size == 1) { if (end - at < 16) throw new IllegalArgumentException("视频文件不完整"); size = b.getLong(at + 8); header = 16; }
            if (size == 0) size = end - at;
            if (size < header || size > end - at) throw new IllegalArgumentException("视频文件不完整");
            int data = at + header, next = at + (int) size;
            if (type == 0x6d6f6f76 || type == 0x7472616b || type == 0x6d646961) { // moov/trak/mdia
                boxes(b, data, next, depth + 1, facts);
            } else if (type == 0x6d766864 || type == 0x6d646864) { // mvhd/mdhd: reject a long track even if movie duration lies
                if (next - data < 4) throw new IllegalArgumentException("视频时长无法读取");
                int version = Byte.toUnsignedInt(b.get(data));
                if (version > 1) throw new IllegalArgumentException("视频时长无法读取");
                int scaleAt = data + (version == 1 ? 20 : 12);
                if (next - scaleAt < (version == 1 ? 12 : 8)) throw new IllegalArgumentException("视频时长无法读取");
                long scale = Integer.toUnsignedLong(b.getInt(scaleAt));
                long duration = version == 1 ? b.getLong(scaleAt + 4) : Integer.toUnsignedLong(b.getInt(scaleAt + 4));
                if (scale == 0 || duration <= 0) throw new IllegalArgumentException("视频时长无法读取");
                facts.duration = Math.max(facts.duration, (double) duration / scale);
                if (type == 0x6d766864) facts.movie = true;
            } else if (type == 0x68646c72 && next - data >= 12 && b.getInt(data + 8) == 0x76696465) { // hdlr/vide
                facts.video = true;
            }
            at = next;
        }
    }
}
