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

    int createCommerceRefund(com.chengyinhub.business.domain.OmsOrder order);
    java.util.Map<String, Object> selectCommerceRefundForUpdate(@org.apache.ibatis.annotations.Param("orderId") Long orderId);
    int settleCommerceRefund(@org.apache.ibatis.annotations.Param("orderId") Long orderId,
            @org.apache.ibatis.annotations.Param("factId") Long factId,
            @org.apache.ibatis.annotations.Param("factHash") String factHash);
    int revokeCommerceSubscription(@org.apache.ibatis.annotations.Param("orderId") Long orderId);
    int markCommerceRefunded(@org.apache.ibatis.annotations.Param("orderId") Long orderId);
    MerchantSubscription selectCommerceSubscriptionForUpdate(@org.apache.ibatis.annotations.Param("orderId") Long orderId);
}
