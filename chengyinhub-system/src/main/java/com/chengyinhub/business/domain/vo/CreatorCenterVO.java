package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CreatorIncomeDetail;
import com.chengyinhub.business.domain.CreatorMetricDaily;
import com.chengyinhub.business.domain.CreatorProfile;

import java.util.Collections;
import java.util.List;

/**
 * P3 创作者中心视图。
 */
public class CreatorCenterVO {
    private String applyStatus;
    private CreatorProfile profile;
    private CreatorMetricDaily metric;
    private List<CreatorIncomeDetail> recentIncome = Collections.emptyList();

    public String getApplyStatus() { return applyStatus; }
    public void setApplyStatus(String applyStatus) { this.applyStatus = applyStatus; }
    public CreatorProfile getProfile() { return profile; }
    public void setProfile(CreatorProfile profile) { this.profile = profile; }
    public CreatorMetricDaily getMetric() { return metric; }
    public void setMetric(CreatorMetricDaily metric) { this.metric = metric; }
    public List<CreatorIncomeDetail> getRecentIncome() { return recentIncome; }
    public void setRecentIncome(List<CreatorIncomeDetail> recentIncome) { this.recentIncome = recentIncome; }
}
