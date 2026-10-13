package com.chengyinhub.business.service;

import com.chengyinhub.business.service.support.EditionBreakEvenCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditionBreakEvenCalculatorTest {

    private final EditionBreakEvenCalculator calculator = new EditionBreakEvenCalculator();

    @Test
    void roundsRequiredTicketsUp() {
        EditionBreakEvenCalculator.Result result = calculator.calculate(
                new BigDecimal("2000"), new BigDecimal("69"), new BigDecimal("9"));

        assertTrue(result.isAvailable());
        assertEquals(34L, result.getRequiredTickets());
    }

    @Test
    void missingInputIsUnavailableRatherThanZero() {
        EditionBreakEvenCalculator.Result result = calculator.calculate(
                null, new BigDecimal("69"), new BigDecimal("9"));

        assertFalse(result.isAvailable());
        assertEquals("FIXED_COST_UNKNOWN", result.getReason());
    }

    @Test
    void nonPositiveContributionIsUnavailableRatherThanBlockingSale() {
        EditionBreakEvenCalculator.Result result = calculator.calculate(
                new BigDecimal("2000"), new BigDecimal("69"), new BigDecimal("69"));

        assertFalse(result.isAvailable());
        assertEquals("NON_POSITIVE_UNIT_CONTRIBUTION", result.getReason());
    }

    @Test
    void zeroFixedCostNeedsZeroTickets() {
        EditionBreakEvenCalculator.Result result = calculator.calculate(
                BigDecimal.ZERO, new BigDecimal("69"), new BigDecimal("9"));

        assertTrue(result.isAvailable());
        assertEquals(0L, result.getRequiredTickets());
    }
}
