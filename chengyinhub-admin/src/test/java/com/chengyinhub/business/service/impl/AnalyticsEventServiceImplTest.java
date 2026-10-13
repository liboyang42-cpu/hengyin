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
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsEventServiceImplTest {

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
