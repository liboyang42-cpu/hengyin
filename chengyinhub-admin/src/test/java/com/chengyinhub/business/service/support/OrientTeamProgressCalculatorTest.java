package com.chengyinhub.business.service.support;

import com.chengyinhub.business.service.support.OrientTeamProgressCalculator.TeamProgress;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrientTeamProgressCalculatorTest {

    private static final List<Long> TASKS = List.of(11L, 12L, 13L);

    @Test
    void requiredIsRecomputedFromActiveMembers() {
        assertEquals(3, OrientTeamProgressCalculator.required(3, 1.0));
        assertEquals(2, OrientTeamProgressCalculator.required(3, 0.5));
        assertEquals(1, OrientTeamProgressCalculator.required(1, 0.5));
        assertEquals(2, OrientTeamProgressCalculator.required(4, 0.5));
        assertEquals(0, OrientTeamProgressCalculator.required(0, 1.0));
        assertEquals(3, OrientTeamProgressCalculator.required(3, 7.0), "非法比例按全员");
    }

    @Test
    void memberLeavingLowersThresholdAndTheirCompletionsStopCounting() {
        Map<Long, Map<Long, Date>> facts = new HashMap<>();
        facts.put(1L, Map.of(11L, t(1), 12L, t(2)));
        facts.put(2L, Map.of(11L, t(3)));
        facts.put(3L, Map.of(12L, t(4), 13L, t(5))); // 3 号之后退队

        TeamProgress before = OrientTeamProgressCalculator.compute(7L, List.of(1L, 2L, 3L), TASKS, facts, 1.0);
        assertEquals(3, before.required);
        assertEquals(0, before.teamDoneCount, "全员阈值下没有任何任务三人都完成");

        TeamProgress after = OrientTeamProgressCalculator.compute(7L, List.of(1L, 2L), TASKS, facts, 1.0);
        assertEquals(2, after.required, "退队后阈值按在岗 2 人重算");
        assertEquals(1, after.teamDoneCount, "只有 11 被在岗二人都完成");
        assertTrue(after.tasks.get(0).teamDone);
        assertFalse(after.tasks.get(1).teamDone, "12 只有退队成员和 1 号完成,退队成员不计");
        assertEquals(1, after.tasks.get(1).completedCount);
        assertFalse(after.tasks.get(2).teamDone, "13 只有退队成员完成,不能因此算队伍完成");
        assertEquals(2, after.excludedDepartedFacts);
        assertEquals(t(3), after.tasks.get(0).teamDoneTime, "队伍完成时刻 = 第 required 个在岗成员完成的时刻");
    }

    @Test
    void cannotPadThresholdByRecruitingSomeoneWhoAlreadyFinishedThenLeaving() {
        Map<Long, Map<Long, Date>> facts = new HashMap<>();
        facts.put(1L, Map.of(11L, t(1)));
        facts.put(9L, Map.of(11L, t(2), 12L, t(2), 13L, t(2)));
        TeamProgress withHelper = OrientTeamProgressCalculator.compute(1L, List.of(1L, 9L), TASKS, facts, 0.5);
        assertEquals(3, withHelper.teamDoneCount);
        TeamProgress helperGone = OrientTeamProgressCalculator.compute(1L, List.of(1L), TASKS, facts, 0.5);
        assertEquals(1, helperGone.teamDoneCount, "帮手退队后,他完成的任务不再算队伍完成");
    }

    @Test
    void rankingUsesDoneCountThenEarliestFinishAndSkipsEmptyTeams() {
        Map<Long, Map<Long, Date>> facts = new HashMap<>();
        facts.put(1L, Map.of(11L, t(10), 12L, t(20)));
        facts.put(2L, Map.of(11L, t(5), 12L, t(15)));
        facts.put(3L, Map.of(11L, t(1)));
        TeamProgress a = OrientTeamProgressCalculator.compute(100L, List.of(1L), TASKS, facts, 1.0);
        TeamProgress b = OrientTeamProgressCalculator.compute(200L, List.of(2L), TASKS, facts, 1.0);
        TeamProgress c = OrientTeamProgressCalculator.compute(300L, List.of(3L), TASKS, facts, 1.0);
        TeamProgress empty = OrientTeamProgressCalculator.compute(400L, List.of(), TASKS, facts, 1.0);
        List<TeamProgress> ranked = OrientTeamProgressCalculator.rank(List.of(a, b, c, empty));
        assertEquals(List.of(200L, 100L, 300L), ranked.stream().map(p -> p.teamId).toList());

        // 200 队唯一成员退队 → 在岗 0 人,不再参与排名,其余名次顺延
        TeamProgress bLeft = OrientTeamProgressCalculator.compute(200L, List.of(), TASKS, facts, 1.0);
        assertEquals(List.of(100L, 300L),
                OrientTeamProgressCalculator.rank(List.of(a, bLeft, c)).stream().map(p -> p.teamId).toList());
    }

    private static Date t(int minute) {
        return new Date(1_790_000_000_000L + minute * 60_000L);
    }
}
