package com.chengyinhub.business.service.rewardpledge;

import com.chengyinhub.business.moneyswitch.MoneyArea;
import com.chengyinhub.business.moneyswitch.MoneyGate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Server side of the app's ReviewedAreaSwitch.moneyPayments && ReviewedAreaSwitch.payout. Anything in the reward
 * package that would pay out cash is refused with FEATURE_CLOSED unless both are on. Both default to off; TEST
 * turns them on in its overlay properties. Points, badges and coupon-backed non-cash rewards never read this.
 *
 * <p>W7 MERCHANT-3: open also needs the Q5 {@code payout} area ({@link MoneyGate}), so the admin page's 资金开关 closes
 * these paths without a restart. Without a gate (hand-built in unit tests) it is closed.
 */
@Component
public class CashPayoutSwitch {
    public static final String FEATURE_CLOSED = "FEATURE_CLOSED";
    private final boolean moneyPayments;
    private final boolean payout;
    private final MoneyGate gate;

    @Autowired
    public CashPayoutSwitch(@Value("${chengyin.release.money-payments:false}") boolean moneyPayments,
                            @Value("${chengyin.release.payout:false}") boolean payout,
                            MoneyGate gate) {
        this.moneyPayments = moneyPayments;
        this.payout = payout;
        this.gate = gate;
    }

    /** No Q5 gate: closed whatever the legacy properties say. */
    public CashPayoutSwitch(boolean moneyPayments, boolean payout) {
        this(moneyPayments, payout, null);
    }

    public static CashPayoutSwitch off() { return new CashPayoutSwitch(false, false); }

    public boolean open() { return moneyPayments && payout && gate != null && gate.isOpen(MoneyArea.PAYOUT); }
}
