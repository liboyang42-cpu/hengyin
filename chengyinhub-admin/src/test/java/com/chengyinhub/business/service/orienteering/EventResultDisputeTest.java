package com.chengyinhub.business.service.orienteering;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.orienteering.EventScoreVersioning.ResultVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventResultDisputeTest {

    private EventScoreVersioning scores;
    private EventResultDispute disputes;
    private ResultVersion original;

    @BeforeEach
    void setUp() {
        PublishedRuleSelector selector = new PublishedRuleSelector();
        OrdinaryCityTimezoneQualifier qualifier = new OrdinaryCityTimezoneQualifier();
        IClubService clubs = mock(IClubService.class);
        IClubMemberService members = mock(IClubMemberService.class);
        when(clubs.selectClubById(11L)).thenReturn(TeamClubEligibilityAuthorityTest.club(11L, 501L));
        when(members.selectByClubAndMember(11L, 501L)).thenReturn(null);
        when(members.selectByClubAndMember(11L, 502L)).thenReturn(TeamClubEligibilityAuthorityTest.member(11L, 502L));
        TeamClubEligibilityAuthority authority = new TeamClubEligibilityAuthority(
                clubs, members, mock(IClubParticipationGuard.class));
        scores = new EventScoreVersioning();
        disputes = new EventResultDispute(scores);
        CmsTopic course = PublishedRuleSelectorTest.publishedCity(7L, 9L);
        var frozen = selector.select(course);
        var registration = authority.register(course, TeamClubEligibilityAuthority.EntryMode.TEAM,
                List.of(501L, 502L), List.of(11L), "reg-disp-01");
        Date start = OrdinaryCityTimezoneQualifierTest.at(2026, 10, 24, 10, 0);
        var safety = qualifier.confirm(frozen, OrdinaryCityTimezoneQualifier.CITY_ZONE,
                OrdinaryCityTimezoneQualifierTest.adultDayItems(), null, start,
                OrdinaryCityTimezoneQualifierTest.at(2026, 10, 24, 15, 0), start,
                OrdinaryCityTimezoneQualifierTest.at(1990, 1, 1, 0, 0), false);
        var run = scores.start(registration, frozen, safety, 7L, "start-disp01");
        scores.appendScore(run.id, 7000, "TASK", "TASK_DONE", "scr-disp-01");
        original = scores.finish(run.id, "fin-disp-01");
    }

    @Test
    void restorePointsAtOriginalFinishRequestAndDoesNotInventANewAward() {
        EventResultDispute.Dispute dispute = disputes.open("fin-disp-01", "计分条目被误记", "dsp-01xxxx");
        assertEquals("fin-disp-01", dispute.originalResultRequestId);
        assertEquals(7000, dispute.originalRewardCenti);
        assertEquals(EventScoreVersioning.REWARD_KIND, dispute.rewardKind);
        EventResultDispute.RestoreReceipt restore = disputes.restoreOriginalReward("dsp-01xxxx", "rst-01xxxx");
        assertEquals("fin-disp-01", restore.originalResultRequestId);
        assertEquals(original.version, restore.originalResultVersion);
        assertEquals(original.originalRewardCenti, restore.restoredRewardCenti);
        assertEquals(EventScoreVersioning.REWARD_KIND, restore.rewardKind);
        assertEquals(7L, restore.frozenRuleVersion);
        assertSame(restore, disputes.readRestore("rst-01xxxx"));
        assertSame(original, scores.readback("fin-disp-01"));
        assertEquals(EventResultDispute.STATUS_RESTORED, disputes.readback("dsp-01xxxx").status());
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> disputes.readRestore("rst-missing")).getErrorCode());
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueOpenApplies() {
        EventResultDispute.OriginalReceipt blank = disputes.recoverOriginal(null);
        assertNull(blank.originalRequestId);
        assertEquals(EventResultDispute.OUTCOME_NOT_FOUND, blank.outcome);
        assertEquals(false, blank.mintedNewRequest);
        EventResultDispute.OriginalReceipt neverDispute = disputes.recoverOriginal("c04-dsp-never");
        assertEquals("c04-dsp-never", neverDispute.originalRequestId);
        assertEquals(EventResultDispute.OUTCOME_NOT_FOUND, neverDispute.outcome);
        assertEquals(false, neverDispute.mintedNewRequest);
        assertNull(neverDispute.dispute);
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> disputes.readback("c04-dsp-never")).getErrorCode());
        EventResultDispute.Dispute first = disputes.open("fin-disp-01", "原争议键首次写入", "c04-dsp-never");
        assertEquals("c04-dsp-never", first.requestId);
        assertEquals(EventResultDispute.OUTCOME_PENDING, disputes.recoverOriginal("c04-dsp-never").outcome);
        assertSame(first, disputes.recoverOriginal("c04-dsp-never").dispute);
        assertSame(first, disputes.open("fin-disp-01", "原争议键首次写入", "c04-dsp-never"));
    }
}
