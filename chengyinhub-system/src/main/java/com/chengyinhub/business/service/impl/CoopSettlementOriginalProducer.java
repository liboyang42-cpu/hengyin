package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.TopicSettlementCashBasis;
import com.chengyinhub.business.mapper.CoopSettlementOriginalMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopSettlementCashEvidenceMapper;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.util.*;

/** Copies already persisted original amounts for the exact one-order case. No allocation/fee algorithm. */
@Service
public class CoopSettlementOriginalProducer {
    private final CmsTopicMapper topics;
    private final CoopSettlementOriginalMapper originals;
    private final CoopSettlementCashEvidenceMapper cash;
    private final CoopSettlementOriginalLedger ledger;
    private final ObjectMapper json=new ObjectMapper();
    public CoopSettlementOriginalProducer(CmsTopicMapper topics,CoopSettlementOriginalMapper originals,CoopSettlementCashEvidenceMapper cash,CoopSettlementOriginalLedger ledger) {
        this.topics=topics;this.originals=originals;this.cash=cash;this.ledger=ledger;
    }
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public String freezeSingleOrder(CmsTopic topic,TopicSettlementCashBasis basis,Date frozenAt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("ORIGINAL_PRODUCER_TRANSACTION_REQUIRED");
        if (topic==null || topic.getId()==null || basis==null || frozenAt==null) throw new IllegalArgumentException("Original producer scope required");
        // Repeat the same topic fence even for a direct bean caller; never acquire registration first.
        CmsTopic locked=topics.selectCmsTopicByIdForUpdate(topic.getId());
        if (locked==null || !topic.getId().equals(locked.getId())) throw new IllegalStateException("ORIGINAL_TOPIC_FENCE_REQUIRED");
        topic=locked;
        List<CmsRegistration> rows=originals.selectOriginalRegistrationsForUpdate(topic.getId());
        if (rows==null) throw new IllegalStateException("CURRENT_ORIGINAL_ROWS_REQUIRED");
        if (rows.size()!=1) return "NOT_ENABLED_ORDER_SET_REQUIRES_ALLOCATION";
        CmsRegistration row=rows.get(0);
        if (!Long.valueOf(1).equals(row.getOwnerType()) || !topic.getId().equals(row.getOwnerId())) throw new IllegalStateException("ORIGINAL_SOURCE_SCOPE_CONFLICT");
        if (!Integer.valueOf(2).equals(row.getPaymentStatus()) || !Integer.valueOf(2).equals(row.getRegistrationStatus())) return "NOT_ENABLED_ORIGINAL_PAYMENT_NOT_COMPLETED";
        if (row.getId()==null || row.getRegistrationNo()==null || row.getRegistrationNo().isBlank()
                || row.getTransactionId()==null || row.getTransactionId().isBlank()) return "NOT_ENABLED_ORIGINAL_PAYMENT_IDENTITY_MISSING";
        if (row.getParentOrderNo()!=null || !Long.valueOf(1).equals(row.getOrderNum())) return "NOT_ENABLED_PARENT_OR_MULTI_SEAT";
        if (row.getPayableAmount()==null || row.getPayableAmount().signum()<=0 || row.getWechatPaymentAmount()==null
                || row.getPayableAmount().compareTo(row.getWechatPaymentAmount())!=0) return "NOT_ENABLED_MIXED_OR_UNPROVEN_CASH";
        if (basis.getTotalSales().compareTo(row.getPayableAmount())!=0) throw new IllegalStateException("ORIGINAL_LOCKED_CASH_SET_CHANGED");
        if (topic.getMemberId()==null || topic.getFeePlatformRate()==null || topic.getFeeGuaranteeBaseRate()==null
                || topic.getFeeGuaranteeStepRate()==null || topic.getFeeGuaranteeMaxMerchant()==null) return "NOT_ENABLED_FROZEN_TOPIC_FIELDS_MISSING";
        WechatVerifiedPaymentFact.Reference payment=originals.selectPaymentVerifiedReferenceForUpdate(row.getId());
        if (payment==null) return "NOT_ENABLED_ORIGINAL_PAYMENT_RECEIPT_MISSING";
        List<CoopSettlement> heads=originals.selectTopicHeadsForUpdate(topic.getId());
        if (heads==null || heads.isEmpty()) throw new IllegalStateException("ORIGINAL_HEADS_REQUIRED");
        List<CoopSettlementOriginalComponent> components=new ArrayList<>();
        int verifiedMerchants=0;
        for (CoopSettlement head:heads) {
            if (head==null || !topic.getId().equals(head.getTopicId()) || head.getAmount()==null) throw new IllegalStateException("ORIGINAL_HEAD_SCOPE_CONFLICT");
            if (head.getAmount().signum()<0) return "NOT_ENABLED_NEGATIVE_MARGIN_RESPONSIBILITY";
            String line=head.getLineType();
            if (line==null) return "NOT_ENABLED_OTHER_HEAD_KIND";
            if ("DEPOSIT_DEDUCTION".equals(line)) return "NOT_ENABLED_DEPOSIT_SETOFF_ORIGINAL_ALLOCATION";
            if (!Set.of("PLATFORM","INITIATOR_GUARANTEE","INITIATOR_MARGIN","MERCHANT_VERIFY","LEAD_FEE").contains(line)) return "NOT_ENABLED_OTHER_HEAD_KIND";
            CoopSettlementOriginalComponent component=new CoopSettlementOriginalComponent();
            component.setSourceRegistrationId(row.getId());component.setPayerRegistrationId(row.getId());component.setSettlementId(head.getId());
            component.setReceiverKind("PLATFORM".equals(line)?"ACCOUNTING":"MEMBER");component.setReceiverMemberId(head.getMerchantId());
            component.setLineType(line);component.setOriginalAmount(head.getAmount());component.setOriginalOrderCash(row.getWechatPaymentAmount());
            component.setCalculationVersion("CLASSIC_SINGLE_ORDER_ORIGIN_V1");component.setPaymentFactId(payment.id());component.setPaymentFactHash(payment.hash());
            if ("MERCHANT_VERIFY".equals(line)||"LEAD_FEE".equals(line)) {
                List<CoopSettlementDetail> details=originals.selectOriginalDetailsForHeadForUpdate(head.getId());
                if (details==null || details.size()!=1) return "NOT_ENABLED_HEAD_REQUIRES_ORDER_ALLOCATION";
                CoopSettlementDetail detail=details.get(0);
                if (!row.getId().equals(detail.getRegistrationId()) || detail.getClaimId()==null || detail.getOriginAmount()==null
                        || detail.getOriginAmount().compareTo(head.getAmount())!=0) throw new IllegalStateException("ORIGINAL_CLAIM_AMOUNT_CONFLICT");
                component.setSourceClaimId(detail.getClaimId());
                if ("MERCHANT_VERIFY".equals(line) && head.getAmount().signum()>0) verifiedMerchants++;
            }
            components.add(component);
        }
        CoopSettlementCashClose close=new CoopSettlementCashClose(); close.setTopicId(topic.getId());close.setCommercialContextType("TOPIC_EDITION");
        close.setCommercialContextId(topic.getId());close.setCloseKind("POSITIVE_SALES");close.setInitiatorMemberId(topic.getMemberId());
        close.setOriginalSalesBasis(basis.getTotalSales());close.setOriginalContributionCount(1);close.setOriginalVerifiedMerchantCount(verifiedMerchants);
        close.setPlatformRate(topic.getFeePlatformRate());close.setGuaranteeBaseRate(topic.getFeeGuaranteeBaseRate());
        close.setGuaranteeStepRate(topic.getFeeGuaranteeStepRate());close.setGuaranteeMaxMerchant(topic.getFeeGuaranteeMaxMerchant());
        close.setOriginCalculationVersion("CLASSIC_SINGLE_ORDER_ORIGIN_V1");
        CoopSettlementCashClose prior=cash.selectCloseForUpdate(topic.getId());close.setClosedAt(prior==null?frozenAt:prior.getClosedAt());
        try {
            Map<String,Object> contract=new TreeMap<>();contract.put("kind","EXACT_SINGLE_ORDER_COPY");contract.put("paymentFactId",payment.id());
            contract.put("paymentFactHash",payment.hash());contract.put("registrationId",row.getId());contract.put("headIds",heads.stream().map(CoopSettlement::getId).sorted().toList());
            close.setContractSnapshot(json.writeValueAsString(contract));close.setSourceFingerprint(WechatVerifiedPaymentFact.sha256(close.getContractSnapshot()));
            CoopSettlementCashContribution contribution=new CoopSettlementCashContribution();contribution.setRegistrationId(row.getId());contribution.setPayerRegistrationId(row.getId());
            contribution.setRegistrationNo(row.getRegistrationNo());contribution.setPayerOrderNo(row.getRegistrationNo());contribution.setOwnerType(1);
            contribution.setOwnerId(topic.getId());contribution.setSourceTopicId(topic.getId());contribution.setSnapshotPaymentStatus(row.getPaymentStatus());
            contribution.setSnapshotRegistrationStatus(row.getRegistrationStatus());contribution.setSnapshotVerificationStatus(row.getVerificationStatus());
            contribution.setSnapshotPayableAmount(row.getPayableAmount());contribution.setSalesBasisAmount(row.getPayableAmount());
            contribution.setRegistrationSnapshot(json.writeValueAsString(Map.of("wechatPaymentAmount",row.getWechatPaymentAmount(),"transactionId",row.getTransactionId())));
            contribution.setSourceFingerprint(WechatVerifiedPaymentFact.sha256(contribution.getRegistrationSnapshot()));
            ledger.freezeOriginals(close,List.of(contribution),components);
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {throw new IllegalStateException("ORIGINAL_SNAPSHOT_ENCODING_FAILED",impossible);}
        return "FROZEN_EXACT_SINGLE_ORDER";
    }
}
