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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
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
        assertEquals(15000, finished.originalRewardCenti);
        assertEquals(EventScoreVersioning.REWARD_KIND, finished.rewardKind);
        assertEquals(7L, finished.frozenRuleVersion);
        assertEquals(frozen.digest, finished.frozenRuleDigest);
        assertEquals(3, finished.evidence.size());
        assertSame(finished, scores.readback("fin-journey1"));
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> scores.readback("fin-invented")).getErrorCode());

        EventResultDispute.RestoreReceipt restore = disputes.restoreOriginalReward(
                disputes.open("fin-journey1", "完赛计分待复核", "dsp-journey").requestId, "rst-journey");
        assertEquals("fin-journey1", restore.originalResultRequestId);
        assertEquals(finished.originalRewardCenti, restore.restoredRewardCenti);
        assertSame(finished, scores.readback("fin-journey1"));
    }
}
