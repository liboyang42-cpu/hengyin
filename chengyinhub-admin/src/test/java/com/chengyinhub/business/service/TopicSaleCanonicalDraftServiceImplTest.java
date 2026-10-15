package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.TopicSaleCanonicalDraft;
import com.chengyinhub.business.domain.dto.TopicSaleCanonicalDraftDTO;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.service.impl.TopicSaleCanonicalDraftServiceImpl;
import com.chengyinhub.business.service.support.TopicSaleCanonicalCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicSaleCanonicalDraftServiceImplTest {
    private static final Long TOPIC = 83L;
    private CmsTopicMapper topicMapper;
    private TopicSaleSnapshotMapper snapshotMapper;
    private TopicSaleCanonicalDraftServiceImpl service;
    private ITopicCommercialReviewService reviewService;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        snapshotMapper = mock(TopicSaleSnapshotMapper.class);
        service = new TopicSaleCanonicalDraftServiceImpl(topicMapper, snapshotMapper);
        reviewService = mock(ITopicCommercialReviewService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "commercialReviewService", reviewService);
        when(reviewService.submitSaleReviewIfReady(TOPIC, 3L)).thenReturn(
                new com.chengyinhub.business.domain.vo.TopicSaleReviewProgress(17L, "已提交开售审核"));
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(3L);
        topic.setLifecycle(TopicLifecycleState.PRICING.getCode());
        topic.setDelFlag(0);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
    }

    @Test
    void firstSaveUsesVersionOneAndRequiresMapperReadback() {
        TopicSaleCanonicalDraftDTO request = request(0L);
        when(snapshotMapper.insertDraft(any(TopicSaleCanonicalDraft.class))).thenReturn(1);
        when(snapshotMapper.selectDraftByTopicId(TOPIC)).thenAnswer(invocation -> {
            TopicSaleCanonicalDraft saved = new TopicSaleCanonicalDraft();
            saved.setTopicId(TOPIC);
            saved.setSchemaVersion(TopicSaleCanonicalV1.SCHEMA_VERSION);
            saved.setParameterVersion(1L);
            saved.setCanonicalJson(TopicSaleCanonicalCodec.writeDraft(request.getCanonical()));
            return saved;
        });

        TopicSaleCanonicalDraft saved = service.save(TOPIC, 7L, request);

        assertEquals(Long.valueOf(1L), saved.getParameterVersion());
        verify(snapshotMapper).insertDraft(any(TopicSaleCanonicalDraft.class));
        verify(snapshotMapper).selectDraftByTopicId(TOPIC);
        assertEquals(Long.valueOf(17L), saved.getSaleReviewProgress().getTaskId());
        verify(reviewService).submitSaleReviewIfReady(TOPIC, 3L);
    }

    @Test
    void replayingAnOldParameterVersionIsRejectedBeforeWrite() {
        TopicSaleCanonicalDraft current = new TopicSaleCanonicalDraft();
        current.setTopicId(TOPIC);
        current.setSchemaVersion(TopicSaleCanonicalV1.SCHEMA_VERSION);
        current.setParameterVersion(2L);
        when(snapshotMapper.selectDraftByTopicIdForUpdate(TOPIC)).thenReturn(current);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.save(TOPIC, 7L, request(1L)));

        assertEquals("canonical 草稿版本冲突", error.getMessage());
        verify(snapshotMapper, never()).updateDraftVersioned(any(TopicSaleCanonicalDraft.class), eq(1L));
        verify(reviewService, never()).submitSaleReviewIfReady(any(), any());
    }

    private static TopicSaleCanonicalDraftDTO request(Long expectedVersion) {
        TopicSaleCanonicalDraftDTO request = new TopicSaleCanonicalDraftDTO();
        request.setSchemaVersion(TopicSaleCanonicalV1.SCHEMA_VERSION);
        request.setExpectedParameterVersion(expectedVersion);
        request.setCanonical(TopicSaleCanonicalTestFixture.canonical());
        return request;
    }
}
