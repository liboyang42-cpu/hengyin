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
 * O5b-1:编辑分级开关。开:先审模式下轻改(错别字/措辞)不退回待审;先发后审下实质改动走抽检单重新进审核。
 * 关:与 AUDIT-G1-2 原口径完全一致。留置/打标的编辑不参与分级(仍按原路径)。
 */
class ActivityUpdateLightEditTest {
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
        exist.setName("周末城市夜跑");
        exist.setDescription("周六晚上八点在滨江公园集合，沿江跑五公里。");
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

    private static CmsActivityPublishRequest typoFix() {
        CmsActivityPublishRequest r = new CmsActivityPublishRequest();
        r.setId(ACTIVITY);
        r.setName("周末城市夜跑！");
        r.setDescription("周六晚上八点在滨江公园集合,沿江跑五公里。");
        return r;
    }

    private static CmsActivityPublishRequest rewrite() {
        CmsActivityPublishRequest r = new CmsActivityPublishRequest();
        r.setId(ACTIVITY);
        r.setName("周末城市夜跑");
        r.setDescription("改为线上打卡活动，扫码进群领取福利。");
        return r;
    }

    private void preReviewAll() {
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_PRE_REVIEW_ALL)).thenReturn("true");
    }

    @Test
    void switchOffKeepsOriginalPreReviewLoopForTypoFix() {
        preReviewAll();

        controller.update(typoFix());

        verify(cmsActivityService).updateActivityByOwner(any(), eq(MEMBER_ID), anyString(), eq(true));
    }

    @Test
    void switchOnTypoFixDoesNotReenterReview() {
        ReflectionTestUtils.setField(controller, "lightEditClassificationEnabled", true);
        preReviewAll();

        AjaxResult result = controller.update(typoFix());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(cmsActivityService).updateActivityByOwner(any(), eq(MEMBER_ID), anyString(), eq(false));
        verify(auditTaskService, never()).submitOrRefreshActivity(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void switchOnSubstantiveEditStillReentersPreReview() {
        ReflectionTestUtils.setField(controller, "lightEditClassificationEnabled", true);
        preReviewAll();

        controller.update(rewrite());

        verify(cmsActivityService).updateActivityByOwner(any(), eq(MEMBER_ID), anyString(), eq(true));
    }

    @Test
    void switchOnSubstantivePostReviewEditOpensSpotCheckAndStaysLive() {
        ReflectionTestUtils.setField(controller, "lightEditClassificationEnabled", true);

        controller.update(rewrite());

        verify(cmsActivityService).updateActivityByOwner(any(), eq(MEMBER_ID), anyString(), eq(false));
        verify(auditTaskService).submitOrRefreshActivity(eq(ACTIVITY), eq(MEMBER_ID), eq("post_review_flag"), anyString());
    }

    @Test
    void switchOnTypoFixUnderPostReviewOpensNothing() {
        ReflectionTestUtils.setField(controller, "lightEditClassificationEnabled", true);

        controller.update(typoFix());

        verify(cmsActivityService).updateActivityByOwner(any(), eq(MEMBER_ID), anyString(), eq(false));
        verify(auditTaskService, never()).submitOrRefreshActivity(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void switchOffSubstantivePostReviewEditOpensNothingAsBefore() {
        controller.update(rewrite());

        verify(auditTaskService, never()).submitOrRefreshActivity(anyLong(), anyLong(), anyString(), anyString());
    }

    /** 门禁打标(需复核)的编辑不参与分级:即便是轻改也照旧进抽检单,不被当成轻改放过。 */
    @Test
    void flaggedEditIsNeverDowngradedToLight() {
        ReflectionTestUtils.setField(controller, "lightEditClassificationEnabled", true);
        when(wxSec.checkText(anyString(), anyString())).thenReturn(IWxContentSecurityService.REVIEW_REQUIRED);

        controller.update(typoFix());

        verify(auditTaskService).submitOrRefreshActivity(eq(ACTIVITY), eq(MEMBER_ID), eq("post_review_flag"), anyString());
    }

    /** 先审模式下文本机审存疑(HOLD/TEXT_REVIEW)的编辑不参与分级:轻改也照旧退回待审。 */
    @Test
    void textHeldEditIsNeverDowngradedToLight() {
        ReflectionTestUtils.setField(controller, "lightEditClassificationEnabled", true);
        preReviewAll();
        when(wxSec.checkText(anyString(), anyString())).thenReturn(IWxContentSecurityService.REVIEW_REQUIRED);

        controller.update(typoFix());

        verify(cmsActivityService).updateActivityByOwner(any(), eq(MEMBER_ID), anyString(), eq(true));
    }
}
