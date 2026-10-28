package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.TopicEditionReopenRequest;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.ProductRoleMapper;
import com.chengyinhub.business.mapper.TopicEditionReopenMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.impl.TopicEditionReopenServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.RoleEntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R8-STORY:剧情配置随模板复制 / 官方复制 / 重开一期沿用;章节重建后 chapterId 按 old→new 重映射,
 * 否则副本 / 新期剧情指向来源期的章节 id(发布期才拦得住)。
 */
class TopicJourneyStoryCarryTest {

    private static final Long SOURCE_TOPIC = 41L;
    private static final Long NEW_TOPIC = 42L;
    private static final Long MEMBER = 20088L;
    private static final Long PLATFORM_MEMBER = 100000L;
    private static final Long SOURCE_CHAPTER = 11L;
    private static final Long NEW_CHAPTER = 911L;

    private static final String STORY = "{\"schemaVersion\":1,"
            + "\"stages\":[{\"chapterId\":11,\"end\":{\"changed\":[{\"when\":null,\"text\":\"无论如何\"}]}}]}";

    private final List<CmsTopicChapter> copiedChapters = new ArrayList<CmsTopicChapter>();

    private CmsTopicMapper topicMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicNodeMapper nodeMapper;
    private IProductRoleService productRoleService;
    private CmsTopicServiceImpl service;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        productRoleService = mock(IProductRoleService.class);

        service = new CmsTopicServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);
        ReflectionTestUtils.setField(service, "platformMemberId", PLATFORM_MEMBER);

        CmsTopic source = new CmsTopic();
        source.setId(SOURCE_TOPIC);
        source.setMemberId(PLATFORM_MEMBER);
        source.setName("源主题");
        source.setProductType(ProductType.FREE_EXPLORE);
        source.setJourneyStoryJson(STORY);
        when(topicMapper.selectCmsTopicById(SOURCE_TOPIC)).thenReturn(source);
        when(topicMapper.selectCmsTopicById(NEW_TOPIC)).thenReturn(null);
        doAnswer(invocation -> {
            ((CmsTopic) invocation.getArgument(0)).setId(NEW_TOPIC);
            return 1;
        }).when(topicMapper).insertCmsTopic(any(CmsTopic.class));
        when(topicMapper.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);

        CmsTopicChapter sourceChapter = new CmsTopicChapter();
        sourceChapter.setId(SOURCE_CHAPTER);
        sourceChapter.setTopicId(SOURCE_TOPIC);
        sourceChapter.setSortId(1L);
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class))).thenAnswer(invocation -> {
            CmsTopicChapter probe = invocation.getArgument(0, CmsTopicChapter.class);
            return SOURCE_TOPIC.equals(probe.getTopicId())
                    ? new ArrayList<CmsTopicChapter>(Collections.singletonList(sourceChapter))
                    : new ArrayList<CmsTopicChapter>(copiedChapters);
        });
        doAnswer(invocation -> {
            CmsTopicChapter chapter = invocation.getArgument(0, CmsTopicChapter.class);
            chapter.setId(NEW_CHAPTER);
            if (NEW_TOPIC.equals(chapter.getTopicId())) {
                copiedChapters.add(chapter);
            }
            return 1;
        }).when(chapterMapper).insertCmsTopicChapter(any(CmsTopicChapter.class));
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.<CmsTopicNode>emptyList());
    }

    @Test
    void templateCopyCarriesStoryAndRemapsChapterIds() {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("copyType", 2);
        params.put("memberId", MEMBER);

        com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE_TOPIC, params);

        ArgumentCaptor<CmsTopic> insert = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).insertCmsTopic(insert.capture());
        assertEquals(STORY, insert.getValue().getJourneyStoryJson(), "剧情必须随模板复制");

        ArgumentCaptor<CmsTopic> update = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).updateCmsTopic(update.capture());
        assertTrue(update.getValue().getJourneyStoryJson().contains("\"chapterId\":" + NEW_CHAPTER),
                "副本章节是新 id,剧情 chapterId 必须重映射:" + update.getValue().getJourneyStoryJson());
    }

    @Test
    void copyWithoutStoryLeavesTargetNullAndSkipsRemap() {
        topicMapper.selectCmsTopicById(SOURCE_TOPIC).setJourneyStoryJson(null);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("copyType", 2);
        params.put("memberId", MEMBER);

        com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service).copyCmsTopic(SOURCE_TOPIC, params);

        ArgumentCaptor<CmsTopic> insert = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).insertCmsTopic(insert.capture());
        assertNull(insert.getValue().getJourneyStoryJson(), "源主题未配置时副本不得凭空生成剧情");
    }

    // ---------- 重开一期 ----------

    @Test
    void reopenCarriesStoryAndRemapsChapterIds() {
        TopicEditionReopenMapper reopenMapper = mock(TopicEditionReopenMapper.class);
        ProductRoleMapper productRoleMapper = mock(ProductRoleMapper.class);
        CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
        ClubMapper clubMapper = mock(ClubMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        TopicEditionReopenServiceImpl reopenService = new TopicEditionReopenServiceImpl(
                reopenMapper, productRoleMapper, templateMapper, clubMapper, memberMapper,
                mock(com.chengyinhub.business.mapper.NpcProfileMapper.class));
        ReflectionTestUtils.setField(reopenService, "platformMemberId", PLATFORM_MEMBER);

        CmsTopic source = new CmsTopic();
        source.setId(SOURCE_TOPIC);
        source.setDelFlag(0);
        source.setLifecycle(TopicLifecycleState.SELLING.getCode());
        source.setProductType(ProductType.ORIENTEERING);
        source.setJourneyStoryJson(STORY);
        when(reopenMapper.selectSourceTopicForUpdate(SOURCE_TOPIC)).thenReturn(source);

        ProductRole content = new ProductRole();
        content.setSlot("CONTENT");
        content.setEntityType(RoleEntityType.PLATFORM);
        content.setStatus(1);
        when(reopenMapper.selectSourceContentForUpdate(SOURCE_TOPIC)).thenReturn(content);

        CmsTopicChapter sourceChapter = new CmsTopicChapter();
        sourceChapter.setId(SOURCE_CHAPTER);
        sourceChapter.setTopicId(SOURCE_TOPIC);
        sourceChapter.setSortId(1L);
        when(reopenMapper.selectSourceChapters(SOURCE_TOPIC))
                .thenReturn(Collections.singletonList(sourceChapter));
        when(reopenMapper.selectSourceNodes(SOURCE_TOPIC)).thenReturn(Collections.<CmsTopicNode>emptyList());

        doAnswer(invocation -> {
            ((TopicEditionReopenRequest) invocation.getArgument(0)).setId(801L);
            return 1;
        }).when(reopenMapper).insertReopenRequest(any(TopicEditionReopenRequest.class));
        doAnswer(invocation -> {
            ((CmsTopic) invocation.getArgument(0)).setId(NEW_TOPIC);
            return 1;
        }).when(reopenMapper).insertReopenedTopic(any(CmsTopic.class));
        doAnswer(invocation -> {
            ((CmsTopicChapter) invocation.getArgument(0)).setId(NEW_CHAPTER);
            return 1;
        }).when(reopenMapper).insertReopenedChapter(any(CmsTopicChapter.class));
        when(productRoleMapper.insertProductRole(any(ProductRole.class))).thenReturn(1);
        when(reopenMapper.updateReopenedJourneyStory(eq(NEW_TOPIC), any(String.class))).thenReturn(1);
        when(reopenMapper.completeReopenRequest(anyLong(), anyLong())).thenReturn(1);

        assertEquals(NEW_TOPIC, reopenService.reopenNextEdition(SOURCE_TOPIC, "edition-r8"));

        ArgumentCaptor<CmsTopic> inserted = ArgumentCaptor.forClass(CmsTopic.class);
        verify(reopenMapper).insertReopenedTopic(inserted.capture());
        assertEquals(STORY, inserted.getValue().getJourneyStoryJson(), "剧情是内容的一部分,重开一期必须沿用");

        ArgumentCaptor<String> remapped = ArgumentCaptor.forClass(String.class);
        verify(reopenMapper).updateReopenedJourneyStory(eq(NEW_TOPIC), remapped.capture());
        assertTrue(remapped.getValue().contains("\"chapterId\":" + NEW_CHAPTER),
                "新期章节是新 id,剧情 chapterId 必须重映射:" + remapped.getValue());
    }
}
