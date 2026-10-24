package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.Map;

/** 同一次数据库快照中的看板累计收入和每日收入。 */
public final class MerchantDashboardIncome {
    private final BigDecimal total;
    private final Map<String, BigDecimal> byDay;

    public MerchantDashboardIncome(BigDecimal total, Map<String, BigDecimal> byDay) {
        this.total = total == null ? BigDecimal.ZERO : total;
        this.byDay = Map.copyOf(byDay);
    }

    public BigDecimal getTotal() {
        return total;
    }

    public Map<String, BigDecimal> getByDay() {
        return byDay;
    }
}
