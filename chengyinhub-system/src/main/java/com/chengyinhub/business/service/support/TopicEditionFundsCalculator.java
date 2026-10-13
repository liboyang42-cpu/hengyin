package com.chengyinhub.business.service.support;

import java.math.BigDecimal;

/** 探店日期级六量的唯一算术口径；只计算，不读写数据库。 */
public class TopicEditionFundsCalculator {

    public Calculation calculate(BigDecimal gCash, BigDecimal uIn, BigDecimal uOut,
                                 BigDecimal uPending, BigDecimal rRefund) {
        requireKnown("gCash", gCash);
        requireKnown("uIn", uIn);
        requireKnown("uOut", uOut);
        requireKnown("uPending", uPending);
        requireKnown("rRefund", rRefund);
        requireNonNegative("gCash", gCash);
        requireNonNegative("uIn", uIn);
        requireNonNegative("uOut", uOut);
        requireNonNegative("uPending", uPending);
        requireNonNegative("rRefund", rRefund);
        if (uPending.compareTo(uOut) > 0) {
            throw new IllegalArgumentException("EDITION_FUNDS_UNBALANCED:uPending>uOut");
        }
        BigDecimal gEarned = gCash.add(uIn).subtract(uOut).subtract(rRefund);
        if (gEarned.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("EDITION_FUNDS_UNBALANCED:gEarned<0");
        }
        return new Calculation(gCash, uIn, gEarned, uOut, uPending, rRefund);
    }

    public void assertBalanced(Calculation value) {
        if (value == null) {
            throw new IllegalArgumentException("EDITION_FUNDS_UNKNOWN:calculation");
        }
        requireKnown("gCash", value.gCash);
        requireKnown("uIn", value.uIn);
        requireKnown("gEarned", value.gEarned);
        requireKnown("uOut", value.uOut);
        requireKnown("uPending", value.uPending);
        requireKnown("rRefund", value.rRefund);
        BigDecimal left = value.gCash.add(value.uIn);
        BigDecimal right = value.gEarned.add(value.uOut).add(value.rRefund);
        if (left.compareTo(right) != 0) {
            throw new IllegalStateException("EDITION_FUNDS_UNBALANCED:gCash=" + plain(value.gCash)
                    + ",uIn=" + plain(value.uIn)
                    + ",gEarned=" + plain(value.gEarned)
                    + ",uOut=" + plain(value.uOut)
                    + ",rRefund=" + plain(value.rRefund));
        }
    }

    private void requireKnown(String name, BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("EDITION_FUNDS_UNKNOWN:" + name);
        }
    }

    private void requireNonNegative(String name, BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("EDITION_FUNDS_INVALID:" + name + "<0");
        }
    }

    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    public static final class Calculation {
        private final BigDecimal gCash;
        private final BigDecimal uIn;
        private final BigDecimal gEarned;
        private final BigDecimal uOut;
        private final BigDecimal uPending;
        private final BigDecimal rRefund;

        public Calculation(BigDecimal gCash, BigDecimal uIn, BigDecimal gEarned,
                           BigDecimal uOut, BigDecimal uPending, BigDecimal rRefund) {
            this.gCash = gCash;
            this.uIn = uIn;
            this.gEarned = gEarned;
            this.uOut = uOut;
            this.uPending = uPending;
            this.rRefund = rRefund;
        }

        public BigDecimal getGCash() { return gCash; }
        public BigDecimal getUIn() { return uIn; }
        public BigDecimal getGEarned() { return gEarned; }
        public BigDecimal getUOut() { return uOut; }
        public BigDecimal getUPending() { return uPending; }
        public BigDecimal getRRefund() { return rRefund; }
        public BigDecimal getUOutOutsideRefundWindow() { return uOut.subtract(uPending); }
    }
}
