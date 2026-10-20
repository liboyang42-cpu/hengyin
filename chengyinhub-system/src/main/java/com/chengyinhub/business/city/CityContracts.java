package com.chengyinhub.business.city;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.chengyinhub.business.city.CityChecks.*;

/**
 * Internal, immutable server facts, NOT HTTP request DTOs or proof-verification implementations.
 * Only a future reviewed authorization/publication/persistence adapter may supply these facts.
 * No topic, registration, template, city currency, team wallet or initial points balance exists here.
 */
public final class CityContracts {
    private CityContracts() { }

    public enum BoardStatus { DRAFT, REVIEW, READY, OPEN, FROZEN, CLOSING, CLOSED, ARCHIVED }
    public enum VisitStatus { ACCEPTED, SUCCEEDED, CANCELLED, EXPIRED }

    public static final class Rules {
        public final String releaseId;
        public final String contentHash;
        public final String allocationPolicyId;
        public final long maxFee;
        public final long maxGrossReward;
        public final int actionsPerRound;
        public final long protectionSeconds;

        public Rules(String releaseId, String contentHash, String allocationPolicyId,
                     long maxFee, long maxGrossReward, int actionsPerRound, long protectionSeconds) {
            this.releaseId = id(releaseId);
            require(contentHash != null && contentHash.matches("[0-9a-f]{64}"), "INVALID_RULE_HASH");
            this.contentHash = contentHash;
            this.allocationPolicyId = id(allocationPolicyId);
            this.maxFee = nonnegative(maxFee);
            this.maxGrossReward = nonnegative(maxGrossReward);
            require(actionsPerRound > 0, "ACTION_BUDGET_NOT_APPROVED");
            this.actionsPerRound = actionsPerRound;
            this.protectionSeconds = nonnegative(protectionSeconds);
        }
    }

    public static final class Board {
        public final String boardId;
        public final Rules rules;
        public final BoardStatus status;
        public final long version;
        public final Instant opensAt;
        public final Instant closesAt;

        public Board(String boardId, Rules rules, BoardStatus status, long version,
                     Instant opensAt, Instant closesAt) {
            this.boardId = id(boardId);
            require(rules != null && status != null && opensAt != null && closesAt != null
                    && opensAt.isBefore(closesAt), "INVALID_BOARD");
            this.rules = rules;
            this.status = status;
            this.version = nonnegative(version);
            this.opensAt = opensAt;
            this.closesAt = closesAt;
        }

        public void requireOpen(Instant now) {
            require(now != null && status == BoardStatus.OPEN && !now.isBefore(opensAt)
                    && now.isBefore(closesAt), "BOARD_NOT_OPEN");
        }
    }

    public static final class Participation {
        public final String boardId;
        public final String teamId;
        public final long memberVersion;
        public final Set<Long> members;
        public final boolean eligible;

        public Participation(String boardId, String teamId, long memberVersion,
                             Set<Long> members, boolean eligible) {
            this.boardId = id(boardId);
            this.teamId = id(teamId);
            this.memberVersion = positive(memberVersion);
            require(members != null && !members.isEmpty() && members.size() <= 128,
                    "INVALID_MEMBERSHIP");
            TreeSet<Long> copy = new TreeSet<Long>();
            for (Long member : members) {
                require(member != null, "INVALID_MEMBER");
                copy.add(positive(member));
            }
            this.members = Collections.unmodifiableSet(copy);
            this.eligible = eligible;
        }

        public void requireActor(Board board, long actorId, long expectedMemberVersion) {
            require(eligible && boardId.equals(board.boardId) && members.contains(actorId)
                    && memberVersion == expectedMemberVersion, "PARTICIPATION_NOT_AUTHORIZED");
        }
    }

    /** Issued by the server round allocator, never copied from a client round_seq. */
    public static final class RoundGrant {
        public final String boardId;
        public final String teamId;
        public final String rulesReleaseId;
        public final long sequence;
        public final Instant opensAt;
        public final Instant closesAt;
        public final String outcomeId;

        public RoundGrant(String boardId, String teamId, String rulesReleaseId, long sequence,
                          Instant opensAt, Instant closesAt, String outcomeId) {
            this.boardId = id(boardId);
            this.teamId = id(teamId);
            this.rulesReleaseId = id(rulesReleaseId);
            this.sequence = positive(sequence);
            require(opensAt != null && closesAt != null && opensAt.isBefore(closesAt), "INVALID_ROUND_WINDOW");
            this.opensAt = opensAt;
            this.closesAt = closesAt;
            this.outcomeId = id(outcomeId);
        }

        public String businessKey() {
            return key("ROUND", boardId, teamId, Long.toString(sequence));
        }
    }

    /** Location approval AND task completion from an authoritative verifier, with bounded scope. */
    public static final class TaskProof {
        public final String proofId;
        public final String boardId;
        public final String teamId;
        public final String propertyId;
        public final String taskInstanceId;
        public final String visitKey;
        public final long actorId;
        public final long memberVersion;
        public final Instant completedAt;
        public final Instant expiresAt;
        public final boolean locationApproved;
        public final boolean taskSucceeded;

        public TaskProof(String proofId, String boardId, String teamId, String propertyId,
                         String taskInstanceId, String visitKey, long actorId, long memberVersion,
                         Instant completedAt, Instant expiresAt, boolean locationApproved, boolean taskSucceeded) {
            this.proofId = id(proofId);
            this.boardId = id(boardId);
            this.teamId = id(teamId);
            this.propertyId = id(propertyId);
            this.taskInstanceId = id(taskInstanceId);
            this.visitKey = id(visitKey);
            this.actorId = positive(actorId);
            this.memberVersion = positive(memberVersion);
            require(completedAt != null && expiresAt != null && completedAt.isBefore(expiresAt), "INVALID_PROOF_WINDOW");
            this.completedAt = completedAt;
            this.expiresAt = expiresAt;
            this.locationApproved = locationApproved;
            this.taskSucceeded = taskSucceeded;
        }
    }

    /** Immutable virtual game ownership; does not mutate POI/store/legal ownership. */
    public static final class Property {
        public final String boardId;
        public final String propertyId;
        public final String ownerTeamId;
        public final long version;
        public final Instant protectedUntil;
        public final boolean available;

        public Property(String boardId, String propertyId, String ownerTeamId, long version,
                        Instant protectedUntil, boolean available) {
            this.boardId = id(boardId);
            this.propertyId = id(propertyId);
            this.ownerTeamId = ownerTeamId == null ? null : id(ownerTeamId);
            this.version = nonnegative(version);
            require(protectedUntil != null, "MISSING_PROTECTION_TIME");
            this.protectedUntil = protectedUntil;
            this.available = available;
        }

        /** Proposal only: caller must atomically validate holding limit, actions and point effect. */
        public Property proposeAcquire(long expectedVersion, String acquiringTeam, Instant now, Rules rules) {
            require(version == expectedVersion, "PROPERTY_VERSION_CONFLICT");
            require(ownerTeamId == null, "PROPERTY_ALREADY_OWNED");
            require(available && now != null && rules != null, "PROPERTY_UNAVAILABLE");
            return new Property(boardId, propertyId, id(acquiringTeam), Math.addExact(version, 1),
                    now.plusSeconds(rules.protectionSeconds), true);
        }

        /** Proposal only: challenge state/evidence/compensation transaction is a later slice. */
        public Property proposeTransfer(long expectedVersion, String expectedOwner, String newOwner,
                                        Instant now, Rules rules) {
            require(version == expectedVersion && ownerTeamId != null
                    && ownerTeamId.equals(expectedOwner), "PROPERTY_VERSION_CONFLICT");
            require(available && now != null && rules != null, "PROPERTY_UNAVAILABLE");
            require(!now.isBefore(protectedUntil), "PROPERTY_PROTECTED");
            require(!ownerTeamId.equals(id(newOwner)), "SELF_TRANSFER_FORBIDDEN");
            return new Property(boardId, propertyId, newOwner, Math.addExact(version, 1),
                    now.plusSeconds(rules.protectionSeconds), true);
        }
    }

    /** Published-policy allocation approved against the operating team's own member snapshot. */
    public static final class FeeAllocation {
        public final String approvalId;
        public final String policyId;
        public final String boardId;
        public final String ownerTeamId;
        public final long ownerMemberVersion;
        public final Map<Long, Long> credits;

        public FeeAllocation(String approvalId, String policyId, Participation operatingTeam,
                             Map<Long, Long> credits) {
            this.approvalId = id(approvalId);
            this.policyId = id(policyId);
            require(operatingTeam != null && operatingTeam.eligible, "FEE_ALLOCATION_NOT_APPROVED");
            this.boardId = operatingTeam.boardId;
            this.ownerTeamId = operatingTeam.teamId;
            this.ownerMemberVersion = operatingTeam.memberVersion;
            this.credits = amounts(credits);
            require(operatingTeam.members.containsAll(this.credits.keySet()), "FEE_BENEFICIARY_NOT_AUTHORIZED");
        }

        /** Storage reconstruction only; preserves original approval fields, never issues an approval. */
        static FeeAllocation restore(String approvalId, String policyId, String boardId, String ownerTeamId,
                                     long ownerMemberVersion, Map<Long, Long> credits) {
            return new FeeAllocation(approvalId, policyId, boardId, ownerTeamId, ownerMemberVersion, credits);
        }
        private FeeAllocation(String approvalId, String policyId, String boardId, String ownerTeamId,
                              long ownerMemberVersion, Map<Long, Long> credits) {
            this.approvalId = id(approvalId); this.policyId = id(policyId); this.boardId = id(boardId);
            this.ownerTeamId = id(ownerTeamId); this.ownerMemberVersion = positive(ownerMemberVersion);
            this.credits = amounts(credits);
        }

        public long total() {
            long sum = 0;
            for (long credit : credits.values()) sum = Math.addExact(sum, credit);
            return sum;
        }
    }

    /** Frozen server-approved offer. Rewards are explicit per-person allocations, never a team balance. */
    public static final class Offer {
        public final String offerId;
        public final String boardId;
        public final String propertyId;
        public final String ownerTeamId;
        public final long propertyVersion;
        public final String visitingTeamId;
        public final long memberVersion;
        public final String serviceInstanceId;
        public final String visitScope;
        public final String taskInstanceId;
        public final String rewardScope;
        public final String rulesReleaseId;
        public final String rulesHash;
        public final String allocationPolicyId;
        public final String availabilityRevision;
        public final long payerId;
        public final long fee;
        public final Map<Long, Long> rewards;
        public final FeeAllocation feeAllocation;
        public final Map<Long, Long> feeCredits;
        public final Instant expiresAt;
        public final String fingerprint;

        public Offer(String offerId, String boardId, String propertyId, String ownerTeamId,
                     long propertyVersion, String visitingTeamId, long memberVersion,
                     String serviceInstanceId, String visitScope, String taskInstanceId, String rewardScope,
                     Rules rules, String availabilityRevision, long payerId, long fee,
                     Map<Long, Long> rewards, FeeAllocation feeAllocation, Instant expiresAt) {
            this.offerId = id(offerId);
            this.boardId = id(boardId);
            this.propertyId = id(propertyId);
            this.ownerTeamId = id(ownerTeamId);
            this.propertyVersion = nonnegative(propertyVersion);
            this.visitingTeamId = id(visitingTeamId);
            this.memberVersion = positive(memberVersion);
            this.serviceInstanceId = id(serviceInstanceId);
            this.visitScope = id(visitScope);
            this.taskInstanceId = id(taskInstanceId);
            this.rewardScope = id(rewardScope);
            require(rules != null, "RULES_NOT_APPROVED");
            this.rulesReleaseId = rules.releaseId;
            this.rulesHash = rules.contentHash;
            this.allocationPolicyId = rules.allocationPolicyId;
            this.availabilityRevision = id(availabilityRevision);
            this.payerId = positive(payerId);
            this.fee = nonnegative(fee);
            this.rewards = amounts(rewards);
            require(fee == 0 || feeAllocation != null, "FEE_ALLOCATION_NOT_APPROVED");
            if (feeAllocation != null) {
                require(feeAllocation.boardId.equals(boardId) && feeAllocation.ownerTeamId.equals(ownerTeamId)
                        && feeAllocation.policyId.equals(rules.allocationPolicyId), "FEE_ALLOCATION_NOT_APPROVED");
                require(feeAllocation.total() == fee, "FEE_ALLOCATION_NOT_CONSERVING");
            }
            this.feeAllocation = feeAllocation;
            this.feeCredits = feeAllocation == null ? Collections.<Long, Long>emptyMap() : feeAllocation.credits;
            require(expiresAt != null, "MISSING_OFFER_EXPIRY");
            this.expiresAt = expiresAt;
            require(fee <= rules.maxFee && grossReward() <= rules.maxGrossReward, "ECONOMIC_LIMIT_EXCEEDED");
            this.fingerprint = key(offerId, boardId, propertyId, ownerTeamId, Long.toString(propertyVersion),
                    visitingTeamId, Long.toString(memberVersion), serviceInstanceId, visitScope,
                    taskInstanceId, rewardScope, rulesReleaseId, rulesHash, allocationPolicyId,
                    availabilityRevision, Long.toString(payerId), Long.toString(fee),
                    amountHash(this.rewards), amountHash(this.feeCredits),
                    feeAllocation == null ? "NONE" : feeAllocation.approvalId,
                    feeAllocation == null ? "NONE" : Long.toString(feeAllocation.ownerMemberVersion), expiresAt.toString());
        }

        public long grossReward() {
            long sum = 0;
            for (long reward : rewards.values()) sum = Math.addExact(sum, reward);
            return sum;
        }

        public String visitKey() {
            return key("VISIT", boardId, visitingTeamId, serviceInstanceId, visitScope);
        }

        public String settlementKey() { return key("VISIT_SETTLEMENT", visitKey()); }
        public String rewardKey(long memberId) {
            return key("REWARD", boardId, rulesReleaseId, rewardScope, Long.toString(memberId));
        }
    }

    /** Server-authenticated payer consent for this exact frozen offer, not a client boolean. */
    public static final class PayerConsent {
        public final String consentId;
        public final long payerId;
        public final String offerFingerprint;
        public final long maximumFee;
        public final Instant expiresAt;

        public PayerConsent(String consentId, long payerId, String offerFingerprint,
                            long maximumFee, Instant expiresAt) {
            this.consentId = id(consentId);
            this.payerId = positive(payerId);
            this.offerFingerprint = id(offerFingerprint);
            this.maximumFee = nonnegative(maximumFee);
            require(expiresAt != null, "MISSING_CONSENT_EXPIRY");
            this.expiresAt = expiresAt;
        }

        public void requireValid(Offer offer, Instant now) {
            require(payerId == offer.payerId && offerFingerprint.equals(offer.fingerprint)
                    && maximumFee >= offer.fee && now != null && now.isBefore(expiresAt), "PAYER_CONSENT_REQUIRED");
        }
    }

    /** Server ledger reservation, bound to one effect and frozen offer; zero-reward offers need none. */
    public static final class RewardReservation {
        public final String reservationId;
        public final String effectKey;
        public final String offerFingerprint;
        public final long reservedGrossPoints;
        public final boolean active;

        public RewardReservation(String reservationId, Offer offer, long reservedGrossPoints, boolean active) {
            this.reservationId = id(reservationId);
            require(offer != null, "MISSING_OFFER");
            this.effectKey = offer.settlementKey();
            this.offerFingerprint = offer.fingerprint;
            this.reservedGrossPoints = nonnegative(reservedGrossPoints);
            this.active = active;
        }

        /** Zero-reward offers have no budget relationship and must never carry a reservation. */
        public static void requireForOffer(Offer offer, RewardReservation reservation) {
            require(offer != null, "MISSING_OFFER");
            if (offer.grossReward() == 0) {
                require(reservation == null, "REWARD_RESERVATION_FORBIDDEN");
            } else {
                require(reservation != null, "REWARD_BUDGET_NOT_RESERVED");
                reservation.requireValid(offer);
            }
        }

        public void requireValid(Offer offer) {
            require(active && effectKey.equals(offer.settlementKey()) && offerFingerprint.equals(offer.fingerprint)
                    && reservedGrossPoints == offer.grossReward(), "REWARD_BUDGET_NOT_RESERVED");
        }
    }
}
