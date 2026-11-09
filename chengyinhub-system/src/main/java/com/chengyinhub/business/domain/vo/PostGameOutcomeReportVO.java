package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 完局维护与有效复报的只读报表（C08 主指标 + 六类结果分列）。
 *
 * <p>本对象是**即时只读计算**，不是历史可回放快照：每次请求按当前源表重算，
 * 因此同时返回 {@code computedAt}、每个来源的水位与口径版本，供人工复算。
 * 未成熟不当 0%，读失败不当 0。
 *
 * <p>六类结果中只有 {@code serverFinal=true} 的项来自服务端业务事实；客户端观测项
 * 单独成行并标 {@code serverFinal=false}，不得当完成/成交。
 */
public final class PostGameOutcomeReportVO {

    /**
     * 一类结果：会员数、member-day 行数、事实数、水位与来源声明。
     *
     * @param serverFinal 是否服务端终局事实；false 表示仅为客户端观测，不能当业务完成
     */
    public record MetricCount(String metricCode, long members, long memberDays, long facts,
                              Date firstOccurredAt, Date lastOccurredAt,
                              String sourceTables, String identityBasis,
                              boolean serverFinal, boolean publicVisible,
                              Date watermarkAt) {
    }

    /**
     * 成熟 cohort 分母及排除明细。{@code retainedMembers} 在未成熟时为 null（不是 0）。
     *
     * <p>排除口径：终态全退 = {@code payment_status=4}（已退款）**或**退款已确认到账
     * （{@code status=1} 且 {@code payout_status in (1,4)}）且累计退款额 ≥ 实付；
     * 两条路取并集 —— 退款到账可能先于 payment_status 翻转。
     * {@code payout_status in (0,2,3,5,6)} 是未到账/待人工/已提交未确认，**不算已退**。
     */
    public record Cohort(long eligibleMembers, long freeMembers, long fullRefundExcluded,
                         long cancelledOrExpiredExcluded,
                         long partialRefundMembers, long pendingRefundMembers,
                         long duplicateOrdersCollapsed, long multiSeatCollapsed,
                         Long retainedMembers, Long freeRetainedMembers,
                         long partialRefundRetained, long pendingRefundRetained,
                         long lateFactCount, long notMatureMembers,
                         BigDecimal retentionRate, BigDecimal freeRetentionRate) {
    }

    /** repeat_signup_v1 独立口径；仅 30 日请求提供，旧 V2 cohort 与结果项不变。 */
    public record RepeatSignupV1(String definitionVersion, int windowDays,
                                 String timeBasis, String identityBasis, String recomputeBasis,
                                 boolean available, String unknownReason,
                                 RepeatSignupCohort paid, RepeatSignupCohort all) {
    }

    /** 分子/比例只使用 matureMembers；零成熟分母为 null，未成熟会员不算未复报。 */
    public record RepeatSignupCohort(String eligibilityBasis,
                                     long eligibleMembers, long matureMembers, long notMatureMembers,
                                     Long retainedMembers, BigDecimal retentionRate,
                                     long partialRefundMembers, long pendingRefundMembers,
                                     long partialRefundRetained, long pendingRefundRetained,
                                     long lateFactCount) {
    }

    /**
     * 口径版本；数值随口径变化递增，便于判断两次结果是否可比。
     *
     * <p>V2：把节点小游戏({@code game_runtime_session})移出整局完成口径，改用真实整局终局事实
     * （{@code topic_route_session.status=1} 已到终点）。同口径内的历史响应数字与 V2 不可直接比较。
     */
    public static final String BASIS_VERSION = "POST_GAME_OUTCOME_V2_20261108";
    /** 已核对但本报表无法按会员归属的完成来源：列在这里而不是猜一个会员。 */
    public static final String GAME_SESSION_ACTIVITY_FINISHED_UNAVAILABLE =
            "game_session(活动级总局, status=FINISHED)：只有 finished_by 单个操作人，没有逐会员参与事实，"
                    + "无法把一次总局完成归属到具体会员，因此不计入 OFFICIAL_COMPLETION";
    public static final String DEDUPLICATION = "MEMBER_LEVEL_FIRST_VALID_REGISTRATION";
    public static final String IDENTITY_BASIS =
            "服务端报名事实：全历史首笔有效报名(registration_status=2 & payment_status=2 & payment_time) "
                    + "+ refund_application 到账退款；客户端观测单列，不参与分母分子";
    public static final String RECOMPUTE_BASIS =
            "READ_ONLY_SQL：分母=全历史首笔有效报名去重会员，分子=窗口内另一笔不同 parent_order_no 的有效报名会员";
    public static final String NOT_A_SNAPSHOT =
            "即时只读计算；不落库、不可历史回放。需要对比请自行留存响应(含 computedAt 与各来源水位)";
    public static final String TEST_EXCLUSION_BASIS =
            "NO_SERVER_MARKER：现有表无服务端测试账号标记列，testExcluded 恒 0 且不可信；不得据此宣称已排除测试数据";

    private final String cohortDate;
    private final int windowDays;
    private final boolean mature;
    private final Date windowStart;
    private final Date windowEnd;
    private final Date computedAt;
    private final Map<String, Date> sourceWatermarks;
    private final Cohort cohort;
    private final Map<String, MetricCount> metrics;
    private final RoamRevisitReportVO roamRevisit;
    private final RepeatSignupV1 repeatSignupV1;

    public PostGameOutcomeReportVO(String cohortDate, int windowDays, boolean mature,
                                   Date windowStart, Date windowEnd, Date computedAt,
                                   Map<String, Date> sourceWatermarks, Cohort cohort,
                                   Map<String, MetricCount> metrics, RoamRevisitReportVO roamRevisit) {
        this(cohortDate, windowDays, mature, windowStart, windowEnd, computedAt,
                sourceWatermarks, cohort, metrics, roamRevisit, null);
    }

    public PostGameOutcomeReportVO(String cohortDate, int windowDays, boolean mature,
                                   Date windowStart, Date windowEnd, Date computedAt,
                                   Map<String, Date> sourceWatermarks, Cohort cohort,
                                   Map<String, MetricCount> metrics, RoamRevisitReportVO roamRevisit,
                                   RepeatSignupV1 repeatSignupV1) {
        this.repeatSignupV1 = repeatSignupV1;
        this.cohortDate = cohortDate;
        this.windowDays = windowDays;
        this.mature = mature;
        this.windowStart = copy(windowStart);
        this.windowEnd = copy(windowEnd);
        this.computedAt = copy(computedAt);
        this.sourceWatermarks = sourceWatermarks == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(sourceWatermarks));
        this.cohort = cohort;
        this.metrics = metrics == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
        this.roamRevisit = roamRevisit;
    }

    /**
     * 已核对但无法按会员归属、因而未计入的完成来源。显式列出而不是静默忽略，
     * 让读者知道「整局完成数」缺了哪一块以及为什么。
     */
    public Map<String, String> getUnavailableSources() {
        return Map.of("GAME_SESSION_ACTIVITY_FINISHED", GAME_SESSION_ACTIVITY_FINISHED_UNAVAILABLE);
    }

    public String getCohortDate() { return cohortDate; }
    public int getWindowDays() { return windowDays; }
    public boolean isMature() { return mature; }
    public Date getWindowStart() { return copy(windowStart); }
    public Date getWindowEnd() { return copy(windowEnd); }
    public Date getComputedAt() { return copy(computedAt); }
    public Map<String, Date> getSourceWatermarks() { return sourceWatermarks; }
    public Cohort getCohort() { return cohort; }
    public Map<String, MetricCount> getMetrics() { return metrics; }
    public RoamRevisitReportVO getRoamRevisit() { return roamRevisit; }

    public RepeatSignupV1 getRepeatSignupV1() { return repeatSignupV1; }
    public String getBasisVersion() { return BASIS_VERSION; }
    public String getDeduplication() { return DEDUPLICATION; }
    public String getIdentityBasis() { return IDENTITY_BASIS; }
    public String getRecomputeBasis() { return RECOMPUTE_BASIS; }
    public boolean isHistoricalReplayable() { return false; }
    public String getSnapshotNote() { return NOT_A_SNAPSHOT; }
    public String getTestExclusionBasis() { return TEST_EXCLUSION_BASIS; }
    public List<String> getMetricCodes() { return List.copyOf(metrics.keySet()); }

    private static Date copy(Date value) {
        return value == null ? null : new Date(value.getTime());
    }
}
