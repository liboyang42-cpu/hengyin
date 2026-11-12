package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 订单促销成交快照(insert-only,一单一行)。表 marketing_order_adjustment。
 *
 * <p><b>为什么单独一张表而不改 cms_registration</b>:
 * {@code cms_registration.discount_amount} 现在的语义是"俱乐部会员价让利",
 * 现有报表与退款都按这个语义读;把促销额直接加进那一列会静默改掉既有列的含义。
 * 因此促销分项另立不可变快照,由建单事务同事务写入 —— 满足"下单快照、事后不回读现规则",
 * 又不改变既有资金列语义。
 *
 * <p><b>退款只读本表</b>:{@link #appliedJson} 是按成交时规则版本冻结的分项正文,
 * 退款/对账按它回冲,不重算当前营销配置。
 */
public class MarketingOrderAdjustment {

    private static final long serialVersionUID = 1L;

    /** 1 = cms_registration(v1 仅此一种)。 */
    public static final int ORDER_REGISTRATION = 1;

    private Long id;
    private Integer orderType;
    private Long orderId;
    private Long memberId;
    private Integer ownerType;
    private Long ownerId;
    /** 票面合计(任何优惠之前)。 */
    private BigDecimal listAmount;
    /** 阶梯价让利。 */
    private BigDecimal tierDiscountAmount;
    /** 组合包/礼品价让利。 */
    private BigDecimal offerDiscountAmount;
    /** 触发式促销让利合计。 */
    private BigDecimal promotionDiscountAmount;
    /** 一切让利之后的应付(未扣积分;积分抵扣仍由既有 computePrice 处理)。 */
    private BigDecimal payableAmount;
    private Long offerId;
    private String offerCode;
    /** 分项正文(JSON),含每一条的 code/kind/amount/ruleVersion 与被排除原因。 */
    private String appliedJson;
    /** 参与计算的规则版本串:offer:3|tier:7|promo:12,9。 */
    private String ruleVersion;
    /** 报价签名:与 quoteSign 扩展位同源,便于比对"这单是不是按那个报价成交的"。 */
    private String quoteFingerprint;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getOrderType() { return orderType; }
    public void setOrderType(Integer orderType) { this.orderType = orderType; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public Integer getOwnerType() { return ownerType; }
    public void setOwnerType(Integer ownerType) { this.ownerType = ownerType; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

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

    public Long getOfferId() { return offerId; }
    public void setOfferId(Long offerId) { this.offerId = offerId; }

    public String getOfferCode() { return offerCode; }
    public void setOfferCode(String offerCode) { this.offerCode = offerCode; }

    public String getAppliedJson() { return appliedJson; }
    public void setAppliedJson(String appliedJson) { this.appliedJson = appliedJson; }

    public String getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(String ruleVersion) { this.ruleVersion = ruleVersion; }

    public String getQuoteFingerprint() { return quoteFingerprint; }
    public void setQuoteFingerprint(String quoteFingerprint) { this.quoteFingerprint = quoteFingerprint; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
