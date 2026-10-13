package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.List;

public class CoopSettlementPlan {
    private final CoopSettlementSnapshot snapshot;
    private final String route;
    private final List<CoopSettlementOriginLine> originLines;

    public CoopSettlementPlan(CoopSettlementSnapshot snapshot, String route,
            List<CoopSettlementOriginLine> originLines) {
        this.snapshot = snapshot;
        this.route = route;
        this.originLines = originLines;
    }

    public CoopSettlementSnapshot getSnapshot() { return snapshot; }
    public String getRoute() { return route; }
    public List<CoopSettlementOriginLine> getOriginLines() { return originLines; }
    public BigDecimal getOriginAmount() {
        return com.chengyinhub.business.service.support.CoopSettlementContract.sumOrigin(originLines);
    }
    public int getVerifiedHeads() { return originLines.size(); }
    public BigDecimal getEligibleSales() {
        BigDecimal sum = BigDecimal.ZERO;
        for (CoopSettlementOriginLine line : originLines) {
            if (line.getEligibleSalesSnapshot() != null) sum = sum.add(line.getEligibleSalesSnapshot());
        }
        return sum;
    }
}
