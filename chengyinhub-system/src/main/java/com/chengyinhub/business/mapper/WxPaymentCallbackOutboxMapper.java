package com.chengyinhub.business.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.WxPaymentCallbackOutbox;

public interface WxPaymentCallbackOutboxMapper
{
    int insertIgnore(WxPaymentCallbackOutbox row);

    /** 只选 pending/retryable 且到期的行;money_affecting=1 一律不自动派发。 */
    List<WxPaymentCallbackOutbox> selectClaimableForUpdate(@Param("limit") int limit);

    int claimById(@Param("id") Long id, @Param("ownerToken") String ownerToken);

    int complete(@Param("id") Long id,
                 @Param("ownerToken") String ownerToken,
                 @Param("fenceToken") long fenceToken);

    /** 未达上限转 retryable 并算递增间隔;达到 max_attempts 转 manual_required(不再自动重试)。 */
    int retryOrEscalate(@Param("id") Long id,
                        @Param("ownerToken") String ownerToken,
                        @Param("fenceToken") long fenceToken,
                        @Param("errorCode") String errorCode);

    int escalateToManual(@Param("id") Long id,
                         @Param("ownerToken") String ownerToken,
                         @Param("fenceToken") long fenceToken,
                         @Param("errorCode") String errorCode);
}
