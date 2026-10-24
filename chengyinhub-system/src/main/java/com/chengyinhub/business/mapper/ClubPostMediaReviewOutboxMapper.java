package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubPostMediaReviewOutbox;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface ClubPostMediaReviewOutboxMapper {
    int insertIgnore(ClubPostMediaReviewOutbox task);

    ClubPostMediaReviewOutbox selectByKey(@Param("postId") Long postId,
                                           @Param("postVersion") Integer postVersion,
                                           @Param("mediaHash") String mediaHash);

    List<Long> selectDueIds(@Param("limit") int limit);

    /** Stop expired in-flight, legacy ambiguous retries and exhausted rows without reclaiming them. */
    int retireUnsafeBatch(@Param("limit") int limit);

    int retireUnsafeById(@Param("id") Long id);

    int markManualReview(@Param("id") Long id,
                         @Param("ownerToken") String ownerToken,
                         @Param("fenceToken") Long fenceToken,
                         @Param("traceHash") String traceHash,
                         @Param("errorCode") String errorCode);

    long countBacklog();

    Date selectOldestBacklogCreatedAt();

    int claim(@Param("id") Long id, @Param("ownerToken") String ownerToken);

    ClubPostMediaReviewOutbox selectOwned(@Param("id") Long id,
                                          @Param("ownerToken") String ownerToken);

    int markSubmitted(@Param("id") Long id,
                      @Param("ownerToken") String ownerToken,
                      @Param("fenceToken") Long fenceToken,
                      @Param("traceHash") String traceHash);

    int markRetryable(@Param("id") Long id,
                      @Param("ownerToken") String ownerToken,
                      @Param("fenceToken") Long fenceToken,
                      @Param("nextAttemptAt") Date nextAttemptAt,
                      @Param("errorCode") String errorCode);

    int markStale(@Param("id") Long id,
                  @Param("ownerToken") String ownerToken,
                  @Param("fenceToken") Long fenceToken,
                  @Param("errorCode") String errorCode);

    int markPoisoned(@Param("id") Long id,
                     @Param("ownerToken") String ownerToken,
                     @Param("fenceToken") Long fenceToken,
                     @Param("errorCode") String errorCode);
}
