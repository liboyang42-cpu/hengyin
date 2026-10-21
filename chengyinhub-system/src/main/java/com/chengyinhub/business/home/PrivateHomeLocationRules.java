package com.chengyinhub.business.home;

import com.chengyinhub.business.home.PrivateHomeContracts.Home;
import com.chengyinhub.business.home.PrivateHomeContracts.Observation;
import com.chengyinhub.business.home.PrivateHomeContracts.Point;
import com.chengyinhub.business.home.PrivateHomeLocationPolicy.Snapshot;
import com.chengyinhub.common.exception.ServiceException;
import java.math.BigDecimal;

/** Manual one-shot fix checks. A failure is a distinct code, never a verified proof. */
public final class PrivateHomeLocationRules {
    private static final double EARTH_METERS = 6371000.0;
    private PrivateHomeLocationRules() { }

    public static String classify(Snapshot rules, Home home, Observation observation, long now) {
        if (observation == null || observation.permission == null) return "HOME_REQUEST_INVALID";
        if ("DENIED".equals(observation.permission)) return "HOME_LOCATION_DENIED";
        if (!"GRANTED".equals(observation.permission)) return "HOME_REQUEST_INVALID";
        if (observation.network == null) return "HOME_REQUEST_INVALID";
        if ("UNKNOWN".equals(observation.network)) return "HOME_NETWORK_UNKNOWN";
        if (!"KNOWN".equals(observation.network)) return "HOME_REQUEST_INVALID";
        if (observation.accuracyMeters == null || observation.accuracyMeters < 0
                || observation.accuracyMeters > rules.accuracyMaxMeters) return "HOME_ACCURACY_INSUFFICIENT";
        if (observation.observedAtEpochMillis == null) return "HOME_LOCATION_STALE";
        long age;
        try { age = Math.subtractExact(now, observation.observedAtEpochMillis); }
        catch (ArithmeticException e) { return "HOME_LOCATION_STALE"; }
        if (age < -5000 || age > rules.freshnessMillis) return "HOME_LOCATION_STALE";
        if (observation.dwellMillis == null || observation.dwellMillis < rules.dwellMillis) return "HOME_DWELL_INSUFFICIENT";
        if (home == null || home.latitude == null || home.longitude == null) return "HOME_VERSION_INVALID";
        try { validatePoint(observation); }
        catch (ServiceException e) { return e.getMessage(); }
        if (meters(home.latitude, home.longitude, observation.latitude, observation.longitude) > rules.radiusMeters)
            return "HOME_OUT_OF_RADIUS";
        return null;
    }

    private static void validatePoint(Observation observation) {
        Point point = new Point(observation.latitude, observation.longitude, observation.datum);
        if (!"WGS84".equals(point.datum) || point.latitude == null || point.longitude == null
                || point.latitude.scale() > 6 || point.longitude.scale() > 6
                || point.latitude.abs().compareTo(BigDecimal.valueOf(90)) > 0
                || point.longitude.abs().compareTo(BigDecimal.valueOf(180)) > 0)
            throw new ServiceException("HOME_POINT_INVALID");
    }

    static double meters(BigDecimal lat1, BigDecimal lon1, BigDecimal lat2, BigDecimal lon2) {
        double phi1 = Math.toRadians(lat1.doubleValue());
        double phi2 = Math.toRadians(lat2.doubleValue());
        double dPhi = Math.toRadians(lat2.subtract(lat1).doubleValue());
        double dLon = Math.toRadians(lon2.subtract(lon1).doubleValue());
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_METERS * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
