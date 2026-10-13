package com.chengyinhub.business.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * VIEW对象 view_product_like
 * 
 * @author webxh
 * @date 2025-08-30
 */
public class ViewProductLike extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long memberId;

    /** 产品ID */
    @Excel(name = "产品ID")
    private Long productId;

    /** 商品名称 */
    @Excel(name = "商品名称")
    private String productName;

    /** 主图 */
    @Excel(name = "主图")
    private String pic;

    /** 售价 */
    @Excel(name = "售价")
    private Integer price;

    /** 原价 */
    @Excel(name = "原价")
    private Integer originalPrice;

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

    public void setProductId(Long productId) 
    {
        this.productId = productId;
    }

    public Long getProductId() 
    {
        return productId;
    }

    public void setProductName(String productName) 
    {
        this.productName = productName;
    }

    public String getProductName() 
    {
        return productName;
    }

    public void setPic(String pic) 
    {
        this.pic = pic;
    }

    public String getPic() 
    {
        return pic;
    }

    public void setPrice(Integer price) 
    {
        this.price = price;
    }

    public Integer getPrice() 
    {
        return price;
    }

    public void setOriginalPrice(Integer originalPrice) 
    {
        this.originalPrice = originalPrice;
    }

    public Integer getOriginalPrice() 
    {
        return originalPrice;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("memberId", getMemberId())
            .append("productId", getProductId())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("productName", getProductName())
            .append("pic", getPic())
            .append("price", getPrice())
            .append("originalPrice", getOriginalPrice())
            .toString();
    }
}
