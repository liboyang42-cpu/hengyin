package com.chengyinhub.business.locationtrust;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Reads the optional fix-quality fields the app sends with every location write:
 * {@code locAccuracy} (m), {@code locTimestamp} (epoch ms), {@code locSimulated}, {@code locAccessory} ("1"/"true").
 *
 * <p>They are read from the current request rather than added to every controller signature, so existing
 * endpoints, request fingerprints and callers stay unchanged. Outside a web request (unit tests, jobs) all
 * signals are absent, which scores exactly like an old client.
 */
public final class LocationSignals {
    public static final String ACCURACY = "locAccuracy";
    public static final String TIMESTAMP = "locTimestamp";
    public static final String SIMULATED = "locSimulated";
    public static final String ACCESSORY = "locAccessory";

    private LocationSignals() {
    }

    /** Fix for these coordinates with signals from the current request; {@code accuracyFallback} covers routes that already had an accuracy field. */
    public static LocationFix current(double lat, double lng, String accuracyFallback, String timestampFallback) {
        HttpServletRequest request = request();
        String acc = param(request, ACCURACY);
        String ts = param(request, TIMESTAMP);
        return parse(lat, lng, acc != null ? acc : accuracyFallback, ts != null ? ts : timestampFallback,
                param(request, SIMULATED), param(request, ACCESSORY));
    }

    public static LocationFix current(double lat, double lng) {
        return current(lat, lng, null, null);
    }

    public static LocationFix parse(double lat, double lng, String accuracy, String timestamp, String simulated,
                                    String accessory) {
        return new LocationFix(lat, lng, parseDouble(accuracy), parseLong(timestamp), parseFlag(simulated),
                parseFlag(accessory));
    }

    static Double parseDouble(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Double.valueOf(raw.trim());   // NaN/negative kept: the policy rejects them as INVALID_ACCURACY
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            long v = Long.parseLong(raw.trim());
            return v > 0L ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Boolean parseFlag(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        if ("1".equals(v) || "true".equalsIgnoreCase(v)) return Boolean.TRUE;
        if ("0".equals(v) || "false".equalsIgnoreCase(v)) return Boolean.FALSE;
        return null;
    }

    private static HttpServletRequest request() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes s ? s.getRequest() : null;
    }

    private static String param(HttpServletRequest request, String name) {
        if (request == null) return null;
        try {
            String v = request.getParameter(name);
            return v == null || v.isBlank() ? null : v;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
