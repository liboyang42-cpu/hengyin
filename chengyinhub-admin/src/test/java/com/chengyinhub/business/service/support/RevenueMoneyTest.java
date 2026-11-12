package com.chengyinhub.business.service.support;

import com.chengyinhub.business.exception.MarketingRevenueException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 精度契约：分笔部分退款不得因逐笔取整丢分或超付。
 *
 * <p>核心反例（root 明确要求）：实付 0.03、三方各 0.01，分三笔各退 0.01，必须全部冲完且无残差。
 * 旧实现逐笔 {@code HALF_UP} 会在这类用例上漂移，所以这里钉住整数分 + 累计分摊的行为。
 */
class RevenueMoneyTest {

    @Test
    void threeOneCentLinesAreFullyReversedByThreeOneCentRefunds() {
        long[] originLines = {1L, 1L, 1L};
        long paidFen = 3L;

        long[] afterFirst = RevenueMoney.allocate(1L, originLines);
        long[] afterSecond = RevenueMoney.allocate(2L, originLines);
        long[] afterThird = RevenueMoney.allocate(3L, originLines);

        assertArrayEquals(new long[]{1L, 0L, 0L}, afterFirst);
        assertEquals(1L, sum(afterFirst));
        assertEquals(2L, sum(afterSecond));
        assertArrayEquals(new long[]{1L, 1L, 1L}, afterThird);
        assertEquals(paidFen, sum(afterThird), "三笔退完必须恰好冲满，无残差");
    }

    @Test
    void everyCumulativeStepStaysWithinEachLineAndSumsExactly() {
        long[] originLines = {3333L, 3334L, 3333L}; // 合计 10000 分
        long paidFen = sum(originLines);

        for (long cumulative = 0; cumulative <= paidFen; cumulative += 7L) {
            long[] target = RevenueMoney.allocate(cumulative, originLines);
            assertEquals(cumulative, sum(target), "累计 " + cumulative + " 必须精确分摊");
            for (int i = 0; i < originLines.length; i++) {
                assertEquals(true, target[i] <= originLines[i],
                        "第 " + i + " 行不得超过其原分录");
            }
        }
    }

    @Test
    void perEventDeltasNeverLosACentAcrossManySmallRefunds() {
        long[] originLines = {4000L, 3500L, 2500L};
        long paidFen = sum(originLines);
        long[] reversed = new long[originLines.length];

        // 模拟 97 笔小额退款，每笔都按"累计目标 − 已冲销"取增量。
        long cumulative = 0;
        for (int event = 0; event < 98; event++) {
            long step = 103L;
            if (cumulative + step > paidFen) {
                step = paidFen - cumulative;
            }
            cumulative += step;
            long[] target = RevenueMoney.allocate(cumulative, originLines);
            for (int i = 0; i < originLines.length; i++) {
                long delta = target[i] - reversed[i];
                assertEquals(true, delta >= 0, "增量不得为负（否则等于重复冲销）");
                reversed[i] = target[i];
            }
            assertEquals(cumulative, sum(reversed));
        }
        long[] finalTarget = RevenueMoney.allocate(paidFen, originLines);
        assertArrayEquals(finalTarget, reversed, "全额退完后必须与原分录完全相抵");
        assertEquals(paidFen, sum(reversed));
    }

    @Test
    void allocationIsDeterministicForReplay() {
        long[] originLines = {1L, 2L, 3L, 4L};
        long[] first = RevenueMoney.allocate(5L, originLines);
        long[] second = RevenueMoney.allocate(5L, originLines);
        assertArrayEquals(first, second, "同一输入必须得到同一结果，否则重放会漂移");
        assertEquals(5L, sum(first));
    }

    @Test
    void totalBeyondLineSumIsRejectedInsteadOfOverpaying() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RevenueMoney.allocate(11L, new long[]{5L, 5L}));
        assertEquals(true, error.getMessage().contains("outside line range"));
    }

    @Test
    void strictFenConversionRejectsFractionalCents() {
        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> RevenueMoney.toFen(new BigDecimal("1.001"), "X", "金额"));
        assertEquals("AMOUNT_FRACTIONAL_CENT", error.getReasonCode());
    }

    @Test
    void strictFenConversionRejectsNegative() {
        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> RevenueMoney.toFen(new BigDecimal("-1.00"), "X", "金额"));
        assertEquals("X", error.getReasonCode());
    }

    @Test
    void fenRoundTripsToExactAmount() {
        assertEquals(new BigDecimal("0.01"), RevenueMoney.toAmount(1L));
        assertEquals(new BigDecimal("12.99"), RevenueMoney.toAmount(1299L));
        assertEquals(new BigDecimal("0.00"), RevenueMoney.toAmount(0L));
    }

    private static long sum(long[] values) {
        long total = 0;
        for (long value : values) {
            total += value;
        }
        return total;
    }
}
