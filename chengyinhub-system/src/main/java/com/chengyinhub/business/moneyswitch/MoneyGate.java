package com.chengyinhub.business.moneyswitch;

import com.chengyinhub.common.exception.BusinessRefusal;
import com.chengyinhub.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * W7 MERCHANT-3: the one server-side off switch for money writes. Every server money path asks this gate for its Q5
 * area <b>and</b> keeps its own legacy property (AND, defence in depth), so switching an area off on the admin page
 * (资金开关) stops that path on the server within one request, without a configuration change or a restart.
 *
 * <p>Fail closed: no service, a read error or anything other than exactly {@code true} for the area is closed. The
 * underlying {@link MoneySwitchService#snapshot()} already applies the server ceiling.
 */
@Component
public class MoneyGate {
    private static final Logger log = LoggerFactory.getLogger(MoneyGate.class);

    private final MoneySwitchService moneySwitch;

    public MoneyGate(MoneySwitchService moneySwitch) {
        this.moneySwitch = moneySwitch;
    }

    /** True only when the area's effective Q5 value is exactly on. Any problem reads closed. */
    public boolean isOpen(MoneyArea area) {
        if (area == null || moneySwitch == null) {
            return false;
        }
        try {
            Map<String, Boolean> snapshot = moneySwitch.snapshot();
            return snapshot != null && Boolean.TRUE.equals(snapshot.get(area.key()));
        } catch (RuntimeException unreadable) {
            log.error("money gate read failed for {}; reading closed", area.key(), unreadable);
            return false;
        }
    }

    /** Refuses with {@code COMMON.FEATURE_OFF} unless the area is open. */
    public void requireOpen(MoneyArea area) {
        if (!isOpen(area)) {
            throw new BusinessRefusal(ErrorCode.FEATURE_OFF);
        }
    }
}
