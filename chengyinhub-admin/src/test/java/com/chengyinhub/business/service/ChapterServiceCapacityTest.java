package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta;
import com.chengyinhub.business.domain.dto.ChapterServiceCapacityDTO;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper;
import com.chengyinhub.business.service.impl.CmsTopicChapterServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 人工批次容量必须三数成套、按章节归属写入，并完整读回。 */
class ChapterServiceCapacityTest
{
    private static final Long TOPIC_ID = 81L;
    private static final Long CHAPTER_ID = 18L;

    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicMapper topicMapper;
    private CmsTopicTemplateMetaMapper metaMapper;
    private ITopicOperationsScopeAuthorizer scopeAuthorizer;
    private CmsTopicChapterServiceImpl service;

    @BeforeEach
    void setUp()
    {
        chapterMapper = mock(CmsTopicChapterMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        metaMapper = mock(CmsTopicTemplateMetaMapper.class);
        scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        service = new CmsTopicChapterServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        com.chengyinhub.business.service.support.TopicContentBudgetTestWiring.chapter(service);
        ReflectionTestUtils.setField(service, "cmsTopicTemplateMetaMapper", metaMapper);
        ReflectionTestUtils.setField(service, "topicOperationsScopeAuthorizer", scopeAuthorizer);

        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(CHAPTER_ID);
        chapter.setTopicId(TOPIC_ID);
        when(chapterMapper.lockChaptersForCapacity(Collections.singletonList(CHAPTER_ID)))
                .thenReturn(Collections.singletonList(CHAPTER_ID));
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER_ID)).thenReturn(chapter);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setDelFlag(0);
        topic.setLifecycle(TopicLifecycleState.DRAFT.getCode());
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        when(scopeAuthorizer.canViewTopic(TOPIC_ID)).thenReturn(true);
    }

    static Stream<Arguments> invalidTriples()
    {
        return Stream.of(
                Arguments.of(null, 15, 2, "每批人数"),
                Arguments.of(0, 15, 2, "每批人数"),
                Arguments.of(-1, 15, 2, "每批人数"),
                Arguments.of(4, null, 2, "批次间隔"),
                Arguments.of(4, 0, 2, "批次间隔"),
                Arguments.of(4, -1, 2, "批次间隔"),
                Arguments.of(4, 15, null, "并行位"),
                Arguments.of(4, 15, 0, "并行位"),
                Arguments.of(4, 15, -1, "并行位")
        );
    }

    @ParameterizedTest(name = "batch={0}, interval={1}, concurrent={2}")
    @MethodSource("invalidTriples")
    void anyMissingZeroOrNegativeValueIsRejectedPrecisely(Integer batchSize,
                                                            Integer intervalMinutes,
                                                            Integer maxConcurrent,
                                                            String expectedField)
    {
        ChapterServiceCapacityDTO capacity = capacity(batchSize, intervalMinutes, maxConcurrent);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.configureServiceCapacity(CHAPTER_ID, capacity));

        assertTrue(error.getMessage().contains(expectedField), error.getMessage());
        verify(chapterMapper, never()).lockChaptersForCapacity(org.mockito.ArgumentMatchers.anyList());
        verify(chapterMapper, never()).selectContentByIdForUpdate(org.mockito.ArgumentMatchers.anyLong());
        verify(topicMapper, never()).selectCmsTopicByIdForUpdate(org.mockito.ArgumentMatchers.anyLong());
        verify(metaMapper, never()).upsertChapterServiceCapacity(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void nullDtoFailsBeforeChapterOrTopicLock()
    {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.configureServiceCapacity(CHAPTER_ID, null));

        assertTrue(error.getMessage().contains("缺少章节服务容量配置"));
        verify(chapterMapper, never()).lockChaptersForCapacity(org.mockito.ArgumentMatchers.anyList());
        verify(chapterMapper, never()).selectContentByIdForUpdate(org.mockito.ArgumentMatchers.anyLong());
        verify(topicMapper, never()).selectCmsTopicByIdForUpdate(org.mockito.ArgumentMatchers.anyLong());
        verify(metaMapper, never()).upsertChapterServiceCapacity(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void validWriteAlwaysPersistsTheCompleteTuple()
    {
        service.configureServiceCapacity(CHAPTER_ID, capacity(4, 15, 2));
        service.configureServiceCapacity(CHAPTER_ID, capacity(6, 15, 2));

        verify(metaMapper).upsertChapterServiceCapacity(CHAPTER_ID, TOPIC_ID, 4, 15, 2);
        verify(metaMapper).upsertChapterServiceCapacity(CHAPTER_ID, TOPIC_ID, 6, 15, 2);
    }

    @Test
    void readBackReturnsAllThreePersistedValues()
    {
        CmsTopicChapterTemplateMeta meta = new CmsTopicChapterTemplateMeta();
        meta.setChapterId(CHAPTER_ID);
        meta.setBatchSize(6);
        meta.setBatchIntervalMinutes(20);
        meta.setEffortMaxConcurrent(3);
        when(metaMapper.selectChapterMetaByChapterId(CHAPTER_ID)).thenReturn(meta);

        ChapterServiceCapacityDTO result = service.getServiceCapacity(CHAPTER_ID);

        assertEquals(Integer.valueOf(6), result.getBatchSize());
        assertEquals(Integer.valueOf(20), result.getBatchIntervalMinutes());
        assertEquals(Integer.valueOf(3), result.getEffortMaxConcurrent());
    }

    @Test
    void nonOwnerScopeFailsClosedBeforeCapacityWrite()
    {
        when(scopeAuthorizer.canViewTopic(TOPIC_ID)).thenReturn(false);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.configureServiceCapacity(CHAPTER_ID, capacity(4, 15, 2)));

        assertTrue(error.getMessage().contains("无操作范围"));
        verify(metaMapper, never()).upsertChapterServiceCapacity(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    private static ChapterServiceCapacityDTO capacity(Integer batchSize, Integer interval, Integer concurrent)
    {
        ChapterServiceCapacityDTO capacity = new ChapterServiceCapacityDTO();
        capacity.setBatchSize(batchSize);
        capacity.setBatchIntervalMinutes(interval);
        capacity.setEffortMaxConcurrent(concurrent);
        return capacity;
    }
}
