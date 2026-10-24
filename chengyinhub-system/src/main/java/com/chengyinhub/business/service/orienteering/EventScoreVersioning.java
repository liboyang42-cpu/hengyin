package com.chengyinhub.business.service.orienteering;

import com.chengyinhub.business.domain.OrientScoreEntry;
import com.chengyinhub.business.mapper.OrientScoringMapper;
import com.chengyinhub.business.service.IOrientScoringService;
import com.chengyinhub.business.service.orienteering.OrdinaryCityTimezoneQualifier.SafetyConfirmation;
import com.chengyinhub.business.service.orienteering.PublishedRuleSelector.FrozenPublishedRule;
import com.chengyinhub.business.service.orienteering.TeamClubEligibilityAuthority.Registration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 赛事成绩版本真源。有符号计分、并列名次、DNF/DQ/待审、finish 后按原请求回读；
 * 不写入成长个人榜，也不发奖。持久计分走既有 {@link IOrientScoringService} /
 * {@link OrientScoringMapper}，不另造一套 ledger。
 */
public final class EventScoreVersioning {

    public static final String REWARD_KIND = "EVENT_SCORE";
    public static final String OUTCOME_RUNNING = "RUNNING";
    public static final String OUTCOME_PENDING = "PENDING";
    public static final String OUTCOME_DNF = "DNF";
    public static final String OUTCOME_DQ = "DQ";
    public static final String OUTCOME_FINISHED = "FINISHED";
    public static final String OUTCOME_NOT_FOUND = "NOT_FOUND";
    public static final String OUTCOME_UNKNOWN = "UNKNOWN";
    /** 原计分写入/回读依赖 TopicRouteSession；缺会话时 WAITING_SOURCE，不占进程账。 */
    public static final String SCORE_LEDGER_SESSION = "orientScoring.topicRouteSession";
    /** 原奖励来源不是成绩；finish 不得把 scoreCenti 抄进 originalRewardCenti。 */
    public static final String ORIGINAL_REWARD_SOURCE = "eventScore.originalRewardSource";

    private static final Set<String> EVIDENCE_KINDS = Set.of("SCAN", "PHOTO", "QUIZ", "TASK_RECEIPT");
    private static final Set<String> GPS_KINDS = Set.of("GPS", "LAT", "LNG", "LATLNG", "COORDINATE", "LOCATION");
    private static final Set<String> TERMINAL = Set.of(OUTCOME_FINISHED, OUTCOME_DNF, OUTCOME_DQ);
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_.:-]{8,64}");

    private final AtomicLong runSeq = new AtomicLong(1);
    private final Map<Long, EventRun> runs = new ConcurrentHashMap<Long, EventRun>();
    private final Map<String, ResultVersion> resultsByRequest = new ConcurrentHashMap<String, ResultVersion>();
    private final Map<Long, ResultVersion> resultsByRun = new ConcurrentHashMap<Long, ResultVersion>();
    private final IOrientScoringService originalScoring;
    private final OrientScoringMapper scoringMapper;
    private final Long topicRouteSessionId;

    public EventScoreVersioning() {
        this(null, null, null);
    }

    public EventScoreVersioning(IOrientScoringService originalScoring, OrientScoringMapper scoringMapper,
                                Long topicRouteSessionId) {
        this.originalScoring = originalScoring;
        this.scoringMapper = scoringMapper;
        this.topicRouteSessionId = topicRouteSessionId;
    }

    public EventRun start(Registration registration, FrozenPublishedRule frozen, SafetyConfirmation safety,
                          Long ackPublishedConfigVersion, String requestId) {
        String key = requireKey(requestId);
        if (registration == null || frozen == null || safety == null) {
            throw new OrienteeringEventException(OrienteeringEventException.INVALID_ARGUMENT, "开局参数有误");
        }
        if (registration.topicId != frozen.topicId) {
            throw new OrienteeringEventException(OrienteeringEventException.INVALID_ARGUMENT, "报名与规则不是同一主题");
        }
        if (!safety.complete() || safety.frozenRuleVersion != frozen.publishedConfigVersion
                || !frozen.digest.equals(safety.frozenRuleDigest)) {
            throw new OrienteeringEventException(OrienteeringEventException.SAFETY_UNCONFIRMED, "须按已发布规则完成逐项安全确认");
        }
        if (ackPublishedConfigVersion == null || ackPublishedConfigVersion != frozen.publishedConfigVersion) {
            throw new OrienteeringEventException(OrienteeringEventException.RULE_VERSION_MISMATCH,
                    "开局必须确认冻结的已发布规则版本");
        }
        EventRun created = new EventRun(runSeq.getAndIncrement(), key, registration, frozen, OUTCOME_RUNNING);
        EventRun raced = putUnique(key, created);
        return raced != null ? raced : created;
    }

    public EventRun recordEvidence(long runId, long taskId, String evidenceKind, String evidenceFingerprint,
                                   String requestId) {
        String key = requireKey(requestId);
        EventRun run = requireRun(runId);
        requireWritable(run);
        String kind = evidenceKind == null ? "" : evidenceKind.trim().toUpperCase();
        if (GPS_KINDS.contains(kind) || looksLikeGps(evidenceFingerprint)) {
            throw new OrienteeringEventException(OrienteeringEventException.GPS_FORBIDDEN,
                    "任务证据不得提交伪造或客户端 GPS");
        }
        if (!EVIDENCE_KINDS.contains(kind) || evidenceFingerprint == null || evidenceFingerprint.trim().isEmpty()
                || taskId <= 0) {
            throw new OrienteeringEventException(OrienteeringEventException.EVIDENCE_INVALID, "任务证据无效");
        }
        Evidence evidence = new Evidence(taskId, kind, evidenceFingerprint.trim(), key);
        synchronized (run) {
            requireWritable(run);
            for (Evidence existing : run.evidence) {
                if (existing.requestId.equals(key)) {
                    if (!existing.sameAs(evidence)) {
                        throw new OrienteeringEventException(OrienteeringEventException.IDEMPOTENCY_CONFLICT,
                                "同一证据键不能改写");
                    }
                    return snapshot(run);
                }
            }
            run.evidence.add(evidence);
            return snapshot(run);
        }
    }

    public EventRun appendScore(long runId, int signedCenti, String dimension, String reasonCode, String requestId) {
        String key = requireKey(requestId);
        EventRun run = requireRun(runId);
        requireWritable(run);
        if (dimension == null || dimension.trim().isEmpty() || reasonCode == null || reasonCode.trim().isEmpty()) {
            throw new OrienteeringEventException(OrienteeringEventException.INVALID_ARGUMENT, "计分维度无效");
        }
        ScoreEntry entry = new ScoreEntry(signedCenti, dimension.trim(), reasonCode.trim(), key);
        synchronized (run) {
            requireWritable(run);
            for (ScoreEntry existing : run.entries) {
                if (existing.requestId.equals(key)) {
                    if (!existing.sameAs(entry)) {
                        throw new OrienteeringEventException(OrienteeringEventException.IDEMPOTENCY_CONFLICT,
                                "同一计分键不能改写");
                    }
                    return snapshot(run);
                }
            }
            run.entries.add(entry);
            run.scoreCenti += signedCenti;
            return snapshot(run);
        }
    }

    public EventRun markOutcome(long runId, String outcome, String requestId) {
        requireKey(requestId);
        EventRun run = requireRun(runId);
        String next = outcome == null ? "" : outcome.trim().toUpperCase();
        if (!OUTCOME_PENDING.equals(next) && !OUTCOME_DNF.equals(next) && !OUTCOME_DQ.equals(next)
                && !OUTCOME_RUNNING.equals(next)) {
            throw new OrienteeringEventException(OrienteeringEventException.INVALID_ARGUMENT, "终局标记无效");
        }
        synchronized (run) {
            if (resultsByRun.containsKey(run.id)) {
                throw new OrienteeringEventException(OrienteeringEventException.ALREADY_TERMINAL, "成绩已封版");
            }
            if (OUTCOME_RUNNING.equals(next) && OUTCOME_PENDING.equals(run.outcome)) {
                run.outcome = OUTCOME_RUNNING;
                return snapshot(run);
            }
            if (TERMINAL.contains(run.outcome) || OUTCOME_PENDING.equals(run.outcome) && !OUTCOME_RUNNING.equals(next)) {
                if (!run.outcome.equals(next)) {
                    throw new OrienteeringEventException(OrienteeringEventException.ALREADY_TERMINAL, "本局状态已变化");
                }
                return snapshot(run);
            }
            run.outcome = next;
            return snapshot(run);
        }
    }

    public ResultVersion finish(long runId, String requestId) {
        String key = requireKey(requestId);
        EventRun run = requireRun(runId);
        synchronized (run) {
            ResultVersion existing = resultsByRequest.get(key);
            if (existing != null) {
                if (existing.runId != run.id) {
                    throw new OrienteeringEventException(OrienteeringEventException.IDEMPOTENCY_CONFLICT,
                            "同一完赛键不能改写为另一局");
                }
                return existing;
            }
            ResultVersion already = resultsByRun.get(run.id);
            if (already != null) {
                throw new OrienteeringEventException(OrienteeringEventException.IDEMPOTENCY_CONFLICT,
                        "本局已有完赛回执，请按原请求回读");
            }
            if (OUTCOME_PENDING.equals(run.outcome)) {
                throw new OrienteeringEventException(OrienteeringEventException.PENDING_REVIEW, "待审局不能完赛封版");
            }
            if (OUTCOME_RUNNING.equals(run.outcome)) run.outcome = OUTCOME_FINISHED;
            ResultVersion written = new ResultVersion(key, run.id, run.registration.topicId,
                    run.frozen.publishedConfigVersion, run.frozen.digest, 1, run.outcome,
                    run.scoreCenti, 0, REWARD_KIND, snapshotEntries(run), snapshotEvidence(run),
                    new Date());
            resultsByRequest.put(key, written);
            resultsByRun.put(run.id, written);
            return written;
        }
    }

    /** 回读原完赛请求；找不到就 NOT_FOUND，绝不新造一条请求。缺失键保持 null，不得写成空串。 */
    public ResultVersion readback(String requestId) {
        ResultVersion found = requestId == null ? null : resultsByRequest.get(requestId);
        if (found == null) {
            throw new OrienteeringEventException(OrienteeringEventException.NOT_FOUND, "没有这条成绩回执");
        }
        return found;
    }

    public OriginalReceipt recoverOriginal(String requestId) {
        return recoverUnknown(requestId);
    }

    /**
     * Missing / blank requestId stays Java {@code null} with typed {@code NOT_FOUND}.
     * Never-seen ids return {@code NOT_FOUND} without occupying the ledger.
     */
    public OriginalReceipt recoverUnknown(String requestId) {
        String id = parseKey(requestId);
        if (id == null) {
            return OriginalReceipt.notFound(null);
        }
        ResultVersion found = resultsByRequest.get(id);
        if (found == null) {
            return OriginalReceipt.notFound(id);
        }
        return new OriginalReceipt(found.requestId, found.outcome, false, found);
    }

    /**
     * 把完赛局的计分条目交给既有 {@link IOrientScoringService#appendEntry}。
     * 缺 TopicRouteSession / 原 writer 时 WAITING_SOURCE，不另造 ledger，也不把成绩当奖励。
     */
    public List<OrientScoreEntry> persistOriginal(String finishRequestId) {
        ResultVersion result = readback(finishRequestId);
        if (originalScoring == null || topicRouteSessionId == null) {
            throw new OrienteeringEventException(OrienteeringEventException.WAITING_SOURCE,
                    "原计分写入需要 TopicRouteSession", SCORE_LEDGER_SESSION);
        }
        List<OrientScoreEntry> written = new ArrayList<OrientScoreEntry>();
        for (ScoreEntry entry : result.entries) {
            written.add(originalScoring.appendEntry(topicRouteSessionId, entry.dimension, entry.signedCenti,
                    entry.reasonCode, "PROGRESS", "event-score:" + entry.requestId, null, entry.requestId));
        }
        return Collections.unmodifiableList(written);
    }

    /**
     * 新实例从既有 orient_score_ledger 按幂等键回读。进程 ConcurrentHashMap 不是持久恢复。
     * 缺 mapper/session 为 WAITING_SOURCE；从未见过的键 NOT_FOUND，不占进程完赛账。
     * 缺/空 requestId 保持 Java {@code null}。
     */
    public OrientScoreEntry recoverFromLedger(String requestId) {
        String key = parseKey(requestId);
        if (key == null) {
            return null;
        }
        if (scoringMapper == null || topicRouteSessionId == null) {
            throw new OrienteeringEventException(OrienteeringEventException.WAITING_SOURCE,
                    "原计分回读需要 TopicRouteSession", SCORE_LEDGER_SESSION);
        }
        OrientScoreEntry found = scoringMapper.selectEntryByIdem(topicRouteSessionId, key);
        if (found == null) {
            throw new OrienteeringEventException(OrienteeringEventException.NOT_FOUND, "没有这条成绩流水");
        }
        return found;
    }

    public ResultVersion rejectGrowthSubstitution() {
        throw new OrienteeringEventException(OrienteeringEventException.GROWTH_LEADERBOARD_FORBIDDEN,
                "赛事成绩不能用成长个人榜替代");
    }

    public List<RankedEntry> rank(long topicId) {
        List<ResultVersion> finished = new ArrayList<ResultVersion>();
        for (ResultVersion result : resultsByRequest.values()) {
            if (result.topicId == topicId && OUTCOME_FINISHED.equals(result.outcome)) finished.add(result);
        }
        finished.sort(Comparator.comparingInt((ResultVersion r) -> r.scoreCenti).reversed()
                .thenComparing(r -> r.finishedAt)
                .thenComparingLong(r -> r.runId));
        List<RankedEntry> ranked = new ArrayList<RankedEntry>();
        int place = 0;
        Integer previousScore = null;
        for (int i = 0; i < finished.size(); i++) {
            ResultVersion row = finished.get(i);
            if (previousScore == null || row.scoreCenti != previousScore) place = i + 1;
            previousScore = row.scoreCenti;
            ranked.add(new RankedEntry(place, row));
        }
        return Collections.unmodifiableList(ranked);
    }

    EventRun requireRun(long runId) {
        EventRun run = runs.get(runId);
        if (run == null) throw new OrienteeringEventException(OrienteeringEventException.NOT_STARTED, "没有这局赛事");
        return run;
    }

    private EventRun putUnique(String startRequestId, EventRun created) {
        synchronized (this) {
            for (EventRun existing : runs.values()) {
                if (startRequestId.equals(existing.startRequestId)) {
                    if (!existing.sameStart(created)) {
                        throw new OrienteeringEventException(OrienteeringEventException.IDEMPOTENCY_CONFLICT,
                                "同一开局键不能改写");
                    }
                    return snapshot(existing);
                }
            }
            runs.put(created.id, created);
            return null;
        }
    }

    private static void requireWritable(EventRun run) {
        if (OUTCOME_PENDING.equals(run.outcome)) {
            throw new OrienteeringEventException(OrienteeringEventException.PENDING_REVIEW, "待审局不能继续入账");
        }
        if (TERMINAL.contains(run.outcome)) {
            throw new OrienteeringEventException(OrienteeringEventException.ALREADY_TERMINAL, "本局已终局");
        }
    }

    private static boolean looksLikeGps(String fingerprint) {
        if (fingerprint == null) return false;
        String lower = fingerprint.toLowerCase();
        return lower.contains("\"lat\"") || lower.contains("\"lng\"") || lower.contains("\"latitude\"")
                || lower.contains("\"longitude\"") || lower.contains("gps=");
    }

    static String parseKey(String requestId) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            return null;
        }
        return requestId;
    }

    private static String requireKey(String requestId) {
        String id = parseKey(requestId);
        if (id == null) {
            throw new OrienteeringEventException(OrienteeringEventException.INVALID_ARGUMENT, "幂等键无效");
        }
        return id;
    }

    private static EventRun snapshot(EventRun run) {
        return run;
    }

    private static List<ScoreEntry> snapshotEntries(EventRun run) {
        return Collections.unmodifiableList(new ArrayList<ScoreEntry>(run.entries));
    }

    private static List<Evidence> snapshotEvidence(EventRun run) {
        return Collections.unmodifiableList(new ArrayList<Evidence>(run.evidence));
    }

    public static final class EventRun {
        public final long id;
        public final String startRequestId;
        public final Registration registration;
        public final FrozenPublishedRule frozen;
        volatile String outcome;
        volatile int scoreCenti;
        final List<ScoreEntry> entries = new ArrayList<ScoreEntry>();
        final List<Evidence> evidence = new ArrayList<Evidence>();

        EventRun(long id, String startRequestId, Registration registration, FrozenPublishedRule frozen, String outcome) {
            this.id = id;
            this.startRequestId = startRequestId;
            this.registration = registration;
            this.frozen = frozen;
            this.outcome = outcome;
        }

        public String outcome() { return outcome; }
        public int scoreCenti() { return scoreCenti; }
        public List<ScoreEntry> entries() { return Collections.unmodifiableList(new ArrayList<ScoreEntry>(entries)); }
        public List<Evidence> evidence() { return Collections.unmodifiableList(new ArrayList<Evidence>(evidence)); }

        boolean sameStart(EventRun other) {
            return registration.requestId.equals(other.registration.requestId)
                    && frozen.equals(other.frozen);
        }
    }

    public static final class ScoreEntry {
        public final int signedCenti;
        public final String dimension;
        public final String reasonCode;
        public final String requestId;

        ScoreEntry(int signedCenti, String dimension, String reasonCode, String requestId) {
            this.signedCenti = signedCenti;
            this.dimension = dimension;
            this.reasonCode = reasonCode;
            this.requestId = requestId;
        }

        boolean sameAs(ScoreEntry other) {
            return signedCenti == other.signedCenti && dimension.equals(other.dimension)
                    && reasonCode.equals(other.reasonCode);
        }
    }

    public static final class Evidence {
        public final long taskId;
        public final String kind;
        public final String fingerprint;
        public final String requestId;

        Evidence(long taskId, String kind, String fingerprint, String requestId) {
            this.taskId = taskId;
            this.kind = kind;
            this.fingerprint = fingerprint;
            this.requestId = requestId;
        }

        boolean sameAs(Evidence other) {
            return taskId == other.taskId && kind.equals(other.kind) && fingerprint.equals(other.fingerprint);
        }
    }

    public static final class OriginalReceipt {
        public final String originalRequestId;
        public final String outcome;
        public final boolean mintedNewRequest;
        public final ResultVersion result;

        OriginalReceipt(String originalRequestId, String outcome, boolean mintedNewRequest, ResultVersion result) {
            this.originalRequestId = originalRequestId;
            this.outcome = outcome;
            this.mintedNewRequest = mintedNewRequest;
            this.result = result;
        }

        static OriginalReceipt notFound(String requestId) {
            return new OriginalReceipt(requestId, OUTCOME_NOT_FOUND, false, null);
        }
    }

    public static final class ResultVersion {
        public final String requestId;
        public final long runId;
        public final long topicId;
        public final long frozenRuleVersion;
        public final String frozenRuleDigest;
        public final int version;
        public final String outcome;
        public final int scoreCenti;
        public final int originalRewardCenti;
        public final String rewardKind;
        public final List<ScoreEntry> entries;
        public final List<Evidence> evidence;
        public final Date finishedAt;

        ResultVersion(String requestId, long runId, long topicId, long frozenRuleVersion, String frozenRuleDigest,
                      int version, String outcome, int scoreCenti, int originalRewardCenti, String rewardKind,
                      List<ScoreEntry> entries, List<Evidence> evidence, Date finishedAt) {
            this.requestId = requestId;
            this.runId = runId;
            this.topicId = topicId;
            this.frozenRuleVersion = frozenRuleVersion;
            this.frozenRuleDigest = frozenRuleDigest;
            this.version = version;
            this.outcome = outcome;
            this.scoreCenti = scoreCenti;
            this.originalRewardCenti = originalRewardCenti;
            this.rewardKind = rewardKind;
            this.entries = entries;
            this.evidence = evidence;
            this.finishedAt = finishedAt;
        }
    }

    public static final class RankedEntry {
        public final int rank;
        public final ResultVersion result;

        RankedEntry(int rank, ResultVersion result) {
            this.rank = rank;
            this.result = result;
        }
    }
}
