package com.chengyinhub.business.service.support;

import com.chengyinhub.common.exception.ServiceException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Declarations belong to the existing upload receipt; they are not proof of a licence. */
public final class AppMediaProvenance {
    public static final String LICENSE_MISSING = "ASSET_LICENSE_MISSING";
    private static final Set<String> TYPES = Set.of("original", "licensed", "ai-generated", "public-domain", "unknown");
    private AppMediaProvenance() { }

    public static Map<String, Object> normalize(Map<String, Object> input) {
        if (input == null || !Set.of("sourceType", "licenseNote", "attribution", "aiModel", "createdAt").containsAll(input.keySet()))
            throw new ServiceException("素材来源声明字段无效", 422);
        Map<String, Object> result = new LinkedHashMap<>();
        String source = text(input, "sourceType", 24);
        if (source == null || !TYPES.contains(source)) throw new ServiceException("素材来源类型无效", 422);
        result.put("sourceType", source);
        result.put("licenseNote", text(input, "licenseNote", 2000));
        result.put("attribution", text(input, "attribution", 255));
        result.put("aiModel", text(input, "aiModel", 100));
        String created = text(input, "createdAt", 40);
        try {
            Instant instant = created == null ? null : Instant.parse(created);
            if (instant != null && (instant.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                    || !instant.isBefore(Instant.parse("9999-12-31T23:59:59Z")))) throw new IllegalArgumentException();
            result.put("sourceCreatedAt", instant == null ? null : Timestamp.from(instant));
        } catch (DateTimeParseException | IllegalArgumentException invalid) {
            throw new ServiceException("素材创作时间须为有效的 UTC ISO 时间", 422);
        }
        return result;
    }

    private static String text(Map<String, Object> input, String key, int max) {
        Object value = input.get(key);
        if (value == null) return null;
        if (!(value instanceof String text) || text.length() > max || text.indexOf('\0') >= 0)
            throw new ServiceException("素材来源声明字段无效：" + key, 422);
        return text.trim().isEmpty() ? null : text.trim();
    }

    public static boolean missing(Map<String, Object> row) {
        Object source = row.get("sourceType"), note = row.get("licenseNote");
        return !(source instanceof String type) || !TYPES.contains(type) || "unknown".equals(type)
                || !(note instanceof String text) || text.isBlank();
    }

    public static void requirePublication(Map<String, Object> row) {
        // Null is an untouched pre-declaration receipt; preserve its existing publication contract.
        if (row.get("sourceType") != null && missing(row))
            throw new ServiceException(LICENSE_MISSING + "：请补充素材来源和授权声明后再公开", 422);
    }

    public static Map<String, Object> ownerView(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceType", row.get("sourceType") == null ? "unknown" : row.get("sourceType"));
        result.put("licenseNote", row.get("licenseNote"));
        result.put("attribution", row.get("attribution"));
        result.put("aiModel", row.get("aiModel"));
        result.put("createdAt", row.get("sourceCreatedAt"));
        return result;
    }

    public static Map<String, Object> publicView(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object source = row.get("sourceType");
        result.put("sourceType", source instanceof String type && TYPES.contains(type) ? type : "unknown");
        result.put("attribution", row.get("attribution"));
        result.put("aiGenerated", "ai-generated".equals(source));
        result.put("sourcePendingConfirmation", "unknown".equals(result.get("sourceType")));
        return result;
    }
}
