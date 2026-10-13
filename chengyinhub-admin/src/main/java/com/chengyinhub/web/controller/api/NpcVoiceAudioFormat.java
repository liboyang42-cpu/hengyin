package com.chengyinhub.web.controller.api;

/**
 * 门店 NPC 语音入口的字节级格式识别。
 *
 * <p>文件名和 multipart Content-Type 都是客户端声明，不能作为 ASR 格式真源。这里仅允许
 * 受控的音频容器，并把由字节内容识别出的格式传给 ASR；无法确定时返回 {@code null}，
 * 由入口在调用 ASR 前失败关闭。
 */
final class NpcVoiceAudioFormat
{
    private static final int[] MPEG1_LAYER3_BITRATES = {
            0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0
    };
    private static final int[] MPEG2_LAYER23_BITRATES = {
            0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0
    };
    private static final int[] MPEG1_SAMPLE_RATES = { 44100, 48000, 32000 };
    private static final int[] MPEG2_SAMPLE_RATES = { 22050, 24000, 16000 };
    private static final int[] MPEG25_SAMPLE_RATES = { 11025, 12000, 8000 };
    private static final int[] AAC_SAMPLE_RATES = {
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
    };

    private NpcVoiceAudioFormat() {}

    static String detect(byte[] audio)
    {
        if (isM4a(audio)) return "m4a";
        if (isPcmWav(audio)) return "wav";
        if (isAacAdts(audio)) return "aac";
        if (isMp3(audio)) return "mp3";
        return null;
    }

    private static boolean isPcmWav(byte[] bytes)
    {
        if (bytes == null || bytes.length < 44 || !asciiAt(bytes, 0, "RIFF")
                || !asciiAt(bytes, 8, "WAVE") || uint32Le(bytes, 4) + 8 != bytes.length)
        {
            return false;
        }
        boolean validFormat = false;
        boolean hasAudioData = false;
        int offset = 12;
        while (offset + 8 <= bytes.length)
        {
            long chunkSize = uint32Le(bytes, offset + 4);
            long chunkEnd = (long) offset + 8 + chunkSize;
            if (chunkEnd > bytes.length) return false;
            if (asciiAt(bytes, offset, "fmt ") && chunkSize >= 16)
            {
                int encoding = uint16Le(bytes, offset + 8);
                int channels = uint16Le(bytes, offset + 10);
                long sampleRate = uint32Le(bytes, offset + 12);
                int bitsPerSample = uint16Le(bytes, offset + 22);
                validFormat = encoding == 1 && (channels == 1 || channels == 2)
                        && sampleRate >= 8000 && sampleRate <= 48000
                        && (bitsPerSample == 8 || bitsPerSample == 16
                            || bitsPerSample == 24 || bitsPerSample == 32);
            }
            if (asciiAt(bytes, offset, "data") && chunkSize > 0) hasAudioData = true;
            offset = (int) chunkEnd + ((chunkSize & 1) == 0 ? 0 : 1);
        }
        return offset == bytes.length && validFormat && hasAudioData;
    }

    private static boolean isAacAdts(byte[] bytes)
    {
        if (bytes == null) return false;
        int offset = 0;
        int frames = 0;
        while (offset < bytes.length)
        {
            if (offset + 7 > bytes.length) return false;
            int b1 = bytes[offset + 1] & 0xFF;
            int b2 = bytes[offset + 2] & 0xFF;
            int b3 = bytes[offset + 3] & 0xFF;
            int b4 = bytes[offset + 4] & 0xFF;
            int b5 = bytes[offset + 5] & 0xFF;
            if ((bytes[offset] & 0xFF) != 0xFF || (b1 & 0xF6) != 0xF0) return false;
            int profile = (b2 >>> 6) & 0x03;
            int sampleRateIndex = (b2 >>> 2) & 0x0F;
            int channelConfig = ((b2 & 0x01) << 2) | ((b3 >>> 6) & 0x03);
            int headerSize = (b1 & 0x01) == 0 ? 9 : 7;
            int frameSize = ((b3 & 0x03) << 11) | (b4 << 3) | ((b5 >>> 5) & 0x07);
            if (profile != 1 || sampleRateIndex > 12 || channelConfig < 1 || channelConfig > 2
                    || frameSize < headerSize || frameSize > bytes.length - offset)
            {
                return false;
            }
            offset += frameSize;
            frames++;
        }
        return frames > 0;
    }

    private static boolean isM4a(byte[] bytes)
    {
        if (bytes == null || bytes.length < 24 || !asciiAt(bytes, 4, "ftyp")) return false;
        long firstBoxSize = uint32(bytes, 0);
        if (firstBoxSize < 16 || firstBoxSize > bytes.length) return false;

        boolean m4aBrand = asciiAt(bytes, 8, "M4A ");
        for (int offset = 16; !m4aBrand && offset + 4 <= firstBoxSize; offset += 4)
        {
            m4aBrand = asciiAt(bytes, offset, "M4A ");
        }
        if (!m4aBrand) return false;

        boolean hasAacAudioTrack = false;
        boolean hasMediaData = false;
        int offset = 0;
        while (offset < bytes.length)
        {
            int end = boxEnd(bytes, offset, bytes.length);
            if (end < 0) return false;
            if (asciiAt(bytes, offset + 4, "moov"))
            {
                hasAacAudioTrack |= hasAacAudioTrack(bytes, boxPayload(bytes, offset), end);
            }
            if (asciiAt(bytes, offset + 4, "mdat") && end > boxPayload(bytes, offset)) hasMediaData = true;
            offset = end;
        }
        return hasAacAudioTrack && hasMediaData;
    }

    private static boolean hasAacAudioTrack(byte[] bytes, int start, int end)
    {
        int offset = start;
        while (offset < end)
        {
            int boxEnd = boxEnd(bytes, offset, end);
            if (boxEnd < 0) return false;
            if (asciiAt(bytes, offset + 4, "trak") && isAacTrack(bytes, boxPayload(bytes, offset), boxEnd))
            {
                return true;
            }
            offset = boxEnd;
        }
        return false;
    }

    private static boolean isAacTrack(byte[] bytes, int start, int end)
    {
        int mdia = findChild(bytes, start, end, "mdia");
        if (mdia < 0) return false;
        int mdiaEnd = boxEnd(bytes, mdia, end);
        int mdiaPayload = boxPayload(bytes, mdia);

        int handler = findChild(bytes, mdiaPayload, mdiaEnd, "hdlr");
        int handlerPayload = boxPayload(bytes, handler);
        if (handler < 0 || handlerPayload < 0 || handlerPayload + 12 > boxEnd(bytes, handler, mdiaEnd)
                || !asciiAt(bytes, handlerPayload + 8, "soun"))
        {
            return false;
        }

        int minf = findChild(bytes, mdiaPayload, mdiaEnd, "minf");
        if (minf < 0) return false;
        int stbl = findChild(bytes, boxPayload(bytes, minf), boxEnd(bytes, minf, mdiaEnd), "stbl");
        if (stbl < 0) return false;
        int stsd = findChild(bytes, boxPayload(bytes, stbl), boxEnd(bytes, stbl, mdiaEnd), "stsd");
        return stsd >= 0 && hasMp4aSampleEntry(bytes, stsd, boxEnd(bytes, stsd, mdiaEnd));
    }

    private static boolean hasMp4aSampleEntry(byte[] bytes, int stsd, int stsdEnd)
    {
        int payload = boxPayload(bytes, stsd);
        if (payload < 0 || payload + 8 > stsdEnd) return false;
        long entryCount = uint32(bytes, payload + 4);
        if (entryCount != 1) return false;
        int offset = payload + 8;
        int entryEnd = boxEnd(bytes, offset, stsdEnd);
        return entryEnd == stsdEnd && asciiAt(bytes, offset + 4, "mp4a")
                && validMp4aSampleEntry(bytes, offset, entryEnd);
    }

    private static boolean validMp4aSampleEntry(byte[] bytes, int start, int end)
    {
        int payload = boxPayload(bytes, start);
        if (payload < 0 || payload + 28 > end) return false;
        int channels = uint16(bytes, payload + 16);
        int sampleBits = uint16(bytes, payload + 18);
        long sampleRate = uint32(bytes, payload + 24) >>> 16;
        int esds = findChild(bytes, payload + 28, end, "esds");
        boolean appleCoreAudioI16 = hasAppleCoreAudioI16Marker(bytes, payload + 28, end);
        return (channels == 1 || channels == 2) && sampleBits == 16
                && sampleRate >= 8000 && sampleRate <= 48000 && esds >= 0
                && hasAacLcDecoderConfig(bytes, esds, boxEnd(bytes, esds, end), channels,
                        (int) sampleRate, appleCoreAudioI16);
    }

    private static boolean hasAppleCoreAudioI16Marker(byte[] bytes, int start, int end)
    {
        int sbtd = findChild(bytes, start, end, "sbtd");
        if (sbtd < 0) return false;
        int payload = boxPayload(bytes, sbtd);
        return boxEnd(bytes, sbtd, end) - sbtd == 15 && asciiAt(bytes, payload + 4, "I16");
    }

    private static boolean hasAacLcDecoderConfig(
            byte[] bytes, int esds, int esdsEnd, int expectedChannels, int expectedSampleRate,
            boolean appleCoreAudioI16)
    {
        int payload = boxPayload(bytes, esds);
        if (payload < 0 || payload + 4 >= esdsEnd) return false;
        long esRange = descriptorRange(bytes, payload + 4, esdsEnd, 0x03);
        if (esRange < 0) return false;
        int cursor = rangeStart(esRange);
        int esEnd = rangeEnd(esRange);
        if (cursor + 3 > esEnd) return false;
        int flags = bytes[cursor + 2] & 0xFF;
        cursor += 3;
        if ((flags & 0x80) != 0) cursor += 2;
        if ((flags & 0x40) != 0)
        {
            if (cursor >= esEnd) return false;
            int urlLength = bytes[cursor] & 0xFF;
            cursor += 1 + urlLength;
        }
        if ((flags & 0x20) != 0) cursor += 2;
        if (cursor > esEnd) return false;

        long decoderRange = descriptorRange(bytes, cursor, esEnd, 0x04);
        if (decoderRange < 0) return false;
        int decoder = rangeStart(decoderRange);
        int decoderEnd = rangeEnd(decoderRange);
        if (decoder + 13 > decoderEnd || (bytes[decoder] & 0xFF) != 0x40
                || ((bytes[decoder + 1] >>> 2) & 0x3F) != 0x05)
        {
            return false;
        }

        long configRange = descriptorRange(bytes, decoder + 13, decoderEnd, 0x05);
        if (configRange < 0 || rangeStart(configRange) + 2 > rangeEnd(configRange)) return false;
        int config = rangeStart(configRange);
        int audioObjectType = (bytes[config] >>> 3) & 0x1F;
        int sampleRateIndex = ((bytes[config] & 0x07) << 1) | ((bytes[config + 1] >>> 7) & 0x01);
        int channelConfig = (bytes[config + 1] >>> 3) & 0x0F;
        boolean channelsMatch = channelConfig == expectedChannels
                || (appleCoreAudioI16 && expectedChannels == 2 && channelConfig == 1);
        return audioObjectType == 2 && sampleRateIndex < AAC_SAMPLE_RATES.length
                && AAC_SAMPLE_RATES[sampleRateIndex] == expectedSampleRate
                && channelsMatch;
    }

    private static long descriptorRange(byte[] bytes, int offset, int limit, int expectedTag)
    {
        if (offset < 0 || offset + 2 > limit || (bytes[offset] & 0xFF) != expectedTag) return -1;
        int cursor = offset + 1;
        int length = 0;
        boolean complete = false;
        for (int count = 0; count < 4 && cursor < limit; count++)
        {
            int value = bytes[cursor++] & 0xFF;
            length = (length << 7) | (value & 0x7F);
            if ((value & 0x80) == 0)
            {
                complete = true;
                break;
            }
        }
        if (!complete || length > limit - cursor) return -1;
        return ((long) cursor << 32) | ((cursor + length) & 0xFFFFFFFFL);
    }

    private static int rangeStart(long range)
    {
        return (int) (range >>> 32);
    }

    private static int rangeEnd(long range)
    {
        return (int) range;
    }

    private static int findChild(byte[] bytes, int start, int end, String type)
    {
        if (start < 0 || end < start || end > bytes.length) return -1;
        int offset = start;
        while (offset < end)
        {
            int childEnd = boxEnd(bytes, offset, end);
            if (childEnd < 0) return -1;
            if (asciiAt(bytes, offset + 4, type)) return offset;
            offset = childEnd;
        }
        return -1;
    }

    private static int boxPayload(byte[] bytes, int offset)
    {
        if (offset < 0 || offset + 8 > bytes.length) return -1;
        return offset + (uint32(bytes, offset) == 1 ? 16 : 8);
    }

    private static int boxEnd(byte[] bytes, int offset, int limit)
    {
        if (offset < 0 || limit > bytes.length || offset + 8 > limit) return -1;
        long size = uint32(bytes, offset);
        int headerSize = 8;
        if (size == 1)
        {
            if (offset + 16 > limit) return -1;
            size = uint64WithinInt(bytes, offset + 8);
            headerSize = 16;
        }
        else if (size == 0)
        {
            size = limit - offset;
        }
        if (size < headerSize || size > limit - offset) return -1;
        return offset + (int) size;
    }

    private static boolean isMp3(byte[] bytes)
    {
        if (bytes == null || bytes.length < 4) return false;
        int offset = 0;
        if (asciiAt(bytes, 0, "ID3"))
        {
            if (bytes.length < 14) return false;
            int version = bytes[3] & 0xFF;
            if (version < 2 || version > 4) return false;
            for (int i = 6; i < 10; i++) if ((bytes[i] & 0x80) != 0) return false;
            int tagSize = ((bytes[6] & 0x7F) << 21) | ((bytes[7] & 0x7F) << 14)
                    | ((bytes[8] & 0x7F) << 7) | (bytes[9] & 0x7F);
            offset = 10 + tagSize;
            if (version == 4 && (bytes[5] & 0x10) != 0) offset += 10;
        }

        int frames = 0;
        while (offset < bytes.length)
        {
            if (bytes.length - offset == 128 && asciiAt(bytes, offset, "TAG"))
            {
                offset = bytes.length;
                break;
            }
            int frameSize = mpegAudioFrameSize(bytes, offset);
            if (frameSize <= 0 || frameSize > bytes.length - offset) return false;
            offset += frameSize;
            frames++;
        }
        return frames >= 2;
    }

    private static int mpegAudioFrameSize(byte[] bytes, int offset)
    {
        if (offset < 0 || offset + 4 > bytes.length) return -1;
        int b1 = bytes[offset + 1] & 0xFF;
        int b2 = bytes[offset + 2] & 0xFF;
        if ((bytes[offset] & 0xFF) != 0xFF || (b1 & 0xE0) != 0xE0) return -1;
        int version = (b1 >>> 3) & 0x03;
        int layer = (b1 >>> 1) & 0x03;
        int bitrateIndex = (b2 >>> 4) & 0x0F;
        int sampleRateIndex = (b2 >>> 2) & 0x03;
        // “mp3”只表示 MPEG Audio Layer III；Layer I/II 不能贴同一个格式标签送进 ASR。
        if (version == 1 || layer != 1 || bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) return -1;

        int bitrate = (version == 3 ? MPEG1_LAYER3_BITRATES[bitrateIndex]
                : MPEG2_LAYER23_BITRATES[bitrateIndex]) * 1000;
        int sampleRate = mpegSampleRate(version, sampleRateIndex);
        int padding = (b2 >>> 1) & 0x01;
        if (bitrate <= 0 || sampleRate <= 0) return -1;
        return ((version == 3 ? 144 : 72) * bitrate / sampleRate) + padding;
    }

    private static int mpegSampleRate(int version, int index)
    {
        int[] rates = version == 3 ? MPEG1_SAMPLE_RATES
                : version == 2 ? MPEG2_SAMPLE_RATES : MPEG25_SAMPLE_RATES;
        return rates[index];
    }

    private static long uint32(byte[] bytes, int offset)
    {
        if (offset < 0 || offset + 4 > bytes.length) return -1;
        return ((long) (bytes[offset] & 0xFF) << 24)
                | ((long) (bytes[offset + 1] & 0xFF) << 16)
                | ((long) (bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    private static long uint64WithinInt(byte[] bytes, int offset)
    {
        if (offset < 0 || offset + 8 > bytes.length) return -1;
        for (int i = 0; i < 4; i++) if (bytes[offset + i] != 0) return -1;
        return uint32(bytes, offset + 4);
    }

    private static int uint16(byte[] bytes, int offset)
    {
        if (offset < 0 || offset + 2 > bytes.length) return -1;
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static long uint32Le(byte[] bytes, int offset)
    {
        if (offset < 0 || offset + 4 > bytes.length) return -1;
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    private static int uint16Le(byte[] bytes, int offset)
    {
        if (offset < 0 || offset + 2 > bytes.length) return -1;
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static boolean asciiAt(byte[] bytes, int offset, String expected)
    {
        if (offset < 0 || offset + expected.length() > bytes.length) return false;
        for (int i = 0; i < expected.length(); i++)
        {
            if ((bytes[offset + i] & 0xFF) != expected.charAt(i)) return false;
        }
        return true;
    }
}
