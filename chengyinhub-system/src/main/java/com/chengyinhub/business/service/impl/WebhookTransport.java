package com.chengyinhub.business.service.impl;

/**
 * 告警 webhook 的投递出口 seam:返回 TransportResponse(含 HTTP 状态码),
 * 不再用异常语义表达非 2xx。生产实现为 {@link HttpWebhookTransport},
 * 测试为 fake transport(不触网)。
 */
public interface WebhookTransport {

    /**
     * POST 一条 JSON 消息到指定 webhook。
     *
     * @return 投递结果(含状态码与响应体);连接/超时失败时 statusCode=-1
     */
    TransportResponse post(String url, String jsonBody);
}
