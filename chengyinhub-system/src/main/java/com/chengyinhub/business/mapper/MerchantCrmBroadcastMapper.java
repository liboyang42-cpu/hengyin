package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantCrmBroadcast;
import com.chengyinhub.business.domain.MerchantCrmBroadcastRecipient;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface MerchantCrmBroadcastMapper {
    MerchantCrmBroadcast selectByRequestId(@Param("merchantId") Long merchantId,
                                           @Param("requestId") String requestId);

    int insertBroadcast(MerchantCrmBroadcast row);

    /** 商家当日已发广播条数(含 SENDING/失败;「每天最多 3 条」的判据)。 */
    int countTodayByMerchant(@Param("merchantId") Long merchantId);

    /**
     * 该客户今天是否已被本商家的 CRM 触达占用额度(与 campaign 共用的自然日互斥真源)。
     * 预览用:只要 guard 行存在,无论状态,今天都不该再发第二条。
     */
    int countGuardToday(@Param("merchantId") Long merchantId,
                        @Param("memberId") Long memberId,
                        @Param("channel") String channel);

    int insertRecipient(MerchantCrmBroadcastRecipient row);

    List<MerchantCrmBroadcastRecipient> selectRecipients(@Param("merchantId") Long merchantId,
                                                         @Param("broadcastId") Long broadcastId,
                                                         @Param("limit") Integer limit);

    int markRecipientDelivered(@Param("merchantId") Long merchantId,
                               @Param("broadcastId") Long broadcastId,
                               @Param("recipientId") Long recipientId,
                               @Param("messageReceiptId") Long messageReceiptId);

    int markRecipientSkipped(@Param("merchantId") Long merchantId,
                             @Param("broadcastId") Long broadcastId,
                             @Param("recipientId") Long recipientId,
                             @Param("status") String status,
                             @Param("failureCode") String failureCode,
                             @Param("failureMessage") String failureMessage);

    int finalizeBroadcast(MerchantCrmBroadcast row);
}
