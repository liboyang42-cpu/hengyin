package com.chengyinhub.business.task;

import com.chengyinhub.business.service.IClubPostMediaReviewService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ClubPostMediaReviewTaskTest {
    @Test
    void dedicatedTaskRetriesDueMediaReviews() {
        IClubPostMediaReviewService mediaReview = mock(IClubPostMediaReviewService.class);
        ClubPostMediaReviewTask task = new ClubPostMediaReviewTask(mediaReview);

        task.run();

        verify(mediaReview).retryDue();
    }

    @Test
    void batchFailureDoesNotEscapeQuartzTask() {
        IClubPostMediaReviewService mediaReview = mock(IClubPostMediaReviewService.class);
        doThrow(new IllegalStateException("temporary database failure"))
                .when(mediaReview).retryDue();
        ClubPostMediaReviewTask task = new ClubPostMediaReviewTask(mediaReview);

        assertDoesNotThrow(task::run);

        verify(mediaReview).retryDue();
    }
}
