package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
