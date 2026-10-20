package com.chengyinhub.business.service.coupon;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.mapper.CouponTermsSnapshotMapper;
import com.chengyinhub.business.mapper.CouponTermsSnapshotMapper.Row;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.coupon.CouponTermsGrantAuthority.Intent;
import com.chengyinhub.business.service.coupon.CouponTermsGrantAuthority.Approval;
import java.time.Clock;
import java.util.Objects;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Private, dormant proposal. No bean, controller, factory or existing grant call site enables it.
 * Existing legacy issuance is unchanged and does not acquire invented historical snapshots.
 * The exact existing grant service and both mappers must share this transaction's datasource.
 */
public final class CouponTermsSnapshotService {
    public enum Mode { DISABLED, ISOLATED_REVIEW }
    public enum Availability { RECORDED, NOT_PROVIDED }
    public static final class Read {
        public final long historyId;
        public final Availability availability;
        public final CouponTermsSnapshot terms;
        private Read(long historyId, CouponTermsSnapshot terms) {
            this.historyId = historyId; this.terms = terms;
            this.availability = terms == null ? Availability.NOT_PROVIDED : Availability.RECORDED;
        }
    }
    private final Mode mode;
    private final CouponTermsSnapshotMapper mapper;
    private final ISmsCouponHistoryService originalGrant;
    private final CouponTermsGrantAuthority authority;
    private final TransactionTemplate transaction;
    private final Clock clock;
    public CouponTermsSnapshotService() { mode = Mode.DISABLED; mapper = null; originalGrant = null; authority = null; transaction = null; clock = Clock.systemUTC(); }
    public CouponTermsSnapshotService(Mode mode, CouponTermsSnapshotMapper mapper, ISmsCouponHistoryService originalGrant,
            CouponTermsGrantAuthority authority, PlatformTransactionManager manager, Clock clock) {
        this.mode = Objects.requireNonNull(mode); this.mapper = Objects.requireNonNull(mapper);
        this.originalGrant = Objects.requireNonNull(originalGrant); this.authority = Objects.requireNonNull(authority);
        this.clock = Objects.requireNonNull(clock); transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }
    private void enabled() { if (mode != Mode.ISOLATED_REVIEW) throw new IllegalStateException("Coupon snapshot proposal disabled"); }
    public Read recordNewGrant(final Intent intent) {
        enabled(); Objects.requireNonNull(intent);
        // An uncommitted upstream event cannot be promoted by joining its ambient transaction.
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Committed source receipt required outside ambient transaction");
        return transaction.execute(status -> {
            approve(intent);
            SmsCoupon definition = mapper.lockDefinition(intent.couponId, intent.issuerMemberId, intent.merchantId);
            if (definition == null) throw new IllegalStateException("Current owned merchant definition unavailable");
            Row replay = mapper.byNonce(intent.issuerMemberId, intent.nonce);
            if (replay != null) {
                if (!intent.digest().equals(replay.intentDigest)) throw new IllegalStateException("Nonce conflicts with original intent");
                approve(intent);
                return readRecorded(intent.beneficiaryMemberId, replay.historyId, replay);
            }
            if (mapper.bySource(intent.sourceReceiptId, intent.beneficiaryMemberId, intent.couponId) != null)
                throw new IllegalStateException("Source receipt already recorded under another nonce");
            CouponTermsSnapshot terms = CouponTermsSnapshot.fromDefinition(definition);
            if (!terms.getDigest().equals(intent.disclosedTermsDigest)) throw new IllegalStateException("Definition changed since disclosure");
            approve(intent); // Recheck after lock wait, with final clock sampled AFTER authority returns.
            Long historyId = originalGrant.grantCouponWithReceipt(intent.beneficiaryMemberId, definition);
            if (historyId == null || historyId <= 0) throw new IllegalStateException("Existing grant refused");
            Row row = new Row(); row.historyId = historyId; row.couponId = intent.couponId;
            row.issuerMemberId = intent.issuerMemberId; row.merchantId = intent.merchantId;
            row.actorMemberId = intent.actorMemberId; row.beneficiaryMemberId = intent.beneficiaryMemberId;
            row.nonce = intent.nonce; row.sourceReceiptId = intent.sourceReceiptId; row.intentDigest = intent.digest();
            row.termsJson = terms.getJson(); row.termsDigest = terms.getDigest(); row.recordedAt = clock.millis();
            if (mapper.insert(row) != 1) throw new IllegalStateException("Snapshot insert failed");
            approve(intent); // Failure after either insert rolls history, stock and snapshot back together.
            return readRecorded(intent.beneficiaryMemberId, historyId, row);
        });
    }
    private void approve(Intent intent) {
        Approval proof = authority.lockCurrentApproved(intent);
        long current = clock.millis();
        if (proof == null || proof.disposition != CouponTermsGrantAuthority.Disposition.COMMITTED_ELIGIBLE
                || !intent.digest().equals(proof.intentDigest) || current >= proof.validUntilEpochMillis)
            throw new IllegalStateException("Current committed source authorization not provided");
    }
    public Read readOwned(long beneficiary, long historyId) {
        enabled(); if (beneficiary <= 0 || historyId <= 0) throw new IllegalArgumentException("Owner and history required");
        SmsCouponHistory history = mapper.ownedHistory(beneficiary, historyId);
        if (history == null) return null;
        Row row = mapper.ownedSnapshot(beneficiary, historyId);
        if (row == null) return new Read(historyId, null); // Never borrow current parent terms or backfill.
        return readRecorded(beneficiary, historyId, row);
    }
    private Read readRecorded(long beneficiary, long historyId, Row row) {
        SmsCouponHistory history = mapper.ownedHistory(beneficiary, historyId);
        if (history == null || !Objects.equals(row.historyId, historyId) || !Objects.equals(row.beneficiaryMemberId, beneficiary)
                || !Objects.equals(history.getCouponId(), row.couponId)) throw new IllegalStateException("Recorded owner/history correlation mismatch");
        if (row.issuerMemberId == null || row.merchantId == null || row.actorMemberId == null || row.couponId == null
                || row.recordedAt == null || !new Intent(row.issuerMemberId, row.merchantId, row.actorMemberId,
                beneficiary, row.couponId, row.sourceReceiptId, row.nonce, row.termsDigest).digest().equals(row.intentDigest))
            throw new IllegalStateException("Recorded source/actor correlation mismatch");
        CouponTermsSnapshot terms = CouponTermsSnapshot.recorded(row.termsJson, row.termsDigest);
        Long start = history.getStartTime() == null ? null : history.getStartTime().getTime();
        Long end = history.getEndTime() == null ? null : history.getEndTime().getTime();
        if (!Objects.equals(terms.getStartTime(), start) || !Objects.equals(terms.getEndTime(), end))
            throw new IllegalStateException("Recorded history validity mismatch");
        return new Read(historyId, terms);
    }
}
