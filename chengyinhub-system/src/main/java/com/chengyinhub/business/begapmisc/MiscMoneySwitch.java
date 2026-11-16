package com.chengyinhub.business.begapmisc;

import com.chengyinhub.business.moneyswitch.MoneyArea;
import com.chengyinhub.business.moneyswitch.MoneyGate;
import com.chengyinhub.business.service.rewardpledge.CashPayoutSwitch;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Server side of the app's {@code ReviewedAreaSwitch.moneyPayments} / {@code payout} for the misc money writes
 * ({@code /api/pay/create}, {@code /api/asset/withdraw}). Same properties as {@link CashPayoutSwitch}
 * ({@code chengyin.release.money-payments}, {@code chengyin.release.payout}), both default off. Closed is the standard
 * {@code code 403, errorCode FEATURE_CLOSED}, answered before the body is read.
 *
 * <p>W7 MERCHANT-3: each answer is the legacy property AND the Q5 area ({@link MoneyGate}): payments need
 * {@code tickets}, payouts need {@code payout}. Closing the area on the admin page closes the server path at once.
 * Without a gate (unit tests that build this by hand) every path is closed.
 */
@Component
public class MiscMoneySwitch {
    private final boolean moneyPayments;
    private final boolean payout;
    private final MoneyGate gate;

    @Autowired
    public MiscMoneySwitch(@Value("${chengyin.release.money-payments:false}") boolean moneyPayments,
                           @Value("${chengyin.release.payout:false}") boolean payout,
                           MoneyGate gate) {
        this.moneyPayments = moneyPayments;
        this.payout = payout;
        this.gate = gate;
    }

    /** No Q5 gate: every path reads closed whatever the legacy properties say. */
    public MiscMoneySwitch(boolean moneyPayments, boolean payout) {
        this(moneyPayments, payout, null);
    }

    /** Paying for an order needs the payments property and the Q5 {@code tickets} area. */
    public boolean paymentsOpen() { return moneyPayments && areaOpen(MoneyArea.TICKETS); }

    /** Money leaving the platform needs both properties and the Q5 {@code payout} area, as for the reward cash paths. */
    public boolean payoutOpen() { return moneyPayments && payout && areaOpen(MoneyArea.PAYOUT); }

    private boolean areaOpen(MoneyArea area) { return gate != null && gate.isOpen(area); }

    public static AjaxResult closed() {
        AjaxResult result = new AjaxResult(403, CashPayoutSwitch.FEATURE_CLOSED);
        result.put("errorCode", CashPayoutSwitch.FEATURE_CLOSED);
        return result;
    }
}
