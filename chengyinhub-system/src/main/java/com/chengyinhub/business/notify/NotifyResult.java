package com.chengyinhub.business.notify;

/**
 * 通知通道的可观测结果。接受、站内落库、未知和明确失败必须分开；任何状态都不代表用户已读。
 */
public final class NotifyResult {
    public enum Status { IN_APP_STORED, CHANNEL_ACCEPTED, UNKNOWN, FAILED }

    private final String channel;
    private final Status status;
    private final String providerCode;

    private NotifyResult(String channel, Status status, String providerCode) {
        this.channel = channel;
        this.status = status;
        this.providerCode = providerCode;
    }

    public static NotifyResult of(String channel, Status status, String providerCode) {
        return new NotifyResult(channel, status, providerCode);
    }

    public static NotifyResult accepted(String channel) {
        return of(channel, Status.CHANNEL_ACCEPTED, "0");
    }

    public static NotifyResult failed(String channel, String providerCode) {
        return of(channel, Status.FAILED, providerCode);
    }

    public static NotifyResult unknown(String channel) {
        return of(channel, Status.UNKNOWN, null);
    }

    public String getChannel() { return channel; }
    public Status getStatus() { return status; }
    public String getProviderCode() { return providerCode; }

    /** 兼容旧 boolean：仅本地已落库或渠道明确接受为 true，不表示设备送达/用户已读。 */
    public boolean isConfirmed() {
        return status == Status.IN_APP_STORED || status == Status.CHANNEL_ACCEPTED;
    }
}
