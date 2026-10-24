package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.domain.dto.TopicUpdateDTO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsCollaboratorsMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

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

/**
 * R8-STORY:主题级剧情配置的编辑保存链路 —— 校验后落库、章节重建后 chapterId 重映射、
 * 老编辑端不带上送不抹原值、非法当场拒。
 */
class TopicJourneyStorySaveTest {

    private static final Long ME = 100L;
    private static final Long TOPIC = 7L;
    private static final Long LIVE_CHAPTER = 11L;
    private static final Long NEW_CHAPTER = 900L;

    private static final String STORY = "{\"schemaVersion\":1,"
            + "\"call\":{\"npcId\":7,\"opener\":\"开场\"},"
            + "\"stages\":[{\"chapterId\":11,\"question\":\"问?\",\"end\":{\"changed\":["
            + "{\"when\":null,\"text\":\"无论如何\"}],\"open\":\"本章完\"}}]}";

    private CmsTopicMapper topicMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicNodeMapper nodeMapper;
    private OmsTicketMapper ticketMapper;
    private CmsCollaboratorsMapper collaboratorsMapper;
    private CmsActivityMapper activityMapper;
    private CmsRegistrationMapper registrationMapper;
    private CmsTopicServiceImpl svc;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        collaboratorsMapper = mock(CmsCollaboratorsMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        registrationMapper = mock(CmsRegistrationMapper.class);

        svc = new CmsTopicServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(svc, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(svc, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(svc, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(svc, "cmsTopicNodeMapper", nodeMapper);
        ReflectionTestUtils.setField(svc, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(svc, "cmsCollaboratorsMapper", collaboratorsMapper);
        ReflectionTestUtils.setField(svc, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(svc, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(svc, "commercialTopicPolicy",
                new com.chengyinhub.business.service.support.CommercialTopicPolicy(
                        ticketMapper, chapterMapper,
                        mock(com.chengyinhub.business.mapper.MerchantChapterOfferMapper.class), topicMapper));
        ReflectionTestUtils.setField(svc, "couponRewardAuthorizationService",
                mock(ICouponRewardAuthorizationService.class));
        ReflectionTestUtils.setField(svc, "clubService", mock(IClubService.class));
        ReflectionTestUtils.setField(svc, "xpBudgetService", mock(IXpBudgetService.class));
        ReflectionTestUtils.setField(svc, "auditTaskService", mock(IAuditTaskService.class));
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(svc, "omsTicketService", mock(IOmsTicketService.class));

        when(registrationMapper.selectCmsRegistrationList(any())).thenReturn(Collections.<CmsRegistration>emptyList());
        when(activityMapper.selectCmsActivityList(any())).thenReturn(Collections.<CmsActivity>emptyList());
        when(topicMapper.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);

        // 现存章节:编辑器的 chapterId 就是它
        CmsTopicChapter live = new CmsTopicChapter();
        live.setId(LIVE_CHAPTER);
        live.setTopicId(TOPIC);
        live.setSortId(1L);
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(new ArrayList<CmsTopicChapter>(Collections.singletonList(live)));
        // 重建后的新章节 id
        doAnswer(invocation -> {
            ((CmsTopicChapter) invocation.getArgument(0)).setId(NEW_CHAPTER);
            return 1;
        }).when(chapterMapper).insertCmsTopicChapter(any(CmsTopicChapter.class));
        // CORE OWN-C03-13：重建节点插入后须生成有效身份，否则 fail-closed；夹具按真实插入回填 id。
        java.util.concurrent.atomic.AtomicLong nodeIds = new java.util.concurrent.atomic.AtomicLong(9000L);
        doAnswer(invocation -> {
            ((com.chengyinhub.business.domain.CmsTopicNode) invocation.getArgument(0)).setId(nodeIds.incrementAndGet());
            return 1;
        }).when(nodeMapper).insertCmsTopicNode(any(com.chengyinhub.business.domain.CmsTopicNode.class));
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
    }

    private CmsTopic topic(int status, Integer isDraft) {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setMemberId(ME);
        t.setStatus(status);
        t.setLifecycle(status == 1 ? 1 : 0);
        t.setDelFlag(0);
        t.setStartDate(new Date(System.currentTimeMillis() + 3 * 24L * 3600_000L));
        t.setName("旧名字");
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(t);
        when(topicMapper.selectTopicIsDraft(TOPIC)).thenReturn(isDraft);
        return t;
    }

    private TopicUpdateDTO dtoWithStory(String story) {
        TopicUpdateDTO dto = new TopicUpdateDTO();
        dto.setId(TOPIC);
        dto.setName("新名字");
        dto.setProductType(ProductType.ORIENTEERING);
        dto.setStartDate(new Date(System.currentTimeMillis() + 3 * 24L * 3600_000L));
        dto.setEndDate(new Date(System.currentTimeMillis() + 5 * 24L * 3600_000L));
        NodeDTO node = new NodeDTO();
        node.setName("第一站");
        node.setAddress("人民广场");
        node.setNodeTime(30);
        ChapterDTO chapter = new ChapterDTO();
        chapter.setId(LIVE_CHAPTER);
        chapter.setName("第一章");
        chapter.setNodes(new ArrayList<NodeDTO>(Arrays.asList(node)));
        dto.setChapters(new ArrayList<ChapterDTO>(Arrays.asList(chapter)));
        dto.setTickets(new ArrayList<ActivityTicketRequest>());
        dto.setJourneyStory(story);
        return dto;
    }

    private List<CmsTopic> capturedTopicUpdates() {
        ArgumentCaptor<CmsTopic> captor = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper, times(2)).updateCmsTopic(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void draftEdit_persistsStoryAndRemapsChapterIdAfterRebuild() {
        topic(0, 1);

        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).updateTopicByOwner(dtoWithStory(STORY), ME, "me");

        List<CmsTopic> updates = capturedTopicUpdates();
        String stored = updates.get(0).getJourneyStoryJson();
        assertNotNull(stored, "配置了剧情就必须落库");
        assertTrue(stored.contains("\"chapterId\":11"), stored);
        String remapped = updates.get(1).getJourneyStoryJson();
        assertTrue(remapped.contains("\"chapterId\":" + NEW_CHAPTER),
                "章节重建后 chapterId 必须重映射到新 id,否则玩家读到的是上一批章节:" + remapped);
        assertTrue(remapped.contains("\"npcId\":7"), "重映射不得丢掉其余字段:" + remapped);
    }

    @Test
    void draftEdit_withoutStory_leavesColumnUntouched() {
        topic(0, 1);

        com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).updateTopicByOwner(dtoWithStory(null), ME, "me");

        ArgumentCaptor<CmsTopic> captor = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).updateCmsTopic(captor.capture());
        assertNull(captor.getValue().getJourneyStoryJson(),
                "老编辑端不带 journeyStory 必须写 null(动态 SQL 靠它跳过),不能落一份空配置把原值盖掉");
    }

    @Test
    void draftEdit_illegalStory_rejectedBeforeAnyWrite() {
        topic(0, 1);
        String illegal = "{\"schemaVersion\":1,\"ending\":{\"endings\":["
                + "{\"code\":\"a\",\"title\":\"A\",\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.x\"}]}]}}";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).updateTopicByOwner(dtoWithStory(illegal), ME, "me"));

        assertTrue(error.getMessage().contains("fallback"), error.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any(CmsTopic.class));
    }

    @Test
    void draftEdit_storyReferencingForeignChapter_isRejected() {
        topic(0, 1);
        String foreign = "{\"schemaVersion\":1,\"stages\":[{\"chapterId\":999,\"end\":{}}]}";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.wire(svc).updateTopicByOwner(dtoWithStory(foreign), ME, "me"));

        assertTrue(error.getMessage().contains("章节不属于本主题"), error.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any(CmsTopic.class));
    }
}
