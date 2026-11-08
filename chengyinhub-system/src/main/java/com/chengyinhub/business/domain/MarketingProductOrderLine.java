package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 成交时冻结的分摊行 marketing_product_order_line。
 *
 * <p>M2-F 的首次使用激活只读本表（`selectGrantLines`），不需要解析
 * {@code product_snapshot_json}，避免对 JSON 结构做猜测。
 */
public class MarketingProductOrderLine {

    private Long id;
    private Long orderId;
    private Long topicId;
    private BigDecimal listAmount;
    private BigDecimal payableAmount;
    /** -1 首次使用 0 永久 1 购买起 N 日 2 固定期限（与 M2-F GrantLine 同义）。 */
    private Integer validityKind;
    private Integer validDays;
    private Date fixedValidFrom;
    private Date fixedValidTo;
    private Integer exploreCapacity;
    private Integer sortNo;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public BigDecimal getListAmount() { return listAmount; }
    public void setListAmount(BigDecimal listAmount) { this.listAmount = listAmount; }

    public BigDecimal getPayableAmount() { return payableAmount; }
    public void setPayableAmount(BigDecimal payableAmount) { this.payableAmount = payableAmount; }

    public Integer getValidityKind() { return validityKind; }
    public void setValidityKind(Integer validityKind) { this.validityKind = validityKind; }

    public Integer getValidDays() { return validDays; }
    public void setValidDays(Integer validDays) { this.validDays = validDays; }

    public Date getFixedValidFrom() { return fixedValidFrom; }
    public void setFixedValidFrom(Date fixedValidFrom) { this.fixedValidFrom = fixedValidFrom; }

    public Date getFixedValidTo() { return fixedValidTo; }
    public void setFixedValidTo(Date fixedValidTo) { this.fixedValidTo = fixedValidTo; }

    public Integer getExploreCapacity() { return exploreCapacity; }
    public void setExploreCapacity(Integer exploreCapacity) { this.exploreCapacity = exploreCapacity; }

    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
