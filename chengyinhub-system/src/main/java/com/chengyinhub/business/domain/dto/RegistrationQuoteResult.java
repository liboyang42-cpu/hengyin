package com.chengyinhub.business.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

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

    /**
     * C06 商品交付摘要（报价只读投影）。只从服务端既有票种/活动/主题事实派生，
     * 不参与金额计算，也不改变 quoteSign；客户端不得凭这些字段自行判定可售或权益。
     */
    @Schema(description = "商品交付摘要(只读服务端事实)")
    private Delivery delivery;

    /**
     * 此刻服务端实际可用的支付通道。仅代表通道就绪，不代表商品在该市场已获准销售；
     * 数字商品 IAP/商店地区分类由 App 侧商品策略单独确认。
     */
    @Schema(description = "当前可用支付通道(JSAPI/APP);空表示通道未就绪")
    private List<String> paymentChannels;

    /** 报价交付摘要：买的是什么、给谁、多少人、何时可用、退款规则。 */
    public static class Delivery {
        /** {@link com.chengyinhub.common.constant.PurchaseKind}:1 有人带票/2 自玩通行证/3 自由探索票 */
        private Integer purchaseKind;
        private String ticketName;
        /** 该票一张覆盖的人数(多人票=同一报名单内席位);1 或 null 表示单人。 */
        private Integer teamSize;
        private Integer minPeople;
        private Integer maxPeople;
        /** 1 = 报名需审核；0/null = 不需要。 */
        private Integer needAudit;
        /** 服务可用期(活动/主题既有起止)。null = 服务端未给出，不得由客户端补默认值。 */
        private Date serviceStartAt;
        private Date serviceEndAt;
        /** 票种既有退款规则原文；null = 未配置，不得展示为"可退"。 */
        private String refundRule;
        /** 权益归属种类：报名场景恒为付款人本人，与主办/商家主体分开表达。 */
        private String beneficiaryKind;
        /** 内容/场次归属主体(主题活动主办者或活动所属商家会员)。 */
        private Long sellerMemberId;
        /** 报价金额由服务端计算并签名，客户端仅展示。 */
        private Boolean amountFromServer;

        public Integer getPurchaseKind() { return purchaseKind; }
        public void setPurchaseKind(Integer purchaseKind) { this.purchaseKind = purchaseKind; }
        public String getTicketName() { return ticketName; }
        public void setTicketName(String ticketName) { this.ticketName = ticketName; }
        public Integer getTeamSize() { return teamSize; }
        public void setTeamSize(Integer teamSize) { this.teamSize = teamSize; }
        public Integer getMinPeople() { return minPeople; }
        public void setMinPeople(Integer minPeople) { this.minPeople = minPeople; }
        public Integer getMaxPeople() { return maxPeople; }
        public void setMaxPeople(Integer maxPeople) { this.maxPeople = maxPeople; }
        public Integer getNeedAudit() { return needAudit; }
        public void setNeedAudit(Integer needAudit) { this.needAudit = needAudit; }
        public Date getServiceStartAt() { return serviceStartAt; }
        public void setServiceStartAt(Date serviceStartAt) { this.serviceStartAt = serviceStartAt; }
        public Date getServiceEndAt() { return serviceEndAt; }
        public void setServiceEndAt(Date serviceEndAt) { this.serviceEndAt = serviceEndAt; }
        public String getRefundRule() { return refundRule; }
        public void setRefundRule(String refundRule) { this.refundRule = refundRule; }
        public String getBeneficiaryKind() { return beneficiaryKind; }
        public void setBeneficiaryKind(String beneficiaryKind) { this.beneficiaryKind = beneficiaryKind; }
        public Long getSellerMemberId() { return sellerMemberId; }
        public void setSellerMemberId(Long sellerMemberId) { this.sellerMemberId = sellerMemberId; }
        public Boolean getAmountFromServer() { return amountFromServer; }
        public void setAmountFromServer(Boolean amountFromServer) { this.amountFromServer = amountFromServer; }
    }

    /** 此刻下单后取消可退的百分比(2026-11-02 退款档位):100 / 50 / 0;null = 非场次单或算不出来。 */
    @Schema(description = "此刻下单后取消可退的百分比(100/50/0;null=未知)")
    private Integer refundPercentNow;
    /** 第十二轮 k6(p1 N-1):这单能否用积分抵扣(自由探索、没积分、可抵 0 ⇒ false);null = 未判定(老路径)。 */
    private Boolean pointsUsable;

    public Boolean getPointsUsable() { return pointsUsable; }
    public void setPointsUsable(Boolean pointsUsable) { this.pointsUsable = pointsUsable; }

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

    public Integer getRefundPercentNow() {
        return refundPercentNow;
    }

    public void setRefundPercentNow(Integer refundPercentNow) {
        this.refundPercentNow = refundPercentNow;
    }

    public void setRefundableNow(Boolean refundableNow) {
        this.refundableNow = refundableNow;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public void setDelivery(Delivery delivery) {
        this.delivery = delivery;
    }

    public List<String> getPaymentChannels() {
        return paymentChannels;
    }

    public void setPaymentChannels(List<String> paymentChannels) {
        this.paymentChannels = paymentChannels;
    }


    // ---- M2/M4 营销:促销报价与完局奖励券只读投影(三方保留:B7 的 delivery/paymentChannels 保留在上面) ----
    /**
     * 完局奖励券的公开投影:本单对应主题已配置的「通关后发放」券,只用于下单前的期望展示。
     *
     * <p>⚠️ 它不是折扣,也不参与计价与 quoteSign:券按商家核销,不能抵本次支付
     * ({@code couponDeductYuan} 恒为 0)。发放仍由服务端在真实完局事实发生时执行
     * ({@code TopicCompletionAwardServiceImpl.grantOnce} → 通关券日志判重),客户端不得据此发券。
     * 券已结束/失效/停发/删除时投影为 null({@link com.chengyinhub.business.domain.vo.PublicCouponSummaryVO#from})。
     */
    @Schema(description = "完局奖励券(仅在主题已配置且券可展示时返回;不是折扣)")
    private com.chengyinhub.business.domain.vo.PublicCouponSummaryVO completeRewardCoupon;

    /**
     * 该会员是否已持有覆盖本主题的有效营销通行证(只读投影,来自 M3 授权事实)。
     *
     * <p>⚠️ 它<b>不</b>改变本单价格,也<b>不</b>阻断购买:通行证与"有人带票"可能是不同 SKU。
     * 是否禁止重复购买属产品决策,本字段只供界面提示"你已可直接游玩"。
     */
    @Schema(description = "是否已持有覆盖本主题的营销通行证(只读,不改价)")
    private Boolean coveredByPass;

    /** coveredByPass 判定所依据的主题 id(ownerType=2 时为活动所属主题;无主题为 null)。 */
    @Schema(description = "通行证覆盖判定的主题 id")
    private Long coveredTopicId;

    /** 通行证（平台会员）覆盖本主题：true 时本单金额已按 ¥0 报价，前端按钮显示「免费领取」。未覆盖为 null。 */
    private Boolean platformPassCovered;

    /** 覆盖本单的通行证有效段到期时刻（领取后的自玩票到期不晚于它）。 */
    private java.util.Date platformPassValidTo;

    public Boolean getPlatformPassCovered() { return platformPassCovered; }
    public void setPlatformPassCovered(Boolean v) { this.platformPassCovered = v; }
    public java.util.Date getPlatformPassValidTo() { return platformPassValidTo; }
    public void setPlatformPassValidTo(java.util.Date v) { this.platformPassValidTo = v; }

    /**
     * 票面合计(促销前,元)。= Σ 票种现价 × 张数,由服务端读 {@code oms_ticket.price} 算出。
     * 它与 {@link #payAmount} 的差额里同时含会员价与促销减免;分项见下面三个字段。
     */
    @Schema(description = "票面合计(促销前,元)")
    private BigDecimal listAmount;

    /** 已生效的每一项减免(阶梯/组合包/触发式),含规则版本与服务端事实 id。 */
    @Schema(description = "促销减免分项")
    private java.util.List<com.chengyinhub.business.domain.dto.MarketingAdjustment> marketingAdjustments;

    /**
     * 促销减免合计(元,非负):人数阶梯 + 组合包/礼品价 + 触发式优惠。
     *
     * <p>⚠️ 与 {@link #pointsDeductYuan} 不同:积分抵扣不在这里,它仍由既有
     * {@code computePrice} 计算。两项都是"减免",所以
     * {@code listAmount - marketingDiscountAmount - memberDiscountYuan - pointsDeductYuan == payAmount}
     * 只在不含会员价时才严格成立;下单金额一律以 {@link #payAmount} 为准。
     */
    @Schema(description = "促销减免合计(元)")
    private BigDecimal marketingDiscountAmount;

    /**
     * 未生效的候选及其原因码(如 OFFER_PRICE_ABOVE_LIST / STACK_GROUP_TAKEN / BELOW_MIN_AMOUNT)。
     * 客户端据此解释,不靠文案猜测。
     */
    @Schema(description = "未生效优惠及原因")
    private java.util.List<com.chengyinhub.business.domain.dto.MarketingExclusion> marketingExclusions;

    /** 参与本次报价的规则版本串(offer/tier/promo 各自 ruleVersion),供快照与对账。 */
    @Schema(description = "促销规则版本串")
    private String marketingRuleVersion;

    /** 促销报价指纹:下单必须原样回传,服务端重算比对;不一致 409。 */
    @Schema(description = "促销报价指纹(下单回传)")
    private String marketingSign;

    public com.chengyinhub.business.domain.vo.PublicCouponSummaryVO getCompleteRewardCoupon() {
        return completeRewardCoupon;
    }

    public void setCompleteRewardCoupon(
            com.chengyinhub.business.domain.vo.PublicCouponSummaryVO completeRewardCoupon) {
        this.completeRewardCoupon = completeRewardCoupon;
    }

    public Boolean getCoveredByPass() {
        return coveredByPass;
    }

    public void setCoveredByPass(Boolean coveredByPass) {
        this.coveredByPass = coveredByPass;
    }

    public Long getCoveredTopicId() {
        return coveredTopicId;
    }

    public void setCoveredTopicId(Long coveredTopicId) {
        this.coveredTopicId = coveredTopicId;
    }

    public BigDecimal getListAmount() {
        return listAmount;
    }

    public void setListAmount(BigDecimal listAmount) {
        this.listAmount = listAmount;
    }

    public java.util.List<com.chengyinhub.business.domain.dto.MarketingAdjustment> getMarketingAdjustments() {
        return marketingAdjustments;
    }

    public void setMarketingAdjustments(
            java.util.List<com.chengyinhub.business.domain.dto.MarketingAdjustment> marketingAdjustments) {
        this.marketingAdjustments = marketingAdjustments;
    }

    public BigDecimal getMarketingDiscountAmount() {
        return marketingDiscountAmount;
    }

    public void setMarketingDiscountAmount(BigDecimal marketingDiscountAmount) {
        this.marketingDiscountAmount = marketingDiscountAmount;
    }

    public java.util.List<com.chengyinhub.business.domain.dto.MarketingExclusion> getMarketingExclusions() {
        return marketingExclusions;
    }

    public void setMarketingExclusions(
            java.util.List<com.chengyinhub.business.domain.dto.MarketingExclusion> marketingExclusions) {
        this.marketingExclusions = marketingExclusions;
    }

    public String getMarketingRuleVersion() {
        return marketingRuleVersion;
    }

    public void setMarketingRuleVersion(String marketingRuleVersion) {
        this.marketingRuleVersion = marketingRuleVersion;
    }

    public String getMarketingSign() {
        return marketingSign;
    }

    public void setMarketingSign(String marketingSign) {
        this.marketingSign = marketingSign;
    }
}
