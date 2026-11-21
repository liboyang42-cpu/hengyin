package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;
import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * Per-member revealed fog cells (roam_member_fog_cell): one row per member and geohash-7 cell, with the first and
 * the latest reveal time only. No session, no order of travel, no coordinates: a raw track cannot be rebuilt from it.
 */
public interface RoamFogCellMapper {
    /** Idempotent: a cell already held keeps its first time and moves its latest time forward. */
    int upsertCells(@Param("memberId") Long memberId, @Param("cells") Collection<String> cells, @Param("at") Date at);

    /** Kept cells under the given precision-5 prefixes. */
    List<String> selectRetainedCellsByPrefixes(@Param("memberId") Long memberId,
                                               @Param("prefixes") Collection<String> prefixes,
                                               @Param("limit") Integer limit);

    /** Today's 30-day behaviour: cells of open sessions or sessions finished within 30 days, under the prefixes. */
    List<String> selectRecentCellsByPrefixes(@Param("memberId") Long memberId,
                                             @Param("prefixes") Collection<String> prefixes,
                                             @Param("limit") Integer limit);

    /** Kept cells, latest reveal first (the legacy /tiles restore list). */
    List<String> selectRetainedCells(@Param("memberId") Long memberId, @Param("limit") Integer limit);

    /** Used only while keeping is switched off: removes cells not revealed again since the cutoff. */
    int deleteNotRevealedSince(@Param("cutoff") Date cutoff, @Param("limit") Integer limit);

    /** R13: every kept cell of one member (roam location consent withdrawn, or account erased). Idempotent. */
    int deleteByMember(@Param("memberId") Long memberId);
}
