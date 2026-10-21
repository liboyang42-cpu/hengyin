package com.chengyinhub.business.citygame.analytics.fairness.source;

import com.chengyinhub.business.citygame.analytics.fairness.CityFairnessIngestionService;
import com.chengyinhub.business.citygame.analytics.fairness.CityFairnessReader;
import com.chengyinhub.business.citygame.analytics.fairness.FairnessFactContract;
import com.chengyinhub.business.citygame.analytics.fairness.source.mapper.FairnessOnsiteSourceMapper;
import com.chengyinhub.business.citygame.analytics.fairness.source.mapper.FairnessOutingMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Reads one onsite result and, only with a prior outing bind plus an approved counting row,
 * calls the existing ingestion service. rulesVersion is rules_release_id.
 */
@Service
public class CityFairnessOnsiteRelay {
    public static final String SOURCE = "CITY_ONSITE_TASK_RESULT_V1";
    public static final List<String> COVERAGE_GAPS = List.of(
            "outing_id", "novice-label", "headcount", "join-time", "revenue",
            "gps-false-rejections", "safety-tickets", "verification-latency");

    private static final DateTimeFormatter WALL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FairnessOnsiteSourceMapper facts;
    private final FairnessOutingMapper outings;
    public final CityFairnessIngestionService ingestion;
    private final DataSource dataSource;
    Clock clock;

    @Autowired
    public CityFairnessOnsiteRelay(FairnessOnsiteSourceMapper facts, FairnessOutingMapper outings,
                                   CityFairnessIngestionService ingestion, DataSource dataSource) {
        this.facts = facts;
        this.outings = outings;
        this.ingestion = ingestion;
        this.dataSource = dataSource;
        this.clock = Clock.systemUTC();
    }

    /** App reopen is not an effective outing and does not bind one. */
    public RelayResult reopen(long memberId) {
        return RelayResult.of("NOT_AN_OUTING", FairnessFactContract.APP_REOPEN, null, null, null, null, null);
    }

    @Transactional
    public RelayResult bind(long memberId, String instanceId) {
        if (memberId <= 0 || blank(instanceId)) return RelayResult.of("NOT_ACTIVE", "instance", null, null, null, null, null);
        FairnessOuting existing = outings.selectByInstance(instanceId);
        if (existing != null) return bound(existing, memberId);
        try {
            if (outings.insertActive(memberId, instanceId) != 1) {
                return RelayResult.of("NOT_ACTIVE", "instance", null, null, null, null, null);
            }
        } catch (DuplicateKeyException duplicate) {
            existing = outings.selectByInstance(instanceId);
            if (existing == null) return RelayResult.of("NOT_ACTIVE", "instance", null, null, null, null, null);
            return bound(existing, memberId);
        }
        existing = outings.selectByInstance(instanceId);
        if (existing == null) return RelayResult.of("NOT_ACTIVE", "instance", null, null, null, null, null);
        return bound(existing, memberId);
    }

    @Transactional
    public RelayResult forward(long memberId, String resultId) {
        if (memberId <= 0 || blank(resultId)) return RelayResult.of("ABSENT", null, null, null, null, null, null);
        OnsiteTaskFact fact = facts.selectFact(resultId);
        if (fact == null || blank(fact.resultId)) return RelayResult.of("ABSENT", null, null, null, null, null, null);
        if (!Long.valueOf(memberId).equals(fact.memberId) || !Long.valueOf(memberId).equals(fact.acceptedBy)
                || !Long.valueOf(memberId).equals(fact.requestMemberId)) {
            return RelayResult.of("CROSS_ACCOUNT", "member", fact.resultId, null, null, null, null);
        }
        if ("UNKNOWN".equals(fact.requestState)) return RelayResult.of("UNKNOWN", "state", fact.resultId, null, null, null, null);
        if ("EXHAUSTED".equals(fact.outcome) || "EXHAUSTED".equals(fact.instanceStatus)) {
            return RelayResult.of("EXHAUSTED", "outcome", fact.resultId, null, null, null, null);
        }
        if (!"PASSED".equals(fact.outcome) || !"PASSED".equals(fact.instanceStatus)
                || !"COMPLETE".equals(fact.requestState) || fact.httpStatus == null || fact.httpStatus != 200
                || blank(fact.boardId) || blank(fact.regionId) || blank(fact.seasonId) || blank(fact.rulesReleaseId)
                || fact.releaseId == null || blank(fact.contentHash)) {
            return RelayResult.of("NOT_COMMITTED", "passed", fact.resultId, null, null, null, null);
        }
        FairnessOuting outing = outings.selectByInstance(fact.instanceId);
        if (outing == null || outing.id == null) return RelayResult.of("SOURCE_UNBOUND", "outing", fact.resultId, null, null, null, null);
        if (!Long.valueOf(memberId).equals(outing.memberId)) {
            return RelayResult.of("CROSS_ACCOUNT", "outing", fact.resultId, Long.toString(outing.id), null, null, null);
        }
        if (!sameScope(fact, outing)) {
            return RelayResult.of("CROSS_SCOPE", "scope", fact.resultId, Long.toString(outing.id), null, null, null);
        }
        Integer counting = outings.selectCounting(SOURCE, fact.boardId, fact.regionId, fact.seasonId, fact.rulesReleaseId);
        if (counting == null || counting != 1) {
            return RelayResult.of("POLICY_OFF", "countsAsEffectiveOuting", fact.resultId, Long.toString(outing.id), null, null, null);
        }
        ZoneOffset zone = sessionOffset();
        if (zone == null) {
            return RelayResult.of("TIMEZONE_UNVERIFIED", "timeZone", fact.resultId, Long.toString(outing.id), null, null, null);
        }
        Long occurredAt = seconds(fact.createdAt, zone);
        if (occurredAt == null) {
            return RelayResult.of("NOT_COMMITTED", "createdAt", fact.resultId, Long.toString(outing.id), null, null, null);
        }
        long receivedAt = Math.floorDiv(clock.millis(), 1000L) * 1000L;
        Long verifiedAt = seconds(fact.evidenceCheckedAt, zone);
        CityFairnessReader.EventRow event = new CityFairnessReader.EventRow();
        event.source = SOURCE;
        event.eventId = fact.resultId;
        event.outingId = Long.toString(outing.id);
        event.kind = FairnessFactContract.EFFECTIVE_TASK;
        event.actorId = memberId;
        event.occurredAt = occurredAt;
        event.knownAt = occurredAt;
        event.boardId = fact.boardId;
        event.regionId = fact.regionId;
        event.seasonId = fact.seasonId;
        event.rulesVersion = fact.rulesReleaseId;
        CityFairnessIngestionService.IngestResult stored = ingestion.ingest(event);
        if ("STORED".equals(stored.status) || "DEDUPED".equals(stored.status)) {
            try {
                outings.insertReceipt(SOURCE, fact.resultId, receivedAt);
            } catch (DuplicateKeyException ignored) {
                // replay keeps the first receipt
            }
        }
        return RelayResult.of(stored.status, stored.reason, fact.resultId, event.outingId, occurredAt, verifiedAt, receivedAt);
    }

    private static RelayResult bound(FairnessOuting outing, long memberId) {
        if (!Long.valueOf(memberId).equals(outing.memberId)) {
            return RelayResult.of("CROSS_ACCOUNT", "outing", null, Long.toString(outing.id), null, null, null);
        }
        return RelayResult.of("BOUND", null, null, Long.toString(outing.id), null, null, null);
    }

    private static boolean sameScope(OnsiteTaskFact fact, FairnessOuting outing) {
        return fact.boardId.equals(outing.boardId) && fact.regionId.equals(outing.regionId)
                && fact.seasonId.equals(outing.seasonId) && fact.rulesReleaseId.equals(outing.rulesReleaseId)
                && fact.releaseId.equals(outing.releaseId) && fact.contentHash.equals(outing.contentHash);
    }

    private ZoneOffset sessionOffset() {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (Statement statement = connection.createStatement()) {
            String raw = scalar(statement, "select @@session.time_zone");
            if (raw == null) {
                raw = scalar(statement, "select setting_value from information_schema.settings where setting_name = 'TIME ZONE'");
            }
            return offset(raw);
        } catch (SQLException ex) {
            return null;
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private static String scalar(Statement statement, String sql) {
        try (ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        } catch (SQLException ex) {
            return null;
        }
    }

    static ZoneOffset offset(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty() || "SYSTEM".equalsIgnoreCase(value)) return null;
        if ("UTC".equalsIgnoreCase(value) || "Z".equals(value) || "+00:00".equals(value) || "-00:00".equals(value)) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneOffset.of(value);
        } catch (DateTimeException ex) {
            return null;
        }
    }

    private static Long seconds(String text, ZoneOffset zone) {
        if (text == null) return null;
        String wall = text.trim();
        if (wall.length() < 19) return null;
        try {
            return LocalDateTime.parse(wall.substring(0, 19), WALL).toEpochSecond(zone) * 1000L;
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public static final class RelayResult {
        public final String status;
        public final String reason;
        public final String eventId;
        public final String outingId;
        public final Long occurredAt;
        public final Long verifiedAt;
        public final Long receivedAt;
        public final List<String> coverageGaps;

        private RelayResult(String status, String reason, String eventId, String outingId,
                            Long occurredAt, Long verifiedAt, Long receivedAt) {
            this.status = status;
            this.reason = reason;
            this.eventId = eventId;
            this.outingId = outingId;
            this.occurredAt = occurredAt;
            this.verifiedAt = verifiedAt;
            this.receivedAt = receivedAt;
            this.coverageGaps = COVERAGE_GAPS;
        }

        static RelayResult of(String status, String reason, String eventId, String outingId,
                              Long occurredAt, Long verifiedAt, Long receivedAt) {
            return new RelayResult(status, reason, eventId, outingId, occurredAt, verifiedAt, receivedAt);
        }
    }
}
