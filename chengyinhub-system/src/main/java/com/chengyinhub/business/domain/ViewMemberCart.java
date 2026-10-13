package com.chengyinhub.business.domain;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * VIEW对象 view_member_cart
 * 
 * @author ruoyi
 * @date 2025-09-03
 */
public class ViewMemberCart extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 购物车表ID */
    private Long id;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long memberId;

    /** 商家ID */
    @Excel(name = "商家ID")
    private Long merchantId;

    /** 购物车类型：0本地商家 1线上商城  2积分商城 */
    @Excel(name = "购物车类型：0本地商家 1线上商城  2积分商城")
    private Long cartType;

    /** 商品ID */
    @Excel(name = "商品ID")
    private Long productId;

    /** SKU ID */
    @Excel(name = "SKU ID")
    private Long skuId;

    /** 商品属性 */
    @Excel(name = "商品属性")
    private String spData;

    /** 商品数量 */
    @Excel(name = "商品数量")
    private Long quantity;

    /** 0-&gt;失效；1-&gt;有效 */
    @Excel(name = "0-&gt;失效；1-&gt;有效")
    private Long status;

    /** 默认0 ，1是直接购买 */
    @Excel(name = "默认0 ，1是直接购买")
    private Long isBuy;

    /** 商品名称 */
    @Excel(name = "商品名称")
    private String productName;

    /** 主图 */
    @Excel(name = "主图")
    private String productPic;

    /** 销售价 */
    @Excel(name = "销售价")
    private BigDecimal price;

    /** 原价 */
    @Excel(name = "原价")
    private BigDecimal originalPrice;

    /** $column.columnComment */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private String skuName;

    /** $column.columnComment */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private String skuNameAr;

    /** 展示图片 */
    @Excel(name = "展示图片")
    private String skuPic;

    /** 重量 */
    @Excel(name = "重量")
    private BigDecimal weight;


    @Schema(description = "展示图片",hidden = true)
    @JsonIgnore
    private PmsSku pmsSku;

    public PmsSku getPmsSku() {
        return pmsSku;
    }

    public void setPmsSku(PmsSku pmsSku) {
        this.pmsSku = pmsSku;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setMemberId(Long memberId) 
    {
        this.memberId = memberId;
    }

    public Long getMemberId() 
    {
        return memberId;
    }

    public void setMerchantId(Long merchantId) 
    {
        this.merchantId = merchantId;
    }

    public Long getMerchantId() 
    {
        return merchantId;
    }

    public void setCartType(Long cartType) 
    {
        this.cartType = cartType;
    }

    public Long getCartType() 
    {
        return cartType;
    }

    public void setProductId(Long productId) 
    {
        this.productId = productId;
    }

    public Long getProductId() 
    {
        return productId;
    }

    public void setSkuId(Long skuId) 
    {
        this.skuId = skuId;
    }

    public Long getSkuId() 
    {
        return skuId;
    }

    public void setSpData(String spData) 
    {
        this.spData = spData;
    }

    public String getSpData() 
    {
        return spData;
    }

    public void setQuantity(Long quantity) 
    {
        this.quantity = quantity;
    }

    public Long getQuantity() 
    {
        return quantity;
    }

    public void setStatus(Long status) 
    {
        this.status = status;
    }

    public Long getStatus() 
    {
        return status;
    }

    public void setIsBuy(Long isBuy) 
    {
        this.isBuy = isBuy;
    }

    public Long getIsBuy() 
    {
        return isBuy;
    }

    public void setProductName(String productName) 
    {
        this.productName = productName;
    }

    public String getProductName() 
    {
        return productName;
    }

    public void setProductPic(String productPic) 
    {
        this.productPic = productPic;
    }

    public String getProductPic() 
    {
        return productPic;
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

    public void setSkuName(String skuName) 
    {
        this.skuName = skuName;
    }

    public String getSkuName() 
    {
        return skuName;
    }

    public void setSkuNameAr(String skuNameAr) 
    {
        this.skuNameAr = skuNameAr;
    }

    public String getSkuNameAr() 
    {
        return skuNameAr;
    }

    public void setSkuPic(String skuPic) 
    {
        this.skuPic = skuPic;
    }

    public String getSkuPic() 
    {
        return skuPic;
    }

    public void setWeight(BigDecimal weight) 
    {
        this.weight = weight;
    }

    public BigDecimal getWeight() 
    {
        return weight;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("memberId", getMemberId())
            .append("merchantId", getMerchantId())
            .append("cartType", getCartType())
            .append("productId", getProductId())
            .append("skuId", getSkuId())
            .append("spData", getSpData())
            .append("quantity", getQuantity())
            .append("status", getStatus())
            .append("isBuy", getIsBuy())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("productName", getProductName())
            .append("productPic", getProductPic())
            .append("price", getPrice())
            .append("originalPrice", getOriginalPrice())
            .append("skuName", getSkuName())
            .append("skuNameAr", getSkuNameAr())
            .append("skuPic", getSkuPic())
            .append("weight", getWeight())
            .toString();
    }
}
