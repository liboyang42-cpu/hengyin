package com.chengyinhub.business.moneypayout;

import com.chengyinhub.business.moneyswitch.MoneyArea;
import com.chengyinhub.business.moneyswitch.MoneySwitchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The money switch, read for the payout package. Any error reads closed. The WeChat transfer has its own
 * long-standing property ({@code withdrawal.fast-withdraw.enabled}); a review can send money only when both are on.
 */
@Component
public class PayoutMoneyGate {
    private final MoneySwitchService moneySwitch;
    private final boolean transferEnabled;

    public PayoutMoneyGate(MoneySwitchService moneySwitch,
                           @Value("${withdrawal.fast-withdraw.enabled:false}") boolean transferEnabled) {
        this.moneySwitch = moneySwitch;
        this.transferEnabled = transferEnabled;
    }

    public boolean isOpen(MoneyArea area) {
        try {
            return Boolean.TRUE.equals(moneySwitch.snapshot().get(area.key()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    public boolean transferEnabled() {
        return transferEnabled;
    }
}
