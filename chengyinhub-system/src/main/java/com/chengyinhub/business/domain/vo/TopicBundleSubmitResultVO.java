package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 专业主题 V2 保存后的审核合同。
 *
 * <p>{@code published} 是「玩家现在看得见」,不是「过审了」—— 专业主题先公开后审,
 * 两者必须分开读:{@code reviewPassed}(checkStatus=1)才是过审,{@code publicVisible} 是在架。
 * 其余字段都是 {@link TopicPublicationStatusVO} 五段事实的扁平镜像,便于老客户端只读单个字段。
 */
public class TopicBundleSubmitResultVO {
    private boolean published;
    private boolean commercialAwaitingReview;
    private String commercialNextStep;

    private boolean reviewPassed;
    private Integer checkStatus;
    /** 本次送审内容绑定的主题版本;审核裁决按它比对,换版即失效(改版不会批准错版)。 */
    private Long reviewConfigVersion;
    private boolean publicVisible;
    private String publicReason;
    private boolean saleOpen;
    private String saleBlockReason;
    private boolean playable;
    private String playableReason;
    private TopicPublicationStatusVO publicationStatus;

    private final Long topicId;
    private final Long auditTaskId;
    private final String reviewState;
    private final List<Long> bundledTemplateIds;

    public TopicBundleSubmitResultVO(Long topicId, Long auditTaskId, String reviewState,
                                     List<Long> bundledTemplateIds) {
        this.topicId = topicId;
        this.auditTaskId = auditTaskId;
        this.reviewState = reviewState;
        this.bundledTemplateIds = Collections.unmodifiableList(new ArrayList<Long>(bundledTemplateIds));
    }

    public boolean isCommercialAwaitingReview() { return commercialAwaitingReview; }
    public void setCommercialAwaitingReview(boolean value) { this.commercialAwaitingReview = value; }
    public String getCommercialNextStep() { return commercialNextStep; }
    public void setCommercialNextStep(String value) { this.commercialNextStep = value; }
    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }
    public boolean isReviewPassed() { return reviewPassed; }
    public void setReviewPassed(boolean reviewPassed) { this.reviewPassed = reviewPassed; }
    public Integer getCheckStatus() { return checkStatus; }
    public void setCheckStatus(Integer checkStatus) { this.checkStatus = checkStatus; }
    public Long getReviewConfigVersion() { return reviewConfigVersion; }
    public void setReviewConfigVersion(Long reviewConfigVersion) { this.reviewConfigVersion = reviewConfigVersion; }
    public boolean isPublicVisible() { return publicVisible; }
    public void setPublicVisible(boolean publicVisible) { this.publicVisible = publicVisible; }
    public String getPublicReason() { return publicReason; }
    public void setPublicReason(String publicReason) { this.publicReason = publicReason; }
    public boolean isSaleOpen() { return saleOpen; }
    public void setSaleOpen(boolean saleOpen) { this.saleOpen = saleOpen; }
    public String getSaleBlockReason() { return saleBlockReason; }
    public void setSaleBlockReason(String saleBlockReason) { this.saleBlockReason = saleBlockReason; }
    public boolean isPlayable() { return playable; }
    public void setPlayable(boolean playable) { this.playable = playable; }
    public String getPlayableReason() { return playableReason; }
    public void setPlayableReason(String playableReason) { this.playableReason = playableReason; }
    public TopicPublicationStatusVO getPublicationStatus() { return publicationStatus; }
    public void setPublicationStatus(TopicPublicationStatusVO publicationStatus) { this.publicationStatus = publicationStatus; }
    public Long getTopicId() { return topicId; }
    public Long getAuditTaskId() { return auditTaskId; }
    public String getReviewState() { return reviewState; }
    public List<Long> getBundledTemplateIds() { return bundledTemplateIds; }
}
