package com.chengyinhub.business.service.support;

import java.math.BigDecimal;

/** CM = G_earned - 平台八项成本 - F - B；负值是合法结果。 */
public class EditionContributionMarginCalculator {

    public BigDecimal calculate(BigDecimal gEarned, BigDecimal platformCosts,
                                BigDecimal fixedCompensation, BigDecimal bonuses) {
        requireKnown("gEarned", gEarned);
        requireKnown("platformCosts", platformCosts);
        requireKnown("fixedCompensation", fixedCompensation);
        requireKnown("bonuses", bonuses);
        return gEarned.subtract(platformCosts).subtract(fixedCompensation).subtract(bonuses);
    }

    private void requireKnown(String name, BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("EDITION_CM_UNKNOWN:" + name);
        }
    }
}
