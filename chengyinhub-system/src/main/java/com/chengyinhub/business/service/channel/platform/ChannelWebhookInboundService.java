package com.chengyinhub.business.service.channel.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.chengyinhub.business.mapper.channel.platform.ChannelWebhookMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.ISensitiveContentLibrary;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 频道入站 Webhook 接收（POST /api/im/webhook/inbound/{endpointId}，无登录态，靠签名）。
 * <p>按顺序校验，每次判定都落 im_webhook_inbound_log（App NativeWebhookHTTPService.inboundLogs 的规则）：
 * <ol>
 * <li>端点存在且未删除（否则 404，不落日志）；暂停 → REJECTED ENDPOINT_PAUSED。</li>
 * <li>IP 白名单（非空时；来源 IP 走 TrustedProxyIpResolver）→ BLOCKED IP_NOT_ALLOWED。</li>
 * <li>每分钟调用数 ≥ rate_limit_per_min → RATE_LIMITED。</li>
 * <li>X-Chengyin-Timestamp 与服务器时间相差 ≤ {@value #WINDOW_SECONDS}s；X-Chengyin-Nonce 16–64 位 [A-Za-z0-9_-]；
 *     X-Chengyin-Signature = v1=hex(HMAC-SHA256(端点密钥, timestamp.nonce.body))，常量时间比较 → 否则 REJECTED。</li>
 * <li>nonce 在该端点上首次出现（im_webhook_inbound_nonce 主键）→ 否则 REJECTED REPLAYED。</li>
 * <li>正文 {"content": 1–2000 字} → 否则 REJECTED BODY_INVALID；频道需有会话（type 5 或旧会话桥）且正常 → 否则 REJECTED CHANNEL_UNAVAILABLE。</li>
 * <li>文本审核（同 /api/im/send 的 IWxContentSecurityService.checkText）：放行 → 落可见消息，ACCEPTED；违规 → 不落消息，BLOCKED；
 *     需人工复核或机审不可用（Webhook 没有微信 openid，生产机审必然不可用）→ 落不可见消息（check_status 0、visible_at 空），PENDING_REVIEW。</li>
 * </ol>
 * 消息发送者是系统（sender_id 0），extra_json 带 sender_type 4 与 webhook_id；日志的 sender_type/webhook_id/message_id 只在真的落了消息时填。
 */
@Service
public class ChannelWebhookInboundService
{
    private static final Logger log = LoggerFactory.getLogger(ChannelWebhookInboundService.class);
    public static final int WINDOW_SECONDS = 300;
    /** nonce 保留时间：超过时间窗的请求本来就被拒，留两倍窗口足够。 */
    static final int NONCE_TTL_SECONDS = 2 * WINDOW_SECONDS;
    public static final int MAX_BODY_BYTES = 16 * 1024;
    public static final int SENDER_TYPE_WEBHOOK = 4;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 接收结果：HTTP 状态 + 业务码 + 数据。 */
    public record Result(int status, String errorCode, String message, Map<String, Object> data) { }

    @Autowired
    private ChannelWebhookMapper hooks;
    @Autowired
    private PiiCryptoService crypto;
    @Autowired
    private IWxContentSecurityService security;
    @Autowired(required = false)
    private PlatformTransactionManager transactions;

    public Result ingest(long endpointId, String timestamp, String nonce, String signature, byte[] body, String clientIp)
    {
        Map<String, Object> ep = hooks.selectEndpointForIngest(endpointId);
        if (ep == null || ChannelGuildAccess.toLong(ep.get("deleted")) != 0)
            return new Result(404, "WEBHOOK_NOT_FOUND", "Webhook 不存在", null);
        String requestHash = body == null ? null : sha256Hex(body);
        String loggedNonce = nonce != null && nonce.matches("[A-Za-z0-9_-]{16,64}") ? nonce : null;
        if (ChannelGuildAccess.toLong(ep.get("status")) != 1)
            return reject(endpointId, "REJECTED", "ENDPOINT_PAUSED", 409, requestHash, loggedNonce);
        List<String> allowed = ChannelWebhookService.decodeList(ep.get("allowedIpsJson"));
        if (!allowed.isEmpty() && !allowedIp(clientIp, allowed))
            return reject(endpointId, "BLOCKED", "IP_NOT_ALLOWED", 403, requestHash, loggedNonce);
        int limit = (int) ChannelGuildAccess.toLong(ep.get("rateLimitPerMin"));
        if (hooks.countRecentInbound(endpointId, 60) >= limit)
            return reject(endpointId, "RATE_LIMITED", "RATE_LIMITED", 429, requestHash, loggedNonce);
        Long signedAt = timestamp(timestamp);
        if (signedAt == null || Math.abs(System.currentTimeMillis() / 1000 - signedAt) > WINDOW_SECONDS)
            return reject(endpointId, "REJECTED", "TIMESTAMP_OUT_OF_WINDOW", 401, requestHash, loggedNonce);
        if (loggedNonce == null) return reject(endpointId, "REJECTED", "NONCE_INVALID", 400, requestHash, null);
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES)
            return reject(endpointId, "REJECTED", "BODY_INVALID", 400, requestHash, loggedNonce);
        Object cipher = ep.get("secretCipher");
        if (cipher == null || crypto == null || !crypto.isEnabled())
            return reject(endpointId, "REJECTED", "SECRET_UNRECOVERABLE", 409, requestHash, loggedNonce);
        String expected = "v1=" + ChannelWebhookDispatcher.sign(crypto.decrypt(cipher.toString()), signedAt, nonce, body);
        if (signature == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), signature.strip().getBytes(StandardCharsets.US_ASCII)))
            return reject(endpointId, "REJECTED", "SIGNATURE_INVALID", 401, requestHash, loggedNonce);
        // 签名有效之后才消耗 nonce：伪造请求烧不掉合法调用方的 nonce。
        if (hooks.insertNonce(endpointId, nonce, signedAt) != 1)
            return reject(endpointId, "REJECTED", "REPLAYED", 409, requestHash, nonce);
        hooks.touchEndpointCalled(endpointId);
        String content = content(body);
        if (content == null) return reject(endpointId, "REJECTED", "BODY_INVALID", 400, requestHash, nonce);
        long channelId = ChannelGuildAccess.toLong(ep.get("channelId"));
        Map<String, Object> bridge = hooks.selectChannelConversation(channelId);
        if (bridge == null || bridge.get("conversationId") == null || bridge.get("conversationRow") == null
                || ChannelGuildAccess.toLong(bridge.get("channelStatus")) != 0)
            return reject(endpointId, "REJECTED", "CHANNEL_UNAVAILABLE", 409, requestHash, nonce);
        String verdict = moderate(content);
        if (verdict != null && !reviewable(verdict))
            return reject(endpointId, "BLOCKED", "CONTENT_BLOCKED", 403, requestHash, nonce);
        boolean visible = verdict == null;
        long conversationId = ChannelGuildAccess.toLong(bridge.get("conversationId"));
        return inTransaction(() -> accept(ep, endpointId, conversationId, content, visible, requestHash, nonce));
    }

    private Result accept(Map<String, Object> ep, long endpointId, long conversationId, String content, boolean visible,
            String requestHash, String nonce)
    {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("sender_type", SENDER_TYPE_WEBHOOK);
        extra.put("webhook_id", String.valueOf(endpointId));
        extra.put("webhook_name", ep.get("name"));
        if (ep.get("avatarUrl") != null) extra.put("webhook_avatar_url", ep.get("avatarUrl"));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("conversationId", conversationId);
        message.put("content", content);
        message.put("extraJson", ChannelWebhookService.encode(extra));
        message.put("checkStatus", visible ? 1 : 0);
        message.put("visible", visible);
        message.put("clientMessageId", "wh-" + endpointId + "-" + sha256Hex(nonce.getBytes(StandardCharsets.UTF_8)).substring(0, 40));
        message.put("requestHash", requestHash);
        if (hooks.insertWebhookMessage(message) != 1) throw new IllegalStateException("Webhook message insert failed");
        long messageId = ChannelGuildAccess.toLong(message.get("id"));
        if (visible)
        {
            hooks.touchWebhookConversation(conversationId, content.length() > 200 ? content.substring(0, 200) : content);
            hooks.fanOutWebhookUnread(conversationId);
        }
        String result = visible ? "ACCEPTED" : "PENDING_REVIEW";
        Map<String, Object> row = logRow(endpointId, result, visible ? null : "REVIEW_REQUIRED", requestHash, nonce);
        row.put("messageId", messageId);
        row.put("senderType", SENDER_TYPE_WEBHOOK);
        row.put("webhookId", endpointId);
        row.put("originalMessageStored", 1);
        hooks.insertInboundLog(row);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("result", result);
        data.put("log_id", String.valueOf(ChannelGuildAccess.toLong(row.get("id"))));
        data.put("message_id", String.valueOf(messageId));
        return new Result(visible ? 200 : 202, null, visible ? "已接收" : "已接收，待审核", data);
    }

    private Result reject(long endpointId, String result, String reason, int status, String requestHash, String nonce)
    {
        Map<String, Object> row = logRow(endpointId, result, reason, requestHash, nonce);
        hooks.insertInboundLog(row);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("result", result);
        data.put("reason", reason);
        data.put("log_id", String.valueOf(ChannelGuildAccess.toLong(row.get("id"))));
        return new Result(status, "WEBHOOK_" + reason, result, data);
    }

    private static Map<String, Object> logRow(long endpointId, String result, String reason, String requestHash, String nonce)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("endpointId", endpointId);
        row.put("result", result);
        row.put("reason", reason);
        row.put("messageId", null);
        row.put("requestHash", requestHash);
        row.put("nonce", nonce);
        row.put("senderType", null);
        row.put("webhookId", null);
        row.put("originalMessageStored", null);
        return row;
    }

    /** null = 放行；否则是审核文案（违规 / 需复核 / 不可用）。审核调用本身失败视为不可用。 */
    private String moderate(String content)
    {
        try
        {
            return security.checkText(content, null);
        }
        catch (RuntimeException e)
        {
            log.warn("[webhook] 入站文本审核调用失败，按待审处理: {}", e.getClass().getSimpleName());
            return IWxContentSecurityService.MSG_UNAVAILABLE;
        }
    }

    static boolean reviewable(String verdict)
    {
        return !ISensitiveContentLibrary.VIOLATION_MESSAGE.equals(verdict)
                && (IWxContentSecurityService.REVIEW_REQUIRED.equals(verdict) || IWxContentSecurityService.isDetectionUnavailable(verdict));
    }

    /** {"content": "..."}：只此一个字段，1–2000 字，非空白，不含除换行/制表外的控制字符。 */
    static String content(byte[] body)
    {
        try
        {
            JsonNode node = JSON.readTree(body);
            if (node == null || !node.isObject() || node.size() != 1 || !node.has("content") || !node.get("content").isTextual()) return null;
            String text = node.get("content").asText();
            if (text.isBlank() || text.length() > 2000 || text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) return null;
            return text;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    static Long timestamp(String raw)
    {
        if (raw == null || !raw.matches("[0-9]{1,12}")) return null;
        return Long.parseLong(raw);
    }

    static boolean allowedIp(String ip, List<String> allowed)
    {
        long[] addr = cidr(ip);
        if (addr == null || addr[1] != 32) return false;
        for (String rule : allowed)
        {
            long[] r = cidr(rule);
            if (r == null) continue;
            long mask = r[1] == 0 ? 0 : (0xFFFFFFFFL << (32 - r[1])) & 0xFFFFFFFFL;
            if ((addr[0] & mask) == (r[0] & mask)) return true;
        }
        return false;
    }

    /** "a.b.c.d" 或 "a.b.c.d/n"（n 8–32）→ {地址, 前缀长}；否则 null。 */
    static long[] cidr(String raw)
    {
        if (raw == null) return null;
        String[] parts = raw.split("/", -1);
        if (parts.length > 2 || !parts[0].matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return null;
        long value = 0;
        for (String octet : parts[0].split("\\."))
        {
            int o = Integer.parseInt(octet);
            if (o > 255 || (octet.length() > 1 && octet.startsWith("0"))) return null;
            value = (value << 8) | o;
        }
        int bits = 32;
        if (parts.length == 2)
        {
            if (!parts[1].matches("[0-9]{1,2}")) return null;
            bits = Integer.parseInt(parts[1]);
            if (bits < 8 || bits > 32) return null;
        }
        return new long[] { value, bits };
    }

    /** 过期 nonce 清理（超过两倍时间窗的早被时间窗拒绝，不再需要）。 */
    @Scheduled(fixedDelayString = "${chengyin.im.webhook.inbound.nonce-cleanup-ms:600000}", initialDelayString = "${chengyin.im.webhook.inbound.nonce-cleanup-ms:600000}")
    public void purgeNonces()
    {
        try
        {
            hooks.deleteExpiredNonces(NONCE_TTL_SECONDS, 1000);
        }
        catch (RuntimeException e)
        {
            log.warn("[webhook] nonce 清理失败: {}", e.getClass().getSimpleName());
        }
    }

    private <T> T inTransaction(Supplier<T> work)
    {
        if (transactions == null) return work.get();
        return new TransactionTemplate(transactions).execute(status -> work.get());
    }

    static String sha256Hex(byte[] data)
    {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
