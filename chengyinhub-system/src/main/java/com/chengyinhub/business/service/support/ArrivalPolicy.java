package com.chengyinhub.business.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 到达策略（BE-09）：作者在节点公开配置里可选覆盖围栏半径/精度闸/定位时效，
 * 发布时与既有冻结到达配置（routeStations）一起冻结，开局后只读冻结副本。
 *
 * <p>不变量（不因新策略改变）：
 * <ul>
 *   <li>缺 {@code arrivalPolicy} 或字段缺省 = 旧局/旧发布规则：半径 50m、精度 80m、不校验定位时效；</li>
 *   <li>围栏与精度<b>各自独立</b>判定，绝不把 accuracy 加到半径上（低精度不能扩大可到达范围）；</li>
 *   <li>{@code fallbackMode} 只表达作者声明的替代方式，<b>不授权任何 fallback 或 skip</b>；
 *       真正的「已获批准 fallback」来自地点合同的 approvedFallbackNodeIds，见
 *       {@link TopicLocationContract#approvedFallbacksForNode(String, Long, Set)}；</li>
 *   <li>非法冻结策略失败关闭（抛 {@link IllegalArgumentException}），不得静默降级成放行。</li>
 * </ul>
 */
public final class ArrivalPolicy {

    public static final String FIELD = "arrivalPolicy";
    public static final int SCHEMA_VERSION = 1;
    /** 既有基线值，不是新策略默认：BE-09 要求原 50m/80m 原样保留，直接引用守卫的同一常量。 */
    public static final double LEGACY_RADIUS_METERS = ArriveLocationGuardPolicy.GEOFENCE_METERS;
    public static final double LEGACY_MAX_ACCURACY_METERS = ArriveLocationGuardPolicy.MISSING_ACCURACY_METERS;

    /**
     * 开局定位校验半径(业主 2026-11-13 拍板:约 300 米,只在开局时看一次)。
     * 开局之后各站到点不再要求定位,逐站 50m 围栏({@link #LEGACY_RADIUS_METERS})不再用于站点。
     */
    public static final double START_RADIUS_METERS = 300d;

    public static final String FALLBACK_NONE = "NONE";
    public static final String FALLBACK_ALTERNATE_EVIDENCE = "ALTERNATE_EVIDENCE";
    public static final String FALLBACK_ALTERNATE_NODE = "ALTERNATE_NODE";
    public static final String FALLBACK_SKIP_ALLOWED = "SKIP_ALLOWED";

    /** 与地点合同 alternativePolicy 同一组取值；缺省 NONE，不默认放行任何替代。 */
    private static final Set<String> FALLBACK_MODES = Collections.unmodifiableSet(new LinkedHashSet<String>(
            Arrays.asList(FALLBACK_NONE, FALLBACK_ALTERNATE_EVIDENCE, FALLBACK_ALTERNATE_NODE, FALLBACK_SKIP_ALLOWED)));

    private static final Set<String> KNOWN_KEYS = Collections.unmodifiableSet(new LinkedHashSet<String>(
            Arrays.asList("version", "radiusMeters", "maxAccuracyMeters", "maxAgeSeconds", "fallbackMode")));

    /** 围栏/精度上限只拦住明显没有场地意义的数值，不替代作者的现场判断。 */
    private static final double MAX_METERS = 10_000d;
    private static final int MAX_AGE_SECONDS = 86_400;
    /** 端上时钟与服务端允许的向前偏差；超过按陈旧处理。 */
    public static final long CLOCK_SKEW_MILLIS = 60_000L;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ArrivalPolicy LEGACY =
            new ArrivalPolicy(SCHEMA_VERSION, LEGACY_RADIUS_METERS, LEGACY_MAX_ACCURACY_METERS, null, FALLBACK_NONE);

    /** 围栏判定结果；null 原因码语义见调用方映射。 */
    public enum Fence {
        PASS,
        INVALID,
        ACCURACY_LOW,
        TOO_FAR
    }

    private final int version;
    private final double radiusMeters;
    private final double maxAccuracyMeters;
    private final Integer maxAgeSeconds;
    private final String fallbackMode;

    private ArrivalPolicy(int version, double radiusMeters, double maxAccuracyMeters,
                          Integer maxAgeSeconds, String fallbackMode) {
        this.version = version;
        this.radiusMeters = radiusMeters;
        this.maxAccuracyMeters = maxAccuracyMeters;
        this.maxAgeSeconds = maxAgeSeconds;
        this.fallbackMode = fallbackMode;
    }

    /** 旧局/旧发布（无冻结策略）使用的基线；不要把它写进新发布冻结副本。 */
    public static ArrivalPolicy legacy() {
        return LEGACY;
    }

    /**
     * 运行时从冻结站点读取策略。{@code station} 为 null 或没有 arrivalPolicy = 旧局规则；
     * 有该键但不是合法对象/数值 = 配置损坏，抛异常由调用方失败关闭。
     */
    public static ArrivalPolicy fromStation(JsonNode station) {
        if (station == null || station.isMissingNode() || station.isNull()) return LEGACY;
        JsonNode raw = station.path(FIELD);
        if (raw.isMissingNode() || raw.isNull()) return LEGACY;
        return parse(raw);
    }

    /** 严格解析（已规范化或作者原文都可）；未知字段/越界值一律拒绝。 */
    public static ArrivalPolicy parse(JsonNode raw) {
        ObjectNode normalized = normalize(raw);
        return new ArrivalPolicy(
                normalized.path("version").asInt(SCHEMA_VERSION),
                normalized.path("radiusMeters").asDouble(LEGACY_RADIUS_METERS),
                normalized.path("maxAccuracyMeters").asDouble(LEGACY_MAX_ACCURACY_METERS),
                normalized.has("maxAgeSeconds") ? Integer.valueOf(normalized.path("maxAgeSeconds").asInt()) : null,
                normalized.path("fallbackMode").asText(FALLBACK_NONE));
    }

    /**
     * 保存/发布校验并规范化作者配置。只保留已知键；未知键说明作者写错了位置或拼写，
     * 静默忽略会让策略「看起来生效」而实际没有，所以直接拒绝。
     */
    public static ObjectNode normalize(JsonNode raw) {
        if (raw == null || !raw.isObject()) throw new IllegalArgumentException("到达策略必须是对象");
        Iterator<String> names = raw.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!KNOWN_KEYS.contains(name)) throw new IllegalArgumentException("到达策略包含未知字段:" + name);
        }
        ObjectNode out = JSON.createObjectNode();
        int version = raw.has("version") ? raw.path("version").asInt(-1) : SCHEMA_VERSION;
        if (version != SCHEMA_VERSION) throw new IllegalArgumentException("到达策略版本不支持");
        out.put("version", version);
        if (raw.has("radiusMeters")) out.put("radiusMeters", meters(raw.get("radiusMeters"), "radiusMeters"));
        if (raw.has("maxAccuracyMeters")) {
            out.put("maxAccuracyMeters", meters(raw.get("maxAccuracyMeters"), "maxAccuracyMeters"));
        }
        if (raw.has("maxAgeSeconds")) {
            JsonNode age = raw.get("maxAgeSeconds");
            if (!age.isIntegralNumber() || age.asLong() < 1L || age.asLong() > MAX_AGE_SECONDS) {
                throw new IllegalArgumentException("maxAgeSeconds 须为 1 至 " + MAX_AGE_SECONDS + " 的整数秒");
            }
            out.put("maxAgeSeconds", age.asInt());
        }
        String fallback = raw.has("fallbackMode") ? raw.path("fallbackMode").asText(null) : FALLBACK_NONE;
        if (fallback == null || !FALLBACK_MODES.contains(fallback)) {
            throw new IllegalArgumentException("fallbackMode 不支持:" + fallback);
        }
        out.put("fallbackMode", fallback);
        return out;
    }

    private static double meters(JsonNode value, String field) {
        if (value == null || !value.isNumber()) throw new IllegalArgumentException(field + " 须为数字");
        double meters = value.asDouble();
        if (!Double.isFinite(meters) || meters <= 0d || meters > MAX_METERS) {
            throw new IllegalArgumentException(field + " 须为 (0, " + (long) MAX_METERS + "] 的有限米数");
        }
        return meters;
    }

    /**
     * 围栏判定：先精度、后距离，两者互不抵扣。低精度不会放大半径——
     * 半径永远只由 {@link #radiusMeters()} 决定，精度只决定是否允许参与判定。
     */
    public Fence fenceViolation(double distanceMeters, double accuracyMeters) {
        if (!Double.isFinite(distanceMeters) || !Double.isFinite(accuracyMeters) || accuracyMeters < 0d) {
            return Fence.INVALID;
        }
        if (accuracyMeters > maxAccuracyMeters) return Fence.ACCURACY_LOW;
        if (distanceMeters > radiusMeters) return Fence.TOO_FAR;
        return Fence.PASS;
    }

    /**
     * 开局校验:与首站(或集合点)距离 &le; {@link #START_RADIUS_METERS}。精度仍先于距离、互不抵扣,
     * 低精度不能放大可开局范围;精度上限沿用旧 80m 基线。
     */
    public static Fence startViolation(double distanceMeters, double accuracyMeters) {
        if (!Double.isFinite(distanceMeters) || !Double.isFinite(accuracyMeters) || accuracyMeters < 0d) {
            return Fence.INVALID;
        }
        if (accuracyMeters > LEGACY_MAX_ACCURACY_METERS) return Fence.ACCURACY_LOW;
        if (distanceMeters > START_RADIUS_METERS) return Fence.TOO_FAR;
        return Fence.PASS;
    }

    /** 策略要求定位时效时，缺失/未来/过旧都按陈旧处理；未要求时不校验（旧局兼容）。 */
    public boolean locationTooOld(Long capturedAtMillis, long nowMillis) {
        if (maxAgeSeconds == null) return false;
        if (capturedAtMillis == null || capturedAtMillis.longValue() <= 0L) return true;
        if (capturedAtMillis.longValue() > nowMillis + CLOCK_SKEW_MILLIS) return true;
        return nowMillis - capturedAtMillis.longValue() > maxAgeSeconds.longValue() * 1000L;
    }

    public int version() {
        return version;
    }

    public double radiusMeters() {
        return radiusMeters;
    }

    public double maxAccuracyMeters() {
        return maxAccuracyMeters;
    }

    /** null = 不校验定位时效。 */
    public Integer maxAgeSeconds() {
        return maxAgeSeconds;
    }

    public String fallbackMode() {
        return fallbackMode;
    }

    /**
     * 响应里的「已获批准 fallback」：只回冻结站点里由地点合同批准过的节点编号，
     * 不从 fallbackMode 推导，不把作者声明当授权。
     */
    public static List<String> approvedFallbacks(JsonNode station) {
        if (station == null || !station.isObject()) return Collections.emptyList();
        JsonNode raw = station.path("approvedFallbacks");
        if (!raw.isArray() || raw.size() == 0) return Collections.emptyList();
        List<String> out = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (JsonNode id : raw) {
            if (id == null || !id.isTextual()) continue;
            String value = id.asText().trim();
            if (value.isEmpty() || !value.matches("[1-9][0-9]{0,18}") || !seen.add(value)) continue;
            out.add(value);
        }
        return out;
    }
}
