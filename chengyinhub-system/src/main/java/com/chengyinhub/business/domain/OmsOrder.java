package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 订单对象 oms_order
 * 
 * @author ruoyi
 * @date 2025-08-25
 */
public class OmsOrder extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 订单id */
    private Long id;
    private Long verifiedPaymentFactId;
    private String verifiedPaymentFactHash;

    /** 订单类型：1积分商城 */
    @Excel(name = "订单类型：1积分商城")
    private Long orderType;

    /** 订单编号 */
    @Excel(name = "订单编号")
    private String orderSn;

    /** 支付id */
    @Excel(name = "支付id")
    private Long payId;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long memberId;

    /** 商家ID */
    @Excel(name = "商家ID")
    private Long mmsMerchantId;

    /** 订单商品数量 */
    @Excel(name = "订单商品数量")
    private Long productQuantity;

    /** 订单商品总金额 */
    @Excel(name = "订单商品总金额")
    private BigDecimal productTotalAmount;

    /** 配送费 */
    @Excel(name = "配送费")
    private BigDecimal deliveryFee;

    /** 第三方支付金额 */
    @Excel(name = "第三方支付金额")
    private BigDecimal payAmount;

    /** 税费 */
    @Excel(name = "税费")
    private BigDecimal taxFee;

    /** 订单总金额 */
    @Excel(name = "订单总金额")
    private BigDecimal orderTotalAmount;

    /** 积分抵扣金额 */
    @Excel(name = "积分抵扣金额")
    private BigDecimal pointsAmount;

    /** 支付方式：0未支付  1积分支付 */
    @Excel(name = "支付方式：0未支付  1积分支付")
    private Long payType;

    /** 订单状态：0->待付款；1->待发货；2->已发货；3->待评价；4->已完成；5->已关闭；6->无效订单 */
    @Excel(name = "订单状态：0->待付款；1->待发货；2->已发货；3->待评价；4->已完成；5->已关闭；6->无效订单")
    private Long status;

    /** 退款状态，枚举值：1：无售后或售后关闭，2：售后处理中，3：退款中，4： 退款成功 */
    @Excel(name = "退款状态，枚举值：1：无售后或售后关闭，2：售后处理中，3：退款中，4： 退款成功")
    private Long aftersaleStatus;

    /** 物流公司(配送方式) */
    @Excel(name = "物流公司(配送方式)")
    private String deliveryCompany;

    /** 物流单号 */
    @Excel(name = "物流单号")
    private String deliverySn;

    /** 自动确认时间（天） */
    @Excel(name = "自动确认时间", readConverterExp = "天=")
    private Long autoConfirmDay;

    /** 收货人姓名 */
    @Excel(name = "收货人姓名")
    private String receiverName;

    /** 收货人电话 */
    @Excel(name = "收货人电话")
    private String receiverPhone;

    /** 收货人电话区号 */
    @Excel(name = "收货人电话区号")
    private String receiverPhoneCode;

    /** 收货人省市区 */
    @Excel(name = "收货人省市区")
    private String receiverProvince;

    /** 详细地址 */
    @Excel(name = "详细地址")
    private String receiverDetailAddress;

    /** 订单备注 */
    @Excel(name = "订单备注")
    private String orderNote;

    /** 商家备注 */
    @Excel(name = "商家备注")
    private String merchantNote;

    /** 确认收货状态：0->未确认；1->已确认 */
    @Excel(name = "确认收货状态：0->未确认；1->已确认")
    private Long confirmStatus;

    /** 支付时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "支付时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date paymentTime;

    /** 发货时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "发货时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date deliveryTime;

    /** 确认收货时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "确认收货时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date receiveTime;

    /** 使用的优惠券id */
    @Excel(name = "使用的优惠券id")
    private Long memberCouponId;

    /** 优惠券金额 */
    @Excel(name = "优惠券金额")
    private BigDecimal couponAmount;

    /** 删除标记 0 代表存在  2删除 */
    private Integer delFlag;

    /** 商业化订单类型及权益快照；普通商城订单均为空。 */
    private String bizType;
    private Integer entitlementMaxUsage;
    private Integer entitlementDays;
    private String paymentTransactionId;
    private String commerceRequestId;

    /** Internal immutable successful consumption proof, never a client-writable API field. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public Long getVerifiedPaymentFactId() { return verifiedPaymentFactId; }
    public void setVerifiedPaymentFactId(Long value) { verifiedPaymentFactId = value; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getVerifiedPaymentFactHash() { return verifiedPaymentFactHash; }
    public void setVerifiedPaymentFactHash(String value) { verifiedPaymentFactHash = value; }

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setOrderType(Long orderType) 
    {
        this.orderType = orderType;
    }

    public Long getOrderType() 
    {
        return orderType;
    }

    public void setOrderSn(String orderSn) 
    {
        this.orderSn = orderSn;
    }

    public String getOrderSn() 
    {
        return orderSn;
    }

    public void setPayId(Long payId) 
    {
        this.payId = payId;
    }

    public Long getPayId() 
    {
        return payId;
    }

    public void setMemberId(Long memberId) 
    {
        this.memberId = memberId;
    }

    public Long getMemberId() 
    {
        return memberId;
    }

    public void setMmsMerchantId(Long mmsMerchantId) 
    {
        this.mmsMerchantId = mmsMerchantId;
    }

    public Long getMmsMerchantId() 
    {
        return mmsMerchantId;
    }

    public void setProductQuantity(Long productQuantity) 
    {
        this.productQuantity = productQuantity;
    }

    public Long getProductQuantity() 
    {
        return productQuantity;
    }

    public void setProductTotalAmount(BigDecimal productTotalAmount) 
    {
        this.productTotalAmount = productTotalAmount;
    }

    public BigDecimal getProductTotalAmount() 
    {
        return productTotalAmount;
    }

    public void setDeliveryFee(BigDecimal deliveryFee) 
    {
        this.deliveryFee = deliveryFee;
    }

    public BigDecimal getDeliveryFee() 
    {
        return deliveryFee;
    }

    public void setPayAmount(BigDecimal payAmount) 
    {
        this.payAmount = payAmount;
    }

    public BigDecimal getPayAmount() 
    {
        return payAmount;
    }

    public void setTaxFee(BigDecimal taxFee) 
    {
        this.taxFee = taxFee;
    }

    public BigDecimal getTaxFee() 
    {
        return taxFee;
    }

    public void setOrderTotalAmount(BigDecimal orderTotalAmount) 
    {
        this.orderTotalAmount = orderTotalAmount;
    }

    public BigDecimal getOrderTotalAmount() 
    {
        return orderTotalAmount;
    }

    public void setPointsAmount(BigDecimal pointsAmount) 
    {
        this.pointsAmount = pointsAmount;
    }

    public BigDecimal getPointsAmount() 
    {
        return pointsAmount;
    }

    public void setPayType(Long payType) 
    {
        this.payType = payType;
    }

    public Long getPayType() 
    {
        return payType;
    }

    public void setStatus(Long status) 
    {
        this.status = status;
    }

    public Long getStatus() 
    {
        return status;
    }

    public void setAftersaleStatus(Long aftersaleStatus) 
    {
        this.aftersaleStatus = aftersaleStatus;
    }

    public Long getAftersaleStatus() 
    {
        return aftersaleStatus;
    }

    public void setDeliveryCompany(String deliveryCompany) 
    {
        this.deliveryCompany = deliveryCompany;
    }

    public String getDeliveryCompany() 
    {
        return deliveryCompany;
    }

    public void setDeliverySn(String deliverySn) 
    {
        this.deliverySn = deliverySn;
    }

    public String getDeliverySn() 
    {
        return deliverySn;
    }

    public void setAutoConfirmDay(Long autoConfirmDay) 
    {
        this.autoConfirmDay = autoConfirmDay;
    }

    public Long getAutoConfirmDay() 
    {
        return autoConfirmDay;
    }

    public void setReceiverName(String receiverName) 
    {
        this.receiverName = receiverName;
    }

    public String getReceiverName() 
    {
        return receiverName;
    }

    public void setReceiverPhone(String receiverPhone) 
    {
        this.receiverPhone = receiverPhone;
    }

    public String getReceiverPhone() 
    {
        return receiverPhone;
    }

    public void setReceiverPhoneCode(String receiverPhoneCode) 
    {
        this.receiverPhoneCode = receiverPhoneCode;
    }

    public String getReceiverPhoneCode() 
    {
        return receiverPhoneCode;
    }

    public void setReceiverProvince(String receiverProvince) 
    {
        this.receiverProvince = receiverProvince;
    }

    public String getReceiverProvince() 
    {
        return receiverProvince;
    }

    public void setReceiverDetailAddress(String receiverDetailAddress) 
    {
        this.receiverDetailAddress = receiverDetailAddress;
    }

    public String getReceiverDetailAddress() 
    {
        return receiverDetailAddress;
    }

    public void setOrderNote(String orderNote) 
    {
        this.orderNote = orderNote;
    }

    public String getOrderNote() 
    {
        return orderNote;
    }

    public void setMerchantNote(String merchantNote) 
    {
        this.merchantNote = merchantNote;
    }

    public String getMerchantNote() 
    {
        return merchantNote;
    }

    public void setConfirmStatus(Long confirmStatus) 
    {
        this.confirmStatus = confirmStatus;
    }

    public Long getConfirmStatus() 
    {
        return confirmStatus;
    }

    public void setPaymentTime(Date paymentTime) 
    {
        this.paymentTime = paymentTime;
    }

    public Date getPaymentTime() 
    {
        return paymentTime;
    }

    public void setDeliveryTime(Date deliveryTime) 
    {
        this.deliveryTime = deliveryTime;
    }

    public Date getDeliveryTime() 
    {
        return deliveryTime;
    }

    public void setReceiveTime(Date receiveTime) 
    {
        this.receiveTime = receiveTime;
    }

    public Date getReceiveTime() 
    {
        return receiveTime;
    }

    public void setMemberCouponId(Long memberCouponId) 
    {
        this.memberCouponId = memberCouponId;
    }

    public Long getMemberCouponId() 
    {
        return memberCouponId;
    }

    public void setCouponAmount(BigDecimal couponAmount) 
    {
        this.couponAmount = couponAmount;
    }

    public BigDecimal getCouponAmount() 
    {
        return couponAmount;
    }

    public void setDelFlag(Integer delFlag) 
    {
        this.delFlag = delFlag;
    }

    public Integer getDelFlag() 
    {
        return delFlag;
    }

    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Integer getEntitlementMaxUsage() { return entitlementMaxUsage; }
    public void setEntitlementMaxUsage(Integer entitlementMaxUsage) { this.entitlementMaxUsage = entitlementMaxUsage; }
    public Integer getEntitlementDays() { return entitlementDays; }
    public void setEntitlementDays(Integer entitlementDays) { this.entitlementDays = entitlementDays; }
    public String getPaymentTransactionId() { return paymentTransactionId; }
    public void setPaymentTransactionId(String paymentTransactionId) { this.paymentTransactionId = paymentTransactionId; }
    public String getCommerceRequestId() { return commerceRequestId; }
    public void setCommerceRequestId(String commerceRequestId) { this.commerceRequestId = commerceRequestId; }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("orderType", getOrderType())
            .append("orderSn", getOrderSn())
            .append("payId", getPayId())
            .append("memberId", getMemberId())
            .append("mmsMerchantId", getMmsMerchantId())
            .append("productQuantity", getProductQuantity())
            .append("productTotalAmount", getProductTotalAmount())
            .append("deliveryFee", getDeliveryFee())
            .append("payAmount", getPayAmount())
            .append("taxFee", getTaxFee())
            .append("orderTotalAmount", getOrderTotalAmount())
            .append("pointsAmount", getPointsAmount())
            .append("payType", getPayType())
            .append("status", getStatus())
            .append("aftersaleStatus", getAftersaleStatus())
            .append("deliveryCompany", getDeliveryCompany())
            .append("deliverySn", getDeliverySn())
            .append("autoConfirmDay", getAutoConfirmDay())
            .append("receiverName", getReceiverName())
            .append("receiverPhone", getReceiverPhone())
            .append("receiverPhoneCode", getReceiverPhoneCode())
            .append("receiverProvince", getReceiverProvince())
            .append("receiverDetailAddress", getReceiverDetailAddress())
            .append("orderNote", getOrderNote())
            .append("merchantNote", getMerchantNote())
            .append("confirmStatus", getConfirmStatus())
            .append("paymentTime", getPaymentTime())
            .append("deliveryTime", getDeliveryTime())
            .append("receiveTime", getReceiveTime())
            .append("memberCouponId", getMemberCouponId())
            .append("couponAmount", getCouponAmount())
            .append("delFlag", getDelFlag())
            .append("bizType", getBizType())
            .append("entitlementMaxUsage", getEntitlementMaxUsage())
            .append("entitlementDays", getEntitlementDays())
            .append("paymentTransactionId", getPaymentTransactionId())
            .append("commerceRequestId", getCommerceRequestId())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}
