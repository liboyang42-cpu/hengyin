package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.*;

/** Server wall-clock policy, shared by the opening gate and local-reminder projection. */
public final class TimeWindowSchedule {
    private TimeWindowSchedule() { }

    public static ZoneId zone(JsonNode config) {
        if (!config.has("timeZone")) return ZoneId.systemDefault(); // preserve legacy server calendar
        JsonNode value = config.get("timeZone");
        if (!value.isTextual() || !ZoneId.getAvailableZoneIds().contains(value.asText())) {
            throw new IllegalArgumentException("开放时区须为有效 IANA 时区");
        }
        return ZoneId.of(value.asText());
    }

    private static LocalTime time(JsonNode cfg, String key) {
        String value = cfg.path(key).asText("");
        if (!value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) {
            throw new IllegalStateException("时段限定配置无效，请商家修正开放时段后再试");
        }
        return LocalTime.parse(value);
    }

    public static boolean isOpen(JsonNode cfg, Instant now) {
        if (!cfg.path("enabled").asBoolean(false)) return true;
        return open(time(cfg, "openFrom"), time(cfg, "openTo"), now.atZone(zone(cfg)).toLocalTime());
    }

    private static boolean open(LocalTime from, LocalTime to, LocalTime now) {
        if (from.equals(to)) throw new IllegalStateException("开放时段的起止不能相同");
        return from.isBefore(to) ? !now.isBefore(from) && now.isBefore(to)
                : !now.isBefore(from) || now.isBefore(to);
    }

    public static Map<String, Object> view(JsonNode cfg, Instant now) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean enabled = cfg.path("enabled").asBoolean(false);
        result.put("enabled", enabled);
        result.put("serverNow", now.toEpochMilli());
        result.put("notificationProvider", "LOCAL_ONLY");
        if (!enabled) {
            result.put("openNow", true);
            result.put("nextOpenAt", null);
            result.put("nextCloseAt", null);
            result.put("windowVersion", sha256("time-window-v1\ndisabled"));
            return result;
        }
        ZoneId zone = zone(cfg);
        LocalTime from = time(cfg, "openFrom"), to = time(cfg, "openTo");
        boolean current = open(from, to, now.atZone(zone).toLocalTime());
        result.put("openFrom", from.toString());
        result.put("openTo", to.toString());
        result.put("timeZone", zone.getId());
        result.put("openNow", current);
        result.put("windowVersion", sha256("time-window-v1\n" + zone.getId() + "\n" + from + "\n" + to));
        SortedSet<Instant> boundaries = new TreeSet<>();
        LocalDate today = now.atZone(zone).toLocalDate();
        ZoneRules rules = zone.getRules();
        // All valid offsets are needed: a fall-back hour may contain two distinct openings.
        for (int day = -1; day <= 5; day++) {
            for (LocalTime boundary : Arrays.asList(from, to)) {
                LocalDateTime local = today.plusDays(day).atTime(boundary);
                List<ZoneOffset> offsets = rules.getValidOffsets(local);
                if (offsets.isEmpty()) boundaries.add(rules.getTransition(local).getInstant());
                else for (ZoneOffset offset : offsets) boundaries.add(local.toInstant(offset));
            }
        }
        // A gap/overlap transition itself may change wall-clock membership.
        Instant horizon = now.plusSeconds(7 * 86400L);
        ZoneOffsetTransition transition = rules.nextTransition(now.minusNanos(1));
        while (transition != null && transition.getInstant().isBefore(horizon)) {
            boundaries.add(transition.getInstant());
            transition = rules.nextTransition(transition.getInstant().plusNanos(1));
        }
        Instant nextOpen = null, nextClose = null;
        boolean previous = current;
        for (Instant candidate : boundaries) {
            if (!candidate.isAfter(now)) continue;
            boolean after = open(from, to, candidate.atZone(zone).toLocalTime());
            if (!previous && after && nextOpen == null) nextOpen = candidate;
            if (previous && !after && nextClose == null) nextClose = candidate;
            previous = after;
        }
        if (nextOpen == null || nextClose == null) throw new IllegalStateException("无法计算下一开放时段");
        result.put("nextOpenAt", nextOpen.toEpochMilli());
        result.put("nextCloseAt", nextClose.toEpochMilli());
        return result;
    }

    public static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
