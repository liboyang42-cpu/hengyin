package com.chengyinhub.business.service.support;

import java.util.ArrayList;
import java.util.List;

/**
 * 宾果九宫格的纯逻辑:填格、连线判定、奖档推进。
 *
 * <p>全部是位运算,不碰 IO —— 这样它能被一个不连库的测试盯住。
 * 九个格子固定成 3×3,位序如下:
 * <pre>
 *   0 1 2
 *   3 4 5
 *   6 7 8
 * </pre>
 *
 * <p>用位图而不是子表,是因为位或天然幂等:同一个完成事件被重放,
 * {@code mask | bit} 的结果不变,不需要额外的唯一键去挡重复。
 */
public final class BingoCard {

    /** 九宫格,别改 —— 连线表是按这个尺寸手写的 */
    public static final int SIZE = 9;
    public static final int FULL_MASK = (1 << SIZE) - 1;

    /** 奖档:填出第一格 / 连成一线 / 九格全满 */
    public static final int REWARD_FIRST = 1;
    public static final int REWARD_LINE = 1 << 1;
    public static final int REWARD_FULL = 1 << 2;

    /**
     * 每格自己的奖励位,占 reward_mask 的第 3 位起共九位。
     *
     * <p>与三档奖共用一列,是为了共用 markRewarded 那条「已记过就命中 0 行」的条件更新 ——
     * 发券的幂等靠的就是它,再开一列就要再写一套一模一样的防重发。
     */
    public static final int CELL_REWARD_SHIFT = 3;

    public static int cellRewardBit(int position) {
        if (position < 0 || position >= SIZE) {
            throw new IllegalArgumentException("宾果格位置须为 0 至 " + (SIZE - 1));
        }
        return 1 << (CELL_REWARD_SHIFT + position);
    }

    /** 八条线:三横、三竖、两斜 */
    private static final int[] LINES = {
        0b000000111, 0b000111000, 0b111000000,   // 横
        0b001001001, 0b010010010, 0b100100100,   // 竖
        0b100010001, 0b001010100                 // 斜
    };

    private BingoCard() {
    }

    /** 填一格。已经填过的返回原值 —— 位或天然幂等,重放安全。 */
    public static int fill(int filledMask, int position) {
        if (position < 0 || position >= SIZE) {
            throw new IllegalArgumentException("宾果格位置须为 0 至 " + (SIZE - 1));
        }
        return filledMask | (1 << position);
    }

    public static boolean isFilled(int filledMask, int position) {
        return position >= 0 && position < SIZE && (filledMask & (1 << position)) != 0;
    }

    public static int filledCount(int filledMask) {
        return Integer.bitCount(filledMask & FULL_MASK);
    }

    /** 连成了几条线。 */
    public static int lineCount(int filledMask) {
        int lines = 0;
        for (int line : LINES) {
            if ((filledMask & line) == line) lines++;
        }
        return lines;
    }

    public static boolean isFull(int filledMask) {
        return (filledMask & FULL_MASK) == FULL_MASK;
    }

    /**
     * 这一次填格新解锁了哪些奖档。
     *
     * <p>{@code rewardMask} 记的是已经发过的档,所以同一档只会返回一次 ——
     * 这是"三连奖只发一次"的保证,不靠调用方记状态。
     *
     * @return 新解锁的档位(REWARD_* 的按位或);没有新档返回 0
     */
    public static int newlyUnlocked(int filledMask, int rewardMask) {
        int unlocked = 0;
        if (filledCount(filledMask) >= 1) unlocked |= REWARD_FIRST;
        if (lineCount(filledMask) >= 1) unlocked |= REWARD_LINE;
        if (isFull(filledMask)) unlocked |= REWARD_FULL;
        // 每填上一格,那一格自己的奖也解锁一次
        for (int position = 0; position < SIZE; position++) {
            if (isFilled(filledMask, position)) unlocked |= cellRewardBit(position);
        }
        // 去掉已经发过的
        return unlocked & ~rewardMask;
    }

    /** 把新解锁的档并进已发记录。 */
    public static int markRewarded(int rewardMask, int newlyUnlocked) {
        return rewardMask | newlyUnlocked;
    }

    /** 哪几格已填,给前端渲染用。 */
    public static List<Integer> filledPositions(int filledMask) {
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            if (isFilled(filledMask, i)) positions.add(i);
        }
        return positions;
    }
}
