package com.chengyinhub.business.service.fulfillment;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.common.constant.EntitlementInvalidReason;

/**
 * C07 exclusive adapter: held multi-merchant entitlement projection.
 *
 * <p>Mirrors {@code registration_entitlement} occupancy: status 0 (应得/已售未核销)
 * occupies capacity; 1 already redeemed; 2 invalid. Projects a hold keyed by the
 * original requestId. Does not call {@code IPerkLedgerService#reserveEntitlements}
 * or {@code #redeemChapter}, and does not write the mapper.
 *
 * <p>原权益行走既有 {@link IPerkLedgerService#listEntitlements}；原核销流水走
 * {@link IPerkLedgerService#listRedemptions}。缺端口 WAITING_SOURCE。
 * 商家/有效期仍是扫码上下文，不从 entitlement 表发明 merchantId。不调用 redeemChapter。
 */
public final class EntitlementHoldProjection {

    public static final String PERK_LIST = "perkLedger.listEntitlements";
    public static final String PERK_REDEMPTIONS = "perkLedger.listRedemptions";

    /** {@code registration_entitlement.status}: 已售未核销, occupies capacity. */
    public static final int STATUS_HELD = 0;
    public static final int STATUS_REDEEMED = 1;
    public static final int STATUS_INVALID = 2;

    public enum Outcome {
        HELD,
        REDEEMED,
        INVALID,
        UNKNOWN
    }

    /**
     * One chapter hold for one original issue request. {@code merchantId} is the
     * fulfilling store for that chapter, not a transfer recipient.
     */
    public record HoldFact(
            String originalRequestId,
            Long registrationId,
            Long chapterId,
            Long merchantId,
            Integer status,
            Instant validEndExclusive,
            String invalidReason) {

        public HoldFact {
            originalRequestId = FulfillmentEvidence.blankToNull(originalRequestId);
            invalidReason = invalidReason == null ? "" : invalidReason;
        }
    }

    public record HoldView(
            String originalRequestId,
            Outcome outcome,
            boolean occupiesCapacity,
            boolean executedReserve,
            boolean executedRedeem,
            boolean executedAward) {

        public HoldView {
            originalRequestId = FulfillmentEvidence.blankToNull(originalRequestId);
            outcome = outcome == null ? Outcome.UNKNOWN : outcome;
        }
    }

    private final ConcurrentHashMap<String, HoldView> originalIntentByRequestId =
            new ConcurrentHashMap<String, HoldView>();
    private final ConcurrentHashMap<String, Integer> occupyCountByRequestId =
            new ConcurrentHashMap<String, Integer>();
    private final OriginalEntitlementSource source;
    private final OriginalRedemptionSource redemptions;

    public EntitlementHoldProjection() {
        this(null);
    }

    public EntitlementHoldProjection(OriginalEntitlementSource source) {
        this(source, null);
    }

    public EntitlementHoldProjection(OriginalEntitlementSource source, OriginalRedemptionSource redemptions) {
        this.source = source;
        this.redemptions = redemptions;
    }

    /**
     * Project the hold that already belongs to {@code originalRequestId}.
     * Missing source is UNKNOWN and does not mint a new request.
     * Non-UNKNOWN original is unique; UNKNOWN observation does not occupy.
     */
    public HoldView project(HoldFact fact, Instant serverNow) {
        Objects.requireNonNull(serverNow, "serverNow");
        if (fact != null && fact.originalRequestId() != null) {
            HoldView original = originalIntentByRequestId.get(fact.originalRequestId());
            if (original != null) {
                return original;
            }
        }
        HoldView view = compute(fact, serverNow);
        if (view.originalRequestId() != null && view.outcome() != Outcome.UNKNOWN) {
            originalIntentByRequestId.putIfAbsent(view.originalRequestId(), view);
            if (view.occupiesCapacity()) {
                occupyCountByRequestId.putIfAbsent(view.originalRequestId(), 1);
            }
        }
        return view;
    }

    public HoldView requery(HoldFact fact, Instant serverNow) {
        return project(fact, serverNow);
    }

    public HoldView recoverOriginal(String originalRequestId) {
        String id = FulfillmentEvidence.blankToNull(originalRequestId);
        if (id == null) {
            return unknown(null);
        }
        HoldView original = originalIntentByRequestId.get(id);
        return original == null ? unknown(id) : original;
    }

    /**
     * 从既有权益账回读一章。缺 {@link IPerkLedgerService#listEntitlements} 为 WAITING_SOURCE。
     * 未见过的 registration/chapter 保持 UNKNOWN，不占容量。不调用 reserve/redeem。
     */
    public HoldView projectFromLedger(String originalRequestId, Long registrationId, Long chapterId,
            Long merchantId, Instant validEndExclusive, Instant serverNow) {
        Objects.requireNonNull(serverNow, "serverNow");
        if (source == null) {
            throw new WaitingSourceException(PERK_LIST);
        }
        if (registrationId == null || registrationId.longValue() <= 0L) {
            throw new WaitingSourceException(PERK_LIST);
        }
        List<RegistrationEntitlement> rows = source.listEntitlements(registrationId);
        RegistrationEntitlement match = findChapter(rows, chapterId);
        if (match == null) {
            return recoverOriginal(originalRequestId);
        }
        return project(new HoldFact(originalRequestId, registrationId, chapterId, merchantId,
                match.getStatus(), validEndExclusive, match.getInvalidReason()), serverNow);
    }

    /**
     * 从既有核销流水回读。缺 {@link IPerkLedgerService#listRedemptions} 为 WAITING_SOURCE。
     * 未见过的章保持 UNKNOWN，不占容量，不调用 {@code redeemChapter}。
     * 已撤销流水不是生效原核销。
     */
    public HoldView recoverFromRedemptions(String originalRequestId, Long registrationId, Long chapterId,
            Instant serverNow) {
        Objects.requireNonNull(serverNow, "serverNow");
        if (redemptions == null) {
            throw new WaitingSourceException(PERK_REDEMPTIONS);
        }
        if (registrationId == null || registrationId.longValue() <= 0L) {
            throw new WaitingSourceException(PERK_REDEMPTIONS);
        }
        List<Redemption> rows = redemptions.listRedemptions(registrationId);
        Redemption match = findActiveRedemption(rows, chapterId);
        if (match == null) {
            return recoverOriginal(originalRequestId);
        }
        return project(new HoldFact(originalRequestId, registrationId, chapterId, match.getMerchantId(),
                STATUS_REDEEMED, null, ""), serverNow);
    }

    private static Redemption findActiveRedemption(List<Redemption> rows, Long chapterId) {
        if (rows == null || chapterId == null) {
            return null;
        }
        for (Redemption row : rows) {
            if (row == null || !chapterId.equals(row.getChapterId()) || row.getReversedAt() != null) {
                continue;
            }
            if ("ACTIVE".equals(row.getStatus())) {
                return row;
            }
        }
        return null;
    }

    public int occupyCount(String originalRequestId) {
        String id = FulfillmentEvidence.blankToNull(originalRequestId);
        if (id == null) {
            return 0;
        }
        Integer count = occupyCountByRequestId.get(id);
        return count == null ? 0 : count;
    }

    private static HoldView compute(HoldFact fact, Instant serverNow) {
        if (fact == null) {
            return unknown(null);
        }
        String requestId = fact.originalRequestId();
        if (requestId == null || fact.registrationId() == null || fact.registrationId() <= 0L
                || fact.chapterId() == null || fact.chapterId() <= 0L
                || fact.merchantId() == null || fact.merchantId() <= 0L) {
            return unknown(requestId);
        }
        if (fact.status() == null) {
            return unknown(requestId);
        }
        if (fact.status() == STATUS_REDEEMED) {
            return new HoldView(requestId, Outcome.REDEEMED, false, false, false, false);
        }
        if (fact.status() == STATUS_INVALID) {
            return new HoldView(requestId, Outcome.INVALID, false, false, false, false);
        }
        if (fact.status() != STATUS_HELD) {
            return unknown(requestId);
        }
        if (fact.validEndExclusive() == null) {
            return unknown(requestId);
        }
        if (!serverNow.isBefore(fact.validEndExclusive())) {
            return new HoldView(requestId, Outcome.INVALID, false, false, false, false);
        }
        if (!fact.invalidReason().isEmpty()
                && (EntitlementInvalidReason.REFUND.equals(fact.invalidReason())
                || EntitlementInvalidReason.ORGANIZER_CANCEL.equals(fact.invalidReason())
                || EntitlementInvalidReason.FULFILLMENT_GAP.equals(fact.invalidReason())
                || EntitlementInvalidReason.MANUAL.equals(fact.invalidReason()))) {
            return new HoldView(requestId, Outcome.INVALID, false, false, false, false);
        }
        return new HoldView(requestId, Outcome.HELD, true, false, false, false);
    }

    private static HoldView unknown(String requestId) {
        return new HoldView(requestId, Outcome.UNKNOWN, false, false, false, false);
    }

    private static RegistrationEntitlement findChapter(List<RegistrationEntitlement> rows, Long chapterId) {
        if (rows == null || chapterId == null) {
            return null;
        }
        for (RegistrationEntitlement row : rows) {
            if (row != null && chapterId.equals(row.getChapterId())) {
                return row;
            }
        }
        return null;
    }

    /** 只读既有权益账；不 reserve / redeem。 */
    public interface OriginalEntitlementSource {
        List<RegistrationEntitlement> listEntitlements(Long registrationId);
    }

    /** 只读既有核销流水；不 redeem / reverse。 */
    public interface OriginalRedemptionSource {
        List<Redemption> listRedemptions(Long registrationId);
    }

    public static final class PerkLedgerOriginalRedemptionSource implements OriginalRedemptionSource {
        private final IPerkLedgerService ledger;

        public PerkLedgerOriginalRedemptionSource(IPerkLedgerService ledger) {
            this.ledger = ledger;
        }

        @Override
        public List<Redemption> listRedemptions(Long registrationId) {
            if (ledger == null) {
                throw new WaitingSourceException(PERK_REDEMPTIONS);
            }
            return ledger.listRedemptions(registrationId);
        }
    }

    public static final class PerkLedgerOriginalEntitlementSource implements OriginalEntitlementSource {
        private final IPerkLedgerService ledger;

        public PerkLedgerOriginalEntitlementSource(IPerkLedgerService ledger) {
            this.ledger = ledger;
        }

        @Override
        public List<RegistrationEntitlement> listEntitlements(Long registrationId) {
            if (ledger == null) {
                throw new WaitingSourceException(PERK_LIST);
            }
            return ledger.listEntitlements(registrationId);
        }
    }

    public static final class WaitingSourceException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final String waitingCapability;

        public WaitingSourceException(String waitingCapability) {
            super("WAITING_SOURCE " + waitingCapability);
            this.waitingCapability = waitingCapability;
        }

        public String getWaitingCapability() {
            return waitingCapability;
        }
    }
}
