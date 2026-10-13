package com.chengyinhub.business.service.impl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 乙-2 发起人保底率:5% + 2%×min(有核销商家数, 5),封顶 15%。
 */
class PricingGuaranteeRateTest {

    private final PricingServiceImpl svc = new PricingServiceImpl();

    private void assertRate(String expected, int n) {
        assertEquals(0, new BigDecimal(expected).compareTo(svc.initiatorGuaranteeRate(n)), "n=" + n);
    }

    @Test
    void guaranteeRate_scalesAndCaps() {
        assertRate("0.05", 0);   // 无商家:仅 5% 保底
        assertRate("0.07", 1);   // +2%
        assertRate("0.11", 3);   // 5%+6%
        assertRate("0.15", 5);   // 封顶 5 家 = 5%+10%
        assertRate("0.15", 6);   // 超过 5 家仍封顶 15%
        assertRate("0.15", 99);  // 极端也封顶
        assertRate("0.05", -1);  // 负数兜底为 0 家
    }
}
