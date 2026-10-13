package com.chengyinhub.business.service;

import com.chengyinhub.business.service.support.BingoCard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宾果九宫格的位运算逻辑。
 *
 * <p>格子位序:
 * <pre>
 *   0 1 2
 *   3 4 5
 *   6 7 8
 * </pre>
 */
class BingoCardTest {

    @Test
    void fillingIsIdempotentSoReplayedEventsAreHarmless() {
        int mask = BingoCard.fill(0, 4);
        assertEquals(mask, BingoCard.fill(mask, 4), "同一个事件重放,格子状态不该变");
        assertEquals(1, BingoCard.filledCount(mask));
    }

    @Test
    void rejectsPositionsOutsideTheGrid() {
        assertThrows(IllegalArgumentException.class, () -> BingoCard.fill(0, 9));
        assertThrows(IllegalArgumentException.class, () -> BingoCard.fill(0, -1));
    }

    @Test
    void countsRowsColumnsAndDiagonals() {
        // 第一行 0,1,2
        int topRow = BingoCard.fill(BingoCard.fill(BingoCard.fill(0, 0), 1), 2);
        assertEquals(1, BingoCard.lineCount(topRow), "一横算一条");

        // 第一列 0,3,6
        int leftCol = BingoCard.fill(BingoCard.fill(BingoCard.fill(0, 0), 3), 6);
        assertEquals(1, BingoCard.lineCount(leftCol), "一竖算一条");

        // 主对角 0,4,8
        int diag = BingoCard.fill(BingoCard.fill(BingoCard.fill(0, 0), 4), 8);
        assertEquals(1, BingoCard.lineCount(diag), "斜线也算");

        // 副对角 2,4,6
        int anti = BingoCard.fill(BingoCard.fill(BingoCard.fill(0, 2), 4), 6);
        assertEquals(1, BingoCard.lineCount(anti));
    }

    @Test
    void countsCrossingLinesSeparately() {
        // 第一行 + 第一列,共用格子 0,应该算两条
        int cross = 0;
        for (int p : new int[] { 0, 1, 2, 3, 6 }) cross = BingoCard.fill(cross, p);
        assertEquals(2, BingoCard.lineCount(cross));
    }

    @Test
    void twoOfThreeIsNotALine() {
        int almost = BingoCard.fill(BingoCard.fill(0, 0), 1);
        assertEquals(0, BingoCard.lineCount(almost));
    }

    @Test
    void fullBoardIsRecognised() {
        int mask = 0;
        for (int i = 0; i < BingoCard.SIZE; i++) mask = BingoCard.fill(mask, i);

        assertTrue(BingoCard.isFull(mask));
        assertEquals(9, BingoCard.filledCount(mask));
        assertEquals(8, BingoCard.lineCount(mask), "满格时八条线全成立");
    }

    /**
     * ★ 同一档只该发一次,靠 rewardMask 记账,不靠调用方。
     *
     * <p>断言用「位」不用「全等」:每格自己的奖也挤在同一个 mask 里(第 3 位起九位),
     * 所以填一格解锁的是「首格奖 + 那一格的奖」两位。写全等就等于断言
     * <b>这个 mask 里只能有三档奖</b> —— 一加每格奖就红,而红的是断言不是实现。
     */
    @Test
    void eachRewardTierUnlocksOnlyOnce() {
        int filled = BingoCard.fill(0, 0);
        int rewarded = 0;

        int first = BingoCard.newlyUnlocked(filled, rewarded);
        assertTrue((first & BingoCard.REWARD_FIRST) != 0, "填出第一格解锁首格奖");
        assertTrue((first & BingoCard.cellRewardBit(0)) != 0, "第一格自己的奖也一起解锁");
        rewarded = BingoCard.markRewarded(rewarded, first);

        // 再填一格,首格奖不该重复解锁 —— 但第 2 格自己的奖是新的
        filled = BingoCard.fill(filled, 1);
        int second = BingoCard.newlyUnlocked(filled, rewarded);
        assertEquals(0, second & BingoCard.REWARD_FIRST, "首格奖已发过,不再解锁");
        assertTrue((second & BingoCard.cellRewardBit(1)) != 0, "第 2 格自己的奖是新解锁的");
        rewarded = BingoCard.markRewarded(rewarded, second);

        // 连成一线,三连奖解锁
        filled = BingoCard.fill(filled, 2);
        int line = BingoCard.newlyUnlocked(filled, rewarded);
        assertTrue((line & BingoCard.REWARD_LINE) != 0);
        rewarded = BingoCard.markRewarded(rewarded, line);
        assertEquals(0, BingoCard.newlyUnlocked(filled, rewarded), "全都发过了,不再解锁任何一位");
    }

    /**
     * 一次填格同时凑满一线和满格时,两档一起解锁。
     *
     * <p>最后一格必然同时触发多条件,漏掉一档就是少发一份奖。
     */
    @Test
    void lastCellCanUnlockSeveralTiersAtOnce() {
        int filled = 0;
        for (int i = 0; i < 8; i++) filled = BingoCard.fill(filled, i);
        // 前八格已经连出线了,先把线奖与首格奖记成已发
        int rewarded = BingoCard.markRewarded(0,
                BingoCard.newlyUnlocked(filled, 0));

        filled = BingoCard.fill(filled, 8);
        int unlocked = BingoCard.newlyUnlocked(filled, rewarded);

        assertTrue((unlocked & BingoCard.REWARD_FULL) != 0, "最后一格解锁满格奖");
        assertTrue((unlocked & BingoCard.cellRewardBit(8)) != 0, "第 9 格自己的奖也在这一下解锁");
        assertEquals(0, unlocked & (BingoCard.REWARD_FIRST | BingoCard.REWARD_LINE),
                "首格奖和线奖上一步已发过,不能重复解锁");
        assertTrue(BingoCard.isFull(filled));
    }

    @Test
    void emptyBoardUnlocksNothing() {
        assertEquals(0, BingoCard.newlyUnlocked(0, 0));
        assertEquals(0, BingoCard.filledCount(0));
        assertFalse(BingoCard.isFull(0));
    }

    @Test
    void filledPositionsAreReportedInOrder() {
        int mask = 0;
        for (int p : new int[] { 4, 0, 8 }) mask = BingoCard.fill(mask, p);

        assertEquals(java.util.Arrays.asList(0, 4, 8), BingoCard.filledPositions(mask));
    }
}
