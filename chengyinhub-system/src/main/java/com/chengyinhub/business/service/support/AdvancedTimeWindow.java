package com.chengyinhub.business.service.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server calendar for an advanced-play node's daily opening window ("HH:mm"–"HH:mm", may cross
 * midnight), evaluated in the server zone. The app never computes the next opening itself; it reads
 * openNow / nextOpenAt / nextCloseAt from here and may schedule a local reminder for nextOpenAt.
 * Same rule as AdvancedGameRuntimeServiceImpl#requireWithinTimeWindow.
 */
public final class AdvancedTimeWindow {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private AdvancedTimeWindow() {}

    /** Minute of day for "HH:mm", or -1 when malformed. */
    public static int minuteOfDay(String text) {
        if (text == null || text.length() != 5 || text.charAt(2) != ':') return -1;
        try {
            int hour = Integer.parseInt(text.substring(0, 2)), minute = Integer.parseInt(text.substring(3));
            return hour < 0 || hour > 23 || minute < 0 || minute > 59 ? -1 : hour * 60 + minute;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * @throws IllegalArgumentException when the window is malformed (fail closed, as start() does)
     */
    public static Map<String, Object> compute(String openFrom, String openTo, long nowMillis) {
        int from = minuteOfDay(openFrom), to = minuteOfDay(openTo);
        if (from < 0 || to < 0 || from == to) throw new IllegalArgumentException("时段限定配置无效");
        ZonedDateTime now = Instant.ofEpochMilli(nowMillis).atZone(ZONE);
        int minute = now.getHour() * 60 + now.getMinute();
        boolean open = from < to ? minute >= from && minute < to : minute >= from || minute < to;
        ZonedDateTime nextOpen = at(now, from);
        if (!nextOpen.isAfter(now)) nextOpen = nextOpen.plusDays(1);
        ZonedDateTime nextClose = at(now, to);
        if (!nextClose.isAfter(now)) nextClose = nextClose.plusDays(1);
        if (!open && !nextClose.isAfter(nextOpen)) nextClose = nextClose.plusDays(1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", true);
        out.put("notificationProvider", "LOCAL_ONLY");
        out.put("timeZone", ZONE.getId());
        out.put("serverNow", nowMillis);
        out.put("openNow", open);
        out.put("nextOpenAt", nextOpen.toInstant().toEpochMilli());
        out.put("nextCloseAt", nextClose.toInstant().toEpochMilli());
        out.put("openFrom", openFrom);
        out.put("openTo", openTo);
        out.put("windowVersion", version(openFrom + "-" + openTo + "@" + ZONE.getId()));
        return out;
    }

    private static ZonedDateTime at(ZonedDateTime day, int minuteOfDay) {
        return day.with(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60));
    }

    private static String version(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("tw1-");
            for (int i = 0; i < 8; i++) out.append(String.format("%02x", digest[i]));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
