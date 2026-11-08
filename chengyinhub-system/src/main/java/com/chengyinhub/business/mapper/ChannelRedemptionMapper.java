package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ChannelRedemption;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

/** 兑换回执。一码一行；撤销只改状态，不删除。 */
public interface ChannelRedemptionMapper {

    int insert(ChannelRedemption redemption);

    ChannelRedemption selectByVoucherId(@Param("voucherId") Long voucherId);

    ChannelRedemption selectByVoucherIdForUpdate(@Param("voucherId") Long voucherId);

    ChannelRedemption selectByMemberRequest(@Param("memberId") Long memberId,
                                            @Param("requestId") String requestId);

    ChannelRedemption selectByReceiptNo(@Param("receiptNo") String receiptNo);

    ChannelRedemption selectByChannelOrderNo(@Param("channelOrderNo") String channelOrderNo);

    int markRevoked(@Param("id") Long id,
                    @Param("reason") String reason,
                    @Param("revokedAt") Date revokedAt,
                    @Param("updateTime") Date updateTime);
}
