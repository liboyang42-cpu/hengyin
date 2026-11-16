package com.chengyinhub.business.pointsgov;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.citycoin.CityCoinService;
import com.chengyinhub.business.mapper.PointsGovernanceMapper;
import com.chengyinhub.business.service.support.PointsValuePolicy;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.service.ISysConfigService;

/**
 * R7 MON-09: points stop being an unbudgeted cash liability.
 *
 * <ul>
 *   <li>Per-member daily cap per award source ({@code points_award_cap}, keyed by the ledger's event type).</li>
 *   <li>Monthly budget per programme ({@code points_programme_budget}); usage advances with a conditional UPDATE
 *       inside the award transaction, so concurrent awards cannot overshoot it and a rolled-back award releases it.</li>
 *   <li>Dashboard of points issued, redeemed and outstanding liability in yuan.</li>
 * </ul>
 *
 * <p>{@link #admit} must run inside the award transaction after the member row is locked
 * ({@code UmsMemberMapper.selectPointsForUpdate}); the lock serialises the per-member daily sum.
 * Mode {@code points.governance.mode}: ENFORCE (default), SHADOW (log only), OFF. Missing tables fail open
 * (the award goes through as before and an error is logged) so the deploy order does not matter.
 */
@Service
public class PointsGovernanceService {

    public static final String CONFIG_MODE = "points.governance.mode";
    static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT);
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Logger log = LoggerFactory.getLogger(PointsGovernanceService.class);

    public enum Mode { ENFORCE, SHADOW, OFF }

    /** ADMITTED, UNGOVERNED (no active cap row or mode OFF), DAILY_CAP, MONTHLY_BUDGET. */
    public record Admission(boolean admitted, String outcome, String programme) {
        static Admission yes(String outcome, String programme) { return new Admission(true, outcome, programme); }
        static Admission no(String outcome, String programme) { return new Admission(false, outcome, programme); }
    }

    @Autowired(required = false)
    private PointsGovernanceMapper mapper;
    @Autowired(required = false)
    private ISysConfigService configService;
    @Autowired(required = false)
    private PointsValuePolicy valuePolicy;
    @Autowired(required = false)
    private CityCoinService cityCoinService;
    private Clock clock = Clock.system(ZONE);

    public PointsGovernanceService() {
    }

    /** For tests. */
    public PointsGovernanceService(PointsGovernanceMapper mapper, ISysConfigService configService,
                                   PointsValuePolicy valuePolicy, CityCoinService cityCoinService) {
        this.mapper = mapper;
        this.configService = configService;
        this.valuePolicy = valuePolicy;
        this.cityCoinService = cityCoinService;
    }

    private PointsValuePolicy value() {
        return valuePolicy == null ? PointsValuePolicy.codeDefaults() : valuePolicy;
    }

    void setClock(Clock clock) { this.clock = clock; }

    public Mode mode() {
        String raw = null;
        try {
            raw = configService == null ? null : configService.selectConfigByKey(CONFIG_MODE);
        } catch (RuntimeException e) {
            log.warn("points governance mode unreadable, using ENFORCE: {}", e.toString());
        }
        if (raw == null || raw.isBlank()) return Mode.ENFORCE;
        try {
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Mode.ENFORCE;
        }
    }

    /**
     * Decides whether {@code points} from award source {@code eventType} may be credited to {@code memberId}, and
     * when admitted books them against the programme's monthly budget. Call under the member row lock, in the
     * award transaction, right before the balance update.
     */
    public Admission admit(Long memberId, Long eventType, int points) {
        if (memberId == null || eventType == null || points <= 0) return Admission.yes("UNGOVERNED", null);
        Mode mode = mode();
        if (mode == Mode.OFF || mapper == null) return Admission.yes("UNGOVERNED", null);
        try {
            return admitGoverned(memberId, eventType, points, mode);
        } catch (DataAccessException e) {
            log.error("points governance unavailable (migration_20261115_points_governance.sql applied?); awarding "
                    + "member={} source={} points={} without caps: {}", memberId, eventType, points, e.toString());
            return Admission.yes("UNGOVERNED", null);
        }
    }

    private Admission admitGoverned(Long memberId, Long eventType, int points, Mode mode) {
        Map<String, Object> cap = mapper.selectCap(eventType);
        if (cap == null || !"0".equals(String.valueOf(cap.get("status")))) return Admission.yes("UNGOVERNED", null);
        String programme = (String) cap.get("programme");
        Long dailyCap = asLong(cap.get("dailyCapPoints"));
        if (dailyCap != null) {
            Date dayStart = Date.from(LocalDate.now(clock).atStartOfDay(ZONE).toInstant());
            long used = mapper.sumMemberSourceSince(memberId, eventType, dayStart);
            if (used + points > dailyCap) {
                if (mode == Mode.ENFORCE) {
                    log.info("points daily cap reached member={} source={} used={} cap={} points={}",
                            memberId, eventType, used, dailyCap, points);
                    return Admission.no("DAILY_CAP", programme);
                }
                log.warn("[shadow] points daily cap would block member={} source={} used={} cap={} points={}",
                        memberId, eventType, used, dailyCap, points);
            }
        }
        if (programme == null || programme.isBlank()) return Admission.yes("ADMITTED", null);
        Map<String, Object> budgetRow = mapper.selectBudget(programme);
        Long budget = budgetRow != null && "0".equals(String.valueOf(budgetRow.get("status")))
                ? asLong(budgetRow.get("monthlyBudgetPoints")) : null;
        String period = YearMonth.now(clock).format(PERIOD);
        Date now = Date.from(clock.instant());
        mapper.ensureUsage(programme, period, now);
        if (mapper.consumeBudget(programme, period, points, budget, now) == 1) return Admission.yes("ADMITTED", programme);
        if (mode == Mode.ENFORCE) {
            log.info("points monthly budget reached programme={} period={} budget={} points={}", programme, period, budget, points);
            return Admission.no("MONTHLY_BUDGET", programme);
        }
        log.warn("[shadow] points monthly budget would block programme={} period={} budget={} points={}",
                programme, period, budget, points);
        mapper.consumeBudget(programme, period, points, null, now);
        return Admission.yes("ADMITTED", programme);
    }

    // ---- admin console ----

    /** Dashboard of one month (yyyy-MM; blank = current month). */
    public Map<String, Object> dashboard(String period) {
        YearMonth month = parsePeriod(period);
        Date from = Date.from(month.atDay(1).atStartOfDay(ZONE).toInstant());
        Date to = Date.from(month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant());
        long issued = mapper.sumPointsDetail(1L, from, to);
        long redeemed = mapper.sumPointsDetail(2L, from, to);
        long outstanding = mapper.sumOutstandingPoints();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", month.format(PERIOD));
        out.put("mode", mode().name());
        out.put("yuanPerPoint", value().rate());
        out.put("issuedPoints", issued);
        out.put("issuedYuan", value().yuanOf(issued));
        out.put("redeemedPoints", redeemed);
        out.put("redeemedYuan", value().yuanOf(redeemed));
        out.put("outstandingPoints", outstanding);
        out.put("outstandingLiabilityYuan", value().yuanOf(outstanding));
        List<Map<String, Object>> programmes = mapper.selectBudgetsWithUsage(month.format(PERIOD));
        for (Map<String, Object> p : programmes) {
            Long budget = asLong(p.get("monthlyBudgetPoints"));
            long used = asLong(p.get("issuedPoints")) == null ? 0L : asLong(p.get("issuedPoints"));
            p.put("issuedYuan", value().yuanOf(used));
            p.put("budgetYuan", budget == null ? null : value().yuanOf(budget));
            p.put("remainingPoints", budget == null ? null : Math.max(0L, budget - used));
            p.put("budgetSet", budget != null);
        }
        out.put("programmes", programmes);
        out.put("caps", mapper.selectCaps());
        if (cityCoinService != null) out.put("cityCoin", cityCoinService.dashboard(from, to));
        return out;
    }

    public int updateCap(Long eventType, Integer dailyCapPoints, String status, String updateBy) {
        if (eventType == null) throw new ServiceException("缺少积分来源");
        if (dailyCapPoints != null && (dailyCapPoints < 0 || dailyCapPoints > 1_000_000)) {
            throw new ServiceException("每日上限必须是 0 到 1000000 的整数，留空表示不设上限");
        }
        String s = normaliseStatus(status);
        int rows = mapper.updateCap(eventType, dailyCapPoints, s, updateBy, Date.from(clock.instant()));
        if (rows != 1) throw new ServiceException("积分来源不存在：" + eventType);
        return rows;
    }

    public int updateBudget(String programme, Long monthlyBudgetPoints, String status, String updateBy) {
        if (programme == null || programme.isBlank()) throw new ServiceException("缺少奖励项目");
        if (monthlyBudgetPoints != null && (monthlyBudgetPoints < 0 || monthlyBudgetPoints > 10_000_000_000L)) {
            throw new ServiceException("月度预算必须是 0 到 10000000000 的整数，留空表示未设预算");
        }
        String s = normaliseStatus(status);
        int rows = mapper.updateBudget(programme.trim(), monthlyBudgetPoints, s, updateBy, Date.from(clock.instant()));
        if (rows != 1) throw new ServiceException("奖励项目不存在：" + programme);
        return rows;
    }

    YearMonth parsePeriod(String period) {
        if (period == null || period.isBlank()) return YearMonth.now(clock);
        try {
            return YearMonth.parse(period.trim(), PERIOD);
        } catch (DateTimeParseException e) {
            throw new ServiceException("月份格式应为 yyyy-MM");
        }
    }

    private static String normaliseStatus(String status) {
        if (status == null || status.isBlank()) return "0";
        String s = status.trim();
        if (!"0".equals(s) && !"1".equals(s)) throw new ServiceException("状态只能是 0(生效) 或 1(停用)");
        return s;
    }

    static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        if (v instanceof BigDecimal b) return b.longValue();
        try { return Long.parseLong(v.toString().trim()); } catch (NumberFormatException e) { return null; }
    }
}
