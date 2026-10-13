package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MerchantCrmMarketingConsent;
import com.chengyinhub.business.domain.dto.MerchantCrmMarketingConsentRequest;
import com.chengyinhub.business.domain.vo.MerchantCrmConsentMerchantVO;

import java.util.List;

public interface IMerchantCrmMarketingConsentService {
    List<MerchantCrmConsentMerchantVO> list(Long memberId);
    MerchantCrmMarketingConsent update(Long memberId, MerchantCrmMarketingConsentRequest request);
}
