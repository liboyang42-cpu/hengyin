package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.support.CoopOriginalAllocation;
import com.chengyinhub.business.service.support.RegistrationPaymentConsumptionReadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** G4d producer: copy real claim/detail amounts; allocate only the original aggregate accounting heads. */
@Service
public class CoopSettlementOriginalProducer {
    public static final String VERSION="CLASSIC_ORIGINAL_LARGEST_REMAINDER_V2";
    private final CmsTopicMapper topics;
    private final CoopSettlementOriginalMapper originals;
    private final CoopSettlementCashEvidenceMapper cash;
    private final CoopSettlementOriginalLedger ledger;
    private final RegistrationPaymentConsumptionReadService consumptions;
    private final WechatVerifiedPaymentFactService facts;
    private final RegistrationSeatMapper seats;
    private final CoopSettlementManualCases manual;
    private final ObjectMapper json=new ObjectMapper();
    public CoopSettlementOriginalProducer(CmsTopicMapper topics, CoopSettlementOriginalMapper originals,
            CoopSettlementCashEvidenceMapper cash, CoopSettlementOriginalLedger ledger,
            RegistrationPaymentConsumptionReadService consumptions, WechatVerifiedPaymentFactService facts,
            RegistrationSeatMapper seats, CoopSettlementManualCases manual) {
        this.topics=topics;this.originals=originals;this.cash=cash;this.ledger=ledger;
        this.consumptions=consumptions;this.facts=facts;this.seats=seats;this.manual=manual;
    }
    public void requireNoManualCase(Long topicId) { manual.requireNoOpenCase(topicId); }

    /** Caller immediately throws the original conservation failure; receipt is saved only after physical rollback. */
    public void retainNegativeMargin(CmsTopic topic, BigDecimal sales, BigDecimal platform,
            BigDecimal guarantee, BigDecimal merchant, BigDecimal margin, List<CoopSettlement> plannedHeads) {
        Map<String,Object> snapshot=new TreeMap<>(); snapshot.put("topicId",topic.getId());snapshot.put("version",VERSION);
        snapshot.put("sales",sales);snapshot.put("platform",platform);snapshot.put("guarantee",guarantee);
        snapshot.put("merchant",merchant);snapshot.put("organizerMargin",margin);snapshot.put("originalMerchantHeads",plannedHeads);
        snapshot.put("initiator",topic.getMemberId());snapshot.put("platformRate",topic.getFeePlatformRate());
        snapshot.put("guaranteeBaseRate",topic.getFeeGuaranteeBaseRate());snapshot.put("guaranteeStepRate",topic.getFeeGuaranteeStepRate());
        snapshot.put("guaranteeMaxMerchant",topic.getFeeGuaranteeMaxMerchant());
        manual.afterRollback(topic.getId(),encode(snapshot),money(margin));
    }

    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void freezeOriginals(CmsTopic observed, TopicSettlementCashBasis basis, Date frozenAt) {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw repair("ORIGINAL_PRODUCER_TRANSACTION_REQUIRED");
        if(observed==null || observed.getId()==null || basis==null || frozenAt==null) throw repair("ORIGINAL_PRODUCER_SCOPE_REQUIRED");
        CmsTopic topic=topics.selectCmsTopicByIdForUpdate(observed.getId());
        if(topic==null || !observed.getId().equals(topic.getId())) throw repair("ORIGINAL_TOPIC_FENCE_REQUIRED");
        if(!Integer.valueOf(1).equals(topic.getIsSettlement())) throw repair("ORIGINAL_SETTLEMENT_CLAIM_REQUIRED");
        if(topic.getMemberId()==null || topic.getFeePlatformRate()==null || topic.getFeeGuaranteeBaseRate()==null
                || topic.getFeeGuaranteeStepRate()==null || topic.getFeeGuaranteeMaxMerchant()==null) throw repair("FROZEN_TOPIC_FIELDS_REQUIRED");
        manual.requireNoOpenCase(topic.getId());
        List<CmsRegistration> current=originals.selectOriginalRegistrationsForUpdate(topic.getId());
        if(current==null) throw repair("CURRENT_ORIGINAL_ROWS_REQUIRED");
        Map<Long,CmsRegistration> rows=new TreeMap<>();
        for(CmsRegistration row:current) {
            if(row==null || row.getId()==null || !Long.valueOf(1).equals(row.getOwnerType()) || !topic.getId().equals(row.getOwnerId()))
                throw repair("ORIGINAL_SOURCE_SCOPE_CONFLICT");
            if(Integer.valueOf(1).equals(row.getPaymentStatus()) || Integer.valueOf(2).equals(row.getPaymentStatus()) && Integer.valueOf(1).equals(row.getRegistrationStatus()))
                throw repair("ORIGINAL_PAYMENT_UNRESOLVED");
            if(Integer.valueOf(2).equals(row.getPaymentStatus()) && Integer.valueOf(2).equals(row.getRegistrationStatus())) {
                if(row.getRegistrationNo()==null || row.getRegistrationNo().isBlank() || row.getMemberId()==null
                        || row.getPayableAmount()==null || row.getPayableAmount().signum()<0 || rows.put(row.getId(),row)!=null)
                    throw repair("ORIGINAL_CASH_IDENTITY_REQUIRED");
            }
        }
        Map<Long,Long> payerByRow=new TreeMap<>(); Map<Long,BigDecimal> weights=new TreeMap<>();
        for(CmsRegistration row:rows.values()) {
            List<CmsRegistration> payers;
            if(row.getParentOrderNo()==null) payers=List.of(row);
            else payers=rows.values().stream().filter(p->row.getParentOrderNo().equals(p.getParentOrderNo())
                    && row.getMemberId().equals(p.getMemberId()) && positive(p.getWechatPaymentAmount())).toList();
            if(payers.size()!=1 || !positive(payers.get(0).getWechatPaymentAmount())) throw repair("ORIGINAL_PAYER_NOT_UNIQUE");
            Long payer=payers.get(0).getId();payerByRow.put(row.getId(),payer);
            weights.merge(payer,money(row.getPayableAmount()),BigDecimal::add);
        }
        BigDecimal sales=weights.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add);
        if(sales.compareTo(basis.getTotalSales())!=0 || sales.signum()<=0) throw repair("ORIGINAL_LOCKED_CASH_SET_CHANGED");
        Map<Long,WechatVerifiedPaymentFact.Reference> payments=new TreeMap<>();
        for(var entry:weights.entrySet()) {
            CmsRegistration payer=rows.get(entry.getKey());
            if(!sameAmount(payer.getWechatPaymentAmount(),entry.getValue()) || payer.getTransactionId()==null) throw repair("MIXED_OR_UNPROVEN_ORIGINAL_CASH");
            var consumed=consumptions.findConsumedPayment(payer.getId());
            if(consumed==null) throw repair("ORIGINAL_CONSUMPTION_READ_REQUIRED");
            WechatVerifiedPaymentFact.Reference reference=consumed.isPresent()?consumed.get().reference():originals.selectPaymentVerifiedReferenceForUpdate(payer.getId());
            if(reference==null) throw repair("ORIGINAL_PAYMENT_RECEIPT_REQUIRED");
            var paid=facts.requireVerified(reference).facts();
            if(paid.kind()!=WechatVerifiedPaymentFact.Kind.PAYMENT || !payer.getRegistrationNo().equals(paid.businessOutTradeNo())
                    || !payer.getTransactionId().equals(paid.transactionId()) || paid.amountFen()!=paid.totalFen()
                    || paid.totalFen()!=entry.getValue().movePointRight(2).longValueExact()) throw repair("ORIGINAL_PAYMENT_IDENTITY_CONFLICT");
            if(consumed.isPresent() && (!payer.getRegistrationNo().equals(consumed.get().registrationNo())
                    || !payer.getTransactionId().equals(consumed.get().transactionId()))) throw repair("ORIGINAL_CONSUMPTION_IDENTITY_CONFLICT");
            payments.put(payer.getId(),reference);
        }
        List<CoopSettlement> heads=originals.selectTopicHeadsForUpdate(topic.getId());
        if(heads==null || heads.isEmpty()) throw repair("ORIGINAL_HEADS_REQUIRED");
        Map<String,CoopSettlement> accounting=new HashMap<>(); List<CoopSettlementOriginalComponent> components=new ArrayList<>();
        Map<Long,BigDecimal> used=new TreeMap<>(); weights.keySet().forEach(id->used.put(id,BigDecimal.ZERO.setScale(2)));
        Set<Long> verifiedMerchants=new HashSet<>();
        for(CoopSettlement head:heads) {
            if(head==null || head.getId()==null || !topic.getId().equals(head.getTopicId()) || head.getAmount()==null || head.getLineType()==null) throw repair("ORIGINAL_HEAD_SCOPE_CONFLICT");
            if(head.getAmount().signum()<0) throw repair("NEGATIVE_ORIGINAL_HEAD");
            if(Set.of("PLATFORM","INITIATOR_GUARANTEE","INITIATOR_MARGIN").contains(head.getLineType())) {
                if(accounting.put(head.getLineType(),head)!=null) throw repair("DUPLICATE_ORIGINAL_ACCOUNTING_HEAD");
            } else if(Set.of("MERCHANT_VERIFY","LEAD_FEE").contains(head.getLineType())) {
                List<CoopSettlementDetail> details=originals.selectOriginalDetailsForHeadForUpdate(head.getId());
                if(details==null || details.isEmpty()) throw repair("ORIGINAL_CLAIM_DETAILS_REQUIRED");
                BigDecimal sum=BigDecimal.ZERO;
                for(CoopSettlementDetail detail:details) {
                    Long payer=payerByRow.get(detail.getRegistrationId());
                    if(payer==null || detail.getClaimId()==null || detail.getOriginAmount()==null || detail.getOriginAmount().signum()<0)
                        throw repair("ORIGINAL_CLAIM_SOURCE_CONFLICT");
                    components.add(component(head,detail.getRegistrationId(),payer,money(detail.getOriginAmount()),detail.getClaimId(),weights,payments));
                    used.merge(payer,money(detail.getOriginAmount()),BigDecimal::add);sum=sum.add(detail.getOriginAmount());
                }
                if(sum.compareTo(head.getAmount())!=0) throw repair("ORIGINAL_CLAIM_HEAD_CONSERVATION");
                if("MERCHANT_VERIFY".equals(head.getLineType()) && sum.signum()>0) verifiedMerchants.add(head.getMerchantId());
            } else throw repair("ORIGINAL_HEAD_KIND_UNPROVEN:"+head.getLineType());
        }
        for(String kind:List.of("PLATFORM","INITIATOR_GUARANTEE")) {
            CoopSettlement head=accounting.get(kind);if(head==null) throw repair("ORIGINAL_ACCOUNTING_HEAD_REQUIRED:"+kind);
            for(var share:CoopOriginalAllocation.allocate(money(head.getAmount()),weights).entrySet()) {
                components.add(component(head,share.getKey(),share.getKey(),share.getValue(),null,weights,payments));
                used.merge(share.getKey(),share.getValue(),BigDecimal::add);
            }
        }
        CoopSettlement margin=accounting.get("INITIATOR_MARGIN");BigDecimal marginTotal=BigDecimal.ZERO;
        for(var entry:weights.entrySet()) {
            BigDecimal residual=entry.getValue().subtract(used.get(entry.getKey()));
            if(residual.signum()<0) {
                Map<String,Object> source=new TreeMap<>();source.put("topicId",topic.getId());source.put("version",VERSION);
                source.put("payerRegistrationId",entry.getKey());source.put("originalCash",entry.getValue());source.put("allocatedCost",used.get(entry.getKey()));
                source.put("cashByPayer",weights);source.put("allocatedCostByPayer",used);source.put("originalComponents",components);
                source.put("paymentFactId",payments.get(entry.getKey()).id());source.put("paymentFactHash",payments.get(entry.getKey()).hash());
                manual.afterRollback(topic.getId(),encode(source),residual);
                throw repair("NEGATIVE_ORGANIZER_MARGIN_MANUAL_REQUIRED");
            }
            marginTotal=marginTotal.add(residual);
            if(margin!=null) components.add(component(margin,entry.getKey(),entry.getKey(),residual,null,weights,payments));
        }
        if(margin==null && marginTotal.signum()!=0 || margin!=null && marginTotal.compareTo(margin.getAmount())!=0) throw repair("ORIGINAL_MARGIN_HEAD_CONSERVATION");
        List<CoopSettlementCashContribution> contributions=new ArrayList<>(); Map<Long,Object> seatSnapshot=new TreeMap<>();
        for(CmsRegistration row:rows.values()) {
            Long payer=payerByRow.get(row.getId());
            CoopSettlementCashContribution contribution=new CoopSettlementCashContribution();contribution.setRegistrationId(row.getId());contribution.setPayerRegistrationId(payer);
            contribution.setRegistrationNo(row.getRegistrationNo());contribution.setPayerOrderNo(rows.get(payer).getRegistrationNo());
            contribution.setOwnerType(1);contribution.setOwnerId(topic.getId());contribution.setSourceTopicId(topic.getId());
            contribution.setSnapshotPaymentStatus(row.getPaymentStatus());contribution.setSnapshotRegistrationStatus(row.getRegistrationStatus());
            contribution.setSnapshotVerificationStatus(row.getVerificationStatus());contribution.setSnapshotPayableAmount(money(row.getPayableAmount()));
            contribution.setSalesBasisAmount(money(row.getPayableAmount()));
            Map<String,Object> registration=new TreeMap<>();registration.put("wechatPaymentAmount",row.getWechatPaymentAmount());registration.put("transactionId",row.getTransactionId());
            registration.put("memberId",row.getMemberId());registration.put("parentOrderNo",row.getParentOrderNo());registration.put("orderNum",row.getOrderNum());
            contribution.setRegistrationSnapshot(encode(registration));contribution.setSourceFingerprint(WechatVerifiedPaymentFact.sha256(contribution.getRegistrationSnapshot()));
            contributions.add(contribution);
            List<RegistrationSeat> actualSeats=seats.selectByRegistrationForUpdate(row.getId());
            if(actualSeats==null) throw repair("ORIGINAL_SEAT_SNAPSHOT_REQUIRED");
            seatSnapshot.put(row.getId(),actualSeats);
        }
        CoopSettlementCashClose close=new CoopSettlementCashClose();close.setTopicId(topic.getId());close.setCommercialContextType("TOPIC_EDITION");close.setCommercialContextId(topic.getId());
        close.setCloseKind("POSITIVE_SALES");close.setInitiatorMemberId(topic.getMemberId());close.setOriginalSalesBasis(sales);
        close.setOriginalContributionCount(contributions.size());close.setOriginalVerifiedMerchantCount(verifiedMerchants.size());
        close.setPlatformRate(topic.getFeePlatformRate());close.setGuaranteeBaseRate(topic.getFeeGuaranteeBaseRate());
        close.setGuaranteeStepRate(topic.getFeeGuaranteeStepRate());close.setGuaranteeMaxMerchant(topic.getFeeGuaranteeMaxMerchant());close.setOriginCalculationVersion(VERSION);
        CoopSettlementCashClose prior=cash.selectCloseForUpdate(topic.getId());close.setClosedAt(prior==null?frozenAt:prior.getClosedAt());
        Map<String,Object> contract=new TreeMap<>();contract.put("version",VERSION);contract.put("payerCash",weights);contract.put("verifiedSeatFacts",seatSnapshot);
        contract.put("headIds",heads.stream().map(CoopSettlement::getId).sorted().toList());
        // Original line classification is retained; this producer never decides cost refundability.
        Map<Long,Object> headTerms=new TreeMap<>();
        for(CoopSettlement head:heads) {
            Map<String,Object> terms=new TreeMap<>();terms.put("lineType",head.getLineType());terms.put("receiver",head.getMerchantId());
            terms.put("shareMode",head.getShareMode());terms.put("shareRate",head.getShareRate());terms.put("fixedFee",head.getFixedFee());
            terms.put("originalAmount",head.getAmount());terms.put("coopOrderId",head.getCoopOrderId());headTerms.put(head.getId(),terms);
        }
        contract.put("originalHeadTerms",headTerms);
        contract.put("paymentReferences",payments);close.setContractSnapshot(encode(contract));close.setSourceFingerprint(WechatVerifiedPaymentFact.sha256(close.getContractSnapshot()));
        ledger.freezeOriginals(close,contributions,components);
    }
    private CoopSettlementOriginalComponent component(CoopSettlement head,Long source,Long payer,BigDecimal amount,Long claim,
            Map<Long,BigDecimal> cash,Map<Long,WechatVerifiedPaymentFact.Reference> payments) {
        CoopSettlementOriginalComponent c=new CoopSettlementOriginalComponent();c.setSourceRegistrationId(source);c.setPayerRegistrationId(payer);c.setSettlementId(head.getId());
        c.setReceiverKind("PLATFORM".equals(head.getLineType())?"ACCOUNTING":"MEMBER");c.setReceiverMemberId(head.getMerchantId());c.setLineType(head.getLineType());
        c.setOriginalAmount(amount);c.setOriginalOrderCash(cash.get(payer));c.setSourceClaimId(claim);c.setCalculationVersion(VERSION);
        c.setPaymentFactId(payments.get(payer).id());c.setPaymentFactHash(payments.get(payer).hash());return c;
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception failed){throw new IllegalStateException("ORIGINAL_SNAPSHOT_ENCODING_FAILED",failed);}}
    private static BigDecimal money(BigDecimal value){if(value==null)throw repair("EXACT_MONEY_REQUIRED");return value.setScale(2,RoundingMode.UNNECESSARY);}
    private static boolean positive(BigDecimal value){return value!=null && value.signum()>0;}
    private static boolean sameAmount(BigDecimal a,BigDecimal b){return a!=null && b!=null && a.compareTo(b)==0;}
    private static IllegalStateException repair(String message){return new IllegalStateException("MANUAL_REPAIR:"+message);}
}
