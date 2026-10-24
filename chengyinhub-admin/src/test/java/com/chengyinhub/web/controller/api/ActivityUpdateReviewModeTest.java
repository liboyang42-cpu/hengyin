package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.impl.ContentSafetyGateImpl;
import com.chengyinhub.business.service.support.ActivityReviewPolicy;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
 * AUDIT-G1-2:/api/activity/update 与 /publish 同一道统一门禁 + 同一套分级。
 * 真门禁(ContentSafetyGateImpl)+ 真策略(ActivityReviewPolicy),只 mock 检测供应商 / 配置 / service。
 */
class ActivityUpdateReviewModeTest {
    private static final Long MEMBER_ID = 101L;
    private static final Long ACTIVITY = 300L;

    private ApiActivityController controller;
    private ICmsActivityService cmsActivityService;
    private IWxContentSecurityService wxSec;
    private IAuditTaskService auditTaskService;
    private ISysConfigService config;
    private CmsActivity exist;

    @BeforeEach
    void setUp() {
        controller = new ApiActivityController() {
            @Override public Long getAppUserId() { return MEMBER_ID; }
            @Override public String getAppUserName() { return "阿测"; }
        };
        IUmsMemberService umsMemberService = mock(IUmsMemberService.class);
        IRoleService roleService = mock(IRoleService.class);
        cmsActivityService = mock(ICmsActivityService.class);
        wxSec = mock(IWxContentSecurityService.class);
        auditTaskService = mock(IAuditTaskService.class);
        config = mock(ISysConfigService.class);
        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setWxOpenId("openid-x");
        when(umsMemberService.selectUmsMemberById(MEMBER_ID)).thenReturn(member);
        when(roleService.resolveRole(member)).thenReturn(IRoleService.ROLE_CLUB);
        when(wxSec.isEnabled()).thenReturn(true);
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_HIGH_RISK_CATEGORIES)).thenReturn("13");
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_PRE_REVIEW_ALL)).thenReturn("false");
        exist = new CmsActivity();
        exist.setId(ACTIVITY);
        exist.setMemberId(MEMBER_ID);
        exist.setDelFlag(0);
        exist.setCategoryIds("3");
        when(cmsActivityService.selectCmsActivityById(ACTIVITY)).thenReturn(exist);
        when(cmsActivityService.updateActivityByOwner(any(), anyLong(), anyString(), anyBoolean())).thenReturn(1);

        ContentSafetyGateImpl gate = new ContentSafetyGateImpl();
        ReflectionTestUtils.setField(gate, "wxContentSecurityService", wxSec);
        ReflectionTestUtils.setField(gate, "auditTaskService", auditTaskService);
        ActivityReviewPolicy policy = new ActivityReviewPolicy();
        ReflectionTestUtils.setField(policy, "configService", config);

        ReflectionTestUtils.setField(controller, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(controller, "roleService", roleService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", cmsActivityService);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", wxSec);
        ReflectionTestUtils.setField(controller, "contentSafetyGate", gate);
        ReflectionTestUtils.setField(controller, "activityReviewPolicy", policy);
        ReflectionTestUtils.setField(controller, "auditTaskService", auditTaskService);
    }

    private static CmsActivityPublishRequest edit() {
        CmsActivityPublishRequest r = new CmsActivityPublishRequest();
        r.setId(ACTIVITY);
        r.setName("改后的名字");
        r.setDescription("改后的简介");
        return r;
    }

    @Test
    void violatingEditIsRefusedByTheGate() {
        when(wxSec.checkText(anyString(), anyString())).thenReturn("内容含违规词");

        AjaxResult result = controller.update(edit());

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("活动内容包含违规信息，请修改后重试", result.get(AjaxResult.MSG_TAG));
        verify(cmsActivityService, never()).updateActivityByOwner(any(), anyLong(), anyString(), anyBoolean());
    }

    @Test
    void postReviewEditStaysLive() {
        AjaxResult result = controller.update(edit());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(wxSec).checkText("改后的名字\n改后的简介", "openid-x");
        verify(cmsActivityService).updateActivityByOwner(any(), eq(MEMBER_ID), anyString(), eq(false));
    }

    @Test
    void preReviewValveSendsEditBackToReview() {
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_PRE_REVIEW_ALL)).thenReturn("true");

        AjaxResult result = controller.update(edit());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(cmsActivityService).updateActivityByOwner(any(), eq(MEMBER_ID), anyString(), eq(true));
        verify(auditTaskService, never()).submitSafetyHold(anyString(), any(), any(), anyString(), any());
    }

    /** 编辑页不回带类目时按库里的类目判高风险,不能因为没传就降成先发后审。 */
    @Test
    void highRiskCategoryInDbStillPreReviewsWhenRequestOmitsIt() {
        exist.setCategoryIds("13");

        controller.update(edit());

        verify(cmsActivityService).updateActivityByOwner(any(), eq(MEMBER_ID), anyString(), eq(true));
    }

    @Test
    void reviewRequiredEditLeavesSpotCheckViaRefresh() {
        when(wxSec.checkText(anyString(), anyString())).thenReturn(IWxContentSecurityService.REVIEW_REQUIRED);

        AjaxResult result = controller.update(edit());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(cmsActivityService).updateActivityByOwner(any(), eq(MEMBER_ID), anyString(), eq(false));
        verify(auditTaskService).submitOrRefreshActivity(eq(ACTIVITY), eq(MEMBER_ID), eq("post_review_flag"), anyString());
        verify(auditTaskService, never()).submit(anyString(), anyLong(), anyLong(), anyString(), any());
    }
}
