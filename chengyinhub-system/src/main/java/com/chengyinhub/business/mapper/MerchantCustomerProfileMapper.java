package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantCustomerProfile;
import com.chengyinhub.business.domain.vo.MerchantCrmSummaryVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface MerchantCustomerProfileMapper {
    List<MerchantCustomerProfile> selectCustomerList(MerchantCustomerProfile query);

    MerchantCrmSummaryVO selectSummaryByMerchantId(@Param("merchantId") Long merchantId);
}
