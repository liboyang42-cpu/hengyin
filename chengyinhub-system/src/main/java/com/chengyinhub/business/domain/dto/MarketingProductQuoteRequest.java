package com.chengyinhub.business.domain.dto;

/** 商品报价请求。金额永远由服务端按商品配置重算，客户端只回传签名。 */
public class MarketingProductQuoteRequest {

    private Long productId;
    private Integer quantity;
    /** 1自用 2礼品 3加座。 */
    private Integer fulfillmentKind;
    /** 加座时必填：被加座的原商品订单号。 */
    private String originalOrderNo;
    /** 礼品可选：预绑定领取人。 */
    private Long recipientMemberId;

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public Integer getFulfillmentKind() { return fulfillmentKind; }
    public void setFulfillmentKind(Integer fulfillmentKind) { this.fulfillmentKind = fulfillmentKind; }

    public String getOriginalOrderNo() { return originalOrderNo; }
    public void setOriginalOrderNo(String originalOrderNo) { this.originalOrderNo = originalOrderNo; }

    public Long getRecipientMemberId() { return recipientMemberId; }
    public void setRecipientMemberId(Long recipientMemberId) { this.recipientMemberId = recipientMemberId; }
}
