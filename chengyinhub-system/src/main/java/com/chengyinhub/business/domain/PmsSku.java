package com.chengyinhub.business.domain;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * sku信息对象 pms_sku
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
public class PmsSku extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** $column.columnComment */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private Long skuSnapshotId;

    /** $column.columnComment */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private Long productId;

    /** sku编码 */
    @Excel(name = "sku编码")
    private String outSkuId;

    /** $column.columnComment */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private String skuName;

    /** $column.columnComment */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private String skuNameAr;

    /** 销售价 */
    @Excel(name = "销售价")
    private BigDecimal price;

    /** 重量 */
    @Excel(name = "重量")
    private BigDecimal weight;

    /** 原价 */
    @Excel(name = "原价")
    private BigDecimal originalPrice;

    /** 展示图片 */
    @Excel(name = "展示图片")
    private String pic;

    /** 库存 */
    @Excel(name = "库存")
    private Long stock;

    /** 商品销售属性，json格式 */
    @Excel(name = "商品销售属性，json格式")
    private String spData;


    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setSkuSnapshotId(Long skuSnapshotId) 
    {
        this.skuSnapshotId = skuSnapshotId;
    }

    public Long getSkuSnapshotId() 
    {
        return skuSnapshotId;
    }

    public void setProductId(Long productId) 
    {
        this.productId = productId;
    }

    public Long getProductId() 
    {
        return productId;
    }

    public void setOutSkuId(String outSkuId) 
    {
        this.outSkuId = outSkuId;
    }

    public String getOutSkuId() 
    {
        return outSkuId;
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

    public void setPrice(BigDecimal price) 
    {
        this.price = price;
    }

    public BigDecimal getPrice() 
    {
        return price;
    }

    public void setWeight(BigDecimal weight) 
    {
        this.weight = weight;
    }

    public BigDecimal getWeight() 
    {
        return weight;
    }

    public void setOriginalPrice(BigDecimal originalPrice) 
    {
        this.originalPrice = originalPrice;
    }

    public BigDecimal getOriginalPrice() 
    {
        return originalPrice;
    }

    public void setPic(String pic) 
    {
        this.pic = pic;
    }

    public String getPic() 
    {
        return pic;
    }

    public void setStock(Long stock) 
    {
        this.stock = stock;
    }

    public Long getStock() 
    {
        return stock;
    }

    public void setSpData(String spData) 
    {
        this.spData = spData;
    }

    public String getSpData() 
    {
        return spData;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("skuSnapshotId", getSkuSnapshotId())
            .append("productId", getProductId())
            .append("outSkuId", getOutSkuId())
            .append("skuName", getSkuName())
            .append("skuNameAr", getSkuNameAr())
            .append("price", getPrice())
            .append("weight", getWeight())
            .append("originalPrice", getOriginalPrice())
            .append("pic", getPic())
            .append("stock", getStock())
            .append("spData", getSpData())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}
