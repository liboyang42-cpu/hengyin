package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 原账本收入只读投影（<b>应计/结算口径</b>）。
 *
 * <p>口径：毛额取 {@code coop_settlement_original_component.original_amount}（按 component 发生时间归属期间），
 * 冲回取 {@code coop_settlement_original_adjustment.amount}（恒为负，按 adjustment 发生时间归属期间，
 * 经 component_id 回指原分项的收款会员）；净额 = 毛额 + 冲回。
 *
 * <p><b>这不是实收，也不是可提现金额</b>：它只是原账本分项的应计汇总。真实到账/可提现要看
 * 打款事实（{@code payout_time} / 钱包流水），本投影不读也不推断。
 *
 * <p>{@code receiver_kind=ACCOUNTING}（如平台行）的分项单列 {@code unattributed}，<b>不摊给任何会员</b>。
 *
 * <p>读失败一律抛异常，并由调用方以 {@code revenueState=UNKNOWN} 表达：未知收入不得以 0 展示。
 */
public final class CreatorRevenueLedgerVO {

    public record Entry(Long memberId, BigDecimal grossAmount, BigDecimal refundedAmount, BigDecimal netAmount,
                        long componentCount, long settlementCount, Long maxComponentId) {
    }

    public record Totals(BigDecimal grossAmount, BigDecimal refundedAmount, BigDecimal netAmount) {
    }

    /** 收入读取状态：AVAILABLE=已读到原账本；UNKNOWN=读失败/不可用，数值不可展示为 0。 */
    public enum State { AVAILABLE, UNKNOWN }

    public static final String SCOPE = "ORIGINAL_LEDGER_RECEIVER_MEMBER";
    public static final String REVENUE_BASIS = "ACCRUAL_ORIGINAL_LEDGER";
    public static final String REVENUE_BASIS_NOTE =
            "应计/结算口径：原账本分项金额汇总，不等于实收，也不等于可提现；到账与可提现以打款事实为准";
    public static final String IDENTITY_BASIS =
            "coop_settlement_original_component.receiver_kind + receiver_member_id；"
                    + "只有 MEMBER 且 member>0 计入会员收入，ACCOUNTING 单列";
    public static final String LEDGER_TABLES =
            "coop_settlement_original_component + coop_settlement_original_adjustment";

    private final State state;
    private final String unavailableReason;
    private final Date periodStart;
    private final Date periodEnd;
    private final List<Entry> entries;
    private final Totals attributed;
    private final Totals unattributed;
    private final Date watermarkAt;
    private final List<String> calculationVersions;

    private CreatorRevenueLedgerVO(State state, String unavailableReason, Date periodStart, Date periodEnd,
                                   List<Entry> entries, Totals attributed, Totals unattributed,
                                   Date watermarkAt, List<String> calculationVersions) {
        this.state = state;
        this.unavailableReason = unavailableReason;
        this.periodStart = copy(periodStart);
        this.periodEnd = copy(periodEnd);
        this.entries = entries == null ? Collections.emptyList() : List.copyOf(entries);
        this.attributed = attributed;
        this.unattributed = unattributed;
        this.watermarkAt = copy(watermarkAt);
        this.calculationVersions = calculationVersions == null
                ? Collections.emptyList() : List.copyOf(new ArrayList<>(calculationVersions));
    }

    /** 已读到原账本。 */
    public static CreatorRevenueLedgerVO available(Date periodStart, Date periodEnd, List<Entry> entries,
                                                   Totals attributed, Totals unattributed, Date watermarkAt,
                                                   List<String> calculationVersions) {
        return new CreatorRevenueLedgerVO(State.AVAILABLE, null, periodStart, periodEnd, entries,
                attributed == null ? zero() : attributed,
                unattributed == null ? zero() : unattributed,
                watermarkAt, calculationVersions);
    }

    /**
     * 读失败：保留期间以便前端说明范围，金额字段一律为 null —— 未知不展示成 0。
     */
    public static CreatorRevenueLedgerVO unknown(Date periodStart, Date periodEnd, String reason) {
        return new CreatorRevenueLedgerVO(State.UNKNOWN,
                reason == null || reason.isBlank() ? "原账本读取失败" : reason,
                periodStart, periodEnd, List.of(), null, null, null, List.of());
    }

    private static Totals zero() {
        return new Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public State getState() { return state; }
    public boolean isAvailable() { return state == State.AVAILABLE; }
    public String getUnavailableReason() { return unavailableReason; }
    public String getScope() { return SCOPE; }
    public String getRevenueBasis() { return REVENUE_BASIS; }
    public String getRevenueBasisNote() { return REVENUE_BASIS_NOTE; }
    public String getIdentityBasis() { return IDENTITY_BASIS; }
    public String getLedgerTables() { return LEDGER_TABLES; }
    public Date getPeriodStart() { return copy(periodStart); }
    public Date getPeriodEnd() { return copy(periodEnd); }
    public List<Entry> getEntries() { return entries; }
    public Totals getAttributed() { return attributed; }
    public Totals getUnattributed() { return unattributed; }
    public Date getWatermarkAt() { return copy(watermarkAt); }
    public List<String> getCalculationVersions() { return calculationVersions; }

    private static Date copy(Date value) {
        return value == null ? null : new Date(value.getTime());
    }
}
