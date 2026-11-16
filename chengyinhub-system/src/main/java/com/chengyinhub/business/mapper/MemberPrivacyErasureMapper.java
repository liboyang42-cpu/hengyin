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

    // ---- CITY + roam territory (SYS-citygame) ----
    /** The member's own visitor/claim check-ins carry their coordinates and photo: deleted. */
    int deleteTerritoryCheckins(@Param("memberId") Long memberId);

    /** Other players' check-ins at the member's territories keep their rows; the owner link goes (owner_member_id = 0). */
    int unlinkTerritoryCheckinOwner(@Param("memberId") Long memberId);

    /** Territories the member held: released (REVOKED), unlinked (owner 0) and the claim photo dropped. */
    int releaseTerritories(@Param("memberId") Long memberId);

    /** Anti-cheat audit rows stay for counts; member link (0), coordinates and detail go. */
    int anonymizeTerritoryAudit(@Param("memberId") Long memberId);

    /** Player rows of the CITY board audit: actor unlinked; territory-claim detail (holds the claim photo) dropped. */
    int anonymizeBoardAuditPlayer(@Param("memberId") Long memberId);

    // ---- measurement / push ----
    int deleteInstallLinks(@Param("memberId") Long memberId);

    int deletePushDevices(@Param("memberId") Long memberId);

    /** W5 C2: the member's message reactions. */
    int deleteMessageReactions(@Param("memberId") Long memberId);

    /** W5 C2: USER and REPLY mention rows that name the member. */
    int deleteMentionsOfMember(@Param("memberId") Long memberId);

    // C3 channel events: own RSVPs and album rows are deleted; an event the member posted stays (it is a club
    // activity) but loses the poster link.
    int deleteChannelEventRsvps(@Param("memberId") Long memberId);

    int deleteChannelEventPhotos(@Param("memberId") Long memberId);

    int unlinkChannelEventPoster(@Param("memberId") Long memberId);

    /** W7 SOC-15: the member's invite links stop working and lose the creator link. */
    int revokeClubInvitesByCreator(@Param("memberId") Long memberId);
}
