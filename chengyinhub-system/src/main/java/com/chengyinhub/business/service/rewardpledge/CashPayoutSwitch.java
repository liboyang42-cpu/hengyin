package com.chengyinhub.business.service.rewardpledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Server side of the app's ReviewedAreaSwitch.moneyPayments && ReviewedAreaSwitch.payout. Anything in the reward
 * package that would pay out cash is refused with FEATURE_CLOSED unless both are on. Both default to off; TEST
 * turns them on in its overlay properties. Points, badges and coupon-backed non-cash rewards never read this.
 */
@Component
public class CashPayoutSwitch {
    public static final String FEATURE_CLOSED = "FEATURE_CLOSED";
    private final boolean moneyPayments;
    private final boolean payout;

    public CashPayoutSwitch(@Value("${chengyin.release.money-payments:false}") boolean moneyPayments,
                            @Value("${chengyin.release.payout:false}") boolean payout) {
        this.moneyPayments = moneyPayments;
        this.payout = payout;
    }

    public static CashPayoutSwitch off() { return new CashPayoutSwitch(false, false); }

    public boolean open() { return moneyPayments && payout; }
}
