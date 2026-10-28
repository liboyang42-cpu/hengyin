package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.OrientPhase;
import com.chengyinhub.business.domain.OrientSessionClock;
import com.chengyinhub.business.domain.OrientSessionRow;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.OrientSessionMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.OrientTaskEvidenceVerifier;
import com.chengyinhub.business.service.support.OrientSessionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrientSessionServiceImplTest {
    private static final long MEMBER = 7L;
    private static final long OTHER = 8L;
    private static final long TOPIC = 100L;
    private static final long SESSION = 500L;
    private static final long TASK = 31L;
    private static final long CONFIG_VERSION = 4L;
    private static final long T0 = 1_760_000_000_000L;

    private final OrientSessionFeatureGate gate = mock(OrientSessionFeatureGate.class);
    private final TopicRouteSessionMapper routeMapper = mock(TopicRouteSessionMapper.class);
    private final ITopicRouteRuntimeService routeRuntime = mock(ITopicRouteRuntimeService.class);
    private final CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
    private final CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
    private final OrientTaskEvidenceVerifier verifier = mock(OrientTaskEvidenceVerifier.class);
    private final FakeOrientMapper orientMapper = new FakeOrientMapper();
    private final List<TopicRouteAction> actions = new ArrayList<TopicRouteAction>();
    private final MutableClock clock = new MutableClock(T0);

    private OrientSessionServiceImpl service;
    private CmsTopic topic;
    private OrientSessionRow row;

    @BeforeEach
    void setUp() {
        service = new OrientSessionServiceImpl(gate, orientMapper, routeMapper, routeRuntime, topicMapper, nodeMapper,
                verifier, clock);
        when(gate.isEnabled()).thenReturn(true);
        topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(1);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(routeRuntime.playInstance(MEMBER, TOPIC, 0L)).thenReturn("default");

        row = new OrientSessionRow();
        row.setId(SESSION);
        row.setActivityId(0L);
        row.setTopicId(TOPIC);
        row.setMemberId(MEMBER);
        row.setPlayInstanceId("default");
        row.setConfigVersion(CONFIG_VERSION);
        row.setStatus(TopicRouteSession.STATUS_RUNNING);
        row.setVersion(0);
        orientMapper.rows.put(SESSION, row);

        TopicRouteSession current = new TopicRouteSession();
        current.setId(SESSION);
        current.setActivityId(0L);
        current.setTopicId(TOPIC);
        current.setMemberId(MEMBER);
        current.setPlayInstanceId("default");
        when(routeMapper.selectCurrentByOwner(0L, TOPIC, MEMBER)).thenReturn(current);

        when(routeMapper.selectActionByAction(anyLong(), anyLong(), anyString())).thenAnswer(inv -> {
            for (TopicRouteAction a : actions) {
                if (a.getSessionId().equals(inv.getArgument(0)) && a.getMemberId().equals(inv.getArgument(1))
                        && a.getActionId().equals(inv.getArgument(2))) return a;
            }
            return null;
        });
        when(routeMapper.selectActionByReceipt(anyLong(), anyString())).thenAnswer(inv -> {
            for (TopicRouteAction a : actions) {
                if (a.getSessionId().equals(inv.getArgument(0)) && a.getReceiptId().equals(inv.getArgument(1))) return a;
            }
            return null;
        });
        when(routeMapper.insertAction(any(TopicRouteAction.class))).thenAnswer(inv -> {
            TopicRouteAction a = inv.getArgument(0);
            for (TopicRouteAction existing : actions) {
                if (existing.getSessionId().equals(a.getSessionId()) && (existing.getReceiptId().equals(a.getReceiptId())
                        || existing.getActionId().equals(a.getActionId()) && existing.getMemberId().equals(a.getMemberId()))) {
                    throw new DuplicateKeyException("uk_topic_route_action");
                }
            }
            actions.add(a);
            return 1;
        });

        CmsTopicNode node = new CmsTopicNode();
        node.setId(TASK);
        node.setTopicId(TOPIC);
        when(nodeMapper.selectCmsTopicNodeById(TASK)).thenReturn(node);
    }

    @Test
    void disabledSwitchRejectsEveryEndpointWithoutTouchingNewSchema() {
        when(gate.isEnabled()).thenReturn(false);
        assertErr("ORIENT_SESSION_DISABLED", () -> service.prepare(MEMBER, TOPIC, 0L, "key-prepare-1"));
        assertErr("ORIENT_SESSION_DISABLED", () -> service.start(MEMBER, SESSION, CONFIG_VERSION, "key-start-1"));
        assertErr("ORIENT_SESSION_DISABLED", () -> service.abandon(MEMBER, SESSION, "key-abandon-1"));
        assertErr("ORIENT_SESSION_DISABLED", () -> service.taskResult(MEMBER, SESSION, TASK, null, "key-task-1"));
        assertErr("ORIENT_SESSION_DISABLED", () -> service.view(MEMBER, SESSION));
        assertErr("ORIENT_SESSION_DISABLED", () -> service.receipt(MEMBER, SESSION, "key-start-1"));
        assertEquals(0, orientMapper.calls);
        verify(routeRuntime, never()).state(anyLong(), anyLong(), any());
        assertTrue(actions.isEmpty());
    }

    @Test
    void onlyCityOrienteeringTopicsEnterTheFlow() {
        topic.setProductType(2);
        assertErr("ORIENT_PRODUCT_TYPE_UNSUPPORTED", () -> service.prepare(MEMBER, TOPIC, 0L, "key-prepare-1"));
        topic.setProductType(null);
        assertErr("ORIENT_PRODUCT_TYPE_UNSUPPORTED", () -> service.start(MEMBER, SESSION, CONFIG_VERSION, "key-start-1"));
        assertErr("ORIENT_PRODUCT_TYPE_UNSUPPORTED", () -> service.abandon(MEMBER, SESSION, "key-abandon-1"));
        assertEquals(TopicRouteSession.STATUS_RUNNING, row.getStatus().intValue());
    }

    @Test
    void prepareAckStartHaveNoCircularDependency() {
        Map<String, Object> prepared = service.prepare(MEMBER, TOPIC, 0L, "key-prepare-1");
        assertEquals("PREPARED", prepared.get("phase"));
        assertEquals(CONFIG_VERSION, prepared.get("ackConfigVersion"));
        assertNull(prepared.get("startedAt"));
        assertEquals("PREPARED", row.getPhase());

        OrientSessionException noAck = assertErr("ORIENT_ACK_REQUIRED",
                () -> service.start(MEMBER, SESSION, null, "key-start-0"));
        assertEquals(CONFIG_VERSION, ((Map<?, ?>) noAck.getData()).get("ackConfigVersion"));
        assertErr("ORIENT_ACK_REQUIRED", () -> service.start(MEMBER, SESSION, CONFIG_VERSION + 1, "key-start-0b"));
        assertNull(orientMapper.clocks.get(SESSION));

        clock.set(T0 + 5_000L);
        Map<String, Object> started = service.start(MEMBER, SESSION, CONFIG_VERSION, "key-start-1");
        assertEquals("RUNNING", started.get("phase"));
        assertEquals(T0 + 5_000L, started.get("startedAt"));
        assertEquals(false, started.get("resumed"));
        assertEquals("RUNNING", row.getPhase());
    }

    @Test
    void reentryNeverRestartsTheServerClock() {
        service.start(MEMBER, SESSION, CONFIG_VERSION, "key-start-1");
        clock.set(T0 + 60_000L);
        // 重进:新的幂等键、甚至不带确认,都只续上原计时。
        Map<String, Object> again = service.start(MEMBER, SESSION, null, "key-start-2");
        assertEquals(true, again.get("resumed"));
        assertEquals(T0, again.get("startedAt"));
        assertEquals(60_000L, again.get("elapsedMs"));
        Map<String, Object> reprepared = service.prepare(MEMBER, TOPIC, 0L, "key-prepare-2");
        assertEquals("RUNNING", reprepared.get("phase"));
        assertEquals(T0, reprepared.get("startedAt"));
        assertEquals(1, orientMapper.clockInserts);
    }

    @Test
    void elapsedTimeComesOnlyFromTheServerClock() {
        service.start(MEMBER, SESSION, CONFIG_VERSION, "key-start-1");
        clock.set(T0 + 42_000L);
        Map<String, Object> view = service.view(MEMBER, SESSION);
        assertEquals(42_000L, view.get("elapsedMs"));
        assertEquals(T0 + 42_000L, view.get("serverNow"));
        clock.set(T0 + 90_000L);
        Map<String, Object> abandoned = service.abandon(MEMBER, SESSION, "key-abandon-1");
        assertEquals(90_000L, abandoned.get("elapsedMs"));
        clock.set(T0 + 999_000L);
        // 终局后用时冻结,时钟再走也不变。
        assertEquals(90_000L, service.view(MEMBER, SESSION).get("elapsedMs"));
    }

    @Test
    void sameKeyReplaysTheSameReceiptAndOtherPayloadIsRejected() {
        Map<String, Object> first = service.start(MEMBER, SESSION, CONFIG_VERSION, "key-start-1");
        clock.set(T0 + 30_000L);
        Map<String, Object> replay = service.start(MEMBER, SESSION, CONFIG_VERSION, "key-start-1");
        assertEquals(true, replay.get("replayed"));
        assertEquals(((Number) first.get("startedAt")).longValue(), ((Number) replay.get("startedAt")).longValue());
        assertEquals(((Number) first.get("serverNow")).longValue(), ((Number) replay.get("serverNow")).longValue());
        assertErr("ORIENT_IDEMPOTENCY_CONFLICT", () -> service.start(MEMBER, SESSION, CONFIG_VERSION + 9, "key-start-1"));

        Map<String, Object> readBack = service.receipt(MEMBER, SESSION, "key-start-1");
        assertEquals(((Number) first.get("serverNow")).longValue(), ((Number) readBack.get("serverNow")).longValue());
        assertErr("ORIENT_RECEIPT_NOT_FOUND", () -> service.receipt(MEMBER, SESSION, "key-never-sent"));
        assertErr("ORIENT_IDEMPOTENCY_KEY_REQUIRED", () -> service.start(MEMBER, SESSION, CONFIG_VERSION, null));
        assertErr("ORIENT_IDEMPOTENCY_KEY_REQUIRED", () -> service.abandon(MEMBER, SESSION, "bad key!"));
    }

    @Test
    void otherMembersCannotReadOrWriteTheSession() {
        assertErr("ORIENT_SESSION_NOT_FOUND", () -> service.start(OTHER, SESSION, CONFIG_VERSION, "key-start-1"));
        assertErr("ORIENT_SESSION_NOT_FOUND", () -> service.abandon(OTHER, SESSION, "key-abandon-1"));
        assertErr("ORIENT_SESSION_NOT_FOUND", () -> service.taskResult(OTHER, SESSION, TASK, null, "key-task-1"));
        assertErr("ORIENT_SESSION_NOT_FOUND", () -> service.view(OTHER, SESSION));
        assertErr("ORIENT_SESSION_NOT_FOUND", () -> service.receipt(OTHER, SESSION, "key-start-1"));
        assertTrue(actions.isEmpty());
    }

    @Test
    void abandonIsTerminalAndIdempotent() {
        service.start(MEMBER, SESSION, CONFIG_VERSION, "key-start-1");
        clock.set(T0 + 10_000L);
        Map<String, Object> first = service.abandon(MEMBER, SESSION, "key-abandon-1");
        assertEquals("ABANDONED", first.get("phase"));
        assertEquals(TopicRouteSession.STATUS_ENDED, row.getStatus().intValue());
        assertEquals(1, orientMapper.terminations);

        clock.set(T0 + 20_000L);
        Map<String, Object> again = service.abandon(MEMBER, SESSION, "key-abandon-2");
        assertEquals("ABANDONED", again.get("phase"));
        assertEquals(10_000L, ((Number) again.get("elapsedMs")).longValue());
        assertEquals(1, orientMapper.terminations);

        assertErr("ORIENT_SESSION_TERMINAL", () -> service.start(MEMBER, SESSION, CONFIG_VERSION, "key-start-2"));
        assertErr("ORIENT_SESSION_TERMINAL", () -> service.disqualify(MEMBER, SESSION, "GPS_SPOOF", "key-dq-1"));
        assertErr("ORIENT_SESSION_TERMINAL", () -> service.taskResult(MEMBER, SESSION, TASK, null, "key-task-1"));
        assertErr("ORIENT_SESSION_TERMINAL", () -> service.prepare(MEMBER, TOPIC, 0L, "key-prepare-9"));
    }

    @Test
    void disqualifyIsTerminalAndIdempotent() {
        Map<String, Object> first = service.disqualify(MEMBER, SESSION, "GPS_SPOOF", "key-dq-1");
        assertEquals("DISQUALIFIED", first.get("phase"));
        assertEquals("GPS_SPOOF", first.get("reasonCode"));
        assertEquals(OrientPhase.STATUS_DISQUALIFIED, row.getStatus().intValue());
        Map<String, Object> again = service.disqualify(MEMBER, SESSION, "GPS_SPOOF", "key-dq-2");
        assertEquals("DISQUALIFIED", again.get("phase"));
        assertEquals(1, orientMapper.terminations);
        assertErr("ORIENT_SESSION_TERMINAL", () -> service.abandon(MEMBER, SESSION, "key-abandon-1"));
        assertErr("ORIENT_BAD_REQUEST", () -> service.disqualify(MEMBER, SESSION, "bad reason", "key-dq-3"));
    }

    @Test
    void taskResultIsRecordedOncePerLogicalTaskWhicheverAlternativeIsUsed() {
        assertErr("ORIENT_SESSION_NOT_STARTED", () -> service.taskResult(MEMBER, SESSION, TASK, null, "key-task-0"));
        service.start(MEMBER, SESSION, CONFIG_VERSION, "key-start-1");
        assertErr("ORIENT_TASK_EVIDENCE_MISSING", () -> service.taskResult(MEMBER, SESSION, TASK, null, "key-task-1"));

        when(verifier.verifiedCompletion(any())).thenReturn(new Date(T0 + 7_000L));
        Map<String, Object> done = service.taskResult(MEMBER, SESSION, TASK, "scan", "key-task-2");
        assertEquals("SCAN", done.get("alternativeTaskType"));
        assertEquals(7_000L, done.get("elapsedMs"));
        assertEquals("orient:task:" + TASK, done.get("receiptId"));

        OrientSessionException dup = assertErr("ORIENT_TASK_ALREADY_COMPLETED",
                () -> service.taskResult(MEMBER, SESSION, TASK, "QUIZ", "key-task-3"));
        assertEquals("SCAN", ((Map<?, ?>) dup.getData()).get("alternativeTaskType"));
        assertEquals(true, service.taskResult(MEMBER, SESSION, TASK, "SCAN", "key-task-2").get("replayed"));

        assertErr("ORIENT_BAD_REQUEST", () -> service.taskResult(MEMBER, SESSION, TASK, "AR_MAGIC", "key-task-4"));
        assertErr("ORIENT_TASK_NOT_FOUND", () -> service.taskResult(MEMBER, SESSION, 999L, null, "key-task-5"));
    }

    @Test
    void taskEvidenceFromBeforeStartDoesNotCount() {
        clock.set(T0 + 1_000L);
        service.start(MEMBER, SESSION, CONFIG_VERSION, "key-start-1");
        when(verifier.verifiedCompletion(any())).thenReturn(new Date(T0));
        assertErr("ORIENT_TASK_EVIDENCE_MISSING", () -> service.taskResult(MEMBER, SESSION, TASK, null, "key-task-1"));
    }

    @Test
    void prepareMapsAccessFailuresAndMissingRouteSessions() {
        when(routeRuntime.state(eq(MEMBER), eq(TOPIC), eq(0L)))
                .thenThrow(new com.chengyinhub.common.exception.ServiceException("请先报名", 403));
        assertErr("ORIENT_ACCESS_DENIED", () -> service.prepare(MEMBER, TOPIC, 0L, "key-prepare-1"));
        // 首次开赛同样复核可玩性(如报名已退款),不凭 prepare 时的结论起表。
        assertErr("ORIENT_ACCESS_DENIED", () -> service.start(MEMBER, SESSION, CONFIG_VERSION, "key-start-1"));
        assertNull(orientMapper.clocks.get(SESSION));
    }

    @Test
    void notPlayableRouteIsReportedWithItsOwnCode() {
        when(routeRuntime.state(eq(MEMBER), eq(TOPIC), eq(0L))).thenThrow(new IllegalStateException("请先购买自玩通行证"));
        OrientSessionException e = assertErr("ORIENT_NOT_PLAYABLE", () -> service.prepare(MEMBER, TOPIC, 0L, "key-prepare-1"));
        assertEquals("请先购买自玩通行证", e.getMessage());
    }

    @Test
    void prepareRejectsTopicsWithoutRouteSessionAndTeamRuns() {
        when(routeMapper.selectCurrentByOwner(0L, TOPIC, MEMBER)).thenReturn(null);
        when(routeMapper.selectByOwner(0L, TOPIC, MEMBER, "default")).thenReturn(null);
        assertErr("ORIENT_SESSION_UNAVAILABLE", () -> service.prepare(MEMBER, TOPIC, 0L, "key-prepare-1"));
        when(routeRuntime.playInstance(MEMBER, TOPIC, 0L)).thenReturn("game:12");
        assertErr("ORIENT_TEAM_UNSUPPORTED", () -> service.prepare(MEMBER, TOPIC, 0L, "key-prepare-2"));
    }

    @Test
    void existingSessionsAreMappedByWhetherAClockFactExists() {
        assertEquals(OrientPhase.PREPARED, OrientPhase.derive(0, null, false));
        assertEquals(OrientPhase.RUNNING, OrientPhase.derive(0, null, true));
        assertEquals(OrientPhase.RUNNING, OrientPhase.derive(0, "RUNNING", false));
        assertEquals(OrientPhase.FINISHED, OrientPhase.derive(1, null, true));
        assertEquals(OrientPhase.ABANDONED, OrientPhase.derive(2, null, false));
        assertEquals(OrientPhase.ABANDONED, OrientPhase.derive(2, "ABANDONED", true));
        assertEquals(OrientPhase.DISQUALIFIED, OrientPhase.derive(3, null, true));
        assertFalse(OrientPhase.PREPARED.isTerminal());
        assertFalse(OrientPhase.RUNNING.isTerminal());

        orientMapper.clocks.put(SESSION, clockRow(T0 - 5_000L));
        Map<String, Object> view = service.view(MEMBER, SESSION);
        assertEquals("RUNNING", view.get("phase"));
        assertEquals(5_000L, view.get("elapsedMs"));
    }

    @Test
    void orientReceiptsStayOutOfTheJourneyLog() {
        assertNull(PlayJourneyReadServiceImpl.kindOf("orient:key-task-2", "ORIENT_TASK"));
        assertNull(PlayJourneyReadServiceImpl.kindOf("orient:key-start-1", "ORIENT_START"));
    }

    private static OrientSessionException assertErr(String err, org.junit.jupiter.api.function.Executable call) {
        OrientSessionException e = assertThrows(OrientSessionException.class, call);
        assertEquals(err, e.getErr());
        return e;
    }

    private OrientSessionClock clockRow(long startedAt) {
        OrientSessionClock c = new OrientSessionClock();
        c.setSessionId(SESSION);
        c.setMemberId(MEMBER);
        c.setStartedAt(new Date(startedAt));
        return c;
    }

    private static final class MutableClock extends Clock {
        private long millis;
        MutableClock(long millis) { this.millis = millis; }
        void set(long millis) { this.millis = millis; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }

    /** 内存版 OrientSessionMapper:语句的归属/状态条件与 XML 一致。 */
    private static final class FakeOrientMapper implements OrientSessionMapper {
        final Map<Long, OrientSessionRow> rows = new HashMap<Long, OrientSessionRow>();
        final Map<Long, OrientSessionClock> clocks = new HashMap<Long, OrientSessionClock>();
        int calls;
        int clockInserts;
        int terminations;

        private OrientSessionRow owned(Long id, Long memberId) {
            calls++;
            OrientSessionRow r = rows.get(id);
            return r != null && r.getMemberId().equals(memberId) ? r : null;
        }

        @Override public List<OrientSessionRow> selectOwnedSessions(Long memberId, Long topicId, Long activityId) {
            calls++;
            return rows.values().stream().filter(r -> r.getMemberId().equals(memberId)
                    && r.getTopicId().equals(topicId)
                    && (activityId == null || activityId.equals(r.getActivityId()))).toList();
        }

        @Override public OrientSessionRow selectSession(Long id, Long memberId) { return owned(id, memberId); }
        @Override public OrientSessionRow selectSessionForUpdate(Long id, Long memberId) { return owned(id, memberId); }

        @Override public int updateRunningPhase(Long id, Long memberId, String phase, Date updateTime) {
            OrientSessionRow r = owned(id, memberId);
            if (r == null || r.getStatus() != 0
                    || (r.getPhase() != null && !"PREPARED".equals(r.getPhase()) && !"RUNNING".equals(r.getPhase()))) return 0;
            r.setPhase(phase);
            return 1;
        }

        @Override public int terminate(Long id, Long memberId, int status, String phase, Date endedAt) {
            OrientSessionRow r = owned(id, memberId);
            if (r == null || r.getStatus() != 0) return 0;
            r.setStatus(status);
            r.setPhase(phase);
            r.setCompletedAt(endedAt);
            terminations++;
            return 1;
        }

        @Override public OrientSessionClock selectClock(Long sessionId, Long memberId) {
            calls++;
            OrientSessionClock c = clocks.get(sessionId);
            return c != null && c.getMemberId().equals(memberId) ? c : null;
        }

        @Override public int insertClock(OrientSessionClock clock) {
            calls++;
            if (clocks.containsKey(clock.getSessionId())) throw new DuplicateKeyException("PRIMARY");
            clocks.put(clock.getSessionId(), clock);
            clockInserts++;
            return 1;
        }

        @Override public int closeClock(Long sessionId, Long memberId, Date endedAt, long elapsedMs) {
            OrientSessionClock c = selectClock(sessionId, memberId);
            if (c == null || c.getEndedAt() != null) return 0;
            c.setEndedAt(endedAt);
            c.setElapsedMs(elapsedMs);
            return 1;
        }

        @Override public Date selectVerifiedCompletion(Long memberId, Long activityId, Long topicId, Long nodeId,
                                                       String playInstanceId, Date since) {
            calls++;
            return null;
        }
    }
}
