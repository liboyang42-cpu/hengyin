package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.OutcomeFactRow;
import com.chengyinhub.business.domain.dto.RegistrationCohortFact;
import com.chengyinhub.business.domain.vo.PostGameOutcomeReportVO;
import com.chengyinhub.business.domain.vo.RoamRevisitReportVO;
import com.chengyinhub.business.mapper.PostGameOutcomeMapper;
import com.chengyinhub.business.service.IPostGameOutcomeReportService;
import com.chengyinhub.business.service.IRoamRevisitReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 完局维护与有效复报的只读实现。
 *
 * <p>主指标口径（可人工复算，全部随响应返回）：
 * <ol>
 *   <li>候选会员 = cohort 当天有过付费报名的会员；</li>
 *   <li>「首笔报名」按<b>全历史</b>取最早的一笔付费报名（{@code payment_status in (2,4)} 且 {@code payment_time} 非空），
 *       因此同一会员只会落进一个 cohort，不会因跨月复购被重复计入；</li>
 *   <li>首笔全退（{@code payment_status=4} 或 已确认到账退款额 ≥ 实付）→ 不进分母，计入 {@code fullRefundExcluded}；</li>
 *   <li>首笔取消/过期 → 不进分母，计入 {@code cancelledOrExpiredExcluded}；</li>
 *   <li>首笔免费（实付 ≤ 0）→ 进 {@code freeMembers}，与付费分母分开展示；</li>
 *   <li>首笔部分退 / 有待审退款 → 仍进分母（钱已付出且未全退），各自单列计数；</li>
 *   <li>分子 = 分母中在观察窗内产生<b>另一笔不同 parent_order_no</b> 的有效报名（未全退、已支付、未取消）；</li>
 *   <li>观察窗未走完 ⇒ {@code retainedMembers=null} + {@code mature=false}，<b>不当 0%</b>。</li>
 * </ol>
 *
 * <p>只读：不落库、不写修订。任何来源查询返回 null 一律抛异常 —— 未知不得展示为 0。
 */
@Service
public class PostGameOutcomeReportServiceImpl implements IPostGameOutcomeReportService {

    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int[] SUPPORTED_WINDOWS = {7, 30, 90};
    /** 单批 IN 列表上限：避免超大 cohort 生成超长 SQL / 超参数上限。 */
    static final int MEMBER_BATCH_SIZE = 500;
    static final String COMPLETION = "OFFICIAL_COMPLETION";
    static final String REWARD = "REWARD_ISSUANCE";
    static final String PRIVATE_PHOTO = "PRIVATE_PHOTO";
    static final String PUBLIC_SHARE = "PUBLIC_SHARE";
    static final String RECO_CLICK = "RECOMMENDATION_CLICK";
    static final String SECOND_REGISTRATION = "SECOND_VALID_REGISTRATION";
    static final String CLIENT_OBSERVED = "CLIENT_OBSERVED_EVENT";

    private final PostGameOutcomeMapper mapper;
    private final IRoamRevisitReportService roamRevisitReportService;
    private final Clock clock;

    @Autowired
    public PostGameOutcomeReportServiceImpl(PostGameOutcomeMapper mapper,
                                            IRoamRevisitReportService roamRevisitReportService) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.roamRevisitReportService = roamRevisitReportService;
        this.clock = Clock.system(REPORT_ZONE);
    }

    PostGameOutcomeReportServiceImpl(PostGameOutcomeMapper mapper,
                                     IRoamRevisitReportService roamRevisitReportService,
                                     Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.roamRevisitReportService = roamRevisitReportService;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(readOnly = true)
    public PostGameOutcomeReportVO generate(LocalDate cohortDate, int windowDays) {
        validate(cohortDate, windowDays);

        ZoneId zone = clock.getZone();
        Date cohortStart = Date.from(cohortDate.atStartOfDay(zone).toInstant());
        Date cohortEnd = Date.from(cohortDate.plusDays(1).atStartOfDay(zone).toInstant());
        // 观察窗从 cohort 次日 00:00 起算 windowDays 天；窗末即成熟点。
        Date windowEnd = Date.from(cohortDate.plusDays(windowDays + 1L).atStartOfDay(zone).toInstant());
        boolean mature = !clock.instant().isBefore(windowEnd.toInstant());

        List<Long> memberIds = require(mapper.selectCohortMemberIds(cohortStart, cohortEnd),
                "cohort 候选会员查询返回 null，拒绝把未知报成 0");
        List<RegistrationCohortFact> history = loadHistory(memberIds, windowEnd);

        Map<Long, List<RegistrationCohortFact>> byMember = new HashMap<>();
        long lateFacts = 0;
        for (RegistrationCohortFact fact : history) {
            if (fact == null || fact.getMemberId() == null || fact.getPaymentTime() == null) continue;
            byMember.computeIfAbsent(fact.getMemberId(), key -> new ArrayList<>()).add(fact);
            // 迟到事实：已计入但落库晚于成熟点。保留计数以便复算时对账。
            if (fact.getCreateTime() != null && !fact.getCreateTime().before(windowEnd)
                    && fact.getPaymentTime().before(windowEnd)) {
                lateFacts++;
            }
        }
        for (List<RegistrationCohortFact> rows : byMember.values()) {
            rows.sort(Comparator.comparing(RegistrationCohortFact::getPaymentTime)
                    .thenComparing(RegistrationCohortFact::getRegistrationId));
        }

        CohortTally tally = new CohortTally();
        Set<Long> paidDenominator = new HashSet<>();
        Set<Long> freeDenominator = new HashSet<>();
        Map<Long, Set<String>> cohortParentOrders = new HashMap<>();
        Set<Long> retainedPaid = new HashSet<>();
        Set<Long> retainedFree = new HashSet<>();
        Date lastSecondPayment = null;

        for (Map.Entry<Long, List<RegistrationCohortFact>> entry : byMember.entrySet()) {
            Long memberId = entry.getKey();
            List<RegistrationCohortFact> rows = entry.getValue();
            RegistrationCohortFact first = rows.get(0);
            // 首笔不在 cohort 当天 ⇒ 该会员属于更早的 cohort，本次不计（全历史口径的关键）。
            if (first.getPaymentTime().before(cohortStart) || !first.getPaymentTime().before(cohortEnd)) continue;

            if (isFullyRefunded(first)) {
                tally.fullRefundExcluded++;
                continue;
            }
            if (!isValidlyPaid(first)) {
                tally.cancelledOrExpiredExcluded++;
                continue;
            }
            boolean free = isFree(first);
            if (free) {
                tally.freeMembers++;
                freeDenominator.add(memberId);
            } else {
                tally.eligibleMembers++;
                paidDenominator.add(memberId);
                if (isPartiallyRefunded(first)) tally.partialRefundMembers++;
                if (hasPendingRefund(first)) tally.pendingRefundMembers++;
            }

            List<RegistrationCohortFact> dayRows = new ArrayList<>();
            Set<String> parentOrders = new HashSet<>();
            for (RegistrationCohortFact row : rows) {
                if (row.getPaymentTime().before(cohortStart) || !row.getPaymentTime().before(cohortEnd)) continue;
                dayRows.add(row);
                if (row.getParentOrderNo() != null && !row.getParentOrderNo().isEmpty()) {
                    parentOrders.add(row.getParentOrderNo());
                }
            }
            cohortParentOrders.put(memberId, parentOrders);
            // 同人同日多单：多余的行数（诊断用，不参与分母）。
            if (dayRows.size() > 1) tally.duplicateOrdersCollapsed += dayRows.size() - 1L;
            // 同单多席：同一 parent_order_no 下多于 1 行的部分（诊断用）。
            Map<String, Integer> seatsPerOrder = new HashMap<>();
            for (RegistrationCohortFact row : dayRows) {
                if (row.getParentOrderNo() == null || row.getParentOrderNo().isEmpty()) continue;
                seatsPerOrder.merge(row.getParentOrderNo(), 1, Integer::sum);
            }
            for (Integer seats : seatsPerOrder.values()) {
                if (seats != null && seats > 1) tally.multiSeatCollapsed += seats - 1L;
            }
        }

        // 分子：观察窗内另一笔不同 parent_order_no 的有效报名。
        for (Map.Entry<Long, List<RegistrationCohortFact>> entry : byMember.entrySet()) {
            Long memberId = entry.getKey();
            boolean paid = paidDenominator.contains(memberId);
            if (!paid && !freeDenominator.contains(memberId)) continue;
            Set<String> cohortOrders = cohortParentOrders.getOrDefault(memberId, Set.of());
            Set<String> secondOrders = new HashSet<>();
            boolean partial = false;
            boolean pending = false;
            Date latest = null;
            for (RegistrationCohortFact row : entry.getValue()) {
                if (row.getPaymentTime().before(cohortEnd)) continue;
                // SQL 已按 windowEnd 上界取数；服务层再守一次，避免越界行被当成「另一笔」。
                if (!row.getPaymentTime().before(windowEnd)) continue;
                if (isFullyRefunded(row) || !isValidlyPaid(row)) continue;
                String parentOrder = row.getParentOrderNo();
                if (parentOrder != null && !parentOrder.isEmpty() && cohortOrders.contains(parentOrder)) {
                    continue; // 同单多席：不是「另一笔」报名
                }
                secondOrders.add(parentOrder == null || parentOrder.isEmpty()
                        ? "registration:" + row.getRegistrationId() : parentOrder);
                if (isPartiallyRefunded(row)) partial = true;
                if (hasPendingRefund(row)) pending = true;
                latest = latest == null || row.getPaymentTime().after(latest) ? row.getPaymentTime() : latest;
            }
            if (secondOrders.isEmpty()) continue;
            if (paid) {
                retainedPaid.add(memberId);
                if (partial) tally.partialRefundRetained++;
                if (pending) tally.pendingRefundRetained++;
            } else {
                retainedFree.add(memberId);
            }
            lastSecondPayment = latest == null ? lastSecondPayment
                    : (lastSecondPayment == null || latest.after(lastSecondPayment) ? latest : lastSecondPayment);
        }

        tally.lateFacts = lateFacts;
        PostGameOutcomeReportVO.Cohort cohort = tally.toCohort(mature, retainedPaid.size(), retainedFree.size());

        Map<String, Date> watermarks = new LinkedHashMap<>();
        for (Map<String, Object> row : require(mapper.selectSourceWatermarks(),
                "来源水位查询返回 null，拒绝输出无水位报表")) {
            if (row == null) continue;
            Object name = row.get("sourceName");
            Object value = row.get("watermarkAt");
            if (name instanceof String && value instanceof Date) {
                watermarks.put((String) name, new Date(((Date) value).getTime()));
            }
        }

        Map<String, PostGameOutcomeReportVO.MetricCount> metrics = new LinkedHashMap<>();
        metrics.put(COMPLETION, metric(COMPLETION,
                mapper.selectCompletionFacts(cohortStart, windowEnd),
                "player_badge(TOPIC_FINISH_*, 通关发奖事实) + topic_route_session(status=1 已到终点) "
                        + "+ roam_session(status=1 玩家结算)",
                "服务端整局终局事实；member-day 去重。节点小游戏(game_runtime_session)不算整局完成", true, true,
                "服务端完成事实查询返回 null，拒绝把未知报成 0"));
        metrics.put(REWARD, metric(REWARD,
                mapper.selectRewardFacts(cohortStart, windowEnd),
                "cms_topic_completion_coupon_reward_log + topic_completion_xp_award + roam_poi_reward_log",
                "服务端发放事实；无发放行不计", true, false,
                "奖励发放事实查询返回 null，拒绝把未知报成 0"));
        metrics.put(PRIVATE_PHOTO, metric(PRIVATE_PHOTO,
                mapper.selectPrivatePhotoFacts(cohortStart, windowEnd),
                "community_post_media + community_post(audience=PRIVATE)",
                "默认私人；不返回 objectKey/URL", true, false,
                "私人照片事实查询返回 null，拒绝把未知报成 0"));
        metrics.put(PUBLIC_SHARE, metric(PUBLIC_SHARE,
                mapper.selectPublicShareFacts(cohortStart, windowEnd),
                "community_post(lifecycle=PUBLISHED, audience=PUBLIC)",
                "仅公开可见帖文", true, true,
                "公开分享事实查询返回 null，拒绝把未知报成 0"));
        metrics.put(RECO_CLICK, metric(RECO_CLICK,
                mapper.selectRecommendationClickFacts(cohortStart, windowEnd),
                "biz_event_log(finish_route_recommendation_click / roam_exploreday_reco_click)",
                "按 idempotency_key 去重；点击不等于报名", true, true,
                "推荐点击事实查询返回 null，拒绝把未知报成 0"));
        metrics.put(SECOND_REGISTRATION, new PostGameOutcomeReportVO.MetricCount(
                SECOND_REGISTRATION, mature ? retainedPaid.size() : 0L, mature ? retainedPaid.size() : 0L,
                mature ? retainedPaid.size() : 0L, null, lastSecondPayment,
                "cms_registration(payment_status=2) 另一笔不同 parent_order_no",
                "与主指标分子同源；未成熟时 members=0，须以 mature=false 区分而不是读成 0%",
                true, true, lastSecondPayment));
        metrics.put(CLIENT_OBSERVED, metric(CLIENT_OBSERVED,
                mapper.selectClientObservedOutcomeFacts(cohortStart, windowEnd),
                "biz_event_log(mission_complete / play_arrive_success / roam_finish / finish_route_recommendation_shown)",
                "客户端观测，非服务端终局；仅作对照，不参与分母分子", false, false,
                "客户端观测事实查询返回 null，拒绝把未知报成 0"));

        RoamRevisitReportVO roamRevisit = roamRevisitReportService == null ? null
                : roamRevisitReportService.generate(cohortDate, RoamRevisitReportVO.Metric.ROAM_REVISIT, windowDays);

        Date computedAt = new Date(clock.millis());
        return new PostGameOutcomeReportVO(cohortDate.toString(), windowDays, mature,
                cohortStart, windowEnd, computedAt, watermarks, cohort, metrics, roamRevisit,
                windowDays == 30 ? repeatSignupV1(byMember, cohortStart, cohortEnd, computedAt.toInstant()) : null);
    }

    private static PostGameOutcomeReportVO.RepeatSignupV1 repeatSignupV1(
            Map<Long, List<RegistrationCohortFact>> byMember, Date cohortStart, Date cohortEnd, Instant now) {
        PostGameOutcomeReportVO.RepeatSignupCohort paid =
                repeatSignupCohort(byMember, cohortStart, cohortEnd, now, true);
        PostGameOutcomeReportVO.RepeatSignupCohort all =
                repeatSignupCohort(byMember, cohortStart, cohortEnd, now, false);
        boolean available = paid != null && all != null;
        return new PostGameOutcomeReportVO.RepeatSignupV1(
                "repeat_signup_v1", 30,
                "首个有效报名 payment_time 为锚；第二笔时间 > 首笔且 < 首笔+30*24h；同时间不推断先后；"
                        + "逐人到窗末成熟，仅成熟会员计分子/比例，未成熟不算未复报",
                "member_id 报名账户去重；parent_order_no 区分独立订单，同单多席不重复；"
                        + "不推断代购实际参加人；现金/免费沿用 payable_amount 优先、total_amount 兜底、均空为0",
                "READ_ONLY_SQL：当前支付/报名状态与原到账退款事实重算，部分退/待审未到账不作全退；"
                        + "迟到仅按 create_time >= 个人窗末诊断；共享本响应 computedAt/sourceWatermarks，"
                        + "当前时刻之后支付不计，不可历史回放，与旧 V2 不可混比",
                available, available ? null : "相关首报名cohort或30日观察窗内有效事实缺少 parent_order_no，独立订单身份未知",
                available ? paid : null, available ? all : null);
    }

    private static PostGameOutcomeReportVO.RepeatSignupCohort repeatSignupCohort(
            Map<Long, List<RegistrationCohortFact>> byMember, Date cohortStart, Date cohortEnd,
            Instant now, boolean paidOnly) {
        long eligible = 0, mature = 0, retained = 0;
        long partial = 0, pending = 0, partialRetained = 0, pendingRetained = 0, late = 0;
        for (List<RegistrationCohortFact> rows : byMember.values()) {
            List<RegistrationCohortFact> valid = rows.stream()
                    .filter(row -> !row.getPaymentTime().toInstant().isAfter(now)
                            && isValidlyPaid(row) && !isFullyRefunded(row)
                            && (!paidOnly || !isFree(row)))
                    .toList();
            if (valid.isEmpty()) continue;
            RegistrationCohortFact first = valid.get(0);
            if (first.getPaymentTime().before(cohortStart) || !first.getPaymentTime().before(cohortEnd)) continue;
            if (first.getParentOrderNo() == null || first.getParentOrderNo().isBlank()) return null;
            Instant end = first.getPaymentTime().toInstant().plus(Duration.ofDays(30));
            boolean memberMature = !now.isBefore(end);
            eligible++;
            if (memberMature) mature++;
            if (isPartiallyRefunded(first)) partial++;
            if (hasPendingRefund(first)) pending++;
            boolean repeated = false, repeatedPartial = false, repeatedPending = false;
            for (RegistrationCohortFact row : valid) {
                Instant payment = row.getPaymentTime().toInstant();
                if (!payment.isBefore(end)) continue;
                if (row.getCreateTime() != null && !row.getCreateTime().toInstant().isBefore(end)) late++;
                // 同时刻即使订单号不同，也无法证明这是首单之后的再次报名。
                if (!row.getPaymentTime().after(first.getPaymentTime())) continue;
                String order = row.getParentOrderNo();
                if (order == null || order.isBlank()) return null;
                if (order.equals(first.getParentOrderNo())) continue;
                repeated = true;
                repeatedPartial |= isPartiallyRefunded(row);
                repeatedPending |= hasPendingRefund(row);
            }
            if (memberMature && repeated) {
                retained++;
                if (repeatedPartial) partialRetained++;
                if (repeatedPending) pendingRetained++;
            }
        }
        return new PostGameOutcomeReportVO.RepeatSignupCohort(
                paidOnly ? "全历史首个现金实付>0且有效未全退报名；第二笔同样须现金实付>0且有效未全退"
                        : "全历史首个有效未全退报名(含免费)；第二笔可付费或免费；与paid独立选首单和分母",
                eligible, mature, eligible - mature,
                mature > 0 ? Long.valueOf(retained) : null, CohortTally.rate(true, retained, mature),
                partial, pending, partialRetained, pendingRetained, late);
    }

    private static void validate(LocalDate cohortDate, int windowDays) {
        if (cohortDate == null) throw new IllegalArgumentException("cohortDate 不能为空");
        for (int supported : SUPPORTED_WINDOWS) {
            if (supported == windowDays) return;
        }
        throw new IllegalArgumentException("windowDays 仅支持 7/30/90");
    }

    /**
     * 按批取报名历史：单条 SQL 的 IN 列表不能随 cohort 规模无限增长，
     * 所以按固定批大小切分；任一批返回 null 即视为该来源不可读。
     */
    private List<RegistrationCohortFact> loadHistory(List<Long> memberIds, Date windowEnd) {
        if (memberIds.isEmpty()) return List.of();
        List<RegistrationCohortFact> all = new ArrayList<>(memberIds.size() * 2);
        for (int from = 0; from < memberIds.size(); from += MEMBER_BATCH_SIZE) {
            int to = Math.min(from + MEMBER_BATCH_SIZE, memberIds.size());
            List<RegistrationCohortFact> batch = require(
                    mapper.selectPaidRegistrationHistory(memberIds.subList(from, to), windowEnd),
                    "报名历史查询返回 null，拒绝把未知报成 0");
            all.addAll(batch);
        }
        return all;
    }

    private static <T> T require(T value, String message) {
        if (value == null) throw new IllegalStateException(message);
        return value;
    }

    private PostGameOutcomeReportVO.MetricCount metric(String code, List<OutcomeFactRow> rows,
                                                       String sourceTables, String identityBasis,
                                                       boolean serverFinal, boolean publicVisible,
                                                       String nullMessage) {
        List<OutcomeFactRow> facts = require(rows, nullMessage);
        Set<Long> members = new HashSet<>();
        long factCount = 0;
        long memberDays = 0;
        Date first = null;
        Date last = null;
        for (OutcomeFactRow row : facts) {
            if (row == null) continue;
            if (row.getMemberId() != null) members.add(row.getMemberId());
            memberDays++;
            factCount += row.getFactCount() == null ? 0 : row.getFactCount();
            first = earliest(first, row.getFirstOccurredAt());
            last = latest(last, row.getLastOccurredAt());
        }
        return new PostGameOutcomeReportVO.MetricCount(code, members.size(), memberDays, factCount,
                first, last, sourceTables, identityBasis, serverFinal, publicVisible, last);
    }

    private static Date earliest(Date left, Date right) {
        if (left == null) return right == null ? null : new Date(right.getTime());
        if (right == null) return left;
        return right.before(left) ? new Date(right.getTime()) : left;
    }

    private static Date latest(Date left, Date right) {
        if (left == null) return right == null ? null : new Date(right.getTime());
        if (right == null) return left;
        return right.after(left) ? new Date(right.getTime()) : left;
    }

    /** 已支付且未取消/未过期。 */
    static boolean isValidlyPaid(RegistrationCohortFact fact) {
        return Integer.valueOf(2).equals(fact.getPaymentStatus())
                && Integer.valueOf(2).equals(fact.getRegistrationStatus());
    }

    /** 实付 ≤ 0 ⇒ 免单（含纯积分 0 现金），与付费分母分开。 */
    static boolean isFree(RegistrationCohortFact fact) {
        return paidBase(fact).signum() <= 0;
    }

    static BigDecimal paidBase(RegistrationCohortFact fact) {
        if (fact.getPayableAmount() != null) return fact.getPayableAmount();
        if (fact.getTotalAmount() != null) return fact.getTotalAmount();
        return BigDecimal.ZERO;
    }

    /**
     * 全退 = 已退款(payment_status=4) <b>或</b> 退款已确认到账且累计退款 ≥ 实付。
     *
     * <p>两条路取并集，因为退款到账事实可能先于 payment_status 翻转；
     * payout_status in (0,2,3,5,6) 钱还没确认到用户，不算已退。
     */
    static boolean isFullyRefunded(RegistrationCohortFact fact) {
        if (Integer.valueOf(4).equals(fact.getPaymentStatus())) return true;
        BigDecimal refunded = fact.getSettledRefundAmount();
        if (Boolean.TRUE.equals(fact.getHasSettledRefund()) && refunded != null) {
            BigDecimal base = paidBase(fact);
            return base.signum() > 0 && refunded.compareTo(base) >= 0;
        }
        return false;
    }

    static boolean isPartiallyRefunded(RegistrationCohortFact fact) {
        if (isFullyRefunded(fact)) return false;
        BigDecimal refunded = fact.getSettledRefundAmount();
        return refunded != null && refunded.signum() > 0;
    }

    static boolean hasPendingRefund(RegistrationCohortFact fact) {
        return fact.getPendingRefundCount() != null && fact.getPendingRefundCount() > 0;
    }

    /** cohort 累加器：只收数字，不解释口径。 */
    private static final class CohortTally {
        private long eligibleMembers;
        private long freeMembers;
        private long fullRefundExcluded;
        private long cancelledOrExpiredExcluded;
        private long partialRefundMembers;
        private long pendingRefundMembers;
        private long duplicateOrdersCollapsed;
        private long multiSeatCollapsed;
        private long partialRefundRetained;
        private long pendingRefundRetained;
        private long lateFacts;

        private PostGameOutcomeReportVO.Cohort toCohort(boolean mature, int retainedPaid, int retainedFree) {
            long notMature = mature ? 0 : eligibleMembers + freeMembers;
            // 分子只在有分母时才有定义。分母为 0（本 cohort 无人达标、或首笔都落在更早的 cohort）
            // 表示「没有这个比率」，不是「留存 0 人」；写 0 会把未知冒充成已知。
            Long paidRetained = mature && eligibleMembers > 0 ? Long.valueOf(retainedPaid) : null;
            Long freeRetained = mature && freeMembers > 0 ? Long.valueOf(retainedFree) : null;
            return new PostGameOutcomeReportVO.Cohort(
                    eligibleMembers, freeMembers, fullRefundExcluded, cancelledOrExpiredExcluded,
                    partialRefundMembers, pendingRefundMembers,
                    duplicateOrdersCollapsed, multiSeatCollapsed,
                    paidRetained, freeRetained,
                    mature ? partialRefundRetained : 0, mature ? pendingRefundRetained : 0,
                    lateFacts, notMature,
                    rate(mature, retainedPaid, eligibleMembers),
                    rate(mature, retainedFree, freeMembers));
        }

        private static BigDecimal rate(boolean mature, long retained, long denominator) {
            if (!mature || denominator <= 0) return null;
            return BigDecimal.valueOf(retained)
                    .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
        }
    }
}
