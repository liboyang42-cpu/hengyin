package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.ClubEditionHours;
import com.chengyinhub.business.domain.TopicEditionCost;
import com.chengyinhub.business.domain.TopicEditionFunds;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/** 后台只读期级财务视图；N_BE 明确不是开售闸。 */
public class TopicEditionFinanceOverviewVO {
    private TopicEditionFunds funds;
    private String snapshotKind;
    private TopicEditionCost cost;
    private List<ClubEditionHours> hours = Collections.emptyList();
    private BigDecimal platformCostTotal;
    private BigDecimal contributionMargin;
    private String contributionMarginReason;
    private Long breakEvenTickets;
    private String breakEvenReason;
    private final boolean breakEvenSaleGate = false;
    public TopicEditionFunds getFunds() { return funds; } public void setFunds(TopicEditionFunds v) { funds = v; }
    public String getSnapshotKind() { return snapshotKind; } public void setSnapshotKind(String v) { snapshotKind = v; }
    public TopicEditionCost getCost() { return cost; } public void setCost(TopicEditionCost v) { cost = v; }
    public List<ClubEditionHours> getHours() { return hours; } public void setHours(List<ClubEditionHours> v) { hours = v; }
    public BigDecimal getPlatformCostTotal() { return platformCostTotal; } public void setPlatformCostTotal(BigDecimal v) { platformCostTotal = v; }
    public BigDecimal getContributionMargin() { return contributionMargin; } public void setContributionMargin(BigDecimal v) { contributionMargin = v; }
    public String getContributionMarginReason() { return contributionMarginReason; } public void setContributionMarginReason(String v) { contributionMarginReason = v; }
    public Long getBreakEvenTickets() { return breakEvenTickets; } public void setBreakEvenTickets(Long v) { breakEvenTickets = v; }
    public String getBreakEvenReason() { return breakEvenReason; } public void setBreakEvenReason(String v) { breakEvenReason = v; }
    public boolean isBreakEvenSaleGate() { return breakEvenSaleGate; }
}
