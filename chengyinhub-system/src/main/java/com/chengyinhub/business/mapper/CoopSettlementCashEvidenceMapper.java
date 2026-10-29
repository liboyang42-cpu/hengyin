package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CoopSettlementCashClose;
import com.chengyinhub.business.domain.CoopSettlementCashContribution;
import com.chengyinhub.business.domain.CoopSettlementCashEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ibatis.annotations.Param;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 仅原证据持久化；无生产调用者，不计算/执行任何分账。调用者先持原 topic fence。 */
public interface CoopSettlementCashEvidenceMapper {
    int insertClose(CoopSettlementCashClose row);
    int insertContribution(CoopSettlementCashContribution row);
    int insertEvent(CoopSettlementCashEvent row);
    CoopSettlementCashClose selectCloseForUpdate(@Param("topicId") Long topicId);
    CoopSettlementCashContribution selectContributionForUpdate(@Param("registrationId") Long registrationId);
    CoopSettlementCashEvent selectEventForUpdate(@Param("cashCloseId") Long cashCloseId,
            @Param("eventType") String eventType, @Param("sourceId") Long sourceId);

    /** 同身份只读回原证据；不同载荷绝不覆盖原行，也不把指纹相同当作内容相同。 */
    default CoopSettlementCashClose recordClose(CoopSettlementCashClose row) {
        requireTransaction();
        CoopSettlementCashClose prior = selectCloseForUpdate(row.getTopicId());
        if (prior!=null) { requireSame(prior,row); return prior; }
        try { requireOne(insertClose(row)); } catch (DuplicateKeyException duplicate) { /* read exact key below */ }
        CoopSettlementCashClose current = selectCloseForUpdate(row.getTopicId());
        requireSame(current,row); return current;
    }
    default CoopSettlementCashContribution recordContribution(CoopSettlementCashContribution row) {
        requireTransaction();
        CoopSettlementCashContribution prior = selectContributionForUpdate(row.getRegistrationId());
        if (prior!=null) { requireSame(prior,row); return prior; }
        try {
            if (insertContribution(row)!=1) throw new IllegalStateException("CASH_EVIDENCE_PAYER_OR_SOURCE_IDENTITY_UNPROVEN");
        } catch (DuplicateKeyException duplicate) { /* read exact key below */ }
        CoopSettlementCashContribution current = selectContributionForUpdate(row.getRegistrationId());
        requireSame(current,row); return current;
    }
    default CoopSettlementCashEvent recordEvent(CoopSettlementCashEvent row) {
        requireTransaction();
        CoopSettlementCashEvent prior = selectEventForUpdate(row.getCashCloseId(),row.getEventType(),row.getSourceId());
        if (prior!=null) { requireSame(prior,row); return prior; }
        try { requireOne(insertEvent(row)); } catch (DuplicateKeyException duplicate) { /* read exact key below */ }
        CoopSettlementCashEvent current = selectEventForUpdate(row.getCashCloseId(),row.getEventType(),row.getSourceId());
        requireSame(current,row); return current;
    }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("CASH_EVIDENCE_WRITE_TRANSACTION_REQUIRED");
        }
    }
    private static void requireOne(int count) {
        if (count!=1) throw new IllegalStateException("CASH_EVIDENCE_SOURCE_IDENTITY_CONFLICT");
    }
    private static void requireSame(Object stored,Object requested) {
        if (stored==null || requested==null || !payload(stored).equals(payload(requested))) {
            throw new IllegalStateException("CASH_EVIDENCE_PAYLOAD_CONFLICT");
        }
    }
    private static ObjectNode payload(Object value) {
        ObjectMapper json = new ObjectMapper();
        ObjectNode node = json.valueToTree(value);
        node.remove("id"); node.remove("createdAt");
        for (String field : new String[]{"contractSnapshot","registrationSnapshot","sourceSnapshot"}) {
            JsonNode raw = node.get(field);
            if (raw!=null && !raw.isNull()) {
                try { node.set(field,json.readTree(raw.asText())); }
                catch (java.io.IOException invalid) { throw new IllegalArgumentException("CASH_EVIDENCE_INVALID_JSON"); }
            }
        }
        return node;
    }
}
