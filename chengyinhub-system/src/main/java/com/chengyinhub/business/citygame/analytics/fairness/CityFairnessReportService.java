package com.chengyinhub.business.citygame.analytics.fairness;

import com.chengyinhub.business.citygame.analytics.fairness.contract.FairnessFieldContract;
import com.chengyinhub.business.citygame.analytics.fairness.mapper.CityFairnessMapper;
import com.chengyinhub.business.citygame.analytics.fairness.source.CityFairnessOnsiteRelay;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.chengyinhub.business.citygame.analytics.fairness.FairnessFactContract.*;

/**
 * Account- and scope-scoped read, snapshot, and page. A repeated query reads that scope's snapshot back.
 */
@Service
public class CityFairnessReportService {
    private static final String QUERY_ID = "[A-Za-z0-9._\\-]{1,64}";
    private final CityFairnessMapper mapper;
    private final CityFairnessReader reader = new CityFairnessReader();

    public CityFairnessReportService(CityFairnessMapper mapper) {
        this.mapper = mapper;
    }

    public Page read(long accountId, String queryId, Long asOf, String cursor,
                     String boardId, String regionId, String seasonId, String rulesVersion) {
        return load(accountId, queryId, asOf, cursor, boardId, regionId, seasonId, rulesVersion, false);
    }

    public Page outings(long accountId, String queryId, Long asOf, String cursor,
                        String boardId, String regionId, String seasonId, String rulesVersion) {
        return load(accountId, queryId, asOf, cursor, boardId, regionId, seasonId, rulesVersion, true);
    }

    private Page load(long accountId, String queryId, Long asOf, String cursor,
                      String boardId, String regionId, String seasonId, String rulesVersion, boolean ownOutings) {
        if (accountId <= 0) return Page.reject(401, "UNAUTHORIZED", asOf);
        if (queryId == null || !queryId.matches(QUERY_ID)) return Page.reject(400, "queryId", asOf);
        if (cursor != null && !cursor.isBlank() && !cursor.matches("[0-9]{1,9}")) return Page.reject(400, "cursor", asOf);
        List<String> scopeMissing = new ArrayList<>();
        if (boardId == null || boardId.isBlank()) scopeMissing.add("boardId");
        if (regionId == null || regionId.isBlank()) scopeMissing.add("regionId");
        if (seasonId == null || seasonId.isBlank()) scopeMissing.add("seasonId");
        if (rulesVersion == null || rulesVersion.isBlank()) scopeMissing.add("rulesVersion");
        if (!scopeMissing.isEmpty()) {
            return unanswered(accountId, queryId, asOf, boardId, regionId, seasonId, rulesVersion, scopeMissing);
        }
        int offset = cursor == null || cursor.isBlank() || "0".equals(cursor) ? 0 : Integer.parseInt(cursor);
        CityFairnessReadRow stored;
        try {
            stored = mapper.selectRead(accountId, queryId, boardId, regionId, seasonId, rulesVersion);
        } catch (RuntimeException ex) {
            return unavailable(accountId, queryId, asOf, boardId, regionId, seasonId, rulesVersion);
        }
        if (stored != null) {
            if (asOf != null && !asOf.equals(stored.asOf)) return Page.reject(409, "query-conflict", asOf);
            return present(accountId, queryId, stored, offset, true, ownOutings);
        }
        if (asOf == null || asOf < 0) return Page.reject(400, "asOf", asOf);
        return compute(accountId, queryId, asOf, offset, boardId, regionId, seasonId, rulesVersion, ownOutings);
    }

    private Page compute(long accountId, String queryId, long asOf, int offset,
                         String boardId, String regionId, String seasonId, String rulesVersion, boolean ownOutings) {
        CityFairnessReader.ParameterRow parameter;
        try {
            parameter = MyBatisCityFairnessSource.oneApproved(
                    mapper.selectParameters(boardId, regionId, seasonId, rulesVersion),
                    boardId, regionId, seasonId, rulesVersion);
        } catch (CityFairnessReader.FairnessReadException ex) {
            return persist(accountId, queryId, asOf, offset, boardId, regionId, seasonId, rulesVersion,
                    CityFairnessReader.Result.notComputable(asOf, List.of(ex.missing)), 0, ownOutings, sourceGaps());
        } catch (RuntimeException ex) {
            return Page.reject(503, "city_fairness_parameter", asOf);
        }
        if (parameter == null) return Page.reject(403, "scope-rejected", asOf);
        List<String> gaps = gaps(parameter);
        if (!gaps.isEmpty()) {
            return persist(accountId, queryId, asOf, offset, boardId, regionId, seasonId, rulesVersion,
                    CityFairnessReader.Result.notComputable(asOf, gaps, parameter.metricVersion), 0, ownOutings, sourceGaps());
        }
        List<CityFairnessReader.EventRow> events;
        try {
            events = mapper.selectEvents(boardId, regionId, seasonId, rulesVersion, accountId, asOf, parameter.maxEvents + 1);
        } catch (RuntimeException ex) {
            return Page.reject(503, "city_fairness_event", asOf);
        }
        if (events == null) events = List.of();
        List<CityFairnessReader.EventRow> own = new ArrayList<>();
        for (CityFairnessReader.EventRow event : events) {
            if (event != null && event.actorId != null && event.actorId > 0 && event.actorId != accountId) continue;
            own.add(event);
        }
        events = own;
        List<String> coverage = coverageGaps(events);
        if (events.size() > parameter.maxEvents) {
            return persist(accountId, queryId, asOf, offset, boardId, regionId, seasonId, rulesVersion,
                    CityFairnessReader.Result.notComputable(asOf, List.of("event-bound"), parameter.metricVersion),
                    parameter.pageSize, ownOutings, coverage);
        }
        for (CityFairnessReader.EventRow event : events) {
            if (event == null || event.actorId == null || event.actorId != accountId
                    || !boardId.equals(event.boardId) || !regionId.equals(event.regionId)
                    || !seasonId.equals(event.seasonId) || !rulesVersion.equals(event.rulesVersion)) {
                return persist(accountId, queryId, asOf, offset, boardId, regionId, seasonId, rulesVersion,
                        CityFairnessReader.Result.notComputable(asOf, List.of("scope"), parameter.metricVersion),
                        parameter.pageSize, ownOutings, coverage);
            }
        }
        CityFairnessReader.Result result = reader.read(asOf, new Prefetched(parameter, events));
        return persist(accountId, queryId, asOf, offset, boardId, regionId, seasonId, rulesVersion, result,
                parameter.pageSize, ownOutings, coverage);
    }

    private Page persist(long accountId, String queryId, long asOf, int offset, String boardId, String regionId,
                          String seasonId, String rulesVersion, CityFairnessReader.Result result, int pageSize,
                          boolean ownOutings, List<String> coverageGaps) {
        CityFairnessReadRow row = new CityFairnessReadRow();
        row.accountId = accountId;
        row.queryId = queryId;
        row.asOf = asOf;
        row.boardId = boardId;
        row.regionId = regionId;
        row.seasonId = seasonId;
        row.rulesVersion = rulesVersion;
        row.payload = encode(result, pageSize, coverageGaps);
        try {
            mapper.insertRead(row);
        } catch (DuplicateKeyException duplicate) {
            CityFairnessReadRow winner;
            try {
                winner = mapper.selectRead(accountId, queryId, boardId, regionId, seasonId, rulesVersion);
            } catch (RuntimeException ex) {
                return unavailable(accountId, queryId, asOf, boardId, regionId, seasonId, rulesVersion);
            }
            if (winner == null || !winner.asOf.equals(asOf)) return Page.reject(409, "query-conflict", asOf);
            return present(accountId, queryId, winner, offset, true, ownOutings);
        } catch (RuntimeException ex) {
            return unavailable(accountId, queryId, asOf, boardId, regionId, seasonId, rulesVersion);
        }
        return present(accountId, queryId, row, offset, false, ownOutings);
    }

    private static Page present(long accountId, String queryId, CityFairnessReadRow row, int offset, boolean readBack,
                                boolean ownOutings) {
        return ownOutings ? sliceOutings(accountId, queryId, row, offset, readBack)
                : slice(accountId, queryId, row, offset, readBack);
    }

    private static Page unanswered(long accountId, String queryId, Long asOf, String boardId, String regionId,
                                   String seasonId, String rulesVersion, List<String> missing) {
        List<String> sorted = new ArrayList<>(missing);
        sorted.sort(String::compareTo);
        return new Page(200, null, false, accountId, queryId, asOf, CityFairnessReader.NOT_COMPUTABLE, "MISSING",
                null, null, null, FairnessFieldContract.FORMULA_ID, null, boardId, regionId, seasonId, rulesVersion,
                sorted, List.of(), List.of(), List.of(), List.of(), null, null, List.of(), null, sourceGaps());
    }

    private static Page unavailable(long accountId, String queryId, Long asOf, String boardId, String regionId,
                                    String seasonId, String rulesVersion) {
        return new Page(503, "unavailable", false, accountId, queryId, asOf, null, null,
                null, null, null, null, null, boardId, regionId, seasonId, rulesVersion,
                List.of("city_fairness_read"), List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(), null, sourceGaps());
    }

    private static List<String> gaps(CityFairnessReader.ParameterRow parameter) {
        List<String> missing = new ArrayList<>();
        if (!"APPROVED".equals(parameter.state)) missing.add(PARAMETER_APPROVAL);
        if (parameter.sourceRef == null || parameter.sourceRef.isBlank()) missing.add(PARAMETER_SOURCE_REF);
        if (parameter.deltaMillis == null || parameter.deltaMillis <= 0) missing.add(DELTA_MILLIS);
        if (parameter.horizonMillis == null || parameter.horizonMillis <= 0) missing.add(HORIZON_MILLIS);
        if (parameter.graceMillis == null || parameter.graceMillis <= 0) missing.add(GRACE_MILLIS);
        if (parameter.mergeWindowMillis == null || parameter.mergeWindowMillis <= 0) missing.add(MERGE_WINDOW_MILLIS);
        if (!FairnessFieldContract.STATE_RULE.equals(parameter.stateRule)) missing.add(STATE_RULE);
        if (parameter.metricVersion == null || parameter.metricVersion.isBlank()) missing.add(METRIC_VERSION);
        if (parameter.maxEvents == null || parameter.maxEvents <= 0) missing.add(MAX_EVENTS);
        if (parameter.pageSize == null || parameter.pageSize <= 0) missing.add(PAGE_SIZE);
        missing.sort(String::compareTo);
        return missing;
    }

    private static Page slice(long accountId, String queryId, CityFairnessReadRow row, int offset, boolean readBack) {
        Decoded decoded = decode(row.payload);
        int size = decoded.pageSize <= 0 ? 0 : decoded.groups.size();
        int from = Math.min(Math.max(offset, 0), size);
        int to = decoded.pageSize <= 0 ? from : Math.min(size, from + decoded.pageSize);
        String next = to < size ? Integer.toString(to) : null;
        return new Page(200, null, readBack, accountId, queryId, row.asOf, decoded.status, decoded.maturity,
                decoded.metricVersion, decoded.matureCount, decoded.denominator, decoded.formulaId, decoded.factRevision,
                row.boardId, row.regionId, row.seasonId, row.rulesVersion, decoded.missing,
                List.copyOf(decoded.groups.subList(from, to)), ownClasses(decoded.outingClasses, accountId),
                ownSeconds(decoded.secondOutings, accountId), decoded.lateEvents, shownCursor(from), next, List.of(), null,
                decoded.coverageGaps);
    }

    private static Page sliceOutings(long accountId, String queryId, CityFairnessReadRow row, int offset, boolean readBack) {
        Decoded decoded = decode(row.payload);
        List<CityFairnessReader.OutingEvidence> own = new ArrayList<>();
        Long t0 = null;
        String maturity = decoded.maturity;
        for (CityFairnessReader.OutingClass item : decoded.outingClasses) {
            if (item.actorId == accountId) {
                t0 = item.t0;
                maturity = item.maturity;
            }
        }
        for (CityFairnessReader.OutingEvidence item : decoded.evidence) {
            if (item.actorId == accountId) own.add(item);
        }
        int size = decoded.pageSize <= 0 ? 0 : own.size();
        int from = Math.min(Math.max(offset, 0), size);
        int to = decoded.pageSize <= 0 ? from : Math.min(size, from + decoded.pageSize);
        String next = to < size ? Integer.toString(to) : null;
        return new Page(200, null, readBack, accountId, queryId, row.asOf, decoded.status, maturity,
                decoded.metricVersion, decoded.matureCount, decoded.denominator, decoded.formulaId, decoded.factRevision,
                row.boardId, row.regionId, row.seasonId, row.rulesVersion, decoded.missing, List.of(), List.of(),
                List.of(), List.of(), shownCursor(from), next, List.copyOf(own.subList(from, to)), t0,
                decoded.coverageGaps);
    }

    private static String shownCursor(int from) {
        return from == 0 ? null : Integer.toString(from);
    }

    private static List<CityFairnessReader.OutingClass> ownClasses(List<CityFairnessReader.OutingClass> items, long accountId) {
        List<CityFairnessReader.OutingClass> own = new ArrayList<>();
        for (CityFairnessReader.OutingClass item : items) {
            if (item.actorId == accountId) own.add(item);
        }
        return List.copyOf(own);
    }

    private static List<CityFairnessReader.SecondOuting> ownSeconds(List<CityFairnessReader.SecondOuting> items, long accountId) {
        List<CityFairnessReader.SecondOuting> own = new ArrayList<>();
        for (CityFairnessReader.SecondOuting item : items) {
            if (item.actorId == accountId) own.add(item);
        }
        return List.copyOf(own);
    }

    private static List<String> sourceGaps() {
        return List.copyOf(CityFairnessOnsiteRelay.COVERAGE_GAPS);
    }

    private static List<String> coverageGaps(List<CityFairnessReader.EventRow> events) {
        boolean outing = false;
        boolean novice = false;
        boolean headcount = false;
        boolean join = false;
        boolean revenue = false;
        boolean gps = false;
        boolean safety = false;
        boolean latency = false;
        for (CityFairnessReader.EventRow event : events) {
            if (event == null) continue;
            if (event.outingId != null && !event.outingId.isBlank()) outing = true;
            if (event.newcomer != null && !event.newcomer.isBlank()) novice = true;
            if (event.teamSize != null) headcount = true;
            if (event.joinTime != null) join = true;
            if (GAIN.equals(event.kind) || event.gainAmount != null) revenue = true;
            if (GPS_FALSE_REJECTION.equals(event.kind)) gps = true;
            if (SAFETY_TICKET.equals(event.kind)) safety = true;
            if (MERCHANT_VERIFICATION.equals(event.kind)) latency = true;
        }
        List<String> gaps = new ArrayList<>();
        for (String gap : CityFairnessOnsiteRelay.COVERAGE_GAPS) {
            boolean covered = switch (gap) {
                case "outing_id" -> outing;
                case "novice-label" -> novice;
                case "headcount" -> headcount;
                case "join-time" -> join;
                case "revenue" -> revenue;
                case "gps-false-rejections" -> gps;
                case "safety-tickets" -> safety;
                case "verification-latency" -> latency;
                default -> false;
            };
            if (!covered) gaps.add(gap);
        }
        return List.copyOf(gaps);
    }

    static String encode(CityFairnessReader.Result result, int pageSize, List<String> coverageGaps) {
        StringBuilder out = new StringBuilder();
        out.append("v3\n").append(result.asOf).append('\n').append(result.status).append('\n').append(result.maturity())
                .append('\n').append(pageSize).append('\n').append(cell(result.metricVersion)).append('\n')
                .append(cell(result.matureCount)).append('\n').append(cell(result.denominator)).append('\n')
                .append(cell(result.formulaId)).append('\n').append(cell(result.factRevision)).append('\n')
                .append(String.join("\u001f", result.missing)).append('\n');
        for (CityFairnessReader.Group group : result.groups) {
            out.append("G\t").append(cell(group.joinTime)).append('\t').append(cell(group.teamSize)).append('\t')
                    .append(cell(group.actionCost)).append('\t').append(cell(group.newcomer)).append('\t')
                    .append(group.goalCompletions).append('\t').append(group.effectiveGain).append('\t')
                    .append(group.topActorGain).append('\t').append(group.totalGain).append('\t')
                    .append(group.zeroPointBlocks).append('\t').append(group.challengeCompensationExits).append('\t')
                    .append(group.gpsFalseRejections).append('\t').append(group.safetyTickets).append('\t')
                    .append(group.merchantVerifications).append('\t').append(group.merchantVerificationMillis).append('\n');
        }
        for (CityFairnessReader.OutingClass item : result.outingClasses) {
            out.append("C\t").append(item.outingId).append('\t').append(item.actorId).append('\t')
                    .append(item.maturity).append('\t').append(item.reason).append('\t').append(cell(item.t0)).append('\t')
                    .append(cell(item.newbieQueue)).append('\t').append(cell(item.secondOutingId)).append('\n');
        }
        for (CityFairnessReader.SecondOuting item : result.secondOutings) {
            out.append("S\t").append(item.actorId).append('\t').append(item.outingId).append('\t')
                    .append(item.anchorMillis).append('\t').append(cell(item.joinTime)).append('\t')
                    .append(cell(item.teamSize)).append('\t').append(cell(item.actionCost)).append('\t')
                    .append(cell(item.newcomer)).append('\n');
        }
        for (CityFairnessReader.LateEvent item : result.lateEvents) {
            out.append("L\t").append(item.outingId).append('\t').append(item.eventId).append('\t')
                    .append(item.occurredAt).append('\n');
        }
        for (CityFairnessReader.OutingEvidence item : result.evidence) {
            out.append("E\t").append(item.actorId).append('\t').append(item.outingId).append('\t')
                    .append(cell(item.t0)).append('\t').append(item.valid).append('\t').append(item.reason).append('\t')
                    .append(item.inWindow).append('\t').append(item.maturity).append('\n');
        }
        out.append("K\t").append(String.join("\u001f", coverageGaps == null ? List.of() : coverageGaps)).append('\n');
        return out.toString();
    }

    private static Decoded decode(String payload) {
        String[] lines = payload.split("\n", -1);
        Decoded decoded = new Decoded();
        decoded.status = lines[2];
        decoded.maturity = lines[3];
        decoded.pageSize = Integer.parseInt(lines[4]);
        decoded.metricVersion = textOrNull(lines[5]);
        decoded.matureCount = intOrNull(lines[6]);
        decoded.denominator = intOrNull(lines[7]);
        decoded.formulaId = textOrNull(lines[8]);
        decoded.factRevision = longOrNull(lines[9]);
        decoded.missing = lines[10].isEmpty() ? List.of() : List.of(lines[10].split("\u001f", -1));
        List<CityFairnessReader.Group> groups = new ArrayList<>();
        List<CityFairnessReader.OutingClass> classes = new ArrayList<>();
        List<CityFairnessReader.SecondOuting> seconds = new ArrayList<>();
        List<CityFairnessReader.LateEvent> late = new ArrayList<>();
        List<CityFairnessReader.OutingEvidence> evidence = new ArrayList<>();
        for (int i = 11; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            String[] field = lines[i].split("\t", -1);
            if (field[0].equals("G")) {
                CityFairnessReader.Group group = CityFairnessReader.restoreGroup(
                        longOrNull(field[1]), intOrNull(field[2]), intOrNull(field[3]), textOrNull(field[4]));
                group.goalCompletions = Integer.parseInt(field[5]);
                group.effectiveGain = Long.parseLong(field[6]);
                group.topActorGain = Long.parseLong(field[7]);
                group.totalGain = Long.parseLong(field[8]);
                group.zeroPointBlocks = Integer.parseInt(field[9]);
                group.challengeCompensationExits = Integer.parseInt(field[10]);
                group.gpsFalseRejections = Integer.parseInt(field[11]);
                group.safetyTickets = Integer.parseInt(field[12]);
                group.merchantVerifications = Integer.parseInt(field[13]);
                group.merchantVerificationMillis = Long.parseLong(field[14]);
                groups.add(group);
            } else if (field[0].equals("C")) {
                classes.add(new CityFairnessReader.OutingClass(field[1], Long.parseLong(field[2]), field[3], field[4],
                        longOrNull(field[5]), textOrNull(field[6]), textOrNull(field[7])));
            } else if (field[0].equals("S")) {
                seconds.add(new CityFairnessReader.SecondOuting(Long.parseLong(field[1]), field[2], Long.parseLong(field[3]),
                        longOrNull(field[4]), intOrNull(field[5]), intOrNull(field[6]), textOrNull(field[7])));
            } else if (field[0].equals("L")) {
                late.add(new CityFairnessReader.LateEvent(field[1], field[2], Long.parseLong(field[3])));
            } else if (field[0].equals("E")) {
                evidence.add(new CityFairnessReader.OutingEvidence(Long.parseLong(field[1]), field[2], longOrNull(field[3]),
                        Boolean.parseBoolean(field[4]), field[5], Boolean.parseBoolean(field[6]), field[7]));
            } else if (field[0].equals("K")) {
                evidenceGaps(decoded, field);
            }
        }
        decoded.groups = groups;
        decoded.outingClasses = List.copyOf(classes);
        decoded.secondOutings = List.copyOf(seconds);
        decoded.lateEvents = List.copyOf(late);
        decoded.evidence = List.copyOf(evidence);
        return decoded;
    }

    private static void evidenceGaps(Decoded decoded, String[] field) {
        if (field.length < 2 || field[1].isEmpty()) decoded.coverageGaps = List.of();
        else decoded.coverageGaps = List.of(field[1].split("\u001f", -1));
    }

    private static String cell(Object value) { return value == null ? "-" : value.toString(); }

    private static Long longOrNull(String value) { return "-".equals(value) ? null : Long.valueOf(value); }

    private static Integer intOrNull(String value) { return "-".equals(value) ? null : Integer.valueOf(value); }

    private static String textOrNull(String value) { return "-".equals(value) ? null : value; }

    private static final class Prefetched implements CityFairnessReader.Source {
        private final CityFairnessReader.ParameterRow parameter;
        private final List<CityFairnessReader.EventRow> events;

        Prefetched(CityFairnessReader.ParameterRow parameter, List<CityFairnessReader.EventRow> events) {
            this.parameter = parameter;
            this.events = events;
        }

        public CityFairnessReader.ParameterRow parameters() { return parameter; }
        public List<CityFairnessReader.EventRow> events(long asOfInclusive) { return events; }
        public List<String> unavailableStreams() { return List.of(); }
    }

    private static final class Decoded {
        String status;
        String maturity;
        int pageSize;
        String metricVersion;
        Integer matureCount;
        Integer denominator;
        String formulaId;
        Long factRevision;
        List<String> missing = List.of();
        List<CityFairnessReader.Group> groups = List.of();
        List<CityFairnessReader.OutingClass> outingClasses = List.of();
        List<CityFairnessReader.SecondOuting> secondOutings = List.of();
        List<CityFairnessReader.LateEvent> lateEvents = List.of();
        List<CityFairnessReader.OutingEvidence> evidence = List.of();
        List<String> coverageGaps = List.of();
    }

    public static final class Page {
        public final int httpStatus;
        public final String errorCode;
        public final boolean readBack;
        public final long accountId;
        public final String queryId;
        public final Long asOf;
        public final String status;
        public final String maturity;
        public final String metricVersion;
        public final Integer matureCount;
        public final Integer denominator;
        public final String formulaId;
        public final Long factRevision;
        public final String boardId;
        public final String regionId;
        public final String seasonId;
        public final String rulesVersion;
        public final List<String> missing;
        public final List<CityFairnessReader.Group> groups;
        public final List<CityFairnessReader.OutingClass> outingClasses;
        public final List<CityFairnessReader.SecondOuting> secondOutings;
        public final List<CityFairnessReader.LateEvent> lateEvents;
        public final String cursor;
        public final String nextCursor;
        public final List<CityFairnessReader.OutingEvidence> outings;
        public final Long t0;
        public final List<String> coverageGaps;

        Page(int httpStatus, String errorCode, boolean readBack, long accountId, String queryId, Long asOf,
             String status, String maturity, String metricVersion, Integer matureCount, Integer denominator,
             String formulaId, Long factRevision, String boardId, String regionId, String seasonId, String rulesVersion,
             List<String> missing, List<CityFairnessReader.Group> groups, List<CityFairnessReader.OutingClass> outingClasses,
             List<CityFairnessReader.SecondOuting> secondOutings, List<CityFairnessReader.LateEvent> lateEvents,
             String cursor, String nextCursor, List<CityFairnessReader.OutingEvidence> outings, Long t0,
             List<String> coverageGaps) {
            this.httpStatus = httpStatus;
            this.errorCode = errorCode;
            this.readBack = readBack;
            this.accountId = accountId;
            this.queryId = queryId;
            this.asOf = asOf;
            this.status = status;
            this.maturity = maturity;
            this.metricVersion = metricVersion;
            this.matureCount = matureCount;
            this.denominator = denominator;
            this.formulaId = formulaId;
            this.factRevision = factRevision;
            this.boardId = boardId;
            this.regionId = regionId;
            this.seasonId = seasonId;
            this.rulesVersion = rulesVersion;
            this.missing = missing;
            this.groups = groups;
            this.outingClasses = outingClasses;
            this.secondOutings = secondOutings;
            this.lateEvents = lateEvents;
            this.cursor = cursor;
            this.nextCursor = nextCursor;
            this.outings = outings;
            this.t0 = t0;
            this.coverageGaps = coverageGaps == null ? List.of() : List.copyOf(coverageGaps);
        }

        static Page reject(int httpStatus, String errorCode, Long asOf) {
            return new Page(httpStatus, errorCode, false, 0, null, asOf, null, null, null, null, null,
                    null, null, null, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), null, null,
                    List.of(), null, List.of());
        }
    }
}
