package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.WxPaymentCallbackInbox;

public interface WxPaymentCallbackInboxMapper
{
    int insertIgnore(WxPaymentCallbackInbox row);

    WxPaymentCallbackInbox selectByNotifyHash(@Param("notifyHash") String notifyHash);

    WxPaymentCallbackInbox selectForUpdate(@Param("notifyHash") String notifyHash);

    int claim(@Param("notifyHash") String notifyHash,
              @Param("ownerToken") String ownerToken);

    WxPaymentCallbackInbox selectOwnedForUpdate(@Param("notifyHash") String notifyHash,
                                                @Param("ownerToken") String ownerToken,
                                                @Param("fenceToken") long fenceToken);

    /** 锁住回调对应的业务单据行,返回 null 代表目标不存在。固定表名 choose,禁止拼接。 */
    Long lockBusinessTarget(@Param("bizType") int bizType, @Param("bizId") Long bizId);

    int markDone(@Param("notifyHash") String notifyHash,
                 @Param("ownerToken") String ownerToken,
                 @Param("fenceToken") long fenceToken);

    int markIgnoredStale(@Param("notifyHash") String notifyHash,
                         @Param("ownerToken") String ownerToken,
                         @Param("fenceToken") long fenceToken,
                         @Param("errorCode") String errorCode);

    int release(@Param("notifyHash") String notifyHash,
                @Param("ownerToken") String ownerToken,
                @Param("fenceToken") long fenceToken);
}
