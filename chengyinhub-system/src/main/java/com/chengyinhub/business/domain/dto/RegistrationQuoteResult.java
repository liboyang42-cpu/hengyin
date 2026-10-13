package com.chengyinhub.business.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 报名报价结果（M0-1 实扣报价一致）
 *
 * <p>与 {@code createRegistration} 共用同一套金额计算（{@code computePrice}），
 * 保证「前端展示 = 微信实付」分毫不差。quoteSign 为无状态签名：
 * MD5(memberId|ownerType|ownerId|票组合|isUsePoint|payAmount|pointsUsed|couponDeductYuan|SALT)，
 * 下单时回传，后端重算比对，不一致（如期间积分余额/票价变动）返回 409。
 */
public class RegistrationQuoteResult {

    /** 实际应付金额（元，微信实付） */
    @Schema(description = "实际应付金额(元)")
    private BigDecimal payAmount;

    /** 使用的积分数 */
    @Schema(description = "使用积分数")
    private Integer pointsUsed;

    /** 积分抵扣金额（元） */
    @Schema(description = "积分抵扣金额(元)")
    private BigDecimal pointsDeductYuan;

    /** 优惠券抵扣金额（元）。当前报名支付路径不接受券抵扣（券为商家核销型），恒为 0。 */
    @Schema(description = "优惠券抵扣金额(元)")
    private BigDecimal couponDeductYuan;

    /** 报价签名 */
    @Schema(description = "报价签名")
    private String quoteSign;

    /** 俱乐部会员优惠金额（元，不含积分抵扣） */
    @Schema(description = "俱乐部会员优惠金额(元)")
    private BigDecimal memberDiscountYuan;

    /** 当前报名人是否为本活动归属俱乐部的有效成员 */
    @Schema(description = "是否为俱乐部成员")
    private Boolean clubMember;

    /** 本活动是否已为成员开启优先报名 */
    @Schema(description = "是否开启俱乐部成员优先报名")
    private Boolean prioritySignupEnabled;

    /**
     * 下单前退款截止点(1-22 附注口径)。场次票 = 集合时间前 24 小时,由
     * {@code RefundPolicy.sessionRefundDeadline} 这唯一一口钟算出;非场次/缺锚点为 null。
     */
    @Schema(description = "退款截止时间(场次票=集合前24小时;null=无法确定)")
    private Date refundDeadline;

    /** 此刻下单是否还可退:null = 系统算不出来(前端不弹、保持默认路径),false = 本单不可退。 */
    @Schema(description = "此刻下单后是否可退(null=未知,不可当可退宣传)")
    private Boolean refundableNow;

    public RegistrationQuoteResult() {
    }

    public RegistrationQuoteResult(BigDecimal payAmount, Integer pointsUsed, BigDecimal pointsDeductYuan,
                                   BigDecimal couponDeductYuan, String quoteSign) {
        this(payAmount, pointsUsed, pointsDeductYuan, couponDeductYuan, quoteSign,
                BigDecimal.ZERO, false, false);
    }

    public RegistrationQuoteResult(BigDecimal payAmount, Integer pointsUsed, BigDecimal pointsDeductYuan,
                                   BigDecimal couponDeductYuan, String quoteSign,
                                   BigDecimal memberDiscountYuan, Boolean clubMember,
                                   Boolean prioritySignupEnabled) {
        this.payAmount = payAmount;
        this.pointsUsed = pointsUsed;
        this.pointsDeductYuan = pointsDeductYuan;
        this.couponDeductYuan = couponDeductYuan;
        this.quoteSign = quoteSign;
        this.memberDiscountYuan = memberDiscountYuan;
        this.clubMember = clubMember;
        this.prioritySignupEnabled = prioritySignupEnabled;
    }

    public BigDecimal getPayAmount() {
        return payAmount;
    }

    public void setPayAmount(BigDecimal payAmount) {
        this.payAmount = payAmount;
    }

    public Integer getPointsUsed() {
        return pointsUsed;
    }

    public void setPointsUsed(Integer pointsUsed) {
        this.pointsUsed = pointsUsed;
    }

    public BigDecimal getPointsDeductYuan() {
        return pointsDeductYuan;
    }

    public void setPointsDeductYuan(BigDecimal pointsDeductYuan) {
        this.pointsDeductYuan = pointsDeductYuan;
    }

    public BigDecimal getCouponDeductYuan() {
        return couponDeductYuan;
    }

    public void setCouponDeductYuan(BigDecimal couponDeductYuan) {
        this.couponDeductYuan = couponDeductYuan;
    }

    public String getQuoteSign() {
        return quoteSign;
    }

    public void setQuoteSign(String quoteSign) {
        this.quoteSign = quoteSign;
    }

    public BigDecimal getMemberDiscountYuan() {
        return memberDiscountYuan;
    }

    public void setMemberDiscountYuan(BigDecimal memberDiscountYuan) {
        this.memberDiscountYuan = memberDiscountYuan;
    }

    public Boolean getClubMember() {
        return clubMember;
    }

    public void setClubMember(Boolean clubMember) {
        this.clubMember = clubMember;
    }

    public Boolean getPrioritySignupEnabled() {
        return prioritySignupEnabled;
    }

    public void setPrioritySignupEnabled(Boolean prioritySignupEnabled) {
        this.prioritySignupEnabled = prioritySignupEnabled;
    }

    public Date getRefundDeadline() {
        return refundDeadline;
    }

    public void setRefundDeadline(Date refundDeadline) {
        this.refundDeadline = refundDeadline;
    }

    public Boolean getRefundableNow() {
        return refundableNow;
    }

    public void setRefundableNow(Boolean refundableNow) {
        this.refundableNow = refundableNow;
    }
}
