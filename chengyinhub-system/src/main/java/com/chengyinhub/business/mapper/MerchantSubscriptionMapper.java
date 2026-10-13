package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantSubscription;

import java.util.List;

/**
 * E4 商家付费权益/订阅 Mapper
 */
public interface MerchantSubscriptionMapper {

    List<MerchantSubscription> selectMerchantSubscriptionList(MerchantSubscription q);

    int insertMerchantSubscription(MerchantSubscription s);

    int grantMerchantSubscription(MerchantSubscription s);
}
