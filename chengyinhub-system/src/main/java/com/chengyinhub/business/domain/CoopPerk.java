package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 协作权益申报 coop_perk(报告§5.1 · D-3)。
 * 受邀商家同意合作时,从常备权益模板申报本次合作提供的礼品/券/折扣,挂到该邀约。
 * 章节开放招商则从模板生成一份独立快照，直接归属商家、不依赖邀约。
 */
public class CoopPerk extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    /** 所属邀约；章节开放招商生成的独立快照为空 */
    private Long inviteId;
    /** 直接归属商家memberId；仅章节开放招商生成的独立快照非空 */
    private Long merchantId;
    /** 0礼品 1优惠券 2折扣体验 */
    private Integer perkType;
    private String name;
    /** 单人成本价 */
    private BigDecimal unitCost;
    /** 权益零售标价(玩家眼里这份权益值多少)。
     *  与 unitCost 分开是因为二者用途不同:章节入驻门槛 perkMinValue 比的是本列,不是成本价。
     *  可空:存量 perk 没报过零售价 ⇒ 门槛校验一律不通过(故意的,没报零售价就不该进供给池) */
    private BigDecimal retailValue;
    private Integer quota;
    private String redeemRule;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date validEnd;
    private Integer delFlag;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getInviteId() { return inviteId; }
    public void setInviteId(Long inviteId) { this.inviteId = inviteId; }
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
