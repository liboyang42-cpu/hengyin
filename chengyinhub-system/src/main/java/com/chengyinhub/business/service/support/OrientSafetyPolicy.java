package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.OrientSafetyRule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 城市定向安全闸(Doc2 F1d)。纯函数,不读库。
 *
 * <ul>
 *   <li><b>保险不强制</b>:insurance_required 恒为 0,任何情况下都不是阻断项。</li>
 *   <li><b>夜间 / 天气 / 年龄与监护</b>是真阻断:
 *     夜间场次只允许已确认成年的成员(未成年一律不得进入,监护也不能放行;生日未知同样拒绝,
 *     与 CircleExploreServiceImpl 夜间主题同口径);白天场次未成年需监护核验。</li>
 *   <li><b>SOS 永远可用</b>:{@link #sosAllowed()} 不接受信誉、处罚、黑名单等任何输入。</li>
 * </ul>
 */
public final class OrientSafetyPolicy {

    public static final String WEATHER_BLOCKED = "WEATHER_BLOCKED";
    public static final String MINOR_NIGHT_FORBIDDEN = "MINOR_NIGHT_FORBIDDEN";
    public static final String AGE_UNVERIFIED_NIGHT = "AGE_UNVERIFIED_NIGHT";
    public static final String GUARDIAN_REQUIRED = "GUARDIAN_REQUIRED";

    public static final int DEFAULT_NIGHT_START = 20 * 60;
    public static final int DEFAULT_NIGHT_END = 6 * 60;
    public static final int ADULT_AGE = 18;
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private OrientSafetyPolicy() {
    }

    /** 安全判定结果。blockers 为空 = 放行。 */
    public static final class Decision {
        public final List<Map<String, String>> blockers;
        public final boolean night;
        public final Boolean minor;

        Decision(List<Map<String, String>> blockers, boolean night, Boolean minor) {
            this.blockers = blockers;
            this.night = night;
            this.minor = minor;
        }

        public boolean allowed() { return blockers.isEmpty(); }

        /** 保险不强制:恒 false。 */
        public boolean insuranceRequired() { return false; }

        public boolean sosAvailable() { return sosAllowed(); }
    }

    /** SOS 不受信誉限制:签名里刻意没有任何信誉/处罚参数,恒为 true。 */
    public static boolean sosAllowed() {
        return true;
    }

    /**
     * @param rule 主题规则,null = 默认规则(20:00-06:00 为夜间,无天气停玩)
     * @param sessionStart 场次开始;自玩传当前时刻
     * @param sessionEnd 场次结束;null 视为与开始同一刻
     * @param birthday 成员生日,null = 未知
     * @param guardianVerified 监护核验是否已通过(当前仓内没有监护数据源,调用方恒传 false)
     */
    public static Decision evaluate(OrientSafetyRule rule, Date sessionStart, Date sessionEnd, Date now,
                                    Date birthday, boolean guardianVerified) {
        List<Map<String, String>> blockers = new ArrayList<>();
        if (rule != null && Integer.valueOf(1).equals(rule.getWeatherBlock())
                && (rule.getWeatherBlockUntil() == null || now.before(rule.getWeatherBlockUntil()))) {
            String reason = rule.getWeatherReason() == null || rule.getWeatherReason().trim().isEmpty()
                    ? "天气预警,本场暂停" : rule.getWeatherReason().trim();
            blockers.add(blocker(WEATHER_BLOCKED, reason));
        }
        int nightStart = rule == null || rule.getNightStartMinute() == null ? DEFAULT_NIGHT_START : rule.getNightStartMinute();
        int nightEnd = rule == null || rule.getNightEndMinute() == null ? DEFAULT_NIGHT_END : rule.getNightEndMinute();
        Date start = sessionStart == null ? now : sessionStart;
        Date end = sessionEnd == null || sessionEnd.before(start) ? start : sessionEnd;
        boolean night = overlapsNight(start, end, nightStart, nightEnd);

        Boolean minor = null;
        if (birthday != null) {
            LocalDate born = Instant.ofEpochMilli(birthday.getTime()).atZone(ZONE).toLocalDate();
            LocalDate at = Instant.ofEpochMilli(start.getTime()).atZone(ZONE).toLocalDate();
            minor = Period.between(born, at).getYears() < ADULT_AGE;
        }
        if (night) {
            if (minor == null) {
                blockers.add(blocker(AGE_UNVERIFIED_NIGHT, "夜间场次仅面向已确认成年的成员,请先完善生日"));
            } else if (minor) {
                blockers.add(blocker(MINOR_NIGHT_FORBIDDEN, "未成年人不能参加夜间场次"));
            }
        } else if (Boolean.TRUE.equals(minor) && !guardianVerified) {
            blockers.add(blocker(GUARDIAN_REQUIRED, "未成年人参加需要监护人核验"));
        }
        return new Decision(blockers, night, minor);
    }

    /** 区间 [start,end] 是否与任一夜间窗口相交。start==end 视为一个时刻。窗口起止相同 = 无夜间。 */
    static boolean overlapsNight(Date start, Date end, int nightStartMinute, int nightEndMinute) {
        if (nightStartMinute == nightEndMinute) return false;
        ZonedDateTime s = Instant.ofEpochMilli(start.getTime()).atZone(ZONE);
        ZonedDateTime e = Instant.ofEpochMilli(Math.max(end.getTime(), start.getTime() + 1)).atZone(ZONE);
        LocalDate day = s.toLocalDate().minusDays(1);
        LocalDate lastDay = e.toLocalDate();
        while (!day.isAfter(lastDay)) {
            ZonedDateTime ns = day.atStartOfDay(ZONE).plusMinutes(nightStartMinute);
            ZonedDateTime ne = (nightStartMinute < nightEndMinute ? day : day.plusDays(1))
                    .atStartOfDay(ZONE).plusMinutes(nightEndMinute);
            if (s.isBefore(ne) && e.isAfter(ns)) return true;
            day = day.plusDays(1);
        }
        return false;
    }

    private static Map<String, String> blocker(String code, String message) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        return m;
    }
}
