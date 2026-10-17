package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICouponRewardAuthorizationService;
import com.chengyinhub.business.service.ITemplatePublishService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 商家项目里的节点玩法资产必须归商家 Owner，空 scope 仍归员工个人。 */
class ApiTemplateMerchantOperatorScopeTest {
    private static final long PRINCIPAL = 901L;
    private static final long OWNER = 900L;

    private ApiTemplateController controller;
    private ITemplatePublishService publishService;
    private ICmsMemberTemplateService memberTemplateService;
    private ICouponRewardAuthorizationService couponAuthorizationService;

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        controller = spy(new ApiTemplateController());
        publishService = mock(ITemplatePublishService.class);
        memberTemplateService = mock(ICmsMemberTemplateService.class);
        couponAuthorizationService = mock(ICouponRewardAuthorizationService.class);
        IMerchantAccessService accessService = mock(IMerchantAccessService.class);
        MerchantAccessContext access = new MerchantAccessContext(
                PRINCIPAL, 77L, OWNER, MerchantRoleCode.MERCHANT_MANAGER);
        doReturn(PRINCIPAL).when(controller).getAppUserId();
        doReturn("operator901").when(controller).getAppUserName();
        when(accessService.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE)).thenReturn(access);
        ReflectionTestUtils.setField(controller, "templatePublishService", publishService);
        ReflectionTestUtils.setField(controller, "memberTemplateService", memberTemplateService);
        ReflectionTestUtils.setField(controller, "couponRewardAuthorizationService", couponAuthorizationService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);
        // X02:publish 现在会对正文送 msgSecCheck、对封面/勋章图送 media_check_async。
        // 本用例断言的是 scope 归属,内容安全 mock 返回 null(放行)即可。
        ReflectionTestUtils.setField(controller, "wxContentSecurityService",
                mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
        ReflectionTestUtils.setField(controller, "umsMemberService",
                mock(com.chengyinhub.business.service.IUmsMemberService.class));
        when(memberTemplateService.selectNumByTitle(any())).thenReturn(0);
        when(memberTemplateService.selectMemberTemplateList(any())).thenReturn(Collections.emptyList());
    }

    private CmsTemplatePublishRequest request(String scope) {
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        request.setTitle("到店互动");
        request.setScope(scope);
        return request;
    }

    @Test
    void merchantPublishAndDraftUseOwnerScope() {
        when(publishService.publishTemplateWithTransaction(any(), eq(OWNER), eq("operator901"))).thenReturn(71L);
        when(publishService.saveOrUpdateDraft(any(), eq(OWNER), eq("operator901"))).thenReturn(72L);

        assertEquals(200, controller.publish(request("MERCHANT")).get(AjaxResult.CODE_TAG));
        assertEquals(200, controller.draft(request("MERCHANT")).get(AjaxResult.CODE_TAG));

        verify(couponAuthorizationService, org.mockito.Mockito.times(2))
                .assertOwnedRewardCoupon(null, OWNER);
        verify(publishService).publishTemplateWithTransaction(any(), eq(OWNER), eq("operator901"));
        verify(publishService).saveOrUpdateDraft(any(), eq(OWNER), eq("operator901"));
    }

    @Test
    void merchantDeleteToggleAndListUseOwnerScope() {
        when(publishService.deleteTemplateWithSync(71L, OWNER)).thenReturn(true);
        when(publishService.updateTemplateLibraryStatus(71L, 1, OWNER)).thenReturn(true);

        assertEquals(200, controller.deleteTemplate("71", "MERCHANT").get(AjaxResult.CODE_TAG));
        assertEquals(200, controller.updateTemplateLibraryStatus("71", "1", "MERCHANT")
                .get(AjaxResult.CODE_TAG));
        assertEquals(200, controller.myTemplateList("", "", "", "", "MERCHANT")
                .get(AjaxResult.CODE_TAG));

        ArgumentCaptor<MemberTemplateVO> probe = ArgumentCaptor.forClass(MemberTemplateVO.class);
        verify(memberTemplateService).selectMemberTemplateList(probe.capture());
        assertEquals(OWNER, probe.getValue().getMemberId());
    }

    @Test
    void marketSubmitUsesResolvedOwnerAndReturnsAuditTaskId() {
        when(publishService.submitTemplateToMarket(71L, OWNER, "operator901")).thenReturn(301L);

        AjaxResult result = controller.submitTemplateToMarket(71L, "MERCHANT");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(301L, result.get(AjaxResult.DATA_TAG));
        verify(publishService).submitTemplateToMarket(71L, OWNER, "operator901");
    }

    @Test
    void blankScopeKeepsOperatorPersonalTemplateIdentity() {
        when(publishService.publishTemplateWithTransaction(any(), eq(PRINCIPAL), eq("operator901"))).thenReturn(73L);

        AjaxResult result = controller.publish(request(null));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(publishService).publishTemplateWithTransaction(any(), eq(PRINCIPAL), eq("operator901"));
    }

    @Test
    void sourceAdoptionKeepsMerchantOwnerAndForcesSourceOnly() {
        CmsTemplatePublishRequest request = request("MERCHANT");
        request.setOriginalTemplateId(16L);
        request.setAdoptSourceOnly(false);
        when(publishService.saveOrUpdateDraft(any(), eq(OWNER), eq("operator901"))).thenReturn(74L);
        assertEquals(74L, controller.adoptDraft(request).get(AjaxResult.DATA_TAG));
        assertEquals(Boolean.TRUE, request.getAdoptSourceOnly());
        verify(publishService).saveOrUpdateDraft(request, OWNER, "operator901");
    }

    @Test
    void sourceAdoptionDoesNotBypassMerchantPermission() {
        IMerchantAccessService access = (IMerchantAccessService) ReflectionTestUtils.getField(controller, "merchantAccessService");
        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new com.chengyinhub.common.exception.ServiceException("无权管理项目", 403));
        assertEquals(403, controller.adoptDraft(request("MERCHANT")).get(AjaxResult.CODE_TAG));
        verify(publishService, never()).saveOrUpdateDraft(any(), any(), any());
    }

    @Test
    void sourceAdoptionAcceptsJsonBodyAndPersonalOwner() throws Exception {
        when(publishService.saveOrUpdateDraft(any(), eq(PRINCIPAL), eq("operator901"))).thenReturn(75L);
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/template/adopt-draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"旧玩法\",\"originalTemplateId\":16,\"adoptSourceOnly\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(75));
        ArgumentCaptor<CmsTemplatePublishRequest> body = ArgumentCaptor.forClass(CmsTemplatePublishRequest.class);
        verify(publishService).saveOrUpdateDraft(body.capture(), eq(PRINCIPAL), eq("operator901"));
        assertEquals(16L, body.getValue().getOriginalTemplateId());
        assertEquals(Boolean.TRUE, body.getValue().getAdoptSourceOnly());
    }

    @Test
    void sourceAdoptionRefusesUrlencodedBody() throws Exception {
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/template/adopt-draft")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("{\"title\":\"旧玩法\",\"originalTemplateId\":16}"))
                .andExpect(status().isUnsupportedMediaType());
        verify(publishService, never()).saveOrUpdateDraft(any(), any(), any());
    }

    private CmsMemberTemplate draft(long owner) {
        CmsMemberTemplate draft = new CmsMemberTemplate();
        draft.setId(74L);
        draft.setMemberId(owner);
        draft.setDelFlag(0);
        draft.setDraftStatus(0);
        draft.setStatus(0L);
        draft.setPublishStatus(0L);
        draft.setQuestionAnswer("商家草稿答案");
        draft.setAdvancedConfigJson("{\"schemaVersion\":1,\"blindBox\":{\"pool\":[{\"id\":\"private-card\"}]}}");
        return draft;
    }

    @Test
    void merchantAdoptionReadsOwnerDraftThroughSameProjectPermission() throws Exception {
        when(publishService.saveOrUpdateDraft(any(), eq(OWNER), eq("operator901"))).thenReturn(74L);
        assertEquals(74L, controller.adoptDraft(request("MERCHANT")).get(AjaxResult.DATA_TAG));
        when(memberTemplateService.selectCmsMemberTemplateById(74L)).thenReturn(draft(OWNER));
        assertEquals(200, controller.myInfo("74", "MERCHANT").get(AjaxResult.CODE_TAG), "已获管理权限的采用者无法回读草稿");

        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/template/myinfo")
                .param("id", "74").param("scope", "MERCHANT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.memberId").value(OWNER))
                .andExpect(jsonPath("$.data.questionAnswer").value("商家草稿答案"))
                .andExpect(jsonPath("$.data.advancedConfigJson").value(draft(OWNER).getAdvancedConfigJson()));
        IMerchantAccessService access = (IMerchantAccessService) ReflectionTestUtils.getField(controller, "merchantAccessService");
        verify(access, org.mockito.Mockito.times(3)).require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE);
    }

    @Test
    void merchantReadRejectsRevokedProjectPermissionBeforeLoadingTemplate() throws Exception {
        when(publishService.saveOrUpdateDraft(any(), eq(OWNER), eq("operator901"))).thenReturn(74L);
        assertEquals(74L, controller.adoptDraft(request("MERCHANT")).get(AjaxResult.DATA_TAG));
        IMerchantAccessService access = (IMerchantAccessService) ReflectionTestUtils.getField(controller, "merchantAccessService");
        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new com.chengyinhub.common.exception.ServiceException("商家身份无效或权限不足", 403));

        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/template/myinfo")
                .param("id", "74").param("scope", "MERCHANT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(memberTemplateService, never()).selectCmsMemberTemplateById(any());
    }

    @Test
    void merchantManagerCannotReadOtherOwnersPrivateDraft() throws Exception {
        when(memberTemplateService.selectCmsMemberTemplateById(74L)).thenReturn(draft(800L));
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/template/myinfo")
                .param("id", "74").param("scope", "MERCHANT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.msg").value("模版不存在"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void merchantManagerStillReceivesSafeProjectionOfOtherOwnersPublicTemplate() throws Exception {
        CmsMemberTemplate published = draft(800L);
        published.setDraftStatus(1);
        published.setStatus(1L);
        published.setPublishStatus(1L);
        when(memberTemplateService.selectCmsMemberTemplateById(74L)).thenReturn(published);
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/template/myinfo")
                .param("id", "74").param("scope", "MERCHANT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.questionAnswer").doesNotExist())
                .andExpect(jsonPath("$.data.advancedConfigJson").value(org.hamcrest.Matchers.containsString("\"schemaVersion\":1")))
                .andExpect(jsonPath("$.data.advancedConfigJson").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-card"))));
    }

    @Test
    void blankScopeKeepsPersonalReadIdentityEvenForMerchantManager() throws Exception {
        when(memberTemplateService.selectCmsMemberTemplateById(74L)).thenReturn(draft(OWNER));
        org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/template/myinfo").param("id", "74"))
                .andExpect(jsonPath("$.msg").value("模版不存在"))
                .andExpect(jsonPath("$.data").doesNotExist());
        when(memberTemplateService.selectCmsMemberTemplateById(74L)).thenReturn(draft(PRINCIPAL));
        mvc.perform(post("/api/template/myinfo").param("id", "74"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.questionAnswer").value("商家草稿答案"));
        IMerchantAccessService access = (IMerchantAccessService) ReflectionTestUtils.getField(controller, "merchantAccessService");
        verify(access, never()).require(any(), any());
    }

    @Test
    void personalOwnerCanReadPendingTemplateWithPrivateEditingFields() throws Exception {
        CmsMemberTemplate pending = draft(PRINCIPAL);
        pending.setDraftStatus(1);
        pending.setCheckStatus(0);
        when(memberTemplateService.selectCmsMemberTemplateById(74L)).thenReturn(pending);
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/template/myinfo")
                .param("id", "74"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(74))
                .andExpect(jsonPath("$.data.memberId").value(PRINCIPAL))
                .andExpect(jsonPath("$.data.draftStatus").value(1))
                .andExpect(jsonPath("$.data.status").value(0))
                .andExpect(jsonPath("$.data.publishStatus").value(0))
                .andExpect(jsonPath("$.data.questionAnswer").value(pending.getQuestionAnswer()))
                .andExpect(jsonPath("$.data.advancedConfigJson").value(pending.getAdvancedConfigJson()));
        IMerchantAccessService access = (IMerchantAccessService) ReflectionTestUtils.getField(controller, "merchantAccessService");
        verify(access, never()).require(any(), any());
    }

    @Test
    void pendingTemplateRemainsHiddenFromOtherViewers() throws Exception {
        CmsMemberTemplate pending = draft(OWNER);
        pending.setDraftStatus(1);
        pending.setCheckStatus(0);
        when(memberTemplateService.selectCmsMemberTemplateById(74L)).thenReturn(pending);
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/template/myinfo")
                .param("id", "74"))
                .andExpect(jsonPath("$.msg").value("模版不存在"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void merchantPendingReadKeepsExistingProjectManagementScope() throws Exception {
        CmsMemberTemplate pending = draft(OWNER);
        pending.setDraftStatus(1);
        pending.setCheckStatus(0);
        when(memberTemplateService.selectCmsMemberTemplateById(74L)).thenReturn(pending);
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/template/myinfo")
                .param("id", "74").param("scope", "MERCHANT"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.memberId").value(OWNER))
                .andExpect(jsonPath("$.data.questionAnswer").value(pending.getQuestionAnswer()));
        IMerchantAccessService access = (IMerchantAccessService) ReflectionTestUtils.getField(controller, "merchantAccessService");
        verify(access).require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE);
    }

    @Test
    void ownerPendingReadDoesNotExpandRemovedRejectedUnlistedOrUnknownStates() {
        for (String state : new String[]{"deleted", "rejected", "unlisted", "unknown-draft", "null-status"}) {
            CmsMemberTemplate row = draft(PRINCIPAL);
            row.setDraftStatus(1);
            row.setCheckStatus(0);
            String expected = "模版审核中";
            if ("deleted".equals(state)) { row.setDelFlag(2); expected = "模版已删除"; }
            if ("rejected".equals(state)) { row.setStatus(2L); row.setCheckStatus(2); expected = "模版审核未通过"; }
            if ("unlisted".equals(state)) { row.setStatus(1L); expected = "模版已下架"; }
            if ("unknown-draft".equals(state)) row.setDraftStatus(2);
            if ("null-status".equals(state)) row.setStatus(null);
            when(memberTemplateService.selectCmsMemberTemplateById(74L)).thenReturn(row);
            AjaxResult result = controller.myInfo("74", null);
            assertEquals(expected, result.get(AjaxResult.MSG_TAG), state);
            org.junit.jupiter.api.Assertions.assertFalse(result.containsKey(AjaxResult.DATA_TAG), state);
        }
    }

    @Test
    void invalidReadScopeCannotFallBackToPersonalIdentity() throws Exception {
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/template/myinfo")
                .param("id", "74").param("scope", "invalid-scope"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(memberTemplateService, never()).selectCmsMemberTemplateById(any());
    }
}
