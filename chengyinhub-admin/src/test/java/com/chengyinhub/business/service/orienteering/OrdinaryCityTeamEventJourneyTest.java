package com.chengyinhub.business.service.orienteering;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OrientScoreEntry;
import com.chengyinhub.business.mapper.OrientScoringMapper;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IOrientScoringService;
import com.chengyinhub.business.service.support.OrientSessionException;
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

/**
 * 首个可跑旅程：已发布普通城市定向 + 真实多俱乐部 TEAM 资格 → 冻结已发布规则开局 →
 * 任务证据与有符号计分 → finish 写入版本化成绩 → 按原请求回读。
 */
class OrdinaryCityTeamEventJourneyTest {

    private static final long CLUB_A = 11L;
    private static final long CLUB_B = 22L;

    private PublishedRuleSelector selector;
    private OrdinaryCityTimezoneQualifier qualifier;
    private TeamClubEligibilityAuthority authority;
    private EventScoreVersioning scores;
    private EventResultDispute disputes;
    private IOrientScoringService writer;
    private OrientScoringMapper mapper;

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
        writer = mock(IOrientScoringService.class);
        mapper = mock(OrientScoringMapper.class);
        when(writer.appendEntry(eq(88L), eq("TASK"), eq(10000), eq("TASK_DONE"), eq("PROGRESS"),
                eq("event-score:scr-jour-01"), isNull(), eq("scr-jour-01"))).thenReturn(row("scr-jour-01", 10000));
        when(writer.appendEntry(eq(88L), eq("TASK"), eq(7000), eq("TASK_DONE"), eq("PROGRESS"),
                eq("event-score:scr-jour-02"), isNull(), eq("scr-jour-02"))).thenReturn(row("scr-jour-02", 7000));
        when(writer.appendEntry(eq(88L), eq("PENALTY"), eq(-2000), eq("LATE_PENALTY"), eq("PROGRESS"),
                eq("event-score:scr-jour-03"), isNull(), eq("scr-jour-03"))).thenReturn(row("scr-jour-03", -2000));
        when(mapper.selectEntryByIdem(88L, "scr-jour-01")).thenReturn(row("scr-jour-01", 10000));
        scores = new EventScoreVersioning(writer, mapper, null);
        disputes = new EventResultDispute(scores);
    }

    @Test
    void publishedCityTeamJourneyFreezesRuleSevenAndReadbackReturnsOriginalFinish() {
        CmsTopic course = PublishedRuleSelectorTest.publishedCity(7L, 9L);
        FrozenPublishedRule frozen = selector.select(course);
        assertEquals(7L, frozen.publishedConfigVersion);
        assertNotEquals(course.getConfigVersion().longValue(), frozen.publishedConfigVersion);

        Registration registration = authority.register(course, TeamClubEligibilityAuthority.EntryMode.TEAM,
                List.of(501L, 502L, 602L), List.of(CLUB_A, CLUB_B), "reg-journey1");
        assertSame(registration, authority.readback("reg-journey1"));
        assertEquals(CLUB_A, registration.memberClubId.get(501L));
        assertEquals(CLUB_B, registration.memberClubId.get(602L));

        Date startAt = OrdinaryCityTimezoneQualifierTest.at(2026, 10, 24, 10, 0);
        qualifier.qualify(course, TeamClubEligibilityAuthorityTest.club(CLUB_A, 501L), null);
        SafetyConfirmation safety = qualifier.confirm(frozen, OrdinaryCityTimezoneQualifier.CITY_ZONE,
                OrdinaryCityTimezoneQualifierTest.adultDayItems(), null, startAt,
                OrdinaryCityTimezoneQualifierTest.at(2026, 10, 24, 15, 0), startAt,
                OrdinaryCityTimezoneQualifierTest.at(1990, 1, 1, 0, 0), false);

        EventScoreVersioning.EventRun run = scores.start(registration, frozen, safety, frozen.publishedConfigVersion,
                "start-journy");
        assertEquals(7L, run.frozen.publishedConfigVersion);
        scores.recordEvidence(run.id, 41L, "SCAN", "task:41:scan:alpha", "evd-jour-01");
        scores.recordEvidence(run.id, 42L, "PHOTO", "task:42:photo:beta", "evd-jour-02");
        scores.recordEvidence(run.id, 43L, "QUIZ", "task:43:quiz:gamma", "evd-jour-03");
        scores.appendScore(run.id, 10000, "TASK", "TASK_DONE", "scr-jour-01");
        scores.appendScore(run.id, 7000, "TASK", "TASK_DONE", "scr-jour-02");
        scores.appendScore(run.id, -2000, "PENALTY", "LATE_PENALTY", "scr-jour-03");

        ResultVersion finished = scores.finish(run.id, "fin-journey1");
        assertEquals(1, finished.version);
        assertEquals(15000, finished.scoreCenti);
        assertNull(finished.originalRewardCenti);
        assertEquals(EventScoreVersioning.REWARD_KIND, finished.rewardKind);
        assertEquals(7L, finished.frozenRuleVersion);
        assertEquals(frozen.digest, finished.frozenRuleDigest);
        assertEquals(3, finished.evidence.size());
        assertSame(finished, scores.readback("fin-journey1"));
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> scores.readback("fin-invented")).getErrorCode());

        OrienteeringEventException unconfirmed = assertThrows(OrienteeringEventException.class,
                () -> disputes.restoreOriginalReward(
                        disputes.open("fin-journey1", "完赛计分待复核", "dsp-journey").requestId, "rst-journey"));
        assertEquals(EventResultDispute.ORIGINAL_REWARD_READ, unconfirmed.getWaitingCapability());
        assertEquals(EventResultDispute.STATUS_PENDING, disputes.readback("dsp-journey").status());
        assertSame(finished, scores.readback("fin-journey1"));
        assertEquals("orientation.policyBinding", EventScoreVersioning.POLICY_BINDING);

        assertEquals(EventScoreVersioning.AUTHORIZED_SESSION,
                assertThrows(OrienteeringEventException.class,
                        () -> scores.persistOriginal("fin-journey1", (memberId, originalSessionId) -> Long.valueOf(99L),
                                7L, 88L)).getWaitingCapability());
        verify(writer, never()).appendEntry(any(), any(), anyInt(), any(), any(), any(), any(), any());
        List<OrientScoreEntry> persisted = scores.persistOriginal("fin-journey1",
                (memberId, originalSessionId) -> originalSessionId, 7L, 88L);
        assertEquals(3, persisted.size());
        assertEquals("scr-jour-01", persisted.get(0).getIdempotencyKey());
        verify(writer).appendEntry(eq(88L), eq("TASK"), eq(10000), eq("TASK_DONE"), eq("PROGRESS"),
                eq("event-score:scr-jour-01"), isNull(), eq("scr-jour-01"));

        EventScoreVersioning restarted = new EventScoreVersioning(writer, mapper, 88L);
        assertEquals(EventScoreVersioning.OUTCOME_NOT_FOUND, restarted.recoverOriginal("fin-journey1").outcome);
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> restarted.readback("fin-journey1")).getErrorCode());
        assertEquals(10000, restarted.recoverFromLedger("scr-jour-01").getDeltaCenti());

        Map<String, Object> original = new LinkedHashMap<String, Object>();
        original.put("replayed", Boolean.TRUE);
        original.put("sessionId", Long.valueOf(88L));
        original.put("configVersion", Long.valueOf(7L));
        original.put("scoreCenti", Integer.valueOf(15000));
        Map<String, Object> sameSession = scores.recoverFromSessionReceipt(7L, 88L, "fin-journey1",
                (memberId, sessionId, key) -> sessionId.longValue() == 88L ? original
                        : failNotFound());
        assertEquals(Boolean.TRUE, sameSession.get("replayed"));
        assertEquals(Long.valueOf(88L), sameSession.get("sessionId"));
        assertEquals(Integer.valueOf(15000), sameSession.get("scoreCenti"));
        assertEquals(Long.valueOf(7L), sameSession.get("configVersion"));
        assertNull(sameSession.get("policy_key"));
        assertNull(sameSession.get("sourceIdentity"));
        assertNull(scores.recoverFromSessionReceipt(7L, 99L, "fin-journey1",
                (memberId, sessionId, key) -> failNotFound()));
    }

    private static OrientScoreEntry row(String key, int deltaCenti) {
        OrientScoreEntry entry = new OrientScoreEntry();
        entry.setSessionId(88L);
        entry.setIdempotencyKey(key);
        entry.setDeltaCenti(deltaCenti);
        return entry;
    }

    private static Map<String, Object> failNotFound() {
        throw new OrientSessionException("ORIENT_RECEIPT_NOT_FOUND", 404, "没有这次请求的回执");
    }
}
