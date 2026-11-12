package com.chengyinhub.business.domain.dto;

import com.chengyinhub.business.domain.MarketingOffer;
import com.chengyinhub.business.domain.MarketingPromotion;
import com.chengyinhub.business.domain.MarketingTier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计算器的事实包:全部由服务端从库/事实表读好后填入,计算器不访问 DB、不读时钟。
 *
 * <p>这样拆分的目的只有一个:让"算钱"这件事可以被穷举测试 ——
 * 同样的输入必须得到同样的输出,不给"顺手查一下当前配置"留缝。
 *
 * <p>这里只放<b>计算真正要用的</b>事实:已通过资格校验的组合包、该范围的阶梯、候选促销,
 * 以及每条促销按服务端事实算出的资格。组合包的成员行不进来 —— 成员匹配已在服务层完成,
 * 计算器只认"这个打包价能不能用"。
 */
public class MarketingPromotionFacts {

    /** 通过服务端校验的组合包;null = 未选或不可用。 */
    private MarketingOffer offer;
    /** 请求了 offer 但不适用时的原因码;与 offer == null 同时出现。 */
    private String offerExclusionReason;
    private String offerExclusionDetail;

    private List<MarketingTier> tiers = new ArrayList<>();
    /** 已按 priority 降序、id 升序排好的候选促销。 */
    private List<MarketingPromotion> promotions = new ArrayList<>();
    private Map<Long, MarketingEligibility> eligibility = new LinkedHashMap<>();

    public MarketingOffer getOffer() { return offer; }
    public void setOffer(MarketingOffer offer) { this.offer = offer; }

    public String getOfferExclusionReason() { return offerExclusionReason; }
    public void setOfferExclusionReason(String offerExclusionReason) {
        this.offerExclusionReason = offerExclusionReason;
    }

    public String getOfferExclusionDetail() { return offerExclusionDetail; }
    public void setOfferExclusionDetail(String offerExclusionDetail) {
        this.offerExclusionDetail = offerExclusionDetail;
    }

    public List<MarketingTier> getTiers() { return tiers; }
    public void setTiers(List<MarketingTier> tiers) { this.tiers = tiers == null ? new ArrayList<>() : tiers; }

    public List<MarketingPromotion> getPromotions() { return promotions; }
    public void setPromotions(List<MarketingPromotion> promotions) {
        this.promotions = promotions == null ? new ArrayList<>() : promotions;
    }

    public Map<Long, MarketingEligibility> getEligibility() { return eligibility; }
    public void setEligibility(Map<Long, MarketingEligibility> eligibility) {
        this.eligibility = eligibility == null ? new LinkedHashMap<>() : eligibility;
    }
}
