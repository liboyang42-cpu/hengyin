package com.chengyinhub.business.domain.vo;

/**
 * O-14 每日推荐打分的候选池行(只含公开可见内容)。
 */
public class RecommendationCandidateVO {
    private String candidateType;
    private Long candidateId;
    private Long ownerMemberId;
    private String cityCode;
    private Long popularity;

    public String getCandidateType() { return candidateType; }
    public void setCandidateType(String candidateType) { this.candidateType = candidateType; }
    public Long getCandidateId() { return candidateId; }
    public void setCandidateId(Long candidateId) { this.candidateId = candidateId; }
    public Long getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(Long ownerMemberId) { this.ownerMemberId = ownerMemberId; }
    public String getCityCode() { return cityCode; }
    public void setCityCode(String cityCode) { this.cityCode = cityCode; }
    public Long getPopularity() { return popularity; }
    public void setPopularity(Long popularity) { this.popularity = popularity; }
}
