package com.chengyinhub.business.domain.vo;

/** 生效俱乐部合作单尚未产出对应带队场次的对账差异。 */
public class ClubSessionGapVO {
    private Long topicId;
    private Long clubId;

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
}
