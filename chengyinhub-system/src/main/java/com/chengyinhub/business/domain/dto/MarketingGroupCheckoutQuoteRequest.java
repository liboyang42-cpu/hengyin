package com.chengyinhub.business.domain.dto;

/**
 * 团体/学校商品报价请求。
 *
 * <p>故意不接收 ownerType/ownerId/ticketId/金额：这些只能从商品绑定解析，
 * 客户端提交的任何票价、人数上限或席位数字都不参与成交。
 */
public class MarketingGroupCheckoutQuoteRequest {

    private Long productId;
    /** 购买份数（每份对应一张原票种），默认 1。 */
    private Integer quantity;
    /** 0 不使用积分，1 使用；是否可抵由原报价计算。 */
    private Integer isUsePoint;
    /** 组合包/礼品等促销业务码；价格与适用性由原服务端判定。 */
    private String offerCode;
    /** 自由定向票的参与日期 yyyy-MM-dd；经典定向票留空。 */
    private String participateDate;

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public Integer getIsUsePoint() { return isUsePoint; }
    public void setIsUsePoint(Integer isUsePoint) { this.isUsePoint = isUsePoint; }

    public String getOfferCode() { return offerCode; }
    public void setOfferCode(String offerCode) { this.offerCode = offerCode; }

    public String getParticipateDate() { return participateDate; }
    public void setParticipateDate(String participateDate) { this.participateDate = participateDate; }
}
