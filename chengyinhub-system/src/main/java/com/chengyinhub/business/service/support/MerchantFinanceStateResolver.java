package com.chengyinhub.business.service.support;

/** 商家资金读模型的展示态归一；履约与结算保持为两条事实轴。 */
public final class MerchantFinanceStateResolver {
    private MerchantFinanceStateResolver() { }

    public static String resolve(String fulfillmentState, String settlementState) {
        if ("ERROR".equals(settlementState)) return "LEDGER_ERROR";
        if ("NOT_APPLICABLE".equals(settlementState)) return "NO_CASH_SETTLEMENT";
        if ("ADJUSTMENT_PENDING".equals(settlementState)) return "ADJUSTMENT_PENDING";
        if ("ADJUSTED".equals(settlementState)) return "ADJUSTED";
        if ("REVERSED".equals(fulfillmentState)) return "REVERSED_BEFORE_SETTLEMENT";
        if ("SETTLED".equals(settlementState)) return "SETTLED";
        return "PENDING_SETTLEMENT";
    }
}
