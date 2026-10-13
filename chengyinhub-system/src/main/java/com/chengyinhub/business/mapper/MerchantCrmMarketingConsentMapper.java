package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantCrmMarketingConsent;
import com.chengyinhub.business.domain.vo.MerchantCrmConsentMerchantVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface MerchantCrmMarketingConsentMapper {
    List<MerchantCrmConsentMerchantVO> selectEligibleMerchants(@Param("memberId") Long memberId);
    MerchantCrmConsentMerchantVO selectEligibleMerchant(@Param("memberId") Long memberId,
                                                         @Param("merchantRowId") Long merchantRowId);
    MerchantCrmMarketingConsent selectByRequestId(@Param("memberId") Long memberId,
                                                   @Param("merchantRowId") Long merchantRowId,
                                                   @Param("requestId") String requestId);
    MerchantCrmMarketingConsent selectLatestOptIn(@Param("memberId") Long memberId,
                                                   @Param("ownerMemberId") Long ownerMemberId,
                                                   @Param("merchantRowId") Long merchantRowId,
                                                   @Param("channel") String channel);
    int insertConsent(MerchantCrmMarketingConsent consent);
}
