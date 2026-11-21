package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.MemberPrivacyErasureMapper;
import com.chengyinhub.business.mapper.RoamFogCellMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.support.RoamLocationRetention;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R13 privacy erasure: removes the personal data the 2026-11-15 roadmap packages added, for one member, as a step
 * of the account-deregistration erasure ({@code AccountDeregistrationServiceImpl.executeOne}).
 *
 * <p>Runs inside the caller's per-account transaction and never commits on its own: either every step below lands
 * with the rest of the erasure, or the whole account erasure rolls back (and the existing FAILED path takes over).
 *
 * <ul>
 *   <li><b>Deleted</b> (pure personal location or device data, nothing else refers to the rows):
 *       kept fog cells, fog tiles, session tile records, share snapshots, location-trust fixes,
 *       install-id link, push devices, message reactions, mention rows that name the member, data-copy requests (ONB-10).</li>
 *   <li><b>Anonymised</b> (rows other ledgers or counters refer to; same treatment the 30-day job gives expired
 *       location data): roam sessions lose every coordinate and keep distance/tiles/POI/XP aggregates; POI finds,
 *       shop visits and POI reward logs lose the place and the claim fingerprint (the fingerprint only stops the
 *       same member claiming twice, which a retired member id never can); claim evidence keeps its photo hashes for
 *       duplicate detection but is unlinked from the member and loses its coordinates. CITY/roam territories the
 *       member held are released (REVOKED, owner 0, claim photo dropped); their own territory check-ins are deleted;
 *       territory anti-cheat and CITY board audit rows stay without the member link, coordinates or claim photo.</li>
 * </ul>
 * Audit records kept on purpose (consent ledger, age attestation, the deregistration request) are not touched here.
 */
@Service
public class MemberPrivacyErasureService {
    private final MemberPrivacyErasureMapper mapper;
    private final RoamFogCellMapper fogCells;
    private final RoamMapper roam;

    public MemberPrivacyErasureService(MemberPrivacyErasureMapper mapper, RoamFogCellMapper fogCells, RoamMapper roam) {
        this.mapper = mapper;
        this.fogCells = fogCells;
        this.roam = roam;
    }

    /** Erases the member's roadmap personal data. Returns the affected row count per step (for logs and tests). */
    public Map<String, Integer> erase(Long memberId, Date now) {
        if (memberId == null || memberId <= 0 || now == null) throw new IllegalArgumentException("Invalid erasure member");
        Map<String, Integer> counts = new LinkedHashMap<>();
        // Roam tracks: session geometry first, then the per-cell and per-place rows.
        int sessions = 0;
        for (Map<String, Object> row : mapper.selectSessionsForErasure(memberId)) {
            Long id = ((Number) row.get("id")).longValue();
            String result = RoamLocationRetention.stripJson((String) row.get("finish_result_json"));
            sessions += mapper.anonymizeSession(id, memberId, result, now);
        }
        counts.put("roam_session", sessions);
        counts.put("roam_fog_tile", mapper.deleteFogTiles(memberId));
        counts.put("roam_session_record_archive", mapper.deleteSessionRecords(memberId));
        counts.put("roam_share_snapshot", mapper.deleteShareSnapshots(memberId));
        // Merchants' anonymous cumulative visitor counts must survive the raw visit erasure (as in the 30-day job).
        for (Map<String, Object> shop : mapper.selectShopVisitSources(memberId)) {
            roam.recordAnonymousShopVisitor(memberId, ((Number) shop.get("source_type")).intValue(),
                    ((Number) shop.get("source_id")).longValue());
        }
        counts.put("roam_shop_visit", mapper.anonymizeShopVisits(memberId));
        counts.put("roam_poi_found", mapper.anonymizePoiFound(memberId));
        counts.put("roam_poi_reward_log", mapper.anonymizePoiRewardLog(memberId));
        // Kept fog cells (fog-map package): kept for good only while the account and the consent last.
        counts.put("roam_member_fog_cell", fogCells.deleteByMember(memberId));
        // Location trust (P8).
        counts.put("location_trust_fix", mapper.deleteLocationTrustFixes(memberId));
        counts.put("location_claim_evidence", mapper.anonymizeClaimEvidence(memberId));
        // CITY + roam territory (SYS-citygame): own check-ins deleted, held territories released and unlinked,
        // anti-cheat and board audit rows kept without the member link, coordinates or claim photo.
        counts.put("roam_territory_checkin", mapper.deleteTerritoryCheckins(memberId));
        counts.put("roam_territory_checkin_owner", mapper.unlinkTerritoryCheckinOwner(memberId));
        counts.put("roam_territory", mapper.releaseTerritories(memberId));
        counts.put("roam_territory_audit", mapper.anonymizeTerritoryAudit(memberId));
        counts.put("city_game_board_audit", mapper.anonymizeBoardAuditPlayer(memberId));
        // Measurement and push.
        counts.put("app_install_link", mapper.deleteInstallLinks(memberId));
        counts.put("push_device", mapper.deletePushDevices(memberId));
        // Channel messaging (W5 C2): the member's own reactions, and mention rows that point at the member
        // (USER and REPLY targets). Messages, reply/thread links and other members' mentions stay.
        counts.put("im_message_reaction", mapper.deleteMessageReactions(memberId));
        counts.put("im_message_mention", mapper.deleteMentionsOfMember(memberId));
        // Channel events (C3): RSVPs and album rows deleted (the photo files follow the media asset erasure);
        // events the member posted stay as club activities without the poster link.
        counts.put("channel_event_rsvp", mapper.deleteChannelEventRsvps(memberId));
        counts.put("channel_event_photo", mapper.deleteChannelEventPhotos(memberId));
        counts.put("channel_event_poster", mapper.unlinkChannelEventPoster(memberId));
        // Club invites (W7 SOC-15): links the member made stop working and lose the creator link; use counts stay.
        counts.put("club_invite", mapper.revokeClubInvitesByCreator(memberId));
        // Data-copy requests (W7 ONB-10): the account is going away, so its copy requests (and their notes) go too.
        counts.put("member_data_copy_request", mapper.deleteDataCopyRequests(memberId));
        return counts;
    }
}
