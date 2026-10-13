package com.chengyinhub.business.service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import com.chengyinhub.common.exception.ServiceException;

/** NF-03 活动/主题筛选字段与枚举的服务端真源。 */
public final class DiscoveryFilterContract {
    public static final String RESOURCE_ACTIVITY = "ACTIVITY";
    public static final String RESOURCE_TOPIC = "TOPIC";
    public static final String SCHEDULE_ONGOING = "ONGOING";
    public static final String SCHEDULE_UPCOMING = "UPCOMING";
    public static final int ACTIVITY_RADIUS_METERS = 100000;

    private DiscoveryFilterContract() { }

    public static Map<String, Object> schema(String resource) {
        String type = normalize(resource);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resource", type);
        out.put("pagination", "pageNum/pageSize");
        out.put("totalField", "data.total");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("category_id", map("type", "positiveLong", "multiple", false));
        fields.put("city_keyword", map("type", "string", "maxLength", 50, "match", "addressContains"));
        fields.put("product_type", map("enum", Arrays.asList("CLASSIC", "FREE_EXPLORE"), "multiple", false));
        if (RESOURCE_ACTIVITY.equals(type)) {
            fields.put("schedule", map("enum", Arrays.asList(SCHEDULE_ONGOING, SCHEDULE_UPCOMING), "multiple", false));
            fields.put("sort", map("enum", Arrays.asList("LATEST", "NEAREST"),
                    "coordinateRequirement", "longitude+latitude", "locationFallback", "LATEST"));
            fields.put("distance_meters", map("enum", Arrays.asList(ACTIVITY_RADIUS_METERS),
                    "coordinateRequirement", "longitude+latitude"));
        } else {
            fields.put("self_play", map("enum", Arrays.asList("ANY", "ONLY"), "multiple", false));
            fields.put("is_recommend", map("enum", Arrays.asList("ANY", "ONLY"), "multiple", false));
        }
        out.put("fields", fields);
        return out;
    }

    public static String schedule(String value) {
        String normalized = optional(value);
        if (normalized == null) return null;
        if (!SCHEDULE_ONGOING.equals(normalized) && !SCHEDULE_UPCOMING.equals(normalized)) {
            throw invalid("schedule", Arrays.asList(SCHEDULE_ONGOING, SCHEDULE_UPCOMING));
        }
        return normalized;
    }

    public static Integer productType(String value) {
        String normalized = optional(value);
        if (normalized == null) return null;
        if ("CLASSIC".equals(normalized)) return 1;
        if ("FREE_EXPLORE".equals(normalized)) return 2;
        throw invalid("product_type", Arrays.asList("CLASSIC", "FREE_EXPLORE"));
    }

    public static Integer activitySort(String value, String longitude, String latitude) {
        String normalized = optional(value);
        if (normalized == null || "LATEST".equals(normalized)) return null;
        if (!"NEAREST".equals(normalized)) throw invalid("sort", Arrays.asList("LATEST", "NEAREST"));
        if (blank(longitude) || blank(latitude)) {
            throw new ServiceException("sort=NEAREST 必须同时提供 longitude、latitude；无定位权限请降级 LATEST", 400);
        }
        requireCoordinates(longitude, latitude);
        return 1;
    }

    public static void coordinates(String longitude, String latitude) {
        boolean hasLongitude = !blank(longitude);
        boolean hasLatitude = !blank(latitude);
        if (hasLongitude != hasLatitude) {
            throw new ServiceException("longitude、latitude 必须同时提供", 400);
        }
        if (hasLongitude) requireCoordinates(longitude, latitude);
    }

    public static String categoryId(String value) {
        String normalized = optionalRaw(value);
        if (normalized == null) return null;
        try {
            long parsed = Long.parseLong(normalized);
            if (parsed <= 0) throw new NumberFormatException();
            return String.valueOf(parsed);
        } catch (NumberFormatException e) {
            throw new ServiceException("category_id 必须为正整数", 400);
        }
    }

    public static String cityKeyword(String value) {
        String normalized = optionalRaw(value);
        if (normalized == null) return null;
        if (normalized.length() > 50) throw new ServiceException("city_keyword 最长 50 个字符", 400);
        return normalized;
    }

    public static Integer distanceMeters(String value, String longitude, String latitude) {
        String normalized = optionalRaw(value);
        if (normalized == null) return null;
        if (blank(longitude) || blank(latitude)) {
            throw new ServiceException("distance_meters 必须同时提供 longitude、latitude", 400);
        }
        requireCoordinates(longitude, latitude);
        if (!String.valueOf(ACTIVITY_RADIUS_METERS).equals(normalized)) {
            throw new ServiceException("distance_meters 枚举无效，支持值: [" + ACTIVITY_RADIUS_METERS + "]", 400);
        }
        return ACTIVITY_RADIUS_METERS;
    }

    public static Integer only(String field, String value) {
        String normalized = optional(value);
        if (normalized == null || "ANY".equals(normalized) || "0".equals(normalized)) return null;
        if ("ONLY".equals(normalized) || "1".equals(normalized)) return 1;
        throw invalid(field, Arrays.asList("ANY", "ONLY"));
    }

    private static String normalize(String resource) {
        String value = optional(resource);
        if (!RESOURCE_ACTIVITY.equals(value) && !RESOURCE_TOPIC.equals(value)) {
            throw invalid("resource", Arrays.asList(RESOURCE_ACTIVITY, RESOURCE_TOPIC));
        }
        return value;
    }

    private static String optional(String value) {
        return blank(value) ? null : value.trim().toUpperCase();
    }

    private static String optionalRaw(String value) {
        return blank(value) ? null : value.trim();
    }

    private static void requireCoordinates(String longitude, String latitude) {
        try {
            double lon = Double.parseDouble(longitude.trim());
            double lat = Double.parseDouble(latitude.trim());
            if (Double.isNaN(lon) || Double.isInfinite(lon) || lon < -180D || lon > 180D
                    || Double.isNaN(lat) || Double.isInfinite(lat) || lat < -90D || lat > 90D) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new ServiceException("longitude/latitude 必须为合法经纬度", 400);
        }
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    private static ServiceException invalid(String field, Object values) {
        return new ServiceException(field + " 枚举无效，支持值: " + values, 400);
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) out.put(String.valueOf(values[i]), values[i + 1]);
        return out;
    }
}
