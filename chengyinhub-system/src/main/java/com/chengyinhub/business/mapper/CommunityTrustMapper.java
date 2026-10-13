package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CommunityReportAppeal;
import com.chengyinhub.business.domain.CommunityReportCase;
import com.chengyinhub.business.domain.CommunityReportTimeline;
import com.chengyinhub.business.domain.ContributionRewardLedger;
import com.chengyinhub.business.domain.MerchantContribution;
import com.chengyinhub.business.domain.MerchantContributionReview;
import com.chengyinhub.business.domain.MerchantContributionSupplement;
import com.chengyinhub.business.domain.MerchantSafetyAggregate;
import com.chengyinhub.business.domain.MerchantSafetyDimensionAggregate;
import com.chengyinhub.business.domain.MerchantSafetyRating;
import com.chengyinhub.business.domain.MerchantSafetyRatingDimension;
import com.chengyinhub.business.domain.MerchantSafetyPolicy;
import com.chengyinhub.business.domain.vo.CommunityReportSubjectVO;
import com.chengyinhub.business.domain.vo.CommunityReportTimelineVO;
import com.chengyinhub.business.domain.vo.CommunityReportPlatformVO;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface CommunityTrustMapper {
    CommunityReportCase selectReportByRequestForUpdate(@Param("reporterMemberId") Long reporterMemberId,
                                                        @Param("requestId") String requestId);
    CommunityReportCase selectReportForUpdate(Long id);
    CommunityReportCase selectReportForOwner(@Param("id") Long id,
                                              @Param("reporterMemberId") Long reporterMemberId);
    CommunityReportCase selectReportForOwnerForUpdate(@Param("id") Long id,
                                                       @Param("reporterMemberId") Long reporterMemberId);
    List<CommunityReportCase> selectReportsForOwner(@Param("reporterMemberId") Long reporterMemberId,
                                                    @Param("stage") String stage);
    List<CommunityReportPlatformVO> selectPlatformReports(@Param("stage") String stage,
                                                          @Param("targetType") String targetType);
    CommunityReportSubjectVO selectSubjectReport(@Param("id") Long id,
                                                  @Param("subjectMemberId") Long subjectMemberId);
    CommunityReportSubjectVO selectSubjectReportForUpdate(@Param("id") Long id,
                                                           @Param("subjectMemberId") Long subjectMemberId);
    List<CommunityReportTimelineVO> selectPublicTimeline(Long reportId);
    CommunityReportTimeline selectTimelineByEventKeyForUpdate(
            @Param("reportId") Long reportId, @Param("eventKey") String eventKey);
    int insertReport(CommunityReportCase row);
    int insertReportTimeline(CommunityReportTimeline row);
    int insertReportAppeal(CommunityReportAppeal row);
    CommunityReportAppeal selectAppealByRequestForUpdate(
            @Param("appellantMemberId") Long appellantMemberId,
            @Param("requestId") String requestId);
    int appealReportCas(@Param("id") Long id,
                        @Param("expectedStage") String expectedStage,
                        @Param("nextStage") String nextStage,
                        @Param("expectedVersion") Integer expectedVersion);
    int transitionReportCas(@Param("id") Long id,
                            @Param("expectedStage") String expectedStage,
                            @Param("nextStage") String nextStage,
                            @Param("expectedVersion") Integer expectedVersion,
                            @Param("decisionSummary") String decisionSummary,
                            @Param("estimatedResolveAt") Date estimatedResolveAt);

    MerchantContribution selectContributionByRequestForUpdate(
            @Param("contributorMemberId") Long contributorMemberId,
            @Param("idempotencyKey") String idempotencyKey);
    MerchantContribution selectContributionByScopeForUpdate(
            @Param("contributorMemberId") Long contributorMemberId,
            @Param("merchantId") Long merchantId,
            @Param("scopeKey") String scopeKey);
    MerchantContribution selectContributionForUpdate(Long id);
    MerchantContribution selectContributionForOwner(@Param("id") Long id,
                                                     @Param("contributorMemberId") Long contributorMemberId);
    MerchantContribution selectContributionForOwnerForUpdate(@Param("id") Long id,
                                                              @Param("contributorMemberId") Long contributorMemberId);
    List<MerchantContribution> selectContributionsForOwner(Long contributorMemberId);
    List<MerchantContribution> selectPlatformContributions(@Param("status") String status,
                                                           @Param("merchantId") Long merchantId);
    int insertContribution(MerchantContribution row);
    MerchantContributionSupplement selectContributionSupplementByRequestForUpdate(
            @Param("contributorMemberId") Long contributorMemberId,
            @Param("requestId") String requestId);
    int supplementContributionCas(@Param("id") Long id,
                                  @Param("expectedStatus") String expectedStatus,
                                  @Param("expectedVersion") Integer expectedVersion,
                                  @Param("description") String description,
                                  @Param("evidenceAssetIdsJson") String evidenceAssetIdsJson,
                                  @Param("updateTime") Date updateTime);
    int insertContributionSupplement(MerchantContributionSupplement row);
    int reviewContributionCas(@Param("id") Long id,
                              @Param("expectedStatus") String expectedStatus,
                              @Param("nextStatus") String nextStatus,
                              @Param("expectedVersion") Integer expectedVersion,
                              @Param("reviewerUserId") Long reviewerUserId,
                              @Param("reviewReason") String reviewReason,
                              @Param("canSupplement") boolean canSupplement);
    int mergeContributionCas(@Param("id") Long id,
                             @Param("expectedVersion") Integer expectedVersion);
    int insertContributionReview(MerchantContributionReview row);
    MerchantContributionReview selectContributionReviewByRequestForUpdate(
            @Param("reviewerUserId") Long reviewerUserId,
            @Param("requestId") String requestId);
    int insertRewardLedger(ContributionRewardLedger row);
    ContributionRewardLedger selectRewardLedgerByContribution(Long contributionId);
    Long selectMerchantIdForUpdate(Long merchantId);

    MerchantSafetyRating selectSafetyRatingByRequestForUpdate(
            @Param("memberId") Long memberId, @Param("idempotencyKey") String idempotencyKey);
    MerchantSafetyRating selectSafetyRatingByPeriodForUpdate(
            @Param("memberId") Long memberId, @Param("merchantId") Long merchantId,
            @Param("ratingPeriod") String ratingPeriod);
    int insertSafetyRating(MerchantSafetyRating row);
    int insertSafetyRatingDimension(MerchantSafetyRatingDimension row);
    Long selectVerifiedVisitForSafety(@Param("memberId") Long memberId,
                                      @Param("merchantId") Long merchantId);
    MerchantSafetyPolicy selectActiveSafetyPolicy();
    MerchantSafetyAggregate selectSafetyAggregate(@Param("merchantId") Long merchantId,
                                                  @Param("cutoff") Date cutoff);
    List<MerchantSafetyDimensionAggregate> selectSafetyDimensionAggregates(
            @Param("merchantId") Long merchantId, @Param("cutoff") Date cutoff);
}
