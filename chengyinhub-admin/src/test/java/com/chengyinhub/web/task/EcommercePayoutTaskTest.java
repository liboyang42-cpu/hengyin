package com.chengyinhub.web.task;

import com.chengyinhub.business.payout.EcommerceProfitShareService;
import com.chengyinhub.business.payout.EcommerceSubMerchantService;
import com.chengyinhub.business.payout.PayoutSwitches;
import com.chengyinhub.business.service.impl.AlarmService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** l-payout2：电商收付通定时任务。开关关闭零调用；打开时按「补计划 → 提交到期 → 查分账 → 查回退」顺序；失败告警后抛出。 */
class EcommercePayoutTaskTest {
    private final EcommerceProfitShareService sharing = mock(EcommerceProfitShareService.class);
    private final EcommerceSubMerchantService subs = mock(EcommerceSubMerchantService.class);
    private final AlarmService alarms = mock(AlarmService.class);

    private EcommercePayoutTask task(PayoutSwitches switches) {
        EcommercePayoutTask t = new EcommercePayoutTask();
        ReflectionTestUtils.setField(t, "switches", switches);
        ReflectionTestUtils.setField(t, "profitShareService", sharing);
        ReflectionTestUtils.setField(t, "subMerchantService", subs);
        ReflectionTestUtils.setField(t, "alarmService", alarms);
        return t;
    }

    @Test
    void switchOffDoesNothing() {
        EcommercePayoutTask t = task(PayoutSwitches.allOff());
        t.shareAndRetry();
        t.syncOnboarding();
        verifyNoInteractions(sharing, subs, alarms);
    }

    @Test
    void disabledMasterWithConfiguredCredentialsDoesNotCallAnyService() {
        EcommercePayoutTask t = task(new PayoutSwitches(false, true, true,
                "1900000001", "wx_mini", "", 30, false, "none"));
        t.shareAndRetry();
        t.syncOnboarding();
        verifyNoInteractions(sharing, subs, alarms);
    }

    @Test
    void personalOptInCannotActivateJobsWithoutPayoutMasterSwitch() {
        EcommercePayoutTask t = task(PayoutSwitches.allOff().withPersonalClubEnabled(true));
        t.shareAndRetry();
        t.syncOnboarding();
        verifyNoInteractions(sharing, subs, alarms);
    }

    @Test
    void switchOnRunsInOrderAndFailureAlarmsThenThrows() {
        PayoutSwitches on = new PayoutSwitches(true, true, true, "1900000001", "wx_mini", "", 30, false, "none");
        EcommercePayoutTask t = task(on);
        t.shareAndRetry();
        var order = inOrder(sharing);
        order.verify(sharing).planMissing(50);
        order.verify(sharing).submitPlanned(50);
        order.verify(sharing).syncProcessing(100);
        order.verify(sharing).syncReturns(100);
        t.syncOnboarding();
        verify(subs).refreshInProgress(100);
        IllegalStateException boom = new IllegalStateException("x");
        when(sharing.submitPlanned(anyInt())).thenThrow(boom);
        assertSame(boom, assertThrows(IllegalStateException.class, t::shareAndRetry));
        verify(alarms).alarm(anyString(), anyString());
    }
}
