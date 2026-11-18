package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.common.constant.ProductType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * F13 票夹:一张票什么时候能用、用到什么时候、通关标签(OR-011 / OR-012=B)。
 *
 * <p>票夹和订单详情共用这一处口径,客户端不再各算各的:
 * <ul>
 *   <li>usableFrom:主题票取主题开始日 00:00;场次票取场次开始时刻(精确到分钟);通行证类(有 expiresAt / ③ 探索票)为 null = 立即可用。</li>
 *   <li>usableUntil:expiresAt 优先;否则主题结束日 23:59:59;否则场次 endDate;都没有为 null = 一直可用。</li>
 *   <li>usableState:按服务端此刻判 USABLE / UPCOMING / EXPIRED。</li>
 *   <li>clearState:只有城市定向有通关概念,CLEARED / NOT_CLEARED;其余 NONE。</li>
 * </ul>
 * 日界统一按 Asia/Shanghai。只读,不改任何事实。
 */
public final class TicketUsableWindow {
    public static final String USABLE = "USABLE";
    public static final String UPCOMING = "UPCOMING";
    public static final String EXPIRED = "EXPIRED";

    public static final String CLEARED = "CLEARED";
    public static final String NOT_CLEARED = "NOT_CLEARED";
    public static final String NONE = "NONE";

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TicketUsableWindow() { }

    /** 给一行订单盖上 usableFrom / usableUntil / usableState / clearState。row 的 cmsTopic / cmsActivity 需已挂好。 */
    public static void stamp(CmsRegistration row, Date now) {
        if (row == null) return;
        Instant from = usableFrom(row);
        Instant until = usableUntil(row);
        row.setUsableFrom(format(from));
        row.setUsableUntil(format(until));
        row.setUsableState(state(from, until, now == null ? Instant.now() : now.toInstant()));
        row.setClearState(clearState(row));
    }

    static boolean isPass(CmsRegistration row) {
        return row.getExpiresAt() != null || ExploreTicketProgress.isExplorePass(row);
    }

    static Instant usableFrom(CmsRegistration row) {
        if (isPass(row)) return null;
        if (Long.valueOf(1L).equals(row.getOwnerType())) {
            CmsTopic topic = row.getCmsTopic();
            return topic == null ? null : startOfDay(topic.getStartDate());
        }
        if (Long.valueOf(2L).equals(row.getOwnerType())) {
            CmsActivity activity = row.getCmsActivity();
            return activity == null || activity.getStartDate() == null ? null : activity.getStartDate().toInstant();
        }
        return null;
    }

    static Instant usableUntil(CmsRegistration row) {
        if (row.getExpiresAt() != null) return row.getExpiresAt().toInstant();
        if (Long.valueOf(1L).equals(row.getOwnerType())) {
            CmsTopic topic = row.getCmsTopic();
            return topic == null ? null : endOfDay(topic.getEndDate());
        }
        if (Long.valueOf(2L).equals(row.getOwnerType())) {
            CmsActivity activity = row.getCmsActivity();
            return activity == null || activity.getEndDate() == null ? null : activity.getEndDate().toInstant();
        }
        return null;
    }

    static String state(Instant from, Instant until, Instant now) {
        if (until != null && now.isAfter(until)) return EXPIRED;
        if (from != null && now.isBefore(from)) return UPCOMING;
        return USABLE;
    }

    static String clearState(CmsRegistration row) {
        // 只有城市定向主题票(含自玩通行证)有通关概念;自由探索、③ 探索票、场次一律 NONE
        if (!Long.valueOf(1L).equals(row.getOwnerType()) || ExploreTicketProgress.isExplorePass(row)) return NONE;
        CmsTopic topic = row.getCmsTopic();
        Integer productType = topic == null ? null : topic.getProductType();
        if (productType != null && productType != ProductType.ORIENTEERING) return NONE;
        return Boolean.TRUE.equals(row.getTopicCompleted()) ? CLEARED : NOT_CLEARED;
    }

    private static Instant startOfDay(Date date) {
        if (date == null) return null;
        LocalDate day = date.toInstant().atZone(ZONE).toLocalDate();
        return day.atStartOfDay(ZONE).toInstant();
    }

    private static Instant endOfDay(Date date) {
        if (date == null) return null;
        LocalDate day = date.toInstant().atZone(ZONE).toLocalDate();
        return LocalDateTime.of(day, LocalTime.of(23, 59, 59)).atZone(ZONE).toInstant();
    }

    private static String format(Instant at) {
        return at == null ? null : FORMAT.format(at.atZone(ZONE));
    }
}
