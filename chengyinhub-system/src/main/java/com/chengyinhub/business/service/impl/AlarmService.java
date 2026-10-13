package com.chengyinhub.business.service.impl;

import java.net.URL;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.core.redis.RedisCache;

/**
 * 监控告警(C3/资损可观测):关键失败(发奖丢奖、券超发、结算异常)除落 error 日志外,
 * 配了 webhook 则推送到钉钉/企微群,运营/风控实时可见。
 *
 * <p>配置:application.yml 加 alarm.webhook=<钉钉或企微机器人 webhook>;未配则仅记 error 日志(降级,不阻断)。
 * 用法:alarmService.alarm("发奖失败", "member=123 node=456 xp=12");
 *
 * <p>D12 P3-1A 投递闭环:
 * <ul>
 *   <li>主 webhook 投递成功(2xx + errcode=0)仅发送一次;</li>
 *   <li>非 2xx、连接异常、超时均判失败;</li>
 *   <li>主失败且配置 alarm.failure-webhook 时,只向独立 failure webhook 发一次失败事件,
 *       禁止递归调用主 webhook——failure 失败也只记日志、不能抛出;</li>
 *   <li>transport 使用 JDK HttpURLConnection,显式 connect/read timeout,限制响应体读取;</li>
 *   <li>不记录完整 webhook URL(仅 host)、不记录完整告警 detail;</li>
 *   <li>无论哪种结果都不向调用方抛异常——告警投递失败不能反过来阻断主流程。</li>
 * </ul>
 */
@Service
public class AlarmService {

    private static final Logger log = LoggerFactory.getLogger(AlarmService.class);

    /** 钉钉/企微机器人 webhook;未配置则仅落日志 */
    @Value("${alarm.webhook:}")
    private String webhook;

    /** 独立 failure webhook;主投递失败时只向此地址发送失败事件,禁止递归调用主 webhook */
    @Value("${alarm.failure-webhook:}")
    private String failureWebhook;

    /** transport 连接超时(毫秒) */
    @Value("${alarm.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    /** transport 读取超时(毫秒) */
    @Value("${alarm.read-timeout-ms:5000}")
    private int readTimeoutMs;

    private WebhookTransport transport;

    @Autowired
    private RedisCache redisCache;

    public AlarmService() {
        // transport 在 @PostConstruct 中根据注入的 timeout 配置创建
    }

    /** 仅供测试注入投递出口与 webhook;生产走无参构造 + {@code @Value} 注入。 */
    AlarmService(WebhookTransport transport, String webhook, String failureWebhook) {
        this.transport = transport;
        this.webhook = webhook;
        this.failureWebhook = failureWebhook;
    }

    @PostConstruct
    void initTransport() {
        if (this.transport == null) {
            this.transport = new HttpWebhookTransport(connectTimeoutMs, readTimeoutMs);
        }
    }

    /** 告警投递的终态,用于可观测与排查(成功/失败都是显式状态,不留「静默返回」)。 */
    enum AlarmDelivery {
        /** 未配置 webhook,按设计只落 error 日志 */
        SKIPPED_NOT_CONFIGURED,
        /** webhook 配置不是合法 http(s) 地址,fail-closed 不发送 */
        INVALID_TARGET,
        /** 第三方已接受 */
        SUCCESS,
        /** HTTP 2xx 但第三方业务明确拒绝(errcode 非 0) */
        REJECTED,
        /** 非 2xx / 网络失败 / 超时 / 其它异常 */
        FAILED
    }

    public void alarm(String title, String detail) {
        deliver(title, detail);
    }

    /** Limit attempts per fixed monitoring event for ten minutes, never claim delivery from a Redis lease. */
    public void alarmThrottled(String event, String title, String detail) {
        if (event == null || !event.matches("[a-z][a-z0-9_.-]{0,79}")) {
            throw new IllegalArgumentException("Invalid alarm event key");
        }
        try {
            Boolean claimed = redisCache.setIfAbsent("alarm:attempt:" + event, "1", 600, TimeUnit.SECONDS);
            if (Boolean.FALSE.equals(claimed)) {
                log.debug("[ALARM] Attempt rate limited event={}", event);
                return;
            }
            if (claimed == null) {
                log.warn("[ALARM] Attempt lease unknown event={}; falling back to delivery", event);
            }
        } catch (Exception failure) {
            log.warn("[ALARM] Attempt lease failed event={} type={}; falling back to delivery",
                    event, failure.getClass().getSimpleName());
        }
        alarm(title, detail);
    }

    AlarmDelivery deliver(String title, String detail) {
        log.error("[ALARM] {} (detail len={})", title, detail == null ? 0 : detail.length());
        if (StringUtils.isEmpty(webhook)) {
            return AlarmDelivery.SKIPPED_NOT_CONFIGURED;
        }
        String host = resolveHost(webhook);
        if (host == null) {
            log.error("[ALARM] 告警投递中止:alarm.webhook 不是合法的 http(s) 地址 title={}", title);
            return AlarmDelivery.INVALID_TARGET;
        }

        String payload = buildPayload(title, detail);
        TransportResponse response;
        try {
            response = transport.post(webhook, payload);
        } catch (Exception e) {
            log.error("[ALARM] 告警投递失败(transport 异常) title={} host={} cause={}",
                    title, host, e.getClass().getSimpleName());
            notifyFailure(title, detail);
            return AlarmDelivery.FAILED;
        }
        if (response == null) {
            log.error("[ALARM] 告警投递失败(transport 返回 null) title={} host={}",
                    title, host);
            notifyFailure(title, detail);
            return AlarmDelivery.FAILED;
        }

        if (!response.is2xx()) {
            log.error("[ALARM] 告警投递失败 title={} host={} status={}",
                    title, host, response.getStatusCode());
            notifyFailure(title, detail);
            return AlarmDelivery.FAILED;
        }

        String errcode = rejectionCode(response.getBody());
        if (errcode != null) {
            log.error("[ALARM] 告警投递被第三方拒绝 title={} host={} errcode={}",
                    title, host, errcode);
            notifyFailure(title, detail);
            return AlarmDelivery.REJECTED;
        }

        log.info("[ALARM] 告警投递已被第三方接受 delivered host={} title={}", host, title);
        return AlarmDelivery.SUCCESS;
    }

    /** 主 webhook 投递失败时,向独立 failure webhook 发送一次失败事件。不递归、不抛异常。 */
    private void notifyFailure(String title, String detail) {
        if (StringUtils.isEmpty(failureWebhook)) {
            return;
        }
        if (failureWebhook.equals(webhook)) {
            log.error("[ALARM] 失败事件跳过:failure webhook 与主相同,禁止递归");
            return;
        }
        String failureHost = resolveHost(failureWebhook);
        if (failureHost == null) {
            log.error("[ALARM] 失败事件跳过:failure webhook 不是合法的 http(s) 地址");
            return;
        }
        try {
            String failurePayload = "{\"msgtype\":\"text\",\"text\":{\"content\":"
                    + JSON.toJSONString("[城瘾告警投递失败] " + (title == null ? "" : title)
                    + "\n主 webhook 投递失败,请检查告警通道") + "}}";
            TransportResponse failureResponse = transport.post(failureWebhook, failurePayload);
            if (failureResponse == null) {
                log.error("[ALARM] 失败事件投递到独立出口失败(transport 返回 null) failureHost={}",
                        failureHost);
                return;
            }
            String errcode = failureResponse.is2xx() ? rejectionCode(failureResponse.getBody()) : null;
            if (errcode != null) {
                log.error("[ALARM] 失败事件投递到独立出口被第三方拒绝 failureHost={} errcode={}",
                        failureHost, errcode);
            } else if (failureResponse.is2xx()) {
                log.info("[ALARM] 失败事件已投递到独立出口 failureHost={}", failureHost);
            } else {
                log.error("[ALARM] 失败事件投递到独立出口失败 failureHost={} status={}",
                        failureHost, failureResponse.getStatusCode());
            }
        } catch (Exception e) {
            log.error("[ALARM] 失败事件投递到独立出口异常 failureHost={}", failureHost);
        }
    }

    private String buildPayload(String title, String detail) {
        String content = "[城瘾告警] " + (title == null ? "" : title) + "\n" + (detail == null ? "" : detail);
        return "{\"msgtype\":\"text\",\"text\":{\"content\":" + JSON.toJSONString(content) + "}}";
    }

    /**
     * 取「明确的业务拒绝码」:响应是 JSON 且 errcode 存在且非 0 才算拒绝。
     * 空响应 / 非 JSON / 无 errcode 一律不改判成功——这些第三方本来就不回 errcode。
     *
     * @return 拒绝码字符串,未被拒绝返回 null
     */
    private static String rejectionCode(String response) {
        if (StringUtils.isEmpty(response)) {
            return null;
        }
        JSONObject json;
        try {
            json = JSON.parseObject(response);
        } catch (Exception notJson) {
            return null;
        }
        if (json == null || !json.containsKey("errcode")) {
            return null;
        }
        String errcode = json.getString("errcode");
        if (StringUtils.isEmpty(errcode) || "0".equals(errcode)) {
            return null;
        }
        return errcode;
    }

    /** 解析并校验投递目标,只接受 http(s);顺带产出脱敏 host(不含 path/query,即不含 access_token)。 */
    private static String resolveHost(String rawWebhook) {
        try {
            URL target = new URL(rawWebhook);
            String protocol = target.getProtocol();
            if (!"http".equals(protocol) && !"https".equals(protocol)) {
                return null;
            }
            if (StringUtils.isEmpty(target.getHost())) {
                return null;
            }
            return target.getPort() < 0
                    ? target.getHost() : target.getHost() + ":" + target.getPort();
        } catch (Exception invalid) {
            return null;
        }
    }

}
