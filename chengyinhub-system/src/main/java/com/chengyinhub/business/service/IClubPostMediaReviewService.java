package com.chengyinhub.business.service;

/** Durable ClubPost image review dispatch. */
public interface IClubPostMediaReviewService {
    /** Must be invoked inside the same transaction that commits a ClubPost or its new version. */
    int enqueueUpdatedMedia(Long postId, Integer postVersion, Long submitterMemberId, String images);

    /** Bounded Quartz recovery for pending, retryable, and expired claims. */
    int retryDue();
}
