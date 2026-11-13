package com.chengyinhub.business.service.friend;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * App 端原意图指纹的服务端同构实现（逐字节对齐 Swift：NativeFriendIntentJournal.originalIntentProof、
 * NativeFriendMetadataCommand.fingerprint、NativeFriendRemovalCommand.fingerprint、
 * NativeFriendChannelInviteCommand.proof）。receipt.payload_hash 必须与 App 本地计算一致，否则 App 视为结果未知。
 */
public final class FriendProofs
{
    private FriendProofs() { }

    public static String sha256(byte[] bytes)
    {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String sha256(String text) { return sha256(text.getBytes(StandardCharsets.UTF_8)); }

    /** "len:value" 串接，len 为 UTF-8 字节数。 */
    public static String lengthPrefixed(List<String> fields)
    {
        StringBuilder sb = new StringBuilder();
        for (String f : fields) sb.append(f.getBytes(StandardCharsets.UTF_8).length).append(':').append(f);
        return sha256(sb.toString());
    }

    /** 好友请求/接受/拒绝/撤回。sourceType 0 = 无来源。 */
    public static String command(long peerId, String action, String message, Long expectedVersion, Long reviewId,
            int sourceType, long sourceId, String generation)
    {
        String tag = sourceType == 0 ? "friend-command-v1" : sourceType == 4 ? "friend-post-command-v1"
                : sourceType == 3 ? "friend-team-command-v1" : sourceType == 2 ? "friend-activity-command-v1"
                : "friend-channel-command-v1";
        List<String> fields = new ArrayList<>(List.of(tag, String.valueOf(peerId), action, message,
                expectedVersion == null ? "" : String.valueOf(expectedVersion), reviewId == null ? "" : String.valueOf(reviewId)));
        if (sourceType != 0)
        {
            fields.add(String.valueOf(sourceType));
            fields.add(String.valueOf(sourceId));
            if (sourceType == 4) fields.add(generation == null ? "" : generation);
        }
        return lengthPrefixed(fields);
    }

    public static String removal(long peerId, long expectedVersion)
    {
        return sha256("friend-removal-v1\n" + peerId + "\n" + expectedVersion);
    }

    /** friend-metadata-v1：按键排序，N / S{len}:{text} / I{n}; 编码，覆盖请求体全部键。 */
    public static String metadata(Map<String, Object> body)
    {
        StringBuilder raw = new StringBuilder("friend-metadata-v1\n");
        for (Map.Entry<String, Object> e : new TreeMap<>(body).entrySet())
        {
            String key = e.getKey();
            raw.append(key.getBytes(StandardCharsets.UTF_8).length).append(':').append(key);
            Object v = e.getValue();
            if (v == null) raw.append('N');
            else if (v instanceof String s) raw.append('S').append(s.getBytes(StandardCharsets.UTF_8).length).append(':').append(s);
            else if (v instanceof Integer || v instanceof Long || v instanceof java.math.BigInteger)
                raw.append('I').append(((Number) v).longValue()).append(';');
            else throw new IllegalArgumentException("unsupported metadata value");
        }
        return sha256(raw.toString());
    }

    /** original-friend-channel-invite-v1。peers 按 id 升序，accepted 按 key 升序（App 构造时已排序）。 */
    public static String channelInvite(String action, long objectId, String message, List<long[]> peers, List<String[]> accepted)
    {
        List<String> fields = new ArrayList<>(List.of("original-friend-channel-invite-v1", action, String.valueOf(objectId), message,
                String.valueOf(peers.size())));
        for (long[] p : peers) { fields.add(String.valueOf(p[0])); fields.add(String.valueOf(p[1])); }
        fields.add(String.valueOf(accepted.size()));
        for (String[] a : accepted) { fields.add(a[0]); fields.add(a[1]); }
        return lengthPrefixed(fields);
    }
}
