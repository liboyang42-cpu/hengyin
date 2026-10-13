package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.MemberCashWithdrawMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CashWithdrawReconciliationAlarmTest {
    @Test void alertsContainBoundedActualWithdrawalIdsAndKeepNullUnknownDistinctFromZero() {
        MemberCashWithdrawMapper mapper = mock(MemberCashWithdrawMapper.class);
        AlarmService alarm = mock(AlarmService.class);
        CashWithdrawServiceImpl service = service(mapper, alarm);
        when(mapper.countStuckProcessing(any())).thenReturn(30);
        when(mapper.countManualPending()).thenReturn(2);
        when(mapper.countSuccessWithoutBatch()).thenReturn(1);
        when(mapper.sampleStuckProcessingIds(any())).thenReturn(LongStream.rangeClosed(1, 30).boxed().toList());
        when(mapper.sampleManualPendingIds()).thenReturn(Arrays.asList(41L, null));
        when(mapper.sampleSuccessWithoutBatchIds()).thenReturn(null);
        String summary = service.reconcile(12);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(alarm).alarmThrottled(eq("monitor.withdraw.stuck"), anyString(), detail.capture());
        assertTrue(detail.getValue().contains("1,2,3")); assertTrue(detail.getValue().contains(",20]"));
        assertFalse(detail.getValue().contains(",21"));
        verify(alarm).alarmThrottled(eq("monitor.withdraw.manual"), anyString(), contains("[41,UNKNOWN]"));
        verify(alarm).alarmThrottled(eq("monitor.withdraw.missing_batch"), anyString(), contains("withdrawIds=UNKNOWN"));
        assertTrue(summary.contains("30"));
        verify(mapper, never()).updateMemberCashWithdraw(any());
    }

    @Test void healthyCountsNeedNoSampleReadOrAlarmAndSqlFailureCannotLookHealthy() {
        MemberCashWithdrawMapper mapper = mock(MemberCashWithdrawMapper.class);
        AlarmService alarm = mock(AlarmService.class);
        CashWithdrawServiceImpl service = service(mapper, alarm);
        service.reconcile(12);
        verifyNoInteractions(alarm);
        verify(mapper, never()).sampleStuckProcessingIds(any());
        verify(mapper, never()).sampleManualPendingIds();
        verify(mapper, never()).sampleSuccessWithoutBatchIds();
        IllegalStateException failure = new IllegalStateException("fixture failure");
        when(mapper.countStuckProcessing(any())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.reconcile(12)));
    }

    private CashWithdrawServiceImpl service(MemberCashWithdrawMapper mapper, AlarmService alarm) {
        CashWithdrawServiceImpl service = new CashWithdrawServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "alarmService", alarm);
        return service;
    }
}
