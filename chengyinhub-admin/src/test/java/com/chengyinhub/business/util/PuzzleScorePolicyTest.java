package com.chengyinhub.business.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PuzzleScorePolicyTest
{
    @Test
    void solvedWithoutHintScoresOneHundred()
    {
        assertEquals(100, PuzzleScorePolicy.solvedScore(0));
    }

    @Test
    void eachHintLowersOnlyThePuzzleScoreCap()
    {
        assertEquals(70, PuzzleScorePolicy.solvedScore(1));
        assertEquals(40, PuzzleScorePolicy.solvedScore(2));
    }

    @Test
    void answerRevealAlwaysScoresZero()
    {
        assertEquals(0, PuzzleScorePolicy.revealedScore());
    }

    @Test
    void unknownHintLevelsFailClosedAtTheLowestSolvedScore()
    {
        assertEquals(40, PuzzleScorePolicy.solvedScore(9));
    }
}
