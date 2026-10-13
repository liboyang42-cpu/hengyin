package com.chengyinhub.business.service;

/** 逐人独立事务投递；服务端锁 recipient 保证渠道幂等。 */
public interface IMerchantCrmRecipientDeliveryService {
    void deliver(Long merchantId, Long campaignId, Long recipientId);
    void recordFailure(Long merchantId, Long campaignId, Long recipientId, RuntimeException cause);
}
