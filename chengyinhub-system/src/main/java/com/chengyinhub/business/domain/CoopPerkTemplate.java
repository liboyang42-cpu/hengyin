package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 商家常备权益模板 coop_perk_template(报告§5.1 · F1-3)。
 * 商家维护礼品/券/折扣模板,邀约时快速引用。
 */
public class CoopPerkTemplate extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    /** 商家memberId(模板归属) */
    private Long merchantId;
    /** 0礼品 1优惠券 2折扣体验 */
    private Integer perkType;
    private String name;
    /** 单人成本价 */
    private BigDecimal unitCost;
    /** 权益零售标价(玩家眼里这份权益值多少)。
     *  与 unitCost 分开是因为二者用途不同:章节入驻门槛 perkMinValue 比的是本列,不是成本价。
     *  模板带上它,商家申报权益时才能把零售价一起带进 coop_perk,不用每次重填 */
    private BigDecimal retailValue;
    /** 总配额 */
    private Integer quota;
    private String redeemRule;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date validEnd;
    private Integer delFlag;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Integer getPerkType() { return perkType; }
    public void setPerkType(Integer perkType) { this.perkType = perkType; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }
    public BigDecimal getRetailValue() { return retailValue; }
    public void setRetailValue(BigDecimal retailValue) { this.retailValue = retailValue; }
    public Integer getQuota() { return quota; }
    public void setQuota(Integer quota) { this.quota = quota; }
    public String getRedeemRule() { return redeemRule; }
    public void setRedeemRule(String redeemRule) { this.redeemRule = redeemRule; }
    public Date getValidEnd() { return validEnd; }
    public void setValidEnd(Date validEnd) { this.validEnd = validEnd; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
}
