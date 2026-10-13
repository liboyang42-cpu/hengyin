package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;

/**
 * P3 推荐候选项。
 */
public class RecommendationItemVO {
    private String candidateType;
    private Long candidateId;
    private String title;
    private String imgUrl;
    private BigDecimal score;
    private String reasonCodes;
    private Integer status;
    private Integer publishStatus;
    private Integer delFlag;

    public String getCandidateType() { return candidateType; }
    public void setCandidateType(String candidateType) { this.candidateType = candidateType; }
    public Long getCandidateId() { return candidateId; }
    public void setCandidateId(Long candidateId) { this.candidateId = candidateId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getImgUrl() { return imgUrl; }
    public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public String getReasonCodes() { return reasonCodes; }
    public void setReasonCodes(String reasonCodes) { this.reasonCodes = reasonCodes; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getPublishStatus() { return publishStatus; }
    public void setPublishStatus(Integer publishStatus) { this.publishStatus = publishStatus; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
}
