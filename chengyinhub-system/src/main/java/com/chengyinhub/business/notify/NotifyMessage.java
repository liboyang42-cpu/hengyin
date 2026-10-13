package com.chengyinhub.business.notify;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条通知的**语义**载荷:事件 + 收件人 + 业务字段。
 *
 * ⚠️ 这里的字段是业务语义(topicName / amount / fromName),**不是任何通道的格式**。
 * 微信的 thing1/amount1、push 的 title/body,都由各自通道从这些语义字段翻译过去 ——
 * 通道格式一旦漏进这一层,解耦就白做了。
 */
public final class NotifyMessage {

    private final NotifyEvent event;
    private final Long memberId;
    private final Map<String, String> fields = new LinkedHashMap<>();

    private NotifyMessage(NotifyEvent event, Long memberId) {
        this.event = event;
        this.memberId = memberId;
    }

    public static NotifyMessage of(NotifyEvent event, Long memberId) {
        return new NotifyMessage(event, memberId);
    }

    /** null 值不入表:通道取不到时各自兜底,比塞一个 "null" 字符串安全。 */
    public NotifyMessage with(String key, String value) {
        if (value != null) fields.put(key, value);
        return this;
    }

    public NotifyMessage with(String key, int value) {
        fields.put(key, String.valueOf(value));
        return this;
    }

    public NotifyEvent event() {
        return event;
    }

    public Long memberId() {
        return memberId;
    }

    /** 取字段;缺失返回空串而不是 null —— 通道侧全是字符串拼装,少一层判空。 */
    public String get(String key) {
        String v = fields.get(key);
        return v == null ? "" : v;
    }

    /**
     * 取整数字段。只有 {@link #with(String, int)} 能写入,所以这里不做 parse 异常兜底 ——
     * 那种兜底防不到任何真实输入,却会把「走了 3200 米」静默变成「走了 0 米」。
     */
    public int getInt(String key, int defaultValue) {
        String v = fields.get(key);
        return (v == null || v.isEmpty()) ? defaultValue : Integer.parseInt(v);
    }

}
