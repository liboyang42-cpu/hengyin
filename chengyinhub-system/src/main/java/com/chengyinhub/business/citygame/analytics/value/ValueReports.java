package com.chengyinhub.business.citygame.analytics.value;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chengyinhub.business.cityboard.CityBoardDraftDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** 按显式口径计算漏斗、耗时、评分和访问成本。缺覆盖不写 0，也不写成功。 */
final class ValueReports {
    private static final int INCOMPLETE_CAP = 50;
    private static final ObjectMapper JSON = CityBoardDraftDocument.mapper();

    private ValueReports() {
    }

    static Map<String, Object> compute(List<ValueEventRow> facts, List<ValueEventRow> visits, List<ValueEventRow> prior,
            ValueContracts.Definition definition, boolean cohortRequested) {
        ValueContracts.Definition defined = definition == null ? new ValueContracts.Definition() : definition;
        Set<String> meaningful = norm(defined.meaningfulActions);
        Set<String> choices = norm(defined.effectiveChoices);
        List<ValueEventRow> rows = behavior(visits);
        List<Visit> grouped = group(rows, meaningful, choices);
        Set<String> kinds = kinds(rows);
        Set<String> priorKinds = kinds(behavior(prior));

        Map<String, Object> funnel = new LinkedHashMap<String, Object>();
        int entries = count(grouped, visit -> visit.entry != null);
        int meaningfulCount = count(grouped, visit -> visit.meaningful != null);
        int services = count(grouped, visit -> visit.service != null);
        int publics = count(grouped, visit -> visit.publicGoal != null);
        int coops = count(grouped, visit -> visit.coopGoal != null);
        funnel.put("entry", covered(kinds, ValueKinds.ENTRY) ? measured(entries, null) : missing("MISSING_COVERAGE"));
        boolean meaningfulReady = meaningful != null && covered(kinds, ValueKinds.MEANINGFUL_ACTION);
        funnel.put("meaningfulAction", meaningful == null ? missing("MISSING_DEFINITION")
                : !covered(kinds, ValueKinds.MEANINGFUL_ACTION) ? missing("MISSING_COVERAGE")
                : measured(meaningfulCount, entries > 0 && covered(kinds, ValueKinds.ENTRY) ? fraction(meaningfulCount, entries) : null));
        funnel.put("serviceSuccess", !covered(kinds, ValueKinds.SERVICE_SUCCESS) ? missing("MISSING_COVERAGE")
                : measured(services, meaningfulReady && meaningfulCount > 0 ? fraction(services, meaningfulCount) : null));
        funnel.put("publicGoal", !covered(kinds, ValueKinds.PUBLIC_GOAL) ? missing("MISSING_COVERAGE")
                : measured(publics, covered(kinds, ValueKinds.SERVICE_SUCCESS) && services > 0 ? fraction(publics, services) : null));
        funnel.put("cooperativeGoal", !covered(kinds, ValueKinds.COOPERATIVE_GOAL) ? missing("MISSING_COVERAGE")
                : measured(coops, covered(kinds, ValueKinds.SERVICE_SUCCESS) && services > 0 ? fraction(coops, services) : null));

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("funnel", funnel);
        out.put("timeToFirstEffectiveChoice", time(grouped, kinds, choices));
        Map<String, Object> reasons = new LinkedHashMap<String, Object>();
        reasons.put("skip", reasons(rows, ValueKinds.SKIP, covered(kinds, ValueKinds.SKIP)));
        reasons.put("exit", reasons(rows, ValueKinds.EXIT, covered(kinds, ValueKinds.EXIT)));
        out.put("skipExitReasons", reasons);
        out.put("serviceRatings", ratings(rows, kinds, defined));
        out.put("visitCost", cost(grouped, rows, kinds, defined));
        out.put("byService", byService(rows, defined));
        if (cohortRequested) {
            out.put("byCohort", cohort(grouped, prior, priorKinds, kinds, defined.veteranMinPriorServiceSuccess));
        }
        out.put("boardFacts", boardFacts(facts));
        return out;
    }

    private static Map<String, Object> time(List<Visit> visits, Set<String> kinds, Set<String> choices) {
        if (choices == null) {
            return missing("MISSING_DEFINITION");
        }
        if (!covered(kinds, ValueKinds.MEANINGFUL_ACTION)) {
            return missing("MISSING_COVERAGE");
        }
        List<Long> durations = new ArrayList<Long>();
        for (Visit visit : visits) {
            if (visit.choice != null && visit.entry != null) {
                durations.add(visit.choice.getOccurredAt().getTime() - visit.entry.getOccurredAt().getTime());
            }
        }
        Map<String, Object> measured = measured(durations.size(), null);
        if (!durations.isEmpty()) {
            measured.put("medianMillis", median(durations));
        }
        return measured;
    }

    private static Map<String, Object> reasons(List<ValueEventRow> rows, String kind, boolean covered) {
        if (!covered) {
            return missing("MISSING_COVERAGE");
        }
        Map<String, Set<String>> grouped = new TreeMap<String, Set<String>>();
        for (ValueEventRow row : rows) {
            if (kind.equals(row.getKind()) && row.getReasonCode() != null) {
                grouped.computeIfAbsent(row.getReasonCode(), key -> new TreeSet<String>()).add(row.getVisitId());
            }
        }
        List<Map<String, Object>> reasons = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Set<String>> entry : grouped.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("reasonCode", entry.getKey());
            item.put("visitCount", entry.getValue().size());
            reasons.add(item);
        }
        Map<String, Object> out = measured(0, null);
        out.remove("visitCount");
        out.put("reasons", reasons);
        return out;
    }

    private static Map<String, Object> ratings(List<ValueEventRow> rows, Set<String> kinds, ValueContracts.Definition defined) {
        if (defined.ratingMin == null || defined.ratingMax == null) {
            return missing("MISSING_DEFINITION");
        }
        if (!covered(kinds, ValueKinds.SERVICE_RATING)) {
            return missing("MISSING_COVERAGE");
        }
        long sum = 0;
        int count = 0;
        int excluded = 0;
        Map<String, long[]> byService = new TreeMap<String, long[]>();
        for (ValueEventRow row : rows) {
            if (!ValueKinds.SERVICE_RATING.equals(row.getKind()) || row.getRating() == null) {
                continue;
            }
            if (row.getRating() < defined.ratingMin || row.getRating() > defined.ratingMax) {
                excluded++;
                continue;
            }
            sum += row.getRating();
            count++;
            long[] bucket = byService.computeIfAbsent(row.getServiceId(), key -> new long[2]);
            bucket[0] += row.getRating();
            bucket[1]++;
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("status", "MEASURED");
        if (excluded > 0) {
            out.put("excluded", excluded);
        }
        if (count > 0) {
            out.put("count", count);
            out.put("mean", sum + "/" + count);
            out.put("sum", sum);
            List<Map<String, Object>> services = new ArrayList<Map<String, Object>>();
            for (Map.Entry<String, long[]> entry : byService.entrySet()) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("count", entry.getValue()[1]);
                item.put("mean", entry.getValue()[0] + "/" + entry.getValue()[1]);
                item.put("serviceId", entry.getKey());
                item.put("sum", entry.getValue()[0]);
                services.add(item);
            }
            out.put("byService", services);
        }
        return out;
    }

    private static Map<String, Object> cost(List<Visit> visits, List<ValueEventRow> rows, Set<String> kinds,
            ValueContracts.Definition defined) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("netPoints", money(visits, rows, kinds, ValueKinds.NET_POINTS, defined.pointsUnit, null, true));
        out.put("budget", money(visits, rows, kinds, ValueKinds.VISIT_BUDGET, "MINOR", defined.budgetCurrency, false));
        List<Map<String, Object>> perVisit = new ArrayList<Map<String, Object>>();
        for (Visit visit : visits) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            if (visit.points != null && defined.pointsUnit != null && defined.pointsUnit.equals(visit.points.getUnit())) {
                item.put("netPoints", visit.points.getAmount());
            }
            if (visit.budget != null && defined.budgetCurrency != null && defined.budgetCurrency.equals(visit.budget.getCurrency())) {
                item.put("budget", visit.budget.getAmount());
            }
            if (!item.isEmpty()) {
                item.put("visitId", visit.id);
                perVisit.add(item);
            }
            if (perVisit.size() == INCOMPLETE_CAP) {
                break;
            }
        }
        if (!perVisit.isEmpty()) {
            out.put("perVisit", perVisit);
        }
        return out;
    }

    private static Map<String, Object> money(List<Visit> visits, List<ValueEventRow> rows, Set<String> kinds, String kind,
            String unit, String currency, boolean points) {
        if (unit == null || (!points && currency == null)) {
            return missing("MISSING_DEFINITION");
        }
        if (!covered(kinds, kind)) {
            return missing("MISSING_COVERAGE");
        }
        List<ValueEventRow> matched = new ArrayList<ValueEventRow>();
        Set<String> currencies = new TreeSet<String>();
        for (ValueEventRow row : rows) {
            if (!kind.equals(row.getKind()) || !unit.equals(row.getUnit())) {
                continue;
            }
            if ("MINOR".equals(unit)) {
                currencies.add(row.getCurrency());
            }
            matched.add(row);
        }
        if (matched.isEmpty() || ("MINOR".equals(unit) && currency == null && currencies.size() != 1)
                || (currency != null && matched.stream().noneMatch(row -> currency.equals(row.getCurrency())))) {
            return missing(matched.isEmpty() ? "MISSING_UNIT" : "MISSING_UNIT");
        }
        if ("MINOR".equals(unit) && currency == null && currencies.size() != 1) {
            return missing("MISSING_UNIT");
        }
        String useCurrency = currency != null ? currency : ("MINOR".equals(unit) ? currencies.iterator().next() : null);
        if ("MINOR".equals(unit) && currencies.size() > 1 && currency == null) {
            return missing("MISSING_UNIT");
        }
        long sum = 0;
        int count = 0;
        Set<String> contributing = new TreeSet<String>();
        for (ValueEventRow row : matched) {
            if (useCurrency != null && !useCurrency.equals(row.getCurrency())) {
                continue;
            }
            if (row.getAmount() == null) {
                continue;
            }
            sum += row.getAmount();
            count++;
            contributing.add(row.getVisitId());
        }
        if (count == 0) {
            return missing("MISSING_UNIT");
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("status", "MEASURED");
        out.put("sum", sum);
        out.put("unit", unit);
        out.put("visitCount", count);
        if (useCurrency != null) {
            out.put("currency", useCurrency);
        }
        List<String> incomplete = new ArrayList<String>();
        for (Visit visit : visits) {
            if (!contributing.contains(visit.id)) {
                incomplete.add(visit.id);
            }
        }
        if (!incomplete.isEmpty()) {
            List<String> capped = incomplete.size() > INCOMPLETE_CAP ? incomplete.subList(0, INCOMPLETE_CAP) : incomplete;
            out.put("incompleteVisits", new ArrayList<String>(capped));
        }
        return out;
    }

    private static boolean matches(ValueEventRow row, String unit, String currency, boolean points) {
        if (unit == null || row == null || !unit.equals(row.getUnit())) {
            return false;
        }
        if ("POINT".equals(unit)) {
            return points && row.getCurrency() == null;
        }
        return currency != null && currency.equals(row.getCurrency());
    }

    private static Map<String, Object> byService(List<ValueEventRow> rows, ValueContracts.Definition defined) {
        Map<String, Map<String, Object>> services = new TreeMap<String, Map<String, Object>>();
        int unsplit = 0;
        boolean any = false;
        for (ValueEventRow row : rows) {
            if (!ValueKinds.SERVICE_SUCCESS.equals(row.getKind()) && !ValueKinds.SERVICE_RATING.equals(row.getKind())
                    && !ValueKinds.NET_POINTS.equals(row.getKind()) && !ValueKinds.VISIT_BUDGET.equals(row.getKind())) {
                continue;
            }
            if (row.getServiceId() == null) {
                unsplit++;
                continue;
            }
            any = true;
            Map<String, Object> bucket = services.computeIfAbsent(row.getServiceId(), key -> {
                Map<String, Object> created = new LinkedHashMap<String, Object>();
                created.put("serviceId", key);
                return created;
            });
            if (ValueKinds.SERVICE_SUCCESS.equals(row.getKind())) {
                bucket.put("serviceSuccess", ((Number) bucket.getOrDefault("serviceSuccess", 0)).intValue() + 1);
            } else if (ValueKinds.SERVICE_RATING.equals(row.getKind()) && defined.ratingMin != null && defined.ratingMax != null
                    && row.getRating() != null && row.getRating() >= defined.ratingMin && row.getRating() <= defined.ratingMax) {
                bucket.put("ratingCount", ((Number) bucket.getOrDefault("ratingCount", 0)).intValue() + 1);
                bucket.put("ratingSum", ((Number) bucket.getOrDefault("ratingSum", 0L)).longValue() + row.getRating());
            } else if (ValueKinds.NET_POINTS.equals(row.getKind()) && matches(row, defined.pointsUnit, null, true)) {
                bucket.put("netPoints", ((Number) bucket.getOrDefault("netPoints", 0L)).longValue() + row.getAmount());
            } else if (ValueKinds.VISIT_BUDGET.equals(row.getKind()) && matches(row, "MINOR", defined.budgetCurrency, false)) {
                bucket.put("budget", ((Number) bucket.getOrDefault("budget", 0L)).longValue() + row.getAmount());
            }
        }
        if (!any) {
            return missing("MISSING_COVERAGE");
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("services", new ArrayList<Map<String, Object>>(services.values()));
        out.put("status", "MEASURED");
        if (unsplit > 0) {
            out.put("unsplit", unsplit);
        }
        return out;
    }

    private static Map<String, Object> cohort(List<Visit> visits, List<ValueEventRow> prior, Set<String> priorKinds,
            Set<String> kinds, int threshold) {
        if (!covered(priorKinds, ValueKinds.SERVICE_SUCCESS)) {
            return missing("MISSING_COVERAGE");
        }
        Map<Long, Set<String>> pairs = new LinkedHashMap<Long, Set<String>>();
        for (ValueEventRow row : behavior(prior)) {
            if (ValueKinds.SERVICE_SUCCESS.equals(row.getKind())) {
                pairs.computeIfAbsent(row.getMemberId(), key -> new TreeSet<String>())
                        .add(row.getVisitId() + "\n" + row.getServiceId());
            }
        }
        int newcomerVisits = 0;
        int veteranVisits = 0;
        int newcomerService = 0;
        int veteranService = 0;
        for (Visit visit : visits) {
            int priorCount = pairs.getOrDefault(visit.memberId, Set.of()).size();
            boolean veteran = priorCount >= threshold;
            if (veteran) {
                veteranVisits++;
            } else {
                newcomerVisits++;
            }
            if (visit.service != null) {
                if (veteran) {
                    veteranService++;
                } else {
                    newcomerService++;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("newcomer", cohortSide(newcomerVisits, newcomerService, covered(kinds, ValueKinds.SERVICE_SUCCESS)));
        out.put("status", "MEASURED");
        out.put("threshold", threshold);
        out.put("veteran", cohortSide(veteranVisits, veteranService, covered(kinds, ValueKinds.SERVICE_SUCCESS)));
        return out;
    }

    private static Map<String, Object> cohortSide(int visits, int services, boolean serviceCovered) {
        Map<String, Object> side = new LinkedHashMap<String, Object>();
        if (serviceCovered) {
            side.put("serviceSuccess", services);
        }
        side.put("visitCount", visits);
        return side;
    }

    private static Map<String, Object> boardFacts(List<ValueEventRow> facts) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<Object> coverage = new ArrayList<Object>();
        Object definition = null;
        if (facts != null) {
            for (ValueEventRow row : facts) {
                if (ValueKinds.DEFINITION.equals(row.getKind())) {
                    definition = read(row.getDetailJson());
                } else if (ValueKinds.COVERAGE.equals(row.getKind())) {
                    coverage.add(read(row.getDetailJson()));
                }
            }
        }
        out.put("coverage", coverage);
        out.put("definition", definition == null ? "MISSING" : definition);
        return out;
    }

    private static Object read(String json) {
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ex) {
            return "DOCUMENT_UNREADABLE";
        }
    }

    private static ValueEventRow earliest(List<ValueEventRow> rows, String kind, Long afterExclusive,
            java.util.function.Predicate<ValueEventRow> filter) {
        ValueEventRow best = null;
        for (ValueEventRow row : rows) {
            if (!kind.equals(row.getKind()) || ValueKinds.noise(row.getActionCode())) {
                continue;
            }
            if (filter != null && !filter.test(row)) {
                continue;
            }
            long at = row.getOccurredAt().getTime();
            if (afterExclusive != null && at <= afterExclusive) {
                continue;
            }
            if (best == null || at < best.getOccurredAt().getTime()
                    || (at == best.getOccurredAt().getTime() && row.getId() < best.getId())) {
                best = row;
            }
        }
        return best;
    }

    private static List<ValueEventRow> behavior(List<ValueEventRow> rows) {
        List<ValueEventRow> out = new ArrayList<ValueEventRow>();
        if (rows == null) {
            return out;
        }
        for (ValueEventRow row : rows) {
            if (ValueKinds.behavior(row.getKind()) && !ValueKinds.noise(row.getActionCode())) {
                out.add(row);
            }
        }
        return out;
    }

    private static Set<String> kinds(List<ValueEventRow> rows) {
        Set<String> kinds = new TreeSet<String>();
        for (ValueEventRow row : rows) {
            kinds.add(row.getKind());
        }
        return kinds;
    }

    private static Set<String> norm(Set<String> codes) {
        if (codes == null) {
            return null;
        }
        Set<String> out = new TreeSet<String>();
        for (String code : codes) {
            String normalized = ValueKinds.norm(code);
            if (normalized != null && !ValueKinds.noise(normalized)) {
                out.add(normalized);
            }
        }
        return out;
    }

    private static boolean covered(Set<String> kinds, String kind) {
        return kinds.contains(kind);
    }

    private static int count(List<Visit> visits, java.util.function.Predicate<Visit> predicate) {
        int count = 0;
        for (Visit visit : visits) {
            if (predicate.test(visit)) {
                count++;
            }
        }
        return count;
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<Long>(values);
        sorted.sort(Comparator.naturalOrder());
        int size = sorted.size();
        if ((size & 1) == 1) {
            return sorted.get(size / 2);
        }
        return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
    }

    private static String fraction(int numerator, int denominator) {
        return numerator + "/" + denominator;
    }

    private static Map<String, Object> missing(String status) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("status", status);
        return out;
    }

    private static Map<String, Object> measured(int visitCount, String rate) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (rate != null) {
            out.put("rate", rate);
        }
        out.put("status", "MEASURED");
        out.put("visitCount", visitCount);
        return out;
    }

    private static final class Visit {
        private final String id;
        private final Set<String> meaningfulCodes = new TreeSet<String>();
        private final Set<String> choiceCodes = new TreeSet<String>();
        private final List<ValueEventRow> events = new ArrayList<ValueEventRow>();
        private Long memberId;
        private ValueEventRow entry;
        private ValueEventRow meaningful;
        private ValueEventRow choice;
        private ValueEventRow service;
        private ValueEventRow publicGoal;
        private ValueEventRow coopGoal;
        private ValueEventRow points;
        private ValueEventRow budget;

        private Visit(String id) {
            this.id = id;
        }
    }

    private static List<Visit> group(List<ValueEventRow> rows, Set<String> meaningful, Set<String> choices) {
        Map<String, Visit> visits = new TreeMap<String, Visit>();
        for (ValueEventRow row : rows) {
            if (row.getVisitId() == null) {
                continue;
            }
            Visit visit = visits.computeIfAbsent(row.getVisitId(), key -> {
                Visit created = new Visit(key);
                if (meaningful != null) {
                    created.meaningfulCodes.addAll(meaningful);
                }
                if (choices != null) {
                    created.choiceCodes.addAll(choices);
                }
                return created;
            });
            visit.events.add(row);
            if (visit.memberId == null && row.getMemberId() != null) {
                visit.memberId = row.getMemberId();
            }
        }
        for (Visit visit : visits.values()) {
            visit.entry = earliest(visit.events, ValueKinds.ENTRY, null, null);
            Long afterEntry = visit.entry == null ? null : visit.entry.getOccurredAt().getTime();
            visit.meaningful = afterEntry == null ? null : earliest(visit.events, ValueKinds.MEANINGFUL_ACTION, afterEntry,
                    row -> row.getActionCode() != null && visit.meaningfulCodes.contains(row.getActionCode()));
            visit.choice = afterEntry == null ? null : earliest(visit.events, ValueKinds.MEANINGFUL_ACTION, afterEntry,
                    row -> row.getActionCode() != null && visit.choiceCodes.contains(row.getActionCode()));
            Long afterMeaningful = visit.meaningful == null ? null : visit.meaningful.getOccurredAt().getTime();
            visit.service = afterMeaningful == null ? null : earliest(visit.events, ValueKinds.SERVICE_SUCCESS, afterMeaningful, null);
            Long afterService = visit.service == null ? null : visit.service.getOccurredAt().getTime();
            visit.publicGoal = afterService == null ? null : earliest(visit.events, ValueKinds.PUBLIC_GOAL, afterService, null);
            visit.coopGoal = afterService == null ? null : earliest(visit.events, ValueKinds.COOPERATIVE_GOAL, afterService, null);
            visit.points = earliest(visit.events, ValueKinds.NET_POINTS, null, null);
            visit.budget = earliest(visit.events, ValueKinds.VISIT_BUDGET, null, null);
        }
        return new ArrayList<Visit>(visits.values());
    }
}
