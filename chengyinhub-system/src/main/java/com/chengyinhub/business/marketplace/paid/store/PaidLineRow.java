package com.chengyinhub.business.marketplace.paid.store;
/** Persistence row; never serialize as a public response. */
public final class PaidLineRow {
    private Long id;
    public Long getId(){return id;} public void setId(Long value){id=value;}
    private Long orderId;
    public Long getOrderId(){return orderId;} public void setOrderId(Long value){orderId=value;}
    private Long productId;
    public Long getProductId(){return productId;} public void setProductId(Long value){productId=value;}
    private Long skuId;
    public Long getSkuId(){return skuId;} public void setSkuId(Long value){skuId=value;}
    private Long quantity;
    public Long getQuantity(){return quantity;} public void setQuantity(Long value){quantity=value;}
    private Long aftersaleOrderId;
    public Long getAftersaleOrderId(){return aftersaleOrderId;} public void setAftersaleOrderId(Long value){aftersaleOrderId=value;}
    private Long aftersaleStatus;
    public Long getAftersaleStatus(){return aftersaleStatus;} public void setAftersaleStatus(Long value){aftersaleStatus=value;}
    private java.math.BigDecimal price;
    public java.math.BigDecimal getPrice(){return price;} public void setPrice(java.math.BigDecimal value){price=value;}
    @Override public String toString(){return "PaidLineRow[redacted]";}
}
