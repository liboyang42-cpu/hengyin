package com.chengyinhub.business.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;

/** 俱乐部主理人主动开一场：选择已有合作主题、出发日期与集合时刻。 */
public class ClubSessionOpenRequest {
    private Long clubId;
    private Long topicId;

    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private Date sessionDate;

    /**
     * 集合时刻(HH:mm,Asia/Shanghai)。5.9 场次退款钟(集合前 24h)的锚点 ——
     * 缺省时后端拒绝开场,不再静默压成 00:00(那会让实际截止比页面承诺早半天到一天)。
     */
    private String sessionTime;

    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public Date getSessionDate() { return sessionDate; }
    public void setSessionDate(Date sessionDate) { this.sessionDate = sessionDate; }

    public String getSessionTime() { return sessionTime; }
    public void setSessionTime(String sessionTime) { this.sessionTime = sessionTime; }
}
