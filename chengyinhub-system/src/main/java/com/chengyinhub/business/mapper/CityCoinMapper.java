package com.chengyinhub.business.mapper;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/**
 * R7 city coin: non-cashable territory currency. This mapper only credits and reads. It has, and must keep having,
 * no statement that lowers a balance, deletes a ledger row or touches points, cash, coupons or orders
 * ({@code CityCoinNoConversionGuardTest} enforces this).
 */
public interface CityCoinMapper {

    /** Creates the account row with balance 0 when missing. */
    int ensureAccount(@Param("memberId") Long memberId, @Param("now") Date now);

    /** Balance under a row lock (serialises credits of one member). */
    Long selectBalanceForUpdate(@Param("memberId") Long memberId);

    Long selectBalance(@Param("memberId") Long memberId);

    /** Ledger row by its unique reference. Keys: id, memberId, amount, balanceAfter, reason, refKey, placeId, createTime. */
    Map<String, Object> selectByRef(@Param("refKey") String refKey);

    /** City coin this member was credited for {@code reason} since {@code since}. */
    long sumCreditedSince(@Param("memberId") Long memberId, @Param("reason") String reason, @Param("since") Date since);

    int insertCredit(@Param("memberId") Long memberId, @Param("amount") int amount, @Param("balanceAfter") long balanceAfter,
                     @Param("reason") String reason, @Param("refKey") String refKey, @Param("placeId") String placeId,
                     @Param("now") Date now);

    /** Adds a positive amount to the balance. */
    int creditBalance(@Param("memberId") Long memberId, @Param("amount") int amount, @Param("now") Date now);

    /** Newest first. Keys: id, amount, balanceAfter, reason, placeId, createTime. */
    List<Map<String, Object>> selectHistory(@Param("memberId") Long memberId, @Param("limit") int limit);

    long sumCreditedBetween(@Param("from") Date from, @Param("to") Date to);

    long sumOutstanding();
}
