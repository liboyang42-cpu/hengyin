package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 促销供给的成员行:一张票 + 数量。表 marketing_offer_item。
 *
 * <p>组合包的构成必须由服务端可验证:报价时按 (票种, 数量) 逐项比对购物车,
 * 不接受客户端自报"这单属于某组合包"。礼品的成员行恒为一条、数量 1。
 */
public class MarketingOfferItem {

    private static final long serialVersionUID = 1L;

    /** 成员类型:1=票种(oms_ticket.id)。 */
    public static final int ITEM_TICKET = 1;

    private Long id;
    private Long offerId;
    private Integer itemType;
    private Long itemId;
    private Integer quantity;
    /** 配置时成员标价快照,仅用于后台展示与校验,报价按票的真源现价计算。 */
    private BigDecimal unitListAmount;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getOfferId() { return offerId; }
    public void setOfferId(Long offerId) { this.offerId = offerId; }

    public Integer getItemType() { return itemType; }
    public void setItemType(Integer itemType) { this.itemType = itemType; }

    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getUnitListAmount() { return unitListAmount; }
    public void setUnitListAmount(BigDecimal unitListAmount) { this.unitListAmount = unitListAmount; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
