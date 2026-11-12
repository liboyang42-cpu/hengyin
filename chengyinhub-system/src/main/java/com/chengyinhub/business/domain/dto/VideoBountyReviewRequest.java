package com.chengyinhub.business.domain.dto;

import java.util.Date;

/**
 * 审核决定请求。
 *
 * @author chengyinhub
 */
public class VideoBountyReviewRequest
{
    /** APPROVE 或 REJECT(大小写不敏感)。 */
    private String decision;
    /** 通过时必填:真实发布时间,作为 7 天窗口起点。 */
    private Date publishedAt;
    /** 拒绝/通过备注。 */
    private String reviewNote;

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public Date getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Date publishedAt) { this.publishedAt = publishedAt; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}
