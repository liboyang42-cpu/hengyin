package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 订单中所包含的商品对象 oms_order_item
 * 
 * @author ruoyi
 * @date 2025-08-25
 */
public class OmsOrderItem extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** 订单id */
    @Excel(name = "订单id")
    private Long orderId;

    /** $column.columnComment */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private Long productId;

    /** 商品编码 */
    @Excel(name = "商品编码")
    private String outProductId;

    /** 商品sku id */
    @Excel(name = "商品sku id")
    private Long skuId;

    /** sku编码 */
    @Excel(name = "sku编码")
    private String outSkuId;

    /** 展示图片 */
    @Excel(name = "展示图片")
    private String pic;

    /** 商品名称 */
    @Excel(name = "商品名称")
    private String productName;

    /** 价格 */
    @Excel(name = "价格")
    private BigDecimal price;

    /** 原价 */
    @Excel(name = "原价")
    private BigDecimal originalPrice;

    /** 购买数量 */
    @Excel(name = "购买数量")
    private Long quantity;

    /** 商品sku属性:[{"key":"颜色","value":"颜色"},{"key":"容量","value":"4G"}] */
    @Excel(name = "商品sku属性:[{\"key\":\"颜色\",\"value\":\"颜色\"},{\"key\":\"容量\",\"value\":\"4G\"}]")
    private String spData;

    /** 是否评价 0未评价 1已评价 （但是订单要已收货才能评价） */
    @Excel(name = "是否评价 0未评价 1已评价 ", readConverterExp = "但=是订单要已收货才能评价")
    private Long isEvaluate;

    /** 售后订单ID */
    @Excel(name = "售后订单ID")
    private Long aftersaleOrderId;

    /** 售后状态：0未申请售后   */
    @Excel(name = "售后状态：0未申请售后  ")
    private Long aftersaleStatus;

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setOrderId(Long orderId) 
    {
        this.orderId = orderId;
    }

    public Long getOrderId() 
    {
        return orderId;
    }

    public void setProductId(Long productId) 
    {
        this.productId = productId;
    }

    public Long getProductId() 
    {
        return productId;
    }

    public void setOutProductId(String outProductId) 
    {
        this.outProductId = outProductId;
    }

    public String getOutProductId() 
    {
        return outProductId;
    }

    public void setSkuId(Long skuId) 
    {
        this.skuId = skuId;
    }

    public Long getSkuId() 
    {
        return skuId;
    }

    public void setOutSkuId(String outSkuId) 
    {
        this.outSkuId = outSkuId;
    }

    public String getOutSkuId() 
    {
        return outSkuId;
    }

    public void setPic(String pic) 
    {
        this.pic = pic;
    }

    public String getPic() 
    {
        return pic;
    }

    public void setProductName(String productName) 
    {
        this.productName = productName;
    }

    public String getProductName() 
    {
        return productName;
    }

    public void setPrice(BigDecimal price) 
    {
        this.price = price;
    }

    public BigDecimal getPrice() 
    {
        return price;
    }

    public void setOriginalPrice(BigDecimal originalPrice) 
    {
        this.originalPrice = originalPrice;
    }

    public BigDecimal getOriginalPrice() 
    {
        return originalPrice;
    }

    public void setQuantity(Long quantity) 
    {
        this.quantity = quantity;
    }

    public Long getQuantity() 
    {
        return quantity;
    }

    public void setSpData(String spData) 
    {
        this.spData = spData;
    }

    public String getSpData() 
    {
        return spData;
    }

    public void setIsEvaluate(Long isEvaluate) 
    {
        this.isEvaluate = isEvaluate;
    }

    public Long getIsEvaluate() 
    {
        return isEvaluate;
    }

    public void setAftersaleOrderId(Long aftersaleOrderId) 
    {
        this.aftersaleOrderId = aftersaleOrderId;
    }

    public Long getAftersaleOrderId() 
    {
        return aftersaleOrderId;
    }

    public void setAftersaleStatus(Long aftersaleStatus) 
    {
        this.aftersaleStatus = aftersaleStatus;
    }

    public Long getAftersaleStatus() 
    {
        return aftersaleStatus;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("orderId", getOrderId())
            .append("productId", getProductId())
            .append("outProductId", getOutProductId())
            .append("skuId", getSkuId())
            .append("outSkuId", getOutSkuId())
            .append("pic", getPic())
            .append("productName", getProductName())
            .append("price", getPrice())
            .append("originalPrice", getOriginalPrice())
            .append("quantity", getQuantity())
            .append("spData", getSpData())
            .append("isEvaluate", getIsEvaluate())
            .append("aftersaleOrderId", getAftersaleOrderId())
            .append("aftersaleStatus", getAftersaleStatus())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}
