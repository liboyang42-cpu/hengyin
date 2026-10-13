package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.UmsComment;
import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.domain.RoamStamp;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.WxContentCallbackInbox;
import com.chengyinhub.business.mapper.ImMessageMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.mapper.RoamStampMapper;
import com.chengyinhub.business.service.IUmsCommentService;
import com.chengyinhub.business.service.IUmsCreativeSquareService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.IWxContentCallbackInboxService;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.config.WxPayConfig;
import com.chengyinhub.system.service.ISysConfigService;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.slf4j.LoggerFactory;

/**
 * 图片内容安全异步回调 handleMediaCheckResult 的 IM 闭环。
 * IM 图片用裸 checkMediaAsync 送检, 不写 redis 映射, 只把 trace_id 落库到 im_message。
 * 因此回调命中违规时, 必须按 trace_id 反查消息并下架(status=BLOCKED, selectPage 已过滤)。
 */
class WxContentSecurityServiceImplTest {

    private static class ReviewResponseService extends WxContentSecurityServiceImpl {
        @Override
        String sendPost(String url, String body, String contentType) {
            return "{\"errcode\":0,\"result\":{\"suggest\":\"review\"}}";
        }
    }

    private static class MissingSuggestResponseService extends WxContentSecurityServiceImpl {
        @Override
        String sendPost(String url, String body, String contentType) {
            return "{\"errcode\":0,\"result\":{}}";
        }
    }

    private static class ThrowingResponseService extends WxContentSecurityServiceImpl {
        @Override
        String sendPost(String url, String body, String contentType) {
            throw new RuntimeException("access_token=leak https://private/media.jpg trace-secret");
        }
    }

    private static class OperationalErrorResponseService extends WxContentSecurityServiceImpl {
        @Override
        String sendPost(String url, String body, String contentType) {
            return "{\"errcode\":45009,\"errmsg\":\"reach max api daily quota limit\"}";
        }
    }

    /** 生产 2026-09-18 实证:商家据点玩法传空 openid,微信回 40003。 */
    private static class InvalidOpenidResponseService extends WxContentSecurityServiceImpl {
        @Override
        String sendPost(String url, String body, String contentType) {
            return "{\"errcode\":40003,\"errmsg\":\"invalid openid\"}";
        }
    }

    /** 非调用方错误的未知接口错误,仍应走「可重试繁忙」,不能被 40003 的分支误收。 */
    private static class UnknownErrorResponseService extends WxContentSecurityServiceImpl {
        @Override
        String sendPost(String url, String body, String contentType) {
            return "{\"errcode\":50001,\"errmsg\":\"unknown\"}";
        }
    }

    @Test
    void tokenConfigurationFailuresAreUnavailableButTransientFailuresRemainRetryable() {
        for (int errcode : new int[] {40125, 40013, 40164, -1, 45009}) {
            WxContentSecurityServiceImpl service = enabledTextService(new WxContentSecurityServiceImpl() {
                @Override
                String sendPost(String url, String body, String contentType) {
                    if (url.endsWith("/stable_token")) {
                        return "{\"errcode\":" + errcode + ",\"errmsg\":\"private-upstream-detail\"}";
                    }
                    throw new AssertionError("无 token 不得发起审核");
                }
            });
            ReflectionTestUtils.setField(service, "redisCache", mock(RedisCache.class));
            ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                    LoggerFactory.getLogger(WxContentSecurityServiceImpl.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                assertEquals(errcode == -1 || errcode == 45009
                        ? IWxContentSecurityService.MSG_BUSY : IWxContentSecurityService.MSG_UNAVAILABLE,
                        service.checkText("正常内容", "openid"));
                assertEquals("", service.getAccessToken());
                assertEquals("", service.refreshAccessToken());
                assertTrue(appender.list.stream().anyMatch(event ->
                        event.getFormattedMessage().contains("errcode=" + errcode)));
                assertFalse(appender.list.stream().anyMatch(event ->
                        event.getFormattedMessage().contains("private-upstream-detail")));
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
        }
    }

    @Test
    void contentTokenUsesStablePostWithoutGlobalForceRefreshOrSecretInUrl() {
        WxContentSecurityServiceImpl service = enabledTextService(new WxContentSecurityServiceImpl() {
            @Override
            String sendGet(String url) { throw new AssertionError("Token must use POST"); }
            @Override
            String sendPost(String url, String body, String contentType) {
                assertEquals("https://api.weixin.qq.com/cgi-bin/stable_token", url);
                assertFalse(url.contains("secret="));
                assertTrue(body.contains("\"force_refresh\":false"));
                assertEquals("application/json", contentType);
                return "{\"access_token\":\"stable-test-token\",\"expires_in\":7200}";
            }
        });
        ReflectionTestUtils.setField(service, "redisCache", mock(RedisCache.class));
        assertEquals("stable-test-token", service.getAccessToken());
        assertEquals("stable-test-token", service.refreshAccessToken());
    }

    private WxContentSecurityServiceImpl enabledTextService(WxContentSecurityServiceImpl service) {
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.getCacheObject("wx:mini:access_token")).thenReturn("cached-token");
        WxPayConfig wxConfig = mock(WxPayConfig.class);
        when(wxConfig.isSecCheckEnabled()).thenReturn(true);
        when(wxConfig.getMiniAppId()).thenReturn("appid");
        when(wxConfig.getMiniSecret()).thenReturn("secret");
        ReflectionTestUtils.setField(service, "redisCache", redisCache);
        ReflectionTestUtils.setField(service, "wxConfig", wxConfig);
        return service;
    }

    private WxContentSecurityServiceImpl service(RedisCache redisCache, ImMessageMapper imMessageMapper) {
        WxContentSecurityServiceImpl service = new WxContentSecurityServiceImpl();
        ReflectionTestUtils.setField(service, "redisCache", redisCache);
        ReflectionTestUtils.setField(service, "imMessageMapper", imMessageMapper);
        IWxContentCallbackInboxService inbox = mock(IWxContentCallbackInboxService.class);
        when(inbox.isRegistered(anyString())).thenAnswer(invocation ->
                testInboxRow(redisCache, imMessageMapper, invocation.getArgument(0, String.class)) != null);
        when(inbox.process(anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(IWxContentCallbackInboxService.Mutation.class))).thenAnswer(invocation -> {
                    WxContentCallbackInbox row = testInboxRow(
                            redisCache, imMessageMapper, invocation.getArgument(0, String.class));
                    if (row == null) return false;
                    IWxContentCallbackInboxService.Mutation mutation = invocation.getArgument(
                            2, IWxContentCallbackInboxService.Mutation.class);
                    return mutation.apply(row, invocation.getArgument(1, Boolean.class));
                });
        ReflectionTestUtils.setField(service, "callbackInboxService", inbox);
        return service;
    }

    private static WxContentCallbackInbox testInboxRow(RedisCache redisCache,
                                                        ImMessageMapper imMessageMapper,
                                                        String traceId) {
        String mapping = redisCache.getCacheObject("wx:mediacheck:trace:" + traceId);
        int bizType;
        Long bizId;
        if (mapping != null && mapping.contains(":")) {
            String[] parts = mapping.split(":");
            bizType = Integer.parseInt(parts[0]);
            bizId = Long.parseLong(parts[1]);
        } else {
            ImMessage message = imMessageMapper.selectByTraceId(traceId);
            if (message == null) return null;
            bizType = IWxContentSecurityService.BIZ_TYPE_IM_MESSAGE;
            bizId = message.getId();
        }
        WxContentCallbackInbox row = new WxContentCallbackInbox();
        row.setBizType(bizType);
        row.setBizId(bizId);
        return row;
    }

    private ImMessage message(long id) {
        ImMessage m = new ImMessage();
        m.setId(id);
        return m;
    }

    private UmsCreativeSquare square(long id, int status) {
        UmsCreativeSquare square = new UmsCreativeSquare();
        square.setId(id);
        square.setStatus(status);
        return square;
    }

    @Test
    void callbackProtocolValidationFinishesBeforeNonceOccupation() {
        RedisCache redisCache = mock(RedisCache.class);
        ISysConfigService configService = mock(ISysConfigService.class);
        when(configService.selectConfigByKey("wx.msgpush.token")).thenReturn("test-token");
        WxContentSecurityServiceImpl service = service(redisCache, mock(ImMessageMapper.class));
        ReflectionTestUtils.setField(service, "configService", configService);
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(Instant.ofEpochSecond(1700000000L), ZoneOffset.UTC));

        IWxContentSecurityService.CallbackResult result = service.processMediaCheckCallback(
                "4858c5890e55f7fd170a7e3fe5526831728c8f35",
                "1700000000",
                "nonce-struct",
                "{\"Event\":\"wxa_media_check\",\"trace_id\":\"trace-struct\",\"errcode\":0,\"result\":{}}");

        assertEquals(IWxContentSecurityService.CallbackResult.INVALID_PAYLOAD, result);
        verify(redisCache, never()).setIfAbsent(
                startsWith("wx:content-sec:callback:nonce:"), anyString(), eq(10), eq(TimeUnit.MINUTES));
    }

    @Test
    void callbackRejectsMissingTokenWrongSignatureAndBothTimestampOverrunsBeforeNonce() {
        RedisCache redisCache = mock(RedisCache.class);
        ISysConfigService configService = mock(ISysConfigService.class);
        WxContentSecurityServiceImpl service = service(redisCache, mock(ImMessageMapper.class));
        ReflectionTestUtils.setField(service, "configService", configService);
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(Instant.ofEpochSecond(1700000000L), ZoneOffset.UTC));
        String body = "{\"Event\":\"wxa_media_check\",\"trace_id\":\"trace\",\"errcode\":0,\"result\":{\"suggest\":\"pass\"}}";

        when(configService.selectConfigByKey("wx.msgpush.token")).thenReturn("");
        assertEquals(IWxContentSecurityService.CallbackResult.TOKEN_UNAVAILABLE,
                service.processMediaCheckCallback("sig", "1700000000", "nonce", body));
        when(configService.selectConfigByKey("wx.msgpush.token")).thenReturn("test-token");
        assertEquals(IWxContentSecurityService.CallbackResult.FORBIDDEN,
                service.processMediaCheckCallback("wrong", "1700000000", "nonce", body));
        assertEquals(IWxContentSecurityService.CallbackResult.INVALID_TIMESTAMP,
                service.processMediaCheckCallback("761c6132f0e96ffae2ef5616b73b6eae4582314e",
                        "1699999699", "nonce-minus-301", body));
        assertEquals(IWxContentSecurityService.CallbackResult.INVALID_TIMESTAMP,
                service.processMediaCheckCallback("f01898f870daa608deb0192db4f73ca99a631d03",
                        "1700000301", "nonce", body));

        verify(redisCache, never()).setIfAbsent(
                startsWith("wx:content-sec:callback:nonce:"), anyString(), eq(10), eq(TimeUnit.MINUTES));
    }

    @Test
    void callbackTimestampWindowIncludesExactlyMinusAndPlus300AndRunsBusinessOnceEach() {
        RedisCache redisCache = mock(RedisCache.class);
        ISysConfigService configService = mock(ISysConfigService.class);
        when(configService.selectConfigByKey("wx.msgpush.token")).thenReturn("test-token");
        when(redisCache.setIfAbsent(startsWith("wx:content-sec:callback:nonce:"), anyString(),
                eq(10), eq(TimeUnit.MINUTES))).thenReturn(true);
        WxContentSecurityServiceImpl service = spy(service(redisCache, mock(ImMessageMapper.class)));
        ReflectionTestUtils.setField(service, "configService", configService);
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(Instant.ofEpochSecond(1700000000L), ZoneOffset.UTC));
        doReturn(true).when(service).handleMediaCheckResult("trace-boundary", false);
        String body = "{\"Event\":\"wxa_media_check\",\"trace_id\":\"trace-boundary\",\"errcode\":0,\"result\":{\"suggest\":\"pass\"}}";

        assertEquals(IWxContentSecurityService.CallbackResult.SUCCESS,
                service.processMediaCheckCallback("d61636c35cd5125f0d4f10d3f3418edc0e4b2fdb",
                        "1699999700", "nonce-minus-300", body));
        assertEquals(IWxContentSecurityService.CallbackResult.SUCCESS,
                service.processMediaCheckCallback("436be91ea846b2d0e25b6f482077c02f3871a6bb",
                        "1700000300", "nonce-plus-300", body));

        verify(service, times(2)).handleMediaCheckResult("trace-boundary", false);
    }

    @Test
    void invalidPlaintextShapesAndAesEnvelopeNeverOccupyNonce() {
        RedisCache redisCache = mock(RedisCache.class);
        ISysConfigService configService = mock(ISysConfigService.class);
        when(configService.selectConfigByKey("wx.msgpush.token")).thenReturn("test-token");
        WxContentSecurityServiceImpl service = service(redisCache, mock(ImMessageMapper.class));
        ReflectionTestUtils.setField(service, "configService", configService);
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(Instant.ofEpochSecond(1700000000L), ZoneOffset.UTC));
        String[] invalid = {
                "{bad-json", "{\"Encrypt\":\"ciphertext\"}",
                "{\"Event\":\"wxa_media_check\",\"trace_id\":\"trace\",\"errcode\":87014,\"result\":{\"suggest\":\"risky\"}}",
                "{\"Event\":\"wxa_media_check\",\"trace_id\":\"trace\",\"errcode\":0,\"result\":{}}"
        };

        for (String body : invalid) {
            assertEquals(IWxContentSecurityService.CallbackResult.INVALID_PAYLOAD,
                    service.processMediaCheckCallback("4858c5890e55f7fd170a7e3fe5526831728c8f35",
                            "1700000000", "nonce-struct", body));
        }
        verify(redisCache, never()).setIfAbsent(
                startsWith("wx:content-sec:callback:nonce:"), anyString(), eq(10), eq(TimeUnit.MINUTES));
    }

    @Test
    void businessFailureReleasesOnlyNonceOwnerAndCanBeRetried() {
        RedisCache redisCache = mock(RedisCache.class);
        ISysConfigService configService = mock(ISysConfigService.class);
        when(configService.selectConfigByKey("wx.msgpush.token")).thenReturn("test-token");
        when(redisCache.setIfAbsent(startsWith("wx:content-sec:callback:nonce:"), anyString(),
                eq(10), eq(TimeUnit.MINUTES))).thenReturn(true, true);
        when(redisCache.compareAndDelete(startsWith("wx:content-sec:callback:nonce:"), anyString())).thenReturn(true);
        WxContentSecurityServiceImpl service = spy(service(redisCache, mock(ImMessageMapper.class)));
        ReflectionTestUtils.setField(service, "configService", configService);
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(Instant.ofEpochSecond(1700000000L), ZoneOffset.UTC));
        doReturn(false, true).when(service).handleMediaCheckResult("trace-false", false);
        String body = "{\"Event\":\"wxa_media_check\",\"trace_id\":\"trace-false\",\"errcode\":0,\"result\":{\"suggest\":\"pass\"}}";

        assertEquals(IWxContentSecurityService.CallbackResult.PROCESSING_FAILED,
                service.processMediaCheckCallback("8e91e748d4120504e771fd0ea6687904595b9d59",
                        "1700000000", "nonce-false", body));
        assertEquals(IWxContentSecurityService.CallbackResult.SUCCESS,
                service.processMediaCheckCallback("8e91e748d4120504e771fd0ea6687904595b9d59",
                        "1700000000", "nonce-false", body));

        verify(redisCache, times(1)).compareAndDelete(
                startsWith("wx:content-sec:callback:nonce:"), anyString());
    }

    @Test
    void reviewInfoLogContainsOnlyLengthAndNeverUserContent() {
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.getCacheObject("wx:mini:access_token")).thenReturn("cached-token");
        WxPayConfig wxConfig = mock(WxPayConfig.class);
        when(wxConfig.isSecCheckEnabled()).thenReturn(true);
        when(wxConfig.getMiniAppId()).thenReturn("appid");
        when(wxConfig.getMiniSecret()).thenReturn("secret");
        ReviewResponseService service = new ReviewResponseService();
        ReflectionTestUtils.setField(service, "redisCache", redisCache);
        ReflectionTestUtils.setField(service, "wxConfig", wxConfig);
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(WxContentSecurityServiceImpl.class);
        ch.qos.logback.classic.Level previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        String secretContent = "private-user-message-should-never-appear";

        try {
            assertEquals(IWxContentSecurityService.REVIEW_REQUIRED,
                    service.checkText(secretContent, "openid"));
            StringBuilder messages = new StringBuilder();
            for (ILoggingEvent event : appender.list) messages.append(event.getFormattedMessage());
            String joined = messages.toString();
            assertFalse(joined.contains(secretContent));
            assertTrue(joined.contains("contentLength=" + secretContent.length()));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void malformedSuccessResponseRequiresManualReviewInsteadOfPassing() {
        WxContentSecurityServiceImpl service =
                enabledTextService(new MissingSuggestResponseService());

        // 成功包缺 result 是真「需要人工复审」,不是「审不了」:仍回 REVIEW_REQUIRED。
        assertSame(IWxContentSecurityService.REVIEW_REQUIRED,
                service.checkText("待检测内容", "openid"));
    }

    @Test
    void operationalApiFailureReportsRetryableBusyInsteadOfContentBlock() {
        WxContentSecurityServiceImpl service =
                enabledTextService(new OperationalErrorResponseService());

        // 合并 #1057×master(RUN-29 语义):可重试的接口错误回 MSG_BUSY,调用方判「繁忙 ≠ 违规」。
        assertSame(IWxContentSecurityService.MSG_BUSY,
                service.checkText("待检测内容", "openid"));
    }

    @Test
    void transportFailureReportsRetryableBusyInsteadOfContentBlock() {
        WxContentSecurityServiceImpl service =
                enabledTextService(new ThrowingResponseService());

        assertSame(IWxContentSecurityService.MSG_BUSY,
                service.checkText("待检测内容", "openid"));
    }

    @Test
    void invalidOpenidErrcodeIsCallerErrorNotRetryableBusy() {
        WxContentSecurityServiceImpl service =
                enabledTextService(new InvalidOpenidResponseService());

        String result = service.checkText("hello", "openid-valid");

        assertNotNull(result, "调用方错误同样 fail-close,绝不放行");
        assertSame(IWxContentSecurityService.MSG_UNAVAILABLE, result);
        assertNotSame(IWxContentSecurityService.MSG_BUSY, result,
                "40003 重试无用,不能提示用户「服务繁忙,请稍后重试」");
    }

    @Test
    void missingOpenidFailsClosedWithoutPretendingBusy() {
        WxContentSecurityServiceImpl service = enabledTextService(new WxContentSecurityServiceImpl());

        String result = service.checkText("hello", "");

        assertSame(IWxContentSecurityService.MSG_UNAVAILABLE, result);
        assertNotSame(IWxContentSecurityService.MSG_BUSY, result);
    }

    @Test
    void unknownErrcodeStaysRetryableBusy() {
        WxContentSecurityServiceImpl service =
                enabledTextService(new UnknownErrorResponseService());

        assertSame(IWxContentSecurityService.MSG_BUSY, service.checkText("hello", "openid-valid"));
    }

    @Test
    void httpFailureLogDoesNotContainTokenMediaUrlTraceOrExceptionMessage() {
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.getCacheObject("wx:mini:access_token")).thenReturn("token-secret");
        WxPayConfig wxConfig = mock(WxPayConfig.class);
        when(wxConfig.isSecCheckEnabled()).thenReturn(true);
        when(wxConfig.getMiniAppId()).thenReturn("appid");
        when(wxConfig.getMiniSecret()).thenReturn("secret");
        ThrowingResponseService service = new ThrowingResponseService();
        ReflectionTestUtils.setField(service, "redisCache", redisCache);
        ReflectionTestUtils.setField(service, "wxConfig", wxConfig);
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(WxContentSecurityServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertNull(service.checkMediaAsync("https://private/media.jpg", "openid-secret"));
            StringBuilder messages = new StringBuilder();
            for (ILoggingEvent event : appender.list) {
                messages.append(event.getFormattedMessage());
                if (event.getThrowableProxy() != null) messages.append(event.getThrowableProxy().getMessage());
            }
            String joined = messages.toString();
            assertFalse(joined.contains("token-secret"));
            assertFalse(joined.contains("https://private/media.jpg"));
            assertFalse(joined.contains("trace-secret"));
            assertFalse(joined.contains("access_token=leak"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void productionCallbackPathUsesMysqlInboxInsteadOfRedisResultLease() {
        RedisCache redisCache = mock(RedisCache.class);
        IWxContentCallbackInboxService inbox = mock(IWxContentCallbackInboxService.class);
        when(inbox.isRegistered("trace-db")).thenReturn(true);
        when(inbox.process(eq("trace-db"), eq(true), any(IWxContentCallbackInboxService.Mutation.class)))
                .thenReturn(true);
        WxContentSecurityServiceImpl service = service(redisCache, mock(ImMessageMapper.class));
        ReflectionTestUtils.setField(service, "callbackInboxService", inbox);

        assertTrue(service.handleMediaCheckResult("trace-db", true));

        verify(inbox).process(eq("trace-db"), eq(true), any(IWxContentCallbackInboxService.Mutation.class));
        verify(redisCache, never()).setIfAbsent(startsWith("wx:content-sec:callback:result:"),
                anyString(), eq(10), eq(TimeUnit.MINUTES));
    }

    @Test
    void submitDoesNotPublishRedisMappingWhenMysqlInboxRegistrationFails() {
        RedisCache redisCache = mock(RedisCache.class);
        IWxContentCallbackInboxService inbox = mock(IWxContentCallbackInboxService.class);
        when(inbox.register("trace-db", IWxContentSecurityService.BIZ_TYPE_SQUARE, 42L,
                "https://o/p.jpg")).thenReturn(false);
        WxPayConfig wxConfig = mock(WxPayConfig.class);
        when(wxConfig.isSecCheckEnabled()).thenReturn(true);
        WxContentSecurityServiceImpl service = spy(service(redisCache, mock(ImMessageMapper.class)));
        ReflectionTestUtils.setField(service, "wxConfig", wxConfig);
        ReflectionTestUtils.setField(service, "callbackInboxService", inbox);
        doReturn("trace-db").when(service).checkMediaAsync("https://o/p.jpg", "openid");

        assertNull(service.submitMediaCheckOne(
                IWxContentSecurityService.BIZ_TYPE_SQUARE, 42L, "https://o/p.jpg", "openid"));

        verify(redisCache, never()).setCacheObject(eq("wx:mediacheck:trace:trace-db"),
                anyString(), eq(2), eq(TimeUnit.DAYS));
    }

    @Test
    void auxiliaryRedisFailureDoesNotEraseTheAuthoritativeMysqlInboxReceipt() {
        RedisCache redisCache = mock(RedisCache.class);
        IWxContentCallbackInboxService inbox = mock(IWxContentCallbackInboxService.class);
        when(inbox.register("trace-db", IWxContentSecurityService.BIZ_TYPE_CLUB_POST, 42L,
                "https://o/p.jpg")).thenReturn(true);
        doThrow(new IllegalStateException("redis unavailable")).when(redisCache).setCacheObject(
                eq("wx:mediacheck:trace:trace-db"), anyString(), eq(2), eq(TimeUnit.DAYS));
        WxPayConfig wxConfig = mock(WxPayConfig.class);
        when(wxConfig.isSecCheckEnabled()).thenReturn(true);
        WxContentSecurityServiceImpl service = spy(service(redisCache, mock(ImMessageMapper.class)));
        ReflectionTestUtils.setField(service, "wxConfig", wxConfig);
        ReflectionTestUtils.setField(service, "callbackInboxService", inbox);
        doReturn("trace-db").when(service).checkMediaAsync("https://o/p.jpg", "openid");

        assertEquals("trace-db", service.submitMediaCheckOne(
                IWxContentSecurityService.BIZ_TYPE_CLUB_POST, 42L, "https://o/p.jpg", "openid"));

        verify(inbox).register("trace-db", IWxContentSecurityService.BIZ_TYPE_CLUB_POST, 42L,
                "https://o/p.jpg");
    }

    @Test
    void callbackNonceUsesAtomicSetIfAbsentWithFiniteTtl() {
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.setIfAbsent(
                eq("wx:content-sec:callback:nonce:2586fb5da6d00d8e20bb960cdb2b0c1b717d88d463ee0c3a0089e5df037b4b2a"),
                anyString(),
                eq(10),
                eq(TimeUnit.MINUTES))).thenReturn(true);

        String owner = service(redisCache, mock(ImMessageMapper.class))
                .tryAcquireCallbackNonce("nonce-once");

        assertNotNull(owner);
        verify(redisCache).setIfAbsent(
                "wx:content-sec:callback:nonce:2586fb5da6d00d8e20bb960cdb2b0c1b717d88d463ee0c3a0089e5df037b4b2a",
                owner,
                10,
                TimeUnit.MINUTES);
    }

    @Test
    void duplicateCallbackNonceIsRejected() {
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.setIfAbsent(anyString(), anyString(), eq(10), eq(TimeUnit.MINUTES))).thenReturn(false);

        String owner = service(redisCache, mock(ImMessageMapper.class))
                .tryAcquireCallbackNonce("nonce-once");

        assertNull(owner);
    }

    @Test
    void callbackNonceCanOnlyBeReleasedByItsOwner() {
        RedisCache redisCache = mock(RedisCache.class);
        when(redisCache.compareAndDelete(anyString(), eq("owner-current"))).thenReturn(true);
        WxContentSecurityServiceImpl service = service(redisCache, mock(ImMessageMapper.class));

        assertTrue(service.releaseCallbackNonce("nonce-once", "owner-current"));
        assertFalse(service.releaseCallbackNonce("nonce-once", "owner-stale"));

        verify(redisCache).compareAndDelete(
                "wx:content-sec:callback:nonce:2586fb5da6d00d8e20bb960cdb2b0c1b717d88d463ee0c3a0089e5df037b4b2a",
                "owner-current");
        verify(redisCache).compareAndDelete(
                "wx:content-sec:callback:nonce:2586fb5da6d00d8e20bb960cdb2b0c1b717d88d463ee0c3a0089e5df037b4b2a",
                "owner-stale");
    }

    @Test
    void riskyImImageWithoutRedisMappingIsBlockedByTraceId() {
        RedisCache redisCache = mock(RedisCache.class);
        ImMessageMapper imMessageMapper = mock(ImMessageMapper.class);
        // IM 图片没有 redis 映射
        when(redisCache.getCacheObject(anyString())).thenReturn(null);
        ImMessage message = message(77L);
        message.setTraceId("trace-im-1");
        message.setStatus(ImMessage.STATUS_NORMAL);
        message.setCheckStatus(0);
        when(imMessageMapper.selectByTraceId("trace-im-1")).thenReturn(message);
        when(imMessageMapper.selectImMessageById(77L)).thenReturn(message);
        when(imMessageMapper.applyMediaCheckResult(77L, "trace-im-1", true)).thenReturn(1);
        WxContentSecurityServiceImpl service = service(redisCache, imMessageMapper);

        boolean handled = service.handleMediaCheckResult("trace-im-1", true);

        assertTrue(handled);
        verify(imMessageMapper).applyMediaCheckResult(77L, "trace-im-1", true);
    }

    @Test
    void passingImImageIsNotBlocked() {
        RedisCache redisCache = mock(RedisCache.class);
        ImMessageMapper imMessageMapper = mock(ImMessageMapper.class);
        when(redisCache.getCacheObject(anyString())).thenReturn(null);
        ImMessage message = message(88L);
        message.setTraceId("trace-im-2");
        message.setStatus(ImMessage.STATUS_NORMAL);
        message.setCheckStatus(0);
        when(imMessageMapper.selectByTraceId("trace-im-2")).thenReturn(message);
        when(imMessageMapper.selectImMessageById(88L)).thenReturn(message);
        when(imMessageMapper.applyMediaCheckResult(88L, "trace-im-2", false)).thenReturn(1);
        WxContentSecurityServiceImpl service = service(redisCache, imMessageMapper);

        boolean handled = service.handleMediaCheckResult("trace-im-2", false);

        assertTrue(handled);
        verify(imMessageMapper).applyMediaCheckResult(88L, "trace-im-2", false);
        verify(imMessageMapper, never()).updateStatus(eq(88L), eq(ImMessage.STATUS_BLOCKED));
    }

    @Test
    void alreadyRemovedClubPostIsSuccessfulWhenDeleteReportsZeroRows() {
        RedisCache redisCache = mock(RedisCache.class);
        ImMessageMapper imMessageMapper = mock(ImMessageMapper.class);
        IWxContentCallbackInboxService inbox = mock(IWxContentCallbackInboxService.class);
        IClubPostService clubPostService = mock(IClubPostService.class);
        WxContentCallbackInbox row = new WxContentCallbackInbox();
        row.setBizType(IWxContentSecurityService.BIZ_TYPE_CLUB_POST);
        row.setBizId(42L);
        row.setMediaHash("media-hash");
        ClubPost deleted = new ClubPost();
        deleted.setId(42L);
        deleted.setDelFlag(2);
        when(inbox.isRegistered("trace-club")).thenReturn(true);
        when(inbox.process(eq("trace-club"), eq(true), any(IWxContentCallbackInboxService.Mutation.class)))
                .thenAnswer(invocation -> invocation.getArgument(2, IWxContentCallbackInboxService.Mutation.class)
                        .apply(row, true));
        when(clubPostService.deleteClubPostById(42L)).thenReturn(0);
        when(clubPostService.selectClubPostById(42L)).thenReturn(deleted);
        WxContentSecurityServiceImpl service = service(redisCache, imMessageMapper);
        ReflectionTestUtils.setField(service, "callbackInboxService", inbox);
        ReflectionTestUtils.setField(service, "clubPostService", clubPostService);

        assertTrue(service.handleMediaCheckResult("trace-club", true));

        verify(clubPostService).deleteClubPostById(42L);
        verify(clubPostService).selectClubPostById(42L);
    }

    @Test
    void riskySquareImageIsTakenDownToStatus2() {
        RedisCache redisCache = mock(RedisCache.class);
        ImMessageMapper imMessageMapper = mock(ImMessageMapper.class);
        IUmsCreativeSquareService squareService = mock(IUmsCreativeSquareService.class);
        when(redisCache.getCacheObject(anyString())).thenReturn("9:123"); // BIZ_TYPE_SQUARE=9
        WxContentSecurityServiceImpl service = service(redisCache, imMessageMapper);
        ReflectionTestUtils.setField(service, "umsCreativeSquareService", squareService);
        when(squareService.selectUmsCreativeSquareById(123L)).thenReturn(square(123L, 1));
        when(squareService.updateUmsCreativeSquare(any(UmsCreativeSquare.class))).thenReturn(1);

        boolean handled = service.handleMediaCheckResult("trace-sq-1", true);

        assertTrue(handled);
        ArgumentCaptor<UmsCreativeSquare> captor = ArgumentCaptor.forClass(UmsCreativeSquare.class);
        verify(squareService).updateUmsCreativeSquare(captor.capture());
        assertEquals(Long.valueOf(123L), captor.getValue().getId());
        assertEquals(Integer.valueOf(2), captor.getValue().getStatus()); // 2=下架
    }

    @Test
    void passingSquareImageIsNotTakenDown() {
        RedisCache redisCache = mock(RedisCache.class);
        ImMessageMapper imMessageMapper = mock(ImMessageMapper.class);
        IUmsCreativeSquareService squareService = mock(IUmsCreativeSquareService.class);
        when(redisCache.getCacheObject(anyString())).thenReturn("9:123");
        WxContentSecurityServiceImpl service = service(redisCache, imMessageMapper);
        ReflectionTestUtils.setField(service, "umsCreativeSquareService", squareService);

        boolean handled = service.handleMediaCheckResult("trace-sq-2", false);

        assertTrue(handled);
        verify(squareService, never()).updateUmsCreativeSquare(any());
    }

    @Test
    void riskyCommentImageIsTakenDownToStatus2() {
        RedisCache redisCache = mock(RedisCache.class);
        ImMessageMapper imMessageMapper = mock(ImMessageMapper.class);
        IUmsCommentService commentService = mock(IUmsCommentService.class);
        when(redisCache.getCacheObject(anyString())).thenReturn("10:456"); // BIZ_TYPE_COMMENT=10
        WxContentSecurityServiceImpl service = service(redisCache, imMessageMapper);
        ReflectionTestUtils.setField(service, "umsCommentService", commentService);
        UmsComment current = new UmsComment();
        current.setId(456L);
        current.setStatus(1);
        when(commentService.selectUmsCommentById(456L)).thenReturn(current);
        when(commentService.updateUmsComment(any(UmsComment.class))).thenReturn(1);

        boolean handled = service.handleMediaCheckResult("trace-cm-1", true);

        assertTrue(handled);
        ArgumentCaptor<UmsComment> captor = ArgumentCaptor.forClass(UmsComment.class);
        verify(commentService).updateUmsComment(captor.capture());
        assertEquals(Long.valueOf(456L), captor.getValue().getId());
        assertEquals(Integer.valueOf(2), captor.getValue().getStatus());
    }

    // ---- 漫游集邮 (BIZ_TYPE_ROAM_STAMP=11) ----
    // 集邮票与其它业务的差别:「通过」也是要落库的事实。其它业务通过=无事发生,
    // 集邮票通过必须把 check_state 从 0 迁到 1,否则「机审过了」与「压根没送检」永远同为 0。

    private WxContentSecurityServiceImpl stampService(RedisCache redisCache, RoamStampMapper roamStampMapper) {
        WxContentSecurityServiceImpl service = service(redisCache, mock(ImMessageMapper.class));
        ReflectionTestUtils.setField(service, "roamStampMapper", roamStampMapper);
        return service;
    }

    @Test
    void passingStampIsWrittenBackToPassed() {
        RedisCache redisCache = mock(RedisCache.class);
        RoamStampMapper roamStampMapper = mock(RoamStampMapper.class);
        when(redisCache.getCacheObject(anyString())).thenReturn("11:5");
        WxContentSecurityServiceImpl service = stampService(redisCache, roamStampMapper);
        when(roamStampMapper.markCheckPassed(5L)).thenReturn(1);

        boolean handled = service.handleMediaCheckResult("trace-st-pass", false);

        assertTrue(handled);
        // 🔴 负控靶心:若沿用上面那句「!risky 直接返回」,这行一次都不会被调用,
        // 票永远停在 0 —— 这正是该红的场景,改回早返回本断言必炸。
        verify(roamStampMapper).markCheckPassed(5L);
        verify(roamStampMapper, never()).markCheckRisky(anyLong());
    }

    @Test
    void riskyStampIsMarkedRiskyAndLeavesTheAlbum() {
        RedisCache redisCache = mock(RedisCache.class);
        RoamStampMapper roamStampMapper = mock(RoamStampMapper.class);
        when(redisCache.getCacheObject(anyString())).thenReturn("11:5");
        WxContentSecurityServiceImpl service = stampService(redisCache, roamStampMapper);
        when(roamStampMapper.markCheckRisky(5L)).thenReturn(1);

        boolean handled = service.handleMediaCheckResult("trace-st-risky", true);

        assertTrue(handled);
        verify(roamStampMapper).markCheckRisky(5L);
        verify(roamStampMapper, never()).markCheckPassed(anyLong());
    }

    @Test
    void zeroRowStampPassHasNoEvidenceOfExpectedStateAndMustFailClosed() {
        RedisCache redisCache = mock(RedisCache.class);
        RoamStampMapper roamStampMapper = mock(RoamStampMapper.class);
        when(redisCache.getCacheObject("wx:mediacheck:trace:trace-st-zero")).thenReturn("11:5");
        WxContentSecurityServiceImpl service = stampService(redisCache, roamStampMapper);
        when(roamStampMapper.markCheckPassed(5L)).thenReturn(0);

        assertFalse(service.handleMediaCheckResult("trace-st-zero", false));

        verify(redisCache, never()).deleteObject("wx:mediacheck:trace:trace-st-zero");
    }

    @Test
    void zeroRowStampPassCanSucceedOnlyWithObservedPassedState() {
        RedisCache redisCache = mock(RedisCache.class);
        RoamStampMapper roamStampMapper = mock(RoamStampMapper.class);
        when(redisCache.getCacheObject("wx:mediacheck:trace:trace-st-observed")).thenReturn("11:5");
        WxContentSecurityServiceImpl service = stampService(redisCache, roamStampMapper);
        when(roamStampMapper.markCheckPassed(5L)).thenReturn(0);
        RoamStamp observed = new RoamStamp();
        observed.setId(5L);
        observed.setCheckState(1);
        when(roamStampMapper.selectById(5L)).thenReturn(observed);

        assertTrue(service.handleMediaCheckResult("trace-st-observed", false));

        verify(redisCache).deleteObject("wx:mediacheck:trace:trace-st-observed");
    }

    @Test
    void zeroRowBusinessUpdateCannotBeMarkedDoneOrAcknowledged() {
        RedisCache redisCache = mock(RedisCache.class);
        IUmsCreativeSquareService squareService = mock(IUmsCreativeSquareService.class);
        when(redisCache.getCacheObject("wx:mediacheck:trace:trace-zero")).thenReturn("9:123");
        WxContentSecurityServiceImpl service = service(redisCache, mock(ImMessageMapper.class));
        ReflectionTestUtils.setField(service, "umsCreativeSquareService", squareService);
        when(squareService.selectUmsCreativeSquareById(123L)).thenReturn(square(123L, 1), square(123L, 1));
        when(squareService.updateUmsCreativeSquare(any(UmsCreativeSquare.class))).thenReturn(0);

        assertFalse(service.handleMediaCheckResult("trace-zero", true));

        verify(redisCache, never()).deleteObject("wx:mediacheck:trace:trace-zero");
    }

    @Test
    void unknownTraceWithNoMappingAndNoMessageIsIgnored() {
        RedisCache redisCache = mock(RedisCache.class);
        ImMessageMapper imMessageMapper = mock(ImMessageMapper.class);
        when(redisCache.getCacheObject(anyString())).thenReturn(null);
        when(imMessageMapper.selectByTraceId(anyString())).thenReturn(null);
        WxContentSecurityServiceImpl service = service(redisCache, imMessageMapper);

        boolean handled = service.handleMediaCheckResult("trace-unknown", true);

        assertFalse(handled);
        verify(imMessageMapper, never()).updateStatus(eq(0L), eq(ImMessage.STATUS_BLOCKED));
    }

    // ===== 节点 NPC 头像(BIZ_TYPE_NODE_NPC=16,bizId=npc_profile.id)=====

    /**
     * ★ 违规必须下架节点 NPC,而且**不能**去清商家资料图 ——
     * 复用 BIZ_TYPE_MERCHANT_PROFILE 的写法会把 npc id 当 merchant id,
     * 清掉的是别人家的封面/相册,并且一路返回成功。
     */
    @Test
    void nodeNpcAvatarViolationHidesNpcAndNeverTouchesMerchantMedia() {
        RedisCache redisCache = mock(RedisCache.class);
        ImMessageMapper imMessageMapper = mock(ImMessageMapper.class);
        NpcProfileMapper npcMapper = mock(NpcProfileMapper.class);
        MmsMerchantMapper merchantMapper = mock(MmsMerchantMapper.class);
        when(redisCache.getCacheObject(anyString())).thenReturn("16:456");
        WxContentSecurityServiceImpl service = service(redisCache, imMessageMapper);
        ReflectionTestUtils.setField(service, "npcProfileMapper", npcMapper);
        ReflectionTestUtils.setField(service, "mmsMerchantMapper", merchantMapper);
        when(npcMapper.hideNodeNpcById(456L)).thenReturn(1);

        assertTrue(service.handleMediaCheckResult("trace-npc-1", true));

        verify(npcMapper).hideNodeNpcById(456L);
        verify(merchantMapper, never()).clearProfileMedia(anyLong());
    }

    /** 已经下架过的再回调一次 = 成功(可重试的幂等语义),不是「没更新到 = 失败」。 */
    @Test
    void alreadyHiddenNodeNpcIsConfirmedOnRepeatCallback() {
        RedisCache redisCache = mock(RedisCache.class);
        ImMessageMapper imMessageMapper = mock(ImMessageMapper.class);
        NpcProfileMapper npcMapper = mock(NpcProfileMapper.class);
        when(redisCache.getCacheObject(anyString())).thenReturn("16:456");
        WxContentSecurityServiceImpl service = service(redisCache, imMessageMapper);
        ReflectionTestUtils.setField(service, "npcProfileMapper", npcMapper);
        when(npcMapper.hideNodeNpcById(456L)).thenReturn(0);
        NpcProfile hidden = new NpcProfile();
        hidden.setId(456L);
        hidden.setEnabled(0);
        when(npcMapper.selectNodeNpcById(456L)).thenReturn(hidden);

        assertTrue(service.handleMediaCheckResult("trace-npc-2", true));
    }

    /**
     * 迟到的回调不许误伤新人:商家已经换过一张合规头像时,旧送检的违规结果必须拦在
     * 「当前媒体仍是当初送检媒体」这道闸上(isCurrent=false),不能把新头像连人一起下架。
     */
    @Test
    void staleNodeNpcAvatarSubmissionIsNotCurrent() {
        RedisCache redisCache = mock(RedisCache.class);
        ImMessageMapper imMessageMapper = mock(ImMessageMapper.class);
        NpcProfileMapper npcMapper = mock(NpcProfileMapper.class);
        IWxContentCallbackInboxService inbox = mock(IWxContentCallbackInboxService.class);
        when(inbox.isRegistered("trace-npc-3")).thenReturn(true);
        when(inbox.process(eq("trace-npc-3"), eq(false), any(IWxContentCallbackInboxService.Mutation.class)))
                .thenReturn(false);
        WxContentSecurityServiceImpl service = service(redisCache, imMessageMapper);
        ReflectionTestUtils.setField(service, "callbackInboxService", inbox);
        ReflectionTestUtils.setField(service, "npcProfileMapper", npcMapper);
        NpcProfile row = new NpcProfile();
        row.setId(456L);
        row.setAvatar("https://o/right.png");
        when(npcMapper.selectNodeNpcById(456L)).thenReturn(row);
        service.handleMediaCheckResult("trace-npc-3", false);

        ArgumentCaptor<IWxContentCallbackInboxService.Mutation> cap =
                ArgumentCaptor.forClass(IWxContentCallbackInboxService.Mutation.class);
        verify(inbox).process(eq("trace-npc-3"), eq(false), cap.capture());

        WxContentCallbackInbox stale = new WxContentCallbackInbox();
        stale.setBizType(IWxContentSecurityService.BIZ_TYPE_NODE_NPC);
        stale.setBizId(456L);
        stale.setMediaHash("deadbeef");
        assertFalse(cap.getValue().isCurrent(stale), "旧媒体的回调不得命中新头像");

        WxContentCallbackInbox current = new WxContentCallbackInbox();
        current.setBizType(IWxContentSecurityService.BIZ_TYPE_NODE_NPC);
        current.setBizId(456L);
        current.setMediaHash(WxContentCallbackInboxServiceImpl.sha256Hex("https://o/right.png"));
        assertTrue(cap.getValue().isCurrent(current), "当前这版送检必须认得出");
    }
}
