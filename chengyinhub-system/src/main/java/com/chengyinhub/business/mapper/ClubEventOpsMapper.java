package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubEventOccurrence;
import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.ClubWaitlistEntry;
import com.chengyinhub.business.domain.ClubEventAttendanceCorrection;
import com.chengyinhub.business.domain.ClubEventCampaign;
import com.chengyinhub.business.domain.ClubEventDelivery;
import com.chengyinhub.business.domain.vo.ClubEventCancellationStateVO;
import org.apache.ibatis.annotations.Param;

public interface ClubEventOpsMapper {
    ClubEventSeries selectSeriesByRequest(@Param("clubId") Long clubId,
                                          @Param("actorMemberId") Long actorMemberId,
                                          @Param("requestId") String requestId);
    ClubEventSeries selectSeriesByRequestForUpdate(@Param("clubId") Long clubId,
                                                   @Param("actorMemberId") Long actorMemberId,
                                                   @Param("requestId") String requestId);
    java.util.List<ClubEventSeries> selectActiveSeriesByClub(@Param("clubId") Long clubId);
    ClubEventSeries selectActiveSeriesByIdAndClub(@Param("seriesId") Long seriesId,
                                                  @Param("clubId") Long clubId);
    java.util.List<ClubEventOccurrence> selectFutureOccurrences(
            @Param("seriesId") Long seriesId, @Param("clubId") Long clubId,
            @Param("now") java.util.Date now);
    /**
     * Figma E1「日期清单」:某系列的**全部**场次(含已过去的、含已取消的)。
     * selectFutureOccurrences 只回未来 ACTIVE,而清单要把已取消和已结束的也画出来 ——
     * 「本场已取消」本身就是一种要看见的状态。
     */
    java.util.List<com.chengyinhub.business.domain.vo.ClubEventOccurrenceRowVO> selectSeriesOccurrenceRows(
            @Param("seriesId") Long seriesId, @Param("clubId") Long clubId);

    int insertSeries(ClubEventSeries series);
    int insertOccurrence(ClubEventOccurrence occurrence);
    java.util.List<Long> selectActivityTicketIdsForUpdate(@Param("activityId") Long activityId);
    int updateUnsoldActivityTicketCapacity(@Param("ticketId") Long ticketId,
                                           @Param("activityId") Long activityId,
                                           @Param("capacity") Integer capacity);
    ClubEventSeries selectSeriesByIdForUpdate(@Param("seriesId") Long seriesId);
    java.util.List<ClubEventOccurrence> selectFutureOccurrencesForUpdate(
            @Param("seriesId") Long seriesId, @Param("now") java.util.Date now);
    int countProtectedActivityHistory(@Param("activityId") Long activityId);
    int cancelFutureActivityCas(@Param("activityId") Long activityId, @Param("now") java.util.Date now,
                                @Param("username") String username);
    int cancelOccurrenceCas(@Param("id") Long id, @Param("expectedVersion") Integer expectedVersion);
    int updateSeriesScheduleCas(@Param("seriesId") Long seriesId,
                                @Param("expectedVersion") Integer expectedVersion,
                                @Param("recurrenceType") String recurrenceType,
                                @Param("username") String username,
                                @Param("now") java.util.Date now);
    ClubEventSeries selectSeriesPolicyByActivity(@Param("activityId") Long activityId);
    ClubEventSeries selectSeriesPolicyByActivityForUpdate(@Param("activityId") Long activityId);
    ClubEventSeries selectSeriesByActivityAnyStatusForUpdate(@Param("activityId") Long activityId);
    Long selectActivityTopicForUpdate(@Param("activityId") Long activityId,
                                      @Param("clubId") Long clubId);
    ClubEventOccurrence selectOccurrenceByActivityAndClubForUpdate(
            @Param("activityId") Long activityId, @Param("clubId") Long clubId);
    ClubEventCancellationStateVO selectOccurrenceCancellationState(
            @Param("activityId") Long activityId, @Param("clubId") Long clubId);
    int isActiveParticipant(@Param("clubId") Long clubId, @Param("memberId") Long memberId);
    Long selectClubOwnerForUpdate(@Param("clubId") Long clubId);
    Long selectActiveClubMemberForUpdate(@Param("clubId") Long clubId,
                                         @Param("memberId") Long memberId);
    int countActiveRegistration(@Param("memberId") Long memberId,
                                @Param("activityId") Long activityId,
                                @Param("ticketId") Long ticketId);
    ClubWaitlistEntry selectActiveWaitlist(@Param("memberId") Long memberId,
                                            @Param("activityId") Long activityId,
                                            @Param("ticketId") Long ticketId);
    ClubWaitlistEntry selectLatestWaitlist(@Param("memberId") Long memberId,
                                            @Param("activityId") Long activityId,
                                            @Param("ticketId") Long ticketId);
    ClubWaitlistEntry selectActiveWaitlistForUpdate(@Param("memberId") Long memberId,
                                                     @Param("activityId") Long activityId,
                                                     @Param("ticketId") Long ticketId);
    int cancelActiveWaitlistCas(@Param("id") Long id, @Param("expectedVersion") Integer expectedVersion,
                                @Param("expectedState") String expectedState,
                                @Param("memberId") Long memberId, @Param("activityId") Long activityId,
                                @Param("ticketId") Long ticketId);
    java.util.List<ClubWaitlistEntry> selectActiveWaitlistsForUpdate(
            @Param("clubId") Long clubId, @Param("activityId") Long activityId);
    int restoreWaitlistHold(@Param("ticketId") Long ticketId,
                            @Param("activityId") Long activityId);
    int insertWaitlist(ClubWaitlistEntry entry);
    ClubWaitlistEntry selectFirstWaitingForUpdate(@Param("activityId") Long activityId,
                                                   @Param("ticketId") Long ticketId);
    int offerWaitingCas(@Param("id") Long id, @Param("expectedVersion") Integer expectedVersion,
                        @Param("tokenHash") String tokenHash, @Param("offeredAt") java.util.Date offeredAt,
                        @Param("expiresAt") java.util.Date expiresAt);
    ClubWaitlistEntry selectWaitlistById(@Param("id") Long id);
    ClubWaitlistEntry selectWaitlistByIdForUpdate(@Param("id") Long id);
    int claimOfferCas(@Param("id") Long id, @Param("expectedVersion") Integer expectedVersion,
                      @Param("memberId") Long memberId, @Param("activityId") Long activityId,
                      @Param("ticketId") Long ticketId, @Param("now") java.util.Date now);
    int bindClaimedRegistrationCas(@Param("id") Long id, @Param("registrationId") Long registrationId);
    ClubWaitlistEntry selectWaitlistByRegistration(@Param("registrationId") Long registrationId);
    ClubWaitlistEntry selectWaitlistByRegistrationForUpdate(@Param("registrationId") Long registrationId);
    int convertClaimedByRegistration(@Param("registrationId") Long registrationId);
<<<<<<< public
    /** 维护页按原waitlist行身份推进，固定MAX排除扫描期新插入。kind 0过期/1已付/2终态/3晋级。 */
    Long selectWaitlistMaintenanceMaxId();
    java.util.List<java.util.Map<String, Object>> selectWaitlistMaintenancePage(
            @Param("kind") int kind, @Param("now") java.util.Date now,
            @Param("afterId") long afterId, @Param("maxId") long maxId, @Param("limit") int limit);

    java.util.List<Long> selectExpiredOfferedIds(@Param("now") java.util.Date now,
                                                  @Param("limit") int limit);
=======
    Long selectWaitlistScanUpperId();
    java.util.List<ClubWaitlistEntry> selectExpiredOfferedPage(
            @Param("now") java.util.Date now, @Param("upperId") Long upperId,
            @Param("afterTime") java.util.Date afterTime, @Param("afterId") Long afterId,
            @Param("limit") int limit);
>>>>>>> private-codex/batch1
    int expireOfferedCas(@Param("id") Long id, @Param("expectedVersion") Integer expectedVersion,
                         @Param("now") java.util.Date now);
    java.util.List<ClubWaitlistEntry> selectClaimedPaidPage(
            @Param("upperId") Long upperId, @Param("afterId") Long afterId, @Param("limit") int limit);
    java.util.List<ClubWaitlistEntry> selectClaimedTerminalPage(
            @Param("upperId") Long upperId, @Param("afterId") Long afterId, @Param("limit") int limit);
    int expireClaimedTerminalCas(@Param("registrationId") Long registrationId);
    java.util.List<ClubWaitlistEntry> selectPromotableWaitlistScopePage(
            @Param("now") java.util.Date now, @Param("upperId") Long upperId,
            @Param("afterTime") java.util.Date afterTime, @Param("afterId") Long afterId,
            @Param("limit") int limit);
    java.util.List<java.util.Map<String, Object>> selectPaidRoster(@Param("activityId") Long activityId);
    java.util.List<java.util.Map<String, Object>> selectWaitlistRoster(@Param("activityId") Long activityId);
    java.util.Map<String, Object> selectAttendanceTargetForUpdate(@Param("activityId") Long activityId,
                                                                  @Param("memberId") Long memberId);
    ClubEventAttendanceCorrection selectLatestCorrectionForUpdate(@Param("activityId") Long activityId,
                                                                   @Param("memberId") Long memberId);
    ClubEventAttendanceCorrection selectAttendanceCorrectionByRequestForUpdate(
            @Param("actorMemberId") Long actorMemberId, @Param("requestId") String requestId);
    int insertAttendanceCorrection(ClubEventAttendanceCorrection correction);
    ClubEventCampaign selectCampaignByRequest(@Param("clubId") Long clubId,
                                               @Param("actorMemberId") Long actorMemberId,
                                               @Param("requestId") String requestId);
    ClubEventCampaign selectCampaignById(@Param("id") Long id);
    int insertCampaign(ClubEventCampaign campaign);
    java.util.List<Long> selectAllMemberRecipients(@Param("clubId") Long clubId);
    java.util.List<Long> selectAdminRecipients(@Param("clubId") Long clubId);
    java.util.List<Long> selectRegisteredRecipients(@Param("clubId") Long clubId,
                                                     @Param("activityId") Long activityId);
    java.util.List<Long> selectWaitlistRecipients(@Param("clubId") Long clubId,
                                                   @Param("activityId") Long activityId);
    java.util.List<Long> selectNoShowRecipients(@Param("clubId") Long clubId,
                                                 @Param("activityId") Long activityId);
    java.util.List<Long> selectInactiveRecipients(@Param("clubId") Long clubId);
    int insertDelivery(ClubEventDelivery delivery);
    int insertOutbox(@Param("deliveryId") Long deliveryId, @Param("nextAttemptAt") java.util.Date nextAttemptAt);
    int claimNotificationOutbox(@Param("deliveryId") Long deliveryId,
                                @Param("leaseUntil") java.util.Date leaseUntil);
    java.util.List<ClubEventDelivery> selectDispatchableByCampaign(@Param("campaignId") Long campaignId,
                                                                   @Param("limit") int limit);
    ClubEventDelivery selectDeliveryByIdForUpdate(@Param("id") Long id);
    int isEligibleNotificationRecipient(@Param("campaignId") Long campaignId,
                                        @Param("clubId") Long clubId,
                                        @Param("activityId") Long activityId,
                                        @Param("audienceType") String audienceType,
                                        @Param("recipientMemberId") Long recipientMemberId);
    java.util.List<Long> selectDueCampaignIds(@Param("now") java.util.Date now, @Param("limit") int limit);
    int markDeliverySentCas(@Param("id") Long id, @Param("expectedVersion") Integer expectedVersion,
                            @Param("messageId") Long messageId, @Param("sentAt") java.util.Date sentAt);
    int markDeliveryFailedCas(@Param("id") Long id, @Param("expectedVersion") Integer expectedVersion,
                              @Param("lastError") String lastError, @Param("nextAttemptAt") java.util.Date nextAttemptAt);
    int markDeliverySkippedIneligibleCas(@Param("id") Long id,
                                         @Param("expectedVersion") Integer expectedVersion);
    int completeOutbox(@Param("deliveryId") Long deliveryId);
    int rescheduleOutbox(@Param("deliveryId") Long deliveryId, @Param("nextAttemptAt") java.util.Date nextAttemptAt);
    int refreshCampaignCounts(@Param("campaignId") Long campaignId);
}
