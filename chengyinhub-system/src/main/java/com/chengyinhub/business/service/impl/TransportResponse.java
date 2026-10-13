package com.chengyinhub.business.service.impl;

/**
 * Webhook transport 的单次投递结果:显式携带 HTTP 状态码与响应体,
 * 让 AlarmService 可以在不依赖异常语义的前提下判定投递成功/拒绝/失败。
 */
public class TransportResponse {

    private final int statusCode;
    private final String body;

    public TransportResponse(int statusCode, String body) {
        this.statusCode = statusCode;
        this.body = body;
    }

    /** HTTP 状态码;连接/超时失败固定为 -1。 */
    public int getStatusCode() {
        return statusCode;
    }

    /** 响应体,可能为空;受 read cap 限制。 */
    public String getBody() {
        return body;
    }

    /** 2xx 判成功 */
    public boolean is2xx() {
        return statusCode >= 200 && statusCode < 300;
    }
}
