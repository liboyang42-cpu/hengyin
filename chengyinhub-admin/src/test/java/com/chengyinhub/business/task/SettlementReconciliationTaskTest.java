package com.chengyinhub.business.task;

import com.chengyinhub.business.domain.vo.SettlementBatchReconciliationVO;
import com.chengyinhub.business.mapper.SettlementBatchMapper;
import com.chengyinhub.business.service.impl.AlarmService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementReconciliationTaskTest {

    @Test
    void runPropagatesBatchQueryFailureInsteadOfReportingEmptyTable() {
        SettlementBatchMapper mapper = mock(SettlementBatchMapper.class);
        AlarmService alarmService = mock(AlarmService.class);
        IllegalStateException queryFailure = new IllegalStateException("settlement_batch query failed");
        when(mapper.countBatches()).thenThrow(queryFailure);
        SettlementReconciliationTask task = new SettlementReconciliationTask();
        ReflectionTestUtils.setField(task, "settlementBatchMapper", mapper);
        ReflectionTestUtils.setField(task, "alarmService", alarmService);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, task::run);

        assertSame(queryFailure, thrown);
        verifyNoInteractions(alarmService);
    }

    @Test
    void runAlertsWhenSettlementBatchTableIsEmpty() {
        SettlementBatchMapper mapper = mock(SettlementBatchMapper.class);
        AlarmService alarmService = mock(AlarmService.class);
        when(mapper.countBatches()).thenReturn(0L);
        SettlementReconciliationTask task = new SettlementReconciliationTask();
        ReflectionTestUtils.setField(task, "settlementBatchMapper", mapper);
        ReflectionTestUtils.setField(task, "alarmService", alarmService);

        assertThrows(IllegalStateException.class, task::run);

        verify(alarmService).alarm(eq("结算批次未接线"), contains("settlement_batch=0"));
    }

    @Test
    void runDoesNotAlertWhenAllBatchAmountsMatch() {
        SettlementBatchMapper mapper = mock(SettlementBatchMapper.class);
        AlarmService alarmService = mock(AlarmService.class);
        when(mapper.countBatches()).thenReturn(1L);
        when(mapper.selectAmountMismatchBatches()).thenReturn(Collections.emptyList());
        SettlementReconciliationTask task = new SettlementReconciliationTask();
        ReflectionTestUtils.setField(task, "settlementBatchMapper", mapper);
        ReflectionTestUtils.setField(task, "alarmService", alarmService);

        task.run();

        verifyNoInteractions(alarmService);
    }

    @Test
    void runAlertsOnlyForBatchAmountMismatch() {
        SettlementBatchMapper mapper = mock(SettlementBatchMapper.class);
        AlarmService alarmService = mock(AlarmService.class);
        SettlementBatchReconciliationVO mismatch = new SettlementBatchReconciliationVO();
        mismatch.setBatchId(11L);
        mismatch.setMerchantId(7L);
        mismatch.setBatchTotalAmount(new BigDecimal("100.00"));
        mismatch.setDetailTotalAmount(new BigDecimal("98.00"));
        mismatch.setDifferenceAmount(new BigDecimal("2.00"));
        when(mapper.countBatches()).thenReturn(1L);
        when(mapper.selectAmountMismatchBatches()).thenReturn(Collections.singletonList(mismatch));

        SettlementReconciliationTask task = new SettlementReconciliationTask();
        ReflectionTestUtils.setField(task, "settlementBatchMapper", mapper);
        ReflectionTestUtils.setField(task, "alarmService", alarmService);

        assertThrows(IllegalStateException.class, task::run);

        verify(alarmService).alarm(eq("结算批次金额不一致"), contains("batchId=11"));
        verify(alarmService).alarm(eq("结算批次金额不一致"), contains("difference=2.00"));
    }

    @Test
    void runKeepsUnknownAndZeroAmountsDistinctInAlarmDetail() {
        SettlementBatchMapper mapper = mock(SettlementBatchMapper.class);
        AlarmService alarmService = mock(AlarmService.class);
        SettlementBatchReconciliationVO mismatch = new SettlementBatchReconciliationVO();
        mismatch.setBatchId(12L);
        mismatch.setMerchantId(8L);
        mismatch.setBatchTotalAmount(null);
        mismatch.setDetailTotalAmount(BigDecimal.ZERO);
        mismatch.setDifferenceAmount(null);
        when(mapper.countBatches()).thenReturn(1L);
        when(mapper.selectAmountMismatchBatches()).thenReturn(Collections.singletonList(mismatch));

        SettlementReconciliationTask task = new SettlementReconciliationTask();
        ReflectionTestUtils.setField(task, "settlementBatchMapper", mapper);
        ReflectionTestUtils.setField(task, "alarmService", alarmService);

        assertThrows(IllegalStateException.class, task::run);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(alarmService).alarm(eq("结算批次金额不一致"), detail.capture());
        assertEquals("batchId=12 merchantId=8 batchAmount=UNKNOWN detailAmount=0 difference=UNKNOWN",
                detail.getValue());
    }
}
