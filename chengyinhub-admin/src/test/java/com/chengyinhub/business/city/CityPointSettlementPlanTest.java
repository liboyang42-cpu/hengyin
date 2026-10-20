package com.chengyinhub.business.city;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.chengyinhub.business.city.CityContracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Arithmetic and authorization-dependency contracts; never a DB transaction test. */
class CityPointSettlementPlanTest {
    private final Instant expires = Instant.parse("2026-10-03T00:00:00Z");
    private final Rules rules = new Rules("rules", String.join("", Collections.nCopies(64, "a")),
            "explicit-allocation", 10, 20, 1, 0);

    @Test void ownEqualRewardCanNetToZeroWithoutAdvancePoints() {
        Offer offer = offer(5, amounts(1, 5));
        CityPointSettlementPlan plan = CityPointSettlementPlan.prepare(offer, reservation(offer), balances(1, 0));
        assertEquals(0, plan.entries.get(0).netChange);
        assertEquals(0, plan.entries.get(0).proposedAfter);
        assertEquals(5, plan.entries.get(0).rewardCredit);
        assertEquals(5, plan.entries.get(0).feeDebit);
        assertEquals(5, plan.grossBudgetConsumption);
    }

    @Test void chargeUsesOnlyExplicitPayerEvenWithRichTeammateSnapshot() {
        Offer offer = offer(5, amounts(1, 2));
        CityPointSettlementPlan plan = CityPointSettlementPlan.prepare(offer, reservation(offer), balances(1, 3, 2, 1000));
        assertEquals(2, plan.entries.size());
        assertEquals(1, plan.entries.get(0).memberId);
        assertEquals(0, plan.entries.get(0).proposedAfter);
    }

    @Test void missingOrInactiveOrUnderfundedBudgetFailsClosed() {
        Offer offer = offer(5, amounts(1, 7));
        reason("REWARD_BUDGET_NOT_RESERVED", () -> CityPointSettlementPlan.prepare(offer, null, balances(1, 0)));
        reason("REWARD_BUDGET_NOT_RESERVED", () -> CityPointSettlementPlan.prepare(offer,
                new RewardReservation("b", offer, 6, true), balances(1, 0)));
        reason("REWARD_BUDGET_NOT_RESERVED", () -> CityPointSettlementPlan.prepare(offer,
                new RewardReservation("b", offer, 7, false), balances(1, 0)));
    }

    @Test void budgetCannotBeReusedOnDifferentFrozenOffer() {
        Offer first = offer(5, amounts(1, 7));
        Offer repriced = offer(6, amounts(1, 7));
        reason("REWARD_BUDGET_NOT_RESERVED", () -> CityPointSettlementPlan.prepare(repriced,
                reservation(first), balances(1, 100)));
    }

    @Test void missingAccountSnapshotAndNegativeBalanceAreRejected() {
        Offer offer = offer(5, amounts(1, 7, 2, 2));
        reason("AUTHORITATIVE_BALANCE_REQUIRED", () -> CityPointSettlementPlan.prepare(offer,
                reservation(offer), balances(1, 0)));
        reason("AUTHORITATIVE_BALANCE_REQUIRED", () -> CityPointSettlementPlan.prepare(offer,
                reservation(offer), balances(1, 0, 2, -1)));
    }

    @Test void arithmeticAndExistingIntStorageNeverWrap() {
        Offer offer = offer(0, amounts(1, 7));
        reason("AUTHORITATIVE_BALANCE_REQUIRED", () -> CityPointSettlementPlan.prepare(offer,
                reservation(offer), amounts(1, Long.MAX_VALUE)));
        reason("POINT_STORAGE_LIMIT_EXCEEDED", () -> CityPointSettlementPlan.prepare(offer,
                reservation(offer), amounts(1, Integer.MAX_VALUE)));
        assertThrows(ArithmeticException.class, () -> offer(0, amounts(1, Long.MAX_VALUE, 2, 1)));
    }

    @Test void policyLimitsAndAbsentAllocationAreRejectedRatherThanDefaulted() {
        reason("ECONOMIC_LIMIT_EXCEEDED", () -> offer(11, amounts(1, 7)));
        reason("ECONOMIC_LIMIT_EXCEEDED", () -> offer(1, amounts(1, 21)));
        reason("INVALID_ID", () -> new Rules("r", rules.contentHash, null, 0, 0, 1, 0));
        reason("ACTION_BUDGET_NOT_APPROVED", () -> new Rules("r", rules.contentHash, "a", 0, 0, 0, 0));
    }

    @Test void malformedOrUnboundedIdentifiersCannotAliasReceiptKeys() {
        reason("INVALID_ID", () -> new Participation("board\uD800", "team", 1, Collections.singleton(1L), true));
        reason("INVALID_ID", () -> new Participation(" board", "team", 1, Collections.singleton(1L), true));
        assertNotEquals(CityChecks.key("ab", "c"), CityChecks.key("a", "bc"));
    }

    @Test void orderIndependentAllocationHasStableFingerprintAndImmutablePlan() {
        Offer first = offer(5, amounts(9, 2, 1, 7));
        Offer second = offer(5, amounts(1, 7, 9, 2));
        assertEquals(first.fingerprint, second.fingerprint);
        CityPointSettlementPlan plan = CityPointSettlementPlan.prepare(first, reservation(first), amounts(1, 0, 9, 0));
        assertThrows(UnsupportedOperationException.class, () -> plan.entries.clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.memberLockOrder.clear());
    }

    @Test void freshBoardOrChangedTeamCannotResetIndividualRewardScopeWithinSameBoard() {
        Offer first = offer(5, amounts(1, 7));
        Offer newTeam = new Offer("offer2", "board", "property", "owner", 1, "new-team", 1,
                "service", "scope2", "task2", "reward", rules, "available", 1, 5, amounts(1, 7), feeAllocation(5), expires);
        assertNotEquals(first.visitKey(), newTeam.visitKey());
        assertEquals(first.rewardKey(1), newTeam.rewardKey(1));
    }

    @Test void positiveFeeWithoutApprovedRecipientsFailsClosed() {
        reason("FEE_ALLOCATION_NOT_APPROVED", () -> new Offer("offer", "board", "property", "owner", 1,
                "team", 1, "service", "scope", "task", "reward", rules, "available", 1, 5,
                amounts(1, 7), null, expires));
    }

    @Test void feesAreConservedSeparatelyAndNeverConsumeRewardBudget() {
        Offer feeOnly = offer(5, Collections.<Long, Long>emptyMap());
        CityPointSettlementPlan plan = CityPointSettlementPlan.prepare(feeOnly, null, balances(1, 5));
        assertEquals(0, plan.grossBudgetConsumption);
        assertEquals(0, plan.entries.stream().mapToLong(entry -> entry.netChange).sum());
        assertEquals(5, plan.entries.stream().mapToLong(entry -> entry.feeCredit).sum());
        assertEquals(5, plan.entries.stream().mapToLong(entry -> entry.feeDebit).sum());
        assertEquals(5, plan.entries.get(1).proposedAfter);
    }

    @Test void feeAllocationMustMatchFeeAndAuthorizedOperatingMembers() {
        Participation owner = new Participation("board", "owner", 2, Collections.singleton(9L), true);
        reason("FEE_BENEFICIARY_NOT_AUTHORIZED", () -> new FeeAllocation("approval", rules.allocationPolicyId,
                owner, amounts(10, 5)));
        FeeAllocation tooSmall = new FeeAllocation("approval", rules.allocationPolicyId, owner, amounts(9, 4));
        reason("FEE_ALLOCATION_NOT_CONSERVING", () -> new Offer("offer", "board", "property", "owner", 1,
                "team", 1, "service", "scope", "task", "reward", rules, "available", 1, 5,
                amounts(1, 7), tooSmall, expires));
        FeeAllocation wrongPolicy = new FeeAllocation("approval", "unapproved-policy", owner, amounts(9, 5));
        reason("FEE_ALLOCATION_NOT_APPROVED", () -> new Offer("offer", "board", "property", "owner", 1,
                "team", 1, "service", "scope", "task", "reward", rules, "available", 1, 5,
                amounts(1, 7), wrongPolicy, expires));
    }

    @Test void payerMayBeApprovedFeeBeneficiaryWithoutDoubleCountingBudget() {
        Participation owner = new Participation("board", "owner", 2,
                new java.util.HashSet<Long>(java.util.Arrays.asList(1L, 9L)), true);
        FeeAllocation distribution = new FeeAllocation("approval", rules.allocationPolicyId, owner, amounts(1, 2, 9, 3));
        Offer offer = new Offer("offer", "board", "property", "owner", 1,
                "team", 1, "service", "scope", "task", "reward", rules, "available", 1, 5,
                amounts(1, 5), distribution, expires);
        CityPointSettlementPlan plan = CityPointSettlementPlan.prepare(offer, reservation(offer), balances(1, 0));
        assertEquals(2, plan.entries.get(0).proposedAfter);
        assertEquals(3, plan.entries.get(1).proposedAfter);
        assertEquals(5, plan.grossBudgetConsumption);
        assertEquals(5, plan.entries.stream().mapToLong(entry -> entry.netChange).sum());
    }

    @Test void payerFeeCreditCannotReplaceOwnApprovedRewardOrExistingBalance() {
        Participation owner = new Participation("board", "owner", 2, Collections.singleton(1L), true);
        FeeAllocation distribution = new FeeAllocation("approval", rules.allocationPolicyId, owner, amounts(1, 5));
        Offer offer = new Offer("offer", "board", "property", "owner", 1,
                "team", 1, "service", "scope", "task", "reward", rules, "available", 1, 5,
                Collections.<Long, Long>emptyMap(), distribution, expires);
        reason("PAYER_POINTS_INSUFFICIENT", () -> CityPointSettlementPlan.prepare(offer, null, amounts(1, 0)));
    }

    @Test void zeroNetParticipantRetainsSeparateRewardAndFeeFacts() {
        Participation owner = new Participation("board", "owner", 2,
                new java.util.HashSet<Long>(java.util.Arrays.asList(1L, 9L)), true);
        FeeAllocation distribution = new FeeAllocation("approval", rules.allocationPolicyId, owner, amounts(1, 2, 9, 3));
        Offer offer = new Offer("offer", "board", "property", "owner", 1,
                "team", 1, "service", "scope", "task", "reward", rules, "available", 1, 5,
                amounts(1, 3), distribution, expires);
        CityPointSettlementPlan plan = CityPointSettlementPlan.prepare(offer, reservation(offer), balances(1, 2));
        assertEquals(0, plan.entries.get(0).netChange);
        assertEquals(3, plan.entries.get(0).rewardCredit);
        assertEquals(2, plan.entries.get(0).feeCredit);
        assertEquals(5, plan.entries.get(0).feeDebit);
        assertEquals(3, plan.entries.stream().mapToLong(entry -> entry.netChange).sum());
    }

    @Test void zeroRewardSettlementRejectsEvenMatchingZeroAmountReservation() {
        Offer free = offer(0, Collections.<Long, Long>emptyMap());
        reason("REWARD_RESERVATION_FORBIDDEN", () -> CityPointSettlementPlan.prepare(free,
                new RewardReservation("unnecessary", free, 0, true), amounts(1, 0)));
    }

    @Test void feeOnlySettlementCannotCarryAnotherOffersRewardReservation() {
        Offer feeOnly = offer(5, Collections.<Long, Long>emptyMap());
        Offer funded = offer(5, amounts(1, 7));
        reason("REWARD_RESERVATION_FORBIDDEN", () -> CityPointSettlementPlan.prepare(feeOnly,
                reservation(funded), balances(1, 5)));
        CityPointSettlementPlan valid = CityPointSettlementPlan.prepare(feeOnly, null, balances(1, 5));
        assertNull(valid.budgetReservationId);
        assertEquals(0, valid.grossBudgetConsumption);
        assertEquals(0, valid.entries.stream().mapToLong(entry -> entry.netChange).sum());
    }

    private Offer offer(long fee, Map<Long, Long> rewards) {
        return new Offer("offer", "board", "property", "owner", 1, "team", 1,
                "service", "scope", "task", "reward", rules, "available", 1, fee, rewards, feeAllocation(fee), expires);
    }
    private FeeAllocation feeAllocation(long fee) {
        return fee == 0 ? null : new FeeAllocation("fee-approval", rules.allocationPolicyId,
                new Participation("board", "owner", 1, Collections.singleton(9L), true), amounts(9, fee));
    }
    private static Map<Long, Long> balances(long... pairs) {
        Map<Long, Long> result = amounts(pairs);
        result.put(9L, 0L);
        return result;
    }
    private RewardReservation reservation(Offer offer) {
        return new RewardReservation("budget", offer, offer.grossReward(), true);
    }
    private static Map<Long, Long> amounts(long... pairs) {
        Map<Long, Long> map = new HashMap<Long, Long>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }
    private void reason(String expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(CityRuleException.class, action).getMessage());
    }
}
