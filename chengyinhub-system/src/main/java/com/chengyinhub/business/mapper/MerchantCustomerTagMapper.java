package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantCustomerTag;

import java.util.List;

public interface MerchantCustomerTagMapper {
    List<MerchantCustomerTag> selectTagList(MerchantCustomerTag query);

    int insertMerchantCustomerTag(MerchantCustomerTag tag);

    int updateMerchantCustomerTag(MerchantCustomerTag tag);
}
