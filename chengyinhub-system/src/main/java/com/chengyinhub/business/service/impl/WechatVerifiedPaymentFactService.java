package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference;
import com.chengyinhub.business.mapper.WechatVerifiedPaymentFactMapper;
import com.chengyinhub.business.service.WechatPaymentOrderOwner;
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
    public static final String REJECTION_VERIFIED_BUSINESS_MISSING = "VERIFIED_BUSINESS_MISSING";
    @Autowired private WechatVerifiedPaymentFactMapper mapper;
    @Autowired private org.springframework.transaction.PlatformTransactionManager factReadTransactions;
    @Autowired private org.springframework.transaction.PlatformTransactionManager factWriteTransactions;
    @Autowired private WechatPaymentOrderOwner factBusinessOwner;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate factIntentJdbc;
    public record Stored(Reference reference, WechatVerifiedPaymentFact facts) { }
    public record RecoveryTicket(long id, long businessId, Stored original, int state, String reason) { }
    private record WriteResult(Reference reference, ConflictException rejected) { }
    public static class ConflictException extends IllegalStateException {
        public ConflictException() { super("Verified payment fact identity conflict; evidence retained"); }
    }

    /** SDK observation is complete before entry. Never suspend an outer owner of the same business row. */
    public Reference recordVerified(WechatVerifiedPaymentFact candidate) {
        candidate.requireValid();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("渠道事实必须在原业务事务外持久化，不能悬挂持行锁的调用方");
        var transaction=new org.springframework.transaction.support.TransactionTemplate(factWriteTransactions);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        WriteResult result=transaction.execute(status -> recordSerialized(candidate));
        if (result==null) throw new IllegalStateException("Verified fact transaction has no result");
        if (result.rejected()!=null) throw result.rejected(); // Rejection evidence has already committed.
        return result.reference();
    }

    private WriteResult recordSerialized(WechatVerifiedPaymentFact candidate) {
        WechatPaymentOrderOwner.Snapshot business;
        try { business=factBusinessOwner.lock(candidate.businessOutTradeNo()); }
        catch (WechatPaymentOrderOwner.MissingOrderException missing) { business=null; }
        // The original business row is shared by guard/CAS, all attempts and this fact producer.
        // No SDK, business mutation or callback-DONE write runs in this independent short transaction.
        if (business!=null && (business.id()==null || business.id().longValue()<=0))
            throw new IllegalStateException("原业务锁身份不完整");
        if (!matchesFrozenIntent(candidate,business)) {
            var original=mapper.selectMatchingCurrent(candidate.kind().name(),candidate.mchid(),candidate.providerId(),candidate.merchantEventNo());
            reject(candidate,original.isEmpty()?null:number(original.get(0),"id"),null,"ORIGINAL_INTENT_CONFLICT");
            return new WriteResult(null,new ConflictException());
        }
        Map<String,Object> insert=columns(candidate);
        boolean inserted=mapper.insertIgnore(insert)==1;
        var matches=mapper.selectMatchingCurrent(candidate.kind().name(),candidate.mchid(),candidate.providerId(),candidate.merchantEventNo());
        if (matches.size()!=1 || !checked(matches.get(0)).reference().hash().equals(candidate.factHash())) {
            if (matches.isEmpty()) throw new IllegalStateException("Verified fact insert has no durable readback");
            reject(candidate,number(matches.get(0),"id"),null,"PROVIDER_IDENTITY");
            return new WriteResult(null,new ConflictException());
        }
        Stored stored=checked(matches.get(0));
        if (business==null) {
            reject(candidate,stored.reference().id(),null,REJECTION_VERIFIED_BUSINESS_MISSING);
            return new WriteResult(stored.reference(),new ConflictException());
        }
        if (candidate.kind()==WechatVerifiedPaymentFact.Kind.PAYMENT) {
            var attempts=factIntentJdbc.queryForList("SELECT closed_confirmed_at FROM wx_pay_order_intent WHERE mchid=? AND out_trade_no=?",
                    candidate.mchid(),candidate.wxOutTradeNo());
            boolean closed=attempts.size()==1 && attempts.get(0).get("closed_confirmed_at")!=null;
            if (closed || (inserted && !business.payable())) {
                String reason=closed?"VERIFIED_SUCCESS_AFTER_CLOSED":"VERIFIED_SUCCESS_LOCAL_CONFLICT";
                reject(candidate,stored.reference().id(),null,reason);
                enqueueRecovery(stored,business.id().longValue(),reason);
            }
        }
        return new WriteResult(stored.reference(),null);
    }

    private boolean matchesFrozenIntent(WechatVerifiedPaymentFact fact,WechatPaymentOrderOwner.Snapshot business) {
        var rows=factIntentJdbc.queryForList("SELECT appid,trade_type,currency,total_fen FROM wx_pay_order_intent WHERE mchid=? AND out_trade_no=?",
                fact.mchid(),fact.wxOutTradeNo());
        if (rows.isEmpty()) return fact.intentRequestHash()==null; // Historical actual provider original, never guessed channel defaults.
        if (rows.size()!=1) return false;
        var row=rows.get(0);
        if (!fact.appid().equals(row.get("appid")) || !fact.tradeType().equals(row.get("trade_type"))
                || !fact.currency().equals(row.get("currency")) || !(row.get("total_fen") instanceof Number total)
                || total.longValue()!=fact.totalFen()) return false;
        var binding=factIntentJdbc.queryForList("SELECT business_out_trade_no,biz_type,biz_id FROM wx_pay_order_intent WHERE mchid=? AND out_trade_no=?",
                fact.mchid(),fact.wxOutTradeNo());
        if (binding.size()!=1 || !fact.businessOutTradeNo().equals(binding.get(0).get("business_out_trade_no"))) return false;
        if (business!=null && (!business.type().equals(binding.get(0).get("biz_type"))
                || !(binding.get(0).get("biz_id") instanceof Number id) || id.longValue()!=business.id().longValue())) return false;
        var hashes=factIntentJdbc.queryForList("SELECT request_hash FROM wx_pay_order_intent WHERE mchid=? AND out_trade_no=?",
                fact.mchid(),fact.wxOutTradeNo());
        return hashes.size()==1 && fact.intentRequestHash()!=null && fact.intentRequestHash().equals(hashes.get(0).get("request_hash"));
    }

    private static String recoveryKey(Reference reference) { return "VERIFIED:"+reference.id()+":"+reference.hash(); }
    private void enqueueRecovery(Stored stored,long businessId,String reason) {
        Map<String,Object> row=new HashMap<>(); row.put("idempotency_key",recoveryKey(stored.reference()));
        row.put("biz_id",businessId);row.put("reason",reason);mapper.insertRecovery(row);
        var actual=mapper.selectRecovery(recoveryKey(stored.reference()));
        if (actual==null || number(actual,"biz_id")!=businessId || number(actual,"biz_type")!=1
                || number(actual,"money_affecting")!=0 || (number(actual,"state")!=4 && number(actual,"state")!=2))
            throw new IllegalStateException("成功冲突恢复队列持久回读不一致");
    }

    /** Durable manual handoff, not a current provider status and never authority to refund/reopen capacity. */
    public java.util.Optional<RecoveryTicket> findPaymentRecovery(Reference reference) {
        Stored original=requireVerified(reference);
        if (original.facts().kind()!=WechatVerifiedPaymentFact.Kind.PAYMENT) throw new IllegalArgumentException("原支付事实引用必填");
        return readFresh(() -> {
            var row=mapper.selectRecovery(recoveryKey(reference));
            if (row==null) return java.util.Optional.empty();
            if (number(row,"biz_id")<=0 || number(row,"biz_type")!=1 || number(row,"money_affecting")!=0)
                throw new IllegalStateException("原支付恢复身份不完整");
            return java.util.Optional.of(new RecoveryTicket(number(row,"id"),number(row,"biz_id"),original,
                    Math.toIntExact(number(row,"state")),(String)row.get("last_error_code")));
        });
    }

    /** Owner calls only after its own exact consumption proof, in that original successful mutation/DONE transaction. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void completePaymentRecovery(Reference reference,long businessId) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("恢复完成必须与原业务成功消费同事务");
        var stored=requireVerified(reference);
        if (stored.facts().kind()!=WechatVerifiedPaymentFact.Kind.PAYMENT) throw new IllegalArgumentException("原支付事实必填");
        var original=factBusinessOwner.lock(stored.facts().businessOutTradeNo());
        if (original==null || original.id()==null || original.id().longValue()!=businessId)
            throw new ConflictException();
        var queued=mapper.selectRecoveryCurrent(recoveryKey(reference));
        if (queued==null) return;
        if (number(queued,"biz_id")!=businessId || number(queued,"biz_type")!=1 || number(queued,"money_affecting")!=0
                || (number(queued,"state")!=4 && number(queued,"state")!=2)) throw new ConflictException();
        if (number(queued,"state")==4 && mapper.completeRecovery(recoveryKey(reference),businessId)!=1)
            throw new IllegalStateException("原支付恢复完成回写冲突");
        var completed=mapper.selectRecoveryCurrent(recoveryKey(reference));
        if (completed==null || number(completed,"biz_id")!=businessId || number(completed,"state")!=2)
            throw new IllegalStateException("原支付恢复完成持久回读不一致");
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

    /** The domain row and the latest attempt stay locked until the caller commits its own effects. */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY,
            noRollbackFor = ConflictException.class)
    public Stored requireConsumablePayment(Reference reference) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Payment consumption requires the original business transaction");
        Stored stored = requireVerified(reference);
        WechatVerifiedPaymentFact fact = stored.facts();
        if (fact.kind() != WechatVerifiedPaymentFact.Kind.PAYMENT) throw new ConflictException();
        WechatPaymentOrderOwner.Snapshot business = factBusinessOwner.lock(fact.businessOutTradeNo());
        if (business.id() == null || business.id().longValue() <= 0) throw new ConflictException();
        var rows = factIntentJdbc.queryForList(
                "SELECT * FROM wx_pay_order_intent WHERE biz_type=? AND biz_id=? ORDER BY attempt_no DESC LIMIT 1 FOR UPDATE",
                business.type(), business.id().longValue());
        if (rows.isEmpty()) {
            if (fact.intentRequestHash() != null || !fact.businessOutTradeNo().equals(fact.wxOutTradeNo()))
                throw new ConflictException();
            return stored;
        }
        var current = rows.get(0);
        if (current.get("closed_confirmed_at") != null
                || !fact.mchid().equals(current.get("mchid"))
                || !fact.businessOutTradeNo().equals(current.get("business_out_trade_no"))
                || !fact.wxOutTradeNo().equals(current.get("out_trade_no"))
                || !fact.appid().equals(current.get("appid"))
                || !fact.tradeType().equals(current.get("trade_type"))
                || !fact.currency().equals(current.get("currency"))
                || !(current.get("total_fen") instanceof Number total) || total.longValue() != fact.totalFen()
                || fact.intentRequestHash() == null || !fact.intentRequestHash().equals(current.get("request_hash")))
            throw new ConflictException();
        return stored;
    }

    /** Programmatic boundary also covers self-invocation from binding/audit paths; no business lock is acquired. */
    private <T> T readFresh(java.util.function.Supplier<T> read) {
        var transaction=new org.springframework.transaction.support.TransactionTemplate(factReadTransactions);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setReadOnly(true);
        return transaction.execute(status -> read.get());
    }

    /** Compatibility submission still requires one original verified PAYMENT, never the latest alias. */
    public Stored findPaymentSuccess(String businessNo) {
        if (businessNo==null || businessNo.isBlank()) throw new IllegalArgumentException("Original business identity required");
        return readFresh(() -> {
            Stored payment=null;
            for (var row:mapper.selectSuccessesByBusiness(businessNo)) {
                Stored stored=checked(row);
                if (!businessNo.equals(stored.facts().businessOutTradeNo())) throw new ConflictException();
                if (stored.facts().kind()!=WechatVerifiedPaymentFact.Kind.PAYMENT) continue;
                if (payment!=null) throw new ConflictException();
                payment=stored;
            }
            return payment;
        });
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

    /** Denial-only guard for owners holding the original business row; never selects a replacement merchant. */
    public boolean hasConfirmedExternalSuccess(String businessNo) {
        if (businessNo==null || businessNo.isBlank())
            throw new IllegalArgumentException("Original business identity required");
        return readFresh(() -> {
            var rows=mapper.selectSuccessesByBusiness(businessNo);
            for (var row:rows) {
                var fact=checked(row).facts();
                if (!businessNo.equals(fact.businessOutTradeNo())) throw new ConflictException();
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
