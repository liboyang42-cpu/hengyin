package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

/** 主题通关券发放幂等日志：每位玩家每主题最多成功占位一次。 */
public interface CmsTopicCompletionCouponRewardMapper
{
    int insertIgnore(@Param("topicId") Long topicId, @Param("memberId") Long memberId,
                     @Param("couponId") Long couponId);

    /** 批5 凭证面只读:该玩家该主题的通关券发放时刻;未发过返 null(不返 0,「没发」不是「发了 0 张」)。 */
    java.util.Date selectGrantedAt(@Param("topicId") Long topicId, @Param("memberId") Long memberId);

    /** 拍板4 冲回:这位玩家这主题发过的通关券模板 id;没发过返 null。 */
    Long selectCouponId(@Param("topicId") Long topicId, @Param("memberId") Long memberId);

    int deleteGrant(@Param("topicId") Long topicId, @Param("memberId") Long memberId);
}
