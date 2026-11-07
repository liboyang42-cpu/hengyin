package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.ContentSafetyGateImpl;
import com.chengyinhub.business.service.support.ActivityReviewPolicy;
import com.chengyinhub.business.service.support.UgcSubmissionSafety;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real moderation gate; mapper/service mocks prove rejected content never reaches the save boundary. */
class ActivityBoundGameContentSafetyTest {
    private ApiActivityController controller;
    private ICmsActivityService activities;
    private ICmsMemberTemplateService templates;
    private IWxContentSecurityService security;
    private CmsActivity stored;
    private CmsMemberTemplate game;

    @BeforeEach
    void setUp() {
        controller = new ApiActivityController() {
            @Override public Long getAppUserId() { return 101L; }
            @Override public String getAppUserName() { return "主理人"; }
        };
        activities = mock(ICmsActivityService.class);
        templates = mock(ICmsMemberTemplateService.class);
        security = mock(IWxContentSecurityService.class);
        when(security.isEnabled()).thenReturn(true);
        when(security.checkText(contains("违禁"), any())).thenReturn("违规内容");
        UmsMember member = new UmsMember();
        member.setId(101L); member.setWxOpenId("author-openid");
        IUmsMemberService members = mock(IUmsMemberService.class);
        when(members.selectUmsMemberById(101L)).thenReturn(member);
        IRoleService roles = mock(IRoleService.class);
        when(roles.resolveRole(member)).thenReturn(IRoleService.ROLE_CLUB);
        IQuotaService quota = mock(IQuotaService.class);
        when(quota.runWithCreateGuard(anyLong(), anyString(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(2)).get());
        when(activities.publishActivity(any(), anyLong(), anyString(), anyBoolean())).thenReturn(9L);
        stored = new CmsActivity();
        stored.setId(9L); stored.setMemberId(101L); stored.setTemplateId(55L); stored.setPublishStatus(0L);
        when(activities.selectCmsActivityById(9L)).thenReturn(stored);
        game = new CmsMemberTemplate();
        game.setId(55L); game.setTitle("桥边问答"); game.setQuestionName("桥建于哪一年");
        when(templates.selectCmsMemberTemplateById(55L)).thenReturn(game);
        ActivityReviewPolicy policy = new ActivityReviewPolicy();
        ISysConfigService config = mock(ISysConfigService.class);
        when(config.selectConfigByKey(anyString())).thenReturn("");
        ReflectionTestUtils.setField(policy, "configService", config);
        ContentSafetyGateImpl gate = new ContentSafetyGateImpl();
        ReflectionTestUtils.setField(gate, "wxContentSecurityService", security);
        ReflectionTestUtils.setField(gate, "auditTaskService", mock(IAuditTaskService.class));
        ReflectionTestUtils.setField(controller, "cmsActivityService", activities);
        ReflectionTestUtils.setField(controller, "memberTemplateService", templates);
        ReflectionTestUtils.setField(controller, "umsMemberService", members);
        ReflectionTestUtils.setField(controller, "roleService", roles);
        ReflectionTestUtils.setField(controller, "quotaService", quota);
        ReflectionTestUtils.setField(controller, "contentSafetyGate", gate);
        ReflectionTestUtils.setField(controller, "activityReviewPolicy", policy);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", security);
        ReflectionTestUtils.setField(controller, "submissionSafety", mock(UgcSubmissionSafety.class));
    }

    private CmsActivityPublishRequest request(boolean update) {
        CmsActivityPublishRequest request = new CmsActivityPublishRequest();
        request.setName("周末活动"); request.setTemplateId(55L);
        if (update) request.setId(9L);
        return request;
    }

    @Test
    void createAndUpdateRejectBoundQuestionAndNestedUnknownFieldsBeforeAnySave() {
        for (boolean update : new boolean[]{false, true}) {
            for (String field : new String[]{"question", "story", "advanced", "preference", "sensor"}) {
                game.setQuestionName("正常题目"); game.setStoryJson(null); game.setAdvancedConfigJson(null);
                game.setPreferenceJson(null); game.setSensorConfig(null);
                if (field.equals("question")) game.setQuestionName("违禁题干");
                if (field.equals("story")) game.setStoryJson("[{\"futureField\":\"违禁故事\"}]");
                if (field.equals("advanced")) game.setAdvancedConfigJson("{\"futureGame\":{\"anything\":[\"违禁配置\"]}}");
                if (field.equals("preference")) game.setPreferenceJson("{\"unknown\":\"违禁偏好\"}");
                if (field.equals("sensor")) game.setSensorConfig("{\"unknown\":\"违禁配置\"}");
                AjaxResult result = update ? controller.update(request(true)) : controller.publish(request(false));
                assertNotEquals(200, result.get(AjaxResult.CODE_TAG), field);
            }
        }
        verify(activities, never()).publishActivity(any(), anyLong(), anyString(), anyBoolean());
        verify(activities, never()).updateActivityByOwner(any(), anyLong(), anyString(), anyBoolean());
    }

    @Test
    void cleanPreviouslyReviewedGameStillPassesTheSameGateOnCreateAndUpdate() {
        game.setCheckStatus(1);
        game.setAdvancedConfigJson("{\"kind\":\"QA\",\"nested\":{\"futureField\":\"正常新字段\",\"count\":3,\"enabled\":true}}");
        assertEquals(200, controller.publish(request(false)).get(AjaxResult.CODE_TAG));
        assertEquals(200, controller.update(request(true)).get(AjaxResult.CODE_TAG));
        verify(security, times(2)).checkText(contains("正常新字段"), eq("author-openid"));
        verify(activities).publishActivity(any(), eq(101L), anyString(), eq(false));
        verify(activities).updateActivityByOwner(any(), eq(101L), anyString(), eq(false));
    }

    @Test
    void busyOrThrowingScannerHoldsBothSavesInsteadOfPublishingUncheckedGame() {
        when(security.checkText(anyString(), any())).thenReturn(IWxContentSecurityService.MSG_BUSY);
        assertEquals(200, controller.publish(request(false)).get(AjaxResult.CODE_TAG));
        assertEquals(200, controller.update(request(true)).get(AjaxResult.CODE_TAG));
        verify(activities).publishActivity(any(), eq(101L), anyString(), eq(true));
        verify(activities).updateActivityByOwner(any(), eq(101L), anyString(), eq(true));
        clearInvocations(activities);
        when(security.checkText(anyString(), any())).thenThrow(new IllegalStateException("scanner unavailable"));
        assertEquals(200, controller.publish(request(false)).get(AjaxResult.CODE_TAG));
        assertEquals(200, controller.update(request(true)).get(AjaxResult.CODE_TAG));
        verify(activities).publishActivity(any(), eq(101L), anyString(), eq(true));
        verify(activities).updateActivityByOwner(any(), eq(101L), anyString(), eq(true));
    }

    @Test
    void omittedBindingOnEditRechecksStoredGameAndOwnershipStillPrecedesScanning() {
        game.setQuestionName("违禁旧题");
        CmsActivityPublishRequest request = request(true);
        request.setTemplateId(null);
        assertNotEquals(200, controller.update(request).get(AjaxResult.CODE_TAG));
        verify(activities, never()).updateActivityByOwner(any(), anyLong(), anyString(), anyBoolean());
        clearInvocations(templates, security);
        stored.setMemberId(202L);
        assertNotEquals(200, controller.update(request).get(AjaxResult.CODE_TAG));
        assertNotEquals(200, controller.activityUpdatePublishStatus("9", null).get(AjaxResult.CODE_TAG));
        verifyNoInteractions(templates, security);
    }

    @Test
    void missingOrUnreadableGameCannotSaveOrPublish() {
        when(templates.selectCmsMemberTemplateById(55L)).thenReturn(null);
        assertNotEquals(200, controller.publish(request(false)).get(AjaxResult.CODE_TAG));
        assertNotEquals(200, controller.update(request(true)).get(AjaxResult.CODE_TAG));
        when(templates.selectCmsMemberTemplateById(55L)).thenThrow(new IllegalStateException("read failed"));
        assertNotEquals(200, controller.publish(request(false)).get(AjaxResult.CODE_TAG));
        assertNotEquals(200, controller.update(request(true)).get(AjaxResult.CODE_TAG));
        verify(activities, never()).publishActivity(any(), anyLong(), anyString(), anyBoolean());
        verify(activities, never()).updateActivityByOwner(any(), anyLong(), anyString(), anyBoolean());
    }

    @Test
    void relistingRechecksPersistedGameAndCannotExposeRejectedOrHeldText() {
        game.setQuestionName("违禁旧题");
        assertNotEquals(200, controller.activityUpdatePublishStatus("9", null).get(AjaxResult.CODE_TAG));
        game.setQuestionName("正常题目");
        when(security.checkText(anyString(), any())).thenReturn(IWxContentSecurityService.MSG_BUSY);
        assertNotEquals(200, controller.activityUpdatePublishStatus("9", null).get(AjaxResult.CODE_TAG));
        assertEquals(0L, stored.getPublishStatus());
        verify(activities, never()).updateCmsActivity(any());
        when(security.checkText(anyString(), any())).thenReturn(null);
        when(activities.updateCmsActivity(any())).thenReturn(1);
        assertEquals(200, controller.activityUpdatePublishStatus("9", null).get(AjaxResult.CODE_TAG));
        assertEquals(1L, stored.getPublishStatus());
    }
}
