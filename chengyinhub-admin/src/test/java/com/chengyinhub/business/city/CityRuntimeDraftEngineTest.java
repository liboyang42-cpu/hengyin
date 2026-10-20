package com.chengyinhub.business.city;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static com.chengyinhub.business.city.CityContracts.*;
import static com.chengyinhub.business.city.CityRuntimeDraftEngine.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic pure-domain tests only. None is evidence of MySQL locking, rollback or durability. */
class CityRuntimeDraftEngineTest {
    private final Instant now = Instant.parse("2026-10-02T12:00:00Z");
    private final Rules rules = new Rules("rules-1", String.join("", Collections.nCopies(64, "a")),
            "synthetic-approved-allocation", 20, 30, 2, 60);
    private final Board board = new Board("board-1", rules, BoardStatus.OPEN, 1,
            now.minusSeconds(60), now.plusSeconds(3600));
    private final Participation team = new Participation("board-1", "team-1", 1,
            new HashSet<Long>(Arrays.asList(1L, 2L)), true);
    private final Property property = new Property("board-1", "property-1", "owner-1", 4,
            now.plusSeconds(60), true);
    private final RoundGrant round = new RoundGrant("board-1", "team-1", "rules-1", 1,
            now.minusSeconds(10), now.plusSeconds(600), "approved-option-A");
    private final CityRuntimeDraftEngine engine = new CityRuntimeDraftEngine(Mode.CONTRACT_EVALUATION);

    @Test void defaultRuntimeIsDisabled() {
        reason("CITY_RUNTIME_DISABLED", () -> new CityRuntimeDraftEngine().roll(new CityRuntimeState(board),
                team, 1, 1, 0, "r1", round, now));
    }

    @Test void rollIsTeamBusinessUniqueAcrossMembersRequestsAndNewRandomOutcome() {
        Proposal first = rolled();
        RoundGrant otherOutcome = new RoundGrant("board-1", "team-1", "rules-1", 1,
                now.minusSeconds(10), now.plusSeconds(600), "should-never-reroll");
        Proposal retry = engine.roll(first.proposedState, team, 2, 1, 0, "r-new", otherOutcome, now);
        assertTrue(retry.replay);
        assertSame(first.receipt, retry.receipt);
        assertEquals("approved-option-A", retry.receipt.outcomeId);
        assertEquals(2, retry.proposedState.rounds.get(round.businessKey()).remainingActions);
        assertFalse(first.committed);
    }

    @Test void boardAndTeamAreBothPartOfRoundBusinessKey() {
        assertNotEquals(round.businessKey(), new RoundGrant("board-2", "team-1", "rules-1", 1,
                now, now.plusSeconds(1), "outcome").businessKey());
        assertNotEquals(round.businessKey(), new RoundGrant("board-1", "team-2", "rules-1", 1,
                now, now.plusSeconds(1), "outcome").businessKey());
    }

    @Test void replayRequestAliasAlsoRejectsLaterPayloadChanges() {
        Proposal first = rolled();
        Proposal replay = engine.roll(first.proposedState, team, 1, 1, 0, "alias", round, now);
        assertEquals(first.proposedState.stateVersion, replay.proposedState.stateVersion);
        assertEquals(2, replay.proposedState.requestReceipts.size());
        RoundGrant next = new RoundGrant("board-1", "team-1", "rules-1", 2,
                now, now.plusSeconds(600), "B");
        reason("REQUEST_PAYLOAD_CONFLICT", () -> engine.roll(replay.proposedState, team, 1, 1,
                replay.proposedState.stateVersion, "alias", next, now));
    }

    @Test void sameRequestWithChangedRoundIsConflict() {
        RoundGrant next = new RoundGrant("board-1", "team-1", "rules-1", 2,
                now, now.plusSeconds(600), "B");
        reason("REQUEST_PAYLOAD_CONFLICT", () -> engine.roll(rolled().proposedState, team, 1, 1, 1,
                "roll-1", next, now));
    }

    @Test void staleMembershipOrUnknownActorCannotReadOrReplayReceipt() {
        CityRuntimeState state = rolled().proposedState;
        reason("PARTICIPATION_NOT_AUTHORIZED", () -> engine.roll(state, team, 3, 1, 1, "x", round, now));
        reason("PARTICIPATION_NOT_AUTHORIZED", () -> engine.roll(state, team, 1, 2, 1, "roll-1", round, now));
    }

    @Test void roundFromAnotherBoardOrRulesAndUnopenedRoundAreDenied() {
        reason("ROUND_SCOPE_MISMATCH", () -> engine.roll(new CityRuntimeState(board), team, 1, 1, 0, "x",
                new RoundGrant("board-2", "team-1", "rules-1", 1, now, now.plusSeconds(5), "A"), now));
        reason("ROUND_WINDOW_CLOSED", () -> engine.roll(new CityRuntimeState(board), team, 1, 1, 0, "x",
                new RoundGrant("board-1", "team-1", "rules-1", 1, now.plusSeconds(1), now.plusSeconds(5), "A"), now));
    }

    @Test void voluntaryAcceptFreezesOfferConsumesOneTeamActionButNoPoints() {
        Offer offer = offer("v1", "reward1", 1, amounts(1, 7, 9, 2), 5);
        CityRuntimeState original = rolled().proposedState;
        Proposal accepted = accept(original, offer, "accept-1");
        assertNull(accepted.receipt.pointPlan);
        assertEquals(1, accepted.proposedState.rounds.get(round.businessKey()).remainingActions);
        assertEquals(2, original.rounds.get(round.businessKey()).remainingActions);
        assertSame(offer, accepted.proposedState.visits.get(offer.visitKey()).offer);
        assertEquals(VisitStatus.ACCEPTED, accepted.proposedState.visits.get(offer.visitKey()).status);
        Proposal retry = accept(accepted.proposedState, offer, "accept-new");
        assertTrue(retry.replay);
        assertEquals(1, retry.proposedState.rounds.get(round.businessKey()).remainingActions);
    }

    @Test void changedOfferCannotChargeAcceptedBusinessVisitAgain() {
        Offer first = offer("v1", "reward1", 1, amounts(1, 7, 9, 2), 5);
        Offer changedPrice = offer("v1", "reward1", 1, amounts(1, 7, 9, 2), 6);
        CityRuntimeState accepted = accept(rolled().proposedState, first, "accept-1").proposedState;
        reason("VISIT_OFFER_CONFLICT", () -> accept(accepted, changedPrice, "accept-new"));
    }

    @Test void acceptanceRequiresConsentFromExactPayerForExactOffer() {
        Offer offer = offer("v1", "reward1", 2, amounts(2, 7, 9, 2), 5);
        CityRuntimeState state = rolled().proposedState;
        reason("PAYER_CONSENT_REQUIRED", () -> engine.acceptVisit(state, team, 1, state.stateVersion,
                "accept", offer, property, "open-1", round,
                new PayerConsent("wrong-payer", 1, offer.fingerprint, 5, now.plusSeconds(500)), reserve(offer), now));
        reason("PAYER_CONSENT_REQUIRED", () -> engine.acceptVisit(state, team, 1, state.stateVersion,
                "accept", offer, property, "open-1", round, null, reserve(offer), now));
        assertNull(accept(state, offer, "accept").receipt.pointPlan);
    }

    @Test void payerOutsideParticipationIsNotAuthorizedEvenIfAQuoteContainsIt() {
        Offer offer = offer("v1", "reward1", 99, amounts(99, 7, 9, 2), 5);
        reason("PAYER_NOT_PARTICIPATING", () -> accept(rolled().proposedState, offer, "accept"));
    }

    @Test void acceptRejectsStalePropertyAvailabilityAndSelfVisit() {
        Offer offer = offer("v1", "reward1", 1, amounts(1, 7, 9, 2), 5);
        CityRuntimeState state = rolled().proposedState;
        reason("PROPERTY_VERSION_CONFLICT", () -> engine.acceptVisit(state, team, 1, state.stateVersion,
                "a", offer, new Property("board-1", "property-1", "owner-1", 5, now, true),
                "open-1", round, consent(offer), reserve(offer), now));
        reason("AVAILABILITY_VERSION_CONFLICT", () -> engine.acceptVisit(state, team, 1, state.stateVersion,
                "a", offer, property, "closed-2", round, consent(offer), reserve(offer), now));
        Offer self = new Offer("q", "board-1", "property-1", "team-1", 4, "team-1", 1,
                "service-1", "v1", "task-v1", "reward1", rules, "open-1", 1, 0,
                Collections.<Long, Long>emptyMap(), null, now.plusSeconds(400));
        reason("SELF_VISIT_FORBIDDEN", () -> engine.acceptVisit(state, team, 1, state.stateVersion,
                "a", self, new Property("board-1", "property-1", "team-1", 4, now, true),
                "open-1", round, consent(self), null, now));
    }

    @Test void teamActionBudgetCannotBeRefilledByRerollOrTeammates() {
        Offer a = offer("v1", "r1", 1, amounts(1, 7, 9, 2), 5);
        Offer b = offer("v2", "r2", 1, amounts(1, 7, 9, 2), 5);
        Offer c = offer("v3", "r3", 1, amounts(1, 7, 9, 2), 5);
        CityRuntimeState state = accept(rolled().proposedState, a, "a1").proposedState;
        state = accept(state, b, "a2").proposedState;
        state = engine.roll(state, team, 2, 1, state.stateVersion, "another-roll", round, now).proposedState;
        final CityRuntimeState exhausted = state;
        reason("ROUND_ACTION_UNAVAILABLE", () -> accept(exhausted, c, "a3"));
    }

    @Test void completionUsesPayersOwnRewardForZeroBalanceAndPreservesOtherBalances() {
        Offer offer = offer("v1", "r1", 1, amounts(1, 7, 9, 2), 5);
        Proposal result = complete(accept(rolled().proposedState, offer, "a").proposedState, offer,
                "done", proof(offer, "proof1", true, true), property, amounts(1, 0, 9, 11));
        assertEquals(VisitStatus.SUCCEEDED, result.proposedState.visits.get(offer.visitKey()).status);
        CityPointSettlementPlan plan = result.receipt.pointPlan;
        assertEquals(9, plan.grossBudgetConsumption); // fees do not replenish approved reward budget
        assertEquals(Arrays.asList(1L, 9L), plan.memberLockOrder);
        assertEquals(2, plan.entries.get(0).proposedAfter);
        assertEquals(18, plan.entries.get(1).proposedAfter);
        assertEquals(5, plan.entries.get(1).feeCredit);
        assertEquals(9, plan.entries.stream().mapToLong(entry -> entry.netChange).sum());
        assertEquals(5, plan.entries.get(0).feeDebit);
        assertEquals(0, plan.entries.get(1).feeDebit);
        assertFalse(result.committed);
    }

    @Test void otherMembersRewardsCannotCoverPayersDeficit() {
        Offer offer = offer("v1", "r1", 1, amounts(2, 10, 9, 2), 5);
        CityRuntimeState accepted = accept(rolled().proposedState, offer, "a").proposedState;
        Map<Long, Long> balances = amounts(1, 0, 9, 0);
        balances.put(2L, 100L);
        reason("PAYER_POINTS_INSUFFICIENT", () -> complete(accepted, offer, "done",
                proof(offer, "p", true, true), property, balances));
        assertEquals(VisitStatus.ACCEPTED, accepted.visits.get(offer.visitKey()).status);
        assertTrue(accepted.rewardEffects.isEmpty());
    }

    @Test void successfulReceiptReplaysWithoutNewBalanceReadsOrFreshRandomness() {
        Offer offer = offer("v1", "r1", 1, amounts(1, 7, 9, 2), 5);
        Proposal first = complete(accept(rolled().proposedState, offer, "a").proposedState,
                offer, "done", proof(offer, "p", true, true), property, amounts(1, 0, 9, 0));
        Proposal retry = complete(first.proposedState, offer, "new-request", proof(offer, "p2", false, false),
                null, null);
        assertTrue(retry.replay);
        assertSame(first.receipt, retry.receipt);
        assertEquals(1, retry.proposedState.proofEffects.size());
        assertEquals(2, retry.proposedState.rewardEffects.size());
    }

    @Test void completionAfterOwnerChangeKeepsOriginalFeeAndBeneficiaries() {
        Offer offer = offer("v1", "r1", 1, amounts(1, 7, 9, 2), 5);
        Property changedOwner = new Property("board-1", "property-1", "owner-2", 5, now, true);
        Proposal done = complete(accept(rolled().proposedState, offer, "a").proposedState, offer, "done",
                proof(offer, "p", true, true), changedOwner, amounts(1, 0, 9, 0));
        assertEquals(Arrays.asList(1L, 9L), done.receipt.pointPlan.memberLockOrder);
        assertEquals("owner-1", done.proposedState.visits.get(offer.visitKey()).offer.ownerTeamId);
        assertEquals(5, done.receipt.pointPlan.entries.get(1).feeCredit);
        assertEquals(7, done.receipt.pointPlan.entries.get(1).proposedAfter);
    }

    @Test void failedLocationTaskOrWrongScopeNeverProducesASettlement() {
        Offer offer = offer("v1", "r1", 1, amounts(1, 7, 9, 2), 5);
        CityRuntimeState state = accept(rolled().proposedState, offer, "a").proposedState;
        reason("PROOF_NOT_VALID_FOR_VISIT", () -> complete(state, offer, "d1",
                proof(offer, "p1", false, true), property, amounts(1, 0, 9, 0)));
        reason("PROOF_NOT_VALID_FOR_VISIT", () -> complete(state, offer, "d2",
                proof(offer, "p2", true, false), property, amounts(1, 0, 9, 0)));
        Offer other = offer("v2", "r2", 1, amounts(1, 7, 9, 2), 5);
        reason("PROOF_NOT_VALID_FOR_VISIT", () -> complete(state, offer, "d3",
                proof(other, "p3", true, true), property, amounts(1, 0, 9, 0)));
        assertEquals(VisitStatus.ACCEPTED, state.visits.get(offer.visitKey()).status);
    }

    @Test void futureOrExpiredProofsAreRejected() {
        Offer offer = offer("v1", "r1", 1, amounts(1, 7, 9, 2), 5);
        CityRuntimeState state = accept(rolled().proposedState, offer, "a").proposedState;
        TaskProof future = new TaskProof("p", "board-1", "team-1", "property-1", offer.taskInstanceId,
                offer.visitKey(), 1, 1, now.plusSeconds(1), now.plusSeconds(5), true, true);
        reason("PROOF_NOT_VALID_FOR_VISIT", () -> complete(state, offer, "done", future, property, amounts(1, 0, 9, 0)));
        reason("PROOF_NOT_VALID_FOR_VISIT", () -> engine.completeVisit(state, team, 1, 1, state.stateVersion,
                "expired", offer.visitKey(), proof(offer, "old-proof", true, true), property, reserve(offer),
                amounts(1, 0, 9, 0), now.plusSeconds(300)));
    }

    @Test void revokedBudgetCannotSettleAnAcceptedVisit() {
        Offer offer = offer("v1", "r1", 1, amounts(1, 7, 9, 2), 5);
        CityRuntimeState state = accept(rolled().proposedState, offer, "a").proposedState;
        RewardReservation revoked = new RewardReservation(reserve(offer).reservationId, offer,
                offer.grossReward(), false);
        reason("REWARD_BUDGET_NOT_RESERVED", () -> engine.completeVisit(state, team, 1, 1,
                state.stateVersion, "done", offer.visitKey(), proof(offer, "p", true, true), property,
                revoked, amounts(1, 0, 9, 0), now));
        assertEquals(VisitStatus.ACCEPTED, state.visits.get(offer.visitKey()).status);
    }

    @Test void staleStateVersionCannotConsumeAnotherAction() {
        Offer offer = offer("v1", "r1", 1, amounts(1, 7, 9, 2), 5);
        CityRuntimeState state = rolled().proposedState;
        reason("STATE_VERSION_CONFLICT", () -> engine.acceptVisit(state, team, 1, 0,
                "accept", offer, property, "open-1", round, consent(offer), reserve(offer), now));
        assertEquals(2, state.rounds.get(round.businessKey()).remainingActions);
    }

    @Test void repeatRewardScopeAcrossDifferentVisitsCannotMintAgain() {
        Offer first = offer("v1", "shared-reward", 1, amounts(1, 7, 9, 2), 5);
        Offer second = offer("v2", "shared-reward", 1, amounts(1, 7, 9, 2), 5);
        CityRuntimeState state = complete(accept(rolled().proposedState, first, "a1").proposedState,
                first, "d1", proof(first, "p1", true, true), property, amounts(1, 0, 9, 0)).proposedState;
        CityRuntimeState secondAccepted = accept(state, second, "a2").proposedState;
        reason("REWARD_SCOPE_ALREADY_SETTLED", () -> complete(secondAccepted, second, "d2",
                proof(second, "p2", true, true), property, amounts(1, 100, 9, 100)));
    }

    @Test void closingBlocksSettlementAndFreeExitReleasesOnlyItsReservation() {
        Offer offer = offer("v1", "r1", 1, amounts(1, 7, 9, 2), 5);
        CityRuntimeState state = accept(rolled().proposedState, offer, "a").proposedState;
        Property closed = new Property("board-1", "property-1", "owner-1", 5, now, false);
        reason("SERVICE_UNAVAILABLE_REQUIRES_RESOLUTION", () -> complete(state, offer, "d",
                proof(offer, "p", true, true), closed, amounts(1, 0, 9, 0)));
        CityRuntimeState frozen = state.withBoardFacts(new Board("board-1", rules, BoardStatus.FROZEN, 2,
                board.opensAt, board.closesAt));
        reason("BOARD_NOT_OPEN", () -> complete(frozen, offer, "d",
                proof(offer, "p", true, true), property, amounts(1, 0, 9, 0)));
        Proposal cancel = engine.cancelVisit(frozen, team, 1, 1, frozen.stateVersion, "cancel", offer.visitKey(), now);
        assertNull(cancel.receipt.pointPlan);
        assertEquals(reserve(offer).reservationId, cancel.receipt.reservationToRelease);
        assertEquals(VisitStatus.CANCELLED, cancel.proposedState.visits.get(offer.visitKey()).status);
        assertTrue(engine.cancelVisit(cancel.proposedState, team, 1, 1, 0, "retry-cancel", offer.visitKey(), now).replay);
        reason("BOARD_NOT_OPEN", () -> accept(frozen, offer("v2", "r2", 1, amounts(1, 7, 9, 2), 5), "new"));
    }

    @Test void cancellationExpiryAndCompletionAreMutuallyExclusive() {
        Offer offer = offer("v1", "r1", 1, amounts(1, 7, 9, 2), 5);
        CityRuntimeState state = accept(rolled().proposedState, offer, "a").proposedState;
        Proposal expired = engine.cancelVisit(state, team, 1, 1, state.stateVersion, "expire",
                offer.visitKey(), offer.expiresAt);
        assertEquals(VisitStatus.EXPIRED, expired.proposedState.visits.get(offer.visitKey()).status);
        reason("VISIT_ALREADY_TERMINAL", () -> complete(expired.proposedState, offer, "d",
                proof(offer, "p", true, true), property, amounts(1, 0, 9, 0)));
    }

    @Test void approvedZeroFeeOptionHasNoRewardBudgetOrDebit() {
        Offer free = offer("free", "free-scope", 1, Collections.<Long, Long>emptyMap(), 0);
        Proposal done = complete(accept(rolled().proposedState, free, "a").proposedState,
                free, "d", proof(free, "p", true, true), property, amounts(1, 0));
        assertEquals(0, done.receipt.pointPlan.grossBudgetConsumption);
        assertNull(done.receipt.pointPlan.budgetReservationId);
        assertEquals(0, done.receipt.pointPlan.entries.get(0).netChange);
    }

    @Test void propertyCompareAndSwapAndProtectionCannotBeBypassed() {
        Property empty = new Property("board-1", "property-1", null, 0, now, true);
        Property acquired = empty.proposeAcquire(0, "team-1", now, rules);
        reason("PROPERTY_VERSION_CONFLICT", () -> acquired.proposeAcquire(0, "team-2", now, rules));
        reason("PROPERTY_ALREADY_OWNED", () -> acquired.proposeAcquire(1, "team-2", now, rules));
        reason("PROPERTY_PROTECTED", () -> acquired.proposeTransfer(1, "team-1", "team-2", now, rules));
        Property transferred = acquired.proposeTransfer(1, "team-1", "team-2", now.plusSeconds(60), rules);
        assertEquals("team-2", transferred.ownerTeamId);
        assertEquals(2, transferred.version);
        assertEquals("team-1", acquired.ownerTeamId);
    }

    @Test void snapshotCollectionsCannotBeMutatedAndSeasonHasNoPointReset() {
        Proposal state = rolled();
        assertThrows(UnsupportedOperationException.class, () -> state.proposedState.rounds.clear());
        assertThrows(UnsupportedOperationException.class, () -> team.members.clear());
        Offer offer = offer("v1", "r1", 1, amounts(1, 7), 5);
        assertThrows(UnsupportedOperationException.class, () -> offer.rewards.put(2L, 100L));
        assertTrue(new CityRuntimeState(new Board("new-season", rules, BoardStatus.OPEN, 1,
                board.opensAt, board.closesAt)).effects.isEmpty());
    }

    @Test void zeroRewardAcceptanceRejectsForeignReservationWithoutConsumingAction() {
        Offer free = offer("free", "free-reward", 1, Collections.<Long, Long>emptyMap(), 0);
        Offer funded = offer("funded", "funded-reward", 1, amounts(1, 7), 0);
        CityRuntimeState state = rolled().proposedState;
        reason("REWARD_RESERVATION_FORBIDDEN", () -> engine.acceptVisit(state, team, 1, state.stateVersion,
                "accept-free", free, property, "open-1", round, consent(free), reserve(funded), now));
        assertEquals(2, state.rounds.get(round.businessKey()).remainingActions);
        assertTrue(state.visits.isEmpty());
    }

    @Test void zeroRewardCompletionRejectsForeignReservationAndPreservesAcceptedVisit() {
        Offer free = offer("free", "free-reward", 1, Collections.<Long, Long>emptyMap(), 0);
        Offer funded = offer("funded", "funded-reward", 1, amounts(1, 7), 0);
        CityRuntimeState state = accept(rolled().proposedState, free, "accept-free").proposedState;
        reason("REWARD_RESERVATION_FORBIDDEN", () -> engine.completeVisit(state, team, 1, 1, state.stateVersion,
                "complete-free", free.visitKey(), proof(free, "p", true, true), property,
                reserve(funded), amounts(1, 0), now));
        assertEquals(VisitStatus.ACCEPTED, state.visits.get(free.visitKey()).status);
        assertTrue(state.rewardEffects.isEmpty());
    }

    @Test void zeroRewardCancellationNeverReleasesBudgetAndReplaysWithoutOne() {
        Offer free = offer("free", "free-reward", 1, Collections.<Long, Long>emptyMap(), 0);
        CityRuntimeState state = accept(rolled().proposedState, free, "accept-free").proposedState;
        Proposal cancelled = engine.cancelVisit(state, team, 1, 1, state.stateVersion,
                "cancel-free", free.visitKey(), now);
        assertNull(cancelled.receipt.reservationToRelease);
        assertNull(cancelled.receipt.pointPlan);
        Proposal replay = engine.cancelVisit(cancelled.proposedState, team, 1, 1, state.stateVersion,
                "cancel-again", free.visitKey(), now);
        assertTrue(replay.replay);
        assertNull(replay.receipt.reservationToRelease);
    }

    @Test void cancellationRejectsCorruptStoredForeignReservationBeforeProposingRelease() {
        Offer free = offer("free", "free-reward", 1, Collections.<Long, Long>emptyMap(), 0);
        Offer funded = offer("funded", "funded-reward", 1, amounts(1, 7), 0);
        Proposal accepted = accept(rolled().proposedState, free, "accept-free");
        CityRuntimeState.Visit original = accepted.proposedState.visits.get(free.visitKey());
        // Deliberately construct the invalid pre-fix persisted shape; no repository is simulated.
        CityRuntimeState.Visit corrupt = new CityRuntimeState.Visit(free, original.payerConsent,
                reserve(funded), original.acceptedRoundKey, original.voluntaryActorId, original.acceptedAt,
                VisitStatus.ACCEPTED);
        CityRuntimeState state = accepted.proposedState.propose(null, corrupt, accepted.receipt, null, null);
        reason("REWARD_RESERVATION_FORBIDDEN", () -> engine.cancelVisit(state, team, 1, 1,
                state.stateVersion, "cancel-corrupt", free.visitKey(), now));
        reason("REWARD_RESERVATION_FORBIDDEN", () -> engine.completeVisit(state, team, 1, 1,
                state.stateVersion, "complete-corrupt", free.visitKey(), proof(free, "p", true, true),
                property, null, amounts(1, 0), now));
    }

    @Test void fundedVisitCancellationRejectsDifferentOffersStoredReservation() {
        Offer offer = offer("first", "reward-first", 1, amounts(1, 7), 0);
        Offer other = offer("other", "reward-other", 1, amounts(1, 7), 0);
        Proposal accepted = accept(rolled().proposedState, offer, "accept");
        CityRuntimeState.Visit original = accepted.proposedState.visits.get(offer.visitKey());
        CityRuntimeState.Visit corrupt = new CityRuntimeState.Visit(offer, original.payerConsent,
                reserve(other), original.acceptedRoundKey, original.voluntaryActorId, original.acceptedAt,
                VisitStatus.ACCEPTED);
        CityRuntimeState state = accepted.proposedState.propose(null, corrupt, accepted.receipt, null, null);
        reason("REWARD_BUDGET_NOT_RESERVED", () -> engine.cancelVisit(state, team, 1, 1,
                state.stateVersion, "cancel-corrupt", offer.visitKey(), now));
    }

    private Proposal rolled() {
        return engine.roll(new CityRuntimeState(board), team, 1, 1, 0, "roll-1", round, now);
    }
    private Offer offer(String visitScope, String rewardScope, long payer, Map<Long, Long> rewards, long fee) {
        return new Offer("offer-" + visitScope, "board-1", "property-1", "owner-1", 4, "team-1", 1,
                "service-1", visitScope, "task-" + visitScope, rewardScope, rules, "open-1",
                payer, fee, rewards, feeAllocation(fee), now.plusSeconds(400));
    }
    private FeeAllocation feeAllocation(long fee) {
        return fee == 0 ? null : new FeeAllocation("fee-approval", rules.allocationPolicyId,
                new Participation("board-1", "owner-1", 1, Collections.singleton(9L), true), amounts(9, fee));
    }
    private PayerConsent consent(Offer offer) {
        return new PayerConsent("consent-" + offer.visitScope, offer.payerId, offer.fingerprint, offer.fee,
                now.plusSeconds(500));
    }
    private RewardReservation reserve(Offer offer) {
        return offer.grossReward() == 0 ? null : new RewardReservation("budget-" + offer.visitScope,
                offer, offer.grossReward(), true);
    }
    private Proposal accept(CityRuntimeState state, Offer offer, String request) {
        return engine.acceptVisit(state, team, 1, state.stateVersion, request, offer, property,
                "open-1", round, consent(offer), reserve(offer), now);
    }
    private TaskProof proof(Offer offer, String proofId, boolean location, boolean task) {
        return new TaskProof(proofId, "board-1", "team-1", "property-1", offer.taskInstanceId,
                offer.visitKey(), 1, 1, now, now.plusSeconds(300), location, task);
    }
    private Proposal complete(CityRuntimeState state, Offer offer, String request, TaskProof proof,
                              Property current, Map<Long, Long> balances) {
        return engine.completeVisit(state, team, 1, 1, state.stateVersion, request, offer.visitKey(),
                proof, current, reserve(offer), balances, now);
    }
    private static Map<Long, Long> amounts(long... pairs) {
        Map<Long, Long> map = new HashMap<Long, Long>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }
    private void reason(String reason, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(reason, assertThrows(CityRuleException.class, operation).getMessage());
    }
}
