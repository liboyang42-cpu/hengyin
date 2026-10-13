package com.chengyinhub.business.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.OfficialEventArrivalVerification;
import com.chengyinhub.business.domain.OfficialEventBinding;
import com.chengyinhub.business.domain.OfficialEventFulfillmentRequirement;
import com.chengyinhub.business.domain.OfficialEventMission;
import com.chengyinhub.business.domain.OfficialEventMissionEvidence;
import com.chengyinhub.business.domain.OfficialEventOperatorAuthorization;
import com.chengyinhub.business.domain.OfficialEventParticipation;
import com.chengyinhub.business.domain.OfficialEventParty;
import com.chengyinhub.business.domain.OfficialEventRewardIssuance;

/**
 * 官方活动 V2 聚合的数据访问 seam。活动合同内部表只由 V2 服务写入，页面不能绕开它写 progress。
 */
public interface OfficialEventV2Mapper {
    int insertBinding(OfficialEventBinding binding);
    int deleteBindingsByEvent(@Param("eventId") Long eventId);
    int activateDraftBindings(@Param("eventId") Long eventId);
    int activateDraftMissions(@Param("eventId") Long eventId);
    int activateDraftRequirements(@Param("eventId") Long eventId);
    List<OfficialEventBinding> selectBindingsByEvent(@Param("eventId") Long eventId);
    OfficialEventBinding selectBindingById(@Param("id") Long id);
    OfficialEventBinding selectBindingByEventAndCode(@Param("eventId") Long eventId, @Param("bindingCode") String bindingCode);

    int insertMission(OfficialEventMission mission);
    int deleteMissionsByEvent(@Param("eventId") Long eventId);
    List<OfficialEventMission> selectMissionsByEvent(@Param("eventId") Long eventId);
    OfficialEventMission selectMissionByEventAndCode(@Param("eventId") Long eventId, @Param("missionCode") String missionCode);
    List<OfficialEventMission> selectActiveThemeMissions(@Param("topicId") Long topicId);

    int insertParty(OfficialEventParty party);
    int deletePartiesByEvent(@Param("eventId") Long eventId);
    int deleteInvitedPartiesByEvent(@Param("eventId") Long eventId);
    List<OfficialEventParty> selectPartiesByEvent(@Param("eventId") Long eventId);
    OfficialEventParty selectPartyById(@Param("id") Long id);
    int updatePartyStatus(@Param("id") Long id, @Param("status") String status,
                          @Param("operatorId") Long operatorId, @Param("reason") String reason);
    int updatePartyStatusAndOrganizerAuthority(@Param("id") Long id, @Param("status") String status,
                                               @Param("organizerAuthority") Integer organizerAuthority,
                                               @Param("operatorId") Long operatorId, @Param("reason") String reason);
    List<OfficialEventParty> selectPartiesByEventForUpdate(@Param("eventId") Long eventId);
    List<Map<String, Object>> selectPartyInboxForMember(@Param("memberId") Long memberId);
    int activateAcceptedParties(@Param("eventId") Long eventId);
    int archiveActiveParties(@Param("eventId") Long eventId);

    int insertRequirement(OfficialEventFulfillmentRequirement requirement);
    int deleteRequirementsByEvent(@Param("eventId") Long eventId);
    List<OfficialEventFulfillmentRequirement> selectRequirementsByEvent(@Param("eventId") Long eventId);

    int insertParticipationIgnore(OfficialEventParticipation participation);
    OfficialEventParticipation selectParticipation(@Param("eventId") Long eventId, @Param("memberId") Long memberId);
    int updateParticipationState(@Param("eventId") Long eventId, @Param("memberId") Long memberId,
                                 @Param("state") String state, @Param("reason") String reason);
    int countActiveParticipations(@Param("eventId") Long eventId);
    List<Long> selectActiveParticipationMemberIds(@Param("eventId") Long eventId);

    int insertEvidenceIgnore(OfficialEventMissionEvidence evidence);
    OfficialEventMissionEvidence selectEvidenceByDedupeKey(@Param("dedupeKey") String dedupeKey);
    List<OfficialEventMissionEvidence> selectEvidenceByEventAndMember(@Param("eventId") Long eventId, @Param("memberId") Long memberId);
    int countValidEvidence(@Param("eventId") Long eventId, @Param("missionId") Long missionId,
                           @Param("memberId") Long memberId);
    int countValidEvidenceByEvent(@Param("eventId") Long eventId);
    int revokeEvidenceById(@Param("id") Long id, @Param("reason") String reason, @Param("operatorId") Long operatorId);
    OfficialEventMissionEvidence selectEvidenceById(@Param("id") Long id);
    int revokeEvidenceByMissionAndMember(@Param("missionId") Long missionId, @Param("memberId") Long memberId,
                                         @Param("reason") String reason, @Param("operatorId") Long operatorId);
    int revokeEvidenceBySourceFact(@Param("sourceType") String sourceType, @Param("sourceFactId") String sourceFactId,
                                   @Param("reason") String reason, @Param("operatorId") Long operatorId);
    List<Long> selectEventIdsByThemeExploreEvidence(@Param("registrationId") Long registrationId, @Param("topicId") Long topicId);
    List<Long> selectEventIdsByThemeExploreEvidenceForUpdate(@Param("registrationId") Long registrationId, @Param("topicId") Long topicId);

    int insertRewardIssuanceIgnore(OfficialEventRewardIssuance issuance);
    OfficialEventRewardIssuance selectRewardIssuance(@Param("eventId") Long eventId, @Param("memberId") Long memberId,
                                                      @Param("rewardRuleVersion") String rewardRuleVersion);
    int claimPendingRewardIssuance(@Param("id") Long id);
    int updateRewardIssuanceStatus(@Param("id") Long id, @Param("status") String status, @Param("failureReason") String failureReason);
    int markRewardManualReviewBySourceFact(@Param("sourceType") String sourceType, @Param("sourceFactId") String sourceFactId,
                                           @Param("reason") String reason);
    int markRewardManualReviewByThemeExplore(@Param("registrationId") Long registrationId, @Param("topicId") Long topicId,
                                             @Param("reason") String reason);
    int markRewardManualReviewByEvidenceId(@Param("evidenceId") Long evidenceId, @Param("reason") String reason);
    int markRewardManualReviewByEventAndMember(@Param("eventId") Long eventId, @Param("memberId") Long memberId,
                                               @Param("reason") String reason);

    int insertArrivalVerificationIgnore(OfficialEventArrivalVerification verification);
    OfficialEventArrivalVerification selectArrivalVerificationByRequest(@Param("eventId") Long eventId,
                                                                        @Param("missionId") Long missionId,
                                                                        @Param("memberId") Long memberId,
                                                                        @Param("requestId") String requestId);
    OfficialEventArrivalVerification selectLatestArrivalVerification(@Param("eventId") Long eventId,
                                                                     @Param("missionId") Long missionId,
                                                                     @Param("memberId") Long memberId);
    /** 会员最近一次被接受到达的目标坐标（跨活动/任务），供跨次到达测速。键：foundAt/lat/lng。 */
    Map<String, Object> selectLastAcceptedArrivalLoc(@Param("memberId") Long memberId);

    List<Map<String, Object>> selectMission360Rows(@Param("eventId") Long eventId);
    List<Map<String, Object>> selectFulfillmentCoverageRows(@Param("eventId") Long eventId);
    List<Map<String, Object>> selectParticipantRewardRows(@Param("eventId") Long eventId);
    List<Map<String, Object>> selectActiveV2RoamEvents(@Param("city") String city, @Param("memberId") Long memberId);
    List<Map<String, Object>> selectPublicV2Events(@Param("city") String city, @Param("memberId") Long memberId);
    List<Map<String, Object>> selectV2EventsByMember(@Param("memberId") Long memberId);

    /** 后台 sys_user 到白名单 ums_member 的显式代理授权；禁止以数值 ID 猜测身份。 */
    OfficialEventOperatorAuthorization selectActiveOperatorAuthorization(@Param("sysUserId") Long sysUserId);
    OfficialEventOperatorAuthorization selectActiveOperatorAuthorizationForUpdate(@Param("sysUserId") Long sysUserId);
    int insertOperatorAuthorization(OfficialEventOperatorAuthorization authorization);
    int revokeOperatorAuthorization(@Param("id") Long id, @Param("revokedBy") Long revokedBy,
                                    @Param("revokeReason") String revokeReason);

    int updateV2ControlState(@Param("eventId") Long eventId, @Param("controlState") String controlState,
                             @Param("pausedFromStatus") Integer pausedFromStatus, @Param("pausedAt") java.util.Date pausedAt,
                             @Param("pausedBy") Long pausedBy, @Param("pausedReason") String pausedReason,
                             @Param("pauseImpactScope") String pauseImpactScope,
                             @Param("pauseAlternativePlan") String pauseAlternativePlan,
                             @Param("pauseNotificationPlan") String pauseNotificationPlan,
                             @Param("pauseNotificationStatus") String pauseNotificationStatus,
                             @Param("resumeNote") String resumeNote);

    int revokeThemeExploreEvidence(@Param("registrationId") Long registrationId, @Param("topicId") Long topicId,
                                   @Param("reason") String reason, @Param("operatorId") Long operatorId);

    List<Map<String, Object>> selectAuditLogs(@Param("eventId") Long eventId);

    int insertAuditLog(@Param("eventId") Long eventId, @Param("action") String action,
                       @Param("operatorId") Long operatorId, @Param("summary") String summary,
                       @Param("beforeJson") String beforeJson, @Param("afterJson") String afterJson);
}
