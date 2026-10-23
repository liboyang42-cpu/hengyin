package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.dto.ai.AiSafetyPrecheckReq;
import com.chengyinhub.business.domain.vo.ai.AiSafetyIssue;
import com.chengyinhub.business.domain.vo.ai.AiSafetyPrecheckResp;
import com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.support.TopicAiPrecheckService;
import com.chengyinhub.business.service.support.TopicPublishCheckService.PublishCheckIssue;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** DOC2 O5c-(c):AI 安全预检接入发布前试检查 —— 默认关、fail-open、只送文本不送位置/PII。 */
class TopicAiPrecheckServiceTest {

    private IAiCreatorService ai;
    private TopicAiPrecheckService service;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        ai = mock(IAiCreatorService.class);
        service = new TopicAiPrecheckService(ai);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "timeoutMs", 2000L);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        service.shutdown();
    }

    @Test
    void switchOffByDefaultCallsNothingAndAddsNothing() {
        TopicAiPrecheckService fresh = new TopicAiPrecheckService(ai);
        try {
            assertFalse(fresh.isEnabled());
            assertTrue(fresh.check(topic(), nodes()).isEmpty());
            verifyNoInteractions(ai);
        } finally {
            fresh.shutdown();
        }
    }

    @Test
    void providerTimeoutFailsOpenWithWarn() {
        ReflectionTestUtils.setField(service, "timeoutMs", 100L);
        when(ai.safetyPrecheck(any(), anyLong())).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return pass();
        });
        long t0 = System.nanoTime();
        List<PublishCheckIssue> issues = service.check(topic(), nodes());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 2000L, "超时必须按配置收口");
        assertSingleSkipped(issues);
    }

    @Test
    void rateLimitedAndUpstreamOutageAndUnknownOutcomeAllFailOpen() {
        for (RuntimeException failure : Arrays.<RuntimeException>asList(
                new AiServiceException(AiServiceException.Reason.RATE_LIMITED, "额度用尽"),
                new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, "上游不可用"),
                new AiServiceException(AiServiceException.Reason.OUTPUT_BLOCKED, "输出被拦"),
                new AiProviderOutcomeUnknownException("待确认", new RuntimeException("timeout")),
                new IllegalStateException("boom"))) {
            doThrow(failure).when(ai).safetyPrecheck(any(), anyLong());
            assertSingleSkipped(service.check(topic(), nodes()));
        }
    }

    @Test
    void inputBlockedByContentSecurityIsAnError() {
        when(ai.safetyPrecheck(any(), anyLong()))
                .thenThrow(new AiServiceException(AiServiceException.Reason.INPUT_BLOCKED, "含违规内容"));
        List<PublishCheckIssue> issues = service.check(topic(), nodes());
        assertEquals(1, issues.size());
        assertEquals(PublishCheckIssue.LEVEL_ERROR, issues.get(0).getLevel());
        assertEquals(TopicAiPrecheckService.CODE_AI_PRECHECK_CONTENT_BLOCKED, issues.get(0).getCode());
    }

    @Test
    void contentSecurityHitIsErrorButAiOpinionsAreOnlyWarnings() {
        AiSafetyPrecheckResp resp = new AiSafetyPrecheckResp();
        resp.setPass(false);
        resp.setIssues(Arrays.asList(
                new AiSafetyIssue("error", "content_security", "含违规词"),
                new AiSafetyIssue("error", "safety", "夜间水边任务"),
                new AiSafetyIssue("warn", "gap", "缺少集合点说明")));
        when(ai.safetyPrecheck(any(), anyLong())).thenReturn(resp);
        List<PublishCheckIssue> issues = service.check(topic(), nodes());
        assertEquals(3, issues.size());
        assertEquals(PublishCheckIssue.LEVEL_ERROR, issues.get(0).getLevel());
        assertEquals(TopicAiPrecheckService.CODE_AI_PRECHECK_CONTENT_BLOCKED, issues.get(0).getCode());
        for (PublishCheckIssue i : issues.subList(1, 3)) {
            assertEquals(PublishCheckIssue.LEVEL_WARN, i.getLevel());
            assertEquals(TopicAiPrecheckService.CODE_AI_PRECHECK_RISK, i.getCode());
        }
    }

    @Test
    void contentSecurityOutageAndParseErrorFailOpen() {
        AiSafetyPrecheckResp resp = new AiSafetyPrecheckResp();
        resp.setPass(false);
        resp.setIssues(Arrays.asList(
                new AiSafetyIssue("error", "content_security", IWxContentSecurityService.MSG_BUSY),
                new AiSafetyIssue("error", "parse_error", "AI 响应格式异常,请重试")));
        when(ai.safetyPrecheck(any(), anyLong())).thenReturn(resp);
        assertSingleSkipped(service.check(topic(), nodes()));
    }

    @Test
    void cleanResultAddsNothing() {
        when(ai.safetyPrecheck(any(), anyLong())).thenReturn(pass());
        assertTrue(service.check(topic(), nodes()).isEmpty());
    }

    @Test
    void topicWithoutOwnerIsSkippedWithoutSpendingAnyonesQuota() {
        CmsTopic t = topic();
        t.setMemberId(null);
        assertSingleSkipped(service.check(t, nodes()));
        verify(ai, never()).safetyPrecheck(any(), anyLong());
    }

    @Test
    void providerReceivesOnlyTextNeverLocationOrPhone() {
        when(ai.safetyPrecheck(any(), anyLong())).thenReturn(pass());
        service.check(topic(), nodes());
        ArgumentCaptor<AiSafetyPrecheckReq> captor = ArgumentCaptor.forClass(AiSafetyPrecheckReq.class);
        verify(ai).safetyPrecheck(captor.capture(), eq(7L));
        String payload = JSON.toJSONString(captor.getValue());
        assertTrue(payload.contains("老城夜游"));
        assertTrue(payload.contains("钟楼"));
        for (String leaked : new String[]{"31.230416", "121.473701", "人民路88号", "13812345678"}) {
            assertFalse(payload.contains(leaked), "不得送出:" + leaked);
        }
    }

    private static void assertSingleSkipped(List<PublishCheckIssue> issues) {
        assertEquals(1, issues.size(), String.valueOf(issues));
        assertEquals(PublishCheckIssue.LEVEL_WARN, issues.get(0).getLevel());
        assertEquals(TopicAiPrecheckService.CODE_AI_PRECHECK_SKIPPED, issues.get(0).getCode());
    }

    private static AiSafetyPrecheckResp pass() {
        AiSafetyPrecheckResp resp = new AiSafetyPrecheckResp();
        resp.setPass(true);
        resp.setIssues(Collections.<AiSafetyIssue>emptyList());
        return resp;
    }

    private static CmsTopic topic() {
        CmsTopic t = new CmsTopic();
        t.setId(21L);
        t.setMemberId(7L);
        t.setName("老城夜游");
        t.setDescription("集合点 31.230416,121.473701,联系 13812345678");
        return t;
    }

    private static List<CmsTopicNode> nodes() {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(301L);
        n.setName("钟楼");
        n.setDescription("在钟楼下找到铜牌");
        n.setAddress("人民路88号");
        n.setLatitude("31.230416");
        n.setLongitude("121.473701");
        return Collections.singletonList(n);
    }
}
