package com.chengyinhub.business.channelevent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;

import com.chengyinhub.business.service.channel.platform.ChannelRejection;

/**
 * The event form posted from a channel: title, time, place (map pin), capacity, description, price.
 * Pure validation, no I/O, so it is unit-tested on its own. Times are {@code yyyy-MM-dd HH:mm:ss} in
 * Asia/Shanghai (the activity API's format) or epoch milliseconds.
 */
public record ChannelEventDraft(String title, Date startAt, Date endAt, String placeName, String address,
        BigDecimal lat, BigDecimal lng, int capacity, String description, BigDecimal price, String requestId)
{
    public static final int TITLE_MAX = 60;
    public static final int PLACE_MAX = 80;
    public static final int ADDRESS_MAX = 200;
    public static final int DESCRIPTION_MAX = 2000;
    public static final int CAPACITY_MAX = 1000;
    public static final BigDecimal PRICE_MAX = new BigDecimal("9999.99");
    /** An event may run at most this long (one card is one sitting, not a season). */
    public static final long MAX_DURATION_MS = 7L * 24 * 3600 * 1000;
    /** And may be scheduled at most this far ahead. */
    public static final long MAX_LEAD_MS = 365L * 24 * 3600 * 1000;

    public boolean paid() { return price.signum() > 0; }

    public static ChannelEventDraft parse(Map<String, Object> body, Date now)
    {
        if (body == null) throw bad("EVENT_FORM_INVALID", "请填写活动信息");
        String title = text(body.get("title"));
        if (title == null) throw bad("EVENT_TITLE_REQUIRED", "请填写活动名称");
        if (title.codePointCount(0, title.length()) > TITLE_MAX) throw bad("EVENT_TITLE_TOO_LONG", "活动名称最多 60 个字");
        Date start = time(body.get("startAt"));
        Date end = time(body.get("endAt"));
        if (start == null || end == null) throw bad("EVENT_TIME_REQUIRED", "请选择开始和结束时间");
        if (!end.after(start)) throw bad("EVENT_TIME_ORDER", "结束时间要晚于开始时间");
        if (!start.after(now)) throw bad("EVENT_TIME_PAST", "开始时间要晚于现在");
        if (end.getTime() - start.getTime() > MAX_DURATION_MS) throw bad("EVENT_TOO_LONG", "一场活动最长 7 天");
        if (start.getTime() - now.getTime() > MAX_LEAD_MS) throw bad("EVENT_TOO_FAR", "最多提前一年发布活动");
        String place = text(body.get("placeName"));
        if (place == null) throw bad("EVENT_PLACE_REQUIRED", "请选择活动地点");
        if (place.codePointCount(0, place.length()) > PLACE_MAX) throw bad("EVENT_PLACE_TOO_LONG", "地点名称最多 80 个字");
        String address = text(body.get("address"));
        if (address != null && address.codePointCount(0, address.length()) > ADDRESS_MAX) address = clip(address, ADDRESS_MAX);
        BigDecimal lat = decimal(body.get("lat"));
        BigDecimal lng = decimal(body.get("lng"));
        if (lat == null || lng == null) throw bad("EVENT_PIN_REQUIRED", "请在地图上选好地点");
        if (lat.abs().compareTo(BigDecimal.valueOf(90)) > 0 || lng.abs().compareTo(BigDecimal.valueOf(180)) > 0
                || (lat.signum() == 0 && lng.signum() == 0))
            throw bad("EVENT_PIN_INVALID", "地点坐标无效，请重新选点");
        Integer capacity = integer(body.get("capacity"));
        if (capacity == null || capacity < 1) throw bad("EVENT_CAPACITY_REQUIRED", "请填写人数上限");
        if (capacity > CAPACITY_MAX) throw bad("EVENT_CAPACITY_TOO_LARGE", "人数上限最多 1000 人");
        String description = text(body.get("description"));
        if (description != null && description.codePointCount(0, description.length()) > DESCRIPTION_MAX)
            throw bad("EVENT_DESCRIPTION_TOO_LONG", "活动介绍最多 2000 字");
        BigDecimal price = decimal(body.get("price"));
        if (price == null) price = BigDecimal.ZERO;
        if (price.signum() < 0 || price.compareTo(PRICE_MAX) > 0) throw bad("EVENT_PRICE_INVALID", "票价无效");
        if (price.scale() > 2) throw bad("EVENT_PRICE_INVALID", "票价最多两位小数");
        String requestId = text(body.get("requestId"));
        if (requestId != null && !requestId.matches("[A-Za-z0-9_-]{8,64}")) throw bad("EVENT_REQUEST_ID_INVALID", "请求编号无效");
        return new ChannelEventDraft(title, start, end, place, address, lat.setScale(7, RoundingMode.HALF_UP),
                lng.setScale(7, RoundingMode.HALF_UP), capacity, description, price.setScale(2, RoundingMode.UNNECESSARY), requestId);
    }

    static ChannelRejection bad(String code, String message)
    {
        return new ChannelRejection(400, code, message);
    }

    static String text(Object raw)
    {
        if (raw == null) return null;
        String s = raw.toString().strip();
        return s.isEmpty() ? null : s;
    }

    static String clip(String s, int max)
    {
        if (s == null || s.codePointCount(0, s.length()) <= max) return s;
        return s.substring(0, s.offsetByCodePoints(0, max));
    }

    static Date time(Object raw)
    {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.longValue() > 0 ? new Date(n.longValue()) : null;
        String s = raw.toString().strip();
        if (s.matches("\\d{12,14}")) return new Date(Long.parseLong(s));
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        f.setLenient(false);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        try
        {
            return f.parse(s);
        }
        catch (ParseException e)
        {
            throw bad("EVENT_TIME_INVALID", "时间格式无效");
        }
    }

    static BigDecimal decimal(Object raw)
    {
        if (raw == null) return null;
        try
        {
            String s = raw.toString().strip();
            return s.isEmpty() ? null : new BigDecimal(s);
        }
        catch (NumberFormatException e)
        {
            throw bad("EVENT_NUMBER_INVALID", "数字格式无效");
        }
    }

    static Integer integer(Object raw)
    {
        BigDecimal d = decimal(raw);
        if (d == null) return null;
        try
        {
            return d.intValueExact();
        }
        catch (ArithmeticException e)
        {
            throw bad("EVENT_NUMBER_INVALID", "人数需为整数");
        }
    }
}
