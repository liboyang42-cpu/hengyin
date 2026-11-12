package com.chengyinhub.business.service.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 视频悬赏应计的纯计算口径(无 Spring、无 IO,可单测)。
 *
 * <p><b>规则(全部可配置,不写死在调用方)</b>:
 * <ul>
 *   <li>只计"发布后 N 天窗口"内的有效自然播放;</li>
 *   <li>每条投稿按 {@code ratePerThousand} 折算;</li>
 *   <li><b>同一原视频跨平台合计封顶</b>:组内所有投稿的应计之和不得超过 {@code perVideoCap}。
 *       跨平台按播放量比例分摊封顶额度,重复搬运不会因为"多贴"而多领。</li>
 * </ul>
 *
 * <p><b>为什么用 DOWN(截断)而不是 HALF_UP 结尾差</b>:多出来的几分钱分不清该给谁,
 * 截断保证"任何情况下组内合计 ≤ 封顶",平台不会因为舍入多付。未分配的尾差留在平台侧,
 * 明确不发放,而不是记到某个人账上。
 *
 * @author chengyinhub
 */
public final class VideoBountyRuleCalculator
{
    /** 金额小数位。 */
    public static final int MONEY_SCALE = 2;
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private VideoBountyRuleCalculator() {
    }

    /** 规则快照:计算时必须用投稿/证据当时生效的版本,不能读当前配置。 */
    public static final class Rule
    {
        private final BigDecimal ratePerThousand;
        private final BigDecimal perVideoCap;
        private final int windowDays;
        private final String currency;

        public Rule(BigDecimal ratePerThousand, BigDecimal perVideoCap, int windowDays, String currency) {
            if (ratePerThousand == null || ratePerThousand.signum() < 0) {
                throw new IllegalArgumentException("ratePerThousand 必须是非负金额");
            }
            if (perVideoCap == null || perVideoCap.signum() < 0) {
                throw new IllegalArgumentException("perVideoCap 必须是非负金额");
            }
            if (windowDays <= 0) {
                throw new IllegalArgumentException("windowDays 必须为正数");
            }
            this.ratePerThousand = ratePerThousand;
            this.perVideoCap = perVideoCap;
            this.windowDays = windowDays;
            this.currency = currency;
        }

        public BigDecimal getRatePerThousand() { return ratePerThousand; }
        public BigDecimal getPerVideoCap() { return perVideoCap; }
        public int getWindowDays() { return windowDays; }
        public String getCurrency() { return currency; }
    }

    /** 组内一条投稿的播放基数。 */
    public static final class Contribution
    {
        private final long submissionId;
        private final long views;

        public Contribution(long submissionId, long views) {
            this.submissionId = submissionId;
            this.views = Math.max(0L, views);
        }

        public long getSubmissionId() { return submissionId; }
        public long getViews() { return views; }
    }

    /** 组内一条投稿的分配结果。 */
    public static final class Allocation
    {
        private final long submissionId;
        private final long views;
        private final BigDecimal grossAmount;
        private final BigDecimal payableAmount;

        Allocation(long submissionId, long views, BigDecimal grossAmount, BigDecimal payableAmount) {
            this.submissionId = submissionId;
            this.views = views;
            this.grossAmount = grossAmount;
            this.payableAmount = payableAmount;
        }

        public long getSubmissionId() { return submissionId; }
        public long getViews() { return views; }
        public BigDecimal getGrossAmount() { return grossAmount; }
        public BigDecimal getPayableAmount() { return payableAmount; }
    }

    /** 组级计算结果。 */
    public static final class GroupCalculation
    {
        private final long groupViews;
        private final BigDecimal groupGross;
        private final BigDecimal perVideoCap;
        private final BigDecimal payableTotal;
        private final List<Allocation> allocations;

        GroupCalculation(long groupViews, BigDecimal groupGross, BigDecimal perVideoCap,
                         BigDecimal payableTotal, List<Allocation> allocations) {
            this.groupViews = groupViews;
            this.groupGross = groupGross;
            this.perVideoCap = perVideoCap;
            this.payableTotal = payableTotal;
            this.allocations = Collections.unmodifiableList(allocations);
        }

        public long getGroupViews() { return groupViews; }
        public BigDecimal getGroupGross() { return groupGross; }
        public BigDecimal getPerVideoCap() { return perVideoCap; }
        public BigDecimal getPayableTotal() { return payableTotal; }
        public List<Allocation> getAllocations() { return allocations; }

        public Allocation forSubmission(long submissionId) {
            for (Allocation allocation : allocations) {
                if (allocation.getSubmissionId() == submissionId) return allocation;
            }
            return null;
        }
    }

    /**
     * 计算整组应计。
     *
     * @param rule          规则快照
     * @param contributions 同组所有<b>有效</b>投稿(已通过审核且在窗口内的播放基数)
     */
    public static GroupCalculation compute(Rule rule, List<Contribution> contributions) {
        List<Contribution> rows = contributions == null ? Collections.emptyList() : contributions;
        long groupViews = 0L;
        for (Contribution row : rows) groupViews += row.getViews();
        BigDecimal groupGross = amount(rule.ratePerThousand, groupViews);

        boolean capped = groupGross.compareTo(rule.perVideoCap) > 0;
        List<Allocation> allocations = new ArrayList<>(rows.size());
        BigDecimal paid = BigDecimal.ZERO.setScale(MONEY_SCALE);
        for (Contribution row : rows) {
            BigDecimal gross = amount(rule.ratePerThousand, row.getViews());
            // 触发封顶时按播放占比分摊封顶额度,否则按本条应计;两者都截断到分。
            BigDecimal target;
            if (capped) {
                target = groupViews == 0L
                        ? BigDecimal.ZERO.setScale(MONEY_SCALE)
                        : rule.perVideoCap.multiply(BigDecimal.valueOf(row.getViews()))
                                .divide(BigDecimal.valueOf(groupViews), MONEY_SCALE, RoundingMode.DOWN);
            } else {
                target = gross;
            }
            // 剩余额度是硬上限:无论哪条分支,组内已分配合计都不会超过封顶。
            BigDecimal room = rule.perVideoCap.subtract(paid);
            BigDecimal payable = target.min(room).max(BigDecimal.ZERO).setScale(MONEY_SCALE, RoundingMode.DOWN);
            paid = paid.add(payable);
            allocations.add(new Allocation(row.getSubmissionId(), row.getViews(), gross, payable));
        }
        return new GroupCalculation(groupViews, groupGross, rule.perVideoCap, paid, allocations);
    }

    /** {@code rate × views / 1000},截断到分。 */
    public static BigDecimal amount(BigDecimal ratePerThousand, long views) {
        if (views <= 0L) return BigDecimal.ZERO.setScale(MONEY_SCALE);
        return ratePerThousand.multiply(BigDecimal.valueOf(views))
                .divide(THOUSAND, MONEY_SCALE, RoundingMode.DOWN);
    }

    /**
     * 证据区间是否整体落在应计窗口内。
     *
     * <p><b>为什么要求"整体"而不是重叠</b>:证据只给出区间总量,没有逐日分布。
     * 区间跨过窗口边界时无法拆分,若按重叠计入就会把窗口外的播放算进应计;
     * 因此宁可少计(要求按窗口边界重新导出),也不凭空推断。
     */
    public static boolean fullyInsideWindow(Date periodStart, Date periodEnd, Date windowStart, Date windowEnd) {
        if (periodStart == null || periodEnd == null || windowStart == null || windowEnd == null) return false;
        if (periodEnd.before(periodStart)) return false;
        return !periodStart.before(windowStart) && !periodEnd.after(windowEnd);
    }

    /** 窗口终点 = 发布时间 + windowDays(按天精确加,不做"月末取整"这类隐式改动)。 */
    public static Date windowEnd(Date publishedAt, int windowDays) {
        return new Date(publishedAt.getTime() + windowDays * 24L * 60L * 60L * 1000L);
    }
}
