package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 专业主题 V2 保存后的审核合同。 */
public class TopicBundleSubmitResultVO {
    private boolean published;
    private boolean commercialAwaitingReview;
    private String commercialNextStep;
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
    public Long getTopicId() { return topicId; }
    public Long getAuditTaskId() { return auditTaskId; }
    public String getReviewState() { return reviewState; }
    public List<Long> getBundledTemplateIds() { return bundledTemplateIds; }
}
