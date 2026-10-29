package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubMembershipInvoice;
import com.chengyinhub.business.domain.ClubMembershipPlan;
import com.chengyinhub.business.domain.ClubMembershipRefundReview;
import com.chengyinhub.business.domain.ClubMembershipSetting;
import com.chengyinhub.business.domain.dto.ClubMembershipActionRequest;
import com.chengyinhub.business.domain.dto.ClubMembershipPlanRequest;
import com.chengyinhub.business.domain.dto.ClubMembershipRenewRequest;
import com.chengyinhub.business.domain.dto.ClubMembershipSettingRequest;
import com.chengyinhub.business.domain.vo.ClubMembershipOverviewVO;
import com.chengyinhub.business.domain.vo.ClubMembershipRenewalVO;
import com.chengyinhub.business.domain.vo.ClubMembershipAccessEndResultVO;

import java.util.List;

public interface IClubMembershipService {
    ClubMembershipOverviewVO overview(Long principalMemberId, Long clubId);
    ClubMembershipSetting setting(Long principalMemberId, Long clubId);
    ClubMembershipSetting updateSetting(Long principalMemberId, ClubMembershipSettingRequest request);
    List<ClubMembershipPlan> plans(Long principalMemberId, Long clubId, boolean manage);
    ClubMembershipPlan savePlan(Long principalMemberId, ClubMembershipPlanRequest request);
    ClubMembershipRenewalVO renew(Long principalMemberId, ClubMembershipRenewRequest request);
    ClubMembershipRenewalVO resumeInvoice(Long principalMemberId, ClubMembershipActionRequest request);
    ClubMembershipRenewalVO closeInvoice(Long principalMemberId, ClubMembershipActionRequest request);
    ClubMembershipInvoice invoice(Long principalMemberId, Long clubId, Long invoiceId);
    List<ClubMembershipInvoice> financeInvoices(Long principalMemberId, Long clubId);
    void cancelTerm(Long principalMemberId, ClubMembershipActionRequest request);
    ClubMembershipRefundReview requestRefundReview(Long principalMemberId, ClubMembershipActionRequest request);
    ClubMembershipAccessEndResultVO handleMemberAccessEnded(Long clubId, Long memberId,
                                                             String eventType, String requestId);
    ClubMembershipAccessEndResultVO handleClubAccessEnded(Long clubId, String eventType, String requestId);
    boolean supportsOutTradeNo(String outTradeNo);
    boolean handlePaidInvoice(String outTradeNo, boolean paid, Integer amountFen, String transactionId,
            com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference reference);
}
