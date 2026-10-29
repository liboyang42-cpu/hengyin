package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.domain.dto.ChapterSupplyTermsDTO;
import com.chengyinhub.business.domain.dto.ChapterServiceCapacityDTO;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper;
import com.chengyinhub.business.mapper.MerchantChapterApplicationMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.service.impl.CmsTopicChapterServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 章节写入口服从父主题生命周期；四个历史锁序 case 名保留，断言改为批准的 topic→chapter 顺序。 */
class CmsTopicChapterLifecycleGuardTest
{
    private static final Long TOPIC_ID = 81L;
    private static final Long CHAPTER_ID = 18L;

    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicMapper topicMapper;
    private MerchantChapterApplicationMapper applicationMapper;
    private MerchantChapterOfferMapper offerMapper;
    private CmsTopicTemplateMetaMapper metaMapper;
    private ITopicOperationsScopeAuthorizer scopeAuthorizer;
    private CmsTopicChapterServiceImpl service;

    @BeforeEach
    void setUp()
    {
        chapterMapper = mock(CmsTopicChapterMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        applicationMapper = mock(MerchantChapterApplicationMapper.class);
        offerMapper = mock(MerchantChapterOfferMapper.class);
        metaMapper = mock(CmsTopicTemplateMetaMapper.class);
        scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        service = new CmsTopicChapterServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        com.chengyinhub.business.service.support.TopicContentBudgetTestWiring.chapter(service);
        ReflectionTestUtils.setField(service, "merchantChapterApplicationMapper", applicationMapper);
        ReflectionTestUtils.setField(service, "merchantChapterOfferMapper", offerMapper);
        ReflectionTestUtils.setField(service, "cmsTopicTemplateMetaMapper", metaMapper);
        ReflectionTestUtils.setField(service, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        // P1-LC-1:章节写入口多了「已发布免费主题禁转商业」的写后复核。本类测生命周期冻结,
        // 政策桩默认「不受保护」(isConversionProtected=false ⇒ 复核 no-op)。
        ReflectionTestUtils.setField(service, "commercialTopicPolicy",
                mock(com.chengyinhub.business.service.support.CommercialTopicPolicy.class));
        ReflectionTestUtils.setField(service, "productRoleService", mock(IProductRoleService.class));

        when(chapterMapper.selectCmsTopicChapterById(CHAPTER_ID)).thenReturn(chapter());
        when(chapterMapper.lockChaptersForCapacity(Collections.singletonList(CHAPTER_ID)))
                .thenReturn(Collections.singletonList(CHAPTER_ID));
        when(applicationMapper.countCurrentByChapter(CHAPTER_ID)).thenReturn(0);
        when(offerMapper.countAllByChapter(CHAPTER_ID)).thenReturn(0);
        when(scopeAuthorizer.canViewTopic(TOPIC_ID)).thenReturn(true);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic(TopicLifecycleState.SELLING));
    }

    @Test
    void sellingTopicRejectsAddEditDeleteAndSupplyTermsWrites()
    {
        IllegalStateException add = assertThrows(IllegalStateException.class,
                () -> service.insertCmsTopicChapter(chapter()));
        IllegalStateException edit = assertThrows(IllegalStateException.class,
                () -> service.updateCmsTopicChapter(chapter()));
        IllegalStateException delete = assertThrows(IllegalStateException.class,
                () -> service.deleteCmsTopicChapterById(CHAPTER_ID));
        ChapterSupplyTermsDTO terms = new ChapterSupplyTermsDTO();
        terms.setTermsMode("PERK");
        terms.setMaxMerchant(1);
        IllegalStateException supplyTerms = assertThrows(IllegalStateException.class,
                () -> service.configureSupplyTerms(CHAPTER_ID, terms));
        IllegalStateException capacity = assertThrows(IllegalStateException.class,
                () -> service.configureServiceCapacity(CHAPTER_ID, capacity()));

        assertTrue(add.getMessage().contains("SELLING"));
        assertTrue(edit.getMessage().contains("SELLING"));
        assertTrue(delete.getMessage().contains("SELLING"));
        assertTrue(supplyTerms.getMessage().contains("SELLING"));
        assertTrue(capacity.getMessage().contains("SELLING"));
        assertTrue(capacity.getMessage().contains("人工批次容量"));
        verify(chapterMapper, never()).insertCmsTopicChapter(any());
        verify(chapterMapper, never()).updateCmsTopicChapter(any());
        verify(chapterMapper, never()).deleteCmsTopicChapterById(anyLong());
        verify(chapterMapper, never()).updateSupplyTerms(anyLong(), any(), any(), any(), any(), any());
        verify(metaMapper, never()).upsertChapterServiceCapacity(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void missingOrUnknownParentLifecycleFailsClosed()
    {
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(null);
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> service.insertCmsTopicChapter(chapter()));

        CmsTopic unknown = new CmsTopic();
        unknown.setId(TOPIC_ID);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(unknown);
        IllegalStateException lifecycleUnknown = assertThrows(IllegalStateException.class,
                () -> service.insertCmsTopicChapter(chapter()));

        unknown.setLifecycle(-1);
        IllegalStateException lifecycleOutOfRange = assertThrows(IllegalStateException.class,
                () -> service.insertCmsTopicChapter(chapter()));

        assertTrue(missing.getMessage().contains("不存在") || missing.getMessage().contains("生命周期未知"));
        assertTrue(lifecycleUnknown.getMessage().contains("生命周期未知"));
        assertTrue(lifecycleOutOfRange.getMessage().contains("生命周期未知"));
        verify(chapterMapper, never()).insertCmsTopicChapter(any());
    }

    @Test
    void pendingReviewDoesNotMasqueradeAsSaleStarted()
    {
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID))
                .thenReturn(topic(TopicLifecycleState.PENDING_REVIEW));

        assertDoesNotThrow(() -> service.insertCmsTopicChapter(chapter()));
    }

    @Test
    void nonOwnerScopeRejectsEveryWriteInsideService()
    {
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic(TopicLifecycleState.DRAFT));
        when(scopeAuthorizer.canViewTopic(TOPIC_ID)).thenReturn(false);
        ChapterSupplyTermsDTO terms = new ChapterSupplyTermsDTO();
        terms.setTermsMode("PERK");
        terms.setMaxMerchant(1);

        assertThrows(IllegalStateException.class, () -> service.insertCmsTopicChapter(chapter()));
        assertThrows(IllegalStateException.class, () -> service.updateCmsTopicChapter(chapter()));
        assertThrows(IllegalStateException.class, () -> service.deleteCmsTopicChapterById(CHAPTER_ID));
        assertThrows(IllegalStateException.class, () -> service.configureSupplyTerms(CHAPTER_ID, terms));
        assertThrows(IllegalStateException.class,
                () -> service.configureServiceCapacity(CHAPTER_ID, capacity()));

        verify(chapterMapper, never()).insertCmsTopicChapter(any());
        verify(chapterMapper, never()).updateCmsTopicChapter(any());
        verify(chapterMapper, never()).deleteCmsTopicChapterById(anyLong());
        verify(chapterMapper, never()).updateSupplyTerms(anyLong(), any(), any(), any(), any(), any());
        verify(metaMapper, never()).upsertChapterServiceCapacity(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void existingChapterWritesLockChapterBeforeParentTopic()
    {
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic(TopicLifecycleState.DRAFT));

        service.updateCmsTopicChapter(chapter());

        org.mockito.InOrder locks = org.mockito.Mockito.inOrder(chapterMapper, topicMapper);
        locks.verify(topicMapper).lockTopicContentBudget(TOPIC_ID);
        locks.verify(topicMapper).selectCmsTopicByIdForUpdate(TOPIC_ID);
        locks.verify(chapterMapper).selectContentByIdForUpdate(CHAPTER_ID);
    }

    @Test
    void deleteLocksChapterBeforeParentTopic()
    {
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic(TopicLifecycleState.DRAFT));

        service.deleteCmsTopicChapterById(CHAPTER_ID);

        org.mockito.InOrder locks = org.mockito.Mockito.inOrder(chapterMapper, topicMapper);
        locks.verify(topicMapper).lockTopicContentBudget(TOPIC_ID);
        locks.verify(topicMapper).selectCmsTopicByIdForUpdate(TOPIC_ID);
        locks.verify(chapterMapper).selectContentByIdForUpdate(CHAPTER_ID);
    }

    @Test
    void supplyTermsLockChapterBeforeParentTopic()
    {
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic(TopicLifecycleState.DRAFT));
        ChapterSupplyTermsDTO terms = new ChapterSupplyTermsDTO();
        terms.setTermsMode("PERK");
        terms.setMaxMerchant(0);

        service.configureSupplyTerms(CHAPTER_ID, terms);

        org.mockito.InOrder locks = org.mockito.Mockito.inOrder(chapterMapper, topicMapper);
        locks.verify(topicMapper).lockTopicContentBudget(TOPIC_ID);
        locks.verify(topicMapper).selectCmsTopicByIdForUpdate(TOPIC_ID);
        locks.verify(chapterMapper).selectContentByIdForUpdate(CHAPTER_ID);
    }

    @Test
    void serviceCapacityLocksChapterBeforeParentTopic()
    {
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic(TopicLifecycleState.DRAFT));

        service.configureServiceCapacity(CHAPTER_ID, capacity());

        org.mockito.InOrder locks = org.mockito.Mockito.inOrder(chapterMapper, topicMapper);
        locks.verify(topicMapper).lockTopicContentBudget(TOPIC_ID);
        locks.verify(topicMapper).selectCmsTopicByIdForUpdate(TOPIC_ID);
        locks.verify(chapterMapper).selectContentByIdForUpdate(CHAPTER_ID);
    }

    private ChapterServiceCapacityDTO capacity()
    {
        ChapterServiceCapacityDTO capacity = new ChapterServiceCapacityDTO();
        capacity.setBatchSize(4);
        capacity.setBatchIntervalMinutes(15);
        capacity.setEffortMaxConcurrent(2);
        return capacity;
    }

    private CmsTopicChapter chapter()
    {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(CHAPTER_ID);
        chapter.setTopicId(TOPIC_ID);
        chapter.setName("第一店");
        chapter.setSortId(1L);
        chapter.setRequired(1);
        chapter.setTermsMode("PERK");
        chapter.setMaxMerchant(1);
        chapter.setRecruitEnabled(0);
        return chapter;
    }

    private CmsTopic topic(TopicLifecycleState lifecycle)
    {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setLifecycle(lifecycle.getCode());
        topic.setDelFlag(0);
        return topic;
    }
}
