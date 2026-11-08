package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ChannelVoucher;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/** 渠道兑换码。所有状态迁移带原状态条件；明文码不落库。 */
public interface ChannelVoucherMapper {

    int insert(ChannelVoucher voucher);

    ChannelVoucher selectById(@Param("id") Long id);

    ChannelVoucher selectByIdForUpdate(@Param("id") Long id);

    ChannelVoucher selectByPublicId(@Param("publicId") String publicId);

    ChannelVoucher selectByPublicIdForUpdate(@Param("publicId") String publicId);

    ChannelVoucher selectByVerification(@Param("verificationId") Long verificationId);

    ChannelVoucher selectByAccountRequest(@Param("accountId") Long accountId,
                                          @Param("requestId") String requestId);

    List<ChannelVoucher> selectByAccount(@Param("accountId") Long accountId,
                                         @Param("from") Date from,
                                         @Param("to") Date to,
                                         @Param("limit") int limit);

    int markRedeemed(@Param("id") Long id, @Param("updateTime") Date updateTime);

    int markRevoked(@Param("id") Long id,
                    @Param("source") String source,
                    @Param("updateTime") Date updateTime);

    int markRevocationRequested(@Param("id") Long id,
                                @Param("reason") String reason,
                                @Param("requestId") String requestId,
                                @Param("requestedAt") Date requestedAt,
                                @Param("updateTime") Date updateTime);

    int markInventoryConsumed(@Param("id") Long id, @Param("updateTime") Date updateTime);

    int markInventoryReleased(@Param("id") Long id, @Param("updateTime") Date updateTime);

    Map<String, Object> selectReconciliationSummary(@Param("accountId") Long accountId,
                                                    @Param("from") Date from,
                                                    @Param("to") Date to);

    List<Map<String, Object>> selectReconciliationIssues(@Param("accountId") Long accountId,
                                                         @Param("from") Date from,
                                                         @Param("to") Date to,
                                                         @Param("limit") int limit);
}
