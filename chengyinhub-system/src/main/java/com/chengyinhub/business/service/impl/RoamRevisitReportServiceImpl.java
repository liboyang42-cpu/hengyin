package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.RoamRevisitFact;
import com.chengyinhub.business.domain.vo.RoamRevisitReportVO;
import com.chengyinhub.business.mapper.RoamRevisitReportMapper;
import com.chengyinhub.business.service.IRoamRevisitReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class RoamRevisitReportServiceImpl implements IRoamRevisitReportService {
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");
    static final String SHOP_SCOPE = "cohort=观察日 visited_at 有 roam_shop_visit 的去重成员；复访=后续另一 session 同 source-scoped 商家";
    static final String SHOP_IDENTITY = "source_type+source_id (source-scoped; no canonical bridge)";
    static final String ROAM_SCOPE = "cohort=started_at 落在观察日的任意 roam_session 去重成员；复访=后续 started_at 落窗的另一 roam_session；不区分是否结算";
    static final String ROAM_IDENTITY = "roam_session.id (started_at 事实，不区分是否结算)";

    private final RoamRevisitReportMapper reportMapper;
    private final Clock clock;

    @Autowired
    public RoamRevisitReportServiceImpl(RoamRevisitReportMapper reportMapper) {
        this(reportMapper, Clock.system(REPORT_ZONE));
    }

    RoamRevisitReportServiceImpl(RoamRevisitReportMapper reportMapper, Clock clock) {
        this.reportMapper = Objects.requireNonNull(reportMapper, "reportMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(readOnly = true)
    public RoamRevisitReportVO generate(LocalDate cohortDate, RoamRevisitReportVO.Metric metric, int windowDays) {
        validate(cohortDate, metric, windowDays);

        ZoneId zone = clock.getZone();
        Date from = Date.from(cohortDate.atStartOfDay(zone).toInstant());
        Date observationEnd = Date.from(cohortDate.plusDays(1).atStartOfDay(zone).toInstant());
        Date to = Date.from(cohortDate.plusDays(windowDays + 1L).atStartOfDay(zone).toInstant());

        Date dataCutoffAt;
        List<RoamRevisitFact> facts;
        if (metric == RoamRevisitReportVO.Metric.SHOP_REVISIT) {
            dataCutoffAt = reportMapper.selectShopDataCutoffAt();
            facts = reportMapper.selectShopVisitFacts(from, to);
        } else {
            dataCutoffAt = reportMapper.selectRoamDataCutoffAt();
            facts = reportMapper.selectRoamSessionFacts(from, to);
        }
        if (dataCutoffAt == null) {
            throw new IllegalStateException(metric + " 数据截止时间为空，拒绝把未知数据状态报告为 0");
        }
        if (facts == null) {
            throw new IllegalStateException(metric + " 查询返回 null，拒绝把查询失败报告为 0");
        }

        Map<Long, MemberCohort> cohorts = buildCohorts(facts, observationEnd, metric);
        // 成熟度是观察窗是否已经走完的时钟事实；最新业务行只作可观测水位，不能反向改写历史成熟度。
        boolean mature = !clock.instant().isBefore(to.toInstant());
        int eligible = mature ? cohorts.size() : 0;
        int notMature = mature ? 0 : cohorts.size();
        int revisited = mature ? countRevisited(cohorts, facts, observationEnd, metric) : 0;

        return new RoamRevisitReportVO(
                cohortDate, metric, windowDays, eligible, revisited, notMature,
                dataCutoffAt, Date.from(clock.instant()),
                metric == RoamRevisitReportVO.Metric.SHOP_REVISIT ? SHOP_SCOPE : ROAM_SCOPE,
                metric == RoamRevisitReportVO.Metric.SHOP_REVISIT ? SHOP_IDENTITY : ROAM_IDENTITY);
    }

    private static void validate(LocalDate cohortDate, RoamRevisitReportVO.Metric metric, int windowDays) {
        if (cohortDate == null) throw new IllegalArgumentException("cohortDate 不能为空");
        if (metric == null) throw new IllegalArgumentException("metric 不能为空");
        if (windowDays != 7 && windowDays != 30 && windowDays != 90) {
            throw new IllegalArgumentException("windowDays 仅支持 7/30/90");
        }
    }

    private static Map<Long, MemberCohort> buildCohorts(List<RoamRevisitFact> facts, Date observationEnd,
                                                         RoamRevisitReportVO.Metric metric) {
        Map<Long, MemberCohort> cohorts = new HashMap<>();
        for (RoamRevisitFact fact : facts) {
            if (!validFact(fact) || !fact.getOccurredAt().before(observationEnd)) continue;
            MemberCohort cohort = cohorts.computeIfAbsent(fact.getMemberId(), key -> new MemberCohort());
            cohort.observationSessions.add(fact.getSessionId());
            if (metric == RoamRevisitReportVO.Metric.SHOP_REVISIT) {
                if (fact.getSourceType() == null || fact.getSourceId() == null) continue;
                cohort.shopIdentities.add(new ShopIdentity(fact.getSourceType(), fact.getSourceId()));
            }
        }
        return cohorts;
    }

    private static int countRevisited(Map<Long, MemberCohort> cohorts, List<RoamRevisitFact> facts,
                                      Date observationEnd, RoamRevisitReportVO.Metric metric) {
        Set<Long> revisitedMembers = new HashSet<>();
        for (RoamRevisitFact fact : facts) {
            if (!validFact(fact) || fact.getOccurredAt().before(observationEnd)) continue;
            MemberCohort cohort = cohorts.get(fact.getMemberId());
            if (cohort == null || cohort.observationSessions.contains(fact.getSessionId())) continue;
            if (metric == RoamRevisitReportVO.Metric.ROAM_REVISIT
                    || cohort.shopIdentities.contains(new ShopIdentity(fact.getSourceType(), fact.getSourceId()))) {
                revisitedMembers.add(fact.getMemberId());
            }
        }
        return revisitedMembers.size();
    }

    private static boolean validFact(RoamRevisitFact fact) {
        return fact != null && fact.getMemberId() != null && fact.getSessionId() != null
                && fact.getOccurredAt() != null;
    }

    private static final class MemberCohort {
        private final Set<Long> observationSessions = new HashSet<>();
        private final Set<ShopIdentity> shopIdentities = new HashSet<>();
    }

    private static final class ShopIdentity {
        private final Integer sourceType;
        private final Long sourceId;

        private ShopIdentity(Integer sourceType, Long sourceId) {
            this.sourceType = sourceType;
            this.sourceId = sourceId;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof ShopIdentity)) return false;
            ShopIdentity that = (ShopIdentity) value;
            return Objects.equals(sourceType, that.sourceType) && Objects.equals(sourceId, that.sourceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sourceType, sourceId);
        }
    }
}
