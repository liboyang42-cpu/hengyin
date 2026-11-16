package com.chengyinhub.business.citygame;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Immutable, typed view of the City Monopoly + Attributes v2 numbers (CITY-MONOPOLY-SPEC §6.1).
 *
 * <p>Defaults come from {@code citygame/city-monopoly-defaults.properties} (the approved values); admin overrides come
 * from {@code sys_config} rows with the same keys and, optionally, a board's {@code config_json}. Unknown override keys
 * are ignored and malformed values fail closed (the default stays), so a typo in the admin console cannot disable a cap.
 * Owner ruling 2026-11-15: one currency, 积分; there is no CITY_COIN or wallet.
 */
public final class CityMonopolyConfig {
    public static final String RESOURCE = "citygame/city-monopoly-defaults.properties";
    private static final Map<String, String> DEFAULTS = loadDefaults();

    private final Map<String, String> values;

    private CityMonopolyConfig(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    public static CityMonopolyConfig defaults() {
        return new CityMonopolyConfig(new TreeMap<>(DEFAULTS));
    }

    /** Approved default values (key -> raw string), in file order. */
    public static Map<String, String> defaultValues() {
        return Collections.unmodifiableMap(DEFAULTS);
    }

    /** Applies overrides on top of the defaults; keys not in the defaults and values that fail validation are dropped. */
    public static CityMonopolyConfig withOverrides(Map<String, String> overrides) {
        Map<String, String> merged = new TreeMap<>(DEFAULTS);
        if (overrides != null) {
            for (Map.Entry<String, String> e : overrides.entrySet()) {
                String key = e.getKey();
                String value = e.getValue() == null ? null : e.getValue().trim();
                if (key == null || value == null || value.isEmpty() || !DEFAULTS.containsKey(key)) continue;
                if (validValue(key, value)) merged.put(key, value);
            }
        }
        return new CityMonopolyConfig(merged);
    }

    /** Overrides that would be rejected by {@link #withOverrides}, with a reason; used by the admin preview. */
    public static Map<String, String> invalidOverrides(Map<String, String> overrides) {
        Map<String, String> out = new LinkedHashMap<>();
        if (overrides == null) return out;
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            String key = e.getKey();
            String value = e.getValue() == null ? "" : e.getValue().trim();
            if (!DEFAULTS.containsKey(key)) out.put(key, "UNKNOWN_KEY");
            else if (value.isEmpty() || !validValue(key, value)) out.put(key, "INVALID_VALUE");
        }
        return out;
    }

    /** A value is valid when it parses the same way as the approved default and respects hard safety bounds. */
    static boolean validValue(String key, String value) {
        String def = DEFAULTS.get(key);
        try {
            if (key.endsWith("currency")) return "POINTS".equals(value);
            if (key.equals("city.monopoly.claim.attest_mode")) return "REQUIRED".equals(value) || "SIGNAL".equals(value);
            if (isBoolean(def)) return "true".equals(value) || "false".equals(value);
            if (isNumber(def)) {
                double v = Double.parseDouble(value);
                if (Double.isNaN(v) || Double.isInfinite(v)) return false;
                if (key.equals("city.monopoly.yield.event_multiplier")) {
                    return v >= 0 && v <= Double.parseDouble(DEFAULTS.get("city.monopoly.yield.event_multiplier_max"));
                }
                if (key.equals("city.monopoly.yield.event_multiplier_max")) return v >= 1.0 && v <= 2.0;
                if (key.equals("city.monopoly.yield.multiplier_cap")) return v >= 1.0 && v <= 4.0;
                if (key.equals("city.monopoly.yield.city_launch_multiplier")) return v >= 1.0 && v <= 2.0;
                return v >= (key.endsWith(".weathered") || key.endsWith(".dc") ? -20 : 0);
            }
            if (def.startsWith("[")) { return com.alibaba.fastjson2.JSON.parseArray(value) != null; }
            if (def.contains("@")) { timeBands(value); return true; }
            if (def.matches("[0-9.]+(,[0-9.]+)+")) { doubles(value); return true; }
            if (def.matches("[A-Za-z0-9_]+:[0-9.]+(,[A-Za-z0-9_]+:[0-9.]+)*")) { levelMap(value); return true; }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isBoolean(String v) { return "true".equals(v) || "false".equals(v); }

    private static boolean isNumber(String v) {
        try { Double.parseDouble(v); return true; } catch (NumberFormatException e) { return false; }
    }

    public Map<String, String> asMap() { return values; }

    public String str(String key) {
        String v = values.get(key);
        if (v == null) throw new IllegalArgumentException("unknown city monopoly config key: " + key);
        return v;
    }

    public double num(String key) { return Double.parseDouble(str(key)); }

    public int integer(String key) { return (int) Math.round(num(key)); }

    public boolean flag(String key) { return Boolean.parseBoolean(str(key)); }

    public List<Double> list(String key) { return doubles(str(key)); }

    public List<String> strings(String key) {
        List<String> out = new ArrayList<>();
        for (String s : str(key).split(",")) out.add(s.trim());
        return out;
    }

    /** "level:value" pairs, keys kept as strings (levels or tier names). */
    public Map<String, Double> levels(String key) { return levelMap(str(key)); }

    public List<TimeBand> timeBands() { return timeBands(str("city.monopoly.yield.time_bands")); }

    /** SHA-256 over the sorted effective values; quotes bind to it so a mid-quote config change invalidates them. */
    public String rulesHash() {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(values).forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Half-open local time band [fromMinute, toMinute) with a rent multiplier; may wrap midnight. */
    public record TimeBand(int fromMinute, int toMinute, double multiplier) {
        public boolean contains(int minuteOfDay) {
            if (fromMinute <= toMinute) return minuteOfDay >= fromMinute && minuteOfDay < toMinute;
            return minuteOfDay >= fromMinute || minuteOfDay < toMinute;
        }
    }

    static List<TimeBand> timeBands(String raw) {
        List<TimeBand> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String[] mp = part.trim().split("@");
            String[] range = mp[0].split("-");
            double m = Double.parseDouble(mp[1]);
            if (m < 0 || m > 4) throw new IllegalArgumentException("time band multiplier out of range");
            out.add(new TimeBand(minute(range[0]), minute(range[1]), m));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("no time bands");
        return out;
    }

    static int minute(String hhmm) {
        String[] p = hhmm.trim().split(":");
        int h = Integer.parseInt(p[0]);
        int m = Integer.parseInt(p[1]);
        if (h < 0 || h > 24 || m < 0 || m > 59) throw new IllegalArgumentException("bad time " + hhmm);
        return h * 60 + m;
    }

    static List<Double> doubles(String raw) {
        List<Double> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            double v = Double.parseDouble(s.trim());
            if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) throw new IllegalArgumentException("bad number");
            out.add(v);
        }
        return out;
    }

    static Map<String, Double> levelMap(String raw) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String part : raw.split(",")) {
            String[] kv = part.trim().split(":");
            if (kv.length != 2) throw new IllegalArgumentException("bad pair " + part);
            out.put(kv[0].trim(), Double.parseDouble(kv[1].trim()));
        }
        return out;
    }

    private static Map<String, String> loadDefaults() {
        Properties props = new Properties();
        List<String> order = new ArrayList<>();
        try (InputStream in = CityMonopolyConfig.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            byte[] bytes = in.readAllBytes();
            props.load(new InputStreamReader(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int eq = t.indexOf('=');
                if (eq > 0) order.add(t.substring(0, eq).trim());
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot load " + RESOURCE, e);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : order) out.put(k, props.getProperty(k));
        return out;
    }
}
