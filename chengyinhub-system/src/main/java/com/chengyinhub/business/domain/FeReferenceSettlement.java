package com.chengyinhub.business.domain;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.Date;

/**
 * F11 自由探索店铺参考结算行(表 fe_reference_settlement)。
 *
 * <p>参考金额:线上算完整给各方看,钱不经过平台划给店铺,由场次收款人与店铺线下结款后标记
 * {@link #STATUS_SETTLED_OFFLINE}。本行不参与任何余额 / 提现 / 退款追回汇总。
 */
public class FeReferenceSettlement {

    public static final String BASIS_PERK_REDEEMED = "PERK_REDEEMED";
    public static final String BASIS_REVSHARE_HEAD = "REVSHARE_HEAD";
    /** 引流:预留,本次不写。 */
    public static final String BASIS_TRAFFIC = "TRAFFIC";
    /** 分销佣金:预留(业主「马上可以分销」),本次不写。 */
    public static final String BASIS_DISTRIBUTION = "DISTRIBUTION";

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_SETTLED_OFFLINE = "SETTLED_OFFLINE";
    public static final String STATUS_VOID = "VOID";
    public static final String STATUS_DISPUTED = "DISPUTED";

    public static final String PAYER_CLUB = "CLUB";
    public static final String PAYER_MEMBER = "MEMBER";

    /** AUTHOR 承接行的固定备注。 */
    public static final String NOTE_AUTHOR_SELF_SUPPLY = "作者自营";

    private Long id;
    private Long topicId;
    private Long activityId;
    private Long chapterId;
    private Long offerId;
    private String supplierKind;
    private Long merchantId;
    private String payerKind;
    /** CLUB = 俱乐部 id;MEMBER = 收款人 member_id。 */
    private Long payerId;
    private String basis;
    private Integer quantity;
    private BigDecimal unitAmount;
    private BigDecimal amount;
    private String status;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date settledAt;
    private Long settledBy;
    private String voucherNo;
    private String note;
    private Long registrationId;
    private Long redemptionId;
    private String idempotencyKey;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;

    /** 只读联表:场次标题(无场次时为主题名)。 */
    private String activityTitle;
    /** 只读联表:章节名。 */
    private String chapterName;
    /** 只读联表:店铺名。 */
    private String merchantName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
    public Long getOfferId() { return offerId; }
    public void setOfferId(Long offerId) { this.offerId = offerId; }
    public String getSupplierKind() { return supplierKind; }
    public void setSupplierKind(String supplierKind) { this.supplierKind = supplierKind; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public String getPayerKind() { return payerKind; }
    public void setPayerKind(String payerKind) { this.payerKind = payerKind; }
    public Long getPayerId() { return payerId; }
    public void setPayerId(Long payerId) { this.payerId = payerId; }
    public String getBasis() { return basis; }
    public void setBasis(String basis) { this.basis = basis; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getUnitAmount() { return unitAmount; }
    public void setUnitAmount(BigDecimal unitAmount) { this.unitAmount = unitAmount; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Date getSettledAt() { return settledAt; }
    public void setSettledAt(Date settledAt) { this.settledAt = settledAt; }
    public Long getSettledBy() { return settledBy; }
    public void setSettledBy(Long settledBy) { this.settledBy = settledBy; }
    public String getVoucherNo() { return voucherNo; }
    public void setVoucherNo(String voucherNo) { this.voucherNo = voucherNo; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Long getRedemptionId() { return redemptionId; }
    public void setRedemptionId(Long redemptionId) { this.redemptionId = redemptionId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
    public String getActivityTitle() { return activityTitle; }
    public void setActivityTitle(String activityTitle) { this.activityTitle = activityTitle; }
    public String getChapterName() { return chapterName; }
    public void setChapterName(String chapterName) { this.chapterName = chapterName; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
}
