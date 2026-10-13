package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.GrowthMission;
import com.chengyinhub.business.domain.GrowthMissionProgress;
import com.chengyinhub.business.domain.MemberGrowth;
import com.chengyinhub.business.domain.PlayerBadge;

import java.util.Collections;
import java.util.List;

/**
 * P3 玩家成长中心视图。
 */
public class MemberGrowthCenterVO {
    private MemberGrowth growth;
    /** 统一积分余额(UmsMember.point,与资产中心同池) */
    private Long points;
    /** 全站积分总榜名次；没有可排名数据时为 null。 */
    private Integer rank;
    private List<GrowthMission> missions = Collections.emptyList();
    private List<GrowthMissionProgress> progress = Collections.emptyList();
    private List<PlayerBadge> badges = Collections.emptyList();

    public MemberGrowth getGrowth() { return growth; }
    public void setGrowth(MemberGrowth growth) { this.growth = growth; }
    public Long getPoints() { return points; }
    public void setPoints(Long points) { this.points = points; }
    public Integer getRank() { return rank; }
    public void setRank(Integer rank) { this.rank = rank; }
    public List<GrowthMission> getMissions() { return missions; }
    public void setMissions(List<GrowthMission> missions) { this.missions = missions; }
    public List<GrowthMissionProgress> getProgress() { return progress; }
    public void setProgress(List<GrowthMissionProgress> progress) { this.progress = progress; }
    public List<PlayerBadge> getBadges() { return badges; }
    public void setBadges(List<PlayerBadge> badges) { this.badges = badges; }
}
