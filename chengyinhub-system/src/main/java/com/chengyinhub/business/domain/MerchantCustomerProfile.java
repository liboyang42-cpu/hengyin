package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.math.BigDecimal;
import java.util.Date;

/**
 * P3 商家客户画像 merchant_customer_profile。
 */
public class MerchantCustomerProfile extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long merchantId;
    private Long memberId;
    private String memberNickname;
    private String memberPhone;
    private Long registrationCount;
    private Long orderCount;
    private BigDecimal orderAmount;
    private Long couponReceiveCount;
    private Long couponVerifyCount;
    private Date lastInteractionTime;
    private String customerStage;
    private String cityCode;
    private String tagsJson;
    private Integer delFlag;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getMemberNickname() { return memberNickname; }
    public void setMemberNickname(String memberNickname) { this.memberNickname = memberNickname; }
    public String getMemberPhone() { return memberPhone; }
    public void setMemberPhone(String memberPhone) { this.memberPhone = memberPhone; }
    public Long getRegistrationCount() { return registrationCount; }
    public void setRegistrationCount(Long registrationCount) { this.registrationCount = registrationCount; }
    public Long getOrderCount() { return orderCount; }
    public void setOrderCount(Long orderCount) { this.orderCount = orderCount; }
    public BigDecimal getOrderAmount() { return orderAmount; }
    public void setOrderAmount(BigDecimal orderAmount) { this.orderAmount = orderAmount; }
    public Long getCouponReceiveCount() { return couponReceiveCount; }
    public void setCouponReceiveCount(Long couponReceiveCount) { this.couponReceiveCount = couponReceiveCount; }
    public Long getCouponVerifyCount() { return couponVerifyCount; }
    public void setCouponVerifyCount(Long couponVerifyCount) { this.couponVerifyCount = couponVerifyCount; }
    public Date getLastInteractionTime() { return lastInteractionTime; }
    public void setLastInteractionTime(Date lastInteractionTime) { this.lastInteractionTime = lastInteractionTime; }
    public String getCustomerStage() { return customerStage; }
    public void setCustomerStage(String customerStage) { this.customerStage = customerStage; }
    public String getCityCode() { return cityCode; }
    public void setCityCode(String cityCode) { this.cityCode = cityCode; }
    public String getTagsJson() { return tagsJson; }
    public void setTagsJson(String tagsJson) { this.tagsJson = tagsJson; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
}
