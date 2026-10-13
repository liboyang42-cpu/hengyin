package com.chengyinhub.business.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 优惠券领取使用记录对象 sms_coupon_history
 * 
 * @author webxh
 * @date 2025-09-01
 */
public class SmsCouponHistory extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** 优惠券ID */
    @Excel(name = "优惠券ID")
    private Long couponId;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long memberId;

    /** 优惠券码（唯一标识） */
    @Excel(name = "优惠券码", readConverterExp = "唯=一标识")
    private String couponCode;


    @Excel(name = "核销二维码")
    private String qrcodeUrl;


    /** 获取方式：0-&gt;后台赠送；1-&gt;主动领取 */
    @Excel(name = "获取方式：0-&gt;后台赠送；1-&gt;主动领取")
    private Integer getType;

    /** 领取时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "领取时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date getTime;

    /** 使用状态：0-&gt;未使用；1-&gt;已使用；2-&gt;已过期 */
    @Excel(name = "使用状态：0-&gt;未使用；1-&gt;已使用；2-&gt;已过期")
    private Integer useStatus;

    /** 使用时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "使用时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date useTime;

    /** 订单ID */
    @Excel(name = "订单ID")
    private Long orderId;

    /** 订单编号 */
    @Excel(name = "订单编号")
    private String orderSn;

    /** 有效开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "有效开始时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date startTime;

    /** 有效结束时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "有效结束时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date endTime;

    /** 备注 */
    @Excel(name = "备注")
    private String note;

    /** 删除标记 0 代表存在  2删除 */
    private Integer delFlag;

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setCouponId(Long couponId) 
    {
        this.couponId = couponId;
    }

    public Long getCouponId() 
    {
        return couponId;
    }

    public void setMemberId(Long memberId) 
    {
        this.memberId = memberId;
    }

    public Long getMemberId() 
    {
        return memberId;
    }


    public String getQrcodeUrl() {
        return qrcodeUrl;
    }

    public void setQrcodeUrl(String qrcodeUrl) {
        this.qrcodeUrl = qrcodeUrl;
    }

    public void setCouponCode(String couponCode) 
    {
        this.couponCode = couponCode;
    }

    public String getCouponCode() 
    {
        return couponCode;
    }

    public void setGetType(Integer getType) 
    {
        this.getType = getType;
    }

    public Integer getGetType() 
    {
        return getType;
    }

    public void setGetTime(Date getTime) 
    {
        this.getTime = getTime;
    }

    public Date getGetTime() 
    {
        return getTime;
    }

    public void setUseStatus(Integer useStatus) 
    {
        this.useStatus = useStatus;
    }

    public Integer getUseStatus() 
    {
        return useStatus;
    }

    public void setUseTime(Date useTime) 
    {
        this.useTime = useTime;
    }

    public Date getUseTime() 
    {
        return useTime;
    }

    public void setOrderId(Long orderId) 
    {
        this.orderId = orderId;
    }

    public Long getOrderId() 
    {
        return orderId;
    }

    public void setOrderSn(String orderSn) 
    {
        this.orderSn = orderSn;
    }

    public String getOrderSn() 
    {
        return orderSn;
    }

    public void setStartTime(Date startTime) 
    {
        this.startTime = startTime;
    }

    public Date getStartTime() 
    {
        return startTime;
    }

    public void setEndTime(Date endTime) 
    {
        this.endTime = endTime;
    }

    public Date getEndTime() 
    {
        return endTime;
    }

    public void setNote(String note) 
    {
        this.note = note;
    }

    public String getNote() 
    {
        return note;
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
            .append("couponId", getCouponId())
            .append("memberId", getMemberId())
                .append("qrcodeUrl", getQrcodeUrl())
            .append("couponCode", getCouponCode())
            .append("getType", getGetType())
            .append("getTime", getGetTime())
            .append("useStatus", getUseStatus())
            .append("useTime", getUseTime())
            .append("orderId", getOrderId())
            .append("orderSn", getOrderSn())
            .append("startTime", getStartTime())
            .append("endTime", getEndTime())
            .append("note", getNote())
            .append("delFlag", getDelFlag())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}
