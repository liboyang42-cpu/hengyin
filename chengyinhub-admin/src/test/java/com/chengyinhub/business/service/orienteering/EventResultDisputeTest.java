package com.chengyinhub.business.service.orienteering;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.orienteering.EventScoreVersioning.ResultVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        assertNull(dispute.originalRewardCenti);
        assertEquals(EventScoreVersioning.REWARD_KIND, dispute.rewardKind);
        OrienteeringEventException unconfirmed = assertThrows(OrienteeringEventException.class,
                () -> disputes.restoreOriginalReward("dsp-01xxxx", "rst-01xxxx"));
        assertEquals(EventResultDispute.ORIGINAL_REWARD_READ, unconfirmed.getWaitingCapability());
        assertEquals(EventResultDispute.STATUS_PENDING, disputes.readback("dsp-01xxxx").status());
        EventResultDispute confirmed = new EventResultDispute(scores, (memberId, sessionId) -> {
            java.util.Map<String, Object> fact = new java.util.LinkedHashMap<String, Object>();
            fact.put("state", "AVAILABLE");
            fact.put("originalRewardCenti", Integer.valueOf(2400));
            return fact;
        });
        confirmed.open("fin-disp-01", "计分条目被误记", "dsp-01xxxx");
        EventResultDispute.RestoreReceipt restore = confirmed.restoreOriginalReward("dsp-01xxxx", "rst-01xxxx", 7L, 30L);
        assertEquals("fin-disp-01", restore.originalResultRequestId);
        assertEquals(original.version, restore.originalResultVersion);
        assertEquals(Integer.valueOf(2400), restore.restoredRewardCenti);
        assertEquals(EventScoreVersioning.REWARD_KIND, restore.rewardKind);
        assertEquals(7L, restore.frozenRuleVersion);
        assertSame(restore, confirmed.readRestore("rst-01xxxx"));
        assertSame(original, scores.readback("fin-disp-01"));
        assertEquals(EventResultDispute.STATUS_RESTORED, confirmed.readback("dsp-01xxxx").status());
        assertEquals(EventResultDispute.STATUS_PENDING, disputes.readback("dsp-01xxxx").status());
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

    @Test
    void unusedRestoreOfMissingDisputeDoesNotOccupyThenUniqueRestoreApplies() {
        assertEquals(EventResultDispute.ORIGINAL_REWARD_READ,
                assertThrows(OrienteeringEventException.class,
                        () -> disputes.restoreOriginalReward("c04-dsp-missing", "c04-rst-never"))
                        .getWaitingCapability());
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> disputes.readRestore("c04-rst-never"))
                        .getErrorCode());
        EventResultDispute.Dispute opened = disputes.open("fin-disp-01", "原恢复键首次写入", "c04-dsp-rstx");
        assertEquals(EventResultDispute.STATUS_PENDING, opened.status());
        EventResultDispute confirmed = new EventResultDispute(scores, (memberId, sessionId) -> {
            java.util.Map<String, Object> fact = new java.util.LinkedHashMap<String, Object>();
            fact.put("state", "AVAILABLE");
            fact.put("originalRewardCenti", Integer.valueOf(2400));
            return fact;
        });
        confirmed.open("fin-disp-01", "原恢复键首次写入", "c04-dsp-rstx");
        EventResultDispute.RestoreReceipt first = confirmed.restoreOriginalReward(
                "c04-dsp-rstx", "c04-rst-never", 7L, 30L);
        assertEquals("c04-rst-never", first.requestId);
        assertEquals("fin-disp-01", first.originalResultRequestId);
        assertEquals(Integer.valueOf(2400), first.restoredRewardCenti);
        assertSame(first, confirmed.readRestore("c04-rst-never"));
        assertSame(first, confirmed.restoreOriginalReward("c04-dsp-rstx", "c04-rst-never", 7L, 30L));
        assertEquals(EventResultDispute.STATUS_RESTORED, confirmed.readback("c04-dsp-rstx").status());
        assertEquals(EventResultDispute.STATUS_PENDING, disputes.readback("c04-dsp-rstx").status());
    }

    @Test
    void recoverFromOriginalRewardReadDoesNotOccupyDisputeThenUniqueFactApplies() {
        EventResultDispute missing = new EventResultDispute(scores);
        OrienteeringEventException waiting = assertThrows(OrienteeringEventException.class,
                () -> missing.recoverFromOriginalRewardRead(7L, 30L));
        assertEquals(OrienteeringEventException.WAITING_SOURCE, waiting.getErrorCode());
        assertEquals(EventResultDispute.ORIGINAL_REWARD_READ, waiting.getWaitingCapability());
        assertNull(missing.recoverFromOriginalRewardRead(0L, 30L));
        assertNull(missing.recoverFromOriginalRewardRead(7L, 0L));

        Map<String, Map<String, Object>> facts = new LinkedHashMap<String, Map<String, Object>>();
        EventResultDispute.OriginalRewardReadSource source =
                new EventResultDispute.OriginalRewardReadSource() {
                    @Override
                    public Map<String, Object> readPersonalNaturalFinish(long memberId, long sessionId) {
                        return facts.get(memberId + ":" + sessionId);
                    }
                };
        EventResultDispute wired = new EventResultDispute(scores, source);
        assertNull(wired.recoverFromOriginalRewardRead(7L, 30L));
        assertThrows(OrienteeringEventException.class, () -> wired.readback("c04-reward-missing"));

        Map<String, Object> original = new LinkedHashMap<String, Object>();
        original.put("state", "PARTIAL");
        original.put("session_id", 30L);
        original.put("authority", "ORIGINAL_ISSUANCE_FACTS_AND_IMMUTABLE_ACTIONS");
        facts.put("7:30", original);
        EventResultDispute restarted = new EventResultDispute(scores, source);
        Map<String, Object> recovered = restarted.recoverFromOriginalRewardRead(7L, 30L);
        assertEquals("PARTIAL", recovered.get("state"));
        assertEquals(30L, recovered.get("session_id"));
        Map<String, Object> itemized = new LinkedHashMap<String, Object>();
        itemized.put("state", "PARTIAL");
        itemized.put("items", List.of(Integer.valueOf(1000), Integer.valueOf(1400)));
        EventResultDispute partial = new EventResultDispute(scores, (memberId, sessionId) -> itemized);
        partial.open("fin-disp-01", "分项不是总额", "dsp-partial");
        assertEquals(EventResultDispute.ORIGINAL_REWARD_READ,
                assertThrows(OrienteeringEventException.class,
                        () -> partial.restoreOriginalReward("dsp-partial", "rst-partial", 7L, 30L))
                        .getWaitingCapability());
        assertEquals(EventResultDispute.STATUS_PENDING, partial.readback("dsp-partial").status());
        assertEquals(0, restarted.recoverUnknown("c04-reward-missing").dispute == null ? 0 : 1);
        assertEquals(EventResultDispute.OUTCOME_NOT_FOUND,
                restarted.recoverUnknown("c04-reward-missing").outcome);
    }
}
