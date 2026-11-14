package com.chengyinhub.business.merchantgap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Back-office, read-only ledger of the merchant chapter-supply exit receipts written by
 * {@link MerchantOfferWithdrawalRequestService#withdraw}. The exit is self-service (no review step) and the receipt is
 * immutable, so this service only reads; it never changes an offer or a receipt.
 */
@Service
public class MerchantOfferWithdrawalAdminService {
    static final Set<String> OUTCOMES = Set.of("WITHDRAWN", "REJECTED_STATE", "REJECTED_CHANGED", "REJECTED_INPUT");
    static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbc;

    @Autowired
    public MerchantOfferWithdrawalAdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> list(Long merchantId, Long offerId, String outcome, Integer pageNum, Integer pageSize) {
        if (merchantId != null && merchantId <= 0) throw new MerchantGapException(400, "INVALID_REQUEST", "门店编号无效");
        if (offerId != null && offerId <= 0) throw new MerchantGapException(400, "INVALID_REQUEST", "供给编号无效");
        String filter = outcome == null || outcome.isBlank() ? null : outcome.trim();
        if (filter != null && !OUTCOMES.contains(filter)) throw new MerchantGapException(400, "INVALID_REQUEST", "结果筛选无效");
        int size = pageSize == null ? 20 : Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
        int page = pageNum == null ? 1 : Math.max(1, pageNum);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (merchantId != null) { where.append(" AND merchant_id = ?"); args.add(merchantId); }
        if (offerId != null) { where.append(" AND offer_id = ?"); args.add(offerId); }
        if (filter != null) { where.append(" AND outcome = ?"); args.add(filter); }
        Long total = jdbc.queryForObject("SELECT COUNT(1) FROM merchant_offer_withdrawal_request" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) (page - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM merchant_offer_withdrawal_request" + where
                + " ORDER BY completed_at DESC, id DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) items.add(view(r));
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String o : List.of("WITHDRAWN", "REJECTED_STATE", "REJECTED_CHANGED", "REJECTED_INPUT")) counts.put(o, 0L);
        for (Map<String, Object> c : jdbc.queryForList("SELECT outcome, COUNT(1) AS n FROM merchant_offer_withdrawal_request GROUP BY outcome")) {
            counts.put(String.valueOf(c.get("outcome")), ((Number) c.get("n")).longValue());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0L : total);
        out.put("rows", items);
        out.put("counts", counts);
        return out;
    }

    static Map<String, Object> view(Map<String, Object> r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", num(r.get("id")));
        v.put("requestId", r.get("request_id"));
        v.put("outcome", r.get("outcome"));
        v.put("merchantId", num(r.get("merchant_id")));
        v.put("ownerId", num(r.get("owner_id")));
        v.put("actorId", num(r.get("actor_id")));
        v.put("offerId", num(r.get("offer_id")));
        v.put("topicId", num(r.get("topic_id")));
        v.put("chapterId", num(r.get("chapter_id")));
        v.put("statusAtWrite", num(r.get("status_at_write")));
        v.put("requiredAtWrite", num(r.get("required_at_write")));
        v.put("userStatusAtWrite", num(r.get("user_status_at_write")));
        v.put("offerUpdatedAt", num(r.get("offer_updated_at")));
        v.put("completedAt", num(r.get("completed_at")));
        v.put("intentSHA256", r.get("intent_sha256"));
        v.put("receivedSHA256", r.get("received_sha256"));
        v.put("expectedSHA256", r.get("expected_sha256"));
        v.put("reasonSHA256", r.get("reason_sha256"));
        v.put("receivedReasonSHA256", r.get("received_reason_sha256"));
        return v;
    }

    private static Long num(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }
}
