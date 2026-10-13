package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * P3 商家客户标签 merchant_customer_tag。
 */
public class MerchantCustomerTag extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long merchantId;
    private Integer merchantScopeVersion;
    private String tagCode;
    private String tagName;
    private String tagColor;
    private Integer sort;
    private Integer delFlag;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Integer getMerchantScopeVersion() { return merchantScopeVersion; }
    public void setMerchantScopeVersion(Integer value) { merchantScopeVersion = value; }
    public String getTagCode() { return tagCode; }
    public void setTagCode(String tagCode) { this.tagCode = tagCode; }
    public String getTagName() { return tagName; }
    public void setTagName(String tagName) { this.tagName = tagName; }
    public String getTagColor() { return tagColor; }
    public void setTagColor(String tagColor) { this.tagColor = tagColor; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
}
