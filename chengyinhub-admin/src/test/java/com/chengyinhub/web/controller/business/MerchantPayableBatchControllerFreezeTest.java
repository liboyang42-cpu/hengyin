package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.service.IMerchantPayableService;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P块:③应付批次冻结/解冻 + markPaid 双人复核异常回显(controller 层)。
 */
class MerchantPayableBatchControllerFreezeTest {

    private IMerchantPayableService merchantPayableService;
    private MerchantPayableBatchController controller;

    @BeforeEach
    void setUp() {
        merchantPayableService = mock(IMerchantPayableService.class);
        controller = new MerchantPayableBatchController();
        ReflectionTestUtils.setField(controller, "merchantPayableService", merchantPayableService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String username) {
        SysUser user = new SysUser();
        user.setUserId(1L);
        user.setUserName(username);
        LoginUser loginUser = new LoginUser(user, new HashSet<>());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }

    @Test
    void freezeRejectsBlankReasonBeforeCallingService() {
        Map<String, Object> body = new HashMap<>();
        body.put("frozenReason", "  ");

        AjaxResult result = controller.freeze(1L, body);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(merchantPayableService, never()).freezeBatch(anyLong(), anyString(), anyString());
    }

    @Test
    void freezeSucceedsWhenReasonProvided() {
        loginAs("riskops");
        when(merchantPayableService.freezeBatch(1L, "riskops", "疑似虚假核销")).thenReturn(true);
        Map<String, Object> body = new HashMap<>();
        body.put("frozenReason", "疑似虚假核销");

        AjaxResult result = controller.freeze(1L, body);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
    }

    @Test
    void unfreezeReportsFailureWhenNotFrozen() {
        loginAs("riskops");
        when(merchantPayableService.unfreezeBatch(1L, "riskops")).thenReturn(false);

        AjaxResult result = controller.unfreeze(1L);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
    }

    /** markPaid 双人复核异常必须回显成业务错误,不能让 500 IllegalStateException 冒穿到前端变成系统异常页。 */
    @Test
    void markPaidTranslatesMakerCheckerViolationToBusinessError() {
        loginAs("finance1");
        when(merchantPayableService.markBatchPaid(1L, "finance1", "V1"))
                .thenThrow(new IllegalStateException("复核人不能与提交人为同一人(双人复核)"));
        Map<String, Object> body = new HashMap<>();
        body.put("payVoucherNo", "V1");

        AjaxResult result = controller.markPaid(1L, body);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("复核人不能与提交人为同一人(双人复核)", result.get(AjaxResult.MSG_TAG));
    }
}
