package com.chengyinhub.business.domain.dto;

import java.util.Date;

import com.chengyinhub.business.domain.VideoBountySubmission;

/**
 * 投稿投影:只暴露作者需要的字段,不泄露内部审核人 id。
 *
 * @author chengyinhub
 */
public class VideoBountySubmissionView
{
    private Long id;
    private String campaignKey;
    private Long topicId;
    private String platform;
    private String sourceUrl;
    private String originGroupKey;
    private Integer status;
    private String statusLabel;
    private Date submittedAt;
    private Date publishedAt;
    private Date windowEndsAt;
    private String reviewNote;

    public static VideoBountySubmissionView of(VideoBountySubmission row) {
        VideoBountySubmissionView view = new VideoBountySubmissionView();
        view.id = row.getId();
        view.campaignKey = row.getCampaignKey();
        view.topicId = row.getTopicId();
        view.platform = row.getPlatform();
        view.sourceUrl = row.getSourceUrl();
        view.originGroupKey = row.getOriginGroupKey();
        view.status = row.getStatus();
        view.statusLabel = label(row.getStatus());
        view.submittedAt = row.getSubmittedAt();
        view.publishedAt = row.getPublishedAt();
        view.windowEndsAt = row.getWindowEndsAt();
        view.reviewNote = row.getReviewNote();
        return view;
    }

    private static String label(Integer status) {
        if (status == null) return "UNKNOWN";
        switch (status) {
            case VideoBountySubmission.STATUS_SUBMITTED: return "SUBMITTED";
            case VideoBountySubmission.STATUS_UNDER_REVIEW: return "UNDER_REVIEW";
            case VideoBountySubmission.STATUS_APPROVED: return "APPROVED";
            case VideoBountySubmission.STATUS_REJECTED: return "REJECTED";
            case VideoBountySubmission.STATUS_REVOKED: return "REVOKED";
            case VideoBountySubmission.STATUS_APPEALING: return "APPEALING";
            case VideoBountySubmission.STATUS_CANCELLED: return "CANCELLED";
            default: return "UNKNOWN";
        }
    }

    public Long getId() { return id; }
    public String getCampaignKey() { return campaignKey; }
    public Long getTopicId() { return topicId; }
    public String getPlatform() { return platform; }
    public String getSourceUrl() { return sourceUrl; }
    public String getOriginGroupKey() { return originGroupKey; }
    public Integer getStatus() { return status; }
    public String getStatusLabel() { return statusLabel; }
    public Date getSubmittedAt() { return submittedAt; }
    public Date getPublishedAt() { return publishedAt; }
    public Date getWindowEndsAt() { return windowEndsAt; }
    public String getReviewNote() { return reviewNote; }
}
