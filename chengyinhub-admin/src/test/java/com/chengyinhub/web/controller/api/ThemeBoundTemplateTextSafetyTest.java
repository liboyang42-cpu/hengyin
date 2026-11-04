package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.service.ICapabilityGuard;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICouponRewardAuthorizationService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.ITemplatePublishService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * check-1104 j5 B1:主题里的节点玩法文字(名称/规则/题干/选项/提示/反馈/故事块)
 * 在玩法保存(草稿口)、主题发布/编辑、草稿主题发布三处都必须过同一道机审;命中即拒绝,不落库/不发布。
 * 机审桩照既有测试:只对含「违规」的送检文本返回拦截,经真门禁 ContentSafetyGateImpl 判定。
 */
class ThemeBoundTemplateTextSafetyTest {

    private static final long MEMBER = 9003L;

    @Test
    void templateDraftSaveRejectsRiskyQuestionBeforeWriting() {
        ApiTemplateController controller = spy(new ApiTemplateController());
        doReturn(MEMBER).when(controller).getAppUserId();
        doReturn("lead9003").when(controller).getAppUserName();
        ITemplatePublishService publishService = mock(ITemplatePublishService.class);
        IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        when(security.checkText(anyString(), any())).thenReturn(null);
        when(security.checkText(contains("违规"), any())).thenReturn("内容包含违规信息");
        ReflectionTestUtils.setField(controller, "templatePublishService", publishService);
        ReflectionTestUtils.setField(controller, "couponRewardAuthorizationService",
                mock(ICouponRewardAuthorizationService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", security);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));

        for (String field : new String[]{"title", "rule", "question", "option", "hint", "feedback", "story"}) {
            CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
            request.setTitle("桥边问答");
            if ("title".equals(field)) request.setTitle("桥边问答 违规");
            if ("rule".equals(field)) request.setRuleInstructions("违规规则");
            if ("question".equals(field)) request.setQuestionName("违规题干");
            if ("option".equals(field)) request.setQuestionB("违规选项");
            if ("hint".equals(field)) request.setHint1("违规提示");
            if ("feedback".equals(field)) request.setFeedbackText("违规反馈");
            if ("story".equals(field)) request.setStoryJson("[{\"title\":\"段\",\"text\":\"违规故事\"}]");

            AjaxResult result = controller.draft(request);

            assertEquals(500, result.get(AjaxResult.CODE_TAG), field + ":" + result);
        }
        verify(publishService, never()).saveOrUpdateDraft(any(), anyLong(), anyString());

        CmsTemplatePublishRequest clean = new CmsTemplatePublishRequest();
        clean.setTitle("桥边问答");
        clean.setQuestionName("桥建于哪一年");
        when(publishService.saveOrUpdateDraft(any(), anyLong(), anyString())).thenReturn(55L);
        assertEquals(200, controller.draft(clean).get(AjaxResult.CODE_TAG));
    }

    @Test
    void themeCreateRejectsRiskyTextInBoundNodeGameAndNamesIt() {
        ApiTopicController controller = spy(new ApiTopicController());
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        ICmsMemberTemplateService templates = mock(ICmsMemberTemplateService.class);
        wireTopic(controller, topicService, security, templates);
        CmsMemberTemplate bound = new CmsMemberTemplate();
        bound.setId(55L);
        bound.setTitle("桥边问答");
        bound.setQuestionName("违规题干");
        when(templates.selectCmsMemberTemplateByIds(List.of(55L))).thenReturn(List.of(bound));

        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setImgUrl("media-asset:1");
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setName("正常主题名");
        NodeDTO node = new NodeDTO();
        node.setTemplateId(55L);
        ChapterDTO chapter = new ChapterDTO();
        chapter.setNodes(Collections.singletonList(node));
        dto.setChapters(Collections.singletonList(chapter));

        AjaxResult result = controller.createTopic(dto);

        assertEquals(400, result.get(AjaxResult.CODE_TAG), String.valueOf(result));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("节点玩法「桥边问答」.questionName"),
                String.valueOf(result.get(AjaxResult.MSG_TAG)));
        verify(topicService, never()).createTopic(any(), anyLong(), anyString());
        verify(topicService, never()).createTopicV2WithReview(any(), anyLong(), anyString());
    }

    @Test
    void draftThemePublishRechecksPersistedBoundNodeGameText() {
        ApiTopicController controller = spy(new ApiTopicController());
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        ICmsMemberTemplateService templates = mock(ICmsMemberTemplateService.class);
        wireTopic(controller, topicService, security, templates);
        CmsTopic topic = new CmsTopic();
        topic.setId(990014L);
        topic.setMemberId(MEMBER);
        topic.setPublishMode("simple");
        when(topicService.selectCmsTopicById(990014L)).thenReturn(topic);
        when(topicService.selectTopicIsDraft(990014L)).thenReturn(1);
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        CmsTopicNode persisted = new CmsTopicNode();
        persisted.setTopicId(990014L);
        persisted.setTemplateId(56L);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(List.of(persisted));
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", nodes);
        CmsMemberTemplate bound = new CmsMemberTemplate();
        bound.setId(56L);
        bound.setTitle("桥边问答");
        bound.setHint2("违规提示");
        when(templates.selectCmsMemberTemplateByIds(List.of(56L))).thenReturn(List.of(bound));

        AjaxResult result = controller.publishDraft("990014", null, null, null);

        assertEquals(400, result.get(AjaxResult.CODE_TAG), String.valueOf(result));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("hint2"),
                String.valueOf(result.get(AjaxResult.MSG_TAG)));
        verify(topicService, never()).publishDraft(anyLong(), anyLong());
        verify(topicService, never()).publishDraftWithReview(anyLong(), anyLong());
    }

    private void wireTopic(ApiTopicController controller, ICmsTopicService topicService,
                           IWxContentSecurityService security, ICmsMemberTemplateService templates) {
        doReturn(MEMBER).when(controller).getAppUserId();
        doReturn("lead9003").when(controller).getAppUserName();
        IQuotaService quotaService = mock(IQuotaService.class);
        when(quotaService.checkCreate(anyLong(), anyString())).thenReturn(null);
        when(quotaService.checkNodesPerTheme(anyLong(), anyInt())).thenReturn(null);
        when(security.checkText(anyString(), any())).thenReturn(null);
        when(security.checkText(contains("违规"), any())).thenReturn("内容包含违规信息");
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "quotaService", quotaService);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", security);
        ReflectionTestUtils.setField(controller, "memberTemplateService", templates);
        ReflectionTestUtils.setField(controller, "memberIdentityService",
                mock(com.chengyinhub.business.service.impl.MemberIdentityService.class));
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        TopicContentSafetyTestFixtures.wire(controller, security);
        ReflectionTestUtils.setField(controller, "capabilityGuard", mock(ICapabilityGuard.class));
        ReflectionTestUtils.setField(controller, "merchantAccessService", mock(IMerchantAccessService.class));
    }
}
