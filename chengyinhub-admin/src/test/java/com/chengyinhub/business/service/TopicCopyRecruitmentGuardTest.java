package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 复制主题直接写 mapper，也必须不扩散历史无效的章节招商配置。 */
class TopicCopyRecruitmentGuardTest {

    private static final Long SOURCE_TOPIC = 41L;
    private static final Long NEW_TOPIC = 42L;

    private CmsTopicMapper topicMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicNodeMapper nodeMapper;
    private CmsTopicServiceImpl service;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodeMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "sysCategoryService", mock(ISysCategoryService.class));
        // CORE OWN-C03-13：复制写入章节后须生成有效身份，否则 fail-closed；夹具按真实插入回填 id。
        java.util.concurrent.atomic.AtomicLong chapterIds = new java.util.concurrent.atomic.AtomicLong(600L);
        org.mockito.Mockito.when(chapterMapper.insertCmsTopicChapter(org.mockito.ArgumentMatchers.any(CmsTopicChapter.class))).thenAnswer(invocation -> {
            ((CmsTopicChapter) invocation.getArgument(0)).setId(chapterIds.incrementAndGet());
            return 1;
        });
    }

    @Test
    void copy_rejectsSecondLegacyInvalidCapacityInsideTransactionalCopy() throws Exception {
        CmsTopic original = new CmsTopic();
        original.setId(SOURCE_TOPIC);
        original.setProductType(ProductType.FREE_EXPLORE);
        CmsTopicChapter validChapter = new CmsTopicChapter();
        validChapter.setId(6L);
        validChapter.setTopicId(SOURCE_TOPIC);
        validChapter.setRecruitEnabled(0);
        validChapter.setMaxMerchant(0);
        CmsTopicChapter legacyChapter = new CmsTopicChapter();
        legacyChapter.setId(7L);
        legacyChapter.setTopicId(SOURCE_TOPIC);
        legacyChapter.setRecruitEnabled(1);
        legacyChapter.setCategoryId(18);
        legacyChapter.setMaxMerchant(-1);

        when(topicMapper.selectCmsTopicById(SOURCE_TOPIC)).thenReturn(original);
        doAnswer(invocation -> {
            ((CmsTopic) invocation.getArgument(0)).setId(NEW_TOPIC);
            return 1;
        }).when(topicMapper).insertCmsTopic(any(CmsTopic.class));
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Arrays.asList(validChapter, legacyChapter));
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE_TOPIC, Collections.<String, Object>singletonMap("copyType", 2)));

        assertTrue(error.getMessage().contains("0到127"));
        org.mockito.ArgumentCaptor<CmsTopicChapter> copied = org.mockito.ArgumentCaptor
                .forClass(CmsTopicChapter.class);
        verify(chapterMapper, times(1)).insertCmsTopicChapter(copied.capture());
        assertEquals(Integer.valueOf(0), copied.getValue().getMaxMerchant());
        assertNotNull(CmsTopicServiceImpl.class
                .getMethod("copyCmsTopic", Long.class, java.util.Map.class)
                .getAnnotation(Transactional.class));
    }

    @Test
    void copy_downgradesRevshareToPerkWithoutFee() {
        CmsTopic original = new CmsTopic();
        original.setId(SOURCE_TOPIC);
        original.setProductType(ProductType.FREE_EXPLORE);
        CmsTopicChapter revshare = new CmsTopicChapter();
        revshare.setId(6L);
        revshare.setTopicId(SOURCE_TOPIC);
        revshare.setRecruitEnabled(0);
        revshare.setMaxMerchant(0);
        revshare.setTermsMode("REVSHARE");
        revshare.setMaxPerHeadFee(new BigDecimal("20.00"));

        when(topicMapper.selectCmsTopicById(SOURCE_TOPIC)).thenReturn(original);
        doAnswer(invocation -> {
            ((CmsTopic) invocation.getArgument(0)).setId(NEW_TOPIC);
            return 1;
        }).when(topicMapper).insertCmsTopic(any(CmsTopic.class));
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(revshare));
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());

        com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE_TOPIC, Collections.<String, Object>singletonMap("copyType", 2));

        org.mockito.ArgumentCaptor<CmsTopicChapter> copied = org.mockito.ArgumentCaptor
                .forClass(CmsTopicChapter.class);
        verify(chapterMapper).insertCmsTopicChapter(copied.capture());
        assertEquals("PERK", copied.getValue().getTermsMode());
        assertNull(copied.getValue().getMaxPerHeadFee());
    }

    @Test
    void copy_rejectsIncompleteRevshareSupplyTerms() {
        CmsTopic original = new CmsTopic();
        original.setId(SOURCE_TOPIC);
        original.setProductType(ProductType.FREE_EXPLORE);
        CmsTopicChapter revshare = new CmsTopicChapter();
        revshare.setId(6L);
        revshare.setTopicId(SOURCE_TOPIC);
        revshare.setRecruitEnabled(0);
        revshare.setMaxMerchant(0);
        revshare.setTermsMode("REVSHARE");

        when(topicMapper.selectCmsTopicById(SOURCE_TOPIC)).thenReturn(original);
        doAnswer(invocation -> {
            ((CmsTopic) invocation.getArgument(0)).setId(NEW_TOPIC);
            return 1;
        }).when(topicMapper).insertCmsTopic(any(CmsTopic.class));
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(revshare));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE_TOPIC, Collections.<String, Object>singletonMap("copyType", 2)));

        assertTrue(error.getMessage().contains("必须配置每人次人头费上限"));
        verify(chapterMapper, never()).insertCmsTopicChapter(any(CmsTopicChapter.class));
    }

    @Test
    void copy_rejectsNonPositivePerkThreshold() {
        CmsTopic original = new CmsTopic();
        original.setId(SOURCE_TOPIC);
        original.setProductType(ProductType.FREE_EXPLORE);
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(6L);
        chapter.setTopicId(SOURCE_TOPIC);
        chapter.setRecruitEnabled(0);
        chapter.setMaxMerchant(0);
        chapter.setTermsMode("PERK");
        chapter.setPerkMinValue(BigDecimal.ZERO);

        when(topicMapper.selectCmsTopicById(SOURCE_TOPIC)).thenReturn(original);
        doAnswer(invocation -> {
            ((CmsTopic) invocation.getArgument(0)).setId(NEW_TOPIC);
            return 1;
        }).when(topicMapper).insertCmsTopic(any(CmsTopic.class));
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(chapter));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE_TOPIC, Collections.<String, Object>singletonMap("copyType", 2)));

        assertTrue(error.getMessage().contains("权益最低价值"));
        verify(chapterMapper, never()).insertCmsTopicChapter(any(CmsTopicChapter.class));
    }
}
