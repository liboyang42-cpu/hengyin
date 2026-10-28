package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.CoopSettlementManualCaseMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

/** Control evidence only. Never debits, creates debt, reopens or approves a financial outcome. */
@Service
public class CoopSettlementManualCases {
    private final CoopSettlementManualCaseMapper rows;
    private final PlatformTransactionManager transactions;
    public CoopSettlementManualCases(CoopSettlementManualCaseMapper rows, PlatformTransactionManager transactions) {
        this.rows=rows; this.transactions=transactions;
    }
    public void requireNoOpenCase(Long topicId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("ORIGINAL_TOPIC_TRANSACTION_REQUIRED");
        if(rows.selectOpenTopicForUpdate(topicId)!=null) throw new IllegalStateException("NEGATIVE_MARGIN_MANUAL_REQUIRED:topicId="+topicId);
    }
    public void afterRollback(Long topicId, String snapshot, BigDecimal negative) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("NEGATIVE_MARGIN_RECOVERY_TRANSACTION_REQUIRED");
        if(negative==null || negative.signum()>=0) throw new IllegalArgumentException("Negative original amount required");
        String key=WechatVerifiedPaymentFact.sha256("COOP_NEGATIVE_MARGIN:"+topicId+":"+snapshot);
        Map<String,Object> original=Map.of("caseKey",key,"topicId",topicId,"reason","NEGATIVE_ORGANIZER_MARGIN",
                "negativeAmount",negative,"sourceSnapshot",snapshot);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status!=STATUS_ROLLED_BACK) return;
                // Only after physical rollback: detach completed resources, never suspend live business locks.
                TransactionTemplate detached=new TransactionTemplate(transactions);
                detached.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
                detached.executeWithoutResult(ignored->{
                    TransactionTemplate write=new TransactionTemplate(transactions);
                    write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    write.executeWithoutResult(tx->{
                        rows.insertIgnore(original);
                        Map<String,Object> stored=rows.selectByKeyForUpdate(key);
                        if(stored==null || !topicId.equals(((Number)stored.get("topic_id")).longValue())
                                || !"MANUAL_REQUIRED".equals(stored.get("state"))
                                || !snapshot.equals(stored.get("source_snapshot"))
                                || negative.compareTo(new BigDecimal(stored.get("negative_amount").toString()))!=0)
                            throw new IllegalStateException("NEGATIVE_MARGIN_MANUAL_RECEIPT_CONFLICT");
                    });
                });
            }
        });
    }
}
