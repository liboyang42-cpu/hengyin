package com.chengyinhub.business.service.support;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** Approved original-head cents allocation; exact rational remainders, stable registration-ID ties. */
public final class CoopOriginalAllocation {
    private CoopOriginalAllocation() { }
    public static Map<Long,BigDecimal> allocate(BigDecimal amount, Map<Long,BigDecimal> cash) {
        BigInteger target = amount.movePointRight(2).toBigIntegerExact();
        if (target.signum() < 0 || cash == null || cash.isEmpty()) throw new IllegalArgumentException("Original allocation required");
        Map<Long,BigInteger> weights = new TreeMap<>(); BigInteger total = BigInteger.ZERO;
        for (var entry : cash.entrySet()) {
            BigInteger value = entry.getValue().movePointRight(2).toBigIntegerExact();
            if (entry.getKey() == null || entry.getKey() <= 0 || value.signum() < 0) throw new IllegalArgumentException("Original cash weight invalid");
            weights.put(entry.getKey(),value); total=total.add(value);
        }
        if (total.signum() <= 0) throw new IllegalArgumentException("Original positive cash required");
        Map<Long,BigInteger> cents = new TreeMap<>(), remainders = new HashMap<>(); BigInteger assigned = BigInteger.ZERO;
        for (var entry : weights.entrySet()) {
            BigInteger[] division = target.multiply(entry.getValue()).divideAndRemainder(total);
            cents.put(entry.getKey(),division[0]); remainders.put(entry.getKey(),division[1]); assigned=assigned.add(division[0]);
        }
        List<Long> order = new ArrayList<>(weights.keySet());
        order.sort(Comparator.<Long,BigInteger>comparing(remainders::get).reversed().thenComparingLong(Long::longValue));
        int extra=target.subtract(assigned).intValueExact();
        for(int i=0;i<extra;i++) cents.compute(order.get(i),(id,value)->value.add(BigInteger.ONE));
        Map<Long,BigDecimal> result=new TreeMap<>(); cents.forEach((id,value)->result.put(id,new BigDecimal(value,2)));
        return result;
    }
}
