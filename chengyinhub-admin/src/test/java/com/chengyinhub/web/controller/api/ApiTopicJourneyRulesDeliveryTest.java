package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.vo.TopicInfoVO;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IViewCollaboratorsService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * R2-B10 主题数值规则的两条读侧出口:编辑器回显(edit-detail)与玩家侧只读下发(info-to-user)。
 *
 * <p>两者都只新增 journeyRules 一个字段,不改已有字段;未配置 = null(不下发),
 * 作者关掉检定 = 下发 checkEnabled=false 的归一化 JSON。
 */
class ApiTopicJourneyRulesDeliveryTest {

    private static final long VIEWER = 901L;
    private static final long TOPIC_ID = 7001L;
    private static final String RULES = "{\"schemaVersion\":1,\"checkEnabled\":true,\"hp\":{\"enabled\":true,"
            + "\"max\":10,\"init\":10},\"luck\":{\"enabled\":true,\"max\":3,\"init\":3},"
            + "\"tiers\":{\"easy\":{\"dc\":10,\"damage\":1},\"medium\":{\"dc\":13,\"damage\":2},"
            + "\"hard\":{\"dc\":16,\"damage\":3}},\"recovery\":{\"stationEndIfHurt\":2,"
            + "\"stageRestHpFloor\":6,\"stageRestLuckFloor\":1,\"exhaustRestHp\":5},"
            + "\"fumbleExtraDamage\":1,\"modCap\":6}";

    private CmsTopic topic(String journeyRulesJson) {
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
        topic.setJourneyRulesJson(journeyRulesJson);
        return topic;
    }

    /** 编辑器回填:顶层 journeyRules 与保存入参同名回显。 */
    @Test
    void editDetail_echoesJourneyRules() {
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(RULES));
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
        assertEquals(RULES, data.get("journeyRules"), "回显的必须与保存的同一份归一化 JSON");
        assertEquals("测试主题", ((CmsTopic) data.get("topic")).getName(), "已有字段不能被挤掉");
    }

    /** 玩家主题详情:配置了的原样下发。 */
    @Test
    void playerTopicInfo_deliversJourneyRules() {
        AjaxResult result = playerTopicInfo(topic(RULES));

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        assertEquals(RULES, ((TopicInfoVO) result.get("data")).getJourneyRules());
    }

    /** 未配置 = null(不下发),老主题响应形状其余不变。 */
    @Test
    void playerTopicInfo_withoutJourneyRules_deliversNull() {
        AjaxResult result = playerTopicInfo(topic(null));

        assertEquals(200, result.get(AjaxResult.CODE_TAG), result.toString());
        assertNull(((TopicInfoVO) result.get("data")).getJourneyRules());
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
