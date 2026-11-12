package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MarketingPromotion;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** 触发式优惠配置读写。只读写 marketing_promotion。 */
public interface MarketingPromotionMapper {

    /**
     * 本报价可用的候选促销:全局(scope_type=0)或精确命中 (ownerType, ownerId) 的,
     * 已按 priority 降序、id 升序排好 —— 叠加顺序由 SQL 定一次,Java 不再排。
     */
    List<MarketingPromotion> selectActiveByScope(@Param("ownerType") Integer ownerType,
                                                 @Param("ownerId") Long ownerId,
                                                 @Param("now") Date now);

    List<MarketingPromotion> selectPromotionList(MarketingPromotion query);

    /** 含已软删:业务码一旦用过就不可复用。 */
    int countByCode(@Param("promoCode") String promoCode);

    MarketingPromotion selectPromotionById(@Param("id") Long id);

    int insertPromotion(MarketingPromotion promotion);

    int updatePromotion(MarketingPromotion promotion);

    int deletePromotionByIds(@Param("ids") Long[] ids);
}
