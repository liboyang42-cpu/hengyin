package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 合作双向互评 coop_review(报告§六 · F4-2)。活动结束后发布者↔商家互评。
 */
public class CoopReview extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long topicId;
    /** 评价人 */
    private Long fromId;
    /** 被评人 */
    private Long toId;
    /** 评分 1~5 */
    private Integer rating;
    private String comment;
    private Integer delFlag;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getFromId() { return fromId; }
    public void setFromId(Long fromId) { this.fromId = fromId; }
    public Long getToId() { return toId; }
    public void setToId(Long toId) { this.toId = toId; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
}
