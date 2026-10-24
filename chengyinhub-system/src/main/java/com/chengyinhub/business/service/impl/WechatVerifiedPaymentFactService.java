package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference;
import com.chengyinhub.business.mapper.WechatVerifiedPaymentFactMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durable provider evidence only; never changes balances, business state, callback DONE or external requests. */
@Service
public class WechatVerifiedPaymentFactService {
    @Autowired private WechatVerifiedPaymentFactMapper mapper;
    @Autowired private org.springframework.transaction.PlatformTransactionManager factReadTransactions;
    public record Stored(Reference reference, WechatVerifiedPaymentFact facts) { }
    public static class ConflictException extends IllegalStateException {
        public ConflictException() { super("Verified payment fact identity conflict; evidence retained"); }
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW, noRollbackFor=ConflictException.class)
    public Reference recordVerified(WechatVerifiedPaymentFact candidate) {
        candidate.requireValid();
        Map<String,Object> insert = columns(candidate);
        mapper.insertIgnore(insert);
        List<Map<String,Object>> matches = mapper.selectMatching(candidate.kind().name(), candidate.mchid(),
                candidate.providerId(), candidate.merchantEventNo());
        if (matches.size() == 1) {
            Stored existing = checked(matches.get(0));
            if (existing.reference().hash().equals(candidate.factHash())) return existing.reference();
        }
        if (matches.isEmpty()) throw new IllegalStateException("Verified fact insert has no durable readback");
        reject(candidate, number(matches.get(0),"id"), null, "PROVIDER_IDENTITY");
        throw new ConflictException();
    }

    public Stored requireVerified(Reference reference) {
        if (reference==null) throw new IllegalArgumentException("Verified fact reference required");
        return readFresh(() -> {
            Map<String,Object> row=mapper.selectById(reference.id());
            if (row==null) throw new IllegalStateException("Verified payment fact missing");
            Stored stored=checked(row);
            if (!stored.reference().equals(reference)) throw new ConflictException();
            return stored;
        });
    }

    /** Programmatic boundary also covers self-invocation from binding/audit paths; no business lock is acquired. */
    private <T> T readFresh(java.util.function.Supplier<T> read) {
        var transaction=new org.springframework.transaction.support.TransactionTemplate(factReadTransactions);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setReadOnly(true);
        return transaction.execute(status -> read.get());
    }

    public Stored findPaymentSuccess(String mchid,String wxNo,String transactionId) {
        return findSuccess(WechatVerifiedPaymentFact.Kind.PAYMENT,mchid,transactionId,wxNo);
    }
    public Stored findRefundSuccess(String mchid,String refundNo,String refundId) {
        return findSuccess(WechatVerifiedPaymentFact.Kind.REFUND,mchid,refundId,refundNo);
    }
    private Stored findSuccess(WechatVerifiedPaymentFact.Kind kind,String mchid,String providerId,String eventNo) {
        if (mchid==null || mchid.isBlank() || providerId==null || providerId.isBlank() || eventNo==null || eventNo.isBlank()) throw new IllegalArgumentException("Original success identity required");
        return readFresh(() -> {
            var rows=mapper.selectMatching(kind.name(),mchid,providerId,eventNo);
            if (rows.isEmpty()) return null;
            if (rows.size()!=1) throw new ConflictException();
            Stored value=checked(rows.get(0)); var fact=value.facts();
            if (fact.kind()!=kind || !mchid.equals(fact.mchid()) || !providerId.equals(fact.providerId())
                    || !eventNo.equals(fact.merchantEventNo())) throw new ConflictException();
            return value;
        });
    }
    /** Evidence guard only: no local paid/refunded state is inferred or mutated. */
    public boolean hasConfirmedExternalSuccess(String mchid,String businessNo) {
        if (mchid==null || mchid.isBlank() || businessNo==null || businessNo.isBlank())
            throw new IllegalArgumentException("Original merchant and business identity required");
        return readFresh(() -> {
            var rows=mapper.selectSuccessesByBusiness(businessNo);
            for (var row:rows) {
                var fact=checked(row).facts();
                if (!mchid.equals(fact.mchid()) || !businessNo.equals(fact.businessOutTradeNo())) throw new ConflictException();
            }
            return !rows.isEmpty();
        });
    }

    /** Caller has already locked its inbox row. Linking never upgrades an old unbound receipt. */
    public void bindNewInbox(long inboxId, String notifyHash, Reference reference, boolean newInbox) {
        notifyHash=canonicalNotifyHash(notifyHash);
        requireVerified(reference);
        Map<String,Object> old = mapper.selectInboxLink(notifyHash);
        if (old == null && !newInbox) throw new ConflictException();
        if (old == null) {
            Map<String,Object> row = new HashMap<>();
            row.put("inbox_id",inboxId); row.put("notify_hash",notifyHash);
            row.put("fact_id",reference.id()); row.put("fact_hash",reference.hash());
            mapper.insertInboxLink(row);
            old = mapper.selectInboxLink(notifyHash);
        }
        if (!matchesLink(old,inboxId,reference)) throw new ConflictException();
    }

    public boolean hasInboxLink(long inboxId, String notifyHash, Reference reference) {
        notifyHash=canonicalNotifyHash(notifyHash);
        requireVerified(reference);
        return matchesLink(mapper.selectInboxLink(notifyHash),inboxId,reference);
    }

    public boolean hasInboxLinkForNotify(String notifyHash,Reference reference) {
        notifyHash=canonicalNotifyHash(notifyHash);
        requireVerified(reference);
        var row=mapper.selectInboxLink(notifyHash);
        return row!=null && number(row,"inbox_id")>0 && matchesLink(row,number(row,"inbox_id"),reference);
    }

    /** Separate transaction preserves rejected binding evidence even when caller's business transaction rolls back. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void recordRejectedBinding(String notifyHash, Reference candidate, Long originalFactId) {
        notifyHash=canonicalNotifyHash(notifyHash);
        reject(requireVerified(candidate).facts(), originalFactId, notifyHash, "INBOX_BINDING");
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void recordRejectedRefundNotification(Reference original, String[] providerProjection) {
        if (original!=null) requireVerified(original);
        StringBuilder canonical = new StringBuilder("refund-notification-v1:");
        for (String raw : providerProjection) {
            String part=auditValue(raw);
            if (part == null) canonical.append("-1:");
            else canonical.append(part.length()).append(':').append(part);
        }
        String value = canonical.toString();
        Map<String,Object> row = new HashMap<>();
        row.put("candidate_fact_hash",WechatVerifiedPaymentFact.sha256(value));
        row.put("rejection_key",WechatVerifiedPaymentFact.sha256("REFUND_NOTIFICATION:"+(original==null?"UNVERIFIED":original.hash())+":"+value));
        row.put("rejection_reason",original==null?"UNVERIFIED_REFUND":"REFUND_NOTIFICATION"); row.put("original_fact_id",original==null?null:original.id());
        row.put("notify_hash",null); row.put("candidate_facts",value); mapper.insertRejection(row);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void recordRejectedPaymentNotification(String[] providerProjection) {
        StringBuilder canonical=new StringBuilder("unverified-payment-notification-v1:");
        for (String raw:providerProjection) {
            String part=auditValue(raw);
            if (part==null) canonical.append("-1:"); else canonical.append(part.length()).append(':').append(part);
        }
        String value=canonical.toString(); Map<String,Object> row=new HashMap<>();
        row.put("candidate_fact_hash",WechatVerifiedPaymentFact.sha256(value));
        row.put("rejection_key",WechatVerifiedPaymentFact.sha256("UNVERIFIED_PAYMENT:"+value));
        row.put("rejection_reason","UNVERIFIED_PAYMENT"); row.put("original_fact_id",null);
        row.put("notify_hash",null);row.put("candidate_facts",value);mapper.insertRejection(row);
    }

    private static String auditValue(String raw) {
        if (raw==null) return null;
        if (raw.matches("-?[0-9]{1,20}") || java.util.Set.of("CNY","APP","JSAPI","SUCCESS","REFUND",
                "PROCESSING","CLOSED","ABNORMAL","FAIL","UNKNOWN").contains(raw)) return raw;
        try { return java.time.OffsetDateTime.parse(raw).toInstant().toString(); }
        catch (java.time.format.DateTimeParseException notTimestamp) {
            return "sha256:"+WechatVerifiedPaymentFact.sha256(raw);
        }
    }

    private static String canonicalNotifyHash(String value) {
        if (value==null || !value.matches("[A-Fa-f0-9]{64}")) throw new IllegalArgumentException("Invalid inbox SHA-256 encoding");
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private boolean matchesLink(Map<String,Object> row, long inboxId, Reference reference) {
        return row != null && number(row,"inbox_id")==inboxId && number(row,"fact_id")==reference.id()
                && reference.hash().equals(row.get("fact_hash"));
    }
    private void reject(WechatVerifiedPaymentFact candidate, Long originalId, String notifyHash, String reason) {
        Map<String,Object> row = new HashMap<>();
        row.put("rejection_key", WechatVerifiedPaymentFact.sha256(reason+":"+candidate.factHash()+":"
                +originalId+":"+notifyHash));
        row.put("rejection_reason",reason); row.put("original_fact_id",originalId);
        row.put("candidate_fact_hash",candidate.factHash()); row.put("notify_hash",notifyHash);
        row.put("candidate_facts",candidate.canonicalFacts()); mapper.insertRejection(row);
    }
    private static long number(Map<String,Object> row, String key) {
        if (!(row.get(key) instanceof Number value)) throw new IllegalStateException("Verified fact numeric evidence missing");
        return value.longValue();
    }
    private static Stored checked(Map<String,Object> row) {
        if (!"VERIFIED".equals(row.get("verification_outcome")) || number(row,"verification_version")!=1
                || !WechatVerifiedPaymentFact.VERIFICATION_METHOD.equals(row.get("verification_method"))) {
            throw new IllegalStateException("Untrusted payment fact provenance");
        }
        WechatVerifiedPaymentFact fact = new WechatVerifiedPaymentFact(
                WechatVerifiedPaymentFact.Kind.valueOf((String)row.get("fact_kind")), (String)row.get("mchid"),
                (String)row.get("appid"), (String)row.get("business_out_trade_no"), (String)row.get("wx_out_trade_no"),
                (String)row.get("merchant_event_no"), (String)row.get("provider_id"), (String)row.get("transaction_id"),
                (String)row.get("trade_type"), (String)row.get("currency"), number(row,"total_fen"), number(row,"amount_fen"),
                Instant.parse((String)row.get("success_at")), (String)row.get("intent_request_hash"),
                (String)row.get("original_payment_state"));
        fact.requireValid();
        if (!fact.factHash().equals(row.get("fact_hash"))) throw new IllegalStateException("Verified fact columns/hash conflict");
        return new Stored(new Reference(number(row,"id"),fact.factHash()),fact);
    }
    public static Map<String,Object> columns(WechatVerifiedPaymentFact f) {
        f.requireValid(); Map<String,Object> row = new HashMap<>();
        row.put("fact_kind",f.kind().name()); row.put("mchid",f.mchid()); row.put("appid",f.appid());
        row.put("business_out_trade_no",f.businessOutTradeNo()); row.put("wx_out_trade_no",f.wxOutTradeNo());
        row.put("merchant_event_no",f.merchantEventNo()); row.put("provider_id",f.providerId());
        row.put("transaction_id",f.transactionId()); row.put("trade_type",f.tradeType()); row.put("currency",f.currency());
        row.put("total_fen",f.totalFen()); row.put("amount_fen",f.amountFen()); row.put("success_at",f.successAt().toString());
        row.put("intent_request_hash",f.intentRequestHash()); row.put("fact_hash",f.factHash());
        row.put("verification_outcome","VERIFIED"); row.put("verification_version",1);
        row.put("verification_method",WechatVerifiedPaymentFact.VERIFICATION_METHOD);
        row.put("original_payment_state",f.originalPaymentState()); return row;
    }
}
