package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 活动/主题报名对象 cms_registration
 * 
 * @author webxh
 * @date 2025-09-04
 */
public class CmsRegistration extends BaseEntity
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

    /** 参与日期 yyyy-MM-dd（自由定向） */
    @Excel(name = "参与日期")
    private String participateDate;

    /** 父订单号 同一支付的多条记录共享 */
    @Excel(name = "父订单号")
    private String parentOrderNo;

    /** BE-03 幂等键:前端每次结账生成的 UUID。同一 requestId 重复提交返回已建订单,防网络重试/双击建重单 */
    private String requestId;

    /** requestId 对应的规范化请求指纹；相同键但资金语义不同必须 fail-closed。 */
    private String requestFingerprint;

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
    @Excel(name = "支付状态", readConverterExp = "0=待支付,1=支付中,2=已支付,3=支付失败,4=已退款")
    private Integer paymentStatus;

    /** 支付方式：0纯积分 1纯微信 2积分+微信 */
    @Excel(name = "支付方式", readConverterExp = "0=纯积分,1=纯微信,2=积分+微信")
    private Integer paymentType;


    /** 支付时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "支付时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
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

    /** 下单时冻结的平台费率；支付与结算不得回读 live 费率改写历史订单。 */
    private BigDecimal feePlatformRate;

    /** 报名状态：1待支付 2报名成功 3已取消 4已过期 */
    @Excel(name = "报名状态", readConverterExp = "1=待支付,2=报名成功,3=已取消,4=已过期")
    private Integer registrationStatus;

    /** 支付过期时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "支付过期时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date payExpireTime;

    /** M2 自玩:主题通行证的主题ID(配合 owner_type=1 + activity_id=0) */
    private Long topicId;

    /**
     * P0-02 产品真源:1有人带票 2自玩通行证 3自由探索票。取值见
     * {@link com.chengyinhub.common.constant.PurchaseKind}。下单时由(ticketId 有无 + 主题 product_type)
     * 推导并**固化** —— 主题的 product_type 事后可能被改,已售订单必须按买的时候算。
     * NULL = 存量未回填,读侧回落既有的隐式推断。
     */
    private Integer purchaseKind;

    /** 下单时携带的本期票源俱乐部标记；支付成功后以 sourceFrozenAt 表示归因已冻结。 */
    private Long sourceClubId;

    /** 票源归因冻结时刻；非空后业务写入口不得再改 sourceClubId。 */
    private Date sourceFrozenAt;

    /** 本期售票来源原文（专属链接或邀请码），仅用于审计，不参与跨期覆盖。 */
    private String sourceChannelCode;

    /**
     * [P0-06] 退款政策标识(下单时快照)。NULL = 存量单 ⇒ 读侧回落现取。
     * 见 {@link com.chengyinhub.common.constant.RefundPolicyCode}。
     */
    private String refundPolicyCode;

    /** [P0-06] 退款政策版本(下单时快照)。同一 code 的参数调整靠它区分。 */
    private Integer refundPolicyVersion;

    /**
     * [P0-06] 可退截止点快照(③ = 票的 end_time)。
     * <p><b>这是「别再算了」的那一列</b>:锚点源字段卖票之后还能改(oms_ticket 有后台 CRUD),
     * 现取 = 追溯改写所有已售单的退款窗口,且零告警。
     */
    private Date refundDeadline;

    /** M2 自玩:通行证有效期(购后90天) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date expiresAt;

    /** 本订单首次合法扫码成功时间；订单展示完成事实，与权益核销独立。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date firstSuccessfulScanAt;

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
    @Excel(name = "积分是否返还 0未返  1已返", readConverterExp = "0=未返 ,1=已返")
    private Integer pointsReturned;



    private Integer isOnline;



    /** 报名对应的活动信息 */
    private CmsActivity cmsActivity;

    /** 报名对应主题信息 */
    private CmsTopic cmsTopic;

    private List<CmsRegistrationScan> registrationScanList;

    private  MmsMerchant mmsMerchant;

    /** 退款可退判定(瞬态,订单详情展示用;仅主题计算,口径见 RefundPolicy,与 cancel 路径一致) */
    private com.chengyinhub.business.util.RefundPolicy.Verdict refundInfo;

    /** 最新退款申请(瞬态,玩家订单展示用；payoutStatus 是退款是否到账的唯一事实)。 */
    private RefundApplication refundApplication;

    /** 玩家本人冻结订单的人工售后状态；仅展示，不暴露案件内部材料。 */
    private String manualRefundCaseStatus;

    /** 服务端读时刻(瞬态,epoch ms):玩家订单「未开始/进行中/已完成」标签按它判,不读手机时钟(U-033)。 */
    private Long serverNow;

    public Long getServerNow() { return serverNow; }

    public void setServerNow(Long serverNow) { this.serverNow = serverNow; }

    public String getManualRefundCaseStatus() { return manualRefundCaseStatus; }

    public void setManualRefundCaseStatus(String status) { this.manualRefundCaseStatus = status; }

    /** 订单来源的主办方/主理人昵称(瞬态,仅玩家订单详情展示)。 */
    private String organizerName;

    /** 支付方式中文(瞬态,订单详情展示用):积分 / 微信 / 微信+积分。 */
    private String paymentTypeLabel;

    /**
     * 退款截止展示文案(瞬态)。可退时「可免费取消至 M月D日 HH:mm」;
     * 不可退时回落 refundInfo.reason。
     */
    private String refundDeadlineDisplay;

    /**
     * ③ 探索票的权益清单(瞬态,订单详情展示用)。status:0=待核销 1=已核销 2=已失效。
     *
     * <p>只有 ③ 会有值:① / ② 从不预占 entitlement,这里恒 null(不是空表,是 null ——
     * 调用方据此区分"没有这个概念"与"有但空了")。
     */
    private List<RegistrationEntitlement> entitlements;

    /**
     * ③ 探店日票面的票种快照(瞬态,订单详情展示用):场次时间槽 startTime/endTime、
     * 集合点 meetingPoint/gatherLat/gatherLng。只有 ③ 订单详情会带,其余恒 null。
     */
    private OmsTicket omsTicket;

    public List<RegistrationEntitlement> getEntitlements() {
        return entitlements;
    }

    public void setEntitlements(List<RegistrationEntitlement> entitlements) {
        this.entitlements = entitlements;
    }

    public OmsTicket getOmsTicket() {
        return omsTicket;
    }

    public void setOmsTicket(OmsTicket omsTicket) {
        this.omsTicket = omsTicket;
    }

    public com.chengyinhub.business.util.RefundPolicy.Verdict getRefundInfo() {
        return refundInfo;
    }

    public void setRefundInfo(com.chengyinhub.business.util.RefundPolicy.Verdict refundInfo) {
        this.refundInfo = refundInfo;
    }

    public RefundApplication getRefundApplication() {
        return refundApplication;
    }

    public void setRefundApplication(RefundApplication refundApplication) {
        this.refundApplication = refundApplication;
    }

    public String getOrganizerName() {
        return organizerName;
    }

    public void setOrganizerName(String organizerName) {
        this.organizerName = organizerName;
    }

    public String getPaymentTypeLabel() {
        return paymentTypeLabel;
    }

    public void setPaymentTypeLabel(String paymentTypeLabel) {
        this.paymentTypeLabel = paymentTypeLabel;
    }

    public String getRefundDeadlineDisplay() {
        return refundDeadlineDisplay;
    }

    public void setRefundDeadlineDisplay(String refundDeadlineDisplay) {
        this.refundDeadlineDisplay = refundDeadlineDisplay;
    }

    public Integer getIsOnline() {
        return isOnline;
    }

    public void setIsOnline(Integer isOnline) {
        this.isOnline = isOnline;
    }

    public MmsMerchant getMmsMerchant() {
        return mmsMerchant;
    }

    public void setMmsMerchant(MmsMerchant mmsMerchant) {
        this.mmsMerchant = mmsMerchant;
    }

    public List<CmsRegistrationScan> getRegistrationScanList() {
        return registrationScanList;
    }

    public void setRegistrationScanList(List<CmsRegistrationScan> registrationScanList) {
        this.registrationScanList = registrationScanList;
    }

    public Integer getPointsReturned() {
        return pointsReturned;
    }

    public void setPointsReturned(Integer pointsReturned) {
        this.pointsReturned = pointsReturned;
    }




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

    public void setParticipateDate(String participateDate)
    {
        this.participateDate = participateDate;
    }

    public String getParticipateDate()
    {
        return participateDate;
    }

    public void setParentOrderNo(String parentOrderNo)
    {
        this.parentOrderNo = parentOrderNo;
    }

    public String getParentOrderNo()
    {
        return parentOrderNo;
    }

    public void setRequestId(String requestId)
    {
        this.requestId = requestId;
    }

    public String getRequestId()
    {
        return requestId;
    }

    public String getRequestFingerprint()
    {
        return requestFingerprint;
    }

    public void setRequestFingerprint(String requestFingerprint)
    {
        this.requestFingerprint = requestFingerprint;
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

    public BigDecimal getFeePlatformRate() { return feePlatformRate; }
    public void setFeePlatformRate(BigDecimal feePlatformRate) { this.feePlatformRate = feePlatformRate; }

    public void setRegistrationStatus(Integer registrationStatus) 
    {
        this.registrationStatus = registrationStatus;
    }

    public Integer getRegistrationStatus() 
    {
        return registrationStatus;
    }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public Integer getPurchaseKind() { return purchaseKind; }
    public void setPurchaseKind(Integer purchaseKind) { this.purchaseKind = purchaseKind; }

    public Long getSourceClubId() { return sourceClubId; }
    public void setSourceClubId(Long sourceClubId) { this.sourceClubId = sourceClubId; }

    public Date getSourceFrozenAt() { return sourceFrozenAt; }
    public void setSourceFrozenAt(Date sourceFrozenAt) { this.sourceFrozenAt = sourceFrozenAt; }

    public String getSourceChannelCode() { return sourceChannelCode; }
    public void setSourceChannelCode(String sourceChannelCode) { this.sourceChannelCode = sourceChannelCode; }

    public String getRefundPolicyCode() { return refundPolicyCode; }
    public void setRefundPolicyCode(String refundPolicyCode) { this.refundPolicyCode = refundPolicyCode; }

    public Integer getRefundPolicyVersion() { return refundPolicyVersion; }
    public void setRefundPolicyVersion(Integer refundPolicyVersion) { this.refundPolicyVersion = refundPolicyVersion; }

    public Date getRefundDeadline() { return refundDeadline; }
    public void setRefundDeadline(Date refundDeadline) { this.refundDeadline = refundDeadline; }

    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }

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

    public Date getFirstSuccessfulScanAt() { return firstSuccessfulScanAt; }

    public void setFirstSuccessfulScanAt(Date firstSuccessfulScanAt) {
        this.firstSuccessfulScanAt = firstSuccessfulScanAt;
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

    public CmsActivity getCmsActivity() {
        return cmsActivity;
    }

    public void setCmsActivity(CmsActivity cmsActivity) {
        this.cmsActivity = cmsActivity;
    }

    public CmsTopic getCmsTopic() {
        return cmsTopic;
    }

    public void setCmsTopic(CmsTopic cmsTopic) {
        this.cmsTopic = cmsTopic;
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
            .append("participateDate", getParticipateDate())
            .append("parentOrderNo", getParentOrderNo())
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
            .toString();
    }
}
