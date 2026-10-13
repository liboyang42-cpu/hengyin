package com.chengyinhub.business.service.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** N_BE 只读计算；本类不依赖也不调用任何开售状态机。 */
public class EditionBreakEvenCalculator {

    public Result calculate(BigDecimal fixedCost, BigDecimal ticketPrice, BigDecimal variableCostPerTicket) {
        if (fixedCost == null) {
            return Result.unavailable("FIXED_COST_UNKNOWN");
        }
        if (ticketPrice == null) {
            return Result.unavailable("TICKET_PRICE_UNKNOWN");
        }
        if (variableCostPerTicket == null) {
            return Result.unavailable("VARIABLE_COST_UNKNOWN");
        }
        if (fixedCost.compareTo(BigDecimal.ZERO) < 0
                || ticketPrice.compareTo(BigDecimal.ZERO) < 0
                || variableCostPerTicket.compareTo(BigDecimal.ZERO) < 0) {
            return Result.unavailable("NEGATIVE_INPUT");
        }
        BigDecimal contribution = ticketPrice.subtract(variableCostPerTicket);
        if (contribution.compareTo(BigDecimal.ZERO) <= 0) {
            return Result.unavailable("NON_POSITIVE_UNIT_CONTRIBUTION");
        }
        long required = fixedCost.divide(contribution, 0, RoundingMode.CEILING).longValueExact();
        return Result.available(required);
    }

    public static final class Result {
        private final boolean available;
        private final Long requiredTickets;
        private final String reason;

        private Result(boolean available, Long requiredTickets, String reason) {
            this.available = available;
            this.requiredTickets = requiredTickets;
            this.reason = reason;
        }

        public static Result available(long requiredTickets) {
            return new Result(true, requiredTickets, null);
        }

        public static Result unavailable(String reason) {
            return new Result(false, null, reason);
        }

        public boolean isAvailable() { return available; }
        public Long getRequiredTickets() { return requiredTickets; }
        public String getReason() { return reason; }
    }
}
