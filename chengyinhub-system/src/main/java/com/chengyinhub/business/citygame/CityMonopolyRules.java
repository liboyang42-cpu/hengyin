package com.chengyinhub.business.citygame;

import com.chengyinhub.common.utils.GeoDistanceUtils;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, side-effect free City Monopoly rules (CITY-MONOPOLY-SPEC §2), parameterised by {@link CityMonopolyConfig}.
 * Every amount is 积分 (owner ruling 2026-11-15). Services only orchestrate storage around these functions.
 */
public final class CityMonopolyRules {
    private CityMonopolyRules() {}

    private static final String P = "city.monopoly.";
    public static final int MAX_LEVEL = 3;

    // ------------------------------------------------------------------ tiers & upgrades

    public static int normalizeTier(Integer tier) {
        if (tier == null) return 1;
        return Math.max(1, Math.min(4, tier));
    }

    public static long claimCost(CityMonopolyConfig c, int tier) {
        return Math.round(c.num(P + "tiers.T" + normalizeTier(tier) + ".claim_cost"));
    }

    public static int baseYield(CityMonopolyConfig c, int tier) {
        return (int) Math.round(c.num(P + "tiers.T" + normalizeTier(tier) + ".base_yield"));
    }

    /** Cost to go from {@code fromLevel} to {@code fromLevel + 1}; -1 when already at Lv3. */
    public static long upgradeCost(CityMonopolyConfig c, int tier, int fromLevel) {
        List<Double> factors = c.list(P + "upgrade.cost_factor");
        if (fromLevel < 0 || fromLevel >= factors.size()) return -1;
        return Math.round(claimCost(c, tier) * factors.get(fromLevel));
    }

    /** Total invested at a level: claim cost plus every upgrade up to that level (T1 Lv3 = 210). */
    public static long investedAt(CityMonopolyConfig c, int tier, int level) {
        long total = claimCost(c, tier);
        for (int l = 0; l < Math.min(level, MAX_LEVEL); l++) total += upgradeCost(c, tier, l);
        return total;
    }

    public static double levelYieldMultiplier(CityMonopolyConfig c, int level) {
        List<Double> u = c.list(P + "upgrade.yield_multiplier");
        return u.get(Math.max(0, Math.min(level, u.size() - 1)));
    }

    // ------------------------------------------------------------------ yield (rent)

    public enum SetState { NONE, MAJORITY, MONOPOLY }

    public enum Weather { CLEAR, LIGHT_RAIN, MODERATE_RAIN, RED_ALERT }

    public static SetState setState(CityMonopolyConfig c, int ownerHeldInDistrict, int ownableInDistrict) {
        if (ownableInDistrict <= 0 || ownerHeldInDistrict <= 0) return SetState.NONE;
        if (ownerHeldInDistrict >= ownableInDistrict) return SetState.MONOPOLY;
        double share = (double) ownerHeldInDistrict / ownableInDistrict;
        return share >= c.num(P + "yield.set_bonus.majority_share") ? SetState.MAJORITY : SetState.NONE;
    }

    public static double setMultiplier(CityMonopolyConfig c, SetState s) {
        return switch (s) {
            case MONOPOLY -> c.num(P + "yield.set_bonus.monopoly");
            case MAJORITY -> c.num(P + "yield.set_bonus.majority");
            default -> 1.0;
        };
    }

    public static double timeMultiplier(CityMonopolyConfig c, LocalTime t) {
        int minute = t.getHour() * 60 + t.getMinute();
        for (CityMonopolyConfig.TimeBand b : c.timeBands()) if (b.contains(minute)) return b.multiplier();
        return 0.0;
    }

    public static double weatherMultiplier(CityMonopolyConfig c, Weather w) {
        if (w == null) return 1.0;
        return switch (w) {
            case LIGHT_RAIN -> c.num(P + "yield.weather.light_rain");
            case MODERATE_RAIN -> c.num(P + "yield.weather.moderate_rain");
            case RED_ALERT -> 0.0; // SAFETY_BLOCKED
            default -> 1.0;
        };
    }

    /** Inputs for one rent computation. */
    public record YieldInput(int tier, int level, SetState set, LocalTime localTime, Weather weather,
                             double eventMultiplier, double launchMultiplier, boolean teamBonus, int ownerCharmLevel,
                             boolean weathered, boolean concentrationPenalty) {}

    /** Result: base, every multiplier, product before and after the cap, and the rounded raw rent (pre daily cap). */
    public record YieldQuote(int base, Map<String, Double> multipliers, double product, double cappedProduct,
                             boolean capped, long raw) {}

    /**
     * yield = round(base[tier] × min(M_CAP, U × S_set × T_time × W_env × B_team × (1 + 0.002 × charm))).
     * The event multiplier is clamped to event_multiplier_max; weathering (×0.5) and the concentration penalty (×0.5)
     * are applied after the cap so they always bite.
     */
    public static YieldQuote computeYield(CityMonopolyConfig c, YieldInput in) {
        int base = baseYield(c, in.tier());
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("level", levelYieldMultiplier(c, in.level()));
        m.put("set", setMultiplier(c, in.set()));
        m.put("time", timeMultiplier(c, in.localTime()));
        double event = Math.max(0.0, Math.min(in.eventMultiplier() <= 0 ? 1.0 : in.eventMultiplier(),
                c.num(P + "yield.event_multiplier_max")));
        m.put("env", weatherMultiplier(c, in.weather()) * event);
        double launch = in.launchMultiplier() <= 0 ? 1.0 : Math.min(2.0, in.launchMultiplier());
        m.put("launch", launch);
        m.put("team", in.teamBonus() ? c.num(P + "yield.team_bonus.m") : 1.0);
        int charm = Math.max(0, Math.min(50, in.ownerCharmLevel()));
        m.put("charm", 1.0 + c.num(P + "yield.charm_bonus_per_level") * charm);
        double product = 1.0;
        for (double v : m.values()) product *= v;
        double cap = c.num(P + "yield.multiplier_cap");
        double capped = Math.min(cap, product);
        double post = 1.0;
        if (in.weathered()) { post *= c.num(P + "decay.weathered.yield"); m.put("weathered", c.num(P + "decay.weathered.yield")); }
        if (in.concentrationPenalty()) {
            post *= c.num(P + "abuse.concentration.penalty");
            m.put("concentration", c.num(P + "abuse.concentration.penalty"));
        }
        long raw = Math.round(base * capped * post);
        return new YieldQuote(base, m, product, capped, product > cap, Math.max(0, raw));
    }

    /** Owner daily threshold T = 200 + min(100, 2 × charm). */
    public static long ownerDailyThreshold(CityMonopolyConfig c, int charmLevel) {
        long soft = Math.round(c.num(P + "yield.owner_daily.soft_threshold"));
        long bonus = Math.min(Math.round(c.num(P + "yield.owner_daily.charm_threshold_max")),
                Math.round(c.num(P + "yield.owner_daily.charm_threshold_per_level") * Math.max(0, charmLevel)));
        return soft + bonus;
    }

    /** Cumulative granted amount for a cumulative raw amount R: min(R,T) + min(0.5 × max(R−T,0), 100). */
    public static long ownerDailyGrantedTotal(CityMonopolyConfig c, long cumulativeRaw, int charmLevel) {
        long t = ownerDailyThreshold(c, charmLevel);
        long over = Math.max(0, cumulativeRaw - t);
        long overGranted = Math.min(Math.round(c.num(P + "yield.owner_daily.over_max")),
                (long) Math.floor(over * c.num(P + "yield.owner_daily.over_rate")));
        return Math.min(cumulativeRaw, t) + overGranted;
    }

    /** Daily-cap grant for one new raw amount given the owner's raw already recorded today. */
    public record CapResult(long granted, String capReason) {}

    public static CapResult applyOwnerDailyCap(CityMonopolyConfig c, long rawToday, long newRaw, int charmLevel) {
        long before = ownerDailyGrantedTotal(c, rawToday, charmLevel);
        long after = ownerDailyGrantedTotal(c, rawToday + Math.max(0, newRaw), charmLevel);
        long granted = Math.max(0, after - before);
        String reason = null;
        long t = ownerDailyThreshold(c, charmLevel);
        if (granted < newRaw) {
            reason = after >= t + Math.round(c.num(P + "yield.owner_daily.over_max")) ? "OWNER_DAILY_MAX" : "OWNER_DAILY_SOFT";
        }
        return new CapResult(granted, reason);
    }

    public static long ownerDailyMax(CityMonopolyConfig c, int charmLevel) {
        return ownerDailyThreshold(c, charmLevel) + Math.round(c.num(P + "yield.owner_daily.over_max"));
    }

    /** Facts about one visitor check-in on a held property, gathered by the service. */
    public record VisitFacts(boolean selfVisit, boolean sameTeam, boolean merchantStaff,
                             double distanceM, Double hAccM, double timeMultiplier, boolean safetyBlocked,
                             long visitorAccountAgeDays, int visitorLevel, boolean visitorPhoneBound,
                             int distinctOwnersVisited14d, int offboardCheckins14d,
                             int otherYieldingAccountsOnDevice30d, boolean deviceFrozen,
                             int visitorYieldingToday, int visitorThisPropertyToday,
                             int pairThisPropertyInWindow, int pairOwnerInWindow, int propertyVisitsToday,
                             int reciprocalAtoB, int reciprocalBtoA, boolean visitorBanned, boolean ownerBanned) {}

    /** Ordered reasons why a check-in produces no rent; empty means it yields. Fog/XP are unaffected either way. */
    public static List<String> yieldIneligibility(CityMonopolyConfig c, VisitFacts f) {
        List<String> r = new ArrayList<>();
        if (f.selfVisit()) { r.add("SELF_VISIT"); return r; }
        if (f.sameTeam()) r.add("SAME_TEAM");
        if (f.merchantStaff()) r.add("MERCHANT_STAFF");
        if (f.distanceM() > c.num(P + "yield.visit_radius_m")) r.add("OUT_OF_RADIUS");
        if (f.hAccM() == null || f.hAccM() < 0 || f.hAccM() > c.num(P + "yield.visit_max_h_accuracy_m")) r.add("POOR_ACCURACY");
        if (f.safetyBlocked()) r.add("SAFETY_BLOCKED");
        if (f.timeMultiplier() <= 0) r.add("QUIET_HOURS");
        if (f.visitorBanned()) r.add("VISITOR_BANNED");
        if (f.ownerBanned()) r.add("OWNER_BANNED");
        if (f.visitorAccountAgeDays() < c.num(P + "abuse.visitor_min_account_age_d")) r.add("VISITOR_ACCOUNT_TOO_NEW");
        if (f.visitorLevel() < c.num(P + "abuse.visitor_min_level")) r.add("VISITOR_LEVEL_TOO_LOW");
        if (!f.visitorPhoneBound()) r.add("VISITOR_PHONE_UNBOUND");
        if (f.distinctOwnersVisited14d() < c.num(P + "abuse.visitor_diversity.min_distinct_owners")
                && f.offboardCheckins14d() < c.num(P + "abuse.visitor_diversity.or_min_offboard_checkins")) {
            r.add("VISITOR_DIVERSITY");
        }
        if (f.deviceFrozen()) r.add("DEVICE_FROZEN");
        else if (f.otherYieldingAccountsOnDevice30d() >= c.num(P + "abuse.device.max_yielding_accounts_per_30d")) r.add("DEVICE_SHARED");
        if (f.visitorYieldingToday() >= c.num(P + "yield.visitor_daily_yielding_checkins")) r.add("VISITOR_DAILY_CAP");
        if (f.visitorThisPropertyToday() >= c.num(P + "yield.visitor_per_property_per_day")) r.add("VISITOR_PROPERTY_DAILY_CAP");
        if (f.pairThisPropertyInWindow() >= 1) r.add("PAIR_PROPERTY_CAP");
        if (f.pairOwnerInWindow() >= c.num(P + "yield.pair_cap.per_owner_per_7d")) r.add("PAIR_OWNER_CAP");
        if (f.propertyVisitsToday() >= c.num(P + "yield.per_property_daily_visits")) r.add("PROPERTY_DAILY_CAP");
        if (reciprocal(c, f.reciprocalAtoB(), f.reciprocalBtoA())) r.add("RECIPROCITY");
        return r;
    }

    public static boolean reciprocal(CityMonopolyConfig c, int aToB, int bToA) {
        double min = c.num(P + "abuse.reciprocity.min_each_way");
        return aToB >= min && bToA >= min;
    }

    /** Concentration rule: 7d rent > 300 and top-3 visitors > 60% (or HHI > 0.35). */
    public static boolean concentrated(CityMonopolyConfig c, Map<Long, Long> yieldByVisitor7d) {
        long total = 0;
        for (long v : yieldByVisitor7d.values()) total += v;
        if (total <= c.num(P + "abuse.concentration.min_yield")) return false;
        List<Long> sorted = new ArrayList<>(yieldByVisitor7d.values());
        sorted.sort(Comparator.reverseOrder());
        long top3 = 0;
        for (int i = 0; i < Math.min(3, sorted.size()); i++) top3 += sorted.get(i);
        double hhi = 0;
        for (long v : sorted) { double s = (double) v / total; hhi += s * s; }
        return (double) top3 / total > c.num(P + "abuse.concentration.top3_share") || hhi > c.num(P + "abuse.concentration.hhi");
    }

    // ------------------------------------------------------------------ claim checks

    public record Sample(long tMillis, double lat, double lng, Double hAcc) {}

    /** Claim / upgrade / challenge evidence. Photo fields are ignored when {@code requirePhoto} is false. */
    public record Evidence(double lat, double lng, Double hAcc, List<Sample> samples, boolean simulated,
                           boolean attestVerified, boolean merchantQr, boolean inAppCamera, Long capturedAtMillis,
                           Double captureLat, Double captureLng, Long phash, Double recaptureScore) {}

    public record ClaimContext(double anchorLat, double anchorLng, long nowMillis, LocalTime localTime,
                               boolean requirePhoto, long accountAgeDays, int accountLevel, boolean phoneBound,
                               int claimsToday, int failedOnPropertyToday, boolean lostWithinCooldown,
                               int holdings, int exploreLevel, boolean staffOfStore, boolean safetyBlocked,
                               boolean tileLit, List<Long> ownPriorHashes, List<Long> otherHashes, boolean banned,
                               boolean checkAccountGates) {}

    public record ClaimVerdict(List<String> reasons, List<String> auditFlags, double distanceM, int dwellSeconds,
                               double maxSpeedMps, double riskScore) {
        public boolean ok() { return reasons.isEmpty(); }
    }

    public static ClaimVerdict evaluateClaim(CityMonopolyConfig c, Evidence ev, ClaimContext ctx) {
        List<String> r = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        double dist = GeoDistanceUtils.haversineMeters(ev.lat(), ev.lng(), ctx.anchorLat(), ctx.anchorLng());
        if (ctx.banned()) r.add("CITY_BANNED");
        if (ctx.safetyBlocked()) r.add("SAFETY_BLOCKED");
        if (!withinHours(c, ctx.localTime())) r.add("OUTSIDE_HOURS");
        if (dist > c.num(P + "claim.radius_m")) r.add("OUT_OF_RADIUS");
        double accLimit = ev.merchantQr() && c.flag(P + "claim.merchant_qr_fallback.enabled")
                ? c.num(P + "claim.merchant_qr_fallback.max_h_accuracy_m") : c.num(P + "claim.max_h_accuracy_m");
        if (ev.hAcc() == null || ev.hAcc() < 0 || ev.hAcc() > accLimit) r.add("POOR_ACCURACY");
        Dwell dwell = dwell(c, ev.samples(), ctx.anchorLat(), ctx.anchorLng(), ctx.nowMillis());
        if (dwell.samples < c.num(P + "claim.dwell.min_samples")) r.add("DWELL_TOO_FEW_SAMPLES");
        if (dwell.spanSeconds < c.num(P + "claim.dwell.min_span_s")) r.add("DWELL_TOO_SHORT");
        if (dwell.outsideSpread) r.add("DWELL_OUTSIDE_SPREAD");
        if (dwell.maxSpeed > c.num(P + "claim.max_speed_mps")) r.add("SPEED_IMPLAUSIBLE");
        if (dwell.maxSpeed > c.num(P + "claim.teleport_audit_mps")) flags.add("TELEPORT_AUDIT");
        if (ev.simulated() && c.flag(P + "claim.reject_simulated_location")) { r.add("SIMULATED_LOCATION"); flags.add("SPOOF"); }
        if (c.flag(P + "claim.require_app_attest") && !ev.attestVerified()) r.add("APP_ATTEST_REQUIRED");
        double risk = 0.0;
        if (ctx.requirePhoto()) {
            if (c.flag(P + "claim.photo.in_app_camera_only") && !ev.inAppCamera()) r.add("PHOTO_NOT_IN_APP_CAMERA");
            if (ev.capturedAtMillis() == null
                    || ctx.nowMillis() - ev.capturedAtMillis() > c.num(P + "claim.photo.max_capture_age_s") * 1000
                    || ev.capturedAtMillis() - ctx.nowMillis() > 5_000) {
                r.add("PHOTO_TOO_OLD");
            }
            if (ev.captureLat() == null || ev.captureLng() == null
                    || GeoDistanceUtils.haversineMeters(ev.captureLat(), ev.captureLng(), ev.lat(), ev.lng())
                    > c.num(P + "claim.photo.max_capture_to_request_m")) {
                r.add("PHOTO_LOCATION_MISMATCH");
            }
            if (ev.phash() != null) {
                for (Long h : ctx.ownPriorHashes()) {
                    if (h != null && hamming(h, ev.phash()) <= c.num(P + "claim.photo.phash_reuse_self_max_hamming")) { r.add("PHOTO_REUSED"); break; }
                }
                for (Long h : ctx.otherHashes()) {
                    if (h != null && hamming(h, ev.phash()) <= c.num(P + "claim.photo.phash_copy_other_max_hamming")) { r.add("PHOTO_COPIED"); break; }
                }
            } else {
                r.add("PHOTO_HASH_MISSING");
            }
            double recapture = ev.recaptureScore() == null ? 0.0 : ev.recaptureScore();
            if (recapture >= c.num(P + "claim.photo.screen_recapture_reject_score")) r.add("PHOTO_SCREEN_RECAPTURE");
            risk = Math.max(risk, recapture);
        }
        if (ctx.checkAccountGates()) {
            if (ctx.accountAgeDays() < c.num(P + "claim.min_account_age_days")) r.add("ACCOUNT_TOO_NEW");
            if (ctx.accountLevel() < c.num(P + "claim.min_account_level")) r.add("ACCOUNT_LEVEL_TOO_LOW");
            if (!ctx.phoneBound()) r.add("PHONE_UNBOUND");
            if (ctx.claimsToday() >= c.num(P + "claim.per_member_per_day")) r.add("DAILY_CLAIM_LIMIT");
            if (ctx.failedOnPropertyToday() >= c.num(P + "claim.per_property_failed_per_day")) r.add("PROPERTY_FAILED_LIMIT");
            if (ctx.lostWithinCooldown()) r.add("RECLAIM_COOLDOWN");
            if (ctx.holdings() >= holdingLimit(c, ctx.exploreLevel())) r.add("HOLDING_LIMIT");
            if (ctx.staffOfStore() && c.flag(P + "abuse.staff_cannot_claim_own_store")) r.add("STAFF_OWN_STORE");
            if ("require_lit_tile".equals(c.str(P + "board.fog_gate")) && !ctx.tileLit()) r.add("FOG_NOT_LIT");
        }
        if (ev.simulated()) risk = Math.max(risk, 1.0);
        if (dwell.maxSpeed > c.num(P + "claim.teleport_audit_mps")) risk = Math.max(risk, 0.8);
        return new ClaimVerdict(Collections.unmodifiableList(r), flags, dist, dwell.spanSeconds, dwell.maxSpeed, risk);
    }

    /** Whether a passed claim photo goes to human review: risk ≥ threshold, or a deterministic 5% sample. */
    public static boolean needsHumanReview(CityMonopolyConfig c, double riskScore, String sampleKey) {
        if (riskScore >= c.num(P + "claim.photo.human_review_risk_threshold")) return true;
        long mixed = java.util.UUID.nameUUIDFromBytes((sampleKey == null ? "" : sampleKey).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .getMostSignificantBits();
        int bucket = (int) Math.floorMod(mixed, 10_000L);
        return bucket < c.num(P + "claim.photo.human_review_sample_rate") * 10_000;
    }

    public static boolean withinHours(CityMonopolyConfig c, LocalTime t) {
        int m = t.getHour() * 60 + t.getMinute();
        return m >= CityMonopolyConfig.minute(c.str(P + "claim.hours.open")) && m < CityMonopolyConfig.minute(c.str(P + "claim.hours.close"));
    }

    public static int hamming(long a, long b) { return Long.bitCount(a ^ b); }

    private record Dwell(int samples, int spanSeconds, boolean outsideSpread, double maxSpeed) {}

    private static Dwell dwell(CityMonopolyConfig c, List<Sample> samples, double aLat, double aLng, long now) {
        if (samples == null || samples.isEmpty()) return new Dwell(0, 0, false, 0);
        long window = Math.round(c.num(P + "claim.dwell.window_s") * 1000);
        List<Sample> in = new ArrayList<>();
        for (Sample s : samples) {
            if (s != null && s.tMillis() <= now + 5_000 && now - s.tMillis() <= window) in.add(s);
        }
        in.sort(Comparator.comparingLong(Sample::tMillis));
        boolean outside = false;
        double maxSpeed = 0;
        for (int i = 0; i < in.size(); i++) {
            Sample s = in.get(i);
            if (GeoDistanceUtils.haversineMeters(s.lat(), s.lng(), aLat, aLng) > c.num(P + "claim.dwell.max_spread_m")) outside = true;
            if (i > 0) {
                Sample p = in.get(i - 1);
                double dt = Math.max(0.5, (s.tMillis() - p.tMillis()) / 1000.0);
                maxSpeed = Math.max(maxSpeed, GeoDistanceUtils.haversineMeters(p.lat(), p.lng(), s.lat(), s.lng()) / dt);
            }
        }
        int span = in.size() < 2 ? 0 : (int) ((in.get(in.size() - 1).tMillis() - in.get(0).tMillis()) / 1000);
        return new Dwell(in.size(), span, outside, maxSpeed);
    }

    /** Personal holding limit: 9, +1 at explore Lv15/30/45, max 12. */
    public static int holdingLimit(CityMonopolyConfig c, int exploreLevel) {
        int limit = c.integer(P + "claim.holding_limit.base");
        for (double lv : c.list(P + "claim.holding_limit.explore_bonus_levels")) if (exploreLevel >= lv) limit++;
        return Math.min(limit, c.integer(P + "claim.holding_limit.max"));
    }

    // ------------------------------------------------------------------ protection

    public enum ProtectionEvent { CLAIM, DEFENSE, TAKEOVER }

    public static long protectionHours(CityMonopolyConfig c, ProtectionEvent e, boolean finale) {
        return switch (e) {
            case CLAIM -> Math.round(finale ? c.num(P + "season.phases.finale_claim_protection_h") : c.num(P + "protection.after_claim_h"));
            case DEFENSE -> Math.round(c.num(P + "protection.after_defense_h"));
            case TAKEOVER -> Math.round(c.num(P + "protection.after_takeover_h"));
        };
    }

    /** Shield: +12 h on top of current protection, never more than 48 h from now; null when on cooldown. */
    public static Long shieldUntil(CityMonopolyConfig c, long nowMillis, Long protectedUntil, Long lastShieldAt) {
        long cooldown = Math.round(c.num(P + "protection.shield.per_property_cooldown_d") * 86_400_000L);
        if (lastShieldAt != null && nowMillis - lastShieldAt < cooldown) return null;
        long base = protectedUntil == null ? nowMillis : Math.max(nowMillis, protectedUntil);
        long next = base + Math.round(c.num(P + "protection.shield.hours") * 3_600_000L);
        return Math.min(next, nowMillis + Math.round(c.num(P + "protection.shield.max_total_h") * 3_600_000L));
    }

    // ------------------------------------------------------------------ challenge

    public enum TaskResult { NONE, FAIL, PASS, PERFECT }

    public static long challengeFee(CityMonopolyConfig c, long invested, boolean finale, boolean revenge) {
        double fee = Math.max(c.num(P + "challenge.fee.min"), c.num(P + "challenge.fee.rate_of_invested") * invested);
        if (finale) fee *= 1.0 - c.num(P + "season.phases.finale_challenge_discount");
        if (revenge) fee *= 1.0 - c.num(P + "challenge.revenge_discount.rate");
        return Math.round(fee);
    }

    public static int challengeDc(CityMonopolyConfig c, int level, boolean ownerVisitedRecently, SetState set, boolean weathered) {
        double dc = c.num(P + "challenge.dc.base") + c.num(P + "challenge.dc.per_level") * level;
        if (ownerVisitedRecently) dc += c.num(P + "challenge.dc.recent_visit");
        if (set == SetState.MAJORITY) dc += c.num(P + "challenge.dc.majority");
        if (set == SetState.MONOPOLY) dc += c.num(P + "challenge.dc.monopoly");
        if (weathered) dc += c.num(P + "challenge.dc.weathered");
        return (int) Math.max(1, Math.min(c.num(P + "challenge.dc.max"), Math.round(dc)));
    }

    public record ModInput(TaskResult task, int insightLevel, int staminaLevel, double kmLastWindow,
                           int assistsInRadius, int charmLevel) {}

    public record Mods(Map<String, Integer> parts, int total) {}

    public static Mods challengeMods(CityMonopolyConfig c, ModInput in) {
        Map<String, Integer> parts = new LinkedHashMap<>();
        int task = in.task() == TaskResult.PERFECT ? c.integer(P + "challenge.mods.task_perfect")
                : in.task() == TaskResult.PASS ? c.integer(P + "challenge.mods.task_pass") : 0;
        parts.put("task", task);
        int insight = 0;
        for (Map.Entry<String, Double> e : c.levels(P + "challenge.mods.insight").entrySet()) {
            if (in.insightLevel() >= Integer.parseInt(e.getKey())) insight = Math.max(insight, e.getValue().intValue());
        }
        parts.put("insight", insight);
        boolean warm = in.staminaLevel() >= c.num(P + "challenge.mods.warmup_min_stamina")
                && in.kmLastWindow() >= c.num(P + "challenge.mods.warmup_km");
        parts.put("warmup", warm ? 1 : 0);
        int assistMax = c.integer(P + "challenge.mods.assist_max") + (in.charmLevel() >= 30 ? 1 : 0);
        parts.put("assist", Math.min(assistMax, Math.max(0, in.assistsInRadius()) * c.integer(P + "challenge.mods.assist_per_member")));
        int sum = 0;
        for (int v : parts.values()) sum += v;
        return new Mods(parts, Math.min(sum, c.integer(P + "challenge.mods.cap")));
    }

    /** d20 + mod ≥ DC wins; natural 20 always wins, natural 1 always loses. */
    public static boolean challengerWins(CityMonopolyConfig c, int d20, int mod, int dc) {
        if (d20 < 1 || d20 > 20) throw new IllegalArgumentException("d20 out of range");
        if (d20 == 20 && c.flag(P + "challenge.nat20_wins")) return true;
        if (d20 == 1 && c.flag(P + "challenge.nat1_loses")) return false;
        return d20 + mod >= dc;
    }

    /** Exact win probability over a fair d20 (spec §2.7 table). */
    public static double winProbability(CityMonopolyConfig c, int dc, int mod) {
        int wins = 0;
        for (int r = 1; r <= 20; r++) if (challengerWins(c, r, mod, dc)) wins++;
        return wins / 20.0;
    }

    public record FeeSplit(long fee, long defenderComp, long burned, long refund) {}

    public static FeeSplit feeSplit(CityMonopolyConfig c, long fee, boolean challengerWon, boolean taskFailed) {
        if (taskFailed) {
            long refund = (long) Math.floor(fee * c.num(P + "challenge.task_fail_refund"));
            return new FeeSplit(fee, 0, fee - refund, refund);
        }
        double rate = challengerWon ? c.num(P + "challenge.on_win.defender_comp") : c.num(P + "challenge.on_loss.defender_comp");
        long comp = (long) Math.floor(fee * rate);
        return new FeeSplit(fee, comp, fee - comp, 0);
    }

    public static int levelAfterTakeover(CityMonopolyConfig c, int level) {
        return Math.max(0, level - c.integer(P + "challenge.on_win.level_drop"));
    }

    public static int rerollsPerDay(CityMonopolyConfig c, int luckLevel) {
        int n = 0;
        for (Map.Entry<String, Double> e : c.levels(P + "rerolls.luck_levels").entrySet()) {
            if (Math.min(luckLevel, 30) >= Integer.parseInt(e.getKey())) n = Math.max(n, e.getValue().intValue());
        }
        return n;
    }

    // ------------------------------------------------------------------ decay & upkeep

    public enum DecayState { OK, WEATHERED, DECAYING, RELEASED }

    public record Decay(DecayState state, int levelDrops, boolean reminderDay) {}

    public static int loreGraceDays(CityMonopolyConfig c, int loreLevel) {
        int g = 0;
        for (Map.Entry<String, Double> e : c.levels(P + "decay.lore_grace_days").entrySet()) {
            if (loreLevel >= Integer.parseInt(e.getKey())) g = Math.max(g, e.getValue().intValue());
        }
        return g;
    }

    /** Decay from whole days since the last owner visit; lore Lv20/40 add 1/2 grace days to every threshold. */
    public static Decay decay(CityMonopolyConfig c, long daysSinceVisit, int loreLevel) {
        int g = loreGraceDays(c, loreLevel);
        long weathered = c.integer(P + "decay.weathered_after_d") + g;
        long drop = c.integer(P + "decay.level_drop_at_d") + g;
        long release = c.integer(P + "decay.release_at_d") + g;
        boolean reminder = false;
        for (double d : c.list(P + "decay.reminders_d")) if (daysSinceVisit == Math.round(d) + g) reminder = true;
        if (daysSinceVisit >= release) return new Decay(DecayState.RELEASED, 0, reminder);
        if (daysSinceVisit >= drop) {
            int drops = 1 + (int) ((daysSinceVisit - drop) / c.integer(P + "decay.level_drop_every_d"));
            return new Decay(DecayState.DECAYING, drops, reminder);
        }
        if (daysSinceVisit >= weathered) return new Decay(DecayState.WEATHERED, 0, reminder);
        return new Decay(DecayState.OK, 0, reminder);
    }

    public static long upkeep(CityMonopolyConfig c, long invested, int loreLevel) {
        if (invested <= 0) return 0;
        double disc = 0;
        for (Map.Entry<String, Double> e : c.levels(P + "upkeep.lore_discount").entrySet()) {
            if (loreLevel >= Integer.parseInt(e.getKey())) disc = Math.max(disc, e.getValue());
        }
        long v = Math.round(invested * c.num(P + "upkeep.rate_of_invested_per_day") * (1 - disc));
        return Math.max(Math.round(c.num(P + "upkeep.min_per_property")), v);
    }

    // ------------------------------------------------------------------ chance & dice event

    public static String chanceTask(CityMonopolyConfig c, int d6) {
        List<String> table = c.strings(P + "chance.task_table_d6");
        return table.get(Math.max(1, Math.min(6, d6)) - 1);
    }

    // ------------------------------------------------------------------ seasons

    public enum Phase { OPENING, REGULAR, FINALE, FROZEN, BREAK }

    public record Season(String seasonId, ZonedDateTime startsAt, ZonedDateTime freezesAt, ZonedDateTime endsAt) {}

    public static ZoneId zone(CityMonopolyConfig c) { return ZoneId.of(c.str(P + "timezone")); }

    private static DayOfWeek dow(String token) {
        return switch (token.toUpperCase()) {
            case "MON" -> DayOfWeek.MONDAY; case "TUE" -> DayOfWeek.TUESDAY; case "WED" -> DayOfWeek.WEDNESDAY;
            case "THU" -> DayOfWeek.THURSDAY; case "FRI" -> DayOfWeek.FRIDAY; case "SAT" -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
    }

    /**
     * Seasons are 28-day blocks starting MON 10:00, anchored at {@code epochStart} (the first season's Monday).
     * They freeze on the 4th Sunday at 22:00 and the next season opens the following Monday 10:00 (12 h break).
     */
    public static Season seasonAt(CityMonopolyConfig c, ZonedDateTime epochStart, ZonedDateTime at) {
        int len = c.integer(P + "season.length_d");
        String[] start = c.str(P + "season.start").split(" ");
        LocalTime startTime = LocalTime.parse(start[1]);
        ZonedDateTime anchor = epochStart.withZoneSameInstant(zone(c))
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(dow(start[0]))).with(startTime);
        ZonedDateTime local = at.withZoneSameInstant(zone(c));
        long days = ChronoUnit.DAYS.between(anchor, local);
        long idx = Math.floorDiv(days, len);
        if (local.isBefore(anchor.plusDays(idx * len))) idx--;
        ZonedDateTime s = anchor.plusDays(idx * len);
        String[] freeze = c.str(P + "season.freeze").split(" ");
        ZonedDateTime lastDay = s.plusDays(len - 1).toLocalDate().atStartOfDay(zone(c));
        ZonedDateTime freezeAt = lastDay.with(java.time.temporal.TemporalAdjusters.previousOrSame(dow(freeze[0])))
                .with(LocalTime.parse(freeze[1]));
        return new Season(seasonId(s), s, freezeAt, s.plusDays(len));
    }

    public static String seasonId(ZonedDateTime start) {
        int year = start.get(IsoFields.WEEK_BASED_YEAR);
        int week = start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return "s" + year + "w" + (week < 10 ? "0" + week : String.valueOf(week));
    }

    /** 1-based day of the season. */
    public static int seasonDay(Season s, ZonedDateTime at) {
        return (int) Duration.between(s.startsAt(), at.withZoneSameInstant(s.startsAt().getZone())).toDays() + 1;
    }

    public static Phase phase(CityMonopolyConfig c, Season s, ZonedDateTime at) {
        if (!at.isBefore(s.freezesAt())) return at.isBefore(s.endsAt()) ? Phase.BREAK : Phase.FROZEN;
        int day = seasonDay(s, at);
        List<Double> opening = c.list(P + "season.phases.opening_d");
        List<Double> finale = c.list(P + "season.phases.finale_d");
        if (day >= opening.get(0) && day <= opening.get(1)) return Phase.OPENING;
        if (day >= finale.get(0) && day <= finale.get(1)) return Phase.FINALE;
        return Phase.REGULAR;
    }

    public static LocalDateTime boardDayStart(CityMonopolyConfig c, long millis) {
        return ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), zone(c)).toLocalDate().atStartOfDay();
    }

    /** Personal season score: confirmed rent + 20×first claims + 30×takeovers + 10×defenses + Σ holding-day weights. */
    public static double soloScore(CityMonopolyConfig c, long confirmedRent, int firstClaims, int takeovers, int defenses,
                                   Map<Integer, Integer> holdingDaysByTier) {
        double s = confirmedRent + c.num(P + "season.score.per_first_claim") * firstClaims
                + c.num(P + "season.score.per_takeover") * takeovers + c.num(P + "season.score.per_defense") * defenses;
        Map<String, Double> w = c.levels(P + "season.score.holding_day_weight");
        if (holdingDaysByTier != null) {
            for (Map.Entry<Integer, Integer> e : holdingDaysByTier.entrySet()) {
                s += w.getOrDefault("T" + normalizeTier(e.getKey()), 1.0) * e.getValue();
            }
        }
        return s;
    }

    public static double topNSum(List<Double> scores, int n) {
        List<Double> sorted = new ArrayList<>(scores);
        sorted.sort(Comparator.reverseOrder());
        double s = 0;
        for (int i = 0; i < Math.min(n, sorted.size()); i++) s += sorted.get(i);
        return s;
    }

    public static double teamScore(CityMonopolyConfig c, List<Double> memberScores, double diceEventScore) {
        return topNSum(memberScores, c.integer(P + "season.team_score_top_n")) + c.num(P + "dice_event.season_weight") * diceEventScore;
    }

    public static double clubScore(CityMonopolyConfig c, List<Double> memberScores, int districtsWithHoldings) {
        return topNSum(memberScores, c.integer(P + "season.club_score.top_n")) + c.num(P + "season.club_score.per_district") * districtsWithHoldings;
    }

    /** Leaderboard window: indices of the top N plus me ± around (0-based, ascending, de-duplicated). */
    public static List<Integer> leaderboardWindow(CityMonopolyConfig c, int size, int myIndex) {
        List<Integer> out = new ArrayList<>();
        int top = c.integer(P + "season.leaderboard_window.top");
        int around = c.integer(P + "season.leaderboard_window.around_me");
        for (int i = 0; i < Math.min(top, size); i++) out.add(i);
        if (myIndex >= 0) {
            for (int i = Math.max(0, myIndex - around); i <= Math.min(size - 1, myIndex + around); i++) if (!out.contains(i)) out.add(i);
        }
        Collections.sort(out);
        return out;
    }

    /** Solo season reward for 1-based rank among {@code total} participants (best matching tier wins; XP not stacked). */
    public static Map<String, Object> soloReward(CityMonopolyConfig c, int rank, int total, int claims) {
        com.alibaba.fastjson2.JSONArray tiers = com.alibaba.fastjson2.JSON.parseArray(c.str(P + "season.rewards.solo"));
        Map<String, Object> best = null;
        int bestXp = -1;
        for (int i = 0; i < tiers.size(); i++) {
            com.alibaba.fastjson2.JSONObject t = tiers.getJSONObject(i);
            boolean match;
            if (t.containsKey("rank")) match = rank == t.getIntValue("rank");
            else if (t.containsKey("rankMax")) match = rank <= t.getIntValue("rankMax");
            else if (t.containsKey("pctMax")) match = total > 0 && (double) rank / total <= t.getDoubleValue("pctMax");
            else match = claims >= t.getIntValue("participantMinClaims", 1);
            if (match && t.getIntValue("xp") > bestXp) { best = new LinkedHashMap<>(t); bestXp = t.getIntValue("xp"); }
        }
        return best;
    }
}
