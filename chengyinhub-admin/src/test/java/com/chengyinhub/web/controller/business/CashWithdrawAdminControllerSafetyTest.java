package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.service.ICashWithdrawService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CashWithdrawAdminControllerSafetyTest {

    private ICashWithdrawService cashWithdrawService;
    private CashWithdrawAdminController controller;

    @BeforeEach
    void setUp() {
        cashWithdrawService = mock(ICashWithdrawService.class);
        controller = new CashWithdrawAdminController();
        ReflectionTestUtils.setField(controller, "cashWithdrawService", cashWithdrawService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 装一个登录态,供 controller.getUsername() 读取(freeze/unfreeze 需要记冻结操作人)。 */
    private void loginAs(String username) {
        SysUser user = new SysUser();
        user.setUserId(1L);
        user.setUserName(username);
        LoginUser loginUser = new LoginUser(user, new HashSet<>());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }

    @Test
    void manualRefundRejectsBlankReasonBeforeLoadingOrMovingMoney() {
        MemberCashWithdraw request = new MemberCashWithdraw();
        request.setId(7L);
        request.setStatus(MemberCashWithdraw.STATUS_FAILED_MANUAL);
        request.setManualNote("  ");

        AjaxResult result = controller.manualRefund(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(cashWithdrawService, never()).getById(7L);
        verify(cashWithdrawService, never()).manualRefund(7L, null, null, MemberCashWithdraw.STATUS_FAILED_MANUAL);
    }

    @Test
    void manualRefundReportsMissingFinancialEvidenceAsInvestigation() {
        loginAs("finance");
        MemberCashWithdraw request = new MemberCashWithdraw();
        request.setId(7L); request.setStatus(MemberCashWithdraw.STATUS_FAILED_MANUAL); request.setManualNote("核查");
        when(cashWithdrawService.getById(7L)).thenReturn(request);
        when(cashWithdrawService.manualRefund(7L, "finance", "核查", 3))
                .thenThrow(new IllegalStateException("缺少原扣款持久证据，保留调查"));
        AjaxResult result = controller.manualRefund(request);
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("缺少原扣款持久证据，保留调查", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void freezeRejectsBlankReasonBeforeCallingService() {
        MemberCashWithdraw request = new MemberCashWithdraw();
        request.setId(7L);
        request.setFrozenReason("  ");

        AjaxResult result = controller.freeze(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(cashWithdrawService, never()).freezeWithdraw(anyLong(), anyString(), anyString());
    }

    @Test
    void freezeRejectsMissingId() {
        MemberCashWithdraw request = new MemberCashWithdraw();
        request.setFrozenReason("疑似盗号");

        AjaxResult result = controller.freeze(request);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(cashWithdrawService, never()).freezeWithdraw(anyLong(), anyString(), anyString());
    }

    @Test
    void freezeSucceedsWhenReasonProvided() {
        loginAs("riskops");
        when(cashWithdrawService.freezeWithdraw(7L, "riskops", "疑似盗号")).thenReturn(true);
        MemberCashWithdraw request = new MemberCashWithdraw();
        request.setId(7L);
        request.setFrozenReason("疑似盗号");

        AjaxResult result = controller.freeze(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
    }

    @Test
    void unfreezeReportsFailureWhenNotFrozen() {
        loginAs("riskops");
        when(cashWithdrawService.unfreezeWithdraw(7L, "riskops")).thenReturn(false);

        AjaxResult result = controller.unfreeze(7L);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
    }
}
