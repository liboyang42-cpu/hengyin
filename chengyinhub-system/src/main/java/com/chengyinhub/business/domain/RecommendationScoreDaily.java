package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.math.BigDecimal;
import java.util.Date;

/**
 * P3 每日推荐评分对象 recommendation_score_daily。
 */
public class RecommendationScoreDaily extends BaseEntity {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Date statDate;
    private Long memberId;
    private String candidateType;
    private Long candidateId;
    private BigDecimal score;
    private String reasonCodes;
    private Integer rankPosition;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Date getStatDate() { return statDate; }
    public void setStatDate(Date statDate) { this.statDate = statDate; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getCandidateType() { return candidateType; }
    public void setCandidateType(String candidateType) { this.candidateType = candidateType; }
    public Long getCandidateId() { return candidateId; }
    public void setCandidateId(Long candidateId) { this.candidateId = candidateId; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public String getReasonCodes() { return reasonCodes; }
    public void setReasonCodes(String reasonCodes) { this.reasonCodes = reasonCodes; }
    public Integer getRankPosition() { return rankPosition; }
    public void setRankPosition(Integer rankPosition) { this.rankPosition = rankPosition; }
}
