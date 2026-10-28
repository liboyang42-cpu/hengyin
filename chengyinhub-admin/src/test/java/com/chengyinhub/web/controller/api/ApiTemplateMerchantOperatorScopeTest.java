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
import com.chengyinhub.business.service.support.TemplateLibraryRepublishReview;
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
    private com.chengyinhub.business.service.support.TemplateAdoptionService adoptionService;
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
        adoptionService = mock(com.chengyinhub.business.service.support.TemplateAdoptionService.class);
        ReflectionTestUtils.setField(controller, "templateAdoptionService", adoptionService);
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

    private com.chengyinhub.business.domain.dto.TemplateAdoptionCommand adoption(CmsTemplatePublishRequest request) {
        var command = new com.chengyinhub.business.domain.dto.TemplateAdoptionCommand();
        command.setRequestId("adopt_test_request_001"); command.setSourceVersion("a".repeat(64));
        command.setScope(request.getScope()); command.setOriginalTemplateId(request.getOriginalTemplateId() == null ? "16" : request.getOriginalTemplateId().toString());
        return command;
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
        // 上架复检按我的模板 + 归属读投影(#398 F2),商家操作员同样落到店主 OWNER 名下。
        com.chengyinhub.business.service.ICmsTemplateLibraryService libraryService =
                mock(com.chengyinhub.business.service.ICmsTemplateLibraryService.class);
        com.chengyinhub.business.domain.CmsTemplateLibrary library =
                new com.chengyinhub.business.domain.CmsTemplateLibrary();
        library.setId(171L);
        library.setMemberTemplateId(71L);
        library.setMemberId(OWNER);
        library.setDelFlag(0);
        when(libraryService.selectCmsTemplateLibraryList(any())).thenReturn(Collections.singletonList(library));
        ReflectionTestUtils.setField(controller, "templateLibraryService", libraryService);
        when(publishService.updateTemplateLibraryStatus(eq(71L), eq(1), eq(OWNER),
                any(TemplateLibraryRepublishReview.class))).thenReturn(true);

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
        CmsTemplatePublishRequest request = request("MERCHANT"); request.setOriginalTemplateId(16L);
        when(adoptionService.adopt(eq(PRINCIPAL), eq(OWNER), any(), eq("operator901")))
                .thenReturn(java.util.Map.of("draftId", "74", "status", "COMPLETED"));
        assertEquals(java.util.Map.of("draftId", "74", "status", "COMPLETED"), controller.adoptDraft(adoption(request)).get(AjaxResult.DATA_TAG));
        ArgumentCaptor<com.chengyinhub.business.domain.dto.TemplateAdoptionCommand> command = ArgumentCaptor.forClass(com.chengyinhub.business.domain.dto.TemplateAdoptionCommand.class);
        verify(adoptionService).adopt(eq(PRINCIPAL), eq(OWNER), command.capture(), eq("operator901"));
        assertEquals("16", command.getValue().getOriginalTemplateId());
        assertEquals("adopt_test_request_001", command.getValue().getRequestId());
        assertEquals("a".repeat(64), command.getValue().getSourceVersion());
        verify(publishService, never()).saveOrUpdateDraft(any(), any(), any());
    }

    @Test
    void sourceAdoptionDoesNotBypassMerchantPermission() {
        IMerchantAccessService access = (IMerchantAccessService) ReflectionTestUtils.getField(controller, "merchantAccessService");
        when(access.require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new com.chengyinhub.common.exception.ServiceException("无权管理项目", 403));
        assertEquals(403, controller.adoptDraft(adoption(request("MERCHANT"))).get(AjaxResult.CODE_TAG));
        verify(publishService, never()).saveOrUpdateDraft(any(), any(), any());
    }

    @Test
    void sourceAdoptionAcceptsOnlyLibraryIdentityWithoutRequiringPublicContentCopy() throws Exception {
        when(publishService.saveOrUpdateDraft(any(), eq(OWNER), eq("operator901"))).thenReturn(76L);
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/template/adopt-draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"originalTemplateId\":16,\"scope\":\"MERCHANT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(76));
        ArgumentCaptor<CmsTemplatePublishRequest> body = ArgumentCaptor.forClass(CmsTemplatePublishRequest.class);
        verify(publishService).saveOrUpdateDraft(body.capture(), eq(OWNER), eq("operator901"));
        assertEquals(16L, body.getValue().getOriginalTemplateId());
        assertEquals(Boolean.TRUE, body.getValue().getAdoptSourceOnly());
        verify(couponAuthorizationService, never()).assertOwnedRewardCoupon(any(), any());
    }

    @Test
    void sourceAdoptionIgnoresDiscardedClientCouponButOrdinaryDraftStillChecksIt() {
        CmsTemplatePublishRequest adopting = request("MERCHANT");
        adopting.setTitle(null); adopting.setOriginalTemplateId(16L); adopting.setCouponId(999L);
        when(publishService.saveOrUpdateDraft(any(), eq(OWNER), eq("operator901"))).thenReturn(76L);
        assertEquals(200, controller.adoptDraft(adopting).get(AjaxResult.CODE_TAG));
        verify(couponAuthorizationService, never()).assertOwnedRewardCoupon(any(), any());

        CmsTemplatePublishRequest ordinary = request("MERCHANT"); ordinary.setCouponId(999L);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("非本主体优惠券"))
                .when(couponAuthorizationService).assertOwnedRewardCoupon(999L, OWNER);
        assertEquals(500, controller.draft(ordinary).get(AjaxResult.CODE_TAG));
        verify(couponAuthorizationService).assertOwnedRewardCoupon(999L, OWNER);
        verify(publishService, never()).saveOrUpdateDraft(eq(ordinary), any(), any());
    }

    @Test
    void sourceAdoptionAcceptsJsonBodyAndPersonalOwner() throws Exception {
        when(adoptionService.adopt(eq(PRINCIPAL), eq(PRINCIPAL), any(), eq("operator901")))
                .thenReturn(java.util.Map.of("draftId", "9007199254740993", "status", "COMPLETED"));
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/template/adopt-draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"adopt_test_request_001\",\"sourceVersion\":\"" + "a".repeat(64) + "\",\"originalTemplateId\":\"9007199254740995\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.draftId").value("9007199254740993"));
        ArgumentCaptor<com.chengyinhub.business.domain.dto.TemplateAdoptionCommand> body = ArgumentCaptor.forClass(com.chengyinhub.business.domain.dto.TemplateAdoptionCommand.class);
        verify(adoptionService).adopt(eq(PRINCIPAL), eq(PRINCIPAL), body.capture(), eq("operator901"));
        assertEquals("9007199254740995", body.getValue().getOriginalTemplateId());
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
        when(adoptionService.adopt(eq(PRINCIPAL), eq(OWNER), any(), eq("operator901")))
                .thenReturn(java.util.Map.of("draftId", "74"));
        assertEquals(java.util.Map.of("draftId", "74"), controller.adoptDraft(adoption(request("MERCHANT"))).get(AjaxResult.DATA_TAG));
        when(memberTemplateService.selectCmsMemberTemplateById(74L)).thenReturn(draft(OWNER));
        assertEquals(200, controller.myInfo("74", "MERCHANT").get(AjaxResult.CODE_TAG), "已获管理权限的采用者无法回读草稿");

        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/template/myinfo")
                .param("id", "74").param("scope", "MERCHANT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.memberId").value(OWNER))
                .andExpect(jsonPath("$.data.canEdit").value(true))
                .andExpect(jsonPath("$.data.questionAnswer").value("商家草稿答案"))
                .andExpect(jsonPath("$.data.advancedConfigJson").value(draft(OWNER).getAdvancedConfigJson()));
        IMerchantAccessService access = (IMerchantAccessService) ReflectionTestUtils.getField(controller, "merchantAccessService");
        verify(access, org.mockito.Mockito.times(3)).require(PRINCIPAL, MerchantPermission.PROJECT_MANAGE);
    }

    @Test
    void merchantReadRejectsRevokedProjectPermissionBeforeLoadingTemplate() throws Exception {
        when(adoptionService.adopt(eq(PRINCIPAL), eq(OWNER), any(), eq("operator901")))
                .thenReturn(java.util.Map.of("draftId", "74"));
        assertEquals(java.util.Map.of("draftId", "74"), controller.adoptDraft(adoption(request("MERCHANT"))).get(AjaxResult.DATA_TAG));
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
                .andExpect(jsonPath("$.data.canEdit").value(false))
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
                .andExpect(jsonPath("$.data.canEdit").value(true))
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
                .andExpect(jsonPath("$.data.canEdit").value(true))
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

    @Test
    void canEditIsReturnedToClientsButCannotBeSetByRequestJson() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        CmsMemberTemplate submitted = mapper.readValue("{\"id\":74,\"canEdit\":true}", CmsMemberTemplate.class);
        org.junit.jupiter.api.Assertions.assertNull(submitted.getCanEdit());

        submitted.setCanEdit(false);
        org.junit.jupiter.api.Assertions.assertFalse(mapper.readTree(mapper.writeValueAsString(submitted))
                .get("canEdit").asBoolean());
    }
}
