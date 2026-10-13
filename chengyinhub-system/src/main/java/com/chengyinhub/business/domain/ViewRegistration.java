package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * VIEW对象 view_registration
 * 
 * @author ruoyi
 * @date 2025-09-24
 */
public class ViewRegistration extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 报名ID */
    private Long id;

    /** 报名编号(唯一) */
    @Excel(name = "报名编号(唯一)")
    private String registrationNo;

    /** 拥有类型1、主题  2、活动 */
    @Excel(name = "拥有类型1、主题  2、活动")
    private Long ownerType;

    /** 主题ID/活动ID */
    @Excel(name = "主题ID/活动ID")
    private Long ownerId;

    /** 活动/主题名称(冗余) */
    @Excel(name = "活动/主题名称(冗余)")
    private String activityTitle;

    /** 票种ID */
    @Excel(name = "票种ID")
    private Long ticketId;

    /** 票种名称(冗余) */
    @Excel(name = "票种名称(冗余)")
    private String ticketName;

    /** 票种价格(冗余) */
    @Excel(name = "票种价格(冗余)")
    private BigDecimal ticketPrice;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long memberId;

    /** 报名姓名 */
    @Excel(name = "报名姓名")
    private String realName;

    /** 联系电话 */
    @Excel(name = "联系电话")
    private String phone;

    /** 邮箱 */
    @Excel(name = "邮箱")
    private String email;

    /** 地址 */
    @Excel(name = "地址")
    private String address;

    /** 地址ID */
    @Excel(name = "地址ID")
    private Long addressId;

    /** 订票数量 */
    @Excel(name = "订票数量")
    private Long orderNum;

    /** 原始金额 */
    @Excel(name = "原始金额")
    private BigDecimal originalAmount;

    /** 折扣金额 */
    @Excel(name = "折扣金额")
    private BigDecimal discountAmount;

    /** 应付总额 */
    @Excel(name = "应付总额")
    private BigDecimal totalAmount;

    /** 使用积分数量 */
    @Excel(name = "使用积分数量")
    private Long pointUsed;

    /** 积分抵扣金额 */
    @Excel(name = "积分抵扣金额")
    private BigDecimal pointDeductAmount;

    /** 积分抵扣比例(1积分=0.01元) */
    @Excel(name = "积分抵扣比例(1积分=0.01元)")
    private BigDecimal pointDeductRate;

    /** 最大可抵扣金额 */
    @Excel(name = "最大可抵扣金额")
    private BigDecimal maxPointDeduct;

    /** 实际需支付金额 */
    @Excel(name = "实际需支付金额")
    private BigDecimal payableAmount;

    /** 支付状态：0待支付 1支付中 2已支付 3支付失败 4已退款 */
    @Excel(name = "支付状态：0待支付 1支付中 2已支付 3支付失败 4已退款")
    private Integer paymentStatus;

    /** 支付方式：0纯积分 1纯微信 2积分+微信 */
    @Excel(name = "支付方式：0纯积分 1纯微信 2积分+微信")
    private Integer paymentType;

    /** 支付时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "支付时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date paymentTime;

    /** 支付交易号 */
    @Excel(name = "支付交易号")
    private String transactionId;

    /** 积分支付金额 */
    @Excel(name = "积分支付金额")
    private BigDecimal pointPaymentAmount;

    /** 微信支付金额 */
    @Excel(name = "微信支付金额")
    private BigDecimal wechatPaymentAmount;

    /** 报名状态：1待支付 2报名成功 3已取消 4已过期 */
    @Excel(name = "报名状态：1待支付 2报名成功 3已取消 4已过期")
    private Integer registrationStatus;

    /** 支付过期时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "支付过期时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date payExpireTime;

    /** 核销码 */
    @Excel(name = "核销码")
    private String verificationCode;

    /** 核销二维码图片URL */
    @Excel(name = "核销二维码图片URL")
    private String qrcodeUrl;

    /** 核销状态：0未核销 1已核销 */
    @Excel(name = "核销状态：0未核销 1已核销")
    private Integer verificationStatus;

    /** 核销时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "核销时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date verificationTime;

    /** 核销人ID */
    @Excel(name = "核销人ID")
    private Long verifierId;

    /** 积分是否返还 0未返  1已返 */
    @Excel(name = "积分是否返还 0未返  1已返")
    private Integer pointsReturned;

    /** 头像 */
    @Excel(name = "头像")
    private String avatar;

    /** 昵称 */
    @Excel(name = "昵称")
    private String nickname;

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setRegistrationNo(String registrationNo) 
    {
        this.registrationNo = registrationNo;
    }

    public String getRegistrationNo() 
    {
        return registrationNo;
    }

    public void setOwnerType(Long ownerType) 
    {
        this.ownerType = ownerType;
    }

    public Long getOwnerType() 
    {
        return ownerType;
    }

    public void setOwnerId(Long ownerId) 
    {
        this.ownerId = ownerId;
    }

    public Long getOwnerId() 
    {
        return ownerId;
    }

    public void setActivityTitle(String activityTitle) 
    {
        this.activityTitle = activityTitle;
    }

    public String getActivityTitle() 
    {
        return activityTitle;
    }

    public void setTicketId(Long ticketId) 
    {
        this.ticketId = ticketId;
    }

    public Long getTicketId() 
    {
        return ticketId;
    }

    public void setTicketName(String ticketName) 
    {
        this.ticketName = ticketName;
    }

    public String getTicketName() 
    {
        return ticketName;
    }

    public void setTicketPrice(BigDecimal ticketPrice) 
    {
        this.ticketPrice = ticketPrice;
    }

    public BigDecimal getTicketPrice() 
    {
        return ticketPrice;
    }

    public void setMemberId(Long memberId) 
    {
        this.memberId = memberId;
    }

    public Long getMemberId() 
    {
        return memberId;
    }

    public void setRealName(String realName) 
    {
        this.realName = realName;
    }

    public String getRealName() 
    {
        return realName;
    }

    public void setPhone(String phone) 
    {
        this.phone = phone;
    }

    public String getPhone() 
    {
        return phone;
    }

    public void setEmail(String email) 
    {
        this.email = email;
    }

    public String getEmail() 
    {
        return email;
    }

    public void setAddress(String address) 
    {
        this.address = address;
    }

    public String getAddress() 
    {
        return address;
    }

    public void setAddressId(Long addressId) 
    {
        this.addressId = addressId;
    }

    public Long getAddressId() 
    {
        return addressId;
    }

    public void setOrderNum(Long orderNum) 
    {
        this.orderNum = orderNum;
    }

    public Long getOrderNum() 
    {
        return orderNum;
    }

    public void setOriginalAmount(BigDecimal originalAmount) 
    {
        this.originalAmount = originalAmount;
    }

    public BigDecimal getOriginalAmount() 
    {
        return originalAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) 
    {
        this.discountAmount = discountAmount;
    }

    public BigDecimal getDiscountAmount() 
    {
        return discountAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) 
    {
        this.totalAmount = totalAmount;
    }

    public BigDecimal getTotalAmount() 
    {
        return totalAmount;
    }

    public void setPointUsed(Long pointUsed) 
    {
        this.pointUsed = pointUsed;
    }

    public Long getPointUsed() 
    {
        return pointUsed;
    }

    public void setPointDeductAmount(BigDecimal pointDeductAmount) 
    {
        this.pointDeductAmount = pointDeductAmount;
    }

    public BigDecimal getPointDeductAmount() 
    {
        return pointDeductAmount;
    }

    public void setPointDeductRate(BigDecimal pointDeductRate) 
    {
        this.pointDeductRate = pointDeductRate;
    }

    public BigDecimal getPointDeductRate() 
    {
        return pointDeductRate;
    }

    public void setMaxPointDeduct(BigDecimal maxPointDeduct) 
    {
        this.maxPointDeduct = maxPointDeduct;
    }

    public BigDecimal getMaxPointDeduct() 
    {
        return maxPointDeduct;
    }

    public void setPayableAmount(BigDecimal payableAmount) 
    {
        this.payableAmount = payableAmount;
    }

    public BigDecimal getPayableAmount() 
    {
        return payableAmount;
    }

    public void setPaymentStatus(Integer paymentStatus) 
    {
        this.paymentStatus = paymentStatus;
    }

    public Integer getPaymentStatus() 
    {
        return paymentStatus;
    }

    public void setPaymentType(Integer paymentType) 
    {
        this.paymentType = paymentType;
    }

    public Integer getPaymentType() 
    {
        return paymentType;
    }

    public void setPaymentTime(Date paymentTime) 
    {
        this.paymentTime = paymentTime;
    }

    public Date getPaymentTime() 
    {
        return paymentTime;
    }

    public void setTransactionId(String transactionId) 
    {
        this.transactionId = transactionId;
    }

    public String getTransactionId() 
    {
        return transactionId;
    }

    public void setPointPaymentAmount(BigDecimal pointPaymentAmount) 
    {
        this.pointPaymentAmount = pointPaymentAmount;
    }

    public BigDecimal getPointPaymentAmount() 
    {
        return pointPaymentAmount;
    }

    public void setWechatPaymentAmount(BigDecimal wechatPaymentAmount) 
    {
        this.wechatPaymentAmount = wechatPaymentAmount;
    }

    public BigDecimal getWechatPaymentAmount() 
    {
        return wechatPaymentAmount;
    }

    public void setRegistrationStatus(Integer registrationStatus) 
    {
        this.registrationStatus = registrationStatus;
    }

    public Integer getRegistrationStatus() 
    {
        return registrationStatus;
    }

    public void setPayExpireTime(Date payExpireTime) 
    {
        this.payExpireTime = payExpireTime;
    }

    public Date getPayExpireTime() 
    {
        return payExpireTime;
    }

    public void setVerificationCode(String verificationCode) 
    {
        this.verificationCode = verificationCode;
    }

    public String getVerificationCode() 
    {
        return verificationCode;
    }

    public void setQrcodeUrl(String qrcodeUrl) 
    {
        this.qrcodeUrl = qrcodeUrl;
    }

    public String getQrcodeUrl() 
    {
        return qrcodeUrl;
    }

    public void setVerificationStatus(Integer verificationStatus) 
    {
        this.verificationStatus = verificationStatus;
    }

    public Integer getVerificationStatus() 
    {
        return verificationStatus;
    }

    public void setVerificationTime(Date verificationTime) 
    {
        this.verificationTime = verificationTime;
    }

    public Date getVerificationTime() 
    {
        return verificationTime;
    }

    public void setVerifierId(Long verifierId) 
    {
        this.verifierId = verifierId;
    }

    public Long getVerifierId() 
    {
        return verifierId;
    }

    public void setPointsReturned(Integer pointsReturned) 
    {
        this.pointsReturned = pointsReturned;
    }

    public Integer getPointsReturned() 
    {
        return pointsReturned;
    }

    public void setAvatar(String avatar) 
    {
        this.avatar = avatar;
    }

    public String getAvatar() 
    {
        return avatar;
    }

    public void setNickname(String nickname) 
    {
        this.nickname = nickname;
    }

    public String getNickname() 
    {
        return nickname;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("registrationNo", getRegistrationNo())
            .append("ownerType", getOwnerType())
            .append("ownerId", getOwnerId())
            .append("activityTitle", getActivityTitle())
            .append("ticketId", getTicketId())
            .append("ticketName", getTicketName())
            .append("ticketPrice", getTicketPrice())
            .append("memberId", getMemberId())
            .append("realName", getRealName())
            .append("phone", getPhone())
            .append("email", getEmail())
            .append("address", getAddress())
            .append("addressId", getAddressId())
            .append("orderNum", getOrderNum())
            .append("originalAmount", getOriginalAmount())
            .append("discountAmount", getDiscountAmount())
            .append("totalAmount", getTotalAmount())
            .append("pointUsed", getPointUsed())
            .append("pointDeductAmount", getPointDeductAmount())
            .append("pointDeductRate", getPointDeductRate())
            .append("maxPointDeduct", getMaxPointDeduct())
            .append("payableAmount", getPayableAmount())
            .append("paymentStatus", getPaymentStatus())
            .append("paymentType", getPaymentType())
            .append("paymentTime", getPaymentTime())
            .append("transactionId", getTransactionId())
            .append("pointPaymentAmount", getPointPaymentAmount())
            .append("wechatPaymentAmount", getWechatPaymentAmount())
            .append("registrationStatus", getRegistrationStatus())
            .append("payExpireTime", getPayExpireTime())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("verificationCode", getVerificationCode())
            .append("qrcodeUrl", getQrcodeUrl())
            .append("verificationStatus", getVerificationStatus())
            .append("verificationTime", getVerificationTime())
            .append("verifierId", getVerifierId())
            .append("pointsReturned", getPointsReturned())
            .append("avatar", getAvatar())
            .append("nickname", getNickname())
            .toString();
    }
}
