package com.chengyinhub.business.task;

import com.chengyinhub.business.domain.WxBillImport;
import com.chengyinhub.business.mapper.WxBillImportMapper;
import com.chengyinhub.business.service.IWxTradeBillService;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.common.config.WxPayConfig;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WxTradeBillDownloadTaskTest {
    private WxTradeBillDownloadTask task;
    private IWxTradeBillService downloads;
    private WxBillImportMapper imports;
    private AlarmService alarms;
    private com.chengyinhub.business.service.IFourLedgerReconcileService reconcile;

    @BeforeEach
    void setUp() {
        task = new WxTradeBillDownloadTask(); downloads = mock(IWxTradeBillService.class);
        imports = mock(WxBillImportMapper.class); alarms = mock(AlarmService.class);
        WxPayConfig config = mock(WxPayConfig.class); when(config.getMerchantId()).thenReturn("1234567890");
        ReflectionTestUtils.setField(task, "wxTradeBillService", downloads);
        ReflectionTestUtils.setField(task, "importMapper", imports);
        ReflectionTestUtils.setField(task, "wxPayConfig", config);
        ReflectionTestUtils.setField(task, "alarmService", alarms);
        reconcile = mock(com.chengyinhub.business.service.IFourLedgerReconcileService.class);
        ReflectionTestUtils.setField(task, "reconcileService", reconcile);
        when(reconcile.reconcileWechatBillAgainstRegistrations(any())).thenReturn(java.util.Collections.emptyList());
        when(imports.selectImport(eq("1234567890"), anyString(), any())).thenAnswer(call -> {
            WxBillImport value = new WxBillImport(); value.setStatus("SUCCESS");
            value.setBalanceStatus("UNVERIFIED"); value.setRowCount(0); value.setSourceHash("a".repeat(40));
            return value;
        });
    }

    @Test
    void downloadsYesterdayInShanghaiAndAllThreeAccountsEvenWhenHostUsesUsTime() {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"));
            task.download();
        } finally { java.util.TimeZone.setDefault(original); }
        ArgumentCaptor<Date> date = ArgumentCaptor.forClass(Date.class);
        verify(downloads).downloadAndIngestBill(date.capture());
        assertEquals(Date.from(LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1)
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()), date.getValue());
        for (String type : List.of("BASIC", "OPERATION", "FEES")) {
            verify(downloads).downloadAndIngestFundFlowBill(date.getValue(), type);
        }
        verifyNoInteractions(alarms);
    }

    @Test
    void missingOrCreatingTradeBillStaysPendingButDoesNotStarveFundAccounts() {
        when(downloads.downloadAndIngestBill(any())).thenThrow(
                new IWxTradeBillService.BillUnavailableException("TRADE", "STATEMENT_CREATING"));
        assertThrows(IllegalStateException.class, task::download);
        for (String type : List.of("BASIC", "OPERATION", "FEES")) verify(downloads).downloadAndIngestFundFlowBill(any(), eq(type));
        verify(imports, never()).selectImport(anyString(), eq("TRADE"), any());
        verifyNoInteractions(alarms);
    }

    @Test
    void completeTradeImportActuallyReconcilesAndReportsDifferencesWithoutStarvingFundImports() {
        when(reconcile.reconcileWechatBillAgainstRegistrations(any())).thenReturn(
                List.of(mock(com.chengyinhub.business.domain.LedgerDiscrepancy.class)));
        assertThrows(IllegalStateException.class, task::download);
        verify(reconcile).reconcileWechatBillAgainstRegistrations(any());
        verify(alarms).alarmThrottled(eq("payment.bill.trade.difference"), anyString(), contains("differenceCount=1"));
        for (String type : List.of("BASIC", "OPERATION", "FEES")) verify(downloads).downloadAndIngestFundFlowBill(any(), eq(type));
    }

    @Test
    void failedDownloadAndAlarmFailureStillAllowLaterAccountsAndEndAsFailure() {
        when(downloads.downloadAndIngestFundFlowBill(any(), eq("BASIC"))).thenThrow(new IllegalStateException("provider unavailable"));
        doThrow(new IllegalStateException("alarm unavailable")).when(alarms).alarmThrottled(anyString(), anyString(), anyString());
        assertThrows(IllegalStateException.class, task::download);
        verify(downloads).downloadAndIngestFundFlowBill(any(), eq("OPERATION"));
        verify(downloads).downloadAndIngestFundFlowBill(any(), eq("FEES"));
        ArgumentCaptor<String> events = ArgumentCaptor.forClass(String.class);
        verify(alarms).alarmThrottled(events.capture(), anyString(), anyString());
        assertEquals("payment.bill.download.basic", events.getValue());
        assertTrue(events.getValue().matches("[a-z][a-z0-9_.-]{0,79}"), "must satisfy the real AlarmService event contract");
    }

    @Test
    void missingSuccessMarkerAndProvableBalanceGapCannotBecomeACompleteSuccessfulRun() {
        when(imports.selectImport(anyString(), eq("TRADE"), any())).thenReturn(null);
        assertThrows(IllegalStateException.class, task::download);
        WxBillImport complete = new WxBillImport(); complete.setStatus("SUCCESS"); complete.setBalanceStatus("UNVERIFIED");
        complete.setSourceHash("a".repeat(40)); complete.setRowCount(0);
        when(imports.selectImport(anyString(), eq("TRADE"), any())).thenReturn(complete);
        WxBillImport gap = new WxBillImport(); gap.setStatus("SUCCESS"); gap.setBalanceStatus("GAP");
        gap.setSourceHash("a".repeat(40)); gap.setRowCount(0);
        when(imports.selectImport(anyString(), eq("BASIC"), any())).thenReturn(gap);
        IllegalStateException failure = assertThrows(IllegalStateException.class, task::download);
        assertTrue(failure.getMessage().contains("1 类待核"));
        verify(alarms).alarmThrottled(eq("payment.bill.balance.basic"), anyString(), anyString());
    }
}
