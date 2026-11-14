package com.chengyinhub.business.merchantgap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.service.IMerchantOfferService;

/** offer-withdrawal/request/{current,result,withdraw}. */
class MerchantOfferWithdrawalRequestServiceTest {
    static final long OWNER = 9002L, STORE = 8001L, OFFER = 12L;
    static final String RID = "5b8f1d2a-0c3e-4f6a-9b7c-1d2e3f4a5b6c";

    MerchantGapH2 db;
    IMerchantOfferService offers;
    MerchantOfferWithdrawalRequestService service;

    @BeforeEach
    void setUp() throws Exception {
        db = MerchantGapH2.create(
                "CREATE TABLE merchant_chapter_offer (id BIGINT PRIMARY KEY, topic_id BIGINT, chapter_id BIGINT, merchant_id BIGINT, perk_id BIGINT, terms_mode VARCHAR(16), per_head_fee DECIMAL(10,2), quota_total INT, quota_used INT, valid_end DATETIME, status INT, withdraw_reason VARCHAR(500), withdraw_required TINYINT, update_time DATETIME)",
                "CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, lifecycle INT, user_status INT, product_type INT, circle_theme_code VARCHAR(32), del_flag INT)",
                "CREATE TABLE cms_topic_chapter (id BIGINT PRIMARY KEY, topic_id BIGINT, required INT)",
                "INSERT INTO merchant_chapter_offer VALUES (12, 30, 31, 9002, NULL, 'PERK', 12.50, 10, 2, '2026-12-31 00:00:00', 1, NULL, NULL, '2026-11-01 10:00:00')",
                "INSERT INTO cms_topic VALUES (30, 3, 1, 2, NULL, 0)",
                "INSERT INTO cms_topic_chapter VALUES (31, 30, 1)");
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(eq(OWNER), anyString())).thenReturn(new MerchantAccessContext(OWNER, STORE, OWNER, MerchantRoleCode.MERCHANT_OWNER));
        offers = mock(IMerchantOfferService.class);
        doAnswer(inv -> {
            db.jdbc.update("UPDATE merchant_chapter_offer SET status = 0, withdraw_reason = ?, withdraw_required = 1, update_time = '2026-11-14 09:00:00' WHERE id = ?",
                    new Object[] {inv.getArgument(2), inv.getArgument(0)});
            db.jdbc.update("UPDATE cms_topic SET user_status = 0 WHERE id = 30");
            return true;
        }).when(offers).withdrawOwnChapterOffer(eq(OFFER), eq(OWNER), anyString());
        service = new MerchantOfferWithdrawalRequestService(access, offers, db.jdbc, db.transactions);
    }

    @Test
    void appleSortedJsonMatchesFoundationOutput() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("reason", "店休/装修");
        f.put("offerId", 12L);
        f.put("expectedSHA256", "ab");
        f.put("reasonSHA256", "cd");
        assertEquals("{\"expectedSHA256\":\"ab\",\"offerId\":12,\"reason\":\"店休\\/装修\",\"reasonSHA256\":\"cd\"}", MerchantGapSupport.appleSortedJson(f));
    }

    @Test
    void currentIsTheCallersOwnOrdinaryOfferWithASnapshotHash() {
        Map<String, Object> current = service.current(OWNER, STORE, OFFER);
        assertEquals(1, current.get("receiptVersion"));
        assertEquals(STORE, current.get("merchantId"));
        assertEquals(OWNER, current.get("ownerId"));
        assertEquals(30L, current.get("topicId"));
        assertEquals(true, current.get("isOrdinary"));
        assertEquals(true, current.get("canWithdraw"));
        assertEquals(true, current.get("requiredByOriginalRule"));
        assertEquals("12.50", current.get("perHeadFee"));
        assertEquals(64, ((String) current.get("snapshotSHA256")).length());
        assertEquals(403, assertThrows(MerchantGapException.class, () -> service.current(OWNER, 9999L, OFFER)).getStatus());
        assertEquals(404, assertThrows(MerchantGapException.class, () -> service.current(OWNER, STORE, 404L)).getStatus());
    }

    Map<String, Object> body(String reason, String expected) {
        String reasonHash = MerchantGapSupport.sha256(reason);
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("offerId", OFFER);
        intent.put("reason", reason);
        intent.put("reasonSHA256", reasonHash);
        intent.put("expectedSHA256", expected);
        Map<String, Object> body = new LinkedHashMap<>(intent);
        body.put("merchantId", STORE);
        body.put("requestId", RID);
        body.put("intentSHA256", MerchantGapSupport.sha256(MerchantGapSupport.appleSortedJson(intent)));
        return body;
    }

    @Test
    void withdrawStoresWithdrawnAndReplaysWithoutASecondWrite() {
        String expected = (String) service.current(OWNER, STORE, OFFER).get("snapshotSHA256");
        Map<String, Object> receipt = service.withdraw(OWNER, body("店休/装修", expected));
        assertEquals("WITHDRAWN", receipt.get("outcome"));
        assertEquals(0, receipt.get("statusAtWrite"));
        assertEquals(1, receipt.get("requiredAtWrite"));
        assertEquals(30L, receipt.get("topicId"));
        assertEquals(receipt.get("intentSHA256"), receipt.get("receivedSHA256"));
        assertEquals(receipt.get("reasonSHA256"), receipt.get("receivedReasonSHA256"));
        assertNotNull(receipt.get("completedAt"));
        assertEquals(receipt, service.withdraw(OWNER, body("店休/装修", expected)));
        verify(offers, times(1)).withdrawOwnChapterOffer(eq(OFFER), eq(OWNER), anyString());
        assertEquals(receipt, service.result(OWNER, STORE, OFFER, RID));
    }

    @Test
    void staleSnapshotIsRejectedChangedAndNothingIsWritten() {
        Map<String, Object> receipt = service.withdraw(OWNER, body("店休", "0".repeat(64)));
        assertEquals("REJECTED_CHANGED", receipt.get("outcome"));
        assertNull(receipt.get("topicId"));
        verify(offers, never()).withdrawOwnChapterOffer(any(), any(), any());
    }

    @Test
    void tamperedIntentIsRejectedInput() {
        String expected = (String) service.current(OWNER, STORE, OFFER).get("snapshotSHA256");
        Map<String, Object> body = body("店休", expected);
        body.put("reason", "别的原因");
        assertEquals("REJECTED_INPUT", service.withdraw(OWNER, body).get("outcome"));
        verify(offers, never()).withdrawOwnChapterOffer(any(), any(), any());
    }

    @Test
    void resultOfAnUnseenRequestIsUnknown() {
        Map<String, Object> unknown = service.result(OWNER, STORE, OFFER, RID);
        assertEquals("UNKNOWN", unknown.get("outcome"));
        assertEquals(RID, unknown.get("requestId"));
        assertEquals(400, assertThrows(MerchantGapException.class, () -> service.result(OWNER, STORE, OFFER, RID.toUpperCase())).getStatus());
    }
}
