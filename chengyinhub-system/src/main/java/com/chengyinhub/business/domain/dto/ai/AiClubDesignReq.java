package com.chengyinhub.business.domain.dto.ai;

/**
 * 俱乐部活动策划请求。
 *
 * @author chengyinhub
 */
public class AiClubDesignReq
{
    /** 一句话想法 */
    private String idea;

    /** 俱乐部风格 (如"户外探险"/"文艺慢生活") */
    private String clubStyle;

    /** 期望时长(分钟) */
    private Integer targetDurationMin;

    public String getIdea() { return idea; }
    public void setIdea(String idea) { this.idea = idea; }
    public String getClubStyle() { return clubStyle; }
    public void setClubStyle(String clubStyle) { this.clubStyle = clubStyle; }
    public Integer getTargetDurationMin() { return targetDurationMin; }
    public void setTargetDurationMin(Integer targetDurationMin) { this.targetDurationMin = targetDurationMin; }
}
