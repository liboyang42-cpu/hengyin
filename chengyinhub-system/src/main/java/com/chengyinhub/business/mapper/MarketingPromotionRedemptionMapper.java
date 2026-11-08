package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MarketingPromotionRedemption;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** 促销使用事实读写。只读写 marketing_promotion_redemption(insert-only + 撤销状态)。 */
public interface MarketingPromotionRedemptionMapper {

    int countByMember(@Param("promotionId") Long promotionId, @Param("memberId") Long memberId);

    /** 同一触发事实是否已被用过(含已随退款撤销的行 —— 撤销不删行,才挡得住重复领取)。 */
    int countByFact(@Param("promotionId") Long promotionId,
                    @Param("memberId") Long memberId,
                    @Param("sourceFactId") Long sourceFactId);

    int insertRedemption(MarketingPromotionRedemption row);

    /** 退款撤销:只改状态,不删行。已撤销的行重复调用不改(幂等)。 */
    int revokeByOrder(@Param("orderType") Integer orderType,
                      @Param("orderId") Long orderId,
                      @Param("status") Integer status,
                      @Param("now") Date now);

    List<MarketingPromotionRedemption> selectByOrder(@Param("orderType") Integer orderType,
                                                     @Param("orderId") Long orderId);
}
