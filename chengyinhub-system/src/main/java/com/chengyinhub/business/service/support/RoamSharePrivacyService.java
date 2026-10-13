package com.chengyinhub.business.service.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** 匿名路线快照的唯一坐标隐私闸。 */
@Service
public class RoamSharePrivacyService {
    public static final int MAX_SNAPSHOT_CHARS = 64 * 1024;
    public static final int MAX_TRACK_POINTS = 500;
    public static final int MAX_POIS = 50;
    /** M0112/N015:客户端与服务端共用的去敏合同版本;客户端声明更高版本 = 服务端不认识的去敏规则,整份拒绝。 */
    public static final int REDACTION_VERSION = 1;
    public static final int MAX_MASK_ZONES = 5;
    public static final int MAX_CLIP_PERCENT = 40;
    private static final Pattern DURATION = Pattern.compile("\\d{1,4}:\\d{2}(:\\d{2})?");
    private static final Pattern POI_CAT = Pattern.compile("[a-z_]{1,16}");

    /** 写入前只保留白名单字段,且将真实坐标量化到最多 3 位小数。 */
    public JSONObject normalize(long clientTs, String raw) {
        if (raw == null || raw.length() > MAX_SNAPSHOT_CHARS) throw new IllegalArgumentException("size");
        JSONObject source = JSON.parseObject(raw);
        if (source == null) throw new IllegalArgumentException("empty");
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ts", clientTs - clientTs % 3_600_000L);
        String routeName = text(source.get("routeName"), 40);
        out.put("routeName", routeName.isEmpty() ? "城市漫游足迹" : routeName);
        String time = text(source.get("time"), 10);
        out.put("time", DURATION.matcher(time).matches() ? time : "—");
        out.put("distance", number(source.get("distance"), 10000));
        out.put("explorePct", number(source.get("explorePct"), 100));
        Double shops = number(source.get("shops"), 10000);
        out.put("shops", shops == null || shops % 1 != 0 ? null : shops.intValue());
        out.put("medal", text(source.get("medal"), 40));
        out.put("shopMedalName", text(source.get("shopMedalName"), 40));
        out.put("track", points(source.get("track"), MAX_TRACK_POINTS));
        out.put("pois", pois(source.get("pois")));
        JSONObject redaction = redaction(source.get("redaction"));
        if (redaction != null) out.put("redaction", redaction);
        return out;
    }

    /** 迁移完成前的读时兼容:历史精确坐标也不得穿过匿名接口。 */
    public JSONObject normalizeStored(String raw) {
        JSONObject stored = JSON.parseObject(raw);
        Long clientTs = stored == null ? null : stored.getLong("ts");
        if (clientTs == null || clientTs <= 0) throw new IllegalArgumentException("ts");
        return normalize(clientTs, raw);
    }

    /**
     * 去敏声明只存「用了哪版规则、裁了多少、遮了几处」,不存遮挡区域坐标(那就是住址)。
     * 服务端拿不到原始轨迹,无法复核裁剪是否真的做了;这里保证的是版本一致与字段形状,
     * 量化到 3 位小数也不等于住宅不可推断。
     */
    private static JSONObject redaction(Object value) {
        if (value == null) return null;
        if (!(value instanceof JSONObject)) throw new IllegalArgumentException("redaction");
        JSONObject r = (JSONObject) value;
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("v", integer(r.get("v"), 1, REDACTION_VERSION));
        Object clip = r.get("clip");
        if (!(clip instanceof Boolean)) throw new IllegalArgumentException("redaction.clip");
        out.put("clip", clip);
        out.put("pct", integer(r.get("pct"), 0, MAX_CLIP_PERCENT));
        out.put("zones", integer(r.get("zones"), 0, MAX_MASK_ZONES));
        return out;
    }

    private static int integer(Object value, int min, int max) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("integer");
        double d = ((Number) value).doubleValue();
        if (!Double.isFinite(d) || d % 1 != 0 || d < min || d > max) throw new IllegalArgumentException("range");
        return (int) d;
    }

    private static String text(Object value, int max) {
        if (value == null) return "";
        if (!(value instanceof String)) throw new IllegalArgumentException("text");
        String s = ((String) value).replaceAll("[\\p{Cntrl}]", "").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static Double number(Object value, double max) {
        if (value == null) return null;
        if (!(value instanceof Number)) throw new IllegalArgumentException("number");
        double d = ((Number) value).doubleValue();
        if (!Double.isFinite(d) || d < 0 || d > max) throw new IllegalArgumentException("range");
        return d;
    }

    private static JSONArray points(Object value, int max) {
        JSONArray out = new JSONArray();
        if (value == null) return out;
        if (!(value instanceof JSONArray) || ((JSONArray) value).size() > max) throw new IllegalArgumentException("track");
        for (Object item : (JSONArray) value) out.add(point(item, true));
        return out;
    }

    private static JSONArray pois(Object value) {
        JSONArray out = new JSONArray();
        if (value == null) return out;
        if (!(value instanceof JSONArray) || ((JSONArray) value).size() > MAX_POIS) throw new IllegalArgumentException("pois");
        for (Object item : (JSONArray) value) {
            if (!(item instanceof JSONObject)) throw new IllegalArgumentException("poi");
            JSONObject poi = (JSONObject) item;
            JSONObject row = new JSONObject(new LinkedHashMap<>());
            row.put("name", text(poi.get("name"), 40));
            String cat = text(poi.get("cat"), 16);
            row.put("cat", POI_CAT.matcher(cat).matches() ? cat : "landmark");
            row.put("catLabel", text(poi.get("catLabel"), 16));
            if (poi.get("lat") != null || poi.get("lng") != null) row.putAll(point(poi, false));
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> point(Object item, boolean track) {
        if (!(item instanceof JSONObject)) throw new IllegalArgumentException(track ? "point" : "poi");
        JSONObject p = (JSONObject) item;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("lat", coordinate(p.get("lat"), 90));
        row.put("lng", coordinate(p.get("lng"), 180));
        return track ? new JSONObject(row) : row;
    }

    private static BigDecimal coordinate(Object value, double max) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("coordinate");
        double d = ((Number) value).doubleValue();
        if (!Double.isFinite(d) || Math.abs(d) > max) throw new IllegalArgumentException("coordinate");
        return BigDecimal.valueOf(d).setScale(3, RoundingMode.HALF_UP);
    }
}
