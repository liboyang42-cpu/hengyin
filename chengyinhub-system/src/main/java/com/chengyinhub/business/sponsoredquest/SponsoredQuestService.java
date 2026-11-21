package com.chengyinhub.business.sponsoredquest;

import com.chengyinhub.business.sponsoredquest.mapper.SponsoredQuestMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * S3b sponsored quests, player side. Players see a campaign only here: as a quest inside the opt-in deals layer,
 * always with the fixed 「广告」 / "Ad" label. Nothing in the fog map, roam pin ranking or search reads this service
 * or its tables, so a live campaign cannot change what is revealed, the order of pins or the order of results.
 */
@Service
public class SponsoredQuestService {
    /** Same box limit as the deals layer: one city view, never a country scrape. */
    public static final double MAX_SPAN_DEGREES = 0.5;
    public static final int MAX_ROWS = 20;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final SponsoredQuestMapper mapper;

    @Autowired
    public SponsoredQuestService(SponsoredQuestMapper mapper) {
        this.mapper = mapper;
    }

    public List<Map<String, Object>> liveInBox(Double minLat, Double maxLat, Double minLng, Double maxLng) {
        requireBox(minLat, maxLat, minLng, maxLng);
        List<Map<String, Object>> rows = mapper.selectLiveInBox(minLat, maxLat, minLng, maxLng, MAX_ROWS);
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        List<Long> ids = new ArrayList<>();
        List<Map<String, Object>> campaigns = new ArrayList<>();
        for (Map<String, Object> raw : rows) {
            Map<String, Object> row = caseless(raw);
            campaigns.add(row);
            Long id = asLong(row.get("id"));
            if (id != null) ids.add(id);
        }
        Map<Long, List<Map<String, Object>>> places = new LinkedHashMap<>();
        List<Map<String, Object>> placeRows = ids.isEmpty() ? null : mapper.selectLivePlaces(ids);
        if (placeRows != null) {
            for (Map<String, Object> raw : placeRows) {
                Map<String, Object> p = caseless(raw);
                Long campaignId = asLong(p.get("campaignId"));
                if (campaignId == null) continue;
                Map<String, Object> place = new LinkedHashMap<>();
                place.put("poiId", asLong(p.get("poiId")));
                place.put("name", p.get("name"));
                place.put("lat", p.get("lat"));
                place.put("lng", p.get("lng"));
                places.computeIfAbsent(campaignId, k -> new ArrayList<>()).add(place);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : campaigns) {
            Long id = asLong(row.get("id"));
            List<Map<String, Object>> stops = id == null ? null : places.get(id);
            if (stops == null || stops.isEmpty()) continue;
            out.add(playerRow(row, stops));
        }
        return out;
    }

    /** One player row. The label fields are always written last, from code, so no stored value can drop them. */
    static Map<String, Object> playerRow(Map<String, Object> row, List<Map<String, Object>> places) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", asLong(row.get("id")));
        out.put("title", row.get("title"));
        out.put("content", row.get("content"));
        out.put("sponsorName", row.get("sponsorName"));
        out.put("coverImg", row.get("coverImg"));
        out.put("startTime", time(row.get("startTime")));
        out.put("endTime", time(row.get("endTime")));
        out.put("places", places);
        labelled(out);
        return out;
    }

    /** Adds (or overwrites) the visible label. Every player-facing campaign row passes through here. */
    public static Map<String, Object> labelled(Map<String, Object> row) {
        row.put("sponsored", Boolean.TRUE);
        row.put("label", SponsoredQuestLabel.ZH_HANS);
        row.put("labelEn", SponsoredQuestLabel.EN);
        return row;
    }

    /** Column aliases keep their case on MySQL but not on every driver; read them case-insensitively. */
    static Map<String, Object> caseless(Map<String, Object> row) {
        Map<String, Object> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (row != null) out.putAll(row);
        return out;
    }

    static void requireBox(Double minLat, Double maxLat, Double minLng, Double maxLng) {
        if (minLat == null || maxLat == null || minLng == null || maxLng == null
                || !Double.isFinite(minLat) || !Double.isFinite(maxLat) || !Double.isFinite(minLng) || !Double.isFinite(maxLng)
                || minLat < -90 || maxLat > 90 || minLng < -180 || maxLng > 180
                || minLat >= maxLat || minLng >= maxLng) {
            throw new IllegalArgumentException("地图范围无效");
        }
        if (maxLat - minLat > MAX_SPAN_DEGREES + 1e-9 || maxLng - minLng > MAX_SPAN_DEGREES + 1e-9) {
            throw new IllegalArgumentException("地图范围太大，请放大地图后再看");
        }
    }

    static String time(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime t) return t.format(TIME);
        if (value instanceof Date d) return d.toInstant().atZone(ZONE).toLocalDateTime().format(TIME);
        return String.valueOf(value);
    }

    static Long asLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
