package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterApplicationMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.service.impl.CmsTopicChapterServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Collections;

/** V1 招商章不能被通用编辑降级回旧的主题级中标授权。 */
class CmsTopicChapterRecruitmentGuardTest
{
    private static final Long CHAPTER_ID = 33L;

    @Test
    void cannotDisableRecruitmentWhenV1RelationsExist()
    {
        CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        MerchantChapterApplicationMapper applicationMapper = mock(MerchantChapterApplicationMapper.class);
        MerchantChapterOfferMapper offerMapper = mock(MerchantChapterOfferMapper.class);
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        CmsTopicChapterServiceImpl service = new CmsTopicChapterServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        com.chengyinhub.business.service.support.TopicContentBudgetTestWiring.chapter(service);
        ReflectionTestUtils.setField(service, "merchantChapterApplicationMapper", applicationMapper);
        ReflectionTestUtils.setField(service, "merchantChapterOfferMapper", offerMapper);
        ReflectionTestUtils.setField(service, "topicOperationsScopeAuthorizer", scopeAuthorizer);

        CmsTopicChapter current = new CmsTopicChapter();
        current.setId(CHAPTER_ID);
        current.setTopicId(8L);
        current.setRecruitEnabled(1);
        current.setCategoryId(18);
        current.setMaxMerchant(2);
        current.setTermsMode("PERK");
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER_ID)).thenReturn(current);
        when(chapterMapper.lockChaptersForCapacity(Collections.singletonList(CHAPTER_ID)))
                .thenReturn(Collections.singletonList(CHAPTER_ID));
        CmsTopic topic = new CmsTopic();
        topic.setId(8L);
        topic.setLifecycle(TopicLifecycleState.DRAFT.getCode());
        topic.setDelFlag(0);
        when(topicMapper.selectCmsTopicByIdForUpdate(8L)).thenReturn(topic);
        when(scopeAuthorizer.canViewTopic(8L)).thenReturn(true);
        when(applicationMapper.countCurrentByChapter(CHAPTER_ID)).thenReturn(1);
        when(offerMapper.countAllByChapter(CHAPTER_ID)).thenReturn(0);

        CmsTopicChapter patch = new CmsTopicChapter();
        patch.setId(CHAPTER_ID);
        patch.setRecruitEnabled(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.updateCmsTopicChapter(patch));

        assertTrue(error.getMessage().contains("申请") || error.getMessage().contains("供给"));
        verify(chapterMapper, never()).updateCmsTopicChapter(patch);
    }
}
