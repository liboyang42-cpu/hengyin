package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 促销报价结果:分项 + 守恒的应付金额 + 报价指纹。
 *
 * <p><b>金额守恒是不可协商的</b>:
 * {@code listAmount - tierDiscount - offerDiscount - promotionDiscount == payableAmount},
 * 每一步都按 2 位小数四舍五入,且任何一次减免都不会把余额压到负数。
 * 只要等式不成立,计算器直接抛异常 —— 宁可不报价,也不报一个加不回去的数。
 *
 * <p><b>payableAmount 是"扣积分之前"的应付</b>:积分抵扣仍由既有
 * {@code CmsRegistrationServiceImpl.computePrice} 处理,避免两处各算一遍积分。
 */
public class MarketingPromotionQuote {

    /** 分项类别。 */
    public static final String KIND_TIER = "TIER";
    public static final String KIND_OFFER = "OFFER";
    public static final String KIND_PROMOTION = "PROMOTION";

    private List<MarketingQuoteLine> lines = new ArrayList<>();
    private List<MarketingAdjustment> adjustments = new ArrayList<>();
    private List<MarketingExclusion> exclusions = new ArrayList<>();

    private Long memberId;
    private Integer ownerType;
    private Long ownerId;
    private BigDecimal listAmount = BigDecimal.ZERO;
    private BigDecimal tierDiscountAmount = BigDecimal.ZERO;
    private BigDecimal offerDiscountAmount = BigDecimal.ZERO;
    private BigDecimal promotionDiscountAmount = BigDecimal.ZERO;
    private BigDecimal payableAmount = BigDecimal.ZERO;

    private Long offerId;
    private String offerCode;
    private Integer offerKind;

    private List<Long> promotionIds = new ArrayList<>();
    private List<String> promotionCodes = new ArrayList<>();
    /** offer:3|tier:7|promo:12,9 —— 参与计算的规则版本串,进快照与签名。 */
    private String ruleVersion = "";
    /** 促销部分的报价指纹:进 quoteSign 扩展位,并写入订单快照。 */
    private String marketingSign = "";

    /** 是否真的发生了任何促销效果(供调用方决定要不要多走一次写路径)。 */
    public boolean hasAnyAdjustment() {
        return offerId != null || !adjustments.isEmpty();
    }

    public List<MarketingQuoteLine> getLines() { return lines; }
    public void setLines(List<MarketingQuoteLine> lines) { this.lines = lines == null ? new ArrayList<>() : lines; }

    public List<MarketingAdjustment> getAdjustments() { return adjustments; }
    public void setAdjustments(List<MarketingAdjustment> adjustments) {
        this.adjustments = adjustments == null ? new ArrayList<>() : adjustments;
    }

    public List<MarketingExclusion> getExclusions() { return exclusions; }
    public void setExclusions(List<MarketingExclusion> exclusions) {
        this.exclusions = exclusions == null ? new ArrayList<>() : exclusions;
    }

    public BigDecimal getListAmount() { return listAmount; }
    public void setListAmount(BigDecimal listAmount) { this.listAmount = listAmount; }

    public BigDecimal getTierDiscountAmount() { return tierDiscountAmount; }
    public void setTierDiscountAmount(BigDecimal tierDiscountAmount) { this.tierDiscountAmount = tierDiscountAmount; }

    public BigDecimal getOfferDiscountAmount() { return offerDiscountAmount; }
    public void setOfferDiscountAmount(BigDecimal offerDiscountAmount) { this.offerDiscountAmount = offerDiscountAmount; }

    public BigDecimal getPromotionDiscountAmount() { return promotionDiscountAmount; }
    public void setPromotionDiscountAmount(BigDecimal promotionDiscountAmount) {
        this.promotionDiscountAmount = promotionDiscountAmount;
    }

    public BigDecimal getPayableAmount() { return payableAmount; }
    public void setPayableAmount(BigDecimal payableAmount) { this.payableAmount = payableAmount; }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public Integer getOwnerType() { return ownerType; }
    public void setOwnerType(Integer ownerType) { this.ownerType = ownerType; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public Long getOfferId() { return offerId; }
    public void setOfferId(Long offerId) { this.offerId = offerId; }

    public String getOfferCode() { return offerCode; }
    public void setOfferCode(String offerCode) { this.offerCode = offerCode; }

    public Integer getOfferKind() { return offerKind; }
    public void setOfferKind(Integer offerKind) { this.offerKind = offerKind; }

    public List<Long> getPromotionIds() { return promotionIds; }
    public void setPromotionIds(List<Long> promotionIds) {
        this.promotionIds = promotionIds == null ? new ArrayList<>() : promotionIds;
    }

    public List<String> getPromotionCodes() { return promotionCodes; }
    public void setPromotionCodes(List<String> promotionCodes) {
        this.promotionCodes = promotionCodes == null ? new ArrayList<>() : promotionCodes;
    }

    public String getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(String ruleVersion) { this.ruleVersion = ruleVersion == null ? "" : ruleVersion; }

    public String getMarketingSign() { return marketingSign; }
    public void setMarketingSign(String marketingSign) {
        this.marketingSign = marketingSign == null ? "" : marketingSign;
    }
}
