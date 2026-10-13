package com.chengyinhub.business.service.support;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** App 传感器与伪 AR 滤镜模板的纯校验规则。 */
public final class SensorChallengePolicy
{
    public static final int MAX_CONFIG_LENGTH = 2000;
    public static final String FILTER_SHOT = "filter_shot";
    private static final Set<String> SENSOR_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("still", "steps", "audio_clip")));
    private static final Set<String> FILTER_STYLES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("night_vision", "pet_pov")));
    private static final ObjectMapper JSON = new ObjectMapper();

    private SensorChallengePolicy() {}

    public static boolean isAppSensor(Integer validationMethod)
    {
        return validationMethod != null && validationMethod == ValidationMethod.APP_SENSOR;
    }

    public static boolean isPhotoFilter(Integer validationMethod, String sensorType)
    {
        return validationMethod != null && validationMethod == ValidationMethod.PHOTO
                && FILTER_SHOT.equals(sensorType);
    }

    public static void validatePhotoFilterConfig(String sensorConfig)
    {
        JsonNode root = parseJsonObject(sensorConfig);
        JsonNode style = root.get("filterStyle");
        if (style == null || !style.isTextual() || !FILTER_STYLES.contains(style.asText())) {
            throw new IllegalArgumentException("滤镜样式无效");
        }
    }

    public static boolean isPlayablePhotoFilter(Integer validationMethod, String sensorType, String sensorConfig)
    {
        if (!isPhotoFilter(validationMethod, sensorType)) return false;
        try {
            validatePhotoFilterConfig(sensorConfig);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static void validateTemplateConfig(String sensorType, String sensorConfig)
    {
        JsonNode root = parseConfig(sensorType, sensorConfig);
        validateTemplateConfig(sensorType, root);
    }

    private static void validateTemplateConfig(String sensorType, JsonNode root)
    {
        if ("still".equals(sensorType)) {
            requireConfigInt(root, "durationSec", 600, "静止目标时长无效");
        } else if ("steps".equals(sensorType)) {
            requireConfigInt(root, "targetSteps", 100000, "目标步数无效");
            requireConfigInt(root, "windowSec", 86400, "计步时间窗无效");
        } else {
            requireConfigInt(root, "minDurationSec", 60, "音频最短时长无效");
        }
    }

    public static String validateSubmissionAndFingerprint(String requestedType, Map<String, Object> payload,
            String configuredType, String sensorConfig)
    {
        String sensorType = requestedType == null ? "" : requestedType.trim();
        if (FILTER_SHOT.equals(sensorType)) throw new IllegalArgumentException("filter_shot 不属于传感器上报");
        JsonNode root = parseConfig(sensorType, sensorConfig);
        if (!sensorType.equals(configuredType)) throw new IllegalArgumentException("传感器类型与节点配置不匹配");
        validateTemplateConfig(sensorType, root);
        if (payload == null) throw new IllegalArgumentException("传感器数据不能为空");

        try {
            if ("still".equals(sensorType)) {
                int heldSec = positiveInt(payload.get("heldSec"));
                int required = root.get("durationSec").asInt();
                if (heldSec < required || heldSec > 600) throw new IllegalArgumentException("静止时长不合理");
                return sensorType + "|" + heldSec;
            }
            if ("steps".equals(sensorType)) {
                int steps = positiveInt(payload.get("steps"));
                int target = root.get("targetSteps").asInt();
                int durationSec = positiveInt(payload.get("durationSec"));
                int windowSec = root.get("windowSec").asInt();
                if (steps < target || steps > 100000) throw new IllegalArgumentException("步数不合理");
                if (durationSec > windowSec) throw new IllegalArgumentException("计步时长不合理");
                return sensorType + "|" + steps + "|" + durationSec;
            }

            int durationSec = positiveInt(payload.get("durationSec"));
            int minimum = root.get("minDurationSec").asInt();
            Object mediaUrl = payload.get("mediaUrl");
            if (durationSec < minimum || durationSec > 60 || mediaUrl == null
                    || StringUtils.isBlank(String.valueOf(mediaUrl)) || String.valueOf(mediaUrl).length() > 1024) {
                throw new IllegalArgumentException("音频数据不合理");
            }
            return sensorType + "|" + durationSec + "|" + mediaUrl;
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null) throw e;
            throw new IllegalArgumentException("传感器数据无效", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("传感器数据无效", e);
        }
    }

    private static JsonNode parseConfig(String sensorType, String sensorConfig)
    {
        if (!SENSOR_TYPES.contains(sensorType)) throw new IllegalArgumentException("不支持的传感器类型");
        return parseJsonObject(sensorConfig);
    }

    private static JsonNode parseJsonObject(String sensorConfig)
    {
        if (StringUtils.isBlank(sensorConfig) || sensorConfig.length() > MAX_CONFIG_LENGTH) {
            throw new IllegalArgumentException("App 玩法配置无效");
        }
        try {
            JsonNode root = JSON.readTree(sensorConfig);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("传感器配置必须为 JSON 对象");
            return root;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("传感器配置不是合法 JSON", e);
        }
    }

    private static int requireConfigInt(JsonNode root, String field, int max, String message)
    {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.asInt() <= 0 || value.asInt() > max) {
            throw new IllegalArgumentException(message);
        }
        return value.asInt();
    }

    private static int positiveInt(Object value)
    {
        if (value instanceof Number) {
            double raw = ((Number) value).doubleValue();
            if (raw <= 0 || raw != Math.rint(raw) || raw > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("传感器数据无效");
            }
            return (int) raw;
        }
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            if (parsed <= 0) throw new IllegalArgumentException("传感器数据无效");
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("传感器数据无效", e);
        }
    }
}
