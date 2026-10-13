package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.impl.MerchantCommerceService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 商业化权益(P4 #17-3):商家成员 + PROFILE_WRITE,商家主体按 ownerMemberId/merchantId。 */
class ApiMerchantCommerceControllerTest {
    private static final long PRINCIPAL = 9002L;
    private static final long OWNER = 9001L;
    private static final long MERCHANT_ROW = 11L;

    @Test
    void managerReadsCapabilitiesScopedToOwnerMerchant() {
        IMerchantAccessService access = access(MerchantRoleCode.MERCHANT_MANAGER);
        MerchantCommerceService commerce = mock(MerchantCommerceService.class);
        IUmsMemberService members = mock(IUmsMemberService.class);
        when(commerce.isSelfCheckoutEnabled()).thenReturn(true);
        MerchantCommerceService.Entitlement entitlement = new MerchantCommerceService.Entitlement(3, 1);
        when(commerce.entitlement(OWNER, "merchant",
                MerchantCommerceService.BIZ_PREMIUM_TEMPLATE, 0)).thenReturn(entitlement);
        when(commerce.entitlement(OWNER, "merchant",
                MerchantCommerceService.ENTITLEMENT_CITY_NODE, 0)).thenReturn(entitlement);
        when(members.selectUmsMemberById(OWNER)).thenReturn(member(OWNER, "merchant", null));

        AjaxResult result = controller(access, commerce, members).capabilities();

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        verify(commerce).entitlement(OWNER, "merchant", MerchantCommerceService.BIZ_PREMIUM_TEMPLATE, 0);
        verify(commerce).entitlement(OWNER, "merchant", MerchantCommerceService.ENTITLEMENT_CITY_NODE, 0);
        verify(access).require(PRINCIPAL, MerchantPermission.PROFILE_WRITE);
    }

    @Test
    void managerCreatesOrderUnderOwnerMerchantWithOwnPaymentIdentity() {
        IMerchantAccessService access = access(MerchantRoleCode.MERCHANT_MANAGER);
        MerchantCommerceService commerce = mock(MerchantCommerceService.class);
        IUmsMemberService members = mock(IUmsMemberService.class);
        Map<String, String> order = Collections.singletonMap("orderSn", "TPLTM_1");
        when(members.selectUmsMemberById(PRINCIPAL)).thenReturn(member(PRINCIPAL, "user", "wx-op-2"));
        when(commerce.createOrder(OWNER, MERCHANT_ROW, "wx-op-2", "premium_template", "req-1"))
                .thenReturn(order);

        AjaxResult result = controller(access, commerce, members).createOrder(
                payload("bizType", "premium_template", "requestId", "req-1"));

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        verify(commerce).createOrder(OWNER, MERCHANT_ROW, "wx-op-2", "premium_template", "req-1");
    }

    @Test
    void managerChecksOrderStatusUnderOwnerMerchant() {
        IMerchantAccessService access = access(MerchantRoleCode.MERCHANT_MANAGER);
        MerchantCommerceService commerce = mock(MerchantCommerceService.class);
        IUmsMemberService members = mock(IUmsMemberService.class);
        when(commerce.orderStatus(OWNER, MERCHANT_ROW, "SN-1"))
                .thenReturn(Collections.singletonMap("paymentStatus", "success"));

        AjaxResult result = controller(access, commerce, members).orderStatus(payload("orderSn", "SN-1"));

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        verify(commerce).orderStatus(OWNER, MERCHANT_ROW, "SN-1");
    }

    @Test
    void checkinWithoutProfileWriteCannotReachCommerce() {
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(PRINCIPAL, MerchantPermission.PROFILE_WRITE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", HttpStatus.FORBIDDEN));
        MerchantCommerceService commerce = mock(MerchantCommerceService.class);
        IUmsMemberService members = mock(IUmsMemberService.class);
        ApiMerchantCommerceController controller = controller(access, commerce, members);

        ServiceException error = assertThrows(ServiceException.class, controller::capabilities);
        assertEquals(HttpStatus.FORBIDDEN, error.getCode());
        assertThrows(ServiceException.class, () -> controller.createOrder(payload("bizType", "premium_template")));
        assertThrows(ServiceException.class, () -> controller.orderStatus(payload("orderSn", "SN-1")));
        verifyNoInteractions(commerce, members);
    }

    private ApiMerchantCommerceController controller(IMerchantAccessService access,
                                                     MerchantCommerceService commerce,
                                                     IUmsMemberService members) {
        ApiMerchantCommerceController controller = new LoggedInController(PRINCIPAL);
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "commerceService", commerce);
        ReflectionTestUtils.setField(controller, "memberService", members);
        return controller;
    }

    private IMerchantAccessService access(String roleCode) {
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(eq(PRINCIPAL), anyString())).thenReturn(
                new MerchantAccessContext(PRINCIPAL, MERCHANT_ROW, OWNER, roleCode));
        return access;
    }

    private UmsMember member(Long id, String role, String openId) {
        UmsMember member = new UmsMember();
        member.setId(id);
        member.setRole(role);
        member.setWxOpenId(openId);
        return member;
    }

    private Map<String, Object> payload(String key, String value) {
        return Collections.singletonMap(key, value);
    }

    private Map<String, Object> payload(String key, String value, String key2, String value2) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put(key, value);
        body.put(key2, value2);
        return body;
    }

    private static class LoggedInController extends ApiMerchantCommerceController {
        private final Long principal;

        private LoggedInController(Long principal) {
            this.principal = principal;
        }

        @Override
        public Long getAppUserId() {
            return principal;
        }
    }
}
