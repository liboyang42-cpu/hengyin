package com.chengyinhub.business.citygame.analytics.fairness;

import com.chengyinhub.business.citygame.analytics.fairness.contract.FairnessFieldContract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static com.chengyinhub.business.citygame.analytics.fairness.FairnessFactContract.*;

/**
 * Member cohort for one approved scope.
 * t0 is the member's first confirmed valid completion. D is members with t0+H+L &lt;= asOf,
 * excluding test and confirm-cheat. N is members in D with another valid outing in (t0, t0+H] at least Δ later.
 * A fact with knownAt after asOf is invisible. Unset config stays unset.
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
        List<EventRow> events;
        try {
            events = source.events(asOf);
        } catch (FairnessReadException ex) {
            return Result.notComputable(asOf, List.of(ex.missing), version(parameter));
        } catch (RuntimeException ex) {
            return Result.notComputable(asOf, List.of("city_fairness_event"), version(parameter));
        }
        if (events == null) return Result.notComputable(asOf, List.of("city_fairness_event"), version(parameter));
        return compute(asOf, parameter, events);
    }

    private static List<String> parameterGaps(ParameterRow parameter) {
        List<String> missing = new ArrayList<>();
        if (parameter == null) {
            missing.add(DELTA_MILLIS);
            missing.add(HORIZON_MILLIS);
            missing.add(GRACE_MILLIS);
            missing.add(MERGE_WINDOW_MILLIS);
            missing.add(STATE_RULE);
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
        if (parameter.mergeWindowMillis == null || parameter.mergeWindowMillis <= 0) missing.add(MERGE_WINDOW_MILLIS);
        if (!FairnessFieldContract.STATE_RULE.equals(parameter.stateRule)) missing.add(STATE_RULE);
        if (parameter.metricVersion == null || parameter.metricVersion.isBlank()) missing.add(METRIC_VERSION);
        if (parameter.boardId == null || parameter.boardId.isBlank()) missing.add("boardId");
        if (parameter.regionId == null || parameter.regionId.isBlank()) missing.add("regionId");
        if (parameter.seasonId == null || parameter.seasonId.isBlank()) missing.add("seasonId");
        if (parameter.rulesVersion == null || parameter.rulesVersion.isBlank()) missing.add("rulesVersion");
        return missing;
    }

    private static Result compute(long asOf, ParameterRow parameter, List<EventRow> events) {
        List<String> missing = new ArrayList<>();
        Map<String, EventRow> byId = new LinkedHashMap<>();
        for (EventRow event : events) {
            if (event == null) {
                missing.add("event");
                continue;
            }
            if (event.eventId == null || event.eventId.isBlank()) {
                missing.add("eventId");
                continue;
            }
            if (event.knownAt == null || event.occurredAt == null || event.knownAt < 0 || event.occurredAt < 0
                    || event.knownAt < event.occurredAt) {
                missing.add("knownAt:" + event.eventId);
                continue;
            }
            if (event.knownAt > asOf || event.occurredAt > asOf) continue;
            if (ignored(event.kind)) continue;
            EventRow prior = byId.putIfAbsent(dedupeKey(event), event);
            if (prior != null && !same(prior, event)) missing.add("event-conflict:" + event.eventId);
        }
        if (!missing.isEmpty()) return Result.notComputable(asOf, missing, version(parameter));

        long factRevision = Long.MIN_VALUE;
        boolean anyFact = false;
        for (EventRow event : byId.values()) {
            anyFact = true;
            factRevision = Math.max(factRevision, event.knownAt);
            if (!known(event.kind)) {
                missing.add("kind:" + event.eventId);
                continue;
            }
            if (event.actorId == null || event.actorId <= 0) missing.add("actorId:" + event.eventId);
            if (needsOuting(event.kind) && (event.outingId == null || event.outingId.isBlank())) {
                missing.add("outingId:" + event.eventId);
            }
            if (REVOKE.equals(event.kind)
                    && (event.subjectSource == null || event.subjectSource.isBlank()
                    || event.subjectEventId == null || event.subjectEventId.isBlank())) {
                missing.add("subjectEventId:" + event.eventId);
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
        }
        if (!missing.isEmpty()) return Result.notComputable(asOf, missing, version(parameter));

        List<EventRow> revokes = new ArrayList<>();
        for (EventRow event : byId.values()) {
            if (REVOKE.equals(event.kind)) revokes.add(event);
        }
        for (EventRow revoke : revokes) {
            byId.remove(revoke.subjectSource + "\0" + revoke.subjectEventId);
            byId.remove(dedupeKey(revoke));
        }

        Map<Long, ActorFacts> actors = new LinkedHashMap<>();
        Map<String, Long> outingOwner = new LinkedHashMap<>();
        for (EventRow event : byId.values()) {
            if (REVOKE.equals(event.kind)) continue;
            ActorFacts actor = actors.computeIfAbsent(event.actorId, ActorFacts::new);
            if (CONFIRM_CHEAT.equals(event.kind)) {
                actor.cheat = true;
                continue;
            }
            if (TEST_MEMBER.equals(event.kind)) {
                actor.test = true;
                continue;
            }
            if (event.outingId == null || event.outingId.isBlank()) continue;
            Long owner = outingOwner.putIfAbsent(event.outingId, event.actorId);
            if (owner != null && !owner.equals(event.actorId)) {
                missing.add("outing-actor:" + event.outingId);
                continue;
            }
            actor.outings.computeIfAbsent(event.outingId, key -> new ArrayList<>()).add(event);
        }
        if (!missing.isEmpty()) return Result.notComputable(asOf, missing, version(parameter));

        Map<GroupKey, Group> groups = new TreeMap<>();
        List<OutingClass> classes = new ArrayList<>();
        List<SecondOuting> seconds = new ArrayList<>();
        List<LateEvent> late = new ArrayList<>();
        try {
            for (ActorFacts actor : actors.values()) {
                classify(asOf, parameter, actor, groups, classes, seconds, late, missing);
            }
        } catch (FairnessReadException ex) {
            return Result.notComputable(asOf, List.of(ex.missing), version(parameter));
        }
        if (!missing.isEmpty()) return Result.notComputable(asOf, missing, version(parameter));

        classes.sort(Comparator.comparingLong(item -> item.actorId));
        seconds.sort(Comparator.comparingLong(item -> item.actorId));
        late.sort(Comparator.comparing((LateEvent item) -> item.outingId).thenComparing(item -> item.eventId));
        int denominator = 0;
        int numerator = 0;
        for (OutingClass item : classes) {
            if (!FairnessFieldContract.MATURE.equals(item.maturity)) continue;
            denominator++;
            if (FairnessFieldContract.SECOND_OUTING.equals(item.reason)) numerator++;
        }
        Integer matureCount = denominator == 0 ? null : numerator;
        Integer denominatorCount = denominator == 0 ? null : denominator;
        return new Result(COMPUTABLE, List.of(), new ArrayList<>(groups.values()), seconds, classes, late, asOf,
                parameter.metricVersion, matureCount, denominatorCount, FairnessFieldContract.FORMULA_ID,
                anyFact ? factRevision : null);
    }

    private static void classify(long asOf, ParameterRow parameter, ActorFacts actor, Map<GroupKey, Group> groups,
                                 List<OutingClass> classes, List<SecondOuting> seconds, List<LateEvent> late,
                                 List<String> missing) {
        for (Map.Entry<String, List<EventRow>> outing : actor.outings.entrySet()) {
            long earliest = Long.MAX_VALUE;
            long latest = Long.MIN_VALUE;
            for (EventRow event : outing.getValue()) {
                earliest = Math.min(earliest, event.occurredAt);
                latest = Math.max(latest, event.occurredAt);
            }
            if (latest - earliest > parameter.mergeWindowMillis) missing.add("merge-window:" + outing.getKey());
        }
        if (!missing.isEmpty()) return;

        EventRow t0Event = null;
        for (List<EventRow> outing : actor.outings.values()) {
            for (EventRow event : outing) {
                if (!effective(event.kind)) continue;
                if (t0Event == null || earlier(event, t0Event)) t0Event = event;
            }
        }
        if (t0Event == null) {
            classes.add(new OutingClass("-", actor.actorId, FairnessFieldContract.IMMATURE, FairnessFieldContract.NO_T0,
                    null, null, null));
            return;
        }
        for (List<EventRow> outing : actor.outings.values()) {
            for (EventRow event : outing) {
                if (!effective(event.kind) || !event.occurredAt.equals(t0Event.occurredAt)) continue;
                if (!Objects.equals(event.boardId, t0Event.boardId) || !Objects.equals(newcomer(event.newcomer), newcomer(t0Event.newcomer))) {
                    missing.add("cohort-conflict:" + actor.actorId);
                    return;
                }
            }
        }
        long horizonEnd;
        long matureAt;
        long deltaDue;
        try {
            horizonEnd = Math.addExact(t0Event.occurredAt, parameter.horizonMillis);
        } catch (ArithmeticException ex) {
            missing.add(HORIZON_MILLIS);
            return;
        }
        try {
            matureAt = Math.addExact(horizonEnd, parameter.graceMillis);
        } catch (ArithmeticException ex) {
            missing.add(GRACE_MILLIS);
            return;
        }
        try {
            deltaDue = Math.addExact(t0Event.occurredAt, parameter.deltaMillis);
        } catch (ArithmeticException ex) {
            missing.add(DELTA_MILLIS);
            return;
        }
        boolean counted = !actor.cheat && !actor.test && asOf >= matureAt;
        Group group = actor.cheat || actor.test ? null : groups.computeIfAbsent(
                new GroupKey(t0Event.joinTime, t0Event.teamSize, t0Event.actionCost, newcomer(t0Event.newcomer)),
                Group::new);
        EventRow second = null;
        for (Map.Entry<String, List<EventRow>> outing : actor.outings.entrySet()) {
            EventRow anchor = null;
            for (EventRow event : outing.getValue()) {
                if (event.occurredAt > horizonEnd) {
                    late.add(new LateEvent(outing.getKey(), event.eventId, event.occurredAt));
                    continue;
                }
                if (group != null) group.add(event);
                if (!effective(event.kind)) continue;
                if (anchor == null || earlier(event, anchor)) anchor = event;
            }
            if (anchor == null || outing.getKey().equals(t0Event.outingId)) continue;
            long at = anchor.occurredAt;
            if (at <= t0Event.occurredAt || at > horizonEnd || at < deltaDue) continue;
            if (second == null || earlier(anchor, second)) second = anchor;
        }
        String maturity;
        String reason;
        String secondId = null;
        if (actor.cheat) {
            maturity = FairnessFieldContract.EXCLUDED;
            reason = FairnessFieldContract.CHEAT;
        } else if (actor.test) {
            maturity = FairnessFieldContract.EXCLUDED;
            reason = FairnessFieldContract.TEST;
        } else if (!counted) {
            maturity = FairnessFieldContract.IMMATURE;
            reason = FairnessFieldContract.AWAITING_MATURITY;
        } else if (second != null) {
            maturity = FairnessFieldContract.MATURE;
            reason = FairnessFieldContract.SECOND_OUTING;
            secondId = second.outingId;
            seconds.add(new SecondOuting(actor.actorId, second.outingId, second.occurredAt, second.joinTime,
                    second.teamSize, second.actionCost, newcomer(second.newcomer)));
        } else {
            maturity = FairnessFieldContract.MATURE;
            reason = FairnessFieldContract.NO_SECOND_OUTING;
        }
        classes.add(new OutingClass(t0Event.outingId, actor.actorId, maturity, reason, t0Event.occurredAt,
                newcomer(t0Event.newcomer), secondId));
    }

    private static boolean earlier(EventRow left, EventRow right) {
        int time = left.occurredAt.compareTo(right.occurredAt);
        if (time != 0) return time < 0;
        int source = String.valueOf(left.source).compareTo(String.valueOf(right.source));
        if (source != 0) return source < 0;
        return String.valueOf(left.eventId).compareTo(String.valueOf(right.eventId)) < 0;
    }

    private static String version(ParameterRow parameter) {
        if (parameter == null || parameter.metricVersion == null || parameter.metricVersion.isBlank()) return null;
        return parameter.metricVersion;
    }

    private static String dedupeKey(EventRow event) {
        String source = event.source == null ? "" : event.source;
        return source + "\0" + event.eventId;
    }

    private static boolean sameScope(ParameterRow parameter, EventRow event) {
        return Objects.equals(parameter.boardId, event.boardId)
                && Objects.equals(parameter.regionId, event.regionId)
                && Objects.equals(parameter.seasonId, event.seasonId)
                && Objects.equals(parameter.rulesVersion, event.rulesVersion);
    }

    private static boolean effective(String kind) {
        return EFFECTIVE_TASK.equals(kind) || EFFECTIVE_SERVICE.equals(kind);
    }

    private static boolean ignored(String kind) {
        return APP_REOPEN.equals(kind) || RECONNECT.equals(kind) || REGISTRATION_REPEAT.equals(kind);
    }

    private static boolean needsOuting(String kind) {
        return effective(kind) || GOAL_COMPLETED.equals(kind) || GAIN.equals(kind) || ZERO_POINT_BLOCK.equals(kind)
                || CHALLENGE_COMPENSATION_EXIT.equals(kind) || GPS_FALSE_REJECTION.equals(kind)
                || SAFETY_TICKET.equals(kind) || MERCHANT_VERIFICATION.equals(kind);
    }

    private static boolean known(String kind) {
        return needsOuting(kind) || SCAN.equals(kind) || EXIT.equals(kind) || LOW_ACTIVITY.equals(kind)
                || CONFIRM_CHEAT.equals(kind) || TEST_MEMBER.equals(kind) || REVOKE.equals(kind);
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
                && Objects.equals(left.knownAt, right.knownAt)
                && Objects.equals(left.subjectSource, right.subjectSource)
                && Objects.equals(left.subjectEventId, right.subjectEventId)
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
        List<EventRow> events(long asOfInclusive);
        List<String> unavailableStreams();
    }

    public static final class ParameterRow {
        public String state;
        public String sourceRef;
        public Long deltaMillis;
        public Long horizonMillis;
        public Long graceMillis;
        public Long mergeWindowMillis;
        public String stateRule;
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
        public Long knownAt;
        public String subjectSource;
        public String subjectEventId;
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
        public final String formulaId;
        public final Long factRevision;

        Result(String status, List<String> missing, List<Group> groups, List<SecondOuting> secondOutings,
               List<OutingClass> outingClasses, List<LateEvent> lateEvents, long asOf, String metricVersion,
               Integer matureCount, Integer denominator, String formulaId, Long factRevision) {
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
            this.formulaId = formulaId;
            this.factRevision = factRevision;
        }

        static Result notComputable(long asOf, List<String> missing) {
            return notComputable(asOf, missing, null);
        }

        static Result notComputable(long asOf, List<String> missing, String metricVersion) {
            List<String> sorted = new ArrayList<>(missing);
            sorted.sort(String::compareTo);
            String version = metricVersion == null || metricVersion.isBlank() ? null : metricVersion;
            return new Result(NOT_COMPUTABLE, sorted.stream().distinct().toList(), List.of(), List.of(), List.of(),
                    List.of(), asOf, version, null, null, FairnessFieldContract.FORMULA_ID, null);
        }

        public boolean computable() { return COMPUTABLE.equals(status); }

        public String maturity() {
            if (!computable()) return "MISSING";
            if (denominator == null) return FairnessFieldContract.NO_MATURE_SAMPLE;
            return FairnessFieldContract.COUNTED;
        }
    }

    public static final class OutingClass {
        public final String outingId;
        public final long actorId;
        public final String maturity;
        public final String reason;
        public final Long t0;
        public final String newbieQueue;
        public final String secondOutingId;

        public OutingClass(String outingId, long actorId, String maturity, String reason, Long t0,
                           String newbieQueue, String secondOutingId) {
            this.outingId = outingId;
            this.actorId = actorId;
            this.maturity = maturity;
            this.reason = reason;
            this.t0 = t0;
            this.newbieQueue = newbieQueue;
            this.secondOutingId = secondOutingId;
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

        void add(EventRow event) {
            switch (event.kind) {
                case GOAL_COMPLETED -> goalCompletions++;
                case GAIN -> {
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

    private static final class ActorFacts {
        final long actorId;
        final Map<String, List<EventRow>> outings = new LinkedHashMap<>();
        boolean cheat;
        boolean test;

        ActorFacts(long actorId) { this.actorId = actorId; }
    }
}
