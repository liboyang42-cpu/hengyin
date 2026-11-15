package com.chengyinhub.business.merchantproof;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Q1 merchant-reach: the weekly proof that the game brings customers to a shop. Pure arithmetic over "touches"
 * (one row per committed event at this shop); {@link MerchantWeeklyProofService} reads the rows.
 *
 * <p>Definitions (owner-facing, also returned in {@code definitions}):
 * <ul>
 *   <li>verified visit: one player on one local day with at least one counter verification by the shop's staff
 *       (ticket, coupon or map-node redemption);</li>
 *   <li>play at the node: a player's check-in at one of the shop's map nodes, or an entry into a CITY board point
 *       the shop operates;</li>
 *   <li>redemption: every counter verification, by kind;</li>
 *   <li>returning player ({@link #COHORT_DEFINITION}): a player with any touch at this shop in the week who also had
 *       a touch in the {@value #LOOKBACK_DAYS} days before the week; every other player of the week is first-time.</li>
 * </ul>
 * The week runs Monday 00:00 to the next Monday 00:00 in {@link #ZONE}.
 */
public final class MerchantWeeklyProof {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final int LOOKBACK_DAYS = 28;
    public static final String COHORT_DEFINITION = "RETURNING_WITHIN_28_DAYS";

    public enum Kind {
        TICKET_REDEMPTION(true), COUPON_REDEMPTION(true), NODE_REDEMPTION(true), NODE_CHECKIN(false), CITY_POINT_VISIT(false);
        final boolean staffVerified;
        Kind(boolean staffVerified) { this.staffVerified = staffVerified; }
    }

    public record Touch(long memberId, Instant at, Kind kind) {}

    private MerchantWeeklyProof() {}

    /** Monday of the week that holds {@code day}. */
    public static LocalDate weekStart(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }

    /**
     * @param week        touches inside [weekStart, weekStart + 7 days)
     * @param earlier     member ids with a touch inside [weekStart - 28 days, weekStart)
     * @param hasNode     whether the shop has a map node or a CITY board point (otherwise plays are not covered)
     */
    public static Map<String, Object> compute(LocalDate weekStart, List<Touch> week, Set<Long> earlier, boolean hasNode) {
        Map<Kind, Integer> byKind = new EnumMap<>(Kind.class);
        Set<String> verifiedDays = new HashSet<>();
        Set<Long> players = new HashSet<>();
        Set<Long> nodePlayers = new HashSet<>();
        int plays = 0;
        for (Touch t : week) {
            if (t.memberId() <= 0 || t.at() == null) continue;
            byKind.merge(t.kind(), 1, Integer::sum);
            players.add(t.memberId());
            if (t.kind().staffVerified) {
                verifiedDays.add(t.memberId() + "@" + t.at().atZone(ZONE).toLocalDate());
            } else {
                plays++;
                nodePlayers.add(t.memberId());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "REPORTED");
        out.put("weekStart", weekStart.toString());
        out.put("weekEnd", weekStart.plusDays(7).toString());
        out.put("timezone", ZONE.getId());

        Map<String, Object> visits = measured();
        visits.put("count", verifiedDays.size());
        out.put("verifiedVisits", visits);

        if (hasNode) {
            Map<String, Object> node = measured();
            node.put("count", plays);
            node.put("players", nodePlayers.size());
            node.put("checkins", byKind.getOrDefault(Kind.NODE_CHECKIN, 0));
            node.put("cityPointVisits", byKind.getOrDefault(Kind.CITY_POINT_VISIT, 0));
            out.put("playsAtNode", node);
        } else {
            out.put("playsAtNode", status("MISSING_COVERAGE"));
        }

        Map<String, Object> redemptions = measured();
        int ticket = byKind.getOrDefault(Kind.TICKET_REDEMPTION, 0);
        int coupon = byKind.getOrDefault(Kind.COUPON_REDEMPTION, 0);
        int node = byKind.getOrDefault(Kind.NODE_REDEMPTION, 0);
        redemptions.put("count", ticket + coupon + node);
        redemptions.put("ticket", ticket);
        redemptions.put("coupon", coupon);
        redemptions.put("cityNode", node);
        out.put("redemptions", redemptions);

        out.put("returningPlayers", cohort(players, earlier));
        return out;
    }

    /**
     * The cohort split shared with the CITY value report. MEASURED keeps {@code count} = returning players and
     * {@code rate} = "returning/players" so the existing app metric decoder reads it unchanged.
     */
    public static Map<String, Object> cohort(Set<Long> players, Set<Long> earlier) {
        if (players.isEmpty()) {
            Map<String, Object> missing = status("MISSING_COVERAGE");
            missing.put("definition", COHORT_DEFINITION);
            return missing;
        }
        int returning = 0;
        for (Long p : players) if (earlier.contains(p)) returning++;
        Map<String, Object> m = measured();
        m.put("definition", COHORT_DEFINITION);
        m.put("lookbackDays", LOOKBACK_DAYS);
        m.put("count", returning);
        m.put("rate", returning + "/" + players.size());
        m.put("players", players.size());
        m.put("returning", returning);
        m.put("firstTime", players.size() - returning);
        return m;
    }

    private static Map<String, Object> measured() { return status("MEASURED"); }

    private static Map<String, Object> status(String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", value);
        return m;
    }
}
