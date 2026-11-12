package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 人数阶梯价:同一票种按本单购买人数给不同单价。表 marketing_tier。
 *
 * <p><b>与既有阶梯的区别</b>:{@code PricingService.initiatorGuaranteeRate(verifiedMerchantCount)}
 * 是"商家侧"阶梯(按核销商家数抬发起人保底率),不是买家侧价格;两者不共用、不互相改写。
 *
 * <p><b>落点</b>:阶梯改的是<b>单价基数</b>,不是"折扣"。所以它先于一切促销生效,
 * 之后的分项折扣在阶梯价上再算,金额守恒(见 {@code MarketingPromotionCalculator})。
 *
 * <p><b>不设下限地压价</b>:单价只能 ≤ 票面现价,不能高于票面;
 * 也绝不绕过 {@code PricingService} 的最低报价地板 —— 那是开售时的定价闸,本表不改它。
 */
public class MarketingTier extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 通配票种:0 = 该范围下所有票种。 */
    public static final long TICKET_ALL = 0L;

    private Long id;
    /** 稳定业务码,便于后台检索与日志定位。 */
    private String tierCode;
    /** 适用范围:1=主题 2=活动。 */
    private Integer ownerType;
    private Long ownerId;
    /** 票种ID;0 = 该范围内全部票种(此时同一区间不得与具体票种重叠)。 */
    private Long ticketId;
    private Integer minQuantity;
    /** null = 无上界。 */
    private Integer maxQuantity;
    private BigDecimal unitPrice;
    /** 0=停用 1=启用。 */
    private Integer status;
    private Integer ruleVersion;
    private Date validFrom;
    private Date validTo;
    private Integer delFlag;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTierCode() { return tierCode; }
    public void setTierCode(String tierCode) { this.tierCode = tierCode; }

    public Integer getOwnerType() { return ownerType; }
    public void setOwnerType(Integer ownerType) { this.ownerType = ownerType; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }

    public Integer getMinQuantity() { return minQuantity; }
    public void setMinQuantity(Integer minQuantity) { this.minQuantity = minQuantity; }

    public Integer getMaxQuantity() { return maxQuantity; }
    public void setMaxQuantity(Integer maxQuantity) { this.maxQuantity = maxQuantity; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }

    public Date getValidFrom() { return validFrom; }
    public void setValidFrom(Date validFrom) { this.validFrom = validFrom; }

    public Date getValidTo() { return validTo; }
    public void setValidTo(Date validTo) { this.validTo = validTo; }

    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
}
