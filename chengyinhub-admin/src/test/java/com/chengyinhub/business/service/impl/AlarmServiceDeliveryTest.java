package com.chengyinhub.business.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.service.impl.AlarmService.AlarmDelivery;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D12 P3-1A 告警投递闭环契约:区分「调用返回」与「第三方业务接受」;
 * 主 webhook 失败 → 独立 failure webhook(禁止递归);transport 带显式超时与状态码。
 *
 * <p>纯逻辑,不联外部 webhook、不发真实告警——投递出口由 {@link WebhookTransport} 桩注入。
 */
class AlarmServiceDeliveryTest {

    /** 带凭据的 webhook,任何日志里都不允许出现 access_token 值 */
    private static final String SECRET = "SECRET_TOKEN_9f3a";
    private static final String WEBHOOK =
            "https://oapi.dingtalk.com/robot/send?access_token=" + SECRET;
    private static final String FAILURE_WEBHOOK =
            "https://hooks.example.com/failure?channel=alerts";

    /** 记录桩被调用的次数与每次调用参数 */
    private static final class TransportStub implements WebhookTransport {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> urls = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        private final TransportResponse fixedResponse;
        private int[] statusCodes;
        private String[] responseBodies;
        private int sequenceIndex;

        TransportStub(int statusCode, String body) {
            this.fixedResponse = new TransportResponse(statusCode, body);
            this.statusCodes = null;
            this.responseBodies = null;
        }

        /** 多步 stub:按调用次序依次返回不同的 (statusCode, body) */
        TransportStub(int[] statusCodes, String[] responseBodies) {
            this.fixedResponse = null;
            this.statusCodes = statusCodes;
            this.responseBodies = responseBodies;
        }

        @Override
        public TransportResponse post(String url, String jsonBody) {
            calls.incrementAndGet();
            urls.add(url);
            bodies.add(jsonBody);
            if (fixedResponse != null) {
                return fixedResponse;
            }
            int idx = sequenceIndex++;
            if (idx >= statusCodes.length) {
                return new TransportResponse(-1, null);
            }
            return new TransportResponse(statusCodes[idx], responseBodies[idx]);
        }
    }

    /** 模拟 transport 抛异常(非正常返回 TransportResponse) */
    private static final class ThrowingTransport implements WebhookTransport {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> urls = new ArrayList<>();
        private final RuntimeException toThrow;

        ThrowingTransport(RuntimeException toThrow) {
            this.toThrow = toThrow;
        }

        @Override
        public TransportResponse post(String url, String jsonBody) {
            calls.incrementAndGet();
            urls.add(url);
            throw toThrow;
        }
    }

    /** 模拟 transport 返回 null */
    private static final class NullResponseTransport implements WebhookTransport {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public TransportResponse post(String url, String jsonBody) {
            calls.incrementAndGet();
            return null;
        }
    }

    /** 捕获 AlarmService 自己打的日志 */
    private static List<ILoggingEvent> capture(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(AlarmService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Level original = logger.getLevel();
        logger.setLevel(Level.INFO);
        logger.addAppender(logs);
        try {
            action.run();
        } finally {
            logger.detachAppender(logs);
            logger.setLevel(original);
        }
        return logs.list;
    }

    private static AlarmService serviceWith(String webhook, String failureWebhook,
                                             WebhookTransport transport) {
        return new AlarmService(transport, webhook, failureWebhook);
    }

    private static AlarmService serviceWith(String webhook, WebhookTransport transport) {
        return new AlarmService(transport, webhook, null);
    }

    // ============================================================
    // 1) 未配置
    // ============================================================

    @Test
    void notConfigured_onlyLogsError_neverDelivers() {
        TransportStub stub = new TransportStub(200, "{\"errcode\":0}");
        AlarmService service = serviceWith("", null, stub);

        List<ILoggingEvent> logs = capture(() ->
                assertEquals(AlarmDelivery.SKIPPED_NOT_CONFIGURED,
                        service.deliver("发奖失败", "member=1")));

        assertEquals(0, stub.calls.get(), "未配置 webhook 时绝不允许发起投递");
        assertTrue(logs.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("[ALARM]")
                        && e.getFormattedMessage().contains("发奖失败")),
                "未配置 webhook 仍必须落 [ALARM] error 日志(可观测底线)");
    }

    // ============================================================
    // 2) 2xx + 业务接受
    // ============================================================

    @Test
    void httpOk_errcodeZero_isSuccess_andLogsSuccess() {
        TransportStub stub = new TransportStub(200, "{\"errcode\":0,\"errmsg\":\"ok\"}");
        AlarmService service = serviceWith(WEBHOOK, null, stub);

        List<ILoggingEvent> logs = capture(() ->
                assertEquals(AlarmDelivery.SUCCESS, service.deliver("券超发", "coupon=7")));

        assertEquals(1, stub.calls.get());
        assertTrue(logs.stream().anyMatch(e -> e.getFormattedMessage().contains("delivered")),
                "投递成功必须有可观察的成功日志");
    }

    @Test
    void httpOk_emptyBody_isSuccess() {
        assertEquals(AlarmDelivery.SUCCESS,
                serviceWith(WEBHOOK, new TransportStub(200, "")).deliver("t", "d"));
        assertEquals(AlarmDelivery.SUCCESS,
                serviceWith(WEBHOOK, new TransportStub(200, null)).deliver("t", "d"));
    }

    @Test
    void httpOk_bodyWithoutErrcode_isSuccess() {
        assertEquals(AlarmDelivery.SUCCESS,
                serviceWith(WEBHOOK, new TransportStub(200, "{\"ok\":true}")).deliver("t", "d"));
        assertEquals(AlarmDelivery.SUCCESS,
                serviceWith(WEBHOOK, new TransportStub(200, "<html>ok</html>")).deliver("t", "d"));
    }

    // ============================================================
    // 3) 2xx + 业务拒绝 → main FAILED + failure webhook 被调
    // ============================================================

    @Test
    void httpOk_errcodeNonZero_isRejected_andReasonIsObservable() {
        TransportStub stub = new TransportStub(200,
                "{\"errcode\":310000,\"errmsg\":\"invalid webhook keyword\"}");
        AlarmService service = serviceWith(WEBHOOK, null, stub);

        List<ILoggingEvent> logs = capture(() ->
                assertEquals(AlarmDelivery.REJECTED, service.deliver("结算异常", "order=9"),
                        "HTTP 2xx 但 errcode 非 0 = 第三方没有接受,必须判拒绝"));

        assertTrue(logs.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("310000")),
                "业务拒绝的 errcode 必须可用于排查");
    }

    @Test
    void httpOk_errcodeNonZero_asString_isRejected() {
        assertEquals(AlarmDelivery.REJECTED,
                serviceWith(WEBHOOK, new TransportStub(200, "{\"errcode\":\"93000\"}"))
                        .deliver("t", "d"));
    }

    // ============================================================
    // 4) 非 2xx / 连接异常 / 超时 → main FAILED
    // ============================================================

    @Test
    void httpNonSuccessStatus_isFailed() {
        AlarmService service = serviceWith(WEBHOOK, new TransportStub(500, "Internal Error"));

        List<ILoggingEvent> logs = capture(() ->
                assertEquals(AlarmDelivery.FAILED, service.deliver("t", "d")));

        assertTrue(logs.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("500")),
                "非 2xx 的 status 必须落进失败日志");
    }

    @Test
    void transportConnectionFailure_isFailed() {
        AlarmService service = serviceWith(WEBHOOK, new TransportStub(-1, null));

        List<ILoggingEvent> logs = capture(() ->
                assertEquals(AlarmDelivery.FAILED, service.deliver("t", "d")));

        assertTrue(logs.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("-1")),
                "连接失败(statusCode=-1)必须落进失败日志");
    }

    // ============================================================
    // 5) 主失败 → 独立 failure webhook 被调用(仅一次)
    // ============================================================

    @Test
    void mainFailed_triggersFailureWebhook_once() {
        TransportStub stub = new TransportStub(
                new int[]{500, 200},
                new String[]{"Internal Error", "{\"errcode\":0}"});
        AlarmService service = serviceWith(WEBHOOK, FAILURE_WEBHOOK, stub);

        List<ILoggingEvent> logs = capture(() ->
                assertEquals(AlarmDelivery.FAILED, service.deliver("t", "d")));

        assertEquals(2, stub.calls.get(), "主失败后应仅对 failure webhook 再发一次");
        assertEquals(WEBHOOK, stub.urls.get(0), "第一调用必须是主 webhook");
        assertEquals(FAILURE_WEBHOOK, stub.urls.get(1), "第二调用必须是 failure webhook");

        assertTrue(logs.stream().anyMatch(e ->
                        e.getFormattedMessage().contains("失败事件已投递到独立出口")),
                "failure webhook 成功必须有可观察日志");
    }

    @Test
    void mainRejected_triggersFailureWebhook_once() {
        TransportStub stub = new TransportStub(
                new int[]{200, 200},
                new String[]{"{\"errcode\":310000}", "{\"errcode\":0}"});
        AlarmService service = serviceWith(WEBHOOK, FAILURE_WEBHOOK, stub);

        assertEquals(AlarmDelivery.REJECTED, service.deliver("结算异常", "order=9"));
        assertEquals(2, stub.calls.get(), "主被拒绝后应仅对 failure webhook 再发一次");
        assertEquals(WEBHOOK, stub.urls.get(0));
        assertEquals(FAILURE_WEBHOOK, stub.urls.get(1));
    }

    @Test
    void mainConnectionFailure_triggersFailureWebhook() {
        TransportStub stub = new TransportStub(
                new int[]{-1, 200},
                new String[]{null, "{\"errcode\":0}"});
        AlarmService service = serviceWith(WEBHOOK, FAILURE_WEBHOOK, stub);

        assertEquals(AlarmDelivery.FAILED, service.deliver("t", "d"));
        assertEquals(2, stub.calls.get());
        assertTrue(stub.bodies.get(1).contains("城瘾告警投递失败"),
                "failure 事件 body 应包含标识前缀");
    }

    // ============================================================
    // 6) failure webhook 自己也失败 → 仅记日志,不抛异常,不递归
    // ============================================================

    @Test
    void failureWebhookFails_noExceptionAndNoRecursion() {
        TransportStub stub = new TransportStub(
                new int[]{500, 503},
                new String[]{"Internal Error", "Service Unavailable"});
        AlarmService service = serviceWith(WEBHOOK, FAILURE_WEBHOOK, stub);

        List<ILoggingEvent> logs = capture(() ->
                assertEquals(AlarmDelivery.FAILED, service.deliver("t", "d")));

        assertEquals(2, stub.calls.get(), "应该调用两次:主 + failure,不会递归");
        assertTrue(logs.stream().anyMatch(e ->
                        e.getFormattedMessage().contains("失败事件投递到独立出口失败")),
                "failure webhook 失败应记录可观察日志");
    }

    @Test
    void failureWebhookConnectionFailure_noExceptionAndNoRecursion() {
        TransportStub stub = new TransportStub(
                new int[]{500, -1},
                new String[]{"Internal Error", null});
        AlarmService service = serviceWith(WEBHOOK, FAILURE_WEBHOOK, stub);

        List<ILoggingEvent> logs = capture(() ->
                assertEquals(AlarmDelivery.FAILED, service.deliver("t", "d")));

        assertEquals(2, stub.calls.get());
        assertTrue(logs.stream().anyMatch(e ->
                        e.getFormattedMessage().contains("失败事件投递到独立出口失败")),
                "failure webhook 连接失败也应记录日志,不抛异常");
    }

    @Test
    void failureWebhookBusinessRejection_isNotReportedAsDelivered() {
        for (String body : List.of("{\"errcode\":93000}", "{\"errcode\":\"93000\"}")) {
            TransportStub stub = new TransportStub(new int[]{500, 200}, new String[]{"err", body});
            List<ILoggingEvent> logs = capture(() -> assertEquals(AlarmDelivery.FAILED,
                    serviceWith(WEBHOOK, FAILURE_WEBHOOK, stub).deliver("t", "d")));
            assertEquals(2, stub.calls.get(), "备出口拒绝不能递归重发");
            assertTrue(logs.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                    && e.getFormattedMessage().contains("独立出口被第三方拒绝")
                    && e.getFormattedMessage().contains("93000")));
            assertFalse(logs.stream().anyMatch(e -> e.getFormattedMessage().contains("失败事件已投递")));
        }
    }

    @Test
    void failureWebhookAcceptedBodyRules_matchMainWebhook() {
        for (String body : new String[]{null, "", "<html>ok</html>", "{\"ok\":true}", "{\"errcode\":0}"}) {
            TransportStub stub = new TransportStub(new int[]{500, 200}, new String[]{"err", body});
            List<ILoggingEvent> logs = capture(() -> assertEquals(AlarmDelivery.FAILED,
                    serviceWith(WEBHOOK, FAILURE_WEBHOOK, stub).deliver("t", "d")));
            assertEquals(2, stub.calls.get());
            assertTrue(logs.stream().anyMatch(e -> e.getFormattedMessage().contains("失败事件已投递到独立出口")));
        }
    }

    // ============================================================
    // 7) 无 failure webhook → 仅主失败日志,不额外调用
    // ============================================================

    @Test
    void noFailureWebhook_onlyMainFailureLog_noExtraCall() {
        TransportStub stub = new TransportStub(500, "Internal Error");
        AlarmService service = serviceWith(WEBHOOK, null, stub);

        List<ILoggingEvent> logs = capture(() ->
                assertEquals(AlarmDelivery.FAILED, service.deliver("t", "d")));

        assertEquals(1, stub.calls.get(), "无 failure webhook 时只调主一次");
        assertTrue(logs.stream().anyMatch(e -> e.getFormattedMessage().contains("[ALARM]")),
                "必须保留主 [ALARM] 日志");
    }

    // ============================================================
    // 8) alarm() 永不抛异常(主流程健壮性)
    // ============================================================

    @Test
    void alarm_neverThrows_regardlessOfTransportFailure() {
        AlarmService service = serviceWith(WEBHOOK, FAILURE_WEBHOOK,
                new TransportStub(-1, null));
        service.alarm("t", "d");

        AlarmService noFailure = serviceWith(WEBHOOK, null,
                new TransportStub(-1, null));
        noFailure.alarm("t", "d");
    }

    // ============================================================
    // 9) 无效目标 fail-closed
    // ============================================================

    @Test
    void malformedUrl_failsClosed_withoutSending() {
        TransportStub stub = new TransportStub(200, "{\"errcode\":0}");
        AlarmService service = serviceWith("not-a-url", null, stub);

        assertEquals(AlarmDelivery.INVALID_TARGET, service.deliver("t", "d"));
        assertEquals(0, stub.calls.get(), "URL 无法解析时绝不允许发出请求");
    }

    @Test
    void nonHttpScheme_failsClosed_withoutSending() {
        TransportStub stub = new TransportStub(200, "{\"errcode\":0}");
        AlarmService service = serviceWith("ftp://evil.example.com/hook", null, stub);

        assertEquals(AlarmDelivery.INVALID_TARGET, service.deliver("t", "d"));
        assertEquals(0, stub.calls.get(), "非 http(s) 目标绝不允许发出请求");

        TransportStub hostless = new TransportStub(200, "{\"errcode\":0}");
        assertEquals(AlarmDelivery.INVALID_TARGET,
                serviceWith("file:///etc/passwd", null, hostless).deliver("t", "d"));
        assertEquals(0, hostless.calls.get(), "无 host 的目标绝不允许发出请求");
    }

    // ============================================================
    // 10) 敏感信息不落日志(URL/token/body)
    // ============================================================

    @Test
    void secretsNeverAppearInLogs_onAnyOutcome() {
        String secretResponse = "{\"errcode\":310000,\"errmsg\":\"leak " + SECRET + "\"}";
        List<ILoggingEvent> all = new ArrayList<>();

        all.addAll(capture(() -> serviceWith(WEBHOOK, null, new TransportStub(200, secretResponse))
                .deliver("t", "d")));
        all.addAll(capture(() -> serviceWith(WEBHOOK, FAILURE_WEBHOOK, new TransportStub(500, "boom"))
                .deliver("t", "d")));
        all.addAll(capture(() -> serviceWith(WEBHOOK, null, new TransportStub(200, "{\"errcode\":0}"))
                .deliver("t", "d")));

        for (ILoggingEvent event : all) {
            String rendered = event.getFormattedMessage();
            assertFalse(rendered.contains(SECRET),
                    "日志泄露了 webhook 凭据: " + rendered);
            assertFalse(rendered.contains("access_token"),
                    "日志泄露了完整 webhook: " + rendered);
        }
        assertTrue(all.stream().anyMatch(e ->
                        e.getFormattedMessage().contains("oapi.dingtalk.com")),
                "应记录脱敏 host 以便定位投递目标");
    }

    @Test
    void failureWebhookUrl_notLogged() {
        TransportStub stub = new TransportStub(
                new int[]{500, 200},
                new String[]{"err", "{\"errcode\":0}"});
        AlarmService service = serviceWith(WEBHOOK, FAILURE_WEBHOOK, stub);

        List<ILoggingEvent> logs = capture(() ->
                service.deliver("t", "d"));

        for (ILoggingEvent event : logs) {
            String rendered = event.getFormattedMessage();
            assertFalse(rendered.contains("/failure"),
                    "failure webhook 完整路径不应出现在日志: " + rendered);
        }
    }

    @Test
    void transportExceptionMessage_withSecret_notLeaked() {
        AlarmService service = serviceWith(WEBHOOK, null,
                new ThrowingTransport(new RuntimeException(
                        "connect to https://oapi.dingtalk.com/robot/send?access_token=" + SECRET)));

        List<ILoggingEvent> logs = capture(() ->
                service.deliver("t", "d"));

        for (ILoggingEvent event : logs) {
            String rendered = event.getFormattedMessage();
            assertFalse(rendered.contains(SECRET),
                    "transport 异常消息泄露了 SECRET: " + rendered);
            assertFalse(rendered.contains("access_token"),
                    "transport 异常消息泄露了完整 webhook: " + rendered);
        }
    }

    // ============================================================
    // 11) deliver() 内 transport 抛异常/null 响应 → alarm() 永不抛异常
    // ============================================================

    @Test
    void transportThrowsInDeliver_alarmNeverThrows() {
        AlarmService service = serviceWith(WEBHOOK, null,
                new ThrowingTransport(new RuntimeException("network error")));

        List<ILoggingEvent> logs = capture(() -> {
            AlarmDelivery result = service.deliver("结算异常", "order=99");
            assertEquals(AlarmDelivery.FAILED, result);
        });

        assertTrue(logs.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("告警投递失败")),
                "transport 抛异常应落 FAILED 日志");
        service.alarm("t", "d");
    }

    @Test
    void transportThrowsInDeliver_withFailureWebhook_noException() {
        AlarmService service = serviceWith(WEBHOOK, FAILURE_WEBHOOK,
                new ThrowingTransport(new RuntimeException("network error")));

        service.alarm("t", "d");
    }

    @Test
    void transportReturnsNullInDeliver_alarmNeverThrows() {
        AlarmService service = serviceWith(WEBHOOK, null,
                new NullResponseTransport());

        List<ILoggingEvent> logs = capture(() -> {
            AlarmDelivery result = service.deliver("券超发", "coupon=7");
            assertEquals(AlarmDelivery.FAILED, result);
        });

        assertTrue(logs.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("告警投递失败")),
                "transport 返回 null 应落 FAILED 日志");
        service.alarm("t", "d");
    }

    // ============================================================
    // 12) [ALARM] 日志不记录任何 detail 原文(仅 title + len)
    // ============================================================

    @Test
    void primaryAlarmLog_onlyTitleAndLen_notDetailText() {
        TransportStub stub = new TransportStub(200, "{\"errcode\":0}");
        AlarmService service = serviceWith(WEBHOOK, null, stub);

        String longDetail = "member=" + "x".repeat(600) + "extra";

        List<ILoggingEvent> logs = capture(() ->
                service.deliver("发奖失败", longDetail));

        assertTrue(logs.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("[ALARM]")),
                "主 [ALARM] 日志不能丢失");
        assertTrue(logs.stream().anyMatch(e -> e.getFormattedMessage().contains("detail len=")),
                "主 [ALARM] 必须记录 detail 长度");
        for (ILoggingEvent event : logs) {
            String rendered = event.getFormattedMessage();
            assertFalse(rendered.contains(longDetail),
                    "detail 原文(长)不应出现在日志: " + rendered);
        }
    }

    @Test
    void primaryAlarmLog_shortDetail_alsoNotLogged() {
        TransportStub stub = new TransportStub(200, "{\"errcode\":0}");
        AlarmService service = serviceWith(WEBHOOK, null, stub);

        String shortDetail = "phone=13800138000,amount=9999";

        List<ILoggingEvent> logs = capture(() ->
                service.deliver("结算异常", shortDetail));

        for (ILoggingEvent event : logs) {
            String rendered = event.getFormattedMessage();
            assertFalse(rendered.contains(shortDetail),
                    "detail 原文(短)也不应出现在日志: " + rendered);
        }
    }

    @Test
    void deliveryFailureLog_doesNotContainFullDetail() {
        TransportStub stub = new TransportStub(500, "Internal Error");
        AlarmService service = serviceWith(WEBHOOK, null, stub);

        String sensitiveDetail = "phone=13800138000,amount=9999,account=secret123";

        List<ILoggingEvent> logs = capture(() ->
                service.deliver("t", sensitiveDetail));

        for (ILoggingEvent event : logs) {
            String rendered = event.getFormattedMessage();
            assertFalse(rendered.contains(sensitiveDetail),
                    "任何日志均不应包含 detail 原文: " + rendered);
        }
    }

    // ============================================================
    // 13) failure webhook 非法/同主地址 → fail-closed
    // ============================================================

    @Test
    void failureWebhook_equalsMainWebhook_isSkipped() {
        TransportStub stub = new TransportStub(
                new int[]{500, 200},
                new String[]{"err", "{\"errcode\":0}"});
        AlarmService service = serviceWith(WEBHOOK, WEBHOOK, stub);

        List<ILoggingEvent> logs = capture(() ->
                assertEquals(AlarmDelivery.FAILED, service.deliver("t", "d")));

        assertEquals(1, stub.calls.get(),
                "failure webhook 与主相同时,不允许投递 failure 事件(防递归)");
        assertTrue(logs.stream().anyMatch(e ->
                        e.getFormattedMessage().contains("failure webhook 与主相同")),
                "必须有日志说明跳过了 failure webhook");
    }

    @Test
    void failureWebhook_invalidUrl_isSkipped() {
        TransportStub stub = new TransportStub(500, "err");
        AlarmService service = serviceWith(WEBHOOK, "not-a-url", stub);

        List<ILoggingEvent> logs = capture(() ->
                assertEquals(AlarmDelivery.FAILED, service.deliver("t", "d")));

        assertEquals(1, stub.calls.get(),
                "非法 failure webhook 不应触发投递");
        assertTrue(logs.stream().anyMatch(e ->
                        e.getFormattedMessage().contains("failure webhook 不是合法的")),
                "必须有日志说明跳过了非法 failure webhook");
    }

    @Test
    void failureWebhook_nonHttpScheme_isSkipped() {
        TransportStub stub = new TransportStub(500, "err");
        AlarmService service = serviceWith(WEBHOOK, "ftp://evil.example.com/hook", stub);

        List<ILoggingEvent> logs = capture(() ->
                assertEquals(AlarmDelivery.FAILED, service.deliver("t", "d")));

        assertEquals(1, stub.calls.get());
        assertTrue(logs.stream().anyMatch(e ->
                        e.getFormattedMessage().contains("failure webhook 不是合法的")),
                "非 http(s) 的 failure webhook 不应触发投递");
    }

    // ============================================================
    // 14) JSON 转义不回归
    // ============================================================

    @Test
    void payloadStaysValidJson_withQuotesAndNewlines() {
        TransportStub stub = new TransportStub(200, "{\"errcode\":0}");
        AlarmService service = serviceWith(WEBHOOK, null, stub);

        service.deliver("发\"奖\"失败", "member=1\nnode=2\\x");

        JSONObject payload = JSON.parseObject(stub.bodies.get(0));
        String content = payload.getJSONObject("text").getString("content");
        assertTrue(content.contains("发\"奖\"失败"), "标题引号必须被正确转义: " + content);
        assertTrue(content.contains("member=1\nnode=2\\x"), "明细换行/反斜杠必须保真: " + content);
        assertEquals("text", payload.getString("msgtype"));
    }

    @Test
    void mainAndFailurePayloads_escapeEveryJsonControlCharacter() {
        StringBuilder controls = new StringBuilder();
        for (char character = 0; character < 32; character++) controls.append(character);
        String title = "异常\"\\" + controls;
        String detail = "明细\"\\" + controls;
        TransportStub stub = new TransportStub(new int[]{500, 200}, new String[]{"err", "{\"errcode\":0}"});
        assertEquals(AlarmDelivery.FAILED, serviceWith(WEBHOOK, FAILURE_WEBHOOK, stub).deliver(title, detail));
        assertEquals(2, stub.calls.get());
        for (String body : stub.bodies) {
            assertFalse(body.chars().anyMatch(character -> character < 32), "JSON 字符串内不能含原始控制字符");
            assertEquals("text", JSON.parseObject(body).getString("msgtype"));
        }
        assertEquals("[城瘾告警] " + title + "\n" + detail,
                JSON.parseObject(stub.bodies.get(0)).getJSONObject("text").getString("content"));
        assertEquals("[城瘾告警投递失败] " + title + "\n主 webhook 投递失败,请检查告警通道",
                JSON.parseObject(stub.bodies.get(1)).getJSONObject("text").getString("content"));
    }
}
