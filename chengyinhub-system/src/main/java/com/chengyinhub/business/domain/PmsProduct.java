package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.List;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 积分商城商品对象 pms_product
 * 
 * @author ruoyi
 * @date 2025-08-25
 */
public class PmsProduct extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;


    /** 商户ID */
    @Excel(name = "商户ID")
    private Long merchantId;

    /** 商品名称 */
    @Excel(name = "商品名称")
    private String productName;

    /** 主图 */
    @Excel(name = "主图")
    private String pic;

    /** 画册图片，连产品图片限制为5张，以分号分割 */
    @Excel(name = "画册图片，连产品图片限制为5张，以分号分割")
    private String albumPics;

    /** 上架状态：0 审核中  1上架  2下架 */
    @Excel(name = "上架状态：0 审核中  1上架  2下架")
    private Integer publishStatus;

    /** 排序 */
    @Excel(name = "排序")
    private Integer sortId;

    /** "排序方式0默认  1时间倒序  2时间升序  3价格倒序  4价格升序 */
    @Excel(name = "排序方式")
    private Integer sortType;

    /** 售价 */
    @Excel(name = "售价")
    private Integer price;

    /** 原价 */
    @Excel(name = "原价")
    private Integer originalPrice;

    /** 库存 */
    @Excel(name = "库存")
    private Long stock;

    /** 单位 */
    @Excel(name = "单位")
    private String unit;

    /** 商品重量，默认为克 */
    @Excel(name = "商品重量，默认为克")
    private BigDecimal weight;

    /** 产品详情网页内容 */
    @Excel(name = "产品详情网页内容")
    private String detailHtml;

    /** 商品销售属性，json格式 */
    @Excel(name = "商品销售属性，json格式")
    private String productAttr;

    /** 删除标记 0 代表存在  2删除 */
    private Integer delFlag;


    private List<PmsSku> skuList;

    public List<PmsSku> getSkuList() {
        return skuList;
    }

    public void setSkuList(List<PmsSku> skuList) {
        this.skuList = skuList;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }


    public void setMerchantId(Long merchantId)
    {
        this.merchantId = merchantId;
    }

    public Long getMerchantId()
    {
        return merchantId;
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

    public void setAlbumPics(String albumPics) 
    {
        this.albumPics = albumPics;
    }

    public String getAlbumPics() 
    {
        return albumPics;
    }

    public void setPublishStatus(Integer publishStatus) 
    {
        this.publishStatus = publishStatus;
    }

    public Integer getPublishStatus() 
    {
        return publishStatus;
    }

    public void setSortId(Integer sortId) 
    {
        this.sortId = sortId;
    }

    public Integer getSortId() 
    {
        return sortId;
    }


    public void setSortType(Integer sortType)
    {
        this.sortType = sortType;
    }

    public Integer getSortType()
    {
        return sortType;
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

    public void setStock(Long stock) 
    {
        this.stock = stock;
    }

    public Long getStock() 
    {
        return stock;
    }

    public void setUnit(String unit) 
    {
        this.unit = unit;
    }

    public String getUnit() 
    {
        return unit;
    }

    public void setWeight(BigDecimal weight) 
    {
        this.weight = weight;
    }

    public BigDecimal getWeight() 
    {
        return weight;
    }

    public void setDetailHtml(String detailHtml) 
    {
        this.detailHtml = detailHtml;
    }

    public String getDetailHtml() 
    {
        return detailHtml;
    }

    public void setProductAttr(String productAttr) 
    {
        this.productAttr = productAttr;
    }

    public String getProductAttr() 
    {
        return productAttr;
    }

    public void setDelFlag(Integer delFlag) 
    {
        this.delFlag = delFlag;
    }

    public Integer getDelFlag() 
    {
        return delFlag;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
                .append("merchantId", getMerchantId())
            .append("productName", getProductName())
            .append("pic", getPic())
            .append("albumPics", getAlbumPics())
            .append("publishStatus", getPublishStatus())
            .append("sortId", getSortId())
                .append("sortType", getSortType())
            .append("price", getPrice())
            .append("originalPrice", getOriginalPrice())
            .append("stock", getStock())
            .append("unit", getUnit())
            .append("weight", getWeight())
            .append("detailHtml", getDetailHtml())
            .append("productAttr", getProductAttr())
            .append("delFlag", getDelFlag())
            .append("skuList", getSkuList())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}
