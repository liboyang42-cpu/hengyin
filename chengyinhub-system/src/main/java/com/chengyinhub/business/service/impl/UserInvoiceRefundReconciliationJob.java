package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UserInvoiceOrderLine;
import com.chengyinhub.business.mapper.UserInvoiceMapper;
import com.chengyinhub.business.mapper.UserInvoiceProcessingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Invoice-owned recovery over durable refund facts. No refund callback or refund-table writes.
 * Each consumer call is an independent transaction; multiple instances are safe via invoice CAS.
 * A failed row remains a candidate. Cursor wraps and resets on restart, so no fact is acknowledged away.
 */
@Component
public class UserInvoiceRefundReconciliationJob {
    private static final Logger log=LoggerFactory.getLogger(UserInvoiceRefundReconciliationJob.class);
    private final UserInvoiceProcessingMapper processing;
    private final UserInvoiceMapper invoices;
    private final UserInvoiceProcessingService consumer;
    private final AtomicBoolean running=new AtomicBoolean();
    private long afterRegistrationId;

    public UserInvoiceRefundReconciliationJob(UserInvoiceProcessingMapper processing,UserInvoiceMapper invoices,
                                              UserInvoiceProcessingService consumer) {
        this.processing=processing; this.invoices=invoices; this.consumer=consumer;
    }

    // Uses existing chengyin.jobs.enabled scheduling infrastructure. No external issuance or notifications.
    @Scheduled(fixedDelayString="${chengyin.user-invoice.refund-reconcile-delay-ms:30000}",
               initialDelayString="${chengyin.user-invoice.refund-reconcile-delay-ms:30000}")
    public void reconcilePendingRefundFacts() {
        if(!running.compareAndSet(false,true)) return;
        try {
            List<Long> candidates=processing.refundPauseCandidates(afterRegistrationId);
            if(candidates.isEmpty()) {afterRegistrationId=0; return;}
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            int processed=0;
            for(Long id:candidates) {
                if(System.nanoTime()>deadline) break;
                try {consumer.acceptRegistrationRefundFact(id);}
                catch(RuntimeException failure) {
                    // No refund reason, tax, provider response or exception message is logged.
                    log.warn("Invoice refund reconciliation pending registrationId={} failureType={}",id,failure.getClass().getSimpleName());
                }
                afterRegistrationId=id; processed++;
            }
            if(processed==candidates.size() && candidates.size()<50) afterRegistrationId=0;
        } catch(RuntimeException failure) {
            log.warn("Invoice refund reconciliation scan pending failureType={}",failure.getClass().getSimpleName());
        } finally {running.set(false);}
    }

    /** Actual scoped manual recovery entry. Authorization is checked before resolving registrations.
     * Calls the proxied consumer, not a self-invoked transactional method. */
    public Map<String,Object> refreshApplication(Long actor,Long applicationId) {
        consumer.detail(actor,applicationId);
        for(UserInvoiceOrderLine line:invoices.selectOrderLines(applicationId)) {
            consumer.acceptRegistrationRefundFact(line.getOrderId());
        }
        return consumer.detail(actor,applicationId);
    }
}
