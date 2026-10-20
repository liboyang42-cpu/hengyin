package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.business.service.support.ActivityReviewPolicy;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gap 12·03 P0-5:后台 POST/PUT /business/activity 与小程序发布同一条审核路径 ——
 * 模式由 ActivityReviewPolicy 定、门禁 decide() 裁决,status/publish_status 不认请求体;
 * 先审建 BIZ_ACTIVITY 审核单,明确违规按后台错误口径拒,编辑不能把活动变成公开。
 *
 * <p>控制器 + 真实 CmsActivityServiceImpl,只 mock 到 mapper / 审核单服务 / 门禁这一层,
 * 断言落库的 status/publish_status 与审核单,而不是断言调用了哪个 service 方法。
 *
 * <p>⚠️ 负控:控制器 add 改回 insertCmsActivity(body) ⇒ 前三条红;
 * edit 改回 updateCmsActivity(editable) ⇒ 后三条红。
 */
class CmsActivityAdminReviewGateTest {

    private static final long NEW_ID = 777L;
    private static final long EXISTING_ID = 500L;
    private static final long OWNER = 42L;
    private static final String HIGH_RISK_CATEGORY = "9";

    private CmsActivityController controller;
    private CmsActivityMapper mapper;
    private IAuditTaskService auditTaskService;
    private ISysConfigService config;

    @BeforeEach
    void setUp() {
        mapper = mock(CmsActivityMapper.class);
        auditTaskService = mock(IAuditTaskService.class);
        CmsActivityServiceImpl service = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(service, "cmsActivityMapper", mapper);
        ReflectionTestUtils.setField(service, "auditTaskService", auditTaskService);
        ReflectionTestUtils.setField(service, "omsTicketMapper", mock(OmsTicketMapper.class));
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", mock(CmsRegistrationMapper.class));

        config = mock(ISysConfigService.class);
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_HIGH_RISK_CATEGORIES)).thenReturn(HIGH_RISK_CATEGORY);
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_PRE_REVIEW_ALL)).thenReturn("false");
        ActivityReviewPolicy policy = new ActivityReviewPolicy();
        ReflectionTestUtils.setField(policy, "configService", config);

        controller = new CmsActivityController();
        ReflectionTestUtils.setField(controller, "cmsActivityService", service);
        setIfPresent(controller, "activityReviewPolicy", policy);
        setIfPresent(controller, "contentSafetyGate", gate());

        doAnswer(inv -> {
            ((CmsActivity) inv.getArgument(0)).setId(NEW_ID);
            return 1;
        }).when(mapper).insertCmsActivity(any(CmsActivity.class));
        when(mapper.updateCmsActivity(any(CmsActivity.class))).thenReturn(1);

        SysUser user = new SysUser();
        user.setUserId(1L);
        user.setUserName("ops");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(user, new HashSet<>()), null, Collections.emptyList()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 与 ContentSafetyGateImpl 同口径的替身:违规文本 BLOCK;先审 + 报名 HOLD;先发后审 PASS。 */
    private static IContentSafetyGate gate() {
        IContentSafetyGate gate = mock(IContentSafetyGate.class);
        when(gate.decide(any(PublishSafetyRequest.class))).thenAnswer(inv -> {
            PublishSafetyRequest req = inv.getArgument(0);
            if (req.getText() != null && req.getText().contains("违禁")) {
                return SafetyDecision.block("TEXT_RISK");
            }
            if (PublishSafetyRequest.ReviewMode.PRE_REVIEW.name().equals(req.getReviewMode())) {
                return SafetyDecision.hold("STRUCTURED_REVIEW", null);
            }
            return SafetyDecision.pass();
        });
        return gate;
    }

    /** 修复前控制器没有这两个字段 —— 让用例在旧代码上以断言失败(而不是装配异常)的方式变红。 */
    private static void setIfPresent(Object target, String field, Object value) {
        if (ReflectionUtils.findField(target.getClass(), field) != null) {
            ReflectionTestUtils.setField(target, field, value);
        }
    }

    private static CmsActivity body(String name, String categoryIds) {
        CmsActivity a = new CmsActivity();
        a.setMemberId(OWNER);
        a.setName(name);
        a.setDescription("周末城市漫步");
        a.setCategoryIds(categoryIds);
        a.setStatus(1L);
        a.setPublishStatus(1L);
        return a;
    }

    private CmsActivity insertedRow() {
        ArgumentCaptor<CmsActivity> cap = ArgumentCaptor.forClass(CmsActivity.class);
        verify(mapper).insertCmsActivity(cap.capture());
        return cap.getValue();
    }

    @Test
    void create_bodyStatusPublic_isStoredPerPolicyNotBody() {
        // 总闸 pre_review_all=true:请求体自报 status=1/publish_status=1,落库仍按策略待审。
        when(config.selectConfigByKey(ActivityReviewPolicy.CONFIG_PRE_REVIEW_ALL)).thenReturn("true");

        AjaxResult res = controller.add(body("城市漫步", "1"));

        assertEquals(200, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        CmsActivity row = insertedRow();
        assertEquals(0L, row.getStatus());
        assertEquals(0L, row.getPublishStatus());
        verify(auditTaskService).submit(eq(AuditTask.BIZ_ACTIVITY), eq(NEW_ID), eq(OWNER), eq("user_publish"), anyString());
    }

    @Test
    void create_highRiskCategory_isStoredPendingWithAuditTask() {
        AjaxResult res = controller.add(body("溯溪", "1," + HIGH_RISK_CATEGORY));

        assertEquals(200, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        CmsActivity row = insertedRow();
        assertEquals(0L, row.getStatus());
        assertEquals(0L, row.getPublishStatus());
        verify(auditTaskService).submit(eq(AuditTask.BIZ_ACTIVITY), eq(NEW_ID), eq(OWNER), eq("user_publish"), anyString());
    }

    @Test
    void create_riskyText_isRefusedAndNotStored() {
        AjaxResult res = controller.add(body("违禁内容活动", "1"));

        assertEquals(500, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        assertEquals("活动内容包含违规信息，请修改后重试", res.get("msg"));
        verify(mapper, never()).insertCmsActivity(any(CmsActivity.class));
    }

    private CmsActivity existing(long status) {
        CmsActivity a = new CmsActivity();
        a.setId(EXISTING_ID);
        a.setMemberId(OWNER);
        a.setName("原活动名");
        a.setDescription("原简介");
        a.setCategoryIds("1");
        a.setStatus(status);
        a.setPublishStatus(status == 1L ? 1L : 0L);
        a.setDelFlag(0);
        return a;
    }

    private CmsActivity editPatch(String name) {
        CmsActivity p = new CmsActivity();
        p.setId(EXISTING_ID);
        p.setName(name);
        p.setStatus(1L);
        p.setPublishStatus(1L);
        return p;
    }

    /** 编辑只写内容字段:status/publish_status 不进主表更新;任何一次 updateCmsActivity 都不能写出 1。 */
    private void assertNeverWrotePublic() {
        ArgumentCaptor<CmsActivity> cap = ArgumentCaptor.forClass(CmsActivity.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).updateCmsActivity(cap.capture());
        for (CmsActivity written : cap.getAllValues()) {
            assertNotEquals(Long.valueOf(1L), written.getStatus(), "编辑写出了 status=1");
            assertNotEquals(Long.valueOf(1L), written.getPublishStatus(), "编辑写出了 publish_status=1");
        }
    }

    @Test
    void edit_pendingActivity_cannotBeFlippedPublic_andAuditTaskIsRefreshed() {
        when(mapper.selectCmsActivityById(EXISTING_ID)).thenReturn(existing(0L));

        AjaxResult res = controller.edit(editPatch("改过的活动名"));

        assertEquals(200, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        assertNeverWrotePublic();
        verify(auditTaskService).submitOrRefreshActivity(eq(EXISTING_ID), eq(OWNER), eq("user_republish"), anyString());
    }

    @Test
    void edit_rejectedActivity_isResubmittedPendingNotPublic() {
        when(mapper.selectCmsActivityById(EXISTING_ID)).thenReturn(existing(2L));

        AjaxResult res = controller.edit(editPatch("改过的活动名"));

        assertEquals(200, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        assertNeverWrotePublic();
        ArgumentCaptor<CmsActivity> cap = ArgumentCaptor.forClass(CmsActivity.class);
        verify(mapper, org.mockito.Mockito.times(2)).updateCmsActivity(cap.capture());
        assertEquals(0L, cap.getAllValues().get(1).getStatus());
        assertEquals(0L, cap.getAllValues().get(1).getPublishStatus());
        verify(auditTaskService).submitOrRefreshActivity(eq(EXISTING_ID), eq(OWNER), eq("user_republish"), anyString());
    }

    @Test
    void edit_highRiskPublicActivity_contentChange_goesBackToPending() {
        CmsActivity current = existing(1L);
        current.setCategoryIds(HIGH_RISK_CATEGORY);
        when(mapper.selectCmsActivityById(EXISTING_ID)).thenReturn(current);

        AjaxResult res = controller.edit(editPatch("改过的活动名"));

        assertEquals(200, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        ArgumentCaptor<CmsActivity> cap = ArgumentCaptor.forClass(CmsActivity.class);
        verify(mapper, org.mockito.Mockito.times(2)).updateCmsActivity(cap.capture());
        assertNull(cap.getAllValues().get(0).getStatus(), "内容更新不能带审核态");
        assertEquals(0L, cap.getAllValues().get(1).getStatus());
        assertEquals(0L, cap.getAllValues().get(1).getPublishStatus());
        verify(auditTaskService).submitOrRefreshActivity(eq(EXISTING_ID), eq(OWNER), eq("user_republish"), anyString());
    }

    @Test
    void edit_riskyText_isRefusedAndNotStored() {
        when(mapper.selectCmsActivityById(EXISTING_ID)).thenReturn(existing(1L));

        AjaxResult res = controller.edit(editPatch("违禁内容活动"));

        assertEquals(500, ((Integer) res.get("code")).intValue(), "实际:" + res.get("msg"));
        verify(mapper, never()).updateCmsActivity(any(CmsActivity.class));
        verify(auditTaskService, never()).submitOrRefreshActivity(any(), any(), anyString(), anyString());
    }
}
