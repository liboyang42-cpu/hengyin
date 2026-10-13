package com.chengyinhub.business.util;

/** 城市定向谜题分：与探索值、可兑换积分完全分离。 */
public final class PuzzleScorePolicy
{
    public static final String COMPLETION_SOLVED = "SOLVED";
    public static final String COMPLETION_REVEALED = "REVEALED";

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
}
