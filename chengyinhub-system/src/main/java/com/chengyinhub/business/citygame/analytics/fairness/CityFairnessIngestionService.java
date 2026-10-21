package com.chengyinhub.business.citygame.analytics.fairness;

import com.chengyinhub.business.citygame.analytics.fairness.mapper.CityFairnessMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.chengyinhub.business.citygame.analytics.fairness.FairnessFactContract.*;

/**
 * Persists a server-attested CITY fact. Identity is (source, event_id).
 * Reopen, reconnect, and registration repeat are refused and are not stored as outings.
 */
@Service
public class CityFairnessIngestionService {
    private final CityFairnessMapper mapper;

    public CityFairnessIngestionService(CityFairnessMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public IngestResult ingest(CityFairnessReader.EventRow fact) {
        String rejection = reject(fact);
        if (rejection != null) return IngestResult.rejected(rejection);
        CityFairnessReader.EventRow existing = mapper.selectEvent(fact.source, fact.eventId);
        if (existing != null) return sameOrConflict(existing, fact);
        try {
            mapper.insertEvent(fact);
        } catch (DuplicateKeyException duplicate) {
            existing = mapper.selectEvent(fact.source, fact.eventId);
            if (existing == null) return IngestResult.rejected("city_fairness_event");
            return sameOrConflict(existing, fact);
        }
        return IngestResult.stored();
    }

    private static IngestResult sameOrConflict(CityFairnessReader.EventRow existing, CityFairnessReader.EventRow fact) {
        return CityFairnessReader.same(existing, fact) ? IngestResult.deduped() : IngestResult.conflict(fact.eventId);
    }

    private static String reject(CityFairnessReader.EventRow fact) {
        if (fact == null || fact.source == null || fact.source.isBlank()) return "source";
        if (fact.eventId == null || fact.eventId.isBlank()) return "eventId";
        if (fact.source.indexOf('\t') >= 0 || fact.source.indexOf('\n') >= 0) return "source";
        if (fact.eventId.indexOf('\t') >= 0 || fact.eventId.indexOf('\n') >= 0) return "eventId";
        if (APP_REOPEN.equals(fact.kind) || RECONNECT.equals(fact.kind) || REGISTRATION_REPEAT.equals(fact.kind)) {
            return "not-an-outing";
        }
        if (!known(fact.kind)) return "kind";
        if (fact.actorId == null || fact.actorId <= 0) return "actorId";
        if (fact.outingId == null || fact.outingId.isBlank()) return "outingId";
        if (fact.outingId.indexOf('\t') >= 0 || fact.outingId.indexOf('\n') >= 0) return "outingId";
        if (fact.occurredAt == null) return "occurredAt";
        if (GAIN.equals(fact.kind) && (fact.gainAmount == null || fact.gainAmount < 0)) return "gainAmount";
        if (MERCHANT_VERIFICATION.equals(fact.kind) && (fact.verificationMillis == null || fact.verificationMillis < 0)) {
            return "verificationMillis";
        }
        return null;
    }

    private static boolean known(String kind) {
        return EFFECTIVE_TASK.equals(kind) || EFFECTIVE_SERVICE.equals(kind) || GOAL_COMPLETED.equals(kind)
                || GAIN.equals(kind) || ZERO_POINT_BLOCK.equals(kind) || CHALLENGE_COMPENSATION_EXIT.equals(kind)
                || GPS_FALSE_REJECTION.equals(kind) || SAFETY_TICKET.equals(kind) || MERCHANT_VERIFICATION.equals(kind);
    }

    public static final class IngestResult {
        public final String status;
        public final String reason;

        private IngestResult(String status, String reason) {
            this.status = status;
            this.reason = reason;
        }

        static IngestResult stored() { return new IngestResult("STORED", null); }
        static IngestResult deduped() { return new IngestResult("DEDUPED", null); }
        static IngestResult conflict(String eventId) { return new IngestResult("CONFLICT", "event-conflict:" + eventId); }
        static IngestResult rejected(String reason) { return new IngestResult("REJECTED", reason); }
    }
}
