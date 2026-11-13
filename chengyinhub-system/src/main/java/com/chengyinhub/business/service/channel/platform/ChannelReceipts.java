package com.chengyinhub.business.service.channel.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 原请求回执：同一 (操作者, 族, request_id) 在同一事务里先占位、加锁，再执行一次；
 * 指纹不同 → REQUEST_ID_CONFLICT；已有响应 → 原样重放并标 replayed。调用方必须处于可写事务内。
 */
public final class ChannelReceipts
{
    public static final String META = "META";
    public static final String GOV = "GOV";
    public static final String MSG = "MSG";

    private static final ObjectMapper JSON = new ObjectMapper();

    private ChannelReceipts() { }

    public static String fingerprint(String operation, Object scope, Map<String, ?> payload)
    {
        try
        {
            Map<String, Object> canonical = new TreeMap<>();
            canonical.put("op", operation);
            canonical.put("scope", String.valueOf(scope));
            canonical.put("payload", payload == null ? Map.of() : new TreeMap<>(payload));
            byte[] body = JSON.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Cannot bind request", e);
        }
    }

    public static Map<String, Object> once(ChannelPlatformMapper mapper, long operator, String family, String requestId,
            String fingerprint, String operation, long channelId, Supplier<Map<String, Object>> work)
    {
        mapper.reserveReceipt(operator, family, requestId, fingerprint, operation, channelId);
        Map<String, Object> receipt = mapper.lockReceipt(operator, family, requestId);
        if (receipt == null) throw new IllegalStateException("Receipt reservation lost");
        if (!fingerprint.equals(String.valueOf(receipt.get("fingerprint"))))
            throw new ChannelRejection(409, "REQUEST_ID_CONFLICT", "该请求标识已用于其他内容");
        Object stored = receipt.get("response");
        if (stored != null)
        {
            Map<String, Object> replay = decode(stored.toString());
            replay.put("replayed", Boolean.TRUE);
            return replay;
        }
        Map<String, Object> response = work.get();
        String encoded = encode(response);
        if (mapper.finishReceipt(operator, family, requestId, encoded) != 1)
            throw new IllegalStateException("Receipt finish failed");
        // 首次响应与重放同一份持久化表示，数字/字符串类型一致。
        return decode(encoded);
    }

    public static String encode(Map<String, Object> value)
    {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot store receipt", e); }
    }

    public static Map<String, Object> decode(String value)
    {
        try { return JSON.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() { }); }
        catch (Exception e) { throw new IllegalStateException("Cannot read receipt", e); }
    }

    public static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
