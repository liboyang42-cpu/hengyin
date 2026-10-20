package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.impl.ContentSafetyGateImpl;
import com.chengyinhub.business.service.support.ActivityReviewPolicy;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-20 分级审核(推翻 M0-6「活动先审后发」)发布链路:
 * 真门禁(ContentSafetyGateImpl)+ 真策略(ActivityReviewPolicy),只 mock 检测供应商 / 配置 / 落库。
 */
class ActivityPublishReviewModeTest {
    private static final Long MEMBER_ID = 101L;
    private static final Long MERCHANT_OWNER_ID = 77L;

    private ApiActivityController controller;
    private IRoleService roleService;
    private IQuotaService quotaService;
    private ICmsActivityService cmsActivityService;
    private IWxContentSecurityService wxSec;
    private IMerchantAccessService merchantAccessService;
    private IAuditTaskService auditTaskService;
    private ISysConfigService config;
    private UmsMember member;

    @BeforeEach
    void setUp() {
        controller = new ApiActivityController() {
            @Override public Long getAppUserId() { return MEMBER_ID; }
            @Override public String getAppUserName() { return "阿测"; }
        };
        IUmsMemberService umsMemberService = mock(IUmsMemberService.class);
        roleService = mock(IRoleService.class);
        quotaService = mock(IQuotaService.class);
        cmsActivityService = mock(ICmsActivityService.class);
        wxSec = mock(IWxContentSecurityService.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        auditTaskService = mock(IAuditTaskService.class);
        config = mock(ISysConfigService.class);
        member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setWxOpenId("openid-x");
        when(umsMemberService.selectUmsMemberById(MEMBER_ID)).thenReturn(member);
        when(roleService.resolveRole(member)).thenReturn(IRoleService.ROLE_CLUB);
        when(wxSec.isEnabled()).thenReturn(true);
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_HIGH_RISK_CATEGORIES)).thenReturn("");
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_PRE_REVIEW_ALL)).thenReturn("false");
        when(quotaService.runWithCreateGuard(anyLong(), anyString(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(2)).get());
        when(cmsActivityService.publishActivity(any(), anyLong(), anyString(), anyBoolean())).thenReturn(9L);

        ContentSafetyGateImpl gate = new ContentSafetyGateImpl();
        ReflectionTestUtils.setField(gate, "wxContentSecurityService", wxSec);
        ReflectionTestUtils.setField(gate, "auditTaskService", auditTaskService);
        ActivityReviewPolicy policy = new ActivityReviewPolicy();
        ReflectionTestUtils.setField(policy, "configService", config);

        ReflectionTestUtils.setField(controller, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(controller, "roleService", roleService);
        ReflectionTestUtils.setField(controller, "quotaService", quotaService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", cmsActivityService);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", wxSec);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
        ReflectionTestUtils.setField(controller, "contentSafetyGate", gate);
        ReflectionTestUtils.setField(controller, "activityReviewPolicy", policy);
        ReflectionTestUtils.setField(controller, "auditTaskService", auditTaskService);
    }

    private static CmsActivityPublishRequest activity(String categoryIds) {
        CmsActivityPublishRequest r = new CmsActivityPublishRequest();
        r.setName("周末徒步");
        r.setDescription("一起走走");
        r.setCategoryIds(categoryIds);
        return r;
    }

    @Test
    void freeActivityPublishesImmediatelyWithoutAuditTask() {
        AjaxResult result = controller.publish(activity("3"));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(wxSec).checkText("周末徒步\n一起走走", "openid-x");
        verify(cmsActivityService).publishActivity(any(), eq(MEMBER_ID), anyString(), eq(false));
        verify(auditTaskService, never()).submit(anyString(), any(), any(), anyString(), any());
        verify(auditTaskService, never()).submitSafetyHold(anyString(), any(), any(), anyString(), any());
    }

    /** 俱乐部收费场(既有口径)同样先发后审:价格/报名不再强制人工。 */
    @Test
    void paidClubActivityAlsoPublishesImmediately() {
        CmsActivityPublishRequest r = activity("3");
        ActivityTicketRequest ticket = new ActivityTicketRequest();
        ticket.setName("标准票");
        ticket.setPrice(new BigDecimal("69"));
        r.setTickets(Collections.singletonList(ticket));

        AjaxResult result = controller.publish(r);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(cmsActivityService).publishActivity(any(), eq(MEMBER_ID), anyString(), eq(false));
    }

    @Test
    void highRiskCategoryStaysPending() {
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_HIGH_RISK_CATEGORIES)).thenReturn("7,12");

        AjaxResult result = controller.publish(activity("3,12"));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(cmsActivityService).publishActivity(any(), eq(MEMBER_ID), anyString(), eq(true));
        // 门禁前置裁决不建孤儿单;BIZ_ACTIVITY 审核单由 publishActivity(preReview=true) 落库后建
        verify(auditTaskService, never()).submitSafetyHold(anyString(), any(), any(), anyString(), any());
    }

    @Test
    void preReviewAllValveMakesEverythingPending() {
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_PRE_REVIEW_ALL)).thenReturn("true");

        AjaxResult result = controller.publish(activity("3"));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(cmsActivityService).publishActivity(any(), eq(MEMBER_ID), anyString(), eq(true));
    }

    @Test
    void violatingTextIsRefusedInPostReview() {
        when(wxSec.checkText(anyString(), anyString())).thenReturn("内容含违规词");

        AjaxResult result = controller.publish(activity("3"));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("活动内容包含违规信息，请修改后重试", result.get(AjaxResult.MSG_TAG));
        verify(cmsActivityService, never()).publishActivity(any(), anyLong(), anyString(), anyBoolean());
    }

    @Test
    void violatingTextIsRefusedInPreReview() {
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_PRE_REVIEW_ALL)).thenReturn("true");
        when(wxSec.checkText(anyString(), anyString())).thenReturn("内容含违规词");

        AjaxResult result = controller.publish(activity("3"));

        assertEquals("活动内容包含违规信息，请修改后重试", result.get(AjaxResult.MSG_TAG));
        verify(cmsActivityService, never()).publishActivity(any(), anyLong(), anyString(), anyBoolean());
    }

    /** 机审给不出结论:先发后审照常上架,但在审核台留一张非阻断的抽检单。 */
    @Test
    void reviewRequiredTextPublishesButLeavesSpotCheckTask() {
        when(wxSec.checkText(anyString(), anyString())).thenReturn(IWxContentSecurityService.REVIEW_REQUIRED);

        AjaxResult result = controller.publish(activity("3"));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(cmsActivityService).publishActivity(any(), eq(MEMBER_ID), anyString(), eq(false));
        verify(auditTaskService).submit(eq(AuditTask.BIZ_ACTIVITY), eq(9L), eq(MEMBER_ID),
                eq("post_review_flag"), anyString());
    }

    @Test
    void unapprovedMerchantIsRefused() {
        // 入驻未通过(mms_merchant.status!=1)时 require 抛 403,这是「谁能发」的真源
        when(merchantAccessService.require(MEMBER_ID, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));
        CmsActivityPublishRequest r = activity("3");
        r.setScope("MERCHANT");

        AjaxResult result = controller.publish(r);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(cmsActivityService, never()).publishActivity(any(), anyLong(), anyString(), anyBoolean());
    }

    /** 显式兜底:商家上下文解析不出商家行(未审核主体),也不放行。 */
    @Test
    void merchantContextWithoutApprovedMerchantRowIsRefused() {
        when(merchantAccessService.require(MEMBER_ID, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(MEMBER_ID, null, MERCHANT_OWNER_ID, "MERCHANT_OWNER"));
        CmsActivityPublishRequest r = activity("3");
        r.setScope("MERCHANT");

        AjaxResult result = controller.publish(r);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("商家未通过入驻审核，暂不能发布活动", result.get(AjaxResult.MSG_TAG));
        verify(cmsActivityService, never()).publishActivity(any(), anyLong(), anyString(), anyBoolean());
    }

    @Test
    void approvedMerchantFreeActivityPublishesImmediately() {
        when(merchantAccessService.require(MEMBER_ID, MerchantPermission.PROJECT_MANAGE))
                .thenReturn(new MerchantAccessContext(MEMBER_ID, 5L, MERCHANT_OWNER_ID, "MERCHANT_MANAGER"));
        CmsActivityPublishRequest r = activity("3");
        r.setScope("MERCHANT");

        AjaxResult result = controller.publish(r);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(cmsActivityService).publishActivity(any(), eq(MERCHANT_OWNER_ID), anyString(), eq(false));
    }
}
