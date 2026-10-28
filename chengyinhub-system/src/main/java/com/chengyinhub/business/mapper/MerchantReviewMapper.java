package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantReview;
import com.chengyinhub.business.domain.MerchantReviewEligibility;
import com.chengyinhub.business.domain.MerchantReviewReport;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;
import java.util.Date;

public interface MerchantReviewMapper {
    Long lockActiveMember(@Param("memberId") Long memberId);

    int countActiveMerchantRowsForRow(@Param("merchantRowId") Long merchantRowId);

    MerchantReviewEligibility selectEligibility(@Param("authorMemberId") Long authorMemberId,
                                                 @Param("merchantRowId") Long merchantRowId,
                                                 @Param("registrationId") Long registrationId);

    MerchantReviewEligibility selectLatestEligibility(@Param("authorMemberId") Long authorMemberId,
                                                       @Param("merchantRowId") Long merchantRowId);

    /** Read-only eligibility hint; no locks and no fallback to a different registration. */
    MerchantReviewEligibility selectEligibilityForRegistration(@Param("authorMemberId") Long authorMemberId,
                                                               @Param("merchantRowId") Long merchantRowId,
                                                               @Param("registrationId") Long registrationId);

    MerchantReview selectByAuthorRequestForUpdate(@Param("authorMemberId") Long authorMemberId,
                                                  @Param("requestId") String requestId);

    MerchantReview selectByRegistrationMerchant(@Param("registrationId") Long registrationId,
                                                @Param("merchantOwnerMemberId") Long merchantOwnerMemberId);

    MerchantReview selectReviewByIdForUpdate(@Param("id") Long id);

    int insertReview(MerchantReview review);

    long countPublicReviews(@Param("merchantRowId") Long merchantRowId);

    List<MerchantReview> selectPublicReviews(@Param("merchantRowId") Long merchantRowId,
                                             @Param("offset") int offset,
                                             @Param("limit") int limit);

    Map<String, Object> selectPublicRatingSummary(@Param("merchantRowId") Long merchantRowId);

    Date selectPublicReviewsUpdatedAt(@Param("merchantRowId") Long merchantRowId);

    Map<String, Object> selectReviewStats(@Param("merchantRowId") Long merchantRowId);

    long countManageReviews(@Param("merchantRowId") Long merchantRowId,
                            @Param("filter") String filter);

    List<MerchantReview> selectManageReviews(@Param("merchantRowId") Long merchantRowId,
                                             @Param("filter") String filter,
                                             @Param("offset") int offset,
                                             @Param("limit") int limit);

    Map<String, Object> selectManageFilterCounts(@Param("merchantRowId") Long merchantRowId);

    int replyCas(@Param("id") Long id,
                 @Param("merchantRowId") Long merchantRowId,
                 @Param("merchantOwnerMemberId") Long merchantOwnerMemberId,
                 @Param("content") String content,
                 @Param("actorMemberId") Long actorMemberId,
                 @Param("actorRoleCode") String actorRoleCode,
                 @Param("requestId") String requestId,
                 @Param("fingerprint") String fingerprint,
                 @Param("expectedVersion") Integer expectedVersion);

    /** 改已有回复;`reply_content is not null` 保证它不会顶替「第一次回复」。 */
    int updateReplyCas(@Param("id") Long id,
                       @Param("merchantRowId") Long merchantRowId,
                       @Param("merchantOwnerMemberId") Long merchantOwnerMemberId,
                       @Param("content") String content,
                       @Param("actorMemberId") Long actorMemberId,
                       @Param("actorRoleCode") String actorRoleCode,
                       @Param("requestId") String requestId,
                       @Param("fingerprint") String fingerprint,
                       @Param("expectedVersion") Integer expectedVersion);

    /** 删已有回复;同样带 version CAS 与归属条件。 */
    int deleteReplyCas(@Param("id") Long id,
                       @Param("merchantRowId") Long merchantRowId,
                       @Param("merchantOwnerMemberId") Long merchantOwnerMemberId,
                       @Param("expectedVersion") Integer expectedVersion);

    int moderateCas(@Param("id") Long id,
                    @Param("expectedStatus") String expectedStatus,
                    @Param("status") String status,
                    @Param("reason") String reason,
                    @Param("expectedVersion") Integer expectedVersion);

    MerchantReviewReport selectReportByReporterRequestForUpdate(@Param("reporterMemberId") Long reporterMemberId,
                                                                @Param("requestId") String requestId);

    int insertReport(MerchantReviewReport report);
}
