package com.chengyinhub.business.discovery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/**
 * W14 isolated server-contract evaluation only. No Spring bean, endpoint, publisher or award.
 * Source must load current owner-scoped authority and append-only committed events together.
 * There is deliberately no production Source: existing route actions do not prove release binding.
 */
public final class EggDiscoveryEvaluator {
    public enum Mode { DISABLED, ISOLATED_EVALUATION }
    public enum Kind { THEME, MAP }
    private final Mode mode;
    private final Source source;
    private final Clock clock;

    public EggDiscoveryEvaluator() { this.mode = Mode.DISABLED; this.source = null; this.clock = null; }
    public EggDiscoveryEvaluator(Mode mode, Source source, Clock clock) {
        require(mode != null && source != null && clock != null, "DEPENDENCIES_REQUIRED");
        this.mode = mode; this.source = source; this.clock = clock;
    }

    /** Only identity is supplied by the caller; rules, safety and evidence are never request DTOs. */
    public Receipt discover(long memberId, Scope scope, String definitionId) {
        require(mode == Mode.ISOLATED_EVALUATION, "DISCOVERY_DISABLED");
        require(memberId > 0 && scope != null, "OWNER_SCOPE_REQUIRED");
        token(definitionId);
        Instant now = clock.instant();
        Snapshot facts = source.readCurrent(memberId, scope, definitionId, now);
        require(facts != null && facts.memberId == memberId && scope.equals(facts.rule.scope)
                && definitionId.equals(facts.rule.id) && now.equals(facts.observedAt), "CURRENT_AUTHORITY_REQUIRED");
        // Check before replay or secret content lookup. Closure/revocation must not be bypassed by old events.
        require(facts.authorized && facts.active && facts.placePermitted && facts.placeSafe
                && facts.placeOpen, "DISCOVERY_UNAVAILABLE");
        Rule rule = facts.rule;
        List<Event> events = canonicalEvents(facts.events, memberId, scope, now);
        // Receipt ID identifies one discovery per member / exact run or season / immutable definition.
        // Stable on appended late events, request-ID changes and transport duplicate delivery.
        List<List<List<Event>>> prefixes = new ArrayList<List<List<Event>>>();
        for (int i = 0; i < rule.steps.size(); i++) prefixes.add(new ArrayList<List<Event>>());
        int work = 0;
        for (Event event : events) {
            if (!event.verified || event.occurredAt.isBefore(rule.startsAt)
                    || !event.occurredAt.isBefore(rule.endsAt)) continue;
            // Descending update: one source receipt cannot satisfy two repeated steps.
            for (int i = rule.steps.size() - 1; i >= 0; i--) {
                if (!event.stepId.equals(rule.steps.get(i))) continue;
                List<List<Event>> candidates = i == 0
                        ? Collections.singletonList(Collections.<Event>emptyList()) : prefixes.get(i - 1);
                for (List<Event> previous : candidates) {
                    require(++work <= 1000000, "MATCH_WORK_BOUND_EXCEEDED");
                    if (!previous.isEmpty() && (event.occurredAt.isBefore(previous.get(previous.size() - 1).occurredAt)
                            || Duration.between(previous.get(0).occurredAt, event.occurredAt)
                                    .compareTo(rule.maxDuration) > 0)) continue;
                    List<Event> chosen = new ArrayList<Event>(previous);
                    chosen.add(event);
                    if (chosen.size() == rule.steps.size()) {
                        List<String> evidence = new ArrayList<String>();
                        for (Event match : chosen) evidence.add(match.receiptId);
                        return new Receipt("egg-" + digest(Arrays.asList(scope.key(), String.valueOf(memberId),
                                rule.id, String.valueOf(rule.version))), memberId, scope, rule.id, rule.version,
                                rule.fingerprint, event.occurredAt, evidence);
                    }
                    // Keep every non-dominated prefix: newer start alone is insufficient for late events.
                    List<List<Event>> frontier = prefixes.get(i);
                    boolean dominated = false;
                    for (List<Event> held : frontier) {
                        require(++work <= 1000000, "MATCH_WORK_BOUND_EXCEEDED");
                        if (dominates(held, chosen)) { dominated = true; break; }
                    }
                    if (!dominated) {
                        for (Iterator<List<Event>> iterator = frontier.iterator(); iterator.hasNext();) {
                            require(++work <= 1000000, "MATCH_WORK_BOUND_EXCEEDED");
                            if (dominates(chosen, iterator.next())) iterator.remove();
                        }
                        frontier.add(chosen);
                    }
                }
            }
        }
        return null; // No progress/count/step IDs/coordinates/answers/media are projected to the client.
    }

    private static boolean dominates(List<Event> left, List<Event> right) {
        return !left.get(0).occurredAt.isBefore(right.get(0).occurredAt)
                && !left.get(left.size() - 1).occurredAt.isAfter(right.get(right.size() - 1).occurredAt);
    }

    private static List<Event> canonicalEvents(List<Event> input, long memberId, Scope scope, Instant now) {
        require(input != null && input.size() <= 10000, "EVENT_HISTORY_BOUND_EXCEEDED");
        Map<String, Event> receipts = new HashMap<String, Event>();
        Map<Long, String> sequences = new HashMap<Long, String>();
        for (Event event : input) {
            require(event != null && event.memberId == memberId && scope.equals(event.scope), "EVENT_SCOPE_MISMATCH");
            require(!event.occurredAt.isAfter(now), "FUTURE_EVENT");
            Event duplicate = receipts.put(event.receiptId, event);
            require(duplicate == null || duplicate.fingerprint.equals(event.fingerprint), "EVENT_ID_CONFLICT");
            String previous = sequences.put(event.sequence, event.receiptId);
            require(previous == null || previous.equals(event.receiptId), "EVENT_SEQUENCE_CONFLICT");
        }
        List<Event> ordered = new ArrayList<Event>(receipts.values());
        Collections.sort(ordered, Comparator.comparingLong(e -> e.sequence));
        return ordered;
    }

    /** Server-only port. Implementations must use committed history; client clocks/GPS/fog are not proof. */
    public interface Source {
        Snapshot readCurrent(long memberId, Scope scope, String definitionId, Instant serverNow);
    }

    public static final class Scope {
        public final Kind kind;
        // THEME = topicId/releaseId/runId; MAP = boardId/rulesReleaseId/seasonId. Never activityId fallback.
        public final String ownerId, releaseId, occurrenceId;
        private Scope(Kind kind, String ownerId, String releaseId, String occurrenceId) {
            this.kind = kind; this.ownerId = token(ownerId); this.releaseId = token(releaseId);
            this.occurrenceId = token(occurrenceId);
        }
        public static Scope theme(String topicId, String releaseId, String runId) {
            return new Scope(Kind.THEME, topicId, releaseId, runId);
        }
        public static Scope map(String boardId, String rulesReleaseId, String seasonId) {
            return new Scope(Kind.MAP, boardId, rulesReleaseId, seasonId);
        }
        private String key() { return kind + ":" + ownerId + ":" + releaseId + ":" + occurrenceId; }
        @Override public boolean equals(Object other) { return other instanceof Scope && key().equals(((Scope) other).key()); }
        @Override public int hashCode() { return key().hashCode(); }
    }

    /** Immutable published definition. UTC instants are resolved at publication using explicit IANA zone. */
    public static final class Rule {
        public final Scope scope;
        public final String id, publicationZone, fingerprint;
        public final long version;
        public final List<String> steps;
        public final Instant startsAt, endsAt;
        public final Duration maxDuration;
        public Rule(Scope scope, String id, long version, List<String> steps, Instant startsAt,
                    Instant endsAt, Duration maxDuration, String publicationZone) {
            require(scope != null && version > 0 && steps != null && !steps.isEmpty() && steps.size() <= 32,
                    "RULE_REQUIRED");
            require(startsAt != null && endsAt != null && startsAt.isBefore(endsAt)
                    && maxDuration != null && !maxDuration.isNegative() && !maxDuration.isZero(), "WINDOW_REQUIRED");
            require(publicationZone != null, "PUBLICATION_ZONE_REQUIRED");
            ZoneId.of(publicationZone);
            this.scope = scope; this.id = token(id); this.version = version;
            List<String> copy = new ArrayList<String>();
            for (String step : steps) copy.add(token(step));
            this.steps = Collections.unmodifiableList(copy); this.startsAt = startsAt; this.endsAt = endsAt;
            this.maxDuration = maxDuration; this.publicationZone = publicationZone;
            List<String> values = new ArrayList<String>(Arrays.asList(scope.key(), id, String.valueOf(version),
                    startsAt.toString(), endsAt.toString(), maxDuration.toString(), publicationZone));
            values.addAll(copy); this.fingerprint = digest(values);
        }
    }

    /** No payload/answer/location fields: persisted source receipt is mapped to an approved step server-side. */
    public static final class Event {
        public final String receiptId, stepId;
        public final long memberId, sequence;
        public final Scope scope;
        public final Instant occurredAt;
        public final boolean verified;
        private final String fingerprint;
        public Event(String receiptId, long memberId, Scope scope, long sequence, String stepId,
                     Instant occurredAt, boolean verified) {
            require(memberId > 0 && scope != null && sequence > 0 && occurredAt != null, "EVENT_REQUIRED");
            this.receiptId = token(receiptId); this.memberId = memberId; this.scope = scope;
            this.sequence = sequence; this.stepId = token(stepId); this.occurredAt = occurredAt; this.verified = verified;
            this.fingerprint = digest(Arrays.asList(receiptId, String.valueOf(memberId), scope.key(),
                    String.valueOf(sequence), stepId, occurredAt.toString(), String.valueOf(verified)));
        }
    }

    public static final class Snapshot {
        public final long memberId;
        public final Rule rule;
        public final Instant observedAt;
        public final boolean authorized, active, placePermitted, placeSafe, placeOpen;
        public final List<Event> events;
        public Snapshot(long memberId, Rule rule, Instant observedAt, boolean authorized, boolean active,
                        boolean placePermitted, boolean placeSafe, boolean placeOpen, List<Event> events) {
            require(rule != null && observedAt != null && events != null, "SNAPSHOT_REQUIRED");
            this.memberId = memberId; this.rule = rule; this.observedAt = observedAt;
            this.authorized = authorized; this.active = active; this.placePermitted = placePermitted;
            this.placeSafe = placeSafe; this.placeOpen = placeOpen;
            this.events = Collections.unmodifiableList(new ArrayList<Event>(events));
        }
    }

    /** Internal evaluated evidence, NOT an award and NOT production attestation. Do not serialize this object. */
    public static final class Receipt {
        public final String receiptId, definitionId, definitionFingerprint;
        public final String kind = "STORY_UNLOCK";
        public final String provenance = "ISOLATED_EVALUATION";
        public final long memberId, definitionVersion;
        public final Scope scope;
        public final Instant discoveredAt;
        private final List<String> sourceReceiptIds;
        private Receipt(String receiptId, long memberId, Scope scope, String definitionId, long version,
                        String fingerprint, Instant discoveredAt, List<String> evidence) {
            this.receiptId = receiptId; this.memberId = memberId; this.scope = scope;
            this.definitionId = definitionId; this.definitionVersion = version;
            this.definitionFingerprint = fingerprint; this.discoveredAt = discoveredAt;
            this.sourceReceiptIds = Collections.unmodifiableList(new ArrayList<String>(evidence));
        }
        /** Client allowlist, intentionally omits evidence, definition IDs, hash and unlock content. */
        public Map<String, String> publicView() {
            Map<String, String> result = new LinkedHashMap<String, String>();
            result.put("receiptId", receiptId); result.put("kind", kind);
            result.put("discoveredAt", discoveredAt.toString());
            return Collections.unmodifiableMap(result);
        }
    }

    private static String token(String value) {
        require(value != null && value.matches("[A-Za-z0-9._-]{1,128}"), "INVALID_IDENTIFIER"); return value;
    }
    private static void require(boolean valid, String code) { if (!valid) throw new IllegalStateException(code); }
    private static String digest(List<String> fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII)); digest.update(bytes);
            }
            StringBuilder value = new StringBuilder();
            for (byte b : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", b & 255));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
