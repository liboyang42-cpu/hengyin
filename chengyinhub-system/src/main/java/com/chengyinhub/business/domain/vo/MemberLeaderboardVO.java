package com.chengyinhub.business.domain.vo;

import java.util.Collections;
import java.util.List;

/**
 * 成长中心排行榜视图:某个 metric × period 组合下的前 N 名 + 我的名次。
 */
public class MemberLeaderboardVO {
    /** 排名指标:point / exp */
    private String metric;
    /** 周期:total / week */
    private String period;
    private List<LeaderboardItemVO> list = Collections.emptyList();
    /** 当前登录用户的名次(含 rankPercentage) */
    private LeaderboardItemVO me;

    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public List<LeaderboardItemVO> getList() { return list; }
    public void setList(List<LeaderboardItemVO> list) { this.list = list; }
    public LeaderboardItemVO getMe() { return me; }
    public void setMe(LeaderboardItemVO me) { this.me = me; }
}
