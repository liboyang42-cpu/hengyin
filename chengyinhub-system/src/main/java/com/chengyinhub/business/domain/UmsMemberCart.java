package com.chengyinhub.business.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 购物车对象 ums_member_cart
 * 
 * @author ruoyi
 * @date 2025-09-02
 */
public class UmsMemberCart extends BaseEntity
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
            .toString();
    }
}
