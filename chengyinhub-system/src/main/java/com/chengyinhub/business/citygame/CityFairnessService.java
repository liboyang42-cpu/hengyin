package com.chengyinhub.business.citygame;

import com.chengyinhub.business.mapper.CityGameMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import static com.chengyinhub.business.citygame.CityGameSupport.millis;
import static com.chengyinhub.business.citygame.CityGameSupport.requireMember;

/**
 * CITY fairness cohort (formula city-fairness-cohort-v1). An outing is a member's first board action
 * on a calendar day. A member is mature once their first outing is older than the board horizon; the
 * ratio counts mature members whose second outing came within the board delta of the first. Only the
 * caller's own outing classes are returned, so the app may show the ratio without exposing others.
 */
@Service
public class CityFairnessService {
    public static final String FORMULA = "city-fairness-cohort-v1";
    private static final int MAX_OUTINGS = 20_000;

    private final CityGameMapper mapper;
    private final LongSupplier clock;

    public CityFairnessService(CityGameMapper mapper) { this(mapper, System::currentTimeMillis); }

    CityFairnessService(CityGameMapper mapper, LongSupplier clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Cohort facts for one board at one instant. */
    static final class Cohort {
        int denominator;
        int matureCount;
        final List<Map<String, Object>> own = new ArrayList<>();
        Long ownFirstT0;
        String ownMaturity = "PENDING";
        String ownReason = "first-outing";
        String ownSecondOutingId;
    }

    static Cohort cohort(List<Map<String, Object>> outings, long memberId, long asOf, long horizonMillis, long deltaMillis) {
        Cohort c = new Cohort();
        int i = 0;
        while (i < outings.size()) {
            long member = ((Number) outings.get(i).get("memberId")).longValue();
            int j = i;
            while (j < outings.size() && ((Number) outings.get(j).get("memberId")).longValue() == member) j++;
            Map<String, Object> first = outings.get(i);
            Map<String, Object> second = j - i > 1 ? outings.get(i + 1) : null;
            long t0 = millis(first.get("t0"));
            boolean mature = t0 + horizonMillis <= asOf;
            boolean returned = second != null && millis(second.get("t0")) - t0 <= deltaMillis;
            if (mature) {
                c.denominator++;
                if (returned) c.matureCount++;
            }
            if (member == memberId) {
                c.ownFirstT0 = t0;
                c.ownMaturity = mature ? "MATURE" : "PENDING";
                c.ownReason = returned ? "inside-delta" : mature ? "after-horizon" : "first-outing";
                c.ownSecondOutingId = second == null ? null : (String) second.get("outingId");
                for (int k = i; k < j; k++) c.own.add(outings.get(k));
            }
            i = j;
        }
        return c;
    }

    public Map<String, Object> report(Long memberId, Map<String, String> q) {
        Context ctx = context(memberId, q);
        Map<String, Object> data = ctx.payload();
        if (ctx.notComputable) return data;
        Cohort c = ctx.cohort;
        if (c.denominator == 0) {
            data.put("maturity", "NO_MATURE_SAMPLE");
            data.put("matureCount", null);
            data.put("denominator", null);
            data.put("outingClasses", null);
            return data;
        }
        data.put("maturity", "COUNTED");
        data.put("matureCount", c.matureCount);
        data.put("denominator", c.denominator);
        List<Map<String, Object>> classes = new ArrayList<>();
        if (!c.own.isEmpty()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("outingId", c.own.get(0).get("outingId"));
            row.put("actorId", memberId);
            row.put("maturity", c.ownMaturity);
            row.put("reason", c.ownReason);
            row.put("t0", c.ownFirstT0);
            row.put("newbieQueue", null);
            row.put("secondOutingId", c.ownSecondOutingId);
            classes.add(row);
        }
        data.put("outingClasses", classes);
        return data;
    }

    public Map<String, Object> outing(Long memberId, Map<String, String> q) {
        Context ctx = context(memberId, q);
        Map<String, Object> data = ctx.payload();
        if (ctx.notComputable) return data;
        Cohort c = ctx.cohort;
        data.put("maturity", c.ownMaturity);
        data.put("t0", c.ownFirstT0);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < c.own.size(); i++) {
            Map<String, Object> o = c.own.get(i);
            long t0 = millis(o.get("t0"));
            boolean inWindow = c.ownFirstT0 != null && t0 - c.ownFirstT0 <= ctx.deltaMillis;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("outingId", o.get("outingId"));
            row.put("t0", t0);
            row.put("valid", true);
            row.put("inWindow", inWindow);
            row.put("reason", i == 0 ? "first-outing" : i == 1 ? (inWindow ? "second-outing" : "after-horizon") : "not-effective");
            row.put("maturity", c.ownMaturity);
            row.put("metricVersion", data.get("metricVersion"));
            row.put("factRevision", data.get("factRevision"));
            rows.add(row);
        }
        data.put("outings", rows);
        return data;
    }

    private final class Context {
        Map<String, Object> board;
        long memberId, asOf, deltaMillis;
        String queryId, cursor;
        boolean notComputable;
        Cohort cohort;
        List<String> missing = new ArrayList<>();

        Map<String, Object> payload() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", memberId);
            data.put("queryId", queryId);
            data.put("status", notComputable ? "NOT_COMPUTABLE" : "COMPUTABLE");
            data.put("formulaId", FORMULA);
            data.put("metricVersion", board.get("fairnessMetricVersion"));
            data.put("boardId", board.get("boardId"));
            data.put("regionId", board.get("regionId"));
            data.put("seasonId", board.get("seasonId"));
            data.put("rulesVersion", board.get("ruleVersion"));
            data.put("cursor", cursor);
            data.put("nextCursor", null);
            data.put("asOf", asOf);
            data.put("factRevision", ((Number) board.get("eventSequence")).longValue());
            data.put("missing", missing);
            data.put("readBack", false);
            List<String> gaps = List.of("challengeCompensationExits", "gpsFalseRejections", "safetyTickets", "merchantVerifications",
                    "merchantVerificationMillis");
            data.put("coverageGaps", gaps);
            data.put("groups", notComputable ? List.of() : List.of(group()));
            return data;
        }

        private Map<String, Object> group() {
            Map<String, Object> facts = mapper.selectBoardFacts((String) board.get("boardId"), new Date(asOf));
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("joinTime", null);
            g.put("teamSize", ((Number) board.get("maxMembers")).intValue());
            g.put("actionCost", ((Number) board.get("actionsPerRound")).intValue());
            g.put("newcomer", cohort == null || cohort.ownFirstT0 == null ? null : cohort.ownReason);
            g.put("goalCompletions", count(facts.get("goalCompletions")));
            g.put("effectiveGain", count(facts.get("effectiveGain")));
            g.put("topActorGain", count(facts.get("topActorGain")));
            g.put("totalGain", count(facts.get("totalGain")));
            g.put("zeroPointBlocks", count(facts.get("zeroPointBlocks")));
            g.put("challengeCompensationExits", null);
            g.put("gpsFalseRejections", null);
            g.put("safetyTickets", null);
            g.put("merchantVerifications", null);
            g.put("merchantVerificationMillis", null);
            return g;
        }
    }

    private static Integer count(Object value) {
        if (value == null) return null;
        long v = ((Number) value).longValue();
        return (int) Math.max(0, Math.min(1_000_000, v));
    }

    private Context context(Long memberId, Map<String, String> q) {
        requireMember(memberId);
        if (q.containsKey("accountId") || q.containsKey("outingId")) throw new CityGameException(400, "queryId", "query invalid");
        String boardId = q.get("boardId");
        if (!CityGameRules.id(boardId) || !CityGameRules.id(q.get("regionId")) || !CityGameRules.id(q.get("seasonId"))
                || q.get("rulesVersion") == null) {
            throw new CityGameException(403, "scope-rejected", "范围不匹配");
        }
        String queryId = q.get("queryId");
        if (queryId == null || !queryId.matches("[A-Za-z0-9._-]{1,64}")) throw new CityGameException(400, "queryId", "queryId invalid");
        String asOfText = q.get("asOf");
        if (asOfText == null || !asOfText.matches("[0-9]{1,15}")) throw new CityGameException(400, "asOf", "asOf invalid");
        long asOf = Long.parseLong(asOfText);
        if (asOf > 253_402_300_799_000L || asOf > clock.getAsLong()) throw new CityGameException(400, "asOf", "asOf invalid");
        String cursor = q.get("cursor");
        if (cursor != null && !cursor.matches("[0-9]{1,9}")) throw new CityGameException(400, "cursor", "cursor invalid");
        if (cursor != null && !"0".equals(cursor)) throw new CityGameException(400, "cursor", "cursor out of range");

        Map<String, Object> board = mapper.selectBoard(boardId);
        if (board == null || !board.get("regionId").equals(q.get("regionId")) || !board.get("seasonId").equals(q.get("seasonId"))
                || !board.get("ruleVersion").equals(q.get("rulesVersion"))) {
            throw new CityGameException(403, "scope-rejected", "范围不匹配");
        }
        Context ctx = new Context();
        ctx.board = board;
        ctx.memberId = memberId;
        ctx.asOf = asOf;
        ctx.queryId = queryId;
        ctx.cursor = cursor;
        Object horizon = board.get("fairnessHorizonSeconds"), delta = board.get("fairnessDeltaSeconds");
        if (horizon == null) ctx.missing.add("fairness_horizon");
        if (delta == null) ctx.missing.add("fairness_delta");
        if (board.get("fairnessMetricVersion") == null) ctx.missing.add("fairness_metric_version");
        if (!ctx.missing.isEmpty()) {
            ctx.notComputable = true;
            throw new CityGameException(422, "NOT_COMPUTABLE", "公平指标参数未发布", ctx.payload());
        }
        ctx.deltaMillis = 1000L * ((Number) delta).intValue();
        List<Map<String, Object>> outings = mapper.selectOutings(boardId, new Date(asOf), MAX_OUTINGS + 1);
        if (outings.size() > MAX_OUTINGS) throw new CityGameException(503, "unavailable", "公平指标暂不可读",
                Map.of("missing", List.of("city_fairness_read"), "errorCode", "unavailable"));
        ctx.cohort = cohort(outings, memberId, asOf, 1000L * ((Number) horizon).intValue(), ctx.deltaMillis);
        return ctx;
    }
}
