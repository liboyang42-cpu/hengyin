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
        if (!missing.isEmpty()) return Result.notComputable(missing);
        long start;
        try {
            start = Math.subtractExact(asOf, parameter.horizonMillis);
        } catch (ArithmeticException ex) {
            return Result.notComputable(List.of(HORIZON_MILLIS));
        }
        List<EventRow> events;
        try {
            events = source.events(start, asOf);
        } catch (FairnessReadException ex) {
            return Result.notComputable(List.of(ex.missing));
        } catch (RuntimeException ex) {
            return Result.notComputable(List.of("city_fairness_event"));
        }
        if (events == null) return Result.notComputable(List.of("city_fairness_event"));
        return compute(asOf, start, parameter, events);
    }

    private static List<String> parameterGaps(ParameterRow parameter) {
        List<String> missing = new ArrayList<>();
        if (parameter == null) {
            missing.add(DELTA_MILLIS);
            missing.add(HORIZON_MILLIS);
            missing.add(LOWER_EFFECTIVE_EVENTS);
            missing.add(PARAMETER_APPROVAL);
            missing.add(PARAMETER_SOURCE_REF);
            return missing;
        }
        if (!"APPROVED".equals(parameter.state)) missing.add(PARAMETER_APPROVAL);
        if (parameter.sourceRef == null || parameter.sourceRef.isBlank()) missing.add(PARAMETER_SOURCE_REF);
        if (parameter.deltaMillis == null || parameter.deltaMillis <= 0) missing.add(DELTA_MILLIS);
        if (parameter.horizonMillis == null || parameter.horizonMillis <= 0) missing.add(HORIZON_MILLIS);
        if (parameter.lowerEffectiveEvents == null || parameter.lowerEffectiveEvents <= 0) missing.add(LOWER_EFFECTIVE_EVENTS);
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
        if (!missing.isEmpty()) return Result.notComputable(missing);

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
            if (GAIN.equals(event.kind) && event.gainAmount == null) missing.add("gainAmount:" + event.eventId);
            if (GAIN.equals(event.kind) && event.gainAmount != null && event.gainAmount < 0) {
                missing.add("negative-gain:" + event.eventId);
            }
            if (MERCHANT_VERIFICATION.equals(event.kind) && (event.verificationMillis == null || event.verificationMillis < 0)) {
                missing.add("verificationMillis:" + event.eventId);
            }
            outings.computeIfAbsent(event.outingId, key -> new ArrayList<>()).add(event);
        }
        if (!missing.isEmpty()) return Result.notComputable(missing);

        Map<GroupKey, Group> groups = new TreeMap<>();
        Map<Long, List<Outing>> byActor = new LinkedHashMap<>();
        List<OutingClass> classes = new ArrayList<>();
        for (Map.Entry<String, List<EventRow>> entry : outings.entrySet()) {
            Outing outing = outing(entry.getKey(), entry.getValue(), parameter.lowerEffectiveEvents, missing);
            if (outing == null) continue;
            classes.add(new OutingClass(outing.id, outing.actorId,
                    outing.effective ? "MATURE" : "IMMATURE",
                    outing.effective ? "effective" : "below-lower-bound"));
            try {
                if (outing.effective || counted(outing)) groups.computeIfAbsent(outing.key, Group::new).add(outing);
            } catch (FairnessReadException ex) {
                return Result.notComputable(List.of(ex.missing));
            }
            if (outing.effective) byActor.computeIfAbsent(outing.actorId, key -> new ArrayList<>()).add(outing);
        }
        if (!missing.isEmpty()) return Result.notComputable(missing);

        List<SecondOuting> seconds = new ArrayList<>();
        for (Map.Entry<Long, List<Outing>> entry : byActor.entrySet()) {
            List<Outing> ordered = new ArrayList<>(entry.getValue());
            ordered.sort(Comparator.comparingLong(item -> item.anchor));
            Long previous = null;
            int counted = 0;
            for (Outing outing : ordered) {
                if (previous != null) {
                    long due;
                    try {
                        due = Math.addExact(previous, parameter.deltaMillis);
                    } catch (ArithmeticException ex) {
                        return Result.notComputable(List.of(DELTA_MILLIS));
                    }
                    if (outing.anchor < due) {
                        replaceClass(classes, outing.id, "IMMATURE", "inside-delta");
                        continue;
                    }
                }
                previous = outing.anchor;
                counted++;
                if (counted == 2) seconds.add(new SecondOuting(entry.getKey(), outing.id, outing.anchor, outing.key));
            }
        }
        seconds.sort(Comparator.comparingLong(item -> item.actorId));
        classes.sort(Comparator.comparing(item -> item.outingId));
        List<Group> listed = new ArrayList<>(groups.values());
        return new Result(COMPUTABLE, List.of(), listed, seconds, classes);
    }

    private static String dedupeKey(EventRow event) {
        String source = event.source == null ? "" : event.source;
        return source + "\0" + event.eventId;
    }

    private static void replaceClass(List<OutingClass> classes, String outingId, String maturity, String reason) {
        for (int i = 0; i < classes.size(); i++) {
            OutingClass current = classes.get(i);
            if (outingId.equals(current.outingId)) {
                classes.set(i, new OutingClass(outingId, current.actorId, maturity, reason));
                return;
            }
        }
    }

    private static Outing outing(String id, List<EventRow> events, int lower, List<String> missing) {
        EventRow first = events.get(0);
        Long joinTime = first.joinTime;
        Integer teamSize = first.teamSize;
        Integer actionCost = first.actionCost;
        String newcomer = newcomer(first.newcomer);
        Long actorId = first.actorId;
        int effectiveEvents = 0;
        long anchor = Long.MAX_VALUE;
        for (EventRow event : events) {
            if (!Objects.equals(joinTime, event.joinTime) || !Objects.equals(teamSize, event.teamSize)
                    || !Objects.equals(actionCost, event.actionCost) || !Objects.equals(newcomer, newcomer(event.newcomer))
                    || !Objects.equals(actorId, event.actorId)) {
                missing.add("outing-attribute-conflict:" + id);
                return null;
            }
            if (EFFECTIVE_TASK.equals(event.kind) || EFFECTIVE_SERVICE.equals(event.kind)) {
                effectiveEvents++;
                anchor = Math.min(anchor, event.occurredAt);
            }
        }
        boolean effective = effectiveEvents >= lower;
        if (!effective) anchor = events.stream().mapToLong(event -> event.occurredAt).min().orElse(0L);
        return new Outing(id, actorId, new GroupKey(joinTime, teamSize, actionCost, newcomer), anchor, effective, events);
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
                && Objects.equals(left.verificationMillis, right.verificationMillis);
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
        public Integer lowerEffectiveEvents;
        public Integer maxEvents;
        public Integer pageSize;
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
    }

    public static final class Result {
        public final String status;
        public final List<String> missing;
        public final List<Group> groups;
        public final List<SecondOuting> secondOutings;
        public final List<OutingClass> outingClasses;

        Result(String status, List<String> missing, List<Group> groups, List<SecondOuting> secondOutings,
               List<OutingClass> outingClasses) {
            this.status = status;
            this.missing = List.copyOf(missing);
            this.groups = List.copyOf(groups);
            this.secondOutings = List.copyOf(secondOutings);
            this.outingClasses = List.copyOf(outingClasses);
        }

        static Result notComputable(List<String> missing) {
            List<String> sorted = new ArrayList<>(missing);
            sorted.sort(String::compareTo);
            return new Result(NOT_COMPUTABLE, sorted.stream().distinct().toList(), List.of(), List.of(), List.of());
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

        public OutingClass(String outingId, long actorId, String maturity, String reason) {
            this.outingId = outingId;
            this.actorId = actorId;
            this.maturity = maturity;
            this.reason = reason;
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
                        if (!outing.effective) break;
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
        final long anchor;
        final boolean effective;
        final List<EventRow> events;

        Outing(String id, long actorId, GroupKey key, long anchor, boolean effective, List<EventRow> events) {
            this.id = id;
            this.actorId = actorId;
            this.key = key;
            this.anchor = anchor;
            this.effective = effective;
            this.events = events;
        }
    }
}
