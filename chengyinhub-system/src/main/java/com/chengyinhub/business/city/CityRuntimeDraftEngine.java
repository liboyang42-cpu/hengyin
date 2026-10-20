package com.chengyinhub.business.city;

import java.time.Instant;
import java.util.Map;

import static com.chengyinhub.business.city.CityChecks.*;
import static com.chengyinhub.business.city.CityContracts.*;
import static com.chengyinhub.business.city.CityRuntimeState.*;

/**
 * Default OFF and deliberately not a Spring bean/controller/mapper. Pure transition proposals only.
 * CONTRACT_EVALUATION does not enable any live operation. There is no commit or balance-write method.
 * All authoritative facts must come from reviewed server adapters, never client deserialization.
 */
public final class CityRuntimeDraftEngine {
    public enum Mode { DISABLED, CONTRACT_EVALUATION }
    private final Mode mode;

    public CityRuntimeDraftEngine() { this(Mode.DISABLED); }
    public CityRuntimeDraftEngine(Mode mode) {
        require(mode != null, "MISSING_MODE");
        this.mode = mode;
    }

    public Proposal roll(CityRuntimeState state, Participation participation, long actorId,
                         long expectedMemberVersion, long expectedStateVersion, String requestId,
                         RoundGrant authoritativeGrant, Instant now) {
        authorize(state, participation, actorId, expectedMemberVersion);
        require(authoritativeGrant != null, "SERVER_ROUND_REQUIRED");
        RoundGrant grant = authoritativeGrant;
        require(state.board.boardId.equals(grant.boardId) && participation.teamId.equals(grant.teamId)
                && state.board.rules.releaseId.equals(grant.rulesReleaseId), "ROUND_SCOPE_MISMATCH");
        String requestKey = requestKey(state, actorId, requestId);
        String payload = key("ROLL", participation.teamId, Long.toString(expectedMemberVersion),
                Long.toString(grant.sequence));
        Proposal replay = replay(state, requestKey, payload, grant.businessKey());
        if (replay != null) return replay;
        state.board.requireOpen(now);
        require(state.stateVersion == expectedStateVersion, "STATE_VERSION_CONFLICT");
        require(!now.isBefore(grant.opensAt) && now.isBefore(grant.closesAt), "ROUND_WINDOW_CLOSED");
        // The allocator decides when a new round is legitimate; callers cannot nominate the next number.
        Receipt receipt = receipt(state, requestKey, payload, grant.businessKey(), "ROUND_ROLLED", grant.outcomeId, null, null);
        return proposal(state, new Round(grant, state.board.rules.actionsPerRound), null, receipt, null);
    }

    /** The only service-entry operation: explicit voluntary acceptance of a visible frozen offer. */
    public Proposal acceptVisit(CityRuntimeState state, Participation participation, long actorId,
                                long expectedStateVersion, String requestId, Offer offer,
                                Property currentProperty, String currentAvailabilityRevision,
                                RoundGrant currentServerRound, PayerConsent consent,
                                RewardReservation reservation, Instant now) {
        require(offer != null, "MISSING_OFFER");
        authorize(state, participation, actorId, offer.memberVersion);
        if (offer.grossReward() == 0) RewardReservation.requireForOffer(offer, reservation);
        require(offer.boardId.equals(state.board.boardId) && offer.visitingTeamId.equals(participation.teamId), "OFFER_SCOPE_MISMATCH");
        require(participation.members.contains(offer.payerId), "PAYER_NOT_PARTICIPATING");
        String requestKey = requestKey(state, actorId, requestId);
        String payload = key("ACCEPT_VISIT", offer.fingerprint);
        Proposal replay = replay(state, requestKey, payload, key("VISIT_ACCEPT", offer.visitKey()));
        if (replay != null) return replay;
        state.board.requireOpen(now);
        require(state.stateVersion == expectedStateVersion, "STATE_VERSION_CONFLICT");
        require(now.isBefore(offer.expiresAt), "OFFER_EXPIRED");
        require(offer.fee <= state.board.rules.maxFee && offer.grossReward() <= state.board.rules.maxGrossReward,
                "ECONOMIC_LIMIT_EXCEEDED");
        require(offer.rulesReleaseId.equals(state.board.rules.releaseId)
                && offer.rulesHash.equals(state.board.rules.contentHash)
                && offer.allocationPolicyId.equals(state.board.rules.allocationPolicyId), "RULE_VERSION_CONFLICT");
        require(currentProperty != null && currentProperty.boardId.equals(offer.boardId)
                && currentProperty.propertyId.equals(offer.propertyId) && currentProperty.available
                && currentProperty.version == offer.propertyVersion
                && offer.ownerTeamId.equals(currentProperty.ownerTeamId), "PROPERTY_VERSION_CONFLICT");
        require(offer.availabilityRevision.equals(currentAvailabilityRevision), "AVAILABILITY_VERSION_CONFLICT");
        require(!participation.teamId.equals(offer.ownerTeamId), "SELF_VISIT_FORBIDDEN");
        require(consent != null, "PAYER_CONSENT_REQUIRED");
        consent.requireValid(offer, now);
        RewardReservation.requireForOffer(offer, reservation);
        require(currentServerRound != null && currentServerRound.boardId.equals(offer.boardId)
                && currentServerRound.teamId.equals(participation.teamId), "SERVER_ROUND_REQUIRED");
        Round round = state.rounds.get(currentServerRound.businessKey());
        require(round != null && round.remainingActions > 0 && !now.isBefore(round.grant.opensAt)
                && now.isBefore(round.grant.closesAt), "ROUND_ACTION_UNAVAILABLE");
        Visit visit = new Visit(offer, consent, reservation, round.grant.businessKey(), actorId, now, VisitStatus.ACCEPTED);
        Receipt receipt = receipt(state, requestKey, payload, key("VISIT_ACCEPT", offer.visitKey()),
                "VISIT_ACCEPTED", offer.visitKey(), null, null);
        return proposal(state, new Round(round.grant, round.remainingActions - 1), visit, receipt, null);
    }

    public Proposal completeVisit(CityRuntimeState state, Participation participation, long actorId,
                                  long expectedMemberVersion, long expectedStateVersion, String requestId,
                                  String visitKey, TaskProof authoritativeProof, Property currentProperty,
                                  RewardReservation currentReservation, Map<Long, Long> lockedPointSnapshots,
                                  Instant now) {
        authorize(state, participation, actorId, expectedMemberVersion);
        Visit visit = requireVisit(state, participation, visitKey);
        // No reservation can be smuggled through a zero-reward completion, even on a retry.
        if (visit.offer.grossReward() == 0) {
            RewardReservation.requireForOffer(visit.offer, visit.reservation);
            RewardReservation.requireForOffer(visit.offer, currentReservation);
        }
        require(authoritativeProof != null, "AUTHORITATIVE_PROOF_REQUIRED");
        String requestKey = requestKey(state, actorId, requestId);
        String payload = key("COMPLETE_VISIT", id(visitKey), authoritativeProof.proofId,
                Long.toString(expectedMemberVersion));
        Proposal replay = replay(state, requestKey, payload, visit.offer.settlementKey());
        if (replay != null) return replay;
        state.board.requireOpen(now);
        require(state.stateVersion == expectedStateVersion, "STATE_VERSION_CONFLICT");
        require(visit.status == VisitStatus.ACCEPTED, "VISIT_ALREADY_TERMINAL");
        require(now.isBefore(visit.offer.expiresAt), "VISIT_EXPIRED");
        visit.payerConsent.requireValid(visit.offer, now);
        require(participation.memberVersion == visit.offer.memberVersion, "MEMBERSHIP_CHANGED_REQUIRES_RESOLUTION");
        // Owner changes do not reprice or move accepted beneficiaries; safety/closure still blocks new settlement.
        require(currentProperty != null && currentProperty.boardId.equals(visit.offer.boardId)
                && currentProperty.propertyId.equals(visit.offer.propertyId) && currentProperty.available,
                "SERVICE_UNAVAILABLE_REQUIRES_RESOLUTION");
        TaskProof proof = authoritativeProof;
        require(proof.locationApproved && proof.taskSucceeded && proof.actorId == actorId
                && proof.memberVersion == participation.memberVersion && proof.boardId.equals(visit.offer.boardId)
                && proof.teamId.equals(participation.teamId) && proof.propertyId.equals(visit.offer.propertyId)
                && proof.taskInstanceId.equals(visit.offer.taskInstanceId) && proof.visitKey.equals(visitKey)
                && !proof.completedAt.isBefore(visit.acceptedAt) && !proof.completedAt.isAfter(now)
                && now.isBefore(proof.expiresAt), "PROOF_NOT_VALID_FOR_VISIT");
        require(!state.proofEffects.containsKey(proof.proofId), "PROOF_ALREADY_CONSUMED");
        for (Map.Entry<Long, Long> reward : visit.offer.rewards.entrySet()) {
            if (reward.getValue() > 0) require(!state.rewardEffects.containsKey(visit.offer.rewardKey(reward.getKey())), "REWARD_SCOPE_ALREADY_SETTLED");
        }
        if (visit.offer.grossReward() > 0) {
            require(currentReservation != null && visit.reservation != null
                    && currentReservation.reservationId.equals(visit.reservation.reservationId), "REWARD_BUDGET_NOT_RESERVED");
            currentReservation.requireValid(visit.offer);
        }
        CityPointSettlementPlan plan = CityPointSettlementPlan.prepare(visit.offer, currentReservation, lockedPointSnapshots);
        Receipt receipt = receipt(state, requestKey, payload, visit.offer.settlementKey(),
                "VISIT_SUCCEEDED", visitKey, plan, null);
        return proposal(state, null, visit.terminal(VisitStatus.SUCCEEDED), receipt, proof.proofId);
    }

    /** Free exit remains available after closure/expiry. No points, action refund or fabricated compensation. */
    public Proposal cancelVisit(CityRuntimeState state, Participation participation, long actorId,
                                long expectedMemberVersion, long expectedStateVersion, String requestId,
                                String visitKey, Instant now) {
        authorize(state, participation, actorId, expectedMemberVersion);
        Visit visit = requireVisit(state, participation, visitKey);
        // A zero-reward visit may never release any reservation, including legacy/corrupt snapshots.
        if (visit.offer.grossReward() == 0) RewardReservation.requireForOffer(visit.offer, visit.reservation);
        String requestKey = requestKey(state, actorId, requestId);
        String payload = key("CANCEL_VISIT", id(visitKey), Long.toString(expectedMemberVersion));
        Proposal replay = replay(state, requestKey, payload, key("VISIT_CANCEL", visitKey));
        if (replay != null) return replay;
        require(state.stateVersion == expectedStateVersion, "STATE_VERSION_CONFLICT");
        require(now != null && visit.status == VisitStatus.ACCEPTED, "VISIT_ALREADY_TERMINAL");
        RewardReservation.requireForOffer(visit.offer, visit.reservation);
        VisitStatus terminal = now.isBefore(visit.offer.expiresAt) ? VisitStatus.CANCELLED : VisitStatus.EXPIRED;
        Receipt receipt = receipt(state, requestKey, payload, key("VISIT_CANCEL", visitKey),
                "VISIT_" + terminal.name(), visitKey, null,
                visit.reservation == null ? null : visit.reservation.reservationId);
        return proposal(state, null, visit.terminal(terminal), receipt, null);
    }

    private void authorize(CityRuntimeState state, Participation participation, long actorId, long memberVersion) {
        require(mode == Mode.CONTRACT_EVALUATION, "CITY_RUNTIME_DISABLED");
        require(state != null && participation != null, "MISSING_AUTHORITY");
        participation.requireActor(state.board, actorId, memberVersion);
    }

    private Visit requireVisit(CityRuntimeState state, Participation participation, String visitKey) {
        Visit visit = state.visits.get(id(visitKey));
        require(visit != null && visit.offer.visitingTeamId.equals(participation.teamId), "VISIT_NOT_AUTHORIZED");
        return visit;
    }

    private String requestKey(CityRuntimeState state, long actorId, String requestId) {
        return key("REQUEST", state.board.boardId, Long.toString(positive(actorId)), id(requestId));
    }

    private Proposal replay(CityRuntimeState state, String requestKey, String payload, String effectKey) {
        Receipt request = state.requestReceipts.get(requestKey);
        if (request != null) {
            require(request.payloadHash.equals(payload), "REQUEST_PAYLOAD_CONFLICT");
            return new Proposal(state, request, true);
        }
        Receipt effect = state.effects.get(effectKey);
        if (effect != null) {
            // Acceptance binds the entire offer. A new quote cannot overwrite an accepted business visit.
            if ("VISIT_ACCEPTED".equals(effect.kind)) require(effect.payloadHash.equals(payload), "VISIT_OFFER_CONFLICT");
            // The alias must also bind its payload: otherwise a replay requestId could later be reused
            // for a different action. This proposes only a request-ledger write, no second effect.
            return new Proposal(state.withRequestAlias(requestKey, payload, effect), effect, true);
        }
        return null;
    }

    private Receipt receipt(CityRuntimeState state, String request, String payload, String effect,
                            String kind, String outcome, CityPointSettlementPlan points, String release) {
        return new Receipt(request, payload, effect, kind, outcome, Math.addExact(state.stateVersion, 1), points, release);
    }

    private Proposal proposal(CityRuntimeState state, Round round, Visit visit, Receipt receipt, String proofId) {
        return new Proposal(state.propose(round, visit, receipt, proofId, receipt.pointPlan), receipt, false);
    }

    public static final class Proposal {
        public final CityRuntimeState proposedState;
        public final Receipt receipt;
        public final boolean replay;
        /** Absolute gate: this object never proves a committed success or authorizes live point writes. */
        public final boolean committed = false;
        private Proposal(CityRuntimeState proposedState, Receipt receipt, boolean replay) {
            this.proposedState = proposedState;
            this.receipt = receipt;
            this.replay = replay;
        }
    }
}
