package com.chengyinhub.business.service;

import com.chengyinhub.business.service.support.BingoCard;
import com.chengyinhub.business.service.support.BingoCells;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宾果九格的达成判定。
 *
 * <p>九格全部只依据「这个主题下的节点完成记录」—— 分享、步数、印章那些格子
 * 事件源在别的模块接不上,放进来就是永远填不满的假保证。
 */
class BingoCellsTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 2026-09-07 是周一。 */
    private Date at(int day, int hour) {
        return Date.from(LocalDateTime.of(2026, 9, day, hour, 0).atZone(ZONE).toInstant());
    }

    private BingoCells.Progress progress(int nodeCount, Date... times) {
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= nodeCount; i++) ids.add(i);
        return new BingoCells.Progress(ids, Arrays.asList(times));
    }

    private boolean filled(int mask, String code) {
        return BingoCells.cells().stream()
                .filter(c -> c.getCode().equals(code))
                .findFirst()
                .map(c -> BingoCard.isFilled(mask, c.getPosition()))
                .orElse(false);
    }

    @Test
    void nineCellsMapOntoTheNineBoardPositions() {
        List<BingoCells.Cell> cells = BingoCells.cells();
        assertEquals(BingoCard.SIZE, cells.size());
        for (int i = 0; i < cells.size(); i++) {
            assertEquals(i, cells.get(i).getPosition(), "位序必须与 BingoCard 一一对应");
        }
    }

    @Test
    void noProgressFillsNothing() {
        assertEquals(0, BingoCells.evaluate(null));
        assertEquals(0, BingoCells.evaluate(progress(0)));
    }

    @Test
    void nodeCountCellsUnlockInOrder() {
        int one = BingoCells.evaluate(progress(1, at(7, 14)));
        assertTrue(filled(one, "NODE_1"));
        assertFalse(filled(one, "NODE_3"));

        int five = BingoCells.evaluate(progress(5, at(7, 14)));
        assertTrue(filled(five, "NODE_3"));
        assertTrue(filled(five, "NODE_5"));
        assertFalse(filled(five, "NODE_8"));
    }

    @Test
    void burstNeedsThreeInTheSameDay() {
        // 三次分散在三天:不算爆发
        int spread = BingoCells.evaluate(progress(3, at(7, 14), at(8, 14), at(9, 14)));
        assertFalse(filled(spread, "BURST_3"));

        // 同一天三次:算
        int burst = BingoCells.evaluate(progress(3, at(7, 10), at(7, 14), at(7, 18)));
        assertTrue(filled(burst, "BURST_3"));
    }

    @Test
    void twoDaysAndStreakAreDifferentThings() {
        // 周一 + 周三:两天各来一次成立,连续两天不成立
        int gap = BingoCells.evaluate(progress(2, at(7, 14), at(9, 14)));
        assertTrue(filled(gap, "DAYS_2"));
        assertFalse(filled(gap, "STREAK_2"), "隔了一天不算连续");

        // 周一 + 周二:两个都成立
        int streak = BingoCells.evaluate(progress(2, at(7, 14), at(8, 14)));
        assertTrue(filled(streak, "DAYS_2"));
        assertTrue(filled(streak, "STREAK_2"));
    }

    @Test
    void weekendCellNeedsAnActualWeekend() {
        // 9/7 周一 ~ 9/11 周五
        int weekday = BingoCells.evaluate(progress(2, at(7, 14), at(11, 14)));
        assertFalse(filled(weekday, "WEEKEND"));

        // 9/12 是周六
        int weekend = BingoCells.evaluate(progress(1, at(12, 14)));
        assertTrue(filled(weekend, "WEEKEND"));

        // 9/13 是周日
        assertTrue(filled(BingoCells.evaluate(progress(1, at(13, 14))), "WEEKEND"));
    }

    @Test
    void earlyCellUsesShanghaiNoonNotUtc() {
        // 上海时间 11 点算「中午前」
        assertTrue(filled(BingoCells.evaluate(progress(1, at(7, 11))), "EARLY"));
        // 13 点不算
        assertFalse(filled(BingoCells.evaluate(progress(1, at(7, 13))), "EARLY"));
        // 边界:正午 12 点不算「中午前」
        assertFalse(filled(BingoCells.evaluate(progress(1, at(7, 12))), "EARLY"));
    }

    /** 同一个节点完成两次(重放)不该把节点数算成 2。 */
    @Test
    void repeatedNodeIdsCountOnce() {
        BingoCells.Progress replayed =
                new BingoCells.Progress(Arrays.asList(1L, 1L, 1L), Arrays.asList(at(7, 14)));
        int mask = BingoCells.evaluate(replayed);

        assertTrue(filled(mask, "NODE_1"));
        assertFalse(filled(mask, "NODE_3"), "同一个节点重复记录不该凑数");
    }

    /**
     * evaluate 返回的是「当前该是什么样」,不是「这次新填了哪些」。
     *
     * <p>所以某次事件漏掉了,下一次完成会把它补上,不需要补偿任务。
     */
    @Test
    void evaluateIsAbsoluteSoMissedEventsSelfHeal() {
        int afterThree = BingoCells.evaluate(progress(3, at(7, 10), at(7, 14), at(7, 18)));
        // 假设第二次完成时的事件丢了,库里只有第一格
        int stale = BingoCard.fill(0, 0);
        int healed = stale | afterThree;

        assertTrue(filled(healed, "NODE_3"), "下一次完成会把漏掉的格补上");
        assertTrue(filled(healed, "BURST_3"));
    }

    @Test
    void fullBoardIsReachable() {
        // 8 个点、跨周末、连续两天、有一天三连、有一次中午前
        int mask = BingoCells.evaluate(progress(8,
                at(11, 10), at(11, 14), at(11, 18),   // 周五三连,含中午前
                at(12, 14), at(13, 14),               // 周六周日,连续
                at(14, 14), at(15, 14), at(16, 14)));

        assertTrue(BingoCard.isFull(mask), "九格应当可达,填不满的格子就是死格");
    }
}
