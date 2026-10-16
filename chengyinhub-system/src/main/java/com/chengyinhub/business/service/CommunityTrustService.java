package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CommunityReportCase;
import com.chengyinhub.business.domain.MerchantContribution;
import com.chengyinhub.business.domain.MerchantSafetyRating;
import com.chengyinhub.business.domain.dto.CommunityReportAppealRequest;
import com.chengyinhub.business.domain.dto.CommunityReportStageRequest;
import com.chengyinhub.business.domain.dto.CommunityReportSubmitRequest;
import com.chengyinhub.business.domain.dto.MerchantContributionReviewRequest;
import com.chengyinhub.business.domain.dto.MerchantContributionSubmitRequest;
import com.chengyinhub.business.domain.dto.MerchantContributionSupplementRequest;
import com.chengyinhub.business.domain.dto.MerchantSafetyRatingRequest;
import com.chengyinhub.business.domain.vo.CommunityReportSubjectVO;
import com.chengyinhub.business.domain.vo.CommunityReportVO;
import com.chengyinhub.business.domain.vo.CommunityReportPlatformVO;
import com.chengyinhub.business.domain.vo.MerchantContributionVO;
import com.chengyinhub.business.domain.vo.MerchantSafetySummaryVO;

import java.util.List;
import java.util.Map;

public interface CommunityTrustService {
    CommunityReportCase submitReport(Long principalMemberId, CommunityReportSubmitRequest request);
    Map<String, Object> getReportTaxonomy();
    CommunityReportCase appealReport(Long principalMemberId, CommunityReportAppealRequest request);
    List<CommunityReportVO> listMyReports(Long principalMemberId, String stage);
    List<CommunityReportVO> listMyReports(Long principalMemberId, String stage, String requestId);
    CommunityReportVO getMyReport(Long principalMemberId, Long reportId);
    CommunityReportSubjectVO getSubjectReport(Long principalMemberId, Long reportId);
    List<CommunityReportPlatformVO> listPlatformReports(String stage, String targetType);
    CommunityReportCase transitionReport(Long operatorUserId, CommunityReportStageRequest request);

    MerchantContribution submitContribution(Long principalMemberId,
                                              MerchantContributionSubmitRequest request);
    Map<String, Object> getContributionTaxonomy();
    MerchantContribution supplementContribution(Long principalMemberId,
                                                  MerchantContributionSupplementRequest request);
    List<MerchantContributionVO> listMyContributions(Long principalMemberId);
    MerchantContributionVO getMyContribution(Long principalMemberId, Long contributionId);
    List<MerchantContribution> listPlatformContributions(String status, Long merchantId);
    MerchantContribution reviewContribution(Long reviewerUserId,
                                              MerchantContributionReviewRequest request);

    MerchantSafetyRating submitSafetyRating(Long principalMemberId,
                                             MerchantSafetyRatingRequest request);
    MerchantSafetySummaryVO getSafetySummary(Long merchantId);
    Map<String, Object> getSafetyTaxonomy();
}
