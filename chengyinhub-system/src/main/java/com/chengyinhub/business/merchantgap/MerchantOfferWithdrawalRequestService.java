package com.chengyinhub.business.merchantgap;

import java.math.BigDecimal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.service.IMerchantOfferService;

/**
 * /api/merchant/offer-withdrawal/request/{current,result,withdraw}: the original request/receipt protocol around the
 * existing ordinary chapter-supply exit ({@link IMerchantOfferService#withdrawOwnChapterOffer}). The current row
 * carries a snapshot hash; the withdraw names the hash it saw, the reason hash and an intent hash over
 * {expectedSHA256, offerId, reason, reasonSHA256} (Foundation sorted-key JSON). The stored receipt is immutable and
 * read back by request id. Supply exit moves no money: sold orders, receivables and redemptions are unchanged.
 */
@Service
public class MerchantOfferWithdrawalRequestService {
    static final Set<String> WITHDRAW_KEYS = Set.of("merchantId", "offerId", "requestId", "intentSHA256",
            "expectedSHA256", "reasonSHA256", "reason");

    private final IMerchantAccessService access;
    private final IMerchantOfferService offers;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    @Autowired
    public MerchantOfferWithdrawalRequestService(IMerchantAccessService access, IMerchantOfferService offers,
            JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.access = access;
        this.offers = offers;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    private MerchantAccessContext scope(Long principal, Long merchantId) {
        MerchantAccessContext ctx = MerchantGapSupport.merchant(access, principal, MerchantPermission.COOP_MANAGE);
        if (merchantId == null || !merchantId.equals(ctx.getMerchantId())) {
            throw new MerchantGapException(403, "MERCHANT_SCOPE_MISMATCH", "不是当前门店");
        }
        return ctx;
    }

    public Map<String, Object> current(Long principal, Long merchantId, Long offerId) {
        MerchantAccessContext ctx = scope(principal, merchantId);
        if (offerId == null || offerId <= 0) throw new MerchantGapException(400, "INVALID_REQUEST", "缺少供给记录");
        return currentView(principal, ctx, offerId);
    }

    Map<String, Object> currentView(long principal, MerchantAccessContext ctx, long offerId) {
        Map<String, Object> offer = offerRow(offerId);
        if (offer == null || !ctx.getOwnerMemberId().equals(num(offer.get("merchant_id")))) {
            throw new MerchantGapException(404, "OFFER_NOT_FOUND", "没有这条供给");
        }
        Long topicId = num(offer.get("topic_id"));
        Long chapterId = num(offer.get("chapter_id"));
        Map<String, Object> topic = topicId == null ? null : first("SELECT id, lifecycle, user_status, product_type, circle_theme_code, del_flag FROM cms_topic WHERE id = ?", topicId);
        Map<String, Object> chapter = chapterId == null ? null : first("SELECT id, topic_id, required FROM cms_topic_chapter WHERE id = ?", chapterId);
        boolean ordinary = topic != null && !Integer.valueOf(2).equals(intOf(topic.get("del_flag")))
                && Integer.valueOf(2).equals(intOf(topic.get("product_type")))
                && blank(topic.get("circle_theme_code"))
                && chapter != null && topicId.equals(num(chapter.get("topic_id")));
        Integer status = intOf(offer.get("status"));
        Integer lifecycle = topic == null ? null : intOf(topic.get("lifecycle"));
        boolean live = lifecycle == null || !terminal(lifecycle);
        Integer chapterRequired = chapter == null ? null : intOf(chapter.get("required"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("receiptVersion", 1);
        out.put("actorId", principal);
        out.put("merchantId", ctx.getMerchantId());
        out.put("ownerId", ctx.getOwnerMemberId());
        out.put("offerId", offerId);
        out.put("topicId", topicId);
        out.put("chapterId", chapterId);
        out.put("status", status);
        out.put("validEndEpoch", epoch(offer.get("valid_end")));
        out.put("updatedAtEpoch", epoch(offer.get("update_time")));
        out.put("termsMode", offer.get("terms_mode"));
        out.put("quotaTotal", intOf(offer.get("quota_total")));
        out.put("quotaUsed", intOf(offer.get("quota_used")));
        out.put("perkId", num(offer.get("perk_id")));
        Object fee = offer.get("per_head_fee");
        out.put("perHeadFee", fee == null ? null : fee instanceof BigDecimal d ? d.toPlainString() : String.valueOf(fee));
        out.put("withdrawReason", offer.get("withdraw_reason"));
        out.put("withdrawRequired", offer.get("withdraw_required") == null ? null : intOf(offer.get("withdraw_required")) != 0);
        out.put("chapterRequired", chapterRequired);
        out.put("requiredByOriginalRule", chapterRequired == null ? null : chapterRequired != 0);
        out.put("topicLifecycle", lifecycle);
        out.put("topicUserStatus", topic == null ? null : intOf(topic.get("user_status")));
        out.put("isOrdinary", ordinary);
        out.put("canWithdraw", ordinary && live && Integer.valueOf(1).equals(status));
        Map<String, Object> hashed = new LinkedHashMap<>(out);
        hashed.remove("actorId");
        out.put("snapshotSHA256", MerchantGapSupport.sha256(MerchantGapSupport.appleSortedJson(hashed)));
        return out;
    }

    public Map<String, Object> result(Long principal, Long merchantId, Long offerId, String requestId) {
        MerchantAccessContext ctx = scope(principal, merchantId);
        if (offerId == null || offerId <= 0 || requestId == null || !MerchantGapSupport.LOWER_UUID.matcher(requestId).matches()) {
            throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        }
        Map<String, Object> row = receiptRow(principal, ctx.getMerchantId(), offerId, requestId);
        if (row != null) return receiptView(row);
        Map<String, Object> unknown = base(principal, ctx, offerId, requestId, "UNKNOWN");
        unknown.put("completedAt", null);
        return unknown;
    }

    public Map<String, Object> withdraw(Long principal, Map<String, Object> body) {
        if (body == null || !body.keySet().equals(WITHDRAW_KEYS)) throw new MerchantGapException(400, "INVALID_REQUEST", "请求字段不完整");
        Long merchantId = MerchantGapSupport.positive(body, "merchantId");
        Long offerId = MerchantGapSupport.positive(body, "offerId");
        String requestId = MerchantGapSupport.text(body, "requestId");
        MerchantAccessContext ctx = scope(principal, merchantId);
        if (offerId == null || requestId == null || !MerchantGapSupport.LOWER_UUID.matcher(requestId).matches()) {
            throw new MerchantGapException(400, "INVALID_REQUEST", "请求无效");
        }
        String intent = MerchantGapSupport.text(body, "intentSHA256");
        String expected = MerchantGapSupport.text(body, "expectedSHA256");
        String reasonHash = MerchantGapSupport.text(body, "reasonSHA256");
        String reason = MerchantGapSupport.text(body, "reason");
        Map<String, Object> previous = receiptRow(principal, ctx.getMerchantId(), offerId, requestId);
        if (previous != null) {
            if (!same(previous.get("intent_sha256"), intent) || !same(previous.get("expected_sha256"), expected)
                    || !same(previous.get("reason_sha256"), reasonHash)) {
                throw new MerchantGapException(409, "REQUEST_ID_CONFLICT", "请求编号已用于其他退出");
            }
            return receiptView(previous);
        }
        Map<String, Object> received = new LinkedHashMap<>();
        received.put("expectedSHA256", expected);
        received.put("offerId", offerId);
        received.put("reason", reason);
        received.put("reasonSHA256", reasonHash);
        String receivedIntent = MerchantGapSupport.sha256(MerchantGapSupport.appleSortedJson(received));
        String receivedReason = reason == null ? MerchantGapSupport.sha256("") : MerchantGapSupport.sha256(reason);
        Receipt receipt = new Receipt(principal, ctx, offerId, requestId, intent, receivedIntent, expected, reasonHash, receivedReason);
        if (!hex(intent) || !hex(expected) || !hex(reasonHash) || reason == null || !reason.equals(trimJava(reason))
                || reason.isEmpty() || reason.length() > 500 || !receivedReason.equals(reasonHash) || !receivedIntent.equals(intent)) {
            return store(receipt, "REJECTED_INPUT");
        }
        try {
            return tx.execute(status -> {
                Map<String, Object> now = currentView(principal, ctx, offerId);
                if (!expected.equals(now.get("snapshotSHA256"))) return store(receipt, "REJECTED_CHANGED");
                if (!Boolean.TRUE.equals(now.get("canWithdraw"))) return store(receipt, "REJECTED_STATE");
                try {
                    offers.withdrawOwnChapterOffer(offerId, ctx.getOwnerMemberId(), reason);
                } catch (IllegalStateException refused) {
                    status.setRollbackOnly();
                    return null;
                }
                Map<String, Object> after = offerRow(offerId);
                Map<String, Object> topic = first("SELECT user_status FROM cms_topic WHERE id = ?", num(now.get("topicId")));
                receipt.topicId = num(now.get("topicId"));
                receipt.chapterId = num(now.get("chapterId"));
                receipt.statusAtWrite = intOf(after.get("status"));
                receipt.requiredAtWrite = Boolean.TRUE.equals(now.get("requiredByOriginalRule")) ? 1 : 0;
                receipt.userStatusAtWrite = topic == null ? null : intOf(topic.get("user_status"));
                receipt.offerUpdatedAt = epoch(after.get("update_time"));
                if (receipt.offerUpdatedAt == null) receipt.offerUpdatedAt = System.currentTimeMillis();
                return store(receipt, "WITHDRAWN");
            }) instanceof Map<?, ?> stored ? cast(stored) : store(receipt, "REJECTED_STATE");
        } catch (DuplicateKeyException raced) {
            return receiptView(receiptRow(principal, ctx.getMerchantId(), offerId, requestId));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> value) { return (Map<String, Object>) value; }

    private Map<String, Object> store(Receipt r, String outcome) {
        boolean ok = "WITHDRAWN".equals(outcome);
        jdbc.update("INSERT INTO merchant_offer_withdrawal_request(actor_id, merchant_id, owner_id, offer_id, request_id, outcome, intent_sha256, received_sha256, expected_sha256, reason_sha256, received_reason_sha256, topic_id, chapter_id, status_at_write, required_at_write, user_status_at_write, offer_updated_at, completed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                r.actor, r.ctx.getMerchantId(), r.ctx.getOwnerMemberId(), r.offerId, r.requestId, outcome,
                hexOrNull(r.intent), r.receivedIntent, hexOrNull(r.expected), hexOrNull(r.reasonHash), r.receivedReason,
                ok ? r.topicId : null, ok ? r.chapterId : null, ok ? r.statusAtWrite : null, ok ? r.requiredAtWrite : null,
                ok ? r.userStatusAtWrite : null, ok ? r.offerUpdatedAt : null, System.currentTimeMillis());
        return receiptView(receiptRow(r.actor, r.ctx.getMerchantId(), r.offerId, r.requestId));
    }

    private Map<String, Object> receiptRow(long actor, long merchantId, long offerId, String requestId) {
        return first("SELECT * FROM merchant_offer_withdrawal_request WHERE actor_id = ? AND merchant_id = ? AND offer_id = ? AND request_id = ?",
                actor, merchantId, offerId, requestId);
    }

    private static Map<String, Object> receiptView(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("receiptVersion", 1);
        out.put("actorId", num(row.get("actor_id")));
        out.put("merchantId", num(row.get("merchant_id")));
        out.put("ownerId", num(row.get("owner_id")));
        out.put("offerId", num(row.get("offer_id")));
        out.put("requestId", row.get("request_id"));
        out.put("outcome", row.get("outcome"));
        out.put("intentSHA256", row.get("intent_sha256"));
        out.put("receivedSHA256", row.get("received_sha256"));
        out.put("expectedSHA256", row.get("expected_sha256"));
        out.put("reasonSHA256", row.get("reason_sha256"));
        out.put("receivedReasonSHA256", row.get("received_reason_sha256"));
        out.put("completedAt", num(row.get("completed_at")));
        out.put("topicId", num(row.get("topic_id")));
        out.put("chapterId", num(row.get("chapter_id")));
        out.put("statusAtWrite", intOf(row.get("status_at_write")));
        out.put("requiredAtWrite", intOf(row.get("required_at_write")));
        out.put("userStatusAtWrite", intOf(row.get("user_status_at_write")));
        out.put("offerUpdatedAt", num(row.get("offer_updated_at")));
        return out;
    }

    private static Map<String, Object> base(long actor, MerchantAccessContext ctx, long offerId, String requestId, String outcome) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("receiptVersion", 1);
        out.put("actorId", actor);
        out.put("merchantId", ctx.getMerchantId());
        out.put("ownerId", ctx.getOwnerMemberId());
        out.put("offerId", offerId);
        out.put("requestId", requestId);
        out.put("outcome", outcome);
        return out;
    }

    private Map<String, Object> offerRow(long offerId) {
        return first("SELECT id, topic_id, chapter_id, merchant_id, perk_id, terms_mode, per_head_fee, quota_total, quota_used, valid_end, status, withdraw_reason, withdraw_required, update_time FROM merchant_chapter_offer WHERE id = ?", offerId);
    }

    private Map<String, Object> first(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Java String.trim(): strips code points <= U+0020 at both ends, which is what the app mirrors. */
    static String trimJava(String value) { return value.trim(); }

    private static boolean terminal(int lifecycle) {
        try {
            return com.chengyinhub.business.domain.enums.TopicLifecycleState.fromCode(lifecycle).isRunTerminal();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static boolean hex(String value) { return value != null && MerchantGapSupport.HEX64.matcher(value).matches(); }
    private static String hexOrNull(String value) { return hex(value) ? value : null; }
    private static boolean same(Object stored, String given) { return stored == null ? given == null : stored.equals(given); }
    private static boolean blank(Object value) { return value == null || String.valueOf(value).trim().isEmpty(); }
    private static Long num(Object value) { return value instanceof Number n ? n.longValue() : null; }
    private static Integer intOf(Object value) {
        if (value instanceof Boolean b) return b ? 1 : 0;
        return value instanceof Number n ? n.intValue() : null;
    }
    private static Long epoch(Object value) { return value instanceof Date d ? d.getTime() : null; }

    private static final class Receipt {
        final long actor;
        final MerchantAccessContext ctx;
        final long offerId;
        final String requestId, intent, receivedIntent, expected, reasonHash, receivedReason;
        Long topicId, chapterId, offerUpdatedAt;
        Integer statusAtWrite, requiredAtWrite, userStatusAtWrite;

        Receipt(long actor, MerchantAccessContext ctx, long offerId, String requestId, String intent, String receivedIntent,
                String expected, String reasonHash, String receivedReason) {
            this.actor = actor;
            this.ctx = ctx;
            this.offerId = offerId;
            this.requestId = requestId;
            this.intent = intent;
            this.receivedIntent = receivedIntent;
            this.expected = expected;
            this.reasonHash = reasonHash;
            this.receivedReason = receivedReason;
        }
    }
}
