package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.WxContentCallbackInbox;

public interface WxContentCallbackInboxMapper
{
    int insertIgnore(WxContentCallbackInbox row);

    WxContentCallbackInbox selectByTraceHash(@Param("traceHash") String traceHash);

    WxContentCallbackInbox selectForUpdate(@Param("traceHash") String traceHash);

    int claim(@Param("traceHash") String traceHash,
              @Param("ownerToken") String ownerToken,
              @Param("verdict") int verdict);

    WxContentCallbackInbox selectOwnedForUpdate(@Param("traceHash") String traceHash,
                                                 @Param("ownerToken") String ownerToken,
                                                 @Param("fenceToken") long fenceToken);

    Long lockBusinessTarget(@Param("bizType") int bizType, @Param("bizId") Long bizId);

    /** 治理/历史读取用只读事实:目标是否曾命中内容安全违规(verdict=1)。 */
    int countRiskyCallback(@Param("bizType") int bizType, @Param("bizId") Long bizId);

    /** 审核回执用只读事实:该目标该媒体(SHA-256 小写十六进制)最近一次送检;没送检过返回 null。 */
    WxContentCallbackInbox selectLatestByTargetMedia(@Param("bizType") int bizType, @Param("bizId") Long bizId,
                                                     @Param("mediaHash") String mediaHash);

    int markDone(@Param("traceHash") String traceHash,
                 @Param("ownerToken") String ownerToken,
                 @Param("fenceToken") long fenceToken,
                 @Param("verdict") int verdict);

    int markIgnoredStale(@Param("traceHash") String traceHash,
                         @Param("ownerToken") String ownerToken,
                         @Param("fenceToken") long fenceToken,
                         @Param("verdict") int verdict);

    int markManualReview(@Param("traceHash") String traceHash,
                         @Param("ownerToken") String ownerToken,
                         @Param("fenceToken") long fenceToken,
                         @Param("verdict") int verdict,
                         @Param("errorCode") String errorCode);

    int release(@Param("traceHash") String traceHash,
                @Param("ownerToken") String ownerToken,
                @Param("fenceToken") long fenceToken);
}
