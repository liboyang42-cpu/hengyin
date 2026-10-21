package com.chengyinhub.business.citygame.analytics.fairness.source;

import com.chengyinhub.business.citygame.analytics.fairness.CityFairnessIngestionService;
import com.chengyinhub.business.citygame.analytics.fairness.CityFairnessReader;
import com.chengyinhub.business.citygame.analytics.fairness.FairnessFactContract;
import com.chengyinhub.business.citygame.analytics.fairness.source.mapper.FairnessCompletionSourceMapper;
import com.chengyinhub.business.domain.GamePlayerNodeOutcome;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Reads one committed node outcome and, only when that row itself carries city scope,
 * calls the existing ingestion service. No new transaction.
 */
@Service
public class CityFairnessSourceRelay {
    public static final String SOURCE = "game_player_node_outcome";
    public static final List<String> COVERAGE_GAPS = List.of(
            "gps-false-rejections", "safety-tickets", "city-board-scope-join");

    private static final List<String> SERVER_SOURCES = List.of(
            "EVIDENCE_ONLY", "AUTO_HASH", "MERCHANT_APPROVAL", "FALLBACK_PLAN");

    private final FairnessCompletionSourceMapper outcomes;
    public final CityFairnessIngestionService ingestion;

    public CityFairnessSourceRelay(FairnessCompletionSourceMapper outcomes, CityFairnessIngestionService ingestion) {
        this.outcomes = outcomes;
        this.ingestion = ingestion;
    }

    @Transactional
    public ForwardResult forward(Long outcomeId) {
        if (outcomeId == null || outcomeId <= 0) return ForwardResult.absent();
        FairnessCompletionRow row = outcomes.selectById(outcomeId);
        if (row == null || row.id == null) return ForwardResult.absent();
        String eventId = Long.toString(row.id);
        String outingId = row.gameSessionId == null ? null : "game-session:" + row.gameSessionId;
        if (!committed(row.status)) return ForwardResult.of("NOT_COMMITTED", "status", eventId, outingId);
        if (!SERVER_SOURCES.contains(row.completionSource) || row.completedAt == null
                || row.memberId == null || row.memberId <= 0 || outingId == null) {
            return ForwardResult.of("NOT_AUTHORITATIVE", "completion-source", eventId, outingId);
        }
        if (blank(row.boardId) || blank(row.regionId) || blank(row.seasonId) || blank(row.rulesVersion)) {
            return ForwardResult.of("SCOPE_ABSENT", "city-board-scope-join", eventId, outingId);
        }
        CityFairnessReader.EventRow fact = new CityFairnessReader.EventRow();
        fact.source = SOURCE;
        fact.eventId = eventId;
        fact.outingId = outingId;
        fact.kind = FairnessFactContract.EFFECTIVE_TASK;
        fact.actorId = row.memberId;
        fact.occurredAt = row.completedAt.getTime();
        fact.boardId = row.boardId;
        fact.regionId = row.regionId;
        fact.seasonId = row.seasonId;
        fact.rulesVersion = row.rulesVersion;
        CityFairnessIngestionService.IngestResult stored = ingestion.ingest(fact);
        return ForwardResult.of(stored.status, stored.reason, eventId, outingId);
    }

    private static boolean committed(String status) {
        return GamePlayerNodeOutcome.COMPLETED.equals(status)
                || GamePlayerNodeOutcome.FALLBACK_COMPLETED.equals(status);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public static final class ForwardResult {
        public final String status;
        public final String reason;
        public final String eventId;
        public final String outingId;
        public final List<String> coverageGaps;

        private ForwardResult(String status, String reason, String eventId, String outingId) {
            this.status = status;
            this.reason = reason;
            this.eventId = eventId;
            this.outingId = outingId;
            this.coverageGaps = COVERAGE_GAPS;
        }

        static ForwardResult absent() {
            return new ForwardResult("ABSENT", null, null, null);
        }

        static ForwardResult of(String status, String reason, String eventId, String outingId) {
            return new ForwardResult(status, reason, eventId, outingId);
        }
    }
}
