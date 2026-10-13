package com.chengyinhub.web.task;

import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.impl.AlarmService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CoopSettlementPayoutTaskTest {

    @Test
    void runDoesNotAlertWhenPayoutSucceeds() {
        ICmsTopicService cmsTopicService = mock(ICmsTopicService.class);
        AlarmService alarmService = mock(AlarmService.class);
        when(cmsTopicService.payoutDueCoopSettlements()).thenReturn(3);

        CoopSettlementPayoutTask task = new CoopSettlementPayoutTask();
        ReflectionTestUtils.setField(task, "cmsTopicService", cmsTopicService);
        ReflectionTestUtils.setField(task, "alarmService", alarmService);

        task.run();

        verifyNoInteractions(alarmService);
    }

    @Test
    void runAlertsWhenPayoutThrows() {
        ICmsTopicService cmsTopicService = mock(ICmsTopicService.class);
        AlarmService alarmService = mock(AlarmService.class);
        when(cmsTopicService.payoutDueCoopSettlements()).thenThrow(new RuntimeException("db timeout"));

        CoopSettlementPayoutTask task = new CoopSettlementPayoutTask();
        ReflectionTestUtils.setField(task, "cmsTopicService", cmsTopicService);
        ReflectionTestUtils.setField(task, "alarmService", alarmService);

        // 1-04:告警之后必须再抛,Quartz 才会记 FAIL
        RuntimeException thrown = assertThrows(RuntimeException.class, task::run);
        assertEquals("db timeout", thrown.getMessage());
        verify(alarmService).alarm(eq("商家结算T+7打款失败"), contains("db timeout"));
    }

    @Test
    void alarmFailureDoesNotMaskOriginalPayoutFailure() {
        ICmsTopicService cmsTopicService = mock(ICmsTopicService.class);
        AlarmService alarmService = mock(AlarmService.class);
        when(cmsTopicService.payoutDueCoopSettlements()).thenThrow(new RuntimeException("db timeout"));
        org.mockito.Mockito.doThrow(new RuntimeException("webhook down"))
                .when(alarmService).alarm(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

        CoopSettlementPayoutTask task = new CoopSettlementPayoutTask();
        ReflectionTestUtils.setField(task, "cmsTopicService", cmsTopicService);
        ReflectionTestUtils.setField(task, "alarmService", alarmService);

        RuntimeException thrown = assertThrows(RuntimeException.class, task::run);
        assertEquals("db timeout", thrown.getMessage(), "告警通道挂了不能盖掉打款失败本身");
    }
}
