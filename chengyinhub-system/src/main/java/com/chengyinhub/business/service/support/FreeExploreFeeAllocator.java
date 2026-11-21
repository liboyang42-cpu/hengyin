package com.chengyinhub.business.service.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自由探索场次平台费分摊(余数法)。
 * 场次现金与逐单分账行必须到分一致:先按各单冻结费率精确求和,整场四舍五入得到场次平台费,
 * 再把每单费用向下取到分,剩余的分按小数部分从大到小(并列按序号)补给各单,逐单之和恒等于场次平台费。
 * 纯函数,无 IO。
 */
public final class FreeExploreFeeAllocator {

    private FreeExploreFeeAllocator() {
    }

    /** 一张可结算的票:id、实付金额、下单冻结的平台费率(小数,0.03=3%)。 */
    public static final class Item {
        public final Long id;
        public final BigDecimal gross;
        public final BigDecimal rate;

        public Item(Long id, BigDecimal gross, BigDecimal rate) {
            this.id = id;
            this.gross = gross;
            this.rate = rate;
        }
    }

    /** 返回 id → 该单平台费(保持入参顺序)。费率缺失或非正按 0。 */
    public static Map<Long, BigDecimal> allocate(List<Item> items) {
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        if (items == null || items.isEmpty()) return result;
        BigDecimal exactTotal = BigDecimal.ZERO;
        List<BigDecimal> exact = new ArrayList<>();
        for (Item item : items) {
            BigDecimal e = item.rate == null || item.rate.signum() <= 0 || item.gross == null
                    ? BigDecimal.ZERO : item.gross.multiply(item.rate);
            exact.add(e);
            exactTotal = exactTotal.add(e);
        }
        BigDecimal target = exactTotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal[] floors = new BigDecimal[items.size()];
        BigDecimal[] fractions = new BigDecimal[items.size()];
        BigDecimal floorSum = BigDecimal.ZERO;
        for (int i = 0; i < items.size(); i++) {
            floors[i] = exact.get(i).setScale(2, RoundingMode.DOWN);
            fractions[i] = exact.get(i).subtract(floors[i]);
            floorSum = floorSum.add(floors[i]);
        }
        int extra = target.subtract(floorSum).movePointRight(2).intValueExact();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) order.add(i);
        order.sort((a, b) -> {
            int c = fractions[b].compareTo(fractions[a]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        BigDecimal cent = new BigDecimal("0.01");
        for (int k = 0; k < extra && k < order.size(); k++) {
            floors[order.get(k)] = floors[order.get(k)].add(cent);
        }
        for (int i = 0; i < items.size(); i++) result.put(items.get(i).id, floors[i]);
        return result;
    }

    /** 场次平台费合计。 */
    public static BigDecimal total(Map<Long, BigDecimal> fees) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal fee : fees.values()) sum = sum.add(fee);
        return sum;
    }
}
