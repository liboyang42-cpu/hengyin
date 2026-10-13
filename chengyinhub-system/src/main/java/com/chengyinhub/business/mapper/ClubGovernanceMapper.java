package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubGovernanceBan;
import com.chengyinhub.business.domain.ClubGovernanceCase;
import com.chengyinhub.business.domain.ClubGovernanceOwnerTransfer;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface ClubGovernanceMapper {
    List<ClubGovernanceBan> selectByClub(Long clubId);
    ClubGovernanceBan selectByIdForUpdate(Long id);
    ClubGovernanceBan selectByRequestIdForUpdate(@Param("clubId") Long clubId,
                                                  @Param("requestId") String requestId);
    ClubGovernanceBan selectEffectiveByClubAndMember(@Param("clubId") Long clubId,
                                                      @Param("targetMemberId") Long targetMemberId,
                                                      @Param("now") Date now);
    int insertClubGovernanceBan(ClubGovernanceBan row);
    int expireStaleByClub(@Param("clubId") Long clubId, @Param("now") Date now);
    int expireStaleByClubAndMember(@Param("clubId") Long clubId,
                                   @Param("targetMemberId") Long targetMemberId,
                                   @Param("now") Date now);
    int unbanCas(@Param("id") Long id, @Param("clubId") Long clubId,
                 @Param("expectedVersion") Integer expectedVersion,
                 @Param("unbannedByMemberId") Long unbannedByMemberId,
                 @Param("unbanReason") String unbanReason,
                 @Param("unbanRequestId") String unbanRequestId,
                 @Param("updateBy") String updateBy);

    ClubGovernanceOwnerTransfer selectOwnerTransferByRequestForUpdate(
            @Param("clubId") Long clubId, @Param("requestId") String requestId);
    int insertOwnerTransfer(ClubGovernanceOwnerTransfer row);
    int deleteActiveMemberForOwnerTransfer(@Param("clubId") Long clubId,
                                            @Param("memberId") Long memberId);
    int upsertFormerOwnerAsMember(@Param("clubId") Long clubId,
                                  @Param("memberId") Long memberId,
                                  @Param("joinTime") Date joinTime);
    int transferClubOwnerCas(@Param("clubId") Long clubId,
                             @Param("expectedOwnerMemberId") Long expectedOwnerMemberId,
                             @Param("newOwnerMemberId") Long newOwnerMemberId);

    ClubGovernanceCase selectCaseBySubmitRequestForUpdate(
            @Param("clubId") Long clubId, @Param("filerMemberId") Long filerMemberId,
            @Param("requestId") String requestId);
    ClubGovernanceCase selectCaseByDecisionRequestForUpdate(
            @Param("clubId") Long clubId, @Param("requestId") String requestId);
    ClubGovernanceCase selectPendingAppealByBanForUpdate(Long banId);
    ClubGovernanceCase selectPendingSubjectCaseForUpdate(
            @Param("clubId") Long clubId, @Param("subjectMemberId") Long subjectMemberId);
    ClubGovernanceCase selectCaseByIdForUpdate(Long id);
    int insertCase(ClubGovernanceCase row);
    List<ClubGovernanceCase> selectMyCases(@Param("clubId") Long clubId,
                                           @Param("filerMemberId") Long filerMemberId);
    List<ClubGovernanceCase> selectPlatformCases(@Param("caseType") String caseType,
                                                 @Param("status") String status,
                                                 @Param("clubId") Long clubId);
    int reviewCaseCas(@Param("id") Long id, @Param("expectedVersion") Integer expectedVersion,
                      @Param("status") String status, @Param("decidedByUserId") Long decidedByUserId,
                      @Param("decisionReason") String decisionReason,
                      @Param("decisionRequestId") String decisionRequestId);
    ClubGovernanceBan selectEffectiveByClubAndMemberForUpdate(
            @Param("clubId") Long clubId, @Param("targetMemberId") Long targetMemberId,
            @Param("now") Date now);
    int platformUnbanCas(@Param("id") Long id, @Param("clubId") Long clubId,
                         @Param("expectedVersion") Integer expectedVersion,
                         @Param("reason") String reason,
                         @Param("requestId") String requestId);
}
