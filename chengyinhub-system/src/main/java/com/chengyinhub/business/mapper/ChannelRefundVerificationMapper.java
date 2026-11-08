package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ChannelRefundVerification;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

/** 渠道退款事实。事件唯一、退款单唯一、消费一次性 CAS。 */
public interface ChannelRefundVerificationMapper {

    int insert(ChannelRefundVerification refund);

    ChannelRefundVerification selectById(@Param("id") Long id);

    ChannelRefundVerification selectByIdForUpdate(@Param("id") Long id);

    ChannelRefundVerification selectByProviderEvent(@Param("providerKey") String providerKey,
                                                     @Param("eventId") String eventId);

    ChannelRefundVerification selectByAccountRefundNo(@Param("accountId") Long accountId,
                                                      @Param("channelRefundNo") String channelRefundNo);

    int markVerified(@Param("id") Long id,
                     @Param("fullRefund") Integer fullRefund,
                     @Param("verifiedAt") Date verifiedAt,
                     @Param("updateTime") Date updateTime);

    int markRejected(@Param("id") Long id,
                     @Param("reasonCode") String reasonCode,
                     @Param("updateTime") Date updateTime);

    int markConsumed(@Param("id") Long id,
                     @Param("consumedAt") Date consumedAt,
                     @Param("updateTime") Date updateTime);

    /**
     * 无需冲销的终态：该渠道退款对应不到任何已发准入/已应计收入（例如购买后未兑换就退款）。
     * 记下显式原因并标 consumed，避免回调被无限重投，也不假装发生过冲销。
     */
    int markNoRevenueToReverse(@Param("id") Long id,
                               @Param("reasonCode") String reasonCode,
                               @Param("consumedAt") Date consumedAt,
                               @Param("updateTime") Date updateTime);

    /** 已验证但尚未被消费的退款事实（供重投/补偿任务重新驱动撤销+入队）。 */
    java.util.List<ChannelRefundVerification> selectVerifiedUnconsumed(@Param("limit") int limit);
}
