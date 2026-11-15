package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * R13 privacy erasure: member-scoped statements for the personal data the 2026-11-15 roadmap packages added
 * (roam tracks, location-trust trail and claim evidence, install link, push devices).
 * Kept fog cells are deleted through {@link RoamFogCellMapper#deleteByMember}, which consent withdrawal also uses.
 * Every statement touches only the given member's rows and is idempotent; the caller owns the transaction.
 */
public interface MemberPrivacyErasureMapper {

    // ---- roam tracks ----
    /** Keys: id, finish_result_json. Every session of the member, open or finished, not yet anonymised here. */
    List<Map<String, Object>> selectSessionsForErasure(@Param("memberId") Long memberId);

    /** Strips the session's geometry; distance/tiles/POI/XP aggregates stay, as in the 30-day job. */
    int anonymizeSession(@Param("id") Long id, @Param("memberId") Long memberId,
                         @Param("resultJson") String resultJson, @Param("now") Date now);

    int deleteFogTiles(@Param("memberId") Long memberId);

    int deleteSessionRecords(@Param("memberId") Long memberId);

    int deleteShareSnapshots(@Param("memberId") Long memberId);

    /** Keys: source_type, source_id. Shops the member visited that still name the shop. */
    List<Map<String, Object>> selectShopVisitSources(@Param("memberId") Long memberId);

    int anonymizeShopVisits(@Param("memberId") Long memberId);

    int anonymizePoiFound(@Param("memberId") Long memberId);

    /** Coupon rows keep poi_id for coupon accounting (same rule as the 30-day job); coordinates always go. */
    int anonymizePoiRewardLog(@Param("memberId") Long memberId);

    // ---- location trust ----
    int deleteLocationTrustFixes(@Param("memberId") Long memberId);

    /** Keeps the photo hashes for duplicate detection; unlinks the member (member_id = 0) and drops coordinates. */
    int anonymizeClaimEvidence(@Param("memberId") Long memberId);

    // ---- measurement / push ----
    int deleteInstallLinks(@Param("memberId") Long memberId);

    int deletePushDevices(@Param("memberId") Long memberId);
}
