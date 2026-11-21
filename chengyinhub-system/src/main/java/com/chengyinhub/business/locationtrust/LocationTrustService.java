package com.chengyinhub.business.locationtrust;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.chengyinhub.business.mapper.LocationTrustMapper;
import com.chengyinhub.system.service.ISysConfigService;

/**
 * SEC-04..SEC-10: scores every location-bearing write and keeps the review trail.
 *
 * <p>Fail-open by design: if the config or the trail table is unavailable (migration not applied yet, DB
 * hiccup), the write is scored ACCEPT and behaves exactly as before, with an error log. Legacy checks in each
 * endpoint (geofences, the 35 m/s presence rule, the reveal budget) still run either way.
 *
 * <p>The trail row is written in its own transaction so a rejected write (or a caller that later rolls back)
 * still leaves a record for review.
 */
@Service
public class LocationTrustService {

    private static final Logger log = LoggerFactory.getLogger(LocationTrustService.class);
    /** Structured review log, one line per non-clean decision; mirrors the table row. */
    private static final Logger REVIEW_LOG = LoggerFactory.getLogger("com.chengyinhub.locationtrust.review");

    static final int REVEAL_TRACK_POINTS = 200;

    @Autowired(required = false)
    private ISysConfigService configService;
    @Autowired(required = false)
    private LocationTrustMapper mapper;
    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    public LocationTrustService() {
    }

    /** For tests. */
    public LocationTrustService(ISysConfigService configService, LocationTrustMapper mapper,
                                PlatformTransactionManager transactionManager) {
        this.configService = configService;
        this.mapper = mapper;
        this.transactionManager = transactionManager;
    }

    /** Scored verdict, what the write actually does (SHADOW mode never blocks) and the trail row id. */
    public record Assessment(LocationTrustPolicy.Verdict verdict, LocationTrustDecision applied, Long fixId) {
        public static Assessment legacy() {
            return new Assessment(LocationTrustPolicy.Verdict.accept(), LocationTrustDecision.ACCEPT, null);
        }

        public boolean credited() {
            return applied == LocationTrustDecision.ACCEPT;
        }

        public boolean rejected() {
            return applied == LocationTrustDecision.REJECT;
        }

        public boolean withheld() {
            return applied == LocationTrustDecision.ACCEPT_NO_REWARD;
        }

        public List<String> reasons() {
            return verdict.reasons();
        }
    }

    public LocationTrustConfig config() {
        if (configService == null) return LocationTrustConfig.defaults();
        try {
            return LocationTrustConfig.read(configService::selectConfigByKey);
        } catch (RuntimeException e) {
            log.error("location trust config unreadable, using defaults reason={}", e.getClass().getSimpleName());
            return LocationTrustConfig.defaults();
        }
    }

    public Assessment assess(Long memberId, Long sessionId, LocationTrustKind kind, String refId, LocationFix fix) {
        return assess(memberId, sessionId, kind, refId, fix, null);
    }

    public Assessment assess(Long memberId, Long sessionId, LocationTrustKind kind, String refId, LocationFix fix,
                             String detail) {
        LocationTrustConfig cfg = config();
        if (!cfg.scoring() || memberId == null || memberId <= 0L || fix == null) return Assessment.legacy();
        long now = System.currentTimeMillis();
        LocationTrustPolicy.Baseline baseline = null;
        LocationTrustPolicy.Baseline windowStart = null;
        int identical = 0;
        if (mapper != null && fix.validCoordinates()) {
            try {
                Date since = new Date(now - cfg.speedBaselineWindowSeconds() * 1000L);
                baseline = baseline(mapper.selectLastCleanFix(memberId, since));
                windowStart = baseline == null ? null
                        : baseline(mapper.selectFirstCleanFix(memberId, new Date(now - cfg.windowMinutes() * 60_000L)));
                identical = mapper.countIdenticalSince(memberId, scale6(fix.lat()), scale6(fix.lng()),
                        accuracy(fix), new Date(now - 3_600_000L));
            } catch (RuntimeException e) {
                log.error("location trust history unreadable, scoring without it member={} kind={} reason={}",
                        memberId, kind, e.getClass().getSimpleName());
            }
        }
        LocationTrustPolicy.Verdict verdict = LocationTrustPolicy.evaluate(kind, fix, baseline, windowStart, identical, now, cfg);
        LocationTrustDecision applied = cfg.enforcing() ? verdict.decision() : LocationTrustDecision.ACCEPT;
        Long fixId = fix.validCoordinates() ? record(memberId, sessionId, kind, refId, fix, verdict, applied, now, detail) : null;
        if (verdict.flagged() || verdict.decision() != LocationTrustDecision.ACCEPT) {
            REVIEW_LOG.warn("event=LOCATION_TRUST kind={} member={} session={} ref={} decision={} applied={} reasons={} speedMps={} fixId={}",
                    kind, memberId, sessionId, refId, verdict.decision(), applied, verdict.reasonText(),
                    verdict.speedMps() == null ? "-" : String.format(java.util.Locale.ROOT, "%.1f", verdict.speedMps()),
                    fixId);
        }
        return new Assessment(verdict, applied, fixId);
    }

    /** Result of {@link #creditableTiles}: tiles to credit, and how many were dropped by which rule. */
    public record RevealFilter(List<String> credited, int droppedFarFromTrack, int droppedOverBudget,
                               int droppedUntrusted) {
        public int dropped() {
            return droppedFarFromTrack + droppedOverBudget + droppedUntrusted;
        }
    }

    /**
     * SEC-07: which of the client's tiles may be credited.
     * <ol>
     *   <li>The fix sent with the batch (new app builds) is scored; a withheld/rejected fix credits no tiles.</li>
     *   <li>When the session has a fix track (the batch fix plus earlier accepted batch fixes), only tiles within
     *       {@code revealTrackRadiusMeters} of that track count. Old clients send no fixes: no track, step skipped.</li>
     *   <li>Session budget at the configured reveal speed, then a rolling per-window cap for all sessions.</li>
     * </ol>
     * Every drop writes to the review trail. Dropped tiles are not an error: the client's upload queue must drain.
     */
    public RevealFilter creditableTiles(Long memberId, Long sessionId, Date startedAt, List<String> tiles,
                                        int sessionTilesSoFar, int legacyBudget, LocationFix fix) {
        LocationTrustConfig cfg = config();
        if (tiles == null || tiles.isEmpty()) return new RevealFilter(List.of(), 0, 0, 0);
        if (!cfg.scoring()) {
            int remaining = Math.max(0, legacyBudget - sessionTilesSoFar);
            List<String> kept = tiles.size() > remaining ? List.copyOf(tiles.subList(0, remaining)) : List.copyOf(tiles);
            return new RevealFilter(kept, 0, tiles.size() - kept.size(), 0);
        }
        long now = System.currentTimeMillis();
        Assessment fixAssessment = null;
        if (fix != null) {
            fixAssessment = assess(memberId, sessionId, LocationTrustKind.REVEAL, null, fix, "tiles=" + tiles.size());
            if (!fixAssessment.credited()) {
                REVIEW_LOG.warn("event=LOCATION_TRUST_REVEAL_DROPPED member={} session={} tiles={} reasons={} fixId={}",
                        memberId, sessionId, tiles.size(), String.join(",", fixAssessment.reasons()), fixAssessment.fixId());
                // SHADOW mode keeps crediting even if the fix scored badly
                if (cfg.enforcing()) return new RevealFilter(List.of(), 0, 0, tiles.size());
            }
        }
        List<double[]> track = new ArrayList<>();
        if (fix != null && fix.validCoordinates()) {
            track.add(new double[] {fix.lat(), fix.lng()});
            if (mapper != null) {
                try {
                    for (Map<String, Object> p : mapper.selectSessionRevealTrack(memberId, sessionId, REVEAL_TRACK_POINTS)) {
                        Double lat = number(p.get("lat")), lng = number(p.get("lng"));
                        if (lat != null && lng != null) track.add(new double[] {lat, lng});
                    }
                } catch (RuntimeException e) {
                    log.error("location trust reveal track unreadable member={} session={} reason={}",
                            memberId, sessionId, e.getClass().getSimpleName());
                }
            }
        }
        List<String> near = LocationTrustPolicy.tilesNearTrack(tiles, track, cfg);
        int far = tiles.size() - near.size();
        int sessionBudget = LocationTrustPolicy.revealSessionBudget(startedAt == null ? 0L : startedAt.getTime(), now,
                200, cfg);
        int remaining = Math.min(sessionBudget, legacyBudget) - sessionTilesSoFar;
        if (mapper != null) {
            try {
                int recent = mapper.countTilesRevealedSince(memberId, new Date(now - cfg.revealWindowMinutes() * 60_000L));
                remaining = Math.min(remaining, LocationTrustPolicy.revealWindowBudget(cfg) - recent);
            } catch (RuntimeException e) {
                log.error("location trust reveal window unreadable member={} reason={}", memberId, e.getClass().getSimpleName());
            }
        }
        remaining = Math.max(0, remaining);
        int over = Math.max(0, near.size() - remaining);
        if (far > 0 || over > 0) {
            String detail = "tiles=" + tiles.size() + " farFromTrack=" + far + " overBudget=" + over;
            REVIEW_LOG.warn("event=LOCATION_TRUST_REVEAL_TRIMMED member={} session={} {} enforced={} fixId={}",
                    memberId, sessionId, detail, cfg.enforcing(), fixAssessment == null ? null : fixAssessment.fixId());
            if (fixAssessment != null && fixAssessment.fixId() != null && mapper != null) {
                try {
                    mapper.updateFixDetail(fixAssessment.fixId(), detail);
                } catch (RuntimeException e) {
                    log.error("location trust reveal detail not saved fixId={}", fixAssessment.fixId());
                }
            }
        }
        if (!cfg.enforcing()) {
            // SHADOW: scored and logged above, but credit exactly what the legacy budget allows
            int legacyRemaining = Math.max(0, legacyBudget - sessionTilesSoFar);
            List<String> kept = tiles.size() > legacyRemaining ? List.copyOf(tiles.subList(0, legacyRemaining)) : List.copyOf(tiles);
            return new RevealFilter(kept, 0, tiles.size() - kept.size(), 0);
        }
        List<String> kept = near.size() > remaining ? List.copyOf(near.subList(0, remaining)) : List.copyOf(near);
        return new RevealFilter(kept, far, over, 0);
    }

    /**
     * R4 guest-walk: one trail row for a track that was scored as a whole on the device clock (a walk made before
     * sign-in and uploaded afterwards). {@code lastFix} is the row's point; the reasons and decision come from the
     * caller's track scoring. Returns the row id, or null when nothing was written.
     */
    public Long recordTrack(Long memberId, Long sessionId, LocationTrustKind kind, String refId, LocationFix lastFix,
                            List<String> reasons, LocationTrustDecision decision, String detail) {
        LocationTrustConfig cfg = config();
        if (!cfg.scoring() || memberId == null || memberId <= 0L || lastFix == null || !lastFix.validCoordinates()) return null;
        LocationTrustPolicy.Verdict verdict = new LocationTrustPolicy.Verdict(decision, reasons == null ? List.of() : reasons, null);
        LocationTrustDecision applied = cfg.enforcing() ? decision : LocationTrustDecision.ACCEPT;
        Long fixId = record(memberId, sessionId, kind, refId, lastFix, verdict, applied, System.currentTimeMillis(), detail);
        if (verdict.flagged() || decision != LocationTrustDecision.ACCEPT) {
            REVIEW_LOG.warn("event=LOCATION_TRUST kind={} member={} session={} ref={} decision={} applied={} reasons={} detail={} fixId={}",
                    kind, memberId, sessionId, refId, decision, applied, verdict.reasonText(), detail, fixId);
        }
        return fixId;
    }

    /** Clean fixes since {@code since}, newest first, as {lat, lng, receivedAtMs}. Empty on any error. */
    public List<double[]> cleanFixesSince(Long memberId, Date since, int limit) {
        List<double[]> out = new ArrayList<>();
        if (mapper == null) return out;
        try {
            for (Map<String, Object> p : mapper.selectCleanFixesSince(memberId, since, limit)) {
                Double lat = number(p.get("lat")), lng = number(p.get("lng"));
                Long at = millis(p.get("receivedAt"));
                if (lat != null && lng != null && at != null) out.add(new double[] {lat, lng, at});
            }
        } catch (RuntimeException e) {
            log.error("location trust fix history unreadable member={} reason={}", memberId, e.getClass().getSimpleName());
        }
        return out;
    }

    /** Retention: delete trail rows older than the cutoff, at most {@code limit}. */
    public int deleteFixesBefore(Date cutoff, int limit) {
        return mapper == null ? 0 : mapper.deleteFixesBefore(cutoff, limit);
    }

    private Long record(Long memberId, Long sessionId, LocationTrustKind kind, String refId, LocationFix fix,
                        LocationTrustPolicy.Verdict verdict, LocationTrustDecision applied, long now, String detail) {
        if (mapper == null) return null;
        LocationTrustFixRow row = new LocationTrustFixRow();
        row.setMemberId(memberId);
        row.setSessionId(sessionId != null && sessionId > 0L ? sessionId : null);
        row.setKind(kind.name());
        row.setRefId(refId == null ? null : refId.length() > 64 ? refId.substring(0, 64) : refId);
        row.setLat(scale6(fix.lat()));
        row.setLng(scale6(fix.lng()));
        row.setAccuracyM(accuracy(fix));
        row.setClientTs(fix.clientTimestampMs() == null ? null : new Date(fix.clientTimestampMs()));
        row.setReceivedAt(new Date(now));
        row.setSimulated(flag(fix.simulated()));
        row.setAccessory(flag(fix.accessory()));
        row.setSpeedMps(verdict.speedMps() == null || !Double.isFinite(verdict.speedMps()) ? null
                : BigDecimal.valueOf(Math.min(9_999_999d, verdict.speedMps())).setScale(2, RoundingMode.HALF_UP));
        row.setDecision(verdict.decision().name());
        row.setAppliedDecision(applied.name());
        String reasons = verdict.reasonText();
        row.setReasons(reasons.isEmpty() ? null : reasons);
        row.setDetail(detail == null ? null : detail.length() > 255 ? detail.substring(0, 255) : detail);
        try {
            if (transactionManager == null) {
                mapper.insertFix(row);
            } else {
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.executeWithoutResult(status -> mapper.insertFix(row));
            }
            return row.getId();
        } catch (RuntimeException e) {
            log.error("location trust trail not written member={} kind={} decision={} reason={}",
                    memberId, kind, verdict.decision(), e.getClass().getSimpleName());
            return null;
        }
    }

    private static LocationTrustPolicy.Baseline baseline(Map<String, Object> row) {
        if (row == null) return null;
        Double lat = number(row.get("lat")), lng = number(row.get("lng"));
        Long at = millis(row.get("receivedAt"));
        if (lat == null || lng == null || at == null) return null;
        return new LocationTrustPolicy.Baseline(lat, lng, at, millis(row.get("clientTs")), number(row.get("accuracyM")));
    }

    static BigDecimal scale6(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal accuracy(LocationFix fix) {
        Double a = fix.accuracyMeters();
        if (a == null || !Double.isFinite(a)) return null;
        return BigDecimal.valueOf(Math.max(-1d, Math.min(99_999_999d, a))).setScale(1, RoundingMode.HALF_UP);
    }

    private static Integer flag(Boolean b) {
        return b == null ? null : b ? 1 : 0;
    }

    static Double number(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return null;
        try {
            return Double.valueOf(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Long millis(Object raw) {
        if (raw instanceof Date d) return d.getTime();
        if (raw instanceof java.time.LocalDateTime ldt) {
            return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        return null;
    }
}
