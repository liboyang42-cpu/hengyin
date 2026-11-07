package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 机审未完成内容的自动复检队列行(Round5C)。内容在复检通过前只有作者自己看得到;
 * 复检任务按 next_attempt_at 有界重试,通过自动发布、违规自动下架、耗尽自动驳回并通知作者。
 */
public class ContentSafetyRecheck {
    public static final int STATE_PENDING = 0;
    public static final int STATE_PASSED = 1;
    public static final int STATE_REJECTED = 2;
    public static final int STATE_EXHAUSTED = 3;
    public static final int STATE_EXPIRED = 4;

    private Long id;
    private String bizType;
    private Long bizId;
    private Long memberId;
    private String textBody;
    private String mediaJson;
    /** 1 = 通过后仍需人工的业务审批(价格/地点/报名、官方活动),复检只负责内容安全部分。 */
    private Integer businessReview;
    private Integer state;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Date nextAttemptAt;
    private String lastErrorCode;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getTextBody() { return textBody; }
    public void setTextBody(String textBody) { this.textBody = textBody; }
    public String getMediaJson() { return mediaJson; }
    public void setMediaJson(String mediaJson) { this.mediaJson = mediaJson; }
    public Integer getBusinessReview() { return businessReview; }
    public void setBusinessReview(Integer businessReview) { this.businessReview = businessReview; }
    public Integer getState() { return state; }
    public void setState(Integer state) { this.state = state; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }
    public Date getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Date nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
