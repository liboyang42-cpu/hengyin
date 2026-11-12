package com.chengyinhub.business.util;

/** 城市定向谜题分：与探索值、可兑换积分完全分离。 */
public final class PuzzleScorePolicy
{
    public static final String COMPLETION_SOLVED = "SOLVED";
    public static final String COMPLETION_REVEALED = "REVEALED";
    /**
     * 跳过：本题记 0 分、只算辅助完成。
     *
     * <p>必须与 SOLVED、REVEALED 分开存：写成 SOLVED 等于伪造答对，写成 REVEALED 会把
     * 「看了答案」与「直接跳过」混成一件事（两者结算口径不同，完局页也要分别解释）。
     */
    public static final String COMPLETION_SKIPPED = "SKIPPED";

    private PuzzleScorePolicy()
    {
    }

    public static int solvedScore(int hintLevel)
    {
        if (hintLevel <= 0)
        {
            return 100;
        }
        if (hintLevel == 1)
        {
            return 70;
        }
        return 40;
    }

    public static int revealedScore()
    {
        return 0;
    }

    /** 跳过与揭示同为 0 分，但完成方式分开记，不做「一律 0 分算通关」的合并。 */
    public static int skippedScore()
    {
        return 0;
    }
}
