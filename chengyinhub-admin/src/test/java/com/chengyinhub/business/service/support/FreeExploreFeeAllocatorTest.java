package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** D-29:场次现金平台费与逐单分账行到分一致(余数法),换费率与退款冲回口径。 */
class FreeExploreFeeAllocatorTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static FreeExploreFeeAllocator.Item item(long id, String gross, String rate) {
        return new FreeExploreFeeAllocator.Item(id, bd(gross), bd(rate));
    }

    @Test
    void threeTicketsOf950CashEqualsLedger() {
        List<FreeExploreFeeAllocator.Item> items = Arrays.asList(
                item(1, "9.50", "0.03"), item(2, "9.50", "0.03"), item(3, "9.50", "0.03"));
        Map<Long, BigDecimal> fees = FreeExploreFeeAllocator.allocate(items);
        // 整场 28.50×3% = 0.855 → 0.86;旧逐单 HALF_UP 为 0.29×3 = 0.87
        assertEquals(bd("0.86"), FreeExploreFeeAllocator.total(fees));
        assertEquals(bd("0.86"), bd("28.50").multiply(bd("0.03")).setScale(2, java.math.RoundingMode.HALF_UP));
        for (BigDecimal fee : fees.values()) {
            assertEquals(2, fee.scale());
        }
    }

    @Test
    void ledgerRowsSumToSessionFeeForManyTickets() {
        for (int n = 1; n <= 40; n++) {
            List<FreeExploreFeeAllocator.Item> items = new java.util.ArrayList<>();
            BigDecimal gross = BigDecimal.ZERO;
            for (int i = 0; i < n; i++) {
                BigDecimal g = bd("9.50").add(bd("0.07").multiply(BigDecimal.valueOf(i % 5)));
                items.add(new FreeExploreFeeAllocator.Item((long) i, g, bd("0.03")));
                gross = gross.add(g);
            }
            BigDecimal expected = gross.multiply(bd("0.03")).setScale(2, java.math.RoundingMode.HALF_UP);
            assertEquals(expected, FreeExploreFeeAllocator.total(FreeExploreFeeAllocator.allocate(items)), "n=" + n);
        }
    }

    @Test
    void frozenRatesDifferPerRegistrationUseEachOwnRate() {
        // 费率调整后新单 5%、老单冻结 3%:各用各的冻结费率,合计到分
        List<FreeExploreFeeAllocator.Item> items = Arrays.asList(item(1, "10.00", "0.03"), item(2, "10.00", "0.05"));
        Map<Long, BigDecimal> fees = FreeExploreFeeAllocator.allocate(items);
        assertEquals(bd("0.30"), fees.get(1L));
        assertEquals(bd("0.50"), fees.get(2L));
    }

    @Test
    void refundReversalOfEveryTicketSumsBackToSessionFee() {
        List<FreeExploreFeeAllocator.Item> items = Arrays.asList(
                item(1, "9.50", "0.03"), item(2, "9.50", "0.03"), item(3, "9.50", "0.03"));
        Map<Long, BigDecimal> fees = FreeExploreFeeAllocator.allocate(items);
        BigDecimal reversed = BigDecimal.ZERO;
        for (BigDecimal fee : fees.values()) {
            reversed = reversed.add(fee); // 全额退款按本单分账行整行冲回
        }
        assertEquals(FreeExploreFeeAllocator.total(fees), reversed);
    }

    @Test
    void emptyAndNonPositiveRateYieldZero() {
        assertEquals(0, FreeExploreFeeAllocator.allocate(Collections.<FreeExploreFeeAllocator.Item>emptyList()).size());
        Map<Long, BigDecimal> fees = FreeExploreFeeAllocator.allocate(
                Collections.singletonList(new FreeExploreFeeAllocator.Item(1L, bd("10.00"), null)));
        assertEquals(bd("0.00"), fees.get(1L));
    }
}
