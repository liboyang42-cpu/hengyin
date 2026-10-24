package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.CoopPaymentRecoveryTarget;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IWxPaymentCallbackOutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

/** Retains an original verified success needing cash recovery; no SDK call, payment CAS or allocation. */
@Service
public class CoopClosedPaymentRecovery {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(CoopClosedPaymentRecovery.class);
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<CoopClosedPaymentRecovery> self;

    private final CmsTopicMapper topics;
    private final CoopSettlementOriginalMapper originals;
    private final CoopSettlementCashEvidenceMapper cash;
    private final WechatVerifiedPaymentFactService facts;
    private final IWxPaymentCallbackOutboxService outbox;
    public CoopClosedPaymentRecovery(CmsTopicMapper topics,CoopSettlementOriginalMapper originals,CoopSettlementCashEvidenceMapper cash,
            WechatVerifiedPaymentFactService facts,IWxPaymentCallbackOutboxService outbox) {
        this.topics=topics;this.originals=originals;this.cash=cash;this.facts=facts;this.outbox=outbox;
    }
    /** Caller is outside its business transaction, after B registerVerified. Durable manual work survives a later business rollback. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Exception.class)
    public boolean retainClosedSuccess(Long registrationId,WechatVerifiedPaymentFact.Reference reference) {
        requireTransaction();
        if (blockedTarget(registrationId,reference)==null) return true;
        String key="COOP_PAID_HALF_LATE_SUCCESS:"+reference.id()+":"+reference.hash();
        outbox.enqueue(WxPaymentCallbackOutbox.BIZ_TYPE_PAYMENT_QUERY,registrationId,key,true);
        WxPaymentCallbackOutbox retained=originals.selectClosedPaymentRecoveryForUpdate(key);
        if (retained==null || !registrationId.equals(retained.getBizId())
                || !Integer.valueOf(WxPaymentCallbackOutbox.BIZ_TYPE_PAYMENT_QUERY).equals(retained.getBizType())
                || !key.equals(retained.getIdempotencyKey()) || !Boolean.TRUE.equals(retained.getMoneyAffecting())
                || !Integer.valueOf(WxPaymentCallbackOutbox.STATE_MANUAL_REQUIRED).equals(retained.getState())) {
            throw new IllegalStateException("CLOSED_PAYMENT_RECOVERY_RECEIPT_CONFLICT");
        }
        return false; // A must not acknowledge business completion or run the repair/CAS.
    }
    /** Recheck inside A's topic-first business transaction; the outside observation is not a lease/proof of admission. */
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void requireCashAdmission(Long registrationId,WechatVerifiedPaymentFact.Reference reference) {
        requireTransaction();
        if (blockedTarget(registrationId,reference)!=null) {
            // No new transaction while the caller holds topic/registration locks. Retain only after rollback releases them.
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status!=STATUS_ROLLED_BACK) return;
                        try { self.getObject().retainClosedSuccess(registrationId,reference); }
                        catch (RuntimeException unavailable) {
                            // Official B fact/inbox are still durable; a repeated original callback retries this same identity.
                            log.error("CLOSED_PAYMENT_RECOVERY_RETENTION_FAILED registrationId={} factId={}",registrationId,reference.id(),unavailable);
                        }
                    }
                });
            }
            throw new IllegalStateException("TOPIC_CLOSED_PAID_HALF_STATE_RECOVERY_REQUIRED");
        }
    }
    private CoopPaymentRecoveryTarget blockedTarget(Long id,WechatVerifiedPaymentFact.Reference reference) {
        WechatVerifiedPaymentFact proof=facts.requireVerified(reference).facts();proof.requireValid();
        if (proof.kind()!=WechatVerifiedPaymentFact.Kind.PAYMENT) throw new IllegalArgumentException("Original payment SUCCESS required");
        CoopPaymentRecoveryTarget observed=originals.selectPaymentRecoveryTarget(id);
        if (observed==null || !Objects.equals(id,observed.getRegistrationId()) || !Boolean.TRUE.equals(observed.getRelationPresent())) throw new IllegalStateException("PAYMENT_RECOVERY_RELATION_MISSING");
        if (!Objects.equals(observed.getRegistrationNo(),proof.businessOutTradeNo())) throw new IllegalStateException("PAYMENT_RECOVERY_ORIGINAL_IDENTITY_CONFLICT");
        if (observed.getTopicId()==null) return null; // Existing, actual standalone activity; A keeps its own admission rules.
        CmsTopic topic=topics.selectCmsTopicByIdForUpdate(observed.getTopicId());
        if (topic==null || !observed.getTopicId().equals(topic.getId())) throw new IllegalStateException("PAYMENT_RECOVERY_TOPIC_MISSING");
        CoopPaymentRecoveryTarget current=originals.selectPaymentRecoveryTargetForUpdate(id);
        if (current==null || !Objects.equals(id,current.getRegistrationId()) || !Boolean.TRUE.equals(current.getRelationPresent()) || !Objects.equals(current.getTopicId(),topic.getId())
                || !Objects.equals(current.getRegistrationNo(),proof.businessOutTradeNo())) throw new IllegalStateException("PAYMENT_RECOVERY_SCOPE_CHANGED");
        if (current.getWechatPaymentAmount()==null || current.getWechatPaymentAmount().movePointRight(2).longValueExact()!=proof.amountFen()
                || current.getTransactionId()!=null && !current.getTransactionId().isBlank() && !current.getTransactionId().equals(proof.transactionId())) {
            throw new IllegalStateException("PAYMENT_RECOVERY_ORIGINAL_IDENTITY_CONFLICT");
        }
        CoopSettlementCashClose close=cash.selectCloseForUpdate(topic.getId());
        if (!Integer.valueOf(1).equals(topic.getIsSettlement()) && close==null) return null;
        // This slice addresses PAID 2/1, not the separate PAYING 1/1 confirmation guard.
        if (Integer.valueOf(2).equals(current.getPaymentStatus()) && Integer.valueOf(1).equals(current.getRegistrationStatus())) return current;
        if (Integer.valueOf(2).equals(current.getPaymentStatus()) && Integer.valueOf(2).equals(current.getRegistrationStatus())) {
            if (close!=null) {
                CoopSettlementCashContribution source=cash.selectContributionForUpdate(id);
                if (source==null || !close.getId().equals(source.getCashCloseId()) || source.getSalesBasisAmount()==null
                        || source.getSalesBasisAmount().compareTo(current.getWechatPaymentAmount())!=0) return current;
            }
            List<CoopSettlement> heads=originals.selectTopicHeadsForUpdate(topic.getId());
            if (heads==null) throw new IllegalStateException("PAYMENT_RECOVERY_HEADS_UNAVAILABLE");
            if (heads.stream().noneMatch(h->h!=null && Set.of("PLATFORM","MERCHANT_VERIFY","LEAD_FEE","INITIATOR_GUARANTEE","INITIATOR_MARGIN").contains(h.getLineType()==null?"":h.getLineType()))) return current;
        }
        return null;
    }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("CLOSED_PAYMENT_RECOVERY_TRANSACTION_REQUIRED");
    }
}
