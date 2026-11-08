package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MarketingOffer;
import com.chengyinhub.business.domain.MarketingOfferItem;
import com.chengyinhub.business.domain.MarketingPromotion;
import com.chengyinhub.business.domain.MarketingTier;

import java.util.List;

/**
 * 促销配置(后台)服务。
 *
 * <p><b>写权限</b>:菜单权限之外必须过对象级范围校验 —— 主题走
 * {@code ITopicOperationsScopeAuthorizer.canEditTopic},活动走其所属主题;两者都没有
 * 就只能是全局范围账号。仅凭菜单权限不得改别人主题的组合包/阶梯/促销。
 *
 * <p><b>配置校验是"宁可不给卖"的方向</b>:坏配置一律拒写(打包价高于分买、阶梯区间重叠、
 * 折扣率 &gt; 1、窗口未配置),而不是存进去等报价时才炸。
 */
public interface IMarketingPromotionConfigService {

    List<MarketingOffer> listOffers(MarketingOffer query);

    MarketingOffer getOffer(Long id);

    int createOffer(MarketingOffer offer, List<MarketingOfferItem> items);

    int updateOffer(MarketingOffer offer, List<MarketingOfferItem> items);

    int deleteOffers(Long[] ids);

    List<MarketingTier> listTiers(MarketingTier query);

    /**
     * 新增人数阶梯价。
     *
     * <p>业务键 {@code (owner_type, owner_id, ticket_id, min_quantity)} 上仍有软删行时
     * <b>复活那一行</b>并写入本次配置(规则版本继续递增),不 insert 第二行 —— 唯一键不含
     * {@code del_flag},直接 insert 会 1062。归属按原行再校验一次。
     */
    int createTier(MarketingTier tier);

    int updateTier(MarketingTier tier);

    int deleteTiers(Long[] ids);

    List<MarketingPromotion> listPromotions(MarketingPromotion query);

    int createPromotion(MarketingPromotion promotion);

    int updatePromotion(MarketingPromotion promotion);

    int deletePromotions(Long[] ids);
}
