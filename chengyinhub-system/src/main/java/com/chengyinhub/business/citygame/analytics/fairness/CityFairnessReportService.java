package com.chengyinhub.business.citygame.analytics.fairness;

import com.chengyinhub.business.citygame.analytics.fairness.mapper.CityFairnessMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.chengyinhub.business.citygame.analytics.fairness.FairnessFactContract.*;

/**
 * Account-scoped read, snapshot, and page. A repeated query reads the stored snapshot back.
 */
@Service
public class CityFairnessReportService {
    private static final String QUERY_ID = "[A-Za-z0-9._\\-]{1,64}";
    private final CityFairnessMapper mapper;
    private final CityFairnessReader reader = new CityFairnessReader();

    public CityFairnessReportService(CityFairnessMapper mapper) {
        this.mapper = mapper;
    }

    public Page read(long accountId, String queryId, Long asOf, String cursor) {
        if (accountId <= 0) return Page.reject(401, "UNAUTHORIZED");
        if (queryId == null || !queryId.matches(QUERY_ID)) return Page.reject(400, "queryId");
        if (cursor != null && !cursor.isBlank() && !cursor.matches("[0-9]{1,9}")) return Page.reject(400, "cursor");
        int offset = cursor == null || cursor.isBlank() ? 0 : Integer.parseInt(cursor);
        CityFairnessReadRow stored = mapper.selectRead(accountId, queryId);
        if (stored != null) {
            if (asOf != null && !asOf.equals(stored.asOf)) return Page.reject(409, "query-conflict");
            return slice(accountId, queryId, stored, offset, true);
        }
        if (asOf == null || asOf < 0) return Page.reject(400, "asOf");
        return compute(accountId, queryId, asOf, offset);
    }

    private Page compute(long accountId, String queryId, long asOf, int offset) {
        CityFairnessReader.ParameterRow parameter;
        try {
            parameter = MyBatisCityFairnessSource.oneApproved(mapper.selectParameters());
        } catch (CityFairnessReader.FairnessReadException ex) {
            return persist(accountId, queryId, asOf, offset, CityFairnessReader.NOT_COMPUTABLE, "MISSING",
                    List.of(ex.missing), List.of(), List.of(), List.of(), 0);
        } catch (RuntimeException ex) {
            return Page.reject(503, "city_fairness_parameter");
        }
        List<String> gaps = gaps(parameter);
        if (!gaps.isEmpty()) {
            return persist(accountId, queryId, asOf, offset, CityFairnessReader.NOT_COMPUTABLE, "MISSING",
                    gaps, List.of(), List.of(), List.of(), 0);
        }
        long start;
        try {
            start = Math.subtractExact(asOf, parameter.horizonMillis);
        } catch (ArithmeticException ex) {
            return persist(accountId, queryId, asOf, offset, CityFairnessReader.NOT_COMPUTABLE, "MISSING",
                    List.of(HORIZON_MILLIS), List.of(), List.of(), List.of(), parameter.pageSize);
        }
        List<CityFairnessReader.EventRow> events;
        try {
            events = mapper.selectEvents(accountId, start, asOf, parameter.maxEvents + 1);
        } catch (RuntimeException ex) {
            return Page.reject(503, "city_fairness_event");
        }
        if (events == null) events = List.of();
        if (events.size() > parameter.maxEvents) {
            return persist(accountId, queryId, asOf, offset, CityFairnessReader.NOT_COMPUTABLE, "MISSING",
                    List.of("event-bound"), List.of(), List.of(), List.of(), parameter.pageSize);
        }
        for (CityFairnessReader.EventRow event : events) {
            if (event == null || event.actorId == null || event.actorId != accountId) {
                return persist(accountId, queryId, asOf, offset, CityFairnessReader.NOT_COMPUTABLE, "MISSING",
                        List.of("scope"), List.of(), List.of(), List.of(), parameter.pageSize);
            }
        }
        CityFairnessReader.Result result = reader.read(asOf, new Prefetched(parameter, events));
        return persist(accountId, queryId, asOf, offset, result.status, result.maturity(), result.missing,
                result.groups, result.secondOutings, result.outingClasses, parameter.pageSize);
    }

    private Page persist(long accountId, String queryId, long asOf, int offset, String status, String maturity,
                          List<String> missing, List<CityFairnessReader.Group> groups,
                          List<CityFairnessReader.SecondOuting> seconds, List<CityFairnessReader.OutingClass> classes,
                          int pageSize) {
        CityFairnessReadRow row = new CityFairnessReadRow();
        row.accountId = accountId;
        row.queryId = queryId;
        row.asOf = asOf;
        row.payload = encode(asOf, status, maturity, pageSize, missing, groups, seconds, classes);
        try {
            mapper.insertRead(row);
        } catch (DuplicateKeyException duplicate) {
            CityFairnessReadRow winner = mapper.selectRead(accountId, queryId);
            if (winner == null || !winner.asOf.equals(asOf)) return Page.reject(409, "query-conflict");
            return slice(accountId, queryId, winner, offset, true);
        } catch (RuntimeException ex) {
            return Page.reject(503, "city_fairness_read");
        }
        return slice(accountId, queryId, row, offset, false);
    }

    private static List<String> gaps(CityFairnessReader.ParameterRow parameter) {
        List<String> missing = new ArrayList<>();
        if (parameter == null) {
            missing.add(DELTA_MILLIS);
            missing.add(HORIZON_MILLIS);
            missing.add(LOWER_EFFECTIVE_EVENTS);
            missing.add(PARAMETER_APPROVAL);
            missing.add(PARAMETER_SOURCE_REF);
            missing.add(MAX_EVENTS);
            missing.add(PAGE_SIZE);
            return missing;
        }
        if (!"APPROVED".equals(parameter.state)) missing.add(PARAMETER_APPROVAL);
        if (parameter.sourceRef == null || parameter.sourceRef.isBlank()) missing.add(PARAMETER_SOURCE_REF);
        if (parameter.deltaMillis == null || parameter.deltaMillis <= 0) missing.add(DELTA_MILLIS);
        if (parameter.horizonMillis == null || parameter.horizonMillis <= 0) missing.add(HORIZON_MILLIS);
        if (parameter.lowerEffectiveEvents == null || parameter.lowerEffectiveEvents <= 0) missing.add(LOWER_EFFECTIVE_EVENTS);
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
                decoded.missing, List.copyOf(decoded.groups.subList(from, to)), decoded.outingClasses,
                decoded.secondOutings, Integer.toString(from), next);
    }

    static String encode(long asOf, String status, String maturity, int pageSize, List<String> missing,
                          List<CityFairnessReader.Group> groups, List<CityFairnessReader.SecondOuting> seconds,
                          List<CityFairnessReader.OutingClass> classes) {
        StringBuilder out = new StringBuilder();
        out.append("v1\n").append(asOf).append('\n').append(status).append('\n').append(maturity).append('\n')
                .append(pageSize).append('\n').append(String.join("\u001f", missing)).append('\n');
        for (CityFairnessReader.Group group : groups) {
            out.append("G\t").append(cell(group.joinTime)).append('\t').append(cell(group.teamSize)).append('\t')
                    .append(cell(group.actionCost)).append('\t').append(cell(group.newcomer)).append('\t')
                    .append(group.goalCompletions).append('\t').append(group.effectiveGain).append('\t')
                    .append(group.topActorGain).append('\t').append(group.totalGain).append('\t')
                    .append(group.zeroPointBlocks).append('\t').append(group.challengeCompensationExits).append('\t')
                    .append(group.gpsFalseRejections).append('\t').append(group.safetyTickets).append('\t')
                    .append(group.merchantVerifications).append('\t').append(group.merchantVerificationMillis).append('\n');
        }
        for (CityFairnessReader.OutingClass item : classes) {
            out.append("C\t").append(item.outingId).append('\t').append(item.actorId).append('\t')
                    .append(item.maturity).append('\t').append(item.reason).append('\n');
        }
        for (CityFairnessReader.SecondOuting item : seconds) {
            out.append("S\t").append(item.actorId).append('\t').append(item.outingId).append('\t')
                    .append(item.anchorMillis).append('\t').append(cell(item.joinTime)).append('\t')
                    .append(cell(item.teamSize)).append('\t').append(cell(item.actionCost)).append('\t')
                    .append(cell(item.newcomer)).append('\n');
        }
        return out.toString();
    }

    private static Decoded decode(String payload) {
        String[] lines = payload.split("\n", -1);
        Decoded decoded = new Decoded();
        decoded.status = lines[2];
        decoded.maturity = lines[3];
        decoded.pageSize = Integer.parseInt(lines[4]);
        decoded.missing = lines[5].isEmpty() ? List.of() : List.of(lines[5].split("\u001f", -1));
        List<CityFairnessReader.Group> groups = new ArrayList<>();
        List<CityFairnessReader.OutingClass> classes = new ArrayList<>();
        List<CityFairnessReader.SecondOuting> seconds = new ArrayList<>();
        for (int i = 6; i < lines.length; i++) {
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
                classes.add(new CityFairnessReader.OutingClass(field[1], Long.parseLong(field[2]), field[3], field[4]));
            } else if (field[0].equals("S")) {
                seconds.add(new CityFairnessReader.SecondOuting(Long.parseLong(field[1]), field[2], Long.parseLong(field[3]),
                        longOrNull(field[4]), intOrNull(field[5]), intOrNull(field[6]), textOrNull(field[7])));
            }
        }
        decoded.groups = groups;
        decoded.outingClasses = List.copyOf(classes);
        decoded.secondOutings = List.copyOf(seconds);
        return decoded;
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
        public List<CityFairnessReader.EventRow> events(long windowStartExclusive, long asOfInclusive) { return events; }
        public List<String> unavailableStreams() { return List.of(); }
    }

    private static final class Decoded {
        String status;
        String maturity;
        int pageSize;
        List<String> missing = List.of();
        List<CityFairnessReader.Group> groups = List.of();
        List<CityFairnessReader.OutingClass> outingClasses = List.of();
        List<CityFairnessReader.SecondOuting> secondOutings = List.of();
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
        public final List<String> missing;
        public final List<CityFairnessReader.Group> groups;
        public final List<CityFairnessReader.OutingClass> outingClasses;
        public final List<CityFairnessReader.SecondOuting> secondOutings;
        public final String cursor;
        public final String nextCursor;

        Page(int httpStatus, String errorCode, boolean readBack, long accountId, String queryId, Long asOf,
             String status, String maturity, List<String> missing, List<CityFairnessReader.Group> groups,
             List<CityFairnessReader.OutingClass> outingClasses, List<CityFairnessReader.SecondOuting> secondOutings,
             String cursor, String nextCursor) {
            this.httpStatus = httpStatus;
            this.errorCode = errorCode;
            this.readBack = readBack;
            this.accountId = accountId;
            this.queryId = queryId;
            this.asOf = asOf;
            this.status = status;
            this.maturity = maturity;
            this.missing = missing;
            this.groups = groups;
            this.outingClasses = outingClasses;
            this.secondOutings = secondOutings;
            this.cursor = cursor;
            this.nextCursor = nextCursor;
        }

        static Page reject(int httpStatus, String errorCode) {
            return new Page(httpStatus, errorCode, false, 0, null, null, null, null, List.of(), List.of(), List.of(),
                    List.of(), null, null);
        }
    }
}
