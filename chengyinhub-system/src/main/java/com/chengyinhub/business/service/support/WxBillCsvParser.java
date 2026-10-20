package com.chengyinhub.business.service.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 微信官方交易/资金账单解析；只保留外部事实，不推断订单状态或调整余额。 */
public final class WxBillCsvParser {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    private WxBillCsvParser() { }

    public record TradeRow(Date tradeTime, String merchantId, String transactionId,
            String outTradeNo, String tradeState, String refundId, String outRefundNo,
            String refundStatus, BigDecimal orderAmount, BigDecimal settlementAmount,
            BigDecimal couponAmount, BigDecimal requestedRefundAmount,
            BigDecimal settlementRefundAmount, BigDecimal couponRefundAmount, BigDecimal fee) { }

    public record FundFlowRow(Date accountingTime, String wxBusinessNo, String flowNo,
            String businessName, String businessType, String direction, BigDecimal amount,
            BigDecimal balance, String initiator, String remark, String voucherNo) { }

    public static List<TradeRow> trade(Reader source, String merchantId) throws IOException {
        Csv csv = new Csv(source);
        csv.require("交易时间", "商户号", "微信订单号", "商户订单号", "交易状态", "货币种类",
                "应结订单金额", "代金券金额", "微信退款单号", "商户退款单号", "退款金额",
                "充值券退款金额", "退款状态", "手续费", "订单金额", "申请退款金额");
        List<TradeRow> rows = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        BigDecimal order = BigDecimal.ZERO, settlement = BigDecimal.ZERO, refund = BigDecimal.ZERO;
        BigDecimal settlementRefund = BigDecimal.ZERO, couponRefund = BigDecimal.ZERO, fees = BigDecimal.ZERO;
        List<String> cells;
        while ((cells = csv.next("总交易单数")) != null) {
            if (!merchantId.equals(csv.value(cells, "商户号")) || !"CNY".equals(csv.value(cells, "货币种类"))) {
                throw invalid("账单商户号或币种不匹配");
            }
            String state = csv.value(cells, "交易状态");
            if (!Set.of("SUCCESS", "REFUND", "REVOKED").contains(state)) throw invalid("未知交易状态");
            String refundStatus = csv.value(cells, "退款状态");
            if (!"SUCCESS".equals(state) && !Set.of("SUCCESS", "PROCESSING", "FAIL", "CHANGE").contains(refundStatus)) {
                throw invalid("未知退款状态");
            }
            TradeRow row = new TradeRow(time(csv.value(cells, "交易时间")), merchantId,
                    required(csv.value(cells, "微信订单号"), 64), required(csv.value(cells, "商户订单号"), 64),
                    state, csv.value(cells, "微信退款单号"), csv.value(cells, "商户退款单号"), refundStatus,
                    money(csv.value(cells, "订单金额"), false), money(csv.value(cells, "应结订单金额"), false),
                    money(csv.value(cells, "代金券金额"), false), money(csv.value(cells, "申请退款金额"), false),
                    money(csv.value(cells, "退款金额"), false), money(csv.value(cells, "充值券退款金额"), false),
                    money(csv.value(cells, "手续费"), true));
            String identity = "SUCCESS".equals(state) ? "PAY:" + row.transactionId()
                    : "REFUND:" + required(row.refundId(), 64);
            if (!"SUCCESS".equals(state)) required(row.outRefundNo(), 64);
            if (!identities.add(identity)) throw invalid("账单中存在重复交易明细");
            rows.add(row);
            order = order.add(row.orderAmount()); settlement = settlement.add(row.settlementAmount());
            refund = refund.add(row.requestedRefundAmount()); settlementRefund = settlementRefund.add(row.settlementRefundAmount());
            couponRefund = couponRefund.add(row.couponRefundAmount()); fees = fees.add(row.fee());
        }
        csv.summary("总交易单数", rows.size());
        csv.summary("订单总金额", order); csv.summary("应结订单总金额", settlement);
        csv.summary("申请退款总金额", refund); csv.summary("退款总金额", settlementRefund);
        csv.summary("充值券退款总金额", couponRefund); csv.summary("手续费总金额", fees);
        return rows;
    }

    public static List<FundFlowRow> fundFlow(Reader source) throws IOException {
        Csv csv = new Csv(source);
        csv.require("记账时间", "微信支付业务单号", "资金流水单号", "业务名称", "业务类型", "收支类型",
                "收支金额(元)", "账户结余(元)", "资金变更提交申请人", "备注", "业务凭证号");
        List<FundFlowRow> rows = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        long incomeCount = 0, expenseCount = 0;
        BigDecimal income = BigDecimal.ZERO, expense = BigDecimal.ZERO;
        List<String> cells;
        while ((cells = csv.next("资金流水总笔数")) != null) {
            String direction = csv.value(cells, "收支类型");
            if (!"收入".equals(direction) && !"支出".equals(direction)) throw invalid("未知资金收支类型");
            FundFlowRow row = new FundFlowRow(time(csv.value(cells, "记账时间")),
                    required(csv.value(cells, "微信支付业务单号"), 128),
                    required(csv.value(cells, "资金流水单号"), 128),
                    required(csv.value(cells, "业务名称"), 128), required(csv.value(cells, "业务类型"), 128),
                    direction, money(csv.value(cells, "收支金额(元)"), false),
                    money(csv.value(cells, "账户结余(元)"), true), csv.value(cells, "资金变更提交申请人"),
                    csv.value(cells, "备注"), csv.value(cells, "业务凭证号"));
            if (!identities.add(row.flowNo())) throw invalid("账单中存在重复资金流水");
            rows.add(row);
            if ("收入".equals(direction)) { incomeCount++; income = income.add(row.amount()); }
            else { expenseCount++; expense = expense.add(row.amount()); }
        }
        csv.summary("资金流水总笔数", rows.size()); csv.summary("收入笔数", incomeCount);
        csv.summary("支出笔数", expenseCount); csv.summary("收入金额", income); csv.summary("支出金额", expense);
        return rows;
    }

    private static Date time(String value) {
        try { return Date.from(LocalDateTime.parse(value, TIME).atZone(ZONE).toInstant()); }
        catch (RuntimeException failure) { throw invalid("账单时间格式错误"); }
    }

    private static BigDecimal money(String value, boolean signed) {
        if (!value.matches((signed ? "-?" : "") + "[0-9]{1,10}(?:\\.[0-9]{1,2})?")) {
            throw invalid("账单金额格式或精度错误");
        }
        return new BigDecimal(value).setScale(2);
    }

    private static String required(String value, int maxLength) {
        if (value.isBlank() || "0".equals(value) || value.length() > maxLength) throw invalid("账单必填字段缺失或过长");
        return value;
    }

    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }

    private static final class Csv {
        private final BufferedReader reader;
        private final Map<String, Integer> header;
        private Map<String, Integer> summaryHeader;
        private List<String> summaryValues;

        private Csv(Reader source) throws IOException {
            reader = source instanceof BufferedReader ? (BufferedReader) source : new BufferedReader(source);
            String line = nonempty();
            if (line == null) throw invalid("账单文件为空");
            header = columns(cells(line.replaceFirst("^\\uFEFF", "")));
        }

        private void require(String... names) {
            for (String name : names) if (!header.containsKey(name)) throw invalid("账单缺少字段: " + name);
        }

        private List<String> next(String footer) throws IOException {
            if (summaryHeader != null) return null;
            String line = nonempty();
            if (line == null) throw invalid("账单缺少汇总，可能下载不完整");
            List<String> values = cells(line);
            if (footer.equals(values.get(0))) {
                summaryHeader = columns(values);
                String summary = nonempty();
                if (summary == null) throw invalid("账单缺少汇总值");
                summaryValues = cells(summary);
                if (summaryValues.size() != summaryHeader.size() || nonempty() != null) throw invalid("账单汇总格式错误");
                return null;
            }
            if (values.size() != header.size()) throw invalid("账单明细列数错误");
            return values;
        }

        private String value(List<String> values, String name) { return values.get(header.get(name)); }

        private void summary(String name, long expected) {
            String value = summary(name);
            if (!value.matches("[0-9]{1,12}") || Long.parseLong(value) != expected) throw invalid("账单汇总笔数不匹配: " + name);
        }

        private void summary(String name, BigDecimal expected) {
            if (money(summary(name), true).compareTo(expected) != 0) throw invalid("账单汇总金额不匹配: " + name);
        }

        private String summary(String name) {
            if (summaryHeader == null || !summaryHeader.containsKey(name)) throw invalid("账单缺少汇总字段: " + name);
            return summaryValues.get(summaryHeader.get(name));
        }

        private String nonempty() throws IOException {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                StringBuilder record = new StringBuilder(line);
                while (openQuote(record)) {
                    String next = reader.readLine();
                    if (next == null) throw invalid("账单CSV引号未闭合");
                    record.append('\n').append(next);
                }
                return record.toString();
            }
            return null;
        }

        private static boolean openQuote(CharSequence record) {
            boolean quoted = false;
            for (int i = 0; i < record.length(); i++) {
                if (record.charAt(i) != '"') continue;
                if (quoted && i + 1 < record.length() && record.charAt(i + 1) == '"') i++;
                else quoted = !quoted;
            }
            return quoted;
        }

        private static Map<String, Integer> columns(List<String> cells) {
            Map<String, Integer> result = new HashMap<>();
            for (int i = 0; i < cells.size(); i++) {
                if (cells.get(i).isBlank() || result.put(cells.get(i), i) != null) throw invalid("账单表头重复或为空");
            }
            return result;
        }

        private static List<String> cells(String line) {
            List<String> values = new ArrayList<>();
            StringBuilder value = new StringBuilder();
            boolean quoted = false, closedQuote = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quoted) {
                    if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { value.append('"'); i++; }
                    else if (c == '"') { quoted = false; closedQuote = true; }
                    else value.append(c);
                } else if (c == ',') {
                    values.add(clean(value.toString())); value.setLength(0); closedQuote = false;
                } else if (c == '"' && (value.length() == 0 || "`".contentEquals(value)) && !closedQuote) quoted = true;
                else {
                    if (c == '"' || (closedQuote && !Character.isWhitespace(c))) throw invalid("账单CSV引号格式错误");
                    if (!closedQuote) value.append(c);
                }
            }
            if (quoted) throw invalid("账单CSV引号未闭合");
            values.add(clean(value.toString()));
            return values;
        }

        private static String clean(String value) {
            return value.startsWith("`") ? value.substring(1) : value;
        }
    }
}
