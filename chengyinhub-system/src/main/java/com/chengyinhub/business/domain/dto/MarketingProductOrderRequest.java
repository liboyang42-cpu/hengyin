package com.chengyinhub.business.domain.dto;

/** 商品下单请求。requestId 是幂等键；quoteSign 绑定服务端报价，价格不信任客户端。 */
public class MarketingProductOrderRequest {

    private Long productId;
    private Integer payMode;
    private Integer quantity;
    private Integer fulfillmentKind;
    private String originalOrderNo;
    private Long recipientMemberId;
    private String quoteSign;
    /** 报价到期毫秒；参与签名，改大即签名不符。 */
    private Long quoteExpiresAt;
    private String requestId;

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Integer getPayMode() { return payMode; }
    public void setPayMode(Integer payMode) { this.payMode = payMode; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public Integer getFulfillmentKind() { return fulfillmentKind; }
    public void setFulfillmentKind(Integer fulfillmentKind) { this.fulfillmentKind = fulfillmentKind; }

    public String getOriginalOrderNo() { return originalOrderNo; }
    public void setOriginalOrderNo(String originalOrderNo) { this.originalOrderNo = originalOrderNo; }

    public Long getRecipientMemberId() { return recipientMemberId; }
    public void setRecipientMemberId(Long recipientMemberId) { this.recipientMemberId = recipientMemberId; }

    public String getQuoteSign() { return quoteSign; }
    public void setQuoteSign(String quoteSign) { this.quoteSign = quoteSign; }

    public Long getQuoteExpiresAt() { return quoteExpiresAt; }
    public void setQuoteExpiresAt(Long quoteExpiresAt) { this.quoteExpiresAt = quoteExpiresAt; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
