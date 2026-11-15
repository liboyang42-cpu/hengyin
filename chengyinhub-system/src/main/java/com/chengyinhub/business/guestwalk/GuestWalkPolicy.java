package com.chengyinhub.business.guestwalk;

import com.chengyinhub.business.locationtrust.LocationFix;
import com.chengyinhub.business.locationtrust.LocationTrustConfig;
import com.chengyinhub.business.locationtrust.LocationTrustDecision;
import com.chengyinhub.business.locationtrust.LocationTrustKind;
import com.chengyinhub.business.locationtrust.LocationTrustPolicy;
import com.chengyinhub.common.utils.GeoDistanceUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * R4 guest-walk (GRO-12): the contract and the scoring for one walk a player made on this device before signing in.
 *
 * <p>The walk ran on the phone only. After sign-in the app sends it once, with the fixes it recorded. Nothing here
 * trusts the client's cells by themselves:
 * <ul>
 *   <li>Strict body: known keys only, at most {@link #MAX_CELLS} geohash-7 cells and {@link #MAX_FIXES} fixes.</li>
 *   <li>Time window: the walk started at most {@link #MAX_AGE_MILLIS} (24 h) before the upload, which the app sends
 *       right after sign-in, lasted at most {@link #MAX_DURATION_MILLIS}, and no fix is in the future.</li>
 *   <li>Location trust (P8): every fix is scored with {@link LocationTrustPolicy#evaluate} against the previous
 *       accepted fix of the same track, on the device clock (the walk is history, so the server clock would call every
 *       fix stale). Poor accuracy, impossible speed and invalid fixes leave the track; a simulated fix withholds the
 *       whole walk under the configured simulated action.</li>
 *   <li>Cells count only within {@code revealTrackRadiusMeters} of an accepted fix, then within the walking-speed
 *       budget for the walk's duration, then the cap.</li>
 * </ul>
 */
public final class GuestWalkPolicy {

    public static final int MAX_BODY_CHARS = 256 * 1024;
    /** Cap on cells credited for one guest walk. */
    public static final int MAX_CELLS = 400;
    public static final int MAX_FIXES = 1500;
    /** Owner decision 2026-11-15: the walk may be at most 24 hours old at sign-in. */
    public static final long MAX_AGE_MILLIS = 24L * 60 * 60 * 1000;
    public static final long MAX_DURATION_MILLIS = 4L * 60 * 60 * 1000;
    public static final long MAX_FUTURE_SKEW_MILLIS = 5L * 60 * 1000;
    /** Fixes may sit this far outside [startedAt, finishedAt] (first fix before the start tap, clock rounding). */
    public static final long FIX_EDGE_SLACK_MILLIS = 60_000L;
    /** Tiles the budget allows before any walking time (a standing start still lifts the cells around the player). */
    public static final int BUDGET_BASE_CELLS = 30;
    /** First-walk reward: 1 XP per credited cell, capped at the daily tile XP limit of a normal walk. */
    public static final int FIRST_WALK_XP_CAP = 200;

    public static final String OUT_OF_WINDOW = "OUT_OF_WINDOW";
    public static final String NO_TRACK = "NO_TRACK";

    static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final String GEOHASH32 = "0123456789bcdefghjkmnpqrstuvwxyz";
    private static final Set<String> BODY_KEYS =
            Set.of("installId", "walkId", "startedAt", "finishedAt", "distanceM", "cells", "fixes");
    private static final Set<String> FIX_KEYS = Set.of("lat", "lng", "accuracy", "timestamp", "simulated", "accessory");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GuestWalkPolicy() {
    }

    /** Contract failure; the message is a fixed short sentence and never echoes input. */
    public static final class Rejected extends IllegalArgumentException {
        public Rejected(String message) {
            super(message);
        }
    }

    /** A parsed upload. Cells are distinct, in the order sent; fixes are sorted by device time. */
    public record Upload(String installId, String walkId, long startedAt, long finishedAt, int distanceM,
                         List<String> cells, List<LocationFix> fixes) {
    }

    /**
     * What may be credited.
     *
     * @param credited      cells to credit
     * @param withheld      true when nothing may be credited (window, simulated fix, no accepted fix)
     * @param reasons       trust reason codes, first one is the client code
     * @param acceptedTrack accepted fixes as {lat, lng}, in time order (also the stored display track)
     * @param distanceM     walked distance along the accepted track, never more than the client's own figure
     */
    public record Outcome(List<String> credited, boolean withheld, List<String> reasons, int droppedFarFromTrack,
                          int droppedOverBudget, int droppedFixes, List<double[]> acceptedTrack, int distanceM) {
        public int xp() {
            return withheld ? 0 : Math.min(FIRST_WALK_XP_CAP, credited.size());
        }

        public String code() {
            return reasons.isEmpty() ? "" : reasons.get(0);
        }
    }

    public static boolean isUuid(String value) {
        return value != null && UUID_PATTERN.matcher(value).matches();
    }

    public static boolean isGeohash7(String value) {
        if (value == null || value.length() != 7) return false;
        for (int i = 0; i < value.length(); i++) {
            if (GEOHASH32.indexOf(value.charAt(i)) < 0) return false;
        }
        return true;
    }

    public static Upload parse(String body) {
        if (body == null || body.isBlank()) throw new Rejected("内容不能为空");
        if (body.length() > MAX_BODY_CHARS) throw new Rejected("内容过大");
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new Rejected("内容格式不正确");
        }
        if (root == null || !root.isObject()) throw new Rejected("内容格式不正确");
        onlyKeys(root, BODY_KEYS);
        String installId = text(root.get("installId"));
        String walkId = text(root.get("walkId"));
        if (!isUuid(installId)) throw new Rejected("安装号格式不正确");
        if (!isUuid(walkId)) throw new Rejected("漫游编号格式不正确");
        long startedAt = millis(root.get("startedAt"));
        long finishedAt = millis(root.get("finishedAt"));
        JsonNode distance = root.get("distanceM");
        int distanceM = 0;
        if (distance != null && !distance.isNull()) {
            if (!distance.isIntegralNumber() || distance.asLong() < 0 || distance.asLong() > 200_000)
                throw new Rejected("里程格式不正确");
            distanceM = distance.asInt();
        }
        JsonNode cellsNode = root.get("cells");
        if (cellsNode == null || !cellsNode.isArray()) throw new Rejected("格子格式不正确");
        if (cellsNode.size() > MAX_CELLS) throw new Rejected("格子数量超出上限");
        LinkedHashSet<String> cells = new LinkedHashSet<>();
        for (JsonNode cell : cellsNode) {
            String code = text(cell);
            if (!isGeohash7(code)) throw new Rejected("格子格式不正确");
            cells.add(code);
        }
        JsonNode fixesNode = root.get("fixes");
        if (fixesNode == null || !fixesNode.isArray()) throw new Rejected("定位记录格式不正确");
        if (fixesNode.size() > MAX_FIXES) throw new Rejected("定位记录数量超出上限");
        List<LocationFix> fixes = new ArrayList<>(fixesNode.size());
        for (JsonNode fix : fixesNode) fixes.add(fix(fix));
        fixes.sort(Comparator.comparingLong(LocationFix::clientTimestampMs));
        return new Upload(installId, walkId, startedAt, finishedAt, distanceM, List.copyOf(cells), List.copyOf(fixes));
    }

    /** Scores the upload received at {@code nowMs}. Pure: no I/O. */
    public static Outcome evaluate(Upload upload, long nowMs, LocationTrustConfig cfg) {
        List<String> reasons = new ArrayList<>();
        if (!inWindow(upload, nowMs)) {
            reasons.add(OUT_OF_WINDOW);
            return new Outcome(List.of(), true, reasons, 0, 0, upload.fixes().size(), List.of(), 0);
        }
        List<double[]> track = new ArrayList<>();
        int droppedFixes = 0;
        boolean withheld = false;
        LocationTrustPolicy.Baseline baseline = null;
        Map<String, Integer> seen = new HashMap<>();
        double walked = 0d;
        double[] previous = null;
        for (LocationFix fix : upload.fixes()) {
            long t = fix.clientTimestampMs();
            if (t < upload.startedAt() - FIX_EDGE_SLACK_MILLIS || t > upload.finishedAt() + FIX_EDGE_SLACK_MILLIS) {
                droppedFixes++;
                continue;
            }
            String key = identityKey(fix);
            int identicalBefore = seen.getOrDefault(key, 0);
            seen.put(key, identicalBefore + 1);
            LocationTrustPolicy.Verdict verdict = cfg.scoring()
                    ? LocationTrustPolicy.evaluate(LocationTrustKind.GUEST_WALK, fix, baseline, identicalBefore, t, cfg)
                    : LocationTrustPolicy.Verdict.accept();
            for (String reason : verdict.reasons()) {
                if (!reasons.contains(reason)) reasons.add(reason);
            }
            boolean poorAccuracy = verdict.reasons().contains(LocationTrustPolicy.ACCURACY_POOR);
            if (verdict.reasons().contains(LocationTrustPolicy.SIMULATED)
                    && verdict.decision() != LocationTrustDecision.ACCEPT && cfg.enforcing()) {
                withheld = true;
            }
            boolean keep = !cfg.enforcing()
                    || (verdict.decision() == LocationTrustDecision.ACCEPT && !poorAccuracy);
            if (!keep) {
                droppedFixes++;
                continue;
            }
            double[] point = {fix.lat(), fix.lng()};
            if (previous != null) {
                double step = GeoDistanceUtils.haversineMeters(previous[0], previous[1], point[0], point[1]);
                if (Double.isFinite(step)) walked += step;
            }
            previous = point;
            track.add(point);
            baseline = new LocationTrustPolicy.Baseline(fix.lat(), fix.lng(), t, t);
        }
        int distanceM = (int) Math.min(upload.distanceM(), Math.round(walked));
        if (withheld) {
            moveFirst(reasons, LocationTrustPolicy.SIMULATED);
            return new Outcome(List.of(), true, reasons, 0, 0, droppedFixes, List.copyOf(track), distanceM);
        }
        if (track.isEmpty()) {
            reasons.add(0, NO_TRACK);
            return new Outcome(List.of(), true, reasons, 0, 0, droppedFixes, List.of(), 0);
        }
        List<String> near = LocationTrustPolicy.tilesNearTrack(upload.cells(), track, cfg);
        int far = upload.cells().size() - near.size();
        int budget = Math.min(MAX_CELLS,
                LocationTrustPolicy.revealSessionBudget(upload.startedAt(), upload.finishedAt(), BUDGET_BASE_CELLS, cfg));
        List<String> credited = near.size() > budget ? List.copyOf(near.subList(0, budget)) : List.copyOf(near);
        int over = near.size() - credited.size();
        return new Outcome(credited, false, reasons, far, over, droppedFixes, List.copyOf(track), distanceM);
    }

    static boolean inWindow(Upload upload, long nowMs) {
        long started = upload.startedAt();
        long finished = upload.finishedAt();
        if (finished < started) return false;
        if (finished - started > MAX_DURATION_MILLIS) return false;
        if (started < nowMs - MAX_AGE_MILLIS) return false;
        if (finished > nowMs + MAX_FUTURE_SKEW_MILLIS) return false;
        for (LocationFix fix : upload.fixes()) {
            if (fix.clientTimestampMs() > nowMs + MAX_FUTURE_SKEW_MILLIS) return false;
        }
        return true;
    }

    /** Simplified display track for the walk record card, as [[lat,lng],...]. */
    public static String trackJson(List<double[]> track) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < track.size(); i++) {
            if (i > 0) out.append(',');
            out.append('[').append(track.get(i)[0]).append(',').append(track.get(i)[1]).append(']');
        }
        return out.append(']').toString();
    }

    private static void moveFirst(List<String> reasons, String reason) {
        if (reasons.remove(reason)) reasons.add(0, reason);
    }

    private static String identityKey(LocationFix fix) {
        return String.format(java.util.Locale.ROOT, "%.6f,%.6f,%s", fix.lat(), fix.lng(),
                fix.accuracyMeters() == null ? "-" : String.format(java.util.Locale.ROOT, "%.1f", fix.accuracyMeters()));
    }

    private static LocationFix fix(JsonNode node) {
        if (node == null || !node.isObject()) throw new Rejected("定位记录格式不正确");
        onlyKeys(node, FIX_KEYS);
        double lat = number(node.get("lat"));
        double lng = number(node.get("lng"));
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180)
            throw new Rejected("定位记录格式不正确");
        JsonNode accuracyNode = node.get("accuracy");
        Double accuracy = null;
        if (accuracyNode != null && !accuracyNode.isNull()) accuracy = number(accuracyNode);
        long timestamp = millis(node.get("timestamp"));
        return new LocationFix(lat, lng, accuracy, timestamp, flag(node.get("simulated")), flag(node.get("accessory")));
    }

    private static Boolean flag(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber() && (node.asInt() == 0 || node.asInt() == 1)) return node.asInt() == 1;
        throw new Rejected("定位记录格式不正确");
    }

    private static void onlyKeys(JsonNode node, Set<String> allowed) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) throw new Rejected("内容包含未知字段");
        }
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) throw new Rejected("内容格式不正确");
        return node.asText();
    }

    private static double number(JsonNode node) {
        if (node == null || !node.isNumber()) throw new Rejected("定位记录格式不正确");
        return node.asDouble();
    }

    private static long millis(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || node.asLong() <= 0L) throw new Rejected("时间格式不正确");
        return node.asLong();
    }
}
