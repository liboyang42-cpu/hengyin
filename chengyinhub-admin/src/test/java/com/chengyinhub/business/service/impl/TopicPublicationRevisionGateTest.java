package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.TopicContentRevisionWrite;
import com.chengyinhub.business.domain.dto.TopicContentRevisionWriteResult;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.ITopicContentRevisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.eq;

/**
 * BE-06 发布写口的原子顺序合同。
 *
 * <p>核心不变量:{@code persistRouteSnapshot} 是四条正式发布路径(直发/过审后发布/草稿发布/建主题自动发布)
 * 的唯一写口。它必须先落修订、再推进发布头;发布头返回 0 时方法返回 0,由调用方抛错回滚整个事务 ——
 * 绝不留下「有修订、没有可见发布头」的可下载孤立版本。
 *
 * <p>本条只证顺序与短路;真实回滚由 Spring 事务 + 真库 IT 证。
 */
class TopicPublicationRevisionGateTest {

    private static final long TOPIC = 10L;
    private static final String FROZEN = "{\"runtimeSnapshotVersion\":1,\"routeMode\":\"BRANCH_GRAPH\"}";

    private TopicPublicationService service;
    private CmsTopicMapper topics;
    private ITopicContentRevisionService revisions;

    @BeforeEach
    void setUp() {
        service = new TopicPublicationService();
        topics = mock(CmsTopicMapper.class);
        revisions = mock(ITopicContentRevisionService.class);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "contentRevisionService", revisions);
    }

    @Test
    void revisionIsWrittenBeforeThePublishedHeadAdvances() {
        when(revisions.recordPublishedRevision(any())).thenReturn(recorded());
        when(topics.publishRouteSnapshot(eq(TOPIC), eq(FROZEN), any(Date.class))).thenReturn(1);

        assertEquals(1, service.persistRouteSnapshot(TOPIC, FROZEN, new Date(), "req-9"));

        InOrder order = inOrder(revisions, topics);
        ArgumentCaptor<TopicContentRevisionWrite> write = ArgumentCaptor.forClass(TopicContentRevisionWrite.class);
        order.verify(revisions).recordPublishedRevision(write.capture());
        order.verify(topics).publishRouteSnapshot(eq(TOPIC), eq(FROZEN), any(Date.class));
        assertEquals(FROZEN, write.getValue().getFrozenSnapshot(), "落成修订的必须是这次编译出的同一份 payload");
        assertEquals("req-9", write.getValue().getPublishRequestId());
    }

    @Test
    void replayOfTheSameRequestDoesNotAdvanceThePublishedHeadAgain() {
        when(revisions.recordPublishedRevision(any())).thenReturn(TopicContentRevisionWriteResult.replay(
                new com.chengyinhub.business.domain.CmsTopicContentRevision()));

        assertEquals(1, service.persistRouteSnapshot(TOPIC, FROZEN, new Date(), "req-9"),
                "重试要按成功返回调用方,否则发布会被误报失败");
        verify(topics, never()).publishRouteSnapshot(any(), any(), any());
    }

    @Test
    void publishedHeadFailureIsReportedToTheCallerSoTheTransactionRollsBack() {
        when(revisions.recordPublishedRevision(any())).thenReturn(recorded());
        when(topics.publishRouteSnapshot(eq(TOPIC), any(), any(Date.class))).thenReturn(0);

        assertEquals(0, service.persistRouteSnapshot(TOPIC, FROZEN, new Date(), "req-9"));
        // 调用方(publishValidatedRouteSnapshot/publishDraftInternal)见到非 1 立即抛错,事务整体回滚 ——
        // 先插入的修订随之消失,DB 里不可能留下可下载的孤立修订。
    }

    @Test
    void legacyThreeArgumentCallKeepsItsOriginalSemanticsAndRequestIdStaysNull() {
        when(revisions.recordPublishedRevision(any())).thenReturn(recorded());
        when(topics.publishRouteSnapshot(eq(TOPIC), eq(FROZEN), any(Date.class))).thenReturn(1);
        ArgumentCaptor<TopicContentRevisionWrite> write = ArgumentCaptor.forClass(TopicContentRevisionWrite.class);

        assertEquals(1, service.persistRouteSnapshot(TOPIC, FROZEN, new Date()));

        verify(revisions).recordPublishedRevision(write.capture());
        assertNull(write.getValue().getPublishRequestId(), "旧调用不得凭空生成/由 hash 推导请求号");
    }

    private static TopicContentRevisionWriteResult recorded() {
        com.chengyinhub.business.domain.CmsTopicContentRevision row =
                new com.chengyinhub.business.domain.CmsTopicContentRevision();
        row.setTopicId(TOPIC);
        row.setRevisionNo(1L);
        row.setContentSha256("sha");
        return TopicContentRevisionWriteResult.recorded(row);
    }
}
