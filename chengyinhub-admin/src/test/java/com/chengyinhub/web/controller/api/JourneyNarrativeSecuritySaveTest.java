package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.domain.dto.TopicCreateDTO;
import com.chengyinhub.business.domain.dto.TopicStateKeyRegisterDTO;
import com.chengyinhub.business.domain.dto.TopicUpdateDTO;
import com.chengyinhub.business.service.ICapabilityGuard;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICouponRewardAuthorizationService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.ITemplatePublishService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.impl.TemplatePublishServiceImpl;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R12:新增叙事文本接入服务端内容安全 —— 命中必须拒绝保存,并在提示里带字段名。
 *
 * <p>桩法照现有测试:mock {@link IWxContentSecurityService#checkText},只对含「违规」的送检文本返回拦截。
 */
class JourneyNarrativeSecuritySaveTest {

    private static final long MEMBER = 901L;

    @Test
    void topicCreateRejectsRiskyJourneyStoryAndNamesField() {
        ApiTopicController controller = spy(new ApiTopicController());
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        setUpTopicController(controller, topicService, security);

        TopicCreateDTO dto = new TopicCreateDTO();
        dto.setProductType(ProductType.FREE_EXPLORE);
        dto.setName("正常主题名");
        dto.setJourneyStory("{\"schemaVersion\":1,\"call\":{\"npcId\":1,\"opener\":\"违规开场白\"}}");

        AjaxResult result = controller.createTopic(dto);

        // CORE OWN-C03-21/24：内容安全拒绝在任何写入前发生，业务码改为 400，与写入结果不确定的 500 区分。
        assertEquals(400, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("journeyStory.call.opener"),
                String.valueOf(result.get(AjaxResult.MSG_TAG)));
        verify(topicService, never()).createTopic(any(), anyLong(), anyString());
    }

    @Test
    void topicUpdateRejectsRiskyJourneyStoryAndNamesField() {
        ApiTopicController controller = spy(new ApiTopicController());
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        setUpTopicController(controller, topicService, security);

        TopicUpdateDTO dto = new TopicUpdateDTO();
        dto.setId(7001L);
        dto.setName("正常主题名");
        dto.setJourneyStory("{\"schemaVersion\":1,\"ending\":{\"epilogues\":[{\"npcId\":1,"
                + "\"ally\":\"违规尾声\"}]}}");

        AjaxResult result = controller.updateTopic(dto);

        // CORE OWN-C03-21/24：内容安全拒绝在任何写入前发生，业务码改为 400，与写入结果不确定的 500 区分。
        assertEquals(400, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG))
                        .contains("journeyStory.ending.epilogues[].ally"),
                String.valueOf(result.get(AjaxResult.MSG_TAG)));
        verify(topicService, never()).updateTopicByOwner(any(), anyLong(), anyString());
    }

    @Test
    void merchantNodeTemplateSubmitRejectsRiskyNarrativeAndNamesField() {
        ApiMerchantNodeController controller = spy(new ApiMerchantNodeController());
        doReturn(MEMBER).when(controller).getAppUserId();
        ICmsMemberTemplateService templateRows = mock(ICmsMemberTemplateService.class);
        IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        setUpMerchantNodeController(controller, templateRows, security);

        CmsMemberTemplate body = new CmsMemberTemplate();
        body.setTitle("商家据点玩法");
        body.setValidationMethod(2);
        body.setSensorType("");
        body.setSensorConfig("");
        body.setAdvancedConfigJson("{\"schemaVersion\":1,\"enter\":{\"npcId\":1,\"aside\":\"违规旁白\"}}");

        AjaxResult result = controller.templateSubmit(body);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("blockConfig.enter.aside"),
                String.valueOf(result.get(AjaxResult.MSG_TAG)));
        verify(templateRows, never()).insertCmsMemberTemplate(any());
    }

    @Test
    void merchantNodeTemplateSubmitPrimaryTextCheckCoversRuleInstructions() {
        ApiMerchantNodeController controller = spy(new ApiMerchantNodeController());
        doReturn(MEMBER).when(controller).getAppUserId();
        ICmsMemberTemplateService templateRows = mock(ICmsMemberTemplateService.class);
        IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        setUpMerchantNodeController(controller, templateRows, security);

        CmsMemberTemplate body = new CmsMemberTemplate();
        body.setTitle("商家据点玩法");
        body.setValidationMethod(2);
        body.setSensorType("");
        body.setSensorConfig("");
        body.setRuleInstructions("违规规则说明");

        AjaxResult result = controller.templateSubmit(body);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        // #398 F5:主送检文本(标题/简介/题干…)本身就要带上规则说明,不能只靠后续叙事批次兜底。
        verify(security).checkText(eq(IWxContentSecurityService.joinText("商家据点玩法", null, null, null, null, null,
                "违规规则说明")), any());
        verify(templateRows, never()).insertCmsMemberTemplate(any());
    }

    @Test
    void templatePublishRejectsRiskyNarrativeAndNamesField() {
        ApiTemplateController controller = spy(new ApiTemplateController());
        doReturn(MEMBER).when(controller).getAppUserId();
        doReturn("publisher901").when(controller).getAppUserName();
        ITemplatePublishService publishService = mock(ITemplatePublishService.class);
        ICmsMemberTemplateService memberTemplateService = mock(ICmsMemberTemplateService.class);
        IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        when(security.checkText(anyString(), any())).thenReturn(null);
        when(security.checkText(contains("违规"), any())).thenReturn("内容包含违规信息");
        when(memberTemplateService.selectNumByTitle(any())).thenReturn(0);
        ReflectionTestUtils.setField(controller, "templatePublishService", publishService);
        ReflectionTestUtils.setField(controller, "memberTemplateService", memberTemplateService);
        ReflectionTestUtils.setField(controller, "couponRewardAuthorizationService",
                mock(ICouponRewardAuthorizationService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", security);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));

        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setTitle("到店互动");
        request.setAdvancedConfigJson("{\"schemaVersion\":1,\"check\":{\"enabled\":true,"
                + "\"checkId\":\"ask_arong\",\"tier\":\"easy\",\"skill\":\"察言\","
                + "\"successText\":\"违规检定文案\"}}");

        AjaxResult result = controller.publish(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("blockConfig.check.successText"),
                String.valueOf(result.get(AjaxResult.MSG_TAG)));
        verify(publishService, never()).publishTemplateWithTransaction(any(), anyLong(), anyString());
    }

    @Test
    void stateKeyRegisterRejectsRiskyLabelAndNamesField() {
        ApiTopicStateKeyController controller = spy(new ApiTopicStateKeyController());
        doReturn(MEMBER).when(controller).getAppUserId();
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        ThemeStateKeyService keyService = mock(ThemeStateKeyService.class);
        IWxContentSecurityService security = mock(IWxContentSecurityService.class);
        when(security.checkText(anyString(), any())).thenReturn(null);
        when(security.checkText(contains("违规"), any())).thenReturn("内容包含违规信息");
        CmsTopic topic = new CmsTopic();
        topic.setId(7001L);
        topic.setMemberId(MEMBER);
        // R11-B 编辑窗口闸在内容安全闸之前:草稿态才放行,否则先被生命周期拦掉,测不到 label 送检
        topic.setLifecycle(TopicLifecycleState.DRAFT.getCode());
        when(topicService.selectCmsTopicById(7001L)).thenReturn(topic);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "themeStateKeyService", keyService);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", security);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));

        ThemeStateKey key = new ThemeStateKey();
        key.setStateKey("clue.ledger_page");
        key.setKeyType(ThemeStateKey.TYPE_FLAG);
        key.setScope(ThemeStateKey.SCOPE_RUN);
        key.setLabel("违规标签");
        TopicStateKeyRegisterDTO dto = new TopicStateKeyRegisterDTO();
        dto.setTopicId(7001L);
        dto.setKeys(Collections.singletonList(key));

        AjaxResult result = controller.registerStateKeys(dto);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("themeStateKey.label"),
                String.valueOf(result.get(AjaxResult.MSG_TAG)));
        verify(keyService, never()).register(anyLong(), any());
    }

    private void setUpTopicController(ApiTopicController controller, ICmsTopicService topicService,
                                      IWxContentSecurityService security) {
        doReturn(MEMBER).when(controller).getAppUserId();
        doReturn("publisher901").when(controller).getAppUserName();
        IQuotaService quotaService = mock(IQuotaService.class);
        when(quotaService.checkCreate(anyLong(), anyString())).thenReturn(null);
        when(quotaService.checkNodesPerTheme(anyLong(), anyInt())).thenReturn(null);
        when(security.checkText(anyString(), any())).thenReturn(null);
        when(security.checkText(contains("违规"), any())).thenReturn("内容包含违规信息");
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "quotaService", quotaService);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", security);
        // RUN-52 发布者实名:本用例测的是内容安全拒写与字段名透传,实名闸在这里放行
        // (mock 的 checkRegistered 返回 null = 已登记)。
        ReflectionTestUtils.setField(controller, "memberIdentityService",
                mock(com.chengyinhub.business.service.impl.MemberIdentityService.class));
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        TopicContentSafetyTestFixtures.wire(controller, security);
        ReflectionTestUtils.setField(controller, "capabilityGuard", mock(ICapabilityGuard.class));
        ReflectionTestUtils.setField(controller, "merchantAccessService", mock(IMerchantAccessService.class));
    }

    private void setUpMerchantNodeController(ApiMerchantNodeController controller,
                                             ICmsMemberTemplateService templateRows,
                                             IWxContentSecurityService security) {
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(17L);
        merchant.setMemberId(MEMBER);
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        when(merchants.selectMmsMerchantById(17L)).thenReturn(merchant);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(MEMBER, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(MEMBER, 17L, MEMBER, MerchantRoleCode.MERCHANT_OWNER));
        IUmsMemberService members = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember();
        member.setId(MEMBER);
        member.setRole("merchant");
        when(members.selectUmsMemberById(MEMBER)).thenReturn(member);
        IRoleService roles = mock(IRoleService.class);
        UserPermission permission = new UserPermission();
        permission.setCanOpenAsNode(true);
        when(roles.getPermission(member)).thenReturn(permission);
        when(security.checkText(anyString(), any())).thenReturn(null);
        when(security.checkText(contains("违规"), any())).thenReturn("内容包含违规信息");
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchants);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "umsMemberService", members);
        ReflectionTestUtils.setField(controller, "roleService", roles);
        ReflectionTestUtils.setField(controller, "templateService", templateRows);
        ReflectionTestUtils.setField(controller, "templatePublishService", new TemplatePublishServiceImpl());
        ReflectionTestUtils.setField(controller, "wxSec", security);
        ReflectionTestUtils.setField(controller, "couponRewardAuthorizationService",
                mock(ICouponRewardAuthorizationService.class));
    }
}
