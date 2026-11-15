package com.chengyinhub.business.citygame;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Resolves the effective City Monopoly / Attributes v2 config: approved defaults, overridden by admin-edited
 * {@code sys_config} rows ({@code city.monopoly.*}, {@code attributes.v2.*}), overridden by a board's
 * {@code city_game_board.config_json}. Global values are cached for 60 s; the admin update path refreshes immediately.
 */
@Service
public class CityMonopolyConfigService {
    static final long CACHE_MS = 60_000L;

    private final JdbcTemplate jdbc;
    private final LongSupplier clock;
    private volatile CityMonopolyConfig cached;
    private volatile long cachedAt;

    @Autowired
    public CityMonopolyConfigService(JdbcTemplate jdbc) {
        this(jdbc, System::currentTimeMillis);
    }

    CityMonopolyConfigService(JdbcTemplate jdbc, LongSupplier clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Fixed config (tests and fallbacks). */
    public static CityMonopolyConfigService fixed(CityMonopolyConfig config) {
        CityMonopolyConfigService s = new CityMonopolyConfigService(null, () -> 0L);
        s.cached = config;
        s.cachedAt = Long.MAX_VALUE / 2;
        return s;
    }

    public CityMonopolyConfig global() {
        CityMonopolyConfig c = cached;
        long now = clock.getAsLong();
        if (c != null && (jdbc == null || now - cachedAt < CACHE_MS)) return c;
        Map<String, String> overrides = new LinkedHashMap<>();
        try {
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT config_key, config_value FROM sys_config WHERE config_key LIKE 'city.monopoly.%' OR config_key LIKE 'attributes.v2.%'")) {
                overrides.put(String.valueOf(row.get("config_key")), row.get("config_value") == null ? null : String.valueOf(row.get("config_value")));
            }
        } catch (RuntimeException e) {
            // Fail closed to the approved defaults rather than to "no caps".
            if (c != null) return c;
        }
        c = CityMonopolyConfig.withOverrides(overrides);
        cached = c;
        cachedAt = now;
        return c;
    }

    /** Global config with a board's frozen {@code config_json} overrides applied on top. */
    public CityMonopolyConfig forBoard(String boardId) {
        CityMonopolyConfig g = global();
        if (boardId == null || boardId.isEmpty() || jdbc == null) return g;
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("SELECT config_json, launch_multiplier FROM city_game_board WHERE board_id = ?", boardId);
        } catch (RuntimeException e) {
            return g;
        }
        if (rows.isEmpty()) return g;
        Map<String, String> merged = new LinkedHashMap<>(g.asMap());
        Object json = rows.get(0).get("config_json");
        if (json != null) {
            try {
                JSONObject o = JSON.parseObject(String.valueOf(json));
                for (String k : o.keySet()) merged.put(k, o.getString(k));
            } catch (RuntimeException ignored) {
                // malformed board overrides are ignored (defaults stay)
            }
        }
        Object launch = rows.get(0).get("launch_multiplier");
        if (launch instanceof Number && ((Number) launch).doubleValue() > 1.0) {
            merged.put("city.monopoly.yield.city_launch_multiplier", String.valueOf(((Number) launch).doubleValue()));
        }
        return CityMonopolyConfig.withOverrides(merged);
    }

    /** Admin view: effective values, approved defaults and the keys that differ. */
    public Map<String, Object> describe() {
        CityMonopolyConfig c = global();
        Map<String, Object> diff = new LinkedHashMap<>();
        CityMonopolyConfig.defaultValues().forEach((k, v) -> {
            if (!v.equals(c.asMap().get(k))) diff.put(k, Map.of("default", v, "effective", c.asMap().get(k)));
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rulesHash", c.rulesHash());
        out.put("effective", c.asMap());
        out.put("defaults", CityMonopolyConfig.defaultValues());
        out.put("diff", diff);
        return out;
    }

    /** Validates and writes admin overrides into sys_config; rejects the whole batch on any invalid entry. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> update(Map<String, String> changes, String operator) {
        if (changes == null || changes.isEmpty()) throw new CityGameException(400, "CONFIG_EMPTY", "没有要修改的配置");
        Map<String, String> invalid = CityMonopolyConfig.invalidOverrides(changes);
        if (!invalid.isEmpty()) throw new CityGameException(400, "CONFIG_INVALID", "配置校验失败", invalid);
        for (Map.Entry<String, String> e : changes.entrySet()) {
            int n = jdbc.update("UPDATE sys_config SET config_value = ?, update_by = ?, update_time = NOW() WHERE config_key = ?",
                    e.getValue().trim(), operator == null ? "admin" : operator, e.getKey());
            if (n == 0) {
                jdbc.update("INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark) "
                                + "VALUES (?,?,?,'Y',?,NOW(),'CITY-MONOPOLY admin override')",
                        e.getKey().length() > 100 ? e.getKey().substring(0, 100) : e.getKey(), e.getKey(), e.getValue().trim(),
                        operator == null ? "admin" : operator);
            }
        }
        cached = null;
        return describe();
    }
}
