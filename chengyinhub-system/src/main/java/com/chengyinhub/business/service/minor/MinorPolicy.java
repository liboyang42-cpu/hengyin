package com.chengyinhub.business.service.minor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * DOC2 F7 未成年人保护政策常量。政策文本变更时必须升 {@link #POLICY_VERSION}：
 * 旧版本下的授权不再被新版本认可，需监护人重新确认。
 */
public final class MinorPolicy {
    public static final String POLICY_VERSION = "minor-2026-10-22";
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 「白天」窗口 [08:00, 18:00)；数值待业主确认，改动只需改这里。 */
    public static final LocalTime DAYTIME_START = LocalTime.of(8, 0);
    public static final LocalTime DAYTIME_END = LocalTime.of(18, 0);
    public static final int MAX_VALID_DAYS = 365;
    public static final int INVITE_TTL_HOURS = 24;

    private MinorPolicy() { }

    /** ums_member.birthday → 北京时间日期；兼容 java.sql.Date（其 toInstant 会抛错）。 */
    public static LocalDate toLocalDate(Date date) {
        if (date == null) return null;
        if (date instanceof java.sql.Date sqlDate) return sqlDate.toLocalDate();
        return date.toInstant().atZone(ZONE).toLocalDate();
    }
}
