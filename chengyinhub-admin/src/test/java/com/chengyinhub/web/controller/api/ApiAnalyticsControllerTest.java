package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.AnalyticsBatchDTO;
import com.chengyinhub.business.domain.dto.AnalyticsEventDTO;
import com.chengyinhub.business.service.IAnalyticsEventService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiAnalyticsControllerTest {

    private IAnalyticsEventService analyticsEventService;
    private TestableApiAnalyticsController controller;

    @BeforeEach
    void setUp() {
        analyticsEventService = mock(IAnalyticsEventService.class);
        controller = new TestableApiAnalyticsController(1001L);
        ReflectionTestUtils.setField(controller, "analyticsEventService", analyticsEventService);
    }

    @Test
    void recordBatchRejectsUnkeyedGeneralEventBeforePersistence() {
        AjaxResult result = controller.recordBatch(batch(event("content_view", null)));
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("埋点必须携带幂等键", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(analyticsEventService);
    }

    @Test
    void conflictingIdentityReturnsFailureRatherThanSuccessAcceptedZero() {
        AnalyticsBatchDTO request = batch(event("content_view", "conflict"));
        when(analyticsEventService.recordBatch(1001L, request))
                .thenThrow(new IllegalArgumentException("private event details"));
        AjaxResult result = controller.recordBatch(request);
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("事件幂等键已用于不同内容或未确认落库", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void recordBatchRejectsUnknownEventName() {
        AnalyticsBatchDTO batch = batch(event("unknown_event", "id-1"));

        AjaxResult result = controller.recordBatch(batch);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("不支持的埋点事件：unknown_event", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void authenticatedAnalyticsEndpointHasMemberRateLimit() throws Exception {
        Method method = ApiAnalyticsController.class.getMethod("recordBatch", AnalyticsBatchDTO.class);
        RateLimiter limiter = method.getAnnotation(RateLimiter.class);
        assertNotNull(limiter);
        assertEquals(LimitType.MEMBER, limiter.limitType());
        assertEquals(60, limiter.time());
        assertEquals(30, limiter.count());
    }

    @Test
    void recordBatchRejectsWholeBatchWhenThirtySeventhEventIsUnknown() {
        List<AnalyticsEventDTO> events = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            events.add(event(i == 36 ? "client_only_event" : "content_view", "id-" + i));
        }

        AjaxResult result = controller.recordBatch(batch(events));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("不支持的埋点事件：client_only_event", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(analyticsEventService);
    }

    @Test
    void recordBatchRejectsMoreThanFiftyEvents() {
        List<AnalyticsEventDTO> events = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            events.add(event("content_view", "id-" + i));
        }

        AjaxResult result = controller.recordBatch(batch(events));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("单次最多上报50条事件", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void recordBatchRejectsSensitiveAnalyticsProperties() {
        AnalyticsEventDTO event = event("content_view", "id-sensitive");
        event.setPropertiesJson("{\"phone\":\"13800138000\",\"address\":\"上海\"}");

        AjaxResult result = controller.recordBatch(batch(event));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("事件属性不得包含敏感个人信息", result.get(AjaxResult.MSG_TAG));
    }

    @SuppressWarnings("unchecked")
    @Test
    void recordBatchReturnsAcceptedCountFromService() {
        AnalyticsBatchDTO batch = batch(event("content_view", "id-1"));
        when(analyticsEventService.recordBatch(eq(1001L), eq(batch))).thenReturn(1);

        AjaxResult result = controller.recordBatch(batch);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        assertEquals(1, data.get("accepted"));
        verify(analyticsEventService).recordBatch(1001L, batch);
    }

    @Test
    void recordBatchAcceptsDistinctCommunityPostConsumptionEvents() {
        List<AnalyticsEventDTO> events = Arrays.asList(
                event("community_post_impression", "post-impression-42"),
                event("community_post_open", "post-open-42"),
                event("community_post_qualified_view", "post-qualified-42"));
        for (AnalyticsEventDTO event : events) {
            event.setBizType("community_post");
            event.setActorScope("member:1001");
        }
        AnalyticsBatchDTO batch = batch(events);
        when(analyticsEventService.visibleCommunityPostAuthor(1001L, 10L)).thenReturn(2002L);
        when(analyticsEventService.recordBatch(eq(1001L), eq(batch))).thenReturn(3);

        AjaxResult result = controller.recordBatch(batch);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(analyticsEventService).visibleCommunityPostAuthor(1001L, 10L);
        verify(analyticsEventService).recordBatch(1001L, batch);
    }

    @Test
    void recordBatchRejectsOversizedScalarFieldBeforePersistence() {
        AnalyticsEventDTO event = event("content_view", "id-long-path");
        event.setPagePath(String.join("", Collections.nCopies(256, "x")));

        AjaxResult result = controller.recordBatch(batch(event));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("事件字段超过长度限制", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(analyticsEventService);
    }

    @Test
    void recordBatchRejectsCommunityEventCapturedUnderAnotherPrincipalGeneration() {
        AnalyticsEventDTO event = event("community_post_open", "post-open-42");
        event.setBizType("community_post");
        event.setActorScope("member:2002");

        AjaxResult result = controller.recordBatch(batch(event));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("登录状态已变化，请丢弃旧埋点", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(analyticsEventService);
    }

    @Test
    void recordBatchRejectsCommunityNamedEventWithMismatchedBizType() {
        AnalyticsEventDTO event = event("community_post_open", "post-open-wrong-type");
        event.setBizType("template");
        event.setActorScope("member:1001");

        AjaxResult result = controller.recordBatch(batch(event));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("社区事件业务类型必须为 community_post", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(analyticsEventService);
    }

    @Test
    void recordBatchNormalizesBizTypeBeforeCommunitySecurityChecks() {
        AnalyticsEventDTO event = event("content_like", "like-42");
        event.setBizType(" COMMUNITY_POST ");
        event.setBizId(42L);
        event.setActorScope("member:1001");
        when(analyticsEventService.visibleCommunityPostAuthor(1001L, 42L)).thenReturn(2002L);
        AnalyticsBatchDTO batch = batch(event);
        when(analyticsEventService.recordBatch(1001L, batch)).thenReturn(1);

        AjaxResult result = controller.recordBatch(batch);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("community_post", event.getBizType());
        verify(analyticsEventService).visibleCommunityPostAuthor(1001L, 42L);
    }

    @Test
    void recordBatchRejectsBizTypeOutsideCanonicalWireFormat() {
        AnalyticsEventDTO event = event("content_view", "bad-biz-type");
        event.setBizType("community/post");

        AjaxResult result = controller.recordBatch(batch(event));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("业务类型格式不合法", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(analyticsEventService);
    }

    @Test
    void recordBatchRejectsAnyScopedEventCapturedUnderAnotherPrincipalGeneration() {
        AnalyticsEventDTO event = event("search_submit", "search-42");
        event.setActorScope("member:2002");

        AjaxResult result = controller.recordBatch(batch(event));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("登录状态已变化，请丢弃旧埋点", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(analyticsEventService);
    }

    @Test
    void recordBatchRejectsCommunityEventWithoutIdempotencyKey() {
        AnalyticsEventDTO event = event("community_post_open", null);
        event.setBizType("community_post");
        event.setActorScope("member:1001");

        AjaxResult result = controller.recordBatch(batch(event));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("社区埋点必须携带幂等键", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(analyticsEventService);
    }

    @Test
    void recordBatchRejectsOccurredAtOutsideTheBoundedLateArrivalWindow() {
        AnalyticsEventDTO event = event("community_post_open", "post-open-old");
        event.setBizType("community_post");
        event.setActorScope("member:1001");
        event.setOccurredAt(new Date(System.currentTimeMillis() - 15L * 24 * 60 * 60 * 1000));

        AjaxResult result = controller.recordBatch(batch(event));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("事件时间超出允许窗口", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(analyticsEventService);
    }

    @Test
    void recordBatchRejectsOccurredAtBeyondSmallClockSkew() {
        AnalyticsEventDTO event = event("community_post_open", "post-open-future");
        event.setBizType("community_post");
        event.setActorScope("member:1001");
        event.setOccurredAt(new Date(System.currentTimeMillis() + 6L * 60 * 1000));

        AjaxResult result = controller.recordBatch(batch(event));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("事件时间超出允许窗口", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(analyticsEventService);
    }

    @Test
    void recordBatchRejectsAnonymousQualifiedViewBecausePilotDenominatorRequiresAUser() {
        controller = new TestableApiAnalyticsController(null);
        ReflectionTestUtils.setField(controller, "analyticsEventService", analyticsEventService);
        AnalyticsEventDTO event = event("community_post_qualified_view", "post-qualified-42");
        event.setBizType("community_post");
        event.setBizId(42L);
        event.setActorScope("anonymous");

        AjaxResult result = controller.recordBatch(batch(event));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("行为采集需要登录", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(analyticsEventService);
    }

    @Test
    void recordBatchRejectsAnonymousFinancialEvents() {
        controller = new TestableApiAnalyticsController(null);
        ReflectionTestUtils.setField(controller, "analyticsEventService", analyticsEventService);
        AnalyticsEventDTO event = event("payment_success", "fake-payment");

        AjaxResult result = controller.recordBatch(batch(event));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("行为采集需要登录", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(analyticsEventService);
    }

    @Test
    void recordBatchRejectsQualifiedViewWithoutPostId() {
        AnalyticsEventDTO event = event("community_post_qualified_view", "post-qualified-missing");
        event.setBizType("community_post");
        event.setBizId(null);
        event.setActorScope("member:1001");

        AjaxResult result = controller.recordBatch(batch(event));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("社区埋点必须关联有效帖文", result.get(AjaxResult.MSG_TAG));
        verifyNoInteractions(analyticsEventService);
    }

    @Test
    void recordBatchRejectsQualifiedViewForMissingOrInvisiblePost() {
        AnalyticsEventDTO event = event("community_post_qualified_view", "post-qualified-42");
        event.setBizType("community_post");
        event.setBizId(42L);
        event.setActorScope("member:1001");
        when(analyticsEventService.visibleCommunityPostAuthor(1001L, 42L)).thenReturn(null);

        AjaxResult result = controller.recordBatch(batch(event));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("帖文不存在或不可见", result.get(AjaxResult.MSG_TAG));
        verify(analyticsEventService).visibleCommunityPostAuthor(1001L, 42L);
    }

    @Test
    void recordBatchRejectsQualifiedViewFromPostAuthor() {
        AnalyticsEventDTO event = event("community_post_qualified_view", "post-qualified-own");
        event.setBizType("community_post");
        event.setBizId(42L);
        event.setActorScope("member:1001");
        when(analyticsEventService.visibleCommunityPostAuthor(1001L, 42L)).thenReturn(1001L);

        AjaxResult result = controller.recordBatch(batch(event));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("帖文不可见或不能计入合格阅读", result.get(AjaxResult.MSG_TAG));
        verify(analyticsEventService).visibleCommunityPostAuthor(1001L, 42L);
        verifyNoMoreInteractions(analyticsEventService);
    }

    @Test
    void recordBatchAcceptsRoamAiSurfaceEventWithoutSensitiveProperties() {
        AnalyticsEventDTO event = event("roam_route_story_shown", "id-roam-story");
        event.setBizId(0L);
        event.setPropertiesJson("{}");
        AnalyticsBatchDTO batch = batch(event);
        when(analyticsEventService.recordBatch(eq(1001L), eq(batch))).thenReturn(1);

        AjaxResult result = controller.recordBatch(batch);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(analyticsEventService).recordBatch(1001L, batch);
    }

    @Test
    void recordBatchRejectsRoamAiSurfaceEventLinkedToPoiOrStory() {
        AnalyticsEventDTO event = event("roam_poi_meaning_shown", "id-roam-poi");
        event.setBizId(12L);
        event.setPropertiesJson("{}");

        AjaxResult result = controller.recordBatch(batch(event));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("漫游 AI 表面埋点不得关联业务对象或携带属性", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void recordBatchRejectsRoamAiSurfaceEventWithAnyProperty() {
        AnalyticsEventDTO event = event("roam_footprint_hint_shown", "id-roam-footprint");
        event.setBizId(0L);
        event.setPropertiesJson("{\"x\":1}");

        AjaxResult result = controller.recordBatch(batch(event));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("漫游 AI 表面埋点不得关联业务对象或携带属性", result.get(AjaxResult.MSG_TAG));
    }

    private static AnalyticsBatchDTO batch(AnalyticsEventDTO event) {
        return batch(Collections.singletonList(event));
    }

    private static AnalyticsBatchDTO batch(List<AnalyticsEventDTO> events) {
        AnalyticsBatchDTO batch = new AnalyticsBatchDTO();
        batch.setEvents(events);
        return batch;
    }

    private static AnalyticsEventDTO event(String eventName, String idempotencyKey) {
        AnalyticsEventDTO event = new AnalyticsEventDTO();
        event.setEventName(eventName);
        event.setIdempotencyKey(idempotencyKey);
        event.setPagePath("/pages/template/index");
        event.setBizType("template");
        event.setBizId(10L);
        event.setOccurredAt(new Date());
        return event;
    }

    private static class TestableApiAnalyticsController extends ApiAnalyticsController {
        private final Long appUserId;

        private TestableApiAnalyticsController(Long appUserId) {
            this.appUserId = appUserId;
        }

        @Override
        public Long getAppUserId() {
            return appUserId;
        }
    }
}
