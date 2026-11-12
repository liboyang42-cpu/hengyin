package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference;
import com.chengyinhub.business.mapper.WorkshopMapper;
import com.chengyinhub.common.exception.ServiceException;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Applies verified facts to the locked original Workshop order; never calls a payment provider. */
@Component
public class WorkshopWechatPaymentOrderAdapter {
    private final WorkshopMapper mapper;
    private final WechatVerifiedPaymentFactService verified;
    private final TransactionTemplate tx;

    public WorkshopWechatPaymentOrderAdapter(WorkshopMapper mapper, WechatVerifiedPaymentFactService verified,
                                             PlatformTransactionManager transactions) {
        this.mapper = mapper;
        this.verified = verified;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Called by the production verified callback router and by the owner's original-order query. */
    public boolean handlePaidOrder(Map<String,String> observation) {
        if (observation == null || !"SUCCESS".equals(observation.get("trade_state"))) return false;
        String no = observation.get("out_trade_no");
        if (no == null || !no.matches("WKO_[a-f0-9]{32}")) return false;
        Reference reference = reference(observation);
        return Boolean.TRUE.equals(tx.execute(s -> {
            var order = mapper.lockOrder(no);
            if (order == null || order.isEmpty()) return false;
            var fact = verified.requireConsumablePayment(reference).facts();
            requirePayment(order,fact);
            if (order.get("paid_at") != null) {
                same(order,"payment_fact_id",reference.id()); same(order,"payment_fact_hash",reference.hash());
                return true; // Refunded/revoked licenses are never reactivated by an old SUCCESS.
            }
            if (!"PENDING".equals(order.get("state"))) return false;
            var update = mutation(order); update.put("factId",reference.id()); update.put("factHash",reference.hash());
            update.put("paidAt",Date.from(fact.successAt())); changed(mapper.activate(update));
            verified.completePaymentRecovery(reference,number(order,"id"));
            return true;
        }));
    }

    public boolean handleRefund(Map<String,String> observation) {
        if (observation == null || !"SUCCESS".equals(observation.get("status"))) return false;
        String no = observation.get("outTradeNo");
        if (no == null || !no.matches("WKO_[a-f0-9]{32}")) return false;
        Reference reference = reference(observation);
        return Boolean.TRUE.equals(tx.execute(s -> {
            var order = mapper.lockOrder(no);
            if (order == null || order.isEmpty() || order.get("paid_at") == null) return false;
            var original = verified.requireVerified(paymentReference(order)).facts(); requirePayment(order,original);
            var fact = verified.requireVerified(reference).facts();
            if (fact.kind()!=WechatVerifiedPaymentFact.Kind.REFUND || !no.equals(fact.businessOutTradeNo())
                    || !Objects.equals(order.get("refund_no"),fact.merchantEventNo())
                    || !Objects.equals(observation.get("outRefundNo"),fact.merchantEventNo())
                    || fact.amountFen()!=number(order,"amount_fen") || fact.totalFen()!=original.totalFen()
                    || !fact.transactionId().equals(original.transactionId()) || !fact.mchid().equals(original.mchid())
                    || !fact.appid().equals(original.appid()) || !fact.tradeType().equals(original.tradeType())
                    || !fact.wxOutTradeNo().equals(original.wxOutTradeNo()) || !"CNY".equals(fact.currency()))
                throw failure(409,"REFUND_FACT_CONFLICT");
            if ("REFUNDED".equals(order.get("state"))) {
                same(order,"refund_fact_id",reference.id()); same(order,"refund_fact_hash",reference.hash()); return true;
            }
            var update = mutation(order); update.put("factId",reference.id()); update.put("factHash",reference.hash());
            update.put("refundedAt",Date.from(fact.successAt())); changed(mapper.refunded(update)); return true;
        }));
    }

    private static void requirePayment(Map<String,Object> order,WechatVerifiedPaymentFact fact) {
        if (!"WECHAT_APP".equals(order.get("provider")) || fact.kind()!=WechatVerifiedPaymentFact.Kind.PAYMENT || !Objects.equals(order.get("order_no"),fact.businessOutTradeNo())
                || fact.totalFen()!=number(order,"amount_fen") || fact.amountFen()!=fact.totalFen()
                || !"APP".equals(fact.tradeType()) || !"CNY".equals(fact.currency())) throw failure(409,"PAYMENT_FACT_CONFLICT");
    }

    private static Reference reference(Map<String,String> observed) {
        try { return new Reference(Long.parseLong(observed.get("verified_fact_id")),observed.get("verified_fact_hash")); }
        catch (RuntimeException invalid) { throw failure(409,"VERIFIED_FACT_REQUIRED"); }
    }

    static Reference paymentReference(Map<String,Object> row) { return new Reference(number(row,"payment_fact_id"),text(row,"payment_fact_hash")); }

    static Map<String,Object> mutation(Map<String,Object> order) {
        Map<String,Object> row = new HashMap<>(); row.put("orderNo",order.get("order_no")); row.put("owner",order.get("buyer_id")); return row;
    }

    static void same(Map<String,Object> row,String field,Object expected) {
        Object actual = row==null ? null : row.get(field);
        if (actual instanceof Number && expected instanceof Number) {
            if (((Number)actual).longValue()==((Number)expected).longValue()) return;
        } else if (actual!=null && actual.equals(expected)) return;
        throw failure(409,"FACT_OR_IDEMPOTENCY_CONFLICT");
    }

    static long number(Map<String,Object> row,String field) {
        if (row==null || !(row.get(field) instanceof Number n)) throw failure(409,"PERSISTED_FACT_MISSING"); return n.longValue();
    }

    static String text(Map<String,Object> row,String field) {
        if (row==null || !(row.get(field) instanceof String s)) throw failure(409,"PERSISTED_FACT_MISSING"); return s;
    }

    static void changed(int count) { if (count!=1) throw new IllegalStateException("Workshop durable mutation lost ownership"); }

    static ServiceException failure(int code,String reason) { return new ServiceException(reason,code); }
}

