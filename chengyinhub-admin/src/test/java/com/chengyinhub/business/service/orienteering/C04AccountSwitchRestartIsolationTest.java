package com.chengyinhub.business.service.orienteering;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.orienteering.EventScoreVersioning.ResultVersion;
import com.chengyinhub.business.service.orienteering.OrdinaryCityTimezoneQualifier.SafetyConfirmation;
import com.chengyinhub.business.service.orienteering.PublishedRuleSelector.FrozenPublishedRule;
import com.chengyinhub.business.service.orienteering.TeamClubEligibilityAuthority.Registration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 独占闭环隔离：账号/队伍切换不能改写原 requestId；进程内重启回读仍是原 finish/UNKNOWN；
 * 缺失 requestId 保持 Java null，不得写成空串。夹具令牌，不造 v12/232 原文编号。
 */
class C04AccountSwitchRestartIsolationTest {

    private static final long CLUB_A = 11L;
    private static final long CLUB_B = 22L;
    private static final String REG_ORIGINAL = "reg-isol-01";
    private static final String START_ORIGINAL = "start-iso1";
    private static final String FINISH_ORIGINAL = "fin-isol-01";
    private static final String FINISH_UNKNOWN = "fin-unknow";
    private static final String REG_SWITCHED = "reg-isol-02";
    private static final String START_SWITCHED = "start-iso2";
    private static final String FINISH_SWITCHED = "fin-isol-02";
    private static final String DISPUTE_ORIGINAL = "dsp-isol01";

    private PublishedRuleSelector selector;
    private OrdinaryCityTimezoneQualifier qualifier;
    private TeamClubEligibilityAuthority authority;
    private EventScoreVersioning scores;
    private EventResultDispute disputes;
    private CmsTopic course;
    private FrozenPublishedRule frozen;
    private SafetyConfirmation safety;

    @BeforeEach
    void setUp() {
        selector = new PublishedRuleSelector();
        qualifier = new OrdinaryCityTimezoneQualifier();
        IClubService clubs = mock(IClubService.class);
        IClubMemberService members = mock(IClubMemberService.class);
        IClubParticipationGuard guard = mock(IClubParticipationGuard.class);
        when(clubs.selectClubById(CLUB_A)).thenReturn(TeamClubEligibilityAuthorityTest.club(CLUB_A, 501L));
        when(clubs.selectClubById(CLUB_B)).thenReturn(TeamClubEligibilityAuthorityTest.club(CLUB_B, 601L));
        when(members.selectByClubAndMember(CLUB_A, 501L)).thenReturn(null);
        when(members.selectByClubAndMember(CLUB_A, 502L)).thenReturn(TeamClubEligibilityAuthorityTest.member(CLUB_A, 502L));
        when(members.selectByClubAndMember(CLUB_B, 602L)).thenReturn(TeamClubEligibilityAuthorityTest.member(CLUB_B, 602L));
        authority = new TeamClubEligibilityAuthority(clubs, members, guard);
        scores = new EventScoreVersioning();
        disputes = new EventResultDispute(scores);
        course = PublishedRuleSelectorTest.publishedCity(7L, 9L);
        frozen = selector.select(course);
        Date start = OrdinaryCityTimezoneQualifierTest.at(2026, 10, 24, 10, 0);
        safety = qualifier.confirm(frozen, OrdinaryCityTimezoneQualifier.CITY_ZONE,
                OrdinaryCityTimezoneQualifierTest.adultDayItems(), null, start,
                OrdinaryCityTimezoneQualifierTest.at(2026, 10, 24, 15, 0), start,
                OrdinaryCityTimezoneQualifierTest.at(1990, 1, 1, 0, 0), false);
    }

    @Test
    void accountOrTeamSwitchDoesNotRewriteOriginalRequestId() {
        Registration original = authority.register(course, TeamClubEligibilityAuthority.EntryMode.TEAM,
                List.of(501L, 502L), List.of(CLUB_A), REG_ORIGINAL);
        EventScoreVersioning.EventRun run = scores.start(original, frozen, safety, 7L, START_ORIGINAL);
        scores.recordEvidence(run.id, 31L, "SCAN", "task:31:scan:isol-a", "evd-isol01");
        scores.appendScore(run.id, 9000, "TASK", "TASK_DONE", "scr-isol01");
        ResultVersion finished = scores.finish(run.id, FINISH_ORIGINAL);

        assertEquals(REG_ORIGINAL, original.requestId);
        assertEquals(FINISH_ORIGINAL, finished.requestId);
        assertSame(original, authority.readback(REG_ORIGINAL));
        assertSame(finished, scores.readback(FINISH_ORIGINAL));

        assertEquals(OrienteeringEventException.IDEMPOTENCY_CONFLICT,
                assertThrows(OrienteeringEventException.class, () -> authority.register(course,
                        TeamClubEligibilityAuthority.EntryMode.TEAM, List.of(501L, 502L, 602L),
                        List.of(CLUB_A, CLUB_B), REG_ORIGINAL)).getErrorCode());
        assertSame(original, authority.readback(REG_ORIGINAL));
        assertEquals(List.of(501L, 502L), authority.readback(REG_ORIGINAL).memberIds);
        assertEquals(REG_ORIGINAL, authority.readback(REG_ORIGINAL).requestId);

        Registration switched = authority.register(course, TeamClubEligibilityAuthority.EntryMode.TEAM,
                List.of(501L, 502L, 602L), List.of(CLUB_A, CLUB_B), REG_SWITCHED);
        assertEquals(REG_SWITCHED, switched.requestId);
        assertNotEquals(original.requestId, switched.requestId);
        EventScoreVersioning.EventRun other = scores.start(switched, frozen, safety, 7L, START_SWITCHED);
        scores.appendScore(other.id, 4000, "TASK", "TASK_DONE", "scr-isol02");
        ResultVersion otherFinish = scores.finish(other.id, FINISH_SWITCHED);
        assertEquals(FINISH_SWITCHED, otherFinish.requestId);

        assertEquals(OrienteeringEventException.IDEMPOTENCY_CONFLICT,
                assertThrows(OrienteeringEventException.class,
                        () -> scores.finish(run.id, FINISH_SWITCHED)).getErrorCode());
        assertEquals(OrienteeringEventException.IDEMPOTENCY_CONFLICT,
                assertThrows(OrienteeringEventException.class,
                        () -> scores.finish(run.id, "fin-new-01")).getErrorCode());

        assertSame(finished, scores.readback(FINISH_ORIGINAL));
        assertEquals(FINISH_ORIGINAL, scores.readback(FINISH_ORIGINAL).requestId);
        assertNull(scores.readback(FINISH_ORIGINAL).originalRewardCenti);

        EventResultDispute.Dispute dispute = disputes.open(FINISH_ORIGINAL, "账号切换后复核原完赛", DISPUTE_ORIGINAL);
        assertEquals(FINISH_ORIGINAL, dispute.originalResultRequestId);
        assertEquals(OrienteeringEventException.IDEMPOTENCY_CONFLICT,
                assertThrows(OrienteeringEventException.class,
                        () -> disputes.open(FINISH_SWITCHED, "换号改写争议原键", DISPUTE_ORIGINAL)).getErrorCode());
        assertEquals(FINISH_ORIGINAL, disputes.readback(DISPUTE_ORIGINAL).originalResultRequestId);
        assertSame(finished, scores.readback(FINISH_ORIGINAL));
    }

    @Test
    void inProcessRestartRequeryKeepsOriginalFinishAndUnknown() {
        Registration original = authority.register(course, TeamClubEligibilityAuthority.EntryMode.TEAM,
                List.of(501L, 502L), List.of(CLUB_A), REG_ORIGINAL);
        EventScoreVersioning.EventRun run = scores.start(original, frozen, safety, 7L, START_ORIGINAL);
        scores.appendScore(run.id, 8000, "TASK", "TASK_DONE", "scr-isol01");
        ResultVersion finished = scores.finish(run.id, FINISH_ORIGINAL);

        EventScoreVersioning.EventRun restarted = scores.start(original, frozen, safety, 7L, START_ORIGINAL);
        assertEquals(run.id, restarted.id);
        assertEquals(EventScoreVersioning.OUTCOME_FINISHED, restarted.outcome());
        assertSame(finished, scores.readback(FINISH_ORIGINAL));
        assertEquals(FINISH_ORIGINAL, scores.readback(FINISH_ORIGINAL).requestId);
        assertEquals(EventScoreVersioning.OUTCOME_FINISHED, scores.readback(FINISH_ORIGINAL).outcome);

        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> scores.readback(FINISH_UNKNOWN)).getErrorCode());
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> scores.readback(FINISH_UNKNOWN)).getErrorCode());
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> authority.readback("reg-unknow")).getErrorCode());
        assertSame(finished, scores.readback(FINISH_ORIGINAL));
        assertSame(original, authority.readback(REG_ORIGINAL));
    }

    @Test
    void missingRequestIdStaysJavaNullNeverEmptyString() {
        Registration original = authority.register(course, TeamClubEligibilityAuthority.EntryMode.TEAM,
                List.of(501L, 502L), List.of(CLUB_A), REG_ORIGINAL);
        EventScoreVersioning.EventRun run = scores.start(original, frozen, safety, 7L, START_ORIGINAL);
        scores.appendScore(run.id, 5000, "TASK", "TASK_DONE", "scr-isol01");
        ResultVersion finished = scores.finish(run.id, FINISH_ORIGINAL);

        assertEquals(OrienteeringEventException.INVALID_ARGUMENT,
                assertThrows(OrienteeringEventException.class, () -> authority.register(course,
                        TeamClubEligibilityAuthority.EntryMode.TEAM, List.of(501L, 502L),
                        List.of(CLUB_A), null)).getErrorCode());
        assertEquals(OrienteeringEventException.INVALID_ARGUMENT,
                assertThrows(OrienteeringEventException.class, () -> authority.register(course,
                        TeamClubEligibilityAuthority.EntryMode.TEAM, List.of(501L, 502L),
                        List.of(CLUB_A), "")).getErrorCode());
        assertEquals(OrienteeringEventException.INVALID_ARGUMENT,
                assertThrows(OrienteeringEventException.class,
                        () -> scores.start(original, frozen, safety, 7L, null)).getErrorCode());
        assertEquals(OrienteeringEventException.INVALID_ARGUMENT,
                assertThrows(OrienteeringEventException.class,
                        () -> scores.finish(run.id, null)).getErrorCode());
        assertEquals(OrienteeringEventException.INVALID_ARGUMENT,
                assertThrows(OrienteeringEventException.class,
                        () -> scores.finish(run.id, "")).getErrorCode());

        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> scores.readback(null)).getErrorCode());
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> scores.readback("")).getErrorCode());
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> authority.readback(null)).getErrorCode());
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> authority.readback("")).getErrorCode());
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> disputes.readback(null)).getErrorCode());
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> disputes.readRestore(null)).getErrorCode());

        EventResultDispute.Dispute pending = disputes.open(FINISH_ORIGINAL, "缺失恢复键保持空值", DISPUTE_ORIGINAL);
        assertNull(pending.restoreRequestId());
        assertNotEquals("", pending.restoreRequestId());
        assertEquals(FINISH_ORIGINAL, pending.originalResultRequestId);
        assertSame(finished, scores.readback(FINISH_ORIGINAL));
        assertEquals(FINISH_ORIGINAL, finished.requestId);
        assertNotEquals("", finished.requestId);
    }

    @Test
    void recoverUnknownKeepsMissingRequestIdNullAndDoesNotOccupyLedger() {
        Registration original = authority.register(course, TeamClubEligibilityAuthority.EntryMode.TEAM,
                List.of(501L, 502L), List.of(CLUB_A), REG_ORIGINAL);
        EventScoreVersioning.EventRun run = scores.start(original, frozen, safety, 7L, START_ORIGINAL);
        scores.appendScore(run.id, 5000, "TASK", "TASK_DONE", "scr-isol01");

        EventScoreVersioning.OriginalReceipt blankScore = scores.recoverUnknown(null);
        assertNull(blankScore.originalRequestId);
        assertEquals(EventScoreVersioning.OUTCOME_NOT_FOUND, blankScore.outcome);
        assertEquals(false, blankScore.mintedNewRequest);
        assertNull(blankScore.result);
        EventScoreVersioning.OriginalReceipt emptyScore = scores.recoverUnknown("");
        assertNull(emptyScore.originalRequestId);
        assertEquals(EventScoreVersioning.OUTCOME_NOT_FOUND, emptyScore.outcome);
        assertEquals(false, emptyScore.mintedNewRequest);

        TeamClubEligibilityAuthority.OriginalReceipt blankReg = authority.recoverUnknown(null);
        assertNull(blankReg.originalRequestId);
        assertEquals(TeamClubEligibilityAuthority.OUTCOME_NOT_FOUND, blankReg.outcome);
        assertEquals(false, blankReg.mintedNewRequest);
        assertNull(blankReg.registration);
        TeamClubEligibilityAuthority.OriginalReceipt emptyReg = authority.recoverUnknown("");
        assertNull(emptyReg.originalRequestId);
        assertNotEquals("", emptyReg.originalRequestId);
        assertEquals(TeamClubEligibilityAuthority.OUTCOME_NOT_FOUND, emptyReg.outcome);

        EventResultDispute.OriginalReceipt blankDispute = disputes.recoverUnknown(null);
        assertNull(blankDispute.originalRequestId);
        assertEquals(EventResultDispute.OUTCOME_NOT_FOUND, blankDispute.outcome);
        assertEquals(false, blankDispute.mintedNewRequest);
        assertNull(blankDispute.dispute);
        EventResultDispute.OriginalReceipt emptyDispute = disputes.recoverUnknown("");
        assertNull(emptyDispute.originalRequestId);
        assertEquals(EventResultDispute.OUTCOME_NOT_FOUND, emptyDispute.outcome);

        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> scores.readback(null)).getErrorCode());
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> scores.readback("")).getErrorCode());
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> authority.readback(null)).getErrorCode());
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> disputes.readback(null)).getErrorCode());

        EventScoreVersioning.OriginalReceipt neverFinish = scores.recoverUnknown(FINISH_UNKNOWN);
        assertEquals(FINISH_UNKNOWN, neverFinish.originalRequestId);
        assertEquals(EventScoreVersioning.OUTCOME_NOT_FOUND, neverFinish.outcome);
        assertEquals(false, neverFinish.mintedNewRequest);
        assertNull(neverFinish.result);
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> scores.readback(FINISH_UNKNOWN)).getErrorCode());

        ResultVersion firstFinish = scores.finish(run.id, FINISH_UNKNOWN);
        assertEquals(FINISH_UNKNOWN, firstFinish.requestId);
        assertEquals(EventScoreVersioning.OUTCOME_FINISHED, firstFinish.outcome);
        assertSame(firstFinish, scores.readback(FINISH_UNKNOWN));
        EventScoreVersioning.OriginalReceipt recoveredFinish = scores.recoverUnknown(FINISH_UNKNOWN);
        assertEquals(FINISH_UNKNOWN, recoveredFinish.originalRequestId);
        assertEquals(EventScoreVersioning.OUTCOME_FINISHED, recoveredFinish.outcome);
        assertEquals(false, recoveredFinish.mintedNewRequest);
        assertSame(firstFinish, recoveredFinish.result);

        TeamClubEligibilityAuthority.OriginalReceipt neverReg = authority.recoverUnknown("reg-unknow");
        assertEquals("reg-unknow", neverReg.originalRequestId);
        assertEquals(TeamClubEligibilityAuthority.OUTCOME_NOT_FOUND, neverReg.outcome);
        assertEquals(false, neverReg.mintedNewRequest);
        assertNull(neverReg.registration);
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> authority.readback("reg-unknow")).getErrorCode());

        Registration firstUnknownReg = authority.register(course, TeamClubEligibilityAuthority.EntryMode.TEAM,
                List.of(501L, 502L), List.of(CLUB_A), "reg-unknow");
        assertEquals("reg-unknow", firstUnknownReg.requestId);
        assertSame(firstUnknownReg, authority.readback("reg-unknow"));
        assertEquals("reg-unknow", authority.recoverUnknown("reg-unknow").originalRequestId);
        assertEquals(TeamClubEligibilityAuthority.OUTCOME_REGISTERED,
                authority.recoverUnknown("reg-unknow").outcome);

        EventResultDispute.OriginalReceipt neverDispute = disputes.recoverUnknown("dsp-unknow");
        assertEquals("dsp-unknow", neverDispute.originalRequestId);
        assertEquals(EventResultDispute.OUTCOME_NOT_FOUND, neverDispute.outcome);
        assertEquals(false, neverDispute.mintedNewRequest);
        assertNull(neverDispute.dispute);
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> disputes.readback("dsp-unknow")).getErrorCode());

        EventResultDispute.Dispute firstDispute = disputes.open(FINISH_UNKNOWN, "未知争议键首次写入", "dsp-unknow");
        assertEquals("dsp-unknow", firstDispute.requestId);
        assertEquals(FINISH_UNKNOWN, firstDispute.originalResultRequestId);
        assertSame(firstDispute, disputes.readback("dsp-unknow"));
        assertEquals(EventResultDispute.OUTCOME_PENDING, disputes.recoverUnknown("dsp-unknow").outcome);
        assertEquals(false, disputes.recoverUnknown("dsp-unknow").mintedNewRequest);
        assertSame(firstDispute, disputes.recoverUnknown("dsp-unknow").dispute);
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyLedgerThenUniqueWriteApplies() {
        Registration original = authority.register(course, TeamClubEligibilityAuthority.EntryMode.TEAM,
                List.of(501L, 502L), List.of(CLUB_A), REG_ORIGINAL);
        EventScoreVersioning.EventRun run = scores.start(original, frozen, safety, 7L, START_ORIGINAL);
        scores.appendScore(run.id, 5000, "TASK", "TASK_DONE", "scr-orig01");

        EventScoreVersioning.OriginalReceipt blankScore = scores.recoverOriginal(null);
        assertNull(blankScore.originalRequestId);
        assertEquals(EventScoreVersioning.OUTCOME_NOT_FOUND, blankScore.outcome);
        assertEquals(false, blankScore.mintedNewRequest);
        EventScoreVersioning.OriginalReceipt neverFinish = scores.recoverOriginal("fin-orig-unk");
        assertEquals("fin-orig-unk", neverFinish.originalRequestId);
        assertEquals(EventScoreVersioning.OUTCOME_NOT_FOUND, neverFinish.outcome);
        assertEquals(false, neverFinish.mintedNewRequest);
        assertNull(neverFinish.result);
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> scores.readback("fin-orig-unk")).getErrorCode());

        ResultVersion firstFinish = scores.finish(run.id, "fin-orig-unk");
        assertEquals("fin-orig-unk", firstFinish.requestId);
        assertEquals(EventScoreVersioning.OUTCOME_FINISHED, firstFinish.outcome);
        assertSame(firstFinish, scores.readback("fin-orig-unk"));
        EventScoreVersioning.OriginalReceipt recoveredFinish = scores.recoverOriginal("fin-orig-unk");
        assertEquals("fin-orig-unk", recoveredFinish.originalRequestId);
        assertEquals(EventScoreVersioning.OUTCOME_FINISHED, recoveredFinish.outcome);
        assertEquals(false, recoveredFinish.mintedNewRequest);
        assertSame(firstFinish, recoveredFinish.result);

        TeamClubEligibilityAuthority.OriginalReceipt neverReg = authority.recoverOriginal("reg-orig-unk");
        assertEquals("reg-orig-unk", neverReg.originalRequestId);
        assertEquals(TeamClubEligibilityAuthority.OUTCOME_NOT_FOUND, neverReg.outcome);
        assertNull(neverReg.registration);
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> authority.readback("reg-orig-unk")).getErrorCode());
        Registration firstUnknownReg = authority.register(course, TeamClubEligibilityAuthority.EntryMode.TEAM,
                List.of(501L, 502L), List.of(CLUB_A), "reg-orig-unk");
        assertEquals("reg-orig-unk", firstUnknownReg.requestId);
        assertEquals(TeamClubEligibilityAuthority.OUTCOME_REGISTERED,
                authority.recoverOriginal("reg-orig-unk").outcome);

        EventResultDispute.OriginalReceipt neverDispute = disputes.recoverOriginal("dsp-orig-unk");
        assertEquals("dsp-orig-unk", neverDispute.originalRequestId);
        assertEquals(EventResultDispute.OUTCOME_NOT_FOUND, neverDispute.outcome);
        assertNull(neverDispute.dispute);
        EventResultDispute.Dispute firstDispute = disputes.open("fin-orig-unk", "原争议键首次写入", "dsp-orig-unk");
        assertEquals("dsp-orig-unk", firstDispute.requestId);
        assertEquals(EventResultDispute.OUTCOME_PENDING, disputes.recoverOriginal("dsp-orig-unk").outcome);
        assertSame(firstDispute, disputes.recoverOriginal("dsp-orig-unk").dispute);
    }
}
