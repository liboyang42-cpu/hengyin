package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Official bill facts stay distinct; malformed downloads must never become a partial success. */
class WxBillCsvParserTest {
    private static final String MERCHANT = "1900000109";
    private static final List<String> TRADE_HEADER = List.of("交易时间", "商户号", "微信订单号", "商户订单号",
            "交易状态", "货币种类", "应结订单金额", "代金券金额", "微信退款单号", "商户退款单号",
            "退款金额", "充值券退款金额", "退款状态", "手续费", "订单金额", "申请退款金额");
    private static final List<String> TRADE_TOTAL_HEADER = List.of("总交易单数", "订单总金额", "应结订单总金额",
            "申请退款总金额", "退款总金额", "充值券退款总金额", "手续费总金额");
    private static final List<String> FUND_HEADER = List.of("记账时间", "微信支付业务单号", "资金流水单号",
            "业务名称", "业务类型", "收支类型", "收支金额(元)", "账户结余(元)", "资金变更提交申请人", "备注", "业务凭证号");
    private static final List<String> FUND_TOTAL_HEADER = List.of("资金流水总笔数", "收入笔数", "支出笔数", "收入金额", "支出金额");

    @Test
    void paymentAndSuccessfulPartialRefundKeepGrossSettlementCouponAndFeesSeparate() throws IOException {
        List<WxBillCsvParser.TradeRow> rows = trade(tradeBill(List.of(payment(), refund("SUCCESS")),
                List.of("2", "25.60", "22.60", "5.70", "4.20", "1.50", "0.07")));
        assertEquals(2, rows.size());
        WxBillCsvParser.TradeRow pay = rows.get(0), refund = rows.get(1);
        assertEquals("SUCCESS", pay.tradeState());
        assertEquals("REFUND", refund.tradeState());
        assertEquals("SUCCESS", refund.refundStatus());
        assertEquals(pay.transactionId(), refund.transactionId(), "A payment and its refund are different bill facts");
        assertEquals("refund-wx-1", refund.refundId());
        assertEquals("refund-local-1", refund.outRefundNo());
        assertEquals(new BigDecimal("19.90"), pay.orderAmount());
        assertEquals(new BigDecimal("18.40"), pay.settlementAmount());
        assertEquals(new BigDecimal("1.50"), pay.couponAmount());
        assertEquals(new BigDecimal("5.70"), refund.requestedRefundAmount());
        assertEquals(new BigDecimal("4.20"), refund.settlementRefundAmount());
        assertEquals(new BigDecimal("1.50"), refund.couponRefundAmount());
        assertEquals(new BigDecimal("-0.03"), refund.fee());
    }

    @Test
    void nonSuccessfulRefundStatesArePreservedAndNeverPromotedToSuccess() throws IOException {
        for (String state : List.of("PROCESSING", "FAIL", "CHANGE")) {
            WxBillCsvParser.TradeRow row = trade(tradeBill(List.of(refund(state)),
                    List.of("1", "5.70", "4.20", "5.70", "4.20", "1.50", "-0.03"))).get(0);
            assertEquals(state, row.refundStatus());
            assertEquals("REFUND", row.tradeState());
        }
    }

    @Test
    void incomeExpenseAndUnknownBusinessTypesRemainExternalFactsNotAnInferredBalance() throws IOException {
        List<WxBillCsvParser.FundFlowRow> rows = funds(fundBill(List.of(income(), expense()),
                List.of("2", "1", "1", "100.10", "0.03")));
        assertEquals("收入", rows.get(0).direction());
        assertEquals("支出", rows.get(1).direction());
        assertEquals("未来业务类型/不可推断", rows.get(1).businessType());
        BigDecimal net = rows.get(0).amount().subtract(rows.get(1).amount());
        assertEquals(new BigDecimal("100.07"), net);
        assertEquals(new BigDecimal("800.07"), rows.get(1).balance(),
                "Ending balance is supplied by WeChat; the bill does not prove the opening balance");
        assertEquals("wx-fund-2", rows.get(1).wxBusinessNo());
        assertEquals("voucher-2", rows.get(1).voucherNo());
    }

    @Test
    void fundBalanceMayBeNegativeWithoutMakingAnExpenseAmountNegative() throws IOException {
        List<String> row = with(expense(), 7, "-0.01");
        WxBillCsvParser.FundFlowRow result = funds(fundBill(List.of(row),
                List.of("1", "0", "1", "0.00", "0.03"))).get(0);
        assertEquals(new BigDecimal("0.03"), result.amount());
        assertEquals(new BigDecimal("-0.01"), result.balance());
    }

    @Test
    void bomCrLfAndWechatBacktickPrefixesDoNotAlterIdentifiersOrDecimalPrecision() throws IOException {
        List<String> row = new ArrayList<>(payment());
        for (int index : List.of(1, 2, 3, 6, 7, 10, 11, 13, 14, 15)) row.set(index, "`" + row.get(index));
        String body = "\uFEFF" + tradeBill(List.of(row), paymentTotal()).replace("\n", "\r\n");
        WxBillCsvParser.TradeRow result = trade(body).get(0);
        assertEquals(MERCHANT, result.merchantId());
        assertEquals("wx-pay-1", result.transactionId());
        assertEquals("local-pay-1", result.outTradeNo());
        assertEquals(new BigDecimal("19.90"), result.orderAmount());
    }

    @Test
    void quotedCommaEscapedQuoteAndEmbeddedNewlineAreOneFundRecord() throws IOException {
        String remark = "站点,\"A\"\n次日复核";
        String body = fundBill(List.of(with(income(), 9, remark)), List.of("1", "1", "0", "100.10", "0.00"));
        WxBillCsvParser.FundFlowRow row = funds(body).get(0);
        assertEquals(remark, row.remark());
        assertEquals("提交人甲", row.initiator());
        assertEquals(remark, funds(body.replace("\"站点,", "`\"站点,")).get(0).remark(),
                "WeChat backtick before a quoted value is a prefix, not part of the remark");
    }

    @Test
    void rawBusinessTypeRemarksAndIdentifiersRetainTheirOriginalWhitespace() throws IOException {
        String businessType = " 未来业务类型 ";
        String remark = "  原始备注\n保留尾空白  ";
        List<String> row = with(with(with(income(), 4, businessType), 9, remark), 2, "flow-1 ");
        WxBillCsvParser.FundFlowRow result = funds(fundBill(List.of(row),
                List.of("1", "1", "0", "100.10", "0"))).get(0);
        assertEquals(businessType, result.businessType());
        assertEquals(remark, result.remark());
        assertEquals("flow-1 ", result.flowNo());
    }

    @Test
    void reorderedHeadersAndAdditionalOfficialColumnsDoNotShiftFinancialFields() throws IOException {
        List<String> header = new ArrayList<>(TRADE_HEADER), row = new ArrayList<>(payment());
        header.add("商品名称"); row.add("夜行,路线");
        java.util.Collections.rotate(header, 5); java.util.Collections.rotate(row, 5);
        List<WxBillCsvParser.TradeRow> rows = trade(csv(header) + csv(row)
                + csv(TRADE_TOTAL_HEADER) + csv(paymentTotal()));
        assertEquals(new BigDecimal("19.90"), rows.get(0).orderAmount());
        assertEquals(new BigDecimal("18.40"), rows.get(0).settlementAmount());
    }

    @Test
    void timestampsUseShanghaiRegardlessOfHostTimezoneAndRejectInvalidCalendarDates() throws IOException {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            assertEquals(Instant.parse("2026-10-15T01:02:03Z"),
                    trade(tradeBill(List.of(payment()), paymentTotal())).get(0).tradeTime().toInstant());
            assertEquals(Instant.parse("2026-10-15T01:02:03Z"),
                    funds(fundBill(List.of(income()), List.of("1", "1", "0", "100.10", "0.00")))
                            .get(0).accountingTime().toInstant());
        } finally { TimeZone.setDefault(original); }
        assertThrows(IllegalArgumentException.class, () -> trade(tradeBill(
                List.of(with(payment(), 0, "2026-02-30 09:02:03")), paymentTotal())));
        assertThrows(IllegalArgumentException.class, () -> trade(tradeBill(
                List.of(with(payment(), 0, "2026-10-15 24:00:00")), paymentTotal())));
    }

    @Test
    void anotherMerchantOrNonCnyCurrencyCannotBeReconciledIntoThisMerchant() {
        for (List<String> row : List.of(with(payment(), 1, "another-merchant"), with(payment(), 5, "USD"))) {
            assertThrows(IllegalArgumentException.class, () -> trade(tradeBill(List.of(row), paymentTotal())));
        }
    }

    @Test
    void financialValuesRejectExponentsSubCentPrecisionNegativePrincipalAndNonNumbers() {
        for (String invalid : List.of("1e1", "19.901", "-19.90", "NaN", "Infinity", "", "true")) {
            assertThrows(IllegalArgumentException.class,
                    () -> trade(tradeBill(List.of(with(payment(), 14, invalid)), paymentTotal())), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> funds(fundBill(
                List.of(with(expense(), 6, "-0.03")), List.of("1", "0", "1", "0.00", "0.03"))));
    }

    @Test
    void oneCentAmountsAreAddedExactlyWithoutFloatingPointRounding() throws IOException {
        List<String> one = with(with(with(with(payment(), 14, "0.10"), 6, "0.10"), 7, "0"), 13, "0");
        List<String> two = with(with(with(with(one, 2, "wx-pay-2"), 3, "local-pay-2"), 14, "0.20"), 6, "0.20");
        assertEquals(2, trade(tradeBill(List.of(one, two),
                List.of("2", "0.30", "0.30", "0", "0", "0", "0"))).size());
    }

    @Test
    void duplicatePaymentRefundOrFundFlowIdsAreRejectedEvenWithMatchingTotals() {
        assertThrows(IllegalArgumentException.class, () -> trade(tradeBill(List.of(payment(), payment()),
                List.of("2", "39.80", "36.80", "0", "0", "0", "0.20"))));
        assertThrows(IllegalArgumentException.class, () -> trade(tradeBill(List.of(refund("SUCCESS"), refund("SUCCESS")),
                List.of("2", "11.40", "8.40", "11.40", "8.40", "3.00", "-0.06"))));
        assertThrows(IllegalArgumentException.class, () -> funds(fundBill(List.of(income(), income()),
                List.of("2", "2", "0", "200.20", "0"))));
    }

    @Test
    void refundRequiresBothExternalAndMerchantRefundIdentitiesAndAKnownStatus() {
        for (List<String> row : List.of(with(refund("SUCCESS"), 8, ""),
                with(refund("SUCCESS"), 9, "0"), with(refund("SUCCESS"), 12, "UNKNOWN"))) {
            assertThrows(IllegalArgumentException.class, () -> trade(tradeBill(List.of(row),
                    List.of("1", "5.70", "4.20", "5.70", "4.20", "1.50", "-0.03"))));
        }
        assertThrows(IllegalArgumentException.class, () -> trade(tradeBill(
                List.of(with(payment(), 4, "PENDING")), paymentTotal())));
        assertThrows(IllegalArgumentException.class, () -> funds(fundBill(
                List.of(with(income(), 5, "TRANSFER")), List.of("1", "1", "0", "100.10", "0"))));
    }

    @Test
    void missingDuplicateOrEmptyHeadersAndWrongDetailWidthsCannotBecomeSuccess() {
        List<String> missing = new ArrayList<>(TRADE_HEADER); missing.remove(14);
        List<String> duplicate = with(TRADE_HEADER, 14, "应结订单金额");
        for (List<String> header : List.of(missing, duplicate, with(TRADE_HEADER, 14, ""))) {
            assertThrows(IllegalArgumentException.class,
                    () -> trade(csv(header) + csv(payment()) + csv(TRADE_TOTAL_HEADER) + csv(paymentTotal())));
        }
        List<String> shortRow = new ArrayList<>(payment()); shortRow.remove(15);
        List<String> extraRow = new ArrayList<>(payment()); extraRow.add("unexpected");
        for (List<String> row : List.of(shortRow, extraRow)) {
            assertThrows(IllegalArgumentException.class, () -> trade(tradeBill(List.of(row), paymentTotal())));
        }
        for (List<String> header : List.of(with(FUND_HEADER, 6, "未知金额字段"), with(FUND_HEADER, 6, "账户结余(元)"))) {
            assertThrows(IllegalArgumentException.class, () -> funds(csv(header) + csv(income())
                    + csv(FUND_TOTAL_HEADER) + csv(List.of("1", "1", "0", "100.10", "0"))));
        }
        List<String> shortFund = new ArrayList<>(income()); shortFund.remove(10);
        assertThrows(IllegalArgumentException.class, () -> funds(fundBill(List.of(shortFund),
                List.of("1", "1", "0", "100.10", "0"))));
    }

    @Test
    void eachTradeFooterCountOrAmountMustMatchItsOwnColumn() {
        for (int i = 0; i < paymentTotal().size(); i++) {
            int index = i;
            assertThrows(IllegalArgumentException.class, () -> trade(tradeBill(List.of(payment()),
                    with(paymentTotal(), index, index == 0 ? "2" : "999.99"))), "summary column " + index);
        }
        List<String> header = new ArrayList<>(TRADE_TOTAL_HEADER); header.remove(4);
        assertThrows(IllegalArgumentException.class,
                () -> trade(csv(TRADE_HEADER) + csv(payment()) + csv(header) + csv(paymentTotal())));
    }

    @Test
    void fundFooterCountsAndIncomeExpenseAmountsAreCheckedIndependently() {
        List<String> totals = List.of("2", "1", "1", "100.10", "0.03");
        for (int i = 0; i < totals.size(); i++) {
            int index = i;
            assertThrows(IllegalArgumentException.class, () -> funds(fundBill(List.of(income(), expense()),
                    with(totals, index, index < 3 ? "9" : "999.99"))), "summary column " + index);
        }
    }

    @Test
    void truncatedDownloadMalformedLaterRowOrTrailingDataNeverReturnsEarlierGoodRows() {
        String prefix = csv(TRADE_HEADER) + csv(payment());
        for (String broken : List.of(prefix, prefix + csv(TRADE_TOTAL_HEADER),
                prefix + "\"unterminated\n", tradeBill(List.of(payment()), paymentTotal()) + csv(payment()),
                prefix + csv(with(payment(), 2, "")) + csv(TRADE_TOTAL_HEADER) + csv(paymentTotal()))) {
            assertThrows(IllegalArgumentException.class, () -> trade(broken));
        }
    }

    @Test
    void quoteInTheMiddleOfAnUnquotedFieldIsRejectedInsteadOfSilentlyChangingTheCsvMeaning() {
        for (String malformed : List.of("备注\"截断", "\"备注\"suffix")) {
            String body = fundBill(List.of(income()), List.of("1", "1", "0", "100.10", "0"))
                    .replace("普通备注", malformed);
            assertThrows(IllegalArgumentException.class, () -> funds(body));
        }
    }

    @Test
    void readerIoFailureIsNotReportedAsAnEmptyOrPartlyParsedBill() {
        Reader failedDownload = new Reader() {
            private final StringReader prefix = new StringReader(csv(TRADE_HEADER) + csv(payment()));
            @Override public int read(char[] buffer, int offset, int length) throws IOException {
                int read = prefix.read(buffer, offset, length);
                if (read < 0) throw new IOException("download interrupted");
                return read;
            }
            @Override public void close() { prefix.close(); }
        };
        assertThrows(IOException.class, () -> WxBillCsvParser.trade(failedDownload, MERCHANT));
    }

    @Test
    void emptyBillIsValidOnlyWithAnExplicitMatchingZeroSummary() throws IOException {
        assertEquals(List.of(), trade(tradeBill(List.of(), List.of("0", "0", "0", "0", "0", "0", "0"))));
        assertEquals(List.of(), funds(fundBill(List.of(), List.of("0", "0", "0", "0", "0"))));
        assertThrows(IllegalArgumentException.class, () -> trade(""));
        assertThrows(IllegalArgumentException.class, () -> funds(csv(FUND_HEADER)));
    }

    private static List<String> payment() {
        return List.of("2026-10-15 09:02:03", MERCHANT, "wx-pay-1", "local-pay-1", "SUCCESS", "CNY",
                "18.40", "1.50", "", "", "0", "0", "", "0.10", "19.90", "0");
    }

    private static List<String> paymentTotal() { return List.of("1", "19.90", "18.40", "0", "0", "0", "0.10"); }

    private static List<String> refund(String status) {
        return List.of("2026-10-15 09:03:04", MERCHANT, "wx-pay-1", "local-pay-1", "REFUND", "CNY",
                "4.20", "1.50", "refund-wx-1", "refund-local-1", "4.20", "1.50", status, "-0.03", "5.70", "5.70");
    }

    private static List<String> income() {
        return List.of("2026-10-15 09:02:03", "wx-fund-1", "flow-1", "交易入账", "交易", "收入",
                "100.10", "800.10", "提交人甲", "普通备注", "voucher-1");
    }

    private static List<String> expense() {
        return List.of("2026-10-15 09:03:04", "wx-fund-2", "flow-2", "调整", "未来业务类型/不可推断", "支出",
                "0.03", "800.07", "提交人乙", "仅保留外部事实", "voucher-2");
    }

    private static List<String> with(List<String> cells, int index, String value) {
        List<String> copy = new ArrayList<>(cells); copy.set(index, value); return copy;
    }

    private static String tradeBill(List<List<String>> rows, List<String> total) {
        return bill(TRADE_HEADER, rows, TRADE_TOTAL_HEADER, total);
    }

    private static String fundBill(List<List<String>> rows, List<String> total) {
        return bill(FUND_HEADER, rows, FUND_TOTAL_HEADER, total);
    }

    private static String bill(List<String> header, List<List<String>> rows, List<String> footer, List<String> total) {
        StringBuilder body = new StringBuilder(csv(header));
        for (List<String> row : rows) body.append(csv(row));
        return body.append(csv(footer)).append(csv(total)).toString();
    }

    private static String csv(List<String> cells) {
        List<String> encoded = new ArrayList<>();
        for (String cell : cells) {
            boolean quote = cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r");
            encoded.add(quote ? "\"" + cell.replace("\"", "\"\"") + "\"" : cell);
        }
        return String.join(",", encoded) + "\n";
    }

    private static List<WxBillCsvParser.TradeRow> trade(String body) throws IOException {
        return WxBillCsvParser.trade(new StringReader(body), MERCHANT);
    }

    private static List<WxBillCsvParser.FundFlowRow> funds(String body) throws IOException {
        return WxBillCsvParser.fundFlow(new StringReader(body));
    }
}
