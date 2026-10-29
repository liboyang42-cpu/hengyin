package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;

/**
 * 商家章节承接申请 merchant_chapter_application。
 *
 * <p>这是意向与审核记录，不是可核销的生效供给。只有通过审核后，商家才可调用
 * MerchantOfferService.enroll 写入 merchant_chapter_offer。</p>
 */
public class MerchantChapterApplication extends BaseEntity {
    private static final long serialVersionUID = 1L;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_APPROVED = 1;
    public static final int STATUS_REJECTED = 2;

    private Long id;
    /** Persistent attempt identity. NULL means legacy evidence is unknown, never an implicit round 1. */
    private Long applicationRound;
    /** 由 chapterId 反查写入，仅用于查询索引，不能作为归属判据。 */
    private Long topicId;
    private Long chapterId;
    /** 本次承接所补位的原退出供给；由主办邀请与服务端确认链写入。 */
    private Long replacementOfOfferId;
    /** 商家 memberId，不是 mms_merchant.id。 */
    private Long merchantId;
    /** 0待审核 1已通过 2已拒绝。 */
    private Integer status;

    /**
     * 来源:0 商家申请 / 1 主办方邀请。
     * ★邀请不另建链路 —— 它就是「预先批准的申请」(建出来即 status=已通过)。
     * 这一列还决定**点位要不要再审**:邀请来的免审,人是主办方主动邀的。
     */
    private Integer source;
    private String message;
    private String auditRemark;
    /** 实际审核的主题发布者 memberId。 */
    private Long auditMemberId;
    private Date handleTime;
    private Integer delFlag;

    /* 仅授权后的列表展示字段，不落本表。 */
    private String topicName;
    private String topicImgUrl;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date startDate;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date endDate;
    private String chapterName;
    private String merchantName;
    /** 当前商家在本章节是否已有生效供给；仅“我的申请”列表回显，不落库。 */
    private Boolean offerActive;
    /**
     * 当前商家在本章节的供给占位状态；仅“我的申请”列表回显，不落库。
     * NONE=无记录，ACTIVE=生效，INACTIVE=已停用，EXPIRED=已过期。
     */
    private String offerState;
    /** 本人或原主办的供给退出历史只读投影，不恢复申请或承接资格。 */
    private String offerWithdrawReason;
    private Long offerId;
    private String circleThemeCode;
    private Date circleSupplyCheckedAt;

    public Long getApplicationRound() { return applicationRound; }
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public void setApplicationRound(Long value) { applicationRound = value; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getReplacementOfOfferId() { return replacementOfOfferId; }
    public void setReplacementOfOfferId(Long value) { this.replacementOfOfferId = value; }
    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public static final int SOURCE_APPLY = 0;
    public static final int SOURCE_INVITE = 1;

    public Integer getSource()
    {
        return source;
    }

    public void setSource(Integer source)
    {
        this.source = source;
    }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getAuditRemark() { return auditRemark; }
    public void setAuditRemark(String auditRemark) { this.auditRemark = auditRemark; }
    public Long getAuditMemberId() { return auditMemberId; }
    public void setAuditMemberId(Long auditMemberId) { this.auditMemberId = auditMemberId; }
    public Date getHandleTime() { return handleTime; }
    public void setHandleTime(Date handleTime) { this.handleTime = handleTime; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public String getTopicName() { return topicName; }
    public void setTopicName(String topicName) { this.topicName = topicName; }
    public String getTopicImgUrl() { return topicImgUrl; }
    public void setTopicImgUrl(String value) { this.topicImgUrl = value; }
    public Date getStartDate() { return startDate; }
    public void setStartDate(Date value) { this.startDate = value; }
    public Date getEndDate() { return endDate; }
    public void setEndDate(Date value) { this.endDate = value; }
    public String getChapterName() { return chapterName; }
    public void setChapterName(String chapterName) { this.chapterName = chapterName; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public Boolean getOfferActive() { return offerActive; }
    public void setOfferActive(Boolean offerActive) { this.offerActive = offerActive; }
    public String getOfferWithdrawReason() { return offerWithdrawReason; }
    public void setOfferWithdrawReason(String value) { this.offerWithdrawReason = value; }
    public String getOfferState() { return offerState; }
    public void setOfferState(String offerState) { this.offerState = offerState; }
    public Long getOfferId() { return offerId; }
    public void setOfferId(Long value) { this.offerId = value; }
    public String getCircleThemeCode() { return circleThemeCode; }
    public void setCircleThemeCode(String value) { this.circleThemeCode = value; }
    public Date getCircleSupplyCheckedAt() { return circleSupplyCheckedAt; }
    public void setCircleSupplyCheckedAt(Date value) { this.circleSupplyCheckedAt = value; }
}
