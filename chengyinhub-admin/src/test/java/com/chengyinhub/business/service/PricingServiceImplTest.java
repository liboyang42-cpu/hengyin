package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.PricingResult;
import com.chengyinhub.business.service.impl.PricingServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 定价引擎底价公式单测(报告§5.4)。compute() 为纯计算,不依赖 mapper/Spring。
 * P_min = C / (1 − platformRate − sumShareRate),C = leadPerHead + Σfixed + perk,2 位向上取整。
 */
class PricingServiceImplTest {

    private final PricingServiceImpl svc = new PricingServiceImpl();
    private final BigDecimal PLATFORM = new BigDecimal("0.10");

    private void assertPMin(String expected, PricingResult r) {
        assertEquals(0, new BigDecimal(expected).compareTo(r.getPriceMin()),
                "P_min 期望 " + expected + " 实际 " + r.getPriceMin());
    }

    @Test
    void allZero_pMinZero() {
        PricingResult r = svc.compute(null, null, null, null, null, PLATFORM);
        assertPMin("0.00", r);
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getCostC()));
    }

    @Test
    void fixedFeeOnly() {
        // C=20, denom=0.9, 20/0.9=22.222.. → ceil 22.23
        PricingResult r = svc.compute(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("20"), BigDecimal.ZERO, BigDecimal.ZERO, PLATFORM);
        assertPMin("22.23", r);
    }

    @Test
    void shareRateWithLeadCost() {
        // C=10, denom=1-0.1-0.3=0.6, 10/0.6=16.666.. → ceil 16.67
        PricingResult r = svc.compute(new BigDecimal("0.30"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10"), BigDecimal.ZERO, PLATFORM);
        assertPMin("16.67", r);
    }

    @Test
    void combinedCost() {
        // C=10+5=15, denom=1-0.1-0.2=0.7, 15/0.7=21.428.. → ceil 21.43
        PricingResult r = svc.compute(new BigDecimal("0.20"), BigDecimal.ZERO, new BigDecimal("5"), new BigDecimal("10"), BigDecimal.ZERO, PLATFORM);
        assertPMin("21.43", r);
        assertEquals(0, new BigDecimal("15").compareTo(r.getCostC()));
    }

    @Test
    void shareTooHigh_throws() {
        // platform 0.10 + share 0.95 → denom=-0.05 ≤0
        assertThrows(IllegalStateException.class,
                () -> svc.compute(new BigDecimal("0.95"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, PLATFORM));
    }

    @Test
    void shareExactly90WithPlatform10_throws() {
        // 0.10 + 0.90 = 1.00 → denom=0 ≤0
        assertThrows(IllegalStateException.class,
                () -> svc.compute(new BigDecimal("0.90"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, PLATFORM));
    }

    @Test
    void platformRateDefaultIs10Percent() {
        assertEquals(0, new BigDecimal("0.10").compareTo(svc.platformRate()));
    }
}
