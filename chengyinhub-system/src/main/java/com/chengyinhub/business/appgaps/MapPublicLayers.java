package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.MapPublicLayerMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * App 公共地图图层（POST /api/map/public-layers，GCJ-02 视窗）。
 * store（门店）与 activity（已上架活动）由公开表实时投影；roam_poi 的发现态因人而异、theme/story_node
 * 尚无对外公开的坐标投影，这三类如实回 UNAVAILABLE 而不是伪造坐标。游标是各图层的 id 水位，按图层独立翻页。
 */
@Service
public class MapPublicLayers {
    public static final String DATUM = "GCJ-02";
    static final List<String> KNOWN = List.of("roam_poi", "store", "activity", "theme", "story_node");
    static final DateTimeFormatter VERSION = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    /** 已翻完的图层水位：后续页对它只回空。 */
    static final long EXHAUSTED = 999_999_999_999_999_999L;
    private final MapPublicLayerMapper mapper;

    public MapPublicLayers(MapPublicLayerMapper mapper) { this.mapper = mapper; }

    public Map<String, Object> read(Map<String, Object> body, LocalDateTime now) {
        if (body == null || !Set.of("datum", "minLat", "maxLat", "minLng", "maxLng", "limit", "layers", "cursor", "aggregatedQueryEnabled").containsAll(body.keySet())
                || !DATUM.equals(body.get("datum"))) throw new ServiceException("图层请求无效", 400);
        if (body.containsKey("aggregatedQueryEnabled") && !Boolean.FALSE.equals(body.get("aggregatedQueryEnabled"))) throw new ServiceException("不支持聚合查询", 400);
        BigDecimal south = number(body.get("minLat")), north = number(body.get("maxLat")), west = number(body.get("minLng")), east = number(body.get("maxLng"));
        if (south == null || north == null || west == null || east == null || south.compareTo(north) >= 0 || west.compareTo(east) >= 0
                || south.compareTo(BigDecimal.valueOf(-90)) < 0 || north.compareTo(BigDecimal.valueOf(90)) > 0
                || west.compareTo(BigDecimal.valueOf(-180)) < 0 || east.compareTo(BigDecimal.valueOf(180)) > 0
                || north.subtract(south).multiply(east.subtract(west)).compareTo(BigDecimal.ONE) > 0) throw new ServiceException("视窗无效或过大", 400);
        if (!(body.get("limit") instanceof Integer limit) || limit < 1 || limit > 50) throw new ServiceException("limit 无效", 400);
        if (!(body.get("layers") instanceof List<?> raw) || raw.isEmpty() || raw.size() > 5 || new HashSet<>(raw).size() != raw.size()
                || !KNOWN.containsAll(raw)) throw new ServiceException("图层无效", 400);
        @SuppressWarnings("unchecked") List<String> layers = (List<String>) raw;
        Map<String, Long> after = cursor(body.get("cursor"));
        List<Map<String, Object>> pages = new ArrayList<>();
        Map<String, Long> next = new LinkedHashMap<>();
        boolean more = false;
        for (String layer : layers) {
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("sourceType", layer);
            long from = after.getOrDefault(layer, 0L);
            List<Map<String, Object>> items = new ArrayList<>();
            if ("store".equals(layer) || "activity".equals(layer)) {
                List<Map<String, Object>> rows = "store".equals(layer)
                        ? mapper.selectStores(south, north, west, east, from, limit)
                        : mapper.selectActivities(south, north, west, east, from, limit);
                for (Map<String, Object> row : rows) {
                    Map<String, Object> item = item(layer, row, now);
                    if (item != null) items.add(item);
                }
                page.put("status", "OK");
                if (rows.size() == limit) { next.put(layer, ((Number) rows.get(rows.size() - 1).get("id")).longValue()); more = true; }
                else next.put(layer, EXHAUSTED);
            } else {
                page.put("status", "UNAVAILABLE");
                page.put("reason", "roam_poi".equals(layer) ? "VIEWER_SPECIFIC_SOURCE" : "PUBLIC_PROJECTION_NOT_PUBLISHED");
                page.put("capabilities", Map.of("read", false));
            }
            page.put("items", items);
            pages.add(page);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("datum", DATUM); out.put("layers", pages);
        out.put("nextCursor", more ? encode(next) : null);
        return out;
    }

    static Map<String, Object> item(String layer, Map<String, Object> row, LocalDateTime now) {
        String name = row.get("name") == null ? "" : String.valueOf(row.get("name")).strip();
        String lat = plain(row.get("lat")), lng = plain(row.get("lng"));
        LocalDateTime version = time(row.get("versionTime"));
        if (name.isEmpty() || name.length() > 300 || lat == null || lng == null || version == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sourceType", layer); m.put("sourceId", ((Number) row.get("id")).longValue()); m.put("name", name);
        m.put("latitude", lat); m.put("longitude", lng); m.put("visibilityVersion", VERSION.format(version));
        if ("store".equals(layer)) {
            boolean open = row.get("businessStatus") instanceof Number n && n.intValue() == 1;
            m.put("visualState", open ? "open" : "closed"); m.put("actions", List.of("navigate"));
            m.put("businessStatus", open ? 1 : 0);
        } else {
            LocalDateTime start = time(row.get("startDate")), end = time(row.get("endDate"));
            String state = start != null && now.isBefore(start) ? "notStarted" : end != null && now.isAfter(end) ? "completed" : "running";
            m.put("visualState", state); m.put("actions", List.of());
        }
        if (row.get("address") != null && !String.valueOf(row.get("address")).isBlank()) m.put("address", String.valueOf(row.get("address")));
        return m;
    }

    static String plain(Object value) {
        if (value == null) return null;
        try {
            String text = new BigDecimal(String.valueOf(value).strip()).stripTrailingZeros().toPlainString();
            return text.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?") ? text : null;
        } catch (NumberFormatException e) { return null; }
    }

    static LocalDateTime time(Object value) {
        if (value instanceof LocalDateTime t) return t;
        if (value instanceof java.util.Date d) return LocalDateTime.ofInstant(d.toInstant(), SHANGHAI);
        return null;
    }

    static BigDecimal number(Object value) {
        if (value instanceof Number n && !(value instanceof Double d && (d.isNaN() || d.isInfinite()))) return new BigDecimal(n.toString());
        return null;
    }

    static String encode(Map<String, Long> after) {
        StringBuilder s = new StringBuilder("v1");
        after.forEach((k, v) -> s.append(';').append(k).append(':').append(v));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.toString().getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, Long> cursor(Object raw) {
        Map<String, Long> m = new HashMap<>();
        if (raw == null) return m;
        if (!(raw instanceof String text) || text.isEmpty() || text.length() > 2048) throw new ServiceException("游标无效", 400);
        String decoded;
        try { decoded = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { throw new ServiceException("游标无效", 400); }
        String[] parts = decoded.split(";");
        if (!"v1".equals(parts[0])) throw new ServiceException("游标无效", 400);
        for (int i = 1; i < parts.length; i++) {
            String[] kv = parts[i].split(":");
            if (kv.length != 2 || !KNOWN.contains(kv[0]) || !kv[1].matches("\\d{1,18}")) throw new ServiceException("游标无效", 400);
            m.put(kv[0], Long.parseLong(kv[1]));
        }
        return m;
    }
}
