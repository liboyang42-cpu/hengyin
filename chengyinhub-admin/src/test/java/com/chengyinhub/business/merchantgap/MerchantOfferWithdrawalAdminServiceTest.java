package com.chengyinhub.business.merchantgap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** /business/offer-withdrawal/list: read-only ledger over merchant_offer_withdrawal_request. */
class MerchantOfferWithdrawalAdminServiceTest {
    MerchantGapH2 db;
    MerchantOfferWithdrawalAdminService service;

    @BeforeEach
    void setUp() throws Exception {
        db = MerchantGapH2.create();
        insert(1, 8001, 12, "5b8f1d2a-0c3e-4f6a-9b7c-1d2e3f4a5b61", "WITHDRAWN", 1000);
        insert(2, 8001, 13, "5b8f1d2a-0c3e-4f6a-9b7c-1d2e3f4a5b62", "REJECTED_STATE", 2000);
        insert(3, 8002, 14, "5b8f1d2a-0c3e-4f6a-9b7c-1d2e3f4a5b63", "WITHDRAWN", 3000);
        service = new MerchantOfferWithdrawalAdminService(db.jdbc);
    }

    private void insert(long id, long merchant, long offer, String requestId, String outcome, long completedAt) {
        boolean ok = "WITHDRAWN".equals(outcome);
        db.jdbc.update("INSERT INTO merchant_offer_withdrawal_request(id, actor_id, merchant_id, owner_id, offer_id, request_id, outcome,"
                        + " intent_sha256, topic_id, chapter_id, status_at_write, completed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, 9002L, merchant, 9002L, offer, requestId, outcome, "a".repeat(64), ok ? 30L : null, ok ? 31L : null, ok ? 0 : null, completedAt);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("rows");
    }

    @Test
    void listsNewestFirstWithCountsPerOutcome() {
        Map<String, Object> page = service.list(null, null, null, null, null);
        assertEquals(3L, page.get("total"));
        assertEquals(List.of(3L, 2L, 1L), rows(page).stream().map(r -> r.get("id")).toList());
        Map<?, ?> counts = (Map<?, ?>) page.get("counts");
        assertEquals(2L, counts.get("WITHDRAWN"));
        assertEquals(1L, counts.get("REJECTED_STATE"));
        assertEquals(0L, counts.get("REJECTED_INPUT"));
        Map<String, Object> first = rows(page).get(0);
        assertEquals("5b8f1d2a-0c3e-4f6a-9b7c-1d2e3f4a5b63", first.get("requestId"));
        assertEquals(30L, first.get("topicId"));
        assertEquals(3000L, first.get("completedAt"));
    }

    @Test
    void filtersByMerchantOfferAndOutcomeAndPages() {
        assertEquals(2L, service.list(8001L, null, null, null, null).get("total"));
        assertEquals(1L, service.list(8001L, 13L, null, null, null).get("total"));
        assertEquals(2L, service.list(null, null, "WITHDRAWN", null, null).get("total"));
        Map<String, Object> second = service.list(null, null, null, 2, 2);
        assertEquals(3L, second.get("total"));
        assertEquals(List.of(1L), rows(second).stream().map(r -> r.get("id")).toList());
    }

    @Test
    void rejectsUnknownOutcomeAndNonPositiveIds() {
        assertEquals(400, assertThrows(MerchantGapException.class, () -> service.list(null, null, "DROP", null, null)).getStatus());
        assertEquals(400, assertThrows(MerchantGapException.class, () -> service.list(0L, null, null, null, null)).getStatus());
        assertEquals(400, assertThrows(MerchantGapException.class, () -> service.list(null, -1L, null, null, null)).getStatus());
    }

    @Test
    void neverWrites() {
        service.list(null, null, null, 1, 100);
        assertEquals(3, db.jdbc.queryForObject("SELECT COUNT(1) FROM merchant_offer_withdrawal_request", Integer.class));
    }
}
