package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ICashWithdrawService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 通道A 快速提现总开关:关闭时必须在任何资金动作前拒绝,绝不进入 service。
 */
class ApiWalletControllerSwitchTest {

    @Test
    void fastWithdrawDisabled_rejectsBeforeService() {
        ApiWalletController c = new ApiWalletController();
        ICashWithdrawService svc = mock(ICashWithdrawService.class);
        ReflectionTestUtils.setField(c, "cashWithdrawService", svc);
        ReflectionTestUtils.setField(c, "fastWithdrawEnabled", false);

        java.util.Map<String, Object> body = new HashMap<>();
        body.put("amount", "10");
        AjaxResult r = c.withdraw(body);

        // 返回错误(非成功码),且绝不调用提现 service(未鉴权、未扣款)
        assertNotEquals(200, r.get("code"));
        verify(svc, never()).withdraw(any(), any());
    }
}
