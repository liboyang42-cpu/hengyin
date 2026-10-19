package com.chengyinhub.business.city;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.chengyinhub.business.city.CityContracts.*;

/** Immutable domain snapshot used for transition evaluation, NOT an in-memory persistence service. */
public final class CityRuntimeState {
    public final Board board;
    public final long stateVersion;
    public final Map<String, Round> rounds;
    public final Map<String, Visit> visits;
    public final Map<String, Receipt> requestReceipts;
    public final Map<String, Receipt> effects;
    public final Map<String, String> rewardEffects;
    public final Map<String, String> proofEffects;

    public CityRuntimeState(Board board) {
        this(board, 0, Collections.<String, Round>emptyMap(), Collections.<String, Visit>emptyMap(),
                Collections.<String, Receipt>emptyMap(), Collections.<String, Receipt>emptyMap(),
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap());
    }

    CityRuntimeState(Board board, long stateVersion, Map<String, Round> rounds,
                             Map<String, Visit> visits, Map<String, Receipt> requestReceipts,
                             Map<String, Receipt> effects, Map<String, String> rewardEffects,
                             Map<String, String> proofEffects) {
        CityChecks.require(board != null, "MISSING_BOARD");
        this.board = board;
        this.stateVersion = stateVersion;
        this.rounds = freeze(rounds);
        this.visits = freeze(visits);
        this.requestReceipts = freeze(requestReceipts);
        this.effects = freeze(effects);
        this.rewardEffects = freeze(rewardEffects);
        this.proofEffects = freeze(proofEffects);
    }

    /** Refresh trusted lifecycle facts without migrating rules or erasing historical effects. */
    public CityRuntimeState withBoardFacts(Board currentBoard) {
        CityChecks.require(currentBoard != null && board.boardId.equals(currentBoard.boardId)
                && board.rules.releaseId.equals(currentBoard.rules.releaseId)
                && board.rules.contentHash.equals(currentBoard.rules.contentHash)
                && currentBoard.version >= board.version, "BOARD_FACTS_CONFLICT");
        return new CityRuntimeState(currentBoard, stateVersion, rounds, visits,
                requestReceipts, effects, rewardEffects, proofEffects);
    }

    CityRuntimeState withRequestAlias(String requestKey, String payloadHash, Receipt canonical) {
        Map<String, Receipt> aliases = new LinkedHashMap<String, Receipt>(requestReceipts);
        aliases.put(requestKey, new Receipt(requestKey, payloadHash, canonical.effectKey, canonical.kind,
                canonical.outcomeId, canonical.resultingStateVersion, canonical.pointPlan,
                canonical.reservationToRelease));
        return new CityRuntimeState(board, stateVersion, rounds, visits, aliases, effects, rewardEffects, proofEffects);
    }

    CityRuntimeState propose(Round round, Visit visit, Receipt receipt, String proofId,
                             CityPointSettlementPlan points) {
        Map<String, Round> nextRounds = new LinkedHashMap<String, Round>(rounds);
        Map<String, Visit> nextVisits = new LinkedHashMap<String, Visit>(visits);
        Map<String, Receipt> nextRequests = new LinkedHashMap<String, Receipt>(requestReceipts);
        Map<String, Receipt> nextEffects = new LinkedHashMap<String, Receipt>(effects);
        Map<String, String> nextRewards = new LinkedHashMap<String, String>(rewardEffects);
        Map<String, String> nextProofs = new LinkedHashMap<String, String>(proofEffects);
        if (round != null) nextRounds.put(round.grant.businessKey(), round);
        if (visit != null) nextVisits.put(visit.offer.visitKey(), visit);
        nextRequests.put(receipt.requestKey, receipt);
        nextEffects.put(receipt.effectKey, receipt);
        if (proofId != null) nextProofs.put(proofId, receipt.effectKey);
        if (points != null) {
            for (CityPointSettlementPlan.MemberEntry entry : points.entries) {
                if (entry.rewardBusinessKey != null) nextRewards.put(entry.rewardBusinessKey, receipt.effectKey);
            }
        }
        return new CityRuntimeState(board, Math.addExact(stateVersion, 1), nextRounds, nextVisits,
                nextRequests, nextEffects, nextRewards, nextProofs);
    }

    private static <T> Map<String, T> freeze(Map<String, T> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<String, T>(source));
    }

    public static final class Round {
        public final RoundGrant grant;
        public final int remainingActions;
        Round(RoundGrant grant, int remainingActions) {
            this.grant = grant;
            this.remainingActions = remainingActions;
        }
    }

    public static final class Visit {
        public final Offer offer;
        public final PayerConsent payerConsent;
        public final RewardReservation reservation;
        public final String acceptedRoundKey;
        public final long voluntaryActorId;
        public final Instant acceptedAt;
        public final VisitStatus status;

        Visit(Offer offer, PayerConsent payerConsent, RewardReservation reservation,
              String acceptedRoundKey, long voluntaryActorId, Instant acceptedAt, VisitStatus status) {
            this.offer = offer;
            this.payerConsent = payerConsent;
            this.reservation = reservation;
            this.acceptedRoundKey = acceptedRoundKey;
            this.voluntaryActorId = voluntaryActorId;
            this.acceptedAt = acceptedAt;
            this.status = status;
        }

        Visit terminal(VisitStatus nextStatus) {
            return new Visit(offer, payerConsent, reservation, acceptedRoundKey, voluntaryActorId, acceptedAt, nextStatus);
        }
    }

    /** Stored only if all associated business/points/outbox changes commit in the future adapter. */
    public static final class Receipt {
        public final String requestKey;
        public final String payloadHash;
        public final String effectKey;
        public final String kind;
        public final String outcomeId;
        public final long resultingStateVersion;
        public final CityPointSettlementPlan pointPlan;
        public final String reservationToRelease;

        Receipt(String requestKey, String payloadHash, String effectKey, String kind, String outcomeId,
                long resultingStateVersion, CityPointSettlementPlan pointPlan, String reservationToRelease) {
            this.requestKey = requestKey;
            this.payloadHash = payloadHash;
            this.effectKey = effectKey;
            this.kind = kind;
            this.outcomeId = outcomeId;
            this.resultingStateVersion = resultingStateVersion;
            this.pointPlan = pointPlan;
            this.reservationToRelease = reservationToRelease;
        }
    }
}
