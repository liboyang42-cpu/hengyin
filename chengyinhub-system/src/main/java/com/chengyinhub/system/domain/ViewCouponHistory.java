package com.chengyinhub.system.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * VIEW对象 view_coupon_history
 * 
 * @author ruoyi
 * @date 2025-10-16
 */
public class ViewCouponHistory extends BaseEntity
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

    /** 获取方式：0->后台赠送；1->主动领取 */
    @Excel(name = "获取方式：0->后台赠送；1->主动领取")
    private Long getType;

    /** 领取时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "领取时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date getTime;

    /** 使用状态：0->未使用；1->已使用；2->已过期 */
    @Excel(name = "使用状态：0->未使用；1->已使用；2->已过期")
    private Long useStatus;

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
    private Long delFlag;

    /** 优惠券名称 */
    @Excel(name = "优惠券名称")
    private String couponName;

    /** 优惠券类型：0礼品券 1、9折券  2、8折券 3、体验卡 */
    @Excel(name = "优惠券类型：0礼品券 1、9折券  2、8折券 3、体验卡")
    private Long couponType;

    /** 说明 */
    @Excel(name = "说明")
    private String couponDescription;

    /** 删除标记 0 代表存在  2删除 */
    @Excel(name = "删除标记 0 代表存在  2删除")
    private Long couponDelFlag;

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

    public void setCouponCode(String couponCode) 
    {
        this.couponCode = couponCode;
    }

    public String getCouponCode() 
    {
        return couponCode;
    }

    public String getQrcodeUrl() {
        return qrcodeUrl;
    }

    public void setQrcodeUrl(String qrcodeUrl) {
        this.qrcodeUrl = qrcodeUrl;
    }

    public void setGetType(Long getType) 
    {
        this.getType = getType;
    }

    public Long getGetType() 
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

    public void setUseStatus(Long useStatus) 
    {
        this.useStatus = useStatus;
    }

    public Long getUseStatus() 
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

    public void setDelFlag(Long delFlag) 
    {
        this.delFlag = delFlag;
    }

    public Long getDelFlag() 
    {
        return delFlag;
    }

    public void setCouponName(String couponName) 
    {
        this.couponName = couponName;
    }

    public String getCouponName() 
    {
        return couponName;
    }

    public void setCouponType(Long couponType) 
    {
        this.couponType = couponType;
    }

    public Long getCouponType() 
    {
        return couponType;
    }

    public void setCouponDescription(String couponDescription) 
    {
        this.couponDescription = couponDescription;
    }

    public String getCouponDescription() 
    {
        return couponDescription;
    }

    public void setCouponDelFlag(Long couponDelFlag) 
    {
        this.couponDelFlag = couponDelFlag;
    }

    public Long getCouponDelFlag() 
    {
        return couponDelFlag;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("couponId", getCouponId())
            .append("memberId", getMemberId())
            .append("couponCode", getCouponCode())
                .append("qrcodeUrl", getQrcodeUrl())
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
            .append("couponName", getCouponName())
            .append("couponType", getCouponType())
            .append("couponDescription", getCouponDescription())
            .append("couponDelFlag", getCouponDelFlag())
            .toString();
    }
}
