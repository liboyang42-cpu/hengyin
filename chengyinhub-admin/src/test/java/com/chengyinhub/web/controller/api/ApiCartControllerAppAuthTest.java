package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ViewMemberCart;
import com.chengyinhub.business.domain.vo.MemberCartVO;
import com.chengyinhub.business.service.IUmsMemberCartService;
import com.chengyinhub.business.service.IViewMemberCartService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies every /api/cart/* endpoint authenticates C-end LoginAppUser principals
 * via getAppUserId() / getAppUserName() rather than admin getLoginUser().
 */
class ApiCartControllerAppAuthTest {

    private static final long MEMBER_ID = 9004L;
    private static final String MEMBER_NAME = "e2e-player";

    private IViewMemberCartService viewMemberCartService;
    private IUmsMemberCartService umsMemberCartService;
    private ApiCartController controller;

    @BeforeEach
    void setUp() {
        viewMemberCartService = mock(IViewMemberCartService.class);
        umsMemberCartService = mock(IUmsMemberCartService.class);
        controller = new ApiCartController();
        ReflectionTestUtils.setField(controller, "viewMemberCartService", viewMemberCartService);
        ReflectionTestUtils.setField(controller, "umsMemberCartService", umsMemberCartService);

        MockHttpServletRequest req = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        AppUser appUser = new AppUser();
        appUser.setUserId(MEMBER_ID);
        appUser.setUserName(MEMBER_NAME);
        LoginAppUser principal = new LoginAppUser(MEMBER_ID, appUser);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void allSixEndpointsResolveLoginAppUserPrincipal() {
        when(viewMemberCartService.selectViewMemberCartList(argThat(c ->
                c != null && Long.valueOf(MEMBER_ID).equals(c.getMemberId()) && Long.valueOf(0L).equals(c.getIsBuy()))))
                .thenReturn(Collections.singletonList(new ViewMemberCart()));
        when(umsMemberCartService.addCart(MEMBER_ID, "10", "20", "1", "0")).thenReturn(1L);
        when(umsMemberCartService.updateCart(MEMBER_ID, "1", "2", "20")).thenReturn(1L);
        when(umsMemberCartService.previewSettlement(MEMBER_ID, "1")).thenReturn(new MemberCartVO());
        when(umsMemberCartService.settleCart(MEMBER_ID, MEMBER_NAME, "1", "note", "2")).thenReturn(1L);

        AjaxResult listRes = controller.getCartList();
        assertTrue(listRes.isSuccess());
        assertTrue(controller.cartAdd("10", "20", "1", "0").isSuccess());
        assertTrue(controller.cartUpdate("1", "2", "20").isSuccess());
        assertTrue(controller.cartSettlement("1").isSuccess());
        assertTrue(controller.orderSettlement("1", "note", "2").isSuccess());
        assertTrue(controller.cartDelete("1").isSuccess());

        verify(umsMemberCartService).deleteCarts(MEMBER_ID, "1");
        verify(umsMemberCartService).settleCart(MEMBER_ID, MEMBER_NAME, "1", "note", "2");
    }

    @Test
    void unauthenticatedCallerThrows401() {
        SecurityContextHolder.clearContext();
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.getCartList());
        assertEquals(401, ex.getCode());
    }
}
