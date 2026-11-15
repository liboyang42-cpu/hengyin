package com.chengyinhub.business.push;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.alibaba.fastjson2.JSON;

/**
 * APNs 负载（App 依赖这个形状）：
 * <pre>{"aps":{"alert":{"title":..,"body":..},"sound":"default","thread-id":..},"page":"/pages/..","kind":"chat|club|event","conversation_id":"..."}</pre>
 * page 只能是相对源路径；整体不超过 4KB（超出时截断 body）。
 */
public final class PushPayloadBuilder
{
    public static final int MAX_BYTES = 4096;
    static final int MAX_TITLE_CHARS = 64;
    static final int MAX_BODY_CHARS = 240;

    private PushPayloadBuilder() { }

    public static String build(PushMessage m)
    {
        if (m == null || m.kind() == null || !m.kind().matches("chat|club|event")) throw new IllegalArgumentException("kind");
        if (m.page() == null || !m.page().startsWith("/") || m.page().startsWith("//") || m.page().contains("://"))
            throw new IllegalArgumentException("page must be a relative source path");
        if (PushMessage.KIND_CHAT.equals(m.kind()) && (m.conversationId() == null || m.conversationId().isEmpty()))
            throw new IllegalArgumentException("conversation_id");
        String title = cut(m.title(), MAX_TITLE_CHARS);
        String body = cut(m.body(), MAX_BODY_CHARS);
        String json = render(m, title, body);
        while (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES && !body.isEmpty())
        {
            body = cut(body, Math.max(0, body.length() - Math.max(16, body.length() / 4)));
            json = render(m, title, body);
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("payload too large");
        return json;
    }

    private static String render(PushMessage m, String title, String body)
    {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("title", title);
        alert.put("body", body);
        Map<String, Object> aps = new LinkedHashMap<>();
        aps.put("alert", alert);
        aps.put("sound", "default");
        aps.put("thread-id", m.threadId());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("aps", aps);
        root.put("page", m.page());
        root.put("kind", m.kind());
        if (PushMessage.KIND_CHAT.equals(m.kind())) root.put("conversation_id", m.conversationId());
        return JSON.toJSONString(root);
    }

    /** 按码点截断，超长时以 … 结尾，避免切断代理对。 */
    static String cut(String value, int maxChars)
    {
        String v = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (v.codePointCount(0, v.length()) <= maxChars) return v;
        if (maxChars <= 1) return maxChars == 1 ? "…" : "";
        int end = v.offsetByCodePoints(0, maxChars - 1);
        return v.substring(0, end) + "…";
    }
}
