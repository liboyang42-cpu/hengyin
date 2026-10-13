package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;


public class PointsStatisticsDTO {
    // 本周增加积分
    private BigDecimal weekPoints;
    // 用户排名百分比
    private String rankPercentage;
    // 总用户数
    private Integer totalUsers;
    // 当前用户排名
    private Integer userRank;

    public BigDecimal getWeekPoints() {
        return weekPoints;
    }

    public void setWeekPoints(BigDecimal weekPoints) {
        this.weekPoints = weekPoints;
    }

    public String getRankPercentage() {
        return rankPercentage;
    }

    public void setRankPercentage(String rankPercentage) {
        this.rankPercentage = rankPercentage;
    }

    public Integer getTotalUsers() {
        return totalUsers;
    }

    public void setTotalUsers(Integer totalUsers) {
        this.totalUsers = totalUsers;
    }

    public Integer getUserRank() {
        return userRank;
    }

    public void setUserRank(Integer userRank) {
        this.userRank = userRank;
    }

    public PointsStatisticsDTO() {
        this.weekPoints = BigDecimal.ZERO;
        this.rankPercentage = "TOP0%";
        this.totalUsers = 0;
        this.userRank = 0;
    }
}