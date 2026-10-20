package com.chengyinhub.business.city.draft;

import java.util.*;
import static com.chengyinhub.business.city.draft.CityBoardDraftService.*;

/** Reference facts are supplied by a server authority, never accepted in a save request. */
public final class CityBoardDraftReferenceValidator {
    private CityBoardDraftReferenceValidator() { }
    private static final String[] FIELDS = { "rulesRef", "taskRef", "diceRef", "challengeRef", "settlementRef" };
    private static final String[] KINDS = { "RULES", "TASK", "DICE", "CHALLENGE", "SETTLEMENT" };
    private static List<?> rows(Map<String, Object> catalog, String key) {
        require(catalog != null && catalog.get(key) instanceof List, 503, "REFERENCE_AUTHORITY_UNAVAILABLE");
        return (List<?>) catalog.get(key);
    }
    public static Map<String, Object> catalog(CityBoardDraftAuthority.Context context, Map<String, Object> catalog) {
        List<Object> refs = new ArrayList<>(), places = new ArrayList<>();
        for (Object value : rows(catalog, "references")) {
            Map<String, Object> ref = map(value);
            if (context.regionIds.contains(ref.get("regionId"))) refs.add(object("id", ref.get("id"), "version", ref.get("version"),
                    "hash", ref.get("hash"), "kind", ref.get("kind"), "regionId", ref.get("regionId"), "name", ref.get("name")));
        }
        for (Object value : rows(catalog, "places")) {
            Map<String, Object> place = map(value);
            if (context.regionIds.contains(place.get("regionId")) && Boolean.TRUE.equals(place.get("authorized")))
                places.add(object("placeId", place.get("placeId"), "regionId", place.get("regionId"), "name", place.get("name")));
        }
        return object("regionIds", new TreeSet<>(context.regionIds), "references", refs, "places", places, "draftOnly", true);
    }
    public static Map<String, Object> validate(CityBoardDraftAuthority.Context context, Map<String, Object> catalog, Map<String, Object> payload) {
        Map<String, Object> rule = map(payload.get("rule"));
        String region = (String) payload.get("regionId");
        List<Object> bindings = new ArrayList<>();
        Map<String, Object> rulesEntry = null;
        for (int i = 0; i < FIELDS.length; i++) {
            Map<String, Object> wanted = map(rule.get(FIELDS[i]));
            Map<String, Object> match = null;
            for (Object candidate : rows(catalog, "references")) {
                Map<String, Object> ref = map(candidate);
                if (wanted.get("id").equals(ref.get("id"))) {
                    require(match == null, 503, "AMBIGUOUS_REFERENCE_AUTHORITY"); match = ref;
                }
            }
            require(match != null && region.equals(match.get("regionId")) && "CITY".equals(match.get("scope"))
                    && KINDS[i].equals(match.get("kind")) && Boolean.TRUE.equals(match.get("approved"))
                    && wanted.get("hash").equals(match.get("hash"))
                    && ((Number) wanted.get("version")).longValue() == number(match.get("version"), 1, Integer.MAX_VALUE), 400, "REFERENCE_INVALID");
            if (i == 1) require(Boolean.TRUE.equals(match.get("feasible")), 400, "TASK_NOT_FEASIBLE");
            if (i == 0) rulesEntry = match;
            bindings.add(object("field", FIELDS[i], "reference", copy(wanted)));
        }
        Map<String, Object> limits = map(rulesEntry.get("limits"));
        for (String field : Arrays.asList("actionBudget", "maxHoldings", "protectionSeconds", "acquisitionCostPoints", "serviceFeePoints", "taskRewardPoints", "rewardBudgetPoints")) {
            Map<String, Object> range = map(limits.get(field));
            long min = number(range.get("min"), 0, Integer.MAX_VALUE), max = number(range.get("max"), min, Integer.MAX_VALUE);
            number(rule.get(field), min, max);
        }
        List<Object> facts = new ArrayList<>();
        for (Object value : (List<?>) payload.get("servicePoints")) {
            String placeId = (String) map(value).get("placeId"); Map<String, Object> match = null;
            for (Object candidate : rows(catalog, "places")) {
                Map<String, Object> place = map(candidate);
                if (placeId.equals(place.get("placeId"))) { require(match == null, 503, "AMBIGUOUS_PLACE_AUTHORITY"); match = place; }
            }
            require(match != null && region.equals(match.get("regionId")), 400, "PLACE_REFERENCE_INVALID");
            for (String fact : Arrays.asList("authorized", "licensed", "public", "safe", "open"))
                require(Boolean.TRUE.equals(match.get(fact)), 400, "PLACE_UNAVAILABLE_OR_UNSAFE");
            number(match.get("capacity"), 1, Integer.MAX_VALUE);
            number(match.get("version"), 1, Integer.MAX_VALUE); digest(match.get("hash"));
            require(match.get("taskIds") instanceof List && ((List<?>) match.get("taskIds")).contains(map(rule.get("taskRef")).get("id")), 400, "TASK_PLACE_MISMATCH");
            facts.add(object("placeId", placeId, "version", match.get("version"), "hash", match.get("hash"), "capacity", match.get("capacity")));
        }
        return object("classification", "merchant_operational_fact", "references", bindings, "places", facts, "validationOnly", true);
    }
}
