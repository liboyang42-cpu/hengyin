package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.MerchantCrmCampaignPreviewRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmCampaignRequest;
import com.chengyinhub.business.domain.vo.MerchantCrmCampaignPreviewVO;
import com.chengyinhub.business.domain.vo.MerchantCrmCampaignVO;
import com.chengyinhub.business.domain.vo.MerchantCrmCouponOptionVO;

import java.util.List;

public interface IMerchantCrmCampaignService {
    List<MerchantCrmCouponOptionVO> coupons(Long principalMemberId);
    MerchantCrmCampaignPreviewVO preview(Long principalMemberId, MerchantCrmCampaignPreviewRequest request);
    MerchantCrmCampaignVO create(Long principalMemberId, MerchantCrmCampaignRequest request);
    MerchantCrmCampaignVO dispatch(Long principalMemberId, Long campaignId);
    MerchantCrmCampaignVO retry(Long principalMemberId, Long campaignId);
    MerchantCrmCampaignVO detail(Long principalMemberId, Long campaignId);
    List<MerchantCrmCampaignVO> list(Long principalMemberId);
    int recoverStaleProcessing();
}
