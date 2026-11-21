package com.chengyinhub.business.dailyloop;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.support.StreakLedger;
import com.chengyinhub.business.service.support.StreakLedger.Outcome;
import com.chengyinhub.business.service.support.StreakLedger.Policy;
import com.chengyinhub.business.service.support.StreakLedger.State;
import com.chengyinhub.business.service.support.StreakLedger.View;

/** P7 GAM-04 / GRO-07:连续天数 + 补签卡规则。 */
class StreakLedgerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);
    private static final Policy WEEKLY_TWO = new Policy(7, 2);

    @Test
    void firstActivityStartsAtOne() {
        Outcome o = StreakLedger.onActive(new State(null, 0, 0), TODAY, WEEKLY_TWO);
        assertEquals(1, o.streak());
        assertTrue(o.advanced());
        assertFalse(o.freezeEarned());
    }

    @Test
    void sameDayDoesNotAdvanceOrEarn() {
        Outcome o = StreakLedger.onActive(new State(TODAY, 7, 0), TODAY, WEEKLY_TWO);
        assertEquals(7, o.streak());
        assertFalse(o.advanced());
        assertEquals(0, o.freezes(), "同一天再活跃不能再发一张卡");
    }

    @Test
    void consecutiveDayAdvancesAndEarnsFreezeOnEverySeventhDay() {
        Outcome o = StreakLedger.onActive(new State(TODAY.minusDays(1), 6, 0), TODAY, WEEKLY_TWO);
        assertEquals(7, o.streak());
        assertTrue(o.freezeEarned());
        assertEquals(1, o.freezes());

        Outcome o14 = StreakLedger.onActive(new State(TODAY.minusDays(1), 13, 1), TODAY, WEEKLY_TWO);
        assertEquals(2, o14.freezes());
    }

    @Test
    void freezesAreCappedAtMax() {
        Outcome o = StreakLedger.onActive(new State(TODAY.minusDays(1), 20, 2), TODAY, WEEKLY_TWO);
        assertEquals(21, o.streak());
        assertFalse(o.freezeEarned());
        assertEquals(2, o.freezes());
    }

    @Test
    void oneMissedDayIsCoveredAutomaticallyByAFreeze() {
        Outcome o = StreakLedger.onActive(new State(TODAY.minusDays(2), 9, 1), TODAY, WEEKLY_TWO);
        assertEquals(10, o.streak());
        assertEquals(1, o.freezesUsed());
        assertEquals(0, o.freezes());
    }

    @Test
    void twoMissedDaysNeedTwoFreezes() {
        Outcome covered = StreakLedger.onActive(new State(TODAY.minusDays(3), 9, 2), TODAY, WEEKLY_TWO);
        assertEquals(10, covered.streak());
        assertEquals(2, covered.freezesUsed());

        Outcome broken = StreakLedger.onActive(new State(TODAY.minusDays(3), 9, 1), TODAY, WEEKLY_TWO);
        assertEquals(1, broken.streak());
        assertEquals(0, broken.freezesUsed());
        assertEquals(1, broken.freezes(), "断了时卡不被吞掉");
    }

    @Test
    void noFreezePolicyNeverEarns() {
        Outcome o = StreakLedger.onActive(new State(TODAY.minusDays(1), 6, 0), TODAY, new Policy(0, 2));
        assertEquals(0, o.freezes());
        Outcome o2 = StreakLedger.onActive(new State(TODAY.minusDays(1), 6, 0), TODAY, new Policy(7, 0));
        assertEquals(0, o2.freezes());
    }

    @Test
    void clockGoingBackwardsIsTreatedAsSameDay() {
        Outcome o = StreakLedger.onActive(new State(TODAY.plusDays(1), 4, 0), TODAY, WEEKLY_TWO);
        assertEquals(4, o.streak());
        assertFalse(o.advanced());
    }

    @Test
    void viewShowsActiveTodayAtRiskProtectedAndBroken() {
        View active = StreakLedger.view(new State(TODAY, 5, 1), TODAY, WEEKLY_TWO);
        assertEquals(5, active.currentStreak());
        assertTrue(active.activeToday());
        assertFalse(active.atRisk());

        View risk = StreakLedger.view(new State(TODAY.minusDays(1), 5, 1), TODAY, WEEKLY_TWO);
        assertEquals(5, risk.currentStreak());
        assertTrue(risk.atRisk());
        assertEquals(1, risk.freezesAvailable());

        View covered = StreakLedger.view(new State(TODAY.minusDays(2), 5, 1), TODAY, WEEKLY_TWO);
        assertEquals(5, covered.currentStreak(), "漏的那天由卡补上");
        assertEquals(0, covered.freezesAvailable());
        assertEquals(1, covered.freezesPending());
        assertTrue(covered.atRisk());

        View broken = StreakLedger.view(new State(TODAY.minusDays(3), 5, 1), TODAY, WEEKLY_TWO);
        assertEquals(0, broken.currentStreak());
        assertTrue(broken.broken());
        assertFalse(broken.atRisk());

        View none = StreakLedger.view(new State(null, 0, 0), TODAY, WEEKLY_TWO);
        assertEquals(0, none.currentStreak());
        assertFalse(none.atRisk());
    }

    @Test
    void nextLadderStep() {
        int[] ladder = { 3, 7, 14, 30 };
        assertEquals(3, StreakLedger.nextLadderStep(0, ladder));
        assertEquals(7, StreakLedger.nextLadderStep(3, ladder));
        assertEquals(30, StreakLedger.nextLadderStep(14, ladder));
        assertNull(StreakLedger.nextLadderStep(30, ladder));
    }
}
