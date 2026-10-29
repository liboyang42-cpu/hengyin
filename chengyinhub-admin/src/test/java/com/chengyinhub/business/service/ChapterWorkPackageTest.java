package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper;
import com.chengyinhub.business.service.impl.CmsTopicChapterServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F05:章节商家工作包(roleNeed + 人力/场地/材料/时段/资质)必须有正常写链,且六项成套、缺一拒写,
 * 开售后与服务容量同样冻结。开售快照 TopicSaleCanonicalV1.validateMerchantWork 缺一项即拒,
 * 这里保证写口不会落下半套事实。方法按名反射调用,修复前是「方法不存在」的运行时红而非编译红。
 */
class ChapterWorkPackageTest
{
    private static final Long TOPIC_ID = 81L;
    private static final Long CHAPTER_ID = 18L;

    private CmsTopicTemplateMetaMapper metaMapper;
    private CmsTopic topic;
    private CmsTopicChapterServiceImpl service;

    @BeforeEach
    void setUp()
    {
        CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        metaMapper = mock(CmsTopicTemplateMetaMapper.class);
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
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
        topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setDelFlag(0);
        topic.setLifecycle(TopicLifecycleState.PRICING.getCode());
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        when(scopeAuthorizer.canViewTopic(TOPIC_ID)).thenReturn(true);
    }

    private static CmsTopicChapterTemplateMeta complete()
    {
        CmsTopicChapterTemplateMeta meta = new CmsTopicChapterTemplateMeta();
        meta.setRoleNeed("店员带玩家完成一次试吃");
        meta.setEffortStaffMinutes(30);
        meta.setEffortVenue("店内 2 张桌");
        meta.setEffortMaterials("提示卡 20 张");
        meta.setEffortAvailableSlots("周六 14:00-18:00");
        meta.setEffortQualifications("食品经营许可证");
        return meta;
    }

    private void configure(CmsTopicChapterTemplateMeta meta)
    {
        ReflectionTestUtils.invokeMethod(service, "configureWorkPackage", CHAPTER_ID, meta);
    }

    @Test
    void completeWorkPackageIsPersistedAgainstTheChapterOwnTopic()
    {
        CmsTopicChapterTemplateMeta input = complete();
        input.setTopicId(999L); // 客户端传的归属不可信
        configure(input);

        ArgumentCaptor<CmsTopicChapterTemplateMeta> written = ArgumentCaptor.forClass(CmsTopicChapterTemplateMeta.class);
        verify(metaMapper).upsertChapterWorkPackage(written.capture());
        assertEquals(CHAPTER_ID, written.getValue().getChapterId());
        assertEquals(TOPIC_ID, written.getValue().getTopicId());
        assertEquals("食品经营许可证", written.getValue().getEffortQualifications());
    }

    /**
     * 负控:六项任缺一(空/空白/非正数)都拒写,且断言是**对应字段那道闸**拒的(按错误文案里的字段名),
     * 不是随便哪个 IllegalStateException —— 删掉任一条校验,该用例必红(W3f 负控已实测)。
     */
    @Test
    void anyMissingFactIsRejectedBeforeWrite()
    {
        java.util.Map<String, Consumer<CmsTopicChapterTemplateMeta>> breakers =
                new java.util.LinkedHashMap<String, Consumer<CmsTopicChapterTemplateMeta>>();
        breakers.put("所需角色", m -> m.setRoleNeed(" "));
        breakers.put("人力(分钟)", m -> m.setEffortStaffMinutes(null));
        breakers.put("人力(分钟)#0", m -> m.setEffortStaffMinutes(0));
        breakers.put("场地", m -> m.setEffortVenue(null));
        breakers.put("材料", m -> m.setEffortMaterials(""));
        breakers.put("可用时段", m -> m.setEffortAvailableSlots(null));
        breakers.put("资质", m -> m.setEffortQualifications(" "));
        for (java.util.Map.Entry<String, Consumer<CmsTopicChapterTemplateMeta>> breaker : breakers.entrySet()) {
            CmsTopicChapterTemplateMeta meta = complete();
            breaker.getValue().accept(meta);
            String field = breaker.getKey().split("#")[0];
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> configure(meta),
                    breaker.getKey());
            assertTrue(error.getMessage().startsWith(field), breaker.getKey() + " 被错误的闸拒绝:" + error.getMessage());
        }
        verify(metaMapper, never()).upsertChapterWorkPackage(any());
    }

    /** 负控:开售后工作包与供给条款、服务容量一样冻结。 */
    @Test
    void sellingTopicIsFrozen()
    {
        topic.setLifecycle(TopicLifecycleState.SELLING.getCode());
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> configure(complete()));
        assertTrue(error.getMessage().contains("冻结"), error.getMessage());
        verify(metaMapper, never()).upsertChapterWorkPackage(any());
    }
}
