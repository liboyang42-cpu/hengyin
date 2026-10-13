package com.chengyinhub.business.service;

import com.chengyinhub.business.service.support.TopicEditionFundsCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TopicEditionFundsCalculatorTest {

    private final TopicEditionFundsCalculator calculator = new TopicEditionFundsCalculator();

    @Test
    void derivesEarnedFromConservationWithoutTreatingPaidAsEarned() {
        TopicEditionFundsCalculator.Calculation result = calculator.calculate(
                money("300"), money("0"), money("100"), money("100"), money("60"));

        assertEquals(money("140"), result.getGEarned());
        assertEquals(money("100"), result.getUPending());
        assertEquals(money("0"), result.getUOutOutsideRefundWindow());
        calculator.assertBalanced(result);
    }

    @Test
    void refundDoesNotDisappearFromOriginalCash() {
        TopicEditionFundsCalculator.Calculation result = calculator.calculate(
                money("69"), money("0"), money("0"), money("0"), money("69"));

        assertEquals(money("69"), result.getGCash());
        assertEquals(money("0"), result.getGEarned());
    }

    @Test
    void rejectsUnknownMoneyInsteadOfTurningNullIntoZero() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(money("69"), null, money("0"), money("0"), money("0")));

        assertEquals("EDITION_FUNDS_UNKNOWN:uIn", error.getMessage());
    }

    @Test
    void rejectsPendingThatIsNotAContainedPartOfUOut() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(money("69"), money("0"), money("20"), money("30"), money("0")));

        assertEquals("EDITION_FUNDS_UNBALANCED:uPending>uOut", error.getMessage());
    }

    @Test
    void rejectsFactsThatWouldDeriveNegativeEarnedRevenue() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(money("69"), money("0"), money("50"), money("30"), money("30")));

        assertEquals("EDITION_FUNDS_UNBALANCED:gEarned<0", error.getMessage());
    }

    @Test
    void explicitBalanceAssertionTurnsRedWhenAnyQuantityDrifts() {
        TopicEditionFundsCalculator.Calculation invalid = new TopicEditionFundsCalculator.Calculation(
                money("100"), money("0"), money("70"), money("30"), money("30"), money("10"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> calculator.assertBalanced(invalid));
        assertEquals("EDITION_FUNDS_UNBALANCED:gCash=100,uIn=0,gEarned=70,uOut=30,rRefund=10",
                error.getMessage());
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
