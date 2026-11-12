package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/** 一条已生效的让利分项。amount 恒为正数,含义是"减免了多少元"。 */
public class MarketingAdjustment {

    private String kind;
    /** 业务码:促销 promoCode / 阶梯 tierCode / 组合包 offerCode。 */
    private String code;
    private String label;
    private BigDecimal amount = BigDecimal.ZERO;
    private Integer ruleVersion;
    private Long promotionId;
    private Long offerId;
    private Integer sourceFactType;
    private Long sourceFactId;

    public MarketingAdjustment() {
    }

    public MarketingAdjustment(String kind, String code, String label, BigDecimal amount, Integer ruleVersion) {
        this.kind = kind;
        this.code = code;
        this.label = label;
        this.amount = amount;
        this.ruleVersion = ruleVersion;
    }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }

    public Long getPromotionId() { return promotionId; }
    public void setPromotionId(Long promotionId) { this.promotionId = promotionId; }

    public Long getOfferId() { return offerId; }
    public void setOfferId(Long offerId) { this.offerId = offerId; }

    public Integer getSourceFactType() { return sourceFactType; }
    public void setSourceFactType(Integer sourceFactType) { this.sourceFactType = sourceFactType; }

    public Long getSourceFactId() { return sourceFactId; }
    public void setSourceFactId(Long sourceFactId) { this.sourceFactId = sourceFactId; }
}
