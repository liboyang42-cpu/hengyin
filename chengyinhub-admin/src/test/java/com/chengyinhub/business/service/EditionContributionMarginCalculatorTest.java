package com.chengyinhub.business.service;

import com.chengyinhub.business.service.support.EditionContributionMarginCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EditionContributionMarginCalculatorTest {

    private final EditionContributionMarginCalculator calculator = new EditionContributionMarginCalculator();

    @Test
    void preservesNegativeContributionMargin() {
        BigDecimal cm = calculator.calculate(
                new BigDecimal("1380"), new BigDecimal("0"), new BigDecimal("2000"), new BigDecimal("0"));

        assertEquals(new BigDecimal("-620"), cm);
    }

    @Test
    void subtractsPlatformCostsFixedCompensationAndBonuses() {
        BigDecimal cm = calculator.calculate(
                new BigDecimal("5000"), new BigDecimal("900"), new BigDecimal("2000"), new BigDecimal("600"));

        assertEquals(new BigDecimal("1500"), cm);
    }

    @Test
    void unknownInputFailsClosed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(new BigDecimal("5000"), null,
                        new BigDecimal("2000"), new BigDecimal("600")));

        assertEquals("EDITION_CM_UNKNOWN:platformCosts", error.getMessage());
    }
}
