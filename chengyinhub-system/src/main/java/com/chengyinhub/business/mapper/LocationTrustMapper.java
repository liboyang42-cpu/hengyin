package com.chengyinhub.business.mapper;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.locationtrust.LocationTrustFixRow;
import com.chengyinhub.business.locationtrust.ClaimEvidenceRow;

/** P8 location trust: scored fixes (review trail) and CITY claim photo evidence. */
public interface LocationTrustMapper {

    int insertFix(LocationTrustFixRow row);

    /** Latest fix scored ACCEPT and not simulated, received at or after {@code since}. Keys: lat,lng,receivedAt,clientTs. */
    Map<String, Object> selectLastCleanFix(@Param("memberId") Long memberId, @Param("since") Date since);

    /** How many fixes since {@code since} carry exactly these coordinates and this accuracy. */
    int countIdenticalSince(@Param("memberId") Long memberId, @Param("lat") java.math.BigDecimal lat,
                            @Param("lng") java.math.BigDecimal lng, @Param("accuracy") java.math.BigDecimal accuracy,
                            @Param("since") Date since);

    /** Accepted REVEAL fixes of one session, newest first. Keys: lat,lng. */
    List<Map<String, Object>> selectSessionRevealTrack(@Param("memberId") Long memberId,
                                                       @Param("sessionId") Long sessionId, @Param("limit") int limit);

    /** Tiles this member got credited since {@code since} (all sessions). */
    int countTilesRevealedSince(@Param("memberId") Long memberId, @Param("since") Date since);

    /** Fixes with clean coordinates near a place within a time window. Keys: lat,lng,receivedAt. */
    List<Map<String, Object>> selectCleanFixesSince(@Param("memberId") Long memberId, @Param("since") Date since,
                                                    @Param("limit") int limit);

    int updateFixDetail(@Param("id") Long id, @Param("detail") String detail);

    int deleteFixesBefore(@Param("cutoff") Date cutoff, @Param("limit") int limit);

    /** Review list for the admin console: newest non-ACCEPT scored rows, optionally for one member. */
    List<LocationTrustFixRow> selectFlagged(@Param("memberId") Long memberId, @Param("kind") String kind,
                                            @Param("limit") int limit);

    int insertEvidence(ClaimEvidenceRow row);

    ClaimEvidenceRow selectEvidenceOwned(@Param("id") Long id, @Param("memberId") Long memberId);

    List<ClaimEvidenceRow> selectEvidenceBySha(@Param("sha") String sha, @Param("since") Date since);

    /**
     * Closest earlier perceptual hash (by Hamming distance) within the window, ignoring this member's own earlier
     * shots of the same place (a retake is not a duplicate). Keys: id,memberId,placeRef,distance.
     */
    Map<String, Object> selectNearestPhash(@Param("phash") Long phash, @Param("since") Date since,
                                           @Param("memberId") Long memberId, @Param("placeRef") String placeRef);

    int consumeEvidence(@Param("id") Long id, @Param("memberId") Long memberId, @Param("consumedRef") String consumedRef,
                        @Param("now") Date now);

    /** City Monopoly: ACCEPT or REVIEW evidence (a REVIEW claim goes to a person); one use only. */
    int consumeEvidenceForReview(@Param("id") Long id, @Param("memberId") Long memberId, @Param("consumedRef") String consumedRef,
                        @Param("now") Date now);

    int clearEvidencePhotoCoordinatesBefore(@Param("cutoff") Date cutoff, @Param("limit") int limit);
}
