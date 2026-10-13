package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MerchantCustomerProfile;
import com.chengyinhub.business.domain.vo.MerchantCrmProfileReconcileVO;
import com.chengyinhub.business.domain.vo.MerchantCrmSummaryVO;

import java.util.List;

public interface IMerchantCrmService {
    List<MerchantCustomerProfile> selectCustomerList(MerchantCustomerProfile query);

    MerchantCrmProfileReconcileVO aggregateMerchantCustomers(Long ownerMemberId);

    MerchantCrmSummaryVO getSummary(Long merchantId);
}
