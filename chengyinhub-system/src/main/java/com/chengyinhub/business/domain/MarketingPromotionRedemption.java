package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 促销使用事实(insert-only,状态只允许 USED → 撤销)。表 marketing_promotion_redemption。
 *
 * <p><b>一次性语义</b>:{@code UNIQUE(promotion_id, member_id, source_fact_id)} ——
 * 同一个触发事实(某一次通关)对同一促销只能兑一次。退款只把状态改成
 * "已随退款撤销",<b>不会删行、也不会把该事实重新放回可领池</b>:
 * 删行即等于"退款后可再领一次",那正是要防的重复领取。
 *
 * <p>退款与对账读本表 + {@link MarketingOrderAdjustment} 的成交快照,
 * <b>不重算现规则</b>:促销配置在成交后改版,已成交订单的优惠额仍按冻结快照。
 */
public class MarketingPromotionRedemption {

    private static final long serialVersionUID = 1L;

    public static final int SOURCE_FACT_BADGE = 1;

    public static final int STATUS_USED = 1;
    public static final int STATUS_REVOKED_BY_REFUND = 2;

    private Long id;
    private Long promotionId;
    /** 冗余业务码:快照可读,不依赖 join 时点的配置是否还在。 */
    private String promoCode;
    private Long memberId;
    private Integer sourceFactType;
    private Long sourceFactId;
    private Integer orderType;
    private Long orderId;
    /** 本单实际抵扣金额(元),来自报价计算,不事后重算。 */
    private BigDecimal discountAmount;
    private Integer ruleVersion;
    private Integer status;
    /** 幂等键:promo:{promotionId}:{orderType}:{orderId}。 */
    private String idempotencyKey;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPromotionId() { return promotionId; }
    public void setPromotionId(Long promotionId) { this.promotionId = promotionId; }

    public String getPromoCode() { return promoCode; }
    public void setPromoCode(String promoCode) { this.promoCode = promoCode; }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public Integer getSourceFactType() { return sourceFactType; }
    public void setSourceFactType(Integer sourceFactType) { this.sourceFactType = sourceFactType; }

    public Long getSourceFactId() { return sourceFactId; }
    public void setSourceFactId(Long sourceFactId) { this.sourceFactId = sourceFactId; }

    public Integer getOrderType() { return orderType; }
    public void setOrderType(Integer orderType) { this.orderType = orderType; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }

    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
