package com.chengyinhub.business.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 【请填写功能名称】对象 ums_member_address
 * 
 * @author ruoyi
 * @date 2025-08-26
 */
public class UmsMemberAddress extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long memberId;

    /** 姓名 */
    @Excel(name = "姓名")
    private String fullName;

    /** 区号 */
    @Excel(name = "区号")
    private String areaCode;

    /** 手机 */
    @Excel(name = "手机")
    private String mobilePhone;

    /** 省市区，用腾讯的三级地址 */
    @Excel(name = "省市区，用腾讯的三级地址")
    private String province;

    /** 详细地址 */
    @Excel(name = "详细地址")
    private String detailAddress;

    /** 是否默认 0不是  1是 */
    @Excel(name = "是否默认 0不是  1是")
    private Integer isDefault;

    /** 删除标记 0 代表存在  2删除 */
    private Integer delFlag;

    /** 新增地址幂等键；仅当前会员范围内唯一。 */
    private String requestId;

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

    public void setFullName(String fullName) 
    {
        this.fullName = fullName;
    }

    public String getFullName() 
    {
        return fullName;
    }

    public void setAreaCode(String areaCode) 
    {
        this.areaCode = areaCode;
    }

    public String getAreaCode() 
    {
        return areaCode;
    }

    public void setMobilePhone(String mobilePhone) 
    {
        this.mobilePhone = mobilePhone;
    }

    public String getMobilePhone() 
    {
        return mobilePhone;
    }

    public void setProvince(String province) 
    {
        this.province = province;
    }

    public String getProvince() 
    {
        return province;
    }

    public void setDetailAddress(String detailAddress) 
    {
        this.detailAddress = detailAddress;
    }

    public String getDetailAddress() 
    {
        return detailAddress;
    }

    public void setIsDefault(Integer isDefault) 
    {
        this.isDefault = isDefault;
    }

    public Integer getIsDefault() 
    {
        return isDefault;
    }

    public void setDelFlag(Integer delFlag) 
    {
        this.delFlag = delFlag;
    }

    public Integer getDelFlag() 
    {
        return delFlag;
    }

    public String getRequestId() { return requestId; }

    public void setRequestId(String requestId) { this.requestId = requestId; }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("memberId", getMemberId())
            .append("fullName", getFullName())
            .append("areaCode", getAreaCode())
            .append("mobilePhone", getMobilePhone())
            .append("province", getProvince())
            .append("detailAddress", getDetailAddress())
            .append("isDefault", getIsDefault())
            .append("delFlag", getDelFlag())
            .append("requestId", getRequestId())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}
