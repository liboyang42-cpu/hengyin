package com.chengyinhub.business.service.orienteering;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OrientScoreEntry;
import com.chengyinhub.business.mapper.OrientScoringMapper;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IOrientScoringService;
import com.chengyinhub.business.service.orienteering.EventScoreVersioning.ResultVersion;
import com.chengyinhub.business.service.orienteering.OrdinaryCityTimezoneQualifier.SafetyConfirmation;
import com.chengyinhub.business.service.orienteering.PublishedRuleSelector.FrozenPublishedRule;
import com.chengyinhub.business.service.orienteering.TeamClubEligibilityAuthority.Registration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventScoreVersioningTest {

    private PublishedRuleSelector selector;
    private OrdinaryCityTimezoneQualifier qualifier;
    private TeamClubEligibilityAuthority authority;
    private EventScoreVersioning scores;
    private CmsTopic course;
    private FrozenPublishedRule frozen;
    private Registration registration;
    private SafetyConfirmation safety;

    @BeforeEach
    void setUp() {
        selector = new PublishedRuleSelector();
        qualifier = new OrdinaryCityTimezoneQualifier();
        IClubService clubs = mock(IClubService.class);
        IClubMemberService members = mock(IClubMemberService.class);
        IClubParticipationGuard guard = mock(IClubParticipationGuard.class);
        when(clubs.selectClubById(11L)).thenReturn(TeamClubEligibilityAuthorityTest.club(11L, 501L));
        when(members.selectByClubAndMember(11L, 501L)).thenReturn(null);
        when(members.selectByClubAndMember(11L, 502L)).thenReturn(TeamClubEligibilityAuthorityTest.member(11L, 502L));
        authority = new TeamClubEligibilityAuthority(clubs, members, guard);
        scores = new EventScoreVersioning();
        course = PublishedRuleSelectorTest.publishedCity(7L, 9L);
        frozen = selector.select(course);
        registration = authority.register(course, TeamClubEligibilityAuthority.EntryMode.TEAM,
                List.of(501L, 502L), List.of(11L), "reg-score01");
        Date start = OrdinaryCityTimezoneQualifierTest.at(2026, 10, 24, 10, 0);
        safety = qualifier.confirm(frozen, OrdinaryCityTimezoneQualifier.CITY_ZONE,
                OrdinaryCityTimezoneQualifierTest.adultDayItems(), null, start,
                OrdinaryCityTimezoneQualifierTest.at(2026, 10, 24, 15, 0), start,
                OrdinaryCityTimezoneQualifierTest.at(1990, 1, 1, 0, 0), false);
    }

    @Test
    void startUsesFrozenPublishedVersionAndFinishReadbackIsTheOriginalRequest() {
        EventScoreVersioning.EventRun run = scores.start(registration, frozen, safety, 7L, "start-sc-01");
        assertEquals(7L, run.frozen.publishedConfigVersion);
        assertEquals(frozen.digest, run.frozen.digest);
        scores.recordEvidence(run.id, 31L, "SCAN", "task:31:scan:receipt-a", "evd-sc-01");
        scores.appendScore(run.id, 10000, "TASK", "TASK_DONE", "scr-sc-01");
        scores.appendScore(run.id, -1500, "PENALTY", "HINT_USED", "scr-sc-02");
        ResultVersion finished = scores.finish(run.id, "fin-sc-01");
        assertEquals(1, finished.version);
        assertEquals(8500, finished.scoreCenti);
        assertNull(finished.originalRewardCenti);
        assertEquals(EventScoreVersioning.REWARD_KIND, finished.rewardKind);
        assertEquals("fin-sc-01", finished.requestId);
        assertSame(finished, scores.readback("fin-sc-01"));
        assertSame(finished, scores.finish(run.id, "fin-sc-01"));
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> scores.readback("fin-missing")).getErrorCode());
        assertEquals(OrienteeringEventException.IDEMPOTENCY_CONFLICT,
                assertThrows(OrienteeringEventException.class, () -> scores.finish(run.id, "fin-sc-02")).getErrorCode());
    }

    @Test
    void gpsEvidenceAndGrowthLeaderboardAreRejected() {
        EventScoreVersioning.EventRun run = scores.start(registration, frozen, safety, 7L, "start-sc-02");
        assertEquals(OrienteeringEventException.GPS_FORBIDDEN,
                assertThrows(OrienteeringEventException.class,
                        () -> scores.recordEvidence(run.id, 31L, "GPS", "lat=31.2,lng=121.4", "evd-gps-01")).getErrorCode());
        assertEquals(OrienteeringEventException.GPS_FORBIDDEN,
                assertThrows(OrienteeringEventException.class,
                        () -> scores.recordEvidence(run.id, 31L, "SCAN", "{\"lat\":31.2,\"lng\":121.4}", "evd-gps-02")).getErrorCode());
        assertEquals(OrienteeringEventException.GROWTH_LEADERBOARD_FORBIDDEN,
                assertThrows(OrienteeringEventException.class, scores::rejectGrowthSubstitution).getErrorCode());
        assertEquals(OrienteeringEventException.RULE_VERSION_MISMATCH,
                assertThrows(OrienteeringEventException.class,
                        () -> scores.start(registration, frozen, safety, 9L, "start-sc-03")).getErrorCode());
    }

    @Test
    void pendingBlocksFinishWhileDnfAndDqCanSealAndTiesShareRank() {
        EventScoreVersioning.EventRun pending = scores.start(registration, frozen, safety, 7L, "start-pd-01");
        scores.markOutcome(pending.id, EventScoreVersioning.OUTCOME_PENDING, "mark-pd-01");
        assertEquals(OrienteeringEventException.PENDING_REVIEW,
                assertThrows(OrienteeringEventException.class, () -> scores.finish(pending.id, "fin-pd-01")).getErrorCode());
        scores.markOutcome(pending.id, EventScoreVersioning.OUTCOME_RUNNING, "mark-pd-02");
        scores.appendScore(pending.id, 5000, "TASK", "TASK_DONE", "scr-pd-01");
        ResultVersion a = scores.finish(pending.id, "fin-pd-02");
        assertEquals(EventScoreVersioning.OUTCOME_FINISHED, a.outcome);

        Registration other = authority.register(course, TeamClubEligibilityAuthority.EntryMode.TEAM,
                List.of(501L, 502L), List.of(11L), "reg-score02");
        EventScoreVersioning.EventRun dnf = scores.start(other, frozen, safety, 7L, "start-dnf01");
        scores.appendScore(dnf.id, 2000, "TASK", "TASK_DONE", "scr-dnf-01");
        scores.markOutcome(dnf.id, EventScoreVersioning.OUTCOME_DNF, "mark-dnf01");
        ResultVersion dnfResult = scores.finish(dnf.id, "fin-dnf-01");
        assertEquals(EventScoreVersioning.OUTCOME_DNF, dnfResult.outcome);

        Registration third = authority.register(course, TeamClubEligibilityAuthority.EntryMode.TEAM,
                List.of(501L, 502L), List.of(11L), "reg-score03");
        EventScoreVersioning.EventRun tie = scores.start(third, frozen, safety, 7L, "start-tie01");
        scores.appendScore(tie.id, 5000, "TASK", "TASK_DONE", "scr-tie-01");
        scores.finish(tie.id, "fin-tie-01");

        List<EventScoreVersioning.RankedEntry> ranked = scores.rank(course.getId());
        assertEquals(2, ranked.size());
        assertEquals(1, ranked.get(0).rank);
        assertEquals(1, ranked.get(1).rank);
        assertEquals(5000, ranked.get(0).result.scoreCenti);
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueFinishApplies() {
        EventScoreVersioning.OriginalReceipt blank = scores.recoverOriginal(null);
        assertNull(blank.originalRequestId);
        assertEquals(EventScoreVersioning.OUTCOME_NOT_FOUND, blank.outcome);
        assertEquals(false, blank.mintedNewRequest);
        EventScoreVersioning.OriginalReceipt neverFinish = scores.recoverOriginal("c04-fin-never");
        assertEquals("c04-fin-never", neverFinish.originalRequestId);
        assertEquals(EventScoreVersioning.OUTCOME_NOT_FOUND, neverFinish.outcome);
        assertEquals(false, neverFinish.mintedNewRequest);
        assertNull(neverFinish.result);
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> scores.readback("c04-fin-never")).getErrorCode());
        EventScoreVersioning.EventRun run = scores.start(registration, frozen, safety, 7L, "c04-start-never");
        scores.appendScore(run.id, 5000, "TASK", "TASK_DONE", "c04-scr-never");
        ResultVersion first = scores.finish(run.id, "c04-fin-never");
        assertEquals("c04-fin-never", first.requestId);
        assertEquals(EventScoreVersioning.OUTCOME_FINISHED, first.outcome);
        assertSame(first, scores.readback("c04-fin-never"));
        EventScoreVersioning.OriginalReceipt recovered = scores.recoverOriginal("c04-fin-never");
        assertEquals("c04-fin-never", recovered.originalRequestId);
        assertEquals(EventScoreVersioning.OUTCOME_FINISHED, recovered.outcome);
        assertEquals(false, recovered.mintedNewRequest);
        assertSame(first, recovered.result);
        assertSame(first, scores.finish(run.id, "c04-fin-never"));
    }

    @Test
    void recoverOriginalOfNeverSeenStartIdDoesNotOccupyThenUniqueStartApplies() {
        EventScoreVersioning.OriginalReceipt neverStart = scores.recoverOriginal("c04-sta-never");
        assertEquals("c04-sta-never", neverStart.originalRequestId);
        assertEquals(EventScoreVersioning.OUTCOME_NOT_FOUND, neverStart.outcome);
        assertEquals(false, neverStart.mintedNewRequest);
        assertNull(neverStart.result);
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> scores.readback("c04-sta-never")).getErrorCode());
        EventScoreVersioning.EventRun first = scores.start(registration, frozen, safety, 7L, "c04-sta-never");
        assertEquals("c04-sta-never", first.startRequestId);
        assertEquals(EventScoreVersioning.OUTCOME_RUNNING, first.outcome());
        EventScoreVersioning.EventRun replay = scores.start(registration, frozen, safety, 7L, "c04-sta-never");
        assertEquals(first.id, replay.id);
        assertEquals(EventScoreVersioning.OUTCOME_NOT_FOUND, scores.recoverOriginal("c04-sta-never").outcome);
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> scores.readback("c04-sta-never")).getErrorCode());
    }

    @Test
    void unusedPendingFinishDoesNotOccupyThenUniqueFinishApplies() {
        EventScoreVersioning.EventRun pending = scores.start(registration, frozen, safety, 7L, "c04-sta-pend");
        scores.markOutcome(pending.id, EventScoreVersioning.OUTCOME_PENDING, "c04-mark-pend");
        assertEquals(OrienteeringEventException.PENDING_REVIEW,
                assertThrows(OrienteeringEventException.class, () -> scores.finish(pending.id, "c04-fin-pend"))
                        .getErrorCode());
        EventScoreVersioning.OriginalReceipt unused = scores.recoverOriginal("c04-fin-pend");
        assertEquals("c04-fin-pend", unused.originalRequestId);
        assertEquals(EventScoreVersioning.OUTCOME_NOT_FOUND, unused.outcome);
        assertEquals(false, unused.mintedNewRequest);
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> scores.readback("c04-fin-pend")).getErrorCode());
        scores.markOutcome(pending.id, EventScoreVersioning.OUTCOME_RUNNING, "c04-mark-runx");
        scores.appendScore(pending.id, 4000, "TASK", "TASK_DONE", "c04-scr-pend");
        ResultVersion first = scores.finish(pending.id, "c04-fin-pend");
        assertEquals("c04-fin-pend", first.requestId);
        assertEquals(EventScoreVersioning.OUTCOME_FINISHED, first.outcome);
        assertSame(first, scores.readback("c04-fin-pend"));
        assertSame(first, scores.finish(pending.id, "c04-fin-pend"));
    }

    @Test
    void unusedGpsEvidenceDoesNotOccupyThenUniqueScanApplies() {
        EventScoreVersioning.EventRun run = scores.start(registration, frozen, safety, 7L, "c04-sta-gpsx");
        assertEquals(OrienteeringEventException.GPS_FORBIDDEN,
                assertThrows(OrienteeringEventException.class,
                        () -> scores.recordEvidence(run.id, 31L, "GPS", "lat=31.2,lng=121.4", "c04-evd-gpsx"))
                        .getErrorCode());
        assertEquals(0, scores.requireRun(run.id).evidence().size(), "GPS 观察不得占证据账");
        EventScoreVersioning.EventRun first = scores.recordEvidence(run.id, 31L, "SCAN",
                "task:31:scan:alpha", "c04-evd-gpsx");
        assertEquals(1, first.evidence().size());
        assertEquals("c04-evd-gpsx", first.evidence().get(0).requestId);
        assertEquals("SCAN", first.evidence().get(0).kind);
        assertSame(first, scores.recordEvidence(run.id, 31L, "SCAN", "task:31:scan:alpha", "c04-evd-gpsx"));
    }

    @Test
    void persistOriginalUsesExistingScoringWriterAndNewInstanceReadsLedger() {
        EventScoreVersioning.EventRun run = scores.start(registration, frozen, safety, 7L, "start-sc-led");
        scores.appendScore(run.id, 10000, "TASK", "TASK_DONE", "scr-led-01");
        scores.appendScore(run.id, -1500, "PENALTY", "HINT_USED", "scr-led-02");
        ResultVersion finished = scores.finish(run.id, "fin-sc-led1");
        assertEquals(8500, finished.scoreCenti);
        assertNull(finished.originalRewardCenti);
        OrienteeringEventException waiting = assertThrows(OrienteeringEventException.class,
                () -> scores.persistOriginal("fin-sc-led1"));
        assertEquals(OrienteeringEventException.WAITING_SOURCE, waiting.getErrorCode());
        assertEquals(EventScoreVersioning.AUTHORIZED_SESSION, waiting.getWaitingCapability());
        assertNull(scores.recoverFromLedger(null));

        IOrientScoringService writer = mock(IOrientScoringService.class);
        OrientScoringMapper mapper = mock(OrientScoringMapper.class);
        OrientScoreEntry taskRow = new OrientScoreEntry();
        taskRow.setSessionId(88L);
        taskRow.setIdempotencyKey("scr-led-01");
        taskRow.setDeltaCenti(10000);
        OrientScoreEntry penaltyRow = new OrientScoreEntry();
        penaltyRow.setIdempotencyKey("scr-led-02");
        penaltyRow.setDeltaCenti(-1500);
        when(writer.appendEntry(eq(88L), eq("TASK"), eq(10000), eq("TASK_DONE"), eq("PROGRESS"),
                eq("event-score:scr-led-01"), isNull(), eq("scr-led-01"))).thenReturn(taskRow);
        when(writer.appendEntry(eq(88L), eq("PENALTY"), eq(-1500), eq("HINT_USED"), eq("PROGRESS"),
                eq("event-score:scr-led-02"), isNull(), eq("scr-led-02"))).thenReturn(penaltyRow);
        when(mapper.selectEntryByIdem(88L, "scr-led-01")).thenReturn(taskRow);
        when(mapper.selectEntryByIdem(88L, "scr-led-missing")).thenReturn(null);

        EventScoreVersioning wired = new EventScoreVersioning(writer, mapper, 88L);
        EventScoreVersioning.EventRun wiredRun = wired.start(registration, frozen, safety, 7L, "start-sc-w01");
        wired.appendScore(wiredRun.id, 10000, "TASK", "TASK_DONE", "scr-led-01");
        wired.appendScore(wiredRun.id, -1500, "PENALTY", "HINT_USED", "scr-led-02");
        ResultVersion wiredFinish = wired.finish(wiredRun.id, "fin-sc-w001");
        assertNull(wiredFinish.originalRewardCenti);
        OrienteeringEventException clientSession = assertThrows(OrienteeringEventException.class,
                () -> wired.persistOriginal("fin-sc-w001"));
        assertEquals(EventScoreVersioning.AUTHORIZED_SESSION, clientSession.getWaitingCapability());
        verify(writer, never()).appendEntry(any(), any(), anyInt(), any(), any(), any(), any(), any());
        assertEquals(EventScoreVersioning.AUTHORIZED_SESSION,
                assertThrows(OrienteeringEventException.class,
                        () -> wired.persistOriginal("fin-sc-w001", (memberId, originalSessionId) -> Long.valueOf(99L),
                                7L, 88L)).getWaitingCapability());
        verify(writer, never()).appendEntry(any(), any(), anyInt(), any(), any(), any(), any(), any());
        List<OrientScoreEntry> persisted = wired.persistOriginal("fin-sc-w001",
                (memberId, originalSessionId) -> originalSessionId, 7L, 88L);
        assertEquals(2, persisted.size());
        assertEquals("scr-led-01", persisted.get(0).getIdempotencyKey());

        EventScoreVersioning restarted = new EventScoreVersioning(writer, mapper, 88L);
        OrientScoreEntry recovered = restarted.recoverFromLedger("scr-led-01");
        assertEquals("scr-led-01", recovered.getIdempotencyKey());
        assertEquals(10000, recovered.getDeltaCenti());
        OrientScoreEntry otherSession = new OrientScoreEntry();
        otherSession.setSessionId(99L);
        otherSession.setIdempotencyKey("scr-led-other-session");
        otherSession.setDeltaCenti(1);
        when(mapper.selectEntryByIdem(88L, "scr-led-other-session")).thenReturn(otherSession);
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class,
                        () -> restarted.recoverFromLedger("scr-led-other-session")).getErrorCode());
        OrientScoreEntry otherKey = new OrientScoreEntry();
        otherKey.setSessionId(88L);
        otherKey.setIdempotencyKey("other-key");
        otherKey.setDeltaCenti(1);
        when(mapper.selectEntryByIdem(88L, "scr-led-other-key")).thenReturn(otherKey);
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class,
                        () -> restarted.recoverFromLedger("scr-led-other-key")).getErrorCode());
        assertNull(wiredFinish.originalRewardCenti);
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> restarted.recoverFromLedger("scr-led-missing"))
                        .getErrorCode());
        assertEquals(EventScoreVersioning.OUTCOME_NOT_FOUND, restarted.recoverOriginal("fin-sc-w001").outcome);
    }

    @Test
    void recoverFromScoringReceiptReadsOriginalWithoutOccupyingFinishLedger() {
        EventScoreVersioning missing = new EventScoreVersioning();
        OrienteeringEventException waiting = assertThrows(OrienteeringEventException.class,
                () -> missing.recoverFromScoringReceipt(7L, IOrientScoringService.KIND_APPEAL, 30L, "scr-rcpt-01"));
        assertEquals(OrienteeringEventException.WAITING_SOURCE, waiting.getErrorCode());
        assertEquals(EventScoreVersioning.SCORING_RECEIPT, waiting.getWaitingCapability());
        assertNull(missing.recoverFromScoringReceipt(7L, "SCORE", 30L, "scr-rcpt-01"));
        assertNull(missing.recoverFromScoringReceipt(7L, IOrientScoringService.KIND_APPEAL, 30L, null));

        IOrientScoringService scoring = mock(IOrientScoringService.class);
        Map<String, Object> absent = new LinkedHashMap<String, Object>();
        absent.put("state", "NOT_FOUND");
        when(scoring.receipt(7L, IOrientScoringService.KIND_APPEAL, 30L, "scr-rcpt-none")).thenReturn(absent);
        com.chengyinhub.business.domain.OrientScoreAppeal same = new com.chengyinhub.business.domain.OrientScoreAppeal();
        same.setSessionId(30L);
        Map<String, Object> accepted = new LinkedHashMap<String, Object>();
        accepted.put("state", "ACCEPTED");
        accepted.put("kind", IOrientScoringService.KIND_APPEAL);
        accepted.put("idempotencyKey", "scr-rcpt-01");
        accepted.put("record", same);
        when(scoring.receipt(7L, IOrientScoringService.KIND_APPEAL, 30L, "scr-rcpt-01")).thenReturn(accepted);
        com.chengyinhub.business.domain.OrientScoreAppeal other = new com.chengyinhub.business.domain.OrientScoreAppeal();
        other.setSessionId(99L);
        Map<String, Object> foreign = new LinkedHashMap<String, Object>();
        foreign.put("state", "ACCEPTED");
        foreign.put("record", other);
        when(scoring.receipt(7L, IOrientScoringService.KIND_APPEAL, 30L, "scr-rcpt-oth")).thenReturn(foreign);
        Map<String, Object> settled = new LinkedHashMap<String, Object>();
        settled.put("state", "SETTLED");
        settled.put("sessionId", Long.valueOf(30L));
        settled.put("idempotencyKey", "other-settle-key");
        when(scoring.receipt(7L, IOrientScoringService.KIND_SETTLE, 30L, "scr-settle-01")).thenReturn(settled);
        Map<String, Object> otherSettlement = new LinkedHashMap<String, Object>();
        otherSettlement.put("state", "SETTLED");
        otherSettlement.put("sessionId", Long.valueOf(99L));
        otherSettlement.put("idempotencyKey", "scr-settle-oth");
        when(scoring.receipt(7L, IOrientScoringService.KIND_SETTLE, 30L, "scr-settle-oth")).thenReturn(otherSettlement);

        EventScoreVersioning wired = new EventScoreVersioning(scoring, null, null);
        assertNull(wired.recoverFromScoringReceipt(7L, IOrientScoringService.KIND_APPEAL, 30L, "scr-rcpt-none"));
        assertEquals(EventScoreVersioning.OUTCOME_NOT_FOUND, wired.recoverOriginal("scr-rcpt-none").outcome);
        Map<String, Object> recovered = wired.recoverFromScoringReceipt(
                7L, IOrientScoringService.KIND_APPEAL, 30L, "scr-rcpt-01");
        assertEquals("ACCEPTED", recovered.get("state"));
        assertNull(wired.recoverFromScoringReceipt(7L, IOrientScoringService.KIND_APPEAL, 30L, "scr-rcpt-oth"));
        Map<String, Object> sameSettlement = wired.recoverFromScoringReceipt(
                7L, IOrientScoringService.KIND_SETTLE, 30L, "scr-settle-01");
        assertEquals(Long.valueOf(30L), sameSettlement.get("sessionId"));
        assertEquals("other-settle-key", sameSettlement.get("idempotencyKey"));
        assertNull(wired.recoverFromScoringReceipt(7L, IOrientScoringService.KIND_SETTLE, 30L, "scr-settle-oth"));
        assertEquals(EventScoreVersioning.OUTCOME_NOT_FOUND, wired.recoverOriginal("scr-rcpt-01").outcome);
        verify(scoring, never()).appendEntry(any(), any(), anyInt(), any(), any(), any(), any(), any());
        EventScoreVersioning.EventRun bad = scores.start(registration, frozen, safety, 7L, "start-bad-dom");
        OrienteeringEventException illegal = assertThrows(OrienteeringEventException.class,
                () -> scores.appendScore(bad.id, 100, "PROGRESS", "task-done", "scr-bad-dom"));
        assertEquals(OrienteeringEventException.INVALID_ARGUMENT, illegal.getErrorCode());
        assertEquals(0, scores.requireRun(bad.id).scoreCenti());
    }

    @Test
    void recoverFromSessionReceiptReadsOriginalWithoutOccupyingFinishLedger() {
        EventScoreVersioning missing = new EventScoreVersioning();
        OrienteeringEventException waiting = assertThrows(OrienteeringEventException.class,
                () -> missing.recoverFromSessionReceipt(7L, 30L, "orient-ses-01", null));
        assertEquals(OrienteeringEventException.WAITING_SOURCE, waiting.getErrorCode());
        assertEquals(EventScoreVersioning.SESSION_RECEIPT, waiting.getWaitingCapability());
        assertNull(missing.recoverFromSessionReceipt(7L, 30L, null, (memberId, sessionId, key) -> {
            throw new AssertionError("blank key must not read");
        }));
        OrienteeringEventException missingService = assertThrows(OrienteeringEventException.class,
                () -> EventScoreVersioning.fromSession(null).receipt(7L, 30L, "orient-ses-01"));
        assertEquals(EventScoreVersioning.SESSION_RECEIPT, missingService.getWaitingCapability());

        EventScoreVersioning.SessionReceiptSource source = (memberId, sessionId, key) -> {
            if ("orient-ses-none".equals(key)) {
                throw new com.chengyinhub.business.service.support.OrientSessionException(
                        EventScoreVersioning.SESSION_RECEIPT_NOT_FOUND, 404, "没有这次请求的回执");
            }
            Map<String, Object> found = new LinkedHashMap<String, Object>();
            found.put("replayed", Boolean.TRUE);
            found.put("idempotencyKey", key);
            return found;
        };
        EventScoreVersioning wired = new EventScoreVersioning();
        assertNull(wired.recoverFromSessionReceipt(7L, 30L, "orient-ses-none", source));
        assertEquals(EventScoreVersioning.OUTCOME_NOT_FOUND, wired.recoverOriginal("orient-ses-none").outcome);
        Map<String, Object> recovered = wired.recoverFromSessionReceipt(7L, 30L, "orient-ses-01", source);
        assertEquals(Boolean.TRUE, recovered.get("replayed"));
        assertEquals(EventScoreVersioning.OUTCOME_NOT_FOUND, wired.recoverOriginal("orient-ses-01").outcome);
        Map<String, Object> other = new LinkedHashMap<String, Object>();
        other.put("replayed", Boolean.TRUE);
        other.put("sessionId", Long.valueOf(99L));
        assertNull(wired.recoverFromSessionReceipt(7L, 30L, "orient-ses-other",
                (memberId, sessionId, key) -> other));
        assertEquals(EventScoreVersioning.OUTCOME_NOT_FOUND, wired.recoverOriginal("orient-ses-other").outcome);
    }
}
