package com.chengyinhub.business.locationtrust;

import com.chengyinhub.common.utils.GeoDistanceUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * SEC-10: pure rules for CITY claim photo evidence. Verdict is REJECT if any reject reason fired, else REVIEW if
 * any review reason fired, else ACCEPT. REVIEW means "a person should look": the claim flow decides whether a
 * REVIEW evidence may back a claim; only ACCEPT evidence can be consumed automatically.
 */
public final class ClaimEvidencePolicy {

    public enum Verdict { ACCEPT, REVIEW, REJECT }

    public static final String CAMERA = "CAMERA";

    // reject reasons
    public static final String NOT_CAMERA = "NOT_CAMERA";
    public static final String PHOTO_TOO_OLD = "PHOTO_TOO_OLD";
    public static final String PHOTO_TIME_FUTURE = "PHOTO_TIME_FUTURE";
    public static final String PHOTO_FAR_FROM_PLACE = "PHOTO_FAR_FROM_PLACE";
    public static final String NOT_NEAR_PLACE = "NOT_NEAR_PLACE";
    public static final String FIX_REJECTED = "FIX_REJECTED";
    public static final String DUPLICATE_PHOTO = "DUPLICATE_PHOTO";
    public static final String NEAR_DUPLICATE_PHOTO = "NEAR_DUPLICATE_PHOTO";
    // review reasons
    public static final String PHOTO_UNREADABLE = "PHOTO_UNREADABLE";
    public static final String PHOTO_TIME_MISSING = "PHOTO_TIME_MISSING";
    public static final String NO_FIX = "NO_FIX";
    public static final String FIX_WITHHELD = "FIX_WITHHELD";
    public static final String SIMILAR_PHOTO = "SIMILAR_PHOTO";

    private ClaimEvidencePolicy() {
    }

    /**
     * @param fixDecision     scored decision of the fix sent with the evidence, null when none was sent
     * @param fix             that fix (null when none)
     * @param recentCleanFixes clean server-side fixes in the claim window, as {lat, lng, ...}
     * @param exactDuplicate  the same photo bytes were already used by someone else or for another place
     * @param nearestHamming  smallest dHash distance to earlier evidence of other claims, null when none / no hash
     */
    public record Input(String captureSource, Long photoPhash, Long photoTakenAtMs, Double photoLat, Double photoLng,
                        double placeLat, double placeLng, LocationTrustDecision fixDecision, LocationFix fix,
                        List<double[]> recentCleanFixes, boolean exactDuplicate, Integer nearestHamming) {
    }

    public record Result(Verdict verdict, List<String> reasons) {
        public String reasonText() {
            return String.join(",", reasons);
        }
    }

    public static Result evaluate(Input in, long nowMs, LocationTrustConfig cfg) {
        List<String> reject = new ArrayList<>();
        List<String> review = new ArrayList<>();
        if (!CAMERA.equals(in.captureSource())) reject.add(NOT_CAMERA);
        if (in.photoPhash() == null) review.add(PHOTO_UNREADABLE);
        Long taken = in.photoTakenAtMs();
        if (taken == null) {
            review.add(PHOTO_TIME_MISSING);
        } else if (taken > nowMs + cfg.maxFutureSkewSeconds() * 1000L) {
            reject.add(PHOTO_TIME_FUTURE);
        } else if (nowMs - taken > cfg.claimPhotoMaxAgeMinutes() * 60_000L) {
            reject.add(PHOTO_TOO_OLD);
        }
        double radius = cfg.claimRadiusMeters();
        if (in.photoLat() != null && in.photoLng() != null) {
            double d = GeoDistanceUtils.haversineMeters(in.photoLat(), in.photoLng(), in.placeLat(), in.placeLng());
            if (!Double.isFinite(d) || d > radius) reject.add(PHOTO_FAR_FROM_PLACE);
        }
        boolean fixNear = false;
        if (in.fix() != null && in.fixDecision() != null) {
            if (in.fixDecision() == LocationTrustDecision.REJECT) {
                reject.add(FIX_REJECTED);
            } else {
                double d = GeoDistanceUtils.haversineMeters(in.fix().lat(), in.fix().lng(), in.placeLat(), in.placeLng());
                if (Double.isFinite(d) && d <= radius) {
                    if (in.fixDecision() == LocationTrustDecision.ACCEPT) fixNear = true;
                    else review.add(FIX_WITHHELD);
                } else {
                    reject.add(NOT_NEAR_PLACE);
                }
            }
        }
        if (!fixNear && !reject.contains(NOT_NEAR_PLACE) && !reject.contains(FIX_REJECTED)
                && !review.contains(FIX_WITHHELD)) {
            boolean historyNear = false;
            if (in.recentCleanFixes() != null) {
                for (double[] p : in.recentCleanFixes()) {
                    if (GeoDistanceUtils.haversineMeters(p[0], p[1], in.placeLat(), in.placeLng()) <= radius) {
                        historyNear = true;
                        break;
                    }
                }
            }
            if (!historyNear) review.add(NO_FIX);
        }
        if (in.exactDuplicate()) reject.add(DUPLICATE_PHOTO);
        Integer ham = in.nearestHamming();
        if (ham != null && !in.exactDuplicate()) {
            if (ham <= cfg.claimPhashRejectHamming()) reject.add(NEAR_DUPLICATE_PHOTO);
            else if (ham <= cfg.claimPhashReviewHamming()) review.add(SIMILAR_PHOTO);
        }
        List<String> reasons = new ArrayList<>(reject);
        reasons.addAll(review);
        Verdict v = !reject.isEmpty() ? Verdict.REJECT : !review.isEmpty() ? Verdict.REVIEW : Verdict.ACCEPT;
        return new Result(v, reasons);
    }
}
