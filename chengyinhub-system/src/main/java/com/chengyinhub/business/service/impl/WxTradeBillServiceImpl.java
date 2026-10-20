package com.chengyinhub.business.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.chengyinhub.business.domain.WxBillImport;
import com.chengyinhub.business.domain.WxTradeBillDetail;
import com.chengyinhub.business.domain.WxTradeBillSnapshot;
import com.chengyinhub.business.domain.WxFundFlowBillSnapshot;
import com.chengyinhub.business.mapper.WxBillImportMapper;
import com.chengyinhub.business.mapper.WxTradeBillDetailMapper;
import com.chengyinhub.business.mapper.WxTradeBillSnapshotMapper;
import com.chengyinhub.business.mapper.WxFundFlowBillSnapshotMapper;
import com.chengyinhub.business.service.IWxTradeBillService;
import com.chengyinhub.business.service.support.WxBillCsvParser;
import com.chengyinhub.common.config.WxPayConfig;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.service.billdownload.BillDownloadServiceExtension;
import com.wechat.pay.java.service.billdownload.DigestBillEntity;
import com.wechat.pay.java.service.billdownload.model.AccountType;
import com.wechat.pay.java.service.billdownload.model.BillType;
import com.wechat.pay.java.service.billdownload.model.GetFundFlowBillRequest;
import com.wechat.pay.java.service.billdownload.model.GetTradeBillRequest;

/** 微信官方账单下载：先校验完整文件，再原子导入；不修改业务订单或余额。 */
@Service
public class WxTradeBillServiceImpl implements IWxTradeBillService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    @Autowired private WxPayConfig wxPayConfig;
    @Autowired private WxTradeBillDetailMapper detailMapper;
    @Autowired private WxTradeBillSnapshotMapper snapshotMapper;
    @Autowired private WxFundFlowBillSnapshotMapper fundFlowMapper;
    @Autowired private WxBillImportMapper importMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    private BillDownloadServiceExtension billDownloadService;

    @Override
    public int downloadAndIngestBill(Date billDate) {
        Date day = billDate(billDate);
        GetTradeBillRequest request = new GetTradeBillRequest();
        request.setBillDate(java.time.Instant.ofEpochMilli(day.getTime()).atZone(ZONE).toLocalDate().toString());
        request.setBillType(BillType.ALL);
        // 不请求 GZIP：SDK 0.2.15 在解压后计算摘要，可能与压缩文件的官方摘要不一致。
        try (DownloadedFile file = verifiedFile(service().getTradeBill(request))) {
            List<WxBillCsvParser.TradeRow> rows;
            try (java.io.Reader reader = Files.newBufferedReader(file.path())) {
                rows = WxBillCsvParser.trade(reader, wxPayConfig.getMerchantId());
            }
            for (WxBillCsvParser.TradeRow row : rows) requireSameDay(day, row.tradeTime());
            return new TransactionTemplate(transactionManager).execute(status -> {
                int inserted = 0;
                for (WxBillCsvParser.TradeRow row : rows) {
                    WxTradeBillDetail detail = tradeDetail(day, row);
                    try {
                        if (detailMapper.insertDetail(detail) != 1) throw new IllegalStateException("交易明细写入未确认");
                        inserted++;
                    }
                    catch (DuplicateKeyException duplicate) {
                        if (detailMapper.countMatchingDetail(detail) != 1) throw conflictingFacts();
                    }
                    if ("SUCCESS".equals(row.tradeState()) || "SUCCESS".equals(row.refundStatus())) {
                        insertSuccessfulSnapshot(day, row);
                    }
                }
                WxBillImport batch = batch(day, "TRADE", "SUCCESS", file.hash(), rows.size());
                batch.setBalanceStatus("UNVERIFIED");
                batch.setReviewReason("交易总额与应结金额分存；资金余额需按独立账户账单核对");
                confirmSuccessfulBatch(batch);
                return inserted;
            });
        } catch (ServiceException failure) {
            throw pendingOrFailure(day, "TRADE", failure);
        } catch (IOException failure) {
            throw new IllegalStateException("微信交易账单文件读取或清理失败，导入结果需核对", failure);
        }
    }

    @Override
    public int downloadAndIngestFundFlowBill(Date billDate) {
        return downloadAndIngestFundFlowBill(billDate, "BASIC");
    }

    @Override
    public int downloadAndIngestFundFlowBill(Date billDate, String accountType) {
        Date day = billDate(billDate);
        if (accountType == null || !Set.of("BASIC", "OPERATION", "FEES").contains(accountType)) {
            throw new IllegalArgumentException("不支持的微信资金账户类型");
        }
        GetFundFlowBillRequest request = new GetFundFlowBillRequest();
        request.setBillDate(java.time.Instant.ofEpochMilli(day.getTime()).atZone(ZONE).toLocalDate().toString());
        request.setAccountType(AccountType.valueOf(accountType));
        try (DownloadedFile file = verifiedFile(service().getFundFlowBill(request))) {
            List<WxBillCsvParser.FundFlowRow> rows;
            try (java.io.Reader reader = Files.newBufferedReader(file.path())) {
                rows = WxBillCsvParser.fundFlow(reader);
            }
            for (WxBillCsvParser.FundFlowRow row : rows) requireSameDay(day, row.accountingTime());
            return new TransactionTemplate(transactionManager).execute(status -> {
                int inserted = 0;
                BigDecimal income = BigDecimal.ZERO, expense = BigDecimal.ZERO;
                for (WxBillCsvParser.FundFlowRow row : rows) {
                    WxFundFlowBillSnapshot snapshot = fundFlow(day, accountType, row);
                    try {
                        if (fundFlowMapper.insertSnapshot(snapshot) != 1) throw new IllegalStateException("资金明细写入未确认");
                        inserted++;
                    }
                    catch (DuplicateKeyException duplicate) {
                        if (fundFlowMapper.countMatchingSnapshot(snapshot) != 1) throw conflictingFacts();
                    }
                    if ("收入".equals(row.direction())) income = income.add(row.amount());
                    else expense = expense.add(row.amount());
                }
                WxBillImport batch = batch(day, accountType, "SUCCESS", file.hash(), rows.size());
                batch.setIncomeAmount(income); batch.setExpenseAmount(expense);
                batch.setNetAmount(income.subtract(expense));
                balanceContinuity(rows, batch);
                confirmSuccessfulBatch(batch);
                return inserted;
            });
        } catch (ServiceException failure) {
            throw pendingOrFailure(day, accountType, failure);
        } catch (IOException failure) {
            throw new IllegalStateException("微信资金账单文件读取或清理失败，导入结果需核对", failure);
        }
    }

    private synchronized BillDownloadServiceExtension service() {
        if (!wxPayConfig.isWechatPayEnabled()) throw new IllegalStateException("微信支付未启用，账单待核");
        if (wxPayConfig.getMerchantId() == null || wxPayConfig.getMerchantId().isBlank()) {
            throw new IllegalStateException("微信账单商户号未配置，账单待核");
        }
        if (billDownloadService == null) {
            billDownloadService = new BillDownloadServiceExtension.Builder().config(wxPayConfig.getConfig()).build();
        }
        return billDownloadService;
    }

    private DownloadedFile verifiedFile(DigestBillEntity bill) throws IOException {
        if (bill == null || bill.getInputStream() == null) throw new IllegalStateException("微信账单响应缺少文件");
        Path file = null;
        boolean verified = false;
        try {
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-1"); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            try (InputStream stream = bill.getInputStream()) {
                file = Files.createTempFile("wx-bill-", ".csv");
                Files.copy(new DigestInputStream(stream, digest), file, StandardCopyOption.REPLACE_EXISTING);
                if (!bill.verifyHash()) throw new IllegalStateException("微信账单摘要校验失败，未导入");
            }
            verified = true;
            return new DownloadedFile(file, HexFormat.of().formatHex(digest.digest()));
        } finally {
            if (!verified && file != null) Files.deleteIfExists(file);
        }
    }

    private record DownloadedFile(Path path, String hash) implements AutoCloseable {
        @Override public void close() throws IOException { Files.deleteIfExists(path); }
    }

    private RuntimeException pendingOrFailure(Date day, String type, ServiceException failure) {
        String code = failure.getErrorCode();
        if ("NO_STATEMENT_EXIST".equals(code) || "STATEMENT_CREATING".equals(code)) {
            WxBillImport batch = batch(day, type,
                    "NO_STATEMENT_EXIST".equals(code) ? "NO_STATEMENT" : "CREATING", null, 0);
            batch.setBalanceStatus("UNVERIFIED");
            batch.setReviewReason("微信账单不存在或仍在生成，待后续核对；不视为零交易证明");
            new TransactionTemplate(transactionManager).execute(status -> importMapper.upsertImport(batch));
            return new BillUnavailableException(type, code);
        }
        return new IllegalStateException("微信账单请求失败，未确认导入", failure);
    }

    private WxBillImport batch(Date day, String type, String status, String hash, int rows) {
        WxBillImport batch = new WxBillImport();
        batch.setBillDate(day); batch.setMerchantId(wxPayConfig.getMerchantId());
        batch.setBillType(type); batch.setStatus(status); batch.setSourceHash(hash); batch.setRowCount(rows);
        return batch;
    }

    private void confirmSuccessfulBatch(WxBillImport batch) {
        importMapper.upsertImport(batch);
        WxBillImport saved = importMapper.selectImport(batch.getMerchantId(), batch.getBillType(), batch.getBillDate());
        if (saved == null || !"SUCCESS".equals(saved.getStatus())
                || !batch.getSourceHash().equals(saved.getSourceHash())
                || !batch.getRowCount().equals(saved.getRowCount())) {
            throw new IllegalStateException("账单完成标记与当前已校验文件不一致，本次整批回滚待核");
        }
    }

    private static LocalDate calendarDate(Date value) {
        return value instanceof java.sql.Date sqlDate ? sqlDate.toLocalDate()
                : java.time.Instant.ofEpochMilli(value.getTime()).atZone(ZONE).toLocalDate();
    }

    private static Date billDate(Date value) {
        if (value == null) throw new IllegalArgumentException("账单日期不能为空");
        LocalDate day = calendarDate(value);
        LocalDate today = LocalDate.now(ZONE);
        if (!day.isBefore(today) || day.isBefore(today.minusMonths(3))) {
            throw new IllegalArgumentException("账单日期必须为三个月内已结束的北京时间自然日");
        }
        return Date.from(day.atStartOfDay(ZONE).toInstant());
    }

    private static void requireSameDay(Date day, Date time) {
        if (!java.time.Instant.ofEpochMilli(day.getTime()).atZone(ZONE).toLocalDate().equals(java.time.Instant.ofEpochMilli(time.getTime()).atZone(ZONE).toLocalDate())) {
            throw new IllegalArgumentException("账单明细日期不属于申请的账单日");
        }
    }

    private static IllegalStateException conflictingFacts() {
        return new IllegalStateException("同一微信流水已存在不同事实，本次整批回滚，待人工核对");
    }

    private WxTradeBillDetail tradeDetail(Date day, WxBillCsvParser.TradeRow row) {
        WxTradeBillDetail value = new WxTradeBillDetail();
        value.setBillDate(day); value.setMerchantId(row.merchantId()); value.setTradeTime(row.tradeTime());
        value.setTransactionId(row.transactionId()); value.setOutTradeNo(row.outTradeNo());
        value.setTradeState(row.tradeState()); value.setRefundId(row.refundId()); value.setOutRefundNo(row.outRefundNo());
        value.setRefundStatus(row.refundStatus()); value.setOrderAmount(row.orderAmount());
        value.setSettlementAmount(row.settlementAmount()); value.setCouponAmount(row.couponAmount());
        value.setRequestedRefundAmount(row.requestedRefundAmount()); value.setSettlementRefundAmount(row.settlementRefundAmount());
        value.setCouponRefundAmount(row.couponRefundAmount()); value.setFee(row.fee());
        value.setRecordKey("SUCCESS".equals(row.tradeState()) ? "PAY:" + row.transactionId() : "REFUND:" + row.refundId());
        return value;
    }

    private WxFundFlowBillSnapshot fundFlow(Date day, String type, WxBillCsvParser.FundFlowRow row) {
        WxFundFlowBillSnapshot value = new WxFundFlowBillSnapshot();
        value.setBillDate(day); value.setMerchantId(wxPayConfig.getMerchantId()); value.setAccountType(type);
        value.setAccountingTime(row.accountingTime()); value.setWxBusinessNo(row.wxBusinessNo());
        value.setFlowNo(row.flowNo()); value.setBusinessName(row.businessName()); value.setBusinessType(row.businessType());
        value.setDirection(row.direction()); value.setAmount(row.amount()); value.setBalance(row.balance());
        value.setInitiator(row.initiator()); value.setRemark(row.remark()); value.setVoucherNo(row.voucherNo());
        return value;
    }

    private void insertSuccessfulSnapshot(Date day, WxBillCsvParser.TradeRow row) {
        boolean payment = "SUCCESS".equals(row.tradeState());
        WxTradeBillSnapshot value = new WxTradeBillSnapshot();
        value.setBillDate(day); value.setTradeTime(row.tradeTime());
        value.setWxTradeNo(payment ? row.transactionId() : row.refundId());
        value.setOutTradeNo(row.outTradeNo()); value.setOutRefundNo(payment ? null : row.outRefundNo());
        value.setBizType(payment ? WxTradeBillSnapshot.BIZ_TYPE_PAYMENT : WxTradeBillSnapshot.BIZ_TYPE_REFUND);
        value.setAmount(payment ? row.orderAmount() : row.requestedRefundAmount());
        if (value.getAmount().signum() <= 0) throw new IllegalArgumentException("成功交易的订单或申请退款金额必须大于零");
        try {
            if (snapshotMapper.insertSnapshot(value) != 1) throw new IllegalStateException("成功快照写入未确认");
        }
        catch (DuplicateKeyException duplicate) {
            List<WxTradeBillSnapshot> previous = snapshotMapper.selectByOutTradeNo(value.getOutTradeNo());
            boolean same = previous != null && previous.stream().anyMatch(existing ->
                    value.getWxTradeNo().equals(existing.getWxTradeNo())
                    && value.getBizType().equals(existing.getBizType())
                    && existing.getAmount() != null && value.getAmount().compareTo(existing.getAmount()) == 0
                    && existing.getBillDate() != null && calendarDate(value.getBillDate()).equals(calendarDate(existing.getBillDate()))
                    && value.getTradeTime().equals(existing.getTradeTime())
                    && java.util.Objects.equals(value.getOutRefundNo(), existing.getOutRefundNo()));
            if (!same) throw conflictingFacts();
        }
    }

    private static void balanceContinuity(List<WxBillCsvParser.FundFlowRow> rows, WxBillImport batch) {
        batch.setBalanceStatus("UNVERIFIED");
        batch.setReviewReason("缺少已核期初余额，不能确认账户全量余额；未自动调账");
        if (rows.size() < 2) return;
        List<WxBillCsvParser.FundFlowRow> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(WxBillCsvParser.FundFlowRow::accountingTime));
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).accountingTime().equals(ordered.get(i - 1).accountingTime())) {
                batch.setReviewReason("同秒流水顺序无法确认且缺少期初余额，余额连续性待核");
                return;
            }
        }
        for (int i = 1; i < ordered.size(); i++) {
            WxBillCsvParser.FundFlowRow current = ordered.get(i);
            BigDecimal change = "收入".equals(current.direction()) ? current.amount() : current.amount().negate();
            if (ordered.get(i - 1).balance().add(change).compareTo(current.balance()) != 0) {
                batch.setBalanceStatus("GAP");
                batch.setReviewReason("同账户可排序流水的余额不连续，已保留原始事实，待人工核对；未自动调账");
                return;
            }
        }
        batch.setReviewReason("已核文件内相邻流水余额连续性；缺少已核期初余额，账户全量余额仍待核");
    }
}
