package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubMembershipInvoice;
import com.chengyinhub.business.domain.ClubMembershipPlan;
import com.chengyinhub.business.domain.ClubMembershipRefundReview;
import com.chengyinhub.business.domain.ClubMembershipSetting;
import com.chengyinhub.business.domain.ClubMembershipTerm;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface ClubMembershipMapper {
    ClubMembershipSetting selectSetting(Long clubId);
    Long lockMemberForRenewal(@Param("memberId") Long memberId);
    int insertSetting(ClubMembershipSetting setting);
    int updateSettingCas(@Param("clubId") Long clubId, @Param("enabled") boolean enabled,
                         @Param("version") int version, @Param("updateBy") String updateBy,
                         @Param("updateTime") Date updateTime);

    ClubMembershipPlan selectPlanByIdForUpdate(Long id);
    List<ClubMembershipPlan> selectPlans(@Param("clubId") Long clubId,
                                         @Param("activeOnly") boolean activeOnly);
    int insertPlan(ClubMembershipPlan plan);
    int updatePlanCas(ClubMembershipPlan plan);

    ClubMembershipInvoice selectInvoiceById(Long id);
    ClubMembershipInvoice selectInvoiceByIdForUpdate(Long id);
    ClubMembershipInvoice selectInvoiceByOutTradeNo(String outTradeNo);
    ClubMembershipInvoice selectInvoiceByOutTradeNoForUpdate(String outTradeNo);
    ClubMembershipInvoice selectInvoiceByIdempotency(@Param("clubId") Long clubId,
                                                      @Param("memberId") Long memberId,
                                                      @Param("idempotencyKey") String idempotencyKey);
    ClubMembershipInvoice selectActivePayingInvoiceForUpdate(@Param("clubId") Long clubId,
                                                              @Param("memberId") Long memberId);
    ClubMembershipInvoice selectInvoiceByTransactionId(String transactionId);
    int insertInvoice(ClubMembershipInvoice invoice);
    int markInvoicePayingCas(@Param("id") Long id, @Param("version") int version,
                             @Param("updateTime") Date updateTime);
    int attachPayParamsCas(@Param("id") Long id, @Param("version") int version,
                           @Param("payParamsJson") String payParamsJson,
                           @Param("updateTime") Date updateTime);
    int markInvoicePaidCas(@Param("id") Long id, @Param("version") int version,
                           @Param("transactionId") String transactionId,
                           @Param("paidAt") Date paidAt);
    int markInvoiceClosedCas(@Param("id") Long id, @Param("version") int version,
                             @Param("updateTime") Date updateTime);
    List<ClubMembershipInvoice> selectPayingInvoicesByMemberForUpdate(@Param("clubId") Long clubId,
                                                                      @Param("memberId") Long memberId);
    List<ClubMembershipInvoice> selectPayingInvoicesByClubForUpdate(Long clubId);
    int blockInvoiceResumeCas(@Param("id") Long id, @Param("version") int version,
                              @Param("blockedAt") Date blockedAt);
    List<ClubMembershipInvoice> selectInvoicesByMember(@Param("clubId") Long clubId,
                                                       @Param("memberId") Long memberId);
    List<ClubMembershipInvoice> selectInvoicesForFinance(Long clubId);

    ClubMembershipTerm selectLatestTermForUpdate(@Param("clubId") Long clubId,
                                                  @Param("memberId") Long memberId);
    ClubMembershipTerm selectTermById(Long id);
    List<ClubMembershipTerm> selectTermsByMember(@Param("clubId") Long clubId,
                                                 @Param("memberId") Long memberId);
    List<ClubMembershipTerm> selectEntitledTermsByMemberForUpdate(@Param("clubId") Long clubId,
                                                                  @Param("memberId") Long memberId,
                                                                  @Param("now") Date now);
    List<ClubMembershipTerm> selectEntitledTermsByClubForUpdate(@Param("clubId") Long clubId,
                                                                @Param("now") Date now);
    int insertTerm(ClubMembershipTerm term);
    int updateTermStatusCas(@Param("id") Long id, @Param("clubId") Long clubId,
                            @Param("memberId") Long memberId, @Param("version") int version,
                            @Param("status") String status, @Param("updateTime") Date updateTime);

    ClubMembershipRefundReview selectRefundReviewByInvoice(Long invoiceId);
    List<ClubMembershipRefundReview> selectRefundReviewsByMember(@Param("clubId") Long clubId,
                                                                 @Param("memberId") Long memberId);
    int insertRefundReview(ClubMembershipRefundReview review);
    int bindInvoicePaymentProof(@Param("id") Long id, @Param("transactionId") String transactionId,
            @Param("amountFen") Long amountFen, @Param("snapshotHash") String snapshotHash,
            @Param("factId") Long factId, @Param("factHash") String factHash);
}
