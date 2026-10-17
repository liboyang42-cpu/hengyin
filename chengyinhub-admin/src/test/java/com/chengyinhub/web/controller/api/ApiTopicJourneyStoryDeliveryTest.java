package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IViewCollaboratorsService;
import com.alibaba.fastjson2.JSON;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * R8-STORY 主题级剧情配置的两条读侧出口:编辑器回显(edit-detail)与玩家侧只读下发(info-to-user)。
 *
 * <p>R22-SEC S1:玩家侧不再下发 journeyStory —— 它含结局条件(when)、optionalClues 与 relationKey,
 * 整段都是剧透;编辑回显仍按显式字段回给作者。
 */
class ApiTopicJourneyStoryDeliveryTest {

    private static final long VIEWER = 901L;
    private static final long TOPIC_ID = 7001L;
    private static final String STORY = "{\"schemaVersion\":1,"
            + "\"call\":{\"npcId\":7,\"opener\":\"开场\"},"
            + "\"ending\":{\"optionalClues\":[\"clue.qiao_receipt\"]},"
            + "\"stages\":[{\"chapterId\":11,\"end\":{\"changed\":[{\"when\":"
            + "{\"op\":\"EQ\",\"var\":\"counter.stage1_result\",\"value\":1},\"text\":\"无论如何\"}]}}]}";

    private CmsTopic topic(String journeyStoryJson) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(VIEWER);
        topic.setDelFlag(0);
        topic.setStatus(0);
        topic.setUserStatus(0);
        topic.setProductType(1);
        topic.setPublishMode("pro");
        topic.setLifecycle(0);
        topic.setName("测试主题");
        topic.setJourneyStoryJson(journeyStoryJson);
        return topic;
    }

    @Test
    void editDetail_echoesJourneyStory() {
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(STORY.replace("schemaVersion\":1", "schemaVersion\":1,\"openingChapterId\":11")));
        when(topicService.selectTopicIsDraft(TOPIC_ID)).thenReturn(1);
        ApiTopicController controller = spy(new ApiTopicController());
        ReflectionTestUtils.setField(controller, "merchantBusinessStatusService",
                mock(com.chengyinhub.business.service.IMerchantBusinessStatusService.class));
        doReturn(VIEWER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ICmsTopicChapterService chapterService = mock(ICmsTopicChapterService.class);
        when(chapterService.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "cmsTopicChapterService", chapterService);
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", mock(ICmsTopicNodeService.class));
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "omsTicketService", ticketService);
        IViewCollaboratorsService collaboratorService = mock(IViewCollaboratorsService.class);
        when(collaboratorService.selectViewCollaboratorsList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "viewCollaboratorsService", collaboratorService);

        AjaxResult result = controller.topicEditDetail(String.valueOf(TOPIC_ID), null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(STORY.replace("schemaVersion\":1", "schemaVersion\":1,\"openingChapterId\":11"), data.get("journeyStory"), "回显的必须是保存时那份");
        assertEquals(11L, data.get("openingChapterId"));
        assertEquals("测试主题", ((CmsTopic) data.get("topic")).getName(), "已有字段不能被挤掉");
    }

    /**
     * R22-SEC S1:玩家侧不得下发 journeyStory(含 when / optionalClues / relationKey 等剧透)。
     *
     * <p>负控:恢复 topicInfo 里的 setJourneyStory 下发,本用例立刻红(序列化里出现 journeyStory
     * 与 clue.qiao_receipt / counter.stage1_result)。
     */
    @Test
    void playerTopicInfo_deliversNoJourneyStory() {
        AjaxResult result = playerTopicInfo(topic(STORY));

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        String json = JSON.toJSONString(result);
        assertFalse(json.contains("journeyStory"), json);
        assertFalse(json.contains("clue.qiao_receipt"), json);
        assertFalse(json.contains("counter.stage1_result"), json);
    }

    @Test
    void playerTopicInfo_withoutJourneyStory_alsoDeliversNone() {
        AjaxResult result = playerTopicInfo(topic(null));

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        assertFalse(JSON.toJSONString(result).contains("journeyStory"));
    }

    @Test
    void playerTopicInfoPreservesRefreshedServiceLocationCount() {
        // Controller projection only; real database refresh is covered by PublicTopicStationCountMySqlIT.
        CmsTopic topic = topic(null);
        topic.setLocationCount(4L);
        AjaxResult result = playerTopicInfo(topic);
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(4L, ((com.chengyinhub.business.domain.vo.TopicInfoVO) result.get("data")).getLocationCount());
    }

    /** topicInfo 的最小依赖集:空章节/无票/无报名,足够走到组装 TopicInfoVO 并返回。 */
    private AjaxResult playerTopicInfo(CmsTopic topic) {
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectCmsTopicDetailById(TOPIC_ID, VIEWER)).thenReturn(topic);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        ApiTopicController controller = spy(new ApiTopicController());
        ReflectionTestUtils.setField(controller, "merchantBusinessStatusService",
                mock(com.chengyinhub.business.service.IMerchantBusinessStatusService.class));
        doReturn(VIEWER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ICmsTopicChapterService chapterService = mock(ICmsTopicChapterService.class);
        when(chapterService.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "cmsTopicChapterService", chapterService);
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", mock(ICmsTopicNodeService.class));
        IOmsTicketService ticketService = mock(IOmsTicketService.class);
        when(ticketService.selectOmsTicketList(any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(controller, "omsTicketService", ticketService);
        ReflectionTestUtils.setField(controller, "viewCollaboratorsService", mock(IViewCollaboratorsService.class));
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService",
                mock(com.chengyinhub.business.service.ICmsRegistrationMerchantService.class));
        ReflectionTestUtils.setField(controller, "umsCommentService",
                mock(com.chengyinhub.business.service.IUmsCommentService.class));
        ReflectionTestUtils.setField(controller, "commentService",
                mock(com.chengyinhub.business.service.IViewCommentService.class));
        ReflectionTestUtils.setField(controller, "viewRegistrationService",
                mock(com.chengyinhub.business.service.IViewRegistrationService.class));
        ReflectionTestUtils.setField(controller, "cmsActivityService",
                mock(com.chengyinhub.business.service.ICmsActivityService.class));
        ReflectionTestUtils.setField(controller, "cmsRegistrationMapper",
                mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(controller, "playerNodeProgressService",
                mock(com.chengyinhub.business.service.IPlayerNodeProgressService.class));

        return controller.topicInfo(String.valueOf(TOPIC_ID));
    }
}
