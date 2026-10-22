package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * DOC2 F7 用户发票抬头。titleName / taxNo 由 mapper 侧 PiiCryptoTypeHandler 落密文，
 * 对外回读一律走掩码视图，完整税号不出服务端。
 */
public class UserInvoiceTitle {
    public static final String TYPE_PERSONAL = "PERSONAL";
    public static final String TYPE_COMPANY = "COMPANY";

    private Long id;
    private Long memberId;
    private String titleType;
    private String titleName;
    private String taxNo;
    private Integer isDefault;
    private Integer delFlag;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getMemberId() { return memberId; } public void setMemberId(Long v) { memberId = v; }
    public String getTitleType() { return titleType; } public void setTitleType(String v) { titleType = v; }
    public String getTitleName() { return titleName; } public void setTitleName(String v) { titleName = v; }
    public String getTaxNo() { return taxNo; } public void setTaxNo(String v) { taxNo = v; }
    public Integer getIsDefault() { return isDefault; } public void setIsDefault(Integer v) { isDefault = v; }
    public Integer getDelFlag() { return delFlag; } public void setDelFlag(Integer v) { delFlag = v; }
    public Date getCreateTime() { return createTime; } public void setCreateTime(Date v) { createTime = v; }
    public Date getUpdateTime() { return updateTime; } public void setUpdateTime(Date v) { updateTime = v; }
}
