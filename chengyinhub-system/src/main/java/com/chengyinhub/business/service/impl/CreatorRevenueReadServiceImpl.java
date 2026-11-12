package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.CreatorRevenueRow;
import com.chengyinhub.business.domain.vo.CreatorRevenueLedgerVO;
import com.chengyinhub.business.mapper.CreatorRevenueLedgerMapper;
import com.chengyinhub.business.service.ICreatorRevenueReadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 原账本收入只读实现（应计/结算口径）。
 *
 * <p>硬约束（对应「收入按原账本」「读失败不假零」）：
 * <ol>
 *   <li>不计算费率、不重算分成、不改原 head 金额；只汇总既有不可变分项与冲回。</li>
 *   <li>只有 {@code receiver_kind=MEMBER} 且 {@code receiver_member_id>0} 进会员收入；
 *       {@code ACCOUNTING}（如平台行）单列 {@code unattributed}，不摊给任何会员，也不并进任何人净额。</li>
 *   <li>mapper 返回 null（查询失败）⇒ 抛 {@link IllegalStateException}；
 *       调用方据此输出 {@code revenueState=UNKNOWN}，不返回空列表或 0 冒充「没收入」。</li>
 * </ol>
 */
@Service
public class CreatorRevenueReadServiceImpl implements ICreatorRevenueReadService {

    private static final ZoneId LEDGER_ZONE = ZoneId.of("Asia/Shanghai");
    static final int DEFAULT_WINDOW_DAYS = 30;
    private static final String RECEIVER_MEMBER = "MEMBER";

    private final CreatorRevenueLedgerMapper mapper;
    private final Clock clock;

    @Autowired
    public CreatorRevenueReadServiceImpl(CreatorRevenueLedgerMapper mapper) {
        this(mapper, Clock.system(LEDGER_ZONE));
    }

    CreatorRevenueReadServiceImpl(CreatorRevenueLedgerMapper mapper, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(readOnly = true)
    public CreatorRevenueLedgerVO read(Date fromInclusive, Date toExclusive) {
        Window window = resolveWindow(fromInclusive, toExclusive);
        Map<Long, Accumulator> attributed = new LinkedHashMap<>();
        Accumulator unattributed = new Accumulator();

        List<CreatorRevenueRow> gross = mapper.selectGrossByReceiver(window.from, window.toExclusive);
        if (gross == null) {
            throw new IllegalStateException("原账本毛额查询返回 null，拒绝把未知收入报告为 0");
        }
        for (CreatorRevenueRow row : gross) {
            if (row == null) continue;
            bucket(row, attributed, unattributed).mergeGross(row);
        }

        List<CreatorRevenueRow> refunded = mapper.selectRefundedByReceiver(window.from, window.toExclusive);
        if (refunded == null) {
            throw new IllegalStateException("原账本冲回查询返回 null，拒绝把未知冲回报告为 0");
        }
        for (CreatorRevenueRow row : refunded) {
            if (row == null) continue;
            bucket(row, attributed, unattributed).mergeRefunded(row);
        }

        List<String> versions = mapper.selectCalculationVersions(window.from, window.toExclusive);
        if (versions == null) {
            throw new IllegalStateException("原账本计算版本查询返回 null，拒绝在口径未知时出报表");
        }

        List<CreatorRevenueLedgerVO.Entry> entries = new ArrayList<>(attributed.size());
        for (Map.Entry<Long, Accumulator> entry : attributed.entrySet()) {
            entries.add(entry.getValue().toEntry(entry.getKey()));
        }
        return CreatorRevenueLedgerVO.available(window.from, window.toExclusive, entries,
                attributedTotals(entries), unattributed.toTotals(), unattributed.watermarkAt, versions);
    }

    @Override
    @Transactional(readOnly = true)
    public CreatorRevenueLedgerVO readForMember(Long memberId, Date fromInclusive, Date toExclusive) {
        if (memberId == null) throw new IllegalArgumentException("memberId 不能为空");
        CreatorRevenueLedgerVO ledger = read(fromInclusive, toExclusive);
        return CreatorRevenueLedgerVO.available(ledger.getPeriodStart(), ledger.getPeriodEnd(),
                memberEntries(ledger.getEntries(), memberId),
                memberTotals(ledger.getEntries(), memberId),
                // 平台/ACCOUNTING 应计不属于任何个体创作者：会员视角不展示，也不并进净额。
                null, ledger.getWatermarkAt(), ledger.getCalculationVersions());
    }

    private static List<CreatorRevenueLedgerVO.Entry> memberEntries(
            List<CreatorRevenueLedgerVO.Entry> entries, Long memberId) {
        for (CreatorRevenueLedgerVO.Entry entry : entries) {
            if (memberId.equals(entry.memberId())) return List.of(entry);
        }
        // 期间内确实没有该会员的原分项 ⇒ 净额 0；水位与口径版本仍随响应给出，便于区分「无分项」与「读失败」。
        return List.of();
    }

    private static CreatorRevenueLedgerVO.Totals memberTotals(
            List<CreatorRevenueLedgerVO.Entry> entries, Long memberId) {
        for (CreatorRevenueLedgerVO.Entry entry : entries) {
            if (memberId.equals(entry.memberId())) {
                return new CreatorRevenueLedgerVO.Totals(
                        entry.grossAmount(), entry.refundedAmount(), entry.netAmount());
            }
        }
        return new CreatorRevenueLedgerVO.Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static CreatorRevenueLedgerVO.Totals attributedTotals(List<CreatorRevenueLedgerVO.Entry> entries) {
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal refunded = BigDecimal.ZERO;
        for (CreatorRevenueLedgerVO.Entry entry : entries) {
            gross = gross.add(entry.grossAmount());
            refunded = refunded.add(entry.refundedAmount());
        }
        return new CreatorRevenueLedgerVO.Totals(gross, refunded, gross.add(refunded));
    }

    /**
     * 只有 MEMBER 且有正 member id 才进会员收入；其余（ACCOUNTING、member 缺失）全部归 unattributed。
     * 这条判据是「ACCOUNTING 等不得进会员输出」的落点。
     */
    private static Accumulator bucket(CreatorRevenueRow row, Map<Long, Accumulator> attributed,
                                      Accumulator unattributed) {
        Long memberId = row.getMemberId();
        if (!RECEIVER_MEMBER.equals(row.getReceiverKind()) || memberId == null || memberId <= 0) {
            return unattributed;
        }
        return attributed.computeIfAbsent(memberId, key -> new Accumulator());
    }

    private Window resolveWindow(Date fromInclusive, Date toExclusive) {
        if (fromInclusive != null && toExclusive != null) {
            if (!toExclusive.after(fromInclusive)) {
                throw new IllegalArgumentException("收入期间结束必须晚于开始");
            }
            return new Window(fromInclusive, toExclusive);
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate start = today.minusDays(DEFAULT_WINDOW_DAYS);
        return new Window(Date.from(start.atStartOfDay(LEDGER_ZONE).toInstant()),
                Date.from(today.atStartOfDay(LEDGER_ZONE).toInstant()));
    }

    private static final class Window {
        private final Date from;
        private final Date toExclusive;

        private Window(Date from, Date toExclusive) {
            this.from = from;
            this.toExclusive = toExclusive;
        }
    }

    private static final class Accumulator {
        private BigDecimal gross = BigDecimal.ZERO;
        private BigDecimal refunded = BigDecimal.ZERO;
        private long componentCount;
        private long settlementCount;
        private Long maxComponentId;
        private Date watermarkAt;

        private void mergeGross(CreatorRevenueRow row) {
            gross = gross.add(nullSafe(row.getGrossAmount()));
            componentCount += row.getComponentCount() == null ? 0 : row.getComponentCount();
            settlementCount += row.getSettlementCount() == null ? 0 : row.getSettlementCount();
            maxComponentId = maxId(maxComponentId, row.getMaxComponentId());
            watermarkAt = later(watermarkAt, row.getWatermarkAt());
        }

        /**
         * 冲回行只贡献「冲回额」与水位。
         *
         * <p>分项数、结算数、最大分项 id 属于<b>原分项</b>那一套命名空间：
         * 冲回来自 {@code coop_settlement_original_adjustment}，它的行数和主键是 adjustment 的，
         * 不是 component 的。把它们并进分项计数会把「3 个分项」报成「4 个」（多出冲回行），
         * 并让追踪 id 指向另一张表 —— 追账时会在错误表里找不到那一行。
         */
        private void mergeRefunded(CreatorRevenueRow row) {
            refunded = refunded.add(nullSafe(row.getRefundedAmount()));
            watermarkAt = later(watermarkAt, row.getWatermarkAt());
        }

        private CreatorRevenueLedgerVO.Entry toEntry(Long memberId) {
            return new CreatorRevenueLedgerVO.Entry(memberId, gross, refunded, gross.add(refunded),
                    componentCount, settlementCount, maxComponentId);
        }

        private CreatorRevenueLedgerVO.Totals toTotals() {
            return new CreatorRevenueLedgerVO.Totals(gross, refunded, gross.add(refunded));
        }

        private static BigDecimal nullSafe(BigDecimal value) {
            return value == null ? BigDecimal.ZERO : value;
        }

        private static Long maxId(Long left, Long right) {
            if (left == null) return right;
            if (right == null) return left;
            return left >= right ? left : right;
        }

        private static Date later(Date left, Date right) {
            if (left == null) return right == null ? null : new Date(right.getTime());
            if (right == null) return left;
            return right.after(left) ? new Date(right.getTime()) : left;
        }
    }
}
