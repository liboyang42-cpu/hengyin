package com.chengyinhub.business.begapmisc;

import com.chengyinhub.business.service.rewardpledge.CashPayoutSwitch;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Server side of the app's {@code ReviewedAreaSwitch.moneyPayments} / {@code payout} for the misc money writes
 * ({@code /api/pay/create}, {@code /api/asset/withdraw}). Same properties as {@link CashPayoutSwitch}
 * ({@code chengyin.release.money-payments}, {@code chengyin.release.payout}), both default off. Closed is the standard
 * {@code code 403, errorCode FEATURE_CLOSED}, answered before the body is read.
 */
@Component
public class MiscMoneySwitch {
    private final boolean moneyPayments;
    private final boolean payout;

    public MiscMoneySwitch(@Value("${chengyin.release.money-payments:false}") boolean moneyPayments,
                           @Value("${chengyin.release.payout:false}") boolean payout) {
        this.moneyPayments = moneyPayments;
        this.payout = payout;
    }

    /** Paying for an order needs the payments switch. */
    public boolean paymentsOpen() { return moneyPayments; }

    /** Money leaving the platform needs both switches, as for the reward cash paths. */
    public boolean payoutOpen() { return moneyPayments && payout; }

    public static AjaxResult closed() {
        AjaxResult result = new AjaxResult(403, CashPayoutSwitch.FEATURE_CLOSED);
        result.put("errorCode", CashPayoutSwitch.FEATURE_CLOSED);
        return result;
    }
}
