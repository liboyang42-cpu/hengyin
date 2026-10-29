package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.domain.dto.TemplateAdoptionCommand;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.mapper.TemplateAdoptionReceiptMapper;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICouponRewardAuthorizationService;
import com.chengyinhub.business.service.ITemplatePublishService;
import com.chengyinhub.business.service.support.TemplateLibraryRepublishReview;
import com.chengyinhub.business.service.support.TemplateAdoptionService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
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

    private record SourceAdoptionFixture(TemplateAdoptionCommand command,
            CmsTemplateLibrary source, CmsMemberTemplate canonical) { }

    /** Real command/hash/receipt owner; mapper and draft persistence are explicit in-memory unit boundaries.
     * The controlled transaction manager matches the existing adoption unit fixture, not SQL rollback proof. */
    private SourceAdoptionFixture installSourceAdoption() {
        TemplateAdoptionReceiptMapper receipts = mock(TemplateAdoptionReceiptMapper.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        Map<String, Object> row = new HashMap<>();
        when(receipts.reserve(anyMap())).thenAnswer(call -> {
            if (row.isEmpty()) {
                Map<String, Object> facts = call.getArgument(0);
                row.putAll(facts); row.put("status", "PENDING");
            }
            return 1;
        });
        when(receipts.lock(eq(PRINCIPAL), any())).thenAnswer(call -> new HashMap<>(row));
        when(receipts.read(eq(PRINCIPAL), any())).thenAnswer(call -> new HashMap<>(row));
        when(receipts.finish(eq(PRINCIPAL), any(), any(), any(), any(), any())).thenAnswer(call -> {
            assertEquals(row.get("requestId"), call.getArgument(1));
            row.put("status", call.getArgument(2)); row.put("draftId", call.getArgument(3));
            row.put("reason", call.getArgument(4)); row.put("draftVersion", call.getArgument(5));
            return 1;
        });
        CmsTemplateLibrary source = new CmsTemplateLibrary();
        source.setId(16L); source.setMemberTemplateId(116L); source.setMemberId(800L);
        source.setTitle("原公共内容"); source.setDelFlag(0); source.setStatus(1L); source.setPublishStatus(1L);
        source.setQuestionAnswer("原服务端答案"); source.setAdvancedConfigJson("{}");
        CmsMemberTemplate canonical = draft(800L);
        canonical.setId(116L); canonical.setTitle(source.getTitle()); canonical.setDraftStatus(1);
        canonical.setStatus(1L); canonical.setPublishStatus(1L);
        canonical.setQuestionAnswer(source.getQuestionAnswer()); canonical.setAdvancedConfigJson("{}");
        when(receipts.sourceCanonicalId(16L)).thenReturn(116L);
        when(receipts.lockSource(16L)).thenReturn(source);
        when(receipts.lockDraft(116L)).thenReturn(canonical);
        CmsMemberTemplate created = draft(OWNER); created.setId(76L); created.setTitle(source.getTitle());
        created.setOriginalTemplateId(16L); created.setQuestionAnswer(source.getQuestionAnswer());
        created.setAdvancedConfigJson(source.getAdvancedConfigJson());
        var draftWritten = new java.util.concurrent.atomic.AtomicBoolean();
        when(receipts.lockDraft(76L)).thenAnswer(call -> draftWritten.get() ? created : null);
        when(publishService.saveOrUpdateDraft(any(), eq(OWNER), eq("operator901"))).thenAnswer(call -> {
            org.junit.jupiter.api.Assertions.assertFalse(draftWritten.getAndSet(true), "原采用命令不可创建第二份草稿");
            return created.getId();
        });
        adoptionService = new TemplateAdoptionService(receipts, publishService, manager);
        ReflectionTestUtils.setField(controller, "templateAdoptionService", adoptionService);
        AjaxResult sourceResult = controller.adoptionSource("16", "MERCHANT");
        assertEquals(200, sourceResult.get(AjaxResult.CODE_TAG));
        Map<?, ?> version = (Map<?, ?>) sourceResult.get(AjaxResult.DATA_TAG);
        TemplateAdoptionCommand command = adoption(request("MERCHANT"));
        command.setSourceVersion((String) version.get("sourceVersion"));
        assertEquals("16", version.get("originalTemplateId"));
        return new SourceAdoptionFixture(command, source, canonical);
    }

    private String adoptionJson(TemplateAdoptionCommand command) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(command);
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
        verify(adoptionService, never()).adopt(any(), any(), any(), any());
        verify(publishService, never()).saveOrUpdateDraft(any(), any(), any());
    }

    @Test
    void sourceAdoptionAcceptsOnlyLibraryIdentityWithoutRequiringPublicContentCopy() throws Exception {
        SourceAdoptionFixture fixture = installSourceAdoption();
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String bodyJson = adoptionJson(fixture.command());
        mvc.perform(post("/api/template/adopt-draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.draftId").value("76"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.originalTemplateId").value("16"))
                .andExpect(jsonPath("$.data.requestId").value(fixture.command().getRequestId()));
        mvc.perform(post("/api/template/adopt-draft").contentType(MediaType.APPLICATION_JSON).content(bodyJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.draftId").value("76"))
                .andExpect(jsonPath("$.data.requestId").value(fixture.command().getRequestId()));
        ArgumentCaptor<CmsTemplatePublishRequest> body = ArgumentCaptor.forClass(CmsTemplatePublishRequest.class);
        verify(publishService, times(1)).saveOrUpdateDraft(body.capture(), eq(OWNER), eq("operator901"));
        assertEquals(16L, body.getValue().getOriginalTemplateId());
        assertEquals(Boolean.TRUE, body.getValue().getAdoptSourceOnly());
        assertEquals("MERCHANT", body.getValue().getScope());
        assertNull(body.getValue().getTitle(), "客户端不需读取或复制原公共内容");
        assertNull(body.getValue().getCouponId());
        assertEquals("原公共内容", fixture.source().getTitle());
        assertEquals("原服务端答案", fixture.source().getQuestionAnswer());
        assertEquals(800L, fixture.source().getMemberId());
        assertEquals(116L, fixture.canonical().getId());
        assertEquals(800L, fixture.canonical().getMemberId());
        verify(couponAuthorizationService, never()).assertOwnedRewardCoupon(any(), any());
    }

    @Test
    void sourceAdoptionIgnoresDiscardedClientCouponButOrdinaryDraftStillChecksIt() throws Exception {
        SourceAdoptionFixture fixture = installSourceAdoption();
        var submitted = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(fixture.command());
        ((com.fasterxml.jackson.databind.node.ObjectNode) submitted).put("couponId", 999L).putNull("title");
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/template/adopt-draft")
                .contentType(MediaType.APPLICATION_JSON).content(submitted.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.draftId").value("76"));
        ArgumentCaptor<CmsTemplatePublishRequest> adopted = ArgumentCaptor.forClass(CmsTemplatePublishRequest.class);
        verify(publishService).saveOrUpdateDraft(adopted.capture(), eq(OWNER), eq("operator901"));
        assertEquals(16L, adopted.getValue().getOriginalTemplateId());
        assertEquals(Boolean.TRUE, adopted.getValue().getAdoptSourceOnly());
        assertNull(adopted.getValue().getCouponId(), "不可信客户端coupon不能进入真实采用owner转发的draft");
        assertNull(adopted.getValue().getTitle());
        assertNull(fixture.source().getCouponId());
        assertEquals("原服务端答案", fixture.canonical().getQuestionAnswer());
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
