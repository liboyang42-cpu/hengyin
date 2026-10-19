package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.NotificationDeliveryReceipt;
import org.apache.ibatis.annotations.Param;

public interface NotificationDeliveryReceiptMapper {
    int insertIntent(NotificationDeliveryReceipt receipt);

    NotificationDeliveryReceipt selectByIntent(@Param("eventType") String eventType,
                                                @Param("eventId") Long eventId,
                                                @Param("recipientId") Long recipientId,
                                                @Param("channel") String channel);

    /** 明确失败可按宿主原调度重试；PROCESSING/UNKNOWN 不可重新领取。 */
    int claim(@Param("eventType") String eventType, @Param("eventId") Long eventId,
              @Param("recipientId") Long recipientId, @Param("channel") String channel);

    int claimPending(@Param("eventType") String eventType, @Param("eventId") Long eventId,
                     @Param("recipientId") Long recipientId, @Param("channel") String channel);

    int complete(@Param("eventType") String eventType, @Param("eventId") Long eventId,
                 @Param("recipientId") Long recipientId, @Param("channel") String channel,
                 @Param("status") String status, @Param("providerCode") String providerCode);
}
