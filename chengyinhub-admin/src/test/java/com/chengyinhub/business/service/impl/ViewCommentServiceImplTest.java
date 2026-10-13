package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ViewComment;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViewCommentServiceImplTest
{
    @Test
    void summarizeRatingDistributionKeepsFiveBucketsAndSkipsMissingRatings()
    {
        ViewComment five = commentWithRating(5L);
        ViewComment four = commentWithRating(4L);
        ViewComment missing = new ViewComment();

        Map<Integer, Integer> result = new ViewCommentServiceImpl()
                .summarizeRatingDistribution(Arrays.asList(five, four, missing, null));

        assertEquals(Arrays.asList(5, 4, 3, 2, 1), new java.util.ArrayList<>(result.keySet()));
        assertEquals(1, result.get(5));
        assertEquals(1, result.get(4));
        assertEquals(0, result.get(3));
    }

    private ViewComment commentWithRating(Long rating)
    {
        ViewComment comment = new ViewComment();
        comment.setRating(rating);
        return comment;
    }
}
