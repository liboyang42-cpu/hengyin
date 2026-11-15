package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.WithdrawalDTO;
import com.chengyinhub.business.exception.WithdrawalInsufficientBalanceException;
import com.chengyinhub.business.service.IBankWithdrawalCommandService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** B2(acceptance-r3):超额提现返回具体业务码与「余额不足」,不是通用 500「请稍后重试」。 */
class ApiWithdrawalInsufficientBalanceTest {

    private AjaxResult submitWith(RuntimeException thrown) {
        ApiWithdrawalController controller = new ApiWithdrawalController() {
            @Override public Long getAppUserId() { return 9003L; }
            @Override public String getAppUserName() { return "player"; }
        };
        IBankWithdrawalCommandService command = mock(IBankWithdrawalCommandService.class);
        when(command.submit(any(), eq(9003L), eq("player"))).thenThrow(thrown);
        IUmsMemberService members = mock(IUmsMemberService.class);
        when(members.selectUmsMemberById(9003L)).thenReturn(null);
        ReflectionTestUtils.setField(controller, "bankWithdrawalCommandService", command);
        ReflectionTestUtils.setField(controller, "umsMemberService", members);
        return controller.request(new WithdrawalDTO());
    }

    @Test
    void overBalanceWithdrawalReturnsBusinessCodeAndInsufficientBalanceMessage() {
        AjaxResult result = submitWith(new WithdrawalInsufficientBalanceException(false, "用户余额不足", "用户余额不足"));
        assertEquals(409, result.get(AjaxResult.CODE_TAG));
        assertEquals(WithdrawalInsufficientBalanceException.INSUFFICIENT_BALANCE, result.get("businessCode"));
        String msg = String.valueOf(result.get(AjaxResult.MSG_TAG));
        assertTrue(msg.contains("余额不足"), msg);
        assertFalse(msg.contains("稍后重试"), msg);
    }

    @Test
    void cooldownRejectionKeepsMachineCodeOutOfTheUserMessage() {
        AjaxResult result = submitWith(new WithdrawalInsufficientBalanceException(true,
                "PLAYER_WITHDRAWAL_COOLDOWN:可提现余额不足(冷静期)", "可提现余额不足(冷静期)"));
        assertEquals(409, result.get(AjaxResult.CODE_TAG));
        assertEquals("PLAYER_WITHDRAWAL_COOLDOWN", result.get("businessCode"));
        assertFalse(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("PLAYER_WITHDRAWAL_COOLDOWN"));
    }

    @Test
    void unexpectedFailureStillUsesTheGenericMessage() {
        AjaxResult result = submitWith(new IllegalStateException("db down"));
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
    }
}
