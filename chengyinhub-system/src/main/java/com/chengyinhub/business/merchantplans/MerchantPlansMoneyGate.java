package com.chengyinhub.business.merchantplans;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.moneyswitch.MoneyArea;
import com.chengyinhub.business.moneyswitch.MoneySwitchService;

/**
 * S3a: every charge in this package (plan purchase and renewal, visit charges, invoices) runs only while the money
 * switch area {@code merchantMoney} is effectively on (server ceiling AND stored flag). Any error reads closed.
 */
@Component
public class MerchantPlansMoneyGate {
    private static final Logger log = LoggerFactory.getLogger(MerchantPlansMoneyGate.class);
    public static final String CLOSED_CODE = "PLAN_SALES_CLOSED";
    public static final String CLOSED_MESSAGE = "商家套餐即将开放";

    private final MoneySwitchService moneySwitch;

    public MerchantPlansMoneyGate(MoneySwitchService moneySwitch) {
        this.moneySwitch = moneySwitch;
    }

    public boolean open() {
        try {
            return Boolean.TRUE.equals(moneySwitch.snapshot().get(MoneyArea.MERCHANT_MONEY.key()));
        } catch (RuntimeException e) {
            log.error("money switch read failed; merchant plan charges stay closed", e);
            return false;
        }
    }

    /** @throws IllegalStateException with {@link #CLOSED_CODE} while the switch is closed */
    public void requireOpen() {
        if (!open()) throw new IllegalStateException(CLOSED_CODE);
    }
}
