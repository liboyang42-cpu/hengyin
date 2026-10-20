package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.WxBillImport;
import com.chengyinhub.business.domain.WxTradeBillDetail;
import com.chengyinhub.business.domain.WxTradeBillSnapshot;
import com.chengyinhub.business.domain.WxFundFlowBillSnapshot;
import com.chengyinhub.business.mapper.WxBillImportMapper;
import com.chengyinhub.business.mapper.WxTradeBillDetailMapper;
import com.chengyinhub.business.mapper.WxTradeBillSnapshotMapper;
import com.chengyinhub.business.mapper.WxFundFlowBillSnapshotMapper;
import com.chengyinhub.business.service.IWxTradeBillService.BillUnavailableException;
import com.chengyinhub.common.config.WxPayConfig;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.service.billdownload.BillDownloadServiceExtension;
import com.wechat.pay.java.service.billdownload.DigestBillEntity;
import com.wechat.pay.java.service.billdownload.model.AccountType;
import com.wechat.pay.java.service.billdownload.model.GetFundFlowBillRequest;
import com.wechat.pay.java.service.billdownload.model.GetTradeBillRequest;
import com.wechat.pay.java.service.billdownload.model.HashType;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WxTradeBillServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String MERCHANT = "1234567890";
    private final LocalDate day = LocalDate.now(ZONE).minusDays(1);
    private final Date date = Date.from(day.atStartOfDay(ZONE).toInstant());
    private WxTradeBillServiceImpl service;
    private WxPayConfig config;
    private BillDownloadServiceExtension sdk;
    private WxTradeBillDetailMapper details;
    private WxTradeBillSnapshotMapper snapshots;
    private WxFundFlowBillSnapshotMapper funds;
    private WxBillImportMapper imports;
    private PlatformTransactionManager transactions;

    @BeforeEach
    void setUp() {
        service = new WxTradeBillServiceImpl(); config = mock(WxPayConfig.class);
        sdk = mock(BillDownloadServiceExtension.class); details = mock(WxTradeBillDetailMapper.class);
        snapshots = mock(WxTradeBillSnapshotMapper.class); funds = mock(WxFundFlowBillSnapshotMapper.class);
        imports = mock(WxBillImportMapper.class); transactions = mock(PlatformTransactionManager.class);
        ReflectionTestUtils.setField(service, "wxPayConfig", config);
        ReflectionTestUtils.setField(service, "billDownloadService", sdk);
        ReflectionTestUtils.setField(service, "detailMapper", details);
        ReflectionTestUtils.setField(service, "snapshotMapper", snapshots);
        ReflectionTestUtils.setField(service, "fundFlowMapper", funds);
        ReflectionTestUtils.setField(service, "importMapper", imports);
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        when(config.isWechatPayEnabled()).thenReturn(true); when(config.getMerchantId()).thenReturn(MERCHANT);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(details.insertDetail(any())).thenReturn(1); when(snapshots.insertSnapshot(any())).thenReturn(1);
        when(funds.insertSnapshot(any())).thenReturn(1);
        WxBillImport[] saved = new WxBillImport[1];
        when(imports.upsertImport(any())).thenAnswer(call -> { saved[0] = call.getArgument(0); return 1; });
        when(imports.selectImport(anyString(), anyString(), any())).thenAnswer(call -> saved[0]);
    }

    @Test
    void officialTotalAndRequestedRefundStaySeparateFromSettlementAndPendingRefundIsNotSuccess() throws Exception {
        when(sdk.getTradeBill(any())).thenReturn(bill(trade("PROCESSING")));
        assertEquals(2, service.downloadAndIngestBill(date));
        ArgumentCaptor<WxTradeBillDetail> raw = ArgumentCaptor.forClass(WxTradeBillDetail.class);
        verify(details, times(2)).insertDetail(raw.capture());
        assertEquals(new BigDecimal("10.00"), raw.getAllValues().get(0).getOrderAmount());
        assertEquals(new BigDecimal("8.00"), raw.getAllValues().get(0).getSettlementAmount());
        assertEquals(new BigDecimal("6.00"), raw.getAllValues().get(1).getRequestedRefundAmount());
        assertEquals(new BigDecimal("5.00"), raw.getAllValues().get(1).getSettlementRefundAmount());
        ArgumentCaptor<WxTradeBillSnapshot> successful = ArgumentCaptor.forClass(WxTradeBillSnapshot.class);
        verify(snapshots).insertSnapshot(successful.capture());
        assertEquals(WxTradeBillSnapshot.BIZ_TYPE_PAYMENT, successful.getValue().getBizType());
        assertEquals(new BigDecimal("10.00"), successful.getValue().getAmount());
        ArgumentCaptor<GetTradeBillRequest> request = ArgumentCaptor.forClass(GetTradeBillRequest.class);
        verify(sdk).getTradeBill(request.capture());
        assertEquals(day.toString(), request.getValue().getBillDate());
        assertNull(request.getValue().getTarType());
        verify(transactions).commit(any());
    }

    @Test
    void successfulRefundUsesRequestedRefundTotalAndOfficialRefundIdentity() throws Exception {
        when(sdk.getTradeBill(any())).thenReturn(bill(trade("SUCCESS")));
        service.downloadAndIngestBill(date);
        ArgumentCaptor<WxTradeBillSnapshot> rows = ArgumentCaptor.forClass(WxTradeBillSnapshot.class);
        verify(snapshots, times(2)).insertSnapshot(rows.capture());
        WxTradeBillSnapshot refund = rows.getAllValues().get(1);
        assertEquals("refund1", refund.getWxTradeNo()); assertEquals("RF1", refund.getOutRefundNo());
        assertEquals(WxTradeBillSnapshot.BIZ_TYPE_REFUND, refund.getBizType());
        assertEquals(new BigDecimal("6.00"), refund.getAmount());
    }

    @Test
    void invalidDigestOrTruncatedCsvCannotStartDatabaseTransaction() throws Exception {
        when(sdk.getTradeBill(any())).thenReturn(new DigestBillEntity(
                new ByteArrayInputStream(trade("SUCCESS").getBytes(StandardCharsets.UTF_8)), "0".repeat(40), HashType.SHA1));
        assertThrows(IllegalStateException.class, () -> service.downloadAndIngestBill(date));
        when(sdk.getTradeBill(any())).thenReturn(bill(trade("SUCCESS").split("总交易单数")[0]));
        assertThrows(IllegalArgumentException.class, () -> service.downloadAndIngestBill(date));
        verifyNoInteractions(details, snapshots, funds, imports, transactions);
    }

    @Test
    void matchingDuplicateIsIdempotentButChangedFactsRollbackWholeBatch() throws Exception {
        when(sdk.getTradeBill(any())).thenAnswer(call -> bill(trade("PROCESSING")));
        when(details.insertDetail(any())).thenThrow(new DuplicateKeyException("already imported"));
        when(details.countMatchingDetail(any())).thenReturn(1);
        assertEquals(0, service.downloadAndIngestBill(date));
        verify(transactions).commit(any());
        clearInvocations(transactions, imports, snapshots);
        when(details.countMatchingDetail(any())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.downloadAndIngestBill(date));
        verify(transactions).rollback(any()); verify(transactions, never()).commit(any());
        verifyNoInteractions(imports, snapshots);
    }

    @Test
    void latePersistenceFailureDoesNotCommitTheSuccessfulBatchMarker() throws Exception {
        when(sdk.getTradeBill(any())).thenReturn(bill(trade("SUCCESS")));
        when(snapshots.insertSnapshot(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> service.downloadAndIngestBill(date));
        verify(transactions).rollback(any()); verify(transactions, never()).commit(any());
        verifyNoInteractions(imports);
    }

    @Test
    void alreadySuccessfulDifferentFileHashCannotCommitAUnionOfTwoDifferentStatements() throws Exception {
        when(sdk.getTradeBill(any())).thenReturn(bill(trade("SUCCESS")));
        WxBillImport prior = new WxBillImport(); prior.setStatus("SUCCESS");
        prior.setSourceHash("b".repeat(40)); prior.setRowCount(2);
        when(imports.selectImport(anyString(), anyString(), any())).thenReturn(prior);
        assertThrows(IllegalStateException.class, () -> service.downloadAndIngestBill(date));
        verify(transactions).rollback(any()); verify(transactions, never()).commit(any());
    }

    @Test
    void sqlDateKeepsItsCalendarDayInNewZealandAndDuplicateSnapshotReplayMatches() throws Exception {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Auckland"));
            when(sdk.getTradeBill(any())).thenReturn(bill(trade("PROCESSING")));
            when(details.insertDetail(any())).thenThrow(new DuplicateKeyException("replay"));
            when(details.countMatchingDetail(any())).thenReturn(1);
            when(snapshots.insertSnapshot(any())).thenThrow(new DuplicateKeyException("replay"));
            WxTradeBillSnapshot prior = new WxTradeBillSnapshot(); prior.setBillDate(java.sql.Date.valueOf(day));
            prior.setTradeTime(Date.from(day.atTime(10, 0).atZone(ZONE).toInstant()));
            prior.setWxTradeNo("pay1"); prior.setOutTradeNo("REG1");
            prior.setBizType(WxTradeBillSnapshot.BIZ_TYPE_PAYMENT); prior.setAmount(new BigDecimal("10.00"));
            when(snapshots.selectByOutTradeNo("REG1")).thenReturn(List.of(prior));
            assertEquals(0, service.downloadAndIngestBill(java.sql.Date.valueOf(day)));
            ArgumentCaptor<GetTradeBillRequest> request = ArgumentCaptor.forClass(GetTradeBillRequest.class);
            verify(sdk).getTradeBill(request.capture()); assertEquals(day.toString(), request.getValue().getBillDate());
        } finally { java.util.TimeZone.setDefault(original); }
    }

    @Test
    void allThreeFundAccountsRemainIndependentAndOnlyReportActualNetAndBalances() throws Exception {
        when(sdk.getFundFlowBill(any())).thenAnswer(call -> bill(fund("8.00", "09:00:01")));
        for (String type : List.of("BASIC", "OPERATION", "FEES")) service.downloadAndIngestFundFlowBill(date, type);
        ArgumentCaptor<GetFundFlowBillRequest> requests = ArgumentCaptor.forClass(GetFundFlowBillRequest.class);
        verify(sdk, times(3)).getFundFlowBill(requests.capture());
        assertEquals(List.of(AccountType.BASIC, AccountType.OPERATION, AccountType.FEES),
                requests.getAllValues().stream().map(GetFundFlowBillRequest::getAccountType).toList());
        ArgumentCaptor<WxFundFlowBillSnapshot> rows = ArgumentCaptor.forClass(WxFundFlowBillSnapshot.class);
        verify(funds, times(6)).insertSnapshot(rows.capture());
        assertEquals(List.of("BASIC", "BASIC", "OPERATION", "OPERATION", "FEES", "FEES"),
                rows.getAllValues().stream().map(WxFundFlowBillSnapshot::getAccountType).toList());
        assertEquals("新业务分类", rows.getValue().getBusinessType());
        ArgumentCaptor<WxBillImport> batch = ArgumentCaptor.forClass(WxBillImport.class);
        verify(imports, times(3)).upsertImport(batch.capture());
        assertEquals(new BigDecimal("8.00"), batch.getValue().getNetAmount());
        assertEquals("UNVERIFIED", batch.getValue().getBalanceStatus());
        assertTrue(batch.getValue().getReviewReason().contains("期初"));
        verifyNoInteractions(details, snapshots);
    }

    @Test
    void provableBalanceGapAndSameSecondAmbiguityArePreservedRatherThanCorrected() throws Exception {
        when(sdk.getFundFlowBill(any())).thenReturn(bill(fund("9.00", "09:00:01")));
        service.downloadAndIngestFundFlowBill(date, "BASIC");
        ArgumentCaptor<WxBillImport> batch = ArgumentCaptor.forClass(WxBillImport.class);
        verify(imports).upsertImport(batch.capture()); assertEquals("GAP", batch.getValue().getBalanceStatus());
        clearInvocations(imports);
        when(sdk.getFundFlowBill(any())).thenReturn(bill(fund("9.00", "09:00:00")));
        service.downloadAndIngestFundFlowBill(date, "BASIC");
        verify(imports).upsertImport(batch.capture());
        assertEquals("UNVERIFIED", batch.getValue().getBalanceStatus());
        assertTrue(batch.getValue().getReviewReason().contains("同秒"));
        verifyNoInteractions(snapshots);
    }

    @Test
    void missingAndCreatingStatementsProducePendingMarkersRatherThanEmptySuccess() {
        for (String code : List.of("NO_STATEMENT_EXIST", "STATEMENT_CREATING")) {
            ServiceException failure = new ServiceException(null, 400,
                    "{\"code\":\"" + code + "\",\"message\":\"statement pending\"}");
            doThrow(failure).when(sdk).getTradeBill(any());
            assertThrows(BillUnavailableException.class, () -> service.downloadAndIngestBill(date));
        }
        ArgumentCaptor<WxBillImport> batch = ArgumentCaptor.forClass(WxBillImport.class);
        verify(imports, times(2)).upsertImport(batch.capture());
        verify(sdk, times(2)).getTradeBill(any());
        assertEquals(List.of("NO_STATEMENT", "CREATING"), batch.getAllValues().stream().map(WxBillImport::getStatus).toList());
        assertTrue(batch.getAllValues().stream().allMatch(value -> value.getSourceHash() == null));
        verifyNoInteractions(details, snapshots, funds);
    }

    @Test
    void disabledChannelInvalidDateAndUnknownAccountCannotContactSdk() {
        when(config.isWechatPayEnabled()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> service.downloadAndIngestBill(date));
        assertThrows(IllegalArgumentException.class, () -> service.downloadAndIngestBill(null));
        assertThrows(IllegalArgumentException.class, () -> service.downloadAndIngestBill(new Date()));
        assertThrows(IllegalArgumentException.class, () -> service.downloadAndIngestFundFlowBill(date, null));
        assertThrows(IllegalArgumentException.class, () -> service.downloadAndIngestFundFlowBill(date, "OTHER"));
        verifyNoInteractions(sdk, details, snapshots, funds, imports, transactions);
    }

    private DigestBillEntity bill(String csv) throws Exception {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        return new DigestBillEntity(new ByteArrayInputStream(bytes),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)), HashType.SHA1);
    }

    private String trade(String refundStatus) {
        return "交易时间,商户号,微信订单号,商户订单号,交易状态,货币种类,应结订单金额,代金券金额,微信退款单号,商户退款单号,退款金额,充值券退款金额,退款状态,手续费,订单金额,申请退款金额\n"
                + day + " 10:00:00," + MERCHANT + ",pay1,REG1,SUCCESS,CNY,8.00,2.00,0,0,0,0,,0.10,10.00,0\n"
                + day + " 10:00:01," + MERCHANT + ",pay1,REG1,REFUND,CNY,0,0,refund1,RF1,5.00,1.00," + refundStatus + ",-0.05,0,6.00\n"
                + "总交易单数,应结订单总金额,退款总金额,充值券退款总金额,手续费总金额,订单总金额,申请退款总金额\n"
                + "2,8.00,5.00,1.00,0.05,10.00,6.00\n";
    }

    private String fund(String secondBalance, String secondTime) {
        return "记账时间,微信支付业务单号,资金流水单号,业务名称,业务类型,收支类型,收支金额(元),账户结余(元),资金变更提交申请人,备注,业务凭证号\n"
                + day + " 09:00:00,pay1,flow1,交易,交易,收入,10.00,10.00,system,,REG1\n"
                + day + " " + secondTime + ",pay1,flow2,交易,新业务分类,支出,2.00," + secondBalance + ",system,,REG1\n"
                + "资金流水总笔数,收入笔数,收入金额,支出笔数,支出金额\n2,1,10.00,1,2.00\n";
    }
}
