package com.chengyinhub.web.controller.api;

import org.junit.jupiter.api.Test;

import static com.chengyinhub.web.controller.api.ApiRoamController.crossedMilestone;
import static com.chengyinhub.web.controller.api.ApiRoamController.tileXpWithDailyCap;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2 漫游结算纯逻辑:格子探索值日限 200 跨会话累计 + 版图里程碑跨阈。
 */
class RoamXpTest {

    private static final int LIMIT = 200;
    private static final int[] M = { 50, 200, 1000 };

    @Test
    void tileXp_normalUnderCap() {
        assertEquals(4, tileXpWithDailyCap(4, 4, LIMIT), "今日 4 格全发");
        assertEquals(30, tileXpWithDailyCap(30, 30, LIMIT), "今日 30 格全发");
    }

    @Test
    void tileXp_cappedAt200InSingleSession() {
        assertEquals(200, tileXpWithDailyCap(250, 250, LIMIT), "一会话点 250 格,只发 200");
    }

    @Test
    void tileXp_dailyCapAcrossSessions() {
        // 今日已点 180(prior),本会话再点 30 → 今日 210;只剩 20 额度
        assertEquals(20, tileXpWithDailyCap(210, 30, LIMIT), "跨会话:今日剩 20 额度");
        // 今日之前已达 200,本会话再点 50 → 0
        assertEquals(0, tileXpWithDailyCap(250, 50, LIMIT), "今日已满额,本会话 0");
    }

    @Test
    void tileXp_edgeAndDirty() {
        assertEquals(0, tileXpWithDailyCap(0, 0, LIMIT), "无格 0");
        assertEquals(5, tileXpWithDailyCap(3, 5, LIMIT), "脏数据 today<session:按 session 兜底 5");
    }

    @Test
    void milestone_crossHighest() {
        assertEquals(50, crossedMilestone(0, 50, M), "刚到 50");
        assertEquals(50, crossedMilestone(48, 60, M), "跨过 50");
        assertEquals(200, crossedMilestone(0, 250, M), "一跃过 50 和 200,取最高 200");
        assertEquals(1000, crossedMilestone(999, 1001, M), "跨 1000");
    }

    @Test
    void milestone_noCross() {
        assertEquals(0, crossedMilestone(200, 300, M), "200 已得、1000 未到 → 不发");
        assertEquals(0, crossedMilestone(10, 20, M), "没到任何阈 → 不发");
        assertEquals(0, crossedMilestone(1000, 1200, M), "已封顶 → 不发");
    }
}
