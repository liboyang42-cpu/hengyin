package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopicLike;
import com.chengyinhub.business.mapper.CmsTopicLikeMapper;
import com.chengyinhub.business.service.impl.CmsTopicLikeServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real controller/service calls with a mapper substitute; no database acceptance claim. */
class TopicLikeExplicitCancelConcurrencyTest
{
    private CmsTopicLikeMapper mapper;
    private ApiTopicController controller;

    @BeforeEach
    void setUp()
    {
        mapper = mock(CmsTopicLikeMapper.class);
        CmsTopicLikeServiceImpl service = new CmsTopicLikeServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicLikeMapper", mapper);
        controller = spy(new ApiTopicController());
        doReturn(7L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicLikeService", service);
    }

    private CmsTopicLike row(long id)
    {
        CmsTopicLike row = new CmsTopicLike();
        row.setId(id);
        row.setMemberId(7L);
        row.setDataId(5L);
        return row;
    }

    private void existing()
    {
        when(mapper.selectCmsTopicLikeList(any())).thenReturn(Collections.singletonList(row(99L)));
    }

    @Test
    void twoExplicitCancelsReadingSameRowBothSucceedWhenDeletesReturnOneThenZero() throws Exception
    {
        CyclicBarrier bothReads = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        when(mapper.selectCmsTopicLikeList(any())).thenAnswer(invocation -> {
            CmsTopicLike filter = invocation.getArgument(0);
            assertEquals(Long.valueOf(7L), filter.getMemberId());
            assertEquals(Long.valueOf(5L), filter.getDataId());
            assertEquals(null, filter.getLikeType());
            CmsTopicLike oldRow = row(99L);
            reads.incrementAndGet();
            bothReads.await(5, TimeUnit.SECONDS);
            return Collections.singletonList(oldRow);
        });
        when(mapper.deleteCmsTopicLikeByIds(any())).thenAnswer(invocation -> {
            assertEquals(2, reads.get());
            Long[] ids = invocation.getArgument(0);
            assertEquals(Collections.singletonList(99L), Arrays.asList(ids));
            return deletes.getAndIncrement() == 0 ? 1 : 0;
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AjaxResult> first = executor.submit(() -> controller.topicLike("5", "0"));
            Future<AjaxResult> second = executor.submit(() -> controller.topicLike("5", "0"));
            assertEquals(200, first.get(10, TimeUnit.SECONDS).get("code"));
            assertEquals(200, second.get(10, TimeUnit.SECONDS).get("code"));
            assertEquals(2, deletes.get());
            verify(mapper, times(2)).deleteCmsTopicLikeByIds(new Long[] { 99L });
            verify(mapper, never()).insertCmsTopicLike(any());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void blankLegacyToggleStillFailsWhenDeleteReturnsZero()
    {
        existing();
        when(mapper.deleteCmsTopicLikeByIds(any())).thenReturn(0);
        assertEquals(500, controller.topicLike("5", " ").get("code"));
        verify(mapper).deleteCmsTopicLikeByIds(new Long[] { 99L });
        verify(mapper, never()).insertCmsTopicLike(any());
    }

    @Test
    void explicitCancelDoesNotSwallowDeleteException()
    {
        existing();
        IllegalStateException failure = new IllegalStateException("delete failed");
        when(mapper.deleteCmsTopicLikeByIds(any())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> controller.topicLike("5", "0")));
        verify(mapper, never()).insertCmsTopicLike(any());
    }

    @Test
    void explicitCancelDoesNotAcceptNegativeDeleteCount()
    {
        existing();
        when(mapper.deleteCmsTopicLikeByIds(any())).thenReturn(-1);
        assertEquals(500, controller.topicLike("5", "0").get("code"));
        verify(mapper, never()).insertCmsTopicLike(any());
    }

    @Test
    void explicitCancelDeletesAllSelectedIds()
    {
        when(mapper.selectCmsTopicLikeList(any())).thenReturn(Arrays.asList(row(99L), row(100L)));
        when(mapper.deleteCmsTopicLikeByIds(any())).thenReturn(2);
        assertEquals(200, controller.topicLike("5", "0").get("code"));
        verify(mapper).deleteCmsTopicLikeByIds(new Long[] { 99L, 100L });
        verify(mapper, never()).insertCmsTopicLike(any());
    }
}
