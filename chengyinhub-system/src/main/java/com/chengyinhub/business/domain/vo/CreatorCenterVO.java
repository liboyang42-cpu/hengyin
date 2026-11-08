package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CreatorIncomeDetail;
import com.chengyinhub.business.domain.CreatorMetricDaily;
import com.chengyinhub.business.domain.CreatorProfile;

import java.util.Collections;
import java.util.List;

/**
 * P3 创作者中心视图。
 *
 * <p>{@code recentIncome} 保留原有语义（{@code creator_income_detail} 最近明细），不做改动。
 * 新增的原账本收益走独立字段 {@code revenue}，并总是带 {@code revenueState}：
 * {@code AVAILABLE} 时数值可信；{@code UNKNOWN} 时 {@code revenue} 为 null，
 * 表示「读不到」，<b>不等于 0 收入</b>。
 */
public class CreatorCenterVO {
    private String applyStatus;
    private CreatorProfile profile;
    private CreatorMetricDaily metric;
    private List<CreatorIncomeDetail> recentIncome = Collections.emptyList();
    private CreatorRevenueLedgerVO revenue;
    private CreatorRevenueLedgerVO.State revenueState = CreatorRevenueLedgerVO.State.UNKNOWN;
    private String revenueUnavailableReason;

    public String getApplyStatus() { return applyStatus; }
    public void setApplyStatus(String applyStatus) { this.applyStatus = applyStatus; }
    public CreatorProfile getProfile() { return profile; }
    public void setProfile(CreatorProfile profile) { this.profile = profile; }
    public CreatorMetricDaily getMetric() { return metric; }
    public void setMetric(CreatorMetricDaily metric) { this.metric = metric; }
    public List<CreatorIncomeDetail> getRecentIncome() { return recentIncome; }
    public void setRecentIncome(List<CreatorIncomeDetail> recentIncome) { this.recentIncome = recentIncome; }
    public CreatorRevenueLedgerVO getRevenue() { return revenue; }
    public void setRevenue(CreatorRevenueLedgerVO revenue) { this.revenue = revenue; }
    public CreatorRevenueLedgerVO.State getRevenueState() { return revenueState; }
    public void setRevenueState(CreatorRevenueLedgerVO.State revenueState) { this.revenueState = revenueState; }
    public String getRevenueUnavailableReason() { return revenueUnavailableReason; }
    public void setRevenueUnavailableReason(String revenueUnavailableReason) {
        this.revenueUnavailableReason = revenueUnavailableReason;
    }
}
