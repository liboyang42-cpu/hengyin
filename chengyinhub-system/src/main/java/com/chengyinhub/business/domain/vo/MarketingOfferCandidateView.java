package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 玩家可见的促销供给投影(服务端定价,客户端只读)。
 *
 * <p>只下发"能买什么、多少钱、什么时候结束",不下发任何内部算法或将被排除的组合。
 * {@code available=false} 表示此刻不可用,{@code reasonCode} 给可解释原因,客户端不得自行启用。
 */
public class MarketingOfferCandidateView {

    private String offerCode;
    private Integer offerKind;
    private String name;
    private String description;
    private BigDecimal priceAmount;
    private BigDecimal listAmount;
    /** 相对分买的差额(正=省);配置时冻结值,仅用于文案参考。 */
    private BigDecimal differenceAmount;
    private Date validFrom;
    private Date validTo;
    private Integer maxPerMember;
    private Integer ruleVersion;
    private boolean available;
    private String reasonCode;
    private List<Item> items = new ArrayList<>();

    public static class Item {
        private Long ticketId;
        private Integer quantity;
        private BigDecimal unitListAmount;

        public Long getTicketId() { return ticketId; }
        public void setTicketId(Long ticketId) { this.ticketId = ticketId; }

        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }

        public BigDecimal getUnitListAmount() { return unitListAmount; }
        public void setUnitListAmount(BigDecimal unitListAmount) { this.unitListAmount = unitListAmount; }
    }

    public String getOfferCode() { return offerCode; }
    public void setOfferCode(String offerCode) { this.offerCode = offerCode; }

    public Integer getOfferKind() { return offerKind; }
    public void setOfferKind(Integer offerKind) { this.offerKind = offerKind; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getPriceAmount() { return priceAmount; }
    public void setPriceAmount(BigDecimal priceAmount) { this.priceAmount = priceAmount; }

    public BigDecimal getListAmount() { return listAmount; }
    public void setListAmount(BigDecimal listAmount) { this.listAmount = listAmount; }

    public BigDecimal getDifferenceAmount() { return differenceAmount; }
    public void setDifferenceAmount(BigDecimal differenceAmount) { this.differenceAmount = differenceAmount; }

    public Date getValidFrom() { return validFrom; }
    public void setValidFrom(Date validFrom) { this.validFrom = validFrom; }

    public Date getValidTo() { return validTo; }
    public void setValidTo(Date validTo) { this.validTo = validTo; }

    public Integer getMaxPerMember() { return maxPerMember; }
    public void setMaxPerMember(Integer maxPerMember) { this.maxPerMember = maxPerMember; }

    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }

    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items == null ? new ArrayList<>() : items; }
}
