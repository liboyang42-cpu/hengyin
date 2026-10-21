package com.chengyinhub.business.citygame.analytics.fairness;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static com.chengyinhub.business.citygame.analytics.fairness.FairnessFactContract.*;

/**
 * Read-only fairness and second-effective-outing report.
 * Not a Spring bean. Unapproved parameters or a broken read produce NOT_COMPUTABLE and no filled-in rates.
 */
public final class CityFairnessReader {
    public static final String COMPUTABLE = "COMPUTABLE";
    public static final String NOT_COMPUTABLE = "NOT_COMPUTABLE";

    public Result read(long asOf, Source source) {
        List<String> missing = new ArrayList<>();
        if (source == null) missing.add("fairness-source");
        if (asOf < 0) missing.add("asOf");
        if (source != null && source.unavailableStreams() != null) {
            for (String stream : source.unavailableStreams()) {
                if (stream != null && !stream.isBlank()) missing.add("stream:" + stream);
            }
        }
        ParameterRow parameter = null;
        if (source != null) {
            try {
                parameter = source.parameters();
            } catch (FairnessReadException ex) {
                missing.add(ex.missing);
            } catch (RuntimeException ex) {
                missing.add("city_fairness_parameter");
            }
        }
        missing.addAll(parameterGaps(parameter));
        if (!missing.isEmpty()) return Result.notComputable(asOf, missing, version(parameter));
        long start;
        try {
            start = Math.subtractExact(asOf, parameter.horizonMillis);
        } catch (ArithmeticException ex) {
            return Result.notComputable(asOf, List.of(HORIZON_MILLIS), version(parameter));
        }
        List<EventRow> events;
        try {
            events = source.events(start, asOf);
        } catch (FairnessReadException ex) {
            return Result.notComputable(asOf, List.of(ex.missing), version(parameter));
        } catch (RuntimeException ex) {
            return Result.notComputable(asOf, List.of("city_fairness_event"), version(parameter));
        }
        if (events == null) return Result.notComputable(asOf, List.of("city_fairness_event"), version(parameter));
        return compute(asOf, start, parameter, events);
    }

    private static List<String> parameterGaps(ParameterRow parameter) {
        List<String> missing = new ArrayList<>();
        if (parameter == null) {
            missing.add(DELTA_MILLIS);
            missing.add(HORIZON_MILLIS);
            missing.add(GRACE_MILLIS);
            missing.add(METRIC_VERSION);
            missing.add("boardId");
            missing.add("regionId");
            missing.add("seasonId");
            missing.add("rulesVersion");
            missing.add(PARAMETER_APPROVAL);
            missing.add(PARAMETER_SOURCE_REF);
            return missing;
        }
        if (!"APPROVED".equals(parameter.state)) missing.add(PARAMETER_APPROVAL);
        if (parameter.sourceRef == null || parameter.sourceRef.isBlank()) missing.add(PARAMETER_SOURCE_REF);
        if (parameter.deltaMillis == null || parameter.deltaMillis <= 0) missing.add(DELTA_MILLIS);
        if (parameter.horizonMillis == null || parameter.horizonMillis <= 0) missing.add(HORIZON_MILLIS);
        if (parameter.graceMillis == null || parameter.graceMillis <= 0) missing.add(GRACE_MILLIS);
        if (parameter.metricVersion == null || parameter.metricVersion.isBlank()) missing.add(METRIC_VERSION);
        if (parameter.boardId == null || parameter.boardId.isBlank()) missing.add("boardId");
        if (parameter.regionId == null || parameter.regionId.isBlank()) missing.add("regionId");
        if (parameter.seasonId == null || parameter.seasonId.isBlank()) missing.add("seasonId");
        if (parameter.rulesVersion == null || parameter.rulesVersion.isBlank()) missing.add("rulesVersion");
        return missing;
    }

    private static Result compute(long asOf, long start, ParameterRow parameter, List<EventRow> events) {
        List<String> missing = new ArrayList<>();
        Map<String, EventRow> byId = new LinkedHashMap<>();
        for (EventRow event : events) {
            if (event == null) {
                missing.add("event");
                continue;
            }
            if (event.occurredAt == null || event.occurredAt <= start || event.occurredAt > asOf) continue;
            if (ignored(event.kind)) continue;
            if (event.eventId == null || event.eventId.isBlank()) {
                missing.add("eventId");
                continue;
            }
            EventRow prior = byId.putIfAbsent(dedupeKey(event), event);
            if (prior != null && !same(prior, event)) missing.add("event-conflict:" + event.eventId);
        }
        if (!missing.isEmpty()) return Result.notComputable(asOf, missing, version(parameter));

        Map<String, List<EventRow>> outings = new LinkedHashMap<>();
        for (EventRow event : byId.values()) {
            if (!known(event.kind)) {
                missing.add("kind:" + event.eventId);
                continue;
            }
            if (event.actorId == null || event.actorId <= 0) missing.add("actorId:" + event.eventId);
            if (event.outingId == null || event.outingId.isBlank()) {
                missing.add("outingId:" + event.eventId);
                continue;
            }
            if (event.teamSize != null && event.teamSize < 0) missing.add("teamSize:" + event.eventId);
            if (event.actionCost != null && event.actionCost < 0) missing.add("actionCost:" + event.eventId);
            if (!validNewcomer(event.newcomer)) missing.add("newcomer:" + event.eventId);
            if (!sameScope(parameter, event)) missing.add("scope-mix:" + event.eventId);
            if (GAIN.equals(event.kind) && event.gainAmount == null) missing.add("gainAmount:" + event.eventId);
            if (GAIN.equals(event.kind) && event.gainAmount != null && event.gainAmount < 0) {
                missing.add("negative-gain:" + event.eventId);
            }
            if (MERCHANT_VERIFICATION.equals(event.kind) && (event.verificationMillis == null || event.verificationMillis < 0)) {
                missing.add("verificationMillis:" + event.eventId);
            }
            outings.computeIfAbsent(event.outingId, key -> new ArrayList<>()).add(event);
        }
        if (!missing.isEmpty()) return Result.notComputable(asOf, missing, version(parameter));

        Map<GroupKey, Group> groups = new TreeMap<>();
        Map<Long, List<Outing>> byActor = new LinkedHashMap<>();
        List<OutingClass> classes = new ArrayList<>();
        List<LateEvent> late = new ArrayList<>();
        for (Map.Entry<String, List<EventRow>> entry : outings.entrySet()) {
            Outing outing = outing(entry.getKey(), entry.getValue(), asOf, parameter.graceMillis, missing, late);
            if (outing == null) continue;
            classes.add(new OutingClass(outing.id, outing.actorId, outing.maturity, outing.reason, outing.t0));
            try {
                if (outing.t0 != null || counted(outing)) groups.computeIfAbsent(outing.key, Group::new).add(outing);
            } catch (FairnessReadException ex) {
                return Result.notComputable(asOf, List.of(ex.missing), version(parameter));
            }
            if (outing.closed) byActor.computeIfAbsent(outing.actorId, key -> new ArrayList<>()).add(outing);
        }
        if (!missing.isEmpty()) return Result.notComputable(asOf, missing, version(parameter));

        List<SecondOuting> seconds = new ArrayList<>();
        for (Map.Entry<Long, List<Outing>> entry : byActor.entrySet()) {
            List<Outing> ordered = new ArrayList<>(entry.getValue());
            ordered.sort(Comparator.comparingLong(item -> item.t0));
            Long previous = null;
            int counted = 0;
            for (Outing outing : ordered) {
                if (previous != null) {
                    long due;
                    try {
                        due = Math.addExact(previous, parameter.deltaMillis);
                    } catch (ArithmeticException ex) {
                        return Result.notComputable(asOf, List.of(DELTA_MILLIS), version(parameter));
                    }
                    if (outing.t0 < due) {
                        replaceClass(classes, outing.id, "IMMATURE", "inside-delta");
                        continue;
                    }
                }
                previous = outing.t0;
                counted++;
                if (counted == 2) seconds.add(new SecondOuting(entry.getKey(), outing.id, outing.t0, outing.key));
            }
        }
        seconds.sort(Comparator.comparingLong(item -> item.actorId));
        classes.sort(Comparator.comparing(item -> item.outingId));
        late.sort(Comparator.comparing((LateEvent item) -> item.outingId).thenComparing(item -> item.eventId));
        int denominator = 0;
        int mature = 0;
        for (OutingClass item : classes) {
            if (item.t0 == null || "inside-grace".equals(item.reason)) continue;
            denominator++;
            if ("MATURE".equals(item.maturity)) mature++;
        }
        Integer matureCount = denominator == 0 ? null : mature;
        Integer denominatorCount = denominator == 0 ? null : denominator;
        List<Group> listed = new ArrayList<>(groups.values());
        return new Result(COMPUTABLE, List.of(), listed, seconds, classes, late, asOf, parameter.metricVersion,
                matureCount, denominatorCount);
    }

    private static String version(ParameterRow parameter) {
        if (parameter == null || parameter.metricVersion == null || parameter.metricVersion.isBlank()) return null;
        return parameter.metricVersion;
    }

    private static String dedupeKey(EventRow event) {
        String source = event.source == null ? "" : event.source;
        return source + "\0" + event.eventId;
    }

    private static void replaceClass(List<OutingClass> classes, String outingId, String maturity, String reason) {
        for (int i = 0; i < classes.size(); i++) {
            OutingClass current = classes.get(i);
            if (outingId.equals(current.outingId)) {
                classes.set(i, new OutingClass(outingId, current.actorId, maturity, reason, current.t0));
                return;
            }
        }
    }

    private static Outing outing(String id, List<EventRow> events, long asOf, long grace, List<String> missing,
                                 List<LateEvent> late) {
        EventRow first = events.get(0);
        Long joinTime = first.joinTime;
        Integer teamSize = first.teamSize;
        Integer actionCost = first.actionCost;
        String newcomer = newcomer(first.newcomer);
        Long actorId = first.actorId;
        Long t0 = null;
        for (EventRow event : events) {
            if (!Objects.equals(joinTime, event.joinTime) || !Objects.equals(teamSize, event.teamSize)
                    || !Objects.equals(actionCost, event.actionCost) || !Objects.equals(newcomer, newcomer(event.newcomer))
                    || !Objects.equals(actorId, event.actorId)) {
                missing.add("outing-attribute-conflict:" + id);
                return null;
            }
            if (EFFECTIVE_TASK.equals(event.kind) || EFFECTIVE_SERVICE.equals(event.kind)) {
                t0 = t0 == null ? event.occurredAt : Math.min(t0, event.occurredAt);
            }
        }
        Long cutoff = null;
        if (t0 != null) {
            try {
                cutoff = Math.addExact(t0, grace);
            } catch (ArithmeticException ex) {
                missing.add(GRACE_MILLIS);
                return null;
            }
        }
        List<EventRow> countedEvents = new ArrayList<>();
        for (EventRow event : events) {
            if (cutoff != null && event.occurredAt > cutoff) {
                late.add(new LateEvent(id, event.eventId, event.occurredAt));
            } else {
                countedEvents.add(event);
            }
        }
        boolean closed = cutoff != null && asOf >= cutoff;
        String maturity = closed ? "MATURE" : "IMMATURE";
        String reason = t0 == null ? "no-t0" : closed ? "effective" : "inside-grace";
        return new Outing(id, actorId, new GroupKey(joinTime, teamSize, actionCost, newcomer), t0, closed, maturity,
                reason, countedEvents);
    }

    private static boolean sameScope(ParameterRow parameter, EventRow event) {
        return Objects.equals(parameter.boardId, event.boardId)
                && Objects.equals(parameter.regionId, event.regionId)
                && Objects.equals(parameter.seasonId, event.seasonId)
                && Objects.equals(parameter.rulesVersion, event.rulesVersion);
    }

    private static boolean counted(Outing outing) {
        for (EventRow event : outing.events) {
            switch (event.kind) {
                case GOAL_COMPLETED, ZERO_POINT_BLOCK, CHALLENGE_COMPENSATION_EXIT,
                        GPS_FALSE_REJECTION, SAFETY_TICKET, MERCHANT_VERIFICATION -> { return true; }
                default -> { }
            }
        }
        return false;
    }

    private static boolean ignored(String kind) {
        return APP_REOPEN.equals(kind) || RECONNECT.equals(kind) || REGISTRATION_REPEAT.equals(kind);
    }

    private static boolean known(String kind) {
        return EFFECTIVE_TASK.equals(kind) || EFFECTIVE_SERVICE.equals(kind) || GOAL_COMPLETED.equals(kind)
                || GAIN.equals(kind) || ZERO_POINT_BLOCK.equals(kind) || CHALLENGE_COMPENSATION_EXIT.equals(kind)
                || GPS_FALSE_REJECTION.equals(kind) || SAFETY_TICKET.equals(kind) || MERCHANT_VERIFICATION.equals(kind);
    }

    private static boolean validNewcomer(String value) {
        return value == null || value.isBlank() || NEWCOMER.equals(value) || RETURNING.equals(value);
    }

    private static String newcomer(String value) {
        if (value == null || value.isBlank()) return null;
        return value;
    }

    static boolean same(EventRow left, EventRow right) {
        return Objects.equals(left.source, right.source)
                && Objects.equals(left.outingId, right.outingId) && Objects.equals(left.kind, right.kind)
                && Objects.equals(left.actorId, right.actorId) && Objects.equals(left.occurredAt, right.occurredAt)
                && Objects.equals(left.joinTime, right.joinTime) && Objects.equals(left.teamSize, right.teamSize)
                && Objects.equals(left.actionCost, right.actionCost) && Objects.equals(left.newcomer, right.newcomer)
                && Objects.equals(left.gainAmount, right.gainAmount)
                && Objects.equals(left.verificationMillis, right.verificationMillis)
                && Objects.equals(left.boardId, right.boardId)
                && Objects.equals(left.regionId, right.regionId)
                && Objects.equals(left.seasonId, right.seasonId)
                && Objects.equals(left.rulesVersion, right.rulesVersion);
    }

    public static final class FairnessReadException extends RuntimeException {
        public final String missing;
        public FairnessReadException(String missing) {
            super(missing);
            this.missing = missing;
        }
    }

    public interface Source {
        ParameterRow parameters();
        List<EventRow> events(long windowStartExclusive, long asOfInclusive);
        List<String> unavailableStreams();
    }

    public static final class ParameterRow {
        public String state;
        public String sourceRef;
        public Long deltaMillis;
        public Long horizonMillis;
        public Long graceMillis;
        public Integer maxEvents;
        public Integer pageSize;
        public String boardId;
        public String regionId;
        public String seasonId;
        public String rulesVersion;
        public String metricVersion;
    }

    public static final class EventRow {
        public String source;
        public String eventId;
        public String outingId;
        public String kind;
        public Long actorId;
        public Long occurredAt;
        public Long joinTime;
        public Integer teamSize;
        public Integer actionCost;
        public String newcomer;
        public Long gainAmount;
        public Long verificationMillis;
        public String boardId;
        public String regionId;
        public String seasonId;
        public String rulesVersion;
    }

    public static final class Result {
        public final String status;
        public final List<String> missing;
        public final List<Group> groups;
        public final List<SecondOuting> secondOutings;
        public final List<OutingClass> outingClasses;
        public final List<LateEvent> lateEvents;
        public final long asOf;
        public final String metricVersion;
        public final Integer matureCount;
        public final Integer denominator;

        Result(String status, List<String> missing, List<Group> groups, List<SecondOuting> secondOutings,
               List<OutingClass> outingClasses, List<LateEvent> lateEvents, long asOf, String metricVersion,
               Integer matureCount, Integer denominator) {
            this.status = status;
            this.missing = List.copyOf(missing);
            this.groups = List.copyOf(groups);
            this.secondOutings = List.copyOf(secondOutings);
            this.outingClasses = List.copyOf(outingClasses);
            this.lateEvents = List.copyOf(lateEvents);
            this.asOf = asOf;
            this.metricVersion = metricVersion;
            this.matureCount = matureCount;
            this.denominator = denominator;
        }

        static Result notComputable(long asOf, List<String> missing) {
            return notComputable(asOf, missing, null);
        }

        static Result notComputable(long asOf, List<String> missing, String metricVersion) {
            List<String> sorted = new ArrayList<>(missing);
            sorted.sort(String::compareTo);
            String version = metricVersion == null || metricVersion.isBlank() ? null : metricVersion;
            return new Result(NOT_COMPUTABLE, sorted.stream().distinct().toList(), List.of(), List.of(), List.of(),
                    List.of(), asOf, version, null, null);
        }

        public boolean computable() { return COMPUTABLE.equals(status); }

        public String maturity() {
            if (!computable()) return "MISSING";
            boolean mature = false;
            boolean immature = false;
            for (OutingClass item : outingClasses) {
                if ("IMMATURE".equals(item.maturity)) immature = true;
                if ("MATURE".equals(item.maturity)) mature = true;
            }
            if (mature && immature) return "MIXED";
            if (immature) return "IMMATURE";
            if (mature) return "MATURE";
            return "EMPTY";
        }
    }

    public static final class OutingClass {
        public final String outingId;
        public final long actorId;
        public final String maturity;
        public final String reason;
        public final Long t0;

        public OutingClass(String outingId, long actorId, String maturity, String reason, Long t0) {
            this.outingId = outingId;
            this.actorId = actorId;
            this.maturity = maturity;
            this.reason = reason;
            this.t0 = t0;
        }
    }

    public static final class LateEvent {
        public final String outingId;
        public final String eventId;
        public final long occurredAt;

        public LateEvent(String outingId, String eventId, long occurredAt) {
            this.outingId = outingId;
            this.eventId = eventId;
            this.occurredAt = occurredAt;
        }
    }

    public static final class Group {
        public final Long joinTime;
        public final Integer teamSize;
        public final Integer actionCost;
        public final String newcomer;
        public int goalCompletions;
        public long effectiveGain;
        public long topActorGain;
        public long totalGain;
        public int zeroPointBlocks;
        public int challengeCompensationExits;
        public int gpsFalseRejections;
        public int safetyTickets;
        public int merchantVerifications;
        public long merchantVerificationMillis;
        private final Map<Long, Long> gainByActor = new TreeMap<>();

        Group(GroupKey key) {
            this.joinTime = key.joinTime;
            this.teamSize = key.teamSize;
            this.actionCost = key.actionCost;
            this.newcomer = key.newcomer;
        }

        void add(Outing outing) {
            for (EventRow event : outing.events) {
                switch (event.kind) {
                    case GOAL_COMPLETED -> goalCompletions++;
                    case GAIN -> {
                        if (outing.t0 == null) break;
                        try {
                            effectiveGain = Math.addExact(effectiveGain, event.gainAmount);
                            long prior = gainByActor.getOrDefault(event.actorId, 0L);
                            gainByActor.put(event.actorId, Math.addExact(prior, event.gainAmount));
                        } catch (ArithmeticException ex) {
                            throw new FairnessReadException("gain-overflow");
                        }
                    }
                    case ZERO_POINT_BLOCK -> zeroPointBlocks++;
                    case CHALLENGE_COMPENSATION_EXIT -> challengeCompensationExits++;
                    case GPS_FALSE_REJECTION -> gpsFalseRejections++;
                    case SAFETY_TICKET -> safetyTickets++;
                    case MERCHANT_VERIFICATION -> {
                        merchantVerifications++;
                        try {
                            merchantVerificationMillis = Math.addExact(merchantVerificationMillis, event.verificationMillis);
                        } catch (ArithmeticException ex) {
                            throw new FairnessReadException("verification-overflow");
                        }
                    }
                    default -> { }
                }
            }
            totalGain = effectiveGain;
            topActorGain = gainByActor.values().stream().mapToLong(Long::longValue).max().orElse(0L);
        }

        public boolean concentrationReported() { return totalGain > 0; }
    }

    public static final class SecondOuting {
        public final long actorId;
        public final String outingId;
        public final long anchorMillis;
        public final Long joinTime;
        public final Integer teamSize;
        public final Integer actionCost;
        public final String newcomer;

        SecondOuting(long actorId, String outingId, long anchorMillis, GroupKey key) {
            this(actorId, outingId, anchorMillis, key.joinTime, key.teamSize, key.actionCost, key.newcomer);
        }

        SecondOuting(long actorId, String outingId, long anchorMillis, Long joinTime, Integer teamSize,
                     Integer actionCost, String newcomer) {
            this.actorId = actorId;
            this.outingId = outingId;
            this.anchorMillis = anchorMillis;
            this.joinTime = joinTime;
            this.teamSize = teamSize;
            this.actionCost = actionCost;
            this.newcomer = newcomer;
        }
    }

    static Group restoreGroup(Long joinTime, Integer teamSize, Integer actionCost, String newcomer) {
        return new Group(new GroupKey(joinTime, teamSize, actionCost, newcomer));
    }

    private record GroupKey(Long joinTime, Integer teamSize, Integer actionCost, String newcomer)
            implements Comparable<GroupKey> {
        public int compareTo(GroupKey other) {
            int join = compareNullable(joinTime, other.joinTime);
            if (join != 0) return join;
            int team = compareNullable(teamSize, other.teamSize);
            if (team != 0) return team;
            int cost = compareNullable(actionCost, other.actionCost);
            if (cost != 0) return cost;
            if (newcomer == null && other.newcomer == null) return 0;
            if (newcomer == null) return -1;
            if (other.newcomer == null) return 1;
            return newcomer.compareTo(other.newcomer);
        }

        private static <T extends Comparable<T>> int compareNullable(T left, T right) {
            if (left == null && right == null) return 0;
            if (left == null) return -1;
            if (right == null) return 1;
            return left.compareTo(right);
        }
    }

    private static final class Outing {
        final String id;
        final long actorId;
        final GroupKey key;
        final Long t0;
        final boolean closed;
        final String maturity;
        final String reason;
        final List<EventRow> events;

        Outing(String id, long actorId, GroupKey key, Long t0, boolean closed, String maturity, String reason,
               List<EventRow> events) {
            this.id = id;
            this.actorId = actorId;
            this.key = key;
            this.t0 = t0;
            this.closed = closed;
            this.maturity = maturity;
            this.reason = reason;
            this.events = events;
        }
    }
}
