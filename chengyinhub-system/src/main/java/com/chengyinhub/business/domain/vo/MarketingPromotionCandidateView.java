package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 玩家可见的触发式优惠投影。
 *
 * <p>只下发"有/没有、减多少、窗口到什么时候";{@code eligible=false} 时
 * {@code reasonCode} 说清为什么不可用(未通关 / 超窗口 / 已用过 / 不叠加)。
 * 具体抵扣金额永远以报价接口返回的数值为准,本视图不参与计价。
 */
public class MarketingPromotionCandidateView {

    private String promoCode;
    private String name;
    private String description;
    private Integer triggerKind;
    private Integer discountType;
    private BigDecimal discountValue;
    private BigDecimal maxDiscountAmount;
    private BigDecimal minPayableAmount;
    private Integer windowHours;
    private String stackGroup;
    private boolean exclusive;
    private Integer priority;
    private boolean eligible;
    private String reasonCode;
    /** 触发事实时间(通关时刻),服务端事实,不是客户端上报。 */
    private Date sourceFactTime;
    /** 窗口截止;null = 尚未取得资格,故无窗口。 */
    private Date windowEndsAt;

    public String getPromoCode() { return promoCode; }
    public void setPromoCode(String promoCode) { this.promoCode = promoCode; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getTriggerKind() { return triggerKind; }
    public void setTriggerKind(Integer triggerKind) { this.triggerKind = triggerKind; }

    public Integer getDiscountType() { return discountType; }
    public void setDiscountType(Integer discountType) { this.discountType = discountType; }

    public BigDecimal getDiscountValue() { return discountValue; }
    public void setDiscountValue(BigDecimal discountValue) { this.discountValue = discountValue; }

    public BigDecimal getMaxDiscountAmount() { return maxDiscountAmount; }
    public void setMaxDiscountAmount(BigDecimal maxDiscountAmount) { this.maxDiscountAmount = maxDiscountAmount; }

    public BigDecimal getMinPayableAmount() { return minPayableAmount; }
    public void setMinPayableAmount(BigDecimal minPayableAmount) { this.minPayableAmount = minPayableAmount; }

    public Integer getWindowHours() { return windowHours; }
    public void setWindowHours(Integer windowHours) { this.windowHours = windowHours; }

    public String getStackGroup() { return stackGroup; }
    public void setStackGroup(String stackGroup) { this.stackGroup = stackGroup; }

    public boolean isExclusive() { return exclusive; }
    public void setExclusive(boolean exclusive) { this.exclusive = exclusive; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public boolean isEligible() { return eligible; }
    public void setEligible(boolean eligible) { this.eligible = eligible; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public Date getSourceFactTime() { return sourceFactTime; }
    public void setSourceFactTime(Date sourceFactTime) { this.sourceFactTime = sourceFactTime; }

    public Date getWindowEndsAt() { return windowEndsAt; }
    public void setWindowEndsAt(Date windowEndsAt) { this.windowEndsAt = windowEndsAt; }
}
