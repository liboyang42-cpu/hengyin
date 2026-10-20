package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.BizEventLog;
import com.chengyinhub.business.domain.dto.AnalyticsBatchDTO;
import com.chengyinhub.business.domain.dto.AnalyticsEventDTO;
import com.chengyinhub.business.mapper.BizEventLogMapper;
import com.chengyinhub.business.mapper.BizFunnelDailyMapper;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.IRecommendationService;
import com.chengyinhub.business.domain.community.CommunityPost;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsEventServiceImplTest {

    @Test
    void acknowledgedReplayUsesOriginalEventOnceAndDoesNotIncrementProfileAgain() {
        ReplayFixture f = new ReplayFixture();
        AnalyticsBatchDTO batch = f.batch("content_view", "same", 42L);
        assertEquals(1, f.service.recordBatch(1001L, batch));
        assertEquals(1, f.service.recordBatch(1001L, batch));
        assertEquals(1, f.events.size());
        org.mockito.Mockito.verify(f.recommendation, org.mockito.Mockito.times(1))
                .updateProfileFromEvent(any(BizEventLog.class));
    }

    @Test
    void conflictOnSameIdentityCannotProduceAcceptedReceipt() {
        ReplayFixture f = new ReplayFixture();
        AnalyticsBatchDTO batch = f.batch("content_view", "same", 42L);
        f.service.recordBatch(1001L, batch);
        batch.getEvents().get(0).setBizId(43L);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> f.service.recordBatch(1001L, batch));
        assertEquals(Long.valueOf(42L), f.events.values().iterator().next().getBizId());
    }

    @Test
    void partialPriorCommitAcknowledgesBothRowsButOnlyCreatesMissingProfileEffect() {
        ReplayFixture f = new ReplayFixture();
        AnalyticsBatchDTO first = f.batch("content_view", "first", 42L);
        AnalyticsBatchDTO second = f.batch("content_view", "second", 43L);
        f.service.recordBatch(1001L, first);
        AnalyticsBatchDTO all = new AnalyticsBatchDTO();
        all.setEvents(Arrays.asList(first.getEvents().get(0), second.getEvents().get(0)));
        assertEquals(2, f.service.recordBatch(1001L, all));
        assertEquals(2, f.events.size());
        org.mockito.Mockito.verify(f.recommendation, org.mockito.Mockito.times(2))
                .updateProfileFromEvent(any(BizEventLog.class));
    }

    @Test
    void qualifiedViewKeepsUserPostDayIdentityAcrossSessions() {
        ReplayFixture f = new ReplayFixture();
        AnalyticsBatchDTO batch = f.batch("community_post_qualified_view", "first", 42L);
        f.service.recordBatch(1001L, batch);
        batch.getEvents().get(0).setSessionId("another-session");
        batch.getEvents().get(0).setIdempotencyKey("another-client-key");
        assertEquals(1, f.service.recordBatch(1001L, batch));
        assertEquals(1, f.events.size());
        org.mockito.Mockito.verify(f.recommendation, org.mockito.Mockito.times(1))
                .updateProfileFromEvent(any(BizEventLog.class));
    }

    @Test
    void actorScopesKeepSameClientKeyIndependentBetweenAccounts() {
        ReplayFixture f = new ReplayFixture();
        AnalyticsBatchDTO batch = f.batch("content_view", "same", 42L);
        assertEquals(1, f.service.recordBatch(1001L, batch));
        assertEquals(1, f.service.recordBatch(2002L, batch));
        assertEquals(2, f.events.size());
    }

    @Test
    void ignoredInsertWithoutDurableRowCannotBeAcknowledged() {
        ReplayFixture f = new ReplayFixture();
        when(f.mapper.insertBizEventLog(any(BizEventLog.class))).thenReturn(0);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> f.service.recordBatch(1001L, f.batch("content_view", "missing", 42L)));
        org.mockito.Mockito.verifyNoInteractions(f.recommendation);
    }

    @Test
    void reorderedJsonAndDatabaseSecondPrecisionDoNotTurnValidReplayIntoConflict() {
        ReplayFixture f = new ReplayFixture();
        AnalyticsBatchDTO batch = f.batch("content_view", "same", 42L);
        batch.getEvents().get(0).setPropertiesJson("{\"b\":2,\"a\":1}");
        f.service.recordBatch(1001L, batch);
        BizEventLog persisted = f.events.values().iterator().next();
        persisted.setOccurredAt(new Date(persisted.getOccurredAt().getTime() / 1000 * 1000));
        batch.getEvents().get(0).setPropertiesJson("{\"a\":1,\"b\":2}");
        assertEquals(1, f.service.recordBatch(1001L, batch));
        batch.getEvents().get(0).setPropertiesJson("{\"a\":99,\"b\":2}");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> f.service.recordBatch(1001L, batch));
    }

    @Test
    void paymentRequiresOwnedCommittedFactAndCannotBeCountedAgainWithAnotherClientKey() {
        ReplayFixture f = new ReplayFixture();
        AnalyticsBatchDTO batch = f.batch("payment_success", "client-first", 42L);
        AnalyticsEventDTO item = batch.getEvents().get(0);
        item.setBizType("activity");
        item.setPropertiesJson("{\"registrationId\":9,\"payableAmount\":99999}");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> f.service.recordBatch(1001L, batch));
        assertEquals(0, f.events.size());
        org.mockito.Mockito.verifyNoInteractions(f.recommendation);

        BizEventLog fact = new BizEventLog();
        fact.setMemberId(1001L);
        fact.setBizType("activity");
        fact.setBizId(42L);
        fact.setOccurredAt(new Date(123456000L));
        when(f.mapper.selectRegistrationEventFact(1001L, 9L, "payment_success")).thenReturn(fact);
        assertEquals(1, f.service.recordBatch(1001L, batch));
        item.setIdempotencyKey("another-client-key");
        item.setSessionId("another-session");
        item.setPropertiesJson("{\"registrationId\":\"9\",\"payableAmount\":1}");
        assertEquals(1, f.service.recordBatch(1001L, batch));
        assertEquals(1, f.events.size());
        BizEventLog stored = f.events.values().iterator().next();
        assertEquals("registration-9", stored.getIdempotencyKey());
        assertEquals(fact.getOccurredAt(), stored.getOccurredAt());
        assertEquals("{\"registrationId\":9}", stored.getPropertiesJson());
        org.mockito.Mockito.verify(f.recommendation, org.mockito.Mockito.times(1))
                .updateProfileFromEvent(any(BizEventLog.class));
    }

    @Test
    void registrationFactCannotBeAttributedToDifferentContentOrAccount() {
        ReplayFixture f = new ReplayFixture();
        AnalyticsBatchDTO batch = f.batch("signup_submit", "signup", 42L);
        batch.getEvents().get(0).setBizType("activity");
        batch.getEvents().get(0).setPropertiesJson("{\"registrationId\":9}");
        BizEventLog fact = new BizEventLog();
        fact.setBizType("activity"); fact.setBizId(43L); fact.setOccurredAt(new Date());
        when(f.mapper.selectRegistrationEventFact(1001L, 9L, "signup_submit")).thenReturn(fact);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> f.service.recordBatch(1001L, batch));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> f.service.recordBatch(2002L, batch));
        assertEquals(0, f.events.size());
        org.mockito.Mockito.verifyNoInteractions(f.recommendation);
    }

    /** An identity-map fixture tests service behavior; it is not a MySQL concurrency claim. */
    private static class ReplayFixture {
        final Map<String, BizEventLog> events = new HashMap<>();
        final BizEventLogMapper mapper = mock(BizEventLogMapper.class);
        final IRecommendationService recommendation = mock(IRecommendationService.class);
        final AnalyticsEventServiceImpl service = new AnalyticsEventServiceImpl();
        ReplayFixture() {
            ReflectionTestUtils.setField(service, "bizEventLogMapper", mapper);
            ReflectionTestUtils.setField(service, "recommendationService", recommendation);
            when(mapper.insertBizEventLog(any(BizEventLog.class))).thenAnswer(invocation -> {
                BizEventLog event = invocation.getArgument(0);
                String key = key(event);
                if (events.containsKey(key)) return 0;
                events.put(key, event);
                return 1;
            });
            when(mapper.selectIdempotentEventForUpdate(any(BizEventLog.class)))
                    .thenAnswer(invocation -> events.get(key(invocation.getArgument(0))));
        }
        String key(BizEventLog event) {
            return event.getEventName() + ":" + event.getActorScope() + ":" + event.getIdempotencyKey();
        }
        AnalyticsBatchDTO batch(String eventName, String key, Long bizId) {
            AnalyticsEventDTO item = new AnalyticsEventDTO();
            item.setEventName(eventName);
            item.setIdempotencyKey(key);
            item.setBizId(bizId);
            item.setBizType("topic");
            item.setOccurredAt(Date.from(Instant.parse("2026-09-10T04:00:00.123Z")));
            AnalyticsBatchDTO batch = new AnalyticsBatchDTO();
            batch.setEvents(Collections.singletonList(item));
            return batch;
        }
    }

    @Test
    void qualifiedViewRequiresVisiblePostAndExcludesItsAuthor() {
        CommunityPostMapper postMapper = mock(CommunityPostMapper.class);
        AnalyticsEventServiceImpl service = new AnalyticsEventServiceImpl();
        ReflectionTestUtils.setField(service, "communityPostMapper", postMapper);
        CommunityPost post = new CommunityPost();
        post.setId(42L);
        post.setAuthorId(1001L);
        when(postMapper.selectById(42L, 1001L)).thenReturn(post);
        when(postMapper.selectById(42L, 2002L)).thenReturn(post);

        assertEquals(1001L, service.visibleCommunityPostAuthor(1001L, 42L));
        assertEquals(1001L, service.visibleCommunityPostAuthor(2002L, 42L));
        assertEquals(null, service.visibleCommunityPostAuthor(2002L, 99L));
    }

    @Test
    void recordBatchDoesNotPersistClientControlledAnonymousId() {
        BizEventLogMapper eventLogMapper = mock(BizEventLogMapper.class);
        IRecommendationService recommendationService = mock(IRecommendationService.class);
        when(eventLogMapper.insertBizEventLog(any(BizEventLog.class))).thenReturn(1);
        AnalyticsEventServiceImpl service = new AnalyticsEventServiceImpl();
        ReflectionTestUtils.setField(service, "bizEventLogMapper", eventLogMapper);
        ReflectionTestUtils.setField(service, "recommendationService", recommendationService);

        AnalyticsEventDTO item = new AnalyticsEventDTO();
        item.setEventName("community_post_qualified_view");
        item.setAnonymousId("wx-open-id-from-client");
        item.setBizId(42L);
        item.setIdempotencyKey("client-can-change-this");
        item.setOccurredAt(Date.from(Instant.parse("2026-09-10T04:00:00Z")));
        AnalyticsBatchDTO request = new AnalyticsBatchDTO();
        request.setEvents(Collections.singletonList(item));

        assertEquals(1, service.recordBatch(1001L, request));

        ArgumentCaptor<BizEventLog> captor = ArgumentCaptor.forClass(BizEventLog.class);
        verify(eventLogMapper).insertBizEventLog(captor.capture());
        assertEquals(1001L, captor.getValue().getMemberId());
        assertEquals("", captor.getValue().getAnonymousId());
        assertEquals("community-post-qualified-42-2026-09-10", captor.getValue().getIdempotencyKey(),
                "合格阅读必须按 user-post-day 去重，跨日真实消费不能被永久吞掉");
        verify(recommendationService).updateProfileFromEvent(captor.getValue());
    }

    @Test
    void aggregateDailyRefreshesDailyFunnelRows() {
        BizFunnelDailyMapper funnelDailyMapper = mock(BizFunnelDailyMapper.class);
        AnalyticsEventServiceImpl service = new AnalyticsEventServiceImpl();
        ReflectionTestUtils.setField(service, "bizFunnelDailyMapper", funnelDailyMapper);
        when(funnelDailyMapper.deleteByStatDate("2026-06-19")).thenReturn(2);
        when(funnelDailyMapper.insertFromEventLog("2026-06-19")).thenReturn(5);

        int rows = service.aggregateDaily("2026-06-19");

        assertEquals(5, rows);
        verify(funnelDailyMapper).deleteByStatDate("2026-06-19");
        verify(funnelDailyMapper).insertFromEventLog("2026-06-19");
    }

    @Test
    void publicIngestionNeverSynchronouslyRebuildsHistoricalDailyProjection() {
        BizEventLogMapper eventLogMapper = mock(BizEventLogMapper.class);
        BizFunnelDailyMapper funnelDailyMapper = mock(BizFunnelDailyMapper.class);
        IRecommendationService recommendationService = mock(IRecommendationService.class);
        when(eventLogMapper.insertBizEventLog(any(BizEventLog.class))).thenReturn(1);
        AnalyticsEventServiceImpl service = new AnalyticsEventServiceImpl();
        ReflectionTestUtils.setField(service, "bizEventLogMapper", eventLogMapper);
        ReflectionTestUtils.setField(service, "bizFunnelDailyMapper", funnelDailyMapper);
        ReflectionTestUtils.setField(service, "recommendationService", recommendationService);

        AnalyticsEventDTO item = new AnalyticsEventDTO();
        item.setEventName("community_post_open");
        AnalyticsBatchDTO request = new AnalyticsBatchDTO();
        request.setEvents(Collections.singletonList(item));

        service.recordBatch(1001L, request);

        org.mockito.Mockito.verifyNoInteractions(funnelDailyMapper);
    }
}
