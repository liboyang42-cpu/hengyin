package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ChannelOrderVerification;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** 渠道订单验证事实。insert-only + 终态 CAS；不提供物理删除。 */
public interface ChannelOrderVerificationMapper {

    int insert(ChannelOrderVerification verification);

    ChannelOrderVerification selectById(@Param("id") Long id);

    ChannelOrderVerification selectByIdForUpdate(@Param("id") Long id);

    ChannelOrderVerification selectByAccountOrderNo(@Param("accountId") Long accountId,
                                                     @Param("channelOrderNo") String channelOrderNo);

    ChannelOrderVerification selectByAccountRequest(@Param("accountId") Long accountId,
                                                    @Param("requestId") String requestId);

    ChannelOrderVerification selectByProviderEvent(@Param("providerKey") String providerKey,
                                                    @Param("eventId") String eventId);

    /**
     * 按渠道订单号取已验证购买事实（跨账户）。仅用于收入侧回读原始付款关联；
     * 调用方必须处理"同一订单号命中多账户"的歧义，不得静默取一行。
     */
    java.util.List<ChannelOrderVerification> selectVerifiedByChannelOrderNo(
            @Param("channelOrderNo") String channelOrderNo);

    List<ChannelOrderVerification> selectByAccount(@Param("accountId") Long accountId,
                                                   @Param("status") Integer status,
                                                   @Param("from") Date from,
                                                   @Param("to") Date to,
                                                   @Param("limit") int limit);

    int markVerified(@Param("id") Long id,
                     @Param("productId") Long productId,
                     @Param("quantity") Integer quantity,
                     @Param("amount") BigDecimal amount,
                     @Param("currency") String currency,
                     @Param("skuJson") String skuJson,
                     @Param("rawHash") String rawHash,
                     @Param("verifiedAt") Date verifiedAt,
                     @Param("expiresAt") Date expiresAt,
                     @Param("updateTime") Date updateTime);

    int markRejected(@Param("id") Long id,
                     @Param("reasonCode") String reasonCode,
                     @Param("rawHash") String rawHash,
                     @Param("updateTime") Date updateTime);

    int markRevoked(@Param("id") Long id,
                    @Param("reasonCode") String reasonCode,
                    @Param("updateTime") Date updateTime);

    Map<String, Object> selectReconciliationSummary(@Param("accountId") Long accountId,
                                                    @Param("from") Date from,
                                                    @Param("to") Date to);

    List<Map<String, Object>> selectReconciliationIssues(@Param("accountId") Long accountId,
                                                         @Param("from") Date from,
                                                         @Param("to") Date to,
                                                         @Param("limit") int limit);
}
