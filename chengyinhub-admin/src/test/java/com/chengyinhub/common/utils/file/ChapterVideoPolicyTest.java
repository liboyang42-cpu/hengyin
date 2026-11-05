package com.chengyinhub.common.utils.file;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;

class ChapterVideoPolicyTest {
    private static byte[] box(String type, byte[] data) {
        return ByteBuffer.allocate(8 + data.length).putInt(8 + data.length)
                .put(type.getBytes(StandardCharsets.US_ASCII)).put(data).array();
    }
    private static MockMultipartFile clip(int seconds, boolean video, int trackSeconds) {
        byte[] header = ByteBuffer.allocate(20).putInt(0).putLong(0).putInt(1000).putInt(seconds * 1000).array();
        byte[] track = ByteBuffer.allocate(20).putInt(0).putLong(0).putInt(1000).putInt(trackSeconds * 1000).array();
        byte[] handler = ByteBuffer.allocate(12).putLong(0).putInt(video ? 0x76696465 : 0x736f756e).array();
        byte[] movie = box("mvhd", header), media = box("mdhd", track), hdlr = box("hdlr", handler);
        byte[] mdia = box("mdia", ByteBuffer.allocate(media.length + hdlr.length).put(media).put(hdlr).array());
        byte[] trak = box("trak", mdia);
        byte[] moov = box("moov", ByteBuffer.allocate(movie.length + trak.length).put(movie).put(trak).array());
        return new MockMultipartFile("file", "clip.mp4", "video/mp4", moov);
    }
    @Test void acceptsThirtySecondsAndRejectsLongAudioOnlyAndLyingMovieDuration() {
        assertDoesNotThrow(() -> ChapterVideoPolicy.validate(clip(30, true, 30)));
        assertThrows(IllegalArgumentException.class, () -> ChapterVideoPolicy.validate(clip(31, true, 31)));
        assertThrows(IllegalArgumentException.class, () -> ChapterVideoPolicy.validate(clip(1, false, 1)));
        assertThrows(IllegalArgumentException.class, () -> ChapterVideoPolicy.validate(clip(1, true, 31)));
    }
    @Test void rejectsTruncatedZeroDurationAndOversizeFiles() {
        assertThrows(IllegalArgumentException.class, () -> ChapterVideoPolicy.validate(clip(0, true, 0)));
        assertThrows(IllegalArgumentException.class, () -> ChapterVideoPolicy.validate(new MockMultipartFile("file", new byte[]{0,0,0,99})));
        assertThrows(IllegalArgumentException.class, () -> ChapterVideoPolicy.validate(new MockMultipartFile("file", new byte[(int) ChapterVideoPolicy.MAX_BYTES + 1])));
    }
}
